package ctrmap;

import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One change to the workspace that writes several files: all of it happens, or
 * none of it does.
 *
 * <p>THE WORKSPACE HAD NO UNIT OF WORK (F227). Fourteen user-facing operations -
 * fork geometry, resize, interior wiring, area forks, the open-time repair, zone
 * append, clone, facility setup, blank canvas, the painter's Apply, Connect
 * zones, Rename zone - each wrote its files one after another, and a failure
 * part way left whatever it had reached staged for the next pack: a matrix
 * nothing points to, a zone header and master row that disagree, a one-way door.
 * Five features had each built a private rollback of their own, every one
 * different, and every new operation started non-atomic by default.
 *
 * <p>HOW IT WORKS. An operation opens a unit and commits it when everything is
 * written:
 *
 * <pre>
 * try (WorkspaceUnit unit = WorkspaceUnit.begin("Fork geometry")) {
 *     ... every write ...
 *     unit.commit();
 * }
 * </pre>
 *
 * While a unit is open on this thread, every workspace file it is about to
 * change is copied first - container writes report themselves through
 * {@link ctrmap.formats.GameFiles#aboutToWrite}, and direct writes go through
 * {@link #write} - and so are the list of edited files and the pending
 * compression overrides. A unit closed without {@link #commit} puts every one
 * of them back, newest first, and an operation's in-memory state can join
 * through {@link #onRollback}. A failure to put something back is not
 * swallowed: it is attached to the operation's own exception, and {@link
 * ctrmap.util.Bytes#reason} says it.
 *
 * <p>NESTED UNITS JOIN THE OUTERMOST. An operation that calls another (the
 * append forks geometry and text) is one unit; only the outermost commit or
 * rollback acts.
 *
 * <p>Not: {@code GeoEditForm.Snapshot} - the nearest of the five private rollbacks, and it
 * holds one editor's three in-memory layers, never a file on disk; the forkers write files
 * and cannot reach a form's private class, which is how each feature came to build its own.
 */
public final class WorkspaceUnit implements AutoCloseable {

	private static final ThreadLocal<WorkspaceUnit> OPEN = new ThreadLocal<>();

	private final String name;
	/** True for a unit begun while another was open: it acts through that one. */
	private final boolean joined;
	private final WorkspaceSession session;
	/** Each file's bytes before its first write, or null for a file that did not exist. */
	private final Map<File, byte[]> before = new LinkedHashMap<>();
	private final List<String> editsBefore;
	private final EnumMap<ArchiveType, Map<Integer, Boolean>> pendingBefore;
	private final List<Runnable> undo = new ArrayList<>();
	private boolean committed;
	/** For a joined unit: whether its own close found it committed. */
	private boolean closed;

	private WorkspaceUnit(String name, boolean joined, WorkspaceSession session) {
		this.name = name;
		this.joined = joined;
		this.session = session;
		this.editsBefore = session == null || joined ? null : new ArrayList<>(session.persistPaths());
		this.pendingBefore = session == null || joined ? null : session.pendingCompressionSnapshot();
	}

	/**
	 * Opens a unit on this thread over {@code session}'s workspace, or joins the one already open.
	 * The session is HANDED IN, never fetched from the global: an operation already holds the
	 * workspace it writes, and a unit keyed to whichever session is current would snapshot the
	 * edit list of a game the operation is not writing.
	 */
	public static WorkspaceUnit begin(String name, WorkspaceSession session) {
		WorkspaceUnit outer = OPEN.get();
		if (outer != null) {
			return new WorkspaceUnit(name, true, outer.session);
		}
		WorkspaceUnit u = new WorkspaceUnit(name, false, session);
		OPEN.set(u);
		return u;
	}

	/** The unit open on this thread, or null. */
	public static WorkspaceUnit current() {
		return OPEN.get();
	}

	/**
	 * Tells the open unit, if any, that {@code f} is about to change, so it can
	 * be put back. Called before the first byte of every workspace write.
	 */
	public static void touching(File f) {
		WorkspaceUnit u = OPEN.get();
		if (u != null) {
			u.remember(f);
		}
	}

	/**
	 * Writes {@code data} to {@code f}, remembered first by the open unit if there is one - beside
	 * it and then moved over it, so a write that fails leaves the file as it was rather than cut
	 * short. With no unit open nothing else would put it back (census H.68: the text editor's
	 * failed save left a truncated entry already in the pack list).
	 */
	public static void write(File f, byte[] data) throws IOException {
		touching(f);
		ctrmap.util.Bytes.replaceFile(f, data);
	}

	/** Copies {@code from} over {@code to}, remembered first by the open unit if there is one. */
	public static void copy(File from, File to) throws IOException {
		touching(to);
		Files.copy(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	/** Something to undo in memory if the open unit rolls back; nothing happens with no unit open. */
	public static void onRollback(Runnable r) {
		WorkspaceUnit u = OPEN.get();
		if (u != null) {
			u.undo.add(r);
		}
	}

	private void remember(File f) {
		File key = f.getAbsoluteFile();
		if (before.containsKey(key)) {
			return;
		}
		try {
			before.put(key, key.exists() ? Files.readAllBytes(key.toPath()) : null);
		} catch (IOException ex) {
			//a file this unit cannot copy is a file it cannot put back: refuse the write
			//rather than let it happen unprotected
			throw new IllegalStateException(name + ": " + key.getName() + " could not be read before it is"
					+ " changed, so it could not be put back if the rest failed - nothing more was written: "
					+ ctrmap.util.Bytes.reason(ex), ex);
		}
	}

	/** Everything this unit wrote is kept. */
	public void commit() {
		committed = true;
	}

	/**
	 * Commits, and keeps what it takes to put it all back later - for an operation the user
	 * can take back from the screen. The Extras mass edits rewrote every area or every zone
	 * header and said "It cannot be undone from within CTRMap" (F100), against the rule that
	 * every edit can be.
	 *
	 * <p>What is kept is each touched file's bytes from before, a digest of what this unit left
	 * in it, and the operation's in-memory undos. Only the outermost unit can be kept: a joined
	 * one does not own the files it touched.
	 */
	public Kept commitKeeping() {
		if (joined) {
			throw new IllegalStateException(name + " is part of a larger change, and only that one can be taken back");
		}
		commit();
		Map<File, byte[]> written = new LinkedHashMap<>();
		for (File f : before.keySet()) {
			written.put(f, digest(f));
		}
		return new Kept(name, session, new LinkedHashMap<>(before), written, new ArrayList<>(undo));
	}

	/** SHA-256 of a file's bytes, or null for a file that is not there. */
	static byte[] digest(File f) {
		if (!f.exists()) {
			return null;
		}
		try {
			return java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath()));
		} catch (IOException | java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(f.getName() + " could not be read back: " + ctrmap.util.Bytes.reason(ex), ex);
		}
	}

	/** A committed unit that can still be taken back, while nothing it wrote has changed since. */
	public static final class Kept {

		private final String name;
		private final WorkspaceSession session;
		private final Map<File, byte[]> before;
		private final Map<File, byte[]> written;
		private final List<Runnable> inMemory;

		Kept(String name, WorkspaceSession session, Map<File, byte[]> before, Map<File, byte[]> written,
				List<Runnable> inMemory) {
			this.name = name;
			this.session = session;
			this.before = before;
			this.written = written;
			this.inMemory = inMemory;
		}

		/** What the operation was called, for the button and the question. */
		public String name() {
			return name;
		}

		/**
		 * Puts every file the operation wrote back as it was, as one unit, and its in-memory state
		 * with them - or, when any of those files has changed since, refuses and names them, having
		 * put back nothing: taking back an edit is not taking back the edits made after it.
		 */
		public void revert() throws IOException {
			List<String> changed = new ArrayList<>();
			for (Map.Entry<File, byte[]> e : written.entrySet()) {
				if (!java.util.Arrays.equals(e.getValue(), digest(e.getKey()))) {
					changed.add(e.getKey().getName());
				}
			}
			if (!changed.isEmpty()) {
				throw new IllegalStateException(changed.size() + " file(s) " + name + " wrote have changed since, so"
						+ " nothing was put back: " + changed);
			}
			try (WorkspaceUnit unit = WorkspaceUnit.begin("Undo " + name, session)) {
				for (Map.Entry<File, byte[]> e : before.entrySet()) {
					if (e.getValue() == null) {
						touching(e.getKey());
						Files.deleteIfExists(e.getKey().toPath());
					} else {
						write(e.getKey(), e.getValue());
					}
				}
				unit.commit();
			}
			for (int i = inMemory.size() - 1; i >= 0; i--) {
				inMemory.get(i).run();
			}
		}
	}

	/** The files this unit has changed so far, in the order it first touched them. */
	public List<File> touched() {
		WorkspaceUnit u = joined ? OPEN.get() : this;
		return u == null ? Collections.<File>emptyList() : new ArrayList<>(u.before.keySet());
	}

	/**
	 * Ends the unit. Unless it committed, every file it touched is put back as
	 * it was, newest first, along with the list of edits and the pending
	 * compression overrides, and the in-memory undos run. A joined unit that
	 * did not commit leaves the outermost to decide - its exception is on its
	 * way there.
	 *
	 * @throws IllegalStateException when something could not be put back; it
	 *         names what, and is attached to the operation's own exception by
	 *         try-with-resources
	 */
	@Override
	public void close() {
		if (joined || closed) {
			closed = true;
			return;
		}
		closed = true;
		OPEN.remove();
		if (committed) {
			return;
		}
		List<String> failed = new ArrayList<>();
		List<File> files = new ArrayList<>(before.keySet());
		Collections.reverse(files);
		for (File f : files) {
			byte[] was = before.get(f);
			try {
				if (was == null) {
					Files.deleteIfExists(f.toPath());
				} else {
					Files.write(f.toPath(), was);
				}
			} catch (IOException ex) {
				failed.add(f.getName() + " (" + ctrmap.util.Bytes.reason(ex) + ")");
			}
		}
		if (session != null) {
			List<String> edits = session.persistPaths();
			edits.clear();
			edits.addAll(editsBefore);
			session.restorePendingCompression(pendingBefore);
		}
		for (int i = undo.size() - 1; i >= 0; i--) {
			try {
				undo.get(i).run();
			} catch (RuntimeException ex) {
				failed.add("an in-memory undo (" + ctrmap.util.Bytes.reason(ex) + ")");
			}
		}
		if (!failed.isEmpty()) {
			throw new IllegalStateException(name + " did not finish, and these could not be put back as"
					+ " they were: " + String.join(", ", failed));
		}
	}
}
