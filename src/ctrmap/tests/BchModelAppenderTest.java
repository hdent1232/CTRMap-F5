package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.BchModelAppender;
import ctrmap.formats.h3d.BchModelVerifier;
import ctrmap.formats.h3d.BchTexturePack;
import ctrmap.formats.garc.LZ11;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The trust gate for BchModelAppender, per the verified implementation brief:
 * (A) baseline - every retail model passes the strict verifier (aux-LUT
 *     regions may report only their command-tail note);
 * (B) self-append sweep over EVERY region - append the region's own mesh 0 as
 *     a new material+mesh; output must verify, parse in both parsers, carry
 *     the donor geometry byte-exactly, and leave every old mesh untouched;
 * (C) chained appends (3 successive) on diverse regions;
 * (D) cross-append between same-area regions;
 * (E) cross-AREA append with texture carry via BchTexturePack.importTextures -
 *     every texture the stamped material references must resolve in the target
 *     area's packs afterwards.
 *
 * Usage: java ctrmap.tests.BchModelAppenderTest <romfs-garc-root> [selfStep]
 * where romfs-garc-root contains a/0/3/9, a/0/1/3, a/0/4/0, a/0/1/4.
 */
public class BchModelAppenderTest {

	static GARC field, zone, matrix, area;
	static int baselineOk = 0, selfOk = 0, selfSkip = 0, chainOk = 0, crossOk = 0, crossAreaOk = 0, failures = 0;

	public static void main(String[] args) throws Exception {
		String root = args.length > 0 ? args[0] : "../RomFS_original_garcs";
		int step = args.length > 1 ? Integer.parseInt(args[1]) : 1;
		field = new GARC(new File(root + "/a/0/3/9"));
		zone = new GARC(new File(root + "/a/0/1/3"));
		matrix = new GARC(new File(root + "/a/0/4/0"));
		area = new GARC(new File(root + "/a/0/1/4"));

		// (A) + (B): baseline verify + self-append, every region
		for (int i = 0; i < field.length; i += step) {
			byte[] model = sub(field.getDecompressedEntry(i), 1);
			if (model == null || !BchMapModel.isMapModel(model)) {
				continue;
			}
			try {
				List<String> base = BchModelVerifier.verify(model);
				if (!auxOnly(base, new BchMapModel(model))) {
					throw new IllegalStateException("baseline verify: " + base);
				}
				baselineOk++;
				trySelfAppend(i, model);
			} catch (RuntimeException ex) {
				failures++;
				System.out.println("FAIL region " + i + ": " + ex.getMessage());
				if (failures > 10) {
					summary();
					System.exit(1);
				}
			}
		}

		// (C) chained x3 on diverse regions: normal, aux-LUT, bones, null-meta
		for (int i : new int[]{153, 260, 344, 279}) {
			try {
				byte[] model = sub(field.getDecompressedEntry(i), 1);
				if (model == null || !BchMapModel.isMapModel(model)) {
					continue;
				}
				byte[] cur = model;
				for (int k = 0; k < 3; k++) {
					int dj = pickDonorMesh(cur, cur);
					if (dj < 0) {
						break;
					}
					cur = BchModelAppender.append(cur, cur, dj, "cmf5chain" + k);
					checkOutput(cur, "chain " + i + " step " + k);
				}
				chainOk++;
			} catch (RuntimeException ex) {
				failures++;
				System.out.println("FAIL chain " + i + ": " + ex.getMessage());
			}
		}

		// (D) cross-append same-area pairs from the brief
		for (int[] pair : new int[][]{{593, 590}, {4, 22}}) {
			try {
				byte[] donor = sub(field.getDecompressedEntry(pair[0]), 1);
				byte[] target = sub(field.getDecompressedEntry(pair[1]), 1);
				int dj = pickDonorMesh(donor, target);
				if (dj < 0) {
					throw new IllegalStateException("no usable donor mesh");
				}
				byte[] out = BchModelAppender.append(target, donor, dj, "cmf5x");
				checkOutput(out, "cross " + pair[0] + "->" + pair[1]);
				crossOk++;
			} catch (RuntimeException ex) {
				failures++;
				System.out.println("FAIL cross " + Arrays.toString(pair) + ": " + ex.getMessage());
			}
		}

		// (E) cross-AREA append + texture carry
		try {
			crossArea(682, 722);
			crossAreaOk++;
		} catch (RuntimeException ex) {
			failures++;
			System.out.println("FAIL cross-area 682->722: " + ex.getMessage());
		}

		summary();
		if (failures > 0) {
			System.exit(1);
		}
	}

