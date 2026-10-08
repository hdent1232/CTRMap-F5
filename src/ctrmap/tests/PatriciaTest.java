package ctrmap.tests;

import ctrmap.formats.h3d.Patricia;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The one GameFreak name-tree builder builds the trees the old ones did, and every name in them
 * is found (F066).
 *
 * <p>WHY THIS SUITE EXISTS. The tree builder existed three times - BchTexturePack,
 * BchModelAppender and WorldAnim - each claimed byte-exact against retail on its own. Before the
 * copies went they were measured against each other (20,000 random name sets, a quarter holding
 * names that differ only in the lowest bit: identical trees in all three). PINS records what the
 * BCH builder built for five fixed sets before it was replaced, so the one that replaced it must
 * build the same bytes; the lookup check holds every tree to the retail lookup's rule.
 *
 * <p>ORDER: needs no dump. Retail trees are checked by the dump suites that already compare a
 * rebuilt tree with the game's (WorldAnim's gold test, the BCH writers' round trips).
 *
 * Usage: java ctrmap.tests.PatriciaTest
 */
public class PatriciaTest {

	static int fails = 0;

	/**
	 * Measured 2026-10-07 from BchModelAppender.buildTree as it stood before F066 (the working tree
	 * over a93ef21), by a scratch harness calling it by reflection: each node as
	 * refBit(hex),left,right,name index (-1 for the root). The names are the subject.
	 */
	static final String[][] PINS = {
		{"a", "ffffffff,1,0,-1 6,0,1,0"},
		{"alpha beta gamma", "ffffffff,1,0,-1 26,2,3,0 1e,0,2,1 1a,1,3,2"},
		{"world03_02_02_chikei_ world03_02_02_umi_ world03_02_02_sora sea a",
			"ffffffff,1,0,-1 a6,2,1,0 8e,4,3,1 8d,2,3,2 16,5,4,3 6,0,5,4"},
		{"bX cX b c bXY", "ffffffff,5,0,-1 e,3,2,0 0,1,2,1 6,0,4,2 0,3,4,3 16,1,5,4"},
		{"tex_00 tex_01 tex_02 tex_03 tex_10 tex_11 mask_m mask_n",
			"ffffffff,7,0,-1 2d,0,3,0 28,5,6,1 29,2,4,2 28,3,4,3 20,1,5,4 20,2,6,5 2e,1,8,6 29,7,8,7"},
	};

	/** One section of the suite. */
	interface Section {

		void run() throws Exception;
	}

	/**
	 * Runs a section so that one that throws is a failure said in words and the rest still run: a
	 * suite that dies at its first exception reports nothing about the checks after it.
	 */
	static void section(String name, Section s) {
		try {
			s.run();
		} catch (Throwable t) {
			check(false, name + " threw instead of answering: " + t);
		}
	}

