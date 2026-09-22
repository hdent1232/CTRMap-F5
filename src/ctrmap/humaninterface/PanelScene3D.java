package ctrmap.humaninterface;

import java.util.List;
import java.util.function.Supplier;

/**
 * A {@link Scene3D} over one {@link H3DRenderingPanel} - the camera arithmetic,
 * in one place, for every view that draws a map.
 *
 * <p>WHY THIS IS A CLASS AND NOT AN ANONYMOUS BODY. It was an anonymous body
 * inside the main window, which was fine while the window was the only thing
 * that drew a map. The moment a second view needed one - the Zone Loader's
 * preview - the choice was to copy five assignments or to name them. Copying is
 * how this application ends up with two answers to "where does the camera go",
 * and the owner has had to point out that habit more than once; the second copy
 * is always the one that rots, because the fix lands in the first.
 *
 * <p>THE PANEL IS SUPPLIED, NOT HANDED IN, because the main window builds its
 * scene sixty lines before it builds the panel the scene draws on. Handing the
 * panel here would hand null - a mistake this window has already made once
 * (MainframeEdgesTest section 4) - so the body reads it when it is CALLED.
 */
public final class PanelScene3D implements Scene3D {

	private final Supplier<H3DRenderingPanel> view;
	private final List<CM3DRenderable> drawn;

	/**
	 * @param view the panel this scene draws on, read when needed
	 * @param drawn everything the scene draws
	 */
	public PanelScene3D(Supplier<H3DRenderingPanel> view, List<CM3DRenderable> drawn) {
		if (view == null || drawn == null) {
			throw new IllegalArgumentException("PanelScene3D must be given a view and a list to draw");
		}
		this.view = view;
		this.drawn = drawn;
	}

	@Override
	public List<CM3DRenderable> renderables() {
		return drawn;
	}

	@Override
	public void frameSingleRegion() {
		H3DRenderingPanel panel = view.get();
		if (panel == null) {
			return;
		}
		panel.translateX = 0f;   //720/2 to center the camera
		panel.translateY = -360f;
		panel.translateZ = -720f;   //at the end of the map vertically
		panel.rotateX = 45f;
		//AND THE YAW, which this used to leave alone - decided, not tidied. THREE of
		//the four lines above are the matrix body's with the cell counts written out
		//as constants, and its fifth line was never copied here: orbit the 3D view
		//(a left-button drag), then open a loose GR map file, and the map came up at
		//the angle the last one was left at, while the same gesture ending in a
		//matrix load came up square. Framing a map means a known view of it, and the
		//pitch one line up was already being reset either way, so the camera was
		//never the user's to keep. Zeroed here because that is what the sibling
		//below - the everyday path - already does. The FOURTH line is the one real
		//difference left: translateX 0 against the formula's -360f for one cell
		//across. REPORTED and left alone; changing it moves every loose GR's view.
		panel.rotateY = 0f;
	}

	@Override
	public void frameMatrix(int cellsAcross, int cellsDown) {
		H3DRenderingPanel panel = view.get();
		if (panel == null) {
			return;
		}
		panel.translateX = -cellsAcross * 360f;   //720/2 to center the camera
		panel.translateY = -cellsDown * 360f;
		panel.translateZ = -cellsDown * 720f;   //at the end of the map vertically
		panel.rotateX = 45f;
		panel.rotateY = 0f;
	}

	@Override
	public void frameCells(int cellX0, int cellY0, int cellX1, int cellY1) {
		H3DRenderingPanel panel = view.get();
		if (panel == null) {
			return;
		}
		int x0 = Math.min(cellX0, cellX1);
		int y0 = Math.min(cellY0, cellY1);
		int across = Math.abs(cellX1 - cellX0) + 1;
		int down = Math.abs(cellY1 - cellY0) + 1;
		//THE SAME ARITHMETIC AS frameMatrix, with an origin. That method centres on a matrix
		//by halving it - cellsAcross * 360 is (cellsAcross * 720) / 2 - and pulls the camera
		//back by the height it just framed. Here the centre is offset by the corner the
		//rectangle starts at, and the pull-back uses the LARGER of the two spans: a matrix is
		//usually taller than it is wide, so its own height is enough, while a zone occupying
		//three cells across and one down would sit half outside the view if height alone
		//decided it. Framing one cell keeps a cell's worth of neighbours visible, which is the
		//difference between "which part is this" and "what is this a picture of".
		float centreX = (x0 + across / 2f) * 720f;
		float centreY = (y0 + down / 2f) * 720f;
		panel.translateX = -centreX;
		panel.translateY = -centreY;
		panel.translateZ = -Math.max(across, down) * 720f;
		panel.rotateX = 45f;
		panel.rotateY = 0f;
	}

	@Override
	public void redraw() {
		H3DRenderingPanel panel = view.get();
		if (panel != null) {
			panel.repaint();
		}
	}

	@Override
	public void rebuild() {
		H3DRenderingPanel panel = view.get();
		if (panel != null) {
			panel.reload = true;
		}
	}
}
