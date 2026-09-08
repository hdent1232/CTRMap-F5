package ctrmap.humaninterface.tools;

import ctrmap.humaninterface.PaintForm;
import ctrmap.humaninterface.Selector;
import java.awt.Graphics;
import java.awt.event.MouseEvent;
import javax.swing.SwingUtilities;

/**
 * The Map Painter as a first-class World Editor tool: the side form holds the
 * brushes/buildings/lighting, and painting happens directly on the main map
 * view (the painted cell is drawn as an overlay at its real position).
 */
public class PaintTool extends AbstractTool {

	/** The map painter it hands gestures to. */
	private final PaintForm form;

	public PaintTool(ToolHost host, PaintForm form) {
		super(host);
		this.form = form;
	}

	@Override
	public void onToolInit() {
		host.showToolUi(form);
		form.activate();
	}

	@Override
	public void onTileClick(MouseEvent e) {
		//press already handled the gesture (click fires after release)
	}

	@Override
	public void onTileMouseDown(MouseEvent e) {
		if (Selector.hilightTileX != -1) {
			form.gesturePress(Selector.hilightTileX, Selector.hilightTileY,
					SwingUtilities.isRightMouseButton(e));
		}
	}

	@Override
	public void onTileMouseUp(MouseEvent e) {
		CM2DNoUpdate = false;
	}

	@Override
	public void onTileMouseDragged(MouseEvent e) {
		if (Selector.hilightTileX != -1) {
			CM2DNoUpdate = true;
			form.gestureDrag(Selector.hilightTileX, Selector.hilightTileY,
					SwingUtilities.isRightMouseButton(e));
		}
	}

	@Override
	public void onToolShutdown() {
		form.deactivate();
		Selector.unfocus();
	}

	@Override
	public void fireCancel() {
		form.cancelPending();
	}

	@Override
	public void drawOverlay(Graphics g, int imgstartx, int imgstarty, double globimgdim) {
		form.drawOverlay(g, imgstartx, imgstarty, globimgdim);
	}

	@Override
	public boolean getSelectorEnabled() {
		return true;
	}

	@Override
	public void updateComponents() {
	}

	@Override
	public boolean getNaviEnabled() {
		return false;
	}
}
