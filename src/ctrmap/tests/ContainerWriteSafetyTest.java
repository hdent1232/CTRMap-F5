package ctrmap.tests;

import ctrmap.formats.GameFiles;
import ctrmap.formats.containers.DefaultGamefreakContainer;
import ctrmap.formats.containers.MM;
import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * A Game Freak container is rewritten whole or not at all, read by its own table, and laid out by
 * the subclass that asked for it.
 *
 * <p>FIVE WAYS IT WAS NOT. (1) A store opened the container for writing - which truncates it - and
 * then wrote; a write that failed part way left every subfile gone, not only the one being stored
 * (F048). (2) Two objects over one file each kept the offset table they had read when they opened,
 * so a store through one moved subfiles the other then read and wrote at the old offsets (F234).
 * (3) DefaultGamefreakContainer checked the file against its magic before Java had assigned the
 * magic, so a container of any magic but the five named ones was refused (F050). (4) A new
 * move-model MM was laid out while its type still read as a map matrix, so unpadded, and then read
 * back as padded (F051). (5) A container that could not be created logged and went on, leaving an
 * object over a file that was never written (F049).
 *
 * <p>No game data is needed. The write failure is injected through the GameFiles a container is
 * handed - its aboutToWrite runs after the new bytes are built and before any is written - so it
 * fails the same way on every system.
 */
