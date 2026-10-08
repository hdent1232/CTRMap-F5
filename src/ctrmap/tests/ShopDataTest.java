package ctrmap.tests;

import ctrmap.formats.codepatch.ShopData;
import ctrmap.formats.codepatch.ZoneLimitPatch;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Validates the shop-inventory codec against the REAL decompressed ORAS
 * code.bin: table located at the expected offset, all 24 retail inventories
 * in range, byte-exact spot checks (Lavaridge Herbs, badge-tier mart heads),
 * write round-trip, diff-IPS application, and merging with the zone-limit
 * patch into one code.ips.
 *
 * <p>The build check (F045) runs first and needs no dump: a code.bin made here,
 * with the table's anchor at the measured offset and then anywhere else.
 *
 * Usage: java ctrmap.tests.ShopDataTest &lt;path-to-decompressed-code.bin&gt;
 */
public class ShopDataTest {

	public static void main(String[] args) throws Exception {
		int fails = buildCheck();
		File codeFile = new File(args.length > 0 ? args[0]
				: "../code.bin");
		if (!codeFile.exists()) {
			Skip.section("no decompressed code.bin at " + codeFile + " - the retail half did not run");
			System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
			if (fails > 0) {
				System.exit(1);
			}
			return;
		}
		byte[] code = Files.readAllBytes(codeFile.toPath());

		// location + shape
		int table = ShopData.locate(code);
		fails += check("table at 0x47AA3E", table == 0x47AA3E);
		int[][] shops = ShopData.read(code);
		fails += check("24 shops", shops.length == 24);
		fails += check("215 items total", ShopData.totalItems() == 215);
		boolean inRange = true;
		for (int[] s : shops) {
			for (int id : s) {
				if (id <= 0 || id > 776) {
					inRange = false;
				}
			}
		}
		fails += check("all retail item ids in range", inRange);

		// retail spot checks: Lavaridge Herbs {Heal Powder 36, Energy Powder 34,
		// Energy Root 35, Revival Herb 37}; badge marts start with Poke Ball (4);
		// pre-Pokedex sells exactly one Potion (17)
		fails += check("Lavaridge Herbs exact", Arrays.equals(shops[18], new int[]{36, 34, 35, 37}));
		boolean marts = true;
		for (int s = 0; s <= 8; s++) {
			if (shops[s][0] != 4) {
				marts = false;
			}
		}
		fails += check("badge marts head = Poke Ball", marts);
		fails += check("pre-Pokedex = {Potion}", Arrays.equals(shops[9], new int[]{17}));

		// write round-trip: identity, then a real edit reads back + diff-IPS applies
		fails += check("identity write", Arrays.equals(ShopData.write(code, shops), code));
		int[][] edited = new int[shops.length][];
		for (int i = 0; i < shops.length; i++) {
			edited[i] = shops[i].clone();
		}
		edited[18] = new int[]{50, 51, 52, 53}; // Rare Candy, PP Up, Zinc, PP Max
		byte[] patched = ShopData.write(code, edited);
		fails += check("edit reads back", Arrays.equals(ShopData.read(patched)[18], edited[18]));
		byte[] ips = ShopData.diffIPS(code, patched);
		fails += check("diff-IPS small", ips.length < 64);
		fails += check("diff-IPS applies", Arrays.equals(ShopData.applyIPS(code, ips), patched));

		// merge with the zone-limit patch: one code.ips must carry both edits
		byte[] zoneIps = ZoneLimitPatch.buildIPS(4, code);
		byte[] merged = ShopData.mergeIPS(zoneIps, ips);
		byte[] both = ShopData.applyIPS(code, merged);
		fails += check("merged keeps shop edit", Arrays.equals(ShopData.read(both)[18], edited[18]));
		byte[] zoneOnly = ZoneLimitPatch.applyToCode(4, code);
		//THE FILE OFFSET, 0x012bc0 - this read 0x112bc0, the VIRTUAL address, a window neither patch
		//touches, so it compared stock bytes with stock bytes and could not fail
		byte[] zoneRegion = Arrays.copyOfRange(both, 0x012bc0, 0x012bc4);
		fails += check("the window is one the zone patch changes",
				!Arrays.equals(Arrays.copyOfRange(zoneOnly, 0x012bc0, 0x012bc4), Arrays.copyOfRange(code, 0x012bc0, 0x012bc4)));
		fails += check("merged keeps zone patch", Arrays.equals(zoneRegion, Arrays.copyOfRange(zoneOnly, 0x012bc0, 0x012bc4)));

		System.out.println("shops: " + shops.length + " lists, table 0x" + Integer.toHexString(table)
				+ ", ips " + ips.length + "B, merged " + merged.length + "B");
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * The shop patcher refuses a build it was not measured in (F045). It found its anchor anywhere
	 * past 0x400000 and wrote 430 bytes from wherever it ended, with no check that this was the
	 * executable the offsets came from - where its two sibling patchers both refuse first.
	 */
	static int buildCheck() {
		System.out.println("--- the shop patcher refuses a build it was not measured in (F045)");
		int fails = 0;
		byte[] code = madeCode(ShopData.FILE_OFFSET);
		int[][] shops = ShopData.read(code);
		fails += check("control: a code.bin with the anchor at the measured offset reads its 24 shops",
				shops.length == 24 && shops[18][0] == 4);
		int[][] edited = new int[shops.length][];
		for (int i = 0; i < shops.length; i++) {
			edited[i] = shops[i].clone();
		}
		edited[18] = new int[]{50, 51, 52, 53};
		fails += check("and is written", Arrays.equals(ShopData.read(ShopData.write(code, edited))[18], edited[18]));
		byte[] elsewhere = madeCode(ShopData.FILE_OFFSET + 0x100);
		fails += refused("a build whose anchor ends 0x100 later is refused on write",
				() -> ShopData.write(elsewhere, shops), "different build");
		fails += refused("a build whose anchor ends 0x100 later is refused on read",
				() -> ShopData.read(elsewhere), "different build");
		byte[] notItems = code.clone();
		notItems[ShopData.FILE_OFFSET + 20] = (byte) 0xFF;
		notItems[ShopData.FILE_OFFSET + 21] = (byte) 0xFF;
		fails += refused("a table holding a word that is no item is refused",
				() -> ShopData.write(notItems, shops), "which is no item");
		byte[] cut = Arrays.copyOf(code, ShopData.FILE_OFFSET + 100);
		fails += refused("a file too short for the table is refused", () -> ShopData.write(cut, shops), "too short");
		int[][] tooFar = new int[shops.length][];
		for (int i = 0; i < shops.length; i++) {
			tooFar[i] = shops[i].clone();
		}
		tooFar[0][0] = ShopData.ITEM_COUNT;
		fails += refused("an id past the item table is refused", () -> ShopData.write(code, tooFar), "is no item");
		return fails;
	}

	/** A code.bin of zeros whose shop anchor ends at {@code tableAt}, every slot after it a Poke Ball. */
	static byte[] madeCode(int tableAt) {
		byte[] anchor = "\0rom:/DllStartMenu.cro\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
		byte[] code = new byte[tableAt + ShopData.totalItems() * 2 + 64];
		System.arraycopy(anchor, 0, code, tableAt - anchor.length, anchor.length);
		for (int k = 0; k < ShopData.totalItems(); k++) {
			code[tableAt + k * 2] = 4;
		}
		return code;
	}

	/** 0 when {@code r} throws a message holding {@code why}; 1, and a FAIL line, otherwise. */
	static int refused(String what, Runnable r, String why) {
		try {
			r.run();
			return check(what + " (it went through)", false);
		} catch (RuntimeException said) {
			String m = String.valueOf(said.getMessage());
			System.out.println((m.contains(why) ? "ok: " : "") + what + ": " + m);
			return check(what + " in words (" + said + ")", m.contains(why));
		}
	}

	static int check(String what, boolean ok) {
		if (!ok) {
			System.out.println("FAIL: " + what);
			return 1;
		}
		return 0;
	}
}
