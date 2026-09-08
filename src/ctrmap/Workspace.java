package ctrmap;

import ctrmap.formats.tilemap.EditorTileset;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.pokedata.PokeData;
import ctrmap.formats.text.LocationNames;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import ctrmap.humaninterface.LoadingDialog;
import ctrmap.resources.ResourceAccess;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import javax.swing.SwingWorker;

/**
 * The application's settings, its CURRENT {@link WorkspaceSession}, and the
 * dialogs around opening and packing one.
 *
 * <p>This used to be the god object: the open game's paths, {@code
 * GameType}, archive files and GARC handles were public static fields here,
 * read from seventy production files and written from a handful, all
 * changing together as one "a workspace was opened / packed" transaction
 * that nothing could exercise in isolation. That state now lives in a
 * {@link WorkspaceSession} instance. What remains static here is:
 *
 * <ul>
 * <li>the SETTINGS - the five values kept in java.util.prefs, which the
 * settings dialog and the setup wizard write and {@link #validate} opens a
 * session from;</li>
 * <li>{@link #session() the current session}, installed by a successful
 * validation and nothing else;</li>
 * <li>the UI around the session: {@link #validate} reports what stopped a
 * folder opening and tells the main window to load, {@link #packWorkspace}
 * runs a pack behind a progress dialog;</li>
 * <li>every old static, as a delegator to the current session, while callers
 * are migrated one file at a time to being HANDED a session. A caller that is
 * handed one can be handed a different one, which is what makes its decision
 * testable; a caller reading these statics cannot. {@code
 * ctrmap.tests.WorkspaceSessionTest} counts the files still reading them.</li>
 * </ul>
 */
public class Workspace {

	static Preferences prefs;
	public static String WORKSPACE_PATH;
	public static String GAMEDIR_PATH;
	public static String ESPICA_PATH;
	public static boolean TILESET_DEFAULT;
	public static String TILESET_PATH;

	/**
	 * The open game, or null when no workspace has validated. Installed
	 * whole by {@link #validate} (or {@link #install}) and never edited in
	 * place: a failed switch leaves this null, never half of two games.
	 */
	private static WorkspaceSession current;

	public static void loadWorkspace() {
		prefs = Preferences.userRoot().node(Workspace.class.getName());
		WORKSPACE_PATH = prefs.get("WORKSPACE_PATH", "");
		GAMEDIR_PATH = prefs.get("GAMEDIR_PATH", "");
		ESPICA_PATH = prefs.get("ESPICA_PATH", "");
		TILESET_DEFAULT = prefs.getBoolean("TILESET_DEFAULT", true);
		TILESET_PATH = prefs.get("TILESET_PATH", "");
	}

	public static void createWorkspace(String wspath, String gamepath, String espicapath, boolean tilesetDefault, String customTilesetPath) {
		WORKSPACE_PATH = wspath;
		GAMEDIR_PATH = gamepath;
		ESPICA_PATH = espicapath;
		TILESET_DEFAULT = tilesetDefault;
		TILESET_PATH = customTilesetPath;
	}

	public static EditorTileset getTileset() {
		if (!TILESET_DEFAULT) {
			if (TILESET_PATH != null) {
				File f = new File(TILESET_PATH);
				if (f.exists()) {
					EditorTileset ts = new EditorTileset(f);
					if (ts.tiles != null) { //if yes, the loader failed, wrong magic most likely
						return ts;
					}
				}
			}
		} else {
			return new EditorTileset(ResourceAccess.getStream("DefaultTileset.mets"));
		}
		Utils.showErrorMessage("Invalid tileset", "The tileset is corrupt. Restoring defaults.");
		TILESET_DEFAULT = true;
		return new EditorTileset(ResourceAccess.getStream("DefaultTileset.mets"));
	}

	/**
	 * True when CTRMap has never been pointed at a game. Deliberately tests the
	 * SETTINGS rather than the folders: a user whose dump lives on a drive that
	 * happens to be unplugged has still set CTRMap up, and must not be dragged
	 * back through first-run setup because of it.
	 */
	public static boolean isConfigured() {
		return GAMEDIR_PATH != null && !GAMEDIR_PATH.trim().isEmpty()
				&& WORKSPACE_PATH != null && !WORKSPACE_PATH.trim().isEmpty();
	}

