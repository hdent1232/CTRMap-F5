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
 * terrain grid + per-tile ELEVATION, on top of a "tileset" donor region whose
 * materials/textures it reuses. The geometry engine behind the tile painter:
 * <ul>
 * <li>each terrain material's donor mesh is regenerated as textured floor quads
 *     over its tiles, at each tile's height;</li>
 * <li>where a tile is higher than a neighbour (or the map edge), a vertical
 *     CLIFF quad is emitted on the shared edge using a cliff material, so raised
 *     ground has walls;</li>
 * <li>collision = a floor quad per walkable tile at its height + the cliff walls
 *     (which block passage), via the retail-exact {@link GfColl};</li>
 * <li>the tilemap carries each terrain's measured tuple;</li>
 * <li>lighting is baked into vertex colors (tint x brightness x edge AO).</li>
 * </ul>
 * Frame: 40x40 tiles, 18 world units per tile, center origin (tile (0,0) at
 * world -360,-360); one height level = {@link #STEP} world units.
 */
public class PaintedRegionBuilder {

	public static final int DIM = 40;
	public static final float TILE = 18f;
	public static final float ORIGIN = -360f;
	/** World Y per height level (one tile tall). */
	public static final float STEP = 18f;

	/** A textured quad (4 corners TL/TR/BL/BR) destined for one mesh. */
	private static final class Quad {

		final float[][] pos = new float[4][];
		final float[][] uv = new float[4][];
		final float[][] nrm = new float[4][];
		final float[] ao = new float[4];
	}

	public static RegionFactory.BlankContent build(byte[] donorModel, TilePalette[][] grid) {
		return build(donorModel, grid, null, TerrainLighting.daytime());
	}

	public static RegionFactory.BlankContent build(byte[] donorModel, TilePalette[][] grid, TerrainLighting light) {
		return build(donorModel, grid, null, light);
	}

	public static RegionFactory.BlankContent build(byte[] donorModel, TilePalette[][] grid, int[][] height, TerrainLighting light) {
		return build(donorModel, grid, height, null, light);
	}

	/**
	 * @param height per-tile elevation in levels (null = all flat at 0).
	 * @param ramp per-tile "this is a walkable ramp" flags (null = none); a ramp
	 *             tile slopes from its level down to a lower orthogonal neighbour
	 *             (auto-oriented), replacing the cliff so the player walks it.
	 */
	public static RegionFactory.BlankContent build(byte[] donorModel, TilePalette[][] grid, int[][] height, boolean[][] ramp, TerrainLighting light) {
		return build(donorModel, grid, height, ramp, light, true);
	}

	/**
	 * @param edges when true (and the donor carries a grass-edge material), lay
	 *              GameFreak-style transition strips along grass&harr;dirt/sand seams
	 *              (the projected "blend" edge). Ignored if the tileset donor has
	 *              no edge material.
	 */
	public static RegionFactory.BlankContent build(byte[] donorModel, TilePalette[][] grid, int[][] height, boolean[][] ramp, TerrainLighting light, boolean edges) {
		if (height == null) {
			height = new int[DIM][DIM];
		}
		if (ramp == null) {
			ramp = new boolean[DIM][DIM];
		}
		RegionFactory.BlankContent out = new RegionFactory.BlankContent();
		out.model = buildModel(donorModel, grid, height, ramp, light, edges);
		out.collision = buildCollision(grid, height, ramp);
		out.tilemap = buildTilemap(grid);
		out.props = new byte[]{0, 0, 0, 0};
		return out;
	}

	/** True if the tileset donor carries a grass-edge material (so edge strips are available). */
	public static boolean donorSupportsEdges(byte[] donorModel) {
		return resolveEdgeMesh(new BchMapModel(donorModel)) >= 0;
	}

	/** Descent direction (0 E,1 W,2 S,3 N) of a ramp tile toward a level-below
	 *  neighbour, or -1 if not a ramp / no lower neighbour. */
	static int rampDir(TilePalette[][] grid, int[][] height, boolean[][] ramp, int tx, int ty) {
		if (!ramp[ty][tx]) {
			return -1;
		}
		int h = height[ty][tx];
		for (int d = 0; d < 4; d++) {
			if (neighbourHeight(grid, height, tx, ty, d) == h - 1) {
				return d;
			}
		}
		return -1;
	}

	// ---- visual model -----------------------------------------------------

	static byte[] buildModel(byte[] donorModel, TilePalette[][] grid, int[][] height, boolean[][] ramp, TerrainLighting light, boolean edges) {
		BchMapModel probe = new BchMapModel(donorModel);
		int meshCount = probe.meshCount;
		int groundMesh = defaultGroundMesh(probe);
		int cliffMesh = resolveCliffMesh(probe, groundMesh);
		int edgeMesh = edges ? resolveEdgeMesh(probe) : -1;

		Map<TilePalette, Integer> terrainMesh = new HashMap<>();
		Map<Integer, List<Quad>> quadsByMesh = new HashMap<>();
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				TilePalette t = grid[ty][tx];
				if (t == null || t == TilePalette.VOID) {
					continue;
				}
				int mi = terrainMesh.computeIfAbsent(t, tp -> resolveMesh(probe, tp, groundMesh));
				int h = height[ty][tx];
				int rd = rampDir(grid, height, ramp, tx, ty);
				if (mi >= 0) {
					quadsByMesh.computeIfAbsent(mi, k -> new ArrayList<>()).add(floorQuad(grid, height, tx, ty, h, rd));
				}
				// cliffs on edges where this tile is higher than the neighbour,
				// EXCEPT the ramp's descent edge (that side is now a walkable slope)
				for (int dir = 0; dir < 4; dir++) {
					if (dir == rd) {
						continue;
					}
					int hn = neighbourHeight(grid, height, tx, ty, dir);
					if (hn < h) {
						quadsByMesh.computeIfAbsent(cliffMesh, k -> new ArrayList<>()).add(cliffQuad(tx, ty, dir, hn, h));
					}
				}
			}
		}

		// GameFreak-style transition strips along grass<->dirt/sand seams
		if (edgeMesh >= 0) {
			addEdgeStrips(grid, height, quadsByMesh, edgeMesh);
		}

		byte[] current = donorModel;
		for (int mi = 0; mi < meshCount; mi++) {
			BchMapModel m = new BchMapModel(current);
			BchMapModel.MeshGeom g = m.geometry().get(mi);
			if (!g.posOk) {
				continue;
			}
			List<Quad> quads = quadsByMesh.get(mi);
			if (quads == null || quads.isEmpty()) {
				byte[] vtx = new byte[g.stride];
				System.arraycopy(m.raw, g.vtxAbs, vtx, 0, g.stride);
				current = m.setMeshGeometry(mi, vtx, new int[]{0, 0, 0});
				continue;
			}
			// edge strips author their UVs directly (U along seam, V across the
			// band); ground meshes get world-projected UVs scaled to the texture.
			MapModelObj.ObjMesh om = meshFromQuads(m, g, quads, mi == edgeMesh);
			byte[] vtx = MapModelObjImporter.buildVertexBytes(m, g, om);
			bakeQuadLighting(m, g, vtx, quads, light);
			current = m.setMeshGeometry(mi, vtx, om.triangles);
		}
		return current;
	}

	// ---- edge transition strips (the GameFreak "blend" look) --------------

	private static final int GRP_GRASS = 1, GRP_DIRT = 2;
	/** Half-visual constants: strip reaches EDGE_W world units onto the lower
	 *  (dirt/sand) side of a seam, lifted EDGE_LIFT above ground to overlay it. */
	static final float EDGE_W = 9f, EDGE_LIFT = 0.6f;

	/** Coarse terrain family for edge blending (0 = never edged). */
	private static int terrainGroup(TilePalette t) {
		if (t == null) {
			return 0;
		}
		switch (t) {
			case GRASS:
			case TALL_GRASS:
			case LEDGE_S:
			case LEDGE_E:
			case LEDGE_W:
				return GRP_GRASS;
			case PATH:
			case SAND:
			case DEEP_SAND:
				return GRP_DIRT;
			default:
				return 0;
		}
	}

	private static boolean isGrassDirt(int ga, int gb) {
		return (ga == GRP_GRASS && gb == GRP_DIRT) || (ga == GRP_DIRT && gb == GRP_GRASS);
	}

	/** Lays a grass-edge ribbon along every same-height grass&harr;dirt/sand seam. */
	static void addEdgeStrips(TilePalette[][] grid, int[][] height, Map<Integer, List<Quad>> quadsByMesh, int edgeMesh) {
		List<Quad> strips = new ArrayList<>();
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				int ga = terrainGroup(grid[ty][tx]);
				int ha = height[ty][tx];
				if (tx + 1 < DIM && ha == height[ty][tx + 1]) {
					int gb = terrainGroup(grid[ty][tx + 1]);
					if (isGrassDirt(ga, gb)) {
						strips.add(edgeQuadEW(tx, ty, ha, ga == GRP_GRASS));
					}
				}
				if (ty + 1 < DIM && ha == height[ty + 1][tx]) {
					int gb = terrainGroup(grid[ty + 1][tx]);
					if (isGrassDirt(ga, gb)) {
						strips.add(edgeQuadNS(tx, ty, ha, ga == GRP_GRASS));
					}
				}
			}
		}
		if (!strips.isEmpty()) {
			quadsByMesh.computeIfAbsent(edgeMesh, k -> new ArrayList<>()).addAll(strips);
		}
	}

	/** Edge ribbon on a vertical (east/west) seam; strip lies on the dirt side. */
	private static Quad edgeQuadEW(int tx, int ty, int h, boolean grassIsWest) {
		float xs = (tx + 1) * TILE + ORIGIN;
		float z0 = ty * TILE + ORIGIN, z1 = z0 + TILE;
		float y = h * STEP + EDGE_LIFT;
		float dirtSign = grassIsWest ? 1f : -1f;
		float xSeam = xs, xOut = xs + dirtSign * EDGE_W;
		Quad q = new Quad();
		q.pos[0] = new float[]{xSeam, y, z0}; q.uv[0] = new float[]{0f, 1f};
		q.pos[1] = new float[]{xOut, y, z0};  q.uv[1] = new float[]{0f, 0f};
		q.pos[2] = new float[]{xSeam, y, z1}; q.uv[2] = new float[]{0.5f, 1f};
		q.pos[3] = new float[]{xOut, y, z1};  q.uv[3] = new float[]{0.5f, 0f};
		for (int c = 0; c < 4; c++) {
			q.nrm[c] = new float[]{0f, 1f, 0f};
			q.ao[c] = 1f;
		}
		fixWindingUp(q);
		return q;
	}

	/** Edge ribbon on a horizontal (north/south) seam; strip lies on the dirt side. */
	private static Quad edgeQuadNS(int tx, int ty, int h, boolean grassIsNorth) {
		float zs = (ty + 1) * TILE + ORIGIN;
		float x0 = tx * TILE + ORIGIN, x1 = x0 + TILE;
		float y = h * STEP + EDGE_LIFT;
		float dirtSign = grassIsNorth ? 1f : -1f;
		float zSeam = zs, zOut = zs + dirtSign * EDGE_W;
		Quad q = new Quad();
		q.pos[0] = new float[]{x0, y, zSeam}; q.uv[0] = new float[]{0f, 1f};
		q.pos[1] = new float[]{x0, y, zOut};  q.uv[1] = new float[]{0f, 0f};
		q.pos[2] = new float[]{x1, y, zSeam}; q.uv[2] = new float[]{0.5f, 1f};
		q.pos[3] = new float[]{x1, y, zOut};  q.uv[3] = new float[]{0.5f, 0f};
		for (int c = 0; c < 4; c++) {
			q.nrm[c] = new float[]{0f, 1f, 0f};
			q.ao[c] = 1f;
		}
		fixWindingUp(q);
		return q;
	}

	/** Ensures a flat quad's triangles wind so its face points +Y (up). */
	private static void fixWindingUp(Quad q) {
		float[] a = q.pos[0], b = q.pos[2], c = q.pos[1];
		float ux = b[0] - a[0], uz = b[2] - a[2];
		float vx = c[0] - a[0], vz = c[2] - a[2];
		float ny = uz * vx - ux * vz; // y of cross(u,v)
		if (ny < 0) {
			float[] tp = q.pos[1]; q.pos[1] = q.pos[2]; q.pos[2] = tp;
			float[] tu = q.uv[1]; q.uv[1] = q.uv[2]; q.uv[2] = tu;
		}
	}

	/** Assembles an ObjMesh (positions/UVs/normals/tris) from a list of quads.
	 *  When {@code rawUv}, the quads' authored UVs pass through unscaled (edge
	 *  strips already carry seam-space UVs); otherwise UVs are world-projected. */
	static MapModelObj.ObjMesh meshFromQuads(BchMapModel model, BchMapModel.MeshGeom g, List<Quad> quads, boolean rawUv) {
		float[] scale = rawUv ? new float[]{1f, 1f} : measureUvScale(model, g);
		MapModelObj.ObjMesh om = new MapModelObj.ObjMesh();
		om.meshIndex = g.meshIndex;
		int n = quads.size();
		om.positions = new float[n * 4][];
		om.uvs = new float[n * 4][];
		om.normals = new float[n * 4][];
		int[] tris = new int[n * 6];
		for (int i = 0; i < n; i++) {
			Quad q = quads.get(i);
			int b = i * 4;
			for (int c = 0; c < 4; c++) {
				om.positions[b + c] = q.pos[c];
				om.uvs[b + c] = new float[]{q.uv[c][0] * scale[0], q.uv[c][1] * scale[1]};
				om.normals[b + c] = q.nrm[c];
			}
			int t = i * 6;
			tris[t] = b;
			tris[t + 1] = b + 2;
			tris[t + 2] = b + 1;
			tris[t + 3] = b + 1;
			tris[t + 4] = b + 2;
			tris[t + 5] = b + 3;
		}
		om.triangles = tris;
		return om;
	}

	/** Floor quad for a tile; flat at height h, or SLOPED when rd>=0 (a ramp
	 *  descending toward direction rd drops that edge's 2 corners to h-1). */
	static Quad floorQuad(TilePalette[][] grid, int[][] height, int tx, int ty, int h, int rd) {
		float x0 = tx * TILE + ORIGIN, x1 = x0 + TILE;
		float z0 = ty * TILE + ORIGIN, z1 = z0 + TILE;
		float yHi = h * STEP, yLo = (h - 1) * STEP;
		// per-corner Y (TL,TR,BL,BR); the two corners on the descent edge drop to yLo
		float[] cy = {yHi, yHi, yHi, yHi};
		if (rd == 0) { // east low: TR,BR
			cy[1] = yLo;
			cy[3] = yLo;
		} else if (rd == 1) { // west low: TL,BL
			cy[0] = yLo;
			cy[2] = yLo;
		} else if (rd == 2) { // south low: BL,BR
			cy[2] = yLo;
			cy[3] = yLo;
		} else if (rd == 3) { // north low: TL,TR
			cy[0] = yLo;
			cy[1] = yLo;
		}
		Quad q = new Quad();
		float[][] p = {{x0, cy[0], z0}, {x1, cy[1], z0}, {x0, cy[2], z1}, {x1, cy[3], z1}};
		int[][] corner = {{tx, ty}, {tx + 1, ty}, {tx, ty + 1}, {tx + 1, ty + 1}};
		for (int c = 0; c < 4; c++) {
			q.pos[c] = p[c];
			q.uv[c] = new float[]{p[c][0], p[c][2]};
			q.nrm[c] = new float[]{0f, 1f, 0f};
			q.ao[c] = cornerAO(grid, height, corner[c][0], corner[c][1], h);
		}
		return q;
	}

	/** Vertical cliff quad on tile edge {@code dir}, spanning heights hn..h. */
	static Quad cliffQuad(int tx, int ty, int dir, int hn, int h) {
		float x0 = tx * TILE + ORIGIN, x1 = x0 + TILE;
		float z0 = ty * TILE + ORIGIN, z1 = z0 + TILE;
		float yb = hn * STEP, yt = h * STEP;
		// endpoints of the shared edge (a..b) + outward normal
		float ax, az, bx, bz, nx, nz;
		switch (dir) {
			case 0: ax = x1; az = z0; bx = x1; bz = z1; nx = 1; nz = 0; break;   // east
			case 1: ax = x0; az = z1; bx = x0; bz = z0; nx = -1; nz = 0; break;  // west
			case 2: ax = x1; az = z1; bx = x0; bz = z1; nx = 0; nz = 1; break;   // south
			default: ax = x0; az = z0; bx = x1; bz = z0; nx = 0; nz = -1; break; // north
		}
		Quad q = new Quad();
		// TL=top-a, TR=top-b, BL=bottom-a, BR=bottom-b (horizontal u along edge, v = height)
		float[][] p = {{ax, yt, az}, {bx, yt, bz}, {ax, yb, az}, {bx, yb, bz}};
		float lenA = 0f, lenB = dist(ax, az, bx, bz);
		float[] uPos = {0, lenB, 0, lenB};
		float[] vPos = {yt, yt, yb, yb};
		float[] aoTop = {0.9f, 0.9f, 0.55f, 0.55f}; // cliffs darker toward the base
		for (int c = 0; c < 4; c++) {
			q.pos[c] = p[c];
			q.uv[c] = new float[]{uPos[c], vPos[c]};
			q.nrm[c] = new float[]{nx, 0f, nz};
			q.ao[c] = aoTop[c];
		}
		return q;
	}

	/** Bakes tint x brightness x per-corner AO into each quad's 4 vertex colors. */
	static void bakeQuadLighting(BchMapModel model, BchMapModel.MeshGeom g, byte[] vtx, List<Quad> quads, TerrainLighting light) {
		BchMapModel.MeshAttr col = model.findAttr(g.meshIndex, 3);
		if (col == null) {
			return;
		}
		int compSize = col.size() / Math.max(1, col.elems);
		for (int i = 0; i < quads.size(); i++) {
			Quad q = quads.get(i);
			for (int c = 0; c < 4; c++) {
				int[] rgba = light.vertexColor(q.ao[c]);
				int base = (i * 4 + c) * g.stride + col.offset;
				for (int k = 0; k < col.elems; k++) {
					int o = base + k * compSize;
					if (col.type == 3) {
						putF(vtx, o, (k < 3 ? rgba[k] : rgba[3]) / 255f);
					} else {
						vtx[o] = (byte) (k < 4 ? rgba[k] : 0xFF);
					}
				}
			}
		}
	}

	/** AO at a grid point for a tile at {@code myHeight}: darker next to walls or taller ground. */
	private static float cornerAO(TilePalette[][] grid, int[][] height, int gx, int gy, int myHeight) {
		int occ = 0, total = 0;
		for (int dy = -1; dy <= 0; dy++) {
			for (int dx = -1; dx <= 0; dx++) {
				int x = gx + dx, y = gy + dy;
				if (x < 0 || y < 0 || x >= DIM || y >= DIM) {
					continue;
				}
				total++;
				TilePalette t = grid[y][x];
				if (t == null || !t.walkable || height[y][x] > myHeight) {
					occ++;
				}
			}
		}
		return total == 0 ? 1f : 1f - (float) occ / total;
	}

	private static int neighbourHeight(TilePalette[][] grid, int[][] height, int tx, int ty, int dir) {
		int nx = tx + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
		int ny = ty + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
		if (nx < 0 || ny < 0 || nx >= DIM || ny >= DIM) {
			return 0; // map edge = drop to base
		}
		TilePalette t = grid[ny][nx];
		if (t == null || t == TilePalette.VOID) {
			return 0; // void = drop to base (so raised ground gets a wall)
		}
		return height[ny][nx];
	}

	// ---- collision --------------------------------------------------------

	static byte[] buildCollision(TilePalette[][] grid, int[][] height, boolean[][] ramp) {
		List<float[]> tris = new ArrayList<>();
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				TilePalette t = grid[ty][tx];
				int h = height[ty][tx];
				int rd = rampDir(grid, height, ramp, tx, ty);
				float x0 = tx * TILE + ORIGIN, x1 = x0 + TILE;
				float z0 = ty * TILE + ORIGIN, z1 = z0 + TILE;
				if (t != null && t.floor) {
					// walkable floor - flat, or the ramp's sloped quad
					Quad q = floorQuad(grid, height, tx, ty, h, rd);
					tris.add(new float[]{q.pos[0][0], q.pos[0][1], q.pos[0][2], q.pos[2][0], q.pos[2][1], q.pos[2][2], q.pos[1][0], q.pos[1][1], q.pos[1][2]});
					tris.add(new float[]{q.pos[1][0], q.pos[1][1], q.pos[1][2], q.pos[2][0], q.pos[2][1], q.pos[2][2], q.pos[3][0], q.pos[3][1], q.pos[3][2]});
				}
				if (t == null || t == TilePalette.VOID) {
					continue;
				}
				// cliff walls block passage between levels - except the ramp's slope edge
				for (int dir = 0; dir < 4; dir++) {
					if (dir == rd) {
						continue;
					}
					int hn = neighbourHeight(grid, height, tx, ty, dir);
					if (hn < h) {
						addCliffCollision(tris, tx, ty, dir, hn, h);
					}
				}
			}
		}
		if (tris.isEmpty()) {
			tris.add(new float[]{ORIGIN, 0, ORIGIN, ORIGIN, 0, ORIGIN + TILE, ORIGIN + TILE, 0, ORIGIN});
		}
		return GfColl.build(tris, null);
	}

	private static void addCliffCollision(List<float[]> tris, int tx, int ty, int dir, int hn, int h) {
		float x0 = tx * TILE + ORIGIN, x1 = x0 + TILE;
		float z0 = ty * TILE + ORIGIN, z1 = z0 + TILE;
		float yb = hn * STEP, yt = h * STEP;
		float ax, az, bx, bz;
		switch (dir) {
			case 0: ax = x1; az = z0; bx = x1; bz = z1; break;
			case 1: ax = x0; az = z1; bx = x0; bz = z0; break;
			case 2: ax = x1; az = z1; bx = x0; bz = z1; break;
			default: ax = x0; az = z0; bx = x1; bz = z0; break;
		}
		tris.add(new float[]{ax, yt, az, ax, yb, az, bx, yt, bz});
		tris.add(new float[]{bx, yt, bz, ax, yb, az, bx, yb, bz});
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

	// ---- material resolution + UV scale -----------------------------------

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
				if (name != null && !isEdgeMaterial(name) && name.toLowerCase().contains(hint)) {
					return g.meshIndex;
				}
			}
		}
		return fallback;
	}

	/** The cliff material mesh (gake/cliff/rock), or the rock/ground fallback. */
	private static int resolveCliffMesh(BchMapModel model, int fallback) {
		for (String hint : new String[]{"gake", "cliff", "chip_rock", "rock", "iwa"}) {
			for (BchMapModel.MeshGeom g : model.geometry()) {
				if (!g.posOk) {
					continue;
				}
				String name = model.getMaterialName(model.getMeshMaterialIndex(g.meshIndex));
				if (name != null && !isEdgeMaterial(name) && name.toLowerCase().contains(hint)) {
					return g.meshIndex;
				}
			}
		}
		return fallback;
	}

	/** The grass-edge overlay mesh (chip_kusa_edge / chip_grass_edge), or -1. */
	public static int resolveEdgeMesh(BchMapModel model) {
		for (String hint : new String[]{"kusa_edge", "grass_edge", "edge_tex", "_edge"}) {
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
		return -1;
	}

	private static boolean isEdgeMaterial(String name) {
		String n = name.toLowerCase();
		return n.contains("_edge") || n.contains("edge_tex");
	}

	private static int defaultGroundMesh(BchMapModel model) {
		int best = -1, bestTris = -1;
		for (BchMapModel.MeshGeom g : model.geometry()) {
			if (g.posOk) {
				String name = model.getMaterialName(model.getMeshMaterialIndex(g.meshIndex));
				if (name != null && isEdgeMaterial(name)) {
					continue; // never treat the thin edge overlay as the ground
				}
				int tris = model.getTriangles(g.meshIndex).length;
				if (tris > bestTris) {
					bestTris = tris;
					best = g.meshIndex;
				}
			}
		}
		return best;
	}

	private static float dist(float ax, float az, float bx, float bz) {
		float dx = bx - ax, dz = bz - az;
		return (float) Math.sqrt(dx * dx + dz * dz);
	}

	private static void putF(byte[] b, int o, float f) {
		int v = Float.floatToIntBits(f);
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
		b[o + 2] = (byte) (v >> 16);
		b[o + 3] = (byte) (v >> 24);
	}

	private static float f32(byte[] b, int o) {
		return Float.intBitsToFloat((b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24));
	}
}
