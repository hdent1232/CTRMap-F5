package ctrmap.tests;

import ctrmap.formats.containers.GR;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.gfcollision.GRCollisionFile;
import ctrmap.formats.Triangle;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Validates the legacy-editor -> retail-exact-writer bridge: loading a
 * collision through GRCollisionFile (the render/edit model, which de-dupes
 * across buckets) and writing through the new GfColl path must preserve the
 * triangle SET exactly for every retail region, and the rebuilt subfile must
 * re-parse clean. This is the gate for the fixed "OBJ to collisions" and
 * collision-editor save paths.
 *
 * Usage: java ctrmap.tests.GfCollLegacyBridgeTest <path-to-a039-garc> [step]
 */
public class GfCollLegacyBridgeTest {

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/3/9");
		int step = args.length > 1 ? Integer.parseInt(args[1]) : 1;
		File scratch = new File(System.getProperty("java.io.tmpdir"), "ctrmap_collbridge");
		scratch.mkdirs();
		GARC garc = new GARC(garcFile);
		int tested = 0, ok = 0, failures = 0;
		long collapsedDupes = 0;
		for (int i = 0; i < garc.length; i += step) {
			byte[] entry = garc.getDecompressedEntry(i);
			int count = (entry[2] & 0xFF) | ((entry[3] & 0xFF) << 8);
			if (count < 3) {
				continue;
			}
			byte[] retail = sub(entry, 2);
			if (!GfColl.isColl(retail)) {
				continue;
			}
			tested++;
			try {
				File f = new File(scratch, "gr" + i + ".bin");
				try (FileOutputStream fos = new FileOutputStream(f)) {
					fos.write(entry);
				}
				GR gr = new GR(f);
				GRCollisionFile legacy = new GRCollisionFile(gr);
				legacy.write(); //the FIXED path - routes through GfColl.build
				byte[] rebuilt = new GR(f).getFile(2);

				GfColl a = new GfColl(retail), b = new GfColl(rebuilt);
				Set<Long> setA = triSet(a), setB = triSet(b);
				if (!setA.equals(setB)) {
					throw new IllegalStateException("triangle set differs (retail " + setA.size()
							+ " unique vs rebuilt " + setB.size() + ")");
				}
				collapsedDupes += a.uniqueTris.size() - b.uniqueTris.size();
				//bucket sanity: rebuilt must be internally consistent (parse ok, counts %3)
				for (int k = 0; k < 16; k++) {
					if (b.bucketCnt[k] % 3 != 0) {
						throw new IllegalStateException("bucket " + k + " count not a triangle multiple");
					}
				}
				ok++;
			} catch (RuntimeException ex) {
				failures++;
				System.out.println("FAIL region " + i + ": " + ex.getMessage());
				if (failures > 8) {
					break;
				}
			}
		}
		System.out.println("\nLegacy bridge: tested=" + tested + "  ok=" + ok + "  failures=" + failures
				+ "  (source-duplicate tris collapsed by the editor model: " + collapsedDupes + ")");
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (failures > 0) {
			System.exit(1);
		}
	}

	static Set<Long> triSet(GfColl c) {
		Set<Long> out = new HashSet<>();
		for (float[] t : c.uniqueTris) {
			long h = 1125899906842597L;
			for (int i = 0; i < 9; i++) {
				h = 31 * h + Float.floatToIntBits(t[i]);
			}
			out.add(h);
		}
		return out;
	}

	static byte[] sub(byte[] c, int i) {
		int count = (c[2] & 0xFF) | ((c[3] & 0xFF) << 8);
		if (i >= count) {
			return null;
		}
		int o0 = le32(c, 4 + i * 4), o1 = le32(c, 4 + (i + 1) * 4);
		if (o0 < 0 || o1 > c.length || o1 < o0) {
			return null;
		}
		return java.util.Arrays.copyOfRange(c, o0, o1);
	}

	static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
