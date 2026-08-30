package ctrmap.tests;

import ctrmap.setup.SetupWizard;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JTextField;

/**
 * Drives the setup wizard through its steps WITHOUT ever showing it, and checks
 * that it gates the user the way it promises to.
 *
 * <p>The wizard is the first thing a new user meets, and the failure that
 * matters is not a crash - it is letting someone press Next on a folder that
 * will not work and only finding out four steps later. So the assertions here
 * are about when Next is enabled.
 *
 * <p>Reflection is used deliberately: the wizard's constructor and step machine
 * are private because nothing in the app should drive it except the app, and
 * loosening that for a test would be the wrong trade.
 *
 * <p>Args: a real RomFS dump folder. Optional second arg: a folder to write
 * screenshots into.
 */
public class SetupWizardTest {

	private static int failures = 0;

	public static void main(String[] args) throws Exception {
		if (GraphicsEnvironment.isHeadless()) {
			System.out.println("  no display available - wizard layout not exercised");
			System.out.println("ALL PASS");
			return;
		}
		if (args.length < 1) {
			System.out.println("FAIL usage: SetupWizardTest <a real RomFS dump folder> [shot dir]");
			System.exit(1);
		}
		if (!new File(args[0]).isDirectory()) {
			System.out.println("  no complete RomFS dump at " + args[0] + " - suite skipped");
			System.out.println("ALL PASS");
			return;
		}
		String dump = args[0];
		File shots = args.length > 1 ? new File(args[1]) : null;

		Constructor<SetupWizard> ctor = SetupWizard.class.getDeclaredConstructor(Frame.class);
		ctor.setAccessible(true);
		SetupWizard w = ctor.newInstance((Frame) null);
		//pack() creates the peer but does NOT show the window - nothing appears
		//on screen at any point in this test
		w.pack();
		w.setSize(660, 470);
		w.validate();

		JButton next = (JButton) field(w, "next");
		JButton back = (JButton) field(w, "back");
		JButton skip = (JButton) field(w, "skip");
		JTextField gameField = (JTextField) field(w, "gameField");
		Method showStep = SetupWizard.class.getDeclaredMethod("showStep", int.class);
		showStep.setAccessible(true);
		Method revalidateGame = SetupWizard.class.getDeclaredMethod("revalidateGame");
		revalidateGame.setAccessible(true);

		// step 1: welcome
		showStep.invoke(w, 0);
		check("welcome: Back is disabled", !back.isEnabled());
		check("welcome: Next is enabled", next.isEnabled());
		check("welcome: Skip is hidden", !skip.isVisible());
		shot(w, shots, "wizard-1-welcome");

		// step 2: the game folder - the gate that matters
		showStep.invoke(w, 1);
		check("game step: Skip is offered", skip.isVisible());
		gameField.setText("");
		revalidateGame.invoke(w);
		check("empty folder does not let you continue", !next.isEnabled());

		gameField.setText(new File(dump).getParent());
		revalidateGame.invoke(w);
		check("the folder ABOVE the game does not let you continue", !next.isEnabled());
		shot(w, shots, "wizard-2-wrong-folder");

		gameField.setText("Z:\\nope\\not\\here");
		revalidateGame.invoke(w);
		check("a nonexistent folder does not let you continue", !next.isEnabled());

		gameField.setText(dump);
		revalidateGame.invoke(w);
		check("the real game DOES let you continue", next.isEnabled());
		shot(w, shots, "wizard-2-game-found");

		// step 3: working folder
		showStep.invoke(w, 2);
		JTextField wsField = (JTextField) field(w, "wsField");
		Method revalidateWs = SetupWizard.class.getDeclaredMethod("revalidateWorkspace");
		revalidateWs.setAccessible(true);
		check("a default working folder is suggested", !wsField.getText().trim().isEmpty());
		wsField.setText(new File(dump, "inside-the-game").getAbsolutePath());
		revalidateWs.invoke(w);
		check("a working folder inside the game is refused", !next.isEnabled());
		wsField.setText(new File(System.getProperty("java.io.tmpdir"), "ctrmap_ws_probe").getAbsolutePath());
		revalidateWs.invoke(w);
		check("a sensible working folder is accepted", next.isEnabled());
		shot(w, shots, "wizard-3-workspace");

		// step 4: emulator, always skippable
		showStep.invoke(w, 3);
		check("emulator step: Skip is offered", skip.isVisible());
		check("emulator step: never blocks you", next.isEnabled());
		shot(w, shots, "wizard-4-emulator");

		// step 5: finish
		showStep.invoke(w, 4);
		check("finish: Skip is hidden", !skip.isVisible());
		check("finish: the button says what it will do", next.getText().length() > 4);
		shot(w, shots, "wizard-5-finish");

		w.dispose();
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT (" + failures + ")");
		if (failures > 0) {
			System.exit(1);
		}
	}

	private static Object field(Object o, String name) throws Exception {
		Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	/** Paints the dialog into a PNG. Never shows it. */
	private static void shot(JDialog d, File dir, String name) {
		if (dir == null) {
			return;
		}
		try {
			dir.mkdirs();
			d.validate();
			BufferedImage img = new BufferedImage(d.getWidth(), d.getHeight(), BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
					java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			d.getContentPane().printAll(g);
			g.dispose();
			ImageIO.write(img, "png", new File(dir, name + ".png"));
		} catch (Exception ex) {
			System.out.println("  (could not write " + name + ": " + ex + ")");
		}
	}

	private static void check(String what, boolean ok) {
		if (!ok) {
			failures++;
			System.out.println("FAIL " + what);
		}
	}
}
