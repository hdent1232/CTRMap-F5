package ctrmap.tests;

import java.io.File;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nothing that runs off the event thread reaches Swing (F220).
 *
 * <p>WHY THIS SUITE EXISTS. Opening a zone handed it to all nine editors on a SwingWorker
 * thread: some two hundred widget writes off the event thread, a nested modal progress dialog
 * shown from the worker, and two JOptionPanes - and across the program ten of eighteen
 * background entries reached Swing, at 251 sites, 233 of them through calls. A rule that read a
 * worker's body would have seen eighteen. Nothing anywhere asked which thread it was on.
 *
 * <p>WHAT IT READS. The compiled classes, through {@link Bytecode}: every method's calls, every
 * object it makes, every lambda it binds, each with its source line - because the reach is
 * through calls, and a call is in the bytecode whatever the source spelled.
 *
 * <p>THE ENTRIES - every thread that is not the event thread, found from the class hierarchy
 * and the code, never from a list of names: the {@code doInBackground} of every SwingWorker
 * subclass, the {@code run} of every Thread subclass, the Runnable handed to every
 * {@code new Thread(...)}, and every {@code main} (the main thread is not the event thread). An
 * API this suite does not follow - an executor, a fork-join pool, {@code java.util.Timer}, a
 * parallel stream, a Thread whose Runnable it cannot see made - is REFUSED, not skipped: an entry
 * it cannot read must not read as no entry.
 *
 * <p>WHAT IT FOLLOWS from each, with no depth limit: every call into the program (a virtual or
 * interface call goes to every implementation the program has, lambdas and method references
 * included), every constructor, every static initialiser a reached instruction can trigger,
 * every lambda body bound, and the library-facing overrides of every object made (a Comparator's
 * compare, a listener's method) - EXCEPT a lambda or object handed straight to
 * {@code SwingUtilities}/{@code EventQueue} {@code invokeLater}/{@code invokeAndWait}, to a Swing
 * Timer, or to a Swing {@code add...Listener}, which runs on the event thread. A worker or thread
 * made on the way is its own entry; its {@code done()} runs on the event thread.
 *
 * <p>A TOUCH is any call on Swing state - an AWT/Swing Component (JOptionPane included), or a
 * javax.swing/java.awt type that fires events to listeners (models, documents, actions) - other
 * than the two calls Swing documents as safe from any thread, {@code repaint} and
 * {@code revalidate}, and including making one. A read is a touch: a worker that reads a
 * combo's selection reads whatever the user changed it to meanwhile. Which types count is asked
 * of the real class through reflection (loaded, never initialised), so a new model type counts
 * without anyone adding it; SwingWorker and javax.swing.Timer are the two observable types Swing
 * documents as thread-safe.
 *
 * <p>The ceiling is ZERO touches, and an owner the suite cannot load is UNKNOWN and refused.
 *
 * Usage: java ctrmap.tests.EdtReachTest [src root] [classes root]
 */
public class EdtReachTest {

	static int fails = 0;
	static Map<String, Bytecode.Cls> classes;
	static final ClassLoader LOADER = EdtReachTest.class.getClassLoader();
	/** The two calls Swing documents as safe from any thread. */
	static final Set<String> THREAD_SAFE = new HashSet<>(java.util.Arrays.asList("repaint", "revalidate"));
	static final Pattern OFF_THREAD_API = Pattern.compile("execute|submit|invokeAll|invokeAny|schedule\\w*|\\w*Async");

	public static void main(String[] args) throws Exception {
		//NO WINDOW MAY OPEN, BEFORE ANYTHING TOUCHES AWT: the last section drives Ui with the
		//application's dialogs on, and with the refusal planted out it put a real modal dialog on
		//the display and the run waited for nobody (2026-10-07, a prove that hung). Headless, the
		//same break is a HeadlessException and a FAIL.
		Headless.require();
		File src = new File(args.length > 0 ? args[0] : "src");
		File root = new File(args.length > 1 ? args[1] : "build/classes");
		if (!theClassesAreTheSources(src, root)) {
			System.out.println("FAILURES PRESENT (" + fails + ")");
			System.exit(1);
		}
		classes = Bytecode.read(root);
		theReaderReadsWhatItShould(src);
		swingStateIsAskedOfTheRealClass();
		List<Entry> entries = entries();
		theEntriesAreTheOnesTheSourceHas(entries, src);
		noPlainFieldCrossesTheThread(nothingOffTheEventThreadReachesSwing(entries));
		theSeamsRefuseOffTheEventThread(src);
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	// ------------------------------------------------------------------ the subject

	/**
	 * The classes read are the sources' own: every production source has its class, none newer
	 * than it. A stale or partial build measures some other tree and would report it clean.
	 */
	static boolean theClassesAreTheSources(File src, File root) throws Exception {
		System.out.println("--- the classes read are this source tree's (F220)");
		List<String> missing = new ArrayList<>();
		List<String> stale = new ArrayList<>();
		int sources = 0;
		for (File f : DialogSeamTest.javaSources(new File(src, "ctrmap"))) {
			String rel = src.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/');
			if (rel.startsWith("ctrmap/tests/") || f.getName().equals("package-info.java")) {
				continue;
			}
			sources++;
			File cls = new File(root, rel.substring(0, rel.length() - ".java".length()) + ".class");
			if (!cls.isFile()) {
				missing.add(rel);
			} else if (cls.lastModified() < f.lastModified()) {
				stale.add(rel);
			}
		}
		check(sources > 200 && missing.isEmpty() && stale.isEmpty(), sources + " production sources, each compiled"
				+ " and none newer than its class" + (missing.isEmpty() ? "" : " - no class for " + missing)
				+ (stale.isEmpty() ? "" : " - edited after the build: " + stale + ". Build first; this suite reads class files"));
		return missing.isEmpty() && stale.isEmpty() && sources > 200;
	}

	static boolean isApp(String internal) {
		return internal != null && classes.containsKey(internal) && !internal.startsWith("ctrmap/tests/");
	}

	/** KNOWN ANSWERS for the reader, from classes whose shape the suite can state independently. */
	static void theReaderReadsWhatItShould(File src) throws Exception {
		System.out.println("--- the bytecode reader reads calls, lines and lambdas (control)");
		int methods = 0;
		for (Bytecode.Cls c : classes.values()) {
			for (Bytecode.Method m : c.methods.values()) {
				methods += m.hasCode ? 1 : 0;
			}
		}
		check(methods > 3000, methods + " method bodies decoded, every one ending exactly at its code length");
		Bytecode.Method msg = classes.get("ctrmap/Ui").methods.get(
				"message(Ljava/awt/Component;Ljava/lang/String;Ljava/lang/String;I)V");
		int said = -1;
		for (Bytecode.Insn x : msg.code) {
			if (x.isInvoke() && x.owner.equals("javax/swing/JOptionPane") && x.name.equals("showMessageDialog")) {
				said = msg.line(x.pc);
			}
		}
		List<String> lines = Files.readAllLines(new File(src, "ctrmap/Ui.java").toPath(), StandardCharsets.UTF_8);
		int expected = -1;
		for (int i = 0; i < lines.size(); i++) {
			//split, so DialogSeamTest does not read this line as a raw dialog of the suite's own
			if (lines.get(i).contains("JOptionPane" + ".showMessageDialog(parent, text, title, type);")) {
				expected = i + 1;
			}
		}
		check(said > 0 && said == expected, "Ui.message's JOptionPane call is found, on its source line (" + said
				+ ", the source says " + expected + ")");
		boolean bound = false;
		for (Bytecode.Insn x : classes.get("ctrmap/CtrmapMainframe").methods.get("buildMenuBar()Ljavax/swing/JMenuBar;").code) {
			if (x.op == Bytecode.INVOKEDYNAMIC && x.lambda != null && x.lambda[0].equals("ctrmap/CtrmapMainframe")
					&& x.lambda[1].equals("openGrAction")) {
				bound = true;
			}
		}
		check(bound, "the menu bar's CtrmapMainframe::openGrAction is read as a lambda bound to that method");
	}

	// ------------------------------------------------------------------ what Swing state is

	static final Map<String, Boolean> STATE = new HashMap<>();
	static final Set<String> UNLOADABLE = new TreeSet<>();

	/** Whether calling {@code internal} touches Swing state; an owner that will not load is UNKNOWN. */
	static boolean swingState(String internal) {
		Boolean known = STATE.get(internal);
		if (known != null) {
			return known;
		}
		boolean s;
		try {
			s = swingState(Class.forName(internal.replace('/', '.'), false, LOADER));
		} catch (Throwable t) {
			UNLOADABLE.add(internal + " (" + t + ")");
			s = false;
		}
		STATE.put(internal, s);
		return s;
	}

	static boolean swingState(Class<?> c) {
		if (java.awt.Component.class.isAssignableFrom(c)) {
			return true;
		}
		for (Class<?> t : supertypes(c)) {
			String n = t.getName();
			if (!(n.startsWith("javax.swing.") || n.startsWith("java.awt.")) || t == javax.swing.SwingWorker.class
					|| t == javax.swing.Timer.class) {
				continue;
			}
			for (java.lang.reflect.Method m : t.getDeclaredMethods()) {
				if (Modifier.isPublic(m.getModifiers()) && m.getName().startsWith("add") && m.getName().endsWith("Listener")
						&& m.getParameterCount() == 1 && java.util.EventListener.class.isAssignableFrom(m.getParameterTypes()[0])) {
					return true; //it tells listeners when it changes, so it is something a widget shows
				}
			}
		}
		return false;
	}

	static Set<Class<?>> supertypes(Class<?> c) {
		Set<Class<?>> out = new LinkedHashSet<>();
		ArrayDeque<Class<?>> todo = new ArrayDeque<>();
		todo.add(c);
		while (!todo.isEmpty()) {
			Class<?> t = todo.poll();
			if (!out.add(t)) {
				continue;
			}
			if (t.getSuperclass() != null) {
				todo.add(t.getSuperclass()); //ArrayDeque refuses a null: Object's superclass is one
			}
			todo.addAll(java.util.Arrays.asList(t.getInterfaces()));
		}
		return out;
	}

	/** KNOWN ANSWERS for the classification, both ways. */
	static void swingStateIsAskedOfTheRealClass() {
		System.out.println("--- Swing state is asked of the real class, not a list of names (control)");
		String[] state = {"javax/swing/JComboBox", "javax/swing/DefaultListModel", "javax/swing/text/PlainDocument",
			"javax/swing/JOptionPane", "javax/swing/DefaultBoundedRangeModel", "ctrmap/humaninterface/TileMapPanel"};
		String[] not = {"javax/swing/SwingWorker", "javax/swing/Timer", "java/awt/image/BufferedImage",
			"javax/swing/ImageIcon", "javax/swing/SwingUtilities", "java/awt/Color", "ctrmap/Ui"};
		List<String> wrong = new ArrayList<>();
		for (String s : state) {
			if (!swingState(s)) {
				wrong.add(s + " is Swing state");
			}
		}
		for (String s : not) {
			if (swingState(s)) {
				wrong.add(s + " is not");
			}
		}
		check(wrong.isEmpty() && UNLOADABLE.isEmpty(), "a widget, a model, a document and a dialog are Swing state;"
				+ " the two thread-safe types, an image, an icon, a colour and the dialog seam are not" + (wrong.isEmpty() ? "" : " - wrong: " + wrong));
		boolean said = !swingState("ctrmap/NoSuchClassAnywhere") && UNLOADABLE.size() == 1
				&& UNLOADABLE.iterator().next().startsWith("ctrmap/NoSuchClassAnywhere");
		UNLOADABLE.clear();
		STATE.remove("ctrmap/NoSuchClassAnywhere");
		check(said, "control: a class that cannot be loaded is recorded as UNKNOWN - refused at the end - never read as not Swing");
	}

	// ------------------------------------------------------------------ entries

	static final class Entry {

		final Bytecode.Method method;
		final String kind;

		Entry(Bytecode.Method method, String kind) {
			this.method = method;
			this.kind = kind;
		}

		@Override
		public String toString() {
			return simple(method.cls.name) + "." + method.name + " [" + kind + "]";
		}
	}

	static final List<String> REFUSED_ENTRIES = new ArrayList<>();

	static boolean extendsClass(String c, String target) {
		for (String x = c; x != null; x = classes.containsKey(x) ? classes.get(x).superName : null) {
			if (x.equals(target)) {
				return true;
			}
		}
		return false;
	}

	static final Map<String, Set<String>> SUPERTYPES = new HashMap<>();

	/** Every type {@code c} is, through the program's classes; a library type ends its branch. */
	static Set<String> programSupertypes(String c) {
		Set<String> known = SUPERTYPES.get(c);
		if (known != null) {
			return known;
		}
		Set<String> out = new LinkedHashSet<>();
		ArrayDeque<String> todo = new ArrayDeque<>();
		todo.add(c);
		while (!todo.isEmpty()) {
			String t = todo.poll();
			if (t == null || !out.add(t) || !classes.containsKey(t)) {
				continue;
			}
			Bytecode.Cls k = classes.get(t);
			if (k.superName != null) {
				todo.add(k.superName);
			}
			todo.addAll(k.interfaces);
		}
		SUPERTYPES.put(c, out);
		return out;
	}

	/**
	 * The preview worker (F206). Its one thread runs exactly the work handed to
	 * {@code NewestOnly.ask}, so that work is followed from every call site as an entry of its
	 * own; the thread factory and the executor inside the helper are the machinery those entries
	 * run on, not code the suite would otherwise miss.
	 */
	static final String WORKER = "ctrmap/humaninterface/NewestOnly";

	/** How many {@code NewestOnly.ask} calls the bytecode holds, each of which must be an entry. */
	static int askSites;

	static List<Entry> entries() {
		List<Entry> out = new ArrayList<>();
		askSites = 0;
		for (Bytecode.Cls c : classes.values()) {
			if (!isApp(c.name) || c.name.equals(WORKER) || c.name.startsWith(WORKER + "$")) {
				continue;
			}
			if (extendsClass(c.name, "javax/swing/SwingWorker")) {
				for (Bytecode.Method m : c.methods.values()) {
					if (m.name.equals("doInBackground") && m.hasCode && (m.access & 0x0040) == 0) { //not the bridge
						out.add(new Entry(m, "SwingWorker"));
					}
				}
			}
			if (extendsClass(c.name, "java/lang/Thread") && c.methods.containsKey("run()V")) {
				out.add(new Entry(c.methods.get("run()V"), "Thread subclass"));
			}
			Bytecode.Method main = c.methods.get("main([Ljava/lang/String;)V");
			if (main != null && main.isStatic() && main.hasCode) {
				out.add(new Entry(main, "the main thread"));
			}
			for (Bytecode.Method m : c.methods.values()) {
				for (int k = 0; k < m.code.size(); k++) {
					Bytecode.Insn x = m.code.get(k);
					String where = c.file() + ":" + m.line(x.pc);
					if (x.op == Bytecode.INVOKESPECIAL && x.owner.equals("java/lang/Thread") && x.name.equals("<init>")
							&& x.desc.contains("Ljava/lang/Runnable;")) {
						Bytecode.Method r = runnableHandedAt(m, k);
						if (r == null) {
							REFUSED_ENTRIES.add(where + ": a Thread whose Runnable is not made here, so what it runs cannot be read");
						} else {
							out.add(new Entry(r, "new Thread at " + where));
						}
					} else if (x.isInvoke() && x.owner.equals(WORKER) && x.name.equals("ask")) {
						askSites++;
						Bytecode.Method work = lambdaHandedAt(m, k, "java/util/function/Function");
						if (work == null) {
							REFUSED_ENTRIES.add(where + ": NewestOnly.ask whose work is not made here, so what it runs cannot be read");
						} else {
							out.add(new Entry(work, "NewestOnly work at " + where));
						}
					} else if (x.isInvoke() && ((x.owner.startsWith("java/util/concurrent/") && OFF_THREAD_API.matcher(x.name).matches())
							|| (x.owner.equals("java/util/Timer") && x.name.startsWith("schedule"))
							|| x.name.equals("parallelStream") || (x.owner.startsWith("java/util/stream/") && x.name.equals("parallel")))) {
						REFUSED_ENTRIES.add(where + ": " + simple(x.owner) + "." + x.name + " runs code on a thread this suite does not follow");
					}
				}
			}
		}
		return out;
	}

	/** The run() a {@code new Thread(...)} at {@code at} is handed: the nearest Runnable made before it. */
	static Bytecode.Method runnableHandedAt(Bytecode.Method m, int at) {
		for (int k = at - 1; k >= 0; k--) {
			Bytecode.Insn x = m.code.get(k);
			if (x.op == Bytecode.INVOKEDYNAMIC && x.lambda != null && x.producedType().equals("java/lang/Runnable")) {
				return method(x.lambda[0], x.lambda[1] + x.lambda[2]);
			}
			if (x.op == Bytecode.INVOKESPECIAL && x.name.equals("<init>") && isApp(x.owner)
					&& programSupertypes(x.owner).contains("java/lang/Runnable")) {
				return method(x.owner, "run()V");
			}
		}
		return null;
	}

	/**
	 * The body of the nearest lambda of type {@code type} made before {@code at} - for
	 * {@code NewestOnly.ask(work, show, discard)} the only {@code Function}, the two after it being
	 * {@code Consumer}s that run on the event thread.
	 */
	static Bytecode.Method lambdaHandedAt(Bytecode.Method m, int at, String type) {
		for (int k = at - 1; k >= 0; k--) {
			Bytecode.Insn x = m.code.get(k);
			if (x.op == Bytecode.INVOKEDYNAMIC && x.lambda != null && x.producedType().equals(type)) {
				return method(x.lambda[0], x.lambda[1] + x.lambda[2]);
			}
		}
		return null;
	}

	static Bytecode.Method method(String owner, String key) {
		Bytecode.Cls c = classes.get(owner);
		return c == null ? null : c.methods.get(key);
	}

	/**
	 * The entries agree with the source: one SwingWorker class per {@code doInBackground} body
	 * written, one Thread per {@code new Thread(} or {@code extends Thread}, one main per
	 * {@code main(String[]} - so an entry the reader failed to find is a disagreement, not a
	 * smaller number.
	 */
	static void theEntriesAreTheOnesTheSourceHas(List<Entry> entries, File src) throws Exception {
		System.out.println("--- every thread that is not the event thread is an entry (F220)");
		Pattern bodies = Pattern.compile("\\bdoInBackground\\s*\\(\\s*\\)\\s*(?:throws[^{;]*)?\\{");
		Pattern threads = Pattern.compile("\\bnew\\s+Thread\\s*\\(|\\bextends\\s+Thread\\b");
		Pattern mains = Pattern.compile("\\bstatic\\s+void\\s+main\\s*\\(\\s*(?:final\\s+)?String\\s*(?:\\[\\s*\\]\\s*\\w+|\\.\\.\\.\\s*\\w+|\\w+\\s*\\[\\s*\\])\\s*\\)");
		Pattern asks = Pattern.compile("\\.ask\\s*\\(");
		int written = 0, threadSites = 0, mainSites = 0, askWritten = 0;
		for (File f : DialogSeamTest.javaSources(new File(src, "ctrmap"))) {
			if (f.getParentFile() != null && f.getParentFile().getName().equals("tests")) {
				continue;
			}
			String s = SourceSeamTest.stripComments(DialogSeamTest.read(f));
			written += count(bodies, s);
			mainSites += count(mains, s);
			if (f.getName().equals("NewestOnly.java")) {
				continue; //its one thread runs the work handed to ask, counted at each ask below
			}
			threadSites += count(threads, s);
			if (s.contains("NewestOnly")) {
				askWritten += count(asks, s);
			}
		}
		Set<String> workers = new TreeSet<>();
		int threadEntries = 0, mainEntries = 0, askEntries = 0;
		for (Entry e : entries) {
			if (e.kind.equals("SwingWorker")) {
				workers.add(e.method.cls.name);
			} else if (e.kind.equals("the main thread")) {
				mainEntries++;
			} else if (e.kind.startsWith("NewestOnly work")) {
				askEntries++;
			} else {
				threadEntries++;
			}
		}
		check(askEntries == askSites && askSites == askWritten && askWritten > 0, askEntries + " preview works followed, of "
				+ askSites + " NewestOnly.ask calls in the bytecode and " + askWritten + " in the source");
		check(workers.size() == written && written > 5, workers.size() + " SwingWorker classes in the bytecode, "
				+ written + " doInBackground bodies in the source");
		check(threadEntries == threadSites, threadEntries + " threads in the bytecode, " + threadSites + " in the source");
		check(mainEntries == mainSites && mainSites > 0, mainEntries + " main methods in the bytecode, " + mainSites + " in the source");
		check(REFUSED_ENTRIES.isEmpty(), "no code runs on a thread this suite cannot follow"
				+ (REFUSED_ENTRIES.isEmpty() ? "" : " - " + REFUSED_ENTRIES));
	}

	static int count(Pattern p, String s) {
		int n = 0;
		Matcher m = p.matcher(s);
		while (m.find()) {
			n++;
		}
		return n;
	}

	// ------------------------------------------------------------------ the walk

	/** How a method was reached: from which method, at which line, for the report. */
	static final class Reach {

		final Bytecode.Method from;
		final int line;

		Reach(Bytecode.Method from, int line) {
			this.from = from;
			this.line = line;
		}
	}

	/** Every lambda and method reference the program binds, by the interface it makes and that interface's method name. */
	static Map<String, List<String[]>> lambdaBodies() {
		Map<String, List<String[]>> out = new HashMap<>();
		for (Bytecode.Cls c : classes.values()) {
			if (!isApp(c.name)) {
				continue;
			}
			for (Bytecode.Method m : c.methods.values()) {
				for (int k = 0; k < m.code.size(); k++) {
					Bytecode.Insn x = m.code.get(k);
					if (x.op == Bytecode.INVOKEDYNAMIC && x.lambda != null && !handedToTheEventThread(m.code, k)) {
						String key = x.producedType() + "." + x.name;
						List<String[]> l = out.get(key);
						if (l == null) {
							l = new ArrayList<>();
							out.put(key, l);
						}
						l.add(x.lambda);
					}
				}
			}
		}
		return out;
	}

	static Map<String, List<String[]>> LAMBDAS;
	static final Set<String> UNRESOLVED = new TreeSet<>();
	static final Map<String, Integer> LIBRARY_CALLBACKS = new TreeMap<>();

	static final class Walk {

		final Entry entry;
		final Map<Bytecode.Method, Reach> seen = new IdentityHashMap<>();
		final ArrayDeque<Bytecode.Method> queue = new ArrayDeque<>();
		/** site -> the Swing call made there, with the path that reached it. */
		final Map<String, String> touches = new TreeMap<>();

		Walk(Entry entry) {
			this.entry = entry;
			seen.put(entry.method, null);
			queue.add(entry.method);
		}

		void follow(Bytecode.Method target, Bytecode.Method from, int line) {
			if (target == null || !target.hasCode || seen.containsKey(target)) {
				return;
			}
			seen.put(target, new Reach(from, line));
			queue.add(target);
		}

		void touch(Bytecode.Method in, int line, String what) {
			String site = in.cls.file() + ":" + line;
			if (!touches.containsKey(site + " " + what)) {
				touches.put(site + " " + what, path(in));
			}
		}

		String path(Bytecode.Method m) {
			List<String> steps = new ArrayList<>();
			for (Bytecode.Method at = m; at != null && steps.size() < 40; ) {
				Reach r = seen.get(at);
				if (r == null) {
					break;
				}
				steps.add(0, simple(at.cls.name) + "." + at.name + "@" + r.line);
				at = r.from;
			}
			return steps.isEmpty() ? "" : "  via " + String.join(" > ", steps);
		}

		void run() {
			while (!queue.isEmpty()) {
				Bytecode.Method m = queue.poll();
				for (int k = 0; k < m.code.size(); k++) {
					step(m, k);
				}
			}
		}

		void step(Bytecode.Method m, int k) {
			Bytecode.Insn x = m.code.get(k);
			int line = m.line(x.pc);
			switch (x.op) {
				case Bytecode.NEW:
				case Bytecode.GETSTATIC:
				case Bytecode.PUTSTATIC:
					initialiser(x.owner, m, line);
					return;
				case Bytecode.INVOKEDYNAMIC:
					if (x.lambda != null && !handedToTheEventThread(m.code, k)) {
						bound(x.lambda, m, line);
					}
					return;
				case Bytecode.GETFIELD:
				case Bytecode.PUTFIELD:
				case Bytecode.MONITORENTER:
				case Bytecode.MONITOREXIT:
					return; //read by noPlainFieldCrossesTheThread, never followed
				default:
					break;
			}
			if (x.owner.startsWith("[")) {
				return; //an array's clone()
			}
			if (x.op == Bytecode.INVOKESTATIC) {
				initialiser(x.owner, m, line);
			}
			if (x.name.equals("<init>")) {
				made(x, m, k, line);
				return;
			}
			if (isApp(x.owner)) {
				List<Bytecode.Method> targets = targets(x.owner, x.name, x.desc, x.op);
				if (!targets.isEmpty()) {
					for (Bytecode.Method t : targets) {
						follow(t, m, line);
					}
				} else if (swingState(x.owner)) {
					if (!THREAD_SAFE.contains(x.name)) {
						touch(m, line, simple(x.owner) + "." + x.name);
					}
				} else if (classes.get(x.owner).isInterface() || isAbstractIn(x.owner, x.name + x.desc)) {
					UNRESOLVED.add(simple(x.owner) + "." + x.name + " (called at " + m.cls.file() + ":" + line + ")");
				}
				return;
			}
			if (swingState(x.owner)) {
				if (!THREAD_SAFE.contains(x.name)) {
					touch(m, line, simple(x.owner) + "." + x.name);
				}
				return;
			}
			//a library type the program implements: the call runs whichever program object it was
			//handed - a Runnable made on the event thread and run by a worker runs on the worker
			List<Bytecode.Method> impls = implementations(x.owner, x.name, x.desc);
			if (!impls.isEmpty()) {
				LIBRARY_CALLBACKS.merge(simple(x.owner) + "." + x.name, 1, Integer::sum);
				for (Bytecode.Method t : impls) {
					follow(t, m, line);
				}
			}
		}

		void initialiser(String owner, Bytecode.Method from, int line) {
			if (isApp(owner)) {
				follow(classes.get(owner).methods.get("<clinit>()V"), from, line);
			}
		}

		/** A lambda or method reference bound here: its body runs wherever it is called, so it is followed now. */
		void bound(String[] body, Bytecode.Method from, int line) {
			if (isApp(body[0])) {
				follow(method(body[0], body[1] + body[2]), from, line);
			} else if (swingState(body[0]) && !THREAD_SAFE.contains(body[1])) {
				touch(from, line, simple(body[0]) + "::" + body[1]);
			}
		}

		void made(Bytecode.Insn x, Bytecode.Method m, int k, int line) {
			if (!isApp(x.owner)) {
				if (swingState(x.owner)) {
					touch(m, line, "new " + simple(x.owner));
				}
				return;
			}
			if (extendsClass(x.owner, "javax/swing/SwingWorker") || extendsClass(x.owner, "java/lang/Thread")) {
				return; //its own entry; a worker's done() runs on the event thread
			}
			follow(method(x.owner, x.name + x.desc), m, line);
			if (!handedToTheEventThread(m.code, k)) {
				for (Bytecode.Method cb : callbacks(x.owner)) {
					follow(cb, m, line);
				}
			}
		}
	}

	static boolean isAbstractIn(String owner, String key) {
		for (String t : programSupertypes(owner)) {
			Bytecode.Method d = method(t, key);
			if (d != null && !d.hasCode) {
				return true;
			}
		}
		return false;
	}

	/**
	 * What a call on a program class can run: the body it resolves to, every override or
	 * implementation in the program, and every lambda the program binds to that interface method.
	 */
	static final Map<String, List<Bytecode.Method>> TARGETS = new HashMap<>();

	static List<Bytecode.Method> targets(String owner, String name, String desc, int op) {
		boolean exact = op == Bytecode.INVOKESTATIC || op == Bytecode.INVOKESPECIAL;
		String cacheKey = owner + "." + name + desc + (exact ? "!" : "");
		List<Bytecode.Method> known = TARGETS.get(cacheKey);
		if (known == null) {
			known = targetsOf(owner, name, desc, exact);
			TARGETS.put(cacheKey, known);
		}
		return known;
	}

	static List<Bytecode.Method> targetsOf(String owner, String name, String desc, boolean exact) {
		List<Bytecode.Method> out = new ArrayList<>();
		String key = name + desc;
		Bytecode.Method resolved = null;
		for (String t : programSupertypes(owner)) {
			Bytecode.Method d = method(t, key);
			if (d != null && d.hasCode) {
				resolved = d;
				break;
			}
		}
		if (resolved != null) {
			out.add(resolved);
		}
		if (exact) {
			return out;
		}
		for (Bytecode.Cls c : classes.values()) {
			if (isApp(c.name) && !c.name.equals(owner)) {
				Bytecode.Method d = c.methods.get(key);
				if (d != null && d.hasCode && d != resolved && programSupertypes(c.name).contains(owner)) {
					out.add(d);
				}
			}
		}
		List<String[]> l = LAMBDAS.get(owner + "." + name);
		if (l != null) {
			for (String[] body : l) {
				Bytecode.Method b = method(body[0], body[1] + body[2]);
				if (b != null) {
					out.add(b);
				}
			}
		}
		return out;
	}

	static final Map<String, List<Bytecode.Method>> IMPLEMENTATIONS = new HashMap<>();

	/**
	 * What a call on a LIBRARY type runs in the program: every lambda the program binds to that
	 * method and is not handing to the event thread, and every program class of that type that
	 * declares it. Which object the call was handed is not known here, so all of them are.
	 */
	static List<Bytecode.Method> implementations(String owner, String name, String desc) {
		String key = owner + "." + name + desc;
		List<Bytecode.Method> known = IMPLEMENTATIONS.get(key);
		if (known != null) {
			return known;
		}
		List<Bytecode.Method> out = new ArrayList<>();
		List<String[]> l = LAMBDAS.get(owner + "." + name);
		if (l != null) {
			for (String[] body : l) {
				Bytecode.Method b = method(body[0], body[1] + body[2]);
				if (b != null) {
					out.add(b);
				}
			}
		}
		for (Bytecode.Cls c : classes.values()) {
			if (isApp(c.name) && programSupertypes(c.name).contains(owner)) {
				Bytecode.Method d = c.methods.get(name + desc);
				if (d != null && d.hasCode) {
					out.add(d);
				}
			}
		}
		IMPLEMENTATIONS.put(key, out);
		return out;
	}

	static final Map<String, List<Bytecode.Method>> CALLBACKS = new HashMap<>();

	/**
	 * The methods of a program class that a LIBRARY may call: those overriding or implementing a
	 * method a library supertype declares (a Comparator's compare, a listener's method, a
	 * toString). An object made off the event thread and handed to a library runs these there.
	 */
	static List<Bytecode.Method> callbacks(String owner) {
		List<Bytecode.Method> known = CALLBACKS.get(owner);
		if (known != null) {
			return known;
		}
		Set<String> library = new HashSet<>();
		for (String t : programSupertypes(owner)) {
			if (isApp(t)) {
				continue;
			}
			try {
				for (Class<?> lt : supertypes(Class.forName(t.replace('/', '.'), false, LOADER))) {
					for (java.lang.reflect.Method lm : lt.getDeclaredMethods()) {
						if (!Modifier.isStatic(lm.getModifiers()) && !Modifier.isPrivate(lm.getModifiers())) {
							library.add(lm.getName() + descriptor(lm));
						}
					}
				}
			} catch (Throwable unloadable) {
				UNLOADABLE.add(t + " (" + unloadable + ")");
			}
		}
		List<Bytecode.Method> out = new ArrayList<>();
		for (String t : programSupertypes(owner)) {
			if (!isApp(t)) {
				continue;
			}
			for (Bytecode.Method m : classes.get(t).methods.values()) {
				if (m.hasCode && !m.isStatic() && library.contains(m.key())) {
					out.add(m);
				}
			}
		}
		CALLBACKS.put(owner, out);
		return out;
	}

	static String descriptor(java.lang.reflect.Method m) {
		StringBuilder sb = new StringBuilder("(");
		for (Class<?> p : m.getParameterTypes()) {
			sb.append(descriptor(p));
		}
		return sb.append(')').append(descriptor(m.getReturnType())).toString();
	}

	static String descriptor(Class<?> c) {
		if (c.isArray()) {
			return c.getName().replace('.', '/');
		}
		if (c.isPrimitive()) {
			return c == void.class ? "V" : c == boolean.class ? "Z" : c == byte.class ? "B" : c == char.class ? "C"
					: c == short.class ? "S" : c == int.class ? "I" : c == long.class ? "J" : c == float.class ? "F" : "D";
		}
		return "L" + c.getName().replace('.', '/') + ";";
	}

	/**
	 * Whether what was just made (a lambda at {@code k}, or the object whose constructor is at
	 * {@code k}) goes straight to the event thread: the next call takes it to
	 * invokeLater/invokeAndWait, a Swing Timer, or a Swing add...Listener.
	 */
	static boolean handedToTheEventThread(List<Bytecode.Insn> code, int k) {
		for (int j = k + 1; j < code.size(); j++) {
			Bytecode.Insn x = code.get(j);
			if (!x.isInvoke()) {
				continue;
			}
			if ((x.owner.equals("javax/swing/SwingUtilities") || x.owner.equals("java/awt/EventQueue"))
					&& (x.name.equals("invokeLater") || x.name.equals("invokeAndWait"))) {
				return true;
			}
			if (x.owner.equals("javax/swing/Timer") && x.name.equals("<init>")) {
				return true;
			}
			return x.name.startsWith("add") && x.name.endsWith("Listener") && swingState(x.owner);
		}
		return false;
	}

	static List<Walk> nothingOffTheEventThreadReachesSwing(List<Entry> entries) {
		System.out.println("--- nothing that runs off the event thread reaches Swing (F220)");
		LAMBDAS = lambdaBodies();
		Map<String, String> all = new TreeMap<>();
		int reaching = 0, reached = 0;
		StringBuilder report = new StringBuilder();
		List<Walk> walks = new ArrayList<>();
		for (Entry e : entries) {
			Walk w = new Walk(e);
			w.run();
			walks.add(w);
			reached += w.seen.size();
			if (!w.touches.isEmpty()) {
				reaching++;
				report.append("\n    ").append(e).append(": ").append(w.touches.size()).append(" Swing call(s)");
				for (Map.Entry<String, String> t : w.touches.entrySet()) {
					report.append("\n      ").append(t.getKey()).append(t.getValue());
					all.put(t.getKey(), e.toString());
				}
			}
		}
		System.out.println("  " + entries.size() + " entries reach " + reached + " method bodies; calls on a library"
				+ " type dispatched to the program's own implementations: " + LIBRARY_CALLBACKS);
		check(UNLOADABLE.isEmpty(), "every class a reached call names could be loaded and asked" + (UNLOADABLE.isEmpty() ? ""
				: " - UNKNOWN, so refused: " + UNLOADABLE));
		//A call on a program interface or abstract method that NO program class implements runs
		//nothing: a library cannot implement the program's types - unless the program makes a
		//java.lang.reflect.Proxy, which implements any interface at run time. So that is asked.
		List<String> proxies = new ArrayList<>();
		for (Bytecode.Cls c : classes.values()) {
			for (Bytecode.Method m : isApp(c.name) ? c.methods.values() : java.util.Collections.<Bytecode.Method>emptyList()) {
				for (Bytecode.Insn x : m.code) {
					if (x.isInvoke() && x.owner.equals("java/lang/reflect/Proxy")) {
						proxies.add(c.file() + ":" + m.line(x.pc));
					}
				}
			}
		}
		check(proxies.isEmpty(), "the program makes no Proxy, so a call nothing in it implements runs nothing ("
				+ UNRESOLVED + ")" + (proxies.isEmpty() ? "" : " - but it does, at " + proxies + ", so those calls are UNKNOWN"));
		check(all.isEmpty(), "no thread but the event thread reaches Swing: " + all.size() + " site(s) from " + reaching
				+ " of " + entries.size() + " entries" + report);
		return walks;
	}

	static final int ACC_FINAL = 0x0010, ACC_VOLATILE = 0x0040, ACC_SYNCHRONIZED = 0x0020;

	/** The class that declares field {@code name} as seen from {@code owner}, through superclasses; null outside the program. */
	static Bytecode.Cls declaring(String owner, String name) {
		for (String x = owner; x != null && classes.containsKey(x); x = classes.get(x).superName) {
			if (classes.get(x).fields.containsKey(name)) {
				return classes.get(x);
			}
		}
		return null;
	}

	/** One field instruction: the method it is in, whether it writes, and whether a lock is held there. */
	static final class Access {

		final Bytecode.Method in;
		final Bytecode.Insn at;
		final boolean put;
		final boolean locked;

		Access(Bytecode.Method in, Bytecode.Insn at, boolean put, boolean locked) {
			this.in = in;
			this.at = at;
			this.put = put;
			this.locked = locked;
		}
	}

	/**
	 * Every program-field instruction in {@code m}, with whether a lock is held at it: the method
	 * is synchronized, or the instruction sits inside a {@code synchronized} block's body as the
	 * class file's handler table marks it ({@link Bytecode.Method#lockedAt}).
	 */
	static List<Access> accesses(Bytecode.Method m) {
		List<Access> out = new ArrayList<>();
		for (Bytecode.Insn x : m.code) {
			if ((x.op == Bytecode.PUTFIELD || x.op == Bytecode.PUTSTATIC || x.op == Bytecode.GETFIELD
					|| x.op == Bytecode.GETSTATIC) && isApp(x.owner) && declaring(x.owner, x.name) != null) {
				out.add(new Access(m, x, x.op == Bytecode.PUTFIELD || x.op == Bytecode.PUTSTATIC, m.lockedAt(x.pc)));
			}
		}
		return out;
	}

	/**
	 * The first of {@code as} the walk does not reach, other than the declaring class's own
	 * initialisers; null for none. A lock excuses a pair only when BOTH sides hold one: a
	 * synchronized writer and an unlocked reader race exactly as two unlocked ones do. (Two locks
	 * are taken as the same lock - the bytecode does not say which object each is on.)
	 */
	static Access outside(List<Access> as, Walk w, Bytecode.Cls d, boolean hereLocked) {
		for (Access o : as == null ? java.util.Collections.<Access>emptyList() : as) {
			boolean init = o.in.cls == d && (o.in.name.equals("<init>") || o.in.name.equals("<clinit>"));
			if (!w.seen.containsKey(o.in) && !init && !(hereLocked && o.locked)) {
				return o;
			}
		}
		return null;
	}

	/**
	 * The plain fields one thread writes and another reads (F016). What it reads: every field
	 * instruction in every method an off-event-thread walk reaches, against every field instruction
	 * in the methods it does not.
	 */
	static Map<String, String> sharedPlainFields(List<Walk> walks) {
		Map<String, List<Access>> readers = new HashMap<>(), writers = new HashMap<>();
		for (Bytecode.Cls c : classes.values()) {
			if (!isApp(c.name)) {
				continue;
			}
			for (Bytecode.Method m : c.methods.values()) {
				for (Access a : accesses(m)) {
					String key = declaring(a.at.owner, a.at.name).name + "." + a.at.name;
					(a.put ? writers : readers).computeIfAbsent(key, k -> new ArrayList<>()).add(a);
				}
			}
		}
		Map<String, String> out = new TreeMap<>();
		for (Walk w : walks) {
			if (w.entry.kind.equals("the main thread")) {
				continue;
			}
			Set<String> madeTypes = new HashSet<>();
			for (Bytecode.Method m : w.seen.keySet()) {
				for (Bytecode.Insn x : m.code) {
					if (x.op == Bytecode.NEW && isApp(x.owner)) {
						madeTypes.addAll(programSupertypes(x.owner));
					}
				}
			}
			for (Bytecode.Method m : w.seen.keySet()) {
				for (Access a : accesses(m)) {
					Bytecode.Cls d = declaring(a.at.owner, a.at.name);
					int acc = d.fields.get(a.at.name);
					boolean isStatic = a.at.op == Bytecode.GETSTATIC || a.at.op == Bytecode.PUTSTATIC;
					if ((acc & ACC_FINAL) != 0 || (!isStatic && madeTypes.contains(d.name))) {
						continue;
					}
					boolean plain = (acc & ACC_VOLATILE) == 0;
					String key = d.name + "." + a.at.name;
					//TWO WRITERS, volatile or not: volatile makes a write seen, not "x -= dx" whole, so
					//a drag and a walk each writing the camera lose one or the other
					Access other = a.put ? outside(writers.get(key), w, d, a.locked) : null;
					String said = "written by " + w.entry + " and written outside it";
					if (other == null && plain) {
						other = outside((a.put ? readers : writers).get(key), w, d, a.locked);
						said = (a.put ? "written by " : "read by ") + w.entry + ", " + (a.put ? "read" : "written") + " outside it";
					}
					if (other != null && !out.containsKey(key)) {
						out.put(key, said + " - here at " + m.cls.file() + ":" + m.line(a.at.pc) + ", there in "
								+ simple(other.in.cls.name) + "." + other.in.name);
					}
				}
			}
		}
		return out;
	}

	/**
	 * Fields one thread writes and another touches with no lock, measured 2026-10-08 once F016's
	 * own were fixed - the loaded-zone table read by the zone-open worker while the event thread
	 * swapped it, and the camera walker writing the camera the mouse handlers write. What is left
	 * is, as far as was read, written before the worker starts (its execute() orders that) or
	 * while a modal progress dialog keeps the user from writing it; the bytecode cannot see either,
	 * so each stays counted. HELD EQUAL to the count: it may only fall, and a fix that lowers the
	 * count lowers this with it, so a new shared field cannot land in the room an old one left.
	 * TIGHTENED 40 -> 39 on 2026-10-08, measured by this suite: RandomAccessBAIS.position, read by the
	 * model-loading worker, is the cursor's own private index now (F228).
	 */
	static final int SHARED_CEILING = 39;

	static void noPlainFieldCrossesTheThread(List<Walk> walks) {
		System.out.println("--- no plain field is written on one thread and read on another (F016)");
		theFieldCheckFindsWhatItShould();
		Map<String, String> shared = sharedPlainFields(walks);
		StringBuilder listed = new StringBuilder();
		for (Map.Entry<String, String> e : shared.entrySet()) {
			listed.append("\n    ").append(e.getKey()).append(": ").append(e.getValue());
		}
		check(shared.size() <= SHARED_CEILING, "fields one thread writes and another touches with no lock: " + shared.size()
				+ ", at or under the ceiling " + SHARED_CEILING + " - one immutable state behind a volatile reference, or"
				+ " every write on the event thread, not a plain field" + (shared.size() > SHARED_CEILING ? listed : ""));
		check(Ratchet.tight(shared.size(), SHARED_CEILING), Ratchet.said("shared-field", shared.size(), SHARED_CEILING));
		System.out.println("  counted:" + listed);
	}

	/**
	 * KNOWN ANSWERS for the field check, on a fixture whose answers are stated here and not read
	 * off the program: a thread writes one plain field the event thread reads, reads one the event
	 * thread writes, and writes one volatile field the event thread also writes - three found; a
	 * volatile field it alone writes, a final field, and a field of an object it made itself - none.
	 * A second thread, holding a lock, reads a field the event thread writes holding one - passed -
	 * and one the event thread writes holding none - found: a lock on one side is no lock.
	 */
	static void theFieldCheckFindsWhatItShould() {
		String shared = "ctrmap/fixture/Shared", mine = "ctrmap/fixture/Mine";
		Bytecode.Cls s = new Bytecode.Cls(shared), own = new Bytecode.Cls(mine), t = new Bytecode.Cls("ctrmap/fixture/T"),
				edt = new Bytecode.Cls("ctrmap/fixture/Edt");
		s.fields.put("plain", 0);
		s.fields.put("plainRead", 0);
		s.fields.put("twoWriters", ACC_VOLATILE);
		s.fields.put("vol", ACC_VOLATILE);
		s.fields.put("fin", ACC_FINAL);
		s.fields.put("bothLocked", 0);
		s.fields.put("oneLocked", 0);
		own.fields.put("x", 0);
		Bytecode.Method run = fixtureMethod(t, "run", 0,
				new Object[]{Bytecode.PUTFIELD, shared, "plain"}, new Object[]{Bytecode.GETFIELD, shared, "plainRead"},
				new Object[]{Bytecode.PUTFIELD, shared, "twoWriters"}, new Object[]{Bytecode.PUTFIELD, shared, "vol"},
				new Object[]{Bytecode.GETFIELD, shared, "fin"}, new Object[]{Bytecode.NEW, mine, null},
				new Object[]{Bytecode.PUTFIELD, mine, "x"});
		Bytecode.Method runLocked = fixtureMethod(t, "runLocked", ACC_SYNCHRONIZED,
				new Object[]{Bytecode.GETFIELD, shared, "bothLocked"}, new Object[]{Bytecode.GETFIELD, shared, "oneLocked"});
		fixtureMethod(edt, "paint", 0,
				new Object[]{Bytecode.GETFIELD, shared, "plain"}, new Object[]{Bytecode.PUTFIELD, shared, "plainRead"},
				new Object[]{Bytecode.PUTFIELD, shared, "twoWriters"}, new Object[]{Bytecode.GETFIELD, shared, "vol"},
				new Object[]{Bytecode.GETFIELD, shared, "fin"}, new Object[]{Bytecode.GETFIELD, mine, "x"},
				new Object[]{Bytecode.PUTFIELD, shared, "oneLocked"});
		fixtureMethod(edt, "set", ACC_SYNCHRONIZED, new Object[]{Bytecode.PUTFIELD, shared, "bothLocked"});
		for (Bytecode.Cls c : new Bytecode.Cls[]{s, own, t, edt}) {
			classes.put(c.name, c);
		}
		Set<String> found;
		try {
			Walk w = new Walk(new Entry(run, "fixture thread"));
			w.run();
			Walk locked = new Walk(new Entry(runLocked, "fixture thread holding a lock"));
			locked.run();
			found = sharedPlainFields(java.util.Arrays.asList(w, locked)).keySet();
		} finally {
			for (Bytecode.Cls c : new Bytecode.Cls[]{s, own, t, edt}) {
				classes.remove(c.name);
				SUPERTYPES.remove(c.name);
			}
		}
		Set<String> expected = new TreeSet<>(java.util.Arrays.asList(shared + ".plain", shared + ".plainRead",
				shared + ".twoWriters", shared + ".oneLocked"));
		check(new TreeSet<>(found).equals(expected), "control: the field check finds a plain write read elsewhere, a plain"
				+ " read written elsewhere, two writers of a volatile field and a lock held on one side only, and passes a"
				+ " lone volatile writer, a final field, the thread's own object and a lock held on both - "
				+ new TreeSet<>(found));
		//GameCache.get holds its lock in two blocks with the read between them, and returns early from
		//inside each: every read of drops is locked, the read itself is not - counting monitor enters
		//and exits in code order called "began = drops" unlocked, because the early return exits first
		Bytecode.Cls gc = classes.get("ctrmap/formats/GameCache");
		Bytecode.Method get = gc == null ? null : gc.methods.get(
				"get(Lctrmap/formats/GameCache$Key;Lctrmap/formats/GameCache$Read;)Ljava/lang/Object;");
		int dropsRead = 0, dropsLocked = 0;
		Boolean readLocked = null;
		for (Bytecode.Insn x : get == null ? java.util.Collections.<Bytecode.Insn>emptyList() : get.code) {
			if (x.op == Bytecode.GETFIELD && "drops".equals(x.name)) {
				dropsRead++;
				dropsLocked += get.lockedAt(x.pc) ? 1 : 0;
			} else if (x.isInvoke() && x.name.equals("read")) {
				readLocked = get.lockedAt(x.pc);
			}
		}
		check(dropsRead == 2 && dropsLocked == 2 && Boolean.FALSE.equals(readLocked), "control: the reader sees a"
				+ " synchronized block's body by its handler - GameCache.get reads drops twice, both locked (" + dropsLocked
				+ " of " + dropsRead + "), and calls the read between its blocks unlocked (" + readLocked + ")");
		Bytecode.Cls lz = classes.get("ctrmap/LoadedZone");
		Integer flags = lz == null ? null : lz.fields.get("state");
		check(flags != null && (flags & ACC_VOLATILE) != 0, "control: the reader reads a field's flags off the class -"
				+ " LoadedZone.state is volatile (" + flags + ")");
	}

	/** A fixture method with these access flags whose code is the given {op, owner, member} instructions, in order. */
	static Bytecode.Method fixtureMethod(Bytecode.Cls c, String name, int access, Object[]... code) {
		Bytecode.Method m = new Bytecode.Method(c, name, "()V", access);
		m.hasCode = true;
		int pc = 0;
		for (Object[] i : code) {
			m.code.add(new Bytecode.Insn(pc++, (Integer) i[0], (String) i[1], (String) i[2], i[2] == null ? null : "I", -1));
		}
		c.methods.put(m.key(), m);
		return m;
	}

	/**
	 * What the walk refuses before anything runs, the two seams refuse when it runs (F220): a
	 * real dialog, or a progress window, asked for off the event thread throws, naming the thread.
	 * A suite's recording and a headless load are not refused - a suite's own thread is not the
	 * event thread either - so the refusal is driven here directly.
	 */
	static void theSeamsRefuseOffTheEventThread(File src) throws Exception {
		System.out.println("--- a real dialog or progress window asked for off the event thread is refused (F220)");
		java.lang.reflect.Field enabled = ctrmap.Ui.class.getDeclaredField("dialogsEnabled");
		enabled.setAccessible(true);
		String said;
		enabled.set(null, true);
		try {
			ctrmap.Ui.message(null, "a worker's question", "From a worker", javax.swing.JOptionPane.ERROR_MESSAGE);
			said = "nothing was refused";
		} catch (IllegalStateException refused) {
			said = refused.getMessage();
		} catch (RuntimeException other) {
			said = "not refused, it went on to the dialog: " + other;
		} finally {
			enabled.set(null, false);
		}
		check(said.contains("off the event thread") && said.contains(Thread.currentThread().getName()),
				"with the application's dialogs on, Ui refuses a dialog asked off the event thread, naming the thread: " + said);
		String ui = SourceSeamTest.stripComments(DialogSeamTest.read(new File(src, "ctrmap/Ui.java")));
		List<String> unasked = new ArrayList<>();
		int shown = 0;
		String[] lines = ui.split("\n");
		for (int i = 1; i < lines.length; i++) {
			//split, so DialogSeamTest does not read this suite's patterns as raw dialogs of its own
			if (lines[i].contains("JOptionPane" + ".show") || lines[i].contains("chooser" + ".show")) {
				shown++;
				if (!lines[i - 1].contains("onTheEventThread(")) {
					unasked.add(lines[i].trim());
				}
			}
		}
		check(shown == 5 && unasked.isEmpty(), "and every one of Ui's " + shown + " real dialogs asks it, on the line"
				+ " before" + (unasked.isEmpty() ? "" : " - not: " + unasked));

		java.lang.reflect.Method ask = ctrmap.humaninterface.LoadingDialog.class.getDeclaredMethod("onTheEventThread", String.class);
		ask.setAccessible(true);
		String off;
		try {
			ask.invoke(null, "A progress window");
			off = "nothing was refused";
		} catch (java.lang.reflect.InvocationTargetException refused) {
			off = String.valueOf(refused.getCause().getMessage());
		}
		String[] on = {"not run"};
		javax.swing.SwingUtilities.invokeAndWait(() -> {
			try {
				ask.invoke(null, "A progress window");
				on[0] = null;
			} catch (ReflectiveOperationException ex) {
				on[0] = String.valueOf(ex.getCause());
			}
		});
		check(off.contains("off the event thread") && on[0] == null, "LoadingDialog's check refuses off the event"
				+ " thread (" + off + ") and lets the event thread through (" + on[0] + ")");
		String ld = SourceSeamTest.stripComments(DialogSeamTest.read(new File(src, "ctrmap/humaninterface/LoadingDialog.java")));
		String show = PropEditFormGuardsTest.body(ld, "public void showDialog(");
		String make = PropEditFormGuardsTest.body(ld, "public static LoadingDialog makeDialog(");
		check(show != null && make != null && show.contains("onTheEventThread(")
				&& show.indexOf("onTheEventThread(") < show.indexOf("dlg.setVisible(true)")
				&& make.contains("onTheEventThread(") && make.indexOf("onTheEventThread(") < make.indexOf("new JDialog()"),
				"...and it is asked before the window is made and before it is shown");
	}

	static String simple(String internal) {
		return internal.substring(internal.lastIndexOf('/') + 1);
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
