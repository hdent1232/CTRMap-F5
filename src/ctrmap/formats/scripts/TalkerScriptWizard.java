package ctrmap.formats.scripts;

import ctrmap.humaninterface.ScriptEditor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Headless "add talking NPC" script surgery built on ZoneScriptAnalyzer.
 *
 * cloneTalker() appends a vanilla-shaped 8-instruction simple talker sub at
 * the end of the code section (before data), grows main's dispatch CASETBL by
 * one sorted case pair for a freshly allocated user script ID and re-fixes
 * every relative branch operand through the PawnInstruction listener chain
 * (the ScriptEditor.updateDocument idiom: setPtrsByIndex +
 * callInstructionListeners). The publics' absolute code addresses, which no
 * listener owns, are re-pointed manually. Everything happens in memory; the
 * caller decides where the script bytes go.
 */
public class TalkerScriptWizard {

	/**
	 * User-facing script IDs must stay below this (engine-reserved ranges
	 * start at 2000: items, 3000+ trainers, 7000 berries, 10050 placeholder).
	 */
	public static final int MAX_USER_SCRIPT_ID = 1000;

	/**
	 * First engine-reserved script ID range.
	 */
	public static final int ENGINE_RESERVED_MIN = 2000;

	/**
	 * Clones a simple talker into the zone script's dispatch.
	 *
	 * @param script a decompressThis()'d zone script, modified in place
	 * @param msgLine STORYTEXT line the new talker displays
	 * @return the newly allocated local script ID (dispatch case key)
	 * @throws IllegalStateException if the script has no dispatch, no message
	 * wrapper, no free user script ID, the line does not fit a packed
	 * PUSH_P_C argument or a pre-existing dispatch branch target does not
	 * resolve to an instruction boundary; the script is not modified in that
	 * case
	 */
	public static int cloneTalker(GFLPawnScript script, int msgLine) {
		script.decompressThis();
		if (ZoneScriptAnalyzer.findDispatch(script) == null) {
			throw new IllegalStateException("The zone script has no script dispatch (main SWITCH/CASETBL).");
		}
		PawnInstruction wrapper = ZoneScriptAnalyzer.findMsgWrapper(script);
		if (wrapper == null) {
			throw new IllegalStateException("The zone script has no message display wrapper to call.");
		}
		if (msgLine < Short.MIN_VALUE || msgLine > Short.MAX_VALUE) {
			//packed PUSH_P_C stores its argument in the upper 16 bits of the cell
			throw new IllegalStateException("Message line " + msgLine + " does not fit a packed PUSH_P_C argument.");
		}
		return cloneCallerSub(script, wrapper, new int[]{-1, 1, msgLine, 12});
	}

	/**
	 * Clones a constant-pushing wrapper-caller sub (PROC; one packed PUSH per
	 * constant; CALL wrapper; ZERO_PRI; RETN - the shared geometry of the
	 * vanilla talker, sign and give-item call sites) into the zone script's
	 * dispatch. The insertion machinery is byte-identical to the proven
	 * talking-NPC surgery; only the push constants and the callee vary.
	 *
	 * @param script a decompressThis()'d zone script, modified in place
	 * @param wrapper the PROC entry of the wrapper sub the clone will CALL
	 * (from ZoneScriptAnalyzer.findMsgWrapper/findSignWrapper/findGiveWrapper)
	 * @param pushConsts the constants to push, in push order, including the
	 * trailing argbytes constant (talker: -1, 1, line, 12; sign: type, line,
	 * 8; give: mode, count, item, 12)
	 * @return the newly allocated local script ID (dispatch case key)
	 * @throws IllegalStateException if the script has no dispatch, the wrapper
	 * is null, no free user script ID exists, a constant does not fit a packed
	 * PUSH_P_C argument or a pre-existing dispatch branch target does not
	 * resolve to an instruction boundary; the script is not modified in that
	 * case
	 */
	public static int cloneCallerSub(GFLPawnScript script, PawnInstruction wrapper, int[] pushConsts) {
		script.decompressThis();
		if (wrapper == null) {
			throw new IllegalStateException("The zone script has no wrapper subroutine to call.");
		}
		for (int i = 0; i < pushConsts.length; i++) {
			if (pushConsts[i] < Short.MIN_VALUE || pushConsts[i] > Short.MAX_VALUE) {
				//packed PUSH_P_C stores its argument in the upper 16 bits of the cell
				throw new IllegalStateException("Constant " + pushConsts[i] + " does not fit a packed PUSH_P_C argument.");
			}
		}
		//caller body: PROC; N x PUSH_P_C(const); CALL wrapper; ZERO_PRI; RETN
		PawnInstruction lastIns = script.instructions.get(script.instructions.size() - 1);
		int ptr = lastIns.pointer + 4 + (lastIns.hasCompressedArgument ? 0 : lastIns.argumentCount * 4);
		List<PawnInstruction> body = new ArrayList<>();
		body.add(makeIns(PawnInstruction.Commands.PROC, ptr, 0));
		ptr += 4;
		for (int i = 0; i < pushConsts.length; i++) {
			body.add(makeIns(PawnInstruction.Commands.PUSH_P_C, ptr, pushConsts[i]));
			ptr += 4;
		}
		body.add(makeIns(PawnInstruction.Commands.CALL, ptr, wrapper.pointer - ptr));
		ptr += 8; //CALL carries a full argument cell
		body.add(makeIns(PawnInstruction.Commands.ZERO_PRI, ptr, 0));
		ptr += 4;
		body.add(makeIns(PawnInstruction.Commands.RETN, ptr, 0));
		return installCase(script, body);
	}

