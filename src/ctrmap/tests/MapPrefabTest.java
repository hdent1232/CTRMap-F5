package ctrmap.tests;

import ctrmap.formats.containers.GR;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.MapPrefab;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Prefab pipeline validation on real regions (sampled): a tile box is cut out
 * of region A and stamped back (self-stamp at a shifted anchor - every material
 * guaranteed present) plus into a different region (cross-stamp - missing
 * materials allowed, stamped pieces verified):
 * - extraction: pieces carry full strides + local tris + collision + tiles;
 * - stamping: appended geometry lands EXACTLY at anchor+relative positions,
 *   result re-parses clean in BOTH parsers (BchMapModel + the render parser);
 * - collision stamp: exact triangle-count growth, rebuilt file valid;
 * - save/load: byte-faithful prefab file round-trip.
 *
 * Usage: java ctrmap.tests.MapPrefabTest <path-to-a039-garc> [sampleStep]
 */
public class MapPrefabTest {

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/3/9");
		int step = args.length > 1 ? Integer.parseInt(args[1]) : 30;
		File scratch = new File(System.getProperty("java.io.tmpdir"), "ctrmap_prefab_test");
		scratch.mkdirs();
		GARC garc = new GARC(garcFile);
		int tested = 0, selfOk = 0, crossOk = 0, crossSkipped = 0, roundtripOk = 0, failures = 0, emptyBox = 0;
		for (int i = 0; i < garc.length; i += step) {
			try {
				GR grA = tempGR(scratch, garc, i);
				if (grA == null || !BchMapModel.isMapModel(grA.getFile(1))) {
					continue;
				}
				MapPrefab p = MapPrefab.extract(grA, 12, 12, 27, 27, "test");
				if (p == null) {
					emptyBox++;
					continue;
				}
				tested++;

				// save/load round-trip
				File pf = new File(scratch, "p" + i + ".ctrprefab");
				p.save(pf);
				MapPrefab p2 = MapPrefab.load(pf);
				if (p2.pieces.size() != p.pieces.size() || p2.collTris.size() != p.collTris.size()
						|| !java.util.Arrays.equals(p2.pieces.get(0).vertexBytes, p.pieces.get(0).vertexBytes)) {
					throw new IllegalStateException("save/load mismatch");
				}
				roundtripOk++;

				// SELF-stamp at tile (2,2): all materials must match; verify positions
				verifyStamp(p, grA.getFile(1), 2, 2, 50f, true, "self");
				byte[] collA = grA.getFile(2);
				if (GfColl.isColl(collA) && !p.collTris.isEmpty()) {
					GfColl before = new GfColl(collA);
					GfColl after = new GfColl(p.stampCollision(collA, 2, 2, 50f));
					if (after.uniqueTris.size() != before.uniqueTris.size() + p.collTris.size()) {
						throw new IllegalStateException("self coll stamp: expected +" + p.collTris.size()
								+ " tris, got +" + (after.uniqueTris.size() - before.uniqueTris.size()));
					}
				}
				selfOk++;

				// CROSS-stamp into the next testable region
				GR grB = null;
				for (int j = i + 1; j < Math.min(i + 12, garc.length) && grB == null; j++) {
					GR cand = tempGR(scratch, garc, j);
					if (cand != null && BchMapModel.isMapModel(cand.getFile(1))) {
						grB = cand;
					}
				}
				if (grB != null) {
					MapPrefab.StampResult r = verifyStamp(p, grB.getFile(1), 4, 4, 0f, false, "cross");
					if (r.stamped.isEmpty()) {
						crossSkipped++; //no shared materials - fine, the full-append path covers this later
					} else {
						crossOk++;
					}
				}
			} catch (RuntimeException ex) {
				failures++;
				System.out.println("FAIL region " + i + ": " + ex.getMessage());
				if (failures > 8) {
					break;
				}
			}
		}
		System.out.println("\nMapPrefab: tested=" + tested + " (emptyBox=" + emptyBox + ")  self=" + selfOk
				+ "  cross=" + crossOk + " (no-shared-mats " + crossSkipped + ")  saveload=" + roundtripOk
				+ "  failures=" + failures);
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (failures > 0) {
			System.exit(1);
		}
	}

	/** Stamps and verifies every stamped piece's geometry position-exactly. */
	private static MapPrefab.StampResult verifyStamp(MapPrefab p, byte[] targetModel,
			int tileX, int tileY, float dy, boolean requireAll, String label) {
		MapPrefab.StampResult r = p.stampGeometry(targetModel, tileX, tileY, dy);
		if (requireAll && !r.missingMaterials.isEmpty()) {
			throw new IllegalStateException(label + ": unexpected missing materials " + r.missingMaterials);
		}
		if (r.stamped.isEmpty()) {
			return r;
		}
		BchMapModel re = new BchMapModel(r.newModel);
		if (!re.validate().isEmpty()) {
			throw new IllegalStateException(label + ": stamped model re-parse problems " + re.validate());
		}
		//render-parser acceptance (the second oracle)
		ctrmap.formats.h3d.BCHFile render = new ctrmap.formats.h3d.BCHFile(r.newModel);
		if (render.errorlevel != 0 || render.models.isEmpty()) {
			throw new IllegalStateException(label + ": render parser rejected the stamped model");
		}
		//geometry landing check: replay the stamp's mesh-selection order, tracking
		//each target mesh's cumulative append base (several pieces can share a mesh)
		BchMapModel before = new BchMapModel(targetModel);
		java.util.Map<Integer, Integer> base = new java.util.HashMap<>();
		float ax = tileX * 18f - 360f, az = tileY * 18f - 360f;
		for (MapPrefab.Piece piece : p.pieces) {
			int mesh = MapPrefab.findTargetMesh(before, piece);
			if (mesh < 0) {
				continue; //was a missing-material/layout skip
			}
			int nOld = base.computeIfAbsent(mesh, m -> before.geometry().get(m).vertexCount);
			float[][] pos = re.getVertexPositions(mesh);
			int n = piece.vertexBytes.length / piece.stride;
			if (pos.length < nOld + n) {
				throw new IllegalStateException(label + ": mesh " + mesh + " did not grow by piece size");
			}
			for (int v = 0; v < n; v++) {
				float relX = getF(piece.vertexBytes, v * piece.stride + piece.posOffset);
				float relY = getF(piece.vertexBytes, v * piece.stride + piece.posOffset + 4);
				float relZ = getF(piece.vertexBytes, v * piece.stride + piece.posOffset + 8);
				float[] got = pos[nOld + v];
				if (Math.abs(got[0] - (relX + ax)) > 1e-3f || Math.abs(got[1] - (relY + dy)) > 1e-3f
						|| Math.abs(got[2] - (relZ + az)) > 1e-3f) {
					throw new IllegalStateException(label + ": piece landed at wrong position (v" + v + ")");
				}
			}
			base.put(mesh, nOld + n);
		}
		return r;
	}

	private static GR tempGR(File scratch, GARC garc, int index) throws Exception {
		byte[] entry = garc.getDecompressedEntry(index);
		if (entry == null || entry.length < 8) {
			return null;
		}
		File f = new File(scratch, "gr" + index + ".bin");
		try (FileOutputStream fos = new FileOutputStream(f)) {
			fos.write(entry);
		}
		return new GR(f);
	}

	private static float getF(byte[] b, int o) {
		return Float.intBitsToFloat((b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24));
	}
}
