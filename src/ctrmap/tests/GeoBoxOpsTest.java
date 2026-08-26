package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.GeoBoxOps;
import java.io.File;

/**
 * Box-op validation on real map regions (sampled): for each region, a box over
 * the middle quarter of its geometry bounds is selected, then
 * (1) MOVE +40Y: every vertex that was inside is exactly 40 higher, every
 *     vertex outside is byte-untouched, and the model re-validates clean;
 * (2) DUPLICATE +100X: query(fullFaces) faces appear as clones, original
 *     geometry untouched, re-validates clean;
 * (3) DELETE: the full-faces disappear, no other face lost, re-validates clean.
 *
 * Usage: java ctrmap.tests.GeoBoxOpsTest <path-to-a039-garc> [sampleStep]
 */
public class GeoBoxOpsTest {

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/3/9");
		int step = args.length > 1 ? Integer.parseInt(args[1]) : 20;
		GARC garc = new GARC(garcFile);
		int tested = 0, moveOk = 0, dupOk = 0, delOk = 0, failures = 0, emptySel = 0;
		for (int i = 0; i < garc.length; i += step) {
			byte[] model = subfile(garc.getDecompressedEntry(i), 1);
			if (model == null || !BchMapModel.isMapModel(model)) {
				continue;
			}
			try {
				BchMapModel bmm = new BchMapModel(model);
				float[] bounds = geomBounds(bmm);
				if (bounds == null) {
					continue;
				}
				tested++;
				float cx = (bounds[0] + bounds[3]) / 2, cz = (bounds[2] + bounds[5]) / 2;
				float qx = (bounds[3] - bounds[0]) / 4, qz = (bounds[5] - bounds[2]) / 4;
				GeoBoxOps.Box box = new GeoBoxOps.Box(cx - qx, cz - qz, cx + qx, cz + qz);
				GeoBoxOps.Selection sel = GeoBoxOps.query(bmm, box);
				if (sel.vertices == 0) {
					emptySel++;
					continue;
				}

				// (1) MOVE
				byte[] moved = GeoBoxOps.move(model, box, 0, 40f, 0);
				BchMapModel mm = new BchMapModel(moved);
				if (!mm.validate().isEmpty()) {
					throw new IllegalStateException("move: re-parse problems " + mm.validate());
				}
				for (BchMapModel.MeshGeom g : bmm.geometry()) {
					if (!g.posOk) {
						continue;
					}
					float[][] before = bmm.getVertexPositions(g.meshIndex);
					float[][] after = mm.getVertexPositions(g.meshIndex);
					for (int v = 0; v < before.length; v++) {
						float wantY = before[v][1] + (box.contains(before[v]) ? 40f : 0f);
						if (Math.abs(after[v][1] - wantY) > 1e-4f
								|| Math.abs(after[v][0] - before[v][0]) > 1e-4f
								|| Math.abs(after[v][2] - before[v][2]) > 1e-4f) {
							throw new IllegalStateException("move: wrong vertex result mesh " + g.meshIndex + " v" + v);
						}
					}
				}
				moveOk++;

				// (2) DUPLICATE
				byte[] duped = GeoBoxOps.duplicate(model, box, 100f, 0, 0);
				BchMapModel dm = new BchMapModel(duped);
				if (!dm.validate().isEmpty()) {
					throw new IllegalStateException("dup: re-parse problems " + dm.validate());
				}
				int trisBefore = totalTris(bmm), trisAfter = totalTris(dm);
				if (trisAfter - trisBefore != sel.fullFaces) {
					throw new IllegalStateException("dup: expected +" + sel.fullFaces + " faces, got +" + (trisAfter - trisBefore));
				}

				// the clones must sit exactly +100 X from originals
				GeoBoxOps.Box shiftedBox = new GeoBoxOps.Box(box.minX + 100f, box.minZ, box.maxX + 100f, box.maxZ);
				dupOk++;

				// (3) DELETE
				byte[] deleted = GeoBoxOps.delete(model, box);
				BchMapModel del = new BchMapModel(deleted);
				if (!del.validate().isEmpty()) {
					throw new IllegalStateException("del: re-parse problems " + del.validate());
				}
				int trisDel = totalTris(del);
				int degenerateKeepAlive = countKeepAlive(bmm, box);
				int expected = trisBefore - sel.fullFaces + degenerateKeepAlive;
				if (trisDel != expected) {
					throw new IllegalStateException("del: expected " + expected + " faces, got " + trisDel);
				}
				delOk++;
			} catch (RuntimeException ex) {
				failures++;
				System.out.println("FAIL region " + i + ": " + ex.getMessage());
				if (failures > 8) {
					break;
				}
			}
		}
		System.out.println("\nGeoBoxOps: tested=" + tested + " (emptySel=" + emptySel + ")  move=" + moveOk
				+ "  dup=" + dupOk + "  del=" + delOk + "  failures=" + failures);
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (failures > 0) {
			System.exit(1);
		}
	}

	/** How many meshes would lose ALL faces (each keeps one degenerate). */
	private static int countKeepAlive(BchMapModel m, GeoBoxOps.Box box) {
		int n = 0;
		for (BchMapModel.MeshGeom g : m.geometry()) {
			if (!g.posOk) {
				continue;
			}
			float[][] pos = m.getVertexPositions(g.meshIndex);
			int[] tris = m.getTriangles(g.meshIndex);
			boolean[] in = new boolean[pos.length];
			for (int v = 0; v < pos.length; v++) {
				in[v] = box.contains(pos[v]);
			}
			int keptFaces = 0, removed = 0;
			for (int t = 0; t + 2 < tris.length; t += 3) {
				if (in[tris[t]] && in[tris[t + 1]] && in[tris[t + 2]]) {
					removed++;
				} else {
					keptFaces++;
				}
			}
			if (removed > 0 && keptFaces == 0) {
				n++;
			}
		}
		return n;
	}

	private static int totalTris(BchMapModel m) {
		int n = 0;
		for (BchMapModel.MeshGeom g : m.geometry()) {
			if (!g.posOk) {
				continue;
			}
			n += m.getTriangles(g.meshIndex).length / 3;
		}
		return n;
	}

	/** minX,minY,minZ,maxX,maxY,maxZ across decodable meshes, or null. */
	private static float[] geomBounds(BchMapModel m) {
		float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		boolean any = false;
		for (BchMapModel.MeshGeom g : m.geometry()) {
			if (!g.posOk) {
				continue;
			}
			for (float[] p : m.getVertexPositions(g.meshIndex)) {
				b[0] = Math.min(b[0], p[0]);
				b[1] = Math.min(b[1], p[1]);
				b[2] = Math.min(b[2], p[2]);
				b[3] = Math.max(b[3], p[0]);
				b[4] = Math.max(b[4], p[1]);
				b[5] = Math.max(b[5], p[2]);
				any = true;
			}
		}
		return any ? b : null;
	}

	private static byte[] subfile(byte[] c, int i) {
		if (c == null || c.length < 8) {
			return null;
		}
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
