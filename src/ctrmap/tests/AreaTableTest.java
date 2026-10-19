package ctrmap.tests;

import ctrmap.AreaForker;
import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.ZoneTables;
import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import ctrmap.gamedef.GameType;
import ctrmap.humaninterface.ExtrasPanel;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

/**
 * Where AreaData's per-area table is, and which entries are areas, is one answer before and after
 * an area fork (F235).
 *
 * <p>WHY THIS SUITE EXISTS. The tree held two models of AreaData. ZoneTables said the table is the
 * last entry - length minus the trailing tables - and the Extras panel used that number both as the
 * area count and as the table's index. AreaForker said the table is entry 228 and appends forked
 * areas after it. They agree until the first fork; after it, "inject into ALL areas" walked onto the
 * table and threw with the 228 retail areas written and the forked one not, and the per-area-table
 * patch opened the last forked area as the table.
 *
 * <p>ORDER: needs no dump. The archive is a GARC whose only field read is its length, and the table
 * is a file of zeros.
 *
 * Usage: java ctrmap.tests.AreaTableTest [src root]
 */
public class AreaTableTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		File ws = Scratch.dir("ctrmap_areatable_ws");
		File game = Scratch.dir("ctrmap_areatable_game");
		try {
			//each section reports a throw as a failure and the next still runs, so a broken answer is
			//named by the check about it rather than by whichever section met it first
			section("retail", () -> aRetailArchive(ws, game));
			section("fork", () -> afterAFork(ws, game));
			section("profile", AreaTableTest::oneAnswerForTheTable);
			section("unmeasured", () -> aGameNobodyMeasuredIsRefused(ws, game));
			section("patch", () -> theRowsPatchedAreTheAreas(ws, game));
			section("panel", () -> theExtrasPanelAsksTheseAnswers(root));
		} finally {
			Workspace.reset();
			Scratch.deleteTree(ws);
			Scratch.deleteTree(game);
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	interface Section {

		void run() throws Exception;
	}

	static void section(String name, Section s) {
		try {
			s.run();
		} catch (Exception threw) {
			check(false, "the " + name + " section threw: " + threw);
		}
	}

	static GARC archive(int length) throws Exception {
		GARC g = (GARC) RenderLoopTest.bare(GARC.class);
		g.length = length;
		return g;
	}

	static GARC open(File ws, File game, GameType g, int length) throws Exception {
		GARC ad = archive(length);
		Workspace.install(new WorkspaceSession(ws, game, g, Collections.singletonMap(ArchiveType.AREA_DATA, ad)));
		return ad;
	}

	static void aRetailArchive(File ws, File game) throws Exception {
		System.out.println("--- a retail AreaData: 228 areas, then the per-area table");
		GARC ad = open(ws, game, GameType.ORAS, 229);
		int[] ids = ExtrasPanel.areaIds();
		check(ZoneTables.areaTableIndex(ad) == 228 && ids.length == 228 && ids[227] == 227 && ZoneTables.areaIds(ad).length == 228,
				"control: the table is entry 228 and the areas 0..227 (" + ids.length + " of them)");
		check(ExtrasPanel.areaTableRows(ids) == 228 && ExtrasPanel.areaTableRowSize(10032, ExtrasPanel.areaTableRows(ids)) == 44,
				"and the retail table's 10032 bytes are 228 rows of 44");
	}

	static void afterAFork(File ws, File game) throws Exception {
		System.out.println("--- after one area fork, appended after the table (F235)");
		GARC ad = open(ws, game, GameType.ORAS, 230);
		int[] ids = ExtrasPanel.areaIds();
		check(ZoneTables.areaTableIndex(ad) == 228, "the table is still entry 228 - the old rule named entry 229, the forked area");
		check(ids.length == 229 && ids[227] == 227 && ids[228] == 229 && Arrays.binarySearch(ids, 228) < 0,
				"the areas are 0..227 and 229 - the forked one in, the table out (the old range was 0..228, the table in and the fork out)");
		check(ZoneTables.areaIds(ad).length == 229, "229 areas");
		//AreaForker.planFork grows the table to (newArea + 1) * AREA_ROW: a row per id, 228 a filler
		long grown = (229 + 1) * (long) AreaForker.AREA_ROW;
		int rows = ExtrasPanel.areaTableRows(ids);
		int rowSize = -1;
		String refused = "";
		try {
			rowSize = ExtrasPanel.areaTableRowSize(grown, rows);
		} catch (java.io.IOException ex) {
			refused = ex.getMessage();
		}
		check(rows == 230 && rowSize == 44, "the grown table divides into " + rows + " rows of " + rowSize
				+ ", one per area id up to the fork " + refused);

		open(ws, game, GameType.ORAS, 232);
		ids = ExtrasPanel.areaIds();
		check(ids.length == 231 && ids[ids.length - 1] == 231 && ExtrasPanel.areaTableRows(ids) == 232,
				"and after three: 231 areas, the last 231, the table 232 rows");
	}

	static void oneAnswerForTheTable() {
		System.out.println("--- the profile and the fork name the same table");
		check(GameProfile.of(GameType.ORAS).areaTableIndex() == AreaForker.AD_GLOBAL_TABLE,
				"ORAS's profile and AreaForker both say entry " + AreaForker.AD_GLOBAL_TABLE);
		check(GameProfile.of(GameType.XY).areaTableIndex() == 170, "XY's says 170, as upstream addresses it");
	}

	static void aGameNobodyMeasuredIsRefused(File ws, File game) throws Exception {
		System.out.println("--- a game nobody measured, or an archive too short, is refused in words");
		open(ws, game, GameType.SM, 300);
		String said = "";
		try {
			ExtrasPanel.areaIds();
		} catch (java.io.IOException ex) {
			said = ex.getMessage();
		}
		check(said.contains("areaTableIndex") && said.contains(GameProfile.of(GameType.SM).displayName()),
				"Sun/Moon: refused, naming the game and the number missing: " + said.split("\n")[0]);
		open(ws, game, GameType.ORAS, 100);
		said = "";
		try {
			ExtrasPanel.areaIds();
		} catch (java.io.IOException ex) {
			said = ex.getMessage();
		}
		check(said.contains("no entry 228"), "an ORAS archive of 100 entries: refused, not read as 99 areas: " + said);
	}

	static void theRowsPatchedAreTheAreas(File ws, File game) throws Exception {
		System.out.println("--- a per-area patch writes the areas' rows and no other (F235)");
		open(ws, game, GameType.ORAS, 230);
		int[] ids = ExtrasPanel.areaIds();
		File table = new File(ws, "table.bin");
		Files.write(table.toPath(), new byte[230 * 44]);
		ExtrasPanel.patchAreaRows(table, ids, 44, ExtrasPanel.CAMERA_FLAG_OFFSET, (byte) 1);
		byte[] b = Files.readAllBytes(table.toPath());
		int written = 0;
		for (byte x : b) {
			written += x != 0 ? 1 : 0;
		}
		check(written == 229 && b[229 * 44 + ExtrasPanel.CAMERA_FLAG_OFFSET] == 1 && b[227 * 44 + ExtrasPanel.CAMERA_FLAG_OFFSET] == 1,
				"229 bytes written, one per area - the forked area 229's row included: " + written);
		check(b[228 * 44 + ExtrasPanel.CAMERA_FLAG_OFFSET] == 0, "and the filler row 228, the table's own id, untouched");
	}

	static void theExtrasPanelAsksTheseAnswers(File root) throws Exception {
		System.out.println("--- the Extras panel's mass edits ask these answers (F235)");
		String src = SourceSeamTest.stripComments(DialogSeamTest.read(new File(root, "ctrmap/humaninterface/ExtrasPanel.java")));
		check(src.contains("areas = areaIds();") && src.contains("for (int i : areas)") && !src.contains("i < areas;"),
				"\"inject into ALL areas\" walks the area ids, not a range that runs onto the table");
		String open = PropEditFormGuardsTest.body(src, "private AreaTable openAreaTable() throws IOException {");
		check(open.contains("int index = ZoneTables.areaTableIndex(") && open.contains("getWorkspaceFile(ArchiveType.AREA_DATA, index)")
				&& open.contains("areaTableRows(areas)"),
				"and the per-area-table patches open the profile's table, divided by its rows");
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
