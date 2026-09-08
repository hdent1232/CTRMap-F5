package ctrmap.humaninterface.tools;

import ctrmap.humaninterface.TileEditForm;
import ctrmap.humaninterface.Selector;
import java.awt.Graphics;
import java.awt.event.MouseEvent;
import javax.swing.SwingUtilities;

public class EditTool extends AbstractTool {

	/** The tile inspector it locks and unlocks. */
	private final TileEditForm form;

	public EditTool(ToolHost host, TileEditForm form) {
		super(host);
		this.form = form;
	}

	@Override
	public void onToolInit() {
		host.showToolUi(form);
		form.lockTile(false);
	}

	@Override
	public void onTileClick(MouseEvent e) {
		if (!SwingUtilities.isRightMouseButton(e)) {
			Selector.acqCurTile();
		}
	}

	@Override
	public void onTileMouseDown(MouseEvent e) {
		//this tool has no power here
	}

	@Override
	public void onTileMouseUp(MouseEvent e) {
		//and neither does it here
	}

	@Override
	public void onTileMouseDragged(MouseEvent e) {
		
	}

	@Override
	public void onToolShutdown() {
		form.lockTile(false);
		Selector.unfocus();
	}

	@Override
	public void fireCancel() {
		form.lockTile(false);
		Selector.unfocus();
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
