package ctrmap.tests;

import ctrmap.MapResizer;
import ctrmap.formats.garc.GARC;
import java.io.File;
import static ctrmap.util.LittleEndian.i32;
import static ctrmap.util.LittleEndian.u16;

/**
 * Matrix-resize core validation over every hasLOD==0 retail matrix: grown to
 * (w+1)x(h) and (w)x(h+1), the result must keep every old cell id at its
 * position, place the new ids row-major in the new cells, keep the camera
 * subfile size and any extra subfiles byte-identical, and keep the container
 * contiguous and well-formed.
 *
 * Usage: java ctrmap.tests.MapResizerTest <path-to-a040-garc>
 */
public class MapResizerTest {

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/4/0");
		GARC mm = new GARC(garcFile);
		int tested = 0, ok = 0, lodTested = 0, failures = 0;
		for (int i = 0; i < mm.length; i++) {
			byte[] mat = mm.getDecompressedEntry(i);
			if (mat == null || mat.length < 16 || u16(mat, 2) < 2) {
				continue;
			}
			int sub0 = i32(mat, 4);
			int hasLOD = u16(mat, sub0);
			int w = u16(mat, sub0 + 4), h = u16(mat, sub0 + 6);
			if (w < 1 || h < 1 || (long) (w + 1) * (h + 1) * 16 > 40000) {
				continue;
			}
			tested++;
			if (hasLOD == 1) {
				lodTested++;
			}
			try {
				checkGrow(mat, i, w + 1, h, w, h);
				checkGrow(mat, i, w, h + 1, w, h);
				ok++;
			} catch (RuntimeException ex) {
				failures++;
				System.out.println("FAIL matrix " + i + ": " + ex.getMessage());
				if (failures > 8) {
					break;
				}
			}
		}
		System.out.println("\nMapResizer: tested=" + tested + " (" + lodTested + " LOD)  ok=" + ok + "  failures=" + failures);
		failures += aGrownMapsNewCellIsFlooredWithTheGround(garcFile);
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (failures > 0) {
			System.exit(1);
		}
	}

	/**
	 * A grown map's new cells are floored with the region's GROUND, not its biggest mesh (F017).
	 *
	 * <p>The resize floored them with the biggest mesh - a wall, a fence or the sea on 312 of the first
	 * 400 retail regions - and now goes through the blank canvas's one blanking, which asks for the
	 * ground. Only a region whose biggest mesh is NOT its ground tells the two apart, and no synthetic
	 * map model exists, so this is the dump's: the first such retail region is put under a one-zone
	 * game, the zone's map grown by a cell, and the new cell's one real mesh read back.
	 *
	 * @return the failures
	 */
	static int aGrownMapsNewCellIsFlooredWithTheGround(File a040) throws Exception {
		System.out.println("--- a grown map's new cell is floored with the ground, not the biggest mesh (F017)");
		File a039 = new File(new File(a040.getParentFile().getParentFile(), "3"), "9");
		GARC fd = new GARC(a039);
		int found = -1, differing = 0, scanned = 0, foundGround = -1, foundBiggest = -1;
		for (int r = 0; r < fd.length; r++) {
			byte[] model = ctrmap.formats.containers.ContainerBytes.subfile(fd.getDecompressedEntry(r), 1);
			if (model == null || !ctrmap.formats.h3d.BchMapModel.isMapModel(model)) {
				continue;
			}
			scanned++;
			ctrmap.formats.h3d.BchMapModel m = new ctrmap.formats.h3d.BchMapModel(model);
			int ground = ctrmap.formats.tilemap.PaintedMaterials.defaultGroundMesh(m);
			int biggest = biggestMesh(m);
			if (ground >= 0 && biggest >= 0 && ground != biggest) {
				differing++;
				if (found < 0) {
					found = r;
					foundGround = ground;
					foundBiggest = biggest;
				}
			}
		}
		int fails = 0;
		if (found < 0) {
			System.out.println("  FAIL: control: no retail region has a biggest mesh that is not its ground ("
					+ scanned + " read) - nothing here can tell the two floors apart");
			return 1;
		}
		System.out.println("  ok: control: " + differing + " of " + scanned + " retail regions have a biggest mesh that is"
				+ " not their ground; region " + found + " is the first (ground " + foundGround + ", biggest " + foundBiggest + ")");
		ctrmap.WorkspaceSession ws = MapResizeTest.game("f017", new byte[][]{MapResizeTest.zone(0)}, new int[]{0},
				new byte[][]{MapResizeTest.matrix(0)}, fd.getDecompressedEntry(found));
		try {
			ctrmap.MapResizer.ResizeResult grown = ctrmap.MapResizer.resize(ws, 0, 2, 1);
			byte[] cell = ctrmap.formats.containers.ContainerBytes.subfile(
					MapResizeTest.bytes(ws, ctrmap.gamedef.ArchiveType.FIELD_DATA, grown.newRegions[0]), 1);
			int floor = biggestMesh(new ctrmap.formats.h3d.BchMapModel(cell));
			if (floor != foundGround) {
				System.out.println("  FAIL: the new cell is floored with mesh " + floor + ", not the ground " + foundGround
						+ (floor == foundBiggest ? " - it is the region's biggest mesh" : ""));
				fails++;
			} else {
				System.out.println("  ok: the new cell is floored with mesh " + floor + ", the ground, and the biggest mesh "
						+ foundBiggest + " is left degenerate");
			}
		} finally {
			ctrmap.Workspace.reset();
		}
		return fails;
	}

	/** The mesh with the most triangles, of those whose positions read. */
	static int biggestMesh(ctrmap.formats.h3d.BchMapModel m) {
		int best = -1, bestTris = -1;
		for (ctrmap.formats.h3d.BchMapModel.MeshGeom g : m.geometry()) {
			if (g.posOk && m.getTriangles(g.meshIndex).length > bestTris) {
				bestTris = m.getTriangles(g.meshIndex).length;
				best = g.meshIndex;
			}
		}
		return best;
	}

	static void checkGrow(byte[] mat, int mi, int newW, int newH, int w, int h) {
		int newCells = newW * newH - w * h;
		int[] ids = new int[newCells];
		for (int k = 0; k < newCells; k++) {
			ids[k] = 5000 + k;
		}
		final int PZONE = 321;
		byte[] out = MapResizer.buildResizedMatrix(mat, newW, newH, ids, PZONE);
		int count = u16(out, 2);
		if (count != u16(mat, 2)) {
			throw new IllegalStateException("subfile count changed");
		}
		int oldSub0 = i32(mat, 4);
		int hasLOD = u16(mat, oldSub0);
		int nSub0 = i32(out, 4);
		if (u16(out, nSub0) != hasLOD || u16(out, nSub0 + 4) != newW || u16(out, nSub0 + 6) != newH) {
			throw new IllegalStateException("grid header wrong");
		}
		int next = 0;
		int[] newIdsGrid = new int[newW * newH];
		for (int y = 0; y < newH; y++) {
			for (int x = 0; x < newW; x++) {
				int got = u16(out, nSub0 + 8 + (y * newW + x) * 2);
				int want = (x < w && y < h) ? u16(mat, oldSub0 + 8 + (y * w + x) * 2) : ids[next++];
				newIdsGrid[y * newW + x] = got;
				if (got != want) {
					throw new IllegalStateException("cell " + x + "," + y + " = " + got + " want " + want);
				}
			}
		}
		//LOD matrices: check the zone-switch grid and LOD grid grew correctly
		if (hasLOD == 1) {
			int oZoneBase = oldSub0 + 8 + w * h * 2;
			int nZoneBase = nSub0 + 8 + newW * newH * 2;
			int zw = w * 4, zh = h * 4, nzw = newW * 4, nzh = newH * 4;
			for (int zy = 0; zy < nzh; zy++) {
				for (int zx = 0; zx < nzw; zx++) {
					int got = u16(out, nZoneBase + (zy * nzw + zx) * 2);
					int want;
					if (zx < zw && zy < zh) {
						want = u16(mat, oZoneBase + (zy * zw + zx) * 2);
					} else {
						int rid = newIdsGrid[(zy / 4) * newW + (zx / 4)];
						want = rid != 0xFFFF ? PZONE : 0xFFFF;
					}
					if (got != want) {
						throw new IllegalStateException("zone seg " + zx + "," + zy + " = " + got + " want " + want);
					}
				}
			}
			int oLodBase = oZoneBase + zw * zh * 2;
			int nLodBase = nZoneBase + nzw * nzh * 2;
			for (int y = 0; y < newH; y++) {
				for (int x = 0; x < newW; x++) {
					int got = u16(out, nLodBase + (y * newW + x) * 2);
					int want = (x < w && y < h) ? u16(mat, oLodBase + (y * w + x) * 2) : 0xFFFF;
					if (got != want) {
						throw new IllegalStateException("LOD cell " + x + "," + y + " = " + got + " want " + want);
					}
				}
			}
		}
		//camera subfile: same length, entry count preserved
		int oCam0 = i32(mat, 8), oCam1 = i32(mat, 12);
		int nCam0 = i32(out, 8), nCam1 = i32(out, 12);
		if (oCam1 - oCam0 != nCam1 - nCam0) {
			throw new IllegalStateException("camera subfile size changed");
		}
		if (oCam1 - oCam0 >= 4 && i32(mat, oCam0) != i32(out, nCam0)) {
			throw new IllegalStateException("camera entry count changed");
		}
		//extra subfiles byte-identical
		for (int s = 2; s < count; s++) {
			int oa = ctrmap.formats.containers.ContainerBytes.offsets(mat)[s], ob = ctrmap.formats.containers.ContainerBytes.offsets(mat)[(s + 1)];
			int na = ctrmap.formats.containers.ContainerBytes.offsets(out)[s], nb = ctrmap.formats.containers.ContainerBytes.offsets(out)[(s + 1)];
			if (ob - oa != nb - na) {
				throw new IllegalStateException("extra subfile " + s + " size changed");
			}
			for (int k = 0; k < ob - oa; k++) {
				if (mat[oa + k] != out[na + k]) {
					throw new IllegalStateException("extra subfile " + s + " bytes changed");
				}
			}
		}
		//container well-formed: offsets contiguous, last == length
		if (ctrmap.formats.containers.ContainerBytes.offsets(out)[count] != out.length) {
			throw new IllegalStateException("container end offset wrong");
		}
	}
}
