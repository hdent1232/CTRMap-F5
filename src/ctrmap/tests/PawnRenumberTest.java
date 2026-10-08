package ctrmap.tests;

import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.PawnInstruction;
import ctrmap.formats.scripts.PawnInstruction.Commands;
import java.io.File;
import java.util.Arrays;

/**
 * A script edit that renumbers carries every branch to the instruction it named, or is refused in
 * words before anything changes (F086).
 *
 * <p>WHY THIS SUITE EXISTS. A renumber - the Script Editor's insert, the talker wizard's new case,
 * the message and sign stubs - re-fixes every relative branch through a listener bound to the
 * instruction it targets. A branch whose target was not the start of an instruction had no such
 * instruction, and its listener caught the NullPointerException and wrote {@code -pointer}: a jump to
 * address 0, the HALT_P head, without a word. A case of a CASETBL kept its stale offset, a missing
 * default threw a bare NullPointerException, a script with no main came out of a renumber with main
 * at 0, the editor took any hex target typed into a jump, and an opcode past the table printed a
 * line to stdout and then threw from the disassembler.
 *
 * <p>ORDER: needs no dump. The scripts are built here, instruction by instruction.
 *
 * Usage: java ctrmap.tests.PawnRenumberTest [src root]
 */
public class PawnRenumberTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		aBranchFollowsItsTargetThroughARenumber();
		aBranchToNoInstructionIsRefusedNotSentToZero();
		aBranchToADeletedInstructionIsRefused();
		aCaseWithNoInstructionIsRefused();
		aTargetThatIsNoInstructionIsNotTaken();
		aScriptWithNoMainKeepsNoMain();
		anUnknownOpcodeIsShownNotThrown();
		everyEditPathAsksBeforeItChangesAnything(root);
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	static PawnInstruction ins(Commands c, int arg) {
		PawnInstruction i = new PawnInstruction(0, c.ordinal(), "");
		if (i.argumentCells.length > 0) {
			i.argumentCells[0] = arg;
		}
		return i;
	}

	/** A script of these instructions, laid out, main at 0, listeners bound. Branch cells are set by the caller. */
	static GFLPawnScript script(PawnInstruction... code) {
		GFLPawnScript s = new GFLPawnScript(HandedGameTest.amx());
		s.decompressThis();
		s.instructions.clear();
		s.instructions.addAll(Arrays.asList(code));
		GFLPawnScript.setPtrsByIndex(s.instructions);
		s.mainEntryPointDummy.argumentCells[0] = 0;
		return s;
	}

	static void aim(PawnInstruction branch, PawnInstruction at) {
		branch.argumentCells[0] = at.pointer - branch.pointer;
	}

	static void insertAt(GFLPawnScript s, int index) {
		s.instructions.add(index, ins(Commands.ZERO_PRI, 0));
		GFLPawnScript.setPtrsByIndex(s.instructions);
	}

	static void aBranchFollowsItsTargetThroughARenumber() {
		System.out.println("--- a branch follows the instruction it named through a renumber");
		PawnInstruction jump = ins(Commands.JUMP, 0);
		PawnInstruction retn = ins(Commands.RETN, 0);
		GFLPawnScript s = script(ins(Commands.ZERO_PRI, 0), jump, ins(Commands.ZERO_PRI, 0), retn);
		check(jump.pointer == 4 && retn.pointer == 16 && Commands.CASETBL.ordinal() == 0x82,
				"control: laid out at 0, 4, 12, 16 (jump at " + jump.pointer + ", RETN at " + retn.pointer + "), and CASETBL is 0x82");
		aim(jump, retn);
		s.setInstructionListeners();
		check(s.whyNotRenumberable().isEmpty(), "every branch can follow: '" + s.whyNotRenumberable() + "'");
		insertAt(s, 3);
		s.callInstructionListeners();
		check(jump.pointer + jump.argumentCells[0] == retn.pointer && retn.pointer == 20,
				"an instruction inserted between the jump and its target: the jump still lands on RETN, now at 0x" + Integer.toHexString(retn.pointer));
	}

	static void aBranchToNoInstructionIsRefusedNotSentToZero() {
		System.out.println("--- a branch to no instruction is refused, never sent to address 0 (F086)");
		PawnInstruction good = ins(Commands.JUMP, 0);
		PawnInstruction bad = ins(Commands.JUMP, 0);
		PawnInstruction retn = ins(Commands.RETN, 0);
		GFLPawnScript s = script(good, ins(Commands.ZERO_PRI, 0), bad, retn);
		aim(good, retn);
		bad.argumentCells[0] = (retn.pointer - 2) - bad.pointer;   //into nothing
		int goodCell = good.argumentCells[0];
		int badCell = bad.argumentCells[0];
		s.setInstructionListeners();
		String why = s.whyNotRenumberable();
		check(why.contains("0x" + Integer.toHexString(bad.pointer)) && why.contains("0x" + Integer.toHexString(retn.pointer - 2))
				&& why.contains("not the start of an instruction"), "the script says which branch cannot follow, and where to: " + why);

		insertAt(s, 1);   //between the good jump and its target, so a rewrite of it would show
		String refused = "";
		try {
			s.callInstructionListeners();
		} catch (IllegalStateException said) {
			refused = said.getMessage();
		}
		check(refused.contains("cannot be renumbered"), "the renumber is refused in words: " + refused);
		check(good.argumentCells[0] == goodCell, "before any cell is rewritten - the good jump's still says " + goodCell
				+ " (" + good.argumentCells[0] + ")");
		check(bad.argumentCells[0] == badCell, "and the bad one is not sent to address 0: " + bad.argumentCells[0]
				+ ", was " + badCell + " (-pointer would be " + (-bad.pointer) + ")");

		//the listener alone, as any renumber that skipped the script's check would reach it
		String thrown = "";
		try {
			bad.callJumpListeners();
		} catch (IllegalStateException said) {
			thrown = said.getMessage();
		}
		check(thrown.contains("cannot follow a renumber") && bad.argumentCells[0] == badCell,
				"and its listener, asked alone, throws rather than rewrite it: " + thrown + " (cell " + bad.argumentCells[0] + ")");
	}

	static void aBranchToADeletedInstructionIsRefused() {
		System.out.println("--- a branch to an instruction the script no longer holds is refused (F086)");
		PawnInstruction jump = ins(Commands.JUMP, 0);
		PawnInstruction target = ins(Commands.ZERO_PRI, 0);
		GFLPawnScript s = script(ins(Commands.ZERO_PRI, 0), jump, target, ins(Commands.RETN, 0));
		aim(jump, target);
		s.setInstructionListeners();
		int cell = jump.argumentCells[0];
		s.instructions.remove(target);
		GFLPawnScript.setPtrsByIndex(s.instructions);
		String refused = "";
		try {
			s.callInstructionListeners();
		} catch (IllegalStateException said) {
			refused = said.getMessage();
		}
		check(refused.contains("no longer in the script") && jump.argumentCells[0] == cell,
				"its target deleted, the jump is not carried to the stale address it held: " + refused);
	}

	/** A CASETBL with one case and a default, both aimed by absolute address. */
	static PawnInstruction casetbl() {
		PawnInstruction ct = new PawnInstruction(0, Commands.CASETBL.ordinal(), "");
		ct.hasCompressedArgument = false;
		ct.argumentCells = new int[4];
		ct.argumentCount = 4;
		ct.argumentCells[0] = 1;
		ct.argumentCells[2] = 7;
		return ct;
	}

	static void aimCase(PawnInstruction ct, int caseAt, int defaultAt) {
		ct.argumentCells[1] = defaultAt - (ct.pointer + 4);
		ct.argumentCells[3] = caseAt - (ct.pointer + 2 * 4) - 4;
	}

	static void aCaseWithNoInstructionIsRefused() {
		System.out.println("--- a case or default with no instruction to follow is refused (F086)");
		PawnInstruction ct = casetbl();
		PawnInstruction zero = ins(Commands.ZERO_PRI, 0);
		PawnInstruction retn = ins(Commands.RETN, 0);
		GFLPawnScript s = script(ct, zero, retn);
		aimCase(ct, zero.pointer, retn.pointer);
		s.setInstructionListeners();
		insertAt(s, 1);
		s.callInstructionListeners();
		check(ct.pointer + 2 * 4 + ct.argumentCells[3] + 4 == zero.pointer && ct.pointer + 4 + ct.argumentCells[1] == retn.pointer,
				"control: a case and a default that land on instructions follow them through a renumber");

		ct = casetbl();
		zero = ins(Commands.ZERO_PRI, 0);
		retn = ins(Commands.RETN, 0);
		s = script(ct, zero, retn);
		aimCase(ct, zero.pointer + 2, retn.pointer);
		int stale = ct.argumentCells[3];
		s.setInstructionListeners();
		check(s.whyNotRenumberable().contains("case 7"), "a case into nothing is named: " + s.whyNotRenumberable());
		String thrown = "";
		try {
			ct.callJumpListeners();
		} catch (IllegalStateException said) {
			thrown = said.getMessage();
		}
		check(thrown.contains("case 7") && ct.argumentCells[3] == stale,
				"and its listener throws instead of keeping the stale offset in silence: " + thrown);

		ct = casetbl();
		zero = ins(Commands.ZERO_PRI, 0);
		retn = ins(Commands.RETN, 0);
		s = script(ct, zero, retn);
		aimCase(ct, zero.pointer, retn.pointer + 2);
		s.setInstructionListeners();
		check(s.whyNotRenumberable().contains("the default"), "a default into nothing is named: " + s.whyNotRenumberable());
		thrown = "";
		try {
			ct.callJumpListeners();
		} catch (IllegalStateException said) {
			thrown = said.getMessage();
		} catch (NullPointerException bare) {
			thrown = "a bare " + bare;
		}
		check(thrown.contains("the default") && thrown.contains("cannot follow"), "and its listener says so, not a bare NullPointerException: " + thrown);
	}

	static void aTargetThatIsNoInstructionIsNotTaken() {
		System.out.println("--- a jump target typed into the editor that is no instruction is not taken (F086)");
		PawnInstruction jump = ins(Commands.JUMP, 0);
		PawnInstruction zero = ins(Commands.ZERO_PRI, 0);
		PawnInstruction retn = ins(Commands.RETN, 0);
		GFLPawnScript s = script(ins(Commands.ZERO_PRI, 0), jump, zero, retn);
		aim(jump, retn);
		s.setInstructionListeners();
		int cell = jump.argumentCells[0];
		check(!jump.setArgsFromString("=> 0x" + Integer.toHexString(retn.pointer - 2)) && jump.argumentCells[0] == cell,
				"a target inside nothing is refused and the jump keeps its target (" + jump.argumentCells[0] + ")");
		check(jump.setArgsFromString("=> 0x" + Integer.toHexString(zero.pointer)) && jump.pointer + jump.argumentCells[0] == zero.pointer,
				"one that is an instruction is taken");
		check(s.whyNotRenumberable().isEmpty(), "and the script can still be renumbered: '" + s.whyNotRenumberable() + "'");
	}

	static void aScriptWithNoMainKeepsNoMain() {
		System.out.println("--- a script with no main keeps no main through a renumber (F086)");
		GFLPawnScript s = script(ins(Commands.ZERO_PRI, 0), ins(Commands.RETN, 0));
		s.mainEntryPointDummy.argumentCells[0] = -1;
		String refused = "";
		try {
			s.setInstructionListeners();
			insertAt(s, 0);
			s.callInstructionListeners();
		} catch (IllegalStateException said) {
			refused = said.getMessage();
		}
		check(refused.isEmpty() && s.mainEntryPointDummy.argumentCells[0] == -1,
				"main stays -1 - the renumber made it 0 - and nothing is refused: " + s.mainEntryPointDummy.argumentCells[0] + " " + refused);
	}

	static void anUnknownOpcodeIsShownNotThrown() {
		System.out.println("--- an opcode past the table is shown as its cell, not thrown (F086)");
		String shown;
		try {
			shown = PawnInstruction.getDisassembly(new PawnInstruction(0, 0x7FF0, ""));
		} catch (RuntimeException thrown) {
			shown = "threw " + thrown;
		}
		check(shown.contains("UNKNOWN_OPCODE_0x7FF0"), "opcode 0x7FF0 reads " + shown);
		String known;
		try {
			known = PawnInstruction.getDisassembly(ins(Commands.RETN, 0));
		} catch (RuntimeException thrown) {
			known = "threw " + thrown;
		}
		check(known.startsWith("RETN"), "control: a listed opcode reads its name: " + known);
	}

	static void everyEditPathAsksBeforeItChangesAnything(File root) throws Exception {
		System.out.println("--- every path that renumbers asks first (F086)");
		String editor = SourceSeamTest.stripComments(DialogSeamTest.read(new File(root, "ctrmap/humaninterface/ScriptEditor.java")));
		int asks = editor.indexOf("scr.whyNotRenumberable()");
		int adds = editor.indexOf("scr.instructions.add(");
		check(asks >= 0 && adds > asks, "the Script Editor's insert asks before it adds an instruction (" + asks + " < " + adds + ")");
		String wizard = SourceSeamTest.stripComments(DialogSeamTest.read(new File(root, "ctrmap/formats/scripts/TalkerScriptWizard.java")));
		asks = wizard.indexOf("script.whyNotRenumberable()");
		adds = wizard.indexOf("script.instructions.addAll(body)");
		check(asks >= 0 && adds > asks, "the talker wizard asks before it adds the new case's body (" + asks + " < " + adds + ")");
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
