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

	static final int DIM = PaintedRegionBuilder.DIM;

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

	static boolean usesWater(TilePalette[][] grid) {
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
	static java.util.List<String> zoneRegionModels() {
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
	static ctrmap.formats.containers.AD areaContainer(int areaId) throws Exception {
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
	static boolean zoneWaterScrolls(int areaId) {
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
	static int enableWaterScroll(int areaId) throws Exception {
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
	static void stampPlaced(RegionFactory.BlankContent bc, java.util.List<Placed> placed,
			int[][] height, java.util.Map<Integer, java.util.Set<String>> texNeeds) {
		stampPlaced(bc, placed, height, null, texNeeds);
	}

	/** As above; {@code floorY} is the painted-floor frame (composite retail
	 *  heights) so buildings sit ON the ground, not at level*STEP absolute. */
	static void stampPlaced(RegionFactory.BlankContent bc, java.util.List<Placed> placed,
			int[][] height, float[][] floorY, java.util.Map<Integer, java.util.Set<String>> texNeeds) {
		for (Placed pl : placed) {
			ctrmap.formats.h3d.MapPrefab p = BuildingPaletteDialog.cachedPrefab(pl.e);
			if (p == null) {
				throw new IllegalStateException("could not cut \"" + pl.e.name + "\" from the dump");
			}
			// plant the piece on the terrain at its anchor: donors sit at their
			// own base height (a gym floats at -18, a palm at +46 over a beach
			// patch), so dy re-bases them onto this tile's ground level
			float ground = floorY != null ? floorY[pl.ty][pl.tx]
					: (height != null ? height[pl.ty][pl.tx] : 0) * PaintedRegionBuilder.STEP;
			float dy = ground - pl.e.baseY;
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

	/**
	 * Applies the painted edits to the zone. With a {@code touched} mask this is
	 * a COMPOSITE edit: only the touched tiles of the zone's first map cell are
	 * regenerated and the rest of the region - baked walls, fountains, props,
	 * collision, movement bytes - is preserved; without one, the full
	 * from-scratch rebuild is written into every region. The zone's geometry is
	 * forked first ONLY when it is still shared (re-applying to an
	 * already-private zone reuses its own regions instead of orphaning another
	 * archive append).
	 */
	static void applyToZone(int zoneIndex, TilePalette[][] grid, int[][] height, boolean[][] ramp,
			ctrmap.formats.tilemap.TerrainLighting lighting, boolean edges, java.util.List<Placed> placed,
			boolean[][] touched) throws Exception {
		final boolean composite = touched != null;
		int sharers = GeometryForker.matrixSharers(zoneIndex);
		GeometryForker.ForkResult r = sharers > 0
				? GeometryForker.forkGeometry(zoneIndex)
				: GeometryForker.currentGeometry(zoneIndex);
		java.util.Map<Integer, java.util.Set<String>> texNeeds = new java.util.LinkedHashMap<>();
		//the shared height frame for buildings/door props/warps: the painted
		//floors' actual Y (retail-surface-relative in composite mode)
		float[][] floorY = null;
		if (composite) {
			for (int newRegion : r.newRegions) {
				File f = Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, newRegion);
				if (f != null) {
					GR gr = new GR(f);
					if (BchMapModel.isMapModel(gr.getFile(1))) {
						floorY = PaintedRegionBuilder.floorYGrid(gr.getFile(2), height);
						break;
					}
				}
			}
		}
		if (floorY == null) {
			floorY = new float[TilePainterForm.DIM][TilePainterForm.DIM];
			for (int ty = 0; ty < TilePainterForm.DIM; ty++) {
				for (int tx = 0; tx < TilePainterForm.DIM; tx++) {
					floorY[ty][tx] = height[ty][tx] * PaintedRegionBuilder.STEP;
				}
			}
		}
		// the swinging-door props for placed buildings (registry + textures handled)
		StringBuilder propNote = new StringBuilder();
		byte[] doorProps = placed.isEmpty() ? null : buildDoorProps(placed, floorY, propNote);
		boolean firstCell = true;
		for (int newRegion : r.newRegions) {
			File f = Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, newRegion);
			if (f == null) {
				continue;
			}
			GR gr = new GR(f);
			byte[] donor = gr.getFile(1);
			if (!BchMapModel.isMapModel(donor)) {
				continue;
			}
			if (composite && !firstCell) {
				break; // the paint grid covers only the first cell - leave the rest alone
			}
			RegionFactory.BlankContent bc = composite
					? PaintedRegionBuilder.buildComposite(donor, gr.getFile(2), gr.getFile(0), grid, height, ramp, touched, lighting, edges)
					: PaintedRegionBuilder.build(donor, grid, height, ramp, lighting, edges);
			if (!placed.isEmpty()) {
				stampPlaced(bc, placed, height, floorY, texNeeds);
			}
			gr.storeFile(1, bc.model);
			gr.storeFile(2, bc.collision);
			gr.storeFile(0, bc.tilemap);
			// door props carry ABSOLUTE world coords of the FIRST map cell (where
			// the warps also go) - storing them into every region would stack
			// engine-visible duplicates at that one location
			if (composite) {
				// preserve the region's existing props; only merge new door props in
				if (firstCell && doorProps != null) {
					gr.storeFile(3, mergeProps(gr, doorProps));
				}
			} else {
				gr.storeFile(3, (firstCell && doorProps != null) ? doorProps : bc.props);
			}
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
				wired = wireDoorWarps(zoneIndex, placed, floorY, mode == 0, wireNote);
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

	/**
	 * Merges freshly built door props into a region's EXISTING prop list
	 * (composite apply must not wipe the retail props). Duplicates - same model
	 * at the same spot, e.g. from a re-apply - are skipped.
	 */
	static byte[] mergeProps(GR gr, byte[] doorProps) {
		ctrmap.formats.propdata.GRPropData existing = new ctrmap.formats.propdata.GRPropData(gr);
		ctrmap.formats.propdata.GRPropData incoming = new ctrmap.formats.propdata.GRPropData();
		try {
			ctrmap.LittleEndianDataInputStream dis = new ctrmap.LittleEndianDataInputStream(
					new java.io.ByteArrayInputStream(doorProps));
			int n = dis.readInt();
			for (int i = 0; i < n; i++) {
				incoming.props.add(new ctrmap.formats.propdata.GRProp(dis));
			}
			dis.close();
		} catch (java.io.IOException ex) {
			return doorProps; // unparseable merge input - fall back to the new props alone
		}
		for (ctrmap.formats.propdata.GRProp p : incoming.props) {
			boolean dup = false;
			for (ctrmap.formats.propdata.GRProp e : existing.props) {
				if (e.uid == p.uid && e.x == p.x && e.z == p.z) {
					e.y = p.y; //same prop, possibly re-based (tile raised/lowered)
					dup = true;
					break;
				}
			}
			if (!dup) {
				existing.props.add(p);
			}
		}
		return existing.assemblePropData();
	}

	/** Seeds the grid from the region's existing tilemap tuples (reverse lookup). */
	static void loadFromRegion(int region, TilePalette[][] grid) {
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

	static TilePalette terrainOf(int b0, int b1, int b2, int b3) {
		for (TilePalette t : TilePalette.values()) {
			if ((t.tuple[0] & 0xFF) == b0 && (t.tuple[1] & 0xFF) == b1
					&& (t.tuple[2] & 0xFF) == b2 && (t.tuple[3] & 0xFF) == b3) {
				return t;
			}
		}
		// unknown tuple: treat impassable as rock/void, else walkable ground
		return (b0 & 1) == 1 ? TilePalette.VOID : TilePalette.GRASS;
	}

	static int firstRegion() {
		int[] c = firstRegionCell();
		return c == null ? -1 : c[0];
	}

	/** {regionId, cellX, cellY} of the zone's first map cell (the painted one), or null. */
	static int[] firstRegionCell() {
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
	static byte[] buildDoorProps(java.util.List<Placed> placed, float[][] floorY, StringBuilder note) {
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
				p.y = floorY[pl.ty][pl.tx];
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
	static int ensureDoorPropRegistered(String propModelName) throws Exception {
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
	static int wireDoorWarps(int zoneIndex, java.util.List<Placed> placed, float[][] floorY,
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
			w.z = Math.round(floorY[pl.ty][pl.tx]);
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
					mZonePnl.clearForkDecline(slot); //the slot holds a new zone now
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

	static int cellX(int[] cell) {
		return cell[1];
	}

	static int cellY(int[] cell) {
		return cell[2];
	}

	/** The loaded zone's LIVE ZO container when it is this zone (keeps its
	 *  cached subfile offsets coherent for the open editors), else a fresh one. */
	static ctrmap.formats.containers.ZO zoneContainer(int zoneIndex) throws Exception {
		if (mZonePnl != null && mZonePnl.zone != null && mZonePnl.zoneIndex == zoneIndex && mZonePnl.zone.file != null) {
			return mZonePnl.zone.file;
		}
		return new ctrmap.formats.containers.ZO(Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, zoneIndex));
	}

	static String escapeTypedText(String text) {
		return text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n");
	}

	/** A validated sign-routine donor script from the workspace ZoneData. */
	static ctrmap.formats.scripts.GFLPawnScript paletteSignDonor() {
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
	static int wireSigns(int zoneIndex, java.util.List<Placed> placed) throws Exception {
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

	static Color textOn(Color c) {
		double lum = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
		return lum > 140 ? Color.BLACK : Color.WHITE;
	}

	static int u16(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
	}

	static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}

	/** The 40x40 paint grid canvas. */
}
