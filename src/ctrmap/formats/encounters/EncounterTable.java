package ctrmap.formats.encounters;

import static ctrmap.util.LittleEndian.u16;
import static ctrmap.util.LittleEndian.putU16;

/**
 * One zone's wild-encounter table - the 260-byte record inside the ORAS "EN"
 * pack (ZoneData a/0/1/3, last entry). Format fully measured and adversarially
 * verified against every retail blob (150 non-empty, zero invariant
 * violations; retail anchors like Route 101's Wurmple/Zigzagoon/Poochyena
 * reproduce exactly):
 * <pre>
 * 0x00  u8[9] rates: grass, longGrass, dexnavSpecial, surf, rockSmash,
 *              oldRod, goodRod, superRod, horde(all three)
 * 0x09  u8[5] zero padding
 * 0x0E  61 slots x {u16 species|form&lt;&lt;11, u8 minLevel, u8 maxLevel}:
 *       grass 12, long grass 12, dexnav-hidden 3, surf 5, rock smash 5,
 *       old/good/super rod 3 each, horde A/B/C 5 each
 * 0x102 u16 zero
 * </pre>
 * Empty slot = species 0 with level bytes 01 01; a bank is active iff its rate
 * byte is nonzero and it has at least one species (retail is 100% consistent
 * about this - the editor keeps it that way automatically).
 */
public class EncounterTable {

	public static final int BLOB_SIZE = 260;
	/** Bank names, sizes and rate-byte index, in on-disk slot order. */
	public static final String[] BANK_NAMES = {
		"Grass / cave / desert", "Long grass", "DexNav hidden", "Surf", "Rock Smash",
		"Old Rod", "Good Rod", "Super Rod", "Horde A", "Horde B", "Horde C"
	};
	public static final int[] BANK_SIZES = {12, 12, 3, 5, 5, 3, 3, 3, 5, 5, 5};
	public static final int[] BANK_RATE = {0, 1, 2, 3, 4, 5, 6, 7, 8, 8, 8};
	/** Retail-typical rate value per rate byte (used when a bank first gains data). */
	public static final int[] DEFAULT_RATES = {1, 5, 1, 9, 1, 50, 50, 50, 10};

	public static class Slot {

		public int species;   // national dex, 0 = empty
		public int form;      // 0..31 (31 = random-form sentinel, e.g. Mirage Cave Unown)
		public int minLevel = 1;
		public int maxLevel = 1;

		public boolean isEmpty() {
			return species == 0;
		}
	}

	public final int[] rates = new int[9];
	/** banks[b][s] per BANK_NAMES/BANK_SIZES. */
	public final Slot[][] banks = new Slot[BANK_SIZES.length][];

	public EncounterTable() {
		for (int b = 0; b < BANK_SIZES.length; b++) {
			banks[b] = new Slot[BANK_SIZES[b]];
			for (int s = 0; s < BANK_SIZES[b]; s++) {
				banks[b][s] = new Slot();
			}
		}
	}

	/** Parses a 260-byte blob. */
	public static EncounterTable fromBlob(byte[] blob) {
		if (blob == null || blob.length != BLOB_SIZE) {
			throw new IllegalArgumentException("encounter blob must be " + BLOB_SIZE + " bytes");
		}
		EncounterTable t = new EncounterTable();
		for (int i = 0; i < 9; i++) {
			t.rates[i] = blob[i] & 0xFF;
		}
		int off = 0x0E;
		for (int b = 0; b < BANK_SIZES.length; b++) {
			for (int s = 0; s < BANK_SIZES[b]; s++) {
				int raw = u16(blob, off);
				Slot slot = t.banks[b][s];
				slot.species = raw & 0x7FF;
				slot.form = raw >>> 11;
				slot.minLevel = blob[off + 2] & 0xFF;
				slot.maxLevel = blob[off + 3] & 0xFF;
				off += 4;
			}
		}
		return t;
	}

	/**
	 * Emits the 260-byte blob. Keeps the retail invariants automatically: empty
	 * slots write as 00 00 01 01; each rate byte is forced nonzero (its current
	 * value, or the retail default) when its bank has species, and zero when
	 * empty - so a table can never soft-brick a method.
	 */
	public byte[] toBlob() {
		byte[] out = new byte[BLOB_SIZE];
		boolean[] bankUsed = new boolean[BANK_SIZES.length];
		int off = 0x0E;
		for (int b = 0; b < BANK_SIZES.length; b++) {
			for (int s = 0; s < BANK_SIZES[b]; s++) {
				Slot slot = banks[b][s];
				if (slot.isEmpty()) {
					out[off + 2] = 1;
					out[off + 3] = 1;
				} else {
					if (slot.species > 0x7FF || slot.form > 31) {
						throw new IllegalArgumentException("invalid species/form " + slot.species + "/" + slot.form);
					}
					int raw = slot.species | (slot.form << 11);
					putU16(out, off, raw);
					out[off + 2] = (byte) Math.max(1, Math.min(100, slot.minLevel));
					out[off + 3] = (byte) Math.max(1, Math.min(100, slot.maxLevel));
					bankUsed[b] = true;
				}
				off += 4;
			}
		}
		for (int i = 0; i < 9; i++) {
			boolean used = false;
			for (int b = 0; b < BANK_SIZES.length; b++) {
				if (BANK_RATE[b] == i && bankUsed[b]) {
					used = true;
				}
			}
			out[i] = (byte) (used ? (rates[i] != 0 ? rates[i] : DEFAULT_RATES[i]) : 0);
		}
		return out;
	}

	/** True when no bank has any species (the zone should carry an empty blob). */
	public boolean isEmpty() {
		for (Slot[] bank : banks) {
			for (Slot s : bank) {
				if (!s.isEmpty()) {
					return false;
				}
			}
		}
		return true;
	}

	// ---- EN pack integration ---------------------------------------------

	/**
	 * The zone's table from an EN pack, or null when the zone has no wild data. The pack is read
	 * strictly ({@link EnPack#parse}): it was read as any bytes, and a bad pair of offsets was a
	 * NegativeArraySizeException in the middle of an edit (F053).
	 */
	public static EncounterTable read(byte[] enPack, int zone) {
		byte[] blob = EnPack.parse(enPack).blob(zone);
		return blob.length == 0 ? null : fromBlob(blob);
	}

	/**
	 * Returns a new EN pack with the zone's table replaced ({@code table} null
	 * or empty removes the zone's wild data). Every other zone's bytes are
	 * carried verbatim; the pack is written by {@link EnPack}, its one writer.
	 */
	public static byte[] write(byte[] enPack, int zone, EncounterTable table) {
		byte[] blob = (table == null || table.isEmpty()) ? new byte[0] : table.toBlob();
		return EnPack.parse(enPack).withBlob(zone, blob).bytes();
	}
}
