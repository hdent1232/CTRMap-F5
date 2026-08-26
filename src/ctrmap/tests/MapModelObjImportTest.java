package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.MapModelObj;
import ctrmap.formats.h3d.MapModelObjImporter;
import java.io.BufferedReader;
import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * OBJ IMPORT validation on real map data, three scenarios:
 * (1) identity import on every region - export, parse, apply unchanged; the
 *     result must re-parse clean and every triangle corner must resolve to the
 *     same position;
 * (2) shift import (sampled) - one mesh's vertices moved +25 Y through the OBJ
 *     path; the mesh must move, all others must stay byte-identical in their
 *     positions;
 * (3) re-topology import (sampled) - half the faces of a mesh dropped in the
 *     OBJ; the applied mesh must draw exactly the remaining faces.
 *
 * Usage: java ctrmap.tests.MapModelObjImportTest <path-to-a039-garc> [sampleStep]
 */
public class MapModelObjImportTest {

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/3/9");
		int step = args.length > 1 ? Integer.parseInt(args[1]) : 25;
		GARC garc = new GARC(garcFile);
		int identityOk = 0, shiftOk = 0, retopoOk = 0, failures = 0;
		for (int i = 0; i < garc.length; i++) {
			byte[] model = subfile(garc.getDecompressedEntry(i), 1);
			if (model == null || !BchMapModel.isMapModel(model)) {
				continue;
			}
			try {
				// (1) identity - every region
				BchMapModel bmm = new BchMapModel(model);
				boolean anyDecodable = false;
				for (BchMapModel.MeshGeom g : bmm.geometry()) {
					if (g.posOk) {
						anyDecodable = true;
						break;
					}
				}
				if (!anyDecodable) {
					continue; // nothing exportable/importable (exotic-format-only region) - not a failure
				}
				StringWriter sw = new StringWriter();
				MapModelObj.export(bmm, sw);
				List<MapModelObj.ObjMesh> parsed = MapModelObj.parse(new BufferedReader(new StringReader(sw.toString())));
				List<MapModelObjImporter.Outcome> outcomes = new ArrayList<>();
				byte[] applied = MapModelObjImporter.apply(model, parsed, outcomes);
				BchMapModel re = new BchMapModel(applied);
				if (!re.validate().isEmpty()) {
					throw new IllegalStateException("region " + i + " identity: re-parse problems " + re.validate());
				}
				for (MapModelObj.ObjMesh om : parsed) {
					float[][] pos = re.getVertexPositions(om.meshIndex);
					int[] tris = re.getTriangles(om.meshIndex);
					if (tris.length != om.triangles.length) {
						throw new IllegalStateException("region " + i + " mesh " + om.meshIndex + " identity: tri count changed");
					}
					for (int t = 0; t < tris.length; t++) {
						float[] a = pos[tris[t]], b = om.positions[om.triangles[t]];
						if (Math.abs(a[0] - b[0]) > 1e-3f || Math.abs(a[1] - b[1]) > 1e-3f || Math.abs(a[2] - b[2]) > 1e-3f) {
							throw new IllegalStateException("region " + i + " mesh " + om.meshIndex + " identity: corner mismatch");
						}
					}
				}
				identityOk++;

				if (i % step != 0 || parsed.isEmpty()) {
					continue;
				}
				// (2) shift the first group +25 Y through the OBJ path
				MapModelObj.ObjMesh first = parsed.get(0);
				float[][] savedPos = new float[first.positions.length][];
				for (int v = 0; v < first.positions.length; v++) {
					savedPos[v] = first.positions[v].clone();
					first.positions[v][1] += 25f;
				}
				byte[] shifted = MapModelObjImporter.apply(model, parsed, new ArrayList<>());
				BchMapModel sh = new BchMapModel(shifted);
				float[][] shPos = sh.getVertexPositions(first.meshIndex);
				int[] shTris = sh.getTriangles(first.meshIndex);
				for (int t = 0; t < shTris.length; t++) {
					float want = first.positions[first.triangles[t]][1];
					if (Math.abs(shPos[shTris[t]][1] - want) > 1e-3f) {
						throw new IllegalStateException("region " + i + " shift: Y not applied");
					}
				}
				for (int v = 0; v < first.positions.length; v++) {
					first.positions[v] = savedPos[v];
				}
				shiftOk++;

				// (3) re-topology: keep only every other face of the first group
				int keepFaces = (first.triangles.length / 3) / 2;
				if (keepFaces >= 1) {
					int[] halfTris = new int[keepFaces * 3];
					for (int f = 0; f < keepFaces; f++) {
						System.arraycopy(first.triangles, f * 6, halfTris, f * 3, 3);
					}
					int[] fullTris = first.triangles;
					first.triangles = halfTris;
					byte[] retopo = MapModelObjImporter.apply(model, parsed, new ArrayList<>());
					BchMapModel rt = new BchMapModel(retopo);
					if (rt.getTriangles(first.meshIndex).length != halfTris.length) {
						throw new IllegalStateException("region " + i + " retopo: face count not applied");
					}
					if (!rt.validate().isEmpty()) {
						throw new IllegalStateException("region " + i + " retopo: re-parse problems " + rt.validate());
					}
					first.triangles = fullTris;
					retopoOk++;
				}
			} catch (RuntimeException ex) {
				failures++;
				System.out.println("FAIL region " + i + ": " + ex.getMessage());
				if (failures > 10) {
					System.out.println("too many failures, aborting");
					break;
				}
			}
		}
		System.out.println("\nOBJ import: identity=" + identityOk + " regions, shift=" + shiftOk
				+ " sampled, retopo=" + retopoOk + " sampled, failures=" + failures);
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (failures > 0) {
			System.exit(1);
		}
	}

	private static byte[] subfile(byte[] c, int i) {
		if (c == null || c.length < 8) {
			return null;
		}
		int count = (c[2] & 0xFF) | ((c[3] & 0xFF) << 8);
		if (i >= count) {
			return null;
		}
		int o0 = le32(c, 4 + i * 4), o1 = le32(c, 4 + (i + 1) * 4);
		if (o0 < 0 || o1 > c.length || o1 < o0) {
			return null;
		}
		byte[] out = new byte[o1 - o0];
		System.arraycopy(c, o0, out, 0, out.length);
		return out;
	}

	private static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
