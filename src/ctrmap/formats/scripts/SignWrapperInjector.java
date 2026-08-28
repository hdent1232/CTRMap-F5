package ctrmap.formats.scripts;

import ctrmap.humaninterface.ScriptEditor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Headless transplantation
 * of the vanilla sign-display routine (the closure ZoneScriptAnalyzer.
 * findSignWrapper locates) from a donor zone script into zone scripts that
 * lack it, so NpcTemplates.addSignScript works in every zone.
 *
 * Port of MsgWrapperInjector (the proven, 308/308-corpus-validated message
 * routine transplant) per SPEC_SIGNWRAP.md. The sign closure is strictly
 * simpler than the message closure:
 * - 6 subs / 121 cells (DFS cells {21, 28, 34, 20, 11, 7}), yield stub at
 *   DFS index 4, non-stub transplant block = 5 subs / 110 cells;
 * - byte-canonical across all 69 vanilla carrier zones (canonical CRC32
 *   0x63B1DA11 after SYSREQ_N-hash / CALL-ordinal canonicalization);
 * - fully self-contained, independent of the msg wrapper;
 * - ZERO data-segment address references: no buffer append, no rewrite
 *   sites, no packed-address range constraint - the target data segment is
 *   left completely untouched.
 *
 * The port therefore keeps only: natives dedupe/append, the stub-at-0x4
 * ensure (verbatim from the template; its preconditions were proven on every
 * stub-less zone by MsgWrapperInjectTest) and the verbatim block append with
 * SYSREQ_N index remap + CALL operand recompute. The msg injector's step 3
 * (buffer cells), REWRITE_SITES, rewriteDeltaByPos, the rewrites-count guard
 * and the 16-bit buffer-rebase guard are deliberately DELETED, not
 * parameterized to zero.
 */
public class SignWrapperInjector {

	/**
	 * The canonical donor zone (ZoneData index) - same as
	 * MsgWrapperInjector.PREFERRED_DONOR_ZONE; zone 24 carries both routines,
	 * so both injectors share one canonical donor.
	 */
	public static final int PREFERRED_DONOR_ZONE = 24;

	/**
	 * Name hash of the yield-frame native called by the stub at code address
	 * 0x4 (natives[i].data[1]), stable game-wide (=
	 * MsgWrapperInjector.YIELD_NATIVE_HASH).
	 */
	public static final int YIELD_NATIVE_HASH = 0x0B13A389;

	private static final int OP_PROC = PawnInstruction.Commands.PROC.ordinal();
	private static final int OP_CALL = PawnInstruction.Commands.CALL.ordinal();
	private static final int OP_SWITCH = PawnInstruction.Commands.SWITCH.ordinal();
	private static final int OP_CASETBL = PawnInstruction.Commands.CASETBL.ordinal();
	private static final int OP_SYSREQ_N = PawnInstruction.Commands.SYSREQ_N.ordinal();
	private static final int OP_HALT_P = PawnInstruction.Commands.HALT_P.ordinal();
	private static final int OP_CALL_PRI = PawnInstruction.Commands.CALL_PRI.ordinal();
	private static final int OP_JUMP_PRI = PawnInstruction.Commands.JUMP_PRI.ordinal();
	private static final int OP_SYSREQ_PRI = PawnInstruction.Commands.SYSREQ_PRI.ordinal();
	private static final int OP_SYSREQ_C = PawnInstruction.Commands.SYSREQ_C.ordinal();
	private static final int OP_SYSREQ_D = PawnInstruction.Commands.SYSREQ_D.ordinal();
	private static final int OP_SYSREQ_ND = PawnInstruction.Commands.SYSREQ_ND.ordinal();

	/**
	 * Prefix-entry width every ORAS zone script uses; the appended natives
	 * entries are 8-byte {0, nameHash} records of this width.
	 */
	private static final int EXPECTED_DEFSIZE = 8;

	/**
	 * Cell counts of the 6 closure subs in DFS preorder from the sign wrapper
	 * (donor 24 entries: 2D0, 260, 958, 9E0, 4, 468). Index STUB_DFS_INDEX is
	 * the ptr-0x4 yield stub, which is never transplanted (it is ensured at
	 * 0x4 of the target instead).
	 */
	private static final int[] CLOSURE_SUB_CELLS = {21, 28, 34, 20, 11, 7};
	private static final int STUB_DFS_INDEX = 4;
	private static final int STUB_CELL_COUNT = 11;

