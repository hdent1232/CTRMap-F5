package ctrmap.formats.encounters;

import ctrmap.formats.containers.ContainerBytes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static ctrmap.util.LittleEndian.putI32;
import static ctrmap.util.LittleEndian.putU16;

/**
 * The wild-encounter pack: magic "EN", a u16 zone count, {@code count + 1} absolute u32 offsets
 * (the first at the end of the table, never falling, the last at the end of the file), then one
 * blob per zone - empty for a zone with no wild data.
 *
 * <p>THE ONE READER AND THE ONE WRITER (F053). The pack was re-emitted by three writers - the
 * encounter editor's, and the zone append's and removal's - each with its own header and offset
 * arithmetic, and only the two zone paths checked what they read: the editor read any bytes as a
 * pack, so a bad pair of offsets was a NegativeArraySizeException in the middle of an edit. Every
 * pack is parsed here, strictly, and written here, once.
 *
 * <p>Not: {@link ContainerBytes} - that reads the GameFreak containers' shared header for any
 * magic; this is one format's whole layout, with what may be done to it.
 */
public final class EnPack {

	private final List<byte[]> blobs;

	private EnPack(List<byte[]> blobs) {
		this.blobs = blobs;
	}

	/**
	 * The pack in {@code en}, refused in words when it is not one: too short, the wrong magic, an
	 * offset table that does not fit, a first offset that is not the table's end, offsets that
	 * fall, or a last that is not the file's length.
	 *
	 * @throws IllegalArgumentException naming what is wrong
	 */
	public static EnPack parse(byte[] en) {
		if (en == null || en.length < 8) {
			throw new IllegalArgumentException("EN pack too short (" + (en == null ? 0 : en.length) + " bytes).");
		}
		if (en[0] != 'E' || en[1] != 'N') {
			throw new IllegalArgumentException("EN pack has wrong magic (0x" + Integer.toHexString(en[0] & 0xFF)
					+ Integer.toHexString(en[1] & 0xFF) + ").");
		}
		int count = ContainerBytes.count(en);
		int tableEnd = tableEnd(count);
		//ONE refusal for a table that does not fit: offsets answers null exactly when the file is shorter than
		//tableEnd, so a second check after the first could never be reached (the 2026-10-14 shared sweep)
		int[] offs = ContainerBytes.offsets(en);
		if (offs == null) {
			throw new IllegalArgumentException("EN pack too short for its offset table (" + en.length + " < " + tableEnd + ").");
		}
		for (int i = 0; i <= count; i++) {
			if (i == 0 && offs[0] != tableEnd) {
				throw new IllegalArgumentException("EN pack first offset 0x" + Integer.toHexString(offs[0])
						+ " != table end 0x" + Integer.toHexString(tableEnd) + ".");
			}
			if (i > 0 && offs[i] < offs[i - 1]) {
				throw new IllegalArgumentException("EN pack offsets not monotonic at index " + i + ".");
			}
		}
		if (offs[count] != en.length) {
			throw new IllegalArgumentException("EN pack end sentinel 0x" + Integer.toHexString(offs[count])
					+ " != file length 0x" + Integer.toHexString(en.length) + ".");
		}
		List<byte[]> out = new ArrayList<>(count);
		for (int z = 0; z < count; z++) {
			byte[] b = new byte[offs[z + 1] - offs[z]];
			System.arraycopy(en, offs[z], b, 0, b.length);
			out.add(b);
		}
		return new EnPack(out);
	}

	/**
	 * {@link #parse(byte[])}, and refused unless it holds exactly {@code expectedCount} zones - the
	 * master table's row count, which the pack must match zone for zone.
	 */
	public static EnPack parse(byte[] en, int expectedCount) {
		EnPack p = parse(en);
		if (p.count() != expectedCount) {
			throw new IllegalArgumentException("EN pack count " + p.count() + " != zone count " + expectedCount + ".");
		}
		return p;
	}

	/** How many zones the pack holds. */
	public int count() {
		return blobs.size();
	}

	/** Zone {@code zone}'s blob, a copy; empty when it has no wild data. */
	public byte[] blob(int zone) {
		return blobs.get(inRange(zone)).clone();
	}

	/** The pack with zone {@code zone}'s blob replaced; every other zone's bytes are carried verbatim. */
	public EnPack withBlob(int zone, byte[] blob) {
		List<byte[]> next = new ArrayList<>(blobs);
		next.set(inRange(zone), blob == null ? new byte[0] : blob.clone());
		return new EnPack(next);
	}

	/** The pack grown by {@code n} zones with no wild data - game-legal, 386 retail zones have none. */
	public EnPack grownBy(int n) {
		if (n < 0) {
			throw new IllegalArgumentException("a pack is grown by a count of zones, not by " + n);
		}
		List<byte[]> next = new ArrayList<>(blobs);
		next.addAll(Collections.nCopies(n, new byte[0]));
		return new EnPack(next);
	}

	/** The pack with only its first {@code keep} zones: the removed zones' wild data goes with them. */
	public EnPack truncatedTo(int keep) {
		if (keep < 0 || keep > count()) {
			throw new IllegalArgumentException("keepCount " + keep + " out of range (0.." + count() + ")");
		}
		return new EnPack(new ArrayList<>(blobs.subList(0, keep)));
	}

	/** The pack's bytes: the one place its header and offset table are written. */
	public byte[] bytes() {
		int count = count();
		if (count > 0xFFFF) {
			throw new IllegalStateException("an EN pack holds at most 65535 zones, not " + count);
		}
		int total = tableEnd(count);
		for (byte[] b : blobs) {
			total += b.length;
		}
		byte[] out = new byte[total];
		out[0] = 'E';
		out[1] = 'N';
		putU16(out, 2, count);
		int off = tableEnd(count);
		for (int z = 0; z < count; z++) {
			putI32(out, 4 + z * 4, off);
			byte[] b = blobs.get(z);
			System.arraycopy(b, 0, out, off, b.length);
			off += b.length;
		}
		putI32(out, 4 + count * 4, off);
		return out;
	}

	private int inRange(int zone) {
		if (zone < 0 || zone >= count()) {
			throw new IllegalArgumentException("zone " + zone + " out of range (EN count " + count() + ")");
		}
		return zone;
	}

	private static int tableEnd(int count) {
		return 4 + (count + 1) * 4;
	}
}
