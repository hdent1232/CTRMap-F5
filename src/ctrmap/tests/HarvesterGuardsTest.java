package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.BuildingCatalog;
import ctrmap.tools.BuildingHarvester;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The harvester's own decisions, driven on real regions.
 *
 * <p>WHY THIS IS NOT {@link BuildingCatalogTest}. That suite validates the
 * shipped TSV - 3,479 rows of metadata, committed data that no longer changes
 * when the harvester does. These are the functions that PRODUCE it, and every
 * one of them could be broken with the whole battery still green, because
 * nothing runs them: the naming rule that decides what a cut is called, and the
 * footing that decides how high off the ground it stamps. A break here is not
 * noticed until somebody re-harvests, and then it is noticed as 3,479 wrong
 * rows.
 *
 * <p>WHAT IS ASSERTED.
 * <ul>
 * <li>Which keyword family a material name belongs to, against names taken from
 *     the dump - a roof is a building, a gake is a rock, and a ground chip is
 *     nothing at all. The family table's order is its tie-break order, so this
 *     is checked by hand rather than by asking the same loop twice.</li>
 * <li>A cut is named after what DOMINATES it. The live defect: the rule asked
 *     its families in a fixed order and took the first that matched anything,
 *     so twelve triangles of lamp glass named a 4,733-triangle furnished room
 *     "Littleroot Town lamp", and 127 catalogue entries were filed under a
 *     family another family outweighed. The anchor for that case is found in
 *     the dump, not assumed: a component holding both a lamp-family and a
 *     building-family material, where building owns more of it.</li>
 * <li>...and only when the winning family beats the biggest single material no
 *     family recognises, otherwise the cut is named for its size.</li>
 * <li>A structure's baseY is its own footing. The live defect: the LOWEST
 *     ground under the whole box gave a cliff-top lamp standing at 153 a baseY
 *     of 0 - the sea at the foot of the cliff - and the palette stamped it 153
 *     units into the air. So no component may be filed with a footing outside
 *     its own body: no lower than half a step below its lowest face, no higher
 *     than its top.</li>
 * </ul>
 *
 * Usage: java ctrmap.tests.HarvesterGuardsTest &lt;romfs-root&gt; [regions]
 */
public class HarvesterGuardsTest {

	/** Donor regions to sweep; every catalogue cut came out of one of these. */
	private static final int DEFAULT_REGIONS = 8;

	static int fails = 0;

	/** name -> the family hint it must belong to, or null for "nothing recognises it". */
	private static final String[][] FAMILY_CASES = {
		{"yane_roof01", "building"},
		{"chip_kabe_a", "building"},
		{"mado_glass", "building"},
		{"d112r0103_gake2", "rock"},
		{"chip_iwa_01", "rock"},
		{"lamp_glass", "lamp"},
		{"kanban_a", "sign"},
		{"platan_leaf", "tree"},
		{"kaidan_stone", "stairs"},
		{"chip_ground01", null},
		{"chip_sea_b", null},
	};

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		File garcFile = new File(dump.getAbsolutePath()
				+ Workspace.getArchivePath(Workspace.ArchiveType.FIELD_DATA, Workspace.GameType.ORAS));
		if (!garcFile.isFile()) {
			System.out.println("  skip: no pristine FieldData archive under " + dump + " (pass the romfs root as args[0])");
			System.out.println("ALL PASS");
			return;
		}
		int wanted = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_REGIONS;

		materialFamiliesAreRead();

		GARC gr = new GARC(garcFile);
		List<BuildingHarvester.Comp> comps = sweep(gr, wanted);
		check(comps.size() > 20, "the sweep found " + comps.size() + " structures to judge");
		aCutIsNamedAfterWhatDominatesIt(comps);
		anUnrecognisedCutIsNamedForItsSize(comps);
		aStructureStandsOnItsOwnFooting(comps);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** The keyword table read by hand, so a broken reading of it cannot agree with itself. */
	static void materialFamiliesAreRead() {
		List<String> wrong = new ArrayList<>();
		for (String[] c : FAMILY_CASES) {
			String[] got = BuildingHarvester.familyOf(c[0]);
			String hint = got == null ? null : got[0];
			if (c[1] == null ? hint != null : !c[1].equals(hint)) {
				wrong.add("\"" + c[0] + "\" reads as " + hint + ", not " + c[1]);
			}
		}
		check(wrong.isEmpty(), FAMILY_CASES.length + " material names are placed in the right family"
				+ (wrong.isEmpty() ? "" : " - " + wrong));
	}

