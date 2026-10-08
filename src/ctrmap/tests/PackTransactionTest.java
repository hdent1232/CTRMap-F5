package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.WorkspaceUnit;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.pokedata.ItemData;
import ctrmap.formats.pokedata.ItemTable;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * A pack changes the game folder whole or not at all, and only the archives that were edited.
 *
 * <p>WHY THIS SUITE EXISTS. The pack rewrote the game's archives straight into the game folder one
 * after another (F035): a failure part way - the emulator holding ZoneData open is the usual cause -
 * left some grown and their partners not, an area with no NPC registry at its id, a zone that will
 * not load. And it rewrote and recompressed the six biggest archives on every pack whether anything
 * in them was edited or not (F036), which is also why Deploy has to decompress every entry to tell
 * what changed.
 *
 * <p>The game is written by hand: three archives, a few entries each, packed for real.
 *
 * <p>ORDER: needs no dump and no display; writes only under {@link Scratch}.
 *
 * Usage: java ctrmap.tests.PackTransactionTest
 */
public class PackTransactionTest {

	static int fails = 0;

	static final long OLD = 1_000_000_000_000L;

	public static void main(String[] args) throws Exception {
		Workspace.reset();
		try {
			//first: every other section builds a workspace from the folders this checks
			everyListOfArchivesComesFromOneTable();
			anUneditedGameIsNotRewritten();
			anEditIsPackedAndNothingElse();
			aPackThatFailsPartWayLeavesTheGameAsItWas();
			anItemIsStagedLikeEveryOtherEditAndPackedInPlace();
		} finally {
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	static final class Game {
		WorkspaceSession ws;
		File fd, zo, mm;
	}

	static Game game(String name) throws Exception {
		File root = Scratch.dir("ctrmap_pack_" + name);
		File archives = new File(root, "game");
		archives.mkdirs();
		Game g = new Game();
		g.fd = new File(archives, "fielddata");
		HandedGameTest.writeGarc(g.fd, MapResizeTest.region("region zero"), MapResizeTest.region("region one"));
		g.zo = new File(archives, "zonedata");
		HandedGameTest.writeGarc(g.zo, MapResizeTest.zone(0), new byte[MapResizeTestRow.ROW], "EN".getBytes("US-ASCII"));
		g.mm = new File(archives, "matrices");
		HandedGameTest.writeGarc(g.mm, MapResizeTest.matrix(0));
		for (File f : new File[]{g.fd, g.zo, g.mm}) {
			f.setLastModified(OLD);
		}
		new File(root, "workspace").mkdirs();
		g.ws = Sessions.bare(new File(root, "workspace"), archives, GameType.ORAS)
				.withArchive(ArchiveType.FIELD_DATA, new GARC(g.fd))
				.withArchive(ArchiveType.ZONE_DATA, new GARC(g.zo))
				.withArchive(ArchiveType.MAP_MATRIX, new GARC(g.mm));
		g.ws.prepareDirectories();
		Workspace.install(g.ws);
		return g;
	}

	/** A row's size, for the one master-table entry the hand-built zone archive carries. */
	static final class MapResizeTestRow {
		static final int ROW = ctrmap.MasterTable.ROW;
	}

	static void pack(WorkspaceSession ws) throws Exception {
		ws.packArchives((percent, what) -> {
		});
	}

	static void edit(WorkspaceSession ws, ArchiveType type, int entry, byte[] bytes) throws Exception {
		File f = ws.getWorkspaceFile(type, entry);
		Files.write(f.toPath(), bytes);
		ws.addPersist(f);
	}

	// ------------------------------------------------------------ F036
	static void anUneditedGameIsNotRewritten() throws Exception {
		System.out.println("--- a pack with nothing edited rewrites no archive (F036)");
		Game g = game("unedited");
		byte[][] before = {Files.readAllBytes(g.fd.toPath()), Files.readAllBytes(g.zo.toPath()), Files.readAllBytes(g.mm.toPath())};
		pack(g.ws);
		File[] files = {g.fd, g.zo, g.mm};
		for (int i = 0; i < files.length; i++) {
			check(files[i].lastModified() == OLD && Arrays.equals(Files.readAllBytes(files[i].toPath()), before[i]),
					files[i].getName() + " was not rewritten: it holds no edit");
		}
	}

	static void anEditIsPackedAndNothingElse() throws Exception {
		System.out.println("--- an edited archive is packed, and only it");
		Game g = game("edited");
		byte[] painted = MapResizeTest.region("region one, painted");
		edit(g.ws, ArchiveType.FIELD_DATA, 1, painted);
		pack(g.ws);
		check(Arrays.equals(new GARC(g.fd).getDecompressedEntry(1), painted), "the edit reached the game's FieldData");
		check(g.zo.lastModified() == OLD && g.mm.lastModified() == OLD, "and ZoneData and MapMatrix were not rewritten");
	}

	// ------------------------------------------------------------ F035
	static void aPackThatFailsPartWayLeavesTheGameAsItWas() throws Exception {
		System.out.println("--- a pack that fails part way leaves the game folder as it was (F035)");
		Game g = game("halfway");
		edit(g.ws, ArchiveType.FIELD_DATA, 0, MapResizeTest.region("region zero, edited"));
		edit(g.ws, ArchiveType.ZONE_DATA, 0, MapResizeTest.zone(0));
		g.ws.registerPendingCompression(ArchiveType.FIELD_DATA, 0, false);
		byte[] fdBefore = Files.readAllBytes(g.fd.toPath());
		byte[] zoBefore = Files.readAllBytes(g.zo.toPath());
		//ZoneData is packed after FieldData; something stands where its replacement is written
		File blocked = new File(g.ws.workspaceDir(), "pack_ZONE_DATA.garc");
		//a directory with a file in it: one the writer cannot delete out of its way either
		File inside = new File(blocked, "held");
		check(blocked.mkdirs() && inside.createNewFile(), "the second archive's pack is made to fail");
		String refused = null;
		try {
			pack(g.ws);
		} catch (java.io.IOException | RuntimeException ex) {
			refused = ctrmap.util.Bytes.reason(ex);
		}
		check(refused != null, "the pack refuses: " + refused);
		check(Arrays.equals(Files.readAllBytes(g.fd.toPath()), fdBefore) && g.fd.lastModified() == OLD,
				"FieldData, which was ready first, was NOT put into the game without its partner");
		check(Arrays.equals(Files.readAllBytes(g.zo.toPath()), zoBefore), "and ZoneData is as it was");
		check(!new File(g.ws.workspaceDir(), "pack_FIELD_DATA.garc").exists(), "and the replacement it wrote is gone");
		check(g.ws.hasPendingCompression(ArchiveType.FIELD_DATA),
				"and the compression flag it took is given back for the next pack");
		check(g.ws.isPersisted(g.ws.getWorkspaceFile(ArchiveType.FIELD_DATA, 0)),
				"and the edit is still staged, so packing again carries it");
		inside.delete();
		blocked.delete();
	}

	// ------------------------------------------------------------ F082
	/**
	 * An item record was poked into the game folder at save, outside the workspace: Clean put its name
	 * and description back and kept it, and a save whose text failed after it could not be undone. It
	 * is staged now, like every other edit, and the pack writes it over its own bytes.
	 */
	static void anItemIsStagedLikeEveryOtherEditAndPackedInPlace() throws Exception {
		System.out.println("--- an item record is staged like every other edit, and the pack writes it in place (F082)");
		Game g = game("items");
		File items = g.ws.archiveFile(ArchiveType.ITEM_DATA);
		byte[] retail = HandedGameTest.garcBytes(HandedGameTest.record(0), HandedGameTest.record(12), HandedGameTest.record(7));
		items.getParentFile().mkdirs();
		Files.write(items.toPath(), retail);
		items.setLastModified(OLD);
		//a session holding the item archive as the workspace opens it with its game: a new session
		g.ws = g.ws.withArchive(ArchiveType.ITEM_DATA, new GARC(items, false));
		Workspace.install(g.ws);
		ItemTable t = ItemTable.open(g.ws);
		check(t != null && t.count() == 3, "control: the hand-built item table opens through the workspace");
		if (t == null) {
			return;
		}
		File staged = g.ws.getWorkspaceFile(ArchiveType.ITEM_DATA, 1);

		t.writeRecord(1, HandedGameTest.record(99));
		check(g.ws.isPersisted(staged) && Arrays.equals(Files.readAllBytes(staged.toPath()), HandedGameTest.record(99)),
				"a saved record is staged in the workspace's item folder and listed as an edit");
		check(items.lastModified() == OLD && Arrays.equals(Files.readAllBytes(items.toPath()), retail),
				"and the game's archive is not written at save");

		g.ws.cleanAll();
		ItemTable cleaned = ItemTable.open(g.ws);
		check(!g.ws.isPersisted(staged) && Arrays.equals(cleaned.raw(1), HandedGameTest.record(12)),
				"Clean takes the record back, as it takes back the item's name and description");

		IOException planted = new IOException("planted: the item's text failed after its record");
		IOException caught = null;
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Save item 1", g.ws)) {
			cleaned.writeRecord(1, HandedGameTest.record(55));
			throw planted;
		} catch (IOException ex) {
			caught = ex;
		}
		check(caught == planted && !g.ws.isPersisted(staged) && Arrays.equals(ItemTable.open(g.ws).raw(1), HandedGameTest.record(12)),
				"a save that fails part way puts its record back with the rest of its unit");

		ItemTable.open(g.ws).writeRecord(1, HandedGameTest.record(99));
		pack(g.ws);
		byte[] packed = Files.readAllBytes(items.toPath());
		int at = new GARC(items, false).getEntryFileOffset(1);
		java.util.List<Integer> moved = new java.util.ArrayList<>();
		for (int i = 0; i < Math.min(packed.length, retail.length); i++) {
			if (packed[i] != retail[i]) {
				moved.add(i);
			}
		}
		check(packed.length == retail.length && !moved.isEmpty() && moved.get(0) >= at
				&& moved.get(moved.size() - 1) < at + ItemData.SIZE,
				"the pack writes it in place: the archive keeps its " + retail.length + " bytes, header and offset table"
				+ " included, and only record 1's, from 0x" + Integer.toHexString(at) + ", differ (" + moved.size() + " byte(s))");
		check(ItemTable.changedSinceBaseline(g.ws), "and once packed, deploy ships it");
		check(g.fd.lastModified() == OLD && g.zo.lastModified() == OLD && g.mm.lastModified() == OLD,
				"and no other archive was rewritten");

		//what a pack in place cannot write is refused before the game is touched
		long stamp = items.lastModified();
		File two = g.ws.getWorkspaceFile(ArchiveType.ITEM_DATA, 2);
		Files.write(two.toPath(), new byte[ItemData.SIZE - 1]);
		g.ws.addPersist(two);
		String refused = packRefusal(g.ws);
		check(refused != null && refused.contains("stored as " + ItemData.SIZE) && items.lastModified() == stamp
				&& Arrays.equals(Files.readAllBytes(items.toPath()), packed),
				"a staged record that is not its stored length is refused, and the game's archive is as it was: " + refused);
		Files.write(two.toPath(), HandedGameTest.record(7));
		g.ws.registerPendingCompression(ArchiveType.ITEM_DATA, 2, true);
		refused = packRefusal(g.ws);
		check(refused != null && refused.contains("compression override") && g.ws.hasPendingCompression(ArchiveType.ITEM_DATA)
				&& Arrays.equals(Files.readAllBytes(items.toPath()), packed),
				"and so is a compression override, which is given back, the archive untouched: " + refused);
	}

	static String packRefusal(WorkspaceSession ws) {
		try {
			pack(ws);
			return null;
		} catch (Exception ex) {
			return ctrmap.util.Bytes.reason(ex);
		}
	}

	// ------------------------------------------------------------ F038
	/**
	 * The archive facts are one table, and every list is derived from it: the pack order above is
	 * one of them. Six parallel lists in the session and copies of the required list in the setup
	 * check and two suites had to agree by hand; the folder list's javadoc recorded the bill for
	 * two of them drifting - editors writing into folders nobody made, failing silently.
	 */
	static void everyListOfArchivesComesFromOneTable() throws Exception {
		System.out.println("--- every list of archives comes from one table (F038)");
		check(WorkspaceSession.requiredArchives().equals(Arrays.asList(ArchiveType.AREA_DATA, ArchiveType.FIELD_DATA,
				ArchiveType.MAP_MATRIX, ArchiveType.GAMETEXT, ArchiveType.ZONE_DATA, ArchiveType.BUILDING_MODELS,
				ArchiveType.NPC_REGISTRIES, ArchiveType.MOVE_MODELS)),
				"the eight archives every game must have, in the order a missing one is reported: "
				+ WorkspaceSession.requiredArchives());
		Game g = game("folders");
		java.util.List<String> unmade = new java.util.ArrayList<>();
		int asked = 0;
		for (ArchiveType t : ArchiveType.values()) {
			File dir = g.ws.getExtractionDirectory(t);
			if (dir != null) {
				asked++;
				if (!dir.isDirectory()) {
					unmade.add(t + " -> " + dir.getName());
				}
			}
		}
		check(asked >= 17 && unmade.isEmpty(), "a new workspace has the folder of every archive it can extract ("
				+ asked + " asked; not made: " + unmade + ")");
		//deploy's list stays its own on purpose - it is the pristine snapshot's contract, which a
		//new row must not join silently - but everything it ships is an archive the table holds
		java.util.List<ArchiveType> unheld = new java.util.ArrayList<>();
		for (ArchiveType t : ctrmap.ModDeployer.allWritableArchives()) {
			if (g.ws.getExtractionDirectory(t) == null) {
				unheld.add(t);
			}
		}
		//the item archive included: it was written into the game folder at save, with no row (F082)
		check(unheld.isEmpty(), "every archive deploy ships has a row in the table (not held: " + unheld + ")");
		java.lang.reflect.Field dc = ctrmap.setup.DumpCheck.class.getDeclaredField("REQUIRED");
		dc.setAccessible(true);
		check(dc.get(null) == WorkspaceSession.requiredArchives() || WorkspaceSession.requiredArchives().equals(dc.get(null)),
				"and the setup check asks for exactly the archives the session refuses to open without ("
				+ dc.get(null) + ")");
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
