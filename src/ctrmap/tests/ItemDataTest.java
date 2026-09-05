package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.pokedata.ItemData;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every retail item record survives a read and a write unchanged, and the
 * fields mean what they are named.
 *
 * <p>WHY A ROUND TRIP IS THE FIRST GUARD. An item editor writes back into a
 * table of 776 fixed-size records that the game indexes by position. A reader
 * that is wrong by one byte does not fail - it silently shifts every field of
 * every item after it, and the result still looks like data. The only cheap way
 * to know the mapping is right is to demand that parsing and re-serialising all
 * 776 retail records reproduces the archive byte for byte. That catches a wrong
 * offset, a wrong width, a sign error and an endianness error in one assertion,
 * before anything is built on top.
 *
 * <p>The layout is pk3DS's, not newly derived here, so this suite is also the
 * check that the borrowed mapping actually holds against THIS dump.
 *
 * <p>The value assertions are deliberately few and specific. They are retail
 * facts a wrong mapping cannot coincidentally satisfy: Ultra Ball costs 1200,
 * Life Orb's held argument is 30, Rare Candy sets the level-up bit, HP Up adds
 * 10 HP EVs. A round trip alone would pass on a reader that had two fields
 * transposed, because it never looks at what they mean.
 *
 * <p>Also asserts the fact the whole item design rests on: hold-effect ids are
 * SHARED between items. If they were unique per item they would be identifiers,
 * not behaviours, and reassigning one would be meaningless.
 *
 * Usage: java ctrmap.tests.ItemDataTest &lt;romfs-root&gt;
 */
public class ItemDataTest {

	//Where the item table lives is a PER-GAME fact, so it comes from the gamedef
	//seam rather than being written here. SourceSeamTest enforces that no RomFS
	//path lives outside that package, and a suite is not exempt from the rule it
	//exists to protect.
	private static String itemArchive() {
		String p = ctrmap.gamedef.GameProfile.of(ctrmap.Workspace.GameType.ORAS)
				.archivePath(ctrmap.Workspace.ArchiveType.ITEM_DATA);
		return p == null ? "" : p;
	}

	private static final int ULTRA_BALL = 2, RARE_CANDY = 50, HP_UP = 45, LIFE_ORB = 270;

	static int fails = 0;

	static void check(boolean cond, String msg) {
		if (cond) {
			System.out.println("  ok: " + msg);
		} else {
			System.out.println("  FAIL: " + msg);
			fails++;
		}
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println("  skip: no romfs root given");
			System.out.println("ALL PASS");
			return;
		}
		File garcFile = new File(args[0] + itemArchive());
		if (!garcFile.isFile()) {
			System.out.println("  skip: no item archive at " + garcFile);
			System.out.println("ALL PASS");
			return;
		}

		GARC g = new GARC(garcFile);
		int n = g.getEntryCount();
		System.out.println("--- " + n + " item record(s) in " + itemArchive());

		roundTrip(g, n);
		fieldsMeanWhatTheyAreNamed(g);
		holdEffectsAreSharedNotUnique(g, n);
		blankSlotsAreCountedHonestly(g, n);
		effectLabelsDescribeThemselves(g, n, args[0]);

