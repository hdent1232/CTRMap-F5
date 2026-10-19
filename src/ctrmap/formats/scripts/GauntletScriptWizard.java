package ctrmap.formats.scripts;

import ctrmap.scripts.GfHash;
import java.util.ArrayList;
import java.util.List;

/**
 * Emits the INDEPENDENT battle-challenge script: an NPC whose battles run on
 * the player's OWN trainer entries (trdata/trpoke) through the generic field
 * natives - fully decoupled from the Battle Maison/Institute engine and its
 * shared opponent pools, so the retail game's data stays untouched.
 *
 * <p>Every instruction shape is copied from measured retail scripts:
 * <ul>
 * <li>battle start = {@code _CallTrainerBattleCore(0, tid, 0,0,0,0,0)} - the
 *     rival/route trainer-battle primitive used by 44 ordinary zones (shape:
 *     zone 25 &#64;0x440 wrapper + &#64;0xe84 literal-id callers);</li>
 * <li>result read = the 10-instruction {@code _BattleGetResult} frame (zone 25
 *     &#64;0x470): an 8-cell struct on the stack, cell 0 to PRI, 0 = loss;</li>
 * <li>streak = a SAVE-range work variable via {@code WorkGet}/{@code WorkSet}
 *     (write shape zone 448 &#64;0x3b4: push value, push id);</li>
 * <li>BP award = the proven {@link FacilityScriptWizard} GetBP/ADD/SetBP frame;</li>
 * <li>messages = the zone's msg wrapper, called exactly like the talker.</li>
 * </ul>
 * The trainer for streak k is {@code trainerIds[min(k, n-1)]} via the
 * dispatch-style SWITCH/CASETBL clamp idiom (zone 448 &#64;0x2530). Each talk
 * runs ONE battle; a win increments the streak and pays BP (plus a bonus when
 * the streak reaches the milestone), a loss resets it. Installed as a new
 * dispatch case via the corpus-proven {@link TalkerScriptWizard#installCase}.
 */
public class GauntletScriptWizard {

	/**
	 * Save-range work ids referenced by NO retail zone script (streak default) - where the search for
	 * a free one starts. A default is not an allocation: every challenge built with it shared one
	 * streak, so {@link #workUsage} is asked before one is used (F241).
	 */
	public static final int DEFAULT_STREAK_WORK = 0x4020;

	/** The save range a streak variable must sit in. */
	public static final int SAVE_WORK_FIRST = 0x4000;
	public static final int SAVE_WORK_LAST = 0x4FFF;

	/**
	 * The work variables a script reads or writes by a literal id: the constant pushed last before a
	 * call to WorkGet or WorkSet - the id is the last push for both, in the retail write shape (zone
	 * 448 &#64;0x3b4: push value, push id) and in this wizard's own frames (F241). An id computed at
	 * run time is not seen: nothing short of running the script could see it.
	 */
	public static java.util.Set<Integer> workVariablesUsed(GFLPawnScript script) {
		script.decompressThis();
		int get = GfHash.hashForName("WorkGet");
		int set = GfHash.hashForName("WorkSet");
		java.util.Set<Integer> natives = new java.util.HashSet<>();
		for (int i = 0; i < script.natives.size(); i++) {
			int hash = script.natives.get(i).data[1];
			if (hash == get || hash == set) {
				natives.add(i);
			}
		}
		java.util.Set<Integer> used = new java.util.TreeSet<>();
		for (int i = 1; i < script.instructions.size(); i++) {
			PawnInstruction call = script.instructions.get(i);
			PawnInstruction id = script.instructions.get(i - 1);
			int push = id.getCommand();
			if (call.getCommand() == PawnInstruction.Commands.SYSREQ_N.ordinal() && natives.contains(call.argumentCells[0])
					&& (push == PawnInstruction.Commands.PUSH_C.ordinal() || push == PawnInstruction.Commands.PUSH_P_C.ordinal())) {
				used.add(id.argumentCells[0]);
			}
		}
		return used;
	}

	/** One zone's script by index: null when the zone has none; an exception when it could not be read. */
	public interface ZoneScripts {

		GFLPawnScript get(int zone) throws java.io.IOException;
	}

