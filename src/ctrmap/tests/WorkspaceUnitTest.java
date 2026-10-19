package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.WorkspaceUnit;
import ctrmap.formats.containers.MM;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A change to the workspace that writes several files happens whole, or not at all.
 *
 * <p>THE WORKSPACE HAD NO UNIT OF WORK (F227). Fourteen operations wrote their files one after
 * another and stopped wherever an exception stopped them: resizing a map wrote the new regions,
 * then the matrix, then the zone header, then the master row, and a failure between any two left
 * the earlier ones staged for the next pack - a matrix nothing points to, a header and master row
 * that disagree. {@link WorkspaceUnit} copies every file before its first write and puts all of
 * them back, with the list of edits and the pending compression flags, unless the operation
 * commits. These checks throw part way through a unit and require the workspace byte-identical
 * to before - the files written directly, a copy, a container's own store, a file that did not
 * exist - and require what cannot be put back to be SAID, never swallowed.
 *
 * <p>No game data is needed: the workspace is a scratch folder and the session opens nothing.
 */
public class WorkspaceUnitTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		Workspace.reset();
		try {
			aUnitThatFailsPutsEveryFileBack();
			aCommittedUnitKeepsEverything();
			aContainerStoreIsPutBackToo();
			aNestedUnitJoinsTheOutermost();
			aNestedUnitThatFailsPutsBackItsOwn();
			aFileThatCannotBeCopiedIsNotWritten();
			whatCannotBePutBackIsSaid();
			rawWritesOnlyFall(new File(args.length > 0 ? args[0] : "build/classes"));
			theOperationsASuiteCannotRunOpenAUnit(new File(args.length > 0 ? args[0] : "build/classes"));
			everyCallerOfASaveThatStagesSeveralFilesOpensAUnit(new File(args.length > 0 ? args[0] : "build/classes"));
		} finally {
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * Interior wiring and a geometry fork from ANY caller each run in one unit (F227). Both need a
	 * game's archives to run, which no suite here has (ZoneLinkUnitTest runs Connect zones for
	 * real; these two are owed a run over the dump), so what is checked is their SHAPE in the
	 * bytecode: a unit begun before the first write and committed after the last. {class, method,
	 * the call that makes the first write, the call that makes the last}.
	 */
	static void theOperationsASuiteCannotRunOpenAUnit(File classes) throws IOException {
		System.out.println("--- interior wiring, a geometry fork and both area forks each open a unit, whoever calls them"
				+ " (F227, F002, F003)");
		String[][] ops = {
			{"ctrmap/InteriorWirer", "cloneAndWire", "cloneAndWireIn", "cloneAndWireIn"},
			{"ctrmap/GeometryForker", "forkGeometry", "forkArchives", "repointMasterRow"},
			//a zone's own fork wrote five files and then a master row that can refuse (F003); the
			//appended zones' forks wrote zone by zone with only the id budget checked first (F002)
			{"ctrmap/AreaForker", "forkArea", "forkAreaWrites", "forkAreaWrites"},
			{"ctrmap/AreaForker", "forkAppendedAreas", "forkAppendedAreasWrites", "forkAppendedAreasWrites"},
		};
		for (String[] op : ops) {
			File f = new File(classes, op[0] + ".class");
			if (!f.isFile()) {
				check(false, "no compiled " + op[0] + " under " + classes.getAbsolutePath() + " - build first");
				continue;
			}
			Bytecode.Method m = null;
			for (Bytecode.Method x : Bytecode.readClass(f).methods.values()) {
				if (x.name.equals(op[1]) && x.hasCode) {
					m = x;
				}
			}
			int begin = -1, first = -1, last = -1, commit = -1;
			for (int i = 0; m != null && i < m.code.size(); i++) {
				Bytecode.Insn x = m.code.get(i);
				if (!x.isInvoke()) {
					continue;
				}
				boolean unit = x.owner.equals("ctrmap/WorkspaceUnit");
				if (unit && x.name.equals("begin") && begin < 0) {
					begin = i;
				} else if (unit && (x.name.equals("commit") || x.name.equals("commitKeeping"))) {
					commit = i;
				} else if (x.name.equals(op[2]) && first < 0) {
					first = i;
				}
				if (x.name.equals(op[3])) {
					last = i;
				}
			}
			check(m != null && begin >= 0 && first > begin && last >= first && commit > last,
					op[0] + "." + op[1] + " begins a unit before " + op[2] + " and commits it after " + op[3]
					+ " (call order: begin " + begin + ", first write " + first + ", last " + last + ", commit " + commit + ")");
		}
	}

	/**
	 * Saves that stage more than one file and cannot open a unit themselves - a format class may not
	 * reach the application's unit (F214) - so whoever calls one runs it inside a unit: {owner, name}.
	 */
	private static final String[][] SAVES_THAT_STAGE_SEVERAL_FILES = {
		//an item's record and its name and description are staged together, and a failure part way
		//puts all of them back (F082)
		{"ctrmap/formats/pokedata/ItemEditSession", "save"},
	};

	/**
	 * Every caller of such a save, in any compiled class of the program, begins a unit before the call
	 * and commits it after, in the same method (F082). Read from the bytecode of every class rather
	 * than from a list of callers, so a second caller is held to it the day it is written - a lambda's
	 * body is a method of its own and is read as one.
	 */
	static void everyCallerOfASaveThatStagesSeveralFilesOpensAUnit(File classes) throws IOException {
		System.out.println("--- every caller of a save that stages several files runs it in one unit (F082)");
		java.util.Map<String, Bytecode.Cls> all = Bytecode.read(classes);
		for (String[] save : SAVES_THAT_STAGE_SEVERAL_FILES) {
			int calls = 0;
			List<String> outside = new ArrayList<>();
			for (Bytecode.Cls c : all.values()) {
				if (c.name.startsWith("ctrmap/tests/")) {
					continue; //suites drive the save bare, on purpose
				}
				for (Bytecode.Method m : c.methods.values()) {
					int begin = -1, commit = -1;
					List<Integer> at = new ArrayList<>();
					for (int i = 0; m.hasCode && i < m.code.size(); i++) {
						Bytecode.Insn x = m.code.get(i);
						if (!x.isInvoke()) {
							continue;
						}
						boolean unit = x.owner.equals("ctrmap/WorkspaceUnit");
						if (unit && x.name.equals("begin") && begin < 0) {
							begin = i;
						} else if (unit && (x.name.equals("commit") || x.name.equals("commitKeeping"))) {
							commit = i;   //commitKeeping commits, and keeps what it takes to put it back (F209c)
						} else if (x.owner.equals(save[0]) && x.name.equals(save[1])) {
							at.add(i);
						}
					}
					for (int call : at) {
						calls++;
						if (begin < 0 || begin > call || commit < call) {
							outside.add(m + " (begin " + begin + ", call " + call + ", commit " + commit + ")");
						}
					}
				}
			}
			check(calls > 0, "control: the program calls " + save[0] + "." + save[1] + " (" + calls + " call(s))");
			check(outside.isEmpty(), "and every call is inside a unit begun before it and committed after it"
					+ (outside.isEmpty() ? "" : " - not: " + outside));
		}
	}

	static WorkspaceSession session(String name) throws IOException {
		return Sessions.bare(Scratch.dir("ctrmap_unit_" + name), Scratch.dir("ctrmap_unit_game"), GameType.ORAS);
	}

	static File put(File dir, String name, String text) throws IOException {
		File f = new File(dir, name);
		Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
		return f;
	}

	static String read(File f) throws IOException {
		return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
	}

	static void aUnitThatFailsPutsEveryFileBack() throws Exception {
		System.out.println("--- a unit that fails puts every file, the edit list and the pending flags back");
		WorkspaceSession s = session("rollback");
		File ws = s.workspaceDir();
		File a = put(ws, "a.bin", "old a");
		File b = new File(ws, "b.bin");
		File c = new File(ws, "c.bin");
		s.addPersist(a);
		s.registerPendingCompression(ArchiveType.FIELD_DATA, 1, true);
		List<String> editsBefore = new ArrayList<>(s.persistPaths());
		boolean[] undone = {false};
		IOException planted = new IOException("planted: the operation's third step failed");
		IOException caught = null;
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Test operation", s)) {
			WorkspaceUnit.write(a, "new a".getBytes(StandardCharsets.UTF_8));
			WorkspaceUnit.write(b, "new b".getBytes(StandardCharsets.UTF_8));
			WorkspaceUnit.copy(a, c);
			s.addPersist(b);
			s.registerPendingCompression(ArchiveType.FIELD_DATA, 2, false);
			WorkspaceUnit.inMemory(() -> undone[0] = true, () -> { });
			throw planted;
		} catch (IOException ex) {
			caught = ex;
		}
		check(caught == planted, "the operation's own exception reaches its caller (" + caught + ")");
		check("old a".equals(read(a)), "a file the unit rewrote holds its old bytes again (holds \"" + read(a) + "\")");
		check(!b.exists(), "a file the unit created is gone");
		check(!c.exists(), "a file the unit copied into place is gone");
		check(editsBefore.equals(s.persistPaths()),
				"the list of edits is what it was - a file put back but still listed would ship as an edit "
				+ "(" + s.persistPaths() + ")");
		check(editsBefore.equals(WorkspaceSession.persistedIn(ws)),
				"and so is the list ON DISK, which the next open reads - it is written through as it changes "
				+ "(" + WorkspaceSession.persistedIn(ws) + ")");
		Map<Integer, Boolean> pending = s.consumePendingCompression(ArchiveType.FIELD_DATA);
		check(pending != null && pending.size() == 1 && Boolean.TRUE.equals(pending.get(1)),
				"the pending compression flags are what they were - a flag for a slot that was put back "
				+ "would steer the next pack (" + pending + ")");
		check(undone[0], "an in-memory undo the operation registered ran");
		check(WorkspaceUnit.current() == null, "and no unit is left open on the thread");
	}

	static void aCommittedUnitKeepsEverything() throws Exception {
		System.out.println("--- a committed unit keeps everything it wrote");
		WorkspaceSession s = session("commit");
		File ws = s.workspaceDir();
		File a = put(ws, "a.bin", "old a");
		File b = new File(ws, "b.bin");
		boolean[] undone = {false};
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Test operation", s)) {
			WorkspaceUnit.write(a, "new a".getBytes(StandardCharsets.UTF_8));
			WorkspaceUnit.write(b, "new b".getBytes(StandardCharsets.UTF_8));
			s.addPersist(b);
			s.registerPendingCompression(ArchiveType.ZONE_DATA, 5, true);
			WorkspaceUnit.inMemory(() -> undone[0] = true, () -> { });
			//the two it wrote, and the list of edits it added b to - written through as it changes (F034)
			check(unit.touched().size() == 3 && unit.touched().contains(WorkspaceSession.persistFile(ws).getAbsoluteFile()),
					"the unit knows the 2 files it touched and the edit list (" + unit.touched() + ")");
			unit.commit();
		}
		check("new a".equals(read(a)) && b.isFile() && "new b".equals(read(b)), "both writes are kept");
		check(s.persistPaths().contains(b.getAbsolutePath()), "the new edit stays listed");
		Map<Integer, Boolean> pending = s.consumePendingCompression(ArchiveType.ZONE_DATA);
		check(pending != null && Boolean.TRUE.equals(pending.get(5)), "the pending flag stays (" + pending + ")");
		check(!undone[0], "and no undo ran");
	}

	static void aContainerStoreIsPutBackToo() throws Exception {
		System.out.println("--- a container's own store, inside a unit, is put back with the rest");
		WorkspaceSession s = session("container");
		File f = new File(s.workspaceDir(), "7");
		MM mm = new MM(f, 3, 0, s);
		mm.storeFile(1, new byte[]{1, 2, 3, 4});
		byte[] before = Files.readAllBytes(f.toPath());
		RuntimeException planted = new RuntimeException("planted: a later write failed");
		RuntimeException caught = null;
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Test operation", s)) {
			mm.storeFile(1, new byte[]{9, 9, 9, 9, 9, 9, 9, 9});
			check(unit.touched().contains(f.getAbsoluteFile()),
					"the store told the unit before it wrote (touched " + unit.touched() + ")");
			throw planted;
		} catch (RuntimeException ex) {
			caught = ex;
		}
		check(caught == planted, "the operation's exception reaches its caller");
		check(Arrays.equals(before, Files.readAllBytes(f.toPath())),
				"the container is byte-identical to before the unit - a store that skipped the session's"
				+ " aboutToWrite would be kept by a unit that rolled back");
	}

	static void aNestedUnitJoinsTheOutermost() throws Exception {
		System.out.println("--- a unit begun inside another joins it, and only the outermost decides");
		WorkspaceSession s = session("nested");
		File ws = s.workspaceDir();
		File a = put(ws, "a.bin", "old a");
		File b = put(ws, "b.bin", "old b");
		IOException caught = null;
		try (WorkspaceUnit outer = WorkspaceUnit.begin("Append", s)) {
			WorkspaceUnit.write(a, "new a".getBytes(StandardCharsets.UTF_8));
			try (WorkspaceUnit inner = WorkspaceUnit.begin("Fork geometry", s)) {
				WorkspaceUnit.write(b, "new b".getBytes(StandardCharsets.UTF_8));
				inner.commit();
			}
			check("new b".equals(read(b)), "the inner unit's commit leaves its write in place for now");
			check(WorkspaceUnit.current() == outer, "and the outer unit is still the open one");
			WorkspaceUnit.write(new File(ws, "c.bin"), "after the fork".getBytes(StandardCharsets.UTF_8));
			throw new IOException("planted: the append failed after the fork");
		} catch (IOException ex) {
			caught = ex;
		}
		check(caught != null, "the outer operation failed");
		check("old a".equals(read(a)) && "old b".equals(read(b)),
				"the outer rollback puts back the inner unit's committed write too - a fork that committed"
				+ " on its own would survive the append it belonged to (a=" + read(a) + ", b=" + read(b) + ")");
		check(!new File(ws, "c.bin").exists(),
				"and a write the outer operation made AFTER the nested unit closed is put back too - a nested"
				+ " unit that ends the outer one's protection leaves everything after it on disk");

		//measured by the 2026-10-12 sweep (WorkspaceUnit 224, dropped, SURVIVED): no nested unit was ever asked to be
		//kept. Dropped, it handed back a change to take back made of files the larger one owns - Undo then put back
		//one step of an operation the owner made as one, and left the rest standing
		String refused;
		try (WorkspaceUnit outer = WorkspaceUnit.begin("Apply map", s)) {
			try (WorkspaceUnit inner = WorkspaceUnit.begin("Wire signs", s)) {
				WorkspaceUnit.write(b, "wired".getBytes(StandardCharsets.UTF_8));
				inner.commitKeeping();
				refused = "the nested unit was kept on its own";
			} catch (IllegalStateException partOfMore) {
				refused = partOfMore.getMessage();
			} catch (RuntimeException wentOn) {
				refused = "it went on to keep the nested unit and failed: " + wentOn;
			}
			outer.commit();
		}
		check(refused.contains("Wire signs is part of a larger change, and only that one can be taken back"),
				"a unit begun inside another cannot be kept on its own - only the outermost can be taken back: " + refused);
	}

	/**
	 * A nested unit that does not finish puts back what it wrote - to what each file held when IT began - though
	 * its caller reports the failure and carries on, and the outer unit commits the rest (F209c).
	 */
	static void aNestedUnitThatFailsPutsBackItsOwn() throws Exception {
		System.out.println("--- a nested unit that does not finish puts back its own writes, though its caller carries on");
		WorkspaceSession s = session("savepoint");
		File ws = s.workspaceDir();
		File a = put(ws, "a.bin", "old a");
		File b = put(ws, "b.bin", "old b");
		File c = new File(ws, "c.bin");
		final String[] memory = {"before"};
		try (WorkspaceUnit outer = WorkspaceUnit.begin("Apply map", s)) {
			WorkspaceUnit.write(a, "new a".getBytes(StandardCharsets.UTF_8));
			try (WorkspaceUnit inner = WorkspaceUnit.begin("Wire signs", s)) {
				WorkspaceUnit.write(a, "a written over by the wiring".getBytes(StandardCharsets.UTF_8));
				WorkspaceUnit.write(b, "half-wired b".getBytes(StandardCharsets.UTF_8));
				WorkspaceUnit.write(c, "made by the wiring".getBytes(StandardCharsets.UTF_8));
				s.addPersist(b);
				memory[0] = "wired";
				WorkspaceUnit.inMemory(() -> memory[0] = "before", () -> memory[0] = "wired");
				throw new IOException("planted: the wiring failed part way");
			} catch (IOException reported) {
				//the painter's Apply says a failed wiring in its result, and the map stays applied
			}
			check(WorkspaceUnit.current() == outer, "control: the outer unit is still the open one");
			outer.commit();
		}
		check("new a".equals(read(a)) && "old b".equals(read(b)) && !c.exists(),
				"a nested unit that failed and was carried past puts back its own writes - each file to what it held when"
				+ " the nested unit began, the outer's own write kept - and the outer commits the rest (a=" + read(a)
				+ ", b=" + read(b) + ", c " + (c.exists() ? "made" : "gone") + ")");
		check(!s.isPersisted(b) && memory[0].equals("before"),
				"and with them the files it listed and what it changed in memory (listed: " + s.isPersisted(b)
				+ ", memory: " + memory[0] + ")");
	}

	static void aFileThatCannotBeCopiedIsNotWritten() throws Exception {
		System.out.println("--- a file the unit cannot copy is refused before it is written");
		WorkspaceSession s = session("uncopyable");
		File ws = s.workspaceDir();
		File a = put(ws, "a.bin", "old a");
		File folder = new File(ws, "not-a-file");
		folder.mkdirs();
		RuntimeException refused = null;
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Test operation", s)) {
			WorkspaceUnit.write(a, "new a".getBytes(StandardCharsets.UTF_8));
			WorkspaceUnit.write(folder, "x".getBytes(StandardCharsets.UTF_8));
			unit.commit();
		} catch (IllegalStateException ex) {
			refused = ex;
		} catch (IOException ex) {
			check(false, "the refusal is the unit's, before the write - not the write's own failure (" + ex + ")");
		}
		check(refused != null && refused.getMessage().contains("could not be put back if the rest failed"),
				"the write is refused, saying why (" + refused + ")");
		check("old a".equals(read(a)), "and the earlier write in the same unit is put back");
	}

	static void whatCannotBePutBackIsSaid() throws Exception {
		System.out.println("--- what a rollback cannot put back is said, attached to the operation's exception");
		WorkspaceSession s = session("unrestorable");
		File ws = s.workspaceDir();
		File sub = new File(ws, "sub");
		sub.mkdirs();
		File a = put(sub, "a.bin", "old a");
		IOException planted = new IOException("planted: the operation failed");
		IOException caught = null;
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Test operation", s)) {
			WorkspaceUnit.write(a, "new a".getBytes(StandardCharsets.UTF_8));
			//the folder holding the file is replaced by a FILE, so putting a.bin back cannot work
			Files.delete(a.toPath());
			Files.delete(sub.toPath());
			Files.write(sub.toPath(), new byte[]{0});
			throw planted;
		} catch (IOException ex) {
			caught = ex;
		}
		check(caught == planted, "the operation's own exception is the one its caller sees");
		String said = "";
		for (Throwable t : caught.getSuppressed()) {
			said += t.getMessage();
		}
		check(said.contains("a.bin") && said.contains("could not be put back"),
				"and the rollback's failure rides on it, naming the file - a rollback that swallowed it would"
				+ " leave a half-written workspace reported as untouched (suppressed: " + said + ")");
		//...and the one sentence every caller shows says it: callers report Bytes.reason, not the
		//suppressed list, and it read only the operation's own message
		String told = ctrmap.util.Bytes.reason(caught);
		check(told.contains("planted: the operation failed") && told.contains("a.bin")
				&& told.contains("could not be put back"),
				"and the reason a caller shows names both the failure and what was not put back: " + told);
		//census L.56: java.nio's file exceptions carry the path as their message, so a missing file was said as a
		//bare path. A real one, read from a file that is not there, not one built to match
		String missing = "";
		try {
			Files.readAllBytes(new File(Scratch.dir("reason"), "not there.bin").toPath());
		} catch (IOException absent) {
			missing = ctrmap.util.Bytes.reason(absent);
		}
		check(missing.contains("not there.bin"), "control: the reason for a missing file names it (" + missing + ")");
		check(missing.contains("there is no such file"), "and says what is wrong with it, not only its name: " + missing);
		String held = ctrmap.util.Bytes.reason(new java.nio.file.AccessDeniedException("x.bin", null, "held by the emulator"));
		check(held.equals("x.bin: held by the emulator"), "a file exception that carries its own reason is said as it was: " + held);
		//and EACH kind of file failure is said in its own words, never another's (the 2026-10-15 shared sweep, Bytes
		//137, negate-if, SURVIVED: only the missing file was ever asked). Inverted, a file in use was "the file system
		//refused (AccessDeniedException)" and a file already there, a folder not empty and a file that is not a folder
		//were each told "access was denied"
		Object[][] kinds = {
			{new java.nio.file.NoSuchFileException("k.bin"), "there is no such file"},
			{new java.nio.file.AccessDeniedException("k.bin"), "access was denied (the file is in use, read-only, or not this account's)"},
			{new java.nio.file.FileAlreadyExistsException("k.bin"), "a file is already there"},
			{new java.nio.file.DirectoryNotEmptyException("k.bin"), "the folder is not empty"},
			{new java.nio.file.NotDirectoryException("k.bin"), "it is not a folder"},
			{new java.nio.file.FileSystemLoopException("k.bin"), "the file system refused (FileSystemLoopException)"},
		};
		List<String> wrong = new java.util.ArrayList<>();
		for (Object[] k : kinds) {
			String sentence = ctrmap.util.Bytes.reason((Throwable) k[0]);
			if (!sentence.equals(k[1] + ": k.bin")) {
				wrong.add(k[0].getClass().getSimpleName() + " said \"" + sentence + "\"");
			}
		}
		check(wrong.isEmpty(), "each kind of file failure is said in its own words (" + kinds.length + " kinds): " + wrong);
	}

	/**
	 * The writes a unit cannot see, counted from the compiled program - and the count only falls.
	 *
	 * <p>F227 AND F011, THE CLASS. An operation is all-or-nothing only if every file it changes is
	 * changed through {@link WorkspaceUnit#write}, {@link WorkspaceUnit#copy} or a container that
	 * reports itself; a raw {@code Files.write} in anything it calls is a file a failure leaves
	 * half-written. The open-time repair was all-or-nothing in its own body and wrote eleven files
	 * through two forkers that went straight to disk. Which raw writes touch the workspace and
	 * which do not (a deploy target, the vault, a log) is not decidable from bytecode, so this is
	 * a ratchet rather than a ban: a new raw write anywhere in the program fails here until it
	 * goes through the unit or is argued for in the commit that raises the number.
	 *
	 * <p>Measured with {@link ClassFileScanner} over build/classes, by this method: 2026-10-07, 33
	 * classes over 58 (class, member) edges once GeometryForker, AreaForker and TextForker wrote
	 * through the unit; then, the same day, 32 over 56 once the zone rename's text file and the
	 * zone save's master row (a RandomAccessFile) went through it with the master table (F013);
	 * then 31 over 55 once the zone clone's two writes did too; then 31 over 54 once the edit list
	 * was written beside itself and moved over, instead of through a FileWriter (F034); then 26
	 * over 51 once the text editor's save went through the unit and the tileset editor (and its
	 * Tile class, now gone), the code.ips save and the resource copy wrote whole files through
	 * Bytes.replaceFile or Files.write instead of their own streams (F026, F254, F131) - the
	 * list this method printed that day is the subject, not a reconstruction of the one before;
	 * then 25 over 50 once the map painter's sign wiring wrote its story text through the unit
	 * (F088), the class gone from the list being TilePainterForm; then 23 over 48 once the shop
	 * and item-icon editors saved code.ips through CodeIps instead of their own Files.write (F152),
	 * the classes gone being ShopEditDialog and ItemEditDialog; then 23 over 47 once removing
	 * added zones dropped its stale extracted copies through WorkspaceSession.dropExtracted instead
	 * of the window's own File.delete loop (F043), the edge gone being CtrmapMainframe's
	 * java/io/File.delete; then 22 over 47 once the bundled-resource copy moved from ResourceAccess
	 * into the Builder that uses it (F130) and the updater's set-aside list went through
	 * Bytes.replaceFile (F120), the class gone being ResourceAccess; then, 2026-10-08, 22 over 46
	 * once an item record was staged in the workspace instead of written into the game folder
	 * through its own RandomAccessFile (F082), the edge gone being ItemTable's
	 * java/io/RandomAccessFile.&lt;init&gt; - the tree before it measured 22 over 47 with that edge;
	 * then, the same day, 21 over 45 once the NPC form's story-text write went through the zone's
	 * history and WorkspaceUnit.write (F209): the list this method printed no longer names
	 * NPCEditForm, whose only raw write was that java/io/FileOutputStream.&lt;init&gt;; then, 2026-10-09, 17 over
	 * 40 once the raw browser's writes went through WorkspaceUnit.write and its converter template through the
	 * scratch writer (F209c, F209d): the list no longer names humaninterface/builder/Builder, whose
	 * java/nio/file/Files.copy and Files.write were its raw writes - battery b17 measured 17 over 40.
	 * Some are the unit's own seams - a container reports its store through {@code aboutToWrite}
	 * before writing raw - so this is an upper bound on the debt, not the debt.
	 */
	private static final int RAW_WRITE_CLASSES = 17;
	private static final int RAW_WRITE_EDGES = 40;

	/** The members that write, move or delete a file without asking anything first. */
	static final String[][] RAW_WRITES = {
		{"java/nio/file/Files", "write"}, {"java/nio/file/Files", "copy"}, {"java/nio/file/Files", "move"},
		{"java/nio/file/Files", "newOutputStream"}, {"java/nio/file/Files", "newBufferedWriter"},
		{"java/nio/file/Files", "delete"}, {"java/nio/file/Files", "deleteIfExists"},
		{"java/io/FileOutputStream", "<init>"}, {"java/io/FileWriter", "<init>"},
		{"java/io/RandomAccessFile", "<init>"}, {"java/io/File", "renameTo"}, {"java/io/File", "delete"},
	};

	static void rawWritesOnlyFall(File classes) throws IOException {
		System.out.println("--- the writes a unit cannot see only fall");
		if (!new File(classes, "ctrmap/WorkspaceUnit.class").isFile()) {
			//never "0 found in nothing"
			check(false, "there is a compiled program to measure - no ctrmap/WorkspaceUnit.class under "
					+ classes.getAbsolutePath() + " (build first, or pass the classes root)");
			return;
		}
		List<ClassFileScanner.ClassFile> app = ClassFileScanner.application(classes);
		java.util.Map<String, java.util.Set<String>> byClass = new java.util.TreeMap<>();
		int edges = 0;
		for (String[] raw : RAW_WRITES) {
			for (ClassFileScanner.Member m : ClassFileScanner.edgesTo(app, raw[0])) {
				if (!m.name.equals(raw[1]) || m.owner.equals("ctrmap/WorkspaceUnit")) {
					continue;
				}
				byClass.computeIfAbsent(m.owner, k -> new java.util.TreeSet<>()).add(raw[0] + "." + raw[1]);
				edges++;
			}
		}
		//THE SCAN READ SOMETHING: the unit itself writes raw, by design, and must be seen doing it
		boolean unitSeen = false;
		for (ClassFileScanner.Member m : ClassFileScanner.edgesTo(app, "java/nio/file/Files")) {
			unitSeen |= m.owner.equals("ctrmap/WorkspaceUnit") && m.name.equals("write");
		}
		check(unitSeen, "the scan sees WorkspaceUnit's own Files.write - a scan that cannot is reading nothing");
		for (String forker : new String[]{"ctrmap/GeometryForker", "ctrmap/AreaForker", "ctrmap/TextForker"}) {
			check(!byClass.containsKey(forker), forker + " writes nothing past the unit"
					+ (byClass.containsKey(forker) ? " - it calls " + byClass.get(forker) : ""));
		}
		System.out.println("  " + byClass.size() + " classes write past the unit, over " + edges + " edges:");
		for (java.util.Map.Entry<String, java.util.Set<String>> e : byClass.entrySet()) {
			System.out.println("    " + e.getKey() + " " + e.getValue());
		}
		check(byClass.size() <= RAW_WRITE_CLASSES && edges <= RAW_WRITE_EDGES,
				"no more classes write past the unit than the " + RAW_WRITE_CLASSES + " recorded, over no more than "
				+ RAW_WRITE_EDGES + " edges (it is " + byClass.size() + " over " + edges + ")");
		check(byClass.size() == RAW_WRITE_CLASSES && edges == RAW_WRITE_EDGES,
				"and the recorded numbers are the measured ones (measured " + byClass.size() + " classes, "
				+ edges + " edges; recorded " + RAW_WRITE_CLASSES + ", " + RAW_WRITE_EDGES + ")");
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok   " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}
}
