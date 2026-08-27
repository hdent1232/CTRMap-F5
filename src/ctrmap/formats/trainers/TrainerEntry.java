package ctrmap.formats.trainers;

import java.util.ArrayList;
import java.util.List;

/**
 * One ORAS trainer: the 24-byte trdata record (a/0/3/6) plus its party from
 * trpoke (a/0/3/8, same index). Formats fully measured and anchor-validated
 * (Roxanne/Brawly/Steven byte-exact, Route 102 roster by name):
 * <pre>
 * trdata: u16 flags (bit0 = party has moves, bit1 = has held items),
 *         u16 class, u16 =0, u8 battleType, u8 numPokemon,
 *         4x u16 AI battle items, u32 AI flags (opaque - preserve),
 *         u8 =0, u8 moneyRate, u16 =0
 * trpoke: numPokemon x perMon, perMon = 8 + 2*hasItem + 8*hasMoves:
 *         u8 ivByte (all-stat IV = v*31/255), u8 gender(lo)|ability(hi),
 *         u16 level, u16 species, u16 form,
 *         [u16 heldItem]  [4x u16 moves]   (item BEFORE moves)
 * </pre>
 * NPC link: a map NPC with script id 3000+tid (5000+tid double partner)
 * battles trainer tid. Serialization is byte-compatible with retail/pk3DS -
 * flags and per-mon stride are kept consistent automatically on write.
 */
public class TrainerEntry {

	public static class PartyMon {

		public int species;
		public int form;
		public int level = 5;
		public int ivByte;          // all-stat IVs = ivByte*31/255 (0 / 0xA0 / 0xF0 retail tiers)
		public int genderAbility;   // lo nibble gender 0=random 1=M 2=F; hi nibble ability 0=default 1/2=slot 3=hidden
		public int heldItem;        // 0 = none
		public final int[] moves = new int[4]; // 0 = default level-up moveset
	}

	public int classId;
	public int battleType;          // 0 single, 1 double, 2..4 rare/unmapped - raw
	public final int[] aiItems = new int[4];
	public long aiFlags;            // opaque bitfield - preserved verbatim
	public int moneyRate;
	/** The format flags as READ - writes preserve this shape (a retail entry may
	 *  declare item/move capacity with all-zero slots) and only ever WIDEN it. */
	public int formatFlags;
	public final List<PartyMon> party = new ArrayList<>();

	/** Parses a trainer from its trdata + trpoke entries. */
	public static TrainerEntry read(byte[] trdataEntry, byte[] trpokeEntry) {
		if (trdataEntry == null || trdataEntry.length < 24) {
			throw new IllegalArgumentException("trdata entry too short");
		}
		TrainerEntry t = new TrainerEntry();
		int flags = u16(trdataEntry, 0);
		t.formatFlags = flags;
		boolean hasMoves = (flags & 1) != 0, hasItem = (flags & 2) != 0;
		t.classId = u16(trdataEntry, 2);
		t.battleType = trdataEntry[6] & 0xFF;
		int count = trdataEntry[7] & 0xFF;
		for (int i = 0; i < 4; i++) {
			t.aiItems[i] = u16(trdataEntry, 8 + i * 2);
		}
		t.aiFlags = ((long) u16(trdataEntry, 0x10)) | ((long) u16(trdataEntry, 0x12) << 16);
		t.moneyRate = trdataEntry[0x15] & 0xFF;

		int perMon = 8 + (hasItem ? 2 : 0) + (hasMoves ? 8 : 0);
		if (trpokeEntry == null || trpokeEntry.length < count * perMon) {
			throw new IllegalArgumentException("trpoke entry shorter than numPokemon x perMon");
		}
		for (int m = 0; m < count; m++) {
			int o = m * perMon;
			PartyMon mon = new PartyMon();
			mon.ivByte = trpokeEntry[o] & 0xFF;
			mon.genderAbility = trpokeEntry[o + 1] & 0xFF;
			mon.level = u16(trpokeEntry, o + 2);
			mon.species = u16(trpokeEntry, o + 4);
			mon.form = u16(trpokeEntry, o + 6);
			int p = o + 8;
			if (hasItem) {
				mon.heldItem = u16(trpokeEntry, p);
				p += 2;
			}
			if (hasMoves) {
				for (int k = 0; k < 4; k++) {
					mon.moves[k] = u16(trpokeEntry, p + k * 2);
				}
			}
			t.party.add(mon);
		}
		return t;
	}

	/** True when any mon carries a held item (drives trdata flags bit1). */
	public boolean anyItem() {
		for (PartyMon m : party) {
			if (m.heldItem != 0) {
				return true;
			}
		}
		return false;
	}

	/** True when any mon has explicit moves (drives trdata flags bit0). */
	public boolean anyMoves() {
		for (PartyMon m : party) {
			for (int mv : m.moves) {
				if (mv != 0) {
					return true;
				}
			}
		}
		return false;
	}

	/** The trdata bytes; flags/count derived from the party (never inconsistent). */
	public byte[] toTrdata() {
		if (party.isEmpty() || party.size() > 6) {
			throw new IllegalStateException("party must have 1..6 members");
		}
		byte[] out = new byte[24];
		int flags = formatFlags | (anyMoves() ? 1 : 0) | (anyItem() ? 2 : 0);
		pu16(out, 0, flags);
		pu16(out, 2, classId);
		out[6] = (byte) battleType;
		out[7] = (byte) party.size();
		for (int i = 0; i < 4; i++) {
			pu16(out, 8 + i * 2, aiItems[i]);
		}
		pu16(out, 0x10, (int) (aiFlags & 0xFFFF));
		pu16(out, 0x12, (int) ((aiFlags >>> 16) & 0xFFFF));
		out[0x15] = (byte) moneyRate;
		return out;
	}

	/** The trpoke bytes matching {@link #toTrdata}'s flags. */
	public byte[] toTrpoke() {
		int flags = formatFlags | (anyMoves() ? 1 : 0) | (anyItem() ? 2 : 0);
		boolean hasMoves = (flags & 1) != 0, hasItem = (flags & 2) != 0;
		int perMon = 8 + (hasItem ? 2 : 0) + (hasMoves ? 8 : 0);
		byte[] out = new byte[party.size() * perMon];
		for (int m = 0; m < party.size(); m++) {
			PartyMon mon = party.get(m);
			if (mon.species < 1 || mon.species > 0x7FF) {
				throw new IllegalStateException("mon " + (m + 1) + " has invalid species " + mon.species);
			}
			int o = m * perMon;
			out[o] = (byte) mon.ivByte;
			out[o + 1] = (byte) mon.genderAbility;
			pu16(out, o + 2, Math.max(1, Math.min(100, mon.level)));
			pu16(out, o + 4, mon.species);
			pu16(out, o + 6, mon.form);
			int p = o + 8;
			if (hasItem) {
				pu16(out, p, mon.heldItem);
				p += 2;
			}
			if (hasMoves) {
				for (int k = 0; k < 4; k++) {
					pu16(out, p + k * 2, mon.moves[k]);
				}
			}
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
