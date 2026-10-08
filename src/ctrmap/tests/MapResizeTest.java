package ctrmap.tests;

import ctrmap.MapResizer;
import ctrmap.MasterTable;
import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.ZoneResource;
import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * Growing a zone's map, on a game written by hand: the old cells become the zone's own when it
 * shares them, every write goes to the game the resize is handed, and a resize that fails half way
 * leaves nothing behind.
 *
 * <p>WHY THIS SUITE EXISTS. The resize action told the user that growing a map made the zone's
 * geometry private first (F018), and nothing did: the old cells stayed on regions other zones draw,
 * so painting one repainted the town the zone was cloned from. Its writes had been moved onto the
 * session it is handed (F019) and into one unit of work (F227), and neither could be proven without
 * a game - this is that game. Three zones, two of them on one map, two maps of one region each,
 * written in the shapes the containers and {@link GARC} parse.
 *
 * <p>ORDER: needs no dump and no display; writes only under {@link Scratch}.
 *
 * Usage: java ctrmap.tests.MapResizeTest
 */
public class MapResizeTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		Workspace.reset();
		try {
			aWorkspaceFolderNotThereYetIsMade();
			aSharedMapsOldCellsBecomeTheZonesOwn();
			aPrivateMapIsGrownWithNoCopies();
			everyWriteGoesToTheGameItIsHanded();
			aRenameIsBoundedByTheGameItIsHanded();
			aResizeThatFailsHalfWayLeavesNothing();
			aPrivateMapIsShrunkAndWhatItDropsIsSaid();
			aSharedMapIsCroppedBeforeItIsCopied();
			aMapGrowsOneWayAndShrinksTheOther();
			aShrinkOverTheZonesOwnContentIsRefused();
			aZoneWhoseEntitiesCannotBeReadIsNotShrunk();
			theHeadlessResizeCropsEveryGridAndCutsTheCamera();
		} finally {
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/** Zones 0 and 1 on map 0 (region 0), zone 2 on map 1 (region 1); ORAS's two trailing tables. */
	static WorkspaceSession game(String name) throws Exception {
		return game(name, new byte[][]{zone(0), zone(0), zone(1)}, new int[]{0, 0, 1},
				new byte[][]{matrix(0), matrix(1)}, region("region zero"), region("region one"));
	}

	/**
	 * Zones 0 and 1 on map 0, two cells wide over regions 0 and 1; zone 2 on map 1, two cells wide over
	 * regions 2 and 3. Zones 0 and 2 carry the entities given, zone 1 none - a shrink must read them.
	 */
	static WorkspaceSession wideGame(String name, byte[] zone0, byte[] zone2) throws Exception {
		return game(name, new byte[][]{zone0, zone(0), zone2}, new int[]{0, 0, 1},
				new byte[][]{matrix(2, 1, 0, 1), matrix(2, 1, 2, 3)},
				region("region zero"), region("region one"), region("region two"), region("region three"));
	}

	/** The zones, their master rows' maps, the maps and the regions, as archives under a fresh workspace. */
	static WorkspaceSession game(String name, byte[][] zones, int[] maps, byte[][] matrices, byte[]... regions)
			throws Exception {
		File root = Scratch.dir("ctrmap_resize_" + name);
		File gameDir = new File(root, "game");
		File archives = new File(root, "archives");
		archives.mkdirs();
		File zo = new File(archives, "zonedata");
		byte[][] zoEntries = Arrays.copyOf(zones, zones.length + 2);
		zoEntries[zones.length] = master(maps);
		zoEntries[zones.length + 1] = "EN".getBytes("US-ASCII");
		HandedGameTest.writeGarc(zo, zoEntries);
		File mm = new File(archives, "matrices");
		HandedGameTest.writeGarc(mm, matrices);
		File fd = new File(archives, "regions");
		HandedGameTest.writeGarc(fd, regions);
		new File(root, "workspace").mkdirs();
		WorkspaceSession ws = Sessions.bare(new File(root, "workspace"), gameDir, GameType.ORAS)
				.withArchive(ArchiveType.ZONE_DATA, new GARC(zo))
				.withArchive(ArchiveType.MAP_MATRIX, new GARC(mm))
				.withArchive(ArchiveType.FIELD_DATA, new GARC(fd));
		ws.prepareDirectories();
		//withArchive answers a new session, so the one returned is the one made the open game
		Workspace.install(ws);
		return ws;
	}

	/** A workspace folder that is not there is refused in words, not a NullPointerException. */
	static void aWorkspaceFolderNotThereYetIsMade() throws Exception {
		System.out.println("--- a workspace folder that is not there is refused in words, not a crash");
		File dir = new File(Scratch.dir("ctrmap_resize_fresh"), "not-yet");
		try {
			WorkspaceSession.mkDirsIfNotContains(dir, new String[]{"zone"});
			check(false, "a workspace folder that is not there is refused in words (it was made)");
		} catch (IllegalStateException said) {
			check(said.getMessage().contains("does not exist"),
					"a workspace folder that is not there is refused in words: " + said.getMessage());
		} catch (RuntimeException crash) {
			check(false, "a workspace folder that is not there is refused in words (it threw " + crash + ")");
		}
	}

	// ------------------------------------------------------------ 1. F018
	static void aSharedMapsOldCellsBecomeTheZonesOwn() throws Exception {
		System.out.println("--- growing a shared map gives the zone's old cells its own copies (F018)");
		WorkspaceSession ws = game("shared");
		MapResizer.ResizeResult r = MapResizer.resize(ws, 0, 2, 1);
		int[] grid = grid(ws, r.newMatrix);
		check(grid.length == 2 && grid[0] != 0,
				"the grown map's old cell no longer draws region 0, which zone 1 draws too " + Arrays.toString(grid));
		check(Arrays.equals(r.ownRegions, new int[]{2}) && grid[0] == 2,
				"it draws the zone's own copy, region 2 " + Arrays.toString(r.ownRegions));
		check(Arrays.equals(bytes(ws, ArchiveType.FIELD_DATA, 2), bytes(ws, ArchiveType.FIELD_DATA, 0)),
				"and the copy is region 0 byte for byte, so the zone looks the same until it is edited");
		check(Arrays.equals(r.newRegions, new int[]{3}) && grid[1] == 3,
				"the new cell comes after the copy " + Arrays.toString(r.newRegions));
		check(Arrays.equals(grid(ws, 0), new int[]{0}), "zone 1's map is untouched: map 0 still draws region 0");
		MasterTable t = MasterTable.read(ws);
		check(t.id(ZoneResource.MAP, 0) == r.newMatrix && t.id(ZoneResource.MAP, 1) == 0,
				"the master table points zone 0 at the grown map and leaves zone 1 on map 0");
	}

	static void aPrivateMapIsGrownWithNoCopies() throws Exception {
		System.out.println("--- growing a map the zone already owns copies nothing");
		WorkspaceSession ws = game("private");
		MapResizer.ResizeResult r = MapResizer.resize(ws, 2, 2, 1);
		check(r.ownRegions.length == 0, "no copy is made of a map nobody else draws " + Arrays.toString(r.ownRegions));
		check(Arrays.equals(grid(ws, r.newMatrix), new int[]{1, 2}),
				"the old cell stays on the zone's own region 1 and the new cell is region 2 "
				+ Arrays.toString(grid(ws, r.newMatrix)));
	}

	// ------------------------------------------------------------ 2. F019
	static void everyWriteGoesToTheGameItIsHanded() throws Exception {
		System.out.println("--- every write lands in the game the resize is handed, never the open one (F019)");
		WorkspaceSession ws = game("handed");
		WorkspaceSession open = game("open");
		check(Workspace.session() == open && open != ws, "another game is the open one");
		MapResizer.resize(ws, 0, 2, 1);
		check(open.persistPaths().isEmpty(), "the open game was told of no edit " + open.persistPaths());
		check(ws.persistPaths().size() == 5,
				"the handed game was told of all five: the copy, the new region, the matrix, the zone and the"
				+ " master table (" + ws.persistPaths().size() + ")");
	}

	/**
	 * F019's second instance: the zone rename was handed its session and bounded the zone index
	 * by the GLOBAL game's master index, so a rename handed one game took its range - and its idea
	 * of which entry is the master table - from whichever game was open.
	 */
	static void aRenameIsBoundedByTheGameItIsHanded() throws Exception {
		System.out.println("--- the zone rename asks the game it is handed how many zones there are (F019)");
		WorkspaceSession ws = game("rename");
		File root = Scratch.dir("ctrmap_resize_names");
		File gt = new File(root, "gametext");
		int names = ctrmap.formats.text.LocationNames.gametextIndex(ws);
		byte[][] entries = new byte[names + 1][];
		for (int i = 0; i < names; i++) {
			entries[i] = new byte[]{0};
		}
		entries[names] = ctrmap.formats.text.GFMessageFile.write(Arrays.asList("", "Old Town", "Shared Route"));
		HandedGameTest.writeGarc(gt, entries);
		WorkspaceSession named = ws.withArchive(ArchiveType.GAMETEXT, new GARC(gt));
		//zone 2 alone names location line 1, "Old Town", so the rename is in place
		MasterTable rows = MasterTable.read(named);
		putU16(rows.bytes(), 2 * MasterTable.ROW + ctrmap.ZoneManager.PARENTMAP_OFFSET, 1);
		rows.write();
		WorkspaceSession open = oneZoneGame();
		check(Workspace.session() == open, "a game with ONE zone is the open one");
		String refused = null;
		ctrmap.ZoneManager.RenameResult r = null;
		try {
			r = ctrmap.ZoneManager.renameZone(named, 2, "New Town");
		} catch (java.io.IOException ex) {
			refused = ex.getMessage();
		}
		check(refused == null, "zone 2 of the handed game's three is renamed, not refused by the open game's range ("
				+ refused + ")");
		check(r != null && "Old Town".equals(r.oldName), "and the name it replaced is the handed game's ("
				+ (r == null ? null : r.oldName) + ")");
		check(open.persistPaths().isEmpty(), "and the open game was told of no edit " + open.persistPaths());
	}

	/** One zone, its master table and the encounter pack; installed as the open game. */
	static WorkspaceSession oneZoneGame() throws Exception {
		File root = Scratch.dir("ctrmap_resize_onezone");
		File zo = new File(root, "zonedata");
		HandedGameTest.writeGarc(zo, zone(0), master(0), "EN".getBytes("US-ASCII"));
		new File(root, "workspace").mkdirs();
		WorkspaceSession ws = Sessions.bare(new File(root, "workspace"), new File(root, "game"), GameType.ORAS)
				.withArchive(ArchiveType.ZONE_DATA, new GARC(zo));
		ws.prepareDirectories();
		Workspace.install(ws);
		return ws;
	}

	// ------------------------------------------------------------ 3. F227
	static void aResizeThatFailsHalfWayLeavesNothing() throws Exception {
		System.out.println("--- a resize that fails half way puts back what it wrote (F227)");
		WorkspaceSession ws = game("halfway");
		File copy = new File(ws.getExtractionDirectory(ArchiveType.FIELD_DATA), "2");
		File blocked = new File(ws.getExtractionDirectory(ArchiveType.FIELD_DATA), "3");
		check(blocked.mkdirs(), "the new cell's region is made unwritable: a directory stands at its path");
		List<String> before = new java.util.ArrayList<>(ws.persistPaths());
		String refused = null;
		try {
			MapResizer.resize(ws, 0, 2, 1);
		} catch (java.io.IOException | RuntimeException ex) {
			refused = ctrmap.util.Bytes.reason(ex);
		}
		check(refused != null, "the resize refuses: " + refused);
		check(!copy.exists(), "the private copy it had already written is gone");
		check(ws.persistPaths().equals(before), "and the list of edits is as it was " + ws.persistPaths());
		check(Arrays.equals(grid(ws, 0), new int[]{0}) && MasterTable.read(ws).id(ZoneResource.MAP, 0) == 0,
				"and zone 0 is still on map 0");
	}

	// ------------------------------------------------------------ 4. F020
	static void aPrivateMapIsShrunkAndWhatItDropsIsSaid() throws Exception {
		System.out.println("--- a map the zone owns is shrunk, and the region it drops is named (F020)");
		WorkspaceSession ws = wideGame("shrink", zoneWith(0, npcAt(5, 5)), zoneWith(1, npcAt(30, 12)));
		check(Arrays.equals(MapResizer.currentSize(ws, 2), new int[]{2, 1}), "control: zone 2's map is 2x1");
		MapResizer.ResizeResult r = MapResizer.resize(ws, 2, 1, 1);
		check(Arrays.equals(grid(ws, r.newMatrix), new int[]{2}) && Arrays.equals(MapResizer.currentSize(ws, 2), new int[]{1, 1}),
				"the zone's map is now 1x1, its kept cell still drawing region 2 " + Arrays.toString(grid(ws, r.newMatrix)));
		check(Arrays.equals(r.droppedRegions, new int[]{3}) && r.newRegions.length == 0 && r.ownRegions.length == 0,
				"the dropped cell's region 3 is named, and nothing was added or copied "
				+ Arrays.toString(r.droppedRegions) + Arrays.toString(r.newRegions) + Arrays.toString(r.ownRegions));
		check(MasterTable.read(ws).id(ZoneResource.MAP, 2) == r.newMatrix && Arrays.equals(grid(ws, 1), new int[]{2, 3}),
				"the master table points zone 2 at the new map, and the old one is as it was");
	}

	static void aSharedMapIsCroppedBeforeItIsCopied() throws Exception {
		System.out.println("--- a shared map is cropped before the zone's own copies are made (F020)");
		WorkspaceSession ws = wideGame("shrinkshared", zoneWith(0, npcAt(5, 5)), zoneWith(1, npcAt(5, 5)));
		MapResizer.ResizeResult r = MapResizer.resize(ws, 0, 1, 1);
		check(Arrays.equals(r.ownRegions, new int[]{4}) && Arrays.equals(grid(ws, r.newMatrix), new int[]{4}),
				"the kept cell draws the zone's own copy, region 4 " + Arrays.toString(r.ownRegions));
		check(!new File(ws.getExtractionDirectory(ArchiveType.FIELD_DATA), "5").exists(),
				"and the dropped cell was not copied: there is no region 5");
		check(Arrays.equals(grid(ws, 0), new int[]{0, 1}) && MasterTable.read(ws).id(ZoneResource.MAP, 1) == 0,
				"zone 1 still draws both cells of map 0");
	}

	static void aMapGrowsOneWayAndShrinksTheOther() throws Exception {
		System.out.println("--- one resize can drop a column and add a row (F020)");
		WorkspaceSession ws = wideGame("reshape", zoneWith(0, npcAt(5, 5)), zoneWith(1, npcAt(30, 12)));
		MapResizer.ResizeResult r = MapResizer.resize(ws, 2, 1, 2);
		check(Arrays.equals(grid(ws, r.newMatrix), new int[]{2, 4}) && Arrays.equals(r.newRegions, new int[]{4})
				&& Arrays.equals(r.droppedRegions, new int[]{3}),
				"the kept cell draws region 2, the new one below it region 4, and region 3 is named dropped "
				+ Arrays.toString(grid(ws, r.newMatrix)));
	}

	static void aShrinkOverTheZonesOwnContentIsRefused() throws Exception {
		System.out.println("--- a shrink that would leave the zone's own NPC over nothing is refused (F020)");
		WorkspaceSession ws = wideGame("stranded", zoneWith(0, npcAt(5, 5)), zoneWith(1, npcAt(45, 12)));
		List<String> before = new java.util.ArrayList<>(ws.persistPaths());
		String refused = null;
		try {
			MapResizer.resize(ws, 2, 1, 1);
		} catch (java.io.IOException ex) {
			refused = ex.getMessage();
		}
		check(refused != null && refused.contains("cell (1,0)") && refused.contains("not shrunk"),
				"refused, naming the cells the zone's content reaches: " + refused);
		check(ws.persistPaths().equals(before) && MasterTable.read(ws).id(ZoneResource.MAP, 2) == 1,
				"and nothing was written: zone 2 is still on map 1");
	}

	static void aZoneWhoseEntitiesCannotBeReadIsNotShrunk() throws Exception {
		System.out.println("--- a zone whose entities cannot be read is not shrunk: unknown is not empty (F020)");
		WorkspaceSession ws = wideGame("unread", zoneWith(0, npcAt(5, 5)), zone(1));
		String refused = null;
		try {
			MapResizer.resize(ws, 2, 1, 1);
		} catch (java.io.IOException ex) {
			refused = ex.getMessage();
		}
		check(refused != null && refused.contains("could not be read") && ws.persistPaths().isEmpty(),
				"refused in words, nothing written: " + refused);
	}

	static void theHeadlessResizeCropsEveryGridAndCutsTheCamera() throws Exception {
		System.out.println("--- the headless resize crops every grid of a LOD map and cuts its camera boxes (F020)");
		//2x2, LOD: ids 10..13, zone-switch segments numbered by position, LOD ids 20..23; a full-extent
		//containment box, one reaching into the dropped column, one wholly in it, and a repulsor
		byte[] lod = lodMatrix(2, 2, new float[][]{
			{0, 1440, 0, 1440, 0}, {100, 600, 200, 1000, 0}, {100, 600, 900, 1300, 0}, {0, 1440, 0, 1440, 1}});
		byte[] out = MapResizer.buildResizedMatrix(lod, 1, 2, new int[0], 7);
		int sub0 = i32(out, 4);
		check(u16(out, sub0) == 1 && u16(out, sub0 + 4) == 1 && u16(out, sub0 + 6) == 2
				&& u16(out, sub0 + 8) == 10 && u16(out, sub0 + 10) == 12,
				"the main grid keeps column 0: regions 10 and 12");
		int zBase = sub0 + 8 + 2 * 2;
		boolean zonesKept = true;
		for (int zy = 0; zy < 8; zy++) {
			for (int zx = 0; zx < 4; zx++) {
				zonesKept &= u16(out, zBase + (zy * 4 + zx) * 2) == zy * 8 + zx;
			}
		}
		check(zonesKept, "the zone-switch grid keeps the 4x8 segments under column 0, each where it was");
		int lBase = zBase + 4 * 8 * 2;
		check(u16(out, lBase) == 20 && u16(out, lBase + 2) == 22, "the LOD grid keeps 20 and 22");
		int cam = i32(out, 8);
		check(f32(out, cam + 4 + 12) == 720f && f32(out, cam + 4 + 4) == 1440f,
				"the full-extent box now ends at the new east edge, 720, and keeps its south, 1440");
		check(f32(out, cam + 24 + 12) == 720f && f32(out, cam + 24 + 8) == 200f,
				"a box reaching past the new edge is cut at it (east 1000 -> 720)");
		check(f32(out, cam + 44 + 8) == 720f && f32(out, cam + 44 + 12) == 720f,
				"a box wholly past it shrinks to nothing on the edge, not inside out");
		check(f32(out, cam + 64 + 12) == 1440f, "and the repulsor is left alone");
		boolean sameRefused = false, emptyRefused = false;
		try {
			MapResizer.buildResizedMatrix(lod, 2, 2, new int[0], 7);
		} catch (IllegalArgumentException ex) {
			sameRefused = true;
		}
		try {
			MapResizer.buildResizedMatrix(lod, 0, 2, new int[0], 7);
		} catch (IllegalArgumentException ex) {
			emptyRefused = true;
		}
		check(sameRefused && emptyRefused, "the same size, and a size of no cells, are refused");
	}

	// ------------------------------------------------------------ the game's bytes

	/** A zone container: the header naming map {@code matrix}, the given entities and an empty script. */
	static byte[] zoneWith(int matrix, byte[] entities) throws Exception {
		byte[] header = new byte[MasterTable.ROW];
		putU16(header, ZoneResource.MAP.headerOffset, matrix);
		return container(0x5a4f, header, entities, HandedGameTest.amx());
	}

	/** A zone's entities: one NPC, standing at a tile. */
	static byte[] npcAt(int xTile, int yTile) {
		ctrmap.formats.zone.ZoneEntities e = EditorBench.emptyEntities();
		ctrmap.formats.zone.ZoneEntities.NPC n = new ctrmap.formats.zone.ZoneEntities.NPC();
		n.xTile = xTile;
		n.yTile = yTile;
		e.npcs.add(n);
		return e.assembleData();
	}

	/** A map of {@code w}x{@code h} cells with no LOD layer, drawing these regions row by row. */
	static byte[] matrix(int w, int h, int... regions) {
		byte[] grid = new byte[8 + w * h * 2 + (w * h % 2 == 1 ? 2 : 0)];
		putU16(grid, 4, w);
		putU16(grid, 6, h);
		for (int k = 0; k < w * h; k++) {
			putU16(grid, 8 + k * 2, regions[k]);
		}
		return container(0x4D4D, grid, new byte[4]);
	}

	/**
	 * A LOD map of {@code w}x{@code h}: regions 10.., zone-switch segments numbered by position,
	 * LOD ids 20.., and these camera entries {north, south, west, east, isRepulsor}.
	 */
	static byte[] lodMatrix(int w, int h, float[][] camera) {
		int zw = w * 4, zh = h * 4;
		byte[] grid = new byte[(8 + w * h * 2 + zw * zh * 2 + w * h * 2 + 3) & ~3];
		putU16(grid, 0, 1);
		putU16(grid, 4, w);
		putU16(grid, 6, h);
		for (int k = 0; k < w * h; k++) {
			putU16(grid, 8 + k * 2, 10 + k);
			putU16(grid, 8 + w * h * 2 + zw * zh * 2 + k * 2, 20 + k);
		}
		for (int k = 0; k < zw * zh; k++) {
			putU16(grid, 8 + w * h * 2 + k * 2, (k / zw) * 8 + k % zw);
		}
		byte[] cam = new byte[4 + camera.length * 20];
		putI32(cam, 0, camera.length);
		for (int e = 0; e < camera.length; e++) {
			for (int f = 0; f < 4; f++) {
				putI32(cam, 4 + e * 20 + f * 4, Float.floatToIntBits(camera[e][f]));
			}
			putI32(cam, 4 + e * 20 + 16, (int) camera[e][4]);
		}
		return container(0x4D4D, grid, cam);
	}

	static float f32(byte[] b, int o) {
		return Float.intBitsToFloat(i32(b, o));
	}

	/** A zone container whose header names map {@code matrix}. */
	static byte[] zone(int matrix) {
		byte[] header = new byte[MasterTable.ROW];
		putU16(header, ZoneResource.MAP.headerOffset, matrix);
		return container(0x5a4f, header);
	}

	/** The master table: one row per zone, holding only its map id. */
	static byte[] master(int... maps) {
		byte[] m = new byte[maps.length * MasterTable.ROW];
		for (int z = 0; z < maps.length; z++) {
			putU16(m, z * MasterTable.ROW + ZoneResource.MAP.headerOffset, maps[z]);
		}
		return m;
	}

	/** A 1x1 map with no LOD layer, drawing {@code region}, and a camera subfile with no entries. */
	static byte[] matrix(int region) {
		byte[] grid = new byte[12];
		putU16(grid, 4, 1);
		putU16(grid, 6, 1);
		putU16(grid, 8, region);
		return container(0x4D4D, grid, new byte[4]);
	}

	/** A region container whose first subfile says which region it is. */
	static byte[] region(String said) throws Exception {
		return container(0x4752, said.getBytes("US-ASCII"), new byte[4]);
	}

	/** The Game Freak container shape: magic, count, count + 1 offsets, the subfiles. */
	static byte[] container(int magic, byte[]... subs) {
		int off = 4 + (subs.length + 1) * 4;
		int total = off;
		for (byte[] s : subs) {
			total += s.length;
		}
		byte[] out = new byte[total];
		//the magic reads as text: "GR" is 0x4752, first byte first
		out[0] = (byte) (magic >> 8);
		out[1] = (byte) magic;
		putU16(out, 2, subs.length);
		for (int i = 0; i < subs.length; i++) {
			putI32(out, 4 + i * 4, off);
			System.arraycopy(subs[i], 0, out, off, subs[i].length);
			off += subs[i].length;
		}
		putI32(out, 4 + subs.length * 4, off);
		return out;
	}

	/** The region ids a map's grid names, as the workspace holds it. */
	static int[] grid(WorkspaceSession ws, int matrix) throws Exception {
		byte[] m = bytes(ws, ArchiveType.MAP_MATRIX, matrix);
		int sub0 = i32(m, 4);
		int n = u16(m, sub0 + 4) * u16(m, sub0 + 6);
		int[] ids = new int[n];
		for (int k = 0; k < n; k++) {
			ids[k] = u16(m, sub0 + 8 + k * 2);
		}
		return ids;
	}

	static byte[] bytes(WorkspaceSession ws, ArchiveType type, int entry) throws Exception {
		return Files.readAllBytes(ws.getWorkspaceFile(type, entry).toPath());
	}

	static int u16(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
	}

	static int i32(byte[] b, int o) {
		return u16(b, o) | (u16(b, o + 2) << 16);
	}

	static void putU16(byte[] b, int o, int v) {
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
	}

	static void putI32(byte[] b, int o, int v) {
		putU16(b, o, v);
		putU16(b, o + 2, v >> 16);
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
