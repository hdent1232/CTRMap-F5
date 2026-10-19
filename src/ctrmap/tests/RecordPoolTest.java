package ctrmap.tests;

import ctrmap.RecordPool;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.maison.MaisonPoolGuard;
import ctrmap.formats.maison.MaisonSet;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * The battle-facility editors never write a record they could not read, never offer one as free
 * space, and write only what changed (F102).
 *
 * <p>WHY THIS SUITE EXISTS. Both facility editors caught any failure to read a record and put a
 * blank one in its place - species 0, which the pool guard then offered as free authoring space,
 * or class 0 with no sets - and their Save rewrote all 999 records through a truncating stream, so
 * the next Save replaced a retail record nobody could read with an empty one, and every Save
 * marked the whole archive edited for the next pack.
 *
 * <p>ORDER: needs no dump and no display; writes only under {@link Scratch}. The dialogs are read
 * in their source: they open over the live workspace, which no suite here has.
 *
 * Usage: java ctrmap.tests.RecordPoolTest
 */
public class RecordPoolTest {

	static int fails = 0;
	static final ArchiveType POOL = ArchiveType.MAISON_SET_POOL_A;

	public static void main(String[] args) throws Exception {
		FakeGameFiles game = new FakeGameFiles();
		File garc = new File(game.root(), "pool.garc");
		byte[] pikachu = set(25), shortOne = new byte[5], mew = set(151);
		HandedGameTest.writeGarc(garc, pikachu, shortOne, mew);
		game.open(POOL, new GARC(garc));

		System.out.println("--- a record that cannot be read is named, not stood in for (F102)");
		MaisonSet[] sets = new MaisonSet[3];
		RecordPool pool = RecordPool.load(game, POOL, sets, MaisonSet::read, MaisonSet::new);
		check(sets[0].species == 25 && sets[2].species == 151, "control: the two whole sets read (" + sets[0].species + ", " + sets[2].species + ")");
		check(pool.unreadable(1) && !pool.unreadable(0) && !pool.unreadable(2) && pool.unreadableCount() == 1,
				"the short one is named unreadable: " + pool.why(1));

		//measured by the 2026-10-12 sweep (RecordPool 60, dropped, SURVIVED): nothing asked a record the game cannot
		//stage. Dropped, the read went on with no file, and the user was told the record could not be read because of
		//a null pointer instead of because the game holds no record to stage - here, its archive gone since it opened
		System.out.println("--- a record the game cannot stage is named with that reason, not a null pointer");
		FakeGameFiles gone = new FakeGameFiles();
		File vanished = new File(gone.root(), "vanished.garc");
		HandedGameTest.writeGarc(vanished, set(25));
		gone.open(POOL, new GARC(vanished));
		check(vanished.delete() && gone.staged(POOL, 0) == null, "CONTROL: the archive's file is gone since it was opened,"
				+ " so the game cannot stage its record 0");
		MaisonSet[] lost = new MaisonSet[1];
		RecordPool unstaged = RecordPool.load(gone, POOL, lost, MaisonSet::read, MaisonSet::new);
		check(unstaged != null && unstaged.unreadable(0) && lost[0] != null
				&& String.valueOf(unstaged.why(0)).contains("the game holds no record 0 to stage"),
				"a record the game cannot stage is named unreadable, with that reason: "
				+ (unstaged == null ? "the load answered no pool at all" : unstaged.why(0)));

		//measured by the 2026-10-12 sweep (RecordPool 114, dropped, SURVIVED): nothing asked a save whose edited record
		//has no file to write to. Dropped, the save went on to write to no file and failed on a null pointer, the
		//record named nowhere - here its staged copy and its archive both gone between the read and the save
		System.out.println("--- a save whose edited record has no file to write to is refused, naming the record");
		FakeGameFiles goneAtSave = new FakeGameFiles();
		File held = new File(goneAtSave.root(), "held.garc");
		HandedGameTest.writeGarc(held, set(25));
		goneAtSave.open(POOL, new GARC(held));
		MaisonSet[] one = new MaisonSet[1];
		RecordPool readable = RecordPool.load(goneAtSave, POOL, one, MaisonSet::read, MaisonSet::new);
		File stagedCopy = goneAtSave.staged(POOL, 0);
		check(!readable.unreadable(0) && one[0].species == 25 && stagedCopy != null && stagedCopy.delete() && held.delete()
				&& goneAtSave.staged(POOL, 0) == null,
				"CONTROL: the record was read, and then its staged copy and its archive are gone, so it has no file");
		one[0].species = 26;
		int heardBefore = goneAtSave.edited().size();
		Object saved;
		try {
			saved = readable.save(one, MaisonSet::write);
		} catch (Exception refused) {
			saved = refused;
		}
		check(saved instanceof java.io.IOException
				&& ((Exception) saved).getMessage().contains("record 0 of " + POOL + " has no file to write to")
				&& goneAtSave.edited().size() == heardBefore,
				"a save whose edited record has no file to write to is refused, naming the record, and nothing is marked"
				+ " edited: " + saved);

		System.out.println("--- it is not free space (F102)");
		MaisonPoolGuard guard = MaisonPoolGuard.load(game, POOL, null, new MaisonSet[]{sets[0], sets[1], new MaisonSet()});
		check(guard.firstFreeSlot(new MaisonSet[]{sets[0], sets[1], new MaisonSet()}) == 1,
				"control: by its look alone, the unreadable slot is the first free one");
		check(guard.firstFreeSlot(new MaisonSet[]{sets[0], sets[1], new MaisonSet()}, pool::unreadable) == 2,
				"passed the records it could not read, the guard offers the next one instead");

		System.out.println("--- a save writes what changed, and nothing it could not read (F102)");
		int heard = game.edited().size();
		List<Integer> none = pool.save(sets, MaisonSet::write);
		check(none.isEmpty() && game.edited().size() == heard, "nothing changed, nothing is written or marked edited: " + none);
		sets[2].species = 150;
		sets[1].species = 9;
		List<Integer> wrote = pool.save(sets, MaisonSet::write);
		check(wrote.equals(Arrays.asList(2)), "an edit to one set writes that one: " + wrote);
		check(Arrays.equals(Files.readAllBytes(game.staged(POOL, 1).toPath()), shortOne),
				"and the record it could not read still holds its own bytes, not the placeholder edited in memory");
		check(Arrays.equals(Files.readAllBytes(game.staged(POOL, 0).toPath()), pikachu)
				&& MaisonSet.read(Files.readAllBytes(game.staged(POOL, 2).toPath())).species == 150,
				"the untouched set is as it was and the edited one says 150");
		check(pool.save(sets, MaisonSet::write).isEmpty(), "and saved again, nothing more is written");

		System.out.println("--- both editors read and write through it (F102)");
		String sets_ = source("src/ctrmap/humaninterface/MaisonEditDialog.java");
		String lists_ = source("src/ctrmap/humaninterface/MaisonClassListDialog.java");
		String loadPool = CollisionReadTest.body(sets_, "void loadPool(int idx)");
		String saveSets = CollisionReadTest.body(sets_, "void save(SaveHistory saves) throws Exception");
		String savePool = CollisionReadTest.body(sets_, "public static <T> List<Integer> savePool(");
		String editSet = CollisionReadTest.body(sets_, "public void setValueAt(Object v, int r, int c)");
		String copy = CollisionReadTest.body(sets_, "int copyToFreeSlot(int r)");
		String loadTable = CollisionReadTest.body(lists_, "void loadTable(int idx)");
		String saveTable = CollisionReadTest.body(lists_, "void save(SaveHistory saves) throws Exception");
		String tableSeam = CollisionReadTest.body(lists_, "public static java.util.List<Integer> saveTable(");
		String editList = CollisionReadTest.body(lists_, "public void setValueAt(Object v, int r, int c)");
		check(loadPool != null && loadPool.contains("poolIndex = idx") && saveSets != null && saveSets.contains("dirty = false")
				&& savePool != null && tableSeam != null
				&& editSet != null && editSet.contains("allowVanillaEdits") && copy != null && copy.contains("fireTableRowsUpdated")
				&& loadTable != null && loadTable.contains("tableIndex = idx") && saveTable != null && editList != null
				&& editList.contains("disclosed"), "control: every method read is found");
		check(loadPool != null && loadPool.contains("RecordPool.load(") && !loadPool.contains("new MaisonSet()")
				&& loadTable != null && loadTable.contains("RecordPool.load(") && !loadTable.contains("new MaisonClassList()"),
				"both read through RecordPool, neither puts a blank record in for one it could not read");
		check(savePool != null && savePool.contains("pool.save(") && savePool.contains("WorkspaceUnit.begin(")
				&& !savePool.contains("FileOutputStream") && saveSets != null && saveSets.contains("savePool(")
				&& saveTable != null && saveTable.contains("saveTable(") && tableSeam != null
				&& tableSeam.contains("MaisonEditDialog.savePool("),
				"both save only what changed, as one unit - the one savePool, which both editors' saves call");
		check(editSet != null && editSet.contains("pool.unreadable(r)") && editList != null && editList.contains("pool.unreadable(r)"),
				"both refuse to edit a record they could not read");
		check(copy != null && copy.contains("pool::unreadable"), "and a copy is never put in one");

		//census M.126: every door away from the pool editor asks about its unsaved edits - Class assignments replaced
		//the editor, and its edits with it, without asking, where the pool dropdown and Close asked
		int asked = sets_.split("model\\.dirty && !confirmDiscard\\(dlg\\)", -1).length - 1;
		check(asked >= 3, "the pool dropdown, Close and Class assignments each ask about unsaved pool edits (" + asked + ")");

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	static byte[] set(int species) {
		MaisonSet s = new MaisonSet();
		s.species = species;
		return s.write();
	}

	static String source(String path) throws Exception {
		return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
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
