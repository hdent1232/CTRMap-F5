package ctrmap.humaninterface;

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
	private GeoBoxOps.Box box;

	private static class Snapshot {

		byte[] model;
		Map<Integer, byte[]> coll;
		List<int[]> tiles;      // {x, y, b0..b3} per changed tile
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

	public GeoEditForm() {
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
		String name = JOptionPane.showInputDialog(this, "Prefab name:", "e.g. PokeCenter");
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
			if (mZonePnl != null && mZonePnl.zone != null) {
				p.donorArea = mZonePnl.zone.header.areadataID; //for cross-area texture carry
			}
			clipboard = p;
			StringBuilder mats = new StringBuilder();
			for (ctrmap.formats.h3d.MapPrefab.Piece piece : p.pieces) {
				if (mats.length() > 0) {
					mats.append(", ");
				}
				mats.append(piece.material);
			}
			int save = JOptionPane.showConfirmDialog(this,
					"Copied \"" + p.name + "\": " + p.pieces.size() + " pieces, " + p.collTris.size()
					+ " collision tris, " + p.tilesW + "x" + p.tilesH + " tiles.\nMaterials: " + mats
					+ (p.facesDropped == 0 ? "" : "\n\n" + p.facesDropped + " face(s) crossing the selection edge were left out"
					+ (p.materialsLost.isEmpty() ? "" : " - " + p.materialsLost + " lost entirely")
					+ ".\nWiden the selection to take them.")
					+ "\n\nAlso save it as a .ctrprefab file (reusable across sessions)?",
					"Copy prefab", JOptionPane.YES_NO_OPTION);
			if (save == JOptionPane.YES_OPTION) {
				javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
				fc.setSelectedFile(new java.io.File(p.name.replaceAll("[^A-Za-z0-9_-]", "_") + ".ctrprefab"));
				if (fc.showSaveDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION) {
					p.save(fc.getSelectedFile());
				}
			}
			status.setText("Prefab \"" + p.name + "\" on the clipboard - select tiles elsewhere and Stamp.");
		} catch (Exception ex) {
			status.setText("Copy failed: " + ex.getMessage());
		}
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
		if (p == null || JOptionPane.showConfirmDialog(this,
				(p == null ? "No prefab on the clipboard - load a .ctrprefab file?"
						: "Stamp \"" + p.name + "\" here? (No = load a .ctrprefab file instead)"),
				"Stamp prefab", JOptionPane.YES_NO_OPTION) == JOptionPane.NO_OPTION) {
			javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
			if (fc.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
				return;
			}
			try {
				p = ctrmap.formats.h3d.MapPrefab.load(fc.getSelectedFile());
				clipboard = p;
			} catch (Exception ex) {
				status.setText("Could not load the prefab: " + ex.getMessage());
				return;
			}
		}
		if (p == null) {
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
				Tilemap tm = mTileMapPanel.getRegionForTile(selTx0, selTy0);
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
					tileNote = " +" + r.tilesStamped + " tiles";
				}
			}
			//cross-area texture carry: injected materials reference the DONOR area's
			//textures - import any the target area's packs lack, or the game hardlocks
			String texNote = "";
			if (!r.texturesNeeded.isEmpty() && p.donorArea >= 0
					&& mZonePnl != null && mZonePnl.zone != null
					&& mZonePnl.zone.header.areadataID != p.donorArea) {
				try {
					texNote = carryTextures(p.donorArea, mZonePnl.zone.header.areadataID, r.texturesNeeded);
				} catch (Exception ex) {
					texNote = "  TEXTURE CARRY FAILED (" + ex.getMessage() + ") - the stamped pieces may hardlock; undo if unsure!";
				}
			}
			undo.push(snap);
			currentModel = r.newModel;
			unsaved = true;
			mTileMapPanel.reloadRegionModel(cellX, cellY, currentModel);
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
		if (mTileMapPanel.mm != null) {
			if (mTileMapPanel.mm.ids.get(cx, cy) == -1) {
				selLabel.setText("That cell has no map region.");
				return;
			}
			target = mTileMapPanel.mm.regions.get(cx, cy);
			rid = mTileMapPanel.mm.ids.get(cx, cy);
		} else if (mTileMapPanel.mainGR != null) {
			target = mTileMapPanel.mainGR;
			cx = 0;
			cy = 0;
		}
		if (target == null) {
			selLabel.setText("Load a map first.");
			return;
		}
		if (target != gr) {
			if (unsaved && JOptionPane.showConfirmDialog(this,
					"You have unsaved geometry edits in region " + regionId + ".\nDiscard them?",
					"Map geometry", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
				return;
			}
			gr = target;
			regionId = rid;
			cellX = cx;
			cellY = cy;
			currentModel = gr.getFile(1);
			currentColl.clear();
			for (int cs : collSubfiles(gr)) {
				byte[] cb = gr.getFile(cs);
				if (GfColl.isColl(cb)) {
					currentColl.put(cs, cb);
				}
			}
			undo.clear();
			unsaved = false;
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
		mTileMapPanel.repaint();
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

			undo.push(snap);
			currentModel = result;
			unsaved = true;
			mTileMapPanel.reloadRegionModel(cellX, cellY, currentModel);
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
		Tilemap tm = mTileMapPanel.getRegionForTile(selTx0, selTy0);
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
	}

	private void refreshTiles(Tilemap tm) {
		tm.updateImage();
		mTileMapPanel.perfScale(mTileMapPanel.tilemapScale, cellX, cellY);
	}

	/**
	 * Imports textures the stamped materials need from the donor area's packs
	 * into the target area's (AreaData file 11, the world pack). Returns a
	 * status fragment.
	 */
	private String carryTextures(int donorArea, int targetArea, List<String> needed) throws Exception {
		//pass the loaded zone's LIVE areadata when it covers the target, so its
		//cached subfile offsets stay coherent when the pack grows
		ctrmap.formats.containers.AD live = (mZonePnl != null && mZonePnl.zone != null
				&& mZonePnl.zone.header != null && mZonePnl.zone.header.areadata != null
				&& mZonePnl.zone.header.areadataID == targetArea) ? mZonePnl.zone.header.areadata : null;
		//the zone being edited is the panel's loaded one here; the guard needs it
		//so it does not read this zone's own row as another map depending on the area
		int editingZone = mZonePnl != null ? mZonePnl.zoneIndex : -1;
		return ctrmap.formats.h3d.BchTexturePack.carryToArea(donorArea, targetArea, needed, live, editingZone);
	}

	private void undo() {
		if (undo.isEmpty()) {
			return;
		}
		Snapshot snap = undo.pop();
		currentModel = snap.model;
		currentColl.clear();
		currentColl.putAll(snap.coll);
		Tilemap tm = mTileMapPanel.getRegionForTile(cellX * 40, cellY * 40);
		if (tm != null && !snap.tiles.isEmpty()) {
			//restore in reverse so overlapping records unwind correctly
			for (int i = snap.tiles.size() - 1; i >= 0; i--) {
				int[] r = snap.tiles.get(i);
				tm.setTileData(r[0], r[1], new byte[]{(byte) r[2], (byte) r[3], (byte) r[4], (byte) r[5]});
			}
			refreshTiles(tm);
		}
		unsaved = !undo.isEmpty();
		mTileMapPanel.reloadRegionModel(cellX, cellY, currentModel);
		status.setText("Undone.");
		updateEnabled();
	}

	private void save() {
		if (gr == null || currentModel == null || !unsaved) {
			return;
		}
		if (!gr.storeFile(1, currentModel)) {
			status.setText("Could not write the model.");
			return;
		}
		for (Map.Entry<Integer, byte[]> e : currentColl.entrySet()) {
			gr.storeFile(e.getKey(), e.getValue());
		}
		Tilemap tm = mTileMapPanel.getRegionForTile(cellX * 40, cellY * 40);
		if (tm != null && tm.modified) {
			gr.storeFile(0, tm.assembleTilemap());
			tm.modified = false;
		}
		if (Workspace.valid && gr.getOriginFile() != null
				&& gr.getOriginFile().getAbsolutePath().startsWith(new java.io.File(Workspace.WORKSPACE_PATH).getAbsolutePath())) {
			Workspace.addPersist(gr.getOriginFile());
		}
		unsaved = false;
		status.setText("Saved. Deploy to emulator to see it in game.");
		updateEnabled();
	}

	/** True when it is safe to switch tools/zones (offers to save first). */
	public boolean store(boolean dialog) {
		if (!unsaved) {
			return true;
		}
		int r = dialog ? JOptionPane.showConfirmDialog(this,
				"Save the geometry edits in region " + regionId + "?", "Map geometry",
				JOptionPane.YES_NO_CANCEL_OPTION) : JOptionPane.YES_OPTION;
		if (r == JOptionPane.CANCEL_OPTION) {
			return false;
		}
		if (r == JOptionPane.YES_OPTION) {
			save();
		} else {
			unsaved = false;
		}
		return true;
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
