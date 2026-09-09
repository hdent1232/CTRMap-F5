package ctrmap.humaninterface;

import java.awt.Graphics;
import java.awt.event.MouseEvent;
import ctrmap.formats.mapmatrix.MapMatrix;

/**
 * What the matrix grid and its mouse router need from the editor beside them.
 *
 * <p>WHY THIS IS A CYCLE AND NOT A MIGRATION, which matters because the survey
 * called it one-way and it is not. The panel is built before the form, so the
 * FORM can be handed the panel - that is {@link MatrixCanvas}, and it is done.
 * The panel cannot be handed the form the same way: it does not exist yet.
 * Neither can be built first without the other being null for a moment.
 *
 * <p>So this half is broken deliberately, by two-phase wiring: the panel is
 * built, the form is built over it, and then the panel is TOLD about the form,
 * once, before anything can use either. That is a decision with a cost - there
 * is a window in which the panel has no editor - and the panel says what it does
 * in that window rather than throwing.
 *
 * <p>The router has no such problem: it is built after both, so it is handed
 * this in its constructor like anything else.
 */
public interface MatrixTools {

	/** A new matrix is open; show its numbers. */
	void loadMatrix(MapMatrix mm);

	/** Draw the held tool's overlay on the grid, at the grid image's origin. */
	void drawToolGraphics(Graphics g, int imgstartx, int imgstarty);

	/** The user picked this cell; show what is in it. */
	void showRegion(int x, int y);

	/** A click, for the camera-boundary tool to interpret if it is the one up. */
	void checkCamTool(MouseEvent e);
}
