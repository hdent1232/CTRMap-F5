package ctrmap.tests;

import ctrmap.LittleEndianDataOutputStream;
import ctrmap.Workspace;
import ctrmap.formats.GameFiles;
import ctrmap.formats.containers.AD;
import ctrmap.formats.containers.GR;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchTexturePack;
import ctrmap.formats.h3d.BuildingCatalog;
import ctrmap.formats.maison.MaisonClassList;
import ctrmap.formats.maison.MaisonPoolGuard;
import ctrmap.formats.maison.MaisonSet;
import ctrmap.formats.npcreg.MoveModelPool;
import ctrmap.formats.npcreg.NPCRegistry;
import ctrmap.formats.pokedata.ItemData;
import ctrmap.formats.pokedata.ItemEditSession;
import ctrmap.formats.pokedata.ItemTable;
import ctrmap.formats.pokedata.ItemText;
import ctrmap.formats.pokedata.PokeData;
import ctrmap.formats.propdata.ADPropRegistry;
import ctrmap.formats.propdata.GRProp;
import ctrmap.formats.propdata.PropDatabase;
import ctrmap.formats.scripts.NpcTemplates;
import ctrmap.formats.text.GFMessageFile;
import ctrmap.formats.text.LocationNames;
import ctrmap.formats.tilemap.TerrainCatalog;
import ctrmap.formats.tilemap.Tilemap;
import ctrmap.formats.zone.Zone;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.formats.zone.ZoneHeader;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import ctrmap.gamedef.GameType;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import static ctrmap.formats.LittleEndian.putI32;
import static ctrmap.formats.LittleEndian.putU16;

/**
 * Format classes work when HANDED their game, with no workspace open, and
 * answer for the game they are handed rather than for the one the application
 * has open: the area texture operations of {@link BchTexturePack}, the
 * vanilla-safety guard {@link MaisonPoolGuard}, and - sections 6 to 10 - the
 * item table {@link ItemTable}, the item text {@link ItemText}, the edit
 * session over both {@link ItemEditSession}, the Pokemon reference tables
 * {@link PokeData} and the location names {@link LocationNames}.
 *
 * <p>THE PROPS AND THE NPCS (sections 12 to 17). Six more classes fetched the
 * open game themselves: {@link NpcTemplates} took two text indices from the
 * global's profile; {@link PropDatabase} gated on {@code Workspace.isValid}
 * and built itself from the global's archives, cached with no memory of which
 * game, so a second game got the first's props; {@link MoveModelPool} read the
 * global's archive and workspace files, and asked before a workspace was open
 * cached an empty list for the session; {@link ADPropRegistry} and
 * {@link GRProp} read every model from the global's workspace file, so a prop
 * could only be drawn or named from the application's game; and
 * {@link NPCRegistry} read its models from and recorded its writes in the
 * global, and asked the user whether to keep its changes from inside the
 * format layer, where no suite could answer. Each is handed what it needs now:
 * the profile, or the {@link GameFiles}. The registry's question moved to the
 * NPC form; the registry only {@link NPCRegistry#store writes} or
 * {@link NPCRegistry#discard forgets} and says which. The sections hand each
 * class two fakes and read two answers; where the answer is a model, and no
 * mesh can be built without a dump, what is read back is which game's file
 * the class staged its model from. Proven by breaking, see each section.
 *
 * <p>THE ZONE, THE CATALOGS AND THE WIDGET READS (sections 18 to 23). Six
 * more classes reached past the format layer: {@link ZoneHeader} opened its
 * area, registry and matrix from the global; {@link BuildingCatalog} resolved
 * the pristine snapshot, its archive path and its scratch directory from the
 * global, and {@link TerrainCatalog} cut every donor through it and opened an
 * error dialog from seven places; {@link Zone} asked "keep the changes?" twice
 * and reported a refused record itself, from inside the format layer;
 * {@link ZoneEntities.NPC} read its altitude off the application's map
 * panel; {@link Tilemap} read its colours off the application's tile form;
 * and the three script transplants called a pure renumbering that lived in
 * the script editor window. Each is handed what it needs now: the game, the
 * decision, a {@link ZoneEntities.GroundHeight}, a {@link Tilemap.TileColors};
 * the renumbering lives with the script it renumbers. The sections hand each
 * class two of whatever it needs and read two answers; the zone's and the
 * catalogs' run under {@code Ui.record()} and assert nothing was asked or
 * shown; the last measures, in the bytecode, that none of the nine classes
 * references the global, the main frame, the dialog seam, the keep question
 * or any window class. Proven by breaking, see each section.
 *
 * <p>THE TABLES (sections 6 to 10). Each of the four fetched the open game
 * from {@link Workspace}'s statics itself. ItemTable found its archive in the
 * global's game folder and kept its pre-edit copy in the global's workspace
 * folder, so "does deploy need to ship the items" could only be asked about
 * the application's game; ItemText answered an empty list and wrote nothing
 * whenever no workspace was open; PokeData loaded itself once on first use
 * and could never be pointed at a second game in the same JVM; LocationNames
 * loaded on demand from the global, so a suite wanting another game's names
 * had to install a workspace first. Each is handed its {@link GameFiles} now
 * (the two tables through a loader, as the application loads them when a
 * workspace opens), and the item baseline lives in {@link GameFiles#durable},
 * added for it. The sections hand each class two fakes and read back two
 * answers. Proven by breaking, all four at once: with {@code ItemTable.archiveFile}
 * put back to {@code Workspace.GAMEDIR_PATH} the handed archive was not found
 * (null) and no table opened; with {@code ItemText.read} gated on
 * {@code Workspace.isValid} every list read as empty; with {@code PokeData.load}
 * gated the same way nothing loaded and species 1 was "#1"; with
 * {@code LocationNames.gametextIndex} reading {@code Workspace.variant} a demo
 * game was read at the retail entry ("Wrong City"). Twenty-five checks named
 * it, and the seam test's count rose to 13 classes over 19 edges.
 *
 * <p>WHY THIS SUITE EXISTS. Both of the first two classes fetched the open game from
 * {@link Workspace}'s statics themselves. For BchTexturePack that meant the
 * shared-area check - the one thing standing between a texture carry and
 * fifteen other maps drawing from the same area - could only be asked about
 * the application's game, and answered "nobody shares it" whenever no game was
 * open; a carry made then wrote its pack and recorded the write nowhere. For
 * MaisonPoolGuard it meant the retail-or-free labelling could only judge the
 * application's snapshot, so no suite could hand it a pool of its own and
 * watch it label the slots. {@link GameFilesSeamTest} carries the ratchet that
 * counts such classes; this is the proof for these two, in the form the
 * owner's standard asks for: a class counts as migrated only if it is handed
 * what it needs and could be handed something else. So every section below
 * runs with {@code Workspace.reset()} first and nothing installed after,
 * hands each class a {@link FakeGameFiles}, then hands it a DIFFERENT one and
 * reads back a different answer, and checks that nothing reached the global's
 * edited-file list.
 *
 * <p>Nothing here needs a dump. The zone archive is written by hand in the
 * shape {@link GARC} parses (header, FATO, FATB, FIMB), the area containers
 * are the bytes the container base writes for an empty one, and the texture
 * packs come from {@link BchTexturePack#emit}, which round-trips every retail
 * pack byte for byte.
 *
 * <p>Proven by breaking: with {@code zonesUsingArea} put back to
 * {@code Workspace.getArchive}, the check read no table and refused nothing;
 * with {@code snapshotGarc} put back to {@code Workspace.originalSnapshotDir},
 * the guard fell back to "nothing is retail" on a game whose snapshot it had
 * been handed. The seam test's bytecode count rose by one class in each case.
 *
 * <p>ORDER: needs no dump, opens no workspace, writes only under {@link Scratch}.
 * Resets Workspace before and after, so it can run anywhere in the battery.
 *
 * Usage: java ctrmap.tests.HandedGameTest
 */
public class HandedGameTest {