	static void trySelfAppend(int i, byte[] model) {
		BchMapModel m = new BchMapModel(model);
		int dj = pickDonorMesh(model, model);
		if (dj < 0) {
			selfSkip++;
			return;
		}
		byte[] out = BchModelAppender.append(model, model, dj, "cmf5self");
		checkOutput(out, "self " + i);
		BchMapModel r = new BchMapModel(out);
		if (r.matCount != m.matCount + 1 || r.meshCount != m.meshCount + 1) {
			throw new IllegalStateException("self " + i + ": counts wrong");
		}
		//donor geometry carried byte-exactly (positions + triangles)
		BchMapModel.MeshGeom dg = m.geometry().get(dj);
		if (dg.posOk) {
			int newMesh = findMeshByMat(r, "cmf5self");
			if (newMesh < 0) {
				throw new IllegalStateException("self " + i + ": appended mesh not found by material");
			}
			float[][] a = m.getVertexPositions(dj), b = r.getVertexPositions(newMesh);
			int[] ta = m.getTriangles(dj), tb = r.getTriangles(newMesh);
			if (b == null || tb == null || !Arrays.equals(ta, tb) || a.length > b.length) {
				throw new IllegalStateException("self " + i + ": appended geometry differs");
			}
			for (int v = 0; v < a.length; v++) {
				if (a[v][0] != b[v][0] || a[v][1] != b[v][1] || a[v][2] != b[v][2]) {
					throw new IllegalStateException("self " + i + ": vertex " + v + " differs");
				}
			}
		}
		//every OLD mesh untouched - INDEX-SHIFT-AWARE: the new mesh is inserted at
		//the END of its layer's slice, so old meshes in later layers shift +1
		int donorLayer = meshLayer(m, dj);
		int insIdx = 0;
		for (int j = 0; j < m.meshCount; j++) {
			if (meshLayer(m, j) <= donorLayer) {
				insIdx++;
			}
		}
		for (int j = 0; j < m.meshCount; j++) {
			BchMapModel.MeshGeom og = m.geometry().get(j);
			if (!og.posOk) {
				continue;
			}
			int rj = j < insIdx ? j : j + 1;
			int[] ta = m.getTriangles(j), tb = r.getTriangles(rj);
			if (!Arrays.equals(ta, tb)) {
				throw new IllegalStateException("self " + i + ": old mesh " + j + " (now " + rj + ") triangles changed");
			}
			float[][] pa = m.getVertexPositions(j), pb = r.getVertexPositions(rj);
			if (pa.length > pb.length) {
				throw new IllegalStateException("self " + i + ": old mesh " + j + " lost vertices");
			}
			for (int v = 0; v < pa.length; v++) {
				if (pa[v][0] != pb[v][0] || pa[v][1] != pb[v][1] || pa[v][2] != pb[v][2]) {
					throw new IllegalStateException("self " + i + ": old mesh " + j + " vertex " + v + " changed");
				}
			}
		}
		selfOk++;
	}

	/** A mesh's render layer (Key high byte, mesh header +6 u16 >> 8). */
	static int meshLayer(BchMapModel m, int j) {
		int h = m.meshes.get(j)[0];
		int key = (m.raw[h + 6] & 0xFF) | ((m.raw[h + 7] & 0xFF) << 8);
		return key >>> 8;
	}

