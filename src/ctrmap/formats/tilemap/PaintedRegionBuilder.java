package ctrmap.formats.tilemap;

import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.MapModelObj;
import ctrmap.formats.h3d.MapModelObjImporter;
import ctrmap.formats.h3d.RegionFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a full map region (visual model + collision + tilemap) from a painted
 * terrain grid, on top of a "tileset" donor region whose materials/textures it
 * reuses. This is the geometry engine behind the tile painter: for every
 * material the grid uses, the donor mesh with that material is regenerated as a
 * grid of textured quads over exactly the tiles of that terrain (via the
 * validated {@link BchMapModel#setMeshGeometry} + attribute-encoding path);
 * every other donor mesh collapses to a degenerate triangle. Collision is a
 * flat floor quad per walkable tile ({@link GfColl}); the tilemap carries each
 * terrain's measured tuple (walkability / wild encounters / surf).
 *
 * <p>Frame: region = 40x40 tiles, 18 world units per tile, center origin (tile
 * (0,0) at world -360,-360), floor at Y=0 (flat; elevation is a later layer).
 */
public class PaintedRegionBuilder {

	public static final int DIM = 40;
	public static final float TILE = 18f;
	public static final float ORIGIN = -360f;

	/** Builds the region content from a {@code DIM x DIM} terrain grid. */
	public static RegionFactory.BlankContent build(byte[] donorModel, TilePalette[][] grid) {
		RegionFactory.BlankContent out = new RegionFactory.BlankContent();
		out.model = buildModel(donorModel, grid);
		out.collision = buildCollision(grid);
		out.tilemap = buildTilemap(grid);
		out.props = new byte[]{0, 0, 0, 0};
		return out;
	}

	// ---- visual model -----------------------------------------------------

	static byte[] buildModel(byte[] donorModel, TilePalette[][] grid) {
		BchMapModel probe = new BchMapModel(donorModel);
		int meshCount = probe.meshCount;

		// resolve each terrain's material mesh once, then group tiles by mesh
		Map<TilePalette, Integer> terrainMesh = new HashMap<>();
		int groundMesh = defaultGroundMesh(probe);
		Map<Integer, List<int[]>> tilesByMesh = new HashMap<>();
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				TilePalette t = grid[ty][tx];
				if (t == null || t == TilePalette.VOID) {
					continue;
				}
				if (t == TilePalette.ROCK) {
					// rock is a visible blocked obstacle - it still needs a painted quad
				}
				int mi = terrainMesh.computeIfAbsent(t, tp -> resolveMesh(probe, tp, groundMesh));
				if (mi >= 0) {
					tilesByMesh.computeIfAbsent(mi, k -> new ArrayList<>()).add(new int[]{tx, ty});
				}
			}
		}

		byte[] current = donorModel;
		for (int mi = 0; mi < meshCount; mi++) {
			BchMapModel m = new BchMapModel(current);
			BchMapModel.MeshGeom g = m.geometry().get(mi);
			if (!g.posOk) {
				continue; // exotic mesh - leave (small decorations); it will read as-is
			}
			List<int[]> tiles = tilesByMesh.get(mi);
			if (tiles == null || tiles.isEmpty()) {
				// unused: degenerate so no stray donor geometry shows
				byte[] vtx = new byte[g.stride];
				System.arraycopy(m.raw, g.vtxAbs, vtx, 0, g.stride);
				current = m.setMeshGeometry(mi, vtx, new int[]{0, 0, 0});
				continue;
			}
			MapModelObj.ObjMesh om = buildQuadMesh(m, g, tiles);
			byte[] vtx = MapModelObjImporter.buildVertexBytes(m, g, om);
			current = m.setMeshGeometry(mi, vtx, om.triangles);
		}
		return current;
	}

	/** A quad grid over {@code tiles} with tiled UVs matching the donor mesh's texture density. */
	static MapModelObj.ObjMesh buildQuadMesh(BchMapModel model, BchMapModel.MeshGeom g, List<int[]> tiles) {
		float[] uvScale = measureUvScale(model, g);
		MapModelObj.ObjMesh om = new MapModelObj.ObjMesh();
		om.meshIndex = g.meshIndex;
		int n = tiles.size();
		om.positions = new float[n * 4][];
		om.uvs = new float[n * 4][];
		om.normals = new float[n * 4][];
		List<Integer> tris = new ArrayList<>(n * 6);
		for (int i = 0; i < n; i++) {
			int tx = tiles.get(i)[0], ty = tiles.get(i)[1];
			float x0 = tx * TILE + ORIGIN, x1 = x0 + TILE;
			float z0 = ty * TILE + ORIGIN, z1 = z0 + TILE;
			int b = i * 4;
			set(om, b, x0, z0, uvScale);
			set(om, b + 1, x1, z0, uvScale);
			set(om, b + 2, x0, z1, uvScale);
			set(om, b + 3, x1, z1, uvScale);
			// winding matched to RegionFactory's validated plane
			tris.add(b);
			tris.add(b + 2);
			tris.add(b + 1);
			tris.add(b + 1);
			tris.add(b + 2);
			tris.add(b + 3);
		}
		om.triangles = new int[tris.size()];
		for (int i = 0; i < tris.size(); i++) {
			om.triangles[i] = tris.get(i);
		}
		return om;
	}

	private static void set(MapModelObj.ObjMesh om, int i, float x, float z, float[] uvScale) {
		om.positions[i] = new float[]{x, 0f, z};
		om.uvs[i] = new float[]{x * uvScale[0], z * uvScale[1]};
		om.normals[i] = new float[]{0f, 1f, 0f};
	}

	/** UV units per world unit for a donor mesh (preserves its texture density). */
	static float[] measureUvScale(BchMapModel model, BchMapModel.MeshGeom g) {
		BchMapModel.MeshAttr uv = model.findAttr(g.meshIndex, 4);
		float def = 1f / 36f;
		if (uv == null || uv.type != 3 || g.vertexCount < 3) {
			return new float[]{def, def};
		}
		float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE, minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
		float[][] pos = model.getVertexPositions(g.meshIndex);
		for (int v = 0; v < g.vertexCount; v++) {
			int at = g.vtxAbs + v * g.stride + uv.offset;
			float u = f32(model.raw, at), vv = f32(model.raw, at + 4);
			minX = Math.min(minX, pos[v][0]);
			maxX = Math.max(maxX, pos[v][0]);
			minZ = Math.min(minZ, pos[v][2]);
			maxZ = Math.max(maxZ, pos[v][2]);
			minU = Math.min(minU, u);
			maxU = Math.max(maxU, u);
			minV = Math.min(minV, vv);
			maxV = Math.max(maxV, vv);
		}
		float sx = maxX - minX > 1f ? Math.abs(maxU - minU) / (maxX - minX) : def;
		float sz = maxZ - minZ > 1f ? Math.abs(maxV - minV) / (maxZ - minZ) : def;
		// clamp to a sane tiling range so a degenerate donor UV doesn't blow up
		return new float[]{clampScale(sx, def), clampScale(sz, def)};
	}

	private static float clampScale(float s, float def) {
		if (!(s > 0) || Float.isNaN(s) || s > 1f) {
			return def;
		}
		return Math.max(s, 1f / 720f);
	}

	private static int resolveMesh(BchMapModel model, TilePalette t, int fallback) {
		for (String hint : t.matHints) {
			for (BchMapModel.MeshGeom g : model.geometry()) {
				if (!g.posOk) {
					continue;
				}
				String name = model.getMaterialName(model.getMeshMaterialIndex(g.meshIndex));
				if (name != null && name.toLowerCase().contains(hint)) {
					return g.meshIndex;
				}
			}
		}
		return fallback;
	}

	/** The biggest editable mesh - the ground - used as the fallback material. */
	private static int defaultGroundMesh(BchMapModel model) {
		int best = -1, bestTris = -1;
		for (BchMapModel.MeshGeom g : model.geometry()) {
			if (g.posOk) {
				int tris = model.getTriangles(g.meshIndex).length;
				if (tris > bestTris) {
					bestTris = tris;
					best = g.meshIndex;
				}
			}
		}
		return best;
	}

	// ---- collision --------------------------------------------------------

	static byte[] buildCollision(TilePalette[][] grid) {
		List<float[]> tris = new ArrayList<>();
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				TilePalette t = grid[ty][tx];
				if (t == null || !t.floor) {
					continue;
				}
				float x0 = tx * TILE + ORIGIN, x1 = x0 + TILE;
				float z0 = ty * TILE + ORIGIN, z1 = z0 + TILE;
				tris.add(new float[]{x0, 0, z0, x0, 0, z1, x1, 0, z0});
				tris.add(new float[]{x1, 0, z0, x0, 0, z1, x1, 0, z1});
			}
		}
		if (tris.isEmpty()) {
			// a region with no floor still needs a valid coll subfile
			tris.add(new float[]{ORIGIN, 0, ORIGIN, ORIGIN, 0, ORIGIN + TILE, ORIGIN + TILE, 0, ORIGIN});
		}
		return GfColl.build(tris, null);
	}

	// ---- tilemap ----------------------------------------------------------

	static byte[] buildTilemap(TilePalette[][] grid) {
		byte[] out = new byte[6528];
		out[0] = (byte) DIM;
		out[2] = (byte) DIM;
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				TilePalette t = grid[ty][tx];
				int[] tuple = (t == null ? TilePalette.VOID : t).tuple;
				int off = 4 + (ty * DIM + tx) * 4;
				out[off] = (byte) tuple[0];
				out[off + 1] = (byte) tuple[1];
				out[off + 2] = (byte) tuple[2];
				out[off + 3] = (byte) tuple[3];
			}
		}
		return out;
	}

	private static float f32(byte[] b, int o) {
		return Float.intBitsToFloat((b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24));
	}
}
