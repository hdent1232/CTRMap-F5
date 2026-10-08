package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JLabel;

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
 * <p>ORDER: needs no dump and no display. A stamp needs a retail region, so this reads the order of its
 * stamp and save in its source, the way PaintCommitTest reads the painter's commit. Undo is asked of the
 * running form (F209), which builds over a map view that needs no OpenGL since F077: the region being
 * edited is a document of the zone's history, and its state is set by hand.
 *
 * Usage: java ctrmap.tests.GeoSaveTest
 */
public class GeoSaveTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		Headless.require();
		String src = SourceSeamTest.stripComments(new String(Files.readAllBytes(
				new File("src/ctrmap/humaninterface/GeoEditForm.java").toPath()), StandardCharsets.UTF_8));
		String stamp = CollisionReadTest.body(src, "public void stampHere(");
		String save = CollisionReadTest.body(src, "private void save(");
		String store = CollisionReadTest.body(src, "public boolean store(");
		check(stamp != null && stamp.contains("stampGeometry(") && save != null && store != null,
				"control: the stamp, save and store bodies are found");

		System.out.println("--- a stamp records its texture import and writes nothing (F101)");
		check(stamp != null && !stamp.contains("carryTextures(") && stamp.contains("pendingCarries.add("),
				"the stamp adds a pending carry and does not carry");

		System.out.println("--- Discard takes it back (F101); Undo is asked of the running form below");
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

		System.out.println("--- the form keeps no undo of its own: its edits are the zone history's, which is bounded (F101, F209)");
		check(!src.contains("Deque") && !src.contains(".push(") && src.contains("history.watch("),
				"no stack in the form - the region is watched by the zone's history, which keeps the newest "
				+ ctrmap.humaninterface.UndoHistory.LIMIT + " edits");
		theRegionIsADocumentOfTheZonesHistory();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/**
	 * The region being edited is a document of the zone's history (F209): a stamp is an edit of that
	 * history, Undo takes the model back and drops the texture import the stamp added, Redo puts both
	 * again, and an edit to a region the form has since left is refused - never put into the one picked.
	 */
	@SuppressWarnings("unchecked")
	static void theRegionIsADocumentOfTheZonesHistory() throws Exception {
		System.out.println("--- the region being edited is a document of the zone's history (F209)");
		int[] npc = {0};
		ctrmap.humaninterface.UndoHistory zone = UndoHistoryTest.overOneNumber(npc);
		//a map view with no models: reloadRegionModel has nothing to draw into and returns
		ctrmap.humaninterface.TileMapPanel view = (ctrmap.humaninterface.TileMapPanel) RenderLoopTest.bare(
				ctrmap.humaninterface.TileMapPanel.class);
		String said0 = "(nothing was thrown)";
		try {
			new ctrmap.humaninterface.GeoEditForm(new ctrmap.LoadedZone(), view, null);
		} catch (RuntimeException refused) {
			said0 = refused.getClass().getSimpleName() + ": " + refused.getMessage();
		}
		check(said0.startsWith("IllegalArgumentException") && said0.contains("must be handed the zone's history"),
				"a form built without the zone's history is refused: " + said0);
		ctrmap.humaninterface.GeoEditForm form = new ctrmap.humaninterface.GeoEditForm(new ctrmap.LoadedZone(), view, zone);
		byte[] model = {1, 2, 3};
		byte[] coll = {4, 5};
		set(form, "gr", RenderLoopTest.bare(ctrmap.formats.containers.GR.class));
		set(form, "regionId", 5);
		set(form, "currentModel", model);
		((Map<Integer, byte[]>) get(form, "currentColl")).put(2, coll);
		set(form, "savedModel", model);
		((Map<Integer, byte[]>) get(form, "savedColl")).put(2, coll);
		zone.settle();
		check(!zone.canUndo(), "control: a region picked is noted, not an edit");
		npc[0] = 7;
		zone.settle();
		//a stamp: a new model, and a texture import waiting for Save
		set(form, "currentModel", new byte[]{9});
		List<Object> carries = (List<Object>) get(form, "pendingCarries");
		java.lang.reflect.Constructor<?> carry = Class.forName("ctrmap.humaninterface.GeoEditForm$Carry")
				.getDeclaredConstructor(int.class, int.class, List.class);
		carry.setAccessible(true);
		carries.add(carry.newInstance(1, 2, new ArrayList<String>()));
		set(form, "unsaved", true);
		check(zone.settle() && "the geometry of region 5".equals(zone.undoName()),
				"the stamp is an edit of the zone's history: " + zone.undoName());
		JButton undo = (JButton) get(form, "btnUndo");
		JButton redo = (JButton) get(form, "btnRedo");
		check(undo.isEnabled() && !redo.isEnabled(), "and the form's Undo lights from it");
		undo.doClick();
		check(get(form, "currentModel") == model && carries.isEmpty() && !((Boolean) get(form, "unsaved")),
				"Undo puts the model back, drops the import its stamp added, and the region is as saved again");
		check("Undone: the geometry of region 5.".equals(((JLabel) get(form, "status")).getText()),
				"and says what it took back: " + ((JLabel) get(form, "status")).getText());
		check(npc[0] == 7, "the edit made before it, elsewhere, stays");
		redo.doClick();
		check(((byte[]) get(form, "currentModel"))[0] == 9 && carries.size() == 1 && (Boolean) get(form, "unsaved"),
				"Redo puts the stamp and its import again, unsaved");
		((Map<Integer, byte[]>) get(form, "currentColl")).put(2, new byte[]{8});   //the collision alone
		check(zone.settle() && "the geometry of region 5".equals(zone.undoName()),
				"a change to the collision alone is an edit too: " + zone.undoName());
		undo.doClick();
		check(((Map<Integer, byte[]>) get(form, "currentColl")).get(2) == coll && ((byte[]) get(form, "currentModel"))[0] == 9,
				"and Undo puts that layer back, leaving the stamp");
		//another region picked: an edit to the one left is refused, never put into this one
		set(form, "gr", RenderLoopTest.bare(ctrmap.formats.containers.GR.class));
		set(form, "regionId", 6);
		zone.settle();
		List<String> said = ctrmap.Ui.record();
		try {
			undo.doClick();
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said.size() == 1 && said.get(0).contains("no longer open") && said.get(0).contains("region 5"),
				"an Undo reaching the region the form left is refused, naming it: " + said);
		check(((byte[]) get(form, "currentModel"))[0] == 9, "and nothing was put into the region now picked");
	}

	static Object get(Object o, String name) throws Exception {
		java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	static void set(Object o, String name, Object value) throws Exception {
		java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		f.set(o, value);
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
