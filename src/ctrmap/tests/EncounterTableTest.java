package ctrmap.tests;

import ctrmap.formats.encounters.EncounterTable;
import ctrmap.formats.garc.GARC;
import java.io.File;
import java.util.Arrays;

/**
 * Encounter codec validation against the retail EN pack:
 * (1) every zone: parse -> re-emit must round-trip the whole pack
 *     BYTE-IDENTICALLY (covers all 150 non-empty blobs + all empties);
 * (2) anchors: Route 101 (zone 23) grass must be Wurmple/Zigzagoon/Poochyena,
 *     Petalburg (zone 13) old rod must contain Magikarp;
 * (3) mutation: giving an empty zone a table and removing it again must
 *     restore the original pack byte-identically, leaving every other zone's
 *     blob untouched in between.
 *
 * Usage: java ctrmap.tests.EncounterTableTest <path-to-a013-garc>
 */
public class EncounterTableTest {

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/1/3");
		GARC zo = new GARC(garcFile);
		byte[] pack = zo.getDecompressedEntry(zo.length - 1);
		int count = (pack[2] & 0xFF) | ((pack[3] & 0xFF) << 8);
		System.out.println("EN pack: " + pack.length + " bytes, " + count + " zones");
		int failures = 0, nonEmpty = 0;

		// (1) whole-pack round-trip via every zone
		for (int z = 0; z < count; z++) {
			EncounterTable t = EncounterTable.read(pack, z);
			if (t != null) {
				nonEmpty++;
			}
			byte[] re = EncounterTable.write(pack, z, t);
			if (!Arrays.equals(re, pack)) {
				failures++;
				System.out.println("FAIL zone " + z + ": no-op write not byte-identical");
				if (failures > 5) {
					break;
				}
			}
		}
		System.out.println("round-trip: " + count + " zones (" + nonEmpty + " with wild data)");

		// (2) anchors
		EncounterTable r101 = EncounterTable.read(pack, 23);
		int[] want = {265, 263, 261}; //Wurmple, Zigzagoon, Poochyena
		for (int sp : want) {
			boolean found = false;
			for (EncounterTable.Slot s : r101.banks[0]) {
				if (s.species == sp) {
					found = true;
				}
			}
			if (!found) {
				failures++;
				System.out.println("FAIL anchor: species " + sp + " missing from Route 101 grass");
			}
		}
		EncounterTable petalburg = EncounterTable.read(pack, 13);
		boolean karp = false;
		for (EncounterTable.Slot s : petalburg.banks[5]) {
			if (s.species == 129) {
				karp = true;
			}
		}
		if (!karp) {
			failures++;
			System.out.println("FAIL anchor: Magikarp missing from Petalburg old rod");
		}

		// (3) mutation on an empty zone
		int emptyZone = -1;
		for (int z = 0; z < count; z++) {
			if (EncounterTable.read(pack, z) == null) {
				emptyZone = z;
				break;
			}
		}
		EncounterTable nt = new EncounterTable();
		nt.banks[0][0].species = 261; //Poochyena
		nt.banks[0][0].minLevel = 5;
		nt.banks[0][0].maxLevel = 7;
		nt.banks[3][0].species = 129; //Magikarp surf, why not
		nt.banks[3][0].minLevel = 10;
		nt.banks[3][0].maxLevel = 15;
		byte[] mutated = EncounterTable.write(pack, emptyZone, nt);
		EncounterTable back = EncounterTable.read(mutated, emptyZone);
		if (back == null || back.banks[0][0].species != 261 || back.banks[0][0].maxLevel != 7
				|| back.banks[3][0].species != 129 || back.rates[0] == 0 || back.rates[3] == 0) {
			failures++;
			System.out.println("FAIL mutation: new table did not read back");
		}
		//every OTHER zone identical in the mutated pack
		for (int z = 0; z < count && failures <= 5; z++) {
			if (z == emptyZone) {
				continue;
			}
			byte[] a = blob(pack, z), b = blob(mutated, z);
			if (!Arrays.equals(a, b)) {
				failures++;
				System.out.println("FAIL mutation: zone " + z + " blob changed");
			}
		}
		byte[] restored = EncounterTable.write(mutated, emptyZone, null);
		if (!Arrays.equals(restored, pack)) {
			failures++;
			System.out.println("FAIL mutation: remove did not restore the original pack");
		}

		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT (" + failures + ")");
		if (failures > 0) {
			System.exit(1);
		}
	}

	static byte[] blob(byte[] pack, int z) {
		int o0 = le32(pack, 4 + z * 4), o1 = le32(pack, 4 + (z + 1) * 4);
		return Arrays.copyOfRange(pack, o0, o1);
	}

	static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
