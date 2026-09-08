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
import ctrmap.humaninterface.tools.ToolSelection;
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
	
	/** The tool being driven: this class is the one that changes it. */
	private final ToolSelection tools;

	public TilemapPanelInputManager(TileMapPanel parent, ToolSelection tools){
		super();
		this.tools = tools;
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
		tools.current().onTileMouseDragged(e);
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
			tools.current().fireCancel();
		}
		tools.current().onTileClick(e);
	}
	
	@Override
	public void mouseEntered(MouseEvent arg0) {
		
	}
	
	@Override
	public void mouseExited(MouseEvent arg0) {
		
	}
	
	@Override
	public void mousePressed(MouseEvent e) {
		tools.current().onTileMouseDown(e);
	}
	
	@Override
	public void mouseReleased(MouseEvent e) {
		tools.current().onTileMouseUp(e);
	}
	
	@Override
	public void actionPerformed(ActionEvent e) {
		//the outgoing tool is put down BEFORE the incoming one is built, which
		//is why these are suppliers: the constructor of a tool puts its own
		//form in the editor's pane, and a shutdown running after that would
		//take it straight back out. ToolSelection keeps that order.
		boolean switchCam = false;
		switch (e.getActionCommand()) {
			case ("edit"):
				tools.switchTo(EditTool::new);
				break;
			case ("set"):
				tools.switchTo(SetTool::new);
				break;
			case ("fill"):
				tools.switchTo(FillTool::new);
				break;
			case ("cam"):
				tools.switchTo(CameraTool::new);
				break;
			case ("prop"):
				tools.switchTo(PropTool::new);
				break;
			case ("npc"):
				tools.switchTo(NPCTool::new);
				break;
			case ("warp"):
				tools.switchTo(WarpTool::new);
				break;
			case ("trigger"):
				tools.switchTo(TriggerTool::new);
				break;
			case ("paint"):
				tools.switchTo(ctrmap.humaninterface.tools.PaintTool::new);
				break;
			case ("geo"):
				tools.switchTo(GeoTool::new);
				break;
		}
	}
}
