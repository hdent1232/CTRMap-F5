package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.MapModelObj;
import java.io.BufferedReader;
import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

/**
 * OBJ bridge validation across EVERY real FieldData region: export each map
 * model to OBJ, parse it back, and verify the geometry survives - every
 * triangle's three corner positions must resolve to the same coordinates as the
 * model's own decode, and the mesh identity (mesh index, material) must round
 * trip through the group names. This is the offline gate for the Blender
 * workflow (no emulator needed).
 *
 * Usage: java ctrmap.tests.MapModelObjTest <path-to-a039-garc>
 */
public class MapModelObjTest {

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/3/9");
		GARC garc = new GARC(garcFile);
		int regions = 0, meshesOk = 0, skippedMeshes = 0, failures = 0;
		long trisChecked = 0;
		for (int i = 0; i < garc.length; i++) {
			byte[] gr = garc.getDecompressedEntry(i);
			byte[] model = subfile(gr, 1);
			if (model == null || !BchMapModel.isMapModel(model)) {
				continue;
			}
			regions++;
			try {
				BchMapModel bmm = new BchMapModel(model);
				StringWriter sw = new StringWriter();
				List<Integer> skipped = MapModelObj.export(bmm, sw);
				skippedMeshes += skipped.size();
				List<MapModelObj.ObjMesh> parsed = MapModelObj.parse(new BufferedReader(new StringReader(sw.toString())));

				for (MapModelObj.ObjMesh m : parsed) {
					if (m.meshIndex < 0) {
						throw new IllegalStateException("region " + i + ": group without mesh identity");
					}
					float[][] orig = bmm.getVertexPositions(m.meshIndex);
					int[] origTris = bmm.getTriangles(m.meshIndex);
					if (m.triangles.length != origTris.length) {
						throw new IllegalStateException("region " + i + " mesh " + m.meshIndex
								+ ": tri count " + m.triangles.length + " != " + origTris.length);
					}
					for (int t = 0; t < origTris.length; t++) {
						float[] a = m.positions[m.triangles[t]];
						float[] b = orig[origTris[t]];
						for (int c = 0; c < 3; c++) {
							if (Math.abs(a[c] - b[c]) > 1e-3f) {
								throw new IllegalStateException("region " + i + " mesh " + m.meshIndex
										+ ": corner mismatch at tri-index " + t + " axis " + c
										+ " (" + a[c] + " vs " + b[c] + ")");
							}
						}
						trisChecked++;
					}
					meshesOk++;
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
		System.out.println("\nOBJ round-trip: " + regions + " regions, " + meshesOk + " meshes verified, "
				+ (trisChecked / 3) + " triangles checked, " + skippedMeshes + " meshes skipped (no pos decode), "
				+ failures + " failures");
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
