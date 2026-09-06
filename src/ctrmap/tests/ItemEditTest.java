package ctrmap.tests;

import ctrmap.ModDeployer;
import ctrmap.Workspace;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.pokedata.ItemData;
import ctrmap.formats.pokedata.ItemTable;
import ctrmap.formats.recordschema.RecordField;
import ctrmap.formats.recordschema.RecordSchema;
import ctrmap.formats.recordschema.SchemaRegistry;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Saving an item must change the bytes the user changed and NOTHING else.
 *
 * <p>WHY THIS IS THE GUARD THAT MATTERS. The item table is 776 fixed-size
 * records the game indexes by position, inside a container with its own offset
 * table. Every other archive in this editor is written by extracting to a
 * directory and repacking, which rebuilds that offset table on every save - and
 * this project has already repaired a zone by hand after a pack wrote a stale
 * one. An item edit does not need any of that: the record is exactly as long as
 * the one it replaces, so the writer seeks and pokes 36 bytes. This suite is
 * what makes "and nothing else moved" a fact instead of an intention:
 * <ul>
 * <li>the archive's length is unchanged;</li>
 * <li>every byte outside the edited record is identical, header and offset
 *     table included - so the container was not rebuilt;</li>
 * <li>inside the record, only the bytes belonging to the edited field differ;</li>
 * <li>re-opening the archive still yields 776 readable records.</li>
 * </ul>
 *
 * <p>It also pins the count the UI is required to state plainly: FOUR ids can
 * hold a new item. Five records are blank, but id 0 is the "no item" sentinel
 * that every empty held-item slot in the game points at, so it is not a slot.
 * Offering a fifth would be offering the user a way to break every Pokemon
 * holding nothing.
 *
 * Usage: java ctrmap.tests.ItemEditTest &lt;romfs-root&gt;
 */
public class ItemEditTest {

	static int fails = 0;

	static void check(boolean cond, String msg) {
		if (cond) {
			System.out.println("  ok: " + msg);
		} else {
			System.out.println("  FAIL: " + msg);
			fails++;
		}
	}

	//per-game fact -> gamedef seam, never a literal in a suite
	private static String itemArchive() {
		String p = ctrmap.gamedef.GameProfile.of(ctrmap.Workspace.GameType.ORAS)
				.archivePath(ctrmap.Workspace.ArchiveType.ITEM_DATA);
		return p == null ? "" : p;
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println("  skip: no romfs root given");
			System.out.println("ALL PASS");
			return;
		}
		File src = new File(args[0] + itemArchive());
		if (!src.isFile()) {
			System.out.println("  skip: no item archive at " + src);
			System.out.println("ALL PASS");
			return;
		}
		File tmp = Scratch.dir("ctrmap_item_edit");
		File work = new File(tmp, "itemdata");
		Files.copy(src.toPath(), work.toPath(), StandardCopyOption.REPLACE_EXISTING);
		File baseline = new File(tmp, "baseline");

		anEditChangesOnlyWhatWasEdited(work, baseline);
		theArchiveIsNotRepacked(work, src);
		refusesWhatWouldLandWrong(work, baseline);
		fourSlotsAreFreeAndNotFive(work, args[0]);
		theBaselineIsTakenOnceAndNeverRetaken(tmp, src);
		//last two: they repoint the Workspace statics at a scratch game
		deployShipsItOnlyWhenItWasEdited(tmp, src);
		theEditorRefusesBeforeItBuildsAnything();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** Ultra Ball: a well-known record, and one whose price we can state. */
	private static final int ULTRA_BALL = 2;

