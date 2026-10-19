package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.GauntletScriptWizard;
import ctrmap.formats.scripts.PawnInstruction;
import ctrmap.formats.scripts.ZoneScriptAnalyzer;
import ctrmap.scripts.GfHash;
import java.io.File;
import java.util.List;
import static ctrmap.formats.containers.ContainerBytes.subfile;

/**
 * Dry-run gate for the INDEPENDENT battle-challenge emitter, over every ORAS
 * zone script: append a 3-trainer challenge (streak + BP + milestone; messages
 * where the zone has a msg wrapper), reserialize, re-parse, and assert:
 * (a) the script still parses and its dispatch resolves; (b) the new case id
 * is registered, every pre-existing case still resolves, and the new case
 * target is the retail trampoline rather than the emitted PROC (a case is
 * reached by a JUMP, so a PROC there freezes the game when it returns -
 * DispatchTrampolineTest is the full corpus guard); (c) the body's
 * SYSREQ_N set covers _CallTrainerBattleCore / _BattleGetResult / WorkGet /
 * WorkSet / PlayerGetBP / PlayerSetBP; (d) the body's SWITCH resolves to its
 * CASETBL, every CASETBL arm and the default land on instruction boundaries
 * inside the body, and every JZER/JUMP target is an instruction boundary;
 * (e) each configured trainer id appears as a push constant. A 30-trainer
 * emission bounds the big-CASETBL path. Structural only - in-game behavior is
 * the user's gate.
 *
 * Usage: java ctrmap.tests.GauntletScriptTest &lt;path-to-zonedata-a013-garc&gt;
 */
public class GauntletScriptTest {

