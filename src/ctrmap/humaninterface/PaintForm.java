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
	/** Per-tile way DOWN a ramp (0 E, 1 W, 2 S, 3 N), or NO_RAMP. */
	int[][] ramp = PaintedRegionBuilder.noRamps();
	/** Which tiles the user actually edited: ONLY these are rebuilt on Apply -
	 *  everything else keeps the zone's existing geometry (walls, fountains). */
	boolean[][] touched = new boolean[DIM][DIM];
	final java.util.List<Placed> placed = new java.util.ArrayList<>();
	final TerrainLighting lighting = TerrainLighting.daytime();
	boolean edgeBlend = true;
	byte[] donorModel;
	byte[] donorColl; // the region's collision - the retail-height frame for composite builds
	int cellX, cellY; // the painted cell's position in the zone's world grid
	BuildingCatalog.Entry pendingPlace = null;
	int ptool = 0; // 0 paint, 1 fill, 2 raise, 3 lower, 4 ramp

	private final java.util.ArrayDeque<Object[]> undoStack = new java.util.ArrayDeque<>();
	private final java.util.ArrayDeque<Object[]> redoStack = new java.util.ArrayDeque<>();

	// ---- live 3D preview state (the painted cell is swapped into the main
	// map scene, so the F3 3D view and the 2D view's GL composite show edits
	// as they happen - no separate preview window) ---------------------------
	private boolean toolActive = false;
	private boolean previewInScene = false;
	private byte[] originalModel; // the region's real bytes, restored on tool exit
	private boolean regenRunning = false, regenQueued = false;
	/** Bumped on Apply so an in-flight regen can never re-arm the preview
	 *  across the pack/reload boundary. */
	private int regenEpoch = 0;
	private final javax.swing.Timer regenTimer = new javax.swing.Timer(200, e -> startRegen());

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
		fillAll.setToolTipText("Paints EVERY tile of the cell - this also rebuilds the whole cell on Apply (existing walls/details are replaced).");
		fillAll.setAlignmentX(0f);
		fillAll.addActionListener(e -> {
			snapshot();
			for (int i = 0; i < DIM; i++) {
				java.util.Arrays.fill(grid[i], brush());
				java.util.Arrays.fill(touched[i], true);
			}
			repaintMap();
		});
		add(fillAll);
		add(gap(8));

		edgeChk.setAlignmentX(0f);
		edgeChk.addActionListener(e -> {
			edgeBlend = edgeChk.isSelected();
			repaintMap();
		});
		add(edgeChk);

		//the 2D/3D switch lives on the main toolbar now (always visible, every
		//tool); painting updates the 3D scene live either way

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
		bright.addChangeListener(e -> {
			lighting.brightness = bright.getValue() / 100f;
			if (!bright.getValueIsAdjusting()) {
				schedule3DRegen();
			}
		});
		add(bright);
		add(left(new JLabel("  edge shadow")));
		fixRow(shadow, 22);
		shadow.addChangeListener(e -> {
			lighting.edgeShadow = shadow.getValue() / 100f;
			if (!shadow.getValueIsAdjusting()) {
				schedule3DRegen();
			}
		});
		add(shadow);
		JButton tint = new JButton("Light color");
		tint.setAlignmentX(0f);
		tint.addActionListener(e -> {
			Color c = javax.swing.JColorChooser.showDialog(this, "Light color", lighting.tintColor());
			if (c != null) {
				lighting.tint = c.getRGB() & 0xFFFFFF;
				schedule3DRegen();
			}
		});
		add(tint);
		add(gap(10));

		JButton apply = new JButton("Apply to zone");
		apply.setAlignmentX(0f);
		apply.setFont(apply.getFont().deriveFont(java.awt.Font.BOLD));
		apply.addActionListener(e -> applyAction());
		add(apply);
		add(left(new JLabel("<html><small>Only tiles you touch are rebuilt - the rest<br>of the map keeps its existing look.<br>Ramp: the arrow points down the slope;<br>click again to turn it, right-click to clear.<br>Right-click a placed building to remove it.<br>Apply writes the real map, then Deploy<br>to walk on it.</small></html>")));
		setPreferredSize(new Dimension(250, 780));
		regenTimer.setRepeats(false);
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
			schedule3DRegen();
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
		toolActive = true;
		if (!Workspace.valid || !Workspace.isOA() || mZonePnl == null || mZonePnl.zone == null || mZonePnl.zoneIndex < 0) {
			zoneLabel.setText("Load a zone first (Zone Loader tab)");
			seededZone = -1;
			return;
		}
		if (seededZone != mZonePnl.zoneIndex || regionChangedUnderneath()) {
			seed();
		}
		syncWater();
		schedule3DRegen();
	}

	/** True when another editor (geometry tool, OBJ import, resize) rewrote the
	 *  seeded region since we seeded - the painter document is then stale. */
	private boolean regionChangedUnderneath() {
		if (originalModel == null) {
			return false;
		}
		try {
			int[] cell = TilePainterForm.firstRegionCell();
			if (cell == null) {
				return true;
			}
			byte[] m = new GR(Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, cell[0])).getFile(1);
			return !java.util.Arrays.equals(m, originalModel);
		} catch (Exception ex) {
			return true;
		}
	}

	public void deactivate() {
		toolActive = false;
		pendingPlace = null;
		placeStatus.setText(" ");
		regenTimer.stop();
		restoreRealModel();
	}

	void seed() {
		seededZone = mZonePnl.zoneIndex;
		zoneLabel.setText("Painting zone " + seededZone);
		//a zone switch rebuilds the whole scene, so any old preview swap is gone
		previewInScene = false;
		originalModel = null;
		for (TilePalette[] row : grid) {
			java.util.Arrays.fill(row, TilePalette.GRASS);
		}
		for (int i = 0; i < DIM; i++) {
			java.util.Arrays.fill(height[i], 0);
			java.util.Arrays.fill(ramp[i], PaintedRegionBuilder.NO_RAMP);
			java.util.Arrays.fill(touched[i], false);
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
		donorColl = null;
		if (region >= 0) {
			TilePainterForm.loadFromRegion(region, grid);
			try {
				GR gr = new GR(Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, region));
				byte[] m = gr.getFile(1);
				if (BchMapModel.isMapModel(m)) {
					donorModel = m;
					donorColl = gr.getFile(2);
					//elevations start at the map's REAL ground levels, so painted
					//tiles sit level with their retail surroundings by default
					int borrowed = PaintedRegionBuilder.seedHeightsFromCollision(donorColl, height);
					if (borrowed > 0) {
						zoneLabel.setText("<html>Painting zone " + seededZone + "<br><small>" + borrowed
								+ " tile(s) have no ground of their own and start<br>level with their nearest neighbour</small></html>");
					}
				}
			} catch (Exception ignore) {
			}
		}
		originalModel = donorModel;
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
		//animations live in the AREA - fork a shared one first so the ripple
		//cannot reach other zones (this is what made the water splice a
		//game-wide edit before)
		int areaId = AreaForkPrompt.ensurePrivate(this, mZonePnl.zoneIndex,
				mZonePnl.zone.header.areadataID, "adding the water animation");
		if (areaId < 0) {
			return;
		}
		int rsl = JOptionPane.showConfirmDialog(this,
				"Add GameFreak's sea-scroll animation for this zone's map cells?\n\n"
				+ "This is the exact animation retail water routes use. Safe to do\n"
				+ "before or after painting water.",
				"Make water ripple", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (rsl != JOptionPane.OK_OPTION) {
			return;
		}
		try {
			int changed = TilePainterForm.enableWaterScroll(areaId);
			syncWater();
			AreaForkPrompt.packIfForked(null);
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
		int[][] r2 = new int[DIM][];
		boolean[][] t2 = new boolean[DIM][];
		for (int i = 0; i < DIM; i++) {
			g2[i] = grid[i].clone();
			h2[i] = height[i].clone();
			r2[i] = ramp[i].clone();
			t2[i] = touched[i].clone();
		}
		return new Object[]{g2, h2, r2, new java.util.ArrayList<>(placed), t2};
	}

	@SuppressWarnings("unchecked")
	private void restoreState(Object[] s) {
		TilePalette[][] g2 = (TilePalette[][]) s[0];
		int[][] h2 = (int[][]) s[1];
		int[][] r2 = (int[][]) s[2];
		boolean[][] t2 = (boolean[][]) s[4];
		for (int i = 0; i < DIM; i++) {
			System.arraycopy(g2[i], 0, grid[i], 0, DIM);
			System.arraycopy(h2[i], 0, height[i], 0, DIM);
			System.arraycopy(r2[i], 0, ramp[i], 0, DIM);
			System.arraycopy(t2[i], 0, touched[i], 0, DIM);
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
		//the seeded document must match the DISPLAYED zone - a stroke accepted
		//against a stale document would silently mark tiles of the wrong map
		if (seededZone < 0 || mZonePnl == null || seededZone != mZonePnl.zoneIndex) {
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
				touchFootprint(px, py, pendingPlace.tilesW(), pendingPlace.tilesH());
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
						Placed rem = placed.remove(i);
						//mark its footprint edited so an earlier Apply's stamped
						//geometry there is cleared on the next Apply
						touchFootprint(rem.tx, rem.ty, rem.e.tilesW(), rem.e.tilesH());
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
		if (seededZone < 0 || mZonePnl == null || seededZone != mZonePnl.zoneIndex || ptool != 0 || right) {
			return;
		}
		int lx = localX(gx), ly = localY(gy);
		if (inCell(lx, ly) && (grid[ly][lx] != brush() || !touched[ly][lx])) {
			grid[ly][lx] = brush();
			touched[ly][lx] = true;
			settleRamps();
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
				ramp[ly][lx] = right ? PaintedRegionBuilder.NO_RAMP : turnRamp(lx, ly);
				break;
			default:
				grid[ly][lx] = brush();
				break;
		}
		touched[ly][lx] = true;
		settleRamps();
	}

	/**
	 * The ramp tool's click: a first click takes the way down that the level
	 * gradient suggests, and each click after turns the ramp to the next side
	 * that is lower, so a corner tile can be sent whichever way the route
	 * goes. A tile with nothing lower beside it cannot be a ramp, and says so.
	 */
	private int turnRamp(int lx, int ly) {
		int cur = ramp[ly][lx];
		if (cur == PaintedRegionBuilder.NO_RAMP) {
			int d = PaintedRegionBuilder.steepestDescent(grid, height, lx, ly);
			placeStatus.setText(d < 0 ? "<html>No lower ground beside that tile -<br>raise it or lower a neighbour first.</html>" : " ");
			return d;
		}
		for (int k = 1; k < 4; k++) {
			int d = (cur + k) % 4;
			if (PaintedRegionBuilder.descends(grid, height, lx, ly, d)) {
				return d;
			}
		}
		return cur;
	}

	/**
	 * After any edit: a ramp whose way down is no longer lower turns to the
	 * gradient, or goes when nothing lower is left. Levels change under the
	 * raise and lower tools, but so does the ground a ramp descends to when
	 * a void tile beside it is painted over - void counts as the base level -
	 * so every ramp is settled after every stroke. The arrow on the map moves
	 * or vanishes with it, and the builder never sees a ramp that contradicts
	 * its heights.
	 */
	private void settleRamps() {
		for (int y = 0; y < DIM; y++) {
			for (int x = 0; x < DIM; x++) {
				if (ramp[y][x] != PaintedRegionBuilder.NO_RAMP
						&& !PaintedRegionBuilder.descends(grid, height, x, y, ramp[y][x])) {
					ramp[y][x] = PaintedRegionBuilder.steepestDescent(grid, height, x, y);
				}
			}
		}
	}

	private void touchFootprint(int tx, int ty, int w, int h) {
		for (int y = ty; y < ty + h && y < DIM; y++) {
			for (int x = tx; x < tx + w && x < DIM; x++) {
				if (x >= 0 && y >= 0) {
					touched[y][x] = true;
				}
			}
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
			touched[cy][cx] = true;
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
		schedule3DRegen();
	}

	// ---- live 3D regeneration ----------------------------------------------

	/** Debounced: regenerates the painted cell's model ~200ms after the last
	 *  edit and swaps it into the main scene (2D GL composite + F3 3D view). */
	private void schedule3DRegen() {
		if (seededZone < 0 || donorModel == null || !toolActive) {
			return;
		}
		regenTimer.restart();
	}

	/** One regen result: the stamped model plus its extra donor textures. */
	private static final class RegenResult {

		final byte[] model;
		final java.util.List<ctrmap.formats.h3d.texturing.H3DTexture> extraTextures;

		RegenResult(byte[] model, java.util.List<ctrmap.formats.h3d.texturing.H3DTexture> extra) {
			this.model = model;
			this.extraTextures = extra;
		}
	}

	private void startRegen() {
		if (seededZone < 0 || donorModel == null || !toolActive
				|| mZonePnl == null || seededZone != mZonePnl.zoneIndex) {
			return;
		}
		if (regenRunning) {
			regenQueued = true;
			return;
		}
		regenRunning = true;
		final int zoneAtStart = seededZone;
		final int epochAtStart = regenEpoch;
		final byte[] donor = donorModel;
		final byte[] coll = donorColl;
		final boolean edgesNow = edgeBlend;
		//deep-copy every input on the EDT so the worker never races a stroke
		final TilePalette[][] g2 = new TilePalette[DIM][];
		final int[][] h2 = new int[DIM][];
		final int[][] r2 = new int[DIM][];
		final boolean[][] t2 = new boolean[DIM][];
		for (int i = 0; i < DIM; i++) {
			g2[i] = grid[i].clone();
			h2[i] = height[i].clone();
			r2[i] = ramp[i].clone();
			t2[i] = touched[i].clone();
		}
		final java.util.List<Placed> p2 = new java.util.ArrayList<>(placed);
		final TerrainLighting l2 = new TerrainLighting(lighting.brightness, lighting.tint, lighting.edgeShadow);
		javax.swing.SwingWorker<RegenResult, Void> worker = new javax.swing.SwingWorker<RegenResult, Void>() {
			@Override
			protected RegenResult doInBackground() {
				try {
					//the live preview paints with the same imported materials the
					//Apply will use, so what you see is what you get
					byte[] src = TilePainterForm.importBrushMaterials(donor, g2, t2, null);
					byte[] model = PaintedRegionBuilder.buildModelOnly(src, coll, g2, h2, r2, t2, l2, edgesNow);
					java.util.List<ctrmap.formats.h3d.texturing.H3DTexture> extra = null;
					if (!p2.isEmpty()) {
						ctrmap.formats.h3d.RegionFactory.BlankContent bc = new ctrmap.formats.h3d.RegionFactory.BlankContent();
						bc.model = model;
						//throwaway carriers - only the stamped model is used
						java.util.List<float[]> one = new java.util.ArrayList<>();
						one.add(new float[]{0, 0, 0, 0, 0, PaintedRegionBuilder.TILE, PaintedRegionBuilder.TILE, 0, 0});
						bc.collision = ctrmap.formats.gfcollision.GfColl.build(one, null);
						bc.tilemap = new byte[6528];
						bc.tilemap[0] = (byte) DIM;
						bc.tilemap[2] = (byte) DIM;
						bc.props = new byte[]{0, 0, 0, 0};
						TilePainterForm.stampPlaced(bc, p2, h2, PaintedRegionBuilder.floorYGrid(coll, h2), null);
						model = bc.model;
						//donor-area textures load here, off the EDT (the loaders
						//are synchronized; a GARC decompress can take a moment)
						extra = new java.util.ArrayList<>();
						java.util.Set<Integer> areas = new java.util.LinkedHashSet<>();
						for (Placed pl : p2) {
							areas.add(pl.e.donorArea);
						}
						for (int area : areas) {
							try {
								extra.addAll(BuildingPaletteDialog.donorTextures(area));
							} catch (Exception ignore) {
							}
						}
					}
					return new RegenResult(model, extra);
				} catch (Exception ex) {
					return null; // keep the last good preview; painting goes on
				}
			}

			@Override
			protected void done() {
				regenRunning = false;
				try {
					RegenResult res = get();
					if (res != null && res.model != null && toolActive && mZonePnl != null
							&& zoneAtStart == seededZone && seededZone == mZonePnl.zoneIndex
							&& epochAtStart == regenEpoch && mZonePnl.zone != null) {
						mTileMapPanel.reloadRegionModel(cellX, cellY, res.model, res.extraTextures);
						previewInScene = true;
					}
				} catch (Exception ignore) {
				}
				if (regenQueued) {
					regenQueued = false;
					schedule3DRegen();
				}
			}
		};
		worker.execute();
	}

	/** Puts the region's real bytes back into the scene (tool exit). */
	private void restoreRealModel() {
		if (previewInScene && originalModel != null && mZonePnl != null
				&& mZonePnl.zone != null && seededZone == mZonePnl.zoneIndex) {
			mTileMapPanel.reloadRegionModel(cellX, cellY, originalModel);
		}
		previewInScene = false;
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
				//tiles you touched draw solid (they will be rebuilt); the rest
				//stays faint - that geometry is kept exactly as it is
				g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), touched[ly][lx] ? 205 : 60));
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
				//ramps AND stair brushes, pointing DOWN the slope at the lower
				//neighbour, so what the map will build is what the arrow says
				int rd = d >= 6 ? PaintedRegionBuilder.rampDir(grid, height, ramp, lx, ly) : -1;
				if (rd >= 0) {
					g.setColor(new Color(255, 210, 40));
					int cx = px + cw / 2, cy = py + cw / 2, r = cw / 2 - 1;
					int[] xs = rd == 0 ? new int[]{cx - r, cx - r, cx + r} : rd == 1 ? new int[]{cx + r, cx + r, cx - r}
							: new int[]{cx - r, cx + r, cx};
					int[] ys = rd == 0 || rd == 1 ? new int[]{cy - r, cy + r, cy}
							: rd == 2 ? new int[]{cy - r, cy - r, cy + r} : new int[]{cy + r, cy + r, cy - r};
					g.fillPolygon(xs, ys, 3);
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
		int touchedCount = 0;
		for (boolean[] row : touched) {
			for (boolean b : row) {
				if (b) {
					touchedCount++;
				}
			}
		}
		if (touchedCount == 0 && placed.isEmpty()) {
			JOptionPane.showMessageDialog(this, "Nothing to apply yet - paint some tiles or place a building first.",
					"Map Builder", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		final int zoneIndex = seededZone;
		String waterNote = "";
		if (TilePainterForm.usesWater(grid) && !TilePainterForm.zoneWaterScrolls(mZonePnl.zone.header.areadataID)) {
			waterNote = "\n\nNote: painted water will be STILL here - use \"Make water ripple here\" first.";
		}
		int rsl = JOptionPane.showConfirmDialog(this,
				"Apply your edits to zone " + zoneIndex + "'s map?\n"
				+ "The " + touchedCount + " tile(s) you touched are rebuilt; the rest of the map keeps\n"
				+ "its existing geometry. If the map is still shared with other zones, this\n"
				+ "zone gets its own private copy first. Deploy afterwards to walk on it." + waterNote,
				"Map Builder", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (rsl != JOptionPane.OK_OPTION) {
			return;
		}
		//the apply reads/forks the last-SAVED workspace bytes - flush pending edits
		if (!(mCamEditForm.store(true) && mTileMapPanel.saveTileMap(true) && mMtxEditForm.store(true)
				&& mPropEditForm.store(true) && mNPCEditForm.saveRegistry(true) && mZonePnl.store(true))) {
			return;
		}
		try {
			regenTimer.stop();
			regenEpoch++; // an in-flight regen must not re-arm across the apply
			TilePainterForm.applyToZone(zoneIndex, grid, height, ramp, lighting, edgeBlend, placed, touched);
			// only a SUCCESSFUL apply reaches here: the pack + reload rebuilds
			// the scene from the applied bytes, so the preview swap is gone
			previewInScene = false;
			seededZone = -1; // reseed from the freshly built zone on next activate
		} catch (Exception ex) {
			// the preview may still be swapped in - put the real map back. Apply
			// clears every precondition before it writes anything, so the
			// workspace really is untouched and the dialog can say so - it used
			// to read as "nothing happened" over a half-applied map.
			restoreRealModel();
			JOptionPane.showMessageDialog(this, "Apply failed:\n" + ex.getMessage()
					+ "\n\nNothing was written - the map is exactly as it was.",
					"Map Builder", JOptionPane.ERROR_MESSAGE);
		}
	}
}
