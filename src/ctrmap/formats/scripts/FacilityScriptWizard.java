package ctrmap.formats.scripts;

import ctrmap.scripts.GfHash;
import java.util.ArrayList;
import java.util.List;

/**
 * Emits Battle-facility script subroutines that call engine natives directly,
 * built on the reverse-engineered ORAS native table (see {@link GfHash}) and
 * installed as new dispatch cases via {@link TalkerScriptWizard#installCase}.
 *
 * <p>The BP grant frame is copied verbatim from the retail Battle Maison /
 * Battle Institute lobby scripts (zones 448 and 517), which award BP with
 * exactly {@code PlayerGetBP(); ALT = amount; ADD; PlayerSetBP(sum)} - the only
 * change here is loading the amount as a constant instead of a call parameter,
 * so the routine is self-contained and works in any zone that has a dispatch.
 */
public class FacilityScriptWizard {

	/** Adds {@code amount} Battle Points to the player (clamped by the engine to 9999). */
	public static int addGiveBpScript(GFLPawnScript script, int amount) {
		script.decompressThis();
		if (amount == 0) {
			throw new IllegalArgumentException("BP amount must be non-zero.");
		}
		int getBP = findOrAddNative(script, GfHash.hashForName("PlayerGetBP"));
		int setBP = findOrAddNative(script, GfHash.hashForName("PlayerSetBP"));

		PawnInstruction last = script.instructions.get(script.instructions.size() - 1);
		int ptr = last.pointer + 4 + (last.hasCompressedArgument ? 0 : last.argumentCount * 4);
		List<PawnInstruction> body = new ArrayList<>();
		body.add(makeIns(PawnInstruction.Commands.PROC, ptr, 0));
		ptr += 4;
		body.add(makeSysreq(ptr, getBP, 0));            // PRI = current BP
		ptr += 12;
		body.add(makeIns(PawnInstruction.Commands.CONST_ALT, ptr, amount)); // ALT = amount
		ptr += 8;
		body.add(makeIns(PawnInstruction.Commands.ADD, ptr, 0));            // PRI = BP + amount
		ptr += 4;
		body.add(makeIns(PawnInstruction.Commands.PUSH_PRI, ptr, 0));       // push new value
		ptr += 4;
		body.add(makeSysreq(ptr, setBP, 4));            // PlayerSetBP(new)
		ptr += 12;
		body.add(makeIns(PawnInstruction.Commands.ZERO_PRI, ptr, 0));
		ptr += 4;
		body.add(makeIns(PawnInstruction.Commands.RETN, ptr, 0));
		return TalkerScriptWizard.installCase(script, body);
	}

	/** natives[] index for a name hash, appending a {0, hash} entry if absent. */
	public static int findOrAddNative(GFLPawnScript script, int hash) {
		for (int i = 0; i < script.natives.size(); i++) {
			if (script.natives.get(i).data[1] == hash) {
				return i;
			}
		}
		script.natives.add(new PawnPrefixEntry(8, PawnPrefixEntry.Type.NATIVE, new int[]{0, hash}));
		return script.natives.size() - 1;
	}

	private static PawnInstruction makeIns(PawnInstruction.Commands cmd, int ptr, int arg) {
		PawnInstruction ins = new PawnInstruction(ptr, cmd.ordinal(), "");
		if (ins.argumentCells.length > 0) {
			ins.argumentCells[0] = arg;
		}
		ins.updateDisassembly();
		return ins;
	}

	/** SYSREQ_N(nativeIndex, argBytes) - a full-arg op with two cells. */
	private static PawnInstruction makeSysreq(int ptr, int nativeIndex, int argBytes) {
		PawnInstruction ins = new PawnInstruction(ptr, PawnInstruction.Commands.SYSREQ_N.ordinal(), "");
		ins.argumentCells[0] = nativeIndex;
		ins.argumentCells[1] = argBytes;
		ins.updateDisassembly();
		return ins;
	}
}
