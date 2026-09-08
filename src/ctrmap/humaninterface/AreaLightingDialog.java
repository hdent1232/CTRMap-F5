package ctrmap.humaninterface;

import ctrmap.Workspace;
import ctrmap.formats.area.AreaEnv;
import ctrmap.formats.containers.AD;
import ctrmap.gamedef.ArchiveType;
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
		//AREA_ENV, not "is it ORAS": what this needs is a game whose AreaData
		//subfile 4 has been decoded. Asking which game it is answered false for
		//Sun/Moon and X/Y alike and told both of them to load ORAS.
		ctrmap.gamedef.GameProfile prof = Workspace.isValid() ? Workspace.profile() : null;
		if (prof == null) {
			ctrmap.Ui.error(parent, "Load a workspace first (Options > Workspace settings).", "Area fog & lighting");
			return;
		}
		if (!prof.supports(ctrmap.gamedef.GameProfile.Feature.AREA_ENV)) {
			ctrmap.Ui.error(parent, "Editing area fog and lighting is not available for "
					+ prof.displayName() + "."
					+ "\n\nThe fog colour, near/far draw distances and ambient colour are read"
					+ " from fixed offsets inside AreaData subfile 4, a layout measured on"
					+ " Omega Ruby / Alpha Sapphire."
					+ "\n\nCTRMap refuses here rather than writing colours over whatever this"
					+ " game keeps at those offsets.",
					"Area fog & lighting");
			return;
		}
		if (mZonePnl == null || mZonePnl.zone == null) {
			ctrmap.Ui.error(parent, "Load a zone first (Zone tab).", "Area fog & lighting");
			return;
		}
		//this zone's atmosphere must be ITS OWN: an area shared with other zones
		//gets forked first, so the edit cannot leak into them
		final ctrmap.AreaForker.ForkResult fork = AreaForkPrompt.ensurePrivate(parent, mZonePnl.zoneIndex,
				mZonePnl.zone.header.areadataID, "changing the fog and lighting");
		if (fork == null) {
			return;
		}
		final int areaId = fork.newArea;
		final File areaFile = Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, areaId);
		final AD ad;
		final byte[] sub4;
		final AreaEnv env;
		try {
			ad = new AD(areaFile);
			sub4 = ad.getFile(4);
			env = AreaEnv.read(sub4);
		} catch (Exception ex) {
			ctrmap.Ui.error(parent, "Could not read area " + areaId + " lighting:\n" + ex.getMessage(), "Area fog & lighting");
			return;
		}

		//VISUAL FIRST: the GameFreak atmosphere picker (live preview of this
		//zone under each preset); hand-tuning sits behind "Custom settings..."
		byte[] picked = GfEnvPicker.pick(parent, true);
		if (picked == null) {
			return;
		}
		if (picked != GfEnvPicker.CUSTOM) {
			if (picked.length != sub4.length) {
				ctrmap.Ui.error(parent, "The picked atmosphere block does not match this area's format.", "Area fog & lighting");
				return;
			}
			try {
				System.arraycopy(picked, 0, sub4, 0, sub4.length);
				ad.storeFile(4, sub4);
				Workspace.addPersist(areaFile);
				ctrmap.CtrmapMainframe.refreshSceneFog();
				AreaForkPrompt.packIfForked(fork, null);
				ctrmap.Ui.message(parent,
						"Atmosphere applied. Deploy to see it in-game - the 3D view already shows it.",
						"Area fog & lighting", JOptionPane.INFORMATION_MESSAGE);
			} catch (Exception ex) {
				ctrmap.Ui.error(parent, "Save failed:\n" + ex.getMessage(), "Area fog & lighting");
			}
			return;
		}
		//fall through: the custom-settings form

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

		// refreshes the controls from the (possibly replaced) sub4 block
		final Runnable refresh = () -> {
			AreaEnv cur = AreaEnv.read(sub4);
			fog[0] = rgb(cur.fogColor);
			amb[0] = rgb(cur.ambient);
			fogBtn.setBackground(fog[0]);
			ambBtn.setBackground(amb[0]);
			strength.setValue(Math.max(0, Math.min(100, Math.round(cur.fogColor[3] * 100))));
			near.setValue((int) cur.fogNear);
			far.setValue((int) cur.fogFar);
		};

		JPanel main = new JPanel(new BorderLayout(0, 6));
		main.add(new JLabel("<html>Area " + areaId + " - <b>affects EVERY zone in this area</b>. "
				+ "Routes = long blue haze; caves/rooms = short dim.</html>"), BorderLayout.NORTH);
		main.add(form, BorderLayout.CENTER);

		final JDialog dlg = new JDialog(parent, "Area fog & lighting - area " + areaId, true);
		dlg.setLayout(new BorderLayout());
		dlg.add(main, BorderLayout.CENTER);
		JPanel buttons = new JPanel();
		JButton copyGf = new JButton("Copy a GameFreak zone's atmosphere");
		JButton save = new JButton("Save");
		JButton cancel = new JButton("Cancel");
		buttons.add(copyGf);
		buttons.add(save);
		buttons.add(cancel);
		dlg.add(buttons, BorderLayout.SOUTH);

		copyGf.addActionListener(e -> {
			byte[] src = GfEnvPicker.pick(dlg);
			if (src != null && src.length == sub4.length) {
				// take GameFreak's COMPLETE environment (all 736 floats: colors,
				// light directions, hemisphere, ranges) - fine-tune on top if wanted
				System.arraycopy(src, 0, sub4, 0, sub4.length);
				refresh.run();
			}
		});

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
				ctrmap.CtrmapMainframe.refreshSceneFog();
				AreaForkPrompt.packIfForked(fork, null);
				dlg.dispose();
				ctrmap.Ui.message(parent,
						"Fog & lighting saved. Deploy to see it in-game - the 3D view already shows it.",
						"Area fog & lighting", JOptionPane.INFORMATION_MESSAGE);
			} catch (Exception ex) {
				ctrmap.Ui.error(dlg, "Save failed:\n" + ex.getMessage(), "Area fog & lighting");
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
