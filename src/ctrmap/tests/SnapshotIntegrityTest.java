package ctrmap.tests;

import ctrmap.ModDeployer;
import ctrmap.Workspace;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * The pristine snapshot must never be completed from a game that has been
 * edited.
 *
 * <p>The snapshot is how the editor knows what the cartridge shipped: donors
 * are cut from it, and deployment diffs against it. It used to be filled in
 * lazily - any archive found missing was copied from the live game on the next
 * load. That is safe exactly once, while the game is still untouched, and
 * silently wrong afterwards. Because the snapshot is built archive by archive,
 * one added to the moddable list later, or one deleted from the folder, was
 * captured from a game that had been edited for weeks and recorded as retail.
 * Six archives in the author's own workspace were contaminated this way, the
 * stamp still declared the snapshot legitimate, and building donors cut from
 * already-painted maps compounded the damage.
 *
 * <p>It must also refuse to go on pretending, without a word, that a backup of
 * one game folder describes another: pointing an existing workspace at a second
 * dump kept the first one's backup forever, so Deploy shipped archives nobody
 * had touched and donors were cut from the wrong game.
 *
 * <p>Runs entirely on scratch files - no game dump needed, because
 * snapshotOriginals only copies by path.
 *
 * Usage: java ctrmap.tests.SnapshotIntegrityTest [src-root]   (default "src")
 */
