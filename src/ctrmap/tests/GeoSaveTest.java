package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The geometry form touches the workspace only on Save: a stamp's texture import waits for it, Undo
 * and Discard take it back, and Save writes it with the region as one unit (F101).
 *
 * <p>WHY THIS SUITE EXISTS. The form's class comment promised that everything is undoable and only
 * touches the workspace on Save, and a prefab stamp imported the donor area's textures into the
 * target area's pack the moment it was stamped - so Undo restored the model and left the textures,
 * and Discard left them too. The undo stack kept a whole copy of the region for every edit of the
 * session, without end.
 *
 * <p>ORDER: needs no dump and no display. The form itself needs the map view, which needs GL, and a
 * stamp needs a retail region; so this reads the order of its stamp, undo and save in its source,
 * the way PaintCommitTest reads the painter's commit.
 *
 * Usage: java ctrmap.tests.GeoSaveTest
 */
public class GeoSaveTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		String src = SourceSeamTest.stripComments(new String(Files.readAllBytes(
				new File("src/ctrmap/humaninterface/GeoEditForm.java").toPath()), StandardCharsets.UTF_8));
		String stamp = CollisionReadTest.body(src, "public void stampHere(");
		String undo = CollisionReadTest.body(src, "private void undo(");
		String save = CollisionReadTest.body(src, "private void save(");
		String store = CollisionReadTest.body(src, "public boolean store(");
		String remember = CollisionReadTest.body(src, "private void remember(");
		check(stamp != null && stamp.contains("stampGeometry(") && undo != null && save != null && store != null && remember != null,
				"control: the stamp, undo, save, store and remember bodies are found");

		System.out.println("--- a stamp records its texture import and writes nothing (F101)");
		check(stamp != null && !stamp.contains("carryTextures(") && stamp.contains("pendingCarries.add("),
				"the stamp adds a pending carry and does not carry");
		check(stamp != null && stamp.indexOf("snap.carries = pendingCarries.size()") >= 0
				&& stamp.indexOf("snap.carries = pendingCarries.size()") < stamp.indexOf("pendingCarries.add("),
				"and its snapshot remembers how many were waiting before it");

		System.out.println("--- Undo and Discard take it back (F101)");
		check(undo != null && undo.contains("pendingCarries.remove("), "Undo drops the carries its stamp added");
		//through the discard its question runs (F106), which reads the region again with nothing waiting
		String discard = CollisionReadTest.body(src, "private void discard(");
		String readRegion = CollisionReadTest.body(src, "private void readRegion(");
		check(store != null && store.contains("this::discard") && discard != null && discard.contains("readRegion()")
				&& readRegion != null && readRegion.contains("pendingCarries.clear()"), "a discarded save drops them all");

		System.out.println("--- Save writes them with the region, as one unit (F101)");
		int begin = save == null ? -1 : save.indexOf("WorkspaceUnit.begin(");
		int carry = save == null ? -1 : save.indexOf("carryTextures(");
		int model = save == null ? -1 : save.indexOf("storeFile(1");
		int commit = save == null ? -1 : save.indexOf("unit.commit()");
		int cleared = save == null ? -1 : save.indexOf("pendingCarries.clear()");
		check(begin >= 0 && begin < carry && carry < model && model < commit && commit < cleared,
				"the unit opens, the carries run, the model is written, the unit commits, and only then are the carries"
				+ " done with (begin " + begin + ", carry " + carry + ", model " + model + ", commit " + commit
				+ ", cleared " + cleared + ")");

		System.out.println("--- a save that did not happen stops what asked for it (F106)");
		int saved = store == null ? -1 : store.indexOf("save();");
		int stillUnsaved = saved < 0 ? -1 : store.indexOf("if (unsaved)", saved);
		check(stillUnsaved > saved && store.indexOf("ctrmap.Ui.error(", stillUnsaved) > stillUnsaved
				&& store.indexOf("return false;", stillUnsaved) > stillUnsaved,
				"store() answers false, having said why, when its save left the edit unsaved - it answered true, and"
				+ " the zone switch went on over an edit only the status line said was not written");

		System.out.println("--- Undo keeps a bounded number of edits (F101)");
		check(remember != null && remember.contains("undo.removeLast()") && remember.contains("UNDO_DEPTH")
				&& src.contains("remember(snap);") && !src.replace(remember, "").contains("undo.push("),
				"every snapshot goes through remember, which lets the oldest go past UNDO_DEPTH");

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
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
