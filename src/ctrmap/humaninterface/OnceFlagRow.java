package ctrmap.humaninterface;

import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JSpinner;

/**
 * "Give it once" for a giver wizard: a tick, and the event flag the giver guards itself with (F085).
 *
 * <p>Ticked, the NPC checks the flag and sets it after giving, as 78 of the 155 retail give sites do, so
 * a second talk gives nothing; unticked it gives on every talk, as it always did. The flag offered is the
 * highest one no zone script names (ctrmap.ScriptFlags), and the add asks again before it writes - but a
 * flag the game's own code keeps is one no script shows, so the hint says it is unproven in game.
 *
 * <p>Not: {@link GiverForm} - the item giver and the BP giver both need it, and each is its own form.
 */
public final class OnceFlagRow {

	/** What the hint says: the in-game queue (OUTSTANDING.md) quotes it. */
	static final String HINT = "<html>Once: the NPC checks this event flag and sets it after giving, as retail's one-time<br>"
			+ "givers do. It must be one no zone script uses; a flag the game's own code keeps would still<br>"
			+ "clash, so one-time givers are unproven in game.</html>";

	private final JCheckBox once = new JCheckBox("Give it once, guarded by event flag:");
	private final JSpinner flag = new JSpinner(new javax.swing.SpinnerNumberModel(1, 1, Short.MAX_VALUE, 1));
	private boolean offered;

	OnceFlagRow(ctrmap.formats.GameFiles game, JPanel form) {
		ctrmap.Ui.offer(flag, "Tick \"Give it once\" to choose its flag");
		once.addActionListener(ticked -> {
			ctrmap.Ui.offer(flag, once.isSelected() ? null : "Tick \"Give it once\" to choose its flag");
			if (once.isSelected() && !offered) {
				offered = true;
				try {
					int free = ctrmap.ScriptFlags.of(game).suggestion();
					if (free > 0) {
						flag.setValue(free);
					}
				} catch (java.io.IOException unread) {
					ctrmap.Ui.message(form, "No flag could be offered: " + ctrmap.util.Bytes.reason(unread)
							+ "\nType one; adding the NPC asks again which flags the scripts use.", "Give it once",
							javax.swing.JOptionPane.WARNING_MESSAGE);
				}
			}
		});
		JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
		row.add(once);
		row.add(flag);
		form.add(row);
		form.add(Forms.hint(HINT));
	}

	/** The flag to guard with, or -1 for a giver that gives on every talk. */
	public int flag() {
		return once.isSelected() ? (Integer) flag.getValue() : -1;
	}
}
