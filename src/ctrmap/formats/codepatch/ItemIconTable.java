package ctrmap.formats.codepatch;

import static ctrmap.formats.LittleEndian.i32;

/**
 * Which icon each item draws - a {@code u32[776]} inside the ORAS EXECUTABLE,
 * not in any archive.
 *
 * <p>WHY IT IS A PATCH AND NOT A DATA EDIT. Everything else the item editor
 * changes lives in GARC {@code ITEM_DATA} and rides the normal deploy: copy the
 * archive, done, reversible. The icon index does not. It is a table in
 * code.bin, so changing it means an IPS, Game Patching turned on, and a full
 * emulator restart - a different blast radius, and the reason the editor labels
 * it separately instead of putting it in the same form as the price.
 *
 * <p>MEASURED, in a decompressed Omega Ruby code.bin (virtual address = file
 * offset + 0x100000):
 * <ul>
 * <li>the table is at file offset 0x47C644 and holds exactly 776 u32 entries,
 *     one per item, indexed by item id;</li>
 * <li>it has ZERO SLACK - the next table starts at 0x47D264, which is
 *     0x47C644 + 776*4 exactly, so there is no room to grow it in place;</li>
 * <li>retail values run 0..629; item 0 and the four empty item slots (113, 114,
 *     115, 126) all point at icon 629, the blank;</li>
 * <li>the byte string {@code "ViewUpdater.ViewRotate\0\xFF"} + the float
 *     0x3F060A92 occurs exactly ONCE in the file and ends exactly where the
 *     table begins, which is what {@link #locate} keys on.</li>
 * </ul>
 *
 * <p>THE PATCH REASSIGNS, IT DOES NOT AUTHOR. Pointing an item at another
 * item's icon is a swap between textures the game already ships. Nothing here
 * adds an icon, and nothing here can raise 776.
 */
public final class ItemIconTable {

	/** File offset of the table in a decompressed ORAS code.bin, as measured. */
	public static final int FILE_OFFSET = 0x47C644;

	/** Entries in the table - one per item, item id == index. */
	public static final int COUNT = 776;

	/** Where the NEXT table starts. FILE_OFFSET + COUNT*4 exactly: no slack. */
	public static final int NEXT_TABLE = 0x47D264;

	/** Highest icon index any retail item uses - measured across all 776 entries. */
	public static final int MAX_RETAIL_ICON = 629;

	/**
	 * Icons in the icon archive. MEASURED: 631 entries, so the valid indices are
	 * 0..630.
	 *
	 * <p>Note the gap between this and {@link #MAX_RETAIL_ICON}: index 630
	 * exists and no retail item points at it. It is offered anyway, because the
	 * archive says it is there; what is refused is an index past the END of the
	 * archive, which would draw nothing at all.
	 */
	public static final int ICON_COUNT = 631;

	/** Highest icon index this editor will write. */
	public static final int MAX_ICON = ICON_COUNT - 1;

	/**
	 * The bytes immediately preceding the table. Unique in the file, so it
	 * locates the table without trusting {@link #FILE_OFFSET} alone.
	 */
	private static final byte[] ANCHOR = {
		'V', 'i', 'e', 'w', 'U', 'p', 'd', 'a', 't', 'e', 'r', '.',
		'V', 'i', 'e', 'w', 'R', 'o', 't', 'a', 't', 'e', 0,
		(byte) 0xFF, (byte) 0x92, 0x0A, 0x06, 0x3F
	};

	/**
	 * The stock head of the table that follows this one: 0,1,2,3,4,6,7,8.
	 *
	 * <p>This is the STOCK-BYTE check, in the sense the other patches in this
	 * package use the phrase - a fixed sequence that must read as recorded
	 * before anything is written. It is deliberately taken from the NEIGHBOURING
	 * table rather than from the icon table itself: the icon table is the thing
	 * the user changes, so verifying it against retail would refuse a code.bin
	 * this very editor had already patched, which is the normal case on the
	 * second edit. The neighbour is never written by this patch, so it stays a
	 * fingerprint of the build. Note the skipped 5 - it is why eight words of it
	 * are worth more than eight words of a plain 0,1,2,3 run.
	 */
	private static final int[] NEXT_TABLE_STOCK = {0, 1, 2, 3, 4, 6, 7, 8};

	private ItemIconTable() {
	}

	/**
	 * File offset of the icon table in this code.bin, or -1 when it is not
	 * this build.
	 *
	 * <p>Finds the anchor AND cross-checks it against the measured offset. If
	 * the two disagree the file is a different build that happens to contain
	 * the same string, and the honest answer is the anchor's - but the caller
	 * is told, because a silent relocation is exactly how a patch ends up
	 * writing 3 KB over something else.
	 */
	public static int locate(byte[] code) {
		int at = indexOf(code, ANCHOR);
		if (at < 0) {
			return -1;
		}
		return at + ANCHOR.length;
	}