	/**
	 * Installs {@code body} (a PROC..RETN subroutine, provisional pointers) as a
	 * new dispatch case in the zone's main SWITCH/CASETBL and returns the new
	 * script id an NPC can point its {@code script} field at. Shared by the
	 * talker/sign/giver wizards and the facility/BP emitters: does the
	 * append + trampoline + CASETBL grow + full renumber + branch/public fixups
	 * atomically, validating every pre-existing target first so a failure never
	 * leaves the script half-mutated. The body must already carry any
	 * native-table entries it references (append them before calling this).
	 *
	 * <p><b>The case pair points at a trampoline, never at {@code body}'s
	 * PROC.</b> SWITCH/CASETBL is a JUMP, not a CALL, so a subroutine entered
	 * straight from a case has no return address and no argument-byte cell: its
	 * terminal RETN pops the dispatcher's own saved frame, pops a data-segment
	 * offset into CIP and displaces STK by a code address, and the VM runs data
	 * as instructions. That is a hard freeze of the game - and a quiet one,
	 * because the sub's own CALL unwinds cleanly first, so the dialogue box
	 * appears normally and the hang lands when the player dismisses it. Retail
	 * never does it: 0 of the 2853 case targets in the 536 retail zone scripts
	 * are a PROC, and all 2853 are the three-instruction trampoline this method
	 * emits into main's own PROC frame -
	 * {@code PUSH_P_C(0); CALL <body>; JUMP <epilogue>}.
	 */
	public static int installCase(GFLPawnScript script, List<PawnInstruction> body) {
		script.decompressThis();
		ZoneScriptAnalyzer.Dispatch d = ZoneScriptAnalyzer.findDispatch(script);
		if (d == null) {
			throw new IllegalStateException("The zone script has no script dispatch (main SWITCH/CASETBL).");
		}
		//the trampoline hands control back to the dispatcher's shared epilogue
		//when the sub returns, exactly as every retail case does, so the
		//dispatcher must have one (536 of 536 retail zones do)
		PawnInstruction epilogue = ZoneScriptAnalyzer.findDispatchEpilogue(script);
		if (epilogue == null) {
			throw new IllegalStateException("The zone script's dispatch does not end in the shared \"ZERO_PRI; RETN\" a script case returns through.");
		}
		//validate every pre-existing branch target of the dispatch CASETBL before
		//any surgery: a null case target would be silently skipped by
		//CaseListener.onAddressChange (stale offset after the shift) and a null
		//default target would NPE mid-mutation, leaving the script half-mutated
		for (Map.Entry<Integer, PawnInstruction> c : d.cases.entrySet()) {
			if (c.getValue() == null) {
				throw new IllegalStateException("Dispatch case " + c.getKey() + " does not resolve to an instruction boundary.");
			}
		}
		if (script.lookupInstructionByPtr(d.caseTbl.pointer + 4 + d.caseTbl.argumentCells[1]) == null) {
			throw new IllegalStateException("The dispatch CASETBL default target does not resolve to an instruction boundary.");
		}
		//retail puts every trampoline between the SWITCH and the CASETBL, so
		//the new one goes in immediately before the table (536 of 536 zones)
		int tblIdx = script.instructions.indexOf(d.caseTbl);
		if (tblIdx < 0) {
			throw new IllegalStateException("The dispatch CASETBL is not part of the script's instruction list.");
		}
		int newId = 1;
		for (Integer key : d.cases.keySet()) {
			if (key != -1 && key < ENGINE_RESERVED_MIN && key >= newId) {
				newId = key + 1;
			}
		}
		if (newId >= MAX_USER_SCRIPT_ID) {
			throw new IllegalStateException("No free user script IDs below " + MAX_USER_SCRIPT_ID + ".");
		}

		//snapshot every branch target while the layout is still consistent
		script.setInstructionListeners();
		//publics hold absolute code addresses that no listener owns - resolve
		//them to instructions now so they can be re-pointed after the shift
		PawnInstruction[] publicTargets = new PawnInstruction[script.publics.size()];
		for (int i = 0; i < publicTargets.length; i++) {
			publicTargets[i] = script.lookupInstructionByPtr(script.publics.get(i).data[0]);
		}
		int oldCaseTblPtr = d.caseTbl.pointer;
		PawnInstruction proc = body.get(0);

		script.instructions.addAll(body);
		for (PawnInstruction ins : body) {
			ins.setParent(script); //gives any CALL/branch its JumpListener
		}

		//the retail trampoline, built on the provisional pointer space that
		//still follows the appended body so setParent resolves both operands to
		//the right instructions; the renumber below re-fixes them for real
		PawnInstruction tail = script.instructions.get(script.instructions.size() - 1);
		int tPtr = tail.pointer + 4 + (tail.hasCompressedArgument ? 0 : tail.argumentCount * 4);
		PawnInstruction trampPush = makeIns(PawnInstruction.Commands.PUSH_P_C, tPtr, 0);
		tPtr += 4;
		PawnInstruction trampCall = makeIns(PawnInstruction.Commands.CALL, tPtr, proc.pointer - tPtr);
		tPtr += 8; //CALL carries a full argument cell
		PawnInstruction trampJump = makeIns(PawnInstruction.Commands.JUMP, tPtr, epilogue.pointer - tPtr);
		List<PawnInstruction> trampoline = Arrays.asList(trampPush, trampCall, trampJump);
		int trampolineBytes = 0;
		for (PawnInstruction ins : trampoline) {
			trampolineBytes += 4 + (ins.hasCompressedArgument ? 0 : ins.argumentCount * 4);
		}
		script.instructions.addAll(tblIdx, trampoline);
		for (PawnInstruction ins : trampoline) {
			ins.setParent(script); //gives the CALL and the JUMP their JumpListener
		}

		//grow main's CASETBL by one pair, keeping the case keys sorted
		PawnInstruction ct = d.caseTbl;
		int[] oldArgs = ct.argumentCells;
		int insertAi = oldArgs.length;
		for (int ai = 2; ai + 1 < oldArgs.length; ai += 2) {
			if (oldArgs[ai] > newId) {
				insertAi = ai;
				break;
			}
		}
		int[] newArgs = new int[oldArgs.length + 2];
		System.arraycopy(oldArgs, 0, newArgs, 0, insertAi);
		newArgs[insertAi] = newId;
		newArgs[insertAi + 1] = 0; //offset fixed up after the renumber below
		System.arraycopy(oldArgs, insertAi, newArgs, insertAi + 2, oldArgs.length - insertAi);
		newArgs[0] = oldArgs[0] + 1; //case count
		ct.argumentCells = newArgs;
		ct.argumentCount = newArgs.length;

		//renumber and let the listeners re-fix all relative branch operands
		ScriptEditor.setPtrsByIndex(script.instructions);
		script.callInstructionListeners();
		//the CaseListener snapshot predates the insertion, so it only fixes
		//the pre-existing keys - point the new pair at the new TRAMPOLINE
		//manually (same address math as PawnInstruction.CaseListener)
		newArgs[insertAi + 1] = trampPush.pointer - (ct.pointer + insertAi * 4) - 4;
		ct.updateDisassembly();
		for (int i = 0; i < publicTargets.length; i++) {
			if (publicTargets[i] != null) {
				script.publics.get(i).data[0] = publicTargets[i].pointer;
			} else if (script.publics.get(i).data[0] > oldCaseTblPtr) {
				//an address no instruction owns only gets the raw byte shift:
				//the trampoline went in AT the old CASETBL address, and the
				//table it now precedes grew by one case pair
				script.publics.get(i).data[0] += trampolineBytes + 8;
			} else if (script.publics.get(i).data[0] == oldCaseTblPtr) {
				script.publics.get(i).data[0] += trampolineBytes;
			}
		}
		//write() takes the entry point from the dummy, but keep the field in
		//sync so analyzer calls on the live script keep working
		script.mainEntryPoint = script.mainEntryPointDummy.argumentCells[0];
		verifyCaseInstalled(script, newId, trampPush, trampCall, trampJump, proc, epilogue);
		script.updateRaw();
		return newId;
	}

