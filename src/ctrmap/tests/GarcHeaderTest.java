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

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
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
