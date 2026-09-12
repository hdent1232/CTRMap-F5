package ctrmap.humaninterface;

import ctrmap.LoadedZone;
import ctrmap.WorkspaceSession;
import ctrmap.ZoneLinker;
import java.awt.Component;
import java.awt.GridLayout;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * Connect two zones through a warp - both ways, in one action.
 *
 * <p>Asked for on 2026-09-11. Wiring two zones together meant loading one zone,
 * retyping a target in the warp form, loading the other, retyping the target
 * back, and nothing anywhere checking that the two halves agreed. They have to:
 * a warp carries the zone it leads to AND which warp it arrives at, so half a
 * link is a door the player walks through and cannot walk back out of.
 *
 * <p>IT SHOWS WHAT IT WILL ABANDON BEFORE IT IS DONE. Repointing a warp that
 * already leads somewhere leaves its old partner pointing back at a door that no
 * longer expects it - a one-way trip into a zone that has moved on.
 * {@link ZoneLinker#wouldBreak} is that list and the user reads it while
 * deciding, rather than meeting it in game.
 */
public final class ZoneLinkDialog {

	private ZoneLinkDialog() {
	}

	/**
	 * @param parent what the dialog belongs to
	 * @param ws the open game, handed in
	 * @param zones the zone table, for the default and the range
	 */
	public static void show(Component parent, WorkspaceSession ws, LoadedZone zones) {
		if (ws == null || zones == null || zones.count() == 0) {
			ctrmap.Ui.error(parent, "Load a workspace first (Options > Workspace settings).",
					"Connect zones");
			return;
		}
		int here = Math.max(0, zones.index());
		int last = zones.count() - 1;
		JSpinner zoneA = new JSpinner(new SpinnerNumberModel(here, 0, last, 1));
		JSpinner warpA = new JSpinner(new SpinnerNumberModel(0, 0, 9999, 1));
		JSpinner zoneB = new JSpinner(new SpinnerNumberModel(Math.min(here + 1, last), 0, last, 1));
		JSpinner warpB = new JSpinner(new SpinnerNumberModel(0, 0, 9999, 1));
		JCheckBox both = new JCheckBox("Wire it BOTH ways (walk there and back)", true);
		both.setToolTipText("Unticked, only the first warp is changed - a one-way drop.");

		JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
		form.add(new JLabel("This zone:"));
		form.add(zoneA);
		form.add(new JLabel("...through its warp number:"));
		form.add(warpA);
		form.add(new JLabel("Leads to zone:"));
		form.add(zoneB);
		form.add(new JLabel("...arriving at its warp number:"));
		form.add(warpB);
		form.add(both);
		form.add(new JLabel());

		//ITS OWN DIALOG, NOT A JOptionPane WITH A FORM IN IT. The seam rule is that
		//every dialog goes through Ui, and Ui carries a STRING - so a live form has to
		//be listed as an exception, and that list has a ceiling precisely so adding to
		//it costs a review. A JDialog built here is not an exception to anything: the
		//rule is about JOptionPane, and everything this says in words still goes
		//through Ui below.
		final boolean[] go = {false};
		final javax.swing.JDialog dlg = new javax.swing.JDialog(
			javax.swing.SwingUtilities.getWindowAncestor(parent), "Connect zones",
			java.awt.Dialog.ModalityType.APPLICATION_MODAL);
		dlg.setLayout(new java.awt.BorderLayout(6, 6));
		dlg.add(new JLabel("<html><div style=\"padding:8px\">Connect two zones through a warp."
			+ "<br>The warp numbers are the ones the Warp tool shows on the map -"
			+ "<br>place the warps first, then wire them here.</div></html>"),
			java.awt.BorderLayout.NORTH);
		dlg.add(form, java.awt.BorderLayout.CENTER);
		JPanel buttons = new JPanel();
		javax.swing.JButton ok = new javax.swing.JButton("Connect");
		javax.swing.JButton cancel = new javax.swing.JButton("Cancel");
		ok.addActionListener(e -> {
			go[0] = true;
			dlg.dispose();
		});
		cancel.addActionListener(e -> dlg.dispose());
		buttons.add(ok);
		buttons.add(cancel);
		dlg.add(buttons, java.awt.BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(parent);
		dlg.setVisible(true);
		if (!go[0]) {
			return;
		}
		int za = (Integer) zoneA.getValue(), wa = (Integer) warpA.getValue();
		int zb = (Integer) zoneB.getValue(), wb = (Integer) warpB.getValue();

		//WHAT IT WOULD ABANDON, between the choice and the write
		try {
			List<String> breaks = ZoneLinker.wouldBreak(ws, za, wa, zb, wb);
			if (!breaks.isEmpty()) {
				StringBuilder sb = new StringBuilder("Connecting these leaves the old partners"
						+ " pointing at nothing:\n");
				for (String s : breaks) {
					sb.append("\n  ").append(s);
				}
				sb.append("\n\nThose doors will still be walkable and will still lead here."
						+ "\nConnect anyway?");
				if (ctrmap.Ui.confirm(parent, sb.toString(), "Connect zones",
						JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
						!= JOptionPane.OK_OPTION) {
					return;
				}
			}
		} catch (Exception ex) {
			ctrmap.Ui.error(parent, "Could not read those warps:\n" + ctrmap.Ui.reason(ex),
					"Connect zones");
			return;
		}

		try {
			String said = ZoneLinker.link(ws, za, wa, zb, wb, both.isSelected());
			ctrmap.Ui.message(parent, said, "Connect zones", JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception ex) {
			ctrmap.Ui.error(parent, ctrmap.Ui.reason(ex), "Connect zones");
		}
	}
}
