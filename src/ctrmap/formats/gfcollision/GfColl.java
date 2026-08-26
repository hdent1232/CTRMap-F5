package ctrmap.formats.gfcollision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spec-exact reader/writer for the ORAS "coll" collision subfile (GR subfile 2,
 * and 8/9/10 on multi-layer regions) - the walkable-height mesh. Byte layout
 * (measured across all 857 retail regions):
 * <pre>
 * 0x000 "coll"                      0x004 u32 payloadLen
 * 0x008 u32 0x5D8                   0x00C u32[5] {1,3,2,1,2}
 * 0x020 BOUNDS: 10 structs x 4 verts x (f32 x,y,z,0.0) = 640 B
 *       struct = two XZ rects as (min,max) corner pairs; s0/s1 = the two X
 *       halves x Z halves (level 1), s2..s9 = the 16 level-2 cells
 * 0x2A0 BUCKETS: 16 x {u32 offset, u32 count} in VERTEX units from 0x320
 * 0x320 TRIS: per bucket, vertex = {f32 x,y,z, f32 1.0}; spanning triangles
 *       are DUPLICATED into every bucket whose stored cell rect their XZ AABB
 *       overlaps (inclusive)
 * end   "term" 0 0x5D8 1, zero-pad to 128 alignment
 * </pre>
 * The bounds generator is the retail algorithm (recursive halving with 1%
 * expansion at each level); the corpus test calibrates the level-2 cell
 * enumeration order against real data and verifies bucket membership exactly.
 *
 * <p>The legacy {@code GRCollisionFile/Bounds/Mesh} classes render fine but
 * their bounds formula and bucketing are provably game-invalid (846/857 and
 * 853/857 retail mismatches) - all collision WRITES must go through this class.
 */
public class GfColl {

	public static final int TRI_FLOATS = 9; // 3 verts x xyz

	//parsed state (for verbatim round-trip and const preservation)
	public int payloadLen;
	public int const5d8a = 0x5D8, const5d8b = 0x5D8;
	public int[] consts13212 = {1, 3, 2, 1, 2};
	public byte[] boundsRaw = new byte[640];
	public int[] bucketOff = new int[16];   // vertex units
	public int[] bucketCnt = new int[16];   // vertex units
	public float[][] bucketTris;            // per bucket: n*9 floats (w dropped, always 1.0)
	public int tailPad;                     // zero bytes after term to subfile end

	/** Unique triangles in first-seen (bucket-scan) order; each float[9]. */
	public final List<float[]> uniqueTris = new ArrayList<>();

	public static boolean isColl(byte[] b) {
		return b != null && b.length >= 0x20 && b[0] == 'c' && b[1] == 'o' && b[2] == 'l' && b[3] == 'l';
	}

	public GfColl(byte[] data) {
		if (!isColl(data)) {
			throw new IllegalArgumentException("not a coll subfile");
		}
		payloadLen = i32(data, 4);
		const5d8a = i32(data, 8);
		for (int i = 0; i < 5; i++) {
			consts13212[i] = i32(data, 0xC + i * 4);
		}
		System.arraycopy(data, 0x20, boundsRaw, 0, 640);
		for (int i = 0; i < 16; i++) {
			bucketOff[i] = i32(data, 0x2A0 + i * 8);
			bucketCnt[i] = i32(data, 0x2A0 + i * 8 + 4);
		}
		bucketTris = new float[16][];
		//source-triangle reconstruction: buckets DUPLICATE spanning tris, and the
		//source mesh itself may contain genuinely repeated triangles - a tri's
		//source multiplicity is its max occurrence count across buckets
		Map<Long, float[]> firstSeen = new LinkedHashMap<>();
		Map<Long, Integer> multiplicity = new LinkedHashMap<>();
		for (int b = 0; b < 16; b++) {
			int nv = bucketCnt[b];
			float[] tris = new float[(nv / 3) * 9];
			Map<Long, Integer> inBucket = new LinkedHashMap<>();
			for (int t = 0; t < nv / 3; t++) {
				for (int v = 0; v < 3; v++) {
					int src = 0x320 + (bucketOff[b] + t * 3 + v) * 16;
					tris[t * 9 + v * 3] = f32(data, src);
					tris[t * 9 + v * 3 + 1] = f32(data, src + 4);
					tris[t * 9 + v * 3 + 2] = f32(data, src + 8);
				}
				long key = triKey(tris, t * 9);
				if (!firstSeen.containsKey(key)) {
					float[] u = new float[9];
					System.arraycopy(tris, t * 9, u, 0, 9);
					firstSeen.put(key, u);
				}
				inBucket.merge(key, 1, Integer::sum);
			}
			for (Map.Entry<Long, Integer> e : inBucket.entrySet()) {
				multiplicity.merge(e.getKey(), e.getValue(), Math::max);
			}
			bucketTris[b] = tris;
		}
		for (Map.Entry<Long, float[]> e : firstSeen.entrySet()) {
			int m = multiplicity.get(e.getKey());
			for (int k = 0; k < m; k++) {
				uniqueTris.add(e.getValue());
			}
		}
		int termAt = 0x320 + totalVerts() * 16;
		const5d8b = i32(data, termAt + 8);
		tailPad = data.length - (termAt + 16);
	}

