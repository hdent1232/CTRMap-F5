package ctrmap.tests;

import ctrmap.ZoneRemover;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.garc.GarcRebuilder;
import ctrmap.formats.garc.LZ11;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Validates the shrink-capable GARC writer and zone removal against the
 * pristine ZoneData:
 * <ol>
 * <li>identity rebuild: rewriting all 538 stored entries yields an archive
 *     whose every entry decompresses identically;</li>
 * <li>append simulation: a 4-zone appended layout (zones 536-539 = copies,
 *     master grown to 540 rows at 540, EN at 541) round-trips through the
 *     rebuilder;</li>
 * <li>removal: {@link ZoneRemover#removeFromFile} restores the stock layout
 *     with every base zone, the master table and the EN pack content-equal
 *     to pristine;</li>
 * <li>the base-zone warp scan reports no references into added zones on
 *     pristine data.</li>
 * </ol>
 * Usage: java ctrmap.tests.ZoneRemoveTest &lt;path-to-a013-garc&gt;
 */
public class ZoneRemoveTest {

	public static void main(String[] args) throws Exception {
		File pristine = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/1/3");
		File tmpDir = Scratch.dir("zrm");
		File a = new File(tmpDir, "zrm_a013");
		Files.copy(pristine.toPath(), a.toPath(), StandardCopyOption.REPLACE_EXISTING);
		GARC pz = new GARC(pristine);
		int fails = 0;
		fails += check("pristine has 538 entries", pz.length == 538);

		// (1) identity rebuild
		List<byte[]> stored = new ArrayList<>();
		for (int i = 0; i < pz.length; i++) {
			stored.add(pz.getStoredEntry(i));
		}
		File b = new File(tmpDir, "zrm_identity");
		GarcRebuilder.write(a, b, stored);
		GARC rb = new GARC(b);
		fails += check("identity count", rb.length == 538);
		boolean idOk = true;
		for (int i = 0; i < 538; i++) {
			if (!Arrays.equals(rb.getDecompressedEntry(i), pz.getDecompressedEntry(i))) {
				idOk = false;
				System.out.println("  identity mismatch at entry " + i);
				break;
			}
		}
		fails += check("identity content", idOk);

		// (2) simulated 4-zone append
		byte[] master = pz.getDecompressedEntry(536);
		byte[] grown = Arrays.copyOf(master, master.length + 4 * ZoneRemover.MASTER_ROW);
		for (int k = 0; k < 4; k++) {
			System.arraycopy(master, ZoneRemover.MASTER_ROW, grown, master.length + k * ZoneRemover.MASTER_ROW, ZoneRemover.MASTER_ROW);
		}
		List<byte[]> appended = new ArrayList<>();
		for (int i = 0; i < 536; i++) {
			appended.add(pz.getStoredEntry(i));
		}
		for (int k = 0; k < 4; k++) {
			appended.add(pz.getStoredEntry(1)); // 4 added zones = copies of zone 1
		}
		appended.add(pz.isEntryCompressed(536) ? LZ11.compress(grown) : grown);
		appended.add(pz.getStoredEntry(537));
		File c = new File(tmpDir, "zrm_appended");
		GarcRebuilder.write(a, c, appended);
		GARC ap = new GARC(c);
		fails += check("appended count 542", ap.length == 542);
		fails += check("added zone content", Arrays.equals(ap.getDecompressedEntry(538), pz.getDecompressedEntry(1)));
		fails += check("grown master rows", ap.getDecompressedEntry(540).length == 540 * ZoneRemover.MASTER_ROW);

		// (3) removal restores stock content
		int removed = ZoneRemover.removeFromFile(c);
		fails += check("removed 4", removed == 4);
		GARC rm = new GARC(c);
		fails += check("restored count 538", rm.length == 538);
		boolean restOk = true;
		for (int i = 0; i < 538; i++) {
			if (!Arrays.equals(rm.getDecompressedEntry(i), pz.getDecompressedEntry(i))) {
				restOk = false;
				System.out.println("  restore mismatch at entry " + i);
				break;
			}
		}
		fails += check("restored content == pristine", restOk);
		fails += check("removal on stock is a no-op", ZoneRemover.removeFromFile(b) == 0);

		// (4) pristine base zones reference no added zones
		fails += check("no dangling references in pristine", ZoneRemover.referencesToAdded(pz).isEmpty());

		System.out.println("rebuild+remove: identity 538, append 542, restore 538, " + fails + " failure(s)");
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (fails > 0) {
			System.exit(1);
		}
	}

	static int check(String what, boolean ok) {
		if (!ok) {
			System.out.println("FAIL: " + what);
			return 1;
		}
		return 0;
	}
}
