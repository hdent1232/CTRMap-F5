package ctrmap.tests;

import ctrmap.humaninterface.WorldEditorToolbar;
import java.awt.Component;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * <li>the three action rows - the map actions under the tool row, the Zone
 *     Loader's zone actions, the Extras bar - each button with one action and
 *     a tooltip, none of which steals the focus;</li>
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
		"Map: Map Builder (this zone) | Blank map canvas (this zone)... | Resize map (this zone)... | Edit area fog & lighting... | Make zone independent (own map, own area)... | --- | Import map model (.bch)... | Export map region to OBJ (Blender)... | Import OBJ into map region (Blender)... | OBJ to collisions | Tileset Editor",
		"Zone: Connect zones through a warp... | Rename zone (in-game name)... | Empty zone (clear contents)... | Find reusable base zones... | Remove added zones (restore stock 536)... | Custom battle facility here...",
		"Game Data: Edit trainer (party/battle)... | Edit battle facility opponents... | Edit shop inventories (Marts)... | Edit items (price, effects, name)... | Edit wild encounters (this zone)...",
		"Options: Setup wizard... | --- | Workspace settings | Restore from pristine backup... | Clean workspace",
		"Help: Quick start guide | Check for updates... | --- | Support/Issue tracker | About",
	};

	/** The items whose label needs a sentence more; every other item has none. */
	private static final String[][] TOOLTIPS = {
		{"Open Zone", "Opens a single loose ZO file. To load a map from the game, use the zone dropdown in the \"Zone Loader\" tab instead."},
		{"Setup wizard...", "Point CTRMap at your game, step by step."},
		{"Restore from pristine backup...", "Put the whole game, or one damaged archive, back as it was when CTRMap first copied it."},
		{"Quick start guide", "The guide that ships beside the program: what to do first, in order."},
	};

	/**
	 * The tool row, left to right: tool buttons by action command, then the
	 * label, Undo/Redo, and the two view switches.
	 *
	 * <p>RETARGETED, not relaxed, when "Fog off" was added beside "3D view".
	 * The row is pinned as a whole string so a button cannot be added, moved or
	 * lost without someone writing down that they meant to - which is exactly
	 * what this line is. Fog off belongs here because it is a VIEW switch: the
	 * area's real fog can make a cave impossible to work in, and hiding it for
	 * looking at is not an edit.
	 */
	private static final String EXPECTED_TOOL_ROW =
			"tool:edit tool:set tool:fill tool:cam tool:prop tool:npc tool:warp tool:trigger tool:paint tool:geo"
			+ " label:Current tool: Edit | button:↶ Undo button:↷ Redo | toggle:3D view toggle:Fog off";

	/** What a screen reader calls each tool, in row order. */
	private static final String[] TOOL_NAMES = {"Edit", "Set", "Fill", "Camera", "Prop", "NPC", "Warp", "Trigger", "Map Builder", "Geometry"};

	/** The Blender round trip joined the row (census M.146): it was in the Map menu and nowhere else. */
	private static final String EXPECTED_MAP_ROW = "label: Map:   button:Blank canvas button:Resize map button:Fog & lighting button:Encounters button:Make independent"
			+ " button:Import model button:Export OBJ button:Import OBJ";
	private static final String EXPECTED_ZONE_ROW = "label: Zone actions:   button:Connect zones button:Rename button:Empty button:Find reusable zones button:Remove added zones button:Custom battle facility";
	private static final String EXPECTED_EXTRAS_ROW = "button:Raw archive browser (Builder) button:Tileset editor button:Workspace & paths";

	/**
	 * FEATURES LIVE IN THEIR OWN UI AREA: how many items the main menu bar may hold.
	 *
	 * <p>The owner's standing rule since this project began - never menu-dumped, never a
	 * new window, seldom-used goes to the Extras tab, undo/redo everywhere. What enforced
	 * it was {@link #EXPECTED_MENUS}, an exact list that does go red when an item is
	 * added - but whose cheapest repair is to paste the new item into the list. That is a
	 * PIN, not a refusal: it asks to be updated, and updating it is one line.
	 *
	 * <p>So this sits beside it. Raising the number is a deliberate, single, visible edit
	 * that has to say what it is for, and a feature that belongs in a tab no longer gets
	 * into the menu bar by accident. It may only fall.
	 */
	private static final int MENU_ITEM_CEILING = 35;

	static int fails = 0;

	/**
	 * The zone views whose clear() is empty ON PURPOSE, by the first statement of their show(), and
	 * why. Every other view's clear() must drop what it shows (F222): the Script view's was empty with
	 * a comment saying so, and a closed zone's script stayed editable.
	 */
	static final String[][] CLEAR_KEEPS = {
		{"mTileMapPanel.loadMatrix(", "the map view keeps the map it drew: blanking the viewport on a failed load"
				+ " would hide the map the user still has open; unloadEditors drops it by name"},
		{"mMtxPanel.loadMatrix(", "paired with the map view and dropped with it"},
		{"scene.rebuild();", "rebuilds the 3D scene from the views above it; with nothing open, nothing it drew is stale"},
	};

	/** The text between the brace that opens at or after {@code from} and the one that closes it. */
	static String braced(String src, int from) {
		int open = src.indexOf('{', from);
		int depth = 0;
		for (int i = open; i < src.length(); i++) {
			depth += src.charAt(i) == '{' ? 1 : src.charAt(i) == '}' ? -1 : 0;
			if (depth == 0) {
				return src.substring(open + 1, i);
			}
		}
		return "";
	}

	static void everyZoneViewClearsOrSaysWhy(File window) throws Exception {
		System.out.println("--- every zone view drops what it shows, or is recorded as keeping it (F222)");
		String src = new String(Files.readAllBytes(window.toPath()), StandardCharsets.UTF_8);
		List<String> views = new ArrayList<>();
		List<String> keepsUnsaid = new ArrayList<>();
		boolean scriptSeen = false;
		for (int at = src.indexOf("new ZoneEditors.ZoneView() {"); at >= 0;
				at = src.indexOf("new ZoneEditors.ZoneView() {", at + 1)) {
			String view = braced(src, at);
			String show = SourceSeamTest.stripComments(braced(view, view.indexOf("public void show("))).trim();
			String clear = SourceSeamTest.stripComments(braced(view, view.indexOf("public void clear()"))).trim();
			String key = show.isEmpty() ? "(a show that does nothing)" : show.split("\n")[0].trim();
			views.add(key);
			scriptSeen |= key.startsWith("mScriptPnl.loadScript(");
			if (clear.isEmpty()) {
				boolean said = false;
				for (String[] k : CLEAR_KEEPS) {
					said |= key.startsWith(k[0]);
				}
				if (!said) {
					keepsUnsaid.add(key);
				}
			}
		}
		check(views.size() >= 9 && scriptSeen, "control: the window's " + views.size() + " zone views were read, the"
				+ " script editor's among them");
		check(keepsUnsaid.isEmpty(), "every zone view drops what it shows when the zone closes, or is recorded as"
				+ " keeping it" + (keepsUnsaid.isEmpty() ? "" : " - these clear nothing and say nothing: " + keepsUnsaid
				+ ". Drop what the view shows, or record why it keeps it in CLEAR_KEEPS"));
	}

	/** A click on {@code caption}, as its mouse listeners take one: "" or what it threw. */
	static String clickCaption(javax.swing.JLabel caption) {
		try {
			java.awt.event.MouseEvent click = new java.awt.event.MouseEvent(caption, java.awt.event.MouseEvent.MOUSE_CLICKED,
					0, 0, 1, 1, 1, false);
			for (java.awt.event.MouseListener l : caption.getMouseListeners()) {
				l.mouseClicked(click);
			}
			return "";
		} catch (RuntimeException threw) {
			return " - threw " + threw;
		}
	}

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
		Headless.require();
		File src = new File(args.length > 0 ? args[0] : "src");
		everyMenuPathAMessageNamesExists(new File(src, "ctrmap"));
		everyMenuPathADocNamesExists(src.getAbsoluteFile().getParentFile());
		everyLabelADocNamesExists(src.getAbsoluteFile().getParentFile());
		theAtmosphereUiPromisesOnlyWhatItEdits(new File(src, "ctrmap"));
		theUnknownsAreSaidWhereTheyAreShown(new File(src, "ctrmap"), src.getAbsoluteFile().getParentFile());

		JMenuBar bar = TestWindow.buildMenuBar();
		menuTree(bar);
		theMenuBarMayNotGrow(bar);
		menuWired(bar);
		menuTooltips(bar);
		theAreaActionsAreDeclaredOnce(bar);
		noFeatureLivesOnlyInAMenu(bar);
		everyFileDialogRemembersItsFolder();
		toolRow();
		actionRow("map-actions", TestWindow.buildMapActionsBar(), EXPECTED_MAP_ROW);
		actionRow("Zone Loader", TestWindow.buildZoneActionsBar(), EXPECTED_ZONE_ROW);
		actionRow("Extras", TestWindow.buildExtrasBar(), EXPECTED_EXTRAS_ROW);
		shape(new File(src, "ctrmap/CtrmapMainframe.java"));
		cameraFraming(new File(src, "ctrmap/CtrmapMainframe.java"));
		theZoneActionsShareOneRow(src);
		theZoneButtonsSurviveALayoutPass();
		theLooseMapClearNamesEveryEntityForm(new File(src, "ctrmap/CtrmapMainframe.java"));
		everyZoneViewClearsOrSaysWhy(new File(src, "ctrmap/CtrmapMainframe.java"));
		windowFree(new File(src, "ctrmap/util/Bytes.java"));
		windowFree(new File(src, "ctrmap/humaninterface/Forms.java"));
		windowFree(new File(src, "ctrmap/humaninterface/Picking.java"));

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * The main menu bar may not grow. A new feature goes to its own UI area.
	 *
	 * <p>Counted from the real bar rather than from {@link #EXPECTED_MENUS}, so that
	 * editing the expected list does not quietly raise the ceiling as well - one edit,
	 * two places, and the second one is the one that has to be justified.
	 */
	static void theMenuBarMayNotGrow(JMenuBar bar) {
		int items = 0;
		for (JMenuItem it : items(bar)) {
			if (it != null) {
				items++;
			}
		}
		check(items <= MENU_ITEM_CEILING, "the main menu bar holds " + items + " item(s), at or under the ceiling "
			+ MENU_ITEM_CEILING + " - FEATURES LIVE IN THEIR OWN UI AREA, so a new one goes to its"
			+ " own tab (seldom-used: Extras) rather than onto this bar");
		check(Ratchet.tight(items, MENU_ITEM_CEILING), Ratchet.said("menu-item", items, MENU_ITEM_CEILING));
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
		//the count is EXPECTED_MENUS' (each item, exactly) and MENU_ITEM_CEILING's (the refusal);
		//an exact 'items == 35' beside them was a third copy that had to be edited by hand (F158)

		theZoneLoaderTabHoldsItsPreview();
		theZoneColumnUndoesTheWorkspacesOperations();
		browsingAZoneDoesNotLoadIt();
		theExtrasTabHoldsTheArchiveBrowser();
		theGameDataTabHoldsItsEditors();
		check(unwired.isEmpty(), "every item is wired to exactly one action" + (unwired.isEmpty() ? "" : " - not these: " + unwired));
		packSaysWhyWithNoWorkspace(bar);
		aboutSaysThisBuild(bar);
	}

	/**
	 * Help > About says this build - its version and this project - over the window, clicked through
	 * its own menu item (F243): it was a separate JFrame saying "CTRMAP R0" and linking only upstream.
	 */
	static void aboutSaysThisBuild(JMenuBar bar) {
		JMenuItem about = null;
		for (JMenuItem it : items(bar)) {
			if ("About".equals(it.getText())) {
				about = it;
			}
		}
		if (about == null) {
			check(false, "the menu bar holds About");
			return;
		}
		List<String> said = ctrmap.Ui.record();
		try {
			for (java.awt.event.ActionListener l : about.getActionListeners()) {
				l.actionPerformed(new java.awt.event.ActionEvent(about, java.awt.event.ActionEvent.ACTION_PERFORMED,
						about.getText()));
			}
		} finally {
			ctrmap.Ui.stopRecording();
		}
		String text = said.isEmpty() ? "" : said.get(0);
		check(said.size() == 1 && text.contains("CTRMap-F5 " + ctrmap.update.AppVersion.described())
				&& text.contains(ctrmap.update.UpdateChecker.REPO) && text.contains("HelloOO7"),
				"Help > About says this build's version and this project, and credits upstream, over the window ("
				+ text.replace("\n", " | ") + ")");
	}

	/**
	 * File > Pack Workspace with nothing open says why, clicked the way the user clicks it - through
	 * the item's own listener, not the action by name (F230): the item was wired straight to a pack
	 * that returned without a word when there was no workspace. The pack is then asked directly, as
	 * every other caller asks it: it says why, and the onDone it was handed does not run.
	 */
	static void packSaysWhyWithNoWorkspace(JMenuBar bar) {
		JMenuItem pack = null;
		for (JMenuItem it : items(bar)) {
			if ("Pack Workspace".equals(it.getText())) {
				pack = it;
			}
		}
		if (pack == null) {
			check(false, "the menu bar holds Pack Workspace");
			return;
		}
		ctrmap.Workspace.reset();
		List<String> said = ctrmap.Ui.record();
		try {
			for (java.awt.event.ActionListener l : pack.getActionListeners()) {
				l.actionPerformed(new java.awt.event.ActionEvent(pack, java.awt.event.ActionEvent.ACTION_PERFORMED,
						pack.getText()));
			}
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said.size() == 1 && said.get(0).startsWith("Pack workspace: Load a workspace first"),
				"Pack Workspace with no workspace open says why, through its own menu item (" + said + ")");
		final boolean[] ran = {false};
		List<String> direct = ctrmap.Ui.record();
		try {
			ctrmap.Workspace.packWorkspace(() -> ran[0] = true);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(direct.size() == 1 && direct.get(0).startsWith("Pack workspace: Load a workspace first") && !ran[0],
				"and a caller's pack with no workspace open says why and runs nothing after it (" + direct
				+ (ran[0] ? ", onDone ran" : "") + ")");
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
			Object area = it.getClientProperty(TestWindow.AREA_ACTION);
			if (area != null) {
				want = TestWindow.tooltipOf(area);
			}
			String have = it.getToolTipText();
			if (want == null ? have != null : !want.equals(have)) {
				wrong.add(it.getText() + " -> " + have);
			}
		}
		check(wrong.isEmpty(), "exactly the " + TOOLTIPS.length + " items that need a tooltip, and every area action,"
				+ " have theirs"
				+ (wrong.isEmpty() ? "" : " - wrong: " + wrong));
	}

	/**
	 * The menu items that are not area actions - not drawn as a button on their area's row - each with why the
	 * menu is where it belongs (census M.146). The Blender round trip was four Map-menu items and nothing else: the
	 * way to bring a map or a collision in from Blender was found only in a menu named after another part of the
	 * window. A new menu item is either an area action or argued here; one listed here that is gone is a stale
	 * argument and fails too.
	 */
	private static final String[][] MENU_AT_HOME = {
		{"Open GR Mapfile", "opens a file from disk, which is what the File menu is for"},
		{"Open MapMatrix", "opens a file from disk"},
		{"Open Zone", "opens a file from disk"},
		{"Save", "saves everything open at once - the window's own command, on Ctrl+S"},
		{"Pack Workspace", "acts on the whole workspace, not on one area of the window"},
		{"Deploy to emulator (mod)...", "acts on the whole workspace, on Ctrl+D"},
		{"Map Builder (this zone)", "picks the World Editor's paint tool, which is a button on the tool row itself"},
		{"Tileset Editor", "opens its editor in the Extras tab, where a seldom-used tool lives"},
		{"Edit trainer (party/battle)...", "opens its editor in the Game Data tab, which lists it"},
		{"Edit battle facility opponents...", "opens its editor in the Game Data tab, which lists it"},
		{"Edit shop inventories (Marts)...", "opens its editor in the Game Data tab, which lists it"},
		{"Edit items (price, effects, name)...", "opens its editor in the Game Data tab, which lists it"},
		{"Setup wizard...", "sets the program up, which is what Options is for"},
		{"Workspace settings", "sets the program up"},
		{"Restore from pristine backup...", "acts on the whole game folder, from the program's own backup"},
		{"Clean workspace", "acts on the whole workspace"},
		{"Quick start guide", "help about the program itself"},
		{"Check for updates...", "about the program itself"},
		{"Support/Issue tracker", "about the program itself"},
		{"About", "about the program itself"},
	};

	/**
	 * Every file dialog the window opens is made by the helpers that remember its folder (census L.31). The .bch
	 * import wrote the memory out again under a key of its own, and the OBJ round trip, the code.ips pick and the mod
	 * folder remembered nothing - the workflow that navigates most started from the default every time.
	 */
	static void everyFileDialogRemembersItsFolder() throws Exception {
		System.out.println("--- every file dialog in the window remembers its folder (census L.31)");
		String src = SourceSeamTest.stripComments(new String(Files.readAllBytes(new File("src/ctrmap/CtrmapMainframe.java").toPath()),
				StandardCharsets.UTF_8));
		String rest = src;
		for (String helper : new String[]{"private static JFileChooser openDialog(", "private static JFileChooser folderDialog(",
			"private static Preferences fileDialogPrefs("}) {
			String body = CollisionReadTest.body(src, helper);
			check(body != null, "control: " + helper + " is found");
			rest = body == null ? rest : rest.replace(body, "");
		}
		int choosers = rest.split("new JFileChooser\\(", -1).length - 1;
		int nodes = rest.split("Preferences\\.userRoot\\(\\)", -1).length - 1;
		check(choosers == 0 && nodes == 0, "the window makes a file dialog and reaches its preferences only through the helpers"
				+ " (" + choosers + " chooser(s) and " + nodes + " node(s) elsewhere)");
	}

	/**
	 * Every Map and Zone action is declared once and drawn from that declaration (F223). The menus
	 * and the two area rows were hand-written twice: the comments counting them were wrong both
	 * ways, the battle facility was two modes on the bar and one in the menu, and nothing compared
	 * them. Now every button on the Map and Zone rows must be a {@code CtrmapMainframe.AreaAction},
	 * and every one of those must be on exactly one menu item and one button.
	 */
	static void noFeatureLivesOnlyInAMenu(JMenuBar menus) {
		System.out.println("--- no feature lives only in a menu (census M.146)");
		java.util.Map<String, String> atHome = new java.util.LinkedHashMap<>();
		for (String[] row : MENU_AT_HOME) {
			atHome.put(row[0], row[1]);
		}
		List<String> unargued = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		int areaItems = 0;
		for (JMenuItem it : items(menus)) {
			if (it.getClientProperty(TestWindow.AREA_ACTION) != null) {
				areaItems++;
				continue;
			}
			seen.add(it.getText());
			if (!atHome.containsKey(it.getText())) {
				unargued.add(it.getText());
			}
		}
		List<String> stale = new ArrayList<>(atHome.keySet());
		stale.removeAll(seen);
		check(areaItems > 0 && !seen.isEmpty(), "control: the menus hold " + areaItems + " area actions and " + seen.size() + " other items");
		check(unargued.isEmpty(), "every menu item is an area action - a button on its area's row too - or argued as"
				+ " belonging in a menu" + (unargued.isEmpty() ? "" : " - these are in a menu and nowhere else: " + unargued));
		check(stale.isEmpty(), "and every argument names an item that is still there" + (stale.isEmpty() ? "" : " - gone: " + stale));
	}

	static void theAreaActionsAreDeclaredOnce(JMenuBar menus) {
		System.out.println("--- every Map and Zone action is declared once, in a menu and on its row (F223)");
		Map<Object, Integer> inMenus = new java.util.LinkedHashMap<>();
		Map<Object, Integer> onRows = new java.util.LinkedHashMap<>();
		for (JMenuItem it : items(menus)) {
			Object a = it.getClientProperty(TestWindow.AREA_ACTION);
			if (a != null) {
				inMenus.merge(a, 1, Integer::sum);
			}
		}
		List<String> handWritten = new ArrayList<>();
		int buttons = 0;
		//the Collision row too (census M.146): OBJ to collisions was a menu item and nothing else
		for (JToolBar row : new JToolBar[]{TestWindow.buildMapActionsBar(), TestWindow.buildZoneActionsBar(),
				TestWindow.buildCollisionActionsBar()}) {
			for (Component c : row.getComponents()) {
				if (!(c instanceof AbstractButton)) {
					continue;
				}
				buttons++;
				Object a = ((AbstractButton) c).getClientProperty(TestWindow.AREA_ACTION);
				if (a == null) {
					handWritten.add(((AbstractButton) c).getText());
				} else {
					onRows.merge(a, 1, Integer::sum);
				}
			}
		}
		check(buttons > 0 && !inMenus.isEmpty(), "control: the two rows hold " + buttons + " buttons and the menus "
				+ inMenus.size() + " area actions");
		check(handWritten.isEmpty(), "every button on the Map and Zone rows is a declared area action"
				+ (handWritten.isEmpty() ? "" : " - these were written by hand: " + handWritten));
		List<String> notOnce = new ArrayList<>();
		for (Object a : TestWindow.AREA_ACTION.getEnumConstants()) {
			int m = inMenus.getOrDefault(a, 0);
			int r = onRows.getOrDefault(a, 0);
			if (m != 1 || r != 1) {
				notOnce.add(a + " (menus " + m + ", rows " + r + ")");
			}
		}
		check(notOnce.isEmpty(), "and each of the " + TestWindow.AREA_ACTION.getEnumConstants().length
				+ " is on exactly one menu item and one button" + (notOnce.isEmpty() ? "" : " - these are not: " + notOnce));
	}

	/**
	 * The Zone Loader tab contains the zone preview, beside the panel it is about.
	 *
	 * <p>WHY THIS IS THE CHECK THAT MATTERED. The preview was built twice and shipped
	 * twice without ever being visible in the Zone Loader: once as a dialog behind a
	 * button on another bar, once as a window floating beside the dropdown popup. Both
	 * times every suite was green, because every suite asked whether the DECODE worked.
	 * The owner opened the tab, saw nothing, and said so - twice.
	 *
	 * <p>So this asks the only question that was actually being got wrong: is the thing
	 * in the tab. It needs no game, no display and no zone - absence is structural, and
	 * structure is exactly what can be read back without any of that.
	 */
	static void theZoneLoaderTabHoldsItsPreview() {
		System.out.println("--- the Zone Loader tab holds the zone preview, in the tab");
		javax.swing.JPanel stand = new javax.swing.JPanel();
		java.awt.Container tab = TestWindow.buildZoneTab(stand, new ctrmap.LoadedZone(), new ctrmap.humaninterface.tools.ToolSelection());
		java.awt.LayoutManager lay = tab.getLayout();
		Component east = lay instanceof java.awt.BorderLayout
			? ((java.awt.BorderLayout) lay).getLayoutComponent(java.awt.BorderLayout.EAST) : null;
		//the column can show a zone tool over the browser, and sits over the workspace's Undo and Redo (F209c), so
		//look INSIDE it - the column, then what it holds
		ctrmap.humaninterface.ZoneBrowserPane found = null;
		java.util.List<Component> look = new java.util.ArrayList<>();
		look.add(east);
		for (int depth = 0; depth < 2 && found == null; depth++) {
			java.util.List<Component> next = new java.util.ArrayList<>();
			for (Component in : look) {
				for (Component c : in instanceof java.awt.Container ? ((java.awt.Container) in).getComponents() : new Component[0]) {
					if (c instanceof ctrmap.humaninterface.ZoneBrowserPane) {
						found = (ctrmap.humaninterface.ZoneBrowserPane) c;
					}
					next.add(c);
				}
			}
			look = next;
		}
		check(found != null,
			"the zone BROWSER is IN the Zone Loader tab, beside the zone panel - not in a"
			+ " dialog and not in a window of its own (east = " + (east == null ? "nothing"
			: east.getClass().getSimpleName()) + ")");
		check(found != null && holds(found, found.preview()),
			"...with the live preview inside it, under the list of zones");
		check(((java.awt.BorderLayout) lay).getLayoutComponent(java.awt.BorderLayout.CENTER) == stand,
			"...with the zone panel still the middle of the tab");
		boolean deep = false;
		for (Component c : tab.getComponents()) {
			deep |= holds(c, found);
		}
		check(deep, "and it is a child of the tab, so it is on screen when the tab is");
	}
	/**
	 * Ctrl+Z in the zone column goes to the workspace's history, and that history takes a kept change back and puts it
	 * again (F209c). The window's structural operations - forks, clones, renames, removals, imports, resizes, Connect
	 * zones - are kept there; with the column owned by any other history, or by none, Ctrl+Z beside the zone list
	 * would take back an NPC move instead, or nothing.
	 */
	static void theZoneColumnUndoesTheWorkspacesOperations() {
		System.out.println("--- Ctrl+Z in the zone column takes back the workspace's last operation (F209c)");
		java.awt.Container tab = TestWindow.buildZoneTab(new javax.swing.JPanel(), new ctrmap.LoadedZone(),
				new ctrmap.humaninterface.tools.ToolSelection());
		Component east = ((java.awt.BorderLayout) tab.getLayout()).getLayoutComponent(java.awt.BorderLayout.EAST);
		ctrmap.CtrmapMainframe window = TestWindow.W;
		//ASKED OF ITS GETTER: the history is made when first asked for, so the field is empty in a window that never
		//asked - which made this control the only thing that failed when the column was taken out of the history
		ctrmap.humaninterface.SaveHistory ops = null;
		try {
			java.lang.reflect.Method getter = ctrmap.CtrmapMainframe.class.getDeclaredMethod("workspaceOps");
			getter.setAccessible(true);
			ops = window == null ? null : (ctrmap.humaninterface.SaveHistory) getter.invoke(window);
		} catch (ReflectiveOperationException notAsked) {
			System.out.println("  the window's workspaceOps() could not be asked: " + notAsked);
		}
		check(window != null && ops != null, "control: the window built the zone tab and holds the workspace's history");
		Component leaf = east;
		while (leaf instanceof java.awt.Container && ((java.awt.Container) leaf).getComponentCount() > 0) {
			leaf = ((java.awt.Container) leaf).getComponent(0);
		}
		check(leaf != east && ctrmap.humaninterface.UndoHistory.of(leaf, null) == ops.history(),
				"the zone column's Ctrl+Z goes to the workspace's history (from a " + leaf.getClass().getSimpleName() + ")");
		String back;
		String again;
		try {
			File f = File.createTempFile("ctrmap_workspace_op", ".bin");
			f.deleteOnExit();
			Files.write(f.toPath(), "before".getBytes(StandardCharsets.US_ASCII));
			try (ctrmap.WorkspaceUnit unit = ctrmap.WorkspaceUnit.begin("Test operation", null)) {
				ctrmap.WorkspaceUnit.write(f, "after".getBytes(StandardCharsets.US_ASCII));
				ops.keep(unit.commitKeeping());
			}
			ops.history().undo();
			back = new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII);
			ops.history().redo();
			again = new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII);
		} catch (java.io.IOException cannot) {
			back = again = "the check's own file could not be written: " + cannot;
		}
		check(back.equals("before") && again.equals("after"),
				"and its Undo takes a kept operation back and Redo puts it again (" + back + ", " + again + ")");
	}

	/**
	 * The Extras tab contains the raw archive browser, rather than a button that opens
	 * it in a window.
	 *
	 * <p>Same rule as the zone preview, same kind of check, and the second one written
	 * because the first was not enough on its own: this project has a standing rule that
	 * a feature lives in the part of the UI it belongs to, and nothing counted the
	 * windows, so the rule applied to nothing. DialogSeamTest counts them now and this
	 * says where this one went.
	 */
	static void theExtrasTabHoldsTheArchiveBrowser() {
		System.out.println("--- the Extras tab holds its tools, in the tab");
		javax.swing.JPanel tools = new javax.swing.JPanel();
		javax.swing.JPanel browser = new javax.swing.JPanel();
		java.awt.Container tab = TestWindow.buildExtrasTab(tools, browser);
		java.awt.LayoutManager lay = tab.getLayout();
		Component centre = lay instanceof java.awt.BorderLayout
			? ((java.awt.BorderLayout) lay).getLayoutComponent(java.awt.BorderLayout.CENTER) : null;
		check(holds(centre, tools), "the Extras tools are in the tab");
		TestWindow.showInExtras("Raw archive browser (Builder)", browser);
		check(holds(centre, browser), "...and a tool opened there lands in the same place -"
			+ " the raw archive browser, the tileset editor and the workspace settings were all"
			+ " windows, which is what this tab exists to stop");
		check(!holds(centre, tools), "...taking the place of the tools list while it is open");
	}

	/**
	 * The Game Data tab has somewhere for its editors to open, beside the subjects.
	 *
	 * <p>Five editors - trainers, facility opponents, shops, items, wild encounters -
	 * were modal windows opened from a column of buttons in this tab, which is to say
	 * the tab existed and held nothing but the buttons that opened windows. They are
	 * panels now and this is where they go. Without the host they would have nowhere
	 * to be shown and the buttons would do nothing visible, which is the failure this
	 * whole change exists to stop being possible.
	 */
	static void theGameDataTabHoldsItsEditors() {
		System.out.println("--- the Game Data tab holds its editors, beside the subjects");
		javax.swing.JPanel subjects = new javax.swing.JPanel();
		java.awt.Container tab = TestWindow.buildGameDataTab(subjects);
		java.awt.LayoutManager lay = tab.getLayout();
		Component centre = lay instanceof java.awt.BorderLayout
			? ((java.awt.BorderLayout) lay).getLayoutComponent(java.awt.BorderLayout.CENTER) : null;
		check(centre instanceof javax.swing.JSplitPane,
			"the Game Data tab is a split (" + (centre == null ? "nothing"
			: centre.getClass().getSimpleName()) + ")");
		if (!(centre instanceof javax.swing.JSplitPane)) {
			return;
		}
		javax.swing.JSplitPane split = (javax.swing.JSplitPane) centre;
		check(holds(split.getLeftComponent(), subjects), "...the subjects on the left");
		check(split.getRightComponent() != null,
			"...and a host on the right for the editor being used - five editors that used to"
			+ " be modal windows open into it");
		javax.swing.JLabel stand = new javax.swing.JLabel("an editor");
		TestWindow.showGameData("Trainer editor", stand);
		check(holds(split.getRightComponent(), stand),
			"...and an editor handed to it actually lands there, under its name");
		TestWindow.clearGameData();
	}
	/**
	 * Moving through the zone list DRAWS the zone. It does not open it.
	 *
	 * <p>WHAT THIS IS FOR, in the owner's words: "there is no way to scroll up and down
	 * the zone loader list and see the zones before you load or select them... you try and
	 * use the arrow keys and it automatically selects and loads the zone before you can
	 * even see it". Two builds of this feature hung a preview off the Load Zone dropdown,
	 * where choosing a row IS the load - so arrowing past three candidates opened three
	 * zones, and the preview showed up after the expensive thing it was meant to prevent
	 * had already happened. The asked-for shape was the atmosphere picker: a list, a live
	 * preview of the row you are on, and a button that commits.
	 *
	 * <p>So the separation between looking and opening is the feature, and this is the
	 * check on it. It needs no game and no display: the browser is handed a recorder in
	 * place of the thing that opens zones, and the recorder must stay empty while the
	 * selection moves.
	 */
	static void browsingAZoneDoesNotLoadIt() {
		System.out.println("--- looking through zones opens nothing until you ask");
		ctrmap.humaninterface.ZoneBrowserPane browser = new ctrmap.humaninterface.ZoneBrowserPane(new ctrmap.LoadedZone(), new ctrmap.humaninterface.tools.ToolSelection());
		final int[] opened = {-1};
		final int[] times = {0};
		browser.onLoad(new ctrmap.humaninterface.ZoneBrowserPane.Loader() {
			@Override
			public void load(int zoneIndex) {
				opened[0] = zoneIndex;
				times[0]++;
			}
		});
		browser.setZones(java.util.Arrays.asList(
			"Littleroot Town - 0", "Oldale Town - 7", "Route 101 - 23", "Mossdeep City - 19"));

		//arrowing down the list, which is what the owner was doing
		browser.zoneRows().setSelectedIndex(1);
		browser.zoneRows().setSelectedIndex(2);
		browser.zoneRows().setSelectedIndex(3);
		check(times[0] == 0, "moving through the list opens NOTHING (" + times[0]
			+ " zone(s) opened) - looking is free, which is the whole point of it");
		check(browser.highlighted() == 3, "...but the browser knows which zone you are on ("
			+ browser.highlighted() + ")");

		//and the button is the only thing that opens one
		browser.loadButton().doClick();
		check(times[0] == 1 && opened[0] == 3,
			"'" + ctrmap.humaninterface.ZoneBrowserPane.LOAD + "'" + " opens the highlighted zone,"
			+ " and only when pressed (opened " + opened[0] + ", " + times[0] + " time(s))");

		//the search filters rows, and a row still knows which zone it is
		browser.searchBox().setText("Mossdeep");
		browser.zoneRows().setSelectedIndex(0);
		check(browser.highlighted() == 3, "after searching, the first row is still zone 3 ("
			+ browser.highlighted() + ") - a filtered list that lost track of that would open"
			+ " the wrong zone");
		check(times[0] == 1, "...and filtering opened nothing either");
	}
	/**
	 * Every "Menu > Item" a user-facing message names is a path that exists.
	 *
	 * <p>WHAT THIS CAUGHT. Three messages told the user to run "Map > Fork area" and one
	 * suite pinned the wording. There is no such item: it is called "Fork map geometry
	 * (make zone independent)...", and it has been since it was renamed. The messages fire
	 * at the exact moment the user is blocked - the prop editor, the map painter and the
	 * texture pack all refuse until the zone has its own area - so the one sentence they
	 * get sends them looking through a menu for something that is not there.
	 *
	 * <p>WHAT IT MISSED, AND WHY IT NOW FAILS CLOSED (F224). It skipped a path whose menu it
	 * did not know as "prose", so "Tools &gt; Import OBJ into map region" - a menu that does
	 * not exist, the very defect it is for - passed. Its menu name started at the earliest word
	 * that fit, so "Run File &gt; Deploy" read as a menu called "Run File" and was skipped too,
	 * and so was every path written after a verb, among them "with File &gt; Deploy mod", an
	 * item that does not exist. It never read the "-&gt;" spelling, so "Tools -&gt; Empty
	 * zone", "Game Data -&gt; Trainers" and "Game Data -&gt; Facility opponents" went unread,
	 * all of them wrong, and one suite pinned one of them. Seven messages sent a blocked user
	 * nowhere while this passed. Now it reads only the TEXT of string literals (a {@code >} in
	 * code is a comparison), joined across a {@code +}; it takes every arrow spelling; it
	 * starts the next search at the item, so a second path in the same sentence is read; the
	 * menu is the LAST one or two capitalised words before the arrow; and capitalised words
	 * before an arrow that name no menu are REFUSED unless {@link #PROSE_ARROWS} argues they
	 * are not a path.
	 *
	 * <p>An item may be named by its start, so a message can leave off the long parenthetical,
	 * and the label's ellipsis is ignored - what it refuses is a path that resolves to nothing
	 * at all.
	 */
	static void everyMenuPathAMessageNamesExists(File srcRoot) throws Exception {
		System.out.println("--- every menu path a message names is one the menu bar has (F224)");
		Map<String, List<String>> menus = menus();
		menuPathsAreReadInEveryShape(menus);
		List<String> wrong = new ArrayList<>();
		List<String> noMenu = new ArrayList<>();
		java.util.Set<String[]> proseSeen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		int checked = 0;
		for (File file : DialogSeamTest.javaSources(srcRoot)) {
			if (file.getParentFile() != null && file.getParentFile().getName().equals("tests")) {
				continue;
			}
			String body = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
			for (String text : messageTexts(body)) {
				for (String[] path : menuPaths(text)) {
					String where = file.getName() + ": " + path[0] + " > " + path[1];
					String menu = menuOf(path[0], menus.keySet());
					if (menu == null) {
						String[] prose = proseArrow(file.getName(), path);
						if (prose == null) {
							noMenu.add(where);
						} else {
							proseSeen.add(prose);
						}
						continue;
					}
					checked++;
					if (!itemExists(menus.get(menu), path[1])) {
						wrong.add(where);
					}
				}
			}
		}
		check(wrong.isEmpty(), "every menu path a message names is one the menu bar has"
			+ (wrong.isEmpty() ? "" : " - except " + wrong + ". These fire at the moment"
			+ " a user is blocked, so the one sentence they get has to name something they can find")
			+ " (" + checked + " checked)");
		check(noMenu.isEmpty(), "every capitalised phrase before an arrow in a message names a menu or is argued prose"
			+ (noMenu.isEmpty() ? "" : " - except " + noMenu + ". A phrase that names no menu is a path into"
			+ " one that does not exist, until PROSE_ARROWS argues otherwise"));
		List<String> unused = new ArrayList<>();
		for (String[] p : PROSE_ARROWS) {
			if (!proseSeen.contains(p)) {
				unused.add(p[0] + ": " + p[1] + " > " + p[2]);
			}
		}
		check(unused.isEmpty(), "every argued prose arrow is still in a message (" + proseSeen.size() + " of "
			+ PROSE_ARROWS.length + "), so the list cannot outlive its prose" + (unused.isEmpty() ? "" : " - unused: " + unused));
		check(checked >= 5, "...and there were paths to check (" + checked + "), so a message that"
			+ " stopped naming any would not pass this quietly");
	}

	/** The real menu bar: each menu's name and its items' labels. */
	static Map<String, List<String>> menus() {
		JMenuBar bar = TestWindow.buildMenuBar();
		Map<String, List<String>> menus = new java.util.LinkedHashMap<>();
		for (int i = 0; i < bar.getMenuCount(); i++) {
			JMenu m = bar.getMenu(i);
			List<String> labels = new ArrayList<>();
			for (Component c : m.getMenuComponents()) {
				if (c instanceof JMenuItem) {
					labels.add(((JMenuItem) c).getText());
				}
			}
			menus.put(m.getText(), labels);
		}
		return menus;
	}

	/**
	 * Markdown files in the repository that are RECORDS, not directions to the program's user, each
	 * with its argument (F224). Every other one is read. A record named here that no longer exists
	 * is refused, so the list cannot outlive what it excuses. {file, why}.
	 */
	static final String[][] DOC_RECORDS = {
		{"OUTSTANDING.md", "the work ledger: it quotes the wrong paths it fixed, \"Map > Fork area\" among them, as"
			+ " the record of what was wrong"},
		{"CLAUDE.md", "standing rules for whoever edits the code, not directions to the program's user"},
	};

	/**
	 * Words before an arrow in a doc that are a STEP, not a menu: the docs write any sequence of
	 * steps with arrows, and these are the ones that start at something other than the menu bar.
	 * Each is argued; an entry no doc uses any more is refused. {file, words, why}.
	 */
	static final String[][] DOC_STEPS = {
		{"TESTING.md", "Map Builder", "a step inside the Map Builder tool - open its palette, then filter - not a menu"},
		{"TESTING.md", "ON", "a checkbox ticked, then the painter's Apply button"},
		{"TESTING.md", "OFF", "a checkbox cleared, then what to expect"},
		{"TESTING.md", "Apply", "the painter's Apply button, then the step after it"},
		{"TESTING.md", "Add", "the NPC tool's Add button, then the template its list offers (NPCEditForm's templates)"},
	};

	/**
	 * Every button or item a doc names by its label - bold, ending in "..." - is one the program has
	 * (F137). The quick start guide Help opens sent the user to "Add new zone (EXPERIMENTAL)..." after
	 * the button became "Add zones (lift limit)...": a renamed control is not a menu path, so the
	 * menu-path check above could not see it. A label is found when a string literal in the program,
	 * outside the suites, is that label or ends with it. Scope: the same documents as the menu paths.
	 */
	static void everyLabelADocNamesExists(File repo) throws Exception {
		System.out.println("--- every control a doc names by its label is one the program has (F137)");
		java.util.Set<String> literals = new java.util.HashSet<>();
		Pattern lit = Pattern.compile("\"((?:\\\\.|[^\"\\\\\\n])*)\"");
		for (File f : DialogSeamTest.javaSources(new File(repo, "src/ctrmap"))) {
			if (f.getParentFile() != null && f.getParentFile().getName().equals("tests")) {
				continue;
			}
			Matcher m = lit.matcher(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
			while (m.find()) {
				literals.add(m.group(1).replace("\\u2026", "...").replace("…", "...").trim());
			}
		}
		java.util.Set<String> records = new java.util.HashSet<>();
		for (String[] r : DOC_RECORDS) {
			records.add(r[0]);
		}
		File[] docs = repo.listFiles((d, n) -> n.endsWith(".md") && !records.contains(n));
		Pattern bold = Pattern.compile("\\*\\*([^*\\n]+?(?:\\.\\.\\.|…))\\*\\*");
		List<String> missing = new ArrayList<>();
		int labels = 0;
		for (File doc : docs == null ? new File[0] : docs) {
			List<String> lines = Files.readAllLines(doc.toPath(), StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++) {
				Matcher m = bold.matcher(lines.get(i));
				while (m.find()) {
					String label = m.group(1).replace("…", "...").trim();
					labels++;
					boolean found = false;
					for (String l : literals) {
						found |= l.equals(label) || l.endsWith(label);
					}
					if (!found) {
						missing.add(doc.getName() + ":" + (i + 1) + ": " + label);
					}
				}
			}
		}
		check(literals.contains("Clone zone...") && labels > 0, "control: the program's labels are read (Clone zone... among"
				+ " them) and the docs name " + labels + " by label");
		check(missing.isEmpty(), "every control a doc names by its label is one the program has" + (missing.isEmpty() ? ""
				: " - these are not: " + missing + ". Name the control as the program labels it now"));
	}

	/** A caption that names bytes as unknown or by their bare place: 'Unknown 2:', 'U10', 'ZL2', 'UnknownFlag'. */
	static final Pattern RAW_CAPTION = Pattern.compile("(?i)^\\s*(?:unknown\\b.*|unknownflags?|u[0-9a-f]{1,2}\\s*:?|zl\\d+\\s*:?)\\s*$");

	/** Each UI string that sends the user to pk3DS, and why the program does not do that itself. */
	static final String[][] PK3DS_REDIRECTS = {
		{"NPCEditForm.java", "use pk3DS to place items differently",
			"the program places an item only through a giver NPC in a zone whose script has the give routine; an"
			+ " item ball is not something it places"},
	};

	/**
	 * The labels a form source captions as raw bytes and does not fold or caption again (F221). A
	 * caption inside a NetBeans generated block is what the form starts with; one outside it, or a
	 * label handed to Forms.unknownField, is what the user sees.
	 */
	static List<String> rawCaptionsLeftShowing(String raw, java.util.Set<String> rawSeen) {
		Pattern setText = Pattern.compile("(\\w+)\\.setText\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)");
		int a = raw.indexOf("//GEN-BEGIN:initComponents"), b = raw.indexOf("//GEN-END:initComponents");
		boolean gen = a >= 0 && b > a;
		String generated = SourceSeamTest.stripComments(gen ? raw.substring(a, b) : "");
		String own = SourceSeamTest.stripComments(gen ? raw.substring(0, a) + raw.substring(b) : raw);
		java.util.Set<String> captionedAgain = new java.util.HashSet<>();
		java.util.Set<String> folded = new java.util.HashSet<>();
		for (int part = 0; part < 2; part++) {
			Matcher m = setText.matcher(part == 0 ? generated : own);
			while (m.find()) {
				if (RAW_CAPTION.matcher(m.group(2)).matches()) {
					rawSeen.add(m.group(1));
				} else if (part == 1) {
					captionedAgain.add(m.group(1));
				}
			}
		}
		//the call's text has its literals blanked first: a ';' inside one ended the call there
		Matcher h = Pattern.compile("Forms\\.unknownField\\s*\\(([^;]*)\\)\\s*;")
				.matcher(own.replaceAll("\"(?:[^\"\\\\\\n]|\\\\.)*\"", "\"\""));
		while (h.find()) {
			folded.addAll(java.util.Arrays.asList(h.group(1).split("\\W+")));
		}
		List<String> left = new ArrayList<>();
		for (String v : new java.util.TreeSet<>(rawSeen)) {
			if (!folded.contains(v) && !captionedAgain.contains(v)) {
				left.add(v);
			}
		}
		return left;
	}

	/**
	 * Raw bytes are said to be raw where they are shown, and listed where the open work is (F221). The
	 * Zone tab offered pk3DS's 'UnknownFlag' and 'UnknownFlags', the NPC form 'U10', 'U12' and 'ZL2'/'ZL3'
	 * (the last two the leash the record already names), and the trigger form five 'Unknown n:' boxes,
	 * all among the named fields with nothing to say what they were; the format classes' unnamed fields
	 * were listed nowhere; and the trainer wizard still sent the user to pk3DS for the party and class the
	 * program's own trainer editor sets. Three rules: every label captioned as raw bytes is handed to
	 * Forms.unknownField or captioned again; docs/FORMAT-UNKNOWNS.md lists exactly the unnamed fields the
	 * format classes declare; and a UI string sending the user to pk3DS is one PK3DS_REDIRECTS gives a
	 * reason for.
	 */
	static void theUnknownsAreSaidWhereTheyAreShown(File pkg, File repo) throws Exception {
		System.out.println("--- raw bytes are said to be raw where they are shown, and listed (F221)");
		java.util.Set<String> probeSeen = new java.util.HashSet<>();
		List<String> probeLeft = rawCaptionsLeftShowing("x.setText(\"Unknown 9:\");\ny.setText(\"U12\");\n"
				+ "Forms.unknownField(y, \"Unknown 0x12\", \"z; and more\",\n f);\nw.setText(\"Width\");\n", probeSeen);
		check(probeLeft.equals(java.util.Arrays.asList("x")) && probeSeen.equals(new java.util.HashSet<>(java.util.Arrays.asList("x", "y"))),
				"control: a raw caption left showing is named, a folded one and a plain one are not: " + probeLeft + " " + probeSeen);
		List<File> ui = new ArrayList<>(DialogSeamTest.javaSources(new File(pkg, "humaninterface")));
		ui.add(new File(pkg, "CtrmapMainframe.java"));
		List<String> showing = new ArrayList<>();
		boolean npcU10 = false;
		for (File f : ui) {
			java.util.Set<String> seen = new java.util.HashSet<>();
			for (String v : rawCaptionsLeftShowing(DialogSeamTest.read(f), seen)) {
				showing.add(f.getName() + " " + v);
			}
			npcU10 |= f.getName().equals("NPCEditForm.java") && seen.contains("u10Label");
		}
		check(npcU10, "control: the NPC form's generated 'U10' caption is read as raw bytes");
		check(showing.isEmpty(), "every label captioned as raw bytes is folded by Forms.unknownField or captioned again: " + showing);
		//handed no fields to fold (the 2026-10-14 shared sweep, Forms 206, drop-throw, SURVIVED: every caller hands some)
		String foldsNothing = "(nothing was thrown)";
		try {
			ctrmap.humaninterface.Forms.unknownField(new javax.swing.JLabel("U10"), "U10", "a byte nobody has named");
		} catch (IllegalArgumentException refused) {
			foldsNothing = refused.getMessage();
		}
		check("'U10' must be handed the fields it folds".equals(foldsNothing),
				"a raw-bytes caption handed no fields to fold is refused, naming it: " + foldsNothing);
		//a click on the caption shows its fields, and lays out the panel it sits in when it sits in one (the
		//2026-10-14 shared sweep, Forms 225, negate-if, SURVIVED: no suite clicked one) - every answer read
		final int[] laidOut = {0};
		javax.swing.JPanel holder = new javax.swing.JPanel() {
			@Override
			public void revalidate() {
				laidOut[0]++;
				super.revalidate();
			}
		};
		javax.swing.JLabel inPanel = new javax.swing.JLabel(), alone = new javax.swing.JLabel();
		javax.swing.JTextField shown = new javax.swing.JTextField(), loose = new javax.swing.JTextField();
		holder.add(inPanel);
		ctrmap.humaninterface.Forms.unknownField(inPanel, "U10", "a byte nobody has named", shown);
		ctrmap.humaninterface.Forms.unknownField(alone, "U11", "another", loose);
		laidOut[0] = 0;
		String clicked = clickCaption(inPanel) + clickCaption(alone);
		check(clicked.isEmpty() && shown.isVisible() && loose.isVisible() && laidOut[0] > 0
				&& inPanel.getText().equals("[-] U10") && alone.getText().equals("[-] U11"),
				"a click shows the caption's fields and lays out its panel, and one with no panel throws nothing: laid out "
				+ laidOut[0] + " time(s), \"" + inPanel.getText() + "\", \"" + alone.getText() + "\"" + clicked);

		Pattern decl = Pattern.compile("(?m)^\\s*(?:(?:public|private|protected|static|final|transient)\\s+)*"
				+ "(?:int|long|short|byte|boolean|float|double|byte\\[\\]|int\\[\\]|short\\[\\])\\s+"
				+ "((?:\\w+\\s*(?:=\\s*[^,;]+)?\\s*,\\s*)*\\w+\\s*(?:=\\s*[^,;]+)?)\\s*;");
		Pattern unnamed = Pattern.compile("^(?:unknown\\w*|u[0-9A-Fa-f]{1,2}(?:val)?)$");
		java.util.Set<String> declared = new java.util.TreeSet<>();
		for (File f : DialogSeamTest.javaSources(new File(pkg, "formats"))) {
			Matcher m = decl.matcher(SourceSeamTest.stripComments(DialogSeamTest.read(f)));
			while (m.find()) {
				for (String part : m.group(1).split(",")) {
					String name = part.split("=")[0].trim();
					if (unnamed.matcher(name).matches()) {
						declared.add(f.getName().replace(".java", "") + "." + name);
					}
				}
			}
		}
		check(declared.contains("CameraData.unknownBytes") && declared.contains("ZoneEntities.u10")
				&& !declared.contains("ZoneEntities.uid"),
				"control: the unnamed fields are found by their names, and a named one is not (" + declared.size() + ")");
		File doc = new File(repo, "docs/FORMAT-UNKNOWNS.md");
		java.util.Set<String> listed = new java.util.TreeSet<>();
		if (doc.isFile()) {
			Matcher row = Pattern.compile("(?m)^\\|\\s*`(\\w+\\.\\w+)`").matcher(DialogSeamTest.read(doc));
			while (row.find()) {
				listed.add(row.group(1));
			}
		}
		java.util.Set<String> unlisted = new java.util.TreeSet<>(declared);
		unlisted.removeAll(listed);
		java.util.Set<String> stale = new java.util.TreeSet<>(listed);
		stale.removeAll(declared);
		check(doc.isFile() && !listed.isEmpty(), "control: " + doc + " lists fields (" + listed.size() + ")");
		check(unlisted.isEmpty(), "every unnamed format field has a row in docs/FORMAT-UNKNOWNS.md: " + unlisted);
		check(stale.isEmpty(), "and every row there names a field the formats still declare unnamed: " + stale);

		Pattern literal = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");
		Pattern redirect = Pattern.compile("(?i)\\b(?:in|with|use|using|via|through)\\s+pk3ds\\b");
		check(redirect.matcher("edit party/class in pk3DS").find() && !redirect.matcher("(from pk3DS)").find(),
				"control: a redirect to pk3DS is told from a credit to it");
		List<String> unexplained = new ArrayList<>();
		java.util.Set<String> used = new java.util.HashSet<>();
		for (File f : ui) {
			Matcher m = literal.matcher(SourceSeamTest.stripComments(DialogSeamTest.read(f)));
			while (m.find()) {
				if (!redirect.matcher(m.group()).find()) {
					continue;
				}
				boolean explained = false;
				for (String[] r : PK3DS_REDIRECTS) {
					if (r[0].equals(f.getName()) && m.group().contains(r[1])) {
						explained = true;
						used.add(r[1]);
					}
				}
				if (!explained) {
					unexplained.add(f.getName() + " " + m.group());
				}
			}
		}
		check(unexplained.isEmpty(), "every UI string sending the user to pk3DS is one PK3DS_REDIRECTS says why for: " + unexplained);
		for (String[] r : PK3DS_REDIRECTS) {
			check(used.contains(r[1]), "and each of those reasons still has its string (" + r[0] + ": " + r[1] + ")");
		}
	}

	/**
	 * What the atmosphere UI shows promises only what it edits (F236). The dialog's ambient control was
	 * removed on purpose - the bytes it wrote were the constant 1.0 group of a fog channel, never a
	 * colour - and the World Editor's tooltip and the picker's "Custom settings" went on offering to
	 * hand-tune ambient light. Read: every string literal (comments stripped - the removal is
	 * explained in comments) in the dialog, the picker, and the window's lines that wire the fog
	 * action.
	 */
	static void theAtmosphereUiPromisesOnlyWhatItEdits(File pkg) throws Exception {
		System.out.println("--- the atmosphere UI promises only what it edits (F236)");
		Pattern literal = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");
		Matcher probe = literal.matcher(SourceSeamTest.stripComments("a(\"fog\"); // ambient\nb(\"hand-tune \\\"ambient\\\"\");\n"));
		List<String> probed = new ArrayList<>();
		while (probe.find()) {
			probed.add(probe.group());
		}
		check(probed.size() == 2 && !probed.get(0).contains("ambient") && probed.get(1).contains("ambient"),
				"control: literals are read whole, escaped quotes included, and a comment's words are not: " + probed);
		List<String> promised = new ArrayList<>();
		int lines = 0;
		for (String rel : new String[]{"humaninterface/AreaLightingDialog.java", "humaninterface/GfEnvPicker.java"}) {
			File f = new File(pkg, rel);
			if (!f.isFile()) {
				check(false, "the atmosphere UI's source is at " + f);
				continue;
			}
			String[] code = SourceSeamTest.stripComments(DialogSeamTest.read(f)).split("\n", -1);
			for (int i = 0; i < code.length; i++) {
				lines++;
				Matcher m = literal.matcher(code[i]);
				while (m.find()) {
					if (m.group().toLowerCase(java.util.Locale.ROOT).contains("ambient")) {
						promised.add(rel + ":" + (i + 1) + " " + m.group());
					}
				}
			}
		}
		//THE WINDOW: the action's body, and every call that names it, read WHOLE. This read only the
		//lines holding the action's name, and the fog button's tooltip is an argument on the line above
		//it - so the old tooltip, put back, passed. Parentheses are matched over a copy with literals and
		//comments blanked (same length), and a literal counts only where that copy still has its quote
		File mainFile = new File(pkg, "CtrmapMainframe.java");
		String raw = DialogSeamTest.read(mainFile);
		String bare = SilentCatchTest.blankLiterals(raw);
		List<int[]> spans = new ArrayList<>();
		String decl = "private void fogAndLightingAction(";
		int declAt = bare.indexOf(decl);
		int bodyOpen = declAt < 0 ? -1 : bare.indexOf('{', declAt);
		int bodyClose = bodyOpen < 0 ? -1 : matching(bare, bodyOpen, '{', '}');
		if (bodyClose > bodyOpen) {
			spans.add(new int[]{bodyOpen, bodyClose + 1});
		}
		int calls = 0;
		for (int at = bare.indexOf("fogAndLightingAction"); at >= 0; at = bare.indexOf("fogAndLightingAction", at + 1)) {
			if (declAt >= 0 && at == declAt + decl.indexOf("fogAndLightingAction")) {
				continue;
			}
			int open = -1;
			for (int i = at, depth = 0; i >= 0 && open < 0; i--) {
				char c = bare.charAt(i);
				if (c == ')') {
					depth++;
				} else if (c == '(' && depth-- == 0) {
					open = i;
				}
			}
			int close = open < 0 ? -1 : matching(bare, open, '(', ')');
			if (close > open) {
				spans.add(new int[]{open, close + 1});
				calls++;
			}
		}
		int windowLiterals = 0;
		for (int[] span : spans) {
			lines += raw.substring(span[0], span[1]).split("\n", -1).length;
			Matcher m = literal.matcher(raw);
			m.region(span[0], span[1]);
			while (m.find()) {
				if (bare.charAt(m.start()) != '"') {
					continue; //inside a comment
				}
				windowLiterals++;
				if (m.group().toLowerCase(java.util.Locale.ROOT).contains("ambient")) {
					promised.add("CtrmapMainframe.java:" + (raw.substring(0, m.start()).split("\n", -1).length) + " " + m.group());
				}
			}
		}
		check(bodyClose > bodyOpen && calls >= 1 && windowLiterals >= 3, "control: the window's fog action is read whole -"
				+ " its body, and the call that names it with that call's labels and tooltip (" + calls + " call(s), "
				+ windowLiterals + " literal(s))");
		check(lines > 100, "control: the dialog, the picker and the window's fog code were read (" + lines + " lines)");
		check(promised.isEmpty(), "no text the atmosphere UI shows offers an ambient control it does not have"
				+ (promised.isEmpty() ? "" : " - these do: " + promised));
	}

	/** The index of the bracket closing the one at {@code open}, in a copy with literals blanked; -1 when none does. */
	static int matching(String bare, int open, char opening, char closing) {
		int depth = 0;
		for (int i = open; i < bare.length(); i++) {
			char c = bare.charAt(i);
			if (c == opening) {
				depth++;
			} else if (c == closing && --depth == 0) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Every menu path the docs the owner reads name is one the menu bar has (F224).
	 *
	 * <p>WHAT IT CAUGHT. TESTING.md - the owner's own checklist - sent them to "Tools &gt; Edit
	 * shop inventories", "Tools &gt; Remove added zones", "Tools &gt; Rename zone", "Tools &gt;
	 * Edit area fog &amp; lighting" and "Tools &gt; Tile painter": there is no Tools menu, and the
	 * painter is the Map Builder now. The message check reads only the program's string literals,
	 * so a page the owner follows step by step was never read. The scope is every Markdown file at
	 * the repository's root, less the records {@link #DOC_RECORDS} argues for; quotes, bold and
	 * code marks are dropped first, since docs wrap a label in them.
	 */
	static void everyMenuPathADocNamesExists(File repo) throws Exception {
		System.out.println("--- every menu path a doc the owner reads names is one the menu bar has (F224)");
		Map<String, List<String>> menus = menus();
		File[] docs = repo.listFiles((d, n) -> n.endsWith(".md"));
		if (docs == null) {
			check(false, "the repository root could not be listed: " + repo.getAbsolutePath());
			return;
		}
		java.util.Set<String> records = new java.util.HashSet<>();
		List<String> stale = new ArrayList<>();
		for (String[] r : DOC_RECORDS) {
			records.add(r[0]);
			if (!new File(repo, r[0]).isFile()) {
				stale.add(r[0]);
			}
		}
		List<String> wrong = new ArrayList<>();
		List<String> noMenu = new ArrayList<>();
		List<String> read = new ArrayList<>();
		java.util.Set<String> stepsUsed = new java.util.HashSet<>();
		int checked = 0;
		for (File doc : docs) {
			if (records.contains(doc.getName())) {
				continue;
			}
			read.add(doc.getName());
			List<String> lines = Files.readAllLines(doc.toPath(), StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++) {
				String text = lines.get(i).replace("\"", "").replace("*", "").replace("`", "");
				for (String[] path : menuPaths(text)) {
					String menu = menuOf(path[0], menus.keySet());
					String where = doc.getName() + ":" + (i + 1) + ": " + path[0] + " > " + path[1];
					if (menu == null) {
						String step = null;
						for (String[] s : DOC_STEPS) {
							if (s[0].equals(doc.getName()) && s[1].equals(path[0])) {
								step = s[0] + "|" + s[1];
							}
						}
						if (step == null) {
							noMenu.add(where);
						} else {
							stepsUsed.add(step);
						}
					} else {
						checked++;
						if (!itemExists(menus.get(menu), path[1])) {
							wrong.add(where);
						}
					}
				}
			}
		}
		java.util.Collections.sort(read);
		check(read.contains("TESTING.md") && read.contains("README.md") && read.contains("QUICKSTART.md") && stale.isEmpty(),
				"control: the owner's docs are read - " + read + (stale.isEmpty() ? "" : " - and a record that no longer"
				+ " exists is still excused: " + stale));
		check(wrong.isEmpty(), "every menu path the docs name is one the menu bar has" + (wrong.isEmpty() ? ""
				: " - except " + wrong) + " (" + checked + " checked)");
		check(noMenu.isEmpty(), "every capitalised phrase before an arrow in the docs names a menu or an argued step"
				+ (noMenu.isEmpty() ? "" : " - except " + noMenu + ". The owner follows these step by step"));
		List<String> unusedSteps = new ArrayList<>();
		for (String[] s : DOC_STEPS) {
			if (!stepsUsed.contains(s[0] + "|" + s[1])) {
				unusedSteps.add(s[0] + ": " + s[1]);
			}
		}
		check(unusedSteps.isEmpty(), "every argued step is still in its doc (" + stepsUsed.size() + " of " + DOC_STEPS.length
				+ "), so the list cannot outlive it" + (unusedSteps.isEmpty() ? "" : " - unused: " + unusedSteps));
	}

	/**
	 * Capitalised words before an arrow in a message that are NOT a path into this program's
	 * menus, each with its argument (F224). Anything else that names no menu is refused, and so
	 * is an entry here that no message uses any more. {file, the words before the arrow, the
	 * start of what follows it, why it is not a menu path}.
	 */
	static final String[][] PROSE_ARROWS = {
		{"CameraEditForm.java", "R", "L",
			"a camera motion's direction, right to left, in the combo label \"Horizontal (R > L : 1 > 2)\""},
		{"CameraEditForm.java", "Down", "Up",
			"a camera motion's direction, down to up, in the combo label \"Vertical (Down > Up : 1 > 2)\""},
		{"AreaLightingDialog.java", "Day", "all four",
			"a button that copies the day's lighting to all four times of day: \"Day -> all four\""},
		{"ZoneLoadingPanel.java", "Open Mods Location", "exefs",
			"the emulator's own right-click menu (Azahar), not this program's: \"Open Mods Location -> exefs\\code.ips\""},
	};

	/**
	 * A menu path's shape in a message: capitalised words, an arrow in any spelling (with or
	 * without the spaces, plain or as HTML, or the arrow sign), and what it names. The item ends
	 * at a full stop, a bracket, a colon, a quote, a tag or a line end: several messages name a
	 * path and then explain it - "File > Save: every editor stores what it holds" - and the
	 * explanation is not part of the label.
	 */
	static final java.util.regex.Pattern MENU_PATH = java.util.regex.Pattern.compile(
		"([A-Z]\\w*(?: [A-Z]\\w*)*) ?(?:->|-&gt;|&gt;|>|→) ?([A-Za-z][^\\n.):\"<]{0,60})");

	/**
	 * {the words before the arrow, what it names} for every menu-path shape in a message's text.
	 * The next search starts where the item does, so "File > Pack Workspace, then File > Deploy"
	 * is two paths, not one long item.
	 */
	static List<String[]> menuPaths(String text) {
		List<String[]> out = new ArrayList<>();
		java.util.regex.Matcher m = MENU_PATH.matcher(text);
		int from = 0;
		while (m.find(from)) {
			out.add(new String[]{m.group(1), m.group(2).trim()});
			from = m.start(2);
		}
		return out;
	}

	/** The menu the LAST one or two words before an arrow name ("Run File" is File), or null. */
	static String menuOf(String words, java.util.Set<String> menus) {
		String[] w = words.split(" ");
		String two = w.length >= 2 ? w[w.length - 2] + " " + w[w.length - 1] : null;
		if (two != null && menus.contains(two)) {
			return two;
		}
		return menus.contains(w[w.length - 1]) ? w[w.length - 1] : null;
	}

	/**
	 * Whether a menu holds the item a message names. Either way round, at a word's end: a message
	 * may shorten a long label ("Fork map geometry" for "Fork map geometry (make zone
	 * independent)...") or carry on past a short one ("Open Zone is only for single loose ZO
	 * files"), and may name the label without its own parenthetical ("Deploy to emulator (it
	 * packs first)" for "Deploy to emulator (mod)..."). Case and the ellipsis are ignored.
	 */
	static boolean itemExists(List<String> labels, String item) {
		String b = item.toLowerCase();
		//and the message's OWN parenthetical, which the item stops inside: "File > Deploy (the game
		//files are already packed" names Deploy, as "Game Data > Trainers (pick one" names Trainers
		String named = b.replaceAll("\\s*\\(.*$", "");
		for (String label : labels) {
			String a = label == null ? "" : label.replaceAll("(\\.\\.\\.|…)\\s*$", "").trim().toLowerCase();
			for (String form : new String[]{a, a.replaceAll("\\s*\\([^()]*\\)$", "")}) {
				if (!form.isEmpty() && (startsAtWord(form, b) || startsAtWord(b, form)
						|| (!named.isEmpty() && startsAtWord(form, named)))) {
					return true;
				}
			}
		}
		return false;
	}

	/** Whether {@code s} starts with {@code start} and a word does not carry on past it. */
	static boolean startsAtWord(String s, String start) {
		return s.startsWith(start) && (s.length() == start.length() || !Character.isLetterOrDigit(s.charAt(start.length())));
	}

	/** The {@link #PROSE_ARROWS} entry that argues this phrase is not a path, or null. */
	static String[] proseArrow(String file, String[] path) {
		for (String[] p : PROSE_ARROWS) {
			if (p[0].equals(file) && p[1].equals(path[0]) && path[1].startsWith(p[2])) {
				return p;
			}
		}
		return null;
	}

	/**
	 * The texts a source's string literals spell, adjacent literals joined across a {@code +} so
	 * a message split over lines reads as one sentence. Comments, char literals and code are
	 * never read: a {@code >} there is a comparison or a generic, not a menu path.
	 */
	static List<String> messageTexts(String src) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = null;
		int lastEnd = -1;
		int n = src.length();
		int i = 0;
		while (i < n) {
			char c = src.charAt(i);
			char next = i + 1 < n ? src.charAt(i + 1) : 0;
			if (c == '/' && next == '/') {
				int j = src.indexOf('\n', i);
				i = j < 0 ? n : j;
				continue;
			}
			if (c == '/' && next == '*') {
				int j = src.indexOf("*/", i + 2);
				i = j < 0 ? n : j + 2;
				continue;
			}
			if (c == '\'') {
				int j = i + 1;
				if (j < n && src.charAt(j) == '\\') {
					j++;
				}
				j++;
				while (j < n && src.charAt(j) != '\'') {
					j++;
				}
				i = j + 1;
				continue;
			}
			if (c != '"') {
				i++;
				continue;
			}
			StringBuilder lit = new StringBuilder();
			int j = i + 1;
			while (j < n && src.charAt(j) != '"') {
				char d = src.charAt(j);
				if (d == '\\' && j + 1 < n) {
					char e = src.charAt(j + 1);
					if (e == 'u' && j + 6 <= n) {
						lit.append((char) Integer.parseInt(src.substring(j + 2, j + 6), 16));
						j += 6;
						continue;
					}
					lit.append(e == 'n' ? '\n' : e == 't' ? '\t' : e);
					j += 2;
					continue;
				}
				lit.append(d);
				j++;
			}
			if (cur != null && src.substring(lastEnd, i).trim().equals("+")) {
				cur.append(lit);
			} else {
				if (cur != null) {
					out.add(cur.toString());
				}
				cur = lit;
			}
			lastEnd = j + 1;
			i = j + 1;
		}
		if (cur != null) {
			out.add(cur.toString());
		}
		return out;
	}

	/**
	 * KNOWN ANSWERS for each shape the check was once blind to (F224), through the same methods
	 * the scan uses, against the real menu bar - so a scan that reads nothing, or reads a shape
	 * wrongly, cannot report a clean tree.
	 */
	static void menuPathsAreReadInEveryShape(Map<String, List<String>> menus) {
		List<String> texts = messageTexts("a(\"Run File > Deploy to emulator.\" + \" via Tools -> Empty zone.\");"
			+ " char q = '\"'; // \"Help > Nothing\" is a comment\n b(\"Zone&gt;Fork\" + x + \"Map \\u2192 Resize\")");
		check(texts.size() == 3 && texts.get(0).equals("Run File > Deploy to emulator. via Tools -> Empty zone.")
				&& texts.get(1).equals("Zone&gt;Fork") && texts.get(2).equals("Map → Resize"),
			"control: literals are joined across a +, and a comment, a char literal and the code between are not read: " + texts);
		List<String[]> p = menuPaths(texts.get(0));
		check(p.size() == 2 && "File".equals(menuOf(p.get(0)[0], menus.keySet()))
				&& itemExists(menus.get("File"), p.get(0)[1]),
			"control: a path after a verb is read - \"Run File > Deploy to emulator\" is File's item");
		check(p.size() == 2 && p.get(1)[0].equals("Tools") && menuOf("Tools", menus.keySet()) == null
				&& proseArrow("CtrmapMainframe.java", p.get(1)) == null,
			"control: the arrow spelling is read, and \"Tools\" names no menu and is no argued prose, so it is refused");
		p = menuPaths("Game Data -> Trainers (pick one). Then Game Data > Edit trainer, or File > Pack Workspace, then File > Deploy mod.");
		check(p.size() == 4 && "Game Data".equals(menuOf(p.get(0)[0], menus.keySet()))
				&& !itemExists(menus.get("Game Data"), p.get(0)[1])
				&& "Game Data".equals(menuOf(p.get(1)[0], menus.keySet()))
				&& itemExists(menus.get("Game Data"), p.get(1)[1]),
			"control: a two-word menu is the LAST two words, and \"Trainers\" is no item of it while \"Edit trainer\" is");
		check(p.size() == 4 && itemExists(menus.get("File"), p.get(2)[1]) && p.get(3)[1].equals("Deploy mod")
				&& !itemExists(menus.get("File"), p.get(3)[1]),
			"control: a second path in the same sentence is read, and \"Deploy mod\" is no item of File");
		check(menuPaths("Zone&gt;empty zone").size() == 1 && itemExists(menus.get("Zone"), "empty zone")
				&& !itemExists(menus.get("File"), "Saved copy"),
			"control: the HTML arrow and a lower-case item are read, and an item matches only at a word's end");
	}

	/** Whether {@code what} is somewhere inside {@code where}. */
	static boolean holds(Component where, Component what) {
		if (where == what) {
			return true;
		}
		if (!(where instanceof java.awt.Container)) {
			return false;
		}
		for (Component c : ((java.awt.Container) where).getComponents()) {
			if (holds(c, what)) {
				return true;
			}
		}
		return false;
	}
	// -------------------------------------------------------------- tool row
	static void toolRow() {
		final List<String> commands = new ArrayList<>();
		final int[] toggles = {0, 0};
		final int[] number = {0};
		ctrmap.humaninterface.UndoHistory history = UndoHistoryTest.overOneNumber(number);
		WorldEditorToolbar row = new WorldEditorToolbar(e -> commands.add(e.getActionCommand()),
				() -> toggles[0]++, () -> toggles[1]++, history);

		String got = renderRow(row);
		check(got.equals(EXPECTED_TOOL_ROW), "the tool row reads: " + EXPECTED_TOOL_ROW
				+ (got.equals(EXPECTED_TOOL_ROW) ? "" : "\n        but is: " + got));

		List<JRadioButton> tools = new ArrayList<>();
		JButton undo = null, redo = null;
		JToggleButton view3D = null, fogOff = null;
		for (Component c : row.getComponents()) {
			if (c instanceof JRadioButton) {
				tools.add((JRadioButton) c);
			} else if (c instanceof JToggleButton) {
				//in row order: the view switch, then the fog switch beside it
				if (view3D == null) {
					view3D = (JToggleButton) c;
				} else {
					fogOff = (JToggleButton) c;
				}
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
		check(undo != null && "Nothing to undo (Ctrl+Z)".equals(undo.getToolTipText())
				&& redo != null && "Nothing to redo (Ctrl+Y)".equals(redo.getToolTipText()),
				"Undo and Redo name their shortcuts, and say there is nothing to do: " + (undo == null ? null : undo.getToolTipText()));
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

		//the fog switch is the same shape, and is VIEWING ONLY - a row that
		//decided for itself could come to disagree with what is on screen
		check(fogOff != null && !fogOff.isSelected(),
			"the fog switch starts off - an area is drawn with the fog it really has");
		fogOff.doClick();
		check(toggles[1] == 1 && toggles[0] == 1,
			"pressing it asks the window once (" + toggles[1] + ") and does not touch the view switch");
		row.setFogSuppressed(true);
		check(fogOff.isSelected(), "and the window's answer is what it shows");
		row.setFogSuppressed(false);
		check(!fogOff.isSelected(), "either way");
		check(fogOff.getToolTipText() != null && fogOff.getToolTipText().contains("Viewing only"),
			"and it says so where the user can see it, beside a menu that DOES write fog: "
			+ fogOff.getToolTipText());

		//the undo buttons follow the zone's history they were handed, through its listener (F209)
		check(!undo.isEnabled() && !redo.isEnabled(), "with nothing done, neither undo button is lit");
		number[0] = 7;
		history.settle();
		check(undo.isEnabled() && !redo.isEnabled() && undo.getToolTipText().contains("the number"),
				"an edit lights Undo, which says what it would take back: " + undo.getToolTipText());
		undo.doClick();
		check(number[0] == 0 && !undo.isEnabled() && redo.isEnabled(),
				"pressing it takes the edit back (" + number[0] + ") and lights Redo instead");
		redo.doClick();
		check(number[0] == 7 && undo.isEnabled() && !redo.isEnabled(), "and Redo puts it again (" + number[0] + ")");
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

	// ----------------------------------------------------------- action rows
	/** A row of plain action buttons: what it reads, and that each button does one thing. */
	static void actionRow(String what, JToolBar row, String expected) {
		String got = renderRow(row);
		check(got.equals(expected), "the " + what + " row reads: " + expected
				+ (got.equals(expected) ? "" : "\n        but is: " + got));
		List<String> wrong = new ArrayList<>();
		for (Component c : row.getComponents()) {
			if (c instanceof AbstractButton) {
				AbstractButton b = (AbstractButton) c;
				if (b.getActionListeners().length != 1 || b.isFocusable() || b.getToolTipText() == null || b.getToolTipText().isEmpty()) {
					wrong.add(b.getText());
				}
			}
		}
		check(wrong.isEmpty(), "each " + what + " button has one action, a tooltip, and leaves the focus where it was"
				+ (wrong.isEmpty() ? "" : " - not: " + wrong));
		check(!row.isFloatable(), "the " + what + " row is not a floating palette");
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

	// -------------------------------------------------------- camera framing
	/**
	 * Both ways of pointing the 3D camera at a newly opened map zero the yaw.
	 *
	 * <p>THE DEFECT THIS HOLDS SHUT. The single-region framing wrote four
	 * fields; the matrix framing wrote its own four AND the yaw. So orbiting the
	 * 3D view - a LEFT-button drag in {@code CM3DInputManager.mouseDragged},
	 * where the right button pans instead - and then opening a loose GR map file
	 * brought the new map up at the angle the last one was left at, while the
	 * same gesture ending in a matrix load brought it up square. Three of the
	 * single-region lines are the matrix body's with the cell counts written out
	 * as constants; the fifth was simply never copied. The fourth, translateX,
	 * is a difference of its own and is pinned below rather than fixed.
	 *
	 * <p>WHY THIS IS READ FROM THE SOURCE AND NOT RUN. The framing is five
	 * assignments on {@code m3DDebugPanel}, an {@code H3DRenderingPanel} whose
	 * constructor calls {@code GLProfile.get(GL2)} and starts an FPSAnimator:
	 * there is no headless instance to write to, and the two methods return
	 * nothing to read back. {@link #shape} already reads this same file the same
	 * way. Comments are stripped first, so a comment SAYING the yaw is zeroed
	 * cannot satisfy this.
	 */
	//RETARGETED to PanelScene3D: the camera arithmetic moved out of an anonymous body
	//in the window so the Zone Loader preview could USE it instead of growing a second
	//copy. The claim is unchanged and so is the reading - source, comments stripped.
	static void cameraFraming(File mainframe) throws Exception {
		File scene = new File(mainframe.getParentFile(), "humaninterface/PanelScene3D.java");
		if (scene.isFile()) {
			mainframe = scene;
		}
		if (!mainframe.isFile()) {
			check(false, "the window's source is at " + mainframe + " (pass the src root as args[0])");
			return;
		}
		String text = SourceSeamTest.stripComments(new String(Files.readAllBytes(mainframe.toPath()), StandardCharsets.UTF_8));
		int single = text.indexOf("public void frameSingleRegion() {");
		int matrix = text.indexOf("public void frameMatrix(int cellsAcross, int cellsDown) {");
		int end = matrix < 0 ? -1 : text.indexOf("public void redraw() {", matrix);
		if (single < 0 || matrix < single || end < matrix) {
			check(false, "the camera framings live together, single-region then matrix"
					+ " (found at " + single + ", " + matrix + ", " + end + ")");
			return;
		}
		String singleBody = text.substring(single, matrix);
		String matrixBody = text.substring(matrix, end);
		check(singleBody.contains("rotateY = 0f;"),
				"framing a single region zeroes the yaw, so a loose GR map does not open at the angle the last one was left at");
		check(matrixBody.contains("rotateY = 0f;"),
				"framing a matrix zeroes the yaw");
		check(singleBody.contains("rotateX = 45f;") && matrixBody.contains("rotateX = 45f;"),
				"and both still pitch the camera down 45 degrees");
		//EACH FRAMING CENTRES WHAT IT FRAMES (F159). The audit read translateX 0 against the
		//formula's -360 for one cell as a loose GR sitting half a region off centre, and asked
		//for frameSingleRegion to call frameMatrix(1, 1). It does not sit off centre: a loose
		//region's model is drawn where it stands, its world position never set (0, its middle),
		//while a matrix's cells are placed at cell * 720 + 360. The framings differ because the
		//placements do, and the check holds them TOGETHER: change either placement and this fails.
		File panel = new File(mainframe.getParentFile(), "TileMapPanel.java");
		String placing = panel.isFile() ? SourceSeamTest.stripComments(
				new String(Files.readAllBytes(panel.toPath()), StandardCharsets.UTF_8)) : "";
		//the loose load hands its grids to the setter that retires the ones they replace (census M.95, M.96)
		int loose = placing.indexOf("setRegionModels(new BCHFile[][]{{new BCHFile(file.getFile(1))}},");
		int looseEnd = loose < 0 ? -1 : placing.indexOf("scene.frameSingleRegion();", loose);
		check(singleBody.contains("translateX = 0f;") && looseEnd > loose
				&& !placing.substring(loose, looseEnd).contains("worldLocX"),
				"a single region frames at translateX 0, its own middle: the loose load never moves its model");
		check(matrixBody.contains("translateX = -cellsAcross * 360f;")
				&& placing.contains("model.worldLocX = cellX * 720f + 360f;"),
				"a matrix frames at -cellsAcross * 360, the middle of cells placed at cell * 720 + 360");

		//THE PREVIEW DOES NOT MOVE THE CAMERA, and that is a decision with a bill attached.
		//Framing a zone's own cells was tried twice and broke the preview both times: first
		//by clamping a footprint into a matrix it did not fit, aiming at a corner of a map
		//the zone is not on; then by pulling back the footprint's own depth, which put the
		//camera 720 units from a map the working view frames from 7200. Both shipped with a
		//green suite, because a suite here can assert the ARITHMETIC and cannot see the
		//PICTURE. The camera is left where showRegions puts it, and WHICH PART a zone
		//occupies is said in the caption - the half that was legible in every screenshot of
		//the broken versions.
		File preview = new File(mainframe.getParentFile(), "ZonePreviewPane.java");
		if (!preview.isFile()) {
			check(false, "no ZonePreviewPane source at " + preview);
			return;
		}
		String pv = SourceSeamTest.stripComments(new String(Files.readAllBytes(preview.toPath()),
				StandardCharsets.UTF_8));
		check(!pv.contains("scene.frame"),
				"the preview does not aim the camera itself - twice it did, and twice the map went black");
		check(pv.contains("ZoneFootprint.of("),
				"...but it still works out which cells the zone occupies");
		check(pv.contains("fitsIn(") && pv.contains("framed = null;"),
				"...and DISCARDS a footprint that cannot be about this map rather than"
				+ " squeezing it in - zone 0 measures twelve cells on a one-cell map");
		check(pv.contains("describeCells()") && pv.contains("whole map"),
				"...and says which, or says it is showing the whole map, so the caption"
				+ " tells two zones on one matrix apart when the picture cannot");
	}

	/**
	 * Choosing a zone, cloning one and adding zones are one job and sit in one row.
	 *
	 * <p>REPORTED, and it was this program's own doing rather than anything inherited: the
	 * zone browser was added as a column on the right of the Zone Loader, taking the load
	 * button with it, while "Clone zone..." and "Add zones (lift limit)..." stayed in the
	 * form's top-left corner where the NetBeans layout had always had them. The owner asked
	 * for a preview. Nobody asked for the load button to move, and once it had, half the task
	 * was in one corner of the tab and half in the other.
	 *
	 * <p>Checked as a MOVE, not a copy: a second "Clone zone..." button somewhere else would
	 * satisfy "the browser has one" while making the original complaint worse.
	 */
	static void theZoneActionsShareOneRow(File src) throws Exception {
		System.out.println("--- clone, add and load sit in one row, and the buttons MOVED there");
		ctrmap.humaninterface.ZoneBrowserPane browser =
				new ctrmap.humaninterface.ZoneBrowserPane(new ctrmap.LoadedZone(),
						new ctrmap.humaninterface.tools.ToolSelection());
		javax.swing.JPanel elsewhere = new javax.swing.JPanel();
		javax.swing.JButton clone = new javax.swing.JButton("Clone zone...");
		elsewhere.add(clone);
		check(holds(elsewhere, clone), "the button starts somewhere else, or this proves nothing");

		browser.addAction(clone);
		check(holds(browser, clone), "after addAction the button is in the browser column");
		check(!holds(elsewhere, clone),
				"...and is GONE from where it was - moved, not copied, so there is still"
				+ " exactly one of it in the window");

		java.util.List<String> row = buttonTexts(browser);
		check(row.contains("Clone zone...") && row.contains(ctrmap.humaninterface.ZoneBrowserPane.LOAD),
				"the row offers both: " + row);
		check(row.indexOf(ctrmap.humaninterface.ZoneBrowserPane.LOAD) == row.size() - 1,
				"and the one that COMMITS is last, after the ones that make a zone: " + row);

		//...and the tab really hands them over, which the check above cannot see.
		File frame = new File(src, "ctrmap/CtrmapMainframe.java");
		if (!frame.isFile()) {
			check(false, "no CtrmapMainframe source at " + frame);
			return;
		}
		String text = SourceSeamTest.stripComments(new String(Files.readAllBytes(frame.toPath()),
				StandardCharsets.UTF_8));
		check(text.contains("zoneCreationButtons()") && text.contains("browser.addAction("),
				"buildZoneTab hands the form's zone-making buttons to the browser's row");
	}

	/**
	 * The zone-making buttons are still in the browser AFTER THE FORM IS LAID OUT.
	 *
	 * <p>THIS IS THE CHECK THAT WAS MISSING, and its absence cost the owner four reports of
	 * the same thing. {@code ZoneLoadingPanel} is a NetBeans form using {@code GroupLayout},
	 * and its generated layout names both buttons in the horizontal group and again in the
	 * vertical one. <b>GroupLayout puts every component named in its groups back into its own
	 * container every time that container is laid out.</b> So moving them works exactly once
	 * and the first layout pass silently undoes it.
	 *
	 * <p>Every check said it worked. The tab really does move them. A diagnostic written from
	 * inside the tab builder saw them moved - it runs before the first layout. The headless
	 * suite built the panel with a stand-in and never laid anything out. Only the owner's
	 * window laid out, and only the owner could see the result.
	 *
	 * <p>So this builds the REAL panel, builds the REAL tab, and then LAYS THE FORM OUT before
	 * asking. Without that last step it passes against the broken code, which is the whole
	 * lesson: a UI test that never lays out is testing a tree, not a window.
	 */
	static void theZoneButtonsSurviveALayoutPass() throws Exception {
		System.out.println("--- the zone-making buttons survive the form being laid out");
		ctrmap.LoadedZone owner = new ctrmap.LoadedZone();
		ctrmap.humaninterface.tools.ToolSelection tools =
				new ctrmap.humaninterface.tools.ToolSelection();
		ctrmap.humaninterface.ZoneLoadingPanel panel;
		try {
			panel = new ctrmap.humaninterface.ZoneLoadingPanel(owner, tools,
					new ctrmap.humaninterface.OpenEditors(java.util.Arrays.asList(
							(ctrmap.humaninterface.OpenEditors.Editable) askFirst -> true)),
					new ctrmap.humaninterface.ZoneEditors(java.util.Arrays.asList(
							new ctrmap.humaninterface.ZoneEditors.ZoneView() {
								@Override
								public void show(ctrmap.formats.zone.Zone zone) {
								}

								@Override
								public void clear() {
								}

								@Override
								public boolean commit() {
									return true;
								}

								@Override
								public void showEntities(ctrmap.formats.zone.Zone zone) {
								}
							})),
					new ctrmap.humaninterface.Navigator() {
						@Override
						public void follow(ctrmap.formats.MapObject o) {
						}

						@Override
						public void resync() {
						}
					}, () -> { }, () -> { }, UndoHistoryTest.overOneNumber(new int[]{0}), new ctrmap.humaninterface.SaveHistory(() -> java.util.Collections.<Object>emptyList()));
		} catch (Throwable cannotBuild) {
			check(false, "a real ZoneLoadingPanel can be built headlessly: " + cannotBuild);
			return;
		}
		java.awt.Container tab = TestWindow.buildZoneTab(panel, owner, tools);
		javax.swing.JButton[] made = panel.zoneCreationButtons();
		check(made.length == 2 && made[0] != null && made[1] != null,
				"the panel hands over two zone-making buttons");

		//THE LAYOUT PASS. Everything above passed against the broken version too.
		panel.setSize(900, 700);
		panel.doLayout();
		panel.validate();

		for (javax.swing.JButton b : made) {
			check(!holds(panel, b),
					"'" + b.getText() + "' is NOT pulled back into the form by GroupLayout");
			check(holds(tab, b), "...and is still in the tab, where the user can reach it");
		}
		//...and exactly one visible control per action, so the hidden originals cannot be a
		//second way to fire the same thing.
		int visibleClone = 0;
		for (Component c : visibleButtons(tab)) {
			if ("Clone zone...".equals(((javax.swing.JButton) c).getText())) {
				visibleClone++;
			}
		}
		check(visibleClone == 1,
				"exactly one VISIBLE 'Clone zone...' in the tab, not two (" + visibleClone + ")");
	}

	/** Every visible JButton under a container. */
	static java.util.List<Component> visibleButtons(java.awt.Container root) {
		java.util.List<Component> out = new java.util.ArrayList<>();
		for (Component c : root.getComponents()) {
			if (!c.isVisible()) {
				continue;
			}
			if (c instanceof javax.swing.JButton) {
				out.add(c);
			} else if (c instanceof java.awt.Container) {
				out.addAll(visibleButtons((java.awt.Container) c));
			}
		}
		return out;
	}

	/** Every JButton's text under a container, in the order the layout holds them. */
	static java.util.List<String> buttonTexts(java.awt.Container root) {
		java.util.List<String> out = new java.util.ArrayList<>();
		for (Component c : root.getComponents()) {
			if (c instanceof javax.swing.JButton) {
				out.add(((javax.swing.JButton) c).getText());
			} else if (c instanceof java.awt.Container) {
				out.addAll(buttonTexts((java.awt.Container) c));
			}
		}
		return out;
	}

	/**
	 * The loose-map clear names every entity form the zone close clears.
	 *
	 * <p>{@code TileMapPanel.loadTileMap} - File &gt; Open GR Mapfile - RELEASES
	 * the open zone and then tells this window's
	 * {@link ctrmap.humaninterface.MapEditors} to clear the entity editors. It
	 * named the NPC form and stopped, so the warp and trigger forms went on
	 * showing, selecting and saving the entities of a zone the editor no longer
	 * has open: their {@code saveEntry()} writes into a {@code ZoneEntities} the
	 * Zone tab's Save never reaches, because {@code ZoneLoadingPanel.store}
	 * answers true the moment {@code loadedZone.open()} is null. A warp typed
	 * there is lost with no warning at all.
	 *
	 * <p>CHECKED OVER SOURCE because the only implementation of that seam is an
	 * anonymous class inside the window's constructor path, and no suite can
	 * build it - nothing anywhere calls {@code setEditors} except the window, so
	 * every panel a suite makes has a null editor set and skips the call.
	 *
	 * <p>The expected list is DERIVED from the window's own source rather than
	 * written out here: every form the window hands a {@code ZoneEntities} to is
	 * an entity form, so a fourth one added to the zone-close list and forgotten
	 * in the loose-map clear fails this section by name.
	 *
	 * <p>THE ARGUMENT IS CHECKED TOO, not just the name: a form named here and
	 * handed a live {@code ZoneEntities} is not cleared, and naming it would
	 * otherwise have satisfied this section while the defect stood.
	 */
	static void theLooseMapClearNamesEveryEntityForm(File mainframe) throws Exception {
		if (!mainframe.isFile()) {
			check(false, "the window's source is at " + mainframe + " (pass the src root as args[0])");
			return;
		}
		String text = SourceSeamTest.stripComments(new String(Files.readAllBytes(mainframe.toPath()), StandardCharsets.UTF_8));
		java.util.Set<String> forms = new java.util.TreeSet<>();
		java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("(m\\w+)\\.loadFromEntities\\(").matcher(text);
		while (m.find()) {
			forms.add(m.group(1));
		}
		check(forms.size() >= 3, "the window hands entities to " + forms.size() + " form(s): " + forms);
		String body = bodyOf(text, "public void clearEntities()");
		check(body != null, "and the loose-map clear it wires into MapEditors can be read");
		if (body == null) {
			return;
		}
		List<String> missing = new ArrayList<>();
		for (String form : forms) {
			if (!body.contains(form + ".loadFromEntities(null")) {
				missing.add(form);
			}
		}
		check(missing.isEmpty(), "opening a loose GR file clears every entity form the zone close clears"
				+ " - these are left showing the released zone's entities: " + missing);
	}

	/**
	 * The text between the braces of the named method, or null if it is not
	 * there. Comments are stripped before this runs; string literals are not, so
	 * a brace inside one would miscount - the body read here has no string in it.
	 */
	static String bodyOf(String text, String signature) {
		int at = text.indexOf(signature);
		if (at == -1) {
			return null;
		}
		int open = text.indexOf('{', at);
		if (open == -1) {
			return null;
		}
		int depth = 0;
		for (int i = open; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '{') {
				depth++;
			} else if (c == '}' && --depth == 0) {
				return text.substring(open + 1, i);
			}
		}
		return null;
	}

	/**
	 * A shared helper does not depend on the main window.
	 *
	 * <p>This used to ask it of {@code ctrmap.Utils}, one class holding five
	 * unrelated groups - byte helpers the format layer reads with, dialog
	 * wrappers, Swing form bits, the 3D picking maths. The split gave each
	 * group its own file, so the question is asked of each of them.
	 */
	static void windowFree(File helper) throws Exception {
		if (!helper.isFile()) {
			check(false, "helper is at " + helper);
			return;
		}
		String text = SourceSeamTest.stripComments(new String(Files.readAllBytes(helper.toPath()), StandardCharsets.UTF_8));
		check(!text.contains("CtrmapMainframe"), helper.getName() + " does not depend on the main window");
	}

}
