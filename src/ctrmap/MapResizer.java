package ctrmap;

import ctrmap.formats.containers.GR;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.RegionFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Grows a zone's map beyond one region - the "bigger custom maps" feature
 * (a 2x1 route, a 2x2 Battle-Frontier hub...). The zone gets a NEW map matrix
 * of the requested width/height: the old grid keeps its cells (top-left, with
 * whatever sharing they had), and every new cell is a fresh BLANK canvas region
 * (flat walkable plane in the zone's own area style, via {@link RegionFactory}).
 * Regions tile at 720 world units; the in-game loader reads matrix dimensions
 * from the data (multi-cell retail matrices prove the path), and CTRMap's map
 * view already renders multi-cell matrices.
 *
 * <p>v1 scope: hasLOD==0 matrices (all towns/interiors; LOD matrices carry
 * zone-switch grids that must grow in lockstep - later), and growing only
 * (no shrink). Full-extent camera containment entries are stretched to the new
 * extent; repulsors are left alone.
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
	 * containment entries, which stretch to the new extent.
	 */
	public static byte[] buildResizedMatrix(byte[] mat, int newW, int newH, int[] newCellRegionIds) {
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
		if (hasLOD != 0) {
			throw new IllegalArgumentException("This map uses an LOD matrix - resizing those is not supported yet.");
		}
		if (newW < w || newH < h || (newW == w && newH == h)) {
			throw new IllegalArgumentException("New size must grow the map (current " + w + "x" + h + ").");
		}
		if (newCellRegionIds.length != newW * newH - w * h) {
			throw new IllegalArgumentException("Need " + (newW * newH - w * h) + " new region ids.");
		}
		//new grid
		byte[] ns0 = new byte[pad4(8 + newW * newH * 2)];
		putU16(ns0, 0, 0);
		putU16(ns0, 2, unk);
		putU16(ns0, 4, newW);
		putU16(ns0, 6, newH);
		int next = 0;
		for (int y = 0; y < newH; y++) {
			for (int x = 0; x < newW; x++) {
				int id = (x < w && y < h) ? u16(sub0, 8 + (y * w + x) * 2) : newCellRegionIds[next++];
				putU16(ns0, 8 + (y * newW + x) * 2, id);
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
					putF(cam, base + 4, south + (newH - h) * 720f);
					putF(cam, base + 12, east + (newW - w) * 720f);
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
			p32(out, 4 + i * 4, off);
			System.arraycopy(subs[i], 0, out, off, subs[i].length);
			off += subs[i].length;
		}
		p32(out, 4 + count * 4, off);
		return out;
	}

	/**
	 * Workspace operation: gives the zone a grown matrix with blank-canvas
	 * regions in the new cells. Pack Workspace must run afterwards (one
	 * append per pack cycle, same rule as the fork).
	 */
	public static ResizeResult resize(int zoneIndex, int newW, int newH) throws IOException {
		if (!Workspace.isOA()) {
			throw new IOException("Map resize is ORAS-only in v1.");
		}
		GARC zo = Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA);
		GARC gr = Workspace.getArchive(Workspace.ArchiveType.FIELD_DATA);
		GARC mm = Workspace.getArchive(Workspace.ArchiveType.MAP_MATRIX);
		if (zo == null || gr == null || mm == null) {
			throw new IOException("No workspace is loaded.");
		}
		int zoneCount = zo.length - 2;
		if (zoneIndex < 0 || zoneIndex >= zoneCount) {
			throw new IOException("Zone " + zoneIndex + " out of range.");
		}
		File mmDir = Workspace.getExtractionDirectory(Workspace.ArchiveType.MAP_MATRIX);
		File fdDir = Workspace.getExtractionDirectory(Workspace.ArchiveType.FIELD_DATA);
		int newMatrix = mm.length;
		File matrixOut = new File(mmDir, String.valueOf(newMatrix));
		if (Workspace.persist_paths.contains(matrixOut.getAbsolutePath())) {
			throw new IOException("A map append is already pending. Pack the workspace first.");
		}

		File zoneFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, zoneIndex);
		byte[] zoBytes = readAll(zoneFile);
		int hdrOff = i32(zoBytes, 4);
		int oldMatrix = u16(zoBytes, hdrOff + 4);
		byte[] matBytes = readAll(Workspace.getWorkspaceFile(Workspace.ArchiveType.MAP_MATRIX, oldMatrix));

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
		byte[] templateGr = readAll(Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, templateRegion));

		int newCells = newW * newH - w * h;
		if (newCells <= 0) {
			throw new IOException("New size must grow the map (current " + w + "x" + h + ").");
		}
		int[] newIds = new int[newCells];
		for (int i = 0; i < newCells; i++) {
			newIds[i] = gr.length + i;
		}
		//build the matrix FIRST (validates hasLOD/size before any file lands)
		byte[] newMat = buildResizedMatrix(matBytes, newW, newH, newIds);

		//blank-canvas regions for the new cells
		for (int id : newIds) {
			File f = new File(fdDir, String.valueOf(id));
			writeAll(f, templateGr);
			GR reg = new GR(f);
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
			Workspace.addPersist(f);
			GeometryForker.registerPendingField(id, gr.isEntryCompressed(templateRegion));
		}

		writeAll(matrixOut, newMat);
		Workspace.addPersist(matrixOut);
		GeometryForker.registerPendingMatrix(newMatrix, mm.isEntryCompressed(oldMatrix));

		//repoint the zone (ZO header + the runtime-authoritative master row)
		byte[] newZo = zoBytes.clone();
		putU16(newZo, hdrOff + 4, newMatrix);
		writeAll(zoneFile, newZo);
		Workspace.addPersist(zoneFile);
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

	private static int u16(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
	}

	private static int i32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}

	private static float f32(byte[] b, int o) {
		return Float.intBitsToFloat(i32(b, o));
	}

	private static void putU16(byte[] b, int o, int v) {
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
	}

	private static void p32(byte[] b, int o, int v) {
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
		b[o + 2] = (byte) (v >> 16);
		b[o + 3] = (byte) (v >> 24);
	}

	private static void putF(byte[] b, int o, float f) {
		p32(b, o, Float.floatToIntBits(f));
	}

	private static byte[] readAll(File f) throws IOException {
		InputStream in = new FileInputStream(f);
		byte[] b = new byte[in.available()];
		in.read(b);
		in.close();
		return b;
	}

	private static void writeAll(File f, byte[] b) throws IOException {
		OutputStream os = new FileOutputStream(f);
		os.write(b);
		os.flush();
		os.close();
	}
}
