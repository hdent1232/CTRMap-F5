package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.LoadedZone;
import ctrmap.Workspace;
import ctrmap.formats.containers.GR;
import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.GeoBoxOps;
import ctrmap.formats.tilemap.Tilemap;
import ctrmap.gamedef.ArchiveType;
import ctrmap.humaninterface.GeoEditForm;
import ctrmap.humaninterface.TileMapPanel;
import ctrmap.humaninterface.ZoneLoadingPanel;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JSpinner;

/**
 * What the Geometry tool writes into a map region, driven with no window,
 * against retail FieldData region 7.
 *
 * <p>CHARACTERIZATION. The form's promise is that an edit is transactional
 * across the three layers a map is made of - the visual model, the collision
 * heightmap and the movement tilemap - so the assertions here are the three
 * subfiles of the GR container after Save, compared against what the validated
 * primitives ({@link GeoBoxOps}, {@link GfColl}, {@link Tilemap}) produce from
 * the bytes that were read in. That is the whole contract: the form must write
 * what those primitives say and nothing else.
 *
 * <p>What is pinned:
 * <ol>
 * <li>A dragged tile rectangle names its region, clamps to one 40x40 cell, and
 *     reports the vertices, faces and meshes {@code GeoBoxOps.query} counts.</li>
 * <li>Move with a zero offset is refused, with a sentence, and touches nothing.</li>
 * <li>Move by one tile east writes all three layers - and the tilemap tuples
 *     land one tile east of where they were read.</li>
 * <li>Undo puts all three back, and a save afterwards writes nothing.</li>
 * <li>Delete voids the tiles it covers with the 21 00 00 01 tuple and takes the
 *     geometry with it.</li>
 * <li>The two checkboxes are obeyed: clearing them leaves the collision and the
 *     tilemap exactly as they were read while the model still moves.</li>
 * <li>{@code store(true)} asks first: Cancel refuses the switch and keeps the
 *     edit, No throws it away without writing.</li>
 * <li>Copy prefab and Stamp prefab, whose status line is the only account of
 *     how much of a stamp actually landed.</li>
 * <li>Every button is enabled exactly when the form can act.</li>
 * </ol>
 *
 * Usage: java ctrmap.tests.GeoEditFormGuardsTest &lt;pristine dump root&gt;
 */
public class GeoEditFormGuardsTest {

	/** The 3D scene the map view shares: a recorder, so what it was told can be read. */
	static final RecordingScene SCENE = new RecordingScene();

	/** The 3D gizmo these forms move, so what they told it can be read back. */
	static final RecordingNavi NAVI = new RecordingNavi();
	/** The editors that show the zone, for the panels here: a spy that records and clears. */
	static final ZoneEditorsSpy ZONE_EDITORS = new ZoneEditorsSpy();

	/** The editor set the panels here flush: it records instead of saving. */
	static final RecordingEditors EDITORS = new RecordingEditors();


	/** The zone owner every panel and form built here shares, as the window's would. */
	static final LoadedZone LOADED = new LoadedZone();
	/** The tool this suite holds: its own, so another suite may hold another. */
	static final ctrmap.humaninterface.tools.ToolSelection TOOLS = new ctrmap.humaninterface.tools.ToolSelection();