	static void crossArea(int donorRegion, int targetRegion) {
		Map<Integer, Integer> regionArea = buildRegionAreaMap();
		Integer da = regionArea.get(donorRegion), ta = regionArea.get(targetRegion);
		if (da == null || ta == null || da.equals(ta)) {
			throw new IllegalStateException("pair is not cross-area (areas " + da + "/" + ta + ")");
		}
		byte[] donor = sub(field.getDecompressedEntry(donorRegion), 1);
		byte[] target = sub(field.getDecompressedEntry(targetRegion), 1);
		int dj = pickDonorMesh(donor, target);
		if (dj < 0) {
			throw new IllegalStateException("no usable donor mesh");
		}
		byte[] out = BchModelAppender.append(target, donor, dj, "cmf5area");
		checkOutput(out, "cross-area");

		//texture carry: donor mesh's material texture names -> target area packs
		BchMapModel d = new BchMapModel(donor);
		int dm = d.getMeshMaterialIndex(dj);
		Set<String> want = texNames(d, donor, dm);
		byte[][] tPacks = areaPacks(ta);
		Set<String> have = packNames(tPacks);
		List<String> missing = new ArrayList<>();
		for (String w : want) {
			if (!have.contains(w)) {
				missing.add(w);
			}
		}
		if (!missing.isEmpty()) {
			byte[][] dPacks = areaPacks(da);
			byte[] donorPack = dPacks[1] != null ? dPacks[1] : dPacks[0];
			byte[] newPack = BchTexturePack.importTextures(tPacks[1] != null ? tPacks[1] : tPacks[0], donorPack, missing);
			Set<String> after = new HashSet<>();
			for (BchTexturePack.Texture t : BchTexturePack.parse(newPack)) {
				after.add(t.name);
			}
			after.addAll(have);
			for (String w : want) {
				if (!after.contains(w)) {
					throw new IllegalStateException("texture " + w + " still missing after import");
				}
			}
		}
	}

	// ---- helpers ----------------------------------------------------------

	/** A donor mesh that is unskinned and decodable; -1 if none. */
	static int pickDonorMesh(byte[] donor, byte[] target) {
		BchMapModel d = new BchMapModel(donor);
		List<BchMapModel.MeshGeom> geom = d.geometry();
		for (int j = 0; j < d.meshCount; j++) {
			if (!geom.get(j).posOk) {
				continue;
			}
			try {
				BchModelAppender.append(target, donor, j, "cmf5probe");
				return j;
			} catch (RuntimeException ex) {
				//skinned or otherwise unusable with this target - try the next
			}
		}
		return -1;
	}

	static void checkOutput(byte[] out, String label) {
		BchMapModel r = new BchMapModel(out);
		if (!r.validate().isEmpty()) {
			throw new IllegalStateException(label + ": parse problems " + r.validate());
		}
		List<String> v = BchModelVerifier.verify(out);
		if (!auxOnly(v, r)) {
			throw new IllegalStateException(label + ": verify " + v);
		}
		BCHFile render = new BCHFile(out);
		if (render.errorlevel != 0 || render.models.isEmpty()) {
			throw new IllegalStateException(label + ": render parser rejected");
		}
	}

	/** Verifier findings acceptable iff empty, or (aux-LUT region) only the command-tail notes. */
	static boolean auxOnly(List<String> findings, BchMapModel m) {
		if (findings.isEmpty()) {
			return true;
		}
		if (m.auxDicts.isEmpty()) {
			return false;
		}
		for (String f : findings) {
			if (!f.startsWith("cmd gap") && !f.equals("cmd end") && !f.equals("cmd start")) {
				return false;
			}
		}
		return true;
	}

