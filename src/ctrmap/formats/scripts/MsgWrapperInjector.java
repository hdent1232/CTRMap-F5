package ctrmap.formats.scripts;

import ctrmap.humaninterface.ScriptEditor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Headless transplantation of the vanilla message-display routine (the
 * "msgWrapper" call chain that simple talker scripts CALL into) from a donor
 * zone script into zone scripts that lack it, so the talking-NPC wizard
 * (TalkerScriptWizard.cloneTalker) works in every zone.
 *
 * Measured groundwork (pristine ORAS ZoneData, all 536 zones): 228 zones
 * carry the wrapper, 308 do not - and the wrapper's transitive CALL closure
 * is byte-canonical game-wide: 13 subroutines / 527 cells, self-contained
 * (no indirect control flow, no direct data-segment addressing, every jump
 * staying inside its sub and every CALL resolving inside the closure or to
 * the 11-cell yield-frame stub at code address 0x4). The only per-zone
 * differences are (a) SYSREQ_N native-table indices (the 32-bit name hashes
 * behind them are stable game-wide) and (b) exactly 8 packed immediates
 * holding data-segment byte addresses of a 30-cell scratch/parameter buffer
 * whose initial contents are identical in every zone.
 *
 * injectMsgWrapper() therefore reproduces the vanilla layout exactly:
 * missing natives are appended (dedup'd by name hash; existing entries and
 * their indices are never touched), the yield stub is inserted at code
 * address 0x4 when absent (every vanilla wrapper zone has it there), the 30
 * buffer cells are appended to the data segment, and the 12 non-stub closure
 * subs are appended verbatim at the end of the code section with exactly
 * three fixup classes: SYSREQ_N index remap, CALL operand recompute (calls
 * to the stub stay absolute 0x4) and the 8 positional buffer-address
 * rewrites onto the appended buffer. Donor geometry is validated up front
 * and any structural surprise refuses via InjectionException before the
 * target script is touched.
 */
public class MsgWrapperInjector {

	/**
	 * The canonical donor zone (ZoneData index) whose closure the measured
	 * geometry below was pinned against. Any of the 211 zones whose closure
	 * aligns line-for-line with it validates as a substitute donor.
	 */
	public static final int PREFERRED_DONOR_ZONE = 24;

	/**
	 * Name hash of the yield-frame native called by the stub at code address
	 * 0x4 (natives[i].data[1]), stable game-wide.
	 */
	public static final int YIELD_NATIVE_HASH = 0x0B13A389;

	/**
	 * Cells of the scratch/parameter buffer the closure addresses through
	 * the 8 rewrite sites (120 bytes).
	 */
	public static final int BUFFER_CELLS = 30;

	private static final int OP_PROC = PawnInstruction.Commands.PROC.ordinal();
	private static final int OP_RETN = PawnInstruction.Commands.RETN.ordinal();
	private static final int OP_CALL = PawnInstruction.Commands.CALL.ordinal();
	private static final int OP_SWITCH = PawnInstruction.Commands.SWITCH.ordinal();
	private static final int OP_CASETBL = PawnInstruction.Commands.CASETBL.ordinal();
	private static final int OP_SYSREQ_N = PawnInstruction.Commands.SYSREQ_N.ordinal();
	private static final int OP_PUSH_P_S = PawnInstruction.Commands.PUSH_P_S.ordinal();
	private static final int OP_ZERO_PRI = PawnInstruction.Commands.ZERO_PRI.ordinal();
	private static final int OP_ZERO_ALT = PawnInstruction.Commands.ZERO_ALT.ordinal();
	private static final int OP_HALT = PawnInstruction.Commands.HALT.ordinal();
	private static final int OP_HALT_P = PawnInstruction.Commands.HALT_P.ordinal();
	private static final int OP_PUSH_P_C = PawnInstruction.Commands.PUSH_P_C.ordinal();
	private static final int OP_CONST_P_PRI = PawnInstruction.Commands.CONST_P_PRI.ordinal();
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
	 * Cell counts of the 13 closure subs in DFS preorder from the wrapper
	 * (donor 24 terms: 36C, 4F4, 514, 4D4, A30, B2C, 4, 130, 958, 9E0, 468,
	 * 7F8, CB4). Index STUB_DFS_INDEX is the ptr-0x4 yield stub, which is
	 * never transplanted (it is ensured at 0x4 of the target instead).
	 */
	private static final int[] CLOSURE_SUB_CELLS = {46, 8, 185, 8, 63, 21, 11, 25, 34, 20, 7, 88, 11};
	private static final int STUB_DFS_INDEX = 6;
	private static final int STUB_CELL_COUNT = 11;

