package ctrmap.formats.recordschema;

/**
 * One field of a fixed-stride record: where its bits are, how wide they are,
 * whether they are signed, and how a person should be shown them.
 *
 * <p>WHY BITS RATHER THAN A TYPE ENUM. The structures this editor has to render
 * do not divide neatly into bytes: the item record packs the four stat-boost
 * values two to a byte, keeps four independent flags in the low nibble of
 * another, and spends sixteen bits on a natural-gift type plus two pocket
 * indices. A field described as "u8 at 0x12" cannot say any of that, so every
 * such structure ends up with hand-written accessors and the schema stops being
 * the description of the record. One (byte offset, bit offset, bit count,
 * signed) tuple covers a checkbox, a nibble, a byte, a signed byte and a
 * sixteen-bit word with the same four numbers, and the editor can generate a
 * control for any of them without knowing what the field means.
 *
 * <p>The SIGNED flag is not decoration. Six of the item record's fields are
 * signed EV deltas and some items REMOVE EVs; reading one of those unsigned
 * turns -10 into 246 and nothing anywhere complains. A schema that could not
 * say "signed" would be quietly wrong for exactly the fields a user is most
 * likely to change.
 */
public final class RecordField {

	/** How the editor should present the field. Not what it means - only how to draw it. */
	public enum Kind {
		/** A plain number in a spinner, bounded by the field's own width. */
		NUMBER,
		/** A single bit as a checkbox. */
		FLAG,
		/**
		 * A number chosen from a labelled list. The labels are not written down
		 * anywhere in the game, so they are DERIVED from the records themselves
		 * - see {@link ctrmap.formats.pokedata.ItemEffectLabels}. The field
		 * names which derivation to use through {@link #effectKind}.
		 */
		EFFECT,
		/** A number that is an item id, so the editor can show the item's name. */
		ITEM_ID
	}

	private final String name;
	private final String help;
	private final String group;
	private final int byteOffset;
	private final int bitOffset;
	private final int bitCount;
	private final boolean signed;
	private final Kind kind;
	private final ctrmap.formats.pokedata.ItemEffectLabels.Kind effectKind;
	private final boolean editable;

	private RecordField(String name, String help, String group, int byteOffset, int bitOffset,
			int bitCount, boolean signed, Kind kind,
			ctrmap.formats.pokedata.ItemEffectLabels.Kind effectKind, boolean editable) {
		if (bitCount < 1 || bitCount > 32) {
			throw new IllegalArgumentException("a field is 1..32 bits, not " + bitCount);
		}
		if (bitOffset < 0 || bitOffset > 7) {
			throw new IllegalArgumentException("the bit offset is within a byte, 0..7, not " + bitOffset);
		}
		if (byteOffset < 0) {
			throw new IllegalArgumentException("negative offset");
		}
		if (signed && kind != Kind.NUMBER) {
			throw new IllegalArgumentException("only a plain number is rendered signed");
		}
		this.name = name;
		this.help = help;
		this.group = group;
		this.byteOffset = byteOffset;
		this.bitOffset = bitOffset;
		this.bitCount = bitCount;
		this.signed = signed;
		this.kind = kind;
		this.effectKind = effectKind;
		this.editable = editable;
	}

	// ---- the ways a field gets built ---------------------------------------

	/** An unsigned number occupying whole bytes. */
	public static RecordField number(String group, String name, int off, int bits, String help) {
		return new RecordField(name, help, group, off, 0, bits, false, Kind.NUMBER, null, true);
	}

	/** A SIGNED number occupying whole bytes - the EV deltas, which can be negative. */
	public static RecordField signedNumber(String group, String name, int off, int bits, String help) {
		return new RecordField(name, help, group, off, 0, bits, true, Kind.NUMBER, null, true);
	}

	/** Some bits inside a byte: a nibble, or a flag when {@code bits} is 1. */
	public static RecordField bits(String group, String name, int off, int bitOff, int bits, String help) {
		return new RecordField(name, help, group, off, bitOff, bits, false,
				bits == 1 ? Kind.FLAG : Kind.NUMBER, null, true);
	}

	/** A number whose meaning is only knowable from which retail items carry it. */
	public static RecordField effect(String group, String name, int off, int bits,
			ctrmap.formats.pokedata.ItemEffectLabels.Kind effectKind, String help) {
		return new RecordField(name, help, group, off, 0, bits, false, Kind.EFFECT, effectKind, true);
	}

