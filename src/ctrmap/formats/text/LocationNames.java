package ctrmap.formats.text;

import ctrmap.formats.GameCache;
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
 * <p>WHOSE GAME. Every name is asked of a handed {@link GameFiles}, and the
 * table is that game's, kept in its own store (F218). This class used to fetch
 * the open workspace from the application's global itself, so the table could
 * only ever be the application's game's; then it held one static table that
 * the application loaded when a workspace opened, reloaded after anything
 * rewrote the entry and forgot on reset - a lifetime of its own, kept by three
 * callers remembering to. Now a different game is a different store, a pack
 * empties it, and a writer of the entry says so through {@link #loadFromGarc}.
 */
public class LocationNames {

	/** The handed game's table, read from its staged GAMETEXT entry and kept in its store. */
	private static final GameCache.Key<TextFile> TABLE = new GameCache.Key<>("location names");

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

	/**
	 * Reads the names from the handed game's staged GAMETEXT entry NOW, and keeps them for that
	 * game: asked when a workspace opens, so a game whose text lacks the entry is refused there,
	 * and by anything that has just rewritten the entry (a zone rename, a text save).
	 *
	 * @throws IllegalStateException when the entry cannot be read; what the game kept before is
	 * left as it was
	 */
	public static void loadFromGarc(GameFiles game) {
		TextFile read = read(handed(game, "loadFromGarc"));
		game.cache().forget(TABLE);
		game.cache().get(TABLE, () -> read);
	}

	private static TextFile read(GameFiles game) {
		int index = gametextIndex(game);
		File extracted = game.staged(ArchiveType.GAMETEXT, index);
		if (extracted == null || !extracted.isFile()) {
			throw new IllegalStateException("Location names: GAMETEXT entry " + index
					+ " could not be read from the game handed in (" + game + ").");
		}
		return new TextFile(extracted);
	}

	/**
	 * Keeps for the handed game the names in an already-extracted GAMETEXT entry - a copy a
	 * suite made for itself, standing in for the game's own.
	 */
	public static void load(GameFiles game, File extracted) {
		TextFile read = new TextFile(extracted);
		handed(game, "load").cache().forget(TABLE);
		game.cache().get(TABLE, () -> read);
	}

	/**
	 * The name of a location in the handed game: its table, read the first time a name is asked
	 * for and kept until a pack or a rewrite of the entry.
	 *
	 * @throws IllegalStateException when the game's location names cannot be read - in words,
	 * never a blank or another game's name
	 */
	public static String getLocName(GameFiles game, int parentLoc) {
		GameFiles g = handed(game, "getLocName");
		String out = g.cache().get(TABLE, () -> read(g)).getLine(parentLoc);
		if (out == null) {
			return "NullPointerException";
		} else {
			return out;
		}
	}
}
