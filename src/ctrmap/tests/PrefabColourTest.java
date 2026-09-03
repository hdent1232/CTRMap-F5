package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.BuildingCatalog;
import ctrmap.formats.h3d.MapPrefab;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * A stamped building must keep its colours.
 *
 * <p>The stamper's fast path grows an existing mesh that shares the piece's
 * material name, stride and position offset, copying vertex bytes whole-stride.
 * That is one check short: PICA s8 and u8 are both a single byte, so two meshes
 * can agree on all three and still disagree about what the bytes mean. A u8
 * colour landing in an s8 attribute reads back negative and renders black,
 * which is what happened to stamped fir trees. Worse pairings exist in the
 * corpus - a donor carrying a normal into a target buffering a UV - where the
 * normal's floats are read as texture coordinates.
 *
 * <p>Sweeps the curated catalog, stamps each entry onto a real map, and
 * requires every landed vertex to decode to the donor's colour in the LANDED
 * mesh's own format.
 *
 * Usage: java ctrmap.tests.PrefabColourTest &lt;path-to-a039-garc&gt;
 */
public class PrefabColourTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/3/9");
		ctrmap.Workspace.game = ctrmap.Workspace.GameType.ORAS;
		ctrmap.Workspace.GAMEDIR_PATH = garcFile.getParentFile().getParentFile()
				.getParentFile().getParentFile().getAbsolutePath();
		ctrmap.Workspace.WORKSPACE_PATH = Scratch.dir("ctrmap_prefabcolour").getAbsolutePath();
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

		GARC gr = new GARC(garcFile);
		//A mall with no outdoor materials, a cave, and two routes: a piece has to
		//find a same-named mesh to collide with, so one map is far too small a
		//sample - the defect lands on well under 1% of stampings.
		int[] targets = {153, 745, 1, 15, 3, 5, 7, 9, 11, 13};
		List<BuildingCatalog.Entry> entries = BuildingCatalog.byKind(null);

		int stamped = 0, checked = 0, mismatches = 0, noDonor = 0;
		for (int tgt : targets) {
			byte[] targetModel = sub(gr.getDecompressedEntry(tgt), 1);
			if (targetModel == null || !BchMapModel.isMapModel(targetModel)) {
				continue;
			}
			int seen = 0;
			for (BuildingCatalog.Entry e : entries) {
				//every curated entry, plus a spread of the auto-harvested sweep
				if (e.auto && (seen++ % 37) != 0) {
					continue;
				}
				MapPrefab p = BuildingCatalog.extract(e);
				if (p == null || p.pieces.isEmpty()) {
					continue;
				}
				if (p.donorModel == null) {
					noDonor++;
					continue;
				}
				MapPrefab.StampResult r;
				try {
					r = p.stampGeometry(targetModel, 18, 18, 0);
				} catch (RuntimeException ex) {
					System.out.println("  FAIL " + e.name + " on " + tgt + ": stamp threw " + ex.getMessage());
					fails++;
					continue;
				}
				if (r.stamped.isEmpty()) {
					continue;
				}
				stamped++;
				BchMapModel out = new BchMapModel(r.newModel);
				BchMapModel don = new BchMapModel(p.donorModel);
				for (int i = 0; i < p.pieces.size() && i < r.landings.size(); i++) {
					MapPrefab.Landing l = r.landings.get(i);
					MapPrefab.Piece piece = p.pieces.get(i);
					if (l == null || piece.donorMeshIndex < 0) {
						continue;
					}
					int mesh = meshByName(out, l.material);
					if (mesh < 0) {
						continue;
					}
					BchMapModel.MeshAttr got = out.findAttr(mesh, 3);
					BchMapModel.MeshAttr want = don.findAttr(piece.donorMeshIndex, 3);
					if (got == null || want == null) {
						continue;
					}
					checked++;
					if (got.type != want.type || got.elems != want.elems || got.offset != want.offset) {
						System.out.println("  FAIL " + e.name + " -> region " + tgt + " piece " + i
								+ " (" + l.material + "): donor colour is type " + want.type
								+ "x" + want.elems + "@" + want.offset + ", landed in type "
								+ got.type + "x" + got.elems + "@" + got.offset);
						mismatches++;
						fails++;
					}
				}
			}
		}

		System.out.println("  stampings: " + stamped
				+ ", colour landings checked: " + checked
				+ ", format mismatches: " + mismatches
				+ (noDonor > 0 ? ", skipped without a donor model: " + noDonor : ""));
		if (checked == 0) {
			System.out.println("  FAIL: nothing was checked - the sweep proves nothing");
			fails++;
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	static int meshByName(BchMapModel m, String name) {
		for (int i = 0; i < m.meshCount; i++) {
			if (name != null && name.equals(m.getMaterialName(m.getMeshMaterialIndex(i)))) {
				return i;
			}
		}
		return -1;
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
