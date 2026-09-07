package ctrmap.tests;

import ctrmap.formats.containers.ContainerBytes;
import java.util.Arrays;
import static ctrmap.formats.LittleEndian.putI32;
import static ctrmap.formats.LittleEndian.putU16;

/**
 * The in-memory mini-pack reader every GARC walker now shares.
 *
 * <p>Thirty-four private copies of this slicer were folded into
 * {@link ContainerBytes}. The suites that went through the copies still run
 * against real containers; what none of them ever asked is what the slicer
 * answers for a container that is wrong - cut short, an index past the count,
 * an offset table that runs backwards - because a retail dump has no such
 * container. This suite hands it those, and pins that the honest answer is
 * null rather than an exception or a slice of the wrong bytes.
 *
 * Usage: java ctrmap.tests.ContainerBytesTest
 */
public class ContainerBytesTest {

	static int fails = 0;

	public static void main(String[] args) {
		byte[] c = pack(new byte[][]{{1, 2, 3}, {}, {9, 8, 7, 6, 5}});
		wellFormed(c);
		outsideTheTable(c);
		malformed(c);
		nothingAtAll();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** A mini-pack with the given subfiles, magic "GR", offsets padded to 0x80 like the real ones. */
	static byte[] pack(byte[][] subs) {
		int header = 4 + (subs.length + 1) * 4;
		int[] off = new int[subs.length + 1];
		int at = pad(header);
		for (int i = 0; i < subs.length; i++) {
			off[i] = at;
			at = pad(at + subs[i].length);
		}
		off[subs.length] = at;
		byte[] c = new byte[at];
		c[0] = 'G';
		c[1] = 'R';
		putU16(c, 2, subs.length);
		for (int i = 0; i <= subs.length; i++) {
			putI32(c, 4 + i * 4, off[i]);
		}
		for (int i = 0; i < subs.length; i++) {
			System.arraycopy(subs[i], 0, c, off[i], subs[i].length);
		}
		return c;
	}

	static int pad(int n) {
		return (n + 0x7F) & ~0x7F;
	}

	static void wellFormed(byte[] c) {
		check(ContainerBytes.count(c) == 3, "count reads the u16 at offset 2: " + ContainerBytes.count(c));
		byte[] s0 = ContainerBytes.subfile(c, 0);
		//a padded container: the slice runs to the next subfile's offset, padding included
		check(s0 != null && s0.length == 0x80 && s0[0] == 1 && s0[1] == 2 && s0[2] == 3 && s0[3] == 0,
				"subfile 0 is its bytes up to the next offset: " + (s0 == null ? "null" : s0.length + " bytes"));
		byte[] s1 = ContainerBytes.subfile(c, 1);
		check(s1 != null && s1.length == 0, "a subfile the table says is empty is an empty array, not null");
		byte[] s2 = ContainerBytes.subfile(c, 2);
		check(s2 != null && s2[0] == 9 && s2[4] == 5, "the last subfile runs to the end offset");
		check(s2 != null && s2.length == c.length - 0x100, "the last subfile ends where the container does");

		//an unpadded one, the shape a packer that wrote no padding produces
		byte[] tight = new byte[4 + 3 * 4 + 5];
		tight[0] = 'Z';
		tight[1] = 'O';
		putU16(tight, 2, 2);
		putI32(tight, 4, 16);
		putI32(tight, 8, 18);
		putI32(tight, 12, 21);
		for (int i = 16; i < 21; i++) {
			tight[i] = (byte) (0x40 + i);
		}
		byte[] t0 = ContainerBytes.subfile(tight, 0), t1 = ContainerBytes.subfile(tight, 1);
		check(t0 != null && t0.length == 2 && t0[0] == 0x50 && t1 != null && t1.length == 3 && t1[2] == 0x54,
				"offsets are honoured exactly when nothing is padded");
	}

	static void outsideTheTable(byte[] c) {
		check(ContainerBytes.subfile(c, 3) == null, "an index equal to the count is null, not an exception");
		check(ContainerBytes.subfile(c, 99) == null, "an index far past the count is null");
		check(ContainerBytes.subfile(c, -1) == null, "a negative index is null");
	}

	/** Every way a container can lie, and the reader must not believe it. */
	static void malformed(byte[] c) {
		//the count claims more subfiles than the offset table holds
		byte[] tall = c.clone();
		putU16(tall, 2, 200);
		check(ContainerBytes.count(tall) == 200, "count repeats what the header says even when it is wrong");
		check(ContainerBytes.subfile(tall, 150) == null, "an index whose offsets would lie past the end is null, not an exception");
		check(Arrays.equals(ContainerBytes.subfile(tall, 0), ContainerBytes.subfile(c, 0)),
				"...but the subfiles the table does hold still read");

		//cut the container off inside the offset table
		byte[] cut = Arrays.copyOf(c, 10);
		check(ContainerBytes.subfile(cut, 0) == null, "a container cut off inside its offset table is null for every index");

		//an end offset past the buffer
		byte[] past = c.clone();
		putI32(past, 4 + 3 * 4, c.length + 1);
		check(ContainerBytes.subfile(past, 2) == null, "an offset past the end is null");
		check(ContainerBytes.subfile(past, 1) != null, "...and only the subfile it bounds is affected");

		//offsets that run backwards
		byte[] back = c.clone();
		putI32(back, 4 + 1 * 4, 0x200);
		check(ContainerBytes.subfile(back, 0) == null, "a subfile whose end is before its start is null");
		check(ContainerBytes.subfile(back, 1) == null, "...and so is the neighbour that shares the bad offset");

		//a negative start offset
		byte[] neg = c.clone();
		putI32(neg, 4, -4);
		check(ContainerBytes.subfile(neg, 0) == null, "a negative offset is null");
	}

	static void nothingAtAll() {
		check(ContainerBytes.count(null) == 0 && ContainerBytes.subfile(null, 0) == null, "no container: count 0, subfile null");
		check(ContainerBytes.count(new byte[0]) == 0 && ContainerBytes.subfile(new byte[0], 0) == null, "an empty buffer: count 0, subfile null");
		check(ContainerBytes.count(new byte[3]) == 0, "three bytes is not enough for a count");
		byte[] headerOnly = {'G', 'R', 1, 0};
		check(ContainerBytes.count(headerOnly) == 1 && ContainerBytes.subfile(headerOnly, 0) == null,
				"a header with no offset table declares its count but answers null for the subfile");
	}

	static void check(boolean ok, String what) {
		System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what);
		if (!ok) {
			fails++;
		}
	}
}
