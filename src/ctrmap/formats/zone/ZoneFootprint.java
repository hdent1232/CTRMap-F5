package ctrmap.formats.zone;

/**
 * Which cells of its map matrix a zone's own content actually occupies.
 *
 * <p>WHY THIS EXISTS. Several zones can share one map matrix, and when they do the editor drew
 * the identical picture for every one of them. Reported with three screenshots: Route 132,
 * Route 133 and Route 134 are zones 61, 62 and 63, all "area 19, map 12", and the preview was
 * the same image three times - correct, and useless for choosing which one to open.
 *
 * <p>MEASURED on the retail dump before any of this was written, because the obvious candidates
 * were wrong. The header carries {@code PX/PX2/PY/PY2}, which reads like a rectangle and is
 * <b>zero on every zone sampled</b>; it carries {@code X2/Y2/Z2}, which is a copy of the spawn
 * point rather than an opposite corner. What does separate them is where their content sits:
 *
 * <pre>
 *   zone 61  spawn 7605,2007   NPCs/props around tile 336..422   -> cells x 8..10
 *   zone 62  spawn 5553,2385   NPCs/props around tile 217..308   -> cells x 5..7
 *   zone 63  spawn 3393,2277   NPCs/props around tile 120..188   -> cells x 2..4
 * </pre>
 *
 * Three adjacent, non-overlapping thirds of one route - which is exactly the fact the preview
 * was hiding.
 *
 * <p>TWO COORDINATE SPACES, and mixing them is the mistake this class exists to stop. The
 * header's spawn point and a warp's position are in WORLD units; an NPC, a prop and a trigger
 * are in TILES. The first attempt at this measurement took a min/max over all of them at once
 * and produced ranges that could not all be the same units - zone 62 reading "x 217..5553" -
 * which looked like data and was arithmetic over two different things. The conversion is the
 * one {@code GeoBoxOps} already documents: 1 tile = 18 world units, a cell = 40x40 tiles.
 *
 * <p>Not: {@code TilePainterForm.firstRegionCell} - the nearest thing in the tree, and it does
 * convert a zone's header position into a matrix cell with the same {@code / 720}. Three
 * reasons it could not be called. It answers with ONE cell, the spawn's, where the question
 * here is the whole span a zone occupies - zone 61 spreads over three. It takes a
 * {@code LoadedZone}, the zone the window has OPEN, and the preview's whole purpose is
 * describing a zone nobody has opened. And it reads the workspace through the
 * {@code Workspace} statics, which WorkspaceSessionTest holds to a falling ceiling; the
 * preview is handed its session for exactly that reason. This takes a header and its entities
 * and touches nothing else, which is also what makes it drivable from a suite with no game.
 */
public final class ZoneFootprint {

	/** World units per tile. Stated in GeoBoxOps and confirmed here against the dump. */
	public static final int WORLD_PER_TILE = 18;
	/** Tiles along one edge of a map-matrix cell. */
	public static final int TILES_PER_CELL = 40;
	/** World units along one edge of a cell: the 720 the camera arithmetic uses. */
	public static final int WORLD_PER_CELL = WORLD_PER_TILE * TILES_PER_CELL;

	/** The bounding box in WORLD units, inclusive. */
	public final int minX, minY, maxX, maxY;
	/** How many placed things this was computed from - 0 is never returned, see {@link #of}. */
	public final int from;

	private ZoneFootprint(int minX, int minY, int maxX, int maxY, int from) {
		this.minX = minX;
		this.minY = minY;
		this.maxX = maxX;
		this.maxY = maxY;
		this.from = from;
	}

	public int minCellX() {
		return minX / WORLD_PER_CELL;
	}

	public int minCellY() {
		return minY / WORLD_PER_CELL;
	}

	public int maxCellX() {
		return maxX / WORLD_PER_CELL;
	}

	public int maxCellY() {
		return maxY / WORLD_PER_CELL;
	}

	/** How many cells across the footprint is, at least 1. */
	public int cellsAcross() {
		return maxCellX() - minCellX() + 1;
	}

