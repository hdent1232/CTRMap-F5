package ctrmap.formats.containers;

import java.util.Arrays;
import static ctrmap.formats.LittleEndian.i32;
import static ctrmap.formats.LittleEndian.u16;

/**
 * Reads a GameFreak mini-pack (AD, GR, ZO, MM, BM...) that is already in
 * memory, without writing it to disk first.
 *
 * <p>WHY THIS EXISTS. {@link AbstractGamefreakContainer} is file-backed: it is
 * the right shape for a workspace where every container is an unpacked file
 * that can be edited in place. It is the wrong shape for everything that
 * walks a GARC - a harvester, an integrity scan, a test - and holds each entry
 * as a byte[] it will read once and drop. Those callers each wrote their own
 * eight-line slicer; there were thirty-four of them (seven in the program,
 * twenty-seven in the battery) and they had drifted on what a corrupt table
 * or an empty subfile should answer. Now they answer this.
 *
 * <p>The layout, so nobody has to re-derive it again:
 * <pre>
 *   u16  magic     two ASCII capitals, e.g. "GR"
 *   u16  count     number of subfiles
 *   u32  offset[count + 1]   from the start of the container; the last is the end
 *   ...  subfile data, offsets padded to 0x80 in the padded kinds
 * </pre>
 *
 * <p>What a caller gets back: the bytes of subfile {@code i}, an EMPTY array
 * for a subfile the table says is zero bytes long, and null for anything the
 * table cannot honestly answer - no container, an index outside the count, an
 * offset table cut short, or offsets that run backwards or past the end. It
 * never throws for a malformed container; a reader that wants to complain
 * about one still can, because null is unambiguous. Nothing here checks the
 * magic: the kinds share a layout and a caller that cares about the letters
 * looks at them itself.
 */
public final class ContainerBytes {

	/** Bytes before the offset table: magic and count. */
	private static final int HEADER = 4;

	private ContainerBytes() {
	}

	/** How many subfiles the header declares; 0 for nothing that has a header. */
	public static int count(byte[] container) {
		if (container == null || container.length < HEADER) {
			return 0;
		}
		return u16(container, 2);
	}

	/**
	 * Subfile {@code i}, or null when the container cannot answer for it - see
	 * the class comment for exactly when. An empty subfile is an empty array.
	 */
	public static byte[] subfile(byte[] container, int i) {
		if (i < 0 || i >= count(container)) {
			return null;
		}
		//offsets i and i+1 must both be inside the buffer before they are read
		int tableEnd = HEADER + (i + 2) * 4;
		if (tableEnd > container.length) {
			return null;
		}
		int from = i32(container, HEADER + i * 4);
		int to = i32(container, HEADER + (i + 1) * 4);
		if (from < 0 || to < from || to > container.length) {
			return null;
		}
		return Arrays.copyOfRange(container, from, to);
	}
}
