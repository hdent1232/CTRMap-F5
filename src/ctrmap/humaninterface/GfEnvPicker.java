package ctrmap.humaninterface;

import ctrmap.LoadedZone;
import ctrmap.Workspace;
import ctrmap.formats.area.AreaEnv;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.text.GFMessageFile;
import ctrmap.gamedef.ArchiveType;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import static ctrmap.CtrmapMainframe.mTileMapPanel;
import static ctrmap.formats.LittleEndian.u16;
import static ctrmap.formats.LittleEndian.i32;
import static ctrmap.formats.containers.ContainerBytes.subfile;

/**
 * "Pick GameFreak's atmosphere": browse every retail zone by name, see a LIVE
 * preview of the environment GameFreak gave it (fog/sky color, ambient, fog
 * range, indoor/outdoor character), and take that COMPLETE per-area lighting
 * block to apply to your own area - no hand-tuning needed, but everything stays
 * editable afterwards. Reads the pristine snapshot of the game data when
 * available, so the values are what GameFreak shipped even if the live
 * workspace was edited.
 */
public class GfEnvPicker {

	/** Returned by {@link #pick} when the user asked for the custom-settings form. */
	public static final byte[] CUSTOM = new byte[0];

	/** Opens the picker; returns the chosen zone's full 2944-byte env block, or null. */
	public static byte[] pick(Dialog parent, LoadedZone loaded,
			List<ctrmap.formats.h3d.texturing.H3DTexture> worldTextures) {
		return pick((java.awt.Window) parent, false, loaded, worldTextures);
	}

