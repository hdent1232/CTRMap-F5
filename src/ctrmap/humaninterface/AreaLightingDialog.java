package ctrmap.humaninterface;

import ctrmap.LoadedZone;
import ctrmap.Workspace;
import ctrmap.formats.area.AreaEnv;
import ctrmap.formats.containers.AD;
import ctrmap.gamedef.ArchiveType;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;


/**
 * Edits a zone's AREA atmosphere - the per-area environment GameFreak stored in
 * AreaData subfile {@link AD#ENVIRONMENT} ({@link AreaEnv}). It hand-tunes the fog:
 * colour, strength and near/far draw distance, channels 54-59 of the block's 61. The
 * lighting - light directions, hemisphere, ranges - is only ever copied whole from a
 * GameFreak area; there is no ambient control, because the bytes one wrote were the
 * constant 1.0 group of a fog channel and never a colour (F236). Routes are a long
 * blue haze, caves and rooms a short dim one. This is what makes distance read
 * as sky and gives each area its atmosphere.
 *
 * <p>PER-AREA: the change applies to every zone that shares this zone's area id.
 */
public class AreaLightingDialog {

	/**
	 * The fog and lighting editor, as a pane for the World Editor's tool column.
	 *
	 * <p>It edits the area the open zone belongs to, which is what that column is for.
	 * It was a modal window, which is what this project does not do with features.
	 *
	 * @param worldTextures the open map's decoded world textures, or null - the
	 *        environment picker previews with them, and this dialog is the only
	 *        thing that opens it.
	 * @param onClose what to do when the user is finished with it
	 * @param fogChanged what redraws the 3D view's fog once the area's atmosphere is written - HANDED, not
	 *        a call up into the window (F007): this pane is built by the window and is told what to ask
	 * @param ops the workspace's history, which keeps each save and the fork before it (F209c)
	 * @param reread what reads the open zone again once a fork is packed, and on Undo and Redo of one (F255)
	 * @return the editor, or null when there is nothing to edit (it says why first)
	 */
	public static javax.swing.JComponent panel(java.awt.Component parent, LoadedZone loaded,
			java.util.List<ctrmap.formats.h3d.texturing.H3DTexture> worldTextures, Runnable onClose,
			Runnable fogChanged, SaveHistory ops, Runnable reread) {
		if (fogChanged == null) {
			throw new IllegalArgumentException("the fog and lighting editor must be handed what redraws the fog");
		}
		if (ops == null || reread == null) {
			throw new IllegalArgumentException("the fog and lighting editor must be handed the history that keeps its"
					+ " saves and what reads the zone again");
		}
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
		//THE AREA THE ZONE'S FILE NAMES (F255): its open copy's header could still name one it was forked away from.
		//A shared one is offered a private copy when the edit is SAVED - the prompt's own rule, a fork at the edit -
		//so a look that ends in Cancel forks nothing
		final int zoneIndex = loaded.index();
		final int areaId;
		try {
			areaId = ctrmap.AreaForker.currentArea(zoneIndex);
		} catch (Exception ex) {
			ctrmap.Ui.error(parent, "Could not read which area zone " + zoneIndex + " is on:\n" + ctrmap.util.Bytes.reason(ex),
					"Area fog & lighting");
			return null;
		}
		final File areaFile = Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, areaId);
		final AD ad;
		final byte[] sub4;
		final AreaEnv env;
		try {
			ad = new AD(areaFile, Workspace.session());
			sub4 = ad.getFile(AD.ENVIRONMENT);
			env = AreaEnv.read(sub4);
		} catch (Exception ex) {
			ctrmap.Ui.error(parent, "Could not read area " + areaId + " lighting:\n" + ctrmap.util.Bytes.reason(ex), "Area fog & lighting");
			return null;
		}

		//VISUAL FIRST: the GameFreak atmosphere picker (live preview of this
		//zone under each preset); hand-tuning sits behind "Custom settings..."
		byte[] picked = GfEnvPicker.pick(javax.swing.SwingUtilities.getWindowAncestor(parent), true, loaded, worldTextures);
		if (picked == null) {
			return null;
		}
		if (picked != GfEnvPicker.CUSTOM) {
			applyPicked(parent, loaded, zoneIndex, sub4, picked, ops, reread, fogChanged);
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
		main.add(new JLabel("<html>Area " + areaId + " - <b>a shared area is offered a copy of its own when you save</b>. "
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
			saveCustom(dlg, parent, loaded, zoneIndex, env, sub4, ops, reread, fogChanged, onClose);
		});
		cancel.addActionListener(e -> onClose.run());

		dlg.setMinimumSize(new Dimension(360, dlg.getPreferredSize().height));
		return dlg;
	}

