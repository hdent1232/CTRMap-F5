package ctrmap.formats.tilemap;

import ctrmap.formats.GameFiles;
import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.MapModelObj;
import ctrmap.formats.h3d.MapModelObjImporter;
import ctrmap.formats.h3d.RegionFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static ctrmap.formats.LittleEndian.f32;
import static ctrmap.formats.tilemap.PaintedRegionBuilder.*;
import static ctrmap.formats.tilemap.PaintedCliffMesh.*;
import static ctrmap.formats.tilemap.PaintedCollision.*;
import static ctrmap.formats.tilemap.PaintedFloorMesh.*;
import static ctrmap.formats.tilemap.PaintedMaterials.*;
import static ctrmap.formats.tilemap.PaintedTiles.*;

/**
 * The painter's what height each tile sits at, and which tiles are ground at all.
 *
 * <p>One stage of {@link PaintedRegionBuilder}, which was 3750 lines and 106
 * methods in one file. Every method here is exactly the one that was there,
 * moved and not rewritten: the split is about which file you open to read a
 * stage, not about what the pipeline does. The constants and the other stages
 * are imported statically for the same reason - a body that called a helper
 * still calls the same helper.
 *
 * <p>What this is NOT is decoupling. PaintedRegionBuilder was never a god
 * object by coupling: it is pure, it holds no state, and it has six callers.
 * It was simply too long to read.
 */
public final class PaintedHeights {

	private PaintedHeights() {
	}

	/** A ramp grid with no ramps on it. */
	public static int[][] noRamps() {
		int[][] r = new int[DIM][DIM];
		for (int[] row : r) {
			java.util.Arrays.fill(row, NO_RAMP);
		}
		return r;
	}

	// ---- retail surface heights (composite frame) -------------------------

