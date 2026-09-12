package ctrmap.humaninterface;

import ctrmap.LoadedZone;
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

	/**
	 * @param worldTextures the open map's decoded world textures, or null - the
	 *        environment picker previews with them, and this dialog is the only
	 *        thing that opens it.
	 */
	/**
	 * The fog and lighting editor, as a pane for the World Editor's tool column.
	 *
	 * <p>It edits the area the open zone belongs to, which is what that column is for.
	 * It was a modal window, which is what this project does not do with features.
	 *
	 * @param onClose what to do when the user is finished with it
	 * @return the editor, or null when there is nothing to edit (it says why first)
	 */
	public static javax.swing.JComponent panel(java.awt.Component parent, LoadedZone loaded,
			java.util.List<ctrmap.formats.h3d.texturing.H3DTexture> worldTextures, Runnable onClose) {
		if (loaded == null) {
			throw new IllegalArgumentException("AreaLightingDialog must be handed the LoadedZone");
		}
		//AREA_ENV, not "is it ORAS": what this needs is a game whose AreaData
		//subfile 4 has been decoded. Asking which game it is answered false for
		//Sun/Moon and X/Y alike and told both of them to load ORAS.
		ctrmap.gamedef.GameProfile prof = Workspace.isValid() ? Workspace.profile() : null;
		if (prof == null) {
			ctrmap.Ui.error(parent, "Load a workspace first (Options > Workspace settings).", "Area fog & lighting");
			return null;
		}
		if (!prof.supports(ctrmap.gamedef.GameProfile.Feature.AREA_ENV)) {
			ctrmap.Ui.error(parent, "Editing area fog and lighting is not available for "
					+ prof.displayName() + "."
					+ "\n\nThe fog colours, their strengths and the near/far draw distances are"
					+ " read from fixed channels inside AreaData subfile 4, a layout measured on"
					+ " Omega Ruby / Alpha Sapphire."
					+ "\n\nCTRMap refuses here rather than writing colours over whatever this"
					+ " game keeps at those offsets.",
					"Area fog & lighting");
			return null;
		}
		if (loaded.open() == null) {
			ctrmap.Ui.error(parent, "Load a zone first (Zone tab).", "Area fog & lighting");
			return null;
		}
		//this zone's atmosphere must be ITS OWN: an area shared with other zones
		//gets forked first, so the edit cannot leak into them
		final ctrmap.AreaForker.ForkResult fork = AreaForkPrompt.ensurePrivate(loaded, parent, loaded.index(),
				loaded.open().header.areadataID, "changing the fog and lighting");
		if (fork == null) {
			return null;
		}
		final int areaId = fork.newArea;
		final File areaFile = Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, areaId);
		final AD ad;
		final byte[] sub4;
		final AreaEnv env;
		try {
			ad = new AD(areaFile, Workspace.session());
			sub4 = ad.getFile(4);
			env = AreaEnv.read(sub4);
		} catch (Exception ex) {
			ctrmap.Ui.error(parent, "Could not read area " + areaId + " lighting:\n" + ex.getMessage(), "Area fog & lighting");
			return null;
		}

		//VISUAL FIRST: the GameFreak atmosphere picker (live preview of this
		//zone under each preset); hand-tuning sits behind "Custom settings..."
		byte[] picked = GfEnvPicker.pick(javax.swing.SwingUtilities.getWindowAncestor(parent), true, loaded, worldTextures);
		if (picked == null) {
			return null;
		}
		if (picked != GfEnvPicker.CUSTOM) {
			if (picked.length != sub4.length) {
				ctrmap.Ui.error(parent, "The picked atmosphere block does not match this area's format.", "Area fog & lighting");
				return null;
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
			return null;
		}
		//fall through: the custom-settings form

		//FOUR TIMES OF DAY, because that is what an area carries. The block is
		//float[61][12] and the twelve lanes are three identical groups of four:
		//night, dawn, day, dusk. A route hazes pale at dawn, blue by day and ORANGE
		//at dusk, and none of that was reachable here - this offered ONE colour and
		//ONE strength, and wrote them into four floats that were not fog at all but
		//one unrelated colour sampled across the day. The "Ambient / light colour"
		//control is gone rather than relabelled: the bytes it wrote are the constant
		//1.0 group of that same channel, so it was never a colour the user could
		//choose - see AreaEnv for what the evidence for all of this is, and is not.
		final Color[] fog = new Color[AreaEnv.TIMES];
		final JButton[] fogBtn = new JButton[AreaEnv.TIMES];
		final JSpinner[] strength = new JSpinner[AreaEnv.TIMES];
		final JSpinner near = new JSpinner(new SpinnerNumberModel((int) env.fogNear, -2000, 30000, 50));
		final JSpinner far = new JSpinner(new SpinnerNumberModel((int) env.fogFar, -2000, 30000, 100));

		JPanel form = new JPanel(new GridLayout(0, 3, 6, 6));
		form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		form.add(new JLabel("<html><b>Time of day</b></html>"));
		form.add(new JLabel("<html><b>Fog colour</b></html>"));
		form.add(new JLabel("<html><b>Strength (%)</b></html>"));
		for (int t = 0; t < AreaEnv.TIMES; t++) {
			final int ti = t;
			fog[t] = rgb(env.fogColor[t]);
			fogBtn[t] = swatch("Fog colour at " + AreaEnv.TIME_NAMES[t], fog[t]);
			fogBtn[t].addActionListener(e -> {
				Color c = JColorChooser.showDialog(fogBtn[ti], "Fog colour - " + AreaEnv.TIME_NAMES[ti], fog[ti]);
				if (c != null) {
					fog[ti] = c;
					fogBtn[ti].setBackground(c);
				}
			});
			strength[t] = new JSpinner(new SpinnerNumberModel(percent(env.fogStrength[t]), 0, 100, 5));
			form.add(new JLabel(AreaEnv.TIME_NAMES[t] + ":"));
			form.add(fogBtn[t]);
			form.add(strength[t]);
		}
		form.add(new JLabel("Fog near (starts):"));
		form.add(near);
		form.add(new JLabel());
		form.add(new JLabel("Fog far (fully hidden):"));
		form.add(far);
		form.add(new JLabel());

		// refreshes the controls from the (possibly replaced) sub4 block
		final Runnable refresh = () -> {
			AreaEnv cur = AreaEnv.read(sub4);
			for (int t = 0; t < AreaEnv.TIMES; t++) {
				fog[t] = rgb(cur.fogColor[t]);
				fogBtn[t].setBackground(fog[t]);
				strength[t].setValue(percent(cur.fogStrength[t]));
			}
			near.setValue((int) cur.fogNear);
			far.setValue((int) cur.fogFar);
		};

		JPanel main = new JPanel(new BorderLayout(0, 6));
		main.add(new JLabel("<html>Area " + areaId + " - <b>affects EVERY zone in this area</b>. "
				+ "Routes = long blue haze; caves/rooms = short dim.</html>"), BorderLayout.NORTH);
		main.add(form, BorderLayout.CENTER);

		//A PANE, not a window: the adds below are unchanged.
		final JPanel dlg = new JPanel(new BorderLayout());
		dlg.add(main, BorderLayout.CENTER);
		JPanel buttons = new JPanel();
		JButton copyGf = new JButton("Copy a GameFreak zone's atmosphere");
		JButton sameAllDay = new JButton("Day -> all four");
		sameAllDay.setToolTipText("Give night, dawn and dusk the same fog as day -"
			+ " what interiors do, since a cave has no day cycle.");
		JButton save = new JButton("Save");
		JButton cancel = new JButton("Cancel");
		buttons.add(copyGf);
		buttons.add(sameAllDay);
		buttons.add(save);
		buttons.add(cancel);
		dlg.add(buttons, BorderLayout.SOUTH);

		sameAllDay.addActionListener(e -> {
			for (int t = 0; t < AreaEnv.TIMES; t++) {
				fog[t] = fog[AreaEnv.TIME_DAY];
				fogBtn[t].setBackground(fog[t]);
				strength[t].setValue(strength[AreaEnv.TIME_DAY].getValue());
			}
		});

		copyGf.addActionListener(e -> {
			byte[] src = GfEnvPicker.pick(javax.swing.SwingUtilities.getWindowAncestor(dlg), false, loaded, worldTextures);
			if (src != null && src.length == sub4.length) {
				// take GameFreak's COMPLETE environment (all 736 floats: colors,
				// light directions, hemisphere, ranges) - fine-tune on top if wanted
				System.arraycopy(src, 0, sub4, 0, sub4.length);
				refresh.run();
			}
		});

		save.addActionListener(e -> {
			for (int t = 0; t < AreaEnv.TIMES; t++) {
				env.fogColor[t][0] = fog[t].getRed() / 255f;
				env.fogColor[t][1] = fog[t].getGreen() / 255f;
				env.fogColor[t][2] = fog[t].getBlue() / 255f;
				env.fogStrength[t] = ((Number) strength[t].getValue()).floatValue() / 100f;
			}
			env.fogNear = ((Number) near.getValue()).floatValue();
			env.fogFar = ((Number) far.getValue()).floatValue();
			try {
				env.writeInto(sub4);
				ad.storeFile(4, sub4);
				Workspace.addPersist(areaFile);
				ctrmap.CtrmapMainframe.refreshSceneFog();
				AreaForkPrompt.packIfForked(fork, null);
				onClose.run();
				ctrmap.Ui.message(parent,
						"Fog & lighting saved. Deploy to see it in-game - the 3D view already shows it.",
						"Area fog & lighting", JOptionPane.INFORMATION_MESSAGE);
			} catch (Exception ex) {
				ctrmap.Ui.error(dlg, "Save failed:\n" + ex.getMessage(), "Area fog & lighting");
			}
		});
		cancel.addActionListener(e -> onClose.run());

		dlg.setMinimumSize(new Dimension(360, dlg.getPreferredSize().height));
		return dlg;
	}

	private static JButton swatch(String tip, Color c) {
		JButton b = new JButton();
		b.setToolTipText(tip);
		b.setBackground(c);
		b.setPreferredSize(new Dimension(80, 22));
		return b;
	}

	/** A 0..1 strength as the whole percent the spinner shows. */
	private static int percent(float v) {
		return Math.max(0, Math.min(100, Math.round(v * 100)));
	}

	private static Color rgb(float[] c) {
		return new Color(clamp(c[0]), clamp(c[1]), clamp(c[2]));
	}

	private static float clamp(float v) {
		return v < 0 ? 0 : v > 1 ? 1 : v;
	}
}
