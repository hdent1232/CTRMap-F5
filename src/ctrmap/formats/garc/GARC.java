package ctrmap.formats.garc;

import ctrmap.util.LittleEndianDataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import ctrmap.util.LittleEndian;

/**
 * GARC implementation, ported mostly from the original Ohana3DS and pk3DS, but
 * will be soon rewritten.
 */
public class GARC {

	public File file;

	public short padding;

	private ArrayList<GARCEntry> entries = new ArrayList<>();

	public int length;

	/**
	 * How many entries were actually readable, when the file ran out before the header said
	 * it would. {@link #length} stays at the DECLARED count, because that is what the archive
	 * claims to hold and a reader deciding whether it has everything needs the claim, not a
	 * number quietly corrected to match what survived.
	 *
	 * <p>-1 when the archive parsed whole.
	 */
	public int readableEntries = -1;

	private final boolean allowCompression;

	public GARC(File f) {
		this(f, true);
	}

	/**
	 * Opens a GARC. The container stores NO per-entry compression flag - LZ11 is
	 * detected by sniffing - so archives whose entries are known to be raw
	 * (trainer data: any entry may legitimately START with 0x11) must pass
	 * {@code allowCompression = false} or risk corruption on read.
	 */
	public GARC(File f, boolean allowCompression) {
		this.allowCompression = allowCompression;
		parse(f);
	}

