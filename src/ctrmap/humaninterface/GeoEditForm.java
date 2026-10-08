package ctrmap.humaninterface;

import ctrmap.LoadedZone;
import ctrmap.Workspace;
import ctrmap.formats.containers.GR;
import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.GeoBoxOps;
import ctrmap.formats.tilemap.Tilemap;
import java.awt.Cursor;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import static ctrmap.CtrmapMainframe.*;

/**
 * Side panel of the Geometry tool - the in-app map editor. Drag a tile
 * rectangle in the map view, then Move / Duplicate / Delete everything standing
 * on it. An edit is TRANSACTIONAL across the three data layers that make up a
 * map so they never desync:
 * <ul>
 * <li>the visual 3D model (corpus-validated {@link GeoBoxOps} primitives,
 *     previewed live in the 3D view),</li>
 * <li>the collision heightmap - all layers - via the retail-exact
 *     {@link GfColl} writer (so you actually stand on moved floors),</li>
 * <li>the movement tilemap: tile tuples are copied to the destination on a
 *     tile-aligned move/duplicate, and voided on delete.</li>
 * </ul>
 * Everything is undoable and only touches the workspace on Save.
 */
public class GeoEditForm extends JPanel {

	//selection, in GLOBAL tiles (across the matrix) for the overlay
	public int selTx0 = -1, selTy0 = -1, selTx1 = -1, selTy1 = -1;

	//the region being edited
	private int cellX = -1, cellY = -1;
	private int regionId = -1;
	private GR gr;
	private byte[] currentModel;
	private final Map<Integer, byte[]> currentColl = new HashMap<>();
	private boolean unsaved = false;
	private final Deque<Snapshot> undo = new ArrayDeque<>();
	/**
	 * Each tile this form has changed since the region was picked or saved, as it was before the
	 * first change (key x * 40 + y), and the tilemap they are in: what a discard puts back (F106).
	 * Not the undo stack, which keeps only the last UNDO_DEPTH edits.
	 */
	private final Map<Integer, byte[]> tilesAsSaved = new HashMap<>();
	private Tilemap tilesIn;
	private GeoBoxOps.Box box;

	private static class Snapshot {

		byte[] model;
		Map<Integer, byte[]> coll;
		List<int[]> tiles;      // {x, y, b0..b3} per changed tile
		/** How many texture carries were waiting before this edit, or -1 for an edit that adds none. */
		int carries = -1;
	}

	/**
	 * A texture import a stamp needs, waiting for Save (F101). The stamp used to write it into the
	 * area at once - while the class comment promised the workspace is touched only on Save - so
	 * Undo and Discard left the imported textures in the area's pack.
	 */
	private static final class Carry {

		final int donorArea;
		final int targetArea;
		final List<String> needed;

		Carry(int donorArea, int targetArea, List<String> needed) {
			this.donorArea = donorArea;
			this.targetArea = targetArea;
			this.needed = needed;
		}
	}

	private final List<Carry> pendingCarries = new ArrayList<>();

	/**
	 * How many edits Undo keeps. It kept every one, each a whole copy of the region model and its
	 * collision, for as long as the session lasted (F101).
	 */
	static final int UNDO_DEPTH = 32;

	/** Pushes an edit's snapshot, letting the oldest go past the depth. */
	private void remember(Snapshot snap) {
		undo.push(snap);
		while (undo.size() > UNDO_DEPTH) {
			undo.removeLast();
		}
	}

	private final JLabel selLabel = new JLabel("Drag on the map to select tiles.");
	private final JLabel statsLabel = new JLabel(" ");
	private final JSpinner dx = new JSpinner(new SpinnerNumberModel(0.0, -20000.0, 20000.0, 18.0));
	private final JSpinner dy = new JSpinner(new SpinnerNumberModel(0.0, -20000.0, 20000.0, 9.0));
	private final JSpinner dz = new JSpinner(new SpinnerNumberModel(0.0, -20000.0, 20000.0, 18.0));
	private final JCheckBox chkColl = new JCheckBox("Also move collision (walkable heights)", true);
	private final JCheckBox chkTiles = new JCheckBox("Also update movement tiles", true);
	private final JButton btnMove = new JButton("Move by offset");
	private final JButton btnDup = new JButton("Duplicate at offset");
	private final JButton btnDel = new JButton("Delete selection");
	private final JButton btnCopyPrefab = new JButton("Copy selection as prefab...");
	private final JButton btnStampPrefab = new JButton("Stamp prefab here...");
	private final JButton btnUndo = new JButton("Undo");
	private final JButton btnSave = new JButton("Save to workspace");
	private final JLabel status = new JLabel(" ");

