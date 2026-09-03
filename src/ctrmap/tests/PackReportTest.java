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

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
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
