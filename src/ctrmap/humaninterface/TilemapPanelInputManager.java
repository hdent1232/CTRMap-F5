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
import ctrmap.humaninterface.tools.CameraTool;
import ctrmap.humaninterface.tools.EditTool;
import ctrmap.humaninterface.tools.FillTool;
import ctrmap.humaninterface.tools.GeoTool;
import ctrmap.humaninterface.tools.NPCTool;
import ctrmap.humaninterface.tools.PropTool;
import ctrmap.humaninterface.tools.SetTool;
import ctrmap.humaninterface.tools.TriggerTool;
import ctrmap.humaninterface.tools.WarpTool;

/**
 * CM2D input listener.
 */
public class TilemapPanelInputManager implements MouseWheelListener, MouseMotionListener, MouseInputListener, ActionListener {
	
	public TilemapPanelInputManager(TileMapPanel parent){
		super();
		parent.addMouseWheelListener(this);
		parent.addMouseMotionListener(this);
		parent.addMouseListener(this);
	}
	
	@Override
	public void mouseWheelMoved(MouseWheelEvent e) {
		mTileMapPanel.scaleImage(mTileMapPanel.tilemapScale - e.getWheelRotation() / 10f);
	}
	
	@Override
	public void mouseDragged(MouseEvent e) {
		moveSelector(e);
		CtrmapMainframe.tool.onTileMouseDragged(e);
	}
	
	@Override
	public void mouseMoved(MouseEvent e) {
		moveSelector(e);
	}
	
	private void moveSelector(MouseEvent e) {
		int xbound = (int) (mTileMapPanel.getLocationOnScreen().getX() + (mTileMapPanel.getWidth() - mTileMapPanel.tilemapScaledImage.getWidth()) / 2);
		int ybound = (int) (mTileMapPanel.getLocationOnScreen().getY() + (mTileMapPanel.getHeight() - mTileMapPanel.tilemapScaledImage.getHeight()) / 2);
		if (e.getXOnScreen() >= xbound && e.getXOnScreen() < xbound + mTileMapPanel.tilemapScaledImage.getWidth()
				&& e.getYOnScreen() >= ybound && e.getYOnScreen() < ybound + mTileMapPanel.tilemapScaledImage.getHeight()) {
			Selector.select(e.getXOnScreen() - xbound, e.getYOnScreen() - ybound);
		} else {
			if (Selector.hilightTileX != -1) {
				Selector.deselect();
			}
		}
	}
	
	@Override
	public void mouseClicked(MouseEvent e) {
		if (Selector.hilightTileX == -1) {
			CtrmapMainframe.tool.fireCancel();
		}
		CtrmapMainframe.tool.onTileClick(e);
	}
	
	@Override
	public void mouseEntered(MouseEvent arg0) {
		
	}
	
	@Override
	public void mouseExited(MouseEvent arg0) {
		
	}
	
	@Override
	public void mousePressed(MouseEvent e) {
		CtrmapMainframe.tool.onTileMouseDown(e);
	}
	
	@Override
	public void mouseReleased(MouseEvent e) {
		CtrmapMainframe.tool.onTileMouseUp(e);
	}
	
	@Override
	public void actionPerformed(ActionEvent e) {
		CtrmapMainframe.tool.onToolShutdown();
		boolean switchCam = false;
		switch (e.getActionCommand()) {
			case ("edit"):
				CtrmapMainframe.tool = new EditTool();
				break;
			case ("set"):
				CtrmapMainframe.tool = new SetTool();
				break;
			case ("fill"):
				CtrmapMainframe.tool = new FillTool();
				break;
			case ("cam"):
				CtrmapMainframe.tool = new CameraTool();
				break;
			case ("prop"):
				CtrmapMainframe.tool = new PropTool();
				break;
			case ("npc"):
				CtrmapMainframe.tool = new NPCTool();
				break;
			case ("warp"):
				CtrmapMainframe.tool = new WarpTool();
				break;
			case ("trigger"):
				CtrmapMainframe.tool = new TriggerTool();
				break;
			case ("paint"):
				CtrmapMainframe.tool = new ctrmap.humaninterface.tools.PaintTool();
				break;
			case ("geo"):
				CtrmapMainframe.tool = new GeoTool();
				break;
		}
	}
}
