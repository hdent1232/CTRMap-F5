package ctrmap.tests;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every production method is called by something that runs (F204).
 *
 * <p>DuplicateWorkTest proves every production CLASS is reached, and stops there:
 * a reached class can carry any number of methods nothing calls. Measured from
 * the bytecode on 2026-10-06, 73 production methods had no live caller anywhere
 * and 114 more were alive only because a suite called them - the defunct
 * collision writer, a cliff builder nobody wires, setters for an editor that
 * never shipped - each one a place a reader believes a mechanism exists.
 *
 * <p>The walk is the one that measured them. Roots: every method of a suite,
 * {@code main(String[])}, constructors and static initialisers, any instance
 * method that may override a supertype's (every non-private one in a class
 * whose ancestry leaves this program, so a Swing callback is never called
 * dead), an enum's values/valueOf, every method a lambda or method reference
 * names, and every method named by a string constant in a class that calls a
 * reflection API - a handle or a name the program holds roots the program's walk,
 * one only a suite holds roots only the walk through the suites. From a live method,
 * every invoke reaches the method it names in the class it was resolved against
 * and that class's ancestors (an inherited body) and descendants (an override) -
 * so the walk can still call too much live, never a live method dead.
 *
 * <p>NOT BY NAME ALONE (F059). The first walk made a method live when any invoke
 * anywhere named the same name and descriptor: the proven-invalid legacy collision
 * writer stayed live because a dozen live record writers share
 * {@code write(LittleEndianDataOutputStream)}, and a suite holding the word
 * "build" kept every method named build alive. A name is a reflective call only
 * where reflection is called.
 *
 * <p>Two answers. A production method no live code reaches fails, by name:
 * delete it or call it. One that only a suite reaches is counted against a
 * ceiling that only falls - each is a production path tested in a program
 * that never takes it - and the number recorded is the number measured.
 *
 * <p>A PROGRAM'S main IS A ROOT ONLY WHEN SOMETHING RUNS IT (F027). Every
 * {@code main(String[])} used to be a root, so the NetBeans launcher stubs left in
 * five forms, a frame's own main and a text dumper reading "072.bin" from the
 * working directory were all live, and so was everything only they called. A
 * production main is a root when a launcher outside {@code src/} and
 * {@code docs/} runs its class (a {@code java ... ctrmap.X} command line, a
 * {@code main.class=}, {@code Main-Class:} or {@code --main-class}) or when the
 * class says how it is run, {@code "Usage: java <its name>"}, in its own strings.
 * And the other way round: a launcher that names a class with no main - nine
 * NetBeans run configurations, and a script converting a format with a class
 * deleted years ago - fails by name.
 *
 * <p>Known answers first: GARC's entry reader is live from the program's own
 * roots, and the abandoned bone-matrix adjuster F076 named is gone.
 *
 * Usage: java ctrmap.tests.EveryMethodIsReachedTest [classes-root]
 */
public class EveryMethodIsReachedTest {

	/**
	 * Production methods live only because a suite calls them, measured by this suite on
	 * 2026-10-08 (F204) once the methods with no caller at all were deleted. The audit counted
	 * 114 with a walk that let a name or a method reference held by a suite root the program;
	 * held to the program's own it was 126; with calls resolved by owner and a string counted as
	 * a reflective name only where reflection is called (F059), the same program measures 145 -
	 * the instrument got sharper, the program did not get worse, and twelve methods that only
	 * name-matching had kept alive were deleted with it. 142 once the single-zone append builder
	 * (F041) and an unused byte trimmer (F165) left. 141 once BchModelVerifier's private le32 went
	 * into LittleEndian (F228; found by listing both trees' suite-only methods). 135 once the record
	 * schema's code-patch tier and icon schema went with nothing in the program reading them (F252).
	 * 134 when the static tile history went, its batch-open query read only by suites (F209) - the
	 * number this suite measured.
	 * Lower it when one gains a production caller or leaves; never raise it.
	 */
	private static final int TEST_ONLY_CEILING = 134;

