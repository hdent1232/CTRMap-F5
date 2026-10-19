package ctrmap.formats.tilemap;

/**
 * What every stage of the painted-region build shares: the frame (40x40 tiles of 18 units round a
 * centre origin), the constants the stages agree on, and the plain data they hand each other -
 * a quad, a cliff edge, a shore edge - with the operations two stages both use: winding a quad, rounding an outline.
 *
 * <p>A LEAF (F090): it depends on no stage, and no stage depends on the hub for these any more. The
 * split of PaintedRegionBuilder kept them in the hub, so every stage that needed the frame imported
 * the hub, the hub imported every stage, and each of the seven files wildcard-imported the other six -
 * 42 declared edges for 18 used, through a cycle.
 *
 * <p>Not: {@link PaintedRegionBuilder} - that is the build's driver and the stages' caller; a stage
 * that needs the frame must not need the driver.
 */
public final class PaintedGeometry {

	private PaintedGeometry() {
	}

	public static final int DIM = 40;
	public static final float TILE = 18f;
	public static final float ORIGIN = -360f;
	/** World Y per height level (one tile tall). */
	public static final float STEP = 18f;
	/** The ramp grid's value for a tile that is not a ramp; 0..3 is the way down (E, W, S, N). */
	public static final int NO_RAMP = -1;
	static final String[] DIR_NAMES = {"east", "west", "south", "north"};

	/** A textured quad (4 corners TL/TR/BL/BR) destined for one mesh. */
	static final class Quad {

		final float[][] pos = new float[4][];
		final float[][] uv = new float[4][];
		final float[][] nrm = new float[4][];
		final float[] ao = new float[4];
	}

	/** Bytes per PICA component type: 0=s8, 1=u8, 2=s16, 3=float - the sizes the
	 *  vertex codec in {@link PaintedFloorMesh} reads and writes by. */
	static final int[] COMP_BYTES = {1, 1, 2, 4};

	// ---- edge transition strips (the GameFreak "blend" look; PaintedFloorMesh lays them)

	static final int GRP_GRASS = 1, GRP_DIRT = 2;
	/** Half-visual constants: strip reaches EDGE_W world units onto the lower
	 *  (dirt/sand) side of a seam, lifted EDGE_LIFT above ground to overlay it. */
	static final float EDGE_W = 9f, EDGE_LIFT = 0.6f;

	/** Ground texture repeats: one per 72 world units, i.e. per four tiles. */
	static final float FLOOR_UV = 1f / 72f;

	/** How far the water surface sits below the ground it runs through. */
	static final float WATER_SINK = 7f;

	/** One tile-boundary segment where water meets land at the same height. */
	static final class ShoreEdge {

		float ax, az, bx, bz;   // the segment, head to tail around the water
		float nx, nz;           // unit normal pointing from the land INTO the water
		float y;                // both surfaces are at this height
		int h;                  // that height as a level, for ambient occlusion
		int landMesh;

		String startKey() {
			return CliffEdge.key(ax, az);
		}

		String endKey() {
			return CliffEdge.key(bx, bz);
		}
	}

	/** How far a bank reaches out over the water before meeting its surface. */
	static final float BANK_RUN = 7f;

	/**
	 * One tile-edge segment where the ground steps down, before it is joined to
	 * its neighbours. Endpoints run head-to-tail around a raised area (east,
	 * south, west, north each start where the previous one ended), so chaining
	 * is just "find the edge whose start is this edge's end".
	 */
	static final class CliffEdge {

		float ax, az, bx, bz;   // the tile-boundary segment, in world units
		float nx, nz;           // outward, away from the high ground
		/**
		 * The top of the fall at a and at b. Equal on every face but a ramp's side, whose top follows the
		 * ramp's floor down: one height for the whole edge stood the face at the ramp's top all along its
		 * side, a whole step over the ramp's own foot (2026-10-18). There is no single top to read - a
		 * reader asks for the end it stands at ({@link #topAt}).
		 */
		float yTopA, yTopB;
		float yBot;
		/** The ground this face would lean over is walkable, so it must not. */
		boolean tight;
		/**
		 * No backing wall behind it: the face stands off the map, or is rebuilt on an untouched higher
		 * neighbour's side with a retail top sampled at one point. NOT a face over untouched low ground -
		 * a floor backs no face, and that premise left sky behind three kinds of junction (2026-10-18).
		 */
		boolean donorSide;

		String startKey() {
			return key(ax, az);
		}

		String endKey() {
			return key(bx, bz);
		}

		static String key(float x, float z) {
			return Math.round(x * 4f) + ":" + Math.round(z * 4f);
		}