	/**
	 * The naming rule, twice over: the anchor case the fix was written for, and
	 * then every component of the sweep against the rule stated in prose - the
	 * family owning the most triangles, only when it beats the biggest single
	 * material nothing recognises, ties to the earlier family, otherwise size.
	 */
	static void aCutIsNamedAfterWhatDominatesIt(List<BuildingHarvester.Comp> comps) {
		BuildingHarvester.Comp anchor = null;
		int lampTris = 0, buildingTris = 0;
		for (BuildingHarvester.Comp c : comps) {
			int lamp = 0, building = 0;
			for (Map.Entry<String, Integer> m : c.materialTriangles().entrySet()) {
				String f = familyHint(m.getKey());
				if ("lamp".equals(f)) {
					lamp += m.getValue();
				} else if ("building".equals(f)) {
					building += m.getValue();
				}
			}
			if (lamp > 0 && building > lamp && (anchor == null || building > buildingTris)) {
				anchor = c;
				lampTris = lamp;
				buildingTris = building;
			}
		}
		if (anchor == null) {
			System.out.println("  skip: no swept component mixes a lamp material into a building");
		} else {
			check("building".equals(anchor.hint()), "a " + buildingTris + "-triangle building holding "
					+ lampTris + " triangles of lamp is called a building, not a lamp - it is called \""
					+ anchor.hint() + "\"");
		}

		List<String> wrong = new ArrayList<>();
		int named = 0;
		for (BuildingHarvester.Comp c : comps) {
			String want = expectedHint(c);
			if (!want.equals(c.hint())) {
				if (wrong.size() < 3) {
					wrong.add(describe(c) + " is called \"" + c.hint() + "\", but " + want + " dominates it");
				}
			}
			if (!"decor".equals(c.hint()) && !"structure".equals(c.hint())) {
				named++;
			}
		}
		check(named > 0, named + " of the swept components are named after a keyword family at all"
				+ " (if none are, the rule below proves nothing)");
		check(wrong.isEmpty(), "every swept component is named after the family that owns the most of it"
				+ (wrong.isEmpty() ? "" : " - " + wrong));
	}

	/**
	 * The extra clause: a family that does NOT outweigh the biggest single
	 * unrecognised material must not name the cut. Stated as its own check
	 * because the sweep above would still pass if both the rule and this
	 * reading of it lost the clause together.
	 */
	static void anUnrecognisedCutIsNamedForItsSize(List<BuildingHarvester.Comp> comps) {
		BuildingHarvester.Comp anchor = null;
		int famTris = 0, unnamedTris = 0;
		for (BuildingHarvester.Comp c : comps) {
			Map<String, Integer> perFamily = new HashMap<>();
			int unnamed = 0;
			for (Map.Entry<String, Integer> m : c.materialTriangles().entrySet()) {
				String f = familyHint(m.getKey());
				if (f == null) {
					unnamed = Math.max(unnamed, m.getValue());
				} else {
					perFamily.put(f, perFamily.containsKey(f) ? perFamily.get(f) + m.getValue() : m.getValue());
				}
			}
			int best = 0;
			for (int v : perFamily.values()) {
				best = Math.max(best, v);
			}
			if (best > 0 && best <= unnamed && unnamed > unnamedTris) {
				anchor = c;
				famTris = best;
				unnamedTris = unnamed;
			}
		}
		if (anchor == null) {
			System.out.println("  skip: every swept component's best family outweighs its biggest unknown material");
			return;
		}
		String hint = anchor.hint();
		check("decor".equals(hint) || "structure".equals(hint),
				"a cut whose best family owns " + famTris + " triangles against " + unnamedTris
				+ " of a material nothing recognises is named for its size, not for the family - it is called \""
				+ hint + "\"");
	}