public class ContainerWriteSafetyTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		aStoreThatFailsLeavesTheContainerWhole();
		twoObjectsOverOneFileReadTheFilesOwnTable();
		aContainerOfAnyMagicOpens();
		aNewMoveModelContainerIsLaidOutPadded();
		aContainerThatCannotBeCreatedIsRefused();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** A GameFiles that can be told to fail the next write it hears about. */
	static final class Files2 implements GameFiles {
		boolean failNext;

		@Override
		public void aboutToWrite(File f) {
			if (failNext) {
				failNext = false;
				throw new IllegalStateException("planted: the disk refused " + f.getName());
			}
		}

		@Override
		public File staged(ArchiveType type, int entry) {
			return null;
		}

		@Override
		public void edited(File f) {
		}

		/** It stages nothing ({@link #staged} is null), so no entry has a staged copy. */
		@Override
		public File editedEntry(ArchiveType type, int entry) {
			return null;
		}

		/** It keeps no list, so it cannot tell: an edit, which nothing may replace. */
		@Override
		public boolean isEdit(File f) {
			return true;
		}

		@Override
		public File scratch() {
			return null;
		}

		@Override
		public ctrmap.formats.GameCache cache() {
			return new ctrmap.formats.GameCache();   //nothing here is read twice
		}

		@Override
		public java.util.List<Integer> zonesNamingArea(int area, int except) throws IOException {
			return ctrmap.MasterTable.read(this).using(ctrmap.ZoneResource.AREA, area, except);
		}

		@Override
		public GARC archive(ArchiveType type) {
			return null;
		}

		@Override
		public File archiveFile(ArchiveType type) {
			return null;
		}

		@Override
		public GameProfile profile() {
			return null;
		}

		@Override
		public GameProfile.Variant variant() {
			return null;
		}

		@Override
		public File pristine() {
			return null;
		}

		@Override
		public File durable(String name) {
			return null;
		}
	}

	static void aStoreThatFailsLeavesTheContainerWhole() throws Exception {
		System.out.println("--- a store that fails leaves the container whole (F048)");
		Files2 files = new Files2();
		File f = new File(Scratch.dir("ctrmap_cws_fail"), "3");
		MM mm = new MM(f, 3, MM.MM_MAP_MATRIX, files);
		mm.storeFile(0, new byte[]{1, 1, 1, 1});
		mm.storeFile(1, new byte[]{2, 2, 2, 2});
		mm.storeFile(2, new byte[]{3, 3, 3, 3});
		byte[] before = Files.readAllBytes(f.toPath());
		files.failNext = true;
		RuntimeException refused = null;
		try {
			mm.storeFile(0, new byte[]{9, 9, 9, 9, 9, 9, 9, 9});
		} catch (RuntimeException ex) {
			refused = ex;
		}
		check(refused != null, "the failed store says so rather than returning (" + refused + ")");
		check(Arrays.equals(before, Files.readAllBytes(f.toPath())),
				"the container is byte-identical to before the store - a store that truncated the file"
				+ " first leaves every subfile gone, not only the one it was storing");
		check(Arrays.equals(new byte[]{2, 2, 2, 2}, mm.getFile(1)) && Arrays.equals(new byte[]{3, 3, 3, 3}, mm.getFile(2)),
				"and the subfiles it was not storing read back as they were");
		mm.storeFile(0, new byte[]{7, 7, 7, 7, 7, 7});
		check(Arrays.equals(new byte[]{7, 7, 7, 7, 7, 7}, mm.getFile(0)) && Arrays.equals(new byte[]{3, 3, 3, 3}, mm.getFile(2)),
				"and the next store works from the file as it really is");
	}

	static void twoObjectsOverOneFileReadTheFilesOwnTable() throws Exception {
		System.out.println("--- two objects over one file each read the file's own table (F234)");
		Files2 files = new Files2();
		File f = new File(Scratch.dir("ctrmap_cws_two"), "4");
		MM a = new MM(f, 3, MM.MM_MAP_MATRIX, files);
		a.storeFile(0, new byte[]{1, 1, 1, 1});
		a.storeFile(1, new byte[]{2, 2, 2, 2});
		a.storeFile(2, new byte[]{3, 3, 3, 3});
		MM b = new MM(f, files);
		a.storeFile(0, new byte[]{8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8});
		//B STORES BEFORE IT READS ANYTHING: a read re-reads the table as a side effect, so a check
		//that read through B first refreshed it and could not see a stale store (2026-10-07)
		b.storeFile(2, new byte[]{5, 5});
		check(Arrays.equals(new byte[]{2, 2, 2, 2}, b.getFile(1)),
				"the second object reads subfile 1 where the file now holds it (read "
				+ Arrays.toString(b.getFile(1)) + ")");
		check(Arrays.equals(new byte[]{8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8}, a.getFile(0)),
				"a store through the second object leaves what the first stored intact - a store at the"
				+ " offsets it read when it opened overwrites the first object's subfile (read "
				+ Arrays.toString(a.getFile(0)) + ")");
		check(Arrays.equals(new byte[]{2, 2, 2, 2}, a.getFile(1)) && Arrays.equals(new byte[]{5, 5}, a.getFile(2)),
				"and every subfile reads back as the last store left it");
	}

	static void aContainerOfAnyMagicOpens() throws Exception {
		System.out.println("--- a container of any magic opens (F050)");
		File f = new File(Scratch.dir("ctrmap_cws_magic"), "5");
		short magic = 0x5858;
		ByteBuffer bb = ByteBuffer.allocate(0x84).order(ByteOrder.LITTLE_ENDIAN);
		bb.putShort(magic).putShort((short) 1).putInt(0x80).putInt(0x84);
		bb.position(0x80);
		bb.put(new byte[]{4, 3, 2, 1});
		Files.write(f.toPath(), bb.array());
		DefaultGamefreakContainer c = null;
		RuntimeException refused = null;
		try {
			c = new DefaultGamefreakContainer(f, magic, new Files2());
		} catch (RuntimeException ex) {
			refused = ex;
		}
		check(c != null, "a container whose magic is not one of the five named ones opens - it was refused"
				+ " because its magic was checked before it was assigned (" + refused + ")");
		check(c != null && Arrays.equals(new byte[]{4, 3, 2, 1}, c.getFile(0)), "and reads its subfile");
	}

	static void aNewMoveModelContainerIsLaidOutPadded() throws Exception {
		System.out.println("--- a new move-model container is laid out padded (F051)");
		File f = new File(Scratch.dir("ctrmap_cws_pad"), "6");
		new MM(f, 3, MM.MM_MOVE_MODEL, new Files2());
		ByteBuffer bb = ByteBuffer.wrap(Files.readAllBytes(f.toPath())).order(ByteOrder.LITTLE_ENDIAN);
		int first = bb.getInt(4);
		check(first == 0x80, "its first subfile starts on the 0x80 padding a move model is read with"
				+ " (starts at 0x" + Integer.toHexString(first) + ") - laid out as a map matrix, it is read back"
				+ " at offsets that are not there");
	}

	static void aContainerThatCannotBeCreatedIsRefused() throws Exception {
		System.out.println("--- a container that cannot be created is refused (F049)");
		File nowhere = new File(new File(Scratch.dir("ctrmap_cws_create"), "missing-folder"), "7");
		MM made = null;
		RuntimeException refused = null;
		try {
			made = new MM(nowhere, 3, MM.MM_MAP_MATRIX, new Files2());
		} catch (RuntimeException ex) {
			refused = ex;
		}
		check(made == null && refused != null && String.valueOf(refused.getMessage()).contains("could not create"),
				"the constructor refuses, naming the file - an object over a file never written is stored"
				+ " into and reported as a container that exists (" + refused + ")");
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok   " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}
}
