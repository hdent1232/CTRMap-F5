package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.humaninterface.Selector;
import ctrmap.humaninterface.TilemapPanelInputManager;
import ctrmap.humaninterface.tools.AbstractTool;
import ctrmap.humaninterface.tools.CameraTool;
import ctrmap.humaninterface.tools.EditTool;
import ctrmap.humaninterface.tools.FillTool;
import ctrmap.humaninterface.tools.GeoTool;
import ctrmap.humaninterface.tools.NPCTool;
import ctrmap.humaninterface.tools.PaintTool;
import ctrmap.humaninterface.tools.PropTool;
import ctrmap.humaninterface.tools.SetTool;
import ctrmap.humaninterface.tools.TriggerTool;
import ctrmap.humaninterface.tools.WarpTool;
import java.awt.Graphics;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Characterization of {@link TilemapPanelInputManager}, the World Editor's
 * mouse router: the fifty-three lines every click on the 2D map goes through
 * before any tool sees it, and the one place the editor's current tool is
 * chosen.
 *
 * <p>WHY THIS EXISTS. Two jobs live in this class and neither had any coverage.
 *
 * <ol>
 * <li>It moves the tile cursor and then hands the event on. The ORDER is the
 *     contract: {@code moveSelector} runs BEFORE the tool is told, and every
 *     tool then reads {@link Selector#hilightTileX} rather than the mouse
 *     position. A router that told the tool first would hand every tool the
 *     PREVIOUS tile - an off-by-one-gesture that would look like nothing at all
 *     until a painted tile landed one square behind the cursor. So the checks
 *     here assert what the tool saw, not just that it was called.</li>
 * <li>It writes {@code CtrmapMainframe.tool} at ten sites, one per toolbar
 *     button, each with a label to match. That static is the whole editor's
 *     notion of what the mouse does, and a later step gives it an owner, so
 *     what a tool switch does today is pinned here: the outgoing tool is shut
 *     down FIRST, the new one is constructed (which is what puts its form in
 *     the side panel), and the label is set. Including what an unrecognised
 *     command does, which is not nothing.</li>
 * </ol>
 *
 * <p>The tool-switching checks need a display, because constructing any tool
 * runs its onToolInit and every onToolInit revalidates the mainframe's JFrame.
 * They print a skip without one; the battery runner passes no headless flag, so
 * they run there. Everything else in this suite runs anywhere.
 *
 * Usage: java ctrmap.tests.TilemapInputRouterTest
 */
public class TilemapInputRouterTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		EditorBench.install();
		try {
			theRouterListensToThePanel();
			theCursorFollowsTheMouse();
			theCursorLetsGoOffTheImage();
			everyGestureReachesTheToolWithTheTileUnderIt();
			aClickOffTheMapCancelsFirst();
			theWheelZoomsWithinItsLimits();
			theToolbarSwitchesTools();
		} finally {
			EditorBench.shutdown();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		//Explicit, because this suite builds a JFrame and a PropEditForm and
		//either can hold a non-daemon AWT thread open past the last check.
		System.exit(fails > 0 ? 1 : 0);
	}

	/**
	 * The router subscribes itself in its own constructor. Nothing else does it,
	 * so a router that stopped subscribing would leave the map view completely
	 * dead to the mouse with no error anywhere.
	 */
	static void theRouterListensToThePanel() {
		int wheelBefore = EditorBench.map.getMouseWheelListeners().length;
		int motionBefore = EditorBench.map.getMouseMotionListeners().length;
		int mouseBefore = EditorBench.map.getMouseListeners().length;
		TilemapPanelInputManager router = new TilemapPanelInputManager(EditorBench.map);
		check(Arrays.asList(EditorBench.map.getMouseWheelListeners()).contains(router)
				&& EditorBench.map.getMouseWheelListeners().length == wheelBefore + 1,
				"the router subscribes to the map view's wheel");
		check(Arrays.asList(EditorBench.map.getMouseMotionListeners()).contains(router)
				&& EditorBench.map.getMouseMotionListeners().length == motionBefore + 1,
				"and to its motion");
		check(Arrays.asList(EditorBench.map.getMouseListeners()).contains(router)
				&& EditorBench.map.getMouseListeners().length == mouseBefore + 1,
				"and to its buttons");
		EditorBench.map.removeMouseWheelListener(router);
		EditorBench.map.removeMouseMotionListener(router);
		EditorBench.map.removeMouseListener(router);
	}

	/**
	 * A mouse position becomes a tile. The map is one 40x40 region drawn at 400
	 * pixels square here, so a tile is ten pixels and the arithmetic is checkable
	 * by hand; what matters is that it is a floor, not a round, or the cursor
	 * would jump to the next tile halfway across the current one.
	 */
	static void theCursorFollowsTheMouse() {
		TilemapPanelInputManager router = router();
		EditorBench.hilight(-1, -1);

		router.mouseMoved(EditorBench.mouse(MouseEvent.MOUSE_MOVED, 0, 0, false));
		check(Selector.hilightTileX == 0 && Selector.hilightTileY == 0, "the top left pixel is tile (0,0)");

		router.mouseMoved(EditorBench.mouse(MouseEvent.MOUSE_MOVED, 9, 9, false));
		check(Selector.hilightTileX == 0 && Selector.hilightTileY == 0,
				"and so is the last pixel of that tile: the tile is floored, not rounded");

		router.mouseMoved(EditorBench.mouse(MouseEvent.MOUSE_MOVED, 10, 0, false));
		check(Selector.hilightTileX == 1 && Selector.hilightTileY == 0, "ten pixels along is tile (1,0)");

		router.mouseMoved(EditorBench.mouse(MouseEvent.MOUSE_MOVED, 135, 207, false));
		check(Selector.hilightTileX == 13 && Selector.hilightTileY == 20, "and (135,207) is tile (13,20)");

		router.mouseMoved(EditorBench.mouse(MouseEvent.MOUSE_MOVED, 399, 399, false));
		check(Selector.hilightTileX == 39 && Selector.hilightTileY == 39, "the last pixel of the image is the last tile");
	}

	/**
	 * Off the image the cursor is dropped, and -1 is what every tool tests for
	 * before it touches anything. A router that left the last tile highlighted
	 * would have the Set tool stamping a tile the mouse is no longer over.
	 */
	static void theCursorLetsGoOffTheImage() {
		TilemapPanelInputManager router = router();
		router.mouseMoved(EditorBench.mouse(MouseEvent.MOUSE_MOVED, 100, 100, false));
		check(Selector.hilightTileX == 10, "fixture: the cursor is on tile (10,10)");

		router.mouseMoved(EditorBench.mouse(MouseEvent.MOUSE_MOVED, 400, 100, false));
		check(Selector.hilightTileX == -1 && Selector.hilightTileY == -1,
				"one pixel past the right edge of the image drops the cursor");

		router.mouseMoved(EditorBench.mouse(MouseEvent.MOUSE_MOVED, 100, 400, false));
		check(Selector.hilightTileX == -1, "and so does one pixel below it");

		router.mouseMoved(EditorBench.mouse(MouseEvent.MOUSE_MOVED, 100, 100, false));
		check(Selector.hilightTileX == 10 && Selector.hilightTileY == 10, "and coming back picks it up again");
	}

	/**
	 * Every gesture the router forwards, and the tile the tool saw when it
	 * arrived. The press and the release are handed straight on; the move and
	 * the drag update the cursor FIRST, which is the ordering every tool depends
	 * on and the reason the assertions read the tile the tool was holding rather
	 * than just counting calls.
	 */
	static void everyGestureReachesTheToolWithTheTileUnderIt() {
		TilemapPanelInputManager router = router();
		SpyTool spy = new SpyTool();
		AbstractTool was = CtrmapMainframe.tool;
		try {
			CtrmapMainframe.tool = spy;
			router.mouseMoved(EditorBench.mouse(MouseEvent.MOUSE_MOVED, 5, 5, false));
			spy.calls.clear();

			router.mousePressed(EditorBench.press(55, 65, false));
			check(spy.calls.equals(Arrays.asList("down 55,65")),
					"a press goes to the tool untouched, pixels and all: " + spy.calls);

			spy.calls.clear();
			router.mouseDragged(EditorBench.drag(135, 207, false));
			check(spy.calls.equals(Arrays.asList("dragged 13,20")),
					"a drag moves the cursor to the tile under it and THEN tells the tool,"
					+ " so the tool acts on where the mouse is now: " + spy.calls);

			spy.calls.clear();
			router.mouseReleased(EditorBench.release(135, 207, false));
			check(spy.calls.equals(Arrays.asList("up")), "a release goes to the tool: " + spy.calls);

			spy.calls.clear();
			router.mouseClicked(EditorBench.click(135, 207, false));
			check(spy.calls.equals(Arrays.asList("click 13,20")),
					"a click on the map goes to the tool and nothing is cancelled: " + spy.calls);

			spy.calls.clear();
			router.mouseEntered(EditorBench.mouse(MouseEvent.MOUSE_ENTERED, 1, 1, false));
			router.mouseExited(EditorBench.mouse(MouseEvent.MOUSE_EXITED, 1, 1, false));
			check(spy.calls.isEmpty(), "entering and leaving the map view reach no tool at all: " + spy.calls);

			spy.calls.clear();
			router.mouseMoved(EditorBench.mouse(MouseEvent.MOUSE_MOVED, 200, 200, false));
			check(spy.calls.isEmpty(), "and a plain move reaches no tool either, only the cursor: " + spy.calls);
			check(Selector.hilightTileX == 20 && Selector.hilightTileY == 20, "which did move");
		} finally {
			CtrmapMainframe.tool = was;
		}
	}

	/**
	 * A click that lands off the map cancels whatever the tool had pending
	 * BEFORE it hands the click on. That is how a half-placed building or a
	 * half-drawn selection is dropped, and the order is the whole point: the
	 * cancel has to happen first, and the tool still gets the click.
	 */
	static void aClickOffTheMapCancelsFirst() {
		TilemapPanelInputManager router = router();
		SpyTool spy = new SpyTool();
		AbstractTool was = CtrmapMainframe.tool;
		try {
			CtrmapMainframe.tool = spy;
			router.mouseMoved(EditorBench.mouse(MouseEvent.MOUSE_MOVED, 500, 500, false));
			check(Selector.hilightTileX == -1, "fixture: the cursor is off the map");
			spy.calls.clear();
			router.mouseClicked(EditorBench.click(500, 500, false));
			check(spy.calls.equals(Arrays.asList("cancel", "click -1,-1")),
					"a click off the map cancels first and then delivers the click anyway: " + spy.calls);

			router.mouseMoved(EditorBench.mouse(MouseEvent.MOUSE_MOVED, 100, 100, false));
			spy.calls.clear();
			router.mouseClicked(EditorBench.click(100, 100, false));
			check(spy.calls.equals(Arrays.asList("click 10,10")), "a click on the map cancels nothing: " + spy.calls);
		} finally {
			CtrmapMainframe.tool = was;
		}
	}

	/**
	 * The wheel is the zoom, one tenth of the scale per notch, and the map panel
	 * refuses anything outside its own limits. Both limits are asserted because
	 * the router does not check them itself: it hands the panel whatever the
	 * arithmetic produced, so a change on either side of that seam changes what
	 * a user can zoom to.
	 */
	static void theWheelZoomsWithinItsLimits() {
		TilemapPanelInputManager router = router();
		int rendersBefore = EditorBench.map.renders;

		EditorBench.map.tilemapScale = 1.0d;
		router.mouseWheelMoved(wheel(1));
		check(EditorBench.map.tilemapScale < 0.95d && EditorBench.map.tilemapScale > 0.85d,
				"one notch down zooms out by a tenth (scale " + EditorBench.map.tilemapScale + ")");
		check(EditorBench.map.renders > rendersBefore, "and re-renders the map at the new scale");

		router.mouseWheelMoved(wheel(-1));
		check(EditorBench.map.tilemapScale > 0.95d && EditorBench.map.tilemapScale <= 1.0d,
				"one notch up puts it back (scale " + EditorBench.map.tilemapScale + ")");

		EditorBench.map.tilemapScale = 1.0d;
		router.mouseWheelMoved(wheel(-1));
		check(EditorBench.map.tilemapScale == 1.0d, "at full size the wheel cannot zoom in any further");

		EditorBench.map.tilemapScale = 0.1d;
		router.mouseWheelMoved(wheel(1));
		check(EditorBench.map.tilemapScale == 0.1d, "and near the floor it cannot zoom out any further");

		EditorBench.map.loaded = false;
		EditorBench.map.tilemapScale = 0.5d;
		router.mouseWheelMoved(wheel(1));
		check(EditorBench.map.tilemapScale == 0.5d, "with no map loaded the wheel does nothing");
		EditorBench.map.loaded = true;
		EditorBench.map.tilemapScale = 1.0d;
	}

	/**
	 * The ten toolbar commands, which are the only writers of
	 * {@code CtrmapMainframe.tool}. For each: the outgoing tool is shut down
	 * first, the field holds an instance of the right class, and the status
	 * label names it. The label is the only thing on screen that says which tool
	 * the mouse is holding, so a command that set the field and not the label
	 * would leave a user certain they were painting when they were deleting.
	 *
	 * <p>The two halves have separate owners now: {@link
	 * ctrmap.humaninterface.WorldEditorToolbar} sets the label from the button's
	 * own listener and then hands the command to this router, which makes the
	 * tool. So the ten are driven the way the user drives them - by PRESSING
	 * the button - because that is the only path on which both things happen,
	 * and a router asserted through a synthetic ActionEvent would no longer be
	 * asked about the label at all. The unknown-command pin below still goes
	 * straight to the router, because no button carries such a command.
	 *
	 * <p>PINNED: an action command the switch does not know shuts the current
	 * tool DOWN and then leaves it in place. The tool the mouse still routes to
	 * has had its onToolShutdown run - for the Set and Fill tools that means the
	 * tile inspector was unlocked underneath it, and for the Geometry tool that
	 * its selection was stored and cleared. Nothing in the editor sends such a
	 * command today; it is asserted because the field is about to get an owner
	 * and this is what the owner would inherit.
	 */
	static void theToolbarSwitchesTools() {
		if (!EditorBench.frameAvailable()) {
			System.out.println("  skip: no display, and every tool's onToolInit revalidates the mainframe's JFrame"
					+ " - the ten tool-switch sites are not measured here");
			return;
		}
		String[] commands = {"edit", "set", "fill", "cam", "prop", "npc", "warp", "trigger", "paint", "geo"};
		Class<?>[] classes = {
			EditTool.class, SetTool.class, FillTool.class, CameraTool.class, PropTool.class,
			NPCTool.class, WarpTool.class, TriggerTool.class, PaintTool.class, GeoTool.class
		};
		String[] labels = {
			"Current tool: Edit", "Current tool: Set", "Current tool: Fill", "Current tool: Camera",
			"Current tool: Prop", "Current tool: NPC", "Current tool: Warp", "Current tool: Trigger",
			"Current tool: Map Builder", "Current tool: Geometry"
		};
		TilemapPanelInputManager router = router();
		AbstractTool was = CtrmapMainframe.tool;
		java.awt.event.ActionListener wasSwitch = EditorBench.toolSwitch;
		List<javax.swing.JRadioButton> buttons = EditorBench.toolButtons();
		try {
			EditorBench.toolSwitch = router;
			check(buttons.size() == commands.length,
					"the tool row carries one button per command (" + buttons.size() + ")");
			for (int i = 0; i < commands.length; i++) {
				check(commands[i].equals(buttons.get(i).getActionCommand()),
						"button " + i + " sends \"" + commands[i] + "\" (it sends \""
						+ buttons.get(i).getActionCommand() + "\")");
				SpyTool outgoing = new SpyTool();
				CtrmapMainframe.tool = outgoing;
				EditorBench.setCurrentToolText("");
				buttons.get(i).doClick();
				check(outgoing.calls.equals(Arrays.asList("shutdown")),
						"\"" + commands[i] + "\" shuts the outgoing tool down first: " + outgoing.calls);
				check(CtrmapMainframe.tool != null && CtrmapMainframe.tool.getClass() == classes[i],
						"\"" + commands[i] + "\" installs a " + classes[i].getSimpleName()
						+ " (it installed " + (CtrmapMainframe.tool == null ? "nothing" : CtrmapMainframe.tool.getClass().getSimpleName()) + ")");
				check(labels[i].equals(EditorBench.currentToolText()),
						"and says so: \"" + EditorBench.currentToolText() + "\"");
			}

			//an unrecognised command
			SpyTool stranded = new SpyTool();
			CtrmapMainframe.tool = stranded;
			EditorBench.setCurrentToolText("Current tool: Geometry");
			router.actionPerformed(new ActionEvent(EditorBench.map, ActionEvent.ACTION_PERFORMED, "no such tool"));
			check(CtrmapMainframe.tool == stranded,
					"PINNED: an unknown command leaves the same tool in the field");
			check(stranded.calls.equals(Arrays.asList("shutdown")),
					"PINNED: but only after shutting it down, so the tool the mouse still routes to has already been"
					+ " told it was leaving: " + stranded.calls);
			check("Current tool: Geometry".equals(EditorBench.currentToolText()),
					"and the label still names the tool that is no longer initialised");
		} finally {
			CtrmapMainframe.tool = was;
			EditorBench.toolSwitch = wasSwitch;
		}
	}

	// ---- fixtures -----------------------------------------------------------

	/**
	 * A router that is not subscribed to the panel, so the checks drive its
	 * handlers directly and one synthetic event cannot be delivered twice.
	 */
	static TilemapPanelInputManager router() {
		TilemapPanelInputManager router = new TilemapPanelInputManager(EditorBench.map);
		EditorBench.map.removeMouseWheelListener(router);
		EditorBench.map.removeMouseMotionListener(router);
		EditorBench.map.removeMouseListener(router);
		return router;
	}

	static MouseWheelEvent wheel(int rotation) {
		return new MouseWheelEvent(EditorBench.map, MouseEvent.MOUSE_WHEEL, 0L, 0, 200, 200, 0, false,
				MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, rotation);
	}

	/**
	 * A tool that writes down what it was handed. Each mouse record carries the
	 * tile the SELECTOR was on at the moment the tool was called, which is the
	 * only way to see whether the router updated the cursor before or after
	 * forwarding the event.
	 */
	static final class SpyTool extends AbstractTool {

		final List<String> calls = new ArrayList<String>();

		@Override
		public void onToolInit() {
			//nothing: this tool has no form, and building one would need the window
		}

		@Override
		public void onToolShutdown() {
			calls.add("shutdown");
		}

		@Override
		public void fireCancel() {
			calls.add("cancel");
		}

		@Override
		public void drawOverlay(Graphics g, int imgstartx, int imgstarty, double globimgdim) {
			calls.add("overlay");
		}

		@Override
		public boolean getSelectorEnabled() {
			return true;
		}

		@Override
		public boolean getNaviEnabled() {
			return false;
		}

		@Override
		public void onTileClick(MouseEvent e) {
			calls.add("click " + Selector.hilightTileX + "," + Selector.hilightTileY);
		}

		@Override
		public void onTileMouseDown(MouseEvent e) {
			calls.add("down " + e.getX() + "," + e.getY());
		}

		@Override
		public void onTileMouseUp(MouseEvent e) {
			calls.add("up");
		}

		@Override
		public void onTileMouseDragged(MouseEvent e) {
			calls.add("dragged " + Selector.hilightTileX + "," + Selector.hilightTileY);
		}

		@Override
		public void updateComponents() {
			calls.add("update");
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
