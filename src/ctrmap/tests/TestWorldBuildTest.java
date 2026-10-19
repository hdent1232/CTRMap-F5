package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.formats.containers.ContainerBytes;
import ctrmap.formats.scripts.ZoneScriptAnalyzer;
import ctrmap.formats.tilemap.TilePalette;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.gamedef.ArchiveType;
import java.awt.Point;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The test world builds, from the battery's own game data, and is what its guide says it is (F138).
 *
 * <p>The world the owner walks in game is a build product of {@link TestWorldBuilder}; this builds it into
 * scratch space every battery and reads it back from the packed game: the zone and the interior it took,
 * the painted stations' tiles, the NPCs and signs with their script cases, the trainers, the water's
 * animation, every warp of the way in and out, and the mod folder holding exactly what changed. Over the
 * battery's sources - RomFS_original_garcs and the live game folder - the story text is not retail (the live
 * game carries the owner's edits), so the build runs {@code --not-vanilla} and must SAY so: a world built
 * over an edited game is never taken for the canonical one, which is built from the cartridge image.
 *
 * <p>Usage: java ctrmap.tests.TestWorldBuildTest &lt;pristine garcs&gt; &lt;game folder&gt;
 */
public class TestWorldBuildTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File pristine = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		File game = new File(args.length > 1 ? args[1] : "../RomFS/000400000011C400");
		if (!pristine.isDirectory() || !game.isDirectory()) {
			check(false, "the battery's game data is here: " + pristine + " and " + game);
			finish();
			return;
		}
		System.out.println("--- the test world builds from the battery's game data, and says what was not retail");
		File out = new File(Scratch.dir("ctrmap_testworld"), "world");
		TestWorldBuilder.Built b = TestWorldBuilder.build(out, Arrays.asList(pristine, game), true);
		check(b.notRetail.size() == 1 && b.notRetail.get(0).startsWith("STORYTEXT "),
				"every archive but the story text is the retail one here, and that one is said: " + b.notRetail);

		System.out.println("--- the world took the two placeholder slots, and nothing that is not one");
		int world = b.slots.get(TestWorldBuilder.WORLD);
		Integer interior = b.slots.get(TestWorldBuilder.INTERIOR);
		check(world == 473 && interior != null && interior == 474,
				"the world's zone is 473 and its Pokemon Center's interior 474, the retail game's two placeholders: " + b.slots);
		List<Integer> offered = ctrmap.MapPainter.interiorSlots(-1);
		check(offered.isEmpty(), "with both taken, no slot is offered for another interior - least of all a named zone"
				+ " the engine may switch to, Littleroot's story variants 2-4 the first of them: " + offered);

		System.out.println("--- the NPCs, the signs and the trainers");
		check(b.npcToolSaid.isEmpty(), "the NPC tool asked nothing of a retail game - no question about zones that are the"
				+ " archive's tables: " + b.npcToolSaid);
		WorkspaceSession s = Workspace.session();
		TestWorldBuilder.open(world);
		int[] cell = ctrmap.MapPainter.firstRegionCell(TestWorldBuilder.loaded);
		ZoneEntities we = TestWorldBuilder.entities(s, world);
		ZoneScriptAnalyzer.Dispatch d = ZoneScriptAnalyzer.findDispatch(ctrmap.formats.scripts.PawnClosure.zoneScript(s, world));
		ZoneEntities.NPC talker = npcAt(we, TestWorldBuilder.global(cell, TestWorldBuilder.TALKER_AT));
		ZoneEntities.NPC challenger = npcAt(we, TestWorldBuilder.global(cell, TestWorldBuilder.CHALLENGER_AT));
		check(talker != null && d != null && d.cases.get(talker.script) != null,
				"the talking NPC stands at " + TestWorldBuilder.TALKER_AT + " and its script is a case of the zone's dispatch");
		check(challenger != null && d != null && d.cases.get(challenger.script) != null && challenger != talker,
				"the battle challenge stands at " + TestWorldBuilder.CHALLENGER_AT + " with a case of its own");
		//AT THEIR TILES: the clone keeps Route 102's own signs, so counting readable furniture counted those too (4)
		List<String> unread = new ArrayList<>();
		for (String at : TestWorldBuilder.SIGNS.keySet()) {
			String[] xy = at.split(",");
			Point tile = TestWorldBuilder.global(cell, new Point(Integer.parseInt(xy[0]), Integer.parseInt(xy[1])));
			boolean readable = false;
			for (ZoneEntities.Prop f : we.furniture) {
				readable |= f.x == tile.x && f.y == tile.y && d != null && d.cases.get(f.script) != null;
			}
			if (!readable) {
				unread.add(at);
			}
		}
		check(unread.isEmpty(), "each placed sign is furniture at its tile whose script is a dispatch case"
				+ (unread.isEmpty() ? "" : " - not: " + unread));
		int namesEntry = s.profile().textIndex(ctrmap.gamedef.GameProfile.TextIndex.TRAINER_NAMES);
		List<String> names = ctrmap.formats.text.GFMessageFile.parse(Files.readAllBytes(
				s.getWorkspaceFile(ArchiveType.GAMETEXT, namesEntry).toPath()), false);
		check(Arrays.equals(b.trainers, new int[]{51, 52, 61}), "the challenge battles retail's blank entries 51, 52 and 61, chosen"
				+ " from the data: " + Arrays.toString(b.trainers));
		for (int k = 0; k < b.trainers.length; k++) {
			int tid = b.trainers[k];
			ctrmap.formats.trainers.TrainerEntry t = ctrmap.formats.trainers.TrainerEntry.read(
					s.getArchive(ArchiveType.TRAINER_DATA).getDecompressedEntry(tid),
					s.getArchive(ArchiveType.TRAINER_POKE).getDecompressedEntry(tid));
			int[][] party = (int[][]) TestWorldBuilder.LINEUP[k][1];
			boolean same = t.party.size() == party.length;
			for (int m = 0; same && m < party.length; m++) {
				same = t.party.get(m).species == party[m][0] && t.party.get(m).level == party[m][1];
			}
			check(TestWorldBuilder.LINEUP[k][0].equals(names.get(tid)) && same,
					"trainer " + tid + " reads back as " + names.get(tid) + " with the lineup's party");
		}

		System.out.println("--- the way in, the way out, and the Pokemon Center round trip");
		Point mat = TestWorldBuilder.global(cell, TestWorldBuilder.MAT_AT);
		int matIndex = warpAt(we, mat);
		check(matIndex >= 0 && we.warps.get(matIndex).targetZone == 6 && we.warps.get(matIndex).targetWarpId == 1,
				"the arrival mat at " + TestWorldBuilder.MAT_AT + " leads back to Littleroot (zone 6 warp 1)");
		ZoneEntities.Warp littleroot = TestWorldBuilder.entities(s, 6).warps.get(1);
		ZoneEntities.Warp ownHouse = TestWorldBuilder.entities(s, 226).warps.get(0);
		check(littleroot.targetZone == world && littleroot.targetWarpId == matIndex
				&& ownHouse.targetZone == world && ownHouse.targetWarpId == matIndex,
				"both doors that led into the rival's house - Littleroot's, and the one in the player's own house map -"
				+ " lead to the mat");
		int door = -1;
		for (int w = 0; w < we.warps.size(); w++) {
			door = we.warps.get(w).targetZone == interior ? w : door;
		}
		boolean back = false;
		if (door >= 0) {
			for (ZoneEntities.Warp w : TestWorldBuilder.entities(s, interior).warps) {
				back |= w.targetZone == world && w.targetWarpId == door;
			}
		}
		check(door >= 0 && back, "the Pokemon Center's door leads into zone " + interior + ", whose exit leads back to that door");

		System.out.println("--- the painted stations, the water, and the patch inside the Center");
		byte[] tilemap = ContainerBytes.subfile(Files.readAllBytes(s.getWorkspaceFile(ArchiveType.FIELD_DATA, cell[0]).toPath()), 0);
		Object[][] stations = {
			{TilePalette.ROCK, 4, 20}, {TilePalette.TALL_GRASS, 9, 9}, {TilePalette.DEEP_SAND, 11, 15},
			{TilePalette.BIKE_RAIL, 7, 15}, {TilePalette.CAVE, 30, 8}, {TilePalette.INDOOR, 30, 12},
			{TilePalette.WALKWAY, 30, 16}, {TilePalette.ICE, 30, 21}, {TilePalette.SAND, 30, 26},
			{TilePalette.WATER, 20, 23}, {TilePalette.WATERFALL, 19, 27}, {TilePalette.LEDGE_S, 15, 12},
		};
		List<String> wrong = new ArrayList<>();
		for (Object[] st : stations) {
			if (!isBrush(tilemap, (Integer) st[1], (Integer) st[2], (TilePalette) st[0])) {
				wrong.add(st[0] + " at (" + st[1] + "," + st[2] + ")");
			}
		}
		check(wrong.isEmpty(), "every station's tile carries its brush's tuple in the world's map (" + stations.length
				+ " checked)" + (wrong.isEmpty() ? "" : " - not: " + wrong));
		check(ctrmap.MapPainter.zoneWaterScrolls(TestWorldBuilder.loaded, TestWorldBuilder.loaded.open().header.areadataID),
				"the world's own area binds the sea-scroll animation, so its water moves");
		ctrmap.formats.encounters.EncounterTable own = ctrmap.humaninterface.EncounterEditDialog.readEncounters(s, world);
		ctrmap.formats.encounters.EncounterTable donor = ctrmap.humaninterface.EncounterEditDialog.readEncounters(s, 24);
		check(!own.isEmpty() && Arrays.equals(own.toBlob(), donor.toBlob()),
				"its tall grass meets Route 102's wild Pokemon: the world's encounter table is zone 24's");
		TestWorldBuilder.open(interior);
		int[] icell = ctrmap.MapPainter.firstRegionCell(TestWorldBuilder.loaded);
		byte[] itilemap = ContainerBytes.subfile(Files.readAllBytes(s.getWorkspaceFile(ArchiveType.FIELD_DATA, icell[0]).toPath()), 0);
		boolean patched = true;
		for (int y = TestWorldBuilder.PATCH[1]; y <= TestWorldBuilder.PATCH[3]; y++) {
			for (int x = TestWorldBuilder.PATCH[0]; x <= TestWorldBuilder.PATCH[2]; x++) {
				patched &= isBrush(itilemap, x, y, TilePalette.PATH);
			}
		}
		check(patched, "the 3x2 patch inside the Center is path, painted in place on the cloned room");

		System.out.println("--- the mod folder holds what changed, and the stamp says what built it");
		List<String> shipped = new ArrayList<>();
		File romfs = new File(out, TestWorldBuilder.ROMFS);
		java.util.Deque<File> walk = new java.util.ArrayDeque<>();
		walk.add(romfs);
		while (!walk.isEmpty()) {
			File[] here = walk.pop().listFiles();
			for (File f : here == null ? new File[0] : here) {
				if (f.isDirectory()) {
					walk.add(f);
				} else {
					shipped.add(romfs.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/'));
				}
			}
		}
		java.util.Collections.sort(shipped);
		List<String> changed = new ArrayList<>(b.changed);
		java.util.Collections.sort(changed);
		check(!shipped.isEmpty() && shipped.equals(changed), "the mod folder holds exactly the " + changed.size()
				+ " archives the build changed: " + shipped);
		String stamp = new String(Files.readAllBytes(new File(out, TestWorldBuilder.STAMP).toPath()), StandardCharsets.UTF_8);
		check(stamp.contains("commit ") && stamp.contains("source digest ") && stamp.contains("NOT CANONICAL"),
				"its stamp names the commit and the tree that built it, and says it is not the canonical world");

		System.out.println("--- the same code and the same sources build the same world");
		File again = new File(Scratch.dir("ctrmap_testworld_again"), "world");
		TestWorldBuilder.build(again, Arrays.asList(pristine, game), true);
		List<String> differ = new ArrayList<>();
		for (String rel : shipped) {
			byte[] first = Files.readAllBytes(new File(romfs, rel).toPath());
			File second = new File(new File(again, TestWorldBuilder.ROMFS), rel);
			if (!second.isFile() || !Arrays.equals(first, Files.readAllBytes(second.toPath()))) {
				differ.add(rel);
			}
		}
		check(differ.isEmpty(), "a second build writes every one of the " + shipped.size() + " files byte for byte as the first"
				+ (differ.isEmpty() ? "" : " - these differ: " + differ));
		finish();
	}

	static ZoneEntities.NPC npcAt(ZoneEntities e, Point tile) {
		for (ZoneEntities.NPC n : e.npcs) {
			if (n.xTile == tile.x && n.yTile == tile.y) {
				return n;
			}
		}
		return null;
	}

	static int warpAt(ZoneEntities e, Point tile) {
		for (int w = 0; w < e.warps.size(); w++) {
			if (e.warps.get(w).x == ctrmap.formats.zone.WorldGrid.tileCentre(tile.x)
					&& e.warps.get(w).y == ctrmap.formats.zone.WorldGrid.tileCentre(tile.y)) {
				return w;
			}
		}
		return -1;
	}

	/** Whether tile (x, y) of a region's tilemap carries the brush's four bytes. */
	static boolean isBrush(byte[] tilemap, int x, int y, TilePalette brush) {
		int at = 4 + (y * TestWorldBuilder.DIM + x) * 4;
		if (tilemap == null || at + 4 > tilemap.length) {
			return false;
		}
		for (int k = 0; k < 4; k++) {
			if ((tilemap[at + k] & 0xFF) != brush.tuple[k]) {
				return false;
			}
		}
		return true;
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}

	static void finish() {
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails != 0) {
			System.exit(1);
		}
	}
}
