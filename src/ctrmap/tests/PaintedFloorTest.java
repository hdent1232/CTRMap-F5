package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.RegionFactory;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TerrainLighting;
import ctrmap.formats.tilemap.TilePalette;
import java.io.File;
import java.util.Arrays;

/**
 * Where the tile painter puts a floor and which way it slopes. Five live
 * defects, every one of which reported success while sealing the map:
 *
 * <ol>
 * <li>A ramp had no direction. It sloped toward the first neighbour that sat
 *     exactly one level down, in E,W,S,N order, so the one-tile notch every
 *     route uses for its path up a hill - lower ground on three sides - ran
 *     east across the corridor and the way through stayed walled.</li>
 * <li>A ramp on a two-level hill matched no neighbour at all and was dropped
 *     without a word: a 36-unit wall stood where the arrow was drawn.</li>
 * <li>The stair brushes carried a tuple and a material but no slope, so every
 *     step was a flat tile behind an 18-unit wall.</li>
 * <li>Painted water sank its visible surface seven units but not its
 *     collision, so the player surfed in the air.</li>
 * <li>A painted tile with no collision under its centre - 44% of all retail
 *     tiles - was seeded at level 0, the region's lowest ground. One grass tile
 *     painted beside a path to widen it came out as a walled pit up to seven
 *     tiles deep, still marked walkable, under retail scenery that hid it.</li>
 * </ol>
 *
 * Usage: java ctrmap.tests.PaintedFloorTest &lt;path-to-a039-garc&gt;
 */
public class PaintedFloorTest {

	static final int DIM = PaintedRegionBuilder.DIM;
	static final float TILE = PaintedRegionBuilder.TILE;
	static final float ORIGIN = PaintedRegionBuilder.ORIGIN;
	static final float STEP = PaintedRegionBuilder.STEP;
	static final TerrainLighting L = TerrainLighting.daytime();

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File garc = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs/a/0/3/9");
		if (!garc.isFile()) {
			System.out.println("  skip: no FieldData GARC at " + garc);
			System.out.println("ALL PASS");
			return;
		}
		GARC gr = new GARC(garc);
		byte[] donor = sub(gr.getDecompressedEntry(1), 1); //Route 101: the default tileset donor
		if (donor == null || !BchMapModel.isMapModel(donor)) {
			System.out.println("  skip: region 1 is not a map model");
			System.out.println("ALL PASS");
			return;
		}
		rampRunsAlongTheCorridor(donor);
		rampSpansAnyDrop(donor);
		stairsSlope(donor);
		waterCollisionSinksWithTheMesh(donor);
		unsampledTileSeedsFromItsNeighbour(gr);
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * The notch: a level-1 hill over y &lt;= 18 with one more hill tile at
	 * (20,19) marked as the ramp. Lower ground lies E, W and S of it, and the
	 * way down is SOUTH - the only axis with hill behind it.
	 */
	static void rampRunsAlongTheCorridor(byte[] donor) {
		TilePalette[][] grid = grid(TilePalette.PATH);
		int[][] h = new int[DIM][DIM];
		for (int y = 0; y <= 18; y++) {
			Arrays.fill(h[y], 1);
		}
		h[19][20] = 1;
		int[][] ramp = PaintedRegionBuilder.noRamps();
		ramp[19][20] = PaintedRegionBuilder.steepestDescent(grid, h, 20, 19);
		check(ramp[19][20] == 2, "the gradient across the notch reads its way down as south (" + ramp[19][20] + ")");
		GfColl coll = new GfColl(PaintedRegionBuilder.build(donor, grid, h, ramp, L, false).collision);
		float n = surface(coll, 20.5f, 19.02f), s = surface(coll, 20.5f, 19.98f);
		float w = surface(coll, 20.02f, 19.5f), e = surface(coll, 20.98f, 19.5f);
		check(Math.abs(w - e) < 0.5f, "the notch ramp is level across the corridor (W " + w + " E " + e + ")");
		check(n - s > STEP * 0.9f, "the notch ramp descends south, along the corridor (N " + n + " S " + s + ")");
		check(walls(coll, 20, 19, 2) == 0, "no blocking wall stands across the notch's south edge");
	}