	private static final int ACC_PRIVATE = 0x0002;
	private static final int ACC_STATIC = 0x0008;
	private static final int ACC_ENUM = 0x4000;

	/**
	 * java.lang.Object's overridable methods. Every class's ancestry reaches Object, and Object is
	 * outside the program, so the JDK calls these into ANY class - a string concatenation calls
	 * toString, a HashMap calls hashCode and equals - through an owner the walk cannot follow.
	 * Since calls resolve by owner (F059), an override of one of these is a root the same way an
	 * override of a Swing callback is.
	 */
	private static final Set<String> OBJECT = new HashSet<>(java.util.Arrays.asList(
			"toString()Ljava/lang/String;", "hashCode()I", "equals(Ljava/lang/Object;)Z",
			"clone()Ljava/lang/Object;", "finalize()V"));

	static int fails = 0;

	static void check(boolean cond, String what) {
		System.out.println((cond ? "  ok: " : "  FAIL: ") + what);
		if (!cond) {
			fails++;
		}
	}

	public static void main(String[] args) throws Exception {
		File classes = new File(args.length > 0 ? args[0] : "build/classes");
		System.out.println("--- every production method is called by something that runs (F204)");
		if (!classes.isDirectory()) {
			check(false, "there is a compiled program to walk - none at " + classes);
			finish();
			return;
		}
		File repo = classes.getAbsoluteFile().getParentFile().getParentFile();
		//KNOWN ANSWERS FOR THE LAUNCH SHAPES (F141): each key followed by a class is one, the same key
		//followed by a word in a comment is not - '(F141):' was taken for a class and failed the rule
		List<String> shapes = launchesIn("# what --main-class (F141): names\nmain.class=ctrmap.A\n"
				+ "Main-Class: ctrmap.B\n  --main-class ctrmap.C `\njava -cp x ctrmap.D\n");
		check(shapes.equals(java.util.Arrays.asList("ctrmap/D", "ctrmap/A", "ctrmap/B", "ctrmap/C")),
				"control: a launcher names a class - main.class, Main-Class, --main-class and a java command line"
				+ " are read, and a word in a comment after the key is not: " + shapes);
		Map<String, Set<String>> launched = launchedBy(repo);
		check(launched.containsKey("ctrmap/CtrmapMainframe"),
				"control: the launchers in " + repo + " are read - the application's own class is run by them");
		List<ClassFileScanner.ClassFile> all = ClassFileScanner.scan(classes);
		Set<String> runnable = new TreeSet<>();
		for (ClassFileScanner.ClassFile cf : all) {
			for (ClassFileScanner.Method m : cf.methods) {
				if (isMain(m)) {
					runnable.add(cf.name);
				}
			}
		}
		Map<String, Set<String>> noMain = new java.util.TreeMap<>();
		for (Map.Entry<String, Set<String>> e : launched.entrySet()) {
			if (!runnable.contains(e.getKey())) {
				noMain.put(e.getKey().replace('/', '.'), e.getValue());
			}
		}
		check(noMain.isEmpty(), "every class a launcher runs exists and has a main(String[])"
				+ (noMain.isEmpty() ? "" : " - these do not: " + noMain + ". Delete the launcher, or point"
				+ " it at the class that does the job now"));
		Walk w = new Walk(all, launched.keySet());
		check(w.production >= 4000, "the walk read the program's methods (" + w.production + " in production)");
		check(w.liveFromProgram("ctrmap/formats/garc/GARC", "getDecompressedEntry"),
				"control: GARC's entry reader is reached from the program's own roots");
		check(w.declared("ctrmap/formats/garc/GARC", "getDecompressedEntry")
				&& !w.declared("ctrmap/formats/h3d/model/H3DModel", "adjustBoneVerticesToMatrix"),
				"control: the walk sees declared methods, and the bone-matrix adjuster nothing called is gone");
		check(w.liveFromProgram("ctrmap/WorkspaceSession", "toString"),
				"control: a toString only the JDK calls (string concatenation) is reached - Object's methods are roots");

		commentsNameWhatExists(ClassFileScanner.application(classes), new File(repo, "src"));

		check(w.dead.isEmpty(), "no production method is left with no caller that runs"
				+ (w.dead.isEmpty() ? "" : " - these have none: " + w.dead + ". Delete them, or call them;"
				+ " a method nothing calls is a mechanism a reader believes exists"));
		System.out.println("  production methods only a suite calls: " + w.testOnly.size() + " " + w.testOnly);
		check(w.testOnly.size() <= TEST_ONLY_CEILING, "no more production methods live only through a suite than the "
				+ TEST_ONLY_CEILING + " recorded (it is " + w.testOnly.size() + ")"
				+ (w.testOnly.size() <= TEST_ONLY_CEILING ? "" : " - the new ones are among: " + w.testOnly));
		check(w.testOnly.size() == TEST_ONLY_CEILING, "and the recorded number is the measured one - lower it when"
				+ " one gains a caller or leaves (measured " + w.testOnly.size() + ", recorded " + TEST_ONLY_CEILING + ")");
		everyFieldIsRead(all);
		finish();
	}

