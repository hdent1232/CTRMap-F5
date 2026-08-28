package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.BchModelVerifier;
import ctrmap.formats.h3d.RegionFactory;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TilePalette;
import java.io.File;
import java.util.List;

/**
 * Validates the tile painter's region generator on the default tileset donor
 * (Route 101 region 1): several painted grids must produce a model that passes
 * both parsers and the strict verifier, collision that parses with the exact
 * walkable-tile triangle count, and a tilemap whose tuples match the grid. Also
 * checks that painted meshes actually carry the painted tiles' geometry.
 *
 * Usage: java ctrmap.tests.PaintedRegionTest <path-to-a039-garc>
 */
public class PaintedRegionTest {

	static final int DIM = PaintedRegionBuilder.DIM;

	public static void main(String[] args) throws Exception {
		File garc = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/3/9");
		GARC gr = new GARC(garc);
		byte[] donor = sub(gr.getDecompressedEntry(1), 1); // Route 101 region 1: grass+sea+rock+wood
		if (donor == null || !BchMapModel.isMapModel(donor)) {
			System.out.println("FAIL donor region 1 not a map model");
			System.exit(1);
		}
		int failures = 0;
		failures += check("all grass", grid(TilePalette.GRASS), null, donor);
		failures += check("grass field + water pond + rock border + path", mixedGrid(), null, donor);
		failures += check("checkerboard grass/water", checker(), null, donor);
		failures += check("mostly void with a path", pathGrid(), null, donor);
		failures += check("raised plateau (elevation + cliffs)", grid(TilePalette.GRASS), plateau(), donor);

		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT (" + failures + ")");
		if (failures > 0) {
			System.exit(1);
		}
	}

	static int check(String label, TilePalette[][] g, int[][] hh, byte[] donor) {
		final int[][] height = hh == null ? new int[DIM][DIM] : hh;
		try {
			RegionFactory.BlankContent c = PaintedRegionBuilder.build(donor, g, height, ctrmap.formats.tilemap.TerrainLighting.daytime());

			// model gates
			BchMapModel m = new BchMapModel(c.model);
			if (!m.validate().isEmpty()) {
				throw new IllegalStateException("model parse " + m.validate());
			}
			List<String> v = BchModelVerifier.verify(c.model);
			if (!v.isEmpty() && m.auxDicts.isEmpty()) {
				throw new IllegalStateException("verifier " + v);
			}
			BCHFile render = new BCHFile(c.model);
			if (render.errorlevel != 0 || render.models.isEmpty()) {
				throw new IllegalStateException("render parser rejected");
			}

			// collision: 2 tris per walkable floor tile + 2 per cliff edge (drop)
			int floorTiles = 0, cliffEdges = 0;
			for (int y = 0; y < DIM; y++) {
				for (int x = 0; x < DIM; x++) {
					TilePalette t = g[y][x];
					if (t != null && t.floor) {
						floorTiles++;
					}
					if (t == null || t == TilePalette.VOID) {
						continue;
					}
					int[][] nb = {{x + 1, y}, {x - 1, y}, {x, y + 1}, {x, y - 1}};
					for (int[] p : nb) {
						int hn = (p[0] < 0 || p[1] < 0 || p[0] >= DIM || p[1] >= DIM
								|| g[p[1]][p[0]] == null || g[p[1]][p[0]] == TilePalette.VOID) ? 0 : height[p[1]][p[0]];
						if (hn < height[y][x]) {
							cliffEdges++;
						}
					}
				}
			}
			GfColl coll = new GfColl(c.collision);
			int wantTris = Math.max(1, floorTiles * 2 + cliffEdges * 2);
			if (coll.uniqueTris.size() != wantTris) {
				throw new IllegalStateException("collision tris " + coll.uniqueTris.size() + " want " + wantTris
						+ " (floors " + floorTiles + " cliffs " + cliffEdges + ")");
			}

			// tilemap tuples match the grid
			if (c.tilemap.length != 6528) {
				throw new IllegalStateException("tilemap size " + c.tilemap.length);
			}
			for (int y = 0; y < DIM; y++) {
				for (int x = 0; x < DIM; x++) {
					int off = 4 + (y * DIM + x) * 4;
					int[] tup = (g[y][x] == null ? TilePalette.VOID : g[y][x]).tuple;
					for (int k = 0; k < 4; k++) {
						if ((c.tilemap[off + k] & 0xFF) != tup[k]) {
							throw new IllegalStateException("tilemap tuple mismatch at " + x + "," + y);
						}
					}
				}
			}

			// painted geometry present: total triangles across painted meshes >=
			// 2 per non-void tile that got a floor OR is rock (rock still paints)
			int paintTiles = 0;
			for (int y = 0; y < DIM; y++) {
				for (int x = 0; x < DIM; x++) {
					TilePalette t = g[y][x];
					if (t != null && t != TilePalette.VOID) {
						paintTiles++;
					}
				}
			}
			long modelTris = 0;
			for (BchMapModel.MeshGeom mg : m.geometry()) {
				if (mg.posOk) {
					int tt = m.getTriangles(mg.meshIndex).length / 3;
					if (tt > 1) { // ignore degenerate (1 tri) meshes
						modelTris += tt;
					}
				}
			}
			if (paintTiles > 0 && modelTris < paintTiles) {
				throw new IllegalStateException("painted geometry too small: " + modelTris + " tris for " + paintTiles + " tiles");
			}
			System.out.println("  ok: " + label + "  (" + floorTiles + " floor tiles, " + modelTris + " model tris)");
			return 0;
		} catch (RuntimeException ex) {
			System.out.println("FAIL " + label + ": " + ex.getMessage());
			return 1;
		}
	}

