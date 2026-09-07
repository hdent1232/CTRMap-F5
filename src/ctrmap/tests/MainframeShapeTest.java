package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.humaninterface.TileUndo;
import ctrmap.humaninterface.WorldEditorToolbar;
import java.awt.Component;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;

/**
 * What the main window is made of - its menus and toolbars - built headless
 * and compared with what the user is meant to see.
 *
 * <p>Until this suite existed nothing asserted that the Options menu still
 * held "Restore from pristine backup..." after the next edit to
 * {@code CtrmapMainframe}: every menu, item, button and listener was built
 * inside one 766-line method whose only test was starting the application.
 * Each of those groups is now a builder (or, for the tool row, a class) that
 * owns its widgets as locals or fields, touches no other widget while it is
 * made, and can therefore be made with no display and read back.
 *
 * <p>What is pinned:
 * <ul>
 * <li>the menu tree - every menu, every item, in order, separators where
 *     they are - one action per item, and the three tooltips that explain an
 *     item the label cannot;</li>
 * <li>the World Editor tool row: the ten tools in order, each named for a
 *     screen reader and explained in a tooltip, the label that follows the
 *     chosen tool, the Undo/Redo buttons that follow TileUndo, the 3D toggle
 *     that asks the window rather than deciding for itself, and the two
 *     things other code asks it for by name (the Set and the Map Builder
 *     tool);</li>
 * <li>the map-actions row: five buttons, one action each, none of which
 *     steals the focus from the map;</li>
 * <li>the SHAPE of the window class: no anonymous {@code ActionListener}
 *     (a menu item or button names the method that does its work) and no
 *     preference node named after {@code getClass()} - four anonymous
 *     listeners once keyed the remembered folder of a file dialog on their
 *     own compiler-numbered name, so any anonymous class added above them
 *     silently forgot the folder;</li>
 * <li>a ratchet on the window's public static fields, the count the
 *     decomposition exists to bring down, so it cannot creep back up.</li>
 * </ul>
 *
 * <p>ORDER: needs no dump, no workspace, no scratch space; writes nothing.
 *
 * Usage: java ctrmap.tests.MainframeShapeTest [src-root]   (default "src")
 */
public class MainframeShapeTest {

	/**
	 * The menus as the user sees them: one line per menu, items in order,
	 * "|" between items, "---" where a separator sits. Edit this WITH the
	 * menu - that is the point.
	 */
	private static final String[] EXPECTED_MENUS = {
		"File: Open GR Mapfile | Open MapMatrix | Open Zone | Save | Pack Workspace | Deploy to emulator (mod)...",
		"Map: Map Builder (this zone) | Blank map canvas (this zone)... | Resize map (this zone)... | Edit area fog & lighting... | Fork map geometry (make zone independent)... | --- | Import map model (.bch)... | Export map region to OBJ (Blender)... | Import OBJ into map region (Blender)... | OBJ to collisions | Tileset Editor",
		"Zone: Rename zone (in-game name)... | Empty zone (clear contents)... | Find reusable base zones... | Remove added zones (restore stock 536)... | Custom battle facility here (clone a retail facility)",
		"Game Data: Edit trainer (party/battle)... | Edit battle facility opponents... | Edit shop inventories (Marts)... | Edit items (price, effects, name)... | Edit wild encounters (this zone)...",
		"Options: Setup wizard... | --- | Workspace settings | Restore from pristine backup... | Clean workspace",
		"Help: Check for updates... | --- | Support/Issue tracker | About",
	};

	/** The items whose label needs a sentence more; every other item has none. */
	private static final String[][] TOOLTIPS = {
		{"Open Zone", "Opens a single loose ZO file. To load a map from the game, use the zone dropdown in the \"Zone Loader\" tab instead."},
		{"Setup wizard...", "Point CTRMap at your game, step by step."},
		{"Restore from pristine backup...", "Put the whole game, or one damaged archive, back as it was when CTRMap first copied it."},
	};