	// ------------------------------------------------------------ the current session

	/**
	 * The open game, or null. Every call to this outside Workspace is a
	 * caller that has not yet been handed its session; the migration is done
	 * when nothing calls it.
	 */
	public static WorkspaceSession session() {
		return current;
	}

	/**
	 * Makes a session the open game, or none, and hands it to the main window,
	 * which keeps it in one field rather than fetching it here from every
	 * action. {@link #validate} is how the application does it; a suite does
	 * it directly with a session it built - and because both come through
	 * here, the window is handed a suite's session exactly as it is handed
	 * the user's.
	 */
	public static void install(WorkspaceSession s) {
		current = s;
		CtrmapMainframe.onWorkspaceOpened(s);
	}

	/** The open game's type, or null when no workspace has validated. */
	public static GameType game() {
		return current == null ? null : current.game();
	}

	/** True once a workspace has validated and its archives are open. */
	public static boolean isValid() {
		return current != null;
	}

	/** The extracted files marked as edited - the ones a pack writes back. Empty with no session. */
	public static java.util.List<String> persistPaths() {
		return current == null ? NO_PERSISTED : current.persistPaths();
	}

	/** Unmodifiable: a caller with no session has nothing to mark edited, and trying to is a fault worth hearing about. */
	private static final java.util.List<String> NO_PERSISTED = java.util.Collections.unmodifiableList(new ArrayList<String>());

	/** Scratch directory inside the workspace, or null before validation. */
	public static File temp() {
		return current == null ? null : current.temp();
	}

	public static void validate(Component parent) {
		validate(parent, true);
	}

	/**
	 * Opens the game the settings name and makes it the current session -
	 * or makes there be NO current session, when it cannot be opened.
	 *
	 * <p>All or nothing. The previous session, if any, is gone either way:
	 * repointing a workspace at a folder that fails validation used to keep
	 * the old game's {@code GameType} and all of its archive handles live,
	 * with {@code valid} false and the archive files rebuilt from the new
	 * folder using the old game's layout. Half of each game at once, and the
	 * error list padded with archives "not found" in a folder that was never
	 * identified as that game.
	 *
	 * @param showErrors when false, a failed validation returns quietly instead
	 * of throwing a list of missing archive names at the user. The setup wizard
	 * reports problems in its own words, and a brand-new user must never meet
	 * the raw list before they have had a chance to do anything.
	 */
	public static void validate(Component parent, boolean showErrors) {
		install(null);
		WorkspaceSession opened;
		try {
			opened = WorkspaceSession.open(WORKSPACE_PATH == null ? null : new File(WORKSPACE_PATH),
					GAMEDIR_PATH == null ? null : new File(GAMEDIR_PATH));
		} catch (WorkspaceSession.OpenFailed ex) {
			if (!showErrors) {
				return;
			}
			StringBuilder sb = new StringBuilder();
			for (String s : ex.problems()) {
				sb.append(s);
				sb.append("\n");
			}
			sb.append("\nRun Options > Setup wizard to point CTRMap at your game,\n");
			sb.append("then open a map from the zone dropdown in the \"Zone Loader\" tab.");
			Ui.error(parent, sb.toString(), "Setup Error");
			return;
		}
		opened.prepareDirectories();
		reportSnapshot(opened.snapshotOriginals());
		//the two tables derived from the game are loaded from the session that
		//was just opened, handed in: neither class reads the global any more,
		//so a workspace that opens without loading them leaves the location
		//dropdowns refusing and the Pokemon pickers on id-only labels. They
		//load BEFORE the session is installed, because installing it hands it
		//to the main window, whose zone dropdown names its rows from them.
		LocationNames.loadFromGarc(opened);
		PokeData.load(opened);
		install(opened);
	}

	// ------------------------------------------------------------ delegators

	public static void addPersist(File f) {
		if (current != null) {
			current.addPersist(f);
		}
	}

