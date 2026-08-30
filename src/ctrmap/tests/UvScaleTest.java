package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TerrainCatalog;
import ctrmap.formats.tilemap.TilePalette;
import java.io.File;
import java.util.Arrays;

/**
 * An imported brush must paint at the scale its DONOR was authored at.
 *
 * <p>{@code ensureMaterial} keeps a donor's material and blanks its geometry to
 * a single vertex, because the painter supplies the tiles. That left the
 * painter's UV measurement with nothing to measure, so every imported brush
 * fell back to a fixed 1/36 while retail world-projected ground sits around
 * 1/72 - a consistent 2x texture-scale error on exactly the brushes the editor
 * adds, and only on those. Retail materials measured fine, so nothing in the
 * corpus looked wrong.
 *
 * <p>Nothing in this suite asserted a UV value before this test existed.
 *
 * Usage: java ctrmap.tests.UvScaleTest &lt;path-to-a039-garc&gt;
 */
public class UvScaleTest {

	static final float DEFAULT = 1f / 36f;
	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/3/9");
		//point the pristine-snapshot machinery at the dump we were handed
		ctrmap.Workspace.game = ctrmap.Workspace.GameType.ORAS;
		ctrmap.Workspace.GAMEDIR_PATH = garcFile.getParentFile().getParentFile()
				.getParentFile().getParentFile().getAbsolutePath();
		ctrmap.Workspace.WORKSPACE_PATH = System.getProperty("java.io.tmpdir") + "/ctrmap_uvscale";
		ctrmap.Workspace.temp = new File(ctrmap.Workspace.WORKSPACE_PATH, "temp");
		ctrmap.Workspace.temp.mkdirs();
		File snap = new File(ctrmap.Workspace.originalSnapshotDir().getAbsolutePath()
				+ ctrmap.Workspace.getArchivePath(
						ctrmap.Workspace.ArchiveType.FIELD_DATA, ctrmap.Workspace.game));
		if (!snap.isFile()) {
			snap.getParentFile().mkdirs();
			try {
				java.nio.file.Files.createLink(snap.toPath(), garcFile.toPath());
			} catch (Exception cannotLink) {
				java.nio.file.Files.copy(garcFile.toPath(), snap.toPath());
			}
		}

		//--- every donor must report a measurable scale --------------------------
		int measured = 0, atDefault = 0;
		for (TerrainCatalog.Donor d : TerrainCatalog.donors().values()) {
			float[] s = TerrainCatalog.donorUvScale(d.injectName);
			if (s == null) {
				System.out.println("  FAIL donor " + d.brush + " (" + d.injectName
						+ ") has no measurable scale");
				fails++;
				continue;
			}
			measured++;
			if (Math.abs(s[0] - DEFAULT) < 1e-6f && Math.abs(s[1] - DEFAULT) < 1e-6f) {
				atDefault++;
			}
		}
		System.out.println("  donors measured: " + measured + " (of "
				+ TerrainCatalog.donors().size() + "), sitting exactly on the old default: " + atDefault);
		check(measured > 0, "at least one donor scale is measurable");
		//if every donor happened to equal the default the test would prove nothing
		check(atDefault < measured, "donor scales are not all the old 1/36 default");

		//--- an imported brush paints at the donor's scale, not the default ------
		GARC gr = new GARC(garcFile);
		//Mauville mall: an indoor map with no outdoor materials, so outdoor
		//brushes must import rather than resolve natively
		byte[] model = sub(gr.getDecompressedEntry(153), 1);
		if (model == null || !BchMapModel.isMapModel(model)) {
			System.out.println("  FAIL target 153 unusable");
			fails++;
		} else {
			int compared = 0, matched = 0;
			for (TilePalette brush : TilePalette.brushes()) {
				TerrainCatalog.ImportResult r = TerrainCatalog.ensureMaterial(model, brush);
				if (!r.injected) {
					continue; //resolved natively; not what this test is about
				}
				BchMapModel m = new BchMapModel(r.model);
				int mesh = -1;
				for (int i = 0; i < m.meshCount; i++) {
					String n = m.getMaterialName(m.getMeshMaterialIndex(i));
					if (n != null && n.startsWith("ctr_")) {
						float[] want = TerrainCatalog.donorUvScale(n);
						if (want == null) {
							continue;
						}
						mesh = i;
						float[] got = PaintedRegionBuilder.uvScaleOf(m, i);
						compared++;
						if (Math.abs(got[0] - want[0]) < 1e-6f && Math.abs(got[1] - want[1]) < 1e-6f) {
							matched++;
						} else {
							System.out.println("  FAIL " + brush + " (" + n + ") paints at "
									+ fmt(got) + ", donor is " + fmt(want));
							fails++;
						}
						break;
					}
				}
				if (mesh < 0) {
					System.out.println("  FAIL " + brush + ": imported but no ctr_ material found");
					fails++;
				}
			}
			System.out.println("  imported brushes checked: " + compared + ", at the donor's scale: " + matched);
			check(compared > 0, "at least one brush actually imported into the mall");
		}

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	static String fmt(float[] s) {
		return "1/" + Math.round(1f / s[0]) + " x 1/" + Math.round(1f / s[1]);
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}

	static byte[] sub(byte[] c, int i) {
		if (c == null || c.length < 8) {
			return null;
		}
		int count = (c[2] & 0xFF) | ((c[3] & 0xFF) << 8);
		if (i >= count) {
			return null;
		}
		int o0 = le32(c, 4 + i * 4), o1 = le32(c, 4 + (i + 1) * 4);
		if (o0 < 0 || o1 > c.length || o1 < o0) {
			return null;
		}
		return Arrays.copyOfRange(c, o0, o1);
	}

	static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
