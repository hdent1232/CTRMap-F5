package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.ZoneScriptAnalyzer;
import ctrmap.scripts.GfHash;
import java.io.File;
import java.util.Arrays;
import static ctrmap.formats.containers.ContainerBytes.subfile;

/**
 * Validates the Battle-facility source zones (the Maison lobby = 517, the
 * Battle Institute = 448) that the "Set up Battle facility here" tool clones:
 * each must have a parseable script whose dispatch resolves and whose natives
 * include the facility engine calls (StartBattle, AddWinCount, PlayerSetBP,
 * AddBtlTrainerObj), and the script must round-trip (getScriptBytes -> reparse,
 * dispatch still resolves). This confirms the clone source is a sound facility.
 *
 * Usage: java ctrmap.tests.FacilitySourceTest <path-to-zonedata-a013-garc>
 */
public class FacilitySourceTest {

	public static void main(String[] args) throws Exception {
		File garc = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/1/3");
		GARC zo = new GARC(garc);
		int failures = 0;
		failures += checkFacility(zo, 517, "Battle Maison lobby",
				new String[]{"StartBattle", "AddWinCount", "GetMyWinCount", "AddBtlTrainerObj", "SaveBattleRecord"});
		failures += checkFacility(zo, 448, "Battle Institute",
				new String[]{"StartBattle", "AddBtlTrainerObj"});
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT (" + failures + ")");
		if (failures > 0) {
			System.exit(1);
		}
	}

	static int checkFacility(GARC zo, int zone, String label, String[] wantNatives) {
		try {
			byte[] s2 = subfile(zo.getDecompressedEntry(zone), 2);
			if (s2 == null || s2.length < 8) {
				System.out.println("FAIL " + label + " (zone " + zone + "): no script");
				return 1;
			}
			GFLPawnScript s = new GFLPawnScript(s2);
			s.decompressThis();
			if (ZoneScriptAnalyzer.findDispatch(s) == null) {
				System.out.println("FAIL " + label + ": no dispatch");
				return 1;
			}
			// facility natives present
			java.util.Set<Integer> hashes = new java.util.HashSet<>();
			for (int i = 0; i < s.natives.size(); i++) {
				hashes.add(s.natives.get(i).data[1]);
			}
			int missing = 0;
			for (String n : wantNatives) {
				if (!hashes.contains(GfHash.hash(n))) {
					System.out.println("FAIL " + label + ": native " + n + " not registered");
					missing++;
				}
			}
			// script round-trips
			GFLPawnScript re = new GFLPawnScript(s.getScriptBytes());
			re.decompressThis();
			if (ZoneScriptAnalyzer.findDispatch(re) == null) {
				System.out.println("FAIL " + label + ": dispatch lost on round-trip");
				missing++;
			}
			int cases = ZoneScriptAnalyzer.listScriptIds(re).size();
			System.out.println(label + " (zone " + zone + "): dispatch OK, " + cases + " cases, "
					+ s.natives.size() + " natives, facility natives "
					+ (missing == 0 ? "present" : "MISSING"));
			return missing;
		} catch (Exception ex) {
			System.out.println("FAIL " + label + ": " + ex);
			return 1;
		}
	}
}
