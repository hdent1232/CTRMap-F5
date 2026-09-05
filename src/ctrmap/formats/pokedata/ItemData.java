package ctrmap.formats.pokedata;

/**
 * One item's 36-byte record, as Gen 6 and Gen 7 store it.
 *
 * <p>The layout is an ENGINE constant, not a per-game one, which is why this
 * lives in {@code formats/} rather than in a {@code gamedef} profile: XY, ORAS,
 * SM and USUM all use the same 36 bytes, and only WHERE the table lives differs
 * between them. A profile answers "which archive"; this answers "what is in it".
 *
 * <p>NOT DERIVED HERE. The field mapping is pk3DS's, from
 * {@code pk3DS.Core/Structures/Gen6/Item6.cs} - GPLv3 and compatible, sitting
 * in this tree, and already correct. Re-deriving 36 bytes that somebody has
 * already mapped and shipped would be work with no product. What CTRMap adds is
 * not a better item record; it is items that know about the world built around
 * them, which pk3DS has no way to offer.
 *
 * <p>WHAT THE RECORD CAN AND CANNOT DO, because a tool that blurs this promises
 * what it cannot deliver. The hold-effect id is a dense palette of 183 values,
 * 0..182, with no gaps, and retail SHARES ids across items - Mystic Water, Sea
 * Incense and Wave Incense are all 77. That sharing is the proof that the number
 * selects a behaviour rather than naming the item, so pointing any item at any
 * of the 183 makes it behave that way for the cost of one byte. There is no
 * 184th: a search of code.bin and all 145 CROs for the ARM jump-table idiom
 * found no 183-case dispatch anywhere, because the id is compared at scattered
 * sites inside an event-handler architecture. And some behaviour is keyed on the
 * ITEM ID itself rather than on this record at all - Exp. Share (216) appears as
 * a literal in six code.bin sites and nine CROs, and its record shares a field
 * routine with the Repels. So: reassigning an existing behaviour is a data edit,
 * and authoring a new one is not possible from here.
 *
 * <p>TWO TRAPS worth carrying, both measured against the retail table:
 * <ul>
 * <li>byte 0x10 is CONTEXT-DEPENDENT - a status-cure mask for most items, but
 *     the ball index for Balls. Reading it as one thing everywhere is wrong.</li>
 * <li>byte 0x03 (the held-effect argument) is a general magnitude that is
 *     MIRRORED into the typed healing field for healing items. 112 items use it,
 *     98 of them for something of their own.</li>
 * </ul>
 *
 * <p>Records are fixed-size and contiguous, so an editor can poke bytes in place
 * and never repack the archive - which sidesteps the stale-pack corruption this
 * project has already been bitten by once.
 */
public final class ItemData {

	/** Every record is exactly this long, in every Gen 6/7 game. */
	public static final int SIZE = 36;

	private final byte[] b;

	/** Wraps a copy of one record. Throws when handed something that is not one. */
	public ItemData(byte[] record) {
		if (record == null || record.length != SIZE) {
			throw new IllegalArgumentException("an item record is " + SIZE + " bytes, not "
					+ (record == null ? "null" : String.valueOf(record.length))
					+ " - refusing to read a record that is not one, because a wrong length here"
					+ " would silently shift every field and still look like data");
		}
		this.b = new byte[SIZE];
		System.arraycopy(record, 0, this.b, 0, SIZE);
	}

	/** The bytes, ready to write back. Always {@link #SIZE} long. */
	public byte[] toBytes() {
		byte[] out = new byte[SIZE];
		System.arraycopy(b, 0, out, 0, SIZE);
		return out;
	}

	private int u8(int off) {
		return b[off] & 0xFF;
	}

	private void u8(int off, int v) {
		b[off] = (byte) v;
	}

	private int u16(int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
	}

	private void u16(int off, int v) {
		b[off] = (byte) v;
		b[off + 1] = (byte) (v >> 8);
	}

	// ---- the fields --------------------------------------------------------

	/** Raw stored price. The shop pays {@link #buyPrice()}; see there. */
	public int priceRaw() {
		return u16(0x00);
	}

	public void setPriceRaw(int v) {
		u16(0x00, v);
	}

	/** What the mart charges: the stored value times ten. */
	public int buyPrice() {
		return priceRaw() * 10;
	}

	/** What the mart pays: the stored value times five, so it is never simply half of buy. */
	public int sellPrice() {
		return priceRaw() * 5;
	}

	/** One of the 183 shared behaviours, 0..182. See the class note before changing it. */
	public int heldEffect() {
		return u8(0x02);
	}

	public void setHeldEffect(int v) {
		u8(0x02, v);
	}

	/** The magnitude the held effect uses - Life Orb is 30. Mirrored into healing for healers. */
	public int heldArgument() {
		return u8(0x03);
	}

