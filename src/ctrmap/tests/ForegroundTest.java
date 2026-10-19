package ctrmap.tests;

import ctrmap.Ui;
import ctrmap.update.Updater;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * A window the editor opens while the owner is in another program waits for them, and a window the
 * updater reopens comes back minimized (owner, 2026-10-12: "stop all of the pop ups going to my
 * foreground ... not just visually either, just because i dont visually see them if they load into my
 * foreground they kick me out of stuff i am doing").
 *
 * <p>WHY THIS SUITE EXISTS. Every dialog the editor opens through {@link Ui} came up at once, whether or
 * not the owner was using the editor: the update offer when the network answered, the result of a pack
 * started minutes before, anything in a window the updater had just reopened. A window that comes up
 * over another program takes its foreground, and with it the keyboard. Asked here with real windows -
 * the battery runs every JVM on the unattended desktop, so none of them is on the owner's - a dialog
 * through Ui opens at once while a window of the program is active, and at once while none is showing;
 * while windows show and none is active it waits, through an unrelated window's coming and going, and
 * opens when one is activated. Each case has a deadline, so a wait that never ends is a failure here,
 * not a suite that never returns.
 *
 * <p>ORDER: needs a display (the unattended desktop's will do) and nothing else; refuses to pass
 * without one.
 *
 * Usage: java ctrmap.tests.ForegroundTest
 */
public class ForegroundTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		if (java.awt.GraphicsEnvironment.isHeadless()) {
			Skip.suite("no display: a dialog that waits for the owner is asked with real windows");
		}
		//the application's switch, set as the other suites that open a real dialog set it
		java.lang.reflect.Field enabled = Ui.class.getDeclaredField("dialogsEnabled");
		enabled.setAccessible(true);
		enabled.set(null, true);
		try {
			aDialogOpensAtOnceWhileTheProgramIsInUse();
			aDialogOpensAtOnceWhileNothingIsShowing();
			aDialogWaitsWhileTheProgramIsNotInUse();
		} finally {
			enabled.set(null, false);
		}
		aProgressDialogNobodysClickOpenedWaits();
		theReopenedEditorComesBackMinimized();
		everyWindowNobodyAskedForIsWired();
		everyWindowTheEditorOpensIsClassified(new File("src/ctrmap"));

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	private static void aDialogOpensAtOnceWhileTheProgramIsInUse() throws Exception {
		System.out.println("--- a dialog opens at once while a window of the program is the one in use");
		JFrame f = frame("in use", true);
		check(becomes(() -> f.isActive(), 5000), "CONTROL: a window of the program is active on this desktop");
		AtomicBoolean back = ask("Asked while in use");
		Dialog d = shown("Asked while in use", 3000);
		check(d != null, "a dialog asked while the program is in use opens at once");
		close(d, back);
		dispose(f);
	}

	private static void aDialogOpensAtOnceWhileNothingIsShowing() throws Exception {
		System.out.println("--- a dialog opens at once while no window of the program is showing");
		check(becomes(() -> !anyShowing(), 3000), "CONTROL: no window of the program is showing");
		AtomicBoolean back = ask("Asked with nothing showing");
		Dialog d = shown("Asked with nothing showing", 3000);
		check(d != null, "a dialog asked while nothing of the program is showing opens at once");
		close(d, back);
	}

	private static void aDialogWaitsWhileTheProgramIsNotInUse() throws Exception {
		System.out.println("--- a dialog waits while the program's windows show and none is in use");
		JFrame f = frame("not in use", false);
		String[] saw = {null};
		boolean none = becomes(() -> {
			Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
			saw[0] = "showing " + f.isShowing() + ", active " + (active == null ? "none" : active.getClass().getSimpleName()
					+ " \"" + (active instanceof Frame ? ((Frame) active).getTitle() : active instanceof Dialog
					? ((Dialog) active).getTitle() : "") + "\"");
			return f.isShowing() && (active == null || !active.isShowing());
		}, 3000);
		check(none, "CONTROL: a window of the program shows and none showing is active (" + saw[0] + ")");
		AtomicBoolean back = ask("Asked while not in use");
		check(shown("Asked while not in use", 1500) == null, "a dialog asked while the program is not in use waits");
		JFrame other = frame("another window", false);
		Thread.sleep(300);
		dispose(other);
		check(shown("Asked while not in use", 1000) == null,
				"and another window's coming and going is not the owner coming back: it still waits");
		SwingUtilities.invokeAndWait(() -> {
			f.setFocusableWindowState(true);
			f.toFront();
			f.requestFocus();
		});
		Dialog d = shown("Asked while not in use", 5000);
		check(d != null, "and it opens when a window of the program is activated");
		close(d, back);
		dispose(f);
	}

	/**
	 * A progress dialog - every one of the editor's goes through LoadingDialog - shows at once while the editor is
	 * in use; asked while it is not (the deploy a finished pack starts, minutes after the click) it waits, and when
	 * its work finishes during the wait it is never shown: a progress dialog shown after its close is a modal window
	 * nothing would ever close.
	 */
	private static void aProgressDialogNobodysClickOpenedWaits() throws Exception {
		System.out.println("--- a progress dialog nobody's click just opened waits, and one whose work is done never shows");
		JFrame used = frame("in use, for progress", true);
		check(becomes(() -> used.isActive(), 5000), "CONTROL: a window of the program is active for the progress dialog");
		ctrmap.humaninterface.LoadingDialog[] now = {null};
		SwingUtilities.invokeAndWait(() -> now[0] = ctrmap.humaninterface.LoadingDialog.makeDialog("Asked while in use"));
		AtomicBoolean nowBack = showing(now[0]);
		check(becomes(() -> progressShows(now[0]), 3000), "a progress dialog asked while the program is in use shows at once");
		SwingUtilities.invokeLater(now[0]::close);
		check(becomes(nowBack::get, 3000), "CONTROL: and it returns once its work closes it");
		dispose(used);

		JFrame idle = frame("not in use, for progress", false);
		check(becomes(() -> idle.isShowing(), 3000), "CONTROL: a window of the program shows, not active, for the progress dialog");
		ctrmap.humaninterface.LoadingDialog[] later = {null};
		SwingUtilities.invokeAndWait(() -> later[0] = ctrmap.humaninterface.LoadingDialog.makeDialog("Asked while not in use"));
		AtomicBoolean laterBack = showing(later[0]);
		Thread.sleep(1500);
		check(!becomes(() -> progressShows(later[0]), 200) && !laterBack.get(),
				"a progress dialog asked while the program is not in use waits");
		SwingUtilities.invokeLater(later[0]::close);            //its work finishes while it waits
		Thread.sleep(300);
		SwingUtilities.invokeAndWait(() -> {
			idle.setFocusableWindowState(true);
			idle.toFront();
			idle.requestFocus();
		});
		check(becomes(laterBack::get, 5000) && !becomes(() -> progressShows(later[0]), 300),
				"and one whose work finished while it waited is never shown, the owner coming back or not");
		dispose(idle);
	}

	/** Shows a progress dialog on the event thread; the flag says when showDialog has returned. */
	private static AtomicBoolean showing(ctrmap.humaninterface.LoadingDialog progress) {
		AtomicBoolean back = new AtomicBoolean();
		SwingUtilities.invokeLater(() -> {
			progress.showDialog();
			back.set(true);
		});
		return back;
	}

	/** Whether the progress dialog's own window shows - asked on the event thread, where {@link #becomes} asks it. */
	private static boolean progressShows(ctrmap.humaninterface.LoadingDialog progress) {
		Window w = SwingUtilities.getWindowAncestor(progress);
		return w != null && w.isShowing();
	}

	/**
	 * The window the updater reopens, shown as the editor shows it, beside one shown as always: the first is
	 * minimized and never the active window, the control maximized and active.
	 */
	private static void theReopenedEditorComesBackMinimized() throws Exception {
		System.out.println("--- the window the updater reopens comes back minimized, not active");
		int asStarted = Updater.firstWindowState(new String[0]);
		int reopened = Updater.firstWindowState(new String[]{Updater.REOPENED_FLAG});
		check(asStarted == Frame.MAXIMIZED_BOTH && Updater.firstWindowState(null) == Frame.MAXIMIZED_BOTH
				&& Updater.firstWindowState(new String[]{"--something-else"}) == Frame.MAXIMIZED_BOTH,
				"started by the owner, the window shows maximized, as always");
		check(reopened == (Frame.ICONIFIED | Frame.MAXIMIZED_BOTH),
				"reopened by the updater, it shows minimized, restoring maximized: " + reopened);
		JFrame control = stated("as started", asStarted);
		check(becomes(() -> control.isActive(), 5000), "CONTROL: a window shown as the owner starts it becomes the active one");
		JFrame back = stated("as reopened", reopened);
		Thread.sleep(1500);
		boolean[] seen = {true, false};
		SwingUtilities.invokeAndWait(() -> {
			seen[0] = back.isActive();
			seen[1] = (back.getExtendedState() & Frame.ICONIFIED) != 0;
		});
		check(back.isShowing() && seen[1] && !seen[0],
				"the window the updater reopens is minimized and does not become the active one (active " + seen[0]
				+ ", minimized " + seen[1] + ")");
		dispose(back);
		dispose(control);
	}

	/** The windows nobody's click opens are each wired to wait, read in the source that opens them. */
	private static void everyWindowNobodyAskedForIsWired() throws Exception {
		System.out.println("--- every window nobody's click opens is wired to wait");
		String updater = SourceSeamTest.stripComments(DialogSeamTest.read(new File("src/ctrmap/update/Updater.java")));
		String runApply = CollisionReadTest.body(updater, "public static void runApply(String[] args) {");
		check(runApply != null && runApply.contains("new ProcessBuilder(exe.getAbsolutePath(), REOPENED_FLAG)"),
				"the updater reopens the editor saying it is a reopen");
		String main = SourceSeamTest.stripComments(DialogSeamTest.read(new File("src/ctrmap/CtrmapMainframe.java")));
		String start = CollisionReadTest.body(main, "public static void main(String[] args) {");
		String gui = CollisionReadTest.body(main, "private void createAndShowGUI() {");
		check(start != null && start.contains("window.firstState = ctrmap.update.Updater.firstWindowState(args);")
				&& gui != null && gui.contains("frame.setExtendedState(firstState);")
				&& !gui.contains("frame.setExtendedState(JFrame.MAXIMIZED_BOTH);"),
				"and the editor shows its window in the state its start asks for");
		String offerUi = SourceSeamTest.stripComments(DialogSeamTest.read(new File("src/ctrmap/update/UpdateUI.java")));
		String offer = CollisionReadTest.body(offerUi, "private static void offer(Frame parent, final UpdateChecker.Release rel, boolean startup) {");
		int wait = offer == null ? -1 : offer.indexOf("ctrmap.Ui.untilInUse();");
		//split, so DialogSeamTest does not read this suite's pattern as a raw dialog of its own
		check(wait >= 0 && wait < offer.indexOf("ctrmap.Ui.formOption("),
				"the update offer waits until the editor is in use before it opens");
	}

	/** How a window comes up: on whose action, and so whether it must wait for the owner. */
	private enum Opens {
		/** Directly on the owner's click - a menu item, a button, a mouse click - so the editor is in use. */
		CLICK,
		/** At the start the owner made: the editor's own window, and the first-run wizard. */
		LAUNCH,
		/** Through Ui's one choke point, which waits (onTheEventThread; EdtReachTest pins every one). */
		SEAM,
		/** A progress dialog: LoadingDialog.showDialog, which waits - and skips one whose work is done. */
		PROGRESS,
		/** Nobody's click opens it at the moment it comes up, so it waits: Ui.untilInUse just before it. */
		WAITS,
		/** Matches the shape but is no window: a component inside one. */
		NOT_A_WINDOW
	}

	/** Every way the editor's source puts a window up, as a line shape. */
	private static final java.util.regex.Pattern WINDOW_LINE = java.util.regex.Pattern.compile(
			"JOptionPane\\.show[A-Za-z]*Dialog\\(|\\.show(Open|Save)?Dialog\\(|\\.setVisible\\(true\\)"
			+ "|Desktop\\.getDesktop\\(\\)\\.(open|browse|edit|mail)");

	/**
	 * Every window the editor's own source opens, by file and a piece of its line, how many lines share it, and how
	 * it comes up. Read from the code, each one, on 2026-10-13: 42 lines in 15 files - 29 in 14 since 2026-10-15, when
	 * the sixteen form dialogs went through Ui.form, Ui.formOption and Ui.show, which wait as every seam dialog does.
	 */
	private static final Object[][] WINDOWS = {
		{"CtrmapMainframe.java", "frame.setVisible(true);", 1, Opens.LAUNCH},
		{"CtrmapMainframe.java", "Desktop.getDesktop().open(guide);", 1, Opens.CLICK},
		{"CtrmapMainframe.java", "Desktop.getDesktop().browse(new URI(ctrmap.update.UpdateChecker.ISSUES_PAGE));", 1, Opens.CLICK},
		{"CtrmapMainframe.java", "progress.showDialog();", 1, Opens.PROGRESS},
		{"Ui.java", "JOptionPane.show", 7, Opens.SEAM},
		{"Ui.java", "chooser.show", 1, Opens.SEAM},
		{"Workspace.java", "progress.showDialog();", 1, Opens.PROGRESS},
		{"AreaLightingDialog.java", "JColorChooser.showDialog(", 1, Opens.CLICK},
		{"BuildingPaletteDialog.java", "dlg.setVisible(true);", 1, Opens.CLICK},
		{"GfEnvPicker.java", "dlg.setVisible(true);", 1, Opens.CLICK},
		{"LoadingDialog.java", "dlg.setVisible(true);", 1, Opens.WAITS},
		{"PaintForm.java", "\"Light color\"", 1, Opens.CLICK},
		{"TileMapPanel.java", "progress.showDialog();", 3, Opens.PROGRESS},
		{"ZoneLoadingPanel.java", "progress.showDialog();", 2, Opens.PROGRESS},
		{"Builder.java", "dlg.showDialog();", 1, Opens.PROGRESS},
		{"PokePickers.java", "dlg.setVisible(true);", 1, Opens.CLICK},
		{"SetupWizard.java", "w.setVisible(true);", 1, Opens.LAUNCH},
		{"SetupWizard.java", "finishBar.setVisible(true);", 1, Opens.NOT_A_WINDOW},
		{"UpdateUI.java", "dlg.setVisible(true);", 1, Opens.CLICK},
		{"UpdateUI.java", "Desktop.getDesktop().browse(new java.net.URI(url));", 1, Opens.CLICK},
	};

	/**
	 * Every window the editor's own source opens is in {@link #WINDOWS}, with how it comes up, and every one that
	 * comes up when nobody's click opened it waits for the owner. A new window is a line here or a failure: a pop up
	 * cannot arrive unclassified.
	 */
	private static void everyWindowTheEditorOpensIsClassified(File root) throws Exception {
		System.out.println("--- every window the editor opens is classified, and every one no click opens waits");
		java.util.Map<Object[], Integer> matched = new java.util.HashMap<>();
		List<String> unlisted = new java.util.ArrayList<>();
		List<String> unwaited = new java.util.ArrayList<>();
		int lines = 0, files = 0;
		java.util.Deque<File> walk = new java.util.ArrayDeque<>();
		walk.push(root);
		while (!walk.isEmpty()) {
			File d = walk.pop();
			File[] in = d.listFiles();
			if (in == null) {
				check(false, "CONTROL: the source folder " + d + " can be listed");
				continue;
			}
			for (File f : in) {
				if (f.isDirectory() && !f.getName().equals("tests")) {
					walk.push(f);
				} else if (f.getName().endsWith(".java")) {
					files++;
					String[] src = SourceSeamTest.stripComments(DialogSeamTest.read(f)).split("\n", -1);
					for (int i = 0; i < src.length; i++) {
						if (!WINDOW_LINE.matcher(src[i]).find()) {
							continue;
						}
						lines++;
						Object[] entry = null;
						for (Object[] e : WINDOWS) {
							if (e[0].equals(f.getName()) && src[i].contains((String) e[1])) {
								entry = e;
								break;
							}
						}
						if (entry == null) {
							unlisted.add(f.getName() + ":" + (i + 1) + " " + src[i].trim());
							continue;
						}
						matched.merge(entry, 1, Integer::sum);
						boolean waits = false;
						for (int j = Math.max(0, i - 8); j < i; j++) {
							waits |= src[j].contains("untilInUse();");
						}
						if (entry[3] == Opens.WAITS && !waits) {
							unwaited.add(f.getName() + ":" + (i + 1) + " " + src[i].trim());
						}
					}
				}
			}
		}
		List<String> stale = new java.util.ArrayList<>();
		int listed = 0;
		for (Object[] e : WINDOWS) {
			listed += (Integer) e[2];
			if (!((Integer) e[2]).equals(matched.getOrDefault(e, 0))) {
				stale.add(e[0] + " " + e[1] + ": " + matched.getOrDefault(e, 0) + " line(s), " + e[2] + " listed");
			}
		}
		check(files > 250 && lines == listed, "CONTROL: " + files + " production sources walked, " + lines
				+ " window-opening line(s) found, " + listed + " listed");
		check(unlisted.isEmpty(), "every window the editor opens is classified - not: " + unlisted);
		check(stale.isEmpty(), "and every classified window is still there, as many times as listed - not: " + stale);
		check(unwaited.isEmpty(), "and every window nobody's click opens waits for the owner just before it opens - not: "
				+ unwaited);
	}

	// ------------------------------------------------------------ the harness

	/** A shown frame, focusable or not - one that is not can show and never be the active window. */
	private static JFrame frame(String title, boolean focusable) throws Exception {
		JFrame[] f = {null};
		SwingUtilities.invokeAndWait(() -> {
			f[0] = new JFrame(title);
			f[0].setFocusableWindowState(focusable);
			f[0].setSize(240, 120);
			f[0].setVisible(true);
		});
		return f[0];
	}

	/** A shown frame in the given extended state, as the editor shows its own. */
	private static JFrame stated(String title, int state) throws Exception {
		JFrame[] f = {null};
		SwingUtilities.invokeAndWait(() -> {
			f[0] = new JFrame(title);
			f[0].setSize(240, 120);
			f[0].setExtendedState(state);
			f[0].setVisible(true);
		});
		return f[0];
	}

	/** Asks a real dialog through Ui on the event thread; the flag says when the question has returned. */
	private static AtomicBoolean ask(String title) {
		AtomicBoolean back = new AtomicBoolean();
		SwingUtilities.invokeLater(() -> {
			Ui.message(null, "a question nobody's click asked", title, JOptionPane.INFORMATION_MESSAGE);
			back.set(true);
		});
		return back;
	}

	/** The showing dialog titled {@code title}, waited for up to {@code ms}, or null. */
	private static Dialog shown(String title, long ms) throws Exception {
		long until = System.currentTimeMillis() + ms;
		do {
			Dialog[] found = {null};
			SwingUtilities.invokeAndWait(() -> {
				for (Window w : Window.getWindows()) {
					if (w instanceof Dialog && w.isShowing() && title.equals(((Dialog) w).getTitle())) {
						found[0] = (Dialog) w;
					}
				}
			});
			if (found[0] != null) {
				return found[0];
			}
			Thread.sleep(50);
		} while (System.currentTimeMillis() < until);
		return null;
	}

	/** Closes a dialog the harness found, and waits for its question to return. */
	private static void close(Dialog d, AtomicBoolean back) throws Exception {
		if (d != null) {
			SwingUtilities.invokeLater(d::dispose);
			check(becomes(back::get, 3000), "CONTROL: the question returns once its dialog is closed");
		}
	}

	private static void dispose(JFrame f) throws Exception {
		SwingUtilities.invokeAndWait(f::dispose);
	}

	/** Whether any window of this program shows - asked on the event thread, where {@link #becomes} asks it. */
	private static boolean anyShowing() {
		for (Window w : Window.getWindows()) {
			if (w.isShowing()) {
				return true;
			}
		}
		return false;
	}

	private static boolean becomes(java.util.function.BooleanSupplier what, long ms) throws Exception {
		long until = System.currentTimeMillis() + ms;
		do {
			boolean[] now = {false};
			SwingUtilities.invokeAndWait(() -> now[0] = what.getAsBoolean());
			if (now[0]) {
				return true;
			}
			Thread.sleep(50);
		} while (System.currentTimeMillis() < until);
		return false;
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
