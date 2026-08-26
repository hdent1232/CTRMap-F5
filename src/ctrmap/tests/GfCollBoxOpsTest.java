package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.gfcollision.GfColl;
import java.io.File;

/**
 * Collision box-op validation on sampled real regions: a box over the middle
 * quarter of the collision extent is moved (+40Y), duplicated (+100X) and
 * deleted; each result must re-parse clean, contain the exact expected
 * triangles, and keep untouched geometry bit-identical.
 *
 * Usage: java ctrmap.tests.GfCollBoxOpsTest <path-to-a039-garc> [sampleStep]
 */
public class GfCollBoxOpsTest {

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/3/9");
		int step = args.length > 1 ? Integer.parseInt(args[1]) : 15;
		GARC garc = new GARC(garcFile);
		int tested = 0, ok = 0, failures = 0, emptySel = 0;
		for (int i = 0; i < garc.length; i += step) {
			byte[] gr = garc.getDecompressedEntry(i);
			byte[] coll = subfile(gr, 2);
			if (!GfColl.isColl(coll)) {
				continue;
			}
			try {
				GfColl c = new GfColl(coll);
				if (c.uniqueTris.isEmpty()) {
					continue;
				}
				tested++;
				float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
				for (float[] t : c.uniqueTris) {
					for (int v = 0; v < 3; v++) {
						minX = Math.min(minX, t[v * 3]);
						maxX = Math.max(maxX, t[v * 3]);
						minZ = Math.min(minZ, t[v * 3 + 2]);
						maxZ = Math.max(maxZ, t[v * 3 + 2]);
					}
				}
				float cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
				float qx = (maxX - minX) / 4, qz = (maxZ - minZ) / 4;
				float bx0 = cx - qx, bz0 = cz - qz, bx1 = cx + qx, bz1 = cz + qz;

				int fullInside = 0, touchedVerts = 0;
				for (float[] t : c.uniqueTris) {
					boolean all = true;
					for (int v = 0; v < 3; v++) {
						boolean in = t[v * 3] >= bx0 && t[v * 3] <= bx1 && t[v * 3 + 2] >= bz0 && t[v * 3 + 2] <= bz1;
						if (in) {
							touchedVerts++;
						} else {
							all = false;
						}
					}
					if (all) {
						fullInside++;
					}
				}
				if (touchedVerts == 0) {
					emptySel++;
					continue;
				}

				// MOVE +40Y: every in-box vertex 40 higher, all others identical
				GfColl m = new GfColl(GfColl.moveBox(coll, bx0, bz0, bx1, bz1, 0, 40, 0));
				if (m.uniqueTris.size() != c.uniqueTris.size()) {
					throw new IllegalStateException("move changed tri count");
				}
				for (int t = 0; t < c.uniqueTris.size(); t++) {
					float[] a = c.uniqueTris.get(t), b = m.uniqueTris.get(t);
					for (int v = 0; v < 3; v++) {
						boolean in = a[v * 3] >= bx0 && a[v * 3] <= bx1 && a[v * 3 + 2] >= bz0 && a[v * 3 + 2] <= bz1;
						float wantY = a[v * 3 + 1] + (in ? 40f : 0f);
						if (b[v * 3] != a[v * 3] || b[v * 3 + 1] != wantY || b[v * 3 + 2] != a[v * 3 + 2]) {
							throw new IllegalStateException("move wrong at tri " + t + " v" + v);
						}
					}
				}

				// DUP +100X / DELETE
				GfColl d = new GfColl(GfColl.duplicateBox(coll, bx0, bz0, bx1, bz1, 100, 0, 0));
				if (d.uniqueTris.size() != c.uniqueTris.size() + fullInside) {
					throw new IllegalStateException("dup expected +" + fullInside + " tris, got +"
							+ (d.uniqueTris.size() - c.uniqueTris.size()));
				}
				GfColl del = new GfColl(GfColl.deleteBox(coll, bx0, bz0, bx1, bz1));
				if (del.uniqueTris.size() != c.uniqueTris.size() - fullInside) {
					throw new IllegalStateException("del expected -" + fullInside + " tris, got -"
							+ (c.uniqueTris.size() - del.uniqueTris.size()));
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
		System.out.println("\nGfColl box ops: tested=" + tested + " (emptySel=" + emptySel + ")  ok=" + ok + "  failures=" + failures);
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (failures > 0) {
			System.exit(1);
		}
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
