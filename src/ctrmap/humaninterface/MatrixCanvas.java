package ctrmap.humaninterface;

import java.awt.Point;

/**
 * What the matrix panel is to everything that draws on it: a surface to redraw,
 * and the point where the grid image starts inside it.
 *
 * <p>WHY THIS EXISTS. {@link MatrixEditForm} never used the matrix panel AS a
 * panel. It used exactly five of its members through the main window's static,
 * and fourteen of its sixteen reads were the bare line {@code
 * mMtxPanel.repaint();}. The other two were one expression, written out to work
 * out where the centred grid image begins.
 *
 * <p>THAT EXPRESSION EXISTED THREE TIMES, in three files, identically:
 * {@code (getWidth() - getFullImageWidth()) / 2} in the panel's own painting,
 * again in the form's camera-tool hit test, and a third time in the input
 * router - which had to re-derive it in SCREEN coordinates because there was
 * nowhere to ask. Three copies of "where does the picture start", each of which
 * a future change to the panel's layout would have to find.
 *
 * <p>The int division is kept exactly as it was, including the negative result
 * when the image is wider than the panel: that is not a rounding detail, it is
 * how the grid scrolls, and rewriting it as a max(0, ...) would move the picture.
 */
public interface MatrixCanvas {

	/** Draw the matrix grid again, because what it shows has changed. */
	void redraw();

	/**
	 * Where the grid image starts inside the panel, in panel coordinates.
	 *
	 * <p>Negative when the image is larger than the panel - the caller adds it
	 * to a mouse position either way, and clamping here would put the grid and
	 * the hit test in different places.
	 */
	Point imageOrigin();
}
