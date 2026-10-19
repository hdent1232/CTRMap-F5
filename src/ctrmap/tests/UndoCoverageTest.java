package ctrmap.tests;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every editor keeps its edits in an undo history, or says in words why it cannot (F209d).
 *
 * <p>The owner's rule is undo and redo everywhere. Measured over the compiled program on 2026-10-08, the
 * user-interface classes that reach an edit came to 49 - 32 found by the raw file writes they reach, 39 by
 * the marks an edit leaves - and 12 of them named a history. Nothing counted the rest, so an editor could be
 * written with no history at all and nothing would say so.
 *
 * <p>THE SUBJECT IS DERIVED, NOT LISTED. An editor is an outer class where the user meets the program - it,
 * or a class nested in it, is a Swing or AWT component or has a method handed a user's event - any of whose
 * methods reaches, through the calls it makes, a raw file write or the mark an edit leaves: an
 * {@code edited()} call, {@code Workspace.addPersist}, a {@code storeFile}, a {@code modified} or
 * {@code dirty} flag set. Both are asked: each alone missed editors the other found - the shop patch writes
 * through CodeIps and marks nothing, the tile form marks a tilemap and writes nothing. Read by package
 * instead, the first run counted the updater and the workspace's own writer, because one class in their
 * packages is a component, and missed every drag tool, because theirs holds none.
 *
 * <p>An editor is COVERED when every ENTRY of it - a method nothing else in it calls: an event handler, a
 * lambda on a button, a method another class calls - that reaches an edit either hands a history that edit
 * (builds one over documents, adds to it, writes through it, has it watch more) or reaches only edits that
 * mark state a history document holds - the zone's entities, its tiles - and, when one leans on a history, a
 * suite that undoes through a history names it. Reached from another class, a method that hands a history
 * its edits keeps them - the geometry tool, closing, calls the geometry form's save - and a class that says
 * why it keeps none says it for every caller. Judged per class instead, an editor that kept one save of three
 * passed (2026-10-08). Otherwise its class comment says why it keeps none,
 * {@code No undo:} and a reason of {@value #REASON_MIN} characters or more, or it is OPEN: counted against a
 * ceiling that only falls.
 *
 * <p>Usage: java ctrmap.tests.UndoCoverageTest [classesRoot [sourceRoot]]
 */
public class UndoCoverageTest {

	static final String HISTORY = "ctrmap/humaninterface/UndoHistory";
	/** The mark on a method that writes and changes nothing the user made - where the derivation stops. */
	static final String NOT_AN_EDIT = "Lctrmap/util/NotAnEdit;";
	/** The mark on a store that writes out documents its class's history watches. */
	static final String WRITES_OUT = "Lctrmap/util/WritesOut;";
	static final String DOCUMENT = HISTORY + "$Document";
	/** The shortest reason a {@code No undo:} may give - a reason, not a word. */
	static final int REASON_MIN = 60;
	/**
	 * Editors that keep no history and say nothing about it, recorded. Each one given a history, or found to
	 * edit nothing a user would undo and saying so, lowered it - 17 on 2026-10-08, 0 measured on 2026-10-09 once
	 * the last (the raw archive browser) kept its writes; a new editor written without one raises the count past it.
	 */
	static final int OPEN_CEILING = 0;

	static int fails = 0;

	static void check(boolean cond, String what) {
		System.out.println((cond ? "  ok: " : "  FAIL: ") + what);
		if (!cond) {
			fails++;
		}
	}

	public static void main(String[] args) throws Exception {
		File classes = new File(args.length > 0 ? args[0] : "build/classes");
		File src = new File(args.length > 1 ? args[1] : "src");
		System.out.println("--- every editor keeps its edits in an undo history, or says why it cannot (F209d)");
		//never "0 editors found in nothing": both trees must be there to be read
		if (!new File(classes, HISTORY + ".class").isFile()) {
			check(false, "there is a compiled program to read - no " + HISTORY + ".class under " + classes);
			finish();
			return;
		}
		if (!new File(src, HISTORY + ".java").isFile()) {
			check(false, "there is a source tree to read class comments from - no " + HISTORY + ".java under " + src);
			finish();
			return;
		}
		reasonsAreRead();
		List<ClassFileScanner.ClassFile> all = ClassFileScanner.scan(classes);
		List<ClassFileScanner.ClassFile> app = ClassFileScanner.application(classes);
		Map<String, ClassFileScanner.ClassFile> byName = new HashMap<>();
		for (ClassFileScanner.ClassFile cf : app) {
			byName.put(cf.name, cf);
		}
		Set<String> ui = userFacing(app, byName);
		for (ClassFileScanner.ClassFile cf : app) {
			for (ClassFileScanner.Method m : cf.methods) {
				if (m.annotations.containsKey(WRITES_OUT)) {
					WOUT.put(cf.name + "#" + m.name + m.descriptor, m);
				}
			}
		}
		Map<String, Set<String>> editors = editors(app, byName, ui, key -> false);
		Map<String, List<String>> graph = new HashMap<>(CALLEES);
		Map<String, Set<String>> full = new HashMap<>(REACH);
		Set<String> keeps = keepers(app, byName);
		Map<String, Set<String>> named = suitesNaming(all);
		Set<String> undoing = undoingSuites(all);
		System.out.println("  suites that undo through a history: " + undoing.size() + " of " + named.size());
		Set<String> undone = new TreeSet<>();
		for (String suite : undoing) {
			undone.addAll(named.get(suite));
		}
		Set<String> watched = watched(app);
		Map<String, String> says = new TreeMap<>();
		for (String c : editors.keySet()) {
			String why = reason(source(src, c), simple(c));
			if (why != null) {
				says.put(c, why);
			}
		}
		//A METHOD IN A CLASS THAT SAYS WHY IT KEEPS NO HISTORY SAYS IT FOR ITS EDITS, and one a watched type declares,
		//or marked WritesOut, is that state writing itself out - the document the history holds: read again stopping
		//at those, what is left to each method is what it edits unkept. A method that hands a history its edits needs
		//no stop - every caller of one is one too, judged below by all it reaches; the stop that was here changed no
		//verdict, measured on 2026-10-09 by deleting it (the plant that did stayed green)
		unitKeepers(app);
		KEPT_AWARE = true;
		editors(app, byName, ui, key -> WOUT.containsKey(key)
				|| says.containsKey(outer(key.substring(0, key.indexOf('#')))) || watched.contains(key.substring(0, key.indexOf('#'))));
		KEPT_AWARE = false;
		Map<String, Set<String>> kept = new HashMap<>(REACH);
		keptRangesAreRead(byName, all);

		//KNOWN ANSWERS, each from a class whose edits were read by hand on 2026-10-08
		check(ui.contains("ctrmap/humaninterface/TileEditForm") && ui.contains("ctrmap/humaninterface/tools/SetTool")
				&& !ui.contains("ctrmap/WorkspaceUnit") && !ui.contains("ctrmap/formats/tilemap/Tilemap"),
				"control: a form and a drag tool are where the user meets the program, and the workspace's writer and"
				+ " a format are not");
		check(rafWrites(app, "ctrmap/formats/garc/GARC") && !rafWrites(app, "ctrmap/setup/DumpCheck"),
				"control: a RandomAccessFile opened \"rw\" is a write and one opened \"r\" to read a dump is not");
		check(hasSeed(editors, "ctrmap/humaninterface/ShopEditDialog", "writes "),
				"control: the shop editor is an editor by the file it writes, though it marks nothing");
		check(hasSeed(editors, "ctrmap/humaninterface/TileEditForm", "sets "),
				"control: the tile form is an editor by the tilemap it marks, though it writes no file");
		check(!editors.containsKey("ctrmap/humaninterface/LoadingDialog"),
				"control: the progress dialog edits nothing and is not an editor");
		check(watched.contains("ctrmap/formats/zone/ZoneEntities") && watched.contains("ctrmap/formats/tilemap/Tilemap")
				&& !watched.contains("ctrmap/humaninterface/CameraEditForm"),
				"control: the zone's entities and its tiles are held by history documents, and the camera form, where"
				+ " one of them lives, is not: " + watched);
		check(keeps.contains("ctrmap/humaninterface/CameraEditForm") && !keeps.contains("ctrmap/humaninterface/LoadingDialog"),
				"control: the camera form hands a history its edits, and the progress dialog holds none");
		entriesAreJudged();
		check(undone.contains("ctrmap/humaninterface/ExtrasPanel"),
				"control: the Extras panel is named by a suite that undoes through a history");
		check(named.getOrDefault("ctrmap/tests/DumpCheckTest", new TreeSet<>()).contains("ctrmap/setup/DumpCheck")
				&& !undone.contains("ctrmap/setup/DumpCheck"),
				"control: a class named only by suites that never undo - the dump checker - is not taken as undone through");
		Map<String, String> covered = new TreeMap<>();
		Map<String, String> declared = new TreeMap<>();
		Map<String, String> open = new TreeMap<>();
		for (String c : editors.keySet()) {
			if (says.containsKey(c)) {
				declared.put(c, says.get(c));
				continue;
			}
			Set<String> family = new TreeSet<>();
			for (String key : graph.keySet()) {
				if (outer(key.substring(0, key.indexOf('#'))).equals(c)) {
					family.add(key);
				}
			}
			//what each entry edits that no history takes: its own edits and its callees', each call followed only
			//where it is not inside a unit that is kept (F209d) - an entry that keeps one thing is not excused for
			//the rest. It LEANS on a history when it hands one an edit and its full reach writes
			Map<String, Set<String>> unkept = new TreeMap<>();
			boolean leans = false;
			for (String m : entries(family, graph)) {
				Set<String> reached = new TreeSet<>(OWN.getOrDefault(m, new TreeSet<>()));
				for (String callee : CALLEES.getOrDefault(m, java.util.Collections.<String>emptyList())) {
					reached.addAll(kept.getOrDefault(callee, new TreeSet<>()));
				}
				Set<String> unwatched = new TreeSet<>();
				for (String seed : reached) {
					if (!watchedSeed(seed, watched)) {
						unwatched.add(seed);
					}
				}
				unkept.put(m, unwatched);
				boolean writes = false;
				for (String seed : full.getOrDefault(m, new TreeSet<>())) {
					writes |= !watchedSeed(seed, watched);
				}
				leans |= HANDS.contains(m) && writes;
			}
			Set<String> keeping = new java.util.HashSet<>(WOUT.keySet());
			List<String> bad = unkeptEntries(unkept, keeping);
			if (bad.isEmpty() && (!leans || undone.contains(c))) {
				covered.put(c, leans ? "every entry that edits hands a history its edits, and a suite undoes through it"
						: "every edit it reaches is kept where it is made, or marks state a history document holds");
			} else if (bad.isEmpty()) {
				open.put(c, "hands a history its edits, and no suite undoes through it");
			} else {
				StringBuilder said = new StringBuilder(bad.size() + " entr" + (bad.size() == 1 ? "y" : "ies")
						+ " edit with no history:");
				for (String m : bad) {
					String first = unkept.get(m).iterator().next();
					String head = m.substring(0, m.indexOf('('));
					said.append("\n        ").append(head.substring(head.lastIndexOf('/') + 1)).append(": ")
							.append(unkept.get(m)).append(" - ").append(first).append(" by ").append(chain(m, first));
				}
				open.put(c, said.toString());
			}
		}
		check(covered.containsKey("ctrmap/humaninterface/WorkspaceSettings") && says.containsKey("ctrmap/Workspace"),
				"control: the settings dialog reaches the workspace's clean only through Workspace, which says why the"
				+ " clean keeps no history");
		check(covered.containsKey("ctrmap/humaninterface/CameraEditForm"),
				"control: the camera form's store writes out the camera data a history document holds, and is kept");
		System.out.println("  editors " + editors.size() + ": covered " + covered.size() + ", saying why not "
				+ declared.size() + ", open " + open.size());
		for (Map.Entry<String, String> e : covered.entrySet()) {
			System.out.println("    covered  " + simple(e.getKey()) + ": " + e.getValue());
		}
		for (Map.Entry<String, String> e : declared.entrySet()) {
			System.out.println("    no undo  " + simple(e.getKey()) + ": " + e.getValue());
		}
		for (Map.Entry<String, String> e : open.entrySet()) {
			System.out.println("    OPEN     " + simple(e.getKey()) + ": " + e.getValue());
		}
		check(editors.size() >= 30, "the derivation read the program's editors (" + editors.size() + ")");

		for (Map.Entry<String, String> e : declared.entrySet()) {
			check(e.getValue().length() >= REASON_MIN, simple(e.getKey()) + "'s No undo: gives a reason of "
					+ REASON_MIN + " characters or more (it gives " + e.getValue().length() + ": '" + e.getValue() + "')");
		}
		//A NO UNDO ON A CLASS THAT IS NOT AN UNCOVERED EDITOR IS STALE: it was written for an editor that has
		//since gained a history or stopped editing, and it would excuse whatever that class does next
		check(stale(new TreeSet<>(java.util.Arrays.asList("A", "B", "C")), java.util.Collections.singleton("A"))
				.equals(java.util.Arrays.asList("B", "C")),
				"control: a No undo: on a class that is not an editor saying why, covered or no editor at all, is stale");
		List<String> stale = stale(declaring(src), declared.keySet());
		check(stale.isEmpty(), "every No undo: is on an editor that keeps no history" + (stale.isEmpty() ? ""
				: " - these are covered, or edit nothing, so theirs excuse only what they do next: " + stale
						+ ". Delete it"));

		notAnEditIsNarrow(ui);
		writesOutIsNarrow(app);
		everyPackIsCounted(app);

		check(open.size() <= OPEN_CEILING, "no more editors keep no history and say nothing than the "
				+ OPEN_CEILING + " recorded (it is " + open.size() + ")"
				+ (open.size() <= OPEN_CEILING ? "" : " - give the new one a history, or say in its class comment"
						+ " 'No undo:' and why"));
		check(open.size() == OPEN_CEILING, "and the recorded number is the measured one - lower it when one gains a"
				+ " history (measured " + open.size() + ", recorded " + OPEN_CEILING + ")");
		finish();
	}

	static void finish() {
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	// ------------------------------------------------------------------ the derivation

	static String outer(String name) {
		int d = name.indexOf('$');
		return d < 0 ? name : name.substring(0, d);
	}

	static String simple(String name) {
		return name.startsWith("ctrmap/humaninterface/") ? name.substring("ctrmap/humaninterface/".length()) : name;
	}

	/**
	 * The outer classes where the user meets the program: one of the family is a Swing or AWT component, or
	 * has a method handed a user's event - a listener, a drag tool's mouse handler, a lambda on a button.
	 */
	static Set<String> userFacing(List<ClassFileScanner.ClassFile> app, Map<String, ClassFileScanner.ClassFile> byName) {
		Set<String> out = new TreeSet<>();
		for (ClassFileScanner.ClassFile cf : app) {
			for (String at = cf.superName; at != null;) {
				if (at.startsWith("java/awt/") || at.startsWith("javax/swing/")) {
					out.add(outer(cf.name));
					break;
				}
				ClassFileScanner.ClassFile up = byName.get(at);
				at = up == null ? null : up.superName;
			}
			for (ClassFileScanner.Method m : cf.methods) {
				String handed = m.descriptor.substring(1, m.descriptor.indexOf(')'));
				if (handed.contains("Ljava/awt/event/") || handed.contains("Ljavax/swing/event/")) {
					out.add(outer(cf.name));
				}
			}
		}
		return out;
	}

	/** Whether any method of a class opens a RandomAccessFile to write - the mode it loads says so. */
	static boolean rafWrites(List<ClassFileScanner.ClassFile> app, String name) {
		for (ClassFileScanner.ClassFile cf : app) {
			if (cf.name.equals(name)) {
				for (ClassFileScanner.Method m : cf.methods) {
					if (seeds(m).contains("writes java/io/RandomAccessFile.<init>")) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** The edits one method makes itself: raw file writes it calls, the marks an edit leaves. */
	static Set<String> seeds(ClassFileScanner.Method m) {
		return seeds(m, java.util.Collections.<Integer>emptySet());
	}

	/** The same, leaving out the calls at {@code keptAt}: they are made inside a unit that is kept. */
	static Set<String> seeds(ClassFileScanner.Method m, Set<Integer> keptAt) {
		Set<String> out = new TreeSet<>();
		for (Map.Entry<Integer, String> at : m.calls().entrySet()) {
			if (keptAt.contains(at.getKey())) {
				continue;
			}
			String call = at.getValue();
			int h = call.indexOf('#');
			String owner = call.substring(0, h);
			String name = call.substring(h + 1, call.indexOf('(', h));
			for (String[] w : WorkspaceUnitTest.RAW_WRITES) {
				if (w[0].equals(owner) && w[1].equals(name) && (!owner.equals("java/io/RandomAccessFile") || writingMode(m))) {
					out.add("writes " + owner + "." + name);
				}
			}
			if (name.equals("edited") || name.equals("storeFile")
					|| (owner.equals("ctrmap/Workspace") && name.equals("addPersist"))) {
				out.add("marks " + owner + "." + name);
			}
		}
		for (String f : m.writes) {
			String field = f.substring(f.indexOf('#') + 1);
			if ((field.equals("modified") || field.equals("dirty")) && !m.clears.contains(f)) {
				out.add("sets " + f);
			}
		}
		return out;
	}

	/** Whether a method loads a RandomAccessFile mode that writes: "rw", "rws" or "rwd". */
	static boolean writingMode(ClassFileScanner.Method m) {
		for (String s : m.loads) {
			if (s.equals("rw") || s.equals("rws") || s.equals("rwd")) {
				return true;
			}
		}
		return false;
	}

	/** Each outer class where the user meets the program whose methods reach an edit, with every edit they reach. */
	static Map<String, Set<String>> editors(List<ClassFileScanner.ClassFile> app,
			Map<String, ClassFileScanner.ClassFile> byName, Set<String> ui, java.util.function.Predicate<String> stop) {
		Map<String, Set<String>> reach = new HashMap<>();
		Map<String, List<String>> callees = CALLEES;
		for (ClassFileScanner.ClassFile cf : app) {
			for (ClassFileScanner.Method m : cf.methods) {
				String key = cf.name + "#" + m.name + m.descriptor;
				if (stop.test(key)) {
					//a method that keeps its edits, or one in a class that says why it keeps none
					reach.put(key, new TreeSet<>());
					OWN.put(key, new TreeSet<>());
					callees.put(key, new ArrayList<>());
					continue;
				}
				if (m.annotations.containsKey(NOT_AN_EDIT)) {
					//WHERE THE DERIVATION STOPS: it reaches nothing, and nothing it calls is followed
					CUT.put(key, m);
					reach.put(key, new TreeSet<>());
					OWN.put(key, new TreeSet<>());
					callees.put(key, new ArrayList<>());
					List<String> cut = new ArrayList<>();
					for (String call : m.invokes) {
						String target = resolve(call, byName);
						if (target != null) {
							cut.add(target);
						}
					}
					CUT_CALLEES.put(key, cut);
					continue;
				}
				//KEPT-AWARE (F209d): a call inside a unit that is kept reaches nothing unkept, so it is not followed
				Set<Integer> keptAt = KEPT_AWARE ? keptCalls(m) : java.util.Collections.<Integer>emptySet();
				reach.put(key, seeds(m, keptAt));
				OWN.put(key, seeds(m, keptAt));
				List<String> to = new ArrayList<>();
				for (Map.Entry<Integer, String> call : m.calls().entrySet()) {
					if (keptAt.contains(call.getKey())) {
						continue;
					}
					String target = resolve(call.getValue(), byName);
					if (target != null) {
						to.add(target);
					}
				}
				callees.put(key, to);
			}
		}
		for (boolean grew = true; grew;) {
			grew = false;
			for (Map.Entry<String, List<String>> e : callees.entrySet()) {
				Set<String> mine = reach.get(e.getKey());
				for (String t : e.getValue()) {
					Set<String> theirs = reach.get(t);
					if (theirs != null && theirs != mine && mine.addAll(theirs)) {
						grew = true;
					}
				}
			}
		}
		REACH.putAll(reach);
		Map<String, Set<String>> out = new TreeMap<>();
		for (ClassFileScanner.ClassFile cf : app) {
			String o = outer(cf.name);
			if (!ui.contains(o) || o.equals(HISTORY)) {
				continue;
			}
			for (ClassFileScanner.Method m : cf.methods) {
				Set<String> s = reach.get(cf.name + "#" + m.name + m.descriptor);
				if (!s.isEmpty()) {
					out.computeIfAbsent(o, k -> new TreeSet<>()).addAll(s);
				}
			}
		}
		return out;
	}

	/** Whether the second derivation follows only the calls made outside a unit that is kept. */
	static boolean KEPT_AWARE;
	/** The methods handed a unit that end it kept - "owner#name(desc)" - so a call to one ends a kept range. */
	static final Set<String> UNIT_KEEPERS = new java.util.HashSet<>();

	/** Fills {@link #UNIT_KEEPERS}: methods taking a WorkspaceUnit that call commitKeeping. */
	static void unitKeepers(List<ClassFileScanner.ClassFile> app) {
		for (ClassFileScanner.ClassFile cf : app) {
			for (ClassFileScanner.Method m : cf.methods) {
				if (!m.descriptor.contains("Lctrmap/WorkspaceUnit;")) {
					continue;
				}
				for (String call : m.invokes) {
					if (call.startsWith("ctrmap/WorkspaceUnit#commitKeeping(")) {
						UNIT_KEEPERS.add(cf.name + "#" + m.name + m.descriptor);
					}
				}
			}
		}
	}

	/**
	 * The offsets of {@code m}'s calls made inside a unit that is kept: after a WorkspaceUnit.begin and before the
	 * first commitKeeping, or call to a method that keeps the unit it is handed, after it - and every write through
	 * the history itself, which records what it writes.
	 */
	static Set<Integer> keptCalls(ClassFileScanner.Method m) {
		java.util.SortedMap<Integer, String> calls = m.calls();
		List<Integer> begins = new ArrayList<>();
		List<Integer> ends = new ArrayList<>();
		Set<Integer> out = new java.util.HashSet<>();
		for (Map.Entry<Integer, String> e : calls.entrySet()) {
			String c = e.getValue();
			if (c.startsWith("ctrmap/WorkspaceUnit#begin(")) {
				begins.add(e.getKey());
			} else if (c.startsWith("ctrmap/WorkspaceUnit#commitKeeping(") || UNIT_KEEPERS.contains(c)) {
				ends.add(e.getKey());
			} else if (c.startsWith(HISTORY + "#write(")) {
				out.add(e.getKey());
			}
		}
		for (int begin : begins) {
			for (int end : ends) {
				if (end > begin) {
					out.addAll(calls.subMap(begin + 1, end).keySet());
					break;
				}
			}
		}
		return out;
	}

	/**
	 * KNOWN ANSWERS for the kept ranges, from two methods read by hand on 2026-10-09: the workspace's clone calls the
	 * cloner inside a unit it keeps, and the area fork's own unit is committed and not kept, so its writes are not.
	 */
	static void keptRangesAreRead(Map<String, ClassFileScanner.ClassFile> byName, List<ClassFileScanner.ClassFile> all) {
		ClassFileScanner.Method fixture = null;
		for (ClassFileScanner.ClassFile cf : all) {
			if (cf.name.equals("ctrmap/tests/UndoCoverageTest")) {
				for (ClassFileScanner.Method m : cf.methods) {
					fixture = m.name.equals("keptRangeFixture") ? m : fixture;
				}
			}
		}
		List<String> marked = new ArrayList<>();
		if (fixture != null) {
			Set<Integer> at = keptCalls(fixture);
			for (Map.Entry<Integer, String> e : fixture.calls().entrySet()) {
				if (e.getValue().startsWith("java/lang/Integer#")) {
					marked.add(e.getValue().substring(e.getValue().indexOf('#') + 1, e.getValue().indexOf('('))
							+ (at.contains(e.getKey()) ? " kept" : " not kept"));
				}
			}
		}
		check(marked.equals(java.util.Arrays.asList("toHexString not kept", "toOctalString kept", "toBinaryString not kept")),
				"control: a call before a unit begins, and one after it is kept, are outside the kept range, and the one"
				+ " between is in it: " + marked);
		ClassFileScanner.Method clone = method(byName, "ctrmap/WorkspaceOps", "clone");
		ClassFileScanner.Method fork = method(byName, "ctrmap/AreaForker", "forkArea");
		boolean cloneKept = false;
		if (clone != null) {
			Set<Integer> at = keptCalls(clone);
			for (Map.Entry<Integer, String> e : clone.calls().entrySet()) {
				cloneKept |= e.getValue().startsWith("ctrmap/ZoneCloner#cloneIntoSlot(") && at.contains(e.getKey());
			}
		}
		check(cloneKept && fork != null && keptCalls(fork).isEmpty() && UNIT_KEEPERS.contains(
				"ctrmap/humaninterface/builder/Builder#kept(Lctrmap/WorkspaceUnit;)V"),
				"control: the clone's cloner call is inside a kept unit, the area fork's committed unit keeps nothing,"
				+ " and the raw browser's kept(unit) ends a kept unit");
	}

	/**
	 * EVERY PACK IS COUNTED (F209b): a change kept before a pack may be in the game after it, and the kept change asks
	 * the workspace's pack count whether one ran since - so whatever puts a packed archive into the game counts first.
	 * The raw browser's Add file packed its archive itself, counted by nothing. Read from the class files: every call
	 * outside the archive class that packs a directory into the game, or moves a packed archive over it, sits after
	 * the workspace's pack count is raised in the same method.
	 */
	static void everyPackIsCounted(List<ClassFileScanner.ClassFile> app) {
		List<String> sites = new ArrayList<>();
		List<String> uncounted = new ArrayList<>();
		for (ClassFileScanner.ClassFile cf : app) {
			if (cf.name.equals("ctrmap/formats/garc/GARC")) {
				continue;
			}
			for (ClassFileScanner.Method m : cf.methods) {
				boolean readsCount = m.reads.contains("ctrmap/WorkspaceSession#packs");
				int counted = Integer.MAX_VALUE;
				for (Map.Entry<Integer, String> e : m.calls().entrySet()) {
					String c = e.getValue();
					if (readsCount && c.startsWith("java/util/concurrent/atomic/AtomicInteger#incrementAndGet(")) {
						counted = Math.min(counted, e.getKey());
					} else if (c.startsWith("ctrmap/formats/garc/GARC#packDirectory(")
							|| c.startsWith("ctrmap/formats/garc/GARC#commitRepack(")) {
						String site = cf.name + "#" + m.name;
						if (!sites.contains(site)) {
							sites.add(site);
						}
						if (e.getKey() < counted && !uncounted.contains(site)) {
							uncounted.add(site);
						}
					}
				}
			}
		}
		check(sites.contains("ctrmap/WorkspaceSession#packOne") && sites.contains("ctrmap/WorkspaceSession#packArchives"),
				"control: the one-archive pack and the workspace's pack are read as putting archives into the game: " + sites);
		check(uncounted.isEmpty(), "every place that puts a packed archive into the game counts the pack first"
				+ (uncounted.isEmpty() ? " (" + sites.size() + ")" : " - not " + uncounted));
	}

	/**
	 * KNOWN ANSWER for {@link #keptRangesAreRead}, never called - read from this class's file: one marker call before
	 * the unit, one inside it, one after it is kept.
	 */
	static void keptRangeFixture(ctrmap.WorkspaceSession ws) {
		Integer.toHexString(1);
		try (ctrmap.WorkspaceUnit unit = ctrmap.WorkspaceUnit.begin("the kept range's known answer", ws)) {
			Integer.toOctalString(2);
			unit.commitKeeping();
		}
		Integer.toBinaryString(3);
	}

	/** The one method {@code name} of class {@code cls}, or null. */
	static ClassFileScanner.Method method(Map<String, ClassFileScanner.ClassFile> byName, String cls, String name) {
		ClassFileScanner.ClassFile cf = byName.get(cls);
		if (cf == null) {
			return null;
		}
		for (ClassFileScanner.Method m : cf.methods) {
			if (m.name.equals(name)) {
				return m;
			}
		}
		return null;
	}

	/** The methods marked WritesOut, by key. */
	static final Map<String, ClassFileScanner.Method> WOUT = new TreeMap<>();

	/** The methods that hand a history an edit, themselves or through what they call - keepers() reads them. */
	static final Set<String> HANDS = new java.util.HashSet<>();

	/** The entries of a class: its methods nothing else in it calls - handlers, lambdas, what other classes call. */
	static Set<String> entries(Set<String> family, Map<String, List<String>> graph) {
		Set<String> called = new java.util.HashSet<>();
		for (String m : family) {
			for (String callee : graph.getOrDefault(m, new ArrayList<>())) {
				if (!callee.equals(m)) {
					called.add(callee);
				}
			}
		}
		Set<String> out = new TreeSet<>(family);
		out.removeAll(called);
		return out;
	}

	/** The entries that reach an edit no history takes and hand no history anything themselves. */
	static List<String> unkeptEntries(Map<String, Set<String>> unkept, Set<String> hands) {
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, Set<String>> e : unkept.entrySet()) {
			if (!e.getValue().isEmpty() && !hands.contains(e.getKey())) {
				out.add(e.getKey());
			}
		}
		return out;
	}

	/** KNOWN ANSWERS for the entry judgement, on a class written here. */
	static void entriesAreJudged() {
		Map<String, List<String>> graph = new HashMap<>();
		graph.put("V#save()V", java.util.Arrays.asList("V#write()V"));
		graph.put("V#write()V", new ArrayList<String>());
		graph.put("V#lambda$0()V", java.util.Arrays.asList("V#save()V"));
		graph.put("V#lambda$1()V", java.util.Arrays.asList("V#write()V"));
		check(entries(graph.keySet(), graph).equals(new TreeSet<>(java.util.Arrays.asList("V#lambda$0()V", "V#lambda$1()V"))),
				"control: a class's entries are the methods nothing else in it calls - its two buttons, not their save");
		Map<String, Set<String>> unkept = new TreeMap<>();
		unkept.put("V#lambda$0()V", java.util.Collections.singleton("writes java/nio/file/Files.write"));
		unkept.put("V#lambda$1()V", java.util.Collections.singleton("writes java/nio/file/Files.write"));
		check(unkeptEntries(unkept, java.util.Collections.singleton("V#lambda$0()V"))
				.equals(java.util.Collections.singletonList("V#lambda$1()V")),
				"control: of two buttons that save, the one whose save no history takes is named, though the other's is"
				+ " kept");
	}

	/** Each method's resolved callees and its own edits, as editors() read them - for chain(). */
	static final Map<String, List<String>> CALLEES = new HashMap<>();
	static final Map<String, Set<String>> OWN = new HashMap<>();
	/** The methods marked NotAnEdit, by key; what each calls; and every method's reach once settled. */
	static final Map<String, ClassFileScanner.Method> CUT = new TreeMap<>();
	static final Map<String, List<String>> CUT_CALLEES = new HashMap<>();
	static final Map<String, Set<String>> REACH = new HashMap<>();

	/**
	 * The shortest chain of calls from {@code start} - one method's key, or every method of a class - to one
	 * that makes {@code seed} itself, as "Class.method > Class.method", or null when there is none.
	 */
	static String chain(String start, String seed) {
		Map<String, String> from = new HashMap<>();
		java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>();
		for (String key : new TreeSet<>(CALLEES.keySet())) {
			if (start.contains("#") ? key.equals(start) : outer(key.substring(0, key.indexOf('#'))).equals(start)) {
				from.put(key, null);
				todo.add(key);
			}
		}
		while (!todo.isEmpty()) {
			String at = todo.poll();
			if (OWN.get(at).contains(seed)) {
				List<String> path = new ArrayList<>();
				for (String step = at; step != null; step = from.get(step)) {
					String c = step.substring(0, step.indexOf('#'));
					path.add(0, c.substring(c.lastIndexOf('/') + 1) + "." + step.substring(step.indexOf('#') + 1, step.indexOf('(')));
				}
				return String.join(" > ", path);
			}
			for (String next : CALLEES.get(at)) {
				if (CALLEES.containsKey(next) && !from.containsKey(next)) {
					from.put(next, at);
					todo.add(next);
				}
			}
		}
		return null;
	}

	/** A call resolved to the class that declares it, up the superclass chain; null outside the program. */
	static String resolve(String call, Map<String, ClassFileScanner.ClassFile> byName) {
		int h = call.indexOf('#');
		String sig = call.substring(h + 1);
		for (String at = call.substring(0, h); at != null;) {
			ClassFileScanner.ClassFile cf = byName.get(at);
			if (cf == null) {
				return null;
			}
			for (ClassFileScanner.Method m : cf.methods) {
				if ((m.name + m.descriptor).equals(sig)) {
					return at + "#" + sig;
				}
			}
			at = cf.superName;
		}
		return null;
	}

	static boolean hasSeed(Map<String, Set<String>> editors, String c, String kind) {
		for (String s : editors.getOrDefault(c, new TreeSet<>())) {
			if (s.startsWith(kind)) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ coverage

	/**
	 * The outer classes that hand a history its edits: a method that builds one over documents, adds to it,
	 * writes through it or has it watch more - itself, or through what it calls, so an editor keeping its
	 * saves through a helper counts, and a class that only asks a history whether it can undo, or holds a
	 * field of its type and never uses it, does not.
	 */
	static Set<String> keepers(List<ClassFileScanner.ClassFile> app, Map<String, ClassFileScanner.ClassFile> byName) {
		Map<String, List<String>> callees = new HashMap<>();
		Set<String> hands = new java.util.HashSet<>();
		for (ClassFileScanner.ClassFile cf : app) {
			for (ClassFileScanner.Method m : cf.methods) {
				String key = cf.name + "#" + m.name + m.descriptor;
				List<String> to = new ArrayList<>();
				for (String call : m.invokes) {
					if (handsHistory(call)) {
						hands.add(key);
					}
					String target = resolve(call, byName);
					if (target != null) {
						to.add(target);
					}
				}
				callees.put(key, to);
			}
		}
		for (boolean grew = true; grew;) {
			grew = false;
			for (Map.Entry<String, List<String>> e : callees.entrySet()) {
				if (hands.contains(e.getKey())) {
					continue;
				}
				for (String t : e.getValue()) {
					if (hands.contains(t)) {
						hands.add(e.getKey());
						grew = true;
						break;
					}
				}
			}
		}
		Set<String> out = new TreeSet<>();
		for (String key : hands) {
			out.add(outer(key.substring(0, key.indexOf('#'))));
		}
		HANDS.clear();
		HANDS.addAll(hands);
		out.remove(HISTORY);
		return out;
	}

	/** Whether a call hands a history an edit: builds one, adds to it, writes through it, has it watch more. */
	static boolean handsHistory(String call) {
		for (String how : new String[]{"#<init>(", "#add(", "#write(", "#watch("}) {
			if (call.startsWith(HISTORY + how)) {
				return true;
			}
		}
		return false;
	}

	/** Every class each suite names - a class constant, a type in a descriptor, a member's owner - by suite. */
	static Map<String, Set<String>> suitesNaming(List<ClassFileScanner.ClassFile> all) {
		Map<String, Set<String>> named = new TreeMap<>();
		for (ClassFileScanner.ClassFile cf : all) {
			if (!cf.isTest()) {
				continue;
			}
			Set<String> mine = named.computeIfAbsent(outer(cf.name), k -> new TreeSet<>());
			for (String c : cf.classes) {
				mine.add(outer(c));
			}
			for (ClassFileScanner.Ref r : cf.refs) {
				mine.add(outer(r.owner));
			}
		}
		return named;
	}

	/** The suites whose code calls a history's undo. */
	static Set<String> undoingSuites(List<ClassFileScanner.ClassFile> all) {
		Set<String> undoing = new TreeSet<>();
		for (ClassFileScanner.ClassFile cf : all) {
			for (ClassFileScanner.Ref r : cf.isTest() ? cf.refs : new ArrayList<ClassFileScanner.Ref>()) {
				if (r.method && r.owner.equals(HISTORY) && r.name.equals("undo")) {
					undoing.add(outer(cf.name));
				}
			}
		}
		return undoing;
	}

	/** The program's types a history document holds: the state the history reads around every gesture. */
	static Set<String> watched(List<ClassFileScanner.ClassFile> app) {
		Set<String> out = new TreeSet<>();
		for (ClassFileScanner.ClassFile cf : app) {
			if (!cf.interfaces.contains(DOCUMENT)) {
				continue;
			}
			for (ClassFileScanner.Field f : cf.declaredFields) {
				//this$0 is where a nested document lives, not what it holds
				if (f.descriptor.startsWith("Lctrmap/") && !f.name.startsWith("this$")) {
					out.add(f.descriptor.substring(1, f.descriptor.length() - 1));
				}
			}
		}
		return out;
	}

	/** Whether an edit marks state a history document holds - a flag set on it, or its edited() called. */
	static boolean watchedSeed(String seed, Set<String> watched) {
		String owner;
		if (seed.startsWith("sets ")) {
			owner = seed.substring("sets ".length(), seed.indexOf('#'));
		} else if (seed.startsWith("marks ") && seed.endsWith(".edited")) {
			owner = seed.substring("marks ".length(), seed.lastIndexOf('.'));
		} else {
			return false;
		}
		return watched.contains(owner);
	}

	/**
	 * Every NotAnEdit stops the derivation at a write that changes nothing the user made, and only there: it
	 * cuts raw writes, never the mark an edit leaves; it is never in a class the user drives, where the edit
	 * is made; it gives a reason; and it cuts something - one on a method that writes nothing excuses a write
	 * added to it later.
	 */
	static void notAnEditIsNarrow(Set<String> ui) {
		String reason = "it writes bytes already read into a scratch file nothing the user made is kept in.";
		Set<String> raw = java.util.Collections.singleton("writes java/nio/file/Files.write");
		Set<String> marking = new TreeSet<>(java.util.Arrays.asList("writes java/nio/file/Files.write",
				"marks ctrmap/Workspace.addPersist"));
		check(notAnEditProblems(reason, raw, false, true).isEmpty(),
				"control: a NotAnEdit with a reason, cutting a raw write outside the classes the user drives, stands");
		check(notAnEditProblems(reason, marking, false, true).equals(java.util.Collections.singletonList("marks")),
				"control: a NotAnEdit on a method that marks an edit is refused");
		check(notAnEditProblems(reason, raw, true, true).equals(java.util.Collections.singletonList("user-facing")),
				"control: a NotAnEdit in a class the user drives is refused");
		check(notAnEditProblems("scratch.", raw, false, true).equals(java.util.Collections.singletonList("reason")),
				"control: a NotAnEdit whose reason is a word is refused");
		check(notAnEditProblems(reason, new TreeSet<>(), false, false).equals(java.util.Collections.singletonList("cuts nothing")),
				"control: a NotAnEdit on a method that reaches no write is refused");
		System.out.println("  methods marked NotAnEdit: " + CUT.size());
		check(CUT.containsKey("ctrmap/WorkspaceSession#getWorkspaceFile(Lctrmap/gamedef/ArchiveType;I)Ljava/io/File;"),
				"control: the scanner reads the mark on the workspace's extraction of a dump entry: " + CUT.keySet());
		for (Map.Entry<String, ClassFileScanner.Method> e : CUT.entrySet()) {
			ClassFileScanner.Method m = e.getValue();
			String where = simple(m.owner) + "." + m.name;
			String why = m.annotations.get(NOT_AN_EDIT);
			boolean writes = false;
			for (String callee : CUT_CALLEES.get(e.getKey())) {
				writes |= REACH.containsKey(callee) && !REACH.get(callee).isEmpty();
			}
			List<String> problems = notAnEditProblems(why, seeds(m), ui.contains(outer(m.owner)), writes);
			System.out.println("    " + where + ": " + why);
			check(problems.isEmpty(), where + "'s NotAnEdit gives a reason, cuts a raw write and nothing an edit marks,"
					+ " and is not in a class the user drives" + (problems.isEmpty() ? "" : " - it fails: " + problems));
		}
	}

	/**
	 * What is wrong with one NotAnEdit: "reason" when its reason is shorter than {@value #REASON_MIN}; "marks"
	 * when the method itself leaves the mark an edit leaves; "user-facing" when it is in a class the user
	 * drives; "cuts nothing" when neither it nor anything it calls reaches a write.
	 */
	static List<String> notAnEditProblems(String why, Set<String> own, boolean userFacing, boolean calleesWrite) {
		List<String> out = new ArrayList<>();
		if (why.length() < REASON_MIN) {
			out.add("reason");
		}
		for (String s : own) {
			if (!s.startsWith("writes ")) {
				out.add("marks");
				break;
			}
		}
		if (userFacing) {
			out.add("user-facing");
		}
		if (own.isEmpty() && !calleesWrite) {
			out.add("cuts nothing");
		}
		return out;
	}

	/**
	 * Every WritesOut is in a class whose history watches documents - a method of it builds a history over
	 * documents or has one watch more - and says which in more than a word: in a class watching none there is
	 * nothing it could be writing out.
	 */
	static void writesOutIsNarrow(List<ClassFileScanner.ClassFile> app) {
		Set<String> watching = new TreeSet<>();
		for (ClassFileScanner.ClassFile cf : app) {
			for (ClassFileScanner.Method m : cf.methods) {
				for (String call : m.invokes) {
					if (call.startsWith(HISTORY + "#watch(") || call.startsWith(HISTORY + "#<init>(")) {
						watching.add(outer(cf.name));
					}
				}
			}
		}
		check(watching.contains("ctrmap/humaninterface/CameraEditForm") && !watching.contains("ctrmap/humaninterface/ShopEditDialog"),
				"control: the camera form's history watches a document, and the shop editor watches none");
		String what = "the zone's header boxes, a document of the zone's history";
		check(writesOutProblems(what, true).isEmpty() && writesOutProblems(what, false).equals(java.util.Collections.singletonList("watches nothing"))
				&& writesOutProblems("header", true).equals(java.util.Collections.singletonList("a word")),
				"control: a WritesOut in a class watching no document, or saying which in a word, is refused");
		System.out.println("  methods marked WritesOut: " + WOUT.size());
		for (Map.Entry<String, ClassFileScanner.Method> e : WOUT.entrySet()) {
			ClassFileScanner.Method m = e.getValue();
			String where = simple(m.owner) + "." + m.name;
			List<String> problems = writesOutProblems(m.annotations.get(WRITES_OUT), watching.contains(outer(m.owner)));
			System.out.println("    " + where + ": " + m.annotations.get(WRITES_OUT));
			check(problems.isEmpty(), where + "'s WritesOut is in a class whose history watches documents, and says which"
					+ (problems.isEmpty() ? "" : " - it fails: " + problems));
		}
	}

	/** What is wrong with one WritesOut: "watches nothing", "a word" (under 30 characters). */
	static List<String> writesOutProblems(String what, boolean familyWatches) {
		List<String> out = new ArrayList<>();
		if (!familyWatches) {
			out.add("watches nothing");
		}
		if (what.length() < 30) {
			out.add("a word");
		}
		return out;
	}

	/** The classes whose source says No undo: that are not editors saying why - covered, or no editor at all. */
	static List<String> stale(Set<String> declaring, Set<String> declared) {
		List<String> out = new ArrayList<>();
		for (String c : declaring) {
			if (!declared.contains(c)) {
				out.add(c);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ the reasons

	static String source(File src, String outer) throws IOException {
		File f = new File(src, outer + ".java");
		return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "";
	}

	/**
	 * The reason a class's own comment gives for keeping no history - the words after {@code No undo:} up to
	 * the paragraph's end - or null when it gives none. Only the comment directly above the class counts:
	 * one on a method, or a line comment, excuses nothing.
	 */
	static String reason(String source, String simpleName) {
		String name = simpleName.substring(simpleName.lastIndexOf('/') + 1);
		Matcher decl = Pattern.compile("(?m)^(?:public |final |abstract )*(?:class|interface|enum) "
				+ Pattern.quote(name) + "\\b").matcher(source);
		if (!decl.find()) {
			return null;
		}
		String before = source.substring(0, decl.start());
		int open = before.lastIndexOf("/**");
		int close = before.lastIndexOf("*/");
		if (open < 0 || close < open || !before.substring(close + 2).replaceAll("@\\w+(\\([^)]*\\))?", "").trim().isEmpty()) {
			return null;
		}
		String doc = before.substring(open + 3, close);
		int at = doc.indexOf("No undo:");
		if (at < 0) {
			return null;
		}
		StringBuilder out = new StringBuilder();
		for (String line : doc.substring(at + "No undo:".length()).split("\n")) {
			String text = line.replaceFirst("^\\s*\\*\\s?", "").trim();
			if (text.isEmpty() || text.startsWith("<p>") || text.startsWith("@")) {
				break;
			}
			out.append(out.length() == 0 ? "" : " ").append(text);
		}
		return out.toString().replaceAll("\\{@(?:link|code) ([^}]*)\\}", "$1").replace("#", "").trim();
	}

	/** Every class whose source says No undo: anywhere - read off the tree, not from the derivation. */
	static Set<String> declaring(File src) throws IOException {
		Set<String> out = new TreeSet<>();
		List<File> todo = new ArrayList<>();
		todo.add(src);
		while (!todo.isEmpty()) {
			File[] kids = todo.remove(todo.size() - 1).listFiles();
			for (File f : kids == null ? new File[0] : kids) {
				if (f.isDirectory()) {
					todo.add(f);
				} else if (f.getName().endsWith(".java")
						&& new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).contains("No undo:")) {
					String rel = src.toURI().relativize(f.toURI()).getPath();
					out.add(rel.substring(0, rel.length() - ".java".length()));
				}
			}
		}
		out.remove("ctrmap/tests/UndoCoverageTest");
		return out;
	}

	/** KNOWN ANSWERS for the reason reader, on sources written here. */
	static void reasonsAreRead() {
		String reason = "it shows the game's data and every change it makes is written by the editor it opens.";
		String good = "/**\n * A viewer.\n *\n * <p>No undo: " + reason + "\n *\n * <p>More.\n */\npublic class V {\n}\n";
		check(reason.equals(reason(good, "V")), "control: a No undo: in the class comment is read to its paragraph's end: '"
				+ reason(good, "V") + "'");
		String onMethod = "/** A viewer. */\npublic class V {\n\t/** No undo: " + reason + " */\n\tvoid m() {\n\t}\n}\n";
		String lineComment = "//No undo: " + reason + "\npublic class V {\n}\n";
		String elsewhere = "/** No undo: " + reason + " */\nclass W {\n}\n/** A viewer. */\npublic class V {\n}\n";
		String uncommented = "/** No undo: " + reason + " */\nclass W {\n}\npublic class V {\n}\n";
		check(reason(onMethod, "V") == null && reason(lineComment, "V") == null && reason(elsewhere, "V") == null
				&& reason(uncommented, "V") == null,
				"control: a No undo: on a method, in a line comment or above another class excuses nothing");
		check(reason("/** No undo: short. */\n@SuppressWarnings(\"serial\")\npublic final class V {\n}\n", "V")
				.equals("short."), "control: an annotation between the comment and the class does not hide it");
	}
}
