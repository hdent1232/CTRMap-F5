package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What is left reaching into the main window, named one field at a time, so it
 * can only shrink.
 *
 * <p>WHY THIS SUITE EXISTS. {@link CtrmapMainframe} is the hub every editor
 * panel used to go through: 45 classes read its 22 public statics across 108
 * field references, and one of them - the held tool - was WRITTEN from outside
 * as well. The decoupling steps took the ones with an owner: the loaded zone,
 * the held tool, the dialog parent, the redraw, and everything the ten editing
 * tools used. What remains is written down here rather than left to a number.
 *
 * <p>THIS PARAGRAPH USED TO SAY the rest was a genuine tangle because "the map
 * view reads the NPC form and the NPC form reads the map view, so no order of
 * constructors can hand them to each other". That was measured once and then
 * quoted for weeks. It is not true: {@code TileMapPanel} reads
 * {@code mNPCEditForm} at exactly ONE line - the entity clear in
 * {@code loadTileMap} - and that line is character-for-character what the NPC
 * form's own {@code ZoneEditors.ZoneView.clear()} already does. The cycle is one
 * line deep on that side and removable. Some of what is left IS a real cycle
 * needing an ownership decision - the tile cursor, wanted by both the map view
 * and the tile inspector, has no construction order that satisfies both - but
 * this suite's headline reason must name a cycle that still exists. A ratchet
 * whose stated justification has expired outlives its own argument, and looks
 * authoritative while doing it.
 *
 * <p>So this suite is a ratchet, not a target. Every remaining field is listed
 * with its readers and what they use it for, asserted with EQUALITY: a reader
 * that goes is a line deleted here, and a reader that appears is a class
 * reaching for a global instead of being handed what it needs.
 *
 * <p>Read from {@code build/classes}, because thirty-six files import this
 * window's statics by name and no source regex can see those reads.
 *
 * Usage: java ctrmap.tests.MainframeEdgesTest [classes-root] [src-root]
 */
public class MainframeEdgesTest {

	private static final String WINDOW = "ctrmap/CtrmapMainframe";

	/**
	 * {field, readers, what they use it for}, measured over the compiled program, the window itself
	 * excluded, folded to top-level classes. EMPTY, and that is the point of keeping it as a table
	 * rather than a number: a name appearing here is a class that has started reaching into the
	 * window for something instead of being handed it, and the message says which class and which
	 * field.
	 */
	private static final String[][] REACHES = {};

	/**
	 * {method, callers, why} - the window's static METHODS other classes call (F143), measured the
	 * same way and asserted with the same equality. The field table above was the whole rule, so
	 * "nothing reaches in" was true of fields and silent about these seven calls from five classes:
	 * a dialog that has changed the scene asks the window to redraw its fog, the workspace settings
	 * and the setup wizard tell it a workspace opened, the facility editor asks it to show the Game
	 * Data tab. Each is a call UP from a panel the window built; a new one is a class that started
	 * asking the window to do something instead of being handed the thing to ask.
	 *
	 * <p>EMPTY SINCE F007: the six calls from six classes listed here were each handed in instead - every
	 * one of those panels is built by the window, so it says what to ask as it builds them (a Runnable
	 * for a redraw or an unload, a title-and-editor sink for a tab, the workspace-opened answer as a
	 * function), and none of the six names the window any more. A row here now is a regression.
	 */
	private static final String[][] CALLS = {};

	/**
	 * How many DISTINCT (class file, field) pairs reach in from outside. ZERO.
	 *
	 * <p>THIS IS NOT A COUNT OF CALL SITES, whatever this javadoc said for three
	 * commits. {@link ClassFileScanner.ClassFile#refs} is the constant pool, and
	 * javac emits one Fieldref per (class file, field) however many times the
	 * field is read - so an anonymous inner class is its own reference and a
	 * Java 8 lambda is not, and a change removed a reference only when it
	 * removed the LAST read of that field from that one class file.
	 *
	 * <p>Measured 2026-09-08 at 108 before the decoupling steps, then 64, 55,
	 * 49, 47, 45, 44, 41, 39, 38, 36, 34, 27, 24, 21, 20, 16, 15, 14, 2 and now
	 * none. What each step took is in its commit; what matters here is that the
	 * number is a floor and not a target, and that the table above is the thing
	 * to read when it stops being zero.
	 *
	 * <p>WHAT THIS DOES NOT MEAN. The window still HOLDS these panels. What
	 * changed is that nothing else asks it for them: every editor is handed what
	 * it needs, so two of anything can exist at once and a suite can hand a
	 * different one. Three of the handings are two-phase - the matrix grid, the
	 * map view's editors, and the tool row's Set-tool callback - because those
	 * are real construction cycles, and each says so where it is written.
	 */
	private static final int REFERENCES = 0;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File classes = new File(args.length > 0 ? args[0] : "build/classes");
		if (!new File(classes, WINDOW + ".class").isFile()) {
			System.out.println("FAIL: no compiled " + WINDOW + ".class under " + classes.getAbsolutePath()
					+ " - run build.ps1 first; this suite reads class files, not source");
			System.exit(1);
		}

