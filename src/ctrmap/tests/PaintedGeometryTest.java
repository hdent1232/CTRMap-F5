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

		//the cliff gusset's back face is shaded like its front
		float[] tl = {0, 36, 0}, tr = {6, 36, 3}, bl = {0, 0, 0}, br = {6, 0, 3};
		Object front = gusset.invoke(null, tl, tr, bl, br, false);
		Object back = gusset.invoke(null, tl, tr, bl, br, true);
		List<String> off = astray(front, back);
		check(swapped(front, back) && off.isEmpty(),
				"a cliff gusset's back face winds the other way and is shaded corner for corner like its front"
				+ (off.isEmpty() ? "" : " - these corners are not: " + off));

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