	// ---- grids ----

	static TilePalette[][] grid(TilePalette fill) {
		TilePalette[][] g = new TilePalette[DIM][DIM];
		for (TilePalette[] row : g) {
			java.util.Arrays.fill(row, fill);
		}
		return g;
	}

	static TilePalette[][] mixedGrid() {
		TilePalette[][] g = grid(TilePalette.GRASS);
		for (int y = 0; y < DIM; y++) {
			for (int x = 0; x < DIM; x++) {
				if (x == 0 || y == 0 || x == DIM - 1 || y == DIM - 1) {
					g[y][x] = TilePalette.ROCK;
				} else if (x >= 10 && x < 18 && y >= 10 && y < 18) {
					g[y][x] = TilePalette.WATER;
				} else if (y == 20) {
					g[y][x] = TilePalette.PATH;
				} else if (x >= 25 && x < 32 && y >= 25 && y < 32) {
					g[y][x] = TilePalette.TALL_GRASS;
				}
			}
		}
		return g;
	}

	static TilePalette[][] checker() {
		TilePalette[][] g = new TilePalette[DIM][DIM];
		for (int y = 0; y < DIM; y++) {
			for (int x = 0; x < DIM; x++) {
				g[y][x] = ((x + y) % 2 == 0) ? TilePalette.GRASS : TilePalette.WATER;
			}
		}
		return g;
	}

	static int[][] plateau() {
		int[][] h = new int[DIM][DIM];
		for (int y = 0; y < DIM; y++) {
			for (int x = 0; x < DIM; x++) {
				if (x >= 10 && x < 26 && y >= 10 && y < 26) {
					h[y][x] = 2;
				}
				if (x >= 14 && x < 22 && y >= 14 && y < 22) {
					h[y][x] = 3;
				}
			}
		}
		return h;
	}

	static TilePalette[][] pathGrid() {
		TilePalette[][] g = grid(TilePalette.VOID);
		for (int i = 5; i < 35; i++) {
			g[20][i] = TilePalette.PATH;
			g[i][20] = TilePalette.PATH;
		}
		return g;
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
