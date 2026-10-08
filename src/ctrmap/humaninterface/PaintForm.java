package ctrmap.humaninterface;

import ctrmap.AreaForker;
import ctrmap.LoadedZone;
import ctrmap.Workspace;
import ctrmap.formats.containers.GR;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.BuildingCatalog;
import ctrmap.formats.tilemap.PaintedGeometry;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TerrainLighting;
import ctrmap.formats.tilemap.TilePalette;
import ctrmap.formats.tilemap.Tilemap;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import ctrmap.MapPainter;
import ctrmap.MapPainter.Placed;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import ctrmap.formats.tilemap.PaintedHeights;
import ctrmap.formats.tilemap.PaintedMaterials;

/**
 * The MAP PAINTER as a World Editor tool: brushes, elevation, ramps,
 * buildings, lighting and Apply live in this side form while painting happens
 * directly ON the main map view (the painted 40x40 cell is drawn as an
 * overlay over the zone at its real position). Replaces the old separate
 * painter dialog - one map surface, one zoom, one selector, one undo model.
 */
public class PaintForm extends JPanel {

	static final int DIM = PaintedGeometry.DIM;

	// ---- the painter document (seeded per zone) ---------------------------
	int seededZone = -1;
	TilePalette[][] grid = new TilePalette[DIM][DIM];
	int[][] height = new int[DIM][DIM];
	/** Per-tile way DOWN a ramp (0 E, 1 W, 2 S, 3 N), or NO_RAMP. */
	int[][] ramp = PaintedHeights.noRamps();
	/** Which tiles the user actually edited: ONLY these are rebuilt on Apply -
	 *  everything else keeps the zone's existing geometry (walls, fountains). */
	boolean[][] touched = new boolean[DIM][DIM];
	final java.util.List<Placed> placed = new java.util.ArrayList<>();
	final TerrainLighting lighting = TerrainLighting.daytime();
	boolean edgeBlend = true;
	byte[] donorModel;
	byte[] donorColl; // the region's collision - the retail-height frame for composite builds
	byte[] donorTilemap; // the region's movement tiles - which ground is walkable, for filling unsampled tiles
	int cellX, cellY; // the painted cell's position in the zone's world grid
	BuildingCatalog.Entry pendingPlace = null;
	/** Whether the pending building brings its passengers (the palette's checkbox). */
	boolean pendingPassengers = true;
	int ptool = 0; // 0 paint, 1 fill, 2 raise, 3 lower, 4 ramp

	/**
	 * The zone's history this canvas is a document of (F209), handed in: a painted stroke is taken back
	 * by the same Undo as an NPC moved before it, in the order they were made. The painter kept two
	 * stacks of its own, so Ctrl+Z on the map took back a tile edit while the stroke stayed.
	 */
	private final UndoHistory history;

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
	private final JButton fillAll = new JButton("Fill all with brush");
	private final javax.swing.JList<TilePalette> brushList = new javax.swing.JList<>(TilePalette.brushes());
	private final JLabel placeStatus = new JLabel(" ");
	/** Under Apply: empty while the 3D preview is current, the reason when it is not (F211). */
	private final JLabel previewStatus = new JLabel(" ");
	private final JCheckBox edgeChk = new JCheckBox("Blend grass edges (GameFreak look)", true);
	private final JSlider bright = new JSlider(30, 130, 100);
	private final JSlider shadow = new JSlider(0, 90, 35);

	/** The zone owner this painter was handed: the open zone, its index, its area. */
	private final LoadedZone loadedZone;

	/** The zone list, handed in: applying a painted map rebuilds it and lands on the zone. */
	private final ZoneList zoneList;

	/** The editors that hold unsaved work, handed in: this class flushes them, it does not own them. */
	private final OpenEditors openEditors;

	/**
	 * The map view, handed in AS ITSELF.
	 *
	 * <p>A narrower interface was weighed and rejected for the reason
	 * {@code ToolHost.map()} already gives: this class uses enough of the panel
	 * that naming a capability per member would be a longer way of writing
	 * TileMapPanel. What matters for the standard is that it is HANDED one and
	 * could be handed a different one - or, as PaintFormGuardsTest does, none
	 * at all - rather than reaching into the main window for the only one that
	 * exists.
	 *
	 * <p>SO IT MAY BE NULL, and that is answered here ONCE rather than four
	 * times below: every member that touches it checks first, the way
	 * repaintMap() always did and the other three did not. The window always
	 * hands a real one, so no user reaches a null branch; what the branches buy
	 * is that this document is drivable with no window, and that a missing view
	 * is a skipped repaint rather than an exception thrown out of a path -
	 * applyFailed() - that owes the user a sentence.
	 */
	private final TileMapPanel map;

