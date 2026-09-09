package ctrmap.tests;

import ctrmap.humaninterface.ViewportCentre;
import java.awt.Point;

/**
 * The viewport centre a suite hands a form: a fixed tile, and a count of how
 * many times it was asked.
 *
 * <p>Before {@link ViewportCentre} existed, "a new record is placed where the
 * user is looking" could not be asserted without a real map view scrolled to a
 * real position - so ten call sites across three forms went unasserted, and a
 * headless suite that built one of those forms got a null map view and an
 * exception if it tried to add anything.
 *
 * <p>It counts the asks because the seam's contract is that it answers FRESH
 * each time: a form that places three records while the user scrolls between
 * them must ask three times, not capture one answer.
 */
public class RecordingCentre implements ViewportCentre {

	/** The tile this stands at. Public so a section can move the viewport. */
	public Point at;

	/** How many times a form has asked where the user is looking. */
	public int asks = 0;

	public RecordingCentre() {
		this(new Point(0, 0));
	}

	public RecordingCentre(Point at) {
		this.at = at;
	}

	@Override
	public Point tile() {
		asks++;
		return new Point(at);
	}
}
