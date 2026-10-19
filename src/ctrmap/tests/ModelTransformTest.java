package ctrmap.tests;

import ctrmap.formats.containers.ContainerBytes;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.model.H3DModel;
import ctrmap.formats.h3d.model.H3DSkeleton;
import ctrmap.formats.h3d.model.Transform4;
import java.io.File;
import java.security.MessageDigest;
import java.util.List;

/**
 * The model reader's bone matrices come out exactly as they did on JOGL 2.3.2 (F077, F214).
 *
 * <p>WHY THIS EXISTS. The reader held its world and bone matrices in JOGL's
 * {@code com.jogamp.opengl.math.Matrix4}; moving JOGL to 2.6.0 removed that package, and the reader now
 * holds them in {@link Transform4}, its own. A matrix that came out different would move every skinned
 * vertex the reader places from its bones, silently. MEASURED 2026-10-10 with 2.3.2's own class on the
 * classpath (scratch probe CompareBoneMatrices): every bone of every retail map, building and NPC model,
 * 12,146 bones of which 5,097 are rotated, has the same 16 float bits both ways, and the digest of them all
 * is {@link #DIGEST}. The control the probe ran - the same steps in another order - did differ.
 *
 * <p>ORDER: needs the pristine dump's a/0/3/9, a/0/2/3 and a/0/2/1. No display.
 *
 * Usage: java ctrmap.tests.ModelTransformTest &lt;pristine-dump-root&gt;
 */
public class ModelTransformTest {

	/** SHA-256 over every bone matrix's 16 float bits, little-endian, in archive order - JOGL 2.3.2's. */
	static final String DIGEST = "0c12764de8853af64b9b7b92dfe093033719c506cd243f81482b0c816ff0e9fe";
	static final int BONES = 12146;
	static final int ROTATED = 5097;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		Object[][] archives = {{ctrmap.gamedef.ArchiveType.FIELD_DATA, 1}, {ctrmap.gamedef.ArchiveType.BUILDING_MODELS, 0}, {ctrmap.gamedef.ArchiveType.MOVE_MODELS, 0}};
		for (Object[] a : archives) {
			if (!arc(dump, (ctrmap.gamedef.ArchiveType) a[0]).isFile()) {
				Skip.suite("no " + arc(dump, (ctrmap.gamedef.ArchiveType) a[0]) + " under " + dump.getAbsolutePath());
				return;
			}
		}
		theStepsMultiplyFromTheRight();

		MessageDigest md = MessageDigest.getInstance("SHA-256");
		int bones = 0, rotated = 0, unread = 0;
		for (Object[] a : archives) {
			GARC g = new GARC(arc(dump, (ctrmap.gamedef.ArchiveType) a[0]));
			for (int i = 0; i < g.length; i++) {
				byte[] e = g.getDecompressedEntry(i);
				byte[] bch = e == null ? null : ContainerBytes.subfile(e, (Integer) a[1]);
				if (bch == null || bch.length < 4 || bch[0] != 'B' || bch[1] != 'C' || bch[2] != 'H') {
					continue;
				}
				BCHFile f;
				try {
					f = new BCHFile(bch);
				} catch (RuntimeException ex) {
					unread++;
					continue;
				}
				for (H3DModel m : f.models) {
					if (m.skeleton == null) {
						continue;
					}
					List<H3DSkeleton.H3DBone> bs = m.skeleton.bones;
					for (int b = 0; b < bs.size(); b++) {
						bones++;
						H3DSkeleton.H3DBone bone = bs.get(b);
						if (bone.rotation.x != 0 || bone.rotation.y != 0 || bone.rotation.z != 0) {
							rotated++;
						}
						for (float v : m.skeleton.transform.get(b).cells()) {
							int bits = Float.floatToIntBits(v);
							md.update(new byte[]{(byte) bits, (byte) (bits >> 8), (byte) (bits >> 16), (byte) (bits >> 24)});
						}
					}
				}
			}
		}
		StringBuilder hex = new StringBuilder();
		for (byte x : md.digest()) {
			hex.append(String.format("%02x", x));
		}
		check(unread == 0, "control: every retail model reads (" + unread + " did not)");
		check(bones == BONES && rotated == ROTATED, "control: the corpus is the one measured - " + bones + " bones, "
				+ rotated + " rotated (measured: " + BONES + ", " + ROTATED + ")");
		check(DIGEST.equals(hex.toString()), "every bone matrix is the one JOGL 2.3.2 computed: digest " + hex
				+ " (measured: " + DIGEST + ")");

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** Each step multiplies onto the transform from the right: a translate then a scale keeps the translation. */
	static void theStepsMultiplyFromTheRight() {
		Transform4 t = new Transform4();
		float[] v = t.cells();
		check(v[0] == 1f && v[5] == 1f && v[10] == 1f && v[15] == 1f && v[12] == 0f, "a new transform is the identity");
		t.translate(1f, 2f, 3f);
		t.scale(2f, 3f, 4f);
		check(v[12] == 1f && v[13] == 2f && v[14] == 3f && v[0] == 2f && v[5] == 3f && v[10] == 4f,
				"translate then scale: the translation stays (1,2,3) and the diagonal is (2,3,4)");
		Transform4 r = new Transform4();
		r.rotate((float) Math.toRadians(90), 0f, 0f, 2f);
		float[] w = r.cells();
		check(Math.abs(w[1] - 1f) < 1e-6f && Math.abs(w[4] + 1f) < 1e-6f && Math.abs(w[0]) < 1e-6f,
				"a quarter turn about z (axis given unnormalised) takes x to y: " + w[0] + ", " + w[1] + ", " + w[4]);
	}

	/** Where ORAS keeps {@code t} under the dump root {@code dump} - asked of the game profile. */
	static File arc(File dump, ctrmap.gamedef.ArchiveType t) {
		return new File(dump.getPath() + ctrmap.Workspace.getArchivePath(t, ctrmap.gamedef.GameType.ORAS));
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}
