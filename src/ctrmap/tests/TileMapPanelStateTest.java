package ctrmap.tests;

import ctrmap.LoadedZone;
import ctrmap.Ui;
import ctrmap.Workspace;
import ctrmap.formats.containers.GR;
import ctrmap.formats.containers.MM;
import ctrmap.formats.gfcollision.GRCollisionFile;
import ctrmap.formats.mapmatrix.MapMatrix;
import ctrmap.formats.tilemap.Tilemap;
import ctrmap.gamedef.ArchiveType;
import ctrmap.humaninterface.TileMapPanel;
import java.awt.Dimension;
import java.awt.Point;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/**
 * The map view's non-painting half: what a save writes, what a cancelled save
 * refuses to lose, what unloading clears, and where the viewport thinks it is.
 *
 * <p>WHY THIS SUITE EXISTS. {@link TileMapPanel} is the editor's map, and
 * almost everything measured about it so far has been how it REPORTS failure -
 * {@code DataSafetyGuardsTest} drives {@code awaitLoad}, {@code refreshFailed}
 * and {@code regionSaveFailed}, and {@code NpcEntityGuardsTest} pins the static
 * height lookup's bounds. Deliberately not repeated here.
 *
 * <p>What was never measured is the decision that runs before any of those: the
 * save chain. Every zone switch, every clone and every append begins
 * {@code mCamEditForm.store(true) && mTileMapPanel.saveTileMap(true) && ...},
 * so this one boolean is what stands between "the user said cancel" and "the
 * editor moved on and their tiles are gone". Three of its arms write, clear a
 * flag, or do neither, and each is one line:
 *
 * <ul>
 * <li>YES writes the assembled tilemap into the region container and clears the
 *     modified flag. Drop the write and the flag still clears, so nothing ever
 *     offers to save it again - the edit is gone with the dialog having said it
 *     was kept.</li>
 * <li>NO clears the flag WITHOUT writing, which is the user discarding on
 *     purpose. Make it write and "no" silently means yes.</li>
 * <li>Anything else returns false, and false is what stops the zone switch. Get
 *     that backwards and cancel means proceed.</li>
 * </ul>
 *
 * <p>The matrix save has the same three arms over a grid, plus the one asymmetry
 * pinned below and not fixed: called with {@code dialog} false it writes
 * without asking, while the single-region save called the same way writes
 * nothing at all and leaves the region modified. Both are characterized as they
 * behave.
 *
 * <p>Painting, scaling and the 3D view are out of scope and skipped loudly:
 * {@code renderTileMap} and {@code loadTileMap} both call
 * {@code GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()},
 * which has no answer without a display. Their GUARDS are reachable and are
 * checked; what is behind them is not, and says so.
 *
 * Usage: java ctrmap.tests.TileMapPanelStateTest &lt;pristine dump root&gt;
 */
public class TileMapPanelStateTest {

	/** The 3D scene the map view shares: a recorder, so what it was told can be read. */
	static final RecordingScene SCENE = new RecordingScene();
	/** The tool this suite holds: its own, so another suite may hold another. */
	static final ctrmap.humaninterface.tools.ToolSelection TOOLS = new ctrmap.humaninterface.tools.ToolSelection();


	/** Mauville, whose map matrix is its own in the retail game. */
	private static final int ZONE = 15;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");

		//needs no game: pure geometry over arrays the suite builds itself
		thetwoWaysOfPointingTheCameraStayTwo();
		theViewportCentreIsWhereTheScrollBarsSay();
		aRegionIsPickedByTileNumber();
		theHeightLookupReadsThePanelsOwnCollisions();
		scalingRefusesWhatItCannotDraw();
		aStaleReloadIsIgnoredRatherThanWritten();
		theAnimatorFindsNothingToDrawBeforeAZoneOpens();
		theColoursARegionIsPaintedInFollowTheTileset();
		unloadingDropsTheUndoHistoryWithTheMap();
		theTallGrassIsReadInOnePlace();
		anEditedRegionIsDrawnWhereItSits();
		aDroppedRegionModelIsFreedNotLeaked();
		theGlOverlaysSettingsAreMadeWhereGlStarts();

		if (!dump.isDirectory()) {
			Skip.section("no dump at " + dump
					+ " - the save-path checks need real region containers");
		} else {
			ScratchGame.open(dump);
			aSingleRegionSaveAsksAndActsOnTheAnswer();
			aCancelledSaveStopsTheLoadThatAskedForIt();
			aMatrixSaveAsksOnceAndWritesEveryRegion();
			aMatrixSaveThatIsRefusedWritesNothing();
			unloadingLeavesNothingOfTheOldMap();
			aMatrixThatOutgrewItsArraysWritesOnlyTheCellsItHas();
			openingALooseMapDropsThePreviousZonesTextures();
			loadingAMapDropsTheTilePickedOnTheLastOne();
			aRegionsTallGrassIsItsModelOrNothing();
			readingAMatrixReadsItsFilledCellsOnly();
		}

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * The map view's GL overlay is set up where OpenGL starts - hardware, single-buffered, an alpha channel - and is
	 * none where it does not, the view built either way (the 2026-10-15 shared sweep, TileMapPanel 289, negate-if,
	 * SURVIVED: GlUnavailableTest asks the second half and skips where OpenGL starts, and nothing asked the first).
	 * Whichever this machine is, the inversion is seen: unset settings where GL starts, a throw where it does not.
	 */
	static void theGlOverlaysSettingsAreMadeWhereGlStarts() throws Exception {
		System.out.println("--- the map view's GL overlay is set up where OpenGL starts, and is none where it does not");
		ctrmap.humaninterface.tools.ToolSelection tools = new ctrmap.humaninterface.tools.ToolSelection();
		String built;
		Object caps = null;
		try {
			ctrmap.humaninterface.TileMapPanel map = new ctrmap.humaninterface.TileMapPanel(new ctrmap.LoadedZone(), tools,
					new RecordingScene(), new javax.swing.JScrollPane(),
					new ctrmap.humaninterface.CollEditPanel(tools, UndoHistoryTest.overOneNumber(new int[]{0})), null);
			java.lang.reflect.Field f = ctrmap.humaninterface.TileMapPanel.class.getDeclaredField("caps");
			f.setAccessible(true);
			caps = f.get(map);
			built = "built";
		} catch (RuntimeException | LinkageError threw) {
			built = "threw " + threw;
		}
		boolean gl = ctrmap.humaninterface.GlSupport.available();
		boolean set = caps != null && ((com.jogamp.opengl.GLCapabilities) caps).getHardwareAccelerated()
				&& !((com.jogamp.opengl.GLCapabilities) caps).getDoubleBuffered()
				&& ((com.jogamp.opengl.GLCapabilities) caps).getAlphaBits() == 8;
		check("built".equals(built) && (gl ? set : caps == null), "OpenGL " + (gl ? "starts here: the overlay is"
				+ " hardware, single-buffered, with alpha" : "does not start here: the view is built with no overlay")
				+ " - " + built + ", " + caps);
	}