	/** Bytes per master zone-header row, as {@code ctrmap.AreaForker} reads it. */
	private static final int MASTER_ROW = 0x38;
	/** Where the row keeps the zone's area id. */
	private static final int HDR_AREA_OFF = 2;
	/** ORAS: the master table sits this many entries before the end of ZONE_DATA. */
	private static final int TRAILING = 2;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		Workspace.reset();
		try {
			theSharedAreaCheckReadsTheTableItIsHanded();
			aCarryGrowsTheAreaOfTheGameItIsHanded();
			theAreaOperationsRefuseToBeHandedNothing();
			thePoolGuardReadsTheSnapshotItIsHanded();
			thePoolGuardRefusesToBeHandedNothing();
			theItemTableOpensTheArchiveOfTheGameItIsHanded();
			theItemTextReadsTheStagedListsOfTheGameItIsHanded();
			theEditSessionWritesIntoTheGameItIsHanded();
			thePokeDataAnswersForTheGameItIsHanded();
			theLocationNamesLoadFromTheGameTheyAreHanded();
			theTablesRefuseToBeHandedNothing();
			theNpcTemplatesIndexTheTextOfTheProfileTheyAreHanded();
			thePropDatabaseIsBuiltFromTheGameItIsHanded();
			theMoveModelPoolListsTheGameItIsHanded();
			thePropRegistryAndItsPropsReadTheGameTheyAreHanded();
			theNpcRegistryReadsAndWritesTheGameItIsHanded();
			thePropsAndNpcsRefuseToBeHandedNothing();
			theZoneHeaderOpensTheArchivesOfTheGameItIsHanded();
			theCatalogsCutFromTheSnapshotOfTheGameTheyAreHanded();
			theZoneWritesWhatItIsToldAndAsksNothing();
			theNpcStandsOnTheGroundItIsHanded();
			theTilemapIsPicturedInTheColoursItIsHanded();
			theMigratedClassesReachNoWindow();
		} finally {
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	// ------------------------------------------------ 1. the shared-area check
	static void theSharedAreaCheckReadsTheTableItIsHanded() throws Exception {
		System.out.println("--- the shared-area check reads the zone table of the game it is handed, with no workspace open");
		check(!Workspace.isValid() && Workspace.session() == null, "no workspace is open");

		//four zones: 0, 1 and 3 share area 7; zone 2 has area 9 to itself
		FakeGameFiles fake = zoneGame(7, 7, 9, 7);
		check("zones 1, 3".equals(BchTexturePack.zonesUsingArea(fake, 7, 0)),
				"area 7 is shared, and the sharers are named without the editing zone ("
				+ BchTexturePack.zonesUsingArea(fake, 7, 0) + ")");
		check(BchTexturePack.zonesUsingArea(fake, 9, 2) == null, "area 9 is zone 2's own");
		check("zone 2".equals(BchTexturePack.zonesUsingArea(fake, 9, -1)),
				"and excluding nothing names zone 2 itself (" + BchTexturePack.zonesUsingArea(fake, 9, -1) + ")");

		//a DIFFERENT game, where zone 0 also sits on area 9
		FakeGameFiles other = zoneGame(9, 7, 9, 7);
		check("zone 0".equals(BchTexturePack.zonesUsingArea(other, 9, 2)),
				"handed another game, the same question gets that game's answer ("
				+ BchTexturePack.zonesUsingArea(other, 9, 2) + ")");
		check(BchTexturePack.zonesUsingArea(fake, 9, 2) == null, "and the first game's answer is unchanged");

		//the WORKSPACE copy of the table wins over the archive's: the staged
		//master table says everybody is on area 9
		FakeGameFiles edited = zoneGame(7, 7, 9, 7).plant(ArchiveType.ZONE_DATA, 4, master(9, 9, 9, 9));
		check("zones 0, 1, 3".equals(BchTexturePack.zonesUsingArea(edited, 9, 2)),
				"the staged master table is read in preference to the archive's ("
				+ BchTexturePack.zonesUsingArea(edited, 9, 2) + ")");

		//a game whose zone archive nothing opened: no table to read, no refusal
		check(BchTexturePack.zonesUsingArea(new FakeGameFiles(), 7, 0) == null,
				"a game with no zone archive open yields no sharers rather than a crash");

		//the refusals built on it
		try {
			BchTexturePack.assertNotShared(fake, 7, 0);
			check(false, "assertNotShared refuses a shared area (it allowed area 7)");
		} catch (IllegalStateException ex) {
			check(String.valueOf(ex.getMessage()).contains("zones 1, 3"),
					"assertNotShared refuses a shared area and names the sharers: " + firstLine(ex));
		}
		BchTexturePack.assertNotShared(fake, 9, 2);
		check(true, "and allows a private one");
		try {
			BchTexturePack.importIntoArea(fake, 7, 0, null, null, new ArrayList<String>());
			check(false, "importIntoArea asks the shared question before touching a pack (it did not refuse)");
		} catch (IllegalStateException ex) {
			check(true, "importIntoArea asks the shared question before touching a pack: " + firstLine(ex));
		}
		check(Workspace.session() == null && Workspace.persistPaths().isEmpty(),
				"after all of it no workspace is open and nothing reached the global");
	}

	// ------------------------------------------------ 2. a carry, read back from disk
	static void aCarryGrowsTheAreaOfTheGameItIsHanded() throws Exception {
		System.out.println("--- a texture carry grows the area file of the game it is handed, and reports the write to it");
		FakeGameFiles fake = zoneGame(7, 7, 9, 7)
				.plant(ArchiveType.AREA_DATA, 1, areaHolding("alpha", "beta", "gamma"))
				.plant(ArchiveType.AREA_DATA, 9, areaHolding("alpha"))
				.plant(ArchiveType.AREA_DATA, 7, areaHolding("alpha"));
		check(fake.edited().isEmpty(), "the game has heard of no write yet");

		File target = fake.staged(ArchiveType.AREA_DATA, 9);
		String note = BchTexturePack.carryToArea(fake, 1, 9, Arrays.asList("beta", "gamma"), null, 2);
		check(note != null && note.contains("+2 textures carried"), "the carry reports what it did: " + note.trim());
		check(fake.edited().size() == 1 && fake.edited().get(0).equals(target.getAbsoluteFile()),
				"the target area file, and only it, was reported edited to the handed game (" + fake.edited() + ")");
		List<String> after = namesIn(new AD(target, fake).getFile(11));
		check(after.containsAll(Arrays.asList("alpha", "beta", "gamma")),
				"read back from disk, the area really holds the carried textures " + after);
		check(Workspace.persistPaths().isEmpty(), "and nothing reached the global's edited-file list");

		//already present: nothing to write, nothing new reported
		int heard = fake.edited().size();
		String again = BchTexturePack.carryToArea(fake, 1, 9, Arrays.asList("beta"), null, 2);
		check(again.contains("already present") && fake.edited().size() == heard,
				"a carry of textures the area already holds writes nothing and reports nothing new: " + again.trim());

		//a shared target: refused before anything is written
		File shared = fake.staged(ArchiveType.AREA_DATA, 7);
		byte[] before = Files.readAllBytes(shared.toPath());
		try {
			BchTexturePack.carryToArea(fake, 1, 7, Arrays.asList("beta"), null, 0);
			check(false, "a carry into a shared area is refused (it was allowed)");
		} catch (IllegalStateException ex) {
			check(String.valueOf(ex.getMessage()).contains("zones 1, 3"),
					"a carry into a shared area is refused, naming the sharers: " + firstLine(ex));
		}
		check(Arrays.equals(before, Files.readAllBytes(shared.toPath())) && fake.edited().size() == heard,
				"and the refused carry left the file as it was and reported nothing");

		//the SAME files handed as another game, whose table says area 9 is shared
		FakeGameFiles other = zoneGame(9, 7, 9, 7)
				.plant(ArchiveType.AREA_DATA, 1, areaHolding("alpha", "beta", "gamma", "delta"))
				.plant(ArchiveType.AREA_DATA, 9, areaHolding("alpha"));
		try {
			BchTexturePack.carryToArea(other, 1, 9, Arrays.asList("delta"), null, 2);
			check(false, "handed another game, the carry is judged by that game's table (it was allowed)");
		} catch (IllegalStateException ex) {
			check(String.valueOf(ex.getMessage()).contains("zone 0"),
					"handed another game, the carry is judged by that game's table: " + firstLine(ex));
		}
		check(other.edited().isEmpty() && fake.edited().size() == heard,
				"and neither game heard of a write");

		//a plan alone writes nothing
		BchTexturePack.Carry plan = BchTexturePack.planCarry(fake, 1, 9, Arrays.asList("beta"),
				new AD(target, fake).getFile(11), null, 2);
		check(plan.pack == null && plan.imported.isEmpty() && fake.edited().size() == heard,
				"planning a carry against the grown pack finds nothing to add and writes nothing");
		check(Workspace.session() == null && Workspace.persistPaths().isEmpty(),
				"after all of it no workspace is open and nothing reached the global");
	}

	// ------------------------------------------------ 3. null is refused
	static void theAreaOperationsRefuseToBeHandedNothing() throws Exception {
		System.out.println("--- the area operations handed null refuse in words, rather than answering 'nobody'");
		refuses("zonesUsingArea", () -> BchTexturePack.zonesUsingArea(null, 7, 0));
		refuses("assertNotShared", () -> BchTexturePack.assertNotShared(null, 7, 0));
		refuses("importIntoArea", () -> BchTexturePack.importIntoArea(null, 7, 0, null, null, new ArrayList<String>()));
		refuses("planCarry", () -> BchTexturePack.planCarry(null, 1, 9, new ArrayList<String>(), null, null, 2));
		refuses("carryToArea", () -> BchTexturePack.carryToArea(null, 1, 9, new ArrayList<String>(), null, 2));
	}

	// ------------------------------------------------ 4. the pool guard
	static void thePoolGuardReadsTheSnapshotItIsHanded() throws Exception {
		System.out.println("--- the pool guard labels the slots of the snapshot it is handed, with no workspace open");
		//a snapshot: six set slots, slot 0 filled, and a class list naming slot 4
		FakeGameFiles fake = new FakeGameFiles().withPristine();
		pristineArchive(fake, ArchiveType.MAISON_SET_POOL_A,
				set(25).write(), set(0).write(), set(0).write(), set(0).write(), set(0).write(), set(0).write());
		pristineArchive(fake, ArchiveType.MAISON_CLASS_LIST_A, classList(1, 4).write(), classList(2, 0).write());
		MaisonSet[] current = emptySets(6);

		MaisonPoolGuard g = MaisonPoolGuard.load(fake, ArchiveType.MAISON_SET_POOL_A, ArchiveType.MAISON_CLASS_LIST_A, current);
		check(g.exact, "with a snapshot the labels are exact");
		check(Arrays.equals(g.vanillaUsed, new boolean[]{true, false, false, false, true, false}),
				"retail = filled in the snapshot (slot 0) or named by a retail class list (slot 4): "
				+ Arrays.toString(g.vanillaUsed));
		check(g.freeCount() == 4 && g.firstFreeSlot(current) == 1,
				"four slots are free and the first is slot 1 (" + g.freeCount() + ", " + g.firstFreeSlot(current) + ")");
		check(g.vanilla != null && g.vanilla.length == 6 && g.vanilla[0].species == 25,
				"the pristine sets are the snapshot's (slot 0 is species " + (g.vanilla == null ? "?" : g.vanilla[0].species) + ")");
		MaisonClassList[] lists = MaisonPoolGuard.readSnapshotLists(fake, ArchiveType.MAISON_CLASS_LIST_A);
		check(lists != null && lists.length == 2 && lists[0].setIndices.equals(Arrays.asList(4))
				&& lists[1].setIndices.equals(Arrays.asList(0)),
				"the pristine class lists read back as written");

		//a DIFFERENT game: slot 3 is the retail one, and there is no class list
		FakeGameFiles other = new FakeGameFiles().withPristine();
		pristineArchive(other, ArchiveType.MAISON_SET_POOL_A,
				set(0).write(), set(0).write(), set(0).write(), set(150).write(), set(0).write(), set(0).write());
		MaisonPoolGuard o = MaisonPoolGuard.load(other, ArchiveType.MAISON_SET_POOL_A, ArchiveType.MAISON_CLASS_LIST_A, current);
		check(o.exact && Arrays.equals(o.vanillaUsed, new boolean[]{false, false, false, true, false, false}),
				"handed another game, the labels are that game's: " + Arrays.toString(o.vanillaUsed));
		check(MaisonPoolGuard.readSnapshotLists(other, ArchiveType.MAISON_CLASS_LIST_A) == null,
				"and a table its snapshot lacks reads as null, not as an empty table");

		//no snapshot at all: fail closed on what is filled NOW
		FakeGameFiles bare = new FakeGameFiles();
		MaisonSet[] live = emptySets(6);
		live[2].species = 7;
		MaisonPoolGuard fb = MaisonPoolGuard.load(bare, ArchiveType.MAISON_SET_POOL_A, ArchiveType.MAISON_CLASS_LIST_A, live);
		check(!fb.exact && Arrays.equals(fb.vanillaUsed, new boolean[]{false, false, true, false, false, false}),
				"with no snapshot, whatever is filled now is treated as retail: " + Arrays.toString(fb.vanillaUsed));
		check(MaisonPoolGuard.readSnapshotLists(bare, ArchiveType.MAISON_CLASS_LIST_A) == null,
				"and there are no pristine lists to restore from");

		//a snapshot of the wrong length is not trusted
		MaisonPoolGuard mismatch = MaisonPoolGuard.load(fake, ArchiveType.MAISON_SET_POOL_A, ArchiveType.MAISON_CLASS_LIST_A, emptySets(4));
		check(!mismatch.exact && mismatch.vanilla == null,
				"a snapshot whose length disagrees with the pool is not trusted");
		check(Workspace.session() == null && Workspace.persistPaths().isEmpty(),
				"after all of it no workspace is open and nothing reached the global");
	}

	// ------------------------------------------------ 5. null is refused
	static void thePoolGuardRefusesToBeHandedNothing() throws Exception {
		System.out.println("--- the pool guard handed null refuses in words, rather than guessing");
		refuses("load", () -> MaisonPoolGuard.load(null, ArchiveType.MAISON_SET_POOL_A, ArchiveType.MAISON_CLASS_LIST_A, emptySets(6)));
		refuses("readSnapshotLists", () -> MaisonPoolGuard.readSnapshotLists(null, ArchiveType.MAISON_CLASS_LIST_A));
	}

	// ------------------------------------------------ 6. the item table
	static void theItemTableOpensTheArchiveOfTheGameItIsHanded() throws Exception {
		System.out.println("--- the item table opens, edits and baselines the archive of the game it is handed, with no workspace open");
		check(!Workspace.isValid() && Workspace.session() == null, "no workspace is open");

		byte[] retail = garcBytes(record(0), record(12), record(7));
		FakeGameFiles fake = new FakeGameFiles().plantArchive(ArchiveType.ITEM_DATA, retail);
		check(fake.archiveFile(ArchiveType.ITEM_DATA).equals(ItemTable.archiveFile(fake)),
				"the archive is found where the handed game keeps it (" + ItemTable.archiveFile(fake) + ")");
		check(ItemTable.baselineArchive(fake) == null && !ItemTable.changedSinceBaseline(fake),
				"before any edit there is no pre-edit copy, and nothing to ship");
		ItemTable t = ItemTable.open(fake);
		check(t != null && t.count() == 3 && Arrays.equals(t.raw(1), record(12)),
				"it opens with the three records planted (" + (t == null ? "null" : String.valueOf(t.count())) + ")");
		if (t == null) {
			return;
		}
		check(t.baselineArchive() == null && t.baselineRecord(1) == null, "and the table knows of no pre-edit copy yet");

		t.writeRecord(1, record(99));
		File copy = new File(ItemTable.baselineDir(fake), "itemdata.garc");
		check(ItemTable.baselineDir(fake).equals(fake.durable("original_items")) && copy.isFile(),
				"the first write takes the pre-edit copy into the handed game's durable directory (" + copy + ")");
		check(Arrays.equals(Files.readAllBytes(copy.toPath()), retail),
				"and the copy is the archive as it was, byte for byte");
		check(Arrays.equals(t.baselineRecord(1), record(12)) && Arrays.equals(t.raw(1), record(99)),
				"the table answers both the retail record and the edited one");
		check(copy.equals(ItemTable.baselineArchive(fake)) && copy.equals(t.baselineArchive()),
				"the static and the instance name the same copy");
		check(ItemTable.changedSinceBaseline(fake), "and deploy would ship it");
		check(fake.edited().isEmpty() && Workspace.persistPaths().isEmpty(),
				"an in-place write stages nothing for a pack, in the handed game or in the global");
		t.writeRecord(1, record(12));
		check(!ItemTable.changedSinceBaseline(fake),
				"putting the bytes back means nothing to ship - the content decides, not the edit history");

		//a DIFFERENT game: the same shape, record 1 saying 34, no copy ever taken
		FakeGameFiles other = new FakeGameFiles()
				.plantArchive(ArchiveType.ITEM_DATA, garcBytes(record(0), record(34), record(7)));
		ItemTable o = ItemTable.open(other);
		check(o != null && Arrays.equals(o.raw(1), record(34)), "handed another game, the table is that game's");
		check(!ItemTable.baselineDir(other).equals(ItemTable.baselineDir(fake))
				&& ItemTable.baselineArchive(other) == null && !ItemTable.changedSinceBaseline(other),
				"its pre-edit copy would live in its own directory, and none was taken");
		check(Arrays.equals(t.baselineRecord(1), record(12)), "and the first game's copy is unchanged");

		//a game whose item table is only CITED: on disk, refused by the gate
		FakeGameFiles xy = new FakeGameFiles().profile(GameProfile.of(GameType.XY))
				.plantArchive(ArchiveType.ITEM_DATA, retail);
		check(ItemTable.archiveFile(xy) != null && ItemTable.open(xy) == null,
				"a game whose item table was never measured is refused by the feature gate, not by absence");
		check(ItemTable.archiveFile(new FakeGameFiles()) == null && ItemTable.open(new FakeGameFiles()) == null,
				"and a game with no item archive on disk opens nothing");
		check(Workspace.session() == null && Workspace.persistPaths().isEmpty(),
				"after all of it no workspace is open and nothing reached the global");
	}

	// ------------------------------------------------ 7. the item text
	static void theItemTextReadsTheStagedListsOfTheGameItIsHanded() throws Exception {
		System.out.println("--- the item text reads and stages the lists of the game it is handed, with no workspace open");
		int names = ORAS.textIndex(GameProfile.TextIndex.ITEM_NAMES);
		int descs = ORAS.textIndex(GameProfile.TextIndex.ITEM_DESCRIPTIONS);
		FakeGameFiles fake = new FakeGameFiles()
				.plant(ArchiveType.GAMETEXT, names, textOf("", "Master Ball", "Ultra Ball"))
				.plant(ArchiveType.GAMETEXT, descs, textOf("", "The best Ball.", "A very good Ball."));
		check(Arrays.asList("", "Master Ball", "Ultra Ball").equals(ItemText.read(fake, ItemText.Which.NAMES)),
				"the names are the handed game's staged list (" + ItemText.read(fake, ItemText.Which.NAMES) + ")");
		check("A very good Ball.".equals(ItemText.line(fake, ItemText.Which.DESCRIPTIONS, 2))
				&& ItemText.count(fake, ItemText.Which.DESCRIPTIONS) == 3,
				"and so are the descriptions (" + ItemText.count(fake, ItemText.Which.DESCRIPTIONS) + " lines)");
		check(fake.edited().isEmpty(), "reading stages nothing");

		ItemText.setLine(fake, ItemText.Which.NAMES, 2, "Mega Ball");
		File namesFile = fake.staged(ArchiveType.GAMETEXT, names);
		check("Mega Ball".equals(ItemText.line(fake, ItemText.Which.NAMES, 2)), "a line written reads back");
		check(fake.edited().size() == 1 && fake.edited().get(0).equals(namesFile.getAbsoluteFile()),
				"and the names file, and only it, was reported edited to the handed game (" + fake.edited() + ")");
		check(Workspace.persistPaths().isEmpty(), "and nothing reached the global's edited-file list");
		try {
			ItemText.setLine(fake, ItemText.Which.NAMES, 3, "Beast Ball");
			check(false, "a line past the end is refused (it was written)");
		} catch (IOException ex) {
			check(String.valueOf(ex.getMessage()).contains("does not add lines"),
					"a line past the end is refused in words: " + firstLine(ex));
		}
		check(ItemText.count(fake, ItemText.Which.NAMES) == 3 && fake.edited().size() == 1,
				"and the refused write neither lengthened the list nor reported anything new");

		//a DIFFERENT game: the same slot names a Great Ball, and stages no descriptions
		FakeGameFiles other = new FakeGameFiles()
				.plant(ArchiveType.GAMETEXT, names, textOf("", "Master Ball", "Great Ball"));
		check("Great Ball".equals(ItemText.line(other, ItemText.Which.NAMES, 2)),
				"handed another game, the same line is that game's");
		check("Mega Ball".equals(ItemText.line(fake, ItemText.Which.NAMES, 2)), "and the first game's is unchanged");
		check(ItemText.read(other, ItemText.Which.DESCRIPTIONS).isEmpty(),
				"a list that game has not staged reads as empty, not as the other game's");

		//a game with no VERIFIED entry for item text: XY answers -1
		FakeGameFiles xy = new FakeGameFiles().profile(GameProfile.of(GameType.XY));
		check(ItemText.read(xy, ItemText.Which.NAMES).isEmpty(), "a game with no verified item-name entry reads as empty");
		try {
			ItemText.setLine(xy, ItemText.Which.NAMES, 1, "x");
			check(false, "and refuses a write (it accepted)");
		} catch (IOException ex) {
			check(String.valueOf(ex.getMessage()).contains("verified"), "and refuses a write in words: " + firstLine(ex));
		}
		check(xy.edited().isEmpty(), "reporting nothing");
	}

	// ------------------------------------------------ 8. the edit session
	static void theEditSessionWritesIntoTheGameItIsHanded() throws Exception {
		System.out.println("--- the item edit session writes into the game it is handed, and only what differs");
		int names = ORAS.textIndex(GameProfile.TextIndex.ITEM_NAMES);
		int descs = ORAS.textIndex(GameProfile.TextIndex.ITEM_DESCRIPTIONS);
		FakeGameFiles fake = itemGame(names, descs);
		ItemEditSession s = ItemEditSession.open(fake);
		check(s != null && s.count() == 3 && "Ultra Ball".equals(s.name(2)) && "Very good.".equals(s.description(2)),
				"the session opens on the handed game");
		if (s == null) {
			return;
		}
		check(s.free.equals(Arrays.asList(1)), "and offers the blank, unnamed slot (" + s.free + ")");
		EnumSet<ItemEditSession.Changed> changed = s.save(2, record(7), "Mega Ball", "Very good.");
		check(changed.equals(EnumSet.of(ItemEditSession.Changed.NAME)),
				"a changed name writes the name and only that (" + changed + ")");
		check(fake.edited().size() == 1 && fake.edited().get(0).equals(fake.staged(ArchiveType.GAMETEXT, names).getAbsoluteFile()),
				"staged in the handed game (" + fake.edited() + ")");
		check("Mega Ball".equals(ItemText.line(fake, ItemText.Which.NAMES, 2)), "and readable from it");
		check(Workspace.persistPaths().isEmpty(), "and not in the global");
		changed = s.save(2, record(70), "Mega Ball", "Very good.");
		check(changed.equals(EnumSet.of(ItemEditSession.Changed.RECORD)) && fake.edited().size() == 1
				&& Arrays.equals(s.baselineRecord(2), record(7)),
				"a changed record writes in place, stages nothing new, and the baseline holds the retail one (" + changed + ")");

		//a DIFFERENT game, untouched
		FakeGameFiles other = itemGame(names, descs);
		ItemEditSession o = ItemEditSession.open(other);
		check(o != null && "Ultra Ball".equals(o.name(2)) && Arrays.equals(o.record(2), record(7)) && other.edited().isEmpty(),
				"handed another game, the session sees that game's unedited item");
		check(ItemEditSession.open(new FakeGameFiles().profile(GameProfile.of(GameType.XY))) == null,
				"and a game whose item table was never measured opens no session");
	}

	// ------------------------------------------------ 9. the Pokemon reference tables
	static void thePokeDataAnswersForTheGameItIsHanded() throws Exception {
		System.out.println("--- the Pokemon reference tables are those of the game they are handed, with no workspace open");
		FakeGameFiles fake = referenceGame("Bulbasaur", new int[]{45, 49, 49, 45, 65, 65}, 11, 3, "Pound", 40, "Master Ball");
		PokeData.load(fake);
		check(PokeData.available(), "a game with a PERSONAL archive loads as available");
		check(Arrays.equals(PokeData.baseStats(1), new int[]{45, 49, 49, 45, 65, 65}),
				"species 1's base stats are the handed game's " + Arrays.toString(PokeData.baseStats(1)));
		check(Arrays.equals(PokeData.types(1), new int[]{11, 3}) && Arrays.equals(PokeData.abilities(1), new int[]{65, 0, 34}),
				"and its types and abilities");
		check("Bulbasaur".equals(PokeData.speciesName(1)) && PokeData.speciesCount() == 2,
				"and its name; the count is the list's (" + PokeData.speciesCount() + ")");
		check("Pound".equals(PokeData.moveName(1)) && Arrays.equals(PokeData.moveInfo(1), new int[]{0, 1, 40, 100, 35}),
				"move 1 is the handed game's, name and numbers " + Arrays.toString(PokeData.moveInfo(1)));
		check("Master Ball".equals(PokeData.itemName(1)) && "Overgrow".equals(PokeData.abilityName(65)),
				"and so are item 1 and ability 65");
		check("#5".equals(PokeData.speciesName(5)) && PokeData.baseStats(5) == null,
				"a species the game does not have is an id, with no stats");

		//a DIFFERENT game
		FakeGameFiles other = referenceGame("Fushigidane", new int[]{1, 2, 3, 4, 5, 6}, 9, 2, "Hataku", 50, "Masuta Booru");
		PokeData.load(other);
		check("Fushigidane".equals(PokeData.speciesName(1)) && Arrays.equals(PokeData.baseStats(1), new int[]{1, 2, 3, 4, 5, 6})
				&& Arrays.equals(PokeData.types(1), new int[]{9, 2}),
				"handed another game, species 1 is that game's (" + PokeData.speciesName(1) + ")");
		check("Hataku".equals(PokeData.moveName(1)) && PokeData.moveInfo(1)[2] == 50 && "Masuta Booru".equals(PokeData.itemName(1)),
				"and so are its move and item");
		PokeData.load(fake);
		check("Bulbasaur".equals(PokeData.speciesName(1)) && PokeData.moveInfo(1)[2] == 40,
				"and handed the first again, the first game's");

		//a game with none of the archives: nothing, not the last game's tables
		PokeData.load(new FakeGameFiles());
		check(!PokeData.available() && PokeData.baseStats(1) == null && "#1".equals(PokeData.speciesName(1))
				&& PokeData.moveInfo(1) == null && "#1".equals(PokeData.itemName(1)),
				"a game with none of the reference archives loads as nothing: id-only labels, no guess from the last game");
		//species 0 (its list is empty, not absent), moves and items at the gen 6
		//fallbacks: an asymmetry the class always had, not one this suite pins
		check(PokeData.speciesCount() != 2 && PokeData.moveCount() == 622 && PokeData.itemCount() == 776,
				"and no count is the last game's (species " + PokeData.speciesCount() + ", moves "
				+ PokeData.moveCount() + ", items " + PokeData.itemCount() + ")");

		PokeData.load(fake);
		Workspace.reset();
		check(!PokeData.available() && "#1".equals(PokeData.speciesName(1)),
				"Workspace.reset() forgets the tables, so one game's names cannot outlive it into the next");
		check(Workspace.session() == null && Workspace.persistPaths().isEmpty(),
				"after all of it no workspace is open and nothing reached the global");
	}

	// ------------------------------------------------ 10. the location names
	static void theLocationNamesLoadFromTheGameTheyAreHanded() throws Exception {
		System.out.println("--- the location names load from the game they are handed, at the entry that game's edition uses");
		int retail = ORAS.textIndex(GameProfile.TextIndex.LOCATION_NAMES, GameProfile.Variant.RETAIL);
		int demo = ORAS.textIndex(GameProfile.TextIndex.LOCATION_NAMES, GameProfile.Variant.DEMO);
		check(retail != demo, "the fixture rests on ORAS keeping its demo's names in another entry (" + retail + " vs " + demo + ")");
		FakeGameFiles fake = new FakeGameFiles()
				.plant(ArchiveType.GAMETEXT, retail, textOf("Littleroot Town", "Oldale Town"));
		check(LocationNames.gametextIndex(fake) == retail,
				"a retail game's entry is the retail one (" + LocationNames.gametextIndex(fake) + ")");
		check(LocationNames.gametextIndex(new FakeGameFiles().variant(GameProfile.Variant.DEMO)) == demo,
				"and a demo's is the demo's (" + demo + ")");
		LocationNames.loadFromGarc(fake);
		check("Oldale Town".equals(LocationNames.getLocName(1)),
				"loaded from the handed game, line 1 is its (" + LocationNames.getLocName(1) + ")");

		//a DIFFERENT game
		FakeGameFiles other = new FakeGameFiles()
				.plant(ArchiveType.GAMETEXT, retail, textOf("Pallet Town", "Viridian City"));
		LocationNames.loadFromGarc(other);
		check("Viridian City".equals(LocationNames.getLocName(1)),
				"handed another game, line 1 is that game's (" + LocationNames.getLocName(1) + ")");

		//a demo: read at the demo's entry, not the retail entry beside it
		FakeGameFiles demoGame = new FakeGameFiles().variant(GameProfile.Variant.DEMO)
				.plant(ArchiveType.GAMETEXT, retail, textOf("Wrong Town", "Wrong City"))
				.plant(ArchiveType.GAMETEXT, demo, textOf("Demo Town", "Demo City"));
		LocationNames.loadFromGarc(demoGame);
		check("Demo City".equals(LocationNames.getLocName(1)),
				"a demo is read at the demo's entry, not the retail one beside it (" + LocationNames.getLocName(1) + ")");

		//a game with nothing at that entry
		try {
			LocationNames.loadFromGarc(new FakeGameFiles());
			check(false, "a game with nothing at that entry is refused (it loaded)");
		} catch (IllegalStateException ex) {
			check(String.valueOf(ex.getMessage()).contains("GAMETEXT entry " + retail),
					"a game with nothing at that entry is refused in words: " + firstLine(ex));
		}
		check("Demo City".equals(LocationNames.getLocName(1)), "and the refused load left the previous table in place");

		Workspace.reset();
		try {
			LocationNames.getLocName(1);
			check(false, "after a reset, with nothing loaded, a name is refused (it answered)");
		} catch (IllegalStateException ex) {
			check(String.valueOf(ex.getMessage()).contains("workspace"),
					"after a reset, with nothing loaded, a name is refused in words that name the workspace: " + firstLine(ex));
		}
		check(Workspace.session() == null && Workspace.persistPaths().isEmpty(),
				"after all of it no workspace is open and nothing reached the global");
	}

	// ------------------------------------------------ 11. null is refused
	static void theTablesRefuseToBeHandedNothing() {
		System.out.println("--- the tables handed null refuse in words, rather than answering for no game");
		refuses("ItemTable.open", () -> ItemTable.open((GameFiles) null));
		refuses("ItemTable.archiveFile", () -> ItemTable.archiveFile(null));
		refuses("ItemTable.baselineDir", () -> ItemTable.baselineDir(null));
		refuses("ItemTable.baselineArchive", () -> ItemTable.baselineArchive(null));
		refuses("ItemTable.changedSinceBaseline", () -> ItemTable.changedSinceBaseline(null));
		refuses("ItemText.read", () -> ItemText.read(null, ItemText.Which.NAMES));
		refuses("ItemText.line", () -> ItemText.line(null, ItemText.Which.NAMES, 1));
		refuses("ItemText.setLine", () -> ItemText.setLine(null, ItemText.Which.NAMES, 1, "x"));
		refuses("ItemText.count", () -> ItemText.count(null, ItemText.Which.NAMES));
		refuses("ItemEditSession.open", () -> ItemEditSession.open(null));
		refuses("PokeData.load", () -> PokeData.load(null));
		refuses("LocationNames.gametextIndex", () -> LocationNames.gametextIndex(null));
		refuses("LocationNames.loadFromGarc", () -> LocationNames.loadFromGarc(null));
	}

	// ------------------------------------------------ 12. the NPC templates
	static void theNpcTemplatesIndexTheTextOfTheProfileTheyAreHanded() throws Exception {
		System.out.println("--- the NPC templates index the text lists of the profile they are handed, with no workspace open");
		check(!Workspace.isValid() && Workspace.session() == null, "no workspace is open");
		FakeGameFiles fake = new FakeGameFiles();
		int trainers = ORAS.textIndex(GameProfile.TextIndex.TRAINER_NAMES);
		int items = ORAS.textIndex(GameProfile.TextIndex.ITEM_NAMES);
		check(trainers >= 0 && items >= 0, "the fixture rests on ORAS having verified both lists (" + trainers + ", " + items + ")");
		check(NpcTemplates.gametextTrainerNames(fake.profile()) == trainers,
				"the trainer-name index is the handed profile's (" + NpcTemplates.gametextTrainerNames(fake.profile()) + ")");
		check(NpcTemplates.gametextItemNames(fake.profile()) == items,
				"and so is the item-name index (" + NpcTemplates.gametextItemNames(fake.profile()) + ")");

		//a DIFFERENT game: one whose lists nobody has verified answers -1, not ORAS's number
		FakeGameFiles other = new FakeGameFiles().profile(GameProfile.of(GameType.XY));
		check(NpcTemplates.gametextTrainerNames(other.profile()) == -1 && NpcTemplates.gametextItemNames(other.profile()) == -1,
				"handed another game's profile, the indices are that game's: unverified, so -1 ("
				+ NpcTemplates.gametextTrainerNames(other.profile()) + ", " + NpcTemplates.gametextItemNames(other.profile()) + ")");
		check(NpcTemplates.gametextTrainerNames(fake.profile()) == trainers, "and the first game's answer is unchanged");
		check(Workspace.session() == null && Workspace.persistPaths().isEmpty(),
				"after all of it no workspace is open and nothing reached the global");
	}

	// ------------------------------------------------ 13. the prop database
	static void thePropDatabaseIsBuiltFromTheGameItIsHanded() throws Exception {
		System.out.println("--- the prop database is built from the archives of the game it is handed, and remembers which");
		PropDatabase.invalidate();
		FakeGameFiles fake = propGame(1, "pc01", "tree01");
		check(!PropDatabase.isBuilt(fake), "nothing is built for a game nobody has asked about");
		PropDatabase db = PropDatabase.get(fake);
		check(db != null && db.models.size() == 3 && "pc01".equals(db.getModel(1).name) && "tree01".equals(db.getModel(2).name),
				"the database names the handed game's models (" + (db == null ? "null" : db.getModel(1).name + ", " + db.getModel(2).name) + ")");
		if (db == null) {
			return;
		}
		check(db.getModel(1).donorAreas.equals(Arrays.asList(0)) && db.getModel(2).donorAreas.equals(Arrays.asList(1)),
				"and which of its areas registers each (" + db.getModel(1).donorAreas + ", " + db.getModel(2).donorAreas + ")");
		check(db.getModel(1).template != null && (db.getModel(1).template[2] & 0xFF) == 1,
				"and keeps a registry entry to import each by");
		check(PropDatabase.get(fake) == db && PropDatabase.isBuilt(fake),
				"asked again for the same game, it is the cached database");

		//a DIFFERENT game
		FakeGameFiles other = propGame(1, "bench01", "lamp01");
		PropDatabase o = PropDatabase.get(other);
		check(o != null && o != db && "bench01".equals(o.getModel(1).name),
				"handed another game, the database is that game's (" + (o == null ? "null" : o.getModel(1).name) + ")");
		check(PropDatabase.isBuilt(other) && !PropDatabase.isBuilt(fake),
				"and the cache knows which game it now holds");
		PropDatabase again = PropDatabase.get(fake);
		check(again != o && "pc01".equals(again.getModel(1).name),
				"handed the first game again, the first game's, rebuilt (" + again.getModel(1).name + ")");

		//a game with neither archive open: no database, not the last game's
		check(PropDatabase.get(new FakeGameFiles()) == null, "a game with no archives open has no database, not the last game's");
		check("pc01".equals(PropDatabase.get(fake).getModel(1).name), "and asking for it did not disturb the first game's");
		PropDatabase.invalidate();
		check(!PropDatabase.isBuilt(fake), "invalidate() forgets it");
		check(Workspace.session() == null && Workspace.persistPaths().isEmpty(),
				"after all of it no workspace is open and nothing reached the global");
	}

	// ------------------------------------------------ 14. the move-model pool
	static void theMoveModelPoolListsTheGameItIsHanded() throws Exception {
		System.out.println("--- the move-model pool lists the models of the game it is handed, read through its staged copies");
		MoveModelPool.invalidate();
		FakeGameFiles fake = npcGame("hero", "rival");
		check(MoveModelPool.size(fake) == 2 && "hero".equals(MoveModelPool.name(fake, 0)) && "rival".equals(MoveModelPool.name(fake, 1)),
				"the pool is the handed game's (" + MoveModelPool.size(fake) + ": " + MoveModelPool.name(fake, 0) + ", " + MoveModelPool.name(fake, 1) + ")");
		check(stagedOnDisk(fake, ArchiveType.MOVE_MODELS, 0) && stagedOnDisk(fake, ArchiveType.MOVE_MODELS, 1),
				"read through the game's staged copies, so an edited model would be named by what the workspace holds");
		check(MoveModelPool.name(fake, 2) == null && MoveModelPool.name(fake, -1) == null, "an index past the pool has no name");

		//a DIFFERENT game
		FakeGameFiles other = npcGame("mom", "prof", "clerk");
		check(MoveModelPool.size(other) == 3 && "prof".equals(MoveModelPool.name(other, 1)),
				"handed another game, the pool is that game's (" + MoveModelPool.size(other) + ": " + MoveModelPool.name(other, 1) + ")");
		check("hero".equals(MoveModelPool.name(fake, 0)) && MoveModelPool.size(fake) == 2,
				"and handed the first again, the first game's");
		check(MoveModelPool.size(new FakeGameFiles()) == 0, "a game with no MoveModels archive open lists nothing, not the last game's");
		MoveModelPool.invalidate();
		check(MoveModelPool.size(fake) == 2, "after invalidate() the pool reloads from the game it is handed");
		check(Workspace.session() == null && Workspace.persistPaths().isEmpty(),
				"after all of it no workspace is open and nothing reached the global");
	}

	// ------------------------------------------------ 15. the prop registry and its props
	static void thePropRegistryAndItsPropsReadTheGameTheyAreHanded() throws Exception {
		System.out.println("--- a prop registry stages its models from the game it is handed, and a prop is named from it");
		//area 0 registers reference 5 as model 1 ("pc01"); area 1 reference 6 as model 2 ("tree01");
		//area 2 reference 7 as model 3 ("sign01"), which nothing below stages in THIS game
		FakeGameFiles fake = propGame(0, "pc01", "tree01", "sign01");
		File areaFile = fake.staged(ArchiveType.AREA_DATA, 0);
		ADPropRegistry reg = new ADPropRegistry(new AD(areaFile, fake), null, fake);
		check(reg.entries.size() == 1 && reg.entries.get(5) != null && reg.entries.get(5).model == 1,
				"the registry reads its entries (" + reg.entries.keySet() + ")");
		check(stagedOnDisk(fake, ArchiveType.BUILDING_MODELS, 1) && !stagedOnDisk(fake, ArchiveType.BUILDING_MODELS, 2),
				"and staged reference 5's model, BuildingModels entry 1, from the handed game - and no other");
		check(reg.models.isEmpty(), "the fixture model has no mesh, so nothing was cached to draw; the mesh is not what is under test");

		GRProp byRegistry = new GRProp();
		byRegistry.uid = 5;
		byRegistry.updateName(reg, fake);
		check("pc01".equals(byRegistry.name), "a prop is named by the model its registry entry maps to, read from the handed game (" + byRegistry.name + ")");
		GRProp byUid = new GRProp();
		byUid.uid = 2;
		byUid.updateName(reg, fake);
		check("tree01".equals(byUid.name), "a uid the registry lacks is named by the BuildingModels entry of the uid itself (" + byUid.name + ")");
		GRProp noRegistry = new GRProp();
		noRegistry.uid = 1;
		noRegistry.updateName(null, fake);
		check("pc01".equals(noRegistry.name), "and with no registry at all, by uid (" + noRegistry.name + ")");
		GRProp missing = new GRProp();
		missing.uid = 9;
		missing.updateName(reg, fake);
		check("Model not found".equals(missing.name), "a model the game does not hold is said so, not guessed (" + missing.name + ")");

		//a DIFFERENT game: the same prop and the same registry, named from that game
		FakeGameFiles other = propGame(0, "bench01", "lamp01", "post01");
		GRProp elsewhere = new GRProp();
		elsewhere.uid = 5;
		elsewhere.updateName(reg, other);
		check("bench01".equals(elsewhere.name), "handed another game, the same prop and registry are named from that game (" + elsewhere.name + ")");
		check("pc01".equals(byRegistry.name), "and the first game's name is unchanged");
		//area 2's registry names model 3, an entry both games hold and nothing above has staged in either
		check(!stagedOnDisk(fake, ArchiveType.BUILDING_MODELS, 3) && !stagedOnDisk(other, ArchiveType.BUILDING_MODELS, 3),
				"neither game has staged BuildingModels entry 3 yet");
		ADPropRegistry o = new ADPropRegistry(new AD(other.staged(ArchiveType.AREA_DATA, 2), other), null, other);
		check(o.entries.get(7) != null && o.entries.get(7).model == 3
				&& stagedOnDisk(other, ArchiveType.BUILDING_MODELS, 3) && !stagedOnDisk(fake, ArchiveType.BUILDING_MODELS, 3),
				"a registry handed the other game stages its model there, and not in the first");

		//entries only: no model read, no game needed
		FakeGameFiles bare = propGame(0, "x", "y");
		ADPropRegistry entriesOnly = new ADPropRegistry(new AD(bare.staged(ArchiveType.AREA_DATA, 0), bare));
		check(entriesOnly.entries.size() == 1 && !stagedOnDisk(bare, ArchiveType.BUILDING_MODELS, 1),
				"the entries-only registry reads the table and stages no model");

		//a model the handed game does not hold: refused in words, not a null offset table
		FakeGameFiles lacking = new FakeGameFiles().plant(ArchiveType.AREA_DATA, 0, container("AD", areaRegistry(5, 1), new byte[0]));
		try {
			new ADPropRegistry(new AD(lacking.staged(ArchiveType.AREA_DATA, 0), lacking), null, lacking);
			check(false, "a registry naming a model the game has not staged is refused (it loaded)");
		} catch (IllegalStateException ex) {
			check(String.valueOf(ex.getMessage()).contains("entry 1"),
					"a registry naming a model the game has not staged is refused in words: " + firstLine(ex));
		}

		//a write goes to the game the registry's container was handed
		reg.entries.get(5).eventScr1 = 3;
		reg.modified = true;
		reg.write();
		check(fake.edited().size() == 1 && fake.edited().get(0).equals(areaFile.getAbsoluteFile()),
				"writing the registry reports the area file to the handed game (" + fake.edited() + ")");
		check(new ADPropRegistry(new AD(areaFile, fake)).entries.get(5).eventScr1 == 3, "and reads back from disk");
		check(Workspace.persistPaths().isEmpty(), "and nothing reached the global's edited-file list");
		check(Workspace.session() == null, "after all of it no workspace is open");
	}

	// ------------------------------------------------ 16. the NPC registry
	static void theNpcRegistryReadsAndWritesTheGameItIsHanded() throws Exception {
		System.out.println("--- an NPC registry stages its models from, and reports its writes to, the game it is handed; no dialog anywhere");
		//recorded, with no answers, for the whole section: the registry used to ask
		//"keep the changes?" from inside store(), and a suite that does not listen
		//for that cannot tell "the question moved to the form" from "the question
		//is still here and a closed dialog is refusing every write"
		List<String> said = ctrmap.Ui.record();
		try {
			npcRegistryHandedTwoGames();
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said.isEmpty(), "and no dialog seam was involved in any of it - the question belongs to the NPC form, not here (said " + said + ")");
	}

	/** The body of section 16, run while {@code Ui} is recording. */
	static void npcRegistryHandedTwoGames() throws Exception {
		//MoveModels 0 "hero", 1 "rival"; registry 3 maps uid 7 to model 1
		FakeGameFiles fake = npcGame("hero", "rival").plant(ArchiveType.NPC_REGISTRIES, 3, registryBytes(npcEntry(7, 1)));
		File f = fake.staged(ArchiveType.NPC_REGISTRIES, 3);
		NPCRegistry reg = new NPCRegistry(f, fake);
		check(reg.entries.size() == 1 && reg.entries.get(7) != null && reg.entries.get(7).model == 1,
				"the registry reads its entries (" + reg.entries.keySet() + ")");
		check(stagedOnDisk(fake, ArchiveType.MOVE_MODELS, 1) && !stagedOnDisk(fake, ArchiveType.MOVE_MODELS, 0),
				"and staged uid 7's model, MoveModels entry 1, from the handed game - and no other");
		check(reg.models.isEmpty(), "the fixture model has no mesh, so nothing was cached to draw; the mesh is not what is under test");
		check(!reg.modified && !reg.store() && fake.edited().isEmpty(),
				"with nothing modified, store() writes nothing and says so");

		int uid = reg.registerModel(0);
		check(uid == 0 && reg.modified && stagedOnDisk(fake, ArchiveType.MOVE_MODELS, 0),
				"registering a pool model stages it from the handed game (uid " + uid + ")");
		check(reg.store() && !reg.modified, "store() writes the modified registry and says so");
		check(fake.edited().size() == 1 && fake.edited().get(0).equals(f.getAbsoluteFile()),
				"and reports the file, and only it, to the handed game (" + fake.edited() + ")");
		check(Workspace.persistPaths().isEmpty(), "and nothing reached the global's edited-file list");
		check(new NPCRegistry(f, fake).entries.keySet().equals(new java.util.HashSet<>(Arrays.asList(0, 7))),
				"read back from disk, both entries are there");

		//the decision "discard": nothing written, nothing reported, and said so
		int heard = fake.edited().size();
		byte[] onDisk = Files.readAllBytes(f.toPath());
		reg.entries.remove(0);
		reg.modified = true;
		check(reg.discard() && !reg.modified, "discard() forgets the change and says there was one");
		check(Arrays.equals(onDisk, Files.readAllBytes(f.toPath())) && fake.edited().size() == heard,
				"and wrote nothing and reported nothing");
		check(!reg.discard() && !reg.store() && fake.edited().size() == heard,
				"after it there is nothing to forget and nothing to write");

		//a DIFFERENT game
		FakeGameFiles other = npcGame("mom", "prof", "clerk").plant(ArchiveType.NPC_REGISTRIES, 3, registryBytes(npcEntry(4, 2)));
		File of = other.staged(ArchiveType.NPC_REGISTRIES, 3);
		NPCRegistry o = new NPCRegistry(of, other);
		check(o.entries.get(4) != null && o.entries.get(4).model == 2
				&& stagedOnDisk(other, ArchiveType.MOVE_MODELS, 2) && !stagedOnDisk(fake, ArchiveType.MOVE_MODELS, 2),
				"handed another game, a registry stages its models there, and not in the first");
		o.modified = true;
		check(o.store() && other.edited().size() == 1 && other.edited().get(0).equals(of.getAbsoluteFile()) && fake.edited().size() == heard,
				"and reports its write there, not to the first (" + other.edited() + ")");
		check(NPCRegistry.loadFreshModelByIndex(other, 0) == null && stagedOnDisk(other, ArchiveType.MOVE_MODELS, 0),
				"a fresh preview stages its model from the game it is handed (no mesh in the fixture, so null)");

		//a model the handed game does not hold: refused in words
		FakeGameFiles lacking = new FakeGameFiles().plant(ArchiveType.NPC_REGISTRIES, 3, registryBytes(npcEntry(7, 1)));
		try {
			new NPCRegistry(lacking.staged(ArchiveType.NPC_REGISTRIES, 3), lacking);
			check(false, "a registry naming a model the game has not staged is refused (it loaded)");
		} catch (IllegalStateException ex) {
			check(String.valueOf(ex.getMessage()).contains("MoveModels entry 1"),
					"a registry naming a model the game has not staged is refused in words: " + firstLine(ex));
		}

		//a write that fails is thrown with its reason, not logged and reported as done
		File blocked = Scratch.file("ctrmap_handed_npcreg_blocked");
		NPCRegistry unwritable = new NPCRegistry(blocked, fake);
		unwritable.modified = true;
		Files.delete(blocked.toPath());
		blocked.mkdir();
		try {
			unwritable.store();
			check(false, "a write the file system refuses is thrown (it was reported as done)");
		} catch (IOException ex) {
			check(unwritable.modified && fake.edited().size() == heard,
					"a write the file system refuses is thrown, the registry stays modified and nothing is reported: " + firstLine(ex));
		}
		check(Workspace.session() == null && Workspace.persistPaths().isEmpty(),
				"after all of it no workspace is open and nothing reached the global");
	}

	// ------------------------------------------------ 17. null is refused
	static void thePropsAndNpcsRefuseToBeHandedNothing() throws Exception {
		System.out.println("--- the prop and NPC classes handed null refuse in words, rather than answering for no game");
		FakeGameFiles kit = propGame(0, "x");
		AD area = new AD(kit.staged(ArchiveType.AREA_DATA, 0), kit);
		File regFile = Scratch.file("ctrmap_handed_npcreg_null");
		refuses("NpcTemplates.gametextTrainerNames", () -> NpcTemplates.gametextTrainerNames(null));
		refuses("NpcTemplates.gametextItemNames", () -> NpcTemplates.gametextItemNames(null));
		refuses("PropDatabase.get", () -> PropDatabase.get(null));
		refuses("MoveModelPool.size", () -> MoveModelPool.size(null));
		refuses("MoveModelPool.name", () -> MoveModelPool.name(null, 0));
		refuses("ADPropRegistry", () -> new ADPropRegistry(area, null, (GameFiles) null));
		refuses("GRProp.updateName", () -> new GRProp().updateName(null, null));
		refuses("NPCRegistry", () -> new NPCRegistry(regFile, null));
		refuses("NPCRegistry.loadFreshModelByIndex", () -> NPCRegistry.loadFreshModelByIndex(null, 0));
	}

	// ------------------------------------------------ 18. the zone header
	static void theZoneHeaderOpensTheArchivesOfTheGameItIsHanded() throws Exception {
		System.out.println("--- a zone header opens the area, registry and matrix of the game it is handed, with no workspace open");
		check(!Workspace.isValid() && Workspace.session() == null, "no workspace is open");
		//MoveModels 0 "hero", 1 "rival"; area 5; registry 5 maps uid 7 to model 1; matrix 9
		FakeGameFiles fake = zoneWorld(npcEntry(7, 1), "hero", "rival");
		ZoneHeader h = new ZoneHeader(new byte[0x38], GameType.ORAS);
		h.areadataID = 5;
		h.mapmatrixID = 9;
		h.fetchArchives(fake);
		check(h.areadata != null && h.npcreg != null && h.mapmatrix != null, "the header opened its area, registry and matrix");
		check(h.areadata != null && h.areadata.getOriginFile().getAbsoluteFile().equals(fake.staged(ArchiveType.AREA_DATA, 5).getAbsoluteFile()),
				"the area is the handed game's staged entry 5 (" + (h.areadata == null ? "null" : h.areadata.getOriginFile()) + ")");
		check(h.mapmatrix != null && h.mapmatrix.getOriginFile().getAbsoluteFile().equals(fake.staged(ArchiveType.MAP_MATRIX, 9).getAbsoluteFile()),
				"and the matrix its staged entry 9");
		check(h.npcreg != null && h.npcreg.entries.size() == 1 && h.npcreg.entries.get(7) != null && h.npcreg.entries.get(7).model == 1,
				"the registry is the handed game's (uids " + (h.npcreg == null ? "none" : h.npcreg.entries.keySet()) + ")");
		check(stagedOnDisk(fake, ArchiveType.MOVE_MODELS, 1) && !stagedOnDisk(fake, ArchiveType.MOVE_MODELS, 0),
				"and its model, MoveModels entry 1, was staged from the handed game - and no other");
		check(h.propTextures.isEmpty() && h.worldTextures.isEmpty(), "the fixture packs hold no textures, and none were invented");
		check(fake.edited().isEmpty() && Workspace.persistPaths().isEmpty(), "opening reported no write, to the handed game or to the global");

		//a DIFFERENT game: registry 5 maps uid 4 to model 2
		FakeGameFiles other = zoneWorld(npcEntry(4, 2), "mom", "prof", "clerk");
		ZoneHeader o = new ZoneHeader(new byte[0x38], GameType.ORAS);
		o.areadataID = 5;
		o.mapmatrixID = 9;
		o.fetchArchives(other);
		check(o.npcreg != null && o.npcreg.entries.get(4) != null && o.npcreg.entries.get(4).model == 2
				&& stagedOnDisk(other, ArchiveType.MOVE_MODELS, 2) && !stagedOnDisk(fake, ArchiveType.MOVE_MODELS, 2),
				"handed another game, the same header opens that game's registry and stages its model there, not in the first");
		check(h.npcreg != null && h.npcreg.entries.get(7) != null && h.npcreg.entries.get(4) == null, "and the first header's registry is unchanged");

		//a write through what the header opened goes to the game the header was handed
		if (h.npcreg != null) {
			h.npcreg.modified = true;
			h.npcreg.store();
			check(fake.edited().size() == 1 && fake.edited().get(0).equals(fake.staged(ArchiveType.NPC_REGISTRIES, 5).getAbsoluteFile())
					&& other.edited().isEmpty(),
					"a write through the registry the header opened reports to the game the header was handed (" + fake.edited() + ")");
		}
		refuses("ZoneHeader.fetchArchives", () -> h.fetchArchives(null));
		h.freeArchives();
		check(h.areadata == null && h.npcreg == null && h.mapmatrix == null, "freeArchives() lets go of all three");
		check(Workspace.session() == null && Workspace.persistPaths().isEmpty(),
				"after all of it no workspace is open and nothing reached the global");
	}

	// ------------------------------------------------ 19. the catalogs
	static void theCatalogsCutFromTheSnapshotOfTheGameTheyAreHanded() throws Exception {
		System.out.println("--- the building and terrain catalogs cut donors from the pristine snapshot of the game they are handed, and show nobody anything");
		//recorded, with no answers, for the whole section: the terrain catalog
		//used to open an error dialog from seven places, and a suite that does
		//not listen for that cannot tell "the report moved to the window" from
		//"the report is still here"
		List<String> said = ctrmap.Ui.record();
		try {
			catalogsHandedTwoGames();
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said.isEmpty(), "and no dialog seam was involved in any of it - the catalogs return or throw, and the window reports (said " + said + ")");
	}

	/** The body of section 19, run while {@code Ui} is recording. */
	static void catalogsHandedTwoGames() throws Exception {
		check(!BuildingCatalog.canCutDonor(null), "handed no game there is nothing to cut from: false, and silently (the noise suite pins the silence)");
		check(!BuildingCatalog.canCutDonor(new FakeGameFiles()), "a game with no snapshot has nothing to cut from");
		check(!BuildingCatalog.canCutDonor(new FakeGameFiles().withPristine()), "and neither has a snapshot that does not hold the field archive");

		//a snapshot holding two regions; region 1's model slot says "one"
		FakeGameFiles fake = new FakeGameFiles().withPristine();
		pristineArchive(fake, ArchiveType.FIELD_DATA, region("zero"), region("one"));
		check(BuildingCatalog.canCutDonor(fake), "a snapshot holding the field archive can be cut from");
		GR one = BuildingCatalog.pristineRegion(fake, 1);
		check(one != null && startsWith(one.getFile(1), "one"), "region 1 is cut from the handed game's snapshot");
		check(one != null && fake.scratch().equals(one.getOriginFile().getParentFile()),
				"and staged in the handed game's scratch directory (" + (one == null ? "null" : one.getOriginFile()) + ")");
		check(BuildingCatalog.pristineRegion(fake, 5) == null, "a region the snapshot does not hold is null, not a guess");
		BuildingCatalog.Entry entry = new BuildingCatalog.Entry();
		entry.name = "fixture";
		entry.donorRegion = 1;
		check(BuildingCatalog.extract(fake, entry) == null, "an entry whose donor holds no map model cuts nothing, quietly");
		check(BuildingCatalog.extract(null, entry) == null, "and handed no game, nothing is cut at all");

		//a DIFFERENT game: region 1 says "eins"
		FakeGameFiles other = new FakeGameFiles().withPristine();
		pristineArchive(other, ArchiveType.FIELD_DATA, region("null"), region("eins"));
		GR eins = BuildingCatalog.pristineRegion(other, 1);
		check(eins != null && startsWith(eins.getFile(1), "eins") && other.scratch().equals(eins.getOriginFile().getParentFile()),
				"handed another game, the same region is that game's, staged in that game's scratch");
		GR again = BuildingCatalog.pristineRegion(fake, 1);
		check(again != null && startsWith(again.getFile(1), "one"), "and the first game's is unchanged");
		check(fake.edited().isEmpty() && other.edited().isEmpty() && Workspace.persistPaths().isEmpty(),
				"cutting reported no write anywhere");

		//the terrain catalog over two fresh snapshots: which game it read is
		//measured by the container it staged, since the fixture region is no
		//map model and yields no texture names
		TerrainCatalog.Donor d = new TerrainCatalog.Donor();
		d.donorRegion = 1;
		d.donorMesh = 0;
		d.injectName = "ctr_handed";
		check(TerrainCatalog.donorTextures(null, d).isEmpty() && TerrainCatalog.donorUvScale(null, d.injectName) == null,
				"handed no game, the terrain catalog has no donor textures and no donor scale to give");
		TerrainCatalog.ImportResult untouched = TerrainCatalog.ensureCliffMaterial(null, new byte[]{'B'});
		check(untouched.model.length == 1 && untouched.model[0] == 'B' && !untouched.injected && untouched.texturesNeeded.isEmpty(),
				"and a model asked for the cliff material is handed back exactly as it was");
		FakeGameFiles ta = new FakeGameFiles().withPristine();
		pristineArchive(ta, ArchiveType.FIELD_DATA, region("zero"), region("one"));
		FakeGameFiles tb = new FakeGameFiles().withPristine();
		pristineArchive(tb, ArchiveType.FIELD_DATA, region("null"), region("eins"));
		File cutA = new File(ta.scratch(), "bcat_region_1");
		File cutB = new File(tb.scratch(), "bcat_region_1");
		check(!cutA.exists() && !cutB.exists(), "nothing has been cut from either game yet");
		List<String> names = TerrainCatalog.donorTextures(ta, d);
		check(names.isEmpty() && cutA.isFile() && !cutB.exists(),
				"asked for a donor's textures, the terrain catalog cut the donor from the handed game's snapshot into its scratch, and not the other's");
		check(startsWith(Files.readAllBytes(cutA.toPath()), "GR"), "what it cut is the region container");
		TerrainCatalog.donorTextures(tb, d);
		check(cutB.isFile() && startsWith(new GR(cutB, tb).getFile(1), "eins"), "handed the other game, it cuts from that one");

		//a real failure is THROWN with its reason, not shown. Two of them: a
		//model that is one byte, with a snapshot present to cut from; and a
		//game whose scratch directory is a file, so the cut donor cannot be
		//staged (a truncated archive would not do: GARC swallows that itself)
		TerrainCatalog.Donor cliff = TerrainCatalog.cliffDonor();
		check(cliff != null, "the table names a CLIFF donor (" + (cliff == null ? "none" : cliff.injectName + " in region " + cliff.donorRegion) + ")");
		try {
			TerrainCatalog.ensureCliffMaterial(ta, new byte[]{'B'});
			check(false, "a cliff import that fails with a snapshot present is thrown (it returned)");
		} catch (IllegalStateException ex) {
			check(cliff != null && String.valueOf(ex.getMessage()).contains(cliff.injectName),
					"a cliff import that fails with a snapshot present is thrown, naming the material: " + firstLine(ex));
		}
		try {
			TerrainCatalog.ensureMaterial(ta, new byte[]{'B'}, ctrmap.formats.tilemap.TilePalette.GRASS);
			check(false, "a brush import that fails is thrown (it returned)");
		} catch (IllegalStateException ex) {
			check(String.valueOf(ex.getMessage()).contains("GRASS"), "a brush import that fails is thrown, naming the brush: " + firstLine(ex));
		}
		FakeGameFiles unstageable = new FakeGameFiles().withPristine();
		byte[][] regions = new byte[(cliff == null ? 1 : cliff.donorRegion) + 1][];
		Arrays.fill(regions, region("x"));
		pristineArchive(unstageable, ArchiveType.FIELD_DATA, regions);
		check(unstageable.scratch().delete() && unstageable.scratch().createNewFile(),
				"the fixture's scratch directory is now a file, so nothing can be staged in it");
		check(BuildingCatalog.canCutDonor(unstageable), "and its snapshot can still be asked");
		try {
			TerrainCatalog.donorTextures(unstageable, d);
			check(false, "a donor that cannot be staged is thrown (it answered)");
		} catch (IllegalStateException ex) {
			check(String.valueOf(ex.getMessage()).contains("region 1") && String.valueOf(ex.getMessage()).contains(d.injectName),
					"a donor that cannot be staged is thrown with its reason, for the window to report: " + firstLine(ex));
		}
		try {
			TerrainCatalog.donorUvScale(unstageable, cliff == null ? "ctr_gake" : cliff.injectName);
			check(false, "a donor scale that cannot be measured is thrown (it answered)");
		} catch (IllegalStateException ex) {
			check(String.valueOf(ex.getMessage()).contains("texture scale"),
					"a donor scale that cannot be measured is thrown with its reason: " + firstLine(ex));
		}
		try {
			TerrainCatalog.ensureCliffMaterial(unstageable, new byte[]{'B'});
			check(false, "and so is a cliff import whose donor cannot be staged (it returned)");
		} catch (IllegalStateException ex) {
			check(true, "and so is a cliff import whose donor cannot be staged: " + firstLine(ex));
		}
		refuses("BuildingCatalog.pristineRegion", () -> BuildingCatalog.pristineRegion(null, 1));
		check(Workspace.session() == null && Workspace.persistPaths().isEmpty(),
				"after all of it no workspace is open and nothing reached the global");
	}

	// ------------------------------------------------ 20. the zone
	static void theZoneWritesWhatItIsToldAndAsksNothing() throws Exception {
		System.out.println("--- a zone writes the parts it is told to into the game it is handed, throws when a record refuses, and asks nothing");
		//recorded, with no answers, for the whole section: the zone used to ask
		//"keep the changes?" twice from inside store(), and a suite that does
		//not listen for that cannot tell "the question moved to the panel" from
		//"the question is still here and a closed dialog is cancelling the save"
		List<String> said = ctrmap.Ui.record();
		try {
			zoneHandedTwoGames();
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said.isEmpty(), "and no dialog seam was involved in any of it - the questions belong to the zone panel, not here (said " + said + ")");
	}

	/** The body of section 20, run while {@code Ui} is recording. */
	static void zoneHandedTwoGames() throws Exception {
		FakeGameFiles fake = new FakeGameFiles().plant(ArchiveType.ZONE_DATA, 3, zoneBytes());
		File f = fake.staged(ArchiveType.ZONE_DATA, 3);
		Zone z = new Zone(new ZO(f, fake), GameType.ORAS);
		check(z.changed().isEmpty(), "a zone just read has nothing changed (" + z.changed() + ")");
		check(z.store().isEmpty() && fake.edited().isEmpty(),
				"store() with nothing changed writes no part and reports nothing, the script being what it was");

		z.header.weather = 3;
		check(z.changed().equals(EnumSet.of(Zone.Part.HEADER)), "a changed header is reported as changed (" + z.changed() + ")");
		byte[] before = Files.readAllBytes(f.toPath());
		check(z.store(EnumSet.noneOf(Zone.Part.class)).isEmpty() && Arrays.equals(before, Files.readAllBytes(f.toPath())) && fake.edited().isEmpty(),
				"told to keep nothing, it writes nothing and reports nothing");
		check(z.changed().contains(Zone.Part.HEADER), "and the header stays changed - leaving it out is not forgetting it");
		check(z.store(EnumSet.of(Zone.Part.HEADER)).equals(EnumSet.of(Zone.Part.HEADER)), "told to keep the header, it writes the header and says so");
		check(fake.edited().size() == 1 && fake.edited().get(0).equals(f.getAbsoluteFile()),
				"reporting the zone file, and only it, to the handed game (" + fake.edited() + ")");
		check(Workspace.persistPaths().isEmpty(), "and nothing reached the global's edited-file list");
		Zone reread = new Zone(new ZO(f, fake), GameType.ORAS);
		check(reread.header.weather == 3 && reread.changed().isEmpty(), "read back from disk, the header holds the change and nothing is changed any more");

		//a record that refuses: a warp with no destination
		z.entities.warps.add(new ZoneEntities.Warp());
		z.entities.modified = true;
		check(z.changed().equals(EnumSet.of(Zone.Part.ENTITIES)), "modified entities are reported as changed (" + z.changed() + ")");
		before = Files.readAllBytes(f.toPath());
		int heard = fake.edited().size();
		try {
			z.store();
			check(false, "a warp with no destination is refused (it was written)");
		} catch (IllegalStateException ex) {
			check(String.valueOf(ex.getMessage()).contains("no destination"),
					"a warp with no destination is refused with the record's own reason, thrown for the window to report: " + firstLine(ex));
		}
		check(Arrays.equals(before, Files.readAllBytes(f.toPath())) && fake.edited().size() == heard && z.entities.modified,
				"and nothing was written, nothing reported, and the entities stay modified");

		//the decision "discard": nothing written, said so
		check(z.discardEntities() && !z.entities.modified && z.changed().isEmpty(), "discardEntities() forgets the change and says there was one");
		check(!z.discardEntities(), "and after it there is nothing to forget");

		//given a destination, the entities are written
		ZoneEntities.Warp w = z.entities.warps.get(z.entities.warps.size() - 1);
		w.targetZone = 1;
		w.targetWarpId = 0;
		z.entities.modified = true;
		check(z.store().equals(EnumSet.of(Zone.Part.ENTITIES)) && !z.entities.modified,
				"with every record serialisable, store() writes the entities and forgets the mark");
		check(new Zone(new ZO(f, fake), GameType.ORAS).entities.warps.size() == 1, "read back, the warp is there");

		//a DIFFERENT game: the same zone, changed differently, written there and not here
		FakeGameFiles other = new FakeGameFiles().plant(ArchiveType.ZONE_DATA, 3, zoneBytes());
		File of = other.staged(ArchiveType.ZONE_DATA, 3);
		Zone o = new Zone(new ZO(of, other), GameType.ORAS);
		o.header.weather = 5;
		heard = fake.edited().size();
		check(o.store().equals(EnumSet.of(Zone.Part.HEADER)) && other.edited().size() == 1
				&& other.edited().get(0).equals(of.getAbsoluteFile()) && fake.edited().size() == heard,
				"handed another game, a zone reports its write there, not to the first (" + other.edited() + ")");
		check(new Zone(new ZO(f, fake), GameType.ORAS).header.weather == 3 && new Zone(new ZO(of, other), GameType.ORAS).header.weather == 5,
				"and each game's zone holds its own header");
		check(Workspace.session() == null && Workspace.persistPaths().isEmpty(),
				"after all of it no workspace is open and nothing reached the global");
	}

	// ------------------------------------------------ 21. the NPC's ground
	static void theNpcStandsOnTheGroundItIsHanded() {
		System.out.println("--- an NPC takes its altitude from the ground it is handed, with no map panel anywhere");
		check(ctrmap.CtrmapMainframe.mTileMapPanel == null, "there is no map panel to read");
		ZoneEntities.NPC npc = new ZoneEntities.NPC();
		npc.z3DCoordinate = 12.5f;
		npc.setYFromColl(180f, 1800f, (x, z) -> 7f);
		check(npc.z3DCoordinate == 7f, "handed ground at 7, the NPC stands at 7 (" + npc.z3DCoordinate + ")");
		npc.setYFromColl(180f, 1800f, (x, z) -> Float.NaN);
		check(npc.z3DCoordinate == 7f, "handed ground with no answer, it keeps its altitude");
		npc.setYFromColl(180f, 1800f, (x, z) -> 9f);
		check(npc.z3DCoordinate == 9f, "handed other ground, it stands on that one (" + npc.z3DCoordinate + ")");

		//the map view moves it through MapObject, which takes the ground it was handed with standOn
		npc.z3DCoordinate = 12.5f;
		npc.setX(180f);
		check(npc.xTile == 10 && npc.z3DCoordinate == 12.5f, "moved before any ground was handed, it keeps its altitude - no window, no NaN");
		npc.standOn((x, z) -> x + z);
		npc.setX(180f);
		check(npc.z3DCoordinate == 180f, "handed ground with standOn, a move through the map view stands it on it (" + npc.z3DCoordinate + ")");
		npc.standOn((x, z) -> 4f);
		npc.setZ(360f);
		check(npc.yTile == 20 && npc.z3DCoordinate == 4f, "handed other ground, it stands on that (" + npc.z3DCoordinate + ")");
		npc.standOn(null);
		npc.setX(360f);
		check(npc.xTile == 20 && npc.z3DCoordinate == 4f, "handed no ground again, it keeps its altitude");
		refuses("NPC.setYFromColl", () -> npc.setYFromColl(0f, 0f, null));
		check(ctrmap.CtrmapMainframe.mTileMapPanel == null, "and none of it needed a map panel");
	}

	// ------------------------------------------------ 22. the region's picture
	static void theTilemapIsPicturedInTheColoursItIsHanded() {
		System.out.println("--- a region's picture is painted in the colours it is handed, with no tile form anywhere");
		check(ctrmap.CtrmapMainframe.mTileEditForm == null, "there is no tile form to read");
		Tilemap blue = new Tilemap(null, 40, 40, tile -> Color.BLUE);
		check(blue.getImage().getRGB(5, 5) == Color.BLUE.getRGB() && blue.getImage().getRGB(395, 395) == Color.BLUE.getRGB(),
				"handed blue, every tile is blue");
		Tilemap keyed = new Tilemap(null, 40, 40, tile -> tile == 0x21000001 ? Color.GREEN : Color.RED);
		check(keyed.getImage().getRGB(5, 5) == Color.GREEN.getRGB(),
				"handed a palette, a tile's own value picks its colour (a fresh region is all unwalkable 0x21000001)");
		keyed.setTileData(0, 0, new byte[]{0, 0, 0, 0});
		keyed.updateImage();
		check(keyed.getImage().getRGB(5, 5) == Color.RED.getRGB() && keyed.getImage().getRGB(15, 15) == Color.GREEN.getRGB(),
				"and a repaint after an edit asks the handed palette again");
		Tilemap none = new Tilemap(null, 40, 40);
		none.updateImage();
		check(none.getImage().getRGB(5, 5) == Color.BLACK.getRGB(),
				"handed no colours, the data is held without a picture, and nothing is read from anywhere");
		check(ctrmap.CtrmapMainframe.mTileEditForm == null, "and none of it needed a tile form");
	}

	// ------------------------------------------------ 23. the bytecode
	/**
	 * The nine classes moved in this step reference neither the global, the
	 * main frame, the dialog seam, the keep question nor any window class -
	 * measured in the compiled program, where a static import cannot hide a
	 * read. {@code MapObject}, which the NPC implements and a later step
	 * moves, is a class constant and not a member reference, so it is not
	 * counted here by construction.
	 */
	static void theMigratedClassesReachNoWindow() throws Exception {
		System.out.println("--- the classes migrated here reach neither the global nor the window layer, measured in the bytecode");
		File classes = new File("build/classes");
		if (!new File(classes, "ctrmap/Workspace.class").isFile()) {
			//never "0 edges found in nothing"
			check(false, "there is a compiled program to measure - no ctrmap/Workspace.class under "
					+ classes.getAbsolutePath() + " (run build.ps1, and run this suite from the repo root)");
			return;
		}
		List<ClassFileScanner.ClassFile> app = ClassFileScanner.application(classes);
		String[] migrated = {"ctrmap/formats/zone/ZoneHeader", "ctrmap/formats/h3d/BuildingCatalog",
			"ctrmap/formats/tilemap/TerrainCatalog", "ctrmap/formats/zone/Zone", "ctrmap/formats/zone/ZoneEntities",
			"ctrmap/formats/tilemap/Tilemap", "ctrmap/formats/scripts/MsgWrapperInjector",
			"ctrmap/formats/scripts/SignWrapperInjector", "ctrmap/formats/scripts/TalkerScriptWizard"};
		String[] forbidden = {"ctrmap/Workspace", "ctrmap/CtrmapMainframe", "ctrmap/Ui", "ctrmap/humaninterface/"};
		for (String m : migrated) {
			List<String> reaches = new ArrayList<>();
			boolean compiled = false;
			for (ClassFileScanner.ClassFile cf : app) {
				if (!cf.topLevel().equals(m)) {
					continue;
				}
				compiled = true;
				for (ClassFileScanner.Ref r : cf.refs) {
					for (String owner : forbidden) {
						if (owner.endsWith("/") ? r.owner.startsWith(owner) : r.owner.equals(owner)) {
							reaches.add(r.owner.substring(r.owner.lastIndexOf('/') + 1) + "." + r.name);
						}
					}
					if (r.owner.equals("ctrmap/Utils") && r.name.equals("askToKeep")) {
						reaches.add("ctrmap.Ui.askToKeep");
					}
				}
			}
			check(compiled && reaches.isEmpty(), m.substring(m.lastIndexOf('/') + 1)
					+ " reaches neither the global, the main frame, the dialog seam, the keep question nor any window class"
					+ (compiled ? "" : " (it is not compiled)") + (reaches.isEmpty() ? "" : " - it reaches " + reaches));
		}
	}

	// ------------------------------------------------ fixtures for the zone, the catalogs and the picture

	/**
	 * A game whose MoveModels hold one model per name, whose AreaData entry 5
	 * is an area with nothing in its prop-texture slot and a world pack with
	 * no textures, whose NPC registry 5 holds {@code entry}, and whose MapMatrix
	 * entry 9 is a matrix container: everything a zone header opens.
	 */
	static FakeGameFiles zoneWorld(NPCRegistry.NPCRegistryEntry entry, String... moveModels) throws Exception {
		byte[][] subs = new byte[12][];
		Arrays.fill(subs, new byte[0]);
		subs[11] = bch("world", 0);
		return npcGame(moveModels)
				.plant(ArchiveType.AREA_DATA, 5, container("AD", subs))
				.plant(ArchiveType.NPC_REGISTRIES, 5, registryBytes(entry))
				.plant(ArchiveType.MAP_MATRIX, 9, container("MM", new byte[]{0, 0, 0, 0}, new byte[0]));
	}

	/** A seven-slot region container whose model slot (1) holds a word rather than a model. */
	static byte[] region(String word) {
		return container("GR", new byte[0], bytes(word), new byte[0], new byte[0], new byte[0], new byte[0], new byte[0]);
	}

	/**
	 * A zone container: an all-zero header, an entity section with no records
	 * and the smallest script the parser reads, and that script again as the
	 * map script. An AMX header is 0x3C bytes; with every offset pointing at
	 * its end there are no entries, no code and no data, and it writes back
	 * byte for byte.
	 */
	static byte[] zoneBytes() {
		byte[] script = amx();
		byte[] entities = new byte[12 + script.length];
		putI32(entities, 0, 8);
		System.arraycopy(script, 0, entities, 12, script.length);
		return container("ZO", new byte[0x38], entities, script);
	}

	static byte[] amx() {
		byte[] b = new byte[0x3C];
		putI32(b, 0, 0x3C);
		putU16(b, 4, 0xF1E0);
		b[6] = 8;
		b[7] = 8;
		putU16(b, 10, 8);
		for (int off = 12; off < 0x3C; off += 4) {
			putI32(b, off, 0x3C);
		}
		putI32(b, 28, 0); //main entry point
		return b;
	}

	// ------------------------------------------------ fixtures for the props and NPCs

	/**
	 * A BCH that {@link ctrmap.formats.h3d.H3DModelNameGet} names {@code name}
	 * and {@link ctrmap.formats.h3d.BCHFile} parses without error: a header
	 * for backward-compatibility 0x21, an empty relocation table, a content
	 * header whose model pointer table points at one model header whose name
	 * sits at string-table offset 0. {@code modelCount} is what the content
	 * header claims: 0 for a class that runs the full parser (a model with no
	 * mesh cannot be built by hand, and the parser reads none when told there
	 * are none), 1 for {@link PropDatabase}, which never runs it but gates the
	 * name read on the count.
	 */
	static byte[] bch(String name, int modelCount) throws IOException {
		int main = 0x44;
		int ptrTable = main + 45 * 4;
		int model0 = ptrTable + 4;
		int nameField = model0 + 0x84;
		int strTable = nameField + 4;
		byte[] nm = name.getBytes("US-ASCII");
		byte[] b = new byte[strTable + nm.length + 1];
		b[0] = 'B';
		b[1] = 'C';
		b[2] = 'H';
		b[4] = 0x21;
		b[5] = 0x21;
		putI32(b, 8, main);
		putI32(b, 12, strTable);
		putI32(b, 32, 45 * 4);
		putI32(b, 36, nm.length + 1);
		putI32(b, main, ptrTable - main);
		putI32(b, main + 4, modelCount);
		putI32(b, ptrTable, model0 - main);
		putI32(b, nameField, 0);
		System.arraycopy(nm, 0, b, strTable, nm.length);
		return b;
	}

	/** A GF mini container of these subfiles in the layout the container base reads: two magic bytes, u16 count, count+1 offsets. */
	static byte[] container(String magic, byte[]... subs) {
		int n = subs.length;
		int[] off = new int[n + 1];
		off[0] = 4 + (n + 1) * 4;
		for (int i = 0; i < n; i++) {
			off[i + 1] = off[i] + subs[i].length;
		}
		byte[] o = new byte[off[n]];
		o[0] = (byte) magic.charAt(0);
		o[1] = (byte) magic.charAt(1);
		putU16(o, 2, n);
		for (int i = 0; i <= n; i++) {
			putI32(o, 4 + 4 * i, off[i]);
		}
		for (int i = 0; i < n; i++) {
			System.arraycopy(subs[i], 0, o, off[i], subs[i].length);
		}
		return o;
	}

	/** An AD prop registry (subfile 0) of one 0x50-byte entry: reference, model, the rest zero. */
	static byte[] areaRegistry(int reference, int model) {
		byte[] r = new byte[4 + 0x50];
		putI32(r, 0, 1);
		putU16(r, 4, reference);
		putU16(r, 6, model);
		return r;
	}

	/**
	 * A game whose BuildingModels archive holds the dummy and then one model
	 * per name (entry 1 + i is names[i]), and whose AreaData holds one area
	 * per name, area i registering prop reference 5 + i as model 1 + i, with
	 * an empty texture pack.
	 */
	static FakeGameFiles propGame(int bchModelCount, String... names) throws Exception {
		FakeGameFiles g = new FakeGameFiles();
		byte[][] bm = new byte[names.length + 1][];
		bm[0] = container("BM", bch(PropDatabase.DUMMY_MODEL_NAME, bchModelCount));
		byte[][] ad = new byte[names.length][];
		for (int i = 0; i < names.length; i++) {
			bm[i + 1] = container("BM", bch(names[i], bchModelCount));
			ad[i] = container("AD", areaRegistry(5 + i, 1 + i), new byte[0]);
		}
		File bmFile = new File(g.root(), "buildingmodels.garc");
		writeGarc(bmFile, bm);
		File adFile = new File(g.root(), "areadata.garc");
		writeGarc(adFile, ad);
		return g.open(ArchiveType.BUILDING_MODELS, new GARC(bmFile)).open(ArchiveType.AREA_DATA, new GARC(adFile));
	}

	/** A game whose MoveModels archive holds one model per name, each a container round a BCH with no mesh. */
	static FakeGameFiles npcGame(String... names) throws Exception {
		FakeGameFiles g = new FakeGameFiles();
		byte[][] mm = new byte[names.length][];
		for (int i = 0; i < names.length; i++) {
			mm[i] = container("MM", bch(names[i], 0));
		}
		File f = new File(g.root(), "movemodels.garc");
		writeGarc(f, mm);
		return g.open(ArchiveType.MOVE_MODELS, new GARC(f));
	}

	/** A registry file of these entries, as {@link NPCRegistry} reads and writes them. */
	static byte[] registryBytes(NPCRegistry.NPCRegistryEntry... entries) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LittleEndianDataOutputStream dos = new LittleEndianDataOutputStream(baos);
		for (NPCRegistry.NPCRegistryEntry e : entries) {
			e.write(dos);
		}
		dos.close();
		return baos.toByteArray();
	}

