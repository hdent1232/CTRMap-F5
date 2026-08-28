package ctrmap.formats.garc;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Writes a fresh GARC containing EXACTLY the given stored entries - the
 * missing half of {@link GARC#packDirectory}, which can replace and append but
 * never shrink. Entry bytes are taken as-stored (compressed entries stay
 * compressed, byte-for-byte), and the layout mirrors packDirectory's
 * game-validated conventions verbatim: same header field usage, FATO/FATB
 * shapes, 4-byte 0xFF entry padding, and the max-size hint never shrinking
 * below the template's (the game reads it as a buffer-size hint; larger is
 * safe). The template provides the header/magic/flag conventions and may be
 * the same file as the output.
 */
public class GarcRebuilder {

	public static void write(File template, File out, List<byte[]> stored) throws IOException {
		byte[] head = new byte[16];
		byte[] fatoMagic = new byte[4], fatbMagic = new byte[4], fimbHead = new byte[8];
		int templMax, flags0;
		try (RandomAccessFile in = new RandomAccessFile(template, "r")) {
			in.readFully(head);
			in.skipBytes(8); // dataOffset, fileSize
			templMax = Integer.reverseBytes(in.readInt());
			int fatoPos = le32(head, 4); // header length = FATO position
			in.seek(fatoPos);
			in.readFully(fatoMagic);
			int fatoLength = Integer.reverseBytes(in.readInt());
			long fatbPos = fatoPos + fatoLength;
			in.seek(fatbPos);
			in.readFully(fatbMagic);
			in.skipBytes(4); // FATB length field (quirky in our own output; recompute position instead)
			int templCount = Integer.reverseBytes(in.readInt());
			flags0 = Integer.reverseBytes(in.readInt());
			in.seek(fatbPos + 0xC + templCount * 16L);
			in.readFully(fimbHead);
		}

		int n = stored.size();
		int[] padlen = new int[n];
		int maxlen = 0;
		for (int i = 0; i < n; i++) {
			int len = stored.get(i).length;
			int rem = len % 4;
			padlen[i] = rem == 0 ? 0 : 4 - rem;
			maxlen = Math.max(maxlen, len + padlen[i]);
		}

		File tmp = new File(out.getAbsolutePath() + "_rebuild");
		tmp.delete();
		try (RandomAccessFile dos = new RandomAccessFile(tmp, "rw")) {
			dos.write(head);
			dos.writeInt(0); // dataOffset - finalized below
			dos.writeInt(0); // fileSize - finalized below
			dos.writeInt(Integer.reverseBytes(Math.max(templMax, maxlen)));
			// FATO
			dos.write(fatoMagic);
			dos.writeInt(Integer.reverseBytes(0xC + n * 4));
			dos.writeShort(Short.reverseBytes((short) n));
			dos.writeShort(0xFFFF);
			for (int i = 0; i < n; i++) {
				dos.writeInt(Integer.reverseBytes(i * 16));
			}
			// FATB (length field = n*16, matching packDirectory's convention)
			dos.write(fatbMagic);
			dos.writeInt(Integer.reverseBytes(n * 16));
			dos.writeInt(Integer.reverseBytes(n));
			int off = 0;
			for (int i = 0; i < n; i++) {
				int end = off + stored.get(i).length + padlen[i];
				dos.writeInt(Integer.reverseBytes(flags0));
				dos.writeInt(Integer.reverseBytes(off));
				dos.writeInt(Integer.reverseBytes(end));
				dos.writeInt(Integer.reverseBytes(stored.get(i).length));
				off = end;
			}
			// FIMB header + data-length placeholder
			dos.write(fimbHead);
			long dataLengthPos = dos.getFilePointer();
			dos.writeInt(0);
			for (int i = 0; i < n; i++) {
				dos.write(stored.get(i));
				for (int j = 0; j < padlen[i]; j++) {
					dos.write(0xFF);
				}
			}
			int totalLength = (int) dos.length();
			dos.seek(0x10);
			dos.writeInt(Integer.reverseBytes((int) dataLengthPos + 4));
			dos.writeInt(Integer.reverseBytes(totalLength));
			dos.seek(dataLengthPos);
			dos.writeInt(Integer.reverseBytes(totalLength - ((int) dataLengthPos + 4)));
		}
		Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}

	private static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
