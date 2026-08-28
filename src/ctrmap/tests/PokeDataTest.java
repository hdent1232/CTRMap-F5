package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.text.GFMessageFile;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Verifies the reference-data decode PokeData relies on (reads the GARCs
 * directly so it needs no Workspace): personal a/1/9/5 base stats + types, and
 * the a/1/8/9 move mini-container type/category/power, against well-known
 * anchors. Guards the preview cards against a wrong offset silently showing
 * garbage.
 *
 * Usage: java ctrmap.tests.PokeDataTest <romfs-root>
 */
public class PokeDataTest {

	public static void main(String[] args) throws Exception {
		String root = args.length > 0 ? args[0]
				: "../RomFS/000400000011C400";
		int failures = 0;

		GARC personal = new GARC(new File(root + "/a/1/9/5"), false);
		// species 1 Bulbasaur = Grass(11)/Poison(3), 45/49/49/45/65/65
		byte[] b = personal.getDecompressedEntry(1);
		failures += expect("Bulbasaur HP", b[0] & 0xFF, 45);
		failures += expect("Bulbasaur type1", b[6] & 0xFF, 11);
		failures += expect("Bulbasaur type2", b[7] & 0xFF, 3);
		// species 6 Charizard = Fire(9)/Flying(2)
		byte[] c = personal.getDecompressedEntry(6);
		failures += expect("Charizard type1", c[6] & 0xFF, 9);
		failures += expect("Charizard type2", c[7] & 0xFF, 2);
		// species 25 Pikachu = Electric(12) mono
		byte[] pk = personal.getDecompressedEntry(25);
		failures += expect("Pikachu type1", pk[6] & 0xFF, 12);
		failures += expect("Pikachu type2", pk[7] & 0xFF, 12);

		// moves
		GARC mv = new GARC(new File(root + "/a/1/8/9"), false);
		byte[] mini = mv.getDecompressedEntry(0);
		// Pound(1) Normal(0)/Physical(1)/40, Thunderbolt(85) Electric(12)/Special(2)/90
		int[] pound = move(mini, 1);
		failures += expect("Pound type", pound[0], 0);
		failures += expect("Pound category", pound[1], 1);
		failures += expect("Pound power", pound[2], 40);
		int[] tbolt = move(mini, 85);
		failures += expect("Thunderbolt type", tbolt[0], 12);
		failures += expect("Thunderbolt category", tbolt[1], 2);
		failures += expect("Thunderbolt power", tbolt[2], 90);

		// names line up (GameText 98 species, 14 moves, 18 types, 37 abilities)
		List<String> sp = GFMessageFile.getStrings(read(root, 98));
		failures += expect("species name 1 = Bulbasaur", sp.get(1).equalsIgnoreCase("Bulbasaur") ? 1 : 0, 1);
		List<String> mn = GFMessageFile.getStrings(read(root, 14));
		failures += expect("move name 1 = Pound", mn.get(1).equalsIgnoreCase("Pound") ? 1 : 0, 1);
		List<String> tn = GFMessageFile.getStrings(read(root, 18));
		failures += expect("type 0 = Normal", tn.get(0).equalsIgnoreCase("Normal") ? 1 : 0, 1);
		failures += expect("type 17 = Fairy", tn.get(17).equalsIgnoreCase("Fairy") ? 1 : 0, 1);

		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT (" + failures + ")");
		if (failures > 0) {
			System.exit(1);
		}
	}

	static byte[] read(String root, int entry) throws Exception {
		GARC tx = new GARC(new File(root + "/a/0/7/3"), false);
		return tx.getDecompressedEntry(entry);
	}

	static int[] move(byte[] mini, int mv) {
		int o = i32(mini, 4 + mv * 4);
		return new int[]{mini[o] & 0xFF, mini[o + 2] & 0xFF, mini[o + 3] & 0xFF};
	}

	static int expect(String label, int got, int want) {
		if (got != want) {
			System.out.println("FAIL " + label + ": got " + got + " want " + want);
			return 1;
		}
		return 0;
	}

	static int i32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