	static NPCRegistry.NPCRegistryEntry npcEntry(int uid, int model) {
		NPCRegistry.NPCRegistryEntry e = new NPCRegistry.NPCRegistryEntry();
		e.uid = uid;
		e.model = model;
		return e;
	}

	/**
	 * Whether the fake has extracted this entry to its staging area. The fake
	 * stages under {@code root/<type>/<entry>}; looked at here directly rather
	 * than through {@code staged()}, which would extract it in the asking.
	 * This is how "which game did the class read its model from" is measured
	 * for a class whose only other output is a mesh no fixture can build.
	 */
	static boolean stagedOnDisk(FakeGameFiles g, ArchiveType type, int entry) {
		return new File(new File(g.root(), type.name().toLowerCase()), String.valueOf(entry)).isFile();
	}

	// ------------------------------------------------ fixtures for the tables

	/** The profile whose text indices and archive locations the table fixtures are laid out for. */
	private static final GameProfile ORAS = GameProfile.of(GameType.ORAS);

	/** An item record: {@link ItemData#SIZE} bytes of one value, so 0 is the blank record. */
	static byte[] record(int value) {
		byte[] r = new byte[ItemData.SIZE];
		Arrays.fill(r, (byte) value);
		return r;
	}

	/** The bytes of an archive holding these entries, in the layout {@link #writeGarc} produces. */
	static byte[] garcBytes(byte[]... entries) throws Exception {
		File f = Scratch.file("ctrmap_handed_garc");
		writeGarc(f, entries);
		return Files.readAllBytes(f.toPath());
	}

