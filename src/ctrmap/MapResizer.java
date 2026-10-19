package ctrmap;

import ctrmap.formats.containers.ContainerBytes;
import ctrmap.formats.containers.GR;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.RegionFactory;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import ctrmap.gamedef.GameType;
import ctrmap.formats.mapmatrix.MapMatrix;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.formats.zone.ZoneFootprint;
import ctrmap.formats.zone.ZoneHeader;
import java.io.File;
import java.io.IOException;
import static ctrmap.util.LittleEndian.u16;
import static ctrmap.util.LittleEndian.i32;
import static ctrmap.util.LittleEndian.f32;
import static ctrmap.util.LittleEndian.putU16;
import java.nio.file.Files;
import static ctrmap.util.LittleEndian.putI32;
import static ctrmap.util.LittleEndian.putF32;

/**
 * Grows or shrinks a zone's map - the "bigger custom maps" feature
 * (a 2x1 route, a 2x2 hub area...). The zone gets a NEW map matrix
 * of the requested width/height: the old grid keeps the cells that fit (top-left,
 * with whatever sharing they had), and every new cell is a fresh BLANK canvas region
 * (flat walkable plane in the zone's own area style, via {@link RegionFactory}).
 * Regions tile at 720 world units; the in-game loader reads matrix dimensions
 * from the data (multi-cell retail matrices prove the path), and CTRMap's map
 * view already renders multi-cell matrices.
 *
 * <p>Both plain and LOD matrices are supported (LOD matrices grow their
 * 4x-resolution zone-switch grid and LOD grid in lockstep; new segments join
 * the resized zone). Full-extent camera containment entries follow the new
 * extent; repulsors are left alone.
 *
 * <p>SHRINKING (F020). Growing was the only direction, so a map grown one cell too
 * far could not be put back short of a restore. The cells past the new size are
 * dropped, ground and all, so a shrink is refused while anything of the zone's own -
 * its spawn point, an NPC, a warp, a prop, a trigger - stands in one, and when the
 * zone's entities cannot be read to say. A containment box reaching past the new
 * edge is cut at it. The dropped cells' regions stay in FieldData, unused by this
 * zone, and the result names them.
 */
public class MapResizer {

	public static class ResizeResult {

		public int oldW, oldH, newW, newH;
		public int newMatrix;
		public int[] newRegions;
		/** The old cells' private copies, when the zone shared its map; empty when it did not. */
		public int[] ownRegions = new int[0];
		/** Zones whose ground those copies still carry (see GeometryForker.planFork). */
		public int[] otherZones = new int[0];
		/** Regions drawn only by the cells a shrink dropped: left in FieldData, unused by this zone (F020). */
		public int[] droppedRegions = new int[0];
	}

