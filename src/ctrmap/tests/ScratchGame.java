package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
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
 * so this copies the archives a workspace opens into scratch space, opens a
 * {@link WorkspaceSession} on the copies exactly the way the application does,
 * and installs it as the live one.
 *
 * <p>This used to set sixteen statics of {@link Workspace} by hand - the game,
 * both paths, eight archive files, the scratch and persist paths, the valid
 * flag - and call the loader, which is what opening a game looked like when
 * the state was global. Now it is one call, and the suite that wants a
 * session without installing it can have one: {@link WorkspaceSession#open}.
 */
final class ScratchGame {

	/**
	 * The archives {@link WorkspaceSession#open} requires. All of them, because
	 * a pack reloads all of them - leave one out and the reload dereferences null.
	 */
	private static final ArchiveType[] NEEDED = {
		ArchiveType.AREA_DATA, ArchiveType.FIELD_DATA,
		ArchiveType.MAP_MATRIX, ArchiveType.GAMETEXT,
		ArchiveType.ZONE_DATA, ArchiveType.BUILDING_MODELS,
		ArchiveType.NPC_REGISTRIES, ArchiveType.MOVE_MODELS
	};

	private ScratchGame() {
	}

	/** Copies the dump into scratch space and loads it as the live workspace. */
	static File open(File dump) throws IOException {
		File root = Scratch.dir("ctrmap_scratch_game");
		File game = new File(root, "game");
		File ws = new File(root, "ws");
		//Whatever the suite pointed Workspace at before this, forget it. Opening
		//a throwaway game means THIS game and nothing of the last one.
		Workspace.reset();
		Workspace.GAMEDIR_PATH = game.getAbsolutePath();
		Workspace.WORKSPACE_PATH = ws.getAbsolutePath();
		ws.mkdirs();
		//detection looks for the game's own sound archive; an empty stand-in is enough
		File sound = new File(game, ctrmap.gamedef.GameProfile.of(GameType.ORAS)
				.archivePath(ArchiveType.SOUND_BCSAR));
		sound.getParentFile().mkdirs();
		Files.write(sound.toPath(), new byte[0]);
		for (ArchiveType t : NEEDED) {
			String rel = Workspace.getArchivePath(t, GameType.ORAS);
			File src = new File(dump.getAbsolutePath() + rel);
			File dst = new File(game.getAbsolutePath() + rel);
			dst.getParentFile().mkdirs();
			Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		WorkspaceSession session;
		try {
			session = WorkspaceSession.open(ws, game);
		} catch (WorkspaceSession.OpenFailed ex) {
			throw new IOException("the scratch copy of the game did not open: " + ex.problems(), ex);
		}
		session.prepareDirectories();
		Workspace.install(session);
		//the app takes the pristine backup on every load; the pack guards read it
		Workspace.snapshotOriginals();
		return root;
	}
}
