package ctrmap.humaninterface;

import ctrmap.WorkspaceSession;
import ctrmap.formats.mapmatrix.MapMatrix;
import ctrmap.formats.propdata.ADPropRegistry;
import ctrmap.formats.zone.Zone;
import ctrmap.formats.zone.ZoneFootprint;
import ctrmap.gamedef.ArchiveType;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * The Zone Loader's 3D preview - the editor's own map view, pointed at a zone
 * nobody has opened.
 *
 * <p>IT DRAWS THROUGH THE EDITOR'S LOADER, not a second copy of it. This class
 * used to read the zone header itself, pick a region out of the matrix, open the
 * GR, decode the BCH and place the geometry with its own arithmetic - a smaller,
 * younger twin of {@link TileMapPanel} - and every defect the owner reported came
 * out of the gap between the two: one region instead of the map, a region
 * belonging to a neighbouring zone, four cells of a thirteen-cell map so the
 * bridges ended in mid-air. The editor has drawn zones correctly for years.
 * There is no version of this feature worth having that does not use it.
 *
 * <p>So the preview owns a {@link TileMapPanel} and an {@link H3DRenderingPanel}
 * of its own and calls {@link TileMapPanel#loadRegions} - the same body the
 * editor's own load calls, with a null progress dialog, because looking at a
 * zone must not clear the undo history, drop the picked tile or put a modal
 * dialog up.
 *
 * <p>IT IS HANDED ITS SESSION rather than fetching the open one:
 * WorkspaceSessionTest holds a falling ceiling on how many production classes
 * reach the open-workspace statics.
 */
public final class ZonePreviewPane extends JPanel {

	/** Wide enough for a town to be recognisable; the tab has the room. */
	static final int PREFERRED_WIDTH = 380;

	private final JPanel middle = new JPanel(new BorderLayout());
	private final JLabel note = new JLabel(" ");
	/** The newest request wins: reads run off the EDT and may finish out of order. */
	private final int[] seq = {0};

	private WorkspaceSession ws;
	/** The editor's map view, one of our own - built on first use, see below. */
	private TileMapPanel map;
	private H3DRenderingPanel view;
	/** The scene the map view draws into, kept so the camera can be re-aimed after a load. */
	private Scene3D scene;
	/** Which cells the drawn zone occupies, or null when its own content does not say. */
	private ZoneFootprint framed;
	private int drawn = -1;
	/** The window's loaded zone, handed in - see the constructor. */
	private final ctrmap.LoadedZone owner;
	/** The window's tool selection, handed in with the owner. */
	private final ctrmap.humaninterface.tools.ToolSelection tools;

	/**
	 * @param owner the window's loaded zone, HANDED IN. The map view this pane hosts
	 * takes one, and making a second would be a second answer to "which zone is open" -
	 * two halves of the editor believing different things, which is the failure that
	 * owner exists to make impossible. LoadedZoneTest holds the window to being the only
	 * class that makes one, and it caught this.
	 */
	public ZonePreviewPane(ctrmap.LoadedZone owner,
			ctrmap.humaninterface.tools.ToolSelection tools) {
		super(new BorderLayout(0, 4));
		if (owner == null) {
			throw new IllegalArgumentException("ZonePreviewPane must be handed the LoadedZone");
		}
		if (tools == null) {
			throw new IllegalArgumentException("ZonePreviewPane must be handed the ToolSelection");
		}
		this.owner = owner;
		this.tools = tools;
		setBorder(BorderFactory.createTitledBorder("Zone preview"));
		middle.setPreferredSize(new Dimension(PREFERRED_WIDTH, 300));
		add(middle, BorderLayout.CENTER);
		note.setText("  Pick a zone above to see its map here. Nothing is opened until you load it.");
		add(note, BorderLayout.SOUTH);
		setPreferredSize(new Dimension(PREFERRED_WIDTH, 0));
	}

	/** The game to read maps out of, handed in rather than fetched. */
	public void use(WorkspaceSession session) {
		this.ws = session;
		if (session == null) {
			drawn = -1;
			note.setText("  No game is open yet.");
		}
	}

	/** True once a game has been handed in, so a caller can say why it is empty. */
	public boolean hasGame() {
		return ws != null;
	}

	/**
	 * Stops drawing. The canvas keeps a 60fps clock on a non-daemon thread, so a pane
	 * that is finished with holds the whole process open otherwise.
	 */
	public void stop() {
		if (view != null) {
			view.stop();
		}
	}

	/** The zone currently drawn, or -1 - a suite cannot look at a picture. */
	public int drawnZone() {
		return drawn;
	}

	/** Whether the 3D view has been built yet; false until a zone is previewed. */
	public boolean viewBuilt() {
		return view != null;
	}

	/**
	 * Draws the zone at {@code zoneIndex}: its whole map, exactly as opening it
	 * would draw it.
	 *
	 * <p>Safe to call as fast as a list can be arrowed through - every request
	 * takes a sequence number and only the newest one is allowed to paint.
	 */
	public void preview(final int zoneIndex) {
		if (ws == null) {
			drawn = -1;
			note.setText("  No game is open, so there is nothing to preview.");
			return;
		}
		if (zoneIndex < 0) {
			drawn = -1;
			note.setText("  No zone selected.");
			return;
		}
		note.setText("  Reading zone " + zoneIndex + "...");
		final int mine = ++seq[0];
		final WorkspaceSession reading = ws;
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				Zone zone = null;
				try {
					zone = open(reading, zoneIndex);
				} catch (Exception ex) {
					say(mine, "Zone " + zoneIndex + " could not be read: " + ctrmap.Ui.reason(ex), -1);
					return;
				}
				if (zone == null) {
					say(mine, "Zone " + zoneIndex + " is not in the workspace.", -1);
					return;
				}
				if (mine != seq[0]) {
					free(zone);
					return;                        //a newer zone is already being asked for
				}
				try {
					draw(zone, reading);
				} catch (Exception ex) {
					free(zone);
					say(mine, "Zone " + zoneIndex + " could not be drawn: " + ctrmap.Ui.reason(ex), -1);
					return;
				}
				//SAY WHICH PART, not just which map. Three zones reading "area 19, map 12"
				//over three identical pictures is what was reported; the map id alone cannot
				//tell them apart, and neither could the picture. The cells are the part that
				//differs, so they go in the words as well as in the camera - a caption a user
				//can compare between two rows without trusting their eyes on a small canvas.
				//...and when the footprint was discarded, say the WHOLE MAP rather than
				//nothing, so a black or unhelpful preview is explained instead of silent.
				String where = framed == null ? " - whole map" : " - " + framed.describeCells();
				say(mine, "Zone " + zoneIndex + " - area " + zone.header.areadataID
						+ ", map " + zone.header.mapmatrixID + where, zoneIndex);
			}
		}, "zone-preview");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Opens a zone's header and its archives, the way the window does it.
	 *
	 * <p>Visible to the suites deliberately. Everything else here needs a graphics
	 * context to check, and the one thing that has actually gone wrong twice - reading
	 * a DIFFERENT zone from the one the user is looking at - is decided here, in two
	 * lines with no geometry in them.
	 */
	public static Zone open(WorkspaceSession ws, int zoneIndex) throws Exception {
		File f = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, zoneIndex);
		if (f == null || !f.isFile()) {
			return null;
		}
		Zone z = new Zone(new ctrmap.formats.containers.ZO(f, ws), ws.game());
		z.header.fetchArchives(ws);
		return z;
	}

	/** Hands the zone to the editor's own loader, on our own panel. */
	private void draw(Zone zone, WorkspaceSession reading) {
		MapMatrix mm = new MapMatrix(zone.header.mapmatrix, reading);
		mapView().loadRegions(mm,
				new ADPropRegistry(zone.header.areadata, zone.header.propTextures, reading),
				zone.header.worldTextures, zone.header.propTextures, null);
		//AND THEN AIM AT THE ZONE, not at the map. loadRegions ends by framing the whole
		//matrix, which is right when the map is the subject and wrong here: several zones
		//share one matrix, and the preview drew Route 132, 133 and 134 as the same image.
		//Reported with three screenshots. Framed AFTER the load because the load is what
		//pointed the camera at everything.
		framed = ZoneFootprint.of(zone.header, zone.entities);
		//...AND ONLY IF IT CAN BE ABOUT THIS MAP. Reported twice: this used to CLAMP the
		//footprint into the matrix, which turned "these numbers are not about this map" into
		//a confident corner. Zone 0 measures twelve cells across and its map is one cell, so
		//it framed cell (0,0) and the preview went black. A box that does not fit is not
		//narrowed, it is discarded, and the whole map is framed exactly as it was before this
		//feature existed - loadRegions has already done that, so there is nothing to undo.
		if (framed != null && !framed.fitsIn(mm.width, mm.height)) {
			framed = null;
		}
		if (framed != null) {
			scene.frameCells(framed.minCellX(), framed.minCellY(),
					framed.maxCellX(), framed.maxCellY());
		}
	}

	private void say(final int mine, final String what, final int zone) {
		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				if (mine != seq[0]) {
					return;
				}
				drawn = zone;
				note.setText("  " + what);
				if (view != null) {
					view.repaint();
				}
			}
		});
	}

	private static void free(Zone zone) {
		if (zone != null) {
			zone.header.freeArchives();
		}
	}

	/**
	 * The editor's map view and the 3D panel that draws it, built the first time
	 * there is something to show.
	 *
	 * <p>LATE, because a GLJPanel asks the graphics driver for a context when it
	 * is constructed: doing that while the window is being assembled cost this
	 * tree a suite that did not finish in five minutes.
	 */
	private synchronized TileMapPanel mapView() {
		if (map == null) {
			//THE WINDOW'S tool selection, handed in: a second one is a second answer to
			//"what is the user holding", and this pane only reads it.
			ctrmap.humaninterface.tools.ToolSelection tools = this.tools;
			final java.util.List<CM3DRenderable> drawnBy = new java.util.ArrayList<>();
			final H3DRenderingPanel panel = new H3DRenderingPanel(drawnBy, tools);
			PanelScene3D built3D = new PanelScene3D(() -> panel, drawnBy);
			scene = built3D;
			TileMapPanel built = new TileMapPanel(owner, tools,
					built3D, new javax.swing.JScrollPane(),
					new CollEditPanel(tools), null);
			drawnBy.add(built);
			map = built;
			view = panel;
			SwingUtilities.invokeLater(new Runnable() {
				@Override
				public void run() {
					middle.add(panel, BorderLayout.CENTER);
					middle.revalidate();
				}
			});
		}
		return map;
	}
}
