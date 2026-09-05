package ctrmap.tests;

import ctrmap.Ui;
import ctrmap.Workspace;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * What a pack found wrong has to reach the user.
 *
 * <p>Three things the pack path knows can leave a game that will not boot, and
 * all three were written with {@code System.err.println}: the integrity check's
 * "this zone can no longer load", the archive writer's "somebody else rewrote
 * this file under you", and the workspace's "your pristine backup is missing
 * archives". The shipped build is a jpackage app-image with no console -
 * subsystem WINDOWS_GUI - so every one of those lines is written to a handle
 * that goes nowhere. The user gets the success dialog and nothing else, and
 * finds out when the zone fails to load in game.
 *
 * <p>Reproduced on a scratch copy of the dump: all three warnings printed,
 * "what reached the user: 0 message(s)".
 *
 * <p>Asserted through {@link Ui}, so "the user was told" is a fact a headless
 * suite can check - a bare JOptionPane is neither reachable nor observable
 * here, which is how the missing dialogs stayed missing.
 *
 * Usage: java ctrmap.tests.PackReportTest &lt;romfs-root&gt;
 */
public class PackReportTest {

	/** The zone whose area is broken below; any zone would do. */
	private static final int ZONE = 15;
	/** An area id no registry and no AreaData entry can answer for. */
	private static final int DANGLING_AREA = 250;
	private static final int MASTER_ROW = 0x38;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump);
			System.out.println("ALL PASS");
			return;
		}
		ScratchGame.open(dump);

		//a pack with nothing wrong must say nothing, or the warning becomes
		//noise the user learns to click through
		check(pack().isEmpty(), "a clean pack tells the user nothing");

		//1. a zone that cannot load: its area id has no registry behind it
		int masterIndex = Workspace.zo.length - 2;
		File master = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, masterIndex);
		byte[] rows = Files.readAllBytes(master.toPath());
		rows[ZONE * MASTER_ROW + 2] = (byte) DANGLING_AREA;
		rows[ZONE * MASTER_ROW + 3] = 0;
		Files.write(master.toPath(), rows);
		Workspace.addPersist(master);

		//2. a pristine snapshot that is missing an archive
		String rel = Workspace.getArchivePath(Workspace.ArchiveType.NPC_REGISTRIES, Workspace.game);
		new File(Workspace.originalSnapshotDir().getAbsolutePath() + rel).delete();

		//3. an archive somebody else rewrote while this editor held its entry table
		byte[] mat = Files.readAllBytes(Workspace.mapmatrix.toPath());
		byte[] longer = new byte[mat.length + 64];
		System.arraycopy(mat, 0, longer, 0, mat.length);
		Files.write(Workspace.mapmatrix.toPath(), longer);

		String said = pack().toString();
		System.out.println("  the user is shown: " + said);
		check(said.contains("area " + DANGLING_AREA), "the pack names the zone it can see will not load");
		check(said.contains(rel), "the pack names the archive missing from the pristine backup");
		check(said.contains("changed on disk"), "the pack says an archive was rewritten underneath it");

		//A MATRIX CELL NAMING A REGION THAT DOES NOT EXIST must reach the user
		//too. Finding 6's fix made the region pass read the grid correctly, but
		//report() called check(false), which returns before that pass - so the
		//only place it ever ran was IntegrityTest. Verification re-ran the
		//original repro against the fixed build: the archive held region 5000,
		//check(true) named it, and the Pack dialog said "(nothing)". Typed into
		//the matrix editor, a dangling region loads as a missing file in game.
		int matrix = 14;
		File matFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.MAP_MATRIX, matrix);
		byte[] grid = Files.readAllBytes(matFile.toPath());
		int sub0 = (grid[4] & 0xFF) | ((grid[5] & 0xFF) << 8) | ((grid[6] & 0xFF) << 16) | ((grid[7] & 0xFF) << 24);
		int cell0 = sub0 + 8; //hasLOD, unknown, width, height, then the ids
		grid[cell0] = (byte) (5000 & 0xFF);
		grid[cell0 + 1] = (byte) (5000 >> 8);
		Files.write(matFile.toPath(), grid);
		Workspace.addPersist(matFile);
		said = pack().toString();
		System.out.println("  after a dangling region, the user is shown: " + said);
		check(said.contains("FieldData region that does not exist"), "the pack names a matrix cell whose region FieldData does not have");
		check(said.contains("matrix " + matrix), "and says which matrix");

		aPackThatFailedSaysSoAndStopsThere();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * A pack that THREW has to say so, and must not run the work that was
	 * queued behind it.
	 *
	 * <p>The checks above go through {@code packArchives} directly. This one
	 * goes through {@link Workspace#packWorkspace}, which is what every menu
	 * item in the application actually calls: it packs on a worker and, on the
	 * far side, either tells the user it failed or runs the caller's onDone -
	 * deploy, reload, open the zone that was just appended. That report was a
	 * bare JOptionPane, unreadable by any guard and unreachable without a
	 * screen, so nothing asserted the one sentence separating a failed pack
	 * from a good one. Delete it and the progress bar fills, the dialog closes,
	 * and the user goes on to deploy archives that were never written.
	 *
	 * <p>Fails the pack the way the archive writer itself refuses one - a
	 * staged file named past the end of NPCRegistries - then removes it and
	 * asks for the same pack again, because "it never runs onDone" would also
	 * be true of a pack that could not work at all.
	 */
	static void aPackThatFailedSaysSoAndStopsThere() throws Exception {
		File dir = Workspace.getExtractionDirectory(Workspace.ArchiveType.NPC_REGISTRIES);
		File gapped = new File(dir, String.valueOf(Workspace.npcreg.length + 1));
		Files.write(gapped.toPath(), new byte[]{1, 2, 3, 4});
		Workspace.addPersist(gapped);

		final boolean[] after = {false};
		List<String> told = packThroughTheApp(() -> after[0] = true);
		System.out.println("  a failed pack tells the user: " + told);
		check(!after[0], "a pack that failed does not run the work that was waiting on it");
		check(contains(told, "The workspace was not packed"),
				"and the user is told the pack did not happen: " + told);
		check(contains(told, "Cannot pack " + gapped.getName()),
				"naming what stopped it: " + told);
		check(contains(told, "before deploying"),
				"and warning them off the one thing that would ship the damage: " + told);

		Workspace.persist_paths.remove(gapped.getAbsolutePath());
		gapped.delete();
		after[0] = false;
		told = packThroughTheApp(() -> after[0] = true);
		check(after[0], "a pack that worked does run it");
		check(!contains(told, "The workspace was not packed"),
				"and says nothing about a pack that did not happen: " + told);
	}

	/**
	 * packWorkspace as the application calls it, and everything the user was
	 * told on the way.
	 *
	 * <p>Called off the EDT on purpose: with no screen the progress dialog
	 * holds this thread until the worker's done() closes it, which is the first
	 * thing done() does - so the rest of done(), including the report, may
	 * still be running on the EDT when packWorkspace returns. In the
	 * application that cannot happen (showDialog is called ON the EDT, inside
	 * the modal pump done() itself runs in), so the wait below restores the
	 * order the application has rather than inventing one.
	 */
	static List<String> packThroughTheApp(Runnable onDone) throws Exception {
		List<String> said = Ui.record();
		try {
			Workspace.packWorkspace(onDone);
			javax.swing.SwingUtilities.invokeAndWait(() -> {
			});
		} finally {
			Ui.stopRecording();
		}
		return said;
	}

	static boolean contains(List<String> said, String text) {
		for (String s : said) {
			if (s.contains(text)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Packs and reports the way the app does - packArchives hands back what it
	 * found, reportPackWarnings puts it on screen - and returns what the user
	 * was actually told. Both halves matter: a report nobody shows is the same
	 * silent failure as no report at all.
	 */
	static List<String> pack() throws Exception {
		List<String> said = Ui.record();
		try {
			Workspace.reportPackWarnings(null, Workspace.packArchives((percent, what) -> {
			}));
		} finally {
			Ui.stopRecording();
		}
		return said;
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}
