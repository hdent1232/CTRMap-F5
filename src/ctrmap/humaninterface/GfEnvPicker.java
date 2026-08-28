package ctrmap.humaninterface;

import ctrmap.Workspace;
import ctrmap.formats.area.AreaEnv;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.text.GFMessageFile;
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

	/** Opens the picker; returns the chosen zone's full 2944-byte env block, or null. */
	public static byte[] pick(Dialog parent) {
		final GARC zoG = pristineOrLive(Workspace.ArchiveType.ZONE_DATA);
		final GARC adG = pristineOrLive(Workspace.ArchiveType.AREA_DATA);
		if (zoG == null || adG == null) {
			return null;
		}
		String[] locs = locationNames();

		// zone list: id, location name, area id
		final List<int[]> zones = new ArrayList<>();   // {zoneId, areaId}
		final List<String> labels = new ArrayList<>();
		int zoneCount = zoG.length - 2;
		for (int z = 0; z < zoneCount; z++) {
			byte[] hdr = sub(zoG.getDecompressedEntry(z), 0);
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

		final DefaultListModel<String> lm = new DefaultListModel<>();
		final List<Integer> visible = new ArrayList<>(); // index into zones
		final JList<String> list = new JList<>(lm);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		final JTextField search = new JTextField();
		final EnvPreview preview = new EnvPreview();

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
			preview.set(s4 == null ? null : AreaEnv.read(s4));
		});

		final JDialog dlg = new JDialog(parent, "GameFreak atmospheres", true);
		dlg.setLayout(new BorderLayout(8, 8));
		((JPanel) dlg.getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JPanel left = new JPanel(new BorderLayout(0, 4));
		left.add(search, BorderLayout.NORTH);
		JScrollPane sp = new JScrollPane(list);
		sp.setPreferredSize(new Dimension(280, 340));
		left.add(sp, BorderLayout.CENTER);
		dlg.add(left, BorderLayout.WEST);
		dlg.add(preview, BorderLayout.CENTER);
		JPanel buttons = new JPanel();
		JButton ok = new JButton("Use this atmosphere");
		JButton cancel = new JButton("Cancel");
		buttons.add(ok);
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

	/** Pristine snapshot GARC when available (true retail values), else the live one. */
	private static GARC pristineOrLive(Workspace.ArchiveType type) {
		try {
			String rel = Workspace.getArchivePath(type, Workspace.game);
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
			byte[] s4 = sub(entry, 4);
			return s4 != null && s4.length == AreaEnv.SUB4_LEN ? s4 : null;
		} catch (Exception ex) {
			return null;
		}
	}

	private static String[] locationNames() {
		try {
			File f = Workspace.getWorkspaceFile(Workspace.ArchiveType.GAMETEXT, 90);
			List<String> lines = GFMessageFile.getStrings(java.nio.file.Files.readAllBytes(f.toPath()));
			return lines.toArray(new String[0]);
		} catch (Exception ex) {
			return new String[0];
		}
	}

	/** Paints the selected zone's atmosphere: sky gradient, swatches, fog range. */
	private static class EnvPreview extends JPanel {

		AreaEnv env;

		EnvPreview() {
			setPreferredSize(new Dimension(300, 340));
		}

		void set(AreaEnv env) {
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
			Color fogC = new Color(cl(env.fogColor[0]), cl(env.fogColor[1]), cl(env.fogColor[2]));
			Color ambC = new Color(cl(env.ambient[0]), cl(env.ambient[1]), cl(env.ambient[2]));
			// sky/haze impression: fog color fading over a ground tone
			Color ground = new Color(90, 140, 80);
			g.setPaint(new GradientPaint(0, 20, fogC, 0, 150, mix(ground, fogC, env.fogColor[3])));
			g.fillRoundRect(pad, 20, w - pad * 2, 130, 10, 10);
			g.setColor(new Color(0, 0, 0, 90));
			g.drawRoundRect(pad, 20, w - pad * 2, 130, 10, 10);
			g.setColor(getForeground());
			g.setFont(getFont().deriveFont(Font.BOLD, 13f));
			g.drawString("How this area feels", pad, 14);
			g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
			int y = 175;
			y = row(g, pad, y, "Fog / sky color", fogC);
			y = row(g, pad, y, "Ambient / light", ambC);
			g.setColor(getForeground());
			g.drawString(String.format("Fog: starts %.0f, full at %.0f", env.fogNear, env.fogFar), pad, y + 4);
			y += 22;
			g.drawString(String.format("Fog strength: %.0f%%", env.fogColor[3] * 100), pad, y + 4);
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

	static byte[] sub(byte[] c, int i) {
		if (c == null || c.length < 8) {
			return null;
		}
		int st = u32(c, 4 + 4 * i), en = u32(c, 4 + 4 * (i + 1));
		if (st < 0 || en > c.length || en < st) {
			return null;
		}
		return Arrays.copyOfRange(c, st, en);
	}

	static int u16(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
	}

	static int u32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
