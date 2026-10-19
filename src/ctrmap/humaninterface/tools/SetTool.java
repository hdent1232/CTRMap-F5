package ctrmap.humaninterface.tools;

import java.util.Arrays;

import ctrmap.humaninterface.TileEditForm;
import ctrmap.formats.tilemap.Tilemap;
import ctrmap.humaninterface.Selector;
import java.awt.Graphics;
import java.awt.event.MouseEvent;
import javax.swing.SwingUtilities;

public class SetTool extends AbstractTool {

	/** The tile inspector holding the bytes it stamps. */
	private final TileEditForm form;

	public SetTool(ToolHost host, TileEditForm form) {
		super(host);
		this.form = form;
	}
	public byte[] actTileData = new byte[4];
	@Override
	public void onToolInit() {
		host.showToolUi(form);
		form.makeTile();
		form.lockTile(true);
	}
	
	@Override
	public void onTileClick(MouseEvent e) {
		if (SwingUtilities.isRightMouseButton(e)) {
			Selector.acqCurTile(host.inspector(), host.map());
		}
		else{
			updateTile();   //one click, one gesture, one undo step: the zone's history reads it (F209)
		}
	}

	@Override
	public void onTileMouseDown(MouseEvent e) {
	}

	@Override
	public void onTileMouseUp(MouseEvent e) {
		CM2DNoUpdate = false;   //one drag, one gesture, one undo step (F209)
	}

	@Override
	public void onTileMouseDragged(MouseEvent e) {
		if (!SwingUtilities.isRightMouseButton(e)){
			CM2DNoUpdate = true;
			updateTile();
		}
	}

	private void updateTile(){
		if (Selector.hilightTileX != -1) {
			Tilemap tm = host.map().getRegionForTile(Selector.hilightTileX, Selector.hilightTileY);
			if (tm == null){
				return;
			}
			int lx = Selector.hilightTileX % 40, ly = Selector.hilightTileY % 40;
			if (!Arrays.equals(tm.getTileData(lx, ly), actTileData)) {
				tm.setTileData(lx, ly, actTileData);   //which paints the tile (census L.39)
				host.map().perfScale(host.map().tilemapScale, Selector.hilightTileX / 40, Selector.hilightTileY / 40);
			}
		}
	}

	@Override
	public void onToolShutdown() {
		form.lockTile(false);
		Selector.unfocus(host.map());
	}

	@Override
	public void fireCancel() {
		form.lockTile(false);
		Selector.unfocus(host.map());
	}

	@Override
	public void drawOverlay(Graphics g, int imgstartx, int imgstarty, double globimgdim) {}

	@Override
	public boolean getSelectorEnabled() {
		return true;
	}

	@Override
	public void updateComponents() {
	}
	
	@Override
	public boolean getNaviEnabled() {
		return false;
	}
}