		everyReachIntoTheWindowIsNamed(classes);
		everyCallIntoTheWindowIsNamed(classes);
		nothingOutsideTheWindowWritesItsStatics(classes);
		noFileImportsTheWindowsStatics(new File(args.length > 1 ? args[1] : "src"));
		theWindowKeepsNoStateInStatics();
		theWindowExposesNothing();
		theSuitesWayInRefusesAStaleName();
		nothingIsHandedAStaticTheWindowHasNotBuiltYet(new File(args.length > 1 ? args[1] : "src"));
		everyZoneHandedToTheTabIsGivenASlot(new File(args.length > 1 ? args[1] : "src"));
		theRuleSeesTheShapesItUsedToMiss();
		theZonesHistoryWatchesItsScript(classes);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	// ------------------------------------------------------------ 1. who reaches in
	static void everyReachIntoTheWindowIsNamed(File classes) throws Exception {
		System.out.println("--- what still reaches into the window, field by field");
		List<ClassFileScanner.ClassFile> app = ClassFileScanner.application(classes);
		Map<String, Set<String>> reads = new TreeMap<>();
		int references = 0;
		for (ClassFileScanner.ClassFile cf : app) {
			if (cf.topLevel().equals(WINDOW)) {
				continue;
			}
			for (ClassFileScanner.Ref r : cf.refs) {
				if (r.owner.equals(WINDOW) && !r.method) {
					references++;
					Set<String> who = reads.get(r.name);
					if (who == null) {
						who = new TreeSet<>();
						reads.put(r.name, who);
					}
					who.add(simple(cf.topLevel()));
				}
			}
		}

		Map<String, Set<String>> recorded = new TreeMap<>();
		for (String[] row : REACHES) {
			recorded.put(row[0], new TreeSet<>(Arrays.asList(row[1].split(","))));
		}

		for (String field : new TreeSet<>(union(reads.keySet(), recorded.keySet()))) {
			Set<String> now = reads.containsKey(field) ? reads.get(field) : new TreeSet<String>();
			Set<String> was = recorded.containsKey(field) ? recorded.get(field) : new TreeSet<String>();
			if (now.equals(was)) {
				continue;
			}
			Set<String> gained = new TreeSet<>(now);
			gained.removeAll(was);
			Set<String> gone = new TreeSet<>(was);
			gone.removeAll(now);
			if (!gained.isEmpty()) {
				check(false, field + " gained " + gained
						+ " - a class reaching into the window for it instead of being handed it");
			}
			if (!gone.isEmpty()) {
				check(false, field + " no longer reaches: " + gone
						+ " - good, and this suite's list is the record, so delete them from it");
			}
		}
		if (reads.equals(recorded)) {
			check(true, "the " + recorded.size() + " fields still reached into are exactly the ones recorded, "
					+ "read by exactly the recorded classes");
		}
		System.out.println("  field references from outside: " + references + " (recorded " + REFERENCES + ")");
		check(references == REFERENCES,
				"and the number of (class file, field) pairs is the recorded one - it only falls ("
						+ references + ")");
	}

	/** {@link #CALLS}: who calls which of the window's static methods, asserted with equality. */
	static void everyCallIntoTheWindowIsNamed(File classes) throws Exception {
		System.out.println("--- what still calls into the window, method by method (F143)");
		Map<String, Set<String>> calls = new TreeMap<>();
		int own = 0;
		for (ClassFileScanner.ClassFile cf : ClassFileScanner.application(classes)) {
			for (ClassFileScanner.Ref r : cf.refs) {
				if (!r.owner.equals(WINDOW) || !r.method) {
					continue;
				}
				if (cf.topLevel().equals(WINDOW)) {
					own++;
					continue;
				}
				Set<String> who = calls.get(r.name);
				if (who == null) {
					who = new TreeSet<>();
					calls.put(r.name, who);
				}
				who.add(simple(cf.topLevel()));
			}
		}
		//a reader that cannot see method refs reports none, so it must see the window's own first
		check(own > 0, "control: the window's calls to its own methods are seen (" + own + ")");
		Map<String, Set<String>> recorded = new TreeMap<>();
		for (String[] row : CALLS) {
			recorded.put(row[0], new TreeSet<>(Arrays.asList(row[1].split(","))));
		}
		check(calls.equals(recorded), "the window's static methods other classes call are exactly the "
				+ recorded.size() + " recorded, by exactly the recorded callers"
				+ (calls.equals(recorded) ? "" : " - measured " + calls + ", recorded " + recorded
				+ ". A new caller is a class asking the window instead of being handed what to ask;"
				+ " one gone is a line to delete here"));
	}

