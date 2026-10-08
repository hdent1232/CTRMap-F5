package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A capability has ONE implementation, and a new class has to say what it is not.
 *
 * <p>WHY THIS EXISTS, and it is not a general theory. On 2026-09-12 the owner asked
 * why a zone preview had been built as a second, smaller copy of the editor's map
 * loader instead of using the one that has drawn zones correctly for years. Every
 * defect they reported about that preview - one region instead of the map, a region
 * belonging to the zone next door, four cells of a thirteen-cell map - was a hole in
 * the copy, not in the editor. The copy is deleted now.
 *
 * <p>The instruction "remove duplication" has been given to this project more than
 * once, and a sweep was run against it. That sweep counted public statics, coupling
 * to the window globals, god objects, dialogs and mutation coverage - every one of
 * them a number that could be ratcheted - and never counted implementations, which
 * is where duplication actually shows up. So the sweep could not have removed this
 * and the report did not say so. A rule with no counter behind it applies to
 * nothing; that has now been true here three times, for three different rules.
 *
 * <p>WHAT IT REFUSES:
 * <ul>
 * <li>A second file doing something a named class already owns. The table below is
 *     seeded with the capabilities that have actually been duplicated, each with the
 *     marker that gives it away and the files allowed to carry it. New entries are
 *     welcome; new UNARGUED users are not.</li>
 * <li>A production class that is not in {@code tools/guard/classes.txt} and does not
 *     say, in its own javadoc, which existing class does the nearest job and why it
 *     could not be used. That line is the search this project keeps skipping, made
 *     into something a build can check: you cannot write it without having looked.</li>
 * </ul>
 *
 * <p>IT RUNS IN THE BUILD, not only in the battery. A guard that runs forty minutes
 * later lets the wrong thing be written, run and shipped first; the point of action
 * for authoring is the build, and this is the whole reason it sits there.
 *
 * <p>ORDER: needs no game, no dump and no display; reads source and writes nothing.
 *
 * Usage: java ctrmap.tests.DuplicateWorkTest [src-root]   (default "src")
 */
public class DuplicateWorkTest {

