package ctrmap.formats.pokedata;

import ctrmap.formats.GameFiles;
import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The whole item table, and the only writer for it.
 *
 * <p>WHY IT WRITES IN PLACE. The records are fixed-size, uncompressed and
 * contiguous (measured: 776 entries of 36 bytes from file offset 0x3CE0, with
 * the archive ending exactly where the last one does). Replacing one is
 * therefore a seek and 36 bytes, and the container's header, offset table and
 * every other entry are untouched BY CONSTRUCTION. The alternative - extract to
 * a directory and repack, which is how every other archive in this editor is
 * written - rebuilds the offset table on every save, and this project has
 * already repaired a zone by hand after a pack wrote a stale one. Nothing about
 * an item edit needs that risk.
 *
 * <p>WHAT IT REFUSES. A record that is not {@link ItemData#SIZE} bytes, an id
 * outside the archive, an entry the container stores compressed, or an entry
 * whose stored length is not the record length. Each of those would still
 * "work" - the write would land somewhere - and the damage would show up much
 * later as an archive that reads as data and is not.
 *
 * <p>THE BASELINE. Before the first byte is written, the untouched archive is
 * copied aside inside the workspace. That copy is what "revert to retail" reads
 * and what {@code ModDeployer} diffs against to decide whether the item archive
 * needs to ship at all. It is taken at the last moment when it is still
 * provably retail: this class is the only thing in CTRMap that has ever written
 * this archive, so before its first write there is nothing of ours in there. If
 * the copy has been deleted after edits were made, it is NOT retaken - a
 * baseline captured from an edited game is worse than an absent one, which is
 * the lesson {@code Workspace.snapshotMissingArchives} was written for.
 *
 * <p>WHOSE GAME. Every static entry point is handed the {@link GameFiles} it
 * works on. This class used to fetch the open game from the application's
 * global session holder itself - the archive from its game folder, the
 * baseline from its workspace folder, the feature gate from its profile - so
 * "revert to retail" and "does deploy need to ship this" could only ever be
 * asked about the one game the application had open, and no suite could hand
 * it a table of its own without first installing a workspace in the global.
 * Handed, it answers for the game it was handed; handed nothing, it refuses in
 * words, because a null there used to read as "no workspace, so nothing to
 * ship" and hid the caller that forgot to pass one.
 */
public final class ItemTable {

	/** The class's own directory inside the workspace, holding the pre-edit copy; see {@link GameFiles#durable}. */
	private static final String BASELINE_DIR = "original_items";
	private static final String BASELINE_NAME = "itemdata.garc";
	/** Written beside the baseline the first time this class saves, and never removed. */
	private static final String EDITED_MARK = "edited.txt";

	private final File garcFile;
	private final File baselineDir;
	private GARC garc;
	private final List<byte[]> records = new ArrayList<>();

	private ItemTable(File garcFile, File baselineDir) {
		this.garcFile = garcFile;
		this.baselineDir = baselineDir;
	}

	// ---- finding it --------------------------------------------------------

	/**
	 * The handed game's item archive on disk, or null when that game has no
	 * verified location for it or the file is not there.
	 *
	 * <p>The location is the game's answer, never a literal here: a null from
	 * it means "not present or not yet verified for this game" and is treated
	 * as absence, not as an invitation to guess.
	 */
	public static File archiveFile(GameFiles game) {
		File f = handed(game, "archiveFile").archiveFile(ArchiveType.ITEM_DATA);
		return f != null && f.isFile() ? f : null;
	}

	/**
	 * Where the pre-edit copy lives for the handed game, or null when that
	 * game has no workspace to keep one in. A directory cleaning never
	 * empties, by the contract of {@link GameFiles#durable}: the copy is the
	 * only record of what the archive held before this editor touched it.
	 */
	public static File baselineDir(GameFiles game) {
		return handed(game, "baselineDir").durable(BASELINE_DIR);
	}

	/**
	 * Opens the handed game's item table, or null when there is none this
	 * editor is allowed to write.
	 *
	 * <p>Gated on {@link GameProfile.Feature#ITEM_EDITING} and not merely on
	 * the path existing. XY's item archive is a location CITED from pk3DS that
	 * nobody has measured against an XY dump here, and the profile says so in
	 * a comment - a path good enough to look at and not good enough to poke 36
	 * bytes into. Null here becomes a sentence in the editor rather than a
	 * write through a guess.
	 */
	public static ItemTable open(GameFiles game) throws IOException {
		if (!handed(game, "open").profile().supports(GameProfile.Feature.ITEM_EDITING)) {
			return null;
		}
		File f = archiveFile(game);
		return f == null ? null : open(f, baselineDir(game));
	}

	/**
	 * Opens an item archive.
	 *
	 * <p>Compression sniffing is OFF. An item record is 36 arbitrary bytes and
	 * may legitimately begin with 0x11, which the sniffer reads as an LZ11
	 * header; the trainer archives are opened the same way for the same reason.
	 *
	 * @param baselineDir where the pre-edit copy is kept, or null for a caller
	 * (a suite, a tool) that wants no copy taken
	 */
	public static ItemTable open(File garcFile, File baselineDir) throws IOException {
		if (garcFile == null || !garcFile.isFile()) {
			throw new IOException("no item archive at " + garcFile);
		}
		ItemTable t = new ItemTable(garcFile, baselineDir);
		t.reload();
		return t;
	}

	/**
	 * The refusal every static entry point starts with. A null game used to
	 * mean "no workspace is open" and answered null or false from each of
	 * them, which is indistinguishable from "this game has no item table" and
	 * let a caller that forgot to hand one in look like a game that lacked it.
	 */
	private static GameFiles handed(GameFiles game, String what) {
		if (game == null) {
			throw new IllegalArgumentException("ItemTable." + what + " was handed no game. The item archive, its"
					+ " pre-edit copy and the feature gate all belong to a game, and there is nothing to"
					+ " answer for without one.");
		}
		return game;
	}

	private void reload() throws IOException {
		garc = new GARC(garcFile, false);
		records.clear();
		for (int i = 0; i < garc.getEntryCount(); i++) {
			byte[] raw = garc.getDecompressedEntry(i);
			records.add(raw);
		}
		if (records.isEmpty()) {
			throw new IOException(garcFile + " holds no entries - it is not an item archive");
		}
	}

	// ---- reading -----------------------------------------------------------

	public int count() {
		return records.size();
	}

	public File file() {
		return garcFile;
	}

	/** A copy of the record's bytes, or null when the entry is not a record. */
	public byte[] raw(int id) {
		if (id < 0 || id >= records.size()) {
			return null;
		}
		byte[] b = records.get(id);
		if (b == null || b.length != ItemData.SIZE) {
			return null;
		}
		return b.clone();
	}

	public ItemData record(int id) {
		byte[] b = raw(id);
		return b == null ? null : new ItemData(b);
	}

	/** Every record, in id order, for the derived effect labels. Nulls kept in place. */
	public List<ItemData> all() {
		List<ItemData> out = new ArrayList<>(records.size());
		for (int i = 0; i < records.size(); i++) {
			out.add(record(i));
		}
		return out;
	}

	/**
	 * The ids a new item can actually occupy: all-zero records, MINUS id 0.
	 *
	 * <p>Measured on a retail dump the blank records are 0, 113, 114, 115 and
	 * 126 - five - but id 0 is the "no item" sentinel that every empty held-item
	 * slot in the game points at, so four is the real number. That is a limit,
	 * not a design choice, and it is computed here rather than written down so
	 * that a dump which disagrees produces a different answer instead of a
	 * confident wrong one.
	 *
	 * <p>An empty record does NOT always mean an inert item: Ability Capsule
	 * (645) has an all-zero record and works, because its behaviour is keyed on
	 * its id in code. So this asks for blank AND unnamed - a slot whose name is
	 * the placeholder is one the game itself calls unused.
	 */
	public List<Integer> freeSlots(List<String> names) {
		List<Integer> out = new ArrayList<>();
		for (int i = 1; i < records.size(); i++) {
			ItemData d = record(i);
			if (d == null || !d.isBlank()) {
				continue;
			}
			if (names != null && i < names.size() && !isPlaceholderName(names.get(i))) {
				continue;
			}
			out.add(i);
		}
		return out;
	}

	/** The retail placeholder for an unused item name. */
	public static boolean isPlaceholderName(String s) {
		if (s == null) {
			return true;
		}
		String t = s.trim();
		return t.isEmpty() || "???".equals(t) || "-".equals(t);
	}

	// ---- writing -----------------------------------------------------------

	/**
	 * Replaces one record, in place, without repacking the archive.
	 *
	 * <p>Everything that could make the write land somewhere it should not is
	 * checked first, and the bytes are read back afterwards: a write that
	 * reported success and did nothing is the failure this project keeps
	 * finding, so this one confirms itself rather than trusting the file
	 * system.
	 *
	 * <p>Nothing is reported as edited to the game: the archive is written
	 * where it lives, not staged for a pack, and a pack that heard of it would
	 * look for an extracted file that does not exist.
	 */
	public void writeRecord(int id, byte[] rec) throws IOException {
		if (rec == null || rec.length != ItemData.SIZE) {
			throw new IllegalArgumentException("an item record is " + ItemData.SIZE + " bytes, not "
					+ (rec == null ? "null" : String.valueOf(rec.length)));
		}
		if (id < 0 || id >= records.size()) {
			throw new IllegalArgumentException("item " + id + " is outside this archive's "
					+ records.size() + " entries. This editor does not raise that ceiling.");
		}
		if (garc.isEntryCompressed(id)) {
			throw new IOException("item " + id + " is stored compressed in this archive, so a"
					+ " same-length write would corrupt it. Refusing.");
		}
		if (garc.getEntryStoredLength(id) != ItemData.SIZE) {
			throw new IOException("item " + id + " is stored as " + garc.getEntryStoredLength(id)
					+ " bytes, not " + ItemData.SIZE + " - writing " + ItemData.SIZE
					+ " over it would run into the next record. Refusing.");
		}
		int off = garc.getEntryFileOffset(id);
		long lengthBefore = garcFile.length();
		if (off < 0 || off + ItemData.SIZE > lengthBefore) {
			throw new IOException("item " + id + " claims to start at 0x" + Integer.toHexString(off)
					+ ", which is not inside a " + lengthBefore + "-byte file");
		}
		captureBaseline();

		try (RandomAccessFile raf = new RandomAccessFile(garcFile, "rw")) {
			raf.seek(off);
			raf.write(rec);
		}
		if (garcFile.length() != lengthBefore) {
			throw new IOException("writing item " + id + " changed the archive's length from "
					+ lengthBefore + " to " + garcFile.length() + " - an in-place write must not");
		}
		//read it back: a save that silently did nothing is the failure mode this
		//whole editor exists downstream of
		byte[] back = new byte[ItemData.SIZE];
		try (RandomAccessFile raf = new RandomAccessFile(garcFile, "r")) {
			raf.seek(off);
			raf.readFully(back);
		}
		for (int i = 0; i < ItemData.SIZE; i++) {
			if (back[i] != rec[i]) {
				throw new IOException("item " + id + " did not survive the write - byte 0x"
						+ Integer.toHexString(i) + " reads back as " + (back[i] & 0xFF)
						+ " instead of " + (rec[i] & 0xFF) + ". Is the archive open in another program?");
			}
		}
		records.set(id, rec.clone());
		markEdited();
	}

	/**
	 * Copies the archive aside, once, before the first edit.
	 *
	 * <p>Taken here and not earlier because here is the last moment it is still
	 * provably untouched by this editor. Never RETAKEN: if the copy is gone but
	 * the edited mark is there, the live archive is known to hold our edits and
	 * capturing it would record them as retail.
	 */
	private void captureBaseline() throws IOException {
		if (baselineDir == null) {
			return;                  // no workspace: the caller is a test or a tool
		}
		File dst = new File(baselineDir, BASELINE_NAME);
		if (dst.isFile()) {
			return;
		}
		if (new File(baselineDir, EDITED_MARK).isFile()) {
			throw new IOException("the pre-edit copy of the item archive is missing from\n  "
					+ baselineDir + "\nbut this workspace has already edited items, so the archive"
					+ " as it stands is NOT retail and copying it now would record your edits as"
					+ " the original. Restore that folder from a backup, or start from an"
					+ " unmodified game, before editing items again.");
		}
		if (!baselineDir.isDirectory() && !baselineDir.mkdirs()) {
			throw new IOException("could not create " + baselineDir);
		}
		Files.copy(garcFile.toPath(), dst.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
	}

	private void markEdited() {
		if (baselineDir == null) {
			return;
		}
		File mark = new File(baselineDir, EDITED_MARK);
		if (mark.isFile()) {
			return;
		}
		try {
			if (!baselineDir.isDirectory()) {
				baselineDir.mkdirs();
			}
			Files.write(mark.toPath(), ("This workspace has edited the item archive in place.\r\n"
					+ "The copy beside this file is the archive as it was before the first edit.\r\n")
					.getBytes("UTF-8"));
		} catch (IOException ex) {
			//the mark is a safety rail on RE-capturing a baseline that already
			//exists; failing to write it must not fail the edit the user just made
			System.err.println("ItemTable: could not write " + mark + ": " + ex);
		}
	}

	// ---- the baseline, for reverting and for deciding what to deploy --------

	/** The pre-edit copy in a baseline directory, or null when none was taken (or there is no directory). */
	private static File baselineIn(File dir) {
		if (dir == null) {
			return null;
		}
		File f = new File(dir, BASELINE_NAME);
		return f.isFile() ? f : null;
	}

	/** The pre-edit copy for the handed game, or null when none was taken. */
	public static File baselineArchive(GameFiles game) {
		return baselineIn(baselineDir(game));
	}

	/** The pre-edit copy this table would revert to, or null when none was taken. */
	public File baselineArchive() {
		return baselineIn(baselineDir);
	}

	/**
	 * One record as it was before this table's game ever edited items, or
	 * null when there is no baseline to read it from.
	 *
	 * <p>An instance method on purpose: the copy belongs to the baseline
	 * directory this table was opened with, and a static that answered from
	 * the application's workspace could show one game's retail record beside
	 * another game's table.
	 */
	public byte[] baselineRecord(int id) {
		File f = baselineArchive();
		if (f == null) {
			return null;
		}
		try {
			GARC g = new GARC(f, false);
			if (id < 0 || id >= g.getEntryCount()) {
				return null;
			}
			byte[] b = g.getDecompressedEntry(id);
			return b != null && b.length == ItemData.SIZE ? b : null;
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/**
	 * True when the handed game's live item archive differs from the copy
	 * taken before the first edit - i.e. when a deploy needs to carry it.
	 *
	 * <p>False when no baseline exists, because no baseline means this editor
	 * has never written the archive, so there is nothing of the user's in it.
	 */
	public static boolean changedSinceBaseline(GameFiles game) {
		File base = baselineArchive(handed(game, "changedSinceBaseline"));
		File live = archiveFile(game);
		if (base == null || live == null) {
			return false;
		}
		try {
			if (base.length() != live.length()) {
				return true;
			}
			byte[] a = Files.readAllBytes(base.toPath());
			byte[] b = Files.readAllBytes(live.toPath());
			for (int i = 0; i < a.length; i++) {
				if (a[i] != b[i]) {
					return true;
				}
			}
			return false;
		} catch (IOException ex) {
			//unreadable: say it changed rather than quietly leaving it out of a
			//deploy, because a missing archive in a mod is a bug the user meets
			//in-game and a redundant one costs 43 KB
			return true;
		}
	}
}
