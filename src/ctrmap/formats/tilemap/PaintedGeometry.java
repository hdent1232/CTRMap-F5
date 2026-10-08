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
		float yTop, yBot;
		/** The ground this face would lean over is walkable, so it must not. */
		boolean tight;
		/** The low side is untouched retail terrain, which backs itself. */
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

		/** Same step, so the strip can run continuously through it. */
		String levelKey() {
			return Math.round(yTop * 4f) + "/" + Math.round(yBot * 4f) + (tight ? "/t" : "");
		}
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
	 */
	static void fixWindingTowards(Quad q, float wx, float wy, float wz) {
		float[] a = q.pos[0], b = q.pos[2], c = q.pos[1];
		float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
		float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
		float gx = uy * vz - uz * vy;
		float gy = uz * vx - ux * vz;
		float gz = ux * vy - uy * vx;
		if (gx * wx + gy * wy + gz * wz < 0) {
			swapCorners12(q);
		}
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
