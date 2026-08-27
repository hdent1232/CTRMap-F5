package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.maison.MaisonSet;
import java.io.File;
import java.util.Arrays;

/**
 * Maison opponent-set codec gate against the retail pools:
 * (1) each pool (a/1/8/2,4,6) has 999 entries of 16 bytes;
 * (2) read -> write is BYTE-IDENTICAL for every set in every pool;
 * (3) value ranges on used sets (species<=721, moves<=621, nature<=24,
 *     item<=775);
 * (4) the class->set-list tables (a/1/8/3 -> pool2, a/1/8/5 -> pool4) reference
 *     only in-range, non-empty sets - the retail linkage invariant (0 dangling).
 *
 * Usage: java ctrmap.tests.MaisonSetTest <romfs-root-with-a18>
 */
public class MaisonSetTest {

	public static void main(String[] args) throws Exception {
		String root = args.length > 0 ? args[0]
				: "../RomFS/000400000011C400";
		int failures = 0;
		int[] pools = {2, 4, 6};
		int[][] poolUsed = new int[7][];
		for (int p : pools) {
			GARC g = new GARC(new File(root + "/a/1/8/" + p), false);
			if (g.length != 999) {
				failures++;
				System.out.println("FAIL pool " + p + " entries=" + g.length + " (want 999)");
			}
			boolean[] nonEmpty = new boolean[g.length];
			int used = 0, rtOk = 0;
			for (int i = 0; i < g.length; i++) {
				byte[] rec = g.getDecompressedEntry(i);
				if (rec.length != MaisonSet.SIZE) {
					failures++;
					System.out.println("FAIL pool " + p + " entry " + i + " len=" + rec.length);
					break;
				}
				MaisonSet s = MaisonSet.read(rec);
				if (!Arrays.equals(s.write(), rec)) {
					failures++;
					System.out.println("FAIL pool " + p + " set " + i + " round-trip not byte-identical");
					if (failures > 8) {
						break;
					}
					continue;
				}
				rtOk++;
				if (!s.isEmpty()) {
					nonEmpty[i] = true;
					used++;
					if (s.species > 721 || s.nature > 24 || s.heldItem > 775) {
						failures++;
						System.out.println("FAIL pool " + p + " set " + i + " field range");
					}
					for (int mv : s.moves) {
						if (mv > 621) {
							failures++;
							System.out.println("FAIL pool " + p + " set " + i + " move " + mv);
							break;
						}
					}
				}
			}
			poolUsed[p] = new int[]{used};
			// remember occupancy for the linkage check
			occupancy.put(p, nonEmpty);
			System.out.println("pool " + p + ": " + rtOk + " round-trip, " + used + " used sets");
		}

		// (4) linkage: list tables must reference in-range, non-empty sets
		failures += checkLinkage(root, 3, 2);
		failures += checkLinkage(root, 5, 4);

		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT (" + failures + ")");
		if (failures > 0) {
			System.exit(1);
		}
	}

	static final java.util.Map<Integer, boolean[]> occupancy = new java.util.HashMap<>();

	/**
	 * Each list entry = u16 class tag, u16 count, then count u16 set-indices
	 * (0xFFFF pad). Every referenced index must be &lt; poolSize and non-empty.
	 */
	static int checkLinkage(String root, int listFile, int poolFile) throws Exception {
		GARC lists = new GARC(new File(root + "/a/1/8/" + listFile), false);
		boolean[] occ = occupancy.get(poolFile);
		int refs = 0, dangling = 0, empty = 0, oob = 0;
		for (int i = 0; i < lists.length; i++) {
			byte[] e = lists.getDecompressedEntry(i);
			if (e.length < 4) {
				continue;
			}
			int count = u16(e, 2);
			for (int k = 0; k < count && 4 + k * 2 + 1 < e.length; k++) {
				int idx = u16(e, 4 + k * 2);
				if (idx == 0xFFFF) {
					continue;
				}
				refs++;
				if (idx >= occ.length) {
					oob++;
				} else if (!occ[idx]) {
					empty++;
				}
			}
		}
		dangling = oob + empty;
		System.out.println("linkage a/1/8/" + listFile + " -> pool " + poolFile + ": " + refs
				+ " refs, " + oob + " out-of-range, " + empty + " point-to-empty");
		return dangling == 0 ? 0 : 1;
	}

	static int u16(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
	}
}
