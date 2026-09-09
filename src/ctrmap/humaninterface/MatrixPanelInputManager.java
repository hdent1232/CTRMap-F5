package ctrmap.humaninterface;

import ctrmap.CtrmapMainframe;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

import javax.swing.event.MouseInputListener;

import static ctrmap.CtrmapMainframe.*;

/**
 * Matrix panel input listener.
 */
public class MatrixPanelInputManager implements MouseWheelListener, MouseMotionListener, MouseInputListener {
	
	private MapMatrixPanel parent;
	
	/** The editor this router drives, handed in: it is built after both. */
	private final MatrixTools tools;

	public MatrixPanelInputManager(MapMatrixPanel parent, MatrixTools tools){
		super();
		if (tools == null) {
			throw new IllegalArgumentException("the matrix router must be handed the editor it drives");
		}
		this.tools = tools;
		this.parent = parent;
		parent.addMouseWheelListener(this);
		parent.addMouseMotionListener(this);
		parent.addMouseListener(this);
	}
	
	@Override
	public void mouseWheelMoved(MouseWheelEvent e) {
	}
	
	@Override
	public void mouseDragged(MouseEvent e) {
		moveSelector(e);
	}
	
	@Override
	public void mouseMoved(MouseEvent e) {
		moveSelector(e);
	}
	
	private void moveSelector(MouseEvent e) {
		//the same origin the panel paints at, asked for rather than re-derived:
		//this copy had to be written in SCREEN coordinates because there was
		//nowhere to ask, and a change to the panel's layout had to find it here
		java.awt.Point origin = parent.imageOrigin();
		int xbound = (int) (parent.getLocationOnScreen().getX() + origin.x);
		int ybound = (int) (parent.getLocationOnScreen().getY() + origin.y);
		if (e.getXOnScreen() >= xbound && e.getXOnScreen() < xbound + parent.getFullImageWidth()
				&& e.getYOnScreen() >= ybound && e.getYOnScreen() < ybound + parent.getFullImageHeight()) {
			MatrixSelector.select(e.getXOnScreen() - xbound, e.getYOnScreen() - ybound,
					parent.getFullImageWidth(), parent.getFullImageHeight(),
					parent.mm.width, parent.mm.height);
		} else {
			if (MatrixSelector.hilightRegionX != -1) {
				MatrixSelector.deselect();
			}
		}
		parent.repaint();
	}
	
	@Override
	public void mouseClicked(MouseEvent e) {
		MatrixSelector.acqCurTile();
		//these two used to live INSIDE acqCurTile, which meant the cursor reached
		//through the window for the form and the panel. They belong here: this is
		//the class that already knows both, and the order is the one they had.
		tools.showRegion(MatrixSelector.selRegionX, MatrixSelector.selRegionY);
		parent.redraw();
		tools.checkCamTool(e);
	}
	
	@Override
	public void mouseEntered(MouseEvent arg0) {
		
	}
	
	@Override
	public void mouseExited(MouseEvent arg0) {
		
	}
	
	@Override
	public void mousePressed(MouseEvent e) {
	}
	
	@Override
	public void mouseReleased(MouseEvent e) {
	}
}
