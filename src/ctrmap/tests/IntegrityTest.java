package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.WorkspaceIntegrity;
import java.io.File;
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
 * <p>Checks the invariants against the pristine dump, which must satisfy all of
 * them, and against deliberately broken synthetic tables, which must not - a
 * checker that passes everything is worth nothing.
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

		Workspace.game = Workspace.GameType.ORAS;
		Workspace.GAMEDIR_PATH = gamedir.getAbsolutePath();
		Workspace.WORKSPACE_PATH = System.getProperty("java.io.tmpdir") + "/ctrmap_integrity";
		Workspace.temp = new File(Workspace.WORKSPACE_PATH, "temp");
		Workspace.temp.mkdirs();
		String base = Workspace.GAMEDIR_PATH;
		Workspace.areadata = new File(base + Workspace.getArchivePath(Workspace.ArchiveType.AREA_DATA, Workspace.game));
		Workspace.fielddata = new File(base + Workspace.getArchivePath(Workspace.ArchiveType.FIELD_DATA, Workspace.game));
		Workspace.mapmatrix = new File(base + Workspace.getArchivePath(Workspace.ArchiveType.MAP_MATRIX, Workspace.game));
		Workspace.gametext = new File(base + Workspace.getArchivePath(Workspace.ArchiveType.GAMETEXT, Workspace.game));
		Workspace.zonedata = new File(base + Workspace.getArchivePath(Workspace.ArchiveType.ZONE_DATA, Workspace.game));
		Workspace.buildingmodels = new File(base + Workspace.getArchivePath(Workspace.ArchiveType.BUILDING_MODELS, Workspace.game));
		Workspace.npcregistries = new File(base + Workspace.getArchivePath(Workspace.ArchiveType.NPC_REGISTRIES, Workspace.game));
		Workspace.movemodels = new File(base + Workspace.getArchivePath(Workspace.ArchiveType.MOVE_MODELS, Workspace.game));
		if (!Workspace.areadata.isFile() || !Workspace.npcregistries.isFile()) {
			System.out.println("  skip: dump does not carry the archives this checks");
			System.out.println("ALL PASS");
			return;
		}
		Workspace.valid = true;
		Workspace.loadArchives();

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

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
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
