package ctrmap.formats.scripts;

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
 * <p>THE TRANSPLANT ITSELF IS {@link PawnClosure}'s (F087): this injector was a copy of the
 * message one with the buffer step deleted, 259 of its 278 substantive lines also there. What is
 * here is what only this routine has - its finder, geometry and fingerprint; the data hooks are
 * left at the engine's defaults, which are a routine that addresses no data.
 */
public class SignWrapperInjector {

	/**
	 * The canonical donor zone (ZoneData index) - zone 24 carries both
	 * routines, so both injectors share one canonical donor.
	 */
	public static final int PREFERRED_DONOR_ZONE = PawnClosure.PREFERRED_DONOR_ZONE;

	/**
	 * Name hash of the yield-frame native called by the stub at code address
	 * 0x4 (natives[i].data[1]), stable game-wide.
	 */
	public static final int YIELD_NATIVE_HASH = PawnClosure.YIELD_NATIVE_HASH;

	/**
	 * Cell counts of the 6 closure subs in DFS preorder from the sign wrapper
	 * (donor 24 entries: 2D0, 260, 958, 9E0, 4, 468). Index STUB_DFS_INDEX is
	 * the ptr-0x4 yield stub, which is never transplanted (it is ensured at
	 * 0x4 of the target instead).
	 */
	private static final int[] CLOSURE_SUB_CELLS = {21, 28, 34, 20, 11, 7};
	private static final int STUB_DFS_INDEX = 4;

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

	private static final PawnClosure.Spec<PawnClosure.Donor> SPEC = new PawnClosure.Spec<PawnClosure.Donor>(
			"sign display routine", "sign wrapper", "sign routine", CLOSURE_SUB_CELLS, STUB_DFS_INDEX,
			VANILLA_CLOSURE_CRC32) {

		@Override
		protected PawnInstruction find(GFLPawnScript s) {
			return ZoneScriptAnalyzer.findSignWrapper(s);
		}

		@Override
		protected PawnClosure.Donor newDonor() {
			return new PawnClosure.Donor();
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
	 * The number of instructions injectSignWrapper would add to the target
	 * (the 5 transplanted subs, plus the 8-instruction stub if the target
	 * lacks it). Neither script is modified.
	 */
	public static int countInjectedInstructions(GFLPawnScript target, GFLPawnScript donor) {
		return PawnClosure.countInjectedInstructions(SPEC, target, donor);
	}

	/**
	 * Selects a donor: the canonical PREFERRED_DONOR_ZONE first, then the first other zone whose
	 * script validates.
	 *
	 * @throws PawnClosure.Refused when no zone validates
	 */
	public static GFLPawnScript pickDonor(PawnClosure.ScriptSource source, int zoneCount) {
		return PawnClosure.pickDonor(SPEC, source, zoneCount);
	}

	/** A sign-routine donor from the handed game's own zone files (F248). */
	public static GFLPawnScript donorFrom(ctrmap.formats.GameFiles game) {
		int zones = PawnClosure.zoneCount(game);
		return pickDonor(PawnClosure.zoneScripts(game), zones);
	}

	/**
	 * Injects the donor's sign-routine closure into the target script (in memory; the caller
	 * decides where the bytes go) - {@link PawnClosure#inject} with no data step: the sign closure
	 * addresses no data, and the target data segment is left untouched. Sign-carrying scripts are
	 * refused, which keeps the operation idempotent.
	 *
	 * @param target a zone script with a dispatch and no sign wrapper,
	 * modified in place
	 * @param donor a validated donor (see pickDonor/validateDonor)
	 * @return the code address of the injected wrapper entry PROC
	 * @throws PawnClosure.Refused on any structural surprise; see {@link PawnClosure#inject} for the
	 * self-check that runs after mutation began
	 * @param measured the game the target is from, already asked (NpcTemplates.measuredOn): the
	 * routine's shape is ORAS's
	 */
	public static int injectSignWrapper(NpcTemplates.Measured measured, GFLPawnScript target, GFLPawnScript donor) {
		NpcTemplates.asked(measured);
		return PawnClosure.inject(SPEC, target, donor);
	}
}