	static void anEditChangesOnlyWhatWasEdited(File work, File baseline) throws Exception {
		System.out.println("--- an edited record writes back byte-identical except the field changed");
		byte[] before = Files.readAllBytes(work.toPath());

		ItemTable t = ItemTable.open(work, baseline);
		byte[] rec = t.raw(ULTRA_BALL);
		check(rec != null && new ItemData(rec).buyPrice() == 1200,
				"Ultra Ball reads as the retail record before the edit (price "
				+ (rec == null ? "missing" : String.valueOf(new ItemData(rec).buyPrice())) + ")");

		RecordField price = fieldNamed("Price / 10");
		byte[] edited = rec.clone();
		price.set(edited, 999);
		t.writeRecord(ULTRA_BALL, edited);

		byte[] after = Files.readAllBytes(work.toPath());
		check(before.length == after.length,
				"the archive is still " + before.length + " bytes (got " + after.length + ")");

		//exactly which bytes moved, counted rather than sampled
		List<Integer> changed = new ArrayList<>();
		int n = Math.min(before.length, after.length);
		for (int i = 0; i < n; i++) {
			if (before[i] != after[i]) {
				changed.add(i);
			}
		}
		int recOff = new GARC(work, false).getEntryFileOffset(ULTRA_BALL);
		boolean allInsideTheField = true;
		for (int off : changed) {
			int within = off - recOff;
			if (within < price.byteOffset() || within >= price.endByte()) {
				allInsideTheField = false;
			}
		}
		check(!changed.isEmpty(), "the save actually wrote something (" + changed.size() + " byte(s))");
		check(allInsideTheField, "every changed byte in the whole 43 KB archive lies inside the two"
				+ " bytes of the field that was edited" + (allInsideTheField ? "" : " - changed " + changed));

		ItemTable back = ItemTable.open(work, baseline);
		check(back.record(ULTRA_BALL).priceRaw() == 999, "and the new price reads back");
		check(back.record(ULTRA_BALL).heldEffect() == new ItemData(rec).heldEffect()
				&& back.record(ULTRA_BALL).sortIndex() == new ItemData(rec).sortIndex(),
				"while the rest of the same record is untouched");
	}

	static void theArchiveIsNotRepacked(File work, File pristine) throws Exception {
		System.out.println("--- a record written in place does not repack the archive");
		byte[] live = Files.readAllBytes(work.toPath());
		byte[] orig = Files.readAllBytes(pristine.toPath());
		check(live.length == orig.length, "same length as the retail archive");

		//The container's header and offset tables live outside the record data.
		//A repack rewrites them - identical CONTENT, different bytes - so their
		//being byte-identical is the proof that nothing rebuilt the container.
		GARC g = new GARC(work, false);
		int dataStart = g.getEntryFileOffset(0);
		boolean headerIntact = true;
		for (int i = 0; i < dataStart; i++) {
			if (live[i] != orig[i]) {
				headerIntact = false;
				break;
			}
		}
		check(dataStart > 0, "the record data starts at 0x" + Integer.toHexString(dataStart)
				+ ", after a header and offset table");
		check(headerIntact, "every byte of that header and offset table is unchanged - the"
				+ " container was not rebuilt");

		//and the whole table still reads: 776 records, all the right size
		int good = 0;
		for (int i = 0; i < g.getEntryCount(); i++) {
			byte[] b = g.getDecompressedEntry(i);
			if (b != null && b.length == ItemData.SIZE) {
				good++;
			}
		}
		check(good == g.getEntryCount(), "all " + g.getEntryCount()
				+ " records still read at " + ItemData.SIZE + " bytes (" + good + ")");

		//every OTHER record is byte-identical to retail
		GARC og = new GARC(pristine, false);
		int differing = 0;
		for (int i = 0; i < g.getEntryCount(); i++) {
			if (i == ULTRA_BALL) {
				continue;
			}
			byte[] a = g.getDecompressedEntry(i), b = og.getDecompressedEntry(i);
			if (a == null || b == null || !java.util.Arrays.equals(a, b)) {
				differing++;
			}
		}
		check(differing == 0, "and every other record is byte-identical to retail ("
				+ differing + " differed)");
	}

