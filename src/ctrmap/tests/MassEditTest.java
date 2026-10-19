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
			anEditsMarkTheChangeTookIsPutBack();
			anUndoOrRedoThatCannotWriteSaysSo();
			anUndoAfterAPackReachesTheGame();
			theCollisionMassEditIsWholeAndTakenBack();
			itIsOfferedOnlyWhereItWasMade();
			aMissingAreaTableIsSaid();
		} finally {
			ctrmap.Ui.stopRecording();
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/**
	 * A kept change that took an edit's mark away gives it back with Undo, and leaves the marks it never touched.
	 *
	 * <p>Measured by the 2026-10-12 sweep (WorkspaceUnit 240, negated, SURVIVED): no kept change ever took a mark away.
	 * Inverted, it recorded the marks it had NOT touched as the ones it took, so Undo put the file back without its
	 * mark - the owner's edit on disk, and the next pack leaving it out of the game.
	 */
	static void anEditsMarkTheChangeTookIsPutBack() throws Exception {
		System.out.println("--- a kept change that took an edit's mark away gives it back with Undo");
		WorkspaceSession ws = game("unlisted", GameType.ORAS);
		File dir = ws.getExtractionDirectory(ArchiveType.ZONE_DATA);
		dir.mkdirs();
		File dropped = new File(dir, "9");
		File kept = new File(dir, "10");
		Files.write(dropped.toPath(), "zone 9, edited".getBytes(StandardCharsets.US_ASCII));
		Files.write(kept.toPath(), "zone 10, edited".getBytes(StandardCharsets.US_ASCII));
		ws.addPersist(dropped);
		ws.addPersist(kept);
		WorkspaceUnit.Kept change;
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Remove zone 9", ws)) {
			ws.dropExtracted(ArchiveType.ZONE_DATA, n -> n == 9);
			change = unit.commitKeeping();
		}
		check(!dropped.exists() && !ws.isPersisted(dropped) && ws.isPersisted(kept),
				"control: the change took zone 9's copy and its mark, and left zone 10's");
		change.revert();
		check(dropped.isFile() && ws.isPersisted(dropped) && ws.isPersisted(kept),
				"Undo gives zone 9 back WITH its edited mark, so the next pack carries it, and zone 10 keeps its own");
	}

	/**
	 * An Undo or a Redo that cannot write the files it puts back is refused in words - never answered as done while
	 * the files stay as they were.
	 *
	 * <p>Measured by the 2026-10-12 sweep (WorkspaceUnit 409, dropped, SURVIVED): no take-back of a kept change ever
	 * failed to write. Dropped, Undo answered done with the change's files untouched, and the history stepped back past
	 * a change still on disk. Its twin on Redo (418) is asked with it. Asked on Windows, where a file held open
	 * cannot be deleted.
	 */
	static void anUndoOrRedoThatCannotWriteSaysSo() throws Exception {
		System.out.println("--- an Undo or a Redo that cannot write what it puts back is refused in words");
		if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
			Skip.section("a file held open so it cannot be deleted is asked only on Windows - NOT asked here");
			return;
		}
		WorkspaceSession ws = game("unwritable", GameType.ORAS);
		File dir = Scratch.dir("ctrmap_kept_unwritable");
		File made = new File(dir, "made");
		File gone = new File(dir, "gone");
		Files.write(gone.toPath(), "there before".getBytes(StandardCharsets.US_ASCII));
		WorkspaceUnit.Kept change;
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Test change", ws)) {
			WorkspaceUnit.write(made, "made by the change".getBytes(StandardCharsets.US_ASCII));
			WorkspaceUnit.touching(gone);
			Files.delete(gone.toPath());
			change = unit.commitKeeping();
		}
		String undone;
		try (java.io.FileInputStream hold = new java.io.FileInputStream(made)) {   //Undo must delete it
			try {
				change.undo();
				undone = "Undo answered done";
			} catch (IllegalStateException refused) {
				undone = refused.getMessage();
			}
		}
		check(undone.contains("Test change could not be put back") && made.isFile() && !gone.exists(),
				"an Undo that cannot delete a file it puts back is refused, naming the change, and nothing moved: " + undone);
		change.undo();
		check(!made.exists() && gone.isFile(), "control: with nothing held, the same Undo puts both back");
		String redone;
		try (java.io.FileInputStream hold = new java.io.FileInputStream(gone)) {   //Redo must delete it again
			try {
				change.redo();
				redone = "Redo answered done";
			} catch (IllegalStateException refused) {
				redone = refused.getMessage();
			}
		}
		check(redone.contains("Test change could not be put again") && gone.isFile(),
				"and a Redo that cannot delete one is refused the same way: " + redone);
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
			WorkspaceUnit.inMemory(() -> memory[0] = "before", () -> memory[0] = "after");
			kept = unit.commitKeeping();
		}
		check(text(a).equals("a after") && b.isFile() && memory[0].equals("after"), "control: the unit's writes are kept");
		kept.revert();
		check(text(a).equals("a before") && !b.exists() && memory[0].equals("before"),
				"taken back: the file it changed is as it was, the one it made is gone, and its memory with them");
		kept.reapply();
		check(text(a).equals("a after") && text(b).equals("b made") && memory[0].equals("after"),
				"put again: both files as the operation left them, and its memory with them (F209)");
		kept.revert();
		Files.write(a.toPath(), "a edited since".getBytes(StandardCharsets.US_ASCII));
		String again = null;
		try {
			kept.reapply();
		} catch (IllegalStateException refused) {
			again = refused.getMessage();
		}
		check(again != null && again.contains("since it was taken back") && !b.exists(),
				"a file edited since it was taken back refuses the Redo, naming it, and puts nothing again: " + again);

		//THE COMPRESSION FLAGS IT SET GO WITH IT (F209c): every fork, append and resize sets them, and a kept change
		//refused them - none of those could be taken back
		WorkspaceUnit.Kept flagged;
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Test fork", ws)) {
			WorkspaceUnit.write(a, "a forked".getBytes(StandardCharsets.US_ASCII));
			ws.registerPendingCompression(ArchiveType.FIELD_DATA, 7, true);
			flagged = unit.commitKeeping();
		}
		ws.registerPendingCompression(ArchiveType.ZONE_DATA, 3, false);   //a later change's flag, its own
		flagged.revert();
		boolean back = !ws.hasPendingCompression(ArchiveType.FIELD_DATA) && ws.hasPendingCompression(ArchiveType.ZONE_DATA);
		flagged.reapply();
		check(back && Boolean.TRUE.equals(ws.pendingCompressionSnapshot().get(ArchiveType.FIELD_DATA).get(7))
				&& ws.hasPendingCompression(ArchiveType.ZONE_DATA),
				"a kept change that set a compression flag is kept, its flag taken back with Undo and set again with Redo,"
				+ " and a later change's flag left as it was");
		flagged.revert();
		ws.consumePendingCompression(ArchiveType.ZONE_DATA);

		//NO HALF A CHANGE (F209c): nothing in memory can join a unit able to put it back and not put it again -
		//the one-handed hook is gone, and the two-handed one refuses a missing hand
		boolean noOneHanded;
		try {
			WorkspaceUnit.class.getMethod("onRollback", Runnable.class);
			noOneHanded = false;
		} catch (NoSuchMethodException gone) {
			noOneHanded = true;
		}
		String refusedHalf = null;
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Test edit", ws)) {
			WorkspaceUnit.inMemory(() -> memory[0] = "rolled back", null);
		} catch (IllegalArgumentException refused) {
			refusedHalf = refused.getMessage();
		}
		check(noOneHanded && refusedHalf != null && refusedHalf.contains("put again") && text(a).equals("a edited since"),
				"nothing in memory joins a unit able to put it back and not put it again: no rollback-only hook, and"
				+ " inMemory refuses a missing half (" + refusedHalf + ")");

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

	/**
	 * A kept change a pack has carried into the game, taken back, is carried back out by the next pack - and one
	 * that added an archive entry, which no pack can take out again, is refused (F209b).
	 */
	static void anUndoAfterAPackReachesTheGame() throws Exception {
		System.out.println("--- a change packed into the game and then taken back reaches the game again at the next pack");
		WorkspaceSession ws = game("packed", GameType.ORAS);
		Workspace.PackProgress quiet = (at, what) -> {
		};
		File list = WorkspaceSession.persistFile(ws.workspaceDir());

		//CONTROL: taken back before any pack, the file leaves the list - the archive never held the edit
		File early = Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, 2);
		byte[] earlyWas = Files.readAllBytes(early.toPath());
		WorkspaceUnit.Kept unpacked;
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Test early edit", ws)) {
			WorkspaceUnit.write(early, flipped(earlyWas));
			ws.addPersist(early);
			unpacked = unit.commitKeeping();
		}
		unpacked.revert();
		check(!ws.isPersisted(early) && Arrays.equals(earlyWas, Files.readAllBytes(early.toPath())),
				"control: a change taken back before any pack leaves the list of edits with its bytes put back");

		File area = Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, 1);
		byte[] was = Files.readAllBytes(area.toPath());
		WorkspaceUnit.Kept kept;
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Test area edit", ws)) {
			WorkspaceUnit.write(area, flipped(was));
			ws.addPersist(area);
			kept = unit.commitKeeping();
		}
		ws.packArchives(quiet);
		check(Arrays.equals(flipped(was), Workspace.getArchive(ArchiveType.AREA_DATA).getDecompressedEntry(1)),
				"control: the pack put the edit into the game");
		kept.revert();
		String relative = area.getAbsolutePath().substring(ws.workspaceDir().getAbsolutePath().length());
		boolean onDisk = list.isFile() && text(list).contains(relative);
		check(ws.isPersisted(area) && onDisk,
				"taken back after a pack, the file stays on the list of edits, in memory and on disk, so the next pack"
				+ " writes it back (listed: " + ws.isPersisted(area) + ", on disk: " + onDisk + ")");
		ws.packArchives(quiet);
		check(Arrays.equals(was, Workspace.getArchive(ArchiveType.AREA_DATA).getDecompressedEntry(1)),
				"an edit packed into the game and then taken back is taken out of the game by the next pack");
		String redone = null;
		try {
			kept.reapply();
		} catch (IllegalStateException refused) {
			redone = refused.getMessage();
		}
		check(redone == null && Arrays.equals(flipped(was), Files.readAllBytes(area.toPath())) && ws.isPersisted(area),
				"and put again after that, it is put again - the list it kept is no change since: " + redone);

		//A PACK INSIDE THE CHANGE CARRIES IT TOO: the painter's Apply packs before its change ends
		File inside = Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, 3);
		byte[] insideWas = Files.readAllBytes(inside.toPath());
		WorkspaceUnit.Kept packedInside;
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Test edit packed before it ended", ws)) {
			WorkspaceUnit.write(inside, flipped(insideWas));
			ws.addPersist(inside);
			ws.packArchives(quiet);
			packedInside = unit.commitKeeping();
		}
		packedInside.revert();
		ws.packArchives(quiet);
		check(Arrays.equals(insideWas, Workspace.getArchive(ArchiveType.AREA_DATA).getDecompressedEntry(3)),
				"a change packed before it ended, taken back, is taken out of the game by the next pack");

		//ONE ARCHIVE PACKED BY THE RAW BROWSER'S ADD FILE IS A PACK TOO: it packed the archive itself, counted by nothing
		File single = Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, 4);
		byte[] singleWas = Files.readAllBytes(single.toPath());
		WorkspaceUnit.Kept packedAlone;
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Test edit packed alone", ws)) {
			WorkspaceUnit.write(single, flipped(singleWas));
			ws.addPersist(single);
			packedAlone = unit.commitKeeping();
		}
		ws.packOne(ArchiveType.AREA_DATA);
		packedAlone.revert();
		check(ws.isPersisted(single),
				"a change one archive's own pack carried, taken back, stays on the list for the next pack to write back");

		File added = new File(Workspace.getExtractionDirectory(ArchiveType.AREA_DATA), String.valueOf(AREAS + 1));
		WorkspaceUnit.Kept adding;
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Test area added", ws)) {
			WorkspaceUnit.write(added, area(AREAS + 1));
			ws.addPersist(added);
			adding = unit.commitKeeping();
		}
		ws.packArchives(quiet);
		check(Workspace.getArchive(ArchiveType.AREA_DATA).length == AREAS + 2,
				"control: the pack appended the added area to the game's archive");
		String refused = null;
		try {
			adding.revert();
		} catch (IllegalStateException said) {
			refused = said.getMessage();
		}
		check(refused != null && refused.contains("[" + added.getParentFile().getName() + "/" + added.getName() + "]")
				&& added.isFile() && ws.isPersisted(added),
				"a change that added an archive entry, packed since, is not taken back - no pack takes an entry out -"
				+ " and says so, having put nothing back: " + refused);
	}

	/** {@code b} with its last byte changed: the same layout, different bytes. */
	static byte[] flipped(byte[] b) {
		byte[] out = b.clone();
		out[out.length - 1] ^= 0x01;
		return out;
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
		ctrmap.humaninterface.UndoHistory history = ctrmap.humaninterface.UndoHistory.of(panel.withUndo(), null);
		check(history != null, "the panel's Undo and Redo sit inside the history that owns them, where Ctrl+Z finds it");
		List<String> said = ctrmap.Ui.record(JOptionPane.OK_OPTION);
		MatrixEditFormGuardsTest.invoke(panel, "freecamActionPerformed");
		//the last subfile reads to the container's end, so it carries the container's trailing bytes
		check(Arrays.equals(Arrays.copyOf(sub7(0), dummy.length), dummy) && Arrays.equals(Arrays.copyOf(sub7(2), dummy.length), dummy)
				&& said.size() == 1, "every area's camera collision is the injected one, after one question: " + said.size());
		ctrmap.Ui.stopRecording();

		boolean undone = history.undo();
		boolean same = true;
		for (int i = 0; i < AREAS; i++) {
			same &= Arrays.equals(before.get(i), Files.readAllBytes(Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, i).toPath()));
		}
		check(undone && same, "Undo puts every area back byte for byte");
		check(history.redo() && Arrays.equals(Arrays.copyOf(sub7(0), dummy.length), dummy)
				&& Arrays.equals(Arrays.copyOf(sub7(2), dummy.length), dummy), "and Redo injects every area again (F209)");
		history.undo();

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

	/**
	 * A mass edit whose per-area table cannot be extracted is refused before any question, naming the table (the
	 * 2026-10-14 shared sweep, ExtrasPanel 395, drop-throw, SURVIVED: every made game here held the table).
	 */
	static void aMissingAreaTableIsSaid() throws Exception {
		System.out.println("--- a mass edit whose per-area table cannot be extracted says so, before any question");
		game("notable", GameType.ORAS);
		File table = Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, AREAS);
		check(table != null && table.isFile(), "CONTROL: the made game's per-area table extracts: " + table);
		if (table == null) {
			return;
		}
		table.delete();
		table.mkdirs();   //a folder where the table's file would be: nothing that can be read as the table
		ExtrasPanel panel = new ExtrasPanel(new LoadedZone());
		List<String> said = ctrmap.Ui.record(JOptionPane.OK_OPTION);
		String threw = "";
		try {
			MatrixEditFormGuardsTest.invoke(panel, "btn3dActionPerformed");
		} catch (Exception e) {   //read as an answer: a throw out of the button fails below, in words
			threw = " - and it threw " + e;
		}
		ctrmap.Ui.stopRecording();
		check(said.size() == 1 && said.get(0).contains("could not be extracted from the open workspace, so there is nothing to"
				+ " patch") && threw.isEmpty(), "the edit is refused, naming the table, before any question: " + said + threw);
	}

	static void itIsOfferedOnlyWhereItWasMade() throws Exception {
		System.out.println("--- the mass edits are offered only on a game they were made for (F100)");
		game("xy", GameType.XY);
		byte[] was = Files.readAllBytes(Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, 0).toPath());
		ExtrasPanel panel = new ExtrasPanel(new LoadedZone());
		List<String> said = ctrmap.Ui.record(JOptionPane.OK_OPTION, JOptionPane.OK_OPTION, JOptionPane.OK_OPTION);
		MatrixEditFormGuardsTest.invoke(panel, "freecamActionPerformed");
		MatrixEditFormGuardsTest.invoke(panel, "btn3dActionPerformed");
		//the third, the camera flags and zone failsafes (the 2026-10-14 shared sweep, ExtrasPanel 185, negate-if,
		//SURVIVED: no suite pressed it where it is not offered)
		MatrixEditFormGuardsTest.invoke(panel, "freecamRegActionPerformed");
		check(said.size() == 3 && said.get(0).contains("no verified free-camera mod") && said.get(1).contains("no verified free-camera mod")
				&& said.get(2).contains("no verified free-camera mod"),
				"on X/Y, where nothing was measured, all three are refused in words, before any question: " + said);
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