	public int totalVerts() {
		int n = 0;
		for (int c : bucketCnt) {
			n += c;
		}
		return n;
	}

	/** Reassembles the EXACT parsed bytes - the parse-completeness gate. */
	public byte[] emitVerbatim() {
		int termAt = 0x320 + totalVerts() * 16;
		byte[] out = new byte[termAt + 16 + tailPad];
		out[0] = 'c';
		out[1] = 'o';
		out[2] = 'l';
		out[3] = 'l';
		p32(out, 4, payloadLen);
		p32(out, 8, const5d8a);
		for (int i = 0; i < 5; i++) {
			p32(out, 0xC + i * 4, consts13212[i]);
		}
		System.arraycopy(boundsRaw, 0, out, 0x20, 640);
		int vtx = 0;
		for (int b = 0; b < 16; b++) {
			p32(out, 0x2A0 + b * 8, bucketOff[b]);
			p32(out, 0x2A0 + b * 8 + 4, bucketCnt[b]);
			float[] tris = bucketTris[b];
			for (int t = 0; t < tris.length / 9; t++) {
				for (int v = 0; v < 3; v++) {
					int dst = 0x320 + vtx * 16;
					pf(out, dst, tris[t * 9 + v * 3]);
					pf(out, dst + 4, tris[t * 9 + v * 3 + 1]);
					pf(out, dst + 8, tris[t * 9 + v * 3 + 2]);
					pf(out, dst + 12, 1f);
					vtx++;
				}
			}
		}
		out[termAt] = 't';
		out[termAt + 1] = 'e';
		out[termAt + 2] = 'r';
		out[termAt + 3] = 'm';
		p32(out, termAt + 4, 0);
		p32(out, termAt + 8, const5d8b);
		p32(out, termAt + 12, 1);
		return out;
	}

	// ---- the retail bounds/bucket generator -------------------------------

	/**
	 * Level-2 cell enumeration: cell index k (0..15) -> {xq, zq} quarter
	 * indices. Calibrated against retail data by the corpus test (the "2x2
	 * blocks of 2x2" order): block-major over X then Z, cell-minor the same.
	 */
	static int[] cellQuarters(int k) {
		int block = k / 4, cell = k % 4;
		int bx = block / 2, bz = block % 2;
		int cx = cell / 2, cz = cell % 2;
		return new int[]{bx * 2 + cx, bz * 2 + cz};
	}

	private static float[] expand(float lo, float hi) {
		float d = (hi - lo) * 0.01f;
		return new float[]{lo - d, hi + d};
	}

