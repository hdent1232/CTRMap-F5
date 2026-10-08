package ctrmap;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javax.swing.SwingUtilities;

/**
 * Where an error goes when nobody is watching a console - one sink, installed before the first
 * window.
 *
 * <p>NO ERROR REACHED ANYONE (F219). The Windows build is a launcher with no console, and the
 * program had 76 logger sites, 38 that printed a stack trace or wrote to stderr, no uncaught
 * exception handler and no log file. Every one of them, and every exception escaping a listener
 * on the event thread - which Swing prints to stderr and carries on from - went to a console that
 * did not exist. A user saw a button do nothing.
 *
 * <p>WHAT IT DOES, all three pointed at one file in the user's own folder:
 * <ul>
 * <li>the root logger writes to it, so every logger site in the program reaches it without
 *     being edited - there is ONE handler, which is what makes this a startup question;</li>
 * <li>every exception nobody caught, on any thread - the event thread's included, which hands
 *     its uncaught exceptions to the default handler - is written there, and said in a dialog
 *     that names the file;</li>
 * <li>stderr is copied into it line by line, so a printed stack trace is kept too. Through a
 *     logger of its own rather than a second writer on the file: one handler writes the file.</li>
 * </ul>
 *
 * <p>Not: {@link Ui} - it is how a person is TOLD, and this calls it for that; it holds no file
 * and cannot see an exception nobody handed it. Nothing else in the tree wrote a log at all.
 */
public final class ErrorSink {

	/** The property a suite points the log folder at, so it never writes the user's real log. */
	public static final String ROOT_PROPERTY = "ctrmap.log.root";

	private static File logFile;
	private static FileHandler fileHandler;
	private static final AtomicBoolean SAYING = new AtomicBoolean();

	private ErrorSink() {
	}

	/** The folder the log is written in: beside the vault, under the user's local data. */
	public static File root() {
		String override = System.getProperty(ROOT_PROPERTY);
		if (override != null && !override.isEmpty()) {
			return new File(override);
		}
		String local = System.getenv("LOCALAPPDATA");
		if (local == null || local.isEmpty()) {
			local = System.getProperty("user.home");
		}
		return new File(local, "CTRMap" + File.separator + "logs");
	}

	/** The log file once installed, else null. */
	public static synchronized File logFile() {
		return logFile;
	}

	/**
	 * Installs the sink, once. Later calls return the same file.
	 *
	 * @return the log file
	 * @throws IOException when the folder cannot be written - nothing is installed then, and the
	 *         caller says so where it still can
	 */
	public static synchronized File install() throws IOException {
		if (logFile != null) {
			return logFile;
		}
		File dir = root();
		if (!dir.isDirectory() && !dir.mkdirs()) {
			throw new IOException("the log folder " + dir + " could not be made");
		}
		FileHandler handler = new FileHandler(new File(dir, "ctrmap.%g.log").getPath(), 1 << 20, 3, true);
		handler.setFormatter(new SimpleFormatter());
		handler.setEncoding(StandardCharsets.UTF_8.name());
		//the root logger's own console handler is made NOW, before stderr is replaced below, so it
		//keeps writing to the real stderr and never into the copy that feeds this file
		Logger root = Logger.getLogger("");
		root.getHandlers();
		root.addHandler(handler);

		Logger stderr = Logger.getLogger("ctrmap.stderr");
		stderr.setUseParentHandlers(false);
		stderr.addHandler(handler);
		//A TEE, NOT A CAPTURE: installed once at startup, before any window, and everything still
		//reaches the stream it went to before - the copy is what puts a printed trace in the log
		//file the shipped program's user can send (F219). What the seam rule forbids is the script
		//editor's old swap, which took the output away and never gave it back; SourceSeamTest
		//names this file as the one allowed redirect, with that reason.
		System.setErr(new PrintStream(new CopyToLog(System.err, stderr), true, StandardCharsets.UTF_8.name()));

		Thread.setDefaultUncaughtExceptionHandler(ErrorSink::uncaught);
		fileHandler = handler;
		logFile = new File(dir, "ctrmap.0.log");
		return logFile;
	}

	/** Writes everything held so far to the file - for a suite reading it back. */
	public static synchronized void flush() {
		if (fileHandler != null) {
			fileHandler.flush();
		}
	}

	/** What happens to an exception nobody caught: written to the log, and said. */
	static void uncaught(Thread thread, Throwable e) {
		Logger.getLogger("ctrmap").log(Level.SEVERE, "nothing caught this, on thread " + thread.getName(), e);
		flush();
		//ONE AT A TIME: a listener that throws on every repaint would otherwise stack a dialog per
		//frame. The log has every one of them.
		if (!SAYING.compareAndSet(false, true)) {
			return;
		}
		//ALWAYS LATER, on the event thread (F220) - from it too: whatever threw there has already
		//unwound, and a branch that ran the dialog in place on one thread and handed it on from
		//every other was the one shape EdtReachTest could not tell from a dialog off the thread
		SwingUtilities.invokeLater(() -> {
			try {
				Ui.error(null, "Something went wrong that this program did not handle:\n\n"
						+ ctrmap.util.Bytes.reason(e) + "\n\nWhat it was doing, in full, is in "
						+ (logFile() == null ? "the log" : logFile().getAbsolutePath())
						+ " - send that file with a report.", "CTRMap");
			} finally {
				SAYING.set(false);
			}
		});
	}

	/** Each line written to stderr, to the original stream and to a logger. */
	static final class CopyToLog extends OutputStream {
		private final PrintStream original;
		private final Logger to;
		private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();

		CopyToLog(PrintStream original, Logger to) {
			this.original = original;
			this.to = to;
		}

		@Override
		public synchronized void write(int b) {
			original.write(b);
			if (b == '\n') {
				emit();
			} else {
				line.write(b);
			}
		}

		@Override
		public synchronized void flush() {
			original.flush();
		}

		private void emit() {
			String text;
			try {
				text = line.toString(StandardCharsets.UTF_8.name());
			} catch (UnsupportedEncodingException cannot) {
				text = line.toString();
			}
			line.reset();
			if (text.endsWith("\r")) {
				text = text.substring(0, text.length() - 1);
			}
			to.log(Level.WARNING, text);
		}
	}

}
