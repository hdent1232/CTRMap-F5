package ctrmap.tests;

import ctrmap.Ui;
import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.List;

/**
 * A game folder that cannot be opened is reported, by name, and leaves no
 * session behind - it never throws past {@link Workspace#validate}.
 *
 * <p>THE DEFECT (F231). validate caught only OpenFailed, which covers archives
 * that are ABSENT. An archive that is present and damaged threw RuntimeException
 * out of the session's open, and a game whose archives all open but whose
 * GameText lacks the location-names entry threw IllegalStateException a few
 * lines later - both straight past validate, with the previous session already
 * dropped and no caller ever reaching {@code onWorkspaceOpened(null)}, the
 * method that closes the zone left open. The editor came up, or stayed up, over
 * no workspace with the old zone's editors live, and in the shipped build the
 * exception went nowhere (F219). A TRUNCATED archive opened with only a log
 * line, and the setup wizard told every failure to redo the unpack (census M.140).
 *
 * <p>No game data: the game folders are built here, ORAS-shaped, from synthetic
 * one-entry archives.
 */
public class WorkspaceOpenGuardsTest {

	static int fails = 0;

	static final java.util.List<ArchiveType> REQUIRED = ctrmap.WorkspaceSession.requiredArchives();

	public static void main(String[] args) throws Exception {
		aDamagedArchiveIsNamedNotThrown();
		aFailureAfterTheArchivesOpenedIsReportedToo();
		aTruncatedArchiveIsSaidNotOnlyLogged();
		aFolderThatIsNotAGameIsToldOnlyWhenAskedTo();
		theWizardSaysWhatActuallyFailed();
		Workspace.install(null);
		Workspace.WORKSPACE_PATH = null;
		Workspace.GAMEDIR_PATH = null;
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** An ORAS-shaped game folder: the detection probe and every required archive, one entry each. */
	static File game(ArchiveType broken, boolean truncate) throws Exception {
		File root = Scratch.dir("ctrmap_open_game");
		GameProfile p = GameProfile.of(GameType.ORAS);
		File probe = new File(root, p.detectFile());
		probe.getParentFile().mkdirs();
		probe.createNewFile();
		for (ArchiveType t : REQUIRED) {
			File f = new File(root.getPath() + p.archivePath(t));
			f.getParentFile().mkdirs();
			if (t == broken && !truncate) {
				java.nio.file.Files.write(f.toPath(), new byte[]{'n', 'o', 't', ' ', 'a', ' ', 'G', 'A', 'R', 'C'});
			} else if (t == broken) {
				HandedGameTest.writeGarc(f, new byte[]{1, 2, 3, 4}, new byte[]{5, 6, 7, 8});
				//cut after the first entry's data (header 0x1C + FATO 0x14 + FATB 0x2C + FIMB 0xC
				//puts the data at 0x68): the first entry is whole, the second is not there
				try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
					raf.setLength(0x6C);
				}
			} else {
				HandedGameTest.writeGarc(f, new byte[]{1, 2, 3, 4});
			}
		}
		return root;
	}

	static void pointAt(File game) throws Exception {
		Workspace.GAMEDIR_PATH = game.getAbsolutePath();
		Workspace.WORKSPACE_PATH = Scratch.dir("ctrmap_open_ws").getAbsolutePath();
	}

	static void aDamagedArchiveIsNamedNotThrown() throws Exception {
		pointAt(game(ArchiveType.AREA_DATA, false));
		WorkspaceSession s = null;
		Throwable thrown = null;
		List<String> said = Ui.record();
		try {
			s = Workspace.validate(null, true, fresh());
		} catch (RuntimeException ex) {
			thrown = ex;
		} finally {
			Ui.stopRecording();
		}
		check(thrown == null, "damaged archive: validate answers null rather than throwing; it threw " + thrown);
		check(s == null && !Workspace.isValid(), "damaged archive: and no session is left open");
		check(said.size() == 1 && said.get(0).startsWith("Setup Error:") && said.get(0).contains("AreaData"),
				"damaged archive: the user is told, naming the archive; said " + said);
		check(joined().contains("AreaData GARC could not be read"),
				"damaged archive: the problem is recorded for a caller that reports in its own words: " + joined());

		said = Ui.record();
		try {
			s = Workspace.validate(null, false, fresh());
		} catch (RuntimeException ex) {
			thrown = ex;
		} finally {
			Ui.stopRecording();
		}
		check(thrown == null && s == null, "damaged archive, quiet: null again, nothing thrown (" + thrown + ")");
		check(said.isEmpty() && joined().contains("AreaData"),
				"damaged archive, quiet: nothing shown, the problem still recorded; said " + said + ", recorded " + joined());
	}

