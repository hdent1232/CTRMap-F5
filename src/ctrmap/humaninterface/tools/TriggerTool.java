package ctrmap.humaninterface.tools;

import static ctrmap.CtrmapMainframe.*;
import ctrmap.Utils;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.humaninterface.Selector;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.MouseEvent;
import java.util.ArrayList;

public class TriggerTool extends AbstractTool {

	private boolean isDownOnTrigger = false;

	@Override
	public void onToolInit() {
		Utils.switchToolUI(mTriggerEditForm);
	}

	@Override
	public void onToolShutdown() {
	}

	@Override
	public void fireCancel() {
	}

	@Override
	public void drawOverlay(Graphics g, int imgstartx, int imgstarty, double globimgdim) {
		if (!mTriggerEditForm.loaded || mTriggerEditForm.e == null) {
			return;
		}
		drawTriggerList(g, mTriggerEditForm.e.triggers1, Color.WHITE, imgstartx, imgstarty, globimgdim);
		drawTriggerList(g, mTriggerEditForm.e.triggers2, Color.YELLOW, imgstartx, imgstarty, globimgdim);
	}

	private void drawTriggerList(Graphics g, ArrayList<ZoneEntities.Trigger> list, Color fill, int imgstartx, int imgstarty, double globimgdim) {
		int gidround = (int) Math.round(globimgdim);
		for (int i = 0; i < list.size(); i++) {
			ZoneEntities.Trigger t = list.get(i);
			int xdraw = imgstartx + (int) Math.round(globimgdim * t.x);
			int ydraw = imgstarty + (int) Math.round(globimgdim * t.y);
			int w = (int) Math.round(globimgdim * t.w);
			int h = (int) Math.round(globimgdim * t.h);
			g.setColor(fill);
			g.fillRect(xdraw, ydraw, w, h);
			g.setColor((mTriggerEditForm.trigger == t) ? Color.RED : Color.BLACK);
			g.drawRect(xdraw, ydraw, w, h);
			g.setColor(Color.BLACK);
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, gidround));
			g.drawString(String.valueOf(i), xdraw + 1, ydraw + gidround - 1);
		}
	}

	private boolean hitTestList(MouseEvent e, ArrayList<ZoneEntities.Trigger> list, int listType, boolean isMouseDown) {
		int imgstartx = (mTileMapPanel.getWidth() - mTileMapPanel.tilemapScaledImage.getWidth()) / 2;
		int imgstarty = (mTileMapPanel.getHeight() - mTileMapPanel.tilemapScaledImage.getHeight()) / 2;
		for (int i = 0; i < list.size(); i++) {
			ZoneEntities.Trigger t = list.get(i);
			double xBase = t.x * 18f * 400d / 720d * mTileMapPanel.tilemapScale + imgstartx;
			double yBase = t.y * 18f * 400d / 720d * mTileMapPanel.tilemapScale + imgstarty;
			double width = (t.w * 18f) * 400d / 720d * mTileMapPanel.tilemapScale;
			double height = (t.h * 18f) * 400d / 720d * mTileMapPanel.tilemapScale;
			if (e.getX() > xBase && e.getX() < xBase + width && e.getY() > yBase && e.getY() < yBase + height) {
				mTriggerEditForm.selectTrigger(listType, i);
				if (isMouseDown) {
					isDownOnTrigger = true;
				}
				frame.repaint();
				return true;
			}
		}
		return false;
	}

	@Override
	public void onTileClick(MouseEvent e) {
		if (mTriggerEditForm.loaded) {
			if (!hitTestList(e, mTriggerEditForm.e.triggers1, 0, false)) {
				hitTestList(e, mTriggerEditForm.e.triggers2, 1, false);
			}
		}
	}

	@Override
	public void onTileMouseDown(MouseEvent e) {
		if (mTriggerEditForm.loaded) {
			if (!hitTestList(e, mTriggerEditForm.e.triggers1, 0, true)) {
				hitTestList(e, mTriggerEditForm.e.triggers2, 1, true);
			}
		}
	}

	@Override
	public void onTileMouseUp(MouseEvent e) {
		isDownOnTrigger = false;
		frame.repaint();
	}

	@Override
	public void onTileMouseDragged(MouseEvent e) {
		if (mTriggerEditForm.trigger == null || !mTriggerEditForm.loaded || !isDownOnTrigger || Selector.hilightTileX == -1) {
			return;
		}
		mTriggerEditForm.trigger.x = Selector.hilightTileX;
		mTriggerEditForm.trigger.y = Selector.hilightTileY;
		mTriggerEditForm.e.modified = true;
		mTriggerEditForm.refresh();
	}

	@Override
	public boolean getSelectorEnabled() {
		return false;
	}

	@Override
	public void updateComponents() {
		mCamEditForm.showCamera(mCamEditForm.camIndex, false);
	}

	@Override
	public boolean getNaviEnabled() {
		return false;
	}
}
