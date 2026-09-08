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
				double transformFrom720Space = 400d/720d;
				g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, (int)globimgdim));
				int textWidth = g.getFontMetrics().stringWidth(prop.name);
				int x = (int)(imgstartx + prop.x * host.map().tilemapScale * transformFrom720Space - textWidth/2d);
				int y = (int)(imgstarty + prop.z * host.map().tilemapScale * transformFrom720Space);
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
				int imgstartx = (host.map().getWidth() - host.map().tilemapScaledImage.getWidth()) / 2;
				int imgstarty = (host.map().getHeight() - host.map().tilemapScaledImage.getHeight()) / 2;
				double xBase = prop.x * 400d/720d * host.map().tilemapScale + imgstartx;
				double yBase = prop.z * 400d/720d * host.map().tilemapScale + imgstarty;
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
		int imgstartx = (host.map().getWidth() - host.map().tilemapScaledImage.getWidth()) / 2;
		int imgstarty = (host.map().getHeight() - host.map().tilemapScaledImage.getHeight()) / 2;
		form.prop.x = (float)((e.getX() - imgstartx + xshift) * (720f/400f) / host.map().tilemapScale);
		form.prop.z = (float)((e.getY() - imgstarty + yshift) * (720f/400f) / host.map().tilemapScale);
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