	/**
	 * {capability, marker regex, comma-separated owners, why it has one owner}.
	 *
	 * <p>Seeded from what has actually gone wrong. A marker is chosen to be the
	 * thing only that capability does - not a word that could appear anywhere.
	 */
	private static final String[][] OWNED = {
		{"placing a map region in the world", "720f \\+ 360f|720 \\+ 360f|720 \\+ 360[^0-9]",
			"TileMapPanel.java",
			"a region sits at cell * 720 + 360, and the height lookups and picking divide by"
			+ " the same number. A second answer to where region (3,4) goes is a map whose"
			+ " pieces do not line up, which is what the owner was shown."},
		{"assembling a matrix into a drawable map", "readRegions\\s*\\(",
			"TileMapPanel.java,ZonePreviewPane.java",
			"TileMapPanel.readRegions is the body (its show half, showRegions, installs what it"
			+ " read - F220); the preview and the window's own load CALL it."
			+ " Anything else naming it is fine - anything else DOING it is the defect this"
			+ " suite exists for."},
		{"walking a Pawn routine and its callees",
			"static void dfsClosure\\s*\\(|static void validateStubInsertPreconditions\\s*\\(",
			"PawnClosure.java",
			"one closure walk, one set of stub-insert preconditions. This existed twice - the "
			+ "message and sign transplants were measured at 293 of 326 substantive lines "
			+ "shared, byte-identical in the walk itself - and it rewrites bytecode in zone "
			+ "scripts bound to the save, so a fix landing on one copy and not the other is "
			+ "a zone that hangs on entry for whoever used the other one"},
		{"building a GameFreak name tree", "<<\\s*3\\)\\s*-\\s*1|\\*\\s*8L?\\s*-\\s*1\\b",
			"Patricia.java",
			"the discriminating-bit start of a Patricia insertion. It existed three times - the"
			+ " texture pack, the model appender and the area animation each built their own"
			+ " tree, each claimed byte-exact on its own - and a fix to one would not have"
			+ " reached the others (F066)."},
		{"recognising a BCH by its magic", "\\[1\\]\\s*[!=]=\\s*'C'\\s*(&&|\\|\\|)\\s*\\w+\\[2\\]\\s*[!=]=\\s*'H'",
			"BchSections.java",
			"BchSections.hasMagic is the sniff and isBch the whole-header check; seven hand-written"
			+ " copies disagreed on three bytes or four and on how long a file must be (F066)."},
		{"reading a BCH header or its relocation table", "relocLen\\s*/\\s*4|i32\\(\\s*\\w+\\s*,\\s*0x1C\\s*\\)",
			"BchSections.java",
			"BchSections reads the header for every layout and decodes the relocation words; five"
			+ " readers did it themselves and one placed command-section entries at contents"
			+ " addresses (F066)."},
		{"reading a NUL-terminated string", "\\(char\\)\\s*\\(\\s*\\w+\\[\\w+\\]\\s*&\\s*0xFF\\)",
			"StringUtils.java,DressUpIndex.java",
			"StringUtils.cString is the one loop; there were seven, with two contracts, and the"
			+ " stream reader beside them looped forever on a string nothing ended (F066)."
			+ " DressUpIndex builds a two-byte magic this way, not a string."},
		{"rounding up to a boundary", "\\(\\s*\\w+\\s*\\+\\s*\\w+\\s*-\\s*1\\s*\\)\\s*/\\s*\\w+\\s*\\*\\s*\\w+",
			"Bytes.java",
			"Bytes.align is the one copy; the texture pack, the model appender and the area"
			+ " animation each had their own (F066)."},
		{"finding the mesh that draws a named material",
			"for\\s*\\(\\s*int\\s+(\\w+)\\s*=\\s*0\\s*;\\s*\\1\\s*<\\s*(?:\\w+\\.)?(?:meshCount|meshes\\.size\\(\\))"
			+ "\\s*;\\s*\\1\\+\\+\\s*\\)\\s*\\{\\s*if\\s*\\(\\s*[\\w.]+\\.equals\\(\\s*(?:\\w+\\.)?getMaterialName\\("
			+ "\\s*(?:\\w+\\.)?getMeshMaterialIndex\\(\\s*\\1\\s*\\)",
			"BchMapModel.java",
			"BchMapModel.meshWithMaterial is the one first-match lookup by exact name. Seven loops"
			+ " wrote it out across the importers, the prefab stamper and the painter (F092); a loop"
			+ " that also filters on layout, or matches loosely, is a different job and does not"
			+ " open this way."},
		{"starting a background thread", "new\\s+Thread\\s*\\(|Executors\\s*\\.\\s*new\\w+\\s*\\(",
			"NewestOnly.java,UpdateUI.java,CM3DInputManager.java,ESPICAControl.java",
			"a preview asks NewestOnly: one daemon worker, newest request wins (F206). Both previews"
			+ " started a raw thread per list selection and ran every superseded one to the end. The"
			+ " other three are argued: the updater's download, the 3D walker's loop and ESPICA's"
			+ " process reader are one long-lived daemon each. SwingWorker is not this and is not"
			+ " counted."},
		{"reading text with a Scanner", "new\\s+(java\\.util\\.)?Scanner\\s*\\(",
			"ScriptEditor.java,PawnAssembly.java",
			"a Scanner keeps a read failure to itself and simply runs out of lines (F211): the terrain and"
			+ " ground-material tables, the building catalog and the snapshot stamp each read a failed read as"
			+ " a shorter file. These two scan a String, which cannot fail; a file or a stream is read with a"
			+ " BufferedReader, whose readLine throws."},
		{"finding the game as it shipped", "originalSnapshotDir\\s*\\(",
			"WorkspaceSession.java,Workspace.java",
			"GameFiles.pristineArchive is the one pristine reader: the snapshot, else the sealed backup's"
			+ " verified copy, else nothing - never the live folder (F232). Three readers built"
			+ " \"snapshot + path\" by hand and two of them, with no snapshot, read the user's edited"
			+ " game instead. The session owns the snapshot folder; the static facade only names it."},
		{"escaping text for an HTML label", "replace(All)?\\(\\s*\"&\"\\s*,\\s*\"&amp;\"\\s*\\)",
			"Ui.java",
			"Ui.html is the one escape. The update dialog and the setup wizard each kept a private"
			+ " copy, and the painter's preview note (F211) would have been the third; a copy that"
			+ " forgets one character draws a reason with a '<' in it as a broken tag."},
		{"defining the zone header's size", "static\\s+final\\s+int\\s+\\w+\\s*=\\s*0x38\\s*;",
			"ZoneHeader.java",
			"ZoneHeader.SIZE is the one definition. Six classes each wrote their own 0x38 (F205); the"
			+ " master table, both forkers, the cloner, the integrity check and the zone-limit patch now"
			+ " alias it, so a correction lands everywhere at once."},
		{"preparing map geometry for drawing", "\\.makeAllBOs\\(\\)",
			"H3DModel.java,NPCRegistry.java,ADPropRegistry.java,CustomH3DPreview.java,"
			+ "MapPreview3D.java,PropEditForm.java,TileMapPanel.java",
			"the six views and registries that had this before the rule existed. The list may"
			+ " shrink for free; it grows only when somebody argues for a seventh."},
	};

