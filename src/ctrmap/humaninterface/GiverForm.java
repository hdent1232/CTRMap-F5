package ctrmap.humaninterface;

import ctrmap.formats.npcreg.NPCRegistry;
import ctrmap.formats.scripts.NpcTemplates;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.JSpinner;

/**
 * The wizard for an NPC that hands over an item.
 *
 * <p>One of the ten classes that lived inside {@link NPCEditForm}, which was
 * 2840 lines. Each was an inner class reading the editor it sat in through
 * synthetic bridges, so none could be built without one; each is handed what
 * it needs now and is an ordinary class in a file of its own.
 *
 * <p>The item-giver form: which item, how many, which model.
 */
public final class GiverForm {

	public final JPanel panel = Forms.stackedForm();
	private final IdChooser item;
	private final JSpinner count = new JSpinner(new javax.swing.SpinnerNumberModel(1, 1, 99, 1));
	/** The registry the model picker lists, and the previews it registers - the editor's, handed in. */
	private final NPCRegistry reg;
	private final List<CustomH3DPreview> livePreviews;

	private final ModelPicker model;

	/** The open game the model picker browses; handed in, never fetched. */
	private final ctrmap.formats.GameFiles game;

	public GiverForm(ctrmap.formats.GameFiles game, NPCRegistry reg, List<CustomH3DPreview> livePreviews, List<String> itemNames) {
		this.game = game;
		this.item = new IdChooser(itemNames, NpcTemplates.ITEM_ID_MAX, 1);
		this.reg = reg;
		this.livePreviews = livePreviews;
		this.model = new ModelPicker(reg, livePreviews, game, -1);
		Forms.addLabeled(panel, "Item (type to search):", item);
		Forms.addLabeled(panel, "Quantity:", count);
		Forms.addLabeled(panel, "NPC model (type to search; preview below):", model);
		panel.add(Forms.hint("<html>The NPC is placed at the centre of the view and gives the item<br>each time it is talked to (no one-time flag yet).</html>"));
	}

	public int item() {
		return item.getId();
	}

	public int count() {
		return (Integer) count.getValue();
	}

	public int model() {
		return model.getSelectedUid();
	}
}
