package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import java.io.File;
import java.nio.file.Files;

/**
 * A file that is not a GARC is not read as one, and nothing is written back in a layout the
 * writer was not built for (F054).
 *
 * <p>WHY THIS SUITE EXISTS. The reader read the magic, the version and the rest of the header and
 * threw them away, then assumed version 4: any file long enough parsed into invented entries, and a
 * version-6 archive (the Sun/Moon revision the architecture plans for) or one whose entries are
 * folders would be written back corrupt by a writer that copies a version-4 header and writes one
 * file per entry.
 *
 * <p>ORDER: needs no dump and no display; writes only under {@link Scratch}.
 *
 * Usage: java ctrmap.tests.GarcHeaderTest
 */
public class GarcHeaderTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dir = Scratch.dir("ctrmap_garc_header");
		File staged = new File(dir, "staged");
		staged.mkdirs();

		File good = garc(dir, "good");
		check(new GARC(good).length == 2, "a version-4 GARC written by hand opens, two entries");
		check(prepares(good, staged) == null, "and can be packed back");

		File notGarc = garc(dir, "notgarc");
		patch(notGarc, 0, new byte[]{'X', 'X', 'X', 'X'});
		String said = opens(notGarc);
		check(said != null && said.contains("is not a GARC") && said.contains("CRAG"),
				"a file whose magic is not CRAG is refused, not read into invented entries: " + said);

		File v6 = garc(dir, "v6");
		patch(v6, 10, new byte[]{0x00, 0x06});
		check(opens(v6) == null, "a version-6 header is still read");
		String writes = prepares(v6, staged);
		check(writes != null && writes.contains("version"),
				"but it is not written back with a version-4 header: " + writes);

		File folder = garc(dir, "folder");
		//entry 1's FATB record: its flags word says two files' worth of bits, not one
		int n = 2, fatb = 0x1C + 0xC + 4 * n;
		patch(folder, fatb + 0xC + 16, new byte[]{2, 0, 0, 0});
		check(opens(folder) == null, "an archive with a folder entry is still read");
		String flattens = prepares(folder, staged);
		check(flattens != null && flattens.contains("more than one file"),
				"but it is not written back flattened: " + flattens);

		oneWriterForBoth(dir, staged);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/**
	 * The repack and the exact rebuild write one layout (F056). They were two writers agreeing on
	 * every convention by hand, and both wrote the FATB length twelve short of the section - so the
	 * rebuild could not trust its own output's field. An archive laid out here by hand, to the
	 * format, comes back from a repack that changes nothing byte for byte; the rebuild of the same
	 * entries writes the same bytes; and the FATB length in what was written reaches its FIMB.
	 */
	static void oneWriterForBoth(File dir, File staged) throws Exception {
		System.out.println("--- the repack and the rebuild write one layout, to the format (F056)");
		//4-ALIGNED ENTRIES: the program's writers have always closed a FATB record at the PADDED end,
		//game-validated, where the hand layout closes it at the entry's own end; the reader reads the
		//length, not the end. Entries that need no padding leave that one convention out of the comparison.
		File hand = new File(dir, "hand");
		HandedGameTest.writeGarc(hand, "eight by".getBytes("US-ASCII"), "twelve bytes".getBytes("US-ASCII"));
		byte[] laid = Files.readAllBytes(hand.toPath());
		File repacked = new File(hand.getPath() + "_new");
		check(prepares(hand, staged) == null && repacked.isFile(), "a repack that changes nothing is written");
		byte[] repack = Files.readAllBytes(repacked.toPath());
		check(java.util.Arrays.equals(repack, laid), "and it is the hand-laid archive byte for byte ("
				+ firstDifference(repack, laid) + ")");
		GARC g = new GARC(hand);
		java.util.List<byte[]> entries = new java.util.ArrayList<>();
		for (int i = 0; i < g.length; i++) {
			entries.add(g.getStoredEntry(i));
		}
		File rebuilt = new File(dir, "rebuilt");
		ctrmap.formats.garc.GarcRebuilder.write(hand, rebuilt, entries);
		byte[] rebuild = Files.readAllBytes(rebuilt.toPath());
		check(java.util.Arrays.equals(rebuild, repack), "the rebuild of the same entries writes the same bytes ("
				+ firstDifference(rebuild, repack) + ")");
		int fatb = 0x1C + i32(rebuild, 0x1C + 4);
		int fimb = fatb + 0xC + 16 * i32(rebuild, fatb + 8);
		check(fatb + i32(rebuild, fatb + 4) == fimb && rebuild[fimb] == 'B' && rebuild[fimb + 1] == 'M',
				"the FATB's length is its section's, so it reaches the FIMB (FATB at 0x" + Integer.toHexString(fatb)
				+ ", length 0x" + Integer.toHexString(i32(rebuild, fatb + 4)) + ", FIMB at 0x" + Integer.toHexString(fimb) + ")");
		//THE HINT RAISED BY A BIGGER ENTRY (the 2026-10-16 assembly, GarcRebuilder 48, flip-return, SURVIVED: every
		//rebuild here wrote the template's own entries, so the hint never had to move). The game sizes a read buffer
		//from it; an entry above it is read past that buffer's end
		int before = i32(laid, 0x18);
		entries.add(new byte[4096]);
		File grown = new File(dir, "grown");
		ctrmap.formats.garc.GarcRebuilder.write(hand, grown, entries);
		int hint = i32(Files.readAllBytes(grown.toPath()), 0x18);
		check(before < 4096 && hint >= 4096, "a rebuild holding an entry larger than the template's largest-entry hint"
				+ " raises the hint to it (" + before + " -> " + hint + " for a 4096-byte entry)");
	}

	static String firstDifference(byte[] a, byte[] b) {
		for (int i = 0; i < Math.min(a.length, b.length); i++) {
			if (a[i] != b[i]) {
				return "first difference at 0x" + Integer.toHexString(i);
			}
		}
		return a.length == b.length ? "identical" : "lengths " + a.length + " and " + b.length;
	}

	static int i32(byte[] b, int at) {
		return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
	}

	static File garc(File dir, String name) throws Exception {
		File f = new File(dir, name);
		HandedGameTest.writeGarc(f, "entry zero".getBytes("US-ASCII"), "entry one".getBytes("US-ASCII"));
		return f;
	}

	static void patch(File f, int at, byte[] bytes) throws Exception {
		byte[] b = Files.readAllBytes(f.toPath());
		System.arraycopy(bytes, 0, b, at, bytes.length);
		Files.write(f.toPath(), b);
	}

	/** Null when the file opens as a GARC; the refusal otherwise. */
	static String opens(File f) {
		try {
			new GARC(f);
			return null;
		} catch (RuntimeException refused) {
			return refused.getMessage();
		}
	}

	/** Null when a pack of it would be written; the refusal otherwise. */
	static String prepares(File f, File staged) {
		try {
			new GARC(f).prepareRepack(staged, x -> false, null, new File(f.getPath() + "_new"));
			return null;
		} catch (Exception refused) {
			return refused.getMessage();
		}
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}