	/**
	 * Headless core: the resized matrix container. Old cells that fit land top-left and the rest are dropped;
	 * {@code newCellRegionIds} fill the remaining cells in row-major order.
	 * All other subfiles are carried verbatim except full-extent camera
	 * containment entries, which stretch to the new extent. LOD matrices grow
	 * all three grids (main ids, the 4x-resolution zone-switch grid, and the
	 * LOD grid); {@code primaryZone} is the zone the new regions belong to and
	 * fills the new zone-switch segments (ignored for non-LOD matrices, pass -1).
	 */
	public static byte[] buildResizedMatrix(byte[] mat, int newW, int newH, int[] newCellRegionIds, int primaryZone) {
		int count = ContainerBytes.count(mat);
		if (count < 2) {
			throw new IllegalArgumentException("matrix container has no camera subfile");
		}
		int[] offs = ContainerBytes.offsets(mat);
		if (offs == null) {
			throw new IllegalArgumentException("matrix container's offset table is cut short");
		}
		//THE GRID IS READ BY THE GRID (F248): this parsed it by hand; it still WRITES the layout, and
		//copies the two header words the Grid does not interpret
		MapMatrix.Grid grid = MapMatrix.Grid.of(mat);
		int hasLOD = u16(mat, offs[0]), unk = u16(mat, offs[0] + 2), w = grid.width, h = grid.height;
		if (newW < 1 || newH < 1 || (newW == w && newH == h)) {
			throw new IllegalArgumentException("New size must differ from the current " + w + "x" + h + " and be at least 1x1.");
		}
		int kept = Math.min(w, newW) * Math.min(h, newH);
		if (newCellRegionIds.length != newW * newH - kept) {
			throw new IllegalArgumentException("Need " + (newW * newH - kept) + " new region ids.");
		}
		//main ids grid (grown), tracked so LOD grids can fill by cell occupancy
		int[] newIds = new int[newW * newH];
		int next = 0;
		for (int y = 0; y < newH; y++) {
			for (int x = 0; x < newW; x++) {
				newIds[y * newW + x] = (x < w && y < h) ? grid.id(x, y) : newCellRegionIds[next++];
			}
		}
		int mainLen = 8 + newW * newH * 2;
		int zoneLen = hasLOD == 1 ? newW * 4 * newH * 4 * 2 : 0;
		int lodLen = hasLOD == 1 ? newW * newH * 2 : 0;
		byte[] ns0 = new byte[pad4(mainLen + zoneLen + lodLen)];
		putU16(ns0, 0, hasLOD);
		putU16(ns0, 2, unk);
		putU16(ns0, 4, newW);
		putU16(ns0, 6, newH);
		for (int k = 0; k < newW * newH; k++) {
			putU16(ns0, 8 + k * 2, newIds[k]);
		}
		if (hasLOD == 1) {
			//zone-switch grid: 4x4 segments per region. Old segments copied; new
			//segments belong to primaryZone where a (new) region exists, else 0xFFFF void.
			int zBase = mainLen;
			int zw = w * 4, zh = h * 4, nzw = newW * 4, nzh = newH * 4;
			for (int zy = 0; zy < nzh; zy++) {
				for (int zx = 0; zx < nzw; zx++) {
					int v;
					if (zx < zw && zy < zh) {
						v = u16(mat, grid.zoneLayerOffset() + (zy * zw + zx) * 2);
					} else {
						int rid = newIds[(zy / 4) * newW + (zx / 4)];
						v = (rid != 0xFFFF && primaryZone >= 0) ? primaryZone : 0xFFFF;
					}
					putU16(ns0, zBase + (zy * nzw + zx) * 2, v);
				}
			}
			//LOD grid: old cells copied, new cells 0xFFFF (no LOD swap)
			int lBase = mainLen + zoneLen;
			int oldLodBase = grid.zoneLayerOffset() + zw * zh * 2;
			for (int y = 0; y < newH; y++) {
				for (int x = 0; x < newW; x++) {
					int v = (x < w && y < h) ? u16(mat, oldLodBase + (y * w + x) * 2) : 0xFFFF;
					putU16(ns0, lBase + (y * newW + x) * 2, v);
				}
			}
		}
		//camera subfile: full-extent containment entries follow the new extent; on a shrink a
		//containment box reaching past the new edge is cut at it, and one wholly past it shrinks to
		//nothing on the edge rather than turning inside out (F020)
		byte[] cam = slice(mat, offs[1], offs[2]).clone();
		if (cam.length >= 4) {
			int n = i32(cam, 0);
			float oldEast = w * 720f, oldSouth = h * 720f;
			float newEast = newW * 720f, newSouth = newH * 720f;
			for (int e = 0; e < n && 4 + e * 20 + 20 <= cam.length; e++) {
				int base = 4 + e * 20;
				float north = f32(cam, base), south = f32(cam, base + 4);
				float west = f32(cam, base + 8), east = f32(cam, base + 12);
				int isRepeal = i32(cam, base + 16);
				if (isRepeal == 0 && south >= oldSouth - 40f && east >= oldEast - 40f && north <= 40f && west <= 40f) {
					putF32(cam, base + 4, south + (newH - h) * 720f);
					putF32(cam, base + 12, east + (newW - w) * 720f);
				} else if (isRepeal == 0 && (newW < w || newH < h)) {
					putF32(cam, base, Math.min(north, newSouth));
					putF32(cam, base + 4, Math.min(south, newSouth));
					putF32(cam, base + 8, Math.min(west, newEast));
					putF32(cam, base + 12, Math.min(east, newEast));
				}
			}
		}
		//reassemble (contiguous subfiles, like retail matrices)
		byte[][] subs = new byte[count][];
		subs[0] = ns0;
		subs[1] = cam;
		for (int i = 2; i < count; i++) {
			subs[i] = slice(mat, offs[i], offs[i + 1]);
		}
		int total = 4 + (count + 1) * 4;
		for (byte[] s : subs) {
			total += s.length;
		}
		byte[] out = new byte[total];
		putU16(out, 0, u16(mat, 0)); //magic
		putU16(out, 2, count);
		int off = 4 + (count + 1) * 4;
		for (int i = 0; i < count; i++) {
			putI32(out, 4 + i * 4, off);
			System.arraycopy(subs[i], 0, out, off, subs[i].length);
			off += subs[i].length;
		}
		putI32(out, 4 + count * 4, off);
		return out;
	}

