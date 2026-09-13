package ctrmap.formats.scripts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * The one walk over a Pawn routine and its callees, and the checks a script must
 * pass before one is transplanted into it.
 *
 * <p>Not: {@link MsgWrapperInjector} - that is one of the two transplants that
 * USE this, and it kept its own copy of this engine until now. The other is
 * {@link SignWrapperInjector}, and the two were measured at 293 of 326
 * substantive lines shared, including byte-identical copies of the closure walk,
 * the stub-insert preconditions and the branch-boundary check. The parts that
 * genuinely differ - which wrapper to find, the expected closure geometry, the
 * vanilla fingerprint, the data-segment step - stayed in the injectors.
 *
 * <p>WHY IT MATTERS MORE HERE THAN ELSEWHERE. This rewrites Pawn bytecode inside
 * zone scripts that are bound to the player's save: a wrong CALL operand is a
 * zone that hangs on entry. With two copies, a fix to the walk landed on one
 * injector and not the other, and the suite that would have caught the other was
 * itself a copy - so the sign side had already drifted into the worse-diagnostic
 * version of the same check.
 *
 * <p>REFUSALS ARE {@link Refused}, not either injector's own exception type.
 * Both of those are caught by name - by the editor and by their corpus suites -
 * so each injector catches what this throws and rethrows it as its own, with the
 * message and cause carried through. That keeps every existing caller honest
 * while there is only one body that decides.
 */
public final class PawnClosure {

	private PawnClosure() {
	}

	/** A transplant this engine will not perform, and why. */
	public static class Refused extends IllegalStateException {

		private static final long serialVersionUID = 1L;

		public Refused(String why) {
			super(why);
		}
	}

	private static final int OP_PROC = PawnInstruction.Commands.PROC.ordinal();
	private static final int OP_CALL = PawnInstruction.Commands.CALL.ordinal();
	private static final int OP_SWITCH = PawnInstruction.Commands.SWITCH.ordinal();
	private static final int OP_CASETBL = PawnInstruction.Commands.CASETBL.ordinal();
	private static final int OP_HALT_P = PawnInstruction.Commands.HALT_P.ordinal();

	/**
	 * What a closure walk collects: the subs it reached, verbatim, in the order
	 * it reached them.
	 *
	 * <p>Each injector extends this with what only it has - the message
	 * transplant carries a text buffer and its rewrite sites, the sign one does
	 * not - so the walk below fills the half they share.
	 */
	public static class Donor {

		public int wrapperPtr;
		/** Closure sub entry addresses in DFS preorder. */
		public final List<Integer> subPtrs = new ArrayList<>();
		/** Sub entry address to the verbatim cells of the whole sub. */
		public final Map<Integer, int[]> subCells = new HashMap<>();
		/** Sub entry address to {first instruction index, end index (exclusive)}. */
		public final Map<Integer, int[]> subRanges = new HashMap<>();
	}

	/**
	 * DFS preorder over CALL targets, collecting each sub's instruction range
	 * and verbatim cells; refuses call targets that do not land on a PROC.
	 */
	public static void dfsClosure(GFLPawnScript s, int entryPtr, Donor d) {
		if (d.subCells.containsKey(entryPtr)) {
			return;
		}
		PawnInstruction entry = s.lookupInstructionByPtr(entryPtr);
		if (entry == null || entry.getCommand() != OP_PROC) {
			throw new Refused("Donor CALL target 0x" + Integer.toHexString(entryPtr)
					+ " does not land on a PROC.");
		}
		int idx = s.instructions.indexOf(entry);
		int end = idx + 1;
		while (end < s.instructions.size() && s.instructions.get(end).getCommand() != OP_PROC) {
			end++;
		}
		int endPtr = (end < s.instructions.size()) ? s.instructions.get(end).pointer
				: (s.dataStart - s.instructionStart);
		int[] cells = new int[(endPtr - entryPtr) / 4];
		for (int i = idx; i < end; i++) {
			PawnInstruction ins = s.instructions.get(i);
			int[] raw = ins.getRaw();
			System.arraycopy(raw, 0, cells, (ins.pointer - entryPtr) / 4, raw.length);
		}
		d.subPtrs.add(entryPtr);
		d.subCells.put(entryPtr, cells);
		d.subRanges.put(entryPtr, new int[]{idx, end});
		for (int i = idx; i < end; i++) {
			PawnInstruction ins = s.instructions.get(i);
			if (ins.getCommand() == OP_CALL && ins.argumentCells.length == 1) {
				dfsClosure(s, ins.pointer + ins.argumentCells[0], d);
			}
		}
	}

