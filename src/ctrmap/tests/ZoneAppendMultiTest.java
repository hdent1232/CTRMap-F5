package ctrmap.tests;

import ctrmap.ZoneAppender;
import ctrmap.ZoneAppender.MultiAppendPayloads;
import ctrmap.ZoneCloner;
import ctrmap.formats.codepatch.ZoneLimitPatch;
import ctrmap.formats.garc.GARC;
import java.io.File;
import java.util.Arrays;

/**
 * Validates the multi-zone append (block-of-N layout) against the real ORAS
 * ZoneData GARC, and proves the DATA it produces matches what the CODE PATCH
 * (ZoneLimitPatch) expects: for N added zones the archive grows to
 * M = ZoneLimitPatch.masterIndex(N) zone slots, an M-row master table, and an
 * M-blob EN pack. Code and data therefore agree by construction.
 *
 * Read-only against the pristine backup. Run:
 * java -cp "build/classes;..." ctrmap.tests.ZoneAppendMultiTest
 */
public class ZoneAppendMultiTest {

	private static final String DEFAULT_GARC = "C:\\Users\\flami\\Desktop\\Claude\\sessions\\3DS Editor\\RomFS_original_garcs\\a\\0\\1\\3";
	private static int fails = 0;

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0] : DEFAULT_GARC);
		if (!garcFile.exists()) {
			System.out.println("SKIP: pristine ZoneData GARC not found: " + garcFile.getAbsolutePath());
			return;
		}
		GARC garc = new GARC(garcFile);
		int oldCount = garc.length - 2;                 // 536
		byte[] master = garc.getDecompressedEntry(oldCount);
		byte[] en = garc.getDecompressedEntry(oldCount + 1);
		byte[] srcZo = garc.getDecompressedEntry(0);
		check(master.length == oldCount * ZoneCloner.ZONE_HEADER_SIZE, "master is " + oldCount + " rows");

		for (int n : new int[]{1, 2, 4, 5, 8, 12}) {
			int m = ZoneLimitPatch.masterIndex(n);      // what the code patch will assume
			int addCount = m - oldCount;                // real + spares
			MultiAppendPayloads p = ZoneAppender.buildMultiAppendPayloads(srcZo, master, en, 0, oldCount, addCount);

			check(p.newCount == m, "N=" + n + ": newCount == masterIndex " + m);
			// master table: M rows, appended rows carry their own OAZoneNumber
			check(p.master.length == m * ZoneCloner.ZONE_HEADER_SIZE, "N=" + n + ": master grown to " + m + " rows");
			for (int i = oldCount; i < m; i++) {
				int oa = oaZoneNumber(p.master, i * ZoneCloner.ZONE_HEADER_SIZE);
				check(oa == i, "N=" + n + ": master row " + i + " OAZoneNumber == " + i + " (got " + oa + ")");
			}
			// original master rows untouched
			check(Arrays.equals(Arrays.copyOf(p.master, master.length), master), "N=" + n + ": original master rows preserved");

			// EN pack: M blobs, validates, round-trips, appended blobs empty
			ZoneAppender.validateEN(p.en, m);
			check(((p.en[2] & 0xFF) | ((p.en[3] & 0xFF) << 8)) == m, "N=" + n + ": EN count == " + m);
			check(Arrays.equals(ZoneAppender.rebuildENMulti(p.en, m, 0), p.en), "N=" + n + ": EN round-trips");
			int enEnd = intLE(p.en, 4 + m * 4);
			check(enEnd == p.en.length, "N=" + n + ": EN end sentinel == length");
			for (int i = oldCount; i <= m; i++) {                 // appended offsets all == data end (empty)
				check(intLE(p.en, 4 + i * 4) == p.en.length, "N=" + n + ": appended EN blob " + i + " empty");
			}

			// new ZOs: addCount of them, each a valid ZO with its own OAZoneNumber
			check(p.newZos.length == addCount, "N=" + n + ": produced " + addCount + " new ZO(s)");
			for (int i = 0; i < addCount; i++) {
				byte[] zo = p.newZos[i];
				check(zo[0] == 'Z' && zo[1] == 'O' || (((zo[0] & 0xFF) << 8) | (zo[1] & 0xFF)) == 0x5A4F, "N=" + n + ": new ZO " + i + " magic");
				int hdr = intLE(zo, 4);
				check(oaZoneNumber(zo, hdr) == oldCount + i, "N=" + n + ": new ZO " + i + " OAZoneNumber == " + (oldCount + i));
			}
			System.out.printf("N=%-2d -> M=%-3d  (%d real + %d spare)  master=%d rows  EN=%d blobs  OK%n",
					n, m, n, addCount - n, m, m);
		}

		System.out.println();
		if (fails == 0) {
			System.out.println("PASS");
		} else {
			System.out.println("FAIL (" + fails + ")");
			System.exit(1);
		}
	}

	private static int oaZoneNumber(byte[] b, int headerOff) {
		int v = intLE(b, headerOff + ZoneCloner.UNKNOWN_FLAGS_OFFSET);
		return (v >>> ZoneCloner.OA_ZONE_NUMBER_SHIFT) & 0x7FF;
	}

	private static int intLE(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}

	private static void check(boolean cond, String what) {
		if (!cond) {
			System.out.println("FAILURE: " + what);
			fails++;
		}
	}
}
