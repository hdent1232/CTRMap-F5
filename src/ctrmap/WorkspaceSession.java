package ctrmap;

import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import ctrmap.Workspace.PackProgress;
import ctrmap.formats.GameFiles;
import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.GameProfile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
 *
 * <p>It is also what the format layer is handed. {@link GameFiles} is that
 * layer's own statement of what it needs from an opened game, and this class
 * satisfies it by delegation - each method below is one of the members above
 * under the name the format layer chose. {@link Workspace}, the static facade,
 * deliberately does not implement it.
 */
public final class WorkspaceSession implements GameFiles {
	/**
	 * Makes any of {@code requiredContents} the container does not already
	 * hold. Moved here from {@code ctrmap.Utils} in the package split: this
	 * class was its only caller, and making the workspace's folders is this
	 * class's business.
	 */
	public static void mkDirsIfNotContains(File container, String[] requiredContents) {
		//a folder that is not there, or cannot be listed, is said - never a NullPointerException out
		//of Arrays.asList (found building a game by hand, 2026-10-07) - and not made: a workspace
		//path that names nothing is a setting to fix, not a folder to invent
		String[] held = container.list();
		if (held == null) {
			throw new IllegalStateException("The workspace folder " + container.getAbsolutePath()
					+ " does not exist or cannot be read, so its folders were not made.");
		}
		java.util.List<String> contents = java.util.Arrays.asList(held);
		for (int i = 0; i < requiredContents.length; i++) {
			if (!contents.contains(requiredContents[i])) {
				new File(container.getAbsolutePath() + "/" + requiredContents[i]).mkdir();
			}
		}
	}

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

	/**
	 * EVERY FACT ABOUT AN ARCHIVE THE WORKSPACE HOLDS, ONE ROW EACH (F038). They were six parallel
	 * lists here - the extraction folders twice (a map, and the list of folders to make and empty),
	 * the archives every game must have, the ones opened with compression sniffing off, the reload
	 * order and the pack order - plus a copy of the required list in the setup check and two in the
	 * suites, all agreeing by hand. The folder list's own javadoc recorded what drift between two of
	 * them cost: editors writing into a folder nobody made, failing silently.
	 *
	 * <p>Columns: the archive; its extraction folder; the name it is reported missing under, when
	 * every game must have it (null when optional); whether its entries are sniffed for compression
	 * (false: always raw, and may legitimately start with 0x11); whether a pack writes it back; whether
	 * the pack writes it IN PLACE - fixed-size records over their own bytes, nothing repacked (F082).
	 *
	 * <p>The ORDER is the reload order, and the required archives come first, in the order a missing
	 * one has always been reported. {@link #reloadGARC} reloads the archive asked for and every one
	 * after it, so the trainer and Maison archives - which nothing asks for by name - stay last.
	 */
	private static final Object[][] ARCHIVES = {
		{ArchiveType.AREA_DATA, "areadata", "AreaData", true, true, false},
		{ArchiveType.FIELD_DATA, "fielddata", "FieldData", true, true, false},
		{ArchiveType.MAP_MATRIX, "mapmatrix", "MapMatrix", true, true, false},
		{ArchiveType.GAMETEXT, "gametext", "GameText", true, true, false},
		{ArchiveType.ZONE_DATA, "zonedata", "ZoneData", true, true, false},
		{ArchiveType.BUILDING_MODELS, "buildingmodels", "BuildingModels", true, true, false},
		{ArchiveType.NPC_REGISTRIES, "npcregistries", "NPCRegistries", true, true, false},
		{ArchiveType.MOVE_MODELS, "movemodels", "MoveModels", true, false, false},
		//lazy-loaded on demand and huge: never required, opened only when asked for
		{ArchiveType.STORYTEXT, "storytext", null, true, true, false},
		//ORAS-only and optional - older partial dumps lack them
		{ArchiveType.TRAINER_DATA, "trdata", null, false, true, false},
		{ArchiveType.TRAINER_CLASS, "trclass", null, false, false, false},
		{ArchiveType.TRAINER_POKE, "trpoke", null, false, true, false},
		{ArchiveType.MAISON_SET_POOL_A, "maison_setA", null, false, true, false},
		{ArchiveType.MAISON_CLASS_LIST_A, "maison_listA", null, false, true, false},
		{ArchiveType.MAISON_SET_POOL_B, "maison_setB", null, false, true, false},
		{ArchiveType.MAISON_CLASS_LIST_B, "maison_listB", null, false, true, false},
		{ArchiveType.MAISON_SET_POOL_C, "maison_setC", null, false, true, false},
		//STAGED LIKE EVERY OTHER (F082): an item record was poked straight into the game folder, so a
		//clean reverted its name and kept the record, and a failed save could not be put back. Its
		//records are fixed-size and uncompressed, so the pack writes them over themselves - no table
		//rebuilt - and may begin with 0x11, so nothing sniffs them
		{ArchiveType.ITEM_DATA, "itemdata", null, false, true, true},
		//READ, NEVER WRITTEN: where the shared scripts send the player is part of which zones are reached (F225)
		{ArchiveType.SHARED_SCRIPTS, "sharedscripts", null, true, false, false}
	};

