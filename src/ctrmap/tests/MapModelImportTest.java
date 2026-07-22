package ctrmap.tests;

import ctrmap.formats.containers.GR;
import ctrmap.formats.garc.GARC;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Verifies the Milestone-1 map-model import path: replacing a FieldData GR
 * region's visual model (subfile 1) via the container passthrough (storeFile)
 * is byte-faithful for every other subfile, for models of any size. Operates
 * on temp-dir copies of the pristine FieldData GARC (a/0/3/9); nothing under
 * the backup is written. Run with:
 * java -cp "build/classes;lib/jogl-all.jar;lib/gluegen-rt.jar" ctrmap.tests.MapModelImportTest
 */
public class MapModelImportTest {

	private static final String DEFAULT_GARC_PATH = "C:\\Users\\flami\\Desktop\\Claude\\sessions\\3DS Editor\\RomFS_original_garcs\\a\\0\\3\\9";
	private static int fails = 0;

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0] : DEFAULT_GARC_PATH);
		if (!garcFile.exists()) {
			System.out.println("FAIL: pristine FieldData GARC not found: " + garcFile.getAbsolutePath());
			System.exit(1);
		}
		GARC garc = new GARC(garcFile);
		File tmp = new File(System.getProperty("java.io.tmpdir"), "ctrmap_mapimport_test");
		tmp.mkdirs();

		//test a small, a mid and a large map region container
		int tested = 0;
		for (int idx : new int[]{0, 1, 389}) {
			if (idx >= garc.length) {
				continue;
			}
			byte[] raw = garc.getDecompressedEntry(idx);
			if (raw == null || !isGr(raw)) {
				continue;
			}
			tested++;
			testRegion(tmp, raw, idx);
		}
		for (File f : tmp.listFiles()) {
			f.delete();
		}
		tmp.delete();

		if (tested == 0) {
			System.out.println("FAIL: no GR regions found to test");
			System.exit(1);
		}
		System.out.println("map-model import passthrough: " + tested + " regions tested, " + fails + " failures");
		if (fails == 0) {
			System.out.println("PASS");
		} else {
			System.out.println("FAIL");
			System.exit(1);
		}
	}

	private static void testRegion(File tmp, byte[] grBytes, int idx) throws Exception {
		File grFile = new File(tmp, "gr" + idx);
		write(grFile, grBytes);
		GR gr = new GR(grFile);
		int subs = gr.len;
		check(subs >= 2, "region " + idx + ": GR has a model subfile (len=" + subs + ")");

		//snapshot all original subfiles
		byte[][] orig = new byte[subs][];
		for (int i = 0; i < subs; i++) {
			orig[i] = gr.getFile(i);
		}
		byte[] origModel = orig[1];

		//1. no-op store of subfile 1 -> every subfile byte-identical
		gr.storeFile(1, origModel);
		GR after1 = new GR(grFile);
		check(subfilesEqual(after1, orig, 1, idx), "region " + idx + ": no-op model store preserves every subfile");

		//2. same-length replacement (flip a middle byte) -> other subfiles exact, model updated
		byte[] flipped = origModel.clone();
		flipped[flipped.length / 2] ^= 0x5A;
		gr.storeFile(1, flipped);
		GR after2 = new GR(grFile);
		check(Arrays.equals(after2.getFile(1), flipped), "region " + idx + ": same-length replace lands in slot 1");
		check(subfilesEqual(after2, orig, 1, idx), "region " + idx + ": same-length replace preserves siblings");

		//3. DIFFERENT length (the edited-geometry / Blender case) -> siblings exact.
		//storeFile pads the stored subfile to the container's alignment, so slot 1
		//holds the model followed by zero padding (benign - the BCH parser reads by
		//its own internal length). Verify prefix + zero padding, not exact equality.
		byte[] bigger = Arrays.copyOf(origModel, origModel.length + 40);
		gr.storeFile(1, bigger);
		GR after3 = new GR(grFile);
		check(startsWithThenZero(after3.getFile(1), bigger), "region " + idx + ": different-length replace holds the model (+ zero pad) in slot 1");
		check(subfilesEqual(after3, orig, 1, idx), "region " + idx + ": different-length replace preserves siblings");

		//4. restore original -> the whole container matches the pristine bytes for all subfiles
		gr.storeFile(1, origModel);
		GR restored = new GR(grFile);
		boolean allBack = true;
		for (int i = 0; i < subs; i++) {
			if (!Arrays.equals(restored.getFile(i), orig[i])) {
				allBack = false;
				break;
			}
		}
		check(allBack, "region " + idx + ": restore returns every subfile to the original bytes");
	}

	/** True if every subfile of gr except skipIndex equals the snapshot orig. */
	private static boolean subfilesEqual(GR gr, byte[][] orig, int skipIndex, int idx) {
		for (int i = 0; i < orig.length; i++) {
			if (i == skipIndex) {
				continue;
			}
			if (!Arrays.equals(gr.getFile(i), orig[i])) {
				System.out.println("   region " + idx + ": subfile " + i + " changed unexpectedly");
				return false;
			}
		}
		return true;
	}

	/** True if got starts with want and every trailing byte is zero (alignment pad). */
	private static boolean startsWithThenZero(byte[] got, byte[] want) {
		if (got == null || got.length < want.length) {
			return false;
		}
		for (int i = 0; i < want.length; i++) {
			if (got[i] != want[i]) {
				return false;
			}
		}
		for (int i = want.length; i < got.length; i++) {
			if (got[i] != 0) {
				return false;
			}
		}
		return true;
	}

	private static boolean isGr(byte[] b) {
		//GR magic (0x4752 = 'G','R') is big-endian on disk like other GF containers
		return b.length >= 2 && (((b[0] & 0xFF) << 8) | (b[1] & 0xFF)) == 0x4752;
	}

	private static void check(boolean cond, String what) {
		if (!cond) {
			System.out.println("FAILURE: " + what);
			fails++;
		}
	}

	private static void write(File f, byte[] b) throws Exception {
		try (OutputStream os = new FileOutputStream(f)) {
			os.write(b);
		}
	}
}
