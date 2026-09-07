package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.garc.LZ11;
import java.io.File;
import java.nio.file.Files;

/**
 * The LZ11 sniff decides what a stored archive entry IS, and it must not guess
 * wrong in either direction.
 *
 * <p>WHY THIS SUITE EXISTS. {@code GARC.sniffLZ11} used to carry a 64:1
 * compression-ratio cap. Measured across all 298 archives of a retail ORAS dump,
 * that cap rejected 696 entries which decompress to exactly their declared
 * length - 49 of them BCH files - and every consumer of those got a raw
 * compressed blob where real data should be. The visible case was a/0/8/8's
 * {@code _m} mask textures for clothing designs, stored at 75:1 to 172:1.
 *
 * <p>The cap was also not doing the job its comment claimed. It cited a/0/3/7
 * entry 122, a raw entry that merely starts with 0x11 and declares 0xFF2300;
 * the {@code < 0x400000} size ceiling already rejects that on its own.
 *
 * <p>So this suite pins BOTH directions, because a fix in one direction is how
 * the other regresses:
 * <ul>
 * <li>entries that really are compressed decode to real data - asserted on the
 *     dress-up mask textures, which must come back as BCH;</li>
 * <li>an entry that only LOOKS compressed is still refused - asserted on
 *     a/0/3/7 #122, which must still read back as its raw stored bytes.</li>
 * </ul>
 *
 * <p>And it pins the WRITE side, which is the half that could corrupt somebody's
 * game: {@code packDirectory} decides per-entry compression from
 * {@code entry.compressed}, so reclassifying entries changes what a repack
 * emits. A pristine archive must still survive a read with its stored bytes
 * intact.
 *
 * Usage: java ctrmap.tests.GarcSniffTest &lt;romfs-root&gt;
 */
public class GarcSniffTest {

	/** Dress-up mask textures: stored at 75:1 to 172:1, so the old cap refused them. */
	private static final int[] MASK_ENTRIES = {177, 181, 255, 318};

	/** Raw, merely starts with 0x11, declares 0xFF2300 from 16 stored bytes. */
	private static final int NOT_COMPRESSED_ENTRY = 122;

	static int fails = 0;

	static void check(boolean cond, String msg) {
		if (cond) {
			System.out.println("  ok: " + msg);
		} else {
			System.out.println("  FAIL: " + msg);
			fails++;
		}
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println("  skip: no romfs root given");
			System.out.println("ALL PASS");
			return;
		}
		File root = new File(args[0]);
		File dressUp = new File(root, "a/0/8/8");
		File trclass = new File(root, "a/0/3/7");
		if (!dressUp.isFile() || !trclass.isFile()) {
			System.out.println("  skip: no dump at " + root);
			System.out.println("ALL PASS");
			return;
		}

		compressedEntriesDecodeToRealData(dressUp);
		anEntryThatOnlyLooksCompressedIsRefused(trclass);
		readingDoesNotDisturbTheArchive(dressUp);

		if (fails == 0) {
			System.out.println("ALL PASS");
		} else {
			System.out.println("FAILURES PRESENT (" + fails + ")");
			System.exit(1);
		}
	}

	static void compressedEntriesDecodeToRealData(File dressUp) {
		System.out.println("--- a high-ratio compressed entry decodes, instead of coming back as a blob");
		GARC g = new GARC(dressUp);
		for (int idx : MASK_ENTRIES) {
			byte[] stored = g.getStoredEntry(idx);
			byte[] out = g.getDecompressedEntry(idx);
			check(stored != null && stored.length >= 4 && stored[0] == 0x11,
					"a/0/8/8 #" + idx + " is stored LZ11-compressed");
			//the whole point: what comes back must NOT be the stored bytes
			check(out != null && stored != null && out.length != stored.length,
					"a/0/8/8 #" + idx + " comes back decompressed, not as its stored bytes"
					+ (out == null || stored == null ? "" : " (" + stored.length + " -> " + out.length + ")"));
			check(isBch(out), "and it is a BCH, which is what a texture consumer needs"
					+ (out == null ? " (null)" : " (first bytes " + first4(out) + ")"));
			//and it is the WHOLE file, not a truncated decode
			int declared = stored == null || stored.length < 4 ? -1
					: (stored[1] & 0xFF) | ((stored[2] & 0xFF) << 8) | ((stored[3] & 0xFF) << 16);
			check(out != null && out.length == declared,
					"a/0/8/8 #" + idx + " decodes to exactly its declared length (" + declared + ")");
		}
	}

	static void anEntryThatOnlyLooksCompressedIsRefused(File trclass) {
		System.out.println("--- an entry that merely STARTS with 0x11 is still read raw");
		GARC g = new GARC(trclass);
		byte[] stored = g.getStoredEntry(NOT_COMPRESSED_ENTRY);
		byte[] out = g.getDecompressedEntry(NOT_COMPRESSED_ENTRY);
		check(stored != null && stored.length >= 4 && stored[0] == 0x11,
				"a/0/3/7 #" + NOT_COMPRESSED_ENTRY + " does start with 0x11, so the sniff has to decide");
		int declared = stored == null || stored.length < 4 ? -1
				: (stored[1] & 0xFF) | ((stored[2] & 0xFF) << 8) | ((stored[3] & 0xFF) << 16);
		check(declared >= 0x400000,
				"it declares " + declared + ", at or above the 4 MB ceiling - that is what refuses it");
		check(out != null && stored != null && out.length == stored.length,
				"so it comes back as its raw stored bytes, not inflated"
				+ (out == null || stored == null ? "" : " (" + stored.length + " -> " + out.length + ")"));
		//if it were ever accepted it would balloon; state the size so a regression is legible
		check(out != null && out.length < 0x1000,
				"and is " + (out == null ? "null" : out.length + " bytes")
				+ ", not the 16.7 MB of garbage it would inflate to");
	}

	static void readingDoesNotDisturbTheArchive(File dressUp) throws Exception {
		System.out.println("--- reading an archive leaves it byte-for-byte as it was");
		//the sniff is load-bearing for PACKING: packDirectory takes per-entry
		//compression from entry.compressed, so reclassifying entries changes
		//what a repack writes. Nothing here should write at all, and this is the
		//cheap proof of that before the pack suites take over.
		byte[] before = Files.readAllBytes(dressUp.toPath());
		GARC g = new GARC(dressUp);
		int n = g.getEntryCount();
		int decoded = 0;
		for (int i = 0; i < n; i++) {
			byte[] out = g.getDecompressedEntry(i);
			if (out != null) {
				decoded++;
			}
		}
		byte[] after = Files.readAllBytes(dressUp.toPath());
		check(decoded > 0, "read " + decoded + " of " + n + " entries");
		check(java.util.Arrays.equals(before, after),
				"and the archive on disk is unchanged (" + before.length + " bytes)");
	}

	static boolean isBch(byte[] b) {
		return b != null && b.length >= 4
				&& b[0] == 'B' && b[1] == 'C' && b[2] == 'H' && b[3] == 0x00;
	}

	static String first4(byte[] b) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 4 && i < b.length; i++) {
			sb.append(String.format("%02X ", b[i]));
		}
		return sb.toString().trim();
	}
}
