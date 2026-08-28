package ctrmap.formats.codepatch;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ORAS shop (Poke Mart) inventories - u16 item-id lists embedded in the
 * EXECUTABLE's .rodata, not in any archive. Located by the anchor string
 * {@code \0rom:/DllStartMenu.cro\0} (the table starts right after it; recipe
 * cross-referenced from pk3DS's MartEditor6 and byte-verified against the
 * user's real decompressed code.bin: all 24 retail inventories read exactly,
 * 215 items, file offset 0x47AA3E / VA 0x57AA3E).
 *
 * <p>24 shops: the 9 badge-tier Poke Mart inventories (what every Mart's top
 * clerk sells as you earn badges), the pre-Pokedex list, and the specialty
 * shops (Slateport Market stalls, city Ball/TM shops, Lavaridge Herbs, the
 * Lilycove Dept Store floors). Item counts per shop are FIXED by the engine
 * (the lists are consecutive) - editing changes WHICH items, not how many.
 *
 * <p>Ships as a diff IPS against the original code.bin, mergeable with the
 * zone-limit patch so one code.ips carries both.
 */
public class ShopData {

	/** Items per shop, in table order (ORAS; sums to 215 entries = 430 bytes). */
	public static final int[] COUNTS = {
		3, 10, 14, 17, 18, 19, 19, 19, 19, // badge-tier general marts
		1, // pre-Pokedex
		9, 6, 4, 3, 8, // Slateport market + Rustboro/Slateport shops
		8, 3, 3, 4, // Mauville TMs, Verdanturf/Fallarbor Balls, Lavaridge Herbs
		3, 6, 8, // Lilycove 2F/3F
		7, 4,};   // Lilycove 4F TM floors

	public static final String[] NAMES = {
		"Poke Mart - 0 badges (after Pokedex)", "Poke Mart - 1 badge", "Poke Mart - 2 badges",
		"Poke Mart - 3 badges", "Poke Mart - 4 badges", "Poke Mart - 5 badges",
		"Poke Mart - 6 badges", "Poke Mart - 7 badges", "Poke Mart - 8 badges",
		"Poke Mart - before Pokedex",
		"Slateport Market - Incenses", "Slateport Market - Vitamins", "Slateport Market - TMs",
		"Rustboro City - Poke Balls", "Slateport City - X Items",
		"Mauville City - TMs", "Verdanturf Town - Poke Balls", "Fallarbor Town - Poke Balls",
		"Lavaridge Town - Herbs",
		"Lilycove Dept. 2F - Run Away items", "Lilycove Dept. 3F - Vitamins", "Lilycove Dept. 3F - X Items",
		"Lilycove Dept. 4F - Offensive TMs", "Lilycove Dept. 4F - Defensive TMs",};

	private static final byte[] ANCHOR = anchor("\0rom:/DllStartMenu.cro\0");
	private static final byte[] ANCHOR_PATCHED = anchor("\0rom2:/DllStartMenu.cro\0ÿ");

	private static byte[] anchor(String s) {
		byte[] b = new byte[s.length()];
		for (int i = 0; i < s.length(); i++) {
			b[i] = (byte) s.charAt(i);
		}
		return b;
	}

	public static int totalItems() {
		int n = 0;
		for (int c : COUNTS) {
			n += c;
		}
		return n;
	}

	/** File offset of the shop table in a decompressed code.bin, or -1. */
	public static int locate(byte[] code) {
		for (byte[] a : new byte[][]{ANCHOR, ANCHOR_PATCHED}) {
			int off = indexOf(code, a, 0x400000);
			if (off >= 0) {
				return off + a.length;
			}
		}
		return -1;
	}

	/** Reads all shops: int[shop][slot] item ids. Throws if the table is absent. */
	public static int[][] read(byte[] code) {
		int table = locate(code);
		if (table < 0) {
			throw new IllegalStateException("shop table not found - is this a decompressed ORAS code.bin?");
		}
		int[][] shops = new int[COUNTS.length][];
		int p = table;
		for (int s = 0; s < COUNTS.length; s++) {
			shops[s] = new int[COUNTS[s]];
			for (int i = 0; i < COUNTS[s]; i++) {
				shops[s][i] = (code[p] & 0xFF) | ((code[p + 1] & 0xFF) << 8);
				p += 2;
			}
		}
		return shops;
	}

