package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * What the program keeps in {@code public static} fields, and what stops that
 * growing.
 *
 * <p>CTRMap is built around global mutable state - {@link Workspace} above all,
 * whose paths, archive handles and {@code GameType} were read from everywhere.
 * That state is a {@link ctrmap.WorkspaceSession} instance now, with
 * Workspace's old statics delegating to the current one while callers are
 * migrated ({@code WorkspaceSessionTest} counts them). What this suite does
 * is hold the line on everything else, and make the two failure modes global
 * state actually causes here impossible to reintroduce quietly.
 *
 * <h2>1. The ceiling (a ratchet)</h2>
 * A count of every {@code public static} non-final field in the compiled
 * program outside {@code ctrmap.tests}, which fails when it RISES above
 * {@link #CEILING}. Like the mutation baseline, it cannot force the number
 * down; it stops it drifting up unnoticed. Every one of these is a value any
 * code anywhere can change, so each is a reason a feature cannot be exercised
 * without dragging the rest of the program in, and each is a way one piece of
 * work can disturb another inside one JVM. Adding one should be a decision
 * somebody made on purpose, which is what having to edit the number here
 * forces.
 *
 * <p>Measured from {@code build/classes} rather than from the source text,
 * because that is the measurement that cannot be fooled: it counts fields, not
 * lines, so a multi-declarator line counts once per field; and it never
 * mistakes {@code public static class Foo} for a field, nor an interface
 * constant (implicitly final) for a mutable one. Grepping the sources for
 * "public static" without "final" reports 214 for the same tree this counts
 * 146 in, and 76 of those 214 are type declarations.
 *
 * <p>The class file reader this suite was built around now lives in
 * {@link ClassFileScanner}, so every structural guard reads the same bytecode
 * instead of each inventing a regular expression over source text.
 *
 * <h2>2. A global nothing ever writes</h2>
 * {@code PaintedRegionBuilder.terrainCovered} was a {@code public static
 * boolean[][]} whose javadoc described when "the terrain-kit composer" set it
 * and cleared it. No such composer was ever written. The field was null for
 * the life of every run, so the branch reading it could not be taken, and a
 * reader had every reason to believe a mechanism existed that did not. A
 * public static field nothing in the program assigns is not a hook: it is dead
 * state advertising a feature. The rule is deliberately blunt about what
 * counts as an assignment - any {@code name =} anywhere in the sources - so it
 * can only fire on a field that truly nothing writes.
 *
 * <h2>3. {@link Workspace#reset} covers every field</h2>
 * Workspace holds the settings and the current session, so "state left
 * behind" bites there first. {@code reset()} puts all of it back. A reset
 * that silently misses a field is worse than no reset - it reads as a clean
 * slate while one workspace leaks into the next - so this enumerates
 * Workspace's static fields by reflection and fails when {@code reset()}
 * does not name one. Every field whose value can be constructed here is
 * additionally dirtied for real and checked, which is what proves those
 * lines do something rather than merely being present; the session is
 * installed and must be gone afterwards, since everything the old fields
 * held now hangs off it.
 *
 * <h2>4. Every static that can change is argued (F218)</h2>
 * Section 1 counted 34 of the 186 statics that could change, so state read
 * from the open game grew in the other 152 with seven rules for when it went,
 * two of them never. Every one is now NAMED under the reason it may be a
 * static, read from the bytecode; one that is not named fails, one named and
 * gone fails, and the "constant tables" are measured unwritten rather than
 * argued. There is no reason for "read from the open game".
 *
 * <h2>5. What is read from a game is kept by that game (F218)</h2>
 * Its home instead: {@link ctrmap.formats.GameCache}, one per game, emptied at
 * the one door every archive rewrite passes. A read that throws keeps nothing,
 * an answer read across a drop is not kept, and the facility editor's labels
 * name the game open when they are asked for.
 *
 * <p>ORDER: this suite needs no dump, no workspace and no scratch space, and
 * writes no file. It can run first, last, or alone.
 *
 * Usage: java ctrmap.tests.GlobalStateTest [src-root] [classes-root]
 */
public class GlobalStateTest {

	/**
	 * The number of public static mutable fields outside ctrmap.tests, as left
	 * on 2026-09-06. RAISE THIS ONLY DELIBERATELY, and say in the commit
	 * message which global was added and why it had to be one.
	 *
	 * <p>146 when this suite was written; 141 after five that did not need to
	 * be mutable globals stopped being them (one dead, two private, two final);
	 * 110 once the open game moved out of Workspace's statics into a
	 * WorkspaceSession instance (ten archive files, seventeen GARC handles,
	 * the persist file, the GameType, the valid flag, and a music-name table
	 * nothing ever filled).
	 *
	 * 103 once the main window's 38 menu statics became locals of the one
	 * builder that makes the menu bar; 88 once its tool row's sixteen became
	 * one owner, WorldEditorToolbar; 75 once the panel plumbing nothing else
	 * read became locals or private; 72 once the tab switch and the split
	 * pane became methods the window offers instead of fields it exposes
	 * (MainframeShapeTest ratchets that class on its own).
	 *
	 * <p>Independently of the two reductions above, from the same 141:
	 * 140 once AreaForkPrompt handed its fork result back instead of
	 * remembering it; 139 once the natives table a SYSREQ_N name resolves in
	 * rode on the PawnAssembly instead of on PawnInstruction; 138 once
	 * LocationNames' table went private behind an accessor that loads it.
	 *
	 * <p>MERGED 2026-09-07: the workspace, window and debt reductions all
	 * landed on one tree. Measured by this suite on that build and set to
	 * exactly that. Merge 2 wrote "measured 41" here and left the constant
	 * at 72 - a ceiling 31 above the truth cannot fail on a real regression -
	 * so this number was proven by breaking: one below it fails, naming the
	 * count, before it was set.
	 */
	//38 -> 36: the decoupling steps deleted the window's held tool and made its
	//frame private, both of which were public and mutable.
	//36 -> 34: measured by this suite on 2026-10-07 (F218), the number it printed;
	//section 4 now names every one of them, and every other static that can change.
	private static final int CEILING = 34;

	static int fails = 0;

	static void check(boolean cond, String what) {
		if (!cond) {
			System.out.println("  FAIL: " + what);
			fails++;
		} else {
			System.out.println("  ok: " + what);
		}
	}

	public static void main(String[] args) throws Exception {
		File src = new File(args.length > 0 ? args[0] : "src");
		File classes = new File(args.length > 1 ? args[1] : "build/classes");

		List<ClassFileScanner.Member> globals = ceiling(classes);
		neverWritten(src, globals);
		resetCoversWorkspace(src);
		everyStaticThatCanChangeIsArgued(src, classes);
		whatIsReadFromAGameIsKeptByIt();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	// ---------------------------------------------------------------- 1. ceiling
	static List<ClassFileScanner.Member> ceiling(File classes) throws IOException {
		if (!classes.isDirectory()) {
			//NOT a skip. A guard that quietly measures nothing and prints ALL
			//PASS is the shape of failure this battery exists to refuse.
			check(false, "there is a compiled program to count globals in - none at " + classes
					+ " (run build.ps1, or pass the classes root as args[1])");
			return new ArrayList<>();
		}
		List<ClassFileScanner.Member> globals = new ArrayList<>();
		for (ClassFileScanner.ClassFile cf : ClassFileScanner.application(classes)) {
			globals.addAll(cf.globals);
		}
		Map<String, Integer> perClass = new LinkedHashMap<>();
		for (ClassFileScanner.Member g : globals) {
			String k = g.simpleOwner();
			perClass.put(k, perClass.containsKey(k) ? perClass.get(k) + 1 : 1);
		}
		List<Map.Entry<String, Integer>> top = new ArrayList<>(perClass.entrySet());
		Collections.sort(top, new Comparator<Map.Entry<String, Integer>>() {
			@Override
			public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
				return b.getValue() - a.getValue();
			}
		});
		StringBuilder where = new StringBuilder();
		for (int i = 0; i < Math.min(5, top.size()); i++) {
			where.append(i > 0 ? ", " : "").append(top.get(i).getKey()).append(' ').append(top.get(i).getValue());
		}
		System.out.println("  public static mutable fields outside ctrmap.tests: " + globals.size()
				+ " in " + perClass.size() + " classes (" + where + ")");
		check(globals.size() <= CEILING,
				"the count of public static mutable fields has not risen above " + CEILING
				+ " (it is " + globals.size() + ")");
		return globals;
	}

	// ------------------------------------------------- 2. a global nothing writes
	static void neverWritten(File src, List<ClassFileScanner.Member> globals) throws IOException {
		if (globals.isEmpty() || !src.isDirectory()) {
			check(false, "there are sources at " + src + " and globals to cross-check against them");
			return;
		}
		StringBuilder all = new StringBuilder();
		for (File f : javaFiles(src)) {
			all.append(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).append('\n');
		}
		String text = all.toString();
		List<String> dead = new ArrayList<>();
		for (ClassFileScanner.Member g : globals) {
			//Deliberately blunt: ANY "name =" (or ++/--/+=) anywhere in any source
			//counts, even a local of the same name in an unrelated file. A guard
			//that fires wrongly is worse than one that misses a case, and the
			//field this exists for was written by nothing whatsoever.
			Pattern p = Pattern.compile("\\b" + Pattern.quote(g.name)
					+ "\\s*(?:=(?!=)|\\+\\+|--|\\+=|-=|\\|=|&=)");
			if (!p.matcher(text).find()) {
				dead.add(g.simpleOwner() + "." + g.name);
			}
		}
		check(dead.isEmpty(), "every public static mutable field is written by something"
				+ (dead.isEmpty() ? "" : " - these are not, so they are dead state advertising a feature: " + dead));
	}

	private static List<File> javaFiles(File dir) {
		List<File> out = new ArrayList<>();
		File[] kids = dir.listFiles();
		if (kids == null) {
			return out;
		}
		for (File f : kids) {
			if (f.isDirectory()) {
				out.addAll(javaFiles(f));
			} else if (f.getName().endsWith(".java")) {
				out.add(f);
			}
		}
		return out;
	}

	// ------------------------------------------------ 3. reset() covers Workspace
	static void resetCoversWorkspace(File src) throws Exception {
		List<Field> statics = new ArrayList<>();
		for (Field f : Workspace.class.getDeclaredFields()) {
			int m = f.getModifiers();
			if (Modifier.isStatic(m) && !Modifier.isFinal(m) && !f.isSynthetic()) {
				f.setAccessible(true);
				statics.add(f);
			}
		}
		//What is left static here is the settings, the current session and a
		//once-per-session flag. The open game itself - paths, GameType, archive
		//files, GARC handles, edited-file list - is a WorkspaceSession instance
		//now, and WorkspaceSessionTest holds that class to having no static
		//mutable field at all. Eight is the whole list; a ninth is a decision.
		check(statics.size() <= 8, "Workspace keeps only the settings, the current session and its"
				+ " reporting flag in statics (" + statics.size() + " of them)");

		//(a) every one of them is named in reset()'s own body
		File ws = new File(src, "ctrmap/Workspace.java");
		String body = ws.isFile()
				? methodBody(new String(Files.readAllBytes(ws.toPath()), StandardCharsets.UTF_8),
						"public static void reset() {")
				: null;
		List<String> missing = new ArrayList<>();
		for (Field f : statics) {
			Pattern p = Pattern.compile("\\b" + Pattern.quote(f.getName())
					+ "\\s*(?:=(?!=)|\\.clear\\s*\\()");
			if (body == null || !p.matcher(body).find()) {
				missing.add(f.getName());
			}
		}
		check(body != null && missing.isEmpty(),
				"Workspace.reset() puts back every static Workspace owns"
				+ (body == null ? " - no reset() found in " + ws
						: missing.isEmpty() ? "" : " - it never touches " + missing));

		//(b) and those lines really do it, for every field whose value can be
		//    built here (a GARC needs a real archive on disk; the rest do not)
		Map<Field, Object> startup = new LinkedHashMap<>();
		List<Field> dirtied = new ArrayList<>();
		for (Field f : statics) {
			startup.put(f, f.get(null));
			Object dirty = dirtyValue(f, f.get(null));
			if (dirty != NOT_CONSTRUCTIBLE) {
				f.set(null, dirty);
				dirtied.add(f);
			}
		}
		//the session: reset() must drop it, because everything the old fields
		//held now hangs off it - one leaked session is every old leak at once
		Workspace.install(new ctrmap.WorkspaceSession(new File("dirty-ws"), new File("dirty-game"),
				GameType.ORAS, null));
		check(dirtied.size() >= 5, "enough of Workspace's statics can be dirtied here for the check to mean something ("
				+ dirtied.size() + ")");

		Workspace.reset();

		List<String> survived = new ArrayList<>();
		for (Field f : dirtied) {
			Object now = f.get(null);
			Object was = startup.get(f);
			if (was == null ? now != null : !was.equals(now)) {
				survived.add(f.getName() + " (" + was + " -> " + now + ")");
			}
		}
		check(survived.isEmpty(), "and dirtying them and calling reset() really does put them back"
				+ (survived.isEmpty() ? "" : " - these survived it: " + survived));
		check(Workspace.session() == null && !Workspace.isValid() && Workspace.game() == null,
				"including the current session, which is dropped so no archive of it is reachable");
	}

	// ---------------------------------- 4. every static that can change is argued (F218)

	/** The reason whose fields are measured, not argued: nothing writes into them after class init. */
	static final String CONSTANT = "constant tables";

	/**
	 * Every static field of the program that can change - not final, or final and holding an
	 * array, a collection or any object but a String, a Pattern or a store key - named under the
	 * reason it may be one. The ceiling above counts only the public non-final ones: 34 of the
	 * 186 measured on 2026-10-06, so the rest grew unseen, and state read from the open game was
	 * kept in seven of them with seven different rules for when it went - two of them never (F218).
	 *
	 * <p>NOT A CEILING. A count lets a new static cache in for every one taken out; a name cannot
	 * arrive without being written here, under a reason a reader can hold it to. There is no reason
	 * for "read from the open game": that belongs in the game's own store, {@link
	 * ctrmap.formats.GameFiles#cache}, which a repoint replaces and a pack empties.
	 *
	 * <p>Each row: the reason, its words, then "owner field field ..." in internal names.
	 */
	static final String[][] ARGUED = {
		{"the window", "the one main window's parts and what its editors have selected: one window per"
			+ " process. Its current game and zone are the window's own, replaced whole when a workspace"
			+ " opens - not a copy of anything read from the game. The tile undo history holds the loaded"
			+ " zone's own tile maps and is cleared whenever they change; the clipboard is what the user"
			+ " copied",
			"ctrmap/CtrmapMainframe CM3DComponents collEditMasterPnl extrasBrowser extrasDefault extrasHost extrasOpen frame game gameDataHost jsp jsp2 jsp3 loadedZone m3DDebugPanel mBuilder mCamEditForm mCamScrollPane mCollEditPanel mGeoEditForm mMtxEditForm mMtxPanel mNPCEditForm mPaintForm mPropEditForm mScriptPnl mTextEditor mTileEditForm mTileMapPanel mTilemapScrollPane mTriggerEditForm mWarpEditForm mZonePnl mtxEditMasterPnl navigator openEditors redraw tabs tileEditMasterPnl tools worldToolbar zoneBrowser zoneColumn zoneTabPnl",
			"ctrmap/humaninterface/TileUndo listeners open redoStack undoStack",
			"ctrmap/TileDBWriter mSingleton",
			"ctrmap/humaninterface/GeoEditForm clipboard",
			"ctrmap/humaninterface/MatrixSelector hilightRegionX hilightRegionY selRegionX selRegionY selectSubChunks selecting",
			"ctrmap/humaninterface/Selector hilightTileX hilightTileY selTileX selTileY selecting",
		},
		{"the process", "settings, the holder of the open session, logging, the dialog sink, each thread's"
			+ " open unit, a pack's warnings (drained by the pack that made them), the update lock and a"
			+ " said-once flag: one of each per running program, whichever game is open",
			"ctrmap/ErrorSink SAYING fileHandler logFile",
			"ctrmap/formats/garc/GARC packWarnings",
			"ctrmap/WorkspaceUnit OPEN",
			"ctrmap/Ui dialogsEnabled parent sink",
			"ctrmap/Workspace ESPICA_PATH GAMEDIR_PATH TILESET_DEFAULT TILESET_PATH WORKSPACE_PATH current prefs snapshotProblemShown",
			"ctrmap/humaninterface/BuildingPaletteDialog catalogProblemsSaid",
			"ctrmap/update/AppVersion current",
			"ctrmap/update/Updater runLock runLockFile",
		},
		{"the build's own tables", "read from resources inside this build - the building catalog, the"
			+ " terrain donor table, the ground materials, the native names - the same for every game"
			+ " opened, so kept for the run; nothing here reads a dump",
			"ctrmap/formats/h3d/BuildingCatalog entries problems",
			"ctrmap/formats/tilemap/PaintedMaterials spriteMaterials",
			"ctrmap/formats/tilemap/TerrainCatalog churnDonor churnLoaded cliffDonor cliffLoaded donors",
			"ctrmap/scripts/GfHash HASH_BY_NAME NAME_BY_HASH",
		},
		{"answers that depend on nothing but their key", "a pure function's results, kept: the colour a"
			+ " height draws in is the same for every game, every time",
			"ctrmap/formats/Triangle HEIGHT_COLOURS",
		},
		{"files kept by their own length and time", "an open handle to one snapshot file, keyed by its"
			+ " path, length and time, so a rewritten file is opened anew and another game's file is"
			+ " another key",
			"ctrmap/formats/h3d/BuildingCatalog SNAPSHOTS",
		},
		{"registered once by the class initializer", "filled row by row by helpers the static"
			+ " initializer calls, and only read after",
			"ctrmap/formats/pokedata/PartyParam GET SET",
		},
		{CONSTANT, "built when the class loads and never written after - measured below, not taken on"
			+ " trust: a write into one anywhere but a static initializer fails this suite",
			"ctrmap/ModDeployer MODDABLE MODDABLE_IN_PLACE",
			"ctrmap/WorkspaceSession ARCHIVES PACK_ORDER RELOAD_ORDER REQUIRED SUBDIR WORKSPACE_SUBDIRS",
			"ctrmap/formats/area/AreaEnv COLOR_CHANNELS TIME_NAMES",
			"ctrmap/formats/cameradata/CameraData FIELDS",
			"ctrmap/formats/codepatch/CodeIps EMPTY",
			"ctrmap/formats/codepatch/ItemIconTable ANCHOR NEXT_TABLE_STOCK",
			"ctrmap/formats/codepatch/ShopData ANCHOR ANCHOR_PATCHED COUNTS NAMES",
			"ctrmap/formats/encounters/EncounterTable BANK_NAMES BANK_RATE BANK_SIZES DEFAULT_RATES",
			"ctrmap/formats/h3d/BchMapModel TYPE_BYTES",
			"ctrmap/formats/h3d/BchTexturePack BPP UNIT_REGS",
			"ctrmap/formats/h3d/MapPrefab PASSENGER_CUES",
			"ctrmap/formats/h3d/texturing/TextureCodec etc1LUT tileOrder",
			"ctrmap/formats/pokedata/PokeData CATEGORY_NAMES TYPE_NAMES TYPE_RGB",
			"ctrmap/formats/scripts/MsgWrapperInjector CLOSURE_SUB_CELLS REWRITE_SITES",
			"ctrmap/formats/scripts/NpcTemplates SIGN_TYPES SIGN_TYPE_LABELS",
			"ctrmap/formats/scripts/SignWrapperInjector CLOSURE_SUB_CELLS",
			"ctrmap/formats/text/GFMessageFile VAR_CODES VAR_NAMES",
			"ctrmap/formats/tilemap/PaintedRegionBuilder COMP_BYTES DIR_NAMES",
			"ctrmap/formats/zone/NpcMoveCodes MOVE1_BASE MOVE1_OA MOVE1_XY MOVE2_BASE MOVE2_BASE_RAW MOVE2_OA MOVE2_OA_RAW MOVE2_XY MOVE2_XY_RAW NO_LABELS NO_RAWS",
			"ctrmap/formats/zone/WarpTransitions BASE_LABELS BASE_RAWS NO_LABELS NO_RAWS ORAS_LABELS ORAS_RAWS XY_LABELS XY_RAWS",
			"ctrmap/formats/zone/Zone SUBFILES",
			"ctrmap/gamedef/GameProfile ALL",
			"ctrmap/humaninterface/GfEnvPicker CUSTOM",
			"ctrmap/humaninterface/MaisonClassListDialog TABLES",
			"ctrmap/humaninterface/MaisonEditDialog NATURES POOLS POOL_NAMES",
			"ctrmap/humaninterface/MaisonEditDialog$SetModel PAIRED_LISTS",
			"ctrmap/humaninterface/PropEditForm SEARCH_ALIASES",
			"ctrmap/humaninterface/WorldEditorToolbar TOOLS",
			"ctrmap/setup/SetupWizard TITLES",
			"ctrmap/tools/BuildingHarvester HINT_FAMILIES",
			"ctrmap/tools/TerrainDonorHarvester PROBE_TARGETS",
			"ctrmap/tools/TerrainHarvester SIZES",
			"ctrmap/Workspace NO_PERSISTED",
			"ctrmap/ZoneAppender MADE_PRIVATE",
			"ctrmap/formats/scripts/PawnInstruction cmdList",
			"ctrmap/formats/tilemap/TerrainCatalog NOT_BRUSHES",
			"ctrmap/setup/DumpCheck REQUIRED",
			"ctrmap/formats/zone/ZoneEntities$GroundHeight NONE",
		},
		{"an offline tool's own scratch", "a harvester run from the command line to build this program's"
			+ " tables, never loaded by the editor",
			"ctrmap/tools/TerrainHarvester rockMaterial",
		},
	};

	/** Final references to these cannot change what they hold; every other final reference can. */
	static final List<String> IMMUTABLE = Arrays.asList("Ljava/lang/String;", "Ljava/util/regex/Pattern;",
			"Lctrmap/formats/GameCache$Key;");

	/** Whether a static can change: not final, or final over something that is not a constant. */
	static boolean changes(ClassFileScanner.StaticField f) {
		return !f.isFinal() || (f.descriptor.length() > 1 && !IMMUTABLE.contains(f.descriptor));
	}

	/** "owner.name" of every argued field, by its reason; a field argued twice is reported. */
	static Map<String, String> argued(List<String> twice) {
		Map<String, String> reasonOf = new LinkedHashMap<>();
		for (String[] row : ARGUED) {
			for (int i = 2; i < row.length; i++) {
				String[] words = row[i].trim().split("\\s+");
				for (int j = 1; j < words.length; j++) {
					if (reasonOf.put(words[0] + "." + words[j], row[0]) != null) {
						twice.add(words[0] + "." + words[j]);
					}
				}
			}
		}
		return reasonOf;
	}

	static void everyStaticThatCanChangeIsArgued(File src, File classes) throws IOException {
		System.out.println("--- every static that can change is argued, and none holds what was read from a game (F218)");
		if (!classes.isDirectory()) {
			check(false, "there is a compiled program to read the statics of - none at " + classes);
			return;
		}
		Set<String> counted = new TreeSet<>();
		Set<String> constantsSeen = new TreeSet<>();
		for (ClassFileScanner.ClassFile cf : ClassFileScanner.application(classes)) {
			for (ClassFileScanner.StaticField f : cf.statics) {
				(changes(f) ? counted : constantsSeen).add(f.owner + "." + f.name);
			}
		}
		//KNOWN ANSWERS FIRST: a scan that read the wrong field table answers "nothing to argue"
		check(counted.contains("ctrmap/Workspace.current") && counted.contains("ctrmap/humaninterface/TileUndo.undoStack")
				&& counted.contains("ctrmap/formats/pokedata/PokeData.TYPE_NAMES"),
				"control: a non-final static, a final collection and a final array are each counted");
		check(constantsSeen.contains("ctrmap/formats/pokedata/PokeData.TABLES")
				&& constantsSeen.contains("ctrmap/humaninterface/BuildingPaletteDialog.PREFABS"),
				"control: a store key is a constant, so the caches moved onto the game's store are not counted as statics");
		check(counted.size() >= 150, "the scan read the program's statics (" + counted.size() + " that can change)");

		List<String> twice = new ArrayList<>();
		Map<String, String> reasonOf = argued(twice);
		List<String> unargued = new ArrayList<>(counted);
		unargued.removeAll(reasonOf.keySet());
		List<String> stale = new ArrayList<>(reasonOf.keySet());
		stale.removeAll(counted);
		check(twice.isEmpty(), "no static is argued twice" + (twice.isEmpty() ? "" : " - " + twice));
		check(unargued.isEmpty(), "every static that can change is argued here (" + counted.size() + ")"
				+ (unargued.isEmpty() ? "" : " - these are not: " + unargued + ". What is read from a game"
				+ " belongs in that game's store (GameFiles.cache()), where a repoint or a pack cannot leave it"
				+ " behind; anything else goes under the reason it truly fits, and if none fits it should not"
				+ " be a static"));
		check(stale.isEmpty(), "and every argued static still is one" + (stale.isEmpty() ? ""
				: " - these are gone or no longer change, so take them out: " + stale));
		constantTablesAreNotWritten(src, reasonOf);
	}

	/**
	 * The fields argued as constant tables, held to it: no write into one - an element stored, an
	 * entry put, added, removed, cleared or sorted - outside a static initializer of its class, and
	 * none at all from another class. Known answers first: the same finder sees TileUndo's undo stack
	 * written, nothing written into PokeData's type names, and Workspace's game folder written from
	 * the classes that set it.
	 */
	static void constantTablesAreNotWritten(File src, Map<String, String> reasonOf) throws IOException {
		Writes writes = new Writes(src);
		List<String> written = new ArrayList<>();
		int read = 0;
		for (Map.Entry<String, String> e : reasonOf.entrySet()) {
			if (!CONSTANT.equals(e.getValue())) {
				continue;
			}
			List<String> where = writes.after(e.getKey());
			if (where == null) {
				written.add(e.getKey() + " (its source could not be read)");
				continue;
			}
			read++;
			if (!where.isEmpty()) {
				written.add(e.getKey() + " at " + where);
			}
		}
		List<String> undo = writes.after("ctrmap/humaninterface/TileUndo.undoStack");
		List<String> types = writes.after("ctrmap/formats/pokedata/PokeData.TYPE_NAMES");
		List<String> outside = writes.after("ctrmap/Workspace.GAMEDIR_PATH");
		check(undo != null && !undo.isEmpty() && types != null && types.isEmpty() && outside != null
				&& outside.toString().contains(".java:"),
				"control: the write finder sees TileUndo's stack written " + undo + ", PokeData's type names not "
				+ types + ", and Workspace.GAMEDIR_PATH written from other classes (" + (outside == null ? 0 : outside.size()) + ")");
		check(read >= 60 && written.isEmpty(), "no constant table is written after its class loads, by its class or any"
				+ " other (" + read + " read)" + (written.isEmpty() ? "" : " - these are: " + written + ". A table that"
				+ " changes is state: argue it under the reason it fits, or keep it where its lifetime is"));
	}

	private static final String MUTATION = "\\s*(?:\\[[^\\]]*\\]\\s*(?:=(?!=)|\\+=|-=|\\+\\+|--)"
			+ "|=(?!=)|\\.(?:put|putAll|putIfAbsent|add|addAll|addFirst|addLast|push|pop|poll|remove|removeIf|clear|set"
			+ "|offer|computeIfAbsent|compute|merge|replaceAll|sort)\\s*\\()";

	/**
	 * Where the program writes into a static: in its own class outside a static initializer, and in
	 * any other production class through its qualified name - a public table can be written from
	 * anywhere, and a finder that read only the owner would pass every such write. The other
	 * classes are read once, in one pass each, for every qualified write they hold.
	 */
	static final class Writes {

		private final File src;
		/** "Simple.field" -> "File.java:line" of each write from outside the owner's file. */
		private final Map<String, List<String>> qualified = new java.util.HashMap<>();

		Writes(File src) throws IOException {
			this.src = src;
			Pattern p = Pattern.compile("(?<![\\w.])([A-Z]\\w*)\\s*\\.\\s*(\\w+)" + MUTATION
					+ "|(?:Arrays\\.fill|Arrays\\.sort|Collections\\.sort)\\s*\\(\\s*([A-Z]\\w*)\\s*\\.\\s*(\\w+)\\b");
			for (File f : javaFiles(src)) {
				if (f.getPath().contains(File.separator + "tests" + File.separator)) {
					continue;
				}
				String text = stripped(f);
				java.util.regex.Matcher m = p.matcher(text);
				while (m.find()) {
					String key = m.group(1) != null ? m.group(1) + "." + m.group(2) : m.group(3) + "." + m.group(4);
					List<String> at = qualified.get(key);
					if (at == null) {
						at = new ArrayList<>();
						qualified.put(key, at);
					}
					at.add(f.getName() + ":" + lineOf(text, m.start()));
				}
			}
		}

		/** Every write into {@code owner.field} after its class loaded; null when its source cannot be read. */
		List<String> after(String ownerDotField) throws IOException {
			int dot = ownerDotField.lastIndexOf('.');
			String owner = ownerDotField.substring(0, dot);
			String field = ownerDotField.substring(dot + 1);
			File own = new File(src, owner.split("\\$")[0] + ".java");
			if (!own.isFile()) {
				return null;
			}
			String text = stripped(own);
			List<int[]> clinit = new ArrayList<>();
			java.util.regex.Matcher s = Pattern.compile("\\bstatic\\s*\\{").matcher(text);
			while (s.find()) {
				int depth = 1;
				int i = s.end();
				while (i < text.length() && depth > 0) {
					char c = text.charAt(i++);
					depth += c == '{' ? 1 : c == '}' ? -1 : 0;
				}
				clinit.add(new int[]{s.start(), i});
			}
			List<String> out = new ArrayList<>();
			String named = "(?<![\\w.])" + Pattern.quote(field);
			java.util.regex.Matcher m = Pattern.compile(named + "\\s*(?:\\[[^\\]]*\\]\\s*(?:=(?!=)|\\+=|-=|\\+\\+|--)"
					+ "|\\.(?:put|putAll|putIfAbsent|add|addAll|addFirst|addLast|push|pop|poll|remove|removeIf|clear|set"
					+ "|offer|computeIfAbsent|compute|merge|replaceAll|sort)\\s*\\()"
					+ "|(?:Arrays\\.fill|Arrays\\.sort|Collections\\.sort)\\s*\\(\\s*" + named + "\\b").matcher(text);
			while (m.find()) {
				boolean inInit = false;
				for (int[] span : clinit) {
					inInit |= m.start() >= span[0] && m.start() < span[1];
				}
				if (!inInit) {
					out.add(String.valueOf(lineOf(text, m.start())));
				}
			}
			String simple = owner.substring(Math.max(owner.lastIndexOf('/'), owner.lastIndexOf('$')) + 1);
			for (String at : qualified.getOrDefault(simple + "." + field, Collections.<String>emptyList())) {
				if (!at.startsWith(own.getName() + ":")) {
					out.add(at);
				}
			}
			return out;
		}

		private final Map<File, String> read = new java.util.HashMap<>();

		private String stripped(File f) throws IOException {
			String text = read.get(f);
			if (text == null) {
				text = SourceSeamTest.stripComments(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
				read.put(f, text);
			}
			return text;
		}

		private static int lineOf(String text, int at) {
			int line = 1;
			for (int i = 0; i < at; i++) {
				line += text.charAt(i) == '\n' ? 1 : 0;
			}
			return line;
		}
	}

	// ------------------------------ 5. what is read from a game is kept by that game (F218)
	static void whatIsReadFromAGameIsKeptByIt() throws Exception {
		System.out.println("--- what is read from a game is kept by that game, and goes when the game changes (F218)");
		ctrmap.formats.GameCache store = new ctrmap.formats.GameCache();
		ctrmap.formats.GameCache.Key<String> key = new ctrmap.formats.GameCache.Key<>("an answer");
		int[] reads = {0};
		String failed = null;
		try {
			store.get(key, () -> {
				reads[0]++;
				throw new IOException("the archive did not read");
			});
		} catch (IOException said) {
			failed = said.getMessage();
		}
		check(failed != null && !store.holds(key),
				"a read that throws is said, and keeps nothing - a failure is said again, never remembered as an empty answer");
		String first = store.get(key, () -> {
			reads[0]++;
			return "read";
		});
		String again = store.get(key, () -> {
			reads[0]++;
			return "read twice";
		});
		check("read".equals(first) && "read".equals(again) && reads[0] == 2,
				"the next read is kept, and asked again the store answers it without reading (" + reads[0] + " reads)");
		ctrmap.formats.GameCache.Key<String> nothing = new ctrmap.formats.GameCache.Key<>("an absence");
		store.get(nothing, () -> null);
		check(store.holds(nothing), "an answer of nothing is kept like any other");
		ctrmap.formats.GameCache.Key<String> across = new ctrmap.formats.GameCache.Key<>("read across a pack");
		String during = store.get(across, () -> {
			store.drop();   //the pack lands while this was reading the old archives
			return "the old archives' answer";
		});
		check("the old archives' answer".equals(during) && !store.holds(across) && !store.holds(key),
				"an answer read while the store was emptied is handed back but not kept, and the drop took the rest");

		WorkspaceSession one = new WorkspaceSession(new File("ws-one"), new File("game-one"), GameType.ORAS, null);
		WorkspaceSession two = new WorkspaceSession(new File("ws-two"), new File("game-two"), GameType.ORAS, null);
		one.cache().get(key, () -> "game one's");
		check(one.cache() == one.cache() && one.cache() != two.cache() && !two.cache().holds(key),
				"each game has its own store, the same one every time it is asked - a repoint is another store");
		one.reloadGARC(ctrmap.gamedef.ArchiveType.ZONE_DATA);
		check(!one.cache().holds(key),
				"re-reading an archive - what a pack, the builder and a zone removal do after rewriting one - empties the session's store");
		one.cache().get(key, () -> "game one's, again");
		one.archivesRewritten();
		check(!one.cache().holds(key), "and so does re-reading every archive after a restore from the backup");

		//the facility editor's labels name the game open when it is built, not the one open when the class loaded
		java.lang.reflect.Method labels = ctrmap.humaninterface.MaisonClassListDialog.class.getDeclaredMethod("tableNames");
		labels.setAccessible(true);
		Workspace.reset();
		String[] none = (String[]) labels.invoke(null);
		Workspace.install(new WorkspaceSession(new File("ws-labels"), new File("game-labels"), GameType.ORAS, null));
		String[] open = (String[]) labels.invoke(null);
		Workspace.reset();
		String path = ctrmap.gamedef.GameProfile.of(GameType.ORAS).archivePath(ctrmap.gamedef.ArchiveType.MAISON_CLASS_LIST_A);
		check(none[0].contains("?") && path != null && open[0].contains(path) && !none[0].equals(open[0]),
				"the facility tables' labels name the game open when they are asked for: \"" + none[0] + "\" with none, \""
				+ open[0] + "\" with one");
	}

	private static final Object NOT_CONSTRUCTIBLE = new Object();

	/** A value distinguishable from {@code current}, or NOT_CONSTRUCTIBLE. */
	private static Object dirtyValue(Field f, Object current) {
		Class<?> t = f.getType();
		if (t == String.class) {
			return "dirty";
		}
		if (t == boolean.class) {
			return !((Boolean) current);
		}
		if (t == int.class) {
			return ((Integer) current) + 1;
		}
		if (t == File.class) {
			return new File("dirty");
		}
		if (t == String[].class) {
			return new String[]{"dirty"};
		}
		if (t.isEnum()) {
			Object[] vs = t.getEnumConstants();
			return vs.length > 0 && vs[0] != current ? vs[0] : NOT_CONSTRUCTIBLE;
		}
		return NOT_CONSTRUCTIBLE;
	}

	/** A method's text, from its signature to its matching close brace. */
	static String methodBody(String source, String signature) {
		int at = source.indexOf(signature);
		if (at < 0) {
			return null;
		}
		int i = at + signature.length();
		int depth = 1;
		while (i < source.length() && depth > 0) {
			char c = source.charAt(i);
			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
			}
			i++;
		}
		return source.substring(at, i);
	}
}
