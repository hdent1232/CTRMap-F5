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
		theSinkBeforeItIsInstalled(dir);
		System.setProperty(ErrorSink.ROOT_PROPERTY, dir.getAbsolutePath());
		File log = ErrorSink.install();
		check(log != null && log.getParentFile().equals(dir.getAbsoluteFile()),
				"the sink writes under the folder it was pointed at: " + log);
		check(ErrorSink.install() == log, "and installing it again is the same sink");

		aListenerThatThrowsIsWrittenAndSaid(log);
		aLoggedFailureIsWritten(log);
		aPrintedStackTraceIsWritten(log);
		theApplicationInstallsItFirst(new File(args.length > 0 ? args[0] : "src"));
		theEditorSaysItRunsFromTheClasses(new File(args.length > 0 ? args[0] : "src"));

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/**
	 * What the sink does before, and on the way to, being installed - each line the final sweep of 2026-10-12
	 * negated or dropped with every suite green: a flush with nothing installed, a log folder that cannot be made,
	 * the folder chosen when nothing overrides it, and the trailing carriage return of a line copied from stderr.
	 * Asked before {@link ErrorSink#install()}, which sets process-wide state for the rest of the suite.
	 */
	static void theSinkBeforeItIsInstalled(File scratch) throws Exception {
		System.out.println("--- the sink before it is installed: a flush, a folder it cannot make, where it writes");
		try {
			ErrorSink.flush();
			check(true, "a flush with no sink installed writes nothing and throws nothing");
		} catch (RuntimeException threw) {
			check(false, "a flush with no sink installed writes nothing and throws nothing: " + threw);
		}
		File notAFolder = new File(scratch, "a-file-not-a-folder");
		Files.write(notAFolder.toPath(), new byte[]{1});
		System.setProperty(ErrorSink.ROOT_PROPERTY, new File(notAFolder, "logs").getAbsolutePath());
		String refused = null;
		try {
			ErrorSink.install();
		} catch (java.io.IOException expected) {
			refused = expected.getMessage();
		}
		check(refused != null && refused.contains("could not be made") && refused.contains("a-file-not-a-folder"),
				"a log folder that cannot be made is refused in its own words, naming it: " + refused);
		System.clearProperty(ErrorSink.ROOT_PROPERTY);
		String local = System.getenv("LOCALAPPDATA");
		File expected = new File(local == null || local.isEmpty() ? System.getProperty("user.home") : local,
				"CTRMap" + File.separator + "logs");
		check(ErrorSink.root().equals(expected), "with nothing overriding it, the log goes under LOCALAPPDATA ("
				+ (local == null ? "not set here, so the home folder" : local) + "): " + ErrorSink.root());
		final List<String> logged = new java.util.ArrayList<>();
		Logger to = Logger.getAnonymousLogger();
		to.setUseParentHandlers(false);
		to.addHandler(new java.util.logging.Handler() {
			@Override
			public void publish(java.util.logging.LogRecord record) {
				logged.add(record.getMessage());
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		});
		Class<?> copy = Class.forName("ctrmap.ErrorSink$CopyToLog");
		java.lang.reflect.Constructor<?> make = copy.getDeclaredConstructor(java.io.PrintStream.class, Logger.class);
		make.setAccessible(true);
		java.io.OutputStream lines = (java.io.OutputStream) make.newInstance(
				new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true, "UTF-8"), to);
		lines.write("a windows line\r\na unix line\n".getBytes(StandardCharsets.UTF_8));
		check(logged.size() == 2 && "a windows line".equals(logged.get(0)) && "a unix line".equals(logged.get(1)),
				"a line copied from stderr loses its carriage return, and only that: " + logged);
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

	/**
	 * The editor declares itself while it runs out of a build/classes folder, so no build deletes them under
	 * an open window (2026-10-11, see RunningFrom): a build/classes folder gets build/.editor-running naming
	 * this process; any other folder gets nothing; and the application does it at the start of main.
	 */
	static void theEditorSaysItRunsFromTheClasses(File src) throws Exception {
		System.out.println("--- the editor says it runs from build/classes, so no build deletes them under it");
		File root = Scratch.dir("ctrmap_running_from");
		File classes = new File(new File(root, "build"), "classes");
		check(classes.mkdirs(), "control: a scratch build/classes folder");
		File lock = ctrmap.RunningFrom.declare(classes);
		String pid = java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
		String said = lock == null ? "" : new String(java.nio.file.Files.readAllBytes(lock.toPath()),
				java.nio.charset.StandardCharsets.UTF_8);
		check(lock != null && lock.equals(new File(new File(root, "build"), ctrmap.RunningFrom.LOCK))
				&& said.contains("pid=" + pid + "\n"),
				"run from build/classes, the editor writes build/.editor-running naming this process (" + lock + ")");
		File other = new File(root, "elsewhere");
		check(other.mkdirs() && ctrmap.RunningFrom.declare(other) == null,
				"and run from anything else - a jar's folder - it writes nothing");
		String main = new String(java.nio.file.Files.readAllBytes(new File(src, "ctrmap/CtrmapMainframe.java").toPath()),
				java.nio.charset.StandardCharsets.UTF_8);
		int at = main.indexOf("public static void main(String[] args)");
		int first = at < 0 ? -1 : main.indexOf("RunningFrom.declare();", at);
		check(at >= 0 && first > at && first < main.indexOf("ErrorSink.install();", at),
				"and the application declares itself at the start of main, before anything can fail");
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok: " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}
}