	/**
	 * Reading a matrix reads every filled cell and no empty one (the 2026-10-15 shared sweep, TileMapPanel 668,
	 * negate-if, SURVIVED: readRegions was read in its source only). Inverted, the filled cells were skipped and the
	 * empty ones read as regions that are not there. Over the first retail matrix of at most 64 cells with cells of both kinds.
	 */
	static void readingAMatrixReadsItsFilledCellsOnly() throws Exception {
		System.out.println("--- reading a matrix reads its filled cells and no empty one");
		MapMatrix found = null;
		int filled = 0;
		int empty = 0;
		int matrices = Workspace.getArchive(ArchiveType.MAP_MATRIX).length;
		for (int id = 0; id < matrices && found == null; id++) {
			File f = Workspace.getWorkspaceFile(ArchiveType.MAP_MATRIX, id);
			if (f == null || !f.isFile()) {
				continue;
			}
			MapMatrix mm = new MapMatrix(new MM(f, Workspace.session()), Workspace.session());
			if (mm.width * mm.height > 64) {
				continue;
			}
			filled = 0;
			empty = 0;
			for (int i = 0; i < mm.height; i++) {
				for (int j = 0; j < mm.width; j++) {
					if (mm.ids.get(j, i) != -1) {
						filled++;
					} else {
						empty++;
					}
				}
			}
			found = filled > 0 && empty > 0 ? mm : null;
		}
		check(found != null, "control: a retail matrix of at most 64 cells has filled cells and empty ones (" + filled
				+ " filled, " + empty + " empty)");
		if (found == null) {
			return;
		}
		String wrong;
		try {
			TileMapPanel.Regions r = TileMapPanel.readRegions(found, null, null, null, null);
			Tilemap[][] tilemaps = (Tilemap[][]) field(r, "tilemaps");
			//and each filled cell's map model, placed where it sits (TileMapPanel 673, negate-if, SURVIVED in the same
			//sweep: the model of a cell that has one was dropped)
			ctrmap.formats.h3d.BCHFile[][] models = (ctrmap.formats.h3d.BCHFile[][]) field(r, "models");
			//and a caption for the collision tree exactly where a filled cell's collision reads (TileMapPanel 701,
			//negate-if, SURVIVED in the same sweep: captioned where it did not read, and not where it did)
			ctrmap.formats.gfcollision.GRCollisionFile[][] colls = (ctrmap.formats.gfcollision.GRCollisionFile[][]) field(r, "colls");
			String[][] captions = (String[][]) field(r, "captions");
			List<String> off = new java.util.ArrayList<>();
			for (int i = 0; i < found.height; i++) {
				for (int j = 0; j < found.width; j++) {
					boolean inUse = found.ids.get(j, i) != -1;
					boolean reads = inUse && colls[j][i] != null && colls[j][i].unreadable == null;
					if (inUse != (tilemaps[j][i] != null) || inUse != (models[j][i] != null)
							|| reads != (captions[j][i] != null)) {
						off.add("(" + j + "," + i + ")");
					}
				}
			}
			wrong = off.isEmpty() ? "none" : off.toString();
			//and showing them hands the collision editor every captioned cell's collision, row by row, and no other
			//(TileMapPanel 735, negate-if, SURVIVED in the same sweep: showRegions was never called by a suite)
			List<ctrmap.formats.gfcollision.GRCollisionFile> captioned = new java.util.ArrayList<>();
			for (int i = 0; i < found.height; i++) {
				for (int j = 0; j < found.width; j++) {
					if (captions[j][i] != null) {
						captioned.add(colls[j][i]);
					}
				}
			}
			check(!captioned.isEmpty(), "control: the matrix has a captioned cell (" + captioned.size() + ")");
			ctrmap.humaninterface.CollEditPanel editor = new ctrmap.humaninterface.CollEditPanel(TOOLS,
					UndoHistoryTest.overOneNumber(new int[]{0}));
			String handed;
			javax.swing.JScrollPane scroll = new javax.swing.JScrollPane();
			RecordingScene viewScene = new RecordingScene();
			TileMapPanel shown = null;
			try {
				shown = new TileMapPanel(new ctrmap.LoadedZone(), TOOLS, viewScene, scroll, editor, null);
				shown.showRegions(r, null, null);
				handed = editor.files.equals(captioned) ? "every captioned cell's, in order"
						: editor.files.size() + " of " + captioned.size();
			} catch (RuntimeException threw) {
				handed = "threw " + threw;
			}
			check("every captioned cell's, in order".equals(handed), "showing them gives the collision editor "
					+ "every captioned cell's collision and no other: " + handed);
			//and the map shown paints with no GL overlay made - none is until the view is resized, and none ever is
			//where OpenGL does not start (TileMapPanel 1115, negate-if, SURVIVED in the same sweep: no suite painted
			//a loaded map). Inverted, a view with no overlay asked the one it has not got to draw.
			String painted;
			TOOLS.switchTo(QuietTool::new);
			try {
				scroll.setSize(200, 200);
				scroll.doLayout();
				java.awt.image.BufferedImage frame = new java.awt.image.BufferedImage(200, 200,
						java.awt.image.BufferedImage.TYPE_INT_ARGB);
				java.awt.Graphics g = frame.getGraphics();
				java.lang.reflect.Method paint = TileMapPanel.class.getDeclaredMethod("paintComponent", java.awt.Graphics.class);
				paint.setAccessible(true);
				int asked = QuietTool.overlays;
				try {
					paint.invoke(shown, g);
				} finally {
					g.dispose();
				}
				painted = QuietTool.overlays == asked + 1 ? "painted" : "painted without reaching the tool's overlay";
			} catch (java.lang.reflect.InvocationTargetException threw) {
				painted = "threw " + threw.getCause();
			} catch (RuntimeException threw) {
				painted = "threw " + threw;
			} finally {
				TOOLS.drop();
			}
			check("painted".equals(painted), "and the map it shows paints with no GL overlay made: " + painted);
			//and with one made: the first paint draws it, a paint while the held tool asks for no update keeps that
			//frame, and one that does not draws anew (TileMapPanel 1118, negate-if, SURVIVED in the same sweep: no
			//suite painted a map with an overlay). Inverted, the overlay was drawn only when it need not be, and a
			//paint that should draw it showed the last frame, or none
			if (!ctrmap.humaninterface.GlSupport.available()) {
				Skip.section("OpenGL does not start here, so the map view has no overlay to keep or draw anew");
			} else {
				//DRAWN IS COUNTED, NOT SEEN BY IDENTITY: the frame reader hands back one image it fills again, so a
				//frame drawn anew is the same object as the last (measured: the first version of this check said a
				//paint not asked to keep the frame kept it). The overlay draws the view's scene, and one renderable
				//in it counts its draws
				String overlay;
				String laid = "(not painted)";
				int[] draws = {0};
				viewScene.renderables().add(new CountingRenderable(draws));
				TOOLS.switchTo(QuietTool::new);
				try {
					//SIZED AS THE EDITOR SIZES IT, to its image, so the image starts at the panel's corner - unsized, the
					//origin was (-1600,-1600) and the overlay was laid off the frame (measured). Its listeners are held
					//off while it is sized: a resize is POSTED to the event thread, which would make a GL context of its
					//own current there while this thread holds one; the resize is delivered here instead, below
					java.lang.reflect.Field image = TileMapPanel.class.getDeclaredField("tilemapScaledImage");
					image.setAccessible(true);
					java.awt.image.BufferedImage drawn = (java.awt.image.BufferedImage) image.get(shown);
					java.awt.event.ComponentListener[] listening = shown.getComponentListeners();
					for (java.awt.event.ComponentListener l : listening) {
						shown.removeComponentListener(l);
					}
					shown.setSize(drawn.getWidth(), drawn.getHeight());
					for (java.awt.event.ComponentListener l : listening) {
						shown.addComponentListener(l);
					}
					shown.dispatchEvent(new java.awt.event.ComponentEvent(shown, java.awt.event.ComponentEvent.COMPONENT_RESIZED));
					java.lang.reflect.Field last = TileMapPanel.class.getDeclaredField("CM2DTempImage");
					last.setAccessible(true);
					Object first = paintFrame(shown, last);
					int afterFirst = draws[0];
					TOOLS.current().CM2DNoUpdate = true;
					paintFrame(shown, last);
					int afterKept = draws[0];
					TOOLS.current().CM2DNoUpdate = false;
					paintFrame(shown, last);
					int afterFresh = draws[0];
					overlay = first == null || afterFirst != 1 ? "the first paint drew the overlay " + afterFirst + " time(s)"
							: afterKept != afterFirst ? "a paint asked for no update drew anew"
							: afterFresh != afterKept + 1 ? "a paint not asked to keep the frame kept it" : "kept, then drawn anew";
					//and what the overlay drew is laid over the map: a renderable that fills it red turns the middle
					//of the painted map red (TileMapPanel 1138, negate-if, SURVIVED in the same sweep - inverted, an
					//overlay that was drawn was never laid over the map, and one that was not was asked to be)
					viewScene.renderables().add(new RedOverlay());
					java.awt.image.BufferedImage onto = new java.awt.image.BufferedImage(200, 200,
							java.awt.image.BufferedImage.TYPE_INT_ARGB);
					java.awt.Graphics g = onto.getGraphics();
					try {
						java.lang.reflect.Method paint = TileMapPanel.class.getDeclaredMethod("paintComponent",
								java.awt.Graphics.class);
						paint.setAccessible(true);
						paint.invoke(shown, g);
					} finally {
						g.dispose();
					}
					int reds = 0, x0 = 200, y0 = 200, x1 = -1, y1 = -1;
					for (int y = 0; y < 200; y++) {
						for (int x = 0; x < 200; x++) {
							int px = onto.getRGB(x, y);
							int red = px >> 16 & 0xFF, green = px >> 8 & 0xFF, blue = px & 0xFF;
							if ((px >>> 24) > 0 && red > 2 * green && red > 2 * blue) {
								reds++;
								x0 = Math.min(x0, x);
								y0 = Math.min(y0, y);
								x1 = Math.max(x1, x);
								y1 = Math.max(y1, y);
							}
						}
					}
					java.lang.reflect.Method origin = TileMapPanel.class.getDeclaredMethod("imageOrigin");
					origin.setAccessible(true);
					laid = reds > 0 ? "laid over the map" : "not laid over the map: no red pixel, image origin "
							+ origin.invoke(shown) + ", overlay frame " + (last.get(shown) == null ? "none"
							: ((java.awt.image.BufferedImage) last.get(shown)).getWidth() + "x"
							+ ((java.awt.image.BufferedImage) last.get(shown)).getHeight() + " #" + Integer.toHexString(
							((java.awt.image.BufferedImage) last.get(shown)).getRGB(10, 10)));
					System.out.println("  (red over " + reds + " pixel(s), (" + x0 + "," + y0 + ")-(" + x1 + "," + y1 + "))");
				} catch (ReflectiveOperationException | RuntimeException threw) {
					overlay = "threw " + (threw.getCause() != null ? threw.getCause() : threw);
				} finally {
					TOOLS.drop();
				}
				check("kept, then drawn anew".equals(overlay), "and with an overlay made, a paint the held tool asks to"
						+ " keep keeps the last frame and any other draws anew: " + overlay);
				check("laid over the map".equals(laid), "and what the overlay drew is laid over the map: " + laid);
			}
		} catch (RuntimeException threw) {
			wrong = "threw " + threw;
		}
		check("none".equals(wrong), "every filled cell is read and no empty one: cells read wrongly - " + wrong);
	}

	/**
	 * A region edited on the map is drawn again where it sits, at the view's scale, and a cell the map has not got
	 * draws nothing and throws nothing (the 2026-10-15 shared sweep, TileMapPanel 1278, negate-if, SURVIVED: no suite
	 * redrew one region). Inverted, an edited region was never redrawn and a cell that is not there threw. Needs no
	 * game: a 2x2 map whose region (1,0) is a tile map, made without its constructor, whose image is solid red.
	 */
	static void anEditedRegionIsDrawnWhereItSits() throws Exception {
		System.out.println("--- an edited region is drawn again where it sits, and a cell not there draws nothing");
		TileMapPanel map = new TileMapPanel(new ctrmap.LoadedZone(), TOOLS, new RecordingScene(), new javax.swing.JScrollPane(),
				new ctrmap.humaninterface.CollEditPanel(TOOLS, UndoHistoryTest.overOneNumber(new int[]{0})), null);
		map.loaded = true;
		map.tilemapScaledImage = new java.awt.image.BufferedImage(400, 400, java.awt.image.BufferedImage.TYPE_INT_RGB);
		map.tilemaps = new ctrmap.formats.tilemap.Tilemap[2][2];
		map.tilemaps[1][0] = redTilemap();
		String drawn;
		String missing;
		try {
			map.perfScale(0.5, 1, 0);
			int inside = map.tilemapScaledImage.getRGB(250, 50) & 0xFFFFFF;
			int outside = map.tilemapScaledImage.getRGB(50, 50) & 0xFFFFFF;
			drawn = inside == 0xFF0000 && outside == 0 ? "drawn at (1,0)"
					: String.format("not where it sits: #%06X inside its cell, #%06X outside", inside, outside);
		} catch (RuntimeException threw) {
			drawn = "threw " + threw;
		}
		try {
			map.perfScale(0.5, 5, 5);
			missing = "nothing";
		} catch (RuntimeException threw) {
			missing = "threw " + threw;
		}
		check("drawn at (1,0)".equals(drawn) && "nothing".equals(missing),
				"an edited region is drawn again where it sits (" + drawn + "), and a cell not there draws " + missing);
	}

	/** A tile map whose image is solid red, made without its constructor: getImage is all a redraw asks of it. */
	static ctrmap.formats.tilemap.Tilemap redTilemap() throws Exception {
		java.lang.reflect.Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
		theUnsafe.setAccessible(true);
		Object unsafe = theUnsafe.get(null);
		return (ctrmap.formats.tilemap.Tilemap) unsafe.getClass().getMethod("allocateInstance", Class.class)
				.invoke(unsafe, RedTilemap.class);
	}

