package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.BchModelVerifier;
import ctrmap.formats.h3d.RegionFactory;
import java.io.File;
import java.util.List;

/**
 * Blank-canvas validation on sampled real regions: blank content built from
 * each template must produce a model that passes both parsers and the strict
 * verifier, with the ground mesh an exact flat plane (grid positions, tiled
 * UVs, up normals where buffered) and every other editable mesh degenerate;
 * plus valid flat collision and a correctly bordered tilemap.
 *
 * Usage: java ctrmap.tests.RegionFactoryTest <path-to-a039-garc> [step]
 */
public class RegionFactoryTest {

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/3/9");
		int step = args.length > 1 ? Integer.parseInt(args[1]) : 15;
		GARC garc = new GARC(garcFile);
		int tested = 0, ok = 0, failures = 0;
		for (int i = 0; i < garc.length; i += step) {
			byte[] model = sub(garc.getDecompressedEntry(i), 1);
			if (model == null || !BchMapModel.isMapModel(model)) {
				continue;
			}
			try {
				BchMapModel m = new BchMapModel(model);
				//ground = the editable mesh with the most triangles (the UI default)
				int ground = -1, bestTris = -1;
				for (BchMapModel.MeshGeom g : m.geometry()) {
					if (g.posOk) {
						int t = m.getTriangles(g.meshIndex).length;
						if (t > bestTris) {
							bestTris = t;
							ground = g.meshIndex;
						}
					}
				}
				if (ground < 0) {
					continue;
				}
				tested++;
				RegionFactory.BlankContent bc = RegionFactory.blank(model, ground);

				//model gates
				BchMapModel bm = new BchMapModel(bc.model);
				if (!bm.validate().isEmpty()) {
					throw new IllegalStateException("blank model parse " + bm.validate());
				}
				List<String> v = BchModelVerifier.verify(bc.model);
				if (!v.isEmpty() && bm.auxDicts.isEmpty()) {
					throw new IllegalStateException("blank model verifier " + v);
				}
				BCHFile render = new BCHFile(bc.model);
				if (render.errorlevel != 0 || render.models.isEmpty()) {
					throw new IllegalStateException("render parser rejected blank model");
				}
				float[][] pos = bm.getVertexPositions(ground);
				int[] tris = bm.getTriangles(ground);
				if (pos.length != 121 || tris.length != 600) {
					throw new IllegalStateException("plane shape wrong (" + pos.length + " verts, " + tris.length + " idx)");
				}
				for (float[] p : pos) {
					if (p[1] != 0f || p[0] < -360f || p[0] > 360f || p[2] < -360f || p[2] > 360f) {
						throw new IllegalStateException("plane vertex out of frame");
					}
				}
				for (BchMapModel.MeshGeom g : bm.geometry()) {
					if (g.posOk && g.meshIndex != ground && bm.getTriangles(g.meshIndex).length != 3) {
						throw new IllegalStateException("mesh " + g.meshIndex + " not degenerate");
					}
				}

				//collision gates
				GfColl coll = new GfColl(bc.collision);
				if (coll.uniqueTris.size() != 2) {
					throw new IllegalStateException("collision tri count " + coll.uniqueTris.size());
				}

				//tilemap gates
				if (bc.tilemap.length != 6528) {
					throw new IllegalStateException("tilemap size " + bc.tilemap.length);
				}
				for (int y = 0; y < 40; y++) {
					for (int x = 0; x < 40; x++) {
						int off = 4 + (y * 40 + x) * 4;
						boolean border = x == 0 || y == 0 || x == 39 || y == 39;
						boolean blocked = (bc.tilemap[off] & 1) == 1;
						if (border != blocked) {
							throw new IllegalStateException("tilemap tuple wrong at " + x + "," + y);
						}
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
		System.out.println("\nRegionFactory: tested=" + tested + "  ok=" + ok + "  failures=" + failures);
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (failures > 0) {
			System.exit(1);
		}
	}

	static byte[] sub(byte[] c, int i) {
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
		return java.util.Arrays.copyOfRange(c, o0, o1);
	}

	static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