	/** A GameText file of these lines, with no per-line extras. */
	static byte[] textOf(String... lines) {
		return GFMessageFile.write(Arrays.asList(lines));
	}

	/** A game with a three-record item archive (record 1 blank and unnamed) and both item text lists staged. */
	static FakeGameFiles itemGame(int names, int descs) throws Exception {
		return new FakeGameFiles()
				.plantArchive(ArchiveType.ITEM_DATA, garcBytes(record(0), record(0), record(7)))
				.plant(ArchiveType.GAMETEXT, names, textOf("", "???", "Ultra Ball"))
				.plant(ArchiveType.GAMETEXT, descs, textOf("", "", "Very good."));
	}

	/**
	 * A game whose PERSONAL, MOVE_DATA and GAMETEXT archives - at ORAS's
	 * locations and text indices - describe species 1, move 1 and item 1.
	 * Species 1 always has abilities 65, 0 and 34; ability 65 is Overgrow.
	 */
	static FakeGameFiles referenceGame(String species, int[] stats, int type1, int type2, String move, int power, String item)
			throws Exception {
		//PERSONAL: species 0 blank, species 1 as given; 80-byte records, as the game's
		byte[] one = new byte[80];
		for (int i = 0; i < 6; i++) {
			one[i] = (byte) stats[i];
		}
		one[6] = (byte) type1;
		one[7] = (byte) type2;
		one[0x18] = 65;
		one[0x19] = 0;
		one[0x1A] = 34;
		byte[] personal = garcBytes(new byte[80], one);
		//MOVE_DATA entry 0: a mini-container of two 6-byte move records, offsets from the container start
		byte[] mini = new byte[4 + 2 * 4 + 2 * 6];
		putI32(mini, 4, 12);
		putI32(mini, 8, 18);
		//move 1: type, unused, category, power, accuracy, pp
		mini[18] = 0;
		mini[20] = 1;
		mini[21] = (byte) power;
		mini[22] = 100;
		mini[23] = 35;
		byte[] moves = garcBytes(mini);
		//GAMETEXT: the four name lists at ORAS's indices, every other entry empty
		GameProfile.TextIndex[] lists = {GameProfile.TextIndex.SPECIES_NAMES, GameProfile.TextIndex.ABILITY_NAMES,
			GameProfile.TextIndex.MOVE_NAMES, GameProfile.TextIndex.ITEM_NAMES};
		int entries = 0;
		for (GameProfile.TextIndex t : lists) {
			entries = Math.max(entries, ORAS.textIndex(t) + 1);
		}
		byte[][] text = new byte[entries][];
		Arrays.fill(text, new byte[0]);
		text[ORAS.textIndex(GameProfile.TextIndex.SPECIES_NAMES)] = textOf("???", species);
		text[ORAS.textIndex(GameProfile.TextIndex.MOVE_NAMES)] = textOf("-", move);
		text[ORAS.textIndex(GameProfile.TextIndex.ITEM_NAMES)] = textOf("-", item);
		List<String> abilities = new ArrayList<>(Collections.nCopies(66, ""));
		abilities.set(34, "Chlorophyll");
		abilities.set(65, "Overgrow");
		text[ORAS.textIndex(GameProfile.TextIndex.ABILITY_NAMES)] = GFMessageFile.write(abilities);
		return new FakeGameFiles()
				.plantArchive(ArchiveType.PERSONAL, personal)
				.plantArchive(ArchiveType.MOVE_DATA, moves)
				.plantArchive(ArchiveType.GAMETEXT, garcBytes(text));
	}