	/** See {@link #redTilemap}; its constructor is never run. */
	static final class RedTilemap extends ctrmap.formats.tilemap.Tilemap {

		private RedTilemap() {
			super((ctrmap.formats.containers.GR) null);
		}

		@Override
		public java.awt.image.BufferedImage getImage() {
			java.awt.image.BufferedImage red = new java.awt.image.BufferedImage(40, 40, java.awt.image.BufferedImage.TYPE_INT_RGB);
			java.awt.Graphics g = red.getGraphics();
			g.setColor(java.awt.Color.RED);
			g.fillRect(0, 0, 40, 40);
			g.dispose();
			return red;
		}
	}

	/** A renderable that fills whatever it is drawn into with opaque red: what an overlay drew, unmistakably. */
	static final class RedOverlay implements ctrmap.humaninterface.CM3DRenderable {

		@Override
		public void renderCM3D(com.jogamp.opengl.GL2 gl) {
			gl.glClearColor(1f, 0f, 0f, 1f);
			gl.glClear(com.jogamp.opengl.GL2.GL_COLOR_BUFFER_BIT);
			gl.glClearColor(0f, 0f, 0f, 0f);
		}

		@Override
		public void renderOverlayCM3D(com.jogamp.opengl.GL2 gl) {
		}

		@Override
		public void uploadBuffers(com.jogamp.opengl.GL2 gl) {
		}

		@Override
		public void deleteGLInstanceBuffers(com.jogamp.opengl.GL2 gl) {
		}

		@Override
		public void doSelectionLoop(java.awt.event.MouseEvent e, java.awt.Component parent, float[] mvMatrix,
				float[] projMatrix, int[] view, ctrmap.formats.vectors.Vec3f cameraVec) {
		}
	}

	/** A renderable that draws nothing and counts each time it is asked to. */
	static final class CountingRenderable implements ctrmap.humaninterface.CM3DRenderable {

		private final int[] draws;

		CountingRenderable(int[] draws) {
			this.draws = draws;
		}

		@Override
		public void renderCM3D(com.jogamp.opengl.GL2 gl) {
			draws[0]++;
		}

		@Override
		public void renderOverlayCM3D(com.jogamp.opengl.GL2 gl) {
		}

		@Override
		public void uploadBuffers(com.jogamp.opengl.GL2 gl) {
		}

		@Override
		public void deleteGLInstanceBuffers(com.jogamp.opengl.GL2 gl) {
		}

		@Override
		public void doSelectionLoop(java.awt.event.MouseEvent e, java.awt.Component parent, float[] mvMatrix,
				float[] projMatrix, int[] view, ctrmap.formats.vectors.Vec3f cameraVec) {
		}
	}

