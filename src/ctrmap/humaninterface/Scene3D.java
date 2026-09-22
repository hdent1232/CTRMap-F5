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
 * single region and opening a matrix both point the camera at the new map with
 * almost the same five assignments - the single region's are constants, the
 * matrix's are computed from its size. Behind a boolean the difference would
 * read as an oversight; as two methods with two bodies anyone can see it.
 *
 * <p>ONE OF THOSE DIFFERENCES WAS A DEFECT AND IS NOW GONE. The matrix path
 * zeroed the yaw and the single-region path did not, so orbiting the 3D view
 * and then opening a loose GR map file brought the new map up at the angle the
 * last one was left at. Both zero it now: framing a map means a known view of
 * it, and the pitch was already reset either way. MainframeShapeTest holds it.
 *
 * <p>THE DIFFERENCE STILL HERE IS PINNED, NOT FIXED: the single region frames
 * at translateX 0 where the matrix formula gives -360 for one cell across, so a
 * loose GR sits half a region off centre under a comment that says it is being
 * centred. Reported and left alone - changing it moves every loose GR's view.
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
	 * <p>Zeroes the yaw, as {@link #frameMatrix} does. Still not the same call:
	 * this frames at translateX 0, where the matrix formula gives -360 for one
	 * cell across.
	 */
	void frameSingleRegion();

	/** Point the camera at a matrix this many cells across and down, yaw zeroed. */
	void frameMatrix(int cellsAcross, int cellsDown);

	/** Draw the scene again. */
	void redraw();

	/** The scene's contents changed; its GPU buffers must be rebuilt. */
	void rebuild();
}
