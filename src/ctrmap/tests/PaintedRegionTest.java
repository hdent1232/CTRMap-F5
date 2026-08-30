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
		failures += checkEdges(donor);
		failures += checkCliffWinding(donor);

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

	/**
	 * Every generated cliff wall must face OUT of the plateau it walls off.
	 *
	 * <p>All four edge directions used to wind inward. Most cliff materials cull
	 * back faces, so painted elevation shipped with the plateau top drawn and
	 * its sides invisible - and because the top looked correct, nothing about
	 * the result said "the walls are inside out".
	 *
	 * <p>The plateau is two concentric raised squares, so "outward" is simply
	 * away from their shared centre. The centre is derived from the wall
	 * triangles themselves rather than from tile coordinates, so the check does
	 * not depend on where the region's origin sits.
	 */
	static int checkCliffWinding(byte[] donor) {
		try {
			ctrmap.formats.tilemap.TerrainLighting L = ctrmap.formats.tilemap.TerrainLighting.daytime();
			//A flat build must contain no walls at all. If it did, the donor's
			//own retail cliffs would be in the sample and the check below would
			//be measuring the wrong geometry.
			List<float[]> flat = walls(PaintedRegionBuilder.build(
					donor, grid(TilePalette.GRASS), new int[DIM][DIM], L).model);
			if (!flat.isEmpty()) {
				throw new IllegalStateException("a flat build already has " + flat.size()
						+ " vertical triangles; the sample is not purely generated");
			}
			List<float[]> w = walls(PaintedRegionBuilder.build(
					donor, grid(TilePalette.GRASS), plateau(), L).model);
			if (w.size() < 50) {
				throw new IllegalStateException("only " + w.size()
						+ " cliff triangles generated; the plateau did not build");
			}
			float cx = 0f, cz = 0f;
			for (float[] t : w) {
				cx += t[0];
				cz += t[1];
			}
			cx /= w.size();
			cz /= w.size();
			int out = 0, skipped = 0;
			for (float[] t : w) {
				float ox = t[0] - cx, oz = t[1] - cz;
				if (ox * ox + oz * oz < 1f) {
					skipped++;
					continue;
				}
				if (t[2] * ox + t[3] * oz > 0) {
					out++;
				}
			}
			int judged = w.size() - skipped;
			if (out != judged) {
				throw new IllegalStateException("cliff winding: only " + out + "/" + judged
						+ " walls face outward");
			}
			System.out.println("  ok: cliff winding (" + judged + " walls, all facing out)");
			return 0;
		} catch (RuntimeException ex) {
			System.out.println("FAIL cliff winding: " + ex.getMessage());
			return 1;
		}
	}

	/** Near-vertical triangles as {centroidX, centroidZ, normalX, normalZ}. */
	static List<float[]> walls(byte[] model) {
		List<float[]> out = new java.util.ArrayList<>();
		BchMapModel m = new BchMapModel(model);
		for (BchMapModel.MeshGeom g : m.geometry()) {
			if (!g.posOk) {
				continue;
			}
			float[][] pos = m.getVertexPositions(g.meshIndex);
			int[] tri = m.getTriangles(g.meshIndex);
			for (int i = 0; i + 2 < tri.length; i += 3) {
				float[] a = pos[tri[i]], b = pos[tri[i + 1]], c = pos[tri[i + 2]];
				float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
				float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
				float nx = uy * vz - uz * vy;
				float ny = uz * vx - ux * vz;
				float nz = ux * vy - uy * vx;
				float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (len < 1e-4f || Math.abs(ny) / len > 0.3f) {
					continue; //degenerate, or a floor rather than a wall
				}
				out.add(new float[]{(a[0] + b[0] + c[0]) / 3f, (a[2] + b[2] + c[2]) / 3f, nx, nz});
			}
		}
		return out;
	}

	/**
	 * Edge transition strips: a clean vertical grass|path seam must add exactly
	 * 2 edge tris per boundary segment (40 rows &rarr; 80 tris), and every edge tri
	 * must face up. If the donor has no grass-edge material, edges must add nothing.
	 */
	static int checkEdges(byte[] donor) {
		try {
			boolean supported = PaintedRegionBuilder.donorSupportsEdges(donor);
			TilePalette[][] g = new TilePalette[DIM][DIM];
			for (int y = 0; y < DIM; y++) {
				for (int x = 0; x < DIM; x++) {
					g[y][x] = (x < 20) ? TilePalette.GRASS : TilePalette.PATH;
				}
			}
			int[][] h = new int[DIM][DIM];
			boolean[][] noramp = new boolean[DIM][DIM];
			ctrmap.formats.tilemap.TerrainLighting L = ctrmap.formats.tilemap.TerrainLighting.daytime();
			long trisOff = totalTris(PaintedRegionBuilder.build(donor, g, h, noramp, L, false).model);
			RegionFactory.BlankContent withC = PaintedRegionBuilder.build(donor, g, h, noramp, L, true);
			long delta = totalTris(withC.model) - trisOff;

			if (!supported) {
				if (delta != 0) {
					throw new IllegalStateException("edges added " + delta + " tris but donor has no edge material");
				}
				System.out.println("  ok: edge strips (donor has no edge material; skipped cleanly)");
				return 0;
			}
			if (delta != 80) {
				throw new IllegalStateException("edge strip tris delta " + delta + " want 80 (40-row seam)");
			}
			// winding: every edge-mesh triangle must face up (+Y)
			BchMapModel m = new BchMapModel(withC.model);
			int em = PaintedRegionBuilder.resolveEdgeMesh(m);
			float[][] pos = m.getVertexPositions(em);
			int[] tri = m.getTriangles(em);
			int up = 0, tot = 0;
			for (int i = 0; i + 2 < tri.length; i += 3) {
				float[] a = pos[tri[i]], b = pos[tri[i + 1]], c = pos[tri[i + 2]];
				float ux = b[0] - a[0], uz = b[2] - a[2];
				float vx = c[0] - a[0], vz = c[2] - a[2];
				float ny = uz * vx - ux * vz;
				tot++;
				if (ny > 0) {
					up++;
				}
			}
			if (tot == 0 || up != tot) {
				throw new IllegalStateException("edge winding: only " + up + "/" + tot + " tris face up");
			}
			System.out.println("  ok: edge strips (" + (delta / 2) + " segments, all " + tot + " tris face up)");
			return 0;
		} catch (RuntimeException ex) {
			System.out.println("FAIL edge strips: " + ex.getMessage());
			return 1;
		}
	}

	static long totalTris(byte[] model) {
		BchMapModel m = new BchMapModel(model);
		long t = 0;
		for (BchMapModel.MeshGeom mg : m.geometry()) {
			if (mg.posOk) {
				int c = m.getTriangles(mg.meshIndex).length / 3;
				if (c > 1) {
					t += c;
				}
			}
		}
		return t;
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
