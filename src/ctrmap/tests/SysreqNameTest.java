package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.PawnAssembly;
import ctrmap.formats.scripts.PawnInstruction;
import ctrmap.scripts.GfHash;
import java.io.File;
import static ctrmap.formats.containers.ContainerBytes.subfile;

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
			byte[] sub = subfile(zo.getDecompressedEntry(z), 2);
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
}