	/**
	 * Writes the custom form's atmosphere {@code env} into {@code sub4}, the area's own block, through {@link #save};
	 * once it is saved the form is let go and the save is said, and a cancelled area prompt leaves the form where it
	 * is with nothing written. Out of the form so a suite reaches it: the form opens only after the picker, a modal
	 * window no recording answers (the 2026-10-14 shared sweep, AreaLightingDialog 244, negate-if, SURVIVED).
	 *
	 * <p>NO ANSWER (2026-10-16): it answered whether it saved, and nothing but a suite read that - the form's Save
	 * ignored it - so a flipped answer changed nothing anyone sees (the assembly, AreaLightingDialog 260, flip-return,
	 * SURVIVED). What a save does is what it says and whether the form is let go, and that is what is asked.
	 */
	public static void saveCustom(java.awt.Component form, java.awt.Component parent, LoadedZone loaded,
			int zoneIndex, AreaEnv env, byte[] sub4, SaveHistory ops, Runnable reread, Runnable fogChanged,
			Runnable onClose) {
		try {
			env.writeInto(sub4);
			if (save(form, loaded, zoneIndex, sub4, ops, reread, fogChanged) == null) {
				return;   //the area question was cancelled: nothing written, the form stays
			}
			onClose.run();
			ctrmap.Ui.message(parent,
					"Fog & lighting saved. Deploy to see it in-game - the 3D view already shows it.",
					"Area fog & lighting", JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception ex) {
			ctrmap.Ui.error(form, "Save failed:\n" + ctrmap.util.Bytes.reason(ex), "Area fog & lighting");
		}
	}

	/**
	 * Writes the atmosphere {@code picked} from the GameFreak presets over {@code sub4}, the area's own block,
	 * through {@link #save}, and says it was applied only once it is saved; a block of another size is refused and
	 * nothing written. Out of the panel so a suite reaches it: the picker before it is a modal window no recording
	 * answers (the 2026-10-14 shared sweep, AreaLightingDialog 127, negate-if, SURVIVED).
	 */
	public static void applyPicked(java.awt.Component parent, LoadedZone loaded, int zoneIndex, byte[] sub4,
			byte[] picked, SaveHistory ops, Runnable reread, Runnable fogChanged) {
		if (picked.length != sub4.length) {
			ctrmap.Ui.error(parent, "The picked atmosphere block does not match this area's format.", "Area fog & lighting");
			return;
		}
		try {
			System.arraycopy(picked, 0, sub4, 0, sub4.length);
			if (save(parent, loaded, zoneIndex, sub4, ops, reread, fogChanged) != null) {
				ctrmap.Ui.message(parent,
						"Atmosphere applied. Deploy to see it in-game - the 3D view already shows it.",
						"Area fog & lighting", JOptionPane.INFORMATION_MESSAGE);
			}
		} catch (Exception ex) {
			ctrmap.Ui.error(parent, "Save failed:\n" + ctrmap.util.Bytes.reason(ex), "Area fog & lighting");
		}
	}

	/**
	 * Writes {@code sub4} as the atmosphere of the area zone {@code zoneIndex}'s file names, giving the zone its own
	 * area first when that one is shared and the user asks (F255): the fork a change of its own, the atmosphere
	 * another, both kept in {@code ops} (F209c). A fork is packed and the zone read again whatever the write did.
	 *
	 * @return what the area prompt answered, or null when it was cancelled and nothing was written
	 * @throws IllegalStateException when the atmosphere could not be written; it was not, and a fork made is kept
	 */
	public static ctrmap.AreaForker.ForkResult save(java.awt.Component parent, LoadedZone loaded, int zoneIndex,
			byte[] sub4, SaveHistory ops, Runnable reread, Runnable fogChanged) {
		ctrmap.AreaForker.ForkResult fork = AreaForkPrompt.ensurePrivate(loaded, parent, zoneIndex,
				"changing the fog and lighting", ops, reread);
		if (fork == null) {
			return null;
		}
		try {
			File areaFile = Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, fork.newArea);
			try (ctrmap.WorkspaceUnit unit = ctrmap.WorkspaceUnit.begin("Fog and lighting of area " + fork.newArea,
					Workspace.session())) {
				new AD(areaFile, Workspace.session()).storeFile(AD.ENVIRONMENT, sub4);
				Workspace.addPersist(areaFile);
				ctrmap.WorkspaceUnit.inMemory(fogChanged, fogChanged);
				ops.keep(unit.commitKeeping());
			}
			fogChanged.run();
			return fork;
		} finally {
			AreaForkPrompt.packIfForked(fork, reread);
		}
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