	/**
	 * The largest byte delta from the buffer base among the rewrite sites;
	 * rebased base + this must fit a packed (16-bit) argument.
	 */
	private static final int MAX_BUFFER_DELTA = 96;

	/**
	 * CRC32 over the donor closure's position-independent content: every
	 * cell verbatim except the three fixup classes, which are canonicalized
	 * (SYSREQ_N native index -> the 32-bit name hash behind it, CALL operand
	 * -> the target sub's DFS ordinal, the 8 rewrite-site op cells -> opcode
	 * half + buffer delta), plus the 30 initial buffer cells. Computed from
	 * the PRISTINE ORAS zone 24 (RomFS_original_garcs a/0/1/3) during
	 * development; every one of the 211 closure-aligned wrapper zones
	 * produces this same value, so any user-modified closure content is
	 * rejected as a donor even when the shape checks still pass.
	 */
	private static final long VANILLA_CLOSURE_CRC32 = 0x35D606AAL;

	/**
	 * The 8 buffer-address rewrite sites, the ONLY closure operands that hold
	 * data-segment addresses: {DFS sub index, byte offset within the sub,
	 * command ordinal, byte delta from the buffer base}. All other cells are
	 * copied verbatim (modulo SYSREQ_N/CALL fixups).
	 */
	private static final int[][] REWRITE_SITES = {
		{2, 0x130, OP_CONST_P_PRI, 0},
		{2, 0x14C, OP_PUSH_P_C, 0},
		{2, 0x178, OP_CONST_P_PRI, 12},
		{2, 0x194, OP_PUSH_P_C, 12},
		{2, 0x234, OP_PUSH_P_C, 24},
		{2, 0x290, OP_PUSH_P_C, 48},
		{11, 0xB0, OP_PUSH_P_C, 72},
		{11, 0x10C, OP_PUSH_P_C, 96}
	};

	/**
	 * Thrown when the donor or target violates the measured structural
	 * invariants - the script is refused rather than risking a questionable
	 * transplant. All precondition guards throw before the target is
	 * modified; the two final consistency checks of injectMsgWrapper are the
	 * only exception (see its throws documentation).
	 */
	public static class InjectionException extends IllegalStateException {

		public InjectionException(String message) {
			super(message);
		}
	}

	/**
	 * Supplier of parsed zone scripts by ZoneData index for donor selection
	 * (a GARC in headless use, workspace files in the editor).
	 */
	public interface ScriptSource {

		/**
		 * @return the zone script of the given ZoneData entry, or null when
		 * the entry is absent or not a ZO container
		 */
		GFLPawnScript get(int zoneIndex);
	}

	/**
	 * Whether the script is eligible for injection: it has the main script
	 * dispatch (somewhere to hang talkers off) and no message wrapper yet.
	 */
	public static boolean canInject(GFLPawnScript script) {
		if (script == null) {
			return false;
		}
		script.decompressThis();
		return ZoneScriptAnalyzer.findDispatch(script) != null && ZoneScriptAnalyzer.findMsgWrapper(script) == null;
	}

