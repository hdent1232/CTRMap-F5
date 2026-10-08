package ctrmap.tests;

import ctrmap.LoadedZone;
import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.WorkspaceUnit;
import ctrmap.formats.containers.AD;
import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import ctrmap.humaninterface.ExtrasPanel;
import ctrmap.resources.ResourceAccess;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JOptionPane;

/**
 * The Extras mass edits happen whole or not at all, can be taken back, and are offered only on a
 * game they were made for (F100).
 *
 * <p>WHY THIS SUITE EXISTS. "Inject dummy camera collisions" wrote X/Y Lumiose collision into
 * every area of whatever game was open, one area after another, and an area that could not be
 * written threw out of the loop with the earlier ones already changed and nothing said. Each of
 * the three buttons warned "It cannot be undone from within CTRMap" - in a program whose rule is
 * that every edit can be.
 *
 * <p>ORDER: needs no dump and no display; the panel is the real one, over a made game.
 *
 * Usage: java ctrmap.tests.MassEditTest
 */
public class MassEditTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		Workspace.reset();
		try {
			aKeptUnitCanBeTakenBack();
			theCollisionMassEditIsWholeAndTakenBack();
			itIsOfferedOnlyWhereItWasMade();
		} finally {
			ctrmap.Ui.stopRecording();
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	static void aKeptUnitCanBeTakenBack() throws Exception {
		System.out.println("--- a kept unit is taken back whole, or not at all once its files have moved on");
		WorkspaceSession ws = game("kept", GameType.ORAS);
		File dir = Scratch.dir("ctrmap_kept");
		File a = new File(dir, "a");
		File b = new File(dir, "b");
		Files.write(a.toPath(), "a before".getBytes(StandardCharsets.US_ASCII));
		final String[] memory = {"before"};
		WorkspaceUnit.Kept kept;
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Test edit", ws)) {
			WorkspaceUnit.write(a, "a after".getBytes(StandardCharsets.US_ASCII));
			WorkspaceUnit.write(b, "b made".getBytes(StandardCharsets.US_ASCII));
			memory[0] = "after";
			WorkspaceUnit.onRollback(() -> memory[0] = "before");
			kept = unit.commitKeeping();
		}
		check(text(a).equals("a after") && b.isFile() && memory[0].equals("after"), "control: the unit's writes are kept");
		kept.revert();
		check(text(a).equals("a before") && !b.exists() && memory[0].equals("before"),
				"taken back: the file it changed is as it was, the one it made is gone, and its memory with them");

		try (WorkspaceUnit unit = WorkspaceUnit.begin("Test edit", ws)) {
			WorkspaceUnit.write(a, "a after".getBytes(StandardCharsets.US_ASCII));
			WorkspaceUnit.write(b, "b made".getBytes(StandardCharsets.US_ASCII));
			kept = unit.commitKeeping();
		}
		Files.write(a.toPath(), "a edited since".getBytes(StandardCharsets.US_ASCII));
		String said = null;
		try {
			kept.revert();
		} catch (IllegalStateException refused) {
			said = refused.getMessage();
		}
		check(said != null && said.contains("changed since") && said.contains("[a]") && text(b).equals("b made"),
				"a file edited since refuses the whole undo, naming it, and puts nothing back: " + said);
	}

	static String text(File f) throws Exception {
		return new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII);
	}

	/** An area container: eight subfiles, the eighth (index 7) the camera collision. */
	static byte[] area(int n) {
		byte[][] subs = new byte[8][];
		for (int i = 0; i < 8; i++) {
			subs[i] = ("area " + n + " subfile " + i).getBytes(StandardCharsets.US_ASCII);
		}
		return HandedGameTest.container("AD", subs);
	}

	/**
	 * How many areas the made game has: Omega Ruby / Alpha Sapphire's, with the per-area table
	 * where that game keeps it (F235) - not three areas and the table "last", the layout F235
	 * found the editor assuming and the game not keeping once an area is forked.
	 */
	static final int AREAS = ctrmap.gamedef.GameProfile.of(GameType.ORAS).areaTableIndex();

	/** A made game of {@code type}: {@link #AREAS} areas, then the per-area table, 44 bytes a row. */
	static WorkspaceSession game(String name, GameType type) throws Exception {
		File root = Scratch.dir("ctrmap_massedit_" + name);
		File archives = new File(root, "archives");
		archives.mkdirs();
		File ad = new File(archives, "areadata");
		byte[][] entries = new byte[AREAS + 1][];
		for (int i = 0; i < AREAS; i++) {
			entries[i] = area(i);
		}
		entries[AREAS] = new byte[AREAS * 44];
		HandedGameTest.writeGarc(ad, entries);
		new File(root, "workspace").mkdirs();
		WorkspaceSession ws = Sessions.bare(new File(root, "workspace"), new File(root, "game"), type)
				.withArchive(ArchiveType.AREA_DATA, new GARC(ad));
		ws.prepareDirectories();
		Workspace.install(ws);
		return ws;
	}

	static byte[] sub7(int area) {
		return new AD(Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, area), Workspace.session()).getFile(7);
	}

	static void theCollisionMassEditIsWholeAndTakenBack() throws Exception {
		System.out.println("--- the camera-collision mass edit writes every area, and is taken back (F100)");
		game("freecam", GameType.ORAS);
		byte[] dummy = ResourceAccess.getByteArray("DummyLumioseCollision.bin");
		List<byte[]> before = new ArrayList<>();
		for (int i = 0; i < AREAS; i++) {
			before.add(Files.readAllBytes(Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, i).toPath()));
		}
		ExtrasPanel panel = new ExtrasPanel(new LoadedZone());
		panel.withUndo();
		List<String> said = ctrmap.Ui.record(JOptionPane.OK_OPTION);
		MatrixEditFormGuardsTest.invoke(panel, "freecamActionPerformed");
		//the last subfile reads to the container's end, so it carries the container's trailing bytes
		check(Arrays.equals(Arrays.copyOf(sub7(0), dummy.length), dummy) && Arrays.equals(Arrays.copyOf(sub7(2), dummy.length), dummy)
				&& said.size() == 1, "every area's camera collision is the injected one, after one question: " + said.size());
		ctrmap.Ui.stopRecording();

		Method undo = ExtrasPanel.class.getDeclaredMethod("undoLastMassEdit");
		undo.setAccessible(true);
		said = ctrmap.Ui.record(JOptionPane.OK_OPTION);
		undo.invoke(panel);
		boolean same = true;
		for (int i = 0; i < AREAS; i++) {
			same &= Arrays.equals(before.get(i), Files.readAllBytes(Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, i).toPath()));
		}
		check(same && said.size() == 2 && said.get(1).contains("Put back"), "Undo last mass edit puts every area back byte for byte: " + said);
		ctrmap.Ui.stopRecording();

		System.out.println("--- an area that cannot be written leaves every area as it was (F100, census H.66)");
		File third = Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, 2);
		byte[] thirdBytes = Files.readAllBytes(third.toPath());
		third.delete();
		third.mkdirs();
		Files.write(new File(third, "blocker").toPath(), thirdBytes);
		said = ctrmap.Ui.record(JOptionPane.OK_OPTION);
		MatrixEditFormGuardsTest.invoke(panel, "freecamActionPerformed");
		check(Arrays.equals(before.get(0), Files.readAllBytes(Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, 0).toPath()))
				&& Arrays.equals(before.get(1), Files.readAllBytes(Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, 1).toPath())),
				"the areas before it are as they were");
		check(said.size() == 2 && said.get(1).contains("No camera collision was injected"), "and the user is told: " + said);
		ctrmap.Ui.stopRecording();
	}

	static void itIsOfferedOnlyWhereItWasMade() throws Exception {
		System.out.println("--- the mass edits are offered only on a game they were made for (F100)");
		game("xy", GameType.XY);
		byte[] was = Files.readAllBytes(Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, 0).toPath());
		ExtrasPanel panel = new ExtrasPanel(new LoadedZone());
		List<String> said = ctrmap.Ui.record(JOptionPane.OK_OPTION, JOptionPane.OK_OPTION);
		MatrixEditFormGuardsTest.invoke(panel, "freecamActionPerformed");
		MatrixEditFormGuardsTest.invoke(panel, "btn3dActionPerformed");
		check(said.size() == 2 && said.get(0).contains("no verified free-camera mod") && said.get(1).contains("no verified free-camera mod"),
				"on X/Y, where nothing was measured, both are refused in words, before any question: " + said);
		check(Arrays.equals(was, Files.readAllBytes(Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, 0).toPath())), "and nothing is written");
		ctrmap.Ui.stopRecording();
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