	/**
	 * RomFS-relative path of an archive for a game, or null when that game
	 * lacks it (or its location is not yet verified). The per-game tables live
	 * in {@link ctrmap.gamedef.GameProfile} and its subclasses - the single
	 * home for game-specific constants.
	 */
	public static String getArchivePath(ArchiveType archiveType, GameType gameType) {
		return ctrmap.gamedef.GameProfile.of(gameType).archivePath(archiveType);
	}

	/** The active game's profile (paths, text indices, feature gates). */
	public static ctrmap.gamedef.GameProfile profile() {
		return ctrmap.gamedef.GameProfile.of(game());
	}

	public static void cleanAll() {
		if (current != null) {
			current.cleanAll();
		} else if (WORKSPACE_PATH != null) {
			WorkspaceSession.cleanDirectories(new File(WORKSPACE_PATH), true, NO_PERSISTED);
		}
		saveWorkspace();
	}

	public static void cleanAndReload() {
		if (CtrmapMainframe.frame != null) {
			CtrmapMainframe.unloadEditors();
		}
		cleanAll();
	}

	public static void cleanUnchanged() {
		if (current != null) {
			current.cleanUnchanged();
		} else if (WORKSPACE_PATH != null) {
			WorkspaceSession.cleanDirectories(new File(WORKSPACE_PATH), false, NO_PERSISTED);
		}
	}

	public static GARC getArchive(ArchiveType type) {
		return current == null ? null : current.getArchive(type);
	}

	/** The workspace folder a session-less caller means: the one the settings name. */
	private static File configuredWorkspaceDir() {
		if (current != null) {
			return current.workspaceDir();
		}
		return WORKSPACE_PATH == null ? null : new File(WORKSPACE_PATH);
	}

	public static File getExtractionDirectory(ArchiveType type) {
		File ws = configuredWorkspaceDir();
		return ws == null ? null : WorkspaceSession.extractionDirectory(ws, type);
	}

	/** The storytext GARC, or null if the workspace is not validated or the archive file does not exist. */
	public static GARC getStoryTextGARC() {
		return current == null ? null : current.getStoryTextGARC();
	}

	/** Directory holding the one-time pristine copy of the moddable archives,
	 *  used by {@link ModDeployer} to ship only what actually changed. */
	public static File originalSnapshotDir() {
		File ws = configuredWorkspaceDir();
		return ws == null ? null : WorkspaceSession.originalSnapshotDir(ws);
	}

	/** Records which game folder a pristine snapshot was taken from. */
	public static File originalSnapshotStamp() {
		File ws = configuredWorkspaceDir();
		return ws == null ? null : WorkspaceSession.originalSnapshotStamp(ws);
	}

	/**
	 * The game folder a workspace's pristine snapshot was taken from, or null
	 * when there is no snapshot or it predates stamping.
	 */
	public static String snapshotSourcePath() {
		File ws = configuredWorkspaceDir();
		return ws == null ? null : WorkspaceSession.snapshotSourcePath(ws);
	}

	/** True when the configured workspace holds a pristine snapshot of a DIFFERENT game folder than the one configured. */
	public static boolean snapshotIsForeign() {
		return snapshotIsForeign(GAMEDIR_PATH);
	}

	/**
	 * The same question about a folder the workspace is about to be pointed at,
	 * so the settings dialog can ask BEFORE it repoints anything.
	 */
	public static boolean snapshotIsForeign(String gameDir) {
		File ws = configuredWorkspaceDir();
		return ws != null && WorkspaceSession.snapshotIsForeign(ws, gameDir);
	}

	/** Deletes the pristine snapshot so the next load re-takes it from the current game folder. */
	public static void discardSnapshot() {
		File ws = configuredWorkspaceDir();
		if (ws != null) {
			WorkspaceSession.discardSnapshot(ws);
		}
	}

	/** Archives the snapshot is supposed to hold but does not; empty with no session. */
	public static java.util.List<String> snapshotMissingArchives() {
		return current == null ? new ArrayList<String>() : current.snapshotMissingArchives();
	}

	/** True once a snapshot problem has been shown this session; see {@link #reportSnapshotProblem}. */
	private static boolean snapshotProblemShown;