	// ------------------------------------------------ fixtures

	/** A game whose ZONE_DATA archive holds one entry per given zone, the master table, and one trailing entry. */
	static FakeGameFiles zoneGame(int... areas) throws Exception {
		FakeGameFiles g = new FakeGameFiles();
		byte[][] entries = new byte[areas.length + TRAILING][];
		for (int z = 0; z < areas.length; z++) {
			entries[z] = ("zone" + z).getBytes("US-ASCII");
		}
		entries[areas.length] = master(areas);
		entries[areas.length + 1] = "EN".getBytes("US-ASCII");
		File f = new File(g.root(), "zonedata.garc");
		writeGarc(f, entries);
		return g.open(ArchiveType.ZONE_DATA, new GARC(f));
	}

	/** A master zone-header table: one row per zone, holding only its area id. */
	static byte[] master(int... areas) {
		byte[] m = new byte[areas.length * MASTER_ROW];
		for (int z = 0; z < areas.length; z++) {
			putU16(m, z * MASTER_ROW + HDR_AREA_OFF, areas[z]);
		}
		return m;
	}

	/** The bytes of an area container whose world texture pack (subfile 11) holds these textures. */
	static byte[] areaHolding(String... names) throws Exception {
		FakeGameFiles kit = new FakeGameFiles();
		File f = new File(kit.scratch(), "area_" + names.length);
		Files.write(f.toPath(), emptyArea());
		if (!new AD(f, kit).storeFile(11, pack(names))) {
			throw new IllegalStateException("could not build the fixture area");
		}
		return Files.readAllBytes(f.toPath());
	}

