package ctrmap.tests;

import ctrmap.formats.codepatch.ItemIconTable;
import ctrmap.formats.codepatch.ShopData;
import ctrmap.formats.codepatch.ZoneLimitPatch;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * The item-icon patch must refuse the wrong build, and must never write outside
 * its own table.
 *
 * <p>WHY BOTH HALVES MATTER. This is the only part of the item editor that
 * touches the EXECUTABLE, and the table it edits has ZERO SLACK - the next table
 * begins at 0x47D264, which is 776*4 bytes after 0x47C644 exactly. A patch that
 * is one entry long, or applied to a build where the table sits somewhere else,
 * does not fail: it produces a code.bin that boots and is wrong somewhere the
 * user will not connect to icons. So the offsets are verified against stock
 * bytes before anything is written, the way {@link ZoneLimitPatch} verifies its
 * five words, and the IPS is checked to fall entirely inside the table.
 *
 * <p>The stock-byte fingerprint is deliberately taken from the table that
 * FOLLOWS the icons rather than from the icons themselves. The icon table is
 * the thing the user changes, so fingerprinting it would refuse a code.bin this
 * very editor had already patched - the normal case on a second edit. The
 * neighbour is never written, so it stays a fact about the build.
 *
 * <p>Runs without a dump against a synthetic executable built here, and takes
 * the real decompressed code.bin as args[0] to check the measured offsets
 * against the actual game. The synthetic half can only catch a codec that
 * contradicts itself; the real half is what makes the numbers true.
 *
 * Usage: java ctrmap.tests.ItemIconPatchTest [path-to-decompressed-code.bin]
 */
public class ItemIconPatchTest {

	static int fails = 0;

	static void check(boolean cond, String msg) {
		if (cond) {
			System.out.println("  ok: " + msg);
		} else {
			System.out.println("  FAIL: " + msg);
			fails++;
		}
	}

	/** The bytes that precede the table. Written out here so the fixture is not built from the codec's own copy. */
	private static final byte[] ANCHOR = {
		'V', 'i', 'e', 'w', 'U', 'p', 'd', 'a', 't', 'e', 'r', '.',
		'V', 'i', 'e', 'w', 'R', 'o', 't', 'a', 't', 'e', 0,
		(byte) 0xFF, (byte) 0x92, 0x0A, 0x06, 0x3F
	};

