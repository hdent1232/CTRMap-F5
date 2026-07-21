package ctrmap.formats.scripts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Static, headless analysis helpers for zone Pawn scripts (ZO subfile 2).
 *
 * The engine dispatches NPC/trigger scripts by writing the script ID into a
 * hash-named public variable cell and running main, which does
 * LOAD_P_PRI(cell); SWITCH -&gt; CASETBL. The CASETBL case keys ARE the local
 * script IDs (-1 = zone init). A "simple talker" script is the 8-instruction
 * pattern PROC; PUSH_P_C(-1); PUSH_P_C(1); PUSH_P_C(msgLine); PUSH_P_C(12);
 * CALL &lt;msgWrapper&gt;; ZERO_PRI; RETN, where msgLine indexes the zone's
 * STORYTEXT file (ZoneHeader.textID).
 *
 * All methods expect a script that has already been decompressThis()'d and
 * never modify it (except patchTalkerLine, which edits constants in place).
 */
public class ZoneScriptAnalyzer {

	/**
	 * Name hash of the message-display native (natives[i].data[1]), stable
	 * across all zones.
	 */
	public static final int MSG_DISPLAY_NATIVE_HASH = 0x9ADF1616;

	/**
	 * GetWork argument constant used by the message wrapper sub (0x8011).
	 */
	public static final int MSG_WRAPPER_GETWORK_CONST = 32785;

	/**
	 * Name hash of the bag-space-check native (item, count) the vanilla
	 * give-item wrapper calls before adding, stable game-wide.
	 */
	public static final int CHECK_ITEM_SPACE_NATIVE_HASH = 0xA5135FDE;

	/**
	 * Name hash of the add-item native (item, count) on the give-item
	 * wrapper's success branch, stable game-wide.
	 */
	public static final int ADD_ITEM_NATIVE_HASH = 0x8D631FFE;

	/**
	 * Name hash of the sign frame-style native (arg 0x60000) the vanilla sign
	 * wrapper calls before opening the message window, stable game-wide.
	 */
	public static final int SIGN_STYLE_NATIVE_HASH = 0x6393BE9A;

	private static final int OP_LOAD_PRI = PawnInstruction.Commands.LOAD_PRI.ordinal();
	private static final int OP_LOAD_P_PRI = PawnInstruction.Commands.LOAD_P_PRI.ordinal();
	private static final int OP_PUSH_C = PawnInstruction.Commands.PUSH_C.ordinal();
	private static final int OP_PUSH_P_C = PawnInstruction.Commands.PUSH_P_C.ordinal();
	private static final int OP_PROC = PawnInstruction.Commands.PROC.ordinal();
	private static final int OP_RET = PawnInstruction.Commands.RET.ordinal();
	private static final int OP_RETN = PawnInstruction.Commands.RETN.ordinal();
	private static final int OP_CALL = PawnInstruction.Commands.CALL.ordinal();
	private static final int OP_SWITCH = PawnInstruction.Commands.SWITCH.ordinal();
	private static final int OP_CASETBL = PawnInstruction.Commands.CASETBL.ordinal();
	private static final int OP_ZERO_PRI = PawnInstruction.Commands.ZERO_PRI.ordinal();
	private static final int OP_SYSREQ_N = PawnInstruction.Commands.SYSREQ_N.ordinal();
	private static final int OP_EQ_C_PRI = PawnInstruction.Commands.EQ_C_PRI.ordinal();
	private static final int OP_EQ_P_C_PRI = PawnInstruction.Commands.EQ_P_C_PRI.ordinal();

	/**
	 * Result of findDispatch - the main CASETBL and its ordered case map.
	 */
	public static class Dispatch {

		/**
		 * The SWITCH instruction in main.
		 */
		public PawnInstruction switchIns;
		/**
		 * The CASETBL instruction the SWITCH jumps to.
		 */
		public PawnInstruction caseTbl;
		/**
		 * caseKey (local script ID, -1 = zone init) -&gt; case target
		 * instruction, in CASETBL order. A value may be null if the case
		 * offset does not land on an instruction boundary.
		 */
		public final LinkedHashMap<Integer, PawnInstruction> cases = new LinkedHashMap<>();
	}

