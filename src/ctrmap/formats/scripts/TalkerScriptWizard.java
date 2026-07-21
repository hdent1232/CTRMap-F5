package ctrmap.formats.scripts;

import ctrmap.humaninterface.ScriptEditor;
import java.util.ArrayList;
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
		ZoneScriptAnalyzer.Dispatch d = ZoneScriptAnalyzer.findDispatch(script);
		if (d == null) {
			throw new IllegalStateException("The zone script has no script dispatch (main SWITCH/CASETBL).");
		}
		if (wrapper == null) {
			throw new IllegalStateException("The zone script has no wrapper subroutine to call.");
		}
		for (int i = 0; i < pushConsts.length; i++) {
			if (pushConsts[i] < Short.MIN_VALUE || pushConsts[i] > Short.MAX_VALUE) {
				//packed PUSH_P_C stores its argument in the upper 16 bits of the cell
				throw new IllegalStateException("Constant " + pushConsts[i] + " does not fit a packed PUSH_P_C argument.");
			}
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

		//append the caller sub at the end of the code section (provisional
		//pointers - setPtrsByIndex renumbers them below)
		PawnInstruction lastIns = script.instructions.get(script.instructions.size() - 1);
		int ptr = lastIns.pointer + 4 + (lastIns.hasCompressedArgument ? 0 : lastIns.argumentCount * 4);
		List<PawnInstruction> talker = new ArrayList<>();
		PawnInstruction proc = makeIns(PawnInstruction.Commands.PROC, ptr, 0);
		talker.add(proc);
		ptr += 4;
		for (int i = 0; i < pushConsts.length; i++) {
			talker.add(makeIns(PawnInstruction.Commands.PUSH_P_C, ptr, pushConsts[i]));
			ptr += 4;
		}
		talker.add(makeIns(PawnInstruction.Commands.CALL, ptr, wrapper.pointer - ptr));
		ptr += 8; //CALL carries a full argument cell
		talker.add(makeIns(PawnInstruction.Commands.ZERO_PRI, ptr, 0));
		ptr += 4;
		talker.add(makeIns(PawnInstruction.Commands.RETN, ptr, 0));
		script.instructions.addAll(talker);
		for (PawnInstruction ins : talker) {
			ins.setParent(script); //gives the CALL its wrapper JumpListener
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
		//the pre-existing keys - point the new pair at the new sub manually
		//(same address math as PawnInstruction.CaseListener)
		newArgs[insertAi + 1] = proc.pointer - (ct.pointer + insertAi * 4) - 4;
		ct.updateDisassembly();
		for (int i = 0; i < publicTargets.length; i++) {
			if (publicTargets[i] != null) {
				script.publics.get(i).data[0] = publicTargets[i].pointer;
			} else if (script.publics.get(i).data[0] > oldCaseTblPtr) {
				script.publics.get(i).data[0] += 8; //one new case pair
			}
		}
		//write() takes the entry point from the dummy, but keep the field in
		//sync so analyzer calls on the live script keep working
		script.mainEntryPoint = script.mainEntryPointDummy.argumentCells[0];
		script.updateRaw();
		return newId;
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

	private static PawnInstruction makeIns(PawnInstruction.Commands cmd, int ptr, int arg) {
		PawnInstruction ins = new PawnInstruction(ptr, cmd.ordinal(), "");
		if (ins.argumentCells.length > 0) {
			ins.argumentCells[0] = arg;
		}
		ins.updateDisassembly();
		return ins;
	}
}
