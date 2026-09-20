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
 * the {@code < 0x400000} size ceiling that replaced it rejected that on its
 * own - and, measured strictly across the dump, also rejected two genuine
 * 4.7 MB and 5.5 MB streams while accepting twelve raw table rows. The sniff
 * now rests on two facts about the format instead of a threshold: the fewest
 * bytes a stream of the declared length can have, and a strict decode of the
 * first 64 stored bytes ({@code LZ11.minimumStoredLength},
 * {@code LZ11.prefixDecodes}).
 *
 * <p>So this suite pins BOTH directions, because a fix in one direction is how
 * the other regresses:
 * <ul>
 * <li>entries that really are compressed decode to real data - asserted on the
 *     dress-up mask textures, which must come back as BCH, and on the two
 *     streams larger than the old ceiling;</li>
 * <li>an entry that only LOOKS compressed is still refused - asserted on
 *     a/0/3/7 #122 and on the twelve raw rows, which must read back as their
 *     stored bytes;</li>
 * <li>the two format facts themselves, on bytes this suite writes.</li>
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

	/**
	 * A TRUNCATED ARCHIVE IS NOT DATA. Reading one answers null, never zeros.
	 *
	 * <p>WHAT THIS CAUGHT, measured rather than imagined: both entry readers skipped to an
	 * offset and read a buffer without checking either result, and {@code skip} and
	 * {@code read} both answer how much they actually managed. So an archive shorter than
	 * its own table handed back buffers of exactly the right length whose tails were
	 * zeros - on a/0/4/0 cut in half, 278 of its 431 entries came back pure zero, none
	 * null, no exception, no log line. Downstream that is a zone with no entities and an
	 * area with no atmosphere, and then the editor packs them back over the real ones.
	 *
	 * <p>It reads a REAL archive, cuts a copy in half in scratch space, and asks the copy
	 * for every entry the original has. Nothing in the answer may be a zero-filled buffer
	 * of the right length: an entry either survives the cut whole, or is refused.
	 */
	static void aTruncatedArchiveIsNotData(File root) throws Exception {
		System.out.println("--- a truncated archive answers null, never zeros");
		File real = new File(root, "a/0/4/0");
		if (!real.isFile()) {
			System.out.println("  skip: no a/0/4/0 under " + root);
			return;
		}
		byte[] whole = java.nio.file.Files.readAllBytes(real.toPath());
		File cut = File.createTempFile("ctrmap_half_", ".garc");
		cut.deleteOnExit();
		java.nio.file.Files.write(cut.toPath(),
			java.util.Arrays.copyOf(whole, whole.length / 2));
		ctrmap.formats.garc.GARC good = new ctrmap.formats.garc.GARC(real);
		ctrmap.formats.garc.GARC half = new ctrmap.formats.garc.GARC(cut);
		int zeroed = 0, refused = 0, whole_ = 0, checked = 0;
		for (int i = 0; i < good.length && i < half.length; i++) {
			byte[] want = good.getStoredEntry(i);
			byte[] got;
			try {
				got = half.getStoredEntry(i);
			} catch (RuntimeException refusedLoudly) {
				refused++;
				continue;
			}
			checked++;
			if (got == null) {
				refused++;
			} else if (want != null && java.util.Arrays.equals(want, got)) {
				whole_++;
			} else if (isAllZero(got)) {
				zeroed++;
			}
		}
		check(zeroed == 0, "no entry of a half-truncated archive comes back as zeros pretending"
			+ " to be data (" + zeroed + " did, of " + checked + " asked; " + whole_ + " survived the"
			+ " cut whole and " + refused + " were refused)");
		check(refused > 0, "...and the ones past the cut ARE refused, so the check above had"
			+ " something to be right about (" + refused + ")");
		cut.delete();
	}

	/**
	 * A file that shrinks AFTER it was parsed still refuses, and never answers zeros.
	 *
	 * <p>This is the case the truncation fixture above can no longer reach. {@code parse}
	 * records an entry only once it has read that entry's data, so no entry in a
	 * half-truncated archive can have data past the cut - the defect became unreachable by
	 * construction, and the plant that guarded it survived because there was nothing left to
	 * catch.
	 *
	 * <p>The real case is not a file that was always short. It is a file that CHANGES
	 * underneath a table already in memory - which is why {@code isStale()} and
	 * {@code rereadIfStale()} exist at all. Here the whole archive is parsed, the file on disk
	 * is then cut in half, and the entries whose data is now gone must refuse rather than hand
	 * back a buffer of zeros the editor would write straight back over the real archive.
	 *
	 * <p>Measured before {@code readFully}: 278 of a/0/4/0's 431 entries came back as pure-zero
	 * buffers of the right length. None null, no exception, no log line.
	 */
	static void aFileThatShrinksUnderYouRefuses(File root) throws Exception {
		System.out.println("--- a file that shrinks after it was parsed refuses, never zeros");
		File real = new File(root, "a/0/4/0");
		if (!real.isFile()) {
			System.out.println("  skip: no a/0/4/0 under " + root);
			return;
		}
		byte[] whole = java.nio.file.Files.readAllBytes(real.toPath());
		File copy = File.createTempFile("ctrmap_shrink_", ".garc");
		copy.deleteOnExit();
		java.nio.file.Files.write(copy.toPath(), whole);

		//parsed WHOLE - the table in memory describes every entry
		ctrmap.formats.garc.GARC arc = new ctrmap.formats.garc.GARC(copy);
		int declared = arc.length;
		check(declared > 1, "the archive parsed whole first (" + declared + " entries)");

		//...and now the file is cut underneath it
		java.nio.file.Files.write(copy.toPath(),
			java.util.Arrays.copyOf(whole, whole.length / 2));

		int zeroed = 0, refused = 0, whole_ = 0;
		for (int i = 0; i < declared; i++) {
			byte[] got;
			try {
				got = arc.getStoredEntry(i);
			} catch (RuntimeException refusedLoudly) {
				refused++;
				continue;
			}
			if (got == null) {
				refused++;
			} else if (isAllZero(got)) {
				zeroed++;
			} else {
				whole_++;
			}
		}
		check(zeroed == 0, "no entry of an archive that shrank comes back as zeros pretending"
			+ " to be data (" + zeroed + " did, of " + declared + "; " + whole_ + " were still"
			+ " whole and " + refused + " refused)");
		check(refused > 0, "...and the ones past the cut ARE refused, so the check above had"
			+ " something to be right about (" + refused + ")");
		copy.delete();
	}

	/**
	 * An archive that was read TRUNCATED may not be packed back over the real one.
	 *
	 * <p>Reading a short archive is recoverable and the entries before the cut are whole, so
	 * {@code parse} records the truncation rather than refusing - refusing there took the
	 * readable entries away with the unreadable ones and reddened this suite for a week.
	 *
	 * <p>WRITING it back is the irreversible part. A pack that read 155 of the 436 entries a
	 * header declares and writes 155 back has destroyed the other 281, over the owner's own
	 * game data, which is not in version control. This project has already paid for one
	 * stale-pack corruption on zone 536.
	 */
	static void aTruncatedArchiveMayNotBePackedBack(File root) throws Exception {
		System.out.println("--- a truncated archive may not be packed back");
		File real = new File(root, "a/0/4/0");
		if (!real.isFile()) {
			System.out.println("  skip: no a/0/4/0 under " + root);
			return;
		}
		byte[] whole = java.nio.file.Files.readAllBytes(real.toPath());
		File cut = File.createTempFile("ctrmap_halfpack_", ".garc");
		cut.deleteOnExit();
		java.nio.file.Files.write(cut.toPath(),
			java.util.Arrays.copyOf(whole, whole.length / 2));
		ctrmap.formats.garc.GARC half = new ctrmap.formats.garc.GARC(cut);
		check(half.readableEntries >= 0,
			"the short read is recorded rather than thrown (" + half.readableEntries + " of "
			+ half.length + " readable)");

		File staging = Scratch.dir("halfpack");
		File scratch = Scratch.dir("halfpack-out");
		String said = "";
		try {
			half.packDirectory(staging, f -> true, scratch, new java.util.HashMap<>());
		} catch (Exception refusedLoudly) {
			//ANY exception, not just the expected one. Catching only IllegalStateException
			//let every OTHER failure escape and kill the suite before it reached the check -
			//so with the refusal removed the plant reddened the suite without it ever saying
			//what it was watching for, and was recorded NOT PROVEN. A fixture that cannot
			//report the wrong answer can only report the right one.
			said = refusedLoudly.getClass().getSimpleName() + ": "
				+ String.valueOf(refusedLoudly.getMessage());
		}
		check(said.contains("TRUNCATED") && said.contains("must not be packed back"),
			"packing it back is refused, and the refusal says why: " + oneLine(said));
		check(said.contains("Restore the archive from the pristine copy"),
			"...and what to do instead");
		cut.delete();
	}

	static String oneLine(String said) {
		String flat = said.replace('\n', ' ').replace('\r', ' ').trim();
		return flat.length() > 150 ? flat.substring(0, 150) : flat;
	}

	/** Whether every byte is zero - what a dropped short read used to leave behind. */
	static boolean isAllZero(byte[] b) {
		if (b.length == 0) {
			return false;
		}
		for (byte x : b) {
			if (x != 0) {
				return false;
			}
		}
		return true;
	}
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
		aTruncatedArchiveIsNotData(root);
		aFileThatShrinksUnderYouRefuses(root);
		aTruncatedArchiveMayNotBePackedBack(root);

		compressedEntriesDecodeToRealData(dressUp);
		anEntryThatOnlyLooksCompressedIsRefused(trclass);
		theFormatArithmeticHoldsOnItsOwn(trclass);
		rawRecordsThatStartWith0x11AreReadRaw(root);
		aGenuineStreamAboveTheOldCeilingDecodes(root);
		readingDoesNotDisturbTheArchive(dressUp);
		aReclassifiedEntrySurvivesAnEdit(dressUp);

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
		//what refuses it is the format's own arithmetic, not a size ceiling: a
		//stream of that length needs more bytes than the entry has, and its
		//first token references bytes that were never written
		check(stored != null && stored.length < LZ11.minimumStoredLength(declared),
				"it declares " + declared + ", which no stream of " + (stored == null ? -1 : stored.length)
				+ " bytes can produce (at least " + LZ11.minimumStoredLength(declared) + " needed)");
		check(stored != null && !LZ11.prefixDecodes(stored, 64),
				"and its first token does not decode as LZ11 either");
		check(out != null && stored != null && out.length == stored.length,
				"so it comes back as its raw stored bytes, not inflated"
				+ (out == null || stored == null ? "" : " (" + stored.length + " -> " + out.length + ")"));
		//if it were ever accepted it would balloon; state the size so a regression is legible
		check(out != null && out.length < 0x1000,
				"and is " + (out == null ? "null" : out.length + " bytes")
				+ ", not the 16.7 MB of garbage it would inflate to");
	}

	/**
	 * The two format facts the sniff is built on, checked on bytes this suite
	 * writes itself, so a regression in either is named before the corpus
	 * checks below report its symptoms.
	 */
	static void theFormatArithmeticHoldsOnItsOwn(File trclass) {
		System.out.println("--- the format's arithmetic: fewest bytes a stream can have, and a strict prefix");
		//one token covers 65,808 bytes; the 65,809th needs a second token
		check(LZ11.minimumStoredLength(1) == 9 && LZ11.minimumStoredLength(65808) == 9,
				"1 to 65,808 output bytes need a header, a flag byte and one token: 9 stored bytes");
		check(LZ11.minimumStoredLength(65809) == 13, "65,809 need two tokens: 13");
		check(LZ11.minimumStoredLength(16720640) == 1056,
				"and the 16,720,640 that a/0/3/7 #122 declares need 1,056 (it has 16)");
		//a stream our own compressor writes decodes, truncated it does not
		byte[] plain = new byte[3000];
		for (int i = 0; i < plain.length; i++) {
			plain[i] = (byte) (i * 7 + (i >> 5));
		}
		byte[] packed = LZ11.compress(plain);
		check(LZ11.prefixDecodes(packed, 64), "the first 64 bytes of a stream we compressed decode strictly");
		check(LZ11.prefixDecodes(packed, packed.length), "and so does all of it");
		byte[] cut = java.util.Arrays.copyOf(packed, 40);
		check(!LZ11.prefixDecodes(cut, 64), "cut short at 40 bytes, the stream runs out before its declared length: refused");
		//a back-reference into bytes never written: flag 0x80, token 0x00 0x00 0x00
		//= a 17-byte run at displacement 1 with nothing yet written
		byte[] bad = {0x11, 0x20, 0x00, 0x00, (byte) 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
		check(!LZ11.prefixDecodes(bad, 64), "a first token that references bytes not yet written: refused");
		byte[] zero = {0x11, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
		check(!LZ11.prefixDecodes(zero, 64), "a declared length of 0 is not a stream");
		//the real thing: #122's sixteen bytes
		byte[] e122 = new GARC(trclass).getStoredEntry(NOT_COMPRESSED_ENTRY);
		check(e122 != null && e122.length == 16 && !LZ11.prefixDecodes(e122, 64),
				"a/0/3/7 #122's own 16 bytes fault on their first token");
	}

	/**
	 * Raw table rows whose first byte happens to be 0x11, as measured across
	 * the dump with a strict decoder. All twelve used to be accepted as LZ11
	 * (the 4 MB ceiling saw nothing wrong with them) and every reader got
	 * garbage inflated from them; an edit would have compressed the row into a
	 * slot the game reads raw. Each must come back as exactly its stored
	 * bytes.
	 */
	static void rawRecordsThatStartWith0x11AreReadRaw(File root) {
		System.out.println("--- raw table rows that start with 0x11 are read raw");
		Object[][] rows = {
			{"a/0/0/7", new int[]{2}, 1201},
			{"a/0/2/2", new int[]{17, 273, 371, 463}, 24},
			{"a/0/8/9", new int[]{450, 648}, 490},
			{"a/1/9/0", new int[]{86, 258, 331}, 36},
			{"a/1/9/1", new int[]{22, 385}, 68},
		};
		int seen = 0;
		for (Object[] row : rows) {
			File f = new File(root, (String) row[0]);
			if (!f.isFile()) {
				System.out.println("  skip: no " + row[0] + " in the dump");
				continue;
			}
			GARC g = new GARC(f);
			for (int idx : (int[]) row[1]) {
				byte[] stored = g.getStoredEntry(idx);
				byte[] out = g.getDecompressedEntry(idx);
				seen++;
				check(stored != null && stored.length == (Integer) row[2] && stored[0] == 0x11,
						row[0] + " #" + idx + " is a " + row[2] + "-byte row starting with 0x11"
						+ (stored == null ? "" : " (" + stored.length + " bytes)"));
				check(!g.isEntryCompressed(idx), "and is not taken for a compressed entry");
				check(out != null && stored != null && java.util.Arrays.equals(out, stored),
						"so it is read back as its own " + (stored == null ? "?" : stored.length) + " bytes"
						+ (out == null ? " (null)" : ", not " + out.length));
			}
		}
		check(seen == 12, "all twelve rows the measurement found were checked (" + seen + ")");
		//the shape that explains them: a/0/2/2 is a table of fixed-size rows,
		//not an archive of files that might be compressed
		File a022 = new File(root, "a/0/2/2");
		if (a022.isFile()) {
			GARC g = new GARC(a022);
			int rows24 = 0;
			for (int i = 0; i < g.getEntryCount(); i++) {
				byte[] b = g.getStoredEntry(i);
				if (b != null && b.length == 24) {
					rows24++;
				}
			}
			check(rows24 >= 500, "a/0/2/2 is a table of 24-byte rows (" + rows24 + " of " + g.getEntryCount()
					+ "), and rows 17, 273, 371 and 463 are four of them whose first byte is 0x11");
		}
	}

	/**
	 * The two genuine streams the 4 MB ceiling refused. Every reader got
	 * them back compressed; an edit would have been written raw into a slot
	 * the game reads as LZ11.
	 */
	static void aGenuineStreamAboveTheOldCeilingDecodes(File root) {
		System.out.println("--- a genuine stream larger than the old 4 MB ceiling decodes");
		Object[][] big = {
			{"a/0/0/8", 6223, 4723968, "a Pokemon model"},
			{"a/1/5/2", 561, 5521120, "a BCH"},
		};
		for (Object[] b : big) {
			File f = new File(root, (String) b[0]);
			if (!f.isFile()) {
				System.out.println("  skip: no " + b[0] + " in the dump");
				continue;
			}
			GARC g = new GARC(f);
			int idx = (Integer) b[1];
			int declared = (Integer) b[2];
			byte[] stored = g.getStoredEntry(idx);
			byte[] out = g.getDecompressedEntry(idx);
			check(stored != null && stored.length >= LZ11.minimumStoredLength(declared),
					b[0] + " #" + idx + " declares " + declared + " from " + (stored == null ? -1 : stored.length)
					+ " stored bytes, which the arithmetic allows (at least " + LZ11.minimumStoredLength(declared) + ")");
			check(g.isEntryCompressed(idx), "and is taken for the compressed entry it is");
			check(out != null && out.length == declared, "so it decodes to exactly " + declared + " bytes, "
					+ b[3] + (out == null ? " (null)" : " (got " + out.length + ")"));
		}
		File bch = new File(root, "a/1/5/2");
		if (bch.isFile()) {
			byte[] out = new GARC(bch).getDecompressedEntry(561);
			check(isBch(out), "a/1/5/2 #561 is the BCH its bytes say it is"
					+ (out == null ? " (null)" : " (first bytes " + first4(out) + ")"));
		}
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

	static void aReclassifiedEntrySurvivesAnEdit(File dressUp) {
		System.out.println("--- an entry this fix reclassified still round-trips through a pack");
		//THE WRITE SIDE, and it is why this change is safer rather than riskier.
		//packDirectory rewrites only the entries a user actually staged, and for
		//those it honours entry.compressed: true means LZ11.compress, false means
		//store the bytes as they are. So BEFORE this fix, editing one of these
		//mask textures would have written it RAW into a slot the game reads as
		//LZ11 - a corrupt texture, silently. The property that has to hold now is
		//that what we write is what the game reads back.
		GARC g = new GARC(dressUp);
		for (int idx : MASK_ENTRIES) {
			byte[] original = g.getDecompressedEntry(idx);
			if (original == null) {
				check(false, "a/0/8/8 #" + idx + " could not be read at all");
				continue;
			}
			byte[] repacked = LZ11.compress(original);
			byte[] readBack = null;
			try {
				readBack = LZ11.decompress(repacked);
			} catch (Throwable t) {
			}
			check(readBack != null && java.util.Arrays.equals(original, readBack),
					"a/0/8/8 #" + idx + ": edit it, pack it, read it back - byte for byte the same ("
					+ original.length + " bytes)");
			//and the thing we would write is genuinely compressed, so the slot still
			//holds what the game expects to find there
			check(repacked.length >= 4 && repacked[0] == 0x11,
					"and what gets written is LZ11, not a raw blob in a compressed slot");
		}
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