	/**
	 * Result of findCallerPattern - a matched constant-pushing wrapper-caller
	 * sub: PROC; N x PUSH(_P)_C; CALL &lt;wrapper&gt;; ZERO_PRI; RETN. The simple
	 * talker, the R4 sign and the give-item call sites all share this
	 * geometry, differing only in push count, constants and callee.
	 */
	public static class CallerPattern {

		/**
		 * The PROC entry of the caller subroutine.
		 */
		public PawnInstruction subEntry;
		/**
		 * The push instructions in order (patch targets for the constants).
		 */
		public PawnInstruction[] pushes;
		/**
		 * The pushed constants in push order (last one is the argbytes count).
		 */
		public int[] pushConsts;
		/**
		 * The CALL to the wrapper.
		 */
		public PawnInstruction wrapperCall;
		/**
		 * The wrapper's entry instruction (CALL target), null if unresolvable.
		 */
		public PawnInstruction wrapperEntry;
	}

	/**
	 * Result of findTalkerPattern - a matched 8-instruction simple talker.
	 */
	public static class TalkerPattern {

		/**
		 * STORYTEXT line index (3rd pushed constant).
		 */
		public int msgLine;
		/**
		 * The PROC entry of the talker subroutine.
		 */
		public PawnInstruction subEntry;
		/**
		 * The PUSH instruction holding msgLine (patch target).
		 */
		public PawnInstruction msgLinePush;
		/**
		 * The CALL to the message wrapper.
		 */
		public PawnInstruction wrapperCall;
		/**
		 * The wrapper's entry instruction (CALL target), null if unresolvable.
		 */
		public PawnInstruction wrapperEntry;
	}

	/**
	 * Locates main's LOAD(_P)_PRI + SWITCH -&gt; CASETBL script dispatch.
	 *
	 * @param script a decompressThis()'d zone script
	 * @return the dispatch info, or null if the script has no dispatch
	 */
	public static Dispatch findDispatch(GFLPawnScript script) {
		if (script == null || script.instructions.isEmpty()) {
			return null;
		}
		PawnInstruction entry = script.lookupInstructionByPtr(script.mainEntryPoint);
		if (entry == null) {
			return null;
		}
		int entryIdx = script.instructions.indexOf(entry);
		PawnInstruction switchIns = null;
		boolean loadSeen = false;
		for (int i = entryIdx; i < script.instructions.size() && i < entryIdx + 16; i++) {
			PawnInstruction ins = script.instructions.get(i);
			int cmd = ins.getCommand();
			if (cmd == OP_LOAD_PRI || cmd == OP_LOAD_P_PRI) {
				loadSeen = true;
			} else if (cmd == OP_SWITCH && loadSeen && ins.argumentCells.length == 1) {
				switchIns = ins;
				break;
			} else if (cmd == OP_RETN || cmd == OP_RET) {
				break;
			}
		}
		if (switchIns == null) {
			return null;
		}
		PawnInstruction caseTbl = script.lookupInstructionByPtr(switchIns.pointer + switchIns.argumentCells[0]);
		if (caseTbl == null || caseTbl.getCommand() != OP_CASETBL || caseTbl.argumentCells.length < 2) {
			return null;
		}
		Dispatch d = new Dispatch();
		d.switchIns = switchIns;
		d.caseTbl = caseTbl;
		int count = caseTbl.argumentCells[0];
		for (int k = 0; k < count; k++) {
			int ai = 2 + 2 * k;
			if (ai + 1 >= caseTbl.argumentCells.length) {
				break;
			}
			//same address math as PawnInstruction.CaseListener
			int targetPtr = (caseTbl.pointer + ai * 4) + caseTbl.argumentCells[ai + 1] + 4;
			d.cases.put(caseTbl.argumentCells[ai], script.lookupInstructionByPtr(targetPtr));
		}
		return d;
	}

	/**
	 * The local script IDs of the zone (dispatch case keys except -1), in
	 * CASETBL (sorted) order. Empty if the script has no dispatch.
	 */
	public static List<Integer> listScriptIds(GFLPawnScript script) {
		List<Integer> ret = new ArrayList<>();
		Dispatch d = findDispatch(script);
		if (d == null) {
			return ret;
		}
		for (Integer key : d.cases.keySet()) {
			if (key != -1) {
				ret.add(key);
			}
		}
		return ret;
	}