	/** The zone's map's size in cells, {width, height}, as the workspace holds it: where a resize starts. */
	public static int[] currentSize(WorkspaceSession ws, int zoneIndex) throws IOException {
		MapMatrix.Grid g = MapMatrix.Grid.of(zoneAndMap(ws, zoneIndex)[1]);
		return new int[]{g.width, g.height};
	}

	/** The zone's container and the map matrix its header names, as the workspace holds them. */
	private static byte[][] zoneAndMap(WorkspaceSession ws, int zoneIndex) throws IOException {
		byte[] zo = Files.readAllBytes(ws.getWorkspaceFile(ArchiveType.ZONE_DATA, zoneIndex).toPath());
		int matrix = u16(zo, i32(zo, 4) + 4);
		return new byte[][]{zo, Files.readAllBytes(ws.getWorkspaceFile(ArchiveType.MAP_MATRIX, matrix).toPath())};
	}

	/**
	 * Workspace operation: gives the zone a matrix of the new size, with blank-canvas regions in
	 * the new cells and the cells past it dropped. Pack Workspace must run afterwards (one append
	 * per pack cycle, same rule as the fork).
	 */
	public static ResizeResult resize(WorkspaceSession ws, int zoneIndex, int newW, int newH) throws IOException {
		//WAS "if (!ws.isOA())", which refused X/Y and let Sun/Moon and Ultra
		//Sun/Ultra Moon straight through - "not ORAS" is three games, and two
		//of them would have been handed ORAS's container offsets. A resize
		//appends a map matrix and FieldData regions and repoints the zone at
		//them, which is the same set of measured offsets the area fork needs,
		//so it asks for that capability rather than for a game name.
		GameProfile p = ws.profile();
		if (!p.supports(GameProfile.Feature.AREA_FORK)) {
			throw new IOException("Growing a zone's map is not available for " + p.displayName() + "."
					+ "\n\nA resize appends a new map matrix and new blank regions and repoints the"
					+ " zone at them. Every one of those offsets was measured on Omega Ruby /"
					+ " Alpha Sapphire, and nobody has measured them for " + p.displayName() + ","
					+ " so CTRMap refuses here rather than writing a matrix this game would not read.");
		}
		GARC zo = ws.getArchive(ArchiveType.ZONE_DATA);
		GARC gr = ws.getArchive(ArchiveType.FIELD_DATA);
		GARC mm = ws.getArchive(ArchiveType.MAP_MATRIX);
		if (zo == null || gr == null || mm == null) {
			throw new IOException("No workspace is loaded.");
		}
		//and the range check counted zones as "length - 2", ORAS's tail spelled
		//as a literal in a class that is not allowed to know it
		int zoneCount = ZoneTables.zoneCount(zo, p);
		if (zoneIndex < 0 || zoneIndex >= zoneCount) {
			throw new IOException("Zone " + zoneIndex + " out of range.");
		}
		File mmDir = ws.getExtractionDirectory(ArchiveType.MAP_MATRIX);
		File fdDir = ws.getExtractionDirectory(ArchiveType.FIELD_DATA);
		int newMatrix = GeometryForker.nextFreeMatrix(ws, mm);
		File matrixOut = new File(mmDir, String.valueOf(newMatrix));

		File zoneFile = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, zoneIndex);
		byte[][] read = zoneAndMap(ws, zoneIndex);
		byte[] zoBytes = read[0];
		int hdrOff = i32(zoBytes, 4);
		int oldMatrix = u16(zoBytes, hdrOff + 4);
		byte[] matBytes = read[1];

