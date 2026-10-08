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
 * compressed, byte-for-byte). The tables are written by {@link GarcWriter},
 * the repack's own writer (F056), with the template's header, magics and first
 * entry's flags, 4-byte 0xFF entry padding, and the max-size hint never
 * shrinking below the template's (the game reads it as a buffer-size hint;
 * larger is safe). The template may be the same file as the output.
 */
public class GarcRebuilder {

	public static void write(File template, File out, final List<byte[]> stored) throws IOException {
		final GarcWriter.Template t;
		try (RandomAccessFile in = new RandomAccessFile(template, "r")) {
			t = GarcWriter.Template.read(in);
		}
		File tmp = new File(out.getAbsolutePath() + "_rebuild");
		tmp.delete();
		try (RandomAccessFile dos = new RandomAccessFile(tmp, "rw")) {
			GarcWriter.write(dos, t, new GarcWriter.Entries() {
				@Override
				public int count() {
					return stored.size();
				}

				@Override
				public int flags(int i) {
					return t.firstFlags();
				}

				@Override
				public int length(int i) {
					return stored.get(i).length;
				}

				@Override
				public boolean fresh(int i) {
					return true;
				}

				@Override
				public void write(int i, RandomAccessFile to) throws IOException {
					to.write(stored.get(i));
				}
			}, 4);
		}
		Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}
}
