package ctrmap.formats.zone;

/**
 * A warp's transition code ({@link ZoneEntities.Warp#transitionType}) and the
 * name the editor shows for it - one table, read in both directions.
 *
 * <p>The codes are not dense. Six are shared by every game in the family and
 * the rest are scattered (15..26, 54..57) with different meanings in XY and in
 * ORAS, so a dropdown needs a translation each way. It used to be two
 * hand-written switch statements in the warp form, the same shape as the pair
 * NpcMoveCodes replaced, and they disagreed the same way:
 * <ul>
 * <li>XY row 10 ("Camera move up low") was selected by code 55 and wrote back
 *     code 22 - which row 14 also writes - so code 55 could be shown but never
 *     saved, and a warp wearing it was silently rewritten by the act of saving
 *     the form;</li>
 * <li>the game-neutral half of the switch mapped codes 7, 8 and 9 (XY's) onto
 *     ORAS rows 6, 7 and 8, which write back 10, 11 and 55, and codes 10 and 11
 *     (ORAS's) onto XY rows 6 and 7, which write back 7 and 8. Measured against
 *     the pristine ORAS dump (911 warps in 536 zones) no retail warp wears
 *     7, 8 or 9, so this one had not bitten yet; the row-10 defect needs an XY
 *     dump to measure and this project has none, which is exactly why the
 *     table has to hold by construction rather than by observation;</li>
 * <li>a code neither switch knew selected row -1, and saving then wrote
 *     {@code getTransitionRaw(-1)} = -1 into the record.</li>
 * </ul>
 *
 * <p>Both directions come off {@link #raws}: {@link #index} searches the same
 * array {@link #raw} indexes, so the round trip holds for every row of every
 * game, and WarpTransitionsTest asserts it plus every code the retail dump
 * contains. A code the table does not know answers -1 from {@link #index};
 * the form leaves the dropdown unselected and, on save, keeps the record's
 * code untouched rather than rounding it to a named neighbour.
 *
 * <p>The names are the ones the form always showed; only the code a name
 * writes back has changed, and only where the two old directions disagreed.
 */
public final class WarpTransitions {

	private WarpTransitions() {
	}

	/** Transition codes every game in the family defines, in dropdown order. */
	private static final int[] BASE_RAWS = {0, 2, 3, 4, 5, 6};
	private static final String[] BASE_LABELS = {
		"0 - Warp of No Return",
		"2 - Warp on touch; Arrive at warp.",
		"3 - Warp on touch, push; Arrive at neighbor.",
		"4 - Arrival autotrigger (Contest/Salon)",
		"5 - Warp on walk; Arrive at neighbor",
		"6 - Warp pad"
	};

	private static final int[] XY_RAWS = {7, 8, 9, 15, 55, 16, 17, 56, 22, 57, 54};
	private static final String[] XY_LABELS = {
		"Lumiose City camera rotate",
		"=2/No arrival FX",
		"=3/No arrival FX",
		"Camera move down (obtuse angle)",
		"Camera move up low",
		"Camera move up high",
		"Camera move up high 2",
		"Camera move up highest",
		"Camera rotate up low",
		"Camera rotate up high",
		"Camera rotate in warp direction (FlareHQ)"
	};

	private static final int[] ORAS_RAWS = {10, 11, 55, 56, 54, 17, 18, 24, 23, 22, 26, 20, 16, 19, 15, 21, 57, 25};
	private static final String[] ORAS_LABELS = {
		"Ladder up",
		"Ladder down",
		"Camera move up low (slow)",
		"Camera move up low (fast)",
		"Camera move up low (lowest angle)",
		"Camera move up low (lower angle)",
		"Camera move up low (low angle)",
		"Camera move up high",
		"Camera move up higher",
		"Camera move up highest",
		"Camera move up high (slow)",
		"Camera move up high (lower angle)",
		"Camera move up high (low angle)",
		"Camera move up and left",
		"Camera move up and right",
		"Camera move forward",
		"Camera move above player (90deg)",
		"Camera zoom out"
	};

	/** The dropdown's rows, in order: the shared six, then the game's own. */
	public static String[] labels(boolean xy) {
		return concat(BASE_LABELS, xy ? XY_LABELS : ORAS_LABELS);
	}

	/** The code each row of {@link #labels} writes, in the same order. */
	public static int[] raws(boolean xy) {
		return concat(BASE_RAWS, xy ? XY_RAWS : ORAS_RAWS);
	}

	/** The row that shows {@code raw}, or -1 when the table has no name for it. */
	public static int index(int raw, boolean xy) {
		int[] raws = raws(xy);
		for (int i = 0; i < raws.length; i++) {
			if (raws[i] == raw) {
				return i;
			}
		}
		return -1;
	}

	/** The code row {@code index} writes, or -1 for no selection or a row past the end. */
	public static int raw(int index, boolean xy) {
		int[] raws = raws(xy);
		return index >= 0 && index < raws.length ? raws[index] : -1;
	}

	private static String[] concat(String[] a, String[] b) {
		String[] out = new String[a.length + b.length];
		System.arraycopy(a, 0, out, 0, a.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	private static int[] concat(int[] a, int[] b) {
		int[] out = new int[a.length + b.length];
		System.arraycopy(a, 0, out, 0, a.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}
}