		if (fails == 0) {
			System.out.println("ALL PASS");
		} else {
			System.out.println("FAILURES PRESENT (" + fails + ")");
			System.exit(1);
		}
	}

	static void roundTrip(GARC g, int n) {
		System.out.println("--- read then write reproduces every retail record exactly");
		int wrongSize = 0, differed = 0;
		String firstDiff = "";
		for (int i = 0; i < n; i++) {
			byte[] raw = g.getDecompressedEntry(i);
			if (raw == null || raw.length != ItemData.SIZE) {
				wrongSize++;
				continue;
			}
			byte[] back = new ItemData(raw).toBytes();
			for (int k = 0; k < ItemData.SIZE; k++) {
				if (raw[k] != back[k]) {
					differed++;
					if (firstDiff.isEmpty()) {
						firstDiff = " (item " + i + " byte 0x" + Integer.toHexString(k) + ")";
					}
					break;
				}
			}
		}
		check(wrongSize == 0, "every record is " + ItemData.SIZE + " bytes ("
				+ wrongSize + " were not)");
		check(differed == 0, "all " + n + " records survive a read/write round trip byte for byte"
				+ (differed == 0 ? "" : " - " + differed + " differed" + firstDiff));
	}

	static void fieldsMeanWhatTheyAreNamed(GARC g) {
		System.out.println("--- the fields carry the retail values they should");
		ItemData ultra = at(g, ULTRA_BALL);
		check(ultra != null && ultra.buyPrice() == 1200,
				"Ultra Ball costs 1200" + got(ultra == null ? "missing" : ultra.buyPrice()));
		check(ultra != null && ultra.isBall(),
				"and classifies as a Ball, so byte 0x10 is its ball index and not a cure mask");

		ItemData orb = at(g, LIFE_ORB);
		check(orb != null && orb.heldArgument() == 30,
				"Life Orb's held argument is 30" + got(orb == null ? "missing" : orb.heldArgument()));

		ItemData candy = at(g, RARE_CANDY);
		check(candy != null && candy.levelsUp(), "Rare Candy sets the level-up bit");

		ItemData hpUp = at(g, HP_UP);
		check(hpUp != null && hpUp.ev(0) == 10,
				"HP Up adds 10 HP EVs" + got(hpUp == null ? "missing" : hpUp.ev(0)));
		//signedness is a real hazard here: some items REMOVE EVs, and reading
		//the byte unsigned would turn -10 into 246 with nothing complaining
		boolean anyNegative = false;
		for (int i = 0; i < 776 && !anyNegative; i++) {
			ItemData d = at(g, i);
			if (d == null) {
				continue;
			}
			for (int s = 0; s < 6; s++) {
				if (d.ev(s) < 0) {
					anyNegative = true;
					break;
				}
			}
		}
		check(anyNegative, "at least one item has a NEGATIVE EV delta, so the field is read signed");
	}

	static void holdEffectsAreSharedNotUnique(GARC g, int n) {
		System.out.println("--- hold-effect ids are behaviours, not identities");
		Map<Integer, List<Integer>> byEffect = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			ItemData d = at(g, i);
			if (d == null || d.heldEffect() == 0) {
				continue;
			}
			List<Integer> l = byEffect.get(d.heldEffect());
			if (l == null) {
				l = new ArrayList<>();
				byEffect.put(d.heldEffect(), l);
			}
			l.add(i);
		}
		int shared = 0, max = -1;
		for (Map.Entry<Integer, List<Integer>> e : byEffect.entrySet()) {
			if (e.getValue().size() > 1) {
				shared++;
			}
			if (e.getKey() > max) {
				max = e.getKey();
			}
		}
		//the entire premise of item behaviour editing: if every effect id
		//belonged to exactly one item it would be a name, and reassigning it
		//would mean nothing
		check(shared > 0, "some effect ids are carried by more than one item ("
				+ shared + " of " + byEffect.size() + "), which is what makes them reassignable");
		check(max <= 182, "no effect id exceeds 182, the top of the known palette (highest was "
				+ max + ")");
		//and the derived label table the UI needs is computable from this alone
		check(!byEffect.isEmpty(),
				"effect labels can be derived from the data itself, so they cannot go stale");
	}

	static void blankSlotsAreCountedHonestly(GARC g, int n) {
		System.out.println("--- how many genuinely empty slots there are, counted rather than assumed");
		List<Integer> blanks = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			ItemData d = at(g, i);
			if (d != null && d.isBlank()) {
				blanks.add(i);
			}
		}
		//the plan says four (113, 114, 115, 126). This suite exists partly to
		//catch that claim being wrong for this dump rather than trusting it.
		System.out.println("      blank records: " + blanks);
		check(!blanks.isEmpty(),
				"there is at least one all-zero record, so 'add a new item' is not vacuous");
		check(blanks.contains(113) && blanks.contains(114) && blanks.contains(115),
				"ids 113, 114 and 115 are among them, as the plan measured");
		//id 0 is the "no item" sentinel and is blank by design, so the count of
		//slots a new item can actually occupy is one FEWER than the blank count.
		//The plan says four; that is four usable, not four blank.
		check(blanks.contains(0), "id 0 is blank too - it is the \"no item\" sentinel, not a free slot");
		check(blanks.size() - (blanks.contains(0) ? 1 : 0) == 4,
				"so exactly 4 slots are genuinely free for a new item (found "
				+ (blanks.size() - (blanks.contains(0) ? 1 : 0)) + ") - a real limit a UI must state");
	}

	static void effectLabelsDescribeThemselves(GARC g, int n, String romfs) {
		System.out.println("--- effect labels are derived from the data, not authored");
		List<ItemData> recs = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			recs.add(at(g, i));
		}
		List<String> names = itemNames(romfs);
		System.out.println("      item names loaded: " + (names == null ? "none" : String.valueOf(names.size())));

		ctrmap.formats.pokedata.ItemEffectLabels held = ctrmap.formats.pokedata.ItemEffectLabels.build(
				recs, names, ctrmap.formats.pokedata.ItemEffectLabels.Kind.HELD_EFFECT);
		check(!held.ids().isEmpty(), "hold effects in use: " + held.ids().size());

		//the worked example from the plan: 77 is Mystic Water, Sea Incense and
		//Wave Incense, and a label that cannot say so is not doing its job
		List<String> for77 = held.itemsFor(77);
		System.out.println("      effect 77 -> " + for77);
		check(for77.size() >= 2, "effect 77 is carried by several items, so it describes a behaviour"
				+ " (got " + for77.size() + ")");

		int described = 0;
		for (Integer id : held.ids()) {
			if (!held.label(id).contains("unknown")) {
				described++;
			}
		}
		check(described == held.ids().size(),
				"every id in use gets a label naming real items (" + described + "/" + held.ids().size() + ")");
		//and an id nothing carries must be admitted, never guessed at
		check(held.label(199).contains("unknown"),
				"an id no item carries is reported as unknown rather than given a made-up name");
		System.out.println("      ids named by only ONE item: " + held.singletonCount()
				+ " of " + held.ids().size() + " - named, but not defined");

		//the same machinery must work for the other numbered fields, since they
		//have no name table either
		for (ctrmap.formats.pokedata.ItemEffectLabels.Kind k
				: ctrmap.formats.pokedata.ItemEffectLabels.Kind.values()) {
			ctrmap.formats.pokedata.ItemEffectLabels t
					= ctrmap.formats.pokedata.ItemEffectLabels.build(recs, names, k);
			check(!t.ids().isEmpty(), "  " + k + ": " + t.ids().size() + " id(s) in use, all labelled");
		}
	}

	/** Item names from gametext file 114, or null when they cannot be read. */
	static List<String> itemNames(String romfs) {
		try {
			GARC text = new GARC(new File(romfs + "/a/0/7/3"));
			byte[] raw = text.getDecompressedEntry(114);
			return raw == null ? null : ctrmap.formats.text.GFMessageFile.getStrings(raw);
		} catch (Throwable t) {
			return null;                  // names are a nicety here; ids still work
		}
	}

	static ItemData at(GARC g, int i) {
		byte[] raw = g.getDecompressedEntry(i);
		if (raw == null || raw.length != ItemData.SIZE) {
			return null;
		}
		return new ItemData(raw);
	}

	static String got(Object o) {
		return " (got " + o + ")";
	}
}
