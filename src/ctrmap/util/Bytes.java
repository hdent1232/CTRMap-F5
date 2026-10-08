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
	 * Writes {@code data} beside {@code f} and then moves it over, so a write that fails leaves the
	 * file as it was rather than cut short - the rule the containers follow (F048), for a file that
	 * is not one.
	 */
	public static void replaceFile(java.io.File f, byte[] data) throws java.io.IOException {
		java.io.File tmp = java.io.File.createTempFile("ctrmap-" + f.getName() + ".", ".writing",
				f.getAbsoluteFile().getParentFile());
		try {
			java.nio.file.Files.write(tmp.toPath(), data);
			moveOver(tmp, f);
			tmp = null;
		} finally {
			if (tmp != null) {
				tmp.delete();
			}
		}
	}

	/**
	 * Moves a file written beside {@code to} over it, atomically where the file system can: the one
	 * move a staged write ends with - {@link #replaceFile}'s, and a vault restore's (F140).
	 */
	public static void moveOver(java.io.File from, java.io.File to) throws java.io.IOException {
		try {
			java.nio.file.Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
					java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
			java.nio.file.Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
	}

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
		String said = m == null || m.trim().isEmpty() ? ex.toString() : m;
		//WHAT RODE ON IT IS SAID TOO. A unit of work that cannot put a file back attaches that to
		//the operation's own exception, and WorkspaceUnit's contract was that this method says
		//it - it did not, so a half-written workspace read as an ordinary refusal to every one of
		//this method's callers
		for (Throwable rider : ex.getSuppressed()) {
			said += " - and " + reason(rider);
		}
		return said;
	}

	private Bytes() {
	}

	/**
	 * {@code v} rounded up to a multiple of {@code a} - the one copy (F066: the texture pack, the
	 * model appender and the area animation each had their own).
	 *
	 * @throws IllegalArgumentException for a boundary that is not positive
	 */
	public static int align(int v, int a) {
		if (a <= 0) {
			throw new IllegalArgumentException("Cannot align to a boundary of " + a + ".");
		}
		return (v + a - 1) / a * a;
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
	 * value. False if the file is shorter than two bytes; a file that cannot be read
	 * THROWS (F211): it answered false, so a zone the user picked and the system would
	 * not open was reported as "not a zone mini-pack".
	 */
	public static boolean checkMagicLE16(File f, int magic) throws IOException {
		try (FileInputStream in = new FileInputStream(f)) {
			int lo = in.read(), hi = in.read();
			return hi >= 0 && u16(new byte[]{(byte) lo, (byte) hi}, 0) == magic;
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
