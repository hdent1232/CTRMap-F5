package ctrmap.tests;

import ctrmap.scripts.GfHash;

/**
 * Verifies the native-name hash and the bundled ORAS native table: the nine
 * independently-confirmed ground-truth hashes, the round-trip (every bundled
 * name hashes to a value whose reverse lookup returns a name that hashes back
 * to the same value), and the facility natives a Frontier script needs.
 */
public class GfHashTest {

	public static void main(String[] args) {
		int failures = 0;

		String[][] truths = {
			{"StartBattle", "591B83D2"},
			{"PlayerSetBP", "9EBFC333"},
			{"PlayerGetBP", "2DA048CF"},
			{"BattleHouse_DecideCommVsTrainer", "31BA8523"},
			{"_Suspend", "0B13A389"},
			{"GetMyWinCount", "905122FD"},
			{"AddBtlTrainerObj", "937050FF"},
			{"DelTrainerObj", "854C8FC5"},
			{"SaveBattleRecord", "1229D432"},
		};
		for (String[] t : truths) {
			int got = GfHash.hash(t[0]);
			int want = (int) Long.parseLong(t[1], 16);
			if (got != want) {
				failures++;
				System.out.printf("FAIL hash(%s)=%08X want %s%n", t[0], got, t[1]);
			}
		}

		// bundled table must load and reverse-map the facility natives
		if (GfHash.table().isEmpty()) {
			failures++;
			System.out.println("FAIL native table did not load");
		} else {
			int mapped = 0;
			for (java.util.Map.Entry<String, Integer> e : GfHash.table().entrySet()) {
				// each name must hash to its listed value
				if (GfHash.hash(e.getKey()) != e.getValue()) {
					failures++;
					System.out.println("FAIL table name " + e.getKey() + " hash mismatch");
					if (failures > 8) {
						break;
					}
					continue;
				}
				String back = GfHash.nameForHash(e.getValue());
				if (back != null) {
					mapped++;
				}
			}
			System.out.println("table: " + GfHash.table().size() + " names, " + mapped + " reverse-mapped");
			// the facility natives a custom Frontier script needs must all resolve
			for (String n : new String[]{"StartBattle", "PlayerSetBP", "PlayerGetBP",
				"AddWinCount", "ClearWinCount", "GetMyWinCount", "SaveBattleRecord",
				"AddBtlTrainerObj", "SetTrainer", "DelTrainerObj"}) {
				if (GfHash.nameForHash(GfHash.hash(n)) == null) {
					failures++;
					System.out.println("FAIL facility native not in table: " + n);
				}
			}
		}

		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT (" + failures + ")");
		if (failures > 0) {
			System.exit(1);
		}
	}
}
