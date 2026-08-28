package ctrmap.tests;

import ctrmap.formats.containers.GR;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.BuildingCatalog;
import ctrmap.formats.h3d.MapPrefab;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TerrainLighting;
import ctrmap.formats.tilemap.TilePalette;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * Validates every building-catalog entry against the pristine dump: the box
 * extracts a non-empty prefab, the prefab stamps (geometry + collision +
 * footprint tiles, at its base-height offset) onto a painted grass region,
 * the result passes the strict model validator, and door/interior metadata is
 * shaped sanely. This is the offline gate for the Building Palette.
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
		File tmp = File.createTempFile("bcat", null).getParentFile();
		for (BuildingCatalog.Entry e : entries) {
			try {
				// extract straight from the pristine GARC (the runtime path minus Workspace)
				byte[] regionBytes = gr.getDecompressedEntry(e.donorRegion);
				File rf = new File(tmp, "bcat_test_region");
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
				if (r.stamped.isEmpty()) {
					throw new IllegalStateException("nothing stamped (missing materials " + r.missingMaterials + ")");
				}
				List<String> errs = new BchMapModel(r.newModel).validate();
				if (!errs.isEmpty()) {
					throw new IllegalStateException("stamped model invalid: " + errs.get(0));
				}
				// footprint tiles ride along
				if (p.tiles == null) {
					throw new IllegalStateException("no footprint tiles");
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
		System.out.println("catalog: " + entries.size() + " entries, " + fails + " failure(s)");
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (fails > 0) {
			System.exit(1);
		}
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
