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
		out.model = buildModel(donorModel, grid, height, ramp, null, null, light, edges);
		out.collision = buildCollision(grid, height, ramp);
		out.tilemap = buildTilemap(grid);
		out.props = new byte[]{0, 0, 0, 0};
		return out;
	}

	/**
	 * COMPOSITE build: edits an EXISTING region instead of rebuilding it from
	 * scratch. Only tiles flagged in {@code touched} are regenerated; every
	 * other tile keeps its retail geometry, collision and movement bytes.
	 * Retail triangles are not per-tile tessellated (one floor triangle can
	 * span hundreds of tiles), so the boundary is handled by exact CLIPPING:
	 * covering triangles are cut at the touched tiles' edges, their outside
	 * parts kept (attributes interpolated at the cut), the inside discarded.
	 * Painted floors sit at the retail surface height (sampled from the donor
	 * collision) plus the user's elevation offset, so edits on elevated
	 * terrain stay level with their surroundings.
	 *
	 * @param donorModel     the region's CURRENT visual model (also the tileset)
	 * @param donorCollision the region's current collision subfile (clipped + merged)
	 * @param donorTilemap   the region's current tilemap subfile (merged)
	 * @param touched        which tiles the user actually edited; a null mask
	 *                       degrades to the full from-scratch {@link #build}
	 */
	public static RegionFactory.BlankContent buildComposite(byte[] donorModel, byte[] donorCollision, byte[] donorTilemap,
			TilePalette[][] grid, int[][] height, boolean[][] ramp, boolean[][] touched, TerrainLighting light, boolean edges) {
		if (touched == null) {
			return build(donorModel, grid, height, ramp, light, edges);
		}
		if (height == null) {
			height = new int[DIM][DIM];
		}
		if (ramp == null) {
			ramp = new boolean[DIM][DIM];
		}
		RegionFactory.BlankContent out = new RegionFactory.BlankContent();
		boolean any = false;
		for (boolean[] row : touched) {
			for (boolean b : row) {
				any |= b;
			}
		}
		if (!any) {
			// nothing edited: the region passes through untouched, byte-exactly
			out.model = donorModel;
			out.collision = donorCollision;
			out.tilemap = donorTilemap;
			out.props = new byte[]{0, 0, 0, 0};
			return out;
		}
		float[][] baseY = sampleBaseY(donorCollision);
		out.model = buildModel(donorModel, grid, height, ramp, touched, baseY, light, edges);
		out.collision = buildCollisionComposite(donorCollision, grid, height, ramp, touched, baseY);
		out.tilemap = buildTilemapComposite(donorTilemap, grid, touched);
		out.props = new byte[]{0, 0, 0, 0};
		return out;
	}

	/**
	 * The visual model alone, composite-aware - the live 3D preview's path
	 * (collision is used only to place floors at the retail surface height).
	 */
	public static byte[] buildModelOnly(byte[] donorModel, byte[] donorCollision, TilePalette[][] grid, int[][] height,
			boolean[][] ramp, boolean[][] touched, TerrainLighting light, boolean edges) {
		if (height == null) {
			height = new int[DIM][DIM];
		}
		if (ramp == null) {
			ramp = new boolean[DIM][DIM];
		}
		float[][] baseY = touched != null ? sampleBaseY(donorCollision) : null;
		return buildModel(donorModel, grid, height, ramp, touched, baseY, light, edges);
	}

	// ---- retail surface heights (composite frame) -------------------------

	/**
	 * Seeds the painter's elevation grid from the region's collision: each
	 * tile's level = its retail GROUND height at the tile center (the lowest
	 * surface, so bridges/roofs above never hijack the frame), quantized to
	 * {@link #STEP} RELATIVE to the region's lowest ground. The composite
	 * builder uses the same sampling and baseline, so a tile whose level the
	 * user leaves alone regenerates at EXACTLY its retail height (the
	 * quantization offsets cancel), and below-zero caves or high plateaus
	 * keep the full 0..6 editing range.
	 */
	public static void seedHeightsFromCollision(byte[] coll, int[][] height) {
		float[][] by = sampleBaseY(coll);
		float base0 = baseFloor(by);
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				height[ty][tx] = Float.isNaN(by[ty][tx]) ? 0 : levelOf(by[ty][tx], base0);
			}
		}
	}

	/** The per-tile painted-floor Y grid for the region's CURRENT collision +
	 *  a level grid - the shared frame for floors, buildings, door props and
	 *  warps. An unusable collision degrades to the plain level*STEP frame. */
	public static float[][] floorYGrid(byte[] coll, int[][] height) {
		float[][] by = sampleBaseY(coll);
		float base0 = baseFloor(by);
		float[][] out = new float[DIM][DIM];
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				out[ty][tx] = floorYOf(by[ty][tx], height[ty][tx], base0);
			}
		}
		return out;
	}

	/** The region's ground baseline: its lowest sampled surface (level 0). */
	static float baseFloor(float[][] baseY) {
		float min = Float.NaN;
		for (float[] row : baseY) {
			for (float y : row) {
				if (!Float.isNaN(y) && (Float.isNaN(min) || y < min)) {
					min = y;
				}
			}
		}
		return Float.isNaN(min) ? 0f : min;
	}

	static int levelOf(float y, float base0) {
		return Math.max(0, Math.min(6, Math.round((y - base0) / STEP)));
	}

	/** Painted-floor Y for a tile: the retail surface plus the user's level
	 *  offset from the seeded level; baseline + level*STEP where no surface
	 *  exists (so uncovered tiles stay in the same frame as their neighbours). */
	static float floorYOf(float baseY, int h, float base0) {
		if (Float.isNaN(baseY)) {
			return base0 + h * STEP;
		}
		return baseY + (h - levelOf(baseY, base0)) * STEP;
	}

	/** Per-tile retail GROUND Y at the tile center (the LOWEST collision hit -
	 *  overhead decks and stamped-building roofs must not count), NaN where none. */
	static float[][] sampleBaseY(byte[] donorColl) {
		float[][] out = new float[DIM][DIM];
		for (float[] row : out) {
			java.util.Arrays.fill(row, Float.NaN);
		}
		if (!GfColl.isColl(donorColl)) {
			return out;
		}
		GfColl c;
		try {
			c = new GfColl(donorColl);
		} catch (RuntimeException ex) {
			return out;
		}
		for (float[] t : c.uniqueTris) {
			float minX = Math.min(t[0], Math.min(t[3], t[6])), maxX = Math.max(t[0], Math.max(t[3], t[6]));
			float minZ = Math.min(t[2], Math.min(t[5], t[8])), maxZ = Math.max(t[2], Math.max(t[5], t[8]));
			int tx0 = Math.max(0, (int) Math.floor((minX - ORIGIN) / TILE));
			int tx1 = Math.min(DIM - 1, (int) Math.floor((maxX - ORIGIN) / TILE));
			int ty0 = Math.max(0, (int) Math.floor((minZ - ORIGIN) / TILE));
			int ty1 = Math.min(DIM - 1, (int) Math.floor((maxZ - ORIGIN) / TILE));
			for (int ty = ty0; ty <= ty1; ty++) {
				for (int tx = tx0; tx <= tx1; tx++) {
					float px = (tx + 0.5f) * TILE + ORIGIN;
					float pz = (ty + 0.5f) * TILE + ORIGIN;
					float y = triYAt(t, px, pz);
					if (!Float.isNaN(y) && (Float.isNaN(out[ty][tx]) || y < out[ty][tx])) {
						out[ty][tx] = y;
					}
				}
			}
		}
		return out;
	}

	/** Y of the triangle's plane at plan-view point (px,pz) when the point is
	 *  inside the triangle (small tolerance); NaN otherwise (incl. vertical tris). */
	static float triYAt(float[] t, float px, float pz) {
		float ax = t[0], az = t[2], bx = t[3], bz = t[5], cx = t[6], cz = t[8];
		float d = (bz - cz) * (ax - cx) + (cx - bx) * (az - cz);
		if (Math.abs(d) < 1e-3f) {
			return Float.NaN; // vertical / degenerate in plan view
		}
		float wa = ((bz - cz) * (px - cx) + (cx - bx) * (pz - cz)) / d;
		float wb = ((cz - az) * (px - cx) + (ax - cx) * (pz - cz)) / d;
		float wc = 1f - wa - wb;
		float eps = -0.02f;
		if (wa < eps || wb < eps || wc < eps) {
			return Float.NaN;
		}
		return wa * t[1] + wb * t[4] + wc * t[7];
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

	static byte[] buildModel(byte[] donorModel, TilePalette[][] grid, int[][] height, boolean[][] ramp,
			boolean[][] touched, float[][] baseY, TerrainLighting light, boolean edges) {
		BchMapModel probe = new BchMapModel(donorModel);
		int meshCount = probe.meshCount;
		int groundMesh = defaultGroundMesh(probe);
		int cliffMesh = resolveCliffMesh(probe, groundMesh);
		int edgeMesh = edges ? resolveEdgeMesh(probe) : -1;

		//per-tile painted-floor Y: level*STEP from scratch, or the retail
		//surface plus the level offset in composite mode (baseline-relative)
		float base0 = baseY != null ? baseFloor(baseY) : 0f;
		float[][] yTop = new float[DIM][DIM];
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				yTop[ty][tx] = baseY != null ? floorYOf(baseY[ty][tx], height[ty][tx], base0) : height[ty][tx] * STEP;
			}
		}

		Map<TilePalette, Integer> terrainMesh = new HashMap<>();
		Map<Integer, List<Quad>> quadsByMesh = new HashMap<>();
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				TilePalette t = grid[ty][tx];
				if (t == null || t == TilePalette.VOID) {
					continue;
				}
				if (touched != null && !touched[ty][tx]) {
					continue; // composite: untouched tiles keep their retail geometry
				}
				int mi = terrainMesh.computeIfAbsent(t, tp -> resolveMesh(probe, tp, groundMesh));
				int h = height[ty][tx];
				int rd = rampDir(grid, height, ramp, tx, ty);
				float myY = yTop[ty][tx];
				if (mi >= 0) {
					//a ramp's foot lands on the descent neighbour's ACTUAL floor
					float rampLo = myY - STEP;
					if (rd >= 0) {
						Float dY = neighbourTopY(grid, yTop, baseY, touched, tx, ty, rd);
						if (dY != null) {
							rampLo = dY;
						}
					}
					quadsByMesh.computeIfAbsent(mi, k -> new ArrayList<>()).add(floorQuad(grid, height, tx, ty, h, rd, myY, rampLo));
				}
				// walls where this tile meets a different-height neighbour,
				// EXCEPT the ramp's descent edge (that side is a walkable slope).
				// FULL walls only for real steps (> half a level - retail slopes
				// vary by a few units per tile and must NOT sprout wall spam);
				// small drops get a thin visual skirt so no crack shows.
				for (int dir = 0; dir < 4; dir++) {
					if (dir == rd) {
						continue;
					}
					Float nY = neighbourTopY(grid, yTop, baseY, touched, tx, ty, dir);
					if (nY == null) {
						continue;
					}
					float drop = myY - nY;
					if (drop > 0.75f) {
						quadsByMesh.computeIfAbsent(cliffMesh, k -> new ArrayList<>()).add(cliffQuad(tx, ty, dir, nY, myY));
					} else if (touched != null && !isTouched(touched, tx, ty, dir) && -drop > STEP * 0.55f) {
						//the untouched neighbour is a real step HIGHER: painting
						//at a cliff's foot removed the retail face on this edge -
						//rebuild it from the neighbour's side so no hole remains
						int nx = tx + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
						int ny = ty + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
						int opp = dir == 0 ? 1 : dir == 1 ? 0 : dir == 2 ? 3 : 2;
						quadsByMesh.computeIfAbsent(cliffMesh, k -> new ArrayList<>()).add(cliffQuad(nx, ny, opp, myY, nY));
					}
				}
			}
		}

		// GameFreak-style transition strips along grass<->dirt/sand seams
		if (edgeMesh >= 0) {
			addEdgeStrips(grid, height, touched, yTop, quadsByMesh, edgeMesh);
		}

		//composite clip regions: painted tiles exactly; the edge-ribbon mesh
		//uses a dilated region so stale ribbons NEXT to painted tiles go too
		List<float[]> rects = null, rectsEdge = null;
		float overheadY = Float.MAX_VALUE;
		if (touched != null) {
			rects = TileClip.regionRects(touched, TILE, ORIGIN, 0f);
			rectsEdge = TileClip.regionRects(touched, TILE, ORIGIN, EDGE_W + 0.5f);
			//structures well ABOVE the painted floors (bridge decks, roofs)
			//survive the cut - painting the ground must not delete an overpass
			float top = -Float.MAX_VALUE;
			for (int ty = 0; ty < DIM; ty++) {
				for (int tx = 0; tx < DIM; tx++) {
					if (touched[ty][tx]) {
						top = Math.max(top, yTop[ty][tx]);
					}
				}
			}
			overheadY = top + 1.5f * STEP;
		}

		byte[] current = donorModel;
		for (int mi = 0; mi < meshCount; mi++) {
			BchMapModel m = new BchMapModel(current);
			BchMapModel.MeshGeom g = m.geometry().get(mi);
			if (!g.posOk) {
				continue;
			}
			List<Quad> quads = quadsByMesh.get(mi);
			if (touched != null) {
				current = compositeMesh(m, g, mi, quads, mi == edgeMesh ? rectsEdge : rects, overheadY, light, mi == edgeMesh);
				continue;
			}
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

	/**
	 * The neighbour's top surface for cliff generation: its painted floor when
	 * it is painted, its retail surface when composite-untouched, ground level
	 * for void/off-map in from-scratch mode. Off-map in composite mode answers
	 * with the tile's OWN retail ground, so raised/lowered tiles at the cell
	 * border still close with a wall. Null = no wall (surface unknown).
	 */
	private static Float neighbourTopY(TilePalette[][] grid, float[][] yTop, float[][] baseY,
			boolean[][] touched, int tx, int ty, int dir) {
		int nx = tx + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
		int ny = ty + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
		if (nx < 0 || ny < 0 || nx >= DIM || ny >= DIM) {
			if (touched == null) {
				return 0f;
			}
			float own = baseY != null ? baseY[ty][tx] : Float.NaN;
			return Float.isNaN(own) ? null : Float.valueOf(own);
		}
		if (touched != null && !touched[ny][nx]) {
			float b = baseY != null ? baseY[ny][nx] : Float.NaN;
			return Float.isNaN(b) ? null : Float.valueOf(b);
		}
		TilePalette t = grid[ny][nx];
		if (t == null || t == TilePalette.VOID) {
			if (touched == null) {
				return 0f; // void = drop to base (so raised ground gets a wall)
			}
			float b = baseY != null ? baseY[ny][nx] : Float.NaN;
			return Float.isNaN(b) ? 0f : b;
		}
		return yTop[ny][nx];
	}

	/** The neighbour tile's touched state; off-map counts as untouched. */
	private static boolean isTouched(boolean[][] touched, int tx, int ty, int dir) {
		int nx = tx + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
		int ny = ty + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
		return nx >= 0 && ny >= 0 && nx < DIM && ny < DIM && touched[ny][nx];
	}

	/**
	 * Composite rewrite of one mesh: donor triangles are CLIPPED at the region
	 * boundary (outside parts kept with attributes interpolated at the cut,
	 * inside parts discarded; vertical wall triangles - which span many tiles
	 * just like floors - are 1D-clipped along their run), geometry above
	 * {@code overheadY} (bridge decks, roofs) is preserved whole, the
	 * surviving original vertices are compacted, and the generated quads
	 * appended. A mesh with nothing cut and nothing generated is left
	 * byte-identical.
	 */
	private static byte[] compositeMesh(BchMapModel m, BchMapModel.MeshGeom g, int mi,
			List<Quad> quads, List<float[]> rects, float overheadY, TerrainLighting light, boolean rawUv) {
		int[] tris = m.getTriangles(mi);
		float[][] pos = m.getVertexPositions(mi);
		boolean anyGen = quads != null && !quads.isEmpty();

		//the full attribute layout is the decode/encode map for clip vertices;
		//meshes with an exotic layout cannot be cut - keep them whole (their
		//content simply survives under the paint) but still take generated quads
		List<BchMapModel.MeshAttr> attrs = m.attributes(mi);
		int attrBytes = 0, totalComps = 0, posComp = -1;
		for (BchMapModel.MeshAttr a : attrs) {
			if (a.name == 0 && posComp < 0) {
				posComp = totalComps;
			}
			attrBytes += a.size();
			totalComps += a.elems;
		}
		boolean clippable = !attrs.isEmpty() && attrBytes == g.stride && posComp >= 0;

		List<Integer> keptOrig = new ArrayList<>();     // original tri indices kept whole
		List<float[][]> clipTris = new ArrayList<>();   // cut fragments (full comp vectors)
		boolean anyCut = false;
		for (int t = 0; t + 2 < tris.length; t += 3) {
			int a = tris[t], b = tris[t + 1], c = tris[t + 2];
			if (a >= pos.length || b >= pos.length || c >= pos.length) {
				continue; // malformed index - drop rather than crash
			}
			//overhead structures survive: the cut only applies near the ground
			float minY = Math.min(pos[a][1], Math.min(pos[b][1], pos[c][1]));
			if (!clippable || minY > overheadY) {
				keptOrig.add(a);
				keptOrig.add(b);
				keptOrig.add(c);
				continue;
			}
			float[][] xz = {{pos[a][0], pos[a][2]}, {pos[b][0], pos[b][2]}, {pos[c][0], pos[c][2]}};
			float area2 = Math.abs((xz[1][0] - xz[0][0]) * (xz[2][1] - xz[0][1])
					- (xz[2][0] - xz[0][0]) * (xz[1][1] - xz[0][1]));
			List<float[]> poly = new ArrayList<>(3);
			poly.add(decodeVertex(m.raw, g.vtxAbs + a * g.stride, attrs, totalComps));
			poly.add(decodeVertex(m.raw, g.vtxAbs + b * g.stride, attrs, totalComps));
			poly.add(decodeVertex(m.raw, g.vtxAbs + c * g.stride, attrs, totalComps));
			List<List<float[]>> parts;
			if (area2 < 1.0f) {
				//vertical wall / sliver: cut ALONG its run (retail walls span
				//many tiles; boundary contact counts - cliff faces stand
				//exactly ON tile edges)
				parts = TileClip.clipVerticalPoly(poly, rects, posComp, posComp + 2);
			} else {
				parts = TileClip.subtractRegion(poly, rects, posComp, posComp + 2);
			}
			if (parts.size() == 1 && parts.get(0) == poly) {
				keptOrig.add(a);
				keptOrig.add(b);
				keptOrig.add(c);
				continue;
			}
			anyCut = true;
			for (List<float[]> part : parts) {
				clipTris.addAll(TileClip.fan(part));
			}
		}
		if (!anyCut && !anyGen) {
			return m.raw; // untouched mesh: preserve byte-exactly
		}

		//compact the surviving original vertices (dropped/cut geometry must not
		//leak dead vertices into every re-apply)
		java.util.LinkedHashMap<Integer, Integer> remap = new java.util.LinkedHashMap<>();
		for (int idx : keptOrig) {
			remap.putIfAbsent(idx, remap.size());
		}
		int keptVerts = remap.size();
		int clipVerts = clipTris.size() * 3;

		byte[] genVtx = new byte[0];
		int[] genTris = new int[0];
		if (anyGen) {
			MapModelObj.ObjMesh om = meshFromQuads(m, g, quads, rawUv);
			genVtx = MapModelObjImporter.buildVertexBytes(m, g, om);
			bakeQuadLighting(m, g, genVtx, quads, light);
			genTris = om.triangles;
		}

		byte[] vtx = new byte[(keptVerts + clipVerts) * g.stride + genVtx.length];
		for (Map.Entry<Integer, Integer> e : remap.entrySet()) {
			System.arraycopy(m.raw, g.vtxAbs + e.getKey() * g.stride, vtx, e.getValue() * g.stride, g.stride);
		}
		int w = keptVerts * g.stride;
		for (float[][] ct : clipTris) {
			for (float[] v : ct) {
				encodeVertex(v, attrs, vtx, w);
				w += g.stride;
			}
		}
		System.arraycopy(genVtx, 0, vtx, (keptVerts + clipVerts) * g.stride, genVtx.length);

		int[] newTris = new int[keptOrig.size() + clipVerts + genTris.length];
		int n = 0;
		for (int idx : keptOrig) {
			newTris[n++] = remap.get(idx);
		}
		for (int i = 0; i < clipVerts; i++) {
			newTris[n++] = keptVerts + i;
		}
		int genBase = keptVerts + clipVerts;
		for (int gi : genTris) {
			newTris[n++] = gi + genBase;
		}
		if (newTris.length == 0) {
			// everything on this mesh was painted away - degenerate like the
			// full rebuild does for unused meshes
			byte[] one = new byte[g.stride];
			System.arraycopy(m.raw, g.vtxAbs, one, 0, g.stride);
			return m.setMeshGeometry(mi, one, new int[]{0, 0, 0});
		}
		return m.setMeshGeometry(mi, vtx, newTris);
	}

	// ---- generic vertex codec (clip-vertex reconstruction) ----------------

	/** Bytes per PICA component type: 0=s8, 1=u8, 2=s16, 3=float. */
	private static final int[] COMP_BYTES = {1, 1, 2, 4};

	/** Decodes one vertex record into a flat component vector, attr by attr. */
	static float[] decodeVertex(byte[] raw, int base, List<BchMapModel.MeshAttr> attrs, int totalComps) {
		float[] out = new float[totalComps];
		int c = 0;
		for (BchMapModel.MeshAttr a : attrs) {
			int cs = COMP_BYTES[a.type];
			for (int k = 0; k < a.elems; k++) {
				int o = base + a.offset + k * cs;
				switch (a.type) {
					case 0: out[c++] = raw[o]; break;
					case 1: out[c++] = raw[o] & 0xFF; break;
					case 2: out[c++] = (short) ((raw[o] & 0xFF) | (raw[o + 1] << 8)); break;
					default: out[c++] = Float.intBitsToFloat((raw[o] & 0xFF) | ((raw[o + 1] & 0xFF) << 8)
							| ((raw[o + 2] & 0xFF) << 16) | ((raw[o + 3] & 0xFF) << 24)); break;
				}
			}
		}
		return out;
	}

	/** Encodes a component vector back into vertex bytes (round + clamp). */
	static void encodeVertex(float[] comps, List<BchMapModel.MeshAttr> attrs, byte[] out, int base) {
		int c = 0;
		for (BchMapModel.MeshAttr a : attrs) {
			int cs = COMP_BYTES[a.type];
			for (int k = 0; k < a.elems; k++) {
				int o = base + a.offset + k * cs;
				float v = comps[c++];
				switch (a.type) {
					case 0: out[o] = (byte) Math.max(-128, Math.min(127, Math.round(v))); break;
					case 1: out[o] = (byte) Math.max(0, Math.min(255, Math.round(v))); break;
					case 2: {
						int s = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v)));
						out[o] = (byte) s;
						out[o + 1] = (byte) (s >> 8);
						break;
					}
					default: {
						int bits = Float.floatToIntBits(v);
						out[o] = (byte) bits;
						out[o + 1] = (byte) (bits >> 8);
						out[o + 2] = (byte) (bits >> 16);
						out[o + 3] = (byte) (bits >> 24);
						break;
					}
				}
			}
		}
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

	/** Lays a grass-edge ribbon along every same-height grass&harr;dirt/sand seam.
	 *  In composite mode ({@code touched} non-null) only seams with at least one
	 *  touched side get a strip - retail maps carry their own baked edges (any
	 *  stale ribbon near a painted tile is clipped away by the dilated region). */
	static void addEdgeStrips(TilePalette[][] grid, int[][] height, boolean[][] touched, float[][] yTop, Map<Integer, List<Quad>> quadsByMesh, int edgeMesh) {
		List<Quad> strips = new ArrayList<>();
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				int ga = terrainGroup(grid[ty][tx]);
				int ha = height[ty][tx];
				if (tx + 1 < DIM && ha == height[ty][tx + 1]
						&& (touched == null || touched[ty][tx] || touched[ty][tx + 1])) {
					int gb = terrainGroup(grid[ty][tx + 1]);
					if (isGrassDirt(ga, gb)) {
						strips.add(edgeQuadEW(tx, ty, yTop[ty][tx] + EDGE_LIFT, ga == GRP_GRASS));
					}
				}
				if (ty + 1 < DIM && ha == height[ty + 1][tx]
						&& (touched == null || touched[ty][tx] || touched[ty + 1][tx])) {
					int gb = terrainGroup(grid[ty + 1][tx]);
					if (isGrassDirt(ga, gb)) {
						strips.add(edgeQuadNS(tx, ty, yTop[ty][tx] + EDGE_LIFT, ga == GRP_GRASS));
					}
				}
			}
		}
		if (!strips.isEmpty()) {
			quadsByMesh.computeIfAbsent(edgeMesh, k -> new ArrayList<>()).addAll(strips);
		}
	}

	/** Edge ribbon on a vertical (east/west) seam; strip lies on the dirt side. */
	private static Quad edgeQuadEW(int tx, int ty, float y, boolean grassIsWest) {
		float xs = (tx + 1) * TILE + ORIGIN;
		float z0 = ty * TILE + ORIGIN, z1 = z0 + TILE;
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
	private static Quad edgeQuadNS(int tx, int ty, float y, boolean grassIsNorth) {
		float zs = (ty + 1) * TILE + ORIGIN;
		float x0 = tx * TILE + ORIGIN, x1 = x0 + TILE;
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

	/** Floor quad for a tile; flat at {@code yHi} (the painted-floor Y - level
	 *  frame or retail-surface frame), or SLOPED when rd>=0 (a ramp descending
	 *  toward direction rd drops that edge's 2 corners to {@code yLo} - the
	 *  descent neighbour's actual floor). The level h drives the AO sampling. */
	static Quad floorQuad(TilePalette[][] grid, int[][] height, int tx, int ty, int h, int rd, float yHi, float yLo) {
		float x0 = tx * TILE + ORIGIN, x1 = x0 + TILE;
		float z0 = ty * TILE + ORIGIN, z1 = z0 + TILE;
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

	/** Vertical cliff quad on tile edge {@code dir}, spanning world Y yb..yt. */
	static Quad cliffQuad(int tx, int ty, int dir, float yb, float yt) {
		float x0 = tx * TILE + ORIGIN, x1 = x0 + TILE;
		float z0 = ty * TILE + ORIGIN, z1 = z0 + TILE;
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
		float[][] yTop = new float[DIM][DIM];
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				yTop[ty][tx] = height[ty][tx] * STEP;
			}
		}
		addGeneratedCollision(tris, grid, height, ramp, null, null, yTop);
		if (tris.isEmpty()) {
			tris.add(new float[]{ORIGIN, 0, ORIGIN, ORIGIN, 0, ORIGIN + TILE, ORIGIN + TILE, 0, ORIGIN});
		}
		return GfColl.build(tris, null);
	}

	/** Emits the generated floors + cliff walls for every (touched) tile.
	 *  Blocking walls only for REAL steps (over half a level) - retail slopes
	 *  vary by a few units per tile and must stay walkable; the composite
	 *  low-side case rebuilds the untouched higher neighbour's wall. */
	private static void addGeneratedCollision(List<float[]> tris, TilePalette[][] grid, int[][] height,
			boolean[][] ramp, boolean[][] touched, float[][] baseY, float[][] yTop) {
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				if (touched != null && !touched[ty][tx]) {
					continue;
				}
				TilePalette t = grid[ty][tx];
				int h = height[ty][tx];
				int rd = rampDir(grid, height, ramp, tx, ty);
				float myY = yTop[ty][tx];
				if (t != null && t.floor) {
					// walkable floor - flat, or the ramp's sloped quad
					float rampLo = myY - STEP;
					if (rd >= 0) {
						Float dY = neighbourTopY(grid, yTop, baseY, touched, tx, ty, rd);
						if (dY != null) {
							rampLo = dY;
						}
					}
					Quad q = floorQuad(grid, height, tx, ty, h, rd, myY, rampLo);
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
					Float nY = neighbourTopY(grid, yTop, baseY, touched, tx, ty, dir);
					if (nY == null) {
						continue;
					}
					float drop = myY - nY;
					if (drop > STEP * 0.55f) {
						addCliffCollision(tris, tx, ty, dir, nY, myY);
					} else if (touched != null && !isTouched(touched, tx, ty, dir) && -drop > STEP * 0.55f) {
						int nx = tx + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
						int ny = ty + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
						int opp = dir == 0 ? 1 : dir == 1 ? 0 : dir == 2 ? 3 : 2;
						addCliffCollision(tris, nx, ny, opp, myY, nY);
					}
				}
			}
		}
	}

	private static void addCliffCollision(List<float[]> tris, int tx, int ty, int dir, float yb, float yt) {
		float x0 = tx * TILE + ORIGIN, x1 = x0 + TILE;
		float z0 = ty * TILE + ORIGIN, z1 = z0 + TILE;
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

	/**
	 * Composite collision: donor triangles are CLIPPED at the touched region's
	 * boundary (outside parts kept exactly, inside discarded; vertical wall
	 * triangles touching the region drop whole), then the touched tiles'
	 * generated floors/cliffs are added. Constants ride along from the donor.
	 */
	static byte[] buildCollisionComposite(byte[] donorColl, TilePalette[][] grid, int[][] height,
			boolean[][] ramp, boolean[][] touched, float[][] baseY) {
		List<float[]> tris = new ArrayList<>();
		List<float[]> rects = TileClip.regionRects(touched, TILE, ORIGIN, 0f);
		float base0 = baseFloor(baseY);
		float[][] yTop = new float[DIM][DIM];
		float touchedTop = -Float.MAX_VALUE;
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				yTop[ty][tx] = floorYOf(baseY[ty][tx], height[ty][tx], base0);
				if (touched[ty][tx]) {
					touchedTop = Math.max(touchedTop, yTop[ty][tx]);
				}
			}
		}
		float overheadY = touchedTop + 1.5f * STEP;
		GfColl template = null;
		if (GfColl.isColl(donorColl)) {
			template = new GfColl(donorColl);
			for (float[] t : template.uniqueTris) {
				//overhead structures (bridge decks, roofs) survive the cut
				if (Math.min(t[1], Math.min(t[4], t[7])) > overheadY) {
					tris.add(t);
					continue;
				}
				List<float[]> poly = new ArrayList<>(3);
				poly.add(new float[]{t[0], t[1], t[2]});
				poly.add(new float[]{t[3], t[4], t[5]});
				poly.add(new float[]{t[6], t[7], t[8]});
				float[][] xz = {{t[0], t[2]}, {t[3], t[5]}, {t[6], t[8]}};
				float area2 = Math.abs((xz[1][0] - xz[0][0]) * (xz[2][1] - xz[0][1])
						- (xz[2][0] - xz[0][0]) * (xz[1][1] - xz[0][1]));
				List<List<float[]>> parts = area2 < 1.0f
						? TileClip.clipVerticalPoly(poly, rects, 0, 2)
						: TileClip.subtractRegion(poly, rects, 0, 2);
				if (parts.size() == 1 && parts.get(0) == poly) {
					tris.add(t); //untouched by the region: byte-faithful floats
					continue;
				}
				for (List<float[]> part : parts) {
					for (float[][] ft : TileClip.fan(part)) {
						tris.add(new float[]{ft[0][0], ft[0][1], ft[0][2],
							ft[1][0], ft[1][1], ft[1][2], ft[2][0], ft[2][1], ft[2][2]});
					}
				}
			}
		}
		addGeneratedCollision(tris, grid, height, ramp, touched, baseY, yTop);
		if (tris.isEmpty()) {
			tris.add(new float[]{ORIGIN, 0, ORIGIN, ORIGIN, 0, ORIGIN + TILE, ORIGIN + TILE, 0, ORIGIN});
		}
		return GfColl.build(tris, template);
	}

	// ---- tilemap ----------------------------------------------------------

	/** Composite tilemap: the donor's movement bytes with only the touched
	 *  tiles' tuples overwritten. An unusable donor degrades to a full build. */
	static byte[] buildTilemapComposite(byte[] donorTilemap, TilePalette[][] grid, boolean[][] touched) {
		if (donorTilemap == null || donorTilemap.length < 4 + DIM * DIM * 4
				|| (donorTilemap[0] & 0xFF) != DIM || (donorTilemap[2] & 0xFF) != DIM) {
			return buildTilemap(grid);
		}
		byte[] out = donorTilemap.clone();
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				if (!touched[ty][tx]) {
					continue;
				}
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
