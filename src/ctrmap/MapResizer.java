package ctrmap;

import ctrmap.formats.containers.GR;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.RegionFactory;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import java.io.File;
import java.io.IOException;
import static ctrmap.formats.LittleEndian.u16;
import static ctrmap.formats.LittleEndian.i32;
import static ctrmap.formats.LittleEndian.f32;
import static ctrmap.formats.LittleEndian.putU16;
import java.nio.file.Files;
import static ctrmap.formats.LittleEndian.putI32;
import static ctrmap.formats.LittleEndian.putF32;

/**
 * Grows a zone's map beyond one region - the "bigger custom maps" feature
 * (a 2x1 route, a 2x2 hub area...). The zone gets a NEW map matrix
 * of the requested width/height: the old grid keeps its cells (top-left, with
 * whatever sharing they had), and every new cell is a fresh BLANK canvas region
 * (flat walkable plane in the zone's own area style, via {@link RegionFactory}).
 * Regions tile at 720 world units; the in-game loader reads matrix dimensions
 * from the data (multi-cell retail matrices prove the path), and CTRMap's map
 * view already renders multi-cell matrices.
 *
 * <p>Both plain and LOD matrices are supported (LOD matrices grow their
 * 4x-resolution zone-switch grid and LOD grid in lockstep; new segments join
 * the resized zone). Growing only (no shrink). Full-extent camera containment
 * entries are stretched to the new extent; repulsors are left alone.
 */
public class MapResizer {

	public static class ResizeResult {

		public int oldW, oldH, newW, newH;
		public int newMatrix;
		public int[] newRegions;
	}

