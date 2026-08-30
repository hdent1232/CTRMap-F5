package ctrmap.tests;

import ctrmap.AreaForker;
import ctrmap.formats.garc.GARC;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Guards the question "would growing this area damage a map I am not editing?"
 * against the question the zone table can actually answer, "which rows name this
 * area?".
 *
 * <p>Those are not the same question, and confusing them broke every texture
 * import into a custom zone. The area being carried into is read from the
 * editing zone's own header, and forking writes the new id straight into that
 * zone's row - so the editing zone's row ALWAYS names the target area. A guard
 * that counted rows therefore reported every zone as conflicting with itself.
 * It was invisible on paper because the message read "Area 229 is shared with
 * retail zone 473" and 473 really is the zone's index: the sentence is true and
 * the conclusion is wrong.
 *
 * <p>The old scan also stopped at BASE_ZONES on the theory that only shipped
 * zones deserved protection. Wrong twice over: every custom zone in a hack
 * occupies a repurposed retail slot, so customs were treated as retail and
 * blocked, while a genuinely appended zone sharing an area was never noticed.
 *
 * <p>Runs against the pristine dump only - no workspace, no writes.
 *
 * Usage: java ctrmap.tests.AreaShareGuardTest &lt;path-to-a013-garc&gt;
 */
public class AreaShareGuardTest {

	static final int MASTER_ROW = 0x38;
	static final int HDR_AREA_OFF = 2;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/1/3");
		GARC zo = new GARC(garcFile);
		byte[] master = zo.getDecompressedEntry(zo.length - 2);
		if (master == null || master.length % MASTER_ROW != 0) {
			System.out.println("FAIL: master zone table unreadable");
			System.exit(1);
		}
		int zones = master.length / MASTER_ROW;
		System.out.println("master table: " + zones + " zones");

		//--- the bug, stated as a property -------------------------------------
		//Excluding nothing, EVERY zone finds itself. That is the old behaviour,
		//and it is why every carry was refused.
		int selfFound = 0;
		for (int z = 0; z < zones; z++) {
			if (AreaForker.zonesUsingArea(master, area(master, z), -1).contains(z)) {
				selfFound++;
			}
		}
		check(selfFound == zones,
				"every zone names its own area when nothing is excluded (got "
				+ selfFound + "/" + zones + ")");

		//Excluding the zone, NO zone ever finds itself. This is the whole fix.
		int selfLeaked = 0;
		for (int z = 0; z < zones; z++) {
			if (AreaForker.zonesUsingArea(master, area(master, z), z).contains(z)) {
				selfLeaked++;
			}
		}
		check(selfLeaked == 0, "no zone reports itself as a sharer (leaked " + selfLeaked + ")");

		//--- agreement with an independent count -------------------------------
		Map<Integer, Integer> byArea = new HashMap<>();
		for (int z = 0; z < zones; z++) {
			Integer prev = byArea.get(area(master, z));
			byArea.put(area(master, z), prev == null ? 1 : prev + 1);
		}
		int disagreed = 0, privateZones = 0;
		for (int z = 0; z < zones; z++) {
			int expected = byArea.get(area(master, z)) - 1; //everyone else on this area
			int got = AreaForker.zonesUsingArea(master, area(master, z), z).size();
			if (got != expected) {
				disagreed++;
			}
			if (expected == 0) {
				privateZones++;
			}
		}
		check(disagreed == 0, "agrees with a straight per-area tally for all "
				+ zones + " zones (" + disagreed + " disagreed)");
		System.out.println("    zones already on a private area: " + privateZones
				+ "; sharing one: " + (zones - privateZones));
		check(privateZones > 0 && privateZones < zones,
				"the corpus contains both private and shared areas (a test on all-private"
				+ " or all-shared data would prove nothing)");

		//--- a forked zone is free ---------------------------------------------
		//Give a zone an area no other zone uses - what AreaForker does - and the
		//guard must report it clear. This is the case that failed in the field.
		int maxArea = 0;
		for (int a : byArea.keySet()) {
			maxArea = Math.max(maxArea, a);
		}
		for (int zone : new int[]{473, 474, 153, 157}) {
			if (zone >= zones) {
				continue;
			}
			byte[] forked = Arrays.copyOf(master, master.length);
			int fresh = ++maxArea;
			forked[zone * MASTER_ROW + HDR_AREA_OFF] = (byte) (fresh & 0xFF);
			forked[zone * MASTER_ROW + HDR_AREA_OFF + 1] = (byte) ((fresh >> 8) & 0xFF);
			List<Integer> hits = AreaForker.zonesUsingArea(forked, fresh, zone);
			check(hits.isEmpty(), "zone " + zone + " forked onto private area " + fresh
					+ " is unblocked (got " + hits + ")");
			//and the zone is still visible to a caller that does not exclude it
			check(AreaForker.zonesUsingArea(forked, fresh, -1).equals(
					java.util.Collections.singletonList(zone)),
					"zone " + zone + " is still findable when nothing is excluded");
		}

		//--- an APPENDED zone must still protect a retail one ------------------
		//The old scan stopped at BASE_ZONES, so a zone added past the retail
		//count could share an area and never be reported. Append a row on the
		//area retail zone 15 uses and check both directions.
		int retailArea = area(master, 15);
		byte[] grown = Arrays.copyOf(master, master.length + MASTER_ROW);
		int appended = zones;
		grown[appended * MASTER_ROW + HDR_AREA_OFF] = (byte) (retailArea & 0xFF);
		grown[appended * MASTER_ROW + HDR_AREA_OFF + 1] = (byte) ((retailArea >> 8) & 0xFF);
		check(AreaForker.zonesUsingArea(grown, retailArea, appended).contains(15),
				"an appended zone sees the retail zone already on its area");
		check(AreaForker.zonesUsingArea(grown, retailArea, 15).contains(appended),
				"a retail zone sees the appended zone on its area (the old BASE_ZONES"
				+ " cap looked past it)");

		//--- degenerate input ---------------------------------------------------
		check(AreaForker.zonesUsingArea(null, 0, -1).isEmpty(), "null table yields nothing");
		check(AreaForker.zonesUsingArea(new byte[7], 0, -1).isEmpty(), "runt table yields nothing");
		check(AreaForker.zonesUsingArea(new byte[MASTER_ROW * 3 + 5], 0, -1).size() == 3,
				"a trailing partial row is ignored, not misread");

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	static int area(byte[] master, int zone) {
		int o = zone * MASTER_ROW + HDR_AREA_OFF;
		return (master[o] & 0xFF) | ((master[o + 1] & 0xFF) << 8);
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}