	static void refusesWhatWouldLandWrong(File work, File baseline) throws Exception {
		System.out.println("--- a write that could land in the wrong place is refused, not attempted");
		ItemTable t = ItemTable.open(work, baseline);
		//A payload of NON-ZERO bytes, on purpose. Zeros made the check pass for
		//the wrong reason while this suite was being proven: item 0's record is
		//all zeros, so a rejected write of zeros over it leaves the file
		//identical whether it was rejected or not.
		check(refuses(t, work, 0, filled(ItemData.SIZE - 1)),
				"a record that is not " + ItemData.SIZE + " bytes");
		check(refuses(t, work, 0, filled(ItemData.SIZE + 1)), "or one byte too long");
		check(refuses(t, work, -1, filled(ItemData.SIZE)), "a negative id");
		check(refuses(t, work, t.count(), filled(ItemData.SIZE)),
				"an id past the end of the table - this editor does not raise the 776 ceiling");
		check(refuses(t, work, t.count() + 5000, filled(ItemData.SIZE)), "and one far past it");
	}

	/**
	 * Refused means REFUSED - it threw AND it wrote nothing.
	 *
	 * <p>"It threw" alone is not the property. Measured while proving this
	 * suite: deleting the record-length check let a 35-byte write through, the
	 * read-back then died on an index, and a check that only asked for an
	 * exception scored that as a refusal - while 35 bytes of the user's archive
	 * had already been overwritten. The file is the witness, not the stack.
	 */
	static byte[] filled(int n) {
		byte[] b = new byte[n];
		java.util.Arrays.fill(b, (byte) 0x5A);
		return b;
	}

	static boolean refuses(ItemTable t, File archive, int id, byte[] rec) throws Exception {
		byte[] before = Files.readAllBytes(archive.toPath());
		boolean threw = false;
		try {
			t.writeRecord(id, rec);
		} catch (Exception ex) {
			threw = true;
		}
		byte[] after = Files.readAllBytes(archive.toPath());
		return threw && java.util.Arrays.equals(before, after);
	}

	static void fourSlotsAreFreeAndNotFive(File work, String romfs) throws Exception {
		System.out.println("--- exactly four slots are offered for a new item, and a fifth is not");
		ItemTable t = ItemTable.open(work, null);
		List<String> names = ItemDataTest.itemNames(romfs);
		List<Integer> free = t.freeSlots(names);
		System.out.println("      free slots: " + free);
		check(free.size() == 4, "four slots are free, which is a real limit and not a design choice"
				+ " (got " + free.size() + ")");
		check(free.contains(113) && free.contains(114) && free.contains(115) && free.contains(126),
				"they are 113, 114, 115 and 126, as measured");
		check(!free.contains(0), "id 0 is NOT among them - it is the \"no item\" sentinel every"
				+ " empty held-item slot in the game points at, so handing it out would break"
				+ " every Pokemon holding nothing");

		//count the blanks independently: five records are blank, four are usable
		int blanks = 0;
		for (int i = 0; i < t.count(); i++) {
			ItemData d = t.record(i);
			if (d != null && d.isBlank()) {
				blanks++;
			}
		}
		check(blanks == free.size() + 1, blanks + " records are blank and " + free.size()
				+ " are offered - the difference is id 0, and it is exactly one");

		//an occupied id must never be offered, however cheap it looks
		check(!free.contains(2) && !free.contains(50),
				"a real item's id is never offered as free");
	}

	static void theBaselineIsTakenOnceAndNeverRetaken(File tmp, File pristine) throws Exception {
		System.out.println("--- the pre-edit copy is taken before the first write, and never retaken");
		File dir = new File(tmp, "baseline2");
		File work = new File(tmp, "itemdata2");
		Files.copy(pristine.toPath(), work.toPath(), StandardCopyOption.REPLACE_EXISTING);

		ItemTable t = ItemTable.open(work, dir);
		check(!new File(dir, "itemdata.garc").isFile(),
				"opening the table alone takes no copy - reading is not editing");

		byte[] rec = t.raw(ULTRA_BALL);
		fieldNamed("Price / 10").set(rec, 42);
		t.writeRecord(ULTRA_BALL, rec);
		File copy = new File(dir, "itemdata.garc");
		check(copy.isFile(), "the first write takes the copy");
		check(java.util.Arrays.equals(Files.readAllBytes(copy.toPath()),
				Files.readAllBytes(pristine.toPath())),
				"and the copy is the archive as it was BEFORE that write, byte for byte");

		//a second write must not overwrite the copy with the already-edited file
		fieldNamed("Price / 10").set(rec, 43);
		t.writeRecord(ULTRA_BALL, rec);
		check(java.util.Arrays.equals(Files.readAllBytes(copy.toPath()),
				Files.readAllBytes(pristine.toPath())),
				"a second write leaves it alone - a baseline that drifts forward records the"
				+ " user's edits as the original");

		//and if the copy is deleted after edits, it must NOT be silently retaken
		//from a file that now holds those edits
		check(copy.delete(), "the copy is deleted, as a careless cleanup would");
		boolean refused = false;
		try {
			fieldNamed("Price / 10").set(rec, 44);
			t.writeRecord(ULTRA_BALL, rec);
		} catch (Exception ex) {
			refused = true;
		}
		check(refused, "the next write REFUSES rather than capturing the edited archive as retail"
				+ " - the mistake that contaminated six archives in this project once already");
		check(!copy.isFile(), "and nothing was written into the baseline folder");
	}

