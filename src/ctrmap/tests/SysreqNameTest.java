package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.PawnAssembly;
import ctrmap.formats.scripts.PawnInstruction;
import ctrmap.scripts.GfHash;
import java.io.File;

/**
 * Verifies SYSREQ-by-name in the disassembler: across every ORAS zone script,
 * each SYSREQ_N renders with its resolved native name when the hash is known,
 * and disassemble -> fromString round-trips the native INDEX exactly (name form
 * resolves back through the nativeResolver context). Also reports how many
 * distinct natives resolved to names, as a coverage signal.
 *
 * Usage: java ctrmap.tests.SysreqNameTest <path-to-zonedata-a013-garc>
 */
public class SysreqNameTest {

	public static void main(String[] args) throws Exception {
		File garc = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/1/3");
		GARC zo = new GARC(garc);
		int zones = zo.length - 2;
		int scripts = 0, sysreqs = 0, named = 0, rtFail = 0, unresolved = 0;
		java.util.Set<Integer> distinctNamed = new java.util.HashSet<>();
		java.util.Set<Integer> distinctUnknown = new java.util.HashSet<>();

		for (int z = 0; z < zones; z++) {
			byte[] sub = sub(zo.getDecompressedEntry(z), 2);
			if (sub == null || sub.length < 8) {
				continue;
			}
			GFLPawnScript s;
			try {
				s = new GFLPawnScript(sub);
				s.decompressThis();
			} catch (Exception ex) {
				continue;
			}
			scripts++;
			PawnInstruction.nativeResolver = s;
			for (PawnInstruction ins : s.instructions) {
				if (ins.getCommand() != 0x87) {
					continue;
				}
				sysreqs++;
				int idx = ins.argumentCells[0];
				int hash = (idx >= 0 && idx < s.natives.size()) ? s.natives.get(idx).data[1] : 0;
				String name = GfHash.nameForHash(hash);
				String disasm = PawnInstruction.getDisassembly(ins);
				if (name != null) {
					named++;
					distinctNamed.add(hash);
					if (!disasm.contains(name)) {
						rtFail++;
						System.out.println("FAIL zone " + z + ": disasm missing name " + name + " -> " + disasm);
						continue;
					}
				} else {
					unresolved++;
					distinctUnknown.add(hash);
				}
				// round-trip: parse the disassembly back, index must match
				PawnAssembly one = new PawnAssembly(disasm);
				PawnInstruction back = PawnInstruction.fromString(ins.pointer, disasm, one);
				if (!one.errors.isEmpty() || back.getCommand() != 0x87 || back.argumentCells[0] != idx
						|| back.argumentCells[1] != ins.argumentCells[1]) {
					rtFail++;
					System.out.println("FAIL zone " + z + ": SYSREQ round-trip idx " + idx
							+ " -> " + back.argumentCells[0] + " (disasm: " + disasm + ")");
					if (rtFail > 8) {
						break;
					}
				}
			}
			if (rtFail > 8) {
				break;
			}
		}
		PawnInstruction.nativeResolver = null;

		System.out.println("\nscripts=" + scripts + "  SYSREQ_N calls=" + sysreqs
				+ "  named=" + named + " (" + distinctNamed.size() + " distinct natives)"
				+ "  unresolved=" + unresolved + " (" + distinctUnknown.size() + " distinct)"
				+ "  round-trip failures=" + rtFail);
		System.out.println(rtFail == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (rtFail > 0) {
			System.exit(1);
		}
	}

	static byte[] sub(byte[] c, int i) {
		if (c == null || c.length < 8) {
			return null;
		}
		int count = (c[2] & 0xFF) | ((c[3] & 0xFF) << 8);
		if (i >= count) {
			return null;
		}
		int o0 = le32(c, 4 + i * 4), o1 = le32(c, 4 + (i + 1) * 4);
		if (o0 < 0 || o1 > c.length || o1 < o0) {
			return null;
		}
		return java.util.Arrays.copyOfRange(c, o0, o1);
	}

	static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
