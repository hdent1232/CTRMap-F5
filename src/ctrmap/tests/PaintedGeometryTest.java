package ctrmap.tests;

import ctrmap.formats.tilemap.PaintedCliffMesh;
import ctrmap.formats.tilemap.PaintedGeometry;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What the painter's stages share, with nothing but a polyline or a quad in hand (F090): the one
 * outline rounding and the one way to flip a quad, both now in {@link PaintedGeometry}.
 *
 * <p>WHY. The split of PaintedRegionBuilder moved copies of these into separate stage files, and
 * the copies drifted. The shoreline carried its own rounding beside the cliff's, capped at 0.34 of
 * a tile against 0.3, so a change to one never reached the other. Three corner swaps flipped a
 * quad, and two of them moved a corner's position and UV but not its shading: a cliff gusset's back
 * face came out lit diagonally while its front was lit top to bottom. One function each now, and
 * DuplicateWorkTest refuses a second.
 *
 * <p>WHAT IS CHECKED. A flipped quad has, at every position, the corner attributes it had before -
 * for the swap itself, for winding a quad that faces down, and for the gusset's two faces. The
 * rounding keeps a straight run where it was, pins the ends of an open chain, keeps a loop closed,
 * and lets no vertex travel past its cap - for the cliff's cap and the shoreline's, since both are
 * this one function. These ran only inside PaintedRegionTest, which needs the game's map GARC; a
 * guard that cannot run without a dump does not guard the change it is for.
 *
 * <p>Not: PaintedRegionTest - that builds whole regions from a retail donor model and checks what
 * the build produced; this checks the pieces every build is made of.
 *
 * Usage: java ctrmap.tests.PaintedGeometryTest
 */
public class PaintedGeometryTest {

	static int fails = 0;

	static void check(boolean cond, String what) {
		System.out.println((cond ? "  ok: " : "  FAIL: ") + what);
		if (!cond) {
			fails++;
		}
	}

	static Class<?> quadClass;
	static Constructor<?> newQuad;
	static Field pos, uv, nrm, ao;

