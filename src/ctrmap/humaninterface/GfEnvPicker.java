package ctrmap.humaninterface;

import ctrmap.LoadedZone;
import ctrmap.Workspace;
import ctrmap.formats.area.AreaEnv;
import ctrmap.formats.garc.GARC;
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

import static ctrmap.util.LittleEndian.u16;
import static ctrmap.formats.containers.ContainerBytes.subfile;

/**
 * "Pick GameFreak's atmosphere": browse every retail zone by name, see a LIVE
 * preview of the environment GameFreak gave it (fog colour across the four times
 * of day, fog range, indoor/outdoor character), and take that COMPLETE per-area
 * block - its lighting included, which nothing here decodes - to apply to your
 * own area - no hand-tuning needed, but the fog stays
 * editable afterwards. Reads the pristine snapshot of the game data when
 * available, so the values are what GameFreak shipped even if the live
 * workspace was edited.
 */
public class GfEnvPicker {

	/**
	 * What the picker says, in red under its list, of where its atmospheres came from when that was not the game's
	 * untouched copy; null when there is nothing to say. Out of the picker so a suite reaches it: the picker is a modal
	 * window (the 2026-10-14 shared sweep, GfEnvPicker 196, negate-if, SURVIVED).
	 */
	public static javax.swing.JLabel sourceNotes(List<String> notes) {
		if (notes.isEmpty()) {
			return null;
		}
		return new javax.swing.JLabel("<html><body style='width:270px'><font color='#b00000'>"
				+ ctrmap.Ui.html(String.join(" ", notes)) + "</font></body></html>");
	}

	/**
	 * The picker's left side: the search box over the list, and under them what {@link #sourceNotes} says, when it
	 * says anything. Out of the picker so a suite reaches it (the 2026-10-16 assembly, GfEnvPicker 210, negate-if,
	 * SURVIVED: the picker is modal). Inverted, the notes were never shown, and a picker with nothing to say added a
	 * null where they go.
	 */
	public static JPanel listPane(java.awt.Component search, JList<String> list, List<String> notes) {
		JPanel left = new JPanel(new BorderLayout(0, 4));
		left.add(search, BorderLayout.NORTH);
		JScrollPane sp = new JScrollPane(list);
		sp.setPreferredSize(new Dimension(280, 340));
		left.add(sp, BorderLayout.CENTER);
		javax.swing.JLabel noted = sourceNotes(notes);
		if (noted != null) {
			left.add(noted, BorderLayout.SOUTH);
		}
		return left;
	}

	/** Returned by {@link #pick} when the user asked for the custom-settings form. */
	public static final byte[] CUSTOM = new byte[0];

