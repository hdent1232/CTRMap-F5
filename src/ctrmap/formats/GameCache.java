package ctrmap.formats;

import java.util.HashMap;
import java.util.Map;

/**
 * What a game answers once and keeps: one store per open game, owned by the
 * game, so nothing read from a game can outlive it (F218).
 *
 * <p>Every cache of game-derived state used to be a static field of the class
 * that filled it, and the tree invalidated them seven different ways: one was
 * dropped after a pack, one reloaded on workspace open and never after a pack,
 * two were keyed to the identity of the game handed in, one had an
 * {@code invalidate()} nothing called, and two - the building palette's cut
 * prefabs and donor textures - were keyed to nothing, so after repointing to
 * another game the palette stamped the first game's geometry into the second.
 * A label table was even computed once at class load, from whatever game was
 * open then.
 *
 * <p>Here the lifetime is the game's by construction. A different game is a
 * different {@link GameFiles} and so a different store, which starts empty; the
 * session empties its own store when a pack has rewritten the archives
 * ({@link #drop}); and a key read while the store was being dropped is handed
 * back but not kept, since it may have been read from the archives the pack
 * just replaced. Nothing is kept from a read that throws: a failure is said
 * again next time, never remembered as an empty answer.
 *
 * <p>The store's lock is never held while a key is read. Reads here build prop
 * databases and cut regions, and the classes doing it lock themselves; holding
 * one lock across both would order them, and two threads taking them in the
 * other order would wait for each other for ever. Two threads asking for the
 * same missing key at once may both read it; the first answer kept is the one
 * both get.
 *
 * <p>Not: {@link ctrmap.formats.h3d.BuildingCatalog#snapshotHandle} - that keys one file's handle to the file's
 * length and time, which says when a FILE changed; this says when the GAME did, for answers read from many files.
 */
public final class GameCache {

	/** Names one thing kept here. Compared by identity, so two classes cannot collide on a spelling. */
	public static final class Key<T> {

		private final String name;

		public Key(String name) {
			this.name = name;
		}

		@Override
		public String toString() {
			return name;
		}
	}

	/** How a missing key is read. A throw keeps nothing. */
	public interface Read<T, E extends Exception> {

		T read() throws E;
	}

	private final Map<Key<?>, Object> held = new HashMap<>();
	/** How many times {@link #drop} has run; a read that began before one is not kept. */
	private int drops;

	/**
	 * What {@code key} holds for this game, read by {@code read} the first time
	 * and kept until the game changes. A null answer is kept like any other.
	 */
	public <T, E extends Exception> T get(Key<T> key, Read<T, E> read) throws E {
		int began;
		synchronized (this) {
			if (held.containsKey(key)) {
				return cast(held.get(key));
			}
			began = drops;
		}
		T made = read.read();
		synchronized (this) {
			if (began != drops) {
				return made;
			}
			if (held.containsKey(key)) {
				return cast(held.get(key));
			}
			held.put(key, made);
			return made;
		}
	}

	/** Whether {@code key} is held, without reading anything. */
	public synchronized boolean holds(Key<?> key) {
		return held.containsKey(key);
	}

	/** Forgets one key, because what it was read from was just rewritten. */
	public synchronized void forget(Key<?> key) {
		held.remove(key);
	}

	/** Forgets everything: the game's archives were rewritten, so every answer read from them may be stale. */
	public synchronized void drop() {
		held.clear();
		drops++;
	}

	@SuppressWarnings("unchecked")
	private static <T> T cast(Object o) {
		return (T) o;
	}
}
