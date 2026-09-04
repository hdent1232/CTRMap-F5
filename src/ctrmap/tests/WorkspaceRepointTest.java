package ctrmap.tests;

import ctrmap.Ui;
import ctrmap.Workspace;
import ctrmap.humaninterface.WorkspaceSettings;
import ctrmap.setup.DumpCheck;
import ctrmap.setup.SetupWizard;
import java.awt.Frame;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.prefs.Preferences;
import javax.swing.JOptionPane;
import javax.swing.JTextField;

/**
 * Pointing a workspace at a different game folder, and what happens to the
 * pristine backup when it does.
 *
 * <p>The backup is how CTRMap works out what the user changed and where donor
 * buildings are cut from. A backup taken from ANOTHER game folder is worse than
 * none: Deploy ships archives nobody touched, the palette cuts donors out of the
 * wrong game, and nothing says so. Both places that can repoint a workspace -
 * Options &gt; Workspace settings, and the first-run wizard - decide this, and
 * every branch of both decisions was unmeasured, because each one is guarded by
 * a modal confirm that no suite can answer, inside a window (a JFrame and a
 * JDialog) that a headless suite cannot even build. A mutation sweep confirmed
 * it: the settings dialog's four decision lines and the wizard's one all
 * survived the whole battery.
 *
 * <p>Both decisions are now static and ask through {@link Ui}, so they can be
 * driven with an answer supplied. What is asserted is what the user would
 * notice: they are told, saying no really does leave everything alone, closing
 * the question is not consent, and saying yes actually discards the wrong
 * game's backup rather than pretending to.
 *
 * <p>{@link #theSaveThatWasRefused} and {@link #theFinishThatWasRefused} need
 * the settings window and the wizard themselves and so need a display; they
 * print a skip without one. The battery runner passes no headless flag, so they
 * run there.
 *
 * Usage: java ctrmap.tests.WorkspaceRepointTest &lt;romfs-root&gt;
 */
