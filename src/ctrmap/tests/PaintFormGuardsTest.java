package ctrmap.tests;

import ctrmap.LoadedZone;
import ctrmap.Workspace;
import ctrmap.formats.containers.GR;
import ctrmap.formats.h3d.BuildingCatalog;
import ctrmap.formats.tilemap.PaintedGeometry;
import ctrmap.formats.tilemap.TilePalette;
import ctrmap.gamedef.ArchiveType;
import ctrmap.humaninterface.PaintForm;
import ctrmap.humaninterface.UndoHistory;
import ctrmap.humaninterface.ZoneLoadingPanel;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JLabel;
import ctrmap.formats.tilemap.PaintedHeights;

/**
 * The Map Painter's document, driven without a window: what a click with the
 * ramp tool does and says, what a drag settles, what undo brings back, what
 * the overlay draws, and what the zone label says when the map's own ground
 * is missing. Every one of these was in place and asserted by nothing -
 * mutation testing turned each around and the whole battery stayed green,
 * because no suite had ever constructed the form:
 *
 * <ol>
 * <li>The ramp tool's first click takes the way down the gradient suggests,
 *     each click after turns the ramp to the next lower side, and a tile with
 *     nothing lower beside it says so instead of silently staying flat.</li>
 * <li>A drag-painted tile settles every ramp, so painting grass over the void
 *     a ramp descended to turns or clears the ramp rather than leaving one
 *     the builder refuses.</li>
 * <li>"Fill all with brush" did not settle them: a ramp left pointing at a
 *     filled void stayed on the map, and the next repaint threw out of the
 *     event thread. Undo restores the ramp grid as the int grid it now is; a
 *     stale boolean cast threw on the first undo.</li>
 * <li>The overlay draws the arrow on ramp tiles and nowhere else.</li>
 * <li>Seeding names the tiles that had no ground of their own and took a
 *     neighbour's - a fact the label alone carries.</li>
 * <li>Picking a building out of the palette says which one is about to be
 *     placed, and whether its passengers are coming - the second having no
 *     other trace once the palette has closed.</li>
 * <li>A refused Apply names what stopped it, promises the map is untouched,
 *     and takes the painted preview back out of the 3D scene.</li>
 * </ol>
 *
 * Usage: java ctrmap.tests.PaintFormGuardsTest &lt;pristine-dump-root&gt;
 */
public class PaintFormGuardsTest {


	/** The zone list an apply rebuilds, so the sequence can be read back. */
	static final RecordingZoneList ZONES = new RecordingZoneList();

	/** The 3D scene the map view shares: a recorder, so what it was told can be read. */
	static final RecordingScene SCENE = new RecordingScene();

	/** The 3D gizmo these forms move, so what they told it can be read back. */
	static final RecordingNavi NAVI = new RecordingNavi();
	/** The editors that show the zone, for the panels here: a spy that records and clears. */
	static final ZoneEditorsSpy ZONE_EDITORS = new ZoneEditorsSpy();

	/** The editor set the panels here flush: it records instead of saving. */
	static final RecordingEditors EDITORS = new RecordingEditors();
	/** The workspace history a painter keeps its Apply, ripple and forks in (F209c). */
	static final ctrmap.humaninterface.SaveHistory OPS = new ctrmap.humaninterface.SaveHistory(
			() -> java.util.Collections.<Object>emptyList());

	/** The tool this suite holds: its own, so another suite may hold another. */
	static final ctrmap.humaninterface.tools.ToolSelection TOOLS = new ctrmap.humaninterface.tools.ToolSelection();


	static final int DIM = PaintedGeometry.DIM;
	static final int ZONE = 7;
	/**
	 * The arrow's colour, (255, 210, 40), as the int a pixel reads. AN INT, NOT A COLOR: a Color built
	 * when this class loads asks AWT whether there is a screen before main's Headless.require() can say
	 * there is not - measured, and this suite's switch had never been in force until Headless asked.
	 */
	static final int ARROW = 0xFFFFD228;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		Headless.require();
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		aDocumentWithNoMapView();
		thePainterRefusesToBeBuiltWithoutTheEditors();
		thePainterRefusesToBeBuiltWithoutAHistory();
		rampToolTurnsAndSays();
		dragSettlesRamps();
		fillAllSettlesRamps();
		undoRestoresRamps();
		aStrokeAndAZoneEditAreOneHistory();
		aReseedForgetsTheCanvasAndKeepsTheZone();
		overlayMarksRampsOnly();
		pickingABuildingArmsTheNextClick();
		aRefusedApplySaysSoAndPutsTheMapBack();
		anApplyOverAZoneThatIsNotOpenChangesNothing();
		aPreviewThatCannotBeRebuiltSaysSo();
		thePreviewNoteSaysWhyAndClears();
		aDonorAreaThatWillNotLoadIsNamed();
		everyTileIsABrush();
		aSignsFormMakesASignOnOkAndSceneryOnCancel();
		seedLabelCountsBorrowedTiles(dump);
		noMapViewMeansNoSceneToPutBack(dump);
		activatedThePainterSeedsANewZoneAndKeepsItsOwn(dump);
		makeWaterRippleStopsWhenTheAreaIsNotMadeItsOwn(dump);
		aPreviewThatFailsSaysSoUnderApply(dump);
		applyWarnsOfStillWaterOnlyWhereThereIsWater(dump);
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * The painter refuses to be built without the editors it flushes.
	 *
	 * <p>Applying a paint saves every open editor first and gives up if one
	 * refuses - that is the whole reason it holds the list. Built with none it
	 * would throw at apply time instead, having already let the user paint.
	 */
	static void thePainterRefusesToBeBuiltWithoutTheEditors() {
		System.out.println("--- the painter refuses to be built without the editors it flushes");
		String said = "(nothing was thrown)";
		try {
			new PaintForm(new LoadedZone(), null, ZONES, TestWindow.mTileMapPanel(), history(), OPS, () -> { });
		} catch (IllegalArgumentException refused) {
			said = String.valueOf(refused.getMessage());
		}
		check(said.contains("must be handed the editors"),
				"because an apply flushes them before it writes anything: " + said);
	}

