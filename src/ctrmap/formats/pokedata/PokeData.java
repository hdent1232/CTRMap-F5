package ctrmap.formats.pokedata;

import ctrmap.Workspace;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.text.GFMessageFile;
import ctrmap.gamedef.GameProfile;
import java.awt.Color;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Read-only Pokemon reference data for the editor previews: base stats, types
 * and abilities (the PERSONAL archive), move type/category/power (MOVE_DATA),
 * and display names from GameText - archive locations and text-entry indices
 * come from the active {@link GameProfile}. Loaded lazily from the game
 * directory and cached. All accessors are null/absent-safe so the editors work
 * even without the full romfs (they just fall back to id-only labels).
 */
public class PokeData {

	// gen6 type ids (0..17) with their standard UI colors
	public static final String[] TYPE_NAMES = {
		"Normal", "Fighting", "Flying", "Poison", "Ground", "Rock", "Bug", "Ghost",
		"Steel", "Fire", "Water", "Grass", "Electric", "Psychic", "Ice", "Dragon", "Dark", "Fairy"
	};
	private static final int[] TYPE_RGB = {
		0xA8A878, 0xC03028, 0xA890F0, 0xA040A0, 0xE0C068, 0xB8A038, 0xA8B820, 0x705898,
		0xB8B8D0, 0xF08030, 0x6890F0, 0x78C850, 0xF8D030, 0xF85888, 0x98D8D8, 0x7038F8, 0x705848, 0xEE99AC
	};
	public static final String[] CATEGORY_NAMES = {"Status", "Physical", "Special"};

	public static Color typeColor(int type) {
		return new Color(type >= 0 && type < TYPE_RGB.length ? TYPE_RGB[type] : 0x808080);
	}

	public static String typeName(int type) {
		return type >= 0 && type < TYPE_NAMES.length ? TYPE_NAMES[type] : "?";
	}

	// ---- lazy-loaded caches -----------------------------------------------

	private static boolean loaded = false;
	private static byte[][] personal;      // [species] -> 80-byte record
	private static byte[] moveMini;        // the a/1/8/9 mini-container
	private static int moveCount;
	private static String[] speciesNames, abilityNames, moveNames, itemNames;

	public static synchronized boolean available() {
		ensure();
		return personal != null;
	}