	/**
	 * CRC32 over the donor closure's position-independent content: every cell
	 * verbatim except the two fixup classes, which are canonicalized
	 * (SYSREQ_N native index -> the 32-bit name hash behind it + argBytes,
	 * CALL operand -> the target sub's DFS ordinal). No buffer terms - the
	 * sign closure has no data references. Computed from the PRISTINE ORAS
	 * zone 24; every one of the 69 vanilla sign zones produces this same
	 * value, so any user-modified closure content is rejected as a donor even
	 * when the shape checks still pass.
	 */
	private static final long VANILLA_CLOSURE_CRC32 = 0x63B1DA11L;

	/**
	 * Thrown when the donor or target violates the measured structural
	 * invariants - the script is refused rather than risking a questionable
	 * transplant. All precondition guards throw before the target is
	 * modified; the final post-injection self-check is the only exception
	 * (see injectSignWrapper's throws documentation).
	 */
	public static class InjectionException extends IllegalStateException {

		public InjectionException(String message) {
			super(message);
		}
	}

	/**
	 * Whether the script is eligible for injection: it has the main script
	 * dispatch (somewhere to hang sign cases off) and no sign wrapper yet.
	 */
	public static boolean canInject(GFLPawnScript script) {
		if (script == null) {
			return false;
		}
		script.decompressThis();
		return ZoneScriptAnalyzer.findDispatch(script) != null && ZoneScriptAnalyzer.findSignWrapper(script) == null;
	}

	/**
	 * Validates the donor against the measured closure geometry.
	 *
	 * @throws InjectionException when the donor does not qualify
	 */
	public static void validateDonor(GFLPawnScript donor) {
		buildDonor(donor);
	}

	/**
	 * The number of instructions injectSignWrapper would add to the target
	 * (the 5 transplanted subs, plus the 8-instruction stub if the target
	 * lacks it). Neither script is modified.
	 */
	public static int countInjectedInstructions(GFLPawnScript target, GFLPawnScript donor) {
		Donor d = buildDonor(donor);
		int count = 0;
		for (Integer entryPtr : d.subPtrs) {
			if (entryPtr == 4) {
				continue;
			}
			int[] r = d.subRanges.get(entryPtr);
			count += r[1] - r[0];
		}
		if (target != null) {
			target.decompressThis();
			if (!MsgWrapperInjector.hasStubAt4(target)) {
				count += 8;
			}
		}
		return count;
	}

	/**
	 * Supplier of parsed zone scripts by ZoneData index - reuses
	 * MsgWrapperInjector.ScriptSource so callers can share one supplier.
	 */
	public static GFLPawnScript pickDonor(MsgWrapperInjector.ScriptSource source, int zoneCount) {
		if (source != null) {
			if (PREFERRED_DONOR_ZONE < zoneCount) {
				GFLPawnScript donor = tryDonor(source, PREFERRED_DONOR_ZONE);
				if (donor != null) {
					return donor;
				}
			}
			for (int i = 0; i < zoneCount; i++) {
				if (i == PREFERRED_DONOR_ZONE) {
					continue;
				}
				GFLPawnScript donor = tryDonor(source, i);
				if (donor != null) {
					return donor;
				}
			}
		}
		throw new InjectionException("No zone script validates as a sign-routine donor.");
	}

	private static GFLPawnScript tryDonor(MsgWrapperInjector.ScriptSource source, int index) {
		try {
			GFLPawnScript donor = source.get(index);
			if (donor != null) {
				buildDonor(donor);
				return donor;
			}
		} catch (RuntimeException ex) {
			//not a valid donor (or unreadable) - keep scanning
		}
		return null;
	}

