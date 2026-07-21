package ctrmap.formats.scripts;

import ctrmap.formats.zone.ZoneEntities;

/**
 * The v1 NPC template library: one factory per template validated by the
 * ORAS-wide recon over the pristine ZoneData GARC (536 zones, 2904 NPCs,
 * 2853 dispatch cases).
 *
 * Two template families exist:
 *
 * CLONED-SUB templates (sign, item giver) piggyback on the proven
 * TalkerScriptWizard insertion machinery - they append a vanilla-shaped
 * constant-pushing caller sub to the zone script and register it in the
 * dispatch CASETBL. Their availability is zone-gated on the wrapper routine
 * they CALL being present in the zone script (sign wrapper: 69/536 pristine
 * zones, give wrapper: 120/536; detectors in ZoneScriptAnalyzer).
 *
 * ENGINE-RANGE templates (trainer, double-battle pair) are pure ZoneEntities
 * record constructions - the engine handles script IDs 3000+trainerID and
 * 5000+trainerID itself, no zone-script edit exists or is needed. Field
 * encodings reproduce the measured vanilla statistics: across all 410
 * vanilla trainer NPCs u10/u12/leash are zero and the multi-zone links are
 * -1/-1/-1/0; the dominant single-trainer profile (92 exact instances) is
 * movePerm1=0, movePerm2=1, spawnFlag=0, area 0,0,1x1; all 21 pair NPCs use
 * movePerm2=9 with the same zero profile.
 *
 * Everything here was measured on ORAS. XY is NOT covered by the recon -
 * callers should gate these templates to ORAS workspaces.
 */
public class NpcTemplates {

	/**
	 * Trailing argbytes constant of the sign call site (2 arguments).
	 */
	public static final int SIGN_ARG_BYTES = 8;

	/**
	 * Trailing argbytes constant of the give-item call site (3 arguments).
	 */
	public static final int GIVE_ARG_BYTES = 12;

	/**
	 * The give-item wrapper mode argument; every decoded vanilla call site
	 * passes 1.
	 */
	public static final int GIVE_MODE = 1;

	/**
	 * Sign frame styles observed across the 169 vanilla R4 sign cases, most
	 * common first (6 x95, 9 x50, 5 x12, 7 x12).
	 */
	public static final int[] SIGN_TYPES = {6, 9, 5, 7};

	/**
	 * Display labels for SIGN_TYPES (semantics are unlabeled in the game -
	 * only the observed frequency distinguishes them).
	 */
	public static final String[] SIGN_TYPE_LABELS = {
		"6 - standard sign (most common)",
		"9 - second most common frame",
		"5 - rare frame A",
		"7 - rare frame B"
	};

	/**
	 * The interaction-kind field (unknown2) of every vanilla 1x1 sign
	 * furniture record (115/115 vanilla u2==3 records are u4=0, w=1, h=1).
	 */
	public static final int SIGN_FURNITURE_U2 = 3;

	/**
	 * Engine-reserved script ID base for single trainer battles
	 * (script = 3000 + trainer ID).
	 */
	public static final int TRAINER_SCRIPT_BASE = 3000;

	/**
	 * Engine-reserved script ID base for the second NPC of a double-battle
	 * pair (script = 5000 + trainer ID; all 21 vanilla pair NPCs sit in the
	 * same zone as their 3000+ twin).
	 */
	public static final int TRAINER_PAIR_SCRIPT_BASE = 5000;

	/**
	 * Highest trainer ID with a name line (the ORAS trainer-name text file
	 * has 950 lines; observed NPC references stop around 830).
	 */
	public static final int TRAINER_ID_MAX = 949;

	/**
	 * movePerm2 of the dominant vanilla single-trainer profile ("approaching
	 * trainer", 299 of 410).
	 */
	public static final int TRAINER_MOVE_EVENT = 1;

	/**
	 * movePerm2 of all 21 vanilla double-battle pair NPCs.
	 */
	public static final int TRAINER_PAIR_MOVE_EVENT = 9;

	/**
	 * Highest item ID with a name line (the ORAS item-name text file has 776
	 * lines, index 0 is the null item).
	 */
	public static final int ITEM_ID_MAX = 775;

	/**
	 * ORAS GAMETEXT file index of the trainer-name list (950 lines; measured
	 * on the real ROM, tid 7 = Calvin verified against the Route 102 NPC).
	 */
	public static final int GAMETEXT_TRAINER_NAMES_OA = 22;

	/**
	 * ORAS GAMETEXT file index of the item-name list (776 lines).
	 */
	public static final int GAMETEXT_ITEM_NAMES_OA = 114;

	/**
	 * Result of findSignPattern - a matched vanilla-shaped sign case.
	 */
	public static class SignPattern {

		/**
		 * STORYTEXT line the sign displays.
		 */
		public int msgLine;
		/**
		 * Sign frame style (5/6/7/9 in vanilla).
		 */
		public int signType;
		/**
		 * The PROC entry of the sign subroutine.
		 */
		public PawnInstruction subEntry;
		/**
		 * The PUSH instruction holding msgLine (patch target).
		 */
		public PawnInstruction msgLinePush;
		/**
		 * The sign wrapper's entry instruction.
		 */
		public PawnInstruction wrapperEntry;
	}

