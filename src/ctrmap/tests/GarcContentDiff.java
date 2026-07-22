package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import java.io.File;
import java.util.Arrays;

/**
 * Utility for the deploy script: exits 0 when two GARCs have IDENTICAL
 * decompressed contents (ignoring compression/container byte differences),
 * exits 1 when their contents differ, exits 2 on error. This lets the deploy
 * step ship only archives whose actual data changed, not archives that merely
 * got recompressed by a Pack Workspace.
 *
 * Usage: java ctrmap.tests.GarcContentDiff <liveGarc> <backupGarc>
 */
public class GarcContentDiff {

	public static void main(String[] args) {
		if (args.length < 2) {
			System.out.println("usage: GarcContentDiff <live> <backup>");
			System.exit(2);
		}
		try {
			GARC a = new GARC(new File(args[0]));
			GARC b = new GARC(new File(args[1]));
			if (a.length != b.length) {
				System.out.println("DIFFERENT (entry count " + a.length + " vs " + b.length + ")");
				System.exit(1);
			}
			for (int i = 0; i < a.length; i++) {
				byte[] ea = a.getDecompressedEntry(i);
				byte[] eb = b.getDecompressedEntry(i);
				if (!Arrays.equals(ea, eb)) {
					System.out.println("DIFFERENT (entry " + i + ")");
					System.exit(1);
				}
			}
			System.out.println("IDENTICAL");
			System.exit(0);
		} catch (Exception ex) {
			System.out.println("ERROR: " + ex.getMessage());
			System.exit(2);
		}
	}
}
