package ctrmap.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import static ctrmap.formats.LittleEndian.u16;

/**
 * Reading and comparing raw data: padding, magic numbers, a word, a trim.
 *
 * <p>WHY THIS EXISTS. These lived in {@code ctrmap.Utils}, a class whose own
 * javadoc said it was "methods used between various classes that do not extend
 * the same abstract base" - five unrelated groups in one file: these byte
 * helpers, two dialog wrappers, three Swing form helpers, the 3D picking
 * maths, and a directory helper. Every one of them was reachable from
 * everywhere, so the format layer depended on a class that opened dialogs, and
 * a guard that wanted to say "the format layer may use the pure helpers and
 * nothing else" had to name them one by one.
 *
 * <p>What is here needs no game, no workspace, no window and no display. That
 * is the whole rule, and it is why the format layer may use this class freely.
 *
 * <p>It does read one thing from below it: {@code LittleEndian.u16}, which is
 * the same kind of class and belongs beside this one. Moving it would drag
 * every format reader's imports with it, so it is left where it is and named
 * here rather than duplicated - the byte readers were deduplicated from 57 to
 * 3 in an earlier pass and a fourth would undo that.
 */
public final class Bytes {

	/**
	 * What went wrong, in words: the exception's message, or its type when it gave none.
	 *
	 * <p>HERE BECAUSE THE FORMAT LAYER MAY NOT SPEAK TO THE UI. Ui.reason has said this
	 * for the window side for a long time, and when the container and GARC readers
	 * started naming their failures they reached for it - which SourceSeamTest caught,
	 * correctly: a format class is handed what it needs and answers in its return value.
	 * The alternative was a second copy of three lines, which is the other thing this
	 * tree is being cleared of, so the function moved somewhere both layers may stand.
	 */
	public static String reason(Throwable ex) {
		String m = ex.getMessage();
		return m == null || m.trim().isEmpty() ? ex.toString() : m;
	}

	private Bytes() {
	}

	/** The zero bytes that pad a subfile of {@code length} at {@code offsetInPack} to a 128-byte boundary. */
	public static byte[] getPadding(int offsetInPack, int length) {
		int endingOffset = (int) Math.ceil((offsetInPack + length) / 128f) * 128;
		return new byte[endingOffset - offsetInPack - length];
	}

	/** The first four bytes as a big-endian int. */
	public static int ba2int(byte[] b) {
		int x = b[0];
		x = (x << 8) | (b[1] & 0xFF);
		x = (x << 8) | (b[2] & 0xFF);
		x = (x << 8) | (b[3] & 0xFF);
		return x;
	}

	/** Two floats equal to within a tenth, which is the tolerance a model's vertices are written to. */
	public static boolean impreciseFloatEquals(float f0, float f1) {
		return Math.abs(f0 - f1) < 0.1f;
	}

	/**
	 * Compares the first two bytes of a file against a little endian 16bit magic
	 * value. Returns false if the file is too short or unreadable.
	 */
	public static boolean checkMagicLE16(File f, int magic) {
		FileInputStream in = null;
		try {
			in = new FileInputStream(f);
			byte[] b = new byte[2];
			if (in.read(b) != 2) {
				return false;
			}
			return u16(b, 0) == magic;
		} catch (IOException ex) {
			return false;
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (IOException ex) {
				}
			}
		}
	}

	/** True when the data starts with the three bytes of a BCH container. */
	public static boolean checkBCHMagic(byte[] data) {
		if (data.length < 3) {
			return false;
		}
		if (data[0] == 'B' && data[1] == 'C' && data[2] == 'H') {
			return true;
		} else {
			return false;
		}
	}

	/** True when the data starts with {@code magic}, and false rather than throwing when it is shorter. */
	public static boolean checkMagic(byte[] data, String magic) {
		if (magic.length() > data.length) {
			return false;
		}
		byte[] test = Arrays.copyOfRange(data, 0, magic.length());
		return new String(test).equals(magic);
	}

	/** True for an ASCII capital, which is how a GameFreak container's identifier is spelled. */
	public static boolean isUTF8Capital(byte check) {
		return (check & 0xFF) >= 0x41 && (check & 0xFF) <= 0x5a;
	}

	/** The data up to its last non-zero byte, rounded up to a word. */
	public static byte[] getTrimmedArray(byte[] in) {
		for (int i = in.length - 1; i > 0; i--) {
			if (in[i] != 0) {
				int finalIndex = i + (4 - (i % 4));
				byte[] ret = new byte[finalIndex];
				System.arraycopy(in, 0, ret, 0, finalIndex);
				return ret;
			}
		}
		return new byte[0];
	}
}
