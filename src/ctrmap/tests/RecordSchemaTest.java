package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.pokedata.ItemData;
import ctrmap.formats.recordschema.RecordField;
import ctrmap.formats.recordschema.RecordSchema;
import ctrmap.formats.recordschema.SchemaRegistry;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;

/**
 * The schema registry must describe the SAME bytes the hand-written record
 * class reads.
 *
 * <p>WHY THAT IS THE FIRST GUARD. The whole reason for a registry is that four
 * bespoke forms drift apart. A registry that has itself drifted from
 * {@link ItemData} is worse than the four forms, because it looks authoritative
 * while reading a field one bit to the left - and a bit-shifted nibble does not
 * throw, it just makes the wrong item cheap. So every field that has a named
 * accessor is read BOTH ways for all 776 retail records and the answers must
 * agree, every time. That single assertion catches a wrong offset, a wrong bit
 * position, a wrong width and a sign error, on real data.
 *
 * <p>It also checks the properties the generic editor depends on: no field
 * escapes the record, a set/get round trip is exact over the field's whole
 * range including the negative half of a signed one, and the bytes NO field
 * describes are the ones measured to be dead (0x22 and 0x23, zero in every
 * retail record). That last one is not tidiness - if a byte quietly stopped
 * being covered, the form would stop offering it and nothing else would notice.
 *
 * Usage: java ctrmap.tests.RecordSchemaTest &lt;romfs-root&gt;
 */
public class RecordSchemaTest {

	static int fails = 0;

	static void check(boolean cond, String msg) {
		if (cond) {
			System.out.println("  ok: " + msg);
		} else {
			System.out.println("  FAIL: " + msg);
			fails++;
		}
	}

	//Where the item table lives is a per-game fact, so it comes from the gamedef
	//seam and not from a literal in a suite.
	private static String itemArchive() {
		String p = ctrmap.gamedef.GameProfile.of(GameType.ORAS)
				.archivePath(ArchiveType.ITEM_DATA);
		return p == null ? "" : p;
	}

