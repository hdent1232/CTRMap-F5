package ctrmap.humaninterface;

import java.awt.Point;

/**
 * Where the user is looking: the map tile at the centre of the viewport.
 *
 * <p>WHY THIS EXISTS. Three forms place a NEW record - a warp, a trigger, an
 * NPC and its six wizard variants - and every one of them answered the same
 * question the same way, by reaching into the main window for the map view and
 * asking it {@code getTileAtViewportCentre()}. Ten call sites, one question:
 * "put it where the user is looking, not at 0,0 in a corner of a map they
 * cannot see".
 *
 * <p>It is a seam rather than a passed {@code Point} because the answer changes
 * as the user scrolls, and the forms ask at the moment they place something.
 * A captured Point would place every record where the viewport was when the
 * window was built.
 *
 * <p>The warp and trigger forms each had exactly ONE read of the map view, and
 * this was it - so being handed this is the whole of what they needed from it.
 */
public interface ViewportCentre {

	/**
	 * The tile under the middle of the viewport, in map tile coordinates.
	 *
	 * <p>Answered fresh each call. A form that places three records in a row
	 * while the user scrolls between them gets three different answers, which
	 * is the behaviour the direct calls had.
	 */
	Point tile();
}
