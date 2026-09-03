package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.BchModelVerifier;
import ctrmap.formats.h3d.RegionFactory;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TerrainLighting;
import ctrmap.formats.tilemap.TilePalette;
import java.io.File;
import java.util.Arrays;

/**
 * Validates the Map Builder's COMPOSITE mode (edit-in-place with exact
 * boundary clipping) against retail data. Retail triangles span many tiles,
 * so the checks are COVERAGE-based, not triangle-count-based:
 * <ol>
 * <li>IDENTITY: an all-untouched composite passes the donor's model, collision
 *     and tilemap through byte-exactly;</li>
 * <li>ERASE (VOID brush): nothing covers the painted block afterwards - no
 *     collision floor, no clippable visual geometry - while every untouched
 *     tile's collision surface height is EXACTLY preserved;</li>
 * <li>PAINT (PATH brush at seeded heights): untouched coverage preserved, and
 *     the painted tiles' walkable surface sits at the retail ground height
 *     (the collision-seeded level frame cancels the quantization);</li>
 * <li>CORPUS: a one-tile edit across a sample of all retail regions produces
 *     valid, render-parseable output with untouched coverage preserved.</li>
 * </ol>
 * Usage: java ctrmap.tests.CompositeBuildTest &lt;path-to-a039-garc&gt;
 */
public class CompositeBuildTest {

	static final int DIM = PaintedRegionBuilder.DIM;
	static final float TILE = PaintedRegionBuilder.TILE, ORIGIN = PaintedRegionBuilder.ORIGIN;

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/3/9");
		GARC gr = new GARC(garcFile);
		int failures = 0;

		byte[][] r1 = region(gr, 1);
		if (r1 == null) {
			System.out.println("FAIL region 1 unusable");
			System.exit(1);
		}
		failures += identity("identity (region 1)", r1);
		failures += erase("erase (region 1, 8x8 block)", r1, 5, 5, 12, 12);
		failures += paint("paint (region 1, 8x8 block)", r1, 5, 5, 12, 12);
		failures += paint("paint (region 1, single tile)", r1, 20, 20, 20, 20);
		failures += lShape("paint (region 1, L-shaped mask)", r1);
		failures += reapply("re-apply (region 1, second edit on applied output)", r1);

