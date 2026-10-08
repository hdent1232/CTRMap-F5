package ctrmap.tests;

import ctrmap.LoadedZone;
import ctrmap.formats.containers.MM;
import ctrmap.formats.mapmatrix.MapMatrix;
import ctrmap.humaninterface.MatrixEditForm;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import javax.swing.JCheckBox;

/**
 * The matrix form's selected cell never outlives the cell, and the form refuses what would leave
 * the matrix with no cells or remove a camera boundary that is not there (F103, F080).
 *
 * <p>WHY THIS SUITE EXISTS. Remove column and Remove row shrank the matrix and kept the selected
 * cell, so after removing the column it sat on, the next save - Save, the next click, File &gt;
 * Save, a zone switch - threw out of {@code ids.set} and wrote nothing. Removing past the last
 * row went below zero, and Remove camera with no boundary removed item -1.
 *
 * <p>ORDER: needs no dump and no display; the matrix is made here and never written.
 *
 * Usage: java ctrmap.tests.MatrixCursorTest
 */
public class MatrixCursorTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		List<String> said = ctrmap.Ui.record();
		try {
			run();
		} finally {
			ctrmap.Ui.stopRecording();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	static void run() throws Exception {
		System.out.println("--- a resize lets go of the selected cell (F103)");
		MatrixEditForm form = form(3, 2);
		form.showRegion(2, 1);
		check(cursor(form, "curRegX") == 2 && cursor(form, "curRegY") == 1, "control: the cell at column 2 is selected");
		MatrixEditFormGuardsTest.invoke(form, "btnRemoveColActionPerformed");
		check(form.mm.width == 2 && cursor(form, "curRegX") == -1, "Remove column lets it go ("
				+ form.mm.width + " wide, cursor " + cursor(form, "curRegX") + ")");
		check(saves(form) == null, "and the next save writes nothing through it and throws nothing: " + saves(form));

		System.out.println("--- a save never writes through a cell that is not there (F103)");
		MatrixEditForm other = form(3, 2);
		other.showRegion(2, 1);
		other.mm.ids.removeColumn();
		other.mm.LOD.removeColumn();
		other.mm.zones.removeColumns(4);
		other.mm.width--;
		check(cursor(other, "curRegX") == 2, "control: shrunk behind the form's back, the cursor still names column 2");
		check(saves(other) == null, "and a save still throws nothing: " + saves(other));

		System.out.println("--- the last row and column stay, and a camera boundary that is not there is not removed (F080, F103)");
		MatrixEditForm one = form(1, 1);
		List<String> heard = ctrmap.Ui.record();
		MatrixEditFormGuardsTest.invoke(one, "btnRemoveColActionPerformed");
		MatrixEditFormGuardsTest.invoke(one, "btnRemoveRowActionPerformed");
		check(one.mm.width == 1 && one.mm.height == 1 && one.mm.ids.getWidth() == 1 && one.mm.ids.getHeight() == 1,
				"Remove column and Remove row on a 1x1 map leave it 1x1 (" + one.mm.width + "x" + one.mm.height + ")");
		check(heard.size() == 2 && heard.get(0).contains("at least one column") && heard.get(1).contains("at least one row"),
				"and say why, both times: " + heard);
		one.mm.cambounds.clear();
		heard = ctrmap.Ui.record();
		Throwable thrown = null;
		try {
			MatrixEditFormGuardsTest.invoke(one, "btnRemoveCamActionPerformed");
		} catch (Throwable t) {
			thrown = t;
		}
		check(thrown == null && heard.size() == 1 && heard.get(0).contains("no camera boundary"),
				"Remove camera with no boundary refuses in words, not an index error: " + (thrown != null ? thrown : heard));
	}

	/** A form over a made {@code w}x{@code h} matrix with a zone layer, the Chunk tool picked. */
	static MatrixEditForm form(int w, int h) throws Exception {
		FakeGameFiles fake = new FakeGameFiles();
		MapMatrix mm = new MapMatrix(new MM(new File(fake.scratch(), "cursor.mm"), 2, MM.MM_MAP_MATRIX, fake), w, h, 1);
		MatrixEditForm form = new MatrixEditForm(new LoadedZone(), MatrixEditFormGuardsTest.CANVAS);
		form.mm = mm;
		form.loaded = true;
		((JCheckBox) MatrixEditFormGuardsTest.field(form, "allowExtended")).setSelected(true);
		MatrixEditFormGuardsTest.selectTool(form, "btnChunkTool");
		return form;
	}

	static int cursor(MatrixEditForm form, String name) throws Exception {
		Field f = MatrixEditForm.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.getInt(form);
	}

	/** Null when a save went through, else what it threw. */
	static Throwable saves(MatrixEditForm form) {
		try {
			form.saveAll();
			return null;
		} catch (Throwable t) {
			return t;
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
}
