package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.formats.containers.MM;
import ctrmap.formats.mapmatrix.MapMatrix;
import ctrmap.formats.mapmatrix.MatrixCameraBoundaries;
import ctrmap.formats.zone.Zone;
import ctrmap.gamedef.ArchiveType;
import ctrmap.humaninterface.MapMatrixPanel;
import ctrmap.humaninterface.MatrixEditForm;
import ctrmap.humaninterface.ZoneLoadingPanel;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JRadioButton;

/**
 * What the map-matrix editor writes into a .mm container, driven with no
 * window, against retail matrix 1 (8x8 regions, LOD/zone-switch layer present,
 * three camera boundaries).
 *
 * <p>CHARACTERIZATION, not correctness: several of the things pinned here look
 * wrong and are reported as such rather than fixed, because a later step is
 * going to move this file and the only way that move can be checked is against
 * what the editor does TODAY. The matrix is the file that says which map region
 * loads where, so a byte that changes without anyone asking is a town in the
 * wrong place.
 *
 * <p>What is pinned:
 * <ol>
 * <li>Opening a matrix and saving it untouched writes nothing at all.</li>
 * <li>Typing a region id into the Chunk field puts that 16-bit id at the cell's
 *     own offset in subfile 0, and nothing else moves.</li>
 * <li>An id past the end of FieldData is refused through {@link ctrmap.Ui},
 *     names the range that does exist, and the cell keeps what it had; -1 (an
 *     empty cell) is accepted and -2 is not.</li>
 * <li>Clearing "Allow LOD and Multizone" drops the whole zone-switch and LOD
 *     layer from the written file - 2,176 of matrix 1's 2,312 bytes - on the
 *     next save. The checkbox is read on EVERY save, not only when it is
 *     clicked.</li>
 * <li>Camera boundaries round-trip through the four bound fields in the file's
 *     own order (north, south, west, east, then the repeal flag), and any save
 *     at all rewrites subfile 1 down to just the entries the header counts,
 *     dropping the 260 trailing bytes retail ships.</li>
 * <li>New/Remove camera, and add/remove row/column, and what each does to the
 *     length of the written matrix.</li>
 * <li>The Multizone tool throws ClassCastException the moment a cell that names
 *     a zone is shown, because the zone-reference field is written as a Short
 *     by one path and read as an Integer by another. Pinned as it stands - see
 *     the report.</li>
 * </ol>
 *
 * Usage: java ctrmap.tests.MatrixEditFormGuardsTest &lt;pristine dump root&gt;
 */
public class MatrixEditFormGuardsTest {