	/**
	 * (Re)reads the archive's entry table. Called by the constructor AND at the
	 * end of every {@link #packDirectory} - a pack rewrites the file's layout,
	 * and an instance whose entry table still described the OLD layout would
	 * corrupt any entry it copies "unchanged" on a SECOND pack (stale offsets).
	 */
	private void parse(File f) {
		try {
			this.file = f;
			entries.clear();
			//RESET IT. `entries` is cleared here because a re-parse describes the file as it
			//is NOW; the truncation flag has to be cleared for the same reason. Left standing,
			//an instance that once read a short file would refuse to pack for the rest of its
			//life - including after replaceWith() re-reads a whole archive it just wrote.
			readableEntries = -1;
			//remember the file as it was when this table was read, so a later
			//pack can tell whether anything else has rewritten it since
			parsedLength = f.length();
			parsedModified = f.lastModified();

			try (RandomAccessFile in = new RandomAccessFile(f, "r")) {

				byte[] strbuf = new byte[4];
				in.readFully(strbuf);

				String garcMagic = new String(strbuf, java.nio.charset.StandardCharsets.ISO_8859_1);
				//A FILE THAT IS NOT A GARC IS NOT READ AS ONE (F054). The magic was read and thrown away,
				//so any file long enough was parsed into invented entries - and could be packed back.
				if (!"CRAG".equals(garcMagic)) {
					throw new RuntimeException(f.getName() + " is not a GARC: it begins '" + garcMagic
							+ "', not 'CRAG'. Nothing was read from it.");
				}
				int garcLength = Integer.reverseBytes(in.readInt());
				short endian = Short.reverseBytes(in.readShort());
				short version = Short.reverseBytes(in.readShort());
				this.version = version & 0xFFFF;
				folderEntries = false;
				int sectionCount = Integer.reverseBytes(in.readInt());
				int dataOffset = Integer.reverseBytes(in.readInt());
				int decompressedLength = Integer.reverseBytes(in.readInt());
				int compressedLength = Integer.reverseBytes(in.readInt());
				padding = 4; //version 4 has always nearest pad to 4, ver 6 can specify more

				in.seek(garcLength);

				long fatoPosition = in.getFilePointer();
				in.readFully(strbuf);
				String fatoMagic = new String(strbuf);
				int fatoLength = Integer.reverseBytes(in.readInt());
				short fatoEntries = Short.reverseBytes(in.readShort());
				length = fatoEntries;
				short pad = in.readShort(); //0xFFFF

				long fatbPosition = fatoPosition + fatoLength;
				long fileLength = in.length();
				for (int i = 0; i < fatoEntries; i++) {
					in.seek(fatoPosition + 0xc + i * 4);
					in.seek(Integer.reverseBytes(in.readInt()) + fatbPosition + 0xc);

					int flags = Integer.reverseBytes(in.readInt());
					//an entry holding anything but one file is a folder the writer would flatten
					folderEntries |= flags != 1;

					for (int bit = 0; bit < 32; bit++) {
						if ((flags & (1 << bit)) > 0) {
							int startOffset = Integer.reverseBytes(in.readInt());
							int endOffset = Integer.reverseBytes(in.readInt());
							int length = Integer.reverseBytes(in.readInt());

							long position = in.getFilePointer();

							in.seek(startOffset + dataOffset);

							//ITS FIRST BYTES, NOT THE ENTRY (F055): the sniff decodes 64 of them, and reading
							//every entry whole made opening an archive read all of it. An entry the file
							//cannot hold is still a cut, as the whole read used to find it.
							if ((long) startOffset + dataOffset + length > fileLength) {
								throw new java.io.EOFException("entry " + entries.size() + " runs past the end of the file");
							}
							byte[] prefix = new byte[Math.min(length, SNIFF_PREFIX_BYTES)];
							in.readFully(prefix);

							boolean isCompressed = allowCompression && sniffLZ11(prefix, length);

							GARCEntry entry = new GARCEntry();
							entry.offset = startOffset + dataOffset;
							entry.length = length;
							entry.compressed = isCompressed;
							entries.add(entry);

							in.seek(position);
						}
					}
				}
			}
		} catch (IOException ex) {
			//A HALF-PARSED ARCHIVE IS NOT AN ARCHIVE, AND IT IS NOT NOTHING EITHER.
			//This logged and returned, leaving `length` at the count the header declared
			//while `entries` held only what was read before the file ran out - so the two
			//disagreed and nothing said so. Throwing here was the over-correction: it took
			//the readable entries away along with the unreadable ones, and the battery has
			//been red since, because GarcSniffTest asserts that the survivors stay readable.
			//
			//The disagreement is recorded instead. READING is recoverable - the entries
			//before the cut are whole and getStoredEntry refuses past it. WRITING is the
			//damage, and packDirectory refuses it below: a pack that reads 155 of 436
			//entries and writes 155 back has destroyed the other 281.
			//...BUT ONLY WHEN THERE IS SOMETHING TO RECOVER. "Truncated" describes an archive
			//whose entry table was read and then ran out. A file that is ABSENT, or that is
			//not a GARC at all, yields NOTHING - and recording that as a truncated archive
			//with zero readable entries turns "I could not look" into "it is empty", which is
			//the same answer a real empty archive gives. Measured the day this was written:
			//opening a path that does not exist returned an instance instead of raising, so
			//ZoneLoadingStateTest's missing zone table opened cleanly and ModDeployer called
			//two non-archives identical - identical there meaning "already shipped, skip it".
			//The over-correction and the under-correction are both here, one line apart: read
			//nothing, refuse; read something, record the cut.
			if (entries.isEmpty()) {
				throw new RuntimeException(f.getName() + " is not a readable archive: "
					+ ctrmap.util.Bytes.reason(ex) + ". Nothing was read from it, so it is"
					+ " UNKNOWN rather than empty - a file that cannot be opened is not an"
					+ " archive with no entries in it.", ex);
			}
			readableEntries = entries.size();
			Logger.getLogger(GARC.class.getName()).log(Level.SEVERE,
				file.getName() + " is TRUNCATED: read " + readableEntries + " of the "
				+ length + " entries its header declares (" + ctrmap.util.Bytes.reason(ex)
				+ "). The entries before the cut are readable; the rest refuse, and this archive"
				+ " may not be packed back.", ex);
		}
	}

	/**
	 * How many stored bytes {@link #sniffLZ11} decodes strictly before
	 * believing an entry is compressed. Measured on the dump (below): every
	 * raw record that starts with 0x11 faults within its first 24 bytes, and
	 * the check with a 32-byte bound is already exact, so 64 is margin.
	 */
	static final int SNIFF_PREFIX_BYTES = 64;

