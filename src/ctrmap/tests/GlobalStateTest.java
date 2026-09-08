package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.gamedef.GameType;
import java.io.File;
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
 * whose paths, archive handles and {@code GameType} were read from everywhere.
 * That state is a {@link ctrmap.WorkspaceSession} instance now, with
 * Workspace's old statics delegating to the current one while callers are
 * migrated ({@code WorkspaceSessionTest} counts them). What this suite does
 * is hold the line on everything else, and make the two failure modes global
 * state actually causes here impossible to reintroduce quietly.
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
 * <p>The class file reader this suite was built around now lives in
 * {@link ClassFileScanner}, so every structural guard reads the same bytecode
 * instead of each inventing a regular expression over source text.
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
 * Workspace holds the settings and the current session, so "state left
 * behind" bites there first. {@code reset()} puts all of it back. A reset
 * that silently misses a field is worse than no reset - it reads as a clean
 * slate while one workspace leaks into the next - so this enumerates
 * Workspace's static fields by reflection and fails when {@code reset()}
 * does not name one. Every field whose value can be constructed here is
 * additionally dirtied for real and checked, which is what proves those
 * lines do something rather than merely being present; the session is
 * installed and must be gone afterwards, since everything the old fields
 * held now hangs off it.
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
	 * 110 once the open game moved out of Workspace's statics into a
	 * WorkspaceSession instance (ten archive files, seventeen GARC handles,
	 * the persist file, the GameType, the valid flag, and a music-name table
	 * nothing ever filled).
	 *
	 * 103 once the main window's 38 menu statics became locals of the one
	 * builder that makes the menu bar; 88 once its tool row's sixteen became
	 * one owner, WorldEditorToolbar; 75 once the panel plumbing nothing else
	 * read became locals or private; 72 once the tab switch and the split
	 * pane became methods the window offers instead of fields it exposes
	 * (MainframeShapeTest ratchets that class on its own).
	 *
	 * <p>Independently of the two reductions above, from the same 141:
	 * 140 once AreaForkPrompt handed its fork result back instead of
	 * remembering it; 139 once the natives table a SYSREQ_N name resolves in
	 * rode on the PawnAssembly instead of on PawnInstruction; 138 once
	 * LocationNames' table went private behind an accessor that loads it.
	 *
	 * <p>MERGED 2026-09-07: the workspace, window and debt reductions all
	 * landed on one tree. Measured by this suite on that build and set to
	 * exactly that. Merge 2 wrote "measured 41" here and left the constant
	 * at 72 - a ceiling 31 above the truth cannot fail on a real regression -
	 * so this number was proven by breaking: one below it fails, naming the
	 * count, before it was set.
	 */
	//38 -> 36: the decoupling steps deleted the window's held tool and made its
	//frame private, both of which were public and mutable.
	private static final int CEILING = 36;

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

		List<ClassFileScanner.Member> globals = ceiling(classes);
		neverWritten(src, globals);
		resetCoversWorkspace(src);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	// ---------------------------------------------------------------- 1. ceiling
	static List<ClassFileScanner.Member> ceiling(File classes) throws IOException {
		if (!classes.isDirectory()) {
			//NOT a skip. A guard that quietly measures nothing and prints ALL
			//PASS is the shape of failure this battery exists to refuse.
			check(false, "there is a compiled program to count globals in - none at " + classes
					+ " (run build.ps1, or pass the classes root as args[1])");
			return new ArrayList<>();
		}
		List<ClassFileScanner.Member> globals = new ArrayList<>();
		for (ClassFileScanner.ClassFile cf : ClassFileScanner.application(classes)) {
			globals.addAll(cf.globals);
		}
		Map<String, Integer> perClass = new LinkedHashMap<>();
		for (ClassFileScanner.Member g : globals) {
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

	// ------------------------------------------------- 2. a global nothing writes
	static void neverWritten(File src, List<ClassFileScanner.Member> globals) throws IOException {
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
		for (ClassFileScanner.Member g : globals) {
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
		//What is left static here is the settings, the current session and a
		//once-per-session flag. The open game itself - paths, GameType, archive
		//files, GARC handles, edited-file list - is a WorkspaceSession instance
		//now, and WorkspaceSessionTest holds that class to having no static
		//mutable field at all. Eight is the whole list; a ninth is a decision.
		check(statics.size() <= 8, "Workspace keeps only the settings, the current session and its"
				+ " reporting flag in statics (" + statics.size() + " of them)");

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
		//the session: reset() must drop it, because everything the old fields
		//held now hangs off it - one leaked session is every old leak at once
		Workspace.install(new ctrmap.WorkspaceSession(new File("dirty-ws"), new File("dirty-game"),
				GameType.ORAS, null));
		check(dirtied.size() >= 5, "enough of Workspace's statics can be dirtied here for the check to mean something ("
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
		check(Workspace.session() == null && !Workspace.isValid() && Workspace.game() == null,
				"including the current session, which is dropped so no archive of it is reachable");
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