	public static void main(String[] args) throws Exception {
		theTableHasNoRoomToGrow();

		//"stays inside its own table" runs FIRST on purpose: it is the check
		//that survives a codec whose reader and writer are wrong the same way,
		//and putting it after a read that would throw on such a codec would let
		//a crash stand in for it.
		byte[] synthetic = synthesize();
		thePatchStaysInsideItsOwnTable(synthetic);
		roundTripsAndRefusals(synthetic, "synthetic");
		itRefusesABuildItWasNotMeasuredIn(synthetic);

		File codeFile = new File(args.length > 0 ? args[0] : "../code.bin");
		if (codeFile.isFile()) {
			byte[] code = Files.readAllBytes(codeFile.toPath());
			theRealBuildMatchesWhatWasMeasured(code);
			thePatchStaysInsideItsOwnTable(code);
			roundTripsAndRefusals(code, "the real code.bin");
			mergesWithTheOtherCodePatches(code);
		} else {
			System.out.println("  skip: no decompressed code.bin at " + codeFile
					+ " - the measured offsets were NOT checked against the real game this run");
		}

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	static void theTableHasNoRoomToGrow() {
		System.out.println("--- the table's own arithmetic: 776 entries and not one more");
		check(ItemIconTable.FILE_OFFSET + ItemIconTable.COUNT * 4 == ItemIconTable.NEXT_TABLE,
				"0x" + Integer.toHexString(ItemIconTable.FILE_OFFSET) + " + " + ItemIconTable.COUNT
				+ "*4 == 0x" + Integer.toHexString(ItemIconTable.NEXT_TABLE)
				+ " - the next table starts immediately, so there is nowhere to put a 777th item");
	}

	/**
	 * A stand-in executable: the anchor ending exactly at the measured offset, a
	 * retail-shaped icon table, and the stock head of the table that follows.
	 */
	static byte[] synthesize() {
		byte[] code = new byte[ItemIconTable.NEXT_TABLE + 256];
		System.arraycopy(ANCHOR, 0, code, ItemIconTable.FILE_OFFSET - ANCHOR.length, ANCHOR.length);
		//retail shape: item 0 is the blank icon, then a run
		put(code, ItemIconTable.FILE_OFFSET, 629);
		for (int i = 1; i < ItemIconTable.COUNT; i++) {
			put(code, ItemIconTable.FILE_OFFSET + i * 4, (i - 1) % 630);
		}
		int[] next = {0, 1, 2, 3, 4, 6, 7, 8};
		for (int i = 0; i < next.length; i++) {
			put(code, ItemIconTable.NEXT_TABLE + i * 4, next[i]);
		}
		return code;
	}

	static void roundTripsAndRefusals(byte[] code, String what) {
		System.out.println("--- reading and writing " + what);
		int[] icons = ItemIconTable.read(code);
		check(icons.length == ItemIconTable.COUNT, "read " + icons.length + " entries");
		check(Arrays.equals(ItemIconTable.write(code, icons), code),
				"writing back what was read reproduces the file byte for byte");

		int[] edited = icons.clone();
		edited[5] = icons[6];             // give item 5 the icon item 6 uses
		byte[] patched = ItemIconTable.write(code, edited);
		check(ItemIconTable.read(patched)[5] == icons[6], "an edit reads back");
		check(ItemIconTable.read(patched)[6] == icons[6],
				"and the item it was copied FROM keeps its own icon - this reassigns, it does not move");

		//Refusals. The bound is spelled out here as the MEASURED number - the
		//icon archive holds 631 entries, so 630 is the last one - rather than
		//taken from the constant under test, which would make this assertion
		//agree with the codec no matter what the codec said.
		check(ItemIconTable.MAX_ICON == 630,
				"the last icon in the archive is 630 (631 entries, measured)");
		check(refuses(code, replaceAt(icons, 5, 631)),
				"an icon index past the end of the icon archive is refused - this patch reassigns"
				+ " icons the game already ships, it cannot add one");
		check(refuses(code, replaceAt(icons, 5, 5000)), "and one wildly past it");
		check(!refuses(code, replaceAt(icons, 5, 630)),
				"but 630 IS accepted: no retail item uses it, and it is still a real icon");
		check(refuses(code, replaceAt(icons, 5, -1)), "a negative icon index is refused");
		check(refuses(code, Arrays.copyOf(icons, ItemIconTable.COUNT + 1)),
				"a table with one entry too many is refused - there is no room for it");
		check(refuses(code, Arrays.copyOf(icons, ItemIconTable.COUNT - 1)),
				"and one with an entry too few, which would leave a stale word behind");
		check(refuses(code, null), "and no table at all");
	}

	static void thePatchStaysInsideItsOwnTable(byte[] code) {
		System.out.println("--- the patch never writes outside the 776 words it owns");
		int[] icons = ItemIconTable.read(code);
		int[] edited = icons.clone();
		//change the first and the last entry: the two that would spill if the
		//bounds were off by one in either direction
		edited[0] = 1;
		edited[ItemIconTable.COUNT - 1] = 2;
		byte[] ips = ItemIconTable.diffIPS(code, edited);
		byte[] applied = ShopData.applyIPS(code, ips);
		check(Arrays.equals(applied, ItemIconTable.write(code, edited)),
				"the IPS reproduces the patched file exactly");

		int outside = 0, inside = 0;
		for (int i = 0; i < code.length; i++) {
			if (code[i] != applied[i]) {
				if (i >= ItemIconTable.FILE_OFFSET && i < ItemIconTable.NEXT_TABLE) {
					inside++;
				} else {
					outside++;
				}
			}
		}
		check(inside > 0, inside + " byte(s) changed inside the table");
		check(outside == 0, "and " + outside + " outside it - the neighbouring table, which starts"
				+ " immediately after, is untouched");
	}

	static void itRefusesABuildItWasNotMeasuredIn(byte[] good) {
		System.out.println("--- stock bytes are verified before anything is written");
		byte[] noAnchor = good.clone();
		noAnchor[ItemIconTable.FILE_OFFSET - 4] ^= 0xFF;
		check(!ItemIconTable.atMeasuredOffset(noAnchor) && refusesToRead(noAnchor),
				"a file whose anchor does not read as recorded is refused rather than patched blind");

		byte[] movedTable = good.clone();
		//the neighbouring table is the fingerprint: change the word that makes
		//it distinctive (the skipped 5) and the build is no longer the one these
		//offsets were measured in
		put(movedTable, ItemIconTable.NEXT_TABLE + 5 * 4, 5);
		check(refusesToRead(movedTable),
				"a file where the table AFTER the icons does not read as recorded is refused");

		byte[] shortFile = Arrays.copyOf(good, ItemIconTable.FILE_OFFSET + 8);
		check(refusesToRead(shortFile), "a file too short to hold the table is refused");

		byte[] wildValue = good.clone();
		put(wildValue, ItemIconTable.FILE_OFFSET + 400 * 4, 999999);
		check(refusesToRead(wildValue),
				"a 'table' holding a value no icon index could be is refused - it is not the icon table");

		//and the anchor must be the thing that finds it, not the constant alone
		byte[] shifted = new byte[good.length + 64];
		System.arraycopy(good, 0, shifted, 64, good.length);
		check(refusesToRead(shifted),
				"a build where the anchor is found somewhere ELSE is refused, rather than patched"
				+ " at the offset that used to be right");
	}

	static void theRealBuildMatchesWhatWasMeasured(byte[] code) {
		System.out.println("--- the real ORAS executable, against the numbers that were measured");
		check(ItemIconTable.locate(code) == ItemIconTable.FILE_OFFSET,
				"the anchor puts the table at 0x" + Integer.toHexString(ItemIconTable.FILE_OFFSET)
				+ " (found 0x" + Integer.toHexString(ItemIconTable.locate(code)) + ")");
		int[] icons = ItemIconTable.read(code);
		int max = 0;
		for (int v : icons) {
			max = Math.max(max, v);
		}
		check(max == ItemIconTable.MAX_RETAIL_ICON, "the highest icon retail uses is "
				+ ItemIconTable.MAX_RETAIL_ICON + " (found " + max + ")");

		//A question whose answer is already known, asked to catch a reader that
		//is confidently reading the wrong bytes: the four ids that hold no item
		//- 113, 114, 115 and 126 - must point at the SAME blank icon that item 0
		//does, because they are blank for the same reason.
		int blank = icons[0];
		boolean allBlank = true;
		for (int id : new int[]{113, 114, 115, 126}) {
			allBlank &= icons[id] == blank;
		}
		check(allBlank, "the four free item slots point at the same icon as item 0 (" + blank
				+ ") - the blank, which is what an unused slot should draw");
		check(blank == ItemIconTable.MAX_RETAIL_ICON,
				"and that blank is the last icon in the set, " + blank);
	}

	static void mergesWithTheOtherCodePatches(byte[] code) {
		System.out.println("--- one code.ips carries the icon patch, the shops and the zone limit");
		int[] icons = ItemIconTable.read(code);
		int[] edited = icons.clone();
		edited[50] = icons[2];               // Rare Candy draws the Ultra Ball icon
		byte[] iconIps = ItemIconTable.diffIPS(code, edited);
		byte[] zoneIps = ZoneLimitPatch.buildIPS(4);

		int[][] shops = ShopData.read(code);
		int[][] shopsEdited = new int[shops.length][];
		for (int i = 0; i < shops.length; i++) {
			shopsEdited[i] = shops[i].clone();
		}
		shopsEdited[18] = new int[]{50, 51, 52, 53};
		byte[] shopIps = ShopData.diffIPS(code, ShopData.write(code, shopsEdited));

		byte[] merged = ShopData.mergeIPS(ShopData.mergeIPS(zoneIps, shopIps), iconIps);
		byte[] all = ShopData.applyIPS(code, merged);

		check(ItemIconTable.read(all)[50] == icons[2], "the icon edit survives the merge");
		check(Arrays.equals(ShopData.read(all)[18], shopsEdited[18]), "the shop edit survives it");
		byte[] zoneOnly = ZoneLimitPatch.applyToCode(4, code);
		check(Arrays.equals(Arrays.copyOfRange(all, 0x012bc0, 0x012bc4),
				Arrays.copyOfRange(zoneOnly, 0x012bc0, 0x012bc4)),
				"and so does the zone-limit patch");
		//the icon patch must not have disturbed the others' sites
		check(ItemIconTable.read(all)[51] == icons[51],
				"an item nobody edited keeps its icon through all three patches");
	}

	// ---- helpers -----------------------------------------------------------

	static boolean refuses(byte[] code, int[] icons) {
		try {
			ItemIconTable.write(code, icons);
			return false;
		} catch (RuntimeException ex) {
			return true;
		}
	}

	static boolean refusesToRead(byte[] code) {
		try {
			ItemIconTable.read(code);
			return false;
		} catch (RuntimeException ex) {
			return true;
		}
	}

	static int[] replaceAt(int[] a, int i, int v) {
		int[] b = a.clone();
		b[i] = v;
		return b;
	}

	static void put(byte[] b, int off, int v) {
		b[off] = (byte) v;
		b[off + 1] = (byte) (v >> 8);
		b[off + 2] = (byte) (v >> 16);
		b[off + 3] = (byte) (v >> 24);
	}
}
