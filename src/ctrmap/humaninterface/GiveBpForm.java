package ctrmap.humaninterface;

import ctrmap.formats.npcreg.NPCRegistry;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.JSpinner;

/**
 * The wizard for an NPC that awards Battle Points.
 *
 * <p>One of the ten classes that lived inside {@link NPCEditForm}, which was
 * 2840 lines. Each was an inner class reading the editor it sat in through
 * synthetic bridges, so none could be built without one; each is handed what
 * it needs now and is an ordinary class in a file of its own.
 *
 * <p>The Give-BP form: how many points, and which model.
 */
public final class GiveBpForm {

	public final JPanel panel = Forms.stackedForm();
	private final JSpinner amount = new JSpinner(new javax.swing.SpinnerNumberModel(20, 1, 9999, 1));

	private final ModelPicker model;
	private final OnceFlagRow once;

	public GiveBpForm(ctrmap.formats.GameFiles game, NPCRegistry reg, List<CustomH3DPreview> livePreviews) {
		this.model = new ModelPicker(reg, livePreviews, game, -1);
		Forms.addLabeled(panel, "Battle Points to give:", amount);
		Forms.addLabeled(panel, "NPC model (type to search; preview below):", model);
		panel.add(Forms.hint("<html>The NPC adds this many BP each time it is talked to, unless it gives once (the game<br>caps total BP at 9999). Uses the engine's own BP natives.</html>"));
		this.once = new OnceFlagRow(game, panel);
	}

	/** The event flag the NPC gives once by, or -1 for every talk (F085). */
	public int onceFlag() {
		return once.flag();
	}

	public int amount() {
		return (Integer) amount.getValue();
	}

	public int model() {
		return model.getSelectedUid();
	}
}