		//template = the zone's OWN first region (same area -> textures guaranteed). The matrix's first filled
		//cell, which this took, is a neighbour's on a shared matrix, in another area with other textures (census M.54)
		MapMatrix.Grid grid = MapMatrix.Grid.of(matBytes);
		int w = grid.width, h = grid.height;
		int templateRegion = MapMatrix.ownRegionId(matBytes, zoneIndex);
		if (templateRegion < 0) {
			throw new IOException("The zone's matrix has no regions.");
		}
		byte[] templateGr = Files.readAllBytes(ws.getWorkspaceFile(ArchiveType.FIELD_DATA, templateRegion).toPath());

		if (newW < 1 || newH < 1 || (newW == w && newH == h)) {
			throw new IOException("New size must differ from the current " + w + "x" + h + " and be at least 1x1.");
		}
		int keepW = Math.min(w, newW), keepH = Math.min(h, newH);
		int newCells = newW * newH - keepW * keepH;
		int[] dropped = new int[0];
		if (keepW < w || keepH < h) {
			//NOTHING OF THE ZONE'S IN A DROPPED CELL (F020): the cells past the new size go, ground
			//and all, so a spawn point, an NPC, a warp, a prop or a trigger standing in one would be
			//left over nothing. Asked of the zone's own content, and refused when it cannot be read -
			//unknown is not empty
			ZoneFootprint fp = footprint(zoBytes, ws.game(), zoneIndex);
			if (fp != null && !fp.fitsIn(newW, newH)) {
				throw new IOException("Zone " + zoneIndex + "'s own content - its spawn point, NPCs, warps, props or"
						+ " triggers - reaches " + fp.describeCells() + ", and a " + newW + "x" + newH + " map ends"
						+ " before that. Move or delete what stands past it first; the map was not shrunk.");
			}
			dropped = droppedRegions(grid, keepW, keepH);
			//cropped first, so a shared map's private copies are made of the cells that stay
			matBytes = buildResizedMatrix(matBytes, keepW, keepH, new int[0], zoneIndex);
		}
		//THE OLD CELLS BECOME THIS ZONE'S OWN (F018). The action said growing a map made the
		//zone's geometry private first, and nothing did: the new matrix kept the old cells on the
		//regions every zone sharing the map draws, so painting an old cell of a grown clone
		//repainted the town it was cloned from. Forking first cannot work - a fork appends a
		//matrix, and this refuses while one is pending - so the fork's own plan is taken here and
		//its copies are written in this unit: each old region the grid names gets a private copy,
		//and the new cells come after them.
		int firstNew = gr.length;
		GeometryForker.ForkPlan own = null;
		if (!MasterTable.read(ws).sharing(ZoneResource.MAP, zoneIndex).isEmpty()) {
			try {
				own = GeometryForker.planFork(zoBytes, matBytes, firstNew, newMatrix, zoneIndex, false);
			} catch (IllegalArgumentException bad) {
				throw new IOException("The zone's map could not be made its own, so it was not grown: "
						+ ctrmap.util.Bytes.reason(bad), bad);
			}
			matBytes = own.newMatrixBytes;
			firstNew += own.newRegions.length;
		}
		int[] newIds = new int[newCells];
		for (int i = 0; i < newCells; i++) {
			newIds[i] = firstNew + i;
		}
		//build the matrix FIRST (validates size before any file lands); new LOD
		//zone-switch segments belong to this zone
		byte[] newMat = newW == keepW && newH == keepH ? matBytes
				: buildResizedMatrix(matBytes, newW, newH, newIds, zoneIndex);