	/**
	 * Injects the donor's sign-routine closure into the target script (in
	 * memory; the caller decides where the bytes go):
	 *
	 * 1. append the missing natives (dedup'd by name hash, append-only),
	 * 2. ensure the yield stub at code address 0x4 (insert + renumber via the
	 * proven setInstructionListeners/setPtrsByIndex/callInstructionListeners
	 * idiom, with the manual publics/mainEntryPoint fixups no listener owns),
	 * 3. append the 5 transplant subs at the end of the code section with the
	 * SYSREQ_N/CALL fixups. There is NO data-segment step: the sign closure
	 * addresses no data, and the target data segment is left untouched.
	 *
	 * The dispatch CASETBL, all pre-existing code and data cells, the
	 * existing natives entries and the publics hashes are left untouched
	 * (publics addresses and the main entry point shift by 0x2C when the
	 * stub is inserted). Sign-carrying scripts are refused, which keeps the
	 * operation idempotent.
	 *
	 * @param target a zone script with a dispatch and no sign wrapper,
	 * modified in place
	 * @param donor a validated donor (see pickDonor/validateDonor)
	 * @return the code address of the injected wrapper entry PROC
	 * @throws InjectionException on any structural surprise. Every
	 * donor/target validation guard throws before the target is modified.
	 * The final post-injection wrapper self-check runs after mutation began -
	 * it is unreachable for any donor that passed buildDonor, but callers
	 * that must be robust even against it should inject into a disposable
	 * copy and commit it on success.
	 */
	public static int injectSignWrapper(GFLPawnScript target, GFLPawnScript donor) {
		if (target == null) {
			throw new InjectionException("No target script.");
		}
		target.decompressThis();
		if (target == donor) {
			throw new InjectionException("The donor and the target are the same script.");
		}
		if (ZoneScriptAnalyzer.findDispatch(target) == null) {
			throw new InjectionException("The target script has no script dispatch (main SWITCH/CASETBL).");
		}
		if (ZoneScriptAnalyzer.findSignWrapper(target) != null) {
			throw new InjectionException("The target script already has a sign display routine.");
		}
		Donor d = buildDonor(donor);
		if (target.defsize != EXPECTED_DEFSIZE) {
			throw new InjectionException("Unexpected target prefix entry size " + target.defsize + ".");
		}
		for (int i = 0; i < target.natives.size(); i++) {
			if (target.natives.get(i).data.length < 2) {
				throw new InjectionException("Target native entry " + i + " is malformed.");
			}
		}
		boolean needStub = !MsgWrapperInjector.hasStubAt4(target);
		if (needStub) {
			validateStubInsertPreconditions(target);
		} else {
			//the stub shape at 0x4 must actually call the yield native
			int stubNatIdx = target.instructions.get(3).argumentCells[0];
			if (stubNatIdx < 0 || stubNatIdx >= target.natives.size()
					|| target.natives.get(stubNatIdx).data[1] != YIELD_NATIVE_HASH) {
				throw new InjectionException("The stub-shaped sub at 0x4 does not call the yield native.");
			}
		}

		//---- 1. natives (append-only, dedupe by name hash, first occurrence wins)
		Map<Integer, Integer> hashToTarget = new HashMap<>();
		for (int i = 0; i < target.natives.size(); i++) {
			int hash = target.natives.get(i).data[1];
			if (!hashToTarget.containsKey(hash)) {
				hashToTarget.put(hash, i);
			}
		}
		Integer yieldIdx = hashToTarget.get(YIELD_NATIVE_HASH);
		if (needStub && yieldIdx == null) {
			yieldIdx = target.natives.size();
			int[] data = new int[target.defsize / 4];
			data[1] = YIELD_NATIVE_HASH;
			target.natives.add(new PawnPrefixEntry(target.defsize, PawnPrefixEntry.Type.NATIVE, data));
			hashToTarget.put(YIELD_NATIVE_HASH, yieldIdx);
		}
		Map<Integer, Integer> donorIdxToTargetIdx = new HashMap<>();
		for (Integer entryPtr : d.subPtrs) {
			if (entryPtr == 4) {
				continue; //the stub is handled separately
			}
			int[] r = d.subRanges.get(entryPtr);
			for (int i = r[0]; i < r[1]; i++) {
				PawnInstruction ins = donor.instructions.get(i);
				if (ins.getCommand() == OP_SYSREQ_N) {
					int dIdx = ins.argumentCells[0];
					if (!donorIdxToTargetIdx.containsKey(dIdx)) {
						int hash = donor.natives.get(dIdx).data[1];
						Integer tIdx = hashToTarget.get(hash);
						if (tIdx == null) {
							tIdx = target.natives.size();
							target.natives.add(new PawnPrefixEntry(target.defsize, PawnPrefixEntry.Type.NATIVE, donor.natives.get(dIdx).data.clone()));
							hashToTarget.put(hash, tIdx);
						}
						donorIdxToTargetIdx.put(dIdx, tIdx);
					}
				}
			}
		}

		//---- 2. yield stub at code address 0x4 (donor sub_4 cells verbatim,
		//SYSREQ_N index cell re-pointed at the target's yield native)
		if (needStub) {
			target.setInstructionListeners(); //snapshot every branch target as an object
			int[] stubCells = d.subCells.get(4).clone();
			stubCells[3] = yieldIdx; //SYSREQ_N native-index cell
			int stubShiftBytes = stubCells.length * 4; //0x2C
			int[] all = new int[stubCells.length + 1];
			System.arraycopy(stubCells, 0, all, 1, stubCells.length);
			List<PawnInstruction> stubIns = new ArrayList<>();
			int ci = 1;
			while (ci < all.length) {
				PawnInstruction ins = new PawnInstruction(ci * 4, all, target);
				stubIns.add(ins);
				ci += 1 + (ins.hasCompressedArgument ? 0 : ins.argumentCount);
			}
			target.instructions.addAll(1, stubIns);
			ScriptEditor.setPtrsByIndex(target.instructions);
			target.callInstructionListeners();
			for (PawnInstruction ins : stubIns) {
				ins.setParent(target);
			}
			//publics hold absolute code addresses no listener owns - all of
			//them sit after address 4, so they shift uniformly
			for (PawnPrefixEntry p : target.publics) {
				p.data[0] += stubShiftBytes;
			}
			target.mainEntryPoint = target.mainEntryPointDummy.argumentCells[0];
			target.updateRaw();
			target.dataStart += stubShiftBytes;
			target.heapStart += stubShiftBytes;
		}

		//---- 3. closure block at the end of the code section, subs packed
		//contiguously in donor DFS preorder (the wrapper entry comes first);
		//there is NO buffer step - the sign closure has no data references
		int base = target.dataStart - target.instructionStart;
		Map<Integer, Integer> newEntry = new HashMap<>();
		int off = 0;
		for (Integer entryPtr : d.subPtrs) {
			if (entryPtr == 4) {
				continue;
			}
			newEntry.put(entryPtr, base + off);
			off += d.subCells.get(entryPtr).length * 4;
		}
		int[] block = new int[off / 4];
		for (Integer entryPtr : d.subPtrs) {
			if (entryPtr == 4) {
				continue;
			}
			int[] cells = d.subCells.get(entryPtr);
			int blockOff = (newEntry.get(entryPtr) - base) / 4;
			System.arraycopy(cells, 0, block, blockOff, cells.length);
			int[] r = d.subRanges.get(entryPtr);
			for (int i = r[0]; i < r[1]; i++) {
				PawnInstruction ins = donor.instructions.get(i);
				int cellIdxInSub = (ins.pointer - entryPtr) / 4;
				int cmd = ins.getCommand();
				if (cmd == OP_SYSREQ_N) {
					block[blockOff + cellIdxInSub + 1] = donorIdxToTargetIdx.get(ins.argumentCells[0]);
				} else if (cmd == OP_CALL) {
					int donorTarget = ins.pointer + ins.argumentCells[0];
					int newInsPtr = newEntry.get(entryPtr) + (ins.pointer - entryPtr);
					int newTarget = (donorTarget == 4) ? 4 : newEntry.get(donorTarget);
					block[blockOff + cellIdxInSub + 1] = newTarget - newInsPtr;
				}
			}
		}
		int[] all = new int[base / 4 + block.length];
		System.arraycopy(block, 0, all, base / 4, block.length);
		int ci = 0;
		while (ci < block.length) {
			int ptr = base + ci * 4;
			PawnInstruction ins = new PawnInstruction(ptr, all, target);
			target.instructions.add(ins);
			ci += 1 + (ins.hasCompressedArgument ? 0 : ins.argumentCount);
		}

		//self-check: the analyzer must now see the wrapper at the block base
		int predicted = newEntry.get(d.wrapperPtr);
		PawnInstruction w = ZoneScriptAnalyzer.findSignWrapper(target);
		if (w == null || w.pointer != predicted) {
			throw new InjectionException("Post-injection verification failed: sign wrapper "
					+ (w == null ? "not found" : "at unexpected address 0x" + Integer.toHexString(w.pointer)) + ".");
		}
		return predicted;
	}