	/** An item id, shown with the item's name. */
	public static RecordField itemId(String group, String name, int off, int bits, String help) {
		return new RecordField(name, help, group, off, 0, bits, false, Kind.ITEM_ID, null, true);
	}

	/**
	 * Same field, shown but not changeable.
	 *
	 * <p>Used for the parts of a record whose meaning is understood well enough
	 * to display and not well enough to edit safely - byte 0x10 of an item is a
	 * status-cure mask for most items and a BALL INDEX for Balls, and an editor
	 * that offered one spinner for both would silently turn a Great Ball into
	 * something that cures sleep.
	 */
	public RecordField readOnly() {
		return new RecordField(name, help, group, byteOffset, bitOffset, bitCount, signed,
				kind, effectKind, false);
	}

	// ---- reading and writing ----------------------------------------------

	/** How many bytes of the record this field touches. */
	public int byteSpan() {
		return (bitOffset + bitCount + 7) / 8;
	}

	/** One past the last byte this field touches. */
	public int endByte() {
		return byteOffset + byteSpan();
	}

	public int get(byte[] record) {
		checkFits(record);
		long word = 0;
		for (int i = byteSpan() - 1; i >= 0; i--) {
			word = (word << 8) | (record[byteOffset + i] & 0xFF);
		}
		int v = (int) ((word >>> bitOffset) & mask());
		if (signed && bitCount < 32 && (v & (1 << (bitCount - 1))) != 0) {
			v -= (1 << bitCount);
		}
		return v;
	}

	public void set(byte[] record, int value) {
		checkFits(record);
		if (value < min() || value > max()) {
			throw new IllegalArgumentException(name + " holds " + min() + ".." + max()
					+ "; " + value + " would wrap and silently mean something else");
		}
		long word = 0;
		for (int i = byteSpan() - 1; i >= 0; i--) {
			word = (word << 8) | (record[byteOffset + i] & 0xFF);
		}
		long m = mask() << bitOffset;
		word = (word & ~m) | ((((long) value) & mask()) << bitOffset);
		for (int i = 0; i < byteSpan(); i++) {
			record[byteOffset + i] = (byte) (word >>> (8 * i));
		}
	}

	private long mask() {
		return bitCount == 32 ? 0xFFFFFFFFL : ((1L << bitCount) - 1);
	}

	private void checkFits(byte[] record) {
		if (record == null || endByte() > record.length) {
			throw new IllegalArgumentException(name + " lives at bytes " + byteOffset + ".."
					+ (endByte() - 1) + ", past the end of a " + (record == null ? "null" : record.length)
					+ "-byte record");
		}
	}

	public int min() {
		return signed ? -(1 << (bitCount - 1)) : 0;
	}

	/**
	 * The largest value the field can hold, as an int.
	 *
	 * <p>A 32-bit UNSIGNED field is capped at {@link Integer#MAX_VALUE} rather
	 * than at 0xFFFFFFFF, because the other half of the range has no int to put
	 * it in: {@code (int) 0xFFFFFFFFL} is -1, and a bound of -1 would reject
	 * every value there is. Nothing this schema describes today comes near it -
	 * the widest field is the item-icon table's u32, whose retail values run
	 * 0..629 - and a reader of such a field is expected to bound-check what it
	 * got, which {@code ItemIconTable} does.
	 */
	public int max() {
		if (signed) {
			return (1 << (bitCount - 1)) - 1;
		}
		return bitCount >= 32 ? Integer.MAX_VALUE : (int) mask();
	}

	public String name() {
		return name;
	}

	public String help() {
		return help;
	}

	public String group() {
		return group;
	}

	public int byteOffset() {
		return byteOffset;
	}

	public int bitOffset() {
		return bitOffset;
	}

	public int bitCount() {
		return bitCount;
	}

	public boolean signed() {
		return signed;
	}

	public Kind kind() {
		return kind;
	}

	public boolean editable() {
		return editable;
	}

	public ctrmap.formats.pokedata.ItemEffectLabels.Kind effectKind() {
		return effectKind;
	}

	@Override
	public String toString() {
		return name + " @0x" + Integer.toHexString(byteOffset)
				+ (bitCount % 8 == 0 && bitOffset == 0 ? "" : "." + bitOffset)
				+ " " + (signed ? "s" : "u") + bitCount;
	}
}
