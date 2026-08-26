package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.gfcollision.GfColl;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Collision engine validation against EVERY retail collision subfile (all
 * regions, all layers):
 * (1) parse -> emitVerbatim must be BYTE-IDENTICAL (parse completeness);
 * (2) build(uniqueTris) must reproduce retail bucket membership EXACTLY
 *     (same triangle multiset per bucket) and the bounds block within float
 *     tolerance - proving the writer generates game-equivalent collision.
 *
 * Usage: java ctrmap.tests.GfCollTest <path-to-a039-garc>
 */
public class GfCollTest {

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/3/9");
		GARC garc = new GARC(garcFile);
		int files = 0, verbatimOk = 0, rebuildOk = 0, failures = 0;
		double worstBoundsErr = 0;
		for (int i = 0; i < garc.length; i++) {
			byte[] gr = garc.getDecompressedEntry(i);
			int subCount = (gr[2] & 0xFF) | ((gr[3] & 0xFF) << 8);
			//collision subfiles: 2 always; multi-layer: count 9 -> +8; count 11 -> +9,10
			int[] collSubs = subCount == 9 ? new int[]{2, 8} : subCount == 11 ? new int[]{2, 9, 10} : new int[]{2};
			for (int cs : collSubs) {
				byte[] coll = subfile(gr, cs);
				if (!GfColl.isColl(coll)) {
					continue;
				}
				files++;
				try {
					GfColl c = new GfColl(coll);
					// (1) verbatim byte-identity
					byte[] re = c.emitVerbatim();
					if (!Arrays.equals(re, coll)) {
						throw new IllegalStateException("verbatim mismatch (len " + re.length + " vs " + coll.length
								+ ", first diff " + firstDiff(re, coll) + ")");
					}
					verbatimOk++;

					// (2) rebuilt from unique tris == retail semantically
					byte[] rebuilt = GfColl.build(c.uniqueTris, c);
					GfColl r = new GfColl(rebuilt);
					for (int b = 0; b < 16; b++) {
						if (!sameTriMultiset(c.bucketTris[b], r.bucketTris[b])) {
							throw new IllegalStateException("bucket " + b + " membership differs (retail "
									+ c.bucketTris[b].length / 9 + " vs rebuilt " + r.bucketTris[b].length / 9 + " tris)");
						}
					}
					for (int w = 0; w < 640; w += 4) {
						float a = GfColl.f32(c.boundsRaw, w), q = GfColl.f32(r.boundsRaw, w);
						double err = Math.abs(a - q);
						worstBoundsErr = Math.max(worstBoundsErr, err);
						if (err > 0.1) {
							throw new IllegalStateException("bounds word " + (w / 4) + " differs: " + a + " vs " + q);
						}
					}
					rebuildOk++;
				} catch (RuntimeException ex) {
					failures++;
					System.out.println("FAIL region " + i + " sub " + cs + ": " + ex.getMessage());
					if (failures > 10) {
						System.out.println("too many failures, aborting");
						printSummary(files, verbatimOk, rebuildOk, failures, worstBoundsErr);
						System.exit(1);
					}
				}
			}
		}
		printSummary(files, verbatimOk, rebuildOk, failures, worstBoundsErr);
		if (failures > 0) {
			System.exit(1);
		}
	}

	private static void printSummary(int files, int verbatimOk, int rebuildOk, int failures, double worstBoundsErr) {
		System.out.println("\nGfColl: " + files + " collision files, verbatim=" + verbatimOk
				+ ", rebuild=" + rebuildOk + ", failures=" + failures
				+ String.format(", worst bounds err %.5f", worstBoundsErr));
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
	}

	private static boolean sameTriMultiset(float[] a, float[] b) {
		if (a.length != b.length) {
			return false;
		}
		Map<Long, Integer> count = new HashMap<>();
		for (int t = 0; t < a.length / 9; t++) {
			count.merge(key(a, t * 9), 1, Integer::sum);
		}
		for (int t = 0; t < b.length / 9; t++) {
			Integer c = count.get(key(b, t * 9));
			if (c == null || c == 0) {
				return false;
			}
			count.put(key(b, t * 9), c - 1);
		}
		return true;
	}

	private static long key(float[] a, int off) {
		long h = 1125899906842597L;
		for (int i = 0; i < 9; i++) {
			h = 31 * h + Float.floatToIntBits(a[off + i]);
		}
		return h;
	}

	private static int firstDiff(byte[] a, byte[] b) {
		int m = Math.min(a.length, b.length);
		for (int i = 0; i < m; i++) {
			if (a[i] != b[i]) {
				return i;
			}
		}
		return a.length == b.length ? -1 : m;
	}

	private static byte[] subfile(byte[] c, int i) {
		int count = (c[2] & 0xFF) | ((c[3] & 0xFF) << 8);
		if (i >= count) {
			return null;
		}
		int o0 = le32(c, 4 + i * 4), o1 = le32(c, 4 + (i + 1) * 4);
		if (o0 < 0 || o1 > c.length || o1 < o0) {
			return null;
		}
		byte[] out = new byte[o1 - o0];
		System.arraycopy(c, o0, out, 0, out.length);
		return out;
	}

	private static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
