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

		boolean ok = violations.isEmpty() && asking == 0;
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