	public static void main(String[] args) throws Exception {
		System.out.println("--- the painter's shared geometry: one rounding, one flip (F090)");
		quadClass = Class.forName("ctrmap.formats.tilemap.PaintedGeometry$Quad");
		newQuad = quadClass.getDeclaredConstructor();
		newQuad.setAccessible(true);
		pos = field("pos");
		uv = field("uv");
		nrm = field("nrm");
		ao = field("ao");
		Method swap = method(PaintedGeometry.class, "swapCorners12", quadClass);
		Method towards = method(PaintedGeometry.class, "fixWindingTowards", quadClass, float.class, float.class, float.class);
		Method gusset = method(PaintedCliffMesh.class, "gusset", float[].class, float[].class, float[].class, float[].class,
				boolean.class);

		//control: the comparison sees a corner whose shading stayed behind, and passes a full swap
		Object q = quad(new float[][]{{0, 10, 0}, {18, 10, 0}, {0, 0, 0}, {18, 0, 0}}, new float[]{0.78f, 0.78f, 0.60f, 0.60f});
		Object half = copy(q);
		swapArray((float[][]) pos.get(half));
		swapArray((float[][]) uv.get(half));
		Object full = copy(q);
		swap.invoke(null, full);
		check(!astray(q, half).isEmpty() && astray(q, full).isEmpty() && swapped(q, full),
				"control: a swap that leaves the shading behind is seen (" + astray(q, half) + "), and the one swap"
				+ " carries position, UV, normal and shading together");

		//winding a quad that faces down: it ends up facing up, every corner keeping what it had
		Object down = quad(new float[][]{{0, 0, 0}, {0, 0, 18}, {18, 0, 0}, {18, 0, 18}}, new float[]{0.9f, 0.8f, 0.7f, 0.6f});
		float before = normalY(down);
		Object up = copy(down);
		towards.invoke(null, up, 0f, 1f, 0f);
		check(before < 0 && normalY(up) > 0 && astray(down, up).isEmpty(), "winding a quad that faced down ("
				+ before + ") turns it up (" + normalY(up) + ") and moves no attribute off its corner "
				+ astray(down, up));

		//A ramp's side skirt is zero-height at the top of its slope, so the first triangle it is drawn as can
		//have no area at all. The facing was read off that triangle alone: asked to face either way it kept
		//the winding it was built with, and one corridor wall of every painted ramp faced away from the ramp
		//(2026-10-18). It turns by the quad's whole drawn face.
		Object skirt = quad(new float[][]{{0, 18, 0}, {0, 18, 18}, {0, 18, 0}, {0, 0, 18}}, new float[]{0.85f, 0.85f, 0.65f, 0.65f});
		Object east = copy(skirt), west = copy(skirt);
		towards.invoke(null, east, 1f, 0f, 0f);
		towards.invoke(null, west, -1f, 0f, 0f);
		check(drawnX(skirt, true) == 0f && drawnX(east, false) > 0 && drawnX(west, false) < 0,
				"a quad whose first triangle has no area (its first half drawn " + drawnX(skirt, true) + ") still turns to"
				+ " face east (" + drawnX(east, false) + ") and west (" + drawnX(west, false) + ") as asked");
		//and a face asked to point square to itself has no side that does: refused, never left as built
		String square = null;
		try {
			towards.invoke(null, copy(skirt), 0f, 1f, 0f);
		} catch (java.lang.reflect.InvocationTargetException refused) {
			square = String.valueOf(refused.getCause().getMessage());
		}
		String none = "not refused";
		try {
			towards.invoke(null, quad(new float[][]{{5, 5, 5}, {5, 5, 5}, {5, 5, 5}, {5, 5, 5}}, new float[4]), 0f, 1f, 0f);
		} catch (java.lang.reflect.InvocationTargetException refused) {
			none = String.valueOf(refused.getCause().getMessage());
		}
		check(square != null && square.contains("neither of its sides faces that way") && "not refused".equals(none),
				"a wall asked to face straight up is refused (" + square + "), and a quad with no area - which draws"
				+ " nothing - is not (" + none + ")");

		//the cliff gusset's back face is shaded like its front
		float[] tl = {0, 36, 0}, tr = {6, 36, 3}, bl = {0, 0, 0}, br = {6, 0, 3};
		Object front = gusset.invoke(null, tl, tr, bl, br, false);
		Object back = gusset.invoke(null, tl, tr, bl, br, true);
		List<String> off = astray(front, back);
		check(swapped(front, back) && off.isEmpty(),
				"a cliff gusset's back face winds the other way and is shaded corner for corner like its front"
				+ (off.isEmpty() ? "" : " - these corners are not: " + off));

		aCornerIsBuiltFromTheHeightsAtIt();
		wallsWhoseTopsDoNotLineUpAreNotOneStrip();
		anEdgeRefusesAPointThatIsNotOneOfItsEnds();
		checkRounding("the cliff's cap", PaintedGeometry.CLIFF_PULL, PaintedGeometry.TILE * 0.3f);
		checkRounding("the shoreline's cap", shorePull(), PaintedGeometry.TILE * 0.34f);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * The outline rounding on its own, with a polyline in hand and nothing else, at one cap. Three
	 * invariants the map-sealing argument rests on: the ends of an open chain do not move
	 * (neighbouring chains must still meet), a closed loop stays closed, and no vertex travels more
	 * than the cap (a one-tile path must not be smoothed out of existence from both sides). A straight
	 * run is left exactly where it was, which is what proves the averaging is a rounding and not a
	 * drift.
	 */
	static void checkRounding(String which, float cap, float expected) {
		final float T = PaintedGeometry.TILE;
		check(cap == expected, which + " is " + cap + ", " + (cap / T) + " of a tile, as recorded (" + expected + ")");

		//a straight run: every vertex is already the mean of its neighbours
		float[] sx = {0, T, 2 * T, 3 * T, 4 * T, 5 * T}, sz = {T, T, T, T, T, T};
		float[][] r = PaintedGeometry.roundOutline(sx, sz, false, cap);
		check(Arrays.equals(r[0], sx) && Arrays.equals(r[1], sz), which + ": rounding leaves a straight run exactly"
				+ " where it was");

		//an open staircase: ends pinned, corners cut, nobody travels past the cap
		float[] px = {0, T, T, 2 * T, 2 * T, 3 * T, 3 * T, 4 * T};
		float[] pz = {0, 0, T, T, 2 * T, 2 * T, 3 * T, 3 * T};
		r = PaintedGeometry.roundOutline(px, pz, false, cap);
		int last = px.length - 1;
		boolean pinned = r[0][0] == px[0] && r[1][0] == pz[0] && r[0][last] == px[last] && r[1][last] == pz[last];
		float maxTravel = 0f;
		int moved = 0;
		for (int i = 0; i < px.length; i++) {
			float d = (float) Math.hypot(r[0][i] - px[i], r[1][i] - pz[i]);
			maxTravel = Math.max(maxTravel, d);
			if (d > 1e-4f) {
				moved++;
			}
		}
		check(pinned, which + ": the ends of an open chain stay put, so neighbouring chains still meet");
		check(moved >= 4, which + ": a staircase is rounded (" + moved + " vertices moved)");
		check(maxTravel <= cap + 1e-3f && maxTravel >= cap - 1e-3f, which + ": the travel cap binds on a right-angle"
				+ " staircase and is never passed (max travel " + maxTravel + ", cap " + cap + ")");

		//a closed square: no ends to pin, and it must still close after rounding
		float[] lx = {0, 2 * T, 2 * T, 0, 0}, lz = {0, 0, 2 * T, 2 * T, 0};
		r = PaintedGeometry.roundOutline(lx, lz, true, cap);
		boolean closed = r[0][0] == r[0][4] && r[1][0] == r[1][4];
		float d0 = (float) Math.hypot(r[0][0] - lx[0], r[1][0] - lz[0]);
		boolean symmetric = true;
		for (int i = 1; i < 4; i++) {
			float di = (float) Math.hypot(r[0][i] - lx[i], r[1][i] - lz[i]);
			symmetric &= Math.abs(di - d0) < 1e-3f;
		}
		check(closed && d0 > 1e-3f && d0 <= cap + 1e-3f && symmetric, which + ": a closed loop stays closed and its"
				+ " four corners are cut alike (" + d0 + ")");
	}

	/** The shoreline's cap is the stages' own, package-private: read it, do not restate it. */
	static float shorePull() throws Exception {
		Field f = PaintedGeometry.class.getDeclaredField("SHORE_PULL");
		f.setAccessible(true);
		return f.getFloat(null);
	}

	/**
	 * Every corner of {@code b} whose UV, normal or shading is not what {@code a} has at the same
	 * position - a flip that moved a position and left an attribute behind.
	 */
	static List<String> astray(Object a, Object b) throws Exception {
		float[][] pa = (float[][]) pos.get(a), pb = (float[][]) pos.get(b);
		List<String> out = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			int j = -1;
			for (int k = 0; k < 4; k++) {
				if (Arrays.equals(pa[k], pb[i])) {
					j = k;
				}
			}
			if (j < 0) {
				out.add("corner " + i + " is at a position the other face does not have");
				continue;
			}
			if (!Arrays.equals(((float[][]) uv.get(a))[j], ((float[][]) uv.get(b))[i])
					|| !Arrays.equals(((float[][]) nrm.get(a))[j], ((float[][]) nrm.get(b))[i])
					|| ((float[]) ao.get(a))[j] != ((float[]) ao.get(b))[i]) {
				out.add("corner " + i + " at " + Arrays.toString(pb[i]) + " (shading " + ((float[]) ao.get(b))[i]
						+ " where the other face has " + ((float[]) ao.get(a))[j] + ")");
			}
		}
		return out;
	}

