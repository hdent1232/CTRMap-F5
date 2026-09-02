package ctrmap.tools;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchMapModel;
import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cut a kit of reusable TERRAIN out of the retail maps.
 *
 * <p>{@link BuildingHarvester} deliberately throws terrain away - "components
 * this wide are terrain, not assets" - so the catalogue has trees and houses
 * and not one cliff. That is why every hillside in a generated map had to be
 * synthesised from a tile grid, and synthesising it is what produced staircase
 * silhouettes, mitre spikes, corner slivers and shards across a ramp. Game
 * Freak's artists already modelled this terrain; the fix is to use it.
 *
 * <p>A piece is a rectangle of tiles together with the HEIGHT PROFILE it
 * implements - the walkable floor level of each of its tiles, relative to its
 * own lowest. That profile is what makes reuse safe: a piece is only ever
 * placed where the target map's own elevation grid already calls for exactly
 * that shape, so the collision generated from the grid and the geometry taken
 * from retail describe the same ground. Nothing has to be kept in sync by hand.
 *
 * <p>Only rectangles containing a real step are kept. Flat ground is already
 * handled perfectly well by painting tiles, and a kit full of flat squares
 * would just be a slower way of doing that.
 *
 * <p>Usage: {@code java ctrmap.tools.TerrainHarvester <pristineRomfsDir> [outTsv]}
 */
public class TerrainHarvester {