	/**
	 * Opens the picker; returns the chosen env block, {@link #CUSTOM} when the
	 * user pressed "Custom settings..." (offered only when {@code offerCustom}),
	 * or null on cancel.
	 */
	/**
	 * @param worldTextures the open map's decoded world textures, or null when
	 *        no map is open. Handed in rather than read off the map view through
	 *        the main window: this picker uses exactly this one thing from it.
	 */
	public static byte[] pick(java.awt.Window parent, boolean offerCustom, LoadedZone loaded,
			List<ctrmap.formats.h3d.texturing.H3DTexture> worldTextures) {
		if (loaded == null) {
			throw new IllegalArgumentException("GfEnvPicker must be handed the LoadedZone");
		}
		final GARC zoG = pristineOrLive(ArchiveType.ZONE_DATA);
		final GARC adG = pristineOrLive(ArchiveType.AREA_DATA);
		if (zoG == null || adG == null) {
			return null;
		}
		String[] locs = locationNames();

		// zone list: id, location name, area id
		final List<int[]> zones = new ArrayList<>();   // {zoneId, areaId}
		final List<String> labels = new ArrayList<>();
		int zoneCount = zoG.length - 2;
		for (int z = 0; z < zoneCount; z++) {
			byte[] hdr = subfile(zoG.getDecompressedEntry(z), 0);
			if (hdr == null || hdr.length < 0x20) {
				continue;
			}
			int area = u16(hdr, 2);
			int loc = u16(hdr, 0x1C) & 0x3FF;
			String nm = loc < locs.length ? locs[loc] : "";
			zones.add(new int[]{z, area});
			labels.add(z + "  " + nm + "  (area " + area + ")");
		}

		final Map<Integer, byte[]> envCache = new HashMap<>();
		final byte[][] result = {null};

		// live preview: the USER'S CURRENT zone geometry, re-fogged per selection,
		// so they see each atmosphere on their own map. Falls back to a card.
		final byte[] curModel = currentZoneModel(loaded);
		final List<ctrmap.formats.h3d.texturing.H3DTexture> curTex = worldTextures;
		final MapPreview3D view3d = (curModel != null) ? new MapPreview3D() : null;
		if (view3d != null) {
			view3d.setRegion(curModel, curTex);
			view3d.setPreferredSize(new Dimension(420, 340));
		}
		//COMPACT WHEN THE 3D VIEW IS THERE, not squeezed. This used to override the
		//card to 92px high while the card went on drawing content laid out for 340 -
		//so everything below the gradient band was clipped away, which is most of it.
		//A panel that draws more than it is given is a panel nobody can read; telling
		//it to draw LESS is the difference between a summary and a crop.
		final EnvPreview preview = new EnvPreview(view3d != null);

		final DefaultListModel<String> lm = new DefaultListModel<>();
		final List<Integer> visible = new ArrayList<>(); // index into zones
		final JList<String> list = new JList<>(lm);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		final JTextField search = new JTextField();

		Runnable rebuild = () -> {
			String q = search.getText().trim().toLowerCase();
			lm.clear();
			visible.clear();
			for (int i = 0; i < labels.size(); i++) {
				if (q.isEmpty() || labels.get(i).toLowerCase().contains(q)) {
					lm.addElement(labels.get(i));
					visible.add(i);
				}
			}
		};
		rebuild.run();
		search.getDocument().addDocumentListener(new DocumentListener() {
			public void insertUpdate(DocumentEvent e) {
				rebuild.run();
			}

			public void removeUpdate(DocumentEvent e) {
				rebuild.run();
			}

			public void changedUpdate(DocumentEvent e) {
				rebuild.run();
			}
		});
		list.addListSelectionListener(e -> {
			int vi = list.getSelectedIndex();
			if (vi < 0 || vi >= visible.size()) {
				return;
			}
			int area = zones.get(visible.get(vi))[1];
			byte[] s4 = envCache.computeIfAbsent(area, a -> areaSub4(adG, a));
			AreaEnv env = s4 == null ? null : AreaEnv.read(s4);
			preview.set(env);
			if (view3d != null && env != null) {
				// fog the user's OWN zone with this atmosphere, as it looks by day
				float[] fog = env.viewFog(AreaEnv.TIME_DAY);
				if (fog == null) {
					view3d.clearFog();
				} else {
					view3d.setFog(fog[0], fog[1], fog[2], env.fogNear, env.fogFar);
				}
			}
		});

		final JDialog dlg = new JDialog(parent, "GameFreak atmospheres", Dialog.ModalityType.APPLICATION_MODAL);
		dlg.setLayout(new BorderLayout(8, 8));
		((JPanel) dlg.getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JPanel left = new JPanel(new BorderLayout(0, 4));
		left.add(search, BorderLayout.NORTH);
		JScrollPane sp = new JScrollPane(list);
		sp.setPreferredSize(new Dimension(280, 340));
		left.add(sp, BorderLayout.CENTER);
		dlg.add(left, BorderLayout.WEST);
		if (view3d != null) {
			// your zone in 3D (top), with the atmosphere's values card (bottom)
			JPanel center = new JPanel(new BorderLayout(0, 6));
			JPanel head = new JPanel(new BorderLayout());
			head.add(new javax.swing.JLabel("Your zone under each atmosphere - drag to orbit:"), BorderLayout.WEST);
			center.add(head, BorderLayout.NORTH);
			center.add(view3d, BorderLayout.CENTER);
			center.add(preview, BorderLayout.SOUTH);
			dlg.add(center, BorderLayout.CENTER);
		} else {
			dlg.add(preview, BorderLayout.CENTER);
		}
		dlg.addWindowListener(new java.awt.event.WindowAdapter() {
			@Override
			public void windowClosed(java.awt.event.WindowEvent e) {
				if (view3d != null) {
					view3d.stop();
				}
			}
		});
		JPanel buttons = new JPanel();
		JButton ok = new JButton("Use this atmosphere");
		JButton cancel = new JButton("Cancel");
		buttons.add(ok);
		if (offerCustom) {
			JButton custom = new JButton("Custom settings");
			custom.setToolTipText("Hand-tune the fog color, strength, distances and ambient light instead.");
			custom.addActionListener(e -> {
				result[0] = CUSTOM;
				dlg.dispose();
			});
			buttons.add(custom);
		}
		buttons.add(cancel);
		dlg.add(buttons, BorderLayout.SOUTH);

		Runnable choose = () -> {
			int vi = list.getSelectedIndex();
			if (vi >= 0 && vi < visible.size()) {
				int area = zones.get(visible.get(vi))[1];
				byte[] s4 = envCache.computeIfAbsent(area, a -> areaSub4(adG, a));
				if (s4 != null) {
					result[0] = s4.clone();
				}
			}
			dlg.dispose();
		};
		ok.addActionListener(e -> choose.run());
		cancel.addActionListener(e -> dlg.dispose());
		list.addMouseListener(new java.awt.event.MouseAdapter() {
			public void mouseClicked(java.awt.event.MouseEvent e) {
				if (e.getClickCount() == 2) {
					choose.run();
				}
			}
		});

		if (!visible.isEmpty()) {
			list.setSelectedIndex(0);
		}
		dlg.pack();
		dlg.setLocationRelativeTo(parent);
		dlg.setVisible(true);
		return result[0];
	}

	/** The loaded zone's first region map model (for the live atmosphere preview), or null. */
	private static byte[] currentZoneModel(LoadedZone loaded) {
		try {
			if (loaded.open() == null) {
				return null;
			}
			File mmFile = Workspace.getWorkspaceFile(ArchiveType.MAP_MATRIX, loaded.open().header.mapmatrixID);
			byte[] mm = java.nio.file.Files.readAllBytes(mmFile.toPath());
			int s0 = i32(mm, 4);
			int w = u16(mm, s0 + 4), h = u16(mm, s0 + 6);
			int region = -1;
			for (int k = 0; k < w * h; k++) {
				int id = u16(mm, s0 + 8 + k * 2);
				if (id != 0xFFFF) {
					region = id;
					break;
				}
			}
			if (region < 0) {
				return null;
			}
			ctrmap.formats.containers.GR gr = new ctrmap.formats.containers.GR(
					new File(Workspace.getExtractionDirectory(ArchiveType.FIELD_DATA), String.valueOf(region)),
					Workspace.session());
			byte[] model = gr.getFile(1);
			return ctrmap.formats.h3d.BchMapModel.isMapModel(model) ? model : null;
		} catch (Exception ex) {
			return null;
		}
	}

	/** Pristine snapshot GARC when available (true retail values), else the live one. */
	private static GARC pristineOrLive(ArchiveType type) {
		try {
			String rel = Workspace.getArchivePath(type, Workspace.game());
			if (rel != null) {
				File snap = new File(Workspace.originalSnapshotDir().getAbsolutePath() + rel);
				if (snap.exists()) {
					return new GARC(snap);
				}
			}
		} catch (Exception ignore) {
		}
		return Workspace.getArchive(type);
	}

	private static byte[] areaSub4(GARC adG, int area) {
		try {
			byte[] entry = adG.getDecompressedEntry(area);
			byte[] s4 = subfile(entry, 4);
			return s4 != null && s4.length == AreaEnv.SUB4_LEN ? s4 : null;
		} catch (Exception ex) {
			return null;
		}
	}

	private static String[] locationNames() {
		try {
			File f = Workspace.getWorkspaceFile(ArchiveType.GAMETEXT,
					ctrmap.formats.text.LocationNames.gametextIndex(Workspace.session()));
			List<String> lines = GFMessageFile.getStrings(java.nio.file.Files.readAllBytes(f.toPath()));
			return lines.toArray(new String[0]);
		} catch (Exception ex) {
			return new String[0];
		}
	}

	/** Paints the selected zone's atmosphere: sky gradient, swatches, fog range. */
	/**
	 * The atmosphere card. PUBLIC so a suite can paint one and measure it: this
	 * panel was laid out for 340px and handed 92 whenever the 3D view existed, so
	 * everything below the gradient band was clipped and the owner reported the
	 * atmospheres as "a white blank page". A panel that draws past its own
	 * preferred size cannot be caught by reading it; it has to be drawn.
	 */
	public static class EnvPreview extends JPanel {

		AreaEnv env;
		/** True when the 3D view is showing the feel and this only has to show the values. */
		final boolean compact;

		public EnvPreview(boolean compact) {
			this.compact = compact;
			//360, not 340: the four time-of-day rows made the full card taller than the
			//number it had always asked for, and the last line went 9px past the bottom.
			//Nine pixels is invisible as a bug report and obvious to a measurement.
			setPreferredSize(new Dimension(300, compact ? COMPACT_HEIGHT : FULL_HEIGHT));
		}

		/** What the compact card needs: a label line, a swatch row, and a range line. */
		static final int COMPACT_HEIGHT = 92;
		/** What the full card needs, measured by AreaEnvTest painting one. */
		static final int FULL_HEIGHT = 360;

		/**
		 * The four times of day as swatches on one row, with the range under them.
		 *
		 * <p>Everything the full card says about FEEL - the haze gradient, the
		 * indoor/outdoor reading - is on screen already in 3D, on the user's own map.
		 * What is left is the numbers, and they fit.
		 */
		private void paintCompact(Graphics2D g, int pad) {
			g.setColor(getForeground());
			g.setFont(getFont().deriveFont(Font.BOLD, 12f));
			g.drawString("Fog through the day", pad, 14);
			g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
			int box = 26, gap = 6, x = pad;
			for (int t = 0; t < AreaEnv.TIMES; t++) {
				g.setColor(new Color(cl(env.fogColor[t][0]), cl(env.fogColor[t][1]), cl(env.fogColor[t][2])));
				g.fillRoundRect(x, 22, box, box, 5, 5);
				g.setColor(new Color(0, 0, 0, 90));
				g.drawRoundRect(x, 22, box, box, 5, 5);
				g.setColor(getForeground());
				g.drawString(AreaEnv.TIME_NAMES[t].substring(0, 3), x, 60);
				g.drawString(String.format("%.0f%%", env.fogStrength[t] * 100), x, 72);
				x += box + gap + 14;
			}
			g.drawString(String.format("Fog: starts %.0f, full at %.0f", env.fogNear, env.fogFar),
				pad, 88);
		}

		public void set(AreaEnv env) {
			this.env = env;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g0) {
			super.paintComponent(g0);
			Graphics2D g = (Graphics2D) g0;
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth(), pad = 12;
			if (env == null) {
				g.setColor(getForeground());
				g.drawString("(no environment data)", pad, 30);
				return;
			}
			if (compact) {
				paintCompact(g, pad);
				return;
			}
			//THE FOUR TIMES OF DAY, which is what an area actually carries. This card
			//used to paint one "fog colour" over one "ambient" - and both came from the
			//misread channel 0, where the fog row was three different times of day of a
			//single colour and the ambient row was the constant 1.0 group. So the card
			//drew near-white over white for most areas and looked like it had failed to
			//load, which is exactly what it was reported as.
			Color[] times = new Color[AreaEnv.TIMES];
			for (int t = 0; t < AreaEnv.TIMES; t++) {
				times[t] = new Color(cl(env.fogColor[t][0]), cl(env.fogColor[t][1]), cl(env.fogColor[t][2]));
			}
			Color fogC = times[AreaEnv.TIME_DAY];
			// sky/haze impression: the day fog fading over a ground tone, by its strength
			Color ground = new Color(90, 140, 80);
			float dayStrength = env.fogStrength[AreaEnv.TIME_DAY];
			g.setPaint(new GradientPaint(0, 20, mix(ground, fogC, dayStrength), 0, 150,
				mix(ground, fogC, dayStrength * 0.35f)));
			g.fillRoundRect(pad, 20, w - pad * 2, 130, 10, 10);
			g.setColor(new Color(0, 0, 0, 90));
			g.drawRoundRect(pad, 20, w - pad * 2, 130, 10, 10);
			g.setColor(getForeground());
			g.setFont(getFont().deriveFont(Font.BOLD, 13f));
			g.drawString("How this area feels", pad, 14);
			g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
			int y = 175;
			for (int t = 0; t < AreaEnv.TIMES; t++) {
				y = row(g, pad, y, AreaEnv.TIME_NAMES[t] + String.format("  %.0f%%", env.fogStrength[t] * 100),
					times[t]);
			}
			g.setColor(getForeground());
			g.drawString(String.format("Fog: starts %.0f, full at %.0f", env.fogNear, env.fogFar), pad, y + 4);
			y += 22;
			String kind = env.fogFar >= 2000 ? "Outdoor-style (long draw, open sky)"
					: env.fogFar >= 800 ? "Large interior / bright room" : "Small interior / cave (short draw)";
			g.drawString(kind, pad, y + 4);
			y += 26;
			g.setFont(getFont().deriveFont(Font.ITALIC, 11f));
			g.drawString("Applies the COMPLETE GameFreak setup", pad, y + 4);
			g.drawString("(colors, light direction, ranges).", pad, y + 20);
		}

		private int row(Graphics2D g, int x, int y, String label, Color c) {
			g.setColor(c);
			g.fillRoundRect(x, y, 40, 18, 6, 6);
			g.setColor(new Color(0, 0, 0, 110));
			g.drawRoundRect(x, y, 40, 18, 6, 6);
			g.setColor(getForeground());
			g.drawString(label, x + 50, y + 14);
			return y + 26;
		}

		private static int cl(float v) {
			return Math.max(0, Math.min(255, Math.round(v * 255)));
		}

		private static Color mix(Color a, Color b, float t) {
			t = Math.max(0, Math.min(1, t));
			return new Color(
					(int) (a.getRed() * (1 - t) + b.getRed() * t),
					(int) (a.getGreen() * (1 - t) + b.getGreen() * t),
					(int) (a.getBlue() * (1 - t) + b.getBlue() * t));
		}
	}
}
