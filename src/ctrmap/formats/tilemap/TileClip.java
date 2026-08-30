package ctrmap.formats.tilemap;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact XZ clipping of triangles against a rectilinear tile region - the
 * geometric core of the Map Builder's composite (edit-in-place) mode. Retail
 * map triangles are NOT per-tile tessellated (a single floor triangle can span
 * hundreds of tiles), so membership tests are the wrong tool: painting a tile
 * must CUT the covering triangles at the tile's boundary, keeping the outside
 * parts byte-faithful and discarding only what lies inside.
 *
 * <p>Vertices are handled as generic float component vectors (position plus
 * any other decoded attributes - normals, UVs, colors); clip points lerp every
 * component, so cut geometry keeps its texturing and lighting. The caller says
 * which components are X and Z.
 *
 * <p>Vertical triangles (walls - zero XZ area, un-clippable in plan view) are
 * classified whole: {@link #segmentTouchesRegion} reports whether the wall
 * touches the region (closed bounds, so a wall standing exactly ON a painted
 * tile's edge counts - that is where the generator puts cliff walls).
 */
public final class TileClip {

	/** A rectangle of the region: {x0, z0, x1, z1} in world units (closed). */
	public static List<float[]> regionRects(boolean[][] touched, float tile, float origin, float dilate) {
		List<float[]> rects = new ArrayList<>();
		int dim = touched.length;
		//row-run decomposition, then greedy vertical merge of identical runs
		int[][] runs = new int[dim][]; //per row: pairs x0,x1 flattened
		for (int y = 0; y < dim; y++) {
			List<Integer> r = new ArrayList<>();
			int x = 0;
			while (x < dim) {
				if (touched[y][x]) {
					int s = x;
					while (x < dim && touched[y][x]) {
						x++;
					}
					r.add(s);
					r.add(x - 1);
				} else {
					x++;
				}
			}
			runs[y] = new int[r.size()];
			for (int i = 0; i < r.size(); i++) {
				runs[y][i] = r.get(i);
			}
		}
		boolean[][] consumed = new boolean[dim][];
		for (int y = 0; y < dim; y++) {
			consumed[y] = new boolean[runs[y].length / 2];
		}
		for (int y = 0; y < dim; y++) {
			for (int i = 0; i < runs[y].length / 2; i++) {
				if (consumed[y][i]) {
					continue;
				}
				int x0 = runs[y][i * 2], x1 = runs[y][i * 2 + 1];
				int yEnd = y;
				//extend downward while an identical run exists
				for (int yy = y + 1; yy < dim; yy++) {
					int match = -1;
					for (int j = 0; j < runs[yy].length / 2; j++) {
						if (!consumed[yy][j] && runs[yy][j * 2] == x0 && runs[yy][j * 2 + 1] == x1) {
							match = j;
							break;
						}
					}
					if (match < 0) {
						break;
					}
					consumed[yy][match] = true;
					yEnd = yy;
				}
				rects.add(new float[]{
					x0 * tile + origin - dilate,
					y * tile + origin - dilate,
					(x1 + 1) * tile + origin + dilate,
					(yEnd + 1) * tile + origin + dilate});
			}
		}
		return rects;
	}

	/**
	 * Subtracts the region from one convex polygon: returns the (convex) parts
	 * of the polygon OUTSIDE every rect. Each vertex is a full component vector;
	 * xIdx/zIdx locate the plan-view coordinates. Returns the input list object
	 * unchanged (same identity) when no rect intersected it - callers use that
	 * to keep original geometry byte-exact.
	 */
	public static List<List<float[]>> subtractRegion(List<float[]> poly, List<float[]> rects, int xIdx, int zIdx) {
		List<List<float[]>> parts = new ArrayList<>();
		parts.add(poly);
		boolean changed = false;
		for (float[] r : rects) {
			List<List<float[]>> next = new ArrayList<>();
			for (List<float[]> p : parts) {
				if (!bboxIntersects(p, r, xIdx, zIdx)) {
					next.add(p);
					continue;
				}
				changed = true;
				subtractRect(p, r, xIdx, zIdx, next);
			}
			parts = next;
			if (parts.isEmpty()) {
				break;
			}
		}
		if (!changed) {
			List<List<float[]>> same = new ArrayList<>();
			same.add(poly);
			return same;
		}
		return parts;
	}

	/**
	 * 1D-clips a VERTICAL polygon (a wall - zero plan area, its plan projection
	 * a segment) against the region: retail walls span many tiles just like
	 * floors, so region contact must CUT them at the covered stretch, keeping
	 * the parts over untouched ground. Cuts run along the wall's dominant plan
	 * axis; every vertex component lerps. Returns the input object unchanged
	 * (same identity) when the region does not touch the wall; an empty list
	 * when the wall is entirely covered.
	 */
	public static List<List<float[]>> clipVerticalPoly(List<float[]> poly, List<float[]> rects, int xIdx, int zIdx) {
		float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		for (float[] v : poly) {
			minX = Math.min(minX, v[xIdx]);
			maxX = Math.max(maxX, v[xIdx]);
			minZ = Math.min(minZ, v[zIdx]);
			maxZ = Math.max(maxZ, v[zIdx]);
		}
		boolean alongX = (maxX - minX) >= (maxZ - minZ);
		int comp = alongX ? xIdx : zIdx;
		float lo = alongX ? minX : minZ, hi = alongX ? maxX : maxZ;
		if (hi - lo < 0.05f) {
			//point-like wall (a post): covered-or-not is all there is
			float px = (minX + maxX) / 2f, pz = (minZ + maxZ) / 2f;
			for (float[] r : rects) {
				if (px >= r[0] && px <= r[2] && pz >= r[1] && pz <= r[3]) {
					return new ArrayList<>();
				}
			}
			List<List<float[]>> same = new ArrayList<>();
			same.add(poly);
			return same;
		}
		//covered intervals along the dominant axis: where the wall's plan
		//segment runs inside a rect. The other axis is linear along the
		//segment, so Liang-Barsky t-intervals map straight to axis coords.
		//The segment endpoints are the extremes along the dominant axis.
		float[] p0 = extreme(poly, comp, false), p1 = extreme(poly, comp, true);
		List<float[]> covered = new ArrayList<>();
		for (float[] r : rects) {
			float[] t = segmentRectInterval(p0[xIdx], p0[zIdx], p1[xIdx], p1[zIdx], r);
			if (t != null) {
				float c0 = p0[comp] + (p1[comp] - p0[comp]) * t[0];
				float c1 = p0[comp] + (p1[comp] - p0[comp]) * t[1];
				covered.add(new float[]{Math.min(c0, c1), Math.max(c0, c1)});
			}
		}
		if (covered.isEmpty()) {
			List<List<float[]>> same = new ArrayList<>();
			same.add(poly);
			return same;
		}
		//merge covered intervals, then keep the complement within [lo, hi]
		covered.sort((a, b) -> Float.compare(a[0], b[0]));
		List<float[]> keep = new ArrayList<>();
		float cursor = lo;
		for (float[] iv : covered) {
			if (iv[0] > cursor + 0.05f) {
				keep.add(new float[]{cursor, iv[0]});
			}
			cursor = Math.max(cursor, iv[1]);
		}
		if (hi > cursor + 0.05f) {
			keep.add(new float[]{cursor, hi});
		}
		List<List<float[]>> out = new ArrayList<>();
		for (float[] iv : keep) {
			//cut the wall polygon to the surviving stretch: two half-plane
			//splits along the dominant axis (never parallel to the wall)
			List<float[]> part = split(poly, comp, iv[0], xIdx)[1];
			if (part.size() >= 3) {
				part = split(part, comp, iv[1], xIdx)[0];
			}
			if (part.size() >= 3 && extent(part, comp) > 0.05f) {
				out.add(part);
			}
		}
		return out;
	}

	private static float[] extreme(List<float[]> poly, int comp, boolean max) {
		float[] best = poly.get(0);
		for (float[] v : poly) {
			if (max ? v[comp] > best[comp] : v[comp] < best[comp]) {
				best = v;
			}
		}
		return best;
	}

	private static float extent(List<float[]> p, int comp) {
		float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
		for (float[] v : p) {
			lo = Math.min(lo, v[comp]);
			hi = Math.max(hi, v[comp]);
		}
		return hi - lo;
	}

	/** The [t0,t1] parameter interval of the segment inside the closed rect, or null. */
	static float[] segmentRectInterval(float x0, float z0, float x1, float z1, float[] r) {
		float t0 = 0f, t1 = 1f;
		float dx = x1 - x0, dz = z1 - z0;
		float[] p = {-dx, dx, -dz, dz};
		float[] q = {x0 - r[0], r[2] - x0, z0 - r[1], r[3] - z0};
		for (int i = 0; i < 4; i++) {
			if (Math.abs(p[i]) < 1e-9f) {
				if (q[i] < 0) {
					return null;
				}
			} else {
				float t = q[i] / p[i];
				if (p[i] < 0) {
					t0 = Math.max(t0, t);
				} else {
					t1 = Math.min(t1, t);
				}
				if (t0 > t1) {
					return null;
				}
			}
		}
		return new float[]{t0, t1};
	}

	/** True when the polygon's XZ bbox overlaps the rect (open interiors). */
	static boolean bboxIntersects(List<float[]> poly, float[] r, int xIdx, int zIdx) {
		float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		for (float[] v : poly) {
			minX = Math.min(minX, v[xIdx]);
			maxX = Math.max(maxX, v[xIdx]);
			minZ = Math.min(minZ, v[zIdx]);
			maxZ = Math.max(maxZ, v[zIdx]);
		}
		return maxX > r[0] && minX < r[2] && maxZ > r[1] && minZ < r[3];
	}

	/**
	 * Splits a convex polygon by one rect: the four outside bands are emitted
	 * into {@code out}; the part inside the rect is discarded. Standard
	 * band decomposition - each band is convex.
	 */
	static void subtractRect(List<float[]> poly, float[] r, int xIdx, int zIdx, List<List<float[]>> out) {
		//west band: x <= r[0]
		List<float[]>[] s = split(poly, xIdx, r[0], xIdx);
		emit(s[0], out, xIdx, zIdx);
		List<float[]> core = s[1];
		if (core.isEmpty()) {
			return;
		}
		//east band: x >= r[2]
		s = split(core, xIdx, r[2], xIdx);
		emit(s[1], out, xIdx, zIdx);
		core = s[0];
		if (core.isEmpty()) {
			return;
		}
		//north band: z <= r[1]
		s = split(core, zIdx, r[1], xIdx);
		emit(s[0], out, xIdx, zIdx);
		core = s[1];
		if (core.isEmpty()) {
			return;
		}
		//south band: z >= r[3]
		s = split(core, zIdx, r[3], xIdx);
		emit(s[1], out, xIdx, zIdx);
		//s[0] = inside the rect - discarded
	}

	private static void emit(List<float[]> p, List<List<float[]>> out, int xIdx, int zIdx) {
		if (p.size() >= 3 && area2(p, xIdx, zIdx) > 1e-4) {
			out.add(p);
		}
	}

	/** Plan-view double-area using explicit component indices. */
	public static float area2(List<float[]> p, int xIdx, int zIdx) {
		float a = 0;
		for (int i = 0; i < p.size(); i++) {
			float[] u = p.get(i), v = p.get((i + 1) % p.size());
			a += u[xIdx] * v[zIdx] - v[xIdx] * u[zIdx];
		}
		return Math.abs(a);
	}

	/**
	 * Splits a convex polygon by the plane {@code comp == c}: result[0] = the
	 * part with comp <= c, result[1] = comp >= c. Sutherland-Hodgman on both
	 * sides; crossing points lerp EVERY component. {@code posBase} is the first
	 * POSITION component (x) - duplicate collapse compares position, never a
	 * possibly-constant leading attribute.
	 */
	@SuppressWarnings("unchecked")
	static List<float[]>[] split(List<float[]> poly, int comp, float c, int posBase) {
		List<float[]> lo = new ArrayList<>();
		List<float[]> hi = new ArrayList<>();
		int n = poly.size();
		for (int i = 0; i < n; i++) {
			float[] a = poly.get(i), b = poly.get((i + 1) % n);
			float da = a[comp] - c, db = b[comp] - c;
			if (da <= 0) {
				lo.add(a);
			}
			if (da >= 0) {
				hi.add(a);
			}
			if ((da < 0 && db > 0) || (da > 0 && db < 0)) {
				float t = da / (da - db);
				float[] x = lerp(a, b, t);
				x[comp] = c; //exact on the cut plane
				lo.add(x);
				hi.add(x);
			}
		}
		return new List[]{dedupe(lo, posBase), dedupe(hi, posBase)};
	}

	static float[] lerp(float[] a, float[] b, float t) {
		float[] x = new float[a.length];
		for (int i = 0; i < a.length; i++) {
			x[i] = a[i] + (b[i] - a[i]) * t;
		}
		return x;
	}

	/** Removes consecutive duplicate points a split can produce; positions are
	 *  the three components starting at {@code posBase}. */
	static List<float[]> dedupe(List<float[]> p, int posBase) {
		if (p.size() < 3) {
			return p;
		}
		List<float[]> out = new ArrayList<>();
		for (float[] v : p) {
			if (out.isEmpty() || !samePoint(out.get(out.size() - 1), v, posBase)) {
				out.add(v);
			}
		}
		while (out.size() > 1 && samePoint(out.get(0), out.get(out.size() - 1), posBase)) {
			out.remove(out.size() - 1);
		}
		return out;
	}

	private static boolean samePoint(float[] a, float[] b, int posBase) {
		float d = 0;
		for (int i = posBase; i < Math.min(posBase + 3, a.length); i++) {
			d += Math.abs(a[i] - b[i]);
		}
		return d < 1e-4f;
	}

	/** Fan-triangulates a convex polygon: indices into the poly (0, i, i+1). */
	public static List<float[][]> fan(List<float[]> poly) {
		List<float[][]> tris = new ArrayList<>();
		for (int i = 1; i + 1 < poly.size(); i++) {
			tris.add(new float[][]{poly.get(0), poly.get(i), poly.get(i + 1)});
		}
		return tris;
	}

	/**
	 * True when the XZ segment/point set of a VERTICAL triangle touches any
	 * region rect (closed bounds - boundary contact counts). Pass the tri's
	 * plan-view points; collinearity is the caller's classification.
	 */
	public static boolean segmentTouchesRegion(float[][] xz, List<float[]> rects) {
		for (float[] r : rects) {
			for (int i = 0; i < xz.length; i++) {
				float[] a = xz[i], b = xz[(i + 1) % xz.length];
				if (segmentIntersectsRect(a[0], a[1], b[0], b[1], r)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Liang-Barsky with closed bounds. */
	static boolean segmentIntersectsRect(float x0, float z0, float x1, float z1, float[] r) {
		float t0 = 0f, t1 = 1f;
		float dx = x1 - x0, dz = z1 - z0;
		float[] p = {-dx, dx, -dz, dz};
		float[] q = {x0 - r[0], r[2] - x0, z0 - r[1], r[3] - z0};
		for (int i = 0; i < 4; i++) {
			if (Math.abs(p[i]) < 1e-9f) {
				if (q[i] < 0) {
					return false;
				}
			} else {
				float t = q[i] / p[i];
				if (p[i] < 0) {
					t0 = Math.max(t0, t);
				} else {
					t1 = Math.min(t1, t);
				}
				if (t0 > t1) {
					return false;
				}
			}
		}
		return true;
	}

	private TileClip() {
	}
}