	/** An empty 12-slot area container, byte for byte what the container base creates. */
	static byte[] emptyArea() {
		byte[] o = new byte[0x80 + 12 * 0x80];
		o[0] = 'A';
		o[1] = 'D';
		putU16(o, 2, 12);
		for (int i = 0; i <= 12; i++) {
			putI32(o, 4 + 4 * i, 0x80 + i * 0x80);
		}
		return o;
	}

	/** A converter-shaped texture pack of 8x8 L8 textures with these names, each its own picture. */
	static byte[] pack(String... names) {
		List<BchTexturePack.Texture> texes = new ArrayList<>();
		for (String n : names) {
			BchTexturePack.Texture t = new BchTexturePack.Texture();
			t.name = n;
			t.format = 7;
			t.dimParam = 8 | (8 << 16);
			t.data = new byte[64];
			Arrays.fill(t.data, (byte) n.hashCode());
			texes.add(t);
		}
		return BchTexturePack.emit(texes);
	}

	static List<String> namesIn(byte[] pack) {
		List<String> out = new ArrayList<>();
		if (BchTexturePack.isTexturePack(pack)) {
			for (BchTexturePack.Texture t : BchTexturePack.parse(pack)) {
				out.add(t.name);
			}
		}
		return out;
	}

	/** Writes an archive into the game's pristine snapshot where its profile says the table lives. */
	static void pristineArchive(FakeGameFiles g, ArchiveType t, byte[]... entries) throws Exception {
		File f = new File(g.pristine().getAbsolutePath() + g.profile().archivePath(t));
		f.getParentFile().mkdirs();
		writeGarc(f, entries);
	}

