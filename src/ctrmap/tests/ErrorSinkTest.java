package ctrmap.tests;

import ctrmap.ErrorSink;
import ctrmap.Ui;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;

/**
 * An error nobody caught reaches a file and a person, in the build with no console (F219).
 *
 * <p>WHY THIS SUITE EXISTS. The Windows launcher has no console, and nothing installed an
 * uncaught exception handler or a log file: 76 logger sites, 38 stack traces printed to stderr,
 * and every exception escaping a listener on the event thread went to a console that was not
 * there. This throws from a listener the way a broken button does, and reads the log back.
 *
 * <p>ORDER: needs a display only as much as Swing's event thread does, and no dump. The log is
 * written under {@link Scratch} - {@link ErrorSink#ROOT_PROPERTY} - never into the user's own.
 * It installs process-wide handlers, so it runs in its own JVM, as every suite here does.
 *
 * Usage: java ctrmap.tests.ErrorSinkTest [src-root]
 */
public class ErrorSinkTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dir = Scratch.dir("ctrmap_errorsink");
		System.setProperty(ErrorSink.ROOT_PROPERTY, dir.getAbsolutePath());
		File log = ErrorSink.install();
		check(log != null && log.getParentFile().equals(dir.getAbsoluteFile()),
				"the sink writes under the folder it was pointed at: " + log);
		check(ErrorSink.install() == log, "and installing it again is the same sink");

		aListenerThatThrowsIsWrittenAndSaid(log);
		aLoggedFailureIsWritten(log);
		aPrintedStackTraceIsWritten(log);
		theApplicationInstallsItFirst(new File(args.length > 0 ? args[0] : "src"));

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	static String read(File log) throws Exception {
		ErrorSink.flush();
		return log.isFile() ? new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8) : "";
	}

	static void aListenerThatThrowsIsWrittenAndSaid(File log) throws Exception {
		System.out.println("--- an exception escaping a listener on the event thread is written and said");
		List<String> said = Ui.record(javax.swing.JOptionPane.OK_OPTION);
		try {
			SwingUtilities.invokeLater(() -> {
				throw new IllegalStateException("planted: a listener that throws");
			});
			//the event thread dies with it and a new one takes over; these wait for both, and for
			//the dialog the handler hands back to the event thread
			for (int i = 0; i < 3; i++) {
				SwingUtilities.invokeAndWait(() -> { });
			}
		} finally {
			Ui.stopRecording();
		}
		String held = read(log);
		check(held.contains("planted: a listener that throws"), "the log holds the exception");
		check(held.contains("ErrorSinkTest"), "and where it was thrown, not only its message");
		check(said.size() == 1 && said.get(0).contains("planted: a listener that throws")
				&& said.get(0).contains(log.getName()),
				"and a person is told, with the file named: " + said);
	}

	static void aLoggedFailureIsWritten(File log) throws Exception {
		System.out.println("--- every logger in the program reaches the file without being edited");
		Logger.getLogger("ctrmap.somewhere.Else").log(Level.SEVERE, "planted: a logged failure",
				new java.io.IOException("planted: its cause"));
		String held = read(log);
		check(held.contains("planted: a logged failure") && held.contains("planted: its cause"),
				"a failure logged anywhere is in the file, with its cause");
	}

	static void aPrintedStackTraceIsWritten(File log) throws Exception {
		System.out.println("--- a stack trace printed to stderr is kept too");
		new RuntimeException("planted: a printed stack trace").printStackTrace();
		System.err.flush();
		String held = read(log);
		check(held.contains("planted: a printed stack trace"), "a printed stack trace is in the file");
	}

	/**
	 * The point of action is the application's first line: installed anywhere later, everything
	 * before it - the look and feel, the updater, the window - fails to nobody.
	 */
	static void theApplicationInstallsItFirst(File src) throws Exception {
		System.out.println("--- the application installs it before anything can fail");
		File main = new File(src, "ctrmap/CtrmapMainframe.java");
		if (!main.isFile()) {
			check(false, "the application's source is there to read: " + main.getAbsolutePath());
			return;
		}
		String body = SourceSeamTest.stripComments(new String(Files.readAllBytes(main.toPath()), StandardCharsets.UTF_8));
		int start = body.indexOf("public static void main(String[] args)");
		int install = body.indexOf("ErrorSink.install()", start);
		int updater = body.indexOf("Updater.isApplyInvocation", start);
		int window = body.indexOf("createAndShowGUI();", start);
		check(start >= 0 && install > start && install < updater && install < window,
				"main installs the sink before the updater and the window (" + start + ", " + install + ", "
				+ updater + ", " + window + ")");
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok: " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}
}