	/** Every archive opens; the location names it needs are not in GameText. */
	static void aFailureAfterTheArchivesOpenedIsReportedToo() throws Exception {
		pointAt(game(null, false));
		WorkspaceSession s = null;
		Throwable thrown = null;
		List<String> said = Ui.record();
		try {
			s = Workspace.validate(null, true, fresh());
		} catch (RuntimeException ex) {
			thrown = ex;
		} finally {
			Ui.stopRecording();
		}
		check(thrown == null, "after the open: a failure loading the game's tables is not thrown past validate; it threw " + thrown);
		check(s == null && !Workspace.isValid(), "after the open: no session is left half open");
		check(joined().contains("could not be opened") && joined().contains("Location names"),
				"after the open: the reason is recorded: " + joined());
		check(said.size() == 1 && said.get(0).contains("Location names"), "after the open: and shown; said " + said);
	}

	static void aTruncatedArchiveIsSaidNotOnlyLogged() throws Exception {
		File game = game(ArchiveType.ZONE_DATA, true);
		WorkspaceSession s = WorkspaceSession.open(Scratch.dir("ctrmap_open_ws2"), game);
		String w = String.join("\n", s.openWarnings());
		check(w.contains("ZoneData") && w.contains("TRUNCATED") && w.contains("1 of its 2 entries"),
				"truncated archive: the session opens and says what is damaged: " + w);
	}

	/**
	 * validate shows the user why a folder is not a game when it is asked to, says nothing when it is
	 * not, and hands the reason back either way. The one guard of that switch ran only with a game
	 * dump (WorkspaceSessionTest's failed switch needs an open game first), so on a machine
	 * without one - every cloud checkout - nothing watched it; this needs no game at all.
	 */
	static void aFolderThatIsNotAGameIsToldOnlyWhenAskedTo() throws Exception {
		pointAt(Scratch.dir("ctrmap_open_not_a_game"));
		List<String> said = Ui.record();
		try {
			Workspace.validate(null, true, fresh());
		} finally {
			Ui.stopRecording();
		}
		check(said.size() == 1 && said.get(0).contains("Could not detect game version"),
				"not a game, shown: the user is told what is wrong with the folder they chose; said " + said);
		said = Ui.record();
		try {
			Workspace.validate(null, false, fresh());
		} finally {
			Ui.stopRecording();
		}
		check(said.isEmpty() && joined().contains("Could not detect game version"),
				"not a game, quiet: nothing is shown and the reason is still handed back; said " + said
				+ ", handed back " + joined());
	}

	static void theWizardSaysWhatActuallyFailed() throws Exception {
		Class<?> wiz = Class.forName("ctrmap.setup.SetupWizard");
		java.lang.reflect.Method text = wiz.getDeclaredMethod("loadFailureText", List.class);
		text.setAccessible(true);
		String failed = (String) text.invoke(null, java.util.Arrays.asList("AreaData GARC could not be read (014): x"));
		check(failed.contains("AreaData GARC could not be read") && !failed.contains("probably incomplete"),
				"wizard: a named failure is reported as itself, not as an incomplete unpack: " + failed);
		String empty = (String) text.invoke(null, java.util.Collections.emptyList());
		check(empty.contains("probably incomplete"), "wizard: a game that opened with no maps keeps the unpack advice");
	}

	/** Why the last validate failed, as it handed back to its caller. */
	static final List<String> WHY = new java.util.ArrayList<>();

	/** The list handed to the next validate, emptied first so each check reads only its own call. */
	static List<String> fresh() {
		WHY.clear();
		return WHY;
	}

	static String joined() {
		return String.join(" | ", WHY);
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok   " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}
}
