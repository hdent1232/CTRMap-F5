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
import javax.swing.JCheckBox;
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

	/** A building/decoration placed on the painter grid (anchor = top-left tile). */
	static class Placed {

		final ctrmap.formats.h3d.BuildingCatalog.Entry e;
		final int tx, ty;

		Placed(ctrmap.formats.h3d.BuildingCatalog.Entry e, int tx, int ty) {
			this.e = e;
			this.tx = tx;
			this.ty = ty;
		}

		boolean contains(int x, int y) {
			return x >= tx && y >= ty && x < tx + e.tilesW() && y < ty + e.tilesH();
		}
	}

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
		final int[] tool = {0}; // 0 paint, 1 fill, 2 raise, 3 lower, 4 ramp
		final int[][] height = new int[DIM][DIM];
		final boolean[][] ramp = new boolean[DIM][DIM];
		final ctrmap.formats.tilemap.TerrainLighting lighting = ctrmap.formats.tilemap.TerrainLighting.daytime();
		final boolean[] edgeBlend = {true}; // GameFreak grass<->dirt/sand transition strips
		final java.util.List<Placed> placedBuildings = new java.util.ArrayList<>();
		final ctrmap.formats.h3d.BuildingCatalog.Entry[] pendingPlace = {null};

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

		final GridCanvas canvas = new GridCanvas(grid, height, ramp, brush, tool, terrainTex, placedBuildings, pendingPlace);

		//undo/redo: one snapshot per gesture (stroke, fill, place, remove)
		final java.util.ArrayDeque<Object[]> undoStack = new java.util.ArrayDeque<>();
		final java.util.ArrayDeque<Object[]> redoStack = new java.util.ArrayDeque<>();
		final java.util.function.Supplier<Object[]> copyState = () -> {
			TilePalette[][] g2 = new TilePalette[DIM][];
			int[][] h2 = new int[DIM][];
			boolean[][] r2 = new boolean[DIM][];
			for (int i = 0; i < DIM; i++) {
				g2[i] = grid[i].clone();
				h2[i] = height[i].clone();
				r2[i] = ramp[i].clone();
			}
			return new Object[]{g2, h2, r2, new java.util.ArrayList<>(placedBuildings)};
		};
		final java.util.function.Consumer<Object[]> restoreState = s -> {
			TilePalette[][] g2 = (TilePalette[][]) s[0];
			int[][] h2 = (int[][]) s[1];
			boolean[][] r2 = (boolean[][]) s[2];
			for (int i = 0; i < DIM; i++) {
				System.arraycopy(g2[i], 0, grid[i], 0, DIM);
				System.arraycopy(h2[i], 0, height[i], 0, DIM);
				System.arraycopy(r2[i], 0, ramp[i], 0, DIM);
			}
			placedBuildings.clear();
			placedBuildings.addAll((java.util.List<Placed>) s[3]);
			canvas.repaint();
		};
		final JButton undoBtn = new JButton("↶ Undo");
		final JButton redoBtn = new JButton("↷ Redo");
		final Runnable syncUndoBtns = () -> {
			undoBtn.setEnabled(!undoStack.isEmpty());
			redoBtn.setEnabled(!redoStack.isEmpty());
		};
		canvas.preMutate = () -> {
			undoStack.push(copyState.get());
			while (undoStack.size() > 100) {
				undoStack.removeLast();
			}
			redoStack.clear();
			syncUndoBtns.run();
		};
		undoBtn.addActionListener(e -> {
			if (!undoStack.isEmpty()) {
				redoStack.push(copyState.get());
				restoreState.accept(undoStack.pop());
				syncUndoBtns.run();
			}
		});
		redoBtn.addActionListener(e -> {
			if (!redoStack.isEmpty()) {
				undoStack.push(copyState.get());
				restoreState.accept(redoStack.pop());
				syncUndoBtns.run();
			}
		});
		undoBtn.setEnabled(false);
		redoBtn.setEnabled(false);
		undoBtn.setFocusable(false);
		redoBtn.setFocusable(false);

		final JDialog dlg = new JDialog(frame, "Tile painter - zone " + zoneIndex, true);
		dlg.setLayout(new BorderLayout(8, 8));
		((JPanel) dlg.getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		// brush palette (scrollable - the full tile catalog)
		JPanel side = new JPanel();
		side.setLayout(new javax.swing.BoxLayout(side, javax.swing.BoxLayout.Y_AXIS));
		JPanel undoRow = new JPanel(new GridLayout(1, 2, 4, 0));
		undoRow.setAlignmentX(0f);
		undoRow.setMaximumSize(new Dimension(216, 26));
		undoRow.add(undoBtn);
		undoRow.add(redoBtn);
		side.add(undoRow);
		side.add(javax.swing.Box.createVerticalStrut(6));
		side.add(new JLabel("Brush:"));
		JPanel brushPanel = new JPanel();
		brushPanel.setLayout(new javax.swing.BoxLayout(brushPanel, javax.swing.BoxLayout.Y_AXIS));
		ButtonGroup bg = new ButtonGroup();
		for (TilePalette t : TilePalette.brushes()) {
			JToggleButton b = new JToggleButton(t.label);
			b.setBackground(t.color());
			b.setForeground(textOn(t.color()));
			b.setFocusable(false);
			b.setAlignmentX(0f);
			b.setMaximumSize(new Dimension(200, 24));
			b.addActionListener(e -> brush[0] = t);
			if (t == TilePalette.GRASS) {
				b.setSelected(true);
			}
			bg.add(b);
			brushPanel.add(b);
		}
		javax.swing.JScrollPane brushScroll = new javax.swing.JScrollPane(brushPanel,
				javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
				javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		brushScroll.setMaximumSize(new Dimension(216, 176));
		brushScroll.setPreferredSize(new Dimension(216, 176));
		brushScroll.setAlignmentX(0f);
		side.add(brushScroll);
		side.add(javax.swing.Box.createVerticalStrut(6));

		// buildings & decorations: pick from the mined retail catalog, click to place
		final JButton buildingsBtn = new JButton("Buildings & decor...");
		final JLabel placeStatus = new JLabel(" ");
		buildingsBtn.setAlignmentX(0f);
		buildingsBtn.setFocusable(false);
		buildingsBtn.setToolTipText("Pokémon Centers, Marts, houses, signs, trees - pick one and click the grid to place it. Right-click a placed one to remove it.");
		buildingsBtn.addActionListener(e -> {
			ctrmap.formats.h3d.BuildingCatalog.Entry pick = BuildingPaletteDialog.pick(dlg, donorModel,
					mTileMapPanel.getWorldTextures());
			if (pick != null) {
				pendingPlace[0] = pick;
				placeStatus.setText("<html><b>Placing: " + pick.name + "</b> - click the grid</html>");
			}
		});
		side.add(buildingsBtn);
		placeStatus.setAlignmentX(0f);
		side.add(placeStatus);
		canvas.placeStatus = placeStatus;
		side.add(javax.swing.Box.createVerticalStrut(10));
		side.add(new JLabel("Tool:"));
		ButtonGroup tg = new ButtonGroup();
		JToggleButton paint = new JToggleButton("Paint (drag)");
		JToggleButton fill = new JToggleButton("Fill area");
		JToggleButton raise = new JToggleButton("Raise (+level)");
		JToggleButton lower = new JToggleButton("Lower (-level)");
		JToggleButton rampT = new JToggleButton("Ramp (walkable slope)");
		paint.setSelected(true);
		for (JToggleButton b : new JToggleButton[]{paint, fill, raise, lower, rampT}) {
			b.setFocusable(false);
			b.setAlignmentX(0f);
			tg.add(b);
			side.add(b);
		}
		paint.addActionListener(e -> tool[0] = 0);
		fill.addActionListener(e -> tool[0] = 1);
		raise.addActionListener(e -> tool[0] = 2);
		lower.addActionListener(e -> tool[0] = 3);
		rampT.addActionListener(e -> tool[0] = 4);
		raise.setToolTipText("Click tiles to raise them a level (cliffs form at drops).");
		lower.setToolTipText("Click tiles to lower them a level.");
		rampT.setToolTipText("Mark a raised tile next to a lower one as a walkable ramp/slope. Right-click clears.");
		side.add(javax.swing.Box.createVerticalStrut(10));
		JButton clearAll = new JButton("Fill all with brush");
		clearAll.setAlignmentX(0f);
		clearAll.setFocusable(false);
		clearAll.addActionListener(e -> {
			if (canvas.preMutate != null) {
				canvas.preMutate.run();
			}
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
		view3d.addActionListener(e -> open3DPreview(donorModel, grid, height, ramp, lighting, edgeBlend[0], placedBuildings));
		side.add(view3d);

		// GameFreak-style transition strips along grass<->dirt/sand seams (the "blend"
		// look). Only offered if this zone's tileset actually carries an edge material.
		boolean canEdge = donorModel != null && PaintedRegionBuilder.donorSupportsEdges(donorModel);
		edgeBlend[0] = canEdge;
		final JCheckBox edgeChk = new JCheckBox("Blend grass edges (GameFreak look)", canEdge);
		edgeChk.setAlignmentX(0f);
		edgeChk.setFocusable(false);
		edgeChk.setEnabled(canEdge);
		edgeChk.setToolTipText(canEdge
				? "Lay GameFreak's soft grass-edge strips where grass meets dirt/sand. Shows in 3D preview + Apply."
				: "This zone's tileset has no grass-edge material - start the zone from a grassy route to get edges.");
		edgeChk.addActionListener(e -> edgeBlend[0] = edgeChk.isSelected());
		side.add(edgeChk);

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
		JPanel north = new JPanel(new GridLayout(3, 1));
		north.add(new JLabel("  Paint terrain; Raise/Lower click a tile up/down a level (cliffs form at drops). 3D preview shows the real look. Apply, then Deploy."));
		north.add(new JLabel("  Terrain visuals use this zone's own materials - start the zone from a grassy route (Blank map canvas) for grass/water/rock textures."));
		// hard-to-miss water status: whether water painted here will ripple/scroll,
		// with a one-click fix (splices GameFreak's sea-scroll animation into the area)
		final int zoneAreaId = mZonePnl.zone.header.areadataID;
		final boolean[] ripples = {zoneWaterScrolls(zoneAreaId)};
		final JLabel waterBanner = new JLabel();
		final JButton makeRipple = new JButton("Make water ripple here");
		waterBanner.setOpaque(true);
		waterBanner.setForeground(new Color(0x30, 0x30, 0x30));
		waterBanner.setFont(waterBanner.getFont().deriveFont(java.awt.Font.BOLD));
		makeRipple.setFocusable(false);
		makeRipple.setToolTipText("Adds the retail two-layer sea-scroll animation for this zone's map cells to its area's animation data.");
		final Runnable syncWater = () -> {
			waterBanner.setText(ripples[0]
					? "  💧 Water ripples & scrolls in THIS zone - paint water freely."
					: "  💧 Heads up: water painted here will be STILL - click \"Make water ripple here\" to fix that.");
			waterBanner.setBackground(ripples[0] ? new Color(0xD6, 0xEF, 0xD6) : new Color(0xFF, 0xE9, 0xC2));
			makeRipple.setVisible(!ripples[0]);
		};
		syncWater.run();
		makeRipple.addActionListener(e -> {
			int rsl = JOptionPane.showConfirmDialog(dlg,
					"Add GameFreak's sea-scroll animation for this zone's map cells to area " + zoneAreaId + "?\n\n"
					+ "This is the exact animation retail water routes use (two water texture layers\n"
					+ "scrolling opposite ways). It's bound by map-cell name: any map sharing this\n"
					+ "zone's exact cells gains it too; the area's other maps are untouched.\n"
					+ "Safe to do before or after painting water.",
					"Make water ripple", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (rsl != JOptionPane.OK_OPTION) {
				return;
			}
			try {
				int changed = enableWaterScroll(zoneAreaId);
				ripples[0] = zoneWaterScrolls(zoneAreaId);
				syncWater.run();
				JOptionPane.showMessageDialog(dlg, changed > 0
						? "Sea-scroll animation added for " + changed + " map cell(s).\nPack/Deploy, then check the water in-game (it's on the TESTING.md list)."
						: "No map cells needed changes (the scroll was already bound).",
						"Make water ripple", JOptionPane.INFORMATION_MESSAGE);
			} catch (Exception ex) {
				JOptionPane.showMessageDialog(dlg, "Could not add the animation:\n" + ex.getMessage(),
						"Make water ripple", JOptionPane.ERROR_MESSAGE);
			}
		});
		JPanel waterRow = new JPanel(new BorderLayout());
		waterRow.add(waterBanner, BorderLayout.CENTER);
		waterRow.add(makeRipple, BorderLayout.EAST);
		north.add(waterRow);
		dlg.add(north, BorderLayout.NORTH);

		JPanel buttons = new JPanel();
		JButton objects = new JButton("Objects & furniture...");
		JButton apply = new JButton("Apply to zone");
		JButton close = new JButton("Close");
		buttons.add(objects);
		buttons.add(apply);
		buttons.add(close);
		dlg.add(buttons, BorderLayout.SOUTH);

		objects.addActionListener(e -> JOptionPane.showMessageDialog(dlg,
				"<html><b>Interactive objects</b> (doors, PCs, TVs, boats/submarines, monuments,<br>"
				+ "gym gadgets) are <b>props</b> - place them with the <b>Prop Tool</b> in the toolbar:<br>"
				+ "it has a searchable palette (type 'pc', 'door', 'tv', 'boat'...) with a 3D preview.<br><br>"
				+ "<b>Everything else you can see</b> - building exteriors (Poke Centers, Marts,<br>"
				+ "houses), sign posts, most trees, furniture (beds, tables, counters) - is baked<br>"
				+ "into the <b>map model</b>. Build those by copying them from a real map:<br>"
				+ "open a city/route, select the building's tiles in the <b>Geometry tool</b>, click<br>"
				+ "<b>Copy selection as prefab</b>, then <b>Stamp prefab here</b> on your map (geometry,<br>"
				+ "collision, tiles and textures all carry over - even across areas).<br><br>"
				+ "To make a <b>sign readable</b>, add the interaction with Add NPC / object... &rarr; sign.<br>"
				+ "To make a <b>door work</b>, paint the Door tile and point a warp at an interior zone<br>"
				+ "(clone a real interior with the Zone tools so its exit leads back to your map).</html>",
				"Objects & furniture", JOptionPane.INFORMATION_MESSAGE));

		apply.addActionListener(e -> {
			// water renders correctly (2-layer material) but only SCROLLS if this
			// zone's area animates chip_sea_b (the anim lives in AreaData, not the
			// model) - warn if the user painted water into a non-water area
			String waterNote = "";
			if (usesWater(grid) && !zoneWaterScrolls(mZonePnl.zone.header.areadataID)) {
				waterNote = "\n\nNote: painted water will be STILL here - use the \"Make water ripple\n"
						+ "here\" button (top banner) to add GameFreak's scroll animation first.";
			}
			int rsl = JOptionPane.showConfirmDialog(dlg,
					"Build zone " + zoneIndex + "'s map from the painted tiles?\n"
					+ "The zone gets its own private geometry first (the source is untouched),\n"
					+ "then packs. Deploy to walk on it." + waterNote,
					"Tile painter", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (rsl != JOptionPane.OK_OPTION) {
				return;
			}
			//the apply reads/forks the last-SAVED workspace bytes, so flush any
			//pending on-screen edits first (same store chain as switching zones)
			if (!(mCamEditForm.store(true) && mTileMapPanel.saveTileMap(true) && mMtxEditForm.store(true)
					&& mPropEditForm.store(true) && mNPCEditForm.saveRegistry(true) && mZonePnl.store(true))) {
				return;
			}
			try {
				applyToZone(zoneIndex, grid, height, ramp, lighting, edgeBlend[0], placedBuildings);
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

	private static boolean usesWater(TilePalette[][] grid) {
		for (TilePalette[] row : grid) {
			for (TilePalette t : row) {
				if (t == TilePalette.WATER || t == TilePalette.WATERFALL) {
					return true;
				}
			}
		}
		return false;
	}

	/** Distinct internal model names of the zone's map regions (the anim binding key). */
	private static java.util.List<String> zoneRegionModels() {
		java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
		try {
			File mmFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.MAP_MATRIX, mZonePnl.zone.header.mapmatrixID);
			byte[] mm = java.nio.file.Files.readAllBytes(mmFile.toPath());
			int sub0 = le32(mm, 4);
			int w = u16(mm, sub0 + 4), h = u16(mm, sub0 + 6);
			for (int k = 0; k < w * h; k++) {
				int id = u16(mm, sub0 + 8 + k * 2);
				if (id == 0xFFFF) {
					continue;
				}
				try {
					byte[] model = new GR(Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, id)).getFile(1);
					if (BchMapModel.isMapModel(model)) {
						String n = new BchMapModel(model).getModelName();
						if (n != null && !n.isEmpty()) {
							names.add(n);
						}
					}
				} catch (Exception ignore) {
				}
			}
		} catch (Exception ignore) {
		}
		return new java.util.ArrayList<>(names);
	}

	/** The loaded zone's LIVE areadata container when it covers this area (prop
	 *  and camera editors write through the same instance, and its cached
	 *  subfile offsets must stay coherent when subfile 2 grows), else a fresh one. */
	private static ctrmap.formats.containers.AD areaContainer(int areaId) throws Exception {
		if (mZonePnl != null && mZonePnl.zone != null && mZonePnl.zone.header != null
				&& mZonePnl.zone.header.areadata != null && mZonePnl.zone.header.areadataID == areaId) {
			return mZonePnl.zone.header.areadata;
		}
		return new ctrmap.formats.containers.AD(Workspace.getWorkspaceFile(Workspace.ArchiveType.AREA_DATA, areaId));
	}

	/** True if painted water will actually scroll: every one of the zone's map
	 *  cells has the chip_sea_b scroll pair bound in the area's animations.
	 *  Unknown (unreadable data, no readable cells) counts as NO - the banner
	 *  then shows the fix button, whose click surfaces the real error. */
	private static boolean zoneWaterScrolls(int areaId) {
		try {
			ctrmap.formats.area.WorldAnim wa = new ctrmap.formats.area.WorldAnim(areaContainer(areaId).getFile(2));
			java.util.List<String> models = zoneRegionModels();
			if (models.isEmpty()) {
				return false;
			}
			for (String m : models) {
				if (!wa.hasSeaScroll(m)) {
					return false;
				}
			}
			return true;
		} catch (Exception ex) {
			return false;
		}
	}

	/** Splices the retail sea-scroll pair into the area's animations for each of
	 *  the zone's map cells; validates before storing. Returns cells changed. */
	private static int enableWaterScroll(int areaId) throws Exception {
		ctrmap.formats.containers.AD ad = areaContainer(areaId);
		byte[] sub2 = ad.getFile(2);
		java.util.List<String> models = zoneRegionModels();
		if (models.isEmpty()) {
			throw new IllegalStateException("could not read this zone's map cells (map matrix / region models)");
		}
		int changed = 0;
		for (String m : models) {
			byte[] out = ctrmap.formats.area.WorldAnim.spliceSeaScroll(sub2, m);
			if (out != sub2) {
				sub2 = out;
				changed++;
			}
		}
		if (changed > 0) {
			java.util.List<String> errs = new ctrmap.formats.area.WorldAnim(sub2).validate();
			if (!errs.isEmpty()) {
				throw new IllegalStateException("splice failed validation: " + errs.get(0));
			}
			if (!ad.storeFile(2, sub2)) {
				throw new IllegalStateException("could not write areadata " + areaId + " (file locked or read-only?)");
			}
		}
		return changed;
	}

	/**
	 * Stamps every placed building into a freshly built region (geometry,
	 * collision, movement tiles - the retail footprint tuples ride along, door
	 * tile included), collecting per-donor-area texture needs. Throws on any
	 * failure so a half-stamped map is never applied.
	 */
	private static void stampPlaced(RegionFactory.BlankContent bc, java.util.List<Placed> placed,
			int[][] height, java.util.Map<Integer, java.util.Set<String>> texNeeds) {
		for (Placed pl : placed) {
			ctrmap.formats.h3d.MapPrefab p = BuildingPaletteDialog.cachedPrefab(pl.e);
			if (p == null) {
				throw new IllegalStateException("could not cut \"" + pl.e.name + "\" from the dump");
			}
			// plant the piece on the terrain at its anchor: donors sit at their
			// own base height (a gym floats at -18, a palm at +46 over a beach
			// patch), so dy re-bases them onto this tile's ground level
			float dy = (height != null ? height[pl.ty][pl.tx] : 0) * PaintedRegionBuilder.STEP - pl.e.baseY;
			ctrmap.formats.h3d.MapPrefab.StampResult r = p.stampGeometry(bc.model, pl.tx, pl.ty, dy);
			if (r.stamped.isEmpty()) {
				throw new IllegalStateException("\"" + pl.e.name + "\" could not be stamped"
						+ (r.missingMaterials.isEmpty() ? "" : " (missing materials: " + r.missingMaterials + ")"));
			}
			bc.model = r.newModel;
			bc.collision = p.stampCollision(bc.collision, pl.tx, pl.ty, dy);
			if (p.tiles != null) {
				for (int y = 0; y < p.tilesH; y++) {
					for (int x = 0; x < p.tilesW; x++) {
						int gx = pl.tx + x, gy = pl.ty + y;
						if (gx < DIM && gy < DIM && p.tiles[x] != null && p.tiles[x][y] != null) {
							System.arraycopy(p.tiles[x][y], 0, bc.tilemap, 4 + (gy * DIM + gx) * 4, 4);
						}
					}
				}
			}
			if (texNeeds != null && !r.texturesNeeded.isEmpty()) {
				texNeeds.computeIfAbsent(pl.e.donorArea, k -> new java.util.LinkedHashSet<>()).addAll(r.texturesNeeded);
			}
		}
		java.util.List<String> errs = new BchMapModel(bc.model).validate();
		if (!errs.isEmpty()) {
			throw new IllegalStateException("stamped model failed validation: " + errs.get(0));
		}
	}

	/** Generates the model from the current grid and shows it in the real 3D renderer. */
	private static void open3DPreview(byte[] donorModel, TilePalette[][] grid, int[][] height, boolean[][] ramp, ctrmap.formats.tilemap.TerrainLighting lighting, boolean edges, java.util.List<Placed> placed) {
		try {
			RegionFactory.BlankContent bc = PaintedRegionBuilder.build(donorModel, grid, height, ramp, lighting, edges);
			if (!placed.isEmpty()) {
				stampPlaced(bc, placed, height, null);
			}
			byte[] model = bc.model;
			java.util.List<ctrmap.formats.h3d.texturing.H3DTexture> texes
					= new java.util.ArrayList<>(mTileMapPanel.getWorldTextures());
			java.util.Set<Integer> donorAreas = new java.util.LinkedHashSet<>();
			for (Placed pl : placed) {
				donorAreas.add(pl.e.donorArea);
			}
			for (int area : donorAreas) {
				texes.addAll(BuildingPaletteDialog.donorTextures(area));
			}
			MapPreview3D view = new MapPreview3D();
			view.setRegion(model, texes);
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

	private static void applyToZone(int zoneIndex, TilePalette[][] grid, int[][] height, boolean[][] ramp,
			ctrmap.formats.tilemap.TerrainLighting lighting, boolean edges, java.util.List<Placed> placed) throws Exception {
		GeometryForker.ForkResult r = GeometryForker.forkGeometry(zoneIndex);
		File fdDir = Workspace.getExtractionDirectory(Workspace.ArchiveType.FIELD_DATA);
		java.util.Map<Integer, java.util.Set<String>> texNeeds = new java.util.LinkedHashMap<>();
		// the swinging-door props for placed buildings (registry + textures handled)
		StringBuilder propNote = new StringBuilder();
		byte[] doorProps = placed.isEmpty() ? null : buildDoorProps(placed, height, propNote);
		boolean firstCell = true;
		for (int newRegion : r.newRegions) {
			File f = new File(fdDir, String.valueOf(newRegion));
			GR gr = new GR(f);
			byte[] donor = gr.getFile(1);
			if (!BchMapModel.isMapModel(donor)) {
				continue;
			}
			RegionFactory.BlankContent bc = PaintedRegionBuilder.build(donor, grid, height, ramp, lighting, edges);
			if (!placed.isEmpty()) {
				stampPlaced(bc, placed, height, texNeeds);
			}
			gr.storeFile(1, bc.model);
			gr.storeFile(2, bc.collision);
			gr.storeFile(0, bc.tilemap);
			// door props carry ABSOLUTE world coords of the FIRST map cell (where
			// the warps also go) - storing them into every region would stack
			// engine-visible duplicates at that one location
			gr.storeFile(3, (firstCell && doorProps != null) ? doorProps : bc.props);
			firstCell = false;
		}
		// stamped pieces reference their donor areas' textures - carry any the
		// zone's area lacks, or the game hardlocks on load
		StringBuilder texNote = new StringBuilder();
		int zoneArea = mZonePnl.zone.header.areadataID;
		for (java.util.Map.Entry<Integer, java.util.Set<String>> en : texNeeds.entrySet()) {
			if (en.getKey() == zoneArea) {
				continue;
			}
			texNote.append(ctrmap.formats.h3d.BchTexturePack.carryToArea(
					en.getKey(), zoneArea, new java.util.ArrayList<>(en.getValue()), areaContainer(zoneArea)).trim()).append('\n');
		}
		int enterable = 0;
		for (Placed pl : placed) {
			if (pl.e.enterable()) {
				enterable++;
			}
		}
		int wired = 0;
		StringBuilder wireNote = new StringBuilder();
		if (enterable > 0) {
			String[] opts = {"Clone private interiors (recommended)", "Link retail interiors (enter-only)", "Skip"};
			int mode = JOptionPane.showOptionDialog(frame,
					enterable + " placed building(s) have doors. Wire them now?\n\n"
					+ "CLONE (recommended): each door gets its OWN interior - a copy of the retail\n"
					+ "room placed in a free base zone - and the room's exit leads BACK TO THIS MAP.\n"
					+ "Walk in, walk out: full round trip. (Rename the interior later via Tools.)\n\n"
					+ "RETAIL: doors warp into the shared retail rooms; entering works, but the\n"
					+ "room's exit leads to its retail town until you retarget it.",
					"Door warps", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
			if (mode == 0 || mode == 1) {
				wired = wireDoorWarps(zoneIndex, placed, height, mode == 0, wireNote);
			}
		}
		int signsWired = 0;
		try {
			signsWired = wireSigns(zoneIndex, placed);
		} catch (Exception ex) {
			wireNote.append("\nSign wiring failed: ").append(ex.getMessage());
		}
		final String extras = (signsWired > 0 ? "\n\n" + signsWired + " readable sign(s) wired (text saved; edit later via the NPC tool's dialogue section)." : "")
				+ (texNote.length() > 0 ? "\n" + texNote.toString().trim() : "")
				+ (doorProps != null ? "\n\nSwinging-door prop(s) placed automatically (registry + textures handled)." : "")
				+ (propNote.length() > 0 ? propNote : "")
				+ (wired > 0 ? "\n\n" + wired + " door warp(s) added." + wireNote : "")
				+ (enterable > wired ? "\n\n" + (enterable - wired) + " door(s) left unwired - add warps with the Warp tool when ready." : "");
		Workspace.packWorkspace(new Runnable() {
			@Override
			public void run() {
				mZonePnl.loadEverything(new Runnable() {
					@Override
					public void run() {
						mZonePnl.selectZone(zoneIndex);
						JOptionPane.showMessageDialog(frame,
								"Painted map applied to zone " + zoneIndex + " (region(s) "
								+ java.util.Arrays.toString(r.newRegions) + ").\nDeploy to emulator to walk on it."
								+ extras,
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
		int[] c = firstRegionCell();
		return c == null ? -1 : c[0];
	}

	/** {regionId, cellX, cellY} of the zone's first map cell (the painted one), or null. */
	private static int[] firstRegionCell() {
		try {
			File mmFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.MAP_MATRIX, mZonePnl.zone.header.mapmatrixID);
			byte[] mm = java.nio.file.Files.readAllBytes(mmFile.toPath());
			int sub0 = le32(mm, 4);
			int w = u16(mm, sub0 + 4), h = u16(mm, sub0 + 6);
			for (int k = 0; k < w * h; k++) {
				int id = u16(mm, sub0 + 8 + k * 2);
				if (id != 0xFFFF) {
					return new int[]{id, k % w, k / w};
				}
			}
		} catch (Exception ex) {
		}
		return null;
	}

	/**
	 * Builds the region's prop placements: one swinging-door prop per placed
	 * building, at the door tile's center (retail door props sit at exactly
	 * doorTile*18+9, Y = building base, rotation 0). Handles the area's prop
	 * registry and texture imports; a door whose registration fails is skipped
	 * with a note (the map itself is unaffected). Returns null when no props.
	 */
	private static byte[] buildDoorProps(java.util.List<Placed> placed, int[][] height, StringBuilder note) {
		int[] cell = firstRegionCell();
		if (cell == null) {
			return null;
		}
		ctrmap.formats.propdata.GRPropData pd = new ctrmap.formats.propdata.GRPropData();
		for (Placed pl : placed) {
			if (pl.e.doorProp == null || pl.e.doorProp.equals("-")) {
				continue;
			}
			try {
				int uid = ensureDoorPropRegistered(pl.e.doorProp);
				ctrmap.formats.propdata.GRProp p = new ctrmap.formats.propdata.GRProp();
				p.uid = uid;
				p.x = (cellX(cell) * 40 + pl.tx + pl.e.doorDX) * 18 + 9;
				p.z = (cellY(cell) * 40 + pl.ty + pl.e.doorDY) * 18 + 9;
				p.y = height[pl.ty][pl.tx] * PaintedRegionBuilder.STEP;
				pd.props.add(p);
			} catch (Exception ex) {
				note.append("\nDoor prop for \"").append(pl.e.name).append("\" skipped: ").append(ex.getMessage());
			}
		}
		return pd.props.isEmpty() ? null : pd.assemblePropData();
	}

	/**
	 * Ensures the door prop model is usable in THIS zone's area: registry entry
	 * (cloned from the retail template) and its textures (imported from the
	 * best donor area) - a missing texture would hardlock the game on area
	 * load. Writes through the zone's LIVE areadata container. Returns the
	 * registry reference id for the GRProp uid.
	 */
	private static int ensureDoorPropRegistered(String propModelName) throws Exception {
		ctrmap.formats.propdata.PropDatabase db = ctrmap.formats.propdata.PropDatabase.get();
		if (db == null) {
			throw new IllegalStateException("prop database unavailable");
		}
		ctrmap.formats.propdata.PropDatabase.PropModel pm = null;
		for (ctrmap.formats.propdata.PropDatabase.PropModel m : db.models) {
			if (propModelName.equals(m.name)) {
				pm = m;
				break;
			}
		}
		if (pm == null) {
			throw new IllegalStateException("model \"" + propModelName + "\" not in BuildingModels");
		}
		int areaId = mZonePnl.zone.header.areadataID;
		ctrmap.formats.containers.AD ad = areaContainer(areaId);
		ctrmap.formats.propdata.ADPropRegistry reg = new ctrmap.formats.propdata.ADPropRegistry(ad, null, false);
		for (ctrmap.formats.propdata.ADPropRegistry.ADPropRegistryEntry e : reg.entries.values()) {
			if (e.model == pm.modelIndex) {
				return e.reference; // already registered in this area
			}
		}
		// textures first (all-or-nothing before any registry write)
		byte[] modelBch = ctrmap.formats.propdata.PropDatabase.getSubfile(
				Workspace.bm.getDecompressedEntry(pm.modelIndex), 0);
		byte[] targetPack = ad.getFile(1);
		java.util.Set<String> available = ctrmap.formats.propdata.PropDatabase.getTexturePackTextureNames(targetPack);
		java.util.List<String> missing = ctrmap.formats.propdata.PropDatabase.getMissingTextureNames(modelBch, available);
		if (!missing.isEmpty()) {
			int donorArea = db.findDonorAreaWithTextures(pm, missing);
			if (donorArea < 0) {
				throw new IllegalStateException("no donor area has its textures " + missing);
			}
			byte[] donorPack;
			File donorWs = new File(Workspace.getExtractionDirectory(Workspace.ArchiveType.AREA_DATA), String.valueOf(donorArea));
			if (donorWs.exists()) {
				donorPack = ctrmap.formats.propdata.PropDatabase.getSubfile(java.nio.file.Files.readAllBytes(donorWs.toPath()), 1);
			} else {
				donorPack = ctrmap.formats.propdata.PropDatabase.getSubfile(Workspace.ad.getDecompressedEntry(donorArea), 1);
			}
			byte[] merged = ctrmap.formats.h3d.BchTexturePack.importTextures(targetPack, donorPack, missing);
			if (merged != targetPack) {
				ctrmap.formats.h3d.BCHFile check = new ctrmap.formats.h3d.BCHFile(merged);
				if (check.errorlevel != 0) {
					throw new IllegalStateException("merged texture pack failed verification");
				}
				if (!ad.storeFile(1, merged)) {
					throw new IllegalStateException("could not write the area texture pack");
				}
			}
		}
		// registry entry: retail template when one exists (carries the door's
		// open/close animation bindings), else a bare entry
		ctrmap.formats.propdata.ADPropRegistry.ADPropRegistryEntry entry;
		if (pm.template != null) {
			entry = new ctrmap.formats.propdata.ADPropRegistry.ADPropRegistryEntry(
					new ctrmap.LittleEndianDataInputStream(new java.io.ByteArrayInputStream(pm.template)));
		} else {
			entry = new ctrmap.formats.propdata.ADPropRegistry.ADPropRegistryEntry();
		}
		int ref = pm.modelIndex;
		while (reg.entries.containsKey(ref)) {
			ref++;
		}
		entry.reference = ref;
		entry.model = pm.modelIndex;
		reg.entries.put(ref, entry);
		// checked store (ADPropRegistry.write() cannot report a failed write; an
		// unwritten entry would leave the GRProp uid unresolvable in-game)
		java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
		ctrmap.LittleEndianDataOutputStream dos = new ctrmap.LittleEndianDataOutputStream(baos);
		dos.writeInt(reg.entries.size());
		for (ctrmap.formats.propdata.ADPropRegistry.ADPropRegistryEntry e : reg.entries.values()) {
			e.write(dos);
		}
		dos.close();
		if (!ad.storeFile(0, baos.toByteArray())) {
			throw new IllegalStateException("could not write the area prop registry");
		}
		return ref;
	}

	/**
	 * Adds a retail-shaped door warp for every enterable placed building:
	 * measured from all 167 retail doors - face 1, transition 3, 1x1, at
	 * doorTile*18+9 world units, height = the door tile's terrain level,
	 * target = the interior zone's entry warp (always warp 0 in retail).
	 */
	private static int wireDoorWarps(int zoneIndex, java.util.List<Placed> placed, int[][] height,
			boolean cloneInteriors, StringBuilder note) throws Exception {
		int[] cell = firstRegionCell();
		if (cell == null) {
			throw new IllegalStateException("could not resolve the zone's map cell for warp placement");
		}
		// free base-zone slots for private interior clones (scripts need index
		// < 536); a slot with an in-session workspace file is NOT free even if
		// the packed archive (which the scanner reads) still looks empty
		java.util.List<Integer> slots = new java.util.ArrayList<>();
		if (cloneInteriors) {
			File zoDir = Workspace.getExtractionDirectory(Workspace.ArchiveType.ZONE_DATA);
			for (ctrmap.ZoneRepurposeScanner.Candidate c : ctrmap.ZoneRepurposeScanner.scan()) {
				if (c.tier <= 1 && c.index != zoneIndex && !new File(zoDir, String.valueOf(c.index)).exists()) {
					slots.add(c.index);
				}
			}
		}
		ctrmap.formats.containers.ZO zo = zoneContainer(zoneIndex);
		ctrmap.formats.zone.ZoneEntities ent = new ctrmap.formats.zone.ZoneEntities(zo.getFile(1));

		// PHASE A: add every door warp with its RETAIL interior target (always
		// functional) and store, so warps exist on disk before any clone points
		// back at them. Doors already wired by an earlier Apply are kept as-is.
		java.util.List<int[]> newWarps = new java.util.ArrayList<>(); // {warpIdx, placedIdx}
		for (int pi = 0; pi < placed.size(); pi++) {
			Placed pl = placed.get(pi);
			if (!pl.e.enterable()) {
				continue;
			}
			int wx = (cellX(cell) * 40 + pl.tx + pl.e.doorDX) * 18 + 9;
			int wy = (cellY(cell) * 40 + pl.ty + pl.e.doorDY) * 18 + 9;
			boolean dup = false;
			for (ctrmap.formats.zone.ZoneEntities.Warp w : ent.warps) {
				if (w.x == wx && w.y == wy) {
					dup = true;
					break;
				}
			}
			if (dup) {
				note.append("\n\"").append(pl.e.name).append("\" door already wired - kept as is.");
				continue;
			}
			ctrmap.formats.zone.ZoneEntities.Warp w = new ctrmap.formats.zone.ZoneEntities.Warp();
			w.targetZone = pl.e.interiorZone;
			w.targetWarpId = Math.max(0, pl.e.interiorWarpId);
			w.faceDirection = 1;
			w.transitionType = 3;
			w.coordinateType = 0;
			w.x = wx;
			w.y = wy;
			w.z = height[pl.ty][pl.tx] * 18;
			w.w = 1;
			w.h = 1;
			newWarps.add(new int[]{ent.warps.size(), pi});
			ent.warps.add(w);
		}
		if (newWarps.isEmpty()) {
			return 0;
		}
		ent.modified = true;
		byte[] assembled = ent.assembleData();
		if (assembled == null || !zo.storeFile(1, assembled)) {
			throw new IllegalStateException("could not write the door warps to zone " + zoneIndex);
		}

		// PHASE B: clone private interiors and retarget; a failed clone leaves
		// that door on its (functional) retail target instead of aborting
		if (cloneInteriors) {
			int slotUse = 0;
			boolean retargeted = false;
			for (int[] nw : newWarps) {
				Placed pl = placed.get(nw[1]);
				if (slotUse >= slots.size()) {
					note.append("\nNo free base zone left for \"").append(pl.e.name).append("\" - linked retail instead.");
					continue;
				}
				int slot = slots.get(slotUse++); // consume on attempt - never reuse a possibly half-written slot
				try {
					int retailLinks = ctrmap.InteriorWirer.cloneAndWire(zoneIndex, nw[0], pl.e.interiorZone, slot);
					ent.warps.get(nw[0]).targetZone = slot;
					ent.warps.get(nw[0]).targetWarpId = 0;
					retargeted = true;
					note.append("\n\"").append(pl.e.name).append("\" interior = zone ").append(slot)
							.append(retailLinks > 0 ? " (its upper floor still leads to the retail building)" : "");
				} catch (Exception ex) {
					note.append("\n\"").append(pl.e.name).append("\": interior clone failed (")
							.append(ex.getMessage()).append(") - linked retail instead.");
				}
			}
			if (retargeted) {
				ent.modified = true;
				assembled = ent.assembleData();
				if (assembled == null || !zo.storeFile(1, assembled)) {
					note.append("\nCould not save the retargeted warps - doors lead to the retail interiors.");
				}
			}
		}
		return newWarps.size();
	}

	private static int cellX(int[] cell) {
		return cell[1];
	}

	private static int cellY(int[] cell) {
		return cell[2];
	}

	/** The loaded zone's LIVE ZO container when it is this zone (keeps its
	 *  cached subfile offsets coherent for the open editors), else a fresh one. */
	private static ctrmap.formats.containers.ZO zoneContainer(int zoneIndex) throws Exception {
		if (mZonePnl != null && mZonePnl.zone != null && mZonePnl.zoneIndex == zoneIndex && mZonePnl.zone.file != null) {
			return mZonePnl.zone.file;
		}
		return new ctrmap.formats.containers.ZO(Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, zoneIndex));
	}

	private static String escapeTypedText(String text) {
		return text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n");
	}

	/** A validated sign-routine donor script from the workspace ZoneData. */
	private static ctrmap.formats.scripts.GFLPawnScript paletteSignDonor() {
		final ctrmap.formats.garc.GARC zoneGarc = Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA);
		if (zoneGarc == null) {
			throw new ctrmap.formats.scripts.SignWrapperInjector.InjectionException("The ZoneData archive is not loaded.");
		}
		return ctrmap.formats.scripts.SignWrapperInjector.pickDonor(new ctrmap.formats.scripts.MsgWrapperInjector.ScriptSource() {
			@Override
			public ctrmap.formats.scripts.GFLPawnScript get(int zoneIndex) {
				File f = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, zoneIndex);
				if (f == null || !f.exists()) {
					return null;
				}
				try {
					return ctrmap.formats.scripts.MsgWrapperInjector.extractZoneScript(
							java.nio.file.Files.readAllBytes(f.toPath()));
				} catch (Exception ex) {
					return null;
				}
			}
		}, zoneGarc.length);
	}

	/**
	 * Makes placed SIGN pieces readable: for each, asks for the sign's text,
	 * appends a storytext line, clones the zone's sign script (the proven
	 * NpcTemplates path) and drops the interactive furniture record on the
	 * sign's tile. Signs stay pure scenery when the user cancels their dialog
	 * or the zone's script lacks the sign-display routine.
	 */
	private static int wireSigns(int zoneIndex, java.util.List<Placed> placed) throws Exception {
		java.util.List<Placed> signs = new java.util.ArrayList<>();
		for (Placed pl : placed) {
			if ("SIGN".equals(pl.e.kind)) {
				signs.add(pl);
			}
		}
		if (signs.isEmpty()) {
			return 0;
		}
		int[] cell = firstRegionCell();
		if (cell == null) {
			return 0;
		}
		ctrmap.formats.containers.ZO zo = zoneContainer(zoneIndex);
		ctrmap.formats.scripts.GFLPawnScript s = new ctrmap.formats.scripts.GFLPawnScript(zo.getFile(2));
		s.decompressThis();
		if (ctrmap.formats.scripts.ZoneScriptAnalyzer.findSignWrapper(s) == null) {
			//no sign routine here (467 of 536 vanilla zones) - offer the vanilla
			//transplant, the same proven mechanism as the talking-NPC routine
			try {
				ctrmap.formats.scripts.GFLPawnScript donor = paletteSignDonor();
				int insCount = ctrmap.formats.scripts.SignWrapperInjector.countInjectedInstructions(s, donor);
				if (JOptionPane.showConfirmDialog(frame,
						"To make the placed sign(s) readable, this zone's script needs the vanilla\n"
						+ "sign-display routine (" + insCount + " instructions) transplanted into it.\n"
						+ "Inject it now? (Cancel keeps the signs as scenery.)",
						"Signs", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) {
					return 0;
				}
				ctrmap.formats.scripts.SignWrapperInjector.injectSignWrapper(s, donor);
				if (ctrmap.formats.scripts.ZoneScriptAnalyzer.findSignWrapper(s) == null) {
					throw new IllegalStateException("the injected routine did not verify");
				}
			} catch (RuntimeException ex) {
				JOptionPane.showMessageDialog(frame, signs.size() + " sign(s) placed as scenery only - the sign routine could not\n"
						+ "be transplanted: " + ex.getMessage(), "Signs", JOptionPane.INFORMATION_MESSAGE);
				return 0;
			}
		}
		int textID = mZonePnl.zone.header.textID;
		File sf = Workspace.getStoryTextGARC() != null
				? Workspace.getWorkspaceFile(Workspace.ArchiveType.STORYTEXT, textID) : null;
		if (sf == null || !sf.exists()) {
			JOptionPane.showMessageDialog(frame, "Signs placed as scenery only: the STORYTEXT archive is unavailable.",
					"Signs", JOptionPane.INFORMATION_MESSAGE);
			return 0;
		}
		ctrmap.formats.text.GFMessageFile msg = new ctrmap.formats.text.GFMessageFile(
				java.nio.file.Files.readAllBytes(sf.toPath()));
		ctrmap.formats.zone.ZoneEntities ent = new ctrmap.formats.zone.ZoneEntities(zo.getFile(1));
		int wired = 0;
		for (Placed pl : signs) {
			// a furniture record already on this tile = wired by an earlier Apply
			int gx = cellX(cell) * 40 + pl.tx, gy = cellY(cell) * 40 + pl.ty;
			boolean already = false;
			for (ctrmap.formats.zone.ZoneEntities.Prop fp : ent.furniture) {
				if (fp.x == gx && fp.y == gy) {
					already = true;
					break;
				}
			}
			if (already) {
				continue;
			}
			javax.swing.JTextArea ta = new javax.swing.JTextArea("", 5, 40);
			ta.setLineWrap(true);
			ta.setWrapStyleWord(true);
			javax.swing.JComboBox<String> typeBox = new javax.swing.JComboBox<>(
					ctrmap.formats.scripts.NpcTemplates.SIGN_TYPE_LABELS);
			javax.swing.JPanel panel = new javax.swing.JPanel();
			panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
			panel.add(new JLabel("Text for the " + pl.e.name + " at tile (" + pl.tx + ", " + pl.ty + ") - Cancel = scenery only:"));
			panel.add(new javax.swing.JScrollPane(ta));
			panel.add(new JLabel("Sign style:"));
			panel.add(typeBox);
			if (JOptionPane.showConfirmDialog(frame, panel, "Sign text",
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
				continue;
			}
			String text = escapeTypedText(ta.getText());
			// pre-validate the text encodes cleanly BEFORE mutating anything, so
			// one bad sign cannot discard every other sign at the final write
			try {
				ctrmap.formats.text.GFMessageFile.write(java.util.Arrays.asList(text));
			} catch (RuntimeException ex) {
				JOptionPane.showMessageDialog(frame, "This sign's text could not be encoded and was skipped:\n"
						+ ex.getMessage(), "Sign text", JOptionPane.ERROR_MESSAGE);
				continue;
			}
			int line = msg.getLineCount();
			int caseId = ctrmap.formats.scripts.NpcTemplates.addSignScript(s, line,
					ctrmap.formats.scripts.NpcTemplates.SIGN_TYPES[Math.max(0, typeBox.getSelectedIndex())]);
			msg.addLine(text);
			ent.furniture.add(ctrmap.formats.scripts.NpcTemplates.makeSignFurniture(caseId, gx, gy));
			wired++;
		}
		if (wired > 0) {
			java.nio.file.Files.write(sf.toPath(), msg.write());
			Workspace.addPersist(sf);
			ent.furnitureCount = ent.furniture.size();
			ent.modified = true;
			byte[] assembled = ent.assembleData();
			if (assembled == null || !zo.storeFile(1, assembled)) {
				throw new IllegalStateException("could not write the sign furniture");
			}
			if (!zo.storeFile(2, s.getScriptBytes())) {
				throw new IllegalStateException("could not write the sign script");
			}
		}
		return wired;
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
		final boolean[][] ramp;
		final TilePalette[] brush;
		final int[] tool;
		final ctrmap.formats.tilemap.TerrainTextures terrainTex;
		final java.util.List<Placed> placed;
		final ctrmap.formats.h3d.BuildingCatalog.Entry[] pending;
		JLabel placeStatus;
		/** Called before a mutating gesture starts (undo snapshot hook). */
		Runnable preMutate;
		boolean textured = false;
		private final java.util.Map<TilePalette, java.awt.TexturePaint> paintCache = new java.util.HashMap<>();

		GridCanvas(TilePalette[][] grid, int[][] height, boolean[][] ramp, TilePalette[] brush, int[] tool,
				ctrmap.formats.tilemap.TerrainTextures terrainTex,
				java.util.List<Placed> placed, ctrmap.formats.h3d.BuildingCatalog.Entry[] pending) {
			this.grid = grid;
			this.height = height;
			this.ramp = ramp;
			this.brush = brush;
			this.tool = tool;
			this.terrainTex = terrainTex;
			this.placed = placed;
			this.pending = pending;
			setPreferredSize(new Dimension(DIM * CELL + 1, DIM * CELL + 1));
			MouseAdapter ma = new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					if (preMutate != null) {
						preMutate.run(); // snapshot once per gesture (drags continue it)
					}
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
			boolean right = javax.swing.SwingUtilities.isRightMouseButton(e);
			// building placement mode: left click drops the pending building
			if (pending[0] != null) {
				if (!right) {
					int px = Math.max(0, Math.min(DIM - pending[0].tilesW(), tx));
					int py = Math.max(0, Math.min(DIM - pending[0].tilesH(), ty));
					placed.add(new Placed(pending[0], px, py));
				}
				pending[0] = null; // right click cancels
				if (placeStatus != null) {
					placeStatus.setText(" ");
				}
				repaint();
				return;
			}
			// right click on a placed building removes it (ramp tool keeps its
			// own right-click meaning: clearing ramp flags)
			if (right && tool[0] != 4) {
				for (int i = placed.size() - 1; i >= 0; i--) {
					if (placed.get(i).contains(tx, ty)) {
						if (JOptionPane.showConfirmDialog(this, "Remove \"" + placed.get(i).e.name + "\"?",
								"Buildings", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
							placed.remove(i);
							repaint();
						}
						return;
					}
				}
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
				case 4:
					ramp[ty][tx] = !right; // right-click clears the ramp flag
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
			// level numbers on raised tiles + ramp markers
			g.setFont(getFont().deriveFont(9f));
			for (int ty = 0; ty < DIM; ty++) {
				for (int tx = 0; tx < DIM; tx++) {
					int h = height[ty][tx];
					if (h > 0) {
						g.setColor(Color.BLACK);
						g.drawString(String.valueOf(h), tx * CELL + 4, ty * CELL + 11);
					}
					if (ramp[ty][tx]) {
						g2.setColor(new Color(255, 210, 40));
						int cx = tx * CELL, cy = ty * CELL;
						g2.fillPolygon(new int[]{cx + 2, cx + CELL - 2, cx + CELL / 2},
								new int[]{cy + CELL - 3, cy + CELL - 3, cy + 3}, 3);
						g2.setColor(new Color(120, 90, 0));
						g2.drawPolygon(new int[]{cx + 2, cx + CELL - 2, cx + CELL / 2},
								new int[]{cy + CELL - 3, cy + CELL - 3, cy + 3}, 3);
					}
				}
			}
			// placed buildings/decor: translucent footprint + name + door marker
			for (Placed p : placed) {
				int x = p.tx * CELL, y = p.ty * CELL;
				int w = p.e.tilesW() * CELL, h = p.e.tilesH() * CELL;
				g2.setColor(new Color(70, 60, 160, 70));
				g2.fillRect(x, y, w, h);
				g2.setColor(new Color(60, 50, 140));
				g2.drawRect(x, y, w - 1, h - 1);
				g2.setFont(getFont().deriveFont(java.awt.Font.BOLD, 10f));
				g2.setColor(Color.WHITE);
				g2.drawString(p.e.name, x + 3, y + 12);
				if (p.e.doorDX >= 0) {
					int dx = (p.tx + p.e.doorDX) * CELL, dy2 = (p.ty + p.e.doorDY) * CELL;
					g2.setColor(new Color(255, 140, 40));
					g2.fillRect(dx + 3, dy2 + 3, CELL - 6, CELL - 6);
				}
			}
		}
	}
}
