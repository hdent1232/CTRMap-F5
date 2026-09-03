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
 * <p>Runs entirely on scratch files - no game dump needed, because
 * snapshotOriginals only copies by path.
 *
 * Usage: java ctrmap.tests.SnapshotIntegrityTest
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

		deleteTree(tmp);
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
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
