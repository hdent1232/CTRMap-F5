package ctrmap.tests;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Method bodies out of compiled classes: every call, every object made, every lambda bound and
 * every static touched, each with the source line it came from - what a reachability rule has to
 * follow and a grep of the source cannot (F220: 233 of the 251 Swing calls made off the event
 * thread were reached through calls, not written in a worker's body).
 *
 * <p>Not: ClassFileScanner - that reads a class's constant pool and member tables, which says
 * WHAT a class names, not which method calls which. A call graph needs every method's code, its
 * line table and the bootstrap methods its lambdas are bound by; the guards built on the scanner
 * need none of that, and it stops at the member table to stay fast for them.
 *
 * <p>IT CANNOT MISREAD QUIETLY. Every method's code is decoded instruction by instruction, and a
 * decode that does not end exactly at the code's length - a wrong instruction size anywhere, which
 * would shift every call after it onto garbage - throws, naming the method. A reader that skipped
 * what it could not decode would report a clean method.
 */
public final class Bytecode {

	public static final int INVOKEVIRTUAL = 0xb6, INVOKESPECIAL = 0xb7, INVOKESTATIC = 0xb8,
			INVOKEINTERFACE = 0xb9, INVOKEDYNAMIC = 0xba, NEW = 0xbb, GETSTATIC = 0xb2, PUTSTATIC = 0xb3;

	/** One instruction that names another class's member, a class, or a lambda. */
	public static final class Insn {

		public final int pc;
		public final int op;
		/** The class named: the member's owner, or the class a {@code new} makes. */
		public final String owner;
		/** The member's name; for {@code invokedynamic}, the interface method the lambda implements. */
		public final String name;
		/** The member's descriptor; for {@code invokedynamic}, the call site's (its return type is the interface). */
		public final String desc;
		/** For {@code invokedynamic}: the bootstrap method's index; -1 otherwise. */
		final int bsm;
		/** For {@code invokedynamic} bound by LambdaMetafactory: {owner, name, desc} of the body; else null. */
		public String[] lambda;

		Insn(int pc, int op, String owner, String name, String desc, int bsm) {
			this.pc = pc;
			this.op = op;
			this.owner = owner;
			this.name = name;
			this.desc = desc;
			this.bsm = bsm;
		}

		public boolean isInvoke() {
			return op >= INVOKEVIRTUAL && op <= INVOKEINTERFACE;
		}

		/** The interface type an {@code invokedynamic} call site produces. */
		public String producedType() {
			int r = desc.lastIndexOf(')');
			String t = desc.substring(r + 1);
			return t.startsWith("L") ? t.substring(1, t.length() - 1) : t;
		}

		@Override
		public String toString() {
			return String.format("%d:%02x %s.%s%s", pc, op, owner, name, desc == null ? "" : desc);
		}
	}

	/** One method or constructor with its code. */
	public static final class Method {

		public final Cls cls;
		public final String name;
		public final String desc;
		public final int access;
		/** Its member-naming instructions in order; empty for an abstract or native method. */
		public final List<Insn> code = new ArrayList<>();
		/** True when it has a body at all. */
		public boolean hasCode;
		private final TreeMap<Integer, Integer> lines = new TreeMap<>();

		Method(Cls cls, String name, String desc, int access) {
			this.cls = cls;
			this.name = name;
			this.desc = desc;
			this.access = access;
		}

		public boolean isStatic() {
			return (access & 0x0008) != 0;
		}

		/** The source line {@code pc} is on, or -1 when the class was built without a line table. */
		public int line(int pc) {
			Map.Entry<Integer, Integer> e = lines.floorEntry(pc);
			return e == null ? -1 : e.getValue();
		}

		public String key() {
			return name + desc;
		}

		@Override
		public String toString() {
			return cls.name + "." + name + desc;
		}
	}

	/** One compiled class. */
	public static final class Cls {

		public final String name;
		public String superName;
		public final List<String> interfaces = new ArrayList<>();
		public int access;
		public String source;
		/** Keyed by name + descriptor. */
		public final Map<String, Method> methods = new LinkedHashMap<>();

		Cls(String name) {
			this.name = name;
		}

		public boolean isInterface() {
			return (access & 0x0200) != 0;
		}

		/** "ctrmap/humaninterface/ZoneLoadingPanel.java", for a report a reader can open. */
		public String file() {
			int slash = name.lastIndexOf('/');
			return (slash < 0 ? "" : name.substring(0, slash + 1)) + (source == null ? "?" : source);
		}
	}

	private Bytecode() {
	}

	/** Every class under a compiled tree, keyed by internal name. */
	public static Map<String, Cls> read(File classesRoot) throws IOException {
		Map<String, Cls> out = new TreeMap<>();
		collect(classesRoot, out);
		return out;
	}

	private static void collect(File dir, Map<String, Cls> out) throws IOException {
		File[] kids = dir.listFiles();
		if (kids == null) {
			throw new IOException("cannot list " + dir); //an unreadable directory is not an empty one
		}
		for (File f : kids) {
			if (f.isDirectory()) {
				collect(f, out);
			} else if (f.getName().endsWith(".class")) {
				Cls c = readClass(f);
				out.put(c.name, c);
			}
		}
	}

	/** One class file, every method's code decoded. */
	public static Cls readClass(File f) throws IOException {
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
			if (in.readInt() != 0xCAFEBABE) {
				throw new IOException("not a class file: " + f);
			}
			in.readUnsignedShort();
			in.readUnsignedShort();
			int n = in.readUnsignedShort();
			int[] tag = new int[n];
			String[] utf = new String[n];
			int[] a = new int[n];
			int[] b = new int[n];
			for (int i = 1; i < n; i++) {
				tag[i] = in.readUnsignedByte();
				switch (tag[i]) {
					case 1:
						utf[i] = in.readUTF();
						break;
					case 7: case 8: case 16: case 19: case 20:
						a[i] = in.readUnsignedShort();
						break;
					case 15: //MethodHandle: kind, then the reference
						b[i] = in.readUnsignedByte();
						a[i] = in.readUnsignedShort();
						break;
					case 9: case 10: case 11: case 12: case 17: case 18:
						a[i] = in.readUnsignedShort();
						b[i] = in.readUnsignedShort();
						break;
					case 3: case 4:
						in.readInt();
						break;
					case 5: case 6:
						in.readLong();
						i++;
						break;
					default:
						throw new IOException("unknown constant pool tag " + tag[i] + " in " + f);
				}
			}
			Pool pool = new Pool(tag, utf, a, b);
			int access = in.readUnsignedShort();
			Cls c = new Cls(pool.className(in.readUnsignedShort()));
			c.access = access;
			int sup = in.readUnsignedShort();
			c.superName = sup == 0 ? null : pool.className(sup);
			int ifaces = in.readUnsignedShort();
			for (int i = 0; i < ifaces; i++) {
				c.interfaces.add(pool.className(in.readUnsignedShort()));
			}
			int fields = in.readUnsignedShort();
			for (int i = 0; i < fields; i++) {
				in.readUnsignedShort();
				in.readUnsignedShort();
				in.readUnsignedShort();
				skipAttributes(in);
			}
			int methods = in.readUnsignedShort();
			for (int i = 0; i < methods; i++) {
				int macc = in.readUnsignedShort();
				Method m = new Method(c, utf[in.readUnsignedShort()], utf[in.readUnsignedShort()], macc);
				int attrs = in.readUnsignedShort();
				for (int k = 0; k < attrs; k++) {
					String an = utf[in.readUnsignedShort()];
					int len = in.readInt();
					if (an.equals("Code")) {
						readCode(in, m, pool, f);
					} else {
						skip(in, len);
					}
				}
				c.methods.put(m.key(), m);
			}
			List<String[]> bootstraps = new ArrayList<>();
			int attrs = in.readUnsignedShort();
			for (int k = 0; k < attrs; k++) {
				String an = utf[in.readUnsignedShort()];
				int len = in.readInt();
				if (an.equals("SourceFile")) {
					c.source = utf[in.readUnsignedShort()];
				} else if (an.equals("BootstrapMethods")) {
					int count = in.readUnsignedShort();
					for (int j = 0; j < count; j++) {
						String[] bodyOf = null;
						String[] factory = pool.handle(in.readUnsignedShort());
						int args = in.readUnsignedShort();
						for (int q = 0; q < args; q++) {
							int arg = in.readUnsignedShort();
							if (tag[arg] == 15 && bodyOf == null) {
								bodyOf = pool.handle(arg);
							}
						}
						//only LambdaMetafactory binds a body; another bootstrap (string concatenation,
						//a record's methods) makes no call a reader of this class could follow
						bootstraps.add(factory[0].equals("java/lang/invoke/LambdaMetafactory") ? bodyOf : null);
					}
				} else {
					skip(in, len);
				}
			}
			for (Method m : c.methods.values()) {
				for (Insn x : m.code) {
					if (x.op == INVOKEDYNAMIC) {
						if (x.bsm >= bootstraps.size()) {
							throw new IOException("invokedynamic names bootstrap " + x.bsm + " of " + bootstraps.size() + " in " + m);
						}
						x.lambda = bootstraps.get(x.bsm);
					}
				}
			}
			return c;
		}
	}

	/** Resolves constant pool entries by index. */
	private static final class Pool {

		final int[] tag;
		final String[] utf;
		final int[] a;
		final int[] b;

		Pool(int[] tag, String[] utf, int[] a, int[] b) {
			this.tag = tag;
			this.utf = utf;
			this.a = a;
			this.b = b;
		}

		String className(int i) {
			return utf[a[i]];
		}

		/** {owner, name, desc} of a Fieldref, Methodref or InterfaceMethodref. */
		String[] member(int i) {
			int nt = b[i];
			return new String[]{className(a[i]), utf[a[nt]], utf[b[nt]]};
		}

		/** {owner, name, desc} a MethodHandle points at. */
		String[] handle(int i) {
			if (tag[i] != 15) {
				throw new IllegalStateException("constant " + i + " is not a method handle");
			}
			return member(a[i]);
		}
	}

	private static void readCode(DataInputStream in, Method m, Pool pool, File f) throws IOException {
		m.hasCode = true;
		in.readUnsignedShort();
		in.readUnsignedShort();
		int length = in.readInt();
		byte[] code = new byte[length];
		in.readFully(code);
		int pc = 0;
		while (pc < length) {
			int op = code[pc] & 0xff;
			int size = size(code, pc, op, m);
			switch (op) {
				case INVOKEVIRTUAL: case INVOKESPECIAL: case INVOKESTATIC: case INVOKEINTERFACE:
				case GETSTATIC: case PUTSTATIC: {
					String[] r = pool.member(u2(code, pc + 1));
					m.code.add(new Insn(pc, op, r[0], r[1], r[2], -1));
					break;
				}
				case INVOKEDYNAMIC: {
					int idx = u2(code, pc + 1);
					int nt = pool.b[idx];
					m.code.add(new Insn(pc, op, null, pool.utf[pool.a[nt]], pool.utf[pool.b[nt]], pool.a[idx]));
					break;
				}
				case NEW:
					m.code.add(new Insn(pc, op, pool.className(u2(code, pc + 1)), null, null, -1));
					break;
				default:
					break;
			}
			pc += size;
		}
		if (pc != length) {
			throw new IOException("decoding " + m + " in " + f + " ended at " + pc + ", past its code length " + length);
		}
		int handlers = in.readUnsignedShort();
		skip(in, 8L * handlers);
		int attrs = in.readUnsignedShort();
		for (int k = 0; k < attrs; k++) {
			String an = pool.utf[in.readUnsignedShort()];
			int len = in.readInt();
			if (an.equals("LineNumberTable")) {
				int count = in.readUnsignedShort();
				for (int j = 0; j < count; j++) {
					int start = in.readUnsignedShort();
					m.lines.put(start, in.readUnsignedShort());
				}
			} else {
				skip(in, len);
			}
		}
	}

	/** The size in bytes of the instruction at {@code pc}, opcode included. */
	static int size(byte[] code, int pc, int op, Method m) throws IOException {
		if (op <= 0x0f) {
			return 1;                                 //nop .. dconst_1
		}
		switch (op) {
			case 0x10: return 2;                      //bipush
			case 0x11: return 3;                      //sipush
			case 0x12: return 2;                      //ldc
			case 0x13: case 0x14: return 3;           //ldc_w, ldc2_w
			case 0x84: return 3;                      //iinc
			case 0xa9: return 2;                      //ret
			case 0xbc: return 2;                      //newarray
			case 0xb9: case 0xba: return 5;           //invokeinterface, invokedynamic
			case 0xc5: return 4;                      //multianewarray
			case 0xc8: case 0xc9: return 5;           //goto_w, jsr_w
			case 0xc4:                                //wide: an iinc carries a 16-bit constant too
				return (code[pc + 1] & 0xff) == 0x84 ? 6 : 4;
			case 0xaa: {                              //tableswitch
				int p = pc + 1 + ((4 - ((pc + 1) % 4)) % 4);
				int low = s4(code, p + 4), high = s4(code, p + 8);
				return p + 12 + 4 * (high - low + 1) - pc;
			}
			case 0xab: {                              //lookupswitch
				int p = pc + 1 + ((4 - ((pc + 1) % 4)) % 4);
				return p + 8 + 8 * s4(code, p + 4) - pc;
			}
			default:
				break;
		}
		if (op >= 0x15 && op <= 0x19 || op >= 0x36 && op <= 0x3a) {
			return 2;                                 //the indexed loads and stores
		}
		if (op >= 0x1a && op <= 0x35 || op >= 0x3b && op <= 0x83 || op >= 0x85 && op <= 0x98
				|| op >= 0xac && op <= 0xb1 || op == 0xbe || op == 0xbf || op == 0xc2 || op == 0xc3) {
			return 1;
		}
		if (op >= 0x99 && op <= 0xa8 || op >= 0xb2 && op <= 0xb8 || op == 0xbb || op == 0xbd
				|| op == 0xc0 || op == 0xc1 || op == 0xc6 || op == 0xc7) {
			return 3;
		}
		throw new IOException("unknown opcode 0x" + Integer.toHexString(op) + " at " + pc + " in " + m);
	}

	private static int u2(byte[] c, int p) {
		return ((c[p] & 0xff) << 8) | (c[p + 1] & 0xff);
	}

	private static int s4(byte[] c, int p) {
		return ((c[p] & 0xff) << 24) | ((c[p + 1] & 0xff) << 16) | ((c[p + 2] & 0xff) << 8) | (c[p + 3] & 0xff);
	}

	private static void skipAttributes(DataInputStream in) throws IOException {
		int n = in.readUnsignedShort();
		for (int i = 0; i < n; i++) {
			in.readUnsignedShort();
			skip(in, in.readInt() & 0xffffffffL);
		}
	}

	private static void skip(DataInputStream in, long n) throws IOException {
		while (n > 0) {
			long s = in.skip(n);
			if (s <= 0) {
				throw new IOException("class file ends early");
			}
			n -= s;
		}
	}
}