	/** Classes nothing in the application reaches, each with what it is instead. */
	private static final String[][] UNREACHED = {
		{"BuildingHarvester.java",
			"a research tool the owner runs directly: it walks every region and writes the "
			+ "building catalogue TSV. Not shipped in a menu on purpose"},
		{"TerrainHarvester.java",
			"the same, for terrain: run by hand, output committed, not part of the editor"},
		{"TerrainDonorHarvester.java",
			"the same again, for donor terrain"},
		{"GroundMaterialAudit.java",
			"a one-shot audit the owner runs to check ground materials across the dump"},
		{"BchModelVerifier.java",
			"the acceptance oracle for BchModelAppender output: three suites run it over the "
			+ "857-model corpus and BchModelAppender names it as the rule its writes must "
			+ "satisfy - in prose, which is why nothing reaches it in code"},
		{"DressUpIndex.java",
			"MEASURED, NOT SHIPPED. The whole XY wardrobe index is decoded and proven by "
			+ "DressUpIndexTest; nothing in the editor can reach it yet. It is half a feature "
			+ "and it is recorded here rather than left looking alive"},
		{"DressUpArchive.java",
			"the other half of the same unshipped feature"},
	};

	/** How many may be unreached. It may fall and may not rise quietly. */
	private static final int UNREACHED_CEILING = 7;
	/** The snapshot of classes that predate the rule. */
	private static final String LIST = "tools/guard/classes.txt";

