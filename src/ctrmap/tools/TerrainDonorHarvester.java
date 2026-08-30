package ctrmap.tools;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.BchModelAppender;
import ctrmap.formats.tilemap.TilePalette;
import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Mines a DONOR MATERIAL for every terrain brush, so the Map Builder can paint
 * any brush on any map - even one whose own model has no such material (an
 * indoor mall has no sand, a cave has no grass). The donor material is injected
 * into the target model at paint time; this tool only picks and verifies which
 * retail material each brush should borrow.
 *
 * <p>Scoring, per candidate mesh whose material name matches the brush's
 * hints: mostly-horizontal surface area (real ground, not a wall or a
 * furniture piece), the material's own texture names matching the hint too,
 * and a preference for large, clean terrain meshes. Every winner is then
 * VERIFIED by actually appending it into three hostile targets (an indoor
 * mall, a cave and a route) and re-parsing the result.
 *
 * <p>Emits {@code oras_terrain.tsv} - metadata only (donor region + mesh +
 * material name); the geometry is cut from the user's own dump at paint time.
 *
 * Usage: java ctrmap.tools.TerrainDonorHarvester &lt;pristineRomfsDir&gt; [outTsv]
 */
public class TerrainDonorHarvester {

	/** Targets every winner must inject into cleanly: indoor mall, cave, route. */
	static final int[] PROBE_TARGETS = {153, 745, 1};

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println("Usage: java ctrmap.tools.TerrainDonorHarvester <pristineRomfsDir> [outTsv]");
			return;
		}
		String romfs = args[0];
		String out = args.length > 1 ? args[1] : "src/ctrmap/resources/oras_terrain.tsv";
		ctrmap.Workspace.GameType game = ctrmap.Workspace.GameType.ORAS;
		GARC gr = new GARC(new File(romfs + ctrmap.Workspace.getArchivePath(ctrmap.Workspace.ArchiveType.FIELD_DATA, game)));
		GARC zo = new GARC(new File(romfs + ctrmap.Workspace.getArchivePath(ctrmap.Workspace.ArchiveType.ZONE_DATA, game)));
		GARC mm = new GARC(new File(romfs + ctrmap.Workspace.getArchivePath(ctrmap.Workspace.ArchiveType.MAP_MATRIX, game)));

		//region -> area (for the texture carry at paint time)
		int[] regionArea = new int[gr.length];
		Arrays.fill(regionArea, -1);
		int zones = Math.min(536, zo.length - 2);
		for (int z = 0; z < zones; z++) {
			try {
				byte[] c = zo.getDecompressedEntry(z);
				byte[] hdr = sub(c, 0);
				int area = u16(hdr, 2), matrix = u16(hdr, 4);
				byte[] mat = mm.getDecompressedEntry(matrix);
				int sub0 = le32(mat, 4);
				int w = u16(mat, sub0 + 4), h = u16(mat, sub0 + 6);
				for (int k = 0; k < w * h; k++) {
					int id = u16(mat, sub0 + 8 + k * 2);
					if (id != 0xFFFF && id < regionArea.length && regionArea[id] < 0) {
						regionArea[id] = area;
					}
				}
			} catch (Exception ignore) {
			}
		}

		List<Cand>[] byBrush = new List[TilePalette.values().length];
		for (int i = 0; i < byBrush.length; i++) {
			byBrush[i] = new ArrayList<>();
		}
		int scanned = 0;
		for (int r = 0; r < gr.length; r++) {
			if (regionArea[r] < 0) {
				continue;
			}
			byte[] modelB = sub(gr.getDecompressedEntry(r), 1);
			if (modelB == null || !BchMapModel.isMapModel(modelB)) {
				continue;
			}
			scanned++;
			BchMapModel m;
			try {
				m = new BchMapModel(modelB);
				if (!m.validate().isEmpty()) {
					continue;
				}
			} catch (RuntimeException ex) {
				continue;
			}
			for (BchMapModel.MeshGeom g : m.geometry()) {
				if (!g.posOk) {
					continue;
				}
				String mat = m.getMaterialName(m.getMeshMaterialIndex(g.meshIndex));
				if (mat == null || mat.isEmpty()) {
					continue;
				}
				String ml = mat.toLowerCase();
				double[] areas = surfaceAreas(m, g.meshIndex);
				if (areas[0] < 2000) {
					continue; //too small to judge as a terrain material
				}
				double flatness = areas[0] / Math.max(1e-6, areas[1]);
				for (TilePalette t : TilePalette.values()) {
					int hitIdx = hintIndex(ml, t);
					if (hitIdx < 0) {
						continue;
					}
					Cand c = new Cand();
					c.brush = t;
					c.region = r;
					c.area = regionArea[r];
					c.mesh = g.meshIndex;
					c.material = mat;
					//earlier hints are the brush's preferred look; flat, big
					//meshes are real ground rather than walls or furniture; a
					//name that is mostly the hint beats one that merely
					//contains it inside a long composite name
					double purity = (double) t.matHints[hitIdx].length() / Math.max(1, ml.length());
					double pasted = ml.contains("pasted__") ? -400 : 0;
					c.score = (10.0 - hitIdx) * 1000 + flatness * 500 + purity * 600 + pasted + Math.log10(areas[0]) * 10;
					byBrush[t.ordinal()].add(c);
				}
			}
		}
		System.out.println("scanned " + scanned + " regions");

		List<String> rows = new ArrayList<>();
		byte[][] targets = new byte[PROBE_TARGETS.length][];
		for (int i = 0; i < PROBE_TARGETS.length; i++) {
			targets[i] = sub(gr.getDecompressedEntry(PROBE_TARGETS[i]), 1);
		}
		for (TilePalette t : TilePalette.values()) {
			if (t == TilePalette.VOID) {
				continue;
			}
			List<Cand> cands = byBrush[t.ordinal()];
			cands.sort((a, b) -> Double.compare(b.score, a.score));
			Cand winner = null;
			for (Cand c : cands) {
				if (winner != null) {
					break;
				}
				byte[] donor = sub(gr.getDecompressedEntry(c.region), 1);
				String injectName = injectName(t);
				boolean ok = true;
				for (byte[] target : targets) {
					try {
						byte[] merged = BchModelAppender.append(target, donor, c.mesh, injectName);
						BchMapModel mm2 = new BchMapModel(merged);
						if (!mm2.validate().isEmpty()) {
							ok = false;
							break;
						}
						if (new BCHFile(merged).errorlevel != 0) {
							ok = false;
							break;
						}
					} catch (Exception ex) {
						ok = false;
						break;
					}
				}
				if (ok) {
					winner = c;
				}
			}
			if (winner == null) {
				System.out.println("  " + t.name() + ": NO usable donor");
				continue;
			}
			System.out.println("  " + t.name() + " <- region " + winner.region + " mesh " + winner.mesh
					+ " '" + winner.material + "' (area " + winner.area + ", score " + Math.round(winner.score) + ")");
			rows.add(t.name() + "\t" + winner.region + "\t" + winner.area + "\t" + winner.mesh
					+ "\t" + winner.material + "\t" + injectName(t));
		}

		try (PrintWriter w = new PrintWriter(out, "UTF-8")) {
			w.println("# Terrain brush donors - generated by ctrmap.tools.TerrainDonorHarvester.");
			w.println("# Lets ANY brush paint on ANY map: the material is injected into the target");
			w.println("# model at paint time and its textures carried into the zone's area.");
			w.println("# Metadata only; geometry is cut from the user's own dump.");
			w.println("# brush\tdonorRegion\tdonorArea\tdonorMesh\tmaterialName\tinjectName");
			for (String row : rows) {
				w.println(row);
			}
		}
		System.out.println("wrote " + rows.size() + " donors -> " + out);
	}

	/** The injected material's name - carries the brush's first hint so the
	 *  painter's existing name-based resolver finds it with no changes. */
	static String injectName(TilePalette t) {
		return "ctr_" + t.matHints[0];
	}

	/**
	 * Index of the first hint this material name matches AS A WORD. A bare
	 * substring test is a trap: "suna" (sand) matches "tsu-NA-gi", a CLIFF
	 * material, and the sand brush would quietly borrow a rock face. Names are
	 * split on separators and digits, and a token matches when it equals the
	 * hint or begins/ends with it.
	 */
	static int hintIndex(String matLower, TilePalette t) {
		String[] tokens = matLower.split("[^a-z]+");
		for (int i = 0; i < t.matHints.length; i++) {
			String h = t.matHints[i];
			for (String tok : tokens) {
				if (tok.isEmpty()) {
					continue;
				}
				if (tok.equals(h) || tok.startsWith(h) || tok.endsWith(h)) {
					return i;
				}
			}
		}
		return -1;
	}

	/** {up-facing area, total area} of a mesh - a floor is nearly all up-facing. */
	static double[] surfaceAreas(BchMapModel m, int meshIndex) {
		float[][] pos = m.getVertexPositions(meshIndex);
		int[] tris = m.getTriangles(meshIndex);
		double up = 0, total = 0;
		for (int t = 0; t + 2 < tris.length; t += 3) {
			int a = tris[t], b = tris[t + 1], c = tris[t + 2];
			if (a >= pos.length || b >= pos.length || c >= pos.length) {
				continue;
			}
			double ux = pos[b][0] - pos[a][0], uy = pos[b][1] - pos[a][1], uz = pos[b][2] - pos[a][2];
			double vx = pos[c][0] - pos[a][0], vy = pos[c][1] - pos[a][1], vz = pos[c][2] - pos[a][2];
			double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
			up += Math.abs(ny) * 0.5;
			total += len * 0.5;
		}
		return new double[]{up, total};
	}

	static final class Cand {

		TilePalette brush;
		int region, area, mesh;
		String material;
		double score;
	}

	static byte[] sub(byte[] c, int i) {
		if (c == null || c.length < 8) {
			return null;
		}
		int count = (c[2] & 0xFF) | ((c[3] & 0xFF) << 8);
		if (i >= count) {
			return null;
		}
		int o0 = le32(c, 4 + i * 4), o1 = le32(c, 4 + (i + 1) * 4);
		if (o0 < 0 || o1 > c.length || o1 < o0) {
			return null;
		}
		return Arrays.copyOfRange(c, o0, o1);
	}

	static int u16(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
	}

	static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
