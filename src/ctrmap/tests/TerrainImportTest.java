package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.BchModelAppender;
import ctrmap.formats.h3d.RegionFactory;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TerrainCatalog;
import ctrmap.formats.tilemap.TerrainLighting;
import ctrmap.formats.tilemap.TilePalette;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Proves EVERY terrain brush can paint on ANY map: for each hostile target (an
 * indoor mall with no outdoor materials, a cave, a route, a ship) and each
 * brush, the donor material imports, the model still parses/renders, the brush
 * then resolves to the imported material, a painted patch builds, and the
 * painted vertices' baked lighting round-trips in the mesh's OWN colour format
 * (the s8/u8 bug that rendered paint black).
 *
 * Usage: java ctrmap.tests.TerrainImportTest &lt;path-to-a039-garc&gt;
 */
public class TerrainImportTest {

	static final int DIM = 40;
	/** Mauville mall (no outdoor materials), Granite Cave, Route 101, Route 103. */
	static final int[] TARGETS = {153, 745, 1, 15};

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/3/9");
		GARC gr = new GARC(garcFile);
		//the donor cut reads the pristine dump through the workspace paths -
		//point them at the dump this test was handed (a/0/3/9 -> its romfs root)
		ctrmap.Workspace.game = ctrmap.Workspace.GameType.ORAS;
		//a/0/3/9 -> up four levels is the romfs root the archive paths hang off
		ctrmap.Workspace.GAMEDIR_PATH = garcFile.getParentFile().getParentFile()
				.getParentFile().getParentFile().getAbsolutePath();
		ctrmap.Workspace.WORKSPACE_PATH = System.getProperty("java.io.tmpdir") + "/ctrmap_terrainimport";
		ctrmap.Workspace.temp = new File(ctrmap.Workspace.WORKSPACE_PATH, "temp");
		ctrmap.Workspace.temp.mkdirs();
		int failures = 0, imported = 0, already = 0, colourChecked = 0;

		if (TerrainCatalog.donors().isEmpty()) {
			System.out.println("FAIL no terrain donors loaded (oras_terrain.tsv missing?)");
			System.exit(1);
		}
		System.out.println("donors: " + TerrainCatalog.donors().size());

