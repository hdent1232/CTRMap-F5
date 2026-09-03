package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.scripts.FacilityScriptWizard;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.GauntletScriptWizard;
import ctrmap.formats.scripts.NpcTemplates;
import ctrmap.formats.scripts.PawnInstruction;
import ctrmap.formats.scripts.TalkerScriptWizard;
import ctrmap.formats.scripts.ZoneScriptAnalyzer;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every dispatch case a wizard installs must be entered the way the engine's
 * calling convention requires - through the retail trampoline, never straight
 * into the subroutine's PROC.
 *
 * <p>THE DEFECT THIS GUARDS, which shipped and froze the game: every entry
 * point through {@link TalkerScriptWizard#installCase} pointed the new
 * SWITCH/CASETBL case pair DIRECTLY at the appended subroutine's PROC. Measured
 * against all 536 retail zone scripts, retail NEVER does that: all 2853 retail
 * case targets are a three-instruction trampoline that lives INSIDE the
 * dispatcher's own PROC frame -
 * <pre>
 *     PUSH_P_C(0)          the argument-byte cell the sub's terminal RETN consumes
 *     CALL &lt;sub&gt;           supplies the return address
 *     JUMP &lt;main epilogue&gt; the shared "ZERO_PRI; RETN" at the end of main
 * </pre>
 * 0 of 2853 retail case targets are a PROC; 228 of 228 generated ones were.
 *
 * <p>WHY IT FROZE: SWITCH/CASETBL is a JUMP, not a CALL. OP_PROC pushes FRM;
 * OP_RETN pops FRM, pops CIP, then advances STK by the argbytes cell. Entered
 * by a jump, the generated sub's PROC pushed FRM, the message wrapper CALL ran
 * and unwound cleanly - so the textbox DID appear - and then the sub's terminal
 * RETN popped the dispatcher's saved FRM, popped a data-segment offset into
 * CIP, and displaced STK by a code address. CIP wild, stack blown, the VM ran
 * data as instructions. The cost was a hard freeze of Pokemon ORAS the moment
 * the player dismissed the dialogue of any NPC the editor added.
 *
 * <p>Nothing caught it because the whole talker battery verified the shape the
 * emitter produced instead of the shape the engine requires. This suite checks
 * the case target against RETAIL's own trampolines, read out of the very same
 * zone script, for every {@code installCase} entry point: talker, sign, item
 * giver, give-BP and battle challenge.
 *
 * <p>Usage: java ctrmap.tests.DispatchTrampolineTest &lt;path-to-a013-garc&gt;
 */
public class DispatchTrampolineTest {

	private static final String DEFAULT_GARC_PATH = "../RomFS_original_garcs/a/0/1/3";
	private static final int ZONE_COUNT = 536;
	private static final int FAKE_LINE = 1234;
	/** Every 8th eligible zone gets the (slow, large) battle-challenge emit. */
	private static final int GAUNTLET_STRIDE = 8;

	private static final int OP_PROC = PawnInstruction.Commands.PROC.ordinal();
	private static final int OP_PUSH_P_C = PawnInstruction.Commands.PUSH_P_C.ordinal();
	private static final int OP_CALL = PawnInstruction.Commands.CALL.ordinal();
	private static final int OP_JUMP = PawnInstruction.Commands.JUMP.ordinal();
	private static final int OP_RET = PawnInstruction.Commands.RET.ordinal();
	private static final int OP_RETN = PawnInstruction.Commands.RETN.ordinal();
	private static final int OP_ZERO_PRI = PawnInstruction.Commands.ZERO_PRI.ordinal();

	static int fails = 0;

	/** One installCase entry point, applied to a freshly parsed zone script. */
	private interface Emitter {

		int emit(GFLPawnScript script);
	}

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0] : DEFAULT_GARC_PATH);
		if (!garcFile.exists()) {
			System.out.println("  skip: no pristine ZoneData GARC at " + garcFile.getAbsolutePath());
			System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
			return;
		}
		GARC garc = new GARC(garcFile);

		int[] counts = new int[5];   //zones exercised per template
		int[] bad = new int[5];      //zones whose new case was NOT retail-shaped
		String[] tags = {"talker", "sign", "item giver", "give BP", "battle challenge"};
		List<String> firstErrors = new ArrayList<>();

		for (int z = 0; z < ZONE_COUNT; z++) {
			byte[] raw = zoneScriptBytes(garc, z);
			if (raw == null) {
				continue;
			}
			GFLPawnScript probe;
			try {
				probe = parse(raw);
			} catch (Exception ex) {
				continue; //parse failures are another suite's business
			}
			if (ZoneScriptAnalyzer.findDispatch(probe) == null) {
				continue;
			}
			boolean hasMsg = ZoneScriptAnalyzer.findMsgWrapper(probe) != null;
			boolean hasSign = ZoneScriptAnalyzer.findSignWrapper(probe) != null;
			boolean hasGive = ZoneScriptAnalyzer.findGiveWrapper(probe) != null;

			if (hasMsg) {
				run(0, z, raw, counts, bad, firstErrors, tags,
						s -> TalkerScriptWizard.cloneTalker(s, FAKE_LINE));
			}
			if (hasSign) {
				run(1, z, raw, counts, bad, firstErrors, tags,
						s -> NpcTemplates.addSignScript(s, FAKE_LINE, NpcTemplates.SIGN_TYPES[0]));
			}
			if (hasGive) {
				run(2, z, raw, counts, bad, firstErrors, tags,
						s -> NpcTemplates.addItemGiverScript(s, 4, 1));
			}
			run(3, z, raw, counts, bad, firstErrors, tags,
					s -> FacilityScriptWizard.addGiveBpScript(s, 5));
			if (z % GAUNTLET_STRIDE == 0) {
				run(4, z, raw, counts, bad, firstErrors, tags, s -> {
					GauntletScriptWizard.Config cfg = new GauntletScriptWizard.Config();
					cfg.trainerIds = new int[]{51, 52, 53};
					cfg.bpPerWin = 3;
					return GauntletScriptWizard.addChallengeScript(s, cfg);
				});
			}
		}

		for (String e : firstErrors) {
			System.out.println("  detail: " + e);
		}
		for (int i = 0; i < counts.length; i++) {
			System.out.println("corpus: " + tags[i] + " emitted into " + counts[i]
					+ " zones, " + bad[i] + " NOT retail-shaped");
		}
		check(counts[0] >= 200, "talker corpus is the full eligible set (" + counts[0] + " zones, expected >= 200)");
		check(counts[1] > 0, "sign corpus is non-empty (" + counts[1] + " zones)");
		check(counts[2] > 0, "item-giver corpus is non-empty (" + counts[2] + " zones)");
		check(counts[3] >= 500, "give-BP corpus is the full dispatch set (" + counts[3] + " zones, expected >= 500)");
		check(counts[4] > 0, "battle-challenge corpus is non-empty (" + counts[4] + " zones)");
		for (int i = 0; i < counts.length; i++) {
			check(bad[i] == 0, "every " + tags[i] + " case target has the retail trampoline shape ("
					+ bad[i] + "/" + counts[i] + " wrong)");
		}

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * Emits one template into a fresh copy of the zone script, reserializes,
	 * re-parses the bytes the game would load and verifies the new case.
	 */
	private static void run(int slot, int zone, byte[] raw, int[] counts, int[] bad,
			List<String> firstErrors, String[] tags, Emitter emitter) {
		GFLPawnScript s;
		int newId;
		try {
			s = parse(raw);
			newId = emitter.emit(s);
		} catch (RuntimeException ex) {
			return; //the template refused this zone; availability is another suite's business
		} catch (Exception ex) {
			return;
		}
		counts[slot]++;
		String err;
		try {
			GFLPawnScript fresh = parse(s.getScriptBytes());
			err = verifyRetailShape(fresh, newId);
		} catch (Exception ex) {
			err = "EXCEPTION " + ex;
		}
		if (err != null) {
			bad[slot]++;
			if (firstErrors.size() < 12) {
				firstErrors.add(tags[slot] + " zone " + zone + " case " + newId + ": " + err);
			}
		}
	}

	/**
	 * The whole property, checked against the retail trampolines of the very
	 * same script: the new case target must be PUSH_P_C(0); CALL sub; JUMP
	 * epilogue, living inside main's PROC frame, with the sub reachable by a
	 * CALL and the JUMP landing on the epilogue retail's own cases jump to.
	 *
	 * @return null when the case is retail-shaped, else what is wrong with it
	 */
	private static String verifyRetailShape(GFLPawnScript fresh, int newId) {
		ZoneScriptAnalyzer.Dispatch d = ZoneScriptAnalyzer.findDispatch(fresh);
		if (d == null) {
			return "dispatch lost after the emit";
		}
		if (!d.cases.containsKey(newId)) {
			return "new case key missing from the CASETBL";
		}
		PawnInstruction target = d.cases.get(newId);
		if (target == null) {
			return "new case offset does not land on an instruction boundary";
		}

		//the retail epilogue, read out of this zone's PRE-EXISTING case
		//trampolines - never a hardcoded offset
		Set<Integer> retailEpilogues = new LinkedHashSet<>();
		List<String> retailShapes = new ArrayList<>();
		for (Map.Entry<Integer, PawnInstruction> e : d.cases.entrySet()) {
			if (e.getKey() == newId || e.getValue() == null) {
				continue;
			}
			retailShapes.add(shape(fresh, e.getValue()));
			PawnInstruction j = at(fresh, e.getValue(), 2);
			if (j != null && j.getCommand() == OP_JUMP && j.argumentCells.length == 1) {
				retailEpilogues.add(j.pointer + j.argumentCells[0]);
			}
		}
		if (retailEpilogues.size() != 1) {
			return "the zone's retail cases do not agree on one epilogue (" + retailEpilogues.size() + ")";
		}
		int epiPtr = retailEpilogues.iterator().next();
		PawnInstruction epi = fresh.lookupInstructionByPtr(epiPtr);
		PawnInstruction epiNext = epi == null ? null : at(fresh, epi, 1);
		if (epi == null || epi.getCommand() != OP_ZERO_PRI || epiNext == null || epiNext.getCommand() != OP_RETN) {
			return "the epilogue at 0x" + Integer.toHexString(epiPtr) + " is not \"ZERO_PRI; RETN\"";
		}

		//1. no case target anywhere may be a subroutine PROC - THE freeze
		for (Map.Entry<Integer, PawnInstruction> e : d.cases.entrySet()) {
			if (e.getValue() != null && e.getValue().getCommand() == OP_PROC) {
				return "case " + e.getKey() + " jumps straight into a PROC at 0x"
						+ Integer.toHexString(e.getValue().pointer)
						+ " - the engine would return through a frame that was never set up";
			}
		}

		//2. the new target's opcode sequence must equal retail's in this zone
		String mine = shape(fresh, target);
		if (!retailShapes.isEmpty() && !mine.equals(retailShapes.get(0))) {
			return "case target shape \"" + mine + "\" != retail \"" + retailShapes.get(0) + "\"";
		}

		//3. the trampoline, instruction by instruction
		if (target.getCommand() != OP_PUSH_P_C || target.argumentCells.length < 1 || target.argumentCells[0] != 0) {
			return "case target is not PUSH_P_C(0) - the sub's RETN has no argbytes cell to consume";
		}
		PawnInstruction call = at(fresh, target, 1);
		if (call == null || call.getCommand() != OP_CALL || call.argumentCells.length != 1) {
			return "the trampoline's 2nd instruction is not a CALL - nothing supplies a return address";
		}
		PawnInstruction sub = fresh.lookupInstructionByPtr(call.pointer + call.argumentCells[0]);
		if (sub == null || sub.getCommand() != OP_PROC) {
			return "the trampoline's CALL does not resolve to a subroutine PROC";
		}
		PawnInstruction jmp = at(fresh, target, 2);
		if (jmp == null || jmp.getCommand() != OP_JUMP || jmp.argumentCells.length != 1) {
			return "the trampoline's 3rd instruction is not a JUMP back to the dispatcher";
		}
		if (jmp.pointer + jmp.argumentCells[0] != epiPtr) {
			return "the trampoline's JUMP lands on 0x" + Integer.toHexString(jmp.pointer + jmp.argumentCells[0])
					+ ", not the dispatcher epilogue 0x" + Integer.toHexString(epiPtr);
		}

		//4. the trampoline must live inside main's own PROC frame
		PawnInstruction mainProc = fresh.lookupInstructionByPtr(fresh.mainEntryPoint);
		if (mainProc == null || mainProc.getCommand() != OP_PROC) {
			return "main's entry point no longer lands on a PROC";
		}
		int mi = fresh.instructions.indexOf(mainProc);
		int mainEnd = -1;
		for (int i = mi + 1; i < fresh.instructions.size(); i++) {
			int cmd = fresh.instructions.get(i).getCommand();
			if (cmd == OP_RET || cmd == OP_RETN) {
				mainEnd = i;
				break;
			}
		}
		int ti = fresh.instructions.indexOf(target);
		if (mainEnd < 0 || ti < mi || ti > mainEnd) {
			return "the trampoline at 0x" + Integer.toHexString(target.pointer)
					+ " is outside main's PROC frame";
		}

		//5. the appended sub must be reachable by a CALL (it was by none)
		int callers = 0;
		for (PawnInstruction ins : fresh.instructions) {
			if (ins.getCommand() == OP_CALL && ins.argumentCells.length == 1
					&& ins.pointer + ins.argumentCells[0] == sub.pointer) {
				callers++;
			}
		}
		if (callers == 0) {
			return "the appended sub at 0x" + Integer.toHexString(sub.pointer) + " is reachable by no CALL";
		}

		//6. the sub must still terminate in a RETN of its own
		boolean retn = false;
		int si = fresh.instructions.indexOf(sub);
		for (int i = si + 1; i < fresh.instructions.size(); i++) {
			int cmd = fresh.instructions.get(i).getCommand();
			if (cmd == OP_PROC) {
				break;
			}
			if (cmd == OP_RETN) {
				retn = true;
				break;
			}
		}
		if (!retn) {
			return "the appended sub does not terminate in a RETN";
		}
		return null;
	}

	/** The first three opcode names at an instruction, retail's case signature. */
	private static String shape(GFLPawnScript s, PawnInstruction from) {
		int idx = s.instructions.indexOf(from);
		StringBuilder sb = new StringBuilder();
		for (int k = idx; k >= 0 && k < Math.min(idx + 3, s.instructions.size()); k++) {
			if (k > idx) {
				sb.append(" ; ");
			}
			sb.append(PawnInstruction.Commands.values()[s.instructions.get(k).getCommand()].name());
		}
		return sb.toString();
	}

	/** The instruction {@code n} positions after {@code from}, or null. */
	private static PawnInstruction at(GFLPawnScript s, PawnInstruction from, int n) {
		int idx = s.instructions.indexOf(from);
		if (idx < 0 || idx + n >= s.instructions.size()) {
			return null;
		}
		return s.instructions.get(idx + n);
	}

	private static GFLPawnScript parse(byte[] raw) {
		GFLPawnScript s = new GFLPawnScript(raw);
		s.decompressThis();
		return s;
	}

	/**
	 * Subfile 2 (map script) of a ZoneData entry, or null when the entry is
	 * not a ZO container (magic 0x5A4F, big-endian on disk).
	 */
	private static byte[] zoneScriptBytes(GARC garc, int index) {
		byte[] zo = garc.getDecompressedEntry(index);
		if (zo == null || zo.length < 4) {
			return null;
		}
		int magic = ((zo[0] & 0xFF) << 8) | (zo[1] & 0xFF);
		if (magic != 0x5A4F) {
			return null;
		}
		int count = (zo[2] & 0xFF) | ((zo[3] & 0xFF) << 8);
		if (count < 3 || zo.length < 4 + (count + 1) * 4) {
			return null;
		}
		int start = readIntLE(zo, 4 + 2 * 4);
		int end = readIntLE(zo, 4 + 3 * 4);
		if (start < 0 || end > zo.length || end <= start) {
			return null;
		}
		byte[] scr = new byte[end - start];
		System.arraycopy(zo, start, scr, 0, scr.length);
		return scr;
	}

	private static int readIntLE(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}
