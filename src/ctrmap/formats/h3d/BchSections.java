package ctrmap.formats.h3d;

import static ctrmap.util.LittleEndian.i32;
import static ctrmap.util.LittleEndian.u16;

/**
 * A BCH file's header - where each section starts and how long it is - and its relocation table:
 * the one reader of both (F066).
 *
 * <p>WHY THERE IS ONE. Five classes read this header themselves (the map model, the texture pack,
 * the model appender, the area animation and the upstream model loader) and four of them decoded
 * the relocation table themselves too. They did not agree: the area animation placed every
 * relocation as if it came from the contents section, so a command-section entry was recorded at
 * a contents address it has nothing to do with. Before they were merged, what each made of two
 * dump-free files was recorded, and {@code ctrmap.tests.BchSectionsTest} holds every reader to it.
 *
 * <p>The header layout depends on the backward-compatibility byte: the raw-extension section
 * exists past 0x20, the flags and address count past 7. The relocation words are given as they
 * are; {@link #reloc} decodes one the 0x21 way (a target nibble and a source nibble), which is
 * every layout this program writes. Older layouts give their flags other meanings, and the
 * loader that opens them keeps its own reading of the same words.
 *
 * <p>Not: BCHHeader - that is the upstream loader's field bag, filled from here; this is the
 * reading itself, shared by every reader of the bytes.
 */
public final class BchSections {

	/** Section ids, as a 0x21 relocation's nibbles name them. */
	public static final int CONTENTS = 0, STRINGS = 1, COMMANDS = 2, RAWDATA = 4, RAWDATA_TEX = 5, RAWDATA_VTX = 6,
			RAWDATA_IDX16 = 7, RAWDATA_IDX8 = 8, RAWEXT = 9, BASEADDR = 14;

	/** The two compatibility bytes and the converter version. */
	public final int bc, fc, version;
	public final int contentsAddr, stringsAddr, commandsAddr, rawDataAddr, rawExtAddr, relocAddr;
	public final int contentsLen, stringsLen, commandsLen, rawDataLen, rawExtLen, relocLen;
	public final int uninitData, uninitCmd;
	/** The flags halfword and the address count; 0 when the layout predates them. */
	public final int flags, addressCount;
	/** The relocation table's words, in file order. */
	public final int[] relocWords;

	/** One 0x21 relocation entry, decoded. */
	public static final class Reloc {

		/** The word as stored. */
		public final int word;
		/** The 7-bit flag: source section in the high nibble, target in the low one. */
		public final int flag, target, source;
		/** Where the patched pointer sits, absolute; -1 when the source is not a section with a base. */
		public final int ptrLoc;

		Reloc(int word, int flag, int target, int source, int ptrLoc) {
			this.word = word;
			this.flag = flag;
			this.target = target;
			this.source = source;
			this.ptrLoc = ptrLoc;
		}
	}

	private BchSections(byte[] b) {
		bc = b[4] & 0xFF;
		fc = b[5] & 0xFF;
		version = u16(b, 6);
		int c = 8;
		contentsAddr = i32(b, c);
		stringsAddr = i32(b, c + 4);
		commandsAddr = i32(b, c + 8);
		rawDataAddr = i32(b, c + 12);
		c += 16;
		boolean ext = bc > 0x20;
		rawExtAddr = ext ? i32(b, c) : 0;
		c += ext ? 4 : 0;
		relocAddr = i32(b, c);
		c += 4;
		contentsLen = i32(b, c);
		stringsLen = i32(b, c + 4);
		commandsLen = i32(b, c + 8);
		rawDataLen = i32(b, c + 12);
		c += 16;
		rawExtLen = ext ? i32(b, c) : 0;
		c += ext ? 4 : 0;
		relocLen = i32(b, c);
		c += 4;
		uninitData = i32(b, c);
		uninitCmd = i32(b, c + 4);
		c += 8;
		boolean flagged = bc > 7;
		flags = flagged ? u16(b, c) : 0;
		addressCount = flagged ? u16(b, c + 2) : 0;
		if (relocLen < 0 || relocAddr < 0 || (long) relocAddr + relocLen > b.length) {
			throw new IllegalArgumentException("The BCH's relocation table (" + relocLen + " bytes at " + relocAddr
					+ ") runs past the end of the file (" + b.length + " bytes).");
		}
		relocWords = new int[relocLen / 4];
		for (int i = 0; i < relocWords.length; i++) {
			relocWords[i] = i32(b, relocAddr + i * 4);
		}
	}

	/** The header byte count for this compatibility byte: where the content section may start. */
	static int headerSize(int bc) {
		return 8 + 16 + (bc > 0x20 ? 4 : 0) + 4 + 16 + (bc > 0x20 ? 4 : 0) + 4 + 8 + (bc > 7 ? 4 : 0);
	}

	/**
	 * True when {@code b} starts with the four magic bytes "BCH\0" - the sniff that decides which
	 * reader to hand a file to, which then reads (and may refuse) the rest. The one copy (F066:
	 * six hand-written checks disagreed on three bytes or four, and on how long a file must be).
	 */
	public static boolean hasMagic(byte[] b) {
		return b != null && b.length >= 4 && b[0] == 'B' && b[1] == 'C' && b[2] == 'H' && b[3] == 0;
	}

	/** True when {@code b} has the magic and is long enough for the header it declares. */
	public static boolean isBch(byte[] b) {
		return hasMagic(b) && b.length >= 8 && b.length >= headerSize(b[4] & 0xFF);
	}

	/**
	 * The header and relocation words of {@code b}.
	 *
	 * @throws IllegalArgumentException when it is not a BCH, or its relocation table is not inside it
	 */
	public static BchSections read(byte[] b) {
		if (!isBch(b)) {
			throw new IllegalArgumentException("not a BCH");
		}
		return new BchSections(b);
	}

	/** Where a 0x21 relocation's source section starts; -1 for a source with no base. */
	public int sectionBase(int sec) {
		switch (sec) {
			case CONTENTS:
				return contentsAddr;
			case STRINGS:
				return stringsAddr;
			case COMMANDS:
				return commandsAddr;
			case RAWDATA:
				return rawDataAddr;
			case RAWEXT:
				return rawExtAddr;
			default:
				return -1;
		}
	}

	/** Where a 0x21 relocation's target section starts - the base its pointer is relative to. */
	public int targetBase(int target) {
		switch (target) {
			case CONTENTS:
				return contentsAddr;
			case STRINGS:
				return stringsAddr;
			case COMMANDS:
				return commandsAddr;
			case RAWDATA:
			case RAWDATA_TEX:
			case RAWDATA_VTX:
			case RAWDATA_IDX16:
			case RAWDATA_IDX8:
				return rawDataAddr;
			case BASEADDR:
				return 0;
			default:
				return rawExtAddr;
		}
	}

	/** Relocation {@code i}, decoded the 0x21 way. */
	public Reloc reloc(int i) {
		return decode(relocWords[i]);
	}

	/** A relocation word, decoded the 0x21 way against this file's sections. */
	public Reloc decode(int v) {
		int off = v & 0x1FFFFFF;
		int flag = (v >>> 25) & 0x7F;
		int target = flag & 0xF;
		int source = flag >>> 4;
		int base = sectionBase(source);
		return new Reloc(v, flag, target, source, base < 0 ? -1 : base + (target == STRINGS ? off : off * 4));
	}

	/** How many relocation entries there are. */
	public int relocCount() {
		return relocWords.length;
	}
}