	public void setHeldArgument(int v) {
		u8(0x03, v);
	}

	public int naturalGiftEffect() {
		return u8(0x04);
	}

	public int flingEffect() {
		return u8(0x05);
	}

	public int flingPower() {
		return u8(0x06);
	}

	public int naturalGiftPower() {
		return u8(0x07);
	}

	/** Bit-packed: gift type, two flags, the field pocket and the battle pocket. */
	public int packed() {
		return u16(0x08);
	}

	public void setPacked(int v) {
		u16(0x08, v);
	}

	public int naturalGiftType() {
		return packed() & 0x1F;
	}

	public int fieldPocket() {
		return (packed() >> 7) & 0xF;
	}

	public int battlePocket() {
		return packed() >> 11;
	}

	/** Routine called when used in the field; 0 means unusable there. */
	public int fieldRoutine() {
		return u8(0x0A);
	}

	public void setFieldRoutine(int v) {
		u8(0x0A, v);
	}

	/** Routine called when used in battle; 0 means unusable there. */
	public int battleRoutine() {
		return u8(0x0B);
	}

	public void setBattleRoutine(int v) {
		u8(0x0B, v);
	}

	/** 0-3 battle, 4 balls, 5 mail. */
	public int classification() {
		return u8(0x0D);
	}

	/** Low nibble consumed on use, high nibble not consumed. */
	public int consumable() {
		return u8(0x0E);
	}

	public int sortIndex() {
		return u8(0x0F);
	}

	public void setSortIndex(int v) {
		u8(0x0F, v);
	}

	/**
	 * Byte 0x10, WHICH MEANS TWO DIFFERENT THINGS.
	 *
	 * <p>For a Ball ({@link #classification()} == 4) it is the ball index; for
	 * everything else it is a bitmask of the statuses the item cures. It is
	 * returned raw and named for the ambiguity on purpose, so a caller has to
	 * decide which it is holding rather than assume.
	 */
	public int cureMaskOrBallIndex() {
		return u8(0x10);
	}

	public boolean isBall() {
		return classification() == 4;
	}

	public boolean revives() {
		return (u8(0x11) & 1) == 0;
	}

	public boolean revivesAll() {
		return ((u8(0x11) >> 1) & 1) == 1;
	}

	public boolean levelsUp() {
		return ((u8(0x11) >> 2) & 1) == 1;
	}

	public boolean isEvoStone() {
		return ((u8(0x11) >> 3) & 1) == 1;
	}

	public int boostAtk() {
		return u8(0x11) >> 4;
	}

	public int boostDef() {
		return u8(0x12) & 0xF;
	}

	public int boostSpa() {
		return u8(0x12) >> 4;
	}

	public int boostSpd() {
		return u8(0x13) & 0xF;
	}

	public int boostSpe() {
		return u8(0x13) >> 4;
	}

	public int boostAcc() {
		return u8(0x14) & 0xF;
	}

	public int boostCrit() {
		return u8(0x14) >> 4;
	}

	/** EV deltas, SIGNED - some items take EVs away. Order: HP ATK DEF SPE SPA SPD. */
	public int ev(int i) {
		if (i < 0 || i > 5) {
			throw new IllegalArgumentException("there are six EV stats, not index " + i);
		}
		return b[0x17 + i];               // signed on purpose
	}

	public void setEv(int i, int v) {
		if (i < 0 || i > 5) {
			throw new IllegalArgumentException("there are six EV stats, not index " + i);
		}
		if (v < -128 || v > 127) {
			throw new IllegalArgumentException("an EV delta is one signed byte; " + v
					+ " would wrap and silently mean something else");
		}
		b[0x17 + i] = (byte) v;
	}

	public int healAmount() {
		return u8(0x1D);
	}

	public void setHealAmount(int v) {
		u8(0x1D, v);
	}

	public int ppGain() {
		return u8(0x1E);
	}

	/** Three friendship deltas, signed, applied at low/mid/high friendship. */
	public int friendship(int i) {
		if (i < 0 || i > 2) {
			throw new IllegalArgumentException("there are three friendship deltas, not index " + i);
		}
		return b[0x1F + i];
	}

	/**
	 * True when this record is entirely zero.
	 *
	 * <p>Four ids - 113, 114, 115 and 126 - are genuinely empty in retail and
	 * sit below every engine bound, so they are the only slots a new item can
	 * occupy. Four is a real limit and any UI must say so rather than implying
	 * more. Note that an empty record does NOT always mean an inert item:
	 * Ability Capsule (645) has an all-zero record and works anyway, because its
	 * behaviour is keyed on the item id in code.
	 */
	public boolean isBlank() {
		for (int i = 0; i < SIZE; i++) {
			if (b[i] != 0) {
				return false;
			}
		}
		return true;
	}
}