	/** Derived from {@link #ARCHIVES}: each archive's extraction folder. */
	private static final EnumMap<ArchiveType, String> SUBDIR = new EnumMap<>(ArchiveType.class);
	/** Derived from {@link #ARCHIVES}: the archives every game must have, in the order they are reported. */
	private static final List<ArchiveType> REQUIRED = new ArrayList<>();
	/** Derived from {@link #ARCHIVES}: the reload order. */
	private static final ArchiveType[] RELOAD_ORDER = new ArchiveType[ARCHIVES.length];
	/** Derived from {@link #ARCHIVES}: the archives a pack may write, in its order. */
	private static final List<ArchiveType> PACK_ORDER = new ArrayList<>();
	/** Derived from {@link #ARCHIVES}: the archives a pack writes in place rather than repacking. */
	private static final java.util.Set<ArchiveType> IN_PLACE = EnumSet.noneOf(ArchiveType.class);

	/**
	 * Every per-archive extraction directory, derived from {@link #ARCHIVES}, and the workspace's
	 * scratch folder. {@link #prepareDirectories} creates all of them and {@link #cleanAll} empties
	 * all of them. {@code _original_garcs} is deliberately absent - it is the pristine backup and
	 * must survive cleaning.
	 */
	public static final String[] WORKSPACE_SUBDIRS;

	static {
		List<String> dirs = new ArrayList<>();
		for (int i = 0; i < ARCHIVES.length; i++) {
			ArchiveType t = (ArchiveType) ARCHIVES[i][0];
			SUBDIR.put(t, (String) ARCHIVES[i][1]);
			dirs.add((String) ARCHIVES[i][1]);
			if (ARCHIVES[i][2] != null) {
				REQUIRED.add(t);
			}
			RELOAD_ORDER[i] = t;
			if ((Boolean) ARCHIVES[i][4]) {
				PACK_ORDER.add(t);
			}
			if ((Boolean) ARCHIVES[i][5]) {
				IN_PLACE.add(t);
			}
		}
		dirs.add("temp");
		WORKSPACE_SUBDIRS = dirs.toArray(new String[0]);
	}

	/** The archives every supported game must have, in the order a missing one is reported. */
	public static List<ArchiveType> requiredArchives() {
		return Collections.unmodifiableList(REQUIRED);
	}

