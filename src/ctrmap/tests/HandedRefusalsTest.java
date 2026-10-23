package ctrmap.tests;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every refusal in the editor that something "must be handed" something, asked - found by walking the source.
 *
 * <p>WHY THIS SUITE EXISTS. A class handed nothing where it needs a collaborator refuses in words, rather than
 * answering for no game or throwing a NullPointerException three calls later. There were 80 such refusals, suites
 * asked 14 of them in their own words, and the 2026-10-15 shared sweep reported the rest a file at a time: a refusal
 * dropped, swallowed or inverted survived wherever no suite handed that argument null (PaintForm 146 was the one that
 * named the class). Asking them one by one is how the next one stays open.
 *
 * <p>So this walks the production source for them, and calls each constructor and method that carries one with one
 * argument null at a time and every other argument given - LoadedZoneTest's collaborators where it knows the type,
 * else a stand-in. Every refusal must be heard, thrown from its own line; and handed everything, a constructor or
 * method may not refuse itself, which is what an inverted test does. A refusal written tomorrow is asked by the same
 * walk, with no list to add it to. An abstract class is asked through the classes the source says extend it.
 *
 * <p>ORDER: needs no dump and no display. No workspace is open while it runs, its paths are scratch folders, and
 * the program's saved settings are compared before and after - a stand-in call that wrote one fails here, loudly.
 *
 * Usage: java ctrmap.tests.HandedRefusalsTest [src root]
 */
public class HandedRefusalsTest {

	static {
		Headless.require();
	}

	static int fails = 0;

	/**
	 * How many "must be handed" refusals the production source held when this was last counted: 80 on 2026-10-15, 79 on
	 * 2026-10-23 - ZoneLinkDialog's one went with the dialog, deleted with Connect zones on the owner's decision (every
	 * other file's count unchanged, git grep -c against 0240c1a).
	 */
	static final int REFUSALS = 79;

	/** One refusal in the source: where its throw statement is, and the constructor or method it is in. */
	static final class Site {

		final File file;
		final String top;
		final int from;
		final int to;
		final String in;
		final String text;

		Site(File file, String top, int from, int to, String in, String text) {
			this.file = file;
			this.top = top;
			this.from = from;
			this.to = to;
			this.in = in;
			this.text = text;
		}

		String where() {
			return file.getName() + ":" + from;
		}

		boolean heardAt(StackTraceElement e) {
			return file.getName().equals(e.getFileName()) && e.getLineNumber() >= from && e.getLineNumber() <= to;
		}
	}

	public static void main(String[] args) throws Exception {
		File src = new File(args.length > 0 ? args[0] : "src");
		System.out.println("--- every \"must be handed\" refusal in the editor is heard, from its own line");
		List<Site> sites = sites(new File(src, "ctrmap"));
		Map<String, String> settingsBefore = settings();
		ctrmap.WorkspaceSession was = ctrmap.Workspace.session();
		ctrmap.Workspace.install(null);
		String wsWas = ctrmap.Workspace.WORKSPACE_PATH, gameWas = ctrmap.Workspace.GAMEDIR_PATH;
		String ws = Scratch.dir("ctrmap_handed_ws").getPath() + File.separator;
		String game = Scratch.dir("ctrmap_handed_game").getPath() + File.separator;
		ctrmap.Workspace.WORKSPACE_PATH = ws;
		ctrmap.Workspace.GAMEDIR_PATH = game;
		Set<String> heard = new LinkedHashSet<>();
		List<String> refusedHandedAll = new ArrayList<>();
		int calls = 0;
		List<String> said = ctrmap.Ui.record();
		try {
			Map<String, List<Site>> byFile = new TreeMap<>();
			for (Site s : sites) {
				byFile.computeIfAbsent(s.file.getPath(), k -> new ArrayList<>()).add(s);
			}
			for (List<Site> inFile : byFile.values()) {
				Set<String> names = new HashSet<>();
				for (Site s : inFile) {
					names.add(s.in);
				}
				for (Executable x : executables(inFile.get(0).top, names, src)) {
					calls += ask(x, sites, heard, refusedHandedAll);
				}
			}
		} finally {
			ctrmap.Ui.stopRecording();
		}
		boolean pathsKept = ws.equals(ctrmap.Workspace.WORKSPACE_PATH) && game.equals(ctrmap.Workspace.GAMEDIR_PATH)
				&& ctrmap.Workspace.session() == null;
		ctrmap.Workspace.WORKSPACE_PATH = wsWas;
		ctrmap.Workspace.GAMEDIR_PATH = gameWas;
		ctrmap.Workspace.install(was);

		//A FLOOR, NOT A CONTROL ONLY: the walk reads the tree it asks, so a refusal taken out takes its own words out of
		//what is expected, and every check below still passes (the drop plant proved it). A refusal added is asked
		//with no change here; one taken out on purpose lowers this, in the same commit, saying why.
		check(sites.size() >= REFUSALS, "the walk finds at least the " + REFUSALS + " refusals there were when this was"
				+ " last counted - " + sites.size() + "; fewer means one was taken out of the source");
		int lighting = 0;
		for (Site s : sites) {
			if (s.file.getName().equals("AreaLightingDialog.java") && heard.contains(s.where())) {
				lighting++;
			}
		}
		check(lighting == 3, "control: the fog and lighting pane's three refusals, which HandedGameTest asks by hand,"
				+ " are heard here too (" + lighting + " of 3)");
		List<String> unheard = new ArrayList<>();
		for (Site s : sites) {
			if (!heard.contains(s.where())) {
				unheard.add(s.where() + " (in " + s.in + ": \"" + s.text + "\")");
			}
		}
		check(unheard.isEmpty(), "every refusal is heard, handed that one argument null - " + (sites.size() - unheard.size())
				+ " of " + sites.size() + " over " + calls + " calls" + (unheard.isEmpty() ? "" : "; unheard:\n      "
				+ String.join("\n      ", unheard)));
		check(refusedHandedAll.isEmpty(), "and handed everything, no constructor or method refuses itself"
				+ (refusedHandedAll.isEmpty() ? "" : ":\n      " + String.join("\n      ", refusedHandedAll)));
		check(pathsKept, "control: nothing the calls ran opened a workspace or moved its paths off the scratch folders");
		Map<String, String> settingsAfter = settings();
		check(settingsAfter.equals(settingsBefore), "and the program's saved settings are as they were ("
				+ settingsBefore.size() + " values)" + (settingsAfter.equals(settingsBefore) ? ""
				: ": before " + settingsBefore + ", after " + settingsAfter));
		System.out.println("  (" + said.size() + " sentence(s) said to nobody while it ran)");

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		//the forms built above start render loops and timers on threads that are not daemons
		System.exit(fails == 0 ? 0 : 1);
	}

