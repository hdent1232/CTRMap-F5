package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import java.awt.Component;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

/**
 * The main window's menu tree, built headless and compared with what the user
 * is meant to see.
 *
 * <p>Until this suite existed nothing asserted that the Options menu still
 * held "Restore from pristine backup..." after the next edit to
 * {@code CtrmapMainframe}: every menu, item and listener was built inside one
 * 766-line method whose only test was starting the application. The menu bar
 * is now one builder that owns its items as locals and touches no other
 * widget while it runs, so it can be built with no display and read back.
 *
 * <p>What is pinned:
 * <ul>
 * <li>the tree - every menu, every item, in order, separators where they are;</li>
 * <li>that every item is wired to exactly one action (an item with none is a
 *     label that does nothing when clicked, and Swing will not say so);</li>
 * <li>the three tooltips that explain an item the label cannot;</li>
 * <li>the SHAPE of the window class: no anonymous {@code ActionListener}
 *     (the menu is declarative - an item names the method that does the work)
 *     and no preference node named after {@code getClass()} - four anonymous
 *     listeners once keyed the remembered folder of a file dialog on their
 *     own compiler-numbered name, so any anonymous class added above them
 *     silently forgot the folder;</li>
 * <li>a ratchet on the window's public static fields, the count the
 *     decomposition exists to bring down, so it cannot creep back up.</li>
 * </ul>
 *
 * <p>ORDER: needs no dump, no workspace, no scratch space; writes nothing.
 *
 * Usage: java ctrmap.tests.MainframeMenusTest [src-root]   (default "src")
 */
public class MainframeMenusTest {

	/**
	 * The menus as the user sees them: one line per menu, items in order,
	 * "|" between items, "---" where a separator sits. Edit this WITH the
	 * menu - that is the point.
	 */
	private static final String[] EXPECTED = {
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

	/**
	 * Public static fields CtrmapMainframe may declare. 91 mutable ones (plus
	 * the final CM3DComponents list) when the decomposition began, every one
	 * assigned once inside createAndShowGUI. LOWER this as widgets find
	 * owners; raising it is the decision this exists to make visible.
	 */
	private static final int STATIC_CEILING = 54;

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
		//no display is needed to build a menu, and none may be assumed: the
		//battery runner passes no flags, and a window on the owner's desktop
		//would block the run until somebody closed it
		System.setProperty("java.awt.headless", "true");
		File src = new File(args.length > 0 ? args[0] : "src");

		JMenuBar bar = CtrmapMainframe.buildMenuBar();
		tree(bar);
		wired(bar);
		tooltips(bar);
		shape(new File(src, "ctrmap/CtrmapMainframe.java"));
		ceiling();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	// ------------------------------------------------------------------ tree
	static void tree(JMenuBar bar) {
		List<String> got = new ArrayList<>();
		for (int i = 0; i < bar.getMenuCount(); i++) {
			got.add(render(bar.getMenu(i)));
		}
		check(got.size() == EXPECTED.length, "the menu bar holds " + EXPECTED.length + " menus (it holds " + got.size() + ")");
		for (int i = 0; i < Math.min(got.size(), EXPECTED.length); i++) {
			check(got.get(i).equals(EXPECTED[i]), "menu " + (i + 1) + " reads: " + EXPECTED[i]
					+ (got.get(i).equals(EXPECTED[i]) ? "" : "\n        but is: " + got.get(i)));
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

	// ----------------------------------------------------------------- wired
	static void wired(JMenuBar bar) {
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

	// -------------------------------------------------------------- tooltips
	static void tooltips(JMenuBar bar) {
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

	// ----------------------------------------------------------------- shape
	static void shape(File mainframe) throws Exception {
		if (!mainframe.isFile()) {
			check(false, "the window's source is at " + mainframe + " (pass the src root as args[0])");
			return;
		}
		String text = SourceSeamTest.stripComments(new String(Files.readAllBytes(mainframe.toPath()), StandardCharsets.UTF_8));
		check(!text.contains("new ActionListener()"),
				"the window declares no anonymous ActionListener - an item names the method that does its work");
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
