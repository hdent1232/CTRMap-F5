package ctrmap.tests;

import ctrmap.humaninterface.CM3DRenderable;
import ctrmap.humaninterface.Scene3D;
import java.util.ArrayList;
import java.util.List;

/**
 * The 3D scene a suite hands the map view: a real renderable list, and a record
 * of what the camera was told.
 *
 * <p>Before {@link Scene3D} existed, "opening a map points the camera at it"
 * could not be asserted at all - it was five field writes on a JOGL panel
 * reached through the main window, so a headless suite either planted one or
 * got a NullPointerException. The two framings are recorded separately on
 * purpose: the two paths are almost the same five assignments, and which of
 * them a load asked for is the thing most likely to be lost. What they WRITE is
 * not recorded here - there is no headless panel to write to. MainframeShapeTest
 * reads those five lines out of the window's source instead.
 */
public class RecordingScene implements Scene3D {

	private final List<CM3DRenderable> renderables = new ArrayList<>();

	/** Every camera move, in order: "single" or "matrix WxH". */
	public final List<String> framings = new ArrayList<>();

	/** How many times the scene was asked to draw itself again. */
	public int redraws = 0;

	/** How many times its buffers were marked stale. */
	public int rebuilds = 0;

	@Override
	public List<CM3DRenderable> renderables() {
		return renderables;
	}

	@Override
	public void frameSingleRegion() {
		framings.add("single");
	}

	@Override
	public void frameMatrix(int cellsAcross, int cellsDown) {
		framings.add("matrix " + cellsAcross + "x" + cellsDown);
	}

	@Override
	public void frameCells(int cellX0, int cellY0, int cellX1, int cellY1) {
		framings.add("cells " + cellX0 + "," + cellY0 + ".." + cellX1 + "," + cellY1);
	}

	@Override
	public void redraw() {
		redraws++;
	}

	@Override
	public void rebuild() {
		rebuilds++;
	}

	/** Forgets everything, so one suite section does not read another's. */
	public void reset() {
		framings.clear();
		redraws = 0;
		rebuilds = 0;
	}
}
