package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.LoadedZone;
import ctrmap.formats.tilemap.Tilemap;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.humaninterface.CollEditPanel;
import ctrmap.humaninterface.CameraEditForm;
import ctrmap.humaninterface.CustomH3DPreview;
import ctrmap.humaninterface.GeoEditForm;
import ctrmap.humaninterface.MatrixEditForm;
import ctrmap.humaninterface.NPCEditForm;
import ctrmap.humaninterface.PaintForm;
import ctrmap.humaninterface.PropEditForm;
import ctrmap.humaninterface.Selector;
import ctrmap.humaninterface.TileEditForm;
import ctrmap.humaninterface.TileMapPanel;
import ctrmap.humaninterface.TriggerEditForm;
import ctrmap.humaninterface.WarpEditForm;
import ctrmap.humaninterface.WorldEditorToolbar;
import java.awt.BorderLayout;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;

/**
 * The World Editor's window, minus the window: every {@link CtrmapMainframe}
 * static the editing tools and the mouse router read, built without showing
 * anything.
 *
 * <p>WHY THIS EXISTS. Every tool in {@code ctrmap.humaninterface.tools} reaches
 * its data through {@code import static ctrmap.CtrmapMainframe.*}, and
 * {@link ctrmap.humaninterface.tools.AbstractTool}'s constructor calls
 * {@code onToolInit()}, which calls {@link CtrmapMainframe#switchToolUI}. So a
 * tool cannot even be CONSTRUCTED until nine forms, three split panes, three
 * master panels and a scroll pane exist. That is why seven of the eight classes
 * this bench serves were at zero coverage: not because they are hard to assert,
 * but because nothing could build one.
 *
 * <p>SIX OF THOSE ARE PRIVATE to the window now - the three master panels and
 * the three split panes - so the bench installs them through
 * {@link #mainframeField(String, Object)} and reads them back through
 * {@link #mainframeField(String)}. That is deliberate and it is the only way
 * left: the fields are the window's own plumbing, nothing in the application
 * reaches them from outside, and a bench that stands in FOR the window has to
 * put something in them or no tool can be built at all.
 *
 * <p>Two things here are deliberately not the real thing, and both are about
 * the tool under test rather than about the collaborator:
 * <ul>
 * <li>{@link BenchMap} answers {@code getLocationOnScreen()} with the origin.
 *     The real one throws IllegalComponentStateException unless the panel is
 *     showing on a screen, and the router's {@code moveSelector} asks it on
 *     every mouse move, so without this the router could only be tested by
 *     showing a window during the battery.</li>
 * <li>{@link BenchMap} counts {@code renderTileMap()} calls instead of running
 *     it. The real one asks {@code GraphicsEnvironment} for a screen device,
 *     which throws with no display, and what a tool must be held to is that it
 *     asked the map view to redraw, not what the redraw produced.</li>
 * </ul>
 *
 * <p>{@link #frameAvailable()} is the one thing this bench cannot fake. Nine
 * lines across these classes call {@code frame.repaint()} or
 * {@code frame.revalidate()} on the mainframe's {@link JFrame}, and a JFrame
 * cannot be constructed at all with no display. Suites gate those checks on it
 * and print a skip. The battery runner passes no headless flag, so they run
 * there; a mutation sweep is headless and they do not.
 */
final class EditorBench {

	/** The zone owner every panel and form built here shares, as the window's would. */
	static final LoadedZone LOADED = new LoadedZone();

	/** The redraw the forms here are handed: what frame.repaint() was, but readable. */
	static final Redraws REDRAW = new Redraws();
	/** The tool this suite holds: its own, so another suite may hold another. */
	static final ctrmap.humaninterface.tools.ToolSelection TOOLS = new ctrmap.humaninterface.tools.ToolSelection();


	static BenchMap map;
	static BenchPaintForm paint;
	static BenchPropForm prop;
	static BenchCamForm cam;
	static TileEditForm tiles;
	static WarpEditForm warps;
	static TriggerEditForm triggers;
	static GeoEditForm geo;
	static Tilemap region;
	/** The real tool row, so the "Current tool" label is the one the editor shows. */
	static WorldEditorToolbar toolbar;

