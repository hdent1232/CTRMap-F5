package ctrmap.humaninterface.tools;

import ctrmap.humaninterface.Selector;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.event.MouseEvent;

import ctrmap.humaninterface.GeoEditForm;

/**
 * The Geometry tool - drag a tile rectangle in the map view and edit the 3D
 * geometry standing on it via the side form ({@code form}): move,
 * duplicate, delete, undo, save. The rectangle is the selection model; the
 * heavy lifting lives in GeoBoxOps.
 */
public class GeoTool extends AbstractTool {

	/** The geometry form holding the selection rectangle. */
	private final GeoEditForm form;

	public GeoTool(ToolHost host, GeoEditForm form) {
		super(host);
		this.form = form;
	}

	private int anchorX = -1, anchorY = -1;
	private boolean dragging = false;

	@Override
	public void onToolInit() {
		host.showToolUi(form);
	}

	@Override
	public void onToolShutdown() {
		form.store(true);
		form.clearSelection();
		Selector.unfocus(host.map());
	}

	@Override
	public void fireCancel() {
		form.clearSelection();
		host.map().repaint();
	}

	@Override
	public void onTileClick(MouseEvent e) {
		if (Selector.hilightTileX != -1) {
			form.setSelection(Selector.hilightTileX, Selector.hilightTileY,
					Selector.hilightTileX, Selector.hilightTileY);
		}
	}

	@Override
	public void onTileMouseDown(MouseEvent e) {
		if (Selector.hilightTileX != -1) {
			anchorX = Selector.hilightTileX;
			anchorY = Selector.hilightTileY;
			dragging = true;
		}
	}

	@Override
	public void onTileMouseUp(MouseEvent e) {
		if (dragging && anchorX != -1 && Selector.hilightTileX != -1) {
			form.setSelection(anchorX, anchorY, Selector.hilightTileX, Selector.hilightTileY);
		}
		dragging = false;
	}

	@Override
	public void onTileMouseDragged(MouseEvent e) {
		if (dragging && anchorX != -1 && Selector.hilightTileX != -1) {
			form.setSelection(anchorX, anchorY, Selector.hilightTileX, Selector.hilightTileY);
		}
	}

	@Override
	public void drawOverlay(Graphics g, int imgstartx, int imgstarty, double globimgdim) {
		if (form.selTx0 < 0) {
			return;
		}
		int x = imgstartx + (int) Math.round(globimgdim * form.selTx0);
		int y = imgstarty + (int) Math.round(globimgdim * form.selTy0);
		int w = (int) Math.round(globimgdim * (form.selTx1 - form.selTx0 + 1));
		int h = (int) Math.round(globimgdim * (form.selTy1 - form.selTy0 + 1));
		g.setColor(new Color(64, 160, 255, 80));
		g.fillRect(x, y, w, h);
		g.setColor(new Color(32, 96, 224));
		g.drawRect(x, y, w, h);
	}

	@Override
	public boolean getSelectorEnabled() {
		return true;
	}

	@Override
	public boolean getNaviEnabled() {
		return false;
	}

	@Override
	public void updateComponents() {
	}
}
