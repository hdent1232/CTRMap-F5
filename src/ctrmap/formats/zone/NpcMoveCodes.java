package ctrmap.formats.zone;

/**
 * The two movement-behaviour codes on an NPC record, and the names the editor
 * shows for them.
 *
 * <p>{@link ZoneEntities.NPC#movePerm1} is a dense enum: code N is the Nth
 * name, so a dropdown over {@link #movePerm1Labels} needs no mapping at all.
 *
 * <p>{@link ZoneEntities.NPC#movePerm2} is NOT dense - the game defines a
 * scattered set of codes (0-4, 7, 9, 12, 14, 15, 16, 20, 22, 23) with gaps
 * between them, and the last two mean different things in XY and in ORAS. A
 * dropdown therefore needs a translation in both directions, and that pair of
 * translations is the whole reason this class exists.
 *
 * <h2>Why it is a table and not two switch statements</h2>
 * It used to be two hand-written switches in the NPC form - one raw-to-index,
 * one index-to-raw - and they disagreed. Measured against the pristine ORAS
 * dump (2904 NPCs across 536 zones):
 * <ul>
 * <li>code 22 ("Approaching diver") is worn by 18 retail NPCs. Raw 22 selected
 *     dropdown row 12, and row 12 wrote back raw 11 - a code ORAS does not
 *     define - because the reverse switch answered row 12 with XY's answer
 *     before it ever looked at which game was loaded;</li>
 * <li>code 9 is worn by 42 retail NPCs. Raw 9 selected row 6, and row 6 wrote
 *     back raw 10, which no retail NPC uses at all.</li>
 * </ul>
 * So 60 of the game's own NPCs had their movement type silently rewritten by
 * the act of touching the dropdown that was supposed to be showing it - a
 * behaviour edit the user never asked for and was never told about. The
 * dropdown fires on re-picking the row already highlighted, so merely opening
 * it to read the value was enough.
 *
 * <p>Two directions written by hand can disagree; one table cannot. Both
 * directions here are derived from {@link #movePerm2Raws}, so the round trip
 * holds by construction, and NpcMoveCodesTest asserts it for every row of
 * every game plus every code the retail dump actually contains.
 *
 * <h2>Codes this class does not know</h2>
 * {@link #movePerm2Index} answers -1 for a code that is not in the table, and
 * the form leaves the dropdown unselected. The raw value stays on the record
 * untouched: an unrecognised code is a thing to preserve, not to round off to
 * the nearest one with a name.
 */
public final class NpcMoveCodes {

	private NpcMoveCodes() {
	}

	/** Movement codes every game in the family defines. Code == index. */
	private static final String[] MOVE1_BASE = {
		"Dummy",
		"MoveCodeNone",
		"MoveCodeDirRnd",
		"MoveCodeDirRndUD",
		"MoveCodeDirRndLR",
		"MoveCodeDirRndUL",
		"MoveCodeDirRndUR",
		"MoveCodeDirRndDL",
		"MoveCodeDirRndDR",
		"MoveCodeDirRndUDL",
		"MoveCodeDirRndUDR",
		"MoveCodeDirRndULR",
		"MoveCodeDirRndDLR",
		"MoveCodeUp",
		"MoveCodeDown",
		"MoveCodeLeft",
		"MoveCodeRight",
		"MoveCodeRndHLim",
		"MoveCodeRndBLim",
		"Move constant in left vertical limit",
		"Move constant in left vertical & upper horizontal limit",
		"Move constant in left vertical & upper horizontal limit; H first",
		"Move constant in left vertical & upper horizontal limit; V first 1",
		"Move constant in left vertical & upper horizontal limit; V first 2",
		"???",
		"???",
		"???",
		"Move constant in right vertical & upper horizontal limit",
		"???",
		"Move constant in left vertical & lower horizontal limit",
		"???",
		"Surf along vertical limit",
		"???",
		"???",
		"???",
		"MoveCodeSit",
		"MoveCodeAlongWallLeftHandLimitChange 1",
		"MoveCodeAlongWallLeftHandLimitChange 2",
		"MoveCodeAlongWallLeftHandLimitChange; VH limit invert",
		"MoveCodeAlongWallLeftHandLimitChange; H limit invert",
		"???",
		"MoveCodeAlongWallRightHandLimitChange; V limit invert",
		"???",
		"MoveCodeAlongWallRightHandLimitChange",
		"???",
		"???",
		"Clockwise cruise along edges of area with unwalkables on edges",
		"MoveCodeRand",
		"???",
		"???",
		"MoveCodeAlongBitLeftHandRoller",
		"MoveCodeAlongBitLeftHandRoller",
		"MoveCodeKakuremino",
		"???",
		"???",
		"???",
		"MoveCodeTsutikemuriAlongBitLeftHand",
		"MoveCodeTsutikemuriAlongBitRightHand",
		"MoveCodeTsutikemuriRand"
	};