	/** {@code b} is {@code a} with corners 1 and 2 exchanged and 0 and 3 where they were. */
	static boolean swapped(Object a, Object b) throws Exception {
		float[][] pa = (float[][]) pos.get(a), pb = (float[][]) pos.get(b);
		return Arrays.equals(pa[0], pb[0]) && Arrays.equals(pa[1], pb[2]) && Arrays.equals(pa[2], pb[1])
				&& Arrays.equals(pa[3], pb[3]);
	}

	/** y of the face normal of corners 0, 2, 1 - the order the stages wind by. */
	static float normalY(Object q) throws Exception {
		float[][] p = (float[][]) pos.get(q);
		float[] a = p[0], b = p[2], c = p[1];
		float ux = b[0] - a[0], uz = b[2] - a[2];
		float vx = c[0] - a[0], vz = c[2] - a[2];
		return uz * vx - ux * vz;
	}

	/**
	 * A corner is built from its walls' heights AT it (2026-10-18). A ramp's side face falls with the ramp, so one
	 * edge has two tops, and every reader at a corner - the deepest drop the feet there stretch to, whether two walls
	 * run on as one strip, the strip's own height there - asks for the top at that corner. Two walls meet at P, a
	 * level one falling 18 and a sloping one 18 at P: whether the sloping one's far end stands at 54 or at 20, what
	 * is built at P above its ground is the same. CONTROL: with the height at P itself changed, it is not. (On the
	 * ground it differs by design: a stepped face lays a run per step of its deeper neighbour, so a far end three
	 * steps high adds a collapsed run at P's foot - measured, flat at 0 and -2.)
	 */
	static void aCornerIsBuiltFromTheHeightsAtIt() throws Exception {
		System.out.println("--- a corner is built from its walls' heights at it, not at their far ends");
		Method edge = method(PaintedCliffMesh.class, "cliffEdge", int.class, int.class, int.class, float.class, float.class,
				int.class, float.class);
		Method emit = method(PaintedCliffMesh.class, "emitCliffStrips", List.class, java.util.Map.class, int.class);
		for (boolean turn : new boolean[]{false, true}) {
			String where = turn ? "where the walls turn" : "on a straight run";
			List<String> far54 = profileAtP(edge, emit, 54f, 18f, turn), far20 = profileAtP(edge, emit, 20f, 18f, turn);
			List<String> at27 = profileAtP(edge, emit, 54f, 27f, turn);
			check(far54.size() > 2 && !far54.equals(at27), "control, " + where + ": what is built at P is read ("
					+ far54.size() + " points on its profile), and it moves when the height AT P does: " + far54 + " / " + at27);
			check(far54.equals(far20), where + ", a wall falling to 18 at P builds P alike whether its far end stands at 54"
					+ " or 20: " + far54 + " / " + far20);
		}
	}

