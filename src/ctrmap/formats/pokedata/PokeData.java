package ctrmap.formats.pokedata;

import ctrmap.formats.GameCache;
import ctrmap.formats.containers.ContainerBytes;
import ctrmap.formats.GameFiles;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.text.GFMessageFile;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import java.awt.Color;
import java.io.File;
import java.util.List;
import static ctrmap.util.LittleEndian.i32;

/**
 * Read-only Pokemon reference data for the editor previews: base stats, types
 * and abilities (the PERSONAL archive), move type/category/power (MOVE_DATA),
 * and display names from GameText - archive locations and text-entry indices
 * come from the handed game's {@link GameProfile}. One instance per game, kept
 * in that game's store; every accessor is null/absent-safe so the editors work
 * even without the full romfs (they just fall back to id-only labels).
 *
 * <p>WHOSE GAME. {@link #of} is handed the {@link GameFiles} to read and
 * answers that game's tables. This class used to load itself, once,
 * on the first accessor call, from the application's global session holder
 * (or, with no workspace open, by probing the configured game folder on its
 * own): so it could never be pointed at a second game in the same JVM - an
 * ORAS session followed by an XY one kept showing ORAS names - and no suite
 * could hand it tables of its own. Then the application loaded it into statics
 * when a workspace opened and forgot it on reset - never after a pack (F218).
 * Now each game's tables live in that game's store, which a pack empties, and
 * a suite hands it a fake. {@link #none} answers the id-only label every
 * accessor gave when no game could be found; nothing here goes looking for one.
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

	// ---- the tables of one game -------------------------------------------

	/** The handed game's tables, kept in that game's own store until a pack rewrites its archives (F218). */
	private static final GameCache.Key<PokeData> TABLES = new GameCache.Key<>("reference tables");

	private final byte[][] personal;      // [species] -> 80-byte record
	private final byte[] moveMini;        // the a/1/8/9 mini-container
	private final int moveCount;
	private final String[] speciesNames, abilityNames, moveNames, itemNames;
	/** How many species, moves and items the game's profile says there are - the counts when its text did not load; 0 with no game (F205). */
	private final int speciesTotal, moveTotal, itemTotal;

	private PokeData(byte[][] personal, byte[] moveMini, int moveCount, String[] speciesNames,
			String[] abilityNames, String[] moveNames, String[] itemNames, int speciesTotal, int moveTotal,
			int itemTotal) {
		this.personal = personal;
		this.moveMini = moveMini;
		this.moveCount = moveCount;
		this.speciesNames = speciesNames;
		this.abilityNames = abilityNames;
		this.moveNames = moveNames;
		this.itemNames = itemNames;
		this.speciesTotal = speciesTotal;
		this.moveTotal = moveTotal;
		this.itemTotal = itemTotal;
	}

	/**
	 * The handed game's reference tables: read the first time they are asked for, and kept in the
	 * game's own store until a pack rewrites its archives (F218). They were statics loaded when a
	 * workspace opened and forgotten on reset - and never after a pack, so a name packed into the
	 * game showed as the old one until the workspace was reopened.
	 *
	 * <p>Reads the archives where the game keeps them, not the workspace's staged copies: this is
	 * reference data for previews, and a name edited in the workspace is not the game's name until
	 * it is packed. Archives the game lacks are left absent, and the accessors fall back to ids for
	 * them; a game that lacks all of them reads as nothing.
	 *
	 * @param game the game to read; null is refused in words - a caller with no game open asks
	 * {@link #none()}, which says so in its name
	 */
	public static PokeData of(GameFiles game) {
		if (game == null) {
			throw new IllegalArgumentException("PokeData.of was handed no game. The reference tables belong to"
					+ " a game, and nothing here goes looking for one; with no game open, ask PokeData.none().");
		}
		return game.cache().get(TABLES, () -> read(game));
	}

	/** The tables of no game: every name is the id alone, every count 0 - what a picker shows with no workspace open. */
	public static PokeData none() {
		return new PokeData(null, null, 0, null, null, null, null, 0, 0, 0);
	}

	private static PokeData read(GameFiles game) {
		GameProfile prof = game.profile();
		byte[][] personal = null;
		byte[] moveMini = null;
		int moveCount = 0;
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
		return new PokeData(personal, moveMini, moveCount,
				text(gameText, prof.textIndex(GameProfile.TextIndex.SPECIES_NAMES)),
				text(gameText, prof.textIndex(GameProfile.TextIndex.ABILITY_NAMES)),
				text(gameText, prof.textIndex(GameProfile.TextIndex.MOVE_NAMES)),
				text(gameText, prof.textIndex(GameProfile.TextIndex.ITEM_NAMES)),
				Math.max(0, prof.speciesIdMax() + 1), Math.max(0, prof.moveIdMax() + 1),
				Math.max(0, prof.itemIdMax() + 1));
	}

	/** True when the game had a PERSONAL archive to read. */
	public boolean available() {
		return personal != null;
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
	public int[] baseStats(int species) {
		byte[] r = rec(species);
		if (r == null) {
			return null;
		}
		return new int[]{r[0] & 0xFF, r[1] & 0xFF, r[2] & 0xFF, r[3] & 0xFF, r[4] & 0xFF, r[5] & 0xFF};
	}

	/** {type1, type2}; type2 == type1 for mono-type. Null if unavailable. */
	public int[] types(int species) {
		byte[] r = rec(species);
		if (r == null) {
			return null;
		}
		return new int[]{r[6] & 0xFF, r[7] & 0xFF};
	}

	/** Ability ids {a1, a2, hidden}, or null. */
	public int[] abilities(int species) {
		byte[] r = rec(species);
		if (r == null || r.length < 0x1B) {
			return null;
		}
		return new int[]{r[0x18] & 0xFF, r[0x19] & 0xFF, r[0x1A] & 0xFF};
	}

	public String speciesName(int species) {
		return name(speciesNames, species);
	}

	public String abilityName(int ability) {
		return name(abilityNames, ability);
	}

	public int speciesCount() {
		return speciesNames != null ? speciesNames.length : speciesTotal;
	}

	private byte[] rec(int species) {
		return personal != null && species >= 0 && species < personal.length && personal[species] != null
				&& personal[species].length >= 8 ? personal[species] : null;
	}

	// ---- moves ------------------------------------------------------------

	/** {type, category(0/1/2), power, accuracy, pp} or null. */
	public int[] moveInfo(int move) {
		if (moveMini == null || move < 0 || move > moveCount) {
			return null;
		}
		byte[] rec = ContainerBytes.subfile(moveMini, move);
		if (rec == null || rec.length < 6) {
			return null;
		}
		return new int[]{rec[0] & 0xFF, rec[2] & 0xFF, rec[3] & 0xFF, rec[4] & 0xFF, rec[5] & 0xFF};
	}

	public String moveName(int move) {
		return name(moveNames, move);
	}

	public int moveCount() {
		return moveNames != null && moveNames.length > 1 ? moveNames.length : moveTotal;
	}

	// ---- items ------------------------------------------------------------

	public String itemName(int item) {
		return name(itemNames, item);
	}

	public int itemCount() {
		return itemNames != null && itemNames.length > 1 ? itemNames.length : itemTotal;
	}

	// ---- helpers ----------------------------------------------------------

	private static String name(String[] arr, int id) {
		if (arr != null && id >= 0 && id < arr.length && !arr[id].isEmpty()) {
			return arr[id];
		}
		return "#" + id;
	}
}
