package ctrmap.humaninterface.tools;

import java.awt.event.MouseEvent;
import ctrmap.humaninterface.PropEditForm;
import ctrmap.formats.propdata.GRProp;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;

public class PropTool extends AbstractTool {

	/** The prop editor whose prop it drags. */
	private final PropEditForm form;

	public PropTool(ToolHost host, PropEditForm form) {
		super(host);
		this.form = form;
	}
	
	private boolean dragging = false;
	private boolean isDownOnProp = false;
	private double xshift = 0;
	private double yshift = 0;
	
	@Override
	public void onToolInit() {
		host.showToolUi(form);
		form.saveAndRefresh();
	}

	@Override
	public void onToolShutdown() {}

	@Override
	public void fireCancel() {
	}

	@Override
	public void drawOverlay(Graphics g, int imgstartx, int imgstarty, double globimgdim){
		if (host.map().loaded && form.loaded){
			for (int i = 0; i < form.props.props.size(); i++){
				GRProp prop = form.props.props.get(i);
				g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, (int)globimgdim));
				int textWidth = g.getFontMetrics().stringWidth(prop.name);
				int x = (int)(imgstartx + host.map().toScreen(prop.x) - textWidth/2d);
				int y = (int)(imgstarty + host.map().toScreen(prop.z));
				prop.nameWidth = textWidth;
				prop.nameHeight = (int)globimgdim;
				g.setColor(Color.WHITE);
				g.fillRect(x, y, textWidth, (int)globimgdim + 2);
				g.setColor((i == form.propIndex) ? Color.RED : Color.BLACK);
				g.drawRect(x, y, textWidth, (int)globimgdim + 2);
				g.setColor(Color.BLACK);
				g.drawString(prop.name, x, y + (int)globimgdim + 1);
				if (dragging && form.propIndex == i){
					g.setColor(Color.RED);
					g.drawLine(0, y, imgstartx + host.map().tilemapScaledImage.getWidth(), y);
					g.drawLine((int)(x + textWidth/2d), 0, (int)(x + textWidth/2d), imgstarty + host.map().tilemapScaledImage.getHeight());
				}
			}
		}
	}
	
	@Override
	public void onTileClick(MouseEvent e) {
	}

	@Override
	public void onTileMouseDown(MouseEvent e) {
		if (form.loaded){
			for (int i = 0; i < form.props.props.size(); i++){
				GRProp prop = form.props.props.get(i);
				java.awt.Point origin = host.map().imageOrigin();
				int imgstartx = origin.x;
				int imgstarty = origin.y;
				double xBase = host.map().toScreen(prop.x) + imgstartx;
				double yBase = host.map().toScreen(prop.z) + imgstarty;
				if (e.getX() > xBase - prop.nameWidth/2 && e.getX() < xBase + prop.nameWidth/2 && e.getY() > yBase && e.getY() < yBase + prop.nameHeight){
					isDownOnProp = true;
					xshift = (xBase - e.getX()); //xBase is in the center
					yshift = (yBase - e.getY());
					form.setProp(i);
					break;
				}
			}
		}
	}

	@Override
	public void onTileMouseUp(MouseEvent e) {
		dragging = false;
		isDownOnProp = false;
		host.redraw();
	}

	@Override
	public void onTileMouseDragged(MouseEvent e) {
		if (form.prop == null || !form.loaded || !isDownOnProp) return;
		dragging = true;
		java.awt.Point origin = host.map().imageOrigin();
		int imgstartx = origin.x;
		int imgstarty = origin.y;
		form.prop.x = host.map().toWorld(e.getX() - imgstartx + xshift);
		form.prop.z = host.map().toWorld(e.getY() - imgstarty + yshift);
		form.props.modified = true;
		form.showProp(form.propIndex);
		host.map().renderTileMap();
	}

	@Override
	public boolean getSelectorEnabled() {
		return false;
	}

	@Override
	public void updateComponents() {
		form.props.modified = true;
		form.showProp(form.propIndex);
	}
	
	@Override
	public boolean getNaviEnabled() {
		return true;
	}
}