	/** Which zones use each work variable, and which zones' scripts could not be read to say (F241). */
	public static final class WorkUsage {

		public final java.util.SortedMap<Integer, List<Integer>> zones = new java.util.TreeMap<>();
		/** Zones whose scripts could not be read: what they use is UNKNOWN, never "nothing". */
		public final List<Integer> unread = new ArrayList<>();

		/** The zones using {@code work}; empty when no zone that could be read does. */
		public List<Integer> users(int work) {
			List<Integer> z = zones.get(work);
			return z == null ? new ArrayList<Integer>() : z;
		}

		/** The first save-range variable at or after {@code from} no zone that could be read uses; -1 when none is left. */
		public int nextFree(int from) {
			for (int w = Math.max(from, SAVE_WORK_FIRST); w <= SAVE_WORK_LAST; w++) {
				if (!zones.containsKey(w)) {
					return w;
				}
			}
			return -1;
		}
	}

	/** Every work variable the given zones' scripts use, by zone, and the zones that could not be read. */
	public static WorkUsage workUsage(ZoneScripts scripts, int zoneCount) {
		WorkUsage u = new WorkUsage();
		for (int z = 0; z < zoneCount; z++) {
			GFLPawnScript s;
			try {
				s = scripts.get(z);
			} catch (java.io.IOException | RuntimeException unreadable) {
				u.unread.add(z);
				continue;
			}
			if (s != null) {
				for (int w : workVariablesUsed(s)) {
					u.zones.computeIfAbsent(w, k -> new ArrayList<>()).add(z);
				}
			}
		}
		return u;
	}

	/** The wizard's inputs; storytext lines of -1 mean "no message". */
	public static class Config {

		public int[] trainerIds;
		public int bpPerWin;
		public int milestone;      // streak length that pays the bonus; 0 = none
		public int milestoneBonus;
		public int streakWorkId = DEFAULT_STREAK_WORK;
		public int introLine = -1;
		public int winLine = -1;
		public int loseLine = -1;
		/** Also run the engine's trainer-loss handler (white-out) on defeat. */
		public boolean loseWhiteout = false;
	}