	/**
	 * Post-condition of {@link #installCase}: the new case resolves to its
	 * trampoline, the trampoline's CALL enters the appended subroutine and its
	 * JUMP returns to the dispatcher's epilogue.
	 *
	 * <p>A failure here is a bug in this class rather than a property of the
	 * zone, and it arrives after the mutation - but the alternative to raising
	 * it is handing the player a script that freezes the game, so the emitter
	 * refuses out loud instead of shipping one. Callers work on a copy and only
	 * commit the zone script once this has returned.
	 */
	private static void verifyCaseInstalled(GFLPawnScript script, int newId, PawnInstruction trampPush,
			PawnInstruction trampCall, PawnInstruction trampJump, PawnInstruction proc, PawnInstruction epilogue) {
		ZoneScriptAnalyzer.Dispatch check = ZoneScriptAnalyzer.findDispatch(script);
		if (check == null || check.cases.get(newId) != trampPush) {
			throw new IllegalStateException("Internal error: dispatch case " + newId + " does not point at its trampoline.");
		}
		if (script.lookupInstructionByPtr(trampCall.pointer + trampCall.argumentCells[0]) != proc) {
			throw new IllegalStateException("Internal error: the case trampoline does not call the new subroutine.");
		}
		if (script.lookupInstructionByPtr(trampJump.pointer + trampJump.argumentCells[0]) != epilogue) {
			throw new IllegalStateException("Internal error: the case trampoline does not return to the dispatch epilogue.");
		}
	}

