package ctrmap.tests;

import ctrmap.AreaForker;
import ctrmap.MasterTable;
import ctrmap.Workspace;
import ctrmap.WorkspaceIntegrity;
import ctrmap.WorkspaceSession;
import ctrmap.ZoneRemover;
import ctrmap.ZoneResource;
import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.function.IntPredicate;

/**
 * Removing added zones gives their area ids back, and the next fork takes them before growing the
 * archive (F043).
 *
 * <p>WHY THIS SUITE EXISTS. Area ids are an 8-bit index and retail uses 229 of the 256. Every
 * append forks an area per zone, spares included, and took each id from AreaData's end; removing
 * the zones gave none back. About six add-four-remove-four cycles ran the 27 spare ids out, and
 * from then on every append was refused - on a workspace with no added zones at all. The removed
 * zones' NPC registries also stayed behind, non-empty at ids no zone names, which is exactly the
 * mark the integrity check reads as an older build's misplaced fork.
 *
 * <p>What must NOT be given back or reused: a non-empty registry at an id no zone named BEFORE the
 * removal. That is the older build's wreckage, which the integrity check asks the user to move by
 * hand, and it may be the only copy of those NPCs.
 *
 * <p>ORDER: needs no dump. The tables and archives are written here.
 *
 * Usage: java ctrmap.tests.AreaIdBudgetTest
 */
