package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.LoadedZone;
import ctrmap.Workspace;
import ctrmap.formats.containers.MM;
import ctrmap.formats.mapmatrix.MapMatrix;
import ctrmap.formats.mapmatrix.MatrixCameraBoundaries;
import ctrmap.formats.zone.Zone;
import ctrmap.gamedef.ArchiveType;
import ctrmap.humaninterface.MapMatrixPanel;
import ctrmap.humaninterface.MatrixEditForm;
import ctrmap.humaninterface.MatrixSelector;
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

	/** The grid the matrix form draws on. A real panel: it needs no display to exist. */
	static final ctrmap.humaninterface.MapMatrixPanel CANVAS = new ctrmap.humaninterface.MapMatrixPanel();

	/** The 3D gizmo these forms move, so what they told it can be read back. */
	static final RecordingNavi NAVI = new RecordingNavi();
	/** The editors that show the zone, for the panels here: a spy that records and clears. */
	static final ZoneEditorsSpy ZONE_EDITORS = new ZoneEditorsSpy();

	/** The editor set the panels here flush: it records instead of saving. */
	static final RecordingEditors EDITORS = new RecordingEditors();

	/** The tool this suite holds: its own, so another suite may hold another. */
	static final ctrmap.humaninterface.tools.ToolSelection TOOLS = new ctrmap.humaninterface.tools.ToolSelection();


	/** 8x8 regions, hasLOD 1 (so it carries the zone-switch and LOD layers), 3 camera boundaries. */
	static final int MATRIX = 1;

	static int fails = 0;

	/**
	 * Where the grid image starts inside the panel is ONE statement, and it is
	 * allowed to be negative.
	 *
	 * <p>WHY BOTH HALVES MATTER. That expression was written out three times in
	 * three files - the panel's own painting, the form's camera-tool hit test,
	 * and the input router, which had to re-derive it in SCREEN coordinates
	 * because there was nowhere to ask. Three copies of "where does the picture
	 * start", each of which a change to the panel's layout would have to find.
	 *
	 * <p>And the sign is load-bearing. When the grid is larger than the panel the
	 * origin goes NEGATIVE, which is how a scrolled grid lines up with the mouse.
	 * Clamping it to zero looks like tidying and moves the picture away from the
	 * hit test: the user would click one region and select another.
	 */
	static void theGridOriginIsOneStatementAndMayBeNegative() throws Exception {
		System.out.println("--- where the grid image starts is one statement, and may be negative");
		MapMatrixPanel panel = new MapMatrixPanel();

		//an empty panel: no matrix, so the image is nothing and the origin is the
		//middle of whatever size it has been given
		panel.setSize(400, 300);
		java.awt.Point empty = panel.imageOrigin();
		check(empty.x == (400 - panel.getFullImageWidth()) / 2
			&& empty.y == (300 - panel.getFullImageHeight()) / 2,
			"the origin is the panel's own centring arithmetic: " + empty);

		//a panel SMALLER than its grid, which is the scrolled case
		panel.loadMatrix(open().mm);
		panel.setSize(50, 50);
		java.awt.Point scrolled = panel.imageOrigin();
		check(panel.getFullImageWidth() > 50 && panel.getFullImageHeight() > 50,
			"the loaded grid really is bigger than the panel (" + panel.getFullImageWidth()
			+ "x" + panel.getFullImageHeight() + "), or this proves nothing");
		check(scrolled.x < 0 && scrolled.y < 0,
			"AND THE ORIGIN IS NEGATIVE, because a clamped one would move the picture away"
			+ " from the hit test and the user would click one region and select another: "
			+ scrolled);
	}

	/**
	 * The cursor turns a point on the grid into a cell, with no editor around it.
	 *
	 * <p>THIS COULD NOT BE ASKED BEFORE. MatrixSelector.select read the matrix
	 * panel off the main window for the image size and the matrix size, and
	 * acqCurTile went further: picking a cell also called the FORM's showRegion
	 * and repainted the panel, both through the window. So "which cell is this
	 * point" and "tell the editor about it" were one indivisible thing, and a
	 * cursor needed an editor to exist at all.
	 *
	 * <p>The arithmetic is unchanged, including the floor and the sub-chunk
	 * multiplier - what changed is that the caller says which grid it means.
	 */
	static void theCursorTurnsAPointIntoACell() {
		System.out.println("--- the cursor turns a point on the grid into a cell, with no editor");
		MatrixSelector.selectSubChunks = false;
		MatrixSelector.select(0, 0, 800, 800, 8, 8);
		check(MatrixSelector.hilightRegionX == 0 && MatrixSelector.hilightRegionY == 0,
			"the top-left corner is cell 0,0");
		MatrixSelector.select(750, 350, 800, 800, 8, 8);
		check(MatrixSelector.hilightRegionX == 7 && MatrixSelector.hilightRegionY == 3,
			"and a point inside the last column lands in it, floored, not rounded up: "
			+ MatrixSelector.hilightRegionX + "," + MatrixSelector.hilightRegionY);

		//the sub-chunk multiplier is the same arithmetic times four
		MatrixSelector.selectSubChunks = true;
		MatrixSelector.select(750, 350, 800, 800, 8, 8);
		check(MatrixSelector.hilightRegionX == 30 && MatrixSelector.hilightRegionY == 14,
			"THE SUB-CHUNK GRID IS FOUR TIMES FINER, so the same point is a different cell: "
			+ MatrixSelector.hilightRegionX + "," + MatrixSelector.hilightRegionY);
		MatrixSelector.selectSubChunks = false;

		//and picking only picks: it no longer tells a form or repaints a panel,
		//which is why this whole section can run without either
		MatrixSelector.acqCurTile();
		check(MatrixSelector.selRegionX == MatrixSelector.hilightRegionX
			&& MatrixSelector.selRegionY == MatrixSelector.hilightRegionY,
			"picking copies the highlighted cell to the picked one, and does nothing else");
	}

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
		ctrmap.formats.text.LocationNames.loadFromGarc(Workspace.session());
		CtrmapMainframe.mMtxPanel = new MapMatrixPanel();

		theMatrixIsTheOneTheseChecksDescribe();
		theCursorTurnsAPointIntoACell();
		theGridOriginIsOneStatementAndMayBeNegative();
		openingAndSavingUntouchedWritesNothing();
		typingARegionIdWritesItIntoTheCell();
		aRegionThatDoesNotExistIsRefusedAndSaid();
		clearingTheLodCheckboxDropsTheZoneLayer();
		cameraBoundsRoundTripAndTheSaveTruncatesSubfileOne();
		newAndRemoveCameraChangeTheEntryCount();
		rowsAndColumnsGrowAndShrinkTheWrittenMatrix();
		theMultizoneToolThrowsOnACellThatNamesAZone();
		aToolButtonBeforeAMatrixIsLoadedDoesNothing();
		switchingToolsDropsACursorMeasuredInTheOtherScale();
		loadingAMatrixDropsTheCellPickedInTheLastOne();
		growingWithTheLayerOffStillGrowsWhatTheSaveReads();
		theGridAsksForTheRoomItsMatrixNeeds();
		aHoleInTheZoneTableLeavesTheFormHoldingNothing();

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

	/**
	 * The three tool buttons work before any matrix is open.
	 *
	 * <p>The Matrix Editor tab is added when the window is built and no tool
	 * radio is selected until {@code loadMatrix} picks one, so from a COLD
	 * START - workspace open, no zone loaded - the first click on any of the
	 * three arrived at {@code switchTools} with mm null. Two of the three
	 * enablers read {@code mm.hasLOD} whatever their argument is, and every
	 * branch calls at least one of them, so all three threw out of the listener:
	 * a stack trace on stderr, the radio left selected, nothing repainted.
	 */
	static void aToolButtonBeforeAMatrixIsLoadedDoesNothing() throws Exception {
		System.out.println("--- a tool button before any matrix is open");
		MatrixEditForm cold = new MatrixEditForm(new LoadedZone(), CANVAS);
		check(cold.mm == null && !cold.loaded, "a form that was never handed a matrix holds none");
		for (String button : new String[]{"btnChunkTool", "btnMzTool", "btnCamTool"}) {
			Throwable thrown = null;
			try {
				selectTool(cold, button);
			} catch (Throwable t) {
				thrown = t;
			}
			check(thrown == null, button + " does not throw with no matrix open: " + thrown);
		}
		check(cold.mm == null, "and the form still holds no matrix afterwards");
	}

	/**
	 * Switching tools drops a cursor measured in the scale it is leaving.
	 *
	 * <p>{@code MatrixSelector} multiplies a click by four when the multizone
	 * tool is up, and {@code showRegion} stores whatever it is handed, so the
	 * form's curRegX/curRegY are REGION coordinates under the chunk tool and
	 * SUB-CHUNK coordinates under the multizone tool. The switch moved the scale
	 * and left the coordinates standing, and the next {@code saveAll} wrote
	 * through them: past the width it is an IndexOutOfBounds, and inside the
	 * width it is a silent write into the WRONG region. saveAll is reached from
	 * the Save button, both spinners, the next click on the panel, and store() -
	 * which is the editor flush, so every zone switch and the window close.
	 *
	 * <p>Asserted as bytes, not as a field: the whole grid must come out of a
	 * save unchanged after a switch, because the form has nothing it is allowed
	 * to write.
	 */
	static void switchingToolsDropsACursorMeasuredInTheOtherScale() throws Exception {
		System.out.println("--- a tool switch drops a cursor it can no longer read");
		Fixture f = open();
		byte[] before = f.section0();
		selectTool(f.form, "btnMzTool");
		//a legal SUB-CHUNK coordinate, and past the region width (8) on purpose:
		//it is the pair that used to reach mm.ids.set under the chunk tool
		int sub = 4 * f.mm.width - 1;
		check(sub >= f.mm.width, "sub-chunk column " + sub + " is off the region grid ("
			+ f.mm.width + " wide), which is the point");
		f.form.showRegion(sub, 0);
		check(((Integer) field(f.form, "curRegX")) == sub, "the multizone tool picked it");
		selectTool(f.form, "btnChunkTool");
		check(((Integer) field(f.form, "curRegX")) == -1
			&& ((Integer) field(f.form, "curRegY")) == -1,
			"switching to the chunk tool drops it, because the number means nothing there");
		check(MatrixSelector.selRegionX == -1 && MatrixSelector.selRegionY == -1,
			"and the picked rectangle goes with it, so nothing is drawn on a cell nobody chose");
		Throwable thrown = null;
		try {
			f.form.saveAll();
		} catch (Throwable t) {
			thrown = t;
		}
		check(thrown == null, "a save straight after the switch does not throw: " + thrown);
		check(f.form.store(false), "and the matrix stores");
		check(firstDiff(before, f.section0()) == -1,
			"with the grid byte-identical - the switch left nothing to write (first difference at "
			+ firstDiff(before, f.section0()) + ")");
	}

	/**
	 * Loading a matrix drops the cell picked in the last one.
	 *
	 * <p>{@code MatrixSelector.unfocus} existed since the class was written and
	 * had no caller anywhere - every {@code unfocus} in the program is the TILE
	 * cursor's twin - so selRegionX/selRegionY survived every zone load. The
	 * form went back to showing region 0,0 while the red picked-cell rectangle
	 * stayed on the cell the PREVIOUS matrix was clicked at, and on a smaller
	 * matrix it was drawn off the grid onto empty white. The user was told they
	 * had picked a cell they had not, in a matrix that no longer has one.
	 */
	static void loadingAMatrixDropsTheCellPickedInTheLastOne() throws Exception {
		System.out.println("--- loading a matrix drops the cell picked in the last one");
		Fixture f = open();
		MatrixSelector.selRegionX = 5;
		MatrixSelector.selRegionY = 5;
		f.form.loadMatrix(f.mm);
		check(MatrixSelector.selRegionX == -1 && MatrixSelector.selRegionY == -1,
			"a load clears the picked cell (" + MatrixSelector.selRegionX + ","
			+ MatrixSelector.selRegionY + ")");
		MatrixSelector.selRegionX = 5;
		MatrixSelector.selRegionY = 5;
		f.form.loadMatrix(null);
		check(MatrixSelector.selRegionX == -1 && MatrixSelector.selRegionY == -1,
			"and so does an unload, which is why the clear is above the mm != null test");
	}

	/**
	 * Growing the grid with the LOD checkbox OFF still grows the layers the
	 * save reads when it is ticked back on.
	 *
	 * <p>All four resize handlers used to move the LOD and zone-switch layers
	 * only while {@code hasLOD} was 1, while the width and height those layers
	 * are INDEXED BY moved unconditionally. {@code assembleData} reads them by
	 * the flag as it stands AT SAVE TIME, and the flag is a checkbox. So: untick
	 * it, add a column, tick it back on, save - and the write walked off the end
	 * of a layer that never grew. assembleData catches IOException only, so the
	 * IndexOutOfBounds escaped store() before the user was asked anything, and
	 * the matrix was not written.
	 */
	static void growingWithTheLayerOffStillGrowsWhatTheSaveReads() throws Exception {
		System.out.println("--- growing with the LOD box off still grows what the save reads");
		Fixture f = open();
		JCheckBox lod = (JCheckBox) field(f.form, "allowExtended");
		check(lod.isSelected() && f.mm.hasLOD == 1, "matrix " + MATRIX + " opens with the layer on");
		lod.setSelected(false);
		invoke(f.form, "allowExtendedActionPerformed");
		invoke(f.form, "btnAddColActionPerformed");
		check(f.mm.width == 9, "the grid is " + f.mm.width + " wide with the box unticked");
		check(f.mm.LOD.getWidth() == 9 && f.mm.zones.getWidth() == 36,
			"and both layers grew anyway (LOD " + f.mm.LOD.getWidth() + ", zones "
			+ f.mm.zones.getWidth() + ") - they are indexed by a width that moved");
		lod.setSelected(true);
		invoke(f.form, "allowExtendedActionPerformed");
		Throwable thrown = null;
		boolean stored = false;
		try {
			stored = f.form.store(false);
		} catch (Throwable t) {
			thrown = t;
		}
		check(thrown == null, "ticking the box back on and saving does not throw: " + thrown);
		check(stored, "and the matrix stores");
		check(f.section0().length == 8 + (9 * 8 + 36 * 32 + 9 * 8) * 2,
			"with every layer written at the new width (" + f.section0().length + " bytes)");
	}

	/**
	 * The grid asks its scroll pane for the room its matrix needs.
	 *
	 * <p>{@code MapMatrixPanel} never sized itself, so the viewport took its
	 * preferred size as exactly the extent: the scrollbars' condition could
	 * never be true and no scrollbar could appear. A matrix wider or taller than
	 * the pane was then drawn from a NEGATIVE origin and clipped on all four
	 * sides, and the regions outside could not be seen, selected or edited at
	 * all. Add column reaches that in a few clicks, 100 pixels at a time.
	 */
	static void theGridAsksForTheRoomItsMatrixNeeds() throws Exception {
		System.out.println("--- the grid asks for the room its matrix needs");
		Fixture f = open();
		MapMatrixPanel bare = new MapMatrixPanel();
		java.awt.Dimension empty = bare.getPreferredSize();
		bare.mm = f.mm;
		java.awt.Dimension asked = bare.getPreferredSize();
		check(asked.width == bare.getFullImageWidth() && asked.height == bare.getFullImageHeight(),
			"a panel holding an " + f.mm.width + "x" + f.mm.height + " matrix asks for "
			+ asked.width + "x" + asked.height + ", the size of the picture it draws");
		check(asked.width > empty.width || asked.height > empty.height,
			"which is more than the default it asked for while holding nothing ("
			+ empty.width + "x" + empty.height + ")");
		int wasWidth = asked.width;
		invoke(f.form, "btnAddColActionPerformed");
		check(bare.getPreferredSize().width > wasWidth,
			"and adding a column asks for more room again (" + wasWidth + " -> "
			+ bare.getPreferredSize().width + "), which is what puts a scrollbar there");
	}

	/**
	 * A hole in the zone table leaves the form holding NOTHING, and saying so.
	 *
	 * <p>{@code this.mm} was assigned as the second statement of loadMatrix's
	 * try and the ready flag was the LAST statement inside it, so a throw in
	 * between - the zone dropdown fill dereferences {@code loadedZone.at(i)},
	 * documented to return null for a slot the rebuild could not fill - left the
	 * form holding a matrix with {@code loaded} still false. saveAll is gated on
	 * "mm != null && loaded", so from that moment every chunk id, LOD, multizone
	 * and camera boundary the user typed was dropped, including through store(),
	 * which File > Save and the window's close handler both run. The form looked
	 * loaded, wrote nothing, and the only trace was a stack trace on stderr.
	 */
	static void aHoleInTheZoneTableLeavesTheFormHoldingNothing() throws Exception {
		System.out.println("--- a hole in the zone table leaves the form holding nothing");
		Fixture f = open();
		LoadedZone lz = (LoadedZone) field(f.form, "loadedZone");
		check(lz.count() > 3, "the fixture table has " + lz.count() + " rows to hole");
		lz.replace(3, null);
		List<String> said = ctrmap.Ui.record();
		try {
			f.form.loadMatrix(f.mm);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said.size() == 1 && said.get(0).contains("could not be shown"),
			"the failure reaches the user: " + said);
		check(said.size() == 1 && said.get(0).contains("holding nothing"),
			"and says what the editor is holding now");
		check(f.form.mm == null && !f.form.loaded,
			"the two flags agree: no matrix, not loaded");
		byte[] before = f.section0();
		f.form.saveAll();
		check(f.form.store(false), "a store after the failed load still answers");
		check(firstDiff(before, f.section0()) == -1,
			"and writes nothing, because there is nothing to write");
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
	 * the zone table it is handed, and the form only ever uses those rows as a count
	 * and a selection index. So the array here is as long as the largest zone
	 * the matrix names and every row is the same parsed zone - the alternative
	 * is parsing five hundred zones to read one number off each.
	 */
	static Fixture open() throws Exception {
		Fixture f = new Fixture();
		File onDisk = Scratch.file("ctrmap_mtxform");
		java.nio.file.Files.write(onDisk.toPath(),
				Workspace.getArchive(ArchiveType.MAP_MATRIX).getDecompressedEntry(MATRIX));
		f.file = new MM(onDisk, Workspace.session());
		//the grid only, no region opened per cell: what "no workspace is open"
		//used to mean to this parse, now said to it directly instead of by
		//uninstalling the session around the call
		f.mm = new MapMatrix(f.file, null);
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
			oneZone = new Zone(new ctrmap.formats.containers.ZO(zf, Workspace.session()), Workspace.game());
		}
		Zone[] rows = new Zone[maxZone + 1];
		Arrays.fill(rows, oneZone);
		LoadedZone lz = new LoadedZone();
		lz.table(rows);
		ZoneLoadingPanel pnl = new ZoneLoadingPanel(lz, TOOLS, EDITORS, ZONE_EDITORS, NAVI);
		CtrmapMainframe.mZonePnl = pnl;
		f.form = new MatrixEditForm(lz, CANVAS);
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
