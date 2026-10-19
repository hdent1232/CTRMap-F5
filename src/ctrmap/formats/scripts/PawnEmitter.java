package ctrmap.formats.scripts;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a new subroutine at provisional pointers past a script's last instruction, each pointer
 * advanced by the instruction's real encoded size, for {@link TalkerScriptWizard#installCase} to place.
 *
 * <p>ONE BUILDER. The battle-challenge wizard kept this as a private class and the BP giver summed its
 * pointers by hand beside it - two spellings of one encoding, and a third template (the one-time giver,
 * F085) would have been a third.
 *
 * <p>THE ONE-TIME GUARD is retail's, measured 2026-10-09 over the 536 zone scripts: of the 155 subs that
 * call a zone's give-item wrapper, 78 ask {@code FlagGet} and {@code FlagSet} in the same sub - zone 8's
 * {@code PUSH flag; FlagGet; JNZ past; ... give ...; PUSH flag; FlagSet} - so a second talk finds the
 * flag set and gives nothing.
 *
 * <p>Not: {@link TalkerScriptWizard} - that installs a finished body into a script's dispatch and is
 * handed one; this is what builds it.
 */
final class PawnEmitter {

	final List<PawnInstruction> body = new ArrayList<>();
	int ptr;

	PawnEmitter(GFLPawnScript script) {
		PawnInstruction last = script.instructions.get(script.instructions.size() - 1);
		ptr = last.pointer + 4 + (last.hasCompressedArgument ? 0 : last.argumentCount * 4);
	}

	PawnInstruction ins(PawnInstruction.Commands cmd, int arg) {
		PawnInstruction i = new PawnInstruction(ptr, cmd.ordinal(), "");
		if (i.argumentCells.length > 0) {
			i.argumentCells[0] = arg;
		}
		i.updateDisassembly();
		body.add(i);
		ptr += 4 + (i.hasCompressedArgument ? 0 : i.argumentCount * 4);
		return i;
	}

	PawnInstruction sysreq(int nativeIndex, int argBytes) {
		PawnInstruction i = new PawnInstruction(ptr, PawnInstruction.Commands.SYSREQ_N.ordinal(), "");
		i.argumentCells[0] = nativeIndex;
		i.argumentCells[1] = argBytes;
		i.updateDisassembly();
		body.add(i);
		ptr += 12;
		return i;
	}

	/** A CALL to {@code target}, relative to the CALL itself. */
	PawnInstruction call(PawnInstruction target) {
		PawnInstruction call = ins(PawnInstruction.Commands.CALL, 0);
		call.argumentCells[0] = target.pointer - call.pointer;
		call.updateDisassembly();
		return call;
	}

	/**
	 * natives[] index for a name hash, appending a {0, hash} entry if absent. Package-private: it
	 * writes the script and has no gate of its own, so only a template that was handed a
	 * {@link NpcTemplates.Measured} may call it (F085). Here, with the builder, rather than in the
	 * BP giver that wrote it first: kept there, the builder and the BP giver each called the other,
	 * a class cycle PackageCycleTest measured on 2026-10-10.
	 */
	static int findOrAddNative(GFLPawnScript script, int hash) {
		for (int i = 0; i < script.natives.size(); i++) {
			if (script.natives.get(i).data[1] == hash) {
				return i;
			}
		}
		script.natives.add(new PawnPrefixEntry(8, PawnPrefixEntry.Type.NATIVE, new int[]{0, hash}));
		return script.natives.size() - 1;
	}

	/** A CASETBL for n cases; argument cells are filled by the caller. */
	PawnInstruction caseTbl(int n) {
		PawnInstruction i = new PawnInstruction(ptr, PawnInstruction.Commands.CASETBL.ordinal(), "");
		i.argumentCells = new int[2 + 2 * n];
		i.argumentCount = i.argumentCells.length;
		i.updateDisassembly();
		body.add(i);
		ptr += 4 + (2 + 2 * n) * 4;
		return i;
	}

	/**
	 * Opens retail's one-time guard: {@code PUSH flag; FlagGet; JNZ <past the body>}. The JNZ lands
	 * where {@link #closeOnce} leaves off. The flag must fit a packed argument.
	 */
	PawnInstruction openOnce(GFLPawnScript script, int flag) {
		if (flag < 1 || flag > Short.MAX_VALUE) {
			throw new IllegalArgumentException("Event flag " + flag + " does not fit a packed argument (1.." + Short.MAX_VALUE + ").");
		}
		ins(PawnInstruction.Commands.PUSH_P_C, flag);
		sysreq(PawnEmitter.findOrAddNative(script, ctrmap.scripts.GfHash.hashForName("FlagGet")), 4);
		return ins(PawnInstruction.Commands.JNZ, 0);
	}

	/**
	 * Closes it: {@code PUSH flag; FlagSet}, and the guard's JNZ lands on the instruction emitted next -
	 * which the caller emits, so a guard is never left landing nowhere.
	 */
	void closeOnce(GFLPawnScript script, PawnInstruction open, int flag) {
		ins(PawnInstruction.Commands.PUSH_P_C, flag);
		sysreq(PawnEmitter.findOrAddNative(script, ctrmap.scripts.GfHash.hashForName("FlagSet")), 4);
		open.argumentCells[0] = ptr - open.pointer;
		open.updateDisassembly();
	}
}
