package ctrmap.humaninterface;

import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

import javax.swing.SwingUtilities;
import javax.swing.event.MouseInputListener;

import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;

/**
 * Listens to user input interfaces and forwards any interaction to CM3D.
 */
public class CM3DInputManager implements MouseWheelListener, MouseMotionListener, MouseInputListener, KeyListener,
		FocusListener {

	private int originMouseX;
	private int originMouseY;
	private float originScaleX;
	private float originScaleY;
	private float originRotateX;
	private float originRotateY;
	private float speed = 10f;
	private final ArrayList<Integer> keycodes = new ArrayList<>();
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
		parent.addFocusListener(this);
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
	 * <p>A TIMER ON THE EVENT THREAD, NOT A THREAD (F016). There were four threads - one per
	 * key, each swallowing the InterruptedException that existed to stop it, none of them
	 * daemons, so a key whose release never arrived walked the camera for the life of the
	 * process - and then one daemon thread, which still wrote the panel's camera fields while
	 * the mouse handlers wrote the same fields on the event thread and read a held-key list the
	 * event thread was changing, with no lock: a drag during a walk lost one or the other,
	 * and the walker had no guarantee it ever saw a key let go. The tick runs on the event
	 * thread now, where every other camera write is, and a lost focus lets go of every key, so
	 * a release that never arrives cannot keep it walking. Four copies of the loop had also
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

	/** The one walker, started when it is not already walking. */
	private void startWalking() {
		if (!walker.isRunning()) {
			walker.start();
		}
	}

	/** One tick of the walk while a movement key is held; the last key let go stops it. */
	private void tick() {
		if (walking()) {
			step();
		} else {
			walker.stop();
		}
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

	/** The walk's tick, every 10 ms on the event thread - better than being tied to the framerate. */
	private final javax.swing.Timer walker = new javax.swing.Timer(10, e -> tick());

	@Override
	public void keyReleased(KeyEvent e) {
		while (keycodes.contains(e.getKeyCode())) {
			keycodes.remove((Integer) e.getKeyCode());
		}
	}

	@Override
	public void focusGained(FocusEvent e) {
	}

	/** A key held when the panel lost focus is never seen let go: every key is let go now. */
	@Override
	public void focusLost(FocusEvent e) {
		keycodes.clear();
		walker.stop();
	}

}
