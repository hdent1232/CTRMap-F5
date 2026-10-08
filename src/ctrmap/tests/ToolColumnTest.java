package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.humaninterface.FormPanel;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/**
 * The World Editor's tool column asks the pane it is about to replace, and the registry editors
 * answer what their save answered (F105).
 *
 * <p>WHY THIS SUITE EXISTS. Both registry editors' close handlers ended "return true", under a
 * javadoc saying they answer false when the save was refused, and the tool column they open into
 * replaced its pane with no question at all - the Extras tab was the only host that asked. So the
 * entry being edited was dropped on any tool switch, zone switch or second Edit registry data
 * click, and a refused save was walked past.
 *
 * <p>ORDER: needs no dump and no display. The editors themselves carry a 3D preview no suite here
 * can make, so their handlers are read in their source.
 *
 * Usage: java ctrmap.tests.ToolColumnTest
 */
public class ToolColumnTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		System.out.println("--- the tool column asks the pane it replaces (F105)");
		Asking keeps = new Asking(false);
		Asking lets = new Asking(true);
		check(!mayLeave(new JScrollPane(keeps), new JPanel()) && keeps.asked == 1,
				"a pane that will not let its edit go keeps the column, and was asked once");
		check(mayLeave(lets, new JPanel()) && lets.asked == 1, "a pane that lets go is replaced, bare as well as wrapped");
		Asking same = new Asking(false);
		check(mayLeave(new JScrollPane(same), same) && same.asked == 0, "showing the pane already there asks nothing");
		check(mayLeave(new JPanel(), new JPanel()) && mayLeave(null, new JPanel()), "a pane that is not a form, or none, is simply replaced");

		System.out.println("--- the column's one way in uses it, and the editors answer for their saves (F105)");
		String frame = source("src/ctrmap/CtrmapMainframe.java");
		String sw = CollisionReadTest.body(frame, "public void switchToolUI(JComponent rightComponent)");
		check(sw != null && sw.contains("jsp.setRightComponent") && sw.indexOf("public static", 1) < 0, "control: switchToolUI is found, whole and alone");
		check(sw != null && sw.contains("mayLeave(") && sw.indexOf("mayLeave(") < sw.indexOf("setRightComponent"),
				"switchToolUI asks before it replaces anything");
		String npc = CollisionReadTest.body(source("src/ctrmap/humaninterface/NPCRegistryEditor.java"), "public boolean closeRequested()");
		String prop = CollisionReadTest.body(source("src/ctrmap/humaninterface/ADPropRegistryEditor.java"), "public boolean closeRequested()");
		check(npc != null && prop != null && npc.contains("saveEntry(true)") && prop.contains("saveEntry(true)"),
				"control: both editors' close handlers are found");
		check(npc != null && npc.replaceAll("\\s+", " ").contains("return saveEntry(true);"),
				"the NPC registry editor answers what its save answered");
		check(prop != null && prop.replaceAll("\\s+", " ").contains("if (!saveEntry(true)) { return false; }"),
				"the prop registry editor answers false when its save was refused");

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/** A pane with an answer, counting how often it was asked. */
	static final class Asking extends FormPanel {

		final boolean answer;
		int asked;

		Asking(boolean answer) {
			this.answer = answer;
		}

		@Override
		public boolean closeRequested() {
			asked++;
			return answer;
		}
	}

	static boolean mayLeave(java.awt.Component outgoing, JComponent incoming) throws Exception {
		Method m = CtrmapMainframe.class.getDeclaredMethod("mayLeave", java.awt.Component.class, JComponent.class);
		m.setAccessible(true);
		return (Boolean) m.invoke(null, outgoing, incoming);
	}

	static String source(String path) throws Exception {
		return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
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
