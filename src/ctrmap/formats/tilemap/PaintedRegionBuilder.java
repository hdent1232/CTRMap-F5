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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static ctrmap.formats.LittleEndian.f32;
import static ctrmap.formats.tilemap.PaintedCliffMesh.*;
import static ctrmap.formats.tilemap.PaintedCollision.*;
import static ctrmap.formats.tilemap.PaintedFloorMesh.*;
import static ctrmap.formats.tilemap.PaintedHeights.*;
import static ctrmap.formats.tilemap.PaintedMaterials.*;
import static ctrmap.formats.tilemap.PaintedTiles.*;

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
	/** The ramp grid's value for a tile that is not a ramp; 0..3 is the way down (E, W, S, N). */
	public static final int NO_RAMP = -1;
	static final String[] DIR_NAMES = {"east", "west", "south", "north"};


	/** A textured quad (4 corners TL/TR/BL/BR) destined for one mesh. */
	static final class Quad {

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
	 * @param ramp per-tile ramp direction ({@link #NO_RAMP} = flat, else the way
	 *             DOWN: 0 E, 1 W, 2 S, 3 N; null = no ramps); a ramp tile slopes
	 *             from its level to that neighbour's floor, replacing the cliff
	 *             so the player walks it. Stair brushes slope on their own.
	 */
	public static RegionFactory.BlankContent build(byte[] donorModel, TilePalette[][] grid, int[][] height, int[][] ramp, TerrainLighting light) {
		return build(donorModel, grid, height, ramp, light, true);
	}

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
	 */
	public static RegionFactory.BlankContent buildComposite(byte[] donorModel, byte[] donorCollision, byte[] donorTilemap,
			TilePalette[][] grid, int[][] height, int[][] ramp, boolean[][] touched, TerrainLighting light, boolean edges) {
		return buildComposite(null, donorModel, donorCollision, donorTilemap, grid, height, ramp, touched, light, edges);
	}

	/** The composite build cutting the catalogue's materials from the handed game; see {@link #build(GameFiles, byte[], TilePalette[][], int[][], int[][], TerrainLighting, boolean)}. */
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
	 * the tilemap only to fill unsampled tiles from walkable ground).
	 */
	public static byte[] buildModelOnly(byte[] donorModel, byte[] donorCollision, byte[] donorTilemap, TilePalette[][] grid,
			int[][] height, int[][] ramp, boolean[][] touched, TerrainLighting light, boolean edges) {
		return buildModelOnly(null, donorModel, donorCollision, donorTilemap, grid, height, ramp, touched, light, edges);
	}

	/** The model-only build cutting the catalogue's materials from the handed game; see {@link #build(GameFiles, byte[], TilePalette[][], int[][], int[][], TerrainLighting, boolean)}. */
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
				if (mi >= 0) {
					float rampLo = rd >= 0 ? rampFoot(grid, yTop, baseY, touched, tx, ty, rd, myY) : myY - STEP;
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
							ce.tight = rampDir(grid, height, ramp, lx, ly) >= 0 || grid[ly][lx] == TilePalette.PATH
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
								&& height[sy3][sx3] == h
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

	// ---- generic vertex codec (clip-vertex reconstruction) ----------------

	/** Bytes per PICA component type: 0=s8, 1=u8, 2=s16, 3=float. */
	static final int[] COMP_BYTES = {1, 1, 2, 4};



	// ---- edge transition strips (the GameFreak "blend" look) --------------

	static final int GRP_GRASS = 1, GRP_DIRT = 2;
	/** Half-visual constants: strip reaches EDGE_W world units onto the lower
	 *  (dirt/sand) side of a seam, lifted EDGE_LIFT above ground to overlay it. */
	static final float EDGE_W = 9f, EDGE_LIFT = 0.6f;

	/** [emitted, rejected-as-flat] ramp skirts, for diagnosis. */
	static final int[] SKIRTS = new int[3];

	/** Ground texture repeats: one per 72 world units, i.e. per four tiles. */
	static final float FLOOR_UV = 1f / 72f;

	/** How far the water surface sits below the ground it runs through. */
	static final float WATER_SINK = 7f;
















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




	/** How far a bank reaches out over the water before meeting its surface. */
	static final float BANK_RUN = 7f;




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

































	/** Fraction of a mesh's surface area that faces up; a floor ~1, a wall ~0. */
	static final double MIN_GROUND_FLATNESS = 0.5;







	/**
	 * Materials whose texture is mostly transparent - sprite strips, decals and
	 * decoration atlases. Measured game-wide by
	 * {@link ctrmap.tools.GroundMaterialAudit}; anything not listed is treated as
	 * usable, so imported and user-supplied materials are never restricted.
	 */
	static Set<String> spriteMaterials;












}