	// ---- the walk ------------------------------------------------------------------------------

	static final Pattern DECL = Pattern.compile("^(\\t+)(?:@\\w+(?:\\([^)]*\\))?\\s+)*"
			+ "((?:public\\s+|protected\\s+|private\\s+|static\\s+|final\\s+|synchronized\\s+|abstract\\s+)*)"
			+ "(?:<[^>]+>\\s+)?(?:[\\w.<>\\[\\], ?]+?\\s+)?(\\w+)\\s*\\(");
	static final Set<String> NOT_A_NAME = new HashSet<>(Arrays.asList("if", "for", "while", "switch", "catch",
			"return", "new", "throw", "else", "synchronized", "try", "do", "super", "this"));

	/** Every production refusal under {@code root} whose words say something must be handed something. */
	static List<Site> sites(File root) throws Exception {
		List<Site> found = new ArrayList<>();
		List<File> files = CollisionReadTest.javaFiles(root, new ArrayList<>());
		for (File f : files) {
			if (f.getPath().contains(File.separator + "tests" + File.separator)) {
				continue;
			}
			String top = topClass(root, f);
			String[] lines = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n", -1);
			for (int i = 0; i < lines.length; i++) {
				String t = lines[i].trim();
				if (!lines[i].contains("must be handed") || t.startsWith("//") || t.startsWith("*")) {
					continue;
				}
				int from = -1;
				for (int j = i; j >= Math.max(0, i - 2) && from < 0; j--) {
					if (lines[j].contains("throw new ")) {
						from = j;
					}
				}
				if (from < 0) {
					continue;   //words, not a refusal: a message built for one elsewhere is asked where it is thrown
				}
				int to = from;
				while (to < lines.length - 1 && !lines[to].trim().endsWith(";")) {
					to++;
				}
				int depth = depth(lines[from]);
				String in = null;
				for (int j = from - 1; j >= 0 && in == null; j--) {
					Matcher m = DECL.matcher(lines[j]);
					if (depth(lines[j]) < depth && m.find() && !NOT_A_NAME.contains(m.group(3))) {
						in = m.group(3);
					}
				}
				Matcher words = Pattern.compile("\"([^\"]*must be handed[^\"]*)\"").matcher(lines[i]);
				found.add(new Site(f, top, from + 1, to + 1, in == null ? "?" : in, words.find() ? words.group(1) : t));
				i = to;
			}
		}
		return found;
	}

	static int depth(String line) {
		int d = 0;
		while (d < line.length() && line.charAt(d) == '\t') {
			d++;
		}
		return d;
	}

