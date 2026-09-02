package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.RegionFactory;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TerrainLighting;
import ctrmap.formats.tilemap.TilePalette;
import java.io.File;
import java.util.Arrays;

/**
 * Paints a block onto real retail maps and asserts that nothing is left
 * STANDING in it.
 *
 * <p>Painting a tile replaces the ground; whatever was on that ground has to go
 * with it. That never used to happen. Every other check passed - the model
 * parsed, the collision was sound, the floor was the right colour - while trees
 * carried on standing in the middle of freshly painted sand, and the only way
 * anyone found out was by looking at a screenshot and saying so.
 *
 * <p>The measure is height above the new floor. A repainted tile can legitimately
 * sit a step higher than its neighbour, so anything within one height step is
 * terrain; anything tens of units up is a thing that was standing there. Before
 * the fix, region 9 kept 196 triangles between 27 and 96 units up.
 *
 * <p>Args: the FieldData GARC, and optionally how many regions to sweep.
 */
public class CompositeLeftoverTest {

	private static final int DIM = 40;
	private static final float TILE = 18f;
	/** How tall a triangle has to be, in itself, to be a thing rather than a
	 *  surface. A floor has no height of its own; a tree billboard has tens. */
	private static final float TALL = 10f;
	/** And it has to be clear of the new floor - one height step is 18. */
	private static final float ABOVE = 20f;
	private static final int[] REGIONS = {7, 8, 9, 10, 15, 44, 153, 208, 300, 316, 388, 500, 600, 745};

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println("FAIL usage: CompositeLeftoverTest <FieldData GARC>");
			System.exit(1);
		}
		GARC fd = new GARC(new File(args[0]));
		int failures = 0, swept = 0, skipped = 0, worstTris = 0;
		String worstWhere = "";

		for (int region : REGIONS) {
			if (region >= fd.length) {
				continue;
			}
			byte[] container;
			byte[] model, coll, tm;
			try {
				container = fd.getDecompressedEntry(region);
				model = sub(container, 1);
				coll = sub(container, 2);
				tm = sub(container, 0);
			} catch (RuntimeException ex) {
				continue;
			}
			if (model == null || !BchMapModel.isMapModel(model)) {
				continue;
			}
			int x0 = 12, y0 = 12, x1 = 27, y1 = 27;
			TilePalette[][] grid = new TilePalette[DIM][DIM];
			boolean[][] touched = new boolean[DIM][DIM];
			int[][] height = new int[DIM][DIM];
			boolean[][] ramp = new boolean[DIM][DIM];
			for (TilePalette[] row : grid) {
				Arrays.fill(row, TilePalette.GRASS);
			}
			PaintedRegionBuilder.seedHeightsFromCollision(coll, height);
			//Only paint where "replace this ground" has one obvious meaning.
			//Flattening a slab across a staircase or a two-storey cave leaves the
			//upper level standing above the new floor, and it SHOULD - that is
			//structure, not scenery. Judging those as leftovers would be judging
			//an operation nobody would perform.
			int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
			for (int y = y0; y <= y1; y++) {
				for (int x = x0; x <= x1; x++) {
					lo = Math.min(lo, height[y][x]);
					hi = Math.max(hi, height[y][x]);
				}
			}
			if (hi - lo > 1) {
				skipped++;
				continue;
			}
			for (int y = y0; y <= y1; y++) {
				for (int x = x0; x <= x1; x++) {
					grid[y][x] = TilePalette.SAND;
					touched[y][x] = true;
				}
			}
			RegionFactory.BlankContent bc;
			try {
				bc = PaintedRegionBuilder.buildComposite(model, coll, tm, grid, height, ramp,
						touched, TerrainLighting.daytime(), true);
			} catch (RuntimeException ex) {
				System.out.println("FAIL region " + region + " did not build: " + ex);
				failures++;
				continue;
			}
			swept++;

			float ox = PaintedRegionBuilder.ORIGIN;
			float px0 = x0 * TILE + ox, px1 = (x1 + 1) * TILE + ox;
			float pz0 = y0 * TILE + ox, pz1 = (y1 + 1) * TILE + ox;
			BchMapModel out = new BchMapModel(bc.model);

			float floor = Float.MAX_VALUE;
			for (int mi = 0; mi < out.meshCount; mi++) {
				float[][] pos = positions(out, mi);
				if (pos == null) {
					continue;
				}
				for (float[] v : pos) {
					if (inside(v[0], v[2], px0, px1, pz0, pz1)) {
						floor = Math.min(floor, v[1]);
					}
				}
			}
			if (floor == Float.MAX_VALUE) {
				continue; //nothing landed in the painted area at all
			}
			//The mesh the builder writes its cliffs into is rebuilt by the
			//builder every run, so whatever stands in it is geometry authored
			//THIS pass - a wall at a step, a backing behind it - and never a
			//leftover. That distinction matters because on these donor-only
			//regions there is no imported cliff material, so the builder picks
			//one of the donor's own cliff meshes and writes into it; judging
			//its contents as leftovers flags terrain the painter deliberately
			//built. Everything else - trees, rocks, houses, ground slabs -
			//is still judged exactly as before.
			//resolved exactly as the builder does, fallback included - without
			//the same fallback this picks a different mesh and excludes nothing
			BchMapModel probe = new BchMapModel(model);
			int cliffMesh = PaintedRegionBuilder.resolveCliffMesh(
					probe, PaintedRegionBuilder.defaultGroundMesh(probe));

			int standing = 0;
			String what = null;
			for (int mi = 0; mi < out.meshCount; mi++) {
				if (mi == cliffMesh) {
					continue;
				}
				float[][] pos = positions(out, mi);
				int[] tris = triangles(out, mi);
				if (pos == null || tris == null) {
					continue;
				}
				for (int t = 0; t + 2 < tris.length; t += 3) {
					int a = tris[t], b = tris[t + 1], c = tris[t + 2];
					if (a >= pos.length || b >= pos.length || c >= pos.length) {
						continue;
					}
					float cx = (pos[a][0] + pos[b][0] + pos[c][0]) / 3f;
					float cz = (pos[a][2] + pos[b][2] + pos[c][2]) / 3f;
					if (!inside(cx, cz, px0, px1, pz0, pz1)) {
						continue;
					}
					float top = Math.max(pos[a][1], Math.max(pos[b][1], pos[c][1]));
					float bottom = Math.min(pos[a][1], Math.min(pos[b][1], pos[c][1]));
					//Deliberately NOT the same test the builder uses. The builder
					//asks "is this a surface?"; this asks "is something tall still
					//here?", which is the thing a person notices in the picture.
					//An upper floor or a bridge deck is flat and stays; a tree is
					//tall and must not.
					if (top - floor > ABOVE && top - bottom > TALL) {
						standing++;
						if (what == null) {
							what = out.getMaterialName(out.getMeshMaterialIndex(mi));
						}
					}
				}
			}
			//a stray triangle or two is noise from a footprint that straddles the
			//edge; a thing left standing is tens of them
			if (standing > 8) {
				failures++;
				System.out.println("FAIL region " + region + ": " + standing
						+ " triangles still standing in the painted area (" + what + ")");
			}
			if (standing > worstTris) {
				worstTris = standing;
				worstWhere = "region " + region + (what == null ? "" : " (" + what + ")");
			}
		}
		System.out.println("  swept " + swept + " single-level maps (" + skipped
				+ " multi-level skipped); worst leftover " + worstTris
				+ " triangle(s)" + (worstTris > 0 ? " in " + worstWhere : ""));
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT (" + failures + ")");
		if (failures > 0) {
			System.exit(1);
		}
	}

	private static float[][] positions(BchMapModel m, int mi) {
		try {
			return m.getVertexPositions(mi);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private static int[] triangles(BchMapModel m, int mi) {
		try {
			return m.getTriangles(mi);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private static boolean inside(float x, float z, float x0, float x1, float z0, float z1) {
		return x > x0 + 0.5f && x < x1 - 0.5f && z > z0 + 0.5f && z < z1 - 0.5f;
	}

	private static byte[] sub(byte[] c, int i) {
		int o0 = le32(c, 4 + i * 4), o1 = le32(c, 4 + (i + 1) * 4);
		if (o0 < 0 || o1 > c.length || o1 <= o0) {
			return null;
		}
		return Arrays.copyOfRange(c, o0, o1);
	}

	private static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
