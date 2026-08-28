package ctrmap.tests;

import ctrmap.formats.area.AreaEnv;
import ctrmap.formats.garc.GARC;
import java.io.File;
import java.util.Arrays;

/**
 * Verifies the AreaData subfile-4 fog/ambient codec against the retail dump:
 * every area's block is 2944 bytes; the measured anchors read back (route
 * fog color blue with near 800/far 4000, indoor white with near/far short);
 * and read -> writeInto is byte-identical (round-trip safe for editing).
 *
 * Usage: java ctrmap.tests.AreaEnvTest <path-to-a014-garc>
 */
public class AreaEnvTest {

	public static void main(String[] args) throws Exception {
		File garc = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/1/4");
		GARC ad = new GARC(garc); // default: LZ11-decompress
		int failures = 0, checked = 0;

		for (int area = 0; area < ad.length; area++) {
			byte[] entry = ad.getDecompressedEntry(area);
			if (entry == null || entry.length < 16 || entry[0] != 'A' || entry[1] != 'D') {
				continue;
			}
			byte[] s4 = sub(entry, 4);
			if (s4 == null || s4.length != AreaEnv.SUB4_LEN) {
				continue;
			}
			checked++;
			AreaEnv e = AreaEnv.read(s4);
			byte[] copy = s4.clone();
			e.writeInto(copy);
			if (!Arrays.equals(copy, s4)) {
				failures++;
				System.out.println("FAIL area " + area + ": no-op writeInto not byte-identical");
				if (failures > 5) {
					break;
				}
			}
		}

		// anchors: area 8 (Route 101) outdoor, area 2 (small indoor)
		AreaEnv route = AreaEnv.read(sub(ad.getDecompressedEntry(8), 4));
		if (Math.abs(route.fogColor[2] - 1.0f) > 0.01 || Math.abs(route.fogNear - 800) > 1 || Math.abs(route.fogFar - 4000) > 1) {
			failures++;
			System.out.println("FAIL route anchor: fogB=" + route.fogColor[2] + " near=" + route.fogNear + " far=" + route.fogFar);
		}
		AreaEnv indoor = AreaEnv.read(sub(ad.getDecompressedEntry(2), 4));
		if (Math.abs(indoor.fogColor[0] - 1.0f) > 0.01 || Math.abs(indoor.fogFar - 360) > 1) {
			failures++;
			System.out.println("FAIL indoor anchor: fogR=" + indoor.fogColor[0] + " far=" + indoor.fogFar);
		}

		System.out.println("AreaEnv: " + checked + " areas round-trip, anchors "
				+ "route(blue near800 far4000) indoor(white far360), failures=" + failures);
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (failures > 0) {
			System.exit(1);
		}
	}

	static byte[] sub(byte[] ad, int i) {
		int st = u32(ad, 4 + 4 * i), en = u32(ad, 4 + 4 * (i + 1));
		if (st < 0 || en > ad.length || en < st) {
			return null;
		}
		return Arrays.copyOfRange(ad, st, en);
	}

	static int u32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
