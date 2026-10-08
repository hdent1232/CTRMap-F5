package ctrmap;

import ctrmap.formats.GameFiles;
import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import ctrmap.util.LittleEndian;

/**
 * The master zone-header table - the copy of every zone's header the game itself reads - read,
 * asked and written in one place.
 *
 * <p>ONE READER, ONE POLICY (F013). Reading it was written out eleven times with four answers to
 * where it is and what to do when it is the wrong size: two sharer counts fell back to the
 * archive's own copy and then answered "1 sharer" when that was wrong too, the two repair passes
 * refused, one helper had no null or size check at all - a missing file was a
 * NullPointerException its caller reported as the refusal "null" - the rename read it with no
 * check either, the texture carry's guard took any multiple of a row as the table, and saving a
 * zone wrote its row into the file through a RandomAccessFile at whatever offset the zone's index
 * gave, past the end of a short table as readily as into it. Counting who shares a resource was
 * written four times over it.
 *
 * <p>THE POLICY ({@link #choose}): the workspace's file when it is the right size; the archive's
 * own copy when the workspace's is a stale extraction rather than a staged edit, so no edit is
 * ever dropped; a refusal, in words, when a staged edit is the wrong size, or nothing readable is
 * left. Never a guessed answer.
 *
 * <p>Not: ZoneTables - it says where the table is in the archive and how many zones there are,
 * and this asks it; it reads no rows. Not ZoneCloner.patchMasterRow - that builds a row for a new
 * zone; this reads and repoints the rows that exist.
 */
public final class MasterTable {

	/** One zone's row: one zone header. */
	public static final int ROW = ctrmap.formats.zone.ZoneHeader.SIZE;

	private final GameFiles files;
	private final File file;
	private final byte[] rows;
	private final int zones;

	private MasterTable(GameFiles files, File file, byte[] rows, int zones) {
		this.files = files;
		this.file = file;
		this.rows = rows;
		this.zones = zones;
	}

	/**
	 * The master table of the game {@code files} holds.
	 *
	 * @throws IOException naming what is wrong, by {@link #choose}'s policy - never a table guessed
	 */
	public static MasterTable read(GameFiles files) throws IOException {
		if (files == null) {
			throw new IOException("No workspace is loaded, so there is no master zone-header table.");
		}
		GARC zo = files.archive(ArchiveType.ZONE_DATA);
		//the handed game's own profile, never the global's (F019): the table sits right after the zones
		int zones = ZoneTables.zoneCount(zo, files.profile());
		File f = files.staged(ArchiveType.ZONE_DATA, zones);
		if (f == null) {
			throw new IOException("The master zone-header table could not be extracted from the workspace.");
		}
		byte[] staged = null;
		if (f.isFile()) {
			try {
				staged = Files.readAllBytes(f.toPath());
			} catch (IOException cannot) {
				throw new IOException("The master zone-header table at " + f + " could not be read: "
						+ ctrmap.util.Bytes.reason(cannot), cannot);
			}
		}
		byte[] archive = zones < zo.length ? zo.getDecompressedEntry(zones) : null;
		return new MasterTable(files, f, choose(staged, files.isEdit(f), archive, zones), zones);
	}

	/**
	 * Rows read from somewhere other than a workspace, to be asked and never written: {@link #write}
	 * refuses.
	 *
	 * @throws IOException when the rows are not {@code zones} rows long
	 */
	public static MasterTable detached(byte[] rows, int zones) throws IOException {
		if (rows == null || zones < 0 || rows.length != zones * ROW) {
			throw new IOException("A master zone-header table for " + zones + " zones is " + (zones * ROW)
					+ " bytes, not " + (rows == null ? "absent" : rows.length + ""));
		}
		return new MasterTable(null, null, rows, zones);
	}