	/**
	 * Whether the 8-instruction yield-frame stub sits at code address 0x4
	 * (as in all 341 vanilla stub zones): PROC; PUSH_P_S(12);
	 * SYSREQ_N(i, 4); ZERO_PRI; ZERO_ALT; HALT(12); ZERO_PRI; RETN.
	 */
	public static boolean hasStubAt4(GFLPawnScript s) {
		if (s == null || s.instructions.size() < 9) {
			return false;
		}
		List<PawnInstruction> l = s.instructions;
		if (l.get(1).pointer != 4) {
			return false;
		}
		return l.get(1).getCommand() == OP_PROC
				&& l.get(2).getCommand() == OP_PUSH_P_S && argIs(l.get(2), 0, 12)
				&& l.get(3).getCommand() == OP_SYSREQ_N && argIs(l.get(3), 1, 4)
				&& l.get(4).getCommand() == OP_ZERO_PRI
				&& l.get(5).getCommand() == OP_ZERO_ALT
				&& l.get(6).getCommand() == OP_HALT && argIs(l.get(6), 0, 12)
				&& l.get(7).getCommand() == OP_ZERO_PRI
				&& l.get(8).getCommand() == OP_RETN;
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
	 * The number of instructions injectMsgWrapper would add to the target
	 * (the 12 transplanted subs, plus the 8-instruction stub if the target
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
			if (!hasStubAt4(target)) {
				count += 8;
			}
		}
		return count;
	}

	/**
	 * The donor's 30 initial buffer cells (validated donor geometry), for
	 * acceptance checks against the transplanted data segment.
	 */
	public static int[] getDonorBufferCells(GFLPawnScript donor) {
		return buildDonor(donor).bufferCells.clone();
	}

	/**
	 * Selects a donor: the canonical PREFERRED_DONOR_ZONE first, then the
	 * first other zone whose script passes validateDonor (any of the 211
	 * closure-aligned wrapper zones qualifies; a user-modified zone 24 is
	 * thereby skipped).
	 *
	 * @param source zone script supplier (may return null per index)
	 * @param zoneCount number of ZoneData entries to consider
	 * @return a validated donor script
	 * @throws InjectionException when no zone validates
	 */
	public static GFLPawnScript pickDonor(ScriptSource source, int zoneCount) {
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
		throw new InjectionException("No zone script validates as a message-routine donor.");
	}