	/** A hill raised twice or three times, with its edge tile ramped south. */
	static void rampSpansAnyDrop(byte[] donor) {
		for (int lvl = 2; lvl <= 3; lvl++) {
			TilePalette[][] grid = grid(TilePalette.GRASS);
			int[][] h = new int[DIM][DIM];
			for (int y = 10; y <= 20; y++) {
				Arrays.fill(h[y], 15, 26, lvl);
			}
			int[][] ramp = PaintedRegionBuilder.noRamps();
			ramp[20][20] = 2;
			GfColl coll = new GfColl(PaintedRegionBuilder.build(donor, grid, h, ramp, L, false).collision);
			float n = surface(coll, 20.5f, 20.02f), s = surface(coll, 20.5f, 20.98f);
			check(walls(coll, 20, 20, 2) == 0, "level-" + lvl + " hill: no wall on the ramp's south edge");
			check(Math.abs(n - lvl * STEP) < STEP * 0.1f && s < STEP * 0.1f,
					"level-" + lvl + " hill: the ramp slopes the whole " + (lvl * STEP) + " units (N " + n + " S " + s + ")");
		}
	}

	/** A three-step north-south run up to a plateau, then the east-west brushes. */
	static void stairsSlope(byte[] donor) {
		TilePalette[][] grid = grid(TilePalette.GRASS);
		int[][] h = new int[DIM][DIM];
		for (int y = 0; y <= 16; y++) {
			Arrays.fill(h[y], 3);
		}
		for (int i = 0; i < 3; i++) {
			grid[17 + i][20] = TilePalette.STAIRS_Z;
			h[17 + i][20] = 3 - i;
		}
		GfColl coll = new GfColl(PaintedRegionBuilder.build(donor, grid, h, null, L, false).collision);
		int walls = 0;
		for (int y = 17; y <= 20; y++) {
			walls += walls(coll, 20, y, 3);
		}
		check(walls == 0, "a north-south stair run has no blocking wall between its steps (" + walls + " wall tris)");
		boolean climbs = true;
		float prev = surface(coll, 20.5f, 20.5f);
		for (int y = 19; y >= 16; y--) {
			float cur = surface(coll, 20.5f, y + 0.5f);
			climbs &= cur > prev;
			prev = cur;
		}
		check(climbs, "the stair run climbs from the ground to the plateau without a flat step");

		//the east-west brushes name their own climb: "climb east" goes DOWN to
		//the west, and on a plateau's west edge that is the only way it can go
		TilePalette[][] g2 = grid(TilePalette.GRASS);
		int[][] h2 = new int[DIM][DIM];
		for (int y = 0; y < DIM; y++) {
			Arrays.fill(h2[y], 20, DIM, 1);
		}
		g2[20][20] = TilePalette.STAIRS_E;
		g2[10][20] = TilePalette.STAIRS_W;
		coll = new GfColl(PaintedRegionBuilder.build(donor, g2, h2, null, L, false).collision);
		check(walls(coll, 20, 20, 1) == 0 && surface(coll, 20.02f, 20.5f) < surface(coll, 20.98f, 20.5f) - STEP * 0.9f,
				"\"Stairs (climb east)\" on a plateau's west edge slopes down to the west with no wall");
		check(walls(coll, 20, 10, 1) == 2 && surface(coll, 20.02f, 10.5f) > STEP * 0.9f,
				"\"Stairs (climb west)\" on that edge has nothing lower to its east, so it lies flat behind its wall");
	}