	/**
	 * Deploy must carry the item archive when it was edited, and must not
	 * disturb the pristine-snapshot contract to do it.
	 *
	 * <p>The second half is the part worth pinning. Adding the item archive to
	 * {@code MODDABLE} would have been the obvious move and is the wrong one:
	 * that list is also the snapshot's contract, and the snapshot refuses to
	 * complete itself from a game that has been in use - so every workspace
	 * already stamped would report a permanently partial backup, on every pack,
	 * and be told to delete it. This asserts the two lists stay separate, and
	 * that a backup which must be whole still gets both.
	 */
	static void deployShipsItOnlyWhenItWasEdited(File tmp, File pristine) throws Exception {
		System.out.println("--- deploy ships the item archive when it changed, and only then");
		List<Workspace.ArchiveType> moddable = Arrays.asList(ModDeployer.MODDABLE);
		check(!moddable.contains(Workspace.ArchiveType.ITEM_DATA),
				"the item archive is NOT in MODDABLE - that list is the pristine snapshot's"
				+ " contract, and an archive added to it after a workspace was stamped can never"
				+ " be captured, so every existing workspace would report a partial backup forever");
		check(Arrays.asList(ModDeployer.MODDABLE_IN_PLACE).contains(Workspace.ArchiveType.ITEM_DATA),
				"it is in MODDABLE_IN_PLACE instead");
		List<Workspace.ArchiveType> all = ModDeployer.allWritableArchives();
		check(all.containsAll(moddable) && all.contains(Workspace.ArchiveType.ITEM_DATA),
				"and a backup that must be whole gets both lists (" + all.size() + " archives)");
		check(new java.util.HashSet<>(all).size() == all.size(),
				"with nothing counted twice");

		//now the decision itself, against a scratch game and a scratch workspace
		File game = new File(tmp, "game");
		File ws = new File(tmp, "ws");
		String rel = ctrmap.gamedef.GameProfile.of(Workspace.GameType.ORAS)
				.archivePath(Workspace.ArchiveType.ITEM_DATA);
		File live = new File(game.getAbsolutePath() + rel);
		live.getParentFile().mkdirs();
		ws.mkdirs();
		Files.copy(pristine.toPath(), live.toPath(), StandardCopyOption.REPLACE_EXISTING);

		String oldGame = Workspace.GAMEDIR_PATH, oldWs = Workspace.WORKSPACE_PATH;
		Workspace.GameType oldType = Workspace.game;
		try {
			Workspace.game = Workspace.GameType.ORAS;
			Workspace.GAMEDIR_PATH = game.getAbsolutePath();
			Workspace.WORKSPACE_PATH = ws.getAbsolutePath();

			check(ItemTable.archiveFile() != null, "the editor finds the archive through the profile");
			check(ItemTable.openWorkspace() != null,
					"and opens it for ORAS, where the location was measured");
			//A path that is only CITED is not a path this editor may write
			//through. XY's item archive comes from pk3DS's reference tables and
			//has never been measured against an XY dump here, so the editor
			//must refuse it - a 36-byte poke into a probably-right offset is the
			//kind of confident wrong answer this project keeps paying for.
			//The XY archive is PUT THERE first, so the only possible reason to
			//refuse is the verification gate. Without this the check passed
			//while the gate was deleted, because the file simply was not there -
			//a guard answering a question nobody asked.
			String xyRel = ctrmap.gamedef.GameProfile.of(Workspace.GameType.XY)
					.archivePath(Workspace.ArchiveType.ITEM_DATA);
			File xyLive = new File(game.getAbsolutePath() + xyRel);
			xyLive.getParentFile().mkdirs();
			Files.copy(pristine.toPath(), xyLive.toPath(), StandardCopyOption.REPLACE_EXISTING);
			Workspace.game = Workspace.GameType.XY;
			boolean xyPresent = ItemTable.archiveFile() != null;
			boolean xyRefused = ItemTable.openWorkspace() == null;
			Workspace.game = Workspace.GameType.ORAS;
			check(xyPresent, "the XY item archive is present in the fixture, so a refusal can only"
					+ " come from the verification gate");
			check(xyRefused, "and REFUSES a game whose item table is only cited, never measured"
					+ " - XY has a path from pk3DS and no verification, so the editor will not"
					+ " write through it");
			check(!ItemTable.changedSinceBaseline(),
					"a workspace that has never edited items ships nothing - no pre-edit copy"
					+ " means nothing of the user's is in there");

			ItemTable t = ItemTable.openWorkspace();
			byte[] rec = t.raw(ULTRA_BALL);
			byte[] original = rec.clone();
			fieldNamed("Price / 10").set(rec, 7);
			t.writeRecord(ULTRA_BALL, rec);
			check(ItemTable.changedSinceBaseline(), "after an edit, deploy ships it");

			t.writeRecord(ULTRA_BALL, original);
			check(!ItemTable.changedSinceBaseline(),
					"and putting the bytes back makes it stop shipping - the test is the CONTENT,"
					+ " not whether the editor was ever opened");
		} finally {
			Workspace.GAMEDIR_PATH = oldGame;
			Workspace.WORKSPACE_PATH = oldWs;
			Workspace.game = oldType;
		}
	}

