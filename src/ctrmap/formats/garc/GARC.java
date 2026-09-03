package ctrmap.formats.garc;

import ctrmap.LittleEndianDataInputStream;
import ctrmap.Workspace;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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

/**
 * GARC implementation, ported mostly from the original Ohana3DS and pk3DS, but
 * will be soon rewritten.
 */
public class GARC {

	public File file;

	public short padding;

	private ArrayList<GARCEntry> entries = new ArrayList<>();

	public int length;

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
			//remember the file as it was when this table was read, so a later
			//pack can tell whether anything else has rewritten it since
			parsedLength = f.length();
			parsedModified = f.lastModified();

			RandomAccessFile in = new RandomAccessFile(f, "r");

			byte[] strbuf = new byte[4];
			in.read(strbuf);

			String garcMagic = new String(strbuf);
			int garcLength = Integer.reverseBytes(in.readInt());
			short endian = Short.reverseBytes(in.readShort());
			short version = Short.reverseBytes(in.readShort());
			int sectionCount = Integer.reverseBytes(in.readInt());
			int dataOffset = Integer.reverseBytes(in.readInt());
			int decompressedLength = Integer.reverseBytes(in.readInt());
			int compressedLength = Integer.reverseBytes(in.readInt());
			padding = 4; //version 4 has always nearest pad to 4, ver 6 can specify more

			in.seek(garcLength);

			long fatoPosition = in.getFilePointer();
			in.read(strbuf);
			String fatoMagic = new String(strbuf);
			int fatoLength = Integer.reverseBytes(in.readInt());
			short fatoEntries = Short.reverseBytes(in.readShort());
			length = fatoEntries;
			short pad = in.readShort(); //0xFFFF

