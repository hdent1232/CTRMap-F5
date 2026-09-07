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
 * and disassemble -> fromString round-trips the native INDEX exactly (the name
 * resolves back through the table of the script the assembly was given). Also
 * reports how many distinct natives resolved to names, as a coverage signal.
 *
 * <p>And it pins WHERE that table comes from: two scripts assembled in the same
 * JVM must each resolve a name in their own table, with no static for one to
 * leak into the other - see {@link #twoScriptsResolveTheSameNameInTheirOwnTable}.
 *
 * Usage: java ctrmap.tests.SysreqNameTest <path-to-zonedata-a013-garc>
 */
public class SysreqNameTest {

	static int fails = 0;

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}

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
				PawnAssembly one = new PawnAssembly(disasm, s);
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
		System.out.println("\nscripts=" + scripts + "  SYSREQ_N calls=" + sysreqs
				+ "  named=" + named + " (" + distinctNamed.size() + " distinct natives)"
				+ "  unresolved=" + unresolved + " (" + distinctUnknown.size() + " distinct)"
				+ "  round-trip failures=" + rtFail);
		twoScriptsResolveTheSameNameInTheirOwnTable(zo);
		int failures = rtFail + fails;
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT (" + failures + ")");
		if (failures > 0) {
			System.exit(1);
		}
	}

	/**
	 * Two scripts, two natives tables, one name - each assembly resolves it in
	 * its own table, and nothing static sits between them.
	 *
	 * <p>The table a name resolves against used to be
	 * {@code PawnInstruction.nativeResolver}, a static the editor set on load
	 * and this suite (and four others) set and nulled again in a finally. Two
	 * scripts open in the same JVM - the editor's, and a wizard's - resolved
	 * the same name to whichever script was set LAST, which is the wrong index
	 * for the other. The table now rides on the {@link PawnAssembly}, so the
	 * only way to resolve against the wrong script is to be handed it.
	 *
	 * <p>Finds a native that two retail scripts register at different indices,
	 * builds BOTH assemblies before either parses a line, and checks each gives
	 * its own index. A static, however it were set, would give one of them the
	 * other's.
	 */
	static void twoScriptsResolveTheSameNameInTheirOwnTable(GARC zo) {
		GFLPawnScript a = null, b = null;
		int hash = 0, idxA = -1, idxB = -1;
		//hash -> (the first script registering it, and at which index)
		java.util.Map<Integer, Object[]> seen = new java.util.HashMap<>();
		for (int z = 0; z < zo.length - 2 && b == null; z++) {
			byte[] raw = subfile(zo.getDecompressedEntry(z), 2);
			if (raw == null || raw.length < 8) {
				continue;
			}
			GFLPawnScript s;
			try {
				s = new GFLPawnScript(raw);
				s.decompressThis();
			} catch (Exception ex) {
				continue;
			}
			for (int i = 0; i < s.natives.size() && b == null; i++) {
				int h = s.natives.get(i).data[1];
				if (GfHash.nameForHash(h) == null) {
					continue; //only a name can be written by name
				}
				Object[] first = seen.get(h);
				if (first == null) {
					seen.put(h, new Object[]{s, i});
				} else if ((Integer) first[1] != i) {
					a = (GFLPawnScript) first[0];
					idxA = (Integer) first[1];
					b = s;
					idxB = i;
					hash = h;
				}
			}
		}
		String name = GfHash.nameForHash(hash);
		check(b != null, "a native two retail scripts register at different indices: " + name
				+ " at " + idxA + " and " + idxB);
		if (b == null) {
			return;
		}
		String line = "SYSREQ_N(" + name + ", 0)";
		//both assemblies exist before either resolves anything
		PawnAssembly forA = new PawnAssembly(line, a);
		PawnAssembly forB = new PawnAssembly(line, b);
		PawnInstruction inA = PawnInstruction.fromString(0, line, forA);
		PawnInstruction inB = PawnInstruction.fromString(0, line, forB);
		check(forA.errors.isEmpty() && inA.argumentCells[0] == idxA,
				line + " assembled for the first script is index " + idxA + " (got "
				+ inA.argumentCells[0] + ", errors " + forA.errors + ")");
		check(forB.errors.isEmpty() && inB.argumentCells[0] == idxB,
				"and for the second script is index " + idxB + " (got "
				+ inB.argumentCells[0] + ", errors " + forB.errors + ")");
		//and an assembly given no script accepts indices only
		PawnAssembly none = new PawnAssembly(line);
		PawnInstruction inNone = PawnInstruction.fromString(0, line, none);
		check(none.errors.size() == 1 && none.errors.get(0).contains("unknown native " + name),
				"an assembly given no script refuses the name out loud: " + none.errors);
		check(inNone.argumentCells[0] == 0, "and leaves the index unset rather than guessing one");
	}

}
