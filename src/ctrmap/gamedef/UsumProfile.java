package ctrmap.gamedef;

import ctrmap.Workspace.ArchiveType;
import ctrmap.Workspace.GameType;

/**
 * Pokemon Ultra Sun / Ultra Moon (Gen 7) - PLACEHOLDER, same status as
 * {@link SmProfile}: Gen 7 replaced the model/animation formats, and nothing
 * is measured yet. Fill from an USUM dump.
 */
public class UsumProfile extends GameProfile {

	@Override
	public GameType type() {
		return GameType.USUM;
	}

	@Override
	public String displayName() {
		return "Ultra Sun / Ultra Moon";
	}

	@Override
	public String archivePath(ArchiveType t) {
		return null; // TODO measure from an USUM dump
	}

	@Override
	public int textIndex(TextIndex t) {
		return -1; // TODO measure from an USUM dump
	}

	@Override
	public boolean supports(Feature f) {
		return false;
	}

	@Override
	public String detectFile() {
		return null; // TODO: pick a probe file once a dump is available
	}
}