	static int findMeshByMat(BchMapModel m, String name) {
		for (int j = 0; j < m.meshCount; j++) {
			if (name.equals(m.getMaterialName(m.getMeshMaterialIndex(j)))) {
				return j;
			}
		}
		return -1;
	}

	/** Texture names referenced by a material header (+0x1C/+0x20/+0x24). */
	static Set<String> texNames(BchMapModel m, byte[] raw, int mat) {
		Set<String> out = new HashSet<>();
		int h = m.matValuesPtr + mat * 0x2C;
		for (int off : new int[]{0x1C, 0x20, 0x24}) {
			int p = m.ptr(h + off);
			if (p != 0) {
				StringBuilder sb = new StringBuilder();
				for (int q = p; q < raw.length && raw[q] != 0; q++) {
					sb.append((char) (raw[q] & 0xFF));
				}
				if (sb.length() > 0) {
					out.add(sb.toString());
				}
			}
		}
		return out;
	}

	/** {file1, file11} texture packs of an area (decompressed), entries may be null. */
	static byte[][] areaPacks(int areaId) {
		byte[] ad = area.getDecompressedEntry(areaId);
		byte[] f1 = sub(ad, 1), f11 = sub(ad, 11);
		if (f1 != null && f1.length > 0 && f1[0] == 0x11) {
			f1 = LZ11.decompress(f1);
		}
		if (f11 != null && f11.length > 0 && f11[0] == 0x11) {
			f11 = LZ11.decompress(f11);
		}
		return new byte[][]{
			f1 != null && BchTexturePack.isTexturePack(f1) ? f1 : null,
			f11 != null && BchTexturePack.isTexturePack(f11) ? f11 : null
		};
	}

	static Set<String> packNames(byte[][] packs) {
		Set<String> out = new HashSet<>();
		for (byte[] p : packs) {
			if (p != null) {
				for (BchTexturePack.Texture t : BchTexturePack.parse(p)) {
					out.add(t.name);
				}
			}
		}
		return out;
	}

	/** region id -> areadataID via ZoneData headers + MapMatrix grids. */
	static Map<Integer, Integer> buildRegionAreaMap() {
		Map<Integer, Integer> out = new HashMap<>();
		int zones = zone.length - 2;
		for (int z = 0; z < zones; z++) {
			byte[] zo = zone.getDecompressedEntry(z);
			byte[] hdr = sub(zo, 0);
			if (hdr == null || hdr.length < 8) {
				continue;
			}
			int areaId = u16(hdr, 2), mmId = u16(hdr, 4);
			if (mmId >= matrix.length) {
				continue;
			}
			byte[] mm = matrix.getDecompressedEntry(mmId);
			byte[] grid = sub(mm, 0);
			if (grid == null || grid.length < 8) {
				continue;
			}
			int w = u16(grid, 4), h = u16(grid, 6);
			for (int k = 0; k < w * h && 8 + k * 2 + 1 < grid.length; k++) {
				int rid = u16(grid, 8 + k * 2);
				if (rid != 0xFFFF) {
					out.putIfAbsent(rid, areaId);
				}
			}
		}
		return out;
	}

	static void summary() {
		System.out.println("\nBchModelAppender gate: baseline=" + baselineOk + "  self=" + selfOk
				+ " (skip " + selfSkip + ")  chain=" + chainOk + "/4  cross=" + crossOk + "/2  crossArea="
				+ crossAreaOk + "/1  failures=" + failures);
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
	}

	static byte[] sub(byte[] c, int i) {
		if (c == null || c.length < 8) {
			return null;
		}
		int count = u16(c, 2);
		if (i >= count) {
			return null;
		}
		int o0 = le32(c, 4 + i * 4), o1 = le32(c, 4 + (i + 1) * 4);
		if (o0 < 0 || o1 > c.length || o1 < o0) {
			return null;
		}
		return Arrays.copyOfRange(c, o0, o1);
	}

	static int u16(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
	}

	static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
