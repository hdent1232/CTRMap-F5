package ctrmap.formats.text;

import ctrmap.Workspace;
import ctrmap.gamedef.GameProfile;
import ctrmap.gamedef.OrasProfile;
import java.io.File;

/**
 * Accessor class for obtaining location names from GAMETEXT files. The entry
 * index comes from the active {@link GameProfile} (the ORAS Special Demo uses
 * its own entry) - use {@link #gametextIndex()} anywhere the location-name
 * table must be resolved, so the decision lives in exactly one place.
 */
public class LocationNames {

	/**
	 * The loaded table, or null until a name is asked for. Private on purpose:
	 * this was a public field that {@link #getLocName} dereferenced with no
	 * null check, so the first caller before a load got a bare
	 * NullPointerException, and ZoneRepurposeScanner loaded the table by hand
	 * first rather than risk it. Loading is the accessor's own job now, and a
	 * caller with nothing to load from is refused in words.
	 */
	private static TextFile textfile;

	/** The GAMETEXT entry holding location names for the loaded game. */
	public static int gametextIndex() {
		if (Workspace.isOA() && Workspace.isOADemo()) {
			return OrasProfile.DEMO_LOCATION_NAMES;
		}
		return Workspace.profile().textIndex(GameProfile.TextIndex.LOCATION_NAMES);
	}

	/** (Re)loads the names from the open workspace's GAMETEXT. */
	public static void loadFromGarc() {
		File extracted = Workspace.getWorkspaceFile(Workspace.ArchiveType.GAMETEXT, gametextIndex());
		if (extracted == null) {
			throw new IllegalStateException("Location names: GAMETEXT entry " + gametextIndex()
					+ " could not be read from the open workspace.");
		}
		load(extracted);
	}

	/**
	 * Loads the names from an already-extracted GAMETEXT entry - the
	 * workspace's, or a copy a suite made for itself without a workspace.
	 */
	public static void load(File extracted) {
		textfile = new TextFile(extracted);
	}

	/**
	 * Forgets the loaded table, so the next name asked for is read from the
	 * workspace open THEN. {@link Workspace#reset} calls this: the table is
	 * derived from the workspace, and a reset that kept it would hand one
	 * game's names to the next.
	 */
	public static void unload() {
		textfile = null;
	}

	/**
	 * The name of a location, read from the open workspace on first use.
	 *
	 * @throws IllegalStateException when nothing is loaded and there is no
	 * workspace to load from - in words, rather than the NullPointerException
	 * the bare field used to give
	 */
	public static String getLocName(int parentLoc) {
		if (textfile == null) {
			if (!Workspace.valid) {
				throw new IllegalStateException("Location names are not loaded, and there is no open"
						+ " workspace to load them from.");
			}
			loadFromGarc();
		}
		String out = textfile.getLine(parentLoc);
		if (out == null) {
			return "NullPointerException";
		} else {
			return out;
		}
	}
}
