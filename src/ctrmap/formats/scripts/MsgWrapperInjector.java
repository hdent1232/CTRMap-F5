package ctrmap.formats.scripts;

import java.util.HashMap;
import java.util.Map;

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
 * and any structural surprise refuses before the target script is touched.
 *
 * <p>THE TRANSPLANT ITSELF IS {@link PawnClosure}'s (F087). What is here is what only this
 * routine has: its geometry and fingerprint, and the buffer - the 8 rewrite sites, the
 * 16-bit rebase guard, the buffer append and the rewrites themselves.
 */
public class MsgWrapperInjector {

	/**
	 * The canonical donor zone (ZoneData index) whose closure the measured
	 * geometry below was pinned against. Any of the 211 zones whose closure
	 * aligns line-for-line with it validates as a substitute donor.
	 */
	public static final int PREFERRED_DONOR_ZONE = PawnClosure.PREFERRED_DONOR_ZONE;

	/**
	 * Name hash of the yield-frame native called by the stub at code address
	 * 0x4 (natives[i].data[1]), stable game-wide.
	 */
	public static final int YIELD_NATIVE_HASH = PawnClosure.YIELD_NATIVE_HASH;

	/**
	 * Cells of the scratch/parameter buffer the closure addresses through
	 * the 8 rewrite sites (120 bytes).
	 */
	public static final int BUFFER_CELLS = 30;

	private static final int OP_PUSH_P_C = PawnInstruction.Commands.PUSH_P_C.ordinal();
	private static final int OP_CONST_P_PRI = PawnInstruction.Commands.CONST_P_PRI.ordinal();

	/**
	 * Cell counts of the 13 closure subs in DFS preorder from the wrapper
	 * (donor 24 terms: 36C, 4F4, 514, 4D4, A30, B2C, 4, 130, 958, 9E0, 468,
	 * 7F8, CB4). Index STUB_DFS_INDEX is the ptr-0x4 yield stub, which is
	 * never transplanted (it is ensured at 0x4 of the target instead).
	 */
	private static final int[] CLOSURE_SUB_CELLS = {46, 8, 185, 8, 63, 21, 11, 25, 34, 20, 7, 88, 11};
	private static final int STUB_DFS_INDEX = 6;

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

	/** What the closure walk collected, plus the buffer only this transplant addresses. */
	private static class Donor extends PawnClosure.Donor {

		/**
		 * "entryPtr:byteOffset" -> byte delta from the buffer base, for the
		 * 8 rewrite sites.
		 */
		final Map<String, Integer> rewriteDeltaByPos = new HashMap<>();
		int bufferBase;
		final int[] bufferCells = new int[BUFFER_CELLS];
		/** Where the buffer lands in the target's data segment, set when the target is checked. */
		int newBufBase;

		Integer delta(int entryPtr, PawnInstruction ins) {
			return rewriteDeltaByPos.get(entryPtr + ":" + (ins.pointer - entryPtr));
		}
	}

	private static final PawnClosure.Spec<Donor> SPEC = new PawnClosure.Spec<Donor>("message wrapper",
			"message wrapper", "message routine", CLOSURE_SUB_CELLS, STUB_DFS_INDEX, VANILLA_CLOSURE_CRC32) {

		@Override
		protected PawnInstruction find(GFLPawnScript s) {
			return ZoneScriptAnalyzer.findMsgWrapper(s);
		}

		@Override
		protected Donor newDonor() {
			return new Donor();
		}

		/** The 8 rewrite sites and the buffer they address. */
		@Override
		protected void readData(GFLPawnScript donor, Donor d) {
			boolean baseSet = false;
			int bufferBase = 0;
			for (int[] site : REWRITE_SITES) {
				int subPtr = d.subPtrs.get(site[0]);
				int insPtr = subPtr + site[1];
				PawnInstruction ins = donor.lookupInstructionByPtr(insPtr);
				if (ins == null || ins.getCommand() != site[2] || !ins.hasCompressedArgument || ins.argumentCells.length < 1) {
					throw new PawnClosure.Refused("Donor rewrite site at 0x" + Integer.toHexString(insPtr) + " does not match the measured shape.");
				}
				int value = ins.argumentCells[0];
				if (!baseSet) {
					bufferBase = value - site[3];
					baseSet = true;
				}
				if (value != bufferBase + site[3]) {
					throw new PawnClosure.Refused("Donor rewrite site at 0x" + Integer.toHexString(insPtr) + " holds " + value + ", expected " + (bufferBase + site[3]) + ".");
				}
				d.rewriteDeltaByPos.put(subPtr + ":" + site[1], site[3]);
			}
			if (bufferBase < 0 || bufferBase % 4 != 0 || bufferBase / 4 + BUFFER_CELLS > donor.data.size()) {
				throw new PawnClosure.Refused("Donor buffer base " + bufferBase + " is not a readable 30-cell data region.");
			}
			d.bufferBase = bufferBase;
			for (int i = 0; i < BUFFER_CELLS; i++) {
				d.bufferCells[i] = donor.data.get(bufferBase / 4 + i).cellValue;
			}
		}

		/** A rewrite site's op cell: its opcode half and its buffer delta - the packed address is per-zone. */
		@Override
		protected int[] fingerprintCells(Donor d, int entryPtr, PawnInstruction ins, int[] raw) {
			Integer delta = d.delta(entryPtr, ins);
			return delta == null ? null : new int[]{raw[0] & 0xFFFF, delta};
		}

		@Override
		protected int[] fingerprintTail(Donor d) {
			return d.bufferCells;
		}

		/**
		 * The 8 rewrite sites are packed immediates - the rebased buffer addresses must fit 16 bits
		 * (measured max 1540, 21x margin).
		 */
		@Override
		protected void checkTarget(GFLPawnScript target, Donor d) {
			d.newBufBase = target.data.size() * 4;
			if (d.newBufBase + MAX_BUFFER_DELTA > Short.MAX_VALUE) {
				throw new PawnClosure.Refused("The target data segment is too large to rebase the buffer (" + d.newBufBase + " bytes).");
			}
		}

		/** The donor's 30 buffer cells, appended to the data segment. */
		@Override
		protected void appendData(GFLPawnScript target, Donor d) {
			for (int i = 0; i < d.bufferCells.length; i++) {
				int v = d.bufferCells[i];
				PawnInstruction dummy = new PawnInstruction(0, v, Integer.toHexString(v));
				dummy.hasCompressedArgument = false;
				dummy.argumentCount = 0;
				dummy.argumentCells = new int[0];
				dummy.cellValue = v;
				target.data.add(dummy);
			}
		}

		@Override
		protected boolean rewrite(Donor d, int[] block, int opCell, int entryPtr, PawnInstruction ins) {
			Integer delta = d.delta(entryPtr, ins);
			if (delta == null) {
				return false;
			}
			int rebased = d.newBufBase + delta;
			//packed instruction: the argument lives in the upper 16 bits of the op cell
			block[opCell] = (block[opCell] & 0x7FFF) | (rebased << 16);
			return true;
		}

		@Override
		protected int rewrites() {
			return REWRITE_SITES.length;
		}
	};