	/**
	 * Decides what a stored entry IS: an LZ11 stream, or raw bytes that merely
	 * start with 0x11. Three checks, each a fact about the format rather than
	 * a threshold: a declared length above zero, at least as many stored bytes
	 * as a stream of that length must have
	 * ({@link LZ11#minimumStoredLength}), and a first
	 * {@value #SNIFF_PREFIX_BYTES} bytes that decode strictly
	 * ({@link LZ11#prefixDecodes}).
	 *
	 * <p>THIS USED TO BE A SIZE CEILING ({@code declared < 4 MB}), and before
	 * that a 64:1 ratio cap as well. Both were guesses standing in for the
	 * measurement, and each was wrong in both directions. Measured across all
	 * 299 archives of a retail ORAS dump - 21,923 entries start with 0x11 -
	 * decoding each STRICTLY (a token that runs past the entry, or a
	 * back-reference to bytes not yet written, is a refusal):
	 * <pre>
	 *                              genuine stream   raw record   declares 0
	 *   the 4 MB ceiling accepted        21,905            12           0
	 *   the 4 MB ceiling refused              2             1           3
	 *   these three checks accept        21,907             0           0
	 *   these three checks refuse             0            13           3
	 * </pre>
	 * The two genuine streams the ceiling refused are a/0/0/8 #6223 (a
	 * Pokemon model, 4,723,968 bytes) and a/1/5/2 #561 (a BCH, 5,521,120):
	 * every reader got them back compressed, and an edit would have been
	 * written raw into a slot the game reads as LZ11. The twelve raw records
	 * it accepted - a/0/0/7 #2, a/0/2/2 #17 #273 #371 #463, a/0/8/9 #450
	 * #648, a/1/9/0 #86 #258 #331, a/1/9/1 #22 #385 - are fixed-size table
	 * rows whose first byte happens to be 0x11 (a/0/2/2 is 510 rows of 24
	 * bytes); every reader got garbage inflated from them. The
	 * one raw record it refused, a/0/3/7 #122 (16 bytes declaring
	 * 16,720,640), is refused here by arithmetic: a stream of that length
	 * needs at least 1,056 bytes, and its first token references bytes that
	 * do not exist.
	 *
	 * <p>An earlier measurement here named a/0/2/2 #463 as the dump's
	 * "worst legitimate ratio" at 5473:1 and counted only 3 raw records. It
	 * was built on {@link LZ11#decompress}, which pads past the end of its
	 * input and so "decodes" anything; #463 is a raw 24-byte row. The worst
	 * genuine ratio, measured strictly, is a/1/6/0 #0: 62 bytes to 262,184
	 * (4229:1), well inside the arithmetic bound.
	 *
	 * <p>WHY STILL NOT A FULL TRIAL DECODE: 41.8 s across the dump, 24 s for
	 * a/0/0/8 alone. Parsing that archive costs about 0.7 s today, and a
	 * 64-byte strict prefix per entry is not measurable against it.
	 *
	 * <p>Guarded by {@link ctrmap.tests.GarcSniffTest}, in both directions.
	 */
	private static boolean sniffLZ11(byte[] prefix, int stored) {
		if (stored < 4 || prefix[0] != 0x11) {
			return false;
		}
		int declared = LittleEndian.u24(prefix, 1);
		return declared > 0
				&& stored >= LZ11.minimumStoredLength(declared)
				&& LZ11.prefixDecodes(prefix, SNIFF_PREFIX_BYTES, stored);
	}

	/**
	 * Packs a workspace directory into this GARC: the files in it that
	 * {@code edited} accepts, named by entry index, replace or append entries,
	 * and the rest are ignored. The rewritten archive is staged as
	 * {@code <name>_new} under {@code scratchDir} and moved over this one.
	 * Entries that already exist keep their original compression flag; appended
	 * entries (index beyond the original entry count) inherit the flag of the
	 * LAST original entry, unless compressionOverrides holds a Boolean for
	 * their index (keyed by the numeric file name in the pack directory).
	 *
	 * <p>Nothing here knows about the workspace. Which files count as edits and
	 * where to stage are handed in, so a suite can pack an archive with an
	 * explicit set and no session at all, and the format layer reaches into no
	 * application class. It used to read both from Workspace's statics.
	 */
	public void packDirectory(File dir, java.util.function.Predicate<File> edited, File scratchDir) throws IOException {
		packDirectory(dir, edited, scratchDir, null);
	}

	/**
	 * True when the archive file has been rewritten by somebody else since this
	 * instance read its entry table.
	 *
	 * <p>The table is a list of offsets into the file. If another process - a
	 * second CTRMap window, a headless tool, a restore script - repacks the
	 * archive, every offset this instance holds now points at the wrong place,
	 * and the next pack copies every "unchanged" entry from garbage. It is
	 * silent: the pack succeeds and the archive is ruined.
	 *
	 * <p>This nearly happened for real. A CTRMap window sat open for eleven
	 * hours while a headless tool rewrote ZoneData five times; one click on Pack
	 * Workspace would have rebuilt the archive from an entry table describing
	 * the file as it was that morning.
	 */
	public boolean isStale() {
		return file != null && file.exists()
				&& (file.length() != parsedLength || file.lastModified() != parsedModified);
	}

