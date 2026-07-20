package ctrmap.tests;

import ctrmap.formats.containers.AD;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.propdata.ADPropRegistry;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Headless order-preservation test for ADPropRegistry.
 *
 * Extracts pristine AreaData entry 0 from the read-only backup GARC, parses
 * its prop registry (AD file 0) without loading models, writes it back
 * unchanged and asserts that both the registry payload and the whole AD
 * container are byte-identical. Entry 0 only has a single registry entry, so
 * the test additionally locates the first AreaData entry whose registry file
 * order differs from HashMap iteration order (entry 9 on the pristine ORAS
 * dump) and round-trips that one too. This guards the LinkedHashMap fix - the
 * old HashMap silently reordered entries in 104/228 registries.
 */
public class ADPropRegistryOrderTest {

	private static final String DEFAULT_GARC_PATH = "C:\\Users\\flami\\Desktop\\Claude\\sessions\\3DS Editor\\RomFS_original_garcs\\a\\0\\1\\4";

	public static void main(String[] args) throws IOException {
		File garcFile = new File(args.length > 0 ? args[0] : DEFAULT_GARC_PATH);
		if (!garcFile.exists()) {
			System.out.println("FAIL: pristine AreaData GARC not found: " + garcFile.getAbsolutePath());
			System.exit(1);
		}

		GARC garc = new GARC(garcFile);

		//required case: entry 0
		if (!roundTrip(garc, 0)) {
			System.exit(1);
		}

		//order-sensitive case: first entry whose registry file order differs
		//from HashMap iteration order - the old HashMap-backed write() would
		//reorder exactly these, so this round-trip fails on a regression
		int reorderIndex = -1;
		for (int i = 1; i < garc.length; i++) {
			byte[] b = garc.getDecompressedEntry(i);
			if (b == null || b.length < 8 || b[0] != 0x41 || b[1] != 0x44) {
				continue; //not an AD container (e.g. ORAS entry 228)
			}
			int file0Offset = readIntLE(b, 4);
			if (file0Offset + 4 > b.length) {
				continue;
			}
			int count = readIntLE(b, file0Offset);
			if (count < 2) {
				continue;
			}
			java.util.List<Integer> fileOrder = new java.util.ArrayList<>();
			java.util.Map<Integer, Integer> hashMap = new java.util.HashMap<>();
			for (int e = 0; e < count; e++) {
				int entryOffset = file0Offset + 4 + e * 0x50;
				if (entryOffset + 2 > b.length) {
					break;
				}
				int ref = (b[entryOffset] & 0xFF) | ((b[entryOffset + 1] & 0xFF) << 8);
				fileOrder.add(ref);
				hashMap.put(ref, ref);
			}
			if (!fileOrder.equals(new java.util.ArrayList<>(hashMap.keySet()))) {
				reorderIndex = i;
				break;
			}
		}
		if (reorderIndex == -1) {
			System.out.println("FAIL: no HashMap-order-sensitive registry found in AreaData");
			System.exit(1);
		}
		if (!roundTrip(garc, reorderIndex)) {
			System.exit(1);
		}

		System.out.println("PASS");
	}

	private static boolean roundTrip(GARC garc, int index) throws IOException {
		byte[] adBytes = garc.getDecompressedEntry(index);
		if (adBytes == null || adBytes.length == 0) {
			System.out.println("FAIL: could not decompress AreaData entry " + index);
			return false;
		}

		File tmp = new File(System.getProperty("java.io.tmpdir"), "ctrmap_ADPropRegistryOrderTest_ad" + index);
		OutputStream os = new FileOutputStream(tmp);
		os.write(adBytes);
		os.close();

		try {
			AD ad = new AD(tmp);
			byte[] file0Before = ad.getFile(0);
			if (file0Before == null || file0Before.length < 4) {
				System.out.println("FAIL: could not read AD file 0 of entry " + index);
				return false;
			}

			ADPropRegistry reg = new ADPropRegistry(ad, null, false);
			if (reg.entries.isEmpty()) {
				System.out.println("FAIL: entry " + index + " registry parsed 0 entries");
				return false;
			}
			System.out.println("entry " + index + ": parsed " + reg.entries.size() + " registry entries");

			reg.modified = true;
			reg.write();

			AD ad2 = new AD(tmp);
			byte[] file0After = ad2.getFile(0);
			if (!Arrays.equals(file0Before, file0After)) {
				System.out.println("FAIL: entry " + index + " registry payload (AD file 0) is not byte-identical after write()");
				hexDiff(file0Before, file0After);
				return false;
			}

			byte[] wholeAfter = readAll(tmp);
			if (!Arrays.equals(adBytes, wholeAfter)) {
				System.out.println("FAIL: entry " + index + " whole AD container is not byte-identical after write()");
				hexDiff(adBytes, wholeAfter);
				return false;
			}
			return true;
		} finally {
			tmp.delete();
		}
	}

	private static int readIntLE(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
	}

	private static byte[] readAll(File f) throws IOException {
		InputStream in = new FileInputStream(f);
		byte[] b = new byte[in.available()];
		in.read(b);
		in.close();
		return b;
	}

	private static void hexDiff(byte[] a, byte[] b) {
		System.out.println("expected " + a.length + " bytes, got " + b.length);
		int shown = 0;
		int max = Math.max(a.length, b.length);
		for (int i = 0; i < max && shown < 32; i++) {
			int av = (i < a.length) ? (a[i] & 0xFF) : -1;
			int bv = (i < b.length) ? (b[i] & 0xFF) : -1;
			if (av != bv) {
				System.out.println(String.format("  offset 0x%06X: expected %s, got %s",
						i,
						av == -1 ? "--" : String.format("%02X", av),
						bv == -1 ? "--" : String.format("%02X", bv)));
				shown++;
			}
		}
	}
}
