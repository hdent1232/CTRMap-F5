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
 * {@link BchMapModel}'s meshes as one OBJ with a group + material per mesh -
 * including texture coordinates ({@code vt}) and normals ({@code vn}) where the
 * mesh buffers them - and parses such an OBJ back for import.
 *
 * <p>Vertex colors have no vanilla-OBJ representation; on import they are
 * inherited from the original surface (see MapModelObjImporter). Meshes whose
 * normal/color live as a fixed constant in the command stream simply have no
 * {@code vn} - the constant stays in the model and keeps applying.
 *
 * <p>Group names encode the mesh identity ({@code mesh<N>_<materialName>}) so an
 * edited OBJ can be matched back to the mesh it came from regardless of
 * reordering by the 3D tool. A face corner may reference any (v, vt, vn)
 * combination - the parser splits vertices per distinct triple, exactly like
 * game buffers expect.
 */
public class MapModelObj {

	/** One exported/parsed mesh: identity + geometry (+ optional UVs/normals). */
	public static class ObjMesh {

		public int meshIndex = -1;
		public String material = "";
		public float[][] positions;   // [n][3]
		public int[] triangles;       // vertex indices, LOCAL to this mesh, 3 per face
		/** Per-local-vertex texture coordinates, or null when the OBJ carried none. */
		public float[][] uvs;
		/** Per-local-vertex normals, or null when the OBJ carried none. */
		public float[][] normals;

		public String groupName() {
			return "mesh" + meshIndex + "_" + sanitize(material);
		}
	}

