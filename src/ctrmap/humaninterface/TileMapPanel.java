package ctrmap.humaninterface;

import com.jogamp.opengl.DefaultGLCapabilitiesChooser;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.util.awt.AWTGLReadBufferUtil;
import ctrmap.CtrmapMainframe;
import ctrmap.LoadedZone;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.GridBagLayout;
import java.awt.image.BufferedImage;

import javax.swing.JLabel;
import javax.swing.JPanel;

import static ctrmap.CtrmapMainframe.*;
import ctrmap.formats.vectors.Vec3f;
import ctrmap.formats.tilemap.Tilemap;
import ctrmap.formats.containers.GR;
import ctrmap.formats.gfcollision.GRCollisionFile;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.model.H3DModel;
import ctrmap.formats.h3d.texturing.H3DTexture;
import ctrmap.formats.mapmatrix.MapMatrix;
import ctrmap.formats.propdata.ADPropRegistry;
import ctrmap.formats.propdata.GRPropData;
import java.awt.AlphaComposite;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Transparency;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingWorker;

/**
 * Originally for editing tilemaps, this has evolved far beyond its purpose.
 * Currently nests everything tied to the map matrix.
 */
public class TileMapPanel extends JPanel implements CM3DRenderable {

	private static final long serialVersionUID = 7357107275764622829L;
	public static final String PROP_REPAINT = "imageUpdated";
	private static final String ESC = "keyEscape";

	public ViewportMode mode = ViewportMode.SINGLE;

	public Tilemap[][] tilemaps;
	public BCHFile[][] models;
	public BCHFile[][] tallgrass;
	public GRCollisionFile[][] colls;
	public MapMatrix mm;
	public GR mainGR;
	//texture lists captured at load so an edited region model can be rebound live (geometry editor)
	private List<H3DTexture> savedWorldTextures;
	private List<H3DTexture> savedPropTextures;

	/** The loaded zone's decoded world textures (for the tile painter's textured preview), or null. */
	public List<H3DTexture> getWorldTextures() {
		return savedWorldTextures;
	}
	public BufferedImage tilemapImage;// = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
	public BufferedImage tilemapScaledImage = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
	public BufferedImage cm2dOverlayImage = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
	public double tilemapScale = 1.0d;
	public boolean loaded = false;
	private final JLabel placeholder = new JLabel("No map loaded");
	private Graphics g;
	public int width;
	public int height;

	private final GLProfile glp;
	private final GLCapabilities caps;
	private GLAutoDrawable CM2DDrawable;
	private BufferedImage CM2DTempImage;
	/** The 3D scene this view shares, handed in: what it draws and where its camera looks. */
	private final Scene3D scene;

	/**
	 * The scroll pane this view lives in, handed in AS ITSELF.
	 *
	 * <p>A narrower interface was considered and rejected for the same reason
	 * ToolHost.map() hands the map view whole: this uses six of its members - the
	 * viewport's position, width, height and size, plus revalidate and repaint -
	 * and naming a capability per member would be a longer way of writing
	 * JScrollPane.
	 */
	private final javax.swing.JScrollPane viewport;

	/**
	 * What colour a tile is drawn in, handed in. Null means "no picture", which
	 * is how a headless holder works, exactly as the null form did.
	 *
	 * <p>{@link Tilemap.TileColors} rather than a new interface: the region
	 * picture already declares this shape, and the map view was the only thing
	 * standing between it and the palette.
	 */
	private final Tilemap.TileColors colours;

	/**
	 * The editors this view tells about the map it has loaded, SET ONCE after
	 * both exist - see {@link MapEditors} for why it cannot be a constructor
	 * argument in either direction.
	 */
	private MapEditors editors;

	/** Tells this view which editors to inform. Once, by the window. */
	public void setEditors(MapEditors editors) {
		if (editors == null) {
			throw new IllegalArgumentException("the map view must be told which editors show what it loads");
		}
		this.editors = editors;
	}

	/** The collision editor drawn alongside this view, handed in. */
	private final CollEditPanel collision;

	public boolean update = true;

	@Override
	public void doSelectionLoop(MouseEvent e, Component parent, float[] mvMatrix, float[] projMatrix, int[] view, Vec3f cameraVec) {}

	public enum ViewportMode {
		SINGLE,
		MULTI
	}

	/** The zone owner this view was handed; opening a bare GR file lets its zone go. */
	private final LoadedZone loadedZone;

	/** Which tool the editor is holding, handed in: this class only asks. */
	private final ctrmap.humaninterface.tools.ToolSelection tools;

