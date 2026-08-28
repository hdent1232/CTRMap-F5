package ctrmap.formats.text;

import ctrmap.Workspace;
import ctrmap.gamedef.GameProfile;
import ctrmap.gamedef.OrasProfile;

/**
 * Accessor class for obtaining location names from GAMETEXT files. The entry
 * index comes from the active {@link GameProfile} (the ORAS Special Demo uses
 * its own entry) - use {@link #gametextIndex()} anywhere the location-name
 * table must be resolved, so the decision lives in exactly one place.
 */
public class LocationNames {

	public static TextFile textfile;

	/** The GAMETEXT entry holding location names for the loaded game. */
	public static int gametextIndex() {
		if (Workspace.isOA() && Workspace.isOADemo()) {
			return OrasProfile.DEMO_LOCATION_NAMES;
		}
		return Workspace.profile().textIndex(GameProfile.TextIndex.LOCATION_NAMES);
	}

	public static void loadFromGarc() {
		textfile = new TextFile(Workspace.getWorkspaceFile(Workspace.ArchiveType.GAMETEXT, gametextIndex()));
	}

	public static String getLocName(int parentLoc) {
		String out = textfile.getLine(parentLoc);
		if (out == null) {
			return "NullPointerException";
		} else {
			return out;
		}
	}
}
