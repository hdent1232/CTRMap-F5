package ctrmap.humaninterface.tools;

import java.awt.Graphics;
import java.awt.event.MouseEvent;

/**
 * Abstract class of CM2D editor plug-in tools.
 *
 * <p>A tool is handed the editor it works inside ({@link ToolHost}) and, by
 * its subclass, the form it drives. It reaches for nothing: every tool here
 * used to open with {@code import static ctrmap.CtrmapMainframe.*} and read
 * the main window's statics, which is why none of them could be built without
 * the whole window standing.
 */
public abstract class AbstractTool {

	/** The editor this tool works inside. Handed in, never fetched. */
	protected final ToolHost host;

	private boolean started;

	protected AbstractTool(ToolHost host) {
		if (host == null) {
			throw new IllegalArgumentException("a tool must be handed the editor it works in");
		}
		this.host = host;
	}

	/**
	 * Takes this tool in hand: lets go of the 3D navigator, so a gizmo bound
	 * by the NPC editor is not left standing over a prop, and lets the tool
	 * set itself up.
	 *
	 * <p>Called by {@link ToolSelection} once, after the tool is built, and
	 * NOT from the constructor above. It used to be: the constructor called
	 * {@code onToolInit()} directly. That works only while a tool has nothing
	 * of its own, because a subclass's fields are assigned after {@code super}
	 * returns - so the moment a tool was handed its form, an {@code onToolInit}
	 * run from the constructor would have read null.
	 */
	public final void start() {
		if (started) {
			return;
		}
		started = true;
		host.releaseNavi();
		onToolInit();
	}

	public abstract void onToolInit();

	public abstract void onToolShutdown();

	public abstract void fireCancel();

	public abstract void drawOverlay(Graphics g, int imgstartx, int imgstarty, double globimgdim);

	public abstract boolean getSelectorEnabled();

	public abstract boolean getNaviEnabled();

	public abstract void onTileClick(MouseEvent e);

	public abstract void onTileMouseDown(MouseEvent e);

	public abstract void onTileMouseUp(MouseEvent e);

	public abstract void onTileMouseDragged(MouseEvent e);

	public abstract void updateComponents();
	public boolean CM2DNoUpdate = false;
}
