package ctrmap.humaninterface;

import static ctrmap.CtrmapMainframe.*;
import ctrmap.Workspace;
import ctrmap.formats.containers.GR;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.BuildingCatalog;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TerrainLighting;
import ctrmap.formats.tilemap.TilePalette;
import ctrmap.humaninterface.TilePainterForm.Placed;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JToggleButton;

/**
 * The MAP PAINTER as a World Editor tool: brushes, elevation, ramps,
 * buildings, lighting and Apply live in this side form while painting happens
 * directly ON the main map view (the painted 40x40 cell is drawn as an
 * overlay over the zone at its real position). Replaces the old separate
 * painter dialog - one map surface, one zoom, one selector, one undo model.
 */
public class PaintForm extends JPanel {

	static final int DIM = PaintedRegionBuilder.DIM;

	// ---- the painter document (seeded per zone) ---------------------------
	int seededZone = -1;
	TilePalette[][] grid = new TilePalette[DIM][DIM];
	int[][] height = new int[DIM][DIM];
	boolean[][] ramp = new boolean[DIM][DIM];
	final java.util.List<Placed> placed = new java.util.ArrayList<>();
	final TerrainLighting lighting = TerrainLighting.daytime();
	boolean edgeBlend = true;
	byte[] donorModel;
	int cellX, cellY; // the painted cell's position in the zone's world grid
	BuildingCatalog.Entry pendingPlace = null;
	int ptool = 0; // 0 paint, 1 fill, 2 raise, 3 lower, 4 ramp

	private final java.util.ArrayDeque<Object[]> undoStack = new java.util.ArrayDeque<>();
	private final java.util.ArrayDeque<Object[]> redoStack = new java.util.ArrayDeque<>();

	// ---- UI ---------------------------------------------------------------
	private final JLabel zoneLabel = new JLabel("No zone loaded");
	private final JLabel waterBanner = new JLabel();
	private final JButton makeRipple = new JButton("Make water ripple here");
	private final JButton undoBtn = new JButton("Undo");
	private final JButton redoBtn = new JButton("Redo");
	private final javax.swing.JList<TilePalette> brushList = new javax.swing.JList<>(TilePalette.brushes());
	private final JLabel placeStatus = new JLabel(" ");
	private final JCheckBox edgeChk = new JCheckBox("Blend grass edges (GameFreak look)", true);
	private final JSlider bright = new JSlider(30, 130, 100);
	private final JSlider shadow = new JSlider(0, 90, 35);