	/** The tool row, left to right: tool buttons by action command, then the label, Undo/Redo, the view toggle. */
	private static final String EXPECTED_TOOL_ROW =
			"tool:edit tool:set tool:fill tool:cam tool:prop tool:npc tool:warp tool:trigger tool:paint tool:geo"
			+ " label:Current tool: Edit | button:↶ Undo button:↷ Redo | toggle:3D view";

	/** What a screen reader calls each tool, in row order. */
	private static final String[] TOOL_NAMES = {"Edit", "Set", "Fill", "Camera", "Prop", "NPC", "Warp", "Trigger", "Map Builder", "Geometry"};

	private static final String EXPECTED_MAP_ROW = "label: Map:   button:Blank canvas button:Resize map button:Fog & lighting button:Encounters button:Fork geometry";

	/**
	 * Public static fields CtrmapMainframe may declare. 91 mutable ones (plus
	 * the final CM3DComponents list) when the decomposition began, every one
	 * assigned once inside createAndShowGUI. LOWER this as widgets find
	 * owners; raising it is the decision this exists to make visible.
	 */
	private static final int STATIC_CEILING = 39;

	static int fails = 0;

	static void check(boolean cond, String what) {
		if (!cond) {
			System.out.println("  FAIL: " + what);
			fails++;
		} else {
			System.out.println("  ok: " + what);
		}
	}