public class AreaIdBudgetTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		theIdsGivenBackAreTakenFirst();
		addingAndRemovingZonesDoesNotRunTheBudgetOut();
		aForkOntoAnIdGivenBackKeepsTheTable();
		removingZonesGivesBackOnlyWhatTheyHeld();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** A master table whose zone i names area areas[i]. */
	static MasterTable master(int... areas) throws java.io.IOException {
		byte[] rows = new byte[areas.length * MasterTable.ROW];
		for (int z = 0; z < areas.length; z++) {
			MapResizeTest.putU16(rows, z * MasterTable.ROW + ZoneResource.AREA.headerOffset, areas[z]);
		}
		return MasterTable.detached(rows, areas.length);
	}

	static final IntPredicate ALL_EMPTY = id -> true;

	static String took(MasterTable m, int adLength, IntPredicate empty, int count) {
		try {
			return "took " + Arrays.toString(AreaForker.takeAreaIds(m, adLength, empty, count));
		} catch (java.io.IOException refused) {
			return refused.getMessage();
		}
	}

	static void theIdsGivenBackAreTakenFirst() throws Exception {
		System.out.println("--- the forked ids given back are taken first, lowest first (F043)");
		MasterTable m = master(3, 230, 230, 232);
		check(m.id(ZoneResource.AREA, 1) == 230 && m.id(ZoneResource.AREA, 3) == 232,
				"control: the table names what it was written with");
		check(AreaForker.unnamedForkedAreas(m, 234).equals(Arrays.asList(229, 231, 233)),
				"ids 229, 231 and 233 are forked and named by no zone: " + AreaForker.unnamedForkedAreas(m, 234));
		check(took(m, 234, ALL_EMPTY, 4).equals("took [229, 231, 233, 234]"),
				"with their registries empty, four forks take them, then the archive's end: " + took(m, 234, ALL_EMPTY, 4));
		IntPredicate wreck = id -> id != 231;
		check(took(m, 234, wreck, 3).equals("took [229, 233, 234]"),
				"an unnamed id whose registry still holds NPCs - an older build's misplaced fork - is never taken: "
				+ took(m, 234, wreck, 3));
		check(AreaForker.unnamedForkedAreas(master(3, 100, 227), 229).isEmpty(),
				"retail's ids are never handed out, named or not - they sit below the per-area table's slot");
		String said = took(m, 234, ALL_EMPTY, 26);
		check(said.contains("only 25 are left") && said.contains("255"),
				"the budget counts the ids given back - 3 plus 22 past the end - and refuses one more in words: " + said);
		check(took(m, 234, ALL_EMPTY, 25).startsWith("took"), "and grants exactly that many");
	}

	static void addingAndRemovingZonesDoesNotRunTheBudgetOut() throws Exception {
		System.out.println("--- adding four zones and removing them, twenty times, costs four ids once (F043)");
		int[] base = {3, 7, 21, 100};
		int adLength = 229;
		String refused = null;
		int cycles = 0;
		for (int cycle = 0; cycle < 20 && refused == null; cycle++) {
			int[] grown = Arrays.copyOf(base, base.length + 4);
			for (int i = base.length; i < grown.length; i++) {
				grown[i] = 21; //a new zone starts on its donor's area
			}
			MasterTable added = master(grown);
			int[] ids;
			try {
				//the removal before this cycle emptied the registries of what it gave back
				ids = AreaForker.takeAreaIds(added, adLength, ALL_EMPTY, 4);
			} catch (java.io.IOException out) {
				refused = "cycle " + (cycle + 1) + ": " + out.getMessage();
				break;
			}
			for (int i = 0; i < 4; i++) {
				added.set(ZoneResource.AREA, base.length + i, ids[i]);
				adLength = Math.max(adLength, ids[i] + 1);
			}
			cycles++;
			//removing them leaves the base rows only, which the next cycle starts from
		}
		check(refused == null && cycles == 20, "all twenty cycles were granted" + (refused == null ? "" : " - " + refused));
		check(adLength == 233, "and AreaData grew by four, once: " + adLength + " entries");
	}

	static void aForkOntoAnIdGivenBackKeepsTheTable() throws Exception {
		System.out.println("--- a fork onto an id given back keeps every row of the per-area table");
		int rows = 234;
		byte[] table = table(rows);
		AreaForker.ForkPlan p = AreaForker.planFork(IntegrityTest.zoneOn(21), new byte[]{1}, new byte[0], table, 231);
		check(p.newTableBytes.length == table.length, "the table keeps its " + rows + " rows: "
				+ p.newTableBytes.length / AreaForker.AREA_ROW);
		check(p.newTableBytes[231 * AreaForker.AREA_ROW] == 21 && p.newTableBytes[233 * AreaForker.AREA_ROW] == (byte) 233
				&& p.newTableBytes[230 * AreaForker.AREA_ROW] == (byte) 230,
				"row 231 is the source area's, and the rows on either side are untouched");
		p = AreaForker.planFork(IntegrityTest.zoneOn(21), new byte[]{1}, new byte[0], table, 234);
		check(p.newTableBytes.length == (rows + 1) * AreaForker.AREA_ROW, "and a fork at the end still grows it by one row");
	}

	/** A per-area table of {@code rows} rows, row r filled with r. */
	static byte[] table(int rows) {
		byte[] table = new byte[rows * AreaForker.AREA_ROW];
		for (int r = 0; r < rows; r++) {
			Arrays.fill(table, r * AreaForker.AREA_ROW, (r + 1) * AreaForker.AREA_ROW, (byte) r);
		}
		return table;
	}

	static final int ADDED = 538;

	/**
	 * 536 base zones and 2 added ones. Zone 1 (base) is forked onto area 230; added zone 536 is on
	 * 229 and 537 on 231. Registry 232 holds NPCs and no zone names it - an older build's
	 * misplaced fork. Registry 233 is empty and named by nobody. AreaData reaches 233.
	 */
	static WorkspaceSession game(String name) throws Exception {
		File root = Scratch.dir("ctrmap_areabudget_" + name);
		byte[][] zo = new byte[ADDED + 2][];
		byte[] rows = new byte[ADDED * MasterTable.ROW];
		for (int z = 0; z < ADDED; z++) {
			int area = z == 1 ? 230 : z == 536 ? 229 : z == 537 ? 231 : 0;
			zo[z] = IntegrityTest.zoneOn(area);
			MapResizeTest.putU16(rows, z * MasterTable.ROW + ZoneResource.AREA.headerOffset, area);
		}
		zo[ADDED] = rows;
		zo[ADDED + 1] = MapResizeTest.container(0x454E, new byte[ADDED][0]);
		File zoFile = new File(root, "zonedata");
		HandedGameTest.writeGarc(zoFile, zo);
		byte[][] areas = new byte[234][];
		byte[][] registries = new byte[234][];
		for (int i = 0; i < 234; i++) {
			areas[i] = new byte[]{(byte) i};
			registries[i] = i == 228 || i == 233 ? new byte[0] : new byte[]{(byte) i, 9};
		}
		areas[AreaForker.AD_GLOBAL_TABLE] = table(234);
		File ad = new File(root, "areas");
		HandedGameTest.writeGarc(ad, areas);
		File np = new File(root, "registries");
		HandedGameTest.writeGarc(np, registries);
		File mm = new File(root, "matrices");
		HandedGameTest.writeGarc(mm, MapResizeTest.matrix(0));
		File fd = new File(root, "regions");
		HandedGameTest.writeGarc(fd, MapResizeTest.region("region zero"));
		new File(root, "workspace").mkdirs();
		WorkspaceSession ws = Sessions.bare(new File(root, "workspace"), new File(root, "game"), GameType.ORAS)
				.withArchive(ArchiveType.ZONE_DATA, new GARC(zoFile))
				.withArchive(ArchiveType.AREA_DATA, new GARC(ad))
				.withArchive(ArchiveType.NPC_REGISTRIES, new GARC(np))
				.withArchive(ArchiveType.MAP_MATRIX, new GARC(mm))
				.withArchive(ArchiveType.FIELD_DATA, new GARC(fd));
		ws.prepareDirectories();
		Workspace.install(ws);
		return ws;
	}

	static void removingZonesGivesBackOnlyWhatTheyHeld() throws Exception {
		System.out.println("--- removing added zones gives back what they held, and nothing else (F043)");
		Workspace.reset();
		try {
			WorkspaceSession s = game("remove");
			check(MasterTable.read(s).zones() == ADDED && MasterTable.read(s).id(ZoneResource.AREA, 537) == 231,
					"control: the game has " + ADDED + " zones, the last on area 231");
			ZoneRemover.Removed done = ZoneRemover.remove(s);
			check(done.zones == 2 && MasterTable.read(s).zones() == 536, "the 2 added zones are removed: " + done.zones);
			check(done.areasBack.equals(Arrays.asList(229, 231)), "and the areas they held, 229 and 231, are given back: "
					+ done.areasBack);
			GARC np = s.getArchive(ArchiveType.NPC_REGISTRIES);
			check(np.length == 234 && np.getDecompressedEntry(229).length == 0 && np.getDecompressedEntry(231).length == 0,
					"their registries are empty, and the archive keeps its 234 entries");
			check(Arrays.equals(np.getDecompressedEntry(230), new byte[]{(byte) 230, 9})
					&& Arrays.equals(np.getDecompressedEntry(232), new byte[]{(byte) 232, 9})
					&& Arrays.equals(np.getDecompressedEntry(227), new byte[]{(byte) 227, 9}),
					"zone 1's area 230, retail's, and the misplaced registry at 232 keep their NPCs");
			String after = String.valueOf(WorkspaceIntegrity.check(s, true));
			check(after.contains("NPC registry [232] holds real NPCs"),
					"the integrity check still reports 232, and only 232, for the user to place by hand: " + after);

			System.out.println("--- the next zones added take the ids given back, and touch no other registry");
			MasterTable grown = MasterTable.detached(Arrays.copyOf(MasterTable.read(s).bytes(), ADDED * MasterTable.ROW), ADDED);
			grown.set(ZoneResource.AREA, 536, 21);
			grown.set(ZoneResource.AREA, 537, 21);
			byte[][] zos = {IntegrityTest.zoneOn(21), IntegrityTest.zoneOn(21)};
			AreaForker.forkAppendedAreas(zos, grown, new int[]{536, 537});
			check(grown.id(ZoneResource.AREA, 536) == 229 && grown.id(ZoneResource.AREA, 537) == 231,
					"the two new zones are on 229 and 231: " + grown.id(ZoneResource.AREA, 536) + ", "
					+ grown.id(ZoneResource.AREA, 537));
			File npDir = s.getExtractionDirectory(ArchiveType.NPC_REGISTRIES);
			File adDir = s.getExtractionDirectory(ArchiveType.AREA_DATA);
			check(Arrays.equals(Files.readAllBytes(new File(npDir, "229").toPath()), new byte[]{21, 9})
					&& Arrays.equals(Files.readAllBytes(new File(npDir, "231").toPath()), new byte[]{21, 9})
					&& Arrays.equals(Files.readAllBytes(new File(adDir, "231").toPath()), new byte[]{21}),
					"each got a copy of area 21 and its registry");
			check(!s.isPendingArtifact(new File(npDir, "230")) && !s.isPendingArtifact(new File(npDir, "232"))
					&& !s.isPendingArtifact(new File(npDir, "233")),
					"and no other registry was written - not zone 1's 230, not the misplaced 232");
			byte[] t = Files.readAllBytes(new File(adDir, String.valueOf(AreaForker.AD_GLOBAL_TABLE)).toPath());
			check(t.length == 234 * AreaForker.AREA_ROW && t[229 * AreaForker.AREA_ROW] == 21
					&& t[231 * AreaForker.AREA_ROW] == 21 && t[230 * AreaForker.AREA_ROW] == (byte) 230,
					"the per-area table keeps its 234 rows, with rows 229 and 231 now area 21's");
		} finally {
			Workspace.reset();
		}
		System.out.println("--- a single zone's fork takes an id given back too (F043)");
		Workspace.reset();
		try {
			WorkspaceSession s = game("single");
			check(ZoneRemover.remove(s).areasBack.equals(Arrays.asList(229, 231)), "control: the removal gave back 229 and 231");
			AreaForker.ForkResult r = AreaForker.forkArea(2);
			File npDir = s.getExtractionDirectory(ArchiveType.NPC_REGISTRIES);
			check(r.forked && r.newArea == 229 && MasterTable.read(s).id(ZoneResource.AREA, 2) == 229,
					"a single fork takes the lowest id given back: " + r.newArea);
			check(Arrays.equals(Files.readAllBytes(new File(npDir, "229").toPath()), new byte[]{0, 9})
					&& !s.isPendingArtifact(new File(npDir, "230")) && !s.isPendingArtifact(new File(npDir, "232")),
					"with area 0's registry, and no other registry written");
		} finally {
			Workspace.reset();
		}
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