	/**
	 * Says a backup went wrong, somewhere the user can actually see it.
	 *
	 * <p>Both failure paths in {@link WorkspaceSession#snapshotOriginals} used
	 * to print to {@code System.err} and nothing else. The shipped jpackage
	 * app-image has no console, so in the built program a backup that failed -
	 * or, worse, one that came out PARTIAL while its stamp still says it is
	 * legitimate - was reported to nobody. Both call sites discard the returned
	 * list, so the return value was not covering it either. The wizard has a
	 * catch that looks like it handles this and is unreachable, because this
	 * method cannot throw.
	 *
	 * <p>Once per session: a partial snapshot is re-detected on every load, and a
	 * dialog on every load would train the user to dismiss it unread, which is
	 * the same silence by another route.
	 */
	static void reportSnapshotProblem(String text) {
		if (snapshotProblemShown) {
			return;
		}
		snapshotProblemShown = true;
		ctrmap.Ui.error(CtrmapMainframe.frame, text, "Pristine backup");
	}

	/** Lets a suite exercise more than one snapshot problem in one JVM. */
	public static void resetSnapshotProblemReporting() {
		snapshotProblemShown = false;
	}

	/**
	 * Puts every static this class owns back to the value it had before any
	 * workspace was loaded, as though the JVM had just started.
	 *
	 * <p>The list below must name EVERY static field of this class. That is not
	 * a request: {@code ctrmap.tests.GlobalStateTest} enumerates the fields by
	 * reflection and fails if one is missing, so a field added later cannot
	 * quietly survive a reset and leak one workspace into the next.
	 *
	 * <p>Nothing in the application calls this. Repointing a live workspace at
	 * a different game goes through {@link #validate}, which replaces the
	 * session whole.
	 */
	public static void reset() {
		prefs = null;
		WORKSPACE_PATH = null;
		GAMEDIR_PATH = null;
		ESPICA_PATH = null;
		TILESET_DEFAULT = false;
		TILESET_PATH = null;
		current = null;
		//the main window holds the session it was handed in install(); a reset
		//that dropped the global's copy and left the window's would leave the
		//window operating on a game nothing else has open
		CtrmapMainframe.onWorkspaceOpened(null);
		//not fields of this class, but derived from it: the location-name
		//table and the Pokemon reference tables are read from the open
		//workspace's game, and a reset that kept them would hand one game's
		//names to the next
		LocationNames.unload();
		PokeData.unload();
		//spelled out rather than delegated to resetSnapshotProblemReporting():
		//GlobalStateTest reads this method's own body to prove no field of this
		//class was left out of the reset, and it cannot follow a call to do it.
		snapshotProblemShown = false;
	}

	/**
	 * Takes (or checks) the current session's pristine backup and puts what
	 * went wrong in front of the user. Returns the archives it refused to
	 * capture, as {@link WorkspaceSession#snapshotOriginals} does.
	 */
	public static java.util.List<String> snapshotOriginals() {
		if (current == null) {
			return new ArrayList<>();
		}
		return reportSnapshot(current.snapshotOriginals());
	}