	private static void ensure() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			GARC p = optional(profile().archivePath(Workspace.ArchiveType.PERSONAL));
			if (p != null) {
				personal = new byte[p.length][];
				for (int i = 0; i < p.length; i++) {
					personal[i] = p.getDecompressedEntry(i);
				}
			}
			GARC mv = optional(profile().archivePath(Workspace.ArchiveType.MOVE_DATA));
			if (mv != null) {
				moveMini = mv.getDecompressedEntry(0);
				//header = 4 + (count+1) u32 offsets; count = (firstOffset-4)/4 - 1
				int first = i32(moveMini, 4);
				moveCount = (first - 4) / 4 - 1;
			}
		} catch (Exception ex) {
			System.err.println("PokeData: reference load failed: " + ex);
		}
		speciesNames = text(profile().textIndex(GameProfile.TextIndex.SPECIES_NAMES));
		abilityNames = text(profile().textIndex(GameProfile.TextIndex.ABILITY_NAMES));
		moveNames = text(profile().textIndex(GameProfile.TextIndex.MOVE_NAMES));
		itemNames = text(profile().textIndex(GameProfile.TextIndex.ITEM_NAMES));
	}

	/** The active game's profile, or ORAS when no workspace is loaded (the
	 *  reference-data paths are then probed against whatever dir is set). */
	private static GameProfile profile() {
		try {
			return Workspace.game != null ? GameProfile.current() : GameProfile.of(Workspace.GameType.ORAS);
		} catch (RuntimeException ex) {
			return GameProfile.of(Workspace.GameType.ORAS);
		}
	}

	private static GARC optional(String rel) {
		try {
			if (rel == null) {
				return null;
			}
			File f = new File(Workspace.GAMEDIR_PATH + rel);
			return f.exists() ? new GARC(f, false) : null;
		} catch (Exception ex) {
			return null;
		}
	}

	private static GARC gameText;

	/** Names read straight from the game dir's GameText (read-only reference). */
	private static String[] text(int entry) {
		try {
			if (entry < 0) {
				return new String[0];
			}
			if (gameText == null) {
				gameText = optional(profile().archivePath(Workspace.ArchiveType.GAMETEXT));
			}
			if (gameText == null) {
				return new String[0];
			}
			List<String> lines = GFMessageFile.getStrings(gameText.getDecompressedEntry(entry));
			return lines.toArray(new String[0]);
		} catch (Exception ex) {
			return new String[0];
		}
	}

	// ---- species ----------------------------------------------------------

	/** Base stats HP/Atk/Def/Spe/SpA/SpD, or null if unavailable. */
	public static synchronized int[] baseStats(int species) {
		ensure();
		byte[] r = rec(species);
		if (r == null) {
			return null;
		}
		return new int[]{r[0] & 0xFF, r[1] & 0xFF, r[2] & 0xFF, r[3] & 0xFF, r[4] & 0xFF, r[5] & 0xFF};
	}

	/** {type1, type2}; type2 == type1 for mono-type. Null if unavailable. */
	public static synchronized int[] types(int species) {
		ensure();
		byte[] r = rec(species);
		if (r == null) {
			return null;
		}
		return new int[]{r[6] & 0xFF, r[7] & 0xFF};
	}

	/** Ability ids {a1, a2, hidden}, or null. */
	public static synchronized int[] abilities(int species) {
		ensure();
		byte[] r = rec(species);
		if (r == null || r.length < 0x1B) {
			return null;
		}
		return new int[]{r[0x18] & 0xFF, r[0x19] & 0xFF, r[0x1A] & 0xFF};
	}

	public static synchronized String speciesName(int species) {
		ensure();
		return name(speciesNames, species);
	}

	public static synchronized String abilityName(int ability) {
		ensure();
		return name(abilityNames, ability);
	}

	public static synchronized int speciesCount() {
		ensure();
		return speciesNames != null ? speciesNames.length : 722;
	}

	private static byte[] rec(int species) {
		return personal != null && species >= 0 && species < personal.length && personal[species] != null
				&& personal[species].length >= 8 ? personal[species] : null;
	}

	// ---- moves ------------------------------------------------------------

	/** {type, category(0/1/2), power, accuracy, pp} or null. */
	public static synchronized int[] moveInfo(int move) {
		ensure();
		if (moveMini == null || move < 0 || move > moveCount) {
			return null;
		}
		int o = i32(moveMini, 4 + move * 4);
		if (o + 6 > moveMini.length) {
			return null;
		}
		return new int[]{moveMini[o] & 0xFF, moveMini[o + 2] & 0xFF, moveMini[o + 3] & 0xFF,
			moveMini[o + 4] & 0xFF, moveMini[o + 5] & 0xFF};
	}

	public static synchronized String moveName(int move) {
		ensure();
		return name(moveNames, move);
	}

	public static synchronized int moveCount() {
		ensure();
		return moveNames != null && moveNames.length > 1 ? moveNames.length : 622;
	}

	// ---- items ------------------------------------------------------------

	public static synchronized String itemName(int item) {
		ensure();
		return name(itemNames, item);
	}

	public static synchronized int itemCount() {
		ensure();
		return itemNames != null && itemNames.length > 1 ? itemNames.length : 776;
	}

	// ---- helpers ----------------------------------------------------------

	private static String name(String[] arr, int id) {
		if (arr != null && id >= 0 && id < arr.length && !arr[id].isEmpty()) {
			return arr[id];
		}
		return "#" + id;
	}

	private static int i32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
