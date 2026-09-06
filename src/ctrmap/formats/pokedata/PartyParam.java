package ctrmap.formats.pokedata;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the integers passed to the ORAS field-script natives
 * {@code PokePartyGetParam} and {@code PokePartySetParam} select.
 *
 * <p>Both natives take three arguments. Pawn pushes them in reverse, so the
 * PUSH nearest the {@code SYSREQ_N} is argument 1:
 * <pre>
 *   PokePartyGetParam(partySlot, selector, index)   -&gt; value
 *   PokePartySetParam(partySlot, selector, value)   -&gt; 0
 * </pre>
 * The <b>selector</b> is the middle argument. Get selectors are a dense
 * 0..53 switch; Set selectors are a separate dense 1000..1019 switch. The
 * third argument is a sub-index for the selectors that need one (move slot,
 * ribbon id) and the value to write for the setter.
 *
 * <h2>Where these names come from</h2>
 * Read out of the retail ARM code, not inferred from script usage. The field
 * natives are registered BY NAME in {@code DllField.cro} (a run of
 * {@code {const char *name; int (*fn)(...)}} pairs in the module's read-only
 * segment, wired up by the CRO relocation table), so each native's C
 * implementation is directly addressable. {@code PokePartyGetParam} is a
 * 54-entry ARM jump table and {@code PokePartySetParam} a 20-entry one; every
 * case tail-calls a {@code pml::pokepara} accessor in the main executable, and
 * those accessors bottom out in a load or store at a fixed offset of the
 * decrypted 232-byte Pokemon record. Those offsets are the evidence: the
 * record is PKHeX's documented Gen 6 PK6 layout - four shuffled 56-byte blocks
 * at 0x08/0x40/0x78/0xB0, block order {@code (PID &gt;&gt; 13) &amp; 0x1F}
 * mod 24 - and the accessor offsets land exactly on its documented fields. The
 * handlers that read a party member's cached level and battle stats reach them
 * through a second pointer, and that structure's fields line up the same way
 * against PK6's party block at 0xE8 (level at 0xEC, current and maximum HP at
 * 0xF0 and 0xF2, then the five other stats).
 *
 * <p>{@link Certainty#CONFIRMED} means the handler was followed to a load or
 * store at a named PK6 offset (or to an arithmetic identity as unambiguous as
 * the shiny XOR). {@link Certainty#PROBABLE} means the handler was followed and
 * its behaviour understood, but the name rests on an inference the offset alone
 * does not force. Selectors that exist in the switch but were not identified
 * are deliberately ABSENT from this table: {@link #get(int)} and
 * {@link #set(int)} return null for them, and null means "not established",
 * never a guess.
 *
 * <h2>Nature and ability cannot be written</h2>
 * There is no Set selector for nature and none for ability. The nature
 * accessors (get and set) and the ability setter exist in the executable, but
 * no case of either native's switch reaches them, and no import veneer in
 * {@code DllField.cro} resolves to the nature setter or the ability setter at
 * all - so no field-script native can write either. Ability can be READ
 * (selector 22); nature cannot even be read through these two natives.
 * {@link #isSafeToWrite(int)} is the gate: a UI must not offer "change nature"
 * on the strength of a selector that is not in this table, because that would
 * put an unknown byte into a player's save.
 */
public final class PartyParam {

	private PartyParam() {
	}

	/** Lowest and highest case of the PokePartyGetParam switch. */
	public static final int GET_MIN = 0;
	public static final int GET_MAX = 53;
	/** Lowest and highest case of the PokePartySetParam switch. */
	public static final int SET_MIN = 1000;
	public static final int SET_MAX = 1019;

	/** How firmly a name is attached to a selector. */
	public enum Certainty {
		/** Handler traced to a load/store at a named PK6 offset. */
		CONFIRMED,
		/** Handler traced and understood; the name is the best reading of it. */
		PROBABLE
	}

	/** One selector of one of the two switches. */
	public static final class Param {

		/** The integer the script passes as argument 2. */
		public final int selector;
		/** A stable machine name, e.g. {@code IV_SPEED}. Never null. */
		public final String name;
		public final Certainty certainty;
		/**
		 * Byte offset of the field in the decrypted PK6 record - 0x00..0xE7 for
		 * the stored 232-byte core, 0xE8..0x103 for the party block that only a
		 * party member carries - or -1 when the value is not one stored field
		 * (a sum, a comparison, a value the handler computes).
		 */
		public final int pk6Offset;
		/** Where in the retail code this was read, and what it does there. */
		public final String evidence;
		/** True when argument 3 selects which one (move slot, ribbon id). */
		public final boolean takesIndex;

		Param(int selector, String name, Certainty certainty, int pk6Offset, boolean takesIndex, String evidence) {
			this.selector = selector;
			this.name = name;
			this.certainty = certainty;
			this.pk6Offset = pk6Offset;
			this.evidence = evidence;
			this.takesIndex = takesIndex;
		}

		@Override
		public String toString() {
			return selector + " " + name + " (" + certainty + ")";
		}
	}

	private static final Map<Integer, Param> GET = new LinkedHashMap<>();
	private static final Map<Integer, Param> SET = new LinkedHashMap<>();

	private static void g(int sel, String name, Certainty c, int off, boolean idx, String ev) {
		GET.put(sel, new Param(sel, name, c, off, idx, ev));
	}

	private static void s(int sel, String name, Certainty c, int off, boolean idx, String ev) {
		SET.put(sel, new Param(sel, name, c, off, idx, ev));
	}

	static {
		// --- PokePartyGetParam, cases 0..53 --------------------------------
		g(0, "SPECIES", Certainty.CONFIRMED, 0x08, false,
				"case 0 -> ldrh [blockA+0] = PK6 0x08; guarded by the egg test, returns a constant for an egg");
		g(1, "FORM", Certainty.CONFIRMED, 0x1D, false,
				"case 1 -> ldrb [blockA+0x15] >> 3 = PK6 0x1D bits 3-7 (AltForm)");
		g(2, "HP_CURRENT", Certainty.CONFIRMED, 0xF0, false,
				"case 2 -> ldrh [partyExt+8] = PK6 0xF0, else computed when there is no party block");
		g(3, "HP_MAX", Certainty.CONFIRMED, 0xF2, false,
				"case 3 -> the same handler as selector 30, GetPower(HP): ldrh [partyExt+0xA] = PK6 0xF2, else computed");
		g(4, "MOVE_PP", Certainty.CONFIRMED, 0x62, true,
				"case 4 -> ldrb [blockB+0x22+arg3] = PK6 0x62+arg3, arg3 clamped to < 4");
		g(5, "MOVE_PP_MAX", Certainty.PROBABLE, -1, true,
				"case 5 -> GetWaza(arg3) and GetPPUp(arg3) combined; the maximum PP of that move slot");
		g(6, "TYPE1", Certainty.PROBABLE, -1, false,
				"case 6 -> species+form lookup with an explicit species 493 / ability 121 (Arceus, Multitype) special case");
		g(7, "TYPE2", Certainty.PROBABLE, -1, false,
				"case 7 -> the same shape as selector 6 against the second type field");
		g(8, "IS_EGG", Certainty.CONFIRMED, 0x74, false,
				"case 8 -> IsEgg(2): (IV32 bit 30) OR the runtime egg flag. PK6 0x74 bit 30 is PKHeX's IsEgg");
		g(9, "IS_EGG_STRICT", Certainty.PROBABLE, 0x74, false,
				"case 9 -> IsEgg(0): (IV32 bit 30) AND NOT the runtime egg flag");
		g(10, "FRIENDSHIP", Certainty.CONFIRMED, 0x92, false,
				"case 10 -> ldrb [blockC+0x1A] = PK6 0x92 (OT friendship), or the handler's byte when CurrentHandler != 0");
		g(11, "EV_TOTAL", Certainty.CONFIRMED, -1, false,
				"case 11 -> the six EV getters called with 0..5 and summed");
		g(12, "HELD_ITEM", Certainty.CONFIRMED, 0x0A, false,
				"case 12 -> ldrh [blockA+2] = PK6 0x0A");
		g(13, "RIBBON", Certainty.CONFIRMED, 0x30, true,
				"case 13 -> tests bit arg3 of the u32 at [blockA+0x28] = PK6 0x30 (with 0x34 for bits 32..63)");
		g(14, "LEVEL", Certainty.CONFIRMED, 0xEC, false,
				"case 14 -> ldrb [partyExt+4] = PK6 0xEC; falls back to species+form+EXP when there is no party block."
				+ " Also the function the computed-stat handlers call for level");
		g(15, "MET_LEVEL", Certainty.CONFIRMED, 0xA5, false,
				"case 15 -> sub-case 9 of the met-data family: ldrb [blockC+0x2D] & 0x7F = PK6 0xA5 bits 0-6");
		// IVs: the six-way family at code.bin +0x3D28B8, indexed 0..5, each case
		// extracting one 5-bit field of the u32 at PK6 0x74.
		g(16, "IV_HP", Certainty.CONFIRMED, 0x74, false, "IV32 & 0x1F (bits 0-4)");
		g(17, "IV_ATK", Certainty.CONFIRMED, 0x74, false, "IV32 lsl 22, lsr 27 (bits 5-9)");
		g(18, "IV_DEF", Certainty.CONFIRMED, 0x74, false, "IV32 lsl 17, lsr 27 (bits 10-14)");
		g(19, "IV_SPATK", Certainty.CONFIRMED, 0x74, false, "IV32 lsl 7, lsr 27 (bits 20-24)");
		g(20, "IV_SPDEF", Certainty.CONFIRMED, 0x74, false, "IV32 lsl 2, lsr 27 (bits 25-29)");
		g(21, "IV_SPEED", Certainty.CONFIRMED, 0x74, false, "IV32 lsl 12, lsr 27 (bits 15-19)");
		g(22, "ABILITY", Certainty.CONFIRMED, 0x14, false,
				"case 22 -> ldrb [blockA+0xC] = PK6 0x14, the ability id");
		// EVs: the six-way family at code.bin +0x3D2830, one byte each.
		g(23, "EV_HP", Certainty.CONFIRMED, 0x1E, false, "ldrb [blockA+0x16]");
		g(24, "EV_ATK", Certainty.CONFIRMED, 0x1F, false, "ldrb [blockA+0x17]");
		g(25, "EV_DEF", Certainty.CONFIRMED, 0x20, false, "ldrb [blockA+0x18]");
		g(26, "EV_SPATK", Certainty.CONFIRMED, 0x22, false, "ldrb [blockA+0x1A]");
		g(27, "EV_SPDEF", Certainty.CONFIRMED, 0x23, false, "ldrb [blockA+0x1B]");
		g(28, "EV_SPEED", Certainty.CONFIRMED, 0x21, false, "ldrb [blockA+0x19]");
		g(29, "GENDER", Certainty.CONFIRMED, 0x1D, false,
				"case 29 -> egg-guarded; ldrb [blockA+0x15] lsl 29, lsr 30 = PK6 0x1D bits 1-2");
		// Computed battle stats: the six-way family at code.bin +0x3D2EC0. Every
		// case but HP calls the nature getter, which is what a stat calculation
		// does and what pins this family to the calculated stats.
		g(30, "STAT_HP", Certainty.CONFIRMED, 0xF2, false,
				"GetPower(0) -> ldrh [partyExt+0xA] = PK6 0xF2; the one case that does NOT apply nature");
		g(31, "STAT_ATK", Certainty.CONFIRMED, 0xF4, false, "GetPower(1) -> ldrh [partyExt+0xC]");
		g(32, "STAT_DEF", Certainty.CONFIRMED, 0xF6, false, "GetPower(2) -> ldrh [partyExt+0xE]");
		g(33, "STAT_SPATK", Certainty.CONFIRMED, 0xFA, false, "GetPower(3) -> ldrh [partyExt+0x12]");
		g(34, "STAT_SPDEF", Certainty.CONFIRMED, 0xFC, false, "GetPower(4) -> ldrh [partyExt+0x14]");
		g(35, "STAT_SPEED", Certainty.CONFIRMED, 0xF8, false, "GetPower(5) -> ldrh [partyExt+0x10]");
		// Contest conditions: the six-way family at code.bin +0x3D267C, six
		// consecutive bytes, in storage order.
		g(36, "CONTEST_COOL", Certainty.CONFIRMED, 0x24, false, "ldrb [blockA+0x1C]");
		g(37, "CONTEST_BEAUTY", Certainty.CONFIRMED, 0x25, false, "ldrb [blockA+0x1D]");
		g(38, "CONTEST_CUTE", Certainty.CONFIRMED, 0x26, false, "ldrb [blockA+0x1E]");
		g(39, "CONTEST_SMART", Certainty.CONFIRMED, 0x27, false, "ldrb [blockA+0x1F]");
		g(40, "CONTEST_TOUGH", Certainty.CONFIRMED, 0x28, false, "ldrb [blockA+0x20]");
		g(41, "CONTEST_SHEEN", Certainty.CONFIRMED, 0x29, false, "ldrb [blockA+0x21]");
		// 42, 43, 52: traced, but they discard the Pokemon entirely and query a
		// global manager with a constant (37, 38, 21). Not party parameters; not named.
		g(44, "FATEFUL_ENCOUNTER", Certainty.PROBABLE, 0x1D, false,
				"case 44 -> ldrb [blockA+0x15] & 1 = PK6 0x1D bit 0");
		g(45, "HAS_STATUS_CONDITION", Certainty.PROBABLE, 0xE8, false,
				"case 45 -> (u32 at partyExt+0 = PK6 0xE8) & 0xFF, tested nonzero; offset confirmed, the name is PKHeX's");
		g(46, "SUPER_TRAINING_UNLOCKED", Certainty.PROBABLE, 0x72, false,
				"case 46 -> ldrb [blockB+0x32] & 1 = PK6 0x72 bit 0");
		g(47, "SUPER_TRAINING_COMPLETE", Certainty.PROBABLE, 0x72, false,
				"case 47 -> ldrb [blockB+0x32] lsl 30, lsr 31 = PK6 0x72 bit 1");
		g(48, "AFFECTION", Certainty.PROBABLE, 0x93, false,
				"case 48 -> ldrb [blockC+0x1B] = PK6 0x93, or the handler's byte when the player is not the OT");
		g(49, "SHINY_XOR", Certainty.CONFIRMED, -1, false,
				"case 49 -> (TID ^ SID ^ PIDlow ^ PIDhigh) from PK6 0x0C and 0x18; the shininess value");
		g(50, "IS_NICKNAMED", Certainty.CONFIRMED, 0x74, false,
				"case 50 -> IV32 >> 31 = PK6 0x74 bit 31");
		g(51, "HAS_DEFAULT_NAME", Certainty.PROBABLE, -1, false,
				"case 51 -> reads the 13-character nickname and compares it with the species' default name");
		g(53, "ORIGIN_VERSION", Certainty.PROBABLE, 0xA7, false,
				"case 53 -> ldrb [blockC+0x2F] = PK6 0xA7");

		// --- PokePartySetParam, cases 1000..1019 ---------------------------
		// IV setters: value clamped to 31, then one 5-bit field of PK6 0x74
		// rewritten. The index order is the same permutation as the getters.
		s(1000, "IV_HP", Certainty.CONFIRMED, 0x74, false, "clamp 31; bic 0x1F, orr value (bits 0-4)");
		s(1001, "IV_ATK", Certainty.CONFIRMED, 0x74, false, "clamp 31; bic 0x3E0 (bits 5-9)");
		s(1002, "IV_DEF", Certainty.CONFIRMED, 0x74, false, "clamp 31; bic 0x7C00 (bits 10-14)");
		s(1003, "IV_SPATK", Certainty.CONFIRMED, 0x74, false, "clamp 31; bic 0x1F00000 (bits 20-24)");
		s(1004, "IV_SPDEF", Certainty.CONFIRMED, 0x74, false, "clamp 31; bic 0x3E000000 (bits 25-29)");
		s(1005, "IV_SPEED", Certainty.CONFIRMED, 0x74, false, "clamp 31; bic 0xF8000 (bits 15-19)");
		s(1006, "FRIENDSHIP", Certainty.CONFIRMED, 0x92, false,
				"case 1006 -> value clamped to 255, then strb [blockC+0x1A] = PK6 0x92, or the handler's byte when CurrentHandler != 0");
		s(1007, "RIBBON", Certainty.CONFIRMED, 0x30, true,
				"case 1007 -> sets bit arg3 of the u32 at [blockA+0x28] = PK6 0x30 (0x34 for bits 32..63)");
		s(1008, "EV_HP", Certainty.CONFIRMED, 0x1E, false, "clamp 252, 510 total check, then strb [blockA+0x16]");
		s(1009, "EV_ATK", Certainty.CONFIRMED, 0x1F, false, "clamp 252, 510 total check");
		s(1010, "EV_DEF", Certainty.CONFIRMED, 0x20, false, "clamp 252, 510 total check");
		s(1011, "EV_SPATK", Certainty.CONFIRMED, 0x22, false, "clamp 252, 510 total check");
		s(1012, "EV_SPDEF", Certainty.CONFIRMED, 0x23, false, "clamp 252, 510 total check");
		s(1013, "EV_SPEED", Certainty.CONFIRMED, 0x21, false, "clamp 252, 510 total check");
		s(1014, "CONTEST_COOL", Certainty.CONFIRMED, 0x24, false, "clamp 255; strb [blockA+0x1C]");
		s(1015, "CONTEST_BEAUTY", Certainty.CONFIRMED, 0x25, false, "clamp 255; strb [blockA+0x1D]");
		s(1016, "CONTEST_CUTE", Certainty.CONFIRMED, 0x26, false, "clamp 255; strb [blockA+0x1E]");
		s(1017, "CONTEST_SMART", Certainty.CONFIRMED, 0x27, false, "clamp 255; strb [blockA+0x1F]");
		s(1018, "CONTEST_TOUGH", Certainty.CONFIRMED, 0x28, false, "clamp 255; strb [blockA+0x20]");
		s(1019, "CONTEST_SHEEN", Certainty.CONFIRMED, 0x29, false, "clamp 255; strb [blockA+0x21]");
	}

	/** The named Get selector, or null when this selector has no established meaning. */
	public static Param get(int selector) {
		return GET.get(selector);
	}

	/** The named Set selector, or null when this selector has no established meaning. */
	public static Param set(int selector) {
		return SET.get(selector);
	}

	/** True when the Get switch has a case for this selector (named or not). */
	public static boolean isGetSelector(int selector) {
		return selector >= GET_MIN && selector <= GET_MAX;
	}

	/** True when the Set switch has a case for this selector (named or not). */
	public static boolean isSetSelector(int selector) {
		return selector >= SET_MIN && selector <= SET_MAX;
	}

	/**
	 * The gate for any UI that would write a party parameter: true only for a
	 * Set selector whose meaning was confirmed against the executable. Every
	 * other selector - unknown, out of range, or only probable - is false, and
	 * offering it would mean writing an unidentified byte into a player's save.
	 */
	public static boolean isSafeToWrite(int selector) {
		Param p = SET.get(selector);
		return p != null && p.certainty == Certainty.CONFIRMED;
	}

	/** The Set selector that writes this name, or null when nothing writes it. */
	public static Integer setSelectorFor(String name) {
		for (Param p : SET.values()) {
			if (p.name.equals(name)) {
				return p.selector;
			}
		}
		return null;
	}

	/** All named Get selectors, in selector order. Read-only. */
	public static Map<Integer, Param> getTable() {
		return Collections.unmodifiableMap(GET);
	}

	/** All named Set selectors, in selector order. Read-only. */
	public static Map<Integer, Param> setTable() {
		return Collections.unmodifiableMap(SET);
	}
}
