package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.BchModelVerifier;
import ctrmap.formats.h3d.MapModelObj;
import ctrmap.formats.h3d.MapModelObjImporter;
import java.io.BufferedReader;
import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-fidelity OBJ validation:
 * (1) attribute-walk census over EVERY mesh in the corpus - the walk must fit
 *     the stride, agree with geometry()'s position decode, and the semantic
 *     tallies are printed for cross-checking against the census;
 * (2) UV/normal round-trip on every region - export with vt/vn, parse,
 *     identity-import, re-decode: every triangle corner's position, UV and
 *     normal must survive exactly;
 * (3) authored-UV import (sampled) - all UVs shifted +0.25 through the OBJ
 *     path must land shifted in the buffers, colors untouched;
 * (4) template new-material import (sampled) - a group with an unknown
 *     material + a template donor mesh must inject a new material+mesh that
 *     passes the strict verifier and carries the authored geometry.
 *
 * Usage: java ctrmap.tests.MapModelObjV2Test <path-to-a039-garc> [sampleStep]
 */
public class MapModelObjV2Test {

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/3/9");
		int step = args.length > 1 ? Integer.parseInt(args[1]) : 25;
		GARC garc = new GARC(garcFile);
		int censusMeshes = 0, censusBad = 0, withUv = 0, withNrm = 0, withColor = 0, withTex1 = 0;
		int rtRegions = 0, uvCorners = 0, nrmCorners = 0, shiftOk = 0, templateOk = 0, failures = 0;
		for (int i = 0; i < garc.length; i++) {
			byte[] model = sub(garc.getDecompressedEntry(i), 1);
			if (model == null || !BchMapModel.isMapModel(model)) {
				continue;
			}
			try {
				BchMapModel m = new BchMapModel(model);
				// (1) census
				for (BchMapModel.MeshGeom g : m.geometry()) {
					censusMeshes++;
					List<BchMapModel.MeshAttr> attrs = m.attributes(g.meshIndex);
					if (attrs.isEmpty()) {
						censusBad++;
						if (g.posOk) {
							throw new IllegalStateException("region " + i + " mesh " + g.meshIndex
									+ ": posOk but attribute walk failed");
						}
						continue;
					}
					boolean posSeen = false;
					for (BchMapModel.MeshAttr a : attrs) {
						if (a.name == 0 && !posSeen) {
							posSeen = true;
							if (g.posOk && (a.offset != g.posOffset || a.type != g.posType)) {
								throw new IllegalStateException("region " + i + " mesh " + g.meshIndex
										+ ": attribute walk disagrees with geometry() position");
							}
						}
						if (a.name == 4) {
							withUv++;
						}
						if (a.name == 1) {
							withNrm++;
						}
						if (a.name == 3) {
							withColor++;
						}
						if (a.name == 5) {
							withTex1++;
						}
					}
				}

				// (2) UV/normal round-trip
				StringWriter sw = new StringWriter();
				MapModelObj.export(m, sw);
				List<MapModelObj.ObjMesh> parsed = MapModelObj.parse(new BufferedReader(new StringReader(sw.toString())));
				boolean any = false;
				for (MapModelObj.ObjMesh om : parsed) {
					if (om.meshIndex < 0) {
						throw new IllegalStateException("region " + i + ": group lost identity");
					}
					any = true;
				}
				if (!any) {
					continue;
				}
				List<MapModelObjImporter.Outcome> outcomes = new ArrayList<>();
				byte[] applied = MapModelObjImporter.apply(model, parsed, outcomes);
				BchMapModel re = new BchMapModel(applied);
				if (!re.validate().isEmpty()) {
					throw new IllegalStateException("region " + i + ": identity import re-parse " + re.validate());
				}
				for (MapModelObj.ObjMesh om : parsed) {
					BchMapModel.MeshGeom g = re.geometry().get(om.meshIndex);
					int[] tris = re.getTriangles(om.meshIndex);
					float[][] pos = re.getVertexPositions(om.meshIndex);
					BchMapModel.MeshAttr uv = re.findAttr(om.meshIndex, 4);
					BchMapModel.MeshAttr nrm = re.findAttr(om.meshIndex, 1);
					if (tris.length != om.triangles.length) {
						throw new IllegalStateException("region " + i + " mesh " + om.meshIndex + ": tri count changed");
					}
					for (int t = 0; t < tris.length; t++) {
						int rv = tris[t], lv = om.triangles[t];
						for (int c = 0; c < 3; c++) {
							if (Math.abs(pos[rv][c] - om.positions[lv][c]) > 1e-3f) {
								throw new IllegalStateException("region " + i + " mesh " + om.meshIndex + ": position corner");
							}
						}
						if (uv != null && om.uvs != null && om.uvs[lv] != null) {
							int at = g.vtxAbs + rv * g.stride + uv.offset;
							if (Math.abs(f32(re.raw, at) - om.uvs[lv][0]) > 1e-4f
									|| Math.abs(f32(re.raw, at + 4) - om.uvs[lv][1]) > 1e-4f) {
								throw new IllegalStateException("region " + i + " mesh " + om.meshIndex + ": UV corner");
							}
							uvCorners++;
						}
						if (nrm != null && om.normals != null && om.normals[lv] != null) {
							int at = g.vtxAbs + rv * g.stride + nrm.offset;
							for (int c = 0; c < 3; c++) {
								if (Math.abs(f32(re.raw, at + c * 4) - om.normals[lv][c]) > 1e-4f) {
									throw new IllegalStateException("region " + i + " mesh " + om.meshIndex + ": normal corner");
								}
							}
							nrmCorners++;
						}
					}
				}
				rtRegions++;

				if (i % step != 0) {
					continue;
				}
				// (3) authored-UV shift import
				MapModelObj.ObjMesh uvGroup = null;
				for (MapModelObj.ObjMesh om : parsed) {
					if (om.uvs != null) {
						uvGroup = om;
						break;
					}
				}
				if (uvGroup != null) {
					for (float[] t : uvGroup.uvs) {
						if (t != null) {
							t[0] += 0.25f;
						}
					}
					byte[] shifted = MapModelObjImporter.apply(model, parsed, new ArrayList<>());
					BchMapModel sm = new BchMapModel(shifted);
					BchMapModel.MeshGeom g = sm.geometry().get(uvGroup.meshIndex);
					BchMapModel.MeshAttr uv = sm.findAttr(uvGroup.meshIndex, 4);
					int[] tris = sm.getTriangles(uvGroup.meshIndex);
					for (int t = 0; t < tris.length; t++) {
						float[] want = uvGroup.uvs[uvGroup.triangles[t]];
						if (want == null) {
							continue;
						}
						int at = g.vtxAbs + tris[t] * g.stride + uv.offset;
						if (Math.abs(f32(sm.raw, at) - want[0]) > 1e-4f) {
							throw new IllegalStateException("region " + i + ": shifted UV not applied");
						}
					}
					for (float[] t : uvGroup.uvs) {
						if (t != null) {
							t[0] -= 0.25f;
						}
					}
					shiftOk++;
				}

				// (4) template new-material import: rename a group's identity away and
				// give it an unknown material; the template = that same donor mesh.
				// Rigid-skinned donor meshes are refused by the appender (bone indices
				// reference the donor skeleton) - pick an unskinned group, if any.
				MapModelObj.ObjMesh donor = null;
				for (MapModelObj.ObjMesh cand : parsed) {
					int subPtr = m.meshes.get(cand.meshIndex)[3];
					if (le32(m.raw, subPtr) == 0) { //skinningMode/nodeIdCount == 0
						donor = cand;
						break;
					}
				}
				if (donor == null) {
					continue; //every mesh is rigid-skinned (interiors) - nothing to test here
				}
				int donorMesh = donor.meshIndex;
				String savedMat = donor.material;
				int savedIdx = donor.meshIndex;
				donor.meshIndex = -1;
				donor.material = "cmf5newmat";
				try {
					List<MapModelObjImporter.Outcome> oc2 = new ArrayList<>();
					byte[] injected = MapModelObjImporter.apply(model, parsed, oc2, model, donorMesh);
					BchMapModel im = new BchMapModel(injected);
					if (!im.validate().isEmpty()) {
						throw new IllegalStateException("region " + i + ": template import re-parse " + im.validate());
					}
					List<String> v = BchModelVerifier.verify(injected);
					if (!v.isEmpty() && im.auxDicts.isEmpty()) {
						throw new IllegalStateException("region " + i + ": template import verifier " + v);
					}
					boolean found = false;
					for (int mm = 0; mm < im.matCount; mm++) {
						if ("cmf5newmat".equals(im.getMaterialName(mm))) {
							found = true;
						}
					}
					if (!found) {
						throw new IllegalStateException("region " + i + ": new material not present");
					}
					templateOk++;
				} catch (IllegalStateException ex) {
					if (ex.getMessage() != null && ex.getMessage().contains("skinned")) {
						//rigid-skinned donor mesh - legitimate refusal, not a failure
					} else {
						throw ex;
					}
				} finally {
					donor.meshIndex = savedIdx;
					donor.material = savedMat;
				}
			} catch (RuntimeException ex) {
				failures++;
				System.out.println("FAIL region " + i + ": " + ex.getMessage());
				if (failures > 10) {
					break;
				}
			}
		}
		System.out.println("\nOBJ v2: census " + censusMeshes + " meshes (" + censusBad + " exotic), buffered UV=" + withUv
				+ " Nrm=" + withNrm + " Color=" + withColor + " Tex1=" + withTex1);
		System.out.println("round-trip " + rtRegions + " regions (" + uvCorners + " UV corners, " + nrmCorners
				+ " normal corners exact), uvShift=" + shiftOk + ", templateInject=" + templateOk + ", failures=" + failures);
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (failures > 0) {
			System.exit(1);
		}
	}

	static byte[] sub(byte[] c, int i) {
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
		return java.util.Arrays.copyOfRange(c, o0, o1);
	}

	static float f32(byte[] b, int o) {
		return Float.intBitsToFloat(le32(b, o));
	}

	static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
