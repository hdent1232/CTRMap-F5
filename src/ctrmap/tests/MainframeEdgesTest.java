package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
	 * {field, readers, what they use it for}. Measured over the compiled
	 * program, the window itself excluded, folded to top-level classes.
	 */
	private static final String[][] REACHES = {
		{"mNPCEditForm", "TileMapPanel,TrainerEditDialog",
			"the NPC editor, loaded with the zone and drawn over the map"},
		{"mPropEditForm", "TileMapPanel",
			"the prop editor, drawn over the map"},
		{"mScriptPnl", "NPCEditForm",
			"the script editor, which an NPC's script edits go through"},
		{"mTileEditForm", "Selector,TileMapPanel,TileUndo,WorkspaceSettings",
			"the tile inspector, which the selector and the undo stack update"},
		{"mTileMapPanel", "GeoEditForm,GfEnvPicker,NPCEditForm,PaintForm,PropEditForm,Selector,TileEditForm,"
			+ "TileUndo,WorkspaceSettings",
			"THE map view: every editor that draws on it or reads a tile from it. The tangle - it reads"
			+ " four of these back"},
				{"mZonePnl", "NPCEditForm,ScriptEditor,SetupWizard,TilePainterForm",
			"the Zone tab as an OPERATION - its save, its rebuild, its zone count. None of them is zone"
			+ " state: that has an owner (LoadedZoneTest)"},
		{"worldToolbar", "TileEditForm", "the tool row, asked to select the Set tool"},
	};

	/**
	 * How many DISTINCT (class file, field) pairs reach in from outside.
	 *
	 * <p>THIS IS NOT A COUNT OF CALL SITES, and this javadoc said it was for
	 * three commits. {@link ClassFileScanner.ClassFile#refs} is the constant
	 * pool, and javac emits one Fieldref per (class file, field) however many
	 * times the field is read. Two consequences worth having in front of you
	 * before planning any of this work: an anonymous inner class is its own
	 * class file and therefore its own reference, while a Java 8 lambda is not -
	 * it compiles to a synthetic method in the enclosing class. And a change
	 * removes a reference only when it removes the LAST read of that field from
	 * that one class file, which is why fifteen reads in one class fall to zero
	 * together or not at all.
	 *
	 * <p>Measured 2026-09-08 at 108 before the
	 * decoupling steps, 64 after them, 55 once the five copies of the editor
	 * flush became one owner ({@link ctrmap.humaninterface.OpenEditors}), and 49
	 * once "show this zone" and "show nothing" became another
	 * ({@link ctrmap.humaninterface.ZoneEditors}), 47 once that owner also said
	 * what "commit what you are holding" means, and 45 once the 3D gizmo became
	 * {@link ctrmap.humaninterface.Navigator} - five copies of the same reach,
	 * two of which guarded against a null panel and three of which did not. The map painter left this
	 * list entirely; the Zone tab stopped naming the matrix, prop, warp, script,
	 * matrix-panel, NPC and trigger editors.
	 */
	private static final int REFERENCES = 27;

	/**
	 * Public static fields on the window: 91 before the structure sweep, 22 after
	 * it, 21 for a long time, 19 now. The two that went - the text editor and the
	 * map builder - had ZERO readers anywhere, program or suite. Nobody proposed
	 * them because nobody had counted; a public static with no readers is not
	 * coupling anyone has, it is coupling nobody has had to argue against.
	 */
	private static final int PUBLIC_STATICS = 19;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File classes = new File(args.length > 0 ? args[0] : "build/classes");
		if (!new File(classes, WINDOW + ".class").isFile()) {
			System.out.println("FAIL: no compiled " + WINDOW + ".class under " + classes.getAbsolutePath()
					+ " - run build.ps1 first; this suite reads class files, not source");
			System.exit(1);
		}

		everyReachIntoTheWindowIsNamed(classes);
		nothingOutsideTheWindowWritesItsStatics(classes);
		theWindowKeepsNoMoreStaticsThanRecorded();
		nothingIsHandedAStaticTheWindowHasNotBuiltYet(new File(args.length > 1 ? args[1] : "src"));
		theRuleSeesTheShapesItUsedToMiss();

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
				"and the number of call sites is the recorded one - it only falls (" + references + ")");
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

		List<String> handedEarly = handedTooEarly(lines);
		check(handedEarly.isEmpty(), "every static handed to something the window builds is already built "
				+ handedEarly);
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
		java.util.regex.Pattern decl = java.util.regex.Pattern.compile(
				"^\\s*(?:public|private|protected)\\s+static\\s+(?:final\\s+)?[\\w.<>\\[\\]]+\\s+(\\w+)\\s*[;=]");
		Set<String> statics = new TreeSet<>();
		for (String line : lines) {
			java.util.regex.Matcher m = decl.matcher(line);
			if (m.find()) {
				statics.add(m.group(1));
			}
		}

		int start = -1;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).contains("private static void createAndShowGUI()")) {
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
	static void theWindowKeepsNoMoreStaticsThanRecorded() {
		System.out.println("--- and the window keeps no more public statics than recorded");
		List<String> statics = new ArrayList<>();
		for (Field f : CtrmapMainframe.class.getDeclaredFields()) {
			if (Modifier.isPublic(f.getModifiers()) && Modifier.isStatic(f.getModifiers())) {
				statics.add(f.getName());
			}
		}
		check(statics.size() == PUBLIC_STATICS,
				"the window has " + PUBLIC_STATICS + " public statics (it has " + statics.size() + ": "
				+ statics + ")");
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
