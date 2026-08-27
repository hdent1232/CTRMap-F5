package ctrmap.formats.maison;

/**
 * One Battle Maison opponent Pokemon "set" - the fixed 16-byte record stored,
 * one per GARC entry, in the three Maison set pools (romfs a/1/8/2, a/1/8/4,
 * a/1/8/6; 999 entries each). Layout reverse-engineered and corpus-verified:
 * <pre>
 * 0x00 u16 species        (national dex; 0 = empty slot)
 * 0x02 u16 move1
 * 0x04 u16 move2
 * 0x06 u16 move3
 * 0x08 u16 move4
 * 0x0A u8  evSpreadPreset (index into an engine EV table)
 * 0x0B u8  nature         (0 Hardy .. 24 Quirky)
 * 0x0C u16 heldItem
 * 0x0E u16 formFlag       (form / flag bits)
 * </pre>
 * The engine decides level (Lv50 in Normal), IVs and ability - those are NOT in
 * the record. Class-to-set-list tables (a/1/8/3 -&gt; pool 2, a/1/8/5 -&gt; pool 4)
 * choose which sets each trainer class draws from. Editing sets re-skins the
 * opponents' teams with no code patch.
 */
public class MaisonSet {

	public static final int SIZE = 16;

	public int species;
	public final int[] moves = new int[4];
	public int evSpreadPreset;
	public int nature;
	public int heldItem;
	public int formFlag;

	public static MaisonSet read(byte[] rec) {
		if (rec == null || rec.length < SIZE) {
			throw new IllegalArgumentException("Maison set record must be " + SIZE + " bytes");
		}
		MaisonSet s = new MaisonSet();
		s.species = u16(rec, 0);
		for (int i = 0; i < 4; i++) {
			s.moves[i] = u16(rec, 2 + i * 2);
		}
		s.evSpreadPreset = rec[0x0A] & 0xFF;
		s.nature = rec[0x0B] & 0xFF;
		s.heldItem = u16(rec, 0x0C);
		s.formFlag = u16(rec, 0x0E);
		return s;
	}

	public byte[] write() {
		byte[] out = new byte[SIZE];
		pu16(out, 0, species);
		for (int i = 0; i < 4; i++) {
			pu16(out, 2 + i * 2, moves[i]);
		}
		out[0x0A] = (byte) evSpreadPreset;
		out[0x0B] = (byte) nature;
		pu16(out, 0x0C, heldItem);
		pu16(out, 0x0E, formFlag);
		return out;
	}

	public boolean isEmpty() {
		return species == 0;
	}

	private static int u16(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
	}

	private static void pu16(byte[] b, int o, int v) {
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
	}
}
