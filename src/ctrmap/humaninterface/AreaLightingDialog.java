package ctrmap.humaninterface;

import ctrmap.Workspace;
import ctrmap.formats.area.AreaEnv;
import ctrmap.formats.containers.AD;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GridLayout;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import static ctrmap.CtrmapMainframe.*;

/**
 * Edits a zone's AREA fog + ambient lighting - the per-area environment
 * GameFreak stored in AreaData subfile 4 ({@link AreaEnv}): the fog/sky color,
 * fog near/far draw distance, and an ambient/light color. Routes are a long
 * blue haze, caves and rooms a short dim one. This is what makes distance read
 * as sky and gives each area its atmosphere.
 *
 * <p>PER-AREA: the change applies to every zone that shares this zone's area id.
 */
public class AreaLightingDialog {

	public static void show(Frame parent) {
		if (!Workspace.valid || !Workspace.isOA()) {
			JOptionPane.showMessageDialog(parent, "Load an ORAS workspace first.", "Area fog & lighting", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (mZonePnl == null || mZonePnl.zone == null) {
			JOptionPane.showMessageDialog(parent, "Load a zone first (Zone tab).", "Area fog & lighting", JOptionPane.ERROR_MESSAGE);
			return;
		}
		final int areaId = mZonePnl.zone.header.areadataID;
		final File areaFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.AREA_DATA, areaId);
		final AD ad;
		final byte[] sub4;
		final AreaEnv env;
		try {
			ad = new AD(areaFile);
			sub4 = ad.getFile(4);
			env = AreaEnv.read(sub4);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(parent, "Could not read area " + areaId + " lighting:\n" + ex.getMessage(), "Area fog & lighting", JOptionPane.ERROR_MESSAGE);
			return;
		}

		final Color[] fog = {rgb(env.fogColor)};
		final Color[] amb = {rgb(env.ambient)};
		final JButton fogBtn = swatch("Fog / sky color", fog[0]);
		final JButton ambBtn = swatch("Ambient / light color", amb[0]);
		final JSpinner strength = new JSpinner(new SpinnerNumberModel(Math.round(env.fogColor[3] * 100), 0, 100, 5));
		final JSpinner near = new JSpinner(new SpinnerNumberModel((int) env.fogNear, -2000, 30000, 50));
		final JSpinner far = new JSpinner(new SpinnerNumberModel((int) env.fogFar, -2000, 30000, 100));
		fogBtn.addActionListener(e -> {
			Color c = JColorChooser.showDialog(fogBtn, "Fog / sky color", fog[0]);
			if (c != null) {
				fog[0] = c;
				fogBtn.setBackground(c);
			}
		});
		ambBtn.addActionListener(e -> {
			Color c = JColorChooser.showDialog(ambBtn, "Ambient / light color", amb[0]);
			if (c != null) {
				amb[0] = c;
				ambBtn.setBackground(c);
			}
		});

		JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
		form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		form.add(new JLabel("Fog / sky color:"));
		form.add(fogBtn);
		form.add(new JLabel("Fog strength (%):"));
		form.add(strength);
		form.add(new JLabel("Fog near (starts):"));
		form.add(near);
		form.add(new JLabel("Fog far (fully hidden):"));
		form.add(far);
		form.add(new JLabel("Ambient / light color:"));
		form.add(ambBtn);

		JPanel main = new JPanel(new BorderLayout(0, 6));
		main.add(new JLabel("<html>Area " + areaId + " - <b>affects EVERY zone in this area</b>. "
				+ "Routes = long blue haze; caves/rooms = short dim.</html>"), BorderLayout.NORTH);
		main.add(form, BorderLayout.CENTER);

		final JDialog dlg = new JDialog(parent, "Area fog & lighting - area " + areaId, true);
		dlg.setLayout(new BorderLayout());
		dlg.add(main, BorderLayout.CENTER);
		JPanel buttons = new JPanel();
		JButton save = new JButton("Save");
		JButton cancel = new JButton("Cancel");
		buttons.add(save);
		buttons.add(cancel);
		dlg.add(buttons, BorderLayout.SOUTH);

		save.addActionListener(e -> {
			env.fogColor[0] = fog[0].getRed() / 255f;
			env.fogColor[1] = fog[0].getGreen() / 255f;
			env.fogColor[2] = fog[0].getBlue() / 255f;
			env.fogColor[3] = ((Integer) strength.getValue()) / 100f;
			env.ambient[0] = amb[0].getRed() / 255f;
			env.ambient[1] = amb[0].getGreen() / 255f;
			env.ambient[2] = amb[0].getBlue() / 255f;
			env.fogNear = ((Number) near.getValue()).floatValue();
			env.fogFar = ((Number) far.getValue()).floatValue();
			try {
				env.writeInto(sub4);
				ad.storeFile(4, sub4);
				Workspace.addPersist(areaFile);
				dlg.dispose();
				JOptionPane.showMessageDialog(parent,
						"Area " + areaId + " fog/lighting saved. Deploy to see it in-game.\n"
						+ "(This changed every zone that uses area " + areaId + ".)",
						"Area fog & lighting", JOptionPane.INFORMATION_MESSAGE);
			} catch (Exception ex) {
				JOptionPane.showMessageDialog(dlg, "Save failed:\n" + ex.getMessage(), "Area fog & lighting", JOptionPane.ERROR_MESSAGE);
			}
		});
		cancel.addActionListener(e -> dlg.dispose());

		dlg.pack();
		dlg.setMinimumSize(new Dimension(360, dlg.getHeight()));
		dlg.setLocationRelativeTo(parent);
		dlg.setVisible(true);
	}

	private static JButton swatch(String tip, Color c) {
		JButton b = new JButton();
		b.setToolTipText(tip);
		b.setBackground(c);
		b.setPreferredSize(new Dimension(80, 22));
		return b;
	}

	private static Color rgb(float[] c) {
		return new Color(clamp(c[0]), clamp(c[1]), clamp(c[2]));
	}

	private static float clamp(float v) {
		return v < 0 ? 0 : v > 1 ? 1 : v;
	}
}
