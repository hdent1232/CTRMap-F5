package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.Workspace;
import ctrmap.formats.containers.GR;
import ctrmap.formats.h3d.BuildingCatalog;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TilePalette;
import ctrmap.humaninterface.PaintForm;
import ctrmap.humaninterface.TilePainterForm;
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

	static final int DIM = PaintedRegionBuilder.DIM;
	static final int ZONE = 7;
	static final Color ARROW = new Color(255, 210, 40);

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		System.setProperty("java.awt.headless", "true");
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		aDocumentWithNoMapView();
		rampToolTurnsAndSays();
		dragSettlesRamps();
		fillAllSettlesRamps();
		undoRestoresRamps();
		overlayMarksRampsOnly();
		pickingABuildingArmsTheNextClick();
		aRefusedApplySaysSoAndPutsTheMapBack();
		seedLabelCountsBorrowedTiles(dump);
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** A form seeded by hand: zone ZONE open, cell (0,0), all grass at level 0, no ramps. */
	static PaintForm document() throws Exception {
		ZoneLoadingPanel pnl = new ZoneLoadingPanel();
		pnl.zoneIndex = ZONE;
		CtrmapMainframe.mZonePnl = pnl;
		CtrmapMainframe.mTileMapPanel = null;
		PaintForm form = new PaintForm();
		set(form, "seededZone", ZONE);
		TilePalette[][] grid = (TilePalette[][]) get(form, "grid");
		for (TilePalette[] row : grid) {
			Arrays.fill(row, TilePalette.GRASS);
		}
		return form;
	}

	/** The notch: a level-1 plateau over y &lt;= 18 with one more plateau tile at (20,19). */
	static PaintForm notch() throws Exception {
		PaintForm form = document();
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
		check(ramp[5][5] == PaintedRegionBuilder.NO_RAMP, "a flat tile cannot be a ramp");
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

	static void undoRestoresRamps() throws Exception {
		PaintForm form = notch();
		int[][] ramp = (int[][]) get(form, "ramp");
		set(form, "ptool", 4);
		form.gesturePress(20, 19, false);
		check(ramp[19][20] == 2, "fixture: a ramp was placed");
		try {
			((JButton) get(form, "undoBtn")).doClick();
			check(ramp[19][20] == PaintedRegionBuilder.NO_RAMP, "undo takes the ramp away again (" + ramp[19][20] + ")");
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
		check(new Color(img.getRGB(20 * 12 + 6, 19 * 12 + 6)).equals(ARROW), "the overlay draws the arrow on the ramp tile");
		int stray = 0;
		for (int ly = 0; ly < DIM; ly++) {
			for (int lx = 0; lx < DIM; lx++) {
				if ((lx != 20 || ly != 19) && new Color(img.getRGB(lx * 12 + 6, ly * 12 + 6)).equals(ARROW)) {
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
		List<TilePainterForm.Placed> placed = (List<TilePainterForm.Placed>) get(form, "placed");

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
		CtrmapMainframe.mTileMapPanel = new ctrmap.humaninterface.TileMapPanel();
		set(form, "previewInScene", true);
		set(form, "originalModel", new byte[]{1, 2, 3, 4});
		List<String> said = ctrmap.Ui.record();
		try {
			form.applyFailed(new IllegalStateException("Area 43 is also used by zones 72, 73"));
		} finally {
			ctrmap.Ui.stopRecording();
			CtrmapMainframe.mTileMapPanel = null;
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
		if (!new File(dump, "a/0/3/9").isFile()) {
			System.out.println("  skip: no pristine dump at " + dump);
			return;
		}
		PaintApplyGuardsTest.openWorkspace(dump);
		PaintApplyGuardsTest.open(74);
		CtrmapMainframe.mTileMapPanel = null;
		PaintForm form = new PaintForm();
		Method seed = PaintForm.class.getDeclaredMethod("seed");
		seed.setAccessible(true);
		seed.invoke(form);
		int[] cell = TilePainterForm.firstRegionCell();
		check(cell != null, "fixture: zone 74 resolves its own map cell");
		if (cell == null) {
			return;
		}
		GR gr = new GR(Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, cell[0]));
		int borrowed = PaintedRegionBuilder.seedHeightsFromCollision(gr.getFile(2), gr.getFile(0), new int[DIM][DIM]);
		check(borrowed > 0, "fixture: region " + cell[0] + " has tiles with no ground of their own (" + borrowed + ")");
		String label = ((JLabel) get(form, "zoneLabel")).getText();
		check(label.contains("Painting zone 74"), "the label names the zone: " + label);
		check(label.contains(borrowed + " tile(s) have no ground of their own"),
				"and says how many tiles start on borrowed ground (" + borrowed + "): " + label);
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
}