	/**
	 * Where a toolbar button press is sent, if anywhere.
	 *
	 * <p>The label used to be a static of the window and the mouse router set
	 * it itself; both halves now live on {@link WorldEditorToolbar}, which
	 * takes the switch as a constructor argument and calls it AFTER setting
	 * the label. So a suite that wants to prove the label follows the tool
	 * puts its own router here and presses the button, which is the path the
	 * user takes. Null - the default - makes a press set the label and
	 * nothing else.
	 */
	static ActionListener toolSwitch;

	private static boolean installed = false;
	private static boolean frameOk = false;

	private EditorBench() {
	}

	/**
	 * Builds the editor's statics. Call once per JVM: the fields are static on
	 * CtrmapMainframe, so a second install would leave tools built against the
	 * first set pointing at forms nothing else can see.
	 */
	static void install() {
		if (installed) {
			return;
		}
		installed = true;

		//the tools all guard on m3DDebugPanel being null (AbstractTool's own
		//constructor does), and a GLJPanel needs JOGL natives the battery's
		//classpath does not carry
		CtrmapMainframe.m3DDebugPanel = null;

		CtrmapMainframe.mTileEditForm = tiles = new TileEditForm(TOOLS);
		CtrmapMainframe.mPaintForm = paint = new BenchPaintForm();
		CtrmapMainframe.mCamEditForm = cam = new BenchCamForm();
		CtrmapMainframe.mCamScrollPane = new JScrollPane(CtrmapMainframe.mCamEditForm);
		CtrmapMainframe.mPropEditForm = prop = new BenchPropForm();
		CtrmapMainframe.mNPCEditForm = new NPCEditForm(LOADED, TOOLS, REDRAW);
		CtrmapMainframe.mWarpEditForm = warps = new WarpEditForm(LOADED, REDRAW);
		CtrmapMainframe.mTriggerEditForm = triggers = new TriggerEditForm(LOADED, REDRAW);
		CtrmapMainframe.mGeoEditForm = geo = new GeoEditForm(LOADED);
		CtrmapMainframe.mCollEditPanel = new CollEditPanel(TOOLS);
		CtrmapMainframe.mMtxEditForm = new MatrixEditForm(LOADED);
		//the "Current tool" label is this row's, not the window's, and the row
		//is what TileEditForm asks for the Set tool
		CtrmapMainframe.worldToolbar = toolbar = new WorldEditorToolbar(
				e -> {
					if (toolSwitch != null) {
						toolSwitch.actionPerformed(e);
					}
				},
				() -> {
				});

		//before the map panel exists, and before anything can resize it: the
		//panel's own resize listener reads this scroll pane's viewport, on the
		//event thread, where a NullPointerException has nothing to catch it
		CtrmapMainframe.mTilemapScrollPane = new JScrollPane();

		//one 40x40 region drawn at 400x400, so one tile is exactly ten pixels
		//and a screen coordinate is a map coordinate
		map = new BenchMap();
		map.width = 40;
		map.height = 40;
		map.mode = TileMapPanel.ViewportMode.SINGLE;
		map.tilemapScale = 1.0d;
		map.tilemapScaledImage = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
		map.loaded = true;
		CtrmapMainframe.mTileMapPanel = map;
		//the tileset the region's picture is painted with lives on the tile
		//form, so the form has to exist before the region does
		region = new Tilemap(null, 40, 40, tiles.tileset);
		map.tilemaps = new Tilemap[][]{{region}};

		JPanel tileMaster = new JPanel(new BorderLayout());
		JPanel collMaster = new JPanel(new BorderLayout());
		JPanel mtxMaster = new JPanel(new BorderLayout());
		//adjustSplitPanes divides by each master panel's width, and a width of
		//zero with a zero-width form on the other side of the fraction is NaN,
		//which setDividerLocation refuses
		tileMaster.setSize(1400, 900);
		collMaster.setSize(1400, 900);
		mtxMaster.setSize(1400, 900);
		JSplitPane worldSplit = split(new JPanel(), new JPanel());
		JSplitPane collSplit = split(new JPanel(), CtrmapMainframe.mCollEditPanel);
		JSplitPane mtxSplit = split(new JPanel(), CtrmapMainframe.mMtxEditForm);
		tileMaster.add(worldSplit, BorderLayout.CENTER);
		collMaster.add(collSplit);
		mtxMaster.add(mtxSplit);
		mainframeField("tileEditMasterPnl", tileMaster);
		mainframeField("collEditMasterPnl", collMaster);
		mainframeField("mtxEditMasterPnl", mtxMaster);
		mainframeField("jsp", worldSplit);
		mainframeField("jsp2", collSplit);
		mainframeField("jsp3", mtxSplit);

		if (!GraphicsEnvironment.isHeadless()) {
			CtrmapMainframe.frame = new JFrame();
			frameOk = true;
		}
	}

