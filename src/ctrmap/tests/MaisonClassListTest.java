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
		File listPath = new File(root + "/a/1/8/" + listFile);
		GARC lists = new GARC(listPath, false);
		GARC pool = new GARC(new File(root + "/a/1/8/" + poolFile), false);
		//A sweep that swept nothing is not a sweep that passed. GARC.parse logs
		//a FileNotFoundException and hands back an archive of length 0, so this
		//suite once printed "0/0 round-trip" and ALL PASS from any worktree -
		//the incident BatteryHygieneTest's registration rule was written for.
		//That rule made the runner pass a path; it cannot make the path point
		//at an archive, and a partial dump still gets past it. This does.
		if (lists.length == 0 || pool.length == 0) {
			System.out.println("FAIL a/1/8/" + listFile + ": read 0 entries (pool " + poolFile
					+ ": " + pool.length + ") from " + listPath.getAbsolutePath()
					+ " - there is nothing here to round-trip, so this suite asserts nothing."
					+ " Point it at a complete dump.");
			return 1;
		}
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