	/**
	 * Emits the challenge and installs it as a new dispatch case.
	 *
	 * @param measured the game the zone is from, already asked (NpcTemplates.measuredOn): the
	 * natives, the work range and the trainer bound are that game's
	 * @return the newly allocated local script ID (for the NPC's script field)
	 */
	public static int addChallengeScript(NpcTemplates.Measured measured, GFLPawnScript script, Config cfg) {
		int trainerMax = NpcTemplates.asked(measured).profile().trainerIdMax();
		script.decompressThis();
		if (cfg.trainerIds == null || cfg.trainerIds.length == 0) {
			throw new IllegalArgumentException("The challenge needs at least one trainer.");
		}
		if (cfg.trainerIds.length > 64) {
			throw new IllegalArgumentException("At most 64 trainers per challenge NPC.");
		}
		for (int tid : cfg.trainerIds) {
			if (tid < 1 || tid > trainerMax) {
				throw new IllegalArgumentException("Trainer id " + tid + " out of range (1.." + trainerMax + ").");
			}
		}
		if (cfg.streakWorkId < SAVE_WORK_FIRST || cfg.streakWorkId > SAVE_WORK_LAST) {
			throw new IllegalArgumentException("The streak work variable must be in the save range 0x4000..0x4FFF.");
		}
		checkPacked(cfg.streakWorkId, "streak work variable");
		checkPacked(cfg.introLine, "intro line");
		checkPacked(cfg.winLine, "win line");
		checkPacked(cfg.loseLine, "lose line");
		PawnInstruction msgWrapper = null;
		if (cfg.introLine >= 0 || cfg.winLine >= 0 || cfg.loseLine >= 0) {
			msgWrapper = ZoneScriptAnalyzer.findMsgWrapper(script);
			if (msgWrapper == null) {
				throw new IllegalStateException("This zone's script has no message display wrapper - "
						+ "inject the vanilla one first (the talking-NPC flow offers it), or emit without messages.");
			}
		}
		int nCore = PawnEmitter.findOrAddNative(script, GfHash.hashForName("_CallTrainerBattleCore"));
		int nResult = PawnEmitter.findOrAddNative(script, GfHash.hashForName("_BattleGetResult"));
		int nWorkGet = PawnEmitter.findOrAddNative(script, GfHash.hashForName("WorkGet"));
		int nWorkSet = PawnEmitter.findOrAddNative(script, GfHash.hashForName("WorkSet"));
		int nGetBP = PawnEmitter.findOrAddNative(script, GfHash.hashForName("PlayerGetBP"));
		int nSetBP = PawnEmitter.findOrAddNative(script, GfHash.hashForName("PlayerSetBP"));
		int nLose = cfg.loseWhiteout
				? PawnEmitter.findOrAddNative(script, GfHash.hashForName("CallTrainerLose")) : -1;

		PawnEmitter e = new PawnEmitter(script);
		e.ins(PawnInstruction.Commands.PROC, 0);
		if (cfg.introLine >= 0) {
			emitMsg(e, msgWrapper, cfg.introLine);
		}
		//PRI = streak; SWITCH selects the trainer (clamped to the last entry)
		e.ins(PawnInstruction.Commands.PUSH_C, cfg.streakWorkId);
		e.sysreq(nWorkGet, 4);
		PawnInstruction sw = e.ins(PawnInstruction.Commands.SWITCH, 0);
		int n = cfg.trainerIds.length;
		PawnInstruction ct = e.caseTbl(n);
		sw.argumentCells[0] = ct.pointer - sw.pointer;
		sw.updateDisassembly();
		//per-trainer arms: push the 7 battle args (arg order per the measured
		//convention LAST push = FIRST arg: (0, tid, 0, 0, 0, 0, 0)), then join
		PawnInstruction[] caseStarts = new PawnInstruction[n];
		List<PawnInstruction> joinJumps = new ArrayList<>();
		for (int k = 0; k < n; k++) {
			caseStarts[k] = e.ins(PawnInstruction.Commands.PUSH_P_C, 0);
			for (int i = 0; i < 4; i++) {
				e.ins(PawnInstruction.Commands.PUSH_P_C, 0);
			}
			e.ins(PawnInstruction.Commands.PUSH_P_C, cfg.trainerIds[k]);
			e.ins(PawnInstruction.Commands.PUSH_P_C, 0);
			if (k < n - 1) {
				joinJumps.add(e.ins(PawnInstruction.Commands.JUMP, 0));
			}
		}
		//join: the battle itself + the zone-25 result frame
		PawnInstruction join = e.sysreq(nCore, 28);
		for (PawnInstruction j : joinJumps) {
			j.argumentCells[0] = join.pointer - j.pointer;
			j.updateDisassembly();
		}
		//CASETBL: case k -> its push arm; default (k >= n) -> the LAST arm
		int[] ctArgs = new int[2 + 2 * n];
		ctArgs[0] = n;
		ctArgs[1] = caseStarts[n - 1].pointer - (ct.pointer + 4);
		for (int k = 0; k < n; k++) {
			ctArgs[2 + 2 * k] = k;
			//offset is relative to the KEY cell (installCase's pair math)
			ctArgs[3 + 2 * k] = caseStarts[k].pointer - (ct.pointer + 4 + (2 + 2 * k) * 4);
		}
		ct.argumentCells = ctArgs;
		ct.argumentCount = ctArgs.length;
		ct.updateDisassembly();

		e.ins(PawnInstruction.Commands.STACK_P, -32);
		e.ins(PawnInstruction.Commands.ZERO_PRI, 0);
		e.ins(PawnInstruction.Commands.ADDR_ALT, -32);
		e.ins(PawnInstruction.Commands.FILL, 32);
		e.ins(PawnInstruction.Commands.PUSH_P_ADR, -32);
		e.sysreq(nResult, 4);
		e.ins(PawnInstruction.Commands.ADDR_P_PRI, -32);
		e.ins(PawnInstruction.Commands.LOAD_I, 0);
		e.ins(PawnInstruction.Commands.STACK_P, 32);
		PawnInstruction toLose = e.ins(PawnInstruction.Commands.JZER, 0); //cell 0 == 0 -> loss

		//WIN: streak++, base BP, optional milestone bonus, optional message
		e.ins(PawnInstruction.Commands.PUSH_C, cfg.streakWorkId);
		e.sysreq(nWorkGet, 4);
		e.ins(PawnInstruction.Commands.ADD_C, 1);
		e.ins(PawnInstruction.Commands.PUSH_PRI, 0);
		e.ins(PawnInstruction.Commands.PUSH_C, cfg.streakWorkId);
		e.sysreq(nWorkSet, 8);
		if (cfg.bpPerWin > 0) {
			emitBp(e, nGetBP, nSetBP, cfg.bpPerWin);
		}
		PawnInstruction skipBonus = null;
		if (cfg.milestone > 0 && cfg.milestoneBonus > 0) {
			e.ins(PawnInstruction.Commands.PUSH_C, cfg.streakWorkId);
			e.sysreq(nWorkGet, 4);
			e.ins(PawnInstruction.Commands.EQ_C_PRI, cfg.milestone);
			skipBonus = e.ins(PawnInstruction.Commands.JZER, 0);
			emitBp(e, nGetBP, nSetBP, cfg.milestoneBonus);
		}
		PawnInstruction afterBonus = null;
		if (cfg.winLine >= 0) {
			afterBonus = emitMsg(e, msgWrapper, cfg.winLine);
		}
		PawnInstruction winEnd = e.ins(PawnInstruction.Commands.ZERO_PRI, 0);
		if (afterBonus == null) {
			afterBonus = winEnd;
		}
		if (skipBonus != null) {
			skipBonus.argumentCells[0] = afterBonus.pointer - skipBonus.pointer;
			skipBonus.updateDisassembly();
		}
		e.ins(PawnInstruction.Commands.RETN, 0);

		//LOSS: reset the streak, optional message, optional white-out
		PawnInstruction loseStart = e.ins(PawnInstruction.Commands.PUSH_P_C, 0);
		toLose.argumentCells[0] = loseStart.pointer - toLose.pointer;
		toLose.updateDisassembly();
		e.ins(PawnInstruction.Commands.PUSH_C, cfg.streakWorkId);
		e.sysreq(nWorkSet, 8);
		if (cfg.loseLine >= 0) {
			emitMsg(e, msgWrapper, cfg.loseLine);
		}
		if (nLose >= 0) {
			e.sysreq(nLose, 0);
		}
		e.ins(PawnInstruction.Commands.ZERO_PRI, 0);
		e.ins(PawnInstruction.Commands.RETN, 0);

		return TalkerScriptWizard.installCase(measured, script, e.body);
	}

