
package ctrmap.formats.h3d;

import java.io.IOException;

/**
 * The NUL-terminated strings of the 3DS formats, read one way (F066).
 *
 * <p>Seven copies of this loop lived in the BCH, area-animation and prop readers, with two
 * contracts: one refused a name that ran off the end of its file, the other six handed back
 * whatever bytes were there as the name. And a stream reader looped forever on a string
 * nothing terminated, because a stream's end reads as -1, which is not 0 - deleted since, with
 * nothing in the program reading a string from a stream (F204). A string that does not
 * end inside its data is refused here, in words, by every reader.
 */
public class StringUtils {

	/** The string at {@code address} in {@code b}; see {@link #cString}. */
	public static String readString(int address, byte[] b) throws IOException {
		try {
			return cString(b, address);
		} catch (IllegalArgumentException ex) {
			throw new IOException(ctrmap.util.Bytes.reason(ex), ex);
		}
	}

	/**
	 * The Latin-1 string at {@code off} in {@code b}, up to its NUL.
	 *
	 * @throws IllegalArgumentException when {@code off} is outside {@code b}, or the string runs off
	 * the end of it with no NUL
	 */
	public static String cString(byte[] b, int off) {
		if (b == null || off < 0 || off >= b.length) {
			throw new IllegalArgumentException("A string at byte " + off + " runs off the end of the data ("
					+ (b == null ? "absent" : b.length + " bytes") + ").");
		}
		StringBuilder sb = new StringBuilder();
		for (int p = off; ; p++) {
			if (p >= b.length) {
				throw new IllegalArgumentException("The string at byte " + off + " runs off the end of the data ("
						+ b.length + " bytes) with no NUL to end it.");
			}
			if (b[p] == 0) {
				return sb.toString();
			}
			sb.append((char) (b[p] & 0xFF));
		}
	}
}
