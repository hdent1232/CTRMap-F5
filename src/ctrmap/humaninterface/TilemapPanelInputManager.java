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
import ctrmap.humaninterface.tools.ToolBox;
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

	/** Where a tool comes from: this class picks one, it does not know how to build one. */
	private final ToolBox box;

	/** The map view this router listens to: the one it was handed, not the window's copy of it. */
	private final TileMapPanel map;

	public TilemapPanelInputManager(TileMapPanel parent, ToolSelection tools, ToolBox box){
		super();
		this.map = parent;
		this.tools = tools;
		this.box = box;
		parent.addMouseWheelListener(this);
		parent.addMouseMotionListener(this);
		parent.addMouseListener(this);
	}
	
	@Override
	public void mouseWheelMoved(MouseWheelEvent e) {
		map.scaleImage(map.tilemapScale - e.getWheelRotation() / 10f);
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
		int xbound = (int) (map.getLocationOnScreen().getX() + (map.getWidth() - map.tilemapScaledImage.getWidth()) / 2);
		int ybound = (int) (map.getLocationOnScreen().getY() + (map.getHeight() - map.tilemapScaledImage.getHeight()) / 2);
		if (e.getXOnScreen() >= xbound && e.getXOnScreen() < xbound + map.tilemapScaledImage.getWidth()
				&& e.getYOnScreen() >= ybound && e.getYOnScreen() < ybound + map.tilemapScaledImage.getHeight()) {
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
				tools.switchTo(box::edit);
				break;
			case ("set"):
				tools.switchTo(box::set);
				break;
			case ("fill"):
				tools.switchTo(box::fill);
				break;
			case ("cam"):
				tools.switchTo(box::camera);
				break;
			case ("prop"):
				tools.switchTo(box::prop);
				break;
			case ("npc"):
				tools.switchTo(box::npc);
				break;
			case ("warp"):
				tools.switchTo(box::warp);
				break;
			case ("trigger"):
				tools.switchTo(box::trigger);
				break;
			case ("paint"):
				tools.switchTo(box::paint);
				break;
			case ("geo"):
				tools.switchTo(box::geometry);
				break;
		}
	}
}