	/** A form seeded by hand: zone ZONE open, cell (0,0), all grass at level 0, no ramps. */
	static PaintForm document() throws Exception {
		return document(history());
	}

	/** The same, its canvas a document of {@code history}. */
	static PaintForm document(UndoHistory history) throws Exception {
		LoadedZone lz = new LoadedZone();
		lz.open(ZONE, null);                      //zone ZONE's index and no zone object: the document reads the index
		ZoneLoadingPanel pnl = new ZoneLoadingPanel(lz, TOOLS, EDITORS, ZONE_EDITORS, NAVI, () -> { }, () -> { }, UndoHistoryTest.overOneNumber(new int[]{0}), new ctrmap.humaninterface.SaveHistory(() -> java.util.Collections.<Object>emptyList()));
		TestWindow.mZonePnl(pnl);
		TestWindow.mTileMapPanel(null);
		PaintForm form = new PaintForm(lz, EDITORS, ZONES, TestWindow.mTileMapPanel(), history, OPS, () -> { });
		set(form, "seededZone", ZONE);
		TilePalette[][] grid = (TilePalette[][]) get(form, "grid");
		for (TilePalette[] row : grid) {
			Arrays.fill(row, TilePalette.GRASS);
		}
		return form;
	}

	/** The notch: a level-1 plateau over y &lt;= 18 with one more plateau tile at (20,19). */
	static PaintForm notch() throws Exception {
		return notch(history());
	}

	static PaintForm notch(UndoHistory history) throws Exception {
		PaintForm form = document(history);
		int[][] height = (int[][]) get(form, "height");
		for (int y = 0; y <= 18; y++) {
			Arrays.fill(height[y], 1);
		}
		height[19][20] = 1;
		return form;
	}

	/**
	 * The document driven with no map view. Every check below is one, because
	 * these tests have no window, so the null test in repaintMap is what makes
	 * the rest of this suite possible at all - and that made it an accident of
	 * the fixture rather than something anything says. Dropping it does not
	 * make a check fail; it kills the run with a NullPointerException four
	 * checks in, and a stack trace is not a sentence about what broke. Said
	 * once, first, in its own words.
	 */
	static void aDocumentWithNoMapView() throws Exception {
		PaintForm form = document();
		try {
			form.gesturePress(5, 5, false);
			check(true, "a gesture on a document with no map view repaints nothing, and throws nothing");
		} catch (RuntimeException ex) {
			check(false, "a gesture on a document with no map view threw " + ex);
		}
	}

	static void rampToolTurnsAndSays() throws Exception {
		PaintForm form = notch();
		int[][] ramp = (int[][]) get(form, "ramp");
		JLabel status = (JLabel) get(form, "placeStatus");
		set(form, "ptool", 4);
		form.gesturePress(20, 19, false);
		check(ramp[19][20] == 2, "the first click on the notch takes the gradient's way down, south (" + ramp[19][20] + ")");
		check(status.getText().trim().isEmpty(), "and has nothing to complain about: \"" + status.getText() + "\"");
		form.gesturePress(20, 19, false);
		check(ramp[19][20] == 0, "the second click turns the ramp to the next lower side, east (" + ramp[19][20] + ")");
		form.gesturePress(20, 19, false);
		check(ramp[19][20] == 1, "the third turns it west (" + ramp[19][20] + ")");
		form.gesturePress(5, 5, false);
		check(ramp[5][5] == PaintedGeometry.NO_RAMP, "a flat tile cannot be a ramp");
		check(status.getText().contains("No lower ground"), "and the painter says so: \"" + status.getText() + "\"");
	}

	static void dragSettlesRamps() throws Exception {
		PaintForm form = document();
		TilePalette[][] grid = (TilePalette[][]) get(form, "grid");
		int[][] height = (int[][]) get(form, "height");
		int[][] ramp = (int[][]) get(form, "ramp");
		//a raised tile ramped east onto a void tile that shares its level
		height[20][20] = 1;
		height[20][21] = 1;
		grid[20][21] = TilePalette.VOID;
		ramp[20][20] = 0;
		set(form, "ptool", 0);
		form.gestureDrag(21, 20, false);
		check(grid[20][21] == TilePalette.GRASS, "the drag painted the void tile");
		check(ramp[20][20] == 1, "and the ramp that descended onto it turned to the side that is still lower, west (" + ramp[20][20] + ")");
	}

	static void fillAllSettlesRamps() throws Exception {
		PaintForm form = document();
		TilePalette[][] grid = (TilePalette[][]) get(form, "grid");
		int[][] height = (int[][]) get(form, "height");
		int[][] ramp = (int[][]) get(form, "ramp");
		//a raised tile ramped east onto a void tile that shares its level, then the whole cell filled
		height[20][20] = 1;
		height[20][21] = 1;
		grid[20][21] = TilePalette.VOID;
		ramp[20][20] = 0;
		((JButton) get(form, "fillAll")).doClick();
		check(grid[20][21] == TilePalette.GRASS, "fill all painted the void tile");
		check(ramp[20][20] == 1, "and settled the ramp that descended onto it, west (" + ramp[20][20] + ")");
		try {
			form.drawOverlay(new BufferedImage(DIM * 12, DIM * 12, BufferedImage.TYPE_INT_ARGB).getGraphics(), 0, 0, 12);
			check(true, "the next repaint has nothing to throw about");
		} catch (RuntimeException ex) {
			check(false, "the next repaint threw " + ex);
		}
	}

	/** A history over one number of its own; a painter built over it adds its canvas to it. */
	static UndoHistory history() {
		return UndoHistoryTest.overOneNumber(new int[]{0});
	}