			long fatbPosition = fatoPosition + fatoLength;
			for (int i = 0; i < fatoEntries; i++) {
				in.seek(fatoPosition + 0xc + i * 4);
				in.seek(Integer.reverseBytes(in.readInt()) + fatbPosition + 0xc);

				int flags = Integer.reverseBytes(in.readInt());

				String folder = "";

				if (flags != 1) {
					folder = String.format("folder_{0:D5}/", i);
				}

				for (int bit = 0; bit < 32; bit++) {
					if ((flags & (1 << bit)) > 0) {
						int startOffset = Integer.reverseBytes(in.readInt());
						int endOffset = Integer.reverseBytes(in.readInt());
						int length = Integer.reverseBytes(in.readInt());

						long position = in.getFilePointer();

						in.seek(startOffset + dataOffset);

						byte[] buffer = new byte[length];
						in.read(buffer);

						boolean isCompressed = allowCompression && sniffLZ11(buffer);

						GARCEntry entry = new GARCEntry();
						entry.offset = startOffset + dataOffset;
						entry.length = length;
						entry.compressed = isCompressed;
						entries.add(entry);

						in.seek(position);
					}
				}
			}
			in.close();
		} catch (IOException ex) {
			Logger.getLogger(GARC.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	/**
	 * Validating LZ11 sniff: first byte 0x11 AND the declared decompressed size
	 * (header bytes 1-3) must be plausible for the entry's stored length. A raw
	 * entry that merely STARTS with 0x11 (measured: trclass a/0/3/7 entry 122,
	 * which would otherwise "inflate" to 16.7 MB of garbage) almost always
	 * declares a wild size and is rejected.
	 */
	private static boolean sniffLZ11(byte[] buffer) {
		if (buffer.length < 4 || buffer[0] != 0x11) {
			return false;
		}
		int declared = (buffer[1] & 0xFF) | ((buffer[2] & 0xFF) << 8) | ((buffer[3] & 0xFF) << 16);
		return declared > 0 && declared < 0x400000 && declared <= Math.max(0x1000, buffer.length * 64);
	}

	public void packDirectory(File dir) throws IOException {
		packDirectory(dir, null);
	}

	/**
	 * Packs a workspace directory into this GARC. Entries that already exist in
	 * the archive keep their original compression flag. Appended entries (index
	 * beyond the original entry count) inherit the compression flag of the LAST
	 * original entry, unless compressionOverrides contains a Boolean for their
	 * index (keyed by the numeric file name in the pack directory).
	 */
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
	 * put it on, so nobody ever saw it. Collecting it here lets
	 * {@link Workspace#packArchives} hand it to the dialog that reports the
	 * pack, whichever archives the pack happened to touch.
	 */
	private static final ArrayList<String> packWarnings = new ArrayList<>();

	/** Takes everything packing has warned about since the last call. */
	public static ArrayList<String> drainPackWarnings() {
		ArrayList<String> out = new ArrayList<>(packWarnings);
		packWarnings.clear();
		return out;
	}

	public void packDirectory(File dir, Map<Integer, Boolean> compressionOverrides) throws IOException {
		if (!dir.isDirectory()) {
			return;
		}
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
		ArrayList<File> files = new ArrayList<>();
		files.addAll(Arrays.asList(dir.listFiles()));
		for (int i = 0; i < files.size(); i++) {
			if (!Workspace.persist_paths.contains(files.get(i).getAbsolutePath())) {
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
		int originalEntryCount = entries.size();
		int[] changedIndices = new int[files.size()];
		byte[][] compressedData = new byte[files.size()][];
		for (int i = 0; i < files.size(); i++) {
			changedIndices[i] = Integer.valueOf(files.get(i).getName());
			InputStream in = new FileInputStream(files.get(i));
			byte[] or = new byte[in.available()];
			in.read(or);
			in.close();
			//an explicit override wins for ANY slot (a zone insert-shift needs to
			//re-compress a slot whose original entry was uncompressed); otherwise
			//existing slots keep their entry's flag and appended slots inherit the
			//last original entry's flag
			Boolean override = (compressionOverrides != null) ? compressionOverrides.get(changedIndices[i]) : null;
			boolean compressed;
			if (override != null) {
				compressed = override;
			} else if (changedIndices[i] < originalEntryCount) {
				compressed = entries.get(changedIndices[i]).compressed;
			} else {
				compressed = entries.get(originalEntryCount - 1).compressed;
			}
			if (compressed) {
				compressedData[i] = LZ11.compress(or);
			} else {
				compressedData[i] = or;
			}
			if (changedIndices[i] > entries.size() - 1) {
				//An archive grows only at its tail. A file named past the tail
				//used to be renumbered to the first free slot without a word,
				//so an area fork's "229" landed at 228 and the zone that asked
				//for 229 could not load. The caller's number is the whole point
				//of the file - every reference to it in the other archives is
				//that number - so a gap is a refusal, not something to guess at.
				if (changedIndices[i] > entries.size()) {
					throw new IOException("Cannot pack " + files.get(i).getName() + " into "
							+ file.getPath() + ": the archive ends at index " + (entries.size() - 1)
							+ ", so that file would be written at index " + entries.size()
							+ " instead of " + changedIndices[i] + ". Whatever indexes this"
							+ " archive would read the wrong entry. Fill indices "
							+ entries.size() + ".." + (changedIndices[i] - 1) + " first.");
				}
				GARCEntry add = new GARCEntry();
				add.compressed = compressed;
				//pad-aligned provisional offset (the real table is re-read
				//from the packed file at the end of this method anyway)
				int prevEnd = entries.get(entries.size() - 1).offset + entries.get(entries.size() - 1).length;
				int rem = prevEnd % padding;
				add.offset = rem == 0 ? prevEnd : prevEnd + padding - rem;
				add.length = compressedData[i].length;
				entries.add(add);
			}
		}
		File newGARC = new File(Workspace.WORKSPACE_PATH + "/" + file.getName() + "_new");
		//get largest unpadded size
		int maxlength = 0;
		int[] filelengths = new int[compressedData.length];
		int[] padlengths = new int[compressedData.length];
		for (int i = 0; i < compressedData.length; i++) {
			int len = compressedData[i].length;
			filelengths[i] = len;
			int remainder = len % padding;
			int padLength = (remainder == 0) ? 0 : padding - remainder;
			padlengths[i] = padLength;
			if (len + padLength > maxlength) {
				maxlength = len + padLength;
			}
		}
		newGARC.delete();
		//The replacement is written beside the workspace and swapped in whole.
		//Whatever goes wrong on the way - the emulator or a virus scanner
		//holding the archive open is the usual case - must leave the archive
		//as it was, leave no half-written copy behind, and reach the caller.
		//This used to log the exception and return: the progress bar filled,
		//the game kept the old map, and the leaked streams made every retry
		//fail the same way.
		try (FileInputStream oldIn = new FileInputStream(file);
				RandomAccessFile dos = new RandomAccessFile(newGARC, "rw")) {
			LittleEndianDataInputStream old = new LittleEndianDataInputStream(oldIn);
			//first 14 bytes of header should be unchanged - let's copy paste them
			byte[] buf = new byte[16];
			old.read(buf);
			dos.write(buf);
			dos.writeInt(0); //TEMPORARY - will be replaced with data offset
			dos.writeInt(0); //TEMPORARY - will be replaced with file size
			old.skip(8);
			int lastMaxSize = old.readInt();
			dos.writeInt(Integer.reverseBytes(Math.max(lastMaxSize, maxlength))); //write either the max unpadded length of new or original files
			//FATO points to FATB - is unchanged
			//we need to read original FATO length
			int fatoMagic = old.readInt();
			int fatoLength = 0xC + entries.size() * 4;
			int fatoEntries = entries.size();
			old.readInt(); //FATO length
			int oldEntries = old.readShort(); //old FATO entries and padding
			old.readShort();
			old.skip(oldEntries * 4);
			dos.writeInt(Integer.reverseBytes(fatoMagic));
			dos.writeInt(Integer.reverseBytes(fatoLength));
			dos.writeShort(Short.reverseBytes((short)fatoEntries));
			dos.writeShort(0xFFFF);
			for (int i = 0; i < fatoEntries; i++){
				dos.writeInt(Integer.reverseBytes(i * 16)); //16 bytes is offset in FATB. We are only implementing simple GARCs without directories and stuff, so we don't care about accuracy.
			}
			//we are at the beginning of FATB in both the original stream and the new file
			//we now need to shift the offsets of all files
			//first we just rewrite the magic, length and entry count
			dos.writeInt(Integer.reverseBytes(old.readInt())); //magic
			old.readInt();
			dos.writeInt(Integer.reverseBytes(entries.size() * 16)); //FATB length
			int oldEntryCount = old.readInt();
			int entryCount = entries.size();
			dos.writeInt(Integer.reverseBytes(entryCount)); //FATB entry count
			FATBEntry[] fatbe = new FATBEntry[entryCount];
			int lastOld = 0;
			for (int i = 0; i < oldEntryCount; i++) {
				fatbe[i] = new FATBEntry();
				fatbe[i].flags = old.readInt();
				fatbe[i].offset = old.readInt();
				fatbe[i].endOffset = old.readInt();
				fatbe[i].len = old.readInt();
				lastOld = i;
			}
			//end of FATB for original
			int baseOffset = fatbe[lastOld].endOffset;
			for (int i = lastOld + 1; i < entryCount; i++){
				fatbe[i] = new FATBEntry();
				fatbe[i].flags = fatbe[0].flags;
				fatbe[i].offset = baseOffset;
				fatbe[i].endOffset = baseOffset + entries.get(i).length;
				if (fatbe[i].endOffset % 4 != 0) {
					fatbe[i].endOffset += 4 - (fatbe[i].endOffset % 4); //padding
				}
				fatbe[i].len = entries.get(i).length;
				baseOffset = fatbe[i].endOffset;
			}
			int offsetShift = 0;
			int processedCustomFiles = 0;
			for (int i = 0; i < fatbe.length; i++) {
				FATBEntry e = fatbe[i];
				if (indexOfIntArray(changedIndices, i) != -1) {
					//write changed file info
					dos.writeInt(Integer.reverseBytes(e.flags));
					dos.writeInt(Integer.reverseBytes(e.offset + offsetShift));
					int endOffset = e.offset + offsetShift + filelengths[processedCustomFiles] + padlengths[processedCustomFiles];
					dos.writeInt(Integer.reverseBytes(endOffset));
					dos.writeInt(Integer.reverseBytes(filelengths[processedCustomFiles]));
					offsetShift += endOffset - (e.endOffset + offsetShift);
					processedCustomFiles++;
				} else {
					dos.writeInt(Integer.reverseBytes(e.flags));
					dos.writeInt(Integer.reverseBytes(e.offset + offsetShift));
					dos.writeInt(Integer.reverseBytes(e.endOffset + offsetShift));
					dos.writeInt(Integer.reverseBytes(e.len));
				}
			}
			buf = new byte[8];
			old.read(buf);
			dos.write(buf);
			//written static part of FIMB
			//the last one is data length - we need it as the last thing ever written, so we dummy it out and mark the position
			int dataLengthPos = (int) dos.getFilePointer();
			dos.writeInt(0);
			//we are at data now, let's write it in
			processedCustomFiles = 0;
			for (int i = 0; i < fatbe.length; i++) {
				FATBEntry e = fatbe[i];
				if (indexOfIntArray(changedIndices, i) != -1) {
					dos.write(compressedData[processedCustomFiles]);
					for (int j = 0; j < padlengths[processedCustomFiles]; j++) {
						dos.write(0xFF);
					}
					processedCustomFiles++;
				} else {
					try (InputStream entryReader = new FileInputStream(file)) {
						byte[] b = new byte[entries.get(i).length];
						entryReader.skip(entries.get(i).offset);
						entryReader.read(b);
						dos.write(b);
						int remainder = b.length % padding;
						int padLength = (remainder == 0) ? 0 : padding - remainder;
						for (int j = 0; j < padLength; j++) {
							dos.write(0xFF);
						}
					}
				}
			}
			int totalLength = (int) dos.length();
			int dataLength = totalLength - (dataLengthPos + 4); //dLP is the offset in FIMB after which the data follows
			dos.seek(0x10);
			dos.writeInt(Integer.reverseBytes(dataLengthPos + 4));
			dos.writeInt(Integer.reverseBytes(totalLength));
			dos.seek(dataLengthPos);
			dos.writeInt(Integer.reverseBytes(dataLength));
		} catch (IOException ex) {
			newGARC.delete();
			throw ex;
		}
		try {
			Files.move(newGARC.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ex) {
			newGARC.delete();
			throw new IOException("Could not replace " + file.getName() + " - is the emulator or another"
					+ " program holding it open? (" + ex.getMessage() + ")", ex);
		}
		//keep THIS instance coherent with the file it just wrote - a stale
		//entry table on a repeat pack copies unchanged entries from wrong
		//offsets and silently corrupts them
		parse(file);
	}

	/**
	 * The entry's bytes exactly as STORED in the archive (still LZ11-compressed
	 * when the entry is). Lets a rebuild preserve entries byte-for-byte without
	 * a decompress/recompress round trip.
	 */
	public byte[] getStoredEntry(int num) {
		try {
			GARCEntry e = entries.get(num);
			byte[] b = new byte[e.length];
			InputStream in = new FileInputStream(file);
			in.skip(e.offset);
			in.read(b);
			in.close();
			return b;
		} catch (IOException ex) {
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

	public byte[] getDecompressedEntry(int num) {
		if (num >= entries.size()) {
			return null;
		}
		try {
			LittleEndianDataInputStream dis = new LittleEndianDataInputStream(new FileInputStream(file));
			dis.skip(entries.get(num).offset);
			byte[] data = new byte[entries.get(num).length];
			dis.read(data);
			dis.close();
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

	public static int indexOfIntArray(int[] array, int key) {
		int returnvalue = -1;
		for (int i = 0; i < array.length; ++i) {
			if (key == array[i]) {
				returnvalue = i;
				break;
			}
		}
		return returnvalue;
	}

	public static void main(String[] args) throws IOException {
		GARC garc = new GARC(new File("1"));
		garc.packDirectory(new File("1_pack"));
	}
}

class GARCEntry {

	int offset;
	int length;
	boolean compressed;
}

class FATBEntry {

	int flags;
	int offset;
	int endOffset;
	int len;
}