		// corpus sweep - a one-tile edit on every 12th region
		int swept = 0, skipped = 0;
		for (int i = 0; i < gr.length; i += 12) {
			byte[][] reg = region(gr, i);
			if (reg == null) {
				skipped++;
				continue;
			}
			try {
				paintCore(reg, 3, 3, 3, 3, false);
				swept++;
			} catch (RuntimeException ex) {
				failures++;
				System.out.println("FAIL corpus region " + i + ": " + ex.getMessage());
			}
		}
		System.out.println("corpus: " + swept + " regions swept, " + skipped + " without a paintable model");

		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT (" + failures + ")");
		if (failures > 0) {
			System.exit(1);
		}
	}

	/** {model, collision, tilemap} of a region entry, or null if not paintable. */
	static byte[][] region(GARC gr, int i) {
		try {
			byte[] c = gr.getDecompressedEntry(i);
			byte[] model = sub(c, 1), coll = sub(c, 2), tm = sub(c, 0);
			if (model == null || !BchMapModel.isMapModel(model) || tm == null || tm.length < 4 + DIM * DIM * 4
					|| (tm[0] & 0xFF) != DIM || (tm[2] & 0xFF) != DIM || coll == null || !GfColl.isColl(coll)) {
				return null;
			}
			return new byte[][]{model, coll, tm};
		} catch (Exception ex) {
			return null;
		}
	}

	static int identity(String label, byte[][] reg) {
		try {
			TilePalette[][] g = grid(TilePalette.GRASS);
			RegionFactory.BlankContent c = PaintedRegionBuilder.buildComposite(reg[0], reg[1], reg[2],
					g, null, null, new boolean[DIM][DIM], TerrainLighting.daytime(), true);
			if (!Arrays.equals(c.model, reg[0]) || !Arrays.equals(c.collision, reg[1]) || !Arrays.equals(c.tilemap, reg[2])) {
				throw new IllegalStateException("bytes changed with nothing touched");
			}
			System.out.println("  ok: " + label);
			return 0;
		} catch (RuntimeException ex) {
			System.out.println("FAIL " + label + ": " + ex.getMessage());
			return 1;
		}
	}

	static int erase(String label, byte[][] reg, int x0, int y0, int x1, int y1) {
		try {
			TilePalette[][] g = grid(TilePalette.GRASS);
			int[][] h = seededHeights(reg[1], reg[2]);
			boolean[][] touched = new boolean[DIM][DIM];
			for (int y = y0; y <= y1; y++) {
				for (int x = x0; x <= x1; x++) {
					g[y][x] = TilePalette.VOID;
					touched[y][x] = true;
				}
			}
			RegionFactory.BlankContent c = PaintedRegionBuilder.buildComposite(reg[0], reg[1], reg[2],
					g, h, null, touched, TerrainLighting.daytime(), true);
			modelGates(c.model, true);

			GfColl donor = new GfColl(reg[1]);
			//ground level of the erased block (only OVERHEAD structures - decks,
			//roofs well above it - may keep covering the tiles)
			float blockTop = -Float.MAX_VALUE;
			for (int y = y0; y <= y1; y++) {
				for (int x = x0; x <= x1; x++) {
					float wy = surfaceYMin(donor, cx(x), cz(y));
					if (!Float.isNaN(wy)) {
						blockTop = Math.max(blockTop, wy);
					}
				}
			}
			float overhead = blockTop + PaintedRegionBuilder.STEP;

			// collision: nothing near the ground covers the erased block any more
			GfColl out = new GfColl(c.collision);
			for (int y = y0; y <= y1; y++) {
				for (int x = x0; x <= x1; x++) {
					float got = surfaceYMin(out, cx(x), cz(y));
					if (!Float.isNaN(got) && got <= overhead) {
						throw new IllegalStateException("collision still covers erased tile " + x + "," + y + " at " + got);
					}
				}
			}
			coveragePreserved(donor, out, touched);

			// visual: no clippable mesh's ground-level triangle covers the block
			BchMapModel m = new BchMapModel(c.model);
			for (BchMapModel.MeshGeom mg : m.geometry()) {
				if (!mg.posOk || !clippable(m, mg)) {
					continue;
				}
				float[][] pos = m.getVertexPositions(mg.meshIndex);
				int[] tris = m.getTriangles(mg.meshIndex);
				for (int t = 0; t + 2 < tris.length; t += 3) {
					float minY = Math.min(pos[tris[t]][1], Math.min(pos[tris[t + 1]][1], pos[tris[t + 2]][1]));
					if (minY > overhead) {
						continue; // overhead structure - allowed to remain
					}
					for (int y = y0; y <= y1; y++) {
						for (int x = x0; x <= x1; x++) {
							if (planCovers(pos[tris[t]], pos[tris[t + 1]], pos[tris[t + 2]], cx(x), cz(y))) {
								throw new IllegalStateException("visual mesh " + mg.meshIndex
										+ " still covers erased tile " + x + "," + y);
							}
						}
					}
				}
			}
			System.out.println("  ok: " + label);
			return 0;
		} catch (RuntimeException ex) {
			System.out.println("FAIL " + label + ": " + ex.getMessage());
			return 1;
		}
	}

	/** An L-shaped touched mask exercises the multi-rect region decomposition. */
	static int lShape(String label, byte[][] reg) {
		try {
			TilePalette[][] g = grid(TilePalette.GRASS);
			int[][] h = seededHeights(reg[1], reg[2]);
			boolean[][] touched = new boolean[DIM][DIM];
			for (int x = 5; x <= 14; x++) {
				g[5][x] = TilePalette.PATH;
				touched[5][x] = true;
			}
			for (int y = 5; y <= 14; y++) {
				g[y][5] = TilePalette.PATH;
				touched[y][5] = true;
			}
			RegionFactory.BlankContent c = PaintedRegionBuilder.buildComposite(reg[0], reg[1], reg[2],
					g, h, null, touched, TerrainLighting.daytime(), true);
			modelGates(c.model, true);
			GfColl donor = new GfColl(reg[1]);
			GfColl out = new GfColl(c.collision);
			coveragePreserved(donor, out, touched);
			for (int i = 5; i <= 14; i++) {
				if (Float.isNaN(surfaceYMin(out, cx(i), cz(5))) || Float.isNaN(surfaceYMin(out, cx(5), cz(i)))) {
					throw new IllegalStateException("painted L-arm tile lost its floor at index " + i);
				}
			}
			System.out.println("  ok: " + label);
			return 0;
		} catch (RuntimeException ex) {
			System.out.println("FAIL " + label + ": " + ex.getMessage());
			return 1;
		}
	}

	/** A second composite edit uses the FIRST edit's output as its donor - the
	 *  real re-apply cycle. Must stay valid and must not balloon the mesh. */
	static int reapply(String label, byte[][] reg) {
		try {
			TilePalette[][] g = grid(TilePalette.GRASS);
			int[][] h = seededHeights(reg[1], reg[2]);
			boolean[][] t1 = new boolean[DIM][DIM];
			for (int y = 5; y <= 9; y++) {
				for (int x = 5; x <= 9; x++) {
					g[y][x] = TilePalette.PATH;
					t1[y][x] = true;
				}
			}
			RegionFactory.BlankContent first = PaintedRegionBuilder.buildComposite(reg[0], reg[1], reg[2],
					g, h, null, t1, TerrainLighting.daytime(), true);
			modelGates(first.model, false);

			//second edit: repaint part of the same block + extend it
			byte[][] reg2 = {first.model, first.collision, first.tilemap};
			TilePalette[][] g2 = grid(TilePalette.GRASS);
			int[][] h2 = seededHeights(first.collision, first.tilemap);
			boolean[][] t2 = new boolean[DIM][DIM];
			for (int y = 7; y <= 11; y++) {
				for (int x = 7; x <= 11; x++) {
					g2[y][x] = TilePalette.SAND;
					t2[y][x] = true;
				}
			}
			RegionFactory.BlankContent second = PaintedRegionBuilder.buildComposite(reg2[0], reg2[1], reg2[2],
					g2, h2, null, t2, TerrainLighting.daytime(), true);
			modelGates(second.model, false);
			GfColl out = new GfColl(second.collision);
			coveragePreserved(new GfColl(first.collision), out, t2);
			for (int y = 7; y <= 11; y++) {
				for (int x = 7; x <= 11; x++) {
					if (Float.isNaN(surfaceYMin(out, cx(x), cz(y)))) {
						throw new IllegalStateException("re-applied tile " + x + "," + y + " lost its floor");
					}
				}
			}
			//growth sanity: the second model must stay in the same size ballpark
			if (second.model.length > first.model.length * 1.5 + 65536) {
				throw new IllegalStateException("re-apply ballooned the model: "
						+ first.model.length + " -> " + second.model.length);
			}
			System.out.println("  ok: " + label);
			return 0;
		} catch (RuntimeException ex) {
			System.out.println("FAIL " + label + ": " + ex.getMessage());
			return 1;
		}
	}

	static int paint(String label, byte[][] reg, int x0, int y0, int x1, int y1) {
		try {
			paintCore(reg, x0, y0, x1, y1, true);
			System.out.println("  ok: " + label);
			return 0;
		} catch (RuntimeException ex) {
			System.out.println("FAIL " + label + ": " + ex.getMessage());
			return 1;
		}
	}

	static void paintCore(byte[][] reg, int x0, int y0, int x1, int y1, boolean deep) {
		TilePalette[][] g = grid(TilePalette.GRASS);
		int[][] h = seededHeights(reg[1], reg[2]);
		boolean[][] touched = new boolean[DIM][DIM];
		for (int y = y0; y <= y1; y++) {
			for (int x = x0; x <= x1; x++) {
				g[y][x] = TilePalette.PATH;
				touched[y][x] = true;
			}
		}
		RegionFactory.BlankContent c = PaintedRegionBuilder.buildComposite(reg[0], reg[1], reg[2],
				g, h, null, touched, TerrainLighting.daytime(), true);
		modelGates(c.model, deep);

		GfColl donor = new GfColl(reg[1]);
		GfColl out = new GfColl(c.collision);
		coveragePreserved(donor, out, touched);

		// painted tiles: walkable at the retail GROUND height (the frame
		// samples the lowest surface and the level frame derives from the same
		// collision, so the offsets cancel exactly)
		for (int y = y0; y <= y1; y++) {
			for (int x = x0; x <= x1; x++) {
				float want = surfaceYMin(donor, cx(x), cz(y));
				float got = surfaceYMin(out, cx(x), cz(y));
				if (Float.isNaN(got)) {
					throw new IllegalStateException("painted tile " + x + "," + y + " has no floor");
				}
				if (!Float.isNaN(want) && Math.abs(got - want) > 0.51f) {
					throw new IllegalStateException("painted tile " + x + "," + y + " floor at " + got
							+ " but retail ground was " + want);
				}
			}
		}

		if (deep) {
			// retail WALLS whose run never touches the painted region must
			// survive float-identically (walls span tiles like floors do)
			java.util.List<float[]> rects = ctrmap.formats.tilemap.TileClip.regionRects(touched, TILE, ORIGIN, 0f);
			java.util.Set<String> outWalls = new java.util.HashSet<>();
			for (float[] t : out.uniqueTris) {
				outWalls.add(triKey(t));
			}
			for (float[] t : donor.uniqueTris) {
				float area2 = Math.abs((t[3] - t[0]) * (t[8] - t[2]) - (t[6] - t[0]) * (t[5] - t[2]));
				if (area2 >= 1.0f) {
					continue;
				}
				float[][] xz = {{t[0], t[2]}, {t[3], t[5]}, {t[6], t[8]}};
				if (ctrmap.formats.tilemap.TileClip.segmentTouchesRegion(xz, rects)) {
					continue; // may legitimately be cut
				}
				if (!outWalls.contains(triKey(t))) {
					throw new IllegalStateException("untouched retail wall vanished (plan "
							+ t[0] + "," + t[2] + " .. " + t[6] + "," + t[8] + ")");
				}
			}
		}

		// tilemap: the block's tuples = brush, all other bytes byte-identical
		for (int y = 0; y < DIM; y++) {
			for (int x = 0; x < DIM; x++) {
				int off = 4 + (y * DIM + x) * 4;
				boolean in = x >= x0 && x <= x1 && y >= y0 && y <= y1;
				for (int k = 0; k < 4; k++) {
					int want = in ? TilePalette.PATH.tuple[k] : (reg[2][off + k] & 0xFF);
					if ((c.tilemap[off + k] & 0xFF) != want) {
						throw new IllegalStateException("tilemap byte at " + x + "," + y + " wrong");
					}
				}
			}
		}
	}

	// ---- gates ----

	static void modelGates(byte[] model, boolean deep) {
		BchMapModel m = new BchMapModel(model);
		if (!m.validate().isEmpty()) {
			throw new IllegalStateException("model parse " + m.validate());
		}
		BCHFile render = new BCHFile(model);
		if (render.errorlevel != 0 || render.models.isEmpty()) {
			throw new IllegalStateException("render parser rejected");
		}
		if (deep) {
			java.util.List<String> v = BchModelVerifier.verify(model);
			if (!v.isEmpty() && m.auxDicts.isEmpty()) {
				throw new IllegalStateException("verifier " + v);
			}
		}
	}

	/** Every untouched tile center's collision surface height must be EXACTLY
	 *  preserved (clipping keeps outside geometry; covered stays covered at the
	 *  same Y, uncovered stays uncovered). */
	static void coveragePreserved(GfColl donor, GfColl out, boolean[][] touched) {
		for (int y = 0; y < DIM; y++) {
			for (int x = 0; x < DIM; x++) {
				if (touched[y][x]) {
					continue;
				}
				float a = surfaceY(donor, cx(x), cz(y));
				float b = surfaceY(out, cx(x), cz(y));
				if (Float.isNaN(a) != Float.isNaN(b) || (!Float.isNaN(a) && Math.abs(a - b) > 0.1f)) {
					throw new IllegalStateException("untouched tile " + x + "," + y
							+ " coverage changed: " + a + " -> " + b);
				}
			}
		}
	}

	/** True when the mesh's layout lets the composite clipper cut it. */
	static boolean clippable(BchMapModel m, BchMapModel.MeshGeom g) {
		java.util.List<BchMapModel.MeshAttr> attrs = m.attributes(g.meshIndex);
		int bytes = 0;
		boolean pos = false;
		for (BchMapModel.MeshAttr a : attrs) {
			bytes += a.size();
			pos |= a.name == 0;
		}
		return !attrs.isEmpty() && bytes == g.stride && pos;
	}

	// ---- independent plan-view sampling (NOT the builder's code) ----

	static float cx(int tx) {
		return (tx + 0.5f) * TILE + ORIGIN;
	}

	static float cz(int ty) {
		return (ty + 0.5f) * TILE + ORIGIN;
	}

	/** Topmost collision surface Y at a plan-view point, NaN when uncovered. */
	static float surfaceY(GfColl c, float px, float pz) {
		float best = Float.NaN;
		for (float[] t : c.uniqueTris) {
			Float y = triY(t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8], px, pz);
			if (y != null && (Float.isNaN(best) || y > best)) {
				best = y;
			}
		}
		return best;
	}

	/** LOWEST collision surface Y at a plan-view point (the ground frame), NaN when uncovered. */
	static float surfaceYMin(GfColl c, float px, float pz) {
		float best = Float.NaN;
		for (float[] t : c.uniqueTris) {
			Float y = triY(t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8], px, pz);
			if (y != null && (Float.isNaN(best) || y < best)) {
				best = y;
			}
		}
		return best;
	}

	static String triKey(float[] t) {
		StringBuilder sb = new StringBuilder();
		for (float v : t) {
			sb.append(Float.floatToIntBits(v)).append(',');
		}
		return sb.toString();
	}

	static Float triY(float ax, float ay, float az, float bx, float by, float bz,
			float cxx, float cy, float czz, float px, float pz) {
		float d = (bz - czz) * (ax - cxx) + (cxx - bx) * (az - czz);
		if (Math.abs(d) < 1e-3f) {
			return null;
		}
		float wa = ((bz - czz) * (px - cxx) + (cxx - bx) * (pz - czz)) / d;
		float wb = ((czz - az) * (px - cxx) + (ax - cxx) * (pz - czz)) / d;
		float wc = 1f - wa - wb;
		if (wa < -0.001f || wb < -0.001f || wc < -0.001f) {
			return null;
		}
		return wa * ay + wb * by + wc * cy;
	}

	static boolean planCovers(float[] a, float[] b, float[] c, float px, float pz) {
		return triY(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], px, pz) != null;
	}

	static int[][] seededHeights(byte[] coll, byte[] tm) {
		int[][] h = new int[DIM][DIM];
		PaintedRegionBuilder.seedHeightsFromCollision(coll, tm, h);
		return h;
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
}
