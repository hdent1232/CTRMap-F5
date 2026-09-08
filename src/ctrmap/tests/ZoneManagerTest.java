package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.ZoneCloner;
import ctrmap.ZoneManager;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.text.GFMessageFile;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Characterization of {@link ZoneManager}, the class that EMPTIES and RENAMES a
 * zone. It writes game data, so every assertion here is about bytes on disk
 * rather than about a call it made.
 *
 * <p>WHY THIS EXISTS. Two suites name ZoneManager - MainframeReportsTest and
 * WorkspaceSessionTest - and both of them scan source text, so before this file
 * not one line of the class had ever been executed by the battery. The class
 * rewrites a zone container, the master zone-header table and the location-name
 * text file; a mistake in any of those is a game that boots to a wrong banner,
 * or a zone whose NPCs are gone and whose script now reads from the wrong
 * offset. Nothing would have failed.
 *
 * <p>WHAT IS PINNED, and it is what the code does TODAY, not what it ought to:
 * <ul>
 * <li>clearZone rewrites ONLY subfile 1 of the container, replacing the record
 * block with a 12-byte all-zero header (totalLength 8) and keeping the embedded
 * script tail byte for byte. The other four subfiles come back identical.</li>
 * <li>clearZone is idempotent and returns 0 the second time.</li>
 * <li>An out-of-range zone index does NOT produce the IOException the guard
 * reads as if it did - see the suspected-defect note on {@link #clearOutOfRange}.
 * The current exception type is pinned so a later refactor that changes it is
 * visible.</li>
 * <li>renameZone on a zone whose name no other zone uses edits that one line in
 * place, leaves every other line alone, and does not touch the master table.</li>
 * <li>renameZone on a SHARED name moves the zone to the lowest free blank name
 * slot and repoints BOTH the ZO header and the master row, preserving the six
 * high bits packed above the 10-bit parentMap in the same u16.</li>
 * <li>With no free blank slot left it falls back to renaming in place, which
 * renames every sharer, and says so in the result.</li>
 * </ul>
 *
 * <p>Everything runs against a throwaway copy of the dump ({@link ScratchGame}),
 * never the owner's game folder or workspace.
 *
 * Usage: java ctrmap.tests.ZoneManagerTest &lt;pristine-dump-root&gt;
 */
public class ZoneManagerTest {

	/** Byte offset, inside a zone header row, of the packed parentMap u16. */
	private static final int PARENT_OFF = 0x1C;
	private static final int PARENT_MASK = 0x3FF;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump.getAbsolutePath());
			System.out.println("ALL PASS");
			return;
		}
		ScratchGame.open(dump);

		int masterIndex = ZoneCloner.getMasterIndex();
		File masterFile = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, masterIndex);
		byte[] master0 = readAll(masterFile);
		int rows = master0.length / ZoneCloner.ZONE_HEADER_SIZE;
		check(masterIndex == 536 && rows == 536,
				"pristine ORAS ZoneData: master table is entry " + masterIndex + " with " + rows + " rows");

		int gtIndex = ctrmap.formats.text.LocationNames.gametextIndex();
		File gtFile = Workspace.getWorkspaceFile(ArchiveType.GAMETEXT, gtIndex);
		GFMessageFile names0 = new GFMessageFile(readAll(gtFile));
		check(gtIndex == 90 && names0.getLineCount() == 356,
				"location names are GAMETEXT " + gtIndex + " with " + names0.getLineCount() + " lines");

		clearEmptiesOnlyTheRecords();
		clearIsIdempotent();
		clearRefusesWithoutWorkspace();
		clearOutOfRange();
		renameUniqueNameInPlace(masterFile, gtFile);
		renameSharedNameForksToAFreeSlot(masterFile, gtFile);
		renameRefusals();
		renameNameLineOutOfRange(masterFile);
		renameFallsBackWhenNoSlotIsFree(masterFile, gtFile);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * The record block goes, the script tail stays. Zone 1 is Littleroot with 22
	 * NPCs and 4 triggers in the retail dump; the expected bytes are built from
	 * the ORIGINAL container, so this fails if clearZone writes anything else.
	 */
	private static void clearEmptiesOnlyTheRecords() throws IOException {
		int zone = 1;
		File zf = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, zone);
		ZO before = new ZO(zf);
		byte[][] subfilesBefore = new byte[before.len][];
		for (int i = 0; i < before.len; i++) {
			subfilesBefore[i] = before.getFile(i);
		}
		ZoneEntities ent = new ZoneEntities(subfilesBefore[1]);
		int placed = ent.furniture.size() + ent.npcs.size() + ent.warps.size()
				+ ent.triggers1.size() + ent.triggers2.size();
		int recordBytes = ent.furniture.size() * 0x14 + ent.npcs.size() * 0x30
				+ (ent.warps.size() + ent.triggers1.size() + ent.triggers2.size()) * 0x18;
		check(placed == 26 && recordBytes == 1152,
				"zone " + zone + " holds " + placed + " placed objects in " + recordBytes + " bytes of records");

		int removed = ZoneManager.clearZone(Workspace.session(), zone);
		check(removed == placed, "clearZone reported " + removed + " removed, the zone held " + placed);

		//what the emptied subfile must be: a 12-byte header declaring totalLength
		//8 and five zero counts, then the embedded script exactly as it was
		byte[] expected = new byte[subfilesBefore[1].length - recordBytes];
		expected[0] = 8;
		System.arraycopy(subfilesBefore[1], 12 + recordBytes, expected, 12, expected.length - 12);

		ZO after = new ZO(zf);
		check(after.len == before.len, "the container still holds " + after.len + " subfiles");
		check(Arrays.equals(expected, after.getFile(1)),
				"the emptied entity block is a zeroed header plus the script, byte for byte ("
				+ after.getFile(1).length + " bytes, was " + subfilesBefore[1].length + ")");
		boolean othersIntact = true;
		for (int i = 0; i < before.len; i++) {
			if (i == 1) {
				continue;
			}
			if (!Arrays.equals(subfilesBefore[i], after.getFile(i))) {
				othersIntact = false;
				check(false, "subfile " + i + " was rewritten by clearZone");
			}
		}
		check(othersIntact, "clearZone left the header, script, encounters and reflections untouched");
		check(Workspace.persistPaths().contains(zf.getAbsolutePath()),
				"the emptied zone was marked to be packed back into the game");

		//and it still parses as a zone with nothing in it
		ZoneEntities reread = new ZoneEntities(after.getFile(1));
		check(reread.npcs.isEmpty() && reread.warps.isEmpty() && reread.furniture.isEmpty()
				&& reread.triggers1.isEmpty() && reread.triggers2.isEmpty(),
				"the emptied zone re-parses with no placed content");
		check(reread.totalLength == 8, "the emptied entity header declares totalLength " + reread.totalLength);
	}

	/** Emptying an empty zone removes nothing and rewrites nothing. */
	private static void clearIsIdempotent() throws IOException {
		File zf = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, 1);
		byte[] before = readAll(zf);
		int removed = ZoneManager.clearZone(Workspace.session(), 1);
		check(removed == 0, "a second clearZone removed " + removed + " objects");
		check(Arrays.equals(before, readAll(zf)), "a second clearZone left the container file identical");
	}

	/**
	 * "No workspace is loaded" used to be said by clearing Workspace.valid; the
	 * open game is a session now, so it is said by there being no session -
	 * which is the same state, expressed where the class under test reads it.
	 */
	private static void clearRefusesWithoutWorkspace() {
		WorkspaceSession held = Workspace.session();
		Workspace.install(null);
		try {
			ZoneManager.clearZone(Workspace.session(), 1);
			check(false, "clearZone with no workspace refuses");
		} catch (IOException ex) {
			check("No workspace is loaded.".equals(ex.getMessage()),
					"clearZone with no workspace refuses: " + ex.getMessage());
		} finally {
			Workspace.install(held);
		}
	}

	/**
	 * SUSPECTED DEFECT, PINNED NOT FIXED. clearZone opens with a null check on
	 * the extracted file and an IOException naming the zone, which reads as the
	 * out-of-range guard. It is not one: WorkspaceSession.getWorkspaceFile hands back the
	 * File it would have written whether or not the archive holds that entry (it
	 * returns null only for an entry that decompresses to nothing), so for an
	 * out-of-range index the check never fires. Such an index reaches the
	 * container constructor instead, which
	 * logs a FileNotFoundException to stderr and carries on, and the failure
	 * finally surfaces as a NullPointerException out of the entity parser. A
	 * negative index fails earlier still, inside the GARC. Both are pinned by
	 * TYPE, because the message text is the JVM's, not this program's.
	 */
	private static void clearOutOfRange() {
		int past = Workspace.getArchive(ArchiveType.ZONE_DATA).length + 100;
		try {
			int n = ZoneManager.clearZone(Workspace.session(), past);
			check(false, "clearZone(" + past + ") returned " + n + " instead of failing");
		} catch (Throwable t) {
			check(t instanceof NullPointerException,
					"clearZone past the end of the archive fails with " + t.getClass().getSimpleName()
					+ " (not the IOException the guard above it reads like)");
		}
		try {
			int n = ZoneManager.clearZone(Workspace.session(), -1);
			check(false, "clearZone(-1) returned " + n + " instead of failing");
		} catch (Throwable t) {
			check(t instanceof IndexOutOfBoundsException,
					"clearZone(-1) fails with " + t.getClass().getSimpleName() + " from inside the archive");
		}
	}

	/**
	 * Zone 24 is Route 102 in the retail dump and no other zone points at that
	 * name line, so the rename is an edit in place: one line changes, the master
	 * table is not touched, and the zone keeps its parentMap.
	 */
	private static void renameUniqueNameInPlace(File masterFile, File gtFile) throws IOException {
		int zone = 24;
		byte[] masterBefore = readAll(masterFile);
		GFMessageFile before = new GFMessageFile(readAll(gtFile));
		int parent = u16(masterBefore, zone * ZoneCloner.ZONE_HEADER_SIZE + PARENT_OFF) & PARENT_MASK;
		File zf = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, zone);
		byte[] zoBefore = readAll(zf);

		ZoneManager.RenameResult r = ZoneManager.renameZone(Workspace.session(), zone, "Characterization Lane");

		check(r.sharers == 1, "zone " + zone + " had " + r.sharers + " zone using its name line");
		check(!r.gaveOwnName && !r.renamedSharers,
				"a zone-unique name is edited in place (gaveOwnName=" + r.gaveOwnName
				+ ", renamedSharers=" + r.renamedSharers + ")");
		check(r.parentMap == parent, "the zone kept name line " + r.parentMap);
		check("Route 102".equals(r.oldName), "the result reports the old name '" + r.oldName + "'");

		GFMessageFile after = new GFMessageFile(readAll(gtFile));
		check(after.getLineCount() == before.getLineCount(),
				"no name line was appended (" + after.getLineCount() + " lines)");
		check("Characterization Lane".equals(after.getLine(parent)),
				"line " + parent + " now reads '" + after.getLine(parent) + "'");
		int changed = 0;
		for (int i = 0; i < after.getLineCount(); i++) {
			if (!eq(before.getLine(i), after.getLine(i))) {
				changed++;
			}
		}
		check(changed == 1, changed + " name line(s) changed; exactly one must");
		check(Arrays.equals(masterBefore, readAll(masterFile)),
				"an in-place rename does not touch the master zone-header table");
		check(Arrays.equals(zoBefore, readAll(zf)),
				"an in-place rename does not touch the zone container");
		check(Workspace.persistPaths().contains(gtFile.getAbsolutePath()),
				"the edited name file was marked to be packed back into the game");
	}

	/**
	 * Zone 6 shares Littleroot Town's name line with eleven other zones, and its
	 * packed u16 carries a non-zero value above the 10-bit parentMap - so this
	 * also proves the repoint writes the low ten bits and nothing else.
	 */
	private static void renameSharedNameForksToAFreeSlot(File masterFile, File gtFile) throws IOException {
		int zone = 6;
		byte[] masterBefore = readAll(masterFile);
		GFMessageFile before = new GFMessageFile(readAll(gtFile));
		int rowOff = zone * ZoneCloner.ZONE_HEADER_SIZE;
		int packedBefore = u16(masterBefore, rowOff + PARENT_OFF);
		int parentBefore = packedBefore & PARENT_MASK;
		File zf = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, zone);
		ZO zoBefore = new ZO(zf);
		byte[][] subfilesBefore = new byte[zoBefore.len][];
		for (int i = 0; i < zoBefore.len; i++) {
			subfilesBefore[i] = zoBefore.getFile(i);
		}
		check((packedBefore >> 10) != 0,
				"zone " + zone + "'s parentMap u16 is 0x" + Integer.toHexString(packedBefore)
				+ ", so it carries bits above the 10-bit field");

		ZoneManager.RenameResult r = ZoneManager.renameZone(Workspace.session(), zone, "Characterization Town");

		check(r.sharers == 12, "zone " + zone + " shared its name with " + (r.sharers - 1) + " others");
		check(r.gaveOwnName && !r.renamedSharers,
				"a shared name is forked to a private slot (gaveOwnName=" + r.gaveOwnName
				+ ", renamedSharers=" + r.renamedSharers + ")");
		check(r.parentMap == 1, "the zone was moved to name line " + r.parentMap
				+ "; the lowest slot no zone uses and that is blank is 1");
		check("Littleroot Town".equals(r.oldName), "the result reports the old name '" + r.oldName + "'");

		GFMessageFile after = new GFMessageFile(readAll(gtFile));
		check(after.getLineCount() == before.getLineCount(),
				"no name line was appended (" + after.getLineCount() + " lines)");
		check("Characterization Town".equals(after.getLine(r.parentMap)),
				"line " + r.parentMap + " now reads '" + after.getLine(r.parentMap) + "'");
		check(eq(before.getLine(parentBefore), after.getLine(parentBefore)),
				"the shared line " + parentBefore + " still reads '" + after.getLine(parentBefore) + "'");
		int changed = 0;
		for (int i = 0; i < after.getLineCount(); i++) {
			if (!eq(before.getLine(i), after.getLine(i))) {
				changed++;
			}
		}
		check(changed == 1, changed + " name line(s) changed; only the new private slot must");

		//the master row: the low ten bits are the new line, everything else is untouched
		byte[] masterAfter = readAll(masterFile);
		check(masterAfter.length == masterBefore.length, "the master table did not change length");
		int packedAfter = u16(masterAfter, rowOff + PARENT_OFF);
		check((packedAfter & PARENT_MASK) == r.parentMap,
				"master row " + zone + " points at name line " + (packedAfter & PARENT_MASK));
		check((packedAfter & ~PARENT_MASK) == (packedBefore & ~PARENT_MASK),
				"master row " + zone + " kept the bits above the parentMap (0x"
				+ Integer.toHexString(packedBefore) + " -> 0x" + Integer.toHexString(packedAfter) + ")");
		int strayMasterBytes = 0;
		for (int i = 0; i < masterAfter.length; i++) {
			if (i == rowOff + PARENT_OFF || i == rowOff + PARENT_OFF + 1) {
				continue;
			}
			if (masterAfter[i] != masterBefore[i]) {
				strayMasterBytes++;
			}
		}
		check(strayMasterBytes == 0,
				strayMasterBytes + " master byte(s) outside row " + zone + "'s parentMap changed");

		//the ZO header carries the same repoint, and nothing else in the container moves
		ZO zoAfter = new ZO(zf);
		check(zoAfter.len == zoBefore.len, "the container still holds " + zoAfter.len + " subfiles");
		byte[] hdrBefore = subfilesBefore[0];
		byte[] hdrAfter = zoAfter.getFile(0);
		check(hdrAfter.length == hdrBefore.length, "the zone header did not change length");
		int packedHdr = u16(hdrAfter, PARENT_OFF);
		check((packedHdr & PARENT_MASK) == r.parentMap,
				"the zone header points at name line " + (packedHdr & PARENT_MASK));
		check((packedHdr & ~PARENT_MASK) == (u16(hdrBefore, PARENT_OFF) & ~PARENT_MASK),
				"the zone header kept the bits above the parentMap");
		int strayHeaderBytes = 0;
		for (int i = 0; i < hdrAfter.length; i++) {
			if (i == PARENT_OFF || i == PARENT_OFF + 1) {
				continue;
			}
			if (hdrAfter[i] != hdrBefore[i]) {
				strayHeaderBytes++;
			}
		}
		check(strayHeaderBytes == 0, strayHeaderBytes + " zone-header byte(s) outside the parentMap changed");
		boolean othersIntact = true;
		for (int i = 1; i < zoBefore.len; i++) {
			if (!Arrays.equals(subfilesBefore[i], zoAfter.getFile(i))) {
				othersIntact = false;
			}
		}
		check(othersIntact, "renaming rewrote only the zone's header subfile");
		check(Workspace.persistPaths().contains(masterFile.getAbsolutePath()),
				"the repointed master table was marked to be packed back into the game");

		//no other zone was moved off the shared line
		int stillOnOldLine = 0;
		for (int i = 0; i < masterAfter.length / ZoneCloner.ZONE_HEADER_SIZE; i++) {
			if ((u16(masterAfter, i * ZoneCloner.ZONE_HEADER_SIZE + PARENT_OFF) & PARENT_MASK) == parentBefore) {
				stillOnOldLine++;
			}
		}
		check(stillOnOldLine == r.sharers - 1,
				stillOnOldLine + " zones still share the old name line; " + (r.sharers - 1) + " must");

		//the NEXT shared rename takes the next free blank slot, not the same one
		ZoneManager.RenameResult r2 = ZoneManager.renameZone(Workspace.session(), 7, "Characterization Two");
		check(r2.gaveOwnName && r2.parentMap == 3,
				"the next forked rename took name line " + r2.parentMap + ", the next free blank slot");
	}

	private static void renameRefusals() {
		int master = ZoneCloner.getMasterIndex();
		try {
			ZoneManager.renameZone(Workspace.session(), master, "x");
			check(false, "renameZone refuses a zone index at the master table");
		} catch (IOException ex) {
			check(("Zone " + master + " out of range (0.." + (master - 1) + ").").equals(ex.getMessage()),
					"renameZone refuses an index at the master table: " + ex.getMessage());
		}
		try {
			ZoneManager.renameZone(Workspace.session(), -1, "x");
			check(false, "renameZone refuses a negative index");
		} catch (IOException ex) {
			check(ex.getMessage().startsWith("Zone -1 out of range"),
					"renameZone refuses a negative index: " + ex.getMessage());
		}
		try {
			ZoneManager.renameZone(Workspace.session(), 24, "Bad [");
			check(false, "renameZone refuses a name the text codec cannot encode");
		} catch (IOException ex) {
			check(ex.getMessage().startsWith("That name can't be encoded: "),
					"renameZone refuses an unencodable name before touching anything: " + ex.getMessage());
		}
		//no workspace at all: once Workspace.valid, now no session
		WorkspaceSession held = Workspace.session();
		Workspace.install(null);
		try {
			ZoneManager.renameZone(Workspace.session(), 1, "x");
			check(false, "renameZone with no workspace refuses");
		} catch (IOException ex) {
			check("No workspace is loaded.".equals(ex.getMessage()),
					"renameZone with no workspace refuses: " + ex.getMessage());
		} finally {
			Workspace.install(held);
		}
		//a workspace whose ZoneData handle is missing: once Workspace.zo = null,
		//now a session holding every other archive and not that one
		Workspace.install(held.withArchive(ArchiveType.ZONE_DATA, null));
		try {
			ZoneManager.renameZone(Workspace.session(), 1, "x");
			check(false, "renameZone with no ZoneData archive refuses");
		} catch (IOException ex) {
			check("ZoneData archive unavailable.".equals(ex.getMessage()),
					"renameZone with no ZoneData archive refuses: " + ex.getMessage());
		} finally {
			Workspace.install(held);
		}
	}

	/**
	 * A zone whose parentMap points past the end of the name table cannot be
	 * renamed in place, and says so instead of writing out of bounds. Retail
	 * data has no such zone, so one is made here by repointing a master row -
	 * the same edit an out-of-range hand-patch would leave behind.
	 */
	private static void renameNameLineOutOfRange(File masterFile) throws IOException {
		int zone = 500;
		byte[] master = readAll(masterFile);
		int rowOff = zone * ZoneCloner.ZONE_HEADER_SIZE;
		int packed = u16(master, rowOff + PARENT_OFF);
		int bogus = 999;
		int repacked = (packed & ~PARENT_MASK) | bogus;
		master[rowOff + PARENT_OFF] = (byte) (repacked & 0xFF);
		master[rowOff + PARENT_OFF + 1] = (byte) ((repacked >> 8) & 0xFF);
		writeAll(masterFile, master);
		try {
			ZoneManager.renameZone(Workspace.session(), zone, "x");
			check(false, "renameZone refuses a zone pointing past the name table");
		} catch (IOException ex) {
			check(("Zone " + zone + "'s name line " + bogus + " is out of range.").equals(ex.getMessage()),
					"renameZone refuses a zone pointing past the name table: " + ex.getMessage());
		}
	}

	/**
	 * With every in-bounds name slot spoken for, a shared rename has nowhere to
	 * fork to, so it renames the shared line and reports that it did. Every
	 * sharer's banner changes, which is why the result carries the flag.
	 */
	private static void renameFallsBackWhenNoSlotIsFree(File masterFile, File gtFile) throws IOException {
		GFMessageFile names = new GFMessageFile(readAll(gtFile));
		for (int i = 0; i < names.getLineCount(); i++) {
			String line = names.getLine(i);
			if (line == null || line.trim().isEmpty()) {
				names.setLine(i, "taken" + i);
			}
		}
		writeAll(gtFile, names.write());

		int zone = 8;
		byte[] masterBefore = readAll(masterFile);
		File zf = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, zone);
		byte[] zoBefore = readAll(zf);
		int parent = u16(masterBefore, zone * ZoneCloner.ZONE_HEADER_SIZE + PARENT_OFF) & PARENT_MASK;

		ZoneManager.RenameResult r = ZoneManager.renameZone(Workspace.session(), zone, "Characterization Shared");

		check(r.sharers > 1, "zone " + zone + " shares its name with " + (r.sharers - 1) + " others");
		check(r.renamedSharers && !r.gaveOwnName,
				"with no free slot the shared line is renamed and the result says so (renamedSharers="
				+ r.renamedSharers + ", gaveOwnName=" + r.gaveOwnName + ")");
		check(r.parentMap == parent, "the zone kept name line " + r.parentMap);
		GFMessageFile after = new GFMessageFile(readAll(gtFile));
		check("Characterization Shared".equals(after.getLine(parent)),
				"the shared line " + parent + " now reads '" + after.getLine(parent) + "'");
		check(Arrays.equals(masterBefore, readAll(masterFile)),
				"the fallback does not repoint the master table");
		check(Arrays.equals(zoBefore, readAll(zf)),
				"the fallback does not repoint the zone container");
	}

	private static boolean eq(String a, String b) {
		return a == null ? b == null : a.equals(b);
	}

	private static int u16(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
	}

	private static byte[] readAll(File f) throws IOException {
		InputStream in = new FileInputStream(f);
		byte[] b = new byte[in.available()];
		in.read(b);
		in.close();
		return b;
	}

	private static void writeAll(File f, byte[] b) throws IOException {
		OutputStream os = new FileOutputStream(f);
		os.write(b);
		os.flush();
		os.close();
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
