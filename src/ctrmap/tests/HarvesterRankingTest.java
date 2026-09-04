package ctrmap.tests;

import ctrmap.tools.BuildingHarvester;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The harvester's naming rule, on inputs this suite builds itself.
 *
 * <p>WHY THIS EXISTS BESIDE {@link HarvesterGuardsTest}. That suite sweeps real
 * regions out of the dump and checks the rule against every component it finds,
 * which is the right test of the rule as a whole - but it can only judge the
 * cases the dump happens to contain, and it says so: two of its checks print a
 * skip when the sweep turns up no anchor for them. The clause that decides
 * whether a family names the cut at all was resting on ONE such anchor, a
 * 1,163-triangle building holding two triangles of lamp glass. Nothing about
 * that component is guaranteed; change the donor regions the sweep looks at and
 * the clause goes unmeasured again with the battery green.
 *
 * <p>So the same rule is stated here against components made of nothing but a
 * material tally, where each clause of it has a case that isolates it:
 * <ul>
 * <li>the family owning the MOST triangles names the cut - not the first family
 *     in the table that matches anything, which is the live defect this rule was
 *     written for: twelve triangles of lamp glass named a furnished room
 *     "Littleroot Town lamp", and 127 catalogue entries were filed under a
 *     family another family outweighed;</li>
 * <li>a tie keeps the earlier family, which is the only thing the table's order
 *     still decides;</li>
 * <li>...and the winning family must beat the biggest SINGLE material no family
 *     recognises, or the cut is named for its size instead. A cut that is mostly
 *     unrecognised ground with a lamp in the corner is not a lamp;</li>
 * <li>a cut nothing recognises is named for its size: two tiles or less each
 *     way is decor, anything bigger is a structure.</li>
 * </ul>
 * A break in any of these is not noticed until somebody re-harvests, and then it
 * is noticed as three and a half thousand wrongly named rows.
 *
 * <p>The tally is injected by reflection because a Comp is normally filled by
 * the sweep that reads geometry, and adding a way to build one by hand would put
 * a constructor in the harvester that only a test would ever call.
 *
 * Usage: java ctrmap.tests.HarvesterRankingTest      (no corpus needed)
 */
public class HarvesterRankingTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		theNamesTheRuleReads();
		theBiggestFamilyNamesTheCut();
		aTieKeepsTheEarlierFamily();
		aFamilyMustBeatWhatNothingRecognises();
		aCutNothingRecognisesIsNamedForItsSize();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * The material names used below really do belong to the families this suite
	 * claims, so a failure further down is a failure of the ranking and not of
	 * the reader underneath it.
	 */
	static void theNamesTheRuleReads() {
		List<String> wrong = new ArrayList<>();
		expect(wrong, "yane_roof", "building");
		expect(wrong, "chip_kabe_a", "building");
		expect(wrong, "lamp_glass", "lamp");
		expect(wrong, "platan_leaf", "tree");
		expect(wrong, "chip_ground01", null);
		check(wrong.isEmpty(), "the material names this suite ranks are read as the families it says"
				+ (wrong.isEmpty() ? "" : " - " + wrong));
	}

	/**
	 * The anchor case, made rather than found: a building with a lamp in it is
	 * a building, because building owns more of it.
	 */
	static void theBiggestFamilyNamesTheCut() throws Exception {
		BuildingHarvester.Comp room = comp(6, 5, "lamp_glass", 12, "yane_roof", 924, "chip_kabe_a", 239);
		check("building".equals(room.hint()),
				"a 1163-triangle building holding 12 triangles of lamp glass is called a building"
				+ " - it is called \"" + room.hint() + "\"");

		//and the same tally the other way up, so the answer cannot be the table's
		//order pretending to be a count
		BuildingHarvester.Comp lantern = comp(2, 2, "lamp_glass", 924, "yane_roof", 12);
		check("lamp".equals(lantern.hint()),
				"and a lamp holding 12 triangles of roof is called a lamp - it is called \""
				+ lantern.hint() + "\"");

		//one recognised material and nothing else: the smallest case in which a
		//family can name a cut at all, and the one that does not depend on which
		//order a hash map hands its entries back
		BuildingHarvester.Comp plain = comp(5, 5, "yane_roof", 100);
		check("building".equals(plain.hint()),
				"a cut made of one building material is a building - it is called \""
				+ plain.hint() + "\"");
	}

	/** The table's order is the tie-break order, and nothing else. */
	static void aTieKeepsTheEarlierFamily() throws Exception {
		BuildingHarvester.Comp tie = comp(5, 5, "platan_leaf", 50, "yane_roof", 50);
		check("tree".equals(tie.hint()),
				"50 triangles of tree against 50 of building keeps the earlier family, tree"
				+ " - it is called \"" + tie.hint() + "\"");
	}

	/**
	 * The clause the dump could stop anchoring: a family that does not outweigh
	 * the biggest single material nothing recognises must not name the cut.
	 */
	static void aFamilyMustBeatWhatNothingRecognises() throws Exception {
		BuildingHarvester.Comp mostlyGround = comp(5, 5, "lamp_glass", 40, "chip_ground01", 900);
		check("structure".equals(mostlyGround.hint()),
				"a 5x5 cut of 900 triangles of unrecognised ground with 40 of lamp in it is named for"
				+ " its size, not called a lamp - it is called \"" + mostlyGround.hint() + "\"");

		BuildingHarvester.Comp mostlyLamp = comp(5, 5, "lamp_glass", 901, "chip_ground01", 900);
		check("lamp".equals(mostlyLamp.hint()),
				"and once the lamp outweighs that ground by one triangle it is a lamp - it is called \""
				+ mostlyLamp.hint() + "\"");
	}

	/** Nothing recognised: the cut is named for how much ground it covers. */
	static void aCutNothingRecognisesIsNamedForItsSize() throws Exception {
		check("decor".equals(comp(2, 2, "chip_ground01", 300).hint()),
				"a 2x2 cut nothing recognises is decor");
		check("structure".equals(comp(3, 2, "chip_ground01", 300).hint()),
				"a 3x2 cut nothing recognises is a structure");
		check("structure".equals(comp(2, 3, "chip_ground01", 300).hint()),
				"and so is a 2x3 one");
	}

	/**
	 * A component of the given tile size whose geometry is the given material
	 * tally: pairs of material name and triangle count.
	 */
	static BuildingHarvester.Comp comp(int tilesW, int tilesH, Object... tally) throws Exception {
		BuildingHarvester.Comp c = new BuildingHarvester.Comp();
		c.tx0 = 10;
		c.ty0 = 10;
		c.tx1 = 10 + tilesW - 1;
		c.ty1 = 10 + tilesH - 1;
		Field f = BuildingHarvester.Comp.class.getDeclaredField("matFaces");
		f.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<String, Integer> mats = (Map<String, Integer>) f.get(c);
		for (int i = 0; i < tally.length; i += 2) {
			mats.put((String) tally[i], (Integer) tally[i + 1]);
		}
		if (c.tilesW() != tilesW || c.tilesH() != tilesH) {
			throw new IllegalStateException("built a " + c.tilesW() + "x" + c.tilesH()
					+ " component when " + tilesW + "x" + tilesH + " was asked for");
		}
		return c;
	}

	static void expect(List<String> wrong, String material, String family) {
		String[] got = BuildingHarvester.familyOf(material);
		String hint = got == null ? null : got[0];
		if (family == null ? hint != null : !family.equals(hint)) {
			wrong.add("\"" + material + "\" reads as " + hint + ", not " + family);
		}
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
