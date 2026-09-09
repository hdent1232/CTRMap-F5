package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.LoadedZone;
import ctrmap.Ui;
import ctrmap.Workspace;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.zone.Zone;
import ctrmap.gamedef.ArchiveType;
import ctrmap.humaninterface.ExtrasPanel;
import ctrmap.humaninterface.GeoEditForm;
import ctrmap.humaninterface.MatrixEditForm;
import ctrmap.humaninterface.NPCEditForm;
import ctrmap.humaninterface.PaintForm;
import ctrmap.humaninterface.PropEditForm;
import ctrmap.humaninterface.TileMapPanel;
import ctrmap.humaninterface.TriggerEditForm;
import ctrmap.humaninterface.WarpEditForm;
import ctrmap.humaninterface.ZoneLoadingPanel;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The loaded zone has ONE owner, every reader is handed it, and no class can
 * reach it through the main window any more.
 *
 * <p>WHY THIS SUITE EXISTS. The zone table, the open zone and its index were
 * three PUBLIC MUTABLE fields of {@link ZoneLoadingPanel}, written from inside
 * the panel, from the map view and from three suites, and read by fourteen
 * classes through {@code CtrmapMainframe.mZonePnl} - the main window's static.
 * Not one of those readers could be pointed at a different zone: there was
 * only ever the one panel to read, so "open zone 15 and check what the form
 * shows" meant opening zone 15 in the real editor. {@link LoadedZone} is that
 * state with an owner: private fields, a method per write named for what
 * happened, and a table that is never handed out, so {@code zones[2] = x}
 * from outside cannot happen.
 *
 * <p>The property asserted is the owner's, and it has two halves. HANDED: every
 * reader takes a {@link LoadedZone} and keeps it in a private final field, has
 * no constructor that does without one, and answers from the one it was given -
 * proven by building two readers over two owners and reading two different
 * answers out of them. NOT FETCHED: the bytecode says no class outside the
 * window makes an owner of its own, and the classes that still name the Zone
 * tab through the window's static are named here one by one, with what each
 * uses it for - none of them zone state. Both halves are needed: a reader
 * handed an owner that quietly kept reading the static would pass the first
 * and fail the second.
 *
 * <p>Read from {@code build/classes} rather than from the source, because a
 * class can reach a static through {@code import static ctrmap.CtrmapMainframe.*}
 * with a bare name that no source regex matches - thirty-six files in this tree
 * do exactly that.
 *
 * <p>ORDER: the reflection and bytecode sections need no game and reset
 * {@link Workspace} around themselves; the rest opens a scratch copy of the
 * dump ({@link ScratchGame}) because the write meanings and the two-owner
 * proof want real zones. Writes only under {@link Scratch}.
 *
 * Usage: java ctrmap.tests.LoadedZoneTest &lt;pristine dump root&gt; [classes-root]
 *        (classes-root defaults to "build/classes")
 */
public class LoadedZoneTest {

	/** The 3D gizmo these forms move, so what they told it can be read back. */
	static final RecordingNavi NAVI = new RecordingNavi();
	/** The editors that show the zone, for the panels here: a spy that records and clears. */
	static final ZoneEditorsSpy ZONE_EDITORS = new ZoneEditorsSpy();

	/** The editor set the panels here flush: it records instead of saving. */
	static final RecordingEditors EDITORS = new RecordingEditors();

	/** The tool this suite holds: its own, so another suite may hold another. */
	static final ctrmap.humaninterface.tools.ToolSelection TOOLS = new ctrmap.humaninterface.tools.ToolSelection();


	/** A zone whose map matrix is shared with others in the retail game. */
	private static final int SHARED_ZONE = 10;
	/** A zone whose map matrix is its own. */
	private static final int PRIVATE_ZONE = 15;

	private static final String OWNER = "ctrmap/LoadedZone";
	private static final String WINDOW = "ctrmap/CtrmapMainframe";
	private static final String ZONE_TAB = "mZonePnl";