	/**
	 * No file imports the window's statics (F008). Nineteen production files carried
	 * {@code import static ctrmap.CtrmapMainframe.*} and sixteen used nothing through it: every
	 * window global one bare identifier away, so a reach could come back with no visible change
	 * at the top of the file. Suites included - a suite qualifies what it touches.
	 */
	static void noFileImportsTheWindowsStatics(File srcRoot) throws Exception {
		System.out.println("--- no file imports the window's statics (F008)");
		java.util.regex.Pattern imp = java.util.regex.Pattern.compile(
				"(?m)^\\s*import\\s+static\\s+ctrmap\\.CtrmapMainframe\\s*\\.\\s*[\\w*]+\\s*;");
		check(imp.matcher("import static ctrmap.CtrmapMainframe.*;").find()
				&& imp.matcher("  import static ctrmap.CtrmapMainframe.mTileMapPanel;").find()
				&& !imp.matcher("import ctrmap.CtrmapMainframe;").find(),
				"control: the rule matches the wildcard and the by-name import, and not a plain one");
		List<File> files = DialogSeamTest.javaSources(new File(srcRoot, "ctrmap"));
		List<String> importers = new ArrayList<>();
		for (File f : files) {
			String text = SourceSeamTest.stripComments(new String(java.nio.file.Files.readAllBytes(f.toPath()),
					java.nio.charset.StandardCharsets.UTF_8));
			if (imp.matcher(text).find()) {
				importers.add(f.getName());
			}
		}
		check(files.size() >= 400, "the sources were read (" + files.size() + " under " + srcRoot + ")");
		check(importers.isEmpty(), "no file imports the window's statics"
				+ (importers.isEmpty() ? "" : " - these do: " + importers + ". Qualify what is used"
				+ " (CtrmapMainframe.x), or better, be handed it"));
	}

	// ------------------------------------------------------- 2. nobody writes them
	static void nothingOutsideTheWindowWritesItsStatics(File classes) throws Exception {
		System.out.println("--- and nothing outside the window assigns one");
		//A putstatic is a write; ClassFileScanner reports field references without
		//saying which way, so this asks the question the other way round: the only
		//field anything outside ever assigned was the held tool, and it is gone.
		//What is left is proved by the compiler - every remaining field is read
		//through a getstatic in a class that never names it on the left of an "=".
		List<String> writers = new ArrayList<>();
		for (ClassFileScanner.ClassFile cf : ClassFileScanner.application(classes)) {
			if (cf.topLevel().equals(WINDOW)) {
				continue;
			}
			for (ClassFileScanner.Ref r : cf.refs) {
				if (r.owner.equals(WINDOW) && !r.method && r.name.equals("tool")) {
					writers.add(cf.name + " names the held tool, which no longer exists as a field");
				}
			}
		}
		check(writers.isEmpty(), "the one static anything outside the window used to WRITE is gone " + writers);
	}

