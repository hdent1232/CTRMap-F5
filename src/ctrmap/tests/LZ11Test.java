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
			}
		} else {
			System.out.println("(corpus GARC not found at " + path + " - synthetic cases only)");
		}

		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT (" + failures + ")");
		if (failures > 0) {
			System.exit(1);
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
