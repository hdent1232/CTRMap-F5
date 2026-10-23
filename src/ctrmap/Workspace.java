package ctrmap;

import ctrmap.formats.tilemap.EditorTileset;
import ctrmap.formats.garc.GARC;
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
import javax.swing.JOptionPane;
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
 *
 * <p>No undo: its one edit of its own is cleaning the workspace, which deletes the files extracted and changed
 * there; {@link #cleanAfterAsking} asks first, counts the edits in the question, offers to pack them into the
 * game before, and cleans nothing when that pack does not finish.
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
	 * Volatile (F016): the building preview's worker reads it while the
	 * event thread may be installing the next game.
	 */
	private static volatile WorkspaceSession current;

	public static void loadWorkspace() {
		prefs = Preferences.userRoot().node(Workspace.class.getName());
		WORKSPACE_PATH = prefs.get("WORKSPACE_PATH", "");
		GAMEDIR_PATH = prefs.get("GAMEDIR_PATH", "");
		ESPICA_PATH = prefs.get("ESPICA_PATH", "");
		TILESET_DEFAULT = prefs.getBoolean("TILESET_DEFAULT", true);
		TILESET_PATH = prefs.get("TILESET_PATH", "");
	}

	/**
	 * The tileset the 2D tile editor draws with: the workspace's own when one is set and reads,
	 * else the bundled one - and when the workspace's own does not read, the user is told why
	 * (it used to be tested for null tiles, which a reader that logged its failures left behind;
	 * a half-read file passed that test with nulls in it).
	 */
	public static EditorTileset getTileset() {
		if (!TILESET_DEFAULT) {
			try {
				if (TILESET_PATH == null || TILESET_PATH.isEmpty()) {
					throw new java.io.IOException("no tileset file is set");
				}
				return new EditorTileset(new File(TILESET_PATH));
			} catch (java.io.IOException unreadable) {
				Ui.error("The tileset " + (TILESET_PATH == null ? "" : TILESET_PATH + " ") + "could not be read, so"
						+ " the default one is used:\n" + Ui.reason(unreadable), "Invalid tileset");
				TILESET_DEFAULT = true;
			}
		}
		try (java.io.InputStream in = ResourceAccess.getStream("DefaultTileset.mets")) {
			return new EditorTileset(in);
		} catch (java.io.IOException broken) {
			throw new IllegalStateException("the default tileset built into the program could not be read: "
					+ Ui.reason(broken), broken);
		}
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
	 * Makes a session the open game, or none.
	 *
	 * <p>This says nothing to anybody. It used to hand the session to the main
	 * window, which is the wrong direction - the state a program keeps should
	 * not know the screen it is drawn on - and it made every suite that
	 * installs a session load the window class. The window is told by whoever
	 * asked for the game: {@link #validate} hands its result back, and the
	 * three UI callers pass it on. A suite that wants the window to know says
	 * so in one line, which is also the honest way to write it down.
	 */
	public static void install(WorkspaceSession s) {
		current = s;
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

	/** See {@link WorkspaceSession#isPendingArtifact}: listed AND still on disk. */
	public static boolean isPendingArtifact(File f) {
		return current != null && current.isPendingArtifact(f);
	}

	/** Unmodifiable: a caller with no session has nothing to mark edited, and trying to is a fault worth hearing about. */
	private static final java.util.List<String> NO_PERSISTED = java.util.Collections.unmodifiableList(new ArrayList<String>());

	/** Scratch directory inside the workspace, or null before validation. */
	public static File temp() {
		return current == null ? null : current.temp();
	}

	public static WorkspaceSession validate(Component parent) {
		return validate(parent, true);
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
	public static WorkspaceSession validate(Component parent, boolean showErrors) {
		return validate(parent, showErrors, null);
	}

	/**
	 * {@link #validate(Component, boolean)}, adding why it failed to {@code problems} - the
	 * words the user is told - for a caller that reports in its own words (the setup wizard).
	 * Handed a list rather than read back from a static: the reasons belong to this one call,
	 * and a static would be a tenth global on a class whose statics are counted (GlobalStateTest).
	 */
	public static WorkspaceSession validate(Component parent, boolean showErrors, java.util.List<String> problems) {
		install(null);
		WorkspaceSession opened;
		try {
			opened = WorkspaceSession.open(WORKSPACE_PATH == null ? null : new File(WORKSPACE_PATH),
					GAMEDIR_PATH == null ? null : new File(GAMEDIR_PATH));
			opened.prepareDirectories();
			reportSnapshot(opened.snapshotOriginals());
			//the location names are read from the session that was just opened, NOW,
			//so a game whose text lacks them is refused here rather than at the first
			//zone dropdown (F231). They are kept in that session's own store (F218),
			//like everything else read from its game: a reset drops the session and
			//them with it, and a pack empties the store. The Pokemon reference tables
			//are read now too, where opening already waits, rather than at the first
			//picker; a pack's emptied store reads them again when next asked.
			LocationNames.loadFromGarc(opened);
			ctrmap.formats.pokedata.PokeData.of(opened);
		} catch (WorkspaceSession.OpenFailed ex) {
			return failedToOpen(parent, showErrors, ex.problems(), problems);
		} catch (RuntimeException ex) {
			//ANY FAILURE, NOT ONLY A MISSING FILE (F231). Only OpenFailed was caught,
			//and that covers files that are absent. A game folder whose archives are
			//all present but whose GameText lacks the location-names entry threw
			//IllegalStateException from loadFromGarc straight past this method, with
			//the old session already gone (install(null) above) and no caller ever
			//reaching onWorkspaceOpened(null) - the method written to close the zone
			//that was open, so its editors were left live over no workspace at all.
			return failedToOpen(parent, showErrors,
					java.util.Collections.singletonList("The game could not be opened: " + Ui.reason(ex)), problems);
		}
		if (showErrors && !opened.openWarnings().isEmpty()) {
			Ui.message(parent, "The game opened, but:\n\n" + String.join("\n", opened.openWarnings()),
					"Damaged archive", JOptionPane.WARNING_MESSAGE);
		}
		install(opened);
		return opened;
	}

	/** Hands why validate failed to {@code out} and, when asked to, shows it; always answers null. */
	private static WorkspaceSession failedToOpen(Component parent, boolean showErrors, java.util.List<String> problems,
			java.util.List<String> out) {
		if (out != null) {
			out.addAll(problems);
		}
		if (!showErrors) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (String s : problems) {
			sb.append(s);
			sb.append("\n");
		}
		sb.append("\nRun Options > Setup wizard to point CTRMap at your game,\n");
		sb.append("then open a map from the zone list in the \"Zone Loader\" tab.");
		Ui.error(parent, sb.toString(), "Setup Error");
		return null;
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

	/**
	 * Forgets the workspace's edits, and the declined fork offers with them.
	 *
	 * @throws IOException when the edits were cleaned but the declines could not be forgotten -
	 * said, not logged: a log line nobody reads left the old answers silencing the offer (F016's sweep)
	 */
	public static void cleanAll() throws IOException {
		if (current != null) {
			current.cleanAll();
		} else if (WORKSPACE_PATH != null) {
			WorkspaceSession.cleanDirectories(new File(WORKSPACE_PATH), true, NO_PERSISTED);
		}
		IOException notForgotten = null;
		if (WORKSPACE_PATH != null) {
			//A CLEAN FORGETS THE EDITS, AND THE DECLINED FORK OFFERS WITH THEM (F253): the zones
			//are the game's own again, so a shared map is worth asking about again
			try {
				ForkDeclines.forget(new File(WORKSPACE_PATH));
			} catch (IOException ex) {
				notForgotten = ex;   //thrown below, once the settings are saved as cleaned
			}
		}
		saveWorkspace();
		if (notForgotten != null) {
			throw new IOException("The workspace was cleaned, but the zones you declined a private map for could not be"
					+ " forgotten (" + ctrmap.util.Bytes.reason(notForgotten) + "), so those offers stay silent until the"
					+ " file " + ForkDeclines.FILE + " is removed.", notForgotten);
		}
	}

	/**
	 * Sweeps the extracted copies nobody edited - the window does this on close.
	 *
	 * <p>WITH NO SESSION OPEN, THE EDITS ARE STILL ON DISK AND STILL LISTED. This
	 * branch used to sweep against an empty list, so after a game folder that
	 * would not open (a damaged archive, a moved dump) closing the window
	 * deleted every unpacked edit along with the scratch (census H.13, F231).
	 * The workspace folder carries its own list; it is read here, and a list
	 * that cannot be read sweeps nothing.
	 */
	public static void cleanUnchanged() {
		if (current != null) {
			current.cleanUnchanged();
		} else if (WORKSPACE_PATH != null) {
			File ws = new File(WORKSPACE_PATH);
			java.util.List<String> edits;
			try {
				edits = WorkspaceSession.persistedIn(ws);
			} catch (java.io.IOException unreadable) {
				Logger.getLogger(Workspace.class.getName()).log(Level.SEVERE,
						"the workspace's list of edited files could not be read; nothing was swept", unreadable);
				return;
			}
			WorkspaceSession.cleanDirectories(ws, false, edits);
		}
	}

	/**
	 * The user's edits the workspace holds: the open session's list, or with no
	 * session, the workspace folder's own list on disk.
	 *
	 * @throws java.io.IOException with no session open, when the folder's list
	 *         exists and cannot be read
	 */
	public static java.util.List<String> editsOnDisk() throws java.io.IOException {
		if (current != null) {
			return current.persistPaths();
		}
		return WORKSPACE_PATH == null ? NO_PERSISTED : WorkspaceSession.persistedIn(new File(WORKSPACE_PATH));
	}

	/**
	 * Empties the workspace - every extracted file, the user's unpacked edits
	 * included - once the user has agreed, offering to pack the edits into the
	 * game first.
	 *
	 * <p>ONE QUESTION, ASKED BY BOTH DOORS. Options &gt; Clean workspace deleted
	 * every edit with no question at all (F033), while the settings dialog's
	 * game-folder change asked - and then, when the user chose to pack first,
	 * ignored a pack that FAILED and cleaned anyway, destroying exactly the
	 * edits the user had just asked to keep (census H.69). Both now come here:
	 * the count of edits is in the question, closing it is Cancel, and "pack
	 * first" cleans only after a pack that finished.
	 *
	 * @param why the first paragraph of the question: why this clean is happening
	 * @param before what the caller needs done once the answer is final and before
	 *        any file goes - unloading the editors that show the files
	 * @return true when the workspace was cleaned
	 */
	public static boolean cleanAfterAsking(java.awt.Component parent, String why, Runnable before) {
		int edits;
		try {
			edits = editsOnDisk().size();
		} catch (java.io.IOException unreadable) {
			Ui.error(parent, "The workspace's list of which files are your edits could not be read, so "
					+ "cleaning would delete edits it cannot tell apart from scratch. Nothing was cleaned.\n\n"
					+ Ui.reason(unreadable), "Clean workspace");
			return false;
		}
		String question = why + "\n\n"
				+ (edits == 0 ? "No file in the workspace is marked as your edit."
						: edits + " file(s) in the workspace are your edits. Anything not yet packed into the"
						+ " game is lost when the workspace is cleaned.")
				+ "\n\nPack your edits into the game before the workspace is cleaned?"
				+ "\n    Yes - pack, then clean (if the pack does not finish, nothing is cleaned)"
				+ "\n    No - clean without packing"
				+ "\n    Cancel - leave everything as it is";
		int res = Ui.confirm(parent, question, "Clean workspace", JOptionPane.YES_NO_CANCEL_OPTION,
				JOptionPane.WARNING_MESSAGE);
		if (res == JOptionPane.YES_OPTION) {
			if (current == null) {
				Ui.error(parent, "No game is open, so there is nothing to pack your edits into. Nothing was"
						+ " cleaned.", "Clean workspace");
				return false;
			}
			final boolean[] packed = {false};
			packWorkspace(() -> packed[0] = true);
			if (!packed[0]) {
				return false; //the pack said why; its edits are still here because nothing was cleaned
			}
		} else if (res != JOptionPane.NO_OPTION) {
			return false; //Cancel, or the question closed: not consent to delete anything
		}
		before.run();
		try {
			cleanAll();
		} catch (IOException partly) {
			Ui.error(parent, ctrmap.util.Bytes.reason(partly), "Clean workspace");
		}
		return true;
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
	@ctrmap.util.ForSuites("SEAM: the static facade over the current session that the suites drive; the application asks the session it holds")
	public static File originalSnapshotDir() {
		File ws = configuredWorkspaceDir();
		return ws == null ? null : WorkspaceSession.originalSnapshotDir(ws);
	}

	/** Records which game folder a pristine snapshot was taken from. */
	@ctrmap.util.ForSuites("SEAM: the static facade over the current session that the suites drive; the application asks the session it holds")
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
	@ctrmap.util.ForSuites("SEAM: the static facade over the current session that the suites drive; the application asks the session it holds")
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
	public static void discardSnapshot() throws IOException {
		File ws = configuredWorkspaceDir();
		if (ws != null) {
			WorkspaceSession.discardSnapshot(ws);
		}
	}

	/** Archives the snapshot is supposed to hold but does not; empty with no session. */
	@ctrmap.util.ForSuites("SEAM: the static facade over the current session that the suites drive; the application asks the session it holds")
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
		ctrmap.Ui.error(text, "Pristine backup");
	}

	/** Lets a suite exercise more than one snapshot problem in one JVM. */
	@ctrmap.util.ForSuites("SEAM: lets a suite exercise more than one snapshot problem in one JVM")
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
		//what was read from the game - the location names, the Pokemon reference
		//tables, the prop database - lived in the session's store and went with it

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
	@ctrmap.util.ForSuites("SEAM: the static facade over the current session that the suites drive; the application asks the session it holds")
	public static java.util.List<String> snapshotOriginals() {
		if (current == null) {
			return new ArrayList<>();
		}
		return reportSnapshot(current.snapshotOriginals());
	}

	/** The three things a backup can have wrong, worded for the user. */
	public static java.util.List<String> reportSnapshot(WorkspaceSession.SnapshotReport report) {
		if (report.foreignTakenFrom != null) {
			Ui.error("This workspace holds a pristine backup of a different game folder:\n  "
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

	/**
	 * Text file {@code entry}'s lines as the workspace holds it - or none, with why said into {@code unread}, for
	 * an editor that shows numbers where names are missing and says so (census M.55). The one door: five
	 * editors each read their names through a private copy that answered no lines, said nothing, for any failure.
	 */
	public static String[] textLines(int entry, java.util.List<String> unread) {
		return ctrmap.formats.text.GameTextLines.orSaid(() -> {
			File f = getWorkspaceFile(ArchiveType.GAMETEXT, entry);
			return f == null ? null : java.nio.file.Files.readAllBytes(f.toPath());
		}, entry, unread);
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
	@ctrmap.util.ForSuites("SEAM: the static facade over the current session that the suites drive; the application asks the session it holds")
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
		if (current == null) {
			//SAID, NOT SKIPPED (F230): this returned without a word and dropped onDone, and File >
			//Pack Workspace was wired straight here - with no game set up, a click did nothing and
			//said nothing, where every sibling action refuses by name
			Ui.error(Ui.parent(), "Load a workspace first (Options > Workspace settings).", "Pack workspace");
			return;
		}
		//a read-only session (a suite over the pristine dump) has nothing to
		//commit TO; the application never opens one
		if (!current.isReadOnly()) {
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
						Ui.error("The workspace was not packed:\n" + cause
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
			reportPackWarnings(Ui.parent(), warnings);
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
	@ctrmap.util.ForSuites("SEAM: the static facade over the current session that the suites drive; the application asks the session it holds")
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