	// ------------------------------------------- 4. built before it is handed
	/**
	 * Nothing the window builds is handed one of the window's own statics
	 * before that static has been assigned.
	 *
	 * <p>WHY THIS RULE EXISTS. It is the defect the decoupling steps
	 * themselves introduced, twice. Handing a collaborator in instead of
	 * letting it reach for a global is the whole point of those steps, but
	 * the collaborator has to EXIST when it is handed over, and the window
	 * builds thirty-one of its statics in one method. The two new owners were
	 * assigned where their lists read best - after the editors they name - and
	 * the Zone tab and the map painter, built sixty lines earlier, were handed
	 * null. Nothing failed at startup. Every save, every zone switch and every
	 * paint apply would have thrown, and no suite could see it, because every
	 * suite builds those panels itself and hands them real ones.
	 *
	 * <p>So the rule is checked over the window's SOURCE, in the order it is
	 * written: for every {@code new X(...)} in {@code createAndShowGUI}, an
	 * argument that names a static of this class must already have been
	 * assigned above it - by ANY assignment, not only a {@code new}: building
	 * the list into a local and then copying it into the field one line too
	 * late is the same null with more steps. Arguments are read across
	 * continuation lines, and a
	 * call whose arguments contain a lambda or an anonymous class is skipped
	 * on purpose: those bodies read their statics when they are CALLED, not
	 * when they are made, which is exactly why both lists could be built where
	 * they were and still name editors that do not exist yet.
	 */
	static void nothingIsHandedAStaticTheWindowHasNotBuiltYet(File srcRoot) throws Exception {
		System.out.println("--- and nothing is handed a static the window has not built yet");
		File f = new File(srcRoot, WINDOW + ".java");
		if (!f.isFile()) {
			check(false, "no " + f.getPath() + " to read - this rule is checked over source, not class files");
			return;
		}
		List<String> lines = new ArrayList<>();
		java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
				new java.io.FileInputStream(f), "UTF-8"));
		for (String line = r.readLine(); line != null; line = r.readLine()) {
			lines.add(line);
		}
		r.close();

		//A RULE THAT READS NO FIELDS PASSES: it read `static` declarations, and since F007 the window's
		//panels are its instance fields - so it is asked here that it still reads them
		Set<String> fields = fieldsOf(lines);
		check(fields.contains("mTileMapPanel") && fields.contains("mZonePnl") && fields.contains("frame"),
				"control: the window's own fields are what this rule reads (" + fields.size() + ")");
		List<String> handedEarly = handedTooEarly(lines);
		check(handedEarly.isEmpty(), "every field handed to something the window builds is already built "
				+ handedEarly);
	}

	/** The fields a class's source declares, instance or static: a member declaration carries an access modifier. */
	static Set<String> fieldsOf(List<String> lines) {
		java.util.regex.Pattern decl = java.util.regex.Pattern.compile(
				"^\\s*(?:public|private|protected)\\s+(?:static\\s+)?(?:final\\s+)?[\\w.<>\\[\\]]+\\s+(\\w+)\\s*[;=]");
		Set<String> fields = new TreeSet<>();
		for (String line : lines) {
			java.util.regex.Matcher m = decl.matcher(line);
			if (m.find()) {
				fields.add(m.group(1));
			}
		}
		return fields;
	}

	/**
	 * The rule itself, over any source: which lines hand a static that the
	 * method they sit in has not assigned yet.
	 *
	 * <p>Separated from the file reading ON PURPOSE, because a rule that can
	 * only be run against the one file it polices cannot be shown to work. The
	 * section below hands it four small sources whose answers are known.
	 */
	static List<String> handedTooEarly(List<String> lines) {
		Set<String> statics = fieldsOf(lines);

		int start = -1;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).matches("\\s*private (?:static )?void createAndShowGUI\\(\\) \\{\\s*")) {
				start = i;
				break;
			}
		}
		if (start < 0) {
			return java.util.Collections.singletonList(
					"no createAndShowGUI() - this rule reads that method by name");
		}
		int end = lines.size() - 1;
		int depth = 0;
		for (int i = start; i < lines.size(); i++) {
			depth += count(lines.get(i), '{') - count(lines.get(i), '}');
			if (i > start && depth == 0) {
				end = i;
				break;
			}
		}

		Map<String, Integer> assignedAt = new TreeMap<>();
		java.util.regex.Pattern assign = java.util.regex.Pattern.compile("(?:^|[^.=!<>+\\-*/&|^\\w])(\\w+)\\s*=[^=]");
		for (int i = start; i <= end; i++) {
			java.util.regex.Matcher m = assign.matcher(lines.get(i));
			while (m.find()) {
				if (statics.contains(m.group(1)) && !assignedAt.containsKey(m.group(1))) {
					assignedAt.put(m.group(1), i);
				}
			}
		}

		List<String> handedEarly = new ArrayList<>();
		//BOTH shapes of handing something over: `new X(a, b)` and `x.setY(a)`. The
		//first version of this rule read only constructors, and a static passed to
		//a setter after construction went straight past it - the same null, one
		//line later.
		java.util.regex.Pattern made = java.util.regex.Pattern.compile("(?:new\\s+[\\w.]+|\\.\\w+)\\s*\\(");
		java.util.regex.Pattern word = java.util.regex.Pattern.compile("\\b(\\w+)\\b");
		//How deep inside a lambda body or an anonymous class we are. Those bodies
		//run when they are CALLED, not here, so a static one of them names may
		//legitimately be assigned further down - that is exactly how the two editor
		//lists can be built before the editors they name. Anything at the method's
		//own level is evaluated NOW and is the rule's business.
		java.util.ArrayDeque<Integer> deferred = new java.util.ArrayDeque<>();
		int brace = 0;
		for (int i = start; i <= end; i++) {
			String line = lines.get(i);
			if (deferred.isEmpty()) {
				java.util.regex.Matcher m = made.matcher(line);
				while (m.find()) {
					String args = arguments(lines, i, m.end());
					if (args == null) {
						continue;
					}
					//SEGMENT BY SEGMENT, not all-or-nothing. Skipping a lambda is
					//right; skipping its plain siblings because it is there is not,
					//and new Foo(mBar, () -> mBaz) is the shape every remaining
					//cluster wants to write.
					for (String arg : topLevelArgs(args)) {
						if (arg.contains("->") || arg.contains("() {")) {
							continue;
						}
						java.util.regex.Matcher w = word.matcher(arg);
						while (w.find()) {
							String name = w.group(1);
							Integer at = assignedAt.get(name);
							if (at != null && at > i) {
								handedEarly.add("line " + (i + 1) + " hands " + name
										+ ", which this method does not build until line " + (at + 1));
							}
						}
					}
				}
			}
			for (int j = 0; j < line.length(); j++) {
				char c = line.charAt(j);
				if (c == '{') {
					brace++;
				} else if (c == '}') {
					brace--;
					while (!deferred.isEmpty() && brace < deferred.peek()) {
						deferred.pop();
					}
				}
			}
			if (opensADeferredBody(line)) {
				deferred.push(brace);
			}
		}

		return handedEarly;
	}

	/**
	 * The argument text of a call whose open paren ends at {@code from}, across
	 * continuation lines.
	 *
	 * <p>The cap was eight lines and is now sixty. An argument list longer than
	 * eight lines is not exotic here - the window's own editor lists run to
	 * thirty - and giving up returned null, which the caller read as "nothing to
	 * check". A rule that goes quiet on the largest calls in the file is worst
	 * exactly where it is needed most.
	 */
	static String arguments(List<String> lines, int line, int from) {
		StringBuilder sb = new StringBuilder();
		int depth = 1;
		for (int i = line; i < lines.size() && i < line + 60; i++) {
			String s = lines.get(i);
			for (int j = (i == line ? from : 0); j < s.length(); j++) {
				char c = s.charAt(j);
				if (c == '(') {
					depth++;
				} else if (c == ')') {
					depth--;
					if (depth == 0) {
						return sb.toString();
					}
				}
				sb.append(c);
			}
			sb.append(' ');
		}
		return null;
	}

	/**
	 * Every zone the window hands the Zone tab is also given a table slot, in
	 * the same block.
	 *
	 * <p>{@code ZoneLoadingPanel.loadZone} deliberately keeps whatever index was
	 * last recorded, because the dropdown's list worker records its own on the
	 * very next line. That makes the call SAFE where an index follows it and
	 * silently wrong where none does: File &gt; Open Zone recorded none, so a
	 * zone opened from a loose file inherited the index of whatever zone the
	 * user had last picked, and the next save wrote that file's 0x38-byte header
	 * into the master zone-header table at THAT slot, replacing an unrelated
	 * map's entry without a word.
	 *
	 * <p>The instance is fixed at its call site. This is what makes a SECOND one
	 * noticeable: a new {@code mZonePnl.loadZone(} anywhere in the window has to
	 * say which slot the zone belongs to, or say -1 for none, within a few lines
	 * of asking for it. Source rather than bytecode because the two calls are
	 * ordinary and it is their ADJACENCY that is the rule.
	 */
	static void everyZoneHandedToTheTabIsGivenASlot(File srcRoot) throws Exception {
		System.out.println("--- every zone handed to the Zone tab is given a table slot");
		File f = new File(srcRoot, WINDOW + ".java");
		if (!f.isFile()) {
			check(false, "no " + f.getPath() + " to read - this rule is checked over source");
			return;
		}
		List<String> lines = new ArrayList<>();
		java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
			new java.io.FileInputStream(f), "UTF-8"));
		for (String line = r.readLine(); line != null; line = r.readLine()) {
			lines.add(line);
		}
		r.close();
		//KNOWN ANSWERS FIRST (F250): the rule skipped any line holding "//", so a real call with a
		//comment after it was exempt, and it saw only the receiver spelled mZonePnl
		check(unslotted(Arrays.asList("mZonePnl.loadZone(z); // why", "x();")).size() == 1
				&& unslotted(Arrays.asList("panel.loadZone(z);", "x();")).size() == 1
				&& unslotted(Arrays.asList("// mZonePnl.loadZone(z);", "x();")).isEmpty()
				&& unslotted(Arrays.asList("mZonePnl.loadZone(z); // why", "loadedZone.open(z, 3);")).isEmpty(),
				"control: a call with a comment after it and a call on another receiver are seen, a call in a"
				+ " comment is not, and a call followed by its slot passes");
		List<String> unslotted = unslotted(lines);
		int calls = 0;
		for (String line : SourceSeamTest.stripComments(String.join("\n", lines)).split("\n", -1)) {
			calls += LOAD_ZONE.matcher(line).find() ? 1 : 0;
		}
		check(calls > 0, "the window hands the Zone tab a zone somewhere (" + calls
			+ " call site(s)) - a rule with nothing to check is not a rule");
		check(unslotted.isEmpty(), "and every one records the slot it belongs to, or -1 for none "
			+ unslotted);
	}

	/** A zone handed to a panel, whatever the receiver is called. */
	static final java.util.regex.Pattern LOAD_ZONE = java.util.regex.Pattern.compile("\\.loadZone\\(");

	/** The lines, comments blanked, that hand a zone over with no slot recorded within the next five. */
	static List<String> unslotted(List<String> raw) {
		String[] lines = SourceSeamTest.stripComments(String.join("\n", raw)).split("\n", -1);
		List<String> out = new ArrayList<>();
		for (int i = 0; i < lines.length; i++) {
			if (!LOAD_ZONE.matcher(lines[i]).find()) {
				continue;
			}
			boolean slotted = false;
			for (int j = i + 1; j < Math.min(lines.length, i + 6) && !slotted; j++) {
				slotted = lines[j].contains("loadedZone.open(");
			}
			if (!slotted) {
				out.add((i + 1) + ": " + lines[i].trim());
			}
		}
		return out;
	}

	/**
	 * The window's zone history watches the zone's script (F209), which the NPC wizards replace, the dialogue
	 * editor re-points and the script panel edits. Asked of the class file, because the window binds its zone
	 * only in createAndShowGUI, which no suite builds: the window calls ZoneDocuments.script, as it calls
	 * ZoneDocuments.entities for the history it is built over (the control).
	 */
	static void theZonesHistoryWatchesItsScript(File classes) throws Exception {
		System.out.println("--- the window's zone history watches the zone's script (F209)");
		ClassFileScanner.ClassFile window = ClassFileScanner.read(new File(classes, WINDOW + ".class"), WINDOW);
		boolean entities = false;
		boolean script = false;
		for (ClassFileScanner.Method m : window.methods) {
			for (String call : m.invokes) {
				entities |= call.startsWith("ctrmap/humaninterface/ZoneDocuments#entities");
				script |= call.startsWith("ctrmap/humaninterface/ZoneDocuments#script");
			}
		}
		check(entities, "control: the window's calls are read - it builds its history over ZoneDocuments.entities");
		check(script, "the window's zone history watches the zone's script: it calls ZoneDocuments.script");
	}

	/**
	 * The rule catches the three shapes it used to miss, and still does not
	 * flag the one it must not.
	 *
	 * <p>WHY THIS SECTION EXISTS. The rule above is a ratchet over one file, so
	 * "it passes" says nothing about what it can SEE. Three holes were found in
	 * it by reading it rather than by running it, and every one of them was in
	 * the direction that lets the original defect back in: it read only
	 * {@code new X(...)} and never a setter; one lambda anywhere in an argument
	 * list excused every plain argument beside it; and it gave up after eight
	 * continuation lines, which is shorter than the window's own editor lists.
	 * A rule that goes quiet on the largest calls in the file is worst exactly
	 * where it is needed.
	 *
	 * <p>The fourth case is the one that must NOT fire: a lambda body naming a
	 * static assigned further down is correct, because the body runs when it is
	 * called. That is how both editor lists can be built before the editors they
	 * name, and a rule that forbade it would forbid the fix it exists to protect.
	 */
	static void theRuleSeesTheShapesItUsedToMiss() {
		System.out.println("--- and the rule can see the shapes it used to miss");

		check(!handedTooEarly(source(
				"mPanel.setEditor(mLate);",
				"mLate = new Editor();")).isEmpty(),
				"a static handed to a SETTER before it is built is caught");

		check(!handedTooEarly(source(
				"mPanel = new Panel(mLate, () -> mAnything.go());",
				"mLate = new Editor();")).isEmpty(),
				"and a plain argument beside a lambda is still checked");

		StringBuilder longCall = new StringBuilder("mPanel = new Panel(");
		for (int i = 0; i < 30; i++) {
			longCall.append("\n\t\t\t\tfiller,");
		}
		check(!handedTooEarly(source(longCall + "\n\t\t\t\tmLate);", "mLate = new Editor();")).isEmpty(),
				"and an argument list thirty lines long does not fall off the end of the scan");

		check(handedTooEarly(source(
				"mPanel = new Panel(java.util.Arrays.asList(",
				"\t\t() -> mLate.go()));",
				"mLate = new Editor();")).isEmpty(),
				"while a lambda BODY naming a static built later is left alone - that is the fix, not the defect");
	}

	/** A one-method window whose statics are all declared, for the section above. */
	static List<String> source(String... body) {
		List<String> lines = new ArrayList<>(Arrays.asList(
				"public class W {",
				"\tpublic static Editor mLate;",
				"\tpublic static Panel mPanel;",
				"\tpublic static Object mAnything;",
				"\tpublic static Object filler;",
				"",
				"\tprivate static void createAndShowGUI() {"));
		for (String line : body) {
			for (String one : line.split("\n")) {
				lines.add("\t\t" + one);
			}
		}
		lines.add("\t}");
		lines.add("}");
		return lines;
	}

	/**
	 * An argument list split at its top-level commas, so one lambda in it does
	 * not excuse the arguments beside it.
	 */
	static List<String> topLevelArgs(String args) {
		List<String> out = new ArrayList<>();
		int depth = 0;
		int from = 0;
		for (int i = 0; i < args.length(); i++) {
			char c = args.charAt(i);
			if (c == '(' || c == '[' || c == '{') {
				depth++;
			} else if (c == ')' || c == ']' || c == '}') {
				depth--;
			} else if (c == ',' && depth == 0) {
				out.add(args.substring(from, i));
				from = i + 1;
			}
		}
		out.add(args.substring(from));
		return out;
	}

	/**
	 * Whether this line opens a body that runs later - a lambda block or an
	 * anonymous class. Its contents are not evaluated where they are written,
	 * which is the one legitimate reason to name a static that is assigned
	 * further down.
	 */
	static boolean opensADeferredBody(String line) {
		String s = line.trim();
		if (!s.endsWith("{")) {
			return false;
		}
		//A LAMBDA BLOCK OR AN ANONYMOUS CLASS, and nothing else. The first version
		//of this test asked whether the line ended "() {", which is also how every
		//no-argument method declaration ends - including createAndShowGUI's own.
		//So the whole method was marked deferred at its first line and the rule
		//silently checked nothing while reporting no problems, which is the exact
		//failure this suite exists to make impossible. The section that hands this
		//rule a synthetic source is what caught it.
		return s.contains("->") || s.contains("new ");
	}

	static int count(String s, char c) {
		int n = 0;
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) == c) {
				n++;
			}
		}
		return n;
	}

	// -------------------------------------------------------- 3. the window's own
	/**
	 * The window keeps its state in itself, not in its class (F007).
	 *
	 * <p>It was a class of statics: 43 fields - 19 of them public - and every method that touched them,
	 * so one window per process was the only shape the program could take, and the suites set its
	 * fields as the program's globals. This counted the public ones down to 19 and stopped there; it was
	 * a ratchet on a number, and a number is satisfied by moving a field from public to private. Now the
	 * rule is the shape: a static field of the window is a compile-time constant - final, and a primitive
	 * or a String - or it is refused by name, public or not.
	 */
	static void theWindowKeepsNoStateInStatics() {
		System.out.println("--- and the window keeps no state in statics (F007)");
		List<String> statics = new ArrayList<>();
		List<String> constants = new ArrayList<>();
		int instance = 0;
		for (Field f : CtrmapMainframe.class.getDeclaredFields()) {
			if (f.isSynthetic()) {
				continue;
			}
			if (!Modifier.isStatic(f.getModifiers())) {
				instance++;
			} else if (Modifier.isFinal(f.getModifiers())
					&& (f.getType().isPrimitive() || f.getType() == String.class)) {
				constants.add(f.getName());
			} else {
				statics.add(f.getName());
			}
		}
		check(instance > 0 && constants.contains("INDEPENDENT_TITLE"),
				"control: the window's own fields are read - " + instance + " instance, constants " + constants);
		check(statics.isEmpty(), "and none is static but a constant: " + statics);
	}

	/**
	 * Nothing of the window can be reached but its entry point and its constructor (F007).
	 *
	 * <p>Its panels were public statics that existed for the suites alone - no class of the program read
	 * one - and its methods were public for the same reason; the program's only way in was main. Every
	 * member is private now: a field, a method, a nested type, anything but {@code main} and the
	 * constructor the program and the suites build it with. The suites reach what they drive through
	 * TestWindow, by reflection, which nothing in the program does by accident. The rule reads the
	 * modifier of every member the class declares, so a public field added tomorrow is refused by name,
	 * whatever it is for.
	 */
	static void theWindowExposesNothing() {
		System.out.println("--- nothing of the window is reachable but main and its constructor (F007)");
		List<String> fixture = exposed(ExposedFixture.class);
		check(fixture.equals(Arrays.asList("constructor[int]", "field shown", "method open()", "type Open")),
				"control: a public field, a package method, a public nested type and a constructor that takes"
				+ " something are caught, the private ones, main and the bare constructor are not: " + fixture);
		List<String> window = exposed(CtrmapMainframe.class);
		check(window.isEmpty(), "and the window exposes nothing else: " + window);
	}

	/** Every member {@code c} declares that is not private - but main(String[]) and a constructor that takes nothing. */
	static List<String> exposed(Class<?> c) {
		List<String> out = new ArrayList<>();
		for (Field f : c.getDeclaredFields()) {
			if (!f.isSynthetic() && !Modifier.isPrivate(f.getModifiers())) {
				out.add("field " + f.getName());
			}
		}
		for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
			if (m.isSynthetic() || Modifier.isPrivate(m.getModifiers())) {
				continue;
			}
			if (m.getName().equals("main") && Modifier.isStatic(m.getModifiers())
					&& Arrays.equals(m.getParameterTypes(), new Class<?>[]{String[].class})) {
				continue;
			}
			out.add("method " + m.getName() + "()");
		}
		for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) {
			if (!k.isSynthetic() && !Modifier.isPrivate(k.getModifiers()) && k.getParameterTypes().length > 0) {
				out.add("constructor" + Arrays.toString(k.getParameterTypes()));
			}
		}
		for (Class<?> n : c.getDeclaredClasses()) {
			if (!n.isSynthetic() && !Modifier.isPrivate(n.getModifiers())) {
				out.add("type " + n.getSimpleName());
			}
		}
		java.util.Collections.sort(out);
		return out;
	}

	/** The rule's known answer: one of each thing it refuses, beside one of each it lets be. */
	@SuppressWarnings("unused")
	static final class ExposedFixture {

		public int shown;
		private int hidden;

		ExposedFixture() {
		}

		ExposedFixture(int given) {
		}

		public static void main(String[] args) {
		}

		void open() {
		}

		private void closed() {
		}

		public static final class Open {
		}

		private static final class Closed {
		}
	}

	/**
	 * The suites' way into the window refuses a member it was not written for (F007). TestWindow reads the
	 * window's private members by name; one renamed, removed or retyped must stop the suites, naming it,
	 * rather than hand them a null - or a ClassCastException - twenty checks on.
	 */
	static void theSuitesWayInRefusesAStaleName() {
		System.out.println("--- the suites' way into the window refuses a member it was not written for (F007)");
		javax.swing.JScrollPane was = TestWindow.mCamScrollPane();
		javax.swing.JScrollPane mine = new javax.swing.JScrollPane();
		TestWindow.mCamScrollPane(mine);
		check(TestWindow.field("mCamScrollPane") == mine, "control: a member set through its accessor is the one read by name");
		TestWindow.mCamScrollPane(was);
		check(refusal(() -> TestWindow.field("noSuchPanel")).contains("no field noSuchPanel"),
				"a name the window does not declare is refused by name: " + refusal(() -> TestWindow.field("noSuchPanel")));
		String retyped = refusal(() -> TestWindow.field("mTileMapPanel", javax.swing.JScrollPane.class));
		check(retyped.contains("TileMapPanel") && retyped.contains("not the javax.swing.JScrollPane"),
				"a member of another type than the suites were written for is refused, naming both: " + retyped);
		String returns = refusal(() -> TestWindow.method("buildMenuBar", void.class));
		check(returns.contains("returns javax.swing.JMenuBar"), "a method returning another type is refused, naming both: " + returns);
		String params = refusal(() -> TestWindow.method("buildMenuBar", javax.swing.JMenuBar.class, int.class));
		check(params.contains("no buildMenuBar"), "and one with other parameters is refused: " + params);
	}

	/** What {@code r} was refused with, or "(nothing was thrown)". */
	static String refusal(Runnable r) {
		try {
			r.run();
			return "(nothing was thrown)";
		} catch (IllegalStateException refused) {
			return refused.getMessage();
		}
	}

	// ---- plumbing ----------------------------------------------------------
	static Set<String> union(Set<String> a, Set<String> b) {
		Set<String> out = new TreeSet<>(a);
		out.addAll(b);
		return out;
	}

	static String simple(String internal) {
		return internal.substring(internal.lastIndexOf('/') + 1);
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok: " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}
}
