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

	/**
	 * The open zone changed under this tool, which is still held.
	 *
	 * <p>Not abstract, because for nine of the ten tools there is nothing to do:
	 * they read the map view when they are used, so the next click already sees
	 * the new zone. The painter is the exception - it holds a DOCUMENT seeded
	 * from the zone that was open when it started, and left alone it keeps
	 * showing, and guarding against, a zone the user has navigated away from.
	 *
	 * <p>WHY THIS IS THE TOOL'S BUSINESS AND NOT THE ZONE TAB'S. The Zone tab
	 * used to ask {@code tools.holding(PaintTool.class)} and then reach into the
	 * main window for the painter's form. That is the Zone tab knowing which
	 * tools exist and which of them care - so adding an eleventh tool that also
	 * needs re-seeding would have meant editing the zone switch. Now the zone
	 * switch says what happened and each tool decides what that means for it.
	 */
	public void onZoneChanged() {
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
