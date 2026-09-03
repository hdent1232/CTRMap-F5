package ctrmap.tests;

import ctrmap.Ui;
import ctrmap.Workspace;
import ctrmap.humaninterface.WorkspaceSettings;
import ctrmap.setup.SetupWizard;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
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
 * <p>{@link #theSaveThatWasRefused} needs the settings window itself and so
 * needs a display; it prints a skip without one. The battery runner passes no
 * headless flag, so it runs there.
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

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}
