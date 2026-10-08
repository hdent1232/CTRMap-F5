package ctrmap.humaninterface.tools;

import ctrmap.formats.zone.WorldGrid;
import ctrmap.humaninterface.WarpEditForm;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.humaninterface.Selector;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.MouseEvent;

public class WarpTool extends AbstractTool {

	/** The warp editor whose warp it moves. */
	private final WarpEditForm form;

	public WarpTool(ToolHost host, WarpEditForm form) {
		super(host);
		this.form = form;
	}

	private boolean isDownOnWarp = false;

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
		paintWarps(g, imgstartx, imgstarty, globimgdim);
	}

	/**
	 * Draws every warp of the loaded zone as a numbered box, the selected one
	 * framed red. Nothing while the warp form has no zone: the tool can be
	 * active before a zone is open, and drawing then threw on the event
	 * thread and left the map view blank.
	 *
	 * <p>It was a static, "because the tool itself cannot be constructed
	 * without the whole window and this is the part a test needs to see".
	 * A tool is handed its form and the editor it works in now, so a suite
	 * builds one and calls this on it, which is what the static was standing
	 * in for.
	 */
	public void paintWarps(Graphics g, int imgstartx, int imgstarty, double globimgdim) {
		if (!form.loaded) {
			return;
		}
		int gidround = (int) Math.round(globimgdim);
		for (int i = 0; i < form.e.warps.size(); i++) {
			ZoneEntities.Warp warp = form.e.warps.get(i);
			int xdraw = imgstartx + (int) Math.round(globimgdim * WorldGrid.tileOfCentre(warp.x));
			int ydraw = imgstarty + (int) Math.round(globimgdim * WorldGrid.tileOfCentre(warp.y));
			int w = (int) Math.round(globimgdim * warp.w);
			int h = (int) Math.round(globimgdim * warp.h);
			g.setColor(Color.WHITE);
			g.fillRect(xdraw, ydraw, w, h);
			g.setColor((form.warp == warp) ? Color.RED : Color.BLACK);
			g.drawRect(xdraw, ydraw, w, h);
			g.setColor(Color.BLACK);
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, gidround));
			g.drawString(String.valueOf(i), xdraw + 1, ydraw + gidround - 1);
		}
	}

	@Override
	public void onTileClick(MouseEvent e
	) {
		if (form.loaded) {
			for (int i = 0; i < form.e.warps.size(); i++) {
				ZoneEntities.Warp warp = form.e.warps.get(i);
				java.awt.Point origin = host.map().imageOrigin();
				int imgstartx = origin.x;
				int imgstarty = origin.y;
				double xBase = host.map().toScreen(warp.x - 9f) + imgstartx;
				double yBase = host.map().toScreen(warp.y - 9f) + imgstarty;
				double width = host.map().toScreen(warp.w * 18f);
				double height = host.map().toScreen(warp.h * 18f);
				if (e.getX() > xBase && e.getX() < xBase + width && e.getY() > yBase && e.getY() < yBase + height) {
					form.showEntry(i);
					host.redraw();
					break;
				}
			}
		}
	}

	@Override
	public void onTileMouseDown(MouseEvent e
	) {
		if (form.loaded) {
			for (int i = 0; i < form.e.warps.size(); i++) {
				ZoneEntities.Warp warp = form.e.warps.get(i);
				java.awt.Point origin = host.map().imageOrigin();
				int imgstartx = origin.x;
				int imgstarty = origin.y;
				double xBase = host.map().toScreen(warp.x - 9f) + imgstartx;
				double yBase = host.map().toScreen(warp.y - 9f) + imgstarty;
				double width = host.map().toScreen(warp.w * 18f);
				double height = host.map().toScreen(warp.h * 18f);
				if (e.getX() > xBase && e.getX() < xBase + width && e.getY() > yBase && e.getY() < yBase + height) {
					form.setWarp(i);
					isDownOnWarp = true;
					host.redraw();
					break;
				}
			}
		}
	}

	@Override
	public void onTileMouseUp(MouseEvent e
	) {
		isDownOnWarp = false;
		host.redraw();
	}

	@Override
	public void onTileMouseDragged(MouseEvent e
	) {
		if (form.warp == null || !form.loaded || !isDownOnWarp || Selector.hilightTileX == -1) {
			return;
		}
		form.warp.x = WorldGrid.tileCentre(Selector.hilightTileX);
		form.warp.y = WorldGrid.tileCentre(Selector.hilightTileY);
		form.e.modified = true;
		form.refresh();
	}

	@Override
	public boolean getSelectorEnabled() {
		return false;
	}

	@Override
	public boolean getNaviEnabled() {
		return false;
	}
}