	/** In-session prefab clipboard (survives zone switches; files survive everything). */
	private static ctrmap.formats.h3d.MapPrefab clipboard;

	/** The zone owner this form was handed: the open zone's area, for texture carry, and its index. */
	private final LoadedZone loadedZone;

	/**
	 * The map view, handed in AS ITSELF.
	 *
	 * <p>A narrower interface was weighed and rejected for the reason
	 * {@code ToolHost.map()} already gives: this class uses enough of the panel
	 * that naming a capability per member would be a longer way of writing
	 * TileMapPanel. What matters for the standard is that it is HANDED one and
	 * could be handed a different one - which a suite now does - rather than
	 * reaching into the main window for the only one that exists.
	 */
	private final TileMapPanel map;

	public GeoEditForm(LoadedZone loadedZone, TileMapPanel map) {
		this.map = map;
		if (loadedZone == null) {
			throw new IllegalArgumentException("GeoEditForm must be handed a LoadedZone");
		}
		this.loadedZone = loadedZone;
		setBorder(BorderFactory.createTitledBorder("Map geometry"));
		setLayout(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = 0;
		c.gridwidth = 2;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(2, 4, 2, 4);
		add(selLabel, c);
		c.gridy++;
		add(statsLabel, c);
		c.gridy++;
		c.gridwidth = 1;
		add(new JLabel("Offset X (east):"), c);
		c.gridx = 1;
		add(dx, c);
		c.gridx = 0;
		c.gridy++;
		add(new JLabel("Offset Y (up):"), c);
		c.gridx = 1;
		add(dy, c);
		c.gridx = 0;
		c.gridy++;
		add(new JLabel("Offset Z (south):"), c);
		c.gridx = 1;
		add(dz, c);
		c.gridx = 0;
		c.gridy++;
		c.gridwidth = 2;
		add(chkColl, c);
		c.gridy++;
		add(chkTiles, c);
		c.gridy++;
		add(btnMove, c);
		c.gridy++;
		add(btnDup, c);
		c.gridy++;
		add(btnDel, c);
		c.gridy++;
		add(btnCopyPrefab, c);
		c.gridy++;
		add(btnStampPrefab, c);
		c.gridy++;
		c.gridwidth = 1;
		add(btnUndo, c);
		c.gridx = 1;
		add(btnSave, c);
		c.gridx = 0;
		c.gridy++;
		c.gridwidth = 2;
		add(status, c);
		c.gridy++;
		JLabel note = new JLabel("<html><i>One tile = 18 units; offsets snapped to tiles move the<br>"
				+ "movement tiles too. Collision edits use the retail-exact<br>"
				+ "rebuilder (validated on every map in the game).</i></html>");
		add(note, c);

		btnMove.addActionListener(e -> op("move"));
		btnDup.addActionListener(e -> op("dup"));
		btnDel.addActionListener(e -> op("del"));
		btnCopyPrefab.addActionListener(e -> copyPrefab());
		btnStampPrefab.addActionListener(e -> stampPrefab());
		btnUndo.addActionListener(e -> undo());
		btnSave.addActionListener(e -> save());
		updateEnabled();
	}

	/**
	 * Cuts the selection into a reusable prefab (geometry + collision + tiles),
	 * keeps it on the clipboard and optionally saves a .ctrprefab file - the
	 * "take this building" half of building maps out of the game's own pieces.
	 */
	private void copyPrefab() {
		if (box == null || gr == null) {
			return;
		}
		String name = (String) ctrmap.Ui.input(this, "Prefab name:", "Input", JOptionPane.QUESTION_MESSAGE, null, "e.g. PokeCenter");
		if (name == null || name.trim().isEmpty()) {
			return;
		}
		try {
			ctrmap.formats.h3d.MapPrefab p = ctrmap.formats.h3d.MapPrefab.extract(gr,
					selTx0 - cellX * 40, selTy0 - cellY * 40, selTx1 - cellX * 40, selTy1 - cellY * 40, name.trim());
			if (p == null) {
				status.setText("Nothing inside the selection to copy.");
				return;
			}
			p.sourceRegion = regionId;
			if (loadedZone.isOpen()) {
				p.donorArea = loadedZone.open().header.areadataID; //for cross-area texture carry
			}
			clipboard = p;
			StringBuilder mats = new StringBuilder();
			for (ctrmap.formats.h3d.MapPrefab.Piece piece : p.pieces) {
				if (mats.length() > 0) {
					mats.append(", ");
				}
				mats.append(piece.material);
			}
			int save = ctrmap.Ui.confirm(this,
					"Copied \"" + p.name + "\": " + p.pieces.size() + " pieces, " + p.collTris.size()
					+ " collision tris, " + p.tilesW + "x" + p.tilesH + " tiles.\nMaterials: " + mats
					+ (p.facesDropped == 0 ? "" : "\n\n" + p.cutReport())
					+ "\n\nAlso save it as a .ctrprefab file (reusable across sessions)?",
					"Copy prefab", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (save == JOptionPane.YES_OPTION) {
				javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
				fc.setSelectedFile(new java.io.File(p.name.replaceAll("[^A-Za-z0-9_-]", "_") + ".ctrprefab"));
				if (ctrmap.Ui.choose(this, fc, true) != null) {
					p.save(fc.getSelectedFile());
				}
			}
			status.setText("Prefab \"" + p.name + "\" on the clipboard - select tiles elsewhere and Stamp.");
		} catch (Exception ex) {
			//a refused cut - faces across the selection, none inside - is the
			//answer to the click, not a footnote for the status bar
			ctrmap.Ui.error(this, "Copy failed: " + ex.getMessage(), "Copy prefab");
		}
	}

	/**
	 * Loads a .ctrprefab file, warning again about the faces its cut left out.
	 *
	 * <p>What the cut left out is a property of the prefab: the file carries it
	 * now, so the warning the copy dialog gave once is given again to whoever
	 * stamps it in a later session - it is the only place that session hears
	 * that the shape it is about to stamp is missing pieces.
	 *
	 * <p>A method of its own because behind the file chooser that leads here
	 * nothing could ever see the warning: a headless suite cannot open a
	 * chooser, so deleting the line left the whole battery green.
	 */
	public static ctrmap.formats.h3d.MapPrefab loadPrefabFile(java.awt.Component parent, java.io.File f) throws Exception {
		ctrmap.formats.h3d.MapPrefab p = ctrmap.formats.h3d.MapPrefab.load(f);
		if (p.facesDropped > 0) {
			ctrmap.Ui.message(parent, "Loaded \"" + p.name + "\".\n\n" + p.cutReport(),
					"Stamp prefab", JOptionPane.WARNING_MESSAGE);
		}
		return p;
	}

	/**
	 * Stamps the clipboard prefab (or a .ctrprefab file) with its anchor at the
	 * selection's top-left tile, using the Y offset spinner for height.
	 */
	private void stampPrefab() {
		if (box == null || gr == null || currentModel == null) {
			return;
		}
		ctrmap.formats.h3d.MapPrefab p = clipboard;
		if (p == null || ctrmap.Ui.confirm(this,
				(p == null ? "No prefab on the clipboard - load a .ctrprefab file?"
						: "Stamp \"" + p.name + "\" here? (No = load a .ctrprefab file instead)"),
				"Stamp prefab", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.NO_OPTION) {
			javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
			if (ctrmap.Ui.choose(this, fc, false) == null) {
				return;
			}
			try {
				p = loadPrefabFile(this, fc.getSelectedFile());
				clipboard = p;
			} catch (Exception ex) {
				status.setText("Could not load the prefab: " + ex.getMessage());
				return;
			}
		}
		stampHere(p);
	}

	/**
	 * Stamps a prefab the user has already chosen, and says what landed.
	 *
	 * <p>What it says is the whole account of the edit. A stamp is partial
	 * whenever the target region has no material for one of the prefab's
	 * pieces - which is most cross-region stamps - and a building missing half
	 * its pieces still occupies its whole footprint, so the map looks edited
	 * either way. "Stamped 3/36 pieces" is the only place the difference
	 * appears; without it a stamp that lost everything but the sea foam reads
	 * exactly like one that worked, and the pieces that did not land are found
	 * later, in the emulator, as an invisible wall. The same line carries the
	 * collision and tiles that went with it and the (unsaved) that says none of
	 * this is on disk yet.
	 *
	 * <p>Its own method because the only way in is a modal confirm and a file
	 * chooser, which a headless guard cannot open: behind them the status line
	 * could be deleted and the whole battery stayed green.
	 */
	public void stampHere(ctrmap.formats.h3d.MapPrefab p) {
		if (p == null || box == null || gr == null || currentModel == null) {
			return;
		}
		float fy = ((Number) dy.getValue()).floatValue();
		int anchorX = selTx0 - cellX * 40, anchorY = selTy0 - cellY * 40;
		setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
		try {
			Snapshot snap = new Snapshot();
			snap.model = currentModel;
			snap.coll = new HashMap<>(currentColl);
			snap.tiles = new ArrayList<>();

			ctrmap.formats.h3d.MapPrefab.StampResult r = p.stampGeometry(currentModel, anchorX, anchorY, fy);
			if (r.stamped.isEmpty()) {
				status.setText("No piece could be stamped - this region shares no materials with the prefab."
						+ (r.missingMaterials.isEmpty() ? "" : " Missing: " + r.missingMaterials));
				return;
			}
			BchMapModel check = new BchMapModel(r.newModel);
			if (!check.validate().isEmpty()) {
				status.setText("Stamp failed validation - not applied.");
				return;
			}
			if (chkColl.isSelected() && currentColl.containsKey(2)) {
				p.stampCollision(r, currentColl.get(2), anchorX, anchorY, fy);
				currentColl.put(2, r.newColl);
			}
			String tileNote = "";
			if (chkTiles.isSelected() && p.tiles != null) {
				Tilemap tm = map.getRegionForTile(selTx0, selTy0);
				if (tm != null) {
					for (int y = 0; y < p.tilesH; y++) {
						for (int x = 0; x < p.tilesW; x++) {
							int nx = anchorX + x, ny = anchorY + y;
							if (nx >= 0 && nx < 40 && ny >= 0 && ny < 40) {
								recordTile(snap.tiles, tm, nx, ny);
							}
						}
					}
					p.stampTiles(r, tm, anchorX, anchorY);
					refreshTiles(tm);
					//every tuple, whatever it does: say what kinds went in
					tileNote = " +" + r.tilesStamped + " tiles (" + ctrmap.formats.h3d.MapPrefab.StampResult.tally(r.tilesWritten) + ")";
				}
			}
			//cross-area texture carry: injected materials reference the DONOR area's
			//textures - import any the target area's packs lack, or the game hardlocks
			String texNote = "";
			if (!r.texturesNeeded.isEmpty() && p.donorArea >= 0
					&& loadedZone.isOpen()
					&& loadedZone.open().header.areadataID != p.donorArea) {
				//RECORDED, NOT WRITTEN (F101): it goes into the area on Save, with the region, and an
				//Undo of this stamp takes it back with the geometry
				snap.carries = pendingCarries.size();
				pendingCarries.add(new Carry(p.donorArea, loadedZone.open().header.areadataID,
						new ArrayList<>(r.texturesNeeded)));
				texNote = "  " + r.texturesNeeded.size() + " texture(s) from area " + p.donorArea + " will be imported on Save";
			}
			remember(snap);
			currentModel = r.newModel;
			unsaved = true;
			map.reloadRegionModel(cellX, cellY, currentModel);
			status.setText("Stamped " + r.stamped.size() + "/" + p.pieces.size() + " pieces"
					+ (r.collTrisAdded > 0 ? " +" + r.collTrisAdded + " collision tris" : "") + tileNote + texNote
					+ (r.missingMaterials.isEmpty() ? "" : "  (skipped: " + r.missingMaterials.size() + " piece(s), see log)")
					+ "  (unsaved)");
			if (!r.missingMaterials.isEmpty()) {
				System.out.println("Prefab stamp skipped pieces: " + r.missingMaterials);
			}
		} catch (RuntimeException ex) {
			status.setText("Stamp failed: " + ex.getMessage());
		} finally {
			setCursor(Cursor.getDefaultCursor());
			updateEnabled();
		}
	}

	/** Tool callback: a tile-rect was dragged (GLOBAL tile coords, any corner order). */
	public void setSelection(int tx0, int ty0, int tx1, int ty1) {
		if (tx0 < 0 || ty0 < 0) {
			return;
		}
		//v1: the selection lives in ONE region cell - clamp to the anchor's cell
		int cx = tx0 / 40, cy = ty0 / 40;
		tx1 = Math.max(cx * 40, Math.min(cx * 40 + 39, tx1));
		ty1 = Math.max(cy * 40, Math.min(cy * 40 + 39, ty1));
		selTx0 = Math.min(tx0, tx1);
		selTy0 = Math.min(ty0, ty1);
		selTx1 = Math.max(tx0, tx1);
		selTy1 = Math.max(ty0, ty1);

		GR target = null;
		int rid = -1;
		if (map.mm != null) {
			if (map.mm.ids.get(cx, cy) == -1) {
				selLabel.setText("That cell has no map region.");
				return;
			}
			target = map.mm.regions.get(cx, cy);
			rid = map.mm.ids.get(cx, cy);
		} else if (map.mainGR != null) {
			target = map.mainGR;
			cx = 0;
			cy = 0;
		}
		if (target == null) {
			selLabel.setText("Load a map first.");
			return;
		}
		if (target != gr) {
			if (unsaved && ctrmap.Ui.confirm(this,
					"You have unsaved geometry edits in region " + regionId + ".\nDiscard them?",
					"Map geometry", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) {
				return;
			}
			if (unsaved) {
				//the region left behind is put back in the map view too, not only forgotten here (F106)
				discard();
			}
			gr = target;
			regionId = rid;
			cellX = cx;
			cellY = cy;
			readRegion();
		}
		//box in REGION-LOCAL world units (center-origin frame shared by model + collision)
		box = GeoBoxOps.Box.ofTiles(selTx0 - cx * 40, selTy0 - cy * 40, selTx1 - cx * 40, selTy1 - cy * 40);
		selLabel.setText("Tiles (" + selTx0 + "," + selTy0 + ")-(" + selTx1 + "," + selTy1 + ")"
				+ (regionId >= 0 ? "  [region " + regionId + "]" : ""));
		if (currentModel != null && BchMapModel.isMapModel(currentModel)) {
			try {
				GeoBoxOps.Selection q = GeoBoxOps.query(new BchMapModel(currentModel), box);
				statsLabel.setText(q.vertices + " verts, " + q.fullFaces + " faces, " + q.touchedMeshes + " meshes selected");
			} catch (RuntimeException ex) {
				statsLabel.setText("(could not inspect the model)");
			}
		} else {
			statsLabel.setText("(this region has no editable map model)");
		}
		updateEnabled();
		map.repaint();
	}

	/** The GR's collision subfile indices (multi-layer regions carry extras). */
	private static int[] collSubfiles(GR gr) {
		int count = gr.len;
		if (count >= 11) {
			return new int[]{2, 9, 10};
		}
		if (count >= 9) {
			return new int[]{2, 8};
		}
		return new int[]{2};
	}

	private void op(String kind) {
		if (box == null || currentModel == null || !BchMapModel.isMapModel(currentModel)) {
			return;
		}
		float fx = ((Number) dx.getValue()).floatValue();
		float fy = ((Number) dy.getValue()).floatValue();
		float fz = ((Number) dz.getValue()).floatValue();
		if (("move".equals(kind) || "dup".equals(kind)) && fx == 0 && fy == 0 && fz == 0) {
			status.setText("Set a non-zero offset first.");
			return;
		}
		setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
		try {
			//visual model
			byte[] result;
			switch (kind) {
				case "move":
					result = GeoBoxOps.move(currentModel, box, fx, fy, fz);
					break;
				case "dup":
					result = GeoBoxOps.duplicate(currentModel, box, fx, fy, fz);
					break;
				default:
					result = GeoBoxOps.delete(currentModel, box);
					break;
			}
			if (java.util.Arrays.equals(result, currentModel)) {
				status.setText("Nothing inside the selection.");
				return;
			}
			BchMapModel check = new BchMapModel(result);
			if (!check.validate().isEmpty()) {
				status.setText("Edit failed validation - not applied.");
				return;
			}
			//snapshot BEFORE side effects
			Snapshot snap = new Snapshot();
			snap.model = currentModel;
			snap.coll = new HashMap<>(currentColl);
			snap.tiles = new ArrayList<>();

			//collision, every layer
			String collNote = "";
			if (chkColl.isSelected()) {
				for (Map.Entry<Integer, byte[]> e : new HashMap<>(currentColl).entrySet()) {
					byte[] nc;
					switch (kind) {
						case "move":
							nc = GfColl.moveBox(e.getValue(), box.minX, box.minZ, box.maxX, box.maxZ, fx, fy, fz);
							break;
						case "dup":
							nc = GfColl.duplicateBox(e.getValue(), box.minX, box.minZ, box.maxX, box.maxZ, fx, fy, fz);
							break;
						default:
							nc = GfColl.deleteBox(e.getValue(), box.minX, box.minZ, box.maxX, box.maxZ);
							break;
					}
					currentColl.put(e.getKey(), nc);
				}
				collNote = " +collision";
			}

			//movement tiles (layer 0)
			String tileNote = "";
			if (chkTiles.isSelected()) {
				tileNote = applyTiles(kind, fx, fz, snap.tiles);
			}

			remember(snap);
			currentModel = result;
			unsaved = true;
			map.reloadRegionModel(cellX, cellY, currentModel);
			status.setText(("move".equals(kind) ? "Moved" : "dup".equals(kind) ? "Duplicated" : "Deleted")
					+ collNote + tileNote + ".  (unsaved)");
		} catch (RuntimeException ex) {
			status.setText("Failed: " + ex.getMessage());
		} finally {
			setCursor(Cursor.getDefaultCursor());
			updateEnabled();
		}
	}

	/**
	 * Copies/voids the 4-byte tile tuples for the op. Move/dup need a
	 * tile-aligned XZ offset to know the destination cells; delete voids the
	 * selection. Every change is recorded for undo.
	 */
	private String applyTiles(String kind, float fx, float fz, List<int[]> changed) {
		Tilemap tm = map.getRegionForTile(selTx0, selTy0);
		if (tm == null) {
			return "";
		}
		int lx0 = selTx0 % 40, ly0 = selTy0 % 40, lx1 = selTx1 % 40, ly1 = selTy1 % 40;
		if ("del".equals(kind)) {
			byte[] voidTuple = {0x21, 0, 0, 1};
			for (int y = ly0; y <= ly1; y++) {
				for (int x = lx0; x <= lx1; x++) {
					recordTile(changed, tm, x, y);
					tm.setTileData(x, y, voidTuple);
				}
			}
			refreshTiles(tm);
			return " +tiles voided";
		}
		int tdx = Math.round(fx / 18f), tdy = Math.round(fz / 18f);
		if (fx != tdx * 18f || fz != tdy * 18f) {
			return " (tiles NOT updated - offset not tile-aligned)";
		}
		if (tdx == 0 && tdy == 0) {
			return ""; //pure height change - tiles unaffected
		}
		//copy the footprint's tuples to the destination (reading originals first)
		int w = lx1 - lx0 + 1, h = ly1 - ly0 + 1;
		byte[][][] src = new byte[w][h][];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				src[x][y] = tm.getTileData(lx0 + x, ly0 + y).clone();
			}
		}
		int copied = 0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int nx = lx0 + x + tdx, ny = ly0 + y + tdy;
				if (nx >= 0 && nx < 40 && ny >= 0 && ny < 40) {
					recordTile(changed, tm, nx, ny);
					tm.setTileData(nx, ny, src[x][y]);
					copied++;
				}
			}
		}
		refreshTiles(tm);
		return copied > 0 ? " +" + copied + " tiles" : "";
	}

