package ctrmap.humaninterface.tools;

import ctrmap.humaninterface.CameraEditForm;
import ctrmap.humaninterface.GeoEditForm;
import ctrmap.humaninterface.NPCEditForm;
import ctrmap.humaninterface.PaintForm;
import ctrmap.humaninterface.PropEditForm;
import ctrmap.humaninterface.TileEditForm;
import ctrmap.humaninterface.TriggerEditForm;
import ctrmap.humaninterface.WarpEditForm;
import javax.swing.JScrollPane;

/**
 * Builds the ten editing tools over the editor they drive.
 *
 * <p>Each tool needs the same host and one or two forms of its own, and the
 * place that picks a tool - the tilemap's mouse router - should not have to
 * know which. It used to know: the router's switch said
 * {@code new NPCTool()} and the tool found the NPC form in the main window's
 * statics for itself. Now the router says {@code box::npc} and this class is
 * the one place that knows an NPC tool drives the NPC form.
 *
 * <p>Every method returns a NEW tool, because that is what picking one up has
 * always meant here: a tool's setup runs as it is taken in hand and its state
 * (a drag in progress, a rectangle, a locked tile) belongs to that turn with
 * it.
 */
public final class ToolBox {

	private final ToolHost host;
	private final TileEditForm tiles;
	private final GeoEditForm geometry;
	private final NPCEditForm npcs;
	private final PropEditForm props;
	private final WarpEditForm warps;
	private final TriggerEditForm triggers;
	private final PaintForm painter;
	private final CameraEditForm cameras;
	private final JScrollPane cameraPane;

	public ToolBox(ToolHost host, TileEditForm tiles, GeoEditForm geometry, NPCEditForm npcs,
			PropEditForm props, WarpEditForm warps, TriggerEditForm triggers, PaintForm painter,
			CameraEditForm cameras, JScrollPane cameraPane) {
		if (host == null) {
			throw new IllegalArgumentException("a tool box must be handed the editor its tools work in");
		}
		this.host = host;
		this.tiles = tiles;
		this.geometry = geometry;
		this.npcs = npcs;
		this.props = props;
		this.warps = warps;
		this.triggers = triggers;
		this.painter = painter;
		this.cameras = cameras;
		this.cameraPane = cameraPane;
	}

	public AbstractTool edit() {
		return new EditTool(host, tiles);
	}

	public AbstractTool set() {
		return new SetTool(host, tiles);
	}

	public AbstractTool fill() {
		return new FillTool(host, tiles);
	}

	public AbstractTool camera() {
		return new CameraTool(host, cameras, cameraPane);
	}

	public AbstractTool prop() {
		return new PropTool(host, props);
	}

	public AbstractTool npc() {
		return new NPCTool(host, npcs);
	}

	public AbstractTool warp() {
		return new WarpTool(host, warps, cameras);
	}

	public AbstractTool trigger() {
		return new TriggerTool(host, triggers, cameras);
	}

	public AbstractTool paint() {
		return new PaintTool(host, painter);
	}

	public AbstractTool geometry() {
		return new GeoTool(host, geometry);
	}
}