	/**
	 * The points built on P's profile - the ray out of P = (0, 18) along the walls' shared outward direction - by a
	 * level wall falling 18 that ends at P and a ramp's west side running on from P to (0, 0), its top {@code atP}
	 * at P and {@code farTop} at its far end; those above the ground the walls fall to (y over half a unit), each
	 * rounded to a tenth. The level wall is tile (20,21)'s west edge, (0,36) -> P, straight on into the ramp's
	 * side - or, {@code turn}, tile (19,21)'s north edge, (-18,18) -> P, an inside corner of the two: joined
	 * strips round a turn and two open ends do not, so only there does whether they join show.
	 */
	static List<String> profileAtP(Method edge, Method emit, float farTop, float atP, boolean turn) throws Exception {
		Object level = turn ? edge.invoke(null, 19, 21, 3, 0f, 18f, -1, 18f) : edge.invoke(null, 20, 21, 1, 0f, 18f, -1, 18f);
		Object ramp = edge.invoke(null, 20, 20, 1, 0f, farTop, 2, atP);
		//the shared outward direction: west on the straight run, north-west at the turn
		float dx = -1f, dz = turn ? -1f : 0f, dl = (float) Math.hypot(dx, dz);
		dx /= dl;
		dz /= dl;
		java.util.Map<Integer, List<Object>> out = new java.util.HashMap<>();
		emit.invoke(null, new ArrayList<>(Arrays.asList(level, ramp)), out, 0);
		java.util.TreeSet<String> at = new java.util.TreeSet<>();
		for (Object q : out.getOrDefault(0, new ArrayList<>())) {
			for (float[] p : (float[][]) pos.get(q)) {
				float rx = p[0], rz = p[2] - 18f;
				if (Math.abs(rx * dz - rz * dx) < 0.05f && rx * dx + rz * dz > -0.01f && p[1] > 0.5f) {
					at.add(String.format("(%.1f,%.1f,%.1f)", p[0], p[1], p[2]));
				}
			}
		}
		return new ArrayList<>(at);
	}