	/** The talker-shaped message call: push -1, 1, line, 12; CALL the wrapper.
	 *  Returns the first instruction of the call (a branch join point). */
	private static PawnInstruction emitMsg(PawnEmitter e, PawnInstruction wrapper, int line) {
		PawnInstruction first = e.ins(PawnInstruction.Commands.PUSH_P_C, -1);
		e.ins(PawnInstruction.Commands.PUSH_P_C, 1);
		e.ins(PawnInstruction.Commands.PUSH_P_C, line);
		e.ins(PawnInstruction.Commands.PUSH_P_C, 12);
		PawnInstruction call = e.ins(PawnInstruction.Commands.CALL, 0);
		call.argumentCells[0] = wrapper.pointer - call.pointer;
		call.updateDisassembly();
		return first;
	}

	/** The proven BP frame: PRI = GetBP; ALT = amount; ADD; SetBP(PRI). */
	private static void emitBp(PawnEmitter e, int nGetBP, int nSetBP, int amount) {
		e.sysreq(nGetBP, 0);
		e.ins(PawnInstruction.Commands.CONST_ALT, amount);
		e.ins(PawnInstruction.Commands.ADD, 0);
		e.ins(PawnInstruction.Commands.PUSH_PRI, 0);
		e.sysreq(nSetBP, 4);
	}

	private static void checkPacked(int v, String what) {
		if (v < Short.MIN_VALUE || v > Short.MAX_VALUE) {
			throw new IllegalArgumentException("The " + what + " (" + v + ") does not fit a packed argument.");
		}
	}

}