	private void recordTile(List<int[]> changed, Tilemap tm, int x, int y) {
		byte[] b = tm.getTileData(x, y);
		changed.add(new int[]{x, y, b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF, b[3] & 0xFF});
		//as it was before this form first changed it, for a discard (F106)
		tilesIn = tm;
		tilesAsSaved.putIfAbsent(x * 40 + y, b.clone());
	}

	/**
	 * The region as its file holds it, as this form's model and collision, with nothing waiting:
	 * what a newly picked region starts from and what a discard goes back to (F106).
	 */
	private void readRegion() {
		currentModel = gr.getFile(1);
		currentColl.clear();
		for (int cs : collSubfiles(gr)) {
			byte[] cb = gr.getFile(cs);
			if (GfColl.isColl(cb)) {
				currentColl.put(cs, cb);
			}
		}
		undo.clear();
		pendingCarries.clear();
		tilesAsSaved.clear();
		tilesIn = null;
		unsaved = false;
	}

	/**
	 * Puts the region back to what was last saved (F106): the model and collision this form holds,
	 * the map view's model of the region, and every tile this form changed in the map view's tilemap.
	 * No used to clear the flag and leave all three as edited - the map view went on drawing the
	 * discarded geometry, and the tilemap stayed marked modified, so the next tile save wrote the
	 * discarded tile moves. The tilemap is left unmarked only when nothing else changed it either.
	 */
	private void discard() {
		if (gr == null) {
			unsaved = false;
			return;
		}
		Tilemap tm = tilesIn;
		Map<Integer, byte[]> tiles = new HashMap<>(tilesAsSaved);
		readRegion();
		if (tm != null && !tiles.isEmpty()) {
			for (Map.Entry<Integer, byte[]> t : tiles.entrySet()) {
				tm.setTileData(t.getKey() / 40, t.getKey() % 40, t.getValue());
			}
			tm.modified = !java.util.Arrays.equals(tm.assembleTilemap(), gr.getFile(0));
			refreshTiles(tm);
		}
		map.reloadRegionModel(cellX, cellY, currentModel);
		status.setText("Discarded.");
		updateEnabled();
	}