	/** The three things a backup can have wrong, worded for the user. */
	public static java.util.List<String> reportSnapshot(WorkspaceSession.SnapshotReport report) {
		if (report.foreignTakenFrom != null) {
			Ui.error(CtrmapMainframe.frame, "This workspace holds a pristine backup of a different game folder:\n  "
					+ report.foreignTakenFrom
					+ "\n\nCTRMap compares your edits against that backup to work out what you"
					+ "\nchanged, and cuts donor buildings out of it, so both are now wrong for"
					+ "\n  " + GAMEDIR_PATH
					+ "\n\nDelete the backup folder\n  " + report.snapshotDir
					+ "\nand reload, against an unmodified game, to take a new one.",
					"Backup belongs to another game");
		}
		if (!report.refused.isEmpty()) {
			reportSnapshotProblem("Some of the pristine backup could not be taken."
					+ "\n\nMissing archive(s): " + report.refused
					+ "\n\nCTRMap compares your edits against that backup to work out what you"
					+ "\nchanged, and cuts donor buildings out of it, so a backup that is missing"
					+ "\npieces gives the wrong answer for them - silently."
					+ "\n\nDelete the snapshot folder and reload against an unmodified game to"
					+ "\nretake it whole.");
			System.err.println("Workspace: the pristine snapshot in " + report.snapshotDir
					+ " is missing " + report.refused.size() + " archive(s): " + report.refused
					+ "\n  They will NOT be captured from the live game, which may already be"
					+ " edited. Delete the snapshot folder and reload against an unmodified"
					+ " game to retake it whole.");
		}
		if (report.failure != null) {
			reportSnapshotProblem("The pristine backup of your game could not be taken."
					+ "\n\n" + report.failure
					+ "\n\nYou can carry on working, but CTRMap has no unmodified copy to"
					+ "\ncompare against - so \"ship only what I changed\" cannot work, and if"
					+ "\nthis game folder is damaged later you will need to dump it from your"
					+ "\nconsole again.");
			System.err.println("Original-archive snapshot failed (non-fatal): " + report.failure);
		}
		return report.refused;
	}

	public static void reloadGARC(ArchiveType arc) {
		if (current != null) {
			current.reloadGARC(arc);
		}
	}

	/** The extracted copy of one entry, or null with no session or no such entry. */
	public static File getWorkspaceFile(ArchiveType arc, int fileNum) {
		return current == null ? null : current.getWorkspaceFile(arc, fileNum);
	}

	public static void packWorkspace() {
		packWorkspace(null);
	}

	/** Progress sink for {@link #packArchives}: the dialog in the app, nothing in a headless test. */
	public interface PackProgress {

		void at(int percent, String what);
	}

	/** Packs the current session's edits back into the game; see {@link WorkspaceSession#packArchives}. */
	public static java.util.List<String> packArchives(PackProgress progress) throws IOException {
		if (current == null) {
			throw new IllegalStateException("no workspace is open");
		}
		return current.packArchives(progress);
	}

	/**
	 * Puts what a pack found wrong in front of the user, through the dialog
	 * they already get when the pack finishes.
	 *
	 * <p>Separate from the pack itself on purpose: the pack runs on a worker
	 * with an application-modal progress dialog on screen, and a message shown
	 * from there would sit behind it, unreachable. This runs on the EDT once
	 * that dialog has closed.
	 */
	public static void reportPackWarnings(Component parent, java.util.List<String> warnings) {
		if (warnings == null || warnings.isEmpty()) {
			return;
		}
		StringBuilder sb = new StringBuilder("The workspace was packed, but the archives it wrote"
				+ " do not add up:\n");
		for (String w : warnings) {
			sb.append('\n').append(wrap(w));
		}
		sb.append("\nFix this before deploying - the game may not load.");
		Ui.error(parent, sb.toString(), "Pack workspace");
	}

	/** One warning, broken at spaces so the dialog does not grow off the screen. */
	private static String wrap(String s) {
		StringBuilder out = new StringBuilder("  - ");
		int lineStart = 0;
		for (String word : s.split(" ")) {
			if (out.length() - lineStart + word.length() > 92) {
				out.append("\n    ");
				lineStart = out.length();
			}
			out.append(word).append(' ');
		}
		return out.append('\n').toString();
	}

