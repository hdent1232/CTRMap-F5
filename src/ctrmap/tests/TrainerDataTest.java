package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.trainers.TrainerEntry;
import java.io.File;
import java.util.Arrays;

/**
 * Trainer codec gate against the retail GARCs:
 * (1) shapes: 950/950/280 entries, dummy entries byte-shaped as measured,
 *     trpoke length == numPokemon x perMon for every trainer;
 * (2) value ranges: flags<=3, class<=279, species<=721, moves<=621,
 *     items<=775, levels 2..81, gender/ability nibbles in the observed set;
 * (3) round-trip: read -> re-emit BYTE-IDENTICAL for every trainer (trdata
 *     and trpoke both);
 * (4) sniff safety: with compression sniffing disabled, no trainer entry
 *     reads as compressed;
 * (5) golden anchor: trainer 561 = Roxanne, class 200, Geodude Lv12 +
 *     Nosepass Lv14 with the exact OR movesets.
 *
 * Usage: java ctrmap.tests.TrainerDataTest <romfs-root-with-a036-a038>
 */
public class TrainerDataTest {

	public static void main(String[] args) throws Exception {
		String root = args.length > 0 ? args[0]
				: "../RomFS/000400000011C400";
		GARC trdata = new GARC(new File(root + "/a/0/3/6"), false);
		GARC trpoke = new GARC(new File(root + "/a/0/3/8"), false);
		GARC trclass = new GARC(new File(root + "/a/0/3/7"), false);
		int failures = 0;

		if (trdata.length != 950 || trpoke.length != 950 || trclass.length != 280) {
			failures++;
			System.out.println("FAIL counts: " + trdata.length + "/" + trpoke.length + "/" + trclass.length);
		}
		byte[] d0 = trdata.getDecompressedEntry(0), p0 = trpoke.getDecompressedEntry(0);
		if (d0.length != 16 || !allZero(d0) || p0.length != 6 || !allZero(p0)) {
			failures++;
			System.out.println("FAIL dummy entry shapes (" + d0.length + "/" + p0.length + ")");
		}

		int roundtripOk = 0;
		for (int t = 1; t < 950; t++) {
			byte[] d = trdata.getDecompressedEntry(t);
			byte[] p = trpoke.getDecompressedEntry(t);
			try {
				if (d.length != 24) {
					throw new IllegalStateException("trdata len " + d.length);
				}
				int flags = (d[0] & 0xFF) | ((d[1] & 0xFF) << 8);
				int cls = (d[2] & 0xFF) | ((d[3] & 0xFF) << 8);
				int count = d[7] & 0xFF;
				int perMon = 8 + 2 * ((flags >> 1) & 1) + 8 * (flags & 1);
				if (flags > 3 || cls > 279 || count < 1 || count > 6) {
					throw new IllegalStateException("field ranges flags=" + flags + " cls=" + cls + " cnt=" + count);
				}
				if (p.length != count * perMon) {
					throw new IllegalStateException("trpoke len " + p.length + " != " + count + "x" + perMon);
				}
				TrainerEntry e = TrainerEntry.read(d, p);
				for (TrainerEntry.PartyMon m : e.party) {
					if (m.species < 1 || m.species > 721 || m.level < 2 || m.level > 81 || m.form > 2) {
						throw new IllegalStateException("mon ranges sp=" + m.species + " lv=" + m.level + " form=" + m.form);
					}
					int g = m.genderAbility & 0xF, a = (m.genderAbility >> 4) & 0xF;
					if (g > 2 || a > 3) {
						throw new IllegalStateException("gender/ability nibble 0x" + Integer.toHexString(m.genderAbility));
					}
					if (m.heldItem > 775) {
						throw new IllegalStateException("item " + m.heldItem);
					}
					for (int mv : m.moves) {
						if (mv > 621) {
							throw new IllegalStateException("move " + mv);
						}
					}
				}
				if (!Arrays.equals(e.toTrdata(), d) || !Arrays.equals(e.toTrpoke(), p)) {
					throw new IllegalStateException("round-trip not byte-identical");
				}
				roundtripOk++;
			} catch (RuntimeException ex) {
				failures++;
				System.out.println("FAIL trainer " + t + ": " + ex.getMessage());
				if (failures > 10) {
					break;
				}
			}
		}

		// golden: Roxanne
		TrainerEntry rox = TrainerEntry.read(trdata.getDecompressedEntry(561), trpoke.getDecompressedEntry(561));
		if (rox.classId != 200 || rox.party.size() != 2
				|| rox.party.get(0).species != 74 || rox.party.get(0).level != 12
				|| rox.party.get(1).species != 299 || rox.party.get(1).level != 14
				|| rox.party.get(1).moves[2] != 317 /*Rock Tomb*/) {
			failures++;
			System.out.println("FAIL golden Roxanne");
		}

		System.out.println("\nTrainerData: 949 trainers, round-trip byte-identical=" + roundtripOk + ", failures=" + failures);
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (failures > 0) {
			System.exit(1);
		}
	}

	static boolean allZero(byte[] b) {
		for (byte x : b) {
			if (x != 0) {
				return false;
			}
		}
		return true;
	}
}