	/**
	 * The insert-at-4 preconditions for stub-less targets (verbatim from the
	 * template): a 1-cell HALT_P at 0 and a PROC at 4, no branch/case target
	 * at or below 4 anywhere, and every branch/case target landing on an
	 * instruction boundary.
	 */
	private static void validateStubInsertPreconditions(GFLPawnScript t) {
		if (t.instructions.size() < 2) {
			throw new InjectionException("The target script is too short to insert the yield stub.");
		}
		PawnInstruction i0 = t.instructions.get(0);
		PawnInstruction i1 = t.instructions.get(1);
		if (i0.pointer != 0 || i0.getCommand() != OP_HALT_P || !i0.hasCompressedArgument
				|| i1.pointer != 4 || i1.getCommand() != OP_PROC) {
			throw new InjectionException("Unexpected code head (need HALT_P at 0 and PROC at 4).");
		}
		for (PawnInstruction ins : t.instructions) {
			int cmd = ins.getCommand();
			if (PawnInstruction.checkJmp(ins) || cmd == OP_SWITCH) {
				if (ins.argumentCells.length < 1 || ins.pointer + ins.argumentCells[0] <= 4) {
					throw new InjectionException("A branch at 0x" + Integer.toHexString(ins.pointer) + " targets the code head.");
				}
				requireInstructionBoundary(t, ins.pointer + ins.argumentCells[0], ins.pointer);
			} else if (cmd == OP_CASETBL) {
				if (ins.argumentCells.length < 2 || (ins.pointer + 4) + ins.argumentCells[1] <= 4) {
					throw new InjectionException("A CASETBL at 0x" + Integer.toHexString(ins.pointer) + " targets the code head.");
				}
				requireInstructionBoundary(t, (ins.pointer + 4) + ins.argumentCells[1], ins.pointer);
				for (int k = 2; k + 1 < ins.argumentCells.length; k += 2) {
					int tgt = (ins.pointer + k * 4) + ins.argumentCells[k + 1] + 4;
					if (tgt <= 4) {
						throw new InjectionException("A CASETBL at 0x" + Integer.toHexString(ins.pointer) + " targets the code head.");
					}
					requireInstructionBoundary(t, tgt, ins.pointer);
				}
			}
		}
	}