	/**
	 * Packs the workspace back into the game archives on a background worker.
	 * Because the pack is asynchronous (and only finishes reloading the GARCs -
	 * updating their entry counts - when the worker completes), callers that
	 * need the fresh archives (e.g. after a zone append changes the entry count)
	 * MUST pass onDone rather than running follow-up work on the calling thread.
	 * onDone runs on the EDT after packing and the GARC reloads are complete.
	 */
	public static void packWorkspace(final Runnable onDone) {
		//a read-only session (a suite over the pristine dump) has nothing to
		//commit TO; the application never opens one
		if (current != null && !current.isReadOnly()) {
			final WorkspaceSession packing = current;
			LoadingDialog progress = LoadingDialog.makeDialog("Packing");
			final java.util.List<String> warnings = new ArrayList<>();
			SwingWorker worker = new SwingWorker() {
				@Override
				protected void done() {
					progress.close();
					try {
						get(); //without this, a pack that threw half-way closes the dialog and
						//onDone deploys or reloads as if every archive had been written
					} catch (Exception ex) {
						Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
						Logger.getLogger(Workspace.class.getName()).log(Level.SEVERE, "packing the workspace", cause);
						//through Ui: this sentence is the only difference between
						//a pack that failed and one that worked, and a bare
						//dialog is not something a guard can see
						Ui.error(CtrmapMainframe.frame, "The workspace was not packed:\n" + cause
								+ "\n\nThe game archives may be partly written. Fix the cause and pack again before deploying.", "Pack workspace");
						return;
					}
					if (onDone != null) {
						onDone.run();
					}
				}

				@Override
				protected Object doInBackground() throws Exception {
					//a failure here must reach done() and the user, not the log
					warnings.addAll(packing.packArchives((percent, what) -> {
						progress.setBarPercent(percent);
						progress.setDescription(what);
					}));
					return null;
				}
			};
			worker.execute();
			progress.showDialog();
			//showDialog returns on the EDT once done() has closed the progress
			//dialog, so this is the first moment anything else can be seen
			reportPackWarnings(CtrmapMainframe.frame, warnings);
		}
	}

	/**
	 * Which EDITION of the open game the dump is - the answer a caller needs
	 * when a game ships more than one and they disagree about their data (the
	 * ORAS Special Demo keeps its location names in another GameText entry).
	 *
	 * <p>The probing is the profile's: this asks
	 * {@link ctrmap.gamedef.GameProfile#detectVariant}, so no caller outside
	 * the gamedef seam has to know what file identifies a demo. With no session
	 * open it probes the configured game folder, which is what the setup wizard
	 * needs before anything has validated.
	 *
	 * @return RETAIL when no game folder is configured at all - a caller with
	 * nothing to probe is not looking at a demo
	 */
	public static ctrmap.gamedef.GameProfile.Variant variant() {
		if (current != null) {
			return current.variant();
		}
		if (GAMEDIR_PATH == null) {
			return ctrmap.gamedef.GameProfile.Variant.RETAIL;
		}
		File dir = new File(GAMEDIR_PATH);
		ctrmap.gamedef.GameProfile p = ctrmap.gamedef.GameProfile.detect(dir);
		return p == null ? ctrmap.gamedef.GameProfile.Variant.RETAIL : p.detectVariant(dir);
	}

	//isOA(), isXY() and isOADemo() USED TO BE HERE. They were the migration's
	//ramp: deprecated in place so the 44 call sites could move one at a time,
	//and deleted once the last one had. Nothing asks which game is open any
	//more - callers ask profile() what the game can DO, what it MEASURED, or
	//variant() which EDITION it is - and SourceSeamTest.noApplicationClassAsks
	//WhichGameIsLoaded reads the compiled classes to keep it that way. A gate
	//that answers a four-valued question with a boolean cannot come back by
	//being written somewhere else.

	public static void prefsPutNonNull(String key, String value) {
		if (value != null && key != null) {
			prefs.put(key, value);
		}
	}

	public static void saveWorkspace() {
		if (prefs == null) {
			return; //loadWorkspace() never ran: a headless tool, not the app
		}
		prefsPutNonNull("WORKSPACE_PATH", WORKSPACE_PATH);
		prefsPutNonNull("GAMEDIR_PATH", GAMEDIR_PATH);
		prefsPutNonNull("ESPICA_PATH", ESPICA_PATH);
		prefs.putBoolean("TILESET_DEFAULT", TILESET_DEFAULT);
		prefsPutNonNull("TILESET_PATH", TILESET_PATH);
		try {
			prefs.flush(); //settings survive a crash, not just a clean exit
		} catch (java.util.prefs.BackingStoreException ex) {
			Logger.getLogger(Workspace.class.getName()).log(Level.SEVERE, null, ex);
		}
		if (current == null) {
			return; //no workspace has validated yet, so there is no file to write
		}
		current.savePersist();
	}
}