	/**
	 * Result of findGiverPattern - a matched vanilla-shaped give-item case.
	 */
	public static class GiverPattern {

		/**
		 * The item given.
		 */
		public int itemId;
		/**
		 * How many are given.
		 */
		public int count;
		/**
		 * The PROC entry of the giver subroutine.
		 */
		public PawnInstruction subEntry;
		/**
		 * The give wrapper's entry instruction.
		 */
		public PawnInstruction wrapperEntry;
	}

	/**
	 * Appends a vanilla-shaped sign script (PROC; PUSH type; PUSH line;
	 * PUSH 8; CALL signWrapper; ZERO_PRI; RETN - the R4 shape of 169 vanilla
	 * cases) to the zone script and registers it in the dispatch.
	 *
	 * @param script a decompressThis()'d zone script, modified in place
	 * @param msgLine STORYTEXT line the sign displays
	 * @param signType sign frame style (one of SIGN_TYPES)
	 * @return the newly allocated local script ID (dispatch case key)
	 * @throws IllegalStateException if the zone script has no sign wrapper or
	 * the TalkerScriptWizard machinery refuses; the script is not modified in
	 * that case
	 */
	public static int addSignScript(GFLPawnScript script, int msgLine, int signType) {
		script.decompressThis();
		PawnInstruction wrapper = ZoneScriptAnalyzer.findSignWrapper(script);
		if (wrapper == null) {
			throw new IllegalStateException("The zone script has no sign display routine (present in 69 of 536 vanilla zones).");
		}
		return TalkerScriptWizard.cloneCallerSub(script, wrapper, new int[]{signType, msgLine, SIGN_ARG_BYTES});
	}

	/**
	 * Appends a vanilla-shaped give-item script (PROC; PUSH mode(1);
	 * PUSH count; PUSH item; PUSH 12; CALL giveWrapper; ZERO_PRI; RETN - the
	 * decoded give call-site shape) to the zone script and registers it in
	 * the dispatch. The giver is repeatable: the vanilla one-shot flag guard
	 * is not templated yet.
	 *
	 * @param script a decompressThis()'d zone script, modified in place
	 * @param itemId the item to give (1..ITEM_ID_MAX)
	 * @param count how many to give (1..99)
	 * @return the newly allocated local script ID (dispatch case key)
	 * @throws IllegalStateException if the zone script has no give-item
	 * wrapper or the TalkerScriptWizard machinery refuses; the script is not
	 * modified in that case
	 */
	public static int addItemGiverScript(GFLPawnScript script, int itemId, int count) {
		if (itemId < 1 || itemId > ITEM_ID_MAX) {
			throw new IllegalStateException("Item ID " + itemId + " is out of range 1.." + ITEM_ID_MAX + ".");
		}
		if (count < 1 || count > 99) {
			throw new IllegalStateException("Item count " + count + " is out of range 1..99.");
		}
		script.decompressThis();
		PawnInstruction wrapper = ZoneScriptAnalyzer.findGiveWrapper(script);
		if (wrapper == null) {
			throw new IllegalStateException("The zone script has no give-item routine (present in 120 of 536 vanilla zones).");
		}
		return TalkerScriptWizard.cloneCallerSub(script, wrapper, new int[]{GIVE_MODE, count, itemId, GIVE_ARG_BYTES});
	}

	/**
	 * Matches the case target of caseKey against the 7-instruction sign shape
	 * with a verified sign-wrapper callee.
	 *
	 * @return the matched sign, or null if the case is not a vanilla-shaped
	 * sign
	 */
	public static SignPattern findSignPattern(GFLPawnScript script, int caseKey) {
		ZoneScriptAnalyzer.CallerPattern cp = ZoneScriptAnalyzer.findCallerPattern(script, caseKey, 3);
		if (cp == null || cp.pushConsts[2] != SIGN_ARG_BYTES) {
			return null;
		}
		if (!ZoneScriptAnalyzer.isSignWrapperEntry(script, cp.wrapperEntry)) {
			return null;
		}
		SignPattern sp = new SignPattern();
		//push order is deepest-argument-first: type, then line (wrapper(line, type))
		sp.signType = cp.pushConsts[0];
		sp.msgLine = cp.pushConsts[1];
		sp.subEntry = cp.subEntry;
		sp.msgLinePush = cp.pushes[1];
		sp.wrapperEntry = cp.wrapperEntry;
		return sp;
	}