	/**
	 * Matches the case target of caseKey against the 8-instruction simple
	 * talker pattern (directly or through the usual PUSH_P_C(0); CALL sub
	 * dispatch stub).
	 *
	 * @return the matched talker, or null if the case is not a simple talker
	 */
	public static TalkerPattern findTalkerPattern(GFLPawnScript script, int caseKey) {
		CallerPattern cp = findCallerPattern(script, caseKey, 4);
		if (cp == null || cp.pushConsts[0] != -1 || cp.pushConsts[1] != 1 || cp.pushConsts[3] != 12) {
			return null;
		}
		TalkerPattern tp = new TalkerPattern();
		tp.msgLine = cp.pushConsts[2];
		tp.subEntry = cp.subEntry;
		tp.msgLinePush = cp.pushes[2];
		tp.wrapperCall = cp.wrapperCall;
		tp.wrapperEntry = cp.wrapperEntry;
		return tp;
	}

	/**
	 * Matches the case target of caseKey against the constant-pushing
	 * wrapper-caller geometry with the given number of pushed constants
	 * (directly or through the usual PUSH_P_C(0); CALL sub dispatch stub).
	 * Only the shape is checked - callers decide what the constants and the
	 * callee must be.
	 *
	 * @return the matched caller, or null if the case has a different shape
	 */
	public static CallerPattern findCallerPattern(GFLPawnScript script, int caseKey, int pushCount) {
		Dispatch d = findDispatch(script);
		if (d == null) {
			return null;
		}
		PawnInstruction target = d.cases.get(caseKey);
		if (target == null) {
			return null;
		}
		PawnInstruction subEntry = resolveSubEntry(script, target);
		if (subEntry == null) {
			return null;
		}
		int idx = script.instructions.indexOf(subEntry);
		if (idx < 0 || idx + pushCount + 4 > script.instructions.size()) {
			return null;
		}
		if (subEntry.getCommand() != OP_PROC) {
			return null;
		}
		PawnInstruction[] pushes = new PawnInstruction[pushCount];
		int[] pushConsts = new int[pushCount];
		for (int i = 0; i < pushCount; i++) {
			PawnInstruction push = script.instructions.get(idx + 1 + i);
			int cmd = push.getCommand();
			if ((cmd != OP_PUSH_P_C && cmd != OP_PUSH_C) || push.argumentCells.length < 1) {
				return null;
			}
			pushes[i] = push;
			pushConsts[i] = push.argumentCells[0];
		}
		PawnInstruction call = script.instructions.get(idx + pushCount + 1);
		if (call.getCommand() != OP_CALL || call.argumentCells.length != 1) {
			return null;
		}
		if (script.instructions.get(idx + pushCount + 2).getCommand() != OP_ZERO_PRI) {
			return null;
		}
		if (script.instructions.get(idx + pushCount + 3).getCommand() != OP_RETN) {
			return null;
		}
		CallerPattern cp = new CallerPattern();
		cp.subEntry = subEntry;
		cp.pushes = pushes;
		cp.pushConsts = pushConsts;
		cp.wrapperCall = call;
		cp.wrapperEntry = script.lookupInstructionByPtr(call.pointer + call.argumentCells[0]);
		return cp;
	}

	/**
	 * Locates the zone's message wrapper sub (sub_36C-equivalent): a sub that
	 * compares an argument to -1, pushes constant 32785 (0x8011) into a
	 * GetWork native call, and whose call chain eventually reaches a SYSREQ_N
	 * whose native-table entry hash is MSG_DISPLAY_NATIVE_HASH.
	 *
	 * @return the wrapper's PROC entry instruction, or null
	 */
	public static PawnInstruction findMsgWrapper(GFLPawnScript script) {
		if (script == null || script.instructions.isEmpty()) {
			return null;
		}
		for (int i = 0; i < script.instructions.size(); i++) {
			PawnInstruction ins = script.instructions.get(i);
			if (ins.getCommand() != OP_PROC) {
				continue;
			}
			if (matchesWrapperShape(script, i) && chainReachesNative(script, ins, MSG_DISPLAY_NATIVE_HASH)) {
				return ins;
			}
		}
		return null;
	}

