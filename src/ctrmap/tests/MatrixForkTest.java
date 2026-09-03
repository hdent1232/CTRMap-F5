package ctrmap.tests;

import ctrmap.GeometryForker;
import ctrmap.formats.garc.GARC;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Guards the map matrix's ZONE-SWITCH layer across a geometry fork.
 *
 * <p>A matrix entry is: {@code hasLOD, unknown, width, height}, then a
 * {@code width*height} grid of FieldData region ids, and - only when
 * {@code hasLOD == 1} - a {@code (width*4) x (height*4)} layer naming the ZONE
 * that owns each quarter cell, then a {@code width*height} LOD grid. The engine
 * resolves the ground under the player through that zone layer.
 *
 * <p>This test exists because the fork used to rewrite the region grid and copy
 * the zone layer verbatim, so a forked map still named the zone it was forked
 * FROM. Nothing in the editor noticed - the map parsed, rendered and had valid
 * collision - but in game the new zone never became current: no location banner,
 * no music, its zone header ignored, and the donor zone's entities (retail item
 * balls, trainers with line of sight) live on the new ground. Picking up one of
 * those inherited item balls froze the game.
 *
 * <p>Three checks:
 * <ol>
 * <li><b>Retail invariant</b> - every zone whose matrix carries a zone layer
 * appears in its own layer. Retail scores 61/61 with no exceptions, which is
 * what makes it safe to assert on our own output.</li>
 * <li><b>Fork invariant</b> - after {@link GeometryForker#planFork} for a
 * brand-new zone the layer names that zone and nothing else, the region grid is
 * still remapped, and the entry's byte length is unchanged.</li>
 * <li><b>Shared-map invariant</b> - forking an EXISTING zone off a matrix it
 * shares must leave every other zone's cell count exactly as it was. 19 retail
 * matrices host several zones.</li>
 * </ol>
 *
 * <p>Args: path to the ZoneData GARC (a/0/1/3) and the MapMatrix GARC (a/0/4/0).
 */
public class MatrixForkTest {

	private static final int MASTER_ROW = 0x38;

	public static void main(String[] args) {
		if (args.length < 2) {
			System.out.println("FAIL usage: MatrixForkTest <ZoneData a/0/1/3> <MapMatrix a/0/4/0>");
			System.exit(1);
		}
		GARC zo = new GARC(new File(args[0]));
		GARC mm = new GARC(new File(args[1]));
		byte[] master = zo.getDecompressedEntry(zo.length - 2);
		int zoneCount = master.length / MASTER_ROW;

		int failures = 0;
		int withLayer = 0, ownIdPresent = 0;
		for (int zone = 0; zone < zoneCount; zone++) {
			int matIdx = u16(master, zone * MASTER_ROW + 4);
			if (matIdx >= mm.length) {
				continue;
			}
			byte[] mat = mm.getDecompressedEntry(matIdx);
			Map<Integer, Integer> zones = layerZones(mat);
			if (zones == null) {
				continue; //no zone layer on this matrix
			}
			withLayer++;
			if (zones.containsKey(zone)) {
				ownIdPresent++;
			} else {
				failures++;
				if (failures <= 10) {
					System.out.println("FAIL zone " + zone + " matrix " + matIdx
							+ ": its own map's zone layer names " + zones + ", not " + zone
							+ " - in game the engine would treat this ground as another zone's");
				}
			}
		}
		System.out.println("  zone layers: " + withLayer + " matrices carry one, own id present in "
				+ ownIdPresent);

		//Fork every zone that has a layer and check the fork claims its own map.
		int forked = 0;
		for (int zone = 0; zone < zoneCount && failures <= 10; zone++) {
			int matIdx = u16(master, zone * MASTER_ROW + 4);
			if (matIdx >= mm.length) {
				continue;
			}
			byte[] mat = mm.getDecompressedEntry(matIdx);
			if (layerZones(mat) == null) {
				continue;
			}
			byte[] zoBytes = zo.getDecompressedEntry(zone);
			int newZone = zoneCount + 7; //a plausible appended slot
			try {
				GeometryForker.ForkPlan p = GeometryForker.planFork(zoBytes, mat, mm.length, mm.length, newZone, true);
				byte[] out = p.newMatrixBytes;
				if (out.length != mat.length) {
					throw new IllegalStateException("fork changed the matrix entry length "
							+ mat.length + " -> " + out.length);
				}
				Map<Integer, Integer> after = layerZones(out);
				if (after == null) {
					throw new IllegalStateException("fork dropped the zone layer");
				}
				if (after.size() != 1 || !after.containsKey(newZone)) {
					throw new IllegalStateException("forked map's zone layer names " + after
							+ ", want only [" + newZone + "]");
				}
				if (p.zoneCellsRewritten <= 0) {
					throw new IllegalStateException("fork reported 0 zone cells rewritten");
				}
				//the region grid must still be remapped to the fork's private regions
				int sub0 = u32(out, 4);
				int w = u16(out, sub0 + 4), h = u16(out, sub0 + 6);
				for (int k = 0; k < w * h; k++) {
					int id = u16(out, sub0 + 8 + k * 2);
					if (id != 0xFFFF && id < mm.length) {
						throw new IllegalStateException("region cell " + k + " still points at a shared region " + id);
					}
				}
				forked++;
			} catch (RuntimeException ex) {
				failures++;
				System.out.println("FAIL fork of zone " + zone + " (matrix " + matIdx + "): " + ex.getMessage());
			}
		}
		System.out.println("  forks checked: " + forked);

		//An EXISTING zone taking its own map away from one it SHARES is a
		//different job from cloning a donor map for a brand-new zone, and the
		//blanket rewrite above is wrong for it: 19 retail matrices host several
		//zones, and 139 of 536 zones sit on one, so the fork prompt fires for
		//them. Rewriting every cell hands Route 111's, 112's, 113's and 114's
		//ground to Fallarbor Town - their banner, music and zone header stop
		//applying there and their item balls and trainers stop loading, with
		//nothing said. Only the forking zone's own cells may move.
		int shared = 0, intact = 0;
		Set<Integer> sharedMatrices = new LinkedHashSet<>();
		for (int zone = 0; zone < zoneCount && failures <= 10; zone++) {
			int matIdx = u16(master, zone * MASTER_ROW + 4);
			if (matIdx >= mm.length) {
				continue;
			}
			byte[] mat = mm.getDecompressedEntry(matIdx);
			Map<Integer, Integer> before = layerZones(mat);
			if (before == null || before.size() < 2) {
				continue; //this map belongs to one zone; nothing to take from anybody
			}
			shared++;
			sharedMatrices.add(matIdx);
			GeometryForker.ForkPlan p = GeometryForker.planFork(
					zo.getDecompressedEntry(zone), mat, mm.length, mm.length, zone, false);
			Map<Integer, Integer> after = layerZones(p.newMatrixBytes);
			boolean ok = true;
			for (Map.Entry<Integer, Integer> e : before.entrySet()) {
				if (e.getKey() == zone) {
					continue;
				}
				if (!e.getValue().equals(after.get(e.getKey()))) {
					ok = false;
					if (failures <= 10) {
						System.out.println("FAIL forking zone " + zone + " off shared matrix " + matIdx
								+ " took zone " + e.getKey() + "'s ground: " + before + " -> " + after);
					}
				}
			}
			if (!before.get(zone).equals(after.get(zone))) {
				ok = false;
				System.out.println("FAIL forking zone " + zone + " off shared matrix " + matIdx
						+ " lost its own ground: " + before + " -> " + after);
			}
			if (p.otherZones.length != before.size() - 1) {
				ok = false;
				System.out.println("FAIL forking zone " + zone + " off shared matrix " + matIdx
						+ " reported " + java.util.Arrays.toString(p.otherZones)
						+ " as the other zones on the map, but the layer holds " + before);
			}
			if (ok) {
				intact++;
			} else {
				failures++;
			}
		}
		System.out.println("  shared maps: " + shared + " zone(s) on " + sharedMatrices.size()
				+ " matrices, other zones' ground left alone in " + intact);

		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT (" + failures + ")");
		if (failures > 0) {
			System.exit(1);
		}
	}

	/**
	 * How many quarter cells each zone owns in a matrix's zone-switch layer, or
	 * null when it has none. The counts matter: a fork that steals another
	 * zone's ground shows up here as its count moving, not as a name appearing.
	 */
	private static Map<Integer, Integer> layerZones(byte[] mat) {
		if (mat == null || mat.length < 12) {
			return null;
		}
		int sub0 = u32(mat, 4);
		if (sub0 < 0 || sub0 + 8 > mat.length) {
			return null;
		}
		if (u16(mat, sub0) != 1) {
			return null; //hasLOD != 1: no zone layer in this entry
		}
		int w = u16(mat, sub0 + 4), h = u16(mat, sub0 + 6);
		if (w <= 0 || h <= 0) {
			return null;
		}
		int off = sub0 + 8 + w * h * 2;
		int quads = (w * 4) * (h * 4);
		if (off + quads * 2 > mat.length) {
			return null;
		}
		Map<Integer, Integer> out = new LinkedHashMap<>();
		for (int q = 0; q < quads; q++) {
			int v = u16(mat, off + q * 2);
			if (v != 0xFFFF) {
				Integer had = out.get(v);
				out.put(v, had == null ? 1 : had + 1);
			}
		}
		return out;
	}

	private static int u16(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
	}

	private static int u32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