	/** Paints {@code map} once onto a frame of its own and answers the overlay frame it kept, read from {@code last}. */
	static Object paintFrame(TileMapPanel map, java.lang.reflect.Field last) throws ReflectiveOperationException {
		java.awt.image.BufferedImage frame = new java.awt.image.BufferedImage(200, 200,
				java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics g = frame.getGraphics();
		java.lang.reflect.Method paint = TileMapPanel.class.getDeclaredMethod("paintComponent", java.awt.Graphics.class);
		paint.setAccessible(true);
		try {
			paint.invoke(map, g);
		} finally {
			g.dispose();
		}
		return last.get(map);
	}

	/** A tool that draws nothing and touches no editor: what the map view needs held to paint. */
	static final class QuietTool extends ctrmap.humaninterface.tools.AbstractTool {

		/** How many times the view asked it for its overlay - the last thing a paint of a loaded map does. */
		static int overlays;

		QuietTool() {
			super(new RecordingHost());
		}

		@Override
		public void onToolInit() {
		}

		@Override
		public void onToolShutdown() {
		}

		@Override
		public void fireCancel() {
		}

		@Override
		public void drawOverlay(java.awt.Graphics g, int x, int y, double dim) {
			overlays++;
		}

		@Override
		public boolean getSelectorEnabled() {
			return false;
		}

		@Override
		public boolean getNaviEnabled() {
			return false;
		}

		@Override
		public void onTileClick(java.awt.event.MouseEvent e) {
		}

		@Override
		public void onTileMouseDown(java.awt.event.MouseEvent e) {
		}

		@Override
		public void onTileMouseUp(java.awt.event.MouseEvent e) {
		}

		@Override
		public void onTileMouseDragged(java.awt.event.MouseEvent e) {
		}
	}

	/**
	 * A region's tall grass is the model its subfile 5 holds, or nothing when that is no model (the 2026-10-15 shared
	 * sweep, TileMapPanel 225, negate-if, SURVIVED: tallGrassOf was read in its source only). Inverted, a region with
	 * grass drew none, and one without had its bytes decoded as a model. Over the first region of each kind in the dump.
	 */
	static void aRegionsTallGrassIsItsModelOrNothing() throws Exception {
		System.out.println("--- a region's tall grass is its subfile 5's model, or nothing");
		ctrmap.formats.garc.GARC fd = ctrmap.Workspace.getArchive(ctrmap.gamedef.ArchiveType.FIELD_DATA);
		java.lang.reflect.Method of = ctrmap.humaninterface.TileMapPanel.class.getDeclaredMethod("tallGrassOf",
				ctrmap.formats.containers.GR.class, List.class, float.class, float.class);
		of.setAccessible(true);
		ctrmap.formats.containers.GR grass = null;
		ctrmap.formats.containers.GR none = null;
		for (int r = 0; r < fd.length && (grass == null || none == null); r++) {
			File f = Scratch.file("ctrmap_tallgrass");
			java.nio.file.Files.write(f.toPath(), fd.getDecompressedEntry(r));
			ctrmap.formats.containers.GR g = new ctrmap.formats.containers.GR(f, ctrmap.Workspace.session());
			if (ctrmap.formats.h3d.BchSections.hasMagic(g.getFile(5))) {
				grass = grass == null ? g : grass;
			} else {
				none = none == null ? g : none;
			}
		}
		check(grass != null && none != null, "control: the dump holds a region with tall grass and one without");
		if (grass == null || none == null) {
			return;
		}
		Object withGrass = of.invoke(null, grass, null, 0f, 0f);
		Object without = of.invoke(null, none, null, 0f, 0f);
		check(withGrass != null && !((ctrmap.formats.h3d.BCHFile) withGrass).models.isEmpty() && without == null,
				"a region with tall grass draws its model, and one without draws nothing: " + withGrass + ", " + without);
	}

	// ---- the save chain ----------------------------------------------------

	/**
	 * One region open, one tile edited, and the three answers the user can give.
	 *
	 * <p>The bytes are the assertion, because they are the only thing that
	 * distinguishes the three. All three close the dialog, all three let the
	 * editor carry on or not, and only the container on disk says which of them
	 * actually happened.
	 *
	 * <p>Also pinned, and NOT fixed: called with {@code dialog} false - which is
	 * how {@code saveMatrix} is reached from the same method, and how a
	 * scripted save would come in - the single-region path writes NOTHING and
	 * leaves the region marked modified, while still returning true. The
	 * multi-region path called the same way writes everything. See
	 * suspected_defects.
	 */
	static void aSingleRegionSaveAsksAndActsOnTheAnswer() throws Exception {
		System.out.println("--- one region: what each answer to the save prompt writes");
		TileMapPanel panel = single(153);
		byte[] pristine = panel.mainGR.getFile(0);

		//nothing modified: no question, no write
		List<String> said = Ui.record();
		boolean ok;
		try {
			ok = save(panel, true);
		} finally {
			Ui.stopRecording();
		}
		check(ok && said.isEmpty(), "an unmodified region is saved without asking anything: " + said);
		check(Arrays.equals(panel.mainGR.getFile(0), pristine), "and without writing");

		//not loaded at all: still no question
		panel.loaded = false;
		said = Ui.record();
		try {
			ok = save(panel, true);
		} finally {
			Ui.stopRecording();
		}
		check(ok && said.isEmpty(), "a panel with no map open saves without asking: " + said);
		panel.loaded = true;

		//edited, and the user keeps it
		byte[] edited = editATile(panel);
		said = Ui.record(JOptionPane.YES_OPTION);
		try {
			ok = save(panel, true);
		} finally {
			Ui.stopRecording();
		}
		check(said.size() == 1 && said.get(0).equals(
				"Save changes: Tilemap has been modified. Do you want to keep the changes?"),
				"an edited region asks whether to keep it, naming what changed: " + said);
		check(ok, "and keeping it lets the editor carry on");
		check(Arrays.equals(panel.mainGR.getFile(0), edited),
				"and the edited tilemap really is in the container");
		check(!panel.tilemaps[0][0].modified,
				"and the region is no longer marked modified, so it is not offered again");

		//edited, and the user discards it
		panel = single(154);
		pristine = panel.mainGR.getFile(0);
		editATile(panel);
		said = Ui.record(JOptionPane.NO_OPTION);
		try {
			ok = save(panel, true);
		} finally {
			Ui.stopRecording();
		}
		check(ok, "discarding also lets the editor carry on");
		check(Arrays.equals(panel.mainGR.getFile(0), pristine),
				"but nothing was written - \"no\" does not quietly mean yes");
		check(!panel.tilemaps[0][0].modified, "and the discard is remembered, so it is not asked again");

		//edited, and the user cancels: the editor must NOT carry on
		panel = single(155);
		pristine = panel.mainGR.getFile(0);
		editATile(panel);
		said = Ui.record(JOptionPane.CANCEL_OPTION);
		try {
			ok = save(panel, true);
		} finally {
			Ui.stopRecording();
		}
		check(!ok, "cancelling refuses, which is what stops the zone switch that asked");
		check(Arrays.equals(panel.mainGR.getFile(0), pristine), "with nothing written");
		check(panel.tilemaps[0][0].modified, "and the edit still marked modified, so it can still be saved");

		//nobody there at all reads as cancel, never as consent
		said = Ui.record();
		try {
			ok = save(panel, true);
		} finally {
			Ui.stopRecording();
		}
		check(!ok && panel.tilemaps[0][0].modified,
				"a closed dialog is treated as cancel, not as permission to discard");

		//RETARGETED. This read "PINNED AS IS: dialog=false writes nothing here,
		//unlike the matrix path" - the asymmetry was seen, written down and
		//left standing. It was not an asymmetry, it was data loss: File > Save
		//is openEditors.saveAll(FALSE), so every tile edit made to a loose GR
		//map was reported saved and thrown away, and the matrix path a few
		//lines up never had the problem, so nobody with a zone open could see
		//it. The flag means "may I ask", not "may I write".
		ok = save(panel, false);
		check(ok, "asked not to prompt, the single-region save returns true");
		check(!Arrays.equals(panel.mainGR.getFile(0), pristine),
				"AND WRITES: a flush that asks nobody still has to save");
		check(!panel.tilemaps[0][0].modified, "with nothing left pending afterwards");
	}

	/**
	 * A load that could not save first must not load.
	 *
	 * <p>{@code loadTileMap} opens with the save chain, so a cancelled save has
	 * to stop it: carrying on replaces the tilemap the user just refused to
	 * discard with a different region's, and the edit is gone with no dialog
	 * having said so.
	 *
	 * <p>Only the refusal is driven. Past it the method asks the local graphics
	 * environment for a screen device, which has no answer here.
	 */
	static void aCancelledSaveStopsTheLoadThatAskedForIt() throws Exception {
		System.out.println("--- a load whose pending save was cancelled does not load");
		TileMapPanel panel = single(156);
		Tilemap before = panel.tilemaps[0][0];
		GR wasOpen = panel.mainGR;
		editATile(panel);

		GR other = new GR(Workspace.getWorkspaceFile(ArchiveType.FIELD_DATA, 157), Workspace.session());
		List<String> said = Ui.record(JOptionPane.CANCEL_OPTION);
		try {
			panel.loadTileMap(other);
		} finally {
			Ui.stopRecording();
		}
		check(said.size() == 1 && said.get(0).startsWith("Save changes: Tilemap"),
				"the load asks about the unsaved edit first: " + said);
		check(panel.mainGR == wasOpen && panel.tilemaps[0][0] == before,
				"and a cancelled answer leaves the map that was open exactly where it was");
		check(panel.tilemaps[0][0].modified, "with the edit still unsaved and still offered");
		Skip.section("the loading half of loadTileMap needs a screen"
				+ " (GraphicsEnvironment.getDefaultScreenDevice)");
	}

	/**
	 * A multi-region map: one edited cell, and the whole grid written.
	 *
	 * <p>The save asks ONCE, at the first modified cell it finds, and then
	 * writes every populated cell. That is worth pinning both ways round: ask
	 * per cell and a nine-region map asks nine times, and write only the
	 * modified cell and the flag-clearing loop still clears all nine, so the
	 * other eight are never offered again.
	 *
	 * <p>The bytes assert it - each cell's container is compared against the
	 * tilemap the panel would assemble for it.
	 */
	static void aMatrixSaveAsksOnceAndWritesEveryRegion() throws Exception {
		System.out.println("--- a matrix save asks once and writes every populated cell");
		TileMapPanel panel = multiCell();
		if (panel == null) {
			check(false, "no retail map matrix of 2 to 8 populated cells was found");
			return;
		}
		int cells = populated(panel);
		check(cells >= 2, "a real multi-region map: " + panel.mm.width + "x" + panel.mm.height
				+ " with " + cells + " populated cell(s), so \"once\" is a claim about a grid");

		//nothing modified: no question, no write
		List<String> said = Ui.record();
		boolean ok;
		try {
			ok = save(panel, true);
		} finally {
			Ui.stopRecording();
		}
		check(ok && said.isEmpty(), "an unmodified matrix is saved without asking: " + said);

		editATile(panel);
		byte[][][] wanted = new byte[panel.mm.width][panel.mm.height][];
		for (int i = 0; i < panel.mm.height; i++) {
			for (int j = 0; j < panel.mm.width; j++) {
				wanted[j][i] = panel.mm.regions.get(j, i) == null ? null : panel.tilemaps[j][i].assembleTilemap();
			}
		}
		said = Ui.record(JOptionPane.YES_OPTION);
		try {
			ok = save(panel, true);
		} finally {
			Ui.stopRecording();
		}
		check(said.size() == 1 && said.get(0).equals(
				"Save changes: Region data has been modified. Do you want to keep the changes?"),
				"an edited matrix asks ONCE, naming region data rather than one tilemap: " + said);
		check(ok, "and keeping it lets the editor carry on");
		int written = 0;
		boolean allWritten = true;
		for (int i = 0; i < panel.mm.height; i++) {
			for (int j = 0; j < panel.mm.width; j++) {
				if (wanted[j][i] == null) {
					continue;
				}
				written++;
				allWritten &= Arrays.equals(panel.mm.regions.get(j, i).getFile(0), wanted[j][i]);
			}
		}
		check(written == cells && allWritten,
				"and EVERY populated cell holds its own assembled tilemap, not just the edited one ("
				+ written + " of " + cells + " cell(s))");
		boolean anyLeftModified = false;
		for (int i = 0; i < panel.mm.height; i++) {
			for (int j = 0; j < panel.mm.width; j++) {
				anyLeftModified |= panel.mm.regions.get(j, i) != null && panel.tilemaps[j][i].modified;
			}
		}
		check(!anyLeftModified, "with nothing still marked modified");
	}

	/**
	 * The same map, refused.
	 *
	 * <p>Discarding must clear the flags without writing, and cancelling must
	 * write nothing AND keep the flags, because the flags are what the editor
	 * still knows about a map that is not on disk. This is the arm that stops a
	 * zone switch, so its return value is asserted with the bytes.
	 */
	static void aMatrixSaveThatIsRefusedWritesNothing() throws Exception {
		System.out.println("--- a refused matrix save keeps the map and the editor where they were");
		TileMapPanel panel = multi();
		byte[] pristine = panel.mm.regions.get(0, 0).getFile(0);
		editATile(panel);

		List<String> said = Ui.record(JOptionPane.CANCEL_OPTION);
		boolean ok;
		try {
			ok = save(panel, true);
		} finally {
			Ui.stopRecording();
		}
		check(!ok, "cancelling a matrix save refuses, which stops the zone switch");
		check(Arrays.equals(panel.mm.regions.get(0, 0).getFile(0), pristine), "with nothing written");
		check(panel.tilemaps[0][0].modified, "and the edit still marked modified");

		said = Ui.record(JOptionPane.NO_OPTION);
		try {
			ok = save(panel, true);
		} finally {
			Ui.stopRecording();
		}
		check(ok, "discarding lets the editor carry on");
		check(Arrays.equals(panel.mm.regions.get(0, 0).getFile(0), pristine),
				"and still writes nothing - a discard is not a save");
		check(!panel.tilemaps[0][0].modified, "but is remembered, so it is not asked again");

		//a matrix with no cells is not a reason to stop anything
		panel.mm = null;
		check(saveMatrixOf(panel, true), "a panel with no matrix at all saves trivially");
	}

	/**
	 * Unloading has to leave the placeholder, not half of a map.
	 *
	 * <p>Every field the renderers and the entity editors index into is cleared
	 * here, and the reason each one matters is the same: a stale array outlives
	 * the zone it came from, so the next read is either the previous map's data
	 * shown as the current one, or an index into an array that is now the wrong
	 * shape. {@code mode} goes back to SINGLE for the same reason - left on
	 * MULTI, the next save walks a null matrix.
	 */
	static void unloadingLeavesNothingOfTheOldMap() throws Exception {
		System.out.println("--- unload leaves nothing of the map that was open");
		TileMapPanel panel = multi();
		check(panel.loaded && panel.mm != null && panel.tilemaps != null
				&& panel.models != null && panel.colls != null,
				"the panel really is holding a map before the unload");
		check(panel.mode == TileMapPanel.ViewportMode.MULTI, "in multi-region mode");

		panel.unload();
		check(!panel.loaded, "unloaded: nothing claims to be open");
		check(panel.mm == null, "the map matrix is dropped");
		check(panel.tilemaps == null, "the tilemaps are dropped");
		check(panel.models == null, "the models are dropped");
		check(panel.tallgrass == null, "the tall grass is dropped");
		check(panel.colls == null, "the collisions are dropped, so no height read finds the old map");
		check(panel.mode == TileMapPanel.ViewportMode.SINGLE,
				"and the mode is back to single, so the next save does not walk a null matrix");
		check(panel.getRegionForTile(0, 0) == null,
				"and a region lookup answers null rather than throwing");
		check(save(panel, true), "and saving an unloaded panel is trivially fine");
	}

	/**
	 * A region's tall grass is read in one place, and both loads go through it (census M.60). The matrix load and the
	 * loose-map load each read subfile 5 themselves; the author's own length fix landed on one, and the loose copy
	 * bound no textures, placed nothing and made no buffers.
	 */
	static void theTallGrassIsReadInOnePlace() throws Exception {
		System.out.println("--- a region's tall grass is read in one place (census M.60)");
		java.util.regex.Pattern grass = java.util.regex.Pattern.compile("\\.getFile\\(\\s*5\\s*\\)");
		check(grass.matcher("byte[] tg = mm.regions.get(j, i).getFile(5);").find() && !grass.matcher("x.getFile(15)").find(),
				"control: the scan sees subfile 5 read, and not subfile 15");
		java.util.Map<String, Integer> readers = new java.util.TreeMap<>();
		for (java.io.File f : CollisionReadTest.javaFiles(new java.io.File("src/ctrmap"), new java.util.ArrayList<>())) {
			if (f.getPath().contains(java.io.File.separator + "tests" + java.io.File.separator)) {
				continue;
			}
			java.util.regex.Matcher m = grass.matcher(SourceSeamTest.stripComments(new String(
					java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8)));
			while (m.find()) {
				readers.merge(f.getName(), 1, Integer::sum);
			}
		}
		String panel = SourceSeamTest.stripComments(new String(java.nio.file.Files.readAllBytes(
				new java.io.File("src/ctrmap/humaninterface/TileMapPanel.java").toPath()), java.nio.charset.StandardCharsets.UTF_8));
		String one = CollisionReadTest.body(panel, "static BCHFile tallGrassOf(");
		check(readers.toString().equals("{TileMapPanel.java=1}") && one != null && grass.matcher(one).find(),
				"subfile 5 is read once, in tallGrassOf: " + readers);
		String loose = CollisionReadTest.body(panel, "public void loadTileMap(");
		String matrix = CollisionReadTest.body(panel, "public static Regions readRegions(");
		check(loose != null && loose.contains("tallGrassOf(") && matrix != null && matrix.contains("tallGrassOf("),
				"and the loose-map load and the matrix load both go through it");
	}

	/**
	 * A region model the panel stops drawing has its GPU buffers freed, not stranded (census M.95, M.96). GL objects
	 * are not the garbage collector's; every zone load, unload and live repaint of a region dropped the models it
	 * replaced, a VBO and a texture per mesh, until the 3D view went black.
	 */
	static void aDroppedRegionModelIsFreedNotLeaked() throws Exception {
		System.out.println("--- a region model the World Editor stops drawing is freed, not leaked (census M.95, M.96)");
		TileMapPanel panel = new TileMapPanel(new LoadedZone(), TOOLS, SCENE, new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(TOOLS, UndoHistoryTest.overOneNumber(new int[]{0})), null);
		java.lang.reflect.Field draw = TileMapPanel.class.getDeclaredField("draw");
		draw.setAccessible(true);
		draw.set(panel, new RenderLoopTest.CountingRenderer());
		ctrmap.formats.h3d.model.H3DModel model = (ctrmap.formats.h3d.model.H3DModel) RenderLoopTest.bare(ctrmap.formats.h3d.model.H3DModel.class);
		model.meshes = new java.util.ArrayList<>();
		RenderLoopTest.CountingMesh mesh = new RenderLoopTest.CountingMesh();
		model.meshes.add(mesh);
		ctrmap.formats.h3d.BCHFile region = (ctrmap.formats.h3d.BCHFile) RenderLoopTest.bare(ctrmap.formats.h3d.BCHFile.class);
		region.models = new java.util.ArrayList<>();
		region.models.add(model);
		panel.models = new ctrmap.formats.h3d.BCHFile[][]{{region}};
		panel.tallgrass = new ctrmap.formats.h3d.BCHFile[1][1];
		java.lang.reflect.Method waiting = TileMapPanel.class.getDeclaredMethod("retiredWaiting");
		waiting.setAccessible(true);
		check((Integer) waiting.invoke(panel) == 0, "control: nothing is waiting to be freed before anything is dropped");
		panel.unload();
		check((Integer) waiting.invoke(panel) == 1, "an unload retires the region model it drops: " + waiting.invoke(panel));
		panel.renderCM3D(null);
		check(mesh.destroys == 1 && (Integer) waiting.invoke(panel) == 0,
				"and the next frame frees its buffers, though there is nothing left to draw (" + mesh.destroys + " freed)");
		//and nothing writes the panel's grids around the two setters that retire what they replace
		String src = SourceSeamTest.stripComments(new String(java.nio.file.Files.readAllBytes(
				new java.io.File("src/ctrmap/humaninterface/TileMapPanel.java").toPath()), java.nio.charset.StandardCharsets.UTF_8));
		for (String owned : new String[]{"public static final class Regions {", "private void setRegionModels(", "private void setRegionModel("}) {
			String body = CollisionReadTest.body(src, owned);
			check(body != null, "control: " + owned + " is found");
			src = body == null ? src : src.replace(body, "");
		}
		java.util.regex.Matcher write = java.util.regex.Pattern.compile(
				"(?<![\\w.])(?:this\\.)?(?:models|tallgrass)\\s*(?:\\[[^\\]]*\\]\\s*)*=(?!=)").matcher(src);
		java.util.List<String> around = new java.util.ArrayList<>();
		while (write.find()) {
			around.add(write.group().trim());
		}
		check(java.util.regex.Pattern.compile("(?<![\\w.])(?:this\\.)?(?:models|tallgrass)\\s*(?:\\[[^\\]]*\\]\\s*)*=(?!=)")
				.matcher("models[cellX][cellY] = bch;").find(), "control: the scan sees a cell of the grid written");
		check(around.isEmpty(), "the panel's model grids are written only through the setters that retire what they replace: " + around);
	}

	/**
	 * Unloading drops the undo history with the map it belongs to.
	 *
	 * <p>{@code loadMatrix} clears it, and says why in its own first line - "a
	 * different zone's tilemaps - old history is invalid". It was the only site in
	 * that file that did, so the ways a map stops being open THROUGH
	 * {@link TileMapPanel#unload()} did not: the workspace repoint and Options &gt;
	 * Clean workspace both reach it through {@code CtrmapMainframe.unloadEditors},
	 * and so does a matrix that failed to load, through {@code awaitLoad}. The
	 * stacks outlived the tilemaps they are over. The World Editor's Undo button
	 * follows {@code TileUndo.canUndo()} through a listener, so it stayed enabled
	 * with no map open, and pressing it called {@code setTileData} on detached
	 * regions and then asked for a redraw that {@code scaleImage} refuses because
	 * {@code loaded} is false - an edit that went somewhere nothing shows and
	 * nothing saves, under a button that reported success.
	 *
	 * <p>NOT asserted here, because it is not fixed: {@code loadTileMap} (File &gt;
	 * Open GR Mapfile) replaces the open map without coming through
	 * {@code unload()} and clears neither this history nor {@code Selector}. That
	 * sibling is reported, not taken.
	 *
	 * <p>Needs no game: what is asserted is that the history GOES, and a region of
	 * unwalkable tiles built here is a region the history can be over.
	 */
	static void unloadingDropsTheUndoHistoryWithTheMap() {
		System.out.println("--- unloading drops the undo history with the map it belongs to");
		TileMapPanel panel = new TileMapPanel(new LoadedZone(), TOOLS, SCENE, new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(TOOLS, UndoHistoryTest.overOneNumber(new int[]{0})), null);
		Tilemap region = new Tilemap(null, 40, 40, null);
		panel.tilemaps = new Tilemap[][]{{region}};
		panel.loaded = true;
		ctrmap.humaninterface.UndoHistory history = historyOver(panel);
		byte[] after = new byte[]{1, 2, 3, 4};
		region.setTileData(1, 1, after);
		history.settle();
		check(history.canUndo(),
				"fixture: an edit to the open map leaves an undo step behind it");

		ctrmap.humaninterface.Selector.hilightTileX = 12;
		ctrmap.humaninterface.Selector.hilightTileY = 13;
		ctrmap.humaninterface.Selector.selTileX = 14;
		ctrmap.humaninterface.Selector.selTileY = 15;
		panel.unload();
		check(panel.tilemaps == null, "the unload drops the tilemaps that step is over");
		check(!history.canUndo(),
				"and the undo history goes with them, so nothing offers to take back an edit to a map that is no longer open");
		//census M.18: the loads cleared the pick alone and unload neither - the cursor named tiles of a map not open
		check(ctrmap.humaninterface.Selector.hilightTileX == -1 && ctrmap.humaninterface.Selector.hilightTileY == -1
				&& ctrmap.humaninterface.Selector.selTileX == -1 && ctrmap.humaninterface.Selector.selTileY == -1,
				"and the whole tile cursor goes too, the tile under the pointer with the picked one");
	}

	// ---- lookups and geometry ----------------------------------------------

	/**
	 * Which region a tile belongs to, by tile number.
	 *
	 * <p>40 tiles to a region, so the arithmetic is integer division and the
	 * cell is [x/40][y/40] - x indexes the FIRST dimension. The tools that
	 * place things (the geometry editor, the Map Builder) ask this to find the
	 * container to write into, so a transposed answer edits the wrong region of
	 * a map that is not square, silently, because both regions are valid.
	 */
	static void aRegionIsPickedByTileNumber() {
		System.out.println("--- a tile number picks its region");
		TileMapPanel panel = new TileMapPanel(new LoadedZone(), TOOLS, SCENE, new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(TOOLS, UndoHistoryTest.overOneNumber(new int[]{0})), null);
		check(panel.getRegionForTile(0, 0) == null,
				"with no map open the lookup answers null rather than throwing");

		Tilemap[][] grid = new Tilemap[3][2]; //3 regions wide, 2 tall
		panel.tilemaps = grid;
		check(panel.getRegionForTile(0, 0) == grid[0][0], "tile (0,0) is the top-left region");
		check(panel.getRegionForTile(39, 39) == grid[0][0], "the last tile of that region is still in it");
		check(panel.getRegionForTile(40, 0) == grid[1][0], "tile (40,0) is one region EAST, not south");
		check(panel.getRegionForTile(0, 40) == grid[0][1], "tile (0,40) is one region SOUTH");
		check(panel.getRegionForTile(119, 79) == grid[2][1], "the far corner is the last region");
	}

	/**
	 * The panel's own height lookup reads the panel's own collisions.
	 *
	 * <p>The bounds arithmetic is pinned by NpcEntityGuardsTest against the
	 * static form. What is pinned here is the one line that binds the instance
	 * to it: the NPC tool drags an entity, asks the panel for the ground height
	 * and writes the answer into the file, so an instance method reading
	 * anything but this panel's {@code colls} gives an altitude belonging to
	 * another map.
	 */
	static void theHeightLookupReadsThePanelsOwnCollisions() {
		System.out.println("--- the panel's height lookup reads the panel's own collisions");
		TileMapPanel panel = new TileMapPanel(new LoadedZone(), TOOLS, SCENE, new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(TOOLS, UndoHistoryTest.overOneNumber(new int[]{0})), null);
		panel.colls = NpcEntityGuardsTest.grounded(2, 4); //2 wide, 4 tall, each a blank collision at 0
		check(panel.getHeightAtWorldLoc(100f, 3 * 720f + 10f) == 0f,
				"a position inside this panel's 2x4 matrix reads its cell's ground, 0");
		check(Float.isNaN(panel.getHeightAtWorldLoc(2 * 720f, 100f)),
				"and one past its width is NaN, which is the caller's cue to leave the altitude alone");
		panel.colls = new GRCollisionFile[1][1];
		check(Float.isNaN(panel.getHeightAtWorldLoc(100f, 3 * 720f + 10f)),
				"and the same position over a 1x1 matrix is now out of range,"
				+ " so the answer really came from this panel's collisions");
	}

	/**
	 * Opening a map points the 3D camera at it - and the two ways of doing that
	 * are NOT the same call.
	 *
	 * <p>WHY THIS IS WORTH A SECTION. Loading a single region and loading a
	 * matrix both aim the camera at the new map with almost-identical
	 * assignments, and this asserts the caller still says WHICH: behind a boolean
	 * the two would be one call with a flag, and the size the matrix framing
	 * carries would have nowhere to go.
	 *
	 * <p>RETARGETED, NOT DELETED. This section was written to hold an asymmetry
	 * open - the matrix path zeroed the yaw and the single-region path did not,
	 * so a loose GR map opened at the angle the last one was left at. That was
	 * decided and fixed; both zero it now, and MainframeShapeTest holds the five
	 * assignments themselves, which need the window's source to read because
	 * there is no headless JOGL panel to write them to. What is left here is the
	 * thing this double can actually see: two calls, and which one was made.
	 */
	static void thetwoWaysOfPointingTheCameraStayTwo() {
		System.out.println("--- the two ways of pointing the 3D camera are not the same call");
		SCENE.reset();
		SCENE.frameSingleRegion();
		SCENE.frameMatrix(4, 3);
		check(SCENE.framings.size() == 2 && !SCENE.framings.get(0).equals(SCENE.framings.get(1)),
			"they are two distinct calls, not one with an argument: " + SCENE.framings);
		check(SCENE.framings.get(1).equals("matrix 4x3"),
			"and the matrix one carries the size it is framing: " + SCENE.framings.get(1));
		SCENE.reset();
	}

	/**
	 * Where the viewport's centre is, in raw pixels, world units and tiles.
	 *
	 * <p>This is where the NPC, warp and trigger tools put a NEW entity: "Add"
	 * places it at the centre of what the user is looking at. Wrong, and the
	 * object lands off screen in a corner of the map, which reads as the button
	 * having done nothing at all - the record is created, so the user presses
	 * Add again.
	 *
	 * <p>Three conversions of one number, each with its own scale: raw pixels,
	 * then world units at 720 per 400 pixels divided by the zoom, then tiles at
	 * the image's own pixels-per-tile. They are asserted against the arithmetic
	 * spelled out rather than against constants, so the check says which
	 * conversion moved if one of them does.
	 */
	static void theViewportCentreIsWhereTheScrollBarsSay() {
		System.out.println("--- the viewport centre, in pixels, world units and tiles");
		//the scroll pane is HANDED to the panel now rather than planted on the
		//window, so it has to be built before the panel and given to it - which is
		//also the only way the two can be the same object
		JPanel view = new JPanel();
		view.setPreferredSize(new Dimension(2000, 2000));
		JScrollPane sp = new JScrollPane(view);
		sp.getViewport().setSize(300, 200);
		sp.getViewport().setViewPosition(new Point(640, 480));
		TileMapPanel panel = new TileMapPanel(new LoadedZone(), TOOLS, SCENE, sp,
			new ctrmap.humaninterface.CollEditPanel(TOOLS, UndoHistoryTest.overOneNumber(new int[]{0})), null);

		panel.height = 2;                       //2 regions tall
		panel.tilemapScale = 0.5d;
		panel.tilemapScaledImage = new java.awt.image.BufferedImage(
				400, 400, java.awt.image.BufferedImage.TYPE_INT_RGB);

		Point pos = sp.getViewport().getViewPosition();
		int imgStartX = (panel.getWidth() - 400) / 2;
		int imgStartY = (panel.getHeight() - 400) / 2;
		int rawX = pos.x - imgStartX + 300 / 2;
		int rawY = pos.y - imgStartY + 200 / 2;
		check(sp.getViewport().getViewPosition().equals(new Point(640, 480)),
				"the scroll position the checks are built on really took (" + pos.x + "," + pos.y + ")");

		Point raw = panel.getRawAtViewportCentre();
		check(raw.equals(new Point(rawX, rawY)),
				"the raw centre is the scroll position plus half the viewport, less the image's"
				+ " offset inside the panel: expected (" + rawX + "," + rawY + "), got " + raw);

		Point world = panel.getWorldLocAtViewportCentre();
		Point wantWorld = new Point((int) Math.round(raw.x / 400d * 720d / panel.tilemapScale),
				(int) Math.round(raw.y / 400d * 720d / panel.tilemapScale));
		check(world.equals(wantWorld),
				"the world position is that in 720-per-region units, divided by the zoom: expected "
				+ wantWorld + ", got " + world);

		Point tile = panel.getTileAtViewportCentre();
		double perRegion = 400 / (double) panel.height;
		Point wantTile = new Point((int) Math.round(raw.x / perRegion), (int) Math.round(raw.y / perRegion));
		check(tile.equals(wantTile),
				"and the tile is the raw centre over the image's own pixels-per-tile: expected "
				+ wantTile + ", got " + tile);

		//the zoom really is in the world conversion and not in the tile one
		panel.tilemapScale = 1.0d;
		check(!panel.getWorldLocAtViewportCentre().equals(world),
				"changing the zoom moves the world position");
		check(panel.getTileAtViewportCentre().equals(tile),
				"and leaves the tile position alone, because that one reads the scaled image");
	}

	/**
	 * Scaling refuses what it cannot draw, rather than trying.
	 *
	 * <p>Both scaling paths are guarded by {@code loaded && scale > 0.05 &&
	 * scale <= 1}, and the guard is the only thing between them and a
	 * {@code createCompatibleImage} of a negative or absurd size. The bounds
	 * are asserted at the edges because that is where an inverted or shifted
	 * comparison shows: 1.0 is allowed and anything above it is not.
	 *
	 * <p>What the guard protects is not reachable here - the drawing behind it
	 * needs a screen - so only the refusals are driven, and the zoom is the
	 * observable: a refused scale must not have been remembered.
	 */
	static void scalingRefusesWhatItCannotDraw() {
		System.out.println("--- scaling refuses a zoom it cannot draw, and does not remember it");
		TileMapPanel panel = new TileMapPanel(new LoadedZone(), TOOLS, SCENE, new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(TOOLS, UndoHistoryTest.overOneNumber(new int[]{0})), null);
		panel.tilemapScale = 0.25d;

		panel.loaded = false;
		panel.scaleImage(0.5d);
		check(panel.tilemapScale == 0.25d, "with no map open a scale request is ignored");
		panel.perfScale(0.5d, 0, 0);
		check(panel.tilemapScale == 0.25d, "and so is a per-region one");

		panel.loaded = true;
		panel.scaleImage(0.05d);
		check(panel.tilemapScale == 0.25d, "a zoom at the lower bound is refused (0.05 is not > 0.05)");
		panel.scaleImage(0d);
		check(panel.tilemapScale == 0.25d, "and so is zero");
		panel.scaleImage(-1d);
		check(panel.tilemapScale == 0.25d, "and a negative one");
		panel.scaleImage(1.5d);
		check(panel.tilemapScale == 0.25d, "and anything past 1, which is full size");
		panel.perfScale(2d, 0, 0);
		check(panel.tilemapScale == 0.25d, "the per-region path holds the same bounds");
		Skip.section("an ACCEPTED scale renders through"
				+ " GraphicsEnvironment.getDefaultScreenDevice, which needs a display");
	}

	/**
	 * A stale live-refresh must be dropped, not written somewhere else.
	 *
	 * <p>The Map Builder regenerates a region on a worker and hands the result
	 * back here; by the time it arrives the user may have loaded another zone,
	 * so the scene it was computed against is gone or a different shape.
	 * Without the bounds test the write lands in whatever array is there now,
	 * which is another map's region.
	 *
	 * <p>Only the refusals are driven: accepting one ends in {@code
	 * makeAllBOs}, which builds GL buffer objects.
	 */
	static void aStaleReloadIsIgnoredRatherThanWritten() {
		System.out.println("--- a stale region refresh is dropped rather than written into another map");
		TileMapPanel panel = new TileMapPanel(new LoadedZone(), TOOLS, SCENE, new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(TOOLS, UndoHistoryTest.overOneNumber(new int[]{0})), null);
		byte[] notEvenAModel = new byte[]{'B', 'C', 'H'};

		panel.models = null;
		panel.reloadRegionModel(0, 0, notEvenAModel);
		check(panel.models == null, "a refresh arriving after the scene was torn down is dropped");

		ctrmap.formats.h3d.BCHFile[][] models = new ctrmap.formats.h3d.BCHFile[2][2];
		panel.models = models;
		panel.reloadRegionModel(-1, 0, notEvenAModel);
		panel.reloadRegionModel(0, -1, notEvenAModel);
		panel.reloadRegionModel(2, 0, notEvenAModel);
		panel.reloadRegionModel(0, 2, notEvenAModel);
		boolean untouched = true;
		for (int x = 0; x < 2; x++) {
			for (int y = 0; y < 2; y++) {
				untouched &= models[x][y] == null;
			}
		}
		check(untouched, "and a cell outside the scene it was computed for writes nothing, on either axis");
		Skip.section("an ACCEPTED region refresh ends in makeAllBOs, which needs a GL context");
	}

	// ---- plumbing ----------------------------------------------------------

	/** A panel holding one region, the way loadTileMap leaves it. */
	static TileMapPanel single(int regionId) throws Exception {
		TileMapPanel panel = new TileMapPanel(new LoadedZone(), TOOLS, SCENE, new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(TOOLS, UndoHistoryTest.overOneNumber(new int[]{0})), null);
		GR gr = new GR(Workspace.getWorkspaceFile(ArchiveType.FIELD_DATA, regionId), Workspace.session());
		panel.mode = TileMapPanel.ViewportMode.SINGLE;
		panel.mainGR = gr;
		panel.tilemaps = new Tilemap[1][1];
		panel.tilemaps[0][0] = new Tilemap(gr);
		panel.width = 40;
		panel.height = 40;
		panel.loaded = true;
		return panel;
	}

	/**
	 * The 3D animator finds nothing to draw before a zone is open, and after
	 * one is closed.
	 *
	 * <p>The renderer starts a 60fps animator in its constructor and draws every
	 * registered renderable as soon as the WORKSPACE is valid - a workspace, not
	 * a zone - and this panel is registered at startup rather than on load. With
	 * the 3D view toggled on before any zone is picked, {@code renderCM3D}
	 * arrived with models null and threw sixty times a second on the animator
	 * thread, where nothing but stderr could see it. {@code unload()} leaves
	 * exactly that state behind, so a matrix that failed to load turned the 3D
	 * view into the same per-frame throw until another zone opened.
	 *
	 * <p>Its two sibling overrides have always opened with a test of this shape.
	 * The one that actually draws was the only one without.
	 */
	static void theAnimatorFindsNothingToDrawBeforeAZoneOpens() {
		System.out.println("--- the 3D renderer draws nothing before a zone is open");
		TileMapPanel panel = new TileMapPanel(new LoadedZone(), TOOLS, SCENE,
			new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(TOOLS, UndoHistoryTest.overOneNumber(new int[]{0})), null);
		check(panel.models == null && !panel.loaded, "a fresh panel holds no models");
		for (String when : new String[]{"fresh", "after unload"}) {
			Throwable thrown = null;
			try {
				panel.renderCM3D(null);
				panel.uploadBuffers(null);
				panel.deleteGLInstanceBuffers(null);
			} catch (Throwable t) {
				thrown = t;
			}
			check(thrown == null, "a frame drawn " + when + " does not throw: " + thrown);
			panel.unload();
		}
		//and the half-loaded state the worker passes through: MULTI with no matrix
		panel.mode = TileMapPanel.ViewportMode.MULTI;
		panel.models = new ctrmap.formats.h3d.BCHFile[1][1];
		panel.tallgrass = new ctrmap.formats.h3d.BCHFile[1][1];
		panel.mm = null;
		Throwable gap = null;
		try {
			panel.renderCM3D(null);
		} catch (Throwable t) {
			gap = t;
		}
		check(gap == null, "nor one drawn in the gap where the mode is MULTI and the matrix"
			+ " is not assigned yet: " + gap);
	}

	/** The panel's Save as the application makes it: on the event thread, where the progress dialog it
	 * shows belongs (F220). */
	static boolean save(TileMapPanel panel, boolean ask) throws Exception {
		return Edt.call(() -> panel.saveTileMap(ask));
	}

	/** The panel's matrix Save, on the event thread for the same reason. */
	static boolean saveMatrixOf(TileMapPanel panel, boolean ask) throws Exception {
		return Edt.call(() -> panel.saveMatrix(ask));
	}

	/**
	 * What {@code tileColors()} answers. Reflection because the method is
	 * package-private in {@code ctrmap.humaninterface} and every suite lives in
	 * {@code ctrmap.tests} - widening it so a test can see it would make the
	 * palette part of the panel's public surface, which is the opposite of what
	 * this campaign is for.
	 */
	static ctrmap.formats.tilemap.Tilemap.TileColors colours(TileMapPanel panel) {
		try {
			java.lang.reflect.Method m = TileMapPanel.class.getDeclaredMethod("tileColors");
			m.setAccessible(true);
			return (ctrmap.formats.tilemap.Tilemap.TileColors) m.invoke(panel);
		} catch (Exception ex) {
			throw new IllegalStateException("no tileColors() on TileMapPanel any more - this"
				+ " check has rotted and must be rewritten, not deleted", ex);
		}
	}

	/**
	 * The colours a region is painted in follow the tileset, rather than the one
	 * it happened to be loaded under.
	 *
	 * <p>{@code Tilemap} captures what it is handed into a final field and paints
	 * from it forever, and {@code Workspace.getTileset()} returns a NEW tileset
	 * object on every call. So handing the OBJECT over meant that changing the
	 * tileset in Workspace settings - which assigns a new one to the tile form and
	 * then asks the map view to redraw - repainted every region in the palette it
	 * had captured at load. The user watched the progress dialog run and saw
	 * nothing change, and the new colours only arrived with the next zone load.
	 *
	 * <p>What the panel hands a region is therefore a live VIEW, and the identity
	 * below is the whole point: the same object keeps answering, and it answers
	 * out of whatever the tileset is now.
	 */
	static void theColoursARegionIsPaintedInFollowTheTileset() {
		System.out.println("--- the colours a region is painted in follow the tileset");
		final java.awt.Color[] palette = {java.awt.Color.RED};
		ctrmap.formats.tilemap.Tilemap.TileColors live = tile -> palette[0];
		TileMapPanel panel = new TileMapPanel(new LoadedZone(), TOOLS, SCENE,
			new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(TOOLS, UndoHistoryTest.overOneNumber(new int[]{0})), live);
		ctrmap.formats.tilemap.Tilemap.TileColors handedOut = colours(panel);
		check(handedOut == live, "the panel hands a region the view it was given, not a copy");
		check(handedOut.colorOf(7).equals(java.awt.Color.RED), "which answers out of the tileset");
		palette[0] = java.awt.Color.BLUE;
		check(handedOut.colorOf(7).equals(java.awt.Color.BLUE),
			"and keeps answering out of it after Workspace settings replaces it, which is"
			+ " what makes the repaint show the new palette rather than the old one");
		TileMapPanel headless = new TileMapPanel(new LoadedZone(), TOOLS, SCENE,
			new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(TOOLS, UndoHistoryTest.overOneNumber(new int[]{0})), null);
		check(colours(headless) == null,
			"and a panel with no palette hands out none, so a headless holder paints nothing");
	}

	/**
	 * A matrix that outgrew the panel's arrays writes only the cells it has.
	 *
	 * <p>Matrix Editor > Add column and Add row raise {@code mm.width} and
	 * {@code mm.height} on the SAME MapMatrix this panel holds, without touching
	 * {@code tilemaps}. (Since the resize handlers were corrected {@code mm.regions}
	 * DOES follow - it is grown with the grid it is indexed by - but the panel's own
	 * arrays still do not, which is what this section is about.) The scan that decides whether to
	 * ask already skips any cell outside tilemaps; the two loops that act on the
	 * answer walked the whole of the new width and height, so the first cell past
	 * the old edge threw - in the SAVE arm into the worker, which abandoned the
	 * save with some regions written and some not, and in the DISCARD arm out of
	 * saveTileMap on the event thread with nothing to catch it.
	 */
	static void aMatrixThatOutgrewItsArraysWritesOnlyTheCellsItHas() throws Exception {
		System.out.println("--- a matrix that outgrew the panel writes only the cells it has");
		for (boolean keep : new boolean[]{true, false}) {
			TileMapPanel panel = multiCell();
			if (panel == null) {
				check(false, "no retail matrix with 2-8 populated cells");
				return;
			}
			markOneModified(panel);
			int was = panel.tilemaps.length;
			panel.mm.width++;
			panel.mm.height++;
			check(panel.mm.width > was, "the matrix is now " + panel.mm.width + " wide over "
			+ was + " column(s) of tilemaps, which is what Add column leaves behind");
			List<String> said = ctrmap.Ui.record(keep ? javax.swing.JOptionPane.YES_OPTION
				: javax.swing.JOptionPane.NO_OPTION);
			Throwable thrown = null;
			boolean answered = false;
			try {
				answered = save(panel, true);
			} catch (Throwable t) {
				thrown = t;
			} finally {
				ctrmap.Ui.stopRecording();
			}
			check(thrown == null, (keep ? "saving" : "discarding")
				+ " an outgrown matrix does not throw: " + thrown);
			check(answered, "and answers yes, so the zone switch that asked may go on");
			check(said.size() == 1, "having asked exactly once: " + said);
		}
	}

	/**
	 * Opening a loose map drops the textures the previous zone was drawn with.
	 *
	 * <p>The two saved texture lists are captured in {@code loadMatrix} and were
	 * reset nowhere, so after "open a zone, then File > Open GR Mapfile" the
	 * panel reported no zone open while {@code getWorldTextures()} still handed
	 * out the OLD zone's decoded world textures. The three readers - the
	 * environment picker, the painter's textured preview and the building placer
	 * - cannot tell a stale list from a live one; they only know how to treat
	 * null as "no textures". Worse than the disagreement: the loose map's own
	 * model loads with no textures bound, and the geometry editor's live refresh
	 * would then bind the previous zone's onto it, so editing changed how it
	 * looked.
	 */
	static void openingALooseMapDropsThePreviousZonesTextures() throws Exception {
		System.out.println("--- opening a loose map drops the last zone's textures");
		TileMapPanel panel = multiCell();
		if (panel == null) {
			check(false, "no retail matrix with 2-8 populated cells");
			return;
		}
		List<ctrmap.formats.h3d.texturing.H3DTexture> world = new java.util.ArrayList<>();
		setField(panel, "savedWorldTextures", world);
		setField(panel, "savedPropTextures", new java.util.ArrayList<ctrmap.formats.h3d.texturing.H3DTexture>());
		check(panel.getWorldTextures() == world, "the panel is handing out a zone's textures");
		GR loose = firstPopulated(panel);
		ctrmap.humaninterface.UndoHistory history = historyOver(panel);
		ctrmap.humaninterface.Selector.selTileX = 500;
		ctrmap.humaninterface.Selector.selTileY = 500;
		ctrmap.humaninterface.Selector.hilightTileX = 500;   //and the hover, which the loads left (census M.18)
		ctrmap.humaninterface.Selector.hilightTileY = 500;
		editOneTile(panel);
		history.settle();
		check(history.canUndo() 
			&& ctrmap.humaninterface.Selector.selTileX != -1,
			"fixture: there is an undo step and a picked tile to lose");
		check(loose != null, "and there is a region container to open as a loose map");
		List<String> said = ctrmap.Ui.record(javax.swing.JOptionPane.NO_OPTION);
		try {
			panel.loadTileMap(loose);
		} catch (java.awt.HeadlessException needsAScreen) {
			//the LOADING half of loadTileMap puts a progress dialog up and cannot run
			//headless. Everything this section asserts happens BEFORE that line - the
			//release, the mode, and the two texture lists - so the state it leaves is
			//still the state under test, and swallowing only HeadlessException means
			//any other failure still comes out.
		} finally {
			ctrmap.Ui.stopRecording();
		}
		//the fixture paints a tile so there is an undo step to lose, which makes the
		//map edited - so this path asks first, exactly as it should, and this run
		//answers discard. That is the real gesture the defect lived in: open a zone,
		//paint, File > Open GR Mapfile.
		check(said.size() == 1 && said.get(0).startsWith("Save changes:"),
			"the edit to the map being replaced is offered before it is replaced: " + said);
		check(panel.getWorldTextures() == null,
			"after opening a loose map it hands out nothing rather than the last zone's: "
			+ panel.getWorldTextures());
		check(panel.mm == null && panel.mode == TileMapPanel.ViewportMode.SINGLE,
			"which is the same answer the rest of its state gives");
		//AND THE TWO OTHER THINGS MEASURED AGAINST THE MAP IT REPLACED. A matrix load
		//has dropped both since it was written; this path went through neither
		//loadMatrix nor unload, so it dropped neither, and "open a zone, paint a tile,
		//File > Open GR Mapfile" left the Undo button lit over a Tilemap the panel no
		//longer held.
		check(!history.canUndo(),
			"and the undo history of the map it replaced, so nothing offers to take back an"
			+ " edit to a map that is not open");
		check(ctrmap.humaninterface.Selector.selTileX == -1
			&& ctrmap.humaninterface.Selector.selTileY == -1,
			"and the picked tile, which named a cell of a matrix wider than the one region"
			+ " a loose map has");
		check(ctrmap.humaninterface.Selector.hilightTileX == -1 && ctrmap.humaninterface.Selector.hilightTileY == -1,
			"opening a loose map drops the tile under the pointer too, measured on the map it replaced (census M.18)");
		//and the collision editor is handed the loose region's collision - the one parse the view reads heights from -
		//when it reads (the 2026-10-15 shared sweep, TileMapPanel 393, negate-if, SURVIVED: nothing asked what the
		//collision editor held after a loose map opened). The handoff runs before the headless stop above.
		ctrmap.humaninterface.CollEditPanel edits = (ctrmap.humaninterface.CollEditPanel) field(panel, "collision");
		check(panel.colls != null && panel.colls[0][0] != null && panel.colls[0][0].unreadable == null
				&& edits.coll == panel.colls[0][0],
				"and the collision editor holds the loose region's collision, the one the view reads heights from: "
				+ (edits.coll == null ? "none" : edits.coll == panel.colls[0][0] ? "the same" : "another"));
	}

	/**
	 * Loading a map drops the tile picked on the one before it.
	 *
	 * <p>{@code Selector.selTileX/selTileY} are statics and nothing reset them
	 * on a load - {@code loadMatrix} cleared the tile UNDO history and left the
	 * cursor exactly where it was - so a tile picked on a wide matrix was still
	 * "selected" after a switch to a smaller one. The tile editor has two
	 * readers of that pair and only one was guarded: showTile asks
	 * {@code getRegionForTile} and labels the tile " - Void" when the answer is
	 * null, while showListModel dereferenced the same call inline. So picking a
	 * tile on a large zone, loading a smaller one and clicking any tile-category
	 * radio button threw on the event thread - ArrayIndexOutOfBounds when the
	 * stale coordinate named a region row the new map does not have, a
	 * NullPointerException when it named an empty cell. It also left the red
	 * picked-tile rectangle painted at the old map's coordinate.
	 */
	static void loadingAMapDropsTheTilePickedOnTheLastOne() throws Exception {
		System.out.println("--- loading a map drops the tile picked on the last one");
		TileMapPanel panel = multiCell();
		if (panel == null) {
			check(false, "no retail matrix with 2-8 populated cells");
			return;
		}
		int cells = Math.min(panel.mm.width, panel.mm.height);
		ctrmap.humaninterface.Selector.selTileX = 500;
		ctrmap.humaninterface.Selector.selTileY = 500;
		ctrmap.humaninterface.Selector.hilightTileX = 500;   //and the hover, which the loads left (census M.18)
		ctrmap.humaninterface.Selector.hilightTileY = 500;
		try {
			//ON THE EVENT THREAD, as the application loads a map: its progress dialog belongs there (F220)
			Edt.run(() -> panel.loadMatrix(panel.mm, null, null, null));
		} catch (RuntimeException needsAScreen) {
			//the loading half puts a progress dialog up and cannot run headless, and
			//awaitLoad rewraps that as IllegalStateException, so this catches the
			//RuntimeException rather than the HeadlessException underneath it. The
			//cursor is dropped in the two statements BEFORE any of that, which is the
			//whole point: it goes with the undo history, at the top, not somewhere
			//inside a worker that may never run.
			check(needsAScreen instanceof java.awt.HeadlessException
				|| String.valueOf(needsAScreen.getCause()).contains("Headless"),
				"the load stopped for want of a screen, not for anything else: " + needsAScreen);
		}
		check(ctrmap.humaninterface.Selector.hilightTileX == -1 && ctrmap.humaninterface.Selector.hilightTileY == -1,
			"loading a matrix drops the tile under the pointer too - a Fill pressed next anchored on the old map (census M.18)");
		check(ctrmap.humaninterface.Selector.selTileX == -1
			&& ctrmap.humaninterface.Selector.selTileY == -1,
			"the picked tile is dropped with the undo history it belongs to ("
			+ ctrmap.humaninterface.Selector.selTileX + ","
			+ ctrmap.humaninterface.Selector.selTileY + ")");
		//WHY 500 AND NOT ANY NUMBER. It is past the edge of this matrix in tiles -
		//40 tiles to a region - which is the situation the defect needed: a tile
		//picked on a wide map, still "selected" over a narrower one. Asserted
		//against the matrix rather than by asking the panel, because
		//getRegionForTile is NOT bounds-safe for a coordinate off the map: it
		//throws ArrayIndexOutOfBounds, which is exactly how the stale cursor used
		//to reach the user. Pinned here as a comment rather than as a check
		//because reproducing it needs a map that finished loading, and that needs
		//a screen.
		check(500 / 40 >= cells, "tile 500 is past this matrix, which is " + cells
			+ " region(s) across - so the cursor could not have survived the load");
	}

	/**
	 * The zone's history over a panel, told when the panel replaces its regions - as the window builds
	 * it (F209). It replaced a static tile history the panel cleared by hand.
	 */
	static ctrmap.humaninterface.UndoHistory historyOver(TileMapPanel panel) {
		LoadedZone lz = new LoadedZone();
		ctrmap.humaninterface.UndoHistory h = new ctrmap.humaninterface.UndoHistory(
				ctrmap.humaninterface.ZoneDocuments.scope(lz, () -> panel),
				ctrmap.humaninterface.ZoneDocuments.entities(lz, () -> {
				}));
		panel.useHistory(h, () -> {
		});
		h.settle();
		return h;
	}

	/** Edits one tile, so there is a history to drop once the edit is settled. */
	static void editOneTile(TileMapPanel panel) {
		for (Tilemap[] col : panel.tilemaps) {
			for (Tilemap tm : col) {
				if (tm == null) {
					continue;
				}
				byte[] was = tm.getTileData(1, 1).clone();
				byte[] now = was.clone();
				now[0] = (byte) (now[0] ^ 0x20);
				tm.setTileData(1, 1, now);
				return;
			}
		}
	}

	/** Marks the first tilemap the panel actually has as edited. */
	static void markOneModified(TileMapPanel panel) {
		for (Tilemap[] col : panel.tilemaps) {
			for (Tilemap tm : col) {
				if (tm != null) {
					tm.modified = true;
					return;
				}
			}
		}
	}

	/** The first region container the panel's matrix names, or null. */
	static GR firstPopulated(TileMapPanel panel) {
		for (int i = 0; i < panel.mm.height; i++) {
			for (int j = 0; j < panel.mm.width; j++) {
				if (panel.mm.regions.get(j, i) != null) {
					return panel.mm.regions.get(j, i);
				}
			}
		}
		return null;
	}

	/** Writes a private field, so a suite can put the panel in a state loadMatrix leaves. */
	static void setField(Object o, String name, Object value) throws Exception {
		java.lang.reflect.Field fl = o.getClass().getDeclaredField(name);
		fl.setAccessible(true);
		fl.set(o, value);
	}

	/** Reads a private field, so a suite can see what the panel handed on. */
	static Object field(Object o, String name) throws Exception {
		java.lang.reflect.Field fl = o.getClass().getDeclaredField(name);
		fl.setAccessible(true);
		return fl.get(o);
	}

	/** A panel holding the whole of zone 15's map, the way loadMatrix leaves it. */
	static TileMapPanel multi() throws Exception {
		ctrmap.formats.zone.Zone z = new ctrmap.formats.zone.Zone(
				new ctrmap.formats.containers.ZO(temp(Workspace.getArchive(ArchiveType.ZONE_DATA).getDecompressedEntry(ZONE)), Workspace.session()),
				Workspace.game());
		File mmFile = Workspace.getWorkspaceFile(ArchiveType.MAP_MATRIX, z.header.mapmatrixID);
		return over(new MapMatrix(new MM(mmFile, Workspace.session()), Workspace.session()));
	}

	/** A panel over a parsed map matrix, the way loadMatrix leaves one. */
	static TileMapPanel over(MapMatrix mm) throws Exception {
		TileMapPanel panel = new TileMapPanel(new LoadedZone(), TOOLS, SCENE, new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(TOOLS, UndoHistoryTest.overOneNumber(new int[]{0})), null);
		panel.mm = mm;
		panel.mode = TileMapPanel.ViewportMode.MULTI;
		panel.tilemaps = new Tilemap[mm.width][mm.height];
		panel.models = new ctrmap.formats.h3d.BCHFile[mm.width][mm.height];
		panel.tallgrass = new ctrmap.formats.h3d.BCHFile[mm.width][mm.height];
		panel.colls = new GRCollisionFile[mm.width][mm.height];
		for (int i = 0; i < mm.height; i++) {
			for (int j = 0; j < mm.width; j++) {
				if (mm.regions.get(j, i) != null) {
					panel.tilemaps[j][i] = new Tilemap(mm.regions.get(j, i));
				}
			}
		}
		panel.width = mm.width * 40;
		panel.height = mm.height * 40;
		panel.loaded = true;
		return panel;
	}

	/**
	 * The first retail map matrix with between 2 and 8 populated cells, so
	 * "asks once and writes every cell" is a claim about a real grid rather
	 * than about a single region. Bounded above because every populated cell
	 * opens a container and builds a tilemap.
	 */
	static TileMapPanel multiCell() throws Exception {
		int matrices = Workspace.getArchive(ArchiveType.MAP_MATRIX).length;
		for (int id = 0; id < matrices; id++) {
			File f = Workspace.getWorkspaceFile(ArchiveType.MAP_MATRIX, id);
			if (f == null || !f.isFile()) {
				continue;
			}
			MapMatrix mm = new MapMatrix(new MM(f, Workspace.session()), Workspace.session());
			if (mm.width * mm.height < 2 || mm.width * mm.height > 8) {
				continue;
			}
			TileMapPanel panel = over(mm);
			if (populated(panel) >= 2) {
				return panel;
			}
		}
		return null;
	}

	static int populated(TileMapPanel panel) {
		int n = 0;
		for (int i = 0; i < panel.mm.height; i++) {
			for (int j = 0; j < panel.mm.width; j++) {
				if (panel.mm.regions.get(j, i) != null) {
					n++;
				}
			}
		}
		return n;
	}

	/**
	 * Edits one tile of the panel's first region and returns the bytes a save
	 * must then write, so the assertion is against what the editor holds rather
	 * than against a literal.
	 */
	static byte[] editATile(TileMapPanel panel) {
		Tilemap tm = panel.tilemaps[0][0];
		byte[] was = tm.getTileData(3, 4);
		byte[] now = Arrays.copyOf(was, was.length);
		now[0] = (byte) (now[0] ^ 0x10); //a different tile, whatever it was
		tm.setTileData(3, 4, now);
		tm.modified = true;
		return tm.assembleTilemap();
	}

	static File temp(byte[] bytes) throws Exception {
		File f = File.createTempFile("ctrmap_tilemap", ".bin");
		f.deleteOnExit();
		java.nio.file.Files.write(f.toPath(), bytes);
		return f;
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}
