package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.maison.MaisonClassList;
import java.io.File;
import java.util.Arrays;

/**
 * Maison class-list codec gate: both list tables (a/1/8/3 fixed-padded 136B,
 * a/1/8/5 tight variable) must read -> write BYTE-IDENTICALLY for every entry,
 * class tags must be in the 2..279 range, and every set index must be in the
 * paired pool's range (proving the codec preserves the linkage the game needs).
 *
 * Usage: java ctrmap.tests.MaisonClassListTest <romfs-root-with-a18>
 */
public class MaisonClassListTest {

	public static void main(String[] args) throws Exception {
		String root = args.length > 0 ? args[0]
				: "../RomFS/000400000011C400";
		int failures = 0;
		failures += checkTable(root, 3, 2);
		failures += checkTable(root, 5, 4);
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT (" + failures + ")");
		if (failures > 0) {
			System.exit(1);
		}
	}

	static int checkTable(String root, int listFile, int poolFile) throws Exception {
		GARC lists = new GARC(new File(root + "/a/1/8/" + listFile), false);
		GARC pool = new GARC(new File(root + "/a/1/8/" + poolFile), false);
		int poolSize = pool.length;
		int fails = 0, rtOk = 0, refs = 0;
		for (int i = 0; i < lists.length; i++) {
			byte[] rec = lists.getDecompressedEntry(i);
			MaisonClassList l = MaisonClassList.read(rec);
			if (!Arrays.equals(l.write(), rec)) {
				fails++;
				System.out.println("FAIL list " + listFile + " entry " + i + " round-trip not byte-identical");
				continue;
			}
			rtOk++;
			if (l.classTag < 2 || l.classTag > 279) {
				fails++;
				System.out.println("FAIL list " + listFile + " entry " + i + " class tag " + l.classTag);
			}
			for (int idx : l.setIndices) {
				refs++;
				if (idx != 0xFFFF && idx >= poolSize) {
					fails++;
					System.out.println("FAIL list " + listFile + " entry " + i + " set index " + idx + " >= pool " + poolSize);
				}
			}
		}
		System.out.println("list a/1/8/" + listFile + " -> pool " + poolFile + ": " + rtOk + "/" + lists.length
				+ " round-trip, " + refs + " set refs in range");
		return fails;
	}
}
