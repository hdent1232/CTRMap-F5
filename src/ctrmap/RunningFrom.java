package ctrmap;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * THE EDITOR SAYS IT IS RUNNING OUT OF build/classes, so nothing deletes them under it.
 *
 * <p>build.ps1 starts by deleting build/classes, and a battery already declared itself with a lock so a
 * build would not do that under it. The editor did not: run from this checkout (run.bat), it loads its
 * classes from that folder as it needs them, and a rebuild started while it was open - a plant drive
 * rebuilt after every plant - pulls them out from under it. On 2026-10-11 the owner lost a window and
 * every edit in it while drives were both rebuilding this tree and opening suites on their screen; the
 * screen half is tools/guard/hidden_desktop.py, and this is the other half.
 *
 * <p>While it runs from a {@code build/classes} folder the editor writes {@code build/.editor-running}
 * naming its process, as the battery's lock does, and removes it when it exits; tools/guard/battery_lock.py
 * {@code --classes} reads it, and every build asks that first. A lock left by an editor that died names a
 * process that is gone, and holds nothing. Run from a released jar there are no such classes to protect,
 * and nothing is written.
 *
 * <p>Not: {@link ErrorSink} - it is installed at the same moment and keeps the errors nobody caught; it
 * holds no word about where the program runs from, and a build cannot read a log to know it is running.
 */
public final class RunningFrom {

	private RunningFrom() {
	}

	/** The lock's name beside {@code build/classes}. */
	public static final String LOCK = ".editor-running";

	/** Declares this editor in the build folder it runs from; the lock written, or null when it runs from none. */
	public static File declare() {
		try {
			java.net.URL where = RunningFrom.class.getProtectionDomain().getCodeSource().getLocation();
			return declare(new File(where.toURI()));
		} catch (Exception notAFolder) {
			//a jar, or a location that is no file at all: there is no build/classes here to keep
			return null;
		}
	}

	/** Declares an editor running out of {@code classes}, when that is a {@code build/classes} folder. */
	@ctrmap.util.NotAnEdit("it writes the editor's own note that it runs from these classes, so a build does not delete"
		+ " them under its window: the program's file, never the user's")
	public static File declare(File classes) {
		File build = classes.getParentFile();
		if (!classes.isDirectory() || !"classes".equals(classes.getName()) || build == null
				|| !"build".equals(build.getName())) {
			return null;
		}
		File lock = new File(build, LOCK);
		try {
			ctrmap.util.Bytes.replaceFile(lock, ("started " + java.time.Instant.now() + "\npid=" + pid()
					+ "\nwhat=the editor\n").getBytes(StandardCharsets.UTF_8));
		} catch (IOException cannotSay) {
			//said where it can be: with no lock, a build is free to delete the classes under this window
			System.err.println("CTRMap could not write " + lock + ", so a build here will not know the editor"
					+ " is running from these classes: " + ctrmap.util.Bytes.reason(cannotSay));
			return null;
		}
		lock.deleteOnExit();
		return lock;
	}

	/** This JVM's process id - Java 8 has no ProcessHandle, and the runtime's name is "pid@host". */
	static String pid() {
		String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
		int at = name.indexOf('@');
		return at > 0 ? name.substring(0, at) : name;
	}
}