	/**
	 * Locates the zone's give-item wrapper sub (the routine the vanilla
	 * give-item call sites CALL): the first sub whose direct body calls the
	 * bag-space-check native and whose call chain reaches the add-item
	 * native. Present in 120 of the 536 pristine ORAS zones.
	 *
	 * @return the wrapper's PROC entry instruction, or null
	 */
	public static PawnInstruction findGiveWrapper(GFLPawnScript script) {
		if (script == null || script.instructions.isEmpty()) {
			return null;
		}
		for (int i = 0; i < script.instructions.size(); i++) {
			PawnInstruction ins = script.instructions.get(i);
			if (ins.getCommand() == OP_PROC && isGiveWrapperEntry(script, ins)) {
				return ins;
			}
		}
		return null;
	}

	/**
	 * Whether the given PROC entry satisfies the give-item wrapper predicate
	 * (bag-space check in the direct body, add-item reachable via CALLs).
	 */
	public static boolean isGiveWrapperEntry(GFLPawnScript script, PawnInstruction entry) {
		if (entry == null || entry.getCommand() != OP_PROC) {
			return false;
		}
		int idx = script.instructions.indexOf(entry);
		if (idx < 0) {
			return false;
		}
		return bodyUsesNative(script, idx, CHECK_ITEM_SPACE_NATIVE_HASH)
				&& chainReachesNative(script, entry, ADD_ITEM_NATIVE_HASH);
	}

	/**
	 * Locates the zone's sign wrapper sub (the routine the vanilla R4 sign
	 * call sites CALL): the first sub that calls the sign frame-style native
	 * within its first five instructions and whose call chain reaches the
	 * message-display native. Present in 69 of the 536 pristine ORAS zones.
	 *
	 * @return the wrapper's PROC entry instruction, or null
	 */
	public static PawnInstruction findSignWrapper(GFLPawnScript script) {
		if (script == null || script.instructions.isEmpty()) {
			return null;
		}
		for (int i = 0; i < script.instructions.size(); i++) {
			PawnInstruction ins = script.instructions.get(i);
			if (ins.getCommand() == OP_PROC && isSignWrapperEntry(script, ins)) {
				return ins;
			}
		}
		return null;
	}

	/**
	 * Whether the given PROC entry satisfies the sign wrapper predicate
	 * (frame-style native in the first five instructions, message display
	 * reachable via CALLs).
	 */
	public static boolean isSignWrapperEntry(GFLPawnScript script, PawnInstruction entry) {
		if (entry == null || entry.getCommand() != OP_PROC) {
			return false;
		}
		int idx = script.instructions.indexOf(entry);
		if (idx < 0) {
			return false;
		}
		int end = subBodyEnd(script, idx);
		boolean style = false;
		for (int k = idx; k <= end && k <= idx + 4; k++) {
			PawnInstruction b = script.instructions.get(k);
			if (b.getCommand() == OP_SYSREQ_N && b.argumentCells.length >= 1) {
				int nIdx = b.argumentCells[0];
				if (nIdx >= 0 && nIdx < script.natives.size()) {
					int[] data = script.natives.get(nIdx).data;
					if (data.length >= 2 && data[1] == SIGN_STYLE_NATIVE_HASH) {
						style = true;
						break;
					}
				}
			}
		}
		return style && chainReachesNative(script, entry, MSG_DISPLAY_NATIVE_HASH);
	}

