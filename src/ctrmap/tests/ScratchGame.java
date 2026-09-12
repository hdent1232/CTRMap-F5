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
	/**
	 * The archives a scratch game needs to be a game.
	 *
	 * <p>STORYTEXT joined the list when an append started giving every zone it
	 * creates its own dialogue file. It is 1.6 MB, which is real but small beside
	 * FieldData, and without it every section that appends refuses - correctly,
	 * because a game folder with no STORYTEXT genuinely cannot make a zone
	 * independent. A suite that worked around that refusal would be measuring a
	 * game nobody has.
	 */
	private static final ArchiveType[] NEEDED = {
		ArchiveType.AREA_DATA, ArchiveType.FIELD_DATA,
		ArchiveType.MAP_MATRIX, ArchiveType.GAMETEXT,
		ArchiveType.ZONE_DATA, ArchiveType.BUILDING_MODELS,
		ArchiveType.NPC_REGISTRIES, ArchiveType.MOVE_MODELS,
		ArchiveType.STORYTEXT
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
		//AN ARCHIVE THE DUMP DOES NOT HAVE IS SKIPPED, AND SAID. Suites are pointed
		//at two different things: the live game folder, which is a whole game, and
		//RomFS_original_garcs, which is only the archives the restore flow needs and
		//has no STORYTEXT at all. Copying unconditionally turned the second into a
		//NoSuchFileException out of this method the moment STORYTEXT joined the list,
		//and twenty-two suites died before their first line. Skipping is right - a
		//scratch game built from a partial dump is genuinely partial, and the code
		//that needs the missing archive refuses on its own - but skipping SILENTLY
		//would leave a suite failing later with no hint of why, so it is printed.
		java.util.List<String> absent = new java.util.ArrayList<>();
		for (ArchiveType t : NEEDED) {
			String rel = Workspace.getArchivePath(t, GameType.ORAS);
			File src = new File(dump.getAbsolutePath() + rel);
			if (!src.isFile()) {
				absent.add(t + " (" + rel + ")");
				continue;
			}
			File dst = new File(game.getAbsolutePath() + rel);
			dst.getParentFile().mkdirs();
			Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		if (!absent.isEmpty()) {
			System.out.println("    (this dump has no " + absent + " - the scratch game goes"
				+ " without, and anything needing it will refuse)");
		}
		WorkspaceSession session;
		try {
			session = WorkspaceSession.open(ws, game);
		} catch (WorkspaceSession.OpenFailed ex) {
			throw new IOException("the scratch copy of the game did not open: " + ex.problems(), ex);
		}
		session.prepareDirectories();
		Workspace.install(session);
		//what the application does one line after validate returns: the window
		//is handed the game it just opened. Said here rather than inside
		//install, so the facade knows nothing about the window and a suite
		//that wants the window told says so.
		ctrmap.CtrmapMainframe.onWorkspaceOpened(session);
		//the app takes the pristine backup on every load; the pack guards read it
		Workspace.snapshotOriginals();
		//and loads the two tables derived from the game, handed the session it
		//opened - exactly what Workspace.validate does. Neither table loads
		//itself from the global any more, so a stand-in for "open a workspace"
		//that skipped this would leave every zone dropdown refusing names
		ctrmap.formats.text.LocationNames.loadFromGarc(session);
		ctrmap.formats.pokedata.PokeData.load(session);
		return root;
	}
}
