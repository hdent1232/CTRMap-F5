package ctrmap.formats.tilemap;

import ctrmap.formats.GameFiles;
import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.MapModelObj;
import ctrmap.formats.h3d.MapModelObjImporter;
import ctrmap.formats.h3d.RegionFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static ctrmap.formats.tilemap.PaintedCliffMesh.bakeQuadLighting;
import static ctrmap.formats.tilemap.PaintedCliffMesh.cliffEdge;
import static ctrmap.formats.tilemap.PaintedCliffMesh.emitCliffStrips;
import static ctrmap.formats.tilemap.PaintedCollision.buildCollision;
import static ctrmap.formats.tilemap.PaintedCollision.buildCollisionComposite;
import static ctrmap.formats.tilemap.PaintedFloorMesh.addChurnLayer;
import static ctrmap.formats.tilemap.PaintedFloorMesh.addEdgeStrips;
import static ctrmap.formats.tilemap.PaintedFloorMesh.addShoreBands;
import static ctrmap.formats.tilemap.PaintedFloorMesh.backingWall;
import static ctrmap.formats.tilemap.PaintedFloorMesh.decodeVertex;
import static ctrmap.formats.tilemap.PaintedFloorMesh.encodeVertex;
import static ctrmap.formats.tilemap.PaintedFloorMesh.floorQuad;
import static ctrmap.formats.tilemap.PaintedFloorMesh.isWet;
import static ctrmap.formats.tilemap.PaintedFloorMesh.meshFromQuads;
import static ctrmap.formats.tilemap.PaintedFloorMesh.rampSkirt;
import static ctrmap.formats.tilemap.PaintedGeometry.CliffEdge;
import static ctrmap.formats.tilemap.PaintedGeometry.DIM;
import static ctrmap.formats.tilemap.PaintedGeometry.EDGE_W;
import static ctrmap.formats.tilemap.PaintedGeometry.ORIGIN;
import static ctrmap.formats.tilemap.PaintedGeometry.Quad;
import static ctrmap.formats.tilemap.PaintedGeometry.STEP;
import static ctrmap.formats.tilemap.PaintedGeometry.TILE;
import static ctrmap.formats.tilemap.PaintedGeometry.WATER_SINK;
import static ctrmap.formats.tilemap.PaintedHeights.baseFloor;
import static ctrmap.formats.tilemap.PaintedHeights.borrowedGroundTiles;
import static ctrmap.formats.tilemap.PaintedHeights.floorBeside;
import static ctrmap.formats.tilemap.PaintedHeights.floorYOf;
import static ctrmap.formats.tilemap.PaintedHeights.isTouched;
import static ctrmap.formats.tilemap.PaintedHeights.nearestGround;
import static ctrmap.formats.tilemap.PaintedHeights.neighbourTopY;
import static ctrmap.formats.tilemap.PaintedHeights.noRamps;
import static ctrmap.formats.tilemap.PaintedHeights.rampDir;
import static ctrmap.formats.tilemap.PaintedHeights.rampFoot;
import static ctrmap.formats.tilemap.PaintedHeights.sampleBaseY;
import static ctrmap.formats.tilemap.PaintedHeights.walkableTiles;
import static ctrmap.formats.tilemap.PaintedMaterials.centreInRegion;
import static ctrmap.formats.tilemap.PaintedMaterials.defaultGroundMesh;
import static ctrmap.formats.tilemap.PaintedMaterials.isSurface;
import static ctrmap.formats.tilemap.PaintedMaterials.resolveCliffMesh;
import static ctrmap.formats.tilemap.PaintedMaterials.resolveEdgeMesh;
import static ctrmap.formats.tilemap.PaintedMaterials.resolveMesh;
import static ctrmap.formats.tilemap.PaintedTiles.buildTilemap;
import static ctrmap.formats.tilemap.PaintedTiles.buildTilemapComposite;

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
 * world -360,-360); one height level = {@link PaintedGeometry#STEP} world units.
 */
public class PaintedRegionBuilder {

	/**
	 * A build with NO game handed: nothing is cut from any pristine snapshot,
	 * so the cliff faces and the lava overlay keep whatever material the donor
	 * model already carries. That is exactly what a build made before a
	 * workspace exists always got; a caller with a game open hands it to
	 * {@link #build(GameFiles, byte[], TilePalette[][], int[][], int[][], TerrainLighting, boolean)}.
	 *
	 * @param edges when true (and the donor carries a grass-edge material), lay
	 *              GameFreak-style transition strips along grass&harr;dirt/sand seams
	 *              (the projected "blend" edge). Ignored if the tileset donor has
	 *              no edge material.
	 */
	public static RegionFactory.BlankContent build(byte[] donorModel, TilePalette[][] grid, int[][] height, int[][] ramp, TerrainLighting light, boolean edges) {
		return build(null, donorModel, grid, height, ramp, light, edges);
	}

	/**
	 * The full build, cutting the catalogue's cliff and lava-churn materials
	 * from the pristine snapshot of the handed game.
	 *
	 * <p>The game is a parameter rather than fetched: {@link TerrainCatalog}
	 * used to resolve the snapshot from the application's global session, so
	 * a build could only ever cut from the application's game. This is the
	 * smallest change that lets a handed game reach it; the rest of this class
	 * is a later step's.
	 *
	 * @param files the game whose snapshot the catalogue cuts from; null means
	 *              nothing is cut, as above
	 * @param height per-tile elevation in levels (null = all flat at 0).
	 * @param ramp per-tile ramp direction ({@link PaintedGeometry#NO_RAMP} = flat, else the way
	 *             DOWN: 0 E, 1 W, 2 S, 3 N; null = no ramps); a ramp tile slopes
	 *             from its level to that neighbour's floor, replacing the cliff
	 *             so the player walks it. Stair brushes slope on their own.
	 * @throws IllegalStateException when there was a snapshot to cut from and
	 *              a catalogue import failed, with the reason; the caller that
	 *              owns a window reports it
	 */
	public static RegionFactory.BlankContent build(GameFiles files, byte[] donorModel, TilePalette[][] grid, int[][] height, int[][] ramp, TerrainLighting light, boolean edges) {
		if (height == null) {
			height = new int[DIM][DIM];
		}
		if (ramp == null) {
			ramp = noRamps();
		}
		RegionFactory.BlankContent out = new RegionFactory.BlankContent();
		out.model = buildModel(files, donorModel, grid, height, ramp, null, null, null, light, edges);
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
	 * @param files          the game the catalogue's materials are cut from, or null for none; see
	 *                       {@link #build(GameFiles, byte[], TilePalette[][], int[][], int[][], TerrainLighting, boolean)}
	 */
	public static RegionFactory.BlankContent buildComposite(GameFiles files, byte[] donorModel, byte[] donorCollision, byte[] donorTilemap,
			TilePalette[][] grid, int[][] height, int[][] ramp, boolean[][] touched, TerrainLighting light, boolean edges) {
		if (touched == null) {
			return build(files, donorModel, grid, height, ramp, light, edges);
		}
		if (height == null) {
			height = new int[DIM][DIM];
		}
		if (ramp == null) {
			ramp = noRamps();
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
		//A DONOR THAT CANNOT BE KEPT IS REFUSED, NOT REBUILT OVER (census H.64): painting over a region keeps every
		//tile not painted, and a tilemap or collision that did not read was replaced whole - the touched mask, the
		//whole point of painting over a region, dropped with nothing said
		String unusable = PaintedTiles.whyNotADonorTilemap(donorTilemap);
		if (unusable == null && !ctrmap.formats.gfcollision.GfColl.isColl(donorCollision)) {
			unusable = "the region's collision (subfile 2) is not a collision";
		}
		if (unusable != null) {
			throw new IllegalStateException("Nothing was painted over this region: " + unusable
					+ ", so what you did not paint cannot be kept.");
		}
		float[][] baseY = sampleBaseY(donorCollision);
		float[][] ground = nearestGround(baseY, walkableTiles(donorTilemap));
		out.borrowedGround = borrowedGroundTiles(baseY, ground, touched);
		out.model = buildModel(files, donorModel, grid, height, ramp, touched, baseY, ground, light, edges);
		out.collision = buildCollisionComposite(donorCollision, grid, height, ramp, touched, baseY, ground);
		out.tilemap = buildTilemapComposite(donorTilemap, grid, touched);
		out.props = new byte[]{0, 0, 0, 0};
		return out;
	}

	/**
	 * The visual model alone, composite-aware - the live 3D preview's path
	 * (collision is used only to place floors at the retail surface height,
	 * the tilemap only to fill unsampled tiles from walkable ground). {@code files} is the game the
	 * catalogue's materials are cut from, or null for none; see
	 * {@link #build(GameFiles, byte[], TilePalette[][], int[][], int[][], TerrainLighting, boolean)}.
	 */
	public static byte[] buildModelOnly(GameFiles files, byte[] donorModel, byte[] donorCollision, byte[] donorTilemap, TilePalette[][] grid,
			int[][] height, int[][] ramp, boolean[][] touched, TerrainLighting light, boolean edges) {
		if (height == null) {
			height = new int[DIM][DIM];
		}
		if (ramp == null) {
			ramp = noRamps();
		}
		float[][] baseY = touched != null ? sampleBaseY(donorCollision) : null;
		float[][] ground = baseY != null ? nearestGround(baseY, walkableTiles(donorTilemap)) : null;
		return buildModel(files, donorModel, grid, height, ramp, touched, baseY, ground, light, edges);
	}

	// ---- visual model -----------------------------------------------------

	/** {@code baseY} is the raw per-tile sample and {@code ground} its filled
	 *  copy ({@link #nearestGround}); both null for a from-scratch build.
	 *  {@code files} is the game the catalogue cuts from, or null for none. */
	static byte[] buildModel(GameFiles files, byte[] donorModel, TilePalette[][] grid, int[][] height, int[][] ramp,
			boolean[][] touched, float[][] baseY, float[][] ground, TerrainLighting light, boolean edges) {
		//Give the model the catalogue's cliff material before anything looks for
		//one. TerrainCatalog.ensureCliffMaterial existed but was never called
		//from anywhere, so the whole CLIFF row was inert: resolveCliffMesh fell
		//straight through to its name hints and every generated cliff took the
		//colour of whatever rock the template region happened to carry. That is
		//exactly the thing the comment inside resolveCliffMesh says it is there
		//to prevent.
		TerrainCatalog.ImportResult cliffImport = TerrainCatalog.ensureCliffMaterial(files, donorModel);
		if (cliffImport != null && cliffImport.model != null) {
			donorModel = cliffImport.model;
		}
		//and vanilla's ADDITIVE molten overlay, if the palette names one
		TerrainCatalog.ImportResult churnImport = TerrainCatalog.ensureChurnMaterial(files, donorModel);
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
		//the corners of every ramp tile painted here, by CliffEdge.key: a face that meets a ramp
		//at one of them is backed even where the ground below it is untouched (F092)
		Set<String> rampCorners = new HashSet<>();

		//per-tile painted-floor Y: level*STEP from scratch, or the retail
		//surface plus the level offset in composite mode (baseline-relative).
		//A painted tile with no sample of its own stands beside its nearest
		//neighbour; the RAW sample stays the reference for walls, because an
		//unsampled UNTOUCHED neighbour is retail geometry - a wall, a cliff
		//face - that already stands there, and a cliff generated against a
		//borrowed height would be a second face in the same place.
		float base0 = baseY != null ? baseFloor(baseY) : 0f;
		float[][] yTop = new float[DIM][DIM];
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				yTop[ty][tx] = ground != null ? floorYOf(ground[ty][tx], height[ty][tx], base0) : height[ty][tx] * STEP;
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
				//where a ramp's slope lands: the one foot its floor, the faces along its sides and its
				//skirts all fall to - the skirt once laid itself to a second reckoning of it
				float rampLo = rd >= 0 ? rampFoot(grid, yTop, baseY, touched, tx, ty, rd, myY) : myY - STEP;
				if (rd >= 0) {
					float cx0 = tx * TILE + ORIGIN, cz0 = ty * TILE + ORIGIN;
					rampCorners.add(CliffEdge.key(cx0, cz0));
					rampCorners.add(CliffEdge.key(cx0 + TILE, cz0));
					rampCorners.add(CliffEdge.key(cx0, cz0 + TILE));
					rampCorners.add(CliffEdge.key(cx0 + TILE, cz0 + TILE));
				}
				if (mi >= 0) {
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
					if (drop > 0.75f) {
						//on a ramp the face's top follows the floor: level along its back,
						//falling with it along its sides, never a step over its own foot
						CliffEdge ce = cliffEdge(tx, ty, dir, nY, myY, rd, rampLo);
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
							ce.tight = rampDir(grid, height, ramp, lx, ly) >= 0 || grid[ly][lx] == TilePalette.PATH
									|| grid[ly][lx] == TilePalette.SAND;
							//Below this face may be retail ground the painter never
							//touched, and it is backed all the same. It was not, on
							//the word that retail ground "backs itself": ground is a
							//floor, and a floor stands behind no face. Every place
							//the visible strip fails to close over such ground was
							//then sky - the ramp's corner (F092), a wall changing
							//depth, an inside corner of two-level walls whose face
							//folds - each found, each excepted, until 2026-10-18,
							//when PaintSealTest's two-depth corner terrain found the
							//third. The premise is gone instead of a fourth exception.
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
						ce2.tight = rd >= 0 || grid[ty][tx] == TilePalette.PATH
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
					//The skirt fills between a ramp's sloping floor and the ground
					//beside it, bounded BY that ground - its top on the floor beside -
					//and is nothing where that ground is lower: the ordinary wall
					//stands there. It was pinned to the
					//ramp's top whatever stood beside it, because bounding it once let
					//more sky through (348px to 2338px over four camera angles) where
					//the wall below was missing; F092 put that wall back, and
					//PaintSealTest now holds the bounded skirt to no sight line through
					//from where anyone can stand. The lane of the same ramp beside it
					//is bounded the same way, so two lanes whose feet differ are walled
					//between their slopes and two that agree get nothing.
					for (int side : (rd == 0 || rd == 1) ? new int[]{2, 3} : new int[]{0, 1}) {
						float[] beside = floorBeside(grid, height, ramp, yTop, baseY, touched, tx, ty, side);
						if (beside == null) {
							continue;
						}
						Quad sk = rampSkirt(tx, ty, side, rd, myY, rampLo, beside);
						if (sk != null) {
							quadsByMesh.computeIfAbsent(cliffMesh, k -> new ArrayList<>()).add(sk);
						}
						//Nothing hangs UNDER the slope. An apron did, from the floor to
						//half a step below its foot, for the sky once seen beneath a
						//ramp from its low side. Wherever ground beside a ramp is lower
						//than its top by more than 0.75, the wall test above stands an
						//ordinary cliff from the ramp's floor down, and every ramp corner
						//is backed - so the apron stood behind a wall that was already
						//there. Measured 2026-10-18 before it was taken out: the same
						//sight lines through, with it and without it, in all 20 builds
						//of PaintSealTest's ten terrains, by every probe there.
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
		//
		//Unbacked: a face standing off the map, with no ground of this region below it, and a
		//face rebuilt on an untouched higher neighbour's side, whose top is retail ground sampled
		//at one point a backing slab could stand proud of - EXCEPT where either meets a ramp,
		//whose corner nothing in the retail ground closes (F092). Every face over untouched LOW
		//ground is backed (see where the wall test builds it).
		List<Quad> backing = quadsByMesh.computeIfAbsent(cliffMesh, k -> new ArrayList<>());
		for (CliffEdge e : cliffEdges) {
			if (e.donorSide && !rampCorners.contains(e.startKey()) && !rampCorners.contains(e.endKey())) {
				continue;
			}
			backing.add(backingWall(e));
		}

		//No posts. Four blades stood on every corner where walls of different depth meet, for the
		//slot between two strips' mitres that a backing wall ending at its own corner left open -
		//measured once as 2 sight lines through the composite terrace. Backing walls now run past
		//their corners and back every face over untouched ground, and on 2026-10-18 the posts were
		//measured closing nothing: the same result, with them and without them, in every terrain
		//PaintSealTest paints - the terrace, a ramp whose low corners meet two depths, a ledge at
		//the foot of a cliff the painter leaves alone - while the post-less build lost 16
		//triangles in each of the last two. They stood where nothing was open.

		emitCliffStrips(cliffEdges, quadsByMesh, cliffMesh);

		// GameFreak-style transition strips along grass<->dirt/sand seams
		if (edgeMesh >= 0) {
			addEdgeStrips(grid, height, touched, yTop, quadsByMesh, edgeMesh);
		}

		addChurnLayer(grid, height, yTop, touched, quadsByMesh, probe);
		addShoreBands(grid, height, yTop, touched, quadsByMesh, terrainMesh, groundMesh, probe);

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
				current = compositeMesh(files, m, g, mi, quads, mi == edgeMesh ? rectsEdge : rects,
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
			MapModelObj.ObjMesh om = meshFromQuads(files, m, g, quads, true);
			byte[] vtx = MapModelObjImporter.buildVertexBytes(m, g, om);
			bakeQuadLighting(m, g, vtx, quads, light);
			current = m.setMeshGeometry(mi, vtx, om.triangles);
		}
		return current;
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
	static byte[] compositeMesh(GameFiles files, BchMapModel m, BchMapModel.MeshGeom g, int mi,
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
			MapModelObj.ObjMesh om = meshFromQuads(files, m, g, quads, rawUv);
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
}