	/**
	 * The painter refuses to be built without the zone's history (F209). Built with none, its strokes
	 * would be taken back by nothing, or by stacks of its own that Ctrl+Z on the map never reaches.
	 */
	static void thePainterRefusesToBeBuiltWithoutAHistory() {
		System.out.println("--- the painter refuses to be built without the zone's history");
		String said = "(nothing was thrown)";
		try {
			new PaintForm(new LoadedZone(), EDITORS, ZONES, TestWindow.mTileMapPanel(), null, OPS, () -> { });
		} catch (RuntimeException refused) {   //any throw is caught, so one that is not the refusal fails in words
			said = refused.getClass().getSimpleName() + ": " + refused.getMessage();
		}
		check(said.startsWith("IllegalArgumentException") && said.contains("must be handed the zone's history"), "because its canvas is a document of it: " + said);
	}

	/**
	 * A stroke and an edit to the zone are one history (F209): Undo takes back the newest, whichever
	 * editor made it, and the painter's buttons light from that history. The painter kept two stacks of
	 * its own, so its Undo never reached an NPC moved before the stroke, and Ctrl+Z on the map never
	 * reached the stroke.
	 */
	static void aStrokeAndAZoneEditAreOneHistory() throws Exception {
		System.out.println("--- a stroke and an edit to the zone are taken back by one Undo, newest first");
		int[] npc = {0};
		UndoHistory zone = UndoHistoryTest.overOneNumber(npc);
		PaintForm form = notch(zone);
		int[][] ramp = (int[][]) get(form, "ramp");
		JButton undo = (JButton) get(form, "undoBtn");
		JButton redo = (JButton) get(form, "redoBtn");
		check(!undo.isEnabled() && !redo.isEnabled(), "control: nothing done, both buttons dark");
		npc[0] = 5;
		zone.settle();
		set(form, "ptool", 4);
		form.gesturePress(20, 19, false);
		zone.settle();
		check(ramp[19][20] == 2 && "the painter's canvas".equals(zone.undoName()),
				"the stroke is the newest edit of the zone's history: " + zone.undoName());
		check(undo.isEnabled() && !redo.isEnabled(), "and the painter's Undo lights from that history");
		undo.doClick();
		check(ramp[19][20] == PaintedGeometry.NO_RAMP && npc[0] == 5,
				"the painter's Undo takes back the stroke and leaves the zone's edit (ramp " + ramp[19][20] + ", npc " + npc[0] + ")");
		undo.doClick();
		check(npc[0] == 0 && ramp[19][20] == PaintedGeometry.NO_RAMP,
				"and the next takes back the edit made before it, in another editor (npc " + npc[0] + ")");
		check(!undo.isEnabled() && redo.isEnabled(), "the buttons follow: nothing left to take back, two to put again");
		redo.doClick();
		redo.doClick();
		check(npc[0] == 5 && ramp[19][20] == 2, "and Redo puts both again, in the order they were made");
	}

	/**
	 * Re-seeding starts the canvas over in place (F209): its old strokes leave the history, because none
	 * of them could be put on the new canvas, while the zone's own edits stay. The history cannot see a
	 * canvas start over - the subject is the same painter - so the painter has to say so.
	 */
	static void aReseedForgetsTheCanvasAndKeepsTheZone() throws Exception {
		System.out.println("--- a re-seed forgets the canvas's strokes and keeps the zone's edits");
		int[] npc = {0};
		UndoHistory zone = UndoHistoryTest.overOneNumber(npc);
		PaintForm form = notch(zone);
		npc[0] = 5;
		zone.settle();
		set(form, "ptool", 4);
		form.gesturePress(20, 19, false);
		zone.settle();
		check("the painter's canvas".equals(zone.undoName()), "fixture: the stroke is the newest edit (" + zone.undoName() + ")");
		Method seed = PaintForm.class.getDeclaredMethod("seed");
		seed.setAccessible(true);
		seed.invoke(form);
		check("the number".equals(zone.undoName()), "after the re-seed the newest edit is the zone's again: " + zone.undoName());
		zone.settle();
		check("the number".equals(zone.undoName()), "and the re-seeded canvas, settled, is no edit: it was seen afresh ("
				+ zone.undoName() + ")");
		check(zone.undo() && npc[0] == 0 && !zone.canUndo(), "so Undo takes back the zone's edit, and that is all there was");
	}

	static void undoRestoresRamps() throws Exception {
		PaintForm form = notch();
		int[][] ramp = (int[][]) get(form, "ramp");
		UndoHistory history = (UndoHistory) get(form, "history");
		set(form, "ptool", 4);
		history.settle();   //what the gesture hook does on the press: the canvas as it was
		form.gesturePress(20, 19, false);
		history.settle();   //and on the release: what the click changed is the edit
		check(ramp[19][20] == 2, "fixture: a ramp was placed");
		try {
			((JButton) get(form, "undoBtn")).doClick();
			check(ramp[19][20] == PaintedGeometry.NO_RAMP, "undo takes the ramp away again (" + ramp[19][20] + ")");
			((JButton) get(form, "redoBtn")).doClick();
			check(ramp[19][20] == 2, "redo brings it back (" + ramp[19][20] + ")");
		} catch (RuntimeException ex) {
			check(false, "undo threw " + ex);
		}
	}

	static void overlayMarksRampsOnly() throws Exception {
		PaintForm form = notch();
		set(form, "ptool", 4);
		form.gesturePress(20, 19, false);
		BufferedImage img = new BufferedImage(DIM * 12, DIM * 12, BufferedImage.TYPE_INT_ARGB);
		form.drawOverlay(img.getGraphics(), 0, 0, 12);
		check(new Color(img.getRGB(20 * 12 + 6, 19 * 12 + 6)).getRGB() == ARROW, "the overlay draws the arrow on the ramp tile");
		int stray = 0;
		for (int ly = 0; ly < DIM; ly++) {
			for (int lx = 0; lx < DIM; lx++) {
				if ((lx != 20 || ly != 19) && new Color(img.getRGB(lx * 12 + 6, ly * 12 + 6)).getRGB() == ARROW) {
					stray++;
				}
			}
		}
		check(stray == 0, "and on no other tile (" + stray + " stray arrows)");
	}

