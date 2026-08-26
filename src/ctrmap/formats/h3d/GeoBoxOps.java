package ctrmap.formats.h3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Box-selection geometry operations on a map model - the engine of the in-app
 * map editor. A selection is an XZ world-space rectangle (the footprint of a
 * tile rectangle; 1 tile = 18 world units, a region = 40x40 tiles = 720x720
 * units starting at world 0,0 for a single region). Operations act on every
 * mesh at once, so a "piece" (a building, a patch of terrain) moves as a whole
 * even though its triangles are spread across per-material meshes:
 *
 * <ul>
 * <li>{@link #move}: translate every vertex inside the box (offset-preserving
 *     write - triangles that straddle the box edge stretch, which is the right
 *     behavior for pulling terrain);</li>
 * <li>{@link #duplicate}: clone every face fully inside the box, offset by a
 *     delta (grow-mesh write) - the "reuse this building/water tile" op;</li>
 * <li>{@link #delete}: remove every face fully inside the box (re-topology
 *     write).</li>
 * </ul>
 *
 * All three build exclusively on the corpus-validated BchMapModel primitives.
 * Every method returns new model bytes and never mutates the input.
 */
public class GeoBoxOps {

	/** World-space XZ selection box, with optional Y bounds (default: all). */
	public static class Box {

		public float minX, maxX, minZ, maxZ;
		public float minY = -Float.MAX_VALUE, maxY = Float.MAX_VALUE;

		public Box(float minX, float minZ, float maxX, float maxZ) {
			this.minX = Math.min(minX, maxX);
			this.maxX = Math.max(minX, maxX);
			this.minZ = Math.min(minZ, maxZ);
			this.maxZ = Math.max(minZ, maxZ);
		}

		public boolean contains(float[] p) {
			return p[0] >= minX && p[0] <= maxX && p[2] >= minZ && p[2] <= maxZ
					&& p[1] >= minY && p[1] <= maxY;
		}

		/**
		 * The box of a tile rectangle (inclusive REGION-LOCAL tile coords,
		 * 18 units/tile). Region models AND collision are center-origin: tile
		 * (0,0) starts at world (-360,-360) - measured on real regions (visual
		 * ~[-360..360] with skirt overhang, collision strictly [-360..360]).
		 */
		public static Box ofTiles(int tx0, int ty0, int tx1, int ty1) {
			int ax = Math.min(tx0, tx1), bx = Math.max(tx0, tx1);
			int ay = Math.min(ty0, ty1), by = Math.max(ty0, ty1);
			return new Box(ax * 18f - 360f, ay * 18f - 360f, (bx + 1) * 18f - 360f, (by + 1) * 18f - 360f);
		}
	}

	/** What a selection covers, for UI display and op planning. */
	public static class Selection {

		public int vertices;
		public int fullFaces;      // faces with all 3 corners inside
		public int touchedMeshes;
	}

	/** Counts what the box covers (cheap - no writes). */
	public static Selection query(BchMapModel model, Box box) {
		Selection s = new Selection();
		for (BchMapModel.MeshGeom g : model.geometry()) {
			if (!g.posOk) {
				continue;
			}
			float[][] pos = model.getVertexPositions(g.meshIndex);
			int[] tris = model.getTriangles(g.meshIndex);
			boolean touched = false;
			boolean[] in = new boolean[pos.length];
			for (int v = 0; v < pos.length; v++) {
				if (box.contains(pos[v])) {
					in[v] = true;
					s.vertices++;
					touched = true;
				}
			}
			for (int t = 0; t + 2 < tris.length; t += 3) {
				if (in[tris[t]] && in[tris[t + 1]] && in[tris[t + 2]]) {
					s.fullFaces++;
				}
			}
			if (touched) {
				s.touchedMeshes++;
			}
		}
		return s;
	}

	/** Translates every vertex inside the box by (dx,dy,dz). Offset-preserving. */
	public static byte[] move(byte[] modelBytes, Box box, float dx, float dy, float dz) {
		byte[] current = modelBytes;
		BchMapModel model = new BchMapModel(current);
		for (BchMapModel.MeshGeom g : model.geometry()) {
			if (!g.posOk) {
				continue;
			}
			float[][] pos = model.getVertexPositions(g.meshIndex);
			boolean touched = false;
			for (float[] p : pos) {
				if (box.contains(p)) {
					p[0] += dx;
					p[1] += dy;
					p[2] += dz;
					touched = true;
				}
			}
			if (touched) {
				current = model.setVertexPositions(g.meshIndex, pos);
				model = new BchMapModel(current);
			}
		}
		return current;
	}

	/**
	 * Clones every face fully inside the box, offset by (dx,dy,dz) - per mesh,
	 * via appendGeometry (attributes ride along with the cloned vertices, so
	 * textures stay correct). Returns the input unchanged if nothing is inside.
	 */
	public static byte[] duplicate(byte[] modelBytes, Box box, float dx, float dy, float dz) {
		byte[] current = modelBytes;
		BchMapModel model = new BchMapModel(current);
		int meshCount = model.meshes.size();
		for (int mi = 0; mi < meshCount; mi++) {
			BchMapModel.MeshGeom g = model.geometry().get(mi);
			if (!g.posOk) {
				continue;
			}
			float[][] pos = model.getVertexPositions(mi);
			int[] tris = model.getTriangles(mi);
			boolean[] in = new boolean[pos.length];
			for (int v = 0; v < pos.length; v++) {
				in[v] = box.contains(pos[v]);
			}
			// faces fully inside -> the set of source vertices to clone
			Map<Integer, Integer> cloneIndex = new LinkedHashMap<>();
			List<Integer> newTris = new ArrayList<>();
			for (int t = 0; t + 2 < tris.length; t += 3) {
				if (in[tris[t]] && in[tris[t + 1]] && in[tris[t + 2]]) {
					for (int c = 0; c < 3; c++) {
						int src = tris[t + c];
						Integer ni = cloneIndex.get(src);
						if (ni == null) {
							ni = cloneIndex.size();
							cloneIndex.put(src, ni);
						}
						newTris.add(ni);
					}
				}
			}
			if (cloneIndex.isEmpty()) {
				continue;
			}
			byte[] extraV = new byte[cloneIndex.size() * g.stride];
			int base = g.vertexCount;
			for (Map.Entry<Integer, Integer> e : cloneIndex.entrySet()) {
				int src = e.getKey(), dst = e.getValue();
				System.arraycopy(model.raw, g.vtxAbs + src * g.stride, extraV, dst * g.stride, g.stride);
				putF(extraV, dst * g.stride + g.posOffset, pos[src][0] + dx);
				putF(extraV, dst * g.stride + g.posOffset + 4, pos[src][1] + dy);
				putF(extraV, dst * g.stride + g.posOffset + 8, pos[src][2] + dz);
			}
			int[] extraI = new int[newTris.size()];
			for (int i = 0; i < extraI.length; i++) {
				extraI[i] = base + newTris.get(i);
			}
			current = model.appendGeometry(mi, extraV, extraI);
			model = new BchMapModel(current);
		}
		return current;
	}

	/**
	 * Removes every face fully inside the box. A mesh that would end up with no
	 * faces keeps one degenerate (zero-area) face so its buffers stay non-empty
	 * and the model structurally valid.
	 */
	public static byte[] delete(byte[] modelBytes, Box box) {
		byte[] current = modelBytes;
		BchMapModel model = new BchMapModel(current);
		int meshCount = model.meshes.size();
		for (int mi = 0; mi < meshCount; mi++) {
			BchMapModel.MeshGeom g = model.geometry().get(mi);
			if (!g.posOk) {
				continue;
			}
			float[][] pos = model.getVertexPositions(mi);
			int[] tris = model.getTriangles(mi);
			boolean[] in = new boolean[pos.length];
			for (int v = 0; v < pos.length; v++) {
				in[v] = box.contains(pos[v]);
			}
			List<Integer> kept = new ArrayList<>();
			boolean removedAny = false;
			for (int t = 0; t + 2 < tris.length; t += 3) {
				if (in[tris[t]] && in[tris[t + 1]] && in[tris[t + 2]]) {
					removedAny = true;
				} else {
					kept.add(tris[t]);
					kept.add(tris[t + 1]);
					kept.add(tris[t + 2]);
				}
			}
			if (!removedAny) {
				continue;
			}
			if (kept.isEmpty()) {
				kept.add(0);
				kept.add(0);
				kept.add(0); // degenerate keep-alive face
			}
			// compact the vertex buffer to the vertices the kept faces use
			Map<Integer, Integer> remap = new LinkedHashMap<>();
			int[] newTris = new int[kept.size()];
			for (int i = 0; i < kept.size(); i++) {
				Integer ni = remap.get(kept.get(i));
				if (ni == null) {
					ni = remap.size();
					remap.put(kept.get(i), ni);
				}
				newTris[i] = ni;
			}
			byte[] newV = new byte[remap.size() * g.stride];
			for (Map.Entry<Integer, Integer> e : remap.entrySet()) {
				System.arraycopy(model.raw, g.vtxAbs + e.getKey() * g.stride, newV, e.getValue() * g.stride, g.stride);
			}
			current = model.setMeshGeometry(mi, newV, newTris);
			model = new BchMapModel(current);
		}
		return current;
	}

	private static void putF(byte[] b, int o, float f) {
		int v = Float.floatToIntBits(f);
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
		b[o + 2] = (byte) (v >> 16);
		b[o + 3] = (byte) (v >> 24);
	}
}
