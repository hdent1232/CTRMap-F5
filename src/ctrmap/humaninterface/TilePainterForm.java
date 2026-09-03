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
	public static class Placed {

		final ctrmap.formats.h3d.BuildingCatalog.Entry e;
		final int tx, ty;
		/** False leaves the cut's passengers - sea planes, shadow decals, floors, terrain chips - behind. */
		final boolean passengers;

		public Placed(ctrmap.formats.h3d.BuildingCatalog.Entry e, int tx, int ty) {
			this(e, tx, ty, true);
		}

		public Placed(ctrmap.formats.h3d.BuildingCatalog.Entry e, int tx, int ty, boolean passengers) {
			this.e = e;
			this.tx = tx;
			this.ty = ty;
			this.passengers = passengers;
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
	 *  subfile offsets must stay coherent when subfile 2 grows), else a fresh one.
	 *  Matched by FILE, not by id: forking a shared area repoints the loaded
	 *  zone's areadataID while its container still points at the OLD area's
	 *  file, and writing through that would grow the very area we just forked
	 *  away from. */
	static ctrmap.formats.containers.AD areaContainer(int areaId) throws Exception {
		File f = Workspace.getWorkspaceFile(Workspace.ArchiveType.AREA_DATA, areaId);
		if (mZonePnl != null && mZonePnl.zone != null && mZonePnl.zone.header != null
				&& mZonePnl.zone.header.areadata != null && f != null
				&& f.equals(mZonePnl.zone.header.areadata.getOriginFile())) {
			return mZonePnl.zone.header.areadata;
		}
		return new ctrmap.formats.containers.AD(f);
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
	 * collision, and the footprint's walls - plus a wired door's tile - over
	 * the painted movement tiles), collecting per-donor-area texture needs.
	 * Throws on any failure, a building that cannot be placed whole included,
	 * so a half-stamped map is never applied. Returns the per-building account
	 * Apply shows: pieces and triangles, height span, collision added, tiles
	 * blocked, the donor tiles kept as painted, and what rode along with the
	 * cut - or was left behind.
	 */
	public static String stampPlaced(RegionFactory.BlankContent bc, java.util.List<Placed> placed,
			int[][] height, java.util.Map<Integer, java.util.Set<String>> texNeeds) {
		return stampPlaced(bc, placed, height, null, texNeeds);
	}

	/** As above; {@code floorY} is the painted-floor frame (composite retail
	 *  heights) so buildings sit ON the ground, not at level*STEP absolute. */
	public static String stampPlaced(RegionFactory.BlankContent bc, java.util.List<Placed> placed,
			int[][] height, float[][] floorY, java.util.Map<Integer, java.util.Set<String>> texNeeds) {
		StringBuilder note = new StringBuilder();
		for (Placed pl : placed) {
			ctrmap.formats.h3d.MapPrefab whole = BuildingPaletteDialog.cachedPrefab(pl.e);
			if (whole == null) {
				throw new IllegalStateException("could not cut \"" + pl.e.name + "\" from the dump");
			}
			String riders = whole.passengerNote();
			ctrmap.formats.h3d.MapPrefab p = pl.passengers || riders.isEmpty() ? whole : whole.withoutPassengers();
			if (p.pieces.isEmpty()) {
				throw new IllegalStateException("\"" + pl.e.name + "\" is nothing but passengers (" + riders
						+ ") - place it with them, or pick something else");
			}
			// plant the piece on the terrain at its anchor: donors sit at their
			// own base height (a gym floats at -18, a palm at +46 over a beach
			// patch), so dy re-bases them onto this tile's ground level
			float ground = floorY != null ? floorY[pl.ty][pl.tx]
					: (height != null ? height[pl.ty][pl.tx] : 0) * PaintedRegionBuilder.STEP;
			float dy = ground - pl.e.baseY;
			ctrmap.formats.h3d.MapPrefab.StampResult r = p.stampGeometry(bc.model, pl.tx, pl.ty, dy);
			// a building missing pieces is not that building: refuse rather than
			// write a fragment. What landed of a skinned donor was a few triangles
			// of sea foam under a full-size invisible wall, and Apply called it done.
			if (!r.missingMaterials.isEmpty()) {
				throw new IllegalStateException("\"" + pl.e.name + "\": " + r.missingMaterials.size() + " of "
						+ p.pieces.size() + " piece(s) cannot be placed on this map: " + r.missingMaterials);
			}
			bc.model = r.newModel;
			p.stampCollision(r, bc.collision, pl.tx, pl.ty, dy);
			bc.collision = r.newColl;
			p.stampFootprint(r, bc.tilemap, pl.tx, pl.ty, pl.e.doorDX, pl.e.doorDY);
			if (texNeeds != null && !r.texturesNeeded.isEmpty()) {
				texNeeds.computeIfAbsent(pl.e.donorArea, k -> new java.util.LinkedHashSet<>()).addAll(r.texturesNeeded);
			}
			note.append("\n\"").append(pl.e.name).append("\" at (").append(pl.tx).append(", ").append(pl.ty).append("): ")
					.append(p.summary(pl.e.baseY)).append(", ")
					.append(r.collTrisAdded > 0 ? "+" + r.collTrisAdded + " collision triangle(s), " : "no collision (not walkable), ")
					.append(r.tilesStamped).append(" tile(s) blocked");
			//the donor's furniture codes are solid like its walls, but they carry
			//an interaction (a bookshelf's text, a PC) onto the user's map
			Integer objects = r.tilesWritten.get("object");
			if (objects != null) {
				note.append(" (").append(objects).append(" of them carry the donor's object codes)");
			}
			if (!r.tilesKept.isEmpty()) {
				int kept = 0;
				for (int n : r.tilesKept.values()) {
					kept += n;
				}
				note.append("; ").append(kept).append(" donor tile(s) kept as painted (")
						.append(ctrmap.formats.h3d.MapPrefab.StampResult.tally(r.tilesKept)).append(')');
			}
			if (!riders.isEmpty()) {
				note.append(pl.passengers ? "; rides along: " : "; left behind: ").append(riders);
			}
			note.append('.');
		}
		java.util.List<String> errs = new BchMapModel(bc.model).validate();
		if (!errs.isEmpty()) {
			throw new IllegalStateException("stamped model failed validation: " + errs.get(0));
		}
		return note.toString().trim();
	}

	/**
	 * The buildings an Apply is about to place, as its confirmation lists
	 * them: each one's manifest, so the size of the thing is on the screen at
	 * the moment of the decision - the palette's own label arrives after a
	 * preview thread and nothing stopped a quick "Place" from beating it.
	 */
	public static String placedSummary(java.util.List<Placed> placed) {
		StringBuilder sb = new StringBuilder();
		for (Placed pl : placed) {
			ctrmap.formats.h3d.MapPrefab p = BuildingPaletteDialog.cachedPrefab(pl.e);
			sb.append(sb.length() > 0 ? "\n" : "").append('"').append(pl.e.name).append("\" at (").append(pl.tx).append(", ").append(pl.ty).append("): ")
					.append(p == null ? "could not be cut from the dump" : BuildingPaletteDialog.manifest(p, null, pl.e)
					+ (pl.passengers || p.passengers().isEmpty() ? "" : " - passengers left behind"));
		}
		return sb.toString();
	}

	/** One region's freshly built content, held in memory until the whole Apply
	 *  has cleared every precondition. */
	private static class StagedRegion {

		int srcRegion;
		boolean firstCell;
		byte[] model, collision, tilemap, props;
	}

	/**
	 * The zone's AreaData with an Apply's edits held in memory. The door-prop
	 * registry, the door props' textures and the carried brush textures all
	 * land in the same container, so they are staged together and written only
	 * once every one of them has succeeded - and never into an area other zones
	 * share.
	 */
	public static class StagedArea {

		final int areaId;
		final int editingZone;
		final ctrmap.formats.containers.AD ad;
		final java.util.Map<Integer, byte[]> pending = new java.util.LinkedHashMap<>();
		private ctrmap.formats.propdata.ADPropRegistry registry;

		public StagedArea(int areaId, int editingZone) throws Exception {
			this.areaId = areaId;
			this.editingZone = editingZone;
			this.ad = areaContainer(areaId);
		}

		/** The subfile as this Apply will leave it: staged bytes, else disk. */
		byte[] file(int num) {
			byte[] staged = pending.get(num);
			return staged != null ? staged : ad.getFile(num);
		}

		void stage(int num, byte[] data) {
			//every area write goes through here, so the shared-area rule cannot
			//be walked around by a path that does its own storeFile
			ctrmap.formats.h3d.BchTexturePack.assertNotShared(areaId, editingZone);
			pending.put(num, data);
		}

		ctrmap.formats.propdata.ADPropRegistry registry() {
			if (registry == null) {
				registry = new ctrmap.formats.propdata.ADPropRegistry(ad, null, false);
			}
			return registry;
		}

		/** Re-stages the prop registry after an entry was added (its own write()
		 *  goes straight to disk and cannot report a failure). */
		void stageRegistry() throws java.io.IOException {
			java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
			ctrmap.LittleEndianDataOutputStream dos = new ctrmap.LittleEndianDataOutputStream(baos);
			dos.writeInt(registry().entries.size());
			for (ctrmap.formats.propdata.ADPropRegistry.ADPropRegistryEntry e : registry().entries.values()) {
				e.write(dos);
			}
			dos.close();
			stage(0, baos.toByteArray());
		}

		void commit() {
			for (java.util.Map.Entry<Integer, byte[]> e : pending.entrySet()) {
				if (!ad.storeFile(e.getKey(), e.getValue())) {
					throw new IllegalStateException("could not write area " + areaId
							+ " subfile " + e.getKey() + " (file locked or read-only?)");
				}
			}
			pending.clear();
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
	 *
	 * <p>All of it or none of it. Everything is built and every precondition
	 * cleared BEFORE the first byte is written: the regions used to be stored as
	 * they were built and the cross-area texture carry only ran afterwards, so a
	 * carry the shared-area guard refused left a half-applied untextured map in
	 * the workspace behind an "Apply failed" dialog - and the next Pack
	 * Workspace shipped it.
	 *
	 * @return exactly what the "Painted map applied" dialog says. The dialog is
	 *         the only account of what an Apply did - which extras ran, which
	 *         did not - and it was built inline inside the pack's callback,
	 *         where no suite can reach it: a headless battery never packs, so
	 *         every sentence in it was unassertable. Handing the text back makes
	 *         what the user is told a fact a guard can read.
	 */
	public static String applyToZone(int zoneIndex, TilePalette[][] grid, int[][] height, int[][] ramp,
			ctrmap.formats.tilemap.TerrainLighting lighting, boolean edges, java.util.List<Placed> placed,
			boolean[][] touched) throws Exception {
		final boolean composite = touched != null;
		//Build from the zone's CURRENT geometry; the private copy is made at
		//commit time. Forking up front repointed the zone's matrix while the
		//loaded header still named the old one, so firstRegionCell() no longer
		//found the zone's own cell and a composite Apply on a shared map skipped
		//every region and reported "Painted map applied" with nothing painted.
		GeometryForker.ForkResult src = GeometryForker.currentGeometry(zoneIndex);
		java.util.Map<Integer, java.util.Set<String>> texNeeds = new java.util.LinkedHashMap<>();
		//the shared height frame for buildings/door props/warps: the painted
		//floors' actual Y (retail-surface-relative in composite mode)
		float[][] floorY = null;
		if (composite) {
			for (int newRegion : src.newRegions) {
				File f = Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, newRegion);
				if (f != null) {
					GR gr = new GR(f);
					if (BchMapModel.isMapModel(gr.getFile(1))) {
						floorY = PaintedRegionBuilder.floorYGrid(gr.getFile(2), gr.getFile(0), height);
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
		//COMPOSITE edits exactly the zone's OWN cell. On a map shared by several
		//zones (94 retail zones are), any other cell belongs to a neighbour -
		//painting it would edit their ground and strand this zone's warps in
		//their territory.
		int[] ownCell = firstRegionCell();
		int ownRegion = ownCell != null ? ownCell[0] : -1;
		boolean firstCell = true;
		String stampNote = "";
		//painted tiles the map had no ground under: they take a walkable
		//neighbour's, which is the difference between a patch level with its
		//surroundings and one sunk into a pit, so the result says how many
		int borrowedGround = 0;
		java.util.List<StagedRegion> staged = new java.util.ArrayList<>();
		for (int region : src.newRegions) {
			File f = Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, region);
			if (f == null) {
				continue;
			}
			GR gr = new GR(f);
			byte[] donor = gr.getFile(1);
			if (!BchMapModel.isMapModel(donor)) {
				continue;
			}
			if (composite && ownRegion >= 0 && region != ownRegion) {
				continue; // not this zone's cell - leave it exactly as it is
			}
			if (composite && ownRegion < 0 && !firstCell) {
				break; // no own cell resolvable: fall back to the old first-cell behaviour
			}
			//give this map a REAL material for every brush it lacks (an indoor
			//map has no sand, a cave no grass) instead of silently painting
			//with whatever mesh happened to be biggest
			donor = importBrushMaterials(donor, grid, touched, texNeeds);
			RegionFactory.BlankContent bc = composite
					? PaintedRegionBuilder.buildComposite(donor, gr.getFile(2), gr.getFile(0), grid, height, ramp, touched, lighting, edges)
					: PaintedRegionBuilder.build(donor, grid, height, ramp, lighting, edges);
			borrowedGround += bc.borrowedGround;
			if (!placed.isEmpty()) {
				stampNote = stampPlaced(bc, placed, height, floorY, texNeeds);
			}
			StagedRegion s = new StagedRegion();
			s.srcRegion = region;
			s.firstCell = firstCell;
			s.model = bc.model;
			s.collision = bc.collision;
			s.tilemap = bc.tilemap;
			s.props = bc.props;
			staged.add(s);
			firstCell = false;
		}
		//Whose area is this? Brush textures and door props go INTO it and 77% of
		//retail zones share theirs, so the fork is offered here - once, for the
		//whole Apply, before anything is written - instead of the carry refusing
		//when half the map is already on disk.
		//Read the area from the zone we are actually painting, not from whatever
		//zone the panel happens to have loaded: the guards below exclude this
		//zone index, so the index and the area it is checked against have to
		//describe the same zone, and callers reach here with a seeded zone that
		//need not be the panel's current one.
		int zoneArea = ctrmap.AreaForker.currentArea(zoneIndex);
		if (needsAreaWrite(zoneArea, placed, texNeeds)) {
			int owned = AreaForkPrompt.ensurePrivate(frame, zoneIndex, zoneArea,
					"adding this map's brush textures and door props");
			if (owned < 0) {
				throw new IllegalStateException("Apply cancelled - nothing was changed.");
			}
			zoneArea = owned;
			String shared = ctrmap.formats.h3d.BchTexturePack.zonesUsingArea(zoneArea, zoneIndex);
			if (shared != null) {
				throw new IllegalStateException("Area " + zoneArea + " is also used by " + shared
						+ ".\nThis map's textures and door props cannot go into it without changing"
						+ "\nthose maps, so nothing was applied. Give this zone its own area"
						+ "\n(Map > Fork area) and Apply again.");
			}
		}
		StagedArea area = new StagedArea(zoneArea, zoneIndex);
		// the swinging-door props for placed buildings (registry + textures staged)
		StringBuilder propNote = new StringBuilder();
		byte[] doorProps = placed.isEmpty() ? null : buildDoorProps(placed, floorY, area, propNote);
		// stamped pieces reference their donor areas' textures - carry any the
		// zone's area lacks, or the game hardlocks on load
		StringBuilder texNote = new StringBuilder();
		for (java.util.Map.Entry<Integer, java.util.Set<String>> en : texNeeds.entrySet()) {
			if (en.getKey() == zoneArea) {
				continue;
			}
			ctrmap.formats.h3d.BchTexturePack.Carry carry = ctrmap.formats.h3d.BchTexturePack.planCarry(
					en.getKey(), zoneArea, new java.util.ArrayList<>(en.getValue()),
					area.file(11), area.file(1), zoneIndex);
			if (carry.pack != null) {
				area.stage(carry.subfile, carry.pack);
			}
			texNote.append(carry.note.trim()).append('\n');
		}
		//COMMIT. Every precondition has passed, so the writing can start: the
		//zone's map is forked here when it is still shared (re-applying to an
		//already-private zone reuses its own regions instead of orphaning
		//another archive append), and the staged bytes land in its own regions.
		GeometryForker.ForkResult r = GeometryForker.ensurePrivate(zoneIndex);
		for (StagedRegion s : staged) {
			int dest = destRegion(r, s.srcRegion);
			GR gr = new GR(Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, dest));
			boolean ok = gr.storeFile(1, s.model);
			ok &= gr.storeFile(2, s.collision);
			ok &= gr.storeFile(0, s.tilemap);
			// door props carry ABSOLUTE world coords of the FIRST map cell (where
			// the warps also go) - storing them into every region would stack
			// engine-visible duplicates at that one location
			if (composite) {
				// preserve the region's existing props; only merge new door props in
				if (s.firstCell && doorProps != null) {
					ok &= gr.storeFile(3, mergeProps(gr, doorProps));
				}
			} else {
				ok &= gr.storeFile(3, (s.firstCell && doorProps != null) ? doorProps : s.props);
			}
			if (!ok) {
				throw new IllegalStateException("could not write region " + dest + " (file locked or read-only?)");
			}
		}
		area.commit();
		int enterable = 0;
		for (Placed pl : placed) {
			if (pl.e.enterable()) {
				enterable++;
			}
		}
		int wired = 0;
		StringBuilder wireNote = new StringBuilder();
		if (enterable > 0) {
			//Past the commit the map IS applied, so an extra that cannot run is
			//a line in the result - never an "Apply failed" over a map that is
			//already on disk and will ship with the next pack.
			try {
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
			} catch (Exception ex) {
				wireNote.append("\nDoor wiring failed: ").append(ex);
			}
		}
		int signsWired = 0;
		try {
			signsWired = wireSigns(zoneIndex, placed);
		} catch (Exception ex) {
			wireNote.append("\nSign wiring failed: ").append(ex);
		}
		final String extras = (borrowedGround > 0 ? "\n\n" + borrowedGround
				+ " painted tile(s) had no ground under them and took a neighbour's"
				+ "\n(they sit level with what stands beside them, not at a height this map gave them)." : "")
				+ (stampNote.isEmpty() ? "" : "\n\n" + stampNote)
				+ (signsWired > 0 ? "\n\n" + signsWired + " readable sign(s) wired (text saved; edit later via the NPC tool's dialogue section)." : "")
				+ (texNote.length() > 0 ? "\n" + texNote.toString().trim() : "")
				+ (doorProps != null ? "\n\nSwinging-door prop(s) placed automatically (registry + textures handled)." : "")
				+ (propNote.length() > 0 ? propNote : "")
				+ (wired > 0 ? "\n\n" + wired + " door warp(s) added." : "")
				//shown whatever happened: a wiring that FAILED reported into this
				//and the old line only printed it when a warp had been added, so
				//the one message about the failure was dropped for having failed
				+ (wireNote.length() > 0 ? "\n" + wireNote.toString().trim() : "")
				+ (enterable > wired ? "\n\n" + (enterable - wired) + " door(s) left unwired - add warps with the Warp tool when ready." : "");
		final String report = "Painted map applied to zone " + zoneIndex + " (region(s) "
				+ java.util.Arrays.toString(r.newRegions) + ").\nDeploy to emulator to walk on it."
				+ extras;
		Workspace.packWorkspace(new Runnable() {
			@Override
			public void run() {
				mZonePnl.loadEverything(new Runnable() {
					@Override
					public void run() {
						mZonePnl.selectZone(zoneIndex);
						ctrmap.Ui.message(frame, report, "Tile painter", JOptionPane.INFORMATION_MESSAGE);
					}
				});
			}
		});
		return report;
	}

	/** Where a staged region's bytes belong: its private copy after a fork, or
	 *  the region itself when the zone already owned its map. */
	static int destRegion(GeometryForker.ForkResult r, int srcRegion) {
		for (int i = 0; i < r.srcRegions.length; i++) {
			if (r.srcRegions[i] == srcRegion) {
				return r.newRegions[i];
			}
		}
		return srcRegion;
	}

	/**
	 * True when applying would have to write into the zone's AreaData: a brush
	 * texture the area does not hold, or a door prop it has not registered.
	 * Asked before anything is written, so a shared area can be forked (or the
	 * Apply refused) once, up front, for the whole edit.
	 */
	public static boolean needsAreaWrite(int areaId, java.util.List<Placed> placed,
			java.util.Map<Integer, java.util.Set<String>> texNeeds) throws Exception {
		ctrmap.formats.containers.AD ad = areaContainer(areaId);
		byte[] world = ad.getFile(11), prop = ad.getFile(1);
		for (java.util.Map.Entry<Integer, java.util.Set<String>> en : texNeeds.entrySet()) {
			if (en.getKey() != areaId && !ctrmap.formats.h3d.BchTexturePack.missingIn(
					world, prop, new java.util.ArrayList<>(en.getValue())).isEmpty()) {
				return true;
			}
		}
		ctrmap.formats.propdata.PropDatabase db = ctrmap.formats.propdata.PropDatabase.get();
		if (db == null) {
			return false;
		}
		ctrmap.formats.propdata.ADPropRegistry reg = null;
		for (Placed pl : placed) {
			ctrmap.formats.propdata.PropDatabase.PropModel pm = doorPropModel(db, pl.e.doorProp);
			if (pm == null) {
				continue; //no door, or a model buildDoorProps will report as missing
			}
			if (reg == null) {
				reg = new ctrmap.formats.propdata.ADPropRegistry(ad, null, false);
			}
			boolean registered = false;
			for (ctrmap.formats.propdata.ADPropRegistry.ADPropRegistryEntry e : reg.entries.values()) {
				registered |= e.model == pm.modelIndex;
			}
			if (!registered) {
				return true;
			}
		}
		return false;
	}

	/** The BuildingModels entry a building's door prop names, or null when it
	 *  has no door or the model is not in the dump. */
	static ctrmap.formats.propdata.PropDatabase.PropModel doorPropModel(
			ctrmap.formats.propdata.PropDatabase db, String propModelName) {
		if (propModelName == null || propModelName.equals("-")) {
			return null;
		}
		for (ctrmap.formats.propdata.PropDatabase.PropModel m : db.models) {
			if (propModelName.equals(m.name)) {
				return m;
			}
		}
		return null;
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

	/**
	 * Imports a real material for every painted brush the map lacks, so any
	 * brush works on any map. Texture needs are added to {@code texNeeds} so
	 * the existing cross-area carry brings them along (a missing texture
	 * hardlocks the game). Returns the (possibly grown) model.
	 *
	 * <p>Needs are recorded whether or not THIS call did the importing. A map
	 * painted once already carries the material, so gating on "injected" meant
	 * the second Apply - the one the user makes after forking the area the
	 * first Apply refused - asked the carry for nothing and reported success
	 * over the same untextured floor. The carry no-ops cheaply when the
	 * textures are already there, so asking every time costs nothing.
	 */
	static byte[] importBrushMaterials(byte[] model, TilePalette[][] grid, boolean[][] touched,
			java.util.Map<Integer, java.util.Set<String>> texNeeds) {
		java.util.Set<TilePalette> used = new java.util.LinkedHashSet<>();
		for (int y = 0; y < DIM; y++) {
			for (int x = 0; x < DIM; x++) {
				if (touched == null || touched[y][x]) {
					TilePalette t = grid[y][x];
					if (t != null && t != TilePalette.VOID) {
						used.add(t);
					}
				}
			}
		}
		byte[] out = model;
		for (TilePalette t : used) {
			ctrmap.formats.tilemap.TerrainCatalog.ImportResult r
					= ctrmap.formats.tilemap.TerrainCatalog.ensureMaterial(out, t);
			out = r.model;
			recordNeeds(r, texNeeds);
		}
		//The generated cliff faces and the lava churn overlay come from the same
		//catalogue, but PaintedRegionBuilder imports them inside the build where
		//nothing could see what they need - and 227 of the game's 228 areas do
		//not hold the cliff donor's texture. Importing them here instead means
		//the carry hears about them; the build's own calls then find the
		//material already present and change nothing.
		ctrmap.formats.tilemap.TerrainCatalog.ImportResult cliff
				= ctrmap.formats.tilemap.TerrainCatalog.ensureCliffMaterial(out);
		out = cliff.model;
		recordNeeds(cliff, texNeeds);
		ctrmap.formats.tilemap.TerrainCatalog.ImportResult churn
				= ctrmap.formats.tilemap.TerrainCatalog.ensureChurnMaterial(out);
		out = churn.model;
		recordNeeds(churn, texNeeds);
		return out;
	}

	/** Files a catalogue import's textures under the donor area they come from. */
	static void recordNeeds(ctrmap.formats.tilemap.TerrainCatalog.ImportResult r,
			java.util.Map<Integer, java.util.Set<String>> texNeeds) {
		if (texNeeds != null && r.donorArea >= 0 && !r.texturesNeeded.isEmpty()) {
			texNeeds.computeIfAbsent(r.donorArea, k -> new java.util.LinkedHashSet<>()).addAll(r.texturesNeeded);
		}
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

	/**
	 * {regionId, cellX, cellY} of THIS ZONE'S OWN map cell - the one to paint.
	 *
	 * <p>Critically NOT simply the matrix's first occupied cell: 94 retail
	 * zones share one big map matrix with their neighbours (Petalburg City and
	 * Route 102 are both cells of matrix 2), and for those the first cell
	 * belongs to a DIFFERENT zone. Painting there edits the neighbour's ground
	 * and - worse - puts this zone's warps inside the neighbour's territory,
	 * so the game reports the neighbour's name and runs its story. The zone
	 * header carries the zone's own world position; the cell containing it is
	 * the zone's own. The first occupied cell remains the fallback for a
	 * header whose position lands outside the map.
	 */
	public static int[] firstRegionCell() {
		try {
			File mmFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.MAP_MATRIX, mZonePnl.zone.header.mapmatrixID);
			byte[] mm = java.nio.file.Files.readAllBytes(mmFile.toPath());
			int sub0 = le32(mm, 4);
			int w = u16(mm, sub0 + 4), h = u16(mm, sub0 + 6);
			//the zone's own position (X = world x, Y = world z), 720 units per cell
			int ownX = mZonePnl.zone.header.X / 720;
			int ownY = mZonePnl.zone.header.Y / 720;
			if (ownX >= 0 && ownY >= 0 && ownX < w && ownY < h) {
				int id = u16(mm, sub0 + 8 + (ownY * w + ownX) * 2);
				if (id != 0xFFFF) {
					return new int[]{id, ownX, ownY};
				}
			}
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
	static byte[] buildDoorProps(java.util.List<Placed> placed, float[][] floorY, StagedArea area, StringBuilder note) {
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
				int uid = ensureDoorPropRegistered(pl.e.doorProp, area, note);
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
	 * load. Both are STAGED into the Apply's area transaction, never written
	 * here: this used to import the textures and rewrite the registry the moment
	 * a building was placed - before anything had asked whether other zones
	 * share the area, and while the rest of the Apply could still fail. Returns
	 * the registry reference id for the GRProp uid.
	 */
	/** An area's prop texture pack as this workspace holds it: the extracted file when there is one, else the archive's. */
	static byte[] propPackOf(int area) throws Exception {
		File ws = new File(Workspace.getExtractionDirectory(Workspace.ArchiveType.AREA_DATA), String.valueOf(area));
		byte[] entry = ws.exists() ? java.nio.file.Files.readAllBytes(ws.toPath()) : Workspace.ad.getDecompressedEntry(area);
		return ctrmap.formats.propdata.PropDatabase.getSubfile(entry, 1);
	}

	public static int ensureDoorPropRegistered(String propModelName, StagedArea area, StringBuilder note) throws Exception {
		ctrmap.formats.propdata.PropDatabase db = ctrmap.formats.propdata.PropDatabase.get();
		if (db == null) {
			throw new IllegalStateException("prop database unavailable");
		}
		ctrmap.formats.propdata.PropDatabase.PropModel pm = doorPropModel(db, propModelName);
		if (pm == null) {
			throw new IllegalStateException("model \"" + propModelName + "\" not in BuildingModels");
		}
		ctrmap.formats.propdata.ADPropRegistry reg = area.registry();
		for (ctrmap.formats.propdata.ADPropRegistry.ADPropRegistryEntry e : reg.entries.values()) {
			if (e.model == pm.modelIndex) {
				return e.reference; // already registered in this area
			}
		}
		// textures first (all-or-nothing before any registry entry)
		byte[] modelBch = ctrmap.formats.propdata.PropDatabase.getSubfile(
				Workspace.bm.getDecompressedEntry(pm.modelIndex), 0);
		byte[] targetPack = area.file(1);
		java.util.Set<String> available = ctrmap.formats.propdata.PropDatabase.getTexturePackTextureNames(targetPack);
		java.util.List<String> missing = ctrmap.formats.propdata.PropDatabase.getMissingTextureNames(modelBch, available);
		//the finding's other half: a name this area ALREADY holds under other
		//pixels. Nothing is missing, so no donor is consulted and the door draws
		//the area's version - "Littleroot house" on area 4 draws area 4's
		//chip_mado - with no line anywhere. Compare against the prop's home.
		if (note != null && !pm.donorAreas.isEmpty() && pm.donorAreas.get(0) != area.areaId) {
			java.util.List<String> all = new java.util.ArrayList<>(ctrmap.formats.propdata.PropDatabase.getMaterialTextureNames(modelBch));
			for (String c : ctrmap.formats.h3d.BchTexturePack.clashesWith(area.file(11), targetPack, propPackOf(pm.donorAreas.get(0)), all)) {
				note.append("\n").append(propModelName).append(" will draw this area's ").append(c)
						.append(", not its own: the two textures share a name and differ.");
			}
		}
		if (!missing.isEmpty()) {
			int donorArea = db.findDonorAreaWithTextures(pm, missing);
			if (donorArea < 0) {
				throw new IllegalStateException("no donor area has its textures " + missing);
			}
			byte[] donorPack = propPackOf(donorArea);
			byte[] merged = ctrmap.formats.h3d.BchTexturePack.importIntoArea(
					area.areaId, area.editingZone, targetPack, donorPack, missing);
			if (merged != targetPack) {
				ctrmap.formats.h3d.BCHFile check = new ctrmap.formats.h3d.BCHFile(merged);
				if (check.errorlevel != 0) {
					throw new IllegalStateException("merged texture pack failed verification");
				}
				area.stage(1, merged);
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
		area.stageRegistry();
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
