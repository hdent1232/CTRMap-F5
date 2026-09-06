package ctrmap.formats.recordschema;

import ctrmap.Workspace;
import ctrmap.formats.pokedata.ItemData;
import ctrmap.formats.pokedata.ItemEffectLabels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Every fixed-stride table this editor knows how to render, in one readable
 * place.
 *
 * <p>THE POINT OF A REGISTRY. Tomorrow's discovery - personal data, moves,
 * trainer classes, gift encounters - is a method here and a line in
 * {@link #all()}, not another dialog. What it is NOT is a place to hide
 * knowledge: a registry nobody can read is worse than a hand-built form,
 * because the form at least admits it is bespoke. So each entry is written out
 * field by field with the offsets visible, and the comment above it says where
 * the layout came from.
 *
 * <p>Nothing here is a guess. The item layout is pk3DS's {@code Item6.cs}
 * (GPLv3, in this tree), re-verified byte for byte against a retail dump by
 * {@code ItemDataTest}; the icon table's offset was measured in a decompressed
 * ORAS code.bin. Per-game locations come from the {@link ctrmap.gamedef}
 * profiles, never from here.
 */
public final class SchemaRegistry {

	private SchemaRegistry() {
	}

	private static final String G_TRADE = "Price and pockets";
	private static final String G_HELD = "Held item";
	private static final String G_USE = "Using the item";
	private static final String G_HEAL = "Healing and revival";
	private static final String G_BOOST = "In-battle stat stages";
	private static final String G_EV = "EV changes (signed - some items REMOVE EVs)";
	private static final String G_FRIEND = "Friendship";

	/**
	 * The 36-byte item record: what an item costs, what holding it does, what
	 * using it does.
	 *
	 * <p>Two bytes of the record deserve their warnings in the form itself
	 * rather than in a comment nobody reads. Byte 0x10 means two different
	 * things depending on whether the item is a Ball, so it is shown and not
	 * edited. And byte 0x03 is a general magnitude that healing items MIRROR
	 * into the typed heal field, so changing one without the other produces an
	 * item that heals two different amounts depending on which code path asks.
	 */
	public static RecordSchema items() {
		List<RecordField> f = new ArrayList<>();

		f.add(RecordField.number(G_TRADE, "Price / 10", 0x00, 16,
				"The mart charges ten times this and pays five times it, so selling is never half of buying."));
		f.add(RecordField.bits(G_TRADE, "Natural Gift type", 0x08, 0, 5,
				"Elemental type of Natural Gift when this berry is held."));
		f.add(RecordField.bits(G_TRADE, "Flag (packed bit 5)", 0x08, 5, 1,
				"One of two packed flags pk3DS leaves unnamed. Shown because an unnamed byte is a hole, not an absence."));
		f.add(RecordField.bits(G_TRADE, "Flag (packed bit 6)", 0x08, 6, 1,
				"The second unnamed packed flag."));
		f.add(RecordField.bits(G_TRADE, "Bag pocket", 0x08, 7, 4,
				"Which pocket of the bag the item files itself into."));
		f.add(RecordField.bits(G_TRADE, "Battle pocket", 0x09, 3, 5,
				"Which list the item appears in during a battle."));
		f.add(RecordField.number(G_TRADE, "Sort index", 0x0F, 8,
				"Position within the pocket. Duplicates are allowed; the game keeps ties in id order."));
		f.add(RecordField.number(G_TRADE, "Classification", 0x0D, 8,
				"0-3 battle item, 4 Ball, 5 mail. Decides what byte 0x10 below means."));
		f.add(RecordField.number(G_TRADE, "Consumed on use", 0x0E, 8,
				"Low nibble consumed, high nibble kept."));
		//Three bytes nobody has named. They are shown rather than left out: a
		//form that silently omits part of a record lets a user believe they are
		//looking at all of it. What retail puts in them was MEASURED across all
		//776 records - 0x0C is 0 or 1, 0x15 only ever holds a single set bit,
		//0x16 holds 0 and 12 and 28..31 - which is enough to show and not enough
		//to name, so they are not named.
		f.add(RecordField.number(G_TRADE, "Unnamed byte 0x0C", 0x0C, 8,
				"pk3DS never named this one. Retail holds only 0 or 1 in it."));
		f.add(RecordField.number(G_TRADE, "Unnamed byte 0x15", 0x15, 8,
				"Unnamed. Every retail value is a single set bit, so it looks like a flag field."));
		f.add(RecordField.number(G_TRADE, "Unnamed byte 0x16", 0x16, 8,
				"Unnamed. Retail holds 0, 12, or 28..31."));

		//The one field the whole feature turns on. Reassigning it hands one
		//existing behaviour to another item for the cost of a byte; there is no
		//spare id to hang a NEW behaviour on, which is why the editor says so
		//beside the control rather than leaving the user to find out.
		f.add(RecordField.effect(G_HELD, "Held effect", 0x02, 8, ItemEffectLabels.Kind.HELD_EFFECT,
				"One of the behaviours the engine already implements. Pick any and this item behaves that way."));
		f.add(RecordField.number(G_HELD, "Held effect magnitude", 0x03, 8,
				"The number the held effect uses - Life Orb is 30. Healing items MIRROR this into the heal field below."));
		f.add(RecordField.effect(G_HELD, "Fling effect", 0x05, 8, ItemEffectLabels.Kind.FLING_EFFECT,
				"What happens to the target when this item is flung."));
		f.add(RecordField.number(G_HELD, "Fling power", 0x06, 8, "Base power of Fling with this item held."));
		f.add(RecordField.effect(G_HELD, "Natural Gift effect", 0x04, 8,
				ItemEffectLabels.Kind.NATURAL_GIFT_EFFECT, "Natural Gift's effect with this berry held."));
		f.add(RecordField.number(G_HELD, "Natural Gift power", 0x07, 8, "Natural Gift's base power."));

		f.add(RecordField.effect(G_USE, "Field use routine", 0x0A, 8, ItemEffectLabels.Kind.FIELD_ROUTINE,
				"What using the item outside battle does. 0 means it cannot be used in the field."));
		f.add(RecordField.effect(G_USE, "Battle use routine", 0x0B, 8, ItemEffectLabels.Kind.BATTLE_ROUTINE,
				"What using the item in battle does. 0 means it cannot be used in battle."));
		f.add(RecordField.number(G_USE, "Cure mask / ball index (0x10)", 0x10, 8,
				"TWO different fields in one byte: the BALL INDEX when Classification is 4, a status-cure"
				+ " bitmask otherwise. Shown and not editable, because one spinner for both would turn a"
				+ " Great Ball into a sleep cure without saying so.").readOnly());

		f.add(RecordField.bits(G_HEAL, "Revive bit (CLEAR means it revives)", 0x11, 0, 1,
				"Inverted in the record: the items that revive are the ones with this bit at 0."));
		f.add(RecordField.bits(G_HEAL, "Revives the whole party", 0x11, 1, 1, "Max Revive-style."));
		f.add(RecordField.bits(G_HEAL, "Levels up", 0x11, 2, 1, "Rare Candy."));
		f.add(RecordField.bits(G_HEAL, "Evolution stone", 0x11, 3, 1, "Triggers a stone evolution."));
		f.add(RecordField.number(G_HEAL, "Heal amount", 0x1D, 8,
				"HP restored. For healing items this MIRRORS the held-effect magnitude above - change both or neither."));
		f.add(RecordField.number(G_HEAL, "PP restored", 0x1E, 8, "Ether/Elixir amount."));

		f.add(RecordField.bits(G_BOOST, "Attack", 0x11, 4, 4, "X Attack-style stage boost."));
		f.add(RecordField.bits(G_BOOST, "Defense", 0x12, 0, 4, ""));
		f.add(RecordField.bits(G_BOOST, "Sp. Attack", 0x12, 4, 4, ""));
		f.add(RecordField.bits(G_BOOST, "Sp. Defense", 0x13, 0, 4, ""));
		f.add(RecordField.bits(G_BOOST, "Speed", 0x13, 4, 4, ""));
		f.add(RecordField.bits(G_BOOST, "Accuracy", 0x14, 0, 4, ""));
		f.add(RecordField.bits(G_BOOST, "Critical rate", 0x14, 4, 4, ""));

		String[] evNames = {"HP", "Attack", "Defense", "Speed", "Sp. Attack", "Sp. Defense"};
		for (int i = 0; i < evNames.length; i++) {
			f.add(RecordField.signedNumber(G_EV, evNames[i], 0x17 + i, 8,
					i == 0 ? "Signed: negative values REMOVE EVs, which is why this is not a plain byte." : ""));
		}

		for (int i = 0; i < 3; i++) {
			f.add(RecordField.signedNumber(G_FRIEND, "Delta at " + new String[]{"low", "mid", "high"}[i]
					+ " friendship", 0x1F + i, 8, i == 0 ? "Signed - the bitter herbs subtract." : ""));
		}

		return RecordSchema.archive("Items",
				"The 36-byte item record. Editing it REASSIGNS behaviour that already exists in the"
				+ " game - it cannot author a new behaviour, because the held-effect ids are a full"
				+ " palette of 183 with no spare, and some behaviour (Exp. Share, Ability Capsule) is"
				+ " keyed on the item id in code and is not in this record at all.",
				Workspace.ArchiveType.ITEM_DATA, -1, ItemData.SIZE, f);
	}

	/**
	 * The item-to-icon table inside the executable: {@code u32[776]} at file
	 * offset 0x47C644 of a decompressed ORAS code.bin.
	 *
	 * <p>MEASURED, and the measurement is the reason this is a separate schema
	 * rather than another field of the item record: it is not in the archive at
	 * all, so changing it is a {@link RecordSchema.Tier#CODE_PATCH} - a
	 * different blast radius, a different deploy step, and a thing that can be
	 * refused against the wrong build. The table has ZERO slack: the next table
	 * begins at 0x47D264, which is exactly 776 * 4 bytes later, so the count is
	 * a hard bound rather than a convention.
	 */
	public static RecordSchema itemIcons() {
		return RecordSchema.code("Item icons",
				"Which icon each item draws. One 32-bit index per item in the executable, so it ships"
				+ " as a code.ips patch and not as archive data. Retail uses indices 0..629.",
				ctrmap.formats.codepatch.ItemIconTable.FILE_OFFSET,
				ctrmap.formats.codepatch.ItemIconTable.COUNT,
				4,
				Arrays.asList(RecordField.number("Icon", "Icon index", 0x00, 32,
						"Index into the item-icon texture archive. Reassigns an icon that already"
						+ " exists; it does not add one.")));
	}

	/** Everything the registry knows, for a UI that wants to list it. */
	public static List<RecordSchema> all() {
		return Arrays.asList(items(), itemIcons());
	}
}
