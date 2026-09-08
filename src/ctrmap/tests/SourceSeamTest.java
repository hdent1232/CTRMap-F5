package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The game-seam and privacy guard: fails when game-specific knowledge leaks
 * outside its one allowed home, or when anything in the repository names a
 * real person's machine. Comments are stripped from Java before scanning, so
 * only live string literals count; other text files are scanned whole.
 * <ul>
 * <li>RomFS GARC paths ("a/0/3/9"-shaped literals) anywhere outside
 *     ctrmap.gamedef - per-game paths belong in a GameProfile;</li>
 * <li>the pristine-dump folder name in NON-test sources - a shared tool must
 *     never hardcode where one person keeps their dump;</li>
 * <li>an absolute path into somebody's home directory ("C:\Users\someone",
 *     "/home/someone/", "/Users/someone/") in ANY file, tests included;</li>
 * <li>System.setOut / System.setErr anywhere. The script editor once swapped
 *     both for its output box around an assembler call with no finally; the
 *     first assembler exception left every later diagnostic in the whole
 *     application - pack failures, the integrity report - going into a stale
 *     text area. Diagnostics are returned as values, never captured.</li>
 * <li>ASKING WHICH GAME IS LOADED, anywhere outside ctrmap.gamedef. Read
 *     {@link #noApplicationClassAsksWhichGameIsLoaded}: that rule is measured
 *     in BYTECODE, not in source text, because the other four here are greps
 *     and a grep cannot see a static import.</li>
 * <li>THE FORMAT LAYER REACHING ABOVE ITSELF: no class under ctrmap.formats
 *     may reference the window, the widgets, the dialogs or the global. Read
 *     {@link #noFormatClassReachesAboveItself}; bytecode again, and both
 *     member references and bare class references, because an
 *     {@code implements} holds no member.</li>
 * </ul>
 *
 * <p>The home-directory rule exists because the first two were not enough. Tests were
 * exempted wholesale on the reasoning that they run against a real dump by
 * design - so forty-five of them sat in the public repository with the
 * author's own home directory spelled out in full, and this guard passed
 * every time. Running against a dump justifies naming the dump folder; it
 * never justified naming the person. Tests are now exempt from the first two
 * rules only.
 *
 * <p>A line that genuinely must show an absolute path (documentation teaching
 * someone where their own files live) can carry the marker {@code SEAM-OK}.
 * That marker is a SOURCE LINE escape, so it does not exist for the bytecode
 * rule - a class file has no line to write it on. The escape there is the
 * {@link #ALLOWED_ASKERS} table, which costs a written reason and a ceiling.
 *
 * Usage: java ctrmap.tests.SourceSeamTest [src-root] [classes-root]
 *        (defaults "src" and "build/classes")
 */
public class SourceSeamTest {

	private static final Pattern GARC_PATH = Pattern.compile("\"[^\"]*\\ba/\\d/\\d/\\d");
	private static final Pattern DUMP_PATH = Pattern.compile("\"[^\"]*RomFS_original_garcs");
	/** Somebody's home directory, on any of the three platforms. */
	private static final Pattern HOME_PATH = Pattern.compile(
			"(?i)[a-z]:[\\\\/]+users[\\\\/]+[^\"\\\\/\\s*<>]+|/home/[^\"/\\s*<>]+/|/Users/[^\"/\\s*<>]+/");
	/** The JVM's diagnostic channel being pointed at a widget. */
	private static final Pattern STREAM_HIJACK = Pattern.compile("System\\.set(Out|Err)\\(");
	/** Text files worth scanning for home paths outside the source tree. */
	private static final String[] TEXT_EXT = {
		".md", ".txt", ".ps1", ".bat", ".cmd", ".sh", ".tsv", ".json", ".xml", ".properties", ".gitignore"
	};
	private static final String[] SKIP_DIRS = {".git", "build", "dist", "lib", "out", "target", "nbproject"};

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		File classes = new File(args.length > 1 ? args[1] : "build/classes");
		if (!new File(root, "ctrmap").isDirectory()) {
			System.out.println("FAIL: source root not found: " + root.getAbsolutePath());
			System.exit(1);
		}
		List<String> violations = new ArrayList<>();
		int scanned = scan(new File(root, "ctrmap"), violations);
		File repo = root.getAbsoluteFile().getParentFile();
		int texts = scanText(repo, violations);
		for (String v : violations) {
			System.out.println("  LEAK: " + v);
		}
		System.out.println("seam guard: " + scanned + " sources + " + texts + " text files scanned, "
				+ violations.size() + " leak(s)");

		int asking = noApplicationClassAsksWhichGameIsLoaded(classes);
		int above = noFormatClassReachesAboveItself(classes);

		boolean ok = violations.isEmpty() && asking == 0 && above == 0;
		System.out.println(ok ? "ALL PASS" : "FAILURES PRESENT");
		if (!ok) {
			System.exit(1);
		}
	}

	// ---------------------------------------------------- the game-identity rule

	/**
	 * The classes that may still ask which game is loaded, as
	 * {internal name, what they hold, why it is still there}.
	 *
	 * <p>Named classes with a written reason, not a count: a ceiling on the
	 * NUMBER of askers would be satisfied by any class at all, and the point of
	 * the rule is which ones. Shrinking this list is free; adding to it costs
	 * an argument somebody has to read. Same ratchet DialogSeamTest puts on its
	 * raw dialogs.
	 */
	private static final String[][] ALLOWED_ASKERS = {
		{"ctrmap/humaninterface/ZoneLoadingPanel",
			"Workspace.game() against GameType.XY / GameType.ORAS",
			"the zone header's per-game VOCABULARY is still spelled in this panel: which map-type"
			+ " names to offer, how the weather dropdown's indices map to the raw values, and"
			+ " whether a header's flashable-darkness and special-walking flags mean anything."
			+ " Those are per-game TABLES, and they move behind GameProfile as data (a name list"
			+ " and an index map per game) - not as another boolean. Everything in this file that"
			+ " DECIDED something has already moved: the zone count and the master-table index"
			+ " come from ZoneTables, the add-zones button and the fork offer from"
			+ " GameProfile.Feature. What is left cannot silently write a wrong record; it can"
			+ " only label a dropdown."}
	};

	/** Mirrors ALLOWED_ASKERS.length. Raising it is the decision this makes visible. */
	private static final int ALLOWED_ASKERS_CEILING = 1;

	/** The holder of the open game, and the session behind it. */
	private static final String WORKSPACE = "ctrmap/Workspace";
	private static final String SESSION = "ctrmap/WorkspaceSession";
	private static final String GAME_TYPE = "ctrmap/gamedef/GameType";
	private static final String PROFILE = "ctrmap/gamedef/GameProfile";
	/**
	 * The three gates the migration deleted. Named here so that re-adding one -
	 * anywhere, under any spelling - fails immediately instead of quietly
	 * starting the ramp over.
	 */
	private static final String[] GATES = {"isOA", "isXY", "isOADemo"};
	/** Reading the open game's identity: Workspace.game(), WorkspaceSession.game(). */
	private static final String GAME_QUERY = "game";
	/** ...and the way round it, asking the profile which game it is FOR. */
	private static final String PROFILE_QUERY = "type";

	/**
	 * No application class outside {@code ctrmap.gamedef} may ask WHICH GAME is
	 * loaded. Two shapes are refused:
	 * <ol>
	 * <li>a reference to {@code Workspace.isOA/isXY/isOADemo} or the same three
	 *     on {@code WorkspaceSession} - deleted in this change, and kept
	 *     deleted here;</li>
	 * <li>holding BOTH an identity query ({@code Workspace.game()},
	 *     {@code WorkspaceSession.game()}, {@code GameProfile.type()}) and a
	 *     {@code GameType} constant, which is what
	 *     {@code game() == GameType.XY} compiles to.</li>
	 * </ol>
	 *
	 * <h2>Why the second shape is a pair and not a comparison</h2>
	 * The constant pool records what a class REFERENCES, not what its methods
	 * do with it, so this cannot see the {@code if_acmpne} that joins them. It
	 * therefore asks the question that pool can answer: does this class both
	 * fetch the game's identity and name a particular game. Passing classes
	 * either hand the identity on (NPCEditForm gives it to
	 * {@code WarpTransitions.labels(GameType)}) or receive one (WarpTransitions
	 * itself, ZoneHeader), and neither is asking. Handing a GameType around is
	 * the migration's whole point; the defect is deciding on one.
	 *
	 * <h2>Why bytecode and not a regular expression</h2>
	 * The other four rules in this file are greps, and a grep reads one
	 * spelling. {@code import static ctrmap.Workspace.isOA;} followed by a bare
	 * {@code isOA()} defeats a {@code Workspace\.isOA} pattern completely, and
	 * 32 files in this repository already name the mainframe's widgets that way
	 * - not to dodge anything, just ordinary Java. The class file records
	 * {@code invokestatic ctrmap/Workspace.isOA} either way. See
	 * {@link ClassFileScanner}, which exists for this.
	 *
	 * <h2>What it does NOT claim</h2>
	 * Inner and anonymous classes are folded into their outer class, so a
	 * listener asking on its panel's behalf reports as the panel. And a class
	 * that constructs a session for a fixed game ({@code ctrmap.tools}'s
	 * harvesters, which open ORAS dumps) names a GameType without asking
	 * anything, so it passes - correctly: it is not reading the identity of a
	 * game somebody else opened.
	 *
	 * @return the number of violations, so the caller can fail the suite
	 */
	static int noApplicationClassAsksWhichGameIsLoaded(File classesRoot) throws Exception {
		//Never "0 violations found in nothing": a guard that measures an empty
		//or half-built tree and passes is the failure this battery exists to
		//refuse. Workspace is the class the rule is ABOUT, so its absence means
		//whatever is in this folder, it is not a build of this program.
		if (!new File(classesRoot, WORKSPACE + ".class").isFile()) {
			System.out.println("  FAIL: no compiled " + WORKSPACE + ".class under "
					+ classesRoot.getAbsolutePath()
					+ " - run build.ps1 first; this rule reads class files, not source");
			return 1;
		}
		List<ClassFileScanner.ClassFile> app = ClassFileScanner.application(classesRoot);
		//what each top-level application class holds, folded across its inner
		//classes: an anonymous worker asks on its panel's behalf
		Map<String, Set<String>> held = new LinkedHashMap<>();
		for (ClassFileScanner.ClassFile cf : app) {
			if (cf.name.startsWith("ctrmap/gamedef/")) {
				continue; //the seam itself is where a game may be named
			}
			for (ClassFileScanner.Ref r : cf.refs) {
				String token = null;
				if (r.method && (r.owner.equals(WORKSPACE) || r.owner.equals(SESSION))) {
					for (String g : GATES) {
						if (r.name.equals(g)) {
							token = "GATE:" + ClassFileScanner.simpleName(r.owner) + "." + r.name + "()";
						}
					}
					if (token == null && r.name.equals(GAME_QUERY)) {
						token = "ASK:" + ClassFileScanner.simpleName(r.owner) + ".game()";
					}
				} else if (r.method && r.owner.equals(PROFILE) && r.name.equals(PROFILE_QUERY)) {
					token = "ASK:GameProfile.type()";
				} else if (!r.method && r.owner.equals(GAME_TYPE)) {
					token = "NAME:GameType." + r.name;
				}
				if (token != null) {
					Set<String> s = held.get(cf.topLevel());
					if (s == null) {
						s = new LinkedHashSet<>();
						held.put(cf.topLevel(), s);
					}
					s.add(token);
				}
			}
		}

		List<String> asked = new ArrayList<>();
		int allowedSeen = 0;
		for (Map.Entry<String, Set<String>> e : held.entrySet()) {
			List<String> gates = pick(e.getValue(), "GATE:");
			List<String> asks = pick(e.getValue(), "ASK:");
			List<String> names = pick(e.getValue(), "NAME:");
			boolean decides = !asks.isEmpty() && !names.isEmpty();
			if (gates.isEmpty() && !decides) {
				continue;
			}
			if (allowedFor(e.getKey()) != null) {
				//an allowed class may keep the comparison; it may NOT bring the
				//deleted gates back, which is a different thing entirely
				if (gates.isEmpty()) {
					allowedSeen++;
					continue;
				}
			}
			asked.add(e.getKey() + " holds " + (gates.isEmpty() ? asks + " and " + names : gates.toString()));
		}
		for (String v : asked) {
			System.out.println("  ASKS: " + v + " - ask ctrmap.gamedef.GameProfile what the game"
					+ " CAN DO (supports) or what somebody MEASURED about it, never which game it is");
		}
		boolean argued = ALLOWED_ASKERS.length <= ALLOWED_ASKERS_CEILING;
		for (String[] a : ALLOWED_ASKERS) {
			argued &= a[2] != null && a[2].trim().length() > 40;
		}
		if (!argued) {
			System.out.println("  ASKS: the allowed list grew past its ceiling of " + ALLOWED_ASKERS_CEILING
					+ ", or an entry gives no reason - " + ALLOWED_ASKERS.length + " entry/entries");
		}
		System.out.println("game-identity rule: " + app.size() + " application class file(s) read, "
				+ asked.size() + " asking which game is loaded, " + allowedSeen + " of "
				+ ALLOWED_ASKERS.length + " allowed exception(s) still present");
		return asked.size() + (argued ? 0 : 1);
	}

	/** The tokens of one kind, without their prefix, for a readable report. */
	private static List<String> pick(Set<String> tokens, String prefix) {
		List<String> out = new ArrayList<>();
		for (String t : tokens) {
			if (t.startsWith(prefix)) {
				out.add(t.substring(prefix.length()));
			}
		}
		return out;
	}

	private static String[] allowedFor(String internalName) {
		for (String[] a : ALLOWED_ASKERS) {
			if (a[0].equals(internalName)) {
				return a;
			}
		}
		return null;
	}

	// ---------------------------------------------------- the layering rule

	/**
	 * The classes under {@code ctrmap.formats} that may still name something
	 * above the format layer, as {internal name, what it names, why it is
	 * still there}. Named classes with a written reason, not a count, for the
	 * reason {@link #ALLOWED_ASKERS} gives. Both entries are a CLASS reference
	 * with no member behind it - an {@code implements} clause - which is
	 * exactly the shape a rule counting member edges alone would never see.
	 */
	private static final String[][] ALLOWED_ABOVE = {
		{"ctrmap/formats/propdata/GRProp", "ctrmap/humaninterface/MapObject",
			"implements MapObject, the interface the viewport selects and drags a placed thing"
			+ " through. The interface is declared beside the widgets but describes a placed"
			+ " thing (its position, its rotation, its name), which is format-layer knowledge;"
			+ " a later step moves it down into ctrmap.formats, and this entry goes with it."
			+ " Until then the prop names the interface and touches no member of its package."},
		{"ctrmap/formats/zone/ZoneEntities", "ctrmap/humaninterface/MapObject",
			"ZoneEntities.NPC implements MapObject for the same reason and moves in the same"
			+ " step; the inner class folds to its outer one here, as everywhere in this battery."}
	};

	/** Mirrors ALLOWED_ABOVE.length. Raising it is the decision this makes visible. */
	private static final int ALLOWED_ABOVE_CEILING = 2;

	/**
	 * The members of {@code ctrmap.Utils} the format layer may use: pure byte
	 * and number helpers, measured 2026-09-08 as the ONLY ones it does use.
	 * Utils also holds a dialog wrapper ({@code askToKeep}, answered with its
	 * {@code Keep} enum), and a dialog reached through a helper's name is still
	 * a dialog in the format layer. Naming the helpers that are allowed, rather
	 * than the wrappers that are not, means a wrapper added to Utils tomorrow
	 * is refused here before anyone has to know it exists.
	 */
	private static final String[] PURE_UTILS = {
		"ba2int", "checkBCHMagic", "getPadding", "impreciseFloatEquals", "isUTF8Capital"
	};
	/**
	 * Distinct (format class, Utils member) edges, measured 2026-09-08: seven
	 * classes, one helper each (WavefrontOBJ, CameraCoordinates,
	 * AbstractGamefreakContainer, ContainerIdentifier, MM, Tilemap, ZoneHeader).
	 * Lower it when one stops; raising it is a review.
	 */
	private static final int PURE_UTILS_EDGES = 7;

	private static final String FORMATS = "ctrmap/formats/";
	private static final String HUMANINTERFACE = "ctrmap/humaninterface/";
	private static final String MAINFRAME = "ctrmap/CtrmapMainframe";
	private static final String UI = "ctrmap/Ui";
	private static final String UTILS = "ctrmap/Utils";
	private static final String KEEP = "ctrmap/Utils$Keep";
	private static final String ASK_TO_KEEP = "askToKeep";

	/**
	 * No class under {@code ctrmap.formats} may reference
	 * {@code ctrmap.humaninterface}, {@code ctrmap.CtrmapMainframe},
	 * {@code ctrmap.Ui}, {@code ctrmap.Workspace}, or {@code ctrmap.Utils}'
	 * dialog wrapper and its answer: the widgets, the window, the dialogs, the
	 * global. A format class is handed what it needs and says what happened in
	 * its return value; it asks nobody anything and looks nothing up.
	 *
	 * <h2>Why this rule exists</h2>
	 * It is the ratchet the decoupling campaign was building towards. Sixteen
	 * format classes fetched the open game from the global; several opened
	 * dialogs (a texture-pack import asked "keep the changes?" from inside the
	 * container write, so no suite could drive the write without a person at
	 * the keyboard); one read the main window's zone panel. Each was migrated
	 * under its own suite ({@link GameFilesSeamTest}, {@link HandedGameTest},
	 * {@link DialogSeamTest}); this is the rule that keeps all of it migrated,
	 * in one place, for every class under the package at once, including the
	 * ones nobody has written yet.
	 *
	 * <h2>Two kinds of reference</h2>
	 * A MEMBER reference is a call or a field read: {@code Ui.error(...)},
	 * {@code Workspace.session()}. A CLASS reference is the name of a type with
	 * no member behind it: an {@code implements}, a parameter type, a cast.
	 * {@link ClassFileScanner.ClassFile#refs} holds the first kind and
	 * {@link ClassFileScanner.ClassFile#classes} the second, and this rule
	 * asks both, because {@code GRProp implements MapObject} is a real edge
	 * from the format layer to the widget package and holds not one member. A
	 * class reference is reported only where no member edge to the same class
	 * was, so one dialog call reads as one line, not two.
	 *
	 * <h2>Why bytecode</h2>
	 * The same reason as {@link #noApplicationClassAsksWhichGameIsLoaded}: a
	 * grep for {@code Ui\.error} is defeated by {@code import static
	 * ctrmap.Ui.error;} followed by a bare {@code error(...)}, and 33 files in
	 * this tree already static-import a class and use bare names. The class
	 * file holds {@code invokestatic ctrmap/Ui.error} either way. Proven by
	 * breaking, both ways: a {@code ctrmap.Ui.error} call added to a container
	 * class failed here naming the class and the member, and the same call
	 * through a static import with a bare name failed with the same line.
	 *
	 * <h2>The Utils rule</h2>
	 * Utils is five unrelated helper groups in one file, and the format layer
	 * legitimately uses the pure ones. So the rule is positive: the ONLY Utils
	 * members the format layer uses are {@link #PURE_UTILS}, over exactly
	 * {@link #PURE_UTILS_EDGES} edges. A dialog wrapper that crept back in
	 * under a helper's name would be one member too many.
	 *
	 * @return the number of violations, so the caller can fail the suite
	 */
	static int noFormatClassReachesAboveItself(File classesRoot) throws Exception {
		if (!new File(classesRoot, WORKSPACE + ".class").isFile()) {
			System.out.println("  FAIL: no compiled " + WORKSPACE + ".class under "
					+ classesRoot.getAbsolutePath()
					+ " - run build.ps1 first; this rule reads class files, not source");
			return 1;
		}
		List<ClassFileScanner.ClassFile> app = ClassFileScanner.application(classesRoot);
		List<String> above = new ArrayList<>();
		Set<String> allowedSeen = new LinkedHashSet<>();
		Map<String, Set<String>> utilsUsed = new LinkedHashMap<>();
		int formatClasses = 0;
		for (ClassFileScanner.ClassFile cf : app) {
			if (!cf.name.startsWith(FORMATS)) {
				continue;
			}
			formatClasses++;
			Set<String> memberOwners = new LinkedHashSet<>();
			for (ClassFileScanner.Ref r : cf.refs) {
				boolean wrapper = r.owner.equals(UTILS) && r.name.equals(ASK_TO_KEEP);
				if (isAbove(r.owner) || wrapper) {
					memberOwners.add(r.owner);
					above.add(cf.topLevel() + (r.method ? " calls " : " reads ") + r.owner + "." + r.name);
				}
				if (r.owner.equals(UTILS)) {
					Set<String> names = utilsUsed.get(cf.topLevel());
					if (names == null) {
						names = new LinkedHashSet<>();
						utilsUsed.put(cf.topLevel(), names);
					}
					names.add(r.name);
				}
			}
			for (String c : cf.classes) {
				if (!(isAbove(c) || c.equals(KEEP)) || memberOwners.contains(c)) {
					continue;
				}
				if (allowedAbove(cf.topLevel(), c) != null) {
					allowedSeen.add(cf.topLevel() + " " + c);
					continue;
				}
				above.add(cf.topLevel() + " names " + c + " (a type, with no member behind it)");
			}
		}
		for (String v : above) {
			System.out.println("  ABOVE: " + v + " - a format class is handed what it needs and answers in"
					+ " its return value; it opens no dialog, reads no widget and reaches no global");
		}

		//the allowed list: within its ceiling, every entry argued, and none stale
		boolean argued = ALLOWED_ABOVE.length <= ALLOWED_ABOVE_CEILING;
		for (String[] a : ALLOWED_ABOVE) {
			argued &= a[2] != null && a[2].trim().length() > 40;
			if (!allowedSeen.contains(a[0] + " " + a[1])) {
				System.out.println("  ABOVE: the allowed entry " + a[0] + " -> " + a[1]
						+ " is no longer in the bytecode - delete it, so the list only ever shrinks");
				argued = false;
			}
		}
		if (ALLOWED_ABOVE.length > ALLOWED_ABOVE_CEILING) {
			System.out.println("  ABOVE: the allowed list grew past its ceiling of " + ALLOWED_ABOVE_CEILING
					+ " - " + ALLOWED_ABOVE.length + " entries");
		}

		//Utils: only the named pure helpers, over exactly the recorded number of edges
		Set<String> pure = new LinkedHashSet<>(java.util.Arrays.asList(PURE_UTILS));
		Set<String> names = new java.util.TreeSet<>();
		int utilsEdges = 0;
		List<String> impure = new ArrayList<>();
		for (Map.Entry<String, Set<String>> e : utilsUsed.entrySet()) {
			for (String n : e.getValue()) {
				names.add(n);
				utilsEdges++;
				if (!pure.contains(n)) {
					impure.add(e.getKey() + " uses Utils." + n);
				}
			}
		}
		for (String v : impure) {
			System.out.println("  ABOVE: " + v + " - not one of the pure helpers " + pure
					+ "; a dialog wrapper reached through a helper's name is still a dialog");
		}
		boolean utilsOk = impure.isEmpty() && utilsEdges == PURE_UTILS_EDGES;
		if (utilsEdges != PURE_UTILS_EDGES) {
			System.out.println("  ABOVE: the format layer holds " + utilsEdges + " (class, Utils member) edges, "
					+ PURE_UTILS_EDGES + " recorded - " + (utilsEdges < PURE_UTILS_EDGES
							? "lower PURE_UTILS_EDGES" : "a new use of Utils in the format layer is a review"));
		}

		System.out.println("layering rule: " + formatClasses + " format class file(s) read, " + above.size()
				+ " reaching above the format layer, " + allowedSeen.size() + " of " + ALLOWED_ABOVE.length
				+ " allowed exception(s) still present; Utils members used: " + names + " over " + utilsEdges
				+ " edge(s)");
		return above.size() + (argued ? 0 : 1) + (utilsOk ? 0 : 1);
	}

	/** The window, the widgets, the dialogs, the global - by internal name, nested types included. */
	private static boolean isAbove(String owner) {
		return owner.startsWith(HUMANINTERFACE)
				|| owner.equals(MAINFRAME) || owner.startsWith(MAINFRAME + "$")
				|| owner.equals(UI) || owner.startsWith(UI + "$")
				|| owner.equals(WORKSPACE) || owner.startsWith(WORKSPACE + "$");
	}

	private static String[] allowedAbove(String internalName, String named) {
		for (String[] a : ALLOWED_ABOVE) {
			if (a[0].equals(internalName) && a[1].equals(named)) {
				return a;
			}
		}
		return null;
	}

	/**
	 * Sweeps the repository's non-Java text - READMEs, build scripts, data
	 * tables - for home paths. The Java scan alone would have missed the
	 * documentation, which is where a path is most likely to be pasted.
	 */
	private static int scanText(File dir, List<String> out) throws Exception {
		int n = 0;
		File[] files = dir.listFiles();
		if (files == null) {
			return 0;
		}
		for (File f : files) {
			if (f.isDirectory()) {
				boolean skip = false;
				for (String s : SKIP_DIRS) {
					skip |= f.getName().equalsIgnoreCase(s);
				}
				if (!skip) {
					n += scanText(f, out);
				}
				continue;
			}
			boolean text = false;
			for (String e : TEXT_EXT) {
				text |= f.getName().toLowerCase().endsWith(e);
			}
			if (!text) {
				continue;
			}
			n++;
			String[] lines = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n", -1);
			for (int i = 0; i < lines.length; i++) {
				if (lines[i].contains("SEAM-OK")) {
					continue;
				}
				if (HOME_PATH.matcher(lines[i]).find()) {
					out.add(f.getName() + ":" + (i + 1) + " home directory in a shipped file: " + lines[i].trim());
				}
			}
		}
		return n;
	}

	private static int scan(File dir, List<String> out) throws Exception {
		int n = 0;
		File[] files = dir.listFiles();
		if (files == null) {
			return 0;
		}
		for (File f : files) {
			if (f.isDirectory()) {
				n += scan(f, out);
				continue;
			}
			if (!f.getName().endsWith(".java")) {
				continue;
			}
			String path = f.getPath().replace('\\', '/');
			boolean isTest = path.contains("/ctrmap/tests/");
			boolean isGamedef = path.contains("/ctrmap/gamedef/");
			//this guard spells the patterns out, so it cannot scan itself
			boolean isSelf = f.getName().equals("SourceSeamTest.java");
			n++;
			String src = stripComments(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
			String[] lines = src.split("\n", -1);
			for (int i = 0; i < lines.length; i++) {
				String line = lines[i];
				if (line.contains("SEAM-OK") || isSelf) {
					continue;
				}
				if (!isTest && !isGamedef && GARC_PATH.matcher(line).find()) {
					out.add(f.getName() + ":" + (i + 1) + " GARC path literal outside gamedef: " + line.trim());
				}
				if (!isTest && DUMP_PATH.matcher(line).find()) {
					out.add(f.getName() + ":" + (i + 1) + " hardcoded dump location: " + line.trim());
				}
				//no exemption: a test may name the dump folder, never its owner
				if (HOME_PATH.matcher(line).find()) {
					out.add(f.getName() + ":" + (i + 1) + " home directory in a shipped file: " + line.trim());
				}
				if (STREAM_HIJACK.matcher(line).find()) {
					out.add(f.getName() + ":" + (i + 1) + " redirects System.out/err: " + line.trim());
				}
			}
		}
		return n;
	}

	/** Removes // and block comments while preserving line numbers and strings. */
	static String stripComments(String src) {
		StringBuilder out = new StringBuilder(src.length());
		boolean inStr = false, inChar = false, inLine = false, inBlock = false;
		for (int i = 0; i < src.length(); i++) {
			char c = src.charAt(i);
			char n = i + 1 < src.length() ? src.charAt(i + 1) : 0;
			if (inLine) {
				if (c == '\n') {
					inLine = false;
					out.append(c);
				}
				continue;
			}
			if (inBlock) {
				if (c == '*' && n == '/') {
					inBlock = false;
					i++;
				} else if (c == '\n') {
					out.append(c);
				}
				continue;
			}
			if (inStr) {
				out.append(c);
				if (c == '\\') {
					if (i + 1 < src.length()) {
						out.append(n);
						i++;
					}
				} else if (c == '"') {
					inStr = false;
				}
				continue;
			}
			if (inChar) {
				out.append(c);
				if (c == '\\') {
					if (i + 1 < src.length()) {
						out.append(n);
						i++;
					}
				} else if (c == '\'') {
					inChar = false;
				}
				continue;
			}
			if (c == '/' && n == '/') {
				inLine = true;
				i++;
				continue;
			}
			if (c == '/' && n == '*') {
				inBlock = true;
				i++;
				continue;
			}
			if (c == '"') {
				inStr = true;
			} else if (c == '\'') {
				inChar = true;
			}
			out.append(c);
		}
		return out.toString();
	}
}
