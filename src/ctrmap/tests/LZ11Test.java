package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.garc.LZ11;
import java.io.File;
import java.util.Arrays;
import java.util.Random;

/**
 * LZ11 encoder validation:
 * (1) round-trip - compress(x) then decompress must equal x, over synthetic
 *     edge cases AND every decompressed entry of a real compressed GARC;
 * (2) ratio - the new encoder's output vs the retail-compressed size, to
 *     confirm the fix (the old length-15-capped port bloated everything).
 *
 * Usage: java ctrmap.tests.LZ11Test [path-to-a-compressed-garc]
 * Default GARC = a/0/1/3 (ZoneData; entries are LZ11-compressed).
 */
public class LZ11Test {

	public static void main(String[] args) throws Exception {
		int failures = 0;

		// (1a) synthetic edge cases
		byte[][] cases = {
			new byte[0],
			new byte[]{7},
			new byte[]{1, 2, 3},
			repeat((byte) 0xAB, 100000),          // long run -> exercises the 4-byte form
			ramp(70000),                           // periodic -> mid-length matches
			random(50000, 12345),                  // incompressible
			random(4097, 999),                     // just over the window
		};
		for (int i = 0; i < cases.length; i++) {
			byte[] c = LZ11.compress(cases[i]);
			byte[] d = LZ11.decompress(c);
			if (!Arrays.equals(d, cases[i])) {
				failures++;
				System.out.println("FAIL synthetic case " + i + " (len " + cases[i].length + ")");
			}
		}

		// (1b) THE RATIO, BOUNDED (F250): it was printed and never asserted, so a return to the
		// length-capped port this suite was written after would have passed. A 100000-byte run
		// needs a handful of the long match forms - tens of bytes; the port that capped a match
		// at 16 needs one two-byte token per 16 bytes plus flags, over 13000. 1000 parts them.
		int runOut = LZ11.compress(repeat((byte) 0xAB, 100000)).length;
		if (runOut > 1000) {
			failures++;
			System.out.println("FAIL a 100000-byte run compresses to " + runOut + " bytes - the long match forms are not used");
		} else {
			System.out.println("ok a 100000-byte run compresses to " + runOut + " bytes: the long match forms are used");
		}

		// (1c) STRICT (F057): what is not a well-formed stream is refused, never padded into data
		byte[] whole = LZ11.compress(ramp(5000));
		failures += refused("a truncated stream", Arrays.copyOf(whole, whole.length - 3), "ends after");
		byte[] wrongType = whole.clone();
		wrongType[0] = 0x10;
		failures += refused("a stream whose type byte is not 0x11", wrongType, "type byte");
		//one token, compressed, reaching six bytes back before anything is written
		failures += refused("a back-reference before the start", new byte[]{0x11, 0x10, 0, 0, (byte) 0x80, 0x20, 0x05},
				"refers 6 byte(s) back");
		failures += refused("a header with nothing after it", new byte[]{0x11, 5, 0, 0}, "ends after");
		//MEASURED by the 2026-10-14 shared sweep (LZ11 29, dropped, SURVIVED): nothing shorter than a header had been
		//handed in - dropped, no stream was a NullPointerException and 3 bytes of 0x11 read past their end
		failures += refused("no stream at all", null, "Not an LZ11 stream: it needs a 4-byte header and is absent.");
		failures += refused("a stream of 3 bytes", new byte[]{0x11, 0, 0},
				"Not an LZ11 stream: it needs a 4-byte header and is 3 byte(s).");
		//and the extended header's two (LZ11 40, dropped, SURVIVED the same day; 45 its sibling): 5 bytes cannot hold
		//the 4-byte size, and a size past what an array can hold is said rather than read as a negative length
		failures += refused("an extended header cut to 5 bytes", new byte[]{0x11, 0, 0, 0, 1},
				"An LZ11 stream with an extended size header needs 8 header bytes; this is 5.");
		failures += refused("an extended header declaring 4294967295 bytes",
				new byte[]{0x11, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
				"An LZ11 stream declares 4294967295 bytes, more than this program can hold.");
		//the extended header: a three-byte size of zero, then the size in four bytes
		byte[] extended = {0x11, 0, 0, 0, 3, 0, 0, 0, 0x00, 'a', 'b', 'c'};
		if (!Arrays.equals(LZ11.decompress(extended), new byte[]{'a', 'b', 'c'})) {
			failures++;
			System.out.println("FAIL the extended size header is not read");
		} else {
			System.out.println("ok the extended size header is read");
		}
		try {
			LZ11.compress(new byte[0x1000000]);
			failures++;
			System.out.println("FAIL compressing 16 MB wrote a header that cannot say its size");
		} catch (IllegalArgumentException refusedSize) {
			System.out.println("ok compressing 16 MB is refused: " + refusedSize.getMessage());
		}

		// (1b)+(2) real corpus
		String path = args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/1/3";
		File f = new File(path);
		if (f.exists()) {
			GARC garc = new GARC(f);
			long retailComp = 0, oursComp = 0, raw = 0;
			int rtOk = 0, compared = 0;
			for (int i = 0; i < garc.length; i++) {
				byte[] dec = garc.getDecompressedEntry(i);
				if (dec == null || dec.length == 0) {
					continue;
				}
				byte[] c = LZ11.compress(dec);
				byte[] back = LZ11.decompress(c);
				if (!Arrays.equals(back, dec)) {
					failures++;
					System.out.println("FAIL corpus entry " + i + " round-trip");
					if (failures > 8) {
						break;
					}
					continue;
				}
				rtOk++;
				raw += dec.length;
				oursComp += c.length;
				if (garc.isEntryCompressed(i)) {
					retailComp += garc.getEntryStoredLength(i);
					compared++;
				}
			}
			System.out.println("\ncorpus: " + rtOk + " entries round-trip OK");
			if (compared > 0) {
				double ratioVsRaw = 100.0 * oursComp / raw;
				double vsRetail = 100.0 * oursComp / retailComp;
				System.out.printf("ratio: ours=%.1f%% of raw; ours is %.1f%% of retail-compressed size (%d entries compared)%n",
						ratioVsRaw, vsRetail, compared);
				//a corpus the game ships compressed must come out smaller than it went in (F250)
				if (oursComp >= raw) {
					failures++;
					System.out.println("FAIL the corpus did not shrink: " + oursComp + " bytes out of " + raw);
				}
			} else {
				failures++;
				System.out.println("FAIL the corpus held no retail-compressed entry to compare against");
			}
		} else {
			Skip.section("corpus GARC not found at " + path + " - synthetic cases only");
		}

		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT (" + failures + ")");
		if (failures > 0) {
			System.exit(1);
		}
	}

	/** 0 when {@code stream} is refused with a message holding {@code why}; 1 (a failure) otherwise. */
	static int refused(String what, byte[] stream, String why) {
		try {
			byte[] got = LZ11.decompress(stream);
			System.out.println("FAIL " + what + " is refused (it decoded to " + got.length + " bytes)");
			return 1;
		} catch (IllegalStateException said) {
			boolean named = said.getMessage().contains(why);
			System.out.println((named ? "ok " : "FAIL ") + what + " is refused: " + said.getMessage());
			return named ? 0 : 1;
		} catch (RuntimeException crash) {
			System.out.println("FAIL " + what + " is refused in words (it threw " + crash + ")");
			return 1;
		}
	}

	static byte[] repeat(byte v, int n) {
		byte[] b = new byte[n];
		Arrays.fill(b, v);
		return b;
	}

	static byte[] ramp(int n) {
		byte[] b = new byte[n];
		for (int i = 0; i < n; i++) {
			b[i] = (byte) (i % 37);
		}
		return b;
	}

	static byte[] random(int n, long seed) {
		byte[] b = new byte[n];
		new Random(seed).nextBytes(b);
		return b;
	}
}
