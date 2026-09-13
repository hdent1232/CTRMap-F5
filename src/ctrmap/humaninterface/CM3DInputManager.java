package ctrmap.humaninterface;

import ctrmap.CtrmapMainframe;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

import javax.swing.SwingUtilities;
import javax.swing.event.MouseInputListener;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;

/**
 * Listens to user input interfaces and forwards any interaction to CM3D.
 */
public class CM3DInputManager implements MouseWheelListener, MouseMotionListener, MouseInputListener, KeyListener {

	private int originMouseX;
	private int originMouseY;
	private float originScaleX;
	private float originScaleY;
	private float originRotateX;
	private float originRotateY;
	private float originRotateZ;
	private float speed = 10f;
	private ArrayList<Integer> keycodes = new ArrayList<>();
	private boolean navi = false;
	
	private H3DRenderingPanel m3DDebugPanel;

	/** Which tool the editor is holding, handed in: this class only asks. */
	private final ctrmap.humaninterface.tools.ToolSelection tools;

	public CM3DInputManager(H3DRenderingPanel parent, ctrmap.humaninterface.tools.ToolSelection tools){
		super();
		this.tools = tools;
		m3DDebugPanel = parent;
		parent.addMouseWheelListener(this);
		parent.addMouseMotionListener(this);
		parent.addMouseListener(this);
		parent.addKeyListener(this);
	}
	
	@Override
	public void mouseClicked(MouseEvent e) {
		m3DDebugPanel.cycleSelection(e);
	}

	@Override
	public void mouseEntered(MouseEvent arg0) {

	}

	@Override
	public void mouseExited(MouseEvent arg0) {

	}

	@Override
	public void mousePressed(MouseEvent e) {
		if (!m3DDebugPanel.hasFocus()) {
			m3DDebugPanel.requestFocus();
		}
		navi = m3DDebugPanel.checkNavi(e);
		setOrigins(e);
	}

	public void setOrigins(MouseEvent e) {
		originMouseX = e.getX();
		originMouseY = e.getY();
		originScaleX = m3DDebugPanel.translateX;
		originScaleY = m3DDebugPanel.translateY;
		originRotateX = m3DDebugPanel.rotateX;
		originRotateY = m3DDebugPanel.rotateY;
		originRotateZ = m3DDebugPanel.rotateZ;
	}

	@Override
	public void mouseReleased(MouseEvent e) {
		navi = false;
	}

	@Override
	public void mouseDragged(MouseEvent e) {
		if (SwingUtilities.isRightMouseButton(e)) {
			m3DDebugPanel.translateX = originScaleX + (e.getX() - originMouseX);
			m3DDebugPanel.translateY = originScaleY - (e.getY() - originMouseY);
		} else if (SwingUtilities.isLeftMouseButton(e)) {
			if (!tools.current().getNaviEnabled() || !navi) {
				m3DDebugPanel.rotateY = (originRotateY + (e.getX() - originMouseX) / 2f) % 360f;
				m3DDebugPanel.rotateX = Math.max(-90f, Math.min(90f, originRotateX + (e.getY() - originMouseY) / 2f)) % 360f;
			}
			else {
				m3DDebugPanel.doNavi(e, originMouseX, originMouseY);
			}
			setOrigins(e);
		}
	}

	@Override
	public void mouseMoved(MouseEvent arg0) {

	}

	@Override
	public void mouseWheelMoved(MouseWheelEvent e) {
		speed = Math.max(-e.getWheelRotation() + speed, 2f);
	}

	@Override
	public void keyTyped(KeyEvent e) {
	}

	@Override
	/**
	 * Starts the camera walking while a movement key is held.
	 *
	 * <p>ONE THREAD, A DAEMON, AND IT STOPS. There were four - one per key, each an
	 * anonymous Thread looping on "is my key still in the set", each swallowing the
	 * InterruptedException that exists to stop it, and none of them daemons. A key press
	 * whose release never arrives - a focus change, a modal dialog, the window closing -
	 * left one running for ever, writing into the 3D panel and holding it alive, and the
	 * process with it. Four copies of the same loop also meant the four directions had
	 * drifted: two of them moved the camera vertically and two did not.
	 */
	public void keyPressed(KeyEvent e) {
		if (keycodes.contains(e.getKeyCode())) {
			return;
		}
		keycodes.add(e.getKeyCode());
		switch (e.getKeyCode()) {
			case KeyEvent.VK_W:
			case KeyEvent.VK_S:
			case KeyEvent.VK_A:
			case KeyEvent.VK_D:
				startWalking();
				break;
			default:
				break;
		}
	}

	/** The one walker, or nothing when it is already walking. */
	private synchronized void startWalking() {
		if (walker != null && walker.isAlive()) {
			return;
		}
		walker = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					while (walking()) {
						step();
						Thread.sleep(10); //better than being tied to the framerate
					}
				} catch (InterruptedException stopped) {
					//ASKED TO STOP, SO STOP. Swallowing this is what let a thread whose key
					//release was lost keep walking the camera for the life of the process.
					Thread.currentThread().interrupt();
				}
			}
		}, "camera-walk");
		//A DAEMON: the window closing ends it, whatever the keyboard last said.
		walker.setDaemon(true);
		walker.start();
	}

	/** Whether any movement key is still held. */
	private boolean walking() {
		return keycodes.contains(KeyEvent.VK_W) || keycodes.contains(KeyEvent.VK_S)
			|| keycodes.contains(KeyEvent.VK_A) || keycodes.contains(KeyEvent.VK_D);
	}

	/**
	 * One tick of movement for whatever is held.
	 *
	 * <p>The pitch factor applies to forward and back only, which is what the four
	 * copies did between them: W and S climbed and dived with the camera angle, A and D
	 * strafed flat. That difference is deliberate and is now in one place where it can
	 * be read.
	 */
	private void step() {
		double yaw = Math.toRadians(m3DDebugPanel.rotateY);
		double pitch = Math.toRadians(m3DDebugPanel.rotateX);
		double flat = Math.min(1f, Math.tan(Math.toRadians(90 - Math.abs(m3DDebugPanel.rotateX))));
		if (keycodes.contains(KeyEvent.VK_W)) {
			m3DDebugPanel.translateX -= Math.sin(yaw) * flat * speed;
			m3DDebugPanel.translateZ += Math.cos(yaw) * flat * speed;
			m3DDebugPanel.translateY += Math.sin(pitch) * speed;
		}
		if (keycodes.contains(KeyEvent.VK_S)) {
			m3DDebugPanel.translateX += Math.sin(yaw) * flat * speed;
			m3DDebugPanel.translateZ -= Math.cos(yaw) * flat * speed;
			m3DDebugPanel.translateY -= Math.sin(pitch) * speed;
		}
		double side = Math.toRadians(m3DDebugPanel.rotateY - 90f);
		if (keycodes.contains(KeyEvent.VK_A)) {
			m3DDebugPanel.translateX -= Math.sin(side) * speed;
			m3DDebugPanel.translateZ += Math.cos(side) * speed;
		}
		if (keycodes.contains(KeyEvent.VK_D)) {
			m3DDebugPanel.translateX += Math.sin(side) * speed;
			m3DDebugPanel.translateZ -= Math.cos(side) * speed;
		}
	}

	/** The walking thread, or null. One at a time. */
	private Thread walker;

	@Override
	public void keyReleased(KeyEvent e) {
		while (keycodes.contains(e.getKeyCode())) {
			keycodes.remove((Integer) e.getKeyCode());
		}
	}

}
