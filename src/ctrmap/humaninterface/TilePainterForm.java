package ctrmap.humaninterface;

import ctrmap.GeometryForker;
import ctrmap.Workspace;
import ctrmap.formats.containers.GR;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.RegionFactory;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TilePalette;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JToggleButton;

import static ctrmap.CtrmapMainframe.*;

/**
 * The tile painter: paint terrain (grass, tall grass, path, sand, water, rock)
 * onto a 40x40 grid and generate a full custom map region from it - textured
 * geometry, walkable/encounter/surf tilemap, and collision - via
 * {@link PaintedRegionBuilder}. The zone's own region is the tileset (its
 * materials + textures are reused), so applying stays within the zone's area.
 * The first step for building a zone the "tile" way instead of importing a 3D
 * model.
 */
public class TilePainterForm {

	private static final int DIM = PaintedRegionBuilder.DIM;

	public static void show() {
		if (!Workspace.valid || !Workspace.isOA()) {
			JOptionPane.showMessageDialog(frame, "Load an ORAS workspace first.", "Tile painter", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (mZonePnl == null || mZonePnl.zone == null || mZonePnl.zoneIndex < 0) {
			JOptionPane.showMessageDialog(frame, "Load the zone to paint first (Zone tab).", "Tile painter", JOptionPane.ERROR_MESSAGE);
			return;
		}
		final int zoneIndex = mZonePnl.zoneIndex;
		final int region = firstRegion();
		if (region < 0) {
			JOptionPane.showMessageDialog(frame, "This zone has no map region to paint.", "Tile painter", JOptionPane.ERROR_MESSAGE);
			return;
		}

		final TilePalette[][] grid = new TilePalette[DIM][DIM];
		for (TilePalette[] row : grid) {
			java.util.Arrays.fill(row, TilePalette.GRASS);
		}
		loadFromRegion(region, grid); // seed from the region's current tilemap if any

		final TilePalette[] brush = {TilePalette.GRASS};
		final int[] tool = {0}; // 0 paint, 1 fill, 2 raise, 3 lower
		final int[][] height = new int[DIM][DIM];
		final ctrmap.formats.tilemap.TerrainLighting lighting = ctrmap.formats.tilemap.TerrainLighting.daytime();

		// the region's own model = the tileset donor (materials + textures + area
		// all correct); used for the textured previews and the generated geometry
		byte[] donorTmp = null;
		ctrmap.formats.tilemap.TerrainTextures terrainTex = null;
		try {
			GR gr = new GR(new File(Workspace.getExtractionDirectory(Workspace.ArchiveType.FIELD_DATA), String.valueOf(region)));
			donorTmp = gr.getFile(1);
			terrainTex = ctrmap.formats.tilemap.TerrainTextures.build(donorTmp, mTileMapPanel.getWorldTextures());
		} catch (Exception ex) {
			// no textures - the preview toggle just falls back to flat colors
		}
		final byte[] donorModel = donorTmp;

		final GridCanvas canvas = new GridCanvas(grid, height, brush, tool, terrainTex);

		final JDialog dlg = new JDialog(frame, "Tile painter - zone " + zoneIndex, true);
		dlg.setLayout(new BorderLayout(8, 8));
		((JPanel) dlg.getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		// brush palette
		JPanel side = new JPanel();
		side.setLayout(new javax.swing.BoxLayout(side, javax.swing.BoxLayout.Y_AXIS));
		side.add(new JLabel("Brush:"));
		ButtonGroup bg = new ButtonGroup();
		for (TilePalette t : TilePalette.brushes()) {
			JToggleButton b = new JToggleButton(t.label);
			b.setBackground(t.color());
			b.setForeground(textOn(t.color()));
			b.setFocusable(false);
			b.setAlignmentX(0f);
			b.setMaximumSize(new Dimension(190, 26));
			b.addActionListener(e -> brush[0] = t);
			if (t == TilePalette.GRASS) {
				b.setSelected(true);
			}
			bg.add(b);
			side.add(b);
		}
		side.add(javax.swing.Box.createVerticalStrut(10));
		side.add(new JLabel("Tool:"));
		ButtonGroup tg = new ButtonGroup();
		JToggleButton paint = new JToggleButton("Paint (drag)");
		JToggleButton fill = new JToggleButton("Fill area");
		JToggleButton raise = new JToggleButton("Raise (+level)");
		JToggleButton lower = new JToggleButton("Lower (-level)");
		paint.setSelected(true);
		for (JToggleButton b : new JToggleButton[]{paint, fill, raise, lower}) {
			b.setFocusable(false);
			b.setAlignmentX(0f);
			tg.add(b);
			side.add(b);
		}
		paint.addActionListener(e -> tool[0] = 0);
		fill.addActionListener(e -> tool[0] = 1);
		raise.addActionListener(e -> tool[0] = 2);
		lower.addActionListener(e -> tool[0] = 3);
		raise.setToolTipText("Click tiles to raise them a level (cliffs form at drops).");
		lower.setToolTipText("Click tiles to lower them a level.");
		side.add(javax.swing.Box.createVerticalStrut(10));
		JButton clearAll = new JButton("Fill all with brush");
		clearAll.setAlignmentX(0f);
		clearAll.setFocusable(false);
		clearAll.addActionListener(e -> {
			for (TilePalette[] row : grid) {
				java.util.Arrays.fill(row, brush[0]);
			}
			canvas.repaint();
		});
		side.add(clearAll);
		side.add(javax.swing.Box.createVerticalStrut(10));
		boolean canTexture = terrainTex != null && terrainTex.any();
		final JToggleButton texToggle = new JToggleButton("Textured (top-down)");
		texToggle.setAlignmentX(0f);
		texToggle.setFocusable(false);
		texToggle.setEnabled(canTexture);
		texToggle.setToolTipText(canTexture
				? "Quick top-down layout with the real textures (approximate - no lighting/angle)."
				: "Load this zone in the map view first (its textures power the preview).");
		texToggle.addActionListener(e -> {
			canvas.textured = texToggle.isSelected();
			canvas.repaint();
		});
		side.add(texToggle);
		final JButton view3d = new JButton("3D preview (real render)");
		view3d.setAlignmentX(0f);
		view3d.setFocusable(false);
		view3d.setEnabled(donorModel != null);
		view3d.setToolTipText("Render the painted map with CTRMap's 3D engine - how it actually looks (drag to orbit).");
		view3d.addActionListener(e -> open3DPreview(donorModel, grid, height, lighting));
		side.add(view3d);

		// lighting: the mood GameFreak varied per area (baked into the ground)
		side.add(javax.swing.Box.createVerticalStrut(12));
		side.add(new JLabel("Lighting (baked into the map):"));
		final JSlider bright = new JSlider(30, 130, Math.round(lighting.brightness * 100));
		final JSlider shadow = new JSlider(0, 90, Math.round(lighting.edgeShadow * 100));
		final Color[] tintHolder = {lighting.tintColor()};
		bright.setMaximumSize(new Dimension(200, 22));
		shadow.setMaximumSize(new Dimension(200, 22));
		bright.setAlignmentX(0f);
		shadow.setAlignmentX(0f);
		bright.setToolTipText("Overall brightness");
		shadow.setToolTipText("Edge shadow (ambient occlusion near walls/water)");
		bright.addChangeListener(e -> lighting.brightness = bright.getValue() / 100f);
		shadow.addChangeListener(e -> lighting.edgeShadow = shadow.getValue() / 100f);
		JPanel presets = new JPanel(new GridLayout(1, 4, 2, 0));
		presets.setAlignmentX(0f);
		presets.setMaximumSize(new Dimension(200, 24));
		Runnable syncSliders = () -> {
			bright.setValue(Math.round(lighting.brightness * 100));
			shadow.setValue(Math.round(lighting.edgeShadow * 100));
		};
		addPreset(presets, "Day", 1.0f, 0xFFF6E6, 0.35f, lighting, syncSliders);
		addPreset(presets, "Dusk", 0.85f, 0xFFC98A, 0.45f, lighting, syncSliders);
		addPreset(presets, "Cave", 0.55f, 0x9FB0C8, 0.6f, lighting, syncSliders);
		addPreset(presets, "Night", 0.45f, 0x8090C0, 0.5f, lighting, syncSliders);
		side.add(presets);
		side.add(new JLabel("  brightness"));
		side.add(bright);
		side.add(new JLabel("  edge shadow"));
		side.add(shadow);
		JButton tintBtn = new JButton("Light color...");
		tintBtn.setAlignmentX(0f);
		tintBtn.setFocusable(false);
		tintBtn.addActionListener(e -> {
			Color c = javax.swing.JColorChooser.showDialog(view3d, "Light color", lighting.tintColor());
			if (c != null) {
				lighting.tint = c.getRGB() & 0xFFFFFF;
			}
		});
		side.add(tintBtn);
		side.add(new JLabel("  (Preview or Apply to see the lighting.)"));

		dlg.add(side, BorderLayout.WEST);
		dlg.add(canvas, BorderLayout.CENTER);
		JPanel north = new JPanel(new GridLayout(2, 1));
		north.add(new JLabel("  Paint terrain; Raise/Lower click a tile up/down a level (cliffs form at drops). 3D preview shows the real look. Apply, then Deploy."));
		north.add(new JLabel("  Terrain visuals use this zone's own materials - start the zone from a grassy route (Blank map canvas) for grass/water/rock textures."));
		dlg.add(north, BorderLayout.NORTH);

		JPanel buttons = new JPanel();
		JButton apply = new JButton("Apply to zone");
		JButton close = new JButton("Close");
		buttons.add(apply);
		buttons.add(close);
		dlg.add(buttons, BorderLayout.SOUTH);

		apply.addActionListener(e -> {
			int rsl = JOptionPane.showConfirmDialog(dlg,
					"Build zone " + zoneIndex + "'s map from the painted tiles?\n"
					+ "The zone gets its own private geometry first (the source is untouched),\n"
					+ "then packs. Deploy to walk on it.",
					"Tile painter", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (rsl != JOptionPane.OK_OPTION) {
				return;
			}
			try {
				applyToZone(zoneIndex, grid, height, lighting);
				dlg.dispose();
			} catch (Exception ex) {
				JOptionPane.showMessageDialog(dlg, "Apply failed:\n" + ex.getMessage(), "Tile painter", JOptionPane.ERROR_MESSAGE);
			}
		});
		close.addActionListener(e -> dlg.dispose());

		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);
	}

	private static void addPreset(JPanel panel, String label, float brightness, int tint, float shadow,
			ctrmap.formats.tilemap.TerrainLighting lighting, Runnable onSet) {
		JButton b = new JButton(label);
		b.setMargin(new java.awt.Insets(1, 2, 1, 2));
		b.setFocusable(false);
		b.addActionListener(e -> {
			lighting.brightness = brightness;
			lighting.tint = tint;
			lighting.edgeShadow = shadow;
			onSet.run();
		});
		panel.add(b);
	}

	/** Generates the model from the current grid and shows it in the real 3D renderer. */
	private static void open3DPreview(byte[] donorModel, TilePalette[][] grid, int[][] height, ctrmap.formats.tilemap.TerrainLighting lighting) {
		try {
			byte[] model = PaintedRegionBuilder.build(donorModel, grid, height, lighting).model;
			MapPreview3D view = new MapPreview3D();
			view.setRegion(model, mTileMapPanel.getWorldTextures());
			// show the zone's area fog/atmosphere in the preview
			try {
				int areaId = mZonePnl.zone.header.areadataID;
				ctrmap.formats.containers.AD ad = new ctrmap.formats.containers.AD(
						Workspace.getWorkspaceFile(Workspace.ArchiveType.AREA_DATA, areaId));
				ctrmap.formats.area.AreaEnv env = ctrmap.formats.area.AreaEnv.read(ad.getFile(4));
				view.setFog(env.fogColor[0], env.fogColor[1], env.fogColor[2], env.fogNear, env.fogFar);
			} catch (Exception ignore) {
				// no fog data - preview just uses the plain sky
			}
			view.setPreferredSize(new Dimension(640, 520));
			final JDialog d = new JDialog(frame, "3D preview - how the map looks (drag to orbit, wheel to zoom)", false);
			d.add(view, BorderLayout.CENTER);
			d.addWindowListener(new java.awt.event.WindowAdapter() {
				@Override
				public void windowClosing(java.awt.event.WindowEvent e) {
					view.stop();
				}
			});
			d.pack();
			d.setLocationRelativeTo(frame);
			d.setVisible(true);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(frame, "3D preview failed:\n" + ex.getMessage(), "Tile painter", JOptionPane.ERROR_MESSAGE);
		}
	}

	private static void applyToZone(int zoneIndex, TilePalette[][] grid, int[][] height, ctrmap.formats.tilemap.TerrainLighting lighting) throws Exception {
		GeometryForker.ForkResult r = GeometryForker.forkGeometry(zoneIndex);
		File fdDir = Workspace.getExtractionDirectory(Workspace.ArchiveType.FIELD_DATA);
		for (int newRegion : r.newRegions) {
			File f = new File(fdDir, String.valueOf(newRegion));
			GR gr = new GR(f);
			byte[] donor = gr.getFile(1);
			if (!BchMapModel.isMapModel(donor)) {
				continue;
			}
			RegionFactory.BlankContent bc = PaintedRegionBuilder.build(donor, grid, height, lighting);
			gr.storeFile(1, bc.model);
			gr.storeFile(2, bc.collision);
			gr.storeFile(0, bc.tilemap);
			gr.storeFile(3, bc.props);
		}
		Workspace.packWorkspace(new Runnable() {
			@Override
			public void run() {
				mZonePnl.loadEverything(new Runnable() {
					@Override
					public void run() {
						mZonePnl.selectZone(zoneIndex);
						JOptionPane.showMessageDialog(frame,
								"Painted map applied to zone " + zoneIndex + " (region(s) "
								+ java.util.Arrays.toString(r.newRegions) + ").\nDeploy to emulator to walk on it.",
								"Tile painter", JOptionPane.INFORMATION_MESSAGE);
					}
				});
			}
		});
	}

	/** Seeds the grid from the region's existing tilemap tuples (reverse lookup). */
	private static void loadFromRegion(int region, TilePalette[][] grid) {
		try {
			GR gr = new GR(new File(Workspace.getExtractionDirectory(Workspace.ArchiveType.FIELD_DATA), String.valueOf(region)));
			byte[] tm = gr.getFile(0);
			if (tm == null || tm.length < 8) {
				return;
			}
			int w = tm[0] & 0xFF, h = tm[2] & 0xFF;
			if (w != DIM || h != DIM) {
				return;
			}
			for (int ty = 0; ty < DIM; ty++) {
				for (int tx = 0; tx < DIM; tx++) {
					int off = 4 + (ty * DIM + tx) * 4;
					grid[ty][tx] = terrainOf(tm[off] & 0xFF, tm[off + 1] & 0xFF, tm[off + 2] & 0xFF, tm[off + 3] & 0xFF);
				}
			}
		} catch (Exception ex) {
			// leave the default all-grass grid
		}
	}

	private static TilePalette terrainOf(int b0, int b1, int b2, int b3) {
		for (TilePalette t : TilePalette.values()) {
			if ((t.tuple[0] & 0xFF) == b0 && (t.tuple[1] & 0xFF) == b1
					&& (t.tuple[2] & 0xFF) == b2 && (t.tuple[3] & 0xFF) == b3) {
				return t;
			}
		}
		// unknown tuple: treat impassable as rock/void, else walkable ground
		return (b0 & 1) == 1 ? TilePalette.VOID : TilePalette.GRASS;
	}

	private static int firstRegion() {
		try {
			File mmFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.MAP_MATRIX, mZonePnl.zone.header.mapmatrixID);
			byte[] mm = java.nio.file.Files.readAllBytes(mmFile.toPath());
			int sub0 = le32(mm, 4);
			int w = u16(mm, sub0 + 4), h = u16(mm, sub0 + 6);
			for (int k = 0; k < w * h; k++) {
				int id = u16(mm, sub0 + 8 + k * 2);
				if (id != 0xFFFF) {
					return id;
				}
			}
		} catch (Exception ex) {
		}
		return -1;
	}

	private static Color textOn(Color c) {
		double lum = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
		return lum > 140 ? Color.BLACK : Color.WHITE;
	}

	private static int u16(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
	}

	private static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}

	/** The 40x40 paint grid canvas. */
	private static class GridCanvas extends JPanel {

		static final int CELL = 14;
		final TilePalette[][] grid;
		final int[][] height;
		final TilePalette[] brush;
		final int[] tool;
		final ctrmap.formats.tilemap.TerrainTextures terrainTex;
		boolean textured = false;
		private final java.util.Map<TilePalette, java.awt.TexturePaint> paintCache = new java.util.HashMap<>();

		GridCanvas(TilePalette[][] grid, int[][] height, TilePalette[] brush, int[] tool, ctrmap.formats.tilemap.TerrainTextures terrainTex) {
			this.grid = grid;
			this.height = height;
			this.brush = brush;
			this.tool = tool;
			this.terrainTex = terrainTex;
			setPreferredSize(new Dimension(DIM * CELL + 1, DIM * CELL + 1));
			MouseAdapter ma = new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					handle(e);
				}

				@Override
				public void mouseDragged(MouseEvent e) {
					if (tool[0] == 0) {
						handle(e);
					}
				}
			};
			addMouseListener(ma);
			addMouseMotionListener(ma);
		}