	/**
	 * Picking a building out of the palette arms the next map click, and the
	 * label says which building - and whether its passengers are coming.
	 *
	 * <p>Nothing else on screen distinguishes "the next click places a Pokemon
	 * Center" from "the next click paints grass", and the passengers answer has
	 * no other trace at all: it is made in a modal dialog that is gone by the
	 * time the building lands, and a room placed without its floors looks like
	 * a room until you walk into it. Both halves are checked against a click
	 * that really places the entry, so the label is tied to what the form will
	 * actually do rather than being a sentence of its own.
	 */
	static void pickingABuildingArmsTheNextClick() throws Exception {
		PaintForm form = document();
		JLabel status = (JLabel) get(form, "placeStatus");
		BuildingCatalog.Entry house = new BuildingCatalog.Entry();
		house.kind = "A_BUILDING";
		house.name = "Littleroot house";
		house.donorRegion = 510;
		house.donorArea = 114;
		house.tx0 = 6;
		house.ty0 = 6;
		house.tx1 = 12;
		house.ty1 = 12;

		@SuppressWarnings("unchecked")
		List<ctrmap.MapPainter.Placed> placed = (List<ctrmap.MapPainter.Placed>) get(form, "placed");

		form.beginPlacing(house, true);
		String withRiders = status.getText();
		check(withRiders.contains("Placing: " + house.name) && withRiders.contains("click the map"),
				"picking a building says which one, and what to do next: " + withRiders);
		check(!withRiders.contains("passengers"), "and says nothing about passengers when they are coming: " + withRiders);
		form.gesturePress(3, 4, false);
		check(placed.size() == 1 && get(placed.get(0), "e") == house && Boolean.TRUE.equals(get(placed.get(0), "passengers")),
				"and the next click really places that building, passengers and all (" + placed + ")");
		check(status.getText().trim().isEmpty(), "after which the label is clear again: \"" + status.getText() + "\"");

		placed.clear();
		form.beginPlacing(house, false);
		String without = status.getText();
		check(without.contains("Placing: " + house.name) && without.contains("passengers left behind"),
				"picking it with the passengers box cleared says they are staying: " + without);
		form.gesturePress(3, 4, false);
		check(placed.size() == 1 && Boolean.FALSE.equals(get(placed.get(0), "passengers")),
				"and the click places it without them (" + placed + ")");
	}

	/**
	 * Census H.50: Apply asks that the painted document is the OPEN zone's. It asked only whether a document was
	 * seeded, so after a failed load - the zone closed at its index, the painter never told - it threw on the
	 * missing header, or wrote the previous zone's paint over the region the matrix's first cell belongs to.
	 */
	static void anApplyOverAZoneThatIsNotOpenChangesNothing() throws Exception {
		System.out.println("--- Apply over a zone that is not open changes nothing, and says so (census H.50)");
		PaintForm form = document();              //zone ZONE's index and no zone object: what a failed load leaves
		((boolean[][]) get(form, "touched"))[0][0] = true;
		check(!form.paintsTheOpenZone(), "control: the document's zone is not the one open");
		java.lang.reflect.Method apply = PaintForm.class.getDeclaredMethod("applyAction");
		apply.setAccessible(true);
		List<String> said = ctrmap.Ui.record();
		try {
			apply.invoke(form);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said.size() == 1 && said.get(0).contains("Nothing was changed") && said.get(0).contains("not the one open"),
				"Apply refuses in words, and never asks whether to apply: " + said);
	}

	/**
	 * A placed sign's text form: OK makes it a sign with what was typed, Cancel keeps it scenery (the 2026-10-16
	 * assembly, PaintForm 1348, negate-if, SURVIVED: no suite answered the form). Inverted, OK threw the text away and
	 * Cancel made a sign of nothing.
	 */
	static void aSignsFormMakesASignOnOkAndSceneryOnCancel() {
		System.out.println("--- a sign's text form: OK makes a sign, Cancel keeps it scenery");
		BuildingCatalog.Entry e = new BuildingCatalog.Entry();
		e.name = "town sign";
		PaintForm.Prompts asks = new PaintForm.Prompts(null, null, () -> { });
		ctrmap.MapPainter.SignText[] got = new ctrmap.MapPainter.SignText[2];
		for (int k = 0; k < 2; k++) {
			List<String> said = ctrmap.Ui.record(k == 0 ? javax.swing.JOptionPane.OK_OPTION : javax.swing.JOptionPane.CANCEL_OPTION);
			try {
				got[k] = asks.signText(new ctrmap.MapPainter.Placed(e, 3, 4));
			} finally {
				ctrmap.Ui.stopRecording();
			}
		}
		check(got[0] != null && "".equals(got[0].text) && got[0].style == 0 && got[1] == null,
				"OK on a sign's form makes it a sign with what was typed, Cancel keeps it scenery: OK gave "
				+ (got[0] == null ? "scenery" : "a sign \"" + got[0].text + "\"") + ", Cancel "
				+ (got[1] == null ? "scenery" : "a sign \"" + got[1].text + "\""));
	}