	/**
	 * TWO WALLS WHOSE TOPS ARE MORE THAN A STEP APART ARE NOT ONE STRIP (mutation sweep of 2026-10-25). Two one-step
	 * walls in a straight run, one standing four steps above the other, have the same height and run straight on, so
	 * only the line-up of their tops keeps them apart - and inverted, it joined exactly these and every suite passed.
	 * Joined, the strip averages the two at the corner they share and stands a blade in the gap between them.
	 * CONTROL: two walls whose tops ARE a step apart do run on as one, and the probe sees the averaged corner.
	 */
	static void wallsWhoseTopsDoNotLineUpAreNotOneStrip() throws Exception {
		System.out.println("--- two walls whose tops are more than a step apart are not built as one strip");
		Method edge = method(PaintedCliffMesh.class, "cliffEdge", int.class, int.class, int.class, float.class, float.class);
		Method emit = method(PaintedCliffMesh.class, "emitCliffStrips", List.class, java.util.Map.class, int.class);
		List<Float> lined = heightsBuilt(edge, emit, 18f, 36f);
		check(inGap(lined, 18f, 36f), "control: one-step walls whose tops line up run on as one strip, averaged at the"
				+ " corner they share: " + lined);
		//the upper wall's foot is sunk below its fall, as every foot is (measured: 70 under a fall to 72, -2 under 0)
		List<Float> apart = heightsBuilt(edge, emit, 72f, 90f);
		check(!apart.isEmpty() && !inGap(apart, 18f, 70f), "one-step walls whose tops are four steps apart are built"
				+ " apart, nothing standing in the gap between the lower one's top, 18, and the upper one's foot, 70: " + apart);
	}

	/**
	 * The heights built, to a tenth, by a one-step wall on tile (20,21)'s west edge falling from {@code top} to
	 * {@code bottom}, running straight on into one on tile (20,20)'s west edge falling from 18 to 0.
	 */
	static List<Float> heightsBuilt(Method edge, Method emit, float bottom, float top) throws Exception {
		Object upper = edge.invoke(null, 20, 21, 1, bottom, top);
		Object lower = edge.invoke(null, 20, 20, 1, 0f, 18f);
		java.util.Map<Integer, List<Object>> out = new java.util.HashMap<>();
		emit.invoke(null, new ArrayList<>(Arrays.asList(upper, lower)), out, 0);
		java.util.TreeSet<Float> ys = new java.util.TreeSet<>();
		for (Object q : out.getOrDefault(0, new ArrayList<>())) {
			for (float[] p : (float[][]) pos.get(q)) {
				ys.add(Math.round(p[1] * 10f) / 10f);
			}
		}
		return new ArrayList<>(ys);
	}