public class WorkspaceRepointTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump);
			System.out.println("ALL PASS");
			return;
		}
		ScratchGame.open(dump);
		String own = Workspace.GAMEDIR_PATH;
		//a second game folder to point at; it need not hold anything, because
		//what is being decided is which game the BACKUP belongs to
		String other = new File(Scratch.dir("ctrmap_other_game"), "game").getAbsolutePath();

		settingsDialog(own, other);
		firstRunWizard(own, other);
		theSaveThatWasRefused(own, other);
		theFinishThatWasRefused(own, other);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** Options &gt; Workspace settings, with the game folder changed. */
	static void settingsDialog(String own, String other) {
		freshBackupOf(own);
		check(sameFolder(own), "the backup is this game folder's to begin with");

		List<String> said = Ui.record();
		boolean go;
		try {
			go = WorkspaceSettings.keepOrRetakeBackup(null, own);
		} finally {
			Ui.stopRecording();
		}
		check(go, "settings: keeping the same game folder goes ahead");
		check(said.isEmpty(), "settings: and asks nothing, since there is nothing wrong; said " + said);
		check(haveBackup(), "settings: and keeps the backup");

		said = Ui.record(JOptionPane.NO_OPTION);
		try {
			go = WorkspaceSettings.keepOrRetakeBackup(null, other);
		} finally {
			Ui.stopRecording();
		}
		check(!said.isEmpty() && said.get(0).contains("another game"),
				"settings: switching folder says the backup belongs to the old one: " + said);
		check(!go, "settings: and answering no refuses the switch");
		check(haveBackup(), "settings: and leaves the backup where it was");

		said = Ui.record(); //nothing answered - the user closed the window
		try {
			go = WorkspaceSettings.keepOrRetakeBackup(null, other);
		} finally {
			Ui.stopRecording();
		}
		check(!go, "settings: closing the question is not consent to switch");
		check(haveBackup(), "settings: and closing it destroys nothing");

		said = Ui.record(JOptionPane.YES_OPTION);
		try {
			go = WorkspaceSettings.keepOrRetakeBackup(null, other);
		} finally {
			Ui.stopRecording();
		}
		check(go, "settings: answering yes lets the switch happen");
		check(!haveBackup(), "settings: and the other game's backup is actually discarded, not just promised");
	}

	/** The first-run wizard, finishing on a working folder that already has one. */
	static void firstRunWizard(String own, String other) {
		freshBackupOf(own);

		List<String> said = Ui.record();
		boolean go;
		try {
			go = SetupWizard.backupBelongsHere(null, own);
		} finally {
			Ui.stopRecording();
		}
		check(go, "wizard: a working folder holding this game's backup finishes setup");
		check(said.isEmpty(), "wizard: and asks nothing; said " + said);
		check(haveBackup(), "wizard: and the backup it already had is kept");

		said = Ui.record(JOptionPane.NO_OPTION);
		try {
			go = SetupWizard.backupBelongsHere(null, other);
		} finally {
			Ui.stopRecording();
		}
		check(!said.isEmpty() && said.get(0).contains("another game"),
				"wizard: a working folder holding another game's backup says so: " + said);
		check(!go, "wizard: and answering no sends setup back to pick a different folder");
		check(haveBackup(), "wizard: and nothing is thrown away on the way back");

		said = Ui.record(); //nothing answered - the user closed the window
		try {
			go = SetupWizard.backupBelongsHere(null, other);
		} finally {
			Ui.stopRecording();
		}
		check(!go, "wizard: closing the question is not consent to replace the backup");
		check(haveBackup(), "wizard: and closing it destroys nothing");

		said = Ui.record(JOptionPane.YES_OPTION);
		try {
			go = SetupWizard.backupBelongsHere(null, other);
		} finally {
			Ui.stopRecording();
		}
		check(go, "wizard: answering yes finishes setup");
		check(!haveBackup(), "wizard: and the other game's backup is actually discarded");
	}

	/**
	 * Saving the settings after refusing the switch must stop right there.
	 *
	 * <p>The refusal is one line in {@code save()} - "the user would rather not
	 * move the workspace after all". Without it the save carries straight on to
	 * the next question and repoints the workspace at the new folder anyway,
	 * keeping the backup that belongs to the old one: the exact state the user
	 * had just declined.
	 */
	static void theSaveThatWasRefused(String own, String other) throws Exception {
		if (java.awt.GraphicsEnvironment.isHeadless()) {
			System.out.println("  skip: no display, and the settings window is a JFrame");
			return;
		}
		freshBackupOf(own);
		Workspace.GAMEDIR_PATH = own;
		WorkspaceSettings form = new WorkspaceSettings();
		try {
			JTextField gameField = field(form, "gameField");
			gameField.setText(other);
			//no to the backup question; the second answer is only reached when
			//the refusal is ignored, and CANCEL makes that harmless
			List<String> said = Ui.record(JOptionPane.NO_OPTION, JOptionPane.CANCEL_OPTION);
			try {
				form.save();
			} finally {
				Ui.stopRecording();
			}
			check(said.size() == 1, "save: refusing the backup question ends the save there,"
					+ " with no further questions; the user was asked " + said.size() + ": " + said);
			check(own.equals(gameField.getText()),
					"save: and the game folder is put back to " + own + " (it reads " + gameField.getText() + ")");
			check(own.equals(Workspace.GAMEDIR_PATH),
					"save: and the workspace still points at the game it was set up with");
			check(haveBackup(), "save: and the backup is untouched");
		} finally {
			form.dispose();
		}
	}

	/**
	 * Finishing the first-run wizard after refusing to replace another game's
	 * backup must stop right there.
	 *
	 * <p>The refusal is one line in {@code doFinish} - the same line, in the
	 * other window that can point a workspace at a game folder, as the one
	 * {@link #theSaveThatWasRefused} covers. Without it the wizard walks past
	 * the answer and sets up anyway: it keeps a pristine backup taken from a
	 * DIFFERENT game folder, which is the one state the user had just declined,
	 * and from then on every "what did I change" answer and every donor
	 * building is cut out of the wrong game with nothing saying so.
	 *
	 * <p>The question is left CLOSED rather than answered No, because closed is
	 * the answer nobody-is-there gives and it is the reading that is easiest to
	 * lose: a later edit that tests for the option it skips instead of the one
	 * it acts on turns "the user walked away" into consent to throw the backup
	 * out. Both readings must refuse.
	 */
	static void theFinishThatWasRefused(String own, String other) throws Exception {
		if (java.awt.GraphicsEnvironment.isHeadless()) {
			System.out.println("  skip: no display, and the wizard is a JDialog");
			return;
		}
		freshBackupOf(own);
		//the wizard's constructor and step machine are private because nothing
		//but the app should drive it; SetupWizardTest reaches them the same way
		Constructor<SetupWizard> ctor = SetupWizard.class.getDeclaredConstructor(Frame.class);
		ctor.setAccessible(true);
		SetupWizard w = ctor.newInstance((Frame) null);
		//doFinish writes the wizard's "do not show this again" preference, which
		//belongs to whoever owns this machine and not to a test run
		Preferences prefs = Preferences.userRoot().node("ctrmap.setup");
		boolean suppressed = prefs.getBoolean("SKIP_SETUP_ON_STARTUP", false);
		try {
			//a dump the wizard would accept, so the run reaches the decision
			DumpCheck.Result usable = new DumpCheck.Result();
			usable.status = DumpCheck.Status.VALID;
			usable.game = Workspace.GameType.ORAS;
			set(w, "gameResult", usable);
			WorkspaceRepointTest.<JTextField>field(w, "gameField").setText(other);
			WorkspaceRepointTest.<JTextField>field(w, "wsField").setText(Workspace.WORKSPACE_PATH);
			call(w, "showStep", new Class<?>[]{int.class}, constant("STEP_FINISH"));

			List<String> said = Ui.record(); //closed, not answered
			try {
				call(w, "doFinish", new Class<?>[0]);
			} finally {
				Ui.stopRecording();
			}
			check(said.size() == 1 && said.get(0).contains("another game"),
					"finish: setting up on a working folder that holds another game's backup asks about it: " + said);
			int step = field(w, "step");
			check(step == constant("STEP_WORKSPACE"),
					"finish: closing that question sends the wizard back to the working-folder step"
					+ " instead of setting up anyway (it is on step " + step + ")");
			check(haveBackup(), "finish: and the backup is still there");
			check(sameFolder(own), "finish: and it still belongs to " + own + ", the folder it was taken from");
		} finally {
			prefs.putBoolean("SKIP_SETUP_ON_STARTUP", suppressed);
			w.dispose();
		}
	}

	/** Retakes the pristine backup so it belongs to the given game folder. */
	static void freshBackupOf(String gameDir) {
		Workspace.GAMEDIR_PATH = gameDir;
		Workspace.discardSnapshot();
		Workspace.snapshotOriginals();
	}

	static boolean haveBackup() {
		return Workspace.originalSnapshotStamp().isFile();
	}

	static boolean sameFolder(String gameDir) {
		return !Workspace.snapshotIsForeign(gameDir);
	}

	@SuppressWarnings("unchecked")
	static <T> T field(Object o, String name) throws Exception {
		Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return (T) f.get(o);
	}

	static void set(Object o, String name, Object value) throws Exception {
		Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		f.set(o, value);
	}

	static void call(Object o, String name, Class<?>[] sig, Object... args) throws Exception {
		Method m = o.getClass().getDeclaredMethod(name, sig);
		m.setAccessible(true);
		m.invoke(o, args);
	}

	/** One of the wizard's private step numbers, read rather than copied. */
	static int constant(String name) throws Exception {
		Field f = SetupWizard.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.getInt(null);
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
