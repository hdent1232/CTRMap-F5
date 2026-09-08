package ctrmap.formats;

/**
 * Little-endian integers and floats inside a byte array - decoded in one place.
 *
 * <p>WHY THIS EXISTS. Every format the 3DS games ship is little-endian, and
 * until this class existed each reader decoded its own: sixty-two private
 * copies of these four-line functions across thirty-five files, the same
 * signed 32-bit read under five different names ({@code u32}, {@code le32},
 * {@code i32}, {@code readIntLE}, {@code readS32}), and one file carrying two
 * of them. None was wrong. None was tested either, and a reader that grows a
 * sixth spelling is a reader nobody can grep for. The names here say the width
 * and the signedness of the value, which is what a format table calls it.
 *
 * <p>These read and write exactly their width and nothing else, and they throw
 * {@link ArrayIndexOutOfBoundsException} for an offset past the end, as the
 * copies did. A reader that must survive a truncated file checks the length
 * first - {@link ctrmap.formats.containers.ContainerBytes} is the example.
 *
 * <p>Big-endian tile ids are a different family: see {@code ctrmap.util.Bytes.ba2int}.
 * Stream-shaped reads are {@code LittleEndianDataInputStream}.
 */
public final class LittleEndian {

	private LittleEndian() {
	}

	/** Unsigned 16-bit value at {@code o}: 0..65535, never negative. */
	public static int u16(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
	}

	/** Signed 32-bit value at {@code o} - the four bytes as Java's int. */
	public static int i32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}

	/** Unsigned 32-bit value at {@code o}: 0..4294967295, never negative. */
	public static long u32(byte[] b, int o) {
		return i32(b, o) & 0xFFFFFFFFL;
	}

	/** IEEE single at {@code o}. */
	public static float f32(byte[] b, int o) {
		return Float.intBitsToFloat(i32(b, o));
	}

	/** Stores the low 16 bits of {@code v} at {@code o}, low byte first. */
	public static void putU16(byte[] b, int o, int v) {
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
	}

	/** Stores {@code v} at {@code o}, low byte first. */
	public static void putI32(byte[] b, int o, int v) {
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
		b[o + 2] = (byte) (v >> 16);
		b[o + 3] = (byte) (v >> 24);
	}

	/** Stores {@code v}'s IEEE bits at {@code o}, low byte first. */
	public static void putF32(byte[] b, int o, float v) {
		putI32(b, o, Float.floatToIntBits(v));
	}
}
