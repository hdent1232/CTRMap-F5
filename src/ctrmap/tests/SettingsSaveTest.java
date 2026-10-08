package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.humaninterface.WorkspaceSettings;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import javax.swing.JOptionPane;
import javax.swing.text.JTextComponent;

/**
 * A settings save that is interrupted changes nothing, and the pane says it did not save (F114).
 *
 * <p>WHY THIS SUITE EXISTS. Workspace settings assigned the workspace, ESPICA and tileset globals
 * first and only then asked about the game folder; declining the backup or cancelling the cleanup
 * "interrupted the saving process" with the four globals already changed and never validated, and
 * the main window wrote them to preferences at exit - the next launch opened a workspace nobody had
 * validated, paired with the old game folder. The pane's close handler answered true either way.
 * The setup wizard did the same with the workspace path when the user went back a step.
 *
 * <p>ORDER: needs no dump and no display. The pane is built without a map; nothing is saved.
 *
 * Usage: java ctrmap.tests.SettingsSaveTest
 */
public class SettingsSaveTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		String ws = Workspace.WORKSPACE_PATH, game = Workspace.GAMEDIR_PATH, spica = Workspace.ESPICA_PATH;
		String tileset = Workspace.TILESET_PATH;
		boolean tilesetDefault = Workspace.TILESET_DEFAULT;
		try {
			run();
		} finally {
			ctrmap.Ui.stopRecording();
			Workspace.WORKSPACE_PATH = ws;
			Workspace.GAMEDIR_PATH = game;
			Workspace.ESPICA_PATH = spica;
			Workspace.TILESET_PATH = tileset;
			Workspace.TILESET_DEFAULT = tilesetDefault;
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	static void run() throws Exception {
		System.out.println("--- an interrupted settings save changes nothing, and says it did not save (F114)");
		File root = Scratch.dir("ctrmap_settings");
		Workspace.WORKSPACE_PATH = new File(root, "old-workspace").getPath();
		Workspace.GAMEDIR_PATH = new File(root, "old-game").getPath();
		Workspace.ESPICA_PATH = "old-espica";
		WorkspaceSettings pane = new WorkspaceSettings(null, () -> { }, opened -> 0, () -> { });
		text(pane, "wsPathField").setText(new File(root, "new-workspace").getPath());
		text(pane, "gameField").setText(new File(root, "new-game").getPath());
		text(pane, "spicaField").setText("new-espica");
		//YES to "save the new settings?", then every question after it closed - which stops the save
		List<String> said = ctrmap.Ui.record(JOptionPane.YES_OPTION);
		boolean leaves = pane.closeRequested();
		check(said.size() >= 2, "control: the save was asked for and then stopped by a question it asked: " + said);
		check(!leaves, "the pane is not let go over a save that did not happen");
		check(Workspace.WORKSPACE_PATH.endsWith("old-workspace") && Workspace.ESPICA_PATH.equals("old-espica")
				&& Workspace.GAMEDIR_PATH.endsWith("old-game"),
				"and none of the settings were assigned: workspace " + Workspace.WORKSPACE_PATH + ", ESPICA "
				+ Workspace.ESPICA_PATH + ", game " + Workspace.GAMEDIR_PATH);

		System.out.println("--- the setup wizard puts the workspace path back when the answer is to go back (F114)");
		String wizard = new String(Files.readAllBytes(new File("src/ctrmap/setup/SetupWizard.java").toPath()), StandardCharsets.UTF_8);
		String finish = CollisionReadTest.body(wizard, "private void doFinish()");
		check(finish != null && finish.contains("settleBackup(") && finish.indexOf("private void", 1) < 0,
				"control: doFinish is found, whole and alone");
		int back = finish == null ? -1 : finish.indexOf("showStep(STEP_WORKSPACE);", finish.indexOf("settleBackup("));
		int restore = finish == null ? -1 : finish.lastIndexOf("Workspace.WORKSPACE_PATH = before;", back);
		check(back > 0 && restore > finish.indexOf("settleBackup("), "going back from the backup question restores the path it set for the asking");
	}

	static JTextComponent text(Object pane, String name) throws Exception {
		Field f = pane.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return (JTextComponent) f.get(pane);
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