	/** How many cells down the footprint is, at least 1. */
	public int cellsDown() {
		return maxCellY() - minCellY() + 1;
	}

	/**
	 * Whether this box can be about a matrix of this size at all.
	 *
	 * <p>THIS REPLACED A CLAMP, and the clamp was a defect the owner had to report twice.
	 * Squeezing an out-of-range box into the matrix turns "these numbers are not about this
	 * map" into a confident, wrong, in-range answer: zone 0's content measures twelve cells
	 * across, its map is ONE cell, and clamping collapsed that to cell (0,0) - so the camera
	 * aimed at a corner and the preview went BLACK. Measured on the owner's own game:
	 * matrix 0 is 1x1, matrix 15 is 2x1, matrix 12 is 16x6. Zone 0 has no NPCs and no props,
	 * fifty warps, and its warp coordinates do not sit in its own map's space; whatever they
	 * are about, they are not about a 1x1 matrix.
	 *
	 * <p>So the answer is a refusal, not a repair. A box that cannot be about this map makes
	 * the caller frame the whole map instead - which is what the preview did before any of
	 * this existed, and is never wrong, only unhelpful. An unhelpful picture of the right
	 * place beats a helpful-looking picture of the wrong one.
	 *
	 * <p>A matrix of no size answers false: its dimensions are unknown, and an unknown map
	 * cannot confirm that anything fits inside it.
	 */
	public boolean fitsIn(int cellsAcross, int cellsDown) {
		if (cellsAcross <= 0 || cellsDown <= 0) {
			return false;
		}
		return minCellX() >= 0 && minCellY() >= 0
				&& maxCellX() < cellsAcross && maxCellY() < cellsDown;
	}

	/** {@code "cells (8,2)-(10,2)"}, or {@code "cell (7,3)"} when it is one cell. */
	public String describeCells() {
		if (cellsAcross() == 1 && cellsDown() == 1) {
			return "cell (" + minCellX() + "," + minCellY() + ")";
		}
		return "cells (" + minCellX() + "," + minCellY() + ")-(" + maxCellX() + "," + maxCellY() + ")";
	}

	/**
	 * Where this zone's own content sits, or NULL when nothing in it says where it is.
	 *
	 * <p>Null rather than a box at the origin. A zone with no entities and a spawn point of
	 * exactly (0,0) is indistinguishable from a zone whose position was never set, and cell
	 * (0,0) is a real cell - so answering "the top-left corner" would be a guess wearing the
	 * clothes of a measurement. The caller frames the whole matrix instead, which is what the
	 * preview did for every zone before this existed.
	 */
	public static ZoneFootprint of(ZoneHeader header, ZoneEntities entities) {
		if (header == null) {
			return null;
		}
		Box box = new Box();
		if (header.X != 0 || header.Y != 0) {
			box.world(header.X, header.Y);
		}
		if (entities != null) {
			for (ZoneEntities.NPC npc : entities.npcs) {
				box.tile(npc.xTile, npc.yTile);
			}
			for (ZoneEntities.Warp warp : entities.warps) {
				box.world(warp.x, warp.y);
			}
			for (ZoneEntities.Prop prop : entities.furniture) {
				box.tile(prop.x, prop.y);
			}
			for (ZoneEntities.Trigger t : entities.triggers1) {
				box.tile(t.x, t.y);
			}
			for (ZoneEntities.Trigger t : entities.triggers2) {
				box.tile(t.x, t.y);
			}
		}
		return box.from == 0 ? null
				: new ZoneFootprint(box.minX, box.minY, box.maxX, box.maxY, box.from);
	}

	/** Accumulates the box, and is the only place either unit is converted. */
	private static final class Box {

		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
		int from;

		void world(int x, int y) {
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
			minY = Math.min(minY, y);
			maxY = Math.max(maxY, y);
			from++;
		}

		void tile(int x, int y) {
			world(x * WORLD_PER_TILE, y * WORLD_PER_TILE);
		}
	}
}
