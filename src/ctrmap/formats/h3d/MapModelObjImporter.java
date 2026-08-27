package ctrmap.formats.h3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a parsed OBJ (see {@link MapModelObj}) back onto a map model - the
 * import half of the Blender workflow, at full attribute fidelity:
 * <ul>
 * <li>positions come from the OBJ ({@code v});</li>
 * <li>texture coordinates come from the OBJ ({@code vt}) when authored - THIS
 *     is what lets a Blender-built structure carry its own UV mapping;</li>
 * <li>normals come from the OBJ ({@code vn}) when authored;</li>
 * <li>colors and any other buffered attribute are inherited from the ORIGINAL
 *     surface by nearest-neighbor (vertex colors are baked lighting - the sane
 *     default), encoded in the mesh's own format (u8/255, s8/127 - never -128).</li>
 * </ul>
 *
 * Groups with a mesh identity ({@code mesh<N>_...}) REPLACE that mesh via
 * {@link BchMapModel#setMeshGeometry}; identity-less groups append to the mesh
 * whose material matches their {@code usemtl}; and when a TEMPLATE donor is
 * given, groups with a material the model does not have get a BRAND-NEW
 * material+mesh injected via {@link BchModelAppender} (cloning the template's
 * render config) before the geometry lands.
 */
public class MapModelObjImporter {

	/** Per-group outcome for user-facing reporting. */
	public static class Outcome {

		public String group;
		public int meshIndex;
		public String action;   // "replaced", "appended", "new material", "skipped: <why>"
		public int vertices;
		public int faces;
	}

	public static byte[] apply(byte[] modelBytes, List<MapModelObj.ObjMesh> objMeshes, List<Outcome> outcomes) {
		return apply(modelBytes, objMeshes, outcomes, null, -1);
	}

	/**
	 * Applies the parsed meshes onto the model, returning the new BCH bytes and
	 * filling {@code outcomes}. With a template donor (any map model + a mesh
	 * index in it), groups whose material the target lacks are created as new
	 * material+mesh from the template. Never throws for a single bad group -
	 * it is reported and skipped; throws only if NOTHING could be applied.
	 */
	public static byte[] apply(byte[] modelBytes, List<MapModelObj.ObjMesh> objMeshes, List<Outcome> outcomes,
			byte[] templateModel, int templateMesh) {
		byte[] current = modelBytes;
		int applied = 0;
		for (MapModelObj.ObjMesh om : objMeshes) {
			Outcome oc = new Outcome();
			oc.group = om.groupName();
			oc.meshIndex = om.meshIndex;
			oc.vertices = om.positions.length;
			oc.faces = om.triangles.length / 3;
			outcomes.add(oc);
			try {
				BchMapModel model = new BchMapModel(current);
				int target = om.meshIndex;
				boolean append = false;
				if (target < 0) {
					target = findMeshByMaterial(model, om.material);
					append = target >= 0;
					if (target < 0 && templateModel != null && templateMesh >= 0) {
						//NEW MATERIAL: clone the template's render config under this
						//group's material name, then land the group's geometry in it
						String matName = uniqueName(model, MapModelObj.sanitize(om.material));
						current = BchModelAppender.append(current, templateModel, templateMesh, matName);
						model = new BchMapModel(current);
						target = findMeshByExactMaterial(model, matName);
						if (target < 0) {
							oc.action = "skipped: template injection did not land";
							continue;
						}
						BchMapModel.MeshGeom g = model.geometry().get(target);
						byte[] vtx = buildVertexBytes(model, g, om);
						current = model.setMeshGeometry(target, vtx, om.triangles.clone());
						oc.action = "new material " + matName + " (from template)";
						applied++;
						continue;
					}
					if (target < 0) {
						oc.action = "skipped: no mesh identity and no material named '" + om.material + "'";
						continue;
					}
				}
				List<BchMapModel.MeshGeom> geom = model.geometry();
				if (target >= geom.size() || !geom.get(target).posOk) {
					oc.action = "skipped: mesh " + target + " not editable";
					continue;
				}
				BchMapModel.MeshGeom g = geom.get(target);
				byte[] vtx = buildVertexBytes(model, g, om);
				if (append) {
					int base = g.vertexCount;
					int[] tris = new int[om.triangles.length];
					for (int i = 0; i < tris.length; i++) {
						tris[i] = base + om.triangles[i];
					}
					current = model.appendGeometry(target, vtx, tris);
					oc.action = "appended to mesh " + target + " (" + model.getMaterialName(model.getMeshMaterialIndex(target)) + ")";
				} else {
					current = model.setMeshGeometry(target, vtx, om.triangles);
					oc.action = "replaced";
				}
				applied++;
			} catch (RuntimeException ex) {
				oc.action = "skipped: " + ex.getMessage();
			}
		}
		if (applied == 0) {
			throw new IllegalStateException("No OBJ group could be applied to the model.");
		}
		return current;
	}

	/**
	 * Encodes vertices in the mesh's exact attribute layout: every attribute
	 * starts as the nearest original vertex's bytes (exact position match wins),
	 * then position - and authored UVs/normals when the OBJ carries them and the
	 * mesh buffers that attribute - are written over it in the mesh's format.
	 */
	static byte[] buildVertexBytes(BchMapModel model, BchMapModel.MeshGeom g, MapModelObj.ObjMesh om) {
		float[][] orig = model.getVertexPositions(g.meshIndex);
		Map<Long, Integer> exact = new HashMap<>();
		for (int v = 0; v < orig.length; v++) {
			exact.putIfAbsent(key(orig[v]), v);
		}
		BchMapModel.MeshAttr uvAttr = model.findAttr(g.meshIndex, 4);
		BchMapModel.MeshAttr nrmAttr = model.findAttr(g.meshIndex, 1);
		byte[] out = new byte[om.positions.length * g.stride];
		for (int v = 0; v < om.positions.length; v++) {
			float[] p = om.positions[v];
			Integer src = exact.get(key(p));
			if (src == null) {
				src = nearest(orig, p);
			}
			int at = v * g.stride;
			System.arraycopy(model.raw, g.vtxAbs + src * g.stride, out, at, g.stride);
			putComp(out, at + g.posOffset, 3, p[0]);
			putComp(out, at + g.posOffset + 4, 3, p[1]);
			putComp(out, at + g.posOffset + 8, 3, p[2]);
			if (uvAttr != null && om.uvs != null && om.uvs[v] != null) {
				writeAttr(out, at + uvAttr.offset, uvAttr, om.uvs[v]);
			}
			if (nrmAttr != null && om.normals != null && om.normals[v] != null) {
				writeAttr(out, at + nrmAttr.offset, nrmAttr, om.normals[v]);
			}
		}
		return out;
	}

	/** Writes as many components as both the attribute and the value carry. */
	private static void writeAttr(byte[] out, int at, BchMapModel.MeshAttr a, float[] val) {
		int n = Math.min(a.elems, val.length);
		int compSize = a.size() / a.elems;
		for (int c = 0; c < n; c++) {
			putComp(out, at + c * compSize, a.type, val[c]);
		}
	}

	/** Encodes one component in a PICA format (game conventions: u8/255, s8/127 never -128). */
	private static void putComp(byte[] b, int o, int type, float v) {
		switch (type) {
			case 3:
				putFloatLE(b, o, v);
				break;
			case 1:
				b[o] = (byte) Math.max(0, Math.min(255, Math.round(v * 255f)));
				break;
			case 0:
				b[o] = (byte) Math.max(-127, Math.min(127, Math.round(v * 127f)));
				break;
			default: //s16 - not observed in map models; natural normalization for safety
				int s = Math.max(-32767, Math.min(32767, Math.round(v * 32767f)));
				b[o] = (byte) s;
				b[o + 1] = (byte) (s >> 8);
				break;
		}
	}

	private static int nearest(float[][] orig, float[] p) {
		int best = 0;
		float bestD = Float.MAX_VALUE;
		for (int v = 0; v < orig.length; v++) {
			float dx = orig[v][0] - p[0], dy = orig[v][1] - p[1], dz = orig[v][2] - p[2];
			float d = dx * dx + dy * dy + dz * dz;
			if (d < bestD) {
				bestD = d;
				best = v;
			}
		}
		return best;
	}

	/** Position key quantized to 1/64 world unit - float-format round-trip safe. */
	private static long key(float[] p) {
		long x = Math.round(p[0] * 64f) & 0x1FFFFF;
		long y = Math.round(p[1] * 64f) & 0x1FFFFF;
		long z = Math.round(p[2] * 64f) & 0x1FFFFF;
		return (x << 42) | (y << 21) | z;
	}

	private static String uniqueName(BchMapModel model, String base) {
		String name = base;
		int k = 2;
		outer:
		while (true) {
			for (int i = 0; i < model.matCount; i++) {
				if (name.equals(model.getMaterialName(i))) {
					name = base + "_m" + (k++);
					continue outer;
				}
			}
			return name;
		}
	}

	private static int findMeshByMaterial(BchMapModel model, String material) {
		if (material == null || material.isEmpty()) {
			return -1;
		}
		String want = MapModelObj.sanitize(material.trim());
		for (int m = 0; m < model.meshes.size(); m++) {
			String name = model.getMaterialName(model.getMeshMaterialIndex(m));
			if (name != null && MapModelObj.sanitize(name).equalsIgnoreCase(want)) {
				return m;
			}
		}
		return -1;
	}

	private static int findMeshByExactMaterial(BchMapModel model, String material) {
		for (int m = 0; m < model.meshes.size(); m++) {
			if (material.equals(model.getMaterialName(model.getMeshMaterialIndex(m)))) {
				return m;
			}
		}
		return -1;
	}

	private static void putFloatLE(byte[] b, int o, float f) {
		int v = Float.floatToIntBits(f);
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
		b[o + 2] = (byte) (v >> 16);
		b[o + 3] = (byte) (v >> 24);
	}
}