	private static JSplitPane split(java.awt.Component left, java.awt.Component right) {
		JSplitPane p = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
		p.setLeftComponent(left);
		p.setRightComponent(right);
		return p;
	}

	/**
	 * True when {@code CtrmapMainframe.frame} exists, so the paths that repaint
	 * or revalidate the window can be driven.
	 */
	static boolean frameAvailable() {
		return frameOk;
	}

	// ---- the window's own plumbing -----------------------------------------

	/**
	 * Puts a value in one of {@link CtrmapMainframe}'s private statics.
	 *
	 * <p>Loud on purpose. A field that has been renamed away must stop this
	 * bench dead rather than leave the panel it should have installed null,
	 * because a null master panel or split pane is a NullPointerException
	 * inside the first tool constructed - twenty checks later, in a place that
	 * says nothing about the bench.
	 */
	static void mainframeField(String name, Object value) {
		try {
			Field f = CtrmapMainframe.class.getDeclaredField(name);
			f.setAccessible(true);
			f.set(null, value);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("EditorBench cannot install CtrmapMainframe." + name, ex);
		}
	}

	/** One of those statics, read back. */
	static Object mainframeField(String name) {
		try {
			Field f = CtrmapMainframe.class.getDeclaredField(name);
			f.setAccessible(true);
			return f.get(null);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("EditorBench cannot read CtrmapMainframe." + name, ex);
		}
	}

	/** The World Editor's split: the map view on the left, the tool's form on the right. */
	static JSplitPane worldSplit() {
		return (JSplitPane) mainframeField("jsp");
	}

	// ---- the tool row ------------------------------------------------------

	/**
	 * The toolbar's ten tool buttons, in row order (edit, set, fill, cam,
	 * prop, npc, warp, trigger, paint, geo). Pressing one is how a user picks
	 * a tool, and the only path that both names the tool in the label and
	 * hands the command on.
	 */
	static List<JRadioButton> toolButtons() {
		List<JRadioButton> out = new ArrayList<JRadioButton>();
		for (java.awt.Component c : toolbar.getComponents()) {
			if (c instanceof JRadioButton) {
				out.add((JRadioButton) c);
			}
		}
		return out;
	}

	/** What the tool row's status label says right now, e.g. "Current tool: Edit". */
	static String currentToolText() {
		return toolbar.currentToolText();
	}

	/**
	 * Writes the tool row's status label directly.
	 *
	 * <p>Reflective by necessity, and only ever used to put the label somewhere
	 * a check can prove it was moved FROM. Nothing but a button press writes
	 * it in the application, which is the point: a check that the press set
	 * the label cannot be allowed to pass on the value the label already had.
	 */
	static void setCurrentToolText(String text) {
		try {
			Field f = WorldEditorToolbar.class.getDeclaredField("currentTool");
			f.setAccessible(true);
			((javax.swing.JLabel) f.get(toolbar)).setText(text);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("EditorBench cannot write the tool row's label", ex);
		}
	}

	/**
	 * Releases what would otherwise hold the JVM open after the last check.
	 *
	 * <p>PropEditForm's generated initComponents builds a CustomH3DPreview,
	 * whose constructor starts an FPSAnimator on a NON-daemon thread, and a
	 * JFrame starts the AWT toolkit thread. Left running, the suite prints its
	 * verdict and then never exits, which a runner reports as a hang rather
	 * than as a pass. DataSafetyGuardsTest learned the animator half of this
	 * the hard way.
	 */
	static void shutdown() {
		try {
			((CustomH3DPreview) field(prop, "PropPreview")).stop();
		} catch (Throwable t) {
			System.out.println("  note: could not stop the prop preview animator: " + t);
		}
		if (CtrmapMainframe.frame != null) {
			CtrmapMainframe.frame.dispose();
		}
	}

	// ---- fixtures ----------------------------------------------------------