	private void refreshTiles(Tilemap tm) {
		tm.updateImage();
		map.perfScale(map.tilemapScale, cellX, cellY);
	}

	/**
	 * Imports textures the stamped materials need from the donor area's packs
	 * into the target area's (AreaData file 11, the world pack). Returns a
	 * status fragment.
	 */
	private String carryTextures(int donorArea, int targetArea, List<String> needed) throws Exception {
		//pass the loaded zone's LIVE areadata when it covers the target, so its
		//cached subfile offsets stay coherent when the pack grows
		ctrmap.formats.zone.Zone open = loadedZone.open();
		ctrmap.formats.containers.AD live = (open != null
				&& open.header != null && open.header.areadata != null
				&& open.header.areadataID == targetArea) ? open.header.areadata : null;
		//the zone being edited is the open one here; the guard needs it so it
		//does not read this zone's own row as another map depending on the area
		int editingZone = loadedZone.index();
		return ctrmap.formats.h3d.BchTexturePack.carryToArea(Workspace.session(), donorArea, targetArea, needed, live, editingZone);
	}

	private void undo() {
		if (undo.isEmpty()) {
			return;
		}
		Snapshot snap = undo.pop();
		while (snap.carries >= 0 && pendingCarries.size() > snap.carries) {
			pendingCarries.remove(pendingCarries.size() - 1);
		}
		currentModel = snap.model;
		currentColl.clear();
		currentColl.putAll(snap.coll);
		Tilemap tm = map.getRegionForTile(cellX * 40, cellY * 40);
		if (tm != null && !snap.tiles.isEmpty()) {
			//restore in reverse so overlapping records unwind correctly
			for (int i = snap.tiles.size() - 1; i >= 0; i--) {
				int[] r = snap.tiles.get(i);
				tm.setTileData(r[0], r[1], new byte[]{(byte) r[2], (byte) r[3], (byte) r[4], (byte) r[5]});
			}
			refreshTiles(tm);
		}
		unsaved = !undo.isEmpty();
		map.reloadRegionModel(cellX, cellY, currentModel);
		status.setText("Undone.");
		updateEnabled();
	}