	/**
	 * The policy, separated so it can be asked with no game: which bytes are the table.
	 *
	 * @param workspace the workspace file's bytes, or null when there is no such file
	 * @param staged whether that file is a staged edit, rather than a plain extraction
	 * @param archive the archive's own entry, or null
	 * @throws IOException when no right-sized table is left that drops no staged edit
	 */
	public static byte[] choose(byte[] workspace, boolean staged, byte[] archive, int zones) throws IOException {
		int want = zones * ROW;
		if (workspace != null && workspace.length == want) {
			return workspace;
		}
		if (staged) {
			throw new IOException("The staged master zone-header table is " + (workspace == null ? 0 : workspace.length)
					+ " bytes, not the " + want + " this game's " + zones + " zones need - it is an edit, so it is"
					+ " not replaced with the archive's copy. Pack or remove it before this can run.");
		}
		if (archive != null && archive.length == want) {
			return archive.clone();
		}
		throw new IOException("Neither the workspace's master zone-header table nor the archive's is the " + want
				+ " bytes this game's " + zones + " zones need.");
	}

	/** How many zones the table has a row for. */
	public int zones() {
		return zones;
	}

	/** The rows themselves, for a caller that repoints several before one {@link #write}. */
	public byte[] bytes() {
		return rows;
	}

	/** The id of {@code res} in zone {@code zone}'s row. */
	public int id(ZoneResource res, int zone) {
		requireZone(zone);
		return res.idInMasterRow(rows, zone);
	}

	/** Points zone {@code zone}'s row at id {@code id} of {@code res}; nothing is written until {@link #write}. */
	public void set(ZoneResource res, int zone, int id) {
		requireZone(zone);
		int at = zone * ROW + res.headerOffset;
		LittleEndian.putU16(rows, at, id);
	}

	/** A copy of zone {@code zone}'s whole row. */
	public byte[] row(int zone) {
		requireZone(zone);
		return java.util.Arrays.copyOfRange(rows, zone * ROW, zone * ROW + ROW);
	}

	/**
	 * Replaces zone {@code zone}'s whole row; nothing is written until {@link #write}.
	 *
	 * @return whether the row changed
	 * @throws IllegalArgumentException when {@code row} is not one row long
	 */
	public boolean setRow(int zone, byte[] row) {
		requireZone(zone);
		if (row == null || row.length != ROW) {
			throw new IllegalArgumentException("A zone header is " + ROW + " bytes, not "
					+ (row == null ? "absent" : row.length + ""));
		}
		if (java.util.Arrays.equals(row(zone), row)) {
			return false;
		}
		System.arraycopy(row, 0, rows, zone * ROW, ROW);
		return true;
	}

	/** Every other zone whose row names the same {@code res} as zone {@code zone}'s, in index order. */
	public List<Integer> sharing(ZoneResource res, int zone) {
		return using(res, id(res, zone), zone);
	}

	/**
	 * Every zone whose row names id {@code id} of {@code res}, in index order, leaving out
	 * {@code except} - normally the zone being edited, or -1 to leave out nothing.
	 *
	 * <p>Leaving it out is the whole point. "Does another map depend on this?" and "which rows name
	 * this?" are different questions, and the editing zone's own row always names what it is about
	 * to change: a guard that counted rows refused every texture import into a freshly forked
	 * private area, reporting the zone as conflicting with itself.
	 */
	public List<Integer> using(ZoneResource res, int id, int except) {
		List<Integer> out = new ArrayList<>();
		for (int z = 0; z < zones; z++) {
			if (z != except && res.idInMasterRow(rows, z) == id) {
				out.add(z);
			}
		}
		return out;
	}

	/** Writes the table back to the workspace, through the open unit, and stages it. */
	public void write() throws IOException {
		if (file == null) {
			throw new IOException("This master zone-header table was not read from a workspace, so it has nowhere"
					+ " to be written.");
		}
		files.aboutToWrite(file);
		WorkspaceUnit.write(file, rows);
		files.edited(file);
	}

	private void requireZone(int zone) {
		if (zone < 0 || zone >= zones) {
			throw new IndexOutOfBoundsException("zone " + zone + " has no row in the master zone-header table, which"
					+ " holds " + zones);
		}
	}
}