	/**
	 * The editor's refusals must happen BEFORE it builds a window.
	 *
	 * <p>Two things at once, and the second is why this runs headless. A user
	 * with no workspace, or with a game whose item table was never verified,
	 * must get a sentence - not a stack trace, and not a half-built dialog. A
	 * headless JVM cannot construct a window at all, so if either check ever
	 * moves below the first {@code new JDialog(...)} this suite stops passing
	 * and starts throwing HeadlessException. The ordering is the assertion.
	 */
	static void theEditorRefusesBeforeItBuildsAnything() {
		System.out.println("--- the editor refuses with a sentence, before it builds a window");
		boolean oldValid = Workspace.valid;
		Workspace.GameType oldType = Workspace.game;
		try {
			Workspace.valid = false;
			List<String> said = ctrmap.Ui.record();
			ctrmap.humaninterface.ItemEditDialog.show(null);
			ctrmap.Ui.stopRecording();
			check(said.size() == 1 && said.get(0).contains("workspace"),
					"with no workspace loaded it says so and returns (" + said + ")");

			Workspace.valid = true;
			Workspace.game = Workspace.GameType.XY;
			said = ctrmap.Ui.record();
			ctrmap.humaninterface.ItemEditDialog.show(null);
			ctrmap.Ui.stopRecording();
			check(said.size() == 1 && said.get(0).contains("VERIFIED"),
					"and for a game whose item table was never measured it says THAT, rather than"
					+ " opening a form over a cited offset");
		} catch (Throwable t) {
			ctrmap.Ui.stopRecording();
			check(false, "it returned without throwing - got " + t);
		} finally {
			Workspace.valid = oldValid;
			Workspace.game = oldType;
		}
	}

	static RecordField fieldNamed(String name) {
		RecordSchema s = SchemaRegistry.items();
		for (RecordField f : s.fields()) {
			if (f.name().equals(name)) {
				return f;
			}
		}
		throw new IllegalStateException("no field named " + name);
	}
}