	/**
	 * Nothing may be filed standing in mid-air or buried: the footing has to be
	 * a height the structure itself reaches.
	 */
	static void aStructureStandsOnItsOwnFooting(List<BuildingHarvester.Comp> comps) {
		List<String> wrong = new ArrayList<>();
		for (BuildingHarvester.Comp c : comps) {
			if (c.baseY < c.minY - 9.5f || c.baseY > c.maxY + 0.5f) {
				if (wrong.size() < 3) {
					wrong.add(describe(c) + " stands from " + c.minY + " to " + c.maxY
							+ " but is footed at " + c.baseY);
				}
			}
		}
		check(wrong.isEmpty(), "every swept structure's footing is a height it actually reaches"
				+ (wrong.isEmpty() ? "" : " - " + wrong));
	}

	/** Non-terrain components of the first donor regions the catalogue uses. */
	static List<BuildingHarvester.Comp> sweep(GARC gr, int wanted) {
		Set<Integer> regions = new LinkedHashSet<>();
		for (BuildingCatalog.Entry e : BuildingCatalog.entries()) {
			if (regions.size() >= wanted) {
				break;
			}
			regions.add(e.donorRegion);
		}
		List<BuildingHarvester.Comp> out = new ArrayList<>();
		for (int r : regions) {
			byte[] rc = gr.getDecompressedEntry(r);
			byte[] model = sub(rc, 1), coll = sub(rc, 2);
			if (model == null || !BchMapModel.isMapModel(model)) {
				continue;
			}
			for (BuildingHarvester.Comp c : BuildingHarvester.detect(model, coll)) {
				if (!c.terrain) {
					out.add(c);
				}
			}
		}
		System.out.println("  swept " + regions + " -> " + out.size() + " structures");
		return out;
	}

	/**
	 * What the cut asks to be called, worked out here from its raw material
	 * tally: the family owning the most triangles when it beats the biggest
	 * single material no family recognises, otherwise its size.
	 */
	static String expectedHint(BuildingHarvester.Comp c) {
		Map<String, Integer> perFamily = new HashMap<>();
		int biggestUnnamed = 0;
		for (Map.Entry<String, Integer> m : c.materialTriangles().entrySet()) {
			String f = familyHint(m.getKey());
			if (f == null) {
				biggestUnnamed = Math.max(biggestUnnamed, m.getValue());
			} else {
				perFamily.put(f, perFamily.containsKey(f) ? perFamily.get(f) + m.getValue() : m.getValue());
			}
		}
		String best = null;
		int bestTris = 0;
		for (String[] family : BuildingHarvester.HINT_FAMILIES) {   //ties keep the earlier family
			int tris = perFamily.containsKey(family[0]) ? perFamily.get(family[0]) : 0;
			if (tris > bestTris) {
				bestTris = tris;
				best = family[0];
			}
		}
		if (best != null && bestTris > biggestUnnamed) {
			return best;
		}
		return c.tilesW() <= 2 && c.tilesH() <= 2 ? "decor" : "structure";
	}

	/** This suite's own reading of the keyword table, checked by FAMILY_CASES. */
	static String familyHint(String material) {
		String ml = material.toLowerCase();
		for (String[] family : BuildingHarvester.HINT_FAMILIES) {
			for (int k = 1; k < family.length; k++) {
				if (ml.contains(family[k])) {
					return family[0];
				}
			}
		}
		return null;
	}

	static String describe(BuildingHarvester.Comp c) {
		return "the " + c.tilesW() + "x" + c.tilesH() + " cut at tile " + c.tx0 + "," + c.ty0
				+ " (" + c.materialTriangles() + ")";
	}

	static byte[] sub(byte[] c, int i) {
		if (c == null || c.length < 8) {
			return null;
		}
		int count = (c[2] & 0xFF) | ((c[3] & 0xFF) << 8);
		if (i >= count) {
			return null;
		}
		int o0 = le32(c, 4 + i * 4), o1 = le32(c, 4 + (i + 1) * 4);
		return Arrays.copyOfRange(c, o0, o1);
	}

	static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
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
