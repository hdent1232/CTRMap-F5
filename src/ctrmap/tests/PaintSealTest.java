package ctrmap.tests;

import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.RegionFactory;
import ctrmap.formats.tilemap.PaintedHeights;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TerrainLighting;
import ctrmap.formats.tilemap.TilePalette;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * No sky through a painted map: wherever the ground steps, the drawn terrain closes the step
 * (F092).
 *
 * <p>WHY THIS SUITE EXISTS. The painter's own comments carried its open seams - a corner where a
 * one-step wall meets a two-step one "patched three times ... and still left slivers", and a
 * ramp skirt kept taller than it should be "until that is fixed", because bounding it let 2338
 * pixels of sky through where 348 had shown. A comment is a defect nobody owns: nothing failed
 * when a seam reopened, and nothing would notice when one was finally closed. This measures it.
 *
 * <p>HOW. Each terrain is painted twice onto the bundled model: from scratch, and as a COMPOSITE
 * edit onto an existing region (a flat painted one standing in for retail ground) touching only
 * the raised tiles - which is how the painter edits a real route, and the case where no backing
 * wall is built against the ground it leaves alone. At every tile boundary where the collision
 * floors on the two sides differ, horizontal segments are cast across the boundary at heights
 * between the two floors, from just inside the high side to {@link #REACH} tiles into the low
 * side. A segment that crosses no drawn triangle is a line of sight through the map - a hole.
 *
 * <p>CONTROLS. The probe must be able to see what it reports absent: the same probe over a
 * scene's FLOOR triangles alone (walls taken away) must find holes, the model's floor must stand
 * where the collision says it does, and a composite edit must not quietly be a from-scratch
 * build.
 *
 * <p>ORDER: needs no dump. The donor is {@code DummyBCH3DModel.bch}, one mesh, so every surface
 * lands in it; that is enough, because sealing is geometry and not material.
 *
 * Usage: java ctrmap.tests.PaintSealTest
 */
public class PaintSealTest {

	static final int DIM = PaintedRegionBuilder.DIM;
	static final float TILE = PaintedRegionBuilder.TILE;
	static final float ORIGIN = PaintedRegionBuilder.ORIGIN;

	/**
	 * How far into the low side a sight line reaches, in tiles. A two-level face leans a whole
	 * tile out: at 0.75 the composite terrace and two-level hill reported 90 and 430 sight lines
	 * through that were the probe stopping short of the face, and at 1.25, 1.5 and 2.0 both read 0.
	 */
	static final float REACH = 1.5f;

	/**
	 * Sight lines through the COMPOSITE notch - a ramp one tile proud of a hill's edge, painted
	 * onto ground the painter leaves alone - as measured when this suite was written: the hill's
	 * face beside the ramp's top stops short of it, and on an edge whose low side is untouched
	 * ground no backing wall stands behind the gap. Owned in OUTSTANDING.md. It may fall and may
	 * not rise; when it falls, lower it to what is measured.
	 */
	static final int NOTCH_COMPOSITE_CEILING = 22;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		byte[] donor = BchSectionsTest.resource("ctrmap/resources/DummyBCH3DModel.bch");
		Scene ground = paint(donor, new int[DIM][DIM], PaintedHeights.noRamps());

		//a level-1 hill: a plain wall on four sides and four outside corners
		int[][] hill = new int[DIM][DIM];
		fill(hill, 15, 10, 25, 20, 1);
		//a terrace: a two-level block reaching the north edge of a one-level hill, so a two-step
		//wall meets a one-step wall at (16,8) and (24,8) - the corner the comments patched thrice
		int[][] terrace = new int[DIM][DIM];
		fill(terrace, 12, 8, 28, 24, 1);
		fill(terrace, 16, 8, 24, 20, 2);
		//a ramp down the south edge of a hill, its sides against hill tiles
		int[][] ramped = new int[DIM][DIM];
		fill(ramped, 15, 10, 25, 20, 1);
		int[][] rampIn = PaintedHeights.noRamps();
		rampIn[20][20] = 2;
		//the notch: a ramp one tile proud of the hill, low ground on BOTH its sides - the place
		//the taller skirt is kept for
		int[][] notch = new int[DIM][DIM];
		fill(notch, 0, 0, DIM - 1, 18, 1);
		notch[19][20] = 1;
		int[][] rampOut = PaintedHeights.noRamps();
		rampOut[19][20] = 2;
		//a two-level drop taken by one ramp
		int[][] tall = new int[DIM][DIM];
		fill(tall, 15, 10, 25, 20, 2);
		int[][] rampTall = PaintedHeights.noRamps();
		rampTall[20][20] = 2;

		String[] names = {"a level-1 hill", "a two-step wall meeting a one-step wall", "a ramp down a hill's edge",
			"a ramp with low ground on both sides", "a ramp taking a two-level drop"};
		int[][][] heights = {hill, terrace, ramped, notch, tall};
		int[][][] ramps = {PaintedHeights.noRamps(), PaintedHeights.noRamps(), rampIn, rampOut, rampTall};
		Scene plain = paint(donor, hill, ramps[0]);
		Scene plainEdit = paintOnto(ground, hill, ramps[0]);

		//CONTROL: the model's floor is where the collision's is, high and low
		float topModel = floorY(plain.tris, 20.5f, 15.5f), topColl = surface(plain.coll, 20.5f, 15.5f);
		float lowModel = floorY(plain.tris, 5.5f, 5.5f), lowColl = surface(plain.coll, 5.5f, 5.5f);
		check(Math.abs(topModel - topColl) < 0.5f && Math.abs(lowModel - lowColl) < 0.5f && topColl - lowColl > 1f,
				"control: the drawn floor stands where the collision does (hill top " + topModel + " / " + topColl
				+ ", ground " + lowModel + " / " + lowColl + ")");
		//CONTROL: with the walls taken away, the probe sees through
		List<float[]> floorOnly = new ArrayList<>();
		for (float[] t : plain.tris) {
			if (Math.abs(normalY(t)) > 0.9f) {
				floorOnly.add(t);
			}
		}
		Seal bare = seal(floorOnly, plain.coll);
		check(bare.holes > 0 && floorOnly.size() < plain.tris.size(), "control: the same probe over the hill's floor alone ("
				+ floorOnly.size() + " of " + plain.tris.size() + " triangles) finds " + bare.holes + " holes in "
				+ bare.cast + " sight lines");
		Seal none = seal(plain.tris, ground.coll);
		check(none.cast == 0, "control: ground with no step casts no sight line (" + none.cast + ")");
		//CONTROL: the edit is a composite one - it keeps the ground's own triangles round the hill
		check(plainEdit.tris.size() != plain.tris.size() && Math.abs(surface(plainEdit.coll, 20.5f, 15.5f) - topColl) < 0.5f,
				"control: the hill painted onto existing ground is a composite edit (" + plainEdit.tris.size()
				+ " triangles, not the " + plain.tris.size() + " of a fresh build) and stands as high");

		for (int i = 0; i < names.length; i++) {
			Scene fresh = i == 0 ? plain : paint(donor, heights[i], ramps[i]);
			Seal r = seal(fresh.tris, fresh.coll);
			check(r.cast > 20 && r.holes == 0, names[i] + ", painted from scratch: every one of " + r.cast
					+ " sight lines across a step hits the terrain" + (r.holes == 0 ? "" : " - " + r.holes
					+ " see through, first at " + r.first));
			Scene edit = i == 0 ? plainEdit : paintOnto(ground, heights[i], ramps[i]);
			Seal e = seal(edit.tris, edit.coll);
			int allowed = heights[i] == notch ? NOTCH_COMPOSITE_CEILING : 0;
			check(e.cast > 20 && e.holes <= allowed, names[i] + ", painted onto existing ground: " + e.holes + " of "
					+ e.cast + " sight lines across a step see through (at most " + allowed + ")"
					+ (e.holes == 0 ? "" : ", first at " + e.first));
			check(e.holes >= allowed, names[i] + ", painted onto existing ground: the ceiling of " + allowed
					+ " is still what is measured" + (e.holes >= allowed ? "" : " - it is " + e.holes
					+ " now; lower NOTCH_COMPOSITE_CEILING to it and tick the item in OUTSTANDING.md if it is 0"));
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	static final class Scene {

		RegionFactory.BlankContent content;
		List<float[]> tris;
		GfColl coll;
	}

	static final class Seal {

		int cast;
		int holes;
		String first;
	}

	static Scene paint(byte[] donor, int[][] height, int[][] ramp) {
		TilePalette[][] grid = new TilePalette[DIM][DIM];
		for (TilePalette[] row : grid) {
			Arrays.fill(row, TilePalette.GRASS);
		}
		Scene s = new Scene();
		s.content = PaintedRegionBuilder.build(donor, grid, height, ramp, TerrainLighting.daytime(), false);
		s.tris = triangles(new BchMapModel(s.content.model));
		s.coll = new GfColl(s.content.collision);
		return s;
	}

	/** The terrain painted as a composite edit onto {@code ground}, touching only its raised and ramped tiles. */
	static Scene paintOnto(Scene ground, int[][] height, int[][] ramp) {
		boolean[][] touched = new boolean[DIM][DIM];
		for (int y = 0; y < DIM; y++) {
			for (int x = 0; x < DIM; x++) {
				touched[y][x] = height[y][x] > 0 || ramp[y][x] >= 0;
			}
		}
		TilePalette[][] grid = new TilePalette[DIM][DIM];
		for (TilePalette[] row : grid) {
			Arrays.fill(row, TilePalette.GRASS);
		}
		Scene s = new Scene();
		s.content = PaintedRegionBuilder.buildComposite(ground.content.model, ground.content.collision,
				ground.content.tilemap, grid, height, ramp, touched, TerrainLighting.daytime(), false);
		s.tris = triangles(new BchMapModel(s.content.model));
		s.coll = new GfColl(s.content.collision);
		return s;
	}

	static void fill(int[][] h, int x0, int y0, int x1, int y1, int level) {
		for (int y = y0; y <= y1; y++) {
			for (int x = x0; x <= x1; x++) {
				h[y][x] = level;
			}
		}
	}

	/** Every drawn triangle, as nine floats, from every mesh of the model. */
	static List<float[]> triangles(BchMapModel m) {
		List<float[]> out = new ArrayList<>();
		for (int mesh = 0; mesh < m.meshCount; mesh++) {
			float[][] pos = m.getVertexPositions(mesh);
			int[] idx = m.getTriangles(mesh);
			if (pos == null || idx == null) {
				continue;
			}
			for (int i = 0; i + 2 < idx.length; i += 3) {
				float[] t = new float[9];
				for (int v = 0; v < 3; v++) {
					System.arraycopy(pos[idx[i + v]], 0, t, v * 3, 3);
				}
				out.add(t);
			}
		}
		return out;
	}

	/**
	 * Where along each stepped tile edge the sight lines are cast. The two at the very ends are
	 * the corners: the slot where walls of different depth meet is a few units wide and sits
	 * right on the corner, so the middle of the edge never sees it. Measured with the corner
	 * posts taken out: the composite terrace showed 2 sight lines through at its two-step
	 * junction, 2% along the edge, and 0 at 10% to 90%.
	 */
	static final float[] ALONG = {0.02f, 0.1f, 0.3f, 0.5f, 0.7f, 0.9f, 0.98f};

	/**
	 * Casts the sight lines: at each of the {@link #ALONG} points of every tile boundary where the
	 * floors differ by more than a unit, four heights between them, each a segment from a quarter
	 * tile inside the high side to {@link #REACH} tiles into the low side.
	 */
	static Seal seal(List<float[]> tris, GfColl coll) {
		return seal(tris, coll, REACH);
	}

	/** The same, reaching {@code reach} tiles into the low side. */
	static Seal seal(List<float[]> tris, GfColl coll, float reach) {
		Seal r = new Seal();
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				for (int dir = 0; dir < 2; dir++) { //0: the boundary to the east, 1: to the south
					if ((dir == 0 && tx + 1 >= DIM) || (dir == 1 && ty + 1 >= DIM)) {
						continue;
					}
					for (float along : ALONG) {
						//tile coordinates of the boundary point, and the unit step across it
						float bx = dir == 0 ? tx + 1 : tx + along, bz = dir == 0 ? ty + along : ty + 1;
						float sx = dir == 0 ? 1 : 0, sz = dir == 0 ? 0 : 1;
						float yA = surface(coll, bx - sx * 0.05f, bz - sz * 0.05f);
						float yB = surface(coll, bx + sx * 0.05f, bz + sz * 0.05f);
						if (Float.isNaN(yA) || Float.isNaN(yB) || Math.abs(yA - yB) <= 1f) {
							continue;
						}
						float sign = yA > yB ? 1 : -1; //+1: the high side is the near one
						float lo = Math.min(yA, yB), hi = Math.max(yA, yB);
						for (int j = 1; j <= 4; j++) {
							float y = lo + (hi - lo) * j / 5f;
							float[] p = world(bx - sign * sx * 0.25f, y, bz - sign * sz * 0.25f);
							float[] q = world(bx + sign * sx * reach, y, bz + sign * sz * reach);
							r.cast++;
							if (!crosses(tris, p, q)) {
								r.holes++;
								if (r.first == null) {
									r.first = String.format("tile (%d,%d) %s edge, %.0f%% along, y %.1f between %.1f and %.1f",
											tx, ty, dir == 0 ? "east" : "south", along * 100, y, lo, hi);
								}
							}
						}
					}
				}
			}
		}
		return r;
	}

	static float[] world(float tx, float y, float tz) {
		return new float[]{tx * TILE + ORIGIN, y, tz * TILE + ORIGIN};
	}

	/** True when segment p-q passes through any of the triangles (Moller-Trumbore). */
	static boolean crosses(List<float[]> tris, float[] p, float[] q) {
		float dx = q[0] - p[0], dy = q[1] - p[1], dz = q[2] - p[2];
		for (float[] t : tris) {
			float e1x = t[3] - t[0], e1y = t[4] - t[1], e1z = t[5] - t[2];
			float e2x = t[6] - t[0], e2y = t[7] - t[1], e2z = t[8] - t[2];
			float hx = dy * e2z - dz * e2y, hy = dz * e2x - dx * e2z, hz = dx * e2y - dy * e2x;
			float a = e1x * hx + e1y * hy + e1z * hz;
			if (Math.abs(a) < 1e-7f) {
				continue;
			}
			float f = 1f / a;
			float sx = p[0] - t[0], sy = p[1] - t[1], sz = p[2] - t[2];
			float u = f * (sx * hx + sy * hy + sz * hz);
			if (u < 0f || u > 1f) {
				continue;
			}
			float qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
			float v = f * (dx * qx + dy * qy + dz * qz);
			if (v < 0f || u + v > 1f) {
				continue;
			}
			float s = f * (e2x * qx + e2y * qy + e2z * qz);
			if (s >= 0f && s <= 1f) {
				return true;
			}
		}
		return false;
	}

	static float normalY(float[] t) {
		float e1x = t[3] - t[0], e1y = t[4] - t[1], e1z = t[5] - t[2];
		float e2x = t[6] - t[0], e2y = t[7] - t[1], e2z = t[8] - t[2];
		float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		return len == 0 ? 0 : ny / len;
	}

	/** The highest drawn surface over a point, given in tile coordinates. */
	static float floorY(List<float[]> tris, float tx, float tz) {
		float px = tx * TILE + ORIGIN, pz = tz * TILE + ORIGIN, best = Float.NaN;
		for (float[] t : tris) {
			float y = PaintedFloorTest.triY(t, px, pz);
			if (!Float.isNaN(y) && (Float.isNaN(best) || y > best)) {
				best = y;
			}
		}
		return best;
	}

	static float surface(GfColl c, float tx, float tz) {
		return PaintedFloorTest.surface(c, tx, tz);
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
