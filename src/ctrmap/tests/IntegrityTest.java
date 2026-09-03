package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.WorkspaceIntegrity;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Cross-archive references must resolve.
 *
 * <p>Each archive validates on its own; nothing validated the indexes between
 * them. That gap produced the same failure repeatedly - an area fork grew
 * AreaData to 230 entries and NPCRegistries to 229, so the new area existed
 * with no registry behind it and every load of that zone threw. It was found by
 * hand each time, always well after the edit that caused it.
 *
 * <p>Checks the invariants against a scratch copy of the pristine dump, which
 * must satisfy all of them, and against deliberately broken data - a synthetic
 * area table, and a real archive with a matrix cell pointing at a region that
 * does not exist - which must not. A checker that passes everything is worth
 * nothing, and this one silently passed every matrix in the game.
 *
 * Usage: java ctrmap.tests.IntegrityTest &lt;romfs-root&gt;
 */
public class IntegrityTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		String root = args.length > 0 ? args[0] : "../RomFS_original_garcs";
		File gamedir = new File(root);
		if (!gamedir.isDirectory()) {
			System.out.println("  skip: no dump at " + gamedir);
			System.out.println("ALL PASS");
			return;
		}

		if (!new File(gamedir.getAbsolutePath()
				+ Workspace.getArchivePath(Workspace.ArchiveType.NPC_REGISTRIES,
						Workspace.GameType.ORAS)).isFile()) {
			System.out.println("  skip: dump does not carry the archives this checks");
			System.out.println("ALL PASS");
			return;
		}
		//a scratch copy, because the last check below has to BREAK an archive
		ScratchGame.open(gamedir);

		int areas = Workspace.getArchive(Workspace.ArchiveType.AREA_DATA).length;
		int regs = Workspace.getArchive(Workspace.ArchiveType.NPC_REGISTRIES).length;
		int zones = Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA).length - 2;
		int mats = Workspace.getArchive(Workspace.ArchiveType.MAP_MATRIX).length;
		int regions = Workspace.getArchive(Workspace.ArchiveType.FIELD_DATA).length;
		System.out.println("  dump: " + areas + " areas, " + regs + " registries, " + zones
				+ " zones, " + mats + " matrices, " + regions + " regions");

		//--- the retail game must satisfy every invariant ----------------------
		List<String> bad = WorkspaceIntegrity.check(true);
		if (bad.isEmpty()) {
			System.out.println("  ok: the retail game passes every cross-archive invariant");
		} else {
			for (String b : bad) {
				System.out.println("  FAIL: retail reports a problem: " + b);
			}
			fails += bad.size();
		}

		//--- the invariant is about INDEXES IN USE, not archive lengths --------
		//Two count-based versions of this rule were written before anyone
		//measured. "One registry per area" calls retail broken. "Registries
		//trail areas by one" matches retail by accident - retail's highest
		//areadataID is 227 against 228 registries, so ten AreaData entries are
		//never named by any zone at all. Neither notices the failure that
		//actually happens: a forked zone whose new area id is past the end of
		//the registry archive.
		int maxArea = highestAreaInUse();
		System.out.println("  highest areadataID in use: " + maxArea
				+ " (registries go up to " + (regs - 1) + ")");
		check(maxArea < regs, "every area id in use has a registry at that index");
		check(maxArea < areas, "every area id in use has an AreaData entry");
		check(!needsMore(227, 228), "retail's 227-against-228 is not reported");
		check(needsMore(229, 229), "an area id past the last registry IS reported");

		danglingRegionIsFound(mats, regions);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * A matrix cell naming a FieldData region that does not exist must be
	 * reported - and the pass has to reach every matrix to find it.
	 *
	 * <p>The region pass read the grid's width and height from offset 4 and 6
	 * of the raw MapMatrix container. A Gamefreak container starts u16 magic,
	 * u16 subfile count, u32 offsets[], so those bytes are offsets[0]: all 431
	 * retail matrices measured 16x0, every one failed the shape test, and the
	 * pass scanned nothing while reporting a clean bill of health. The check
	 * lists "a zone fork rewired a map matrix at a region index the FieldData
	 * archive did not yet have" as one of the three incidents it was written
	 * for, and it could not have caught any of them.
	 *
	 * <p>Breaks the LAST matrix, not the first: a pass that stops early is the
	 * same silent failure in a smaller size.
	 */
	static void danglingRegionIsFound(int matrices, int regions) throws Exception {
		int last = matrices - 1;
		File matFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.MAP_MATRIX, last);
		byte[] mat = Files.readAllBytes(matFile.toPath());
		int sub0 = u32(mat, 4);
		int cell = sub0 + 8;
		int dangling = regions + 4143; //well past the end, and not 0xFFFF
		mat[cell] = (byte) (dangling & 0xFF);
		mat[cell + 1] = (byte) ((dangling >> 8) & 0xFF);
		Files.write(matFile.toPath(), mat);
		Workspace.addPersist(matFile);
		Workspace.packArchives((percent, what) -> {
		});

		String bad = WorkspaceIntegrity.check(true).toString();
		check(bad.contains("FieldData region that does not exist"),
				"a matrix cell naming region " + dangling + " of " + regions + " is reported: " + bad);
		check(bad.contains("matrix " + last),
				"and the pass reached the last matrix (" + last + ") to find it");
	}

	private static int u32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}

	/** The rule in isolation: an id is unusable when no registry has that index. */
	static boolean needsMore(int maxAreaInUse, int registryCount) {
		return maxAreaInUse >= registryCount;
	}

	/** Highest areadataID any zone actually names, from the master table. */
	static int highestAreaInUse() throws Exception {
		ctrmap.formats.garc.GARC zo = Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA);
		byte[] m = zo.getDecompressedEntry(zo.length - 2);
		int best = -1;
		for (int z = 0; z < Math.min(m.length / 0x38, zo.length - 2); z++) {
			int a = (m[z * 0x38 + 2] & 0xFF) | ((m[z * 0x38 + 3] & 0xFF) << 8);
			best = Math.max(best, a);
		}
		return best;
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