	public PaintForm(LoadedZone loadedZone, OpenEditors openEditors, ZoneList zoneList,
			TileMapPanel map, UndoHistory history) {
		this.map = map;
		if (history == null) {
			throw new IllegalArgumentException("PaintForm must be handed the zone's history its canvas is a document of");
		}
		this.history = history;
		//the canvas is one of the zone's documents from now on: it is watched, not recorded by hand
		history.watch(() -> java.util.Collections.<UndoHistory.Document<?>>singletonList(canvas()));
		if (openEditors == null) {
			throw new IllegalArgumentException("PaintForm must be handed the editors it flushes before it applies");
		}
		if (zoneList == null) {
			throw new IllegalArgumentException("PaintForm must be handed the zone list an apply rebuilds");
		}
		this.zoneList = zoneList;
		this.openEditors = openEditors;
		if (loadedZone == null) {
			throw new IllegalArgumentException("PaintForm must be handed a LoadedZone");
		}
		this.loadedZone = loadedZone;
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
		undoBtn.addActionListener(e -> history.act(this, true));
		redoBtn.addActionListener(e -> history.act(this, false));
		history.addListener(this::followHistory);
		followHistory();
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
			//no map view, no world textures: null is what the palette already reads
			//as "no base textures" (see its baseTextures check), and what
			//TileMapPanel.getWorldTextures() itself hands out for a zone whose
			//textures were cleared. The ternary, rather than a bare call, for the
			//reason given on the map field.
			BuildingPaletteDialog.Pick pick = BuildingPaletteDialog.pick(null, donorModel,
					map == null ? null : map.getWorldTextures());
			if (pick != null) {
				beginPlacing(pick.entry, pick.passengers);
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
		fillAll.setToolTipText("Paints EVERY tile of the cell - this also rebuilds the whole cell on Apply (existing walls/details are replaced).");
		fillAll.setAlignmentX(0f);
		fillAll.addActionListener(e -> {
			for (int i = 0; i < DIM; i++) {
				java.util.Arrays.fill(grid[i], brush());
				java.util.Arrays.fill(touched[i], true);
			}
			//void counts as the base level, so filling it in can leave a ramp
			//with nothing lower on its way down - and the next repaint would
			//throw out of the event thread over it
			settleRamps();
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
		add(left(previewStatus));
		add(left(new JLabel("<html><small>Only tiles you touch are rebuilt - the rest<br>of the map keeps its existing look.<br>Ramp: the arrow points down the slope;<br>click again to turn it, right-click to clear.<br>Right-click a placed building to remove it.<br>Apply writes the real map, then Deploy<br>to walk on it.</small></html>")));
		setPreferredSize(new Dimension(250, 780));
		regenTimer.setRepeats(false);
	}

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

	/**
	 * Called when the Painter tool is selected. Seeds from the loaded zone.
	 *
	 * <p>The painter is gated on {@link GameProfile.Feature#TILE_PAINTER} rather
	 * than on the game being ORAS. Both refusals are the same today - ORAS is
	 * the only game the tile brushes, elevation and edge strips were measured
	 * on - but the label now says which of the two reasons applies. It used to
	 * say "Load a zone first" to a user on X/Y who had loaded a zone, sending
	 * them to look for a fault in the zone loader.
	 */
	public void activate() {
		toolActive = true;
		if (!Workspace.isValid()) {
			zoneLabel.setText("Load a zone first (Zone Loader tab)");
			seededZone = -1;
			return;
		}
		if (!Workspace.profile().supports(GameProfile.Feature.TILE_PAINTER)) {
			zoneLabel.setText("<html>The tile painter has not been verified for "
					+ Workspace.profile().displayName() + ".<br><small>"
					+ "Its brushes, elevation steps and edge strips were measured on"
					+ " Omega Ruby / Alpha Sapphire only.</small></html>");
			seededZone = -1;
			return;
		}
		if (!loadedZone.isOpen() || loadedZone.index() < 0) {
			zoneLabel.setText("Load a zone first (Zone Loader tab)");
			seededZone = -1;
			return;
		}
		if (seededZone != loadedZone.index()) {
			seed();
		} else {
			checkUnderneath();
		}
		syncWater();
		schedule3DRegen();
	}

	/** What re-activating the painter finds about the region it seeded (F247). */
	public enum Underneath {
		SAME, CHANGED, UNKNOWN
	}

	/** What re-activating the painter does with the painting it holds (F247). */
	public enum OnActivate {
		KEEP, RESEED, ASK
	}

	/**
	 * Whether another editor (geometry tool, OBJ import, resize) rewrote the seeded region since
	 * the painter seeded it (F247): SAME, CHANGED, or UNKNOWN - with why in {@code why} - when the
	 * region cannot be found or read, or there was no model to compare against. Every one of
	 * those was answered "changed", so a transient read failure - an emulator or a virus scanner
	 * holding the file - discarded the unapplied painting and its undo history without a word.
	 *
	 * @param now receives the model as it reads now, when it reads
	 */
	public static Underneath underneath(LoadedZone loadedZone, byte[] seeded, byte[][] now, StringBuilder why) {
		if (seeded == null) {
			why.append("there was no map model to compare against when painting started");
			return Underneath.UNKNOWN;
		}
		try {
			int[] cell = MapPainter.firstRegionCell(loadedZone);
			if (cell == null) {
				why.append("the zone's map has no region to read");
				return Underneath.UNKNOWN;
			}
			now[0] = new GR(Workspace.getWorkspaceFile(ArchiveType.FIELD_DATA, cell[0]), Workspace.session()).getFile(1);
		} catch (Exception unread) {
			why.append("the region could not be read: ").append(ctrmap.Ui.reason(unread));
			return Underneath.UNKNOWN;
		}
		return java.util.Arrays.equals(now[0], seeded) ? Underneath.SAME : Underneath.CHANGED;
	}

	/**
	 * Re-activated on the zone it holds (F247): a region it cannot check keeps the painting, a
	 * changed one starts over only when nothing is painted, and asks when something is.
	 */
	public static OnActivate onActivate(Underneath u, boolean painted) {
		if (u != Underneath.CHANGED) {
			return OnActivate.KEEP;
		}
		return painted ? OnActivate.ASK : OnActivate.RESEED;
	}

	/** Unapplied painting: a tile touched, or something placed. It asked the painter's own undo stack, which is gone (F209). */
	boolean painted() {
		for (boolean[] row : touched) {
			for (boolean t : row) {
				if (t) {
					return true;
				}
			}
		}
		return !placed.isEmpty();
	}

	/** The painter re-activated on the zone it holds: check the region under it, and say what was done. */
	private void checkUnderneath() {
		byte[][] now = new byte[1][];
		StringBuilder why = new StringBuilder();
		Underneath u = underneath(loadedZone, originalModel, now, why);
		switch (onActivate(u, painted())) {
			case RESEED:
				seed();
				break;
			case ASK:
				if (ctrmap.Ui.confirm(this, "Another editor changed this zone's map since you started painting it.\n\n"
						+ "Start over from the changed map? Your unapplied painting and its undo history are\n"
						+ "discarded. No keeps painting over the map as it was when you started.", "Map painter",
						JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION) {
					seed();
				} else {
					originalModel = now[0];   //asked once: the change is known now, not asked about again
					zoneLabel.setText("<html>Painting zone " + seededZone + "<br><small>over the map as it was -"
							+ " another editor has changed it since</small></html>");
				}
				break;
			default:
				if (u == Underneath.UNKNOWN) {
					zoneLabel.setText("<html>Painting zone " + seededZone + "<br><small>Could not check whether another"
							+ " editor changed this map (" + why + "), so your painting is kept.</small></html>");
				}
		}
	}

	public void deactivate() {
		toolActive = false;
		pendingPlace = null;
		placeStatus.setText(" ");
		previewNote(null);
		regenTimer.stop();
		restoreRealModel();
	}

	/** Says, under Apply, why the 3D preview is not what Apply would write; null clears it. */
	private void previewNote(String problem) {
		previewStatus.setText(problem == null ? " "
				: "<html><body style='width:220px'><font color='#b00000'>" + ctrmap.Ui.html(problem)
				+ "</font></body></html>");
	}

	void seed() {
		seededZone = loadedZone.index();
		zoneLabel.setText("Painting zone " + seededZone);
		previewNote(null);
		//a zone switch rebuilds the whole scene, so any old preview swap is gone
		previewInScene = false;
		originalModel = null;
		for (TilePalette[] row : grid) {
			java.util.Arrays.fill(row, TilePalette.GRASS);
		}
		for (int i = 0; i < DIM; i++) {
			java.util.Arrays.fill(height[i], 0);
			java.util.Arrays.fill(ramp[i], PaintedGeometry.NO_RAMP);
			java.util.Arrays.fill(touched[i], false);
		}
		placed.clear();
		history.forget(this);   //the canvas starts over: its old strokes mean nothing now, the zone's edits still do
		pendingPlace = null;
		placeStatus.setText(" ");
		int[] cell;
		try {
			cell = MapPainter.firstRegionCell(loadedZone);
		} catch (RuntimeException unread) {
			//SAID (F247): it read as "no region" and the painter started blank without a word
			cell = null;
			zoneLabel.setText("<html>Painting zone " + seededZone + "<br><small>Its map could not be read ("
					+ ctrmap.Ui.reason(unread) + "), so painting starts blank</small></html>");
		}
		cellX = cell != null ? cell[1] : 0;
		cellY = cell != null ? cell[2] : 0;
		int region = cell != null ? cell[0] : -1;
		donorModel = null;
		donorColl = null;
		donorTilemap = null;
		if (region >= 0) {
			MapPainter.loadFromRegion(region, grid);
			try {
				GR gr = new GR(Workspace.getWorkspaceFile(ArchiveType.FIELD_DATA, region), Workspace.session());
				byte[] m = gr.getFile(1);
				if (BchMapModel.isMapModel(m)) {
					donorModel = m;
					donorColl = gr.getFile(2);
					donorTilemap = gr.getFile(0);
					//elevations start at the map's REAL ground levels, so painted
					//tiles sit level with their retail surroundings by default
					int borrowed = PaintedHeights.seedHeightsFromCollision(donorColl, donorTilemap, height);
					if (borrowed > 0) {
						zoneLabel.setText("<html>Painting zone " + seededZone + "<br><small>" + borrowed
								+ " tile(s) have no ground of their own and start<br>level with their nearest neighbour</small></html>");
					}
				}
			} catch (Exception unread) {
				//SAID, NOT SWALLOWED (F247): it read as "no donor" - edge blending off, heights at 0 -
				//with the label giving no reason
				zoneLabel.setText("<html>Painting zone " + seededZone + "<br><small>Region " + region
						+ " could not be read (" + ctrmap.Ui.reason(unread) + "): no edge blending, and heights"
						+ " start at 0</small></html>");
			}
		}
		originalModel = donorModel;
		boolean canEdge = donorModel != null && PaintedMaterials.donorSupportsEdges(donorModel);
		edgeChk.setEnabled(canEdge);
		edgeChk.setSelected(canEdge);
		edgeBlend = canEdge;
	}

	private void syncWater() {
		if (seededZone < 0) {
			return;
		}
		boolean ripples = MapPainter.zoneWaterScrolls(loadedZone, loadedZone.open().header.areadataID);
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
		ctrmap.AreaForker.ForkResult fork = AreaForkPrompt.ensurePrivate(loadedZone, this, loadedZone.index(),
				loadedZone.open().header.areadataID, "adding the water animation");
		if (fork == null) {
			return;
		}
		int areaId = fork.newArea;
		int rsl = ctrmap.Ui.confirm(this,
				"Add GameFreak's sea-scroll animation for this zone's map cells?\n\n"
				+ "This is the exact animation retail water routes use. Safe to do\n"
				+ "before or after painting water.",
				"Make water ripple", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (rsl != JOptionPane.OK_OPTION) {
			return;
		}
		try {
			int changed = MapPainter.enableWaterScroll(loadedZone, areaId);
			syncWater();
			AreaForkPrompt.packIfForked(fork, null);
			ctrmap.Ui.message(this, changed > 0
					? "Sea-scroll animation added for " + changed + " map cell(s)."
					: "No map cells needed changes (the scroll was already bound).",
					"Make water ripple", JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception ex) {
			ctrmap.Ui.error(this, "Could not add the animation:\n" + ctrmap.util.Bytes.reason(ex),
					"Make water ripple");
		}
	}

	// ---- gestures from the PaintTool (map coords -> local cell coords) -----

	private int localX(int gx) {
		return gx - cellX * DIM;
	}

	private int localY(int gy) {
		return gy - cellY * DIM;
	}

	private boolean inCell(int lx, int ly) {
		return lx >= 0 && ly >= 0 && lx < DIM && ly < DIM;
	}

	/** The buttons follow the zone's history: lit exactly when there is something to take back or put again. */
	private void followHistory() {
		undoBtn.setEnabled(history.canUndo());
		redoBtn.setEnabled(history.canRedo());
	}

	/**
	 * The unapplied canvas as a document of the zone's history (F209): the brushes on each tile, the heights,
	 * the ramps, which tiles were touched, and the buildings placed - read whole, put back whole.
	 */
	public UndoHistory.Document<Object[]> canvas() {
		return new UndoHistory.Document<Object[]>() {
			@Override
			public Object subject() {
				return PaintForm.this;
			}

			@Override
			public Object[] take() {
				return copyState();
			}

			@Override
			public void put(Object[] state) {
				restoreState(state);
			}

			@Override
			public boolean same(Object[] a, Object[] b) {
				for (int i = 0; i < a.length; i++) {
					if (!java.util.Objects.deepEquals(a[i], b[i])) {
						return false;
					}
				}
				return true;
			}

			@Override
			public String what() {
				return "the painter's canvas";
			}
		};
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

	/**
	 * Arms the next map click to place {@code entry}, and says so.
	 *
	 * <p>The label is the only thing that distinguishes this state from the
	 * ordinary painting one: the cursor does not change, and the next click on
	 * the map drops a building instead of painting a tile. It also carries the
	 * half of the palette's answer that has no other trace - whether the
	 * passengers came along - because that choice is made in a dialog which is
	 * gone by the time the building lands, and a room placed without its floors
	 * looks like a room until you walk into it.
	 *
	 * <p>Its own method because the palette that leads here is modal: behind it
	 * nothing could see the label at all, so deleting the sentence, or writing
	 * the same one whatever was picked, left the whole battery green.
	 */
	public void beginPlacing(BuildingCatalog.Entry entry, boolean passengers) {
		pendingPlace = entry;
		pendingPassengers = passengers;
		placeStatus.setText("<html><b>Placing: " + entry.name + "</b>"
				+ (passengers ? "" : " (passengers left behind)") + " - click the map</html>");
	}

	/** Mouse-down on the map (global tile coords). */
	public void gesturePress(int gx, int gy, boolean right) {
		//the seeded document must match the DISPLAYED zone - a stroke accepted
		//against a stale document would silently mark tiles of the wrong map
		if (seededZone < 0 || seededZone != loadedZone.index()) {
			return;
		}
		int lx = localX(gx), ly = localY(gy);
		if (!inCell(lx, ly)) {
			return;
		}
		// building placement mode
		if (pendingPlace != null) {
			if (!right) {
				int px = Math.max(0, Math.min(DIM - pendingPlace.tilesW(), lx));
				int py = Math.max(0, Math.min(DIM - pendingPlace.tilesH(), ly));
				placed.add(new Placed(pendingPlace, px, py, pendingPassengers));
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
					if (ctrmap.Ui.confirm(this, "Remove \"" + placed.get(i).e.name + "\"?",
							"Buildings", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION) {
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
		applyToolAt(lx, ly, right);
		repaintMap();
	}

	/** Mouse-drag on the map (paint tool only; continues the open gesture). */
	public void gestureDrag(int gx, int gy, boolean right) {
		if (seededZone < 0 || seededZone != loadedZone.index() || ptool != 0 || right) {
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
				ramp[ly][lx] = right ? PaintedGeometry.NO_RAMP : turnRamp(lx, ly);
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
		if (cur == PaintedGeometry.NO_RAMP) {
			int d = PaintedHeights.steepestDescent(grid, height, lx, ly);
			placeStatus.setText(d < 0 ? "<html>No lower ground beside that tile -<br>raise it or lower a neighbour first.</html>" : " ");
			return d;
		}
		for (int k = 1; k < 4; k++) {
			int d = (cur + k) % 4;
			if (PaintedHeights.descends(grid, height, lx, ly, d)) {
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
				if (ramp[y][x] != PaintedGeometry.NO_RAMP
						&& !PaintedHeights.descends(grid, height, x, y, ramp[y][x])) {
					ramp[y][x] = PaintedHeights.steepestDescent(grid, height, x, y);
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
		//no map view - a headless test drives the document without one
		if (map != null) {
			map.firePropertyChange(TileMapPanel.PROP_REPAINT, false, true);
		}
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
		/** Why the preview is not what Apply would write, or null when it is. */
		final String problem;

		RegenResult(byte[] model, java.util.List<ctrmap.formats.h3d.texturing.H3DTexture> extra, String problem) {
			this.model = model;
			this.extraTextures = extra;
			this.problem = problem;
		}
	}

	private void startRegen() {
		if (seededZone < 0 || donorModel == null || !toolActive
				|| seededZone != loadedZone.index()) {
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
		final byte[] tmap = donorTilemap;
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
					byte[] src = MapPainter.importBrushMaterials(donor, g2, t2, null);
					byte[] model = PaintedRegionBuilder.buildModelOnly(Workspace.session(), src, coll, tmap, g2, h2, r2, t2, l2, edgesNow);
					java.util.List<ctrmap.formats.h3d.texturing.H3DTexture> extra = null;
					if (!p2.isEmpty()) {
						ctrmap.formats.h3d.RegionFactory.BlankContent bc = new ctrmap.formats.h3d.RegionFactory.BlankContent();
						bc.model = model;
						//throwaway carriers - only the stamped model is used
						java.util.List<float[]> one = new java.util.ArrayList<>();
						one.add(new float[]{0, 0, 0, 0, 0, PaintedGeometry.TILE, PaintedGeometry.TILE, 0, 0});
						bc.collision = ctrmap.formats.gfcollision.GfColl.build(one, null);
						bc.tilemap = new byte[Tilemap.REGION_BYTES];
						bc.tilemap[0] = (byte) DIM;
						bc.tilemap[2] = (byte) DIM;
						bc.props = new byte[]{0, 0, 0, 0};
						MapPainter.stampPlaced(bc, p2, h2, PaintedHeights.floorYGrid(coll, tmap, h2), null);
						model = bc.model;
						//donor-area textures load here, off the EDT (the loaders
						//are synchronized; a GARC decompress can take a moment)
						extra = new java.util.ArrayList<>();
						java.util.Set<Integer> areas = new java.util.LinkedHashSet<>();
						for (Placed pl : p2) {
							areas.add(pl.e.donorArea);
						}
						java.util.List<String> unloaded = new java.util.ArrayList<>();
						for (int area : areas) {
							try {
								extra.addAll(BuildingPaletteDialog.donorTextures(area));
							} catch (Exception ex) {
								unloaded.add("area " + area + " (" + ctrmap.Ui.reason(ex) + ")");
							}
						}
						if (!unloaded.isEmpty()) {
							return new RegenResult(model, extra, "the textures of placed buildings from "
									+ String.join(", ", unloaded) + " could not be loaded, so they show untextured here");
						}
					}
					return new RegenResult(model, extra, null);
				} catch (Exception ex) {
					//F211: this answered null, the last preview stayed up and looked current. It still
					//stays up - painting goes on - but the note under Apply says it is stale and why
					return new RegenResult(null, null, "the 3D preview could not be rebuilt and shows your earlier"
							+ " strokes: " + ctrmap.Ui.reason(ex));
				}
			}

			@Override
			protected void done() {
				regenRunning = false;
				try {
					RegenResult res = get();
					//map != null: no view, no scene to swap the preview into, so nothing
					//is swapped and previewInScene stays false - which is exactly the
					//state restoreRealModel() then has nothing to put back. Without the
					//clause the reach threw a NullPointerException that the catch below
					//swallowed whole, leaving the same state by accident and in silence.
					boolean current = toolActive && zoneAtStart == seededZone && seededZone == loadedZone.index()
							&& epochAtStart == regenEpoch && loadedZone.isOpen();
					if (current) {
						previewNote(res.problem);
					}
					if (res.model != null && current && map != null) {
						map.reloadRegionModel(cellX, cellY, res.model, res.extraTextures);
						previewInScene = true;
					}
				} catch (Exception ex) {
					//the worker answers every failure as a RegenResult, so this is the swap into the scene
					previewNote("the 3D preview could not be shown: " + ctrmap.Ui.reason(ex));
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
		//map != null first: with no view there is no scene holding a preview, so
		//there is nothing to put back. This runs from applyFailed(), ahead of the
		//report, so throwing here ate the "Nothing was written" the refusal owes
		//the user - the one place where a missing view was worse than a no-op.
		if (map != null && previewInScene && originalModel != null
				&& loadedZone.isOpen() && seededZone == loadedZone.index()) {
			map.reloadRegionModel(cellX, cellY, originalModel);
		}
		previewInScene = false;
	}

	// ---- the overlay on the main map view ---------------------------------

	/** Draws the painted cell over the map (called from the PaintTool). */
	public void drawOverlay(Graphics g, int sx, int sy, double d) {
		if (seededZone < 0 || seededZone != loadedZone.index()) {
			return;
		}
		int ax = cellX * DIM, ay = cellY * DIM;
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
				int rd = d >= 6 ? PaintedHeights.rampDir(grid, height, ramp, lx, ly) : -1;
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
			ctrmap.Ui.message(this, "Nothing to apply yet - paint some tiles or place a building first.",
					"Map Builder", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		final int zoneIndex = seededZone;
		String waterNote = "";
		if (MapPainter.usesWater(grid) && !MapPainter.zoneWaterScrolls(loadedZone, loadedZone.open().header.areadataID)) {
			waterNote = "\n\nNote: painted water will be STILL here - use \"Make water ripple here\" first.";
		}
		//what each building really is, at the moment of the decision
		String buildingsNote = placed.isEmpty() ? "" : "\n\nBuildings to place:\n" + MapPainter.placedSummary(placed);
		int rsl = ctrmap.Ui.confirm(this,
				"Apply your edits to zone " + zoneIndex + "'s map?\n"
				+ "The " + touchedCount + " tile(s) you touched are rebuilt; the rest of the map keeps\n"
				+ "its existing geometry. If the map is still shared with other zones, this\n"
				+ "zone gets its own private copy first. Deploy afterwards to walk on it." + waterNote + buildingsNote,
				"Map Builder", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (rsl != JOptionPane.OK_OPTION) {
			return;
		}
		//the apply reads/forks the last-SAVED workspace bytes - flush pending edits
		if (!openEditors.saveAll(true)) {
			return;
		}
		try {
			regenTimer.stop();
			regenEpoch++; // an in-flight regen must not re-arm across the apply
			MapPainter.applyToZone(loadedZone, zoneIndex, grid, height, ramp, lighting, edgeBlend, placed, touched, new Prompts(zoneList));
			// only a SUCCESSFUL apply reaches here: the pack + reload rebuilds
			// the scene from the applied bytes, so the preview swap is gone
			previewInScene = false;
			seededZone = -1; // reseed from the freshly built zone on next activate
		} catch (Exception ex) {
			applyFailed(ex);
		}
	}

	/**
	 * What a refused Apply owes the user: why it stopped, and that the map is
	 * untouched - plus the map itself, put back.
	 *
	 * <p>Both halves matter and both are this one path's job. The preview swaps
	 * the painted model into the 3D scene while the tool is open, so a failed
	 * Apply that left it there would show the user a map that is not on disk
	 * and never was. And Apply clears every precondition before it writes
	 * anything, which is why the sentence can promise nothing was written -
	 * that promise used to be a bare "Apply failed" over a half-applied map,
	 * and it is the difference between "try again" and "check what shipped".
	 *
	 * <p>Its own method, and through {@link ctrmap.Ui}, because everything
	 * above it in applyAction is a modal dialog: with no display this path was
	 * unreachable, and as a bare JOptionPane "the user was told" was not
	 * something any test could see.
	 */
	public void applyFailed(Exception why) {
		restoreRealModel();
		ctrmap.Ui.error(this, "Apply failed:\n" + ctrmap.util.Bytes.reason(why)
				+ "\n\nNothing was written - the map is exactly as it was.", "Map Builder");
	}

	/**
	 * The map-building engine's questions, asked in the editor (F248): the area fork through the area
	 * prompt, the door wiring and the sign routine through {@link ctrmap.Ui}, each sign's text in its own
	 * form, and after the pack the zone list rebuilt with the painted zone open and the report shown.
	 *
	 * <p>They lived inside the engine, so it could only be reached through the UI package; they are the
	 * only part of it that is UI, and they live with the panel that already names everything they use.
	 */
	public static final class Prompts implements MapPainter.Host {

		private final ZoneList zones;

		/** Asks in the editor's window, and after the pack lands on the painted zone in {@code zones}. */
		public Prompts(ZoneList zones) {
			this.zones = zones;
		}

		@Override
		public AreaForker.ForkResult ownArea(LoadedZone loaded, int zoneIndex, int area, String why) {
			return AreaForkPrompt.ensurePrivate(loaded, ctrmap.Ui.parent(), zoneIndex, area, why);
		}

		@Override
		public MapPainter.DoorWiring doorWiring(int enterable) {
			String[] opts = {"Clone private interiors (recommended)", "Link retail interiors (enter-only)", "Skip"};
			Object pick = ctrmap.Ui.input(
					enterable + " placed building(s) have doors. Wire them now?\n\n"
					+ "CLONE (recommended): each door gets its OWN interior - a copy of the retail\n"
					+ "room placed in a free base zone - and the room's exit leads BACK TO THIS MAP.\n"
					+ "Walk in, walk out: full round trip. (Rename the interior later via Tools.)\n\n"
					+ "RETAIL: doors warp into the shared retail rooms; entering works, but the\n"
					+ "room's exit leads to its retail town until you retarget it.",
					"Door warps", JOptionPane.QUESTION_MESSAGE, opts, opts[0]);
			int mode = java.util.Arrays.asList(opts).indexOf(pick);
			return mode == 0 ? MapPainter.DoorWiring.CLONE : mode == 1 ? MapPainter.DoorWiring.LINK_RETAIL : MapPainter.DoorWiring.SKIP;
		}

		@Override
		public boolean transplantSignRoutine(int instructions) {
			return ctrmap.Ui.confirm(
					"To make the placed sign(s) readable, this zone's script needs the vanilla\n"
					+ "sign-display routine (" + instructions + " instructions) transplanted into it.\n"
					+ "Inject it now? (Cancel keeps the signs as scenery.)",
					"Signs", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
		}

		@Override
		public MapPainter.SignText signText(MapPainter.Placed pl) {
			javax.swing.JTextArea ta = new javax.swing.JTextArea("", 5, 40);
			ta.setLineWrap(true);
			ta.setWrapStyleWord(true);
			JComboBox<String> typeBox = new JComboBox<>(ctrmap.formats.scripts.NpcTemplates.SIGN_TYPE_LABELS);
			JPanel panel = new JPanel();
			panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
			panel.add(new JLabel("Text for the " + pl.e.name + " at tile (" + pl.tx + ", " + pl.ty + ") - Cancel = scenery only:"));
			panel.add(new javax.swing.JScrollPane(ta));
			panel.add(new JLabel("Sign style:"));
			panel.add(typeBox);
			if (JOptionPane.showConfirmDialog(ctrmap.Ui.parent(), panel, "Sign text",
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
				return null;
			}
			return new MapPainter.SignText(ta.getText(), typeBox.getSelectedIndex());
		}

		@Override
		public void tell(String text, String title) {
			ctrmap.Ui.message(text, title, JOptionPane.INFORMATION_MESSAGE);
		}

		@Override
		public void error(String text, String title) {
			ctrmap.Ui.error(text, title);
		}

		@Override
		public void slotTaken(int slot) {
			zones.clearForkDecline(slot);
		}

		@Override
		public void applied(int zoneIndex, String report) {
			zones.rebuild(() -> {
				zones.open(zoneIndex);
				ctrmap.Ui.message(report, "Tile painter", JOptionPane.INFORMATION_MESSAGE);
			});
		}
	}
}
