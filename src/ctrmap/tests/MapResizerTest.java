package ctrmap.tests;

import ctrmap.MapResizer;
import ctrmap.formats.garc.GARC;
import java.io.File;

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
			int sub0 = le32(mat, 4);
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
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (failures > 0) {
			System.exit(1);
		}
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
		int oldSub0 = le32(mat, 4);
		int hasLOD = u16(mat, oldSub0);
		int nSub0 = le32(out, 4);
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
		int oCam0 = le32(mat, 8), oCam1 = le32(mat, 12);
		int nCam0 = le32(out, 8), nCam1 = le32(out, 12);
		if (oCam1 - oCam0 != nCam1 - nCam0) {
			throw new IllegalStateException("camera subfile size changed");
		}
		if (oCam1 - oCam0 >= 4 && le32(mat, oCam0) != le32(out, nCam0)) {
			throw new IllegalStateException("camera entry count changed");
		}
		//extra subfiles byte-identical
		for (int s = 2; s < count; s++) {
			int oa = le32(mat, 4 + s * 4), ob = le32(mat, 4 + (s + 1) * 4);
			int na = le32(out, 4 + s * 4), nb = le32(out, 4 + (s + 1) * 4);
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
		if (le32(out, 4 + count * 4) != out.length) {
			throw new IllegalStateException("container end offset wrong");
		}
	}

	static int u16(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
	}

	static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