	private static void requireInstructionBoundary(GFLPawnScript t, int target, int fromPtr) {
		if (t.lookupInstructionByPtr(target) == null) {
			throw new InjectionException("A branch at 0x" + Integer.toHexString(fromPtr) + " targets 0x" + Integer.toHexString(target) + ", which is not an instruction boundary.");
		}
	}

	//============ donor model ============
	private static class Donor {

		int wrapperPtr;
		/**
		 * Closure sub entry addresses in DFS preorder (includes the ptr-4
		 * stub at index STUB_DFS_INDEX).
		 */
		final List<Integer> subPtrs = new ArrayList<>();
		/**
		 * Sub entry address -> verbatim cells of the whole sub.
		 */
		final Map<Integer, int[]> subCells = new HashMap<>();
		/**
		 * Sub entry address -> {first instruction index, end index (excl)}.
		 */
		final Map<Integer, int[]> subRanges = new HashMap<>();
	}

	/**
	 * Builds and validates the donor closure model against the measured
	 * geometry; throws InjectionException on any mismatch. NOTE: unlike the
	 * msg template there is no rewrite-site/buffer validation - the sign
	 * closure has no data references, and the fingerprint plus the
	 * per-instruction opcode validation below refuse any donor whose closure
	 * unexpectedly grew one.
	 */
	private static Donor buildDonor(GFLPawnScript donor) {
		if (donor == null) {
			throw new InjectionException("No donor script.");
		}
		donor.decompressThis();
		if (donor.defsize != EXPECTED_DEFSIZE) {
			throw new InjectionException("Unexpected donor prefix entry size " + donor.defsize + ".");
		}
		PawnInstruction wrapper = ZoneScriptAnalyzer.findSignWrapper(donor);
		if (wrapper == null) {
			throw new InjectionException("The donor script has no sign display routine.");
		}
		Donor d = new Donor();
		d.wrapperPtr = wrapper.pointer;
		dfsClosure(donor, wrapper.pointer, d);
		if (d.subPtrs.size() != CLOSURE_SUB_CELLS.length) {
			throw new InjectionException("Donor closure has " + d.subPtrs.size() + " subs, expected " + CLOSURE_SUB_CELLS.length + ".");
		}
		if (d.subPtrs.get(STUB_DFS_INDEX) != 4) {
			throw new InjectionException("Donor closure does not reach the yield stub at 0x4 in canonical order.");
		}
		for (int i = 0; i < d.subPtrs.size(); i++) {
			int[] cells = d.subCells.get(d.subPtrs.get(i));
			if (cells.length != CLOSURE_SUB_CELLS[i]) {
				throw new InjectionException("Donor closure sub " + i + " has " + cells.length + " cells, expected " + CLOSURE_SUB_CELLS[i] + ".");
			}
		}
		if (!MsgWrapperInjector.hasStubAt4(donor)) {
			throw new InjectionException("The donor has no yield stub at 0x4.");
		}
		int stubNatIdx = donor.instructions.get(3).argumentCells[0];
		if (stubNatIdx < 0 || stubNatIdx >= donor.natives.size()
				|| donor.natives.get(stubNatIdx).data.length < 2
				|| donor.natives.get(stubNatIdx).data[1] != YIELD_NATIVE_HASH) {
			throw new InjectionException("The donor stub does not call the yield native.");
		}
		if (d.subCells.get(4).length != STUB_CELL_COUNT) {
			throw new InjectionException("The donor yield stub is not " + STUB_CELL_COUNT + " cells.");
		}
		//per-instruction closure validation: self-containment
		for (Integer entryPtr : d.subPtrs) {
			int[] cells = d.subCells.get(entryPtr);
			int endPtr = entryPtr + cells.length * 4;
			int[] r = d.subRanges.get(entryPtr);
			for (int i = r[0]; i < r[1]; i++) {
				PawnInstruction ins = donor.instructions.get(i);
				int cmd = ins.getCommand();
				if (cmd == OP_CALL_PRI || cmd == OP_JUMP_PRI || cmd == OP_SYSREQ_PRI
						|| cmd == OP_SYSREQ_C || cmd == OP_SYSREQ_D || cmd == OP_SYSREQ_ND) {
					throw new InjectionException("Donor closure uses indirect control flow (cmd 0x" + Integer.toHexString(cmd) + " at 0x" + Integer.toHexString(ins.pointer) + ").");
				}
				if (cmd == OP_SYSREQ_N) {
					if (ins.argumentCells.length < 2 || ins.argumentCells[0] < 0 || ins.argumentCells[0] >= donor.natives.size()
							|| donor.natives.get(ins.argumentCells[0]).data.length < 2
							|| donor.natives.get(ins.argumentCells[0]).data[0] != 0) {
						throw new InjectionException("Donor SYSREQ_N at 0x" + Integer.toHexString(ins.pointer) + " has no well-formed natives entry.");
					}
				} else if (cmd == OP_CALL) {
					if (ins.argumentCells.length != 1) {
						throw new InjectionException("Donor CALL at 0x" + Integer.toHexString(ins.pointer) + " is malformed.");
					}
					int tgt = ins.pointer + ins.argumentCells[0];
					if (!d.subCells.containsKey(tgt)) {
						throw new InjectionException("Donor CALL at 0x" + Integer.toHexString(ins.pointer) + " leaves the closure.");
					}
				} else if (PawnInstruction.checkJmp(ins) || cmd == OP_SWITCH) {
					int tgt = ins.pointer + ins.argumentCells[0];
					if (tgt < entryPtr || tgt >= endPtr) {
						throw new InjectionException("Donor branch at 0x" + Integer.toHexString(ins.pointer) + " leaves its sub.");
					}
				} else if (cmd == OP_CASETBL) {
					int defTgt = (ins.pointer + 4) + ins.argumentCells[1];
					if (defTgt < entryPtr || defTgt >= endPtr) {
						throw new InjectionException("Donor CASETBL at 0x" + Integer.toHexString(ins.pointer) + " leaves its sub.");
					}
					for (int k = 2; k + 1 < ins.argumentCells.length; k += 2) {
						int tgt = (ins.pointer + k * 4) + ins.argumentCells[k + 1] + 4;
						if (tgt < entryPtr || tgt >= endPtr) {
							throw new InjectionException("Donor CASETBL at 0x" + Integer.toHexString(ins.pointer) + " leaves its sub.");
						}
					}
				}
			}
		}
		//content fingerprint: the shape checks above cannot see altered
		//non-fixup cells, so pin the closure content to the vanilla routine
		long fp = closureFingerprint(donor, d);
		if (fp != VANILLA_CLOSURE_CRC32) {
			throw new InjectionException("Donor closure content fingerprint 0x" + Long.toHexString(fp)
					+ " does not match the vanilla sign routine (0x" + Long.toHexString(VANILLA_CLOSURE_CRC32) + ") - the donor zone's code was modified.");
		}
		return d;
	}