	/** Whether any height lies strictly between {@code lo} and {@code hi}, half a unit clear of both. */
	static boolean inGap(List<Float> ys, float lo, float hi) {
		for (float y : ys) {
			if (y > lo + 0.5f && y < hi - 0.5f) {
				return true;
			}
		}
		return false;
	}

	/**
	 * AN EDGE ASKED FOR A POINT THAT IS NOT ONE OF ITS ENDS REFUSES (mutation sweep of 2026-10-25). Every reader asks
	 * for the end it stands at, so the refusal is all that stands between a reader gone wrong and a top read off the
	 * wrong end without a word - swallowed into an answer, it passed every suite.
	 */
	static void anEdgeRefusesAPointThatIsNotOneOfItsEnds() throws Exception {
		System.out.println("--- a cliff edge asked for the top at a point that is not one of its ends refuses");
		Method edge = method(PaintedCliffMesh.class, "cliffEdge", int.class, int.class, int.class, float.class, float.class);
		Object e = edge.invoke(null, 20, 20, 1, 0f, 18f);
		Method topAt = method(e.getClass(), "topAt", String.class);
		Object start = method(e.getClass(), "startKey").invoke(e), end = method(e.getClass(), "endKey").invoke(e);
		check((Float) topAt.invoke(e, start) == 18f && (Float) topAt.invoke(e, end) == 18f,
				"control: each end of a level edge answers its top, 18");
		Throwable refused = null;
		try {
			topAt.invoke(e, "-1:-1");
		} catch (java.lang.reflect.InvocationTargetException ex) {
			refused = ex.getCause();
		}
		check(refused instanceof IllegalArgumentException && refused.getMessage().contains("is not an end of the edge"),
				"a point that is neither end is refused, not answered with a height: " + refused);
	}

	/**
	 * The x of the quad's face as drawn: the normals of triangles (0,2,1) and (1,2,3) - the order
	 * PaintedFloorMesh.meshFromQuads writes - summed, or the first one's alone.
	 */
	static float drawnX(Object q, boolean firstOnly) throws Exception {
		float[][] p = (float[][]) pos.get(q);
		float x = 0f;
		for (int[] t : firstOnly ? new int[][]{{0, 2, 1}} : new int[][]{{0, 2, 1}, {1, 2, 3}}) {
			float[] a = p[t[0]], b = p[t[1]], c = p[t[2]];
			x += (b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1]);
		}
		return x;
	}

	static Object quad(float[][] corners, float[] shade) throws Exception {
		Object q = newQuad.newInstance();
		float[][] p = (float[][]) pos.get(q), t = (float[][]) uv.get(q), n = (float[][]) nrm.get(q);
		float[] s = (float[]) ao.get(q);
		for (int c = 0; c < 4; c++) {
			p[c] = corners[c].clone();
			t[c] = new float[]{c * 0.25f, 1f - c * 0.25f};
			n[c] = new float[]{0f, 1f, c * 0.1f};
			s[c] = shade[c];
		}
		return q;
	}

	static Object copy(Object q) throws Exception {
		float[][] p = (float[][]) pos.get(q);
		Object c = quad(p, (float[]) ao.get(q));
		for (int i = 0; i < 4; i++) {
			((float[][]) uv.get(c))[i] = ((float[][]) uv.get(q))[i].clone();
			((float[][]) nrm.get(c))[i] = ((float[][]) nrm.get(q))[i].clone();
		}
		return c;
	}

	static void swapArray(float[][] a) {
		float[] t = a[1];
		a[1] = a[2];
		a[2] = t;
	}

	static Field field(String name) throws Exception {
		Field f = quadClass.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}

	static Method method(Class<?> owner, String name, Class<?>... params) throws Exception {
		Method m = owner.getDeclaredMethod(name, params);
		m.setAccessible(true);
		return m;
	}
}