	public static void main(String[] args) throws Exception {
		//no display is needed to build a menu or a toolbar, and none may be
		//assumed: the battery runner passes no flags, and a window on the
		//owner's desktop would block the run until somebody closed it
		System.setProperty("java.awt.headless", "true");
		File src = new File(args.length > 0 ? args[0] : "src");

		JMenuBar bar = CtrmapMainframe.buildMenuBar();
		menuTree(bar);
		menuWired(bar);
		menuTooltips(bar);
		toolRow();
		mapRow();
		shape(new File(src, "ctrmap/CtrmapMainframe.java"));
		ceiling();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	// ------------------------------------------------------------- menu tree
	static void menuTree(JMenuBar bar) {
		List<String> got = new ArrayList<>();
		for (int i = 0; i < bar.getMenuCount(); i++) {
			got.add(render(bar.getMenu(i)));
		}
		check(got.size() == EXPECTED_MENUS.length, "the menu bar holds " + EXPECTED_MENUS.length + " menus (it holds " + got.size() + ")");
		for (int i = 0; i < Math.min(got.size(), EXPECTED_MENUS.length); i++) {
			check(got.get(i).equals(EXPECTED_MENUS[i]), "menu " + (i + 1) + " reads: " + EXPECTED_MENUS[i]
					+ (got.get(i).equals(EXPECTED_MENUS[i]) ? "" : "\n        but is: " + got.get(i)));
		}
	}

	/** "Name: item | item | --- | item", exactly as a person would list it. */
	static String render(JMenu menu) {
		StringBuilder sb = new StringBuilder(menu.getText()).append(':');
		boolean first = true;
		for (Component c : menu.getMenuComponents()) {
			sb.append(first ? " " : " | ");
			first = false;
			if (c instanceof JPopupMenu.Separator) {
				sb.append("---");
			} else if (c instanceof JMenuItem) {
				sb.append(((JMenuItem) c).getText());
			} else {
				sb.append("<").append(c.getClass().getSimpleName()).append(">");
			}
		}
		return sb.toString();
	}

	static void menuWired(JMenuBar bar) {
		List<String> unwired = new ArrayList<>();
		int items = 0;
		for (JMenuItem it : items(bar)) {
			items++;
			int n = it.getActionListeners().length;
			if (n != 1) {
				unwired.add(it.getText() + " (" + n + " listeners)");
			}
		}
		check(items == 33, "33 menu items in all (" + items + ")");
		check(unwired.isEmpty(), "every item is wired to exactly one action" + (unwired.isEmpty() ? "" : " - not these: " + unwired));
	}

	static List<JMenuItem> items(JMenuBar bar) {
		List<JMenuItem> out = new ArrayList<>();
		for (int i = 0; i < bar.getMenuCount(); i++) {
			for (Component c : bar.getMenu(i).getMenuComponents()) {
				if (c instanceof JMenuItem) {
					out.add((JMenuItem) c);
				}
			}
		}
		return out;
	}

	static void menuTooltips(JMenuBar bar) {
		List<String> wrong = new ArrayList<>();
		for (JMenuItem it : items(bar)) {
			String want = null;
			for (String[] t : TOOLTIPS) {
				if (t[0].equals(it.getText())) {
					want = t[1];
				}
			}
			String have = it.getToolTipText();
			if (want == null ? have != null : !want.equals(have)) {
				wrong.add(it.getText() + " -> " + have);
			}
		}
		check(wrong.isEmpty(), "exactly the " + TOOLTIPS.length + " items that need a tooltip have theirs"
				+ (wrong.isEmpty() ? "" : " - wrong: " + wrong));
	}

	// -------------------------------------------------------------- tool row
	static void toolRow() {
		final List<String> commands = new ArrayList<>();
		final int[] toggles = {0};
		WorldEditorToolbar row = new WorldEditorToolbar(e -> commands.add(e.getActionCommand()), () -> toggles[0]++);

		String got = renderRow(row);
		check(got.equals(EXPECTED_TOOL_ROW), "the tool row reads: " + EXPECTED_TOOL_ROW
				+ (got.equals(EXPECTED_TOOL_ROW) ? "" : "\n        but is: " + got));

		List<JRadioButton> tools = new ArrayList<>();
		JButton undo = null, redo = null;
		JToggleButton view3D = null;
		for (Component c : row.getComponents()) {
			if (c instanceof JRadioButton) {
				tools.add((JRadioButton) c);
			} else if (c instanceof JToggleButton) {
				view3D = (JToggleButton) c;
			} else if (c instanceof JButton) {
				if (undo == null) {
					undo = (JButton) c;
				} else {
					redo = (JButton) c;
				}
			}
		}
		List<String> unnamed = new ArrayList<>();
		for (int i = 0; i < tools.size(); i++) {
			JRadioButton b = tools.get(i);
			String name = b.getAccessibleContext().getAccessibleName();
			String tip = b.getToolTipText();
			if (i >= TOOL_NAMES.length || !TOOL_NAMES[i].equals(name) || tip == null || tip.isEmpty()) {
				unnamed.add(b.getActionCommand() + " (" + name + ")");
			}
		}
		check(unnamed.isEmpty(), "every tool has its accessible name and a tooltip" + (unnamed.isEmpty() ? "" : " - not: " + unnamed));
		check(tools.get(0).isSelected() && "Current tool: Edit".equals(row.currentToolText()),
				"the row starts on the Edit tool and says so");
		check(undo != null && !undo.isEnabled() && redo != null && !redo.isEnabled(),
				"Undo and Redo start disabled - there is nothing to undo");
		check(undo != null && "Undo the last tile edit (Ctrl+Z)".equals(undo.getToolTipText())
				&& redo != null && "Redo the undone tile edit (Ctrl+Y)".equals(redo.getToolTipText()),
				"Undo and Redo name their shortcuts");
		check(undo != null && !undo.isFocusable() && !redo.isFocusable() && !view3D.isFocusable(),
				"none of the row's push buttons takes the focus off the map");

		//the two things other code asks for by name
		row.selectPaintTool();
		check(commands.equals(java.util.Arrays.asList("paint")) && "Current tool: Map Builder".equals(row.currentToolText())
				&& tools.get(8).isSelected(),
				"selectPaintTool() presses the Map Builder tool: the switch hears \"paint\" and the label follows (" + commands + ", " + row.currentToolText() + ")");
		row.selectSetTool();
		check(commands.equals(java.util.Arrays.asList("paint", "set")) && "Current tool: Set".equals(row.currentToolText())
				&& tools.get(1).isSelected(),
				"selectSetTool() presses the Set tool likewise (" + commands + ", " + row.currentToolText() + ")");

		//the view toggle asks the window; the window answers with setView3D
		check(view3D != null && !view3D.isSelected(), "the 3D toggle starts off - the 2D map is what opens");
		view3D.doClick();
		check(toggles[0] == 1, "pressing the toggle asks the window once (" + toggles[0] + ") and decides nothing itself");
		row.setView3D(true);
		check(view3D.isSelected(), "and the window's answer is what the toggle shows");
		row.setView3D(false);
		check(!view3D.isSelected(), "either way");

		//the undo buttons follow TileUndo through its listener, not a static
		final int[] fired = {0};
		TileUndo.addListener(() -> fired[0]++);
		TileUndo.clear();
		check(fired[0] == 1, "TileUndo tells its listeners when the history changes (" + fired[0] + ")");
		check(undo.isEnabled() == TileUndo.canUndo() && redo.isEnabled() == TileUndo.canRedo(),
				"and the buttons agree with it afterwards");
	}

	/** Left to right: "tool:<command>", "label:<text>", "button:<text>", "toggle:<text>", "|" for a separator. */
	static String renderRow(JToolBar row) {
		StringBuilder sb = new StringBuilder();
		for (Component c : row.getComponents()) {
			if (sb.length() > 0) {
				sb.append(' ');
			}
			if (c instanceof JRadioButton) {
				sb.append("tool:").append(((JRadioButton) c).getActionCommand());
			} else if (c instanceof JToggleButton) {
				sb.append("toggle:").append(((JToggleButton) c).getText());
			} else if (c instanceof JButton) {
				sb.append("button:").append(((JButton) c).getText());
			} else if (c instanceof JLabel) {
				sb.append("label:").append(((JLabel) c).getText());
			} else if (c instanceof JToolBar.Separator) {
				sb.append("|");
			} else {
				sb.append("<").append(c.getClass().getSimpleName()).append(">");
			}
		}
		return sb.toString();
	}

	// --------------------------------------------------------------- map row
	static void mapRow() {
		JToolBar row = CtrmapMainframe.buildMapActionsBar();
		String got = renderRow(row);
		check(got.equals(EXPECTED_MAP_ROW), "the map-actions row reads: " + EXPECTED_MAP_ROW
				+ (got.equals(EXPECTED_MAP_ROW) ? "" : "\n        but is: " + got));
		List<String> wrong = new ArrayList<>();
		for (Component c : row.getComponents()) {
			if (c instanceof AbstractButton) {
				AbstractButton b = (AbstractButton) c;
				if (b.getActionListeners().length != 1 || b.isFocusable() || b.getToolTipText() == null || b.getToolTipText().isEmpty()) {
					wrong.add(b.getText());
				}
			}
		}
		check(wrong.isEmpty(), "each map action has one action, a tooltip, and leaves the focus on the map"
				+ (wrong.isEmpty() ? "" : " - not: " + wrong));
		check(!row.isFloatable(), "the map-actions row is not a floating palette");
	}

	// ----------------------------------------------------------------- shape
	static void shape(File mainframe) throws Exception {
		if (!mainframe.isFile()) {
			check(false, "the window's source is at " + mainframe + " (pass the src root as args[0])");
			return;
		}
		String text = SourceSeamTest.stripComments(new String(Files.readAllBytes(mainframe.toPath()), StandardCharsets.UTF_8));
		check(!text.contains("new ActionListener()"),
				"the window declares no anonymous ActionListener - an item or button names the method that does its work");
		check(!text.contains("node(getClass()"),
				"no preference node is named after getClass() - an anonymous listener's name moves with every edit above it");
	}

	// --------------------------------------------------------------- ceiling
	static void ceiling() {
		List<String> names = new ArrayList<>();
		for (Field f : CtrmapMainframe.class.getDeclaredFields()) {
			int m = f.getModifiers();
			if (Modifier.isPublic(m) && Modifier.isStatic(m) && !f.isSynthetic()) {
				names.add(f.getName());
			}
		}
		System.out.println("  CtrmapMainframe public static fields: " + names.size() + " " + names);
		check(names.size() <= STATIC_CEILING, "the window's public static fields have not risen above "
				+ STATIC_CEILING + " (" + names.size() + ")");
	}
}
