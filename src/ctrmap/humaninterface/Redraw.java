package ctrmap.humaninterface;

/**
 * "What I changed is on screen somewhere": the editor's views are out of date
 * and should be drawn again.
 *
 * <p>WHY THIS EXISTS. Eleven classes said this by calling
 * {@code CtrmapMainframe.frame.repaint()} - reaching into the main window's
 * statics for its JFrame and repainting the lot, because none of them knows
 * which view is showing the thing they changed. Two of them had already
 * noticed the shape of the problem and written the same private helper:
 * NPCEditForm and WarpEditForm each wrap the call in a null check, and
 * WarpEditForm's comment says why - "the three buttons above are otherwise
 * unreachable from a guard, because a headless run has no JFrame to repaint
 * and frame.repaint() threw before any of them could be asked what they did".
 *
 * <p>That is the whole case for this interface. A JFrame cannot be built
 * without a display, so every line that touched one was a line no headless
 * suite could reach; asking for a redraw through something handed in makes
 * "it asked to be redrawn" an ordinary fact a test can assert, and the
 * application hands it the one that repaints its window.
 */
public interface Redraw {

	/**
	 * Draw the editor's views again. Called after a change whose author does
	 * not know which view shows it - which is every caller here.
	 */
	void all();
}