	public static void main(String[] args) throws Exception {
		section("the pinned trees", PatriciaTest::theTreesAreTheOldBuildersTrees);
		section("the lookup sweep", PatriciaTest::everyNameIsFound);
		section("the duplicate refusal", PatriciaTest::aNameGivenTwiceIsRefused);
		section("the area animation's tree", PatriciaTest::theAreaAnimationsTreeIsThisOne);
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	static String flat(List<Patricia.Node> nodes, List<String> names) {
		StringBuilder sb = new StringBuilder();
		for (Patricia.Node n : nodes) {
			sb.append(sb.length() == 0 ? "" : " ").append(Long.toHexString(n.refBit & 0xFFFFFFFFL)).append(',')
					.append(n.left).append(',').append(n.right).append(',').append(n.name == null ? -1 : names.indexOf(n.name));
		}
		return sb.toString();
	}

	static void theTreesAreTheOldBuildersTrees() {
		System.out.println("--- the one builder builds the trees the BCH builder built (F066)");
		for (String[] pin : PINS) {
			List<String> names = Arrays.asList(pin[0].split(" "));
			String got = flat(Patricia.build(names), names);
			check(got.equals(pin[1]), names + ": " + (got.equals(pin[1]) ? "as recorded" : got + " - recorded " + pin[1]));
		}
	}

	/** The retail lookup: from the root's left child, follow the tested bit until a link goes up. */
	static String lookup(List<Patricia.Node> nodes, String name) {
		int index = nodes.get(0).left;
		long prev = nodes.get(0).refBit;
		while (nodes.get(index).refBit < prev) {
			prev = nodes.get(index).refBit;
			index = Patricia.bit(name, prev) ? nodes.get(index).right : nodes.get(index).left;
		}
		return nodes.get(index).name;
	}

	static void everyNameIsFound() {
		System.out.println("--- every name in every tree is found by the retail lookup");
		Random rnd = new Random(66);
		int sets = 0, names = 0, lost = 0;
		String first = null;
		for (int t = 0; t < 5000; t++) {
			Set<String> set = new LinkedHashSet<>();
			String alpha = t % 2 == 0 ? "abcdefghijklmnopqrstuvwxyz_0123456789" : "ab";
			int maxLen = t % 3 == 0 ? 4 : 16;
			int n = 1 + rnd.nextInt(alpha.length() == 2 && maxLen == 4 ? 25 : 40);
			while (set.size() < n) {
				StringBuilder sb = new StringBuilder();
				int len = 1 + rnd.nextInt(maxLen);
				for (int i = 0; i < len; i++) {
					sb.append(alpha.charAt(rnd.nextInt(alpha.length())));
				}
				set.add(sb.toString());
			}
			if (t % 4 == 3) {
				set.add("b_" + t);
				set.add("c_" + t); //'b' and 'c' differ only in bit 0
			}
			List<String> list = new ArrayList<>(set);
			List<Patricia.Node> tree = Patricia.build(list);
			sets++;
			for (String s : list) {
				names++;
				if (!s.equals(lookup(tree, s))) {
					lost++;
					if (first == null) {
						first = s + " in " + list;
					}
				}
			}
		}
		check(lost == 0 && names > 50000, names + " names in " + sets + " trees, every one found"
				+ (first == null ? "" : " - not " + lost + ", first " + first));
		List<Patricia.Node> wrong = Patricia.build(Arrays.asList("alpha", "beta", "gamma"));
		wrong.get(1).left ^= 1;
		check(!"alpha".equals(lookup(wrong, "alpha")) || !"beta".equals(lookup(wrong, "beta"))
				|| !"gamma".equals(lookup(wrong, "gamma")), "control: a tree with one link moved loses a name");
	}

	static void aNameGivenTwiceIsRefused() {
		System.out.println("--- a name given twice is refused in words");
		String said;
		try {
			Patricia.build(Arrays.asList("sea", "sky", "sea"));
			said = "built";
		} catch (RuntimeException refused) {
			said = String.valueOf(refused); //a crash is not a refusal in words, and says so below
		}
		check(said.contains("\"sea\"") && said.contains("twice"), "it names the name: " + said);
	}

	static void theAreaAnimationsTreeIsThisOne() throws Exception {
		System.out.println("--- the area animation file's tree is this builder's, with its string offsets");
		java.lang.reflect.Method build = Class.forName("ctrmap.formats.area.WorldAnim")
				.getDeclaredMethod("buildTree", List.class, List.class);
		build.setAccessible(true);
		List<String> names = Arrays.asList("world03_02_02_chikei_", "world03_02_02_umi_", "sea");
		List<Integer> offs = Arrays.asList(0x40, 0x56, 0x69);
		List<?> nodes = (List<?>) build.invoke(null, names, offs);
		List<Patricia.Node> mine = Patricia.build(names);
		boolean same = nodes.size() == mine.size();
		for (int i = 0; same && i < nodes.size(); i++) {
			Object p = nodes.get(i);
			same = field(p, "refBit") == mine.get(i).refBit && field(p, "left") == mine.get(i).left
					&& field(p, "right") == mine.get(i).right
					&& field(p, "nameOff") == (i == 0 ? 0 : offs.get(i - 1));
		}
		check(same, "each node is this builder's, carrying its own name's offset");
	}

	static long field(Object o, String name) throws Exception {
		java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return ((Number) f.get(o)).longValue();
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