	/**
	 * A zone with no records in it, built from bytes rather than from the dump
	 * so that the tool suites run in a fresh clone.
	 *
	 * <p>ZoneEntities has one constructor and it parses. Twelve bytes of header
	 * (a length and five one-byte counts, all zero, then three skipped) are
	 * followed by the zone's Pawn script, whose own header is 60 bytes and
	 * whose entry tables are sized by DIVIDING offset differences by
	 * {@code defsize}. All-zero bytes therefore divide by zero and throw
	 * ArithmeticException out of a constructor that only catches IOException,
	 * so defsize is 8 and every table offset is the end of the header: six
	 * tables of zero entries, no code, no data.
	 */
	static ZoneEntities emptyEntities() {
		byte[] b = new byte[72];
		int end = 60; //the Pawn header's own length, and so the end of everything
		putInt(b, 12, end);   //len
		b[16] = (byte) 0xF1;  //magic
		b[17] = (byte) 0xE0;
		b[22] = 8;            //defsize, the divisor
		putInt(b, 24, end);   //instructionStart
		putInt(b, 28, end);   //dataStart
		putInt(b, 32, end);   //heapStart
		putInt(b, 36, end);   //allocatedMem
		putInt(b, 40, 0);     //mainEntryPoint
		for (int off = 44; off <= 68; off += 4) {
			putInt(b, off, end); //publics..overlays, all empty
		}
		return new ZoneEntities(b);
	}

	private static void putInt(byte[] b, int off, int v) {
		b[off] = (byte) v;
		b[off + 1] = (byte) (v >> 8);
		b[off + 2] = (byte) (v >> 16);
		b[off + 3] = (byte) (v >> 24);
	}

	/** Puts the CM2D cursor on a tile, or off the map with (-1, -1). */
	static void hilight(int tx, int ty) {
		Selector.hilightTileX = tx;
		Selector.hilightTileY = ty;
	}

	/** A synthetic mouse event over the map panel, left or right button. */
	static MouseEvent mouse(int id, int x, int y, boolean right) {
		return new MouseEvent(map, id, 0L,
				right ? InputEvent.BUTTON3_MASK : InputEvent.BUTTON1_MASK,
				x, y, 1, false,
				right ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1);
	}

	static MouseEvent press(int x, int y, boolean right) {
		return mouse(MouseEvent.MOUSE_PRESSED, x, y, right);
	}

	static MouseEvent drag(int x, int y, boolean right) {
		return mouse(MouseEvent.MOUSE_DRAGGED, x, y, right);
	}

	static MouseEvent release(int x, int y, boolean right) {
		return mouse(MouseEvent.MOUSE_RELEASED, x, y, right);
	}

	static MouseEvent click(int x, int y, boolean right) {
		return mouse(MouseEvent.MOUSE_CLICKED, x, y, right);
	}

	/**
	 * The colour a fresh overlay canvas starts as. Deliberately not one of the
	 * colours the tools paint with: white, black, red and yellow are all
	 * meaningful in these overlays, so a background that was any of them would
	 * let an overlay that drew nothing pass a check for the thing it should
	 * have drawn.
	 */
	static final int BG = 0x336699;

	/** A 400x400 canvas to draw an overlay onto and read pixels back from. */
	static BufferedImage canvas() {
		BufferedImage img = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
		Graphics g = img.getGraphics();
		g.setColor(new java.awt.Color(BG));
		g.fillRect(0, 0, 400, 400);
		g.dispose();
		return img;
	}

	/** How many pixels of the given rectangle came out exactly this colour. */
	static int countColour(BufferedImage img, int x, int y, int w, int h, int rgb) {
		int n = 0;
		for (int j = y; j < y + h && j < img.getHeight(); j++) {
			for (int i = x; i < x + w && i < img.getWidth(); i++) {
				if (i >= 0 && j >= 0 && (img.getRGB(i, j) & 0xFFFFFF) == (rgb & 0xFFFFFF)) {
					n++;
				}
			}
		}
		return n;
	}

	// ---- reflection --------------------------------------------------------

