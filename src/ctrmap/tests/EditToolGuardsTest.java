package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.Workspace;
import ctrmap.formats.cameradata.CameraData;
import ctrmap.formats.cameradata.CameraDataFile;
import ctrmap.formats.containers.AD;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.propdata.GRProp;
import ctrmap.formats.propdata.GRPropData;
import ctrmap.formats.tilemap.Tilemap;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import ctrmap.humaninterface.Selector;
import ctrmap.humaninterface.TileUndo;
import ctrmap.humaninterface.tools.AbstractTool;
import ctrmap.humaninterface.tools.CameraTool;
import ctrmap.humaninterface.tools.GeoTool;
import ctrmap.humaninterface.tools.PaintTool;
import ctrmap.humaninterface.tools.PropTool;
import ctrmap.humaninterface.tools.SetTool;
import ctrmap.humaninterface.tools.TriggerTool;
import ctrmap.humaninterface.tools.WarpTool;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import javax.swing.JComboBox;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * Characterization of the seven World Editor tools: what each one does to the
 * editor's data when the mouse arrives, what it draws on the map view, and what
 * it answers about itself.
 *
 * <p>WHY THIS EXISTS. Six of these classes had no coverage at all and the
 * seventh had 15.8%, and not because they are complicated - none is longer than
 * sixty-eight lines. They were unreachable: each reads the whole editor through
 * {@code import static ctrmap.CtrmapMainframe.*}, and {@link AbstractTool}'s
 * constructor runs {@code onToolInit()}, so a tool cannot be built until nine
 * forms and three split panes exist. {@link EditorBench} builds them. What a
 * tool then does to a tile, a warp, a trigger or a prop is ordinary data a
 * suite can read back, and what it draws is pixels.
 *
 * <p>WHAT IS PINNED, INCLUDING WHAT LOOKS WRONG. These are characterization
 * tests for a step that is about to move this code, so they assert today's
 * behaviour, not the behaviour it ought to have. Two things are odd on purpose
 * and are marked where they are asserted:
 * <ul>
 * <li>{@link WarpTool#updateComponents} and {@link TriggerTool#updateComponents}
 *     both refresh the CAMERA form rather than their own, which is what the 3D
 *     view calls after a navigator drag. Neither line can be reached today
 *     (both tools drop the navigator), which is why nothing has noticed.</li>
 * <li>{@link PropTool#updateComponents} marks the prop file modified whether or
 *     not anything moved.</li>
 * </ul>
 *
 * <p>WHAT NEEDS A SCREEN. Nine lines across these classes repaint the
 * mainframe's JFrame, which cannot exist with no display, and every
 * {@code onToolInit} ends in a {@code frame.revalidate()} inside
 * {@link ctrmap.CtrmapMainframe#switchToolUI}. Those checks are gathered in
 * {@link #withTheWindow} and print one skip without a display. The battery
 * runner passes no headless flag, so they run there.
 *
 * <p>The camera checks are the only ones that need the dump, and only because
 * CameraDataFile has a single constructor and it parses an AreaData container.
 * Its record list is then replaced with hand-built cameras, so what is asserted
 * is still a fixture this suite chose.
 *
 * Usage: java ctrmap.tests.EditToolGuardsTest &lt;pristine dump root&gt;
 */
public class EditToolGuardsTest {

	static int fails = 0;
	static final int BG = EditorBench.BG;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		EditorBench.install();
		try {
			theSelectionHoldsOneToolAndSaysSo();
		aZoneSwitchTellsTheHeldTool();
			everyToolAnswersForItself();
			paintToolHandsGesturesToTheForm();
			setToolWritesTheTileBytes();
			setToolMakesOneUndoStepPerDrag();
			geoToolTracksTheRectangle();
			geoToolDrawsTheRectangle();
			propToolDragsAPropAndKeepsTheGrab();
			triggerToolDrawsBothLists();
			warpToolDrawsAndDragsWarps();
			theToolsThatRefreshTheCameraForm();
			theTileInspectorAsksForNoRepaintOfItsOwn();
			withTheWindow(dump);
		} finally {
			EditorBench.shutdown();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		//An explicit exit, which most suites here do not need: this one builds
		//a JFrame and a PropEditForm, and either can hold a non-daemon AWT
		//thread open past the last check. A runner cannot report a hang.
		System.exit(fails > 0 ? 1 : 0);
	}

	// ---- the shape every tool advertises ------------------------------------

	/**
	 * The two questions the editor asks a tool before anything happens: may the
	 * tile cursor follow the mouse, and may the 3D navigator stay bound. The
	 * answers decide whether the CM2D cursor is drawn at all and whether a 3D
	 * gizmo is attached, so a tool that changes its answer changes what the user
	 * can do with it without touching a single handler.
	 *
	 * <p>Also the guard in AbstractTool's own constructor: with no 3D panel a
	 * tool must still build. Every other check in this suite rests on it.
	 */
	/**
	 * Which tool is held is an object, and two of them can exist at once.
	 *
	 * <p>It was {@code CtrmapMainframe.tool}, a public mutable static of the
	 * main window - nine classes read it and the mouse router assigned it from
	 * ten places, the only outside writer any of the window's statics had. A
	 * suite could set it, because anything could; what it could not do was hold
	 * two, so every check about "while the NPC tool is up" was really a check
	 * about the one global the whole battery shares.
	 *
	 * <p>The order is the part worth pinning. The router shut the outgoing tool
	 * down BEFORE building the incoming one, because a tool's constructor puts
	 * its own form in the editor's pane and a shutdown arriving afterwards
	 * would take it straight back out. That is why the selection takes a
	 * supplier: handing it a built tool would reverse the order silently.
	 */
	static void theSelectionHoldsOneToolAndSaysSo() {
		ctrmap.humaninterface.tools.ToolSelection sel = new ctrmap.humaninterface.tools.ToolSelection();
		check(sel.current() == null, "a fresh selection holds nothing");
		//AND A VIEW WITH NOBODY HOLDING ANYTHING STILL DRAWS. The 3D renderer asked the
		//held tool whether to draw the gizmo without checking there was one, so the Zone
		//Loader preview - a view with no tools at all - threw on every frame, on the
		//event thread, and the window came up with its tabs painted over each other.
		//ASKED SO THAT DYING IS AN ANSWER. The defect here is a throw, not a wrong
		//boolean, and a check that lets it escape reports nothing but a stack trace -
		//which is how a plant proving this can look like a guard that said nothing.
		check(!wantsNavi(sel), "...and a renderer handed a selection holding nothing draws"
			+ " no gizmo rather than dying every frame" + how[0]);
		check(!wantsNavi(null), "...nor when there is no selection at all" + how[0]);
		check(!sel.holding(SetTool.class), "and is holding no particular tool");

		java.util.List<String> order = new java.util.ArrayList<String>();
		AbstractTool first = new SpySwitchTool(order, "first");
		sel.switchTo(() -> first);
		check(sel.current() == first && sel.holding(SpySwitchTool.class),
				"picking one up holds it, and says which kind");
		check(order.equals(Arrays.asList("init:first")),
				"the first pick-up shuts nothing down, because nothing was held: " + order);

		AbstractTool second = new SpySwitchTool(order, "second");
		order.clear();
		sel.switchTo(() -> {
			order.add("building:second");
			return second;
		});
		check(sel.current() == second, "switching holds the new one");
		check(order.equals(Arrays.asList("shutdown:first", "building:second")),
				"and the outgoing tool is told BEFORE the incoming one is built, which is the"
				+ " order a tool's constructor depends on: " + order);

		order.clear();
		sel.drop();
		check(sel.current() == null, "dropping holds nothing");
		check(order.isEmpty(), "and tells nobody - it is 'there is no editor', not a switch: " + order);

		//two of them, which the window's static made impossible
		ctrmap.humaninterface.tools.ToolSelection a = new ctrmap.humaninterface.tools.ToolSelection();
		ctrmap.humaninterface.tools.ToolSelection b = new ctrmap.humaninterface.tools.ToolSelection();
		AbstractTool mine = new SpySwitchTool(new java.util.ArrayList<String>(), "a");
		AbstractTool theirs = new SpySwitchTool(new java.util.ArrayList<String>(), "b");
		a.switchTo(() -> mine);
		b.switchTo(() -> theirs);
		check(a.current() == mine && b.current() == theirs,
				"two selections hold two different tools at the same moment");
		a.drop();
		check(a.current() == null && b.current() == theirs,
				"and one letting go does not touch the other");
	}

	/** A tool that records what it was told and when, and touches no editor. */
	/**
	 * A zone switch tells the tool that is held, and the tool decides what that
	 * means for it.
	 *
	 * <p>WHY THIS SEAM EXISTS. The zone switch used to ask
	 * {@code tools.holding(PaintTool.class)} and then reach into the main window
	 * for the painter's form to re-seed it. That is the Zone tab knowing which
	 * tools exist and which of them care about a zone change - so an eleventh
	 * tool needing the same thing would have meant editing the zone switch, and
	 * forgetting to would have left it showing a zone the user had left, with
	 * nothing anywhere to say so.
	 *
	 * <p>Nine of the ten tools do nothing here ON PURPOSE: they read the map view
	 * when they are used, so the next click already sees the new zone. The
	 * painter is the exception because it holds a DOCUMENT seeded from the zone
	 * that was open when it started.
	 */
	static void aZoneSwitchTellsTheHeldTool() {
		final java.util.List<String> told = new java.util.ArrayList<String>();
		ctrmap.humaninterface.tools.ToolSelection sel = new ctrmap.humaninterface.tools.ToolSelection();

		sel.zoneChanged();
		check(told.isEmpty(), "with no tool held it tells nobody, and does not throw: " + told);

		java.util.List<String> order = new java.util.ArrayList<String>();
		AbstractTool caring = new SpySwitchTool(order, "caring") {
			@Override
			public void onZoneChanged() {
				told.add("caring");
			}
		};
		sel.switchTo(() -> caring);
		sel.zoneChanged();
		check(told.equals(Arrays.asList("caring")), "the held tool is told exactly once: " + told);
		check(sel.current() == caring, "and it is still held - this is not a tool switch");

		sel.switchTo(() -> new SpySwitchTool(order, "other"));
		sel.zoneChanged();
		check(told.equals(Arrays.asList("caring")),
			"the tool that was put down is not told again: " + told);

		//the nine that do nothing: the default must be a no-op, not abstract, or
		//every tool would have to write an empty method to say it does not care
		sel.zoneChanged();
		check(told.equals(Arrays.asList("caring")),
			"and a tool that does not override it does nothing, rather than being made to say so");
	}

	/** Not final: the zone-change section subclasses it to record that one call. */
	static class SpySwitchTool extends AbstractTool {

		private final java.util.List<String> log;
		private final String name;

		SpySwitchTool(java.util.List<String> log, String name) {
			super(EditorBench.HOST);
			this.log = log;
			this.name = name;
			log.add("init:" + name);
		}

		@Override
		public void onToolInit() {
		}

		@Override
		public void onToolShutdown() {
			log.add("shutdown:" + name);
		}

		@Override
		public void fireCancel() {
		}

		@Override
		public void drawOverlay(java.awt.Graphics g, int x, int y, double dim) {
		}

		@Override
		public boolean getSelectorEnabled() {
			return false;
		}

		@Override
		public boolean getNaviEnabled() {
			return false;
		}

		@Override
		public void onTileClick(java.awt.event.MouseEvent e) {
		}

		@Override
		public void onTileMouseDown(java.awt.event.MouseEvent e) {
		}

		@Override
		public void onTileMouseUp(java.awt.event.MouseEvent e) {
		}

		@Override
		public void onTileMouseDragged(java.awt.event.MouseEvent e) {
		}

		@Override
		public void updateComponents() {
		}
	}

	static void everyToolAnswersForItself() {
		check(CtrmapMainframe.m3DDebugPanel == null,
				"fixture: there is no 3D panel, which is the case the guard in AbstractTool's constructor is for");
		AbstractTool[] tools = new AbstractTool[]{
			paintTool(), setTool(), geoTool(), propTool(), triggerTool(), warpTool(), cameraTool()
		};
		String[] names = {"Paint", "Set", "Geo", "Prop", "Trigger", "Warp", "Camera"};
		boolean[] selector = {true, true, true, false, false, false, true};
		boolean[] navi = {false, false, false, true, false, false, false};
		for (int i = 0; i < tools.length; i++) {
			check(tools[i].getSelectorEnabled() == selector[i],
					names[i] + "Tool " + (selector[i] ? "wants" : "does not want") + " the tile cursor");
			check(tools[i].getNaviEnabled() == navi[i],
					names[i] + "Tool " + (navi[i] ? "keeps" : "drops") + " the 3D navigator");
			check(!tools[i].CM2DNoUpdate, names[i] + "Tool starts with the map view updating");
		}
		check(!tools[0].getNaviEnabled() && tools[3].getNaviEnabled(),
				"the Prop tool is the only one of the seven that keeps a 3D navigator bound");
	}

	// ---- PaintTool ----------------------------------------------------------

	/**
	 * PaintTool owns no data of its own: every handler is one call on the
	 * painter form, and the only decisions it makes are which call, with which
	 * tile, and whether the map view may repaint mid-stroke.
	 *
	 * <p>The tile it passes is the SELECTOR's tile, not the mouse position, and
	 * the -1 that means "off the map" has to stop the gesture: a press there
	 * would otherwise paint tile (-1,-1) of the seeded region.
	 */
	static void paintToolHandsGesturesToTheForm() {
		PaintTool t = paintTool();
		EditorBench.paint.calls.clear();

		check(SwingUtilities.isRightMouseButton(EditorBench.press(0, 0, true))
				&& !SwingUtilities.isRightMouseButton(EditorBench.press(0, 0, false)),
				"fixture: the synthetic right-button event really reads as a right button");

		EditorBench.hilight(-1, -1);
		t.onTileMouseDown(EditorBench.press(5, 5, false));
		t.onTileMouseDragged(EditorBench.drag(5, 5, false));
		check(EditorBench.paint.calls.isEmpty(),
				"off the map, neither a press nor a drag reaches the painter: " + EditorBench.paint.calls);
		check(!t.CM2DNoUpdate, "and a drag off the map does not freeze the map view either");

		EditorBench.hilight(7, 9);
		t.onTileMouseDown(EditorBench.press(70, 90, false));
		check(EditorBench.paint.calls.equals(Arrays.asList("press 7,9,false")),
				"a left press paints the highlighted tile: " + EditorBench.paint.calls);

		EditorBench.paint.calls.clear();
		t.onTileMouseDown(EditorBench.press(70, 90, true));
		check(EditorBench.paint.calls.equals(Arrays.asList("press 7,9,true")),
				"and the button is carried through, which is how the painter tells erase from paint: " + EditorBench.paint.calls);

		EditorBench.paint.calls.clear();
		t.onTileClick(EditorBench.click(70, 90, false));
		check(EditorBench.paint.calls.isEmpty(),
				"the click that follows the release paints nothing a second time: " + EditorBench.paint.calls);

		EditorBench.hilight(11, 12);
		t.onTileMouseDragged(EditorBench.drag(110, 120, false));
		check(EditorBench.paint.calls.equals(Arrays.asList("drag 11,12,false")),
				"a drag continues the stroke: " + EditorBench.paint.calls);
		check(t.CM2DNoUpdate, "and freezes the map view for the length of the stroke");

		t.onTileMouseUp(EditorBench.release(110, 120, false));
		check(!t.CM2DNoUpdate, "the release lets the map view update again");

		EditorBench.paint.calls.clear();
		t.fireCancel();
		check(EditorBench.paint.calls.equals(Arrays.asList("cancelPending")),
				"cancelling drops a pending building placement: " + EditorBench.paint.calls);

		EditorBench.paint.calls.clear();
		Selector.selTileX = 3;
		Selector.selTileY = 4;
		t.onToolShutdown();
		check(EditorBench.paint.calls.equals(Arrays.asList("deactivate")),
				"leaving the tool deactivates the painter: " + EditorBench.paint.calls);
		check(Selector.selTileX == -1 && Selector.selTileY == -1, "and unfocuses the tile the inspector was holding");

		EditorBench.paint.calls.clear();
		BufferedImage img = EditorBench.canvas();
		Graphics g = img.getGraphics();
		t.drawOverlay(g, 12, 34, 7.5d);
		g.dispose();
		check(EditorBench.paint.calls.equals(Arrays.asList("overlay 12,34,7.5")),
				"the overlay is drawn by the painter, at the coordinates the map view gave it: " + EditorBench.paint.calls);
	}

	// ---- SetTool ------------------------------------------------------------

	/**
	 * The Set tool stamps its four tile bytes onto the highlighted tile. The
	 * bytes in the region are the whole point of the tool, so they are what is
	 * asserted, together with the two things around them a user notices when
	 * they go wrong: the region marked modified (which is what makes a save
	 * write it) and an undo step to take it back.
	 *
	 * <p>The right button does something else entirely - it picks the tile up
	 * into the inspector - and pressing it on the tile already picked up drops
	 * it again. Both are asserted, because a tool that stamped on right-click
	 * would overwrite the tile the user was trying to read.
	 */
	static void setToolWritesTheTileBytes() {
		SetTool t = setTool();
		TileUndo.clear();
		Selector.unfocus(null);
		byte[] before = EditorBench.region.getTileData(3, 4).clone();
		check(Arrays.equals(before, new byte[]{0x21, 0, 0, 1}), "fixture: the scratch region starts as unwalkable tiles");
		check(Arrays.equals(t.actTileData, new byte[4]), "a fresh Set tool carries four zero bytes");

		t.actTileData = new byte[]{1, 2, 3, 4};
		EditorBench.hilight(3, 4);
		t.onTileClick(EditorBench.click(30, 40, false));
		check(Arrays.equals(EditorBench.region.getTileData(3, 4), new byte[]{1, 2, 3, 4}),
				"a left click writes the tool's four bytes into the region");
		check(EditorBench.region.modified, "and marks the region modified, which is what makes a save write it");
		check(TileUndo.canUndo(), "and leaves an undo step");

		t.onTileClick(EditorBench.click(30, 40, false));
		check(TileUndo.undo(RecordingHost.INSPECTOR, CtrmapMainframe.mTileMapPanel), "undo takes the tile back");
		check(Arrays.equals(EditorBench.region.getTileData(3, 4), before), "to exactly the bytes that were there");
		check(!TileUndo.canUndo(),
				"and there is nothing behind it: clicking the same tile twice with the same bytes is one step, not two");
		check(TileUndo.redo(RecordingHost.INSPECTOR, CtrmapMainframe.mTileMapPanel) && Arrays.equals(EditorBench.region.getTileData(3, 4), new byte[]{1, 2, 3, 4}),
				"redo puts the edit back");

		//right button: pick the tile up into the inspector, and pick it up again
		//to drop it
		EditorBench.hilight(6, 7);
		t.onTileClick(EditorBench.click(60, 70, true));
		check(Selector.selTileX == 6 && Selector.selTileY == 7, "a right click picks the tile up into the inspector");
		check(Arrays.equals(EditorBench.region.getTileData(6, 7), new byte[]{0x21, 0, 0, 1}), "and writes nothing");
		t.onTileClick(EditorBench.click(60, 70, true));
		check(Selector.selTileX == -1 && Selector.selTileY == -1, "right clicking the same tile again drops it");

		//no region under the cursor: the tool must do nothing rather than throw
		//on the event thread, which is where a null region would land
		TileUndo.clear();
		Tilemap[][] was = EditorBench.map.tilemaps;
		EditorBench.map.tilemaps = null;
		EditorBench.hilight(1, 1);
		t.onTileClick(EditorBench.click(10, 10, false));
		EditorBench.map.tilemaps = was;
		check(!TileUndo.canUndo(), "a click where there is no region writes nothing and records nothing");

		EditorBench.hilight(-1, -1);
		t.onTileClick(EditorBench.click(0, 0, false));
		check(!TileUndo.canUndo(), "and neither does a click off the map");
	}

	/**
	 * A paint drag is ONE undo step, not one per tile. The tool opens a batch on
	 * every drag event and closes it on the release, so a stroke across three
	 * tiles has to come back in a single undo; the alternative - what the user
	 * gets if the release stops closing the batch, or the drag stops opening one
	 * - is three presses of Ctrl+Z to take back one stroke.
	 */
	static void setToolMakesOneUndoStepPerDrag() {
		SetTool t = setTool();
		TileUndo.clear();
		Selector.unfocus(null);
		byte[] before = EditorBench.region.getTileData(10, 20).clone();
		t.actTileData = new byte[]{9, 8, 7, 6};

		EditorBench.hilight(10, 20);
		t.onTileMouseDown(EditorBench.press(100, 200, false));
		check(!TileUndo.isOpen(), "the press alone opens nothing: this tool paints on the drag");
		t.onTileMouseDragged(EditorBench.drag(100, 200, false));
		check(t.CM2DNoUpdate, "the drag freezes the map view");
		EditorBench.hilight(11, 20);
		t.onTileMouseDragged(EditorBench.drag(110, 200, false));
		EditorBench.hilight(12, 20);
		t.onTileMouseDragged(EditorBench.drag(120, 200, false));
		check(TileUndo.isOpen(), "and the batch is still open mid-stroke");
		t.onTileMouseUp(EditorBench.release(120, 200, false));
		check(!t.CM2DNoUpdate && !TileUndo.isOpen(), "the release unfreezes the view and closes the batch");

		boolean painted = Arrays.equals(EditorBench.region.getTileData(10, 20), new byte[]{9, 8, 7, 6})
				&& Arrays.equals(EditorBench.region.getTileData(11, 20), new byte[]{9, 8, 7, 6})
				&& Arrays.equals(EditorBench.region.getTileData(12, 20), new byte[]{9, 8, 7, 6});
		check(painted, "all three tiles of the stroke carry the tool's bytes");
		check(TileUndo.undo(RecordingHost.INSPECTOR, CtrmapMainframe.mTileMapPanel), "one undo");
		boolean allBack = Arrays.equals(EditorBench.region.getTileData(10, 20), before)
				&& Arrays.equals(EditorBench.region.getTileData(11, 20), before)
				&& Arrays.equals(EditorBench.region.getTileData(12, 20), before);
		check(allBack, "takes the whole stroke back, all three tiles");
		check(!TileUndo.canUndo(), "and there is nothing behind it: the stroke was one step");

		//a right drag is the inspector's gesture, not a stroke
		TileUndo.clear();
		EditorBench.hilight(13, 20);
		t.onTileMouseDragged(EditorBench.drag(130, 200, true));
		check(Arrays.equals(EditorBench.region.getTileData(13, 20), before), "a right drag paints nothing");
		check(!TileUndo.canUndo() && !TileUndo.isOpen(), "and opens no undo batch");
	}

	// ---- GeoTool ------------------------------------------------------------

	/**
	 * The geometry tool's whole model is a tile rectangle, and everything the
	 * geometry form then does - move, duplicate, delete, stamp a prefab - acts
	 * on it. So the rectangle is what is asserted: where a click puts it, what a
	 * drag does to it, that it comes out the right way round whichever corner it
	 * was dragged from, and that it cannot leave the anchor's region cell.
	 *
	 * <p>The rectangle is recorded even with no map loaded, which is the state
	 * this bench is in: the form sets the four numbers and only then gives up
	 * with "Load a map first." That is pinned deliberately, because the overlay
	 * draws from those four numbers whether or not a region was found.
	 */
	static void geoToolTracksTheRectangle() {
		GeoTool t = geoTool();
		EditorBench.geo.clearSelection();
		check(EditorBench.geo.selTx0 == -1, "fixture: nothing is selected to begin with");

		EditorBench.hilight(-1, -1);
		t.onTileClick(EditorBench.click(0, 0, false));
		check(EditorBench.geo.selTx0 == -1, "a click off the map selects nothing");

		EditorBench.hilight(5, 6);
		t.onTileClick(EditorBench.click(50, 60, false));
		check(EditorBench.geo.selTx0 == 5 && EditorBench.geo.selTy0 == 6
				&& EditorBench.geo.selTx1 == 5 && EditorBench.geo.selTy1 == 6,
				"a click selects the single tile under the cursor");

		//drag back and down: the anchor is where the button went down, and the
		//rectangle has to read low corner first whichever way it was dragged
		EditorBench.hilight(10, 10);
		t.onTileMouseDown(EditorBench.press(100, 100, false));
		EditorBench.hilight(3, 20);
		t.onTileMouseDragged(EditorBench.drag(30, 200, false));
		check(EditorBench.geo.selTx0 == 3 && EditorBench.geo.selTy0 == 10
				&& EditorBench.geo.selTx1 == 10 && EditorBench.geo.selTy1 == 20,
				"a drag back and down still gives a rectangle read low corner first");
		t.onTileMouseUp(EditorBench.release(30, 200, false));
		check(EditorBench.geo.selTx0 == 3 && EditorBench.geo.selTy1 == 20, "the release keeps it");

		EditorBench.hilight(1, 1);
		t.onTileMouseDragged(EditorBench.drag(10, 10, false));
		check(EditorBench.geo.selTx0 == 3 && EditorBench.geo.selTy1 == 20,
				"and a drag with no press before it does not move the rectangle");

		//the selection lives in one region cell; a drag past its edge stops there
		EditorBench.hilight(10, 10);
		t.onTileMouseDown(EditorBench.press(100, 100, false));
		EditorBench.hilight(70, 10);
		t.onTileMouseDragged(EditorBench.drag(300, 100, false));
		check(EditorBench.geo.selTx0 == 10 && EditorBench.geo.selTx1 == 39,
				"a drag past the region edge clamps to the cell (tile 39, not 70)");
		t.onTileMouseUp(EditorBench.release(300, 100, false));

		EditorBench.hilight(-1, -1);
		t.onTileMouseDown(EditorBench.press(0, 0, false));
		EditorBench.hilight(2, 2);
		t.onTileMouseDragged(EditorBench.drag(20, 20, false));
		check(EditorBench.geo.selTx0 == 10 && EditorBench.geo.selTx1 == 39,
				"pressing off the map sets no anchor, so the drag after it changes nothing");

		int repaintsBefore = EditorBench.map.repaints;
		t.fireCancel();
		check(EditorBench.geo.selTx0 == -1 && EditorBench.geo.selTy1 == -1, "cancelling clears the rectangle");
		check(EditorBench.map.repaints > repaintsBefore, "and repaints the map view, so the box actually disappears");

		EditorBench.hilight(5, 5);
		t.onTileClick(EditorBench.click(50, 50, false));
		Selector.selTileX = 8;
		Selector.selTileY = 8;
		t.onToolShutdown();
		check(EditorBench.geo.selTx0 == -1, "leaving the tool clears the rectangle");
		check(Selector.selTileX == -1 && Selector.selTileY == -1, "and unfocuses the inspector's tile");
	}

	/**
	 * The blue box on the map view is the only thing that says which tiles the
	 * geometry form will act on, so it is asserted as pixels: the outline colour
	 * on both corners, a tinted interior, nothing outside it, and nothing at all
	 * while there is no selection.
	 */
	static void geoToolDrawsTheRectangle() {
		GeoTool t = geoTool();
		EditorBench.geo.clearSelection();
		BufferedImage img = EditorBench.canvas();
		Graphics g = img.getGraphics();
		t.drawOverlay(g, 0, 0, 10d);
		g.dispose();
		check(rgb(img, 30, 40) == BG, "with no selection the overlay draws nothing");

		EditorBench.geo.selTx0 = 2;
		EditorBench.geo.selTy0 = 3;
		EditorBench.geo.selTx1 = 4;
		EditorBench.geo.selTy1 = 5;
		img = EditorBench.canvas();
		g = img.getGraphics();
		t.drawOverlay(g, 0, 0, 10d);
		g.dispose();
		int outline = new Color(32, 96, 224).getRGB() & 0xFFFFFF;
		check(rgb(img, 20, 30) == outline, "the rectangle's near corner carries the outline colour");
		check(rgb(img, 50, 60) == outline, "and so does the far corner of the three-by-three tile rectangle");
		check(rgb(img, 35, 45) != BG, "the inside is tinted, so the selection reads as filled");
		check(rgb(img, 15, 45) == BG && rgb(img, 60, 45) == BG, "and nothing is drawn outside it");
	}

	// ---- PropTool -----------------------------------------------------------

	/**
	 * Props are dragged by their name label, and the label's size is measured
	 * while it is DRAWN, into the prop record itself. That coupling is what this
	 * check exists for: the hit test reads {@code nameWidth} and
	 * {@code nameHeight}, so a prop that has never been drawn cannot be picked
	 * up, and a drawOverlay that stopped recording the measurement would leave
	 * every prop unclickable with nothing on screen to show for it.
	 *
	 * <p>What the drag itself must preserve is the grab: the prop keeps the same
	 * offset from the cursor it had when the button went down. That is asserted
	 * rather than the mapping arithmetic, so the check stays true of any correct
	 * rewrite of the arithmetic.
	 */
	static void propToolDragsAPropAndKeepsTheGrab() {
		PropTool t = propTool();
		GRPropData data = new GRPropData();
		GRProp p0 = new GRProp();
		p0.name = "ALPHA";
		p0.x = 120f;
		p0.z = 60f;
		GRProp p1 = new GRProp();
		p1.name = "BRAVO";
		p1.x = 300f;
		p1.z = 300f;
		data.props.add(p0);
		data.props.add(p1);
		EditorBench.prop.props = data;
		EditorBench.prop.reg = null;
		EditorBench.prop.loaded = true;
		EditorBench.prop.propIndex = 0;
		EditorBench.prop.prop = p0;
		EditorBench.prop.calls.clear();

		check(p1.nameWidth == 0 && p1.nameHeight == 0, "fixture: a prop that has never been drawn has no measured label");

		BufferedImage img = EditorBench.canvas();
		Graphics g = img.getGraphics();
		t.drawOverlay(g, 0, 0, 10d);
		//the same measurement the overlay makes, made here independently
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
		int w0 = g.getFontMetrics().stringWidth(p0.name);
		int w1 = g.getFontMetrics().stringWidth(p1.name);
		g.dispose();
		check(p1.nameWidth == w1 && p1.nameHeight == 10,
				"drawing the overlay measures each prop's label into the record the hit test reads ("
				+ p1.nameWidth + "x" + p1.nameHeight + ")");

		double scale = 400d / 720d;
		int x0 = (int) (p0.x * scale - w0 / 2d);
		int y0 = (int) (p0.z * scale);
		int x1 = (int) (p1.x * scale - w1 / 2d);
		int y1 = (int) (p1.z * scale);
		check(EditorBench.countColour(img, x1, y1, w1, 12, Color.WHITE.getRGB()) > 20,
				"the label is drawn as a white plate at the prop's position");
		check(rgb(img, x1, y1) == (Color.BLACK.getRGB() & 0xFFFFFF), "a prop that is not selected is outlined in black");
		check(rgb(img, x0, y0) == (Color.RED.getRGB() & 0xFFFFFF), "the prop being edited is outlined in red");

		//press on prop 1's plate
		double xBase = p1.x * scale;
		double yBase = p1.z * scale;
		int pressX = (int) xBase;
		int pressY = (int) yBase + 4;
		t.onTileMouseDown(EditorBench.press(pressX, pressY, false));
		check(EditorBench.prop.calls.equals(Arrays.asList("setProp 1")),
				"pressing on a prop's label selects that prop: " + EditorBench.prop.calls);
		check(EditorBench.prop.prop == p1, "and the form is now on it");

		EditorBench.prop.calls.clear();
		int rendersBefore = EditorBench.map.renders;
		float oldX = p1.x;
		t.onTileMouseDragged(EditorBench.drag(pressX + 40, pressY + 25, false));
		check(p1.x != oldX, "the drag moves the prop");
		check(data.modified, "and marks the prop file modified");
		check(EditorBench.prop.calls.equals(Arrays.asList("showProp 1")),
				"and shows the moved prop in the form: " + EditorBench.prop.calls);
		check(EditorBench.map.renders > rendersBefore, "and re-renders the map view, so the label follows the mouse");
		double keptX = p1.x * scale - (pressX + 40);
		double keptY = p1.z * scale - (pressY + 25);
		check(Math.abs(keptX - (xBase - pressX)) < 0.5d && Math.abs(keptY - (yBase - pressY)) < 0.5d,
				"and the prop keeps the offset from the cursor it was grabbed with (dx " + keptX
				+ " against " + (xBase - pressX) + ")");
		check(p0.x == 120f && p0.z == 60f, "the prop that was not grabbed did not move");

		//a drag by a tool that never got a press
		PropTool fresh = propTool();
		float held = p1.x;
		EditorBench.prop.calls.clear();
		fresh.onTileMouseDragged(EditorBench.drag(10, 10, false));
		check(p1.x == held && EditorBench.prop.calls.isEmpty(),
				"a drag that never pressed on a prop moves nothing: " + EditorBench.prop.calls);

		//a press that misses every label
		EditorBench.prop.calls.clear();
		t.onTileMouseDown(EditorBench.press(399, 399, false));
		check(EditorBench.prop.calls.isEmpty(), "a press on empty map selects nothing: " + EditorBench.prop.calls);

		//and with no prop data loaded at all
		EditorBench.prop.loaded = false;
		EditorBench.prop.calls.clear();
		t.onTileMouseDown(EditorBench.press(pressX, pressY, false));
		check(EditorBench.prop.calls.isEmpty(), "with no props loaded a press does nothing: " + EditorBench.prop.calls);
		EditorBench.prop.loaded = true;
	}

	// ---- TriggerTool --------------------------------------------------------

	/**
	 * Triggers are invisible in game and shapeless in the file; the boxes this
	 * draws are the only way anyone can see where one is. Two lists are drawn in
	 * two colours (type 1 white, type 2 yellow) and the one being edited is
	 * framed red, which is what tells a user which record the fields below
	 * belong to.
	 */
	static void triggerToolDrawsBothLists() {
		TriggerTool t = triggerTool();
		ZoneEntities e = triggerFixture();
		EditorBench.triggers.loadFromEntities(e);
		check(EditorBench.triggers.loaded && EditorBench.triggers.trigger == e.triggers1.get(0),
				"fixture: the trigger form is loaded and editing type-1 trigger 0");

		BufferedImage img = EditorBench.canvas();
		Graphics g = img.getGraphics();
		t.drawOverlay(g, 0, 0, 10d);
		g.dispose();
		check(rgb(img, 35, 45) == (Color.WHITE.getRGB() & 0xFFFFFF), "type-1 triggers are drawn white");
		check(rgb(img, 215, 45) == (Color.YELLOW.getRGB() & 0xFFFFFF), "type-2 triggers are drawn yellow");
		check(rgb(img, 20, 30) == (Color.RED.getRGB() & 0xFFFFFF), "the trigger being edited is framed red");
		check(rgb(img, 100, 30) == (Color.BLACK.getRGB() & 0xFFFFFF), "the others are framed black");
		check(rgb(img, 300, 300) == BG, "and nothing is drawn where there is no trigger");

		EditorBench.triggers.loaded = false;
		img = EditorBench.canvas();
		g = img.getGraphics();
		t.drawOverlay(g, 0, 0, 10d);
		g.dispose();
		check(rgb(img, 35, 45) == BG, "with no zone loaded the overlay draws nothing at all");
		EditorBench.triggers.loaded = true;

		//a drag by a tool that never picked a trigger up
		ZoneEntities.Trigger first = e.triggers1.get(0);
		int wasX = first.x;
		e.modified = false;
		EditorBench.hilight(30, 30);
		t.onTileMouseDragged(EditorBench.drag(300, 300, false));
		check(first.x == wasX && !e.modified, "a drag that never pressed on a trigger moves nothing");
	}

	// ---- WarpTool -----------------------------------------------------------

	/**
	 * Warps carry world coordinates, not tile coordinates, and this tool is the
	 * only place the two are converted: it draws at {@code (x - 9) / 18} and
	 * writes back {@code tile * 18 + 9}. Getting that wrong puts a door half a
	 * tile out in game, which nobody notices until they walk into a wall, so the
	 * drawn position and the written-back coordinate are both asserted.
	 *
	 * <p>{@link WarpTool#paintWarps} is static because the tool cannot be built
	 * without the whole window. It is driven through the tool here anyway, so
	 * the tool's one-line delegation to it is covered with it.
	 */
	static void warpToolDrawsAndDragsWarps() throws Exception {
		WarpTool t = warpTool();
		ZoneEntities e = warpFixture();

		BufferedImage img = EditorBench.canvas();
		Graphics g = img.getGraphics();
		t.drawOverlay(g, 0, 0, 10d);
		g.dispose();
		check(rgb(img, 35, 45) == (Color.WHITE.getRGB() & 0xFFFFFF),
				"warp 0 is drawn as a white box on tile (2,3), the tile its world x of 45 sits on");
		check(rgb(img, 20, 30) == (Color.BLACK.getRGB() & 0xFFFFFF), "framed black while it is not the one being edited");
		check(rgb(img, 115, 45) == (Color.WHITE.getRGB() & 0xFFFFFF), "warp 1 is drawn eight tiles along");
		check(rgb(img, 100, 30) == (Color.RED.getRGB() & 0xFFFFFF), "and framed red, because it is the one the form is on");
		check(rgb(img, 300, 300) == BG, "nothing is drawn where there is no warp");

		EditorBench.warps.loaded = false;
		img = EditorBench.canvas();
		g = img.getGraphics();
		t.drawOverlay(g, 0, 0, 10d);
		g.dispose();
		check(rgb(img, 35, 45) == BG, "with no zone loaded the warp overlay draws nothing");
		EditorBench.warps.loaded = true;

		ZoneEntities.Warp w0 = e.warps.get(0);
		int wasX = w0.x;
		e.modified = false;
		EditorBench.hilight(7, 8);
		t.onTileMouseDragged(EditorBench.drag(70, 80, false));
		check(w0.x == wasX && !e.modified, "a drag that never pressed on a warp moves nothing");
	}

	// ---- the copy-paste in updateComponents ---------------------------------

	/**
	 * PINNED, NOT FIXED. {@code updateComponents()} is what the 3D view calls on
	 * the tool in hand after a navigator drag ({@code H3DRenderingPanel.doNavi}),
	 * so it is the tool's chance to put the number the user just dragged into
	 * its own form. The Prop tool refreshes the prop form and the NPC tool the
	 * NPC form, which is the shape.
	 *
	 * <p>The Warp tool and the Trigger tool refresh the CAMERA form. Neither can
	 * reach the line today, because {@code doNavi} returns with no bound target
	 * and both tools answer false to {@link AbstractTool#getNaviEnabled} - which
	 * is exactly what makes it worth pinning. It is unreachable wrong code that
	 * reads as reachable, so a refactor that gives either tool a navigator, or
	 * that calls updateComponents from anywhere else, ships a tool that redraws
	 * a form the user is not looking at and leaves the one they are looking at
	 * stale. Asserted as it stands, and reported separately.
	 */
	static void theToolsThatRefreshTheCameraForm() {
		EditorBench.cam.camIndex = 4;
		EditorBench.cam.calls.clear();
		cameraTool().updateComponents();
		check(EditorBench.cam.calls.equals(Arrays.asList("showCamera 4,false")),
				"the Camera tool refreshes the camera form: " + EditorBench.cam.calls);

		EditorBench.cam.calls.clear();
		warpTool().updateComponents();
		check(EditorBench.cam.calls.equals(Arrays.asList("showCamera 4,false")),
				"SO DOES THE WARP TOOL, which is a copy-paste the Warp tool's own form pays for"
				+ " if the line ever becomes reachable: " + EditorBench.cam.calls);

		EditorBench.cam.calls.clear();
		triggerTool().updateComponents();
		check(EditorBench.cam.calls.equals(Arrays.asList("showCamera 4,false")),
				"and so does the Trigger tool: " + EditorBench.cam.calls);
		check(!warpTool().getNaviEnabled() && !triggerTool().getNaviEnabled(),
				"which nothing has noticed because both tools drop the navigator, so doNavi never calls either line");

		EditorBench.cam.calls.clear();
		paintTool().updateComponents();
		setTool().updateComponents();
		geoTool().updateComponents();
		check(EditorBench.cam.calls.isEmpty(),
				"the Paint, Set and Geometry tools update nothing: " + EditorBench.cam.calls);

		EditorBench.prop.props.modified = false;
		EditorBench.prop.propIndex = 1;
		EditorBench.prop.calls.clear();
		propTool().updateComponents();
		check(EditorBench.prop.props.modified,
				"PINNED: the Prop tool's updateComponents marks the prop file modified even when nothing changed");
		check(EditorBench.prop.calls.equals(Arrays.asList("showProp 1")),
				"and re-shows the selected prop: " + EditorBench.prop.calls);
	}

	// ---- everything that needs the window -----------------------------------

	/**
	 * The checks that cannot run without a display, gathered so the skip is one
	 * line rather than a dozen. Every {@code onToolInit} ends inside
	 * {@link ctrmap.CtrmapMainframe#switchToolUI}, which revalidates the mainframe's
	 * JFrame, and the warp, trigger and camera hit tests all repaint it.
	 */
	static void withTheWindow(File dump) throws Exception {
		//tool startup used to be in here too: it asserted which form reached the
		//window's split pane, and switchToolUI revalidates a JFrame. A tool is
		//handed the editor it works in now, so what it SHOWS is a record on the
		//host and the check runs with no display at all.
		toolStartupSwitchesTheSidePanel();
		if (!EditorBench.frameAvailable()) {
			System.out.println("  skip: no display - the warp and trigger hit tests and the camera tool"
					+ " read the map view's own rendered image, which needs one");
			return;
		}
		warpToolPicksTheWarpUnderTheCursor();
		triggerToolPicksTheTriggerUnderTheCursor();
		cameraToolSwitchesCamera(dump);
	}

	/**
	 * Building a tool puts its form in the side panel and primes it. Each of
	 * these is one line of an onToolInit, and each is the difference between the
	 * tool being usable and the user looking at the previous tool's form while
	 * their clicks go somewhere else.
	 */
	static void toolStartupSwitchesTheSidePanel() throws Exception {
		EditorBench.paint.calls.clear();
		start(EditorBench.BOX.paint());
		check(EditorBench.HOST.lastShown() == CtrmapMainframe.mPaintForm,
				"the Paint tool puts the painter in the side panel");
		check(EditorBench.paint.calls.equals(Arrays.asList("activate")), "and activates it: " + EditorBench.paint.calls);

		EditorBench.tiles.lockTile(false);
		start(EditorBench.BOX.set());
		check(EditorBench.HOST.lastShown() == CtrmapMainframe.mTileEditForm,
				"the Set tool puts the tile inspector in the side panel");
		check(Boolean.TRUE.equals(EditorBench.field(EditorBench.tiles, "isLocked")),
				"and locks it, so hovering the map no longer overwrites the bytes being stamped");

		start(EditorBench.BOX.geometry());
		check(EditorBench.HOST.lastShown() == CtrmapMainframe.mGeoEditForm,
				"the Geometry tool puts the geometry form in the side panel");

		EditorBench.prop.calls.clear();
		start(EditorBench.BOX.prop());
		check(EditorBench.HOST.lastShown() == CtrmapMainframe.mPropEditForm,
				"the Prop tool puts the prop form in the side panel");
		check(EditorBench.prop.calls.equals(Arrays.asList("saveAndRefresh")),
				"and saves and refreshes it: " + EditorBench.prop.calls);

		start(EditorBench.BOX.warp());
		check(EditorBench.HOST.lastShown() == CtrmapMainframe.mWarpEditForm,
				"the Warp tool puts the warp form in the side panel");

		start(EditorBench.BOX.trigger());
		check(EditorBench.HOST.lastShown() == CtrmapMainframe.mTriggerEditForm,
				"the Trigger tool puts the trigger form in the side panel");

		start(EditorBench.BOX.camera());
		check(EditorBench.HOST.lastShown() == CtrmapMainframe.mCamScrollPane,
				"the Camera tool puts the camera form in the side panel");

		//every one of those let go of the 3D navigator on its way in, which is
		//the other half of taking a tool in hand
		check(EditorBench.HOST.naviReleases >= 7,
				"and each of them released the 3D navigator as it started ("
				+ EditorBench.HOST.naviReleases + ")");

		start(EditorBench.BOX.set()).onToolShutdown();
		check(Boolean.FALSE.equals(EditorBench.field(EditorBench.tiles, "isLocked")),
				"leaving the Set tool unlocks the tile inspector again");
	}

	/** Takes a tool in hand, which is what the selection does once it has built one. */
	static AbstractTool start(AbstractTool tool) {
		tool.start();
		return tool;
	}

	/** The side panel's component, unwrapped from the scroll pane switchToolUI puts round a bare form. */
	static Component rightForm() {
		Component c = EditorBench.worldSplit().getRightComponent();
		if (c instanceof JScrollPane && c != CtrmapMainframe.mCamScrollPane) {
			return ((JScrollPane) c).getViewport().getView();
		}
		return c;
	}

	/**
	 * Clicking a warp box picks that warp up in the form, and dragging it moves
	 * the warp to the tile under the cursor, converted back into the world
	 * coordinates the file stores.
	 */
	static void warpToolPicksTheWarpUnderTheCursor() throws Exception {
		WarpTool t = warpTool();
		ZoneEntities e = warpFixture();
		ZoneEntities.Warp w0 = e.warps.get(0);
		ZoneEntities.Warp w1 = e.warps.get(1);
		check(EditorBench.warps.warp == w1, "fixture: the form is on warp 1");

		t.onTileClick(EditorBench.click(25, 35, false));
		check(EditorBench.warps.warp == w0, "clicking warp 0's box shows warp 0 in the form");

		EditorBench.warps.showEntry(1);
		check(EditorBench.warps.warp == w1, "fixture: back on warp 1");
		t.onTileClick(EditorBench.click(399, 399, false));
		check(EditorBench.warps.warp == w1, "a click on empty map changes nothing");

		t.onTileMouseDown(EditorBench.press(25, 35, false));
		check(EditorBench.warps.warp == w0, "pressing on warp 0's box picks it up");
		e.modified = false;
		EditorBench.hilight(7, 8);
		t.onTileMouseDragged(EditorBench.drag(70, 80, false));
		check(w0.x == 7 * 18 + 9 && w0.y == 8 * 18 + 9,
				"the drag writes the tile back as world coordinates (" + w0.x + ", " + w0.y
				+ "), tile centre rather than tile corner");
		check(e.modified, "and marks the zone modified");
		check(w1.x == 10 * 18 + 9, "the warp that was not grabbed did not move");

		EditorBench.hilight(-1, -1);
		int held = w0.x;
		t.onTileMouseDragged(EditorBench.drag(0, 0, false));
		check(w0.x == held, "a drag off the map leaves the warp where it was");

		EditorBench.hilight(9, 9);
		t.onTileMouseUp(EditorBench.release(90, 90, false));
		t.onTileMouseDragged(EditorBench.drag(90, 90, false));
		check(w0.x == held, "and after the release the drag no longer moves it");
	}

	/**
	 * The same for triggers, with one thing warps do not have: two lists. The
	 * hit test tries type 1 first and only then falls through to type 2, and
	 * picking a type-2 trigger has to switch the form's list dropdown as well,
	 * or the fields below would be editing a record from the other list.
	 */
	static void triggerToolPicksTheTriggerUnderTheCursor() throws Exception {
		TriggerTool t = triggerTool();
		ZoneEntities e = triggerFixture();
		EditorBench.triggers.loadFromEntities(e);
		ZoneEntities.Trigger t1a = e.triggers1.get(0);
		ZoneEntities.Trigger t1b = e.triggers1.get(1);
		ZoneEntities.Trigger t2a = e.triggers2.get(0);

		t.onTileClick(EditorBench.click(105, 35, false));
		check(EditorBench.triggers.trigger == t1b, "clicking the second type-1 trigger selects it");

		t.onTileClick(EditorBench.click(205, 35, false));
		check(EditorBench.triggers.trigger == t2a,
				"clicking a type-2 trigger falls through to the second list and selects it there");
		JComboBox<?> typeBox = (JComboBox<?>) EditorBench.field(EditorBench.triggers, "typeBox");
		check(typeBox.getSelectedIndex() == 1,
				"and switches the form's list dropdown to type 2, so the fields below edit that record");

		t.onTileClick(EditorBench.click(399, 399, false));
		check(EditorBench.triggers.trigger == t2a, "a click on empty map changes nothing");

		t.onTileMouseDown(EditorBench.press(25, 35, false));
		check(EditorBench.triggers.trigger == t1a, "pressing on the first type-1 trigger picks it up");
		e.modified = false;
		EditorBench.hilight(30, 31);
		t.onTileMouseDragged(EditorBench.drag(300, 310, false));
		check(t1a.x == 30 && t1a.y == 31,
				"the drag moves it to the tile under the cursor (triggers are stored in tiles, not world units)");
		check(e.modified, "and marks the zone modified");
		check(t1b.x == 10, "the trigger that was not grabbed did not move");

		EditorBench.hilight(-1, -1);
		t.onTileMouseDragged(EditorBench.drag(0, 0, false));
		check(t1a.x == 30, "a drag off the map leaves it where it was");

		EditorBench.hilight(5, 5);
		t.onTileMouseUp(EditorBench.release(50, 50, false));
		t.onTileMouseDragged(EditorBench.drag(50, 50, false));
		check(t1a.x == 30, "and after the release the drag no longer moves it");
	}

	/**
	 * The camera tool's one job on a click: find the camera whose bounds cover
	 * the highlighted tile and switch the form to it, unless it is already the
	 * one being edited. Switching to the camera already open would throw away
	 * whatever the user had half-typed into the fields, because the switch
	 * commits first.
	 */
	static void cameraToolSwitchesCamera(File dump) throws Exception {
		CameraDataFile cdf = cameraFile(dump);
		if (cdf == null) {
			System.out.println("  skip: no dump at " + dump + " - CameraDataFile only parses an AreaData container");
			return;
		}
		cdf.camData.clear();
		cdf.camData.add(camera(2, 3, 5, 6));
		cdf.camData.add(camera(20, 3, 25, 6));
		cdf.numEntries = 2;
		EditorBench.cam.f = cdf;
		EditorBench.cam.loaded = true;
		EditorBench.cam.cam = cdf.camData.get(0);
		EditorBench.cam.camIndex = 0;

		BufferedImage img = EditorBench.canvas();
		Graphics g = img.getGraphics();
		cameraTool().drawOverlay(g, 0, 0, 10d);
		g.dispose();
		check(rgb(img, 50, 60) == (Color.WHITE.getRGB() & 0xFFFFFF), "camera 0's area is drawn as a white box");
		check(rgb(img, 250, 60) == (Color.WHITE.getRGB() & 0xFFFFFF), "and so is camera 1's");
		check(rgb(img, 20, 30) == (Color.RED.getRGB() & 0xFFFFFF), "the camera being edited is framed red");
		check(rgb(img, 200, 30) == (Color.BLACK.getRGB() & 0xFFFFFF), "the others in black");
		check(rgb(img, 350, 350) == BG, "and nothing is drawn where no camera reaches");

		CameraTool t = cameraTool();
		EditorBench.cam.cam = cdf.camData.get(0);
		EditorBench.cam.calls.clear();
		EditorBench.hilight(22, 4);
		t.onTileClick(EditorBench.click(220, 40, false));
		check(EditorBench.cam.calls.equals(Arrays.asList("commitAndSwitch 1")),
				"clicking inside camera 1's area switches the form to it: " + EditorBench.cam.calls);

		EditorBench.cam.cam = cdf.camData.get(1);
		EditorBench.cam.calls.clear();
		EditorBench.hilight(3, 4);
		t.onTileClick(EditorBench.click(30, 40, false));
		check(EditorBench.cam.calls.equals(Arrays.asList("commitAndSwitch 0")), "and back again: " + EditorBench.cam.calls);

		EditorBench.cam.cam = cdf.camData.get(0);
		EditorBench.cam.calls.clear();
		t.onTileClick(EditorBench.click(30, 40, false));
		check(EditorBench.cam.calls.isEmpty(),
				"clicking the camera already being edited switches nothing, so half-typed fields survive: "
				+ EditorBench.cam.calls);

		EditorBench.cam.calls.clear();
		EditorBench.hilight(38, 38);
		t.onTileClick(EditorBench.click(380, 380, false));
		check(EditorBench.cam.calls.isEmpty(), "and a click outside every camera area switches nothing: " + EditorBench.cam.calls);

		EditorBench.map.loaded = false;
		img = EditorBench.canvas();
		g = img.getGraphics();
		cameraTool().drawOverlay(g, 0, 0, 10d);
		g.dispose();
		check(rgb(img, 50, 60) == BG, "with no map loaded the camera overlay draws nothing");
		EditorBench.map.loaded = true;
	}

	// ---- fixtures -----------------------------------------------------------

	/** Two type-1 triggers on tiles (2,3) and (10,3), one type-2 on (20,3), all two tiles square. */
	static ZoneEntities triggerFixture() {
		ZoneEntities e = EditorBench.emptyEntities();
		e.triggers1.add(trigger(2, 3));
		e.triggers1.add(trigger(10, 3));
		e.triggers2.add(trigger(20, 3));
		e.trigger1Count = 2;
		e.trigger2Count = 1;
		e.modified = false;
		return e;
	}

	static ZoneEntities.Trigger trigger(int x, int y) {
		ZoneEntities.Trigger t = new ZoneEntities.Trigger();
		t.x = x;
		t.y = y;
		t.w = 2;
		t.h = 2;
		return t;
	}

	/**
	 * Two warps on tiles (2,3) and (10,3), with the form's dropdown genuinely on
	 * warp 1.
	 *
	 * <p>The form is populated without {@code loadFromEntities}, which needs
	 * every zone in the game loaded before it can label the destination
	 * dropdown. What the tool depends on is that the dropdown has an entry per
	 * warp and that selecting one really runs the form's own listener, and both
	 * hold here: the selection is moved through the combo box, not by assigning
	 * the field.
	 */
	static ZoneEntities warpFixture() throws Exception {
		ZoneEntities e = EditorBench.emptyEntities();
		e.warps.add(warp(2, 3));
		e.warps.add(warp(10, 3));
		e.warpCount = 2;
		e.modified = false;
		EditorBench.warps.loaded = false;
		EditorBench.warps.e = e;
		@SuppressWarnings("unchecked")
		JComboBox<String> box = (JComboBox<String>) EditorBench.field(EditorBench.warps, "entryBox");
		box.removeAllItems();
		for (int i = 0; i < e.warpCount; i++) {
			box.addItem(i + " - test");
		}
		if (EditorBench.warps.transitionModel.getSize() == 0) {
			//the six rows every game in the family shares - what
			//WarpTransitions answers with no workspace open
			EditorBench.warps.fillTransitionDropdown();
		}
		EditorBench.warps.loaded = true;
		box.setSelectedIndex(1);
		return e;
	}

	/** A two-tile-square warp whose world coordinates put it on the given tile. */
	static ZoneEntities.Warp warp(int tx, int ty) {
		ZoneEntities.Warp w = new ZoneEntities.Warp();
		w.x = tx * 18 + 9;
		w.y = ty * 18 + 9;
		w.w = 2;
		w.h = 2;
		return w;
	}

	static CameraData camera(int x1, int y1, int x2, int y2) {
		CameraData c = new CameraData();
		c.boundX1 = (short) x1;
		c.boundY1 = (short) y1;
		c.boundX2 = (short) x2;
		c.boundY2 = (short) y2;
		c.layer = 0;
		return c;
	}

	/**
	 * The first AreaData container in the dump that parses as camera data, so
	 * there is a real CameraDataFile to hold hand-built cameras. Null with no
	 * dump, which is the only reason the camera checks can be skipped.
	 */
	static CameraDataFile cameraFile(File dump) throws Exception {
		if (!dump.isDirectory()) {
			return null;
		}
		//the dump IS an ORAS dump; nothing here opens a workspace, so the game
		//is named rather than looked up on a session that was never installed
		File garcFile = new File(dump.getAbsolutePath()
				+ Workspace.getArchivePath(ArchiveType.AREA_DATA, GameType.ORAS));
		if (!garcFile.isFile()) {
			return null;
		}
		GARC garc = new GARC(garcFile);
		//a scratch game for the area containers to report to, for the same reason
		FakeGameFiles game = new FakeGameFiles();
		for (int i = 0; i < Math.min(8, garc.length); i++) {
			File tmp = File.createTempFile("ctrmap_tooltest_ad", ".bin");
			tmp.deleteOnExit();
			Files.write(tmp.toPath(), garc.getDecompressedEntry(i));
			try {
				CameraDataFile cdf = new CameraDataFile(new AD(tmp, game));
				if (cdf.camData != null) {
					return cdf;
				}
			} catch (RuntimeException ex) {
				//not every area holds one in a shape this parses; try the next
			}
		}
		return null;
	}

	// ---- tools built without their side panel -------------------------------

	/*
	 * Every tool below is BUILT and not started. Each used to stub out
	 * onToolInit, "which is what AbstractTool's constructor calls and what
	 * needs the mainframe's JFrame" - a tool could not be made without one.
	 * A tool is handed its editor now and its setup runs in start(), which the
	 * selection calls, so building one is free and the stubs are gone with the
	 * reason for them.
	 */
	/**
	 * The tile inspector fires no repaint on ITSELF, and does not need to.
	 *
	 * <p>WHY THIS IS A CHECK AND NOT A GAP. The spinner listener in that form
	 * used to end in {@code firePropertyChange(TileMapPanel.PROP_REPAINT, false,
	 * true)} on the form. The only listener that property has anywhere is on the
	 * MAP VIEW - which is why PaintForm, Selector and TileUndo spell the same
	 * constant with the panel in front - so on the form, with changeSupport null,
	 * the call returned at once and had never once done anything. It was DELETED
	 * rather than given a live receiver, because unlike the prop editor's copies
	 * of the same line there is nothing behind it to recover: the Edit branch
	 * already calls map.scaleImage and the Fill branch map.updateAll, both ending
	 * in viewport.repaint(), and the Set branch driven here only loads the stamp
	 * the tool paints with, which is drawn nowhere. A live receiver would have
	 * been a NEW full-window repaint on every spinner tick.
	 *
	 * <p>Attaching a listener is what makes the absence assertable: with one
	 * attached the form's changeSupport is no longer null, so the deleted line
	 * would fire if it came back, and this section is what would say so.
	 */
	static void theTileInspectorAsksForNoRepaintOfItsOwn() throws Exception {
		final int[] heard = {0};
		java.beans.PropertyChangeListener ear = new java.beans.PropertyChangeListener() {
			@Override
			public void propertyChange(java.beans.PropertyChangeEvent e) {
				if (ctrmap.humaninterface.TileMapPanel.PROP_REPAINT.equals(e.getPropertyName())) {
					heard[0]++;
				}
			}
		};
		EditorBench.tiles.addPropertyChangeListener(ear);
		try {
			//the listener reads the HELD tool, so the inspector has to be driven with
			//one in hand. makeTile's four setValue(0) calls fire nothing on their own -
			//a SpinnerNumberModel only fires when the new value DIFFERS, and these
			//spinners are already 0 - so the explicit write below, which always differs,
			//is what a returning line would fire on.
			EditorBench.TOOLS.switchTo(EditorBench.BOX::set);
			SetTool held = (SetTool) EditorBench.TOOLS.current();
			javax.swing.JSpinner byte0 = (javax.swing.JSpinner) EditorBench.field(EditorBench.tiles, "byte0");
			int was = ((Integer) byte0.getValue()).intValue();
			byte0.setValue((was + 1) & 0xFF);
			check(held.actTileData[0] == (byte) ((was + 1) & 0xFF),
					"the inspector's byte spinner reaches the held Set tool: " + held.actTileData[0]);
			check(heard[0] == 0,
					"and the inspector asks for no repaint of its own, which nothing would hear: " + heard[0]);
		} finally {
			EditorBench.tiles.removePropertyChangeListener(ear);
			EditorBench.TOOLS.drop();
			EditorBench.tiles.lockTile(false);
		}
	}

	static PaintTool paintTool() {
		return (PaintTool) EditorBench.BOX.paint();
	}

	static SetTool setTool() {
		return (SetTool) EditorBench.BOX.set();
	}

	static GeoTool geoTool() {
		return (GeoTool) EditorBench.BOX.geometry();
	}

	static PropTool propTool() {
		return (PropTool) EditorBench.BOX.prop();
	}

	static TriggerTool triggerTool() {
		return (TriggerTool) EditorBench.BOX.trigger();
	}

	static WarpTool warpTool() {
		return (WarpTool) EditorBench.BOX.warp();
	}

	static CameraTool cameraTool() {
		return (CameraTool) EditorBench.BOX.camera();
	}

	/** One pixel, without the alpha byte a TYPE_INT_RGB image reports as opaque. */
	static int rgb(BufferedImage img, int x, int y) {
		return img.getRGB(x, y) & 0xFFFFFF;
	}

	/** How the last {@link #wantsNavi} answered, when it answered by throwing. */
	static final String[] how = {""};

	/** naviWanted, with a throw turned into an answer the check above can report. */
	static boolean wantsNavi(ctrmap.humaninterface.tools.ToolSelection sel) {
		how[0] = "";
		try {
			return ctrmap.humaninterface.H3DRenderingPanel.naviWanted(sel);
		} catch (RuntimeException died) {
			how[0] = " - it threw " + died;
			return true;
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