	/** The header's version, as read: 0x400 for every archive this editor writes. */
	private int version;
	/** Whether any entry held more than one file (a folder), which the writer cannot keep. */
	private boolean folderEntries;

	/** File length when {@link #parse} last ran; see {@link #isStale}. */
	private long parsedLength = -1;
	private long parsedModified = -1;

	/**
	 * What packing had to warn about, waiting for whoever packed to say it out
	 * loud.
	 *
	 * <p>A pack that finds the archive rewritten underneath it carries on - the
	 * repair is to re-read the entry table, and that is what happens - but the
	 * user has to be told that a second program is writing to their game. That
	 * sentence used to go to stderr, and the shipped build has no console to
	 * put it on, so nobody ever saw it. Collecting it here lets the pack
	 * ({@code WorkspaceSession.packArchives}) hand it to the dialog that
	 * reports the pack, whichever archives the pack happened to touch.
	 */
	private static final ArrayList<String> packWarnings = new ArrayList<>();

	/** Takes everything packing has warned about since the last call. */
	public static ArrayList<String> drainPackWarnings() {
		ArrayList<String> out = new ArrayList<>(packWarnings);
		packWarnings.clear();
		return out;
	}

	/**
	 * Packs the staged files of one extraction directory back into this
	 * archive, in four steps that a reader can take one at a time:
	 * {@link #rereadIfStale} (the table on disk may not be the one in
	 * memory), {@link #filesToPack} (which staged files count),
	 * {@link #plan} (what the archive will hold - the table as it will be,
	 * the entries replaced, their stored bytes; the gap refusal lives here),
	 * {@link #writeRepacked} (the bytes, beside the workspace) and
	 * {@link #replaceWith} (the swap, then a re-read so this instance
	 * describes what it just wrote). Nothing here touches the live archive
	 * until the replacement is complete.
	 */
	public void packDirectory(File dir, java.util.function.Predicate<File> edited, File scratchDir,
			Map<Integer, Boolean> compressionOverrides) throws IOException {
		if (!dir.isDirectory()) {
			return;
		}
		File newGARC = new File(scratchDir, file.getName() + "_new");
		prepareRepack(dir, edited, compressionOverrides, newGARC);
		replaceWith(newGARC);
	}

	/**
	 * The first half of {@link #packDirectory}: writes the repacked archive to {@code newGARC}
	 * and leaves the live archive untouched; {@link #commitRepack} puts it in place. Split so a
	 * pack of several archives can write all of them before it changes any (F035).
	 */
	public void prepareRepack(File dir, java.util.function.Predicate<File> edited,
			Map<Integer, Boolean> compressionOverrides, File newGARC) throws IOException {
		requirePackable(dir);
		PackPlan plan = plan(filesToPack(dir, edited), compressionOverrides);
		writeRepacked(newGARC, plan);
	}

	/**
	 * What both halves of a pack refuse before anything is written, and the re-read that makes this
	 * instance describe the archive on disk.
	 */
	private void requirePackable(File dir) throws IOException {
		//A TRUNCATED ARCHIVE MAY NOT BE PACKED BACK, and this is where the damage would be
		//done rather than where it would be noticed. `parse` records a short read instead of
		//refusing, because the entries before the cut are perfectly readable and taking them
		//away helps nobody - but a pack that read 155 of the 436 entries a header declares
		//and writes 155 back has DESTROYED the other 281, irreversibly, over the owner's own
		//game data. This project has already paid for one stale-pack corruption on zone 536.
		//
		//Refusing at read time was the over-correction that reddened the battery; refusing
		//here costs nothing anybody wanted to do.
		if (readableEntries >= 0) {
			throw new IllegalStateException(file.getName() + " was read TRUNCATED ("
				+ readableEntries + " of the " + length + " entries its header declares) and"
				+ " must not be packed back: writing it would destroy the "
				+ (length - readableEntries) + " entries that were never read. Restore the"
				+ " archive from the pristine copy first.");
		}
		if (!dir.isDirectory()) {
			throw new IOException(dir + " is not there, so nothing staged in it can be packed into "
					+ file.getName() + ".");
		}
		//NOTHING IS WRITTEN IN A LAYOUT THE WRITER WAS NOT BUILT FOR (F054). It copies a version-4
		//header and pads to 4, and writes every entry as one file: a version-6 archive (the
		//Sun/Moon revision) or one with folder entries would be written back corrupt.
		if (version != 0x0400) {
			throw new IllegalStateException(file.getName() + " is GARC version 0x" + Integer.toHexString(version)
					+ "; packing writes version 4 (0x400) only, so it is not written back.");
		}
		if (folderEntries) {
			throw new IllegalStateException(file.getName() + " holds entries with more than one file in"
					+ " them; packing writes one file per entry and would flatten them, so it is not written back.");
		}
		rereadIfStale();
	}