	public TileMapPanel(LoadedZone loadedZone, ctrmap.humaninterface.tools.ToolSelection tools,
			Scene3D scene, javax.swing.JScrollPane viewport, CollEditPanel collision,
			Tilemap.TileColors colours) {
		super();
		this.tools = tools;
		if (loadedZone == null) {
			throw new IllegalArgumentException("TileMapPanel must be handed a LoadedZone");
		}
		this.loadedZone = loadedZone;
		//AFTER the LoadedZone check: LoadedZoneTest asserts a null owner is refused
		//by a message naming "LoadedZone", and a refusal above would flip it.
		if (scene == null || viewport == null || collision == null) {
			throw new IllegalArgumentException("TileMapPanel must be handed the 3D scene, its"
				+ " viewport and the collision editor it draws alongside - scene=" + scene
				+ ", viewport=" + viewport + ", collision=" + collision);
		}
		this.scene = scene;
		this.viewport = viewport;
		this.collision = collision;
		this.colours = colours;   //may be null: a headless suite holds the data with no palette
		setLayout(new GridBagLayout());
		add(placeholder);
		g = tilemapScaledImage.getGraphics();
		glp = GLProfile.get(GLProfile.GL2);
		caps = new GLCapabilities(glp);
		caps.setHardwareAccelerated(true);
		caps.setDoubleBuffered(false);
		caps.setAlphaBits(8);
		caps.setRedBits(8);
		caps.setBlueBits(8);
		caps.setGreenBits(8);
		caps.setOnscreen(false);
		addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				if (CM2DDrawable != null) {
					scene.renderables().forEach((r) -> {
						r.deleteGLInstanceBuffers(CM2DDrawable.getGL().getGL2());
					});
					update = true;
				}
				GLDrawableFactory factory = GLDrawableFactory.getFactory(glp);
				CM2DDrawable = factory.createOffscreenAutoDrawable(factory.getDefaultDevice(), caps, new DefaultGLCapabilitiesChooser(), viewport.getViewport().getWidth(), viewport.getViewport().getHeight());
				CM2DDrawable.display();
				CM2DDrawable.getContext().makeCurrent();

				GL2 gl = CM2DDrawable.getGL().getGL2();

				gl.glShadeModel(GL2.GL_SMOOTH);
				gl.glClearColor(0f, 0f, 0f, 0f);
				gl.glClearDepth(1.0f);
				gl.glEnable(GL2.GL_TEXTURE_2D);
				gl.glEnable(GL2.GL_DEPTH_TEST);
				gl.glCullFace(GL2.GL_BACK);
				gl.glEnable(GL2.GL_CULL_FACE);
				gl.glDepthFunc(GL2.GL_LEQUAL);
				gl.glHint(GL2.GL_PERSPECTIVE_CORRECTION_HINT, GL2.GL_NICEST);
			}
		});
	}

	public void loadTileMap(GR file) {
		if (!saveTileMap(true)) {
			return;
		}
		//the map shown is no longer the open zone's: let the zone go so the
		//Zone tab's Save writes nothing, and leave the zone index and the
		//dropdown's selection as they were - a stale-state clear, not an unload
		//(unloadZone is that).
		//THE ENTITY EDITORS ARE CLEARED, below, through the editors seam. This
		//comment used to say they were left as they were, and they were not: the
		//NPC form alone was cleared, so the warp and trigger forms went on
		//editing the entities of the zone this line has just released, into a
		//ZoneEntities the zone save no longer reaches.
		loadedZone.release();
		mm = null;
		//AND THE TEXTURES THE PREVIOUS ZONE WAS DRAWN WITH GO WITH IT. These two lists
		//are captured in loadMatrix and were reset nowhere, so after "open a zone, then
		//File > Open GR Mapfile" this panel reported no zone open while
		//getWorldTextures() still handed out the OLD zone's decoded world textures -
		//two sources of the same truth disagreeing, and the three readers (the
		//environment picker, the tile painter's textured preview and the window's
		//building placer) cannot tell a stale list from a live one; they only know how
		//to treat null as "no textures", which is what this now gives them. Worse than
		//the disagreement: the loose GR's own model is loaded below with NO textures
		//bound at all, and reloadRegionModel - the geometry editor's live refresh -
		//would then bind the previous zone's onto it, so editing the map changed how it
		//looked. This method's own comment calls what it does a stale-state clear;
		//these two fields were the part of the state it did not clear.
		savedWorldTextures = null;
		savedPropTextures = null;
		//AND THE TWO THINGS MEASURED AGAINST THE MAP THAT IS BEING REPLACED, for the
		//reason the comment above gives about the textures: this is a stale-state
		//clear, and these were the rest of the state it did not clear. loadMatrix has
		//dropped both since it was written - the undo history because it is over
		//tilemaps that are about to go, the picked tile because it is a coordinate on
		//a map that is about to go - and this path replaces tilemaps[0][0] over a
		//different container without going through either loadMatrix or unload, so it
		//got neither. What that cost: after "open a zone, paint a tile, File > Open GR
		//Mapfile" the World Editor's Undo button was still lit - it follows
		//TileUndo.canUndo() through a listener - and pressing it called setTileData on
		//a Tilemap this panel no longer holds, so the edit went where nothing shows it
		//and nothing saves it while the button reported that it had worked. The picked
		//tile is the same shape: a loose region is one 40x40 cell, so a coordinate
		//from any matrix wider than one region is off it, and the tile inspector
		//indexes with it.
		TileUndo.clear();
		Selector.unfocus(this);
		mode = ViewportMode.SINGLE;
		width = 40;
		height = 40;
		remove(placeholder);
		tilemaps = new Tilemap[1][1];
		mainGR = file;
		models = new BCHFile[1][1];
		models[0][0] = new BCHFile(file.getFile(1));
		tallgrass = new BCHFile[1][1];
		byte[] tg = file.getFile(5);
		if (tg[0] == 'B' && tg[1] == 'C' && tg[2] == 'H') {
			BCHFile tgbch = new BCHFile(tg);
			if (!tgbch.models.isEmpty()) {
				H3DModel tgmdl = tgbch.models.get(0);
				tallgrass[0][0] = tgbch;
			}
		}
		colls = new GRCollisionFile[1][1];
		colls[0][0] = new GRCollisionFile(file);
		tilemaps[0][0] = new Tilemap(file, tileColors());
		tilemapImage = tilemaps[0][0].getImage();
		tilemapScaledImage = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().createCompatibleImage(400, 400);
		scaleImage(1);
		revalidate();
		loaded = true;
		loadProps(null, null);
		if (editors != null) {
			editors.clearEntities();
		}
		scene.frameSingleRegion();
	}

	public void loadProps(List<H3DTexture> propTextures, ADPropRegistry reg) {
		if (mode == ViewportMode.MULTI) {
			GRPropData comb = new GRPropData();
			for (int i = 0; i < mm.height; i++) {
				for (int j = 0; j < mm.width; j++) {
					if (mm.regions.get(j, i) != null) {
						comb.props.addAll(new GRPropData(mm.regions.get(j, i)).props);
					}
				}
			}
			if (editors != null) {
				editors.showProps(comb, reg, propTextures);
			}
		} else if (mode == ViewportMode.SINGLE) {
			if (editors != null) {
				editors.showLooseProps(mainGR);
			}
		}
	}

	/**
	 * Swaps one region cell's visual model for freshly edited bytes and rebinds
	 * textures/buffers exactly like the original load - the live-refresh hook of
	 * the geometry editor (the 3D view shows the edit immediately).
	 */
	public void reloadRegionModel(int cellX, int cellY, byte[] modelBytes) {
		reloadRegionModel(cellX, cellY, modelBytes, null);
	}

	/**
	 * As {@link #reloadRegionModel(int, int, byte[])}, additionally binding
	 * extra textures (e.g. a placed building's donor-area pack) - the Map
	 * Builder's live-preview path. The extra list only fills name matches, so
	 * it never disturbs the region's own bindings.
	 */
	public void reloadRegionModel(int cellX, int cellY, byte[] modelBytes, java.util.List<ctrmap.formats.h3d.texturing.H3DTexture> extraTextures) {
		//bounds-guard: a stale async caller (the Map Builder's regen worker)
		//must never write into a torn-down or reshaped scene
		if (models == null || cellX < 0 || cellY < 0 || cellX >= models.length || cellY >= models[cellX].length) {
			return;
		}
		BCHFile bch = new BCHFile(modelBytes);
		if (bch.models.isEmpty()) {
			return;
		}
		H3DModel model = bch.models.get(0);
		if (savedWorldTextures != null) {
			model.setMaterialTextures(savedWorldTextures);
		}
		if (savedPropTextures != null) {
			model.setMaterialTextures(savedPropTextures);
		}
		if (extraTextures != null && !extraTextures.isEmpty()) {
			model.setMaterialTextures(extraTextures);
		}
		if (mode == ViewportMode.MULTI) {
			model.worldLocX = cellX * 720f + 360f;
			model.worldLocZ = cellY * 720f + 360f;
		}
		model.makeAllBOs();
		models[cellX][cellY] = bch;
		scene.redraw();
		repaint();
	}

	public void unload() {
		//THE UNDO HISTORY IS OVER THE TILEMAPS DROPPED BELOW and goes with them, for
		//exactly the reason loadMatrix gives where it clears the same stacks: they belong
		//to a map that is no longer open. loadMatrix was the ONLY site in this file that
		//cleared them, so the ways a map stops being open THROUGH HERE - the workspace
		//repoint and Options > Clean workspace, both via CtrmapMainframe.unloadEditors,
		//and a matrix that failed to load, via awaitLoad - left the World Editor's Undo
		//button enabled over a panel whose tilemaps are null. The button follows
		//TileUndo.canUndo() through a listener, so it stayed lit; pressing it called
		//setTileData on Tilemap objects this panel no longer holds and then asked for the
		//redraw that scaleImage refuses because "loaded" is false. The edit went somewhere
		//nothing shows and nothing saves, and the button reported that it had worked.
		//
		//THE SIBLING SITE IS COVERED TOO, and not by this line: loadTileMap (File >
		//Open GR Mapfile) replaces tilemaps[0][0] over a different container without
		//coming through here, so it clears the history and the picked tile itself,
		//beside the textures it already dropped for the same reason.
		TileUndo.clear();
		mode = ViewportMode.SINGLE;
		loaded = false;
		add(placeholder);
		setPreferredSize(placeholder.getPreferredSize());
		invalidate();
		revalidate();
		mm = null;
		tilemaps = null;
		models = null;
		tallgrass = null;
		colls = null;
	}

	public boolean saveMatrix(boolean dialog) {
		if (mm != null) {
			boolean broken = false;
			for (int i = 0; i < mm.height & !broken; i++) {
				for (int j = 0; j < mm.width; j++) {
					if (j >= tilemaps.length || i >= tilemaps[j].length || tilemaps[j][i] == null) {
						continue;
					}
					if (tilemaps[j][i].modified) {
						switch (ctrmap.Ui.askToKeep(dialog, "Region data")) {
							case SAVE:
								LoadingDialog progress = LoadingDialog.makeDialog("Saving matrix");
								SwingWorker worker = new SwingWorker() {
									@Override
									protected void done() {
										progress.close();
										try {
											get(); //without this, a region that failed to write closes the dialog and
											//the zone switch goes on without it
										} catch (Exception ex) {
											regionSaveFailed(ex.getCause() != null ? ex.getCause() : ex);
										}
									}

									@Override
									protected Object doInBackground() {
										for (int i = 0; i < mm.height; i++) {
											for (int j = 0; j < mm.width; j++) {
												//THE MATRIX CAN OUTGROW THESE ARRAYS, and the scan that
												//got us here already knows it - it skips any cell outside
												//tilemaps. This loop did not, and walked the whole of
												//mm.width/mm.height instead. Matrix Editor > Add column and
												//Add row raise mm.width/mm.height on the SAME MapMatrix this
												//panel holds, and the arrays here do not follow. (mm.regions
												//follows now - the four resize handlers grow it - but tilemaps
												//still does not.) The first cell past the old edge threw
												//IndexOutOfBounds out of
												//mm.regions.get() - here into the worker, which abandoned the
												//save with some regions written and some not, and in the
												//DISCARD arm below straight out of saveMatrix on the event
												//thread with nothing to catch it. A cell the panel never
												//loaded a tilemap for has nothing to write back.
												if (j >= tilemaps.length || i >= tilemaps[j].length || tilemaps[j][i] == null) {
													continue;
												}
												if (mm.regions.get(j, i) == null) {
													continue;
												}
												mm.regions.get(j, i).storeFile(0, tilemaps[j][i].assembleTilemap());
												tilemaps[j][i].modified = false; //prevent save dialog popping up until changed again
												progress.setBarPercent((int) (((i * mm.width + j) / (float) (mm.width * mm.height)) * 100));
											}
										}
										return null;
									}
								};

								worker.execute();
								progress.showDialog();

								try {
									worker.get();
								} catch (InterruptedException | ExecutionException ex) {
									return false; //the save failed and done() has said so; leaving now would drop what did not write
								}
								return true; //save as normal
							case DISCARD:
								for (int k = 0; k < mm.height; k++) {
									for (int l = 0; l < mm.width; l++) {
										//the same bound as the save loop above, for the same reason: a
										//column the matrix editor added has no tilemap to unmark. This
										//arm is the worse of the two - it runs on the event thread, so
										//what it threw left saveMatrix altogether and the save simply
										//stopped, with a stack trace on stderr and the user told nothing.
										if (l >= tilemaps.length || k >= tilemaps[l].length || tilemaps[l][k] == null) {
											continue;
										}
										if (mm.regions.get(l, k) == null) {
											continue;
										}
										tilemaps[l][k].modified = false; //prevent save dialog popping up until changed again
									}
								}
								return true; //don't save, but don't interrupt the editor
							default:
								return false;//stop anything going on in the editor, cancel
						}
					}
				}
			}
		}
		return true; //no matrix, no fuss
	}

	public void loadMatrix(MapMatrix matrix, ADPropRegistry reg, List<H3DTexture> worldTextures, List<H3DTexture> propTextures) {
		TileUndo.clear(); //a different zone's tilemaps - old history is invalid
		//AND THE PICKED TILE IS INVALID FOR EXACTLY THE SAME REASON, so it goes with the
		//history. Nothing reset it before, so Selector.selTileX/selTileY kept naming a tile
		//of the map being replaced, and picking a tile on a large zone then loading a
		//smaller one threw on the EDT out of getRegionForTile.
		Selector.unfocus(this);
		LoadingDialog progress = LoadingDialog.makeDialog("Loading matrix");
		SwingWorker worker = new SwingWorker() {
			@Override
			protected void done() {
				progress.close();
				try {
					get(); //without this, a map that failed half-way closes the dialog and the
					//zone comes up over the previous map's regions
				} catch (Exception ex) {
					Logger.getLogger(TileMapPanel.class.getName()).log(Level.SEVERE, "loading matrix", ex.getCause() != null ? ex.getCause() : ex);
				}
			}

			@Override
			protected Object doInBackground() {
				loadRegions(matrix, reg, worldTextures, propTextures, progress);
				return null;
			}
		};
		worker.execute();
		progress.showDialog();
		awaitLoad(worker);
	}

	/**
	 * Assembles a matrix into this panel: every filled cell's tilemap, map model, tall
	 * grass and collision, placed at {@code cell * 720 + 360} and textured with the
	 * zone's own world and prop textures.
	 *
	 * <p>THE ONE BODY that turns a matrix into a picture, with two callers:
	 * {@link #loadMatrix} runs it behind a progress dialog after clearing what belonged
	 * to the previous zone, and the Zone Loader's preview runs it with a null progress
	 * on a panel of its own. A second implementation of this is what the preview used to
	 * be, and every defect the owner reported about it lived in the gap between the two.
	 *
	 * @param progress may be null - then there is nothing to report into, which is what
	 * a preview is: a zone nobody asked to open
	 */
	public void loadRegions(MapMatrix matrix, ADPropRegistry reg, List<H3DTexture> worldTextures,
			List<H3DTexture> propTextures, LoadingDialog progress) {
			mode = ViewportMode.MULTI;
			savedWorldTextures = worldTextures;
			savedPropTextures = propTextures;
			mm = matrix;
			width = mm.width * 40;
			height = mm.height * 40;
			tilemaps = new Tilemap[mm.width][mm.height];
			models = new BCHFile[mm.width][mm.height];
			tallgrass = new BCHFile[mm.width][mm.height];
			colls = new GRCollisionFile[mm.width][mm.height];
			collision.unload();
			for (int i = 0; i < mm.height; i++) {
				for (int j = 0; j < mm.width; j++) {
					if (mm.ids.get(j, i) != -1) {
						tilemaps[j][i] = new Tilemap(mm.regions.get(j, i), tileColors());
						byte[] tg = mm.regions.get(j, i).getFile(5);
						if (tg.length > 0 && tg[0] == 'B' && tg[1] == 'C' && tg[2] == 'H') {
							BCHFile tgbch = new BCHFile(tg);
							if (!tgbch.models.isEmpty()) {
								H3DModel tgmdl = tgbch.models.get(0);
								tgmdl.setMaterialTextures(worldTextures);
								//GR overworld map BCH files have just 1 model, the tall grass is entirely separate BCH
								tgmdl.worldLocX = j * 720f + 360f;
								tgmdl.worldLocZ = i * 720f + 360f;
								tgmdl.makeAllBOs();
								tallgrass[j][i] = tgbch;
							}
						}
						BCHFile bch = new BCHFile(mm.regions.get(j, i).getFile(1));
						if (!bch.models.isEmpty()) {
							H3DModel model = bch.models.get(0);
							if (worldTextures != null) {
								worldTextures.addAll(bch.textures);
								model.setMaterialTextures(worldTextures);
							}
							if (propTextures != null) {
								model.setMaterialTextures(propTextures);
							}
							//GR overworld map BCH files have just 1 model, the tall grass is entirely separate BCH
							model.worldLocX = j * 720f + 360f;
							model.worldLocZ = i * 720f + 360f;
							model.makeAllBOs();
							models[j][i] = bch;
						}
						colls[j][i] = new GRCollisionFile(mm.regions.get(j, i));
						//THE NAME IS A TREE CAPTION AND IT MUST NOT COST THE WHOLE ZONE.
						//Thirteen lines up the model setup is wrapped in
						//"if (!bch.models.isEmpty())" because a region's FieldData subfile 1
						//is not guaranteed to parse to a model - BCHFile returns with an empty
						//model list for anything that is not a BCH - and then this line, which
						//is OUTSIDE that guard, called bch.models.get(0) anyway. On such a
						//region it threw IndexOutOfBoundsException out of doInBackground, which
						//awaitLoad turns into "The map did not load", so the entire zone refused
						//to open and the reason the user was shown was a collision-panel
						//caption. The collision file itself is read on the line above and never
						//needed the model, and this name only ever becomes a JTree node label,
						//so name the cell instead and let the rest of the zone open.
						collision.loadCollision(colls[j][i], bch.models.isEmpty()
								? "Region " + j + "x" + i : bch.models.get(0).name);
					}
					if (progress != null) {
						progress.setBarPercent((int) (((i * mm.width + j) / (float) (mm.width * mm.height)) * 100));
					}
				}
			}
			scene.frameMatrix(mm.width, mm.height);
			remove(placeholder);
			invalidate();
			revalidate();
			if (progress != null) {
				progress.setDescription("Checking compatibility");
			}
			if (progress != null) {
				progress.setDescription("Preparing viewport");
			}
			loaded = true;
			loadProps(propTextures, reg);
			scaleImage(1);
	}

	/**
	 * Waits for a load worker and refuses to return normally when it failed:
	 * the panel goes back to its placeholder rather than keeping half a map,
	 * and the failure is thrown on so the caller cannot carry on as though a
	 * zone were open. Dropping that throw is what let a failed load present
	 * itself as a loaded one.
	 *
	 * <p>Its own method because the only caller starts its worker behind a
	 * modal progress dialog, and a guard with no display cannot open one. What
	 * a failed load leaves behind does not have to go untested for that.
	 */
	public void awaitLoad(SwingWorker<?, ?> worker) {
		try {
			worker.get();
		} catch (InterruptedException | ExecutionException ex) {
			Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
			unload(); //the placeholder, not half of a map
			throw new IllegalStateException("The map did not load: " + cause, cause);
		}
	}

	/**
	 * Writes the tilemap back, asking first only when {@code dialog} says to.
	 *
	 * <p>THE FLAG MEANS "MAY I ASK", NOT "MAY I WRITE". The whole save used to
	 * sit inside {@code modified && dialog}, so a flush that asks nothing - which
	 * is exactly what File &gt; Save is, {@code openEditors.saveAll(false)} -
	 * fell straight through to {@code return true} and reported every tile edit
	 * on a loose GR map as saved while writing none of them. The matrix path a
	 * few lines up never had this problem, so the bug was invisible unless you
	 * had opened a single region through File &gt; Open GR Mapfile.
	 */
	public boolean saveTileMap(boolean dialog) {
		if (loaded) {
			if (mode == ViewportMode.MULTI) {
				return saveMatrix(dialog);
			} else if (tilemaps[0][0].modified) {
				if (!dialog) {
					mainGR.storeFile(0, tilemaps[0][0].assembleTilemap());
					tilemaps[0][0].modified = false;
					return true;
				}
				switch (ctrmap.Ui.askToKeep(true, "Tilemap")) {
					case SAVE:
						mainGR.storeFile(0, tilemaps[0][0].assembleTilemap());
					case DISCARD:
						tilemaps[0][0].modified = false;
						return true;
					default:
						return false;
				}
			}
		}
		return true;
	}

	public void renderTileMap() {
		GraphicsConfiguration gConfig = GraphicsEnvironment
				.getLocalGraphicsEnvironment().getDefaultScreenDevice()
				.getDefaultConfiguration();
		tilemapScaledImage = gConfig.createCompatibleImage((int) (width * 10 * tilemapScale), (int) (height * 10 * tilemapScale));
		cm2dOverlayImage = gConfig.createCompatibleImage((int) (width * 10 * tilemapScale), (int) (height * 10 * tilemapScale), Transparency.TRANSLUCENT);
		g = tilemapScaledImage.getGraphics();
		g.setColor(new Color(0xffffff));
		g.fillRect(0, 0, width * 10, height * 10);
		int regionSize = (int) (Math.round(400 * tilemapScale));
		if (mode == ViewportMode.SINGLE) {
			g.drawImage(tilemaps[0][0].getImage(), 0, 0, (int) (400 * tilemapScale), (int) (400 * tilemapScale), null);
		} else if (mode == ViewportMode.MULTI) {
			for (int i = 0; i < mm.height; i++) {
				for (int j = 0; j < mm.width; j++) {
					if (tilemaps[j][i] != null) {
						//we stretch the regions a few pixels to cover gaps when zooming due to float imprecision
						g.drawImage(tilemaps[j][i].getImage(), (int) (Math.round(400d * tilemapScale * j)), (int) (Math.round(400d * tilemapScale * i)), regionSize + 1, regionSize, null);
					}
				}
			}
		}
	}

	public BufferedImage renderGL(GL2 gl) {
		if (update) {
			scene.renderables().forEach((r) -> {
				r.uploadBuffers(gl);
			});
			update = false;
		}

		scene.renderables().forEach((r) -> {
			r.renderCM3D(gl);
		});

		gl.glFlush();
		return new AWTGLReadBufferUtil(glp, true).readPixelsToBufferedImage(gl, true);
	}

	@Override
	public void renderCM3D(GL2 gl) {
		//THE ANIMATOR DOES NOT WAIT FOR A MAP. H3DRenderingPanel starts a 60fps
		//FPSAnimator in its constructor and its display() draws every registered
		//renderable as soon as the WORKSPACE is valid - a workspace, not a zone -
		//and this panel is registered at startup rather than on load. So with the
		//3D view toggled on before any zone is picked it arrived here with mode
		//still SINGLE and models still null, and models[0][0] threw
		//NullPointerException sixty times a second on the animator thread, where
		//nothing but stderr could see it. unload() leaves precisely that state
		//behind as well - it nulls models and puts mode back to SINGLE - so a
		//matrix that failed to load turned the 3D view into the same per-frame
		//throw until another zone opened. mm is tested too because doInBackground
		//sets mode to MULTI three statements before it assigns mm, and the animator
		//runs in that gap. The two sibling overrides below, uploadBuffers and
		//deleteGLInstanceBuffers, have always opened with a test of this shape;
		//the one that actually draws was the only one without.
		if (models == null || tallgrass == null || (mode == ViewportMode.MULTI && mm == null)) {
			return;
		}
		if (mode == ViewportMode.MULTI) {
			for (int i = 0; i < mm.height; i++) {
				for (int j = 0; j < mm.width; j++) {
					if (j < models.length && i < models[j].length && models[j][i] != null) {
						models[j][i].render(gl);
					}
					if (j < tallgrass.length && i < tallgrass[j].length && tallgrass[j][i] != null) {
						tallgrass[j][i].render(gl);
					}
					//useless probably? Can't really edit it in CM3D so it's better to just link it with CollEd per region.
					/*if (colls[j][i] != null) {
						gl.glPushMatrix();
						gl.glTranslatef(j * 720 + 360f, 1, i * 720 + 360f); //we translate it 1 unit up so it does not overlap with the world models
						gl.glBegin(GL2.GL_TRIANGLES);
						colls[j][i].render(gl);
						gl.glEnd();
						gl.glPopMatrix();
					}*/
				}
			}
		} else {
			if (models[0][0] != null) {
				models[0][0].render(gl);
			}
			if (tallgrass[0][0] != null) {
				tallgrass[0][0].render(gl);
			}
		}
	}

	public float getHeightAtWorldLoc(float x, float z) {
		return getHeightAtWorldLoc(colls, x, z);
	}

	/**
	 * The colours a region's picture is painted in: the tile form's tileset,
	 * or nothing (no picture) when the form is not there, which is how a
	 * headless test holds the data. The region used to read the form itself.
	 */
	Tilemap.TileColors tileColors() {
		if (colours == null) {
			return null;
		}
		//A LIVE VIEW OF THE FORM'S TILESET, NOT THE TILESET OBJECT ITSELF. Tilemap
		//captures what it is handed into a final field and paints from it forever,
		//and Workspace.getTileset() returns a NEW EditorTileset on every call - so
		//handing the object over meant that changing the tileset in Workspace
		//settings (which assigns a new one to the form and then calls updateAll)
		//repainted every region with the palette it had captured at load. The user
		//saw the progress dialog run and nothing change, and the new colours only
		//arrived with the next zone load. Tilemap's own javadoc records that the
		//picture used to read the form's tileset live, per colour; this hands the
		//region something that still does, while keeping what the handing was for -
		//a headless holder gets null and paints nothing, and a suite still hands
		//its own colours.
		return colours;
	}

	/**
	 * Collision height at a world position, or NaN outside the loaded
	 * matrix. colls is [width][height] and each axis is bounded by its own
	 * side: testing both against the width returned NaN for every row past
	 * the width on a map taller than wide (27 retail matrices), and an NPC
	 * dragged there lost its altitude to the file.
	 */
	public static float getHeightAtWorldLoc(GRCollisionFile[][] colls, float x, float z) {
		int cx = (int) (x / 720f);
		int cz = (int) (z / 720f);
		if (x < 0 || z < 0 || cx >= colls.length || cz >= colls[cx].length) {
			return Float.NaN;
			//methods using this should handle Float.NaN
		}
		GRCollisionFile f = colls[cx][cz];
		if (f == null) {
			return 0f;
		}
		return f.getHeightAtPoint((x % 720f) - 360f, (z % 720f) - 360f);
	}

	@Override
	public void renderOverlayCM3D(GL2 gl) {
	}

	@Override
	public void uploadBuffers(GL2 gl) {
		if (loaded && mode == ViewportMode.MULTI && mm != null) {
			for (int i = 0; i < mm.height; i++) {
				for (int j = 0; j < mm.width; j++) {
					if (j < models.length && i < models[j].length && models[j][i] != null) {
						models[j][i].models.get(0).uploadAllBOs(gl);
					}
					if (j < tallgrass.length && i < tallgrass[j].length && tallgrass[j][i] != null) {
						tallgrass[j][i].models.get(0).uploadAllBOs(gl);
					}
				}
			}
		}
	}

	@Override
	public void deleteGLInstanceBuffers(GL2 gl) {
		if (loaded && mode == ViewportMode.MULTI && mm != null) {
			for (int i = 0; i < mm.height; i++) {
				for (int j = 0; j < mm.width; j++) {
					if (models[j][i] != null) {
						models[j][i].models.get(0).destroyAllBOs(gl);
					}
					if (tallgrass[j][i] != null) {
						tallgrass[j][i].models.get(0).destroyAllBOs(gl);
					}
				}
			}
		}
	}

	public Tilemap getRegionForTile(int x, int y) {
		if (tilemaps == null) {
			return null;
		}
		return tilemaps[x / 40][y / 40];
	}

	public Point getRawAtViewportCentre() {
		int imgstartx = (this.getWidth() - tilemapScaledImage.getWidth()) / 2;
		int imgstarty = (this.getHeight() - tilemapScaledImage.getHeight()) / 2;
		int WPStartX = Math.round(viewport.getViewport().getViewPosition().x - imgstartx);
		int WPStartY = Math.round(viewport.getViewport().getViewPosition().y - imgstarty);
		int xFromWPStart = Math.round(viewport.getViewport().getWidth() / 2);
		int yFromWPStart = Math.round(viewport.getViewport().getHeight() / 2);
		return new Point(WPStartX + xFromWPStart, WPStartY + yFromWPStart);
	}

	public Point getWorldLocAtViewportCentre() {
		Point raw = getRawAtViewportCentre();
		return new Point((int) Math.round(raw.x / 400d * 720d / tilemapScale), (int) Math.round(raw.y / 400d * 720d / tilemapScale));
	}

	public Point getTileAtViewportCentre() {
		double globimgdim = tilemapScaledImage.getHeight() / (double) height;
		Point raw = getRawAtViewportCentre();
		return new Point((int) Math.round(raw.x / globimgdim), (int) Math.round(raw.y / globimgdim));
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		if (loaded) {
			int imgstartx = (this.getWidth() - tilemapScaledImage.getWidth()) / 2;
			int imgstarty = (this.getHeight() - tilemapScaledImage.getHeight()) / 2;
			int scrollpanex = viewport.getViewport().getViewPosition().x;
			int scrollpaney = viewport.getViewport().getViewPosition().y;
			int scrollpanew = viewport.getViewport().getSize().width;
			int scrollpaneh = viewport.getViewport().getSize().height;
			if (scrollpanex + scrollpanew > tilemapScaledImage.getWidth()) {
				scrollpanew = tilemapScaledImage.getWidth() - scrollpanex;
			}
			if (scrollpaney + scrollpaneh > tilemapScaledImage.getHeight()) {
				scrollpaneh = tilemapScaledImage.getHeight() - scrollpaney;
			}
			BufferedImage crop = tilemapScaledImage.getSubimage(scrollpanex, scrollpaney, scrollpanew, scrollpaneh);
			/**
			 * Originally, I've tried using the full drawImage method (with
			 * source and dest. coordinates) to only draw a part of the full
			 * image. That resulted in occasional slowdowns (cause unknown) in
			 * paintComponent about 1/10 times when using SetTool, resulting in
			 * major lag. Cropping the image into memory takes less than a
			 * millisecond (depending on the hardware) and results in an overall
			 * pleasant mapping experience.
			 */
			g.drawImage(crop, imgstartx + scrollpanex, imgstarty + scrollpaney, null);

			BufferedImage crop2;
			double globimgdim = tilemapScaledImage.getHeight() / (double) height;
			int gidround = (int) Math.round(globimgdim);
			if (CM2DTempImage != null && (tools.current().CM2DNoUpdate || Selector.getSelectorCM2DRenderOptimizationFlag())) {
				crop2 = CM2DTempImage;
			} else {
				CM2DDrawable.display();
				CM2DDrawable.getContext().makeCurrent();
				GL2 gl = CM2DDrawable.getGL().getGL2();
				gl.glViewport(0, 0, scrollpanew, scrollpaneh);
				gl.glMatrixMode(GL2.GL_PROJECTION);
				gl.glLoadIdentity();

				gl.glOrtho(scrollpanex / globimgdim * 18d, (scrollpanex + scrollpanew) / globimgdim * 18d, (scrollpaney + scrollpaneh) / globimgdim * 18d, (scrollpaney) / globimgdim * 18d, 2000d, -2000d);

				gl.glMatrixMode(GL2.GL_MODELVIEW);
				gl.glLoadIdentity();

				gl.glClear(GL2.GL_COLOR_BUFFER_BIT | GL2.GL_DEPTH_BUFFER_BIT);
				gl.glLoadIdentity();
				gl.glRotatef(-90f, 1.0f, 0.0f, 0.0f);
				crop2 = renderGL(gl);
			}
			Graphics2D g2d = (Graphics2D) g;
			g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
			g2d.drawImage(crop2, imgstartx + scrollpanex, scrollpaney - imgstarty, null);
			g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));

			CM2DTempImage = crop2;

			tools.current().drawOverlay(g, imgstartx, imgstarty, globimgdim);
			g.setColor(Color.RED);
			if (tools.current().getSelectorEnabled()) {
				if (Selector.hilightTileX != -1) {
					g.drawRect(imgstartx + (int) Math.round(Selector.hilightTileX * globimgdim), imgstarty + (int) Math.round(Selector.hilightTileY * globimgdim), gidround, gidround);
				}
				if (Selector.selTileX != -1) {
					g.drawRect(imgstartx + (int) Math.round(Selector.selTileX * globimgdim), imgstarty + (int) Math.round(Selector.selTileY * globimgdim), gidround, gidround);
				}
			}
		}
	}

	/**
	 * What a failed tilemap rebuild owes the user: the picture on screen is not
	 * the map any more, and only this sentence says so - the progress dialog
	 * closes either way, so without it the stale image simply stays up looking
	 * current.
	 *
	 * <p>Its own method, and through {@link ctrmap.Ui}, for two reasons: it
	 * runs inside a worker started behind a modal progress dialog that a
	 * headless guard cannot open, and as a bare JOptionPane "the user was told"
	 * was not a fact any test could check.
	 */
	public void refreshFailed(Throwable cause) {
		Logger.getLogger(TileMapPanel.class.getName()).log(Level.SEVERE, "updating tilemaps", cause);
		ctrmap.Ui.error(this, "The tilemap view was not refreshed:\n" + cause, "Update tilemaps");
	}

	/**
	 * What a failed matrix save owes the user: which failure stopped it, and
	 * that the regions it did not get to are still marked modified.
	 *
	 * <p>The save marks each region unmodified as it writes it, so a run that
	 * throws part-way leaves the rest still flagged - and saying so is what
	 * turns "the dialog closed" into "some of your map is not on disk, and the
	 * editor still knows which". Without it the progress dialog closes exactly
	 * as it does on success and the zone switch carries on, which is how a
	 * half-saved matrix used to leave the building.
	 *
	 * <p>Its own method, and through {@link ctrmap.Ui}, for the same two
	 * reasons as {@link #refreshFailed}: it runs inside a worker started behind
	 * a modal progress dialog a headless guard cannot open, and a bare
	 * JOptionPane is not something a test can read.
	 */
	public void regionSaveFailed(Throwable cause) {
		Logger.getLogger(TileMapPanel.class.getName()).log(Level.SEVERE, "saving matrix", cause);
		ctrmap.Ui.error(this, "The region data was not saved:\n" + cause
				+ "\n\nWhat did not write is still marked modified - fix the cause and save again.",
				"Save region data");
	}

	public void updateAll() {
		LoadingDialog progress = LoadingDialog.makeDialog("Updating tilemap(s)");
		SwingWorker worker = new SwingWorker() {
			@Override
			protected void done() {
				progress.close();
				try {
					get(); //without this, a tile image that failed to rebuild closes the dialog and
					//the old picture stays up as if it were current
				} catch (Exception ex) {
					refreshFailed(ex.getCause() != null ? ex.getCause() : ex);
				}
			}

			@Override
			protected Object doInBackground() {
				if (tilemaps == null) {
					return null;
				}
				for (int i = 0; i < tilemaps.length; i++) { //can't use matrix when not loaded
					for (int j = 0; j < tilemaps[i].length; j++) {
						if (tilemaps[i][j] != null) {
							tilemaps[i][j].updateImage();
						}
						progress.setBarPercent((int) (((i * tilemaps[i].length + j) / (float) (tilemaps.length * tilemaps[i].length)) * 100));
					}
				}
				//THE PANEL THAT JUST REBUILT ITS IMAGES IS THE ONE THAT MUST RESCALE
				//THEM. This said mTileMapPanel - the main window's static - from inside
				//a worker whose enclosing instance is this panel, so the loop above
				//rebuilt THIS panel's region images and this line then rescaled
				//whichever panel the window happened to be holding. Today that is
				//always the same object and the editor behaves identically; the cost was
				//latent, and paid by anyone who ever builds a second panel or runs this
				//with the static unset, where it is a NullPointerException inside the
				//worker that done() reports as "the tilemap view was not refreshed" -
				//a true sentence about the wrong panel.
				TileMapPanel.this.scaleImage(TileMapPanel.this.tilemapScale);
				return null;
			}
		};
		worker.execute();
		progress.showDialog();
	}

	public void scaleImage(double scale) {
		if (loaded && scale > 0.05f && scale <= 1f) {
			tilemapScale = scale;
			renderTileMap();
			this.setPreferredSize(new Dimension(tilemapScaledImage.getWidth(), tilemapScaledImage.getHeight()));
			this.invalidate();
			viewport.revalidate();
			viewport.repaint();
		}
	}

	public void perfScale(double scale, int changedRegionX, int changedRegionY) {
		if (loaded && scale > 0.05f && scale <= 1f) {
			tilemapScale = scale;
			this.setPreferredSize(new Dimension(tilemapScaledImage.getWidth(), tilemapScaledImage.getHeight()));
			g = tilemapScaledImage.getGraphics();
			int regionSize = (int) (Math.round(400 * tilemapScale));
			g.drawImage(tilemaps[changedRegionX][changedRegionY].getImage(), (int) (Math.round(400d * tilemapScale * changedRegionX)), (int) (Math.round(400d * tilemapScale * changedRegionY)), regionSize + 1, regionSize, null);
			viewport.repaint();
		}
	}
}
