package ctrmap.tests;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The battery's one reader of compiled classes: what the program actually
 * references, as opposed to how any one source file happens to spell it.
 *
 * <h2>Why a structural guard should not be a grep</h2>
 * Every structural rule in this battery except the public-static ceiling was a
 * regular expression over source text, and a regular expression over source
 * text cannot see through a static import. Measured on this tree: 33 files
 * carry {@code import static ctrmap.CtrmapMainframe.*} and then name the
 * window's widgets bare, so a {@code CtrmapMainframe\.} grep reports far fewer
 * readers than the class files hold. The same blindness applies to a literal
 * path assembled from constants: javac folds {@code "a/" + "0/1/3"} and any
 * {@code static final String} it is built from into one constant pool entry,
 * so the value is in the bytecode of a class whose source never spells it.
 * That is not hypothetical here. {@code Workspace} and {@code WorkspaceSession}
 * both carry the string "/a/3/0/0" because they use
 * {@code OrasProfile.DEMO_PROBE}, and neither source contains a GARC path at
 * all. A guard that greps sees nothing there; this sees the string.
 *
 * <h2>Why it reads the file instead of loading the class</h2>
 * Loading a class runs its static initialisers, and classes in this tree build
 * windows and reach for the open game when they initialise. Reflection over a
 * loaded class would also miss the constant pool entirely, which is where
 * every reference and every literal lives. So this parses the class file
 * format directly: a truncated or unrecognised file is an IOException, never a
 * quietly empty result, because a guard that measures nothing and passes is
 * the failure this battery exists to refuse.
 *
 * <h2>What it offers</h2>
 * <ul>
 * <li>{@link ClassFile#globals} - the public static non-final fields of a
 *     class, which is what {@link GlobalStateTest} counts;</li>
 * <li>{@link #edgesTo} - every distinct (referencing class, member name) pair
 *     that touches a named owner, with inner and anonymous classes folded to
 *     their outer class so one listener does not read as a separate reader;</li>
 * <li>{@link #callersOf} - every class holding a method reference matching an
 *     owner and a method name pattern, for the dialog seam;</li>
 * <li>{@link ClassFile#strings} and {@link #holdersOfString} - the string
 *     constants of a class, for the seam rules that look for literal paths;</li>
 * <li>{@link #application} - the classes outside {@code ctrmap.tests}, since a
 *     suite's own scaffolding is not the program.</li>
 * </ul>
 *
 * <p>This is test infrastructure, not a bytecode library. It reads the
 * constant pool and the field table and stops; method bodies are skipped
 * because no guard has needed one yet.
 */
public final class ClassFileScanner {

	private static final int ACC_PUBLIC = 0x0001;
	private static final int ACC_STATIC = 0x0008;
	private static final int ACC_FINAL = 0x0010;
	private static final int ACC_SYNTHETIC = 0x1000;

	private ClassFileScanner() {
	}

	/**
	 * An (owning class, member name) pair: a public static field a class
	 * declares, or a member of some other class that one references. Equality
	 * is by both halves so callers can collect distinct pairs in a set.
	 */
	public static final class Member {

		/** Internal name, e.g. {@code ctrmap/humaninterface/Selector}. */
		public final String owner;
		public final String name;

		Member(String owner, String name) {
			this.owner = owner;
			this.name = name;
		}

		/** The owner without its package or its outer class, for reporting. */
		public String simpleOwner() {
			return simpleName(owner);
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Member)) {
				return false;
			}
			Member m = (Member) o;
			return owner.equals(m.owner) && name.equals(m.name);
		}

		@Override
		public int hashCode() {
			return owner.hashCode() * 31 + name.hashCode();
		}

		@Override
		public String toString() {
			return simpleOwner() + "." + name;
		}
	}

	/** One Fieldref, Methodref or InterfaceMethodref out of a constant pool. */
	public static final class Ref {

		/** Internal name of the class the member was resolved against. */
		public final String owner;
		public final String name;
		public final String descriptor;
		/** False for a Fieldref, true for either kind of method reference. */
		public final boolean method;

		Ref(String owner, String name, String descriptor, boolean method) {
			this.owner = owner;
			this.name = name;
			this.descriptor = descriptor;
			this.method = method;
		}

		@Override
		public String toString() {
			return owner + "." + name + descriptor;
		}
	}

	/** One compiled class: its own globals, everything it references, its strings. */
	public static final class ClassFile {

		/** Internal name, e.g. {@code ctrmap/CtrmapMainframe$1}. */
		public final String name;
		/** Its public static non-final fields. */
		public final List<Member> globals;
		/** Every field and method reference in its constant pool. */
		public final List<Ref> refs;
		/** Every string constant in its constant pool, folded as javac left it. */
		public final List<String> strings;
		/**
		 * Every class this one names WITHOUT touching a member of it: the
		 * Class constants (a {@code new}, a cast, an {@code instanceof}, a
		 * caught exception, the superclass and every {@code implements}) and
		 * every class type spelled inside a descriptor or generic signature
		 * (a field's type, a parameter, a return type, a local's type).
		 * {@link #refs} cannot see these: a class that only implements an
		 * interface, or only passes one of its objects through, holds no
		 * Fieldref or Methodref to it at all, and a layering rule that
		 * counted members alone would pass it.
		 */
		public final Set<String> classes;
		/**
		 * The methods and constructors this class declares, as
		 * {@link Ref}s whose owner is this class; the descriptor says what
		 * each is HANDED, which is how a guard tells a class given its game
		 * as a parameter from one that fetches it.
		 */
		public final List<Ref> declares;

		ClassFile(String name, List<Member> globals, List<Ref> refs, List<String> strings,
				Set<String> classes, List<Ref> declares) {
			this.name = name;
			this.globals = Collections.unmodifiableList(globals);
			this.refs = Collections.unmodifiableList(refs);
			this.strings = Collections.unmodifiableList(strings);
			this.classes = Collections.unmodifiableSet(classes);
			this.declares = Collections.unmodifiableList(declares);
		}

		/**
		 * The top-level class this belongs to: {@code Foo$1} and
		 * {@code Foo$Bar} both fold to {@code Foo}. A guard asking who touches
		 * a class wants the file that has to be edited, and an anonymous
		 * listener is not a separate reader of anything.
		 */
		public String topLevel() {
			return outer(name);
		}

		/** True for a suite's own class, which is not part of the program. */
		public boolean isTest() {
			return name.startsWith("ctrmap/tests/");
		}

		@Override
		public String toString() {
			return name;
		}
	}

	// ------------------------------------------------------------------ reading

	/** Every class under a compiled tree, tests included. */
	public static List<ClassFile> scan(File classesRoot) throws IOException {
		List<ClassFile> out = new ArrayList<>();
		collect(classesRoot, classesRoot, out);
		return out;
	}

	/**
	 * The program's own classes: everything under {@code ctrmap.tests} is
	 * dropped, because a rule about the application must not be satisfied or
	 * broken by the suite measuring it.
	 */
	public static List<ClassFile> application(File classesRoot) throws IOException {
		List<ClassFile> out = new ArrayList<>();
		for (ClassFile cf : scan(classesRoot)) {
			if (!cf.isTest()) {
				out.add(cf);
			}
		}
		return out;
	}

	private static void collect(File root, File dir, List<ClassFile> out) throws IOException {
		File[] kids = dir.listFiles();
		if (kids == null) {
			return;
		}
		for (File f : kids) {
			if (f.isDirectory()) {
				collect(root, f, out);
			} else if (f.getName().endsWith(".class")) {
				String rel = root.toURI().relativize(f.toURI()).getPath();
				out.add(read(f, rel.substring(0, rel.length() - ".class".length())));
			}
		}
	}

	/**
	 * One class file, parsed as far as the field table. {@code internalName} is
	 * what the caller wants the class called; the collectors above derive it
	 * from the path under the compiled tree.
	 */
	public static ClassFile read(File f, String internalName) throws IOException {
		DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)));
		try {
			if (in.readInt() != 0xCAFEBABE) {
				throw new IOException("not a class file: " + f);
			}
			skip(in, 4); //minor, major
			int cpCount = in.readUnsignedShort();
			int[] tag = new int[cpCount];
			String[] utf = new String[cpCount];
			int[] a = new int[cpCount];
			int[] b = new int[cpCount];
			for (int i = 1; i < cpCount; i++) {
				tag[i] = in.readUnsignedByte();
				switch (tag[i]) {
					case 1: //Utf8
						utf[i] = in.readUTF();
						break;
					case 7: case 8: case 16: case 19: case 20: //Class, String, MethodType, Module, Package
						a[i] = in.readUnsignedShort();
						break;
					case 15: //MethodHandle: a reference kind then the reference
						skip(in, 1);
						a[i] = in.readUnsignedShort();
						break;
					case 9: case 10: case 11: case 12: case 17: case 18: //the two-index entries
						a[i] = in.readUnsignedShort();
						b[i] = in.readUnsignedShort();
						break;
					case 3: case 4: //Integer, Float
						skip(in, 4);
						break;
					case 5: case 6: //Long, Double
						skip(in, 8);
						i++; //a long or double eats two constant pool slots
						break;
					default:
						throw new IOException("unknown constant pool tag " + tag[i] + " in " + f);
				}
			}
			List<Ref> refs = new ArrayList<>();
			List<String> strings = new ArrayList<>();
			Set<String> classes = new LinkedHashSet<>();
			for (int i = 1; i < cpCount; i++) {
				if (tag[i] == 8) {
					strings.add(utf[a[i]]);
				} else if (tag[i] == 9 || tag[i] == 10 || tag[i] == 11) {
					int nt = b[i];
					refs.add(new Ref(utf[a[a[i]]], utf[a[nt]], utf[b[nt]], tag[i] != 9));
				} else if (tag[i] == 7) {
					//a Class constant names the class bare, or as an array descriptor
					classesIn(utf[a[i]].startsWith("[") ? utf[a[i]] : "L" + utf[a[i]] + ";", classes);
				} else if (tag[i] == 12) {
					classesIn(utf[b[i]], classes); //a NameAndType's descriptor
				} else if (tag[i] == 1 && utf[i] != null && utf[i].startsWith("(")) {
					classesIn(utf[i], classes); //a method descriptor or signature nothing else reached
				}
			}
			skip(in, 6);                          //access flags, this class, super class
			skip(in, 2 * in.readUnsignedShort()); //interfaces
			List<Member> globals = new ArrayList<>();
			int fieldCount = in.readUnsignedShort();
			for (int i = 0; i < fieldCount; i++) {
				int flags = in.readUnsignedShort();
				String name = utf[in.readUnsignedShort()];
				classesIn(utf[in.readUnsignedShort()], classes); //the field's type
				skipAttributes(in);
				boolean mutableGlobal = (flags & ACC_PUBLIC) != 0 && (flags & ACC_STATIC) != 0
						&& (flags & ACC_FINAL) == 0 && (flags & ACC_SYNTHETIC) == 0;
				if (mutableGlobal) {
					globals.add(new Member(internalName, name));
				}
			}
			List<Ref> declares = new ArrayList<>();
			int methodCount = in.readUnsignedShort();
			for (int i = 0; i < methodCount; i++) {
				skip(in, 2); //access flags
				String name = utf[in.readUnsignedShort()];
				String descriptor = utf[in.readUnsignedShort()];
				skipAttributes(in);
				classesIn(descriptor, classes);
				declares.add(new Ref(internalName, name, descriptor, true));
			}
			return new ClassFile(internalName, globals, refs, strings, classes, declares);
		} finally {
			in.close();
		}
	}

	/**
	 * Adds every class type spelled in a descriptor or a generic signature:
	 * each {@code Lname;} and, inside a signature, each {@code Lname<}.
	 * Primitives and type variables are not classes and are skipped.
	 */
	static void classesIn(String descriptor, Set<String> out) {
		if (descriptor == null) {
			return;
		}
		int i = 0;
		while ((i = descriptor.indexOf('L', i)) >= 0) {
			int end = i + 1;
			while (end < descriptor.length() && descriptor.charAt(end) != ';' && descriptor.charAt(end) != '<') {
				end++;
			}
			String name = descriptor.substring(i + 1, end);
			//"L" is also how a signature spells a type variable's bound and
			//a plain letter inside a name; only a slash-shaped name is a class
			if (name.indexOf('/') > 0 || name.startsWith("ctrmap")) {
				out.add(name);
			}
			i = end;
		}
	}

	private static void skipAttributes(DataInputStream in) throws IOException {
		int n = in.readUnsignedShort();
		for (int i = 0; i < n; i++) {
			skip(in, 2);
			skip(in, in.readInt());
		}
	}

	/** skipBytes may stop short; a short skip here would silently misparse. */
	private static void skip(DataInputStream in, int n) throws IOException {
		int done = 0;
		while (done < n) {
			int got = in.skipBytes(n - done);
			if (got <= 0) {
				throw new IOException("truncated class file");
			}
			done += got;
		}
	}

	// ------------------------------------------------------------------ queries

	/**
	 * Every distinct (referencing top-level class, member name) pair touching
	 * {@code owner}. The owner itself is included when its own code touches its
	 * own members, since folding puts its inner classes there too; a caller
	 * asking "who else reads this" drops that one entry by name.
	 */
	public static Set<Member> edgesTo(List<ClassFile> classes, String owner) {
		Set<Member> out = new LinkedHashSet<>();
		for (ClassFile cf : classes) {
			for (Ref r : cf.refs) {
				if (r.owner.equals(owner)) {
					out.add(new Member(cf.topLevel(), r.name));
				}
			}
		}
		return out;
	}

	/**
	 * Every distinct (referencing top-level class, named class) pair where the
	 * named class satisfies {@code target} - a class reference with or without
	 * a member edge behind it. {@code implements ctrmap/humaninterface/MapObject}
	 * is one of these and nothing in {@link #edgesTo}, which is why a layering
	 * rule asks both.
	 */
	public static Set<Member> classEdgesTo(List<ClassFile> classes, java.util.function.Predicate<String> target) {
		Set<Member> out = new LinkedHashSet<>();
		for (ClassFile cf : classes) {
			for (String c : cf.classes) {
				if (target.test(c)) {
					out.add(new Member(cf.topLevel(), c));
				}
			}
		}
		return out;
	}

	/** The top-level classes of {@link #edgesTo}, which is usually the question. */
	public static Set<String> readersOf(List<ClassFile> classes, String owner) {
		Set<String> out = new LinkedHashSet<>();
		for (Member m : edgesTo(classes, owner)) {
			out.add(m.owner);
		}
		return out;
	}

	/**
	 * The top-level classes holding a method reference to {@code owner} whose
	 * name matches {@code methodPattern}, where {@code *} stands for any run of
	 * characters. {@code callersOf(cs, "javax/swing/JOptionPane", "show*Dialog")}
	 * is the dialog seam's question, and unlike a grep it cannot be dodged by
	 * importing the class or by splitting the call across lines.
	 */
	public static Set<String> callersOf(List<ClassFile> classes, String owner, String methodPattern) {
		Set<String> out = new LinkedHashSet<>();
		for (ClassFile cf : classes) {
			for (Ref r : cf.refs) {
				if (r.method && r.owner.equals(owner) && glob(methodPattern, r.name)) {
					out.add(cf.topLevel());
					break;
				}
			}
		}
		return out;
	}

	/**
	 * The top-level classes holding a string constant that matches. The whole
	 * constant is offered to the pattern, so {@code find()} semantics are the
	 * caller's to choose.
	 */
	public static Set<String> holdersOfString(List<ClassFile> classes, Pattern text) {
		Set<String> out = new LinkedHashSet<>();
		for (ClassFile cf : classes) {
			for (String s : cf.strings) {
				if (s != null && text.matcher(s).find()) {
					out.add(cf.topLevel());
					break;
				}
			}
		}
		return out;
	}

	/** {@code Foo$1} and {@code Foo$Bar} fold to {@code Foo}; a package never does. */
	static String outer(String internalName) {
		int slash = internalName.lastIndexOf('/');
		int dollar = internalName.indexOf('$', slash + 1);
		return dollar < 0 ? internalName : internalName.substring(0, dollar);
	}

	/** The class name alone, with neither package nor outer class. */
	static String simpleName(String internalName) {
		String s = outer(internalName);
		return s.substring(s.lastIndexOf('/') + 1);
	}

	/** Literal match, except that {@code *} stands for any run of characters. */
	static boolean glob(String pattern, String s) {
		if (pattern.indexOf('*') < 0) {
			return pattern.equals(s);
		}
		String[] parts = pattern.split("\\*", -1);
		StringBuilder rx = new StringBuilder();
		for (int i = 0; i < parts.length; i++) {
			if (i > 0) {
				rx.append(".*");
			}
			rx.append(Pattern.quote(parts[i]));
		}
		return Pattern.matches(rx.toString(), s);
	}
}
