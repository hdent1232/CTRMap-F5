package ctrmap.formats.h3d;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wavefront OBJ bridge for ORAS map models (the Blender workflow): exports a
 * {@link BchMapModel}'s meshes as one OBJ with a group + material per mesh, and
 * parses such an OBJ back for validation/import. Positions only in v1 - normals
 * and UVs are added once the game-wide attribute census pins their encode rules
 * (they are not needed for geometry-shape edits; the game re-lights from the
 * material config).
 *
 * <p>Group names encode the mesh identity ({@code mesh<N>_<materialName>}) so an
 * edited OBJ can be matched back to the mesh it came from regardless of
 * reordering by the 3D tool.
 */
public class MapModelObj {

	/** One exported/parsed mesh: identity + geometry. */
	public static class ObjMesh {

		public int meshIndex = -1;
		public String material = "";
		public float[][] positions;   // [n][3]
		public int[] triangles;       // vertex indices, LOCAL to this mesh, 3 per face

		public String groupName() {
			return "mesh" + meshIndex + "_" + sanitize(material);
		}
	}

	/**
	 * Exports every decodable mesh of the model. Meshes whose position attribute
	 * does not decode ({@code posOk == false}) are skipped and reported in the
	 * returned list (empty == everything exported).
	 */
	public static List<Integer> export(BchMapModel model, Writer w) throws IOException {
		List<Integer> skipped = new ArrayList<>();
		w.write("# CTRMap-F5 map model export\n");
		int base = 1; // OBJ indices are 1-based and global across groups
		for (BchMapModel.MeshGeom g : model.geometry()) {
			if (!g.posOk) {
				skipped.add(g.meshIndex);
				continue;
			}
			float[][] pos = model.getVertexPositions(g.meshIndex);
			int[] tris = model.getTriangles(g.meshIndex);
			if (pos == null || tris == null) {
				skipped.add(g.meshIndex);
				continue;
			}
			String mat = model.getMaterialName(model.getMeshMaterialIndex(g.meshIndex));
			w.write("o mesh" + g.meshIndex + "_" + sanitize(mat) + "\n");
			w.write("usemtl " + sanitize(mat) + "\n");
			for (float[] p : pos) {
				w.write(String.format(Locale.ROOT, "v %.6f %.6f %.6f%n", p[0], p[1], p[2]));
			}
			for (int t = 0; t + 2 < tris.length; t += 3) {
				w.write("f " + (base + tris[t]) + " " + (base + tris[t + 1]) + " " + (base + tris[t + 2]) + "\n");
			}
			base += pos.length;
		}
		return skipped;
	}

	/**
	 * Parses an OBJ (as written by {@link #export} or edited/re-exported by a 3D
	 * tool). Faces are re-localized to their group's vertex range; groups whose
	 * name does not carry a {@code mesh<N>_} prefix get meshIndex -1 (new
	 * geometry). Only v/f/o/g/usemtl lines are honored; polygons with more than
	 * 3 corners are fan-triangulated.
	 */
	public static List<ObjMesh> parse(java.io.BufferedReader r) throws IOException {
		List<float[]> allVerts = new ArrayList<>();
		Map<String, List<int[]>> facesByGroup = new LinkedHashMap<>();
		Map<String, String> matByGroup = new LinkedHashMap<>();
		String group = "default";
		String line;
		while ((line = r.readLine()) != null) {
			line = line.trim();
			if (line.startsWith("o ") || line.startsWith("g ")) {
				group = line.substring(2).trim();
			} else if (line.startsWith("usemtl ")) {
				matByGroup.put(group, line.substring(7).trim());
			} else if (line.startsWith("v ")) {
				String[] p = line.split("\\s+");
				allVerts.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
			} else if (line.startsWith("f ")) {
				String[] p = line.split("\\s+");
				int[] idx = new int[p.length - 1];
				for (int i = 1; i < p.length; i++) {
					String tok = p[i];
					int slash = tok.indexOf('/');
					if (slash >= 0) {
						tok = tok.substring(0, slash); // v/vt/vn - keep the position index
					}
					int v = Integer.parseInt(tok);
					idx[i - 1] = v > 0 ? v - 1 : allVerts.size() + v; // negative = relative
				}
				List<int[]> faces = facesByGroup.computeIfAbsent(group, k -> new ArrayList<>());
				for (int t = 2; t < idx.length; t++) { // fan-triangulate
					faces.add(new int[]{idx[0], idx[t - 1], idx[t]});
				}
			}
		}
		// re-localize each group's faces to a compact vertex array
		List<ObjMesh> out = new ArrayList<>();
		for (Map.Entry<String, List<int[]>> e : facesByGroup.entrySet()) {
			ObjMesh m = new ObjMesh();
			m.meshIndex = parseMeshIndex(e.getKey());
			m.material = matByGroup.getOrDefault(e.getKey(), "");
			Map<Integer, Integer> remap = new LinkedHashMap<>();
			List<Integer> tris = new ArrayList<>();
			for (int[] f : e.getValue()) {
				for (int v : f) {
					tris.add(remap.computeIfAbsent(v, k -> remap.size()));
				}
			}
			m.positions = new float[remap.size()][];
			for (Map.Entry<Integer, Integer> rm : remap.entrySet()) {
				m.positions[rm.getValue()] = allVerts.get(rm.getKey());
			}
			m.triangles = new int[tris.size()];
			for (int i = 0; i < tris.size(); i++) {
				m.triangles[i] = tris.get(i);
			}
			out.add(m);
		}
		return out;
	}

	/** meshIndex from a {@code mesh<N>_...} group name, or -1 (new geometry). */
	public static int parseMeshIndex(String groupName) {
		if (groupName == null || !groupName.startsWith("mesh")) {
			return -1;
		}
		int us = groupName.indexOf('_');
		try {
			return Integer.parseInt(groupName.substring(4, us < 0 ? groupName.length() : us));
		} catch (RuntimeException ex) {
			return -1;
		}
	}

	private static String sanitize(String s) {
		if (s == null || s.isEmpty()) {
			return "mat";
		}
		return s.replaceAll("[^A-Za-z0-9_.-]", "_");
	}
}
