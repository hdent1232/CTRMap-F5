package ctrmap.formats.scripts;

import ctrmap.formats.GameFiles;
import ctrmap.formats.containers.ContainerBytes;
import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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
 * <p>THE WHOLE TRANSPLANT IS HERE (F087). The walk moved here first and the rest
 * stayed behind: each injector still carried its own donor validation, natives
 * merge, stub insert with its publics and entry-point fixups, block append,
 * self-check and donor search - 259 of the sign injector's 278 substantive lines
 * also in the message one, and the same refusal re-wrapping pasted nine times. Now
 * an injector is a {@link Spec}: which routine, its measured geometry and
 * fingerprint, and - for the message routine alone - the data buffer it addresses.
 *
 * <p>REFUSALS ARE {@link Refused}, one type for both transplants. Each injector had
 * its own InjectionException and caught this engine's refusals to rethrow them as
 * that, so a caller catching one injector's type missed the other's.
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
	//no OP_SWITCH: checkJmp holds SWITCH (0x81), so "checkJmp(ins) || cmd == SWITCH" asked it twice - measured
	//2026-10-14, the second half dropped and MsgWrapperInjectTest's leaving SWITCH still refused as a branch
	private static final int OP_CASETBL = PawnInstruction.Commands.CASETBL.ordinal();
	private static final int OP_HALT_P = PawnInstruction.Commands.HALT_P.ordinal();
	private static final int OP_RETN = PawnInstruction.Commands.RETN.ordinal();
	private static final int OP_SYSREQ_N = PawnInstruction.Commands.SYSREQ_N.ordinal();
	private static final int OP_PUSH_P_S = PawnInstruction.Commands.PUSH_P_S.ordinal();
	private static final int OP_ZERO_PRI = PawnInstruction.Commands.ZERO_PRI.ordinal();
	private static final int OP_ZERO_ALT = PawnInstruction.Commands.ZERO_ALT.ordinal();
	private static final int OP_HALT = PawnInstruction.Commands.HALT.ordinal();
	private static final int OP_CALL_PRI = PawnInstruction.Commands.CALL_PRI.ordinal();
	private static final int OP_JUMP_PRI = PawnInstruction.Commands.JUMP_PRI.ordinal();
	private static final int OP_SYSREQ_PRI = PawnInstruction.Commands.SYSREQ_PRI.ordinal();
	private static final int OP_SYSREQ_C = PawnInstruction.Commands.SYSREQ_C.ordinal();
	private static final int OP_SYSREQ_D = PawnInstruction.Commands.SYSREQ_D.ordinal();
	private static final int OP_SYSREQ_ND = PawnInstruction.Commands.SYSREQ_ND.ordinal();

	/**
	 * The canonical donor zone (ZoneData index) both closures' measured geometry was pinned
	 * against: zone 24 carries the message and the sign routine alike.
	 */
	public static final int PREFERRED_DONOR_ZONE = 24;

	/**
	 * Name hash of the yield-frame native called by the stub at code address 0x4
	 * (natives[i].data[1]), stable game-wide.
	 */
	public static final int YIELD_NATIVE_HASH = 0x0B13A389;

	/**
	 * Prefix-entry width every ORAS zone script uses; the appended natives entries are
	 * 8-byte {0, nameHash} records of this width.
	 */
	private static final int EXPECTED_DEFSIZE = 8;

	/**
	 * Supplier of parsed zone scripts by ZoneData index for donor selection (a GARC in
	 * headless use, workspace files in the editor).
	 */
	public interface ScriptSource {

		/**
		 * @return the zone script of the given ZoneData entry, or null when the entry is
		 * absent or not a ZO container
		 */
		GFLPawnScript get(int zoneIndex);
	}

	/**
	 * Whether the 8-instruction yield-frame stub sits at code address 0x4 (as in all 341
	 * vanilla stub zones): PROC; PUSH_P_S(12); SYSREQ_N(i, 4); ZERO_PRI; ZERO_ALT;
	 * HALT(12); ZERO_PRI; RETN.
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

	private static boolean argIs(PawnInstruction ins, int idx, int value) {
		return ins.argumentCells.length > idx && ins.argumentCells[idx] == value;
	}

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
	private static void crcCell(CRC32 crc, int v) {
		crc.update(v & 0xFF);
		crc.update((v >> 8) & 0xFF);
		crc.update((v >> 16) & 0xFF);
		crc.update((v >> 24) & 0xFF);
	}

	/**
	 * Refuses a script whose code no longer ends where its code/data boundary says (F146). Both
	 * injectors write their closure at {@code dataStart - instructionStart}; a routine wired in since
	 * the boundary was computed grew the code without moving it, so a closure written there lands on
	 * that routine - and the injector's self-check still passes, because a wrapper is findable at the
	 * address it predicted. This was a rule each caller had to follow (inject first), held by no
	 * check; it is the injectors' own refusal now. Recomputing the boundary instead is wrong - see
	 * {@link GFLPawnScript#updateRaw}.
	 */
	public static void requireCodeEndsAtDataBoundary(GFLPawnScript s) {
		int code = PawnDisassembler.getRawInstructions(s.instructions).length;
		int boundary = (s.dataStart - s.instructionStart) / 4;
		if (code != boundary) {
			throw new Refused("The script's code has grown since its code/data boundary was computed (" + code
					+ " cells of code, the boundary says " + boundary + "): a routine was wired in before this"
					+ " one, which would be written over it. Inject this routine first, or save the zone and"
					+ " open it again.");
		}
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
			if (PawnInstruction.checkJmp(ins)) {
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

	/**
	 * What one transplant is (F087): the routine it carries, as the analyzer finds it and as its
	 * refusals name it, the closure geometry and fingerprint measured on the vanilla donor, and -
	 * for a routine that addresses data, which only the message routine does - the data step. The
	 * hooks' defaults are a routine with no data references.
	 */
	abstract static class Spec<D extends Donor> {

		/** The routine as a refusal names it in a script: "message wrapper", "sign display routine". */
		final String wrapper;
		/** The routine as the post-injection self-check names it. */
		final String verified;
		/** The routine as a donor refusal names it: "message routine", "sign routine". */
		final String routine;
		/** Cell counts of the closure's subs in DFS preorder from the routine's entry. */
		final int[] subCells;
		/** Where in that order the 0x4 yield stub falls - never transplanted, ensured instead. */
		final int stubDfsIndex;
		/** CRC32 of the vanilla closure's position-independent content. */
		final long vanillaCrc;

		protected Spec(String wrapper, String verified, String routine, int[] subCells, int stubDfsIndex,
				long vanillaCrc) {
			this.wrapper = wrapper;
			this.verified = verified;
			this.routine = routine;
			this.subCells = subCells;
			this.stubDfsIndex = stubDfsIndex;
			this.vanillaCrc = vanillaCrc;
		}

		/** The routine's entry PROC in a script, or null when the script has none. */
		protected abstract PawnInstruction find(GFLPawnScript s);

		protected abstract D newDonor();

		/** Reads and validates the data the closure addresses, after the closure itself validated. */
		protected void readData(GFLPawnScript donor, D d) {
		}

		/** The cells the fingerprint takes for one instruction in place of its raw ones, or null for its raw ones. */
		protected int[] fingerprintCells(D d, int entryPtr, PawnInstruction ins, int[] raw) {
			return null;
		}

		/** Cells the fingerprint takes after the closure's. */
		protected int[] fingerprintTail(D d) {
			return new int[0];
		}

		/** Refuses a target the data step cannot take; runs before anything is written. */
		protected void checkTarget(GFLPawnScript target, D d) {
		}

		/** Appends the data the transplanted block will address, after the stub is ensured. */
		protected void appendData(GFLPawnScript target, D d) {
		}

		/** Rewrites one copied op cell that addresses that data; answers whether it was one. */
		protected boolean rewrite(D d, int[] block, int opCell, int entryPtr, PawnInstruction ins) {
			return false;
		}

		/** How many op cells {@link #rewrite} must have rewritten. */
		protected int rewrites() {
			return 0;
		}
	}

	/**
	 * Builds and validates the donor closure model against the spec's measured geometry, and pins
	 * its content to the vanilla routine; refuses any mismatch.
	 */
	static <D extends Donor> D buildDonor(Spec<D> spec, GFLPawnScript donor) {
		if (donor == null) {
			throw new Refused("No donor script.");
		}
		donor.decompressThis();
		if (donor.defsize != EXPECTED_DEFSIZE) {
			throw new Refused("Unexpected donor prefix entry size " + donor.defsize + ".");
		}
		PawnInstruction wrapper = spec.find(donor);
		if (wrapper == null) {
			throw new Refused("The donor script has no " + spec.wrapper + ".");
		}
		D d = spec.newDonor();
		d.wrapperPtr = wrapper.pointer;
		dfsClosure(donor, wrapper.pointer, d);
		if (d.subPtrs.size() != spec.subCells.length) {
			throw new Refused("Donor closure has " + d.subPtrs.size() + " subs, expected " + spec.subCells.length + ".");
		}
		if (d.subPtrs.get(spec.stubDfsIndex) != 4) {
			throw new Refused("Donor closure does not reach the yield stub at 0x4 in canonical order.");
		}
		for (int i = 0; i < d.subPtrs.size(); i++) {
			int[] cells = d.subCells.get(d.subPtrs.get(i));
			if (cells.length != spec.subCells[i]) {
				throw new Refused("Donor closure sub " + i + " has " + cells.length + " cells, expected " + spec.subCells[i] + ".");
			}
		}
		if (!hasStubAt4(donor)) {
			throw new Refused("The donor has no yield stub at 0x4.");
		}
		int stubNatIdx = donor.instructions.get(3).argumentCells[0];
		if (stubNatIdx < 0 || stubNatIdx >= donor.natives.size()
				|| donor.natives.get(stubNatIdx).data.length < 2
				|| donor.natives.get(stubNatIdx).data[1] != YIELD_NATIVE_HASH) {
			throw new Refused("The donor stub does not call the yield native.");
		}
		//THE STUB'S LENGTH IS THE SUB CHECK'S (2026-10-14): the stub is sub stubDfsIndex, asked above, and its cells
		//were checked against spec.subCells[stubDfsIndex] - 11 in both specs - so a check of them against 11 here
		//could never refuse anything the loop had not
		requireSelfContained(donor, d);
		spec.readData(donor, d);
		//content fingerprint: the shape checks above cannot see altered
		//non-fixup cells, so pin the closure content to the vanilla routine
		long fp = fingerprint(spec, donor, d);
		if (fp != spec.vanillaCrc) {
			throw new Refused("Donor closure content fingerprint 0x" + Long.toHexString(fp)
					+ " does not match the vanilla " + spec.routine + " (0x" + Long.toHexString(spec.vanillaCrc)
					+ ") - the donor zone's code was modified.");
		}
		return d;
	}

	/**
	 * Per-instruction closure validation: no indirect control flow, every SYSREQ_N on a well-formed
	 * natives entry, every CALL inside the closure, every branch and case inside its own sub.
	 */
	private static void requireSelfContained(GFLPawnScript donor, Donor d) {
		for (Integer entryPtr : d.subPtrs) {
			int[] cells = d.subCells.get(entryPtr);
			int endPtr = entryPtr + cells.length * 4;
			int[] r = d.subRanges.get(entryPtr);
			for (int i = r[0]; i < r[1]; i++) {
				PawnInstruction ins = donor.instructions.get(i);
				int cmd = ins.getCommand();
				if (cmd == OP_CALL_PRI || cmd == OP_JUMP_PRI || cmd == OP_SYSREQ_PRI
						|| cmd == OP_SYSREQ_C || cmd == OP_SYSREQ_D || cmd == OP_SYSREQ_ND) {
					throw new Refused("Donor closure uses indirect control flow (cmd 0x" + Integer.toHexString(cmd) + " at 0x" + Integer.toHexString(ins.pointer) + ").");
				}
				if (cmd == OP_SYSREQ_N) {
					if (ins.argumentCells.length < 2 || ins.argumentCells[0] < 0 || ins.argumentCells[0] >= donor.natives.size()
							|| donor.natives.get(ins.argumentCells[0]).data.length < 2
							|| donor.natives.get(ins.argumentCells[0]).data[0] != 0) {
						throw new Refused("Donor SYSREQ_N at 0x" + Integer.toHexString(ins.pointer) + " has no well-formed natives entry.");
					}
				} else if (cmd == OP_CALL) {
					if (ins.argumentCells.length != 1) {
						throw new Refused("Donor CALL at 0x" + Integer.toHexString(ins.pointer) + " is malformed.");
					}
					//INSIDE BY CONSTRUCTION (2026-10-14): dfsClosure walks every well-formed CALL in every sub it
					//collects, so the target of this one is a sub of the closure or the walk has already refused it
				} else if (PawnInstruction.checkJmp(ins)) {
					int tgt = ins.pointer + ins.argumentCells[0];
					if (tgt < entryPtr || tgt >= endPtr) {
						throw new Refused("Donor branch at 0x" + Integer.toHexString(ins.pointer) + " leaves its sub.");
					}
				} else if (cmd == OP_CASETBL) {
					int defTgt = (ins.pointer + 4) + ins.argumentCells[1];
					if (defTgt < entryPtr || defTgt >= endPtr) {
						throw new Refused("Donor CASETBL at 0x" + Integer.toHexString(ins.pointer) + " leaves its sub.");
					}
					for (int k = 2; k + 1 < ins.argumentCells.length; k += 2) {
						int tgt = (ins.pointer + k * 4) + ins.argumentCells[k + 1] + 4;
						if (tgt < entryPtr || tgt >= endPtr) {
							throw new Refused("Donor CASETBL at 0x" + Integer.toHexString(ins.pointer) + " leaves its sub.");
						}
					}
				}
			}
		}
	}

	/**
	 * The closure's position-independent content hash: every cell verbatim except the fixup
	 * classes, which are canonicalized (SYSREQ_N native index to the name hash behind it, CALL
	 * operand to the target sub's DFS ordinal, and whatever the spec addresses data through), then
	 * the spec's tail. Runs on a closure that passed {@link #requireSelfContained}.
	 */
	private static <D extends Donor> long fingerprint(Spec<D> spec, GFLPawnScript donor, D d) {
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
					int[] canonical = spec.fingerprintCells(d, entryPtr, ins, raw);
					for (int c : canonical == null ? raw : canonical) {
						crcCell(crc, c);
					}
				}
			}
		}
		for (int c : spec.fingerprintTail(d)) {
			crcCell(crc, c);
		}
		return crc.getValue();
	}

	/**
	 * The number of instructions {@link #inject} would add to the target: the transplanted subs,
	 * plus the 8-instruction stub if the target lacks it. Neither script is modified.
	 */
	static int countInjectedInstructions(Spec<?> spec, GFLPawnScript target, GFLPawnScript donor) {
		Donor d = buildDonor(spec, donor);
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
	 * THE ONE READER OF A GAME'S ZONE SCRIPTS BY INDEX (F248): zone {@code zoneIndex}'s script, from the
	 * handed game's own copy of its file - staged in its workspace, extracted when it is not. THROWS when
	 * there is no file or no script in it: what the game's scripts use cannot be answered by skipping the
	 * ones that could not be read. The message transplant's donor loader, the sign transplant's, the map
	 * painter's and the battle challenge's streak-variable check were four copies of this.
	 */
	public static GFLPawnScript zoneScript(GameFiles game, int zoneIndex) throws IOException {
		File f = game.staged(ArchiveType.ZONE_DATA, zoneIndex);
		if (f == null || !f.isFile()) {
			throw new IOException("zone " + zoneIndex + " has no file in the workspace");
		}
		GFLPawnScript s = extractZoneScript(Files.readAllBytes(f.toPath()));
		if (s == null) {
			throw new IOException("zone " + zoneIndex + "'s script could not be parsed");
		}
		return s;
	}

	/**
	 * Extracts subfile 2 (map script) of a raw ZO container and parses it.
	 * Returns null when the data is not a ZO container (magic 0x5A4F,
	 * big-endian on disk) or does not parse. Here beside the one reader that
	 * calls it (F248): in MsgWrapperInjector it made that injector and this
	 * class call each other.
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
			byte[] scr = ContainerBytes.subfile(zo, 2);
			if (scr == null || scr.length == 0) {
				return null;
			}
			GFLPawnScript s = new GFLPawnScript(scr);
			s.decompressThis();
			return s;
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/**
	 * The donor search's source: {@link #zoneScript} for each zone, where one that cannot be read is no
	 * candidate and the next is tried - none at all is the search's own refusal, in words.
	 */
	public static ScriptSource zoneScripts(GameFiles game) {
		return zoneIndex -> {
			try {
				return zoneScript(game, zoneIndex);
			} catch (IOException unreadable) {
				return null; //no candidate - see above
			}
		};
	}

	/** How many ZoneData entries the handed game has: what a donor search walks. Refused in words when it has none open. */
	public static int zoneCount(GameFiles game) {
		GARC zones = game == null ? null : game.archive(ArchiveType.ZONE_DATA);
		if (zones == null) {
			throw new Refused("The ZoneData archive is not loaded.");
		}
		return zones.length;
	}

	/**
	 * Selects a donor: the canonical PREFERRED_DONOR_ZONE first, then the first other zone whose
	 * script validates (a user-modified zone 24 is thereby skipped).
	 *
	 * @param source zone script supplier (may return null per index)
	 * @param zoneCount number of ZoneData entries to consider
	 */
	static GFLPawnScript pickDonor(Spec<?> spec, ScriptSource source, int zoneCount) {
		if (source != null) {
			if (PREFERRED_DONOR_ZONE < zoneCount) {
				GFLPawnScript donor = tryDonor(spec, source, PREFERRED_DONOR_ZONE);
				if (donor != null) {
					return donor;
				}
			}
			for (int i = 0; i < zoneCount; i++) {
				if (i == PREFERRED_DONOR_ZONE) {
					continue;
				}
				GFLPawnScript donor = tryDonor(spec, source, i);
				if (donor != null) {
					return donor;
				}
			}
		}
		throw new Refused("No zone script validates as a " + spec.routine.replace(' ', '-') + " donor.");
	}

	private static GFLPawnScript tryDonor(Spec<?> spec, ScriptSource source, int index) {
		try {
			GFLPawnScript donor = source.get(index);
			if (donor != null) {
				buildDonor(spec, donor);
				return donor;
			}
		} catch (RuntimeException ex) {
			//not a valid donor (or unreadable) - keep scanning
		}
		return null;
	}

	/**
	 * Refuses a transplant that rewrote other than the spec's count of buffer-address cells. Through {@link #inject}
	 * it cannot fire for a donor {@link #buildDonor} passed - the sites are pinned by their measured shape and the
	 * closure by its fingerprint, so the count is the spec's constant - and it is kept as the check that a spec and
	 * its sites still agree. Apart from inject so a suite can ask it: the 2026-10-15 shared sweep (PawnClosure 809,
	 * drop-throw) SURVIVED, nothing able to reach the line.
	 */
	static void requireRewrites(Spec<?> spec, int performed) {
		if (performed != spec.rewrites()) {
			throw new Refused("Expected " + spec.rewrites() + " buffer-address rewrites, performed " + performed + ".");
		}
	}

	/**
	 * Injects the donor's closure into the target script (in memory; the caller decides where the
	 * bytes go):
	 *
	 * 1. append the missing natives (dedup'd by name hash, append-only),
	 * 2. ensure the yield stub at code address 0x4 (insert + renumber via the
	 * proven setInstructionListeners/setPtrsByIndex/callInstructionListeners
	 * idiom, with the manual publics/mainEntryPoint fixups no listener owns),
	 * 3. the spec's data step (the message routine's buffer; nothing for the sign routine),
	 * 4. append the transplanted subs at the end of the code section with the
	 * SYSREQ_N/CALL fixups and the spec's data rewrites.
	 *
	 * The dispatch CASETBL, all pre-existing code and data cells, the existing natives entries and
	 * the publics hashes are left untouched (publics addresses and the main entry point shift by
	 * 0x2C when the stub is inserted). A script that already carries the routine is refused, which
	 * keeps the operation idempotent.
	 *
	 * @return the code address of the injected routine's entry PROC
	 * @throws Refused on any structural surprise. Every donor/target validation throws before the
	 * target is modified; the rewrite-count guard and the post-injection self-check run after
	 * mutation began - they are unreachable for any donor that passed buildDonor, but a caller that
	 * must be robust even against them injects into a disposable copy and commits it on success.
	 *
	 * <p>NOT PUBLIC: the two injectors are the writers a template calls, each handed the measured
	 * game and asking it (NpcScriptGateTest holds every public writer to that).
	 */
	static <D extends Donor> int inject(Spec<D> spec, GFLPawnScript target, GFLPawnScript donor) {
		if (target == null) {
			throw new Refused("No target script.");
		}
		target.decompressThis();
		requireCodeEndsAtDataBoundary(target);
		if (target == donor) {
			throw new Refused("The donor and the target are the same script.");
		}
		if (ZoneScriptAnalyzer.findDispatch(target) == null) {
			throw new Refused("The target script has no script dispatch (main SWITCH/CASETBL).");
		}
		if (spec.find(target) != null) {
			throw new Refused("The target script already has a " + spec.wrapper + ".");
		}
		D d = buildDonor(spec, donor);
		if (target.defsize != EXPECTED_DEFSIZE) {
			throw new Refused("Unexpected target prefix entry size " + target.defsize + ".");
		}
		for (int i = 0; i < target.natives.size(); i++) {
			if (target.natives.get(i).data.length < 2) {
				throw new Refused("Target native entry " + i + " is malformed.");
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
				throw new Refused("The stub-shaped sub at 0x4 does not call the yield native.");
			}
		}
		spec.checkTarget(target, d);

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
			GFLPawnScript.setPtrsByIndex(target.instructions);
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

		//---- 3. the data the closure addresses, if it addresses any
		spec.appendData(target, d);

		//---- 4. closure block at the end of the code section, subs packed
		//contiguously in donor DFS preorder (the routine's entry comes first)
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
				} else if (spec.rewrite(d, block, blockOff + cellIdxInSub, entryPtr, ins)) {
					rewrites++;
				}
			}
		}
		requireRewrites(spec, rewrites);
		int[] all = new int[base / 4 + block.length];
		System.arraycopy(block, 0, all, base / 4, block.length);
		int ci = 0;
		while (ci < block.length) {
			int ptr = base + ci * 4;
			PawnInstruction ins = new PawnInstruction(ptr, all, target);
			target.instructions.add(ins);
			ci += 1 + (ins.hasCompressedArgument ? 0 : ins.argumentCount);
		}

		//self-check: the analyzer must now see the routine at the block base
		int predicted = newEntry.get(d.wrapperPtr);
		PawnInstruction w = spec.find(target);
		if (w == null || w.pointer != predicted) {
			throw new Refused("Post-injection verification failed: " + spec.verified + " "
					+ (w == null ? "not found" : "at unexpected address 0x" + Integer.toHexString(w.pointer)) + ".");
		}
		return predicted;
	}
}