	/** True when {@link #locate} found the table exactly where it was measured. */
	public static boolean atMeasuredOffset(byte[] code) {
		return locate(code) == FILE_OFFSET;
	}

	/**
	 * Refuses a file that is not the build this table was measured in.
	 *
	 * <p>Checked BEFORE any write, the way {@link ZoneLimitPatch#applyToCode}
	 * checks its five stock words: a patch applied to the wrong executable does
	 * not fail, it produces a build that boots and is wrong somewhere else.
	 */
	public static void verify(byte[] code) {
		if (code == null) {
			throw new IllegalArgumentException("no code.bin");
		}
		int table = locate(code);
		if (table < 0) {
			throw new IllegalStateException("this file does not contain the ORAS item-icon table"
					+ " - is it the DECOMPRESSED code.bin? (the anchor before the table was not found)");
		}
		if (table != FILE_OFFSET) {
			throw new IllegalStateException("the item-icon table is at 0x" + Integer.toHexString(table)
					+ " in this file but was measured at 0x" + Integer.toHexString(FILE_OFFSET)
					+ " - this is a different build, and patching it at the measured offset would"
					+ " write over something else entirely");
		}
		if (code.length < NEXT_TABLE + NEXT_TABLE_STOCK.length * 4) {
			throw new IllegalStateException("code.bin is too short to hold the icon table"
					+ " (" + code.length + " bytes)");
		}
		for (int i = 0; i < NEXT_TABLE_STOCK.length; i++) {
			int got = i32(code, NEXT_TABLE + i * 4);
			if (got != NEXT_TABLE_STOCK[i]) {
				throw new IllegalStateException("the table after the item icons does not read as"
						+ " recorded (word " + i + " is " + got + ", expected " + NEXT_TABLE_STOCK[i]
						+ ") - refusing to patch a build these offsets were not measured in");
			}
		}
		for (int i = 0; i < COUNT; i++) {
			int v = i32(code, FILE_OFFSET + i * 4);
			if (v < 0 || v > MAX_ICON) {
				throw new IllegalStateException("item " + i + " points at icon " + v
						+ ", outside the 0.." + MAX_ICON + " range every retail entry sits in"
						+ " - this is not the icon table");
			}
		}
	}

	/** The 776 icon indices. Verifies the build first. */
	public static int[] read(byte[] code) {
		verify(code);
		int[] out = new int[COUNT];
		for (int i = 0; i < COUNT; i++) {
			out[i] = i32(code, FILE_OFFSET + i * 4);
		}
		return out;
	}

	/**
	 * A copy of code with these icon assignments written in. Verifies the build
	 * and every value before touching a byte, and never writes outside the
	 * table's 776 words - the next table begins immediately after it.
	 */
	public static byte[] write(byte[] code, int[] icons) {
		verify(code);
		if (icons == null || icons.length != COUNT) {
			throw new IllegalArgumentException("the icon table holds exactly " + COUNT
					+ " entries, not " + (icons == null ? "null" : String.valueOf(icons.length))
					+ " - it has zero slack, so a different length is a different table");
		}
		for (int i = 0; i < COUNT; i++) {
			if (icons[i] < 0 || icons[i] > MAX_ICON) {
				throw new IllegalArgumentException("item " + i + ": icon " + icons[i]
						+ " is outside 0.." + MAX_ICON + ". This patch reassigns icons the game"
						+ " already ships; it cannot add one.");
			}
		}
		byte[] out = code.clone();
		for (int i = 0; i < COUNT; i++) {
			int p = FILE_OFFSET + i * 4;
			out[p] = (byte) icons[i];
			out[p + 1] = (byte) (icons[i] >> 8);
			out[p + 2] = (byte) (icons[i] >> 16);
			out[p + 3] = (byte) (icons[i] >> 24);
		}
		return out;
	}

	/**
	 * The IPS for these assignments, diffed against the code.bin they came
	 * from, ready to merge into the same code.ips as the zone and shop patches.
	 *
	 * <p>Shares {@link ShopData}'s IPS machinery on purpose. A second IPS
	 * writer would be a second place for the merge to be subtly different, and
	 * the merge is what lets one code.ips carry every executable patch.
	 */
	public static byte[] diffIPS(byte[] code, int[] icons) {
		return ShopData.diffIPS(code, write(code, icons));
	}

	private static int indexOf(byte[] hay, byte[] needle) {
		if (hay == null || hay.length < needle.length) {
			return -1;
		}
		outer:
		for (int i = 0; i <= hay.length - needle.length; i++) {
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