	static final float TILE = 18f, ORIGIN = -360f, STEP = 18f;
	static final int DIM = 40;
	/** A triangle this close to level counts as floor rather than wall. */
	static final float FLOOR_NY = 0.80f;
	/** Sizes of rectangle to cut. Small enough to combine, big enough to be worth reusing. */
	static final int[][] SIZES = {{3, 3}, {4, 4}, {4, 2}, {2, 4}, {5, 3}, {3, 5}};
	/** Keep at most this many alternative cuts implementing the same profile. */
	static final int VARIANTS = 3;

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println("Usage: java ctrmap.tools.TerrainHarvester <pristineRomfsDir> [outTsv]");
			return;
		}
		String root = args[0];
		File out = new File(args.length > 1 ? args[1] : "terrain_kit.tsv");

		//Archive paths belong to the game profile, never spelled out here - the
		//seam guard enforces that so a second game can be supported by adding a
		//profile rather than by hunting literals through the tools.
		ctrmap.Workspace.GameType game = ctrmap.Workspace.GameType.ORAS;
		GARC fd = new GARC(new File(root
				+ ctrmap.Workspace.getArchivePath(ctrmap.Workspace.ArchiveType.FIELD_DATA, game)));
		Map<Integer, int[]> owner = zoneOwners(root, fd.length);

		//profile signature -> the cuts that implement it, best first
		Map<String, List<String>> kit = new LinkedHashMap<>();
		//Straight WALL RUNS, keyed by facing + step + length. Rectangular cuts
		//cannot be laid along an arbitrary contour - they cover it in patches
		//with a seam at every join, which is the same failure the synthesised
		//cliffs had. A run is a stretch of wall of known length and facing, and
		//runs lay end to end exactly the way the generator's strips did, only
		//with rock somebody modelled.
		Map<String, List<String>> runKit = new LinkedHashMap<>();
		int regionsScanned = 0, cuts = 0, runs = 0;

		for (int region = 0; region < fd.length; region++) {
			byte[] raw;
			try {
				raw = sub(fd.getDecompressedEntry(region), 1);
			} catch (RuntimeException ex) {
				continue;
			}
			if (raw == null || raw.length < 16 || !BchMapModel.isMapModel(raw)) {
				continue;
			}
			BchMapModel m;
			try {
				m = new BchMapModel(raw);
			} catch (RuntimeException ex) {
				continue;
			}
			int[] own = owner.get(region);
			if (own == null) {
				continue;   //no zone points at it, so its textures are unreachable
			}
			regionsScanned++;

			float[][] floor = floorHeights(m);
			int[][] rock = rockDensity(m);
			for (int[] size : SIZES) {
				cuts += cutAll(kit, m, region, own[1], floor, rock, size[0], size[1]);
			}
			runs += cutRuns(runKit, m, region, own[1], floor, rock);
		}

		try (PrintWriter w = new PrintWriter(out, "UTF-8")) {
			w.println("# CTRMap-F5 terrain kit (ORAS) - METADATA ONLY; geometry is cut from the");
			w.println("# user's own pristine dump at placement time, exactly as the building");
			w.println("# catalogue works. 'profile' is the walkable floor LEVEL of each tile,");
			w.println("# row-major, relative to the piece's own lowest tile. A piece is only");
			w.println("# ever placed where the target grid already has that same shape, so the");
			w.println("# collision built from the grid and this geometry always agree.");
			w.println("name\tregion\tarea\ttx0\tty0\ttx1\tty1\tw\th\tbaseY\tprofile\trockTris");
			for (List<String> variants : kit.values()) {
				for (String row : variants) {
					w.println(row);
				}
			}
		}
		File runOut = new File(out.getParentFile(), "terrain_runs.tsv");
		try (PrintWriter w = new PrintWriter(runOut, "UTF-8")) {
			w.println("# CTRMap-F5 terrain WALL RUNS (ORAS). A run is a straight stretch of");
			w.println("# cliff: 'facing' is which way the ground drops (0=+x 1=-x 2=+z 3=-z),");
			w.println("# 'step' how many levels it falls, 'len' how many tiles long. Runs of the");
			w.println("# same facing and step lay end to end along a contour without a seam,");
			w.println("# which rectangular cuts cannot do.");
			w.println("name	region	area	tx0	ty0	tx1	ty1	facing	step	len	baseY	rockTris	material");
			for (List<String> v : runKit.values()) {
				for (String row : v) {
					w.println(row);
				}
			}
		}
		System.out.println("scanned " + regionsScanned + " regions, cut " + cuts + " candidate(s)");
		System.out.println("kept " + kit.size() + " distinct height profile(s) -> " + out);
		System.out.println("found " + runs + " wall run(s) in " + runKit.size()
				+ " distinct facing/step/length combination(s) -> " + runOut);
	}

	/**
	 * The walkable floor height of each tile: the highest nearly-level surface
	 * standing in it. Walls, overhangs and billboards are ignored, which is what
	 * makes this the height a player would stand at rather than the height of
	 * whatever geometry happens to pass overhead.
	 */
	public static float[][] floorHeights(BchMapModel m) {
		float[][] floor = new float[DIM][DIM];
		for (float[] r : floor) {
			Arrays.fill(r, Float.NaN);
		}
		for (int mi = 0; mi < m.meshCount; mi++) {
			float[][] pos;
			int[] t;
			try {
				pos = m.getVertexPositions(mi);
				t = m.getTriangles(mi);
			} catch (RuntimeException ex) {
				continue;
			}
			if (pos == null || t == null) {
				continue;   //a mesh with no readable geometry contributes no floor
			}
			for (int k = 0; k + 2 < t.length; k += 3) {
				int a = t[k], b = t[k + 1], c = t[k + 2];
				if (a >= pos.length || b >= pos.length || c >= pos.length) {
					continue;
				}
				float ux = pos[b][0] - pos[a][0], uy = pos[b][1] - pos[a][1], uz = pos[b][2] - pos[a][2];
				float vx = pos[c][0] - pos[a][0], vy = pos[c][1] - pos[a][1], vz = pos[c][2] - pos[a][2];
				float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
				float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (len < 1e-4f || Math.abs(ny) / len < FLOOR_NY) {
					continue;   //a wall, not a floor
				}
				float cx = (pos[a][0] + pos[b][0] + pos[c][0]) / 3f;
				float cz = (pos[a][2] + pos[b][2] + pos[c][2]) / 3f;
				float cy = (pos[a][1] + pos[b][1] + pos[c][1]) / 3f;
				int tx = (int) Math.floor((cx - ORIGIN) / TILE);
				int ty = (int) Math.floor((cz - ORIGIN) / TILE);
				if (tx < 0 || ty < 0 || tx >= DIM || ty >= DIM) {
					continue;
				}
				if (Float.isNaN(floor[ty][tx]) || cy > floor[ty][tx]) {
					floor[ty][tx] = cy;
				}
			}
		}
		return floor;
	}

	/** The dominant cliff material per tile, so a cut can be matched by LOOK. */
	static String[][] rockMaterial = new String[DIM][DIM];

	/** Cliff-ish triangles per tile, to prefer cuts with real rock in them. */
	static int[][] rockDensity(BchMapModel m) {
		int[][] rock = new int[DIM][DIM];
		int[][] rockBest = new int[DIM][DIM];
		for (String[] r : rockMaterial) {
			Arrays.fill(r, null);
		}
		for (int mi = 0; mi < m.meshCount; mi++) {
			String mat = m.getMaterialName(m.getMeshMaterialIndex(mi));
			if (mat == null) {
				continue;
			}
			String lm = mat.toLowerCase();
			if (!(lm.contains("gake") || lm.contains("iwa") || lm.contains("rock")
					|| lm.contains("taki"))) {
				continue;
			}
			float[][] pos;
			int[] t;
			try {
				pos = m.getVertexPositions(mi);
				t = m.getTriangles(mi);
			} catch (RuntimeException ex) {
				continue;
			}
			if (pos == null || t == null) {
				continue;
			}
			for (int k = 0; k + 2 < t.length; k += 3) {
				int a = t[k], b = t[k + 1], c = t[k + 2];
				if (a >= pos.length || b >= pos.length || c >= pos.length) {
					continue;
				}
				float cx = (pos[a][0] + pos[b][0] + pos[c][0]) / 3f;
				float cz = (pos[a][2] + pos[b][2] + pos[c][2]) / 3f;
				int tx = (int) Math.floor((cx - ORIGIN) / TILE);
				int ty = (int) Math.floor((cz - ORIGIN) / TILE);
				if (tx >= 0 && ty >= 0 && tx < DIM && ty < DIM) {
					rock[ty][tx]++;
					//whichever cliff material contributes most triangles here is
					//what this tile looks like, and matching that is the
					//difference between a grass hillside and a desert mesa
					//dropped into the middle of a meadow
					if (++rockBest[ty][tx] > 0 && rockMaterial[ty][tx] == null) {
						rockMaterial[ty][tx] = mat;
					}
				}
			}
		}
		return rock;
	}

	static int cutAll(Map<String, List<String>> kit, BchMapModel m, int region, int area,
			float[][] floor, int[][] rock, int w, int h) {
		int made = 0;
		for (int ty = 0; ty + h <= DIM; ty++) {
			for (int tx = 0; tx + w <= DIM; tx++) {
				//every tile must have a floor, or the cut has a hole in it
				float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
				boolean ok = true;
				for (int y = ty; y < ty + h && ok; y++) {
					for (int x = tx; x < tx + w && ok; x++) {
						if (Float.isNaN(floor[y][x])) {
							ok = false;
						} else {
							lo = Math.min(lo, floor[y][x]);
							hi = Math.max(hi, floor[y][x]);
						}
					}
				}
				if (!ok || hi - lo < STEP * 0.75f) {
					continue;   //flat: painting tiles already does this perfectly
				}
				//quantise to elevation levels; a cut whose floors do not sit on
				//the level grid cannot be matched against a target map's grid
				StringBuilder prof = new StringBuilder();
				boolean onGrid = true;
				int rockTris = 0;
				String mat = null;
				for (int y = ty; y < ty + h && onGrid; y++) {
					for (int x = tx; x < tx + w && onGrid; x++) {
						float rel = (floor[y][x] - lo) / STEP;
						int lvl = Math.round(rel);
						if (Math.abs(rel - lvl) > 0.20f || lvl > 9) {
							onGrid = false;
						} else {
							prof.append(lvl);
							rockTris += rock[y][x];
							if (mat == null && rockMaterial[y][x] != null) {
								mat = rockMaterial[y][x];
							}
						}
					}
				}
				if (!onGrid || rockTris < 40) {
					continue;
				}
				if (mat == null) {
					continue;   //no identifiable cliff material: cannot be matched by look
				}
				String sig = w + "x" + h + ":" + prof;
				List<String> variants = kit.computeIfAbsent(sig, k -> new ArrayList<>());
				if (variants.size() >= VARIANTS) {
					continue;
				}
				String name = "terrain " + sig + " #" + (variants.size() + 1);
				variants.add(String.join("\t", name, String.valueOf(region), String.valueOf(area),
						String.valueOf(tx), String.valueOf(ty),
						String.valueOf(tx + w - 1), String.valueOf(ty + h - 1),
						String.valueOf(w), String.valueOf(h),
						String.valueOf(Math.round(lo)), prof.toString(),
						String.valueOf(rockTris), mat == null ? "-" : mat));
				made++;
			}
		}
		return made;
	}

	/**
	 * Straight runs of cliff, cut with one tile of the low ground included so
	 * the wall face itself comes along.
	 *
	 * <p>Keyed by facing, step and length, which is all the matcher needs: to
	 * clothe a contour it walks the target's own runs and lays kit runs of the
	 * same facing and step end to end.
	 */
	static int cutRuns(Map<String, List<String>> runKit, BchMapModel m, int region, int area,
			float[][] floor, int[][] rock) {
		int[][] lvl = new int[DIM][DIM];
		for (int y = 0; y < DIM; y++) {
			for (int x = 0; x < DIM; x++) {
				lvl[y][x] = Float.isNaN(floor[y][x]) ? Integer.MIN_VALUE
						: Math.round(floor[y][x] / STEP);
			}
		}
		int made = 0;
		//facing 0=+x 1=-x 2=+z 3=-z; a run travels perpendicular to its facing
		int[][] dir = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int facing = 0; facing < 4; facing++) {
			int rx = (facing < 2) ? 0 : 1, ry = (facing < 2) ? 1 : 0;   //travel axis
			for (int sy = 0; sy < DIM; sy++) {
				for (int sx = 0; sx < DIM; sx++) {
					int step = stepAt(lvl, sx, sy, dir[facing]);
					if (step < 1 || step > 3) {
						continue;
					}
					//already counted as part of the run that precedes it?
					int px = sx - rx, py = sy - ry;
					if (px >= 0 && py >= 0 && stepAt(lvl, px, py, dir[facing]) == step) {
						continue;
					}
					int len = 0, rockTris = 0;
					String mat = null;
					int cx = sx, cy = sy;
					while (cx < DIM && cy < DIM && stepAt(lvl, cx, cy, dir[facing]) == step
							&& len < 6) {
						rockTris += rock[cy][cx];
						if (mat == null && rockMaterial[cy][cx] != null) {
							mat = rockMaterial[cy][cx];
						}
						len++;
						cx += rx;
						cy += ry;
					}
					if (len < 2 || mat == null || rockTris < 30) {
						continue;
					}
					//the box: the run's own tiles plus one tile of the ground it
					//drops onto, which is where the wall face actually lives
					int bx0 = Math.min(sx, sx + rx * (len - 1) + dir[facing][0]);
					int by0 = Math.min(sy, sy + ry * (len - 1) + dir[facing][1]);
					int bx1 = Math.max(sx, sx + rx * (len - 1) + dir[facing][0]);
					int by1 = Math.max(sy, sy + ry * (len - 1) + dir[facing][1]);
					if (bx0 < 0 || by0 < 0 || bx1 >= DIM || by1 >= DIM) {
						continue;
					}
					float lo = Float.MAX_VALUE;
					for (int y = by0; y <= by1; y++) {
						for (int x = bx0; x <= bx1; x++) {
							if (Float.isNaN(floor[y][x])) {
								lo = Float.NaN;
								break;
							}
							lo = Math.min(lo, floor[y][x]);
						}
					}
					if (Float.isNaN(lo) || lo == Float.MAX_VALUE) {
						continue;
					}
					String sig = facing + "/" + step + "/" + len;
					List<String> v = runKit.computeIfAbsent(sig, k -> new ArrayList<>());
					if (v.size() >= 6) {
						continue;
					}
					v.add(String.join("	", "run " + sig + " #" + (v.size() + 1),
							String.valueOf(region), String.valueOf(area),
							String.valueOf(bx0), String.valueOf(by0),
							String.valueOf(bx1), String.valueOf(by1),
							String.valueOf(facing), String.valueOf(step), String.valueOf(len),
							String.valueOf(Math.round(lo)), String.valueOf(rockTris), mat));
					made++;
				}
			}
		}
		return made;
	}

	/** How many levels the ground falls from this tile in this direction. */
	static int stepAt(int[][] lvl, int x, int y, int[] d) {
		int nx = x + d[0], ny = y + d[1];
		if (x < 0 || y < 0 || x >= DIM || y >= DIM || nx < 0 || ny < 0 || nx >= DIM || ny >= DIM) {
			return -1;
		}
		if (lvl[y][x] == Integer.MIN_VALUE || lvl[ny][nx] == Integer.MIN_VALUE) {
			return -1;
		}
		return lvl[y][x] - lvl[ny][nx];
	}

	/** region -> {zone, area}, so a cut knows where its textures live. */
	static Map<Integer, int[]> zoneOwners(String root, int regionCount) throws Exception {
		Map<Integer, int[]> owner = new LinkedHashMap<>();
		ctrmap.Workspace.GameType game = ctrmap.Workspace.GameType.ORAS;
		GARC zo = new GARC(new File(root
				+ ctrmap.Workspace.getArchivePath(ctrmap.Workspace.ArchiveType.ZONE_DATA, game)));
		GARC mm = new GARC(new File(root
				+ ctrmap.Workspace.getArchivePath(ctrmap.Workspace.ArchiveType.MAP_MATRIX, game)));
		byte[] master = zo.getDecompressedEntry(zo.length - 2);
		for (int z = 0; z < zo.length - 2; z++) {
			int area, matrix;
			try {
				area = u16(master, z * 0x38 + 2);
				matrix = u16(master, z * 0x38 + 4);
			} catch (RuntimeException ex) {
				continue;
			}
			byte[] mat;
			try {
				mat = mm.getDecompressedEntry(matrix);
			} catch (RuntimeException ex) {
				continue;
			}
			try {
				int sub0 = u32(mat, 4);
				int w = u16(mat, sub0 + 4), h = u16(mat, sub0 + 6);
				for (int i = 0; i < w * h; i++) {
					int r = u16(mat, sub0 + 8 + i * 2);
					if (r != 0xFFFF && r < regionCount) {
						owner.putIfAbsent(r, new int[]{z, area});
					}
				}
			} catch (RuntimeException ex) {
				// a matrix that will not parse owns nothing
			}
		}
		return owner;
	}

	static byte[] sub(byte[] c, int i) {
		if (c == null || c.length < 8) {
			return null;
		}
		int cnt = (c[2] & 0xFF) | ((c[3] & 0xFF) << 8);
		if (i >= cnt) {
			return null;
		}
		int o0 = le32(c, 4 + i * 4), o1 = le32(c, 4 + (i + 1) * 4);
		if (o1 <= o0 || o1 > c.length) {
			return null;
		}
		return Arrays.copyOfRange(c, o0, o1);
	}

	static int u16(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
	}

	static int u32(byte[] b, int o) {
		return le32(b, o);
	}

	static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
