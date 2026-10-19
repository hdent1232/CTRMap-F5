package ctrmap.formats.codepatch;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE IPS FORMAT, READ AND WRITTEN IN ONE PLACE (F047).
 *
 * <p>Three writers spelled it by hand - {@code ShopData.diffIPS}/{@code mergeIPS},
 * {@code CodeIps.emit} and {@code ZoneLimitPatch.buildIPS} - and the icon table's own javadoc said
 * a second writer would be "a second place for the merge to be subtly different". They were three,
 * with two byte scanners beside them. Every IPS this program reads or writes now goes through here;
 * {@link CodeIps} decides which bytes a save owns and asks this for the bytes.
 *
 * <p>A record is {@code {offset, length, byte...}} as ints - the shape {@link #parse} answers and
 * {@link #write} takes. No RLE: nothing here emits one, and one read is refused.
 *
 * <p>Not: {@link ShopData} - it held this as a shop table's helpers, which is why the other two
 * writers did not reach into a shop class and wrote their own; and not {@link CodeIps} - that one
 * decides which bytes a save OWNS, while parse, apply and diff are asked by readers that save
 * nothing.
 */
public final class Ips {

	private Ips() {
	}

	/** The largest offset an IPS record can name (24 bits) and the longest record (16 bits). */
	static final int MAX_OFFSET = 0xFFFFFF;
	static final int MAX_LENGTH = 0xFFFF;

	/**
	 * The patch holding {@code records}, in order. Refuses a record IPS cannot carry: an offset
	 * past 24 bits, or a length of 0 (which IPS reads as RLE) or past 16 bits.
	 */
	public static byte[] write(List<int[]> records) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		ascii(b, "PATCH");
		for (int[] rec : records) {
			int off = rec[0], len = rec[1];
			if (off < 0 || off > MAX_OFFSET) {
				throw new IllegalStateException("offset beyond IPS range: 0x" + Integer.toHexString(off));
			}
			if (len < 1 || len > MAX_LENGTH) {
				throw new IllegalStateException("an IPS record of " + len + " bytes at 0x" + Integer.toHexString(off)
						+ " cannot be written");
			}
			b.write((off >> 16) & 0xFF);
			b.write((off >> 8) & 0xFF);
			b.write(off & 0xFF);
			b.write((len >> 8) & 0xFF);
			b.write(len & 0xFF);
			for (int k = 0; k < len; k++) {
				b.write(rec[2 + k]);
			}
		}
		ascii(b, "EOF");
		return b.toByteArray();
	}

	/** One record: {@code length} bytes of {@code data} from {@code from}, written at {@code offset}. */
	public static int[] record(int offset, byte[] data, int from, int length) {
		int[] rec = new int[2 + length];
		rec[0] = offset;
		rec[1] = length;
		for (int k = 0; k < length; k++) {
			rec[2 + k] = data[from + k] & 0xFF;
		}
		return rec;
	}

	/**
	 * A minimal IPS diffing {@code patched} against {@code original}
	 * (contiguous changed runs; offsets fit IPS's 24 bits for code.bin).
	 */
	public static byte[] diff(byte[] original, byte[] patched) {
		if (original.length != patched.length) {
			throw new IllegalArgumentException("length mismatch");
		}
		List<int[]> records = new ArrayList<>();
		int i = 0;
		while (i < original.length) {
			if (original[i] == patched[i]) {
				i++;
				continue;
			}
			int start = i;
			while (i < original.length && i - start < MAX_LENGTH
					&& (original[i] != patched[i] || (i + 1 < original.length && i - start + 1 < MAX_LENGTH && original[i + 1] != patched[i + 1]))) {
				i++;
			}
			records.add(record(start, patched, start, i - start));
		}
		return write(records);
	}

	/**
	 * Merges two IPS patches into one (records applied in order; {@code ours}
	 * wins where they overlap), re-emitted as contiguous runs.
	 */
	public static byte[] merge(byte[] theirs, byte[] ours) {
		Map<Integer, Byte> bytes = new LinkedHashMap<>();
		for (byte[] ips : new byte[][]{theirs, ours}) {
			for (int[] rec : parse(ips)) {
				for (int k = 0; k < rec[1]; k++) {
					bytes.put(rec[0] + k, (byte) rec[2 + k]);
				}
			}
		}
		List<Integer> offs = new ArrayList<>(bytes.keySet());
		java.util.Collections.sort(offs);
		List<int[]> records = new ArrayList<>();
		int i = 0;
		while (i < offs.size()) {
			int start = offs.get(i), end = i;
			while (end + 1 < offs.size() && offs.get(end + 1) == offs.get(end) + 1 && offs.get(end + 1) - start < MAX_LENGTH) {
				end++;
			}
			int len = offs.get(end) - start + 1;
			int[] rec = new int[2 + len];
			rec[0] = start;
			rec[1] = len;
			for (int k = 0; k < len; k++) {
				rec[2 + k] = bytes.get(start + k) & 0xFF;
			}
			records.add(rec);
			i = end + 1;
		}
		return write(records);
	}

	/** Parses IPS records as {offset, len, data...} ints (no RLE support - we never emit it; throws if seen). */
	public static List<int[]> parse(byte[] ips) {
		List<int[]> out = new ArrayList<>();
		if (ips == null || ips.length < 8) {
			return out;
		}
		int p = 5; // "PATCH"
		while (p + 3 <= ips.length) {
			int off = ((ips[p] & 0xFF) << 16) | ((ips[p + 1] & 0xFF) << 8) | (ips[p + 2] & 0xFF);
			if (off == 0x454F46) { // "EOF"
				break;
			}
			p += 3;
			int len = ((ips[p] & 0xFF) << 8) | (ips[p + 1] & 0xFF);
			p += 2;
			if (len == 0) {
				throw new IllegalArgumentException("RLE IPS records not supported");
			}
			int[] rec = new int[2 + len];
			rec[0] = off;
			rec[1] = len;
			for (int k = 0; k < len; k++) {
				rec[2 + k] = ips[p + k] & 0xFF;
			}
			p += len;
			out.add(rec);
		}
		return out;
	}

	/** Applies an IPS to a copy of code (for tests / verification). */
	@ctrmap.util.ForSuites("ORACLE: applies an IPS the way the emulator's patcher does, so a suite can hold what the editor's patches write to the bytes they mean")
	public static byte[] apply(byte[] code, byte[] ips) {
		byte[] out = code.clone();
		for (int[] rec : parse(ips)) {
			for (int k = 0; k < rec[1]; k++) {
				out[rec[0] + k] = (byte) rec[2 + k];
			}
		}
		return out;
	}

	/** Whether {@code b} is shaped like an IPS: PATCH at the front and EOF at the end. */
	public static boolean isIps(byte[] b) {
		return b.length >= 8 && b[0] == 'P' && b[1] == 'A' && b[2] == 'T' && b[3] == 'C' && b[4] == 'H'
				&& b[b.length - 3] == 'E' && b[b.length - 2] == 'O' && b[b.length - 1] == 'F';
	}

	/** Where {@code needle} first occurs in {@code hay} at or after {@code from}, or -1 (also for a null hay). */
	public static int indexOf(byte[] hay, byte[] needle, int from) {
		if (hay == null || hay.length < needle.length) {
			return -1;
		}
		outer:
		for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (hay[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	private static void ascii(ByteArrayOutputStream b, String s) {
		for (int i = 0; i < s.length(); i++) {
			b.write(s.charAt(i));
		}
	}
}