		/**
		 * The top at the end whose key is {@code end}.
		 *
		 * @throws IllegalArgumentException for a key that is neither end of this edge
		 */
		float topAt(String end) {
			if (end.equals(startKey())) {
				return yTopA;
			}
			if (end.equals(endKey())) {
				return yTopB;
			}
			throw new IllegalArgumentException("the point " + end + " is not an end of the edge " + startKey() + " - " + endKey());
		}
	}

	/**
	 * The two corners of a tile's edge on {@code side} (0 E, 1 W, 2 S, 3 N), each as
	 * {xIndex, zIndex} in {0,1}, in the order every edge-hung quad walks them.
	 */
	static int[][] edgeCorners(int side) {
		return side == 0 ? new int[][]{{1, 0}, {1, 1}}
				: side == 1 ? new int[][]{{0, 0}, {0, 1}}
				: side == 2 ? new int[][]{{0, 1}, {1, 1}}
				: new int[][]{{0, 0}, {1, 0}};
	}

	/**
	 * True when corner {xIndex, zIndex} of a ramp going down {@code rd} is on its descent edge -
	 * one of the two corners its floor drops to the foot. The one statement of which corners fall:
	 * the floor, the skirt and the ground a skirt is bounded by each read it here.
	 */
	static boolean lowCorner(int rd, int xi, int zi) {
		return rd == 0 ? xi == 1 : rd == 1 ? xi == 0 : rd == 2 ? zi == 1 : rd == 3 && zi == 0;
	}

	/** Fraction of a mesh's surface area that faces up; a floor ~1, a wall ~0. */
	static final double MIN_GROUND_FLATNESS = 0.5;

	/**
	 * Winds a quad so its face points along an arbitrary 3D direction, carrying
	 * UVs, normals and AO with the swap.
	 *
	 * <p>Comparing only the horizontal part is right for a vertical wall and
	 * wrong for a sloped one: a shallow face's normal is nearly all +Y, so a
	 * horizontal comparison decides on noise.
	 *
	 * <p>THE FACE IS THE WHOLE QUAD'S, both of the triangles it is drawn as ({@link #faceNormal}).
	 * It was read off the first triangle alone, and a ramp's side skirt is zero-height at the top
	 * of its slope, so on the side whose first corner is the high one that triangle has no area:
	 * the facing asked for was never applied, both skirts of a ramp kept the winding they were
	 * built with, and one of the two corridor walls faced away from the ramp - culled from
	 * anyone standing on it, sky straight through (measured 2026-10-18: 24 of 24 sight lines on
	 * that side of every painted ramp, which a two-sided probe read as sealed). A quad with area
	 * whose face is square to the direction asked has no side that faces it, and is refused.
	 */
	static void fixWindingTowards(Quad q, float wx, float wy, float wz) {
		float[] g = faceNormal(q);
		float d = g[0] * wx + g[1] * wy + g[2] * wz;
		float area = (float) Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
		float want = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
		if (area > 1e-4f && Math.abs(d) <= 1e-4f * area * want) {
			throw new IllegalStateException(String.format("a quad at (%.1f, %.1f, %.1f) was asked to face (%.2f, %.2f, %.2f),"
					+ " square to its own face (%.2f, %.2f, %.2f): neither of its sides faces that way",
					q.pos[0][0], q.pos[0][1], q.pos[0][2], wx, wy, wz, g[0], g[1], g[2]));
		}
		if (d < 0) {
			swapCorners12(q);
		}
	}

	/**
	 * The quad's face direction as drawn, area-weighted: the sum of the normals of the two
	 * triangles it becomes - (0,2,1) and (1,2,3), the order {@code PaintedFloorMesh.meshFromQuads}
	 * writes. A quad with one degenerate half still answers with its other half's face.
	 */
	static float[] faceNormal(Quad q) {
		float[] g = new float[3];
		int[][] halves = {{0, 2, 1}, {1, 2, 3}};
		for (int[] t : halves) {
			float[] a = q.pos[t[0]], b = q.pos[t[1]], c = q.pos[t[2]];
			float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
			float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
			g[0] += uy * vz - uz * vy;
			g[1] += uz * vx - ux * vz;
			g[2] += ux * vy - uy * vx;
		}
		return g;
	}

	/**
	 * Turns a quad's two triangles to wind the other way by swapping corners 1 and 2, carrying
	 * EVERY attribute a corner has. The one place a quad is flipped (F090): there were three, and
	 * the two that swapped only position and UV left the shading where the corner had been - a
	 * cliff gusset's back face came out lit diagonally while its front was lit top to bottom.
	 */
	static void swapCorners12(Quad q) {
		float[] tp = q.pos[1]; q.pos[1] = q.pos[2]; q.pos[2] = tp;
		float[] tu = q.uv[1]; q.uv[1] = q.uv[2]; q.uv[2] = tu;
		float[] tn = q.nrm[1]; q.nrm[1] = q.nrm[2]; q.nrm[2] = tn;
		float ta = q.ao[1]; q.ao[1] = q.ao[2]; q.ao[2] = ta;
	}