	/**
	 * Seeds the painter's elevation grid from the region's collision: each
	 * tile's level = its retail GROUND height at the tile center (the lowest
	 * surface, so bridges/roofs above never hijack the frame), quantized to
	 * {@link #STEP} RELATIVE to the region's lowest ground. The composite
	 * builder uses the same sampling and baseline, so a tile whose level the
	 * user leaves alone regenerates at EXACTLY its retail height (the
	 * quantization offsets cancel), and below-zero caves or high plateaus
	 * keep the full 0..6 editing range.
	 *
	 * @param tilemap the region's movement tiles, so a tile with no ground of
	 *                its own takes it from walkable ground first
	 * @return how many tiles had no ground of their own and took their nearest
	 *         neighbour's ({@link #nearestGround}). The painter tells the user,
	 *         because those tiles start level with whatever stands beside them
	 *         rather than at a height the map itself gave them.
	 */
	public static int seedHeightsFromCollision(byte[] coll, byte[] tilemap, int[][] height) {
		float[][] by = sampleBaseY(coll);
		float[][] ground = nearestGround(by, walkableTiles(tilemap));
		float base0 = baseFloor(by);
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				height[ty][tx] = Float.isNaN(ground[ty][tx]) ? 0 : levelOf(ground[ty][tx], base0);
			}
		}
		return borrowedGroundTiles(by, ground, null);
	}

	/** The per-tile painted-floor Y grid for the region's CURRENT collision and
	 *  tilemap + a level grid - the shared frame for floors, buildings, door
	 *  props and warps. An unusable collision degrades to the plain level*STEP frame. */
	public static float[][] floorYGrid(byte[] coll, byte[] tilemap, int[][] height) {
		float[][] by = sampleBaseY(coll);
		float[][] ground = nearestGround(by, walkableTiles(tilemap));
		float base0 = baseFloor(by);
		float[][] out = new float[DIM][DIM];
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				out[ty][tx] = floorYOf(ground[ty][tx], height[ty][tx], base0);
			}
		}
		return out;
	}

	/**
	 * How many of the {@code touched} tiles have no collision sample of their
	 * own and are therefore built on ground borrowed from the nearest walkable
	 * neighbour ({@link #nearestGround}). A tile with no sample anywhere in the
	 * region borrows nothing and is not counted. Apply reports this, because a
	 * patch standing on ground taken from beside it is level with its
	 * surroundings rather than at a height the map itself gave it.
	 */
	public static int borrowedGroundTiles(byte[] coll, byte[] tilemap, boolean[][] touched) {
		float[][] by = sampleBaseY(coll);
		return borrowedGroundTiles(by, nearestGround(by, walkableTiles(tilemap)), touched);
	}

	/** {@link #borrowedGroundTiles(byte[], byte[], boolean[][])} over grids the builder already has. */
	static int borrowedGroundTiles(float[][] baseY, float[][] ground, boolean[][] touched) {
		int borrowed = 0;
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				if ((touched == null || touched[ty][tx]) && Float.isNaN(baseY[ty][tx]) && !Float.isNaN(ground[ty][tx])) {
					borrowed++;
				}
			}
		}
		return borrowed;
	}

	/** Per-tile walkability from a tilemap subfile (bit 0 of byte 0 clear), or null without a usable tilemap. */
	public static boolean[][] walkableTiles(byte[] tilemap) {
		if (tilemap == null || tilemap.length < 4 + DIM * DIM * 4) {
			return null;
		}
		boolean[][] out = new boolean[DIM][DIM];
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				out[ty][tx] = (tilemap[4 + (ty * DIM + tx) * 4] & 1) == 0;
			}
		}
		return out;
	}

	/**
	 * {@link #sampleBaseY} with its gaps filled from the nearest sampled tile,
	 * so a tile with no collision under its centre - a wall, a cliff face, the
	 * void past the map's edge; 44% of all retail tiles - takes the ground
	 * BESIDE it. It used to take level 0, the region's lowest ground, so the
	 * one grass tile painted to widen a path came out as a walled pit up to
	 * seven tiles deep, still marked walkable, under retail scenery that hid
	 * it. Grows ring by ring so the nearest sample wins. Among equally near
	 * samples, one under a walkable tile beats one under a blocked tile, and
	 * the lower of a kind wins after that, as the sampler itself prefers
	 * ground to what stands over it: taking the lowest regardless borrowed the
	 * cliff base a plateau path runs along, and 276 tiles in 58 retail regions
	 * still came out as walled pits up to seven tiles deep. {@code walkable}
	 * is per-tile walkability, or null when there is no tilemap to read it
	 * from. A region with no sample at all stays NaN throughout.
	 */
	static float[][] nearestGround(float[][] baseY, boolean[][] walkable) {
		float[][] out = new float[DIM][];
		boolean[][] onFoot = new boolean[DIM][DIM];
		for (int ty = 0; ty < DIM; ty++) {
			out[ty] = baseY[ty].clone();
			for (int tx = 0; tx < DIM; tx++) {
				onFoot[ty][tx] = walkable != null && walkable[ty][tx] && !Float.isNaN(baseY[ty][tx]);
			}
		}
		for (boolean grew = true; grew;) {
			grew = false;
			float[][] ring = new float[DIM][DIM];
			boolean[][] ringFoot = new boolean[DIM][DIM];
			for (int ty = 0; ty < DIM; ty++) {
				for (int tx = 0; tx < DIM; tx++) {
					ring[ty][tx] = out[ty][tx];
					ringFoot[ty][tx] = onFoot[ty][tx];
					if (!Float.isNaN(out[ty][tx])) {
						continue;
					}
					for (int d = 0; d < 4; d++) {
						int nx = tx + (d == 0 ? 1 : d == 1 ? -1 : 0);
						int ny = ty + (d == 2 ? 1 : d == 3 ? -1 : 0);
						if (nx < 0 || ny < 0 || nx >= DIM || ny >= DIM || Float.isNaN(out[ny][nx])) {
							continue;
						}
						boolean better = Float.isNaN(ring[ty][tx])
								|| (onFoot[ny][nx] && !ringFoot[ty][tx])
								|| (onFoot[ny][nx] == ringFoot[ty][tx] && out[ny][nx] < ring[ty][tx]);
						if (better) {
							ring[ty][tx] = out[ny][nx];
							ringFoot[ty][tx] = onFoot[ny][nx];
							grew = true;
						}
					}
				}
			}
			out = ring;
			onFoot = ringFoot;
		}
		return out;
	}

	/** The region's ground baseline: its lowest sampled surface (level 0). */
	static float baseFloor(float[][] baseY) {
		float min = Float.NaN;
		for (float[] row : baseY) {
			for (float y : row) {
				if (!Float.isNaN(y) && (Float.isNaN(min) || y < min)) {
					min = y;
				}
			}
		}
		return Float.isNaN(min) ? 0f : min;
	}

	static int levelOf(float y, float base0) {
		return Math.max(0, Math.min(6, Math.round((y - base0) / STEP)));
	}

	/** Painted-floor Y for a tile: the retail surface plus the user's level
	 *  offset from the seeded level; baseline + level*STEP only when the whole
	 *  region has no surface at all (a lone unsampled tile takes its nearest
	 *  neighbour's through {@link #nearestGround} before it gets here). */
	static float floorYOf(float baseY, int h, float base0) {
		if (Float.isNaN(baseY)) {
			return base0 + h * STEP;
		}
		return baseY + (h - levelOf(baseY, base0)) * STEP;
	}

	/** Per-tile retail GROUND Y at the tile center (the LOWEST collision hit -
	 *  overhead decks and stamped-building roofs must not count), NaN where none. */
	public static float[][] sampleBaseY(byte[] donorColl) {
		float[][] out = new float[DIM][DIM];
		for (float[] row : out) {
			java.util.Arrays.fill(row, Float.NaN);
		}
		if (!GfColl.isColl(donorColl)) {
			return out;
		}
		GfColl c;
		try {
			c = new GfColl(donorColl);
		} catch (RuntimeException ex) {
			return out;
		}
		for (float[] t : c.uniqueTris) {
			float minX = Math.min(t[0], Math.min(t[3], t[6])), maxX = Math.max(t[0], Math.max(t[3], t[6]));
			float minZ = Math.min(t[2], Math.min(t[5], t[8])), maxZ = Math.max(t[2], Math.max(t[5], t[8]));
			int tx0 = Math.max(0, (int) Math.floor((minX - ORIGIN) / TILE));
			int tx1 = Math.min(DIM - 1, (int) Math.floor((maxX - ORIGIN) / TILE));
			int ty0 = Math.max(0, (int) Math.floor((minZ - ORIGIN) / TILE));
			int ty1 = Math.min(DIM - 1, (int) Math.floor((maxZ - ORIGIN) / TILE));
			for (int ty = ty0; ty <= ty1; ty++) {
				for (int tx = tx0; tx <= tx1; tx++) {
					float px = (tx + 0.5f) * TILE + ORIGIN;
					float pz = (ty + 0.5f) * TILE + ORIGIN;
					float y = triYAt(t, px, pz);
					if (!Float.isNaN(y) && (Float.isNaN(out[ty][tx]) || y < out[ty][tx])) {
						out[ty][tx] = y;
					}
				}
			}
		}
		return out;
	}

	/** Y of the triangle's plane at plan-view point (px,pz) when the point is
	 *  inside the triangle (small tolerance); NaN otherwise (incl. vertical tris). */
	static float triYAt(float[] t, float px, float pz) {
		float ax = t[0], az = t[2], bx = t[3], bz = t[5], cx = t[6], cz = t[8];
		float d = (bz - cz) * (ax - cx) + (cx - bx) * (az - cz);
		if (Math.abs(d) < 1e-3f) {
			return Float.NaN; // vertical / degenerate in plan view
		}
		float wa = ((bz - cz) * (px - cx) + (cx - bx) * (pz - cz)) / d;
		float wb = ((cz - az) * (px - cx) + (ax - cx) * (pz - cz)) / d;
		float wc = 1f - wa - wb;
		float eps = -0.02f;
		if (wa < eps || wb < eps || wc < eps) {
			return Float.NaN;
		}
		return wa * t[1] + wb * t[4] + wc * t[7];
	}

	/**
	 * The way down from a sloped tile - 0 E, 1 W, 2 S, 3 N - or -1 when the
	 * tile lies flat. A ramp descends the way the ramp grid says. A stair
	 * brush descends the way its movement tuple says, a north-south stair
	 * toward whichever end is lower, and lies flat when that ground is not
	 * lower: a stair painted on level ground is a texture, not a slope.
	 *
	 * <p>A ramp used to take the first neighbour, in E,W,S,N order, that sat
	 * EXACTLY one level down. The one-tile notch every route uses for its way
	 * up a hill has lower ground on three sides, so East always won and the
	 * slope ran across the corridor instead of along it; and a hill raised
	 * twice had no neighbour exactly one level down, so its ramp was dropped
	 * without a word and the tile built as a 36-unit wall. The direction now
	 * travels with the ramp, and any lower neighbour will do - the foot of
	 * the slope already comes from that neighbour's true floor, so a deeper
	 * drop is simply a longer slope. A ramp whose way down is not lower is a
	 * contradiction in the input, not a wall to build quietly.
	 */
	public static int rampDir(TilePalette[][] grid, int[][] height, int[][] ramp, int tx, int ty) {
		int d = ramp[ty][tx];
		if (d != NO_RAMP) {
			if (!descends(grid, height, tx, ty, d)) {
				throw new IllegalStateException("the ramp at (" + tx + "," + ty + ") goes down "
						+ DIR_NAMES[d] + " but the ground there is not lower");
			}
			return d;
		}
		TilePalette t = grid[ty][tx];
		if (t == null || t.descent == TilePalette.FLAT) {
			return -1;
		}
		if (t.descent == TilePalette.DOWN_N_OR_S) {
			return descends(grid, height, tx, ty, 2) ? 2 : descends(grid, height, tx, ty, 3) ? 3 : -1;
		}
		return descends(grid, height, tx, ty, t.descent) ? t.descent : -1;
	}

	/** True when the ground in direction {@code dir} (0 E, 1 W, 2 S, 3 N) lies below this tile. */
	public static boolean descends(TilePalette[][] grid, int[][] height, int tx, int ty, int dir) {
		return neighbourHeight(grid, height, tx, ty, dir) < height[ty][tx];
	}

	/**
	 * The way down a new ramp at (tx,ty) should take, read off the level
	 * gradient across all four neighbours: the axis with the steepest fall
	 * from the tile behind to the tile ahead, so a notch cut into a hillside
	 * runs along its corridor rather than over the side. -1 when nothing
	 * around the tile is lower. Ties keep E,W,S,N order; the painter lets the
	 * user turn the ramp when that is not what they meant.
	 */
	public static int steepestDescent(TilePalette[][] grid, int[][] height, int tx, int ty) {
		int best = -1, bestFall = Integer.MIN_VALUE;
		for (int d = 0; d < 4; d++) {
			if (!descends(grid, height, tx, ty, d)) {
				continue;
			}
			int behind = d == 0 ? 1 : d == 1 ? 0 : d == 2 ? 3 : 2;
			int fall = neighbourHeight(grid, height, tx, ty, behind) - neighbourHeight(grid, height, tx, ty, d);
			if (fall > bestFall) {
				best = d;
				bestFall = fall;
			}
		}
		return best;
	}

	/**
	 * The neighbour's top surface for cliff generation: its painted floor when
	 * it is painted, its retail surface when composite-untouched, ground level
	 * for void/off-map in from-scratch mode. Off-map in composite mode answers
	 * with the tile's OWN retail ground, so raised/lowered tiles at the cell
	 * border still close with a wall. Null = no wall (surface unknown).
	 */
	static Float neighbourTopY(TilePalette[][] grid, float[][] yTop, float[][] baseY,
			boolean[][] touched, int tx, int ty, int dir) {
		int nx = tx + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
		int ny = ty + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
		if (nx < 0 || ny < 0 || nx >= DIM || ny >= DIM) {
			if (touched == null) {
				return 0f;
			}
			float own = baseY != null ? baseY[ty][tx] : Float.NaN;
			return Float.isNaN(own) ? null : Float.valueOf(own);
		}
		if (touched != null && !touched[ny][nx]) {
			float b = baseY != null ? baseY[ny][nx] : Float.NaN;
			return Float.isNaN(b) ? null : Float.valueOf(b);
		}
		TilePalette t = grid[ny][nx];
		if (t == null || t == TilePalette.VOID) {
			if (touched == null) {
				return 0f; // void = drop to base (so raised ground gets a wall)
			}
			float b = baseY != null ? baseY[ny][nx] : Float.NaN;
			return Float.isNaN(b) ? 0f : b;
		}
		return yTop[ny][nx];
	}

	/**
	 * Where a ramp's slope lands: the descent neighbour's top - its painted
	 * floor, or its retail surface when it is untouched. An untouched
	 * neighbour with no collision under its centre has no surface to answer
	 * with, and the slope used to fall back to one step down: a two-level
	 * ramp toward such a tile - 1.2% of retail tiles border one - ended 18
	 * units in the air with no wall and no word, and Apply reported success.
	 * Its foot is now the ground that tile borrows from beside it, the same
	 * floor the painter seeded its level from. Off the map: one step down.
	 */
	static float rampFoot(TilePalette[][] grid, float[][] yTop, float[][] baseY, boolean[][] touched,
			int tx, int ty, int dir, float myY) {
		Float top = neighbourTopY(grid, yTop, baseY, touched, tx, ty, dir);
		if (top != null) {
			return top;
		}
		int nx = tx + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
		int ny = ty + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
		if (nx < 0 || ny < 0 || nx >= DIM || ny >= DIM) {
			return myY - STEP;
		}
		return yTop[ny][nx];
	}

	/** The neighbour tile's touched state; off-map counts as untouched. */
	static boolean isTouched(boolean[][] touched, int tx, int ty, int dir) {
		int nx = tx + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
		int ny = ty + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
		return nx >= 0 && ny >= 0 && nx < DIM && ny < DIM && touched[ny][nx];
	}
}