	/**
	 * The full 640-byte bounds block for a triangle set, per the retail
	 * recursive-1%-expansion algorithm.
	 */
	public static byte[] buildBounds(List<float[]> tris) {
		float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
		float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		for (float[] t : tris) {
			for (int v = 0; v < 3; v++) {
				minX = Math.min(minX, t[v * 3]);
				maxX = Math.max(maxX, t[v * 3]);
				minY = Math.min(minY, t[v * 3 + 1]);
				maxY = Math.max(maxY, t[v * 3 + 1]);
				minZ = Math.min(minZ, t[v * 3 + 2]);
				maxZ = Math.max(maxZ, t[v * 3 + 2]);
			}
		}
		if (tris.isEmpty()) {
			minX = maxX = minY = maxY = minZ = maxZ = 0;
		}
		//level 1: split at the midpoint, expand each half by 1% of ITS extent
		float midX = (minX + maxX) / 2f, midZ = (minZ + maxZ) / 2f;
		float[][] xh = {expand(minX, midX), expand(midX, maxX)};
		float[][] zh = {expand(minZ, midZ), expand(midZ, maxZ)};
		float[] y1 = expand(minY, maxY);
		//level 2: split each EXPANDED half at its own midpoint, expand again
		float[][] xq = new float[4][];
		float[][] zq = new float[4][];
		for (int h = 0; h < 2; h++) {
			float xm = (xh[h][0] + xh[h][1]) / 2f;
			xq[h * 2] = expand(xh[h][0], xm);
			xq[h * 2 + 1] = expand(xm, xh[h][1]);
			float zm = (zh[h][0] + zh[h][1]) / 2f;
			zq[h * 2] = expand(zh[h][0], zm);
			zq[h * 2 + 1] = expand(zm, zh[h][1]);
		}
		float[] y2 = expand(y1[0], y1[1]);

		byte[] out = new byte[640];
		//s0: (Xh0 x Zh0), (Xh0 x Zh1);  s1: (Xh1 x Zh0), (Xh1 x Zh1)
		writeRect(out, 0, 0, xh[0], y1, zh[0]);
		writeRect(out, 0, 1, xh[0], y1, zh[1]);
		writeRect(out, 1, 0, xh[1], y1, zh[0]);
		writeRect(out, 1, 1, xh[1], y1, zh[1]);
		//s2..s9: the 16 level-2 cells, 2 per struct, in calibrated order
		for (int k = 0; k < 16; k++) {
			int[] q = cellQuarters(k);
			writeRect(out, 2 + k / 2, k % 2, xq[q[0]], y2, zq[q[1]]);
		}
		return out;
	}

	private static void writeRect(byte[] out, int struct, int rect, float[] x, float[] y, float[] z) {
		int base = struct * 64 + rect * 32;
		pf(out, base, x[0]);
		pf(out, base + 4, y[0]);
		pf(out, base + 8, z[0]);
		pf(out, base + 12, 0f);
		pf(out, base + 16, x[1]);
		pf(out, base + 20, y[1]);
		pf(out, base + 24, z[1]);
		pf(out, base + 28, 0f);
	}

	/** The stored XZ rect of bucket b, read from a bounds block. */
	static float[] bucketRect(byte[] bounds, int b) {
		int struct = 2 + b / 2, rect = b % 2;
		int base = struct * 64 + rect * 32;
		return new float[]{f32(bounds, base), f32(bounds, base + 8), f32(bounds, base + 16), f32(bounds, base + 24)};
	}

	/**
	 * Builds a complete coll subfile from a unique triangle list: bounds via
	 * the retail generator, membership = triangle XZ AABB overlapping the
	 * STORED cell rect (inclusive), spanners duplicated - the rule that
	 * reproduces retail bucket contents with zero mismatches. Constants are
	 * carried from a parsed template (or defaults), and the file is zero-padded
	 * to the 128-byte subfile alignment.
	 */
	public static byte[] build(List<float[]> tris, GfColl template) {
		byte[] bounds = buildBounds(tris);
		List<List<float[]>> buckets = new ArrayList<>();
		for (int b = 0; b < 16; b++) {
			buckets.add(new ArrayList<>());
		}
		for (float[] t : tris) {
			float tMinX = Math.min(t[0], Math.min(t[3], t[6]));
			float tMaxX = Math.max(t[0], Math.max(t[3], t[6]));
			float tMinZ = Math.min(t[2], Math.min(t[5], t[8]));
			float tMaxZ = Math.max(t[2], Math.max(t[5], t[8]));
			for (int b = 0; b < 16; b++) {
				float[] r = bucketRect(bounds, b);
				if (tMaxX >= r[0] && tMinX <= r[2] && tMaxZ >= r[1] && tMinZ <= r[3]) {
					buckets.get(b).add(t);
				}
			}
		}
		int totalVerts = 0;
		for (List<float[]> bl : buckets) {
			totalVerts += bl.size() * 3;
		}
		int termAt = 0x320 + totalVerts * 16;
		int total = termAt + 16;
		int padded = (total + 0x7F) & ~0x7F;
		byte[] out = new byte[padded];
		out[0] = 'c';
		out[1] = 'o';
		out[2] = 'l';
		out[3] = 'l';
		p32(out, 4, 640 + 128 + 16 * totalVerts + 16);
		p32(out, 8, template != null ? template.const5d8a : 0x5D8);
		int[] c5 = template != null ? template.consts13212 : new int[]{1, 3, 2, 1, 2};
		for (int i = 0; i < 5; i++) {
			p32(out, 0xC + i * 4, c5[i]);
		}
		System.arraycopy(bounds, 0, out, 0x20, 640);
		int vtx = 0;
		for (int b = 0; b < 16; b++) {
			p32(out, 0x2A0 + b * 8, vtx);
			p32(out, 0x2A0 + b * 8 + 4, buckets.get(b).size() * 3);
			for (float[] t : buckets.get(b)) {
				for (int v = 0; v < 3; v++) {
					int dst = 0x320 + vtx * 16;
					pf(out, dst, t[v * 3]);
					pf(out, dst + 4, t[v * 3 + 1]);
					pf(out, dst + 8, t[v * 3 + 2]);
					pf(out, dst + 12, 1f);
					vtx++;
				}
			}
		}
		out[termAt] = 't';
		out[termAt + 1] = 'e';
		out[termAt + 2] = 'r';
		out[termAt + 3] = 'm';
		p32(out, termAt + 4, 0);
		p32(out, termAt + 8, template != null ? template.const5d8b : 0x5D8);
		p32(out, termAt + 12, 1);
		return out;
	}