	/**
	 * The in-place pack (F082): copies this archive to {@code newGARC} and writes each staged entry
	 * over its own stored bytes there. No table is rebuilt and no entry moves, so the header, the
	 * offset table and every other entry are untouched by construction - for an archive of
	 * fixed-size, uncompressed records (the item table), where rebuilding the table on every save
	 * buys nothing. An entry stored compressed, one whose staged bytes are not its stored length, or
	 * a file that names no entry of this archive is refused before the copy is made: each would
	 * still "work", and land over the next record. {@link #commitRepack} puts the result in place, as
	 * it does a repack's.
	 */
	public void prepareInPlace(File dir, java.util.function.Predicate<File> edited, File newGARC) throws IOException {
		requirePackable(dir);
		long size = file.length();
		Map<Integer, byte[]> writes = new java.util.TreeMap<>();
		for (File f : filesToPack(dir, edited)) {
			int id;
			try {
				id = Integer.parseInt(f.getName());
			} catch (NumberFormatException notAnEntry) {
				throw new IOException(f + " is staged as an edit of " + file.getPath() + " but names no entry of it.");
			}
			if (id < 0 || id >= getEntryCount()) {
				throw new IOException("entry " + id + " is staged for " + file.getPath() + ", which holds "
						+ getEntryCount() + "; an archive written in place does not grow.");
			}
			if (isEntryCompressed(id)) {
				throw new IOException("entry " + id + " of " + file.getPath() + " is stored compressed, so writing"
						+ " its bytes in place would corrupt it. Nothing was written.");
			}
			byte[] b = Files.readAllBytes(f.toPath());
			if (b.length != getEntryStoredLength(id)) {
				throw new IOException("entry " + id + " of " + file.getPath() + " is stored as "
						+ getEntryStoredLength(id) + " bytes and staged as " + b.length + "; written in place it"
						+ " would run into the next entry. Nothing was written.");
			}
			int off = getEntryFileOffset(id);
			if (off < 0 || off + b.length > size) {
				throw new IOException("entry " + id + " of " + file.getPath() + " claims to start at 0x"
						+ Integer.toHexString(off) + ", which is not inside its " + size + " bytes.");
			}
			writes.put(id, b);
		}
		//the copy is written the way a repack writes its result, through one RandomAccessFile
		byte[] whole = Files.readAllBytes(file.toPath());
		try (RandomAccessFile raf = new RandomAccessFile(newGARC, "rw")) {
			raf.setLength(0);
			raf.write(whole);
			for (Map.Entry<Integer, byte[]> w : writes.entrySet()) {
				raf.seek(getEntryFileOffset(w.getKey()));
				raf.write(w.getValue());
			}
		}
		if (newGARC.length() != size) {
			throw new IOException("writing " + writes.size() + " entries in place changed the length of " + file.getPath()
					+ " from " + size + " to " + newGARC.length() + " - an in-place write must not.");
		}
	}

	/** The second half: moves a {@link #prepareRepack prepared} archive over this one and re-reads it. */
	public void commitRepack(File newGARC) throws IOException {
		replaceWith(newGARC);
	}

