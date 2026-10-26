package ctrmap.humaninterface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * An exact copy of a flat record - every instance field, by reflection - for the undo documents (F209).
 *
 * <p>BY ITS FIELDS, NOT ITS FILE FORMAT. A zone's NPC or warp could be copied by writing it and
 * reading it back, but the writer validates (an unset warp, an NPC with no altitude, are refused at
 * save) and the format does not keep every in-memory distinction; an undo of a half-made warp must put
 * back exactly the half-made warp. And by reflection rather than a copy method per class, because a
 * field added to a record later is copied without anyone remembering to.
 *
 * <p>FLAT means: a primitive, a String, a boxed value or an enum is copied as is; an array of primitives,
 * or of such arrays, is cloned to its last element; a field typed as an INTERFACE - a height lookup, never a collection or a map, which are
 * interfaces too and are state - is SHARED, never copied, because it is not
 * the record's state but something the record is handed. Anything else - a list, an array of objects,
 * an object of a concrete class - is REFUSED, by class and field name, before a value is read: the
 * copy would share it, a change made inside it would be in the state before the change as well, and
 * an undo would put back nothing without a word. Static and synthetic fields are not state.
 * A record class must have a no-argument constructor; one without is refused by name.
 *
 * <p>Not: {@code Object.clone} - none of these records implements it, and a clone shares a primitive
 * array with its original, which is exactly the aliasing an undo state must not have.
 */
public final class FlatCopy {

	private FlatCopy() {
	}

	/** A new record of the same class with every field's value copied. */
	public static <T> T copy(T record) {
		if (record == null) {
			return null;
		}
		T made = make(record.getClass());
		into(record, made);
		return made;
	}

	/** Copies every field of {@code from} into {@code to}, which keeps its identity. */
	public static <T> void into(T from, T to) {
		if (from.getClass() != to.getClass()) {
			throw new IllegalArgumentException("a " + from.getClass().getSimpleName() + " cannot be copied into a "
					+ to.getClass().getSimpleName());
		}
		for (Field f : fields(from.getClass())) {
			Object v = read(f, from);
			write(f, to, v != null && v.getClass().isArray() ? cloneArray(v) : v);   //flat() let through primitive arrays only
		}
	}

	/**
	 * A field's value, read - THE ONE PLACE A FIELD IS READ HERE (2026-10-24). Every field is made accessible before it is
	 * read, so the IllegalAccessException each loop caught was a line no record reached (the 2026-10-17 sweep, FlatCopy 57,
	 * a HOLE). A field that cannot be read is said, naming it; UndoHistoryTest hands one never made accessible.
	 */
	private static Object read(Field f, Object from) {
		try {
			return f.get(from);
		} catch (IllegalAccessException ex) {
			throw new IllegalStateException(f.getDeclaringClass().getName() + "." + f.getName() + " could not be read: "
					+ ctrmap.Ui.reason(ex), ex);
		}
	}

	/** A field's value, written - the other half; UndoHistoryTest hands one a static final field, which refuses it. */
	private static void write(Field f, Object to, Object value) {
		try {
			f.set(to, value);
		} catch (IllegalAccessException ex) {
			throw new IllegalStateException(f.getDeclaringClass().getName() + "." + f.getName() + " could not be written: "
					+ ctrmap.Ui.reason(ex), ex);
		}
	}

	/** Whether two records are the same class with every field equal (arrays by content, shared references by identity). */
	public static boolean same(Object a, Object b) {
		if (a == b) {
			return true;
		}
		if (a == null || b == null || a.getClass() != b.getClass()) {
			return false;
		}
		for (Field f : fields(a.getClass())) {
			Object x = read(f, a);
			Object y = read(f, b);
			if (x != null && x.getClass().isArray() && y != null && y.getClass().isArray()) {
				if (!java.util.Objects.deepEquals(x, y)) {
					return false;
				}
			} else if (f.getType().isPrimitive() || x instanceof String || x instanceof Enum || x instanceof Number
					|| x instanceof Boolean || x instanceof Character) {
				if (!java.util.Objects.equals(x, y)) {
					return false;
				}
			} else if (x != y) {
				return false;
			}
		}
		return true;
	}