	/**
	 * The position-independent content hash described at
	 * VANILLA_CLOSURE_CRC32; runs on a closure model that already passed the
	 * per-instruction validation (every SYSREQ_N/CALL operand resolvable).
	 */
	private static long closureFingerprint(GFLPawnScript donor, Donor d) {
		Map<Integer, Integer> ord = new HashMap<>();
		for (int i = 0; i < d.subPtrs.size(); i++) {
			ord.put(d.subPtrs.get(i), i);
		}
		CRC32 crc = new CRC32();
		for (Integer entryPtr : d.subPtrs) {
			int[] r = d.subRanges.get(entryPtr);
			for (int i = r[0]; i < r[1]; i++) {
				PawnInstruction ins = donor.instructions.get(i);
				int cmd = ins.getCommand();
				int[] raw = ins.getRaw();
				if (cmd == OP_SYSREQ_N) {
					crcCell(crc, raw[0]);
					crcCell(crc, donor.natives.get(ins.argumentCells[0]).data[1]); //native identity, not index
					crcCell(crc, ins.argumentCells[1]); //argBytes
				} else if (cmd == OP_CALL) {
					crcCell(crc, raw[0]);
					crcCell(crc, ord.get(ins.pointer + ins.argumentCells[0])); //DFS ordinal, not offset
				} else {
					for (int c : raw) {
						crcCell(crc, c);
					}
				}
			}
		}
		return crc.getValue();
	}

	private static void crcCell(CRC32 crc, int v) {
		crc.update(v & 0xFF);
		crc.update((v >> 8) & 0xFF);
		crc.update((v >> 16) & 0xFF);
		crc.update((v >> 24) & 0xFF);
	}

	/**
	 * DFS preorder over CALL targets, collecting each sub's instruction
	 * range and verbatim cells; refuses call targets that do not land on a
	 * PROC.
	 */
	private static void dfsClosure(GFLPawnScript s, int entryPtr, Donor d) {
		if (d.subCells.containsKey(entryPtr)) {
			return;
		}
		PawnInstruction entry = s.lookupInstructionByPtr(entryPtr);
		if (entry == null || entry.getCommand() != OP_PROC) {
			throw new InjectionException("Donor CALL target 0x" + Integer.toHexString(entryPtr) + " does not land on a PROC.");
		}
		int idx = s.instructions.indexOf(entry);
		int end = idx + 1;
		while (end < s.instructions.size() && s.instructions.get(end).getCommand() != OP_PROC) {
			end++;
		}
		int endPtr = (end < s.instructions.size()) ? s.instructions.get(end).pointer : (s.dataStart - s.instructionStart);
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
}
