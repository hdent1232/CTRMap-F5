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
import static ctrmap.formats.tilemap.PaintedRegionBuilder.*;
import static ctrmap.formats.tilemap.PaintedCliffMesh.*;
import static ctrmap.formats.tilemap.PaintedFloorMesh.*;
import static ctrmap.formats.tilemap.PaintedHeights.*;
import static ctrmap.formats.tilemap.PaintedMaterials.*;
import static ctrmap.formats.tilemap.PaintedTiles.*;

/**
 * The painter's the walkable floor and the walls a player cannot pass.
 *
 * <p>One stage of {@link PaintedRegionBuilder}, which was 3750 lines and 106
 * methods in one file. Every method here is exactly the one that was there,
 * moved and not rewritten: the split is about which file you open to read a
 * stage, not about what the pipeline does. The constants and the other stages
 * are imported statically for the same reason - a body that called a helper
 * still calls the same helper.
 *
 * <p>What this is NOT is decoupling. PaintedRegionBuilder was never a god
 * object by coupling: it is pure, it holds no state, and it has six callers.
 * It was simply too long to read.
 */
public final class PaintedCollision {

	private PaintedCollision() {
	}

	// ---- collision --------------------------------------------------------

	static byte[] buildCollision(TilePalette[][] grid, int[][] height, int[][] ramp) {
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
	static void addGeneratedCollision(List<float[]> tris, TilePalette[][] grid, int[][] height,
			int[][] ramp, boolean[][] touched, float[][] baseY, float[][] yTop) {
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
					float rampLo = rd >= 0 ? rampFoot(grid, yTop, baseY, touched, tx, ty, rd, myY) : myY - STEP;
					//Water's floor sits WATER_SINK below the ground, and so must
					//the collision under it: sinking the mesh alone left the
					//player surfing seven units up in the air. The walls below
					//stay at ground level - the bank still blocks there.
					Quad q = floorQuad(grid, height, tx, ty, h, rd, isWet(t) ? myY - WATER_SINK : myY, rampLo);
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

	static void addCliffCollision(List<float[]> tris, int tx, int ty, int dir, float yb, float yt) {
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
			int[][] ramp, boolean[][] touched, float[][] baseY, float[][] ground) {
		List<float[]> tris = new ArrayList<>();
		List<float[]> rects = TileClip.regionRects(touched, TILE, ORIGIN, 0f);
		float base0 = baseFloor(baseY);
		//floors from the filled ground, walls against the raw sample - see buildModel
		float[][] yTop = new float[DIM][DIM];
		float touchedTop = -Float.MAX_VALUE;
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				yTop[ty][tx] = floorYOf(ground[ty][tx], height[ty][tx], base0);
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
}