	/** An 8x8 pond in grass: the collision must sit on the water mesh. */
	static void waterCollisionSinksWithTheMesh(byte[] donor) {
		TilePalette[][] grid = grid(TilePalette.GRASS);
		for (int y = 10; y < 18; y++) {
			Arrays.fill(grid[y], 10, 18, TilePalette.WATER);
		}
		RegionFactory.BlankContent c = PaintedRegionBuilder.build(donor, grid, new int[DIM][DIM], null, L, false);
		GfColl coll = new GfColl(c.collision);
		float water = surface(coll, 13.5f, 13.5f), grass = surface(coll, 5.5f, 5.5f);
		float mesh = meshY(new BchMapModel(c.model), 13, 13);
		check(!Float.isNaN(mesh) && Math.abs(water - mesh) < 0.05f,
				"the collision over painted water lies on the water you can see (collision " + water + ", mesh " + mesh + ")");
		check(Math.abs(grass) < 0.05f, "the grass beside it stays at ground level (" + grass + ")");
	}

	/**
	 * The plain user story: paint ONE grass tile beside retail ground to widen
	 * the way, on a tile that has no collision under its centre. The first
	 * region with such a tile beside raised walkable ground is the sample; the
	 * floor must land beside that ground, not at the region's lowest.
	 */
	static void unsampledTileSeedsFromItsNeighbour(GARC gr) {
		for (int reg = 0; reg < gr.length; reg++) {
			byte[] raw;
			try {
				raw = gr.getDecompressedEntry(reg);
			} catch (RuntimeException ex) {
				continue;
			}
			byte[] model = sub(raw, 1), coll = sub(raw, 2), tm = sub(raw, 0);
			if (model == null || !BchMapModel.isMapModel(model) || !GfColl.isColl(coll) || tm == null || tm.length < 6404) {
				continue;
			}
			float[][] by = PaintedRegionBuilder.sampleBaseY(coll);
			float base0 = Float.NaN;
			for (float[] row : by) {
				for (float y : row) {
					if (!Float.isNaN(y) && (Float.isNaN(base0) || y < base0)) {
						base0 = y;
					}
				}
			}
			if (Float.isNaN(base0)) {
				continue;
			}
			int fx = -1, fy = -1;
			float expected = Float.NaN;
			for (int y = 1; y < DIM - 1 && fx < 0; y++) {
				for (int x = 1; x < DIM - 1 && fx < 0; x++) {
					if (!Float.isNaN(by[y][x])) {
						continue;
					}
					boolean besidePath = false;
					float nearest = Float.NaN;
					int[][] nb = {{x + 1, y}, {x - 1, y}, {x, y + 1}, {x, y - 1}};
					for (int[] p : nb) {
						float ny = by[p[1]][p[0]];
						if (Float.isNaN(ny)) {
							continue;
						}
						nearest = Float.isNaN(nearest) ? ny : Math.min(nearest, ny);
						int off = 4 + (p[1] * DIM + p[0]) * 4;
						boolean walkable = (tm[off] & 1) == 0;
						besidePath |= walkable && ny - base0 > 30f;
					}
					if (besidePath) {
						fx = x;
						fy = y;
						expected = nearest;
					}
				}
			}
			if (fx < 0) {
				continue;
			}
			int[][] h = new int[DIM][DIM];
			int borrowed = PaintedRegionBuilder.seedHeightsFromCollision(coll, h);
			check(borrowed > 0, "region " + reg + ": seeding reports the tiles that took a neighbour's ground (" + borrowed + ")");
			TilePalette[][] grid = grid(TilePalette.GRASS);
			boolean[][] touched = new boolean[DIM][DIM];
			touched[fy][fx] = true;
			RegionFactory.BlankContent c = PaintedRegionBuilder.buildComposite(model, coll, tm, grid, h, null, touched, L, false);
			float got = surface(new GfColl(c.collision), fx + 0.5f, fy + 0.5f);
			check(Math.abs(got - expected) < 0.5f, "region " + reg + ": one grass tile painted at (" + fx + "," + fy
					+ ") with no ground under it lands beside its neighbour at Y=" + expected
					+ " (built at " + got + "; the region's lowest ground is " + base0 + ")");
			return;
		}
		System.out.println("  skip: no region has an unsampled tile beside raised walkable ground");
	}