	/**
	 * The painter offers every tile but VOID, in the order the palette declares them (census L.3). The brush list was
	 * kept by hand beside the enum, so a tile added to the enum was silently missing from the painter.
	 */
	static void everyTileIsABrush() {
		System.out.println("--- the painter offers every tile as a brush (census L.3)");
		java.util.List<ctrmap.formats.tilemap.TilePalette> want = new java.util.ArrayList<>();
		for (ctrmap.formats.tilemap.TilePalette t : ctrmap.formats.tilemap.TilePalette.values()) {
			if (t != ctrmap.formats.tilemap.TilePalette.VOID) {
				want.add(t);
			}
		}
		java.util.List<ctrmap.formats.tilemap.TilePalette> got = java.util.Arrays.asList(ctrmap.formats.tilemap.TilePalette.brushes());
		check(want.size() > 10 && got.equals(want), "every tile but VOID is a brush, in the palette's order: " + got.size()
				+ " of " + want.size());
	}

	/**
	 * Census M.130: the live preview's worker answers a failed rebuild as a problem the note under Apply shows, never
	 * as nothing. It returned null on any exception, the last good preview stayed up looking current, and the first
	 * report was an Apply that failed on the same exception.
	 */
	static void aPreviewThatCannotBeRebuiltSaysSo() throws Exception {
		System.out.println("--- a preview that cannot be rebuilt says it shows earlier strokes (census M.130)");
		String form = SourceSeamTest.stripComments(DialogSeamTest.read(new File("src/ctrmap/humaninterface/PaintForm.java")));
		String regen = CollisionReadTest.body(form, "private void startRegen()");
		check(regen != null && regen.contains("return new RegenResult(null, null, \"the 3D preview could not be rebuilt")
				&& !regen.contains("return null;") && regen.contains("previewNote(res.problem)"),
				"the preview's worker answers a failed rebuild as a problem, and the note under Apply shows it");
		//census M.4: a region whose tilemap could not be read - getFile's null, a size other than 40x40, a file cut
		//short - went into one catch (Exception) and left an all-grass canvas, without a word
		String painter = SourceSeamTest.stripComments(DialogSeamTest.read(new File("src/ctrmap/MapPainter.java")));
		String load = CollisionReadTest.body(painter, "public static void loadFromRegion(int region, TilePalette[][] grid)");
		check(load != null && !load.contains("catch (") && load.contains("throw new IllegalStateException(\"region \"")
				&& load.contains("tm.length < 4 + DIM * DIM * 4"),
				"the painter's seed refuses a tilemap it cannot seed from, in words, catching nothing");
		String seed = CollisionReadTest.body(form, "	void seed() {");
		int loads = seed == null ? -1 : seed.indexOf("MapPainter.loadFromRegion(region, grid);");
		int caught = seed == null ? -1 : seed.indexOf("terrain = \"Region \" + region + \"'s terrain could not be read (\"");
		int shown = seed == null ? -1 : seed.indexOf("if (terrain != null) {");
		check(loads > 0 && caught > loads && shown > caught,
				"and the canvas says its terrain could not be read, with the reason (" + loads + ", " + caught + ", " + shown + ")");
	}

	/**
	 * The preview's donor textures: every area loaded leaves no note; an area whose textures could not be loaded is
	 * named in it, with why, and the others are still loaded (the 2026-10-15 shared sweep, PaintForm 1039, negate-if,
	 * SURVIVED: the step lived in the preview's worker, which no suite can wait on - it is loadDonorTextures now).
	 */
	static void aDonorAreaThatWillNotLoadIsNamed() throws Exception {
		System.out.println("--- a donor area whose textures will not load is named under Apply");
		java.util.List<ctrmap.formats.h3d.texturing.H3DTexture> into = new java.util.ArrayList<>();
		java.util.List<Integer> asked = new java.util.ArrayList<>();
		String none = PaintForm.loadDonorTextures(Arrays.asList(5, 7), into, area -> {
			asked.add(area);
			return java.util.Collections.<ctrmap.formats.h3d.texturing.H3DTexture>emptyList();
		});
		String one = PaintForm.loadDonorTextures(Arrays.asList(5, 7), into, area -> {
			asked.add(area);
			if (area == 7) {
				throw new java.io.IOException("its texture pack is not there");
			}
			return java.util.Collections.<ctrmap.formats.h3d.texturing.H3DTexture>emptyList();
		});
		check(none == null && one != null && one.contains("area 7 (its texture pack is not there)") && !one.contains("area 5")
				&& asked.equals(Arrays.asList(5, 7, 5, 7)), "every area loaded leaves no note; the one that would not load"
				+ " is named with why, and every area was still asked: " + none + " / " + one + " / " + asked);
	}

	/**
	 * The note under Apply says why the 3D preview is not what Apply would write, and clears again (the 2026-10-15
	 * shared sweep, PaintForm 519, drop-report, SURVIVED: the worker that says it is read in its source above, and
	 * nothing read the note). Asked of the note itself on a document - its caller is a SwingWorker's done().
	 */
	static void thePreviewNoteSaysWhyAndClears() throws Exception {
		System.out.println("--- the note under Apply says why the preview differs, and clears");
		PaintForm form = document();
		java.lang.reflect.Method note = PaintForm.class.getDeclaredMethod("previewNote", String.class);
		note.setAccessible(true);
		JLabel status = (JLabel) get(form, "previewStatus");
		note.invoke(form, "the 3D preview could not be rebuilt: no donor model");
		String said = status.getText();
		note.invoke(form, (Object) null);
		check(said.contains("the 3D preview could not be rebuilt: no donor model") && " ".equals(status.getText()),
				"a problem is said under Apply, and none clears it: \"" + said + "\", then \"" + status.getText() + "\"");
	}