	static String topClass(File root, File f) {
		String rel = root.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '.');
		return "ctrmap." + rel.substring(0, rel.length() - ".java".length());
	}

	/**
	 * The constructors and methods that carry the file's refusals: every constructor of a class in the file named by
	 * one, and every method so named - and for an abstract class, the constructors of the classes the source says
	 * extend it, which is how its own is reached.
	 */
	static List<Executable> executables(String top, Set<String> names, File src) throws Exception {
		List<Class<?>> family = new ArrayList<>();
		collect(Class.forName(top), family);
		List<Executable> found = new ArrayList<>();
		for (Class<?> c : family) {
			if (names.contains(c.getSimpleName())) {
				if (Modifier.isAbstract(c.getModifiers())) {
					for (Class<?> sub : extending(c, src)) {
						found.addAll(Arrays.asList(sub.getDeclaredConstructors()));
					}
				} else {
					found.addAll(Arrays.asList(c.getDeclaredConstructors()));
				}
			}
			for (Method m : c.getDeclaredMethods()) {
				if (!m.isSynthetic() && !m.isBridge() && names.contains(m.getName())) {
					found.add(m);
				}
			}
		}
		return found;
	}

	static void collect(Class<?> c, List<Class<?>> into) {
		into.add(c);
		for (Class<?> nested : c.getDeclaredClasses()) {
			collect(nested, into);
		}
	}

	/** The production classes whose source says they extend {@code c}, found by walking the tree. */
	static List<Class<?>> extending(Class<?> c, File src) throws Exception {
		File root = new File(src, "ctrmap");
		Pattern ext = Pattern.compile("class\\s+(\\w+)[^{]*\\bextends\\s+(?:[\\w.]+\\.)?" + c.getSimpleName() + "\\b");
		List<Class<?>> subs = new ArrayList<>();
		for (File f : CollisionReadTest.javaFiles(root, new ArrayList<>())) {
			if (f.getPath().contains(File.separator + "tests" + File.separator)) {
				continue;
			}
			String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			Matcher m = ext.matcher(text);
			while (m.find()) {
				List<Class<?>> family = new ArrayList<>();
				collect(Class.forName(topClass(root, f)), family);
				for (Class<?> k : family) {
					if (k.getSimpleName().equals(m.group(1)) && !Modifier.isAbstract(k.getModifiers())
							&& c.isAssignableFrom(k)) {
						subs.add(k);
					}
				}
			}
		}
		return subs;
	}

	// ---- the asking ----------------------------------------------------------------------------

	/**
	 * Calls {@code x} once with everything handed, once per argument with that one null, and once per array argument
	 * with that one empty. Answers how many calls were made. A refusal heard is the top frame of the first "must be
	 * handed" in what was thrown.
	 */
	static int ask(Executable x, List<Site> sites, Set<String> heard, List<String> refusedHandedAll) {
		x.setAccessible(true);
		Class<?>[] types = x.getParameterTypes();
		int calls = 0;
		for (int nulled = -1; nulled < 2 * types.length; nulled++) {
			boolean empty = nulled >= types.length;
			int which = empty ? nulled - types.length : nulled;
			if (which >= 0 && (types[which].isPrimitive() || empty && !types[which].isArray())) {
				continue;
			}
			Throwable threw = null;
			try {
				Object[] args = new Object[types.length];
				for (int i = 0; i < types.length; i++) {
					args[i] = i != which ? standIn(types[i])
							: empty ? Array.newInstance(types[i].getComponentType(), 0) : null;
				}
				calls++;
				if (x instanceof Constructor) {
					((Constructor<?>) x).newInstance(args);
				} else {
					Method m = (Method) x;
					m.invoke(Modifier.isStatic(m.getModifiers()) ? null : receiver(m.getDeclaringClass()), args);
				}
			} catch (InvocationTargetException ex) {
				threw = ex.getCause();
			} catch (Throwable ex) {
				threw = ex;
			}
			StackTraceElement[] stack = refusal(threw);
			if (stack == null) {
				continue;
			}
			StackTraceElement at = stack[0];
			for (Site s : sites) {
				if (s.heardAt(at)) {
					heard.add(s.where());
					//ITSELF: thrown by the frame this call entered - not by an overload or a helper it went on to
					//call with something of its own, such as an NPC's ground or a receiver built with nothing run
					boolean itself = at.getClassName().equals(x.getDeclaringClass().getName())
							&& at.getMethodName().equals(x instanceof Constructor ? "<init>" : x.getName())
							&& (stack.length < 2 || reflective(stack[1]));
					if (nulled < 0 && itself) {
						refusedHandedAll.add(x + " refused at " + s.where() + " though handed everything");
					}
				}
			}
		}
		return calls;
	}

	/** Whether {@code e} is the reflection that made a call, rather than code that called on. */
	static boolean reflective(StackTraceElement e) {
		String c = e.getClassName();
		return c.startsWith("jdk.internal.reflect.") || c.startsWith("sun.reflect.") || c.startsWith("java.lang.reflect.");
	}

	/** The stack of the first "must be handed" refusal in {@code t}'s causes, or null. */
	static StackTraceElement[] refusal(Throwable t) {
		for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
			if (String.valueOf(c.getMessage()).contains("must be handed") && c.getStackTrace().length > 0) {
				return c.getStackTrace();
			}
		}
		return null;
	}

	/** Something to call an instance method on: one built with nothing run, as the refusals come first. */
	static Object receiver(Class<?> c) throws Exception {
		return allocate(c);
	}

	/**
	 * A handed argument: LoadedZoneTest's collaborator for the types it knows, else a stand-in - an empty one of a
	 * collection, a proxy answering nothing of an interface, a new one of a class that builds with nothing, and
	 * otherwise one allocated with no constructor run. A window is null: none is refused, and none builds headless.
	 */
	static Object standIn(Class<?> t) throws Exception {
		if (t.isPrimitive()) {
			return primitive(t);
		}
		if (t == String.class || t == CharSequence.class || t == Object.class) {
			return "x";
		}
		if (t.isArray()) {
			//one of what it holds: a caller handed none of something it must have is refused too (Forms.unknownField)
			Object one = Array.newInstance(t.getComponentType(), 1);
			Array.set(one, 0, standIn(t.getComponentType()));
			return one;
		}
		Object known = LoadedZoneTest.collaborator(t);
		if (known != LoadedZoneTest.UNKNOWN) {
			return known;
		}
		if (t == ctrmap.LoadedZone.class) {
			return new ctrmap.LoadedZone();
		}
		if (t == File.class) {
			return Scratch.file("ctrmap_handed_refusals");
		}
		if (t.isAssignableFrom(ArrayList.class)) {
			return new ArrayList<Object>();
		}
		if (t.isAssignableFrom(HashSet.class)) {
			return new HashSet<Object>();
		}
		if (t.isAssignableFrom(HashMap.class)) {
			return new HashMap<Object, Object>();
		}
		if (t.isEnum()) {
			Object[] constants = t.getEnumConstants();
			return constants.length == 0 ? null : constants[0];
		}
		if (java.awt.Window.class.isAssignableFrom(t)) {
			return null;
		}
		if (t.isAssignableFrom(javax.swing.JPanel.class)) {
			return new javax.swing.JPanel();
		}
		if (t.isInterface()) {
			return Proxy.newProxyInstance(HandedRefusalsTest.class.getClassLoader(), new Class<?>[]{t}, (proxy, m, a) -> {
				if (m.getName().equals("equals")) {
					return proxy == a[0];
				}
				if (m.getName().equals("hashCode")) {
					return System.identityHashCode(proxy);
				}
				if (m.getName().equals("toString")) {
					return "a stand-in " + t.getSimpleName();
				}
				return m.getReturnType().isPrimitive() ? primitive(m.getReturnType()) : null;
			});
		}
		if (Modifier.isAbstract(t.getModifiers())) {
			return null;
		}
		try {
			Constructor<?> none = t.getConstructor();
			return none.newInstance();
		} catch (ReflectiveOperationException | RuntimeException | LinkageError cannot) {
			return allocate(t);
		}
	}

	static Object primitive(Class<?> t) {
		if (t == boolean.class) {
			return false;
		}
		if (t == char.class) {
			return '\0';
		}
		if (t == void.class) {
			return null;
		}
		if (t == long.class) {
			return 0L;
		}
		if (t == float.class) {
			return 0f;
		}
		if (t == double.class) {
			return 0d;
		}
		if (t == byte.class) {
			return (byte) 0;
		}
		if (t == short.class) {
			return (short) 0;
		}
		return 0;
	}

	/** One of {@code c} with no constructor run. */
	static Object allocate(Class<?> c) throws Exception {
		java.lang.reflect.Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
		f.setAccessible(true);
		Object unsafe = f.get(null);
		return unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, c);
	}

	/** Every value the program keeps in java.util.prefs under its own nodes, by path. */
	static Map<String, String> settings() throws Exception {
		Map<String, String> all = new TreeMap<>();
		java.util.prefs.Preferences root = java.util.prefs.Preferences.userRoot();
		for (String child : root.childrenNames()) {
			if (child.startsWith("ctrmap")) {
				settings(root.node(child), all);
			}
		}
		return all;
	}

	static void settings(java.util.prefs.Preferences node, Map<String, String> into) throws Exception {
		for (String key : node.keys()) {
			into.put(node.absolutePath() + "/" + key, node.get(key, null));
		}
		for (String child : node.childrenNames()) {
			settings(node.node(child), into);
		}
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}