	/**
	 * Opens the picker; returns the chosen env block, {@link #CUSTOM} when the
	 * user pressed "Custom settings..." (offered only when {@code offerCustom}),
	 * or null on cancel.
	 *
	 * @param worldTextures the open map's decoded world textures, or null when
	 *        no map is open. Handed in rather than read off the map view through
	 *        the main window: this picker uses exactly this one thing from it.
	 */
	public static byte[] pick(java.awt.Window parent, boolean offerCustom, LoadedZone loaded,
			List<ctrmap.formats.h3d.texturing.H3DTexture> worldTextures) {
		if (loaded == null) {
			throw new IllegalArgumentException("GfEnvPicker must be handed the LoadedZone");
		}
		//both refusals returned null without a word, which the caller reads as Cancel (F211)
		if (!Workspace.isValid()) {
			ctrmap.Ui.error(parent, "Open a workspace first (Options > Workspace settings): the atmospheres are read"
					+ " from its game data.", "Atmosphere");
			return null;
		}
		List<String> sourceNotes = new ArrayList<>();
		final GARC zoG = pristineOrLive(ArchiveType.ZONE_DATA, sourceNotes);
		final GARC adG = pristineOrLive(ArchiveType.AREA_DATA, sourceNotes);
		if (zoG == null || adG == null) {
			ctrmap.Ui.error(parent, "This workspace's " + (zoG == null ? ArchiveType.ZONE_DATA : ArchiveType.AREA_DATA)
					+ " archive is not open, so there are no atmospheres to pick from.", "Atmosphere");
			return null;
		}
		java.util.List<String> unread = new java.util.ArrayList<>();
		String[] locs = Workspace.textLines(ctrmap.formats.text.LocationNames.gametextIndex(Workspace.session()), unread);
		ctrmap.Ui.saidUnread(parent, unread, "Atmosphere");

		// zone list: id, location name, area id
		final List<int[]> zones = new ArrayList<>();   // {zoneId, areaId}
		final List<String> labels = new ArrayList<>();
		//the profile's measured tail, not ORAS's "2" (F029): on a game ending with the master
		//table alone, length - 2 counted that table as a zone
		int zoneCount;
		try {
			zoneCount = ctrmap.ZoneTables.zoneCount(zoG, Workspace.profile());
		} catch (java.io.IOException unmeasured) {
			ctrmap.Ui.error(parent, ctrmap.Ui.reason(unmeasured), "Atmosphere");
			return null;
		}
		//ONE TABLE, NOT EVERY ZONE (census M.70): this inflated all 536 zone containers - header, entities and
		//script - on the event thread to read two numbers from each header, every time the dialog opened. The
		//master zone-header table holds every zone's header in one entry; measured on the ORAS dump, its row
		//agrees with the zone's own header on the area and the parent map for 536 of 536 zones
		ctrmap.MasterTable master;
		try {
			master = ctrmap.MasterTable.detached(zoG.getDecompressedEntry(zoneCount), zoneCount);
		} catch (java.io.IOException noTable) {
			ctrmap.Ui.error(parent, "The zone list could not be read: " + ctrmap.Ui.reason(noTable), "Atmosphere");
			return null;
		}
		for (int z = 0; z < zoneCount; z++) {
			byte[] hdr = master.row(z);
			int area = u16(hdr, 2);
			int loc = ctrmap.formats.zone.ZoneHeader.parentMapOf(hdr);
			String nm = loc < locs.length ? locs[loc] : "";
			zones.add(new int[]{z, area});
			labels.add(z + "  " + nm + "  (area " + area + ")");
		}

		final Map<Integer, byte[]> envCache = new HashMap<>();
		final byte[][] result = {null};

		// live preview: the USER'S CURRENT zone geometry, re-fogged per selection,
		// so they see each atmosphere on their own map. Falls back to a card.
		final byte[] curModel = currentZoneModel(loaded, sourceNotes);
		final List<ctrmap.formats.h3d.texturing.H3DTexture> curTex = worldTextures;
		//and the card alone where OpenGL cannot start (F077), as where there is no zone
		final MapPreview3D view3d = (curModel != null && GlSupport.available()) ? new MapPreview3D() : null;
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
		ctrmap.util.Selections.onSettled(list, () -> {
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
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);   //the title bar's X lets it go (census L.53)
		dlg.setLayout(new BorderLayout(8, 8));
		((JPanel) dlg.getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		dlg.add(listPane(search, list, sourceNotes), BorderLayout.WEST);
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
			custom.setToolTipText("Hand-tune the fog colour, strength and distances instead.");
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

	/**
	 * The loaded zone's OWN region's map model, for the live atmosphere preview - or null, with the reason
	 * added to {@code notes} when there was a zone to show and it could not be.
	 *
	 * <p>ITS OWN REGION (census M.54): this took the matrix's first filled cell, which on the 94 retail zones
	 * that share a matrix is a neighbour's - the user judged the fog against somebody else's map. It read
	 * the region from the extraction folder by name, which holds nothing until something extracts it, and
	 * every failure went into one catch that answered null - the card, without a word.
	 */
	private static byte[] currentZoneModel(LoadedZone loaded, List<String> notes) {
		if (!loaded.isOpen()) {
			return null;
		}
		try {
			int[] cell = ctrmap.MapPainter.firstRegionCell(loaded);
			if (cell == null) {
				return null;   //the zone's map holds no region: there is nothing of its own to show
			}
			ctrmap.formats.containers.GR gr = new ctrmap.formats.containers.GR(
					Workspace.getWorkspaceFile(ArchiveType.FIELD_DATA, cell[0]), Workspace.session());
			byte[] model = gr.getFile(1);
			if (!ctrmap.formats.h3d.BchMapModel.isMapModel(model)) {
				notes.add("Your zone's region " + cell[0] + " holds no map model this preview can draw, so it shows"
						+ " the card.");
				return null;
			}
			return model;
		} catch (RuntimeException unread) {
			notes.add("Your zone's map could not be read for the preview (" + ctrmap.Ui.reason(unread)
					+ "), so it shows the card.");
			return null;
		}
	}

	/**
	 * Pristine snapshot GARC when available (true retail values), else the live one - and
	 * when it is the live one, {@code notes} says so and why (F211). A snapshot that existed
	 * and would not open was swallowed, and the picker went on calling the workspace's own,
	 * possibly edited, values GameFreak's.
	 */
	private static GARC pristineOrLive(ArchiveType type, List<String> notes) {
		//THE ONE PRISTINE READER (F232): the snapshot, else the sealed backup's verified copy. The
		//workspace's own values are shown only when neither is there, and then SAID to be the
		//workspace's - a picker labels what it shows; it does not stamp it into anything.
		ctrmap.WorkspaceSession session = Workspace.session();
		File snap = null;
		try {
			snap = session == null ? null : session.pristineArchive(type);
		} catch (java.io.IOException damaged) {
			notes.add("The untouched copy of " + type + " could not be read (" + ctrmap.Ui.reason(damaged)
					+ "), so these are this workspace's values.");
			return Workspace.getArchive(type);
		}
		if (snap != null) {
			try {
				return new GARC(snap);
			} catch (Exception ex) {
				notes.add("The pristine copy of " + type + " could not be read (" + ctrmap.Ui.reason(ex)
						+ "), so these are this workspace's values.");
			}
		} else {
			notes.add("This workspace has no pristine copy of " + type
					+ ", so a zone it has edited shows its edited values, not GameFreak's.");
		}
		return Workspace.getArchive(type);
	}

	private static byte[] areaSub4(GARC adG, int area) {
		try {
			byte[] entry = adG.getDecompressedEntry(area);
			byte[] s4 = subfile(entry, ctrmap.formats.containers.AD.ENVIRONMENT);
			return s4 != null && s4.length == AreaEnv.SUB4_LEN ? s4 : null;
		} catch (Exception ex) {
			return null;
		}
	}

	/**
	 * The atmosphere card: sky gradient, swatches, fog range. PUBLIC so a suite can
	 * paint one and measure it: this panel was laid out for 340px and handed 92
	 * whenever the 3D view existed, so everything below the gradient band was clipped
	 * and the owner reported the atmospheres as "a white blank page". A panel that
	 * draws past its own preferred size cannot be caught by reading it; it has to be
	 * drawn.
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