	/**
	 * An Apply that was refused tells the user why, promises the map is
	 * untouched, and takes the painted preview back out of the 3D scene.
	 *
	 * <p>The preview swaps the painted model into the scene while the tool is
	 * open. Leaving it there after a refusal shows a map that is not on disk
	 * and never was - the user's next look at the 3D view is of their own
	 * paint, over a zone that still holds the retail one. And "Nothing was
	 * written" is a promise Apply can only make because it clears every
	 * precondition before the first byte: without that sentence a refusal reads
	 * as "something happened, unclear what", which is the difference between
	 * trying again and going to look at what shipped.
	 *
	 * <p>Everything above this in applyAction is a modal dialog, so the path
	 * has no headless route; the report is asked for directly, with
	 * {@link ctrmap.Ui} collecting it.
	 */
	static void aRefusedApplySaysSoAndPutsTheMapBack() throws Exception {
		PaintForm form = document();
		TestWindow.mTileMapPanel(new ctrmap.humaninterface.TileMapPanel(new LoadedZone(), TOOLS, SCENE, new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(TOOLS, UndoHistoryTest.overOneNumber(new int[]{0})), null));
		set(form, "previewInScene", true);
		set(form, "originalModel", new byte[]{1, 2, 3, 4});
		List<String> said = ctrmap.Ui.record();
		try {
			form.applyFailed(new IllegalStateException("Area 43 is also used by zones 72, 73"));
		} finally {
			ctrmap.Ui.stopRecording();
			TestWindow.mTileMapPanel(null);
		}
		check(said.size() == 1 && said.get(0).contains("Apply failed")
				&& said.get(0).contains("Area 43 is also used by zones 72, 73")
				&& said.get(0).contains("Nothing was written - the map is exactly as it was"),
				"a refused Apply names what stopped it and promises the map is untouched: " + said);
		check(Boolean.FALSE.equals(get(form, "previewInScene")),
				"and the painted preview is out of the 3D scene again, so the view is the map on disk");

		//a second, different refusal must read differently - the sentence
		//carries the reason, it is not a fixed "Apply failed"
		List<String> other = ctrmap.Ui.record();
		try {
			form.applyFailed(new java.io.IOException("could not write region 153 (file locked or read-only?)"));
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(other.size() == 1 && other.get(0).contains("could not write region 153")
				&& !other.get(0).equals(said.get(0)),
				"and a different refusal says a different thing: " + other);
	}

	/**
	 * Seeding from a real zone: the label under the zone name says how many
	 * tiles had no ground of their own. Zone 74's region has 1,389 tiles with
	 * no collision under their centre; Littleroot's, one flat plane, has none.
	 */
	static void seedLabelCountsBorrowedTiles(File dump) throws Exception {
		if (!holdsRegions(dump)) {
			Skip.section("no pristine dump at " + dump);
			return;
		}
		PaintApplyGuardsTest.openWorkspace(dump);
		PaintApplyGuardsTest.open(74);
		TestWindow.mTileMapPanel(null);
		PaintForm form = new PaintForm(PaintApplyGuardsTest.loaded, EDITORS, ZONES, TestWindow.mTileMapPanel(), history(), OPS, () -> { });
		Method seed = PaintForm.class.getDeclaredMethod("seed");
		seed.setAccessible(true);
		seed.invoke(form);
		int[] cell = ctrmap.MapPainter.firstRegionCell(PaintApplyGuardsTest.loaded);
		check(cell != null, "fixture: zone 74 resolves its own map cell");
		if (cell == null) {
			return;
		}
		GR gr = new GR(Workspace.getWorkspaceFile(ArchiveType.FIELD_DATA, cell[0]), Workspace.session());
		int borrowed = PaintedHeights.seedHeightsFromCollision(gr.getFile(2), gr.getFile(0), new int[DIM][DIM]);
		check(borrowed > 0, "fixture: region " + cell[0] + " has tiles with no ground of their own (" + borrowed + ")");
		String label = ((JLabel) get(form, "zoneLabel")).getText();
		check(label.contains("Painting zone 74"), "the label names the zone: " + label);
		check(label.contains(borrowed + " tile(s) have no ground of their own"),
				"and says how many tiles start on borrowed ground (" + borrowed + "): " + label);
	}

	/** Whether {@code dump} holds the region archive these checks open a zone's map from, named by the game's profile. */
	static boolean holdsRegions(File dump) {
		return new File(dump, Workspace.getArchivePath(ArchiveType.FIELD_DATA, ctrmap.gamedef.GameType.ORAS)).isFile();
	}

	static Object get(Object o, String name) throws Exception {
		Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	static void set(Object o, String name, Object value) throws Exception {
		Field f = o.getClass().getDeclaredField(name);
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

/**
	 * A painter with no map view puts nothing back into the 3D scene, and still
	 * tells the user what stopped the Apply.
	 *
	 * <p>The document is drivable with no view - section one pins that, and
	 * every section here is built that way. Only repaintMap() checked for it:
	 * restoreRealModel() reached for the view unconditionally, and it runs from
	 * applyFailed() AHEAD of the report, so a refusal over an open zone threw a
	 * NullPointerException out of applyFailed() before the user was told
	 * "Nothing was written - the map is exactly as it was".
	 *
	 * <p>Why {@link #aRefusedApplySaysSoAndPutsTheMapBack()} cannot see it: its
	 * zone is open at an index with NO Zone object, so loadedZone.isOpen() is
	 * false and the reach is never taken - its "the preview is out of the scene
	 * again" passes on the flag, which restoreRealModel() clears whether or not
	 * it reached the view. This one opens a real zone, which is the only way to
	 * reach the line at all.
	 */
	static void noMapViewMeansNoSceneToPutBack(File dump) throws Exception {
		System.out.println("--- a painter with no map view puts nothing back, and still says the refusal");
		if (!holdsRegions(dump)) {
			Skip.section("no pristine dump at " + dump);
			return;
		}
		PaintApplyGuardsTest.openWorkspace(dump);
		PaintApplyGuardsTest.open(74);
		TestWindow.mTileMapPanel(null);
		PaintForm form = new PaintForm(PaintApplyGuardsTest.loaded, EDITORS, ZONES, TestWindow.mTileMapPanel(), history(), OPS, () -> { });
		//the state a tool exit or a refused Apply finds after a live preview:
		//a swapped-in model over the zone that is open
		set(form, "seededZone", PaintApplyGuardsTest.loaded.index());
		set(form, "previewInScene", true);
		set(form, "originalModel", new byte[]{1, 2, 3, 4});
		check(PaintApplyGuardsTest.loaded.isOpen()
				&& PaintApplyGuardsTest.loaded.index() == 74,
				"fixture: zone 74 is open, so restoreRealModel's other three conditions hold");
		String threw = null;
		List<String> said = ctrmap.Ui.record();
		try {
			form.applyFailed(new java.io.IOException("region 153 is read-only"));
		} catch (RuntimeException ex) {
			threw = String.valueOf(ex);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(threw == null,
				"a refused Apply on a document with no map view reaches the user instead of throwing: " + threw);
		check(said.size() == 1 && said.get(0).contains("region 153 is read-only")
				&& said.get(0).contains("Nothing was written - the map is exactly as it was"),
				"and still names what stopped it and promises the map is untouched: " + said);
		check(Boolean.FALSE.equals(get(form, "previewInScene")),
				"and the preview flag is clear, there being no scene that ever held one");
	}

	/**
	 * Apply's question warns of still water only where water is painted on a zone whose water does not ripple (the
	 * 2026-10-15 shared sweep, PaintForm 1206, negate-if, SURVIVED: no suite asked Apply's question). Inverted, a map
	 * painted without water was warned about its still water, and one with water on a still zone was not. On zone 74,
	 * activated, its canvas all grass with one tile touched, then one tile of water; the question is answered with
	 * nothing, so nothing is applied.
	 */
	static void applyWarnsOfStillWaterOnlyWhereThereIsWater(File dump) throws Exception {
		System.out.println("--- Apply warns of still water only where water is painted");
		if (!holdsRegions(dump)) {
			Skip.section("no pristine dump at " + dump);
			return;
		}
		PaintApplyGuardsTest.openWorkspace(dump);
		PaintApplyGuardsTest.open(74);
		TestWindow.mTileMapPanel(null);
		PaintForm form = new PaintForm(PaintApplyGuardsTest.loaded, EDITORS, ZONES, TestWindow.mTileMapPanel(), history(), OPS, () -> { });
		Method apply = PaintForm.class.getDeclaredMethod("applyAction");
		apply.setAccessible(true);
		List<String> grassSaid;
		List<String> waterSaid;
		ctrmap.Ui.record();
		try {
			form.activate();
		} finally {
			ctrmap.Ui.stopRecording();
		}
		TilePalette[][] grid = (TilePalette[][]) get(form, "grid");
		for (TilePalette[] row : grid) {
			Arrays.fill(row, TilePalette.GRASS);
		}
		((boolean[][]) get(form, "touched"))[5][5] = true;
		grassSaid = ctrmap.Ui.record();
		try {
			apply.invoke(form);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		grid[5][5] = TilePalette.WATER;
		waterSaid = ctrmap.Ui.record();
		try {
			apply.invoke(form);
		} finally {
			ctrmap.Ui.stopRecording();
			form.deactivate();
		}
		boolean ripples = ctrmap.MapPainter.zoneWaterScrolls(PaintApplyGuardsTest.loaded,
				PaintApplyGuardsTest.loaded.open().header.areadataID);
		boolean grassWarned = !grassSaid.isEmpty() && grassSaid.get(0).contains("painted water will be STILL");
		boolean waterWarned = !waterSaid.isEmpty() && waterSaid.get(0).contains("painted water will be STILL");
		check(grassSaid.size() == 1 && grassSaid.get(0).startsWith("Map Builder: Apply your edits to zone 74's map?")
				&& !grassWarned && waterWarned == !ripples,
				"Apply's question: no warning of still water over grass; over water, one exactly when zone 74's water "
				+ (ripples ? "ripples (it does: none)" : "does not ripple (it does not: warned)") + " - " + grassWarned
				+ ", " + waterWarned);
	}

	/**
	 * A preview that could not be rebuilt says so under Apply, from the worker's own done() (the 2026-10-15 shared
	 * sweep, PaintForm 1065, negate-if, SURVIVED: no suite ran the worker). Inverted, a current result's problem went
	 * unsaid and a stale one's was shown. On zone 74, activated, its donor model made bytes no rebuild can read.
	 */
	static void aPreviewThatFailsSaysSoUnderApply(File dump) throws Exception {
		System.out.println("--- a preview that could not be rebuilt says so under Apply, from the worker itself");
		if (!holdsRegions(dump)) {
			Skip.section("no pristine dump at " + dump);
			return;
		}
		PaintApplyGuardsTest.openWorkspace(dump);
		PaintApplyGuardsTest.open(74);
		TestWindow.mTileMapPanel(null);
		PaintForm form = new PaintForm(PaintApplyGuardsTest.loaded, EDITORS, ZONES, TestWindow.mTileMapPanel(), history(), OPS, () -> { });
		ctrmap.Ui.record();
		try {
			form.activate();
			check(get(form, "donorModel") != null, "control: activated on zone 74, the painter holds its donor model");
			set(form, "donorModel", new byte[16]);
			Method regen = PaintForm.class.getDeclaredMethod("startRegen");
			regen.setAccessible(true);
			javax.swing.SwingUtilities.invokeAndWait(() -> {
				try {
					regen.invoke(form);
				} catch (ReflectiveOperationException ex) {
					throw new IllegalStateException(ex);
				}
			});
			long until = System.currentTimeMillis() + 60_000;
			while (Boolean.TRUE.equals(get(form, "regenRunning")) && System.currentTimeMillis() < until) {
				Thread.sleep(50);
			}
			javax.swing.SwingUtilities.invokeAndWait(() -> { });
		} finally {
			ctrmap.Ui.stopRecording();
		}
		String note = ((JLabel) get(form, "previewStatus")).getText();
		form.deactivate();
		check(note.contains("the 3D preview could not be rebuilt"), "the worker's failed rebuild, current, is said under"
				+ " Apply: " + note);
	}

	/**
	 * Make water ripple goes on only over an area the edit may change (the 2026-10-15 shared sweep, PaintForm 643,
	 * negate-if, SURVIVED: no suite pressed it). Inverted, a fork declined fell into a null and was said as a failure,
	 * and an area that needed none was left without its ripple. On zone 74, OK to the ripple and Cancel to a shared
	 * area if one is asked about - each of the two answers zone 74 can give is held to its own outcome.
	 */
	static void makeWaterRippleStopsWhenTheAreaIsNotMadeItsOwn(File dump) throws Exception {
		System.out.println("--- Make water ripple goes on only over an area the edit may change");
		if (!holdsRegions(dump)) {
			Skip.section("no pristine dump at " + dump);
			return;
		}
		PaintApplyGuardsTest.openWorkspace(dump);
		PaintApplyGuardsTest.open(74);
		TestWindow.mTileMapPanel(null);
		PaintForm form = new PaintForm(PaintApplyGuardsTest.loaded, EDITORS, ZONES, TestWindow.mTileMapPanel(), history(), OPS, () -> { });
		ctrmap.Ui.record();
		try {
			form.activate();
		} finally {
			ctrmap.Ui.stopRecording();
		}
		java.lang.reflect.Method ripple = PaintForm.class.getDeclaredMethod("rippleAction");
		ripple.setAccessible(true);
		List<String> said = ctrmap.Ui.record(javax.swing.JOptionPane.OK_OPTION, "Cancel");
		String threw = null;
		try {
			ripple.invoke(form);
		} catch (java.lang.reflect.InvocationTargetException ex) {
			threw = String.valueOf(ex.getCause());
		} finally {
			ctrmap.Ui.stopRecording();
			form.deactivate();
		}
		boolean askedShared = said.size() > 1 && said.get(1).startsWith("Shared area:");
		boolean declined = askedShared && said.size() == 2;
		boolean rippled = !askedShared && said.size() == 2 && said.get(1).startsWith("Make water ripple: ")
				&& (said.get(1).contains("Sea-scroll animation added") || said.get(1).contains("No map cells needed changes"));
		check(threw == null && (declined || rippled), "zone 74's area " + (askedShared ? "is shared: Cancel there changes"
				+ " nothing and says nothing more" : "is its own: the ripple is made, and said") + " - " + threw + ", " + said);
	}

	/**
	 * Activated on a zone it has not seeded, the painter seeds it; activated again on the zone it holds, it keeps what
	 * was painted (F247; the 2026-10-15 shared sweep, PaintForm 409, negate-if, SURVIVED: no suite activated the
	 * painter). Inverted, a new zone was only checked and never seeded, and a re-activation seeded the zone again over
	 * the painting. On zone 74, open in a scratch workspace over the dump.
	 */
	static void activatedThePainterSeedsANewZoneAndKeepsItsOwn(File dump) throws Exception {
		System.out.println("--- activated, the painter seeds a zone it has not seeded and keeps the one it holds");
		if (!holdsRegions(dump)) {
			Skip.section("no pristine dump at " + dump);
			return;
		}
		PaintApplyGuardsTest.openWorkspace(dump);
		PaintApplyGuardsTest.open(74);
		TestWindow.mTileMapPanel(null);
		PaintForm form = new PaintForm(PaintApplyGuardsTest.loaded, EDITORS, ZONES, TestWindow.mTileMapPanel(), history(), OPS, () -> { });
		List<String> said = ctrmap.Ui.record();
		try {
			form.activate();
			check(Integer.valueOf(74).equals(get(form, "seededZone")),
					"activated on zone 74, which it has not seeded, it seeds it: " + get(form, "seededZone") + ", " + said);
			//what a re-activation over a changed map asks by (the 2026-10-15 shared sweep, PaintForm 471, negate-if,
			//SURVIVED: read in its source only) - inverted, a painter with nothing painted asked as if it had painting
			java.lang.reflect.Method painted = PaintForm.class.getDeclaredMethod("painted");
			painted.setAccessible(true);
			boolean before = (Boolean) painted.invoke(form);
			form.gesturePress(5, 5, false);
			boolean[][] touched = (boolean[][]) get(form, "touched");
			check(touched[5][5], "control: a stroke painted tile (5,5)");
			boolean after = (Boolean) painted.invoke(form);
			check(!before && after, "freshly seeded it holds no painting, and after a stroke it does: " + before + ", " + after);
			form.activate();
			touched = (boolean[][]) get(form, "touched");
			check(Integer.valueOf(74).equals(get(form, "seededZone")) && touched[5][5],
					"activated again on the zone it holds, it keeps the painting: tile (5,5) "
					+ (touched[5][5] ? "still painted" : "forgotten") + ", " + said);
			//and says why only when it could not look (the 2026-10-15 shared sweep, PaintForm 501, negate-if,
			//SURVIVED): inverted, a map it checked was said to be unchecked, and one it could not check went unsaid
			String checked = ((JLabel) get(form, "zoneLabel")).getText();
			set(form, "originalModel", null);
			form.activate();
			String unchecked = ((JLabel) get(form, "zoneLabel")).getText();
			touched = (boolean[][]) get(form, "touched");
			check(!checked.contains("Could not check") && touched[5][5] && unchecked.contains("Could not check whether"
					+ " another editor changed this map (there was no map model to compare against when painting started),"
					+ " so your painting is kept"), "over the map it checked it says nothing of checking; with nothing to compare against it"
					+ " keeps the painting and says it could not check, and why: \"" + checked + "\", then \"" + unchecked + "\"");
		} finally {
			ctrmap.Ui.stopRecording();
			form.deactivate();
		}
	}
}