	/** A field by name, looked up through the superclasses too (the bench forms are subclasses). */
	static Object field(Object o, String name) throws Exception {
		Class<?> c = o.getClass();
		while (c != null) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return f.get(o);
			} catch (NoSuchFieldException ex) {
				c = c.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name + " on " + o.getClass());
	}

	// ---- bench doubles -----------------------------------------------------

	/** See the class comment: a map view that can be located and never renders. */
	static final class BenchMap extends TileMapPanel {

		private static final long serialVersionUID = 1L;

		BenchMap() {
			super(LOADED, TOOLS);
		}
		int renders = 0;
		int repaints = 0;

		@Override
		public Point getLocationOnScreen() {
			return new Point(0, 0);
		}

		/*
		 * Reported rather than set. TileMapPanel's constructor registers a
		 * resize listener that builds an OFFSCREEN OPENGL DRAWABLE, so
		 * setSize(400, 400) posts an event that reaches JOGL on the event
		 * thread and throws there, with nothing to catch it; every tool then
		 * still works and the stack trace is pure noise in the log. Nothing in
		 * these suites lays the panel out, so answering the size is enough.
		 */
		@Override
		public int getWidth() {
			return 400;
		}

		@Override
		public int getHeight() {
			return 400;
		}

		@Override
		public void renderTileMap() {
			renders++;
		}

		@Override
		public void repaint() {
			repaints++;
		}
	}

	/**
	 * The painter form as a recorder. PaintTool's whole job is to turn a
	 * highlighted tile and a mouse button into one call on this form, and the
	 * form's own behaviour is PaintFormGuardsTest's and PaintApplyGuardsTest's
	 * subject, not this one's; a real PaintForm would also refuse every gesture
	 * outright, because nothing is seeded without a workspace and a zone.
	 */
	static final class BenchPaintForm extends PaintForm {

		private static final long serialVersionUID = 1L;

		BenchPaintForm() {
			super(LOADED);
		}
		final List<String> calls = new ArrayList<String>();

		@Override
		public void activate() {
			calls.add("activate");
		}

		@Override
		public void deactivate() {
			calls.add("deactivate");
		}

		@Override
		public void cancelPending() {
			calls.add("cancelPending");
		}

		@Override
		public void gesturePress(int gx, int gy, boolean right) {
			calls.add("press " + gx + "," + gy + "," + right);
		}

		@Override
		public void gestureDrag(int gx, int gy, boolean right) {
			calls.add("drag " + gx + "," + gy + "," + right);
		}

		@Override
		public void drawOverlay(Graphics g, int sx, int sy, double d) {
			calls.add("overlay " + sx + "," + sy + "," + d);
		}
	}

	/**
	 * The prop form as a recorder for the three calls PropTool makes on it.
	 *
	 * <p>{@code setProp} is overridden rather than left alone because the real
	 * one drives the dropdown, whose listener runs {@code showProp}, which
	 * dereferences the 3D panel this bench does not have; the NullPointerException
	 * is swallowed by showProp's own catch and leaves {@code loaded} false, so
	 * the SECOND drag of a test would be refused for a reason that exists only
	 * in the test. The net effect the tool depends on - this index is now the
	 * selected prop - is reproduced here.
	 */
	static final class BenchPropForm extends PropEditForm {

		private static final long serialVersionUID = 1L;

		BenchPropForm() {
			super(LOADED, TOOLS, REDRAW);
		}
		final List<String> calls = new ArrayList<String>();

		@Override
		public void setProp(int index) {
			calls.add("setProp " + index);
			propIndex = index;
			prop = props.props.get(index);
		}

		@Override
		public void showProp(int index) {
			calls.add("showProp " + index);
		}

		@Override
		public void saveAndRefresh() {
			calls.add("saveAndRefresh");
		}
	}

	/**
	 * The camera form as a recorder. Both methods CameraTool calls end in
	 * {@code CtrmapMainframe.frame.repaint()}, and {@code commitAndSwitch}
	 * moves the dropdown from a focus listener, which never fires while the
	 * form is not showing; recording the calls is what a suite can hold.
	 */
	static final class BenchCamForm extends CameraEditForm {

		private static final long serialVersionUID = 1L;

		BenchCamForm() {
			super(REDRAW);
		}
		final List<String> calls = new ArrayList<String>();

		@Override
		public void commitAndSwitch(int switchNum) {
			calls.add("commitAndSwitch " + switchNum);
		}

		@Override
		public void showCamera(int entryNum, boolean save) {
			calls.add("showCamera " + entryNum + "," + save);
		}
	}
}
