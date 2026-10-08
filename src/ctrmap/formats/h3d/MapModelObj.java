package ctrmap.formats.h3d;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static ctrmap.util.LittleEndian.f32;

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
		return parse(r, "the OBJ");
	}

	/**
	 * {@link #parse(java.io.BufferedReader)}, naming {@code name} when it refuses. STRICT, AND THE ONE
	 * OBJ READER (F070): a vertex short of three coordinates, a number that is not one, a face of fewer
	 * than three corners and a corner naming a vertex that does not exist are each refused naming the
	 * line. There were two readers - this one, and one for Map > OBJ to collisions that F044 had
	 * already made strict, while this one threw an ArrayIndexOutOfBoundsException or a bare
	 * NumberFormatException at the same input.
	 */
	public static List<ObjMesh> parse(java.io.BufferedReader r, String name) throws IOException {
		List<float[]> vs = new ArrayList<>();
		List<float[]> vts = new ArrayList<>();
		List<float[]> vns = new ArrayList<>();
		Map<String, List<long[]>> facesByGroup = new LinkedHashMap<>(); // corner = packed (v,vt,vn)
		Map<String, String> matByGroup = new LinkedHashMap<>();
		String group = "default";   //faces with no object or group line before them belong to one of their own
		String line;
		int lineNo = 0;
		while ((line = r.readLine()) != null) {
			lineNo++;
			line = line.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			String[] p = line.split("\\s+");
			switch (p[0]) {
				case "o":
				case "g":
					if (p.length > 1) {
						group = line.substring(2).trim();
					}
					break;
				case "usemtl":
					if (p.length > 1) {
						matByGroup.put(group, line.substring(7).trim());
					}
					break;
				case "v":
					if (p.length < 4) {
						throw bad(name, lineNo, "a vertex needs x, y and z");
					}
					vs.add(new float[]{number(p[1], name, lineNo), number(p[2], name, lineNo), number(p[3], name, lineNo)});
					break;
				case "vt":
					if (p.length < 2) {
						throw bad(name, lineNo, "a texture coordinate needs at least u");
					}
					vts.add(new float[]{number(p[1], name, lineNo), p.length > 2 ? number(p[2], name, lineNo) : 0f});
					break;
				case "vn":
					if (p.length < 4) {
						throw bad(name, lineNo, "a normal needs x, y and z");
					}
					vns.add(new float[]{number(p[1], name, lineNo), number(p[2], name, lineNo), number(p[3], name, lineNo)});
					break;
				case "f":
					if (p.length < 4) {
						throw bad(name, lineNo, "a face needs at least three corners");
					}
					long[] corners = new long[p.length - 1];
					for (int i = 1; i < p.length; i++) {
						corners[i - 1] = parseCorner(p[i], vs.size(), vts.size(), vns.size(), name, lineNo);
					}
					List<long[]> faces = facesByGroup.computeIfAbsent(group, k -> new ArrayList<>());
					for (int t = 2; t < corners.length; t++) { // fan-triangulate, in the face's own winding
						faces.add(new long[]{corners[0], corners[t - 1], corners[t]});
					}
					break;
				default:
					break;
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
	private static long parseCorner(String tok, int nv, int nvt, int nvn, String name, int lineNo) throws IOException {
		String[] parts = tok.split("/", -1);
		int v = resolve(parts[0], nv, "vertex", name, lineNo);
		int vt = parts.length > 1 && !parts[1].isEmpty() ? resolve(parts[1], nvt, "texture coordinate", name, lineNo) : -1;
		int vn = parts.length > 2 && !parts[2].isEmpty() ? resolve(parts[2], nvn, "normal", name, lineNo) : -1;
		return ((long) v << 42) | ((long) (vt + 1) << 21) | (vn + 1);
	}

	/** A corner's index: 1-based, or negative counting back from the last one read; one that names nothing is refused. */
	private static int resolve(String s, int count, String what, String name, int lineNo) throws IOException {
		int i;
		try {
			i = Integer.parseInt(s);
		} catch (NumberFormatException notAnIndex) {
			throw bad(name, lineNo, "'" + s + "' is not a " + what + " index");
		}
		int at = i > 0 ? i - 1 : count + i;
		if (i == 0 || at < 0 || at >= count) {
			throw bad(name, lineNo, what + " " + i + " does not exist (" + count + " read so far)");
		}
		return at;
	}

	private static float number(String token, String name, int lineNo) throws IOException {
		try {
			return Float.parseFloat(token);
		} catch (NumberFormatException notANumber) {
			throw bad(name, lineNo, "'" + token + "' is not a number");
		}
	}

	private static IOException bad(String name, int lineNo, String what) {
		return new IOException(name + ", line " + lineNo + ": " + what + ".");
	}

	/**
	 * The collision meshes an OBJ's faces make - Map > OBJ to collisions. Every face with area on the
	 * ground plane, one triangle per fan corner; a wall has none there and is left out. Each group goes
	 * to the next of the sixteen collision slots, round again after the sixteenth. EMPTY when no face is
	 * left: sixteen empty meshes would replace a zone's whole collision with nothing (F044).
	 */
	public static ctrmap.formats.gfcollision.GRCollisionMesh[] collision(List<ObjMesh> meshes) {
		List<List<ctrmap.formats.Triangle>> groups = new ArrayList<>();
		int total = 0;
		for (ObjMesh m : meshes) {
			List<ctrmap.formats.Triangle> tris = new ArrayList<>();
			for (int t = 0; t + 2 < m.triangles.length; t += 3) {
				float[] a = m.positions[m.triangles[t]], b = m.positions[m.triangles[t + 1]], c = m.positions[m.triangles[t + 2]];
				if (!flat(a, b, c)) {
					tris.add(new ctrmap.formats.Triangle(new float[]{a[0], b[0], c[0]}, new float[]{a[1], b[1], c[1]},
							new float[]{a[2], b[2], c[2]}));
				}
			}
			groups.add(tris);
			total += tris.size();
		}
		if (total == 0) {
			return new ctrmap.formats.gfcollision.GRCollisionMesh[0];
		}
		ctrmap.formats.gfcollision.GRCollisionMesh[] out = new ctrmap.formats.gfcollision.GRCollisionMesh[16];
		for (int i = 0; i < 16; i++) {
			out[i] = new ctrmap.formats.gfcollision.GRCollisionMesh();
		}
		for (int i = 0; i < groups.size(); i++) {
			out[i % 16].tris.addAll(groups.get(i));
		}
		return out;
	}

	/** Twice the area of the triangle's shadow on the ground plane is zero: a wall. */
	private static boolean flat(float[] v0, float[] v1, float[] v2) {
		return ctrmap.util.Bytes.impreciseFloatEquals(
			v0[0] * (v1[2] - v2[2]) + v1[0] * (v2[2] - v0[2]) + v2[0] * (v0[2] - v1[2]), 0f);
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
}
