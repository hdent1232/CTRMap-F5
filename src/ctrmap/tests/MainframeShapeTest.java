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
import java.util.Map;
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

	/** The tile inspector, so what the cursor told it can be read back. */
	static final RecordingInspector INSPECTOR = new RecordingInspector();

	/**
	 * The menus as the user sees them: one line per menu, items in order,
	 * "|" between items, "---" where a separator sits. Edit this WITH the
	 * menu - that is the point.
	 */
	private static final String[] EXPECTED_MENUS = {
		"File: Open GR Mapfile | Open MapMatrix | Open Zone | Save | Pack Workspace | Deploy to emulator (mod)...",
		"Map: Map Builder (this zone) | Blank map canvas (this zone)... | Resize map (this zone)... | Edit area fog & lighting... | Fork map geometry (make zone independent)... | --- | Import map model (.bch)... | Export map region to OBJ (Blender)... | Import OBJ into map region (Blender)... | OBJ to collisions | Tileset Editor",
		"Zone: Connect zones through a warp... | Rename zone (in-game name)... | Empty zone (clear contents)... | Find reusable base zones... | Remove added zones (restore stock 536)... | Custom battle facility here (clone a retail facility)",
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

	private static final String EXPECTED_MAP_ROW = "label: Map:   button:Blank canvas button:Resize map button:Fog & lighting button:Encounters button:Fork geometry";
	private static final String EXPECTED_ZONE_ROW = "label: Zone actions:   button:Connect zones button:Rename button:Empty button:Find reusable zones button:Remove added zones button:Custom battle facility";
	private static final String EXPECTED_EXTRAS_ROW = "button:Raw archive browser (Builder) button:Tileset editor button:Workspace & paths";

	/**
	 * Public static fields CtrmapMainframe may declare. 91 mutable ones (plus
	 * the final CM3DComponents list) when the decomposition began, every one
	 * assigned once inside createAndShowGUI. LOWER this as widgets find
	 * owners; raising it is the decision this exists to make visible.
	 */
	//23 -> 21: the held tool is an owner now (ToolSelection) and the frame is
	//private, nothing below the window having a reason to reach it.
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

	private static final int STATIC_CEILING = 21;

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
		everyMenuPathAMessageNamesExists(new File(src, "ctrmap"));

		JMenuBar bar = CtrmapMainframe.buildMenuBar();
		menuTree(bar);
		theMenuBarMayNotGrow(bar);
		menuWired(bar);
		menuTooltips(bar);
		toolRow();
		actionRow("map-actions", CtrmapMainframe.buildMapActionsBar(), EXPECTED_MAP_ROW);
		actionRow("Zone Loader", CtrmapMainframe.buildZoneActionsBar(), EXPECTED_ZONE_ROW);
		actionRow("Extras", CtrmapMainframe.buildExtrasBar(), EXPECTED_EXTRAS_ROW);
		shape(new File(src, "ctrmap/CtrmapMainframe.java"));
		cameraFraming(new File(src, "ctrmap/CtrmapMainframe.java"));
		theZoneActionsShareOneRow(src);
		theZoneButtonsSurviveALayoutPass();
		theLooseMapClearNamesEveryEntityForm(new File(src, "ctrmap/CtrmapMainframe.java"));
		windowFree(new File(src, "ctrmap/util/Bytes.java"));
		windowFree(new File(src, "ctrmap/humaninterface/Forms.java"));
		windowFree(new File(src, "ctrmap/humaninterface/Picking.java"));
		ceiling();

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
		//RETARGETED from 35 to 34, with the reason: "Browse zones (3D preview)..." was
		//removed along with the dialog behind it. The preview it opened now lives in
		//the Zone Loader tab beside the dropdown, which is where the owner looked for
		//it twice and did not find it. The count is pinned so an item cannot be added,
		//moved or lost without somebody writing down that they meant to.
		//35 since Help gained the quick start guide - the file package.ps1 has always
		//shipped beside the program with nothing in the program naming it.
		check(items == 35, "35 menu items in all (" + items + ")");

		theZoneLoaderTabHoldsItsPreview();
		browsingAZoneDoesNotLoadIt();
		theExtrasTabHoldsTheArchiveBrowser();
		theGameDataTabHoldsItsEditors();
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
		java.awt.Container tab = ctrmap.CtrmapMainframe.buildZoneTab(stand, new ctrmap.LoadedZone(), new ctrmap.humaninterface.tools.ToolSelection());
		java.awt.LayoutManager lay = tab.getLayout();
		Component east = lay instanceof java.awt.BorderLayout
			? ((java.awt.BorderLayout) lay).getLayoutComponent(java.awt.BorderLayout.EAST) : null;
		//the column can show a zone tool over the browser, so look INSIDE it
		ctrmap.humaninterface.ZoneBrowserPane found = null;
		for (Component c : east instanceof java.awt.Container
			? ((java.awt.Container) east).getComponents() : new Component[0]) {
			if (c instanceof ctrmap.humaninterface.ZoneBrowserPane) {
				found = (ctrmap.humaninterface.ZoneBrowserPane) c;
			}
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
		java.awt.Container tab = ctrmap.CtrmapMainframe.buildExtrasTab(tools, browser);
		java.awt.LayoutManager lay = tab.getLayout();
		Component centre = lay instanceof java.awt.BorderLayout
			? ((java.awt.BorderLayout) lay).getLayoutComponent(java.awt.BorderLayout.CENTER) : null;
		check(holds(centre, tools), "the Extras tools are in the tab");
		ctrmap.CtrmapMainframe.showInExtras("Raw archive browser (Builder)", browser);
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
		java.awt.Container tab = ctrmap.CtrmapMainframe.buildGameDataTab(subjects);
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
		ctrmap.CtrmapMainframe.showGameData("Trainer editor", stand);
		check(holds(split.getRightComponent(), stand),
			"...and an editor handed to it actually lands there, under its name");
		ctrmap.CtrmapMainframe.clearGameData();
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
	 * <p>It reads production sources for the literal shape, builds the real menu bar, and
	 * matches. An item may be named by its start, so a message can leave off the long
	 * parenthetical, and the ellipsis is ignored - what it refuses is a path that resolves
	 * to nothing at all.
	 */
	static void everyMenuPathAMessageNamesExists(File srcRoot) throws Exception {
		System.out.println("--- every menu path a message names is one the menu bar has");
		JMenuBar bar = CtrmapMainframe.buildMenuBar();
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
		java.util.regex.Pattern named = java.util.regex.Pattern.compile(
			//the item ends at a full stop, a bracket or a colon: several messages name a
			//path and then explain it - "File > Save: every editor stores what it holds" -
			//and the explanation is not part of the label
			"(\\w[\\w ]{1,20}?) > ([A-Z][^\\n):]{2,60}?)[.):]");
		List<String> wrong = new ArrayList<>();
		int checked = 0;
		for (File file : DialogSeamTest.javaSources(srcRoot)) {
			if (file.getParentFile() != null && file.getParentFile().getName().equals("tests")) {
				continue;
			}
			String body = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
			java.util.regex.Matcher m = named.matcher(body);
			while (m.find()) {
				String menu = m.group(1).trim();
				String item = m.group(2).trim();
				if (!menus.containsKey(menu)) {
					continue;                      //not a menu path at all, just prose with a >
				}
				checked++;
				boolean found = false;
				for (String label : menus.get(menu)) {
					//either way round: a message may shorten a long label ("Fork map geometry" for
					//"Fork map geometry (make zone independent)...") or carry on past a short one
					//("Open Zone is only for single loose ZO files")
					String a = label == null ? "" : label.toLowerCase();
					String b = item.toLowerCase();
					if (!a.isEmpty() && (a.startsWith(b) || b.startsWith(a))) {
						found = true;
					}
				}
				if (!found) {
					wrong.add(file.getName() + ": " + menu + " > " + item);
				}
			}
		}
		check(wrong.isEmpty(), checked + " menu path(s) named in messages, all of which the menu"
			+ " bar has" + (wrong.isEmpty() ? "" : " - except " + wrong + ". These fire at the moment"
			+ " a user is blocked, so the one sentence they get has to name something they can find"));
		check(checked >= 5, "...and there were paths to check (" + checked + "), so a message that"
			+ " stopped naming any would not pass this quietly");
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
		WorldEditorToolbar row = new WorldEditorToolbar(e -> commands.add(e.getActionCommand()),
				() -> toggles[0]++, () -> toggles[1]++, INSPECTOR, null);

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
		check(singleBody.contains("translateX = 0f;") && matrixBody.contains("translateX = -cellsAcross * 360f;"),
				"the difference LEFT between them is pinned, not fixed: a single region frames at translateX 0,"
				+ " where the matrix formula gives -360 for one cell across, so a loose GR sits half a region off centre");

		//THE PREVIEW DOES NOT MOVE THE CAMERA, and that is a decision with a bill attached.
		//Framing a zone's own cells was tried twice and broke the preview both times: first
		//by clamping a footprint into a matrix it did not fit, aiming at a corner of a map
		//the zone is not on; then by pulling back the footprint's own depth, which put the
		//camera 720 units from a map the working view frames from 7200. Both shipped with a
		//green suite, because a suite here can assert the ARITHMETIC and cannot see the
		//PICTURE. The camera is left where loadRegions puts it, and WHICH PART a zone
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
							})),
					new ctrmap.humaninterface.Navigator() {
						@Override
						public void follow(ctrmap.humaninterface.MapObject o) {
						}

						@Override
						public void resync() {
						}
					});
		} catch (Throwable cannotBuild) {
			check(false, "a real ZoneLoadingPanel can be built headlessly: " + cannotBuild);
			return;
		}
		java.awt.Container tab = CtrmapMainframe.buildZoneTab(panel, owner, tools);
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
	 * Utils is the grab-bag every format and form reaches for. It used to
	 * reach back into the window for its split pane and frame, which made
	 * the window a dependency of everything. It no longer names the window
	 * at all.
	 */
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
