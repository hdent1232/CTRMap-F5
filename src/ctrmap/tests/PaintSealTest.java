package ctrmap.tests;

import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.RegionFactory;
import ctrmap.formats.tilemap.PaintedGeometry;
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
 * floors on the two sides differ, a viewer stands on the low side at heights between the two
 * floors - the nearest place past the boundary that is open air - and looks back across it to
 * just inside the high side. A look that meets no drawn triangle FACING THE VIEWER is a line of
 * sight through the map - a hole.
 *
 * <p>WHY ONE-SIDED, AND WHY THE NEAREST VIEWER (2026-10-18). The probe counted a triangle met from
 * either side, and a segment out to a fixed reach. The game culls a face seen from behind - the
 * builder's own comments say so - so one corridor wall of every painted ramp, wound away from the
 * ramp, read as sealed here and showed sky in the game; and a segment that ran on past where anyone
 * could stand counted terrain beyond the viewer, so two lanes of one ramp with nothing at all
 * between them read as sealed too. Measured on the sources before the fix: 23 and 24 sight lines
 * through in two of the three ramp terrains this suite painted then, where the old probe read 0,
 * and 43 across two lanes whose feet land at different levels.
 *
 * <p>AND NOTHING HUNG ON AN EDGE STANDS IN THE AIR. A wall hung exactly on a tile boundary - a
 * ramp's skirt - stands no higher than the ground on one side of it or the other; one standing above
 * both is a blade in the open (the skirt pinned to its ramp's top beside lower ground was one).
 *
 * <p>CONTROLS. The probe must be able to see what it reports absent: the same probe over a
 * scene's FLOOR triangles alone (walls taken away) must find holes, and so must the same scene with
 * every triangle turned to face the other way; the side it calls a triangle's front is read off the
 * flat floor, which is seen from above; the model's floor must stand where the collision says it
 * does; a composite edit must not quietly be a from-scratch build; and a blade planted in the open
 * must be found.
 *
 * <p>ORDER: needs no dump. The donor is {@code DummyBCH3DModel.bch}, one mesh, so every surface
 * lands in it; that is enough, because sealing is geometry and not material.
 *
 * Usage: java ctrmap.tests.PaintSealTest
 */
public class PaintSealTest {

	static final int DIM = PaintedGeometry.DIM;
	static final float TILE = PaintedGeometry.TILE;
	static final float ORIGIN = PaintedGeometry.ORIGIN;

	/**
	 * How far into the low side a viewer is looked for, in tiles. A two-level face leans a whole
	 * tile out, and a viewer standing under it is inside the rock: at 0.75 the composite terrace and
	 * two-level hill reported 90 and 430 sight lines through that were the probe stopping short of
	 * the face, and at 1.25, 1.5 and 2.0 both read 0.
	 */
	static final float REACH = 1.5f;

	/**
	 * +1 when a drawn triangle's front is the side its index-order normal points to, -1 the other;
	 * read off the flat ground's floor, which is seen from above. 0 - nothing faces anyone, every
	 * sight line a hole - until it is read.
	 */
	static float front = 0f;

	/*
	 * The COMPOSITE notch - a ramp one tile proud of a hill's edge, painted onto ground the painter
	 * leaves alone - let 22 of 1140 sight lines through when this suite was written: the hill's face
	 * beside the ramp's top stopped short of it, and on an edge whose low side is untouched ground no
	 * backing wall stood behind the gap. That count was held as a ceiling that might only fall. It
	 * fell to 0 on 2026-10-09, when a face sharing a corner with a painted ramp was backed like any
	 * other (PaintedRegionBuilder, F092), and every terrain here is now held to none.
	 */

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		byte[] donor = BchSectionsTest.resource("ctrmap/resources/DummyBCH3DModel.bch");
		Scene ground = paint(donor, new int[DIM][DIM], PaintedHeights.noRamps());
		//CONTROL: which side of a triangle is its front, read off a floor everyone sees from above
		int[] upDown = new int[2];
		front = frontOf(ground.tris, upDown);
		check(front != 0f, "control: the flat ground's floor, seen from above, is drawn all one way (" + upDown[0]
				+ " facing up by its index order, " + upDown[1] + " down), so a triangle's front is the side a sight line must meet");

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
		//two lanes of one ramp whose feet land at different levels: the lower lane's floor falls away from
		//the higher one's, and the gap between the two slopes is a wall neither side was building
		int[][] lanes = new int[DIM][DIM];
		fill(lanes, 15, 10, 25, 20, 2);
		lanes[21][21] = 1;
		int[][] rampLanes = PaintedHeights.noRamps();
		rampLanes[20][20] = 2;
		rampLanes[20][21] = 2;
		//a ramp going east - the other axis, whose skirts hang on the north and south edges
		int[][] east = new int[DIM][DIM];
		fill(east, 15, 10, 25, 20, 1);
		int[][] rampEast = PaintedHeights.noRamps();
		rampEast[15][25] = 0;
		//a ramp on the map's west edge, its one side off the map
		int[][] edge = new int[DIM][DIM];
		fill(edge, 0, 10, 10, 20, 1);
		int[][] rampEdge = PaintedHeights.noRamps();
		rampEdge[20][0] = 2;
		//a two-level ramp standing out from a hill onto a one-level ledge, open ground on both its sides
		int[][] pen = new int[DIM][DIM];
		fill(pen, 15, 10, 25, 19, 2);
		pen[20][20] = 2;
		fill(pen, 18, 21, 22, 21, 1);
		int[][] rampPen = PaintedHeights.noRamps();
		rampPen[20][20] = 2;
		//a ramp cut between a higher wall on one side and its own level on the other
		int[][] mixed = new int[DIM][DIM];
		fill(mixed, 15, 10, 25, 20, 1);
		fill(mixed, 15, 10, 19, 20, 2);
		mixed[21][20] = 0;
		int[][] rampMixed = PaintedHeights.noRamps();
		rampMixed[20][20] = 2;
		//a two-level ramp onto a one-level ledge, its east side at the ledge's level and its west side open: at
		//its low corners walls of different depth meet, and what stands there is read at the ramp's FOOT
		int[][] corner = new int[DIM][DIM];
		fill(corner, 15, 10, 25, 19, 2);
		corner[20][20] = 2;
		corner[21][20] = 1;
		corner[20][21] = 1;
		int[][] rampCorner = PaintedHeights.noRamps();
		rampCorner[20][20] = 2;

		String[] names = {"a level-1 hill", "a two-step wall meeting a one-step wall", "a ramp down a hill's edge",
			"a ramp with low ground on both sides", "a ramp taking a two-level drop",
			"two lanes of a ramp whose feet land at different levels", "a ramp going east",
			"a ramp on the map's edge", "a two-level ramp standing out onto a ledge",
			"a ramp between a higher wall and its own level", "a ramp whose low corners meet walls of two depths"};
		int[][][] heights = {hill, terrace, ramped, notch, tall, lanes, east, edge, pen, mixed, corner};
		int[][][] ramps = {PaintedHeights.noRamps(), PaintedHeights.noRamps(), rampIn, rampOut, rampTall,
			rampLanes, rampEast, rampEdge, rampPen, rampMixed, rampCorner};
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
		//CONTROL: with every triangle turned to face the other way, the probe sees through each wall it met
		List<float[]> turned = new ArrayList<>();
		for (float[] t : plain.tris) {
			turned.add(new float[]{t[0], t[1], t[2], t[6], t[7], t[8], t[3], t[4], t[5]});
		}
		Seal asBuilt = seal(plain.tris, plain.coll), back = seal(turned, plain.coll);
		check(asBuilt.holes == 0 && back.cast >= asBuilt.cast && back.holes == back.cast && back.cast > 20,
				"control: the hill with every triangle turned the other way shows " + back.holes + " of " + back.cast
				+ " sight lines through, as built " + asBuilt.holes + " - a wall seen from behind is not a wall");
		//CONTROL: a blade planted on open ground is found
		float bx = 5 * TILE + ORIGIN, bz = 5 * TILE + ORIGIN;
		List<float[]> planted = new ArrayList<>(plain.tris);
		planted.add(new float[]{bx, 0f, bz, bx, 10f, bz, bx, 10f, bz + TILE});
		List<String> blade = wallsAboveGround(planted, plain.coll, new int[1]);
		check(wallsAboveGround(plain.tris, plain.coll, new int[1]).isEmpty() && blade.size() == 1,
				"control: a wall planted on a tile edge in the open is found standing above the ground (" + blade + ")");
		Seal none = seal(plain.tris, ground.coll);
		check(none.cast == 0, "control: ground with no step casts no sight line (" + none.cast + ")");
		//CONTROL: the edit is a composite one - it keeps the ground it does not touch. Told apart by triangle count
		//until every face was backed (2026-10-18), when a composite edit of flat ground came out exactly a fresh
		//build: so the ground here carries a bump the edit never touches, and the edit must still have it.
		int[][] bump = new int[DIM][DIM];
		bump[5][5] = 1;
		Scene marked = paintOnto(paint(donor, bump, PaintedHeights.noRamps()), hill, PaintedHeights.noRamps());
		check(Math.abs(surface(marked.coll, 5.5f, 5.5f) - topColl) < 0.5f && Math.abs(surface(plain.coll, 5.5f, 5.5f)) < 0.5f
				&& Math.abs(surface(marked.coll, 20.5f, 15.5f) - topColl) < 0.5f && Math.abs(surface(plainEdit.coll, 20.5f, 15.5f) - topColl) < 0.5f,
				"control: the hill painted onto existing ground is a composite edit - it keeps the bump it never touched ("
				+ surface(marked.coll, 5.5f, 5.5f) + ", a fresh build has " + surface(plain.coll, 5.5f, 5.5f) + ") and stands as high");

		int[] counted = new int[2];   //edge-hung wall triangles, looks along ramps' sides
		for (int i = 0; i < names.length; i++) {
			judge(names[i] + ", painted from scratch", i == 0 ? plain : paint(donor, heights[i], ramps[i]), ramps[i], counted);
			judge(names[i] + ", painted onto existing ground", i == 0 ? plainEdit : paintOnto(ground, heights[i], ramps[i]),
					ramps[i], counted);
		}
		//A ramp cut into a cliff the painter leaves alone: painted onto ground that already holds a two-level hill,
		//touching only the ramp's tile. The faces flanking it are rebuilt on the untouched cliff's side, on its
		//retail top - the faces no terrain above has, and the ones a backing wall is still withheld from.
		int[][] cliff = new int[DIM][DIM];
		fill(cliff, 15, 10, 25, 19, 2);
		Scene cliffGround = paint(donor, cliff, PaintedHeights.noRamps());
		int[][] cut = new int[DIM][DIM];
		cut[19][20] = 1;
		int[][] rampCut = PaintedHeights.noRamps();
		rampCut[19][20] = 2;
		Scene cutIn = paintOnto(cliffGround, cut, rampCut);
		float two = 2 * PaintedGeometry.STEP;
		check(Math.abs(surface(cutIn.coll, 17.5f, 15.5f) - two) < 0.5f && surface(cutIn.coll, 20.5f, 19.05f) < two - 2f,
				"control: the cut keeps the cliff it was painted onto (" + surface(cutIn.coll, 17.5f, 15.5f) + " beside it) and"
				+ " lowers only its own tile (" + surface(cutIn.coll, 20.5f, 19.05f) + " at the ramp's top)");
		judge("a ramp cut into a cliff the painter leaves alone", cutIn, rampCut, counted);
		//and a ledge painted at the cliff's foot, no ramp: the cliff's face over it is rebuilt on its untouched side
		//(unbacked), the ledge's own shallower faces meet it at its corners - walls of two depths, one unbacked
		int[][] ledge = new int[DIM][DIM];
		ledge[20][20] = 1;
		ledge[20][21] = 1;
		judge("a ledge painted at the foot of a cliff the painter leaves alone", paintOnto(cliffGround, ledge,
				PaintedHeights.noRamps()), PaintedHeights.noRamps(), counted);
		check(counted[0] > 0, "control: the terrains hang " + counted[0] + " wall triangles on tile edges for that to be asked of");
		check(counted[1] > 0, "control: " + counted[1] + " looks were taken along ramps' sides");
		aDonorThatCannotBeKeptIsRefused(ground);
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * The three things asked of every painted terrain: every sight line across a step meets the terrain facing
	 * it, no wall hung on a tile edge stands above the ground on both sides, and nothing stands over a ramp's
	 * floor along its sides. {@code counted} sums the edge-hung wall triangles and the looks along ramps' sides.
	 */
	static void judge(String what, Scene s, int[][] ramp, int[] counted) {
		Seal r = seal(s.tris, s.coll);
		check(r.cast > 20 && r.holes == 0, what + ": every one of " + r.cast + " sight lines across a step meets the terrain"
				+ " facing it" + (r.holes == 0 ? "" : " - " + r.holes + " see through, first at " + r.first));
		int[] walls = new int[1];
		List<String> blades = wallsAboveGround(s.tris, s.coll, walls);
		counted[0] += walls[0];
		check(blades.isEmpty(), what + ": none of its " + walls[0] + " edge-hung wall triangles stands above the ground on"
				+ " both sides" + (blades.isEmpty() ? "" : " - " + blades.size() + " do, first " + blades.get(0)));
		int[] looks = new int[1];
		List<String> parapets = overRampSides(s.tris, s.coll, ramp, looks);
		counted[1] += looks[0];
		check(parapets.isEmpty(), what + ": along its ramps' sides nothing stands over the floor (" + looks[0] + " looks)"
				+ (parapets.isEmpty() ? "" : " - " + parapets.size() + " meet something, first " + parapets.get(0)));
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
		s.content = PaintedRegionBuilder.buildComposite(null, ground.content.model, ground.content.collision,
				ground.content.tilemap, grid, height, ramp, touched, TerrainLighting.daytime(), false);
		s.tris = triangles(new BchMapModel(s.content.model));
		s.coll = new GfColl(s.content.collision);
		return s;
	}

	/**
	 * Census H.64: painting over a region whose tilemap or collision cannot be kept is refused, naming the subfile.
	 * The composite rebuilt the tilemap from the painted grid alone, and kept none of an unreadable collision - the
	 * touched mask, the whole point of painting over a region, dropped with nothing said.
	 */
	static void aDonorThatCannotBeKeptIsRefused(Scene ground) {
		System.out.println("--- painting over a region whose tilemap or collision cannot be kept is refused (census H.64)");
		int[][] height = new int[DIM][DIM];
		boolean[][] touched = new boolean[DIM][DIM];
		touched[20][20] = true;
		TilePalette[][] grid = new TilePalette[DIM][DIM];
		for (TilePalette[] row : grid) {
			Arrays.fill(row, TilePalette.GRASS);
		}
		byte[][][] donors = {
			{ground.content.collision, ground.content.tilemap},
			{ground.content.collision, Arrays.copyOf(ground.content.tilemap, 100)},
			{new byte[16], ground.content.tilemap}};
		String[] said = new String[donors.length];
		for (int i = 0; i < donors.length; i++) {
			try {
				PaintedRegionBuilder.buildComposite(null, ground.content.model, donors[i][0], donors[i][1], grid, height,
						PaintedHeights.noRamps(), touched, TerrainLighting.daytime(), false);
			} catch (IllegalStateException refused) {
				said[i] = refused.getMessage();
			}
		}
		check(said[0] == null, "control: a region whose subfiles read is painted over: " + said[0]);
		check(said[1] != null && said[1].contains("tilemap (subfile 0)") && said[1].contains("Nothing was painted"),
				"a tilemap that cannot be kept is refused, naming it: " + said[1]);
		check(said[2] != null && said[2].contains("collision (subfile 2)") && said[2].contains("Nothing was painted"),
				"and so is a collision: " + said[2]);
		//the tilemap composite asks the same rule of what it is handed, though its one caller asks first - so no paint
		//reaches its refusal (the 2026-10-14 shared sweep, PaintedTiles 41, dropped, SURVIVED). Asked directly: a cut
		//tilemap clones short, and the first painted tile past its end would be written out of bounds.
		String composite;
		try {
			java.lang.reflect.Method build = Class.forName("ctrmap.formats.tilemap.PaintedTiles").getDeclaredMethod(
					"buildTilemapComposite", byte[].class, TilePalette[][].class, boolean[][].class);
			build.setAccessible(true);
			byte[] built = (byte[]) build.invoke(null, Arrays.copyOf(ground.content.tilemap, 100), grid, touched);
			composite = built == null ? "it answered no tilemap" : "it built " + built.length + " bytes";
		} catch (java.lang.reflect.InvocationTargetException e) {
			composite = String.valueOf(e.getCause() == null ? null : e.getCause().getMessage());
		} catch (ReflectiveOperationException e) {
			composite = "it could not be asked: " + e;
		}
		check(("the region's tilemap (subfile 0) is not a " + DIM + " by " + DIM + " tilemap").equals(composite),
				"the tilemap composite refuses a tilemap it cannot keep, in the rule's words: " + composite);
		//census M.118: one that starts like a collision and does not read is said, never sampled as flat ground
		byte[] damaged = Arrays.copyOf(ground.content.collision, 0x40);
		check(ctrmap.formats.gfcollision.GfColl.isColl(damaged), "control: the cut collision still starts like one");
		String heights = null;
		try {
			PaintedHeights.sampleBaseY(damaged);
		} catch (IllegalStateException refused) {
			heights = refused.getMessage();
		}
		check(heights != null && heights.contains("subfile 2") && heights.contains("does not read"),
				"its ground heights are refused as unknown, not answered as no ground anywhere: " + heights);
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
	 * posts taken out, before every face over untouched ground was backed: the composite terrace
	 * showed 2 sight lines through at its two-step junction, 2% along the edge, and 0 at 10% to
	 * 90%. (The posts are gone: on 2026-10-18 they closed nothing in any terrain here.)
	 */
	static final float[] ALONG = {0.02f, 0.1f, 0.3f, 0.5f, 0.7f, 0.9f, 0.98f};

	/**
	 * Casts the sight lines: at each of the {@link #ALONG} points of every tile boundary where the
	 * floors differ by more than a unit, four heights between them. At each, the viewer is the
	 * NEAREST point past the boundary, within {@link #REACH} tiles, that is open air - above the
	 * collision floor and under no drawn surface facing up, half a unit clear of it - because every
	 * farther viewer's look contains this one's. It looks back to a quarter tile inside the high
	 * side, and must meet a triangle whose front faces it. A height with nowhere to stand is no
	 * sight line.
	 */
	static Seal seal(List<float[]> tris, GfColl coll) {
		return seal(tris, coll, 0, 0, DIM - 1, DIM - 1);
	}

	/** The same, over the boundaries of tiles (x0,y0) to (x1,y1) only - a painted patch on ground that is not. */
	static Seal seal(List<float[]> tris, GfColl coll, int x0, int y0, int x1, int y1) {
		Seal r = new Seal();
		for (int ty = y0; ty <= y1; ty++) {
			for (int tx = x0; tx <= x1; tx++) {
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
							float stand = -1f;
							for (int k = 1; k * 0.05f <= REACH + 1e-4f; k++) {
								float vx = bx + sign * sx * k * 0.05f, vz = bz + sign * sz * k * 0.05f;
								float f = surface(coll, vx, vz);
								if (!Float.isNaN(f) && f < y - 0.01f && !underUpFacing(tris, vx, y, vz)) {
									stand = k * 0.05f;
									break;
								}
							}
							if (stand < 0f) {
								continue;
							}
							float[] p = world(bx - sign * sx * 0.25f, y, bz - sign * sz * 0.25f);
							float[] q = world(bx + sign * sx * stand, y, bz + sign * sz * stand);
							r.cast++;
							if (!meetsFacing(tris, q, p)) {
								r.holes++;
								if (r.first == null) {
									r.first = String.format("tile (%d,%d) %s edge, %.0f%% along, y %.1f between %.1f and %.1f,"
											+ " seen from %.2f tiles out", tx, ty, dir == 0 ? "east" : "south", along * 100, y, lo, hi, stand);
								}
							}
						}
					}
				}
			}
		}
		return r;
	}

	/** True when a drawn surface facing up lies over (tx, y, tz), within half a unit of it or higher: inside the rock. */
	static boolean underUpFacing(List<float[]> tris, float tx, float y, float tz) {
		float px = tx * TILE + ORIGIN, pz = tz * TILE + ORIGIN;
		for (float[] t : tris) {
			float ty = PaintedFloorTest.triY(t, px, pz);
			if (Float.isNaN(ty) || ty <= y - 0.5f) {
				continue;
			}
			float[] n = normal(t);
			float len = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
			if (len > 0f && front * n[1] / len > 0.05f) {
				return true;
			}
		}
		return false;
	}

	/** True when the look from {@code viewer} to {@code target} meets a triangle whose front faces the viewer. */
	static boolean meetsFacing(List<float[]> tris, float[] viewer, float[] target) {
		float dx = target[0] - viewer[0], dy = target[1] - viewer[1], dz = target[2] - viewer[2];
		List<float[]> facing = new ArrayList<>();
		for (float[] t : tris) {
			float[] n = normal(t);
			if (front * (n[0] * dx + n[1] * dy + n[2] * dz) < 0f) {
				facing.add(t);
			}
		}
		return crosses(facing, viewer, target);
	}

	/**
	 * The drawn triangles lying in a tile-boundary plane and spanning most of an edge - a ramp's skirt,
	 * not a short piece ten units across a junction - with a corner standing above the collision floor
	 * on BOTH sides of that line there; {@code checked[0]} counts every such wall triangle looked at.
	 */
	static List<String> wallsAboveGround(List<float[]> tris, GfColl coll, int[] checked) {
		List<String> out = new ArrayList<>();
		for (float[] t : tris) {
			for (int axis = 0; axis < 3; axis += 2) {
				float a = (t[axis] - ORIGIN) / TILE;
				boolean onLine = Math.abs(a - Math.round(a)) < 0.002f;
				for (int v = 1; v < 3 && onLine; v++) {
					onLine = Math.abs(t[v * 3 + axis] - t[axis]) < 0.02f;
				}
				int run = 2 - axis;
				float from = Math.min(t[run], Math.min(t[3 + run], t[6 + run]));
				float to = Math.max(t[run], Math.max(t[3 + run], t[6 + run]));
				if (!onLine || to - from < TILE * 0.75f) {
					continue;
				}
				checked[0]++;
				float mid = ((t[run] + t[3 + run] + t[6 + run]) / 3f - ORIGIN) / TILE;
				for (int v = 0; v < 3; v++) {
					//read a hair in from the corner, so a corner reads the two tiles its own edge runs between
					float at = (t[v * 3 + run] - ORIGIN) / TILE;
					at += (mid - at) * 0.02f;
					float line = (t[v * 3 + axis] - ORIGIN) / TILE;
					float y1 = axis == 0 ? surface(coll, line - 0.05f, at) : surface(coll, at, line - 0.05f);
					float y2 = axis == 0 ? surface(coll, line + 0.05f, at) : surface(coll, at, line + 0.05f);
					if (!Float.isNaN(y1) && !Float.isNaN(y2) && t[v * 3 + 1] > Math.max(y1, y2) + 0.1f) {
						out.add(String.format("(%.1f, %.1f, %.1f) %.1f over the floors %.1f / %.1f", t[v * 3], t[v * 3 + 1],
								t[v * 3 + 2], t[v * 3 + 1] - Math.max(y1, y2), y1, y2));
						break;
					}
				}
			}
		}
		return out;
	}

	/**
	 * Along each side of each painted ramp (the two edges its slope runs along) - 2, 40, 50, 60 and 98% along -
	 * horizontal looks across the edge, 0.15 tiles either way, at 2, 6 and 12 units over the highest floor within
	 * 0.15 tiles: open air, which nothing drawn should cross. The face along a ramp's side stood at the ramp's TOP
	 * the whole way down, a step over its own foot (2026-10-18); the ends are looked at too, because what stands
	 * at a ramp's corner, where walls of two depths can meet, is read at that corner's height. {@code looks[0]}
	 * counts the looks taken.
	 */
	static List<String> overRampSides(List<float[]> tris, GfColl coll, int[][] ramp, int[] looks) {
		List<String> out = new ArrayList<>();
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				int rd = ramp[ty][tx];
				if (rd < 0) {
					continue;
				}
				for (int side : rd == 0 || rd == 1 ? new int[]{2, 3} : new int[]{0, 1}) {
					for (float along : new float[]{0.02f, 0.4f, 0.5f, 0.6f, 0.98f}) {
						//the boundary point, and the unit step across it
						float bx = side == 0 ? tx + 1 : side == 1 ? tx : tx + along;
						float bz = side == 2 ? ty + 1 : side == 3 ? ty : ty + along;
						float sx = side < 2 ? 1 : 0, sz = side < 2 ? 0 : 1;
						float h = Float.NaN;
						for (int i = -3; i <= 3; i++) {
							for (int k = -3; k <= 3; k++) {
								float f = surface(coll, bx + 0.05f * i, bz + 0.05f * k);
								h = Float.isNaN(f) ? h : Float.isNaN(h) ? f : Math.max(h, f);
							}
						}
						if (Float.isNaN(h)) {
							continue;
						}
						for (float over : new float[]{2f, 6f, 12f}) {
							looks[0]++;
							float[] p = world(bx - sx * 0.15f, h + over, bz - sz * 0.15f);
							float[] q = world(bx + sx * 0.15f, h + over, bz + sz * 0.15f);
							if (crosses(tris, p, q)) {
								out.add(String.format("the ramp at (%d,%d), %s side, %.0f%% along, %.0f over its floor (%.1f)", tx, ty,
										side == 0 ? "east" : side == 1 ? "west" : side == 2 ? "south" : "north", along * 100, over, h));
							}
						}
					}
				}
			}
		}
		return out;
	}

	/**
	 * +1 or -1 for {@link #front}, read off the triangles of {@code tris} lying flat - floors, seen
	 * from above - when every one of them is drawn the same way; 0 when they disagree or none lies
	 * flat. {@code upDown} gets the two counts.
	 */
	static float frontOf(List<float[]> tris, int[] upDown) {
		for (float[] t : tris) {
			float[] n = normal(t);
			float len = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
			if (len > 0f && Math.abs(n[1] / len) > 0.9f) {
				upDown[n[1] > 0 ? 0 : 1]++;
			}
		}
		return upDown[0] > 0 && upDown[1] == 0 ? 1f : upDown[1] > 0 && upDown[0] == 0 ? -1f : 0f;
	}

	/** A triangle's normal by its index order, unnormalised. */
	static float[] normal(float[] t) {
		float e1x = t[3] - t[0], e1y = t[4] - t[1], e1z = t[5] - t[2];
		float e2x = t[6] - t[0], e2y = t[7] - t[1], e2z = t[8] - t[2];
		return new float[]{e1y * e2z - e1z * e2y, e1z * e2x - e1x * e2z, e1x * e2y - e1y * e2x};
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
