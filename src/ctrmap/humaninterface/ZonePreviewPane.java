package ctrmap.humaninterface;

import ctrmap.WorkspaceSession;
import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * The Zone Loader's own 3D preview, living in the Zone Loader tab.
 *
 * <p>WHY IT IS A PANEL AND NOT A WINDOW. The owner's standing rule for this
 * project is that a feature lives in the part of the UI it belongs to - it is
 * never dumped in a menu, and it is never a separate window. This preview was
 * built twice against that rule: first as a "Browse zones" dialog behind a
 * button on another bar, then as a floating bubble beside the dropdown's popup.
 * The owner opened the Zone Loader both times and reported, correctly, "still
 * zero zone preview": a preview that is somewhere else is not a preview of the
 * thing you are looking at. The Zone Loader tab has an empty right-hand half.
 * That is where it goes.
 *
 * <p>WHAT IT SHOWS. Whichever zone the dropdown is pointing at - the row being
 * arrowed through while the list is open, and the loaded zone otherwise. It
 * never opens anything: {@link ZonePreview} reads the map out of the workspace
 * and hands back bytes, so looking costs nothing and loading stays the explicit
 * act it was.
 *
 * <p>WHY THE 3D VIEW IS BUILT LATE. {@code MapPreview3D} is a GLJPanel, and
 * constructing one asks the graphics driver for a context. Doing that while the
 * window is being assembled cost the whole application its startup on this
 * machine - measured: a suite that merely built this tab did not finish in five
 * minutes. So the canvas is made the first time a zone is actually previewed.
 * Until then this is a label, which is also what it should look like.
 *
 * <p>IT ALWAYS SAYS SOMETHING. A zone whose map cannot be read leaves the view
 * cleared and a sentence underneath saying which part was missing. A blank pane
 * with no words reads as a broken editor, and a pane still showing the PREVIOUS
 * zone is worse than blank, because it is wrong rather than absent.
 */
public final class ZonePreviewPane extends JPanel {

	/** Wide enough for a town to be recognisable; the tab has the room. */
	static final int PREFERRED_WIDTH = 380;

	private final JPanel middle = new JPanel(new BorderLayout());
	private final JLabel note = new JLabel(" ");
	/** Built on first use - see WHY THE 3D VIEW IS BUILT LATE. */
	private MapPreview3D view;
	/** The newest request wins: reads run off the EDT and may finish out of order. */
	private final int[] seq = {0};
	private WorkspaceSession ws;
	private ZonePreview.Shot last;

	public ZonePreviewPane() {
		super(new BorderLayout(0, 4));
		setBorder(BorderFactory.createTitledBorder("Zone preview"));
		middle.setPreferredSize(new Dimension(PREFERRED_WIDTH, 320));
		add(middle, BorderLayout.CENTER);
		note.setText("  Pick a zone above to see its map here. Nothing is opened until you select it.");
		add(note, BorderLayout.SOUTH);
		setPreferredSize(new Dimension(PREFERRED_WIDTH, 0));
	}

	/**
	 * The game to read maps out of, handed in rather than fetched.
	 *
	 * <p>See {@link ZonePreview#of} - WorkspaceSessionTest holds a falling ceiling
	 * on how many production classes reach the open-workspace statics, and a new
	 * one that reached them would raise it.
	 */
	public void use(WorkspaceSession session) {
		this.ws = session;
		if (session == null) {
			clear("  No game is open yet.");
		}
	}

	/** True once a game has been handed in, so a caller can say why it is empty. */
	public boolean hasGame() {
		return ws != null;
	}

	/** The last shot drawn, for tests - a suite cannot look at a picture. */
	public ZonePreview.Shot lastShot() {
		return last;
	}

	/** Whether the 3D canvas has been built yet; false until a zone is previewed. */
	public boolean viewBuilt() {
		return view != null;
	}

	/**
	 * Draws the zone at {@code zoneIndex}, reading it off the event thread.
	 *
	 * <p>Safe to call as fast as a list can be arrowed through: every request
	 * takes a sequence number and only the newest one is allowed to paint.
	 */
	public void preview(final int zoneIndex) {
		if (ws == null) {
			clear("  No game is open, so there is nothing to preview.");
			return;
		}
		if (zoneIndex < 0) {
			clear("  No zone selected.");
			return;
		}
		note.setText("  Reading zone " + zoneIndex + "...");
		final int mine = ++seq[0];
		final WorkspaceSession reading = ws;
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				final ZonePreview.Shot shot = ZonePreview.of(reading, zoneIndex);
				//DECODED HERE, off the event thread. A town is several regions and each one
				//is a BCH parse; doing that on the EDT froze the window for as long as it
				//took, which is the whole reason arrowing through the list felt broken.
				final java.util.List<ctrmap.formats.h3d.model.H3DModel> drawn
					= new java.util.ArrayList<>();
				for (byte[] bytes : shot.models) {
					if (mine != seq[0]) {
						return; //a newer zone is already being asked for; stop decoding this one
					}
					drawn.add(MapPreview3D.decode(bytes, shot.textures));
				}
				SwingUtilities.invokeLater(new Runnable() {
					@Override
					public void run() {
						if (mine != seq[0]) {
							return;
						}
						paint(shot, drawn);
					}
				});
			}
		}, "zone-preview");
		t.setDaemon(true);
		t.start();
	}

	/** Called on the EDT once the bytes are in and decoded. */
	private void paint(ZonePreview.Shot shot, java.util.List<ctrmap.formats.h3d.model.H3DModel> drawn) {
		last = shot;
		String said = shot.note;
		boolean any = false;
		for (ctrmap.formats.h3d.model.H3DModel m : drawn) {
			any |= m != null;
		}
		if (!any) {
			if (view != null) {
				view.setModels(new java.util.ArrayList<ctrmap.formats.h3d.model.H3DModel>(),
					new int[0], new int[0]); //cleared, never the zone before this one
			}
			if (shot.drawable()) {
				said = "Zone " + shot.zoneIndex + "'s map could not be decoded - nothing to show.";
			}
		} else {
			canvas().setModels(drawn, shot.columns(), shot.rows());
		}
		note.setText("  " + said);
	}

	/** The 3D canvas, built the first time there is something to draw in it. */
	private MapPreview3D canvas() {
		if (view == null) {
			view = new MapPreview3D();
			middle.add(view, BorderLayout.CENTER);
			middle.revalidate();
		}
		return view;
	}

	private void clear(String why) {
		last = null;
		if (view != null) {
			view.setModels(new java.util.ArrayList<ctrmap.formats.h3d.model.H3DModel>(),
				new int[0], new int[0]);
		}
		note.setText(why);
	}
}
