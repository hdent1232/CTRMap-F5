package ctrmap;

import ctrmap.Workspace.ArchiveType;
import ctrmap.Workspace.GameType;
import ctrmap.Workspace.PackProgress;
import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.GameProfile;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One opened game: the two folders it was opened from, the game detected in
 * them, every archive that game locates and the handles on the ones that are
 * open, and the list of extracted files marked as edited.
 *
 * <p>Headless by construction. It builds no window, asks the user nothing,
 * keeps nothing in a static, and never reads a static of {@link Workspace} -
 * {@code ctrmap.tests.WorkspaceSessionTest} holds it to all four. Everything
 * that needs a dialog (validation errors, the pack progress bar, a backup
 * that could not be taken) stays in Workspace, which owns the application's
 * CURRENT session and delegates its old statics to it.
 *
 * <p>Made in one of two ways. {@link #open} is the transaction the
 * application performs when a workspace is validated: probe the game folder,
 * check that every archive the game needs is there, open them all, and
 * either return a session that is whole or throw with every problem found -
 * nothing in between, and nothing global touched either way. The
 * constructor takes the parts as they are, for a caller (a suite, mostly)
 * that already has them.
 *
 * <p>A caller handed a session can be handed a different one. That is the
 * whole point: a decision that takes its session as a parameter can be
 * exercised against the pristine dump in a plain JVM, with no scratch
 * workspace built by hand and nothing left behind for the next suite.
 */
public final class WorkspaceSession {

	/** Every problem {@link #open} found, in the words the user is shown. */
	public static final class OpenFailed extends Exception {

		private final List<String> problems;

		OpenFailed(List<String> problems) {
			super(String.join("\n", problems));
			this.problems = Collections.unmodifiableList(new ArrayList<>(problems));
		}

		public List<String> problems() {
			return problems;
		}
	}

	/** An archive every supported game must have, and the name it is reported missing under. */
	private static final Object[][] REQUIRED = {
		{ArchiveType.AREA_DATA, "AreaData"},
		{ArchiveType.FIELD_DATA, "FieldData"},
		{ArchiveType.MAP_MATRIX, "MapMatrix"},
		{ArchiveType.GAMETEXT, "GameText"},
		{ArchiveType.ZONE_DATA, "ZoneData"},
		{ArchiveType.BUILDING_MODELS, "BuildingModels"},
		{ArchiveType.NPC_REGISTRIES, "NPCRegistries"},
		{ArchiveType.MOVE_MODELS, "MoveModels"}
	};

	/**
	 * Archives opened with compression sniffing DISABLED: their entries are
	 * always raw and may legitimately start with 0x11. ORAS-only and optional
	 * - older partial dumps lack them.
	 */
	private static final ArchiveType[] SNIFF_OFF = {
		ArchiveType.TRAINER_DATA, ArchiveType.TRAINER_CLASS, ArchiveType.TRAINER_POKE,
		ArchiveType.MAISON_SET_POOL_A, ArchiveType.MAISON_CLASS_LIST_A,
		ArchiveType.MAISON_SET_POOL_B, ArchiveType.MAISON_CLASS_LIST_B, ArchiveType.MAISON_SET_POOL_C
	};

	/** The extraction directory of each archive that has one, under the workspace folder. */
	private static final EnumMap<ArchiveType, String> SUBDIR = new EnumMap<>(ArchiveType.class);

	static {
		SUBDIR.put(ArchiveType.AREA_DATA, "areadata");
		SUBDIR.put(ArchiveType.FIELD_DATA, "fielddata");
		SUBDIR.put(ArchiveType.TRAINER_DATA, "trdata");
		SUBDIR.put(ArchiveType.TRAINER_CLASS, "trclass");
		SUBDIR.put(ArchiveType.TRAINER_POKE, "trpoke");
		SUBDIR.put(ArchiveType.MAISON_SET_POOL_A, "maison_setA");
		SUBDIR.put(ArchiveType.MAISON_CLASS_LIST_A, "maison_listA");
		SUBDIR.put(ArchiveType.MAISON_SET_POOL_B, "maison_setB");
		SUBDIR.put(ArchiveType.MAISON_CLASS_LIST_B, "maison_listB");
		SUBDIR.put(ArchiveType.MAISON_SET_POOL_C, "maison_setC");
		SUBDIR.put(ArchiveType.MAP_MATRIX, "mapmatrix");
		SUBDIR.put(ArchiveType.GAMETEXT, "gametext");
		SUBDIR.put(ArchiveType.STORYTEXT, "storytext");
		SUBDIR.put(ArchiveType.ZONE_DATA, "zonedata");
		SUBDIR.put(ArchiveType.BUILDING_MODELS, "buildingmodels");
		SUBDIR.put(ArchiveType.NPC_REGISTRIES, "npcregistries");
		SUBDIR.put(ArchiveType.MOVE_MODELS, "movemodels");
	}

	/**
	 * Every per-archive extraction directory, in one place. {@link
	 * #prepareDirectories} creates all of them and {@link #cleanAll} empties
	 * all of them; when the two lists drifted apart, the directories only the
	 * second one knew about were never created and the editors that wrote
	 * into them failed silently. {@code _original_garcs} is deliberately
	 * absent - it is the pristine backup and must survive cleaning.
	 */
	public static final String[] WORKSPACE_SUBDIRS = {
		"areadata", "fielddata", "mapmatrix", "gametext", "storytext", "zonedata",
		"buildingmodels", "npcregistries", "movemodels",
		"trdata", "trclass", "trpoke",
		"maison_setA", "maison_listA", "maison_setB", "maison_listB", "maison_setC",
		"temp"
	};

	/**
	 * The order {@link #reloadGARC} walks. It reloads the archive asked for
	 * AND every one after it in this list. That is the original switch's
	 * missing {@code break}s, kept on purpose: {@link #packArchives} never
	 * asks for the trainer or Maison archives by name, and only their reload
	 * on the way through here keeps those handles current after a pack.
	 */
	private static final ArchiveType[] RELOAD_ORDER = {
		ArchiveType.AREA_DATA, ArchiveType.BUILDING_MODELS, ArchiveType.FIELD_DATA, ArchiveType.GAMETEXT,
		ArchiveType.MAP_MATRIX, ArchiveType.MOVE_MODELS, ArchiveType.NPC_REGISTRIES, ArchiveType.ZONE_DATA,
		ArchiveType.STORYTEXT,
		ArchiveType.TRAINER_DATA, ArchiveType.TRAINER_CLASS, ArchiveType.TRAINER_POKE,
		ArchiveType.MAISON_SET_POOL_A, ArchiveType.MAISON_CLASS_LIST_A, ArchiveType.MAISON_SET_POOL_B,
		ArchiveType.MAISON_CLASS_LIST_B, ArchiveType.MAISON_SET_POOL_C
	};

	private final File workspaceDir;
	private final File gameDir;
	private final GameType game;
	private final GameProfile profile;
	/** Every archive this game locates, on disk or not. */
	private final EnumMap<ArchiveType, File> archiveFiles = new EnumMap<>(ArchiveType.class);
	/** Open handles. STORYTEXT is absent until first asked for - see {@link #getStoryTextGARC}. */
	private final EnumMap<ArchiveType, GARC> archives = new EnumMap<>(ArchiveType.class);
	private final File temp;
	private final File persistConfig;
	/** Absolute paths of extracted files marked as edited - the ones a pack writes back. */
	private final ArrayList<String> persistPaths;
	/** True for a session whose game folder must never be written; see {@link #readOnly}. */
	private final boolean readOnly;

	/**
	 * A session from its parts, checked against nothing. {@link #open} is the
	 * way the application makes one; this is for a caller that already knows
	 * the game and holds whatever archives it needs (possibly none).
	 *
	 * @param archives handles to hold, by archive; null for none
	 */
	public WorkspaceSession(File workspaceDir, File gameDir, GameType game, Map<ArchiveType, GARC> archives) {
		this(workspaceDir, gameDir, game, archives, new ArrayList<String>(), false);
	}

	private WorkspaceSession(File workspaceDir, File gameDir, GameType game, Map<ArchiveType, GARC> archives,
			ArrayList<String> persistPaths, boolean readOnly) {
		this.workspaceDir = workspaceDir;
		this.gameDir = gameDir;
		this.game = game;
		this.profile = GameProfile.of(game);
		this.persistPaths = persistPaths;
		this.readOnly = readOnly;
		for (ArchiveType t : ArchiveType.values()) {
			String rel = profile.archivePath(t);
			if (rel != null) {
				archiveFiles.put(t, new File(gameDir.getPath() + rel));
			}
		}
		if (archives != null) {
			this.archives.putAll(archives);
		}
		temp = new File(workspaceDir.getPath() + "/temp");
		persistConfig = new File(workspaceDir.getPath() + "/ctrmap_persist.txt");
	}

	/**
	 * A session like this one holding a different handle for one archive
	 * (null drops it), sharing this one's edited-file list. For a suite that
	 * needs one archive to misbehave while everything else stays as opened.
	 */
	public WorkspaceSession withArchive(ArchiveType type, GARC handle) {
		EnumMap<ArchiveType, GARC> handles = new EnumMap<>(archives);
		if (handle == null) {
			handles.remove(type);
		} else {
			handles.put(type, handle);
		}
		return new WorkspaceSession(workspaceDir, gameDir, game, handles, persistPaths, readOnly);
	}

	/**
	 * This session, marked as one whose game folder must never be written -
	 * a suite reading the pristine dump, which every other suite reads too.
	 * {@link #packArchives} refuses; {@link Workspace#packWorkspace} leaves
	 * it alone. Nothing the application opens is read-only.
	 */
	public WorkspaceSession readOnly() {
		return new WorkspaceSession(workspaceDir, gameDir, game, archives, persistPaths, true);
	}

	public boolean isReadOnly() {
		return readOnly;
	}

	/**
	 * Opens a game: the transaction behind {@link Workspace#validate}.
	 *
	 * <p>Reads only. The workspace folder is not created or written to (that
	 * is {@link #prepareDirectories}), and the pristine backup is not taken
	 * (that is {@link #snapshotOriginals}); both are the caller's to do once
	 * it has decided to keep what came back.
	 *
	 * @throws OpenFailed with every problem found - folders missing, the game
	 * unrecognised or unsupported, archives it needs absent - and nothing
	 * opened. Archives are checked only once the game is known, so the list
	 * is about the folder given, never about some other game's layout.
	 */
	public static WorkspaceSession open(File workspaceDir, File gameDir) throws OpenFailed {
		List<String> errors = new ArrayList<>();
		if (workspaceDir == null) {
			errors.add("Workspace path not set");
		} else if (!workspaceDir.exists()) {
			errors.add("Workspace path not found");
		}
		GameType game = null;
		if (gameDir == null) {
			errors.add("Game directory path not set");
		} else if (!gameDir.exists()) {
			errors.add("Game directory path not found");
		} else {
			//detect the game by each profile's probe file (gamedef)
			GameProfile detected = GameProfile.detect(gameDir);
			if (detected == null) {
				errors.add("Could not detect game version");
			} else if (!detected.supports(GameProfile.Feature.H3D_MAPS)) {
				errors.add(detected.displayName() + " detected - this game is not supported yet");
			} else {
				game = detected.type();
			}
		}
		if (game != null) {
			GameProfile profile = GameProfile.of(game);
			for (Object[] r : REQUIRED) {
				//STORYTEXT deliberately has no existence check: lazy-loaded on demand
				if (!new File(gameDir.getPath() + profile.archivePath((ArchiveType) r[0])).exists()) {
					errors.add(r[1] + " GARC not found");
				}
			}
		}
		if (!errors.isEmpty()) {
			throw new OpenFailed(errors);
		}
		WorkspaceSession s = new WorkspaceSession(workspaceDir, gameDir, game, null);
		s.openArchives();
		s.readPersist();
		return s;
	}

	private void openArchives() {
		archives.clear(); //STORYTEXT stays lazy: re-read from this game folder when asked for
		for (Object[] r : REQUIRED) {
			ArchiveType t = (ArchiveType) r[0];
			archives.put(t, new GARC(archiveFiles.get(t)));
		}
		for (ArchiveType t : SNIFF_OFF) {
			File f = archiveFiles.get(t);
			if (f != null && f.exists()) {
				archives.put(t, new GARC(f, false));
			}
		}
	}

	/** The persisted-paths file, if the workspace has one. Written paths are workspace-relative. */
	private void readPersist() {
		persistPaths.clear();
		if (!persistConfig.exists()) {
			return;
		}
		try {
			Scanner scanner = new Scanner(persistConfig);
			scanner.useDelimiter("\n"); //for better crossplatformness, force the Linux endline everywhere
			while (scanner.hasNextLine()) {
				persistPaths.add(workspaceDir.getPath() + scanner.nextLine());
			}
			scanner.close();
		} catch (IOException ex) {
			Logger.getLogger(WorkspaceSession.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	/**
	 * Creates every extraction directory. {@link #getWorkspaceFile} opens a
	 * FileOutputStream without mkdirs, so a missing directory does not throw
	 * - it logs at SEVERE and returns a File that was never written, which is
	 * how the trainer and Maison editors used to fail silently on a brand-new
	 * workspace.
	 */
	public void prepareDirectories() {
		Utils.mkDirsIfNotContains(workspaceDir, WORKSPACE_SUBDIRS);
	}

	/** Writes the persisted-paths file, relative to the workspace so it can move between machines. */
	public void savePersist() {
		try {
			persistConfig.delete();
			persistConfig.createNewFile();
			BufferedWriter writer = new BufferedWriter(new FileWriter(persistConfig));
			for (String line : persistPaths) {
				writer.write(line.replace(workspaceDir.getPath(), "") + "\n");
			}
			writer.close();
		} catch (IOException ex) {
			Logger.getLogger(WorkspaceSession.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	// ------------------------------------------------------------ identity

	public File workspaceDir() {
		return workspaceDir;
	}

	public File gameDir() {
		return gameDir;
	}

	public GameType game() {
		return game;
	}

	/** The game's profile (paths, text indices, feature gates). */
	public GameProfile profile() {
		return profile;
	}

	public boolean isOA() {
		return game == GameType.ORAS;
	}

	public boolean isXY() {
		return game == GameType.XY;
	}

	public boolean isOADemo() {
		return new File(gameDir.getPath() + ctrmap.gamedef.OrasProfile.DEMO_PROBE).exists();
	}

	/** Scratch directory inside the workspace. */
	public File temp() {
		return temp;
	}

	// ------------------------------------------------------------ archives

	/** Where this game keeps an archive, or null when the game lacks it (or its location is not yet verified). */
	public File archiveFile(ArchiveType type) {
		return archiveFiles.get(type);
	}

	/** The open handle, or null: the game lacks the archive, or the dump does. */
	public GARC getArchive(ArchiveType type) {
		if (type == ArchiveType.STORYTEXT) {
			return getStoryTextGARC();
		}
		return archives.get(type);
	}

	/**
	 * Lazily opens the STORYTEXT GARC (dialogue text; one GFMessageFile per
	 * ZoneHeader.textID). Unlike GAMETEXT it is not opened with the rest
	 * because of its size (637 files on ORAS).
	 *
	 * @return the storytext GARC, or null if the archive file does not exist
	 */
	public GARC getStoryTextGARC() {
		GARC g = archives.get(ArchiveType.STORYTEXT);
		if (g == null) {
			File f = archiveFiles.get(ArchiveType.STORYTEXT);
			if (f != null && f.exists()) {
				g = new GARC(f);
				archives.put(ArchiveType.STORYTEXT, g);
			}
		}
		return g;
	}

	/** Re-reads an archive's entry table from disk - and every archive after it in {@link #RELOAD_ORDER}. */
	public void reloadGARC(ArchiveType arc) {
		int start = -1;
		for (int i = 0; i < RELOAD_ORDER.length; i++) {
			if (RELOAD_ORDER[i] == arc) {
				start = i;
			}
		}
		if (start < 0) {
			return;
		}
		for (int i = start; i < RELOAD_ORDER.length; i++) {
			ArchiveType t = RELOAD_ORDER[i];
			GARC g = archives.get(t);
			if (g != null) {
				archives.put(t, sniffs(t) ? new GARC(g.file) : new GARC(g.file, false));
			}
		}
	}

	private static boolean sniffs(ArchiveType t) {
		for (ArchiveType off : SNIFF_OFF) {
			if (off == t) {
				return false;
			}
		}
		return true;
	}

	// ------------------------------------------------------------ extraction

	/** Where one archive's entries are extracted to, under a workspace folder; null for an archive nothing extracts. */
	public static File extractionDirectory(File workspaceDir, ArchiveType type) {
		String sub = SUBDIR.get(type);
		if (sub == null) {
			return null;
		}
		return new File(workspaceDir.getPath() + "/" + sub + "/");
	}

	public File getExtractionDirectory(ArchiveType type) {
		return extractionDirectory(workspaceDir, type);
	}

	/**
	 * The extracted copy of one entry, extracting it first when the
	 * workspace does not hold it yet. Null when the archive has no such entry.
	 */
	public File getWorkspaceFile(ArchiveType arc, int fileNum) {
		File wsFile;
		wsFile = new File(getExtractionDirectory(arc).getAbsolutePath() + "/" + fileNum);
		if (!wsFile.exists() && getArchive(arc).length > fileNum) {
			try {
				OutputStream os = new FileOutputStream(wsFile);
				byte[] b = getArchive(arc).getDecompressedEntry(fileNum);
				if (b == null) {
					os.close();
					return null;
				}
				os.write(b);
				os.flush();
				os.close();
			} catch (IOException ex) {
				Logger.getLogger(WorkspaceSession.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
		return wsFile;
	}

	/** Marks an extracted file as edited, so the next pack writes it back. */
	public void addPersist(File f) {
		if (!persistPaths.contains(f.getAbsolutePath())) {
			persistPaths.add(f.getAbsolutePath());
		}
	}

	/** True when an extracted file is marked as edited - what a pack writes back. */
	public boolean isPersisted(File f) {
		return persistPaths.contains(f.getAbsolutePath());
	}

	/** The live list of edited files' absolute paths. */
	public List<String> persistPaths() {
		return persistPaths;
	}

	private boolean hasPersistedFiles(File dir) {
		File[] files = dir.listFiles();
		if (files == null) {
			return false;
		}
		for (File f : files) {
			if (persistPaths.contains(f.getAbsolutePath())) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------ cleaning

	/** Empties every extraction directory, edited files included, and forgets the edits. */
	public void cleanAll() {
		cleanDirectories(workspaceDir, true, persistPaths);
		persistPaths.clear();
	}

	/** Empties every extraction directory of everything NOT marked edited. */
	public void cleanUnchanged() {
		cleanDirectories(workspaceDir, false, persistPaths);
	}

	/** Empties every extraction directory under a workspace folder; {@code temp} always entirely. */
	public static void cleanDirectories(File workspaceDir, boolean deletePersistent, Collection<String> persisted) {
		for (String dir : WORKSPACE_SUBDIRS) {
			cleanDirectory(workspaceDir.getPath() + "/" + dir, "temp".equals(dir) || deletePersistent, persisted);
		}
	}

	public static void cleanDirectory(String dir, boolean deletePersistent, Collection<String> persisted) {
		File[] files = new File(dir).listFiles();
		if (files == null || files.length == 0) {
			return;
		}
		for (int i = 0; i < files.length; i++) {
			if (!deletePersistent) {
				if (persisted.contains(files[i].getAbsolutePath())) {
					continue;
				}
			}
			files[i].delete();
		}
	}

	// ------------------------------------------------------------ the pristine backup

	/** Directory holding the one-time pristine copy of the moddable archives,
	 *  used by {@link ModDeployer} to ship only what actually changed. */
	public static File originalSnapshotDir(File workspaceDir) {
		return new File(workspaceDir.getPath() + "/_original_garcs");
	}

	/** Records which game folder a pristine snapshot was taken from. */
	public static File originalSnapshotStamp(File workspaceDir) {
		return new File(originalSnapshotDir(workspaceDir), "taken-from.txt");
	}

	/**
	 * The game folder a workspace's pristine snapshot was taken from, or null
	 * when there is no snapshot or it predates stamping.
	 */
	public static String snapshotSourcePath(File workspaceDir) {
		File stamp = originalSnapshotStamp(workspaceDir);
		if (!stamp.isFile()) {
			return null;
		}
		try (Scanner sc = new Scanner(stamp, "UTF-8")) {
			while (sc.hasNextLine()) {
				String line = sc.nextLine().trim();
				if (line.startsWith("gamedir=")) {
					return line.substring("gamedir=".length()).trim();
				}
			}
		} catch (IOException ex) {
			//unreadable stamp is treated as absent
		}
		return null;
	}

	/**
	 * True when a workspace already holds a pristine snapshot of a DIFFERENT
	 * game folder than the one given.
	 *
	 * <p>This matters more than it looks. The snapshot is what "ship only what I
	 * changed" diffs against, and what the building palette, the atmosphere
	 * picker and the Maison guard read pristine data from. It is copied once and
	 * never refreshed, and cleaning a workspace does not remove it - so pointing
	 * an existing workspace at a second dump silently keeps the first dump's
	 * backup forever, and every one of those consumers is quietly wrong from
	 * then on with nothing to indicate it.
	 */
	public static boolean snapshotIsForeign(File workspaceDir, String gameDir) {
		String taken = snapshotSourcePath(workspaceDir);
		if (taken == null || gameDir == null) {
			return false;
		}
		try {
			return !new File(taken).getCanonicalPath()
					.equalsIgnoreCase(new File(gameDir).getCanonicalPath());
		} catch (IOException ex) {
			return !taken.equalsIgnoreCase(gameDir);
		}
	}

	/** Deletes the pristine snapshot so the next load re-takes it from the current game folder. */
	public static void discardSnapshot(File workspaceDir) {
		deleteTree(originalSnapshotDir(workspaceDir));
	}

	private static void deleteTree(File f) {
		if (f == null || !f.exists()) {
			return;
		}
		File[] kids = f.listFiles();
		if (kids != null) {
			for (File k : kids) {
				deleteTree(k);
			}
		}
		f.delete();
	}

	public File originalSnapshotDir() {
		return originalSnapshotDir(workspaceDir);
	}

	public File originalSnapshotStamp() {
		return originalSnapshotStamp(workspaceDir);
	}

	public String snapshotSourcePath() {
		return snapshotSourcePath(workspaceDir);
	}

	public boolean snapshotIsForeign() {
		return snapshotIsForeign(workspaceDir, gameDir.getPath());
	}

	public void discardSnapshot() {
		discardSnapshot(workspaceDir);
	}

	/**
	 * Archives the snapshot is supposed to hold but does not.
	 *
	 * <p>Non-empty means the snapshot is PARTIAL and must not be trusted as a
	 * record of the retail game: the missing archives were never captured, and
	 * by the time anyone noticed, the live copies had been edited.
	 */
	public List<String> snapshotMissingArchives() {
		List<String> missing = new ArrayList<>();
		File snap = originalSnapshotDir();
		for (ArchiveType t : ModDeployer.MODDABLE) {
			File live = archiveFiles.get(t);
			if (live == null) {
				continue;
			}
			String rel = profile.archivePath(t);
			if (live.exists() && !new File(snap.getAbsolutePath() + rel).exists()) {
				missing.add(rel);
			}
		}
		return missing;
	}

	/** What {@link #snapshotOriginals} found, for whoever decides how to tell the user. */
	public static final class SnapshotReport {

		/**
		 * Archives the game has and the snapshot still lacks, left uncaptured
		 * because the snapshot was already established - see
		 * {@link #snapshotOriginals} for why they are never filled in.
		 */
		public final List<String> refused = new ArrayList<>();
		/** The game folder the existing backup was taken from, when that is another folder than this session's; else null. */
		public String foreignTakenFrom;
		/** What stopped the copy, or null when it ran to the end. */
		public Exception failure;
		public File snapshotDir;
	}

	/**
	 * Copies the retail archives aside, once, so edits can be diffed against
	 * what the game shipped.
	 *
	 * <p>Capture happens ONLY while the snapshot is still being established -
	 * that is, before the stamp exists. Once stamped, a missing archive is left
	 * missing and reported, never filled in.
	 *
	 * <p>That restriction is the whole point. This used to copy any archive it
	 * found absent, on every load, which sounds harmless and is not: the
	 * snapshot is built archive by archive, so one added to the moddable list
	 * later - or one lost from the folder - was captured from a game the user
	 * had already been editing for weeks, and recorded as pristine. Six of the
	 * archives in the author's own workspace were contaminated exactly this
	 * way, and the stamp still said the snapshot was legitimate, so donors were
	 * cut from edited maps and the edits compounded.
	 *
	 * <p>Leaving the gap open is the safe failure. Consumers refuse to work
	 * from an absent snapshot ({@code BuildingCatalog.pristineRegion}); none of
	 * them can detect a present-but-wrong one.
	 *
	 * <p>Stamps which game folder it copied from, so {@link #snapshotIsForeign}
	 * can catch a workspace that was later pointed at a different dump. A
	 * snapshot taken before stamping existed has no stamp; it is left alone and
	 * stamped in place rather than re-taken, because re-taking it against
	 * already-edited archives would bake the edits in as "pristine".
	 *
	 * <p>Fully guarded: a snapshot failure must never break loading a
	 * workspace. It is reported in the returned {@link SnapshotReport}, which
	 * {@link Workspace} puts in front of the user; nothing here can show a
	 * dialog.
	 */
	public SnapshotReport snapshotOriginals() {
		SnapshotReport report = new SnapshotReport();
		try {
			File snap = originalSnapshotDir();
			report.snapshotDir = snap;
			boolean established = originalSnapshotStamp().isFile();
			//A backup of one game folder says nothing about another. Repointing
			//a workspace at a second dump keeps the first one's backup - the
			//clean deliberately spares it - so Deploy diffs the new game
			//against the old one and ships archives nobody touched, and every
			//donor the palette cuts comes from the wrong game. The check for
			//this was written, with a javadoc describing exactly this failure,
			//and never called from anywhere.
			//read the stamp ONCE: this decided from one read of it and worded the
			//message from another, so a warning that only fires when the recorded
			//path is known still managed to print "null" as that path. Whatever
			//the two reads disagreed about, the user was shown a folder name that
			//was not the reason they were being warned.
			String takenFrom = snapshotSourcePath();
			if (established && takenFrom != null && snapshotIsForeign()) {
				report.foreignTakenFrom = takenFrom;
			}
			for (ArchiveType t : ModDeployer.MODDABLE) {
				File live = archiveFiles.get(t);
				if (live == null) {
					continue;
				}
				String rel = profile.archivePath(t);
				File dst = new File(snap.getAbsolutePath() + rel);
				if (live.exists() && !dst.exists()) {
					if (established) {
						//the game has been in use since this snapshot was taken;
						//whatever is in the live archive now is not evidence of
						//what shipped
						report.refused.add(rel);
						continue;
					}
					if (dst.getParentFile() != null) {
						dst.getParentFile().mkdirs();
					}
					java.nio.file.Files.copy(live.toPath(), dst.toPath());
				}
			}
			if (snap.isDirectory() && !originalSnapshotStamp().isFile()) {
				try (java.io.PrintWriter pw = new java.io.PrintWriter(
						originalSnapshotStamp(), "UTF-8")) {
					pw.println("# The game folder this pristine backup was copied from.");
					pw.println("# CTRMap compares your edits against it to ship only what changed.");
					pw.println("gamedir=" + gameDir.getAbsolutePath());
					pw.println("game=" + game);
				}
			}
		} catch (Exception ex) {
			report.failure = ex;
		}
		return report;
	}

	// ------------------------------------------------------------ packing

	/**
	 * Packs every edited archive back into the game directory, in one fixed
	 * order, and reloads them. Synchronous and headless: the app runs it on a
	 * worker and the tests run it directly, so the two cannot pack differently.
	 *
	 * <p>Throws on the first archive that cannot be rewritten - the emulator
	 * or a virus scanner holding it open is the usual cause. Nothing is lost:
	 * the edits stay staged in the workspace and marked pending, so the next
	 * pack carries them. The failure used to be swallowed inside the archive
	 * writer, and the progress bar filled while the game kept the old map.
	 *
	 * <p>Returns everything the pack found wrong, as sentences meant for the
	 * user: a cross-archive reference that will stop a zone loading, an archive
	 * a second program is writing to, a pristine backup with holes in it. All
	 * three used to be printed to stderr, and the shipped build has no console,
	 * so a pack that could see the game was broken still ended in the success
	 * dialog alone.
	 */
	public List<String> packArchives(PackProgress progress) throws IOException {
		if (readOnly) {
			throw new IllegalStateException("this session is read-only: " + gameDir + " must not be written");
		}
		//leftovers from a pack that threw are not this pack's news
		GARC.drainPackWarnings();
		progress.at(0, "Packing - fielddata");
		//a pending geometry fork appends private region copies (see GeometryForker)
		getArchive(ArchiveType.FIELD_DATA).packDirectory(getExtractionDirectory(ArchiveType.FIELD_DATA), this::isPersisted, workspaceDir, GeometryForker.consumePendingFieldOverrides());
		progress.at(30, "Packing - areadata");
		//a pending area fork appends a private area copy (see AreaForker)
		getArchive(ArchiveType.AREA_DATA).packDirectory(getExtractionDirectory(ArchiveType.AREA_DATA), this::isPersisted, workspaceDir, AreaForker.consumePendingAreaOverrides());
		progress.at(60, "Packing - zonedata");
		//a pending zone append needs its compression overrides exactly once (see ZoneAppender)
		getArchive(ArchiveType.ZONE_DATA).packDirectory(getExtractionDirectory(ArchiveType.ZONE_DATA), this::isPersisted, workspaceDir, ZoneAppender.consumePendingZoneDataOverrides());
		progress.at(65, "Packing - mapmatrix");
		//a pending geometry fork appends a rewired matrix (see GeometryForker)
		getArchive(ArchiveType.MAP_MATRIX).packDirectory(getExtractionDirectory(ArchiveType.MAP_MATRIX), this::isPersisted, workspaceDir, GeometryForker.consumePendingMatrixOverrides());
		progress.at(70, "Packing - buildingmodels");
		getArchive(ArchiveType.BUILDING_MODELS).packDirectory(getExtractionDirectory(ArchiveType.BUILDING_MODELS), this::isPersisted, workspaceDir);
		progress.at(90, "Packing - npcregistries");
		//an area fork appends the matching registry entry (indexed by area id)
		getArchive(ArchiveType.NPC_REGISTRIES).packDirectory(getExtractionDirectory(ArchiveType.NPC_REGISTRIES), this::isPersisted, workspaceDir, AreaForker.consumePendingNpcRegOverrides());
		progress.at(95, "Packing - trainers");
		//trainer archives: pack only when actually edited (rewriting them
		//without edits would still be byte-faithful, but skip the churn)
		GARC trdata = getArchive(ArchiveType.TRAINER_DATA);
		if (trdata != null && hasPersistedFiles(getExtractionDirectory(ArchiveType.TRAINER_DATA))) {
			trdata.packDirectory(getExtractionDirectory(ArchiveType.TRAINER_DATA), this::isPersisted, workspaceDir);
		}
		GARC trpoke = getArchive(ArchiveType.TRAINER_POKE);
		if (trpoke != null && hasPersistedFiles(getExtractionDirectory(ArchiveType.TRAINER_POKE))) {
			trpoke.packDirectory(getExtractionDirectory(ArchiveType.TRAINER_POKE), this::isPersisted, workspaceDir);
		}
		//Battle Maison opponent pools/lists (edited-only)
		for (ArchiveType mt : new ArchiveType[]{ArchiveType.MAISON_SET_POOL_A, ArchiveType.MAISON_CLASS_LIST_A,
			ArchiveType.MAISON_SET_POOL_B, ArchiveType.MAISON_CLASS_LIST_B, ArchiveType.MAISON_SET_POOL_C}) {
			GARC mg = getArchive(mt);
			if (mg != null && hasPersistedFiles(getExtractionDirectory(mt))) {
				mg.packDirectory(getExtractionDirectory(mt), this::isPersisted, workspaceDir);
			}
		}
		progress.at(95, "Packing - gametext");
		//packDirectory rewrites the GARC in the game directory even with zero persisted files - only pack when text was actually edited
		if (hasPersistedFiles(getExtractionDirectory(ArchiveType.GAMETEXT))) {
			getArchive(ArchiveType.GAMETEXT).packDirectory(getExtractionDirectory(ArchiveType.GAMETEXT), this::isPersisted, workspaceDir);
		}
		progress.at(97, "Packing - storytext");
		//storytext is lazy-loaded and huge - only pack when dialogue was actually edited
		GARC storyGarc = getStoryTextGARC();
		if (storyGarc != null && hasPersistedFiles(getExtractionDirectory(ArchiveType.STORYTEXT))) {
			storyGarc.packDirectory(getExtractionDirectory(ArchiveType.STORYTEXT), this::isPersisted, workspaceDir);
			reloadGARC(ArchiveType.STORYTEXT);
		}
		progress.at(100, "Done, updating GARCs");
		//the GARC indices may have changed and as such we need to reload them
		reloadGARC(ArchiveType.AREA_DATA);
		reloadGARC(ArchiveType.FIELD_DATA);
		reloadGARC(ArchiveType.ZONE_DATA);
		reloadGARC(ArchiveType.MAP_MATRIX);
		reloadGARC(ArchiveType.BUILDING_MODELS);
		reloadGARC(ArchiveType.NPC_REGISTRIES);
		reloadGARC(ArchiveType.GAMETEXT);
		//the session prop database was built from the pre-pack GARCs;
		//drop it so the next palette use rebuilds from the fresh archives
		ctrmap.formats.propdata.PropDatabase.invalidate();
		//The archives now on disk are what the game will load. Cross-
		//archive references are by bare index and nothing else checks
		//them, so an operation that grew one archive and not another
		//leaves a dangling index that only shows up much later, as a
		//zone that will not load. Check here, while the edit that
		//caused it is still the last thing that happened.
		List<String> warnings = new ArrayList<>();
		warnings.addAll(WorkspaceIntegrity.report("packing the workspace"));
		warnings.addAll(GARC.drainPackWarnings());
		List<String> missing = snapshotMissingArchives();
		if (!missing.isEmpty()) {
			warnings.add("the pristine backup in " + originalSnapshotDir() + " is missing "
					+ missing.size() + " archive(s): " + missing + ". They will NOT be captured"
					+ " from the game as it is now, which may already be edited - so Deploy"
					+ " cannot tell what you changed in them, and donor buildings cut from them"
					+ " are unavailable. Delete the backup folder and reload against an"
					+ " unmodified game to retake it whole.");
		}
		return warnings;
	}

	@Override
	public String toString() {
		return "WorkspaceSession[" + game + " at " + gameDir + ", workspace " + workspaceDir + "]";
	}
}
