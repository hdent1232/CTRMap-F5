package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.scripts.FacilityScriptWizard;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.PawnInstruction;
import ctrmap.formats.scripts.ZoneScriptAnalyzer;
import ctrmap.scripts.GfHash;
import java.io.File;
import java.util.List;

/**
 * Dry-run gate for the GiveBP script emitter, over every ORAS zone script:
 * append a "give N BP" case, reserialize with getScriptBytes(), re-parse, and
 * assert (a) the script still parses and its dispatch still resolves, (b) the
 * new case id appears in the dispatch, (c) its body contains SYSREQ_N calls to
 * PlayerGetBP and PlayerSetBP with the amount constant, (d) every pre-existing
 * case still resolves to an instruction boundary (no corruption). Structural
 * only - in-game behavior is the user's gate.
 *
 * Usage: java ctrmap.tests.GiveBpScriptTest <path-to-zonedata-a013-garc>
 */
public class GiveBpScriptTest {

	public static void main(String[] args) throws Exception {
		File garc = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/1/3");
		GARC zo = new GARC(garc);
		int zones = zo.length - 2;
		int tested = 0, ok = 0, noDispatch = 0, failures = 0;
		int getBPh = GfHash.hash("PlayerGetBP"), setBPh = GfHash.hash("PlayerSetBP");
		final int AMOUNT = 20;

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
			if (ZoneScriptAnalyzer.findDispatch(s) == null) {
				noDispatch++;
				continue;
			}
			tested++;
			try {
				List<Integer> before = ZoneScriptAnalyzer.listScriptIds(s);
				int newId = FacilityScriptWizard.addGiveBpScript(s, AMOUNT);
				byte[] out = s.getScriptBytes();

				// re-parse from bytes and verify structure
				GFLPawnScript re = new GFLPawnScript(out);
				re.decompressThis();
				PawnInstruction.nativeResolver = re;
				ZoneScriptAnalyzer.Dispatch d = ZoneScriptAnalyzer.findDispatch(re);
				if (d == null) {
					throw new IllegalStateException("dispatch lost after emit");
				}
				List<Integer> after = ZoneScriptAnalyzer.listScriptIds(re);
				if (!after.contains(newId) || after.size() != before.size() + 1) {
					throw new IllegalStateException("new case id " + newId + " not registered");
				}
				for (java.util.Map.Entry<Integer, PawnInstruction> c : d.cases.entrySet()) {
					if (c.getValue() == null) {
						throw new IllegalStateException("case " + c.getKey() + " no longer resolves");
					}
				}
				// walk the new case body: must call GetBP then SetBP, and hold AMOUNT
				PawnInstruction start = d.cases.get(newId);
				int gi = idxOf(re.instructions, start);
				boolean sawGet = false, sawSet = false, sawAmt = false, sawRetn = false;
				for (int i = gi; i < re.instructions.size() && i < gi + 12; i++) {
					PawnInstruction ins = re.instructions.get(i);
					if (ins.getCommand() == 0x87) {
						int h = re.natives.get(ins.argumentCells[0]).data[1];
						if (h == getBPh) {
							sawGet = true;
						}
						if (h == setBPh) {
							sawSet = true;
						}
					}
					if (ins.getCommand() == 0x0C /*CONST_ALT*/ && ins.argumentCells[0] == AMOUNT) {
						sawAmt = true;
					}
					if (ins.getCommand() == 0x30 /*RETN*/) {
						sawRetn = true;
						break;
					}
				}
				if (!(sawGet && sawSet && sawAmt && sawRetn)) {
					throw new IllegalStateException("body incomplete get=" + sawGet + " set=" + sawSet + " amt=" + sawAmt + " retn=" + sawRetn);
				}
				ok++;
			} catch (RuntimeException ex) {
				failures++;
				System.out.println("FAIL zone " + z + ": " + ex.getMessage());
				if (failures > 8) {
					break;
				}
			}
		}
		PawnInstruction.nativeResolver = null;
		System.out.println("\nGiveBP emit: tested=" + tested + " (noDispatch " + noDispatch + ")  ok=" + ok + "  failures=" + failures);
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (failures > 0) {
			System.exit(1);
		}
	}

	static int idxOf(List<PawnInstruction> list, PawnInstruction target) {
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i) == target || list.get(i).pointer == target.pointer) {
				return i;
			}
		}
		return -1;
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