	/** What a new class must carry: the thing it is NOT, and why. */
	private static final Pattern NOT_LINE = Pattern.compile(
			"(?m)^\\s*\\*\\s*(?:<p>)?\\s*Not:\\s*\\{?@?l?i?n?k?\\s*([A-Za-z0-9_.#]+)[^-]*-\\s*(.{20,})");

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		if (!new File(root, "ctrmap").isDirectory()) {
			System.out.println("FAIL: source root not found: " + root.getAbsolutePath());
			System.exit(1);
		}
		File repo = root.getParentFile() == null ? new File(".") : root.getParentFile();
		aCapabilityHasOneImplementation(root);
		aNewClassSaysWhatItIsNot(root, new File(repo, LIST));
		everyClassIsReachedBySomething(root);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** Rule one: the marker of an owned capability appears only where it is owned. */
	static void aCapabilityHasOneImplementation(File root) throws Exception {
		System.out.println("--- a capability has one implementation");
		for (String[] owned : OWNED) {
			Pattern marker = Pattern.compile(owned[1]);
			List<String> allowed = Arrays.asList(owned[2].split(","));
			List<String> strays = new ArrayList<>();
			boolean seen = false;
			for (File f : DialogSeamTest.javaSources(new File(root, "ctrmap"))) {
				if (f.getParentFile() != null && f.getParentFile().getName().equals("tests")) {
					continue;
				}
				String src = SourceSeamTest.stripComments(
						new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
				Matcher m = marker.matcher(src);
				boolean found = m.find();
				seen |= found && allowed.contains(f.getName());
				if (found && !allowed.contains(f.getName())) {
					strays.add(f.getName());
				}
			}
			//a marker that matches nothing anywhere passes the stray check by never looking:
			//it must find the capability where it IS owned before its silence elsewhere means anything
			check(seen, "control: the marker for " + owned[0] + " is found in its owner " + owned[2]);
			check(strays.isEmpty(), "only " + owned[2] + " does " + owned[0]
					+ (strays.isEmpty() ? "" : " - but so does " + strays + ". " + owned[3]
					+ " If this really is a different job, give it its own entry in OWNED;"
					+ " if it is the same job, call the owner."));
		}
	}

	/**
	 * Rule two: a class written after this rule names the nearest existing thing.
	 *
	 * <p>The snapshot is what existed when the rule arrived - 268 classes nobody is
	 * going back to annotate. Everything after it carries one line:
	 * {@code Not: SomeClass - why it could not be used}.
	 */
	static void aNewClassSaysWhatItIsNot(File root, File list) throws Exception {
		System.out.println("--- a class written since the rule says what it is not");
		if (!list.isFile()) {
			check(false, "no class snapshot at " + list.getPath() + " - regenerate it with"
					+ " tools/guard/snapshot_classes.py, deliberately, because everything"
					+ " missing from it is asked to justify itself");
			return;
		}
		List<String> known = Arrays.asList(new String(Files.readAllBytes(list.toPath()),
				StandardCharsets.UTF_8).split("\\R"));
		List<String> silent = new ArrayList<>();
		int newOnes = 0;
		for (File f : DialogSeamTest.javaSources(new File(root, "ctrmap"))) {
			if (f.getParentFile() != null && f.getParentFile().getName().equals("tests")) {
				continue;
			}
			String path = relative(root, f);
			if (known.contains(path)) {
				continue;
			}
			newOnes++;
			String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			if (!NOT_LINE.matcher(src).find()) {
				silent.add(path);
			}
		}
		check(silent.isEmpty(), newOnes + " class(es) written since the snapshot, each saying"
				+ " what it is not" + (silent.isEmpty() ? "" : " - except " + silent
				+ ". Add a javadoc line \"Not: <the nearest existing class> - <why it could not"
				+ " be used>\". The point is not the line; it is that you cannot write it"
				+ " without having looked, and not looking is how this tree got a second map"
				+ " loader."));
	}

	/**
	 * Every production class is reached by the application, or is named here with a reason.
	 *
	 * <p>WHY THIS EXISTS. This is the hole that let a previous "remove the debt" sweep
	 * report success: nothing measured REACHABILITY, so every ceiling in this battery was
	 * satisfiable by code that never runs. A dead class opens no window, holds no global
	 * and duplicates no capability - it compiles, ships in the jar, and counts toward the
	 * class snapshot as though it were alive. The census found 1,830 lines of it, including
	 * a third stale copy of the tile-semantics constants under the same names as the live
	 * one, and an 83-line class whose javadoc told the next reader it was live.
	 *
	 * <p>REACHED means: walk the references from CtrmapMainframe and the updater, through
	 * production sources only, to a fixed point. A class with its own main() is NOT live by
	 * that alone - four of the dead ones had one, which is exactly how they looked alive.
	 * Standalone tools and measured-but-unshipped formats are real, and they are listed
	 * below with what they are, which is the difference between a decision and a leftover.
	 */
	static void everyClassIsReachedBySomething(File root) throws Exception {
		System.out.println("--- every production class is reached, or is argued for by name");
		Map<String, String> src = new HashMap<>();
		for (File f : DialogSeamTest.javaSources(new File(root, "ctrmap"))) {
			if (f.getParentFile() != null && f.getParentFile().getName().equals("tests")) {
				continue;
			}
			src.put(f.getName().substring(0, f.getName().length() - ".java".length()),
				new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
		}
		List<String> live = new ArrayList<>();
		for (String root_ : new String[]{"CtrmapMainframe", "Updater"}) {
			if (src.containsKey(root_)) {
				live.add(root_);
			}
		}
		for (int i = 0; i < live.size(); i++) {
			String body = SourceSeamTest.stripComments(src.get(live.get(i)));
			for (String other : src.keySet()) {
				if (live.contains(other) || other.equals(live.get(i))) {
					continue;
				}
				if (Pattern.compile("\\b" + Pattern.quote(other) + "\\b").matcher(body).find()) {
					live.add(other);
				}
			}
		}
		List<String> stranded = new ArrayList<>();
		for (String cls : src.keySet()) {
			if (live.contains(cls)) {
				continue;
			}
			boolean argued = false;
			for (String[] e : UNREACHED) {
				argued |= e[0].equals(cls + ".java");
			}
			if (!argued) {
				stranded.add(cls);
			}
		}
		check(stranded.isEmpty(), live.size() + " of " + src.size() + " production class(es)"
			+ " are reached from the application" + (stranded.isEmpty() ? "" : " - and these are not, and are not argued for: " + stranded + ". Either something should call it, or it should be deleted, or it goes in UNREACHED with what it is. A class nothing reaches still compiles, still ships, and still counts toward every other ceiling in this battery"));
		check(UNREACHED.length <= UNREACHED_CEILING, UNREACHED.length + " class(es) argued as unreached, ceiling " + UNREACHED_CEILING + " - this may fall and may not rise quietly");
		//an argument for a class that is reached now, or is gone, is a record of a tree that no
		//longer exists: it tells the next reader "nothing uses this" about something that is used
		List<String> stale = new ArrayList<>();
		for (String[] e : UNREACHED) {
			String cls = e[0].substring(0, e[0].length() - ".java".length());
			if (!src.containsKey(cls)) {
				stale.add(e[0] + " (no such production class)");
			} else if (live.contains(cls)) {
				stale.add(e[0] + " (reached from the application)");
			}
		}
		check(stale.isEmpty(), "every class argued as unreached still is one" + (stale.isEmpty() ? ""
			: " - not " + stale + ". Take its entry out of UNREACHED and lower the ceiling with it"));
	}

	static String relative(File root, File f) {
		String r = root.getAbsolutePath();
		String p = f.getAbsolutePath();
		return (p.startsWith(r) ? p.substring(r.length() + 1) : p).replace('\\', '/');
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