	public static void main(String[] args) throws Exception {
		File garc = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/1/3");
		GARC zo = new GARC(garc);
		int zones = zo.length - 2;
		int tested = 0, ok = 0, noDispatch = 0, withMsgs = 0, failures = 0;
		int[] mustCall = {GfHash.hash("_CallTrainerBattleCore"), GfHash.hash("_BattleGetResult"),
			GfHash.hash("WorkGet"), GfHash.hash("WorkSet"), GfHash.hash("PlayerGetBP"), GfHash.hash("PlayerSetBP")};
		final int[] TRAINERS = {51, 52, 61};

		boolean bigDone = false;
		byte[] probe = null;
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
			if (ZoneScriptAnalyzer.findDispatch(s) == null) {
				noDispatch++;
				continue;
			}
			tested++;
			if (probe == null) {
				probe = sub;
			}
			try {
				boolean hasMsg = ZoneScriptAnalyzer.findMsgWrapper(s) != null;
				if (hasMsg) {
					withMsgs++;
				}
				GauntletScriptWizard.Config cfg = new GauntletScriptWizard.Config();
				cfg.trainerIds = TRAINERS;
				cfg.bpPerWin = 3;
				cfg.milestone = 3;
				cfg.milestoneBonus = 20;
				if (hasMsg) {
					cfg.introLine = 0;
					cfg.winLine = 0;
					cfg.loseLine = 0;
				}
				cfg.loseWhiteout = true;
				//F241: the default is free in retail - measured here, zone by zone, not remembered
				if (GauntletScriptWizard.workVariablesUsed(s).contains(GauntletScriptWizard.DEFAULT_STREAK_WORK)) {
					throw new IllegalStateException("a retail script uses the default streak variable 0x"
							+ Integer.toHexString(GauntletScriptWizard.DEFAULT_STREAK_WORK));
				}
				List<Integer> before = ZoneScriptAnalyzer.listScriptIds(s);
				int newId = GauntletScriptWizard.addChallengeScript(NpcScriptGateTest.ORAS, s, cfg);
				byte[] out = s.getScriptBytes();

				GFLPawnScript re = new GFLPawnScript(out);
				re.decompressThis();
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
				verifyBody(re, caseSub(re, d, newId), mustCall, TRAINERS);
				//F241: the scan that asks whether a streak variable is taken sees the one just emitted
				if (!GauntletScriptWizard.workVariablesUsed(re).contains(cfg.streakWorkId)) {
					throw new IllegalStateException("the work-variable scan does not see streak 0x"
							+ Integer.toHexString(cfg.streakWorkId) + ", which the challenge it emitted uses");
				}
				ok++;

				//one big-emission bound: 30 trainers through the same zone
				if (!bigDone) {
					bigDone = true;
					GFLPawnScript s2 = new GFLPawnScript(sub);
					s2.decompressThis();
					GauntletScriptWizard.Config big = new GauntletScriptWizard.Config();
					big.trainerIds = new int[30];
					for (int i = 0; i < 30; i++) {
						big.trainerIds[i] = 51 + i;
					}
					big.bpPerWin = 1;
					int bigId = GauntletScriptWizard.addChallengeScript(NpcScriptGateTest.ORAS, s2, big);
					GFLPawnScript re2 = new GFLPawnScript(s2.getScriptBytes());
					re2.decompressThis();
					ZoneScriptAnalyzer.Dispatch d2 = ZoneScriptAnalyzer.findDispatch(re2);
					if (d2 == null || d2.cases.get(bigId) == null) {
						throw new IllegalStateException("30-trainer emission failed");
					}
					verifyBody(re2, caseSub(re2, d2, bigId), new int[]{mustCall[0], mustCall[1]}, big.trainerIds);
				}
			} catch (RuntimeException ex) {
				failures++;
				System.out.println("FAIL zone " + z + ": " + ex.getMessage());
				if (failures > 8) {
					break;
				}
			}
		}
		System.out.println("\nGauntlet emit: tested=" + tested + " (noDispatch " + noDispatch
				+ ", withMsgs " + withMsgs + ")  ok=" + ok + "  failures=" + failures);
		failures += aChallengeItCannotEmitIsRefused(probe);
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (failures > 0) {
			System.exit(1);
		}
	}

	/**
	 * A challenge the wizard cannot emit is refused in its own words, with the zone's script left as it was: no trainer,
	 * more than 64, a trainer id outside the game's, and a streak variable outside the save range.
	 *
	 * <p>Measured by the 2026-10-14 shared sweep (GauntletScriptWizard 157, dropped and swallowed, SURVIVED): this suite
	 * handed the wizard only challenges it can emit. Dropped, trainer 0 was pushed as a battle the game has no trainer
	 * for; the 3 siblings, never measured, are asked with it.
	 */
	static int aChallengeItCannotEmitIsRefused(byte[] sub) throws Exception {
		System.out.println("--- a challenge the wizard cannot emit is refused, the script left as it was");
		if (sub == null) {
			System.out.println("FAIL challenge refusals: no zone with a dispatch to ask them of");
			return 1;
		}
		//the bound of the game NpcScriptGateTest.ORAS was measured on
		int max = ctrmap.gamedef.GameProfile.of(ctrmap.gamedef.GameType.ORAS).trainerIdMax();
		int[] sixtyFive = new int[65];
		java.util.Arrays.fill(sixtyFive, 51);
		Object[][] cases = {
			{"no trainer", new int[0], GauntletScriptWizard.DEFAULT_STREAK_WORK, "The challenge needs at least one trainer."},
			{"65 trainers", sixtyFive, GauntletScriptWizard.DEFAULT_STREAK_WORK, "At most 64 trainers per challenge NPC."},
			{"trainer 0", new int[]{51, 0}, GauntletScriptWizard.DEFAULT_STREAK_WORK,
				"Trainer id 0 out of range (1.." + max + ")."},
			{"trainer " + (max + 1), new int[]{max + 1}, GauntletScriptWizard.DEFAULT_STREAK_WORK,
				"Trainer id " + (max + 1) + " out of range (1.." + max + ")."},
			{"streak variable 0x3FFF", new int[]{51}, 0x3FFF,
				"The streak work variable must be in the save range 0x4000..0x4FFF."}};
		int fails = 0;
		for (Object[] c : cases) {
			GFLPawnScript s = new GFLPawnScript(sub);
			s.decompressThis();
			byte[] before = s.getScriptBytes();
			GauntletScriptWizard.Config cfg = new GauntletScriptWizard.Config();
			cfg.trainerIds = (int[]) c[1];
			cfg.streakWorkId = (Integer) c[2];
			String said;
			try {
				said = "it emitted case " + GauntletScriptWizard.addChallengeScript(NpcScriptGateTest.ORAS, s, cfg);
			} catch (IllegalArgumentException refused) {
				said = refused.getMessage();
			} catch (RuntimeException other) {
				said = "it failed some other way: " + other;
			}
			boolean kept = java.util.Arrays.equals(before, s.getScriptBytes());
			if (!said.equals(c[3]) || !kept) {
				System.out.println("FAIL a challenge of " + c[0] + " is refused, the script left as it was: " + said
						+ (kept ? "" : " (and the script was changed)"));
				fails++;
			} else {
				System.out.println("  ok: a challenge of " + c[0] + " is refused, the script left as it was: " + said);
			}
		}
		return fails;
	}

	/**
	 * The subroutine a new dispatch case runs, asserting on the way that the
	 * case is entered the way the engine requires: its target is the retail
	 * trampoline (PUSH_P_C(0); CALL sub; JUMP epilogue), never the emitted PROC
	 * itself, which the engine reaches by a JUMP and cannot return from.
	 */
	static PawnInstruction caseSub(GFLPawnScript re, ZoneScriptAnalyzer.Dispatch d, int caseKey) {
		PawnInstruction target = d.cases.get(caseKey);
		if (target == null) {
			throw new IllegalStateException("case " + caseKey + " does not resolve");
		}
		if (target.getCommand() == PawnInstruction.Commands.PROC.ordinal()) {
			throw new IllegalStateException("case " + caseKey + " jumps straight into a PROC");
		}
		PawnInstruction sub = ZoneScriptAnalyzer.findCaseSubEntry(re, caseKey);
		if (sub == null) {
			throw new IllegalStateException("case " + caseKey + " does not reach a subroutine through its trampoline");
		}
		return sub;
	}

	/** Walks the case body to its final RETN, asserting natives, branch and
	 *  CASETBL integrity, and the trainer-id push constants. */
	static void verifyBody(GFLPawnScript re, PawnInstruction start, int[] mustCall, int[] trainerIds) {
		int gi = idxOf(re.instructions, start);
		if (gi < 0) {
			throw new IllegalStateException("case start not found");
		}
		java.util.Set<Integer> called = new java.util.HashSet<>();
		java.util.Set<Integer> pushed = new java.util.HashSet<>();
		PawnInstruction caseTbl = null;
		int retns = 0;
		int end = re.instructions.size();
		for (int i = gi; i < end; i++) {
			PawnInstruction ins = re.instructions.get(i);
			int cmd = ins.getCommand();
			if (cmd == 0x87) { //SYSREQ_N
				called.add(re.natives.get(ins.argumentCells[0]).data[1]);
			}
			if (ins.hasCompressedArgument) {
				pushed.add(ins.argumentCells[0]);
			}
			if (cmd == 0x81) { //SWITCH -> its CASETBL
				PawnInstruction tbl = re.lookupInstructionByPtr(ins.pointer + ins.argumentCells[0]);
				if (tbl == null || tbl.getCommand() != 0x82) {
					throw new IllegalStateException("SWITCH does not resolve to a CASETBL");
				}
				caseTbl = tbl;
			}
			if (cmd == 0x82) { //CASETBL: default + every arm on a boundary
				int defTarget = ins.pointer + 4 + ins.argumentCells[1];
				if (re.lookupInstructionByPtr(defTarget) == null) {
					throw new IllegalStateException("CASETBL default target unresolved");
				}
				for (int ai = 2; ai + 1 < ins.argumentCells.length; ai += 2) {
					int t = ins.pointer + 4 + ai * 4 + ins.argumentCells[ai + 1];
					if (re.lookupInstructionByPtr(t) == null) {
						throw new IllegalStateException("CASETBL arm " + ins.argumentCells[ai] + " unresolved");
					}
				}
			}
			if ((cmd >= 0x33 && cmd <= 0x40)) { //JUMP/JZER/conditionals
				if (re.lookupInstructionByPtr(ins.pointer + ins.argumentCells[0]) == null) {
					throw new IllegalStateException("branch at 0x" + Integer.toHexString(ins.pointer) + " unresolved");
				}
			}
			if (cmd == 0x30) { //RETN
				retns++;
				if (retns == 2) {
					end = i + 1; //the body has exactly two returns (win + lose)
				}
			}
		}
		if (retns < 2) {
			throw new IllegalStateException("body incomplete: " + retns + " RETN(s)");
		}
		if (caseTbl == null) {
			throw new IllegalStateException("body has no SWITCH/CASETBL");
		}
		for (int h : mustCall) {
			if (!called.contains(h)) {
				throw new IllegalStateException("body never calls native hash 0x" + Integer.toHexString(h));
			}
		}
		for (int tid : trainerIds) {
			if (!pushed.contains(tid)) {
				throw new IllegalStateException("trainer id " + tid + " not pushed in the body");
			}
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
}