	/** A list of copies. */
	public static <T> List<T> copyAll(List<T> records) {
		List<T> out = new ArrayList<>(records.size());
		for (T r : records) {
			out.add(copy(r));
		}
		return out;
	}

	/** Whether two lists hold the same records in the same order. */
	public static boolean sameAll(List<?> a, List<?> b) {
		if (a.size() != b.size()) {
			return false;
		}
		for (int i = 0; i < a.size(); i++) {
			if (!same(a.get(i), b.get(i))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Makes {@code live} hold what {@code state} holds, KEEPING the live records it already has: the
	 * first ones are overwritten in place, so an editor holding one goes on holding the record it showed,
	 * and only the ones added or removed come or go.
	 */
	public static <T> void putAll(List<T> state, List<T> live) {
		int keep = Math.min(state.size(), live.size());
		for (int i = 0; i < keep; i++) {
			into(state.get(i), live.get(i));
		}
		while (live.size() > state.size()) {
			live.remove(live.size() - 1);
		}
		for (int i = keep; i < state.size(); i++) {
			live.add(copy(state.get(i)));
		}
	}

	private static List<Field> fields(Class<?> c) {
		List<Field> out = new ArrayList<>();
		for (Class<?> at = c; at != null && at != Object.class; at = at.getSuperclass()) {
			for (Field f : at.getDeclaredFields()) {
				if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
					if (!flat(f.getType())) {
						throw new IllegalArgumentException(c.getName() + " is not a flat record: its field " + f.getName()
								+ " is a " + f.getType().getSimpleName() + ", which a copy would share, so a change"
								+ " made inside it could never be taken back");
					}
					f.setAccessible(true);
					out.add(f);
				}
			}
		}
		return out;
	}

	/** A type a flat record's field may have: copied as a value, cloned, or - an interface - handed in and shared. */
	private static boolean flat(Class<?> t) {
		return t.isPrimitive() || primitiveArray(t) || t == String.class
				|| t.isEnum() || handedIn(t) || t == Boolean.class || t == Character.class
				|| (Number.class.isAssignableFrom(t) && t.getName().startsWith("java.lang."));
	}

	/**
	 * An interface the record is handed - a height lookup - and shares. Not a collection, a map or anything
	 * else from java.util: a List field is an interface too, and it is the record's state.
	 */
	private static boolean handedIn(Class<?> t) {
		return t.isInterface() && !Iterable.class.isAssignableFrom(t) && !java.util.Map.class.isAssignableFrom(t)
				&& !t.getName().startsWith("java.util.");
	}

	@SuppressWarnings("unchecked")
	private static <T> T make(Class<?> c) {
		try {
			Constructor<?> k = c.getDeclaredConstructor();
			k.setAccessible(true);
			return (T) k.newInstance();
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(c.getName() + " has no constructor that takes nothing, so it cannot be"
					+ " copied for undo: " + ex, ex);
		}
	}

	/** An array of primitives, or of such arrays to any depth: a prop registry entry holds a short[3][3]. */
	private static boolean primitiveArray(Class<?> t) {
		return t.isArray() && (t.getComponentType().isPrimitive() || primitiveArray(t.getComponentType()));
	}

	/** A copy of a primitive array, its inner arrays copied too: shared, an edit to one would be in both. */
	private static Object cloneArray(Object a) {
		int n = java.lang.reflect.Array.getLength(a);
		Object out = java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), n);
		if (a.getClass().getComponentType().isPrimitive()) {
			System.arraycopy(a, 0, out, 0, n);
		} else {
			for (int i = 0; i < n; i++) {
				java.lang.reflect.Array.set(out, i, cloneArray(java.lang.reflect.Array.get(a, i)));
			}
		}
		return out;
	}
}