	/**
	 * Exports every decodable mesh of the model, with UVs and normals when the
	 * mesh buffers them. Meshes whose position attribute does not decode are
	 * skipped and reported in the returned list (empty == everything exported).
	 */
	public static List<Integer> export(BchMapModel model, Writer w) throws IOException {
		List<Integer> skipped = new ArrayList<>();
		w.write("# CTRMap-F5 map model export\n");
		int vBase = 1, vtBase = 1, vnBase = 1; // OBJ indices are 1-based and global
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
			BchMapModel.MeshAttr uv = model.findAttr(g.meshIndex, 4);   // TexCoord0
			BchMapModel.MeshAttr nrm = model.findAttr(g.meshIndex, 1);  // Normal
			boolean hasUv = uv != null && uv.type == 3 && uv.elems >= 2;
			boolean hasNrm = nrm != null && nrm.type == 3 && nrm.elems >= 3;

			String mat = model.getMaterialName(model.getMeshMaterialIndex(g.meshIndex));
			w.write("o mesh" + g.meshIndex + "_" + sanitize(mat) + "\n");
			w.write("usemtl " + sanitize(mat) + "\n");
			for (int v = 0; v < pos.length; v++) {
				w.write(String.format(Locale.ROOT, "v %.6f %.6f %.6f%n", pos[v][0], pos[v][1], pos[v][2]));
			}
			if (hasUv) {
				for (int v = 0; v < pos.length; v++) {
					int at = g.vtxAbs + v * g.stride + uv.offset;
					w.write(String.format(Locale.ROOT, "vt %.6f %.6f%n",
							f32(model.raw, at), f32(model.raw, at + 4)));
				}
			}
			if (hasNrm) {
				for (int v = 0; v < pos.length; v++) {
					int at = g.vtxAbs + v * g.stride + nrm.offset;
					w.write(String.format(Locale.ROOT, "vn %.6f %.6f %.6f%n",
							f32(model.raw, at), f32(model.raw, at + 4), f32(model.raw, at + 8)));
				}
			}
			for (int t = 0; t + 2 < tris.length; t += 3) {
				StringBuilder sb = new StringBuilder("f");
				for (int c = 0; c < 3; c++) {
					int v = tris[t + c];
					sb.append(' ').append(vBase + v);
					if (hasUv && hasNrm) {
						sb.append('/').append(vtBase + v).append('/').append(vnBase + v);
					} else if (hasUv) {
						sb.append('/').append(vtBase + v);
					} else if (hasNrm) {
						sb.append("//").append(vnBase + v);
					}
				}
				w.write(sb.append('\n').toString());
			}
			vBase += pos.length;
			if (hasUv) {
				vtBase += pos.length;
			}
			if (hasNrm) {
				vnBase += pos.length;
			}
		}
		return skipped;
	}

	/**
	 * Parses an OBJ (as written by {@link #export} or edited/re-exported by a 3D
	 * tool). Face corners are split per distinct (v, vt, vn) triple and
	 * re-localized to their group; groups whose name does not carry a
	 * {@code mesh<N>_} prefix get meshIndex -1 (new geometry). Polygons with
	 * more than 3 corners are fan-triangulated; negative indices are relative.
	 */
	public static List<ObjMesh> parse(java.io.BufferedReader r) throws IOException {
		List<float[]> vs = new ArrayList<>();
		List<float[]> vts = new ArrayList<>();
		List<float[]> vns = new ArrayList<>();
		Map<String, List<long[]>> facesByGroup = new LinkedHashMap<>(); // corner = packed (v,vt,vn)
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
				vs.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
			} else if (line.startsWith("vt ")) {
				String[] p = line.split("\\s+");
				vts.add(new float[]{Float.parseFloat(p[1]), p.length > 2 ? Float.parseFloat(p[2]) : 0f});
			} else if (line.startsWith("vn ")) {
				String[] p = line.split("\\s+");
				vns.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
			} else if (line.startsWith("f ")) {
				String[] p = line.split("\\s+");
				long[] corners = new long[p.length - 1];
				for (int i = 1; i < p.length; i++) {
					corners[i - 1] = parseCorner(p[i], vs.size(), vts.size(), vns.size());
				}
				List<long[]> faces = facesByGroup.computeIfAbsent(group, k -> new ArrayList<>());
				for (int t = 2; t < corners.length; t++) { // fan-triangulate
					faces.add(new long[]{corners[0], corners[t - 1], corners[t]});
				}
			}
		}
		//re-localize each group's corners to compact per-group vertex arrays
		List<ObjMesh> out = new ArrayList<>();
		for (Map.Entry<String, List<long[]>> e : facesByGroup.entrySet()) {
			ObjMesh m = new ObjMesh();
			m.meshIndex = parseMeshIndex(e.getKey());
			m.material = matByGroup.getOrDefault(e.getKey(), "");
			Map<Long, Integer> remap = new LinkedHashMap<>();
			List<Integer> tris = new ArrayList<>();
			boolean anyUv = false, anyNrm = false;
			for (long[] f : e.getValue()) {
				for (long corner : f) {
					tris.add(remap.computeIfAbsent(corner, k -> remap.size()));
					if (vtOf(corner) >= 0) {
						anyUv = true;
					}
					if (vnOf(corner) >= 0) {
						anyNrm = true;
					}
				}
			}
			m.positions = new float[remap.size()][];
			m.uvs = anyUv ? new float[remap.size()][] : null;
			m.normals = anyNrm ? new float[remap.size()][] : null;
			for (Map.Entry<Long, Integer> rm : remap.entrySet()) {
				long corner = rm.getKey();
				int li = rm.getValue();
				m.positions[li] = vs.get(vOf(corner));
				if (anyUv) {
					int vt = vtOf(corner);
					m.uvs[li] = vt >= 0 ? vts.get(vt) : null; //null -> importer inherits
				}
				if (anyNrm) {
					int vn = vnOf(corner);
					m.normals[li] = vn >= 0 ? vns.get(vn) : null;
				}
			}
			m.triangles = new int[tris.size()];
			for (int i = 0; i < tris.size(); i++) {
				m.triangles[i] = tris.get(i);
			}
			out.add(m);
		}
		return out;
	}

	//corner packing: 21 bits each for v / vt+1 / vn+1 (index -1 = absent)
	private static long parseCorner(String tok, int nv, int nvt, int nvn) {
		String[] parts = tok.split("/", -1);
		int v = resolve(parts[0], nv);
		int vt = parts.length > 1 && !parts[1].isEmpty() ? resolve(parts[1], nvt) : -1;
		int vn = parts.length > 2 && !parts[2].isEmpty() ? resolve(parts[2], nvn) : -1;
		return ((long) v << 42) | ((long) (vt + 1) << 21) | (vn + 1);
	}

	private static int resolve(String s, int count) {
		int i = Integer.parseInt(s);
		return i > 0 ? i - 1 : count + i;
	}

	private static int vOf(long c) {
		return (int) (c >>> 42);
	}

	private static int vtOf(long c) {
		return (int) ((c >>> 21) & 0x1FFFFF) - 1;
	}

	private static int vnOf(long c) {
		return (int) (c & 0x1FFFFF) - 1;
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

	public static String sanitize(String s) {
		if (s == null || s.isEmpty()) {
			return "mat";
		}
		return s.replaceAll("[^A-Za-z0-9_.-]", "_");
	}

	private static float f32(byte[] b, int o) {
		return Float.intBitsToFloat((b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24));
	}
}
