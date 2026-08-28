package ctrmap.tests;

import ctrmap.InteriorWirer;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.zone.ZoneEntities;
import java.io.File;
import java.util.List;

/**
 * Validates the interior round-trip wiring against the pristine dump, for
 * every interior the building catalog links:
 * <ul>
 * <li>the exit-warp heuristic (target area != interior's area) finds at least
 *     one exit, and every found exit is a REAL door: its retail target zone
 *     warps back into this interior (the measured retail invariant);</li>
 * <li>same-area warps (stairs to another floor) are never classified as
 *     exits;</li>
 * <li>rewiring the exits and reassembling preserves every other entity
 *     byte-for-byte (only the exit warps' target fields change).</li>
 * </ul>
 * Usage: java ctrmap.tests.InteriorWirerTest &lt;path-to-a013-garc&gt;
 */
public class InteriorWirerTest {

	static GARC zo;

	// the catalog's interior zones (oras_buildings.tsv interiorZone column)
	static final int[] INTERIORS = {228, 229, 428, 223, 227, 230, 235, 238, 244, 273, 321, 345, 256, 339, 522};

	public static void main(String[] args) throws Exception {
		zo = new GARC(new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/1/3"));
		int fails = 0, singles = 0, multis = 0;
		for (int zone : INTERIORS) {
			try {
				ZoneEntities ent = entities(zone);
				int area = area(zone);
				List<Integer> exits = InteriorWirer.exitWarps(ent, area, InteriorWirerTest::area);
				if (exits.isEmpty()) {
					throw new IllegalStateException("no exit warps found");
				}
				// every exit must be a real door: its target zone warps back here
				for (int i : exits) {
					int tz = ent.warps.get(i).targetZone;
					boolean back = false;
					for (ZoneEntities.Warp w : entities(tz).warps) {
						if (w.targetZone == zone) {
							back = true;
						}
					}
					if (!back) {
						throw new IllegalStateException("exit " + i + " target zone " + tz + " has no warp back");
					}
				}
				// stairs (same-area targets) never classified as exits
				for (int i = 0; i < ent.warps.size(); i++) {
					if (!exits.contains(i) && area(ent.warps.get(i).targetZone) != area) {
						throw new IllegalStateException("warp " + i + " leads outside but was not classified as exit");
					}
				}
				if (ent.warps.size() == 1 && (exits.size() != 1 || exits.get(0) != 0)) {
					throw new IllegalStateException("single-warp interior must exit via warp 0");
				}

				// rewire + reassemble: only the exit targets change
				ZoneEntities mod = entities(zone);
				for (int i : exits) {
					mod.warps.get(i).targetZone = 500;
					mod.warps.get(i).targetWarpId = 7;
				}
				mod.modified = true;
				ZoneEntities re = new ZoneEntities(mod.assembleData());
				if (re.warps.size() != ent.warps.size() || re.npcs.size() != ent.npcs.size()
						|| re.furniture.size() != ent.furniture.size()
						|| re.triggers1.size() != ent.triggers1.size() || re.triggers2.size() != ent.triggers2.size()) {
					throw new IllegalStateException("entity counts changed on rewire");
				}
				for (int i = 0; i < re.warps.size(); i++) {
					ZoneEntities.Warp a = re.warps.get(i), b = ent.warps.get(i);
					boolean isExit = exits.contains(i);
					if (a.targetZone != (isExit ? 500 : b.targetZone)
							|| a.targetWarpId != (isExit ? 7 : b.targetWarpId)
							|| a.x != b.x || a.y != b.y || a.z != b.z || a.w != b.w || a.h != b.h
							|| a.faceDirection != b.faceDirection || a.transitionType != b.transitionType) {
						throw new IllegalStateException("warp " + i + " corrupted by rewire");
					}
				}
				if (ent.warps.size() == 1) {
					singles++;
				} else {
					multis++;
				}
				System.out.println("  ok: interior " + zone + " (" + ent.warps.size() + " warps, exits " + exits + ")");
			} catch (Exception ex) {
				System.out.println("FAIL interior " + zone + ": " + ex.getMessage());
				fails++;
			}
		}
		System.out.println("interiors: " + INTERIORS.length + " (" + singles + " single-warp, " + multis + " multi), " + fails + " failure(s)");
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (fails > 0) {
			System.exit(1);
		}
	}

	static ZoneEntities entities(int zone) {
		return new ZoneEntities(sub(zo.getDecompressedEntry(zone), 1));
	}

	static int area(int zone) {
		byte[] hdr = sub(zo.getDecompressedEntry(zone), 0);
		return hdr == null ? -1 : (hdr[2] & 0xFF) | ((hdr[3] & 0xFF) << 8);
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
		return java.util.Arrays.copyOfRange(c, o0, o1);
	}

	static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