		//ALL OF IT OR NONE OF IT (F227). New regions, then the matrix, then the zone
		//header, then the master row: a failure between any two used to leave the
		//earlier ones staged - a matrix nothing points to, or a header and master row
		//that disagree. The unit puts every file, the list of edits and the pending
		//compression flags back unless the last write landed.
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Resize map", ws)) {
			//the old cells' private copies, when the map was shared
			if (own != null) {
				for (int i = 0; i < own.srcRegions.length; i++) {
					int src = own.srcRegions[i];
					if (src < 0 || src >= gr.length) {
						throw new IOException("The zone's map names region " + src + ", which does not exist.");
					}
					File from = ws.getWorkspaceFile(ArchiveType.FIELD_DATA, src);
					if (from == null) {
						throw new IOException("FieldData region " + src + " could not be read out of the workspace.");
					}
					File to = new File(fdDir, String.valueOf(own.newRegions[i]));
					WorkspaceUnit.copy(from, to);
					ws.addPersist(to);
					ws.registerPendingCompression(ArchiveType.FIELD_DATA, own.newRegions[i], gr.isEntryCompressed(src));
				}
			}
			//blank-canvas regions for the new cells
			for (int id : newIds) {
				File f = new File(fdDir, String.valueOf(id));
				WorkspaceUnit.write(f, templateGr);
				GR reg = new GR(f, ws);
				//THE ONE BLANKING, with the ground the blank canvas chooses (F017, F073).
				//This was a private copy of the subfile numbering, and it floored the new
				//cells with the BIGGEST mesh - a wall, a fence or the sea on 312 of the
				//first 400 retail regions, which is why Blank canvas stopped doing it.
				//a write that fails part-way throws with its reason, and the unit puts the half back (census H.59)
				if (BchMapModel.isMapModel(reg.getFile(1)) && !RegionFactory.blankOrThrow(reg, -1)) {
					throw new IOException("Region " + id + "'s model is one the blank canvas cannot rebuild, so the map"
							+ " was not grown.");
				}
				ws.addPersist(f);
				ws.registerPendingCompression(ArchiveType.FIELD_DATA, id, gr.isEntryCompressed(templateRegion));
			}

			WorkspaceUnit.write(matrixOut, newMat);
			ws.addPersist(matrixOut);
			ws.registerPendingCompression(ArchiveType.MAP_MATRIX, newMatrix, mm.isEntryCompressed(oldMatrix));

			//repoint the zone (ZO header + the runtime-authoritative master row), both
			//through the session this was handed (F019)
			byte[] newZo = zoBytes.clone();
			putU16(newZo, hdrOff + 4, newMatrix);
			WorkspaceUnit.write(zoneFile, newZo);
			ws.addPersist(zoneFile);
			GeometryForker.repointMasterRow(ws, zo, zoneIndex, ZoneResource.MAP, newMatrix);
			unit.commit();
		}

		ResizeResult r = new ResizeResult();
		r.oldW = w;
		r.oldH = h;
		r.newW = newW;
		r.newH = newH;
		r.newMatrix = newMatrix;
		r.newRegions = newIds;
		r.droppedRegions = dropped;
		if (own != null) {
			r.ownRegions = own.newRegions;
			r.otherZones = own.otherZones;
		}
		return r;
	}

	/**
	 * The zone's own content's extent, from its container's header and entities - or a refusal in
	 * words when they cannot be read, because a shrink that could not look must not go ahead as if
	 * nothing stood in the cells it drops.
	 */
	private static ZoneFootprint footprint(byte[] zoBytes, GameType game, int zoneIndex) throws IOException {
		try {
			int[] offs = ContainerBytes.offsets(zoBytes);
			if (offs == null || ContainerBytes.count(zoBytes) < 2) {
				throw new IllegalArgumentException("its container holds no entities");
			}
			return ZoneFootprint.of(new ZoneHeader(slice(zoBytes, offs[0], offs[1]), game),
					new ZoneEntities(slice(zoBytes, offs[1], offs[2])));
		} catch (RuntimeException unread) {
			throw new IOException("Zone " + zoneIndex + "'s header and entities could not be read ("
					+ ctrmap.util.Bytes.reason(unread) + "), so whether anything of it stands in the cells a"
					+ " shrink drops is unknown; the map was not shrunk.", unread);
		}
	}

	/** The regions the cells past keepW x keepH draw that no kept cell draws, ascending (F020). */
	static int[] droppedRegions(MapMatrix.Grid grid, int keepW, int keepH) {
		java.util.TreeSet<Integer> gone = new java.util.TreeSet<>();
		java.util.Set<Integer> kept = new java.util.HashSet<>();
		for (int y = 0; y < grid.height; y++) {
			for (int x = 0; x < grid.width; x++) {
				int id = grid.id(x, y);
				if (id != 0xFFFF) {
					(x < keepW && y < keepH ? kept : gone).add(id);
				}
			}
		}
		gone.removeAll(kept);
		int[] out = new int[gone.size()];
		int i = 0;
		for (int id : gone) {
			out[i++] = id;
		}
		return out;
	}

	private static byte[] slice(byte[] b, int a, int e) {
		return java.util.Arrays.copyOfRange(b, a, e);
	}

	private static int pad4(int v) {
		return (v + 3) & ~3;
	}


}
