package ctrmap.tests;

import ctrmap.formats.containers.GR;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchMapModel;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Reader gate for the native map-model editor: verifies {@link BchMapModel}
 * losslessly parses a broad spread of real ORAS FieldData map models with every
 * structural invariant intact. Extracts subfile 1 (the visual model BCH) from
 * each GR region of the pristine FieldData GARC (a/0/3/9), parses it, and fails
 * if any region reports a structural problem or throws.
 *
 * Read-only against the pristine backup; nothing is written. Run with:
 * java -cp "build/classes;lib/jogl-all.jar;lib/gluegen-rt.jar" ctrmap.tests.BchMapModelTest
 */
public class BchMapModelTest {

	private static final String DEFAULT_GARC_PATH =
			"C:\\Users\\flami\\Desktop\\Claude\\sessions\\3DS Editor\\RomFS_original_garcs\\a\\0\\3\\9";
	private static int fails = 0;

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0] : DEFAULT_GARC_PATH);
		if (!garcFile.exists()) {
			System.out.println("SKIP: pristine FieldData GARC not found: " + garcFile.getAbsolutePath());
			return;
		}
		GARC garc = new GARC(garcFile);
		File tmp = new File(System.getProperty("java.io.tmpdir"), "ctrmap_mapmodel_test");
		tmp.mkdirs();

		int tested = 0, notMapModel = 0, meshTotal = 0, matTotal = 0, lutRegions = 0;
		long vertsTotal = 0, meshesSeen = 0, meshesDecoded = 0, meshesSkipped = 0;
		// sample every region (step keeps it fast on the 857-entry GARC while covering the whole range)
		int step = 1;
		for (int idx = 0; idx < garc.length; idx += step) {
			byte[] rawgr = garc.getDecompressedEntry(idx);
			if (rawgr == null || rawgr.length < 2 || (((rawgr[0] & 0xFF) << 8) | (rawgr[1] & 0xFF)) != 0x4752) {
				continue; // not a GR container
			}
			File grFile = new File(tmp, "gr" + idx);
			write(grFile, rawgr);
			GR gr = new GR(grFile);
			byte[] model = gr.len >= 2 ? gr.getFile(1) : null;
			grFile.delete();
			if (model == null || !BchMapModel.isMapModel(model)) {
				notMapModel++;
				continue;
			}
			tested++;
			try {
				BchMapModel mm = new BchMapModel(model);
				List<String> probs = mm.validate();
				if (!probs.isEmpty()) {
					fails++;
					System.out.println("region " + idx + " (" + probs.size() + " problems):");
					for (String s : probs) {
						System.out.println("    " + s);
					}
				} else {
					meshTotal += mm.meshCount;
					matTotal += mm.matCount;
					if (!mm.auxDicts.isEmpty()) {
						lutRegions++;
					}
					// --- vertex decode coverage (skipped meshes are handled safely, not failures) ---
					for (BchMapModel.MeshGeom g : mm.geometry()) {
						meshesSeen++;
						if (g.posOk) {
							meshesDecoded++;
							vertsTotal += g.vertexCount;
						} else {
							meshesSkipped++;
						}
					}
					// --- HARD invariant: translate is surgical (only raw-data bytes, re-parses clean) ---
					String tProblem = checkTranslateIsSurgical(mm);
					if (tProblem != null) {
						fails++;
						System.out.println("region " + idx + " translate: " + tProblem);
					}
				}
			} catch (Exception e) {
				fails++;
				System.out.println("region " + idx + " EXCEPTION: " + e);
			}
		}
		tmp.delete();

		System.out.println();
		System.out.println("map-model reader: " + tested + " real map models parsed, " + notMapModel
				+ " non-map-model GR subfiles skipped");
		System.out.println("   totals across clean parses: " + matTotal + " materials, " + meshTotal + " meshes, "
				+ lutRegions + " regions with a materialsLUT dict");
		System.out.printf("   vertex-position decode: %d/%d meshes decoded (%.2f%%), %d skipped (unrecognized format), %d vertices%n",
				meshesDecoded, meshesSeen, meshesSeen == 0 ? 0.0 : 100.0 * meshesDecoded / meshesSeen, meshesSkipped, vertsTotal);
		if (tested == 0) {
			System.out.println("FAIL: no map models found to test");
			System.exit(1);
		}
		if (fails == 0) {
			System.out.println("PASS");
		} else {
			System.out.println("FAIL (" + fails + " regions with problems)");
			System.exit(1);
		}
	}

	/** A translate must be surgical: same length, re-parses clean, and every byte
	 *  that changed lies inside the raw-data section (offset-preserving). */
	private static String checkTranslateIsSurgical(BchMapModel mm) {
		byte[] edited = mm.translate(0f, 100f, 0f);
		if (edited.length != mm.raw.length) {
			return "length changed " + mm.raw.length + " -> " + edited.length;
		}
		if (!new BchMapModel(edited).validate().isEmpty()) {
			return "translated model no longer parses cleanly";
		}
		int lo = mm.rawDataAddr, hi = mm.rawDataAddr + mm.rawDataLen;
		for (int i = 0; i < edited.length; i++) {
			if (edited[i] != mm.raw[i] && (i < lo || i >= hi)) {
				return "byte " + i + " changed outside raw-data section [" + lo + "," + hi + ")";
			}
		}
		return null;
	}

	private static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}

	private static void write(File f, byte[] b) throws Exception {
		try (OutputStream os = new FileOutputStream(f)) {
			os.write(b);
		}
	}
}