	private void save() {
		if (gr == null || currentModel == null || !unsaved) {
			return;
		}
		Tilemap tm = map.getRegionForTile(cellX * 40, cellY * 40);
		String texNote = "";
		//ONE UNIT (F101): the stamps' texture imports, then the model, collision and tiles - all of
		//them or none. A carry that is refused (an area another zone shares) writes nothing at all.
		try (ctrmap.WorkspaceUnit unit = ctrmap.WorkspaceUnit.begin("Save map geometry", Workspace.session())) {
			for (Carry c : pendingCarries) {
				texNote += carryTextures(c.donorArea, c.targetArea, c.needed);
			}
			gr.storeFile(1, currentModel);
			for (Map.Entry<Integer, byte[]> e : currentColl.entrySet()) {
				gr.storeFile(e.getKey(), e.getValue());
			}
			if (tm != null && tm.modified) {
				gr.storeFile(0, tm.assembleTilemap());
			}
			if (Workspace.isValid() && gr.getOriginFile() != null
					&& gr.getOriginFile().getAbsolutePath().startsWith(new java.io.File(Workspace.WORKSPACE_PATH).getAbsolutePath())) {
				Workspace.addPersist(gr.getOriginFile());
			}
			unit.commit();
		} catch (Exception notWritten) {
			status.setText("Nothing was saved: " + ctrmap.util.Bytes.reason(notWritten));
			return;
		}
		if (tm != null) {
			tm.modified = false;
		}
		pendingCarries.clear();
		tilesAsSaved.clear();
		tilesIn = null;
		unsaved = false;
		status.setText("Saved." + texNote + " Deploy to emulator to see it in game.");
		updateEnabled();
	}

