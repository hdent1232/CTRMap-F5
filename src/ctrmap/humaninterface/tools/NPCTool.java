package ctrmap.humaninterface.tools;

import ctrmap.humaninterface.NPCEditForm;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.humaninterface.Selector;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.MouseEvent;

public class NPCTool extends AbstractTool {

	/** The NPC editor whose entry it moves. */
	private final NPCEditForm form;

	public NPCTool(ToolHost host, NPCEditForm form) {
		super(host);
		this.form = form;
	}
	
	private boolean isDownOnNPC = false;
	
	@Override
	public void onToolInit() {
		host.showToolUi(form);
		form.refresh();
	}
	
	@Override
	public void onToolShutdown() {}
	
	@Override
	public void fireCancel() {
	}
	
	@Override
	public void drawOverlay(Graphics g, int imgstartx, int imgstarty, double globimgdim) {
		int gidround = (int) Math.round(globimgdim);
		if (form.loaded) {
			for (int i = 0; i < form.e.NPCCount; i++) {
				ZoneEntities.NPC npc = form.e.npcs.get(i);
				g.setColor(Color.WHITE);
				g.fillRect(imgstartx + (int) (npc.xTile * globimgdim), imgstarty + (int) (npc.yTile * globimgdim), gidround, gidround);
				g.setColor((form.npcIndex == i) ? Color.RED : Color.BLACK); //the form selects by position
				g.drawRect(imgstartx + (int) (npc.xTile * globimgdim), imgstarty + (int) (npc.yTile * globimgdim), gidround, gidround);
				g.setColor(Color.BLACK);
				g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, gidround));
				g.drawString(String.valueOf(npc.uid), imgstartx + (int) (npc.xTile * globimgdim) + 1, imgstarty + (int) (npc.yTile * globimgdim) + gidround - 1);
			}
		}
	}
	
	@Override
	public boolean getSelectorEnabled() {
		return true;
	}
	
	@Override
	public void onTileClick(MouseEvent e) {
	}
	
	@Override
	public void onTileMouseDown(MouseEvent e) {
		if (form.loaded) {
			for (int i = 0; i < form.e.npcs.size(); i++) {
				ZoneEntities.NPC npc = form.e.npcs.get(i);
				if (Selector.hilightTileX == npc.xTile && Selector.hilightTileY == npc.yTile) {
					isDownOnNPC = true;
					form.setNPC(i);
					break;
				}
			}
		}
	}
	
	@Override
	public void onTileMouseUp(MouseEvent e) {
		isDownOnNPC = false;
		host.redraw();
	}
	
	@Override
	public void onTileMouseDragged(MouseEvent e) {
		if (form.npc == null || !form.loaded || !isDownOnNPC || Selector.hilightTileX == -1) {
			return;
		}
		form.npc.xTile = Selector.hilightTileX;
		form.npc.yTile = Selector.hilightTileY;
		//the panel's meshes are the ground; the NPC keeps its altitude where they have no answer, instead of NaN
		form.npc.setYFromColl(form.npc.xTile * 18f, form.npc.yTile * 18f, host.map()::getHeightAtWorldLoc);
		form.e.modified = true;
		form.refresh();
	}

	@Override
	public void updateComponents() {
		form.e.modified = true;
		form.showEntry(form.npcIndex);
	}
	
	@Override
	public boolean getNaviEnabled() {
		return true;
	}
}
