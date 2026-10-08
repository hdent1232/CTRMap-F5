package ctrmap.formats.zone;

/**
 * Where a zone's tile is in the world (F117): a tile is 18 world units, and what stands on a tile -
 * an NPC, a warp, a door - stands at its centre, 18 * tile + 9. That rule was written out fifteen
 * times across the entity records, the NPC and warp forms, the painter and the warp tool.
 *
 * <p>Not: {@code ctrmap.formats.tilemap.PaintedGeometry} - that is the painter's frame for one region,
 * 40 tiles round a centre origin; this is the zone's own tile grid, the one its entity records are
 * written in. Nor {@code TileMapPanel} - that turns world units into the pixels a view draws; this
 * is the world side, which a format class needs and may not reach a window for.
 */
public final class WorldGrid {

	private WorldGrid() {
	}

	/** World units across one tile. */
	public static final int TILE = 18;

	/** The world coordinate of a tile's centre. */
	public static int tileCentre(int tile) {
		return tile * 18 + 9;
	}

	/** The tile whose centre is {@code world}, as a fraction when it is not a centre. */
	public static float tileOfCentre(float world) {
		return (world - 9f) / 18f;
	}
}
