package ctrmap.tests;

import ctrmap.Ui;
import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.gamedef.GameType;
import ctrmap.humaninterface.WorkspaceSettings;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import javax.swing.JOptionPane;
import javax.swing.JTextField;

/**
 * Cleaning the workspace deletes the user's unpacked edits, so it may only
 * happen when the user agreed - and never when they chose to keep the edits.
 *
 * <p>FOUR WAYS IT DID NOT. (1) Options &gt; Clean workspace deleted every
 * edited file with no question at all (F033), one click on a menu item named
 * like housekeeping. (2) The settings dialog's game-folder change asked
 * "commit your changes beforehand?", and on Yes packed - then fell through to
 * the cleanup whether or not the pack finished, so a pack that failed was
 * followed by deleting the very edits it had been asked to keep (census H.69).
 * (3) Closing the window with no game open - which is what a game folder that
 * would not open leaves - swept the workspace against an EMPTY list of edits,
 * because the list it asked was the absent session's (census H.13, F231).
 * (4) A list of edits that could not be read on open was logged and treated as
 * empty, and the next window close swept the edits as scratch.
 *
 * <p>No game data is needed: every case is a scratch workspace holding one
 * file marked as an edit and one that is not.
 */
public class WorkspaceCleanGuardsTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		Workspace.WORKSPACE_PATH = null;
		theMenuDoorAsksFirst();
		theAnswersDoWhatTheySay();
		aPackThatDidNotFinishCleansNothing();
		theSettingsDoorKeepsTheEditsWhenThePackFails();
		withNoGameOpenTheEditsListedOnDiskSurvive();
		anUnreadableListSweepsNothing();
		Workspace.install(null);
		Workspace.WORKSPACE_PATH = null;
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** A workspace with one edited file and one scratch file in zonedata, and a session over it. */
	static final class Ws {
		final File dir;
		final File edit;
		final File scratch;
		final WorkspaceSession session;

		Ws(boolean readOnly) throws IOException {
			dir = Scratch.dir("ctrmap_clean_ws");
			File game = new File(dir.getParentFile(), dir.getName() + "_game");
			WorkspaceSession s = new WorkspaceSession(dir, game, GameType.ORAS, null);
			s.prepareDirectories();
			File zd = new File(dir, "zonedata");
			edit = new File(zd, "5");
			scratch = new File(zd, "6");
			Files.write(edit.toPath(), new byte[]{1, 2, 3});
			Files.write(scratch.toPath(), new byte[]{4, 5, 6});
			s.addPersist(edit);
			s.savePersist();
			session = readOnly ? s.readOnly() : s;
			Workspace.install(session);
			Workspace.WORKSPACE_PATH = dir.getAbsolutePath();
		}
	}

	/** Options &gt; Clean workspace, driven through the menu action itself. */
	static void theMenuDoorAsksFirst() throws Exception {
		Ws w = new Ws(false);
		Method action = ctrmap.CtrmapMainframe.class.getDeclaredMethod("cleanWorkspaceAction");
		action.setAccessible(true);

		List<String> said = Ui.record(); //the question closed, not answered
		Throwable thrown = invoke(action);
		Ui.stopRecording();
		check(thrown == null, "menu: closing the question ends the action quietly; it threw " + thrown);
		check(said.size() == 1 && said.get(0).startsWith("Clean workspace:"),
				"menu: Clean workspace asks before it deletes anything; the user was asked " + said.size() + ": " + said);
		check(said.size() == 1 && said.get(0).contains("1 file(s) in the workspace are your edits"),
				"menu: and the question says how many edits are at stake; said " + said);
		check(w.edit.exists() && w.scratch.exists(), "menu: closing the question deletes nothing");

		said = Ui.record(JOptionPane.CANCEL_OPTION);
		thrown = invoke(action);
		Ui.stopRecording();
		check(thrown == null && said.size() == 1, "menu: Cancel is answered with nothing further; said " + said);
		check(w.edit.exists() && w.scratch.exists(), "menu: Cancel deletes nothing");
	}

	/** What No and Yes do once asked: No cleans; the caller's preparation runs first. */
	static void theAnswersDoWhatTheySay() throws Exception {
		Ws w = new Ws(false);
		final boolean[] before = {false};
		final boolean[] filesWhenBefore = {false};
		List<String> said = Ui.record(JOptionPane.NO_OPTION);
		boolean cleaned = Workspace.cleanAfterAsking(null, "why", () -> {
			before[0] = true;
			filesWhenBefore[0] = w.edit.exists() && w.scratch.exists();
		});
		Ui.stopRecording();
		check(cleaned, "No: the workspace is cleaned without packing");
		check(before[0] && filesWhenBefore[0], "No: the caller's preparation runs, and runs BEFORE any file goes");
		check(!w.edit.exists() && !w.scratch.exists(), "No: the edit and the scratch file are both gone");
		check(said.size() == 1, "No: one question, nothing else said; said " + said);
	}

	/**
	 * Yes means "pack, then clean". A pack that does not finish - here a
	 * session that cannot be packed into - must leave everything.
	 */
	static void aPackThatDidNotFinishCleansNothing() throws Exception {
		Ws w = new Ws(true);
		final boolean[] before = {false};
		List<String> said = Ui.record(JOptionPane.YES_OPTION);
		boolean cleaned = Workspace.cleanAfterAsking(null, "why", () -> before[0] = true);
		Ui.stopRecording();
		check(!cleaned, "Yes: a pack that did not finish is not followed by the cleanup");
		check(!before[0], "Yes: and the caller's preparation (unloading the editors) never runs");
		check(w.edit.exists() && w.scratch.exists(), "Yes: and the edits it was asked to keep are all still there");

		Workspace.install(null);
		said = Ui.record(JOptionPane.YES_OPTION);
		cleaned = Workspace.cleanAfterAsking(null, "why", () -> before[0] = true);
		Ui.stopRecording();
		check(!cleaned && w.edit.exists(), "Yes with no game open: nothing to pack into, so nothing is cleaned");
		check(said.size() == 2 && said.get(1).contains("nothing to pack your edits into"),
				"Yes with no game open: and the user is told why; said " + said);
	}

	/** The other door: the settings dialog when the game folder changes (census H.69). */
	static void theSettingsDoorKeepsTheEditsWhenThePackFails() throws Exception {
		Ws w = new Ws(true);
		String own = new File(w.dir.getParentFile(), "own_game").getAbsolutePath();
		String other = new File(w.dir.getParentFile(), "other_game").getAbsolutePath();
		Workspace.GAMEDIR_PATH = own;
		JTextField gameField = new JTextField(other);
		final boolean[] saved = {false};
		List<String> said = Ui.record(JOptionPane.YES_OPTION, JOptionPane.YES_OPTION, JOptionPane.YES_OPTION);
		WorkspaceSettings.repointGameDir(null, gameField, own, () -> saved[0] = true, () -> { });
		Ui.stopRecording();
		boolean asked = false;
		for (String s : said) {
			asked |= s.startsWith("Clean workspace:") && s.contains("game folder has changed");
		}
		check(asked, "settings: the game-folder change asks the clean question; said " + said);
		check(!saved[0], "settings: pack first, pack did not finish - the save stops instead of going on");
		check(own.equals(Workspace.GAMEDIR_PATH),
				"settings: and the workspace still points at its game (" + Workspace.GAMEDIR_PATH + ")");
		check(w.edit.exists() && w.scratch.exists(), "settings: and every edit is still in the workspace");
	}

	/** Closing the window with no game open: the sweep keeps what the folder's own list names. */
	static void withNoGameOpenTheEditsListedOnDiskSurvive() throws Exception {
		Ws w = new Ws(false);
		Workspace.install(null);
		Workspace.cleanUnchanged();
		check(w.edit.exists(), "no game open: the window-close sweep keeps the edit the workspace lists");
		check(!w.scratch.exists(), "no game open: and still sweeps what nobody edited");

		Ws v = new Ws(false);
		Workspace.install(null);
		List<String> edits = Workspace.editsOnDisk();
		check(edits.size() == 1 && edits.get(0).equals(v.edit.getAbsolutePath()),
				"no game open: the edits are read from the folder's list; read " + edits);

		//a line whose file is gone is not an edit (readPersist says why it blocked appends forever)
		File list = WorkspaceSession.persistFile(v.dir);
		Files.write(list.toPath(), (new String(Files.readAllBytes(list.toPath())) + "/zonedata/77\n").getBytes());
		edits = Workspace.editsOnDisk();
		check(edits.size() == 1, "no game open: the list drops the one whose file is gone; read " + edits);
	}

	/** A list of edits that exists and cannot be read: nothing may be swept, open or not. */
	static void anUnreadableListSweepsNothing() throws Exception {
		Ws w = new Ws(false);
		File list = WorkspaceSession.persistFile(w.dir);
		list.delete();
		check(list.mkdir(), "setup: the list is replaced by something that cannot be read as one");
		Workspace.install(null);
		Workspace.cleanUnchanged();
		check(w.edit.exists() && w.scratch.exists(), "unreadable, no game open: the window-close sweep deletes nothing");

		List<String> said = Ui.record(JOptionPane.NO_OPTION);
		boolean cleaned = Workspace.cleanAfterAsking(null, "why", () -> { });
		Ui.stopRecording();
		check(!cleaned && w.edit.exists(), "unreadable: Clean workspace refuses rather than guess which files are edits");
		check(said.size() == 1 && said.get(0).contains("could not be read"), "unreadable: and says so; said " + said);

		//a session opening over that list: its sweep must keep everything too
		WorkspaceSession s = new WorkspaceSession(w.dir, new File(w.dir, "nogame"), GameType.ORAS, null);
		Method read = WorkspaceSession.class.getDeclaredMethod("readPersist");
		read.setAccessible(true);
		read.invoke(s);
		check(s.editListUnreadable(), "unreadable, session: the session knows its edits are unknown");
		s.cleanUnchanged();
		check(w.edit.exists() && w.scratch.exists(), "unreadable, session: its window-close sweep deletes nothing");
	}

	static Throwable invoke(Method m) throws IllegalAccessException {
		try {
			m.invoke(TestWindow.W);   //the window is an object (F007)
			return null;
		} catch (InvocationTargetException ex) {
			return ex.getCause();
		}
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok   " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}
}