	/**
	 * Headless core: the resized matrix container. Old cells land top-left;
	 * {@code newCellRegionIds} fill the remaining cells in row-major order.
	 * All other subfiles are carried verbatim except full-extent camera
	 * containment entries, which stretch to the new extent. LOD matrices grow
	 * all three grids (main ids, the 4x-resolution zone-switch grid, and the
	 * LOD grid); {@code primaryZone} is the zone the new regions belong to and
	 * fills the new zone-switch segments (ignored for non-LOD matrices, pass -1).
	 */
	public static byte[] buildResizedMatrix(byte[] mat, int newW, int newH, int[] newCellRegionIds, int primaryZone) {
		int count = u16(mat, 2);
		if (count < 2) {
			throw new IllegalArgumentException("matrix container has no camera subfile");
		}
		int[] offs = new int[count + 1];
		for (int i = 0; i <= count; i++) {
			offs[i] = i32(mat, 4 + i * 4);
		}
		byte[] sub0 = slice(mat, offs[0], offs[1]);
		int hasLOD = u16(sub0, 0), unk = u16(sub0, 2), w = u16(sub0, 4), h = u16(sub0, 6);
		if (newW < w || newH < h || (newW == w && newH == h)) {
			throw new IllegalArgumentException("New size must grow the map (current " + w + "x" + h + ").");
		}
		if (newCellRegionIds.length != newW * newH - w * h) {
			throw new IllegalArgumentException("Need " + (newW * newH - w * h) + " new region ids.");
		}
		//main ids grid (grown), tracked so LOD grids can fill by cell occupancy
		int[] newIds = new int[newW * newH];
		int next = 0;
		for (int y = 0; y < newH; y++) {
			for (int x = 0; x < newW; x++) {
				newIds[y * newW + x] = (x < w && y < h) ? u16(sub0, 8 + (y * w + x) * 2) : newCellRegionIds[next++];
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
						v = u16(sub0, 8 + w * h * 2 + (zy * zw + zx) * 2);
					} else {
						int rid = newIds[(zy / 4) * newW + (zx / 4)];
						v = (rid != 0xFFFF && primaryZone >= 0) ? primaryZone : 0xFFFF;
					}
					putU16(ns0, zBase + (zy * nzw + zx) * 2, v);
				}
			}
			//LOD grid: old cells copied, new cells 0xFFFF (no LOD swap)
			int lBase = mainLen + zoneLen;
			int oldLodBase = 8 + w * h * 2 + zw * zh * 2;
			for (int y = 0; y < newH; y++) {
				for (int x = 0; x < newW; x++) {
					int v = (x < w && y < h) ? u16(sub0, oldLodBase + (y * w + x) * 2) : 0xFFFF;
					putU16(ns0, lBase + (y * newW + x) * 2, v);
				}
			}
		}
		//camera subfile: stretch full-extent containment entries
		byte[] cam = slice(mat, offs[1], offs[2]).clone();
		if (cam.length >= 4) {
			int n = i32(cam, 0);
			float oldEast = w * 720f, oldSouth = h * 720f;
			for (int e = 0; e < n && 4 + e * 20 + 20 <= cam.length; e++) {
				int base = 4 + e * 20;
				float north = f32(cam, base), south = f32(cam, base + 4);
				float west = f32(cam, base + 8), east = f32(cam, base + 12);
				int isRepeal = i32(cam, base + 16);
				if (isRepeal == 0 && south >= oldSouth - 40f && east >= oldEast - 40f && north <= 40f && west <= 40f) {
					putF32(cam, base + 4, south + (newH - h) * 720f);
					putF32(cam, base + 12, east + (newW - w) * 720f);
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

	/**
	 * Workspace operation: gives the zone a grown matrix with blank-canvas
	 * regions in the new cells. Pack Workspace must run afterwards (one
	 * append per pack cycle, same rule as the fork).
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
		int newMatrix = mm.length;
		File matrixOut = new File(mmDir, String.valueOf(newMatrix));
		if (ws.isPersisted(matrixOut)) {
			throw new IOException("A map append is already pending. Pack the workspace first.");
		}

		File zoneFile = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, zoneIndex);
		byte[] zoBytes = Files.readAllBytes(zoneFile.toPath());
		int hdrOff = i32(zoBytes, 4);
		int oldMatrix = u16(zoBytes, hdrOff + 4);
		byte[] matBytes = Files.readAllBytes(ws.getWorkspaceFile(ArchiveType.MAP_MATRIX, oldMatrix).toPath());

		//template = the zone's first region (same area -> textures guaranteed)
		int sub0 = i32(matBytes, 4);
		int w = u16(matBytes, sub0 + 4), h = u16(matBytes, sub0 + 6);
		int templateRegion = -1;
		for (int k = 0; k < w * h && templateRegion < 0; k++) {
			int id = u16(matBytes, sub0 + 8 + k * 2);
			if (id != 0xFFFF) {
				templateRegion = id;
			}
		}
		if (templateRegion < 0) {
			throw new IOException("The zone's matrix has no regions.");
		}
		byte[] templateGr = Files.readAllBytes(ws.getWorkspaceFile(ArchiveType.FIELD_DATA, templateRegion).toPath());

		int newCells = newW * newH - w * h;
		if (newCells <= 0) {
			throw new IOException("New size must grow the map (current " + w + "x" + h + ").");
		}
		int[] newIds = new int[newCells];
		for (int i = 0; i < newCells; i++) {
			newIds[i] = gr.length + i;
		}
		//build the matrix FIRST (validates size before any file lands); new LOD
		//zone-switch segments belong to this zone
		byte[] newMat = buildResizedMatrix(matBytes, newW, newH, newIds, zoneIndex);

		//blank-canvas regions for the new cells
		for (int id : newIds) {
			File f = new File(fdDir, String.valueOf(id));
			Files.write(f.toPath(), templateGr);
			GR reg = new GR(f, ws);
			byte[] template = reg.getFile(1);
			if (BchMapModel.isMapModel(template)) {
				BchMapModel tm = new BchMapModel(template);
				int gm = 0, bt = -1;
				for (BchMapModel.MeshGeom g : tm.geometry()) {
					if (g.posOk && tm.getTriangles(g.meshIndex).length > bt) {
						bt = tm.getTriangles(g.meshIndex).length;
						gm = g.meshIndex;
					}
				}
				RegionFactory.BlankContent bc = RegionFactory.blank(template, gm);
				reg.storeFile(1, bc.model);
				reg.storeFile(2, bc.collision);
				reg.storeFile(0, bc.tilemap);
				reg.storeFile(3, bc.props);
				if (reg.len >= 9) {
					reg.storeFile(7, RegionFactory.voidTilemap());
					reg.storeFile(reg.len >= 11 ? 9 : 8, RegionFactory.emptyCollision());
					if (reg.len >= 11) {
						reg.storeFile(8, RegionFactory.voidTilemap());
						reg.storeFile(10, RegionFactory.emptyCollision());
					}
				}
			}
			ws.addPersist(f);
			GeometryForker.registerPendingField(id, gr.isEntryCompressed(templateRegion));
		}

		Files.write(matrixOut.toPath(), newMat);
		ws.addPersist(matrixOut);
		GeometryForker.registerPendingMatrix(newMatrix, mm.isEntryCompressed(oldMatrix));

		//repoint the zone (ZO header + the runtime-authoritative master row)
		byte[] newZo = zoBytes.clone();
		putU16(newZo, hdrOff + 4, newMatrix);
		Files.write(zoneFile.toPath(), newZo);
		ws.addPersist(zoneFile);
		GeometryForker.repointMasterRow(zo, zoneIndex, newMatrix);

		ResizeResult r = new ResizeResult();
		r.oldW = w;
		r.oldH = h;
		r.newW = newW;
		r.newH = newH;
		r.newMatrix = newMatrix;
		r.newRegions = newIds;
		return r;
	}

	private static byte[] slice(byte[] b, int a, int e) {
		return java.util.Arrays.copyOfRange(b, a, e);
	}

	private static int pad4(int v) {
		return (v + 3) & ~3;
	}


}
