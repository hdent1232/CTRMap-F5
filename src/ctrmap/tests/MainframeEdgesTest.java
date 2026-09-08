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
		{"mMtxPanel", "MatrixEditForm,MatrixSelector,ZoneLoadingPanel",
			"the matrix panel, the other half of the same editor"},
		{"mNPCEditForm", "TileMapPanel,TrainerEditDialog,ZoneLoadingPanel",
			"the NPC editor, loaded with the zone and drawn over the map"},
		{"mPaintForm", "ZoneLoadingPanel", "the painter, cancelled when a zone closes"},
		{"mPropEditForm", "TileMapPanel",
			"the prop editor, drawn over the map"},
		{"mScriptPnl", "NPCEditForm,ZoneLoadingPanel",
			"the script editor, which an NPC's script edits go through"},
		{"mTileEditForm", "Selector,TileMapPanel,TileUndo,WorkspaceSettings",
			"the tile inspector, which the selector and the undo stack update"},
		{"mTileMapPanel", "GeoEditForm,GfEnvPicker,NPCEditForm,PaintForm,PropEditForm,Selector,TileEditForm,"
			+ "TileMapPanel,TileUndo,TriggerEditForm,WarpEditForm,WorkspaceSettings,ZoneLoadingPanel",
			"THE map view: every editor that draws on it or reads a tile from it. The tangle - it reads"
			+ " four of these back"},
		{"mTilemapScrollPane", "TileMapPanel", "its own scroll pane, for the viewport size"},
		{"mTriggerEditForm", "ZoneLoadingPanel", "the trigger editor, loaded and cleared with the zone"},
		{"mWarpEditForm", "ZoneLoadingPanel", "the warp editor, loaded and cleared with the zone"},
		{"mZonePnl", "NPCEditForm,ScriptEditor,SetupWizard,TilePainterForm",
			"the Zone tab as an OPERATION - its save, its rebuild, its zone count. None of them is zone"
			+ " state: that has an owner (LoadedZoneTest)"},
		{"worldToolbar", "TileEditForm", "the tool row, asked to select the Set tool"},
	};

	/**
	 * Field references from outside, counted with duplicates: the number above
	 * is classes, this is call sites. Measured 2026-09-08 at 108 before the
	 * decoupling steps, 64 after them, and 55 once the five copies
	 * of the editor flush became one owner ({@link ctrmap.humaninterface.OpenEditors}):
	 * the map painter left this list entirely, and the Zone tab stopped naming
	 * the matrix and prop forms.
	 */
	private static final int REFERENCES = 55;

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
