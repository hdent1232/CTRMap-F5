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
		} finally {
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/** Zones 0 and 1 on map 0 (region 0), zone 2 on map 1 (region 1); ORAS's two trailing tables. */
	static WorkspaceSession game(String name) throws Exception {
		File root = Scratch.dir("ctrmap_resize_" + name);
		File gameDir = new File(root, "game");
		File archives = new File(root, "archives");
		archives.mkdirs();
		File zo = new File(archives, "zonedata");
		HandedGameTest.writeGarc(zo, zone(0), zone(0), zone(1), master(0, 0, 1), "EN".getBytes("US-ASCII"));
		File mm = new File(archives, "matrices");
		HandedGameTest.writeGarc(mm, matrix(0), matrix(1));
		File fd = new File(archives, "regions");
		HandedGameTest.writeGarc(fd, region("region zero"), region("region one"));
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

	// ------------------------------------------------------------ the game's bytes

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