	public PaintForm() {
		setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		JLabel head = new JLabel("Map Painter");
		head.setFont(head.getFont().deriveFont(java.awt.Font.BOLD, 14f));
		add(left(head));
		add(left(zoneLabel));
		add(gap(6));

		JPanel undoRow = new JPanel(new GridLayout(1, 2, 4, 0));
		undoRow.add(undoBtn);
		undoRow.add(redoBtn);
		fixRow(undoRow, 26);
		add(undoRow);
		undoBtn.setEnabled(false);
		redoBtn.setEnabled(false);
		undoBtn.addActionListener(e -> doUndo());
		redoBtn.addActionListener(e -> doRedo());
		add(gap(8));

		waterBanner.setOpaque(true);
		waterBanner.setForeground(new Color(0x30, 0x30, 0x30));
		add(left(waterBanner));
		makeRipple.setAlignmentX(0f);
		makeRipple.addActionListener(e -> rippleAction());
		add(makeRipple);
		add(gap(8));

		add(left(new JLabel("Brush (click the map to paint):")));
		brushList.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
			@Override
			public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
					int index, boolean isSelected, boolean cellHasFocus) {
				JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				TilePalette t = (TilePalette) value;
				l.setText(t.label);
				l.setIcon(new javax.swing.Icon() {
					@Override
					public void paintIcon(java.awt.Component c, Graphics g, int x, int y) {
						g.setColor(t.color());
						g.fillRect(x, y, 13, 13);
						g.setColor(Color.DARK_GRAY);
						g.drawRect(x, y, 13, 13);
					}

					@Override
					public int getIconWidth() {
						return 15;
					}

					@Override
					public int getIconHeight() {
						return 14;
					}
				});
				return l;
			}
		});
		brushList.setSelectedIndex(0);
		brushList.setVisibleRowCount(8);
		javax.swing.JScrollPane bsp = new javax.swing.JScrollPane(brushList);
		bsp.setAlignmentX(0f);
		bsp.setMaximumSize(new Dimension(260, 150));
		bsp.setPreferredSize(new Dimension(240, 150));
		add(bsp);
		add(gap(6));

		JButton buildings = new JButton("Buildings & decor");
		buildings.setAlignmentX(0f);
		buildings.setToolTipText("Pokemon Centers, Marts, houses, signs, trees - pick one, then click the map to place it. Right-click a placed one to remove it.");
		buildings.addActionListener(e -> {
			BuildingCatalog.Entry pick = BuildingPaletteDialog.pick(null, donorModel, mTileMapPanel.getWorldTextures());
			if (pick != null) {
				pendingPlace = pick;
				placeStatus.setText("<html><b>Placing: " + pick.name + "</b> - click the map</html>");
			}
		});
		add(buildings);
		add(left(placeStatus));
		add(gap(8));

		add(left(new JLabel("Tool:")));
		ButtonGroup tg = new ButtonGroup();
		String[] tools = {"Paint (drag)", "Fill area", "Raise (+level)", "Lower (-level)", "Ramp (walkable slope)"};
		for (int i = 0; i < tools.length; i++) {
			final int ti = i;
			JToggleButton b = new JToggleButton(tools[i]);
			b.setAlignmentX(0f);
			b.setFocusable(false);
			b.addActionListener(e -> ptool = ti);
			if (i == 0) {
				b.setSelected(true);
			}
			tg.add(b);
			add(b);
		}
		JButton fillAll = new JButton("Fill all with brush");
		fillAll.setAlignmentX(0f);
		fillAll.addActionListener(e -> {
			snapshot();
			for (TilePalette[] row : grid) {
				java.util.Arrays.fill(row, brush());
			}
			repaintMap();
		});
		add(fillAll);
		add(gap(8));

		edgeChk.setAlignmentX(0f);
		edgeChk.addActionListener(e -> edgeBlend = edgeChk.isSelected());
		add(edgeChk);

		JButton view3d = new JButton("3D preview (real render)");
		view3d.setAlignmentX(0f);
		view3d.setToolTipText("Render the painted map with the 3D engine - how it actually looks (drag to orbit).");
		view3d.addActionListener(e -> {
			if (donorModel != null) {
				TilePainterForm.open3DPreview(donorModel, grid, height, ramp, lighting, edgeBlend, placed);
			}
		});
		add(view3d);
		add(gap(8));

		add(left(new JLabel("Lighting (baked into the map):")));
		JPanel presets = new JPanel(new GridLayout(1, 4, 2, 0));
		fixRow(presets, 24);
		preset(presets, "Day", 1.0f, 0xFFF6E6, 0.35f);
		preset(presets, "Dusk", 0.85f, 0xFFC98A, 0.45f);
		preset(presets, "Cave", 0.55f, 0x9FB0C8, 0.6f);
		preset(presets, "Night", 0.45f, 0x8090C0, 0.5f);
		add(presets);
		add(left(new JLabel("  brightness")));
		fixRow(bright, 22);
		bright.addChangeListener(e -> lighting.brightness = bright.getValue() / 100f);
		add(bright);
		add(left(new JLabel("  edge shadow")));
		fixRow(shadow, 22);
		shadow.addChangeListener(e -> lighting.edgeShadow = shadow.getValue() / 100f);
		add(shadow);
		JButton tint = new JButton("Light color");
		tint.setAlignmentX(0f);
		tint.addActionListener(e -> {
			Color c = javax.swing.JColorChooser.showDialog(this, "Light color", lighting.tintColor());
			if (c != null) {
				lighting.tint = c.getRGB() & 0xFFFFFF;
			}
		});
		add(tint);
		add(gap(10));

		JButton apply = new JButton("Apply to zone");
		apply.setAlignmentX(0f);
		apply.setFont(apply.getFont().deriveFont(java.awt.Font.BOLD));
		apply.addActionListener(e -> applyAction());
		add(apply);
		add(left(new JLabel("<html><small>Right-click: remove a placed building /<br>clear a ramp. Apply builds the real map,<br>then Deploy to walk on it.</small></html>")));
		setPreferredSize(new Dimension(250, 760));
	}

	private JComponentHolder holderUnused; // (no-op field to keep the form simple)

	private static class JComponentHolder {
	}

	private javax.swing.JComponent left(javax.swing.JComponent c) {
		c.setAlignmentX(0f);
		return c;
	}

	private javax.swing.JComponent gap(int h) {
		javax.swing.JComponent c = (javax.swing.JComponent) javax.swing.Box.createVerticalStrut(h);
		return c;
	}

	private void fixRow(javax.swing.JComponent c, int h) {
		c.setAlignmentX(0f);
		c.setMaximumSize(new Dimension(260, h));
	}

	private void preset(JPanel p, String label, float b, int tintRgb, float sh) {
		JButton btn = new JButton(label);
		btn.setMargin(new java.awt.Insets(1, 2, 1, 2));
		btn.setFocusable(false);
		btn.addActionListener(e -> {
			lighting.brightness = b;
			lighting.tint = tintRgb;
			lighting.edgeShadow = sh;
			bright.setValue(Math.round(b * 100));
			shadow.setValue(Math.round(sh * 100));
		});
		p.add(btn);
	}

	TilePalette brush() {
		TilePalette t = brushList.getSelectedValue();
		return t != null ? t : TilePalette.GRASS;
	}

	// ---- activation / seeding ---------------------------------------------

	/** Called when the Painter tool is selected. Seeds from the loaded zone. */
	public void activate() {
		if (!Workspace.valid || !Workspace.isOA() || mZonePnl == null || mZonePnl.zone == null || mZonePnl.zoneIndex < 0) {
			zoneLabel.setText("Load a zone first (Zone Loader tab)");
			seededZone = -1;
			return;
		}
		if (seededZone != mZonePnl.zoneIndex) {
			seed();
		}
		syncWater();
	}

	public void deactivate() {
		pendingPlace = null;
		placeStatus.setText(" ");
	}

	void seed() {
		seededZone = mZonePnl.zoneIndex;
		zoneLabel.setText("Painting zone " + seededZone);
		for (TilePalette[] row : grid) {
			java.util.Arrays.fill(row, TilePalette.GRASS);
		}
		for (int i = 0; i < DIM; i++) {
			java.util.Arrays.fill(height[i], 0);
			java.util.Arrays.fill(ramp[i], false);
		}
		placed.clear();
		undoStack.clear();
		redoStack.clear();
		undoBtn.setEnabled(false);
		redoBtn.setEnabled(false);
		pendingPlace = null;
		placeStatus.setText(" ");
		int[] cell = TilePainterForm.firstRegionCell();
		cellX = cell != null ? cell[1] : 0;
		cellY = cell != null ? cell[2] : 0;
		int region = cell != null ? cell[0] : -1;
		donorModel = null;
		if (region >= 0) {
			TilePainterForm.loadFromRegion(region, grid);
			try {
				byte[] m = new GR(Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, region)).getFile(1);
				if (BchMapModel.isMapModel(m)) {
					donorModel = m;
				}
			} catch (Exception ignore) {
			}
		}
		boolean canEdge = donorModel != null && PaintedRegionBuilder.donorSupportsEdges(donorModel);
		edgeChk.setEnabled(canEdge);
		edgeChk.setSelected(canEdge);
		edgeBlend = canEdge;
	}

	private void syncWater() {
		if (seededZone < 0) {
			return;
		}
		boolean ripples = TilePainterForm.zoneWaterScrolls(mZonePnl.zone.header.areadataID);
		waterBanner.setText(ripples
				? "<html>💧 Water ripples in this zone -<br>paint water freely.</html>"
				: "<html>💧 Water here would be STILL -<br>use the button to fix that.</html>");
		waterBanner.setBackground(ripples ? new Color(0xD6, 0xEF, 0xD6) : new Color(0xFF, 0xE9, 0xC2));
		makeRipple.setVisible(!ripples);
		revalidate();
	}

	private void rippleAction() {
		if (seededZone < 0) {
			return;
		}
		int areaId = mZonePnl.zone.header.areadataID;
		int rsl = JOptionPane.showConfirmDialog(this,
				"Add GameFreak's sea-scroll animation for this zone's map cells to area " + areaId + "?\n\n"
				+ "This is the exact animation retail water routes use. It's bound by map-cell\n"
				+ "name: any map sharing this zone's exact cells gains it too; the area's other\n"
				+ "maps are untouched. Safe to do before or after painting water.",
				"Make water ripple", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (rsl != JOptionPane.OK_OPTION) {
			return;
		}
		try {
			int changed = TilePainterForm.enableWaterScroll(areaId);
			syncWater();
			JOptionPane.showMessageDialog(this, changed > 0
					? "Sea-scroll animation added for " + changed + " map cell(s)."
					: "No map cells needed changes (the scroll was already bound).",
					"Make water ripple", JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this, "Could not add the animation:\n" + ex.getMessage(),
					"Make water ripple", JOptionPane.ERROR_MESSAGE);
		}
	}

	// ---- gestures from the PaintTool (map coords -> local cell coords) -----

	private int localX(int gx) {
		return gx - cellX * 40;
	}

	private int localY(int gy) {
		return gy - cellY * 40;
	}

	private boolean inCell(int lx, int ly) {
		return lx >= 0 && ly >= 0 && lx < DIM && ly < DIM;
	}

	void snapshot() {
		Object[] s = copyState();
		undoStack.push(s);
		while (undoStack.size() > 100) {
			undoStack.removeLast();
		}
		redoStack.clear();
		undoBtn.setEnabled(true);
		redoBtn.setEnabled(false);
	}

	private Object[] copyState() {
		TilePalette[][] g2 = new TilePalette[DIM][];
		int[][] h2 = new int[DIM][];
		boolean[][] r2 = new boolean[DIM][];
		for (int i = 0; i < DIM; i++) {
			g2[i] = grid[i].clone();
			h2[i] = height[i].clone();
			r2[i] = ramp[i].clone();
		}
		return new Object[]{g2, h2, r2, new java.util.ArrayList<>(placed)};
	}

	@SuppressWarnings("unchecked")
	private void restoreState(Object[] s) {
		TilePalette[][] g2 = (TilePalette[][]) s[0];
		int[][] h2 = (int[][]) s[1];
		boolean[][] r2 = (boolean[][]) s[2];
		for (int i = 0; i < DIM; i++) {
			System.arraycopy(g2[i], 0, grid[i], 0, DIM);
			System.arraycopy(h2[i], 0, height[i], 0, DIM);
			System.arraycopy(r2[i], 0, ramp[i], 0, DIM);
		}
		placed.clear();
		placed.addAll((java.util.List<Placed>) s[3]);
		repaintMap();
	}

	private void doUndo() {
		if (!undoStack.isEmpty()) {
			redoStack.push(copyState());
			restoreState(undoStack.pop());
			undoBtn.setEnabled(!undoStack.isEmpty());
			redoBtn.setEnabled(true);
		}
	}

	private void doRedo() {
		if (!redoStack.isEmpty()) {
			undoStack.push(copyState());
			restoreState(redoStack.pop());
			redoBtn.setEnabled(!redoStack.isEmpty());
			undoBtn.setEnabled(true);
		}
	}

	/** Mouse-down on the map (global tile coords). */
	public void gesturePress(int gx, int gy, boolean right) {
		if (seededZone < 0) {
			return;
		}
		int lx = localX(gx), ly = localY(gy);
		if (!inCell(lx, ly)) {
			return;
		}
		// building placement mode
		if (pendingPlace != null) {
			if (!right) {
				snapshot();
				int px = Math.max(0, Math.min(DIM - pendingPlace.tilesW(), lx));
				int py = Math.max(0, Math.min(DIM - pendingPlace.tilesH(), ly));
				placed.add(new Placed(pendingPlace, px, py));
			}
			pendingPlace = null;
			placeStatus.setText(" ");
			repaintMap();
			return;
		}
		// right-click on a placed building removes it (ramp tool keeps its own
		// right-click meaning: clearing ramp flags)
		if (right && ptool != 4) {
			for (int i = placed.size() - 1; i >= 0; i--) {
				if (placed.get(i).contains(lx, ly)) {
					if (JOptionPane.showConfirmDialog(this, "Remove \"" + placed.get(i).e.name + "\"?",
							"Buildings", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
						snapshot();
						placed.remove(i);
						repaintMap();
					}
					return;
				}
			}
			return;
		}
		snapshot();
		applyToolAt(lx, ly, right);
		repaintMap();
	}

	/** Mouse-drag on the map (paint tool only; continues the open gesture). */
	public void gestureDrag(int gx, int gy, boolean right) {
		if (seededZone < 0 || ptool != 0 || right) {
			return;
		}
		int lx = localX(gx), ly = localY(gy);
		if (inCell(lx, ly) && grid[ly][lx] != brush()) {
			grid[ly][lx] = brush();
			repaintMap();
		}
	}

	private void applyToolAt(int lx, int ly, boolean right) {
		switch (ptool) {
			case 1:
				flood(lx, ly, grid[ly][lx], brush());
				break;
			case 2:
				height[ly][lx] = Math.min(6, height[ly][lx] + 1);
				break;
			case 3:
				height[ly][lx] = Math.max(0, height[ly][lx] - 1);
				break;
			case 4:
				ramp[ly][lx] = !right;
				break;
			default:
				grid[ly][lx] = brush();
				break;
		}
	}

	private void flood(int x, int y, TilePalette from, TilePalette to) {
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

	public void cancelPending() {
		pendingPlace = null;
		placeStatus.setText(" ");
	}

	private void repaintMap() {
		mTileMapPanel.firePropertyChange(TileMapPanel.PROP_REPAINT, false, true);
	}

	// ---- the overlay on the main map view ---------------------------------

	/** Draws the painted cell over the map (called from the PaintTool). */
	public void drawOverlay(Graphics g, int sx, int sy, double d) {
		if (seededZone < 0 || seededZone != mZonePnl.zoneIndex) {
			return;
		}
		int ax = cellX * 40, ay = cellY * 40;
		for (int ly = 0; ly < DIM; ly++) {
			for (int lx = 0; lx < DIM; lx++) {
				TilePalette t = grid[ly][lx];
				Color c = t == null ? Color.DARK_GRAY : t.color();
				g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 205));
				int px = sx + (int) Math.round((ax + lx) * d);
				int py = sy + (int) Math.round((ay + ly) * d);
				int px2 = sx + (int) Math.round((ax + lx + 1) * d);
				int py2 = sy + (int) Math.round((ay + ly + 1) * d);
				g.fillRect(px, py, px2 - px, py2 - py);
			}
		}
		// elevation wash + numbers, ramp markers
		for (int ly = 0; ly < DIM; ly++) {
			for (int lx = 0; lx < DIM; lx++) {
				int h = height[ly][lx];
				int px = sx + (int) Math.round((ax + lx) * d);
				int py = sy + (int) Math.round((ay + ly) * d);
				int cw = (int) Math.round(d);
				if (h > 0) {
					g.setColor(new Color(255, 255, 255, Math.min(150, 28 * h)));
					g.fillRect(px, py, cw, cw);
					if (d >= 9) {
						g.setColor(Color.BLACK);
						g.drawString(String.valueOf(h), px + 2, py + Math.min(11, cw - 1));
					}
				}
				if (ramp[ly][lx] && d >= 6) {
					g.setColor(new Color(255, 210, 40));
					g.fillPolygon(new int[]{px + 1, px + cw - 1, px + cw / 2},
							new int[]{py + cw - 2, py + cw - 2, py + 2}, 3);
				}
			}
		}
		// building footprints
		for (Placed p : placed) {
			int px = sx + (int) Math.round((ax + p.tx) * d);
			int py = sy + (int) Math.round((ay + p.ty) * d);
			int pw = (int) Math.round(p.e.tilesW() * d);
			int ph = (int) Math.round(p.e.tilesH() * d);
			g.setColor(new Color(70, 60, 160, 80));
			g.fillRect(px, py, pw, ph);
			g.setColor(new Color(60, 50, 140));
			g.drawRect(px, py, pw - 1, ph - 1);
			if (d >= 5) {
				g.setColor(Color.WHITE);
				g.drawString(p.e.name, px + 3, py + 12);
			}
			if (p.e.doorDX >= 0) {
				int dx = sx + (int) Math.round((ax + p.tx + p.e.doorDX) * d);
				int dy2 = sy + (int) Math.round((ay + p.ty + p.e.doorDY) * d);
				g.setColor(new Color(255, 140, 40));
				g.fillRect(dx + 2, dy2 + 2, Math.max(2, (int) d - 4), Math.max(2, (int) d - 4));
			}
		}
		// frame around the painted cell
		g.setColor(new Color(30, 30, 200));
		g.drawRect(sx + (int) Math.round(ax * d), sy + (int) Math.round(ay * d),
				(int) Math.round(DIM * d), (int) Math.round(DIM * d));
	}

	// ---- apply ------------------------------------------------------------

	private void applyAction() {
		if (seededZone < 0) {
			return;
		}
		final int zoneIndex = seededZone;
		String waterNote = "";
		if (TilePainterForm.usesWater(grid) && !TilePainterForm.zoneWaterScrolls(mZonePnl.zone.header.areadataID)) {
			waterNote = "\n\nNote: painted water will be STILL here - use \"Make water ripple here\" first.";
		}
		int rsl = JOptionPane.showConfirmDialog(this,
				"Build zone " + zoneIndex + "'s map from the painted tiles?\n"
				+ "The zone gets its own private geometry first (the source is untouched),\n"
				+ "then packs. Deploy to walk on it." + waterNote,
				"Map Painter", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (rsl != JOptionPane.OK_OPTION) {
			return;
		}
		//the apply reads/forks the last-SAVED workspace bytes - flush pending edits
		if (!(mCamEditForm.store(true) && mTileMapPanel.saveTileMap(true) && mMtxEditForm.store(true)
				&& mPropEditForm.store(true) && mNPCEditForm.saveRegistry(true) && mZonePnl.store(true))) {
			return;
		}
		try {
			TilePainterForm.applyToZone(zoneIndex, grid, height, ramp, lighting, edgeBlend, placed);
			seededZone = -1; // reseed from the freshly built zone on next activate
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this, "Apply failed:\n" + ex.getMessage(), "Map Painter", JOptionPane.ERROR_MESSAGE);
		}
	}
}