	/** The name a required archive is reported missing under; its enum name for any other. */
	public static String archiveName(ArchiveType t) {
		for (Object[] row : ARCHIVES) {
			if (row[0] == t && row[2] != null) {
				return (String) row[2];
			}
		}
		return t.name();
	}

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
	/** Set when the list of edits exists and could not be read: see {@link #readPersist}. */
	private boolean editListUnreadable;
	/** Absolute paths of extracted files marked as edited - the ones a pack writes back. */
	private final ArrayList<String> persistPaths;
	/** True for a session whose game folder must never be written; see {@link #readOnly}. */
	private final boolean readOnly;
	/** What has been read from this game and kept: this session's own, emptied by a pack (F218). */
	private final ctrmap.formats.GameCache cache = new ctrmap.formats.GameCache();

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
		persistConfig = persistFile(workspaceDir);
	}

	/** The file in a workspace folder that lists which of its extracted files are the user's edits. */
	public static File persistFile(File workspaceDir) {
		return new File(workspaceDir.getPath() + "/ctrmap_persist.txt");
	}

	/**
	 * The user's edits a workspace folder records, read from its own list, as
	 * absolute paths - for a caller with no session open over that folder.
	 *
	 * <p>THE ONE READER OF THAT LIST. A session reads it through here on open,
	 * and so does anything that has to decide what to delete while no session
	 * is open: closing the window after a game failed to open used to clean the
	 * workspace against an EMPTY list, because the list it had was the open
	 * session's and there was none - and every unpacked edit went with the
	 * scratch files (census H.13).
	 *
	 * <p>A line whose file is gone is not an edit and is dropped, for the
	 * reason {@link #readPersist} gives.
	 *
	 * @throws IOException when the list exists and cannot be read. "Could not
	 *         look" is not "there are none": a caller about to delete must not
	 *         read it as that
	 */
	public static List<String> persistedIn(File workspaceDir) throws IOException {
		List<String> out = new ArrayList<>();
		File list = persistFile(workspaceDir);
		if (!list.exists()) {
			return out;
		}
		String root = workspaceDir.getAbsolutePath();
		//"\n" on every platform (savePersist writes it so); a stray "\r" is tolerated
		for (String line : new String(Files.readAllBytes(list.toPath())).split("\r?\n")) {
			if (line.isEmpty()) {
				continue; //an empty line names the workspace folder itself, which is not an edit
			}
			String path = root + line;
			if (new File(path).exists()) {
				out.add(path);
			}
		}
		return out;
	}

	/**
	 * A session like this one holding a different handle for one archive
	 * (null drops it), sharing this one's edited-file list. For a suite that
	 * needs one archive to misbehave while everything else stays as opened.
	 */
	@ctrmap.util.ForSuites("SEAM: a suite swaps one archive's handle for one that misbehaves, everything else as opened")
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
	@ctrmap.util.ForSuites("SEAM: a suite reading the pristine dump marks its session read-only, so nothing it does can pack into the game folder every suite shares")
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
			for (ArchiveType t : REQUIRED) {
				//STORYTEXT deliberately has no existence check: lazy-loaded on demand
				if (!new File(gameDir.getPath() + profile.archivePath(t)).exists()) {
					errors.add(archiveName(t) + " GARC not found");
				}
			}
		}
		if (!errors.isEmpty()) {
			throw new OpenFailed(errors);
		}
		WorkspaceSession s = new WorkspaceSession(workspaceDir, gameDir, game, null);
		List<String> unreadable = s.openArchives();
		if (!unreadable.isEmpty()) {
			throw new OpenFailed(unreadable);
		}
		s.readPersist();
		return s;
	}

	/**
	 * Opens every archive this session holds.
	 *
	 * <p>A DAMAGED ARCHIVE IS A PROBLEM THE USER IS SHOWN, LIKE A MISSING ONE (F231).
	 * {@link #open} checked only that each file exists; a present archive that is not
	 * a readable GARC threw RuntimeException out of here, past OpenFailed - the one
	 * path that names the problem to the user - and the editor came up with no
	 * workspace and no word about why. Each failure is now collected against the
	 * archive's name; a TRUNCATED archive, which opens with the entries before the
	 * cut, is recorded in {@link #openWarnings} so it is said rather than only logged.
	 *
	 * @return one line per archive that could not be read; empty when all opened
	 */
	private List<String> openArchives() {
		List<String> problems = new ArrayList<>();
		openWarnings.clear();
		archives.clear(); //STORYTEXT stays lazy: re-read from this game folder when asked for
		for (ArchiveType t : REQUIRED) {
			openOne(t, archiveName(t), archiveFiles.get(t), sniffs(t), problems);
		}
		//the optional archives that are opened up front: every one but the lazy story text
		for (ArchiveType t : RELOAD_ORDER) {
			File f = archiveFiles.get(t);
			if (!REQUIRED.contains(t) && t != ArchiveType.STORYTEXT && f != null && f.exists()) {
				openOne(t, t.name(), f, sniffs(t), problems);
			}
		}
		return problems;
	}

	private void openOne(ArchiveType t, String name, File f, boolean sniff, List<String> problems) {
		GARC g;
		try {
			g = sniff ? new GARC(f) : new GARC(f, false);
		} catch (RuntimeException ex) {
			problems.add(name + " GARC could not be read (" + f.getName() + "): " + ctrmap.util.Bytes.reason(ex));
			return;
		}
		if (g.readableEntries >= 0) {
			openWarnings.add(name + " (" + f.getName() + ") is TRUNCATED: " + g.readableEntries + " of its "
					+ g.length + " entries could be read. The rest refuse, and it cannot be packed back"
					+ " until the archive is restored.");
		}
		archives.put(t, g);
	}

	/** What opened but is damaged, as the user is told it: see {@link #openArchives}. */
	public List<String> openWarnings() {
		return Collections.unmodifiableList(openWarnings);
	}

	private final List<String> openWarnings = new ArrayList<>();

	/**
	 * The persisted-paths file, if the workspace has one, through {@link #persistedIn}.
	 * Written paths are workspace-relative; read ones are absolute, because every test of
	 * this list ({@link #isPersisted}, {@link #addPersist}, cleaning) asks it with
	 * {@code getAbsolutePath()}, and a workspace folder named relatively used to load
	 * entries none of those tests could ever match.
	 */
	private void readPersist() {
		persistPaths.clear();
		//A LINE WHOSE FILE IS GONE IS NOT AN EDIT, AND KEEPING IT BLOCKS THINGS
		//FOREVER. This list is "the extracted files the user edited", and it is
		//the only durable record of them - so five operations use "is this path
		//listed?" as their test for "is one of me already pending?"
		//(ZoneAppender, AreaForker, GeometryForker, MapResizer and the encounter
		//editor). Nothing pruned it: cleanAll() empties it wholesale, and Remove
		//added zones drops entries by walking the DIRECTORY, so it cannot reach
		//an entry whose file has already gone. Once one is orphaned - a pack that
		//threw part way, a revert, a hand-deleted file - the operation it names is
		//refused for the rest of that workspace's life, and the refusal tells the
		//user to pack, which cannot clear it because packing never touches this
		//list. A real workspace was found with NINE orphaned entries of fourteen,
		//five of them zonedata slots, and zone appending had been dead in it since.
		//Dropping them on load (persistedIn does) is where it belongs: this is the
		//one moment the list is rebuilt from disk, so the check costs one stat per
		//entry and the file heals itself the next time it is written.
		try {
			persistPaths.addAll(persistedIn(workspaceDir));
			editListUnreadable = false;
		} catch (IOException ex) {
			//WHICH FILES ARE EDITS IS NOW UNKNOWN, NOT "NONE". It used to log and go on
			//with an empty list, and the next cleanUnchanged - every window close - then
			//deleted the edits as scratch. Sweeping is refused instead (cleanUnchanged).
			editListUnreadable = true;
			Logger.getLogger(WorkspaceSession.class.getName()).log(Level.SEVERE,
					"the workspace's list of edited files could not be read", ex);
		}
	}

	/** True when the list of edits could not be read on open: see {@link #readPersist}. */
	@ctrmap.util.ForSuites("SEAM: a suite asks whether the edit list failed to read on open, to see it is said and not swept")
	public boolean editListUnreadable() {
		return editListUnreadable;
	}

	/**
	 * Creates every extraction directory. {@link #getWorkspaceFile} opens a
	 * FileOutputStream without mkdirs, so a missing directory does not throw
	 * - it logs at SEVERE and returns a File that was never written, which is
	 * how the trainer and Maison editors used to fail silently on a brand-new
	 * workspace.
	 */
	public void prepareDirectories() {
		mkDirsIfNotContains(workspaceDir, WORKSPACE_SUBDIRS);
	}

	/** Writes the persisted-paths file, relative to the workspace so it can move between machines. */
	public void savePersist() {
		try {
			writePersist();
		} catch (IOException ex) {
			//every entry was written through as it was added (addPersist), so a failure here
			//leaves the list as the last successful write left it - whole - and loses nothing
			Logger.getLogger(WorkspaceSession.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	/**
	 * Writes the list of edits WHOLE OR NOT AT ALL (F034). It was rewritten delete-then-write,
	 * and a failure or a crash between the two left no list - which the next open reads as
	 * "no edits", and the next window close then deletes every unpacked edit as scratch. It is
	 * written beside the old one and moved over it, so the file on disk is always one whole list.
	 * A read-only session writes nothing: it is often a game nobody may write.
	 */
	void writePersist() throws IOException {
		if (readOnly) {
			return;
		}
		StringBuilder sb = new StringBuilder();
		//the root as persistedIn adds it back - absolute - and only as a PREFIX: a
		//replace() of a relative folder name also cut it out of the middle of paths
		String root = workspaceDir.getAbsolutePath();
		for (String line : persistPaths) {
			sb.append(line.startsWith(root) ? line.substring(root.length()) : line).append("\n");
		}
		WorkspaceUnit.touching(persistConfig);
		File writing = new File(persistConfig.getPath() + ".writing");
		Files.write(writing.toPath(), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		try {
			Files.move(writing.toPath(), persistConfig.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
					java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException notHere) {
			Files.move(writing.toPath(), persistConfig.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
	@Override
	public GameProfile profile() {
		return profile;
	}

	//isOA(), isXY() and isOADemo() USED TO BE HERE, beside the statics of the
	//same name in Workspace, and are gone for the same reason: they answered a
	//four-game question with a boolean, and "false" was read as the other game.
	//The two callers left at the end - MapResizer and WorkspaceIntegrity - now
	//ask profile().supports(Feature.AREA_FORK), which is the measurement they
	//actually depended on; isOADemo() had none, and variant() below is the
	//question it was asking.

	/**
	 * Which EDITION of this game the dump is. The probe belongs to the profile
	 * (see {@link GameProfile#detectVariant}) - this class used to build the
	 * File out of {@code OrasProfile.DEMO_PROBE} itself, which put the romfs
	 * path "/a/3/0/0" into this class file's constant pool through javac's
	 * constant inlining even though this source spells no GARC path.
	 */
	@Override
	public GameProfile.Variant variant() {
		return profile.detectVariant(gameDir);
	}

	/** Scratch directory inside the workspace. */
	public File temp() {
		return temp;
	}

	// ------------------------------------------------------------ archives

	/** Where this game keeps an archive, or null when the game lacks it (or its location is not yet verified). */
	@Override
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

	/**
	 * Re-reads one archive's entry table from disk - that archive and no other (F037).
	 *
	 * <p>It reloaded every archive after the named one in {@link #RELOAD_ORDER} too - the original
	 * switch's missing breaks, kept on purpose - so "reload X" secretly meant "reload X and whatever
	 * is listed after it", which no caller could see, and the pack's seven calls opened up to 105
	 * handles where 17 would do. A caller that rewrote several archives asks {@link #reloadAll}.
	 *
	 * <p>Asked for because an archive was rewritten - by a pack, the map builder, a zone removal -
	 * so everything this session kept from its archives goes with the old handles (F218). One
	 * rule at the one door every rewrite already passes, rather than one remembered by each
	 * writer for each cache: the pack used to drop the prop database and nothing else.
	 */
	public void reloadGARC(ArchiveType arc) {
		cache.drop();
		reopen(arc);
	}

	/** Re-reads every open archive's entry table, each once, and drops what was kept from them. */
	public void reloadAll() {
		cache.drop();
		for (ArchiveType t : RELOAD_ORDER) {
			reopen(t);
		}
	}

	private void reopen(ArchiveType t) {
		GARC g = archives.get(t);
		if (g != null) {
			archives.put(t, sniffs(t) ? new GARC(g.file) : new GARC(g.file, false));
		}
	}

	/**
	 * Any archive may have been rewritten under this session - a restore from the backup put files
	 * back into the game folder: every handle is re-read, and everything kept from them goes.
	 */
	public void archivesRewritten() {
		reloadAll();
	}

	private static boolean sniffs(ArchiveType t) {
		for (Object[] row : ARCHIVES) {
			if (row[0] == t) {
				return (Boolean) row[3];
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
	@ctrmap.util.NotAnEdit("it extracts an entry of the dump into the workspace, unchanged, the first time it is"
		+ " asked for: the bytes it writes are the dump's own, so nothing the user made changes")
	public File getWorkspaceFile(ArchiveType arc, int fileNum) {
		//IT USED TO POISON THE WORKSPACE ON ONE FAILURE, permanently. The output file was
		//opened - which CREATES and TRUNCATES it - before the bytes existed, so a null entry
		//left a 0-byte file and a failed write left a half-written one. The catch logged at
		//SEVERE, to a console the shipped app-image does not have, and fell through to
		//`return wsFile`. The guard is `!wsFile.exists()`, so every later call saw the ruin
		//and handed it back AS the entry for the life of that workspace - and 103 call sites
		//read it as one. GeometryForker copies it into a new region and packs it into the
		//user's FieldData; the user gets a zone with no ground and is told nothing.
		//
		//Bytes first, then a temp name moved into place once it is whole - the pattern
		//GARC.writeRepacked already uses. A failure deletes the part-file and THROWS, because
		//a File handed back cannot say what happened to it: "could not extract" and "this
		//entry is empty" must stop being the same answer.
		File wsFile = new File(getExtractionDirectory(arc).getAbsolutePath() + "/" + fileNum);
		if (wsFile.exists() || getArchive(arc).length <= fileNum) {
			return wsFile;
		}
		byte[] b = getArchive(arc).getDecompressedEntry(fileNum);
		if (b == null) {
			return null;
		}
		File part = new File(wsFile.getAbsolutePath() + ".part");
		try {
			java.nio.file.Files.write(part.toPath(), b);
			java.nio.file.Files.move(part.toPath(), wsFile.toPath(),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException cannotExtract) {
			part.delete();
			throw new IllegalStateException("could not extract entry " + fileNum + " of " + arc
				+ " into the workspace: " + ctrmap.util.Bytes.reason(cannotExtract), cannotExtract);
		}
		return wsFile;
	}

	// ------------------------------------------------------------ pending compression (F014)

	/**
	 * Whether each archive entry a fork or an append ADDED is stored LZ11, by
	 * archive, waiting for the next pack - which has no other way to know what
	 * a new slot should be.
	 *
	 * <p>THEY WERE FIVE PRIVATE STATIC MAPS on three classes - two on
	 * GeometryForker, two on AreaForker, one on ZoneAppender - filled by
	 * whoever forked and drained by the pack (F014). Static meant keyed to
	 * nothing: what a fork staged in one game survived repointing the
	 * workspace at another, and no unit of work could put them back when the
	 * fork failed part way. They describe this workspace's files, so this
	 * workspace holds them; {@link WorkspaceUnit} snapshots and restores them
	 * with the files.
	 */
	private final EnumMap<ArchiveType, Map<Integer, Boolean>> pendingCompression = new EnumMap<>(ArchiveType.class);

	/**
	 * How many packs have begun moving archives into the game (F209b). A kept change made before one is in the
	 * game: taken back, its files must stay edits, or the next pack leaves the game holding what was undone.
	 */
	private final java.util.concurrent.atomic.AtomicInteger packs = new java.util.concurrent.atomic.AtomicInteger();

	/** How many packs have begun moving archives into the game since this session opened. */
	public int packs() {
		return packs.get();
	}

	/**
	 * Packs the edited entries of {@code t} into the game's archive now, and counts it as a pack (F209b): what a change
	 * kept before it wrote is in the game from here on. For a tool that adds an entry and must show the archive
	 * holding it - the raw browser's Add file, which packed the archive itself and was counted by nothing.
	 */
	@ctrmap.util.NotAnEdit("it rebuilds one of the game's archives from the workspace's files: it writes out edits"
		+ " already made and kept in the workspace, and makes none of its own")
	public void packOne(ArchiveType t) throws IOException {
		if (readOnly) {
			throw new IllegalStateException("this session is read-only: " + gameDir + " must not be written");
		}
		GARC g = getArchive(t);
		if (g == null) {
			throw new IOException("the " + t + " archive is not open, so there is nothing to pack it into");
		}
		packs.incrementAndGet();   //from the move on the game may hold what the list held
		g.packDirectory(getExtractionDirectory(t), this::isPersisted, workspaceDir);
		reloadGARC(t);
	}

	/** Records that the added entry {@code index} of {@code t} is (or is not) stored compressed. */
	public void registerPendingCompression(ArchiveType t, int index, boolean compressed) {
		Map<Integer, Boolean> m = pendingCompression.get(t);
		if (m == null) {
			m = new java.util.HashMap<>();
			pendingCompression.put(t, m);
		}
		m.put(index, compressed);
	}

	/** Forgets the flag of the added entry {@code index} of {@code t}: a kept change that set it, taken back. */
	public void forgetPendingCompression(ArchiveType t, int index) {
		Map<Integer, Boolean> m = pendingCompression.get(t);
		if (m != null) {
			m.remove(index);
			if (m.isEmpty()) {
				pendingCompression.remove(t);
			}
		}
	}

	/** Whether a fork or an append left flags for {@code t} that only a pack can carry. */
	public boolean hasPendingCompression(ArchiveType t) {
		Map<Integer, Boolean> m = pendingCompression.get(t);
		return m != null && !m.isEmpty();
	}

	/** Takes one archive's pending flags for a pack, or null when there are none. */
	public Map<Integer, Boolean> consumePendingCompression(ArchiveType t) {
		return pendingCompression.remove(t);
	}

	/** A copy of every pending flag, for {@link WorkspaceUnit} to put back. */
	public EnumMap<ArchiveType, Map<Integer, Boolean>> pendingCompressionSnapshot() {
		EnumMap<ArchiveType, Map<Integer, Boolean>> copy = new EnumMap<>(ArchiveType.class);
		for (Map.Entry<ArchiveType, Map<Integer, Boolean>> e : pendingCompression.entrySet()) {
			copy.put(e.getKey(), new java.util.HashMap<>(e.getValue()));
		}
		return copy;
	}

	/** Puts every pending flag back as {@code snapshot} held it. */
	public void restorePendingCompression(EnumMap<ArchiveType, Map<Integer, Boolean>> snapshot) {
		pendingCompression.clear();
		for (Map.Entry<ArchiveType, Map<Integer, Boolean>> e : snapshot.entrySet()) {
			pendingCompression.put(e.getKey(), new java.util.HashMap<>(e.getValue()));
		}
	}

	/** Marks an extracted file as edited, so the next pack writes it back. */
	public void addPersist(File f) {
		if (!persistPaths.contains(f.getAbsolutePath())) {
			persistPaths.add(f.getAbsolutePath());
			//WRITTEN THROUGH (F034). The list was written only when the window closed, so a crash
			//forgot every edit made since the workspace opened - and the next close swept those
			//files away as unchanged. A list that cannot be written is thrown, not logged: an edit
			//the list does not hold is an edit the next close deletes, and inside a unit the throw
			//puts the edit back instead.
			try {
				writePersist();
			} catch (IOException ex) {
				throw new IllegalStateException("The workspace's list of edited files could not be written, so "
						+ f.getName() + " would not be known as an edit and the next close would delete it: "
						+ ctrmap.util.Bytes.reason(ex), ex);
			}
		}
	}

	// ------------------------------------------------------------ what the format layer is handed
	//
	// GameFiles, satisfied by delegation: the format layer chose these names
	// for what it needs, and each is one of the members above. Nothing here
	// decides anything the member it delegates to does not.

	/**
	 * {@link #getWorkspaceFile}, refusing first where that would dereference
	 * nothing: it opens the extraction directory and the archive handle
	 * unchecked, so an archive this game lacks or nothing extracts was a
	 * NullPointerException rather than the null the contract promises.
	 */
	@Override
	public File staged(ArchiveType type, int entry) {
		if (getExtractionDirectory(type) == null || getArchive(type) == null) {
			return null;
		}
		return getWorkspaceFile(type, entry);
	}

	@Override
	public void edited(File f) {
		addPersist(f);
	}

	@Override
	public File editedEntry(ArchiveType type, int entry) {
		File dir = getExtractionDirectory(type);
		if (dir == null) {
			return null;
		}
		File f = new File(dir.getAbsolutePath() + "/" + entry);
		return isPendingArtifact(f) ? f : null;
	}

	/** The open {@link WorkspaceUnit}, if there is one, copies the file before it changes. */
	@Override
	public boolean isEdit(File f) {
		return isPendingArtifact(f);
	}

	@Override
	public void aboutToWrite(File f) {
		WorkspaceUnit.touching(f);
	}

	@Override
	public File scratch() {
		return temp();
	}

	@Override
	public ctrmap.formats.GameCache cache() {
		return cache;
	}

	@Override
	public List<Integer> zonesNamingArea(int area, int except) throws IOException {
		return MasterTable.read(this).using(ZoneResource.AREA, area, except);
	}

	@Override
	public GARC archive(ArchiveType type) {
		return getArchive(type);
	}

	/** {@link #originalSnapshotDir()} when a snapshot has been taken there; null when the folder does not exist. */
	@Override
	public File pristine() {
		File snap = originalSnapshotDir();
		return snap.isDirectory() ? snap : null;
	}

	/**
	 * The snapshot's copy; else the sealed backup's VERIFIED copy, read into this workspace's scratch
	 * once; else null - never the live folder (F232). The backup is the one the vault keeps for this
	 * dump, matched by its title (F121); a backup that is there and does not match what was sealed
	 * throws, because a damaged copy is not an absent one.
	 */
	@Override
	public File pristineArchive(ArchiveType type) throws IOException {
		File snapshotted = GameFiles.super.pristineArchive(type);
		if (snapshotted != null) {
			return snapshotted;
		}
		String rel = profile.archivePath(type);
		if (rel == null || gameDir == null) {
			return null;
		}
		File entry = ctrmap.vault.Vault.entryFor(gameDir);
		if (!ctrmap.vault.Vault.isSealed(entry)) {
			return null;
		}
		File into = new File(new File(scratch(), "pristine-from-vault"), rel.replace('/', File.separatorChar));
		if (into.isFile()) {
			return into;   //verified when it was copied here; a clean empties scratch and it is copied again
		}
		String why = ctrmap.vault.Vault.verifiedCopy(entry, gameDir, rel, into);
		if (!why.isEmpty()) {
			throw new IOException("the sealed backup's copy of " + type + " could not be used: " + why);
		}
		return into;
	}

	/**
	 * A directory under the workspace folder that {@link #cleanAll} and
	 * {@link #cleanUnchanged} never touch: they walk {@link #WORKSPACE_SUBDIRS}
	 * and nothing else. A name from that list, or the pristine snapshot's,
	 * is refused rather than handed out, because a class that keeps its
	 * pre-edit copy in a directory the next clean empties has no pre-edit
	 * copy and no way of knowing it.
	 */
	@Override
	public File durable(String name) {
		if (name == null || name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
				|| ".".equals(name) || "..".equals(name)) {
			throw new IllegalArgumentException("a durable directory is named by one path segment, not \"" + name + "\"");
		}
		for (String sub : WORKSPACE_SUBDIRS) {
			if (sub.equals(name)) {
				throw new IllegalArgumentException("\"" + name + "\" is a directory cleaning the workspace empties;"
						+ " nothing kept there is durable");
			}
		}
		if (originalSnapshotDir().getName().equals(name)) {
			throw new IllegalArgumentException("\"" + name + "\" is the pristine snapshot, which is taken and discarded"
					+ " on its own schedule; a class's own files do not belong inside it");
		}
		return new File(workspaceDir, name);
	}

	/** True when an extracted file is marked as edited - what a pack writes back. */
	public boolean isPersisted(File f) {
		return persistPaths.contains(f.getAbsolutePath());
	}

	/** The live list of edited files' absolute paths. */
	public List<String> persistPaths() {
		return persistPaths;
	}

	/**
	 * Drops the workspace's extracted copies of the entries of one archive that {@code which}
	 * names, and their edited marks: the archive was rewritten under them (a zone removal), so
	 * the next read extracts afresh rather than finding a copy of an entry that is gone.
	 *
	 * @throws IOException naming every copy that could not be deleted; the rest are dropped
	 */
	public void dropExtracted(ArchiveType type, java.util.function.IntPredicate which) throws IOException {
		File dir = getExtractionDirectory(type);
		File[] kids = dir == null ? null : dir.listFiles();
		if (kids == null) {
			return; //nothing was ever extracted from it
		}
		List<File> stuck = new ArrayList<>();
		for (File f : kids) {
			int n;
			try {
				n = Integer.parseInt(f.getName());
			} catch (NumberFormatException notAnEntry) {
				continue;
			}
			if (which.test(n)) {
				persistPaths.remove(f.getAbsolutePath());
				WorkspaceUnit.touching(f);   //copied first, so a kept change that drops it can put it back (F209c)
				if (f.exists() && !f.delete()) {
					stuck.add(f);
				}
			}
		}
		if (!stuck.isEmpty()) {
			throw new IOException("These extracted copies could not be deleted, and now describe entries the archive no"
					+ " longer has: " + stuck + ". Delete them before editing those entries.");
		}
	}

	/**
	 * Whether an operation that writes {@code f} has already run and not yet
	 * been packed - the question five callers were asking with
	 * {@code persistPaths().contains(...)}.
	 *
	 * <p>WHY THAT WAS NOT THE SAME QUESTION. Being listed says the file was
	 * MARKED as edited once. It does not say the file is still there, and an
	 * entry outlives its file easily - a pack that threw part way, a revert, a
	 * hand-deleted workspace file. The list is never pruned except by
	 * {@code cleanAll()}, which empties it wholesale, so an orphan is permanent:
	 * the operation refuses for the rest of that workspace's life and tells the
	 * user to pack, which cannot help, because packing does not touch this list
	 * and never has.
	 *
	 * <p>{@link #readPersist} now drops orphans as it reads, so a workspace
	 * heals on open. This is the same rule applied at the point of asking, for
	 * a file that disappears while the session is live.
	 */
	public boolean isPendingArtifact(File f) {
		return f != null && persistPaths.contains(f.getAbsolutePath()) && f.exists();
	}

	/**
	 * Where archive {@code t} reaches once what is staged is packed: its packed length, then every
	 * entry staged straight after it and not packed yet. THE ONE ANSWER EVERY APPEND ASKS for its
	 * first new index, so a new entry is never written over a staged one and never refused for one.
	 *
	 * <p>PAID FOR IN 1.0.4. Each allocator took the packed length and, finding a fork staged there,
	 * refused with "pack the workspace first". At an action the user pressed that was an
	 * instruction; in the repair that runs when a workspace opens it was a dialog on every launch -
	 * the owner's workspace held an area fork staged on Sep 11 and never packed, the repair's first
	 * new id was that slot, and nothing in the dialog said a pack would end it. Worse, the area
	 * repair's empty-registry fillers started from the PACKED end too, so getting past the refusal
	 * would have written an empty registry over the staged fork's. A staged entry past the end is
	 * a real entry the next pack will add (an archive grows only at its tail, so they are
	 * contiguous), and counting it is what the pack itself will do.
	 *
	 * @param packedLength how many entries the archive holds as packed - its {@code length}
	 */
	public int reach(ArchiveType t, int packedLength) {
		File dir = getExtractionDirectory(t);
		int n = packedLength;
		while (dir != null && isPendingArtifact(new File(dir, String.valueOf(n)))) {
			n++;
		}
		return n;
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

	/**
	 * Empties every extraction directory of everything NOT marked edited - unless
	 * which files are edited could not be read, when nothing is swept at all.
	 */
	public void cleanUnchanged() {
		if (editListUnreadable) {
			return; //see readPersist: unknown edits are kept, never swept as scratch
		}
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
		//a BufferedReader: a Scanner kept a read failure to itself, so the catch below never saw one
		try (java.io.BufferedReader lines = java.nio.file.Files.newBufferedReader(stamp.toPath(),
				java.nio.charset.StandardCharsets.UTF_8)) {
			for (String raw; (raw = lines.readLine()) != null;) {
				String line = raw.trim();
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
	@ctrmap.util.NotAnEdit("it deletes the snapshot of the game's own archives, taken before any edit, so the next"
		+ " load takes it again from the game folder now in use: retail files, nothing the user made")
	public static void discardSnapshot(File workspaceDir) throws java.io.IOException {
		//SAID WHEN IT STAYS (2026-10-12): a snapshot that would not go was reported gone, and the switch went ahead
		//on the other game's backup
		if (!ctrmap.util.Bytes.deleteTree(originalSnapshotDir(workspaceDir))) {
			throw new java.io.IOException("the old backup could not be removed (a file in it is in use or read-only): "
					+ originalSnapshotDir(workspaceDir));
		}
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
	@ctrmap.util.NotAnEdit("it copies the game's archives into the workspace's snapshot before any edit is made -"
		+ " the record of the game as it was, and never re-taken over edited archives: retail files, nothing the"
		+ " user made")
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
	@ctrmap.util.NotAnEdit("it rebuilds the game's archives from the workspace's files: it writes out edits already"
		+ " made and kept in the workspace, and makes none of its own")
	public List<String> packArchives(PackProgress progress) throws IOException {
		if (readOnly) {
			throw new IllegalStateException("this session is read-only: " + gameDir + " must not be written");
		}
		//leftovers from a pack that threw are not this pack's news
		GARC.drainPackWarnings();
		//ALL OF THEM WRITTEN, THEN ALL OF THEM SWAPPED (F035). Each archive was rewritten straight
		//into the game folder in turn, so a failure part way - the emulator holding ZoneData open
		//is the usual cause - left an area appended to AreaData and its NPC registry not, the
		//state that stops a zone loading. Every replacement is written beside the workspace first;
		//the game folder is touched only once all of them exist, one move each.
		//
		//ONLY WHAT WAS EDITED (F036). The six big archives were rewritten and recompressed on every
		//pack whether or not anything in them changed, which is also why Deploy has to decompress
		//every entry to tell what did. An archive is packed when it holds a staged edit or a
		//pending compression override - what the small archives were already gated on.
		List<ArchiveType> packed = new ArrayList<>();
		List<File> prepared = new ArrayList<>();
		Map<ArchiveType, Map<Integer, Boolean>> consumed = new EnumMap<>(ArchiveType.class);
		try {
			int step = 0;
			for (ArchiveType t : PACK_ORDER) {
				progress.at(step++ * 90 / PACK_ORDER.size(), "Packing - " + SUBDIR.get(t));
				//asked before the archive is fetched: story text is loaded lazily and is huge
				File dir = getExtractionDirectory(t);
				if (dir == null || !(hasPersistedFiles(dir) || hasPendingCompression(t))) {
					//NOT WRITTEN, STILL ASKED (PackScope): an archive with nothing to pack is skipped, but one
					//rewritten underneath this editor is read again and said, as a repack would have - held ones
					//only, so story text that was never opened is not opened for it
					GARC held = archives.get(t);
					if (held != null) {
						held.refreshIfStale();
					}
					continue;
				}
				GARC g = t == ArchiveType.STORYTEXT ? getStoryTextGARC() : getArchive(t);
				if (g == null) {
					continue;
				}
				Map<Integer, Boolean> overrides = consumePendingCompression(t);
				if (overrides != null) {
					consumed.put(t, overrides);
				}
				File next = new File(workspaceDir, "pack_" + t.name() + ".garc");
				if (IN_PLACE.contains(t)) {
					if (overrides != null) {
						throw new IOException(t + " is written in place, which neither adds an entry nor changes"
								+ " how one is stored, and a compression override is waiting for it: " + overrides);
					}
					g.prepareInPlace(dir, this::isPersisted, next);
				} else {
					g.prepareRepack(dir, this::isPersisted, overrides, next);
				}
				packed.add(t);
				prepared.add(next);
			}
		} catch (IOException | RuntimeException notPacked) {
			//nothing reached the game: drop what was written and give back what was taken
			for (File f : prepared) {
				f.delete();
			}
			for (Map.Entry<ArchiveType, Map<Integer, Boolean>> e : consumed.entrySet()) {
				for (Map.Entry<Integer, Boolean> one : e.getValue().entrySet()) {
					registerPendingCompression(e.getKey(), one.getKey(), one.getValue());
				}
			}
			throw notPacked;
		}
		progress.at(95, "Moving the packed archives into the game");
		packs.incrementAndGet();   //from the first move the game may hold what the list held: a change kept before it is carried
		for (int i = 0; i < packed.size(); i++) {
			ArchiveType t = packed.get(i);
			GARC g = t == ArchiveType.STORYTEXT ? getStoryTextGARC() : getArchive(t);
			try {
				g.commitRepack(prepared.get(i));
			} catch (IOException moveFailed) {
				for (int j = i + 1; j < prepared.size(); j++) {
					prepared.get(j).delete();
				}
				throw new IOException("The pack moved " + packed.subList(0, i) + " into the game and then could not"
						+ " move " + t + ": " + ctrmap.util.Bytes.reason(moveFailed) + " Those already moved are in the game; the"
						+ " rest stay staged, so packing again carries them.", moveFailed);
			}
		}
		progress.at(100, "Done, updating GARCs");
		//the GARC indices may have changed and as such we need to reload them: every archive once,
		//the story text included, and the reload empties what was kept from the old ones (the prop
		//database among them)
		reloadAll();
		//The archives now on disk are what the game will load. Cross-
		//archive references are by bare index and nothing else checks
		//them, so an operation that grew one archive and not another
		//leaves a dangling index that only shows up much later, as a
		//zone that will not load. Check here, while the edit that
		//caused it is still the last thing that happened.
		List<String> warnings = new ArrayList<>();
		warnings.addAll(WorkspaceIntegrity.report(this, "packing the workspace"));
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
