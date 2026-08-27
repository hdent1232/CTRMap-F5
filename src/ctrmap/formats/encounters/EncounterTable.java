package ctrmap.formats.encounters;

import java.util.ArrayList;
import java.util.List;

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
				int raw = (blob[off] & 0xFF) | ((blob[off + 1] & 0xFF) << 8);
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
					out[off] = (byte) raw;
					out[off + 1] = (byte) (raw >> 8);
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

	/** The zone's table from an EN pack, or null when the zone has no wild data. */
	public static EncounterTable read(byte[] enPack, int zone) {
		int count = (enPack[2] & 0xFF) | ((enPack[3] & 0xFF) << 8);
		if (zone < 0 || zone >= count) {
			throw new IllegalArgumentException("zone " + zone + " out of range (EN count " + count + ")");
		}
		int o0 = i32(enPack, 4 + zone * 4), o1 = i32(enPack, 4 + (zone + 1) * 4);
		if (o1 - o0 == 0) {
			return null;
		}
		byte[] blob = new byte[o1 - o0];
		System.arraycopy(enPack, o0, blob, 0, blob.length);
		return fromBlob(blob);
	}

	/**
	 * Returns a new EN pack with the zone's table replaced ({@code table} null
	 * or empty removes the zone's wild data). Every other zone's bytes are
	 * carried verbatim; the offset table is rebuilt.
	 */
	public static byte[] write(byte[] enPack, int zone, EncounterTable table) {
		int count = (enPack[2] & 0xFF) | ((enPack[3] & 0xFF) << 8);
		if (zone < 0 || zone >= count) {
			throw new IllegalArgumentException("zone " + zone + " out of range (EN count " + count + ")");
		}
		byte[] blob = (table == null || table.isEmpty()) ? new byte[0] : table.toBlob();
		List<byte[]> blobs = new ArrayList<>();
		for (int z = 0; z < count; z++) {
			if (z == zone) {
				blobs.add(blob);
			} else {
				int o0 = i32(enPack, 4 + z * 4), o1 = i32(enPack, 4 + (z + 1) * 4);
				byte[] bz = new byte[o1 - o0];
				System.arraycopy(enPack, o0, bz, 0, bz.length);
				blobs.add(bz);
			}
		}
		int tableEnd = 4 + (count + 1) * 4;
		int total = tableEnd;
		for (byte[] bz : blobs) {
			total += bz.length;
		}
		byte[] out = new byte[total];
		out[0] = 'E';
		out[1] = 'N';
		out[2] = (byte) count;
		out[3] = (byte) (count >> 8);
		int off = tableEnd;
		for (int z = 0; z < count; z++) {
			p32(out, 4 + z * 4, off);
			byte[] bz = blobs.get(z);
			System.arraycopy(bz, 0, out, off, bz.length);
			off += bz.length;
		}
		p32(out, 4 + count * 4, off);
		return out;
	}

	private static int i32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}

	private static void p32(byte[] b, int o, int v) {
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
		b[o + 2] = (byte) (v >> 16);
		b[o + 3] = (byte) (v >> 24);
	}
}
