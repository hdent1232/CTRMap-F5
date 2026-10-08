package ctrmap.formats.codepatch;

import static ctrmap.util.LittleEndian.u16;
import static ctrmap.util.LittleEndian.putU16;

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

	/**
	 * Where the table starts in the retail decompressed code.bin - measured, and checked against
	 * the real file by ShopDataTest. A file whose anchor ends anywhere else is another build.
	 */
	public static final int FILE_OFFSET = 0x47AA3E;

	/** Item ids run 0 to this less one: one per entry of the item icon table. */
	public static final int ITEM_COUNT = ItemIconTable.COUNT;

	public static int totalItems() {
		int n = 0;
		for (int c : COUNTS) {
			n += c;
		}
		return n;
	}

	/**
	 * The code.bin bytes a shop save writes: the whole table, so an inventory put back to the
	 * game's own is put back in code.ips too (F152).
	 */
	public static int[][] owned() {
		return new int[][]{{FILE_OFFSET, FILE_OFFSET + totalItems() * 2}};
	}

	/** File offset of the shop table in a decompressed code.bin, or -1. */
	public static int locate(byte[] code) {
		for (byte[] a : new byte[][]{ANCHOR, ANCHOR_PATCHED}) {
			int off = Ips.indexOf(code, a, 0x400000);
			if (off >= 0) {
				return off + a.length;
			}
		}
		return -1;
	}

	/**
	 * Refuses a file that is not the build this table was measured in (F045).
	 *
	 * <p>Checked BEFORE any read or write, as {@link ItemIconTable#verify} and
	 * {@link ZoneLimitPatch#applyToCode} check theirs: the anchor used to be found anywhere past
	 * 0x400000 and {@link #totalItems} words written from wherever it ended, so on another build -
	 * Alpha Sapphire, a revision, an executable relocated by another tool - this wrote 430 bytes
	 * over whatever followed the string. A patch applied to the wrong executable does not fail; it
	 * produces a build that boots and is wrong somewhere else.
	 */
	public static void verify(byte[] code) {
		if (code == null) {
			throw new IllegalArgumentException("no code.bin");
		}
		int table = locate(code);
		if (table < 0) {
			throw new IllegalStateException("this file does not contain the ORAS shop table - is it the DECOMPRESSED"
					+ " code.bin? (the anchor before the table was not found)");
		}
		if (table != FILE_OFFSET) {
			throw new IllegalStateException("the shop table is at 0x" + Integer.toHexString(table)
					+ " in this file but was measured at 0x" + Integer.toHexString(FILE_OFFSET)
					+ " - this is a different build, so it is neither read nor patched: its "
					+ totalItems() * 2 + " bytes written there would land on something else entirely");
		}
		if (code.length < FILE_OFFSET + totalItems() * 2) {
			throw new IllegalStateException("code.bin is too short to hold the shop table (" + code.length + " bytes)");
		}
		int p = FILE_OFFSET;
		for (int s = 0; s < COUNTS.length; s++) {
			for (int i = 0; i < COUNTS[s]; i++) {
				int id = u16(code, p);
				if (id >= ITEM_COUNT) {
					throw new IllegalStateException(NAMES[s] + ", slot " + (i + 1) + ", holds " + id + ", which is no item"
							+ " (they run 0 to " + (ITEM_COUNT - 1) + ") - this is not the shop table, so it is not patched");
				}
				p += 2;
			}
		}
	}

	/** Reads all shops: int[shop][slot] item ids. Verifies the build first. */
	public static int[][] read(byte[] code) {
		verify(code);
		int[][] shops = new int[COUNTS.length][];
		int p = FILE_OFFSET;
		for (int s = 0; s < COUNTS.length; s++) {
			shops[s] = new int[COUNTS[s]];
			for (int i = 0; i < COUNTS[s]; i++) {
				shops[s][i] = u16(code, p);
				p += 2;
			}
		}
		return shops;
	}

	/** Returns a copy of code with the given inventories written in. Verifies the build first. */
	public static byte[] write(byte[] code, int[][] shops) {
		if (shops.length != COUNTS.length) {
			throw new IllegalArgumentException("expected " + COUNTS.length + " shops");
		}
		verify(code);
		byte[] out = code.clone();
		int p = FILE_OFFSET;
		for (int s = 0; s < COUNTS.length; s++) {
			if (shops[s].length != COUNTS[s]) {
				throw new IllegalArgumentException("shop " + s + " must keep " + COUNTS[s] + " items (the engine reads fixed-length lists)");
			}
			for (int id : shops[s]) {
				if (id < 0 || id >= ITEM_COUNT) {
					throw new IllegalArgumentException(NAMES[s] + ": " + id + " is no item (they run 0 to "
							+ (ITEM_COUNT - 1) + "), so it is not written");
				}
				putU16(out, p, id);
				p += 2;
			}
		}
		return out;
	}

	// ---- IPS (diff + merge) ------------------------------------------------

}
