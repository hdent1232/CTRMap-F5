package ctrmap.formats.scripts;

import ctrmap.scripts.GfHash;

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

	/**
	 * Adds {@code amount} Battle Points to the player (clamped by the engine to 9999), given ONCE when
	 * {@code onceFlag} is an event flag: retail's one-time guard (see {@link PawnEmitter}) around the
	 * grant, so a second talk finds the flag set and grants nothing (F085). -1 grants on every talk, as
	 * before. The flag is not chosen here: {@code ctrmap.ScriptFlags} says which a game's scripts
	 * already name.
	 *
	 * @param measured the game the zone is from, already asked (NpcTemplates.measuredOn): the
	 * native names and the BP frame are ORAS's
	 */
	public static int addGiveBpScript(NpcTemplates.Measured measured, GFLPawnScript script, int amount, int onceFlag) {
		NpcTemplates.asked(measured);
		script.decompressThis();
		if (amount == 0) {
			throw new IllegalArgumentException("BP amount must be non-zero.");
		}
		PawnEmitter e = new PawnEmitter(script);
		e.ins(PawnInstruction.Commands.PROC, 0);
		PawnInstruction once = onceFlag < 0 ? null : e.openOnce(script, onceFlag);
		//THE NATIVES AFTER THE GUARD (2026-10-14): openOnce refuses a flag before it touches the script, and these were
		//added first - so a grant refused for its flag left two natives in the script it refused to change
		int getBP = PawnEmitter.findOrAddNative(script, GfHash.hashForName("PlayerGetBP"));
		int setBP = PawnEmitter.findOrAddNative(script, GfHash.hashForName("PlayerSetBP"));
		e.sysreq(getBP, 0);                                   // PRI = current BP
		e.ins(PawnInstruction.Commands.CONST_ALT, amount);    // ALT = amount
		e.ins(PawnInstruction.Commands.ADD, 0);               // PRI = BP + amount
		e.ins(PawnInstruction.Commands.PUSH_PRI, 0);          // push new value
		e.sysreq(setBP, 4);                                   // PlayerSetBP(new)
		if (once != null) {
			e.closeOnce(script, once, onceFlag);
		}
		e.ins(PawnInstruction.Commands.ZERO_PRI, 0);
		e.ins(PawnInstruction.Commands.RETN, 0);
		return TalkerScriptWizard.installCase(measured, script, e.body);
	}

}