	static MaisonSet set(int species) {
		MaisonSet s = new MaisonSet();
		s.species = species;
		return s;
	}

	static MaisonSet[] emptySets(int n) {
		MaisonSet[] out = new MaisonSet[n];
		for (int i = 0; i < n; i++) {
			out[i] = new MaisonSet();
		}
		return out;
	}

	static MaisonClassList classList(int tag, int... indices) {
		MaisonClassList l = new MaisonClassList();
		l.classTag = tag;
		for (int i : indices) {
			l.setIndices.add(i);
		}
		return l;
	}

	/**
	 * A GARC in the layout {@link GARC} parses: a 0x1C header whose length
	 * field points at FATO, a FATO of one offset per entry, a FATB of one
	 * single-file record per entry, and a FIMB whose data the header's data
	 * offset points at. Entries are stored raw, 4-aligned.
	 */
	static void writeGarc(File out, byte[]... entries) throws Exception {
		int n = entries.length;
		int hdr = 0x1C;
		int fatoLen = 0xC + 4 * n;
		int fatbLen = 0xC + 16 * n;
		int dataOff = hdr + fatoLen + fatbLen + 0xC;
		int[] start = new int[n];
		int[] end = new int[n];
		int pos = 0;
		for (int i = 0; i < n; i++) {
			start[i] = pos;
			end[i] = pos + entries[i].length;
			pos = (end[i] + 3) & ~3;
		}
		byte[] o = new byte[dataOff + pos];
		magic(o, 0, "CRAG");
		putI32(o, 4, hdr);
		putU16(o, 8, 0xFEFF);
		putU16(o, 10, 0x0400);
		putI32(o, 12, 4);
		putI32(o, 16, dataOff);
		putI32(o, 20, o.length);
		putI32(o, 24, o.length);
		int fato = hdr;
		magic(o, fato, "OTAF");
		putI32(o, fato + 4, fatoLen);
		putU16(o, fato + 8, n);
		putU16(o, fato + 10, 0xFFFF);
		int fatb = fato + fatoLen;
		magic(o, fatb, "BTAF");
		putI32(o, fatb + 4, fatbLen);
		putI32(o, fatb + 8, n);
		for (int i = 0; i < n; i++) {
			putI32(o, fato + 0xC + 4 * i, 16 * i);
			int rec = fatb + 0xC + 16 * i;
			putI32(o, rec, 1);
			putI32(o, rec + 4, start[i]);
			putI32(o, rec + 8, end[i]);
			putI32(o, rec + 12, entries[i].length);
			System.arraycopy(entries[i], 0, o, dataOff + start[i], entries[i].length);
		}
		int fimb = fatb + fatbLen;
		magic(o, fimb, "BMIF");
		putI32(o, fimb + 4, 0xC);
		putI32(o, fimb + 8, pos);
		Files.write(out.toPath(), o);
	}

	static void magic(byte[] o, int at, String m) {
		for (int i = 0; i < 4; i++) {
			o[at + i] = (byte) m.charAt(i);
		}
	}

	// ------------------------------------------------ helpers
	interface Call {

		void run() throws Exception;
	}

	static void refuses(String what, Call call) {
		try {
			call.run();
			check(false, what + " handed null refuses (it accepted)");
		} catch (IllegalArgumentException ex) {
			check(String.valueOf(ex.getMessage()).contains("handed"),
					what + " handed null refuses and says why: " + firstLine(ex));
		} catch (Exception ex) {
			check(false, what + " handed null refuses in words, not with " + ex);
		}
	}

	static String firstLine(Exception ex) {
		String m = String.valueOf(ex.getMessage());
		int nl = m.indexOf('\n');
		return nl < 0 ? m : m.substring(0, nl);
	}

	static byte[] bytes(String s) {
		return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
	}

	static boolean startsWith(byte[] data, String word) {
		byte[] w = bytes(word);
		if (data == null || data.length < w.length) {
			return false;
		}
		for (int i = 0; i < w.length; i++) {
			if (data[i] != w[i]) {
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
}
