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
	
	public MatrixPanelInputManager(MapMatrixPanel parent){
		super();
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
		int xbound = (int) (parent.getLocationOnScreen().getX() + (parent.getWidth() - parent.getFullImageWidth()) / 2);
		int ybound = (int) (parent.getLocationOnScreen().getY() + (parent.getHeight() - parent.getFullImageHeight()) / 2);
		if (e.getXOnScreen() >= xbound && e.getXOnScreen() < xbound + parent.getFullImageWidth()
				&& e.getYOnScreen() >= ybound && e.getYOnScreen() < ybound + parent.getFullImageHeight()) {
			MatrixSelector.select(e.getXOnScreen() - xbound, e.getYOnScreen() - ybound);
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
		mMtxEditForm.checkCamTool(e);
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