	/**
	 * Every field the program declares is read by something (F204). A field nothing reads is state somebody
	 * keeps writing for a reader that does not exist - the audit measured 217 - and the method walk, which
	 * follows invokes only, could not see one: ClassFileScanner did not tell a read from a write.
	 *
	 * <p>A read is a getfield or getstatic, resolved through the superclasses to the class that declares the
	 * field. Not asked of: a compile-time constant (javac copies its value into every reader, so no
	 * instruction reads it), an enum constant, a synthetic field.
	 */
	static void everyFieldIsRead(List<ClassFileScanner.ClassFile> all) {
		System.out.println("--- every production field is read by something (F204)");
		Map<String, ClassFileScanner.ClassFile> byName = new java.util.HashMap<>();
		for (ClassFileScanner.ClassFile cf : all) {
			byName.put(cf.name, cf);
		}
		Set<String> byProgram = new HashSet<>(), bySuite = new HashSet<>(), throughAnother = new java.util.TreeSet<>();
		for (ClassFileScanner.ClassFile cf : all) {
			for (ClassFileScanner.Method m : cf.methods) {
				for (String read : m.reads) {
					String declared = declaringField(byName, read);
					if (declared != null) {
						(cf.isTest() ? bySuite : byProgram).add(declared);
						if (!cf.isTest() && !declared.equals(read)) {
							throughAnother.add(read + " -> " + declared);
						}
					}
				}
			}
		}
		check(throughAnother.contains("ctrmap/humaninterface/tools/CameraTool#host -> ctrmap/humaninterface/tools/AbstractTool#host"),
				"control: a read compiled against a subclass is the declaring class's field - the camera tool's host is"
				+ " AbstractTool's (" + throughAnother.size() + " reads the program names through another class)");
		check(byProgram.contains("ctrmap/formats/garc/GARC#entries") || byProgram.contains("ctrmap/formats/garc/GARC#length"),
				"control: a field the program reads is read - GARC's entry table");
		List<String> appUnread = new ArrayList<>(), layoutUnread = new ArrayList<>(), suiteOnly = new ArrayList<>();
		int asked = 0;
		for (ClassFileScanner.ClassFile cf : all) {
			if (cf.isTest()) {
				continue;
			}
			for (ClassFileScanner.Field f : cf.declaredFields) {
				if (f.isSynthetic() || f.isEnumConstant() || f.constant) {
					continue;
				}
				asked++;
				String key = cf.name + "#" + f.name;
				if (byProgram.contains(key)) {
					continue;
				}
				if (bySuite.contains(key)) {
					suiteOnly.add(f.toString());
				} else {
					(cf.name.startsWith("ctrmap/formats/") ? layoutUnread : appUnread).add(f.toString());
				}
			}
		}
		java.util.Collections.sort(appUnread);
		java.util.Collections.sort(layoutUnread);
		java.util.Collections.sort(suiteOnly);
		check(asked > 1000, "control: the program's fields are read from the class files (" + asked + " asked)");
		check(layoutUnread.contains("BCHHeader.magic"), "control: a field the parse only WRITES is not read - BCHHeader's"
				+ " magic, filled from the file and asked by nothing");
		check(appUnread.isEmpty(), "no field of the application outside the formats is read by nothing"
				+ (appUnread.isEmpty() ? "" : " - these are written for a reader that does not exist: " + appUnread
				+ ". Delete them, or read them"));
		//A FORMAT'S LAYOUT, KEPT AND COUNTED: a header field the parse fills because the bytes are there is the
		//file's own description; none of them may be added unread, and each that goes is counted down
		System.out.println("  format layout fields read by nothing: " + layoutUnread.size() + " " + layoutUnread);
		check(layoutUnread.size() <= FORMAT_UNREAD_CEILING, "no more format layout fields are read by nothing than the "
				+ FORMAT_UNREAD_CEILING + " recorded (it is " + layoutUnread.size() + ")");
		check(layoutUnread.size() == FORMAT_UNREAD_CEILING, "and the recorded number is the measured one (measured "
				+ layoutUnread.size() + ", recorded " + FORMAT_UNREAD_CEILING + ")");
		System.out.println("  production fields only a suite reads: " + suiteOnly.size() + " " + suiteOnly);
		check(suiteOnly.size() <= SUITE_ONLY_FIELD_CEILING, "no more production fields are read only by a suite than the "
				+ SUITE_ONLY_FIELD_CEILING + " recorded (it is " + suiteOnly.size() + ")");
		check(suiteOnly.size() == SUITE_ONLY_FIELD_CEILING, "and the recorded number is the measured one (measured "
				+ suiteOnly.size() + ", recorded " + SUITE_ONLY_FIELD_CEILING + ")");
	}