	/**
	 * Whether the direct body of the sub starting at entryIdx (no CALL
	 * recursion) contains a SYSREQ_N of the given native name hash.
	 */
	private static boolean bodyUsesNative(GFLPawnScript script, int entryIdx, int nativeHash) {
		int end = subBodyEnd(script, entryIdx);
		for (int i = entryIdx; i <= end; i++) {
			PawnInstruction ins = script.instructions.get(i);
			if (ins.getCommand() == OP_SYSREQ_N && ins.argumentCells.length >= 1) {
				int nIdx = ins.argumentCells[0];
				if (nIdx >= 0 && nIdx < script.natives.size()) {
					int[] data = script.natives.get(nIdx).data;
					if (data.length >= 2 && data[1] == nativeHash) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * For an existing simple talker, sets the pushed message line constant in
	 * place (no size change).
	 *
	 * @return true if the talker was found and patched
	 */
	public static boolean patchTalkerLine(GFLPawnScript script, int caseKey, int newLine) {
		TalkerPattern tp = findTalkerPattern(script, caseKey);
		if (tp == null) {
			return false;
		}
		if (tp.msgLinePush.hasCompressedArgument && (newLine < Short.MIN_VALUE || newLine > Short.MAX_VALUE)) {
			//packed PUSH_P_C stores its argument in the upper 16 bits of the cell
			return false;
		}
		tp.msgLinePush.argumentCells[0] = newLine;
		tp.msgLinePush.updateDisassembly();
		return true;
	}

	/**
	 * Resolves a dispatch case target to its subroutine PROC: either the
	 * target itself, or the CALL target of a leading PUSH-const stub.
	 */
	private static PawnInstruction resolveSubEntry(GFLPawnScript script, PawnInstruction target) {
		if (target.getCommand() == OP_PROC) {
			return target;
		}
		int idx = script.instructions.indexOf(target);
		if (idx < 0) {
			return null;
		}
		for (int i = idx, steps = 0; i < script.instructions.size() && steps < 5; i++, steps++) {
			PawnInstruction ins = script.instructions.get(i);
			int cmd = ins.getCommand();
			if (cmd == OP_CALL && ins.argumentCells.length == 1) {
				return script.lookupInstructionByPtr(ins.pointer + ins.argumentCells[0]);
			}
			if (cmd != OP_PUSH_P_C && cmd != OP_PUSH_C) {
				return null;
			}
		}
		return null;
	}

	/**
	 * Checks the wrapper shape within the sub starting at entryIdx: an
	 * EQ(_P)_C_PRI(-1) compare, then within a few instructions a PUSH of
	 * constant 32785 immediately feeding a SYSREQ_N.
	 */
	private static boolean matchesWrapperShape(GFLPawnScript script, int entryIdx) {
		int end = subBodyEnd(script, entryIdx);
		for (int e = entryIdx + 1; e <= end; e++) {
			PawnInstruction cmp = script.instructions.get(e);
			int cmpCmd = cmp.getCommand();
			if ((cmpCmd != OP_EQ_C_PRI && cmpCmd != OP_EQ_P_C_PRI) || cmp.argumentCells.length < 1 || cmp.argumentCells[0] != -1) {
				continue;
			}
			for (int p = e + 1; p <= end && p <= e + 6; p++) {
				PawnInstruction push = script.instructions.get(p);
				int pushCmd = push.getCommand();
				if ((pushCmd != OP_PUSH_C && pushCmd != OP_PUSH_P_C) || push.argumentCells.length < 1 || push.argumentCells[0] != MSG_WRAPPER_GETWORK_CONST) {
					continue;
				}
				for (int s = p + 1; s <= end && s <= p + 2; s++) {
					if (script.instructions.get(s).getCommand() == OP_SYSREQ_N) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * BFS over CALL targets from the given sub entry, checking every SYSREQ_N
	 * against the natives table for the given name hash.
	 */
	private static boolean chainReachesNative(GFLPawnScript script, PawnInstruction entry, int nativeHash) {
		List<Integer> queue = new ArrayList<>();
		Set<Integer> visited = new HashSet<>();
		queue.add(entry.pointer);
		while (!queue.isEmpty()) {
			int ptr = queue.remove(queue.size() - 1);
			if (!visited.add(ptr)) {
				continue;
			}
			PawnInstruction sub = script.lookupInstructionByPtr(ptr);
			if (sub == null) {
				continue;
			}
			int idx = script.instructions.indexOf(sub);
			int end = subBodyEnd(script, idx);
			for (int i = idx; i <= end; i++) {
				PawnInstruction ins = script.instructions.get(i);
				int cmd = ins.getCommand();
				if (cmd == OP_SYSREQ_N && ins.argumentCells.length >= 1) {
					int nIdx = ins.argumentCells[0];
					if (nIdx >= 0 && nIdx < script.natives.size()) {
						int[] data = script.natives.get(nIdx).data;
						if (data.length >= 2 && data[1] == nativeHash) {
							return true;
						}
					}
				} else if (cmd == OP_CALL && ins.argumentCells.length == 1) {
					queue.add(ins.pointer + ins.argumentCells[0]);
				}
			}
		}
		return false;
	}

	/**
	 * Index of the last instruction of the sub starting at entryIdx (its
	 * RET/RETN, or the end of the code section).
	 */
	private static int subBodyEnd(GFLPawnScript script, int entryIdx) {
		for (int i = entryIdx; i < script.instructions.size(); i++) {
			int cmd = script.instructions.get(i).getCommand();
			if ((cmd == OP_RET || cmd == OP_RETN) && i > entryIdx) {
				return i;
			}
		}
		return script.instructions.size() - 1;
	}
}
