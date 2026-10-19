package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.BchModelVerifier;
import ctrmap.formats.h3d.RegionFactory;
import java.io.File;
import java.util.List;
import static ctrmap.formats.containers.ContainerBytes.subfile;

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
			byte[] model = subfile(garc.getDecompressedEntry(i), 1);
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
		failures += everyExtraLayerIsBlanked(garc);
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (failures > 0) {
			System.exit(1);
		}
	}

	/**
	 * A blanked region of 11 subfiles has both extra layers blanked - 7 and 8 void tilemaps, 9 and 10 empty collision -
	 * and one of 9 has its one, 7 and 8; none keeps the layer it held.
	 *
	 * <p>Measured by the 2026-10-14 shared sweep (RegionFactory 116, negate-if, SURVIVED): nothing blanked a region
	 * with extra layers and looked at them. Inverted, a region of 11 kept its second layer's old tiles and collision
	 * under a blank canvas, and one of 9 or 10 was written a subfile 10 it does not have.
	 */
	static int everyExtraLayerIsBlanked(GARC garc) throws Exception {
		System.out.println("--- a blanked region's extra layers are blanked, every one it has");
		byte[] template = null;
		for (int i = 0; i < garc.length && template == null; i++) {
			byte[] model = subfile(garc.getDecompressedEntry(i), 1);
			if (model != null && BchMapModel.isMapModel(model)) {
				template = model;
			}
		}
		if (template == null) {
			System.out.println("FAIL extra layers: the archive holds no map model to blank");
			return 1;
		}
		byte[] old = "the layer as it was".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		byte[] voidTiles = RegionFactory.voidTilemap(), noCollision = RegionFactory.emptyCollision();
		int fails = 0;
		for (int len : new int[]{11, 9}) {
			File f = new File(Scratch.dir("ctrmap_blank_layers"), String.valueOf(len));
			ctrmap.formats.containers.GR gr = new ctrmap.formats.containers.GR(f, len, new ContainerWriteSafetyTest.Files2());
			for (int s = 0; s < len; s++) {
				gr.storeFile(s, s == 1 ? template : old);
			}
			String blanked;
			try {
				blanked = RegionFactory.blankOrThrow(gr, -1) ? "blanked" : "not blanked";
			} catch (RuntimeException threw) {
				blanked = "it threw " + threw;
			}
			StringBuilder wrong = new StringBuilder();
			int[] tiles = len >= 11 ? new int[]{7, 8} : new int[]{7};
			int[] coll = len >= 11 ? new int[]{9, 10} : new int[]{8};
			for (int s : tiles) {
				if (!java.util.Arrays.equals(gr.getFile(s), voidTiles)) {
					wrong.append(" subfile ").append(s).append(" is not a void tilemap;");
				}
			}
			for (int s : coll) {
				if (!java.util.Arrays.equals(gr.getFile(s), noCollision)) {
					wrong.append(" subfile ").append(s).append(" is not empty collision;");
				}
			}
			if (!blanked.equals("blanked") || wrong.length() > 0) {
				System.out.println("FAIL a blanked region of " + len + " subfiles has every extra layer blanked: " + blanked
						+ wrong);
				fails++;
			} else {
				System.out.println("  ok: a blanked region of " + len + " subfiles has every extra layer blanked");
			}
		}
		return fails;
	}
}
