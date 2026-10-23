package ctrmap.tests;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * Every place in the window that swaps its view for a tool has a way back that a person can SEE,
 * and the zone list - the only way to open a zone - can be reached from anywhere.
 *
 * <p>PAID FOR IN 1.0.4. The release went out with the Zone Loader's right-hand column able to show
 * Connect zones in place of the zone list, and the way back was a button in a JToolBar - which
 * Windows draws flat, borderless until hovered, so it read as the tool's caption. Nothing in the Zone
 * menu or on the zone actions bar went back to the list. The owner clicked a zone action while the
 * editor was slow, the window caught up on that tool, and they could not open a zone - in a release.
 * Every suite was green, because every suite that looked at the column asked what was IN it, and the
 * button was in it. A tree holding a button is not a person seeing one.
 *
 * <p>So this LAYS THE TABS OUT and judges by geometry: a way back is one that is a bordered JButton,
 * not inside a toolbar, inside the window's bounds, with every ancestor visible - and pressing it, or
 * Esc, or the Zone list action from the bar or the menu, brings the zone list's Load button back on
 * screen. And by source: the window owns no removeAll() of its own (every swap goes through
 * ToolHost, which makes the way back), no "Back to" button is made anywhere else, and no message
 * sends the user to the zone dropdown that 1.0.4 hid.
 *
 * <p>ORDER: needs no dump, no workspace, no display; writes nothing.
 *
 * Usage: java ctrmap.tests.ToolHostTest [src-root]   (default "src")
 */