	// ---- box operations (the Geometry tool's collision coupling) ----------
	// Collision shares the visual model's region-local center-origin frame, so
	// the same selection box drives both. Per-vertex move mirrors the visual
	// mesh behavior (boundary triangles stretch); delete/duplicate act on
	// triangles fully inside the box. All return a freshly built subfile.

	/** Translates every vertex inside the XZ/Y box by (dx,dy,dz), then rebuilds. */
	public static byte[] moveBox(byte[] collBytes, float minX, float minZ, float maxX, float maxZ,
			float dx, float dy, float dz) {
		GfColl c = new GfColl(collBytes);
		boolean touched = false;
		List<float[]> tris = new ArrayList<>();
		for (float[] t : c.uniqueTris) {
			float[] n = t.clone();
			for (int v = 0; v < 3; v++) {
				if (inXZ(n, v, minX, minZ, maxX, maxZ)) {
					n[v * 3] += dx;
					n[v * 3 + 1] += dy;
					n[v * 3 + 2] += dz;
					touched = true;
				}
			}
			tris.add(n);
		}
		return touched ? build(tris, c) : collBytes;
	}

	/** Clones every triangle fully inside the box, offset by (dx,dy,dz), then rebuilds. */
	public static byte[] duplicateBox(byte[] collBytes, float minX, float minZ, float maxX, float maxZ,
			float dx, float dy, float dz) {
		GfColl c = new GfColl(collBytes);
		List<float[]> tris = new ArrayList<>(c.uniqueTris);
		int added = 0;
		for (float[] t : c.uniqueTris) {
			if (inXZ(t, 0, minX, minZ, maxX, maxZ) && inXZ(t, 1, minX, minZ, maxX, maxZ) && inXZ(t, 2, minX, minZ, maxX, maxZ)) {
				float[] n = t.clone();
				for (int v = 0; v < 3; v++) {
					n[v * 3] += dx;
					n[v * 3 + 1] += dy;
					n[v * 3 + 2] += dz;
				}
				tris.add(n);
				added++;
			}
		}
		return added > 0 ? build(tris, c) : collBytes;
	}

	/** Removes every triangle fully inside the box, then rebuilds. */
	public static byte[] deleteBox(byte[] collBytes, float minX, float minZ, float maxX, float maxZ) {
		GfColl c = new GfColl(collBytes);
		List<float[]> tris = new ArrayList<>();
		int removed = 0;
		for (float[] t : c.uniqueTris) {
			if (inXZ(t, 0, minX, minZ, maxX, maxZ) && inXZ(t, 1, minX, minZ, maxX, maxZ) && inXZ(t, 2, minX, minZ, maxX, maxZ)) {
				removed++;
			} else {
				tris.add(t);
			}
		}
		return removed > 0 ? build(tris, c) : collBytes;
	}

	private static boolean inXZ(float[] t, int v, float minX, float minZ, float maxX, float maxZ) {
		float x = t[v * 3], z = t[v * 3 + 2];
		return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
	}

	private static long triKey(float[] a, int off) {
		long h = 1125899906842597L;
		for (int i = 0; i < 9; i++) {
			h = 31 * h + Float.floatToIntBits(a[off + i]);
		}
		return h;
	}

	public static int i32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}

	public static float f32(byte[] b, int o) {
		return Float.intBitsToFloat(i32(b, o));
	}

	static void p32(byte[] b, int o, int v) {
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
		b[o + 2] = (byte) (v >> 16);
		b[o + 3] = (byte) (v >> 24);
	}

	static void pf(byte[] b, int o, float f) {
		p32(b, o, Float.floatToIntBits(f));
	}
}
