package ctrmap.tests;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * <li>{@link ClassFile#statics} - every static field it declares, with its
 *     flags and type, which is what {@link GlobalStateTest} argues;</li>
 * <li>{@link ClassFile#methods} - every method it declares with the methods
 *     its body invokes, and {@link ClassFile#handles} - the methods its
 *     lambdas and method references name - which is what
 *     {@link EveryMethodIsReachedTest} walks;</li>
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
 * constant pool, the field table, and of each method body only its invoke
 * instructions - walking the rest by length, so a body it cannot walk is an
 * IOException, never a method that calls nothing.
 */
public final class ClassFileScanner {

	private static final int ACC_PUBLIC = 0x0001;
	private static final int ACC_STATIC = 0x0008;
	private static final int ACC_FINAL = 0x0010;
	private static final int ACC_SYNTHETIC = 0x1000;
	private static final int ACC_ENUM = 0x4000;

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

	/**
	 * One static field a class declares, with its access flags and its type
	 * descriptor - enough to tell a constant from state that can change.
	 * Synthetic fields and enum constants are not reported: the compiler's and
	 * the enum's own, never written by anything here.
	 */
	public static final class StaticField {

		/** Internal name of the declaring class, inner classes keeping their {@code $}. */
		public final String owner;
		public final String name;
		/** The field's type, e.g. {@code [Ljava/lang/String;} or {@code Ljava/util/Map;}. */
		public final String descriptor;
		public final int flags;

		StaticField(String owner, String name, String descriptor, int flags) {
			this.owner = owner;
			this.name = name;
			this.descriptor = descriptor;
			this.flags = flags;
		}

		public boolean isFinal() {
			return (flags & ACC_FINAL) != 0;
		}

		public boolean isPublic() {
			return (flags & ACC_PUBLIC) != 0;
		}

		@Override
		public String toString() {
			return simpleName(owner) + "." + name;
		}
	}

	/** One method a class declares: its flags, and every method its body invokes, as name + descriptor. */
	public static final class Method {

		public final String owner;
		public final String name;
		public final String descriptor;
		public final int flags;
		/**
		 * Every invokevirtual, invokespecial, invokestatic and invokeinterface in its body, as
		 * {@code owner#name+descriptor}: the class the call was resolved against, and what it names.
		 */
		public final Set<String> invokes;
		/** The fields its body READS - getfield and getstatic - as {@code owner#name}, the owner as the
		 *  instruction names it (the declaring class or a subclass of it). A write is not a read (F204). */
		public final Set<String> reads;
		/** The Code attribute, from max_stack on, or null for a body-less method; and each invoke by its code offset. */
		private final byte[] code;
		private final Map<Integer, String> invokeAt;

		Method(String owner, String name, String descriptor, int flags, Set<String> invokes, Set<String> reads,
				byte[] code, Map<Integer, String> invokeAt) {
			this.owner = owner;
			this.name = name;
			this.descriptor = descriptor;
			this.flags = flags;
			this.invokes = Collections.unmodifiableSet(invokes);
			this.reads = Collections.unmodifiableSet(reads);
			this.code = code;
			this.invokeAt = invokeAt;
		}

		/**
		 * The invokes reachable from the method's entry without passing the "yes" edge of a branch on a
		 * call to {@code ask} (a boolean method, as in {@link #invokes}) - every path, exception handlers
		 * included. An invoke left out runs only where {@code ask} said yes (F077).
		 *
		 * <p>BY PATH, NOT BY PRESENCE. A rule asking only whether the method branches on the answer is
		 * satisfied by guarding one call and adding a second beside it unguarded.
		 */
		public Set<String> reachedUnless(String ask) throws IOException {
			Set<String> out = new LinkedHashSet<>();
			if (code == null) {
				return out;
			}
			int start = 8;
			int length = intAt(code, 4);
			int table = start + length;
			int handlers = ((code[table] & 0xFF) << 8) | (code[table + 1] & 0xFF);
			boolean[] seen = new boolean[length];
			java.util.ArrayDeque<Integer> todo = new java.util.ArrayDeque<>();
			todo.push(0);
			while (!todo.isEmpty()) {
				int at = todo.pop();
				if (at < 0 || at >= length) {
					throw new IOException(this + ": a jump to " + at + " leaves its " + length + " bytes of code");
				}
				if (seen[at]) {
					continue;
				}
				seen[at] = true;
				for (int h = 0; h < handlers; h++) {
					int e = table + 2 + h * 8;
					int from = ((code[e] & 0xFF) << 8) | (code[e + 1] & 0xFF);
					int to = ((code[e + 2] & 0xFF) << 8) | (code[e + 3] & 0xFF);
					if (at >= from && at < to) {
						todo.push(((code[e + 4] & 0xFF) << 8) | (code[e + 5] & 0xFF));
					}
				}
				int pc = start + at;
				int op = code[pc] & 0xFF;
				int next = at + stepAt(code, pc, start);
				String invoked = invokeAt.get(at);
				if (invoked != null) {
					out.add(invoked);
					int branch = next < length ? code[start + next] & 0xFF : -1;
					if (invoked.equals(ask) && (branch == 0x99 || branch == 0x9a)) {
						//the answer branched on: only its "no" edge is walked. ifeq jumps on no, ifne on yes
						todo.push(branch == 0x99 ? next + s2(code, start + next + 1) : next + 3);
						continue;
					}
				}
				if ((op >= 0x99 && op <= 0xa6) || op == 0xc6 || op == 0xc7 || op == 0xa8) {   //if*, jsr
					todo.push(at + s2(code, pc + 1));
					todo.push(next);
				} else if (op == 0xa7) {   //goto
					todo.push(at + s2(code, pc + 1));
				} else if (op == 0xc8 || op == 0xc9) {   //goto_w, jsr_w
					todo.push(at + intAt(code, pc + 1));
					if (op == 0xc9) {
						todo.push(next);
					}
				} else if (op == 0xaa || op == 0xab) {   //tableswitch, lookupswitch
					int p = pc + 1 + ((4 - ((pc - start + 1) % 4)) % 4);
					todo.push(at + intAt(code, p));
					if (op == 0xaa) {
						int cases = intAt(code, p + 8) - intAt(code, p + 4) + 1;
						for (int c = 0; c < cases; c++) {
							todo.push(at + intAt(code, p + 12 + c * 4));
						}
					} else {
						for (int c = 0, pairs = intAt(code, p + 4); c < pairs; c++) {
							todo.push(at + intAt(code, p + 12 + c * 8));
						}
					}
				} else if (!((op >= 0xac && op <= 0xb1) || op == 0xbf || op == 0xa9)) {   //not a return, athrow, ret
					todo.push(next);
				}
			}
			return out;
		}

		/** name + descriptor: what an invoke names, whatever class it was resolved against. */
		public String signature() {
			return name + descriptor;
		}

		@Override
		public String toString() {
			return simpleName(owner) + "." + name + descriptor;
		}
	}

	/** A field a class declares, static or not, with its flags and whether javac inlines its value. */
	public static final class Field {

		public final String owner;
		public final String name;
		public final String descriptor;
		public final int flags;
		/** A compile-time constant (a ConstantValue attribute): javac copies its value into every reader, so
		 *  no instruction ever reads it and "never read" says nothing about it. */
		public final boolean constant;

		Field(String owner, String name, String descriptor, int flags, boolean constant) {
			this.owner = owner;
			this.name = name;
			this.descriptor = descriptor;
			this.flags = flags;
			this.constant = constant;
		}

		public boolean isSynthetic() {
			return (flags & ACC_SYNTHETIC) != 0;
		}

		public boolean isEnumConstant() {
			return (flags & ACC_ENUM) != 0;
		}

		@Override
		public String toString() {
			return simpleName(owner) + "." + name;
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
		/** Every static field it declares, but the synthetic ones and enum constants. */
		public final List<StaticField> statics;
		/** Every method it declares, with what each body invokes. */
		public final List<Method> methods;
		/** name + descriptor of every method a MethodHandle constant names - lambdas and method references. */
		public final Set<String> handles;
		/** Its superclass's internal name (null for java/lang/Object) and its interfaces'. */
		public final String superName;
		public final List<String> interfaces;
		/** Its access flags. */
		public final int flags;
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
		/** The name of every field it declares, static or not, synthetic ones included. */
		public final Set<String> fields;
		/** Every field it declares, with its flags and whether javac inlines it (F204). */
		public final List<Field> declaredFields;

		ClassFile(String name, List<Member> globals, List<StaticField> statics, List<Ref> refs, List<String> strings,
				Set<String> classes, List<Ref> declares, List<Method> methods, Set<String> handles, String superName,
				List<String> interfaces, int flags, Set<String> fields, List<Field> declaredFields) {
			this.name = name;
			this.fields = Collections.unmodifiableSet(fields);
			this.declaredFields = Collections.unmodifiableList(declaredFields);
			this.globals = Collections.unmodifiableList(globals);
			this.statics = Collections.unmodifiableList(statics);
			this.methods = Collections.unmodifiableList(methods);
			this.handles = Collections.unmodifiableSet(handles);
			this.superName = superName;
			this.interfaces = Collections.unmodifiableList(interfaces);
			this.flags = flags;
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
			Set<String> handles = new LinkedHashSet<>();
			for (int i = 1; i < cpCount; i++) {
				if (tag[i] == 15 && (tag[a[i]] == 10 || tag[a[i]] == 11)) {
					int nt = b[a[i]];
					handles.add(utf[a[nt]] + utf[b[nt]]);
				}
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
			int classFlags = in.readUnsignedShort();
			skip(in, 2);                          //this class
			int superIndex = in.readUnsignedShort();
			String superName = superIndex == 0 ? null : utf[a[superIndex]];
			List<String> interfaces = new ArrayList<>();
			for (int n = in.readUnsignedShort(); n > 0; n--) {
				interfaces.add(utf[a[in.readUnsignedShort()]]);
			}
			List<Member> globals = new ArrayList<>();
			List<StaticField> statics = new ArrayList<>();
			Set<String> fields = new LinkedHashSet<>();
			List<Field> declaredFields = new ArrayList<>();
			int fieldCount = in.readUnsignedShort();
			for (int i = 0; i < fieldCount; i++) {
				int flags = in.readUnsignedShort();
				String name = utf[in.readUnsignedShort()];
				fields.add(name);
				String type = utf[in.readUnsignedShort()];
				classesIn(type, classes); //the field's type
				boolean constant = false;
				for (int n = in.readUnsignedShort(); n > 0; n--) {
					constant |= "ConstantValue".equals(utf[in.readUnsignedShort()]);
					skip(in, in.readInt());
				}
				declaredFields.add(new Field(internalName, name, type, flags, constant));
				if ((flags & ACC_STATIC) != 0 && (flags & (ACC_SYNTHETIC | ACC_ENUM)) == 0) {
					if (type == null) {
						throw new IOException(internalName + "." + name + ": its type is not a UTF-8 constant");
					}
					statics.add(new StaticField(internalName, name, type, flags));
				}
				boolean mutableGlobal = (flags & ACC_PUBLIC) != 0 && (flags & ACC_STATIC) != 0
						&& (flags & ACC_FINAL) == 0 && (flags & ACC_SYNTHETIC) == 0;
				if (mutableGlobal) {
					globals.add(new Member(internalName, name));
				}
			}
			List<Ref> declares = new ArrayList<>();
			List<Method> methods = new ArrayList<>();
			int methodCount = in.readUnsignedShort();
			for (int i = 0; i < methodCount; i++) {
				int flags = in.readUnsignedShort();
				String name = utf[in.readUnsignedShort()];
				String descriptor = utf[in.readUnsignedShort()];
				Set<String> invokes = new LinkedHashSet<>();
				Set<String> reads = new LinkedHashSet<>();
				Map<Integer, String> invokeAt = new HashMap<>();
				byte[] code = null;
				for (int n = in.readUnsignedShort(); n > 0; n--) {
					String attribute = utf[in.readUnsignedShort()];
					int length = in.readInt();
					if (!"Code".equals(attribute)) {
						skip(in, length);
						continue;
					}
					code = new byte[length];
					in.readFully(code);
					invokesIn(code, tag, utf, a, b, invokes, reads, invokeAt, internalName + "." + name);
				}
				classesIn(descriptor, classes);
				declares.add(new Ref(internalName, name, descriptor, true));
				methods.add(new Method(internalName, name, descriptor, flags, invokes, reads, code, invokeAt));
			}
			return new ClassFile(internalName, globals, statics, refs, strings, classes, declares, methods, handles,
					superName, interfaces, classFlags, fields, declaredFields);
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

	/** Bytes each opcode takes, operands included; 0 for the three whose length the code must work out. */
	private static final int[] OPCODE_LENGTH = new int[256];

	static {
		java.util.Arrays.fill(OPCODE_LENGTH, 1);
		for (int op : new int[]{0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc}) {
			OPCODE_LENGTH[op] = 2;
		}
		for (int op : new int[]{0x11, 0x13, 0x14, 0x84, 0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7}) {
			OPCODE_LENGTH[op] = 3;
		}
		for (int op = 0x99; op <= 0xa8; op++) {
			OPCODE_LENGTH[op] = 3;   //the conditional branches, goto and jsr
		}
		for (int op = 0xb2; op <= 0xb8; op++) {
			OPCODE_LENGTH[op] = 3;   //the field accesses and three of the invokes
		}
		OPCODE_LENGTH[0xb9] = 5;     //invokeinterface
		OPCODE_LENGTH[0xba] = 5;     //invokedynamic
		OPCODE_LENGTH[0xc5] = 4;     //multianewarray
		OPCODE_LENGTH[0xc8] = 5;     //goto_w
		OPCODE_LENGTH[0xc9] = 5;     //jsr_w
		OPCODE_LENGTH[0xaa] = 0;     //tableswitch
		OPCODE_LENGTH[0xab] = 0;     //lookupswitch
		OPCODE_LENGTH[0xc4] = 0;     //wide
	}

	/**
	 * The invokes in one Code attribute: max_stack, max_locals, code_length and
	 * the code, walked opcode by opcode. A walk that runs off the end of the code
	 * is an IOException: a body read wrong would report a method calling nothing.
	 */
	private static void invokesIn(byte[] attr, int[] tag, String[] utf, int[] a, int[] b, Set<String> out,
			Set<String> reads, Map<Integer, String> invokeAt, String where) throws IOException {
		int length = ((attr[4] & 0xFF) << 24) | ((attr[5] & 0xFF) << 16) | ((attr[6] & 0xFF) << 8) | (attr[7] & 0xFF);
		int start = 8;
		int end = start + length;
		int pc = start;
		while (pc < end) {
			int op = attr[pc] & 0xFF;
			if (op >= 0xb6 && op <= 0xb9) {
				int ref = ((attr[pc + 1] & 0xFF) << 8) | (attr[pc + 2] & 0xFF);
				int nt = b[ref];
				String invoked = utf[a[a[ref]]] + "#" + utf[a[nt]] + utf[b[nt]];
				out.add(invoked);
				invokeAt.put(pc - start, invoked);
			} else if (op == 0xb2 || op == 0xb4) {   //getstatic, getfield; 0xb3 and 0xb5 write
				int ref = ((attr[pc + 1] & 0xFF) << 8) | (attr[pc + 2] & 0xFF);
				reads.add(utf[a[a[ref]]] + "#" + utf[a[b[ref]]]);
			}
			pc += stepAt(attr, pc, start);
		}
		if (pc != end) {
			throw new IOException(where + ": its code did not walk to its end (" + (pc - start) + " of " + length + ")");
		}
	}

	/** The length of the instruction at {@code pc}, the code starting at {@code start}: switches and wide by their operands. */
	private static int stepAt(byte[] attr, int pc, int start) {
		int op = attr[pc] & 0xFF;
		if (op == 0xaa || op == 0xab) {
			int p = pc + 1 + ((4 - ((pc - start + 1) % 4)) % 4);
			if (op == 0xaa) {
				int low = intAt(attr, p + 4);
				int high = intAt(attr, p + 8);
				return p + 12 + (high - low + 1) * 4 - pc;
			}
			return p + 8 + intAt(attr, p + 4) * 8 - pc;
		}
		if (op == 0xc4) {
			return (attr[pc + 1] & 0xFF) == 0x84 ? 6 : 4;
		}
		return OPCODE_LENGTH[op];
	}

	/** A signed two-byte branch offset. */
	private static int s2(byte[] x, int at) {
		return (short) (((x[at] & 0xFF) << 8) | (x[at + 1] & 0xFF));
	}

	private static int intAt(byte[] x, int at) {
		return ((x[at] & 0xFF) << 24) | ((x[at + 1] & 0xFF) << 16) | ((x[at + 2] & 0xFF) << 8) | (x[at + 3] & 0xFF);
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
	 * a member edge behind it. {@code implements ctrmap/formats/MapObject}
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
