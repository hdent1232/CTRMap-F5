package ctrmap;

import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.prefs.Preferences;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

import ctrmap.formats.containers.GR;
import ctrmap.formats.containers.MM;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.mapmatrix.MapMatrix;
import ctrmap.formats.zone.Zone;
import ctrmap.humaninterface.CM3DInputManager;
import ctrmap.humaninterface.CM3DRenderable;
import ctrmap.humaninterface.CameraEditForm;
import ctrmap.humaninterface.CollEditPanel;
import ctrmap.humaninterface.OpenEditors;
import ctrmap.formats.MapObject;
import ctrmap.humaninterface.Navigator;
import ctrmap.humaninterface.Redraw;
import ctrmap.humaninterface.Scene3D;
import ctrmap.humaninterface.ViewportCentre;
import ctrmap.humaninterface.ZoneEditors;
import ctrmap.humaninterface.CollInputManager;
import ctrmap.humaninterface.ExtrasPanel;
import ctrmap.humaninterface.GLPanel;
import ctrmap.humaninterface.GlSupport;
import ctrmap.humaninterface.H3DRenderingPanel;
import ctrmap.humaninterface.MapMatrixPanel;
import ctrmap.humaninterface.MatrixEditForm;
import ctrmap.humaninterface.MatrixPanelInputManager;
import ctrmap.humaninterface.NPCEditForm;
import ctrmap.humaninterface.PropEditForm;
import ctrmap.humaninterface.ScriptEditor;
import ctrmap.humaninterface.TextEditor;
import ctrmap.humaninterface.TileEditForm;
import ctrmap.humaninterface.TilemapPanelInputManager;
import ctrmap.humaninterface.TileMapPanel;
import ctrmap.humaninterface.GeoEditForm;
import ctrmap.humaninterface.TriggerEditForm;
import ctrmap.humaninterface.WarpEditForm;
import ctrmap.humaninterface.WorldEditorToolbar;
import ctrmap.humaninterface.WorkspaceSettings;
import ctrmap.humaninterface.ZoneLoadingPanel;
import ctrmap.humaninterface.builder.Builder;
import ctrmap.humaninterface.tools.ToolBox;
import ctrmap.humaninterface.tools.ToolHost;
import ctrmap.humaninterface.tools.ToolSelection;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.filechooser.FileFilter;

/**
 * The launcher class for CTRMap which nests all of its GUI elements and
 * provides access to them with static imports, similar to a C++ namespace.
 */
public class CtrmapMainframe {

	/**
	 * The editor's window. PRIVATE: nothing below the window reaches it any
	 * more. Fourteen classes did - five dialog parents, which the dialog seam
	 * holds now, and nine repaints, which are a capability the forms and tools
	 * are handed. Both mattered for the same reason: a JFrame cannot be built
	 * without a display, so every line that touched one was a line no headless
	 * suite could reach.
	 */
	private JFrame frame;

	/** How the window first shows: Updater.firstWindowState of the arguments it was started with. */
	private int firstState = JFrame.MAXIMIZED_BOTH;

	/** The open zone's undo history (F209): see where it is built. */
	private ctrmap.humaninterface.UndoHistory zoneHistory;

	/**
	 * The history the key went to: the nearest one an editor around the FOCUSED component owns, else the
	 * zone's. Not the event's source - a binding on an ancestor fires with the ancestor as its source.
	 */
	private ctrmap.humaninterface.UndoHistory activeHistory(ActionEvent e) {
		return ctrmap.humaninterface.UndoHistory.of(
				java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner(), zoneHistory);
	}

	/** Draws the map again from its regions' tiles, and the tile the inspector shows - after an undo put them back. */
	private void redrawRegions() {
		mTileMapPanel.scaleImage(mTileMapPanel.tilemapScale);
		mTileMapPanel.firePropertyChange(TileMapPanel.PROP_REPAINT, false, true);
		if (ctrmap.humaninterface.Selector.selTileX != -1) {
			mTileEditForm.showTile(ctrmap.humaninterface.Selector.selTileX, ctrmap.humaninterface.Selector.selTileY, true);
		}
	}
	/**
	 * The open game, handed to this window when a workspace opens and dropped
	 * when it closes: the ONE place the window keeps it. Every action that
	 * hands the game on - to a container it opens, the zone manager, the
	 * resizer, the location-name reload - reads this field. Each used to
	 * resolve {@code Workspace.session()} for itself at the moment of the
	 * click: five lookups of the same global under a longer name, which the
	 * container constructors' migration would have made fifteen, and not one
	 * of which a suite could hand a different game. The window is the top of
	 * the program and legitimately owns the reference; what it must not do is
	 * fetch it in N places. Assigned in {@link #onWorkspaceOpened} and nowhere
	 * else, and private, so nothing below the window can read it as a global.
	 */
	private WorkspaceSession game;

	/**
	 * The loaded-zone state - the zone table, the open zone and its index -
	 * made ONCE, in {@link #createAndShowGUI}, and handed to the Zone tab and
	 * to every editor that reads it. The window's own actions (the spinner
	 * defaults, the fog refresh, the OBJ region default, blank canvas, resize,
	 * facility setup) read this field; they used to read the panel's three
	 * public fields through {@link #mZonePnl}, the same global under a longer
	 * name, and no suite could hand them a different zone. Assigned through
	 * {@link #bindLoadedZone} and nowhere else, and private, so nothing below
	 * the window can read it as a global.
	 */
	private LoadedZone loadedZone;
	/**
	 * Every editor that holds unsaved work, in the order they are written.
	 * Made ONCE here, because this is where the editors are built, and handed
	 * to the two classes that flush them before they reload or apply. See
	 * {@link OpenEditors} for the five copies of the chain it replaces and
	 * what they disagreed about.
	 *
	 * <p>ASSIGNED FROM A LOCAL, and the two classes handed it get the local,
	 * not this field. That is not a style: when this field was assigned where
	 * the list is easiest to read - after the editors it names - the Zone tab
	 * and the map painter were built sixty lines earlier and were handed null,
	 * so every save, every zone switch and every paint apply threw. A field
	 * read before it is written is a null the compiler cannot see; a local
	 * read before it is written does not compile. The field remains only
	 * because File &gt; Save and the close handler are other methods.
	 */
	private OpenEditors openEditors;
	/**
	 * Which editing tool the World Editor is holding, made ONCE here and
	 * handed to the nine classes that ask which it is and to the mouse router
	 * that changes it. It used to be a public mutable static of this class -
	 * the one the router assigned from ten places - so no suite could hold two
	 * tools, and "the NPC form marks its NPC while the NPC tool is up" had to
	 * be asserted by writing to this window's field.
	 */
	private ToolSelection tools;
	/**
	 * How the editor is asked to draw itself again, made ONCE here and handed
	 * to the forms that used to call {@code frame.repaint()} through this
	 * class's statics. See {@link ctrmap.humaninterface.Redraw} for why that
	 * mattered: a JFrame cannot be built without a display, so every one of
	 * those lines was unreachable from a headless suite.
	 */
	private Redraw redraw;

	/**
	 * The 3D gizmo, made ONCE here and handed to the three forms that used to
	 * reach for the panel themselves. The null guard is in the implementation
	 * below and nowhere else - see {@link Navigator} for the five copies that
	 * disagreed about whether to have one.
	 */
	private Navigator navigator;
	private JTabbedPane tabs;

	/** The World Editor's tool row: the one handle for "pick this tool" and "which view is up". */
	private WorldEditorToolbar worldToolbar;
	private ctrmap.humaninterface.PaintForm mPaintForm;
	private JPanel zoneTabPnl; //showZoneLoadingHint lands the user here

	private JScrollPane mTilemapScrollPane;
	private TileMapPanel mTileMapPanel;
	private TileEditForm mTileEditForm;
	private JScrollPane mCamScrollPane;
	private CameraEditForm mCamEditForm;
	private PropEditForm mPropEditForm;
	private NPCEditForm mNPCEditForm;
	private WarpEditForm mWarpEditForm;
	private TriggerEditForm mTriggerEditForm;
	private GeoEditForm mGeoEditForm;

	private H3DRenderingPanel m3DDebugPanel;
	private CollEditPanel mCollEditPanel;

	private MapMatrixPanel mMtxPanel;
	private MatrixEditForm mMtxEditForm;

	private ZoneLoadingPanel mZonePnl;
	private ScriptEditor mScriptPnl;
	/**
	 * The text editor and the map builder. PRIVATE, because nothing outside this
	 * window has ever read either of them - not one application class and not one
	 * suite. They were public because everything here was, and a public static
	 * with no readers is not coupling anybody has yet, it is coupling nobody has
	 * had to argue against yet.
	 */
	private TextEditor mTextEditor;
	private Builder mBuilder;

	//the World Editor tab and its split: showWorldEditor() and switchToolUI() are the way in
	private JPanel tileEditMasterPnl;
	private JSplitPane jsp;
	//the Collision and Matrix editors' splits, sized by adjustSplitPanes()
	private JPanel collEditMasterPnl;
	private JSplitPane jsp2;
	private JPanel mtxEditMasterPnl;
	private JSplitPane jsp3;


	/**
	 * Everything the 3D view draws. FINAL because the renderer is handed this
	 * very list once, at construction: reassigning the field would leave the
	 * renderer looking at the old list, and every component registered after
	 * the swap would simply never be drawn, with nothing to say so.
	 */
	private final List<CM3DRenderable> CM3DComponents = new ArrayList<>();

	public static void main(String[] args) {
		//This is the application, so this is where dialogs are allowed. Ui keeps
		//them off by default so that a test suite or a command-line tool cannot
		//stop dead on a modal window nobody is there to click.
		Ui.enableDialogs();
		//so no build deletes the classes this window is running from (2026-10-11) - see RunningFrom
		RunningFrom.declare();
		//BEFORE ANYTHING CAN FAIL (F219): the launcher has no console, so an error with
		//nowhere to go reached nobody. If the log folder cannot be written either, the user
		//is told, in a dialog - stderr is the console that is not there.
		java.io.IOException noLog = null;
		try {
			ErrorSink.install();
		} catch (java.io.IOException failed) {
			noLog = failed;
		}
		final String logFailure = noLog == null ? null : "CTRMap could not open its log, so an error it does"
				+ " not handle will be shown but not kept: " + ctrmap.util.Bytes.reason(noLog);
		//The Windows bundle installs an update by starting the DOWNLOADED copy
		//with this flag as the old one closes: it holds nothing in the install
		//folder, so it can replace all of it. Must be the first thing we do -
		//no window, no workspace, nothing. It STAYS ON THIS THREAD: it has no window and
		//waits up to a minute for the old copy to close, which the event thread must not.
		if (ctrmap.update.Updater.isApplyInvocation(args)) {
			if (logFailure != null) {
				try {
					SwingUtilities.invokeAndWait(() -> Ui.error(null, logFailure, "CTRMap"));
				} catch (InterruptedException | java.lang.reflect.InvocationTargetException unsaid) {
					System.out.println(logFailure);
				}
			}
			ctrmap.update.Updater.runApply(args);
			return;
		}
		//EVERYTHING ELSE IS SWING, AND SWING IS THE EVENT THREAD'S (F220). The window was
		//built on this, the main thread: the look and feel, every menu and panel, the
		//workspace prompts and the setup wizard - over three thousand widget calls on a
		//thread Swing does not run on, which EdtReachTest counts from the bytecode.
		//ONE WINDOW, AN OBJECT (F007): everything below was static - 43 fields and every method that
		//touched them - so a second window, or a suite holding two editors, was impossible by
		//construction. This is the only place one is made in the program.
		CtrmapMainframe window = new CtrmapMainframe();
		//reopened by the updater, it comes back minimized rather than up over what the owner is doing
		window.firstState = ctrmap.update.Updater.firstWindowState(args);
		SwingUtilities.invokeLater(() -> window.startOnTheEventThread(logFailure));
	}

