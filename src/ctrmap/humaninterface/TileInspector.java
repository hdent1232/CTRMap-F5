package ctrmap.humaninterface;

/**
 * The tile inspector, as the things that move the cursor see it.
 *
 * <p>WHY THIS EXISTS. Three classes told the inspector what the user had just
 * picked - the map cursor on a hover and on a click, the undo stack after
 * stepping back, and the workspace settings after a tileset change - and all
 * three reached through the main window for the form. None of them wants the
 * form; they want the tile on screen to match the tile that is selected.
 *
 * <p>LOCKING IS PART OF THE CONTRACT AND NOT AN IMPLEMENTATION DETAIL. A locked
 * inspector keeps showing the tile the user CLICKED while the mouse moves over
 * others; unlocked, it follows the hover. The cursor does lock, show, unlock in
 * a particular order, and getting that order wrong shows the wrong tile rather
 * than throwing - which is why the order is written down here.
 */
public interface TileInspector {

	/**
	 * Show this tile.
	 *
	 * @param scroll whether the inspector should bring it into view, which the
	 *        undo stack asks for and the hover does not: scrolling the panel
	 *        under a moving mouse is how you lose the tile you were aiming at.
	 */
	void showTile(int x, int y, boolean scroll);

	/** Hold the shown tile against hovers, or let it follow them again. */
	void lockTile(boolean locked);
}
