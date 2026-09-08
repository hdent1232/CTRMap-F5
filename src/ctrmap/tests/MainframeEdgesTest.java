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
 * tools used. What remains is a genuine tangle rather than an oversight, and it
 * is written down here rather than left to a number: the map view reads the NPC
 * form and the NPC form reads the map view, so no order of constructors can
 * hand them to each other. Breaking that needs a decision about which of them
 * owns what, which is a design step and not a migration.
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
		{"CM3DComponents", "TileMapPanel",
			"the list of things the 3D view draws; the map view adds itself to it"},
		{"m3DDebugPanel", "NPCEditForm,PropEditForm,TileMapPanel,ZoneLoadingPanel",
			"the 3D view, for binding the navigator gizmo to the record being edited"},
		{"mCamEditForm", "ZoneLoadingPanel",
			"the camera form, stored once as the zone switches"},
		{"mCollEditPanel", "TileMapPanel",
			"the collision editor, which the map view draws alongside"},
		{"mMtxEditForm", "MapMatrixPanel,MatrixPanelInputManager,MatrixSelector",
			"the matrix form: the matrix panel, its router and its selector are three halves of one editor"},
		{"mMtxPanel", "MatrixEditForm,MatrixSelector",
			"the matrix panel, the other half of the same editor"},
		{"mNPCEditForm", "TileMapPanel,TrainerEditDialog,ZoneLoadingPanel",
			"the NPC editor, loaded with the zone and drawn over the map"},
		{"mPaintForm", "ZoneLoadingPanel", "the painter, cancelled when a zone closes"},
		{"mPropEditForm", "TileMapPanel",
			"the prop editor, drawn over the map"},
		{"mScriptPnl", "NPCEditForm",
			"the script editor, which an NPC's script edits go through"},
		{"mTileEditForm", "Selector,TileMapPanel,TileUndo,WorkspaceSettings",
			"the tile inspector, which the selector and the undo stack update"},
		{"mTileMapPanel", "GeoEditForm,GfEnvPicker,NPCEditForm,PaintForm,PropEditForm,Selector,TileEditForm,"
			+ "TileMapPanel,TileUndo,TriggerEditForm,WarpEditForm,WorkspaceSettings,ZoneLoadingPanel",
			"THE map view: every editor that draws on it or reads a tile from it. The tangle - it reads"
			+ " four of these back"},
		{"mTilemapScrollPane", "TileMapPanel", "its own scroll pane, for the viewport size"},
		{"mTriggerEditForm", "ZoneLoadingPanel", "the trigger editor, loaded and cleared with the zone"},
				{"mZonePnl", "NPCEditForm,ScriptEditor,SetupWizard,TilePainterForm",
			"the Zone tab as an OPERATION - its save, its rebuild, its zone count. None of them is zone"
			+ " state: that has an owner (LoadedZoneTest)"},
		{"worldToolbar", "TileEditForm", "the tool row, asked to select the Set tool"},
	};

	/**
	 * Field references from outside, counted with duplicates: the number above
	 * is classes, this is call sites. Measured 2026-09-08 at 108 before the
	 * decoupling steps, 64 after them, 55 once the five copies of the editor
	 * flush became one owner ({@link ctrmap.humaninterface.OpenEditors}), and 49
	 * once "show this zone" and "show nothing" became another
	 * ({@link ctrmap.humaninterface.ZoneEditors}). The map painter left this
	 * list entirely; the Zone tab stopped naming the matrix, prop, warp, script
	 * and matrix-panel editors.
	 */
	private static final int REFERENCES = 49;

	/** Public static fields on the window. 91 before the structure sweep, 22 after it, 21 now. */
	private static final int PUBLIC_STATICS = 21;

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
			check(false, "no createAndShowGUI() in " + f.getPath() + " - this rule reads that method by name");
			return;
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
		java.util.regex.Pattern made = java.util.regex.Pattern.compile("new\\s+[\\w.]+\\s*\\(");
		java.util.regex.Pattern word = java.util.regex.Pattern.compile("\\b(\\w+)\\b");
		for (int i = start; i <= end; i++) {
			java.util.regex.Matcher m = made.matcher(lines.get(i));
			while (m.find()) {
				String args = arguments(lines, i, m.end());
				if (args == null || args.contains("->") || args.contains("() {")) {
					continue;
				}
				java.util.regex.Matcher w = word.matcher(args);
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

		check(handedEarly.isEmpty(), "every static handed to something the window builds is already built ("
				+ statics.size() + " statics, " + assignedAt.size() + " of them made here) " + handedEarly);
	}

	/** The argument text of a call whose open paren ends at {@code from}, across continuation lines. */
	static String arguments(List<String> lines, int line, int from) {
		StringBuilder sb = new StringBuilder();
		int depth = 1;
		for (int i = line; i < lines.size() && i < line + 8; i++) {
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
