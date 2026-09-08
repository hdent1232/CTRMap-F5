package ctrmap.humaninterface.tools;

import ctrmap.humaninterface.CameraEditForm;
import ctrmap.formats.cameradata.CameraData;
import ctrmap.humaninterface.Selector;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;

public class CameraTool extends AbstractTool {

	/** The camera editor it switches between cameras. */
	private final CameraEditForm form;

	/** Handed alongside the form: the scroll pane the camera editor is shown in. */
	private final javax.swing.JScrollPane pane;

	public CameraTool(ToolHost host, CameraEditForm form, javax.swing.JScrollPane pane) {
		super(host);
		this.form = form;
		this.pane = pane;
	}

	@Override
	public void onToolInit() {
		host.showToolUi(pane);
	}

	@Override
	public void onToolShutdown() {}

	@Override
	public void fireCancel() {
	}

	@Override
	public void drawOverlay(Graphics g, int imgstartx, int imgstarty, double globimgdim) {
		if (host.map().loaded) {
			ArrayList<Byte> layers = new ArrayList<>();
			for (int i = 0; i < form.f.camData.size(); i++) {
				byte layer = (byte) form.f.camData.get(i).layer;
				if (!layers.contains(layer)) {
					layers.add(layer);
				}
			}
			Collections.sort(layers);
			layers.forEach((layer) -> {
				for (int i = 0; i < form.f.camData.size(); i++) {
					CameraData cam = form.f.camData.get(i);
					if (cam.layer != layer) {
						continue;
					}
					int xdraw = imgstartx + (int) Math.round(globimgdim * cam.boundX1);
					int ydraw = imgstarty + (int) Math.round(globimgdim * cam.boundY1);
					int w = (int) Math.round(globimgdim * (cam.boundX2 + 1) - globimgdim * cam.boundX1);
					int h = (int) Math.round(globimgdim * (cam.boundY2 + 1) - globimgdim * cam.boundY1);
					g.setColor(Color.WHITE);
					g.fillRect(xdraw, ydraw, w, h);
					g.setColor(Color.BLACK);
					g.drawRect(xdraw, ydraw, w, h);
					g.setFont(new Font(Font.SERIF, Font.PLAIN, Math.min(Math.min(h - 1, 20), w - 2))); //width is used to approximately prevent the text from stretching out beyond the rectangle horizontally
					g.drawString("C" + String.valueOf(i), xdraw + 1, ydraw + Math.min(h / 2, 20));
				}
			});
			if (form.cam != null) {
				CameraData cam = form.cam;
				g.setColor(new Color(0xff0000));
				g.drawRect(imgstartx + (int) Math.round(cam.boundX1 * globimgdim), imgstarty + (int) Math.round(cam.boundY1 * globimgdim),
						(int) Math.round((cam.boundX2 - cam.boundX1 + 1) * globimgdim), (int) Math.round((cam.boundY2 - cam.boundY1 + 1) * globimgdim));
			}
		}
	}

	@Override
	public void onTileClick(MouseEvent e) {
		for (int i = 0; i < form.f.camData.size(); i++) {
			CameraData cam = form.f.camData.get(i);
			if (Selector.hilightTileX >= cam.boundX1 && Selector.hilightTileX <= cam.boundX2 && Selector.hilightTileY >= cam.boundY1 && Selector.hilightTileY <= cam.boundY2
					&& form.cam != cam) {
				form.commitAndSwitch(i);
				host.redraw();
				break;
			}
		}
	}

	@Override
	public void onTileMouseDown(MouseEvent e) {
	}

	@Override
	public void onTileMouseUp(MouseEvent e) {
	}

	@Override
	public void onTileMouseDragged(MouseEvent e) {
	}

	@Override
	public boolean getSelectorEnabled() {
		return true;
	}

	@Override
	public void updateComponents() {
		form.showCamera(form.camIndex, false);
	}

	@Override
	public boolean getNaviEnabled() {
		return false;
	}
}
