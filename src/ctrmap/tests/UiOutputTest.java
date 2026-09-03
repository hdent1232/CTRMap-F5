package ctrmap.tests;

import ctrmap.Ui;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.swing.JOptionPane;

/**
 * {@link Ui} is the seam every other guard asserts through, so nothing was
 * asserting it.
 *
 * <p>Every "the user was told" check in this battery works by recording what Ui
 * was handed. That makes Ui itself the one place where deleting the line that
 * actually says something costs nothing: a suite asking "was the message
 * recorded?" is answered by the sink, not by the two lines underneath it that
 * do the saying. Both were measured as unguarded, and each is a whole output
 * path:
 *
 * <ul>
 * <li>The dialogs-off path prints the message. That is where every message a
 *     battery run produces goes - a suite that trips a warning it did not wrap
 *     in {@code Ui.record()} is visible in the log because of that one line.
 *     Delete it and the program says nothing at all under test, which is
 *     exactly the condition this campaign exists to remove.</li>
 * <li>The dialogs-on path opens the dialog. That is the shipped application's
 *     ONLY way of telling the user anything. Delete it and every error in
 *     CTRMap goes silent again while the whole battery still passes, because no
 *     suite runs with dialogs on.</li>
 * </ul>
 *
 * <p>What the program prints is checked by RUNNING it - this suite re-launches
 * itself in a child JVM and reads that process's output - rather than by
 * swapping this JVM's System.out for a buffer. SourceSeamTest forbids the
 * latter anywhere in the repository, and it is right to: the script editor once
 * hijacked both streams around a call with no finally, and every diagnostic in
 * the application afterwards went into a stale text area. Reading a child's
 * output captures nothing.
 *
 * <p>The dialog path is checked by turning dialogs on with no display: opening
 * a real dialog then throws HeadlessException, and a Ui that has stopped
 * opening one returns quietly instead. Headless is forced here rather than
 * assumed - the battery runner passes no flags, and a suite that put a modal
 * window on the owner's desktop would block the run until somebody clicked it.
 * That has happened here before.
 *
 * Usage: java ctrmap.tests.UiOutputTest
 */
public class UiOutputTest {

	/** Child mode: say one thing the way the program does, and print nothing else. */
	private static final String SAY = "--say";
	/** Child mode: say one thing while a test is recording, and print nothing else. */
	private static final String SAY_RECORDED = "--say-recorded";

	private static final String TITLE = "Open MapMatrix";
	private static final String TEXT = "zone 12 was not saved\nnothing was written";

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		//before anything can touch AWT: the dialog check below must be able to
		//throw rather than open a window, whatever the runner was invoked with
		System.setProperty("java.awt.headless", "true");

		if (args.length > 0 && SAY.equals(args[0])) {
			Ui.error(null, TEXT, TITLE);
			return;
		}
		if (args.length > 0 && SAY_RECORDED.equals(args[0])) {
			Ui.record();
			try {
				Ui.error(null, TEXT, TITLE);
			} finally {
				Ui.stopRecording();
			}
			return;
		}

		aSinkIsHandedTheMessage();
		withoutADialogTheMessageIsStillSaid();
		withDialogsOnTheDialogIsOpened();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** What every other suite depends on: a recording run sees what was said. */
	static void aSinkIsHandedTheMessage() {
		List<String> said = Ui.record();
		try {
			Ui.error(null, "the archive is held open", "Pack Workspace");
		} finally {
			Ui.stopRecording();
		}
		check(said.size() == 1 && said.get(0).contains("the archive is held open"),
				"a recording test is handed the message: " + said);
	}

	/**
	 * Dialogs off is how every suite runs. The message must still land
	 * somewhere a person reading the battery log can see it - and must NOT be
	 * printed as well when a test is recording, or every suite's output fills
	 * with the warnings it deliberately provoked.
	 */
	static void withoutADialogTheMessageIsStillSaid() throws Exception {
		String printed = run(SAY);
		check(printed.contains(TITLE), "with dialogs off the title is still said: " + printed.trim());
		check(printed.contains("zone 12 was not saved"), "and so is the message");
		check(printed.contains("zone 12 was not saved | nothing was written"),
				"on one line, so it cannot be mistaken for unrelated output");

		String quiet = run(SAY_RECORDED);
		check(quiet.trim().isEmpty(), "while a test is recording, nothing is printed: " + quiet.trim());
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

	/** Runs this class again in a child JVM and returns everything it printed. */
	static String run(String mode) throws Exception {
		File java = new File(System.getProperty("java.home"), "bin/java.exe");
		if (!java.isFile()) {
			java = new File(System.getProperty("java.home"), "bin/java");
		}
		ProcessBuilder pb = new ProcessBuilder(java.getPath(), "-cp",
				System.getProperty("java.class.path"), UiOutputTest.class.getName(), mode);
		pb.redirectErrorStream(false);
		Process p = pb.start();
		StringBuilder out = new StringBuilder();
		try (BufferedReader r = new BufferedReader(
				new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				out.append(line).append('\n');
			}
		}
		int code = p.waitFor();
		check(code == 0, "the child that says it exits cleanly (" + mode + " -> " + code + ")");
		return out.toString();
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
