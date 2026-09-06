package ctrmap.formats.pokedata;

import ctrmap.Workspace;
import ctrmap.formats.text.GFMessageFile;
import ctrmap.gamedef.GameProfile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * An item's name and its bag description - the other half of a tier-1 item
 * edit.
 *
 * <p>They are two lines of two GameText files, indexed by item id, and they go
 * through the workspace and the normal pack like every other text edit. That
 * matters most for a NEW item: the four free record slots ship with the name
 * "???" and a placeholder description, and an item whose record is perfect and
 * whose name is "???" is not an item anyone can use.
 *
 * <p>Which files those are is a PER-GAME fact and comes from the profile, which
 * answers -1 for a game where nobody has verified it. -1 means absent, and this
 * class treats it that way rather than falling back to a number that happened to
 * work for another game.
 */
public final class ItemText {

	/** Which of the two lists a line comes from. */
	public enum Which {
		NAMES,
		DESCRIPTIONS
	}

	private ItemText() {
	}

	private static int textIndex(Which w) {
		GameProfile p = Workspace.profile();
		return p.textIndex(w == Which.NAMES
				? GameProfile.TextIndex.ITEM_NAMES
				: GameProfile.TextIndex.ITEM_DESCRIPTIONS);
	}

	/**
	 * The workspace's copy of the text file, or null when this game has no
	 * verified index for it (or no workspace is loaded).
	 */
	private static File fileFor(Which w) {
		if (!Workspace.valid) {
			return null;
		}
		int idx = textIndex(w);
		if (idx < 0) {
			return null;
		}
		return Workspace.getWorkspaceFile(Workspace.ArchiveType.GAMETEXT, idx);
	}

	/** Every line of the list, or an empty list when it cannot be read. */
	public static List<String> read(Which w) {
		File f = fileFor(w);
		if (f == null || !f.isFile()) {
			return new ArrayList<>();
		}
		try {
			return new GFMessageFile(Files.readAllBytes(f.toPath())).getLines();
		} catch (IOException | RuntimeException ex) {
			return new ArrayList<>();
		}
	}

	/** One line, or null when the list cannot be read or does not go that far. */
	public static String line(Which w, int id) {
		List<String> l = read(w);
		return id >= 0 && id < l.size() ? l.get(id) : null;
	}

	/**
	 * Replaces one line and stores the file back into the workspace, ready for
	 * the next pack.
	 *
	 * <p>Rewrites the file through its own {@link GFMessageFile}, which keeps
	 * the per-line extra values the games carry alongside the text; building a
	 * fresh file from the strings alone would drop them.
	 *
	 * <p>Refuses to LENGTHEN the list. The three item tables - records, names,
	 * descriptions - are 776 long and indexed by the same id, and a text file
	 * with 777 lines would put the editor's idea of an item out of step with the
	 * game's without anything failing.
	 */
	public static void setLine(Which w, int id, String text) throws IOException {
		File f = fileFor(w);
		if (f == null) {
			throw new IOException("this game has no verified " + (w == Which.NAMES ? "item name" : "item description")
					+ " text file, so the editor will not guess at one");
		}
		GFMessageFile msg = new GFMessageFile(Files.readAllBytes(f.toPath()));
		List<String> lines = msg.getLines();
		if (id < 0 || id >= lines.size()) {
			throw new IOException("item " + id + " is outside the " + lines.size()
					+ " lines this text file holds, and this editor does not add lines to it -"
					+ " the record table, the names and the descriptions are all indexed by the"
					+ " same id and must stay the same length");
		}
		lines.set(id, text == null ? "" : text);
		msg.setLines(lines);
		Files.write(f.toPath(), msg.write());
		Workspace.addPersist(f);
	}

	/** How many lines each list holds, for a caller checking they agree with the records. */
	public static int count(Which w) {
		return read(w).size();
	}
}