	/** True when it is safe to switch tools/zones (offers to save first). */
	public boolean store(boolean dialog) {
		if (!unsaved) {
			return true;
		}
		//THROUGH THE ONE QUESTION (F106): this asked its own, took a closed dialog for No, and its No
		//cleared the flag with the edit still drawn and the tilemap still marked modified
		switch (ctrmap.Ui.askToKeep(dialog, "Map geometry of region " + regionId, this::discard)) {
			case CANCEL:
				return false;
			case SAVE:
				save();
				if (unsaved) {
					//A SAVE THAT DID NOT HAPPEN STOPS WHAT ASKED FOR IT: it answered true, and the zone
					//switch went on over an edit only the status line said was not written
					ctrmap.Ui.error(this, status.getText(), "Map geometry");
					return false;
				}
				return true;
			default:
				return true;
		}
	}

	private void updateEnabled() {
		boolean sel = box != null && currentModel != null && BchMapModel.isMapModel(currentModel);
		btnMove.setEnabled(sel);
		btnDup.setEnabled(sel);
		btnDel.setEnabled(sel);
		btnCopyPrefab.setEnabled(sel);
		btnStampPrefab.setEnabled(sel);
		btnUndo.setEnabled(!undo.isEmpty());
		btnSave.setEnabled(unsaved);
	}

	/** Clears the selection state (tool shutdown). */
	public void clearSelection() {
		selTx0 = selTy0 = selTx1 = selTy1 = -1;
		box = null;
		updateEnabled();
	}
}