	/** 8x8 regions, hasLOD 1 (so it carries the zone-switch and LOD layers), 3 camera boundaries. */
	static final int MATRIX = 1;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "no-dump-given");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump + " - the matrix form checks need matrix " + MATRIX);
			System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
			return;
		}
		ScratchGame.open(dump);
		//the zone dropdown names every row after its location; Workspace.validate
		//loads this in the app, and loadArchives (which ScratchGame calls) does not
		ctrmap.formats.text.LocationNames.loadFromGarc();
		CtrmapMainframe.mMtxPanel = new MapMatrixPanel();

		theMatrixIsTheOneTheseChecksDescribe();
		openingAndSavingUntouchedWritesNothing();
		typingARegionIdWritesItIntoTheCell();
		aRegionThatDoesNotExistIsRefusedAndSaid();
		clearingTheLodCheckboxDropsTheZoneLayer();
		cameraBoundsRoundTripAndTheSaveTruncatesSubfileOne();
		newAndRemoveCameraChangeTheEntryCount();
		rowsAndColumnsGrowAndShrinkTheWrittenMatrix();
		theMultizoneToolThrowsOnACellThatNamesAZone();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** The corpus these checks describe, asserted rather than assumed. */
	static void theMatrixIsTheOneTheseChecksDescribe() throws Exception {
		Fixture f = open();
		check(f.form.loaded && f.form.mm == f.mm, "the form loads matrix " + MATRIX + " with no window");
		check(f.mm.hasLOD == 1, "matrix " + MATRIX + " carries the LOD/zone-switch layer");
		check(f.mm.width == 8 && f.mm.height == 8, "and is 8x8 regions (found " + f.mm.width + "x" + f.mm.height + ")");
		check(f.mm.cambounds.size() == 3, "with 3 camera boundaries (found " + f.mm.cambounds.size() + ")");
		check(f.section0.length == 2312, "its matrix section is 2312 bytes: " + f.section0.length);
		check(f.section1.length == 324 && f.mm.assembleCamData().length == 64,
				"and its camera section is " + f.section1.length + " bytes on disk, of which the header counts "
				+ f.mm.assembleCamData().length);
		check(Arrays.equals(f.mm.assembleData(), f.section0),
				"the matrix section rebuilds byte for byte, so a difference below is the form's doing");
	}

	/** Load, save, and the container on disk must be the one that was read. */
	static void openingAndSavingUntouchedWritesNothing() throws Exception {
		Fixture f = open();
		byte[] whole = f.bytes();
		check(f.form.store(false), "storing an untouched matrix succeeds");
		check(Arrays.equals(f.bytes(), whole), "and writes nothing: the .mm file is byte for byte the one that was opened");
	}

	/**
	 * The chunk tool's one job: the id typed for a cell becomes that cell's
	 * 16-bit entry in the matrix section, at the offset the format puts it.
	 */
	static void typingARegionIdWritesItIntoTheCell() throws Exception {
		Fixture f = open();
		byte[] before = f.section0;
		f.form.showRegion(3, 2); //saves the previous cell first, then shows this one
		check(((Short) value(f.form, "chunkId")) == f.mm.ids.get(3, 2), "the Chunk field shows cell (3,2)'s region: " + value(f.form, "chunkId"));
		short want = 806;
		check(want != f.mm.ids.get(3, 2), "and 806 is not what it already holds (" + f.mm.ids.get(3, 2) + ")");

		((JFormattedTextField) field(f.form, "chunkId")).setValue(want);
		f.form.saveAll();
		check(f.mm.ids.get(3, 2) == want, "Save put region 806 in cell (3,2)");

		byte[] expect = before.clone();
		int at = 8 + (2 * f.mm.width + 3) * 2;
		expect[at] = (byte) (want & 0xFF);
		expect[at + 1] = (byte) ((want >> 8) & 0xFF);
		check(f.form.store(false), "the matrix stores");
		byte[] after = f.section0();
		check(Arrays.equals(after, expect), "and cell (3,2)'s two bytes at offset " + at
				+ " are the only ones that moved" + (Arrays.equals(after, expect) ? "" : " - first difference at " + firstDiff(after, expect)));
	}

	/**
	 * A region id FieldData does not have would name a file the game cannot
	 * open. The form refuses it, says which ids exist, and puts the cell's own
	 * id back in the field. -1 means "no region here" and is allowed.
	 */
	static void aRegionThatDoesNotExistIsRefusedAndSaid() throws Exception {
		Fixture f = open();
		int regions = Workspace.getArchive(ArchiveType.FIELD_DATA).length;
		check(regions > 0, "FieldData holds " + regions + " regions");
		f.form.showRegion(0, 0);
		short held = f.mm.ids.get(0, 0);

		for (short bogus : new short[]{(short) regions, (short) (regions + 5), -2}) {
			((JFormattedTextField) field(f.form, "chunkId")).setValue(bogus);
			List<String> said = ctrmap.Ui.record();
			try {
				f.form.saveAll();
			} finally {
				ctrmap.Ui.stopRecording();
			}
			check(f.mm.ids.get(0, 0) == held, "region " + bogus + " is refused and cell (0,0) keeps " + held);
			check(said.size() == 1 && said.get(0).contains("Region " + bogus + " does not exist"),
					"and the user is told which one: " + said);
			check(said.size() == 1 && said.get(0).contains("0.." + (regions - 1)),
					"naming the range that does exist: " + said);
			check(((Short) value(f.form, "chunkId")) == held, "and the field is put back to " + held);
		}

		((JFormattedTextField) field(f.form, "chunkId")).setValue((short) -1);
		List<String> said = ctrmap.Ui.record();
		try {
			f.form.saveAll();
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(f.mm.ids.get(0, 0) == -1 && said.isEmpty(), "-1 empties the cell with nothing to say: " + said);
	}

	/**
	 * "Allow LOD and Multizone" is read on every save, not only when it is
	 * clicked, and clearing it makes the next save write a matrix section with
	 * no zone-switch and no LOD layer at all - on this matrix, 136 bytes where
	 * there were 2312. Whether that should be possible without a warning is a
	 * question for the report; that it happens is the fact pinned here.
	 */
	static void clearingTheLodCheckboxDropsTheZoneLayer() throws Exception {
		Fixture f = open();
		JCheckBox lod = (JCheckBox) field(f.form, "allowExtended");
		check(lod.isSelected(), "the checkbox opened selected, because the matrix carries the layer");
		lod.setSelected(false);
		f.form.saveAll();
		check(f.mm.hasLOD == 0, "an unselected checkbox turns the flag off on the very next save");
		check(f.form.store(false), "the matrix stores");
		byte[] after = f.section0();
		check(after.length == 136, "and the written matrix section is 8 header bytes plus 64 cells: " + after.length + " bytes");
		check((after[0] & 0xFF) == 0 && (after[1] & 0xFF) == 0, "with the flag cleared in the file");
		check(Arrays.equals(Arrays.copyOfRange(after, 8, 136), Arrays.copyOfRange(f.section0, 8, 136)),
				"the 64 region ids survive; the 2176 bytes of zone-switch and LOD data do not");
	}

	/**
	 * The four bound fields are the camera record, in the file's order. Note
	 * what the save does to the section as a whole: retail ships 324 bytes of
	 * camera data with only three entries counted, and the writer emits just
	 * the counted ones, so the first save of any kind shortens it to 64.
	 */
	static void cameraBoundsRoundTripAndTheSaveTruncatesSubfileOne() throws Exception {
		Fixture f = open();
		selectTool(f.form, "btnCamTool");
		check(field(f.form, "cam") == null && ((JFormattedTextField) field(f.form, "northBound")).getValue() == null,
				"a freshly loaded matrix shows no camera at all: the dropdown is already on entry 0, so picking it fires nothing");
		//so the drive has to leave entry 0 and come back, which is what a user does
		f.form.setCam(1);
		f.form.setCam(0);
		MatrixCameraBoundaries cam = f.mm.cambounds.get(0);
		check(((Float) value(f.form, "northBound")) == cam.north && ((Float) value(f.form, "southBound")) == cam.south
				&& ((Float) value(f.form, "westBound")) == cam.west && ((Float) value(f.form, "eastBound")) == cam.east,
				"the four fields show camera 0: " + cam.north + "/" + cam.south + "/" + cam.west + "/" + cam.east);
		check(((JRadioButton) field(f.form, "btnBoundTypeRepeal")).isSelected() == (cam.isRepeal == 1),
				"and the type radio matches its repeal flag (" + cam.isRepeal + ")");

		((JFormattedTextField) field(f.form, "northBound")).setValue(-12.5f);
		((JFormattedTextField) field(f.form, "southBound")).setValue(1234.25f);
		((JFormattedTextField) field(f.form, "westBound")).setValue(-3.75f);
		((JFormattedTextField) field(f.form, "eastBound")).setValue(999.5f);
		((JRadioButton) field(f.form, "btnBoundTypeRepeal")).setSelected(true);
		f.form.saveCam();
		check(cam.north == -12.5f && cam.south == 1234.25f && cam.west == -3.75f && cam.east == 999.5f && cam.isRepeal == 1,
				"Save copies the four numbers and the flag into the record");

		check(f.form.store(false), "the matrix stores");
		byte[] after = f.section1();
		check(after.length == 64, "the camera section is now just the three counted entries: " + after.length
				+ " bytes, from " + f.section1.length);
		check(u32(after, 0) == 3, "with the count still 3");
		check(f32(after, 4) == -12.5f && f32(after, 8) == 1234.25f && f32(after, 12) == -3.75f && f32(after, 16) == 999.5f,
				"and camera 0 written north, south, west, east: "
				+ f32(after, 4) + "," + f32(after, 8) + "," + f32(after, 12) + "," + f32(after, 16));
		check(u32(after, 20) == 1, "then the repeal flag");
		check(Arrays.equals(Arrays.copyOfRange(after, 24, 64), Arrays.copyOfRange(f.section1, 24, 64)),
				"cameras 1 and 2 are written exactly as they were read");
	}

	/** New camera appends a default 0..100 repeal box; Remove takes the selected one. */
	static void newAndRemoveCameraChangeTheEntryCount() throws Exception {
		Fixture f = open();
		selectTool(f.form, "btnCamTool");
		f.form.setCam(0);
		invoke(f.form, "btnNewCamActionPerformed");
		check(f.mm.cambounds.size() == 4, "New camera added one: " + f.mm.cambounds.size());
		MatrixCameraBoundaries fresh = f.mm.cambounds.get(3);
		check(fresh.north == 0f && fresh.south == 100f && fresh.west == 0f && fresh.east == 100f && fresh.isRepeal == 1,
				"a 0..100 repeal box: " + fresh.north + "/" + fresh.south + "/" + fresh.west + "/" + fresh.east + "/" + fresh.isRepeal);
		check(((JComboBox<?>) field(f.form, "boundEntryBox")).getItemCount() == 4, "and a fourth row in the dropdown");
		check(f.form.store(false), "the matrix stores");
		check(u32(f.section1(), 0) == 4 && f.section1().length == 84, "the camera section counts 4 entries in 84 bytes: "
				+ u32(f.section1(), 0) + " in " + f.section1().length);

		MatrixCameraBoundaries second = f.mm.cambounds.get(1);
		f.form.setCam(1);
		invoke(f.form, "btnRemoveCamActionPerformed");
		check(f.mm.cambounds.size() == 3 && !f.mm.cambounds.contains(second), "Remove camera took the selected entry");
		check(((JComboBox<?>) field(f.form, "boundEntryBox")).getItemCount() == 3, "and its row");
	}

	/**
	 * Add/remove row and column change the matrix's own dimensions AND the
	 * three layers underneath it, so the written section grows and shrinks by
	 * the exact amount the format says.
	 */
	static void rowsAndColumnsGrowAndShrinkTheWrittenMatrix() throws Exception {
		Fixture f = open();
		invoke(f.form, "btnAddColActionPerformed");
		check(f.mm.width == 9 && f.mm.ids.getWidth() == 9, "Add column widened the matrix to " + f.mm.width);
		check(f.mm.LOD.getWidth() == 9 && f.mm.zones.getWidth() == 36, "with the LOD and zone-switch layers following (" + f.mm.zones.getWidth() + " sub-columns)");
		check(f.form.store(false), "the matrix stores");
		//8 header + 9*8 ids + 36*32 zones + 9*8 LOD, all 16-bit
		check(f.section0().length == 8 + (9 * 8 + 36 * 32 + 9 * 8) * 2, "the written section is now " + f.section0().length + " bytes");

		invoke(f.form, "btnAddRowActionPerformed");
		check(f.mm.height == 9 && f.mm.ids.getHeight() == 9 && f.mm.zones.getHeight() == 36, "Add row made it 9x9 (" + f.mm.zones.getHeight() + " sub-rows)");
		invoke(f.form, "btnRemoveRowActionPerformed");
		invoke(f.form, "btnRemoveColActionPerformed");
		check(f.mm.width == 8 && f.mm.height == 8 && f.mm.zones.getWidth() == 32 && f.mm.zones.getHeight() == 32,
				"and removing one of each puts every layer back to 8x8");
		check(f.form.store(false), "the matrix stores");
		check(f.section0().length == 2312, "the written section is back to its original length: " + f.section0().length);
	}

	/**
	 * The Multizone tool, pinned exactly as it behaves. {@code showRegion} sets
	 * the zone-reference field to a Short and then moves the dropdown, whose
	 * listener sets the same field to an Integer and hands it to
	 * {@code setZoneByNumber}, which casts it back to Short. The cast throws.
	 *
	 * <p>This check asserts the throw. It is here because a suite that
	 * asserted what the tool SHOULD do would go green the day somebody moves
	 * this file and quietly changes it; the report says what is wrong.
	 */
	static void theMultizoneToolThrowsOnACellThatNamesAZone() throws Exception {
		Fixture f = open();
		int cx = -1, cy = -1;
		for (int y = 0; y < f.mm.zones.getHeight() && cx == -1; y++) {
			for (int x = 0; x < f.mm.zones.getWidth() && cx == -1; x++) {
				if (f.mm.zones.get(x, y) > 0) {
					cx = x;
					cy = y;
				}
			}
		}
		if (cx == -1) {
			System.out.println("  skip: matrix " + MATRIX + " names no zone above 0, so the dropdown never has to move");
			return;
		}
		check(true, "matrix " + MATRIX + " sub-cell (" + cx + "," + cy + ") names zone " + f.mm.zones.get(cx, cy));
		selectTool(f.form, "btnMzTool");
		JComboBox<String> zones = combo(f.form, "zoneRefDropdown");
		check(zones.getItemCount() > f.mm.zones.get(cx, cy),
				"the zone dropdown covers it (" + zones.getItemCount() + " rows)");
		Throwable thrown = null;
		try {
			f.form.showRegion(cx, cy);
		} catch (Throwable t) {
			thrown = t;
		}
		check(thrown instanceof ClassCastException, "showing a Multizone cell throws " + thrown);
		check(thrown != null && String.valueOf(thrown.getMessage()).contains("Integer")
				&& String.valueOf(thrown.getMessage()).contains("Short"),
				"because an Integer meets a Short cast: " + (thrown == null ? "nothing thrown" : thrown.getMessage()));

		//and the same collision from the other side: Fill chunk reads the field
		//as an Integer while showRegion has just put a Short in it
		Fixture g = open();
		selectTool(g.form, "btnMzTool");
		((JFormattedTextField) field(g.form, "zoneRefNumber")).setValue((short) 0);
		Throwable fill = null;
		try {
			invoke(g.form, "btnFillChunkActionPerformed");
		} catch (Throwable t) {
			fill = t;
		}
		check(fill instanceof ClassCastException, "Fill chunk throws " + fill);
	}

	// ---- fixture -------------------------------------------------------

	/** A loaded matrix form with the .mm the suite may write to, plus the bytes it started from. */
	static class Fixture {

		MatrixEditForm form;
		MapMatrix mm;
		MM file;
		byte[] section0;
		byte[] section1;

		byte[] section0() {
			return file.getFile(0);
		}

		byte[] section1() {
			return file.getFile(1);
		}

		byte[] bytes() throws Exception {
			return java.nio.file.Files.readAllBytes(file.getOriginFile().toPath());
		}
	}

	/** One parsed zone, reused as every row of the zone dropdown (see {@link #open}). */
	private static Zone oneZone;

	/**
	 * Matrix {@link #MATRIX} on a FRESH scratch copy, in a form with no window.
	 * Fresh every time, because these checks write to it and each one describes
	 * what happens to the retail bytes.
	 *
	 * <p>The matrix is PARSED with the workspace closed and the flag put back
	 * afterwards: {@code MapMatrix} opens a GR for every populated cell when a
	 * workspace is live, which for this matrix means extracting sixty-four map
	 * regions the form never looks at. The form itself needs the workspace, for
	 * the FieldData range it refuses ids against.
	 *
	 * <p>{@code loadMatrix} fills the zone dropdown with one row per entry of
	 * {@code mZonePnl.zones}, and the form only ever uses those rows as a count
	 * and a selection index. So the array here is as long as the largest zone
	 * the matrix names and every row is the same parsed zone - the alternative
	 * is parsing five hundred zones to read one number off each.
	 */
	static Fixture open() throws Exception {
		Fixture f = new Fixture();
		File onDisk = Scratch.file("ctrmap_mtxform");
		java.nio.file.Files.write(onDisk.toPath(),
				Workspace.getArchive(ArchiveType.MAP_MATRIX).getDecompressedEntry(MATRIX));
		f.file = new MM(onDisk);
		//"no workspace is open" is now "there is no current session", so the parse
		//is done with the session uninstalled and put straight back afterwards
		WorkspaceSession live = Workspace.session();
		Workspace.install(null);
		try {
			f.mm = new MapMatrix(f.file);
		} finally {
			Workspace.install(live);
		}
		f.section0 = f.file.getFile(0);
		f.section1 = f.file.getFile(1);
		int maxZone = 0;
		for (int x = 0; x < f.mm.zones.getWidth(); x++) {
			for (int y = 0; y < f.mm.zones.getHeight(); y++) {
				maxZone = Math.max(maxZone, f.mm.zones.get(x, y));
			}
		}
		if (oneZone == null) {
			File zf = Scratch.file("ctrmap_mtxzone");
			java.nio.file.Files.write(zf.toPath(),
					Workspace.getArchive(ArchiveType.ZONE_DATA).getDecompressedEntry(0));
			oneZone = new Zone(new ctrmap.formats.containers.ZO(zf), Workspace.game());
		}
		Zone[] rows = new Zone[maxZone + 1];
		Arrays.fill(rows, oneZone);
		ZoneLoadingPanel pnl = new ZoneLoadingPanel();
		pnl.zones = rows;
		CtrmapMainframe.mZonePnl = pnl;
		f.form = new MatrixEditForm();
		CtrmapMainframe.mMtxEditForm = f.form;
		CtrmapMainframe.mMtxPanel.mm = f.mm;
		f.form.loadMatrix(f.mm);
		return f;
	}

	/** Picks one of the three tool radio buttons and runs the form's own switch. */
	static void selectTool(MatrixEditForm form, String button) throws Exception {
		((JRadioButton) field(form, button)).setSelected(true);
		form.switchTools();
	}

	@SuppressWarnings("unchecked")
	static JComboBox<String> combo(Object o, String name) throws Exception {
		return (JComboBox<String>) field(o, name);
	}

	static Object value(Object o, String name) throws Exception {
		return ((JFormattedTextField) field(o, name)).getValue();
	}

	static int u32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}

	static float f32(byte[] b, int o) {
		return Float.intBitsToFloat(u32(b, o));
	}

	static int firstDiff(byte[] a, byte[] b) {
		if (a.length != b.length) {
			return -a.length;
		}
		for (int i = 0; i < a.length; i++) {
			if (a[i] != b[i]) {
				return i;
			}
		}
		return -1;
	}

	static Object field(Object o, String name) throws Exception {
		Field fl = o.getClass().getDeclaredField(name);
		fl.setAccessible(true);
		return fl.get(o);
	}

	/** Calls one of the generated button handlers. */
	static void invoke(Object o, String name) throws Exception {
		Method m = o.getClass().getDeclaredMethod(name, java.awt.event.ActionEvent.class);
		m.setAccessible(true);
		try {
			m.invoke(o, (java.awt.event.ActionEvent) null);
		} catch (InvocationTargetException ex) {
			if (ex.getCause() instanceof Exception) {
				throw (Exception) ex.getCause();
			}
			throw ex;
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
