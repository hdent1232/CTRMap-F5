package ctrmap.tests;

import ctrmap.Workspace;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * A throwaway copy of the game that a suite is allowed to pack into.
 *
 * <p>Guards about forking and packing have to WRITE archives, and the only
 * archives with the shapes the code was written for are the ones in the dump.
 * Packing into the dump would destroy the corpus every other suite reads from,
 * so this copies the archives a workspace opens into scratch space, points
 * {@link Workspace} at the copies, and loads them exactly the way the
 * application does.
 */
final class ScratchGame {

	/**
	 * The archives {@link Workspace#loadArchives} opens. All of them, because a
	 * pack reloads all of them - leave one out and the reload dereferences null.
	 */
	private static final Workspace.ArchiveType[] NEEDED = {
		Workspace.ArchiveType.AREA_DATA, Workspace.ArchiveType.FIELD_DATA,
		Workspace.ArchiveType.MAP_MATRIX, Workspace.ArchiveType.GAMETEXT,
		Workspace.ArchiveType.ZONE_DATA, Workspace.ArchiveType.BUILDING_MODELS,
		Workspace.ArchiveType.NPC_REGISTRIES, Workspace.ArchiveType.MOVE_MODELS
	};

	private ScratchGame() {
	}

	/** Copies the dump into scratch space and loads it as the live workspace. */
	static File open(File dump) throws IOException {
		File root = Scratch.dir("ctrmap_scratch_game");
		File game = new File(root, "game");
		File ws = new File(root, "ws");
		Workspace.game = Workspace.GameType.ORAS;
		Workspace.GAMEDIR_PATH = game.getAbsolutePath();
		Workspace.WORKSPACE_PATH = ws.getAbsolutePath();
		for (String d : Workspace.WORKSPACE_SUBDIRS) {
			new File(ws, d).mkdirs();
		}
		Workspace.temp = new File(ws, "temp");
		Workspace.persist_config = new File(ws, "ctrmap_persist.txt");
		Workspace.persist_paths.clear();
		for (Workspace.ArchiveType t : NEEDED) {
			String rel = Workspace.getArchivePath(t, Workspace.game);
			File src = new File(dump.getAbsolutePath() + rel);
			File dst = new File(game.getAbsolutePath() + rel);
			dst.getParentFile().mkdirs();
			Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		Workspace.areadata = archive(game, Workspace.ArchiveType.AREA_DATA);
		Workspace.fielddata = archive(game, Workspace.ArchiveType.FIELD_DATA);
		Workspace.mapmatrix = archive(game, Workspace.ArchiveType.MAP_MATRIX);
		Workspace.gametext = archive(game, Workspace.ArchiveType.GAMETEXT);
		Workspace.zonedata = archive(game, Workspace.ArchiveType.ZONE_DATA);
		Workspace.buildingmodels = archive(game, Workspace.ArchiveType.BUILDING_MODELS);
		Workspace.npcregistries = archive(game, Workspace.ArchiveType.NPC_REGISTRIES);
		Workspace.movemodels = archive(game, Workspace.ArchiveType.MOVE_MODELS);
		Workspace.valid = true;
		Workspace.loadArchives();
		return root;
	}

	private static File archive(File game, Workspace.ArchiveType t) {
		return new File(game.getAbsolutePath() + Workspace.getArchivePath(t, Workspace.game));
	}
}