	/**
	 * How many of the zone's simple talkers display the given STORYTEXT line
	 * (for shared-line detection before editing dialogue in place).
	 */
	public static int countTalkersUsingLine(GFLPawnScript script, int line) {
		int count = 0;
		for (int id : ZoneScriptAnalyzer.listScriptIds(script)) {
			ZoneScriptAnalyzer.TalkerPattern tp = ZoneScriptAnalyzer.findTalkerPattern(script, id);
			if (tp != null && tp.msgLine == line) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Dropdown items for the zone's local script IDs, with a talker suffix
	 * where the case resolves to a simple talker.
	 */
	public static List<String> buildScriptIdItems(GFLPawnScript script) {
		List<String> ret = new ArrayList<>();
		for (int id : ZoneScriptAnalyzer.listScriptIds(script)) {
			ZoneScriptAnalyzer.TalkerPattern tp = ZoneScriptAnalyzer.findTalkerPattern(script, id);
			ret.add(tp == null ? String.valueOf(id) : id + " (talker: line " + tp.msgLine + ")");
		}
		return ret;
	}

	/**
	 * Whether an NPC may point at scriptId in this zone: 0 (no script), an
	 * engine-reserved id, or a case the dispatch defines. A zone whose
	 * dispatch cannot be read is not refused. Every retail NPC with a local
	 * id satisfies this; the editor used to accept any number and show the
	 * same "not a simple talker" note it shows for a valid advanced script.
	 */
	public static boolean scriptIdExists(GFLPawnScript script, int scriptId) {
		if (scriptId == 0 || scriptId >= ENGINE_RESERVED_MIN) {
			return true;
		}
		List<Integer> ids = ZoneScriptAnalyzer.listScriptIds(script);
		return ids.isEmpty() || ids.contains(scriptId);
	}

	private static PawnInstruction makeIns(PawnInstruction.Commands cmd, int ptr, int arg) {
		PawnInstruction ins = new PawnInstruction(ptr, cmd.ordinal(), "");
		if (ins.argumentCells.length > 0) {
			ins.argumentCells[0] = arg;
		}
		ins.updateDisassembly();
		return ins;
	}
}
