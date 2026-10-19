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
 * through {@link #inMemory}. A failure to put something back is not
 * swallowed: it is attached to the operation's own exception, and {@link
 * ctrmap.util.Bytes#reason} says it.
 *
 * <p>NESTED UNITS JOIN THE OUTERMOST. An operation that calls another (the
 * append forks geometry and text) is one unit; only the outermost commit acts,
 * and its rollback puts back everything, the nested units' writes included. A
 * nested unit that does not finish puts back its OWN writes as it closes - each
 * file as it was when that unit began - so a caller that reports its failure and
 * carries on, as the painter's Apply does with a door wiring, never has the half
 * of it committed by the outer unit (F209c).
 *
 * <p>Not: {@code GeoEditForm.Snapshot} - the nearest of the five private rollbacks, and it
 * holds one editor's three in-memory layers, never a file on disk; the forkers write files
 * and cannot reach a form's private class, which is how each feature came to build its own.
 */
public final class WorkspaceUnit implements AutoCloseable {

	private static final ThreadLocal<WorkspaceUnit> OPEN = new ThreadLocal<>();
	/** The nested units open on this thread, innermost last: each copies what it is the first of them to touch. */
	private static final ThreadLocal<java.util.Deque<WorkspaceUnit>> NESTED =
			ThreadLocal.withInitial(java.util.ArrayDeque::new);

	private final String name;
	/** True for a unit begun while another was open: it acts through that one. */
	private final boolean joined;
	private final WorkspaceSession session;
	/** Each file's bytes before its first write, or null for a file that did not exist. */
	private final Map<File, byte[]> before = new LinkedHashMap<>();
	private final List<String> editsBefore;
	private final EnumMap<ArchiveType, Map<Integer, Boolean>> pendingBefore;
	/** The session's pack count when this unit began: a pack inside it carries it as surely as one after (F209b). */
	private final int packsBefore;
	private final List<Runnable> undo = new ArrayList<>();
	/** Beside each of {@link #undo}: what puts that change again, or null for one only a rollback undoes. */
	private final List<Runnable> again = new ArrayList<>();
	private boolean committed;
	/** For a joined unit: whether its own close found it committed. */
	private boolean closed;
	/** A nested unit's own copies: each file as it was when this unit began, or null for one not there then. */
	private final Map<File, byte[]> own = new LinkedHashMap<>();
	/** A nested unit's edit list and pending flags when it began, and how many in-memory changes there were then. */
	private List<String> ownEdits;
	private EnumMap<ArchiveType, Map<Integer, Boolean>> ownPending;
	private int ownMemory;

	private WorkspaceUnit(String name, boolean joined, WorkspaceSession session) {
		this.name = name;
		this.joined = joined;
		this.session = session;
		this.editsBefore = session == null || joined ? null : new ArrayList<>(session.persistPaths());
		this.pendingBefore = session == null || joined ? null : session.pendingCompressionSnapshot();
		this.packsBefore = session == null || joined ? 0 : session.packs();
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
			WorkspaceUnit nested = new WorkspaceUnit(name, true, outer.session);
			if (outer.session != null) {
				nested.ownEdits = new ArrayList<>(outer.session.persistPaths());
				nested.ownPending = outer.session.pendingCompressionSnapshot();
			}
			nested.ownMemory = outer.undo.size();
			NESTED.get().addLast(nested);
			return nested;
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
			for (WorkspaceUnit nested : NESTED.get()) {
				nested.rememberOwn(f);
			}
		}
	}

	/** A nested unit's copy of {@code f} as it is now, the first time it is touched while this unit is open. */
	private void rememberOwn(File f) {
		File key = f.getAbsoluteFile();
		if (own.containsKey(key)) {
			return;
		}
		try {
			own.put(key, key.exists() ? Files.readAllBytes(key.toPath()) : null);
		} catch (IOException ex) {
			throw new IllegalStateException(name + ": " + key.getName() + " could not be read before it is"
					+ " changed, so it could not be put back if the rest failed - nothing more was written: "
					+ ctrmap.util.Bytes.reason(ex), ex);
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

	/**
	 * An in-memory change the open unit puts back if it rolls back - and, kept to be taken back from the
	 * screen ({@link #commitKeeping}), puts back and puts again with its files (F209). Nothing happens with
	 * no unit open.
	 */
	public static void inMemory(Runnable back, Runnable again) {
		//BOTH, OR NOTHING JOINS (F209c): a change memory could be put back from but not put again from left Redo
		//putting the files again and memory as Undo left it - onRollback, which took one half, is gone
		if (back == null || again == null) {
			throw new IllegalArgumentException("an in-memory change must say both how it is put back and how it is"
					+ " put again");
		}
		WorkspaceUnit u = OPEN.get();
		if (u != null) {
			u.undo.add(back);
			u.again.add(again);
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
		Map<File, byte[]> after = new LinkedHashMap<>();
		for (File f : before.keySet()) {
			after.put(f, read(f));
		}
		List<String> listed = new ArrayList<>();
		List<String> unlisted = new ArrayList<>();
		if (session != null) {
			for (String p : session.persistPaths()) {
				if (!editsBefore.contains(p)) {
					listed.add(p);
				}
			}
			for (String p : editsBefore) {
				if (!session.persistPaths().contains(p)) {
					unlisted.add(p);
				}
			}
		}
		//THE FLAGS IT SET (F209c): each changed one's value before and after, null where there was none
		List<Object[]> flags = new ArrayList<>();
		if (session != null) {
			EnumMap<ArchiveType, Map<Integer, Boolean>> now = session.pendingCompressionSnapshot();
			java.util.Set<ArchiveType> types = new java.util.HashSet<>(pendingBefore.keySet());
			types.addAll(now.keySet());
			for (ArchiveType t : types) {
				Map<Integer, Boolean> was = pendingBefore.getOrDefault(t, java.util.Collections.<Integer, Boolean>emptyMap());
				Map<Integer, Boolean> is = now.getOrDefault(t, java.util.Collections.<Integer, Boolean>emptyMap());
				java.util.Set<Integer> entries = new java.util.HashSet<>(was.keySet());
				entries.addAll(is.keySet());
				for (Integer i : entries) {
					if (!java.util.Objects.equals(was.get(i), is.get(i))) {
						flags.add(new Object[]{t, i, was.get(i), is.get(i)});
					}
				}
			}
		}
		return new Kept(name, session, new LinkedHashMap<>(before), after, new ArrayList<>(undo), new ArrayList<>(again),
				listed, unlisted, flags, packsBefore);
	}

	/** A file's bytes, or null for a file that is not there; one that cannot be read throws, naming it. */
	static byte[] read(File f) {
		if (!f.exists()) {
			return null;
		}
		try {
			return Files.readAllBytes(f.toPath());
		} catch (IOException ex) {
			throw new IllegalStateException(f.getName() + " could not be read back: " + ctrmap.util.Bytes.reason(ex), ex);
		}
	}

	/**
	 * A committed unit that can still be taken back and put again (F209), each only while what it would
	 * write over is what it left there. It is an {@link ctrmap.humaninterface.UndoHistory.Edit}, so a panel's
	 * history holds it beside its other edits.
	 *
	 * <p>WHAT IT HOLDS: each file's bytes before and after, both - Redo writes the after, and the after is
	 * what Undo checks is still there. That is twice the bytes of what it touched, for as long as the history
	 * keeps it (UndoHistory.LIMIT edits).
	 */
	public static final class Kept implements ctrmap.humaninterface.UndoHistory.Edit {

		private final String name;
		private final WorkspaceSession session;
		private final Map<File, byte[]> before;
		private final Map<File, byte[]> after;
		private final List<Runnable> inMemory;
		private final List<Runnable> again;
		/** The files this change added to the workspace's list of edits: out with Undo, in with Redo. */
		private final List<String> listed;
		/** And the files it took off it: in again with Undo, out with Redo. */
		private final List<String> unlisted;
		/** The compression flags it changed, each {type, entry, before, after} - null where there was none. */
		private final List<Object[]> flags;
		/** The session's pack count when its unit began: once that moves, a pack has carried it into the game. */
		private final int packsAtKeep;

		Kept(String name, WorkspaceSession session, Map<File, byte[]> before, Map<File, byte[]> after,
				List<Runnable> inMemory, List<Runnable> again, List<String> listed, List<String> unlisted,
				List<Object[]> flags, int packsAtKeep) {
			this.name = name;
			this.session = session;
			this.before = before;
			this.after = after;
			this.inMemory = inMemory;
			this.again = again;
			this.listed = listed;
			this.unlisted = unlisted;
			this.flags = flags;
			this.packsAtKeep = packsAtKeep;
		}

		/** Whether a pack has begun since this change was made: the game's archives may hold it. */
		private boolean carried() {
			return session != null && session.packs() != packsAtKeep;
		}

		/**
		 * Refuses, having put nothing, when a pack has carried this change and {@code next} would make a listed file
		 * GONE: that file is an archive entry the pack appended, and a pack only appends - nothing could take the
		 * entry out of the game again, so the workspace would say it is gone while the game keeps it.
		 */
		private void refuseAppendedEntries(Map<File, byte[]> next, String what) {
			if (!carried()) {
				return;
			}
			List<String> appended = new ArrayList<>();
			for (Map.Entry<File, byte[]> e : next.entrySet()) {
				if (e.getValue() == null && session.isPersisted(e.getKey())) {
					appended.add(e.getKey().getParentFile().getName() + "/" + e.getKey().getName());
				}
			}
			if (!appended.isEmpty()) {
				throw new IllegalStateException("\"" + name + "\" added " + appended.size() + " archive entr"
						+ (appended.size() == 1 ? "y" : "ies") + " that went into the game at a pack since, and a pack"
						+ " only adds entries, so none can be taken out again - nothing was " + what + ": " + appended);
			}
		}

		/**
		 * Once a pack has carried this change, the list of edits as it is in memory is written to disk, and that
		 * file's bytes become what {@code state} expects of it - so the next pack, after a restart too, writes back
		 * every file Undo or Redo put, and the move after this one finds the list as this one left it.
		 */
		private void keepListedOnDisk(Map<File, byte[]> state) throws IOException {
			if (!carried()) {
				return;
			}
			File list = WorkspaceSession.persistFile(session.workspaceDir()).getAbsoluteFile();
			if (!state.containsKey(list)) {
				return;   //this change listed nothing, so the list on disk is no part of it
			}
			session.writePersist();
			state.put(list, read(list));
		}

		/** Sets the compression flags this change changed back as they were, or again as it left them. */
		private void reflag(boolean again) {
			for (Object[] f : flags) {
				Boolean v = (Boolean) f[again ? 3 : 2];
				if (v == null) {
					session.forgetPendingCompression((ArchiveType) f[0], (Integer) f[1]);
				} else {
					session.registerPendingCompression((ArchiveType) f[0], (Integer) f[1], v);
				}
			}
		}

		/**
		 * Takes this change's additions out of the list of edits held in memory, or puts them in again. The list's
		 * FILE is one this change kept - the session reports it before writing it - so it is put back with the
		 * others; written again here, Redo would find it changed since and put nothing again.
		 */
		private void relist(boolean in) {
			if (session == null) {
				return;
			}
			List<String> edits = session.persistPaths();
			//CARRIED, NOTHING LEAVES THE LIST (F209b): the game holds what the pack wrote, and only a file still
			//listed is written over it again
			for (String p : carried() ? java.util.Collections.<String>emptyList() : in ? unlisted : listed) {
				edits.remove(p);
			}
			for (String p : in ? listed : unlisted) {
				if (!edits.contains(p)) {
					edits.add(p);
				}
			}
		}

		/** What the operation was called, for the button and the question. */
		@Override
		public String name() {
			return name;
		}

		@Override
		public void undo() {
			try {
				revert();
			} catch (IOException ex) {
				throw new IllegalStateException(name + " could not be put back: " + ctrmap.util.Bytes.reason(ex), ex);
			}
		}

		@Override
		public void redo() {
			try {
				reapply();
			} catch (IOException ex) {
				throw new IllegalStateException(name + " could not be put again: " + ctrmap.util.Bytes.reason(ex), ex);
			}
		}

		/** The names of the files that do not hold {@code expect}'s bytes now. */
		private static List<String> changed(Map<File, byte[]> expect) {
			List<String> changed = new ArrayList<>();
			for (Map.Entry<File, byte[]> e : expect.entrySet()) {
				if (!java.util.Arrays.equals(e.getValue(), read(e.getKey()))) {
					changed.add(e.getKey().getName());
				}
			}
			return changed;
		}

		/** Writes {@code next} - each file its bytes, or gone where they are null - as one unit. */
		private void write(Map<File, byte[]> next, String how) throws IOException {
			try (WorkspaceUnit unit = WorkspaceUnit.begin(how + name, session)) {
				for (Map.Entry<File, byte[]> e : next.entrySet()) {
					if (e.getValue() == null) {
						touching(e.getKey());
						Files.deleteIfExists(e.getKey().toPath());
					} else {
						WorkspaceUnit.write(e.getKey(), e.getValue());
					}
				}
				unit.commit();
			}
		}

		/**
		 * Puts every file the operation wrote back as it was, as one unit, and its in-memory state with them -
		 * or, when any of those files has changed since, refuses and names them, having put back nothing:
		 * taking back an edit is not taking back the edits made after it.
		 */
		public void revert() throws IOException {
			List<String> changed = changed(after);
			if (!changed.isEmpty()) {
				throw new IllegalStateException(changed.size() + " file(s) " + name + " wrote have changed since, so"
						+ " nothing was put back: " + changed);
			}
			refuseAppendedEntries(before, "put back");
			write(before, "Undo ");
			relist(false);
			keepListedOnDisk(before);
			reflag(false);
			for (int i = inMemory.size() - 1; i >= 0; i--) {
				inMemory.get(i).run();
			}
		}

		/**
		 * Puts the operation again, files and memory, after it was taken back - or, when any of its files has
		 * changed since it was taken back, refuses and names them, having put nothing again.
		 */
		public void reapply() throws IOException {
			List<String> changed = changed(before);
			if (!changed.isEmpty()) {
				throw new IllegalStateException(changed.size() + " file(s) " + name + " wrote have changed since it was"
						+ " taken back, so nothing was put again: " + changed);
			}
			refuseAppendedEntries(after, "put again");
			write(after, "Redo ");
			relist(true);
			keepListedOnDisk(after);
			reflag(true);
			for (Runnable r : again) {
				r.run();
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
	 * did not commit puts back what it wrote itself, as it was when it began,
	 * and the outermost decides the rest.
	 *
	 * @throws IllegalStateException when something could not be put back; it
	 *         names what, and is attached to the operation's own exception by
	 *         try-with-resources
	 */
	@Override
	@ctrmap.util.NotAnEdit("it puts back what an operation that did not finish wrote: each file gets the bytes it"
		+ " held before the operation began, so it returns to what the user had, and nothing new is made")
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		if (joined) {
			NESTED.get().remove(this);
			if (!committed) {
				putBackOwn();
			}
			return;
		}
		OPEN.remove();
		if (committed) {
			return;
		}
		List<String> failed = putBack(before);
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

	/** Puts each file back as {@code was} holds it, newest first; answers what could not be. */
	private static List<String> putBack(Map<File, byte[]> was) {
		List<String> failed = new ArrayList<>();
		List<File> files = new ArrayList<>(was.keySet());
		Collections.reverse(files);
		for (File f : files) {
			byte[] bytes = was.get(f);
			try {
				if (bytes == null) {
					Files.deleteIfExists(f.toPath());
				} else {
					Files.write(f.toPath(), bytes);
				}
			} catch (IOException ex) {
				failed.add(f.getName() + " (" + ctrmap.util.Bytes.reason(ex) + ")");
			}
		}
		return failed;
	}

	/**
	 * A nested unit that did not finish: its files as they were when it began, the edit list and pending flags as
	 * they were then, and the in-memory changes made inside it taken back - the outer unit goes on with the rest.
	 */
	private void putBackOwn() {
		List<String> failed = putBack(own);
		WorkspaceUnit outer = OPEN.get();
		if (session != null && ownEdits != null) {
			List<String> edits = session.persistPaths();
			edits.clear();
			edits.addAll(ownEdits);
			session.restorePendingCompression(ownPending);
		}
		if (outer != null) {
			for (int i = outer.undo.size() - 1; i >= ownMemory; i--) {
				try {
					outer.undo.get(i).run();
				} catch (RuntimeException ex) {
					failed.add("an in-memory undo (" + ctrmap.util.Bytes.reason(ex) + ")");
				}
				outer.undo.remove(i);
				outer.again.remove(i);
			}
		}
		if (!failed.isEmpty()) {
			throw new IllegalStateException(name + " did not finish, and these could not be put back as"
					+ " they were: " + String.join(", ", failed));
		}
	}
}
