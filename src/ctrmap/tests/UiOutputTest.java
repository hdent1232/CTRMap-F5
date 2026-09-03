package ctrmap.tests;

import ctrmap.Ui;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.List;
import javax.swing.JOptionPane;

/**
 * {@link Ui} is the seam every other guard asserts through, so nothing was
 * asserting it.
 *
 * <p>Every "the user was told" check in this battery works by recording what
 * Ui was handed. That makes Ui itself the one place where deleting the line
 * that actually says something costs nothing: a suite asking "was the message
 * recorded?" is answered by the sink, not by the two lines underneath it that
 * do the saying. Both of them were measured as unguarded, and each is a whole
 * output path:
 *
 * <ul>
 * <li>The dialogs-off path prints the message. That is where every message
 *     produced by a battery run goes - a suite that trips a warning it did not
 *     wrap in {@code Ui.record()} is visible in the log because of that one
 *     line. Delete it and the program says nothing at all under test, which is
 *     precisely the condition this whole campaign exists to remove.</li>
 * <li>The dialogs-on path opens the dialog. That is the shipped application's
 *     ONLY way of telling the user anything. Delete it and every error in
 *     CTRMap becomes silent again, while every suite in the battery still
 *     passes, because no suite runs with dialogs on.</li>
 * </ul>
 *
 * <p>The second is checked by turning dialogs on with no display: opening a
 * real dialog then throws HeadlessException, and a Ui that has stopped opening
 * one returns quietly instead. Headless mode is forced here rather than
 * assumed - the battery runner passes no flags, and a suite that put a modal
 * window on the owner's desktop would block the run until somebody clicked it.
 * That has happened here before.
 *
 * Usage: java ctrmap.tests.UiOutputTest
 */
public class UiOutputTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		//before anything can touch AWT: the dialog check below must be able to
		//throw rather than open a window, whatever the runner was invoked with
		System.setProperty("java.awt.headless", "true");

		aSinkTakesPrecedence();
		withoutADialogTheMessageIsStillSaid();
		withDialogsOnTheDialogIsOpened();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** What every other suite depends on: a recorded run sees what was said. */
	static void aSinkTakesPrecedence() {
		String printed;
		List<String> said = Ui.record();
		try {
			printed = capture(() -> Ui.error(null, "the archive is held open", "Pack Workspace"));
		} finally {
			Ui.stopRecording();
		}
		check(said.size() == 1 && said.get(0).contains("the archive is held open"),
				"a recording test is handed the message: " + said);
		check(printed.isEmpty(), "and it is not also printed, so a suite's own output stays readable");
	}

	/**
	 * Dialogs off is how every suite runs. The message must still land
	 * somewhere a person reading the battery log can see it.
	 */
	static void withoutADialogTheMessageIsStillSaid() {
		String printed = capture(() -> Ui.error(null, "zone 12 was not saved\nnothing was written",
				"Open MapMatrix"));
		check(printed.contains("Open MapMatrix"), "with dialogs off the title is still said: " + printed.trim());
		check(printed.contains("zone 12 was not saved"), "and so is the message");
		check(printed.contains("zone 12 was not saved | nothing was written"),
				"on one line, so it cannot be mistaken for unrelated output");
	}

	/**
	 * Dialogs on is how the shipped application runs, and the dialog is the
	 * only thing the user ever sees. With no display, opening one throws; a Ui
	 * that no longer opens one returns as if it had said something.
	 */
	static void withDialogsOnTheDialogIsOpened() throws Exception {
		if (!java.awt.GraphicsEnvironment.isHeadless()) {
			System.out.println("  skip: a display is attached, so a real dialog would open and block");
			return;
		}
		//Ui.enableDialogs() is the application's to call and only the
		//application may name it (BatteryHygieneTest enforces that), so the
		//shipped state is reproduced through the field itself and put back.
		Field enabled = Ui.class.getDeclaredField("dialogsEnabled");
		enabled.setAccessible(true);
		enabled.set(null, Boolean.TRUE);
		try {
			Ui.message(null, "the emulator is holding a0/1/3 open", "Pack Workspace",
					JOptionPane.ERROR_MESSAGE);
			check(false, "with dialogs on the message reaches a real dialog (it returned instead:"
					+ " the application would say nothing at all)");
		} catch (java.awt.HeadlessException ex) {
			check(true, "with dialogs on the message reaches a real dialog");
		} finally {
			enabled.set(null, Boolean.FALSE);
		}
	}

	/** Runs the body with System.out diverted, and returns what it printed. */
	static String capture(Runnable body) {
		PrintStream real = System.out;
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		try {
			System.setOut(new PrintStream(buf, true));
			body.run();
		} finally {
			System.setOut(real);
		}
		return buf.toString();
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
