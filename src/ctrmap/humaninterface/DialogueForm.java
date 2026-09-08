package ctrmap.humaninterface;

import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * The wizard for an NPC that says one thing when talked to.
 *
 * <p>One of the ten classes that lived inside {@link NPCEditForm}, which was
 * 2840 lines. Each was an inner class reading the editor it sat in through
 * synthetic bridges, so none could be built without one; each is handed what
 * it needs now and is an ordinary class in a file of its own.
 */
/** The dialogue editor's form: the line, as it is, to retype. */
public final class DialogueForm {

	public final JScrollPane panel;
	private final JTextArea text;

	public DialogueForm(String currentLine) {
		text = Forms.textArea(currentLine, 5);
		panel = new JScrollPane(text);
	}

	public String text() {
		return text.getText();
	}
}