	/**
	 * Re-reads the entry table when the archive was rewritten underneath
	 * this instance, and records the warning for whoever packed.
	 */
	private void rereadIfStale() {
		if (isStale()) {
			//Re-reading is the right repair: the entries on disk are fine,
			//only this instance's picture of them is out of date. Packing
			//first and asking questions later is what corrupts the archive.
			String warning = file.getPath() + " changed on disk since it was read"
					+ " (was " + parsedLength + "B, now " + file.length() + "B)."
					+ " Its entry table was re-read before packing - something else is writing"
					+ " to this archive.";
			System.err.println("GARC: " + warning);
			packWarnings.add(warning);
			parse(file);
		}
	}

	/**
	 * The staged files a pack takes from an extraction directory: only the
	 * ones the CALLER says are edited - the directory holds every entry
	 * ever opened - in entry order, which is numeric ("10" sorts before "2"
	 * as text).
	 */
	public static ArrayList<File> filesToPack(File dir, java.util.function.Predicate<File> edited) {
		ArrayList<File> files = new ArrayList<>();
		files.addAll(Arrays.asList(dir.listFiles()));
		for (int i = 0; i < files.size(); i++) {
			if (!edited.test(files.get(i))) {
				files.remove(i);
				i--;
			}
		}
		Collections.sort(files, new Comparator<File>() {
			@Override
			public int compare(File o1, File o2) {
				int i1 = Integer.parseInt(o1.getName());
				int i2 = Integer.parseInt(o2.getName());
				return i1 - i2;
			}
		});
		return files;
	}

	/**
	 * How an entry will be stored: an explicit override wins for ANY slot (a
	 * zone insert-shift needs to re-compress a slot whose original entry was
	 * uncompressed); otherwise an existing slot keeps its entry's flag and an
	 * appended slot inherits the last entry's.
	 */
	public boolean storedCompressed(Boolean override, int slot) {
		if (override != null) {
			return override;
		}
		if (slot < entries.size()) {
			return entries.get(slot).compressed;
		}
		return entries.get(entries.size() - 1).compressed;
	}

	/** What one pack will write: the table as it will be, which slots are replaced, and their stored bytes. */
	private static final class PackPlan {

		final ArrayList<GARCEntry> working;
		final int[] changedIndices;
		final byte[][] compressedData;

		PackPlan(ArrayList<GARCEntry> working, int[] changedIndices, byte[][] compressedData) {
			this.working = working;
			this.changedIndices = changedIndices;
			this.compressedData = compressedData;
		}
	}

	/**
	 * Reads and compresses each staged file and works out the table the
	 * archive will have afterwards. Refuses a file named past the tail.
	 *
	 * <p>Every appended entry goes into a table of this plan's own, and the
	 * instance's table is only ever replaced by re-reading the file it just
	 * wrote. Anything that throws on the way out - a gapped append, the
	 * emulator holding the archive open - therefore leaves this instance
	 * describing the archive as it still is on disk. It used to append
	 * straight into the live table, so a refusal left the instance one or
	 * more entries ahead of the file, and the next pack wrote that table
	 * out: a real FATB entry whose bytes were copied from a provisional
	 * offset that was never in the file.
	 */
	private PackPlan plan(ArrayList<File> files, Map<Integer, Boolean> compressionOverrides) throws IOException {
		ArrayList<GARCEntry> working = new ArrayList<>(entries);
		int[] changedIndices = new int[files.size()];
		byte[][] compressedData = new byte[files.size()][];
		for (int i = 0; i < files.size(); i++) {
			changedIndices[i] = Integer.valueOf(files.get(i).getName());
			//READ ALL OF IT OR REFUSE. available() is not the file length by contract and
			//read() answers what it managed; both answers were dropped, so a staged file
			//that read short was compressed and packed with a zero tail - a region, zone
			//or area silently truncated inside a pack that reported success. The stream
			//also leaked on a throw.
			byte[] or = java.nio.file.Files.readAllBytes(files.get(i).toPath());
			Boolean override = (compressionOverrides != null) ? compressionOverrides.get(changedIndices[i]) : null;
			boolean compressed = storedCompressed(override, changedIndices[i]);
			if (compressed) {
				compressedData[i] = LZ11.compress(or);
			} else {
				compressedData[i] = or;
			}
			if (changedIndices[i] > working.size() - 1) {
				//An archive grows only at its tail. A file named past the tail
				//used to be renumbered to the first free slot without a word,
				//so an area fork's "229" landed at 228 and the zone that asked
				//for 229 could not load. The caller's number is the whole point
				//of the file - every reference to it in the other archives is
				//that number - so a gap is a refusal, not something to guess at.
				if (changedIndices[i] > working.size()) {
					throw new IOException("Cannot pack " + files.get(i).getName() + " into "
							+ file.getPath() + ": the archive ends at index " + (working.size() - 1)
							+ ", so that file would be written at index " + working.size()
							+ " instead of " + changedIndices[i] + ". Whatever indexes this"
							+ " archive would read the wrong entry. Fill indices "
							+ working.size() + ".." + (changedIndices[i] - 1) + " first.");
				}
				GARCEntry add = new GARCEntry();
				add.compressed = compressed;
				//pad-aligned provisional offset (the real table is re-read
				//from the packed file at the end of the pack anyway)
				int prevEnd = working.get(working.size() - 1).offset + working.get(working.size() - 1).length;
				int rem = prevEnd % padding;
				add.offset = rem == 0 ? prevEnd : prevEnd + padding - rem;
				add.length = compressedData[i].length;
				working.add(add);
			}
		}
		return new PackPlan(working, changedIndices, compressedData);
	}

