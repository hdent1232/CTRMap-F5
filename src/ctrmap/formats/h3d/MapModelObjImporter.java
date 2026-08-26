package ctrmap.formats.h3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a parsed OBJ (see {@link MapModelObj}) back onto a map model - the
 * import half of the Blender workflow. Each OBJ group that carries a mesh
 * identity ({@code mesh<N>_...}) REPLACES that mesh's geometry via
 * {@link BchMapModel#setMeshGeometry}; groups without an identity are appended
 * to the first mesh whose material name matches their {@code usemtl} (new
 * geometry reusing an existing material).
 *
 * <p>Vertex attributes beyond position (UVs, normals, colors) are inherited
 * from the ORIGINAL mesh by nearest-neighbor: a vertex that kept its position
 * keeps its exact attribute bytes; a moved/new vertex copies the nearest
 * original vertex's attributes with only the position patched. This keeps
 * texturing intact for shape edits without needing to re-author UVs.
 */
public class MapModelObjImporter {

	/** Per-group outcome for user-facing reporting. */
	public static class Outcome {

		public String group;
		public int meshIndex;
		public String action;   // "replaced", "appended", "skipped: <why>"
		public int vertices;
		public int faces;
	}

	/**
	 * Applies the parsed meshes onto the model, returning the new BCH bytes and
	 * filling {@code outcomes}. Never throws for a single bad group - it is
	 * reported and skipped; throws only if NOTHING could be applied.
	 */
	public static byte[] apply(byte[] modelBytes, List<MapModelObj.ObjMesh> objMeshes, List<Outcome> outcomes) {
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
					append = true;
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
				byte[] vtx = buildVertexBytes(model, g, om.positions);
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
	 * Encodes vertices in the mesh's exact attribute layout: nearest original
	 * vertex's bytes with the position floats patched in.
	 */
	static byte[] buildVertexBytes(BchMapModel model, BchMapModel.MeshGeom g, float[][] positions) {
		float[][] orig = model.getVertexPositions(g.meshIndex);
		// exact-match table (quantized) for O(1) hits on unmoved vertices
		Map<Long, Integer> exact = new HashMap<>();
		for (int v = 0; v < orig.length; v++) {
			exact.putIfAbsent(key(orig[v]), v);
		}
		byte[] out = new byte[positions.length * g.stride];
		for (int v = 0; v < positions.length; v++) {
			float[] p = positions[v];
			Integer src = exact.get(key(p));
			if (src == null) {
				src = nearest(orig, p);
			}
			System.arraycopy(model.raw, g.vtxAbs + src * g.stride, out, v * g.stride, g.stride);
			putFloatLE(out, v * g.stride + g.posOffset, p[0]);
			putFloatLE(out, v * g.stride + g.posOffset + 4, p[1]);
			putFloatLE(out, v * g.stride + g.posOffset + 8, p[2]);
		}
		return out;
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

	private static int findMeshByMaterial(BchMapModel model, String material) {
		if (material == null || material.isEmpty()) {
			return -1;
		}
		String want = material.trim();
		for (int m = 0; m < model.meshes.size(); m++) {
			String name = model.getMaterialName(model.getMeshMaterialIndex(m));
			if (name != null && sanitize(name).equalsIgnoreCase(sanitize(want))) {
				return m;
			}
		}
		return -1;
	}

	private static String sanitize(String s) {
		return s.replaceAll("[^A-Za-z0-9_.-]", "_");
	}

	private static void putFloatLE(byte[] b, int o, float f) {
		int v = Float.floatToIntBits(f);
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
		b[o + 2] = (byte) (v >> 16);
		b[o + 3] = (byte) (v >> 24);
	}
}
