package ctrmap.humaninterface;

import ctrmap.formats.npcreg.NPCRegistry;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * The wizard for an NPC with a line of dialogue and a model.
 *
 * <p>One of the ten classes that lived inside {@link NPCEditForm}, which was
 * 2840 lines. Each was an inner class reading the editor it sat in through
 * synthetic bridges, so none could be built without one; each is handed what
 * it needs now and is an ordinary class in a file of its own.
 *
 * <p>The talking-NPC form: the dialogue text and the model.
 */
public final class TalkerForm {

	public final JPanel panel = Forms.stackedForm();
	private final JTextArea text = Forms.textArea("", 5);
	private final ModelPicker model;

	/** The registry the model picker lists, and the previews it registers - the editor's, handed in. */
	private final NPCRegistry reg;
	private final List<CustomH3DPreview> livePreviews;

	/** The open game the model picker browses; handed in, never fetched. */
	private final ctrmap.formats.GameFiles game;

	public TalkerForm(ctrmap.formats.GameFiles game, NPCRegistry reg, List<CustomH3DPreview> livePreviews, int defaultModel) {
		this.game = game;
		this.reg = reg;
		this.livePreviews = livePreviews;
		model = new ModelPicker(reg, livePreviews, game, defaultModel);
		Forms.addLabeled(panel, "Dialogue text:", new JScrollPane(text));
		Forms.addLabeled(panel, "Model (type to search) - preview below:", model);
		panel.add(Forms.hint("<html>The NPC is placed at the centre of the current view.<br>Only registered overworld models are listed.</html>"));
	}

	public String text() {
		return text.getText();
	}

	public int model() {
		return model.getSelectedUid();
	}
}
