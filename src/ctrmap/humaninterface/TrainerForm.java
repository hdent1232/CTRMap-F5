package ctrmap.humaninterface;

import ctrmap.formats.npcreg.NPCRegistry;
import ctrmap.formats.scripts.NpcTemplates;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.JSpinner;

/**
 * The wizard for a trainer: which one, sight range, facing.
 *
 * <p>One of the ten classes that lived inside {@link NPCEditForm}, which was
 * 2840 lines. Each was an inner class reading the editor it sat in through
 * synthetic bridges, so none could be built without one; each is handed what
 * it needs now and is an ordinary class in a file of its own.
 *
 * <p>The trainer form: which trainer, model, sight range, facing, and whether a double-battle partner comes too.
 */
public final class TrainerForm {

	public final JPanel panel = Forms.stackedForm();
	private final IdChooser trainer;
	/** The registry the model picker lists, and the previews it registers - the editor's, handed in. */
	private final NPCRegistry reg;
	private final List<CustomH3DPreview> livePreviews;

	private final ModelPicker model;
	private final JSpinner sight = new JSpinner(new javax.swing.SpinnerNumberModel(0, 0, 8, 1));
	private final javax.swing.JComboBox<String> facing = new javax.swing.JComboBox<>(new String[]{"Down", "Up", "Left", "Right"});
	private final javax.swing.JCheckBox pair = new javax.swing.JCheckBox("Add double-battle partner (script 5000 + ID) beside it");

	/** The open game the model picker browses; handed in, never fetched. */
	private final ctrmap.formats.GameFiles game;

	public TrainerForm(ctrmap.formats.GameFiles game, NPCRegistry reg, List<CustomH3DPreview> livePreviews, List<String> trainerNames) {
		this.game = game;
		this.trainer = new IdChooser(trainerNames, NpcTemplates.TRAINER_ID_MAX, 1);
		this.reg = reg;
		this.livePreviews = livePreviews;
		this.model = new ModelPicker(reg, livePreviews, game, -1);
		Forms.addLabeled(panel, "Trainer (type to search; edit party/class in pk3DS):", trainer);
		Forms.addLabeled(panel, "NPC model (type to search; preview below):", model);
		Forms.addLabeled(panel, "Sight range (0 = battle on talk only):", sight);
		Forms.addLabeled(panel, "Facing:", facing);
		pair.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		panel.add(pair);
		panel.add(Forms.hint("<html>Places the overworld trainer NPC only. The battle exists only if<br>trainer data slot ID is valid (set it in pk3DS).</html>"));
	}

	public int trainer() {
		return trainer.getId();
	}

	public int model() {
		return model.getSelectedUid();
	}

	public int sight() {
		return (Integer) sight.getValue();
	}

	/** 0=down 1=up 2=left 3=right. */
	public int facing() {
		return Math.max(0, facing.getSelectedIndex());
	}

	public boolean pair() {
		return pair.isSelected();
	}
}
