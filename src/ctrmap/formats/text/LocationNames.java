package ctrmap.formats.text;

import ctrmap.formats.GameCache;
import ctrmap.formats.GameFiles;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

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
 *
 * <p>ONE CODEC (F089). The names were read by a second GameText decoder, a
 * read-only port kept for this class alone, which named the text variables
 * differently from {@link GFMessageFile} (the text editor's), appended the
 * digits "9792" where a female sign belonged, and handed back null for an
 * empty file. They are read by GFMessageFile now, with the private-use glyphs
 * mapped for display, and an id past the table is said in words.
 */
public class LocationNames {

	/** The handed game's table, read from its staged GAMETEXT entry and kept in its store. */
	private static final GameCache.Key<List<String>> TABLE = new GameCache.Key<>("location names");

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
		List<String> read = read(handed(game, "loadFromGarc"));
		game.cache().forget(TABLE);
		game.cache().get(TABLE, () -> read);
	}

	private static List<String> read(GameFiles game) {
		int index = gametextIndex(game);
		File extracted = game.staged(ArchiveType.GAMETEXT, index);
		if (extracted == null || !extracted.isFile()) {
			throw new IllegalStateException("Location names: GAMETEXT entry " + index
					+ " could not be read from the game handed in (" + game + ").");
		}
		return parse(extracted);
	}

	/** One GameText file's lines, private-use glyphs mapped for display; a file that will not read is said. */
	private static List<String> parse(File extracted) {
		try {
			return new GFMessageFile(Files.readAllBytes(extracted.toPath()), true).getLines();
		} catch (IOException | RuntimeException cannot) {
			throw new IllegalStateException("Location names: " + extracted + " could not be read as GameText: "
					+ ctrmap.util.Bytes.reason(cannot), cannot);
		}
	}

	/**
	 * Keeps for the handed game the names in an already-extracted GAMETEXT entry - a copy a
	 * suite made for itself, standing in for the game's own.
	 */
	@ctrmap.util.ForSuites("SEAM: a suite hands the names a GAMETEXT copy of its own holds, standing in for the game's")
	public static void load(GameFiles game, File extracted) {
		List<String> read = parse(extracted);
		handed(game, "load").cache().forget(TABLE);
		game.cache().get(TABLE, () -> read);
	}

	/**
	 * The name of a location in the handed game: its table, read the first time a name is asked
	 * for and kept until a pack or a rewrite of the entry.
	 *
	 * An id the table has no line for is named as one - "unknown location #n" - where this used to
	 * answer the words "NullPointerException", an exception's name standing in for a place's.
	 *
	 * @throws IllegalStateException when the game's location names cannot be read - in words,
	 * never a blank or another game's name
	 */
	public static String getLocName(GameFiles game, int parentLoc) {
		GameFiles g = handed(game, "getLocName");
		List<String> lines = g.cache().get(TABLE, () -> read(g));
		if (parentLoc < 0 || parentLoc >= lines.size()) {
			return unknown(parentLoc);
		}
		return lines.get(parentLoc);
	}

	/**
	 * What the user sees a zone called, wherever a zone is listed or picked: "Location - N". THE ONE LABEL
	 * (census L.36): the Zone Loader wrote it three times, and the warp-target, matrix zone-ref and encounter
	 * pickers wrote it the other way round, so one zone had two names in two dropdowns.
	 */
	public static String zoneLabel(GameFiles game, int parentLoc, int zoneIndex) {
		return getLocName(game, parentLoc) + " - " + zoneIndex;
	}

	/** What a location id the table has no line for is called. */
	public static String unknown(int parentLoc) {
		return "unknown location #" + parentLoc;
	}
}