		void handle(MouseEvent e) {
			int tx = e.getX() / CELL, ty = e.getY() / CELL;
			if (tx < 0 || ty < 0 || tx >= DIM || ty >= DIM) {
				return;
			}
			switch (tool[0]) {
				case 1:
					flood(tx, ty, grid[ty][tx], brush[0]);
					break;
				case 2:
					height[ty][tx] = Math.min(6, height[ty][tx] + 1);
					break;
				case 3:
					height[ty][tx] = Math.max(0, height[ty][tx] - 1);
					break;
				default:
					grid[ty][tx] = brush[0];
					break;
			}
			repaint();
		}

		void flood(int x, int y, TilePalette from, TilePalette to) {
			if (from == to) {
				return;
			}
			java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
			q.add(new int[]{x, y});
			while (!q.isEmpty()) {
				int[] p = q.poll();
				int cx = p[0], cy = p[1];
				if (cx < 0 || cy < 0 || cx >= DIM || cy >= DIM || grid[cy][cx] != from) {
					continue;
				}
				grid[cy][cx] = to;
				q.add(new int[]{cx + 1, cy});
				q.add(new int[]{cx - 1, cy});
				q.add(new int[]{cx, cy + 1});
				q.add(new int[]{cx, cy - 1});
			}
		}

