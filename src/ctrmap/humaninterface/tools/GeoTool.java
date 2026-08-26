package ctrmap.humaninterface.tools;

import ctrmap.Utils;
import ctrmap.humaninterface.Selector;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.event.MouseEvent;

import static ctrmap.CtrmapMainframe.*;

/**
 * The Geometry tool - drag a tile rectangle in the map view and edit the 3D
 * geometry standing on it via the side form ({@code mGeoEditForm}): move,
 * duplicate, delete, undo, save. The rectangle is the selection model; the
 * heavy lifting lives in GeoBoxOps.
 */
public class GeoTool extends AbstractTool {

	private int anchorX = -1, anchorY = -1;
	private boolean dragging = false;

	@Override
	public void onToolInit() {
		Utils.switchToolUI(mGeoEditForm);
	}

	@Override
	public void onToolShutdown() {
		mGeoEditForm.store(true);
		mGeoEditForm.clearSelection();
		Selector.unfocus();
	}

	@Override
	public void fireCancel() {
		mGeoEditForm.clearSelection();
		mTileMapPanel.repaint();
	}

	@Override
	public void onTileClick(MouseEvent e) {
		if (Selector.hilightTileX != -1) {
			mGeoEditForm.setSelection(Selector.hilightTileX, Selector.hilightTileY,
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
			mGeoEditForm.setSelection(anchorX, anchorY, Selector.hilightTileX, Selector.hilightTileY);
		}
		dragging = false;
	}

	@Override
	public void onTileMouseDragged(MouseEvent e) {
		if (dragging && anchorX != -1 && Selector.hilightTileX != -1) {
			mGeoEditForm.setSelection(anchorX, anchorY, Selector.hilightTileX, Selector.hilightTileY);
		}
	}

	@Override
	public void drawOverlay(Graphics g, int imgstartx, int imgstarty, double globimgdim) {
		if (mGeoEditForm.selTx0 < 0) {
			return;
		}
		int x = imgstartx + (int) Math.round(globimgdim * mGeoEditForm.selTx0);
		int y = imgstarty + (int) Math.round(globimgdim * mGeoEditForm.selTy0);
		int w = (int) Math.round(globimgdim * (mGeoEditForm.selTx1 - mGeoEditForm.selTx0 + 1));
		int h = (int) Math.round(globimgdim * (mGeoEditForm.selTy1 - mGeoEditForm.selTy0 + 1));
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
