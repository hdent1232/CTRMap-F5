package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.util.EnumMap;

/**
 * Sessions a suite installs by hand - what setting Workspace's fields one by
 * one used to do, before the open game was an instance.
 *
 * <p>Both install what they build, because the code under test still reads
 * the facade; a suite that wants a session WITHOUT installing it calls
 * {@link WorkspaceSession#open} or the constructor itself.
 */
final class Sessions {

	/** The archives every game needs open - the same eight {@link WorkspaceSession#open} insists on. */
	static final ArchiveType[] REQUIRED = {
		ArchiveType.AREA_DATA, ArchiveType.FIELD_DATA,
		ArchiveType.MAP_MATRIX, ArchiveType.GAMETEXT,
		ArchiveType.ZONE_DATA, ArchiveType.BUILDING_MODELS,
		ArchiveType.NPC_REGISTRIES, ArchiveType.MOVE_MODELS
	};

	private Sessions() {
	}

	/**
	 * A session over a game folder with NO archive open: enough for code that
	 * reads the game, its folders and the workspace's pristine snapshot.
	 * Installed as the current one.
	 */
	static WorkspaceSession bare(File workspaceDir, File gameDir, GameType game) {
		WorkspaceSession s = new WorkspaceSession(workspaceDir, gameDir, game, null);
		Workspace.install(s);
		return s;
	}

	/**
	 * A READ-ONLY session over the pristine dump with the eight required
	 * archives open, installed as the current one. The dump is the corpus
	 * every suite reads, so nothing may pack into it: {@link
	 * Workspace#packWorkspace} leaves a read-only session alone and {@link
	 * WorkspaceSession#packArchives} refuses outright. Built from parts
	 * because the partial dump carries no probe file for {@link
	 * WorkspaceSession#open} to identify the game by.
	 */
	static WorkspaceSession overDump(File workspaceDir, File dump) {
		EnumMap<ArchiveType, GARC> open = new EnumMap<>(ArchiveType.class);
		for (ArchiveType t : REQUIRED) {
			open.put(t, new GARC(new File(dump.getAbsolutePath() + Workspace.getArchivePath(t, GameType.ORAS))));
		}
		WorkspaceSession s = new WorkspaceSession(workspaceDir, dump, GameType.ORAS, open).readOnly();
		Workspace.install(s);
		return s;
	}
}
