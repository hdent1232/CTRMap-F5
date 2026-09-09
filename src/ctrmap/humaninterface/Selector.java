package ctrmap.humaninterface;

import static ctrmap.CtrmapMainframe.*;
import ctrmap.formats.tilemap.Tilemap;

/**
 * CM2D cursor.
 */
public class Selector {
	public static int hilightTileX = -1;
	public static int hilightTileY = -1;
	public static int selTileX = -1;
	public static int selTileY = -1;
	
	public static boolean selecting = false;
	
	/**
	 * The tile under a point on the map image, and the inspector told about it.
	 *
	 * <p>The inspector is HANDED IN rather than read off the window. It stays
	 * inside this method rather than moving to the caller, unlike the matrix
	 * cursor's: acqCurTile below runs lock(false), show, lock(true) in an order
	 * that matters, and splitting that across the five tools that pick a tile
	 * would be five copies of a sequence nobody would keep in step.
	 */
	public static void select(int xOnImage, int yOnImage, TileInspector inspector) {
		if (mTileMapPanel.loaded) {
			selecting = true;
			hilightTileX = (int)(Math.floor(((float)xOnImage/mTileMapPanel.tilemapScaledImage.getWidth())*(double)(mTileMapPanel.width)));
            hilightTileY = (int)(Math.floor(((float)yOnImage/mTileMapPanel.tilemapScaledImage.getHeight())*(double)(mTileMapPanel.height)));
			inspector.showTile(hilightTileX, hilightTileY, false);
			mTileMapPanel.firePropertyChange(TileMapPanel.PROP_REPAINT, false, true);
		}
	}
	
	public static boolean getSelectorCM2DRenderOptimizationFlag(){
		boolean out = selecting;
		selecting = false;
		return out;
	}
	
	public static void acqCurTile(TileInspector inspector) {
		Tilemap reg = mTileMapPanel.getRegionForTile(hilightTileX, hilightTileY);
		if (reg == null){
			return;
		}
		if (hilightTileX != -1) {
			if (hilightTileX == selTileX && hilightTileY == selTileY) {
				inspector.lockTile(false);
				selTileX = -1;
				selTileY = -1;
				return;
			}
			selTileX = hilightTileX;
			selTileY = hilightTileY;
			inspector.lockTile(false);
			inspector.showTile(selTileX, selTileY, false);
			inspector.lockTile(true);
		}
		else {
			selTileX = -1;
			selTileY = -1;
			inspector.lockTile(false);
		}
		mTileMapPanel.firePropertyChange(TileMapPanel.PROP_REPAINT, false, true);
	}
	
	public static void deselect() {
		hilightTileX = -1;
		hilightTileY = -1;
		mTileMapPanel.firePropertyChange(TileMapPanel.PROP_REPAINT, false, true);
	}
	public static void unfocus() {
		selTileX = -1;
		selTileY = -1;
		mTileMapPanel.firePropertyChange(TileMapPanel.PROP_REPAINT, false, true);
	}
}
