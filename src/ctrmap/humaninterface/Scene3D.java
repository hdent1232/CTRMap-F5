package ctrmap.humaninterface;

import java.util.List;

/**
 * The 3D scene both views draw: what is in it, and where the camera is pointed.
 *
 * <p>WHY THIS EXISTS. Two of the window's statics were one idea between them -
 * {@code CM3DComponents}, the list of things that get drawn, and
 * {@code m3DDebugPanel}, the view that draws them. The map view read both,
 * and it was the only class that read either.
 *
 * <p>THE CAMERA IS TWO NAMED METHODS AND NOT A FLAG, deliberately. Opening a
 * single region and opening a matrix both point the camera at the new map, and
 * the two are ALMOST the same four assignments - except that the matrix path
 * also zeroes the yaw and the single-region path does not. That asymmetry is
 * real and visible: orbit the 3D view, then open a loose GR map file, and the
 * new map comes up at the angle you left the last one. Behind a boolean it
 * would read as an oversight and someone would "fix" it; as two methods with
 * two bodies it is a difference anyone can see and decide about.
 *
 * <p>Whether that difference is CORRECT is a separate question this seam does
 * not answer. It is preserved exactly as it was.
 */
public interface Scene3D {

	/**
	 * Everything the scene draws.
	 *
	 * <p>Handed as the list itself rather than as add/remove, because the map
	 * view iterates it three times - to upload buffers, to render, and to drop
	 * buffers when the viewport is resized - and naming a method per pass would
	 * be a longer way of writing the same list.
	 */
	List<CM3DRenderable> renderables();

	/**
	 * Point the camera at a single 720-unit region.
	 *
	 * <p>Does NOT touch the yaw, which is why this is not the same call as
	 * {@link #frameMatrix} with one cell.
	 */
	void frameSingleRegion();

	/** Point the camera at a matrix this many cells across and down, yaw zeroed. */
	void frameMatrix(int cellsAcross, int cellsDown);

	/** Draw the scene again. */
	void redraw();

	/** The scene's contents changed; its GPU buffers must be rebuilt. */
	void rebuild();
}
