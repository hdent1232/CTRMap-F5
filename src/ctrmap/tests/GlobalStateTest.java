package ctrmap.tests;

import ctrmap.Workspace;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What the program keeps in {@code public static} fields, and what stops that
 * growing.
 *
 * <p>CTRMap is built around global mutable state - {@link Workspace} above all,
 * whose paths, archive handles and {@code GameType} are read from everywhere.
 * De-globalising it is a rewrite of every file and is deliberately NOT
 * attempted. What this suite does instead is hold the line, and make the two
 * failure modes global state actually causes here impossible to reintroduce
 * quietly.
 *
 * <h2>1. The ceiling (a ratchet)</h2>
 * A count of every {@code public static} non-final field in the compiled
 * program outside {@code ctrmap.tests}, which fails when it RISES above
 * {@link #CEILING}. Like the mutation baseline, it cannot force the number
 * down; it stops it drifting up unnoticed. Every one of these is a value any
 * code anywhere can change, so each is a reason a feature cannot be exercised
 * without dragging the rest of the program in, and each is a way one piece of
 * work can disturb another inside one JVM. Adding one should be a decision
 * somebody made on purpose, which is what having to edit the number here
 * forces.
 *
 * <p>Measured from {@code build/classes} rather than from the source text,
 * because that is the measurement that cannot be fooled: it counts fields, not
 * lines, so a multi-declarator line counts once per field; and it never
 * mistakes {@code public static class Foo} for a field, nor an interface
 * constant (implicitly final) for a mutable one. Grepping the sources for
 * "public static" without "final" reports 214 for the same tree this counts
 * 146 in, and 76 of those 214 are type declarations.
 *
 * <h2>2. A global nothing ever writes</h2>
 * {@code PaintedRegionBuilder.terrainCovered} was a {@code public static
 * boolean[][]} whose javadoc described when "the terrain-kit composer" set it
 * and cleared it. No such composer was ever written. The field was null for
 * the life of every run, so the branch reading it could not be taken, and a
 * reader had every reason to believe a mechanism existed that did not. A
 * public static field nothing in the program assigns is not a hook: it is dead
 * state advertising a feature. The rule is deliberately blunt about what
 * counts as an assignment - any {@code name =} anywhere in the sources - so it
 * can only fire on a field that truly nothing writes.
 *
 * <h2>3. {@link Workspace#reset} covers every field</h2>
 * Workspace is the god object, so "state left behind" bites there first.
 * {@code reset()} puts all of it back. A reset that silently misses a field is
 * worse than no reset - it reads as a clean slate while one workspace leaks
 * into the next - so this enumerates Workspace's static fields by reflection
 * and fails when {@code reset()} does not name one. Every field whose value
 * can be constructed here is additionally dirtied for real and checked, which
 * is what proves those lines do something rather than merely being present.
 *
 * <p>ORDER: this suite needs no dump, no workspace and no scratch space, and
 * writes no file. It can run first, last, or alone.
 *
 * Usage: java ctrmap.tests.GlobalStateTest [src-root] [classes-root]
 */
public class GlobalStateTest {

	/**
	 * The number of public static mutable fields outside ctrmap.tests, as left
	 * on 2026-09-06. RAISE THIS ONLY DELIBERATELY, and say in the commit
	 * message which global was added and why it had to be one.
	 *
	 * <p>146 when this suite was written; 141 after five that did not need to
	 * be mutable globals stopped being them (one dead, two private, two final);
	 * 103 once the main window's 38 menu statics became locals of the one
	 * builder that makes the menu bar; 88 once its tool row's sixteen became
	 * one owner, WorldEditorToolbar (MainframeShapeTest ratchets that class
	 * on its own).
	 */
	private static final int CEILING = 88;

	private static final int ACC_PUBLIC = 0x0001;
	private static final int ACC_STATIC = 0x0008;
	private static final int ACC_FINAL = 0x0010;
	private static final int ACC_SYNTHETIC = 0x1000;

	static int fails = 0;

	static void check(boolean cond, String what) {
		if (!cond) {
			System.out.println("  FAIL: " + what);
			fails++;
		} else {
			System.out.println("  ok: " + what);
		}
	}

	public static void main(String[] args) throws Exception {
		File src = new File(args.length > 0 ? args[0] : "src");
		File classes = new File(args.length > 1 ? args[1] : "build/classes");

		List<Global> globals = ceiling(classes);
		neverWritten(src, globals);
		resetCoversWorkspace(src);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** One public static mutable field, as its class file records it. */
	static final class Global {

		final String owner;   //e.g. ctrmap/humaninterface/Selector
		final String name;

		Global(String owner, String name) {
			this.owner = owner;
			this.name = name;
		}

		String simpleOwner() {
			String s = owner.substring(owner.lastIndexOf('/') + 1);
			int dollar = s.indexOf('$');
			return dollar < 0 ? s : s.substring(0, dollar);
		}
	}

	// ---------------------------------------------------------------- 1. ceiling
	static List<Global> ceiling(File classes) throws IOException {
		if (!classes.isDirectory()) {
			//NOT a skip. A guard that quietly measures nothing and prints ALL
			//PASS is the shape of failure this battery exists to refuse.
			check(false, "there is a compiled program to count globals in - none at " + classes
					+ " (run build.ps1, or pass the classes root as args[1])");
			return new ArrayList<>();
		}
		List<Global> globals = new ArrayList<>();
		collect(classes, classes, globals);
		Map<String, Integer> perClass = new LinkedHashMap<>();
		for (Global g : globals) {
			String k = g.simpleOwner();
			perClass.put(k, perClass.containsKey(k) ? perClass.get(k) + 1 : 1);
		}
		List<Map.Entry<String, Integer>> top = new ArrayList<>(perClass.entrySet());
		Collections.sort(top, new Comparator<Map.Entry<String, Integer>>() {
			@Override
			public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
				return b.getValue() - a.getValue();
			}
		});
		StringBuilder where = new StringBuilder();
		for (int i = 0; i < Math.min(5, top.size()); i++) {
			where.append(i > 0 ? ", " : "").append(top.get(i).getKey()).append(' ').append(top.get(i).getValue());
		}
		System.out.println("  public static mutable fields outside ctrmap.tests: " + globals.size()
				+ " in " + perClass.size() + " classes (" + where + ")");
		check(globals.size() <= CEILING,
				"the count of public static mutable fields has not risen above " + CEILING
				+ " (it is " + globals.size() + ")");
		return globals;
	}

	private static void collect(File root, File dir, List<Global> out) throws IOException {
		File[] kids = dir.listFiles();
		if (kids == null) {
			return;
		}
		for (File f : kids) {
			if (f.isDirectory()) {
				collect(root, f, out);
			} else if (f.getName().endsWith(".class")) {
				String rel = root.toURI().relativize(f.toURI()).getPath();
				if (rel.startsWith("ctrmap/tests/")) {
					continue; //a suite's own scaffolding is not the program's state
				}
				readFields(f, rel.substring(0, rel.length() - ".class".length()), out);
			}
		}
	}

	/**
	 * The public static non-final fields of one class file. Reads the class
	 * file rather than loading the class: loading runs static initialisers,
	 * and some of these classes build windows or reach for the game when they
	 * initialise.
	 */
	private static void readFields(File f, String owner, List<Global> out) throws IOException {
		DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)));
		try {
			if (in.readInt() != 0xCAFEBABE) {
				return;
			}
			skip(in, 4); //minor, major
			int cpCount = in.readUnsignedShort();
			String[] utf = new String[cpCount];
			for (int i = 1; i < cpCount; i++) {
				int tag = in.readUnsignedByte();
				switch (tag) {
					case 1:
						utf[i] = in.readUTF();
						break;
					case 7: case 8: case 16: case 19: case 20:
						skip(in, 2);
						break;
					case 15:
						skip(in, 3);
						break;
					case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
						skip(in, 4);
						break;
					case 5: case 6:
						skip(in, 8);
						i++; //a long or double eats two constant pool slots
						break;
					default:
						throw new IOException("unknown constant pool tag " + tag + " in " + f);
				}
			}
			skip(in, 6);                          //access flags, this class, super class
			skip(in, 2 * in.readUnsignedShort()); //interfaces
			int fieldCount = in.readUnsignedShort();
			for (int i = 0; i < fieldCount; i++) {
				int flags = in.readUnsignedShort();
				String name = utf[in.readUnsignedShort()];
				skip(in, 2); //descriptor
				skipAttributes(in);
				boolean mutableGlobal = (flags & ACC_PUBLIC) != 0 && (flags & ACC_STATIC) != 0
						&& (flags & ACC_FINAL) == 0 && (flags & ACC_SYNTHETIC) == 0;
				if (mutableGlobal) {
					out.add(new Global(owner, name));
				}
			}
		} finally {
			in.close();
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

	// ------------------------------------------------- 2. a global nothing writes
	static void neverWritten(File src, List<Global> globals) throws IOException {
		if (globals.isEmpty() || !src.isDirectory()) {
			check(false, "there are sources at " + src + " and globals to cross-check against them");
			return;
		}
		StringBuilder all = new StringBuilder();
		for (File f : javaFiles(src)) {
			all.append(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).append('\n');
		}
		String text = all.toString();
		List<String> dead = new ArrayList<>();
		for (Global g : globals) {
			//Deliberately blunt: ANY "name =" (or ++/--/+=) anywhere in any source
			//counts, even a local of the same name in an unrelated file. A guard
			//that fires wrongly is worse than one that misses a case, and the
			//field this exists for was written by nothing whatsoever.
			Pattern p = Pattern.compile("\\b" + Pattern.quote(g.name)
					+ "\\s*(?:=(?!=)|\\+\\+|--|\\+=|-=|\\|=|&=)");
			if (!p.matcher(text).find()) {
				dead.add(g.simpleOwner() + "." + g.name);
			}
		}
		check(dead.isEmpty(), "every public static mutable field is written by something"
				+ (dead.isEmpty() ? "" : " - these are not, so they are dead state advertising a feature: " + dead));
	}

	private static List<File> javaFiles(File dir) {
		List<File> out = new ArrayList<>();
		File[] kids = dir.listFiles();
		if (kids == null) {
			return out;
		}
		for (File f : kids) {
			if (f.isDirectory()) {
				out.addAll(javaFiles(f));
			} else if (f.getName().endsWith(".java")) {
				out.add(f);
			}
		}
		return out;
	}

	// ------------------------------------------------ 3. reset() covers Workspace
	static void resetCoversWorkspace(File src) throws Exception {
		List<Field> statics = new ArrayList<>();
		for (Field f : Workspace.class.getDeclaredFields()) {
			int m = f.getModifiers();
			if (Modifier.isStatic(m) && !Modifier.isFinal(m) && !f.isSynthetic()) {
				f.setAccessible(true);
				statics.add(f);
			}
		}
		check(statics.size() > 20, "Workspace still keeps the open game in statics ("
				+ statics.size() + " of them), so this is the right class to hold to a reset");

		//(a) every one of them is named in reset()'s own body
		File ws = new File(src, "ctrmap/Workspace.java");
		String body = ws.isFile()
				? methodBody(new String(Files.readAllBytes(ws.toPath()), StandardCharsets.UTF_8),
						"public static void reset() {")
				: null;
		List<String> missing = new ArrayList<>();
		for (Field f : statics) {
			Pattern p = Pattern.compile("\\b" + Pattern.quote(f.getName())
					+ "\\s*(?:=(?!=)|\\.clear\\s*\\()");
			if (body == null || !p.matcher(body).find()) {
				missing.add(f.getName());
			}
		}
		check(body != null && missing.isEmpty(),
				"Workspace.reset() puts back every static Workspace owns"
				+ (body == null ? " - no reset() found in " + ws
						: missing.isEmpty() ? "" : " - it never touches " + missing));

		//(b) and those lines really do it, for every field whose value can be
		//    built here (a GARC needs a real archive on disk; the rest do not)
		Map<Field, Object> startup = new LinkedHashMap<>();
		List<Field> dirtied = new ArrayList<>();
		for (Field f : statics) {
			startup.put(f, f.get(null));
			Object dirty = dirtyValue(f, f.get(null));
			if (dirty != NOT_CONSTRUCTIBLE) {
				f.set(null, dirty);
				dirtied.add(f);
			}
		}
		//the one collection: reset() must EMPTY it, because the renderer-style
		//trap applies here too - anything holding the list would keep the old one
		Workspace.persist_paths.add("left over from an earlier workspace");
		check(dirtied.size() >= 15, "enough of Workspace's statics can be dirtied here for the check to mean something ("
				+ dirtied.size() + ")");

		Workspace.reset();

		List<String> survived = new ArrayList<>();
		for (Field f : dirtied) {
			Object now = f.get(null);
			Object was = startup.get(f);
			if (was == null ? now != null : !was.equals(now)) {
				survived.add(f.getName() + " (" + was + " -> " + now + ")");
			}
		}
		check(survived.isEmpty(), "and dirtying them and calling reset() really does put them back"
				+ (survived.isEmpty() ? "" : " - these survived it: " + survived));
		check(Workspace.persist_paths.isEmpty(),
				"including the persisted-paths list, which is emptied rather than replaced");
	}

	private static final Object NOT_CONSTRUCTIBLE = new Object();

	/** A value distinguishable from {@code current}, or NOT_CONSTRUCTIBLE. */
	private static Object dirtyValue(Field f, Object current) {
		Class<?> t = f.getType();
		if (t == String.class) {
			return "dirty";
		}
		if (t == boolean.class) {
			return !((Boolean) current);
		}
		if (t == int.class) {
			return ((Integer) current) + 1;
		}
		if (t == File.class) {
			return new File("dirty");
		}
		if (t == String[].class) {
			return new String[]{"dirty"};
		}
		if (t.isEnum()) {
			Object[] vs = t.getEnumConstants();
			return vs.length > 0 && vs[0] != current ? vs[0] : NOT_CONSTRUCTIBLE;
		}
		return NOT_CONSTRUCTIBLE;
	}

	/** A method's text, from its signature to its matching close brace. */
	static String methodBody(String source, String signature) {
		int at = source.indexOf(signature);
		if (at < 0) {
			return null;
		}
		int i = at + signature.length();
		int depth = 1;
		while (i < source.length() && depth > 0) {
			char c = source.charAt(i);
			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
			}
			i++;
		}
		return source.substring(at, i);
	}
}
