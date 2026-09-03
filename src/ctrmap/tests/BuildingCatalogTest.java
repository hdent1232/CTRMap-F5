package ctrmap.tests;

import ctrmap.formats.containers.GR;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.BuildingCatalog;
import ctrmap.formats.h3d.MapPrefab;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TerrainLighting;
import ctrmap.formats.tilemap.TilePalette;
import ctrmap.tools.BuildingHarvester;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates every building-catalog entry against the pristine dump: the box
 * extracts a non-empty prefab, EVERY piece of it stamps (geometry + collision +
 * footprint tiles, at its base-height offset) onto a painted grass region,
 * the result passes the strict model validator, and door/interior metadata is
 * shaped sanely. This is the offline gate for the Building Palette.
 *
 * <p>Whole, not "at least one piece": the gate used to accept anything that
 * landed a single piece, and fifteen entries cut from skinned regions got
 * through because the ubiquitous chip_sea_b foam exists in the grass base -
 * "Battle Resort structure 32" then placed 3 of its 36 pieces and Apply called
 * it done. A piece whose donor submesh is skinned can only land where a map
 * already carries its material, so such entries are not offered at all.
 *
 * <p>And the name has to describe the cut. The harvester asked its keyword
 * families in a fixed order and took the first that matched anything at all,
 * so one lamp-post material in a furnished room named the whole room "lamp":
 * "Littleroot Town lamp" is four floors, walls, a roof and a baked shadow,
 * 5,775 triangles of which the lamp is a handful. Every auto entry's kind must
 * name the family that owns the most triangles of its cut.
 *
 * Usage: java ctrmap.tests.BuildingCatalogTest &lt;path-to-a039-garc&gt;
 */
public class BuildingCatalogTest {

	static final int DIM = PaintedRegionBuilder.DIM;

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/3/9");
		GARC gr = new GARC(garcFile);
		byte[] base = sub(gr.getDecompressedEntry(1), 1); // grass base donor (Route 101 region 1)

		List<BuildingCatalog.Entry> entries = BuildingCatalog.entries();
		int fails = 0;
		if (entries.size() < 20) {
			System.out.println("FAIL: catalog too small (" + entries.size() + ")");
			fails++;
		}
		int misnamed = 0, unmatched = 0;
		String misnamedExample = null;
		File rf = Scratch.file("bcat_region");
		for (BuildingCatalog.Entry e : entries) {
			try {
				// extract straight from the pristine GARC (the runtime path minus Workspace)
				byte[] regionBytes = gr.getDecompressedEntry(e.donorRegion);
				try (FileOutputStream fo = new FileOutputStream(rf)) {
					fo.write(regionBytes);
				}
				MapPrefab p = MapPrefab.extract(new GR(rf), e.tx0, e.ty0, e.tx1, e.ty1, e.name);
				if (p == null || p.pieces.isEmpty()) {
					throw new IllegalStateException("no geometry in the box");
				}
				p.donorArea = e.donorArea;

				// stamp onto flat painted grass at the base-height offset
				TilePalette[][] g = new TilePalette[DIM][DIM];
				for (TilePalette[] row : g) {
					Arrays.fill(row, TilePalette.GRASS);
				}
				byte[] grass = PaintedRegionBuilder.build(base, g, null, null, TerrainLighting.daytime(), false).model;
				int ax = Math.max(0, 20 - e.tilesW() / 2), ay = Math.max(0, 20 - e.tilesH() / 2);
				MapPrefab.StampResult r = p.stampGeometry(grass, ax, ay, -e.baseY);
				if (!r.missingMaterials.isEmpty()) {
					throw new IllegalStateException(r.missingMaterials.size() + " of " + p.pieces.size()
							+ " piece(s) cannot be placed: " + r.missingMaterials);
				}
				for (MapPrefab.Piece pc : p.pieces) {
					if (pc.skinned) {
						throw new IllegalStateException("piece " + pc.material
								+ " is skinned - it lands only where a map already carries that material");
					}
				}
				List<String> errs = new BchMapModel(r.newModel).validate();
				if (!errs.isEmpty()) {
					throw new IllegalStateException("stamped model invalid: " + errs.get(0));
				}
				// footprint tiles ride along
				if (p.tiles == null) {
					throw new IllegalStateException("no footprint tiles");
				}
				// the name has to describe what dominates the cut, not its
				// smallest recognisable part
				if (e.auto) {
					String dominant = dominantKind(new GR(rf), e);
					if (dominant == null) {
						unmatched++;
					} else if (!dominant.equals(e.kind)) {
						misnamed++;
						if (misnamedExample == null) {
							misnamedExample = "\"" + e.name + "\" is filed as " + e.kind + ", but " + dominant
									+ " owns the most triangles of the cut";
						}
					}
				}
				// door metadata sanity
				if (e.doorDX >= 0) {
					if (e.doorDX >= e.tilesW() || e.doorDY >= e.tilesH()) {
						throw new IllegalStateException("door tile outside the box");
					}
					if (e.interiorZone < 0 || e.interiorZone > 535) {
						throw new IllegalStateException("bad interior zone " + e.interiorZone);
					}
					if (e.doorProp == null || e.doorProp.equals("-")) {
						throw new IllegalStateException("enterable but no door prop");
					}
				}
				System.out.println("  ok: " + e.name + " (" + p.pieces.size() + " pieces, "
						+ r.stamped.size() + " stamped, " + e.tilesW() + "x" + e.tilesH() + ")");
			} catch (Exception ex) {
				System.out.println("FAIL " + e.name + ": " + ex.getMessage());
				fails++;
			}
		}
		if (misnamed > 0) {
			System.out.println("FAIL: " + misnamed + " entries are named after a part smaller than another in the same cut, e.g. "
					+ misnamedExample);
			fails++;
		} else {
			System.out.println("  ok: every auto entry's kind names the family that owns the most triangles of its cut");
		}
		if (unmatched > 0) {
			System.out.println("FAIL: " + unmatched + " auto entries name a box the harvester no longer cuts (stale catalogue?)");
			fails++;
		}
		System.out.println("catalog: " + entries.size() + " entries, " + fails + " failure(s)");
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** Per donor region, the kind each cut box asks for; the components are read once and dropped. */
	static final Map<Integer, Map<String, String>> dominantByRegion = new HashMap<>();