		for (int target : TARGETS) {
			byte[] model = sub(gr.getDecompressedEntry(target), 1);
			if (model == null || !BchMapModel.isMapModel(model)) {
				System.out.println("FAIL target " + target + " unusable");
				failures++;
				continue;
			}
			for (TilePalette brush : TilePalette.brushes()) {
				try {
					boolean had = PaintedRegionBuilder.hasMaterialFor(new BchMapModel(model), brush);
					TerrainCatalog.ImportResult r = TerrainCatalog.ensureMaterial(model, brush);
					if (had) {
						already++;
						if (r.injected) {
							throw new IllegalStateException("imported over an existing material");
						}
					} else {
						if (!r.injected) {
							throw new IllegalStateException("no donor imported (brush unusable on this map)");
						}
						imported++;
					}
					byte[] m2 = r.model;
					if (!new BchMapModel(m2).validate().isEmpty()) {
						throw new IllegalStateException("model parse broke after import");
					}
					if (new BCHFile(m2).errorlevel != 0) {
						throw new IllegalStateException("render parser rejected the imported model");
					}
					if (!PaintedRegionBuilder.hasMaterialFor(new BchMapModel(m2), brush)) {
						throw new IllegalStateException("brush still does not resolve after import");
					}
					if (r.injected) {
						checkImportIsInert(model, m2, brush);
					}
					//idempotent: a second import must change nothing
					TerrainCatalog.ImportResult again = TerrainCatalog.ensureMaterial(m2, brush);
					if (again.injected || !Arrays.equals(again.model, m2)) {
						throw new IllegalStateException("import is not idempotent");
					}
					//paint with it and check the baked colours in the mesh's own format
					colourChecked += paintAndCheck(m2, brush);
				} catch (RuntimeException ex) {
					failures++;
					System.out.println("FAIL target " + target + " brush " + brush.name() + ": " + ex.getMessage());
					if (failures > 10) {
						break;
					}
				}
			}
			System.out.println("  target " + target + " swept");
		}
		System.out.println("imports " + imported + ", already present " + already
				+ ", painted meshes colour-checked " + colourChecked);
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT (" + failures + ")");
		if (failures > 0) {
			System.exit(1);
		}
	}

	/**
	 * An import must give the map a MATERIAL and nothing else: every material the
	 * map already had must keep every one of its triangles, and the new material
	 * must arrive empty.
	 *
	 * <p>This exists because of a real bug. The appender does not put the new mesh
	 * last - it inserts it in render-layer order and shifts the meshes after it -
	 * but the import blanked "the last mesh" on the assumption that it was the new
	 * one. So it wiped an innocent retail mesh (Route 102 lost its shadow layer)
	 * and left the donor's own terrain, 214 triangles of another map's stairs,
	 * standing in the middle of the player's map. Everything still parsed and
	 * rendered, so no other check noticed.
	 */
	private static void checkImportIsInert(byte[] before, byte[] after, TilePalette brush) {
		Map<String, Integer> was = trisByMaterial(new BchMapModel(before));
		Map<String, Integer> now = trisByMaterial(new BchMapModel(after));
		for (Map.Entry<String, Integer> e : was.entrySet()) {
			Integer n = now.get(e.getKey());
			if (n == null) {
				throw new IllegalStateException("import for " + brush + " removed material " + e.getKey());
			}
			if (!n.equals(e.getValue())) {
				throw new IllegalStateException("import for " + brush + " changed existing material "
						+ e.getKey() + ": " + e.getValue() + " -> " + n + " triangles");
			}
		}
		for (Map.Entry<String, Integer> e : now.entrySet()) {
			if (was.containsKey(e.getKey())) {
				continue;
			}
			if (e.getValue() > 1) {
				throw new IllegalStateException("import for " + brush + " brought the donor's geometry along: "
						+ e.getKey() + " has " + e.getValue() + " triangles (want an empty mesh)");
			}
		}
	}

	/** Triangle count per material name. */
	private static Map<String, Integer> trisByMaterial(BchMapModel m) {
		Map<String, Integer> out = new HashMap<>();
		for (int i = 0; i < m.meshCount; i++) {
			String n = m.getMaterialName(m.getMeshMaterialIndex(i));
			int t = 0;
			try {
				t = m.getTriangles(i).length / 3;
			} catch (RuntimeException ignore) {
			}
			Integer prev = out.get(n);
			out.put(n, prev == null ? t : prev + t);
		}
		return out;
	}

	/** Paints a patch and verifies the generated vertex colours decode to the
	 *  lighting value in whatever format the mesh uses. Returns meshes checked. */
	static int paintAndCheck(byte[] model, TilePalette brush) {
		TilePalette[][] grid = new TilePalette[DIM][DIM];
		for (TilePalette[] row : grid) {
			Arrays.fill(row, TilePalette.GRASS);
		}
		boolean[][] touched = new boolean[DIM][DIM];
		for (int y = 10; y <= 13; y++) {
			for (int x = 10; x <= 13; x++) {
				grid[y][x] = brush;
				touched[y][x] = true;
			}
		}
		byte[] out = PaintedRegionBuilder.buildModelOnly(model, null, grid, null, null, touched,
				TerrainLighting.daytime(), false);
		BchMapModel m = new BchMapModel(out);
		List<String> errs = m.validate();
		if (!errs.isEmpty()) {
			throw new IllegalStateException("painted model invalid: " + errs.get(0));
		}
		if (new BCHFile(out).errorlevel != 0) {
			throw new IllegalStateException("painted model rejected by the render parser");
		}
		//check the BRUSH'S OWN mesh: its last vertices are the generated floor
		//quads, whose baked daytime lighting must decode bright in the mesh's
		//own colour format (the s8 bug wrote 0..255 into a signed byte -> black).
		//Other meshes that merely grew hold CLIPPED retail vertices, which carry
		//the retail map's own (legitimately dark) colours.
		int checked = 0;
		BchMapModel before = new BchMapModel(model);
		int paintedMesh = -1;
		for (BchMapModel.MeshGeom g : m.geometry()) {
			if (g.posOk && brush.matHints.length > 0) {
				String name = m.getMaterialName(m.getMeshMaterialIndex(g.meshIndex));
				if (name != null && name.toLowerCase().contains(brush.matHints[0])) {
					paintedMesh = g.meshIndex;
					break;
				}
			}
		}
		for (BchMapModel.MeshGeom g : m.geometry()) {
			if (!g.posOk || g.meshIndex != paintedMesh) {
				continue;
			}
			BchMapModel.MeshAttr col = m.findAttr(g.meshIndex, 3);
			if (col == null || g.vertexCount == 0) {
				continue;
			}
			int beforeCount = g.meshIndex < before.meshCount
					? before.geometry().get(g.meshIndex).vertexCount : 0;
			if (g.vertexCount <= beforeCount) {
				continue; //this mesh gained nothing
			}
			int v = g.vertexCount - 1;
			int at = g.vtxAbs + v * g.stride + col.offset;
			int compSize = col.size() / Math.max(1, col.elems);
			for (int k = 0; k < Math.min(3, col.elems); k++) {
				int o = at + k * compSize;
				float unit;
				switch (col.type) {
					case 0: unit = m.raw[o] / 127f; break;               //s8
					case 1: unit = (m.raw[o] & 0xFF) / 255f; break;      //u8
					case 2: unit = ((short) ((m.raw[o] & 0xFF) | (m.raw[o + 1] << 8))) / 32767f; break;
					default: unit = Float.intBitsToFloat((m.raw[o] & 0xFF) | ((m.raw[o + 1] & 0xFF) << 8)
							| ((m.raw[o + 2] & 0xFF) << 16) | ((m.raw[o + 3] & 0xFF) << 24)); break;
				}
				if (unit < 0.5f) {
					throw new IllegalStateException("painted vertex colour decodes dark (" + unit
							+ ") in mesh " + g.meshIndex + " attr type " + col.type);
				}
			}
			checked++;
		}
		return checked;
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

	static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
