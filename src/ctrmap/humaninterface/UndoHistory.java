package ctrmap.humaninterface;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * The one undo model: what an editor changed, taken back and put again (F209).
 *
 * <p>FOUR MECHANISMS FOR FOUR EDITORS, AND NONE FOR NINETEEN. Undo existed as a static tile deque
 * shared by the whole process, two private snapshot stacks (the painter's and the geometry form's) and
 * the item table's own; the NPC, warp, trigger, camera, prop, collision and matrix editors, the zone
 * header and the drag tools had none, and Ctrl+Z always went to the tile history whatever was being
 * edited. Each was written beside its editor, so the next editor started without one.
 *
 * <p>SO THIS DOES NOT ASK EDITORS TO RECORD. It WATCHES the documents they edit - each a {@link
 * Document} that can hand over its state as a value and take one back - and settles around every user
 * gesture: when a mouse button or a key goes down it notes what each document is, and once the
 * gesture's handlers have run it compares, and whatever changed becomes ONE edit, named for what it
 * changed. An editor that marks a zone modified from a new place, or a new editor over a watched
 * document, is undoable without a line of its own; the guard a recording call would need at every
 * one of eighty write sites is one call site from broken, and this has no call sites.
 *
 * <p>NOT STATIC. A history belongs to what it is the history of - the open zone's lives as long as
 * the zone is open and is reset when another opens - so two of anything can each have their own,
 * and Ctrl+Z goes to the one belonging to what the user is working in ({@link #of}).
 *
 * <p>Not: {@code javax.swing.undo.UndoManager} - it keeps edits someone made, and the whole point
 * here is that nobody has to make one.
 */
public final class UndoHistory {

	/**
	 * Something an editor changes, read and put back whole.
	 *
	 * @param <S> what its state is held as - a value that shares nothing with the live document, so a
	 *        later change to the document cannot reach a state already taken
	 */
	public interface Document<S> {

		/** The live object this reads: two documents over the same object are one document. */
		Object subject();

		/** Its state now, sharing nothing with it. */
		S take();

		/** Makes the document what {@code state} says, without keeping {@code state} itself, and shows it. */
		void put(S state);

		/** Whether two states are the same document. */
		boolean same(S a, S b);

		/** What it is, in words, for the button: "the zone's NPCs, warps and triggers". */
		String what();
	}

	/** One change, taken back and put again. */
	public interface Edit {

		String name();

		void undo();

		void redo();
	}

	/**
	 * Bytes the workspace holds - one subfile of a container, or a file - read and written whole: where
	 * a write that IS an edit lands ({@link #write}).
	 */
	public interface Place {

		/** What it holds now, or null when it cannot be read. */
		byte[] read();

		/** Puts {@code data} there, and reads again whatever is held in memory from it; throws with the reason when it cannot. */
		void write(byte[] data);

		/** What it is, in words, for the button: "the area's prop textures". */
		String what();
	}

	/** How many edits are kept; the oldest go first. */
	public static final int LIMIT = 100;

	private final Supplier<List<Object>> scope;
	/** Where the documents come from: the one the history was built over, then every one added by watch. */
	private final List<Supplier<List<Document<?>>>> documents = new ArrayList<>();
	private final Deque<Edit> undo = new ArrayDeque<>();
	private final Deque<Edit> redo = new ArrayDeque<>();
	/** What each document was when last seen, by the live object it reads. */
	private Map<Object, Object> seen = new IdentityHashMap<>();
	/** What the scope was when last asked: its objects, by identity. */
	private List<Object> lastScope;
	private final List<Runnable> listeners = new ArrayList<>();
	/** How many of the newest edits were added since the last settle, and join what it finds changed. */
	private int joinable;

	/**
	 * A history of whatever documents {@code documents} answers with when asked - asked afresh at every
	 * settle, so a region loaded since is watched from then on, and one gone is let go.
	 *
	 * @param scope what this is the history OF - for a zone's, the open zone and its map - asked before
	 *        anything is recorded, taken back or put again. When any of its objects is not the one it
	 *        was, the history starts again: an edit made on one zone cannot be put back on another, or
	 *        on a closed one nobody is looking at.
	 */
	public UndoHistory(Supplier<List<Object>> scope, Supplier<List<Document<?>>> documents) {
		if (scope == null || documents == null) {
			throw new IllegalArgumentException("a history must be handed what it is the history of, and where"
					+ " its documents come from");
		}
		this.scope = scope;
		this.documents.add(documents);
	}

	/**
	 * Watches more documents from now on: an editor handed this history adds its own (the painter its
	 * canvas), so whoever built the history keeps no list of every editor's documents. They are noted
	 * at the next settle, as they are then; being added is not an edit.
	 */
	public void watch(Supplier<List<Document<?>>> more) {
		if (more == null) {
			throw new IllegalArgumentException("watch was handed no documents");
		}
		documents.add(more);
	}

	/** Every document now, from every source; a second document over an object already read is the same document and is left out. */
	private List<Document<?>> documents() {
		List<Document<?>> out = new ArrayList<>();
		Map<Object, Boolean> subjects = new IdentityHashMap<>();
		for (Supplier<List<Document<?>>> source : documents) {
			for (Document<?> d : source.get()) {
				if (subjects.put(d.subject(), Boolean.TRUE) == null) {
					out.add(d);
				}
			}
		}
		return out;
	}

	/** Starts again when the scope is no longer the one the edits were made in; true when it did. */
	private boolean rescope() {
		List<Object> now = scope.get();
		boolean same = lastScope != null && lastScope.size() == now.size();
		for (int i = 0; same && i < now.size(); i++) {
			same = lastScope.get(i) == now.get(i);
		}
		if (same) {
			return false;
		}
		lastScope = new ArrayList<>(now);
		undo.clear();
		redo.clear();
		joinable = 0;
		seen = new IdentityHashMap<>();
		changed();
		return true;
	}

	/**
	 * Compares every document with what it was when last seen, and records what changed as one edit.
	 * A document seen for the first time is only noted: being opened is not an edit.
	 *
	 * @return whether an edit was recorded
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public boolean settle() {
		rescope();
		List<Part> parts = new ArrayList<>();
		Map<Object, Object> now = new IdentityHashMap<>();
		for (Document d : documents()) {
			Object state = d.take();
			Object subject = d.subject();
			if (seen.containsKey(subject)) {
				Object was = seen.get(subject);
				if (!d.same(was, state)) {
					parts.add(new Part(d, was, state));
				}
			} else if (!d.same(state, d.take())) {
				//READ TWICE ON FIRST SIGHT: a same() that compares the readings' identity, not their content,
				//calls every settle an edit, and Undo then takes back a phantom instead of the change
				throw new IllegalStateException(d.what() + " reads differently twice with nothing changed: its comparison"
						+ " looks at something other than its content, so every gesture would be recorded as an edit");
			}
			now.put(subject, state);
		}
		seen = now;
		if (parts.isEmpty()) {
			joinable = 0;   //an edit added since the last settle stays its own: nothing joined it
			return false;
		}
		List<Edit> made = new ArrayList<>();
		for (; joinable > 0 && !undo.isEmpty(); joinable--) {
			made.add(0, undo.pop());   //the newest is on top: taken off, they are oldest first
		}
		add(new StateEdit(parts, made));
		joinable = 0;
		return true;
	}

	/**
	 * Records an edit made some other way - a write through {@link #write}, a file operation's kept unit -
	 * as the newest. Until the next settle it is JOINABLE: what the same gesture turns out to have changed
	 * in the documents is recorded with it as one edit, so Undo takes back a prop's registration whole -
	 * the textures written into its area and its registry entry - and never in halves.
	 */
	public void add(Edit e) {
		if (e == null) {
			throw new IllegalArgumentException("add was handed no edit");
		}
		rescope();   //an edit belongs to the scope it was made in: asked only at the next settle, a moved scope wiped it
		undo.push(e);
		while (undo.size() > LIMIT) {
			undo.removeLast();
		}
		redo.clear();
		joinable++;
		changed();
	}

	/**
	 * Writes {@code data} to {@code at} as an edit of this history (F209): for a write that IS the edit -
	 * a prop's textures imported into its area, the story line an NPC wizard adds - and never for a save,
	 * whose bytes are the documents' state, already theirs. Taken back and put again only over what it left
	 * there: a place written since, by a save or by another editor, is refused rather than overwritten.
	 *
	 * @throws IllegalStateException when it cannot be read before (nothing is written then) or after
	 */
	public void write(Place at, byte[] data) {
		//A READ THAT THROWS IS SAID AS ONE THAT ANSWERED NULL (census M.4): a container's subfile refuses in
		//words now, and those words lost the one this history adds - whether anything was written
		byte[] before = readOrNull(at);
		if (before == null) {
			throw new IllegalStateException(at.what() + " could not be read" + unread + ", so writing it could not be"
					+ " taken back: nothing was written");
		}
		at.write(data);
		byte[] after = readOrNull(at);
		if (after == null) {
			throw new IllegalStateException(at.what() + " was written and cannot be read back" + unread
					+ ", so it cannot be taken back");
		}
		if (!java.util.Arrays.equals(before, after)) {
			add(new Written(at, before, after));
		}
	}

	/** Why the last {@link #readOrNull} answered null, as " (reason)", or empty when its Place answered null itself. */
	private String unread = "";

	/** {@code at}'s bytes, or null with {@link #unread} saying why when it cannot be read - whether it answers null or throws. */
	private byte[] readOrNull(Place at) {
		unread = "";
		try {
			return at.read();
		} catch (RuntimeException refused) {
			unread = " (" + ctrmap.util.Bytes.reason(refused) + ")";
			return null;
		}
	}

	public boolean canUndo() {
		return !undo.isEmpty();
	}

	public boolean canRedo() {
		return !redo.isEmpty();
	}

	/** What Undo would take back, or null. */
	public String undoName() {
		return undo.isEmpty() ? null : undo.peek().name();
	}

	/** What Redo would put again, or null. */
	public String redoName() {
		return redo.isEmpty() ? null : redo.peek().name();
	}

	/**
	 * Takes back the newest edit. Settles first: a change not yet recorded is the newest edit, and
	 * taking back the one before it would leave it applied over a state it was not made on.
	 *
	 * @return false when there was nothing to take back
	 */
	public boolean undo() {
		settle();
		if (undo.isEmpty()) {
			return false;
		}
		Edit e = undo.peek();
		refuseWhatIsNotOpen(e);
		e.undo();   //before it is taken off: a refused undo leaves the edit where it was
		undo.pop();
		redo.push(e);
		settleQuietly();
		joinable = 0;
		changed();
		return true;
	}

	/** Puts the edit taken back last again; false when there is none. */
	public boolean redo() {
		settle();
		if (redo.isEmpty()) {
			return false;
		}
		Edit e = redo.peek();
		refuseWhatIsNotOpen(e);
		e.redo();   //before it is taken off: a refused redo leaves the edit where it was
		redo.pop();
		undo.push(e);
		settleQuietly();
		joinable = 0;
		changed();
		return true;
	}

	/**
	 * A document an edit changed that is no longer among the open ones - a region the map has let go
	 * without the scope saying so - is refused, and the history starts again, rather than put into an
	 * object nobody is showing.
	 */
	private void refuseWhatIsNotOpen(Edit e) {
		if (!(e instanceof StateEdit)) {
			return;
		}
		Map<Object, Boolean> open = new IdentityHashMap<>();
		for (Document<?> d : documents()) {
			open.put(d.subject(), Boolean.TRUE);
		}
		for (Part p : ((StateEdit) e).parts) {
			if (!open.containsKey(p.doc.subject())) {
				undo.clear();
				redo.clear();
				joinable = 0;
				seen = new IdentityHashMap<>();
				settle();
				changed();
				throw new IllegalStateException("\"" + e.name() + "\" was made on something no longer open ("
						+ p.doc.what() + "), so nothing was changed, and the history starts again from here.");
			}
		}
	}

	/**
	 * Forgets every edit and what every document was, and notes them as they are now: what is open is a
	 * different document (a map reloaded in place), and none of the edits could be put on it.
	 */
	public void reset() {
		undo.clear();
		redo.clear();
		joinable = 0;
		seen = new IdentityHashMap<>();
		settle();   //notes the documents as they are now, recording nothing: nothing was seen before
		changed();
	}

	/**
	 * Lets go of one document: its part of every edit is dropped (an edit left with no part goes), and it
	 * is seen afresh at the next settle. For a document that starts over in place - the painter's canvas
	 * re-seeded on another region - whose old states mean nothing now, while the zone's other edits do.
	 */
	public void forget(Object subject) {
		strip(undo, subject);
		strip(redo, subject);
		seen.remove(subject);
		changed();
	}

	private static void strip(Deque<Edit> edits, Object subject) {
		for (java.util.Iterator<Edit> it = edits.iterator(); it.hasNext();) {
			Edit e = it.next();
			if (e instanceof StateEdit) {
				((StateEdit) e).parts.removeIf(p -> p.doc.subject() == subject);
				if (((StateEdit) e).parts.isEmpty() && ((StateEdit) e).made.isEmpty()) {
					it.remove();
				}
			}
		}
	}

	/** Called whenever what Undo or Redo would do changes. */
	public void addListener(Runnable onChange) {
		listeners.add(onChange);
	}

	private void changed() {
		for (Runnable r : listeners) {
			r.run();
		}
	}

	/** After an undo or redo: what the documents are now is what was just put, not a new edit. */
	private void settleQuietly() {
		Map<Object, Object> now = new IdentityHashMap<>();
		for (Document<?> d : documents()) {
			now.put(d.subject(), d.take());
		}
		seen = now;
	}

	/** One document's part of an edit. */
	private static final class Part {

		@SuppressWarnings("rawtypes")
		final Document doc;
		final Object before;
		final Object after;

		@SuppressWarnings("rawtypes")
		Part(Document doc, Object before, Object after) {
			this.doc = doc;
			this.before = before;
			this.after = after;
		}
	}

	/** What one gesture changed, across however many documents, with the writes it made. */
	private static final class StateEdit implements Edit {

		private final List<Part> parts;
		/**
		 * The edits added during the gesture (a write it made), oldest first. Put back and put again BEFORE
		 * the documents: what a document shows when it is put may be read from what they wrote.
		 */
		private final List<Edit> made;

		StateEdit(List<Part> parts, List<Edit> made) {
			this.parts = parts;
			this.made = made;
		}

		@Override
		public String name() {
			String first = made.isEmpty() ? parts.get(0).doc.what() : made.get(0).name();
			int more = made.size() + parts.size() - 1;
			return more == 0 ? first : first + " and " + more + " more";
		}

		@Override
		@SuppressWarnings("unchecked")
		public void undo() {
			for (int i = made.size() - 1; i >= 0; i--) {
				try {
					made.get(i).undo();
				} catch (RuntimeException refused) {
					for (int j = i + 1; j < made.size(); j++) {
						made.get(j).redo();   //all of it or none: what was taken back goes on again
					}
					throw refused;
				}
			}
			for (int i = parts.size() - 1; i >= 0; i--) {
				parts.get(i).doc.put(parts.get(i).before);
			}
		}

		@Override
		@SuppressWarnings("unchecked")
		public void redo() {
			for (int i = 0; i < made.size(); i++) {
				try {
					made.get(i).redo();
				} catch (RuntimeException refused) {
					for (int j = i - 1; j >= 0; j--) {
						made.get(j).undo();   //all of it or none: what was put again comes off again
					}
					throw refused;
				}
			}
			for (Part p : parts) {
				p.doc.put(p.after);
			}
		}
	}

	/** A write made through {@link #write}: taken back, and put again, only over what it left there. */
	private static final class Written implements Edit {

		private final Place at;
		private final byte[] before;
		private final byte[] after;

		Written(Place at, byte[] before, byte[] after) {
			this.at = at;
			this.before = before;
			this.after = after;
		}

		@Override
		public String name() {
			return at.what();
		}

		@Override
		public void undo() {
			put(after, before, "taken back");
		}

		@Override
		public void redo() {
			put(before, after, "put again");
		}

		private void put(byte[] expect, byte[] next, String how) {
			if (!java.util.Arrays.equals(at.read(), expect)) {
				throw new IllegalStateException(at.what() + " has been written since, so it was not " + how
						+ ": taking back an edit is not taking back what was written after it");
			}
			at.write(next);
		}
	}

	/**
	 * Undo or Redo as a button or a key does it: a refusal - the edit was made on something no longer
	 * open, or a document could not take its state back - is said to the user, through {@link ctrmap.Ui},
	 * and the history goes on.
	 */
	public void act(Component parent, boolean back) {
		try {
			if (back) {
				undo();
			} else {
				redo();
			}
		} catch (RuntimeException refused) {
			ctrmap.Ui.error(parent, (back ? "Nothing was undone: " : "Nothing was redone: ") + ctrmap.Ui.reason(refused),
					back ? "Undo" : "Redo");
		}
	}

	// ------------------------------------------------------------ where Ctrl+Z goes

	/** The client property a component carries to say which history edits made in it belong to. */
	private static final String KEY = UndoHistory.class.getName();

	/** Says that what is edited inside {@code c} belongs to this history. */
	public void own(JComponent c) {
		c.putClientProperty(KEY, this);
	}

	/**
	 * The history the user is working in: the nearest one a component owns, from {@code c} up, or
	 * {@code otherwise} where none does.
	 */
	public static UndoHistory of(Component c, UndoHistory otherwise) {
		for (Component at = c; at != null; at = at.getParent()) {
			if (at instanceof JComponent) {
				Object h = ((JComponent) at).getClientProperty(KEY);
				if (h instanceof UndoHistory) {
					return (UndoHistory) h;
				}
			}
		}
		return otherwise;
	}

	/**
	 * Settles the histories {@code open} answers with around every user gesture, for the run: before a
	 * mouse button or key goes down (so a change made since the last gesture is not taken for this
	 * one) and after it is released, once its handlers have run.
	 */
	public static void settleAroundGestures(Supplier<List<UndoHistory>> open) {
		Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
			if (!(e instanceof InputEvent)) {
				return;
			}
			int id = e.getID();
			if (id == MouseEvent.MOUSE_PRESSED || id == KeyEvent.KEY_PRESSED) {
				for (UndoHistory h : open.get()) {
					h.settle();
				}
			} else if (id == MouseEvent.MOUSE_RELEASED || id == KeyEvent.KEY_RELEASED) {
				SwingUtilities.invokeLater(() -> {
					for (UndoHistory h : open.get()) {
						h.settle();
					}
				});
			}
		}, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
	}
}
