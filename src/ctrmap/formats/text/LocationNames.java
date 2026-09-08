package ctrmap.formats.text;

import ctrmap.formats.GameFiles;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import java.io.File;

/**
 * Accessor class for obtaining location names from GAMETEXT files. The entry
 * index comes from the handed game's {@link GameProfile} (the ORAS Special
 * Demo uses its own entry) - use {@link #gametextIndex} anywhere the
 * location-name table must be resolved, so the decision lives in exactly one
 * place.
 *
 * <p>WHOSE GAME. The loader is handed the {@link GameFiles} to read, and the
 * table it fills is read back by {@link #getLocName} until the next load or
 * {@link #unload}. This class used to fetch the open workspace from the
 * application's global itself, both to resolve the entry and to load the
 * table on the first name asked for; so the table could only ever be the
 * application's game's, and a suite that wanted another game's names had to
 * install a workspace in the global first. The application loads it when a
 * workspace opens and after anything rewrites the entry (a zone rename, a
 * text save); a suite hands the loader a fake. There is no loading on demand
 * any more, because on demand meant from the global: a name asked for with
 * nothing loaded is refused in words.
 */
public class LocationNames {

	/**
	 * The loaded table, or null until a game is loaded. Private on purpose:
	 * this was a public field that {@link #getLocName} dereferenced with no
	 * null check, so the first caller before a load got a bare
	 * NullPointerException, and ZoneRepurposeScanner loaded the table by hand
	 * first rather than risk it. A caller with nothing loaded is refused in
	 * words now.
	 */
	private static TextFile textfile;

	private static GameFiles handed(GameFiles game, String what) {
		if (game == null) {
			throw new IllegalArgumentException("LocationNames." + what + " was handed no game. Which GAMETEXT"
					+ " entry holds the location names is a per-game, per-edition fact, and there is nothing"
					+ " to answer without one.");
		}
		return game;
	}

	/**
	 * The GAMETEXT entry holding location names for the handed game AND the
	 * handed EDITION of it.
	 *
	 * <p>This used to read {@code if (isOA() && isOADemo())} and answer the
	 * ORAS demo's entry itself, which put one game's table numbers in a class
	 * every game shares and would have needed a second special case beside it
	 * for the next game that ships a demo. The profile answers both halves now:
	 * which game, and which edition of it.
	 */
	public static int gametextIndex(GameFiles game) {
		return handed(game, "gametextIndex").profile()
				.textIndex(GameProfile.TextIndex.LOCATION_NAMES, game.variant());
	}

	/** (Re)loads the names from the handed game's staged GAMETEXT entry. */
	public static void loadFromGarc(GameFiles game) {
		int index = gametextIndex(handed(game, "loadFromGarc"));
		File extracted = game.staged(ArchiveType.GAMETEXT, index);
		if (extracted == null || !extracted.isFile()) {
			throw new IllegalStateException("Location names: GAMETEXT entry " + index
					+ " could not be read from the game handed in (" + game + ").");
		}
		load(extracted);
	}

	/**
	 * Loads the names from an already-extracted GAMETEXT entry - a game's
	 * staged one, or a copy a suite made for itself without a game.
	 */
	public static void load(File extracted) {
		textfile = new TextFile(extracted);
	}

	/**
	 * Forgets the loaded table, so the next name asked for is refused until a
	 * game is loaded. {@code Workspace.reset} calls this: the table is
	 * derived from a game, and a reset that kept it would hand one game's
	 * names to the next.
	 */
	public static void unload() {
		textfile = null;
	}

	/**
	 * The name of a location, from the table last loaded.
	 *
	 * @throws IllegalStateException when nothing is loaded - in words, rather
	 * than the NullPointerException the bare field used to give. The names are
	 * loaded when a workspace opens; nothing loads them here, because the only
	 * thing that could be loaded from here is the global this class no longer
	 * reads
	 */
	public static String getLocName(int parentLoc) {
		if (textfile == null) {
			throw new IllegalStateException("Location names are not loaded. They are read from a workspace's"
					+ " GAMETEXT when it opens, and nothing has handed this table a game since the last reset.");
		}
		String out = textfile.getLine(parentLoc);
		if (out == null) {
			return "NullPointerException";
		} else {
			return out;
		}
	}
}