	/**
	 * Writes the repacked archive to {@code newGARC} through {@link GarcWriter}, the one GARC writer
	 * (F056): staged bytes for replaced and appended entries, the old file's bytes for the rest, each
	 * entry keeping its old FATB flags and an appended one taking the first entry's.
	 */
	private void writeRepacked(File newGARC, PackPlan plan) throws IOException {
		final ArrayList<GARCEntry> working = plan.working;
		//A MAP, NOT A SCAN PER ENTRY (F055), and keyed by index, so the order the staged files
		//were planned in cannot pair one entry's bytes with another's slot
		final Map<Integer, byte[]> fresh = new java.util.HashMap<>();
		for (int k = 0; k < plan.changedIndices.length; k++) {
			fresh.put(plan.changedIndices[k], plan.compressedData[k]);
		}
		newGARC.delete();
		//The replacement is written beside the workspace and swapped in whole.
		//Whatever goes wrong on the way - the emulator or a virus scanner
		//holding the archive open is the usual case - must leave the archive
		//as it was, leave no half-written copy behind, and reach the caller.
		//This used to log the exception and return: the progress bar filled,
		//the game kept the old map, and the leaked streams made every retry
		//fail the same way.
		try (final RandomAccessFile src = new RandomAccessFile(file, "r");
				RandomAccessFile dos = new RandomAccessFile(newGARC, "rw")) {
			final GarcWriter.Template t = GarcWriter.Template.read(src);
			GarcWriter.write(dos, t, new GarcWriter.Entries() {
				@Override
				public int count() {
					return working.size();
				}

				@Override
				public int flags(int i) {
					return i < t.flags.length ? t.flags[i] : t.firstFlags();
				}

				@Override
				public int length(int i) {
					return fresh.containsKey(i) ? fresh.get(i).length : working.get(i).length;
				}

				@Override
				public boolean fresh(int i) {
					return fresh.containsKey(i);
				}

				@Override
				public void write(int i, RandomAccessFile to) throws IOException {
					if (fresh.containsKey(i)) {
						to.write(fresh.get(i));
						return;
					}
					//THE ENTRIES THE USER NEVER TOUCHED, and the reason this matters more than the
					//edited ones: a short skip copies an untouched entry FROM THE WRONG OFFSET and a
					//short read copies it with a zero tail, so editing one map corrupts a different
					//one, with no exception and nothing in the report. readFully refuses both. One
					//handle for all of them (F055): this opened the archive again for every entry.
					byte[] b = new byte[working.get(i).length];
					src.seek(working.get(i).offset);
					src.readFully(b);
					to.write(b);
				}
			}, padding);
		} catch (IOException ex) {
			newGARC.delete();
			throw ex;
		}
	}

