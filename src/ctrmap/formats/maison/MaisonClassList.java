package ctrmap.formats.maison;

import java.util.ArrayList;
import java.util.List;

/**
 * One Battle Maison class-to-set-list record: which opponent {@link MaisonSet}s
 * a given trainer class draws from. Stored one per GARC entry in the two list
 * tables (romfs a/1/8/3 -&gt; set pool a/1/8/2, a/1/8/5 -&gt; pool a/1/8/4).
 * Layout (reverse-engineered, corpus-verified, zero dangling refs):
 * <pre>
 * 0x00 u16 classTag   (trainer class 2..279, same space as trclass a/0/3/7)
 * 0x02 u16 count      (number of set indices used)
 * 0x04 count x u16    set indices into the paired pool
 * </pre>
 * Table a/1/8/3 stores fixed 136-byte entries (up to 66 slots, unused = 0xFFFF);
 * table a/1/8/5 stores tight entries (exactly 4 + count*2 bytes). The codec
 * reproduces the source entry's exact length so a no-edit round-trip is
 * byte-identical.
 */
public class MaisonClassList {

	public static final int PAD = 0xFFFF;

	public int classTag;
	public final List<Integer> setIndices = new ArrayList<>();
	/** Source entry length, reproduced on write (fixed-padded tables keep their size). */
	public int sourceLength;

	public static MaisonClassList read(byte[] rec) {
		if (rec == null || rec.length < 4) {
			throw new IllegalArgumentException("class-list record too short");
		}
		MaisonClassList l = new MaisonClassList();
		l.sourceLength = rec.length;
		l.classTag = u16(rec, 0);
		int count = u16(rec, 2);
		for (int i = 0; i < count && 4 + i * 2 + 1 < rec.length; i++) {
			l.setIndices.add(u16(rec, 4 + i * 2));
		}
		return l;
	}

	/**
	 * Emits the record. Reproduces {@link #sourceLength} when the used data fits
	 * (fixed-padded tables keep their byte size, tail = 0xFFFF); otherwise emits
	 * a tight 4 + count*2 record (safe for the variable table, and the only
	 * option when more indices were added than the fixed slot count).
	 */
	public byte[] write() {
		int tight = 4 + setIndices.size() * 2;
		int len = Math.max(tight, sourceLength);
		byte[] out = new byte[len];
		pu16(out, 0, classTag);
		pu16(out, 2, setIndices.size());
		for (int i = 0; i < setIndices.size(); i++) {
			pu16(out, 4 + i * 2, setIndices.get(i));
		}
		for (int o = tight; o + 1 < len; o += 2) {
			pu16(out, o, PAD);
		}
		return out;
	}

	private static int u16(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
	}

	private static void pu16(byte[] b, int o, int v) {
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
	}
}