	/** A map model, one collision layer, a tilemap and two props: everything the tool touches. */
	static final int REGION = 7;
	/** The dragged rectangle: the middle of the cell, where every region has geometry. */
	static final int T0 = 8, T1 = 31;
	/** One tile east - the offset the tile layer can follow. */
	static final float STEP = 18f;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "no-dump-given");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump + " - the geometry form checks need FieldData region " + REGION);
			System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
			return;
		}
		ScratchGame.open(dump);
		ctrmap.formats.text.LocationNames.loadFromGarc(Workspace.session());
		CtrmapMainframe.mTilemapScrollPane = new javax.swing.JScrollPane();
		CtrmapMainframe.mZonePnl = new ZoneLoadingPanel(LOADED, TOOLS, EDITORS, ZONE_EDITORS, NAVI);

		theRegionIsTheOneTheseChecksDescribe();
		aSelectionNamesItsRegionAndCountsWhatIsInIt();
		aSelectionIsClampedToOneCell();
		anOffsetOfZeroIsRefusedAndSaysSo();
		moveWritesAllThreeLayers();
		undoPutsAllThreeLayersBack();
		deleteVoidsTheTilesItCovers();
		theCheckboxesAreObeyed();
		storeAsksFirstAndCancelKeepsTheEdit();
		copyAndStampAPrefabSayWhatLanded();
		theButtonsAreEnabledOnlyWhenTheFormCanAct();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		//TileMapPanel opens a GL profile, which parks a non-daemon thread
		System.exit(fails == 0 ? 0 : 1);
	}

	/** The corpus these checks describe, asserted rather than assumed. */
	static void theRegionIsTheOneTheseChecksDescribe() throws Exception {
		Fixture f = open();
		check(f.gr.len == 7, "FieldData region " + REGION + " is a 7-subfile container (found " + f.gr.len + ")");
		check(BchMapModel.isMapModel(f.model0), "subfile 1 is an editable map model, " + f.model0.length + " bytes");
		check(GfColl.isColl(f.coll0), "subfile 2 is a collision mesh");
		check(f.tiles0.length >= 4 + 40 * 40 * 4, "subfile 0 holds a 40x40 tilemap in " + f.tiles0.length + " bytes");
		GeoBoxOps.Selection q = GeoBoxOps.query(new BchMapModel(f.model0), box());
		check(q.vertices > 0 && q.fullFaces > 0, "tiles (" + T0 + "," + T0 + ")-(" + T1 + "," + T1 + ") cover "
				+ q.vertices + " vertices and " + q.fullFaces + " whole faces");
		check(!Arrays.equals(GeoBoxOps.move(f.model0, box(), STEP, 0, 0), f.model0), "so a move changes the model");
		check(!Arrays.equals(GfColl.moveBox(f.coll0, box().minX, box().minZ, box().maxX, box().maxZ, STEP, 0, 0), f.coll0),
				"and the collision");
	}

	/** The drag: the region is found, the box is built from the tiles, and the counts are reported. */
	static void aSelectionNamesItsRegionAndCountsWhatIsInIt() throws Exception {
		Fixture f = open();
		f.form.setSelection(T0, T0, T1, T1);
		check(f.form.selTx0 == T0 && f.form.selTy0 == T0 && f.form.selTx1 == T1 && f.form.selTy1 == T1,
				"the selection is (" + f.form.selTx0 + "," + f.form.selTy0 + ")-(" + f.form.selTx1 + "," + f.form.selTy1 + ")");
		check(label(f.form, "selLabel").equals("Tiles (" + T0 + "," + T0 + ")-(" + T1 + "," + T1 + ")"),
				"and is named without a region number, because a single-GR map has none: " + label(f.form, "selLabel"));
		GeoBoxOps.Selection q = GeoBoxOps.query(new BchMapModel(f.model0), box());
		check(label(f.form, "statsLabel").equals(q.vertices + " verts, " + q.fullFaces + " faces, " + q.touchedMeshes + " meshes selected"),
				"the stats line counts what the box covers: " + label(f.form, "statsLabel"));

		//a drag started from a corner, in the other order, is the same rectangle
		Fixture g = open();
		g.form.setSelection(T1, T1, T0, T0);
		check(g.form.selTx0 == T0 && g.form.selTy0 == T0 && g.form.selTx1 == T1 && g.form.selTy1 == T1,
				"dragging the other way selects the same rectangle");
	}

	/** v1 edits one region cell, so a drag past its edge is clamped to the anchor's cell. */
	static void aSelectionIsClampedToOneCell() throws Exception {
		Fixture f = open();
		f.form.setSelection(T0, T0, 90, 77);
		check(f.form.selTx1 == 39 && f.form.selTy1 == 39, "a drag out of the cell stops at tile 39: ("
				+ f.form.selTx1 + "," + f.form.selTy1 + ")");
		f.form.setSelection(-1, 5, 10, 10);
		check(f.form.selTx0 == T0, "a drag that starts off the map is ignored, and the old selection stands");
	}

	/** Move and Duplicate need somewhere to go, and say so rather than doing nothing quietly. */
	static void anOffsetOfZeroIsRefusedAndSaysSo() throws Exception {
		Fixture f = open();
		f.form.setSelection(T0, T0, T1, T1);
		button(f.form, "btnMove").doClick();
		check(label(f.form, "status").equals("Set a non-zero offset first."), "Move with no offset says: " + label(f.form, "status"));
		button(f.form, "btnDup").doClick();
		check(label(f.form, "status").equals("Set a non-zero offset first."), "so does Duplicate: " + label(f.form, "status"));
		check(!button(f.form, "btnUndo").isEnabled() && !button(f.form, "btnSave").isEnabled(), "and neither is undoable or saveable");
		check(Arrays.equals(f.gr.getFile(1), f.model0), "the model on disk is untouched");
	}

	/**
	 * The whole point of the form: one edit, three layers, and the bytes are
	 * the ones the validated primitives produce from the bytes that were read.
	 */
	static void moveWritesAllThreeLayers() throws Exception {
		Fixture f = open();
		f.form.setSelection(T0, T0, T1, T1);
		spinner(f.form, "dx").setValue((double) STEP);
		button(f.form, "btnMove").doClick();
		check(label(f.form, "status").equals("Moved +collision +576 tiles.  (unsaved)"),
				"the status line accounts for all three layers and says it is unsaved: " + label(f.form, "status"));
		check(button(f.form, "btnSave").isEnabled() && button(f.form, "btnUndo").isEnabled(), "Save and Undo are now offered");
		check(Arrays.equals(f.gr.getFile(1), f.model0), "and nothing is on disk until Save");

		button(f.form, "btnSave").doClick();
		check(label(f.form, "status").startsWith("Saved."), "Save says so: " + label(f.form, "status"));
		check(!button(f.form, "btnSave").isEnabled(), "and stops offering itself");

		byte[] wantModel = GeoBoxOps.move(f.model0, box(), STEP, 0, 0);
		byte[] wantColl = GfColl.moveBox(f.coll0, box().minX, box().minZ, box().maxX, box().maxZ, STEP, 0, 0);
		check(sub(f.gr, 1, wantModel.length), "subfile 1 is exactly what GeoBoxOps.move produced (" + wantModel.length + " bytes)");
		check(Arrays.equals(Arrays.copyOf(f.gr.getFile(1), wantModel.length), wantModel), "byte for byte");
		check(Arrays.equals(Arrays.copyOf(f.gr.getFile(2), wantColl.length), wantColl),
				"subfile 2 is exactly what GfColl.moveBox produced (" + wantColl.length + " bytes)");

		byte[] wantTiles = f.tiles0.clone();
		for (int y = T0; y <= T1; y++) {
			for (int x = T0; x <= T1; x++) {
				System.arraycopy(f.tiles0, tileAt(f.tiles0, x, y), wantTiles, tileAt(f.tiles0, x + 1, y), 4);
			}
		}
		check(Arrays.equals(Arrays.copyOf(f.gr.getFile(0), wantTiles.length), wantTiles),
				"and subfile 0 has every tile tuple one tile east of where it was read");
	}

	/** Undo is a snapshot of all three layers, and an undone edit is not a save. */
	static void undoPutsAllThreeLayersBack() throws Exception {
		Fixture f = open();
		f.form.setSelection(T0, T0, T1, T1);
		spinner(f.form, "dx").setValue((double) STEP);
		button(f.form, "btnMove").doClick();
		button(f.form, "btnUndo").doClick();
		check(label(f.form, "status").equals("Undone."), "Undo says so: " + label(f.form, "status"));
		check(!button(f.form, "btnUndo").isEnabled() && !button(f.form, "btnSave").isEnabled(),
				"there is nothing left to undo and nothing left to save");
		check(Arrays.equals((byte[]) field(f.form, "currentModel"), f.model0), "the model in hand is the one that was read");
		check(f.form.store(false), "storing afterwards succeeds");
		check(Arrays.equals(f.gr.getFile(1), f.model0) && Arrays.equals(f.gr.getFile(2), f.coll0)
				&& Arrays.equals(f.gr.getFile(0), f.tiles0), "and writes nothing at all");
	}

	/** Delete takes the geometry and voids the movement tiles under it. */
	static void deleteVoidsTheTilesItCovers() throws Exception {
		Fixture f = open();
		f.form.setSelection(T0, T0, T1, T1);
		button(f.form, "btnDel").doClick();
		check(label(f.form, "status").equals("Deleted +collision +tiles voided.  (unsaved)"),
				"Delete accounts for the tiles it voided: " + label(f.form, "status"));
		button(f.form, "btnSave").doClick();

		byte[] wantModel = GeoBoxOps.delete(f.model0, box());
		byte[] wantColl = GfColl.deleteBox(f.coll0, box().minX, box().minZ, box().maxX, box().maxZ);
		check(Arrays.equals(Arrays.copyOf(f.gr.getFile(1), wantModel.length), wantModel), "subfile 1 is what GeoBoxOps.delete produced");
		check(Arrays.equals(Arrays.copyOf(f.gr.getFile(2), wantColl.length), wantColl), "subfile 2 is what GfColl.deleteBox produced");
		byte[] tiles = f.gr.getFile(0);
		boolean allVoid = true, othersKept = true;
		for (int y = 0; y < 40; y++) {
			for (int x = 0; x < 40; x++) {
				int at = tileAt(f.tiles0, x, y);
				boolean inside = x >= T0 && x <= T1 && y >= T0 && y <= T1;
				byte[] now = Arrays.copyOfRange(tiles, at, at + 4);
				if (inside) {
					allVoid &= Arrays.equals(now, new byte[]{0x21, 0, 0, 1});
				} else {
					othersKept &= Arrays.equals(now, Arrays.copyOfRange(f.tiles0, at, at + 4));
				}
			}
		}
		check(allVoid, "every tile inside the selection is the 21 00 00 01 void tuple");
		check(othersKept, "and every tile outside it is the one that was read");
	}

	/** The two checkboxes decide whether the other two layers move with the model. */
	static void theCheckboxesAreObeyed() throws Exception {
		Fixture f = open();
		checkbox(f.form, "chkColl").setSelected(false);
		checkbox(f.form, "chkTiles").setSelected(false);
		f.form.setSelection(T0, T0, T1, T1);
		spinner(f.form, "dx").setValue((double) STEP);
		button(f.form, "btnMove").doClick();
		check(label(f.form, "status").equals("Moved.  (unsaved)"), "with both cleared the status names no other layer: " + label(f.form, "status"));
		button(f.form, "btnSave").doClick();
		byte[] wantModel = GeoBoxOps.move(f.model0, box(), STEP, 0, 0);
		check(Arrays.equals(Arrays.copyOf(f.gr.getFile(1), wantModel.length), wantModel), "the model still moved");
		check(Arrays.equals(f.gr.getFile(2), f.coll0), "the collision is byte for byte the one that was read");
		check(Arrays.equals(f.gr.getFile(0), f.tiles0), "and so is the tilemap");
	}

	/**
	 * Leaving the tool with an unsaved edit asks first. Cancel refuses the
	 * switch, No throws the edit away and writes nothing.
	 */
	static void storeAsksFirstAndCancelKeepsTheEdit() throws Exception {
		Fixture f = open();
		f.form.setSelection(T0, T0, T1, T1);
		spinner(f.form, "dx").setValue((double) STEP);
		button(f.form, "btnMove").doClick();

		List<String> said = ctrmap.Ui.record(JOptionPane.CANCEL_OPTION);
		boolean ok;
		try {
			ok = f.form.store(true);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(!ok, "Cancel refuses the store");
		check(said.size() == 1 && said.get(0).contains("Save the geometry edits"), "having asked: " + said);
		check(button(f.form, "btnSave").isEnabled(), "and the edit is still there to save");
		check(Arrays.equals(f.gr.getFile(1), f.model0), "with nothing written");

		said = ctrmap.Ui.record(JOptionPane.NO_OPTION);
		try {
			ok = f.form.store(true);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(ok && said.size() == 1, "No lets the store through");
		check(Arrays.equals(f.gr.getFile(1), f.model0), "writes nothing");
		check(!((Boolean) field(f.form, "unsaved")), "and drops the edit");
		//store() does not refresh the buttons on this arm, so Save goes on
		//offering itself for an edit that has been thrown away - pinned, see the report
		check(button(f.form, "btnSave").isEnabled(), "but Save is still offered, for an edit that is now gone");

		said = ctrmap.Ui.record();
		try {
			ok = f.form.store(true);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(ok && said.isEmpty(), "and with nothing unsaved it asks nothing at all");
	}

	/**
	 * Copy prefab and Stamp prefab, both of which are reachable only through
	 * modal dialogs - and whose status line is the only place a partial stamp
	 * is ever mentioned.
	 */
	static void copyAndStampAPrefabSayWhatLanded() throws Exception {
		Fixture f = open();
		f.form.setSelection(T0, T0, T1, T1);
		List<String> said = ctrmap.Ui.record("GuardPrefab", JOptionPane.NO_OPTION);
		try {
			button(f.form, "btnCopyPrefab").doClick();
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said.size() == 2 && said.get(0).contains("Prefab name"), "Copy prefab asks for a name: " + said.get(0));
		check(said.get(1).startsWith("Copy prefab: Copied \"GuardPrefab\":"), "and reports what it cut: " + said.get(1));
		check(label(f.form, "status").startsWith("Prefab \"GuardPrefab\" on the clipboard"),
				"the status line says where it went: " + label(f.form, "status"));

		//stamp it back onto the same region, one tile north-west of where it came from
		f.form.setSelection(T0 - 1, T0 - 1, T1 - 1, T1 - 1);
		said = ctrmap.Ui.record(JOptionPane.YES_OPTION);
		try {
			button(f.form, "btnStampPrefab").doClick();
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said.size() == 1 && said.get(0).contains("Stamp \"GuardPrefab\" here?"), "Stamp prefab asks first: " + said);
		check(label(f.form, "status").startsWith("Stamped ") && label(f.form, "status").endsWith("(unsaved)"),
				"and says how much of it landed: " + label(f.form, "status"));
		check(button(f.form, "btnSave").isEnabled(), "with the result waiting to be saved");
		button(f.form, "btnSave").doClick();
		byte[] stamped = f.gr.getFile(1);
		check(!Arrays.equals(stamped, f.model0), "and the saved model is not the one that was read");
		check(new BchMapModel(Arrays.copyOf(stamped, stamped.length)).validate().isEmpty(), "and still parses clean");
	}

	/** Nothing can be pressed before there is a selection, or after it is cleared. */
	static void theButtonsAreEnabledOnlyWhenTheFormCanAct() throws Exception {
		Fixture f = open();
		String[] tools = {"btnMove", "btnDup", "btnDel", "btnCopyPrefab", "btnStampPrefab"};
		boolean any = false;
		for (String b : tools) {
			any |= button(f.form, b).isEnabled();
		}
		check(!any, "a form with no selection offers no tool");
		f.form.setSelection(T0, T0, T1, T1);
		boolean all = true;
		for (String b : tools) {
			all &= button(f.form, b).isEnabled();
		}
		check(all, "a selection over an editable model offers all five");
		f.form.clearSelection();
		any = false;
		for (String b : tools) {
			any |= button(f.form, b).isEnabled();
		}
		check(!any, "and clearing it takes them all away again");
		check(f.form.selTx0 == -1 && f.form.selTy0 == -1 && f.form.selTx1 == -1 && f.form.selTy1 == -1,
				"with the selection itself gone");
	}

	// ---- fixture -------------------------------------------------------

	/** A geometry form over a scratch copy of region {@link #REGION}, with the bytes it started from. */
	static class Fixture {

		GeoEditForm form;
		GR gr;
		/** The three subfiles as the padded container held them when it was opened. */
		byte[] model0;
		byte[] coll0;
		byte[] tiles0;
	}

	/**
	 * Region {@link #REGION} on a FRESH scratch copy, behind a map view holding
	 * that one GR and no matrix - the shape the tool sees when a single region
	 * is open. The view's model array is left null so the live 3D refresh is a
	 * no-op; its tilemap array is real, because the movement tiles are one of
	 * the three layers under test.
	 */
	static Fixture open() throws Exception {
		Fixture f = new Fixture();
		File onDisk = Scratch.file("ctrmap_geoform");
		java.nio.file.Files.write(onDisk.toPath(),
				Workspace.getArchive(ArchiveType.FIELD_DATA).getDecompressedEntry(REGION));
		f.gr = new GR(onDisk, Workspace.session());
		f.tiles0 = f.gr.getFile(0);
		f.model0 = f.gr.getFile(1);
		f.coll0 = f.gr.getFile(2);
		TileMapPanel view = new TileMapPanel(LOADED, TOOLS, SCENE, new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(TOOLS), null);
		view.mainGR = f.gr;
		view.tilemaps = new Tilemap[][]{{new Tilemap(f.gr)}};
		CtrmapMainframe.mTileMapPanel = view;
		f.form = new GeoEditForm(LOADED);
		CtrmapMainframe.mGeoEditForm = f.form;
		return f;
	}

	/** The tile rectangle these checks drag, in the region-local frame the ops use. */
	static GeoBoxOps.Box box() {
		return GeoBoxOps.Box.ofTiles(T0, T0, T1, T1);
	}

	/** Byte offset of one tile tuple inside an assembled tilemap. */
	static int tileAt(byte[] tilemap, int x, int y) {
		int width = (tilemap[0] & 0xFF) | ((tilemap[1] & 0xFF) << 8);
		return 4 + (y * width + x) * 4;
	}

	/** True when a subfile is at least as long as the data it should hold, padding aside. */
	static boolean sub(GR gr, int index, int want) {
		byte[] got = gr.getFile(index);
		return got != null && got.length >= want;
	}

	static String label(Object o, String name) throws Exception {
		return ((JLabel) field(o, name)).getText();
	}

	static JButton button(Object o, String name) throws Exception {
		return (JButton) field(o, name);
	}

	static JCheckBox checkbox(Object o, String name) throws Exception {
		return (JCheckBox) field(o, name);
	}

	static JSpinner spinner(Object o, String name) throws Exception {
		return (JSpinner) field(o, name);
	}

	static Object field(Object o, String name) throws Exception {
		Field fl = o.getClass().getDeclaredField(name);
		fl.setAccessible(true);
		return fl.get(o);
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