	/** The window, built where Swing requires it: on the event thread (F220). */
	private void startOnTheEventThread(String logFailure) {
		if (logFailure != null) {
			Ui.error(null, logFailure, "CTRMap");
		}
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException
				| UnsupportedLookAndFeelException e) {
			e.printStackTrace();
		}
		createAndShowGUI();
	}

	/**
	 * Hands the window the zone owner its actions read. The application calls
	 * this once, from {@link #createAndShowGUI}, with the owner it then hands
	 * the panels; a suite driving an action by name hands its own, or null
	 * for "no owner at all", which every zone-scoped action refuses in words.
	 */
	private void bindLoadedZone(LoadedZone owner) {
		loadedZone = owner;
	}

	/**
	 * Every editor that holds unsaved work, in the order they are written.
	 *
	 * <p>Built here rather than inline in createAndShowGUI, which is the
	 * window's WIRING and was carrying a hundred and ten lines of what the
	 * lists mean. The caller assigns the result to a local and hands that
	 * local on, so the compiler still proves nothing is handed this before
	 * it exists - see MainframeEdgesTest section four for the commit where
	 * that was not true.
	 */
	private OpenEditors buildOpenEditors() {
		//the editors that hold unsaved work, in the order they are written. One
		//list, because five copies of the chain disagreed about which editors
		//counted and in which order - see OpenEditors.
		return new OpenEditors(java.util.Arrays.<OpenEditors.Editable>asList(
				ask -> mCamEditForm.store(ask),
				ask -> mTileMapPanel.saveTileMap(ask),
				ask -> mMtxEditForm.store(ask),
				ask -> mCollEditPanel.store(ask),
				ask -> mPropEditForm.store(ask),
				ask -> mNPCEditForm.saveRegistry(ask),
				ask -> mZonePnl.store(ask),
				ask -> mTextEditor.store(ask)));
	}

	/**
	 * Every editor that shows the open zone, what showing one means, and what
	 * committing what they hold means.
	 *
	 * <p>ORDER IS A DEPENDENCY here and not a preference: the matrix panel is
	 * handed the map view's matrix, so the map view is first.
	 */
	private ZoneEditors buildZoneEditors(Scene3D scene) {
		//what "show this zone" means, editor by editor, in the order they must run:
		//the matrix panel is handed the map view's matrix, so the map view is first.
		//Four of them clear to nothing today; each says so rather than being absent
		//from a shorter list, which is what the Zone tab's unload used to be.
		return new ZoneEditors(java.util.Arrays.<ZoneEditors.ZoneView>asList(
				new ZoneEditors.ZoneView() {
					@Override
					public void show(ctrmap.formats.zone.Zone z) {
						mTileMapPanel.loadMatrix(
								new ctrmap.formats.mapmatrix.MapMatrix(z.header.mapmatrix, Workspace.session()),
								new ctrmap.formats.propdata.ADPropRegistry(z.header.areadata, z.header.propTextures,
										Workspace.session()),
								z.header.worldTextures, z.header.propTextures);
					}

					@Override
					public void clear() {
						//the map view keeps the map it drew: the Zone tab's unload never
						//cleared it, and blanking the viewport on a failed load would
						//hide the map the user still has open
					}

					@Override
					public void showEntities(ctrmap.formats.zone.Zone z) {
						//it draws no entity
					}

					@Override
					public boolean commit() {
						return true;   //its tiles are flushed through OpenEditors, not here
					}
				},
				new ZoneEditors.ZoneView() {
					@Override
					public void show(ctrmap.formats.zone.Zone z) {
						mMtxPanel.loadMatrix(mTileMapPanel.mm);
					}

					@Override
					public void clear() {
						//paired with the map view above, and cleared with it: neither was
					}

					@Override
					public void showEntities(ctrmap.formats.zone.Zone z) {
						//the matrix holds no entity
					}

					@Override
					public boolean commit() {
						return true;   //it shows the matrix; it holds no part-typed record
					}
				},
				new ZoneEditors.ZoneView() {
					@Override
					public void show(ctrmap.formats.zone.Zone z) {
						//NOTHING, and not because the prop editor is not shown a zone: the map
						//view at the head of this list is what shows it one, through
						//loadMatrix -> loadProps -> MapEditors.showProps. Reading the props a
						//second time here would replace the very objects the models drawn in
						//the 3D view were built from.
					}

					@Override
					public void clear() {
						//THE ENTRY THAT WAS MISSING. The prop editor shows the open zone - the
						//map view hands it the matrix's props on the way in (loadMatrix ->
						//loadProps -> MapEditors.showProps) - but it was not on this list at
						//all, and being absent is not the same as clearing to nothing: it meant
						//never asked. So a load that failed left the form holding the PREVIOUS
						//zone's props with loaded still true. WHICH failure decides how bad
						//that is. A throw from inside loadMatrix has already been through
						//awaitLoad, which calls TileMapPanel.unload(): mm is null, so those
						//props belong to a map that no longer exists, renderCM3D goes on
						//drawing them over an emptied viewport, and the form's own store() can
						//only refuse. A throw from earlier in the worker - fetchArchives on a
						//header naming an areadata that is not there, which is how a broken
						//zone usually fails - leaves the map view holding its map, so this
						//unload also takes the props off a map that is still on screen. That is
						//the price of asking one question instead of two, and it is the smaller
						//one: no zone is open by then, and unloadEditors() drops the map view
						//and this form together for exactly the same reason. It drops nothing
						//unsaved either: the zone switch runs openEditors.saveAll(true), whose
						//fifth entry is this form's own store(), before it loads anything.
						mPropEditForm.unload();
					}

					@Override
					public void showEntities(ctrmap.formats.zone.Zone z) {
						//props are not entities; their own question puts them back
					}

					@Override
					public boolean commit() {
						return true;   //its record is read by its own store(), through OpenEditors
					}
				},
				new ZoneEditors.ZoneView() {
					@Override
					public void show(ctrmap.formats.zone.Zone z) {
						mCamEditForm.loadFrom(z.header.areadata);
					}

					@Override
					public void clear() {
						//THE CAMERAS GO WITH THE ZONE (F222): this was empty - "its own store() refuses
						//when it holds nothing" stood in for clearing - so a failed load left the
						//closed zone's area cameras in a form that looked live
						mCamEditForm.unload();
					}

					@Override
					public void showEntities(ctrmap.formats.zone.Zone z) {
						//cameras live in the area, not the entities
					}

					@Override
					public boolean commit() {
						return true;   //flushed through OpenEditors before the zone is written
					}
				},
				new ZoneEditors.ZoneView() {
					@Override
					public void show(ctrmap.formats.zone.Zone z) {
						mNPCEditForm.loadFromEntities(z.entities, z.header.npcreg);
					}

					@Override
					public void clear() {
						mNPCEditForm.loadFromEntities(null, null);
					}

					@Override
					public void showEntities(ctrmap.formats.zone.Zone z) {
						mNPCEditForm.loadFromEntities(z.entities, z.header.npcreg);
					}

					@Override
					public boolean commit() {
						//the ONE that can refuse: an NPC pointing at a script this zone does
						//not define. It has already said so by the time it answers
						return mNPCEditForm.saveEntry();
					}
				},
				new ZoneEditors.ZoneView() {
					@Override
					public void show(ctrmap.formats.zone.Zone z) {
						mWarpEditForm.loadFromEntities(z.entities);
					}

					@Override
					public void clear() {
						mWarpEditForm.loadFromEntities(null);
					}

					@Override
					public void showEntities(ctrmap.formats.zone.Zone z) {
						mWarpEditForm.loadFromEntities(z.entities);
					}

					@Override
					public boolean commit() {
						//THE ONE THAT WAS MISSING. The zone save named the NPC form and the
						//trigger form and stopped, so warp values the user had typed and not
						//pressed Save on were dropped with no warning at all
						mWarpEditForm.saveEntry();
						return true;
					}
				},
				new ZoneEditors.ZoneView() {
					@Override
					public void show(ctrmap.formats.zone.Zone z) {
						mTriggerEditForm.loadFromEntities(z.entities);
					}

					@Override
					public void clear() {
						mTriggerEditForm.loadFromEntities(null);
					}

					@Override
					public void showEntities(ctrmap.formats.zone.Zone z) {
						mTriggerEditForm.loadFromEntities(z.entities);
					}

					@Override
					public boolean commit() {
						mTriggerEditForm.saveEntry();
						return true;
					}
				},
				new ZoneEditors.ZoneView() {
					@Override
					public void show(ctrmap.formats.zone.Zone z) {
						mScriptPnl.loadScript(z.s);
					}

					@Override
					public void clear() {
						//THE SCRIPT GOES WITH THE ZONE (F222): this was empty, so a failed load or
						//Clean workspace left the closed zone's script editable, every edit lost
						mScriptPnl.unload();
					}

					@Override
					public void showEntities(ctrmap.formats.zone.Zone z) {
						//the script is not an entity; a discard of them leaves it
					}

					@Override
					public boolean commit() {
						return true;   //its edits go through the script editor's own save
					}
				},
				new ZoneEditors.ZoneView() {
					@Override
					public void show(ctrmap.formats.zone.Zone z) {
						//LAST, and last for a reason: both views must rebuild from what the
						//seven entries above just loaded, so this cannot run before them.
						//These two lines were written by hand at the end of the zone-switch
						//worker, after it had already said "show this zone" - the eighth and
						//ninth things that happen when a zone is shown, left out of the list
						//because the list did not exist yet when they were written.
						//Note reload is a public mutable field on ANOTHER object, written
						//from outside through this window's static: the "nothing outside
						//writes our statics" rule could never see it, because the static it
						//goes through is only being READ.
						scene.rebuild();
						mTileMapPanel.update = true;
					}

					@Override
					public void clear() {
						//nothing was open, so nothing the views drew is stale
					}

					@Override
					public void showEntities(ctrmap.formats.zone.Zone z) {
						//the 3D view draws the NPCs from the records just read again
						scene.rebuild();
						mTileMapPanel.update = true;
					}

					@Override
					public boolean commit() {
						return true;   //dirty flags hold no record
					}
				}));
	}

	/**
	 * The histories a gesture settles: the zone's, Game Data's, the text editor's and the workspace's, and the tileset
	 * editor's when it is open - null until the Tileset tab opens it. Out of the window so a suite asks it: no suite
	 * builds the window (the 2026-10-16 assembly, CtrmapMainframe 710, negate-if, SURVIVED).
	 */
	private static java.util.List<ctrmap.humaninterface.UndoHistory> settledHistories(ctrmap.humaninterface.UndoHistory zone,
			ctrmap.humaninterface.UndoHistory gameData, ctrmap.humaninterface.UndoHistory text,
			ctrmap.humaninterface.UndoHistory workspace, TileDBWriter tileset) {
		java.util.List<ctrmap.humaninterface.UndoHistory> open = new java.util.ArrayList<>(java.util.Arrays.asList(
				zone, gameData, text, workspace));
		if (tileset != null) {
			open.add(tileset.history());
		}
		return open;
	}

	/**
	 * The 3D collision view's mouse and keys, where OpenGL started; nothing where it did not and there is no view.
	 * Out of the window so a suite asks it: no suite builds the window (the 2026-10-16 assembly, CtrmapMainframe 837,
	 * negate-if, SURVIVED).
	 */
	private static void collisionInput(GLPanel glPanel) {
		if (glPanel != null) {
			new CollInputManager(glPanel);
		}
	}

	/**
	 * A form's path field filled with what a chooser or a maker answered, and left as it was when the answer is none.
	 * One step where the deploy form had three - its folder, its code.ips picked, its code.ips made - each a guard of
	 * its own in a lambda no suite reached (the 2026-10-16 assembly, CtrmapMainframe 2324, 2332 and 2345, SURVIVED).
	 */
	private static void fillWith(javax.swing.JTextField field, File answered) {
		if (answered != null) {
			field.setText(answered.getAbsolutePath());
		}
	}

	/**
	 * Said at the start, after an update the launcher could not install: the reason it kept, and that this is the
	 * version the user had - said at the start because the install's own output went to a log in a hidden folder
	 * (census M.143); nothing when the last update installed or there was none. Out of the window so a suite asks it
	 * (the 2026-10-16 final measurement, CtrmapMainframe 967, negate-if, SURVIVED).
	 */
	private static void sayAnUpdateNotInstalled(java.awt.Component frame, String notInstalled) {
		if (notInstalled != null) {
			Ui.error(frame, "The update could not be installed, so this is the version you had:\n\n" + notInstalled, "Update");
		}
	}

	/**
	 * The new-materials form's mesh spinner bounded to the template region's {@code meshes}: its top lowered to the
	 * last mesh, a number above that brought down to it, and the count said beside it. Out of the form's listener so a
	 * suite asks it: the form is a dialog no recording reaches into (the 2026-10-16 assembly, CtrmapMainframe 3313,
	 * negate-if, SURVIVED).
	 */
	private static void boundMeshSpinner(javax.swing.JSpinner tMesh, javax.swing.JLabel tMeshes, int meshes) {
		javax.swing.SpinnerNumberModel m = (javax.swing.SpinnerNumberModel) tMesh.getModel();
		m.setMaximum(Math.max(0, meshes - 1));
		if (((Number) tMesh.getValue()).intValue() > Math.max(0, meshes - 1)) {
			tMesh.setValue(Math.max(0, meshes - 1));
		}
		tMeshes.setText(meshes == 0 ? "(this region has no map model)" : "(" + meshes + " mesh(es): 0-" + (meshes - 1) + ")");
	}

	private void createAndShowGUI() {
		Workspace.loadWorkspace();
		frame = new JFrame("CTRMap Editor");
		//every dialog this program opens belongs to this window; the seam holds
		//it from here, so nothing below has to reach up for it
		Ui.parent(frame);
		tabs = new JTabbedPane();
		tileEditMasterPnl = new JPanel(new BorderLayout());
		collEditMasterPnl = new JPanel(new BorderLayout());
		mtxEditMasterPnl = new JPanel(new BorderLayout());
		bindLoadedZone(new LoadedZone());
		tools = new ToolSelection();
		//the editor's own answer to "draw it again": the window it owns. Handed
		//to the forms, so none of them has to reach up here for a JFrame - and
		//so a headless suite can hand its own and read back that it was asked.
		redraw = () -> {
			if (frame != null) {
				frame.repaint();
			}
		};
		//what the two views draw, and where the camera looks. Built here because
		//this is where both halves of it now exist.
		//THE CAMERA ARITHMETIC LIVES IN PanelScene3D, not in an anonymous body here.
		//It was five assignments inside this window, which was fine while this window
		//was the only thing that drew a map; the Zone Loader preview draws one too, and
		//the alternative was a second copy of them. The panel is SUPPLIED rather than
		//handed in because it is not built until sixty lines below.
		final Scene3D scene = new ctrmap.humaninterface.PanelScene3D(
			() -> m3DDebugPanel, CM3DComponents);

		final OpenEditors editors = buildOpenEditors();
		final ZoneEditors zoneViews = buildZoneEditors(scene);
		//and the window keeps the flush for its own File > Save and close handler,
		//which run long after this method has returned.
		openEditors = editors;

		//An anonymous class rather than a value, ON PURPOSE and not by accident:
		//the 3D panel is not built until sixty lines below, and this body reads it
		//when it is CALLED - which is after a zone or a record exists to follow.
		//Handing the panel itself here would hand null, which is the mistake this
		//window has already made once (MainframeEdgesTest section 4).
		navigator = new Navigator() {
			@Override
			public void follow(MapObject o) {
				if (m3DDebugPanel != null) {
					m3DDebugPanel.bindNavi(o);
				}
			}

			@Override
			public void resync() {
				if (m3DDebugPanel != null) {
					m3DDebugPanel.navi.synchronizeNavi();
				}
			}
		};
		//THE OPEN ZONE'S HISTORY (F209): every edit made in the zone, whichever editor or tool made it,
		//read from the documents around each gesture. It holds the zone's entities; each editor handed it
		//adds its own documents, so it is built before the first of them - its scope and documents are
		//read through lambdas, so nothing they name needs to exist yet. It starts again by itself when
		//another zone or map opens; reloading a map in place says so through the map view.
		zoneHistory = new ctrmap.humaninterface.UndoHistory(
				ctrmap.humaninterface.ZoneDocuments.scope(loadedZone, () -> mTileMapPanel),
				ctrmap.humaninterface.ZoneDocuments.entities(loadedZone, () -> {
					zoneViews.showEntities(loadedZone.open());
					frame.repaint();
				}));
		mZonePnl = new ZoneLoadingPanel(loadedZone, tools, editors, zoneViews, navigator,
			CtrmapMainframe.this::showWorldEditor, CtrmapMainframe.this::refreshSceneFog, zoneHistory, workspaceOps());
		//the Zone tab IS the zone saver, and the script editor is handed it. Both
		//exist by here: the tab on the line above, and nothing uses the editor until
		//the NPC form below is built over both.
		mScriptPnl = new ScriptEditor(mZonePnl);
		//the zone's script is a document of its history (F209): the NPC wizards replace it, the dialogue editor
		//re-points it, this panel edits it; put back, it is shown here again
		zoneHistory.watch(ctrmap.humaninterface.ZoneDocuments.script(loadedZone, () -> mScriptPnl.loadScript(loadedZone.open().s)));
		mTextEditor = new TextEditor();
		mBuilder = new Builder(CtrmapMainframe.this::showInExtras);

		frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		frame.setLocationByPlatform(true);
		//THESE FOUR MOVED UP HERE from below the map view, and the move is the whole
		//change: every one of them was read by TileMapPanel and by nothing else in
		//the program, so building them first is what lets the map view be HANDED
		//them instead of reaching back up for them. Verified rather than assumed:
		//the scroll pane is a bare JScrollPane, CollEditPanel takes only the tool
		//selection (line above), and H3DRenderingPanel takes the renderable list
		//(a static final, ready at class load) and the same tool selection. The 3D
		//panel starts an animator in its constructor, but its display() does
		//nothing until Workspace.isValid(), which stays false until validate() far
		//below - so starting it earlier draws nothing earlier.
		mTilemapScrollPane = new JScrollPane();
		mCollEditPanel = new CollEditPanel(tools, zoneHistory);
		//NULL WHEN OPENGL CANNOT START (F077): building either was an UnsatisfiedLinkError out of
		//this method, and the window did not open. The tools already treat a null 3D panel as
		//"nothing to follow"; the two views stand as a placeholder saying why.
		GLPanel glPanel = GlSupport.available() ? new GLPanel(mCollEditPanel) : null;
		m3DDebugPanel = GlSupport.available() ? new H3DRenderingPanel(CM3DComponents, tools) : null;
		//A SUPPLIER, not the palette: Workspace Settings replaces the tileset object
		//wholesale, and anything that captured it would go on drawing every region
		//in the old one. Reading the field on each call is what the direct read did.
		final ctrmap.formats.tilemap.Tilemap.TileColors colours = tile -> mTileEditForm.tileset.colorOf(tile);
		mTileMapPanel = new TileMapPanel(loadedZone, tools, scene, mTilemapScrollPane, mCollEditPanel, colours);
		//the map view adds its own regions' tiles, and starts the history again when it replaces them
		mTileMapPanel.useHistory(zoneHistory, this::redrawRegions);
		ctrmap.humaninterface.UndoHistory.settleAroundGestures(() -> settledHistories(zoneHistory,
				gameDataSaves().history(), mTextEditor.history(), workspaceOps().history(), tileset));
		//A BOUND METHOD REFERENCE, not a lambda over the static and not a captured
		//Point. Not a lambda, because the map view exists by this line and the
		//construction-order rule can therefore see and check this handoff - a
		//lambda body is deferred and the rule skips it, which is the right answer
		//for a deferred body and the wrong one for a value that is ready. Not a
		//Point, because the answer changes as the user scrolls and the forms ask at
		//the moment they place something; a captured one would put every new record
		//where the viewport was when the window was built.
		final ViewportCentre viewportCentre = mTileMapPanel::getTileAtViewportCentre;
		mMtxPanel = new MapMatrixPanel();
		mMtxEditForm = new MatrixEditForm(loadedZone, mMtxPanel, zoneHistory);
		//THE SECOND PHASE, and the only order that works: the grid was built above,
		//the form was built over it on the line above this, and now the grid is told
		//which editor is beside it. Neither can be handed the other in a constructor
		//because neither exists when the other is made - see MatrixTools.
		mMtxPanel.setTools(mMtxEditForm);
		JScrollPane mtxScroll = new JScrollPane();
		mCamScrollPane = new JScrollPane();
		//A LAMBDA OVER THE STATIC, chosen deliberately and not by accident: the tool
		//row does not exist yet - it is built from the mouse router, which is built
		//from the tool box, which is built over this form - so there is no order in
		//which one can be handed the other. The body runs when the user picks a tile
		//from the list, by which time the row exists. Section four of
		//MainframeEdgesTest skips lambda bodies for exactly this reason, so this one
		//is chosen in a comment rather than slipped past a rule.
		mTileEditForm = new TileEditForm(tools, () -> worldToolbar.selectSetTool(), mTileMapPanel);
		mPaintForm = new ctrmap.humaninterface.PaintForm(loadedZone, editors, mZonePnl, mTileMapPanel, zoneHistory,
				workspaceOps(), CtrmapMainframe.this::readTheWorkspaceAgain);
		mCamEditForm = new CameraEditForm(redraw, zoneHistory);
		mPropEditForm = new PropEditForm(loadedZone, tools, redraw, navigator, mTileMapPanel,
			CtrmapMainframe.this::switchToolUI, zoneHistory);
		mNPCEditForm = new NPCEditForm(loadedZone, tools, redraw, navigator, viewportCentre,
			mZonePnl, mScriptPnl, mTileMapPanel, CtrmapMainframe.this::switchToolUI, zoneHistory);
		mWarpEditForm = new WarpEditForm(loadedZone, redraw, viewportCentre);
		mTriggerEditForm = new TriggerEditForm(loadedZone, redraw, viewportCentre);
		mGeoEditForm = new GeoEditForm(loadedZone, mTileMapPanel, zoneHistory);
		//THE SECOND PHASE: the map view was built above and the two editors over it,
		//so now it is told which editors show what it loads. Neither can be a
		//constructor argument of the other - see MapEditors.
		mTileMapPanel.setEditors(new ctrmap.humaninterface.MapEditors() {
			@Override
			public void showProps(ctrmap.formats.propdata.GRPropData props,
				ctrmap.formats.propdata.ADPropRegistry reg,
				java.util.List<ctrmap.formats.h3d.texturing.H3DTexture> textures) {
				mPropEditForm.loadDataFile(props, reg, textures);
			}

			@Override
			public void showLooseProps(ctrmap.formats.containers.GR region) {
				mPropEditForm.loadDataFile(region, null);
			}

			@Override
			public void clearEntities() {
				//ALL THREE ENTITY FORMS, which is what this seam's javadoc already
				//promised - "the same clear ZoneEditors does when a zone closes" - and
				//what ZoneEditors.clear() actually does. Only the NPC form was named
				//here, so after File > Open GR Mapfile the warp and trigger forms went
				//on showing, selecting and saving the entities of a zone that
				//loadTileMap had just RELEASED: their saveEntry() writes into a
				//ZoneEntities the Zone tab's Save never reaches, because
				//ZoneLoadingPanel.store answers true the moment loadedZone.open() is
				//null. A warp typed there was lost with no warning - or silently kept,
				//if the user happened to re-pick that zone, since the Zone object is
				//still in the table. Nothing said which.
				//NAMED FORM BY FORM rather than delegating to the zone-close list, which
				//is equivalent today: this path has just LOADED a map, so a future
				//ZoneEditors entry that blanks the map view must not blank it here.
				//Order is ZoneEditors' order, so the two clears read as the same list.
				mNPCEditForm.loadFromEntities(null, null);
				mWarpEditForm.loadFromEntities(null);
				mTriggerEditForm.loadFromEntities(null);
			}
		});
		jsp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
		mTilemapScrollPane.setViewportView(mTileMapPanel);
		mtxScroll.setViewportView(mMtxPanel);
		mCamScrollPane.setViewportView(mCamEditForm);
		mCamScrollPane.setMinimumSize(mCamEditForm.getPreferredSize());
		mCamScrollPane.setPreferredSize(mCamEditForm.getPreferredSize());
		mCamScrollPane.getVerticalScrollBar().setUnitIncrement(16);
		jsp.setLeftComponent(mTilemapScrollPane);
		jsp.setRightComponent(mTileEditForm);

		//each input manager registers itself as its panel's listener; only the
		//tile map's is needed again, by the tool row
		//the editor a tool works inside, and the box that knows which form each
		//tool drives. Both are built here because this is where the parts are;
		//a tool now takes them and reaches for nothing.
		ToolHost toolHost = new ToolHost() {
			@Override
			public void showToolUi(JComponent form) {
				switchToolUI(form);
			}

			@Override
			public void redraw() {
				redraw.all();
			}

			@Override
			public void releaseNavi() {
				//the fifth copy of the same guarded reach, now the same object
				navigator.follow(null);
			}

			@Override
			public ctrmap.humaninterface.TileInspector inspector() {
				return mTileEditForm;
			}

			@Override
			public TileMapPanel map() {
				return mTileMapPanel;
			}
		};
		ToolBox toolBox = new ToolBox(toolHost, mTileEditForm, mGeoEditForm, mNPCEditForm, mPropEditForm,
				mWarpEditForm, mTriggerEditForm, mPaintForm, mCamEditForm, mCamScrollPane);
		TilemapPanelInputManager tilemapInput = new TilemapPanelInputManager(mTileMapPanel, tools, toolBox, mTileEditForm);
		if (m3DDebugPanel != null) {
			new CM3DInputManager(m3DDebugPanel, tools);
		}
		collisionInput(glPanel);
		new MatrixPanelInputManager(mMtxPanel, mMtxEditForm);

		jsp2 = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
		jsp2.setLeftComponent(glPanel != null ? glPanel : GlSupport.placeholder("The 3D collision view"));
		jsp2.setRightComponent(mCollEditPanel);

		jsp3 = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
		jsp3.setLeftComponent(mtxScroll);
		jsp3.setRightComponent(mMtxEditForm);

		worldToolbar = new WorldEditorToolbar(tilemapInput, CtrmapMainframe.this::toggleView,
				CtrmapMainframe.this::toggleSceneFog, zoneHistory);
		JPanel toolbarRows = new JPanel(new java.awt.GridLayout(2, 1));
		toolbarRows.add(worldToolbar);
		toolbarRows.add(buildMapActionsBar());
		tileEditMasterPnl.add(toolbarRows, BorderLayout.NORTH);
		tileEditMasterPnl.add(jsp, BorderLayout.CENTER);

		collEditMasterPnl.add(buildCollisionActionsBar(), BorderLayout.NORTH);   //the tab's own actions (census M.146)
		collEditMasterPnl.add(jsp2, BorderLayout.CENTER);

		mtxEditMasterPnl.add(jsp3);

		zoneTabPnl = buildZoneTab();

		JPanel extrasTabPnl = buildExtrasTab();

		tabs.add("World Editor", tileEditMasterPnl);
		tabs.add("Collision Editor", collEditMasterPnl);
		tabs.add("Matrix Editor", mtxEditMasterPnl);
		tabs.add("Zone Loader", zoneTabPnl);
		tabs.add("Game Data", buildGameDataTab(buildGameDataPanel()));
		tabs.add("Script Editor (experimental)", mScriptPnl.withStatus());
		tabs.add("Extras", extrasTabPnl);
		tabs.add("Text Editor", mTextEditor.withUndo());
		tabs.setToolTipTextAt(tabs.indexOfComponent(tileEditMasterPnl),
				"The map itself: tile BEHAVIOR (walkable/encounters/water/warps - the colored grid) plus props, NPCs, warps, triggers and cameras. Edit tool inspects a tile; Set/Fill paint tile types.");
		tabs.setToolTipTextAt(tabs.indexOfComponent(collEditMasterPnl),
				"The 3D collision SURFACE the player stands on (heights, slopes, stairs). Behavior bytes live in the World Editor; this shapes the physical ground. The Tile Painter edits both at once.");
		tabs.setToolTipTextAt(tabs.indexOfComponent(mtxEditMasterPnl),
				"How map regions tile together into the zone's world grid.");
		tabs.setToolTipTextAt(tabs.indexOfComponent(zoneTabPnl), "Pick a zone in the zone list on the right and press \"Load this zone\" - this is how zones are opened.");

		//Ctrl+Z / Ctrl+Y while the World Editor tab has focus: the history of whatever is being edited
		//there - the open zone's, unless the focus is inside an editor that owns one (F209). It was
		//the tile history whatever the user was editing.
		javax.swing.InputMap tim = tileEditMasterPnl.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
		tim.put(javax.swing.KeyStroke.getKeyStroke("control Z"), "undo");
		tim.put(javax.swing.KeyStroke.getKeyStroke("control Y"), "redo");
		tileEditMasterPnl.getActionMap().put("undo", new javax.swing.AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				activeHistory(e).act(frame, true);
			}
		});
		tileEditMasterPnl.getActionMap().put("redo", new javax.swing.AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				activeHistory(e).act(frame, false);
			}
		});

		frame.getContentPane().add(tabs);

		JMenuBar menubar = buildMenuBar();
		frame.setJMenuBar(menubar);

		CM3DComponents.add(mTileMapPanel);
		CM3DComponents.add(mPropEditForm);
		CM3DComponents.add(mNPCEditForm);

		frame.setSize(1280, 720 + menubar.getHeight());
		frame.setMinimumSize(frame.getSize());
		frame.setExtendedState(firstState);
		frame.setVisible(true);

		//an update applied by the launcher leaves its unpacked copy behind,
		//because the JVM that applied it was running out of that folder
		File installDir = ctrmap.update.Updater.installDir();
		ctrmap.update.Updater.holdRunLock(installDir); //so an update knows when we are gone
		ctrmap.update.Updater.sweep(installDir);
		sayAnUpdateNotInstalled(frame, ctrmap.update.Updater.takeApplyFailure(installDir));

		mTileMapPanel.addPropertyChangeListener(new PropertyChangeListener() {
			@Override
			public void propertyChange(PropertyChangeEvent arg0) {
				SwingUtilities.invokeLater(new Runnable() {
					@Override
					public void run() {
						frame.repaint();
					}
				});
			}
		});
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				if (openEditors.saveAll(true)) {
					Workspace.cleanUnchanged();
					Workspace.saveWorkspace();
					//a staged update for the Windows bundle installs itself as we
					//go; harmless and a no-op for every other kind of install
					ctrmap.update.Updater.startAppImageApply(ctrmap.update.Updater.installDir());
					System.exit(0);
				}
			}
		});
		frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("F2"), "switch2D");
		frame.getRootPane().getActionMap().put("switch2D", new AbstractAction("switch2D") {
			@Override
			public void actionPerformed(ActionEvent e) {
				showView3D(false);
			}
		});
		frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("F3"), "switch3D");
		frame.getRootPane().getActionMap().put("switch3D", new AbstractAction("switch3D") {
			@Override
			public void actionPerformed(ActionEvent e) {
				showView3D(true);
			}
		});
		tools.switchTo(toolBox::set);
		frame.getRootPane().setFocusable(true);
		frame.addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent componentEvent) {
				adjustSplitPanes();
			}
		});
		SwingUtilities.invokeLater(() -> {
			adjustSplitPanes();
		});

		//A first-time user gets the wizard, not a list of missing archive names.
		//The update check waits until setup is out of the way, because it arrives
		//on a background thread whenever the network answers and would otherwise
		//throw a modal dialog over the middle of the wizard.
		if (ctrmap.setup.SetupWizard.shouldRunOnStartup()) {
			ctrmap.setup.SetupWizard.show(frame, CtrmapMainframe.this::onWorkspaceOpened);
		} else {
			onWorkspaceOpened(Workspace.validate(frame));
		}
		ctrmap.update.UpdateUI.checkOnStartup(frame);
	}

	/**
	 * The menu bar: every menu and every item, in the order the user sees
	 * them, each wired to the named method that does the work. This is the
	 * ONLY place a menu item exists - the items are locals here, not fields,
	 * so nothing else in the program can reach one - and MainframeShapeTest
	 * builds this headless and pins the whole tree: labels, order, separators
	 * and one action per item. Building it touches no other widget; the
	 * actions read the editor's panels when they run, not when the menu is
	 * made.
	 *
	 * <p>Menus are SECONDARY access grouped by subject. The primary homes are
	 * the World Editor toolbar, the Zone Loader bar and the Game Data tab.
	 */
	private JMenuBar buildMenuBar() {
		JMenuBar bar = new JMenuBar();

		JMenu file = new JMenu("File");
		file.add(item("Open GR Mapfile", CtrmapMainframe.this::openGrAction));
		file.add(item("Open MapMatrix", CtrmapMainframe.this::openMapMatrixAction));
		file.add(item("Open Zone", CtrmapMainframe.this::openZoneAction,
				"Opens a single loose ZO file. To load a map from the game, use the zone list in the \"Zone Loader\" tab instead."));
		//CTRL+S, AND CTRL+D FOR DEPLOY. Nothing in this menu bar had a key, in an editor
		//whose other writer is the window close handler, and whose edit-and-test cycle
		//ends in Deploy every single time.
		file.add(withKey(item("Save", CtrmapMainframe.this::saveAllAction), java.awt.event.KeyEvent.VK_S));
		file.add(item("Pack Workspace", Workspace::packWorkspace));
		file.add(withKey(item("Deploy to emulator (mod)...", CtrmapMainframe.this::deployModAction),
			java.awt.event.KeyEvent.VK_D));

		JMenu map = new JMenu("Map");
		map.add(item("Map Builder (this zone)", CtrmapMainframe.this::openMapBuilderAction));
		map.add(item(AreaAction.BLANK_CANVAS));
		map.add(item(AreaAction.RESIZE_MAP));
		map.add(item(AreaAction.FOG_AND_LIGHTING));
		map.add(item(AreaAction.MAKE_INDEPENDENT));
		map.addSeparator();
		map.add(item(AreaAction.IMPORT_MAP_MODEL));
		map.add(item(AreaAction.EXPORT_MAP_OBJ));
		map.add(item(AreaAction.IMPORT_MAP_OBJ));
		map.add(item(AreaAction.OBJ_TO_COLLISIONS));
		map.add(item("Tileset Editor", CtrmapMainframe.this::tilesetEditorAction));

		JMenu zone = new JMenu("Zone");
		for (AreaAction a : AreaAction.values()) {
			if (a.area == AreaAction.Area.ZONE) {
				zone.add(item(a));
			}
		}

		JMenu data = new JMenu("Game Data");
		data.add(item("Edit trainer (party/battle)...",
				CtrmapMainframe.this::openTrainers));
		data.add(item("Edit battle facility opponents...", CtrmapMainframe.this::openFacilityOpponents));
		data.add(item("Edit shop inventories (Marts)...", CtrmapMainframe.this::openShops));
		data.add(item("Edit items (price, effects, name)...", CtrmapMainframe.this::openItems));
		data.add(item(AreaAction.ENCOUNTERS));

		JMenu options = new JMenu("Options");
		//the wizard sits above the raw path dialog it replaces for beginners:
		//same job, but it explains itself and checks what you picked
		options.add(item("Setup wizard...", () -> ctrmap.setup.SetupWizard.show(frame, CtrmapMainframe.this::onWorkspaceOpened),
				"Point CTRMap at your game, step by step."));
		options.addSeparator();
		options.add(item("Workspace settings", CtrmapMainframe.this::workspaceSettingsAction));
		options.add(item("Restore from pristine backup...", CtrmapMainframe.this::restoreFromVaultAction,
				"Put the whole game, or one damaged archive, back as it was when CTRMap first copied it."));
		options.add(item("Clean workspace", CtrmapMainframe.this::cleanWorkspaceAction));

		JMenu help = new JMenu("Help");
		help.add(item("Quick start guide", CtrmapMainframe.this::quickStartAction,
			"The guide that ships beside the program: what to do first, in order."));
		help.add(item("Check for updates...", () -> ctrmap.update.UpdateUI.checkNow(frame)));
		help.addSeparator();
		help.add(item("Support/Issue tracker", CtrmapMainframe.this::issueTrackerAction));
		help.add(item("About", CtrmapMainframe.this::aboutAction));

		bar.add(file);
		bar.add(map);
		bar.add(zone);
		bar.add(data);
		bar.add(options);
		bar.add(help);
		return bar;
	}

	/** The same item, with the platform's menu key and the given letter. */
	private static JMenuItem withKey(JMenuItem it, int keyCode) {
		//ASKED OF THE TOOLKIT ONLY WHEN THERE IS ONE. getMenuShortcutKeyMask throws
		//HeadlessException, and this menu bar is built headless by the suite that reads
		//it - which is how this was caught one minute after it was written rather than by
		//the owner. Ctrl is the answer on every platform this ships to anyway.
		int mask = java.awt.event.InputEvent.CTRL_MASK;
		if (!java.awt.GraphicsEnvironment.isHeadless()) {
			mask = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
		}
		it.setAccelerator(javax.swing.KeyStroke.getKeyStroke(keyCode, mask));
		return it;
	}

	/**
	 * Opens QUICKSTART.md, which ships beside the program and had no door.
	 *
	 * <p>package.ps1 copies it into both release layouts, and nothing in the program
	 * ever mentioned it: a first-run user who got past the setup wizard was handed an
	 * eight-tab window and left to find the zone list on the fourth tab by themselves.
	 */
	private void quickStartAction() {
		java.util.List<File> places = ctrmap.update.Updater.besideTheProgram("QUICKSTART.md",
				ctrmap.update.Updater.installDir(), new File(System.getProperty("user.dir")));
		File guide = null;
		for (File place : places) {
			if (place.isFile()) {
				guide = place;
				break;
			}
		}
		if (guide == null) {
			StringBuilder looked = new StringBuilder();
			for (File place : places) {
				looked.append("\n  ").append(place.getPath());
			}
			Ui.error(frame, "QUICKSTART.md is not beside the program. Looked in:" + looked, "Quick start guide");
			return;
		}
		try {
			java.awt.Desktop.getDesktop().open(guide);
		} catch (Exception cannotOpen) {
			Ui.error(frame, "Could not open " + guide.getAbsolutePath() + ":\n"
				+ Ui.reason(cannotOpen), "Quick start guide");
		}
	}

	/** One menu item, wired to the one thing it does. */
	private static JMenuItem item(String label, Runnable action) {
		JMenuItem it = new JMenuItem(label);
		it.addActionListener(e -> action.run());
		return it;
	}

	private static JMenuItem item(String label, Runnable action, String tooltip) {
		JMenuItem it = item(label, action);
		it.setToolTipText(tooltip);
		return it;
	}

	/**
	 * The World Editor's second toolbar row: the map-level actions. The tool
	 * row above it ({@link WorldEditorToolbar}) holds the per-tile tools; each
	 * button here is an {@link AreaAction}, and so is in a menu as well.
	 */
	private JToolBar buildMapActionsBar() {
		return areaBar(" Map:  ", AreaAction.Area.MAP);
	}

	/** An area's toolbar row: its label, then its actions' buttons in the order they are declared. */
	private JToolBar areaBar(String label, AreaAction.Area area) {
		JToolBar bar = new JToolBar();
		bar.setFloatable(false);
		bar.add(new JLabel(label));
		for (AreaAction a : AreaAction.values()) {
			if (a.area == area) {
				javax.swing.JButton b = barButton(a.barLabel, a.tooltip, () -> a.run.accept(this));
				b.putClientProperty(AreaAction.class, a);
				bar.add(b);
			}
		}
		return bar;
	}

	/** The menu item for an area action: its menu label, its tooltip, its handler, and which action it is. */
	private JMenuItem item(AreaAction a) {
		JMenuItem it = item(a.menuLabel, () -> a.run.accept(this), a.tooltip);
		it.putClientProperty(AreaAction.class, a);
		return it;
	}

	/**
	 * An action of the Map or Zone area, declared once and drawn wherever it appears (F223).
	 *
	 * <p>The menus and the two area toolbars were written by hand from separate lists: the
	 * comments saying how many each repeated were wrong both ways ("these five" over a menu
	 * holding four, "these seven" over a row of six), the battle facility was offered as two
	 * modes on the bar and as one in the menu, and nothing compared the two. The menu item and
	 * the button are both made from one of these now - a label each, one tooltip, one handler -
	 * and each area's row is its actions in this order. MainframeShapeTest asks that every
	 * button on the two rows is one of these, and that each is on one menu item and one button.
	 */
	private enum AreaAction {
		BLANK_CANVAS(Area.MAP, "Blank map canvas (this zone)...", "Blank canvas",
				"Replace this zone's map with a blank canvas cloned from a template route.", CtrmapMainframe::blankCanvasAction),
		RESIZE_MAP(Area.MAP, "Resize map (this zone)...", "Resize map",
				"Resize this zone's map (grow/shrink its region grid).", CtrmapMainframe::resizeMapAction),
		FOG_AND_LIGHTING(Area.MAP, "Edit area fog & lighting...", "Fog & lighting",
				"Pick a GameFreak atmosphere - its fog and lighting - with live preview, or hand-tune the fog.",
				CtrmapMainframe::fogAndLightingAction),
		ENCOUNTERS(Area.MAP, "Edit wild encounters (this zone)...", "Encounters",
				"Edit this zone's wild Pokemon encounter slots.", CtrmapMainframe::openEncounters),
		MAKE_INDEPENDENT(Area.MAP, INDEPENDENT_TITLE + " (own map, own area)...", "Make independent",
				"Give this zone its own private map and its own area, so edits stop affecting the source town.",
				CtrmapMainframe::makeZoneIndependentAction),
		//THE BLENDER ROUND TRIP, ON THE MAP'S OWN ROW (census M.146): these four were Map-menu items and nothing
		//else, so the way to bring a map or a collision in from Blender was found only in a menu
		IMPORT_MAP_MODEL(Area.MAP, "Import map model (.bch)...", "Import model",
				"Replace a map region's 3D model with a .bch model file.", CtrmapMainframe::importMapModelAction),
		EXPORT_MAP_OBJ(Area.MAP, "Export map region to OBJ (Blender)...", "Export OBJ",
				"Export a map region's 3D model as a Wavefront OBJ to edit in Blender.", CtrmapMainframe::exportMapObjAction),
		IMPORT_MAP_OBJ(Area.MAP, "Import OBJ into map region (Blender)...", "Import OBJ",
				"Bring a Blender-edited OBJ back into the map region it was exported from.", CtrmapMainframe::importMapObjAction),
		//FIRST OF THE ZONE ROW, AND IN THE ZONE MENU (1.0.4): the zone list is the only way to open a zone,
		//and a tool shown over it left nothing in either place that went back to it
		ZONE_LIST(Area.ZONE, "Zone list (pick a zone to load)", "Zone list",
				"The zone list in the Zone Loader tab, to pick a zone and load it - from wherever you are, any tool over it put away.",
				CtrmapMainframe::showZoneListAction),
		CONNECT_ZONES(Area.ZONE, "Connect zones through a warp...", "Connect zones",
				"Wire a warp in one zone to a warp in another, both ways, so the trip works there AND back.",
				CtrmapMainframe::connectZonesAction),
		RENAME_ZONE(Area.ZONE, "Rename zone (in-game name)...", "Rename",
				"Rename the loaded zone's in-game location banner.", CtrmapMainframe::renameZoneAction),
		EMPTY_ZONE(Area.ZONE, "Empty zone (clear contents)...", "Empty",
				"Clear the loaded zone's NPCs, warps, triggers and furniture (keeps map + script).",
				CtrmapMainframe::emptyZoneAction),
		FIND_REUSABLE_ZONES(Area.ZONE, "Find reusable base zones...", "Find reusable zones",
				"Scan for unused base zones you can safely repurpose for new areas.", CtrmapMainframe::findReusableZonesAction),
		//THE STOCK COUNT IS ORAS'S, and right: the action refuses every game but the one whose
		//stock layout was measured (Feature.ZONE_APPEND), and the number comes from its profile
		REMOVE_ADDED_ZONES(Area.ZONE,
				"Remove added zones (restore stock " + ctrmap.formats.codepatch.ZoneLimitPatch.BASE_ZONES + ")...",
				"Remove added zones", "Delete all added zones (index " + ctrmap.formats.codepatch.ZoneLimitPatch.BASE_ZONES
				+ "+) and restore the stock ZoneData layout.", CtrmapMainframe::removeAddedZonesAction),
		BATTLE_FACILITY(Area.ZONE, "Custom battle facility here...", "Custom battle facility",
				"Build a custom battle facility here: INDEPENDENT script-driven battles with your own trainers"
				+ " (vanilla untouched), or clone a retail facility's full engine (shared opponent pools)."
				+ " Tower, dojo, gauntlet - whatever the zone should host.", CtrmapMainframe::setupFacilityAction),
		OBJ_TO_COLLISIONS(Area.COLLISION, "OBJ to collisions", "OBJ to collisions",
				"Build a region's collision triangles from a Wavefront OBJ.", CtrmapMainframe::objToCollisionsAction);

		/** Which area's toolbar row the action's button is on. */
		public enum Area {
			MAP, ZONE, COLLISION
		}

		public final Area area;
		public final String menuLabel;
		public final String barLabel;
		public final String tooltip;
		/** What the action does, to the window that drew it. */
		final java.util.function.Consumer<CtrmapMainframe> run;

		AreaAction(Area area, String menuLabel, String barLabel, String tooltip, java.util.function.Consumer<CtrmapMainframe> run) {
			this.area = area;
			this.menuLabel = menuLabel;
			this.barLabel = barLabel;
			this.tooltip = tooltip;
			this.run = run;
		}
	}

	/** A toolbar button that does one thing and never takes the focus off the map. */
	private static javax.swing.JButton barButton(String label, String tooltip, Runnable action) {
		javax.swing.JButton b = new javax.swing.JButton(label);
		b.setToolTipText(tooltip);
		b.setFocusable(false);
		b.addActionListener(e -> action.run());
		return b;
	}

	// ---------------------------------------------- the Zone Loader tab

	/**
	 * The right-hand column of the Zone Loader tab: the zone list, which is the only way to open a
	 * zone, or a zone tool shown in its place - always with a way back to the list (see ToolHost).
	 */
	private ctrmap.humaninterface.ToolHost zoneHost;

	/**
	 * Shows a zone tool in that column, under its name, with a way back.
	 *
	 * <p>The column is where zone work belongs - the browser lives there and so does
	 * anything that acts on a zone. A null tool means it refused and has said why.
	 */
	private void inZoneColumn(String title, JComponent tool) {
		if (zoneHost != null && zoneHost.show(title, tool, null, null)) {
			selectTab("Zone Loader");
		}
	}

	/** Back to the zone list. */
	private void closeZoneColumnTool() {
		if (zoneHost != null) {
			zoneHost.goHome();
		}
	}

	/**
	 * The zone list, from anywhere: the Zone Loader tab, with whatever tool was over the list put
	 * away and the list given the keyboard. On the zone actions bar and in the Zone menu, so the one
	 * way to open a zone is never more than a click from wherever the user is.
	 */
	private void showZoneListAction() {
		selectTab("Zone Loader");
		if (zoneHost != null && zoneHost.goHome() && zoneHost.home() instanceof ctrmap.humaninterface.ZoneBrowserPane) {
			((ctrmap.humaninterface.ZoneBrowserPane) zoneHost.home()).zoneRows().requestFocusInWindow();
		}
	}

	/** The Zone Loader tab: the zone panel, its actions above it, its preview beside it. */
	private JPanel buildZoneTab() {
		return buildZoneTab(mZonePnl, loadedZone, tools);
	}

	/**
	 * The Zone Loader tab, built around whatever zone panel it is given.
	 *
	 * <p>THE ZONE BROWSER LIVES HERE, in the tab, in its empty right-hand half: a
	 * list you scroll with the arrow keys, a live preview of the row you are on, and
	 * a button that opens it. That is what was asked for at the start - "like the
	 * preview for the fog and lighting, where you can easily scroll through several
	 * things and see the preview live before selecting something" - and it took three
	 * attempts to build it. A dialog behind a button was not it; a bubble floating
	 * beside the dropdown was not it; and a preview pane fed by the DROPDOWN could
	 * never be it, because arrowing down that dropdown loads every zone it passes.
	 *
	 * <p>It takes the panel as an argument so a suite can build this tab and look at
	 * what is in it without a game, a display or the window's statics. The failure
	 * being guarded against is the preview being ABSENT, and absence is exactly what
	 * a structural check can see.
	 */
	private JPanel buildZoneTab(JComponent zonePanel, LoadedZone owner,
			ctrmap.humaninterface.tools.ToolSelection heldTool) {
		JPanel tab = new JPanel(new BorderLayout());
		tab.add(buildZoneActionsBar(), BorderLayout.NORTH);
		tab.add(zonePanel, BorderLayout.CENTER);
		ctrmap.humaninterface.ZoneBrowserPane browser = new ctrmap.humaninterface.ZoneBrowserPane(owner, heldTool);
		zoneHost = new ctrmap.humaninterface.ToolHost("the zone list", browser, true);
		tab.add(workspaceOps().around(zoneHost), BorderLayout.EAST);
		if (zonePanel instanceof ZoneLoadingPanel) {
			ZoneLoadingPanel loading = (ZoneLoadingPanel) zonePanel;
			loading.useBrowser(browser);
			//ONE COLUMN FOR "WHICH ZONE AM I WORKING ON". Reported, and it was mine: the
			//browser arrived as a self-contained column on the right and "Clone zone..." and
			//"Add zones..." stayed in the form's top-left corner, so choosing a zone, copying
			//one and making new ones ended up in opposite corners of the same tab. The owner
			//asked for a preview and never asked for the load button to move; the placement
			//came with it unbidden, and then the rest was left behind. Moving them here -
			//adding a component to a container takes it out of its old one - puts the whole
			//task in one place, beside the list every one of them acts on.
			for (javax.swing.JButton b : loading.zoneCreationButtons()) {
				browser.addAction(b);
			}
		}
		return tab;
	}

	/** The Collision Editor's actions row: each button is an {@link AreaAction}, and so is in a menu too. */
	private JToolBar buildCollisionActionsBar() {
		return areaBar(" Collision:  ", AreaAction.Area.COLLISION);
	}

	/** The Zone Loader's actions row: each button is an {@link AreaAction}, and so is in the Zone menu too. */
	private JToolBar buildZoneActionsBar() {
		return areaBar(" Zone actions:  ", AreaAction.Area.ZONE);
	}

	// --------------------------------------------------- the Extras tab

	/** The Extras tab: the seldom-used tools, and whichever one is open. */
	private JPanel buildExtrasTab() {
		return buildExtrasTab(new ExtrasPanel(loadedZone).withUndo(), mBuilder.withUndo());
	}

	/**
	 * The Extras tab, built around whatever panels it is given.
	 *
	 * <p>THE SELDOM-USED TOOLS LIVE HERE rather than in windows of their own: the raw
	 * archive browser, the tileset editor and the workspace settings. That is the
	 * owner's rule - a feature belongs to the part of the UI it is about, and seldom
	 * used means Extras, not a window - and it is enforced now rather than remembered:
	 * build.ps1 refuses a tree that opens a top-level window nobody argued for.
	 *
	 * <p>It takes its panels as arguments so a suite can build this tab with stand-ins
	 * and read back what is in it, with no game, no display and no workspace.
	 */
	private JPanel buildExtrasTab(JComponent extras, JComponent browser) {
		JPanel tab = new JPanel(new BorderLayout());
		tab.add(buildExtrasBar(), BorderLayout.NORTH);
		extrasBrowser = browser;
		extrasHost = new ctrmap.humaninterface.ToolHost("Extras", extras, true);
		tab.add(extrasHost, BorderLayout.CENTER);
		return tab;
	}

	/** Where an Extras tool is shown, with the Extras tools as its home (see ToolHost). */
	private ctrmap.humaninterface.ToolHost extrasHost;
	private JComponent extrasBrowser;

	/**
	 * Shows a seldom-used tool in the Extras tab, under its name, with a way back.
	 *
	 * <p>A pane that came from the form designer carries a menu bar it used to hang off
	 * a JFrame; it goes above the pane rather than being dropped, which would take the
	 * tileset editor's whole File menu with it and say nothing.
	 */
	private void showInExtras(String title, JComponent tool) {
		showInExtras(title, tool, tool instanceof ctrmap.humaninterface.FormPanel
			? ((ctrmap.humaninterface.FormPanel) tool).getJMenuBar() : null);
	}

	/** The same, with the tool's menu given - a tool shown inside its Undo and Redo is no FormPanel itself. */
	private void showInExtras(String title, JComponent tool, javax.swing.JMenuBar menu) {
		//IT ASKS before the tool is left: these panes used to be windows and did their saving in
		//windowClosing, so a pane that answers false has an edit it will not discard - and leaving
		//anyway is how the window version lost one
		java.util.function.BooleanSupplier mayLeave = tool instanceof ctrmap.humaninterface.FormPanel
				? ((ctrmap.humaninterface.FormPanel) tool)::closeRequested : null;
		if (extrasHost != null && extrasHost.show(title, tool, menu, mayLeave)) {
			selectTab("Extras");
		}
	}


	/**
	 * The Extras bar: everything that opens in this tab, offered from this tab.
	 *
	 * <p>It offered one of the three. The tileset editor and the workspace settings open
	 * here too and could only be reached from the Map and Options menus, so a user who
	 * came to Extras looking for either found a panel of mass-edit buttons and no sign of
	 * them - the placement rule kept in half and broken in the other half.
	 */
	private JToolBar buildExtrasBar() {
		JToolBar bar = new JToolBar();
		bar.setFloatable(false);
		bar.add(barButton("Raw archive browser (Builder)", "Browse raw GARC entries and their subfiles - advanced, rarely needed.", CtrmapMainframe.this::showBuilderAction));
		bar.add(barButton("Tileset editor", "Edit the tile database: what each tile byte means to the game.", CtrmapMainframe.this::tilesetEditorAction));
		bar.add(barButton("Workspace & paths", "Where the game, the workspace and the emulator live.", CtrmapMainframe.this::workspaceSettingsAction));
		return bar;
	}

	/** Opens the raw archive browser where it lives: in the Extras tab. */
	private void showBuilderAction() {
		showInExtras("Raw archive browser (Builder)", extrasBrowser);
	}

	// ------------------------------------------------------- the 2D/3D view

	/** Puts the 2D map or the 3D scene in the World Editor's view, and keeps the toggle honest. */
	private void showView3D(boolean on) {
		setGraphicUI(!on ? mTilemapScrollPane
				: m3DDebugPanel != null ? m3DDebugPanel : GlSupport.placeholder("The 3D world view"));
		worldToolbar.setView3D(on);
	}

	/**
	 * The toolbar's "3D view" toggle: what is DISPLAYED is the truth, not the toggle's own state.
	 * Asked of the 2D map, which is always there: without OpenGL the 3D side is a placeholder, and a
	 * fresh one each time, so "is it the 3D panel" would answer no while it showed.
	 */
	private void toggleView() {
		showView3D(jsp.getLeftComponent() == mTilemapScrollPane);
	}

	/**
	 * Wire a warp in one zone to a warp in another, both ways - in the Zone Loader tab, beside
	 * the zone it is about.
	 *
	 * <p>On the zone actions bar rather than the warp form: the warp form edits ONE
	 * warp of the loaded zone, and this is about two zones, only one of which is
	 * open. Putting it where a zone-level action lives is also what lets it be used
	 * without loading either end.
	 *
	 * <p>It was a modal window. The rule here is that a feature lives in the part of
	 * the UI it belongs to, and wiring two zones together belongs with the zone list.
	 */
	private void connectZonesAction() {
		inZoneColumn("Connect zones", ctrmap.humaninterface.ZoneLinkDialog.panel(
			frame, game, loadedZone, () -> {
				closeZoneColumnTool();
				reopenIfWritten(); //a link writes both ends through containers of its own (F234)
			}, workspaceOps(), CtrmapMainframe.this::readTheWorkspaceAgain));
	}

	/**
	 * The open zone, read again when an action just wrote its file through a container of its own
	 * (F234) - see {@link ZoneLoadingPanel#reopenIfChangedOnDisk}. A no-op when nothing changed.
	 */
	private void reopenIfWritten() {
		if (mZonePnl != null) {
			mZonePnl.reopenIfChangedOnDisk();
		}
	}

	/** The fog and lighting editor, in the World Editor's tool column. */
	private void fogAndLightingAction() {
		JComponent editor = ctrmap.humaninterface.AreaLightingDialog.panel(frame, loadedZone,
			worldTextures(), () -> showWorldEditor(), CtrmapMainframe.this::refreshSceneFog, workspaceOps(),
			CtrmapMainframe.this::readTheWorkspaceAgain);
		if (editor == null) {
			return;                        //it has already said why
		}
		switchToolUI(editor);
		selectTab("World Editor");
	}

	/**
	 * Hides or shows the area's fog in the 3D view. VIEWING ONLY - it writes
	 * nothing, which is the difference between this and Fog & lighting.
	 *
	 * <p>The PANEL holds the truth and the toggle follows it, exactly as the
	 * 2D/3D switch does, so the button cannot come to disagree with what is on
	 * screen.
	 */
	private void toggleSceneFog() {
		if (m3DDebugPanel == null) {
			return;
		}
		m3DDebugPanel.setFogSuppressed(!m3DDebugPanel.isFogSuppressed());
		worldToolbar.setFogSuppressed(m3DDebugPanel.isFogSuppressed());
	}

	/** Shows the World Editor tab: the map that was just loaded, not the form it was picked from. */
	private void showWorldEditor() {
		tabs.setSelectedComponent(tileEditMasterPnl);
	}

	/**
	 * Puts a tool's form on the right of the World Editor's split. The forms
	 * are taller than the visible side panel on small screens, so one that is
	 * not already a scroll pane is wrapped in one to keep its bottom controls
	 * reachable. Each tool calls this as it starts.
	 */
	private void switchToolUI(JComponent rightComponent) {
		if (!mayLeave(jsp.getRightComponent(), rightComponent)) {
			return;   //the pane being replaced holds an edit it would not let go of
		}
		if (rightComponent instanceof JScrollPane) {
			jsp.setRightComponent(rightComponent);
		} else {
			JScrollPane scroll = new JScrollPane(rightComponent);
			scroll.getVerticalScrollBar().setUnitIncrement(16);
			scroll.setBorder(null);
			//keep the divider maths of adjustSplitPanes() intact: advertise the
			//form's width plus room for the vertical scrollbar
			Dimension pref = rightComponent.getPreferredSize();
			scroll.setPreferredSize(new Dimension(pref.width + scroll.getVerticalScrollBar().getPreferredSize().width + 3, pref.height));
			jsp.setRightComponent(scroll);
		}
		adjustSplitPanes();
		frame.revalidate();
	}

	/**
	 * Whether the pane in the World Editor's tool column may be replaced by {@code incoming}: a
	 * {@link ctrmap.humaninterface.FormPanel} there - bare or in the scroll pane this column wraps
	 * it in - is ASKED, as the Extras tab's host asks before it leaves a tool (F105). This seam replaced
	 * whatever was there with no question, so the registry editors opened into it lost the
	 * entry being edited on any tool switch, zone switch or second Edit registry data click.
	 * Re-showing the pane already there asks nothing.
	 */
	private static boolean mayLeave(java.awt.Component outgoing, JComponent incoming) {
		java.awt.Component pane = outgoing instanceof JScrollPane ? ((JScrollPane) outgoing).getViewport().getView() : outgoing;
		java.awt.Component next = incoming instanceof JScrollPane ? ((JScrollPane) incoming).getViewport().getView() : incoming;
		if (pane == null || pane == next || !(pane instanceof ctrmap.humaninterface.FormPanel)) {
			return true;
		}
		return ((ctrmap.humaninterface.FormPanel) pane).closeRequested();
	}

	/** Puts the 2D map or the 3D scene on the left of the World Editor's split. */
	private void setGraphicUI(JComponent comp) {
		jsp.setLeftComponent(comp);
		adjustSplitPanes();
		frame.revalidate();
	}

	// ------------------------------------------------------------ File menu

	/**
	 * Where the file dialogs remember the folder they were last in: ONE node,
	 * named after this class, with one key per dialog. The listeners these
	 * dialogs came from were anonymous classes that keyed the node on
	 * {@code getClass().getName()} - that is "ctrmap.CtrmapMainframe$5", a
	 * name the compiler hands out by position in the file, so adding an
	 * anonymous class above one silently moved its folder and the dialog
	 * forgot where it had been. Nothing can key on a compiler-numbered name
	 * from a static method, which is why these are static methods. (The
	 * folders remembered under the old names are left behind once; a dialog
	 * then starts from "." exactly as on a fresh install.)
	 */
	private static Preferences fileDialogPrefs() {
		return Preferences.userRoot().node(CtrmapMainframe.class.getName());
	}

	/** A single-file open dialog that starts where the same dialog last ended. */
	private static JFileChooser openDialog(String prefKey, String title) {
		JFileChooser jfc = new JFileChooser(fileDialogPrefs().get(prefKey, new File(".").getAbsolutePath()));
		jfc.setDialogTitle(title);
		jfc.setFileSelectionMode(JFileChooser.FILES_ONLY);
		jfc.setMultiSelectionEnabled(false);
		return jfc;
	}

	/**
	 * A folder dialog that starts at {@code start}. THE ONE OTHER CHOOSER (census L.31): with openDialog it is
	 * the only place this window makes one, so a dialog cannot be written without the memory.
	 */
	private static JFileChooser folderDialog(String start, String title) {
		JFileChooser jfc = new JFileChooser(start);
		jfc.setDialogTitle(title);
		jfc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		return jfc;
	}

	/** Shows {@code jfc} to save; the file named, its folder remembered under {@code prefKey}, or null. */
	private File pickedToSave(JFileChooser jfc, String prefKey) {
		if (ctrmap.Ui.choose(frame, jfc, true) == null) {
			return null;
		}
		File f = jfc.getSelectedFile();
		if (f != null) {
			fileDialogPrefs().put(prefKey, f.getParent());
		}
		return f;
	}

	/** Shows {@code jfc}; the file picked, remembered under {@code prefKey}, or null. */
	private File picked(JFileChooser jfc, String prefKey) {
		if (ctrmap.Ui.choose(frame, jfc, false) == null) {
			return null;
		}
		File f = jfc.getSelectedFile();
		if (f != null) {
			fileDialogPrefs().put(prefKey, f.getParent());
		}
		return f;
	}

	private void openGrAction() {
		if (!gameOpen("Open GR Mapfile")) {
			return;
		}
		File f = picked(openDialog("LAST_DIR_GR", "Open GR/153/bin mapfile"), "LAST_DIR_GR");
		if (f != null) {
			//ask every editor that holds unsaved work first, then replace what is
			//loaded - the decision is openChosenGr, and it is out there rather than
			//here for the same reason openChosenMapMatrix is: behind this chooser
			//nothing can drive it, so the only thing saying the ask happened was a
			//comment. What is left here is the part that needs the panels.
			//READ FIRST, ASK SECOND (census M.100): the file was read after every open editor had been asked to
			//save, so a file that is not a map region cost the user the prompts - and then its refusal reached
			//nobody but the catch-all. Opening the container reads its header and table, and writes nothing
			GR mainGR;
			try {
				mainGR = new GR(f, game);
			} catch (RuntimeException notARegion) {
				Ui.error(frame, f.getName() + " cannot be opened as a map region:\n" + Ui.reason(notARegion)
						+ "\n\nNothing was changed.", "Open GR Mapfile");
				return;
			}
			openChosenGr(openEditors, () -> {
				frame.setTitle("GfMap Editor - " + mainGR.getOriginFile().getName());
				mTileMapPanel.loadTileMap(mainGR); //the collision editor too, from the one read of it
				mTileMapPanel.scaleImage(1);
			});
		}
	}

	private void openMapMatrixAction() {
		if (!gameOpen("Open MapMatrix")) {
			return;
		}
		File f = picked(openDialog("LAST_DIR_MM", "Open MM file"), "LAST_DIR_MM");
		if (f != null) {
			openChosenMapMatrix(f, () -> mTileMapPanel.scaleImage(1));
		}
	}

	private void openZoneAction() {
		if (!Workspace.isValid() && !ctrmap.Ui.confirmOpenWithoutWorkspace("Open Zone")) {
			return;
		}
		//the loose ZO files live in the workspace, never in the RomFS - start there when we can
		File zoneDir = Workspace.isValid() ? Workspace.getExtractionDirectory(ArchiveType.ZONE_DATA) : null;
		JFileChooser jfc = openDialog("LAST_DIR_ZO", "Open ZO file");
		if (zoneDir != null && zoneDir.exists()) {
			jfc.setCurrentDirectory(zoneDir);
		}
		jfc.setFileFilter(new FileFilter() {
			@Override
			public boolean accept(File f) {
				if (f.isDirectory()) {
					return true;
				}
				return f.getName().matches("\\d+") || f.getName().endsWith(".zo") || f.getName().endsWith(".bin");
			}

			@Override
			public String getDescription() {
				return "Zone mini-pack | .zo, .bin, extracted zonedata";
			}
		});
		File f = picked(jfc, "LAST_DIR_ZO");
		if (f != null) {
			boolean zo;
			try {
				zo = ctrmap.util.Bytes.checkMagicLE16(f, 0x5a4f);
			} catch (java.io.IOException ex) {
				Ui.error(f.getName() + " could not be read: " + Ui.reason(ex), "Open ZO file");
				return;
			}
			if (!zo) {
				Ui.error(f.getName()
						+ " is not a zone mini-pack.\n\n"
						+ "The RomFS only holds packed GARC archives, not single zone files, so pointing\n"
						+ "this dialog at the game directory can never work.\n"
						+ (zoneDir != null ? ("Extracted zone files live in " + zoneDir.getAbsolutePath() + "\n") : "")
						+ "\nThe normal way to open a map is the zone list in the \"Zone Loader\" tab.", "Not a ZO file");
				return;
			}
			//WAS "isValid() ? game() : GameType.ORAS". A .zo does not record
			//which game it came from, and a zone read with the wrong game's
			//semantics has the wrong warp labels, the wrong move codes and,
			//if it is then saved, the wrong bits written into its header. With
			//no workspace open there is nothing to read that from, so say so.
			if (!Workspace.isValid()) {
				Ui.error("Open a workspace first (Options > Workspace settings).\n\n"
						+ "A .zo file does not say which game it belongs to, and a zone header means\n"
						+ "different things in each of them, so CTRMap will not guess one for it.", "No workspace");
				return;
			}
			//A LOOSE .zo HAS NO SLOT IN THE ZONE TABLE, AND HAS TO SAY SO.
			//loadZone() deliberately keeps whatever index was last recorded,
			//because the dropdown's list worker records its own on the very next
			//line (ZoneLoadingPanel.zoneListActionPerformed). Nothing recorded one
			//here, so a zone opened from a file inherited the index of whatever
			//zone the user had last picked - and ZoneLoadingPanel.store() then
			//wrote THIS file's 0x38-byte header into the workspace's master
			//zone-header table at that slot, silently replacing an unrelated map's
			//entry. It did it without a word, while the harmless case (nothing ever
			//picked, index -1) was the one that reported. -1 is what
			//LoadedZone.open(int, Zone) documents for a zone that came from a file,
			//and it is also what the three operations that work on a table slot -
			//append, clone and repurpose - already test for before they run.
			//REFUSED IN WORDS (census M.100, its sibling): a mini-pack whose table is damaged threw past this action
			//to the catch-all, as Open GR Mapfile's did
			Zone opened;
			try {
				opened = new Zone(new ZO(f, game), Workspace.game());
			} catch (RuntimeException notAZone) {
				Ui.error(f.getName() + " cannot be opened as a zone:\n" + Ui.reason(notAZone) + "\n\nNothing was changed.",
						"Open ZO file");
				return;
			}
			mZonePnl.loadZone(opened);
			loadedZone.open(-1, opened);
		}
	}

	/** File > Save: every editor stores what it holds, without asking. */
	private void saveAllAction() {
		//SAID WHEN NOT EVERYTHING WAS SAVED (census M.101): the answer was dropped, so Ctrl+S over a file that could
		//not be written answered with nothing at all
		boolean saved;
		try {
			saved = openEditors.saveAll(false);
		} catch (RuntimeException failed) {
			Ui.error(frame, "Not everything was saved:\n" + Ui.reason(failed), "Save");
			return;
		}
		if (!saved) {
			Ui.error(frame, "Not everything was saved: an editor could not write what it holds, and its own message"
					+ " says why.", "Save");
		}
	}

	// ------------------------------------------------------------- Map menu

	/** Map > Map Builder: the painter is a World Editor tool, so go there and pick it. */
	private void openMapBuilderAction() {
		showWorldEditor();
		worldToolbar.selectPaintTool();
	}

	private void objToCollisionsAction() {
		JFileChooser jfc = openDialog("LAST_DIR_OBJ", "Open OBJ file");
		jfc.setFileFilter(new FileFilter() {
			@Override
			public boolean accept(File f) {
				if (f.isDirectory()) {
					return true;
				}
				return f.getName().endsWith(".obj");
			}

			@Override
			public String getDescription() {
				return "Wavefront OBJ file | .obj";
			}
		});
		File f = picked(jfc, "LAST_DIR_OBJ");
		if (f == null) {
			return;
		}
		//IT USED TO DO NOTHING, SILENTLY, when no collision was loaded: the user picked a
		//mesh exported from Blender, waited through the parse, and got no dialog, no log
		//line and no changed tree - indistinguishable from a successful import of an empty
		//file. The collision comes from the Collision Editor tab, two tabs away, and this
		//item is its only bulk action.
		if (mCollEditPanel.coll == null) {
			Ui.error(frame, "No collision is open, so there is nothing to replace.\n\n"
			+ "Open a zone and switch to the Collision Editor tab first - the OBJ replaces the"
			+ " collision meshes of whatever is loaded there.", "OBJ to collisions");
			return;
		}
		ctrmap.formats.gfcollision.GRCollisionMesh[] meshes;
		//THE ONE OBJ READER (F070): this read through a second parser of its own
		try (java.io.BufferedReader in = java.nio.file.Files.newBufferedReader(f.toPath(),
				java.nio.charset.StandardCharsets.ISO_8859_1)) {
			meshes = ctrmap.formats.h3d.MapModelObj.collision(ctrmap.formats.h3d.MapModelObj.parse(in, f.getName()));
		} catch (java.io.IOException unreadable) {
			//said, and the open collision left alone (F044): this used to be a stack trace nobody saw
			Ui.error(frame, "The OBJ could not be read, so nothing was changed:\n" + Ui.reason(unreadable),
					"OBJ to collisions");
			return;
		}
		if (meshes.length == 0) {
			Ui.error(frame, f.getName() + " holds no faces this can use, so nothing was"
			+ " changed.", "OBJ to collisions");
			return;
		}
		mCollEditPanel.coll.meshes = meshes;
		mCollEditPanel.coll.modified = true;
		mCollEditPanel.buildTree();
		Ui.message(frame, meshes.length + " collision mesh(es) read from " + f.getName()
			+ " and put in the Collision Editor. They are not saved until you save the zone.",
			"OBJ to collisions", JOptionPane.INFORMATION_MESSAGE);
	}

	/** The tileset editor, in the Extras tab - it was a window for no better reason
	 *  than having been drawn in the form designer. */
	private void tilesetEditorAction() {
		TileDBWriter editor = new TileDBWriter();
		tileset = editor;
		showInExtras("Tileset editor", editor.withUndo(), editor.getJMenuBar());
	}

	/** The tileset editor last opened in the Extras tab, whose history is settled around gestures; null before. */
	private TileDBWriter tileset;

	// --------------------------------------------------------- Options menu

	/** Workspace settings, in the Extras tab; same reason as the tileset editor. */
	private void workspaceSettingsAction() {
		showInExtras("Workspace & paths", new WorkspaceSettings(mTileMapPanel,
			() -> mTileEditForm.tileset = Workspace.getTileset(),
			CtrmapMainframe.this::onWorkspaceOpened, CtrmapMainframe.this::unloadEditors));
	}

	/** Options &gt; Clean workspace: asks first, with the count of edits - see {@link Workspace#cleanAfterAsking}. */
	private void cleanWorkspaceAction() {
		if (Workspace.cleanAfterAsking(frame, "Clean workspace deletes every file extracted into the workspace;"
				+ " the game is unpacked again the next time it is opened.", CtrmapMainframe.this::unloadEditors)) {
			mZonePnl.loadEverything();
		}
	}

	// ------------------------------------------------------------ Help menu

	private void issueTrackerAction() {
		if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
			try {
				//THIS project's tracker (F005): this opened the upstream 2019 repository's, where a
				//report about F5's code reaches nobody who can fix it
				Desktop.getDesktop().browse(new URI(ctrmap.update.UpdateChecker.ISSUES_PAGE));
			} catch (URISyntaxException | IOException ex) {
				Ui.error(frame, "The issue tracker could not be opened in a browser: " + Ui.reason(ex)
						+ "\nIt is at " + ctrmap.update.UpdateChecker.ISSUES_PAGE, "Browser open error");
			}
		} else {
			Ui.error("Your system either does not support the Java Desktop API or you do not have a suitable browser installed.", "Browser open error");
		}
	}

	/**
	 * Help > About, over the window rather than a window of its own (F243): it was a separate JFrame
	 * saying "CTRMAP R0", crediting and linking only the 2019 upstream, while this fork's version
	 * appeared only in the update dialog.
	 */
	private void aboutAction() {
		Ui.message(frame, aboutText(), "About CTRMap-F5", JOptionPane.INFORMATION_MESSAGE);
	}

	/** What Help > About says: this build, this project, and whose work it stands on. */
	private static String aboutText() {
		return "CTRMap-F5 " + ctrmap.update.AppVersion.described() + "\n"
				+ "https://github.com/" + ctrmap.update.UpdateChecker.REPO + "\n\n"
				+ "A map and game-data editor for Pokemon Omega Ruby and Alpha Sapphire, a fork of\n"
				+ "CTRMap by HelloOO7 (github.com/HelloOO7/CTRMap, 2019).\n\n"
				+ "Research credits: HelloOO7 - tilemap, collision, building models and registries, camera\n"
				+ "data, map matrix, NPC registry, ZoneData; Kaphotics - ZoneData (zones, furniture, NPCs,\n"
				+ "triggers, scripting), GARC and text; gdkchan - GARC, LZ11; Barubary - DSDecmp/LZ11.\n"
				+ "Special thanks: EvanDixon (DotNet3DSToolkit), dnasdw (3dstool), neimod and contributors\n"
				+ "(ctrtool), gdkchan (SPICA, Ohana3DS), and everyone else who made 3DS hacking possible.";
	}

	/**
	 * Pushes the loaded zone's atmosphere (AreaData subfile 4) into the 3D
	 * scene, so fog & lighting edits are visible in the editor itself and not
	 * only in the atmosphere picker's preview. Silent when nothing is loaded.
	 */
	private void refreshSceneFog() {
		if (m3DDebugPanel == null) {
			return;
		}
		try {
			Zone open = loadedZone == null ? null : loadedZone.open();
			if (!Workspace.isValid() || open == null || open.header == null) {
				m3DDebugPanel.clearFog();
				return;
			}
			ctrmap.formats.containers.AD ad = open.header.areadata != null
					? open.header.areadata
					: new ctrmap.formats.containers.AD(Workspace.getWorkspaceFile(
							ArchiveType.AREA_DATA, open.header.areadataID), game);
			ctrmap.formats.area.AreaEnv env = ctrmap.formats.area.AreaEnv.read(ad.getFile(ctrmap.formats.containers.AD.ENVIRONMENT));
			//THE DAY SLOT, and only when the area actually draws fog then. This used to
			//hand over three floats from offset 0 as an r,g,b - which are one channel's
			//red at three different times of day, and are 1.0, 1.0, 1.0 in most areas.
			//The view painted that into glClearColor and GL_FOG_COLOR, so those maps
			//opened as a blank white screen and looked like they had failed to load.
			float[] fog = env.viewFog(ctrmap.formats.area.AreaEnv.TIME_DAY);
			if (fog == null) {
				m3DDebugPanel.clearFog();
			} else {
				m3DDebugPanel.setFog(fog[0], fog[1], fog[2], env.fogNear, env.fogFar);
			}
		} catch (Exception unread) {
			//SAID, NOT SHOWN AS NO FOG (census L.55): an area whose atmosphere could not be read looked like one with
			//none, and nothing said otherwise
			m3DDebugPanel.clearFog();
			java.util.logging.Logger.getLogger(CtrmapMainframe.class.getName()).warning(
					"The 3D view shows no fog: this zone's atmosphere could not be read - " + Ui.reason(unread));
		}
	}

	/**
	 * The Game Data tab: the subjects down the left - the game-wide data editors (trainers,
	 * battle facilities, shops, wild Pokemon) - and the chosen editor beside them.
	 *
	 * <p>THE EDITORS ARE IN THE TAB. They were modal windows opened from a column of
	 * buttons - a feature in a window, which this project does not do - and the tab
	 * they belong to already existed and held nothing but the buttons that opened
	 * them. Nothing counted windows, so the rule applied to none of this until
	 * DialogSeamTest started counting; the ceiling it holds falls as each one lands.
	 *
	 * <p>Takes its entry column as an argument so a suite can build the tab with a
	 * stand-in and read back what is in it, with no game and no display.
	 */
	private JPanel buildGameDataTab(JComponent entries) {
		JPanel tab = new JPanel(new BorderLayout());
		javax.swing.JScrollPane left = new javax.swing.JScrollPane(entries);
		left.setPreferredSize(new java.awt.Dimension(460, 0));
		JLabel hint = new JLabel("<html><i>Pick a subject on the left - it opens here.</i></html>");
		hint.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
		//NOT SCROLLED: the editors that open here scroll their own tables, and a scroll pane around a
		//scroll pane stretches the inner one to its full height and takes its scrolling away
		gameDataHost = new ctrmap.humaninterface.ToolHost("the Game Data subjects", hint, false);
		javax.swing.JSplitPane split = new javax.swing.JSplitPane(
			javax.swing.JSplitPane.HORIZONTAL_SPLIT, left, gameDataHost);
		split.setDividerLocation(460);
		tab.add(split, BorderLayout.CENTER);
		return tab;
	}

	/** Where an open Game Data editor lives, with the subjects' hint as its home (see ToolHost). */
	private ctrmap.humaninterface.ToolHost gameDataHost;

	/**
	 * The workspace's own operations - forks, clones, renames, removals, imports, resizes - each kept so it can be
	 * taken back and put again (F209c). The session's history, not the open zone's: most of them change which
	 * zones there are. Its Undo and Redo sit under the Zone Loader's column, beside the zone list.
	 */
	private ctrmap.humaninterface.SaveHistory workspaceOps;

	/** The workspace's history, made when first asked for: on the event thread, where the window is built (F220). */
	private ctrmap.humaninterface.SaveHistory workspaceOps() {
		if (workspaceOps == null) {
			workspaceOps = new ctrmap.humaninterface.SaveHistory(
					() -> java.util.Collections.<Object>singletonList(Workspace.session()));
		}
		return workspaceOps;
	}

	/** After an operation is put back or put again: the zone list read again, and the open zone if its files moved. */
	private void readTheWorkspaceAgain() {
		if (mZonePnl != null) {
			mZonePnl.loadEverything(mZonePnl::reopenIfChangedOnDisk);
		}
	}

	/**
	 * The saves made in the Game Data tab, each kept so it can be taken back and put again (F209c): Undo and
	 * Redo sit under whichever editor is open, and Ctrl+Z there takes back the last save made in the tab.
	 */
	private ctrmap.humaninterface.SaveHistory gameDataSaves;

	/** The Game Data tab's history, made when first asked for: on the event thread, where the window is built (F220). */
	private ctrmap.humaninterface.SaveHistory gameDataSaves() {
		if (gameDataSaves == null) {
			gameDataSaves = new ctrmap.humaninterface.SaveHistory(
					() -> java.util.Collections.<Object>singletonList(Workspace.session()));
		}
		return gameDataSaves;
	}

	/** Opens the trainer editor in the Game Data tab, and goes there. */
	private void openTrainers() {
		inGameData("Trainer editor", ctrmap.humaninterface.TrainerEditDialog.panelForSelection(
			frame, selectedNpcScript(), CtrmapMainframe.this::clearGameData, gameDataSaves()));
	}

	/** Opens the battle-facility opponent editor in the Game Data tab. */
	private void openFacilityOpponents() {
		inGameData("Facility opponents", ctrmap.humaninterface.MaisonEditDialog.panel(
			frame, CtrmapMainframe.this::clearGameData, CtrmapMainframe.this::showGameData, gameDataSaves()));
	}

	/** Opens the shop editor in the Game Data tab. */
	private void openShops() {
		inGameData("Shop inventories", ctrmap.humaninterface.ShopEditDialog.panel(
			frame, CtrmapMainframe.this::clearGameData, gameDataSaves()));
	}

	/** Opens the item editor in the Game Data tab. */
	private void openItems() {
		inGameData("Item editor", ctrmap.humaninterface.ItemEditDialog.panel(
			frame, CtrmapMainframe.this::clearGameData, gameDataSaves()));
	}

	/**
	 * Shows an editor in the Game Data tab and brings that tab forward.
	 *
	 * <p>A null editor means it refused and has already said why - four of these are
	 * reached from a menu, where the user is looking at something else entirely, so
	 * an editor that quietly appeared in a tab nobody switched to would be the same
	 * as no editor at all. That is the mistake this whole change is undoing.
	 */
	private void inGameData(String title, JComponent editor) {
		if (editor == null) {
			return;
		}
		showGameData(title, editor);
		selectTab("Game Data");
	}

	/**
	 * Opens the wild-encounter editor in the Game Data tab, and goes there.
	 *
	 * <p>Three things ask for it - the Game Data tab's own entry, the Game Data menu
	 * and the map row's Encounters button - and two of them are somewhere else when
	 * they do. An editor that appears in a tab the user is not looking at is the same
	 * as no editor, which is the mistake this whole change is undoing.
	 */
	private void openEncounters() {
		JComponent editor = ctrmap.humaninterface.EncounterEditDialog.panel(
			frame, loadedZone, CtrmapMainframe.this::clearGameData, gameDataSaves());
		if (editor == null) {
			return;                        //it has already said why
		}
		showGameData("Wild encounters", editor);
		selectTab("Game Data");
	}

	/** Brings a tab to the front by its name; does nothing if there is no such tab. */
	private void selectTab(String name) {
		if (tabs == null) {
			return;
		}
		for (int i = 0; i < tabs.getTabCount(); i++) {
			if (name.equals(tabs.getTitleAt(i))) {
				tabs.setSelectedIndex(i);
				return;
			}
		}
	}

	/** Puts an editor in the Game Data tab, under its name. */
	private void showGameData(String title, JComponent editor) {
		if (gameDataHost != null && editor != null) {
			gameDataHost.show(title, gameDataSaves().around(editor), null, null);
		}
	}

	/** Back to the hint, when an editor is finished with. */
	private void clearGameData() {
		if (gameDataHost != null) {
			gameDataHost.goHome();
		}
	}

	private JPanel buildGameDataPanel() {
		JPanel p = new JPanel();
		p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.Y_AXIS));
		p.setBorder(javax.swing.BorderFactory.createEmptyBorder(24, 32, 24, 32));
		addGameDataEntry(p, "Trainers", "Edit any trainer's party, moves, items and battle type.",
				"Trainer editor", e -> openTrainers());
		addGameDataEntry(p, "Battle facilities", "<html>Edit the opponent pools and trainer-class assignments of the battle facility engine.<br>This data is ENGINE-WIDE: the retail facility and every custom facility cloned from it draw<br>from the same pools - author your teams in FREE slots (retail rows are marked and guarded).</html>",
				"Facility opponents", e -> openFacilityOpponents());
		addGameDataEntry(p, "Shops", "Change what the Poke Marts and specialty shops sell (ships as a code.ips patch).",
				"Shop inventories", e -> openShops());
		addGameDataEntry(p, "Items", "<html>Edit any item's price, held effect, use routines, EV changes, name and description.<br>"
				+ "Effects are REASSIGNED from the ones the game already implements - the editor cannot author a new one.<br>"
				+ "Four empty ids can hold a new item; the icon is the one part that needs a code.ips patch.</html>",
				"Item editor", e -> openItems());
		addGameDataEntry(p, "Wild Pokemon", "Edit the loaded zone's wild encounter slots (grass, surf, fishing...).",
				"Wild encounters (this zone)", e -> openEncounters());
		p.add(javax.swing.Box.createVerticalGlue());
		return p;
	}

	private static void addGameDataEntry(JPanel p, String title, String desc, String button, ActionListener action) {
		JLabel t = new JLabel(title);
		t.setFont(t.getFont().deriveFont(java.awt.Font.BOLD, 15f));
		t.setAlignmentX(0f);
		JLabel d = new JLabel(desc);
		d.setForeground(java.awt.Color.DARK_GRAY);
		d.setAlignmentX(0f);
		javax.swing.JButton b = new javax.swing.JButton(button);
		b.addActionListener(action);
		b.setAlignmentX(0f);
		p.add(t);
		p.add(d);
		p.add(javax.swing.Box.createVerticalStrut(6));
		p.add(b);
		p.add(javax.swing.Box.createVerticalStrut(22));
	}

	/** The open map's decoded world textures, or null when no map is open. */
	private java.util.List<ctrmap.formats.h3d.texturing.H3DTexture> worldTextures() {
		return mTileMapPanel == null ? null : mTileMapPanel.getWorldTextures();
	}

	/** The script of the NPC the NPC editor has selected, or null when none is. */
	private Integer selectedNpcScript() {
		return mNPCEditForm == null || mNPCEditForm.npc == null ? null : mNPCEditForm.npc.script;
	}

	/**
	 * Hands the main window the session that just became the open game - or
	 * null, when the workspace closed or failed to open - and, when the
	 * window exists, loads the panels that read from it.
	 *
	 * <p>Called from {@link Workspace#install}, the one place a session
	 * becomes current, so the application and a suite hand the window its
	 * game the same way, and a headless suite (no window, no panels) still
	 * hands it: that is why the assignment is unguarded and the panel loading
	 * is not. {@link Workspace#validate} goes through install only once the
	 * tables derived from the game are loaded, which is why the zone dropdown
	 * has its names when it fills.
	 *
	 * @return how many zones the opened game turned out to have. The setup
	 *         wizard needs this and used to get it by reaching for the Zone TAB
	 *         and counting the items in its dropdown - asking the widget that
	 *         happens to display the answer. Workspace.isValid() is true BEFORE
	 *         the archives are read, so it says nothing; the zone table's own
	 *         count is the honest test, and this is the method the wizard
	 *         already calls.
	 */
	private int onWorkspaceOpened(WorkspaceSession opened) {
		game = opened;
		if (opened == null) {
			//A WORKSPACE THAT FAILED TO OPEN TAKES THE ZONE THAT WAS OPEN WITH IT. Only the
			//successful arm below ever closed one - loadEverything's worker calls
			//loadedZone.release() before it rebuilds the list - so a validate that answered
			//null dropped the game here and returned, leaving the PREVIOUS game's zone open
			//at its old index with the entity editors still showing its records. Options >
			//Workspace settings reaches exactly that: it writes the new game folder, cleans
			//the workspace "to prevent cross-injecting", and then hands this null when the
			//new folder does not validate.
			//
			//WHAT THAT COST, read off the save path rather than assumed. The Zone tab's
			//store() - reached from File > Save and from the window's close handler through
			//OpenEditors - asks only whether a zone is open, so storeZone() ran zone.store()
			//and wrote the old game's zone file back into the workspace folder that had just
			//been cleaned for another game. THAT write is the silent half. The master-table
			//write after it does report: with no session ZoneTables answers "No workspace is
			//loaded" as an IOException and the catch there puts up "the master zone-header
			//table was NOT updated" - a dialog that tells the user the zone was saved, over a
			//workspace it no longer belongs to.
			//
			//The Zone tab already owns what "no zone is open" means and uses it for a load
			//that failed partway; a game that failed to open is the same state, reached one
			//step further out, so it is asked for rather than written again here.
			if (mZonePnl != null) {
				mZonePnl.unloadZone();
			}
			return 0;
		}
		if (frame == null) {
			return 0;
		}
		mBuilder.loadGARCs();
		//the backup is checked when its game opens, not first at the moment it is needed (F140)
		ctrmap.vault.VaultUi.checkOnOpen(frame, opened.gameDir());
		//the dropdown previews the row you are ON, before selecting it loads the zone
		mZonePnl.attachZonePreview(game);
		//THE REPAIR PACKS, AND A PACK IS A WORKER. What follows reads the very
		//archives that pack is rewriting, so it is handed to the repair to run when
		//the pack has finished rather than started beside it. A workspace with
		//nothing to repair runs all of this straight through, exactly as before.
		PaddingZoneRepair.repairOnOpen(frame, opened, Workspace::packWorkspace, workspaceOps()::keep, () -> {
			mZonePnl.loadEverything();
			mTextEditor.loadGarc();
			showZoneLoadingHint();
		});
		return loadedZone.count();
	}

	/**
	 * True when a game is open. Otherwise says so under {@code title} and
	 * returns false, because the actions that open a loose container file
	 * cannot go on without one: a container is handed the game it reports its
	 * writes to and extracts its subfiles into, and there is none. Open GR
	 * Mapfile and Open MapMatrix used to ask "continue anyway?" and then open
	 * the file through a container that reported to nobody, so an edit made
	 * through it was written into the loose file and recorded nowhere.
	 */
	private boolean gameOpen(String title) {
		if (game != null) {
			return true;
		}
		Ui.error(frame, "Open a workspace first (Options > Workspace settings).\n\n"
				+ "A container opened outside a workspace has nowhere to record an edit and\n"
				+ "nowhere to extract its subfiles to, so CTRMap will not open one.", title);
		return false;
	}

	/** Drops everything the editor panels hold from the workspace, ahead of a clean. */
	private void unloadEditors() {
		//THROUGH THE ZONE TAB FIRST (F222, census M.99): it closes the zone and clears every zone view
		//on the one list - the script, warp and trigger forms included, which naming five editors
		//here left holding the closed zone; the map and collision views keep a map through a failed
		//load on purpose, so they are dropped by name below
		if (mZonePnl != null) {
			mZonePnl.unloadZone();
		}
		mTileMapPanel.unload();
		mCollEditPanel.unload();
		mCamEditForm.unload();
		mNPCEditForm.unload();
		mPropEditForm.unload();
	}

	/**
	 * Lands the user on the tab that actually opens zones and explains how to use
	 * it. Called from Workspace.validate() so that it also fires for the first
	 * validation that succeeds after the paths are set in Workspace settings.
	 */
	private void showZoneLoadingHint() {
		tabs.setSelectedComponent(zoneTabPnl);
		Preferences hintPrefs = fileDialogPrefs();   //the window's one node (census L.31)
		if (!hintPrefs.getBoolean("ZONE_HINT_SHOWN", false)) {
			hintPrefs.putBoolean("ZONE_HINT_SHOWN", true);
			Ui.message("Workspace loaded.\n\n"
					+ "Pick a zone in the zone list on the right of the \"Zone Loader\" tab and press\n"
					+ "\"Load this zone\" (or double-click it).\n"
					+ "That loads the world, matrix, collisions and entities for editing.\n\n"
					+ "(File > Open Zone is only for single loose ZO files.)", "Opening a zone", JOptionPane.INFORMATION_MESSAGE);
		}
	}

	private void deployModAction() {
		if (!Workspace.isValid()) {
			Ui.error(frame, "Load a workspace first.", "Deploy mod");
			return;
		}
		String titleId = ModDeployer.guessTitleId();
		File azahar = ModDeployer.azaharModRoot(titleId);

		//THE TITLE THE DUMP SAYS, OR A QUESTION (F238): Omega Ruby's or X's id was pre-filled for every
		//dump whose folder did not name it, and an Alpha Sapphire or Y mod went where it never loads
		final javax.swing.JComboBox<String> titleField = new javax.swing.JComboBox<>(ModDeployer.titleChoices(titleId));
		titleField.setEditable(true);
		final javax.swing.JTextField folderField = new javax.swing.JTextField(azahar != null ? azahar.getAbsolutePath() : "");
		titleField.addActionListener(ev -> folderField.setText(ModDeployer.folderForTitle(titleField.getSelectedItem(), folderField.getText())));
		javax.swing.JButton browse = new javax.swing.JButton("Browse...");
		browse.addActionListener((java.awt.event.ActionEvent ev) -> {
			JFileChooser fc = folderDialog(folderField.getText(), "Mod folder (Azahar mods\\<title>, or your SD luma\\titles\\<title>)");
			fillWith(folderField, ctrmap.Ui.choose(frame, fc, false) != null ? fc.getSelectedFile() : null);
		});
		final javax.swing.JTextField ipsField = new javax.swing.JTextField("");
		javax.swing.JButton ipsBrowse = new javax.swing.JButton("Browse...");
		ipsBrowse.addActionListener((java.awt.event.ActionEvent ev) ->
				fillWith(ipsField, picked(openDialog("LAST_DIR_IPS", "Optional: pick a code.ips (from Add zones) to install"), "LAST_DIR_IPS")));
		javax.swing.JPanel folderRow = new javax.swing.JPanel(new java.awt.BorderLayout());
		folderRow.add(folderField, java.awt.BorderLayout.CENTER);
		folderRow.add(browse, java.awt.BorderLayout.EAST);
		//MADE AGAIN HERE, where it is asked for (census H.2): Add zones was the patch's only producer and runs once
		//per stock base, so one lost to a cancelled save could never be made again
		javax.swing.JButton ipsMake = new javax.swing.JButton("Make zone-limit patch...");
		ipsMake.setToolTipText("Make the zone-limit code patch again for the zones this game holds now");
		ipsMake.addActionListener((java.awt.event.ActionEvent ev) -> fillWith(ipsField, mZonePnl.makeZoneLimitPatch()));
		javax.swing.JPanel ipsButtons = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
		ipsButtons.add(ipsMake);
		ipsButtons.add(ipsBrowse);
		javax.swing.JPanel ipsRow = new javax.swing.JPanel(new java.awt.BorderLayout());
		ipsRow.add(ipsField, java.awt.BorderLayout.CENTER);
		ipsRow.add(ipsButtons, java.awt.BorderLayout.EAST);
		//The mod can be switched off without deleting anything, so the user can
		//play the untouched retail game (to get a starter, to compare against
		//vanilla, to check whether a bug is theirs or ours) and switch back after.
		boolean parked = azahar != null && ModDeployer.isParked(azahar);
		String offOn = parked ? "Turn mod back ON" : "Turn mod OFF (play vanilla)";
		Object[] form = {
			"Deploy your edits as a LayeredFS mod - only archives you actually changed are copied.",
			"Your workspace is packed automatically first, so the latest edits always ship.",
			" ",
			"Title ID:", titleField,
			"Mod folder (Azahar auto-detected; Browse to your SD card for a 3DS/Luma):", folderRow,
			"Code patch to install (optional - the code.ips from 'Add zones', or Make one for the zones this game holds):", ipsRow,
			" ",
			parked
			? "The mod is currently OFF - the game boots completely stock."
			: "\"" + offOn + "\" moves the mod out of the emulator's load path so the game boots"
			+ " stock. Nothing is deleted and your save is untouched; switch it back any time."
		};
		String[] choices = {"Deploy", offOn, "Cancel"};
		int pick = ctrmap.Ui.formOption(frame, form, "Deploy to emulator (LayeredFS mod)",
				JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, choices, choices[0]);
		if (pick != 0 && pick != 1) {
			return;
		}
		String folder = folderField.getText().trim();
		if (folder.isEmpty()) {
			Ui.error(frame, "Pick a mod folder first.", "Deploy mod");
			return;
		}
		final File modRoot = new File(folder);
		if (pick == 1) {
			toggleModAction(modRoot); //decides the direction from the folder's real state
			return;
		}
		String ipsPath = ipsField.getText().trim();
		final File ips = ipsPath.isEmpty() ? null : new File(ipsPath);
		// Always Pack first so the deployed RomFS reflects the LATEST edits. Deploying
		// stale (un-packed) data is the #1 cause of "my change didn't show up" - e.g. a
		// talking NPC whose story-text line was edited but never packed will freeze in
		// game. packWorkspace is async (SwingWorker); deploy in its completion callback.
		Workspace.packWorkspace(new Runnable() {
			@Override
			public void run() {
				deployAfterPack(modRoot, ips);
			}
		});
	}

	/**
	 * What the window does once the pack has finished: deploy, and say what
	 * happened - including when the deployer refuses to.
	 *
	 * <p>THIS IS A METHOD SO THE REFUSAL IS A FACT A SUITE CAN CHECK. It used
	 * to be the body of the Runnable handed to the pack worker, and a worker
	 * swallows what its callback throws: {@link ModDeployer#deploy} refusing to
	 * write over a parked copy would have gone to the worker and nowhere the
	 * user can see, so the menu item would have looked like it did nothing at
	 * all - worse than the raw "already exists" path error the refusal replaced.
	 * That catch is the whole reason this method exists, and extracting it is
	 * the only way to assert it: the dialog above is a live Swing form, so a
	 * suite cannot reach this through {@code deployModAction}.
	 *
	 * <p>Everything it tells the user goes through {@link Ui}, so a suite runs it
	 * with no screen and reads back what was said.
	 */
	private void deployAfterPack(File modRoot, File ips) {
		//OFF THE EVENT THREAD (census M.63): the pack's done() hands this the event thread, and the deploy ran
		//on it - minutes of archive reads with the window frozen and nothing on screen. It runs in a worker now,
		//behind its own progress dialog, and is reported on the event thread when it is done. A caller already
		//off it - a suite - is answered in place
		if (!java.awt.EventQueue.isDispatchThread()) {
			reportDeploy(modRoot, () -> ModDeployer.deploy(modRoot, ips));
			return;
		}
		final String gameDir = Workspace.GAMEDIR_PATH;   //read here, on the event thread that writes it
		ctrmap.humaninterface.LoadingDialog progress = ctrmap.humaninterface.LoadingDialog.makeDialog("Deploying to the emulator");
		javax.swing.SwingWorker<ModDeployer.Result, Void> worker = new javax.swing.SwingWorker<ModDeployer.Result, Void>() {
			@Override
			protected ModDeployer.Result doInBackground() {
				return ModDeployer.deploy(modRoot, ips, gameDir);
			}

			@Override
			protected void done() {
				progress.close();
				reportDeploy(modRoot, () -> {
					try {
						return get();
					} catch (InterruptedException | java.util.concurrent.ExecutionException failed) {
						Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
						throw cause instanceof RuntimeException ? (RuntimeException) cause : new IllegalStateException(cause);
					}
				});
			}
		};
		worker.execute();
		progress.showDialog();
	}

	/** Says what a deploy did, or that it was refused and why - the catch is why deployAfterPack exists (above). */
	private void reportDeploy(File modRoot, java.util.function.Supplier<ModDeployer.Result> deploy) {
		ModDeployer.Result res;
		try {
			res = deploy.get();
		} catch (RuntimeException refused) {
			Ui.error(frame, "Cannot deploy:\n\n" + ctrmap.util.Bytes.reason(refused), "Deploy to emulator");
			return;
		}
		StringBuilder sb = new StringBuilder();
		if (res.deployed.isEmpty() && !res.codeIpsDeployed && res.removed.isEmpty()) {
			//the comparison is against the retail game, not against the last deploy (F021)
			sb.append("Nothing to deploy: every archive is the same as the retail game, and the mod\n"
					+ "holds no old copy of one.\n");
		} else {
			sb.append("Packed and deployed to:\n  ").append(modRoot.getAbsolutePath()).append("\n\n");
			if (!res.deployed.isEmpty()) {
				sb.append("Archives:\n");
				for (String d : res.deployed) {
					sb.append("  ").append(d).append("\n");
				}
			}
			if (res.codeIpsDeployed) {
				sb.append("  exefs\\code.ips  (executable patch)\n");
			}
			if (!res.removed.isEmpty()) {
				sb.append("\nBack to retail, so taken out of the mod:\n");
				for (String d : res.removed) {
					sb.append("  ").append(d).append("\n");
				}
			}
			sb.append("\n").append(res.unchanged).append(" archive(s) unchanged, skipped.\n");
		}
		if (!res.skipped.isEmpty()) {
			sb.append("\nProblems:\n");
			for (String s : res.skipped) {
				sb.append("  ").append(s).append("\n");
			}
		}
		sb.append("\nIMPORTANT: fully CLOSE and reopen the emulator before testing - it caches\n"
				+ "the game's files, so a soft reset can still show the old data.\n");
		sb.append("To play the untouched retail game, come back here and pick\n"
				+ "\"Turn mod OFF (play vanilla)\" - it switches off without deleting anything.");
		Ui.message(frame, sb.toString(), "Deploy to emulator", JOptionPane.INFORMATION_MESSAGE);
	}

	/**
	 * Switches the deployed mod off (or back on) by moving it out of / into the
	 * emulator's load path. This is what you want when you need the untouched
	 * retail game for a while - to play through a story section your edits are
	 * blocking, or to tell whether a bug is the game's or ours - without losing
	 * the mod. Nothing is deleted and save data is never touched.
	 */
	private void toggleModAction(File modRoot) {
		boolean parked = ModDeployer.isParked(modRoot);
		try {
			if (parked) {
				File back = ModDeployer.enable(modRoot);
				Ui.message(frame,
						"Mod is back ON:\n  " + back.getAbsolutePath()
						+ "\n\nFully close and reopen the emulator before playing - it caches game files.",
						"Mod switched on", JOptionPane.INFORMATION_MESSAGE);
			} else if (ModDeployer.isDeployed(modRoot)) {
				File parkedAt = ModDeployer.disable(modRoot);
				Ui.message(frame,
						"Mod is OFF - the game now boots completely stock.\n\n"
						+ "Your edits are safe here:\n  " + parkedAt.getAbsolutePath()
						+ "\n\nSave data was not touched. Come back here and pick \"Turn mod back ON\"\n"
						+ "when you want your world again.\n\n"
						+ "Fully close and reopen the emulator before playing - it caches game files.",
						"Playing vanilla", JOptionPane.INFORMATION_MESSAGE);
			} else {
				Ui.message(frame,
						"There is no deployed mod at:\n  " + modRoot.getAbsolutePath()
						+ "\n\nNothing to switch off - the game already boots stock.",
						"Nothing deployed", JOptionPane.INFORMATION_MESSAGE);
			}
		} catch (java.io.IOException ex) {
			Ui.error(frame,
					"Could not move the mod folder:\n  " + ctrmap.util.Bytes.reason(ex)
					+ "\n\nClose the emulator (it may be holding the files open) and try again.",
					"Mod switch failed");
		}
	}

	/**
	 * Refuses an action in words when the OPEN GAME cannot do it, naming that
	 * game and what is missing. Returns true when the caller must stop.
	 *
	 * <p>WHY THIS REPLACED {@code !Workspace.isOA()}. That test asks which game
	 * is loaded, and every gate built on it answered the same sentence - "Load
	 * an ORAS workspace first" - to four different situations: no workspace at
	 * all, X/Y, Sun/Moon and Ultra Sun/Ultra Moon. A user with X/Y open read an
	 * instruction that was simply false (a workspace WAS loaded) and learned
	 * nothing about why the menu item did not work, while Sun/Moon - a game
	 * whose overworld formats are not even the same generation - was told it
	 * was one archive away. The profile answers a third thing, "nobody measured
	 * that for this game", and that is what the user is entitled to read.
	 *
	 * @param f the capability the action needs, per {@link GameProfile#supports}
	 * @param title the dialog title, so the refusal is attributable to the menu
	 * item that was clicked
	 * @param what the action, as a sentence subject ("Forking map geometry")
	 * @param why what specifically is unmeasured for another game - written per
	 * site, because "it was only done on ORAS" is a claim each site has to earn
	 */
	private boolean refuseUnsupported(GameProfile.Feature f, String title, String what, String why) {
		if (!Workspace.isValid()) {
			Ui.error(frame, "Load a workspace first (Options > Workspace settings).", title);
			return true;
		}
		GameProfile p = Workspace.profile();
		if (p.supports(f)) {
			return false;
		}
		Ui.error(frame, what + " is not available for " + p.displayName() + ".\n\n" + why
				+ "\n\nNone of that has been verified against " + p.displayName()
				+ ", so CTRMap refuses here rather than writing through a guess.", title);
		return true;
	}

	/** The door every shared-area refusal names (F064): its menu label and its dialogs' title. */
	private static final String INDEPENDENT_TITLE = ZoneIndependence.TITLE;

	/**
	 * Gives a zone its own private map geometry ({@link GeometryForker}) and its own private area
	 * ({@link AreaForker}), so a cloned zone can be edited without changing the town it was cloned
	 * from. Defaults the zone picker to the currently loaded zone; both are ticked, and each is a
	 * no-op for a zone that already owns it.
	 *
	 * <p>ONE DOOR FOR BOTH (F064). This item forked the map alone, while every shared-area refusal -
	 * a texture carry, a door prop, a prop's textures - sent the user here to give the zone its own
	 * AREA. Doing what the refusal said appended megabytes of map copies and left the area shared,
	 * so the same refusal came back, and nothing in the menus forked an area at all. The bar may not
	 * grow, so the item that already promised "make zone independent" now does.
	 */
	private void makeZoneIndependentAction() {
		if (refuseUnsupported(GameProfile.Feature.AREA_FORK, INDEPENDENT_TITLE,
				"Making a zone independent",
				"Giving one zone its own copy of its map appends to FieldData and the"
				+ " MapMatrix, giving it its own area appends to AreaData and the NPC registry and"
				+ " grows the engine's per-area table, and both repoint the zone in the master"
				+ " zone-header table; every one of those offsets was measured on Omega Ruby / Alpha"
				+ " Sapphire.")) {
			return;
		}
		ctrmap.formats.garc.GARC zoGarc = Workspace.getArchive(ArchiveType.ZONE_DATA);
		if (zoGarc == null) {
			Ui.error(frame, "ZoneData archive unavailable.", INDEPENDENT_TITLE);
			return;
		}
		//how many trailing entries are tables rather than zones is the profile's
		//measured number, not "2 unless it is XY" - see ZoneTables
		int zoneCount;
		try {
			zoneCount = ZoneTables.zoneCount(zoGarc);
		} catch (java.io.IOException ex) {
			Ui.error(frame, Ui.reason(ex), INDEPENDENT_TITLE);
			return;
		}
		int def = (loadedZone != null && loadedZone.index() >= 0 && loadedZone.index() < zoneCount) ? loadedZone.index() : 0;
		javax.swing.JSpinner idSpinner = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(def, 0, zoneCount - 1, 1));
		javax.swing.JCheckBox ownMap = new javax.swing.JCheckBox("Its own map geometry (regions and map matrix)", true);
		javax.swing.JCheckBox ownArea = new javax.swing.JCheckBox(
				"Its own area (fog, lighting, props, textures and NPC models)", true);
		Object[] form = {
			"Make this zone INDEPENDENT of the zones it shares with.",
			"After this, editing the zone no longer changes any other zone that",
			"currently shares it (e.g. a cloned zone and the town it was cloned from",
			"share the same 3D model and the same area until this is done).",
			" ",
			"Zone (GARC index - the number shown in the zone list):",
			idSpinner,
			ownMap,
			ownArea,
			" ",
			"Each copy starts identical and is made only when the zone still shares;",
			"the zone is repointed at it. Pure data, no code patch."
		};
		if (!ctrmap.Ui.form(frame, form, INDEPENDENT_TITLE)) {
			return;
		}
		int zoneIndex = (Integer) idSpinner.getValue();
		ZoneIndependence done;
		try {
			done = WorkspaceOps.independence(game, zoneIndex, ownMap.isSelected(), ownArea.isSelected(), workspaceOps(),
					CtrmapMainframe.this::readTheWorkspaceAgain);
		} catch (Exception ex) {
			Ui.error(frame, "Zone " + zoneIndex + " was not made independent - nothing was changed:\n"
					+ Ui.reason(ex), INDEPENDENT_TITLE);
			return;
		}
		afterIndependence(done);
	}

	/**
	 * What follows a zone made independent: what was done, said, and a fork packed and read again. Out of the action,
	 * whose form is a dialog no suite gets past, so the 2026-10-12 sweep found this test asserted by nothing.
	 */
	private void afterIndependence(ZoneIndependence done) {
		Ui.message(frame, done.report(), INDEPENDENT_TITLE, javax.swing.JOptionPane.INFORMATION_MESSAGE);
		//NOT REPOINTED IN MEMORY (F255): setting the open zone's area id left its area container, the map view's prop
		//registry and the camera form on the old area. What was forked lands in the archives, and the zone is read
		//again from there - a map fork as much as an area one, whose matrix the open zone held the same way
		if ((done.area != null && done.area.forked) || (done.map != null && done.map.forked)) {
			Workspace.packWorkspace(CtrmapMainframe.this::readTheWorkspaceAgain);
		}
	}

	/**
	 * Loads a .mm file the user picked into the matrix panel. False means it
	 * could not be read and the user has been told why.
	 *
	 * <p>Split out of the menu action, and saying so through {@link Ui},
	 * because the action is only reachable through a file chooser: the one
	 * sentence that distinguishes "that file is not a map matrix" from "the
	 * editor ignored my click" could be deleted with nothing noticing.
	 */
	private boolean openMapMatrixFile(File f) {
		if (!gameOpen("Open MapMatrix")) {
			return false;
		}
		try {
			mTileMapPanel.loadMatrix(new MapMatrix(new MM(f, game), game), null, null, null);
		} catch (RuntimeException ex) {
			//a container that is not a map matrix usually fails on a bare
			//dereference, whose getMessage() is null - "null" in a dialog is
			//indistinguishable from the editor ignoring the click
			Ui.error(frame, Ui.reason(ex), "Open MapMatrix");
			return false;
		}
		return true;
	}

	/**
	 * What Open MapMatrix does with the file the user picked: read it, and fit
	 * the view to it only if it read.
	 *
	 * <p>Word for word what the menu action used to do inline. {@link
	 * #openMapMatrixFile} was already out here so its refusal could be
	 * asserted, but the caller acting on that refusal was still buried in an
	 * anonymous listener behind a modal chooser, and a mutation sweep proved
	 * nothing watched it: inverted, a file that could not be read went on to
	 * refit the view as though a map had loaded - the editor reporting an error
	 * and then behaving as if there had been none - and the whole battery
	 * stayed green.
	 *
	 * @param fitViewToMap what to do once a map really is loaded - in the main
	 * window, rescaling the tilemap panel to it
	 */
	private void openChosenMapMatrix(File chosen, Runnable fitViewToMap) {
		if (!openMapMatrixFile(chosen)) {
			return;
		}
		fitViewToMap.run();
	}

	/**
	 * What Open GR Mapfile does once a file has been picked: ask every editor
	 * that holds unsaved work FIRST, and replace what is loaded only if they
	 * all agreed.
	 *
	 * <p>The ask is the whole of it. This path asked about the TILEMAP only,
	 * inside loadTileMap, and then went on to replace the prop editor's data
	 * outright (loadProps(null, null) -&gt; loadDataFile(mainGR, null)), drop the
	 * prop registry it was holding, clear the NPC editor's entities and
	 * registry, and unload the collision editor. So edited props, an edited prop
	 * registry, an edited NPC registry and an edited collision mesh were all
	 * thrown away with no dialog having mentioned any of them - and because the
	 * tilemap prompt DID appear, the user had every reason to think they had
	 * been asked about everything that was open. "Open something else" flushes
	 * the whole set everywhere else in this program; the Zone tab's own switch
	 * has since OpenEditors existed.
	 *
	 * <p>It is out here, and shaped like {@link #openChosenMapMatrix}, for the
	 * same reason that one is: the decision sat behind a modal JFileChooser that
	 * no suite can answer, so a comment was the only thing claiming the ask
	 * happened. The read cannot come out with it - it needs the tilemap panel,
	 * and building one asks JOGL for a GL profile the battery has no natives for
	 * - so the read is handed in instead. The editors are handed in too, rather
	 * than read from the window's static, so a suite can pass a set that records
	 * the flush and refuses on demand.
	 *
	 * @param editors the editors that hold unsaved work - the window's own
	 * @param loadChosen what replaces what is on screen, run only if they agreed
	 */
	private static void openChosenGr(OpenEditors editors, Runnable loadChosen) {
		if (!editors.saveAll(true)) {
			return;
		}
		try {
			loadChosen.run();
		} catch (RuntimeException notShown) {
			//SAID HERE, under the action's name (census M.100), not by the catch-all as something unhandled
			Ui.error(null, "The map region could not be shown:\n" + Ui.reason(notShown), "Open GR Mapfile");
		}
	}

	/**
	 * Renames a zone's in-game location banner (see {@link ZoneManager#renameZone}).
	 * Shared names are moved to a private free slot so other zones are unaffected.
	 */
	private void renameZoneAction() {
		if (!Workspace.isValid()) {
			Ui.error(frame, "Load a workspace first (Options > Workspace settings).", "Rename zone");
			return;
		}
		ctrmap.formats.garc.GARC zo = Workspace.getArchive(ArchiveType.ZONE_DATA);
		if (zo == null) {
			Ui.error(frame, "ZoneData archive unavailable.", "Rename zone");
			return;
		}
		//"length - (isXY() ? 1 : 2)" has two answers for four games, and the one
		//it gave Sun/Moon was ORAS's - a zone count silently two short. The
		//count is the open game's MEASURED number or a refusal; see ZoneTables.
		int zoneCount;
		try {
			zoneCount = ZoneTables.zoneCount(zo);
		} catch (java.io.IOException ex) {
			Ui.error(frame, Ui.reason(ex), "Rename zone");
			return;
		}
		int def = (loadedZone != null && loadedZone.index() >= 0 && loadedZone.index() < zoneCount) ? loadedZone.index() : 0;
		javax.swing.JSpinner idSpinner = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(def, 0, zoneCount - 1, 1));
		javax.swing.JTextField nameField = new javax.swing.JTextField(24);
		Object[] form = {
			"Rename a zone's in-game location banner (the name shown on entry).",
			"If the name is shared (a cloned zone shares the town it came from), this",
			"zone gets its OWN name and the others are left unchanged.",
			" ",
			"Zone (the number shown in the zone list):",
			idSpinner,
			"New name:",
			nameField
		};
		if (!ctrmap.Ui.form(frame, form, "Rename zone")) {
			return;
		}
		int idx = (Integer) idSpinner.getValue();
		String name = nameField.getText().trim();
		if (name.isEmpty()) {
			Ui.error(frame, "Enter a name.", "Rename zone");
			return;
		}
		try {
			ZoneManager.RenameResult r;
			try {
				r = WorkspaceOps.rename(game, idx, name, false, workspaceOps(), CtrmapMainframe.this::readTheWorkspaceAgain);
			} catch (ZoneManager.SharedNameOnly shared) {
				//THE PROMISE KEPT (F233): no free name line - ask before renaming every zone on the shared one
				if (Ui.confirm(frame, ctrmap.util.Bytes.reason(shared) + "\n\nRename \"" + shared.oldName + "\" to \"" + name
						+ "\" for all " + shared.sharers + " zones?", "Rename zone",
						JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
					return;
				}
				r = WorkspaceOps.rename(game, idx, name, true, workspaceOps(), CtrmapMainframe.this::readTheWorkspaceAgain);
			}
			ctrmap.formats.text.LocationNames.loadFromGarc(game); // refresh the dropdown name cache
			//Both halves of this line were improved independently and both are
			//kept: the text is built by RenameResult.report, which a suite can call
			//without a window, and it is delivered through Ui, so a suite can
			//also read what was said. Either alone leaves half the path dark.
			Ui.message(frame, r.report(idx, name),
					"Rename zone", javax.swing.JOptionPane.INFORMATION_MESSAGE);
			reopenIfWritten(); //the header was written through a container of its own (F234)
		} catch (Exception ex) {
			Ui.error(frame, "Rename failed:\n" + ctrmap.util.Bytes.reason(ex), "Rename zone");
		}
	}

	/**
	 * Puts the game back from its pristine vault - all of it, or one archive.
	 *
	 * <p>Whole-game and single-archive are offered as one choice rather than two
	 * menu items because they are not interchangeable and the difference is the
	 * whole point: restoring everything discards every edit made to the game
	 * folder since the backup, and a stale pack once damaged a SINGLE archive
	 * here (zone 536) and needed a hand repair. Someone reaching for this menu is
	 * already having a bad day; the safer option has to be in front of them, not
	 * one level down.
	 */
	private void restoreFromVaultAction() {
		java.io.File dump = Workspace.GAMEDIR_PATH == null ? null : new java.io.File(Workspace.GAMEDIR_PATH);
		if (dump == null || !dump.isDirectory()) {
			Ui.error(frame, "There is no game folder loaded to restore into.", "Restore");
			return;
		}
		java.io.File entry = ctrmap.vault.Vault.entryFor(dump);
		if (!ctrmap.vault.Vault.isSealed(entry)) {
			Ui.error(frame, ctrmap.vault.Vault.isInterrupted(entry)
					? "The pristine backup of this game was never finished, so it cannot be\n"
					+ "restored from. Re-run the setup wizard to take a fresh one."
					: "There is no pristine backup of this game to restore from.\n\n"
					+ "Re-run the setup wizard against an unmodified dump to take one.",
					"Nothing to restore");
			return;
		}
		Object[] options = {"Restore ONE archive", "Restore the WHOLE game", "Cancel"};
		Object picked = Ui.input(frame,
				"What should be put back?\n\n"
				+ "ONE archive keeps everything else you have done - use this when a single\n"
				+ "file is damaged, which is the usual case.\n\n"
				+ "The WHOLE game discards every change made to the game folder since the\n"
				+ "backup was taken. Edits kept in your workspace are not affected.",
				"Restore from pristine backup", JOptionPane.QUESTION_MESSAGE, options, options[0]);
		if (picked == null || options[2].equals(picked)) {
			return;
		}
		if (options[1].equals(picked)) {
			ctrmap.vault.VaultUi.restoreEverything(frame, entry, dump, Workspace.session());
			return;
		}
		//PICKED FROM WHAT THE BACKUP HOLDS, NOT TYPED (census M.147): the user whose game is damaged had to type
		//an archive path like a game's own, from memory or one example, and a typo was a refusal. Each file is
		//named for its archive where the loaded game's profile knows it - through Workspace, guarded on
		//isValid(), because Workspace.profile() has no profile to hand back before a workspace validates
		java.util.List<String> held;
		try {
			held = ctrmap.vault.Vault.heldFiles(entry);
		} catch (java.io.IOException damaged) {
			Ui.error(frame, ctrmap.util.Bytes.reason(damaged) + "\n\nIts files are still in " + entry + ".", "Backup damaged");
			return;
		}
		if (held.isEmpty()) {
			Ui.error(frame, "The backup lists no files, so there is nothing to put back.", "Nothing to restore");
			return;
		}
		ctrmap.gamedef.GameProfile prof = Workspace.isValid() ? Workspace.profile() : null;
		java.util.Map<String, String> byLabel = new java.util.LinkedHashMap<>();
		for (String file : held) {
			String what = null;
			for (ArchiveType t : prof == null ? new ArchiveType[0] : ArchiveType.values()) {
				String at = prof.archivePath(t);
				if (at != null && file.equals(ctrmap.vault.Vault.key(at))) {
					what = t.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
				}
			}
			byLabel.put(what == null ? file : file + "   (" + what + ")", file);
		}
		Object[] choices = byLabel.keySet().toArray();
		Object rel = Ui.input(frame,
				"Which file should be put back?\n\nThese are the files the backup holds. Everything else in\n"
				+ "the game folder is left alone.",
				"Restore one archive", JOptionPane.QUESTION_MESSAGE, choices, choices[0]);
		if (rel == null || !byLabel.containsKey(String.valueOf(rel))) {
			return;
		}
		ctrmap.vault.VaultUi.restoreOne(frame, entry, byLabel.get(String.valueOf(rel)), dump, Workspace.session());
	}

	/**
	 * Removes all ADDED zones (index &gt;= 536), restoring the stock ZoneData
	 * layout. Packs first (so pending edits are captured, then removed
	 * wholesale) and warns of base-zone warps that would dangle; then
	 * {@link ZoneRemover#remove} takes the zones out as one operation - the
	 * archive rewritten, the stale extraction cache dropped, and the area ids no
	 * zone names any more given back (F043) - and the window reloads and offers to
	 * take the zone-limit patch out of a code.ips.
	 */
	private void removeAddedZonesAction() {
		//the inverse of ZoneAppender, and gated on the same capability: it
		//restores the stock layout that game's executable was built for, and
		//"stock" is a number nobody has established outside ORAS
		if (refuseUnsupported(GameProfile.Feature.ZONE_APPEND, "Remove added zones",
				"Removing added zones",
				"Undoing an append means knowing how many zones the game shipped with and"
				+ " how its master zone-header table is laid out. Both were measured for"
				+ " Omega Ruby / Alpha Sapphire, alongside the code patch that raises the"
				+ " zone limit in the first place.")) {
			return;
		}
		ctrmap.formats.garc.GARC zoArc = Workspace.getArchive(ArchiveType.ZONE_DATA);
		//AN ARCHIVE NOT READ IS NOT A STOCK ONE (F156): a missing table fell into the stock branch and
		//was reported as "no added zones (stock layout)", a statement about an archive never looked at
		if (zoArc == null) {
			Ui.error(frame, "ZoneData archive unavailable.", "Remove added zones");
			return;
		}
		final int stockLength;
		try {
			stockLength = ZoneRemover.BASE_ZONES + ZoneTables.zoneTrailing();
		} catch (java.io.IOException ex) {
			Ui.error(frame, Ui.reason(ex), "Remove added zones");
			return;
		}
		if (zoArc.length <= stockLength) {
			Ui.message(frame, "This ZoneData has no added zones (stock layout).", "Remove added zones", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		final int n = zoArc.length - stockLength;
		java.util.List<String> refs = ZoneRemover.referencesToAdded(Workspace.session());   //the workspace's zones, unpacked edits included (F225)
		StringBuilder refWarn = new StringBuilder();
		if (!refs.isEmpty()) {
			refWarn.append("\n\nWARNING - these warps and script map changes lead into zones being removed and will\n"
					+ "dangle, or could not be checked:");
			for (int i = 0; i < Math.min(8, refs.size()); i++) {
				refWarn.append("\n  ").append(refs.get(i));
			}
			if (refs.size() > 8) {
				refWarn.append("\n  (+").append(refs.size() - 8).append(" more)");
			}
		}
		int rsl = Ui.confirm(frame,
				"Remove all " + n + " added zone(s) and restore the stock " + ctrmap.formats.codepatch.ZoneLimitPatch.BASE_ZONES + "-zone layout?\n\n"
				+ "The workspace is packed first (pending edits captured), then their content is\n"
				+ "DELETED from ZoneData. Afterwards you pick the code.ips to take the zone-limit\n"
				+ "patch out of - the stock game no longer needs it. Shop and item-icon patches in\n"
				+ "it are kept." + refWarn,
				"Remove added zones", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (rsl != JOptionPane.OK_OPTION) {
			return;
		}
		Workspace.packWorkspace(new Runnable() {
			@Override
			public void run() {
				try {
					//the zones out, their cached entries dropped, the archive re-read and their area
					//ids given back to the 8-bit budget - one operation (F043)
					ZoneRemover.Removed done = WorkspaceOps.removeAddedZones(Workspace.session(), workspaceOps(),
							CtrmapMainframe.this::readTheWorkspaceAgain);
					final int removed = done.zones;
					final java.util.List<Integer> areasBack = done.areasBack;
					mZonePnl.clearForkDeclinesFrom(ctrmap.formats.codepatch.ZoneLimitPatch.BASE_ZONES); //those slots no longer exist
					mZonePnl.loadEverything(new Runnable() {
						@Override
						public void run() {
							Ui.message(frame,
									"Removed " + removed + " added zone(s) - ZoneData is back to the stock layout.\n\n"
									+ (areasBack.isEmpty() ? "" : areasBack.size() + " area id(s) no zone names any more"
									+ " were given back for the next zones you add.\n\n")
									+ "Map regions forked for the removed zones remain in FieldData as unused\n"
									+ "tail data (harmless - nothing references them).",
									"Remove added zones", JOptionPane.INFORMATION_MESSAGE);
							stripZoneLimit();
						}
					});
				} catch (Exception ex) {
					Ui.error(frame, "Removal failed:\n" + ctrmap.util.Bytes.reason(ex), "Remove added zones");
				}
			}
		});
	}

	/**
	 * After the added zones go: the zone-limit patch out of the code.ips the user picks, the shop
	 * and icon patches in it kept. Deleting the file, as this used to say to, took them too (F152).
	 */
	private void stripZoneLimit() {
		javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
		fc.setDialogTitle("The code.ips to take the zone-limit patch out of (deployed, or the one you deploy)");
		fc.setSelectedFile(new java.io.File("code.ips"));
		java.io.File ips = Ui.choose(frame, fc, false);
		if (ips == null) {
			Ui.message(frame, "No code.ips was changed. The stock game no longer needs the zone-limit patch, so\n"
					+ "take it out before playing: run this again, or re-save your shop and item-icon\n"
					+ "patches into a fresh code.ips (delete it only if it held nothing but zones):\n"
					+ "  Azahar: load/mods/<titleid>/exefs/code.ips\n"
					+ "  Luma3DS: sdmc:/luma/titles/<titleid>/code.ips", "Remove added zones", JOptionPane.WARNING_MESSAGE);
			return;
		}
		try {
			int left = WorkspaceOps.stripZoneLimit(game, ips, workspaceOps());
			Ui.message(frame, "The zone-limit patch was taken out of " + ips.getName() + (left == 0
					? ". Nothing else was in it, so it now patches nothing and may be deleted."
					: "; the " + left + " patched byte(s) of the shop and item-icon patches in it were kept.")
					+ "\n\nIf it is not the deployed copy, deploy again so the game gets it.",
					"Remove added zones", JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception ex) {
			Ui.error(frame, "The zone-limit patch could not be taken out of " + ips.getName() + ":\n"
					+ Ui.reason(ex), "Remove added zones");
		}
	}

	/**
	 * Empties a zone's placed content - NPCs, warps, triggers, props - keeping the
	 * header and script (see {@link ZoneManager#clearZone}).
	 */
	private void emptyZoneAction() {
		if (!Workspace.isValid()) {
			Ui.error(frame, "Load a workspace first (Options > Workspace settings).", "Empty zone");
			return;
		}
		ctrmap.formats.garc.GARC zo = Workspace.getArchive(ArchiveType.ZONE_DATA);
		if (zo == null) {
			Ui.error(frame, "ZoneData archive unavailable.", "Empty zone");
			return;
		}
		//the open game's measured trailing-entry count, or a refusal - never the
		//"1 for XY, 2 for everything else" guess this used to make
		int zoneCount;
		try {
			zoneCount = ZoneTables.zoneCount(zo);
		} catch (java.io.IOException ex) {
			Ui.error(frame, Ui.reason(ex), "Empty zone");
			return;
		}
		int def = (loadedZone != null && loadedZone.index() >= 0 && loadedZone.index() < zoneCount) ? loadedZone.index() : 0;
		javax.swing.JSpinner idSpinner = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(def, 0, zoneCount - 1, 1));
		Object[] form = {
			"Clear a zone's placed content: NPCs, warps, triggers and props.",
			"The header, script, and wild encounters are kept - good for redoing a zone.",
			" ",
			"Zone (the number shown in the zone list):",
			idSpinner
		};
		Object[] opts = {"Empty it", "Cancel"};
		if (ctrmap.Ui.formOption(frame, form, "Empty zone", javax.swing.JOptionPane.OK_CANCEL_OPTION,
				javax.swing.JOptionPane.WARNING_MESSAGE, opts, opts[1]) != 0) {
			return;
		}
		int idx = (Integer) idSpinner.getValue();
		try {
			int removed = WorkspaceOps.clearZone(game, idx, workspaceOps(), CtrmapMainframe.this::readTheWorkspaceAgain);
			Ui.message(frame,
					"Zone " + idx + " emptied - removed " + removed + " placed object(s).\n\n"
					+ "Run File > Deploy to emulator (it packs first) to apply.",
					"Empty zone", javax.swing.JOptionPane.INFORMATION_MESSAGE);
			//the editor shows it cleared now, not after the user thinks to reselect it - and an
			//open zone left as it was would have written the old entities back (F234)
			reopenIfWritten();
		} catch (Exception ex) {
			Ui.error(frame, "Empty failed:\n" + ctrmap.util.Bytes.reason(ex), "Empty zone");
		}
	}

	/**
	 * Lists base zones (index &lt; 536) that can host interactive custom content
	 * (see {@link ZoneRepurposeScanner}) - the workaround for appended zones not
	 * being able to run scripts.
	 */
	private void findReusableZonesAction() {
		//the scanner is the other half of ZONE_APPEND: it exists because
		//appended zones cannot run scripts, so it hunts retail zones safe to
		//overwrite instead. "This is ORAS-only in v1" named neither the game
		//the user had open nor anything they could act on.
		if (refuseUnsupported(GameProfile.Feature.ZONE_APPEND, "Find reusable zones",
				"Finding reusable base zones",
				"The scan reads every zone header, its script and its warps to judge"
				+ " whether overwriting it is safe. That judgement rests on the zone"
				+ " header layout, the script format and the base/appended zone boundary"
				+ " as measured on Omega Ruby / Alpha Sapphire.")) {
			return;
		}
		//OFF THE EVENT THREAD (F225): 536 zones were read behind a wait cursor with the window frozen,
		//and anything thrown left the action dead with nothing shown
		final WorkspaceSession session = Workspace.session();
		frame.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
		new javax.swing.SwingWorker<ZoneRepurposeScanner.Scan, Void>() {
			@Override
			protected ZoneRepurposeScanner.Scan doInBackground() {
				return ZoneRepurposeScanner.scanZones(session);
			}

			@Override
			protected void done() {
				frame.setCursor(java.awt.Cursor.getDefaultCursor());
				try {
					showReusableZones(get());
				} catch (InterruptedException | java.util.concurrent.ExecutionException failed) {
					Ui.error(frame, "The zones could not be scanned: " + Ui.reason(failed.getCause() != null ? failed.getCause() : failed),
							"Find reusable base zones");
				}
			}
		}.execute();
	}

	/** The reusable-zone list, and the zones the scan could not read (F225), shown. */
	private void showReusableZones(ZoneRepurposeScanner.Scan scan) {
		javax.swing.JTextArea ta = new javax.swing.JTextArea(ZoneRepurposeScanner.report(scan), 28, 76);
		ta.setEditable(false);
		ta.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
		ta.setCaretPosition(0);
		//it arrives when the scan of every zone finishes, not on the click: it waits for the owner (Ui.untilInUse)
		Ui.untilInUse();
		ctrmap.Ui.show(frame, new javax.swing.JScrollPane(ta), ta.getText(), "Find reusable base zones", JOptionPane.INFORMATION_MESSAGE);
	}

	/**
	 * The first FieldData region THE LOADED ZONE OWNS, or -1 - the right default for
	 * the OBJ tools so users do not have to know region numbers.
	 *
	 * <p>OWNS, not "the matrix points at". A matrix can be shared by several zones and
	 * 139 retail zones sit on a shared one; its first filled cell belongs to whichever
	 * zone owns the top-left corner. Measured: on 39 of the 61 zones whose matrix
	 * carries an ownership grid that is a DIFFERENT zone - so this offered Mossdeep
	 * City an export of Route 125, and an import back would have written over it.
	 * A wrong default here is not a wrong default; it is somebody else's map edited.
	 *
	 * <p>-1 IS AN ANSWER, NOT A FAILURE (census L.56): no zone open, or a zone whose matrix
	 * names no region. A matrix that cannot be read is thrown, with its cause - it used to
	 * be the same -1, and the blank canvas told such a zone it owned no map region.
	 */
	private int defaultRegionForLoadedZone() throws java.io.IOException {
		if (loadedZone == null || loadedZone.open() == null) {
			return -1;
		}
		int matrixId = loadedZone.open().header.mapmatrixID;
		File mmFile = Workspace.getWorkspaceFile(ArchiveType.MAP_MATRIX, matrixId);
		if (mmFile == null) {
			return -1;
		}
		byte[] mm;
		try {
			mm = java.nio.file.Files.readAllBytes(mmFile.toPath());
			ctrmap.formats.mapmatrix.MapMatrix.Grid.of(mm);   //a damaged grid is refused here, in words, not read as empty
		} catch (java.io.IOException | IllegalArgumentException unread) {
			throw new java.io.IOException("This zone's map matrix " + matrixId + " could not be read - "
				+ ctrmap.util.Bytes.reason(unread), unread);
		}
		return ctrmap.formats.mapmatrix.MapMatrix.ownRegionId(mm, loadedZone.index());
	}

	/**
	 * defaultRegionForLoadedZone for an OBJ form, which can do without one: the region, or
	 * -1 with the reason in {@code why[0]} when the matrix could not be read - so the form
	 * says why it has no default instead of quietly starting at region 0, another zone's map.
	 */
	private int formDefaultRegion(String[] why) {
		try {
			return defaultRegionForLoadedZone();
		} catch (java.io.IOException unread) {
			why[0] = "No default region: " + ctrmap.util.Bytes.reason(unread);
			return -1;
		}
	}

	/** Exports a map region's 3D model to a Blender-ready OBJ (Map menu). */
	private void exportMapObjAction() {
		if (!Workspace.isValid()) {
			Ui.error(frame, "Load a workspace first (Options > Workspace settings).", "Export map to OBJ");
			return;
		}
		int fieldCount = Workspace.getArchive(ArchiveType.FIELD_DATA).length;
		String[] unread = {null};
		int def = formDefaultRegion(unread);
		javax.swing.JSpinner idSpinner = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(def >= 0 ? def : 0, 0, fieldCount - 1, 1));
		Object[] form = {
			"Export a map region's 3D model as a Wavefront OBJ for Blender.",
			unread[0] != null ? unread[0]
				: def >= 0 ? "(Defaulted to the loaded zone's map region.)" : "Find the ID per cell in the Matrix Editor.",
			"FieldData region ID:",
			idSpinner,
			" ",
			"Edit it in Blender, keep the group names (mesh<N>_...) intact, then use",
			"Map > Import OBJ into map region (Blender) to bring the changes back."
		};
		if (!ctrmap.Ui.form(frame, form, "Export map to OBJ")) {
			return;
		}
		int id = (Integer) idSpinner.getValue();
		//the OBJ round trip remembers one folder, both ways (census L.31): it started from the default every time
		JFileChooser fc = openDialog("LAST_DIR_OBJ", "Save OBJ");
		fc.setSelectedFile(new File(fc.getCurrentDirectory(), "region" + id + ".obj"));
		if (pickedToSave(fc, "LAST_DIR_OBJ") == null) {
			return;
		}
		try {
			GR gr = new GR(Workspace.getWorkspaceFile(ArchiveType.FIELD_DATA, id), game);
			byte[] model = gr.getFile(1);
			if (!ctrmap.formats.h3d.BchMapModel.isMapModel(model)) {
				Ui.error(frame, "FieldData region " + id + " has no editable map model.", "Export map to OBJ");
				return;
			}
			ctrmap.formats.h3d.BchMapModel bmm = new ctrmap.formats.h3d.BchMapModel(model);
			//THE FILE IS AN EXPORT, written where every export is (F209d): built in memory, in the charset the
			//FileWriter it replaces used, and written whole
			java.io.StringWriter w = new java.io.StringWriter();
			java.util.List<Integer> skipped = ctrmap.formats.h3d.MapModelObj.export(bmm, w);
			Exports.write(fc.getSelectedFile(), w.toString().getBytes(java.nio.charset.Charset.defaultCharset()));
			Ui.message(frame,
					"Exported region " + id + " (" + bmm.meshes.size() + " meshes) to\n" + fc.getSelectedFile().getAbsolutePath()
					+ (skipped.isEmpty() ? "" : "\n\n" + skipped.size() + " mesh(es) use an exotic vertex format and were skipped;\nthey stay untouched on import."),
					"Export map to OBJ", JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception ex) {
			Ui.error(frame, "Export failed:\n" + ctrmap.util.Bytes.reason(ex), "Export map to OBJ");
		}
	}

	/** How many meshes FieldData region {@code region}'s map model has; 0 when it has no map model. */
	private int templateMeshes(int region) {
		byte[] m = new GR(Workspace.getWorkspaceFile(ArchiveType.FIELD_DATA, region), game).getFile(1);
		return ctrmap.formats.h3d.BchMapModel.isMapModel(m) ? new ctrmap.formats.h3d.BchMapModel(m).meshCount : 0;
	}

	/** Imports a (Blender-edited) OBJ back into a map region's 3D model (Map menu). */
	private void importMapObjAction() {
		if (!Workspace.isValid()) {
			Ui.error(frame, "Load a workspace first (Options > Workspace settings).", "Import OBJ");
			return;
		}
		int fieldCount = Workspace.getArchive(ArchiveType.FIELD_DATA).length;
		String[] unread = {null};
		int def = formDefaultRegion(unread);
		javax.swing.JSpinner idSpinner = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(def >= 0 ? def : 0, 0, fieldCount - 1, 1));
		Object[] form = {
			"Import a Blender-edited OBJ back into a map region.",
			"Use the SAME region you exported from" + (def >= 0 ? " (defaulted to the loaded zone's)." : "."),
			unread[0] != null ? unread[0] : " ",
			"FieldData region ID:",
			idSpinner,
			" ",
			"Groups named mesh<N>_... replace that mesh; other groups are added to",
			"the mesh whose material matches their usemtl. Textures follow the",
			"nearest original surface automatically."
		};
		if (!ctrmap.Ui.form(frame, form, "Import OBJ")) {
			return;
		}
		int id = (Integer) idSpinner.getValue();
		JFileChooser fc = openDialog("LAST_DIR_OBJ", "Pick the edited OBJ");
		if (picked(fc, "LAST_DIR_OBJ") == null) {
			return;
		}
		try {
			java.util.List<ctrmap.formats.h3d.MapModelObj.ObjMesh> parsed;
			java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(fc.getSelectedFile()));
			try {
				parsed = ctrmap.formats.h3d.MapModelObj.parse(r);
			} finally {
				r.close();
			}
			File grFile = Workspace.getWorkspaceFile(ArchiveType.FIELD_DATA, id);
			GR gr = new GR(grFile, game);
			byte[] model = gr.getFile(1);
			if (!ctrmap.formats.h3d.BchMapModel.isMapModel(model)) {
				Ui.error(frame, "FieldData region " + id + " has no editable map model.", "Import OBJ");
				return;
			}
			//groups whose material this map does not have can be injected as BRAND-NEW
			//materials cloned from a template mesh (render config + texture refs)
			byte[] templateModel = null;
			int templateMesh = -1;
			{
				ctrmap.formats.h3d.BchMapModel bm = new ctrmap.formats.h3d.BchMapModel(model);
				java.util.Set<String> have = new java.util.HashSet<>();
				for (int mi = 0; mi < bm.matCount; mi++) {
					String n = bm.getMaterialName(mi);
					if (n != null) {
						have.add(ctrmap.formats.h3d.MapModelObj.sanitize(n).toLowerCase());
					}
				}
				java.util.List<String> newMats = new java.util.ArrayList<>();
				for (ctrmap.formats.h3d.MapModelObj.ObjMesh om : parsed) {
					if (om.meshIndex < 0 && om.material != null && !om.material.isEmpty()
							&& !have.contains(ctrmap.formats.h3d.MapModelObj.sanitize(om.material).toLowerCase())
							&& !newMats.contains(om.material)) {
						newMats.add(om.material);
					}
				}
				if (!newMats.isEmpty()) {
					javax.swing.JSpinner tRegion = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(id, 0, fieldCount - 1, 1));
					javax.swing.JSpinner tMesh = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(0, 0, 0, 1));
					//BOUNDED BY THE REGION'S MESHES (F226): this was 0-999, and a number past the template's
					//meshes skipped every new-material group with an out-of-bounds text
					javax.swing.JLabel tMeshes = new javax.swing.JLabel();
					Runnable boundToRegion = () -> boundMeshSpinner(tMesh, tMeshes,
							templateMeshes(((Number) tRegion.getValue()).intValue()));
					boundToRegion.run();
					tRegion.addChangeListener(changed -> boundToRegion.run());
					Object[] tForm = {
						"These OBJ groups use materials this map does not have:",
						"  " + newMats,
						" ",
						"They can be created as NEW materials by cloning a template mesh's",
						"render setup + texture. Pick any mesh whose LOOK (texture) fits:",
						"Template region:", tRegion,
						"Template mesh number (see exported group names, mesh<N>_...):", tMesh, tMeshes,
						" ",
						"Cancel imports only the groups whose materials already exist."
					};
					if (ctrmap.Ui.form(frame, tForm, "New materials")) {
						GR tgr = new GR(Workspace.getWorkspaceFile(ArchiveType.FIELD_DATA, (Integer) tRegion.getValue()), game);
						byte[] tm = tgr.getFile(1);
						if (ctrmap.formats.h3d.BchMapModel.isMapModel(tm)) {
							//NO SECOND BOUND (2026-10-16): a mesh past the template's was refused here when the spinner
							//ran 0-999; since F226 boundMeshSpinner holds it to this region's meshes, counted the same
							//way, and the spinner refuses a number typed past its top - no input reached this refusal
							//(the assembly, CtrmapMainframe 3336, SURVIVED), so it is gone
							templateModel = tm;
							templateMesh = (Integer) tMesh.getValue();
						} else {
							Ui.message(frame, "That region has no map model - new-material groups will be skipped.", "Import OBJ", JOptionPane.WARNING_MESSAGE);
						}
					}
				}
			}
			java.util.List<ctrmap.formats.h3d.MapModelObjImporter.Outcome> outcomes = new java.util.ArrayList<>();
			byte[] edited = ctrmap.formats.h3d.MapModelObjImporter.apply(model, parsed, outcomes, templateModel, templateMesh);
			//sanity: the edited model must re-parse clean before it touches the workspace
			ctrmap.formats.h3d.BchMapModel check = new ctrmap.formats.h3d.BchMapModel(edited);
			if (!check.validate().isEmpty()) {
				Ui.error(frame, "The edited model failed validation - nothing was changed:\n" + check.validate(), "Import OBJ");
				return;
			}
			try {
				WorkspaceOps.storeMapModel(game, "Import OBJ into region " + id, grFile, gr, edited, workspaceOps(),
					CtrmapMainframe.this::readTheWorkspaceAgain);
			} catch (RuntimeException notWritten) {
				Ui.error(frame, "Could not write the model into the workspace:\n"
					+ Ui.reason(notWritten), "Import OBJ");
				return;
			}
			StringBuilder sb = new StringBuilder("Imported into region " + id + ":\n");
			for (ctrmap.formats.h3d.MapModelObjImporter.Outcome oc : outcomes) {
				sb.append("  ").append(oc.group).append(": ").append(oc.action)
				  .append(" (").append(oc.vertices).append(" verts, ").append(oc.faces).append(" faces)\n");
			}
			sb.append("\nRun File > Deploy to emulator to see it in game (packs automatically).");
			Ui.message(frame, sb.toString(), "Import OBJ", JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception ex) {
			Ui.error(frame, "Import failed:\n" + ctrmap.util.Bytes.reason(ex), "Import OBJ");
		}
	}

	/**
	 * "Start this zone's map from scratch": forks the zone's geometry to a
	 * private copy (base town untouched), then blanks every private region to a
	 * flat walkable canvas in the chosen ground material. The natural first step
	 * for building a brand-new town/facility.
	 */
	private void blankCanvasAction() {
		//forks the zone's geometry before blanking it, so it needs exactly what
		//GeometryForker.requireGeometryForkSupport needs - asked here so the refusal
		//arrives before the material picker, not after it
		if (refuseUnsupported(GameProfile.Feature.AREA_FORK, "Blank map canvas",
				"Starting a zone's map from scratch",
				"It first gives the zone its OWN geometry (appending to FieldData and the"
				+ " MapMatrix), then rewrites each private region's BCH map model in place."
				+ " Both the fork offsets and the model layout were measured on Omega Ruby"
				+ " / Alpha Sapphire.")) {
			return;
		}
		if (loadedZone == null || !loadedZone.isOpen() || loadedZone.index() < 0) {
			Ui.error(frame, "Load the zone first (Zone tab).", "Blank map canvas");
			return;
		}
		final int zoneIndex = loadedZone.index();
		//ground-material picker from the first region THIS ZONE OWNS - see
		//defaultRegionForLoadedZone for what taking the matrix's first cell instead
		//offered: on a shared matrix it is another zone's tileset
		ctrmap.formats.h3d.BchMapModel probe;
		try {
			int rid = defaultRegionForLoadedZone();
			if (rid < 0) {
				Ui.error(frame, "This zone owns no map region, so there is no ground material to"
					+ " copy.", "Blank map canvas");
				return;
			}
			GR gr = new GR(Workspace.getWorkspaceFile(ArchiveType.FIELD_DATA, rid), game);
			probe = new ctrmap.formats.h3d.BchMapModel(gr.getFile(1));
		} catch (Exception ex) {
			Ui.error(frame, "Could not inspect the zone's map:\n" + ctrmap.util.Bytes.reason(ex), "Blank map canvas");
			return;
		}
		//The zone model's materials, THIS MAP'S GROUND FIRST and the rest
		//biggest first - so small parts (doors, windows, tree bits) sink to the
		//bottom instead of cluttering the top, and the entry labelled as the
		//ground really is it. Ordering by size alone had been answering both
		//questions at once, and on 312 of the first 400 retail regions the
		//biggest mesh is a wall, a fence or the sea.
		int[] order = ctrmap.formats.tilemap.PaintedMaterials.groundFirstMeshOrder(probe);
		java.util.List<String> items = new java.util.ArrayList<>();
		java.util.List<Integer> meshIds = new java.util.ArrayList<>();
		for (int mesh : order) {
			String label = probe.getMaterialName(probe.getMeshMaterialIndex(mesh))
					+ "  (" + probe.getTriangles(mesh).length / 3 + " faces)";
			if (items.isEmpty()) {
				label += "  - this map's main ground";
			}
			items.add(label);
			meshIds.add(mesh);
		}
		int def = 0;
		//visual picker: each entry shows what the material ACTUALLY looks like
		final java.util.List<javax.swing.ImageIcon> matIcons = new java.util.ArrayList<>();
		byte[] probeBytes = probe.raw;
		java.util.List<ctrmap.formats.h3d.texturing.H3DTexture> worldTex
				= mTileMapPanel != null ? mTileMapPanel.getWorldTextures() : null;
		for (int i = 0; i < items.size(); i++) {
			String matName = items.get(i).substring(0, items.get(i).indexOf("  ("));
			java.awt.image.BufferedImage img = ctrmap.formats.tilemap.TerrainTextures.materialImage(probeBytes, worldTex, matName);
			matIcons.add(img == null ? null
					: new javax.swing.ImageIcon(img.getScaledInstance(40, 40, java.awt.Image.SCALE_SMOOTH)));
		}
		javax.swing.JComboBox<String> matPicker = new javax.swing.JComboBox<>(items.toArray(new String[0]));
		matPicker.setRenderer(new javax.swing.DefaultListCellRenderer() {
			@Override
			public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
					int index, boolean isSelected, boolean cellHasFocus) {
				JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				int idx = index >= 0 ? index : matPicker.getSelectedIndex();
				l.setIcon(idx >= 0 && idx < matIcons.size() ? matIcons.get(idx) : null);
				return l;
			}
		});
		matPicker.setSelectedIndex(def);
		Object[] form = {
			"Replace THIS ZONE's map with a flat, walkable blank canvas.",
			"The zone gets its own private map first - the town it was cloned",
			"from keeps its map. Then build with prefabs, the Geometry tool,",
			"or Blender.",
			" ",
			"Ground material (the floor's look). These are THIS map's own",
			"materials, " + ctrmap.formats.tilemap.PaintedMaterials.GROUND_FIRST_ORDER + ".",
			"City/cave maps use texture ATLASES, so a preview can look",
			"busy; the canvas still paints it as a clean tiled floor.",
			matPicker,
			" ",
			"This packs the workspace when done."
		};
		if (!ctrmap.Ui.form(frame, form, "Blank map canvas")) {
			return;
		}
		final int groundMesh = meshIds.get(matPicker.getSelectedIndex());
		//a re-run paints over the zone's own private regions instead of appending another set
		//beside them; the fork and every region's blank are one unit, and the regions that could
		//not be emptied are named in the report rather than called blank (F072)
		final GeometryForker.Canvas canvas;
		try {
			canvas = WorkspaceOps.blankCanvas(game, zoneIndex, groundMesh, workspaceOps(),
					CtrmapMainframe.this::readTheWorkspaceAgain);
		} catch (Exception ex) {
			Ui.error(frame, "Blank map canvas did not run, and nothing was changed:\n" + Ui.reason(ex),
					"Blank map canvas");
			return;
		}
		Workspace.packWorkspace(new Runnable() {
			@Override
			public void run() {
				mZonePnl.loadEverything(new Runnable() {
					@Override
					public void run() {
						mZonePnl.selectZone(canvas.zoneIndex);
						Ui.message(frame, canvas.report(), "Blank map canvas",
								canvas.kept.length > 0 ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
					}
				});
			}
		});
	}

	/**
	 * "Set up a Battle facility here": replaces the loaded BASE zone with a copy
	 * of a retail Battle facility lobby (Maison or Institute) - script AND
	 * entities verbatim, so the facility's engine logic is exactly retail - then
	 * forks its geometry so edits stay local. The starting point for ANY custom
	 * battle facility: author opponents in the pools' free slots (Game Data ->
	 * Facility opponents) and edit the text, then deploy. Must target a base
	 * zone (index &lt; 536) because appended zones cannot run field scripts.
	 */
	private void setupFacilityAction() {
		//MAISON, not "is it ORAS": what this needs is a game whose battle
		//facility is known - the lobby zone indices copied below and the
		//opponent pools the copy will battle out of
		if (refuseUnsupported(GameProfile.Feature.MAISON, "Set up Battle facility",
				"Setting up a Battle facility",
				"It replaces a zone with a verbatim copy of a retail facility lobby, which"
				+ " means knowing which zone that lobby is and where the engine keeps the"
				+ " opponent pools it draws from. Both were measured on Omega Ruby / Alpha"
				+ " Sapphire.")) {
			return;
		}
		if (loadedZone == null || loadedZone.index() < 0) {
			Ui.error(frame, "Load the base zone to convert first (Zone tab).", "Set up Battle facility");
			return;
		}
		final int dstIndex = loadedZone.index();
		int baseZones;
		try {
			baseZones = ZoneTables.zoneCount(Workspace.getArchive(ArchiveType.ZONE_DATA));
		} catch (java.io.IOException ex) {
			Ui.error(frame, Ui.reason(ex), "Set up Battle facility");
			return;
		}
		if (dstIndex >= baseZones) {
			Ui.error(frame,
					"This is an appended zone (index " + dstIndex + "). Appended zones cannot run field\n"
					+ "scripts, so a facility must go in a base zone (< " + baseZones + "). Load or repurpose a\n"
					+ "base zone (e.g. an unused one, via Zone > Empty zone) and try again.",
					"Set up Battle facility");
			return;
		}
		//two ways to build a facility - offer both honestly
		String[] paths = {"Independent battles (your own trainers)", "Clone a retail facility (full engine)"};
		int path = Ui.option(frame,
				"How should this facility's battles work?\n\n"
				+ "INDEPENDENT: an NPC that battles trainer entries YOU author (Game Data ->\n"
				+ "Trainers), with streak + BP rewards in its own script. Nothing vanilla is\n"
				+ "touched, works in any zone, no zone replacement. (New - unproven in-game.)\n\n"
				+ "CLONE: this zone is replaced by a copy of the Battle Maison or Institute -\n"
				+ "the full retail engine (formats, streak saves, scoring), but its opponent\n"
				+ "pools are ENGINE-WIDE, shared with the retail facility.",
				"Set up Battle facility", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, paths, paths[0]);
		if (path < 0) {
			return;
		}
		if (path == 0) {
			Ui.message(frame,
					"To add an independent battle NPC:\n\n"
					+ "1. Author its opponents' teams in Game Data > Edit trainer: pick unused\n"
					+ "   trainer entries - blank-named ones are safe to repurpose.\n"
					+ "2. On the World Editor, choose the NPC tool -> Add -> \"Battle challenge\n"
					+ "   (own trainers)\" and build the lineup, rewards and dialogue there.\n\n"
					+ "Each NPC is one challenge lane; place several for a multi-lane facility.",
					"Independent battle facility", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		String[] kinds = {"Battle Maison (5 formats, Chatelaines)", "Battle Institute (single test)"};
		Object kind = Ui.input(frame,
				"Replace zone " + dstIndex + " with a copy of which retail facility?\n"
				+ "(Its script + NPCs are copied verbatim - the engine logic is retail.\n"
				+ "You then author the opponents and text.)",
				"Set up Battle facility", JOptionPane.PLAIN_MESSAGE, kinds, kinds[0]);
		if (kind == null) {
			return;
		}
		//the lobby's zone is the game's fact, asked of its profile (F009)
		//BY EQUALITY (F157): this was kind == kinds[1], right only while the dialog hands back the very
		//object it was given - an equal answer that was not the same object cloned the other facility
		final int srcIndex = Workspace.profile().facilityLobbyZone(kinds[1].equals(kind)
				? GameProfile.Facility.INSTITUTE : GameProfile.Facility.MAISON);
		if (srcIndex < 0) {
			Ui.error(frame, "CTRMap has not measured where " + kind + "'s lobby is in "
					+ Workspace.profile().displayName() + ", so it cannot copy it.", "Set up Battle facility");
			return;
		}
		if (srcIndex == dstIndex) {
			Ui.error(frame, "That IS the source facility zone - pick a different base zone to convert.", "Set up Battle facility");
			return;
		}
		int confirm = Ui.confirm(frame,
				"Zone " + dstIndex + " will be COMPLETELY REPLACED by a copy of the facility\n"
				+ "lobby (zone " + srcIndex + "): its map, NPCs and script. The copy gets its own\n"
				+ "geometry, so editing it will not change the real facility.\n\n"
				+ "Note: opponent pools are ENGINE-WIDE (shared with the retail facility) -\n"
				+ "author new teams in the pools' FREE slots via Game Data > Edit battle facility opponents.\n"
				+ "Then edit the text and Deploy. Continue?",
				"Set up Battle facility", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (confirm != JOptionPane.OK_OPTION) {
			return;
		}
		try {
			mZonePnl.clearForkDecline(dstIndex); //the slot holds a new zone now
			WorkspaceOps.cloneWithOwnMap(game, srcIndex, dstIndex, workspaceOps(), CtrmapMainframe.this::readTheWorkspaceAgain);
			Workspace.packWorkspace(new Runnable() {
				@Override
				public void run() {
					mZonePnl.loadEverything(new Runnable() {
						@Override
						public void run() {
							mZonePnl.selectZone(dstIndex);
							Ui.message(frame,
									"Zone " + dstIndex + " is now a copy of the facility (from zone " + srcIndex + ").\n\n"
									+ "Next: Game Data > Edit battle facility opponents to author the teams (use the\n"
									+ "pools' free slots - they are shared with the retail facility),\n"
									+ "edit the facility's text, and Deploy to try it in the emulator.\n"
									+ "(A grown/custom facility is unproven in-game - test it.)",
									"Set up Battle facility", JOptionPane.INFORMATION_MESSAGE);
						}
					});
				}
			});
		} catch (Exception ex) {
			Ui.error(frame, "Facility setup failed:\n" + ctrmap.util.Bytes.reason(ex), "Set up Battle facility");
		}
	}

	/**
	 * Grows or shrinks the loaded zone's map to WxH regions ({@link MapResizer}) - new
	 * cells become blank canvases in the zone's own area style, and cells past a
	 * smaller size are dropped with their ground (F020).
	 */
	private void resizeMapAction() {
		//growing a map gives the zone's old cells their own copies when it shares
		//them (F018), so it asks the same question the forkers ask rather than
		//asking which game is open
		if (refuseUnsupported(GameProfile.Feature.AREA_FORK, "Resize map",
				"Growing a zone's map",
				"New map cells are appended to FieldData and wired into a rebuilt"
				+ " MapMatrix after the zone's geometry has been made private. The"
				+ " container layouts and every offset involved were measured on Omega"
				+ " Ruby / Alpha Sapphire.")) {
			return;
		}
		if (loadedZone == null || !loadedZone.isOpen() || loadedZone.index() < 0) {
			Ui.error(frame, "Load the zone first (Zone tab).", "Resize map");
			return;
		}
		final int zoneIndex = loadedZone.index();
		//the spinners start at the map's own size: it offered 2x1 for every map, so OK on a 3x2 map
		//asked for a shrink nobody meant (F020)
		int[] size;
		try {
			size = MapResizer.currentSize(game, zoneIndex);
		} catch (IOException | RuntimeException unread) {
			Ui.error(frame, "The zone's map could not be read:\n" + ctrmap.util.Bytes.reason(unread), "Resize map");
			return;
		}
		javax.swing.JSpinner wSpin = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(size[0], 1, Math.max(4, size[0]), 1));
		javax.swing.JSpinner hSpin = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(size[1], 1, Math.max(4, size[1]), 1));
		Object[] form = {
			"Grow or shrink this zone's map, now " + size[0] + "x" + size[1] + " regions (each = one 40x40-tile map).",
			"Cells that fit stay (given this zone's own copies first if another zone",
			"shares this map); NEW cells become flat blank canvases in this",
			"zone's own style - build on them with prefabs, the Geometry tool, or Blender.",
			"Cells past a smaller size are dropped with their ground - refused while this",
			"zone's spawn point, NPCs, warps, props or triggers stand in one.",
			" ",
			"Width (regions):", wSpin,
			"Height (regions):", hSpin,
			" ",
			"EXPERIMENTAL: multi-region retail maps prove the engine path, but a resized",
			"custom map, grown or shrunk, has not been booted in-game yet. This packs when done."
		};
		if (!ctrmap.Ui.form(frame, form, "Resize map")) {
			return;
		}
		try {
			final MapResizer.ResizeResult r = WorkspaceOps.resize(game, zoneIndex, ((Number) wSpin.getValue()).intValue(),
					((Number) hSpin.getValue()).intValue(), workspaceOps(), CtrmapMainframe.this::readTheWorkspaceAgain);
			Workspace.packWorkspace(new Runnable() {
				@Override
				public void run() {
					mZonePnl.loadEverything(new Runnable() {
						@Override
						public void run() {
							mZonePnl.selectZone(zoneIndex);
							Ui.message(frame,
									"Map resized " + r.oldW + "x" + r.oldH + " -> " + r.newW + "x" + r.newH
									+ " (" + (r.newRegions.length == 0 ? "no new region" : "new blank region(s) "
									+ java.util.Arrays.toString(r.newRegions)) + ", matrix " + r.newMatrix + ").\n"
									+ (r.droppedRegions.length == 0 ? "" : "The dropped cells' region(s) "
											+ java.util.Arrays.toString(r.droppedRegions) + " stay in FieldData, unused by this zone.\n")
									+ (r.ownRegions.length == 0 ? "" : "The old cells now draw this zone's own copies "
											+ java.util.Arrays.toString(r.ownRegions) + ", so editing them changes no other zone"
											+ (r.otherZones.length == 0 ? "" : " - though they still carry ground belonging to zone(s) "
											+ java.util.Arrays.toString(r.otherZones)) + ".\n")
									+ "Deploy to emulator to walk the new area.",
									"Resize map", JOptionPane.INFORMATION_MESSAGE);
						}
					});
				}
			});
		} catch (Exception ex) {
			Ui.error(frame, "Resize failed:\n" + ctrmap.util.Bytes.reason(ex), "Resize map");
		}
	}

	/**
	 * Milestone 1 of native map editing: replace a map region's visual 3D model
	 * (FieldData GR subfile 1) with an externally authored .bch, via the proven
	 * byte-faithful container passthrough (storeFile). This enables the
	 * "build a map in a 3D tool, drop it into the game" workflow; every other
	 * subfile and every untouched map region stays byte-exact. It is NOT
	 * in-editor geometry editing - that needs a full BCH model writer.
	 */
	private void importMapModelAction() {
		if (!Workspace.isValid()) {
			Ui.error(frame, "Load a workspace first (Options > Workspace settings).", "Import map model");
			return;
		}
		int fieldCount = Workspace.getArchive(ArchiveType.FIELD_DATA).length;
		javax.swing.JSpinner idSpinner = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(0, 0, fieldCount - 1, 1));
		Object[] form = {
			"Replace the visual 3D model of a map region.",
			"The region's FieldData ID is shown per cell in the Matrix Editor",
			"(\"Refers to: FIELD_DATA/<id>\"). Enter that ID:",
			idSpinner,
			" ",
			"The .bch must be a valid ORAS field map model (e.g. exported from a",
			"3D tool and converted with SPICA). This does NOT edit geometry in-app."
		};
		if (!ctrmap.Ui.form(frame, form, "Import map model")) {
			return;
		}
		int id = (Integer) idSpinner.getValue();

		//THROUGH THE HELPERS (census L.31): this was the same memory written out again, under a key no other dialog used
		File chosen = picked(openDialog("LAST_DIR_BCH", "Open map model .bch"), "LAST_DIR_BCH");
		if (chosen == null) {
			return;
		}

		byte[] bch;
		try {
			bch = java.nio.file.Files.readAllBytes(chosen.toPath());
		} catch (java.io.IOException ex) {
			Ui.error(frame, "Could not read the file:\n" + ctrmap.util.Bytes.reason(ex), "Import map model");
			return;
		}
		//sanity-check that it parses as a BCH model
		try {
			ctrmap.formats.h3d.BCHFile parsed = new ctrmap.formats.h3d.BCHFile(bch);
			if (parsed.errorlevel != 0 || parsed.models.isEmpty()) {
				Ui.error(frame, "That file does not parse as a BCH model (no models / read error).", "Import map model");
				return;
			}
		} catch (RuntimeException ex) {
			Ui.error(frame, "That file is not a readable BCH model:\n" + ctrmap.util.Bytes.reason(ex), "Import map model");
			return;
		}

		Object[] warnOpts = {"Continue", "Cancel"};
		int confirm = Ui.option(frame,
				"EXPERIMENTAL - untested on real hardware.\n\n"
				+ "This replaces the visual model of FieldData region " + id + " with your\n"
				+ ".bch. The archive rebuild is byte-faithful for every other region and\n"
				+ "subfile, but whether the GAME loads a rebuilt/replaced map model is not\n"
				+ "yet verified. Keep a RomFS backup and test in Citra before hardware.\n\n"
				+ "Continue?",
				"Import map model", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE,
				warnOpts, warnOpts[1]);
		if (confirm != 0) {
			return;
		}
		try {
			File grFile = Workspace.getWorkspaceFile(ArchiveType.FIELD_DATA, id);
			GR gr = new GR(grFile, game);
			if (gr.len < 2) {
				Ui.error(frame, "FieldData entry " + id + " is not a map region container.", "Import map model");
				return;
			}
			try {
				WorkspaceOps.storeMapModel(game, "Import a map model into region " + id, grFile, gr, bch, workspaceOps(),
					CtrmapMainframe.this::readTheWorkspaceAgain);
			} catch (RuntimeException notWritten) {
				Ui.error(frame, "Could not write the model into the workspace:\n"
					+ Ui.reason(notWritten), "Import map model");
				return;
			}
		} catch (RuntimeException ex) {
			Ui.error(frame, "Import failed:\n" + ctrmap.util.Bytes.reason(ex), "Import map model");
			return;
		}
		Ui.message(frame,
				"Map model imported into FieldData region " + id + ".\n\n"
				+ "Run File > Pack Workspace, then load the RomFS as a LayeredFS mod and\n"
				+ "TEST IN CITRA - confirm the map loads before trusting it.",
				"Import map model", javax.swing.JOptionPane.INFORMATION_MESSAGE);
	}

	private void adjustSplitPanes() {
		Dimension vsSize = mCamScrollPane.getVerticalScrollBar().getSize();
		mCamScrollPane.setMinimumSize(new Dimension(mCamEditForm.getMinimumSize().width + vsSize.width + 10, mCamEditForm.getMinimumSize().height));
		mCamScrollPane.setPreferredSize(mCamScrollPane.getMinimumSize());
		double loc = 1d - (double) (jsp.getRightComponent().getPreferredSize().width + jsp.getDividerSize() - 3) / (double) tileEditMasterPnl.getWidth();
		if (loc < 0.1) {
			loc = 0.1d;
		}
		jsp.setDividerLocation(loc);
		double loc2 = 1d - (double) (mCollEditPanel.getPreferredSize().width + jsp2.getDividerSize() - 3) / (double) collEditMasterPnl.getWidth();
		if (loc2 < 0.1) {
			loc2 = 0.1d;
		}
		jsp2.setDividerLocation(loc2);
		double loc3 = 1d - (double) (mMtxEditForm.getPreferredSize().width + jsp3.getDividerSize() - 3) / (double) mtxEditMasterPnl.getWidth();
		if (loc3 < 0.1) {
			loc3 = 0.1d;
		}
		jsp3.setDividerLocation(loc3);
	}
}
