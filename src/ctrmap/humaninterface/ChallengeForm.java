package ctrmap.humaninterface;

import ctrmap.formats.npcreg.NPCRegistry;
import ctrmap.formats.scripts.NpcTemplates;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;

/**
 * The wizard for a battle challenge: trainers, BP, milestones.
 *
 * <p>One of the ten classes that lived inside {@link NPCEditForm}, which was
 * 2840 lines. Each was an inner class reading the editor it sat in through
 * synthetic bridges, so none could be built without one; each is handed what
 * it needs now and is an ordinary class in a file of its own.
 *
 * <p>The battle-challenge form: the lineup, the BP rules, the three texts, the streak variable, the model.
 */
public final class ChallengeForm {

	public final JPanel panel = Forms.stackedForm();
	private final java.util.List<Integer> trainerIds = new java.util.ArrayList<>();
	private final JSpinner bp = new JSpinner(new javax.swing.SpinnerNumberModel(3, 0, 999, 1));
	private final JSpinner milestone = new JSpinner(new javax.swing.SpinnerNumberModel(0, 0, 99, 1));
	private final JSpinner bonus = new JSpinner(new javax.swing.SpinnerNumberModel(20, 0, 9999, 1));
	private final javax.swing.JCheckBox whiteout = new javax.swing.JCheckBox("White out on defeat (engine loss handler)");
	private final JTextArea intro = Forms.textArea("", 2);
	private final JTextArea win = Forms.textArea("", 2);
	private final JTextArea lose = Forms.textArea("", 2);
	private final javax.swing.JTextField workVar = new javax.swing.JTextField(
			Integer.toHexString(ctrmap.formats.scripts.GauntletScriptWizard.DEFAULT_STREAK_WORK), 6);
	/** The registry the model picker lists, and the previews it registers - the editor's, handed in. */
	private final NPCRegistry reg;
	private final List<CustomH3DPreview> livePreviews;

	private final ModelPicker model;

	/** The open game the model picker browses; handed in, never fetched. */
	private final ctrmap.formats.GameFiles game;

	public ChallengeForm(ctrmap.formats.GameFiles game, NPCRegistry reg, List<CustomH3DPreview> livePreviews, List<String> trainerNames) {
		this.game = game;
		this.reg = reg;
		this.livePreviews = livePreviews;
		this.model = new ModelPicker(reg, livePreviews, game, -1);
		final IdChooser idChooser = new IdChooser(trainerNames, NpcTemplates.TRAINER_ID_MAX, 1);
		final javax.swing.DefaultListModel<String> listModel = new javax.swing.DefaultListModel<>();
		final javax.swing.JList<String> trainerList = new javax.swing.JList<>(listModel);
		trainerList.setVisibleRowCount(5);
		javax.swing.JButton addBtn = new javax.swing.JButton("Add to lineup");
		javax.swing.JButton removeBtn = new javax.swing.JButton("Remove selected");
		addBtn.addActionListener(ev -> {
			int tid = idChooser.getId();
			if (tid >= 1 && tid <= 949) {
				trainerIds.add(tid);
				listModel.addElement("#" + trainerIds.size() + "  " + tid
						+ (trainerNames != null && tid < trainerNames.size() && trainerNames.get(tid) != null && !trainerNames.get(tid).isEmpty()
						? " " + trainerNames.get(tid) : ""));
			}
		});
		removeBtn.addActionListener(ev -> {
			int i = trainerList.getSelectedIndex();
			if (i >= 0) {
				trainerIds.remove(i);
				listModel.remove(i);
			}
		});
		Forms.addLabeled(panel, "Lineup (battle 1, 2, ... - the last repeats until a loss):", idChooser);
		JPanel listBtns = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
		listBtns.add(addBtn);
		listBtns.add(removeBtn);
		listBtns.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		panel.add(listBtns);
		JScrollPane listScroll = new JScrollPane(trainerList);
		listScroll.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		panel.add(listScroll);
		Forms.addLabeled(panel, "BP per win (0 = none):", bp);
		Forms.addLabeled(panel, "Bonus at streak (0 = no bonus):", milestone);
		Forms.addLabeled(panel, "Bonus BP:", bonus);
		Forms.addLabeled(panel, "Intro text (empty = none):", new JScrollPane(intro));
		Forms.addLabeled(panel, "Win text (empty = none):", new JScrollPane(win));
		Forms.addLabeled(panel, "Lose text (empty = none):", new JScrollPane(lose));
		Forms.addLabeled(panel, "Streak save variable (hex; script-corpus-free default):", workVar);
		whiteout.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		panel.add(whiteout);
		Forms.addLabeled(panel, "NPC model (type to search; preview below):", model);
		panel.add(Forms.hint("<html>The lineup takes ANY trainer entry: retail trainers (Youngsters, Ace Trainers...)<br>"
				+ "work AS-IS and are not modified by battling them here; for custom competitors,<br>"
				+ "repurpose a blank-named entry in Game Data -> Trainers (set its class, name and<br>"
				+ "team there - the class gives it the Youngster/Ace Trainer/... battle identity).<br>"
				+ "Fully independent of the retail facilities' shared pools - vanilla stays untouched.<br>"
				+ "Each talk = one battle at the current streak. UNPROVEN IN-GAME - test it first.</html>"));
	}

	public ChallengeInput input() {
		ChallengeInput in = new ChallengeInput();
		in.trainerIds.addAll(trainerIds);
		in.bpPerWin = (Integer) bp.getValue();
		in.milestone = (Integer) milestone.getValue();
		in.milestoneBonus = (Integer) bonus.getValue();
		in.streakWorkHex = workVar.getText();
		in.loseWhiteout = whiteout.isSelected();
		in.intro = intro.getText();
		in.win = win.getText();
		in.lose = lose.getText();
		in.model = model.getSelectedUid();
		return in;
	}
}
