package ctrmap.tests;

import ctrmap.formats.containers.GR;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.gfcollision.GRCollisionFile;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import static ctrmap.formats.containers.ContainerBytes.subfile;

/**
 * Validates the collision editor's save against the retail-exact writer: a
 * collision loaded through GRCollisionFile (the render/edit model) and written
 * back unedited must be EXACTLY GfColl.build(uniqueTris, parsed) - byte for
 * byte, every repeated triangle kept - for every retail region. The editor
 * used to read through a parser of its own that dropped every repeat (F061),
 * and this suite then asserted only the triangle SET, so the loss was counted
 * ("collapsed") and passed. This is the gate for the "OBJ to collisions" and
 * collision-editor save paths.
 *
 * Usage: java ctrmap.tests.GfCollLegacyBridgeTest <path-to-a039-garc> [step]
 */
public class GfCollLegacyBridgeTest {

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/3/9");
		int step = args.length > 1 ? Integer.parseInt(args[1]) : 1;
		File scratch = Scratch.dir("ctrmap_collbridge");
		GARC garc = new GARC(garcFile);
		int tested = 0, ok = 0, failures = 0;
		long collapsedDupes = 0;
		//a scratch game for the containers to report their writes to: nothing here opens a workspace
		FakeGameFiles game = new FakeGameFiles();
		for (int i = 0; i < garc.length; i += step) {
			byte[] entry = garc.getDecompressedEntry(i);
			int count = (entry[2] & 0xFF) | ((entry[3] & 0xFF) << 8);
			if (count < 3) {
				continue;
			}
			byte[] retail = subfile(entry, 2);
			if (!GfColl.isColl(retail)) {
				continue;
			}
			tested++;
			try {
				File f = new File(scratch, "gr" + i + ".bin");
				try (FileOutputStream fos = new FileOutputStream(f)) {
					fos.write(entry);
				}
				GR gr = new GR(f, game);
				GRCollisionFile legacy = GRCollisionFile.read(gr);
				legacy.write(); //the FIXED path - routes through GfColl.build
				byte[] rebuilt = new GR(f, game).getFile(2);

				GfColl a = new GfColl(retail), b = new GfColl(rebuilt);
				Set<Long> setA = triSet(a), setB = triSet(b);
				if (!setA.equals(setB)) {
					throw new IllegalStateException("triangle set differs (retail " + setA.size()
							+ " unique vs rebuilt " + setB.size() + ")");
				}
				collapsedDupes += a.uniqueTris.size() - b.uniqueTris.size();
				if (a.uniqueTris.size() != b.uniqueTris.size()) {
					throw new IllegalStateException("the save collapsed " + (a.uniqueTris.size() - b.uniqueTris.size())
							+ " repeated triangle(s) the region holds");
				}
				if (!Arrays.equals(rebuilt, GfColl.build(a.uniqueTris, a))) {
					throw new IllegalStateException("the editor's save is not the retail-exact rebuild of what it read");
				}
				//bucket sanity: rebuilt must be internally consistent (parse ok, counts %3)
				for (int k = 0; k < 16; k++) {
					if (b.bucketCnt[k] % 3 != 0) {
						throw new IllegalStateException("bucket " + k + " count not a triangle multiple");
					}
				}
				ok++;
			} catch (RuntimeException ex) {
				failures++;
				System.out.println("FAIL region " + i + ": " + ex.getMessage());
				if (failures > 8) {
					break;
				}
			}
		}
		System.out.println("\nLegacy bridge: tested=" + tested + "  ok=" + ok + "  failures=" + failures
				+ "  (repeated tris collapsed by the editor model: " + collapsedDupes + ", which must be 0)");
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (failures > 0) {
			System.exit(1);
		}
	}

	static Set<Long> triSet(GfColl c) {
		Set<Long> out = new HashSet<>();
		for (float[] t : c.uniqueTris) {
			long h = 1125899906842597L;
			for (int i = 0; i < 9; i++) {
				h = 31 * h + Float.floatToIntBits(t[i]);
			}
			out.add(h);
		}
		return out;
	}
}