	/** One 32-bit cell into a CRC, little end first - the canonical order. */
	public static void crcCell(CRC32 crc, int v) {
		crc.update(v & 0xFF);
		crc.update((v >> 8) & 0xFF);
		crc.update((v >> 16) & 0xFF);
		crc.update((v >> 24) & 0xFF);
	}

	/**
	 * What a target script must be before a yield stub can be inserted at 0x4:
	 * a HALT_P at 0 and a PROC at 4, no branch or case target at or below 4
	 * (such a target would be redirected into the inserted stub), and every
	 * branch/case target landing on an instruction boundary.
	 *
	 * <p>The insert renumbers through per-instruction listeners, so a target
	 * inside an instruction - a user-modified jump into a CASETBL interior, say
	 * - has no listener owner and would corrupt or NPE later.
	 */
	public static void validateStubInsertPreconditions(GFLPawnScript t) {
		if (t.instructions.size() < 2) {
			throw new Refused("The target script is too short to insert the yield stub.");
		}
		PawnInstruction i0 = t.instructions.get(0);
		PawnInstruction i1 = t.instructions.get(1);
		if (i0.pointer != 0 || i0.getCommand() != OP_HALT_P || !i0.hasCompressedArgument
				|| i1.pointer != 4 || i1.getCommand() != OP_PROC) {
			throw new Refused("Unexpected code head (need HALT_P at 0 and PROC at 4).");
		}
		for (PawnInstruction ins : t.instructions) {
			int cmd = ins.getCommand();
			if (PawnInstruction.checkJmp(ins) || cmd == OP_SWITCH) {
				if (ins.argumentCells.length < 1 || ins.pointer + ins.argumentCells[0] <= 4) {
					throw new Refused("A branch at 0x" + Integer.toHexString(ins.pointer)
							+ " targets the code head.");
				}
				requireInstructionBoundary(t, ins.pointer + ins.argumentCells[0], ins.pointer);
			} else if (cmd == OP_CASETBL) {
				if (ins.argumentCells.length < 2 || (ins.pointer + 4) + ins.argumentCells[1] <= 4) {
					throw new Refused("A CASETBL at 0x" + Integer.toHexString(ins.pointer)
							+ " targets the code head.");
				}
				requireInstructionBoundary(t, (ins.pointer + 4) + ins.argumentCells[1], ins.pointer);
				for (int k = 2; k + 1 < ins.argumentCells.length; k += 2) {
					int tgt = (ins.pointer + k * 4) + ins.argumentCells[k + 1] + 4;
					if (tgt <= 4) {
						throw new Refused("A CASETBL at 0x" + Integer.toHexString(ins.pointer)
								+ " targets the code head.");
					}
					requireInstructionBoundary(t, tgt, ins.pointer);
				}
			}
		}
	}

	/**
	 * Refuses branch/case targets that do not resolve to an instruction boundary
	 * of the target script.
	 */
	public static void requireInstructionBoundary(GFLPawnScript t, int target, int fromPtr) {
		if (t.lookupInstructionByPtr(target) == null) {
			throw new Refused("A branch at 0x" + Integer.toHexString(fromPtr) + " targets 0x"
					+ Integer.toHexString(target) + ", which is not an instruction boundary.");
		}
	}
}
