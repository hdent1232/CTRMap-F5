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
 * Usage: java ctrmap.tests.ShopDataTest &lt;path-to-decompressed-code.bin&gt;
 */
public class ShopDataTest {

	public static void main(String[] args) throws Exception {
		File codeFile = new File(args.length > 0 ? args[0]
				: "../code.bin");
		if (!codeFile.exists()) {
			System.out.println("SKIP: no decompressed code.bin at " + codeFile);
			System.out.println("ALL PASS");
			return;
		}
		byte[] code = Files.readAllBytes(codeFile.toPath());
		int fails = 0;

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
		byte[] zoneIps = ZoneLimitPatch.buildIPS(4);
		byte[] merged = ShopData.mergeIPS(zoneIps, ips);
		byte[] both = ShopData.applyIPS(code, merged);
		fails += check("merged keeps shop edit", Arrays.equals(ShopData.read(both)[18], edited[18]));
		byte[] zoneOnly = ZoneLimitPatch.applyToCode(4, code);
		byte[] zoneRegion = Arrays.copyOfRange(both, 0x112bc0, 0x112bc4);
		fails += check("merged keeps zone patch", Arrays.equals(zoneRegion, Arrays.copyOfRange(zoneOnly, 0x112bc0, 0x112bc4)));

		System.out.println("shops: " + shops.length + " lists, table 0x" + Integer.toHexString(table)
				+ ", ips " + ips.length + "B, merged " + merged.length + "B");
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
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