	/**
	 * Swaps the written replacement in over the live archive, then re-reads
	 * it, so THIS instance stays coherent with the file it just wrote - a
	 * stale entry table on a repeat pack copies unchanged entries from wrong
	 * offsets and silently corrupts them.
	 */
	private void replaceWith(File newGARC) throws IOException {
		try {
			Files.move(newGARC.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ex) {
			newGARC.delete();
			throw new IOException("Could not replace " + file.getName() + " - is the emulator or another"
					+ " program holding it open? (" + ctrmap.util.Bytes.reason(ex) + ")", ex);
		}
		parse(file);
	}

	/**
	 * The entry's bytes exactly as STORED in the archive (still LZ11-compressed
	 * when the entry is). Lets a rebuild preserve entries byte-for-byte without
	 * a decompress/recompress round trip.
	 */
	public byte[] getStoredEntry(int num) {
		//PAST THE CUT, SAY SO. A bare IndexOutOfBoundsException from entries.get() is loud
		//enough not to be data, but it names a list index and not the reason, so a caller
		//reading the log learns that something was out of range rather than that the archive
		//on disk is short. The count the header declares is the number a reader is iterating.
		if (readableEntries >= 0 && num >= readableEntries && num < length) {
			throw new IllegalStateException(file.getName() + " is TRUNCATED: entry " + num
				+ " is past the cut (" + readableEntries + " of " + length
				+ " entries were readable). It is not zeros and it is not empty - it is gone.");
		}
		try {
			GARCEntry e = entries.get(num);
			byte[] b = new byte[e.length];
			//READ IT ALL OR SAY SO. skip() and read() answer how much they managed and both
			//answers were dropped: measured on a/0/4/0 truncated to half, 278 of its 431
			//entries came back as pure-zero buffers of the right length, none null, no
			//exception, no log line - and the editor writes those back over the real archive.
			LittleEndianDataInputStream in = new LittleEndianDataInputStream(new FileInputStream(file));
			try {
				in.skipFully(e.offset);
				in.readFully(b);
			} finally {
				in.close();
			}
			return b;
		} catch (IOException ex) {
			Logger.getLogger(GARC.class.getName()).log(Level.SEVERE,
				"reading stored entry " + num + " of " + file, ex);
			return null;
		}
	}

	/**
	 * Whether the entry at the given index is stored LZ11-compressed (sniffed
	 * from its first byte == 0x11 at load). Used by appenders that must store a
	 * new entry with the SAME compression as the source entry it copies, rather
	 * than gambling on last-entry inheritance (which misflags when the tail
	 * entry's compression differs from the source's).
	 */
	public boolean isEntryCompressed(int num) {
		return entries.get(num).compressed;
	}

	/** The on-disk (stored) byte length of an entry - compressed size when compressed. */
	public int getEntryStoredLength(int num) {
		return entries.get(num).length;
	}

	/**
	 * Absolute file offset where this entry's stored bytes begin.
	 *
	 * <p>For an editor that changes a FIXED-SIZE record and nothing else. Every
	 * other write path here rebuilds the container from a directory, which is
	 * the right thing when lengths change and the wrong thing when they cannot:
	 * a rebuild rewrites the offset table, and this project has already paid
	 * once for a pack that wrote a stale one (zone 536, repaired by hand). A
	 * caller that knows its record is exactly as long as the one it replaces can
	 * seek here and poke the bytes, leaving the header, the offset table and
	 * every other entry untouched by construction rather than by hope.
	 *
	 * <p>Only safe for an UNCOMPRESSED entry whose replacement is exactly
	 * {@link #getEntryStoredLength} bytes; the caller must check both, because
	 * this method cannot tell what is about to be written.
	 */
	public int getEntryFileOffset(int num) {
		return entries.get(num).offset;
	}

	/**
	 * How many entries this instance's table describes. Equals {@link #length}
	 * and the count in the file for an instance that has only ever parsed; the
	 * three drifting apart is what a pack that mutated the table and then threw
	 * used to leave behind, and the next pack wrote the drift into the archive.
	 */
	public int getEntryCount() {
		return entries.size();
	}

	public byte[] getDecompressedEntry(int num) {
		if (num >= entries.size()) {
			return null;
		}
		try {
			LittleEndianDataInputStream dis = new LittleEndianDataInputStream(new FileInputStream(file));
			byte[] data = new byte[entries.get(num).length];
			try {
				//see getStoredEntry: a short read used to be reported as data
				dis.skipFully(entries.get(num).offset);
				dis.readFully(data);
			} finally {
				dis.close();
			}
			if (entries.get(num).compressed) {
				return LZ11.decompress(data);
			} else {
				return data;
			}
		} catch (IOException ex) {
			Logger.getLogger(GARC.class.getName()).log(Level.SEVERE, null, ex);
			return null;
		}
	}
}

class GARCEntry {

	int offset;
	int length;
	boolean compressed;
}