	/**
	 * Matches the case target of caseKey against the 8-instruction give-item
	 * shape with a verified give-wrapper callee.
	 *
	 * @return the matched giver, or null if the case is not a vanilla-shaped
	 * item giver
	 */
	public static GiverPattern findGiverPattern(GFLPawnScript script, int caseKey) {
		ZoneScriptAnalyzer.CallerPattern cp = ZoneScriptAnalyzer.findCallerPattern(script, caseKey, 4);
		if (cp == null || cp.pushConsts[0] != GIVE_MODE || cp.pushConsts[3] != GIVE_ARG_BYTES) {
			return null;
		}
		if (!ZoneScriptAnalyzer.isGiveWrapperEntry(script, cp.wrapperEntry)) {
			return null;
		}
		GiverPattern gp = new GiverPattern();
		gp.count = cp.pushConsts[1];
		gp.itemId = cp.pushConsts[2];
		gp.subEntry = cp.subEntry;
		gp.wrapperEntry = cp.wrapperEntry;
		return gp;
	}

	/**
	 * Builds the furniture record that makes a sign script interactable: the
	 * vanilla 1x1 sign profile (u2=3, u4=0, u6=0, w=1, h=1, u10=0 - all 115
	 * vanilla u2==3 records share it, only the height offset u10 ever
	 * varies).
	 *
	 * @param caseId the sign's local script ID (from addSignScript)
	 * @param xTile tile X of the interaction spot
	 * @param yTile tile Y of the interaction spot
	 */
	public static ZoneEntities.Prop makeSignFurniture(int caseId, int xTile, int yTile) {
		ZoneEntities.Prop p = new ZoneEntities.Prop();
		p.script = caseId;
		p.unknown2 = SIGN_FURNITURE_U2;
		p.x = xTile;
		p.y = yTile;
		//w = h = 1 from the constructor; unknown4/unknown6/unknown10 stay 0
		return p;
	}

	/**
	 * Builds a plain scripted-NPC record (the profile the talking-NPC wizard
	 * has always used: everything zero except position/model/script, area
	 * 1x1, multi-zone links unset). Used for user script IDs such as a
	 * freshly cloned item giver.
	 *
	 * @param uid free NPC UID (nextFreeUid)
	 * @param model MoveModel UID
	 * @param scriptId the local script ID the NPC runs on interaction
	 */
	public static ZoneEntities.NPC makeScriptedNpc(int uid, int model, int scriptId, int xTile, int yTile) {
		ZoneEntities.NPC npc = new ZoneEntities.NPC();
		npc.uid = uid;
		npc.model = model;
		npc.script = scriptId;
		npc.xTile = xTile;
		npc.yTile = yTile;
		return npc;
	}

	/**
	 * Builds a single-trainer NPC record: script = 3000 + trainer ID, the
	 * dominant vanilla stander profile (movePerm1=0, movePerm2=1,
	 * spawnFlag=0, area 0,0,1x1, u10/u12/leash zero, multi-zone -1/-1/-1/0).
	 * The battle only exists if trainer data slot trainerId is valid - edit
	 * party/class in pk3DS.
	 *
	 * @param uid free NPC UID (nextFreeUid)
	 * @param trainerId trainer data slot (1..TRAINER_ID_MAX)
	 * @param model MoveModel UID
	 * @param sightRange tiles of sight (0 = battle on talk only; vanilla max 8)
	 * @param faceDirection 0=down 1=up 2=left 3=right
	 */
	public static ZoneEntities.NPC makeTrainerNpc(int uid, int trainerId, int model, int sightRange, int faceDirection, int xTile, int yTile) {
		if (trainerId < 1 || trainerId > TRAINER_ID_MAX) {
			throw new IllegalStateException("Trainer ID " + trainerId + " is out of range 1.." + TRAINER_ID_MAX + ".");
		}
		ZoneEntities.NPC npc = new ZoneEntities.NPC();
		npc.uid = uid;
		npc.model = model;
		npc.script = TRAINER_SCRIPT_BASE + trainerId;
		npc.movePerm2 = TRAINER_MOVE_EVENT;
		npc.sightRange = sightRange;
		npc.faceDirection = faceDirection;
		npc.xTile = xTile;
		npc.yTile = yTile;
		return npc;
	}

	/**
	 * Builds the second NPC of a double-battle pair: script = 5000 + trainer
	 * ID, movePerm2=9 - the exact profile of all 21 vanilla pair NPCs. Place
	 * it in the same zone as (and usually adjacent to) the 3000+ twin of the
	 * same trainer ID.
	 */
	public static ZoneEntities.NPC makeTrainerPairNpc(int uid, int trainerId, int model, int sightRange, int faceDirection, int xTile, int yTile) {
		ZoneEntities.NPC npc = makeTrainerNpc(uid, trainerId, model, sightRange, faceDirection, xTile, yTile);
		npc.script = TRAINER_PAIR_SCRIPT_BASE + trainerId;
		npc.movePerm2 = TRAINER_PAIR_MOVE_EVENT;
		return npc;
	}

	/**
	 * First NPC UID above every UID in use (the idiom the NPC editor has
	 * always used - free gaps are not reused).
	 */
	public static int nextFreeUid(ZoneEntities e) {
		int uid = 0;
		for (int i = 0; i < e.npcs.size(); i++) {
			uid = Math.max(e.npcs.get(i).uid + 1, uid);
		}
		return uid;
	}
}