	/** Format layout fields nothing reads - measured 2026-10-08 when the scanner first told a read from a write. */
	private static final int FORMAT_UNREAD_CEILING = 120;
	/** Production fields only a suite reads - measured 2026-10-08, as above. */
	private static final int SUITE_ONLY_FIELD_CEILING = 36;

	/** The field a read names, as {@code declaringClass#name} - the class it was resolved against or a superclass. */
	static String declaringField(Map<String, ClassFileScanner.ClassFile> byName, String read) {
		int hash = read.indexOf('#');
		String owner = read.substring(0, hash), name = read.substring(hash + 1);
		java.util.ArrayDeque<String> look = new java.util.ArrayDeque<>();
		look.add(owner);
		while (!look.isEmpty()) {
			ClassFileScanner.ClassFile cf = byName.get(look.poll());
			if (cf == null) {
				continue;
			}
			if (cf.fields.contains(name)) {
				return cf.name + "#" + name;
			}
			if (cf.superName != null) {
				look.add(cf.superName);
			}
			look.addAll(cf.interfaces);
		}
		return null;
	}

	/** Class.member or Class#member, the shape a comment points at code with. */
	private static final Pattern POINTER = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)[.#]([a-zA-Z_][A-Za-z0-9_]*)\\b");
	private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*");

	/**
	 * A comment that points at a production class's member in prose names one that exists (F042).
	 * "is what CtrmapMainframe.repairSharedPaddingZones exists to undo" sent a reader to a method
	 * that had never been there by that name; three more pointers had drifted the same way. Old
	 * code QUOTED - inside {@code {@code ...}} or "double quotes" - may be history ("the gate was
	 * {@code !Workspace.isOA()}") and is not read; a pointer in running text or a link is a claim
	 * that the thing is there. A member is a method, a field or a nested class of the class or of
	 * an in-program ancestor; a class name two packages share is not judged.
	 */
	static void commentsNameWhatExists(List<ClassFileScanner.ClassFile> program, File src) throws IOException {
		Map<String, Set<String>> members = new HashMap<>();
		Map<String, String> parent = new HashMap<>();
		Map<String, Set<String>> bySimple = new HashMap<>();
		for (ClassFileScanner.ClassFile cf : program) {
			Set<String> own = new HashSet<>(cf.fields);
			for (ClassFileScanner.Method m : cf.methods) {
				own.add(m.name);
			}
			//MERGED, not put: a nested class read before its outer one has already added its own name
			members.computeIfAbsent(cf.name, k -> new HashSet<>()).addAll(own);
			parent.put(cf.name, cf.superName);
			String top = cf.topLevel();
			String simple = top.substring(top.lastIndexOf('/') + 1);
			Set<String> tops = bySimple.get(simple);
			if (tops == null) {
				tops = new TreeSet<>();
				bySimple.put(simple, tops);
			}
			tops.add(top);
			if (!cf.name.equals(top)) {
				members.computeIfAbsent(top, k -> new HashSet<>()).add(cf.name.substring(cf.name.lastIndexOf('$') + 1));
			}
		}
		List<String> broken = new ArrayList<>();
		int read = 0, judged = 0;
		for (File f : DialogSeamTest.javaSources(new File(src, "ctrmap"))) {
			if (f.getParentFile() != null && f.getParentFile().getName().equals("tests")) {
				continue;
			}
			read++;
			String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			Matcher c = COMMENT.matcher(text);
			while (c.find()) {
				for (String[] p : pointers(c.group())) {
					Set<String> tops = bySimple.get(p[0]);
					if (tops == null || tops.size() != 1) {
						continue;
					}
					judged++;
					if (!has(tops.iterator().next(), p[1], members, parent)) {
						int line = 1 + text.substring(0, c.start()).replaceAll("[^\\n]", "").length();
						broken.add(f.getName() + ":" + line + " " + p[0] + "." + p[1]);
					}
				}
			}
		}
		Set<String> window = bySimple.get("CtrmapMainframe");
		String top = window == null ? null : window.iterator().next();
		check(top != null && has(top, "frame", members, parent) && !has(top, "repairSharedPaddingZones", members, parent)
				&& pointers("/** see {@code A.b} and \"C.d\" and E.f */").size() == 1,
				"control: a real member is found, the method F042 named is not, and quoted code is not read");
		check(broken.isEmpty(), "every member a comment points at in prose exists (" + judged + " pointers in "
				+ read + " files)" + (broken.isEmpty() ? "" : " - these name nothing: " + broken + ". Point at what"
				+ " does the job now, or quote the old code as {@code ...} if the sentence is its history"));
	}

	/** {class, member} for each pointer in a comment's prose; quoted code blanked first. */
	static List<String[]> pointers(String comment) {
		String prose = blank(blank(comment, "\\{@code[^}]*\\}"), "\"[^\"\\n]*\"");
		List<String[]> out = new ArrayList<>();
		Matcher m = POINTER.matcher(prose);
		while (m.find()) {
			out.add(new String[]{m.group(1), m.group(2)});
		}
		return out;
	}

	private static String blank(String s, String regex) {
		Matcher m = Pattern.compile(regex).matcher(s);
		StringBuffer out = new StringBuffer();
		while (m.find()) {
			m.appendReplacement(out, m.group().replaceAll("(?s).", " "));
		}
		m.appendTail(out);
		return out.toString();
	}

	private static boolean has(String cls, String member, Map<String, Set<String>> members, Map<String, String> parent) {
		for (String c = cls; c != null && members.containsKey(c); c = parent.get(c)) {
			if (members.get(c).contains(member)) {
				return true;
			}
		}
		return false;
	}

	static boolean isMain(ClassFileScanner.Method m) {
		return "main".equals(m.name) && "([Ljava/lang/String;)V".equals(m.descriptor)
				&& (m.flags & ACC_STATIC) != 0;
	}

	/** A class name's shape: what a launcher names, and not whatever word follows the key (F141). */
	private static final String CLASS_NAME = "([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)(?=$|[\\s\"'`;)\\\\])";

	/** The command shapes that run a class. */
	private static final Pattern[] LAUNCH = {
		Pattern.compile("(?m)(?:^|[\\s\"'(&;|])javaw?(?:\\.exe)?\\s[^\\n]*?\\s(ctrmap(?:\\.\\w+)*\\.[A-Z]\\w*)(?=$|[\\s\"'`;)])"),
		Pattern.compile("(?m)^\\s*main\\.class\\s*=\\s*" + CLASS_NAME),
		Pattern.compile("(?m)Main-Class:\\s*" + CLASS_NAME),
		Pattern.compile("--main-class\\s+" + CLASS_NAME),
	};

	/**
	 * Every class a file in the repository runs, by internal name, with the files that run it - the
	 * files a commit would ship as git lists them, not a list of scripts: hidden folders, the
	 * libraries, the sources and the documentation are the only places not read, and Markdown is
	 * prose. Compiled output is out because git ignores it - build-ide/, the IDE's since F141, was
	 * read by a walk whose list of folders predated it. A file that is not UTF-8 text is not a
	 * launcher, and a repository git cannot list is UNKNOWN, not one with no launchers.
	 */
	static Map<String, Set<String>> launchedBy(File repo) throws IOException {
		List<String> listed = SourceSeamTest.shippable(repo);
		if (listed == null) {
			throw new IOException("git cannot list " + repo + " - the launchers there are UNKNOWN, not absent");
		}
		Map<String, Set<String>> out = new java.util.TreeMap<>();
		for (String rel : listed) {
			String[] parts = rel.split("/");
			boolean skip = rel.endsWith(".md") || parts.length > 1 && java.util.Arrays.asList("build", "src", "docs",
					"lib", "dist").contains(parts[0]);
			for (int i = 0; i < parts.length - 1; i++) {
				skip |= parts[i].startsWith(".");
			}
			File k = new File(repo, rel);
			if (skip || !k.isFile() || k.length() > 2_000_000) {
				continue;
			}
			String text;
			try {
				text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
						.decode(ByteBuffer.wrap(Files.readAllBytes(k.toPath()))).toString();
			} catch (CharacterCodingException binary) {
				continue;
			}
			for (String cls : launchesIn(text)) {
				Set<String> by = out.get(cls);
				if (by == null) {
					by = new TreeSet<>();
					out.put(cls, by);
				}
				by.add(rel);
			}
		}
		return out;
	}

	/** The classes one file's text runs, by internal name, in the order its launch shapes name them. */
	static List<String> launchesIn(String text) {
		List<String> out = new ArrayList<>();
		for (Pattern p : LAUNCH) {
			Matcher m = p.matcher(text);
			while (m.find()) {
				out.add(m.group(1).replaceAll("[\"'`]", "").replace('.', '/'));
			}
		}
		return out;
	}

	private static void finish() {
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** The reachability fixed point over one compiled program. */
	static final class Walk {

		final Map<String, ClassFileScanner.ClassFile> byName = new HashMap<>();
		/** owner -> its methods by name + descriptor. */
		final Map<String, Map<String, ClassFileScanner.Method>> declaredBy = new HashMap<>();
		/** owner -> the in-program classes that extend or implement it directly. */
		final Map<String, List<String>> children = new HashMap<>();
		final Set<ClassFileScanner.Method> liveAll;
		final Set<ClassFileScanner.Method> liveProgram;
		final Set<String> dead = new TreeSet<>();
		final Set<String> testOnly = new TreeSet<>();
		int production;

		Walk(List<ClassFileScanner.ClassFile> program, Set<String> launched) {
			//A NAME OR A HANDLE IS THE PROGRAM'S ONLY WHEN THE PROGRAM HOLDS IT: one a suite holds - a
			//reflective call, a method reference, the name in a check's message - reaches the method
			//through that suite, and only the walk from every root may count it
			Set<String> strings = new HashSet<>();
			Set<String> handles = new HashSet<>();
			Set<String> programStrings = new HashSet<>();
			Set<String> programHandles = new HashSet<>();
			for (ClassFileScanner.ClassFile cf : program) {
				byName.put(cf.name, cf);
				handles.addAll(cf.handles);
				if (reflects(cf)) {
					strings.addAll(cf.strings);
				}
				if (!cf.isTest()) {
					programHandles.addAll(cf.handles);
					if (reflects(cf)) {
						programStrings.addAll(cf.strings);
					}
				}
				Map<String, ClassFileScanner.Method> own = new HashMap<>();
				for (ClassFileScanner.Method m : cf.methods) {
					own.put(m.signature(), m);
				}
				declaredBy.put(cf.name, own);
				List<String> supers = new ArrayList<>(cf.interfaces);
				if (cf.superName != null) {
					supers.add(cf.superName);
				}
				for (String s : supers) {
					List<String> kids = children.get(s);
					if (kids == null) {
						kids = new ArrayList<>();
						children.put(s, kids);
					}
					kids.add(cf.name);
				}
			}
			Set<ClassFileScanner.Method> roots = new HashSet<>();
			Set<ClassFileScanner.Method> programRoots = new HashSet<>();
			for (ClassFileScanner.ClassFile cf : program) {
				Set<String> inherited = new HashSet<>();
				boolean leaves = ancestry(cf, inherited, new HashSet<String>());
				for (ClassFileScanner.Method m : cf.methods) {
					boolean always = (isMain(m) && (cf.isTest() || launched.contains(cf.name) || saysHowItRuns(cf)))
							|| "<init>".equals(m.name) || "<clinit>".equals(m.name)
							|| ((cf.flags & ACC_ENUM) != 0 && ("values".equals(m.name) || "valueOf".equals(m.name)))
							|| ((m.flags & (ACC_PRIVATE | ACC_STATIC)) == 0 && (leaves || inherited.contains(m.signature())
									|| OBJECT.contains(m.signature())));
					if (cf.isTest() || always || handles.contains(m.signature()) || strings.contains(m.name)) {
						roots.add(m);
					}
					if (!cf.isTest() && (always || programHandles.contains(m.signature())
							|| programStrings.contains(m.name))) {
						programRoots.add(m);
					}
				}
			}
			liveAll = reach(roots);
			liveProgram = reach(programRoots);
			for (ClassFileScanner.ClassFile cf : program) {
				if (cf.isTest()) {
					continue;
				}
				for (ClassFileScanner.Method m : cf.methods) {
					production++;
					if (m.name.startsWith("lambda$") || m.name.startsWith("access$")) {
						continue;   //the compiler's, reached through what it was made for
					}
					if (!liveAll.contains(m)) {
						dead.add(m.toString());
					} else if (!liveProgram.contains(m)) {
						testOnly.add(m.toString());
					}
				}
			}
		}

		/** Whether a class's own strings say how it is run: "Usage: java " and its own name. */
		private static boolean saysHowItRuns(ClassFileScanner.ClassFile cf) {
			String usage = "Usage: java " + cf.name.replace('/', '.');
			for (String str : cf.strings) {
				if (str.contains(usage)) {
					return true;
				}
			}
			return false;
		}

		/** Adds every in-tree ancestor's method signatures; true when the ancestry leaves the program. */
		private boolean ancestry(ClassFileScanner.ClassFile cf, Set<String> into, Set<String> seen) {
			boolean leaves = false;
			List<String> supers = new ArrayList<>(cf.interfaces);
			if (cf.superName != null) {
				supers.add(cf.superName);
			}
			for (String s : supers) {
				ClassFileScanner.ClassFile up = byName.get(s);
				if (up == null) {
					leaves |= !"java/lang/Object".equals(s);
					continue;
				}
				if (seen.add(s)) {
					for (ClassFileScanner.Method m : up.methods) {
						into.add(m.signature());
					}
					leaves |= ancestry(up, into, seen);
				}
			}
			return leaves;
		}

		private Set<ClassFileScanner.Method> reach(Set<ClassFileScanner.Method> from) {
			Set<ClassFileScanner.Method> live = new HashSet<>(from);
			Deque<ClassFileScanner.Method> todo = new ArrayDeque<>(from);
			while (!todo.isEmpty()) {
				for (String call : todo.pop().invokes) {
					int hash = call.indexOf('#');
					for (ClassFileScanner.Method m : targets(call.substring(0, hash), call.substring(hash + 1))) {
						if (live.add(m)) {
							todo.push(m);
						}
					}
				}
			}
			return live;
		}

		/**
		 * What an invoke of {@code sig} resolved against {@code owner} can run: the body {@code owner} or
		 * an ancestor declares, and every override a descendant declares. An owner outside the program
		 * reaches nothing here - a callback the platform makes into the program is an override, rooted.
		 */
		private Set<ClassFileScanner.Method> targets(String owner, String sig) {
			Set<ClassFileScanner.Method> out = new HashSet<>();
			if (!byName.containsKey(owner)) {
				return out;
			}
			Deque<String> up = new ArrayDeque<>();
			Set<String> seen = new HashSet<>();
			up.push(owner);
			while (!up.isEmpty()) {
				String c = up.pop();
				ClassFileScanner.ClassFile cf = byName.get(c);
				if (cf == null || !seen.add(c)) {
					continue;
				}
				ClassFileScanner.Method m = declaredBy.get(c).get(sig);
				if (m != null) {
					out.add(m);
				}
				for (String s : cf.interfaces) {
					up.push(s);
				}
				if (cf.superName != null) {
					up.push(cf.superName);
				}
			}
			Deque<String> down = new ArrayDeque<>();
			seen.clear();
			down.push(owner);
			while (!down.isEmpty()) {
				String c = down.pop();
				if (!seen.add(c)) {
					continue;
				}
				ClassFileScanner.Method m = declaredBy.containsKey(c) ? declaredBy.get(c).get(sig) : null;
				if (m != null) {
					out.add(m);
				}
				List<String> kids = children.get(c);
				if (kids != null) {
					for (String k : kids) {
						down.push(k);
					}
				}
			}
			return out;
		}

		/** Whether a class calls a reflection API, so that a string it holds may name a method it calls. */
		private static boolean reflects(ClassFileScanner.ClassFile cf) {
			for (ClassFileScanner.Ref r : cf.refs) {
				if (r.method && ("java/lang/Class".equals(r.owner) && r.name.matches("get(Declared)?Methods?")
						|| r.owner.startsWith("java/lang/invoke/MethodHandles"))) {
					return true;
				}
			}
			return false;
		}

		boolean declared(String owner, String name) {
			ClassFileScanner.ClassFile cf = byName.get(owner);
			if (cf == null) {
				return false;
			}
			for (ClassFileScanner.Method m : cf.methods) {
				if (m.name.equals(name)) {
					return true;
				}
			}
			return false;
		}

		boolean liveFromProgram(String owner, String name) {
			for (ClassFileScanner.Method m : liveProgram) {
				if (m.owner.equals(owner) && m.name.equals(name)) {
					return true;
				}
			}
			return false;
		}
	}
}