	/**
	 * The kind the cut itself asks for, worked out here from the component's
	 * raw material/triangle tally rather than asked of the harvester, so a
	 * harvester that names the wrong part - or a catalogue built by one that
	 * did - is caught rather than agreed with: the keyword family owning the
	 * most triangles, and only when it beats the biggest single part no family
	 * recognises; otherwise the component is named for its size. Null when the
	 * box is no longer a component the harvester cuts.
	 */
	static String dominantKind(GR region, BuildingCatalog.Entry e) {
		Map<String, String> byBox = dominantByRegion.get(e.donorRegion);
		if (byBox == null) {
			byBox = new HashMap<>();
			for (BuildingHarvester.Comp c : BuildingHarvester.detect(region.getFile(1),
					region.len > 2 ? region.getFile(2) : null)) {
				if (c.terrain) {
					continue;
				}
				Map<String, Integer> perFamily = new HashMap<>();
				int biggestUnnamed = 0;
				for (Map.Entry<String, Integer> m : c.materialTriangles().entrySet()) {
					String[] family = familyOf(m.getKey());
					if (family == null) {
						biggestUnnamed = Math.max(biggestUnnamed, m.getValue());
					} else {
						perFamily.merge(family[0], m.getValue(), Integer::sum);
					}
				}
				String best = null;
				int bestTris = 0;
				for (String[] family : BuildingHarvester.HINT_FAMILIES) {   //ties keep the earlier family
					int tris = perFamily.getOrDefault(family[0], 0);
					if (tris > bestTris) {
						bestTris = tris;
						best = family[0];
					}
				}
				String hint = best != null && bestTris > biggestUnnamed ? best
						: (c.tilesW() <= 2 && c.tilesH() <= 2 ? "decor" : "structure");
				byBox.put(box(c.tx0, c.ty0, c.tx1, c.ty1), BuildingHarvester.categoryOf(hint));
			}
			dominantByRegion.put(e.donorRegion, byBox);
		}
		return byBox.get(box(e.tx0, e.ty0, e.tx1, e.ty1));
	}

	/** This test's own reading of which family a material name belongs to. */
	static String[] familyOf(String material) {
		String ml = material.toLowerCase();
		for (String[] family : BuildingHarvester.HINT_FAMILIES) {
			for (int k = 1; k < family.length; k++) {
				if (ml.contains(family[k])) {
					return family;
				}
			}
		}
		return null;
	}

	static String box(int tx0, int ty0, int tx1, int ty1) {
		return tx0 + "," + ty0 + "," + tx1 + "," + ty1;
	}

	static byte[] sub(byte[] c, int i) {
		int count = (c[2] & 0xFF) | ((c[3] & 0xFF) << 8);
		if (i >= count) {
			return null;
		}
		int o0 = le32(c, 4 + i * 4), o1 = le32(c, 4 + (i + 1) * 4);
		return Arrays.copyOfRange(c, o0, o1);
	}

	static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
