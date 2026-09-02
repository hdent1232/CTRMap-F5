package ctrmap.formats.tilemap;

import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.MapModelObj;
import ctrmap.formats.h3d.MapModelObjImporter;
import ctrmap.formats.h3d.RegionFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

	/** True when this map has a REAL material for the brush (not the fallback).
	 *  {@link TerrainCatalog} imports one when it does not. */
	public static boolean hasMaterialFor(BchMapModel probe, TilePalette brush) {
		return resolveMesh(probe, brush, -1) >= 0;
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
		//Give the model the catalogue's cliff material before anything looks for
		//one. TerrainCatalog.ensureCliffMaterial existed but was never called
		//from anywhere, so the whole CLIFF row was inert: resolveCliffMesh fell
		//straight through to its name hints and every generated cliff took the
		//colour of whatever rock the template region happened to carry. That is
		//exactly the thing the comment inside resolveCliffMesh says it is there
		//to prevent.
		TerrainCatalog.ImportResult cliffImport = TerrainCatalog.ensureCliffMaterial(donorModel);
		if (cliffImport != null && cliffImport.model != null) {
			donorModel = cliffImport.model;
		}
		//and vanilla's ADDITIVE molten overlay, if the palette names one
		TerrainCatalog.ImportResult churnImport = TerrainCatalog.ensureChurnMaterial(donorModel);
		if (churnImport != null && churnImport.model != null) {
			donorModel = churnImport.model;
		}
		BchMapModel probe = new BchMapModel(donorModel);
		int meshCount = probe.meshCount;
		int groundMesh = defaultGroundMesh(probe);
		int cliffMesh = resolveCliffMesh(probe, groundMesh);
		int edgeMesh = edges ? resolveEdgeMesh(probe) : -1;
		//cliff faces are gathered here and welded into contour strips after the
		//tile sweep, so a boundary becomes one continuous slope rather than a
		//row of separate slabs
		List<CliffEdge> cliffEdges = new ArrayList<>();

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
					//Sink the water. Painted flat, the river sat exactly level with
				//its banks - a blue carpet laid in the lawn, with no channel
				//and nothing to tell the eye it was lower than the grass. A
				//real river is cut into the ground. The drop is deliberately
				//less than a full elevation step: a whole step would make a
				//canyon and would also make the tile a cliff everywhere it met
				//the bank, whereas a few units reads as a watercourse and
				//leaves the shoreline to addShoreBands.
				float surfaceY = isWet(t) ? myY - WATER_SINK : myY;
				quadsByMesh.computeIfAbsent(mi, k -> new ArrayList<>()).add(floorQuad(grid, height, tx, ty, h, rd, surfaceY, rampLo));
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
					int qx = tx + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
					int qy = ty + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
					if (terrainCovered != null && terrainCovered[ty][tx]
							&& qx >= 0 && qy >= 0 && qx < DIM && qy < DIM
							&& terrainCovered[qy][qx]) {
						continue;   //authored terrain already stands here
					}
					if (drop > 0.75f) {
						CliffEdge ce = cliffEdge(tx, ty, dir, nY, myY);
						//A cliff face leans a whole tile out for a two-level
						//drop. Where the ground it leans over is a ramp or a
						//path, that is rock lying across the route: the corridor
						//is two tiles wide and the faces flanking it ate both,
						//leaving the way through buried in shards. Mark it so
						//the face is built steep instead.
						int lx = tx + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
						int ly = ty + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
						if (lx < 0 || ly < 0 || lx >= DIM || ly >= DIM) {
							//Off the edge of the map. There is no ground below
							//this face at all - what it stands over is the
							//retail terrain the region sits in - so a backing
							//wall here is a slab hanging in the painted area
							//with nothing behind it to back.
							ce.donorSide = true;
						}
						if (lx >= 0 && ly >= 0 && lx < DIM && ly < DIM) {
							ce.tight = ramp[ly][lx] || grid[ly][lx] == TilePalette.PATH
									|| grid[ly][lx] == TilePalette.SAND;
							//Below this face is retail ground the painter never
							//touched. It already has its own geometry, so the
							//visible strip is all that is wanted here - a
							//backing wall would just be a tall slab standing
							//inside the painted area with nothing to back.
							ce.donorSide = touched != null && !touched[ly][lx];
						}
						cliffEdges.add(ce);
					} else if (touched != null && !isTouched(touched, tx, ty, dir) && -drop > STEP * 0.55f) {
						//the untouched neighbour is a real step HIGHER: painting
						//at a cliff's foot removed the retail face on this edge -
						//rebuild it from the neighbour's side so no hole remains
						int nx = tx + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
						int ny = ty + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
						int opp = dir == 0 ? 1 : dir == 1 ? 0 : dir == 2 ? 3 : 2;
						//Same rule from the other side: the low ground this face
						//leans over is THIS tile, and if that is a ramp or a
						//path the face must stand up rather than lie across it.
						//This is the call that builds the walls flanking a ramp
						//corridor, and leaving it out was why they still buried
						//the route after the other call site was fixed.
						CliffEdge ce2 = cliffEdge(nx, ny, opp, myY, nY);
						ce2.tight = ramp[ty][tx] || grid[ty][tx] == TilePalette.PATH
								|| grid[ty][tx] == TilePalette.SAND;
						//This face is rebuilt from an UNTOUCHED neighbour's side
						//- its top rests on retail ground the painter never
						//changed. The visible strip restores what painting
						//removed; a backing wall behind it would be a tall slab
						//with nothing to back, standing in the painted area.
						ce2.donorSide = true;
						cliffEdges.add(ce2);
					}
				}
				//A ramp's floor falls WITHIN its own tile, but its height field
				//still reads as the level it starts from, so the wall test above
				//compares two equal integers along the ramp's sides and emits
				//nothing. The ground beside the ramp stays flat while the ramp
				//sinks away from it, and the triangular gap between them opens
				//straight through the map - which is what appeared beside every
				//new ramp corridor. Skirt the sides with that same triangle.
				if (rd >= 0) {
					float rampLo2 = myY - STEP;
					Float dY2 = neighbourTopY(grid, yTop, baseY, touched, tx, ty, rd);
					if (dY2 != null) {
						rampLo2 = dY2;
					}
					//The skirt fills the triangle between a ramp's sloping floor
					//and the flat ground beside it. It must be bounded BY that
					//ground: pinning its top to the ramp's own height while the
					//neighbour sits far below left a blade standing out in open
					//air, which is what was scattered around every corridor.
					//Clamp the top to whichever is lower, and skip entirely when
					//the neighbour is below the slope anyway - there is nothing
					//to fill there, the ordinary wall already covers it.
					for (int side : (rd == 0 || rd == 1) ? new int[]{2, 3} : new int[]{0, 1}) {
						int sx3 = tx + (side == 0 ? 1 : side == 1 ? -1 : 0);
						int sy3 = ty + (side == 2 ? 1 : side == 3 ? -1 : 0);
						if (sx3 >= 0 && sy3 >= 0 && sx3 < DIM && sy3 < DIM
								&& ramp[sy3][sx3] && height[sy3][sx3] == h
								&& rampDir(grid, height, ramp, sx3, sy3) == rd) {
							continue;   //the neighbouring lane of this same ramp
						}
						Float sY = neighbourTopY(grid, yTop, baseY, touched, tx, ty, side);
						if (sY == null) {
							continue;
						}
						//Bounding the top to the neighbour is geometrically the
						//right thing, but it seals far less: measured over four
						//camera angles it takes the sky visible through the map
						//from 348px to 2338px, because where the neighbour is
						//low the ordinary wall that should cover the rest is not
						//actually there. Until that is fixed the taller skirt
						//stays, and its cost is a few blades near corridors.
						List<Quad> cl = quadsByMesh.computeIfAbsent(cliffMesh, k -> new ArrayList<>());
						Quad sk = rampSkirt(tx, ty, side, rd, myY, rampLo2);
						if (sk != null) {
							cl.add(sk);
							SKIRTS[0]++;
						} else {
							SKIRTS[1]++;
						}
						//And the UNDERSIDE. The skirt fills the wedge between the
						//ramp's sloping floor and the flat ground level with its
						//top, which is only half the problem: below that floor
						//there is nothing at all, so a ramp seen from the low
						//side shows sky straight under the slope it is standing
						//on. This apron hangs from the floor down past whatever
						//is beside it.
						Quad ap = rampApron(tx, ty, side, rd, myY, rampLo2);
						if (ap != null) {
							cl.add(ap);
							SKIRTS[2]++;
						}
					}
				}
			}
		}

		//A plain vertical wall behind every step, at the exact tile boundary.
		//The decorative cliff leans outward from that boundary, so this backing
		//sits inside it and is never seen - until the decorative strips fail to
		//meet each other, and then it is all that stands between the player and
		//a view through the map. Junctions are where they fail: strips are split
		//by the step they belong to, and a corner where a one-step wall meets a
		//two-step wall has been patched three times here (a shared bisector, a
		//shared reach, an overlapping collar) and still left slivers, one on
		//each map edge. Patching the seam geometry chases the symptom; a solid
		//wall behind it cannot have a seam at all.
		List<Quad> backing = quadsByMesh.computeIfAbsent(cliffMesh, k -> new ArrayList<>());
		for (CliffEdge e : cliffEdges) {
			if (System.getProperty("nobacking") != null) {
				break;
			}
			if (e.donorSide) {
				SKIRTS[1]++;
				continue;
			}
			SKIRTS[0]++;
			backing.add(backingWall(e));
		}

		//A post at every corner where walls of DIFFERENT depth meet. Those
		//corners belong to two separate strips, so neither strip's mitre closes
		//the wedge between them, and the backing walls do not help either: each
		//is a flat plane through its own tile edge, and the slot sits in the
		//space outside both. It is a small notch - the last one measured three
		//pixels across - but it is a hole in the map. A short column standing
		//on the corner fills it from every direction at once, and like the rest
		//of the backing it lives inside the visible cliff and is never seen.
		Map<String, float[]> post = new LinkedHashMap<>();   // x, z, maxTop, minBot, levelKeys seen
		Map<String, Set<String>> postLevels = new LinkedHashMap<>();
		for (CliffEdge e : cliffEdges) {
			for (int end = 0; end < 2; end++) {
				String k = end == 0 ? e.startKey() : e.endKey();
				float x = end == 0 ? e.ax : e.bx, z = end == 0 ? e.az : e.bz;
				float[] p = post.get(k);
				if (p == null) {
					post.put(k, new float[]{x, z, e.yTop, e.yBot});
				} else {
					p[2] = Math.max(p[2], e.yTop);
					p[3] = Math.min(p[3], e.yBot);
				}
				postLevels.computeIfAbsent(k, q -> new java.util.LinkedHashSet<>()).add(e.levelKey());
			}
		}
		for (Map.Entry<String, float[]> en : post.entrySet()) {
			if (System.getProperty("noposts") != null) {
				break;
			}
			if (postLevels.get(en.getKey()).size() < 2) {
				continue;   //one depth only: the strip's own mitre already closes it
			}
			float[] p = en.getValue();
			//four blades: both axes, and each facing both ways, because a
			//single quad is one-sided and which side the slot opens on
			//depends entirely on where the camera is
			backing.add(cornerPost(p[0], p[1], p[2], p[3], true, 1f));
			backing.add(cornerPost(p[0], p[1], p[2], p[3], true, -1f));
			backing.add(cornerPost(p[0], p[1], p[2], p[3], false, 1f));
			backing.add(cornerPost(p[0], p[1], p[2], p[3], false, -1f));
		}

		emitCliffStrips(cliffEdges, quadsByMesh, cliffMesh, groundMesh);

		// GameFreak-style transition strips along grass<->dirt/sand seams
		if (edgeMesh >= 0) {
			addEdgeStrips(grid, height, touched, yTop, quadsByMesh, edgeMesh);
		}

		addChurnLayer(grid, height, yTop, touched, quadsByMesh, probe);
		addShoreBands(grid, height, yTop, touched, quadsByMesh, terrainMesh, groundMesh, probe);
		if (System.getProperty("skirtstats") != null) {
			System.out.println("  backing walls: " + SKIRTS[0] + " emitted, " + SKIRTS[1]
					+ " skipped as donor-side; aprons " + SKIRTS[2]);
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
				current = compositeMesh(m, g, mi, quads, mi == edgeMesh ? rectsEdge : rects,
						overheadY, light, true);
				continue;
			}
			if (quads == null || quads.isEmpty()) {
				byte[] vtx = new byte[g.stride];
				System.arraycopy(m.raw, g.vtxAbs, vtx, 0, g.stride);
				current = m.setMeshGeometry(mi, vtx, new int[]{0, 0, 0});
				continue;
			}
			// Edge strips AND cliffs author their UVs directly; ground meshes get
			// world-projected UVs scaled to the texture.
			//
			// A cliff has to, because its texture is not a tiling ground sheet -
			// it is a cross-section. Measured on d112r0103_gake2: v 0.00-0.125 is
			// the grassy clifftop, 0.125-0.875 is rock, 0.875-1.0 is grass again
			// where it meets the ground. World-projected UVs put the wide
			// shoulder squarely in that top grass band, so cliffs rendered green
			// with a brown fringe, and two faces of one corner disagreed because
			// they covered different world distances.
			//EVERY quad this builder emits now carries a properly scaled UV -
			//ground at one repeat per 72 units, cliffs per 36 - so none of them
			//want the donor-measured scale on top. That scale is what flattened
			//the floor: measureUvScale read the donor mesh, clampScale let the
			//result fall as low as 1/720, and the entire floor ended up sampling
			//a 0.02-by-0.04 patch of its texture, i.e. one flat colour.
			MapModelObj.ObjMesh om = meshFromQuads(m, g, quads, true);
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
			float minY = Math.min(pos[a][1], Math.min(pos[b][1], pos[c][1]));
			float[][] xz = {{pos[a][0], pos[a][2]}, {pos[b][0], pos[b][2]}, {pos[c][0], pos[c][2]}};
			float area2 = Math.abs((xz[1][0] - xz[0][0]) * (xz[2][1] - xz[0][1])
					- (xz[2][0] - xz[0][0]) * (xz[1][1] - xz[0][1]));

			//An upright surface standing over ground that is being replaced is a
			//thing ON that ground - a tree, a bush, a fence - not part of it, and
			//it goes with the ground it was standing on. Judged by the shape of
			//the triangle rather than by its material: a material name cannot
			//tell scenery from terrain, and a texture being opaque does not stop
			//it being a tree, which is how vegetation kept surviving in the
			//middle of freshly painted sand.
			//
			//Only when the footprint's CENTRE is inside the paint. Cliff faces
			//along the boundary stand exactly on tile edges and merely touch it;
			//those are still cut along their run below, so the edge of the
			//painted area keeps its walls.
			if (clippable && area2 < 1.0f && centreInRegion(xz, rects)) {
				anyCut = true;
				continue;
			}
			//What survives above the paint is decided by SHAPE, because that is
			//what actually separates the two cases.
			//
			//An upper floor, a bridge deck, a roof: flat, facing up, with no
			//vertical extent of its own. Painting the ground under it must not
			//delete it - that is what the overhead rule is for.
			//
			//A tree, a bush, a lamp post: tall, and not lying flat. It is
			//standing ON the ground being replaced, so it goes with it.
			//
			//Measured on the corpus, the two do not overlap: the decoration
			//atlas on Route 102 is 3% flat-facing-up and 91% tall, while the
			//upper floor of a cave interior is 90% flat-facing-up and 0% tall.
			//Height alone could not tell them apart, which is why canopies used
			//to float over freshly painted sand - they simply started above the
			//threshold and so counted as bridges.
			if (!clippable || (minY > overheadY
					&& (isSurface(pos, a, b, c) || !centreInRegion(xz, rects)))) {
				keptOrig.add(a);
				keptOrig.add(b);
				keptOrig.add(c);
				continue;
			}
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

	/** [emitted, rejected-as-flat] ramp skirts, for diagnosis. */
	static final int[] SKIRTS = new int[3];

	/**
	 * Tiles whose cliffs are supplied by STAMPED RETAIL TERRAIN rather than
	 * generated here. Null means generate everything, as before.
	 *
	 * <p>Set by the terrain-kit composer just before a build and cleared after.
	 * A piece from the retail game is only ever placed where the elevation grid
	 * already calls for exactly its shape, so where one lands the generated
	 * cliff would be a second, worse wall in the same place - hence this mask
	 * rather than letting both run.
	 */
	public static boolean[][] terrainCovered;

	/** Ground texture repeats: one per 72 world units, i.e. per four tiles. */
	static final float FLOOR_UV = 1f / 72f;

	/** How far the water surface sits below the ground it runs through. */
	static final float WATER_SINK = 7f;

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

	/**
	 * Ensures a vertical quad's triangles wind so its face points OUT along
	 * {@code (nx, nz)}, not into the terrain it walls off.
	 *
	 * <p>Unlike {@link #fixWindingUp} this carries the per-corner AO across the
	 * swap as well. That helper is only used on quads whose AO is uniform, so
	 * it can leave shading alone; a cliff is deliberately darker toward its
	 * base, and moving the positions without the shading would light the wall
	 * upside down - a fault no geometry check would catch, because every
	 * triangle would be facing the right way.
	 */
	/**
	 * Winds a quad so its face points along an arbitrary 3D direction, carrying
	 * UVs, normals and AO with the swap.
	 *
	 * <p>{@link #fixWindingOut} only compares the horizontal part, which is
	 * right for a vertical wall and wrong for a sloped one: a shallow face's
	 * normal is nearly all +Y, so the horizontal comparison decides on noise.
	 */
	private static void fixWindingTowards(Quad q, float wx, float wy, float wz) {
		float[] a = q.pos[0], b = q.pos[2], c = q.pos[1];
		float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
		float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
		float gx = uy * vz - uz * vy;
		float gy = uz * vx - ux * vz;
		float gz = ux * vy - uy * vx;
		if (gx * wx + gy * wy + gz * wz < 0) {
			float[] tp = q.pos[1]; q.pos[1] = q.pos[2]; q.pos[2] = tp;
			float[] tu = q.uv[1]; q.uv[1] = q.uv[2]; q.uv[2] = tu;
			float[] tn = q.nrm[1]; q.nrm[1] = q.nrm[2]; q.nrm[2] = tn;
			float ta = q.ao[1]; q.ao[1] = q.ao[2]; q.ao[2] = ta;
		}
	}

	private static void fixWindingOut(Quad q, float nx, float nz) {
		float[] a = q.pos[0], b = q.pos[2], c = q.pos[1];
		float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
		float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
		float gx = uy * vz - uz * vy; // x of cross(u,v)
		float gz = ux * vy - uy * vx; // z of cross(u,v)
		if (gx * nx + gz * nz < 0) {
			float[] tp = q.pos[1]; q.pos[1] = q.pos[2]; q.pos[2] = tp;
			float[] tu = q.uv[1]; q.uv[1] = q.uv[2]; q.uv[2] = tu;
			float[] tn = q.nrm[1]; q.nrm[1] = q.nrm[2]; q.nrm[2] = tn;
			float ta = q.ao[1]; q.ao[1] = q.ao[2]; q.ao[2] = ta;
		}
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
	/**
	 * A flat vertical wall spanning one tile-boundary segment, from the top of
	 * the step to a little below its foot, wound to face downhill.
	 *
	 * <p>This is structure, not decoration: it stands exactly on the tile
	 * boundary while the visible cliff leans out past it, so it is hidden
	 * everywhere the visible cliff is intact and shows only where that cliff
	 * has a gap - which is precisely where something needs to be.
	 */
	private static Quad backingWall(CliffEdge e) {
		//Sink it below the step's foot, but not far. Sixteen units closed a
		//slot at the rim, and also hung a tall slab well under the floor in
		//every painted area - which is real geometry, shows up as something
		//left standing, and drags the floor reference down with it. Five is
		//enough to cover the foot.
		final float BURY = 5f;
		//Run each end a little past its corner. Two backing walls that merely
		//abut still crack: the meeting edges are computed independently and
		//land a rounding error apart, which at the map rim showed as a needle
		//of sky three pixels wide and thirty tall. Overlapping costs nothing -
		//both walls are hidden behind the visible cliff anyway - and a crack
		//cannot open between surfaces that overlap.
		final float OVER = 1.5f;
		float ex = e.bx - e.ax, ez = e.bz - e.az;
		float el = (float) Math.hypot(ex, ez);
		if (el > 1e-4f) {
			ex = ex / el * OVER;
			ez = ez / el * OVER;
		} else {
			ex = 0f;
			ez = 0f;
		}
		//Stand it just OUTSIDE the tile boundary rather than exactly on it. The
		//visible face leans outward from that boundary, so this is where the
		//backing actually belongs - behind the face, not in the plane of the
		//floor edge where it both z-fights the floor and counts as something
		//tall standing inside the painted area.
		final float BEHIND = 0.75f;
		float ax = e.ax - ex + e.nx * BEHIND, az = e.az - ez + e.nz * BEHIND;
		float bx = e.bx + ex + e.nx * BEHIND, bz = e.bz + ez + e.nz * BEHIND;
		Quad q = new Quad();
		q.pos[0] = new float[]{ax, e.yTop, az};
		q.pos[1] = new float[]{bx, e.yTop, bz};
		q.pos[2] = new float[]{ax, e.yBot - BURY, az};
		q.pos[3] = new float[]{bx, e.yBot - BURY, bz};
		//tile the rock down the face at the same rate as the visible cliff, so
		//a glimpse of it through a seam does not read as a different material
		float len = (float) Math.hypot(e.bx - e.ax, e.bz - e.az);
		float vSpan = (e.yTop - e.yBot + BURY) / 56f;
		q.uv[0] = new float[]{0f, 0.26f};
		q.uv[1] = new float[]{len / 56f, 0.26f};
		q.uv[2] = new float[]{0f, 0.26f + vSpan};
		q.uv[3] = new float[]{len / 56f, 0.26f + vSpan};
		for (int c = 0; c < 4; c++) {
			q.nrm[c] = new float[]{e.nx, 0f, e.nz};
			q.ao[c] = c < 2 ? 0.82f : 0.62f;
		}
		fixWindingTowards(q, e.nx, 0f, e.nz);
		return q;
	}

	/**
	 * One blade of the two-quad cross that plugs a corner where walls of
	 * different depth meet. Four are emitted per corner - two axes, each faced
	 * both ways - because a quad is one-sided and which side the slot opens on
	 * depends entirely on where the camera stands.
	 */
	private static Quad cornerPost(float x, float z, float yTop, float yBot,
			boolean alongX, float face) {
		final float HALF = 5f, BURY = 5f;
		float dx = alongX ? HALF : 0f, dz = alongX ? 0f : HALF;
		Quad q = new Quad();
		q.pos[0] = new float[]{x - dx, yTop, z - dz};
		q.pos[1] = new float[]{x + dx, yTop, z + dz};
		q.pos[2] = new float[]{x - dx, yBot - BURY, z - dz};
		q.pos[3] = new float[]{x + dx, yBot - BURY, z + dz};
		float vSpan = (yTop - yBot + BURY) / 56f;
		q.uv[0] = new float[]{0f, 0.26f};
		q.uv[1] = new float[]{2f * HALF / 56f, 0.26f};
		q.uv[2] = new float[]{0f, 0.26f + vSpan};
		q.uv[3] = new float[]{2f * HALF / 56f, 0.26f + vSpan};
		for (int c = 0; c < 4; c++) {
			q.nrm[c] = new float[]{alongX ? 0f : 1f, 0f, alongX ? 1f : 0f};
			q.ao[c] = c < 2 ? 0.80f : 0.60f;
		}
		fixWindingTowards(q, (alongX ? 0f : 1f) * face, 0f, (alongX ? 1f : 0f) * face);
		return q;
	}

	/**
	 * The triangular sliver between a ramp's sloping floor and the flat ground
	 * beside it. Top edge level with that ground, bottom edge following the
	 * ramp down, so it is zero-height at the top of the slope and a full step
	 * at the bottom.
	 */
	private static Quad rampSkirt(int tx, int ty, int side, int rd, float yHi, float yLo) {
		float x0 = tx * TILE + ORIGIN, x1 = x0 + TILE;
		float z0 = ty * TILE + ORIGIN, z1 = z0 + TILE;
		//the two corners of this edge, as (xIndex, zIndex) in {0,1}
		int[][] c = side == 0 ? new int[][]{{1, 0}, {1, 1}}
				: side == 1 ? new int[][]{{0, 0}, {0, 1}}
				: side == 2 ? new int[][]{{0, 1}, {1, 1}}
				: new int[][]{{0, 0}, {1, 0}};
		float[] cx = new float[2], cz = new float[2], cy = new float[2];
		for (int i = 0; i < 2; i++) {
			cx[i] = c[i][0] == 0 ? x0 : x1;
			cz[i] = c[i][1] == 0 ? z0 : z1;
			//floorQuad drops exactly the two corners on the descent edge
			boolean low = rd == 0 ? c[i][0] == 1 : rd == 1 ? c[i][0] == 0
					: rd == 2 ? c[i][1] == 1 : c[i][1] == 0;
			cy[i] = low ? yLo : yHi;
		}
		if (Math.abs(cy[0] - cy[1]) < 0.01f) {
			return null;   //flat along this edge: nothing to skirt
		}
		Quad q = new Quad();
		q.pos[0] = new float[]{cx[0], yHi, cz[0]};
		q.pos[1] = new float[]{cx[1], yHi, cz[1]};
		q.pos[2] = new float[]{cx[0], cy[0], cz[0]};
		q.pos[3] = new float[]{cx[1], cy[1], cz[1]};
		float len = (float) Math.hypot(cx[1] - cx[0], cz[1] - cz[0]);
		float vSpan = Math.abs(cy[0] - cy[1]) / 56f;
		q.uv[0] = new float[]{0f, 0.28f};
		q.uv[1] = new float[]{len / 56f, 0.28f};
		q.uv[2] = new float[]{0f, 0.28f + vSpan};
		q.uv[3] = new float[]{len / 56f, 0.28f + vSpan};
		float nx = side == 0 ? 1f : side == 1 ? -1f : 0f;
		float nz = side == 2 ? 1f : side == 3 ? -1f : 0f;
		for (int i = 0; i < 4; i++) {
			q.nrm[i] = new float[]{nx, 0f, nz};
			q.ao[i] = i < 2 ? 0.85f : 0.65f;
		}
		fixWindingTowards(q, nx, 0f, nz);
		return q;
	}

	/**
	 * The wall hanging beneath a ramp's sloping floor on one of its side edges.
	 *
	 * <p>{@link #rampSkirt} closes the wedge ABOVE the slope, between it and
	 * ground level with the ramp's top. This closes what is below it: a ramp
	 * cut into a hillside has open air under its own floor, and from any low
	 * angle that reads as sky beneath the path.
	 */
	private static Quad rampApron(int tx, int ty, int side, int rd, float yHi, float yLo) {
		float x0 = tx * TILE + ORIGIN, x1 = x0 + TILE;
		float z0 = ty * TILE + ORIGIN, z1 = z0 + TILE;
		int[][] c = side == 0 ? new int[][]{{1, 0}, {1, 1}}
				: side == 1 ? new int[][]{{0, 0}, {0, 1}}
				: side == 2 ? new int[][]{{0, 1}, {1, 1}}
				: new int[][]{{0, 0}, {1, 0}};
		float[] cx = new float[2], cz = new float[2], cy = new float[2];
		for (int i = 0; i < 2; i++) {
			cx[i] = c[i][0] == 0 ? x0 : x1;
			cz[i] = c[i][1] == 0 ? z0 : z1;
			boolean low = rd == 0 ? c[i][0] == 1 : rd == 1 ? c[i][0] == 0
					: rd == 2 ? c[i][1] == 1 : c[i][1] == 0;
			cy[i] = low ? yLo : yHi;
		}
		//hang well below: it is hidden by the ground beside it wherever that
		//ground is higher, and it is the only thing there wherever it is not
		//just under the slope, not two whole levels under it
		float foot = Math.min(cy[0], cy[1]) - STEP * 0.5f;
		Quad q = new Quad();
		q.pos[0] = new float[]{cx[0], cy[0], cz[0]};
		q.pos[1] = new float[]{cx[1], cy[1], cz[1]};
		q.pos[2] = new float[]{cx[0], foot, cz[0]};
		q.pos[3] = new float[]{cx[1], foot, cz[1]};
		float len = (float) Math.hypot(cx[1] - cx[0], cz[1] - cz[0]);
		q.uv[0] = new float[]{0f, 0.30f};
		q.uv[1] = new float[]{len / 56f, 0.30f};
		q.uv[2] = new float[]{0f, 0.30f + (Math.max(cy[0], cy[1]) - foot) / 56f};
		q.uv[3] = new float[]{len / 56f, 0.30f + (Math.max(cy[0], cy[1]) - foot) / 56f};
		float nx = side == 0 ? 1f : side == 1 ? -1f : 0f;
		float nz = side == 2 ? 1f : side == 3 ? -1f : 0f;
		for (int i = 0; i < 4; i++) {
			q.nrm[i] = new float[]{nx, 0f, nz};
			q.ao[i] = i < 2 ? 0.80f : 0.58f;
		}
		fixWindingTowards(q, nx, 0f, nz);
		return q;
	}

	/**
	 * Vanilla's second lava surface: an ADDITIVE plate floating two units over
	 * the molten one.
	 *
	 * <p>Retail lava is three meshes - an opaque base plate, this churn plate,
	 * and a rim ribbon - and the offset between the first two measured 2.00
	 * units in every map checked, to two decimal places. The churn material is
	 * additive with depth-write off, and that is the entire reason retail lava
	 * glows: drawn as a single ordinary plate, as this builder did, lava is a
	 * flat orange rectangle no matter what texture or colour it is given.
	 */
	static void addChurnLayer(TilePalette[][] grid, int[][] height, float[][] yTop,
			boolean[][] touched, Map<Integer, List<Quad>> quadsByMesh, BchMapModel probe) {
		TerrainCatalog.Donor churn = TerrainCatalog.churnDonor();
		if (churn == null) {
			return;
		}
		int mesh = -1;
		for (int i = 0; i < probe.meshCount; i++) {
			if (churn.injectName.equals(probe.getMaterialName(probe.getMeshMaterialIndex(i)))) {
				mesh = i;
				break;
			}
		}
		if (mesh < 0) {
			return;   //the material never imported; better nothing than a wrong layer
		}
		final float CHURN_LIFT = 2.00f;
		List<Quad> out = quadsByMesh.computeIfAbsent(mesh, k -> new ArrayList<>());
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				if (touched != null && !touched[ty][tx]) {
					continue;
				}
				if (!isWet(grid[ty][tx])) {
					continue;
				}
				float y = yTop[ty][tx] - WATER_SINK + CHURN_LIFT;
				float x0 = tx * TILE + ORIGIN, x1 = x0 + TILE;
				float z0 = ty * TILE + ORIGIN, z1 = z0 + TILE;
				out.add(flatBand(x0, z0, x1, z0, x0, z1, x1, z1, y, 1f, 1f));
			}
		}
	}

	/** A water tile as far as the shoreline is concerned. */
	private static boolean isWet(TilePalette t) {
		return t == TilePalette.WATER || t == TilePalette.WATERFALL;
	}

	/** One tile-boundary segment where water meets land at the same height. */
	static final class ShoreEdge {

		float ax, az, bx, bz;   // the segment, head to tail around the water
		float nx, nz;           // unit normal pointing from the land INTO the water
		float y;                // both surfaces are at this height
		int h;                  // that height as a level, for ambient occlusion
		int landMesh;

		String startKey() {
			return CliffEdge.key(ax, az);
		}

		String endKey() {
			return CliffEdge.key(bx, bz);
		}
	}

	/**
	 * Round off the waterline.
	 *
	 * <p>Water and bank are both squares of tile geometry sitting at the same
	 * height, so the river's edge steps a whole tile at a time and reads as a
	 * row of blocks - the thing that makes it look like Minecraft rather than a
	 * river. The fix is the one that worked on the cliffs: take the boundary,
	 * round it, and bridge the difference back to the tile grid instead of
	 * moving the tiles themselves.
	 *
	 * <p>Rounding moves the line both ways. Where it swings out over the water
	 * a band of BANK is laid over the water; where it swings back inland a band
	 * of WATER is laid over the bank. Both bands are flat, sit a hair above the
	 * shared surface, and carry the same world-position texture mapping as the
	 * tiles they cover, so they read as more of the same material rather than
	 * as a decal. Nothing is deleted, which is what makes this safe: the square
	 * tiles are all still there underneath.
	 */
	static void addShoreBands(TilePalette[][] grid, int[][] height, float[][] yTop,
			boolean[][] touched, Map<Integer, List<Quad>> quadsByMesh,
			Map<TilePalette, Integer> terrainMesh, int groundMesh, BchMapModel probe) {
		int waterMesh = terrainMesh.computeIfAbsent(TilePalette.WATER,
				tp -> resolveMesh(probe, tp, groundMesh));
		if (waterMesh < 0) {
			return;
		}
		List<ShoreEdge> edges = new ArrayList<>();
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				if (touched != null && !touched[ty][tx]) {
					continue;
				}
				if (!isWet(grid[ty][tx])) {
					continue;
				}
				float x0 = tx * TILE + ORIGIN, x1 = x0 + TILE;
				float z0 = ty * TILE + ORIGIN, z1 = z0 + TILE;
				for (int dir = 0; dir < 4; dir++) {
					int nx = tx + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
					int ny = ty + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
					if (nx < 0 || ny < 0 || nx >= DIM || ny >= DIM) {
						continue;
					}
					if (touched != null && !touched[ny][nx]) {
						continue;
					}
					TilePalette nt = grid[ny][nx];
					if (nt == null || nt == TilePalette.VOID || isWet(nt)) {
						continue;
					}
					//A bank with a step in it is a cliff, and the cliff builder
					//already rounds that. Only the flat waterline is ours.
					if (height[ny][nx] != height[ty][tx]) {
						continue;
					}
					Integer lm = terrainMesh.get(nt);
					if (lm == null || lm < 0) {
						continue;
					}
					ShoreEdge e = new ShoreEdge();
					e.y = yTop[ty][tx];
					e.h = height[ty][tx];
					e.landMesh = lm;
					//head to tail clockwise round the water, so chains link up
					switch (dir) {
						case 0:
							e.ax = x1; e.az = z0; e.bx = x1; e.bz = z1;
							e.nx = -1f; e.nz = 0f;
							break;
						case 2:
							e.ax = x1; e.az = z1; e.bx = x0; e.bz = z1;
							e.nx = 0f; e.nz = -1f;
							break;
						case 1:
							e.ax = x0; e.az = z1; e.bx = x0; e.bz = z0;
							e.nx = 1f; e.nz = 0f;
							break;
						default:
							e.ax = x0; e.az = z0; e.bx = x1; e.bz = z0;
							e.nx = 0f; e.nz = 1f;
							break;
					}
					edges.add(e);
				}
			}
		}
		if (edges.isEmpty()) {
			return;
		}

		Map<String, ShoreEdge> byStart = new LinkedHashMap<>();
		for (ShoreEdge e : edges) {
			byStart.putIfAbsent(e.startKey(), e);
		}
		Set<ShoreEdge> used = new java.util.LinkedHashSet<>();
		for (ShoreEdge seed : edges) {
			if (used.contains(seed)) {
				continue;
			}
			List<ShoreEdge> chain = new ArrayList<>();
			ShoreEdge cur = seed;
			while (cur != null && !used.contains(cur)) {
				used.add(cur);
				chain.add(cur);
				cur = byStart.get(cur.endKey());
			}
			emitShoreChain(chain, grid, height, quadsByMesh, waterMesh);
		}
	}

	private static void emitShoreChain(List<ShoreEdge> chain, TilePalette[][] grid, int[][] height,
			Map<Integer, List<Quad>> quadsByMesh, int waterMesh) {
		int n = chain.size();
		if (n < 2) {
			return;   //a single tile edge has no corner to round
		}
		boolean loop = chain.get(0).startKey().equals(chain.get(n - 1).endKey());
		int vc = n + 1;
		float[] px = new float[vc], pz = new float[vc];
		for (int i = 0; i < n; i++) {
			px[i] = chain.get(i).ax;
			pz[i] = chain.get(i).az;
		}
		px[n] = chain.get(n - 1).bx;
		pz[n] = chain.get(n - 1).bz;

		float[] sx = px.clone(), sz = pz.clone();
		for (int pass = 0; pass < 3; pass++) {
			float[] ax = sx.clone(), az = sz.clone();
			for (int i = 0; i < vc; i++) {
				if (!loop && (i == 0 || i == vc - 1)) {
					continue;   //ends stay put so neighbouring chains still meet
				}
				int prev = i == 0 ? vc - 2 : i - 1;
				int next = i == vc - 1 ? 1 : i + 1;
				ax[i] = sx[i] * 0.5f + sx[prev] * 0.25f + sx[next] * 0.25f;
				az[i] = sz[i] * 0.5f + sz[prev] * 0.25f + sz[next] * 0.25f;
			}
			sx = ax;
			sz = az;
		}
		//Never wander more than a third of a tile from the grid. A river two
		//tiles wide would otherwise have both banks pulled toward each other
		//until they met, and the same cap keeps the bands narrow enough that
		//they cannot reach past the tile they are covering.
		final float MAX_PULL = TILE * 0.34f;
		for (int i = 0; i < vc; i++) {
			float dx = sx[i] - px[i], dz = sz[i] - pz[i];
			float d = (float) Math.hypot(dx, dz);
			if (d > MAX_PULL) {
				sx[i] = px[i] + dx * (MAX_PULL / d);
				sz[i] = pz[i] + dz * (MAX_PULL / d);
			}
		}

		for (int i = 0; i < n; i++) {
			int j = i + 1;
			ShoreEdge e = chain.get(i);
			//how far each end of this segment moved, measured along the
			//segment's own waterward normal: positive is out over the water
			float di = (sx[i] - px[i]) * e.nx + (sz[i] - pz[i]) * e.nz;
			float dj = (sx[j] - px[j]) * e.nx + (sz[j] - pz[j]) * e.nz;
			float aoI = cornerAO(grid, height, Math.round((px[i] - ORIGIN) / TILE),
					Math.round((pz[i] - ORIGIN) / TILE), e.h);
			float aoJ = cornerAO(grid, height, Math.round((px[j] - ORIGIN) / TILE),
					Math.round((pz[j] - ORIGIN) / TILE), e.h);

			//The bank: a slope from the ground at the tile boundary down to the
			//sunk water. It replaces the pair of flat bands that used to lie
			//here, which only worked while water and grass were at the same
			//height - now the water is seven units down and something has to
			//carry the eye between them.
			//How far out each end reaches is what the rounding buys: where the
			//smoothed line swung out over the water the bank runs long and
			//shallow, where it swung inland the bank is short and steep. The
			//waterline is that varying edge, so it curves the way the smoothed
			//outline did without any tile ever being cut.
			float runI = clamp(BANK_RUN + di, 2.5f, TILE * 0.8f);
			float runJ = clamp(BANK_RUN + dj, 2.5f, TILE * 0.8f);
			quadsByMesh.computeIfAbsent(e.landMesh, k -> new ArrayList<>()).add(
					bankQuad(px[i], pz[i], px[j], pz[j],
							px[i] + e.nx * runI, pz[i] + e.nz * runI,
							px[j] + e.nx * runJ, pz[j] + e.nz * runJ,
							e.y + 0.05f, e.y - WATER_SINK, aoI, aoJ));
		}
	}

	static float clamp(float v, float lo, float hi) {
		return v < lo ? lo : v > hi ? hi : v;
	}

	/** How far a bank reaches out over the water before meeting its surface. */
	static final float BANK_RUN = 7f;

	/**
	 * A sloped bank: the top edge on the tile boundary at ground level, the
	 * bottom edge out over the water at the water's surface. Textured by world
	 * position like the ground it continues, so the bank is the same grass or
	 * sand running down to the water rather than a band stuck on afterwards.
	 */
	private static Quad bankQuad(float ax, float az, float bx, float bz,
			float cx, float cz, float dx, float dz,
			float yTop, float yBot, float aoAB, float aoCD) {
		Quad q = new Quad();
		float[][] p = {{ax, yTop, az}, {bx, yTop, bz}, {cx, yBot, cz}, {dx, yBot, dz}};
		float[] ao = {aoAB, aoCD, aoAB * 0.85f, aoCD * 0.85f};
		for (int c = 0; c < 4; c++) {
			q.pos[c] = p[c];
			q.uv[c] = new float[]{p[c][0] * FLOOR_UV, p[c][2] * FLOOR_UV};
			q.nrm[c] = new float[]{0f, 1f, 0f};
			q.ao[c] = ao[c];
		}
		//faces up and outward: the player looks down onto a bank, never up at it
		fixWindingTowards(q, (cx - ax) * 0.4f, 1f, (cz - az) * 0.4f);
		return q;
	}

	/**
	 * A flat, upward-facing quad textured by world position, exactly as
	 * {@link #floorQuad} textures the tiles - so a band laid along the
	 * waterline continues the grass or the water it sits on instead of
	 * reading as a stripe pasted over it.
	 */
	private static Quad flatBand(float ax, float az, float bx, float bz,
			float cx, float cz, float dx, float dz, float y, float aoAB, float aoCD) {
		Quad q = new Quad();
		float[][] p = {{ax, y, az}, {bx, y, bz}, {cx, y, cz}, {dx, y, dz}};
		float[] ao = {aoAB, aoCD, aoAB, aoCD};
		for (int c = 0; c < 4; c++) {
			q.pos[c] = p[c];
			q.uv[c] = new float[]{p[c][0] * FLOOR_UV, p[c][2] * FLOOR_UV};
			q.nrm[c] = new float[]{0f, 1f, 0f};
			q.ao[c] = ao[c];
		}
		fixWindingTowards(q, 0f, 1f, 0f);
		return q;
	}

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
			//Ground tiles once per 72 world units - four tiles - in BOTH axes.
			//Measured as dU/dXZ = dV/dXZ = 1/72.00 on every floor texture in
			//Fiery Path and the Cave of Origin, which is half the texel density
			//of the cliff. Feeding raw world coordinates in meant one repeat per
			//UNIT, so the floor was a smear of noise rather than readable stone.
			q.uv[c] = new float[]{p[c][0] * FLOOR_UV, p[c][2] * FLOOR_UV};
			q.nrm[c] = new float[]{0f, 1f, 0f};
			q.ao[c] = cornerAO(grid, height, corner[c][0], corner[c][1], h);
		}
		return q;
	}

	/**
	 * The cliff face on tile edge {@code dir}, dropping from {@code yt} to
	 * {@code yb}, as a SLOPE rather than a wall.
	 *
	 * <p>This used to emit one vertical quad: top edge and bottom edge at the
	 * same horizontal position, zero run, a 90-degree drop. Nothing in the
	 * retail game looks like that. Measured over real cliff meshes - the face
	 * angles of {@code d112r0103_gake1}, {@code d112r0103_gake2},
	 * {@code gake_basic} and {@code chip_rock_b} - a Pokemon cliff averages
	 * 42-46 degrees, with mean vertical rise almost exactly equal to mean
	 * horizontal run, and a large share of its area (44%, 62%) sitting at only
	 * 20-30 degrees. That shallow band is the shoulder: the ground rolls over
	 * the top before the face steepens.
	 *
	 * <p>So the face is built in two pieces. A shoulder taking the first 30% of
	 * the drop at a shallow angle, and a steeper lower face taking the rest;
	 * together they run out about one unit horizontally per unit of fall, which
	 * is what the corpus does. The face leans OUT over the lower tile, as the
	 * retail geometry does - the ground below tucks under it.
	 */
	/**
	 * One tile-edge segment where the ground steps down, before it is joined to
	 * its neighbours. Endpoints run head-to-tail around a raised area (east,
	 * south, west, north each start where the previous one ended), so chaining
	 * is just "find the edge whose start is this edge's end".
	 */
	static final class CliffEdge {

		float ax, az, bx, bz;   // the tile-boundary segment, in world units
		float nx, nz;           // outward, away from the high ground
		float yTop, yBot;
		/** The ground this face would lean over is walkable, so it must not. */
		boolean tight;
		/** The low side is untouched retail terrain, which backs itself. */
		boolean donorSide;

		String startKey() {
			return key(ax, az);
		}

		String endKey() {
			return key(bx, bz);
		}

		static String key(float x, float z) {
			return Math.round(x * 4f) + ":" + Math.round(z * 4f);
		}

		/** Same step, so the strip can run continuously through it. */
		String levelKey() {
			return Math.round(yTop * 4f) + "/" + Math.round(yBot * 4f) + (tight ? "/t" : "");
		}
	}

	static CliffEdge cliffEdge(int tx, int ty, int dir, float yb, float yt) {
		float x0 = tx * TILE + ORIGIN, x1 = x0 + TILE;
		float z0 = ty * TILE + ORIGIN, z1 = z0 + TILE;
		CliffEdge e = new CliffEdge();
		switch (dir) {
			case 0: e.ax = x1; e.az = z0; e.bx = x1; e.bz = z1; e.nx = 1; e.nz = 0; break;
			case 1: e.ax = x0; e.az = z1; e.bx = x0; e.bz = z0; e.nx = -1; e.nz = 0; break;
			case 2: e.ax = x1; e.az = z1; e.bx = x0; e.bz = z1; e.nx = 0; e.nz = 1; break;
			default: e.ax = x0; e.az = z0; e.bx = x1; e.bz = z0; e.nx = 0; e.nz = -1; break;
		}
		e.yTop = yt;
		e.yBot = yb;
		return e;
	}

	/**
	 * Turns loose per-tile cliff edges into CONTINUOUS strips that follow the
	 * contour, with mitred corners.
	 *
	 * <p>Emitting a quad per tile edge is what made painted elevation look like
	 * stacked blocks. Each tile produced its own free-standing parallelogram, so
	 * wherever the contour stepped, two of them met at an unmitred right angle
	 * and the whole slope read as a staircase of separate slabs. Tilting them
	 * (the 43-degree slope) only tilted each slab; recolouring them only
	 * recoloured each slab. Retail elevation is quantised to tiles exactly like
	 * this, and its cliffs still read as hillsides, because the cliff is one
	 * strip running along the boundary rather than a row of unrelated pieces.
	 *
	 * <p>So: chain the edges head-to-tail, give every chain vertex a single
	 * outward direction (the average of the segments meeting there, lengthened
	 * so the mitre keeps its width), and sweep the shoulder and face bands along
	 * the chain. Neighbouring segments then share their corner vertices and the
	 * seams disappear.
	 */
	static void emitCliffStrips(List<CliffEdge> edges, Map<Integer, List<Quad>> quadsByMesh,
			int cliffMesh, int lipMesh) {
		//group by the step they belong to, so a 1-level and a 2-level drop that
		//touch do not get welded into one strip
		Map<String, List<CliffEdge>> byLevel = new LinkedHashMap<>();
		for (CliffEdge e : edges) {
			byLevel.computeIfAbsent(e.levelKey(), k -> new ArrayList<>()).add(e);
		}

		//The outward direction at a corner has to be agreed on by EVERY wall
		//meeting there, not just by the ones inside a single strip. Strips are
		//split by the step they belong to, so where a one-level drop meets a
		//two-level drop the corner is shared by two different strips - and each
		//used to mitre it alone, sending its foot straight out along its own
		//normal. At a right angle those two directions are perpendicular, so
		//the feet ended up a whole face-width apart and left a wedge of sky
		//between them, right through the map.
		//Summing the normals of every edge that touches a point, across all
		//strips, gives one shared bisector, and the walls meet on it.
		//Slot 0,1 accumulate the shared bisector. Slot 2 records the DEEPEST
		//drop meeting at the point, because agreeing on the direction is only
		//half of it: a wall's foot reaches outward in proportion to its own
		//fall, so where a one-step wall meets a two-step wall their feet stop
		//at different distances along the same line and leave a slit. The
		//shallower wall is stretched out to meet the deeper one below.
		Map<String, float[]> corner = new LinkedHashMap<>();
		for (CliffEdge e : edges) {
			for (String k : new String[]{e.startKey(), e.endKey()}) {
				float[] acc = corner.computeIfAbsent(k, x -> new float[3]);
				acc[0] += e.nx;
				acc[1] += e.nz;
				acc[2] = Math.max(acc[2], e.yTop - e.yBot);
			}
		}

		//Where each open strip's END PROFILE lies, in world coordinates, keyed
		//by the corner it stands on. Two strips meeting at a corner have the
		//same foot and the same bisector but tops 36 units apart in height, so
		//the region between their two profiles is a lens of nothing - and that
		//lens is what has been showing as sky. Recording the profiles lets it
		//be filled directly, without moving a single vertex of either wall.
		Map<String, List<float[][]>> caps = new LinkedHashMap<>();

		List<Quad> out = quadsByMesh.computeIfAbsent(cliffMesh, k -> new ArrayList<>());

		//ONE pool of edges, chained purely by where they touch - not split by the
		//step they belong to. Splitting was the root of the whole class of
		//defect: a cliff whose height changes along its length was cut into a
		//separate strip per step, each smoothed, mitred and capped in isolation,
		//and the seams between them are what produced every sliver, wedge and -
		//where a ramp corridor multiplied the count - the spray of disconnected
		//shards lying across the route. A wall that changes height is still one
		//wall, so it is built as one, and the height is carried per vertex.
		Map<String, List<CliffEdge>> byStart = new LinkedHashMap<>();
		for (CliffEdge e : edges) {
			byStart.computeIfAbsent(e.startKey(), k -> new ArrayList<>()).add(e);
		}
		Set<CliffEdge> used = new java.util.LinkedHashSet<>();
		for (CliffEdge seed : edges) {
			if (used.contains(seed)) {
				continue;
			}
			List<CliffEdge> chain = new ArrayList<>();
			CliffEdge cur = seed;
			while (cur != null && !used.contains(cur)) {
				used.add(cur);
				chain.add(cur);
				//Several edges can start where this one ends - a T-junction, or
				//a corner where a terrace meets a step. Continue along the one
				//that turns least and changes height least, so a wall follows
				//its own contour instead of jumping onto a different feature.
				List<CliffEdge> cands = byStart.get(cur.endKey());
				CliffEdge best = null;
				float bestScore = -Float.MAX_VALUE;
				if (cands != null) {
					float dx = cur.bx - cur.ax, dz = cur.bz - cur.az;
					float dl = (float) Math.hypot(dx, dz);
					if (dl > 1e-4f) {
						dx /= dl;
						dz /= dl;
					}
					for (CliffEdge c : cands) {
						if (used.contains(c)) {
							continue;
						}
						float ex = c.bx - c.ax, ez = c.bz - c.az;
						float el = (float) Math.hypot(ex, ez);
						if (el > 1e-4f) {
							ex /= el;
							ez /= el;
						}
						float straight = dx * ex + dz * ez;
						//A strip may run THROUGH a change of step, but only a
						//modest one. Letting it join anything that happened to
						//start at the same point let a 72-to-36 wall continue
						//into a 36-to-0 wall at right angles, and averaging the
						//heights across that join stretched a long diagonal
						//blade between them. One step of change, and no
						//doubling back on itself.
						float stepChange = Math.abs((c.yTop - c.yBot) - (cur.yTop - cur.yBot));
						if (stepChange > STEP * 1.01f || straight < -0.1f) {
							continue;
						}
						//and the tops must line up: a wall cannot continue into
						//one that starts at a different height
						if (Math.abs(c.yTop - cur.yTop) > STEP * 1.01f) {
							continue;
						}
						float score = straight - stepChange / 72f * 1.5f;
						if (score > bestScore) {
							bestScore = score;
							best = c;
						}
					}
				}
				cur = best;
			}
			emitChain(chain, out, corner, caps,
					lipMesh < 0 ? null : quadsByMesh.computeIfAbsent(lipMesh, k -> new ArrayList<>()));
		}

		//Fill the lens between end profiles that share a corner. Sorted by top
		//height, each neighbouring pair bounds a planar sliver lying in the
		//corner's own bisector plane, so it is stitched straight onto the
		//profile points both walls already use - nothing moves, no vertex is
		//displaced, and there is no new seam to go wrong. Emitted with both
		//windings because the patch is edge-on to that plane and the corners of
		//a map face four different ways.
		for (Map.Entry<String, List<float[][]>> en : caps.entrySet()) {
			if (System.getProperty("nogusset") != null) {
				break;
			}
			List<float[][]> prof = en.getValue();
			if (prof.size() < 2) {
				continue;
			}
			prof.sort((a, b) -> Float.compare(b[0][1], a[0][1]));
			for (int i = 0; i + 1 < prof.size(); i++) {
				float[][] a = prof.get(i), b = prof.get(i + 1);
				//Only fill between profiles that genuinely sit against each
				//other. Where a corridor cuts through, several strips end on
				//one point with feet a tile or more apart, and stitching those
				//together spans the gap with a blade of rock lying across the
				//route rather than closing a seam.
				float apart = (float) Math.hypot(a[2][0] - b[2][0], a[2][2] - b[2][2]);
				if (apart > TILE * 0.9f) {
					continue;
				}
				for (int band = 0; band < 2; band++) {
					float[] at = a[band], bt = b[band], ab = a[band + 1], bb = b[band + 1];
					if (same(at, bt) && same(ab, bb)) {
						continue;   //identical profiles: there is no lens to fill
					}
					out.add(gusset(at, bt, ab, bb, false));
					out.add(gusset(at, bt, ab, bb, true));
				}
			}
		}
	}

	private static boolean same(float[] p, float[] q) {
		return Math.abs(p[0] - q[0]) < 0.05f && Math.abs(p[1] - q[1]) < 0.05f
				&& Math.abs(p[2] - q[2]) < 0.05f;
	}

	/**
	 * One patch across the gap between two walls' end profiles at a shared
	 * corner. Built only from points the walls themselves already use.
	 */
	private static Quad gusset(float[] tl, float[] tr, float[] bl, float[] br, boolean flip) {
		Quad q = new Quad();
		q.pos[0] = tl.clone();
		q.pos[1] = tr.clone();
		q.pos[2] = bl.clone();
		q.pos[3] = br.clone();
		//stay in the rock band of the cliff texture, so a glimpse of the patch
		//reads as more of the same wall
		float h = Math.max(Math.abs(tl[1] - bl[1]), 1f) / 56f;
		q.uv[0] = new float[]{0f, 0.30f};
		q.uv[1] = new float[]{0.18f, 0.30f};
		q.uv[2] = new float[]{0f, 0.30f + h};
		q.uv[3] = new float[]{0.18f, 0.30f + h};
		for (int c = 0; c < 4; c++) {
			q.nrm[c] = new float[]{0f, 1f, 0f};
			q.ao[c] = c < 2 ? 0.78f : 0.60f;
		}
		if (flip) {
			float[] t = q.pos[1];
			q.pos[1] = q.pos[2];
			q.pos[2] = t;
			float[] tu = q.uv[1];
			q.uv[1] = q.uv[2];
			q.uv[2] = tu;
		}
		return q;
	}

	/**
	 * How far out a wall's foot reaches for a given fall, matching the shoulder
	 * and face proportions used when the strip is built (and the same one-tile
	 * cap, so a tall cliff stands steeper rather than overshooting the terrace
	 * below it).
	 */
	private static float reach(float drop) {
		return Math.min(TILE, drop * 0.77f);
	}

	private static void emitChain(List<CliffEdge> chain, List<Quad> out,
			Map<String, float[]> corner, Map<String, List<float[][]>> caps, List<Quad> lip) {
		if (chain.isEmpty()) {
			return;
		}
		int n = chain.size();
		boolean loop = chain.get(0).startKey().equals(chain.get(n - 1).endKey());
		//vertices of the polyline: each edge's start, plus the last edge's end
		int vc = n + 1;
		float[] px = new float[vc], pz = new float[vc];
		for (int i = 0; i < n; i++) {
			px[i] = chain.get(i).ax;
			pz[i] = chain.get(i).az;
		}
		px[n] = chain.get(n - 1).bx;
		pz[n] = chain.get(n - 1).bz;

		//Round the outline. Elevation is quantised to tiles, so the contour
		//steps a tile at a time and a chain of right angles reads as a
		//staircase however well the faces are joined - which is the single
		//thing that has made these cliffs look built out of blocks.
		//One pass of corner cutting was all the top row could take, because the
		//top is where the floor above ends: move it and triangular holes open
		//straight through the map. That constraint is real, but it is not a
		//reason to leave the silhouette square - it only means the smoothed
		//outline has to be BRIDGED back to the tile boundary rather than left
		//floating. The collar emitted below does that, so the whole cliff can
		//now be rounded properly, top row included.
		final int SMOOTH_PASSES = 3;
		float[] smoothX = px.clone(), smoothZ = pz.clone();
		for (int pass = 0; pass < SMOOTH_PASSES; pass++) {
			float[] nx2 = smoothX.clone(), nz2 = smoothZ.clone();
			for (int i = 0; i < vc; i++) {
				//Pin the ends of an open chain. Chains are split by the pair of
				//heights they join, so a raised path is bounded by several
				//short chains that meet end to end - and each is smoothed on
				//its own. Move the shared endpoints and they no longer meet:
				//the wall opens at every junction and you see sky under the
				//path, because there is no floor beneath a raised tile to catch
				//the eye. Pinning costs a little rounding exactly at the joins
				//and keeps the wall closed.
				if (!loop && (i == 0 || i == vc - 1)) {
					continue;
				}
				int prev = i == 0 ? (loop ? vc - 2 : 0) : i - 1;
				int next = i == vc - 1 ? (loop ? 1 : vc - 1) : i + 1;
				nx2[i] = smoothX[i] * 0.5f + smoothX[prev] * 0.25f + smoothX[next] * 0.25f;
				nz2[i] = smoothZ[i] * 0.5f + smoothZ[prev] * 0.25f + smoothZ[next] * 0.25f;
			}
			smoothX = nx2;
			smoothZ = nz2;
		}
		//Smoothing shrinks, and three passes shrink enough to eat a narrow
		//feature alive: a one-tile-wide raised path has its two edges pulled
		//toward each other from both sides at once and collapses into a row of
		//stilts. Long runs need the full rounding, short ones cannot afford it,
		//and the difference is not the chain's length but how far any single
		//vertex ends up from where the tile grid actually put it. So cap that.
		final float MAX_PULL = TILE * 0.3f;
		for (int i = 0; i < vc; i++) {
			float dx = smoothX[i] - px[i], dz = smoothZ[i] - pz[i];
			float d = (float) Math.hypot(dx, dz);
			if (d > MAX_PULL) {
				smoothX[i] = px[i] + dx * (MAX_PULL / d);
				smoothZ[i] = pz[i] + dz * (MAX_PULL / d);
			}
		}

		//outward direction at each vertex: the mitre of the segments meeting there
		float[] mx = new float[vc], mz = new float[vc];
		for (int i = 0; i < vc; i++) {
			CliffEdge prev = i == 0 ? (loop ? chain.get(n - 1) : chain.get(0)) : chain.get(i - 1);
			CliffEdge next = i >= n ? (loop ? chain.get(0) : chain.get(n - 1)) : chain.get(i);
			//Prefer the bisector agreed by every wall touching this point, so
			//that a corner shared with another strip closes. Fall back to this
			//chain's own two edges if the point is not in the map.
			String vk = i < n ? chain.get(i).startKey() : chain.get(n - 1).endKey();
			float[] shared = corner == null ? null : corner.get(vk);
			float sx, sz;
			//The shared bisector is the sum of the outward normals of every
			//wall touching this point. That is meaningful at an ordinary
			//corner, where two or three walls broadly agree. It is meaningless
			//where a ramp corridor cuts through a cliff: half a dozen edges
			//meet there facing opposite ways, the sum cancels to nearly zero,
			//and normalising it yields an essentially random direction - which
			//the dot-clamp and the reach scale then stretch into a blade. The
			//cliff either side of every corridor was a spray of spikes.
			//When the walls disagree that badly, there is no shared bisector to
			//find; use this strip's own two edges.
			float slen = shared == null ? 0f : (float) Math.hypot(shared[0], shared[1]);
			if (shared != null && slen > 0.75f) {
				sx = shared[0];
				sz = shared[1];
			} else {
				sx = prev.nx + next.nx;
				sz = prev.nz + next.nz;
			}
			float len = (float) Math.sqrt(sx * sx + sz * sz);
			if (len < 1e-4f) {
				sx = next.nx;
				sz = next.nz;
				len = 1f;
			}
			sx /= len;
			sz /= len;
			//lengthen so a mitred corner keeps the same face width as a straight run
			//Cap how far a mitre may stretch. At 0.5 a corner reaches twice the
			//face width, which is a spike whenever the bisector is even
			//slightly off; 0.7 keeps corners closed without letting them grow
			//blades.
			float dot = Math.max(0.7f, sx * next.nx + sz * next.nz);
			mx[i] = sx / dot;
			mz[i] = sz / dot;
		}

		//HEIGHT PER VERTEX. This is what lets one strip run through a change of
		//step instead of ending at it. A vertex shared by two edges of different
		//depth takes the average of the two, so the wall's top and bottom edges
		//both slope through the transition - which is what a hillside corner
		//actually looks like - rather than one wall stopping dead and another
		//starting beside it with a seam down the join.
		float[] vyt = new float[vc], vyb = new float[vc];
		for (int i = 0; i < vc; i++) {
			CliffEdge ea = i == 0 ? (loop ? chain.get(n - 1) : chain.get(0)) : chain.get(i - 1);
			CliffEdge eb = i >= n ? (loop ? chain.get(0) : chain.get(n - 1)) : chain.get(i);
			vyt[i] = (ea.yTop + eb.yTop) * 0.5f;
			vyb[i] = (ea.yBot + eb.yBot) * 0.5f;
		}

		//and every proportion derived from it, likewise per vertex
		float[] vyMid = new float[vc], vRunS = new float[vc], vRunT = new float[vc];
		boolean anyDrop = false;
		for (int i = 0; i < vc; i++) {
			float d = vyt[i] - vyb[i];
			if (d > 0.01f) {
				anyDrop = true;
			}
			//The face carries most of the fall AND most of the horizontal run,
			//so from above it is the face you see, not the shoulder.
			float rs = d * 0.22f;
			float rt = rs + d * 0.55f;
			//Never reach further than one tile out, however tall the drop: a
			//45-degree face on a 36-unit step overshoots the terrace below.
			if (rt > TILE) {
				float squeeze = TILE / rt;
				rs *= squeeze;
				rt *= squeeze;
			}
			CliffEdge ea = i == 0 ? (loop ? chain.get(n - 1) : chain.get(0)) : chain.get(i - 1);
			CliffEdge eb = i >= n ? (loop ? chain.get(0) : chain.get(n - 1)) : chain.get(i);
			if (ea.tight || eb.tight) {
				//a face that would lean over a route stands up instead
				rs = Math.min(rs, 1.2f);
				rt = Math.min(rt, 4.5f);
			}
			vyMid[i] = vyb[i] + d * 0.80f;
			vRunS[i] = rs;
			vRunT[i] = rt;
		}
		if (!anyDrop) {
			return;
		}

		//Stretch the mitre at a corner shared with a DEEPER wall so the two feet
		//meet. Far less of this is needed now that a strip carries its own
		//height changes, but a genuine corner between two separate walls still
		//exists and still has to close.
		if (corner != null) {
			for (int i = 0; i < vc; i++) {
				String vk = i < n ? chain.get(i).startKey() : chain.get(n - 1).endKey();
				float[] acc = corner.get(vk);
				if (acc == null || acc.length < 3 || vRunT[i] <= 0.01f
						|| acc[2] <= (vyt[i] - vyb[i]) + 0.01f) {
					continue;
				}
				float scale = Math.min(1.6f, reach(acc[2]) / vRunT[i]);
				if (scale > 1f) {
					mx[i] *= scale;
					mz[i] *= scale;
				}
			}
		}
		float bury = 2.0f;
		//v addresses the texture's bands directly, and this atlas runs the other
		//way up: measured over c108_gake_01, grey rock occupies V 0.00-0.19,
		//green grass V 0.25-0.44, and everything below V 0.50 is blank white.
		//Running the face 0.10 -> 0.92 therefore started it in rock, dragged it
		//through the grass strip and finished in pure white, which is why the
		//cliff came out pale and washed out. So V DESCENDS down the face: grass
		//at the lip, rock all the way down.
		//Which bands of the rock texture the face uses comes from the palette,
		//because cliff textures are not interchangeable: one is stone edge to
		//edge, another bakes a grass strip on top and blank white below, a third
		//runs its bands the other way up. Hardcoding one range meant every new
		//rock came out washed out until the numbers were changed by hand.
		TerrainCatalog.Donor cliffCat = TerrainCatalog.cliffDonor();
		final float V_LIP = cliffCat != null ? cliffCat.vLip : 0.2483f;
		final float V_MID = cliffCat != null ? cliffCat.vMid : 0.2015f;
		final float V_WALL = cliffCat != null ? cliffCat.vWall : 0.0485f;
		final float V_FOOT = cliffCat != null ? cliffCat.vFoot : 0.0031f;
		//u tiles along the contour once per 36 world units - exactly half a
		//repeat per 18-unit tile. Measured over eleven vanilla cliff regions:
		//the modal bin is 36.00 with an area-weighted median of 36.5-37.0. The
		//old 1/56 (and the comment claiming 0.32 repeats per tile) stretched
		//every cobble sideways.
		final float U_PER_UNIT = 1f / 36f;

		//Record this strip's end profiles, so a genuine corner between two
		//separate walls can still be capped.
		if (caps != null && !loop) {
			for (int endIdx : new int[]{0, vc - 1}) {
				String vk = endIdx < n ? chain.get(endIdx).startKey() : chain.get(n - 1).endKey();
				caps.computeIfAbsent(vk, k -> new ArrayList<>()).add(new float[][]{
					{smoothX[endIdx], vyt[endIdx], smoothZ[endIdx]},
					{smoothX[endIdx] + mx[endIdx] * vRunS[endIdx], vyMid[endIdx],
						smoothZ[endIdx] + mz[endIdx] * vRunS[endIdx]},
					{smoothX[endIdx] + mx[endIdx] * vRunT[endIdx], vyb[endIdx] - bury,
						smoothZ[endIdx] + mz[endIdx] * vRunT[endIdx]},
				});
			}
		}

		float u = 0f;
		for (int i = 0; i < n; i++) {
			int j = i + 1;
			float segLen = (float) Math.hypot(px[j] - px[i], pz[j] - pz[i]);
			float enx = chain.get(i).nx, enz = chain.get(i).nz;

			//The collar: a flat ring at the top, from the tile boundary the floor
			//ends on out to wherever the rounded outline went. Rounding moves the
			//top edge both ways, and the outward half used to tear holes because
			//the floor stopped short of the cliff; the collar covers that span
			//whichever way it went, a hair below the floor so the two do not
			//fight over the same pixels.
			//The collar goes in the GROUND mesh, not the cliff's. This rock
			//texture is stone edge to edge with no grass strip baked into it,
			//so drawing the rim in cliff rock left the clifftop a hard grey
			//line; every Pokemon cliff has a green lip where the lawn rolls
			//over the edge. Same geometry, grass material.
			//A vanilla cliff carries an up-facing lip band about 7.5 units wide
			//along its top edge before the terrace floor starts; that is what
			//makes the edge read as rolled-over rock rather than a paper-thin
			//cut. Measured across the Fiery Path and Cave of Origin cliffs.
			final float COLLAR_IN = 2.5f, COLLAR_OUT = 7.5f;
			//Vanilla's clifftop lip is CLIFF material, not ground - measured as an
			//up-facing band about 7.5 units wide along every cliff top. Sending
			//it to the ground mesh put it in whatever the DONOR's default ground
			//happened to be, which is where the thick brown borders around every
			//platform came from: a band of some other map's dirt laid round grey
			//rock. It belongs in the cliff mesh, sampling the cliff's own lip
			//band, so it reads as the rock rolling over its edge.
			out.add(flatQuadV(
					px[i] - mx[i] * COLLAR_IN, pz[i] - mz[i] * COLLAR_IN,
					px[j] - mx[j] * COLLAR_IN, pz[j] - mz[j] * COLLAR_IN,
					smoothX[i] + mx[i] * COLLAR_OUT, smoothZ[i] + mz[i] * COLLAR_OUT,
					smoothX[j] + mx[j] * COLLAR_OUT, smoothZ[j] + mz[j] * COLLAR_OUT,
					vyt[i] - 0.02f, vyt[j] - 0.02f,
					u * U_PER_UNIT, (u + segLen) * U_PER_UNIT, V_LIP, V_MID, 0.86f));

			//A CLIFF IS A STACK OF 18-UNIT STEPS, not one tall slab.
			//
			//Vanilla never lets a cliff face cross a multiple of 18: measured
			//over five lava regions, 0.00% of cliff area does. Each step is
			//three quads - a lip bevel of 2.25, a sheer wall of 13.5, a foot
			//bevel of 2.25 - and together they lean the face back 13.5 units
			//horizontally per step. The texture restarts every step, running
			//vLip -> vMid -> vWall -> vFoot, about a quarter of the atlas.
			//
			//Mapping one V span across the whole drop instead, as this did,
			//smeared the cobbles into horizontal bands however the numbers were
			//tuned - and building the face as a single quad is what made the
			//rock read as flat plastic rather than stacked stone.
			final float STEP_LIP = 2.25f, STEP_WALL = 13.5f, STEP_FOOT = 2.25f;
			final float RUN_LIP = 4.4f, RUN_WALL = 4.5f, RUN_FOOT = 3.4f;
			final float RUN_STEP = RUN_LIP + RUN_WALL + RUN_FOOT;

			float dropI = Math.max(0f, vyt[i] - vyb[i]);
			float dropJ = Math.max(0f, vyt[j] - vyb[j]);
			int steps = (int) Math.ceil(Math.max(dropI, dropJ) / STEP - 0.001f);
			steps = Math.max(1, Math.min(steps, 8));

			for (int st = 0; st < steps; st++) {
				//this step's slice of each end's drop, clamped at the foot so a
				//drop that is not a whole number of levels still lands flush
				float tI = Math.max(vyb[i], vyt[i] - st * STEP);
				float bI = Math.max(vyb[i], vyt[i] - (st + 1) * STEP);
				float tJ = Math.max(vyb[j], vyt[j] - st * STEP);
				float bJ = Math.max(vyb[j], vyt[j] - (st + 1) * STEP);
				float hI = tI - bI, hJ = tJ - bJ;
				if (hI < 0.01f && hJ < 0.01f) {
					continue;
				}
				//a partial step keeps the same proportions, just shorter
				float fI = hI / STEP, fJ = hJ / STEP;
				float oI = st * RUN_STEP, oJ = st * RUN_STEP;

				float lipBotI = tI - STEP_LIP * fI, lipBotJ = tJ - STEP_LIP * fJ;
				float wallBotI = lipBotI - STEP_WALL * fI, wallBotJ = lipBotJ - STEP_WALL * fJ;
				float footBotI = bI - (st == steps - 1 ? bury : 0f);
				float footBotJ = bJ - (st == steps - 1 ? bury : 0f);

				out.add(stripVar(
						smoothX[i], smoothZ[i], mx[i], mz[i], tI, lipBotI,
						oI, oI + RUN_LIP * fI,
						smoothX[j], smoothZ[j], mx[j], mz[j], tJ, lipBotJ,
						oJ, oJ + RUN_LIP * fJ, enx, enz,
						u * U_PER_UNIT, (u + segLen) * U_PER_UNIT,
						V_LIP, V_MID, 0.80f, 0.72f));
				out.add(stripVar(
						smoothX[i], smoothZ[i], mx[i], mz[i], lipBotI, wallBotI,
						oI + RUN_LIP * fI, oI + (RUN_LIP + RUN_WALL) * fI,
						smoothX[j], smoothZ[j], mx[j], mz[j], lipBotJ, wallBotJ,
						oJ + RUN_LIP * fJ, oJ + (RUN_LIP + RUN_WALL) * fJ, enx, enz,
						u * U_PER_UNIT, (u + segLen) * U_PER_UNIT,
						V_MID, V_WALL, 0.72f, 0.56f));
				out.add(stripVar(
						smoothX[i], smoothZ[i], mx[i], mz[i], wallBotI, footBotI,
						oI + (RUN_LIP + RUN_WALL) * fI, oI + RUN_STEP * fI,
						smoothX[j], smoothZ[j], mx[j], mz[j], wallBotJ, footBotJ,
						oJ + (RUN_LIP + RUN_WALL) * fJ, oJ + RUN_STEP * fJ, enx, enz,
						u * U_PER_UNIT, (u + segLen) * U_PER_UNIT,
						V_WALL, V_FOOT, 0.56f, 0.44f));
			}
			u += segLen;
		}
	}

	/**
	 * One band of a contour strip, with its own height and its own outward
	 * reach at EACH end.
	 *
	 * <p>The scalar version could only describe a band of constant depth, which
	 * is why a wall had to be cut wherever its step changed - and every seam
	 * between those pieces was a defect waiting to happen. Letting the two ends
	 * differ is what allows one strip to run through the change.
	 */
	private static Quad stripVar(
			float x0, float z0, float m0x, float m0z,
			float yTop0, float yBot0, float out0Top, float out0Bot,
			float x1, float z1, float m1x, float m1z,
			float yTop1, float yBot1, float out1Top, float out1Bot,
			float edgeNx, float edgeNz,
			float u0, float u1, float vTop, float vBot, float aoTop, float aoBot) {
		Quad q = new Quad();
		q.pos[0] = new float[]{x0 + m0x * out0Top, yTop0, z0 + m0z * out0Top};
		q.pos[1] = new float[]{x1 + m1x * out1Top, yTop1, z1 + m1z * out1Top};
		q.pos[2] = new float[]{x0 + m0x * out0Bot, yBot0, z0 + m0z * out0Bot};
		q.pos[3] = new float[]{x1 + m1x * out1Bot, yBot1, z1 + m1z * out1Bot};
		q.uv[0] = new float[]{u0, vTop};
		q.uv[1] = new float[]{u1, vTop};
		q.uv[2] = new float[]{u0, vBot};
		q.uv[3] = new float[]{u1, vBot};
		q.ao[0] = aoTop;
		q.ao[1] = aoTop;
		q.ao[2] = aoBot;
		q.ao[3] = aoBot;
		//lean averaged over the two ends, so lighting follows the slope
		float run = ((out0Bot - out0Top) + (out1Bot - out1Top)) * 0.5f;
		float rise = ((yTop0 - yBot0) + (yTop1 - yBot1)) * 0.5f;
		float hyp = (float) Math.hypot(run, rise);
		float upward = hyp < 1e-4f ? 0f : run / hyp;
		float horiz = hyp < 1e-4f ? 1f : rise / hyp;
		float ax2 = (m0x + m1x) * 0.5f, az2 = (m0z + m1z) * 0.5f;
		float al = (float) Math.hypot(ax2, az2);
		if (al > 1e-4f) {
			ax2 /= al;
			az2 /= al;
		}
		for (int c = 0; c < 4; c++) {
			q.nrm[c] = new float[]{ax2 * horiz, upward, az2 * horiz};
		}
		//Wind against the SEGMENT'S OWN outward direction, never the averaged
		//mitre: at a corner the two mitres are perpendicular and their average
		//points diagonally, so the sign comes down to rounding - and a cliff
		//face wound inward is culled, showing the background straight through.
		fixWindingTowards(q, edgeNx * horiz, upward, edgeNz * horiz);
		return q;
	}

	/**
	 * A horizontal quad whose two ends may sit at different heights, textured by
	 * world position exactly as the floor is.
	 *
	 * <p>This draws the lip band along a clifftop, and it is GROUND: it has to
	 * continue the terrace it belongs to. Giving it the cliff's V bands instead
	 * made it sample some unrelated stripe of the floor texture, which is where
	 * the thick brown borders round every platform came from.
	 */
	private static Quad flatQuadV(float ax, float az, float bx, float bz,
			float cx, float cz, float dx, float dz, float yA, float yB,
			float u0, float u1, float v0, float v1, float ao) {
		Quad q = new Quad();
		q.pos[0] = new float[]{ax, yA, az};
		q.pos[1] = new float[]{bx, yB, bz};
		q.pos[2] = new float[]{cx, yA, cz};
		q.pos[3] = new float[]{dx, yB, dz};
		q.uv[0] = new float[]{u0, v0};
		q.uv[1] = new float[]{u1, v0};
		q.uv[2] = new float[]{u0, v1};
		q.uv[3] = new float[]{u1, v1};
		for (int c = 0; c < 4; c++) {
			q.nrm[c] = new float[]{0f, 1f, 0f};
			q.ao[c] = ao;
		}
		fixWindingTowards(q, 0f, 1f, 0f);
		return q;
	}

	/**
	 * One band of a contour strip, whose top row may sit on a different line
	 * from its bottom row - the top on the true tile boundary so the floor above
	 * meets it, the bottom on the rounded contour.
	 */
	/**
	 * A horizontal quad, wound to face upward.
	 *
	 * <p>Used for the collar that joins the cliff's rounded top edge back to
	 * the tile boundary where the floor ends. {@link #stripMixed} cannot do
	 * this job: it derives its facing from the rise and run of a slope, and on
	 * a level quad both are zero, so which way it decides to face comes down
	 * to rounding.
	 */
	private static Quad flatQuad(float ax, float az, float bx, float bz,
			float cx, float cz, float dx, float dz, float y,
			float u0, float u1, float v0, float v1, float ao) {
		Quad q = new Quad();
		q.pos[0] = new float[]{ax, y, az};
		q.pos[1] = new float[]{bx, y, bz};
		q.pos[2] = new float[]{cx, y, cz};
		q.pos[3] = new float[]{dx, y, dz};
		q.uv[0] = new float[]{u0, v0};
		q.uv[1] = new float[]{u1, v0};
		q.uv[2] = new float[]{u0, v1};
		q.uv[3] = new float[]{u1, v1};
		for (int c = 0; c < 4; c++) {
			q.nrm[c] = new float[]{0f, 1f, 0f};
			q.ao[c] = ao;
		}
		fixWindingTowards(q, 0f, 1f, 0f);
		return q;
	}

	private static Quad stripMixed(float tx0, float tz0, float bx0, float bz0, float m0x, float m0z,
			float tx1, float tz1, float bx1, float bz1, float m1x, float m1z,
			float edgeNx, float edgeNz,
			float yTop, float yBot, float outTop, float outBot,
			float u0, float u1, float vTop, float vBot, float aoTop, float aoBot) {
		Quad q = new Quad();
		q.pos[0] = new float[]{tx0 + m0x * outTop, yTop, tz0 + m0z * outTop};
		q.pos[1] = new float[]{tx1 + m1x * outTop, yTop, tz1 + m1z * outTop};
		q.pos[2] = new float[]{bx0 + m0x * outBot, yBot, bz0 + m0z * outBot};
		q.pos[3] = new float[]{bx1 + m1x * outBot, yBot, bz1 + m1z * outBot};
		q.uv[0] = new float[]{u0, vTop};
		q.uv[1] = new float[]{u1, vTop};
		q.uv[2] = new float[]{u0, vBot};
		q.uv[3] = new float[]{u1, vBot};
		q.ao[0] = aoTop;
		q.ao[1] = aoTop;
		q.ao[2] = aoBot;
		q.ao[3] = aoBot;
		//face normal from the mitre and the lean, so lighting follows the slope
		float run = outBot - outTop, rise = yTop - yBot;
		float hyp = (float) Math.hypot(run, rise);
		float upward = hyp < 1e-4f ? 0f : run / hyp;
		float horiz = hyp < 1e-4f ? 1f : rise / hyp;
		float ax = (m0x + m1x) * 0.5f, az = (m0z + m1z) * 0.5f;
		float al = (float) Math.hypot(ax, az);
		if (al > 1e-4f) {
			ax /= al;
			az /= al;
		}
		for (int c = 0; c < 4; c++) {
			q.nrm[c] = new float[]{ax * horiz, upward, az * horiz};
		}
		//Wind against the SEGMENT'S OWN outward direction, never the averaged
		//mitre. At a corner the two mitres are perpendicular, so their average
		//points diagonally - and on the shallow shoulder, whose face is nearly
		//horizontal anyway, its horizontal part is small enough that the sign
		//comes down to rounding. Those faces wound inward, and a cliff face
		//wound inward is back-face culled: it vanishes and the background shows
		//straight through, which looks exactly like a missing texture. The edge
		//knows unambiguously which way is downhill; use it.
		fixWindingTowards(q, edgeNx * horiz, upward, edgeNz * horiz);
		return q;
	}

	static List<Quad> cliffQuads(int tx, int ty, int dir, float yb, float yt) {
		List<Quad> out = new ArrayList<>();
		float drop = yt - yb;
		if (drop <= 0.01f) {
			return out;
		}
		//shoulder: 30% of the fall, leaning out ~0.64 of the total drop
		//lower face: the remaining 70%, leaning out ~0.25 - together ~0.89,
		//i.e. run ~= rise, the measured 45 degrees
		//Centre the slope ON the tile boundary and bury its foot.
		//
		//Leaning the whole face outward put its foot flat on the lower tile's
		//floor - two coplanar surfaces, which z-fight, and the fighting reads as
		//dark streaks running down the cliff. Measuring the UVs ruled out tiling
		//as the cause: 0.24 repeats per tile against retail's 0.32.
		//
		//So half the run goes back into the high tile and half comes forward
		//over the low one, and the foot sinks below the lower floor rather than
		//resting on it. Nothing is coplanar with anything.
		float yMid = yb + drop * 0.70f;
		float runShoulder = drop * 0.64f;
		float runFace = drop * 0.25f;
		float total = runShoulder + runFace;
		//The top stays ON the tile edge. Pulling it back into the high tile put
		//the shoulder underneath that tile's own floor quad, which covers the
		//whole tile - so the top half of every cliff was hidden and all that
		//showed was a thin dark strip of the lower face. Burying the foot is
		//enough on its own to stop the coplanar z-fighting with the low floor.
		float back = 0f;
		float bury = 2.0f;              //how far the foot sinks under the low floor
		//v runs along the SURFACE, not down the drop. A sloped face is longer
		//than it is tall - the shoulder especially, which falls 30% of the drop
		//over 64% of it horizontally - so measuring v by height squeezed the
		//texture into a fraction of the space it covers, and squeezed the two
		//bands by different amounts, leaving a visible seam where they meet.
		float lenShoulder = (float) Math.hypot(drop * 0.30f, runShoulder);
		float lenFace = (float) Math.hypot(drop * 0.70f, runFace);
		out.add(slopedQuad(tx, ty, dir, yt, yMid, -back, runShoulder - back, 0f, lenShoulder));
		out.add(slopedQuad(tx, ty, dir, yMid, yb - bury, runShoulder - back, total - back,
				lenShoulder, lenShoulder + lenFace));
		return out;
	}

	/**
	 * One band of a cliff face: from {@code yTop} out at {@code outTop} to
	 * {@code yBot} out at {@code outBot}, where "out" is horizontal distance
	 * past the tile edge in the descent direction.
	 */
	private static Quad slopedQuad(int tx, int ty, int dir, float yTop, float yBot,
			float outTop, float outBot, float vTop, float vBot) {
		Quad q = cliffQuad(tx, ty, dir, yBot, yTop);
		float nx = q.nrm[0][0], nz = q.nrm[0][2];
		//Offset by each vertex's own HEIGHT, never by its slot. cliffQuad ends
		//with a winding fix that swaps slots 1 and 2, so after it returns a
		//top-edge vertex may be sitting in a slot the naive "0,1 are the top"
		//reading calls bottom. Doing it by slot moved one top and one bottom
		//vertex the wrong way each, the two cancelled, and the face came out
		//perfectly vertical - measured flatness 0.0000 over 1164 triangles,
		//exactly the box shape this was meant to remove.
		float mid = (yTop + yBot) * 0.5f;
		for (int c = 0; c < 4; c++) {
			boolean isTop = q.pos[c][1] >= mid;
			float out = isTop ? outTop : outBot;
			q.pos[c][0] += nx * out;
			q.pos[c][2] += nz * out;
			//v measured along the slope, keyed off the vertex's own height for
			//the same reason the offset is: cliffQuad may have swapped slots
			q.uv[c][1] = isTop ? vTop : vBot;
		}
		//the face is no longer vertical, so its normal is not the edge normal
		float run = outBot - outTop, rise = yTop - yBot;
		float len = (float) Math.sqrt(run * run + rise * rise);
		float wantX = nx, wantY = 0f, wantZ = nz;
		if (len > 1e-4f) {
			float upward = run / len, horiz = rise / len;
			wantX = nx * horiz;
			wantY = upward;
			wantZ = nz * horiz;
			for (int c = 0; c < 4; c++) {
				q.nrm[c] = new float[]{wantX, wantY, wantZ};
			}
		}
		//Wind against the FULL 3D normal, not the horizontal edge direction. The
		//shoulder band is only ~25 degrees off horizontal, so its face normal is
		//mostly +Y and its horizontal component is nearly zero - testing the sign
		//of that component decides the winding on rounding noise, which left 8 of
		//384 faces inside out.
		fixWindingTowards(q, wantX, wantY, wantZ);
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
		//The corner order above winds every one of the four directions inward,
		//so painted elevation shipped with no rim walls at all: most cliff
		//materials cull back faces, leaving the plateau top drawn and its sides
		//invisible. The top looked right, which is why it went unreported.
		fixWindingOut(q, nx, nz);
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
					//encode PER ATTRIBUTE FORMAT - a color attribute may be s8
					//(range 0..127, 1/127 scale), u8, s16 or float. Writing the
					//raw 0..255 value into an s8 attribute overflows to a
					//NEGATIVE byte, which the renderer shows as BLACK: measured
					//on 60% of retail regions, and the cause of paint coming out
					//as a dark square on maps like Mauville.
					float unit = (k < 4 ? rgba[k] : 255) / 255f;
					MapModelObjImporter.putComp(vtx, o, col.type, unit);
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
			//A material this editor imported arrives blanked to a single vertex -
			//TerrainCatalog keeps the material and throws the donor's geometry
			//away, so there is nothing left here to measure. Every imported
			//brush therefore painted at the 1/36 default, while retail
			//world-projected ground is authored at about 1/72: a consistent 2x
			//texture-scale error on exactly the brushes the editor adds. The
			//donor still knows its own scale, so ask the catalog for it.
			float[] donor = TerrainCatalog.donorUvScale(
					model.getMaterialName(model.getMeshMaterialIndex(g.meshIndex)));
			return donor != null ? donor : new float[]{def, def};
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

	/**
	 * The mesh a brush paints with: the first material whose name matches one of
	 * the brush's hints, skipping edge overlays and sprite atlases - and, for
	 * ground brushes, skipping anything that is not actually a surface.
	 *
	 * <p>That last filter matters more than it looks. The hints are matched by
	 * substring and ROCK's include {@code gake}, which is Japanese for CLIFF, so
	 * on a normal outdoor route "rock" matched the map's own cliff face:
	 * {@code chip_gake_sea} on Route 101, {@code r105_chip_rock_c} on Route 103.
	 * Painting rock ground then laid vertical cliff art flat on the floor. It
	 * looked like a bad donor row in the terrain table, but the table was never
	 * consulted - {@code ensureMaterial} returns early whenever the map already
	 * has a matching material, and 294 regions matched a cliff this way. Most of
	 * them held a perfectly good flat rock mesh a little further down the list.
	 *
	 * <p>Cliffs pass {@code wantSurface = false}: {@link #resolveCliffMesh} looks
	 * up the same ROCK brush and genuinely wants the vertical material.
	 */
	private static int resolveMesh(BchMapModel model, TilePalette t, int fallback) {
		return resolveMesh(model, t, fallback, true);
	}

	/** Fraction of a mesh's surface area that faces up; a floor ~1, a wall ~0. */
	private static final double MIN_GROUND_FLATNESS = 0.5;

	/** The mesh a ground brush resolves to natively, or -1 when nothing matches
	 *  and the brush must import a donor instead. Exposed for the corpus sweep
	 *  in {@link ctrmap.tests.GroundResolveTest}. */
	public static int resolvedGroundMesh(BchMapModel model, TilePalette t) {
		return resolveMesh(model, t, -1, true);
	}

	/** @see #flatFraction */
	public static double meshFlatness(BchMapModel model, int meshIndex) {
		return flatFraction(model, meshIndex);
	}

	/** @see #measureUvScale */
	public static float[] uvScaleOf(BchMapModel model, int meshIndex) {
		return measureUvScale(model, model.geometry().get(meshIndex));
	}

	private static int resolveMesh(BchMapModel model, TilePalette t, int fallback, boolean wantSurface) {
		for (String hint : t.matHints) {
			for (BchMapModel.MeshGeom g : model.geometry()) {
				if (!g.posOk) {
					continue;
				}
				String name = model.getMaterialName(model.getMeshMaterialIndex(g.meshIndex));
				if (name == null || isEdgeMaterial(name) || isSpriteMaterial(name)
						|| !name.toLowerCase().contains(hint)) {
					continue;
				}
				double flat = flatFraction(model, g.meshIndex);
				if (wantSurface && flat >= 0 && flat < MIN_GROUND_FLATNESS) {
					continue; //a cliff face, not ground - keep looking
				}
				return g.meshIndex;
			}
		}
		return fallback;
	}

	/**
	 * Plan-view area over true surface area for a whole mesh: 1.0 for a flat
	 * floor, ~0 for a vertical wall. Unlike {@link #upFacingArea} this is a
	 * ratio, so it compares meshes of wildly different sizes.
	 *
	 * <p>Returns -1 when the mesh has no measurable surface at all, which is NOT
	 * the same answer as 0. A freshly imported brush material arrives as an
	 * empty placeholder - {@link TerrainCatalog#ensureMaterial} blanks it to a
	 * single vertex, precisely so the painter can fill it - and scoring that as
	 * "perfectly vertical" made the resolver reject the material it had just
	 * imported, for eleven brushes on the indoor test map alone. An unmeasurable
	 * mesh has to be given the benefit of the doubt; the filter exists to reject
	 * cliffs it can see, not geometry it cannot.
	 */
	private static double flatFraction(BchMapModel model, int meshIndex) {
		try {
			float[][] pos = model.getVertexPositions(meshIndex);
			int[] tris = model.getTriangles(meshIndex);
			double plan = 0, total = 0;
			for (int t = 0; t + 2 < tris.length; t += 3) {
				int a = tris[t], b = tris[t + 1], c = tris[t + 2];
				if (a >= pos.length || b >= pos.length || c >= pos.length) {
					continue;
				}
				double ux = pos[b][0] - pos[a][0], uy = pos[b][1] - pos[a][1], uz = pos[b][2] - pos[a][2];
				double vx = pos[c][0] - pos[a][0], vy = pos[c][1] - pos[a][1], vz = pos[c][2] - pos[a][2];
				double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
				total += Math.sqrt(nx * nx + ny * ny + nz * nz);
				plan += Math.abs(ny);
			}
			return total > 0 ? plan / total : -1;
		} catch (RuntimeException ex) {
			return -1;
		}
	}

	/**
	 * Materials whose texture is mostly transparent - sprite strips, decals and
	 * decoration atlases. Measured game-wide by
	 * {@link ctrmap.tools.GroundMaterialAudit}; anything not listed is treated as
	 * usable, so imported and user-supplied materials are never restricted.
	 */
	private static Set<String> spriteMaterials;

	/**
	 * True when this material is a sprite/decal, not a surface you can stand on.
	 *
	 * <p>A material NAME cannot distinguish a tiling ground texture from a sprite
	 * atlas, and the brush hints match by substring, so the painter used to floor
	 * a map with whatever happened to match. On Route 102 "rock" matched
	 * {@code chip_jump_gake} - the jump-down ledge sprite, 43% opaque - and
	 * "path" matched {@code chip_wood_b}, the decoration atlas of bushes and
	 * flowers, 34% opaque. Painted tiles then sampled empty texels and drew
	 * nothing, over retail ground the compositor had already clipped away: the
	 * player stood in a black hole. Rejecting these sends the brush to
	 * {@link TerrainCatalog}, which imports a measured-opaque donor instead.
	 */
	static boolean isSpriteMaterial(String name) {
		return spriteMaterials().contains(name);
	}

	private static synchronized Set<String> spriteMaterials() {
		if (spriteMaterials != null) {
			return spriteMaterials;
		}
		spriteMaterials = new HashSet<>();
		try (java.io.InputStream in = PaintedRegionBuilder.class.getClassLoader()
				.getResourceAsStream("ctrmap/resources/oras_ground_materials.tsv")) {
			if (in != null) {
				java.util.Scanner sc = new java.util.Scanner(in, "UTF-8");
				while (sc.hasNextLine()) {
					String line = sc.nextLine().trim();
					if (line.isEmpty() || line.startsWith("#")) {
						continue;
					}
					String[] f = line.split("\t");
					if (f.length < 4) {
						continue;
					}
					try {
						//measured bimodal: 638 of 788 materials are 100% opaque and
						//everything below 95% is a sprite, decal or atlas
						if (Double.parseDouble(f[3]) < 95.0) {
							spriteMaterials.add(f[0]);
						}
					} catch (NumberFormatException ignore) {
					}
				}
			}
		} catch (Exception ex) {
			System.err.println("PaintedRegionBuilder: ground-material table unavailable: " + ex);
		}
		return spriteMaterials;
	}

	/**
	 * True when a triangle is a piece of SURFACE - something laid flat that you
	 * could stand on - rather than something standing up.
	 *
	 * <p>Both tests matter. Facing up alone would keep a canopy billboard that
	 * happens to lie flat; having no height alone would keep a flat wall panel.
	 * A floor is both at once.
	 */
	private static boolean isSurface(float[][] pos, int a, int b, int c) {
		float ux = pos[b][0] - pos[a][0], uy = pos[b][1] - pos[a][1], uz = pos[b][2] - pos[a][2];
		float vx = pos[c][0] - pos[a][0], vy = pos[c][1] - pos[a][1], vz = pos[c][2] - pos[a][2];
		float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1e-6f) {
			return false;
		}
		float minY = Math.min(pos[a][1], Math.min(pos[b][1], pos[c][1]));
		float maxY = Math.max(pos[a][1], Math.max(pos[b][1], pos[c][1]));
		return Math.abs(ny) / len > 0.8f && maxY - minY < 1.0f;
	}

	/** True when a triangle's footprint centre lies inside the painted area. */
	private static boolean centreInRegion(float[][] xz, List<float[]> rects) {
		float cx = (xz[0][0] + xz[1][0] + xz[2][0]) / 3f;
		float cz = (xz[0][1] + xz[1][1] + xz[2][1]) / 3f;
		for (float[] r : rects) {
			if (cx > r[0] && cx < r[2] && cz > r[1] && cz < r[3]) {
				return true;
			}
		}
		return false;
	}

	/** The cliff material mesh (gake/cliff/rock), or the rock/ground fallback.
	 *  Sprite materials are skipped: in ORAS the "gake" cliff is usually a
	 *  see-through ledge strip, and a wall built from it is an invisible wall. */
	public static int resolveCliffMesh(BchMapModel model, int fallback) {
		//An imported cliff material wins over the map's own. Most outdoor routes
		//carry an orange chip_gake and nothing better, so without this the
		//colour of every generated cliff is decided by whichever region the map
		//happened to be painted from.
		TerrainCatalog.Donor cd = TerrainCatalog.cliffDonor();
		if (cd != null) {
			for (int i = 0; i < model.meshCount; i++) {
				if (cd.injectName.equals(model.getMaterialName(model.getMeshMaterialIndex(i)))) {
					return i;
				}
			}
		}
		for (String hint : new String[]{"gake", "cliff", "chip_rock", "rock", "iwa"}) {
			for (BchMapModel.MeshGeom g : model.geometry()) {
				if (!g.posOk) {
					continue;
				}
				String name = model.getMaterialName(model.getMeshMaterialIndex(g.meshIndex));
				if (name != null && !isEdgeMaterial(name) && !isSpriteMaterial(name)
						&& name.toLowerCase().contains(hint)) {
					return g.meshIndex;
				}
			}
		}
		//the map's own cliff material is a sprite (or it has none): a rock brush
		//imported by TerrainCatalog is a real opaque wall, so prefer that.
		//wantSurface=false - this is a WALL, so the flatness filter that keeps
		//ground brushes off cliff faces must not run in reverse here
		return resolveMesh(model, TilePalette.ROCK, fallback, false);
	}

	/** The grass-edge overlay mesh (chip_kusa_edge / chip_grass_edge), or -1. */
	public static int resolveEdgeMesh(BchMapModel model) {
		for (String hint : new String[]{"kusa_edge", "grass_edge", "edge_tex", "_edge"}) {
			for (BchMapModel.MeshGeom g : model.geometry()) {
				if (!g.posOk) {
					continue;
				}
				String name = model.getMaterialName(model.getMeshMaterialIndex(g.meshIndex));
				if (name == null) {
					continue;
				}
				String lm = name.toLowerCase();
				//The last hint is a bare "_edge", broad enough to catch a
				//shoreline: wave_edge matched it, so every seam between grass
				//and a sand path got drawn with a water material - whose
				//texture the zone's area does not even carry, so the strips
				//came out solid white. A white outline round every path is the
				//result. A seam between two kinds of ground is never a wave.
				if (lm.contains("wave") || lm.contains("sea") || lm.contains("mizu")
						|| lm.contains("water") || lm.contains("taki")
						|| lm.contains("enkei") || lm.contains("umi")) {
					continue;
				}
				if (lm.contains(hint)) {
					return g.meshIndex;
				}
			}
		}
		//Better no edge strips than strips drawn with the wrong material: an
		//absent softening reads as a plain seam, a wrong one reads as damage.
		return -1;
	}

	private static boolean isEdgeMaterial(String name) {
		String n = name.toLowerCase();
		return n.contains("_edge") || n.contains("edge_tex");
	}

	/** The map's main GROUND mesh: the largest FLOOR-facing surface. Scored by
	 *  up-facing triangle area, not raw triangle count - the biggest mesh of an
	 *  indoor map is usually a wall, and painting the floor with a wall
	 *  material is how "sand" ended up looking like grey plaster. */
	public static int defaultGroundMesh(BchMapModel model) {
		int best = -1, bestFallback = -1;
		double bestArea = -1;
		long bestTris = -1;
		for (BchMapModel.MeshGeom g : model.geometry()) {
			if (!g.posOk) {
				continue;
			}
			String name = model.getMaterialName(model.getMeshMaterialIndex(g.meshIndex));
			if (name != null && isEdgeMaterial(name)) {
				continue; // never treat the thin edge overlay as the ground
			}
			int tris = model.getTriangles(g.meshIndex).length;
			if (tris > bestTris) {
				bestTris = tris;
				bestFallback = g.meshIndex;
			}
			double up = upFacingArea(model, g.meshIndex);
			if (up > bestArea) {
				bestArea = up;
				best = g.meshIndex;
			}
		}
		return bestArea > 0 ? best : bestFallback;
	}

	/** Plan-view area of a mesh's up-facing triangles (a floor scores high, a wall ~0). */
	private static double upFacingArea(BchMapModel model, int meshIndex) {
		try {
			float[][] pos = model.getVertexPositions(meshIndex);
			int[] tris = model.getTriangles(meshIndex);
			double area = 0;
			for (int t = 0; t + 2 < tris.length; t += 3) {
				int a = tris[t], b = tris[t + 1], c = tris[t + 2];
				if (a >= pos.length || b >= pos.length || c >= pos.length) {
					continue;
				}
				//|cross(ab, ac).y| / 2 = the triangle's shadow on the ground
				double ux = pos[b][0] - pos[a][0], uz = pos[b][2] - pos[a][2];
				double vx = pos[c][0] - pos[a][0], vz = pos[c][2] - pos[a][2];
				area += Math.abs(ux * vz - vx * uz) * 0.5;
			}
			return area;
		} catch (RuntimeException ex) {
			return 0;
		}
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
