package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.PawnInstruction;
import ctrmap.formats.scripts.PawnPrefixEntry;
import ctrmap.formats.scripts.TalkerScriptWizard;
import ctrmap.formats.scripts.ZoneScriptAnalyzer;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The guards around the dispatch-case freeze must actually refuse - each one
 * of them, out loud, on the input it names.
 *
 * <p>THE DEFECT, which shipped and froze Pokemon ORAS: every case
 * {@link TalkerScriptWizard#installCase} installed pointed the new
 * SWITCH/CASETBL pair DIRECTLY at the appended subroutine's PROC. SWITCH is a
 * JUMP, not a CALL, so the sub was entered with no return address and no
 * argument-byte cell; its terminal RETN then popped the dispatcher's saved FRM
 * and a data-segment offset into CIP and the VM ran data as instructions. The
 * textbox appeared normally and the game hard-froze the instant the player
 * dismissed it. Measured against the retail corpus, 0 of the 2853 case targets
 * in the 536 retail zone scripts are a PROC; all 2853 are the three-instruction
 * trampoline {@code PUSH_P_C(0); CALL <sub>; JUMP <epilogue>} inside the
 * dispatcher's own PROC frame.
 *
 * <p>WHY THIS SUITE EXISTS. The fix added pre-conditions on the input script
 * and post-conditions on the emission, and changed the analyzer to REFUSE the
 * malformed shape instead of quietly resolving it - and a mutation sweep found
 * that not one of those eight lines was asserted by anything. That is the exact
 * hole that let the freeze survive a green battery for months: the old suites
 * verified the shape the emitter PRODUCED instead of the shape the engine
 * REQUIRES. A guard nothing asserts is one careless edit from being no guard,
 * so each one here is exercised on an input built to trip it:
 * <ul>
 * <li>{@link ZoneScriptAnalyzer#describeCaseDefect} must REPORT a case that
 *     points straight at a bare PROC - the pre-fix malformed shape, rebuilt
 *     here out of retail's own trampolines - and must not report retail's;</li>
 * <li>{@code describeCaseDefect} must stay silent about a case the zone does
 *     not define, which is not its business;</li>
 * <li>{@code installCase} must refuse a dispatch with no shared
 *     "ZERO_PRI; RETN" epilogue for the trampoline to return through;</li>
 * <li>{@code installCase} must refuse a CASETBL that is not in the script's
 *     instruction list, rather than splicing at index -1;</li>
 * <li>the publics fixup must leave an address below the CASETBL that no
 *     instruction owns exactly where it was;</li>
 * <li>the emitter's own post-condition must fire on a perturbed emission -
 *     case not at its trampoline, trampoline not calling the sub, trampoline
 *     not returning to the epilogue.</li>
 * </ul>
 *
 * <p>{@code verifyCaseInstalled} is reached by reflection ON PURPOSE. Widening
 * it would edit TalkerScriptWizard.java, and that file is byte-for-byte the one
 * the mutation sweep measured and the one whose emission is waiting on an
 * in-game check; a test must not be the reason either record moves. A rename
 * fails this suite loudly rather than silently skipping it.
 *
 * <p>Usage: java ctrmap.tests.DispatchGuardsTest &lt;path-to-a013-garc&gt;
 */
public class DispatchGuardsTest {

	private static final String DEFAULT_GARC_PATH = "../RomFS_original_garcs/a/0/1/3";
	private static final int ZONE_COUNT = 536;
	private static final int FAKE_LINE = 1234;
	/** A case key no retail zone defines (user ids stop well below this). */
	private static final int UNDEFINED_CASE_KEY = 31337;

	private static final int OP_PROC = PawnInstruction.Commands.PROC.ordinal();
	private static final int OP_CALL = PawnInstruction.Commands.CALL.ordinal();
	private static final int OP_RET = PawnInstruction.Commands.RET.ordinal();
	private static final int OP_RETN = PawnInstruction.Commands.RETN.ordinal();
	private static final int OP_ZERO_PRI = PawnInstruction.Commands.ZERO_PRI.ordinal();
	private static final int OP_NOP = PawnInstruction.Commands.NOP.ordinal();

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0] : DEFAULT_GARC_PATH);
		if (!garcFile.exists()) {
			System.out.println("  skip: no pristine ZoneData GARC at " + garcFile.getAbsolutePath());
			report();
			return;
		}
		GARC garc = new GARC(garcFile);

		byte[] ref = null;
		int refZone = -1;
		for (int z = 0; z < ZONE_COUNT && ref == null; z++) {
			byte[] raw = zoneScriptBytes(garc, z);
			if (raw == null) {
				continue;
			}
			GFLPawnScript s;
			try {
				s = parse(raw);
			} catch (Exception ex) {
				continue; //parse failures are another suite's business
			}
			if (ZoneScriptAnalyzer.findDispatch(s) != null
					&& ZoneScriptAnalyzer.findMsgWrapper(s) != null
					&& ZoneScriptAnalyzer.findDispatchEpilogue(s) != null) {
				ref = raw;
				refZone = z;
			}
		}
		check(ref != null, "a retail zone with a dispatch, a message wrapper and an epilogue to work on");
		if (ref == null) {
			report();
			return;
		}
		System.out.println("  using retail zone " + refZone + " as the reference script");

		analyzerReportsTheBarePROCCase(ref);
		analyzerCorpusSweep(garc);
		refusesDispatchWithoutEpilogue(ref);
		refusesDetachedCaseTable(ref);
		keepsUnownedPublicAddressBelowTheTableInPlace(ref);
		postConditionFiresOnAPerturbedEmission(ref);

		report();
	}

	/**
	 * THE check: hand the analyzer the pre-fix malformed shape and require it
	 * to say so. Retail's own case is repointed from its trampoline straight at
	 * the subroutine PROC the trampoline CALLs - byte-for-byte what CTRMap used
	 * to emit - and the analyzer must both report the freeze and refuse to
	 * resolve any subroutine or talker through it.
	 */
	private static void analyzerReportsTheBarePROCCase(byte[] ref) throws Exception {
		GFLPawnScript s = parse(ref);
		ZoneScriptAnalyzer.Dispatch d = ZoneScriptAnalyzer.findDispatch(s);
		int key = Integer.MIN_VALUE;
		PawnInstruction proc = null;
		for (Map.Entry<Integer, PawnInstruction> e : d.cases.entrySet()) {
			if (e.getValue() == null) {
				continue;
			}
			PawnInstruction p = trampolineSub(s, e.getValue());
			//prefer a real NPC script id over the -1 init case: an added
			//talker's case is what froze the game
			if (p != null && (proc == null || key == -1)) {
				key = e.getKey();
				proc = p;
			}
			if (proc != null && key != -1) {
				break;
			}
		}
		check(proc != null, "the reference zone has a retail trampoline case to malform");
		if (proc == null) {
			return;
		}
		check(ZoneScriptAnalyzer.describeCaseDefect(s, key) == null,
				"retail case " + key + " is reported as defect-free");
		check(!d.cases.containsKey(UNDEFINED_CASE_KEY),
				"case key " + UNDEFINED_CASE_KEY + " really is undefined in this zone");
		check(ZoneScriptAnalyzer.describeCaseDefect(s, UNDEFINED_CASE_KEY) == null,
				"a case the zone does not define is not describeCaseDefect's business");

		//the pre-fix malformed shape, rebuilt out of this zone's own retail parts
		check(pointCaseAt(s, d.caseTbl, key, proc), "case " + key + " repointed straight at the sub PROC at 0x"
				+ Integer.toHexString(proc.pointer));
		ZoneScriptAnalyzer.Dispatch after = ZoneScriptAnalyzer.findDispatch(s);
		check(after != null && after.cases.get(key) == proc,
				"the malformed case now targets the bare PROC (the shape that froze the game)");

		String defect = ZoneScriptAnalyzer.describeCaseDefect(s, key);
		check(defect != null, "the bare-PROC case IS reported as a defect");
		check(defect != null && defect.contains("jumps straight into a subroutine"),
				"the report names the defect, got: " + defect);
		check(defect != null && defect.contains("freezes the game"),
				"the report names the cost, got: " + defect);
		check(ZoneScriptAnalyzer.findCaseSubEntry(s, key) == null,
				"no subroutine is resolved through a bare-PROC case");
		check(ZoneScriptAnalyzer.findTalkerPattern(s, key) == null,
				"no talker pattern is matched through a bare-PROC case");
	}

	/**
	 * The same property at corpus scale: every retail case is clean, and every
	 * case the sweep malforms is reported.
	 */
	private static void analyzerCorpusSweep(GARC garc) {
		int zonesWithDispatch = 0, retailCases = 0, retailNullTargets = 0, retailProcDefects = 0;
		int malformedZones = 0, notReported = 0, stillResolved = 0;
		List<String> detail = new ArrayList<>();
		for (int z = 0; z < ZONE_COUNT; z++) {
			byte[] raw = zoneScriptBytes(garc, z);
			if (raw == null) {
				continue;
			}
			GFLPawnScript s;
			try {
				s = parse(raw);
			} catch (Exception ex) {
				continue;
			}
			ZoneScriptAnalyzer.Dispatch d = ZoneScriptAnalyzer.findDispatch(s);
			if (d == null) {
				continue;
			}
			zonesWithDispatch++;
			int victimKey = Integer.MIN_VALUE;
			PawnInstruction victimProc = null;
			for (Map.Entry<Integer, PawnInstruction> e : d.cases.entrySet()) {
				retailCases++;
				if (e.getValue() == null) {
					retailNullTargets++;
					continue;
				}
				String defect = ZoneScriptAnalyzer.describeCaseDefect(s, e.getKey());
				if (defect != null && defect.contains("jumps straight into")) {
					retailProcDefects++;
					if (detail.size() < 8) {
						detail.add("retail zone " + z + " case " + e.getKey() + ": " + defect);
					}
				}
				if (victimProc == null || victimKey == -1) {
					PawnInstruction p = trampolineSub(s, e.getValue());
					if (p != null && (victimProc == null || e.getKey() != -1)) {
						victimKey = e.getKey();
						victimProc = p;
					}
				}
			}
			if (victimProc == null) {
				continue; //no trampoline case here to malform
			}
			if (!pointCaseAt(s, d.caseTbl, victimKey, victimProc)) {
				continue;
			}
			ZoneScriptAnalyzer.Dispatch after = ZoneScriptAnalyzer.findDispatch(s);
			if (after == null || after.cases.get(victimKey) != victimProc) {
				continue; //could not build the malformed shape in this zone
			}
			malformedZones++;
			String defect = ZoneScriptAnalyzer.describeCaseDefect(s, victimKey);
			if (defect == null || !defect.contains("jumps straight into")) {
				notReported++;
				if (detail.size() < 8) {
					detail.add("zone " + z + " case " + victimKey + ": bare PROC NOT reported (" + defect + ")");
				}
			}
			if (ZoneScriptAnalyzer.findCaseSubEntry(s, victimKey) != null
					|| ZoneScriptAnalyzer.findTalkerPattern(s, victimKey) != null) {
				stillResolved++;
				if (detail.size() < 8) {
					detail.add("zone " + z + " case " + victimKey + ": still resolved through a bare PROC");
				}
			}
		}
		for (String s : detail) {
			System.out.println("  detail: " + s);
		}
		System.out.println("corpus: " + retailCases + " retail dispatch cases in " + zonesWithDispatch
				+ " zones (" + retailNullTargets + " off an instruction boundary)");
		System.out.println("corpus: the bare-PROC shape was built in " + malformedZones + " zones");
		check(retailCases >= 2000, "the retail case corpus is the full set (" + retailCases + ", expected >= 2000)");
		check(retailProcDefects == 0, "no retail dispatch case is reported as the freeze ("
				+ retailProcDefects + " were)");
		check(malformedZones >= 200, "the malformed shape was built at scale (" + malformedZones
				+ " zones, expected >= 200)");
		check(notReported == 0, "every case pointed straight at a PROC is reported ("
				+ notReported + "/" + malformedZones + " missed)");
		check(stillResolved == 0, "no subroutine or talker resolves through a bare-PROC case ("
				+ stillResolved + "/" + malformedZones + " still did)");
	}

	/**
	 * A dispatcher with no shared "ZERO_PRI; RETN" gives the trampoline nothing
	 * to JUMP back to, so the emit must be refused BEFORE any surgery. Damage:
	 * main's epilogue ZERO_PRI is turned into a NOP, leaving the dispatch itself
	 * intact.
	 */
	private static void refusesDispatchWithoutEpilogue(byte[] ref) throws Exception {
		GFLPawnScript s = parse(ref);
		PawnInstruction entry = s.lookupInstructionByPtr(s.mainEntryPoint);
		int entryIdx = s.instructions.indexOf(entry);
		int retIdx = -1;
		for (int i = entryIdx + 1; i < s.instructions.size(); i++) {
			int cmd = s.instructions.get(i).getCommand();
			if (cmd == OP_RET || cmd == OP_RETN) {
				retIdx = i;
				break;
			}
		}
		check(retIdx > entryIdx + 1, "main has a terminating RET/RETN to damage in front of");
		if (retIdx <= entryIdx + 1) {
			return;
		}
		PawnInstruction zero = s.instructions.get(retIdx - 1);
		check(zero.getCommand() == OP_ZERO_PRI, "the instruction before main's RETN is the epilogue ZERO_PRI");
		zero.cellValue = OP_NOP; //same cell width, no pointer moves
		check(ZoneScriptAnalyzer.findDispatchEpilogue(s) == null, "the damaged script has no dispatch epilogue");
		check(ZoneScriptAnalyzer.findDispatch(s) != null, "the damaged script still HAS a dispatch (so the "
				+ "refusal under test is the epilogue one)");

		String msg = refusalMessage(s);
		check(msg != null, "installCase refuses a dispatch with no shared epilogue");
		check(msg != null && msg.contains("does not end in the shared"),
				"the refusal names the missing epilogue, got: " + msg);
	}

	/**
	 * A CASETBL the instruction list does not contain would be spliced at index
	 * -1. The parser cannot produce that script, so the condition is reached
	 * through a list that hides the table from indexOf and nothing else - the
	 * guard's own words, made true.
	 */
	private static void refusesDetachedCaseTable(byte[] ref) throws Exception {
		GFLPawnScript s = parse(ref);
		ZoneScriptAnalyzer.Dispatch d = ZoneScriptAnalyzer.findDispatch(s);
		check(d != null, "the reference script has a dispatch to detach");
		if (d == null) {
			return;
		}
		s.instructions = new CaseTblDetachedList(s.instructions, d.caseTbl);
		ZoneScriptAnalyzer.Dispatch d2 = ZoneScriptAnalyzer.findDispatch(s);
		check(d2 != null && d2.caseTbl == d.caseTbl, "the detached table is still found by pointer");
		check(s.instructions.indexOf(d.caseTbl) < 0, "the detached table is no longer in the instruction list");

		String msg = refusalMessage(s);
		check(msg != null, "installCase refuses a CASETBL that is not in the instruction list");
		check(msg != null && msg.contains("not part of the script's instruction list"),
				"the refusal names the detached table, got: " + msg);
	}

	/**
	 * The publics fixup: an address no instruction owns gets the raw byte shift
	 * only if it sits at or after the insertion point. One below the old
	 * CASETBL must not move at all - shifting it would silently repoint a
	 * public into the middle of somebody else's instruction.
	 */
	private static void keepsUnownedPublicAddressBelowTheTableInPlace(byte[] ref) throws Exception {
		GFLPawnScript s = parse(ref);
		ZoneScriptAnalyzer.Dispatch d = ZoneScriptAnalyzer.findDispatch(s);
		int tblPtr = d.caseTbl.pointer;
		//instruction pointers are cell-aligned, so +2 is owned by nothing
		int below = s.instructions.get(0).pointer + 2;
		int above = tblPtr + 2;
		check(below < tblPtr, "the low probe address is below the CASETBL");
		check(s.lookupInstructionByPtr(below) == null, "the low probe address is owned by no instruction");
		check(s.lookupInstructionByPtr(above) == null, "the high probe address is owned by no instruction");
		s.publics.add(new PawnPrefixEntry(s.defsize, PawnPrefixEntry.Type.PUBLIC, new int[]{below, 0}));
		s.publics.add(new PawnPrefixEntry(s.defsize, PawnPrefixEntry.Type.PUBLIC, new int[]{above, 0}));
		int belowIdx = s.publics.size() - 2;
		int aboveIdx = s.publics.size() - 1;

		int newId = TalkerScriptWizard.cloneTalker(s, FAKE_LINE);
		ZoneScriptAnalyzer.Dispatch post = ZoneScriptAnalyzer.findDispatch(s);
		PawnInstruction tramp = post == null ? null : post.cases.get(newId);
		check(tramp != null, "the emit installed case " + newId);
		if (tramp == null) {
			return;
		}
		int ti = s.instructions.indexOf(tramp);
		int trampolineBytes = insBytes(s.instructions.get(ti))
				+ insBytes(s.instructions.get(ti + 1))
				+ insBytes(s.instructions.get(ti + 2));
		check(trampolineBytes > 0, "the trampoline occupies " + trampolineBytes + " bytes");

		check(s.publics.get(belowIdx).data[0] == below,
				"a public address below the CASETBL that no instruction owns does not move (0x"
				+ Integer.toHexString(below) + " -> 0x" + Integer.toHexString(s.publics.get(belowIdx).data[0]) + ")");
		check(s.publics.get(aboveIdx).data[0] == above + trampolineBytes + 8,
				"a public address above the CASETBL that no instruction owns moves by the trampoline and the "
				+ "new case pair (expected 0x" + Integer.toHexString(above + trampolineBytes + 8) + ", got 0x"
				+ Integer.toHexString(s.publics.get(aboveIdx).data[0]) + ")");
	}

	/**
	 * The emitter's post-condition, handed a real emission with one operand
	 * perturbed. Unreachable through the normal path - that is what a
	 * post-condition is for - so the emission is broken by hand afterwards, one
	 * way at a time, and each break must be refused by name.
	 */
	private static void postConditionFiresOnAPerturbedEmission(byte[] ref) throws Exception {
		Method verify;
		try {
			verify = TalkerScriptWizard.class.getDeclaredMethod("verifyCaseInstalled",
					GFLPawnScript.class, int.class, PawnInstruction.class, PawnInstruction.class,
					PawnInstruction.class, PawnInstruction.class, PawnInstruction.class);
		} catch (NoSuchMethodException ex) {
			check(false, "TalkerScriptWizard still has the verifyCaseInstalled post-condition "
					+ "(signature changed: re-point this suite at it)");
			return;
		}
		verify.setAccessible(true);

		GFLPawnScript s = parse(ref);
		int newId = TalkerScriptWizard.cloneTalker(s, FAKE_LINE);
		ZoneScriptAnalyzer.Dispatch d = ZoneScriptAnalyzer.findDispatch(s);
		PawnInstruction trampPush = d == null ? null : d.cases.get(newId);
		check(trampPush != null, "the emitted case " + newId + " resolves to its trampoline");
		if (trampPush == null) {
			return;
		}
		int ti = s.instructions.indexOf(trampPush);
		PawnInstruction trampCall = s.instructions.get(ti + 1);
		PawnInstruction trampJump = s.instructions.get(ti + 2);
		PawnInstruction proc = s.lookupInstructionByPtr(trampCall.pointer + trampCall.argumentCells[0]);
		PawnInstruction epilogue = ZoneScriptAnalyzer.findDispatchEpilogue(s);
		check(proc != null && proc.getCommand() == OP_PROC, "the trampoline's CALL lands on the appended PROC");
		check(epilogue != null, "the emission still has a dispatch epilogue");
		if (proc == null || epilogue == null) {
			return;
		}

		check(verifyMessage(verify, s, newId, trampPush, trampCall, trampJump, proc, epilogue) == null,
				"the post-condition passes on an untouched emission");

		//1. the case pair no longer lands on the trampoline
		PawnInstruction ct = d.caseTbl;
		int ai = caseArgIndex(ct, newId);
		check(ai > 0, "the new case pair is in the CASETBL");
		if (ai > 0) {
			int saved = ct.argumentCells[ai + 1];
			ct.argumentCells[ai + 1] = saved + 4;
			String msg = verifyMessage(verify, s, newId, trampPush, trampCall, trampJump, proc, epilogue);
			check(msg != null && msg.contains("does not point at its trampoline"),
					"a case pair moved off its trampoline is refused, got: " + msg);
			ct.argumentCells[ai + 1] = saved;
		}

		//2. the trampoline's CALL no longer enters the appended sub
		int savedCall = trampCall.argumentCells[0];
		trampCall.argumentCells[0] = savedCall + 4;
		String callMsg = verifyMessage(verify, s, newId, trampPush, trampCall, trampJump, proc, epilogue);
		check(callMsg != null && callMsg.contains("does not call the new subroutine"),
				"a trampoline CALL moved off the subroutine is refused, got: " + callMsg);
		trampCall.argumentCells[0] = savedCall;

		//3. the trampoline's JUMP no longer returns to the dispatcher
		int savedJump = trampJump.argumentCells[0];
		trampJump.argumentCells[0] = savedJump + 4;
		String jumpMsg = verifyMessage(verify, s, newId, trampPush, trampCall, trampJump, proc, epilogue);
		check(jumpMsg != null && jumpMsg.contains("does not return to the dispatch epilogue"),
				"a trampoline JUMP moved off the epilogue is refused, got: " + jumpMsg);
		trampJump.argumentCells[0] = savedJump;

		check(verifyMessage(verify, s, newId, trampPush, trampCall, trampJump, proc, epilogue) == null,
				"the post-condition passes again once the perturbations are undone");
	}

	/**
	 * Runs the post-condition and returns the refusal message, null when it
	 * accepted, or an "UNEXPECTED" marker for anything that is not the
	 * IllegalStateException the guard is supposed to raise.
	 */
	private static String verifyMessage(Method verify, GFLPawnScript s, int newId, PawnInstruction trampPush,
			PawnInstruction trampCall, PawnInstruction trampJump, PawnInstruction proc, PawnInstruction epilogue) {
		try {
			verify.invoke(null, s, newId, trampPush, trampCall, trampJump, proc, epilogue);
			return null;
		} catch (InvocationTargetException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof IllegalStateException) {
				return cause.getMessage();
			}
			return "UNEXPECTED " + cause;
		} catch (Exception ex) {
			return "UNEXPECTED " + ex;
		}
	}

	/**
	 * Runs the talker emit and returns the IllegalStateException message it
	 * refused with, null when it did not refuse, or an "UNEXPECTED" marker when
	 * it failed some other way (an NPE or an index blowup is a guard that is no
	 * longer there, not a refusal).
	 */
	private static String refusalMessage(GFLPawnScript s) {
		try {
			TalkerScriptWizard.cloneTalker(s, FAKE_LINE);
			return null;
		} catch (IllegalStateException ex) {
			return ex.getMessage();
		} catch (RuntimeException ex) {
			return "UNEXPECTED " + ex;
		}
	}

	/** The CASETBL argument index of a case key, or -1. */
	private static int caseArgIndex(PawnInstruction caseTbl, int key) {
		for (int k = 0; k < caseTbl.argumentCells[0]; k++) {
			int ai = 2 + 2 * k;
			if (ai + 1 >= caseTbl.argumentCells.length) {
				break;
			}
			if (caseTbl.argumentCells[ai] == key) {
				return ai;
			}
		}
		return -1;
	}

	/**
	 * Repoints a case pair at an arbitrary instruction, with the same address
	 * math PawnInstruction.CaseListener and findDispatch use.
	 */
	private static boolean pointCaseAt(GFLPawnScript s, PawnInstruction caseTbl, int key, PawnInstruction target) {
		int ai = caseArgIndex(caseTbl, key);
		if (ai < 0) {
			return false;
		}
		caseTbl.argumentCells[ai + 1] = target.pointer - (caseTbl.pointer + ai * 4) - 4;
		return true;
	}

	/** The subroutine PROC a retail trampoline CALLs, or null. */
	private static PawnInstruction trampolineSub(GFLPawnScript s, PawnInstruction target) {
		int idx = s.instructions.indexOf(target);
		if (idx < 0 || idx + 1 >= s.instructions.size()) {
			return null;
		}
		PawnInstruction call = s.instructions.get(idx + 1);
		if (call.getCommand() != OP_CALL || call.argumentCells.length != 1) {
			return null;
		}
		PawnInstruction proc = s.lookupInstructionByPtr(call.pointer + call.argumentCells[0]);
		return (proc != null && proc.getCommand() == OP_PROC) ? proc : null;
	}

	/** Encoded size of an instruction, the emitter's own formula. */
	private static int insBytes(PawnInstruction ins) {
		return 4 + (ins.hasCompressedArgument ? 0 : ins.argumentCount * 4);
	}

	/**
	 * An instruction list that denies holding one instruction, and is otherwise
	 * itself. lookupInstructionByPtr walks the list and still finds the table;
	 * indexOf does not - exactly the state the "not part of the script's
	 * instruction list" guard names and refuses.
	 */
	private static class CaseTblDetachedList extends ArrayList<PawnInstruction> {

		private static final long serialVersionUID = 1L;

		private final PawnInstruction hidden;

		CaseTblDetachedList(List<PawnInstruction> src, PawnInstruction hidden) {
			super(src);
			this.hidden = hidden;
		}

		@Override
		public int indexOf(Object o) {
			return o == hidden ? -1 : super.indexOf(o);
		}
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

	private static void report() {
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
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
