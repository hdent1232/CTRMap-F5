package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The map painter's Apply writes its map as one unit: the fork, every region and the area, or
 * none of them (F112).
 *
 * <p>WHY THIS SUITE EXISTS. Apply staged everything first and then committed in a plain sequence
 * of writes - the zone's map forked when it was shared, four subfiles per region, then the area's
 * subfiles one by one - and any failure on the way reached a report that said "Nothing was written
 * - the map is exactly as it was". A full disk on the second region left a forked zone with half a
 * map, and the user was told it was untouched.
 *
 * <p>ORDER: needs no dump and no display. Apply itself needs a zone of the game, so this reads the
 * order of its commit in its source; PaintApplyGuardsTest drives Apply on the owner's dump.
 *
 * Usage: java ctrmap.tests.PaintCommitTest
 */
public class PaintCommitTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		String src = SourceSeamTest.stripComments(new String(Files.readAllBytes(
				new File("src/ctrmap/humaninterface/TilePainterForm.java").toPath()), StandardCharsets.UTF_8));
		String apply = CollisionReadTest.body(src, "public static String applyToZone(");
		check(apply != null && apply.contains("ensurePrivate(") && apply.contains("area.commit()")
				&& apply.contains("could not write region"), "control: applyToZone is found, its fork, region writes and area commit in it");
		int begin = apply == null ? -1 : apply.indexOf("WorkspaceUnit.begin(");
		int fork = apply == null ? -1 : apply.indexOf("ensurePrivate(");
		int region = apply == null ? -1 : apply.indexOf("could not write region");
		int area = apply == null ? -1 : apply.indexOf("area.commit()");
		int commit = apply == null ? -1 : apply.indexOf("unit.commit()");
		check(begin >= 0 && begin < fork, "a unit is open before the zone's map is forked");
		check(commit > area && area > region && region > fork,
				"and it is committed only after every region and the area are written (begin " + begin + ", fork " + fork
				+ ", regions " + region + ", area " + area + ", commit " + commit + ")");
		check(apply != null && apply.indexOf("unit.commit()", commit + 1) < 0, "and committed once");

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
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
