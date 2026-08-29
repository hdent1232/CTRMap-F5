package ctrmap.formats.tilemap;

import java.awt.Color;

/**
 * The terrain brushes for the tile painter. Each terrain carries its measured
 * ORAS tilemap tuple (drives walkability + wild encounters + surf), the visual
 * material it paints with (matched by name against a "tileset" donor region -
 * a real map rich in terrain materials, e.g. Route 101), whether it produces a
 * walkable collision floor, and a swatch color for the paint UI.
 *
 * <p>Tuples measured from retail (byte order = tilemap file bytes 0..3):
 * ground {@code 20 00 00 00}, tall grass {@code 06 00 18 3D} (wild encounters),
 * water {@code 06 00 1A 3D} (surf), wall/void {@code 21 00 00 01},
 * sand {@code 20 80 0A 02}.
 */
public enum TilePalette {

	GRASS("Grass", new int[]{0x20, 0x00, 0x00, 0x00}, true, true, 0x6DBE5A,
			new String[]{"chip_kusa", "kusa", "grass", "happa", "shiba"}),
	TALL_GRASS("Tall grass (wild Pokemon)", new int[]{0x06, 0x00, 0x18, 0x3D}, true, true, 0x2E7D32,
			new String[]{"kusa", "grass", "happa"}),
	PATH("Path / dirt", new int[]{0x20, 0x00, 0x00, 0x00}, true, true, 0xC2A76A,
			new String[]{"soil", "michi", "road", "path", "chip_wood", "wood", "dirt"}),
	SAND("Sand / beach", new int[]{0x20, 0x80, 0x0A, 0x02}, true, true, 0xE7D6A0,
			new String[]{"suna", "sand", "beach", "hama"}),
	WATER("Water (surf)", new int[]{0x06, 0x00, 0x1A, 0x3D}, true, true, 0x3E74E8,
			new String[]{"chip_sea", "sea", "umi", "water", "mizu"}),
	ROCK("Rock / wall (blocked)", new int[]{0x21, 0x00, 0x00, 0x01}, false, false, 0x8C8C8C,
			new String[]{"chip_rock", "rock", "iwa", "gake", "ishi", "stone"}),
	// --- expanded catalog (measured tuples; behaviour is the b3 byte) ---
	CAVE("Cave floor", new int[]{0x24, 0x00, 0x00, 0x24}, true, true, 0x6E6656,
			new String[]{"doukutsu", "chip_rock", "rock", "iwa", "gake", "cave"}),
	DEEP_SAND("Deep sand", new int[]{0x20, 0xA0, 0x0A, 0x07}, true, true, 0xD9C58A,
			new String[]{"suna", "sand", "beach", "hama"}),
	ICE("Ice (slippery)", new int[]{0x24, 0x00, 0x00, 0x2D}, true, true, 0xBFE6F0,
			new String[]{"koori", "ice", "kori", "chip_wood"}),
	LEDGE_S("Ledge (jump down)", new int[]{0x21, 0x00, 0x00, 0x75}, true, true, 0x7DA84E,
			new String[]{"gake", "chip_kusa", "kusa", "grass"}),
	LEDGE_E("Ledge (jump east)", new int[]{0x21, 0x00, 0x02, 0x72}, true, true, 0x7DA84E,
			new String[]{"gake", "chip_kusa", "kusa", "grass"}),
	LEDGE_W("Ledge (jump west)", new int[]{0x21, 0x00, 0x02, 0x73}, true, true, 0x7DA84E,
			new String[]{"gake", "chip_kusa", "kusa", "grass"}),
	BIKE_RAIL("Bike rail (cycling road)", new int[]{0x60, 0x00, 0x00, 0x00}, true, true, 0xB08040,
			new String[]{"deck", "road", "michi", "chip_wood", "wood"}),
	WATERFALL("Waterfall", new int[]{0x23, 0x00, 0x1A, 0x40}, true, true, 0x5AA0F0,
			new String[]{"taki", "chip_sea", "sea", "water"}),
	DOOR("Door / warp tile", new int[]{0x01, 0x00, 0x0E, 0xD4}, true, true, 0xB05A3C,
			new String[]{"door", "soil", "michi", "chip_wood", "path"}),
	// interiors + structures (tuples measured across all 857 retail regions)
	INDOOR("Indoor floor", new int[]{0x04, 0x00, 0x00, 0x00}, true, true, 0xC8B088,
			new String[]{"yuka", "floor", "tatami", "chip_wood", "wood", "carpet"}),
	WALKWAY("Boardwalk / walkway", new int[]{0x28, 0x00, 0x00, 0x00}, true, true, 0xA5814B,
			new String[]{"hashi", "bridge", "deck", "chip_wood", "wood", "board"}),
	STAIRS_Z("Stairs (north-south)", new int[]{0x20, 0x00, 0x00, 0x6C}, true, true, 0x9A9A86,
			new String[]{"kaidan", "stairs", "step", "ishi", "stone", "chip_rock"}),
	STAIRS_E("Stairs (climb east)", new int[]{0x20, 0x00, 0x00, 0x6A}, true, true, 0x9A9A86,
			new String[]{"kaidan", "stairs", "step", "ishi", "stone", "chip_rock"}),
	STAIRS_W("Stairs (climb west)", new int[]{0x20, 0x00, 0x00, 0x69}, true, true, 0x9A9A86,
			new String[]{"kaidan", "stairs", "step", "ishi", "stone", "chip_rock"}),
	VOID("Empty / blocked", new int[]{0x21, 0x00, 0x00, 0x01}, false, false, 0x2B2B2B,
			new String[]{});

	public final String label;
	public final int[] tuple;
	/** Walkable = the player (or surf) can enter; drives whether a floor is laid. */
	public final boolean walkable;
	/** True to emit a collision floor quad for this tile. */
	public final boolean floor;
	public final int rgb;
	/** Material-name substrings (lowercased) to match in the tileset donor, best first. */
	public final String[] matHints;

	TilePalette(String label, int[] tuple, boolean walkable, boolean floor, int rgb, String[] matHints) {
		this.label = label;
		this.tuple = tuple;
		this.walkable = walkable;
		this.floor = floor;
		this.rgb = rgb;
		this.matHints = matHints;
	}

	public Color color() {
		return new Color(rgb);
	}

	/** The paintable brushes (VOID is the default background, not a brush swatch). */
	public static TilePalette[] brushes() {
		return new TilePalette[]{GRASS, TALL_GRASS, PATH, SAND, WATER, ROCK,
			CAVE, DEEP_SAND, ICE, LEDGE_S, LEDGE_E, LEDGE_W, BIKE_RAIL, WATERFALL, DOOR,
			INDOOR, WALKWAY, STAIRS_Z, STAIRS_E, STAIRS_W};
	}
}
