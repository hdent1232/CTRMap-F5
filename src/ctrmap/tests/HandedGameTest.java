package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.formats.containers.AD;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchTexturePack;
import ctrmap.formats.maison.MaisonClassList;
import ctrmap.formats.maison.MaisonPoolGuard;
import ctrmap.formats.maison.MaisonSet;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static ctrmap.formats.LittleEndian.putI32;
import static ctrmap.formats.LittleEndian.putU16;

/**
 * Two more format classes work when HANDED their game, with no workspace open,
 * and answer for the game they are handed rather than for the one the
 * application has open: the area texture operations of
 * {@link BchTexturePack} and the vanilla-safety guard {@link MaisonPoolGuard}.
 *
 * <p>WHY THIS SUITE EXISTS. Both classes fetched the open game from
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

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}