	/**
	 * How far a cliff outline's vertex may end up from where the tile grid put it. Smoothing
	 * shrinks, and three passes shrink enough to eat a narrow feature alive: a one-tile-wide
	 * raised path has its two edges pulled toward each other from both sides at once and
	 * collapses into a row of stilts. Long runs need the full rounding, short ones cannot
	 * afford it, and the difference is not the chain's length but how far any single vertex
	 * ends up from the grid. So cap that.
	 */
	public static final float CLIFF_PULL = TILE * 0.3f;

	/**
	 * How far a shoreline's vertex may end up from the grid - not the cliff's cap, and it never was:
	 * the two were separate copies of one rounding, 0.34 of a tile here and 0.3 there, and both values
	 * are kept as they were found. Never wander more than a third of a tile from the grid. A river two
	 * tiles wide would otherwise have both banks pulled toward each other until they met, and the same
	 * cap keeps the bands narrow enough that they cannot reach past the tile they are covering.
	 */
	static final float SHORE_PULL = TILE * 0.34f;

	/**
	 * Rounds the outline. Elevation is quantised to tiles, so the contour
	 * steps a tile at a time and a chain of right angles reads as a
	 * staircase however well the faces are joined - which is the single
	 * thing that has made these cliffs look built out of blocks.
	 * One pass of corner cutting was all the top row could take, because the
	 * top is where the floor above ends: move it and triangular holes open
	 * straight through the map. That constraint is real, but it is not a
	 * reason to leave the silhouette square - it only means the smoothed
	 * outline has to be BRIDGED back to the tile boundary rather than left
	 * floating. The collar emitted by {@link PaintedCliffMesh#emitChain} does that, so the
	 * whole cliff can now be rounded properly, top row included.
	 *
	 * <p>Three passes of {@code 0.25 / 0.5 / 0.25} averaging, then a cap on
	 * how far any vertex may travel. The ends of an open chain are pinned.
	 * The cliffs and the shorelines are rounded by this one function; only the
	 * cap differs, and both caps are declared beside each other above.
	 * Public so a suite can hand it a polyline on its own: the three
	 * invariants below (pinned ends, a closed loop stays closed, the travel
	 * cap) are what the whole map-sealing argument rests on.
	 *
	 * @param px   vertex x, in world units
	 * @param pz   vertex z
	 * @param loop the polyline is closed (first vertex equals last)
	 * @param maxPull how far any vertex may end up from where the tile grid put it:
	 *             {@link #CLIFF_PULL} or {@link #SHORE_PULL}
	 * @return {x, z} of the rounded vertices; the inputs are not touched
	 */
	public static float[][] roundOutline(float[] px, float[] pz, boolean loop, float maxPull) {
		int vc = px.length;
		final int SMOOTH_PASSES = 3;
		float[] smoothX = px.clone(), smoothZ = pz.clone();
		for (int pass = 0; pass < SMOOTH_PASSES; pass++) {
			float[] nx2 = smoothX.clone(), nz2 = smoothZ.clone();
			for (int i = 0; i < vc; i++) {
				//Pin the ends of an open chain. Chains are split by the pair of
				//heights they join, so a raised path is bounded by several
				//short chains that meet end to end - and each is smoothed on
				//its own. Move the shared endpoints and they no longer meet:
				//the wall opens at every junction and you see sky under the
				//path, because there is no floor beneath a raised tile to catch
				//the eye. Pinning costs a little rounding exactly at the joins
				//and keeps the wall closed.
				if (!loop && (i == 0 || i == vc - 1)) {
					continue;
				}
				int prev = i == 0 ? (loop ? vc - 2 : 0) : i - 1;
				int next = i == vc - 1 ? (loop ? 1 : vc - 1) : i + 1;
				nx2[i] = smoothX[i] * 0.5f + smoothX[prev] * 0.25f + smoothX[next] * 0.25f;
				nz2[i] = smoothZ[i] * 0.5f + smoothZ[prev] * 0.25f + smoothZ[next] * 0.25f;
			}
			smoothX = nx2;
			smoothZ = nz2;
		}
		//Smoothing shrinks; the cap is how far any vertex may end up from the grid.
		for (int i = 0; i < vc; i++) {
			float dx = smoothX[i] - px[i], dz = smoothZ[i] - pz[i];
			float d = (float) Math.hypot(dx, dz);
			if (d > maxPull) {
				smoothX[i] = px[i] + dx * (maxPull / d);
				smoothZ[i] = pz[i] + dz * (maxPull / d);
			}
		}
		return new float[][]{smoothX, smoothZ};
	}
}