	/** Movement codes XY appends after {@link #MOVE1_BASE}. */
	private static final String[] MOVE1_XY = {
		"MoveCodeYagiQuick",
		"MoveCodeYagiSlow",
		"MoveCodeYagiStay"
	};

	/** Movement codes ORAS appends after {@link #MOVE1_BASE}. */
	private static final String[] MOVE1_OA = {
		"???",
		"MoveCodeSeiza",
		"MoveCodeSecretBaseTrainer",
		"MoveCodeFishing2"
	};

	/** AI-motion names shared by both games, in dropdown order. */
	private static final String[] MOVE2_BASE = {
		"None",
		"Approaching trainer (Standard)",
		"Approaching trainer - see 1 tile around extra",
		"Approaching trainer - tilt head in expectation",
		"Item",
		"AI Motion",
		"Approaching trainer - paired",
		"Mirror trainer (down)",
		"Fixed line of sight (left)",
		"Fixed line of sight (right)",
		"Mirror trainer pair (down)",
		"Jumpout trainer"
	};

	/**
	 * The record code behind each row of {@link #MOVE2_BASE}. The gaps (5, 6, 8,
	 * 10, 11, 13, 17-19, 21) are codes with no name in either game; they keep
	 * their value and show as no selection.
	 *
	 * <p>Row 6 is code 9. The two hand-written switches this table replaced read
	 * 9 for XY and 10 for ORAS, and 42 retail ORAS NPCs carry 9 while none
	 * carries 10 - so the reverse direction was rewriting the code the game
	 * ships. The name shown for code 9 has not changed; only the code written
	 * back for that name has, and it now matches what was read.
	 */
	private static final int[] MOVE2_BASE_RAW = {0, 1, 2, 3, 4, 7, 9, 12, 14, 15, 16, 20};

	/** AI-motion names XY appends, and their codes. */
	private static final String[] MOVE2_XY = {
		"Approaching Painter (XY Extension)",
		"Rolling skater (XY Extension)",
		"EvTypeTrFighting (GymFight Skater) (XY Extension)"
	};
	private static final int[] MOVE2_XY_RAW = {11, 22, 23};

	/** AI-motion names ORAS appends, and their codes. */
	private static final String[] MOVE2_OA = {
		"Approaching diver (OA Extension)",
		"Secret Base Trainer (OA Extension)"
	};
	private static final int[] MOVE2_OA_RAW = {22, 23};

	/**
	 * Names for {@link ZoneEntities.NPC#movePerm1}, in code order: row N is
	 * code N, which is why the form needs no mapping for this one.
	 *
	 * @param xy true for XY, false for ORAS (and anything else).
	 */
	public static String[] movePerm1Labels(boolean xy) {
		return concat(MOVE1_BASE, xy ? MOVE1_XY : MOVE1_OA);
	}

	/**
	 * Names for {@link ZoneEntities.NPC#movePerm2}, in dropdown order. Row N
	 * means code {@code movePerm2Raws(xy)[N]}, NOT code N.
	 *
	 * @param xy true for XY, false for ORAS (and anything else).
	 */
	public static String[] movePerm2Labels(boolean xy) {
		return concat(MOVE2_BASE, xy ? MOVE2_XY : MOVE2_OA);
	}

	/**
	 * The record code behind each row of {@link #movePerm2Labels}. Same length,
	 * same order; this pairing is the single fact both directions are read off.
	 *
	 * @param xy true for XY, false for ORAS (and anything else).
	 */
	public static int[] movePerm2Raws(boolean xy) {
		int[] ext = xy ? MOVE2_XY_RAW : MOVE2_OA_RAW;
		int[] out = new int[MOVE2_BASE_RAW.length + ext.length];
		System.arraycopy(MOVE2_BASE_RAW, 0, out, 0, MOVE2_BASE_RAW.length);
		System.arraycopy(ext, 0, out, MOVE2_BASE_RAW.length, ext.length);
		return out;
	}

	/**
	 * The dropdown row that shows movePerm2 code {@code raw}, or -1 when this
	 * game gives that code no name. -1 means "leave the dropdown unselected and
	 * the record alone" - never "pick something close".
	 *
	 * @param xy true for XY, false for ORAS (and anything else).
	 */
	public static int movePerm2Index(int raw, boolean xy) {
		int[] raws = movePerm2Raws(xy);
		for (int i = 0; i < raws.length; i++) {
			if (raws[i] == raw) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * The movePerm2 code written by dropdown row {@code index}, or -1 when the
	 * row is out of range (which includes the "nothing selected" -1).
	 *
	 * @param xy true for XY, false for ORAS (and anything else).
	 */
	public static int movePerm2Raw(int index, boolean xy) {
		int[] raws = movePerm2Raws(xy);
		return index < 0 || index >= raws.length ? -1 : raws[index];
	}

	private static String[] concat(String[] a, String[] b) {
		String[] out = new String[a.length + b.length];
		System.arraycopy(a, 0, out, 0, a.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}
}