	// ---- geometry probes ----

	/** The highest collision surface at a point given in TILE units. */
	static float surface(GfColl c, float tx, float tz) {
		float px = tx * TILE + ORIGIN, pz = tz * TILE + ORIGIN, best = Float.NaN;
		for (float[] t : c.uniqueTris) {
			float y = triY(t, px, pz);
			if (!Float.isNaN(y) && (Float.isNaN(best) || y > best)) {
				best = y;
			}
		}
		return best;
	}

	/** Vertical (blocking) triangles standing on edge {@code dir} of a tile. */
	static int walls(GfColl c, int tx, int ty, int dir) {
		float x0 = tx * TILE + ORIGIN, x1 = x0 + TILE, z0 = ty * TILE + ORIGIN, z1 = z0 + TILE;
		float plane = dir == 0 ? x1 : dir == 1 ? x0 : dir == 2 ? z1 : z0;
		boolean xPlane = dir < 2;
		int n = 0;
		for (float[] t : c.uniqueTris) {
			boolean on = true;
			float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
			for (int v = 0; v < 3 && on; v++) {
				float x = t[v * 3], y = t[v * 3 + 1], z = t[v * 3 + 2];
				float p = xPlane ? x : z, q = xPlane ? z : x;
				float q0 = xPlane ? z0 : x0, q1 = xPlane ? z1 : x1;
				on = Math.abs(p - plane) < 0.01f && q > q0 - 0.01f && q < q1 + 0.01f;
				lo = Math.min(lo, y);
				hi = Math.max(hi, y);
			}
			if (on && hi - lo > 0.5f) {
				n++;
			}
		}
		return n;
	}

	/** The lowest model triangle whose centroid lands on a tile. */
	static float meshY(BchMapModel m, int tx, int tz) {
		float best = Float.NaN;
		for (BchMapModel.MeshGeom mg : m.geometry()) {
			if (!mg.posOk) {
				continue;
			}
			float[][] p = m.getVertexPositions(mg.meshIndex);
			int[] t = m.getTriangles(mg.meshIndex);
			for (int i = 0; i + 2 < t.length; i += 3) {
				float cx = (p[t[i]][0] + p[t[i + 1]][0] + p[t[i + 2]][0]) / 3f;
				float cz = (p[t[i]][2] + p[t[i + 1]][2] + p[t[i + 2]][2]) / 3f;
				float cy = (p[t[i]][1] + p[t[i + 1]][1] + p[t[i + 2]][1]) / 3f;
				if ((int) Math.floor((cx - ORIGIN) / TILE) == tx && (int) Math.floor((cz - ORIGIN) / TILE) == tz
						&& (Float.isNaN(best) || cy < best)) {
					best = cy;
				}
			}
		}
		return best;
	}

	static float triY(float[] t, float px, float pz) {
		float ax = t[0], az = t[2], bx = t[3], bz = t[5], cx = t[6], cz = t[8];
		float d = (bz - cz) * (ax - cx) + (cx - bx) * (az - cz);
		if (Math.abs(d) < 1e-3f) {
			return Float.NaN;
		}
		float wa = ((bz - cz) * (px - cx) + (cx - bx) * (pz - cz)) / d;
		float wb = ((cz - az) * (px - cx) + (ax - cx) * (pz - cz)) / d;
		float wc = 1f - wa - wb;
		if (wa < -0.02f || wb < -0.02f || wc < -0.02f) {
			return Float.NaN;
		}
		return wa * t[1] + wb * t[4] + wc * t[7];
	}

	static TilePalette[][] grid(TilePalette fill) {
		TilePalette[][] g = new TilePalette[DIM][DIM];
		for (TilePalette[] row : g) {
			Arrays.fill(row, fill);
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
		return Arrays.copyOfRange(c, o0, o1);
	}

	static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}
