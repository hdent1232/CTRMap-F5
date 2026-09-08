package ctrmap.humaninterface.tools;

import ctrmap.humaninterface.CameraEditForm;
import ctrmap.humaninterface.TriggerEditForm;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.humaninterface.Selector;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.MouseEvent;
import java.util.ArrayList;

public class TriggerTool extends AbstractTool {

	/** The trigger editor whose trigger it moves. */
	private final TriggerEditForm form;

	/** Handed alongside the form: the camera editor this tool keeps in step. */
	private final CameraEditForm camera;

	public TriggerTool(ToolHost host, TriggerEditForm form, CameraEditForm camera) {
		super(host);
		this.form = form;
		this.camera = camera;
	}

	private boolean isDownOnTrigger = false;

	@Override
	public void onToolInit() {
		host.showToolUi(form);
	}

	@Override
	public void onToolShutdown() {
	}

	@Override
	public void fireCancel() {
	}

	@Override
	public void drawOverlay(Graphics g, int imgstartx, int imgstarty, double globimgdim) {
		if (!form.loaded || form.e == null) {
			return;
		}
		drawTriggerList(g, form.e.triggers1, Color.WHITE, imgstartx, imgstarty, globimgdim);
		drawTriggerList(g, form.e.triggers2, Color.YELLOW, imgstartx, imgstarty, globimgdim);
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
			g.setColor((form.trigger == t) ? Color.RED : Color.BLACK);
			g.drawRect(xdraw, ydraw, w, h);
			g.setColor(Color.BLACK);
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, gidround));
			g.drawString(String.valueOf(i), xdraw + 1, ydraw + gidround - 1);
		}
	}

	private boolean hitTestList(MouseEvent e, ArrayList<ZoneEntities.Trigger> list, int listType, boolean isMouseDown) {
		int imgstartx = (host.map().getWidth() - host.map().tilemapScaledImage.getWidth()) / 2;
		int imgstarty = (host.map().getHeight() - host.map().tilemapScaledImage.getHeight()) / 2;
		for (int i = 0; i < list.size(); i++) {
			ZoneEntities.Trigger t = list.get(i);
			double xBase = t.x * 18f * 400d / 720d * host.map().tilemapScale + imgstartx;
			double yBase = t.y * 18f * 400d / 720d * host.map().tilemapScale + imgstarty;
			double width = (t.w * 18f) * 400d / 720d * host.map().tilemapScale;
			double height = (t.h * 18f) * 400d / 720d * host.map().tilemapScale;
			if (e.getX() > xBase && e.getX() < xBase + width && e.getY() > yBase && e.getY() < yBase + height) {
				form.selectTrigger(listType, i);
				if (isMouseDown) {
					isDownOnTrigger = true;
				}
				host.redraw();
				return true;
			}
		}
		return false;
	}

	@Override
	public void onTileClick(MouseEvent e) {
		if (form.loaded) {
			if (!hitTestList(e, form.e.triggers1, 0, false)) {
				hitTestList(e, form.e.triggers2, 1, false);
			}
		}
	}

	@Override
	public void onTileMouseDown(MouseEvent e) {
		if (form.loaded) {
			if (!hitTestList(e, form.e.triggers1, 0, true)) {
				hitTestList(e, form.e.triggers2, 1, true);
			}
		}
	}

	@Override
	public void onTileMouseUp(MouseEvent e) {
		isDownOnTrigger = false;
		host.redraw();
	}

	@Override
	public void onTileMouseDragged(MouseEvent e) {
		if (form.trigger == null || !form.loaded || !isDownOnTrigger || Selector.hilightTileX == -1) {
			return;
		}
		form.trigger.x = Selector.hilightTileX;
		form.trigger.y = Selector.hilightTileY;
		form.e.modified = true;
		form.refresh();
	}

	@Override
	public boolean getSelectorEnabled() {
		return false;
	}

	@Override
	public void updateComponents() {
		camera.showCamera(camera.camIndex, false);
	}

	@Override
	public boolean getNaviEnabled() {
		return false;
	}
}