	/**
	 * Validates the donor against the measured closure geometry.
	 *
	 * @throws PawnClosure.Refused when the donor does not qualify
	 */
	@ctrmap.util.ForSuites("SEAM: a suite asks whether a donor qualifies without injecting anything, to see a bad one refused before any zone is touched")
	public static void validateDonor(GFLPawnScript donor) {
		PawnClosure.buildDonor(SPEC, donor);
	}

	/**
	 * The number of instructions injectMsgWrapper would add to the target
	 * (the 12 transplanted subs, plus the 8-instruction stub if the target
	 * lacks it). Neither script is modified.
	 */
	public static int countInjectedInstructions(GFLPawnScript target, GFLPawnScript donor) {
		return PawnClosure.countInjectedInstructions(SPEC, target, donor);
	}

	/**
	 * The donor's 30 initial buffer cells (validated donor geometry), for
	 * acceptance checks against the transplanted data segment.
	 */
	@ctrmap.util.ForSuites("ORACLE: the donor's 30 buffer cells as its validated geometry reads them, which MsgWrapperInjectTest holds the transplanted data segment to")
	public static int[] getDonorBufferCells(GFLPawnScript donor) {
		return PawnClosure.buildDonor(SPEC, donor).bufferCells.clone();
	}

	/**
	 * Selects a donor: the canonical PREFERRED_DONOR_ZONE first, then the
	 * first other zone whose script passes validateDonor (any of the 211
	 * closure-aligned wrapper zones qualifies; a user-modified zone 24 is
	 * thereby skipped).
	 *
	 * @throws PawnClosure.Refused when no zone validates
	 */
	public static GFLPawnScript pickDonor(PawnClosure.ScriptSource source, int zoneCount) {
		return PawnClosure.pickDonor(SPEC, source, zoneCount);
	}

	/** A message-routine donor from the handed game's own zone files (F248). */
	public static GFLPawnScript donorFrom(ctrmap.formats.GameFiles game) {
		int zones = PawnClosure.zoneCount(game);
		return pickDonor(PawnClosure.zoneScripts(game), zones);
	}

	/**
	 * Injects the donor's message-routine closure into the target script (in memory; the caller
	 * decides where the bytes go) - {@link PawnClosure#inject}, with the 30 buffer cells appended to
	 * the data segment and the 8 buffer-address rewrites onto them. Wrapper-carrying scripts are
	 * refused, which keeps the operation idempotent.
	 *
	 * @param target a zone script with a dispatch and no wrapper, modified
	 * in place
	 * @param donor a validated donor (see pickDonor/validateDonor)
	 * @return the code address of the injected wrapper entry PROC
	 * @throws PawnClosure.Refused on any structural surprise; see {@link PawnClosure#inject} for the
	 * two checks that run after mutation began (callers inject into a disposable copy, as
	 * NPCEditForm does)
	 * @param measured the game the target is from, already asked (NpcTemplates.measuredOn): the
	 * routine's shape and its yield native are ORAS's
	 */
	public static int injectMsgWrapper(NpcTemplates.Measured measured, GFLPawnScript target, GFLPawnScript donor) {
		NpcTemplates.asked(measured);
		return PawnClosure.inject(SPEC, target, donor);
	}
}
