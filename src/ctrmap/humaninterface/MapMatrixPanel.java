package ctrmap.humaninterface;

import static ctrmap.CtrmapMainframe.*;
import ctrmap.formats.mapmatrix.MapMatrix;
import java.awt.Color;
import java.awt.Graphics;
import javax.swing.JPanel;

public class MapMatrixPanel extends JPanel implements MatrixCanvas {

	public MapMatrix mm;

	public MapMatrixPanel() {
		super();
	}

	public void loadMatrix(MapMatrix mm) {
		this.mm = mm;
		//the grid just changed size, so the scroll pane has to lay out again.
		//repaint() alone never asks for a layout pass, and the viewport would go
		//on using the size it already had.
		revalidate();
		mMtxEditForm.loadMatrix(mm);
	}

	/**
	 * The size of the grid this panel draws, so the scroll pane it lives in can
	 * actually scroll.
	 *
	 * <p>WHY THIS EXISTS. This panel computed getFullImageWidth/Height and used
	 * them only to CENTRE the grid, never to size itself. A view that is not
	 * Scrollable is laid out by JViewport at the larger of its preferred size
	 * and the viewport extent, so a panel that asked for nothing was given
	 * exactly the extent: the scrollbars' condition could never be true and no
	 * scrollbar could ever appear. A matrix wider or taller than the pane was
	 * then drawn from a NEGATIVE origin - paintComponent's centring term - and
	 * clipped on all four sides, and the regions that fell outside could not be
	 * seen, selected or edited at all. The editor's own Add column reaches that
	 * in a few clicks, 100 pixels at a time. The map view next door has always
	 * sized itself for its own scroll pane (TileMapPanel.scaleImage); this is
	 * the same statement for this one.
	 *
	 * <p>Nothing changes while the grid fits: the viewport still enlarges the
	 * view to the extent, so the centring term stays positive and the grid stays
	 * centred exactly as before.
	 */
	@Override
	public java.awt.Dimension getPreferredSize() {
		if (mm == null) {
			return super.getPreferredSize();
		}
		return new java.awt.Dimension(getFullImageWidth(), getFullImageHeight());
	}

	@Override
	public void redraw() {
		repaint();
	}

	/**
	 * Where the centred grid image starts inside this panel.
	 *
	 * <p>The same int division that was written out here, in the matrix form's
	 * camera hit test and in the input router - three copies of one statement.
	 * It stays a plain division: negative when the image is wider than the
	 * panel, which is how the grid scrolls.
	 */
	@Override
	public java.awt.Point imageOrigin() {
		return new java.awt.Point((getWidth() - getFullImageWidth()) / 2,
				(getHeight() - getFullImageHeight()) / 2);
	}

	public int getFullImageWidth(){
		if (mm == null){
			return 0;
		}
		else {
			return mm.width * 100;
		}
	}
	
	public int getFullImageHeight(){
		if (mm == null){
			return 0;
		}
		else {
			return mm.height * 100;
		}
	}
	
	@Override
	public void paintComponent(Graphics g) {
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, getWidth(), getHeight());
		if (mm != null) {
			java.awt.Point origin = imageOrigin();
			int imgstartx = origin.x;
			int imgstarty = origin.y;
			for (int x = 0; x < mm.width; x++) {
				for (int y = 0; y < mm.height; y++) {
					g.setColor(Color.BLACK);
					int regionX = x * 100 + imgstartx;
					int regionY = y * 100 + imgstarty;
					g.drawRect(regionX, regionY, 100, 100);
				}
			}
			mMtxEditForm.drawToolGraphics(g, imgstartx, imgstarty);
		}
	}
}
