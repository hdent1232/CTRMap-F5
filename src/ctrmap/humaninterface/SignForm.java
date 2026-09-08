package ctrmap.humaninterface;

import ctrmap.formats.scripts.NpcTemplates;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * The wizard for a sign: a style and the text on it.
 *
 * <p>One of the ten classes that lived inside {@link NPCEditForm}, which was
 * 2840 lines. Each was an inner class reading the editor it sat in through
 * synthetic bridges, so none could be built without one; each is handed what
 * it needs now and is an ordinary class in a file of its own.
 *
 * <p>The sign form: the text and the sign style.
 */
public final class SignForm {

	public final JPanel panel = Forms.stackedForm();
	private final JTextArea text = Forms.textArea("", 5);
	private final javax.swing.JComboBox<String> style = new javax.swing.JComboBox<>(NpcTemplates.SIGN_TYPE_LABELS);

	public SignForm() {
		Forms.addLabeled(panel, "Sign text:", new JScrollPane(text));
		Forms.addLabeled(panel, "Sign style:", style);
		panel.add(Forms.hint("<html>A sign furniture object is placed at the centre of the current view.<br>Edit its exact tile with the Prop tool.</html>"));
	}

	public String text() {
		return text.getText();
	}

	/** The engine's sign type for the chosen style - see {@link NpcTemplates#SIGN_TYPES}. */
	public int signType() {
		return NpcTemplates.SIGN_TYPES[Math.max(0, style.getSelectedIndex())];
	}
}
