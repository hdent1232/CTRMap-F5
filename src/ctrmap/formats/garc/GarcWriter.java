package ctrmap.formats.garc;

import java.io.IOException;
import java.io.RandomAccessFile;
import static ctrmap.util.LittleEndian.i32;

/**
 * The one writer of a GARC's header, FATO, FATB and FIMB (F056): the repack ({@link GARC}, which
 * replaces and appends) and the exact rebuild ({@link GarcRebuilder}, which may shrink) both write
 * through it, so a convention fixed here is fixed for both.
 *
 * <p>There were two, agreeing on every convention by hand, and both wrote the FATB's length field
 * as 16 bytes per entry - twelve short of the section, whose own twelve-byte header the length
 * counts - so the rebuild could not trust its own output's field and computed the position
 * instead. The length written now is the section's, so an archive's FATB length reaches its own
 * FIMB; the reader still finds every position from the counts, so an archive written the old way
 * reads as before.
 *
 * <p>It opens no file: each caller hands it the handles it opened, so the classes that write an
 * archive are the two that always did.
 *
 * <p>Not: {@link GARC} - that reads an archive and plans a repack, and {@link GarcRebuilder} - that
 * chooses an exact list; the bytes of the tables are this one's job for both.
 */
final class GarcWriter {

	private GarcWriter() {
	}

	/** What a written archive holds, entry by entry. */
	interface Entries {

		int count();

		/** The entry's FATB flags word - 1 for one file. */
		int flags(int i);

		/** Its stored length in bytes. */
		int length(int i);

		/** Whether its bytes are new rather than copied from the archive the template was read from. */
		boolean fresh(int i);

		/** Writes its stored bytes, exactly {@link #length} of them. */
		void write(int i, RandomAccessFile out) throws IOException;
	}

	/** The conventions a written archive takes from the one it replaces or is modelled on. */
	static final class Template {

		/** Magic, header length, byte order mark, version and section count: carried verbatim. */
		final byte[] head = new byte[16];
		final byte[] fatoMagic = new byte[4];
		final byte[] fatbMagic = new byte[4];
		/** The FIMB's magic and header length. */
		final byte[] fimbHead = new byte[8];
		/** The header's largest-entry hint, which the game reads as a buffer size: never shrunk. */
		int largest;
		/** Each entry's FATB flags word. */
		int[] flags;

		/** The first entry's flags, the ones an appended entry takes; one file when there is none. */
		int firstFlags() {
			return flags.length > 0 ? flags[0] : 1;
		}

		/**
		 * Reads the conventions from an open archive. Every position is computed from the counts -
		 * never from the FATB length field, which archives written before F056 carry twelve short.
		 */
		static Template read(RandomAccessFile in) throws IOException {
			Template t = new Template();
			in.seek(0);
			in.readFully(t.head);
			in.skipBytes(8); //data offset, file size
			t.largest = Integer.reverseBytes(in.readInt());
			int fatoPos = i32(t.head, 4); //the header length is where FATO starts
			in.seek(fatoPos);
			in.readFully(t.fatoMagic);
			int fatoLength = Integer.reverseBytes(in.readInt());
			long fatbPos = fatoPos + (long) fatoLength;
			in.seek(fatbPos);
			in.readFully(t.fatbMagic);
			in.skipBytes(4); //the FATB length field, not trusted
			int count = Integer.reverseBytes(in.readInt());
			t.flags = new int[count];
			for (int i = 0; i < count; i++) {
				in.seek(fatbPos + 0xC + i * 16L);
				t.flags[i] = Integer.reverseBytes(in.readInt());
			}
			in.seek(fatbPos + 0xC + count * 16L);
			in.readFully(t.fimbHead);
			return t;
		}
	}

	/**
	 * Writes the archive from its first byte into {@code out}, which is emptied first: each entry
	 * padded with 0xFF to {@code padding}, the FATB offsets running on from the lengths, and the
	 * largest-entry hint raised to the largest fresh entry, never lowered.
	 */
	static void write(RandomAccessFile out, Template t, Entries e, int padding) throws IOException {
		int n = e.count();
		int[] pad = new int[n];
		int largest = t.largest;
		for (int i = 0; i < n; i++) {
			int len = e.length(i);
			pad[i] = (padding - len % padding) % padding;
			if (e.fresh(i)) {
				largest = Math.max(largest, len + pad[i]);
			}
		}
		out.setLength(0);
		out.seek(0);
		out.write(t.head);
		out.writeInt(0); //data offset - finalised below
		out.writeInt(0); //file size - finalised below
		out.writeInt(Integer.reverseBytes(largest));
		//FATO: one offset into the FATB per entry
		out.write(t.fatoMagic);
		out.writeInt(Integer.reverseBytes(0xC + n * 4));
		out.writeShort(Short.reverseBytes((short) n));
		out.writeShort(0xFFFF);
		for (int i = 0; i < n; i++) {
			out.writeInt(Integer.reverseBytes(i * 16));
		}
		//FATB: the section's length, its twelve-byte header included, as the format defines it
		out.write(t.fatbMagic);
		out.writeInt(Integer.reverseBytes(0xC + n * 16));
		out.writeInt(Integer.reverseBytes(n));
		int off = 0;
		for (int i = 0; i < n; i++) {
			int end = off + e.length(i) + pad[i];
			out.writeInt(Integer.reverseBytes(e.flags(i)));
			out.writeInt(Integer.reverseBytes(off));
			out.writeInt(Integer.reverseBytes(end));
			out.writeInt(Integer.reverseBytes(e.length(i)));
			off = end;
		}
		//FIMB, its data length written last
		out.write(t.fimbHead);
		long dataLengthPos = out.getFilePointer();
		out.writeInt(0);
		for (int i = 0; i < n; i++) {
			long before = out.getFilePointer();
			e.write(i, out);
			if (out.getFilePointer() - before != e.length(i)) {
				throw new IOException("entry " + i + " wrote " + (out.getFilePointer() - before) + " bytes, not the "
						+ e.length(i) + " its FATB record says");
			}
			for (int j = 0; j < pad[i]; j++) {
				out.write(0xFF);
			}
		}
		int total = (int) out.length();
		out.seek(0x10);
		out.writeInt(Integer.reverseBytes((int) dataLengthPos + 4));
		out.writeInt(Integer.reverseBytes(total));
		out.seek(dataLengthPos);
		out.writeInt(Integer.reverseBytes(total - ((int) dataLengthPos + 4)));
	}
}
