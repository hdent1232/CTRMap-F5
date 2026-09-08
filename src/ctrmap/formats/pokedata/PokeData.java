package ctrmap.formats.pokedata;

import ctrmap.formats.GameFiles;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.text.GFMessageFile;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import java.awt.Color;
import java.io.File;
import java.util.List;
import static ctrmap.formats.LittleEndian.i32;

/**
 * Read-only Pokemon reference data for the editor previews: base stats, types
 * and abilities (the PERSONAL archive), move type/category/power (MOVE_DATA),
 * and display names from GameText - archive locations and text-entry indices
 * come from the handed game's {@link GameProfile}. Cached in statics; every
 * accessor is null/absent-safe so the editors work even without the full
 * romfs (they just fall back to id-only labels).
 *
 * <p>WHOSE GAME. {@link #load} is handed the {@link GameFiles} to read and
 * replaces whatever was loaded before. This class used to load itself, once,
 * on the first accessor call, from the application's global session holder
 * (or, with no workspace open, by probing the configured game folder on its
 * own): so it could never be pointed at a second game in the same JVM - an
 * ORAS session followed by an XY one kept showing ORAS names - and no suite
 * could hand it tables of its own. Now the application loads it when a
 * workspace opens and forgets it on reset, beside the location names, and a
 * suite hands it a fake. With nothing loaded every accessor answers the
 * id-only label it always answered when no game could be found; the
 * difference is that nothing here goes looking for one.
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

	// ---- the loaded tables ------------------------------------------------

	private static byte[][] personal;      // [species] -> 80-byte record
	private static byte[] moveMini;        // the a/1/8/9 mini-container
	private static int moveCount;
	private static String[] speciesNames, abilityNames, moveNames, itemNames;

	/** True when a game has been loaded and it had a PERSONAL archive to read. */
	public static synchronized boolean available() {
		return personal != null;
	}

	/**
	 * Reads the handed game's reference tables, replacing whatever was loaded
	 * before. Archives the game lacks are left absent, and the accessors fall
	 * back to ids for them; a game that lacks all of them loads as nothing.
	 *
	 * <p>Reads the archives where the game keeps them, not the workspace's
	 * staged copies: this is reference data for previews, and a name edited
	 * in the workspace is not the game's name until it is packed.
	 */
	public static synchronized void load(GameFiles game) {
		if (game == null) {
			throw new IllegalArgumentException("PokeData.load was handed no game. The reference tables belong to"
					+ " a game, and nothing here goes looking for one.");
		}
		unload();
		GameProfile prof = game.profile();
		try {
			GARC p = optional(game.archiveFile(ArchiveType.PERSONAL));
			if (p != null) {
				personal = new byte[p.length][];
				for (int i = 0; i < p.length; i++) {
					personal[i] = p.getDecompressedEntry(i);
				}
			}
			GARC mv = optional(game.archiveFile(ArchiveType.MOVE_DATA));
			if (mv != null) {
				moveMini = mv.getDecompressedEntry(0);
				//header = 4 + (count+1) u32 offsets; count = (firstOffset-4)/4 - 1
				int first = i32(moveMini, 4);
				moveCount = (first - 4) / 4 - 1;
			}
		} catch (Exception ex) {
			System.err.println("PokeData: reference load failed: " + ex);
		}
		GARC gameText = optional(game.archiveFile(ArchiveType.GAMETEXT));
		speciesNames = text(gameText, prof.textIndex(GameProfile.TextIndex.SPECIES_NAMES));
		abilityNames = text(gameText, prof.textIndex(GameProfile.TextIndex.ABILITY_NAMES));
		moveNames = text(gameText, prof.textIndex(GameProfile.TextIndex.MOVE_NAMES));
		itemNames = text(gameText, prof.textIndex(GameProfile.TextIndex.ITEM_NAMES));
	}

	/**
	 * Forgets the loaded tables. {@code Workspace.reset} calls this beside
	 * {@code LocationNames.unload}: the tables are derived from a game, and a
	 * reset that kept them would hand one game's names to the next.
	 */
	public static synchronized void unload() {
		personal = null;
		moveMini = null;
		moveCount = 0;
		speciesNames = null;
		abilityNames = null;
		moveNames = null;
		itemNames = null;
	}

	/** An archive opened without compression sniffing, or null when the game lacks it or the file is not there. */
	private static GARC optional(File f) {
		try {
			return f != null && f.exists() ? new GARC(f, false) : null;
		} catch (Exception ex) {
			return null;
		}
	}

	/** Names read straight from the game's GameText (read-only reference). */
	private static String[] text(GARC gameText, int entry) {
		try {
			if (entry < 0 || gameText == null) {
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
		byte[] r = rec(species);
		if (r == null) {
			return null;
		}
		return new int[]{r[0] & 0xFF, r[1] & 0xFF, r[2] & 0xFF, r[3] & 0xFF, r[4] & 0xFF, r[5] & 0xFF};
	}

	/** {type1, type2}; type2 == type1 for mono-type. Null if unavailable. */
	public static synchronized int[] types(int species) {
		byte[] r = rec(species);
		if (r == null) {
			return null;
		}
		return new int[]{r[6] & 0xFF, r[7] & 0xFF};
	}

	/** Ability ids {a1, a2, hidden}, or null. */
	public static synchronized int[] abilities(int species) {
		byte[] r = rec(species);
		if (r == null || r.length < 0x1B) {
			return null;
		}
		return new int[]{r[0x18] & 0xFF, r[0x19] & 0xFF, r[0x1A] & 0xFF};
	}

	public static synchronized String speciesName(int species) {
		return name(speciesNames, species);
	}

	public static synchronized String abilityName(int ability) {
		return name(abilityNames, ability);
	}

	public static synchronized int speciesCount() {
		return speciesNames != null ? speciesNames.length : 722;
	}

	private static byte[] rec(int species) {
		return personal != null && species >= 0 && species < personal.length && personal[species] != null
				&& personal[species].length >= 8 ? personal[species] : null;
	}

	// ---- moves ------------------------------------------------------------

	/** {type, category(0/1/2), power, accuracy, pp} or null. */
	public static synchronized int[] moveInfo(int move) {
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
		return name(moveNames, move);
	}

	public static synchronized int moveCount() {
		return moveNames != null && moveNames.length > 1 ? moveNames.length : 622;
	}

	// ---- items ------------------------------------------------------------

	public static synchronized String itemName(int item) {
		return name(itemNames, item);
	}

	public static synchronized int itemCount() {
		return itemNames != null && itemNames.length > 1 ? itemNames.length : 776;
	}

	// ---- helpers ----------------------------------------------------------

	private static String name(String[] arr, int id) {
		if (arr != null && id >= 0 && id < arr.length && !arr[id].isEmpty()) {
			return arr[id];
		}
		return "#" + id;
	}
}