	/** Returns a copy of code with the given inventories written in. */
	public static byte[] write(byte[] code, int[][] shops) {
		if (shops.length != COUNTS.length) {
			throw new IllegalArgumentException("expected " + COUNTS.length + " shops");
		}
		int table = locate(code);
		if (table < 0) {
			throw new IllegalStateException("shop table not found");
		}
		byte[] out = code.clone();
		int p = table;
		for (int s = 0; s < COUNTS.length; s++) {
			if (shops[s].length != COUNTS[s]) {
				throw new IllegalArgumentException("shop " + s + " must keep " + COUNTS[s] + " items (the engine reads fixed-length lists)");
			}
			for (int id : shops[s]) {
				if (id < 0 || id > 0xFFFF) {
					throw new IllegalArgumentException("item id out of range: " + id);
				}
				out[p] = (byte) id;
				out[p + 1] = (byte) (id >> 8);
				p += 2;
			}
		}
		return out;
	}

	// ---- IPS (diff + merge) ------------------------------------------------

	/**
	 * A minimal IPS diffing {@code patched} against {@code original}
	 * (contiguous changed runs; offsets fit IPS's 24 bits for code.bin).
	 */
	public static byte[] diffIPS(byte[] original, byte[] patched) {
		if (original.length != patched.length) {
			throw new IllegalArgumentException("length mismatch");
		}
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		ascii(b, "PATCH");
		int i = 0;
		while (i < original.length) {
			if (original[i] == patched[i]) {
				i++;
				continue;
			}
			int start = i;
			while (i < original.length && i - start < 0xFFFF
					&& (original[i] != patched[i] || (i + 1 < original.length && i - start + 1 < 0xFFFF && original[i + 1] != patched[i + 1]))) {
				i++;
			}
			int len = i - start;
			if (start > 0xFFFFFF) {
				throw new IllegalStateException("offset beyond IPS range");
			}
			b.write((start >> 16) & 0xFF);
			b.write((start >> 8) & 0xFF);
			b.write(start & 0xFF);
			b.write((len >> 8) & 0xFF);
			b.write(len & 0xFF);
			b.write(patched, start, len);
		}
		ascii(b, "EOF");
		return b.toByteArray();
	}

	/**
	 * Merges two IPS patches into one (records applied in order; {@code ours}
	 * wins where they overlap). Lets the shop patch share code.ips with the
	 * zone-limit patch.
	 */
	public static byte[] mergeIPS(byte[] theirs, byte[] ours) {
		Map<Integer, Byte> bytes = new LinkedHashMap<>();
		for (byte[] ips : new byte[][]{theirs, ours}) {
			for (int[] rec : parseIPS(ips)) {
				for (int k = 0; k < rec[1]; k++) {
					bytes.put(rec[0] + k, (byte) rec[2 + k]);
				}
			}
		}
		// re-emit as contiguous runs
		List<Integer> offs = new ArrayList<>(bytes.keySet());
		java.util.Collections.sort(offs);
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		ascii(b, "PATCH");
		int i = 0;
		while (i < offs.size()) {
			int start = offs.get(i), end = i;
			while (end + 1 < offs.size() && offs.get(end + 1) == offs.get(end) + 1 && offs.get(end + 1) - start < 0xFFFF) {
				end++;
			}
			int len = offs.get(end) - start + 1;
			b.write((start >> 16) & 0xFF);
			b.write((start >> 8) & 0xFF);
			b.write(start & 0xFF);
			b.write((len >> 8) & 0xFF);
			b.write(len & 0xFF);
			for (int k = 0; k < len; k++) {
				b.write(bytes.get(start + k));
			}
			i = end + 1;
		}
		ascii(b, "EOF");
		return b.toByteArray();
	}

	/** Parses IPS records as {offset, len, data...} ints (no RLE support - we never emit it; throws if seen). */
	public static List<int[]> parseIPS(byte[] ips) {
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
	public static byte[] applyIPS(byte[] code, byte[] ips) {
		byte[] out = code.clone();
		for (int[] rec : parseIPS(ips)) {
			for (int k = 0; k < rec[1]; k++) {
				out[rec[0] + k] = (byte) rec[2 + k];
			}
		}
		return out;
	}

	private static void ascii(ByteArrayOutputStream b, String s) {
		for (int i = 0; i < s.length(); i++) {
			b.write(s.charAt(i));
		}
	}

	private static int indexOf(byte[] hay, byte[] needle, int from) {
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
}
