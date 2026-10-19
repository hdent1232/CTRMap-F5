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
 * only live string literals count; every other file git would ship is read
 * whole when its bytes are text (F141: it was a suffix list that skipped
 * nbproject by name).
 * <ul>
 * <li>RomFS GARC paths ("a/0/3/9"-shaped literals) anywhere outside
 *     ctrmap.gamedef and the suites - per-game paths belong in a GameProfile.
 *     The suites are exempt (see below) and their literals are COUNTED under
 *     a ceiling that may only fall (F154): every one is a suite that can be
 *     pointed only at an ORAS dump;</li>
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

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		File classes = new File(args.length > 1 ? args[1] : "build/classes");
		if (!new File(root, "ctrmap").isDirectory()) {
			System.out.println("FAIL: source root not found: " + root.getAbsolutePath());
			System.exit(1);
		}
		List<String> violations = new ArrayList<>();
		//THE MARKER, KNOWN-ANSWER: a scratch source with a home path, once marked in a comment
		//and once not - the marker was looked for after comments were stripped and never fired
		File marked = new File(Scratch.dir("ctrmap_seammark"), "Marked.java");
		String home = "/home/" + "someone" + "/";
		Files.write(marked.toPath(), ("class Marked {\n\tString a = \"" + home + "x\"; // SEAM-OK: teaching a path\n"
				+ "\tString b = \"" + home + "y\";\n}\n").getBytes(StandardCharsets.UTF_8));
		List<String> markLeaks = new ArrayList<>();
		scan(marked.getParentFile(), markLeaks);
		boolean markerWorks = markLeaks.size() == 1 && markLeaks.get(0).startsWith("Marked.java:3 ");
		System.out.println((markerWorks ? "  ok: " : "  FAIL: ") + "a line marked SEAM-OK in its comment is let through and"
				+ " the unmarked one is not: " + markLeaks);
		if (!markerWorks) {
			violations.add("the SEAM-OK control: " + markLeaks);
		}
		redirectsSeen = 0;
		suitePathLines = 0;
		int scanned = scan(new File(root, "ctrmap"), violations);
		if (suitePathLines != SUITE_PATH_CEILING) {
			violations.add("the suites hold " + suitePathLines + " line(s) with an archive-path literal; the recorded"
					+ " ceiling is " + SUITE_PATH_CEILING + (suitePathLines > SUITE_PATH_CEILING ? " - route the new one"
					+ " through GameProfile.archivePath" : " - lower the ceiling to the measured count"));
		}
		//THE TEXT SCAN, KNOWN-ANSWER (F141): a scratch repository with a home path in a file with no
		//suffix, under nbproject/private, in a .py, in an ignored file, in a binary, on a SEAM-OK line and
		//in a Java source - the first three found, the rest not. The scan read a suffix list and skipped
		//nbproject by name, and the leak it was written for sat in nbproject.
		File known = Scratch.dir("ctrmap_seamtext");
		Map<String, byte[]> plant = new LinkedHashMap<>();
		plant.put("hook", ("cd " + home + "x\n").getBytes(StandardCharsets.UTF_8));
		plant.put("nbproject/private/private.xml", ("<file>" + home + "x</file>\n").getBytes(StandardCharsets.UTF_8));
		plant.put("tool.py", ("BASE = \"" + home + "x\"\n").getBytes(StandardCharsets.UTF_8));
		plant.put(".gitignore", "ignored.txt\n".getBytes(StandardCharsets.UTF_8));
		plant.put("ignored.txt", (home + "x\n").getBytes(StandardCharsets.UTF_8));
		plant.put("blob.bin", ("\0" + home + "x\n").getBytes(StandardCharsets.UTF_8));
		plant.put("marked.md", (home + "x - SEAM-OK\n").getBytes(StandardCharsets.UTF_8));
		plant.put("src/ctrmap/A.java", ("class A { String a = \"" + home + "x\"; }\n").getBytes(StandardCharsets.UTF_8));
		for (Map.Entry<String, byte[]> e : plant.entrySet()) {
			File f = new File(known, e.getKey());
			f.getParentFile().mkdirs();
			Files.write(f.toPath(), e.getValue());
		}
		HooksWiredTest.git(known, "init", "-q");
		List<String> textLeaks = new ArrayList<>();
		int textsKnown = scanText(known, new File(known, "src/ctrmap"), textLeaks);
		textLeaks.sort(null);
		//and a folder git cannot list - its .git names nowhere - is said, never read as a clean one
		File unlisted = Scratch.dir("ctrmap_seamunlisted");
		Files.write(new File(unlisted, ".git").toPath(), "gitdir: nowhere\n".getBytes(StandardCharsets.UTF_8));
		List<String> unread = new ArrayList<>();
		scanText(unlisted, new File(unlisted, "src/ctrmap"), unread);
		boolean textScanReads = textLeaks.size() == 3 && textLeaks.get(0).startsWith("hook:1 ")
				&& textLeaks.get(1).startsWith("nbproject/private/private.xml:1 ") && textLeaks.get(2).startsWith("tool.py:1 ")
				&& unread.size() == 1 && unread.get(0).contains("could not list");
		System.out.println((textScanReads ? "  ok: " : "  FAIL: ") + "the text scan reads every file git would ship, by its"
				+ " bytes - " + textsKnown + " read, found in exactly the suffixless hook, nbproject/private and the .py: "
				+ textLeaks + "; and a folder git cannot list is said: " + unread);
		if (!textScanReads) {
			violations.add("the text-scan control: " + textLeaks);
		}
		File repo = root.getAbsoluteFile().getParentFile();
		int texts = scanText(repo, new File(root, "ctrmap"), violations);
		for (String v : violations) {
			System.out.println("  LEAK: " + v);
		}
		if (!Ratchet.tight(ALLOWED_REDIRECTS.length, ALLOWED_REDIRECTS_CEILING)
				|| redirectsSeen != ALLOWED_REDIRECTS.length) {
			violations.add(Ratchet.said("allowed-redirect", ALLOWED_REDIRECTS.length, ALLOWED_REDIRECTS_CEILING)
					+ ", and " + redirectsSeen + " of the allowed redirects are still in the source");
			System.out.println("  LEAK: " + violations.get(violations.size() - 1));
		}
		System.out.println("seam guard: " + scanned + " sources + " + texts + " text files scanned, "
				+ violations.size() + " leak(s), " + redirectsSeen + " allowed redirect(s)");

		int asking = noApplicationClassAsksWhichGameIsLoaded(classes);
		int above = noFormatClassReachesAboveItself(classes);
		int facade = theFacadeDoesNotKnowTheWindow(classes);
		int toolReach = theToolsDoNotKnowTheWindow(classes);
		int retyped = noProfileCountIsRetyped(root);
		int oneString = noCommandIsRunAsOneString(classes);
		int byIndex = noEnumIsReadByIndex(root);

		boolean ok = violations.isEmpty() && asking == 0 && above == 0 && facade == 0 && toolReach == 0 && retyped == 0
				&& oneString == 0 && byIndex == 0;
		System.out.println(ok ? "ALL PASS" : "FAILURES PRESENT");
		if (!ok) {
			System.exit(1);
		}
	}

	// ---------------------------------------------------- the enum-by-number rule

	private static final Pattern BY_INDEX = Pattern.compile("\\.values\\(\\)\\s*\\[");

	/**
	 * No program file turns a number into an enum member by indexing {@code values()} (F079): the
	 * Ohana port did it in 49 places with raw bits read from a BCH, so a value it did not list threw
	 * and the whole file failed to open, over fields nothing drew. {@link ctrmap.util.Bytes#enumOf}
	 * is the one way, and its caller says what an unlisted number reads as, or that it is refused.
	 * Read from the source with comments removed; the helper is asked its three answers first.
	 */
	static int noEnumIsReadByIndex(File root) throws Exception {
		System.out.println("--- no number becomes an enum member by indexing values()");
		int bad = 0;
		Thread.State[] states = Thread.State.values();
		boolean listed = ctrmap.util.Bytes.enumOf(states, 1, null) == states[1];
		boolean standIn = ctrmap.util.Bytes.enumOf(states, 99, Thread.State.NEW) == Thread.State.NEW
				&& ctrmap.util.Bytes.enumOf(states, -1, Thread.State.NEW) == Thread.State.NEW;
		String refusal;
		try {
			refusal = "answered " + ctrmap.util.Bytes.enumOf(states, 99, null);
		} catch (IllegalArgumentException ex) {
			refusal = ex.getMessage();
		}
		boolean refused = refusal.contains("99") && refusal.contains("State");
		System.out.println(((listed && standIn && refused) ? "  ok: " : "  FAIL: ") + "control: enumOf answers a listed"
				+ " number with its member, an unlisted one with the stand-in it was given, and refuses one with none,"
				+ " naming the enum (" + refusal + ")");
		bad += listed && standIn && refused ? 0 : 1;
		boolean reads = BY_INDEX.matcher(stripComments("x = Mode.values()[raw];")).find()
				&& !BY_INDEX.matcher(stripComments("// Mode.values()[raw]\nx = Bytes.enumOf(Mode.values(), raw, null);")).find();
		System.out.println((reads ? "  ok: " : "  FAIL: ") + "control: an index into values() is seen, one in a comment"
				+ " and the helper's call are not");
		bad += reads ? 0 : 1;
		int files = 0;
		for (File f : DialogSeamTest.javaSources(new File(root, "ctrmap"))) {
			if (f.getPath().replace('\\', '/').contains("/ctrmap/tests/")) {
				continue;
			}
			files++;
			String src = stripComments(DialogSeamTest.read(f));
			java.util.regex.Matcher m = BY_INDEX.matcher(src);
			while (m.find()) {
				System.out.println("  FAIL: " + f.getName() + ":" + (src.substring(0, m.start()).split("\n", -1).length)
						+ " indexes values() - use ctrmap.util.Bytes.enumOf and say what an unlisted number reads as");
				bad++;
			}
		}
		System.out.println("enum-by-number rule: " + files + " program files read");
		return bad;
	}

	// ---------------------------------------------------- the command-line rule

	/**
	 * No program class starts a process through {@code Runtime.exec} (F245): ESPICA was started with
	 * {@code exec(path + " -i " + input ...)}, one string that exec splits on whitespace, so every
	 * path under a folder with a space in its name - a Windows user folder, most of them - reached
	 * the converter in pieces. A command is built as a list and started by {@code ProcessBuilder};
	 * exec's array form is the same list with nothing to show for it, so exec is refused whole.
	 * Read from the bytecode, and the converter's own command is asked to keep a spaced path whole.
	 */
	static int noCommandIsRunAsOneString(File classesRoot) throws Exception {
		List<String> execs = new ArrayList<>();
		boolean builder = false;
		for (ClassFileScanner.ClassFile cf : ClassFileScanner.application(classesRoot)) {
			for (ClassFileScanner.Ref r : cf.refs) {
				if (r.method && r.owner.equals("java/lang/Runtime") && r.name.equals("exec")) {
					execs.add(cf.topLevel() + " " + r.descriptor);
				}
				builder |= r.method && r.owner.equals("java/lang/ProcessBuilder")
						&& cf.topLevel().equals("ctrmap/humaninterface/ESPICAControl");
			}
		}
		int bad = 0;
		if (!builder) {
			System.out.println("  EXEC: control failed - the converter's ProcessBuilder call is not seen in"
					+ " ctrmap/humaninterface/ESPICAControl, so an empty list here would mean nothing");
			bad++;
		}
		for (String e : execs) {
			System.out.println("  EXEC: " + e + " starts a process through Runtime.exec - build the command as a"
					+ " list and start it with ProcessBuilder, so a path with a space stays one argument");
			bad++;
		}
		File spaced = new File("My Maps", "map.obj");
		java.util.List<String> argv = new ctrmap.humaninterface.ESPICAControl.ESPICAProcess(
				ctrmap.humaninterface.ESPICAControl.ESPICAFunctionMode.MODEL_CONVERT,
				spaced, new File("My Maps", "donor.bch"), null,
				new String[0]).command("C:\\Program Files\\ESPICA\\ESPICA.exe");
		if (!argv.contains(spaced.getAbsolutePath()) || !argv.get(0).equals("C:\\Program Files\\ESPICA\\ESPICA.exe")) {
			System.out.println("  EXEC: the converter's command does not keep a spaced path whole: " + argv);
			bad++;
		}
		//census H.65: the converter succeeds by its exit code and the file it was asked for, and its errors are read
		File written = File.createTempFile("espica", ".bch");
		written.deleteOnExit();
		File missing = new File(written.getParentFile(), written.getName() + ".not-there");
		String ok = ctrmap.humaninterface.ESPICAControl.failure(0, written);
		String exited = ctrmap.humaninterface.ESPICAControl.failure(1, written);
		String nothing = ctrmap.humaninterface.ESPICAControl.failure(0, missing);
		if (ok != null || exited == null || !exited.contains("code 1") || nothing == null || !nothing.contains("wrote no")) {
			System.out.println("  EXEC: the converter's run is not judged by its exit code and its output file: "
					+ ok + " / " + exited + " / " + nothing);
			bad++;
		}
		String control = stripComments(new String(java.nio.file.Files.readAllBytes(
				new File("src/ctrmap/humaninterface/ESPICAControl.java").toPath()), java.nio.charset.StandardCharsets.UTF_8));
		if (!control.contains(".redirectErrorStream(true)") || !control.contains("failure(process.waitFor(), proc.output)")) {
			System.out.println("  EXEC: the converter's errors are not read with its output, or its exit is not waited for");
			bad++;
		}
		//census M.92: and the reader over its output is closed when it is read
		if (!control.contains("try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream())))")) {
			System.out.println("  EXEC: the reader over the converter's output is not closed when read");
			bad++;
		}
		System.out.println("command-line rule: " + execs.size() + " Runtime.exec call(s); the converter's command is "
				+ argv.size() + " arguments, its spaced paths whole");
		return bad;
	}

	// ---------------------------------------------------- the measured-count rule

	/**
	 * Literals that EQUAL a profile count by coincidence, as {file, value, how many, why}. Argued
	 * one by one and held to a ceiling, as the askers are: a pixel size is not a record count, but
	 * the next 280 typed into a form has to be looked at by someone before it joins this list.
	 */
	private static final String[][] COINCIDENT_COUNTS = {
		{"GfEnvPicker.java", "280", "1", "the environment list's preferred width in pixels"},
		{"IdChooser.java", "280", "1", "the chooser list's preferred width in pixels"},
		{"TileEditForm.java", "280", "1", "a GroupLayout size in NetBeans' generated initComponents, not hand-edited"},
		{"ScriptEditor.java", "170", "1", "the blue channel of the pointer column's highlight colour"},
		{"ZoneLoadingPanel.java", "170", "2", "two GroupLayout sizes in NetBeans' generated initComponents, not hand-edited"},
	};

	/** Mirrors COINCIDENT_COUNTS.length. */
	private static final int COINCIDENT_COUNTS_CEILING = 5;

	/**
	 * No count a game profile answers is typed as a literal outside ctrmap.gamedef (F205).
	 *
	 * <p>WHY. The counts were each defined once somewhere and then re-typed beside their own
	 * name: 536 zones in the window's menus, the zone list's tooltips and two harvesters; 721
	 * species and 621 moves in both trainer editors; 279 trainer classes; 776 items; 622, 722 and
	 * 776 as the reference tables' fallbacks. Each is an Omega Ruby / Alpha Sapphire fact that X/Y
	 * answers differently, and a re-typed copy is the one a correction misses. The seam scan read
	 * only archive paths, so nothing refused any of them.
	 *
	 * <p>THE VALUES ARE ASKED, NOT LISTED: every registered profile's counts - and each maximum
	 * plus one, the entry count a table of them has - so a count a profile gains is guarded the
	 * day it is answered. DECIMAL LITERALS, comments stripped, strings kept: a message that says
	 * "536" has re-typed the count as surely as code has. Hex is not read, measured: the three hex
	 * literals equal to a count when this was written were a Pawn opcode (twice) and a PICA
	 * register, and no count in this tree was ever written in hex.
	 *
	 * @return the number of violations
	 */
	static int noProfileCountIsRetyped(File root) throws Exception {
		System.out.println("--- no count a game profile answers is re-typed outside it");
		java.util.TreeMap<Integer, String> counts = new java.util.TreeMap<>();
		for (ctrmap.gamedef.GameType g : ctrmap.gamedef.GameType.values()) {
			ctrmap.gamedef.GameProfile p;
			try {
				p = ctrmap.gamedef.GameProfile.of(g);
			} catch (IllegalArgumentException none) {
				continue;
			}
			answered(counts, p.retailZoneCount(), g + " retailZoneCount", false);
			answered(counts, p.areaTableIndex(), g + " areaTableIndex", false);
			answered(counts, p.trainerIdMax(), g + " trainerIdMax", true);
			answered(counts, p.itemIdMax(), g + " itemIdMax", true);
			answered(counts, p.speciesIdMax(), g + " speciesIdMax", true);
			answered(counts, p.moveIdMax(), g + " moveIdMax", true);
			answered(counts, p.trainerClassMax(), g + " trainerClassMax", true);
		}
		int fails = 0;
		boolean asked = counts.containsKey(536) && counts.containsKey(950) && counts.containsKey(622);
		System.out.println((asked ? "  ok: " : "  FAIL: ") + "control: the profiles were asked - " + counts.size()
				+ " counts, ORAS's 536 zones, 950 trainer entries and 622 move entries among them");
		fails += asked ? 0 : 1;
		List<String> seen = literalCounts(stripComments(
				"int a = 949; // 721\nString s = \"536 zones\";\nint h = 0x218; float f = 280f; /* 775 */"), counts);
		boolean reads = seen.equals(java.util.Arrays.asList("1:949", "2:536"));
		System.out.println((reads ? "  ok: " : "  FAIL: ") + "control: a count in code and one in a string are read; one"
				+ " in a comment, one in hex and one with a suffix are not: " + seen);
		fails += reads ? 0 : 1;
		Map<String, Integer> allowance = new LinkedHashMap<>();
		for (String[] c : COINCIDENT_COUNTS) {
			allowance.put(c[0] + ":" + c[1], Integer.parseInt(c[2]));
		}
		List<String> retyped = new ArrayList<>();
		int files = 0;
		for (File f : DialogSeamTest.javaSources(new File(root, "ctrmap"))) {
			String path = f.getPath().replace('\\', '/');
			if (path.contains("/ctrmap/tests/") || path.contains("/ctrmap/gamedef/")) {
				continue;
			}
			files++;
			for (String hit : literalCounts(stripComments(DialogSeamTest.read(f)), counts)) {
				int value = Integer.parseInt(hit.substring(hit.indexOf(':') + 1));
				String key = f.getName() + ":" + value;
				Integer left = allowance.get(key);
				if (left != null && left > 0) {
					allowance.put(key, left - 1);
				} else {
					retyped.add(f.getName() + ":" + hit.substring(0, hit.indexOf(':')) + " types " + value + ", which is "
							+ counts.get(value));
				}
			}
		}
		List<String> stale = new ArrayList<>();
		for (Map.Entry<String, Integer> e : allowance.entrySet()) {
			if (e.getValue() != 0) {
				stale.add(e.getKey() + " (" + e.getValue() + " fewer)");
			}
		}
		System.out.println((retyped.isEmpty() ? "  ok: " : "  FAIL: ") + "no profile count is typed in any of the " + files
				+ " application sources outside gamedef" + (retyped.isEmpty() ? "" : " - these are: " + retyped
				+ ". Ask the profile; if one is a coincidence, argue it in COINCIDENT_COUNTS"));
		System.out.println((stale.isEmpty() ? "  ok: " : "  FAIL: ") + "every argued coincidence is still there"
				+ (stale.isEmpty() ? "" : " - not: " + stale + ". Take it out of COINCIDENT_COUNTS"));
		boolean capped = COINCIDENT_COUNTS.length <= COINCIDENT_COUNTS_CEILING;
		System.out.println((capped ? "  ok: " : "  FAIL: ") + "the argued coincidences are " + COINCIDENT_COUNTS.length
				+ " against a ceiling of " + COINCIDENT_COUNTS_CEILING);
		boolean coincidencesTight = Ratchet.tight(COINCIDENT_COUNTS.length, COINCIDENT_COUNTS_CEILING);
		System.out.println((coincidencesTight ? "  ok: " : "  FAIL: ")
				+ Ratchet.said("argued-coincidence", COINCIDENT_COUNTS.length, COINCIDENT_COUNTS_CEILING));
		return fails + retyped.size() + stale.size() + (capped ? 0 : 1) + (coincidencesTight ? 0 : 1);
	}

	/** Records a profile's answer - and, for a maximum, the entry count one past it - when it gave one. */
	private static void answered(Map<Integer, String> counts, int value, String what, boolean plusOne) {
		if (value < 0) {
			return;
		}
		counts.putIfAbsent(value, what);
		if (plusOne) {
			counts.putIfAbsent(value + 1, what + " + 1, the entry count");
		}
	}

	/** "line:value" for each decimal literal in {@code code} equal to a count. */
	static List<String> literalCounts(String code, Map<Integer, String> counts) {
		List<String> out = new ArrayList<>();
		Pattern lit = Pattern.compile("(?<![\\w.$])\\d+(?![\\w.])");
		String[] lines = code.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			java.util.regex.Matcher m = lit.matcher(lines[i]);
			while (m.find()) {
				if (m.group().length() < 10 && counts.containsKey(Integer.parseInt(m.group()))) {
					out.add((i + 1) + ":" + m.group());
				}
			}
		}
		return out;
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

	/**
	 * The classes that may name a particular game without asking which one is loaded, as {internal
	 * name, why} (F207). A class handed a GameType that then compares it decides on the game as
	 * surely as one that fetched it: ZoneHeader read and wrote every game but X/Y with ORAS's flag
	 * bits. Each entry here is either a per-game table with no else - an unlisted game answers
	 * nothing - or a tool that opens one game's dump by design.
	 */
	private static final String[][] ALLOWED_NAMERS = {
		{"ctrmap/formats/zone/NpcMoveCodes",
			"per-game movement-code tables keyed by the game they were read from, each switch ending in an"
			+ " explicit default of NO_LABELS / NO_RAWS: an unlisted game answers no codes, never another"
			+ " game's - the shape ZoneHeader's flags lacked"},
		{"ctrmap/formats/zone/WarpTransitions",
			"per-game warp-transition label and raw tables, the same switch-with-no-else shape as"
			+ " NpcMoveCodes: extLabels/extRaws answer NO_LABELS / NO_RAWS for any game not listed"},
		{"ctrmap/tools/BuildingHarvester",
			"a developer tool that opens an Omega Ruby / Alpha Sapphire dump by design to build the"
			+ " shipped building catalogue; it names the game it opens, it does not branch on a loaded one"},
		{"ctrmap/tools/TerrainDonorHarvester",
			"a developer tool that opens an ORAS dump by design to find terrain donors; it names the game"
			+ " it opens, it does not branch on a loaded one"},
		{"ctrmap/tools/GroundMaterialAudit",
			"a developer tool that opens an ORAS dump by design to audit ground materials; it names the"
			+ " game it opens, it does not branch on a loaded one"},
	};

	/**
	 * Mirrors ALLOWED_NAMERS.length. TIGHTENED 6 -> 5 on 2026-10-08, measured by this suite:
	 * TerrainHarvester went, its two catalogues read by nothing (F119).
	 */
	private static final int ALLOWED_NAMERS_CEILING = 5;

	private static String[] namerFor(String internalName) {
		for (String[] n : ALLOWED_NAMERS) {
			if (n[0].equals(internalName)) {
				return n;
			}
		}
		return null;
	}

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
		Set<String> namersSeen = new LinkedHashSet<>();
		for (Map.Entry<String, Set<String>> e : held.entrySet()) {
			List<String> gates = pick(e.getValue(), "GATE:");
			List<String> asks = pick(e.getValue(), "ASK:");
			List<String> names = pick(e.getValue(), "NAME:");
			boolean decides = !asks.isEmpty() && !names.isEmpty();
			//NAMING A GAME IS THE BRANCH, wherever the game came from (F207): the pair rule saw 1 of
			//the 7 classes that branch on GameType, because 6 were handed theirs, and one of the 6 -
			//ZoneHeader - read and wrote every other game's flags with ORAS's bits
			if (gates.isEmpty() && !decides && !names.isEmpty() && allowedFor(e.getKey()) == null) {
				if (namerFor(e.getKey()) != null) {
					namersSeen.add(e.getKey());
				} else {
					asked.add(e.getKey() + " names " + names + " - a class handed a GameType hands it to GameProfile;"
							+ " a per-game table there, or an argued entry in ALLOWED_NAMERS");
				}
				continue;
			}
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
		boolean askersTight = Ratchet.tight(ALLOWED_ASKERS.length, ALLOWED_ASKERS_CEILING);
		if (!askersTight) {
			System.out.println("  ASKS: " + Ratchet.said("allowed-asker", ALLOWED_ASKERS.length, ALLOWED_ASKERS_CEILING));
		}
		//the namers' table is held the same way, and an entry whose class names no game any more goes
		List<String> staleNamers = new ArrayList<>();
		boolean namersArgued = true;
		for (String[] n : ALLOWED_NAMERS) {
			namersArgued &= n[1] != null && n[1].trim().length() > 40;
			if (!namersSeen.contains(n[0])) {
				staleNamers.add(n[0]);
			}
		}
		boolean namersTight = Ratchet.tight(ALLOWED_NAMERS.length, ALLOWED_NAMERS_CEILING);
		if (!namersArgued || !namersTight || !staleNamers.isEmpty()) {
			System.out.println("  ASKS: the allowed namers - " + (namersArgued ? "" : "an entry gives no reason; ")
					+ (namersTight ? "" : Ratchet.said("allowed-namer", ALLOWED_NAMERS.length, ALLOWED_NAMERS_CEILING) + "; ")
					+ (staleNamers.isEmpty() ? "" : "these name no game any more, so their entries go: " + staleNamers));
		}
		System.out.println("game-identity rule: " + app.size() + " application class file(s) read, "
				+ asked.size() + " asking or naming which game is loaded, " + allowedSeen + " of "
				+ ALLOWED_ASKERS.length + " allowed exception(s) still present, " + namersSeen.size() + " of "
				+ ALLOWED_NAMERS.length + " argued namer(s)");
		return asked.size() + (argued ? 0 : 1) + (askersTight ? 0 : 1)
				+ (namersArgued && namersTight && staleNamers.isEmpty() ? 0 : 1);
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
	 * reason {@link #ALLOWED_ASKERS} gives. EMPTY since F214: the last two
	 * were the prop and the NPC implementing {@code MapObject}, the interface
	 * the viewport drags a placed thing through - a CLASS reference with no
	 * member behind it, the shape a rule counting member edges alone would
	 * never see. The interface describes a placed thing, which is format-layer
	 * knowledge, and lives in {@code ctrmap.formats} now.
	 */
	private static final String[][] ALLOWED_ABOVE = {};

	/** Mirrors ALLOWED_ABOVE.length. Raising it is the decision this makes visible. */
	private static final int ALLOWED_ABOVE_CEILING = 0;

	/**
	 * The class the format layer used to be allowed only PART of.
	 *
	 * <p>{@code ctrmap.Utils} held five unrelated groups: byte helpers the
	 * format layer reads with, two dialog wrappers, Swing form helpers, the 3D
	 * picking maths, and a directory helper. This rule therefore had to name
	 * the members that were allowed - a dialog reached through a helper's name
	 * is still a dialog - and count the edges to them. The split made that
	 * structural: the pure helpers are {@link ctrmap.util.Bytes}, which needs
	 * no game, no window and no display, and the "keep the changes?" question
	 * is on the dialog seam, which this rule already forbids. What is left to
	 * assert is that the class did not come back.
	 */
	private static final String GONE_UTILS = "ctrmap/Utils";

	private static final String FORMATS = "ctrmap/formats/";
	private static final String HUMANINTERFACE = "ctrmap/humaninterface/";
	private static final String MAINFRAME = "ctrmap/CtrmapMainframe";
	private static final String TOOLS = "ctrmap/humaninterface/tools/";
	private static final String UI = "ctrmap/Ui";
	private static final String UTILS = "ctrmap/Utils";
	private static final String KEEP = "ctrmap/Utils$Keep";
	private static final String ASK_TO_KEEP = "askToKeep";

	/**
	 * The workspace facade names the main window NOWHERE.
	 *
	 * <p>{@link ctrmap.Workspace} is where this program keeps the open game.
	 * It knew the window in four places: five dialog parents (the seam holds
	 * the window now), an {@code unloadEditors()} in the middle of a clean, and
	 * the call that handed the window each session as it was installed. Every
	 * one is the wrong direction - the state a program keeps should not know
	 * the screen it is drawn on - and the cost was measurable, not theoretical:
	 * WorkspaceRepointTest records that the path where the user answers both of
	 * its questions "cannot run without a window at all", because the clean it
	 * reaches unloaded editor panels. The facade now answers with what it
	 * opened and the caller tells the window, so that path runs headless.
	 *
	 * <p>Zero, with no allowed exceptions: a facade that needs to say something
	 * to the screen has a caller who can say it.
	 */
	static int theFacadeDoesNotKnowTheWindow(File classesRoot) throws Exception {
		if (!new File(classesRoot, WORKSPACE + ".class").isFile()) {
			System.out.println("  FAIL: no compiled " + WORKSPACE + ".class under "
					+ classesRoot.getAbsolutePath() + " - run build.ps1 first");
			return 1;
		}
		List<String> found = new ArrayList<>();
		for (ClassFileScanner.ClassFile cf : ClassFileScanner.application(classesRoot)) {
			if (!cf.topLevel().equals(WORKSPACE)) {
				continue;
			}
			for (ClassFileScanner.Ref r : cf.refs) {
				if (r.owner.equals(MAINFRAME)) {
					found.add(cf.name + (r.method ? " calls " : " reads ") + r.owner + "." + r.name);
				}
			}
			for (String c : cf.classes) {
				if (c.equals(MAINFRAME)) {
					found.add(cf.name + " names " + c);
				}
			}
		}
		for (String f : found) {
			System.out.println("  FACADE: " + f
					+ " - the workspace facade must not know the window; hand its result to a caller that does");
		}
		System.out.println("facade rule: " + found.size() + " reference(s) from " + WORKSPACE
				+ " to " + MAINFRAME);
		return found.size();
	}

	/**
	 * No editing tool names the main window.
	 *
	 * <p>Every class in {@code ctrmap.humaninterface.tools} opened with
	 * {@code import static ctrmap.CtrmapMainframe.*} and read the window's
	 * statics for the map view, the side panel, the JFrame and the form it
	 * drives - and {@code AbstractTool}'s constructor called
	 * {@code onToolInit}, which put the form in the window's split. So a tool
	 * could not be BUILT until nine forms, three split panes, three master
	 * panels and a scroll pane existed, and not at all without a display.
	 * That is why the bench that stands in for the window exists, and why
	 * seven of the eight classes it serves had been at zero coverage: not
	 * because they are hard to assert, but because nothing could make one.
	 *
	 * <p>A tool is handed {@link ctrmap.humaninterface.tools.ToolHost} and its
	 * own form now. Zero, with no allowed exceptions: a tool that needs
	 * something from the editor asks the host, and the host is what a suite
	 * replaces.
	 */
	static int theToolsDoNotKnowTheWindow(File classesRoot) throws Exception {
		if (!new File(classesRoot, MAINFRAME + ".class").isFile()) {
			System.out.println("  FAIL: no compiled " + MAINFRAME + ".class under "
					+ classesRoot.getAbsolutePath() + " - run build.ps1 first");
			return 1;
		}
		List<String> found = new ArrayList<>();
		int tools = 0;
		for (ClassFileScanner.ClassFile cf : ClassFileScanner.application(classesRoot)) {
			if (!cf.name.startsWith(TOOLS)) {
				continue;
			}
			tools++;
			for (ClassFileScanner.Ref r : cf.refs) {
				if (r.owner.equals(MAINFRAME)) {
					found.add(cf.name + (r.method ? " calls " : " reads ") + r.owner + "." + r.name);
				}
			}
			for (String c : cf.classes) {
				if (c.equals(MAINFRAME)) {
					found.add(cf.name + " names " + c);
				}
			}
		}
		for (String f : found) {
			System.out.println("  TOOL: " + f
					+ " - a tool is handed the editor it works in; ask the ToolHost, or take it in the constructor");
		}
		System.out.println("tool rule: " + tools + " tool class file(s) read, " + found.size()
				+ " reference(s) to " + MAINFRAME);
		return found.size();
	}

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
		int formatClasses = 0;
		for (ClassFileScanner.ClassFile cf : app) {
			if (!cf.name.startsWith(FORMATS)) {
				continue;
			}
			formatClasses++;
			Set<String> memberOwners = new LinkedHashSet<>();
			for (ClassFileScanner.Ref r : cf.refs) {
				if (isAbove(r.owner)) {
					memberOwners.add(r.owner);
					above.add(cf.topLevel() + (r.method ? " calls " : " reads ") + r.owner + "." + r.name);
				}
			}
			for (String c : cf.classes) {
				if (!isAbove(c) || memberOwners.contains(c)) {
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
		boolean aboveTight = Ratchet.tight(ALLOWED_ABOVE.length, ALLOWED_ABOVE_CEILING);
		if (!aboveTight) {
			System.out.println("  ABOVE: " + Ratchet.said("allowed-upward-call", ALLOWED_ABOVE.length,
					ALLOWED_ABOVE_CEILING));
		}

		//the class this rule used to defend a part of does not exist any more
		boolean utilsOk = !new File(classesRoot, GONE_UTILS + ".class").isFile();
		if (!utilsOk) {
			System.out.println("  ABOVE: " + GONE_UTILS + " is back. It was five unrelated groups in one class,"
					+ " and the format layer could reach a dialog through it; the split put the pure helpers in"
					+ " ctrmap.util.Bytes and the questions on the dialog seam");
		}

		System.out.println("layering rule: " + formatClasses + " format class file(s) read, " + above.size()
				+ " reaching above the format layer, " + allowedSeen.size() + " of " + ALLOWED_ABOVE.length
				+ " allowed exception(s) still present; ctrmap/Utils gone: " + utilsOk);
		return above.size() + (argued ? 0 : 1) + (utilsOk ? 0 : 1) + (aboveTight ? 0 : 1);
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
	 * Sweeps every other file a commit could ship - READMEs, build scripts, data tables, hooks, IDE
	 * files - for home paths. The Java scan alone would have missed the documentation, which is where
	 * a path is most likely to be pasted.
	 *
	 * <p>WHAT IT READS IS GIT'S LISTING, AND WHAT IS TEXT IS DECIDED BY THE BYTES (F141). It read a list
	 * of suffixes and skipped folders by name: {@code .py} was not on the list, a git hook has no suffix,
	 * and {@code nbproject} was skipped - which is where eleven lines naming the upstream author's home
	 * sat, tracked, while the javadoc above promised ANY file. The project's own Java sources under
	 * {@code javaRoot} are the Java scan's, read there with their comments stripped.
	 */
	static int scanText(File repo, File javaRoot, List<String> out) throws Exception {
		List<String> listed = shippable(repo);
		if (listed == null || listed.isEmpty()) {
			out.add("the home-path scan could not list the files a commit would ship (git ls-files in " + repo
					+ ") - a scan that did not run is not a clean tree");
			return 0;
		}
		String javaPrefix = javaRoot.getCanonicalPath() + File.separator;
		int n = 0;
		for (String rel : listed) {
			File f = new File(repo, rel);
			if (!f.isFile() || rel.endsWith(".java") && f.getCanonicalPath().startsWith(javaPrefix)) {
				continue;
			}
			byte[] bytes = Files.readAllBytes(f.toPath());
			boolean binary = false;
			for (byte b : bytes) {
				binary |= b == 0;
			}
			if (binary) {
				continue;
			}
			n++;
			String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
			for (int i = 0; i < lines.length; i++) {
				if (lines[i].contains("SEAM-OK")) {
					continue;
				}
				if (HOME_PATH.matcher(lines[i]).find()) {
					out.add(rel + ":" + (i + 1) + " home directory in a shipped file: " + lines[i].trim());
				}
			}
		}
		return n;
	}

	/**
	 * Every file a commit could ship, as git lists it: the tracked ones, and the untracked ones it does
	 * not ignore - what {@code git add -A} would take. Null when git cannot be run or refuses, which is
	 * not an empty repository: a listing that could not be taken is a scan that did not run.
	 */
	static List<String> shippable(File repo) {
		try {
			ProcessBuilder pb = new ProcessBuilder("git", "-C", repo.getAbsolutePath(), "ls-files", "-z", "--cached",
					"--others", "--exclude-standard");
			pb.redirectError(ProcessBuilder.Redirect.INHERIT);
			Process p = pb.start();
			java.io.ByteArrayOutputStream said = new java.io.ByteArrayOutputStream();
			try (java.io.InputStream in = p.getInputStream()) {
				byte[] buf = new byte[65536];
				for (int k = in.read(buf); k >= 0; k = in.read(buf)) {
					said.write(buf, 0, k);
				}
			}
			if (p.waitFor() != 0) {
				return null;
			}
			Set<String> files = new LinkedHashSet<>();
			for (String s : new String(said.toByteArray(), StandardCharsets.UTF_8).split("\0")) {
				if (!s.isEmpty()) {
					files.add(s);
				}
			}
			return new ArrayList<>(files);
		} catch (java.io.IOException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
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
			String original = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			String[] raw = original.split("\n", -1);
			String[] lines = stripComments(original).split("\n", -1);
			for (int i = 0; i < lines.length; i++) {
				String line = lines[i];
				if (isSelf) {
					continue;
				}
				if (STREAM_HIJACK.matcher(line).find()) {
					if (allowedRedirect(f.getName()) != null) {
						redirectsSeen++;
					} else {
						out.add(f.getName() + ":" + (i + 1) + " redirects System.out/err: " + line.trim());
					}
				}
				//THE MARKER IS READ WHERE IT IS WRITTEN: on the line as written, comment and all. It
				//was looked for after the comments had been stripped, so in a .java file it could
				//never be found, and the escape the class comment offers did not exist. It covers
				//the path rules, which is what it was offered for, and not the redirect above.
				if (i < raw.length && raw[i].contains("SEAM-OK")) {
					continue;
				}
				if (!isTest && !isGamedef && GARC_PATH.matcher(line).find()) {
					out.add(f.getName() + ":" + (i + 1) + " GARC path literal outside gamedef: " + line.trim());
				}
				if (isTest && GARC_PATH.matcher(line).find()) {
					suitePathLines++;
				}
				if (!isTest && DUMP_PATH.matcher(line).find()) {
					out.add(f.getName() + ":" + (i + 1) + " hardcoded dump location: " + line.trim());
				}
				//no exemption: a test may name the dump folder, never its owner
				if (HOME_PATH.matcher(line).find()) {
					out.add(f.getName() + ":" + (i + 1) + " home directory in a shipped file: " + line.trim());
				}
			}
		}
		return n;
	}

	/**
	 * The files allowed to redirect a standard stream, as {file name, why}. The rule exists because
	 * the script editor swapped both streams into its output box with no finally, and the first
	 * assembler exception left every later diagnostic going nowhere. A tee that keeps writing to
	 * the stream it replaced is not that, and one is allowed - by name, with its reason, under a
	 * ceiling, and only while it is still there to be allowed.
	 */
	private static final String[][] ALLOWED_REDIRECTS = {
		{"ErrorSink.java", "installs a tee on System.err once at startup, before any window: everything still"
			+ " reaches the stream it went to, and a copy reaches the log file the user can send (F219)."}
	};

	/** Mirrors ALLOWED_REDIRECTS.length. Raising it is the decision this makes visible. */
	private static final int ALLOWED_REDIRECTS_CEILING = 1;

	/** How many allowed redirects the scan met - an allowance nobody uses any more is stale. */
	private static int redirectsSeen;

	/** Lines in the suites that hold an archive-path literal, counted by scan (F154). */
	private static int suitePathLines;

	/**
	 * The suites' archive-path literals, measured by this suite on 2026-10-08 (F154; the audit counted
	 * 138 lines in 64 files of a smaller tree). Each is a suite that can only be pointed at an ORAS
	 * dump. Lower it when one goes through GameProfile.archivePath; never raise it. TIGHTENED 168 -> 166
	 * on 2026-10-08, measured by this suite: F250's scratch-pack section in GarcSniffTest had spelled the
	 * dress-up archive's path in three messages (169, past this ceiling), and they name it now.
	 */
	private static final int SUITE_PATH_CEILING = 159;   //2026-10-15: PaintFormGuardsTest's six "a/0/3/9" became holdsRegions

	private static String allowedRedirect(String fileName) {
		for (String[] a : ALLOWED_REDIRECTS) {
			if (a[0].equals(fileName) && a[1] != null && !a[1].trim().isEmpty()) {
				return a[1];
			}
		}
		return null;
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