	/**
	 * The classes that KEEP an owner: handed one, held in a private final
	 * field, no constructor without one. Ten of them, which is every class
	 * that used to read the panel's three public fields for itself.
	 */
	private static final String[] KEEPERS = {
		"ctrmap.humaninterface.ZoneLoadingPanel",
		"ctrmap.humaninterface.TileMapPanel",
		"ctrmap.humaninterface.MatrixEditForm",
		"ctrmap.humaninterface.NPCEditForm",
		"ctrmap.humaninterface.WarpEditForm",
		"ctrmap.humaninterface.TriggerEditForm",
		"ctrmap.humaninterface.PropEditForm",
		"ctrmap.humaninterface.GeoEditForm",
		"ctrmap.humaninterface.PaintForm",
		"ctrmap.humaninterface.ExtrasPanel"
	};

	/**
	 * The classes handed an owner AT THE CALL and keeping none: a dialog or a
	 * static helper that runs once against the zone that is open when it is
	 * asked. Holding one would be worse, not better - it would outlive the
	 * dialog.
	 */
	private static final String[] PASSERS = {
		"ctrmap.humaninterface.AreaForkPrompt",
		"ctrmap.humaninterface.AreaLightingDialog",
		"ctrmap.humaninterface.EncounterEditDialog",
		"ctrmap.humaninterface.GfEnvPicker",
		"ctrmap.humaninterface.TilePainterForm"
	};

	/**
	 * Application classes that still name the Zone tab through the window's
	 * static, with what each one asks it for. NONE of them is zone state -
	 * every one is an operation the panel performs, which is step F's work
	 * (the panel itself gets an owner and these are handed it):
	 * <ul>
	 * <li>{@code NPCEditForm}, {@code ScriptEditor} - {@code store(...)}, the
	 * Zone tab's save, run as part of a bigger save. The map painter was here
	 * too until the flush got an owner; it asks that instead.
	 * <li>{@code TilePainterForm} - {@code loadEverything}/{@code selectZone}
	 * after it has written new zones, and {@code clearForkDecline}.
	 * <li>{@code SetupWizard} - {@code getLoadedZoneCount}, to say how many
	 * zones the game it just set up has.
	 * </ul>
	 * Measured from {@code build/classes}, folded to top-level classes, the
	 * window itself excluded. Asserted with EQUALITY: this set only shrinks,
	 * and a new name in it is a class reaching the Zone tab through a global
	 * rather than being handed what it needs.
	 */
	private static final String[] STATIC_READERS = {};

	/**
	 * Every field in the program that HOLDS a zone, measured over
	 * {@code build/classes} as "declared type is Zone or Zone[]". The owner's
	 * two, and one more: {@code ZoneScriptEdit} keeps the zone ONE edit is
	 * against, private and final, handed in at construction and dropped with
	 * the edit - a subject, not a copy of what is open. The three public
	 * mutable fields this step removed would appear here, which is why the
	 * rule is equality over the whole set rather than a count.
	 */
	private static final String[] ZONE_KEEPERS = {
		"ctrmap/LoadedZone private Zone[] table",
		"ctrmap/LoadedZone private Zone open",
		"ctrmap/formats/scripts/ZoneScriptEdit private final Zone zone"
	};

	/**
	 * The one application class allowed to MAKE an owner: the window, which
	 * makes exactly one in {@code createAndShowGUI} and hands it to the panels
	 * and forms it builds. A second maker would be a second loaded zone - two
	 * halves of the editor believing different things are open - which is the
	 * failure this owner exists to make impossible. Suites make their own, as
	 * they must to hand two.
	 */
	private static final String[] MAKERS = {"ctrmap/CtrmapMainframe"};

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		File classes = new File(args.length > 1 ? args[1] : "build/classes");