		/** A tiling paint for a terrain's texture (one repeat spans 2 cells), cached. */
		private java.awt.TexturePaint paintFor(TilePalette t) {
			if (terrainTex == null) {
				return null;
			}
			if (paintCache.containsKey(t)) {
				return paintCache.get(t);
			}
			java.awt.image.BufferedImage img = terrainTex.image(t);
			java.awt.TexturePaint p = img == null ? null
					: new java.awt.TexturePaint(img, new java.awt.Rectangle(0, 0, CELL * 2, CELL * 2));
			paintCache.put(t, p);
			return p;
		}

		@Override
		protected void paintComponent(Graphics g) {
			super.paintComponent(g);
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
			for (int ty = 0; ty < DIM; ty++) {
				for (int tx = 0; tx < DIM; tx++) {
					TilePalette t = grid[ty][tx];
					java.awt.TexturePaint tp = textured ? paintFor(t) : null;
					if (tp != null) {
						g2.setPaint(tp);
					} else {
						g2.setPaint(t == null ? Color.DARK_GRAY : t.color());
					}
					g2.fillRect(tx * CELL, ty * CELL, CELL, CELL);
				}
			}
			// elevation overlay: higher tiles get a brighter wash + a level number
			for (int ty = 0; ty < DIM; ty++) {
				for (int tx = 0; tx < DIM; tx++) {
					int h = height[ty][tx];
					if (h <= 0) {
						continue;
					}
					g2.setPaint(new Color(255, 255, 255, Math.min(150, 28 * h)));
					g2.fillRect(tx * CELL, ty * CELL, CELL, CELL);
				}
			}
			// grid lines: faint in textured view, clearer in paint view
			g.setColor(new Color(0, 0, 0, textured ? 22 : 40));
			for (int i = 0; i <= DIM; i++) {
				g.drawLine(i * CELL, 0, i * CELL, DIM * CELL);
				g.drawLine(0, i * CELL, DIM * CELL, i * CELL);
			}
			// level numbers on raised tiles
			g.setFont(getFont().deriveFont(9f));
			for (int ty = 0; ty < DIM; ty++) {
				for (int tx = 0; tx < DIM; tx++) {
					int h = height[ty][tx];
					if (h > 0) {
						g.setColor(Color.BLACK);
						g.drawString(String.valueOf(h), tx * CELL + 4, ty * CELL + 11);
					}
				}
			}
		}
	}
}
