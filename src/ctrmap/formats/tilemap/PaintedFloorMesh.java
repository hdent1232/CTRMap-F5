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
import static ctrmap.formats.tilemap.PaintedCollision.*;
import static ctrmap.formats.tilemap.PaintedHeights.*;
import static ctrmap.formats.tilemap.PaintedMaterials.*;
import static ctrmap.formats.tilemap.PaintedTiles.*;

/**
 * The painter's the floor quads, their edges, the water and the shore bands.
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
public final class PaintedFloorMesh {

	private PaintedFloorMesh() {
	}

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
					default: out[c++] = f32(raw, o); break;
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

	/** Coarse terrain family for edge blending (0 = never edged). */
	static int terrainGroup(TilePalette t) {
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

	static boolean isGrassDirt(int ga, int gb) {
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
	static Quad edgeQuadEW(int tx, int ty, float y, boolean grassIsWest) {
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
	static Quad edgeQuadNS(int tx, int ty, float y, boolean grassIsNorth) {
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
	static void fixWindingTowards(Quad q, float wx, float wy, float wz) {
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

	static void fixWindingOut(Quad q, float nx, float nz) {
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
	static void fixWindingUp(Quad q) {
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
	static MapModelObj.ObjMesh meshFromQuads(GameFiles files, BchMapModel model, BchMapModel.MeshGeom g, List<Quad> quads, boolean rawUv) {
		float[] scale = rawUv ? new float[]{1f, 1f} : measureUvScale(files, model, g);
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
	static Quad backingWall(CliffEdge e) {
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
	static Quad cornerPost(float x, float z, float yTop, float yBot,
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
	static Quad rampSkirt(int tx, int ty, int side, int rd, float yHi, float yLo) {
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
	static Quad rampApron(int tx, int ty, int side, int rd, float yHi, float yLo) {
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
	static boolean isWet(TilePalette t) {
		return t == TilePalette.WATER || t == TilePalette.WATERFALL;
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

	/**
	 * The water's edge along ONE continuous land/water boundary, as a band of
	 * wet sand fading into the water rather than a straight cut between them.
	 *
	 * <p>The same shape as {@link #emitChain} and for the same reason - build
	 * the polyline, round it, take an outward direction per vertex, then sweep
	 * bands along it - but simpler, because a shore has no fall to step down and
	 * so no stack of faces: it is bank, then flat band, then water.
	 *
	 * <p>A chain of one edge is dropped: with two vertices there is no interior
	 * point to round and nothing to smooth, so it would be emitted as the very
	 * straight cut this exists to avoid.
	 */
	static void emitShoreChain(List<ShoreEdge> chain, TilePalette[][] grid, int[][] height,
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

	/**
	 * A sloped bank: the top edge on the tile boundary at ground level, the
	 * bottom edge out over the water at the water's surface. Textured by world
	 * position like the ground it continues, so the bank is the same grass or
	 * sand running down to the water rather than a band stuck on afterwards.
	 */
	static Quad bankQuad(float ax, float az, float bx, float bz,
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
	static Quad flatBand(float ax, float az, float bx, float bz,
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

	/**
	 * The walkable top of one tile: a single quad over the tile's square, at
	 * {@code yHi}, with world-projected UVs and baked corner AO.
	 *
	 * <p>A RAMP tile is the same quad with two of its corners dropped. Which two
	 * is the whole of {@code rd}: the pair on the edge the ramp descends over
	 * goes to {@code yLo}, so the tile is a plane tilted the way the player
	 * walks it. {@code rd} of {@link #NO_RAMP} leaves all four at {@code yHi}
	 * and the tile is flat.
	 *
	 * @param h  the tile's height LEVEL, used only to score its corners' AO
	 *           against the neighbours - the geometry comes from yHi/yLo
	 * @param rd the way DOWN (0 east, 1 west, 2 south, 3 north), or
	 *           {@link #NO_RAMP} for a flat tile
	 */
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
}