public class ToolHostTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File src = new File(args.length > 0 ? args[0] : "src");
		SwingUtilities.invokeAndWait(() -> {
			try {
				theZoneListComesBackFromATool();
				extrasAndGameDataHaveAWayBack();
				aToolHoldingAnEditIsAsked();
			} catch (Throwable t) {
				t.printStackTrace(System.out);
				check(false, "the zone tab could be built and driven: " + t);
			}
		});
		theWindowSwapsNoViewOfItsOwn(src);
		noMessageSendsTheUserToTheHiddenDropdown(src);
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/** The window the tabs are laid out in, at the smallest size the editor is used at. */
	static JPanel window(Container tab) {
		JPanel root = new JPanel(new BorderLayout());
		root.add(tab, BorderLayout.CENTER);
		root.setSize(1366, 768);
		layOut(root);
		return root;
	}

	/** Lays a tree out top-down by hand: validate() does nothing without a display, and a suite has none. */
	static void layOut(Container c) {
		c.doLayout();
		for (Component k : c.getComponents()) {
			if (k instanceof Container) {
				layOut((Container) k);
			}
		}
	}

	/**
	 * Whether a person could see {@code c}: it and every ancestor up to {@code root} visible, it has
	 * an area, and that area lies inside the window. What isShowing() asks, without a display.
	 */
	static boolean onScreen(Component c, Container root) {
		if (c == null || c.getWidth() <= 0 || c.getHeight() <= 0) {
			return false;
		}
		for (Component k = c; k != root; k = k.getParent()) {
			if (k == null || !k.isVisible()) {
				return false;
			}
		}
		Rectangle r = SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), root);
		return new Rectangle(0, 0, root.getWidth(), root.getHeight()).contains(r);
	}

	/** The one way back shown under {@code root}, or null - every button whose text begins "Back to ". */
	static List<JButton> waysBack(Container root) {
		List<JButton> out = new ArrayList<>();
		for (Component k : root.getComponents()) {
			if (k instanceof JButton && ((JButton) k).getText() != null
					&& ((JButton) k).getText().startsWith(ctrmap.humaninterface.ToolHost.BACK)) {
				out.add((JButton) k);
			}
			if (k instanceof Container) {
				out.addAll(waysBack((Container) k));
			}
		}
		return out;
	}

	/** Whether a way back is one a person reads as a button: bordered, and not drawn flat in a toolbar. */
	static String looksLikeAButton(JButton b) {
		if (!b.isBorderPainted()) {
			return "its border is not painted";
		}
		for (Container k = b.getParent(); k != null; k = k.getParent()) {
			if (k instanceof JToolBar) {
				return "it is in a JToolBar, which Windows draws flat until hovered";
			}
		}
		return null;
	}

	/** Checks the way back under {@code root} for a tool just opened: exactly one, seen, a real button, saying where. */
	static JButton theWayBack(Container root, String says, String what) {
		layOut(root);
		List<JButton> backs = waysBack(root);
		check(backs.size() == 1, what + ": exactly one way back is shown (" + backs.size() + ")");
		if (backs.size() != 1) {
			return null;
		}
		JButton b = backs.get(0);
		check(b.getText().equals(says), what + ": it says where it goes - '" + b.getText() + "'");
		String flat = looksLikeAButton(b);
		check(flat == null, what + ": it looks like a button" + (flat == null ? "" : " - " + flat));
		check(onScreen(b, root), what + ": it is on screen at " + b.getBounds());
		return b;
	}

	static void theZoneListComesBackFromATool() throws Exception {
		System.out.println("--- the zone list comes back from a tool, five ways, and is seen to");
		ctrmap.LoadedZone owner = new ctrmap.LoadedZone();
		ctrmap.humaninterface.tools.ToolSelection tools = new ctrmap.humaninterface.tools.ToolSelection();
		ctrmap.humaninterface.ZoneLoadingPanel panel = MainframeShapeTest.realZonePanel(owner, tools);
		JPanel root = window(TestWindow.buildZoneTab(panel, owner, tools));
		ctrmap.humaninterface.ZoneBrowserPane browser = panel.browser();
		JButton load = browser.loadButton();
		//CONTROL: the geometry reads a list that IS there as there, and one that is not as gone
		check(onScreen(browser, root) && onScreen(load, root),
				"control: with no tool open the zone list and '" + load.getText() + "' are on screen");
		check(waysBack(root).isEmpty(), "control: and no way back is shown, there being nowhere to go back from");

		String[] ways = {"its Back button", "Esc", "the tool's own close", "Zone list on the zone actions bar",
			"Zone > Zone list in the menus"};
		for (String way : ways) {
			JPanel tool = new JPanel();
			tool.add(new javax.swing.JLabel("a zone tool"));
			TestWindow.inZoneColumn("Connect zones", tool);
			layOut(root);
			check(!onScreen(load, root), way + " - control: with a tool open the zone list's Load button is NOT on screen");
			JButton back = theWayBack(root, "Back to the zone list", way);
			switch (way) {
				case "its Back button":
					if (back != null) {
						back.doClick();
					}
					break;
				case "Esc":
					escape(back);
					break;
				case "the tool's own close":
					TestWindow.closeZoneColumnTool();
					break;
				case "Zone list on the zone actions bar":
					press(TestWindow.buildZoneActionsBar(), "Zone list", way);
					break;
				default:
					press(zoneMenu(), "Zone list (pick a zone to load)", way);
					break;
			}
			layOut(root);
			check(onScreen(browser, root) && onScreen(load, root),
					"after " + way + " the zone list and '" + load.getText() + "' are on screen again");
		}
	}

	/** Runs the Esc binding of the box a way back sits in, as the key would. */
	static void escape(JButton back) {
		KeyStroke esc = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0);
		for (Container k = back == null ? null : back.getParent(); k != null; k = k.getParent()) {
			if (k instanceof JComponent) {
				Object name = ((JComponent) k).getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(esc);
				javax.swing.Action a = name == null ? null : ((JComponent) k).getActionMap().get(name);
				if (a != null) {
					a.actionPerformed(new java.awt.event.ActionEvent(k, java.awt.event.ActionEvent.ACTION_PERFORMED, "esc"));
					return;
				}
			}
		}
		check(false, "Esc is bound around the way back");
	}

	/** The Zone menu of the window's real menu bar. */
	static JMenu zoneMenu() {
		JMenuBar bar = TestWindow.buildMenuBar();
		for (int i = 0; i < bar.getMenuCount(); i++) {
			if ("Zone".equals(bar.getMenu(i).getText())) {
				return bar.getMenu(i);
			}
		}
		return null;
	}

	/** Presses the button or menu item labelled {@code label} under {@code c}. */
	static void press(Container c, String label, String way) {
		if (c != null) {
			for (Component k : c instanceof JMenu ? ((JMenu) c).getMenuComponents() : c.getComponents()) {
				if (k instanceof javax.swing.AbstractButton && label.equals(((javax.swing.AbstractButton) k).getText())) {
					((javax.swing.AbstractButton) k).doClick();
					return;
				}
			}
		}
		check(false, way + ": there is a '" + label + "' to press");
	}

	static void extrasAndGameDataHaveAWayBack() {
		System.out.println("--- the Extras and Game Data tabs have a way back that is seen");
		JPanel extras = new JPanel();
		extras.add(new JButton("an Extras tool's button"));
		JPanel builder = new JPanel();
		builder.add(new javax.swing.JLabel("the archive browser"));
		JPanel root = window(TestWindow.buildExtrasTab(extras, builder));
		check(onScreen(extras, root), "control: the Extras tools are on screen with nothing open");
		TestWindow.showInExtras("Raw archive browser (Builder)", builder);
		layOut(root);
		check(!onScreen(extras, root), "control: and gone while a tool is open");
		JButton back = theWayBack(root, "Back to Extras", "Extras");
		if (back != null) {
			back.doClick();
		}
		layOut(root);
		check(onScreen(extras, root), "Back to Extras puts the Extras tools on screen again");
		//AN EXTRAS PANE HOLDING AN EDIT IS ASKED (F105): these panes were windows that saved in
		//windowClosing, and one that answers no has an edit it will not throw away
		ToolColumnTest.Asking holding = new ToolColumnTest.Asking(false);
		TestWindow.showInExtras("Tileset editor", holding);
		JButton keep = theWayBack(root, "Back to Extras", "Extras, a pane holding an edit");
		if (keep != null) {
			keep.doClick();
		}
		layOut(root);
		check(holding.asked == 1 && onScreen(holding, root) && !onScreen(extras, root),
				"a pane that will not let go of its edit is asked, and stays (asked " + holding.asked + ")");
		TestWindow.showInExtras("Raw archive browser (Builder)", builder);
		check(holding.asked == 2 && onScreen(holding, root),
				"...and another tool is not shown over it without asking it either (asked " + holding.asked + ")");

		JPanel subjects = new JPanel();
		JPanel data = window(TestWindow.buildGameDataTab(subjects));
		javax.swing.JLabel editor = new javax.swing.JLabel("an editor");
		TestWindow.showGameData("Trainer editor", editor);
		layOut(data);
		check(onScreen(editor, data), "control: a Game Data editor opened is on screen");
		JButton dataBack = theWayBack(data, "Back to the Game Data subjects", "Game Data");
		if (dataBack != null) {
			dataBack.doClick();
		}
		layOut(data);
		check(!onScreen(editor, data) && waysBack(data).isEmpty(), "and its way back puts it away");
	}

	static void aToolHoldingAnEditIsAsked() {
		System.out.println("--- a tool holding an edit is asked before it is left, and keeps the window if it says no");
		JPanel home = new JPanel();
		ctrmap.humaninterface.ToolHost host = new ctrmap.humaninterface.ToolHost("home", home, true);
		boolean[] letGo = {false};
		int[] asked = {0};
		host.show("A form", new JPanel(), null, () -> {
			asked[0]++;
			return letGo[0];
		});
		host.backButton().doClick();
		check(asked[0] == 1 && !host.atHome(), "a tool that will not let go is asked, and stays (" + asked[0] + ")");
		check(!host.show("Another", new JPanel(), null, null) && asked[0] == 2,
				"...and another tool cannot be shown over it without asking it either");
		letGo[0] = true;
		check(host.goHome() && host.atHome() && home.getParent() == host, "once it lets go the home view is back");
	}

	/**
	 * The window swaps no view itself: every swap goes through ToolHost, which is the one maker of a
	 * way back. A removeAll() written into the window again is a swap with whatever way back its author
	 * remembered - the defect, one edit away.
	 */
	static void theWindowSwapsNoViewOfItsOwn(File src) throws Exception {
		System.out.println("--- the window swaps no view of its own, and nothing but ToolHost makes a way back");
		String host = code(new File(src, "ctrmap/humaninterface/ToolHost.java"));
		check(host.contains("removeAll()") && host.contains("\"Back to \""),
				"control: the scan finds removeAll() and \"Back to \" where they are, in ToolHost");
		String window = code(new File(src, "ctrmap/CtrmapMainframe.java"));
		check(!window.contains(".removeAll()"), "CtrmapMainframe has no removeAll() - its views are swapped by ToolHost");
		List<String> makers = new ArrayList<>();
		for (File f : javaFiles(new File(src, "ctrmap"))) {
			if (f.getPath().replace('\\', '/').contains("/tests/") || f.getName().equals("ToolHost.java")) {
				continue;
			}
			for (String lit : literals(code(f))) {
				if (lit.startsWith("Back to ")) {
					makers.add(f.getName() + ": \"" + lit + "\"");
				}
			}
		}
		check(makers.isEmpty(), "no way back is made outside ToolHost: " + makers);
	}

	/**
	 * No message sends the user to the zone dropdown. 1.0.4 hid it behind the zone list and twelve
	 * sentences went on telling people to use it - the Zone Loader tab's own tooltip, the setup wizard's
	 * last page, the hint shown when a workspace opens, and Clone zone's refusal among them.
	 */
	static void noMessageSendsTheUserToTheHiddenDropdown(File src) throws Exception {
		System.out.println("--- no message sends the user to the hidden zone dropdown");
		List<String> stale = new ArrayList<>();
		boolean controlSeen = false;
		for (File f : javaFiles(new File(src, "ctrmap"))) {
			if (f.getPath().replace('\\', '/').contains("/tests/")) {
				continue;
			}
			for (String lit : literals(code(f))) {
				String l = lit.toLowerCase();
				controlSeen |= l.contains("pick one from the dropdown");
				if (l.contains("dropdown") && (l.contains("zone") || l.contains("map"))) {
					stale.add(f.getName() + ": \"" + lit + "\"");
				}
			}
		}
		check(controlSeen, "control: the scan reads the NPC form's own script-dropdown sentence, which stays");
		check(stale.isEmpty(), "no sentence names a zone or map dropdown: " + stale);
	}

	static String code(File f) throws Exception {
		return SourceSeamTest.stripComments(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
	}

	static List<File> javaFiles(File dir) {
		List<File> out = new ArrayList<>();
		File[] in = dir.listFiles();
		if (in != null) {
			for (File f : in) {
				if (f.isDirectory()) {
					out.addAll(javaFiles(f));
				} else if (f.getName().endsWith(".java")) {
					out.add(f);
				}
			}
		}
		return out;
	}

	private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

	static List<String> literals(String code) {
		List<String> out = new ArrayList<>();
		Matcher m = LITERAL.matcher(code);
		while (m.find()) {
			out.add(m.group(1));
		}
		return out;
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok   " : "  FAIL ") + what);
		if (!ok) {
			fails++;
		}
	}
}