	public static void main(String[] args) throws Exception {
		shapeIsSane();
		bitsRoundTrip();
		unmappedIsWhatWasMeasured();
		iconSchemaMatchesTheTable();

		if (args.length > 0) {
			File garcFile = new File(args[0] + itemArchive());
			if (garcFile.isFile()) {
				agreesWithItemData(new GARC(garcFile, false));
				theGeneratedFormCarriesTheRecordFaithfully(new GARC(garcFile, false));
			} else {
				System.out.println("  skip: no item archive at " + garcFile
						+ " - the cross-check against real records did NOT run");
			}
		} else {
			System.out.println("  skip: no romfs root given"
					+ " - the cross-check against real records did NOT run");
		}

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	static void shapeIsSane() {
		System.out.println("--- every schema describes bytes that exist");
		for (RecordSchema s : SchemaRegistry.all()) {
			boolean fits = true;
			String worst = "";
			for (RecordField f : s.fields()) {
				if (f.endByte() > s.stride()) {
					fits = false;
					worst = f.toString();
				}
			}
			check(fits, s.name() + ": no field runs past the " + s.stride() + "-byte record"
					+ (fits ? "" : " (" + worst + ")"));
			check(!s.fields().isEmpty(), s.name() + ": has fields (" + s.fields().size() + ")");
			check(!s.about().isEmpty(), s.name() + ": says what it is");
			//the tier decides the blast radius and the UI must be able to read it
			check(s.tier() != null, s.name() + ": declares its tier (" + s.tier() + ")");
			if (s.tier() == RecordSchema.Tier.DATA) {
				check(s.archive() != null && s.codeFileOffset() < 0,
						s.name() + ": a data schema names an archive and no code offset");
			} else {
				check(s.archive() == null && s.codeFileOffset() >= 0,
						s.name() + ": a code schema names a file offset and no archive");
			}
		}
		//exactly two tiers exist, and the registry must not invent a third
		check(RecordSchema.Tier.values().length == 2,
				"there are exactly two tiers - pure data and the code patch - and nothing else");
	}

	static void bitsRoundTrip() {
		System.out.println("--- a field written into a record reads back, and touches nothing else");
		RecordSchema items = SchemaRegistry.items();
		Random rnd = new Random(20260906L);
		int checked = 0, bad = 0, spilled = 0;
		String firstBad = "";
		for (RecordField f : items.fields()) {
			for (int trial = 0; trial < 64; trial++) {
				byte[] rec = new byte[items.stride()];
				rnd.nextBytes(rec);
				byte[] before = rec.clone();
				int v = f.min() + (f.max() == f.min() ? 0
						: rnd.nextInt(f.max() - f.min() + 1));
				f.set(rec, v);
				if (f.get(rec) != v) {
					bad++;
					if (firstBad.isEmpty()) {
						firstBad = f + " wrote " + v + " and read " + f.get(rec);
					}
				}
				//every BIT the field does not claim must be untouched. Bits, not
				//bytes: four of these fields are nibbles that share a byte with
				//another field, and a write that clobbered its neighbour's half
				//would leave the byte comparison perfectly happy.
				spilled += bitsOutsideField(f, before, rec);
				checked++;
			}
		}
		check(bad == 0, checked + " set/get round trips over the full range of every field"
				+ (bad == 0 ? "" : " - " + bad + " wrong, first: " + firstBad));
		check(spilled == 0, "and none of them wrote outside its own bits"
				+ (spilled == 0 ? "" : " (" + spilled + " did)"));

		//the signed fields are the ones a careless reader gets wrong, so prove
		//the negative half exists rather than assuming it
		RecordField hpEv = null;
		for (RecordField f : items.fields()) {
			if (f.signed() && f.byteOffset() == 0x17) {
				hpEv = f;
			}
		}
		check(hpEv != null && hpEv.min() == -128 && hpEv.max() == 127,
				"the HP EV delta is a SIGNED byte, -128..127, because some items remove EVs");
		if (hpEv != null) {
			byte[] rec = new byte[items.stride()];
			hpEv.set(rec, -10);
			check(hpEv.get(rec) == -10 && (rec[0x17] & 0xFF) == 246,
					"writing -10 stores 0xF6 and reads back as -10, not 246");
		}

		//a value the field cannot hold must be refused, not truncated
		RecordField price = items.fields().get(0);
		boolean refused = false;
		try {
			price.set(new byte[items.stride()], 0x10000);
		} catch (IllegalArgumentException ex) {
			refused = true;
		}
		check(refused, "a value too wide for its field is refused rather than wrapped");
	}

	static void unmappedIsWhatWasMeasured() {
		System.out.println("--- the bytes no field describes are the ones known to be dead");
		List<Integer> unmapped = SchemaRegistry.items().unmappedBytes();
		System.out.println("      unmapped: " + unmapped);
		check(unmapped.size() == 2 && unmapped.contains(0x22) && unmapped.contains(0x23),
				"exactly 0x22 and 0x23 are undescribed - the two bytes measured as zero in all"
				+ " 776 retail records (got " + unmapped + ")");
	}

	static void iconSchemaMatchesTheTable() {
		System.out.println("--- the icon schema and the icon patcher agree on the same table");
		RecordSchema icons = SchemaRegistry.itemIcons();
		check(icons.codeFileOffset() == ctrmap.formats.codepatch.ItemIconTable.FILE_OFFSET,
				"same file offset (0x" + Integer.toHexString(icons.codeFileOffset()) + ")");
		check(icons.recordCount() == ctrmap.formats.codepatch.ItemIconTable.COUNT,
				"same entry count (" + icons.recordCount() + ")");
		check(icons.codeFileOffset() + icons.recordCount() * icons.stride()
				== ctrmap.formats.codepatch.ItemIconTable.NEXT_TABLE,
				"and the table ends exactly where the next one begins - zero slack, so the count"
				+ " is a hard bound and not a convention");
	}

	static void agreesWithItemData(GARC g) {
		System.out.println("--- the schema reads every retail record the same way ItemData does");
		RecordSchema s = SchemaRegistry.items();
		int n = g.getEntryCount();
		int compared = 0, disagreed = 0;
		String first = "";
		for (int id = 0; id < n; id++) {
			byte[] raw = g.getDecompressedEntry(id);
			if (raw == null || raw.length != ItemData.SIZE) {
				continue;
			}
			ItemData d = new ItemData(raw);
			for (String[] pair : new String[][]{
				{"Price / 10", String.valueOf(d.priceRaw())},
				{"Held effect", String.valueOf(d.heldEffect())},
				{"Held effect magnitude", String.valueOf(d.heldArgument())},
				{"Natural Gift effect", String.valueOf(d.naturalGiftEffect())},
				{"Fling effect", String.valueOf(d.flingEffect())},
				{"Fling power", String.valueOf(d.flingPower())},
				{"Natural Gift power", String.valueOf(d.naturalGiftPower())},
				{"Natural Gift type", String.valueOf(d.naturalGiftType())},
				{"Bag pocket", String.valueOf(d.fieldPocket())},
				{"Battle pocket", String.valueOf(d.battlePocket())},
				{"Field use routine", String.valueOf(d.fieldRoutine())},
				{"Battle use routine", String.valueOf(d.battleRoutine())},
				{"Classification", String.valueOf(d.classification())},
				{"Consumed on use", String.valueOf(d.consumable())},
				{"Sort index", String.valueOf(d.sortIndex())},
				{"Cure mask / ball index (0x10)", String.valueOf(d.cureMaskOrBallIndex())},
				{"Revives the whole party", d.revivesAll() ? "1" : "0"},
				{"Levels up", d.levelsUp() ? "1" : "0"},
				{"Evolution stone", d.isEvoStone() ? "1" : "0"},
				{"Attack", String.valueOf(d.boostAtk())},
				{"Defense", String.valueOf(d.boostDef())},
				{"Sp. Attack", String.valueOf(d.boostSpa())},
				{"Sp. Defense", String.valueOf(d.boostSpd())},
				{"Speed", String.valueOf(d.boostSpe())},
				{"Accuracy", String.valueOf(d.boostAcc())},
				{"Critical rate", String.valueOf(d.boostCrit())},
				{"Heal amount", String.valueOf(d.healAmount())},
				{"PP restored", String.valueOf(d.ppGain())}}) {
				RecordField f = field(s, pair[0]);
				if (f == null) {
					if (first.isEmpty()) {
						first = "no field named \"" + pair[0] + "\"";
					}
					disagreed++;
					continue;
				}
				//"Speed" is both a stat boost and an EV; disambiguate by group
				int got = f.get(raw);
				if (got != Integer.parseInt(pair[1])) {
					disagreed++;
					if (first.isEmpty()) {
						first = "item " + id + " " + pair[0] + ": schema " + got
								+ " vs ItemData " + pair[1];
					}
				}
				compared++;
			}
			//the six EV deltas, by position, signed
			String[] evNames = {"HP", "Attack", "Defense", "Speed", "Sp. Attack", "Sp. Defense"};
			for (int i = 0; i < 6; i++) {
				RecordField f = fieldInGroup(s, evNames[i], "EV");
				if (f == null || f.get(raw) != d.ev(i)) {
					disagreed++;
					if (first.isEmpty()) {
						first = "item " + id + " EV " + evNames[i] + ": schema "
								+ (f == null ? "missing" : String.valueOf(f.get(raw)))
								+ " vs ItemData " + d.ev(i);
					}
				}
				compared++;
			}
			for (int i = 0; i < 3; i++) {
				RecordField f = fieldInGroup(s, new String[]{"low", "mid", "high"}[i], "Friendship");
				if (f == null || f.get(raw) != d.friendship(i)) {
					disagreed++;
					if (first.isEmpty()) {
						first = "item " + id + " friendship " + i;
					}
				}
				compared++;
			}
			//the inverted revive bit: ItemData says an item revives when bit 0 is CLEAR
			RecordField rev = field(s, "Revive bit (CLEAR means it revives)");
			if (rev == null || (rev.get(raw) == 0) != d.revives()) {
				disagreed++;
				if (first.isEmpty()) {
					first = "item " + id + " revive bit";
				}
			}
			compared++;
		}
		check(compared > 0, compared + " field readings compared across " + n + " retail records");
		check(disagreed == 0, "the schema and ItemData agree on every one"
				+ (disagreed == 0 ? "" : " - " + disagreed + " disagreed, first: " + first));
	}

	/**
	 * The GENERATED form must carry a record without changing it, and each
	 * control must write its own field and nobody else's.
	 *
	 * <p>This is the assertion a generic editor most needs and least often
	 * gets. A hand-built form is wrong visibly - the wrong label, the wrong box.
	 * A generated one is wrong invisibly: a control that writes the neighbouring
	 * nibble looks entirely correct on screen, and the damage appears later, in
	 * a record the user never opened. So every retail record is loaded into the
	 * real panel and read back, and then every editable control is DRIVEN the
	 * way a person drives it - a spinner set, a box clicked, a dropdown picked -
	 * and the bytes outside that field must not move.
	 *
	 * <p>Runs headless; the panel is built and driven but never shown.
	 */
	static void theGeneratedFormCarriesTheRecordFaithfully(GARC g) {
		System.out.println("--- the generated form carries a record without changing it");
		final RecordSchema s = SchemaRegistry.items();
		final List<ItemData> recs = new ArrayList<>();
		for (int i = 0; i < g.getEntryCount(); i++) {
			byte[] b = g.getDecompressedEntry(i);
			recs.add(b != null && b.length == ItemData.SIZE ? new ItemData(b) : null);
		}
		final Map<ctrmap.formats.pokedata.ItemEffectLabels.Kind,
				ctrmap.formats.pokedata.ItemEffectLabels> byKind
				= new java.util.EnumMap<>(ctrmap.formats.pokedata.ItemEffectLabels.Kind.class);
		for (ctrmap.formats.pokedata.ItemEffectLabels.Kind k
				: ctrmap.formats.pokedata.ItemEffectLabels.Kind.values()) {
			byKind.put(k, ctrmap.formats.pokedata.ItemEffectLabels.build(recs, null, k));
		}
		ctrmap.humaninterface.RecordEditPanel panel
				= new ctrmap.humaninterface.RecordEditPanel(s,
						new ctrmap.humaninterface.RecordEditPanel.Labels() {
					@Override
					public String label(RecordField f, int value) {
						ctrmap.formats.pokedata.ItemEffectLabels l = byKind.get(f.effectKind());
						return l == null ? String.valueOf(value) : l.label(value);
					}

					@Override
					public List<Integer> choices(RecordField f) {
						List<Integer> out = new ArrayList<>();
						for (int i = Math.max(0, f.min()); i <= Math.min(f.max(), 255); i++) {
							out.add(i);
						}
						return out;
					}
				});

		int loaded = 0, changed = 0;
		String firstChanged = "";
		for (int i = 0; i < g.getEntryCount(); i++) {
			byte[] b = g.getDecompressedEntry(i);
			if (b == null || b.length != ItemData.SIZE) {
				continue;
			}
			panel.setRecord(b);
			byte[] back = panel.record();
			loaded++;
			if (!java.util.Arrays.equals(b, back)) {
				changed++;
				if (firstChanged.isEmpty()) {
					firstChanged = " (item " + i + ")";
				}
			}
		}
		check(loaded > 700, loaded + " retail records loaded into the real form");
		check(changed == 0, "and every one reads back byte for byte" + firstChanged);

		//now drive each control the way a person would
		java.util.Map<String, java.awt.Component> byName = new java.util.HashMap<>();
		collect(panel, byName);
		int driven = 0, spilled = 0, notFound = 0, noEffect = 0;
		String firstSpill = "";
		for (RecordField f : s.fields()) {
			if (!f.editable()) {
				continue;
			}
			java.awt.Component c = byName.get(f.key());
			if (c == null) {
				notFound++;
				continue;
			}
			byte[] start = g.getDecompressedEntry(2);
			panel.setRecord(start);
			int was = f.get(start);
			int want = was == f.max() ? f.min() : was + 1;
			if (c instanceof javax.swing.JCheckBox) {
				((javax.swing.JCheckBox) c).doClick();
				want = was == 0 ? 1 : 0;
			} else if (c instanceof javax.swing.JComboBox) {
				((javax.swing.JComboBox<?>) c).setSelectedIndex(want);
			} else if (c instanceof javax.swing.JSpinner) {
				((javax.swing.JSpinner) c).setValue(want);
			} else {
				notFound++;
				continue;
			}
			byte[] after = panel.record();
			driven++;
			if (f.get(after) != want) {
				noEffect++;
				if (firstSpill.isEmpty()) {
					firstSpill = f.name() + " did not take the value " + want;
				}
			}
			int bled = bitsOutsideField(f, start, after);
			if (bled > 0) {
				spilled += bled;
				if (firstSpill.isEmpty()) {
					firstSpill = f.key() + " also changed " + bled + " bit(s) it does not own";
				}
			}
		}
		check(notFound == 0, "every editable field has a control (" + notFound + " missing)");
		check(driven > 30, driven + " controls driven as a person would drive them");
		check(noEffect == 0, "each one changed its own field"
				+ (noEffect == 0 ? "" : " - " + noEffect + " did not: " + firstSpill));
		check(spilled == 0, "and none of them touched a bit belonging to another field"
				+ (spilled == 0 ? "" : " - " + firstSpill));

		//and undo puts the record back exactly
		byte[] start = g.getDecompressedEntry(2);
		panel.setRecord(start);
		java.awt.Component c = byName.get(fieldNamedIn(s, "Sort index").key());
		((javax.swing.JSpinner) c).setValue(99);
		check(!java.util.Arrays.equals(panel.record(), start), "an edit through a control sticks");
		panel.undo();
		check(java.util.Arrays.equals(panel.record(), start),
				"and Undo puts every byte of the record back");
		panel.redo();
		check(SchemaRegistry.items().fields().get(0) != null
				&& fieldNamedIn(s, "Sort index").get(panel.record()) == 99,
				"and Redo puts the edit back");
	}

	/**
	 * How many bits changed that the field does not own.
	 *
	 * <p>Counted in BITS because half of these fields are nibbles sharing a
	 * byte with another field: a write that took the whole byte would clobber
	 * its neighbour, and a byte-level comparison would call that untouched.
	 */
	static int bitsOutsideField(RecordField f, byte[] before, byte[] after) {
		int bled = 0;
		for (int i = 0; i < before.length; i++) {
			int diff = (before[i] ^ after[i]) & 0xFF;
			for (int b = 0; b < 8; b++) {
				if ((diff & (1 << b)) == 0) {
					continue;
				}
				int rel = (i - f.byteOffset()) * 8 + b - f.bitOffset();
				if (rel < 0 || rel >= f.bitCount()) {
					bled++;
				}
			}
		}
		return bled;
	}

	static RecordField fieldNamedIn(RecordSchema s, String name) {
		RecordField f = field(s, name);
		if (f == null) {
			throw new IllegalStateException("no field " + name);
		}
		return f;
	}

	static void collect(java.awt.Container c, java.util.Map<String, java.awt.Component> out) {
		for (java.awt.Component k : c.getComponents()) {
			if (k.getName() != null && !out.containsKey(k.getName())) {
				out.put(k.getName(), k);
			}
			if (k instanceof java.awt.Container) {
				collect((java.awt.Container) k, out);
			}
		}
	}

	static RecordField field(RecordSchema s, String name) {
		for (RecordField f : s.fields()) {
			if (f.name().equals(name)) {
				return f;
			}
		}
		return null;
	}

	/** By name AND group, for names that appear in two groups (Speed is a boost and an EV). */
	static RecordField fieldInGroup(RecordSchema s, String namePart, String groupPart) {
		for (RecordField f : s.fields()) {
			if (f.name().contains(namePart) && f.group().startsWith(groupPart)) {
				return f;
			}
		}
		return null;
	}
}