	private static GFLPawnScript tryDonor(ScriptSource source, int index) {
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
	 * Extracts subfile 2 (map script) of a raw ZO container and parses it.
	 * Returns null when the data is not a ZO container (magic 0x5A4F,
	 * big-endian on disk) or does not parse.
	 */
	public static GFLPawnScript extractZoneScript(byte[] zo) {
		try {
			if (zo == null || zo.length < 4) {
				return null;
			}
			int magic = ((zo[0] & 0xFF) << 8) | (zo[1] & 0xFF);
			if (magic != 0x5A4F) {
				return null;
			}
			int count = (zo[2] & 0xFF) | ((zo[3] & 0xFF) << 8);
			if (count < 3 || zo.length < 4 + (count + 1) * 4) {
				return null;
			}
			int start = readIntLE(zo, 4 + 2 * 4);
			int end = readIntLE(zo, 4 + 3 * 4);
			if (start < 0 || end > zo.length || end <= start) {
				return null;
			}
			byte[] scr = new byte[end - start];
			System.arraycopy(zo, start, scr, 0, scr.length);
			GFLPawnScript s = new GFLPawnScript(scr);
			s.decompressThis();
			return s;
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/**
	 * Injects the donor's message-routine closure into the target script
	 * (in memory; the caller decides where the bytes go):
	 *
	 * 1. append the missing natives (dedup'd by name hash, append-only),
	 * 2. ensure the yield stub at code address 0x4 (insert + renumber via the
	 * proven setInstructionListeners/setPtrsByIndex/callInstructionListeners
	 * idiom, with the manual publics/mainEntryPoint fixups no listener owns),
	 * 3. append the donor's 30 buffer cells to the data segment,
	 * 4. append the 12 transplant subs at the end of the code section with
	 * the SYSREQ_N/CALL/buffer-address fixups.
	 *
	 * The dispatch CASETBL, all pre-existing code and data cells, the
	 * existing natives entries and the publics hashes are left untouched
	 * (publics addresses and the main entry point shift by 0x2C when the
	 * stub is inserted). Wrapper-carrying scripts are refused, which keeps
	 * the operation idempotent.
	 *
	 * @param target a zone script with a dispatch and no wrapper, modified
	 * in place
	 * @param donor a validated donor (see pickDonor/validateDonor)
	 * @return the code address of the injected wrapper entry PROC
	 * @throws InjectionException on any structural surprise. Every
	 * donor/target validation guard throws before the target is modified.
	 * The two final consistency checks (the rewrite-count guard and the
	 * post-injection wrapper self-check) run after mutation began and would
	 * leave the target partially modified - they are unreachable for any
	 * donor that passed buildDonor's geometry+fingerprint validation, but
	 * callers that must be robust even against them should inject into a
	 * disposable copy and commit it on success (as NPCEditForm does).
	 */
	public static int injectMsgWrapper(GFLPawnScript target, GFLPawnScript donor) {
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
		if (ZoneScriptAnalyzer.findMsgWrapper(target) != null) {
			throw new InjectionException("The target script already has a message wrapper.");
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
		boolean needStub = !hasStubAt4(target);
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
		//the 8 rewrite sites are packed immediates - the rebased buffer
		//addresses must fit 16 bits (measured max 1540, 21x margin)
		int newBufBase = target.data.size() * 4;
		if (newBufBase + MAX_BUFFER_DELTA > Short.MAX_VALUE) {
			throw new InjectionException("The target data segment is too large to rebase the buffer (" + newBufBase + " bytes).");
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

		//---- 3. buffer: append the donor's 30 cells to the data segment
		for (int i = 0; i < d.bufferCells.length; i++) {
			int v = d.bufferCells[i];
			PawnInstruction dummy = new PawnInstruction(0, v, Integer.toHexString(v));
			dummy.hasCompressedArgument = false;
			dummy.argumentCount = 0;
			dummy.argumentCells = new int[0];
			dummy.cellValue = v;
			target.data.add(dummy);
		}

		//---- 4. closure block at the end of the code section, subs packed
		//contiguously in donor DFS preorder (the wrapper entry comes first)
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
		int rewrites = 0;
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
				} else {
					Integer delta = d.rewriteDeltaByPos.get(entryPtr + ":" + (ins.pointer - entryPtr));
					if (delta != null) {
						int rebased = newBufBase + delta;
						//packed instruction: the argument lives in the upper 16 bits of the op cell
						block[blockOff + cellIdxInSub] = (block[blockOff + cellIdxInSub] & 0x7FFF) | (rebased << 16);
						rewrites++;
					}
				}
			}
		}
		if (rewrites != REWRITE_SITES.length) {
			throw new InjectionException("Expected " + REWRITE_SITES.length + " buffer-address rewrites, performed " + rewrites + ".");
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
		PawnInstruction w = ZoneScriptAnalyzer.findMsgWrapper(target);
		if (w == null || w.pointer != predicted) {
			throw new InjectionException("Post-injection verification failed: message wrapper "
					+ (w == null ? "not found" : "at unexpected address 0x" + Integer.toHexString(w.pointer)) + ".");
		}
		return predicted;
	}

	/**
	 * The insert-at-4 preconditions for stub-less targets: a 1-cell HALT_P
	 * at 0 and a PROC at 4 (so everything from address 4 shifts uniformly),
	 * no branch/case target at or below 4 anywhere (such a target would be
	 * redirected into the inserted stub), and every branch/case target
	 * landing on an instruction boundary (the insert renumbers via
	 * per-instruction listeners, so a target inside an instruction - e.g. a
	 * user-modified jump into a CASETBL interior - has no listener owner and
	 * would corrupt or NPE later).
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

	/**
	 * Refuses branch/case targets that do not resolve to an instruction
	 * boundary of the target script.
	 */
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
		/**
		 * "entryPtr:byteOffset" -> byte delta from the buffer base, for the
		 * 8 rewrite sites.
		 */
		final Map<String, Integer> rewriteDeltaByPos = new HashMap<>();
		int bufferBase;
		final int[] bufferCells = new int[BUFFER_CELLS];
	}

	/**
	 * Builds and validates the donor closure model against the measured
	 * geometry; throws InjectionException on any mismatch.
	 */
	private static Donor buildDonor(GFLPawnScript donor) {
		if (donor == null) {
			throw new InjectionException("No donor script.");
		}
		donor.decompressThis();
		if (donor.defsize != EXPECTED_DEFSIZE) {
			throw new InjectionException("Unexpected donor prefix entry size " + donor.defsize + ".");
		}
		PawnInstruction wrapper = ZoneScriptAnalyzer.findMsgWrapper(donor);
		if (wrapper == null) {
			throw new InjectionException("The donor script has no message wrapper.");
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
		if (!hasStubAt4(donor)) {
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
		//the 8 rewrite sites and the buffer they address
		boolean baseSet = false;
		int bufferBase = 0;
		for (int[] site : REWRITE_SITES) {
			int subPtr = d.subPtrs.get(site[0]);
			int insPtr = subPtr + site[1];
			PawnInstruction ins = donor.lookupInstructionByPtr(insPtr);
			if (ins == null || ins.getCommand() != site[2] || !ins.hasCompressedArgument || ins.argumentCells.length < 1) {
				throw new InjectionException("Donor rewrite site at 0x" + Integer.toHexString(insPtr) + " does not match the measured shape.");
			}
			int value = ins.argumentCells[0];
			if (!baseSet) {
				bufferBase = value - site[3];
				baseSet = true;
			}
			if (value != bufferBase + site[3]) {
				throw new InjectionException("Donor rewrite site at 0x" + Integer.toHexString(insPtr) + " holds " + value + ", expected " + (bufferBase + site[3]) + ".");
			}
			d.rewriteDeltaByPos.put(subPtr + ":" + site[1], site[3]);
		}
		if (bufferBase < 0 || bufferBase % 4 != 0 || bufferBase / 4 + BUFFER_CELLS > donor.data.size()) {
			throw new InjectionException("Donor buffer base " + bufferBase + " is not a readable 30-cell data region.");
		}
		d.bufferBase = bufferBase;
		for (int i = 0; i < BUFFER_CELLS; i++) {
			d.bufferCells[i] = donor.data.get(bufferBase / 4 + i).cellValue;
		}
		//content fingerprint: the shape checks above cannot see altered
		//non-fixup cells, so pin the closure content to the vanilla routine
		long fp = closureFingerprint(donor, d);
		if (fp != VANILLA_CLOSURE_CRC32) {
			throw new InjectionException("Donor closure content fingerprint 0x" + Long.toHexString(fp)
					+ " does not match the vanilla message routine (0x" + Long.toHexString(VANILLA_CLOSURE_CRC32) + ") - the donor zone's code was modified.");
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
					Integer delta = d.rewriteDeltaByPos.get(entryPtr + ":" + (ins.pointer - entryPtr));
					if (delta != null) {
						crcCell(crc, raw[0] & 0xFFFF); //opcode half; the packed address is per-zone
						crcCell(crc, delta);
					} else {
						for (int c : raw) {
							crcCell(crc, c);
						}
					}
				}
			}
		}
		for (int c : d.bufferCells) {
			crcCell(crc, c);
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

	private static boolean argIs(PawnInstruction ins, int idx, int value) {
		return ins.argumentCells.length > idx && ins.argumentCells[idx] == value;
	}

	private static int readIntLE(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
	}
}
