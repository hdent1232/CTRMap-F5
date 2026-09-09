package ctrmap.humaninterface;

/**
 * Matrix panel cursor: which cell the mouse is over, and which cell was picked.
 *
 * <p>WHAT THIS CLASS IS FOR, now that it says so. It holds four numbers and it
 * translates a position on the grid image into a cell. It used to do two more
 * things, and neither belonged to a cursor: it told the matrix FORM which region
 * had been picked, and it asked the PANEL to repaint. Both went through the main
 * window's statics, so a cursor could not exist without an editor around it.
 *
 * <p>Those two lines moved one level out, to the mouse router that calls this -
 * which is the class that knows both the panel it is attached to and the form
 * beside it. The cursor now knows about neither, and translating a click is
 * something a suite can ask it to do.
 *
 * <p>Still static, and that is the remaining problem rather than a decision:
 * four public mutable statics mean one cursor for the whole program. Splitting
 * that needs an owner for it, which is a design step and not this one.
 */
public class MatrixSelector {

	public static int hilightRegionX = -1;
	public static int hilightRegionY = -1;
	public static int selRegionX = -1;
	public static int selRegionY = -1;

	public static boolean selecting = false;
	public static boolean selectSubChunks = false;

	/**
	 * The cell under a point on the grid image.
	 *
	 * <p>The image size and the matrix size are HANDED IN rather than read off
	 * the window's matrix panel. Same arithmetic, same floor, same sub-chunk
	 * multiplier - what changes is that the caller says which grid it means.
	 */
	public static void select(int xOnImage, int yOnImage, int imageWidth, int imageHeight,
			int cellsAcross, int cellsDown) {
		hilightRegionX = (int) (Math.floor(((float) xOnImage / imageWidth) * (double) (cellsAcross * (selectSubChunks ? 4 : 1))));
		hilightRegionY = (int) (Math.floor(((float) yOnImage / imageHeight) * (double) (cellsDown * (selectSubChunks ? 4 : 1))));
	}

	/**
	 * Picks the cell the mouse is over.
	 *
	 * <p>ONLY that. This used to also call the form's showRegion and repaint the
	 * panel, both through the window - so "remember which cell was clicked" and
	 * "tell the editor about it" were one indivisible thing, and a cursor needed
	 * an editor to exist. The router does those two now, in that order, right
	 * after this returns.
	 */
	public static void acqCurTile() {
		selRegionX = hilightRegionX;
		selRegionY = hilightRegionY;
	}

	public static void deselect() {
		hilightRegionX = -1;
		hilightRegionY = -1;
	}

	public static void unfocus() {
		selRegionX = -1;
		selRegionY = -1;
	}
}