public class SnapshotIntegrityTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File tmp = Scratch.dir("ctrmap_snapshot_test");
		File gamedir = new File(tmp, "game");
		File wsdir = new File(tmp, "ws");
		gamedir.mkdirs();
		wsdir.mkdirs();

		Workspace.game = Workspace.GameType.ORAS;
		Workspace.GAMEDIR_PATH = gamedir.getAbsolutePath();
		Workspace.WORKSPACE_PATH = wsdir.getAbsolutePath();

		//a stand-in game: one small file per moddable archive, at the real paths
		int made = 0;
		for (Workspace.ArchiveType t : ModDeployer.MODDABLE) {
			String rel = Workspace.getArchivePath(t, Workspace.game);
			if (rel == null) {
				continue;
			}
			File f = new File(gamedir.getAbsolutePath() + rel);
			f.getParentFile().mkdirs();
			Files.write(f.toPath(), ("RETAIL " + rel).getBytes(StandardCharsets.UTF_8));
			made++;
		}
		check(made > 0, "the stand-in game has archives (" + made + ")");

		//--- first capture: nothing stamped yet, so everything is taken ---------
		Workspace.snapshotOriginals();
		check(Workspace.originalSnapshotStamp().isFile(), "a fresh snapshot writes its stamp");
		List<String> missing = Workspace.snapshotMissingArchives();
		check(missing.isEmpty(), "a fresh snapshot captures every archive (missing " + missing + ")");

		//--- now the user edits the game, and one snapshot archive goes astray --
		String rel = Workspace.getArchivePath(ModDeployer.MODDABLE[0], Workspace.game);
		File snapFile = new File(Workspace.originalSnapshotDir().getAbsolutePath() + rel);
		File liveFile = new File(gamedir.getAbsolutePath() + rel);
		check(snapFile.delete(), "removed one archive from the snapshot");
		Files.write(liveFile.toPath(), "EDITED BY THE USER".getBytes(StandardCharsets.UTF_8));

		Workspace.snapshotOriginals();

		//THE POINT: the edited archive must NOT have been adopted as pristine
		if (snapFile.isFile()) {
			String got = new String(Files.readAllBytes(snapFile.toPath()), StandardCharsets.UTF_8);
			System.out.println("  FAIL: the snapshot was completed from the live game; it now holds \""
					+ got + "\"");
			fails++;
		} else {
			System.out.println("  ok: an established snapshot refuses to capture the edited archive");
		}
		missing = Workspace.snapshotMissingArchives();
		check(missing.contains(rel), "the gap is reported (" + missing + ")");

		//--- and the rest of the snapshot is untouched --------------------------
		int intact = 0;
		for (Workspace.ArchiveType t : ModDeployer.MODDABLE) {
			String r = Workspace.getArchivePath(t, Workspace.game);
			if (r == null || r.equals(rel)) {
				continue;
			}
			File f = new File(Workspace.originalSnapshotDir().getAbsolutePath() + r);
			if (f.isFile() && new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)
					.startsWith("RETAIL ")) {
				intact++;
			}
		}
		check(intact > 0, "the archives captured while the game was clean are still retail (" + intact + ")");

		//--- a snapshot retaken from scratch works again ------------------------
		deleteTree(Workspace.originalSnapshotDir());
		Workspace.snapshotOriginals();
		check(Workspace.snapshotMissingArchives().isEmpty(),
				"deleting the snapshot folder allows a whole retake");

		pointedAtAnotherGame(new File(tmp, "game2"),
				args.length > 0 ? new File(args[0]) : new File("src"));

		deleteTree(tmp);
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * Pointing an existing workspace at a DIFFERENT game folder must not keep
	 * the first dump's backup in silence.
	 *
	 * <p>Options &gt; Workspace settings sets GAMEDIR_PATH and cleans the
	 * workspace, and the clean deliberately spares _original_garcs, so the
	 * backup of dump A survives into a session editing dump B. From then on
	 * Deploy diffs B against A - archives the user never touched are reported
	 * as changed and shipped - and the building palette and atmosphere picker
	 * cut donors out of the wrong game. snapshotIsForeign() was written for
	 * exactly this, with a javadoc describing the failure, and nothing called
	 * it: the wizard had its own inline copy of the rule and the settings
	 * dialog had none.
	 *
	 * <p>Reproduced with tzb/ForeignSnapshotRepro: "snapshotIsForeign(): true",
	 * snapshot zonedata still dump A's bytes, 14 of dump B's archives refused,
	 * and Deploy shipped 14 archives after the user had edited nothing.
	 */
	static void pointedAtAnotherGame(File otherGame, File src) throws Exception {
		int made = 0;
		for (Workspace.ArchiveType t : ModDeployer.MODDABLE) {
			String rel = Workspace.getArchivePath(t, Workspace.game);
			if (rel == null) {
				continue;
			}
			File f = new File(otherGame.getAbsolutePath() + rel);
			f.getParentFile().mkdirs();
			Files.write(f.toPath(), ("ANOTHER GAME " + rel).getBytes(StandardCharsets.UTF_8));
			made++;
		}
		check(made > 0, "a second stand-in game to point at (" + made + " archives)");

		check(!Workspace.snapshotIsForeign(), "the backup belongs to the game folder it was taken from");
		Workspace.GAMEDIR_PATH = otherGame.getAbsolutePath();
		check(Workspace.snapshotIsForeign(), "and is foreign once the workspace is pointed elsewhere");

		//THE USER MUST BE TOLD. Everything below this line was already true and
		//the workspace still went on being quietly wrong, because nothing said
		//so and nothing asked.
		List<String> said = ctrmap.Ui.record();
		try {
			Workspace.snapshotOriginals();
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(!said.isEmpty(), "opening a workspace whose backup is another game's says so: " + said);

		String rel = Workspace.getArchivePath(ModDeployer.MODDABLE[0], Workspace.game);
		File snapFile = new File(Workspace.originalSnapshotDir().getAbsolutePath() + rel);
		check(!new String(Files.readAllBytes(snapFile.toPath()), StandardCharsets.UTF_8)
				.startsWith("ANOTHER GAME "), "the first game's backup is not quietly overwritten either");

		//and the repair the user is offered actually works
		Workspace.discardSnapshot();
		Workspace.snapshotOriginals();
		check(!Workspace.snapshotIsForeign(), "discarding the backup lets it be retaken from the new folder");
		check(new String(Files.readAllBytes(snapFile.toPath()), StandardCharsets.UTF_8)
				.startsWith("ANOTHER GAME "), "and the retaken backup holds the new game's archives");

		//The dialog that offers that repair cannot be reached from here, so the
		//one thing a headless suite can hold is that the settings dialog still
		//asks the question before it repoints the workspace. It did not, and
		//that is the whole defect.
		File settings = new File(src, "ctrmap/humaninterface/WorkspaceSettings.java");
		if (!settings.isFile()) {
			System.out.println("  skip: no WorkspaceSettings source at " + settings);
			return;
		}
		String save = new String(Files.readAllBytes(settings.toPath()), StandardCharsets.UTF_8);
		int asks = save.indexOf("snapshotIsForeign");
		int repoints = save.indexOf("cleanAndReload");
		check(asks >= 0 && repoints >= 0 && asks < repoints,
				"the workspace settings dialog asks about the backup before it repoints the game folder");
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}

	static void deleteTree(File f) {
		if (f == null || !f.exists()) {
			return;
		}
		File[] kids = f.listFiles();
		if (kids != null) {
			for (File k : kids) {
				deleteTree(k);
			}
		}
		f.delete();
	}
}