		Workspace.reset();
		try {
			aFreshOwnerIsClosed();
			theTableIsCopiedAndTheSlotsAreNeverHandedOut();
			whatEachWriteMeans();
			everyReaderDeclaresThatItIsHandedOne();
			theOwnerIsMadeInOnePlace(classes);
			theZoneStateIsNoLongerReachableThroughTheWindow(classes);
			nobodyElseKeepsTheLoadedZone(classes);
		} finally {
			Workspace.reset();
		}

		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump
					+ " - the two-owner proof needs real zones to hand two readers");
		} else {
			ScratchGame.open(dump);
			twoPanelsOverTwoOwnersAnswerDifferently();
			theWindowAnswersFromTheOwnerItWasBound();
			everyReaderHoldsTheOwnerItWasHanded();
		}

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	// ------------------------------------------------------------ 1. semantics

	static void aFreshOwnerIsClosed() {
		System.out.println("--- an owner that has never been given a zone");
		LoadedZone lz = new LoadedZone();
		check(lz.count() == 0, "holds no zone table (" + lz.count() + ")");
		check(lz.open() == null, "has nothing open");
		check(!lz.isOpen(), "and says so");
		check(lz.index() == -1, "and the index is -1, not 0 - nothing is open (" + lz.index() + ")");
		String refusal = refusedBy(() -> lz.at(0));
		check(refusal.contains("0") && refusal.contains("holds 0"),
				"asking for a slot of an empty table refuses NAMING the slot and the count: " + refusal);
	}

	static void theTableIsCopiedAndTheSlotsAreNeverHandedOut() {
		System.out.println("--- the table is copied in, and no caller keeps a handle on it");
		LoadedZone lz = new LoadedZone();
		Zone[] mine = new Zone[3];
		lz.table(mine);
		check(lz.count() == 3, "the table is as long as the array handed in (" + lz.count() + ")");
		//the caller's array is theirs: writing to it afterwards must not reach the owner
		mine[0] = null;
		check(lz.at(0) == null && lz.count() == 3, "and slot 0 is what the owner holds, not what the caller does");
		String refusal = refusedBy(() -> lz.at(3));
		check(refusal.contains("3") && refusal.contains("holds 3"),
				"a slot past the end refuses naming it and the count: " + refusal);
		refusal = refusedBy(() -> lz.at(-1));
		check(refusal.contains("-1"), "and so does a negative one: " + refusal);
		refusal = refusedBy(() -> lz.table(null));
		check(refusal.contains("empty table"),
				"and a null table is refused with what to hand instead: " + refusal);
		refusal = refusedBy(() -> lz.replace(9, null));
		check(refusal.contains("9"), "replacing a slot the table does not have refuses too: " + refusal);
	}

	/**
	 * The four writes, each pinned to what the panel's three public fields did
	 * before this owner existed, because ZoneLoadingStateTest pins the panel's
	 * behaviour and would go red for any of them.
	 */
	static void whatEachWriteMeans() {
		System.out.println("--- what each write means, pinned to what the three public fields did");
		LoadedZone lz = new LoadedZone();
		Zone[] table = new Zone[4];
		lz.table(table);
		lz.open(2, null);
		check(lz.index() == 2 && lz.open() == null,
				"an index can be set with nothing open - which is the panel between loading and recording");

		//a rebuild: the open zone belonged to the old table, the index is kept
		lz.open(2, null);
		lz.table(new Zone[6]);
		check(lz.count() == 6, "a rebuild replaces the table");
		check(lz.open() == null, "and lets go of whatever was open");
		check(lz.index() == 2, "and PINNED AS IS: does NOT reset the index (" + lz.index() + ")");

		//release vs close: the map view's stale-state clear against the panel's unload
		lz.close();
		check(lz.index() == -1 && lz.open() == null, "close is 'nothing is open' and the index says so");
		lz.open(3, null);
		lz.release();
		check(lz.index() == 3, "release lets the zone go and KEEPS the index (" + lz.index() + ")");

		String refusal = refusedBy(() -> lz.open(-2, null));
		check(refusal.contains("-2") && refusal.contains("-1"),
				"an index below -1 is refused, saying what -1 means: " + refusal);
		refusal = refusedBy(() -> lz.open(1));
		check(refusal.contains("1") && refusal.toLowerCase().contains("no zone"),
				"and opening a slot the rebuild could not fill refuses rather than opening null: " + refusal);
	}

	// -------------------------------------------------- 2. handed, by declaration

	/**
	 * Reflection over the classes themselves, so a reader that grew a second
	 * constructor - the way back to "make your own" - is caught even if no
	 * call site uses it yet.
	 */
	static void everyReaderDeclaresThatItIsHandedOne() throws Exception {
		System.out.println("--- every reader declares that it is handed an owner");
		for (String name : KEEPERS) {
			Class<?> c = Class.forName(name);
			List<Field> mine = new ArrayList<>();
			for (Field f : c.getDeclaredFields()) {
				if (f.getType() == LoadedZone.class) {
					mine.add(f);
				}
			}
			check(mine.size() == 1, simple(name) + " keeps exactly one owner (" + mine.size() + ")");
			for (Field f : mine) {
				check(Modifier.isPrivate(f.getModifiers()) && Modifier.isFinal(f.getModifiers())
						&& !Modifier.isStatic(f.getModifiers()),
						simple(name) + "'s owner field is private, final and per-instance ("
						+ Modifier.toString(f.getModifiers()) + ")");
			}
			int handed = 0;
			for (Constructor<?> ctor : c.getDeclaredConstructors()) {
				if (Arrays.asList(ctor.getParameterTypes()).contains(LoadedZone.class)) {
					handed++;
				}
			}
			check(handed == c.getDeclaredConstructors().length && handed > 0,
					simple(name) + " has no constructor that does without one (" + handed
					+ " of " + c.getDeclaredConstructors().length + ")");
		}
		for (String name : PASSERS) {
			Class<?> c = Class.forName(name);
			int fields = 0;
			for (Field f : c.getDeclaredFields()) {
				if (f.getType() == LoadedZone.class) {
					fields++;
				}
			}
			int takes = 0;
			for (Method m : c.getDeclaredMethods()) {
				if (Arrays.asList(m.getParameterTypes()).contains(LoadedZone.class)) {
					takes++;
				}
			}
			check(takes > 0, simple(name) + " is handed an owner at the call (" + takes + " method(s))");
			check(fields == 0, simple(name) + " keeps none of its own, so it cannot outlive the call ("
					+ fields + ")");
		}
	}

	// ------------------------------------------------------ 3. not fetched, in bytecode

	static void theOwnerIsMadeInOnePlace(File classes) throws Exception {
		System.out.println("--- the owner is made in one place, and handed from there");
		if (!classes.isDirectory()) {
			check(false, "the compiled tree is at " + classes + " - build first");
			return;
		}
		List<ClassFileScanner.ClassFile> app = ClassFileScanner.application(classes);
		Set<String> makers = new TreeSet<>();
		for (ClassFileScanner.ClassFile cf : app) {
			for (ClassFileScanner.Ref r : cf.refs) {
				if (r.owner.equals(OWNER) && r.method && r.name.equals("<init>")) {
					makers.add(cf.topLevel());
				}
			}
		}
		System.out.println("  makers of a LoadedZone outside the suites: " + makers);
		check(makers.equals(new TreeSet<>(Arrays.asList(MAKERS))),
				"exactly the window makes one (" + makers + ", recorded " + Arrays.toString(MAKERS) + ")");

		//and it cannot become a global itself: no statics at all
		Set<String> statics = new TreeSet<>();
		for (Field f : LoadedZone.class.getDeclaredFields()) {
			if (Modifier.isStatic(f.getModifiers())) {
				statics.add(f.getName());
			}
		}
		check(statics.isEmpty(), "LoadedZone holds no static field, so there is no second way to reach one "
				+ statics);
	}

	static void theZoneStateIsNoLongerReachableThroughTheWindow(File classes) throws Exception {
		System.out.println("--- what is left of the Zone tab's static, one reader at a time");
		if (!classes.isDirectory()) {
			check(false, "the compiled tree is at " + classes + " - build first");
			return;
		}
		List<ClassFileScanner.ClassFile> app = ClassFileScanner.application(classes);
		Set<String> readers = new TreeSet<>();
		for (ClassFileScanner.ClassFile cf : app) {
			if (cf.topLevel().equals(WINDOW)) {
				continue;                                  //the window owns the field
			}
			for (ClassFileScanner.Ref r : cf.refs) {
				if (r.owner.equals(WINDOW) && !r.method && r.name.equals(ZONE_TAB)) {
					readers.add(cf.topLevel());
				}
			}
		}
		System.out.println("  classes naming " + WINDOW + "." + ZONE_TAB + ": " + readers.size() + " " + readers);
		check(readers.equals(new TreeSet<>(Arrays.asList(STATIC_READERS))),
				"and they are exactly the recorded ones, each using the panel for an operation and not for zone state"
				+ " (measured " + readers + ", recorded " + Arrays.toString(STATIC_READERS) + ")");

		//no class outside the window may read the window's own owner field either:
		//it is private, so a reader would have to be an inner class of the window
		Set<String> ownerReaders = new TreeSet<>();
		for (ClassFileScanner.ClassFile cf : app) {
			for (ClassFileScanner.Ref r : cf.refs) {
				if (r.owner.equals(WINDOW) && !r.method && r.name.equals("loadedZone")
						&& !cf.topLevel().equals(WINDOW)) {
					ownerReaders.add(cf.topLevel());
				}
			}
		}
		check(ownerReaders.isEmpty(),
				"nothing outside the window reads the window's own owner field " + ownerReaders);
	}

	/**
	 * The zone table and the open zone live in ONE class. A second field
	 * holding a zone somewhere else is a second answer to "what is open",
	 * which is the shape this step removed - and it is invisible to the other
	 * three rules here, all of which ask about the owner rather than about
	 * zones.
	 */
	static void nobodyElseKeepsTheLoadedZone(File classes) throws Exception {
		System.out.println("--- the loaded zone is kept in one class and nowhere else");
		if (!classes.isDirectory()) {
			check(false, "the compiled tree is at " + classes + " - build first");
			return;
		}
		Set<String> keepers = new TreeSet<>();
		for (ClassFileScanner.ClassFile cf : ClassFileScanner.application(classes)) {
			Class<?> c;
			try {
				//loaded WITHOUT running its static initialiser: some of these
				//classes start GL contexts and animator threads when they run
				c = Class.forName(cf.name.replace('/', '.'), false, LoadedZoneTest.class.getClassLoader());
			} catch (Throwable notLoadable) {
				continue;
			}
			for (Field f : c.getDeclaredFields()) {
				String type = f.getType().getName();
				if (type.equals("ctrmap.formats.zone.Zone") || type.equals("[Lctrmap.formats.zone.Zone;")) {
					keepers.add(cf.name + " " + Modifier.toString(f.getModifiers()) + " "
							+ (type.startsWith("[") ? "Zone[]" : "Zone") + " " + f.getName());
				}
			}
		}
		System.out.println("  fields holding a zone: " + keepers);
		check(keepers.equals(new TreeSet<>(Arrays.asList(ZONE_KEEPERS))),
				"exactly the owner's two and the script edit's subject hold a zone (measured " + keepers
				+ ", recorded " + Arrays.toString(ZONE_KEEPERS) + ")");
	}

	// ------------------------------------------------------------ 4. two owners

	/**
	 * The two-owner proof, through behaviour rather than through a field: two
	 * panels of the same class, each handed its own owner, asked the same
	 * question, answering about its own zone. This is the check the three
	 * public fields made impossible - there was one panel, reached through one
	 * static, so "the other zone" did not exist.
	 */
	static void twoPanelsOverTwoOwnersAnswerDifferently() throws Exception {
		System.out.println("--- two panels over two owners answer about their own zone");
		String key = "FORK_DECLINED_" + Workspace.WORKSPACE_PATH.hashCode();
		try {
			LoadedZone a = new LoadedZone();
			LoadedZone b = new LoadedZone();
			ZoneLoadingPanel panelA = new ZoneLoadingPanel(a, TOOLS, EDITORS, ZONE_EDITORS, NAVI);
			ZoneLoadingPanel panelB = new ZoneLoadingPanel(b, TOOLS, EDITORS, ZONE_EDITORS, NAVI);

			//the offer walks the whole table, so give both owners the same first
			//forty zones - reading all 536 would cost the suite a minute and prove
			//nothing more. Only ONE of the two is given a zone to have open.
			int entries = Workspace.getArchive(ArchiveType.ZONE_DATA).length;
			Zone[] table = new Zone[entries - 2];
			int filled = Math.min(40, table.length);
			for (int i = 0; i < filled; i++) {
				table[i] = zoneAt(i);
			}
			a.table(table);
			b.table(table);
			int mm = a.at(SHARED_ZONE).header.mapmatrixID;
			int sharers = 0;
			for (int i = 0; i < filled; i++) {
				if (i != SHARED_ZONE && table[i].header.mapmatrixID == mm) {
					sharers++;
				}
			}
			check(sharers > 0, "corpus zone " + SHARED_ZONE + " really does share its map with "
					+ sharers + " of the first " + filled + " zones");
			String asks = "Shared map: This zone SHARES its map with " + sharers + " other zone(s)";

			a.open(SHARED_ZONE, a.at(SHARED_ZONE));
			List<String> saidA = offerFork(panelA);
			List<String> saidB = offerFork(panelB);
			check(saidA.size() == 1 && saidA.get(0).startsWith(asks),
					"the panel handed the owner with zone " + SHARED_ZONE
					+ " open asks about THAT zone's sharers: " + saidA);
			check(saidB.isEmpty(),
					"and the panel handed an owner with nothing open asks nothing, at the same moment: " + saidB);

			//now the other way round, so what changed is the OWNER and not the panel
			panelA.clearForkDecline(SHARED_ZONE);
			panelB.clearForkDecline(SHARED_ZONE);
			a.close();
			b.open(SHARED_ZONE, b.at(SHARED_ZONE));
			saidA = offerFork(panelA);
			saidB = offerFork(panelB);
			check(saidA.isEmpty(), "with its owner closed, the panel that asked before asks nothing: " + saidA);
			check(saidB.size() == 1 && saidB.get(0).startsWith(asks),
					"and the one whose owner now holds the zone asks instead: " + saidB);
		} finally {
			//the declines live in the machine's own preferences; this run's
			//workspace is a throwaway folder, so its key is removed with it
			try {
				java.util.prefs.Preferences.userRoot().node("ctrmap.ZoneLoadingPanel").remove(key);
			} catch (Exception ignore) {
			}
		}
	}

	/**
	 * The window is a reader like any other: bound to one owner it answers
	 * about that zone, bound to another it answers about that one. Its
	 * region default is the cheapest of its zone-scoped answers to drive - it
	 * reads the open zone's matrix id and nothing else.
	 */
	static void theWindowAnswersFromTheOwnerItWasBound() throws Exception {
		System.out.println("--- the window answers from the owner it was bound, not from a panel");
		LoadedZone withZone = new LoadedZone();
		LoadedZone empty = new LoadedZone();
		Zone z = zoneAt(PRIVATE_ZONE);
		withZone.open(PRIVATE_ZONE, z);

		int expected = ctrmap.formats.mapmatrix.MapMatrix.firstRegionId(
				java.nio.file.Files.readAllBytes(Workspace.getWorkspaceFile(
						ArchiveType.MAP_MATRIX, z.header.mapmatrixID).toPath()));
		check(expected >= 0, "zone " + PRIVATE_ZONE + "'s own map has region " + expected);

		CtrmapMainframe.bindLoadedZone(withZone);
		check(defaultRegion() == expected,
				"bound to the owner holding that zone, the OBJ default is its region (" + defaultRegion() + ")");
		CtrmapMainframe.bindLoadedZone(empty);
		check(defaultRegion() == -1,
				"bound to an owner holding nothing, it is -1 rather than the last answer (" + defaultRegion() + ")");
		CtrmapMainframe.bindLoadedZone(null);
		check(defaultRegion() == -1, "and bound to no owner at all it is still -1, not a throw");
	}

	/**
	 * The remaining readers, built two at a time over two owners: each holds
	 * the one it was handed, and refuses to be built without one. What each
	 * then DOES with it is its own suite's business - what is proved here is
	 * that two of them can exist at once over different zones, which is the
	 * thing the public fields made impossible.
	 */
	static void everyReaderHoldsTheOwnerItWasHanded() throws Exception {
		System.out.println("--- every reader holds the owner it was handed, and refuses to be built without one");
		LoadedZone a = new LoadedZone();
		LoadedZone b = new LoadedZone();
		Set<String> built = new LinkedHashSet<>();
		PropEditForm propA = null;
		PropEditForm propB = null;
		try {
			for (String name : KEEPERS) {
				Class<?> c = Class.forName(name);
				Constructor<?> ctor = handedCtor(c);
				Object first = ctor.newInstance(argsFor(ctor, a));
				Object second = ctor.newInstance(argsFor(ctor, b));
				if (first instanceof PropEditForm) {
					propA = (PropEditForm) first;
					propB = (PropEditForm) second;
				}
				check(handedOwner(first) == a && handedOwner(second) == b,
						simple(name) + ": two of them exist at once, each over its own owner");
				String refusal = refusedBy(() -> {
					try {
						ctor.newInstance(argsFor(ctor, null));
					} catch (java.lang.reflect.InvocationTargetException ex) {
						throw (RuntimeException) ex.getCause();
					} catch (ReflectiveOperationException ex) {
						throw new IllegalStateException(ex);
					}
				});
				check(refusal.contains("LoadedZone"),
						simple(name) + " refuses to be built without one, saying what it needs: " + refusal);
				built.add(simple(name));
			}
		} finally {
			//PropEditForm's generated initComponents builds a CustomH3DPreview,
			//whose constructor starts an FPSAnimator on a NON-daemon thread:
			//left running, the suite prints ALL PASS and then never exits.
			stopPreview(propA);
			stopPreview(propB);
		}
		check(built.size() == KEEPERS.length, "all " + KEEPERS.length + " readers were really built: " + built);
	}

	// ---- plumbing ----------------------------------------------------------

	static void stopPreview(PropEditForm form) {
		if (form == null) {
			return;
		}
		try {
			Field f = PropEditForm.class.getDeclaredField("PropPreview");
			f.setAccessible(true);
			((ctrmap.humaninterface.CustomH3DPreview) f.get(form)).stop();
		} catch (Exception ex) {
			System.out.println("  note: the prop preview could not be stopped (" + ex + ")");
		}
	}

	/**
	 * The constructor a reader is handed its owner through - whatever else it
	 * is handed with it. Asking for {@code getConstructor(LoadedZone.class)}
	 * pinned the SHAPE of the constructor rather than the property this suite
	 * exists to assert, and went red the moment a reader was handed a second
	 * collaborator.
	 */
	static Constructor<?> handedCtor(Class<?> c) {
		for (Constructor<?> ctor : c.getConstructors()) {
			if (Arrays.asList(ctor.getParameterTypes()).contains(LoadedZone.class)) {
				return ctor;
			}
		}
		throw new IllegalStateException(c.getName() + " has no public constructor taking a LoadedZone");
	}

	/**
	 * That constructor's arguments: the owner where it asks for one, and a
	 * fresh one of whatever else it asks for. A collaborator this does not
	 * know how to build is named, rather than passed null and blamed on the
	 * class under test.
	 */
	static Object[] argsFor(Constructor<?> ctor, LoadedZone owner) {
		Class<?>[] types = ctor.getParameterTypes();
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++) {
			if (types[i] == LoadedZone.class) {
				args[i] = owner;
			} else if (types[i] == ctrmap.humaninterface.tools.ToolSelection.class) {
				args[i] = new ctrmap.humaninterface.tools.ToolSelection();
			} else if (types[i] == ctrmap.humaninterface.Redraw.class) {
				args[i] = new Redraws();
			} else if (types[i] == ctrmap.humaninterface.OpenEditors.class) {
				args[i] = new RecordingEditors();
			} else if (types[i] == ctrmap.humaninterface.ZoneEditors.class) {
				args[i] = new ZoneEditorsSpy();
			} else if (types[i] == ctrmap.humaninterface.Navigator.class) {
				args[i] = new RecordingNavi();
			} else if (types[i] == ctrmap.humaninterface.ViewportCentre.class) {
				args[i] = new RecordingCentre();
			} else if (types[i] == ctrmap.humaninterface.MatrixCanvas.class) {
				args[i] = new ctrmap.humaninterface.MapMatrixPanel();
			} else if (types[i] == ctrmap.humaninterface.ZoneList.class) {
				args[i] = new RecordingZoneList();
			} else if (types[i] == ctrmap.humaninterface.ZoneSaver.class
				|| types[i] == ctrmap.humaninterface.ScriptView.class) {
				args[i] = new RecordingZoneSaver();
			} else if (types[i] == ctrmap.humaninterface.Scene3D.class) {
				args[i] = new RecordingScene();
			} else if (types[i] == javax.swing.JScrollPane.class) {
				args[i] = new javax.swing.JScrollPane();
			} else if (types[i] == ctrmap.humaninterface.CollEditPanel.class) {
				args[i] = new ctrmap.humaninterface.CollEditPanel(new ctrmap.humaninterface.tools.ToolSelection());
			} else {
				throw new IllegalStateException(ctor.getDeclaringClass().getName()
						+ " is handed a " + types[i].getName()
						+ ", which this suite does not know how to build - teach it here");
			}
		}
		return args;
	}

	/** The one LoadedZone field of a reader, whatever it called it. */
	static LoadedZone handedOwner(Object reader) throws Exception {
		for (Field f : reader.getClass().getDeclaredFields()) {
			if (f.getType() == LoadedZone.class) {
				f.setAccessible(true);
				return (LoadedZone) f.get(reader);
			}
		}
		return null;
	}

	/** Drives the panel's shared-map offer with the fork declined, and returns what it said. */
	static List<String> offerFork(ZoneLoadingPanel pnl) throws Exception {
		List<String> said = Ui.record(javax.swing.JOptionPane.NO_OPTION);
		try {
			Method m = ZoneLoadingPanel.class.getDeclaredMethod("offerForkIfShared");
			m.setAccessible(true);
			m.invoke(pnl);
		} catch (java.lang.reflect.InvocationTargetException ex) {
			said.add("THREW: " + (ex.getCause() == null ? ex : ex.getCause()));
		} finally {
			Ui.stopRecording();
		}
		return new ArrayList<>(said);
	}

	static int defaultRegion() throws Exception {
		Method m = CtrmapMainframe.class.getDeclaredMethod("defaultRegionForLoadedZone");
		m.setAccessible(true);
		return (Integer) m.invoke(null);
	}

	static Zone zoneAt(int index) throws Exception {
		return new Zone(new ZO(temp(Workspace.getArchive(ArchiveType.ZONE_DATA).getDecompressedEntry(index)),
				Workspace.session()), Workspace.game());
	}

	static File temp(byte[] bytes) throws Exception {
		File f = File.createTempFile("ctrmap_loadedzone", ".bin");
		f.deleteOnExit();
		java.nio.file.Files.write(f.toPath(), bytes);
		return f;
	}

	/** The message of whatever a call refused with, or a sentence saying it did not refuse. */
	static String refusedBy(Runnable call) {
		try {
			call.run();
			return "(nothing was thrown)";
		} catch (RuntimeException ex) {
			return ex.getClass().getSimpleName() + ": " + ex.getMessage();
		}
	}

	static String simple(String binaryName) {
		return binaryName.substring(binaryName.lastIndexOf('.') + 1);
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok: " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}
}
