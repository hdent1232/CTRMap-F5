package ctrmap;

import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.prefs.Preferences;

import javax.swing.ButtonGroup;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

import ctrmap.formats.containers.GR;
import ctrmap.formats.containers.MM;
import ctrmap.formats.WavefrontOBJ;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.mapmatrix.MapMatrix;
import ctrmap.formats.zone.Zone;
import ctrmap.humaninterface.AboutDialog;
import ctrmap.humaninterface.CM3DInputManager;
import ctrmap.humaninterface.CM3DRenderable;
import ctrmap.humaninterface.CameraEditForm;
import ctrmap.humaninterface.CollEditPanel;
import ctrmap.humaninterface.CollInputManager;
import ctrmap.humaninterface.ExtrasPanel;
import ctrmap.humaninterface.GLPanel;
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
import ctrmap.humaninterface.WorkspaceSettings;
import ctrmap.humaninterface.ZoneLoadingPanel;
import ctrmap.humaninterface.builder.Builder;
import ctrmap.humaninterface.tools.AbstractTool;
import ctrmap.humaninterface.tools.SetTool;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
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

	public static JFrame frame;
	public static JTabbedPane tabs;

	public static JMenuBar menubar;
	public static JMenu filemenu;
	public static JMenu toolsmenu;
	public static JMenu optionsmenu;
	public static JMenu helpmenu;
	public static JMenuItem opengr;
	public static JMenuItem openmm;
	public static JMenuItem openzo;
	public static JMenuItem save;
	public static JMenuItem packworkspace;
	public static JMenuItem deploymod;
	public static JMenuItem tilesetWriter;
	public static JMenuItem objconvert;
	public static JMenuItem importMapModel;
	public static JMenuItem exportMapObj;
	public static JMenuItem importMapObj;
	public static JMenuItem forkGeometry;
	public static JMenuItem blankCanvas;
	public static JMenuItem tilePainter;
	public static JMenuItem areaLighting;
	public static JMenuItem resizeMap;
	public static JMenuItem wildEncounters;
	public static JMenuItem trainerEditor;
	public static JMenuItem maisonEditor;
	public static JMenuItem shopEditor;
	public static JMenuItem setupFacility;
	public static JMenuItem renameZone;
	public static JMenuItem emptyZone;
	public static JMenuItem removeAddedZones;
	public static JMenuItem findReusableZones;
	public static JMenuItem wssettings;
	public static JMenuItem setupWizard;
	public static JMenuItem wsclean;
	public static JMenuItem isstracker;
	public static JMenuItem about;
	public static JMenuItem checkUpdates;
	public static JToolBar toolbar;
	/** The always-visible 2D/3D view toggle (F2/F3 keep working and sync it). */
	public static javax.swing.JToggleButton btn3DView;
	public static ButtonGroup toolBtnGroup;
	public static JRadioButton btnEditTool;
	public static JRadioButton btnSetTool;
	public static JRadioButton btnPaintTool;
	public static ctrmap.humaninterface.PaintForm mPaintForm;
	public static javax.swing.JButton btnUndoTile;
	public static javax.swing.JButton btnRedoTile;
	public static JPanel zoneTabPnl;
	public static JPanel extrasTabPnl;
	public static JRadioButton btnFillTool;
	public static JRadioButton btnCamTool;
	public static JRadioButton btnPropTool;
	public static JRadioButton btnNPCTool;
	public static JRadioButton btnWarpTool;
	public static JRadioButton btnTriggerTool;
	public static JRadioButton btnGeoTool;
	public static JLabel currentTool;

	public static JScrollPane mTilemapScrollPane;
	public static TileMapPanel mTileMapPanel;
	public static TileEditForm mTileEditForm;
	public static JScrollPane mCamScrollPane;
	public static CameraEditForm mCamEditForm;
	public static PropEditForm mPropEditForm;
	public static NPCEditForm mNPCEditForm;
	public static WarpEditForm mWarpEditForm;
	public static TriggerEditForm mTriggerEditForm;
	public static GeoEditForm mGeoEditForm;

	public static GLPanel mGLPanel;
	public static H3DRenderingPanel m3DDebugPanel;
	public static CollEditPanel mCollEditPanel;

	public static JScrollPane mMtxScrollPane;
	public static MapMatrixPanel mMtxPanel;
	public static MatrixEditForm mMtxEditForm;

	public static ZoneLoadingPanel mZonePnl;
	public static ScriptEditor mScriptPnl;
	public static ExtrasPanel mExtrasPnl;
	public static TextEditor mTextEditor;
	public static Builder mBuilder;

	public static JPanel tileEditMasterPnl;
	public static JSplitPane jsp;
	public static JPanel collEditMasterPnl;
	public static JSplitPane jsp2;
	public static JPanel mtxEditMasterPnl;
	public static JSplitPane jsp3;

	public static TilemapPanelInputManager mTilemapInputManager;
	public static CollInputManager mCollInputManager;
	public static MatrixPanelInputManager mMtxPnlInputManager;
	public static CM3DInputManager mCM3DInputManager;

	public static AbstractTool tool;

	public static List<CM3DRenderable> CM3DComponents = new ArrayList<>();

	public static void main(String[] args) {
		//The Windows bundle installs an update by starting the DOWNLOADED copy
		//with this flag as the old one closes: it holds nothing in the install
		//folder, so it can replace all of it. Must be the first thing we do -
		//no window, no workspace, nothing.
		if (ctrmap.update.Updater.isApplyInvocation(args)) {
			ctrmap.update.Updater.runApply(args);
			return;
		}
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException
				| UnsupportedLookAndFeelException e) {
			e.printStackTrace();
		}
		createAndShowGUI();
	}

	private static void createAndShowGUI() {
		Workspace.loadWorkspace();
		frame = new JFrame("CTRMap Editor");
		tabs = new JTabbedPane();
		menubar = new JMenuBar();
		filemenu = new JMenu("File");
		toolsmenu = new JMenu("Tools");
		optionsmenu = new JMenu("Options");
		helpmenu = new JMenu("Help");
		opengr = new JMenuItem("Open GR Mapfile");
		openmm = new JMenuItem("Open MapMatrix");
		openzo = new JMenuItem("Open Zone");
		openzo.setToolTipText("Opens a single loose ZO file. To load a map from the game, use the zone dropdown in the \"Zone Loader\" tab instead.");
		save = new JMenuItem("Save");
		packworkspace = new JMenuItem("Pack Workspace");
		deploymod = new JMenuItem("Deploy to emulator (mod)...");
		tilesetWriter = new JMenuItem("Tileset Editor");
		objconvert = new JMenuItem("OBJ to collisions");
		importMapModel = new JMenuItem("Import map model (.bch)...");
		exportMapObj = new JMenuItem("Export map region to OBJ (Blender)...");
		importMapObj = new JMenuItem("Import OBJ into map region (Blender)...");
		forkGeometry = new JMenuItem("Fork map geometry (make zone independent)...");
		blankCanvas = new JMenuItem("Blank map canvas (this zone)...");
		tilePainter = new JMenuItem("Map Builder (this zone)");
		areaLighting = new JMenuItem("Edit area fog & lighting...");
		wildEncounters = new JMenuItem("Edit wild encounters (this zone)...");
		resizeMap = new JMenuItem("Resize map (this zone)...");
		trainerEditor = new JMenuItem("Edit trainer (party/battle)...");
		maisonEditor = new JMenuItem("Edit battle facility opponents...");
		shopEditor = new JMenuItem("Edit shop inventories (Marts)...");
		setupFacility = new JMenuItem("Custom battle facility here (clone a retail facility)");
		renameZone = new JMenuItem("Rename zone (in-game name)...");
		emptyZone = new JMenuItem("Empty zone (clear contents)...");
		removeAddedZones = new JMenuItem("Remove added zones (restore stock 536)...");
		findReusableZones = new JMenuItem("Find reusable base zones...");
		setupWizard = new JMenuItem("Setup wizard...");
		setupWizard.setToolTipText("Point CTRMap at your game, step by step.");
		wssettings = new JMenuItem("Workspace settings");
		wsclean = new JMenuItem("Clean workspace");
		isstracker = new JMenuItem("Support/Issue tracker");
		about = new JMenuItem("About");
		checkUpdates = new JMenuItem("Check for updates...");
		toolbar = new JToolBar();
		btnEditTool = Utils.createGraphicalButton("_tool_edit");
		btnSetTool = Utils.createGraphicalButton("_tool_set");
		btnFillTool = Utils.createGraphicalButton("_tool_fill");
		btnCamTool = Utils.createGraphicalButton("_tool_cam");
		btnPropTool = Utils.createGraphicalButton("_tool_prop");
		btnNPCTool = Utils.createGraphicalButton("_tool_npc");
		btnWarpTool = Utils.createGraphicalButton("_tool_warp");
		btnTriggerTool = Utils.createGraphicalButton("_tool_trigger");
		btnPaintTool = Utils.createGraphicalButton("_tool_paint");
		btnPaintTool.setToolTipText("Map Builder - edit this zone's map with terrain brushes, elevation, buildings and decor, directly on the map view. Only tiles you touch are rebuilt. (The brush-icon Set tool instead paints tile TYPES onto the existing map.)");
		btnPaintTool.getAccessibleContext().setAccessibleName("Map Builder");
		btnGeoTool = Utils.createGraphicalButton("_tool_geo");
		btnGeoTool.setToolTipText("Geometry tool - drag a box on the map to select its 3D geometry, then move/duplicate/delete it, or copy it as a prefab and stamp it elsewhere");
		btnGeoTool.getAccessibleContext().setAccessibleName("Geometry");
		btnEditTool.setToolTipText("Edit tool - click a tile to load its settings");
		btnSetTool.setToolTipText("Set tool - paint the panel's settings onto tiles");
		btnFillTool.setToolTipText("Fill tool - drag a box to fill tiles with settings");
		btnCamTool.setToolTipText("Camera tool - place and edit camera zones");
		btnPropTool.setToolTipText("Prop tool - place and edit map props (trees, signs)");
		btnNPCTool.setToolTipText("NPC tool - place and edit overworld NPCs");
		btnWarpTool.setToolTipText("Warp tool - place and edit warps (doors, stairs)");
		btnTriggerTool.setToolTipText("Trigger tool - place script triggers (step-on events)");
		toolBtnGroup = new ButtonGroup();
		btnEditTool.setSelected(true);
		currentTool = new JLabel("Current tool: Edit");

		toolBtnGroup.add(btnEditTool);
		toolBtnGroup.add(btnSetTool);
		toolBtnGroup.add(btnFillTool);
		toolBtnGroup.add(btnCamTool);
		toolBtnGroup.add(btnPropTool);
		toolBtnGroup.add(btnNPCTool);
		toolBtnGroup.add(btnWarpTool);
		toolBtnGroup.add(btnTriggerTool);
		toolBtnGroup.add(btnPaintTool);
		toolBtnGroup.add(btnGeoTool);
		toolbar.add(btnEditTool);
		toolbar.add(btnSetTool);
		toolbar.add(btnFillTool);
		toolbar.add(btnCamTool);
		toolbar.add(btnPropTool);
		toolbar.add(btnNPCTool);
		toolbar.add(btnWarpTool);
		toolbar.add(btnTriggerTool);
		toolbar.add(btnPaintTool);
		toolbar.add(btnGeoTool);
		toolbar.add(currentTool);

		//undo/redo for tile edits + the map actions, ON the World Editor where
		//they belong (the menus keep shortcuts, but this is the primary home)
		toolbar.addSeparator();
		btnUndoTile = new javax.swing.JButton("↶ Undo");
		btnRedoTile = new javax.swing.JButton("↷ Redo");
		btnUndoTile.setToolTipText("Undo the last tile edit (Ctrl+Z)");
		btnRedoTile.setToolTipText("Redo the undone tile edit (Ctrl+Y)");
		btnUndoTile.setEnabled(false);
		btnRedoTile.setEnabled(false);
		btnUndoTile.setFocusable(false);
		btnRedoTile.setFocusable(false);
		btnUndoTile.addActionListener(e -> ctrmap.humaninterface.TileUndo.undo());
		btnRedoTile.addActionListener(e -> ctrmap.humaninterface.TileUndo.redo());
		toolbar.add(btnUndoTile);
		toolbar.add(btnRedoTile);
		//the 2D/3D view switch, ALWAYS visible and valid for every tool - the
		//displayed component is the source of truth so F2/F3 stay in sync
		btn3DView = new javax.swing.JToggleButton("3D view");
		btn3DView.setToolTipText("Show the map in 3D (fly with WASD + drag; the Map Builder updates it live). F2 = 2D, F3 = 3D.");
		btn3DView.setFocusable(false);
		btn3DView.addActionListener(e -> {
			boolean to3d = jsp.getLeftComponent() != m3DDebugPanel;
			Utils.setGraphicUI(to3d ? m3DDebugPanel : mTilemapScrollPane);
			btn3DView.setSelected(to3d);
		});
		toolbar.addSeparator();
		toolbar.add(btn3DView);
		javax.swing.JButton tbBlank = new javax.swing.JButton("Blank canvas");
		tbBlank.setToolTipText("Replace this zone's map with a blank canvas cloned from a template route.");
		tbBlank.addActionListener(e -> blankCanvasAction());
		javax.swing.JButton tbResize = new javax.swing.JButton("Resize map");
		tbResize.setToolTipText("Resize this zone's map (grow/shrink its region grid).");
		tbResize.addActionListener(e -> resizeMapAction());
		javax.swing.JButton tbFog = new javax.swing.JButton("Fog & lighting");
		tbFog.setToolTipText("Pick a GameFreak atmosphere with live preview, or hand-tune fog and ambient light.");
		tbFog.addActionListener(e -> ctrmap.humaninterface.AreaLightingDialog.show(frame));
		javax.swing.JButton tbEnc = new javax.swing.JButton("Encounters");
		tbEnc.setToolTipText("Edit this zone's wild Pokemon encounter slots.");
		tbEnc.addActionListener(e -> ctrmap.humaninterface.EncounterEditDialog.show(frame));
		javax.swing.JButton tbFork = new javax.swing.JButton("Fork geometry");
		tbFork.setToolTipText("Give this zone its own private map so edits stop affecting the source town.");
		tbFork.addActionListener(e -> forkGeometryAction());
		//second toolbar row: the map-level actions (the toolbar's own row keeps
		//the per-tile tools + undo; the Paint TOOL toggle is the painter's home)
		JToolBar mapActionsBar = new JToolBar();
		mapActionsBar.setFloatable(false);
		mapActionsBar.add(new JLabel(" Map:  "));
		for (javax.swing.JButton b : new javax.swing.JButton[]{tbBlank, tbResize, tbFog, tbEnc, tbFork}) {
			b.setFocusable(false);
			mapActionsBar.add(b);
		}

		tileEditMasterPnl = new JPanel(new BorderLayout());
		collEditMasterPnl = new JPanel(new BorderLayout());
		mtxEditMasterPnl = new JPanel(new BorderLayout());
		mZonePnl = new ZoneLoadingPanel();
		mScriptPnl = new ScriptEditor();
		mExtrasPnl = new ExtrasPanel(mZonePnl);
		mTextEditor = new TextEditor();
		mBuilder = new Builder();

		frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		frame.setLocationByPlatform(true);
		mTileMapPanel = new TileMapPanel();
		mTilemapScrollPane = new JScrollPane();
		mMtxPanel = new MapMatrixPanel();
		mMtxEditForm = new MatrixEditForm();
		mMtxScrollPane = new JScrollPane();
		mCamScrollPane = new JScrollPane();
		mTileEditForm = new TileEditForm();
		mPaintForm = new ctrmap.humaninterface.PaintForm();
		mCamEditForm = new CameraEditForm();
		mPropEditForm = new PropEditForm();
		mNPCEditForm = new NPCEditForm();
		mWarpEditForm = new WarpEditForm();
		mTriggerEditForm = new TriggerEditForm();
		mGeoEditForm = new GeoEditForm();
		mCollEditPanel = new CollEditPanel();
		mGLPanel = new GLPanel(mCollEditPanel);
		m3DDebugPanel = new H3DRenderingPanel(CM3DComponents);
		jsp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
		mTilemapScrollPane.setViewportView(mTileMapPanel);
		mMtxScrollPane.setViewportView(mMtxPanel);
		mCamScrollPane.setViewportView(mCamEditForm);
		mCamScrollPane.setMinimumSize(mCamEditForm.getPreferredSize());
		mCamScrollPane.setPreferredSize(mCamEditForm.getPreferredSize());
		mCamScrollPane.getVerticalScrollBar().setUnitIncrement(16);
		jsp.setLeftComponent(mTilemapScrollPane);
		jsp.setRightComponent(mTileEditForm);

		mTilemapInputManager = new TilemapPanelInputManager(mTileMapPanel);
		mCM3DInputManager = new CM3DInputManager(m3DDebugPanel);
		mCollInputManager = new CollInputManager(mGLPanel);
		mMtxPnlInputManager = new MatrixPanelInputManager(mMtxPanel);

		jsp2 = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
		jsp2.setLeftComponent(mGLPanel);
		jsp2.setRightComponent(mCollEditPanel);

		jsp3 = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
		jsp3.setLeftComponent(mMtxScrollPane);
		jsp3.setRightComponent(mMtxEditForm);

		JPanel toolbarRows = new JPanel(new java.awt.GridLayout(2, 1));
		toolbarRows.add(toolbar);
		toolbarRows.add(mapActionsBar);
		tileEditMasterPnl.add(toolbarRows, BorderLayout.NORTH);
		tileEditMasterPnl.add(jsp, BorderLayout.CENTER);

		collEditMasterPnl.add(jsp2);

		mtxEditMasterPnl.add(jsp3);

		//Zone Loader tab = the zone panel + its lifecycle actions ON the tab
		zoneTabPnl = new JPanel(new BorderLayout());
		JToolBar zoneOps = new JToolBar();
		zoneOps.setFloatable(false);
		zoneOps.add(new JLabel(" Zone actions:  "));
		javax.swing.JButton zbRename = new javax.swing.JButton("Rename");
		zbRename.setToolTipText("Rename the loaded zone's in-game location banner.");
		zbRename.addActionListener(e -> renameZoneAction());
		javax.swing.JButton zbEmpty = new javax.swing.JButton("Empty");
		zbEmpty.setToolTipText("Clear the loaded zone's NPCs, warps, triggers and furniture (keeps map + script).");
		zbEmpty.addActionListener(e -> emptyZoneAction());
		javax.swing.JButton zbFind = new javax.swing.JButton("Find reusable zones");
		zbFind.setToolTipText("Scan for unused base zones you can safely repurpose for new areas.");
		zbFind.addActionListener(e -> findReusableZonesAction());
		javax.swing.JButton zbRemove = new javax.swing.JButton("Remove added zones");
		zbRemove.setToolTipText("Delete all added zones (index 536+) and restore the stock ZoneData layout.");
		zbRemove.addActionListener(e -> removeAddedZonesAction());
		javax.swing.JButton zbFacility = new javax.swing.JButton("Custom battle facility");
		zbFacility.setToolTipText("Build a custom battle facility here: INDEPENDENT script-driven battles with your own trainers (vanilla untouched), or clone a retail facility's full engine (shared opponent pools). Tower, dojo, gauntlet - whatever the zone should host.");
		zbFacility.addActionListener(e -> setupFacilityAction());
		for (javax.swing.JButton b : new javax.swing.JButton[]{zbRename, zbEmpty, zbFind, zbRemove, zbFacility}) {
			b.setFocusable(false);
			zoneOps.add(b);
		}
		zoneTabPnl.add(zoneOps, BorderLayout.NORTH);
		zoneTabPnl.add(mZonePnl, BorderLayout.CENTER);

		//Extras tab hosts the seldom-used Builder (raw archive browser)
		extrasTabPnl = new JPanel(new BorderLayout());
		JToolBar extrasOps = new JToolBar();
		extrasOps.setFloatable(false);
		javax.swing.JButton exBuilder = new javax.swing.JButton("Raw archive browser (Builder)");
		exBuilder.setToolTipText("Browse raw GARC entries and their subfiles - advanced, rarely needed.");
		exBuilder.setFocusable(false);
		exBuilder.addActionListener(e -> {
			javax.swing.JDialog bd = new javax.swing.JDialog(frame, "Builder - raw archive browser", false);
			bd.add(mBuilder);
			bd.setSize(900, 600);
			bd.setLocationRelativeTo(frame);
			bd.setVisible(true);
		});
		extrasOps.add(exBuilder);
		extrasTabPnl.add(extrasOps, BorderLayout.NORTH);
		extrasTabPnl.add(mExtrasPnl, BorderLayout.CENTER);

		tabs.add("World Editor", tileEditMasterPnl);
		tabs.add("Collision Editor", collEditMasterPnl);
		tabs.add("Matrix Editor", mtxEditMasterPnl);
		tabs.add("Zone Loader", zoneTabPnl);
		tabs.add("Game Data", buildGameDataPanel());
		tabs.add("Script Editor (experimental)", mScriptPnl);
		tabs.add("Extras", extrasTabPnl);
		tabs.add("Text Editor", mTextEditor);
		tabs.setToolTipTextAt(tabs.indexOfComponent(tileEditMasterPnl),
				"The map itself: tile BEHAVIOR (walkable/encounters/water/warps - the colored grid) plus props, NPCs, warps, triggers and cameras. Edit tool inspects a tile; Set/Fill paint tile types.");
		tabs.setToolTipTextAt(tabs.indexOfComponent(collEditMasterPnl),
				"The 3D collision SURFACE the player stands on (heights, slopes, stairs). Behavior bytes live in the World Editor; this shapes the physical ground. The Tile Painter edits both at once.");
		tabs.setToolTipTextAt(tabs.indexOfComponent(mtxEditMasterPnl),
				"How map regions tile together into the zone's world grid.");
		tabs.setToolTipTextAt(tabs.indexOfComponent(zoneTabPnl), "Pick a map from the zone dropdown here - this is how zones are opened.");

		//Ctrl+Z / Ctrl+Y while the World Editor tab has focus
		javax.swing.InputMap tim = tileEditMasterPnl.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
		tim.put(javax.swing.KeyStroke.getKeyStroke("control Z"), "tileUndo");
		tim.put(javax.swing.KeyStroke.getKeyStroke("control Y"), "tileRedo");
		tileEditMasterPnl.getActionMap().put("tileUndo", new javax.swing.AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				ctrmap.humaninterface.TileUndo.undo();
			}
		});
		tileEditMasterPnl.getActionMap().put("tileRedo", new javax.swing.AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				ctrmap.humaninterface.TileUndo.redo();
			}
		});

		btnEditTool.setActionCommand("edit");
		btnEditTool.addActionListener(mTilemapInputManager);
		btnSetTool.setActionCommand("set");
		btnSetTool.addActionListener(mTilemapInputManager);
		btnFillTool.setActionCommand("fill");
		btnFillTool.addActionListener(mTilemapInputManager);
		btnCamTool.setActionCommand("cam");
		btnCamTool.addActionListener(mTilemapInputManager);
		btnPropTool.setActionCommand("prop");
		btnPropTool.addActionListener(mTilemapInputManager);
		btnNPCTool.setActionCommand("npc");
		btnNPCTool.addActionListener(mTilemapInputManager);
		btnWarpTool.setActionCommand("warp");
		btnWarpTool.addActionListener(mTilemapInputManager);
		btnTriggerTool.setActionCommand("trigger");
		btnTriggerTool.addActionListener(mTilemapInputManager);
		btnPaintTool.setActionCommand("paint");
		btnPaintTool.addActionListener(mTilemapInputManager);
		btnGeoTool.setActionCommand("geo");
		btnGeoTool.addActionListener(mTilemapInputManager);

		frame.getContentPane().add(tabs);

		frame.setJMenuBar(menubar);
		opengr.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent arg0) {
				if (!Workspace.valid && !Utils.confirmOpenWithoutWorkspace("Open GR Mapfile")) {
					return;
				}
				Preferences prefs = Preferences.userRoot().node(getClass().getName());
				JFileChooser jfc = new JFileChooser(prefs.get("LAST_DIR",
						new File(".").getAbsolutePath()));
				jfc.setDialogTitle("Open GR/153/bin mapfile");
				jfc.setFileSelectionMode(JFileChooser.FILES_ONLY);
				jfc.setMultiSelectionEnabled(false);
				jfc.showOpenDialog(frame);
				if (jfc.getSelectedFile() != null) {
					prefs.put("LAST_DIR", jfc.getSelectedFile().getParent());
					GR mainGR = new GR(jfc.getSelectedFile());
					CtrmapMainframe.frame.setTitle("GfMap Editor - " + mainGR.getOriginFile().getName());
					mTileMapPanel.loadTileMap(mainGR);
					mCollEditPanel.unload();
					mCollEditPanel.loadCollision(mainGR);
					mTileMapPanel.scaleImage(1);
				}
			}
		});
		save.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				mTileMapPanel.saveTileMap(false);
				mMtxEditForm.store(false);
				mCollEditPanel.store();
				mCamEditForm.store(false);
				mPropEditForm.store(false);
				mNPCEditForm.saveRegistry(false);
				mZonePnl.store(false);
				mTextEditor.store(false);
			}
		});
		tilesetWriter.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				JFrame tilesetEditor = new TileDBWriter();
				tilesetEditor.setVisible(true);
			}
		});
		objconvert.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				Preferences prefs = Preferences.userRoot().node(getClass().getName());
				JFileChooser jfc = new JFileChooser(prefs.get("LAST_DIR",
						new File(".").getAbsolutePath()));
				jfc.setDialogTitle("Open OBJ file");
				jfc.setFileSelectionMode(JFileChooser.FILES_ONLY);
				jfc.setMultiSelectionEnabled(false);
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
				jfc.showOpenDialog(frame);
				if (jfc.getSelectedFile() != null) {
					prefs.put("LAST_DIR", jfc.getSelectedFile().getParent());
					WavefrontOBJ obj = new WavefrontOBJ(jfc.getSelectedFile());
					if (mCollEditPanel.coll != null) {
						mCollEditPanel.coll.meshes = obj.getGfCollision();
						mCollEditPanel.coll.modified = true;
						mCollEditPanel.buildTree();
					}
				}
			}
		});
		importMapModel.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				importMapModelAction();
			}
		});
		forkGeometry.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				forkGeometryAction();
			}
		});
		blankCanvas.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				blankCanvasAction();
			}
		});
		tilePainter.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				tabs.setSelectedComponent(tileEditMasterPnl);
				btnPaintTool.doClick(); //the painter is a World Editor tool now
			}
		});
		areaLighting.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				ctrmap.humaninterface.AreaLightingDialog.show(frame);
			}
		});
		wildEncounters.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				ctrmap.humaninterface.EncounterEditDialog.show(frame);
			}
		});
		resizeMap.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				resizeMapAction();
			}
		});
		trainerEditor.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				ctrmap.humaninterface.TrainerEditDialog.showForSelection(frame);
			}
		});
		maisonEditor.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				ctrmap.humaninterface.MaisonEditDialog.show(frame);
			}
		});
		shopEditor.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				ctrmap.humaninterface.ShopEditDialog.show(frame);
			}
		});
		setupFacility.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				setupFacilityAction();
			}
		});
		exportMapObj.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				exportMapObjAction();
			}
		});
		importMapObj.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				importMapObjAction();
			}
		});
		renameZone.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				renameZoneAction();
			}
		});
		emptyZone.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				emptyZoneAction();
			}
		});
		removeAddedZones.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				removeAddedZonesAction();
			}
		});
		findReusableZones.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				findReusableZonesAction();
			}
		});
		deploymod.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				deployModAction();
			}
		});
		openmm.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (!Workspace.valid && !Utils.confirmOpenWithoutWorkspace("Open MapMatrix")) {
					return;
				}
				Preferences prefs = Preferences.userRoot().node(getClass().getName());
				JFileChooser jfc = new JFileChooser(prefs.get("LAST_DIR",
						new File(".").getAbsolutePath()));
				jfc.setDialogTitle("Open MM file");
				jfc.setFileSelectionMode(JFileChooser.FILES_ONLY);
				jfc.setMultiSelectionEnabled(false);
				jfc.showOpenDialog(frame);
				if (jfc.getSelectedFile() != null) {
					prefs.put("LAST_DIR", jfc.getSelectedFile().getParent());
					try {
						mTileMapPanel.loadMatrix(new MapMatrix(new MM(jfc.getSelectedFile())), null, null, null);
					} catch (RuntimeException ex) {
						JOptionPane.showMessageDialog(frame, ex.getMessage(), "Open MapMatrix", JOptionPane.ERROR_MESSAGE);
						return;
					}
					mTileMapPanel.scaleImage(1);
				}
			}
		});
		wssettings.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				WorkspaceSettings form = new WorkspaceSettings();
				form.setLocationByPlatform(true);
				form.setVisible(true);
			}
		});
		packworkspace.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				Workspace.packWorkspace();
			}
		});
		openzo.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (!Workspace.valid && !Utils.confirmOpenWithoutWorkspace("Open Zone")) {
					return;
				}
				Preferences prefs = Preferences.userRoot().node(getClass().getName());
				//the loose ZO files live in the workspace, never in the RomFS - start there when we can
				File zoneDir = Workspace.valid ? Workspace.getExtractionDirectory(Workspace.ArchiveType.ZONE_DATA) : null;
				JFileChooser jfc = (zoneDir != null && zoneDir.exists())
						? new JFileChooser(zoneDir)
						: new JFileChooser(prefs.get("LAST_DIR", new File(".").getAbsolutePath()));
				jfc.setDialogTitle("Open ZO file");
				jfc.setFileSelectionMode(JFileChooser.FILES_ONLY);
				jfc.setMultiSelectionEnabled(false);
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
				jfc.showOpenDialog(frame);
				if (jfc.getSelectedFile() != null) {
					prefs.put("LAST_DIR", jfc.getSelectedFile().getParent());
					if (!Utils.checkMagicLE16(jfc.getSelectedFile(), 0x5a4f)) {
						Utils.showErrorMessage("Not a ZO file", jfc.getSelectedFile().getName()
								+ " is not a zone mini-pack.\n\n"
								+ "The RomFS only holds packed GARC archives, not single zone files, so pointing\n"
								+ "this dialog at the game directory can never work.\n"
								+ (zoneDir != null ? ("Extracted zone files live in " + zoneDir.getAbsolutePath() + "\n") : "")
								+ "\nThe normal way to open a map is the zone dropdown in the \"Zone Loader\" tab.");
						return;
					}
					mZonePnl.loadZone(new Zone(new ZO(jfc.getSelectedFile()), (Workspace.valid) ? Workspace.game : Workspace.GameType.ORAS));
				}
			}
		});
		isstracker.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
					try {
						Desktop.getDesktop().browse(new URI("https://github.com/HelloOO7/CTRMap/issues"));
					} catch (URISyntaxException | IOException ex) {
						Logger.getLogger(CtrmapMainframe.class.getName()).log(Level.SEVERE, null, ex);
					}
				} else {
					Utils.showErrorMessage("Browser open error", "Your system either does not support the Java Desktop API or you do not have a suitable browser installed.");
				}
			}
		});
		setupWizard.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				ctrmap.setup.SetupWizard.show(frame);
			}
		});
		checkUpdates.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				ctrmap.update.UpdateUI.checkNow(frame);
			}
		});
		about.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				AboutDialog dlg = new AboutDialog();
				dlg.setLocationRelativeTo(frame);
				dlg.setVisible(true);
			}
		});
		wsclean.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				Workspace.cleanAndReload();
				mZonePnl.loadEverything();
			}
		});
		filemenu.add(opengr);
		filemenu.add(openmm);
		filemenu.add(openzo);
		filemenu.add(save);
		filemenu.add(packworkspace);
		filemenu.add(deploymod);
		//menus are SECONDARY access grouped by subject - the primary homes are
		//the World Editor toolbar, the Zone Loader bar and the Game Data tab
		JMenu mapMenu = new JMenu("Map");
		mapMenu.add(tilePainter);
		mapMenu.add(blankCanvas);
		mapMenu.add(resizeMap);
		mapMenu.add(areaLighting);
		mapMenu.add(forkGeometry);
		mapMenu.addSeparator();
		mapMenu.add(importMapModel);
		mapMenu.add(exportMapObj);
		mapMenu.add(importMapObj);
		mapMenu.add(objconvert);
		mapMenu.add(tilesetWriter);
		JMenu zoneMenu = new JMenu("Zone");
		zoneMenu.add(renameZone);
		zoneMenu.add(emptyZone);
		zoneMenu.add(findReusableZones);
		zoneMenu.add(removeAddedZones);
		zoneMenu.add(setupFacility);
		JMenu dataMenu = new JMenu("Game Data");
		dataMenu.add(trainerEditor);
		dataMenu.add(maisonEditor);
		dataMenu.add(shopEditor);
		dataMenu.add(wildEncounters);
		//the wizard sits above the raw path dialog it replaces for beginners:
		//same job, but it explains itself and checks what you picked
		optionsmenu.add(setupWizard);
		optionsmenu.addSeparator();
		optionsmenu.add(wssettings);
		optionsmenu.add(wsclean);
		helpmenu.add(checkUpdates);
		helpmenu.addSeparator();
		helpmenu.add(isstracker);
		helpmenu.add(about);
		menubar.add(filemenu);
		menubar.add(mapMenu);
		menubar.add(zoneMenu);
		menubar.add(dataMenu);
		menubar.add(optionsmenu);
		menubar.add(helpmenu);

		CM3DComponents.add(mTileMapPanel);
		CM3DComponents.add(mPropEditForm);
		CM3DComponents.add(mNPCEditForm);

		frame.setSize(1280, 720 + menubar.getHeight());
		frame.setMinimumSize(frame.getSize());
		frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
		frame.setVisible(true);

		//an update applied by the launcher leaves its unpacked copy behind,
		//because the JVM that applied it was running out of that folder
		File installDir = ctrmap.update.Updater.installDir();
		ctrmap.update.Updater.holdRunLock(installDir); //so an update knows when we are gone
		ctrmap.update.Updater.sweep(installDir);

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
				if (mCamEditForm.store(true) && mTileMapPanel.saveTileMap(true) && mMtxEditForm.store(true) && mPropEditForm.store(true) && mNPCEditForm.saveRegistry(true) && mZonePnl.store(true) && mTextEditor.store(true)) {
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
				Utils.setGraphicUI(mTilemapScrollPane);
				if (btn3DView != null) {
					btn3DView.setSelected(false);
				}
			}
		});
		frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("F3"), "switch3D");
		frame.getRootPane().getActionMap().put("switch3D", new AbstractAction("switch3D") {
			@Override
			public void actionPerformed(ActionEvent e) {
				Utils.setGraphicUI(m3DDebugPanel);
				if (btn3DView != null) {
					btn3DView.setSelected(true);
				}
			}
		});
		tool = new SetTool();
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
			ctrmap.setup.SetupWizard.show(frame);
		} else {
			Workspace.validate(frame);
		}
		ctrmap.update.UpdateUI.checkOnStartup(frame);
	}

	/**
	 * Lands the user on the tab that actually opens zones and explains how to use
	 * it. Called from Workspace.validate() so that it also fires for the first
	 * validation that succeeds after the paths are set in Workspace settings.
	 */
	/**
	 * Pushes the loaded zone's atmosphere (AreaData subfile 4) into the 3D
	 * scene, so fog & lighting edits are visible in the editor itself and not
	 * only in the atmosphere picker's preview. Silent when nothing is loaded.
	 */
	public static void refreshSceneFog() {
		if (m3DDebugPanel == null) {
			return;
		}
		try {
			if (!Workspace.valid || mZonePnl == null || mZonePnl.zone == null || mZonePnl.zone.header == null) {
				m3DDebugPanel.clearFog();
				return;
			}
			ctrmap.formats.containers.AD ad = mZonePnl.zone.header.areadata != null
					? mZonePnl.zone.header.areadata
					: new ctrmap.formats.containers.AD(Workspace.getWorkspaceFile(
							Workspace.ArchiveType.AREA_DATA, mZonePnl.zone.header.areadataID));
			ctrmap.formats.area.AreaEnv env = ctrmap.formats.area.AreaEnv.read(ad.getFile(4));
			m3DDebugPanel.setFog(env.fogColor[0], env.fogColor[1], env.fogColor[2], env.fogNear, env.fogFar);
		} catch (Exception ex) {
			m3DDebugPanel.clearFog();
		}
	}

	/**
	 * The Game Data tab: the game-wide data editors (trainers, battle
	 * facilities, shops, wild Pokemon), as big labeled entry points instead of
	 * menu items.
	 */
	private static JPanel buildGameDataPanel() {
		JPanel p = new JPanel();
		p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.Y_AXIS));
		p.setBorder(javax.swing.BorderFactory.createEmptyBorder(24, 32, 24, 32));
		addGameDataEntry(p, "Trainers", "Edit any trainer's party, moves, items and battle type.",
				"Trainer editor", e -> ctrmap.humaninterface.TrainerEditDialog.showForSelection(frame));
		addGameDataEntry(p, "Battle facilities", "<html>Edit the opponent pools and trainer-class assignments of the battle facility engine.<br>This data is ENGINE-WIDE: the retail facility and every custom facility cloned from it draw<br>from the same pools - author your teams in FREE slots (retail rows are marked and guarded).</html>",
				"Facility opponents", e -> ctrmap.humaninterface.MaisonEditDialog.show(frame));
		addGameDataEntry(p, "Shops", "Change what the Poke Marts and specialty shops sell (ships as a code.ips patch).",
				"Shop inventories", e -> ctrmap.humaninterface.ShopEditDialog.show(frame));
		addGameDataEntry(p, "Wild Pokemon", "Edit the loaded zone's wild encounter slots (grass, surf, fishing...).",
				"Wild encounters (this zone)", e -> ctrmap.humaninterface.EncounterEditDialog.show(frame));
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

	public static void showZoneLoadingHint() {
		tabs.setSelectedComponent(zoneTabPnl);
		Preferences hintPrefs = Preferences.userRoot().node(CtrmapMainframe.class.getName());
		if (!hintPrefs.getBoolean("ZONE_HINT_SHOWN", false)) {
			hintPrefs.putBoolean("ZONE_HINT_SHOWN", true);
			Utils.showInfoMessage("Opening a zone", "Workspace loaded.\n\n"
					+ "Pick a map from the zone dropdown at the top of the \"Zone Loader\" tab.\n"
					+ "That loads the world, matrix, collisions and entities for editing.\n\n"
					+ "(File > Open Zone is only for single loose ZO files.)");
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
	private static void deployModAction() {
		if (!Workspace.valid) {
			JOptionPane.showMessageDialog(frame, "Load a workspace first.", "Deploy mod", JOptionPane.ERROR_MESSAGE);
			return;
		}
		String titleId = ModDeployer.guessTitleId();
		File azahar = ModDeployer.azaharModRoot(titleId);

		final javax.swing.JTextField titleField = new javax.swing.JTextField(titleId);
		final javax.swing.JTextField folderField = new javax.swing.JTextField(azahar != null ? azahar.getAbsolutePath() : "");
		javax.swing.JButton browse = new javax.swing.JButton("Browse...");
		browse.addActionListener((java.awt.event.ActionEvent ev) -> {
			JFileChooser fc = new JFileChooser(folderField.getText());
			fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			fc.setDialogTitle("Mod folder (Azahar mods\\<title>, or your SD luma\\titles\\<title>)");
			if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
				folderField.setText(fc.getSelectedFile().getAbsolutePath());
			}
		});
		final javax.swing.JTextField ipsField = new javax.swing.JTextField("");
		javax.swing.JButton ipsBrowse = new javax.swing.JButton("Browse...");
		ipsBrowse.addActionListener((java.awt.event.ActionEvent ev) -> {
			JFileChooser fc = new JFileChooser();
			fc.setDialogTitle("Optional: pick a code.ips (from Add zones) to install");
			if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
				ipsField.setText(fc.getSelectedFile().getAbsolutePath());
			}
		});
		javax.swing.JPanel folderRow = new javax.swing.JPanel(new java.awt.BorderLayout());
		folderRow.add(folderField, java.awt.BorderLayout.CENTER);
		folderRow.add(browse, java.awt.BorderLayout.EAST);
		javax.swing.JPanel ipsRow = new javax.swing.JPanel(new java.awt.BorderLayout());
		ipsRow.add(ipsField, java.awt.BorderLayout.CENTER);
		ipsRow.add(ipsBrowse, java.awt.BorderLayout.EAST);
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
			"Code patch to install (optional - the code.ips from 'Add zones'):", ipsRow,
			" ",
			parked
			? "The mod is currently OFF - the game boots completely stock."
			: "\"" + offOn + "\" moves the mod out of the emulator's load path so the game boots"
			+ " stock. Nothing is deleted and your save is untouched; switch it back any time."
		};
		String[] choices = {"Deploy", offOn, "Cancel"};
		int pick = JOptionPane.showOptionDialog(frame, form, "Deploy to emulator (LayeredFS mod)",
				JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, choices, choices[0]);
		if (pick != 0 && pick != 1) {
			return;
		}
		String folder = folderField.getText().trim();
		if (folder.isEmpty()) {
			JOptionPane.showMessageDialog(frame, "Pick a mod folder first.", "Deploy mod", JOptionPane.ERROR_MESSAGE);
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
				ModDeployer.Result res = ModDeployer.deploy(modRoot, ips);
				StringBuilder sb = new StringBuilder();
				if (res.deployed.isEmpty() && !res.codeIpsDeployed) {
					sb.append("Nothing changed to deploy (no edits since the last deploy).\n");
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
				JOptionPane.showMessageDialog(frame, sb.toString(), "Deploy to emulator", JOptionPane.INFORMATION_MESSAGE);
			}
		});
	}

	/**
	 * Switches the deployed mod off (or back on) by moving it out of / into the
	 * emulator's load path. This is what you want when you need the untouched
	 * retail game for a while - to play through a story section your edits are
	 * blocking, or to tell whether a bug is the game's or ours - without losing
	 * the mod. Nothing is deleted and save data is never touched.
	 */
	private static void toggleModAction(File modRoot) {
		boolean parked = ModDeployer.isParked(modRoot);
		try {
			if (parked) {
				File back = ModDeployer.enable(modRoot);
				JOptionPane.showMessageDialog(frame,
						"Mod is back ON:\n  " + back.getAbsolutePath()
						+ "\n\nFully close and reopen the emulator before playing - it caches game files.",
						"Mod switched on", JOptionPane.INFORMATION_MESSAGE);
			} else if (ModDeployer.isDeployed(modRoot)) {
				File parkedAt = ModDeployer.disable(modRoot);
				JOptionPane.showMessageDialog(frame,
						"Mod is OFF - the game now boots completely stock.\n\n"
						+ "Your edits are safe here:\n  " + parkedAt.getAbsolutePath()
						+ "\n\nSave data was not touched. Come back here and pick \"Turn mod back ON\"\n"
						+ "when you want your world again.\n\n"
						+ "Fully close and reopen the emulator before playing - it caches game files.",
						"Playing vanilla", JOptionPane.INFORMATION_MESSAGE);
			} else {
				JOptionPane.showMessageDialog(frame,
						"There is no deployed mod at:\n  " + modRoot.getAbsolutePath()
						+ "\n\nNothing to switch off - the game already boots stock.",
						"Nothing deployed", JOptionPane.INFORMATION_MESSAGE);
			}
		} catch (java.io.IOException ex) {
			JOptionPane.showMessageDialog(frame,
					"Could not move the mod folder:\n  " + ex.getMessage()
					+ "\n\nClose the emulator (it may be holding the files open) and try again.",
					"Mod switch failed", JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Gives a zone its own private map geometry (see {@link GeometryForker}) so a
	 * cloned zone can be edited without changing the town it was cloned from.
	 * Defaults the zone picker to the currently loaded zone.
	 */
	private static void forkGeometryAction() {
		if (!Workspace.valid) {
			javax.swing.JOptionPane.showMessageDialog(frame, "Load a workspace first (Options > Workspace settings).", "Fork map geometry", javax.swing.JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (!Workspace.isOA()) {
			javax.swing.JOptionPane.showMessageDialog(frame, "Forking map geometry is ORAS-only in v1.", "Fork map geometry", javax.swing.JOptionPane.ERROR_MESSAGE);
			return;
		}
		ctrmap.formats.garc.GARC zoGarc = Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA);
		if (zoGarc == null) {
			javax.swing.JOptionPane.showMessageDialog(frame, "ZoneData archive unavailable.", "Fork map geometry", javax.swing.JOptionPane.ERROR_MESSAGE);
			return;
		}
		int zoneCount = zoGarc.length - 2; //master table + EN pack occupy the last two entries
		int def = (mZonePnl != null && mZonePnl.zoneIndex >= 0 && mZonePnl.zoneIndex < zoneCount) ? mZonePnl.zoneIndex : 0;
		javax.swing.JSpinner idSpinner = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(def, 0, zoneCount - 1, 1));
		Object[] form = {
			"Give this zone its OWN private map geometry.",
			"After forking, editing this zone's map no longer changes any other",
			"zone that currently shares it (e.g. a cloned zone and the town it was",
			"cloned from share the same 3D model until you fork).",
			" ",
			"Zone (GARC index - the number shown in the zone dropdown):",
			idSpinner,
			" ",
			"This copies the zone's FieldData region(s) and its map matrix to new",
			"private entries and repoints the zone at them. Pure data, no code patch."
		};
		if (javax.swing.JOptionPane.showConfirmDialog(frame, form, "Fork map geometry", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE) != javax.swing.JOptionPane.OK_OPTION) {
			return;
		}
		int zoneIndex = (Integer) idSpinner.getValue();
		try {
			GeometryForker.ForkResult r = GeometryForker.ensurePrivate(zoneIndex);
			if (!r.forked) {
				//forking again would append another copy of every region and
				//orphan the ones the zone is using
				javax.swing.JOptionPane.showMessageDialog(frame, "Zone " + zoneIndex
						+ " already has its own map geometry (map matrix " + r.oldMatrix
						+ ", region(s) " + java.util.Arrays.toString(r.srcRegions) + ").\n\n"
						+ "Nothing was changed - editing its map already affects no other zone.",
						"Fork map geometry", javax.swing.JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			StringBuilder sb = new StringBuilder();
			sb.append("Zone ").append(zoneIndex).append(" now has private map geometry.\n\n");
			sb.append("Map matrix ").append(r.oldMatrix).append(" -> ").append(r.newMatrix).append(" (private copy)\n");
			for (int i = 0; i < r.srcRegions.length; i++) {
				sb.append("FieldData region ").append(r.srcRegions[i]).append(" -> ").append(r.newRegions[i]).append(" (private copy)\n");
			}
			sb.append("\nTo change ONLY this zone's map, edit region ").append(r.newRegions[0]);
			if (r.newRegions.length > 1) {
				sb.append(" (and the other new regions above)");
			}
			sb.append(".\nThe original region(s) still belong to the source zone(s).\n\n");
			sb.append("Now run File > Pack Workspace, then File > Deploy to emulator.\n");
			sb.append("No new code.ips is needed - the fork is pure data.");
			javax.swing.JOptionPane.showMessageDialog(frame, sb.toString(), "Fork map geometry", javax.swing.JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception ex) {
			javax.swing.JOptionPane.showMessageDialog(frame, "Fork failed:\n" + ex.getMessage(), "Fork map geometry", javax.swing.JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Renames a zone's in-game location banner (see {@link ZoneManager#renameZone}).
	 * Shared names are moved to a private free slot so other zones are unaffected.
	 */
	private static void renameZoneAction() {
		if (!Workspace.valid) {
			javax.swing.JOptionPane.showMessageDialog(frame, "Load a workspace first (Options > Workspace settings).", "Rename zone", javax.swing.JOptionPane.ERROR_MESSAGE);
			return;
		}
		ctrmap.formats.garc.GARC zo = Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA);
		if (zo == null) {
			javax.swing.JOptionPane.showMessageDialog(frame, "ZoneData archive unavailable.", "Rename zone", javax.swing.JOptionPane.ERROR_MESSAGE);
			return;
		}
		int zoneCount = zo.length - (Workspace.isXY() ? 1 : 2);
		int def = (mZonePnl != null && mZonePnl.zoneIndex >= 0 && mZonePnl.zoneIndex < zoneCount) ? mZonePnl.zoneIndex : 0;
		javax.swing.JSpinner idSpinner = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(def, 0, zoneCount - 1, 1));
		javax.swing.JTextField nameField = new javax.swing.JTextField(24);
		Object[] form = {
			"Rename a zone's in-game location banner (the name shown on entry).",
			"If the name is shared (a cloned zone shares the town it came from), this",
			"zone gets its OWN name and the others are left unchanged.",
			" ",
			"Zone (the number shown in the zone dropdown):",
			idSpinner,
			"New name:",
			nameField
		};
		if (javax.swing.JOptionPane.showConfirmDialog(frame, form, "Rename zone", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE) != javax.swing.JOptionPane.OK_OPTION) {
			return;
		}
		int idx = (Integer) idSpinner.getValue();
		String name = nameField.getText().trim();
		if (name.isEmpty()) {
			javax.swing.JOptionPane.showMessageDialog(frame, "Enter a name.", "Rename zone", javax.swing.JOptionPane.ERROR_MESSAGE);
			return;
		}
		try {
			ZoneManager.RenameResult r = ZoneManager.renameZone(idx, name);
			ctrmap.formats.text.LocationNames.loadFromGarc(); // refresh the dropdown name cache
			StringBuilder sb = new StringBuilder();
			sb.append("Zone ").append(idx).append(" renamed to \"").append(name).append("\".\n\n");
			if (r.gaveOwnName) {
				sb.append("It was sharing the name \"").append(r.oldName).append("\" with ").append(r.sharers)
				  .append(" zones; it now has its own name and the others are unchanged.\n\n");
			} else if (r.renamedSharers) {
				sb.append("NOTE: \"").append(r.oldName).append("\" was shared by ").append(r.sharers)
				  .append(" zones and no free name slot was available, so ALL of them were renamed.\n\n");
			} else {
				sb.append("The name belonged to this zone alone.\n\n");
			}
			sb.append("Run File > Deploy to emulator (it packs first), then fully restart the emulator.\n");
			sb.append("Reselect the zone in the dropdown to see the new name in the editor.");
			javax.swing.JOptionPane.showMessageDialog(frame, sb.toString(), "Rename zone", javax.swing.JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception ex) {
			javax.swing.JOptionPane.showMessageDialog(frame, "Rename failed:\n" + ex.getMessage(), "Rename zone", javax.swing.JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Empties a zone's placed content - NPCs, warps, triggers, props - keeping the
	 * header and script (see {@link ZoneManager#clearZone}).
	 */
	/**
	 * Removes all ADDED zones (index &gt;= 536), restoring the stock ZoneData
	 * layout. Packs first (so pending edits are captured, then removed
	 * wholesale), scans for base-zone warps that would dangle, rewrites the
	 * archive via {@link ZoneRemover}, clears the stale extraction cache and
	 * reloads.
	 */
	private static void removeAddedZonesAction() {
		if (!Workspace.valid || !Workspace.isOA()) {
			JOptionPane.showMessageDialog(frame, "Load an ORAS workspace first.", "Remove added zones", JOptionPane.ERROR_MESSAGE);
			return;
		}
		ctrmap.formats.garc.GARC zoArc = Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA);
		if (zoArc == null || zoArc.length <= 538) {
			JOptionPane.showMessageDialog(frame, "This ZoneData has no added zones (stock layout).", "Remove added zones", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		final int n = zoArc.length - 538;
		java.util.List<String> refs = ZoneRemover.referencesToAdded(zoArc);
		StringBuilder refWarn = new StringBuilder();
		if (!refs.isEmpty()) {
			refWarn.append("\n\nWARNING - these base-zone warps lead into zones being removed and will dangle:");
			for (int i = 0; i < Math.min(8, refs.size()); i++) {
				refWarn.append("\n  ").append(refs.get(i));
			}
			if (refs.size() > 8) {
				refWarn.append("\n  (+").append(refs.size() - 8).append(" more)");
			}
		}
		int rsl = JOptionPane.showConfirmDialog(frame,
				"Remove all " + n + " added zone(s) and restore the stock 536-zone layout?\n\n"
				+ "The workspace is packed first (pending edits captured), then their content is\n"
				+ "DELETED from ZoneData. Afterwards, delete the deployed code.ips - the stock\n"
				+ "game no longer needs the zone-limit patch." + refWarn,
				"Remove added zones", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (rsl != JOptionPane.OK_OPTION) {
			return;
		}
		Workspace.packWorkspace(new Runnable() {
			@Override
			public void run() {
				try {
					int removed = ZoneRemover.removeFromFile(Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA).file);
					//drop the stale extraction cache for everything at/beyond the
					//stock zone range (added zones + the old master/EN cache files)
					java.io.File dir = Workspace.getExtractionDirectory(Workspace.ArchiveType.ZONE_DATA);
					java.io.File[] fs = dir.listFiles();
					if (fs != null) {
						for (java.io.File f : fs) {
							try {
								if (Integer.parseInt(f.getName()) >= 536) {
									Workspace.persist_paths.remove(f.getAbsolutePath());
									f.delete();
								}
							} catch (NumberFormatException ignore) {
							}
						}
					}
					Workspace.reloadGARC(Workspace.ArchiveType.ZONE_DATA);
					mZonePnl.clearForkDeclinesFrom(536); //those slots no longer exist
					mZonePnl.loadEverything(new Runnable() {
						@Override
						public void run() {
							JOptionPane.showMessageDialog(frame,
									"Removed " + removed + " added zone(s) - ZoneData is back to the stock layout.\n\n"
									+ "Remember to DELETE the deployed code.ips:\n"
									+ "  Azahar: load/mods/<titleid>/exefs/code.ips\n"
									+ "  Luma3DS: sdmc:/luma/titles/<titleid>/code.ips\n\n"
									+ "Map regions forked for the removed zones remain in FieldData as unused\n"
									+ "tail data (harmless - nothing references them).",
									"Remove added zones", JOptionPane.INFORMATION_MESSAGE);
						}
					});
				} catch (Exception ex) {
					JOptionPane.showMessageDialog(frame, "Removal failed:\n" + ex.getMessage(), "Remove added zones", JOptionPane.ERROR_MESSAGE);
				}
			}
		});
	}

	private static void emptyZoneAction() {
		if (!Workspace.valid) {
			javax.swing.JOptionPane.showMessageDialog(frame, "Load a workspace first (Options > Workspace settings).", "Empty zone", javax.swing.JOptionPane.ERROR_MESSAGE);
			return;
		}
		ctrmap.formats.garc.GARC zo = Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA);
		if (zo == null) {
			javax.swing.JOptionPane.showMessageDialog(frame, "ZoneData archive unavailable.", "Empty zone", javax.swing.JOptionPane.ERROR_MESSAGE);
			return;
		}
		int zoneCount = zo.length - (Workspace.isXY() ? 1 : 2);
		int def = (mZonePnl != null && mZonePnl.zoneIndex >= 0 && mZonePnl.zoneIndex < zoneCount) ? mZonePnl.zoneIndex : 0;
		javax.swing.JSpinner idSpinner = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(def, 0, zoneCount - 1, 1));
		Object[] form = {
			"Clear a zone's placed content: NPCs, warps, triggers and props.",
			"The header, script, and wild encounters are kept - good for redoing a zone.",
			" ",
			"Zone (the number shown in the zone dropdown):",
			idSpinner
		};
		Object[] opts = {"Empty it", "Cancel"};
		if (javax.swing.JOptionPane.showOptionDialog(frame, form, "Empty zone", javax.swing.JOptionPane.OK_CANCEL_OPTION,
				javax.swing.JOptionPane.WARNING_MESSAGE, null, opts, opts[1]) != 0) {
			return;
		}
		int idx = (Integer) idSpinner.getValue();
		try {
			int removed = ZoneManager.clearZone(idx);
			javax.swing.JOptionPane.showMessageDialog(frame,
					"Zone " + idx + " emptied - removed " + removed + " placed object(s).\n\n"
					+ "Run File > Deploy to emulator (it packs first) to apply.\n"
					+ "Reselect the zone in the dropdown to see it cleared in the editor.",
					"Empty zone", javax.swing.JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception ex) {
			javax.swing.JOptionPane.showMessageDialog(frame, "Empty failed:\n" + ex.getMessage(), "Empty zone", javax.swing.JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Lists base zones (index &lt; 536) that can host interactive custom content
	 * (see {@link ZoneRepurposeScanner}) - the workaround for appended zones not
	 * being able to run scripts.
	 */
	private static void findReusableZonesAction() {
		if (!Workspace.valid) {
			JOptionPane.showMessageDialog(frame, "Load a workspace first (Options > Workspace settings).", "Find reusable zones", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (!Workspace.isOA()) {
			JOptionPane.showMessageDialog(frame, "This is ORAS-only in v1.", "Find reusable zones", JOptionPane.ERROR_MESSAGE);
			return;
		}
		frame.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
		java.util.List<ZoneRepurposeScanner.Candidate> cands;
		try {
			cands = ZoneRepurposeScanner.scan();
		} finally {
			frame.setCursor(java.awt.Cursor.getDefaultCursor());
		}
		int t0 = 0, t1 = 0;
		for (ZoneRepurposeScanner.Candidate c : cands) {
			if (c.tier == 0) {
				t0++;
			} else if (c.tier == 1) {
				t1++;
			}
		}
		StringBuilder sb = new StringBuilder();
		sb.append("Base zones (index < 536) CAN run scripts - build interactive custom areas\n");
		sb.append("(talking NPCs, signs, triggers) here. Appended zones (536+) can't run scripts.\n\n");
		sb.append(t0).append(" SAFEST (placeholder/blank name, empty, no incoming warps),  ")
		  .append(t1).append(" likely-free.\n\n");
		sb.append("VERIFY a pick in-game first: some empty, unreferenced zones are dungeon\n");
		sb.append("interiors reached on foot, and named ones are real areas you'd be replacing.\n\n");
		sb.append(String.format("%-5s %-26s %-4s %-5s %-4s %s%n", "zone", "name", "NPCs", "warp", "trg", "status"));
		sb.append("---------------------------------------------------------------------------\n");
		for (ZoneRepurposeScanner.Candidate c : cands) {
			String nm = c.name.length() > 25 ? c.name.substring(0, 25) : c.name;
			sb.append(String.format("%-5d %-26s %-4d %-5d %-4d %s%n", c.index, nm, c.npcs, c.warpsOut, c.triggers, c.tierLabel()));
		}
		sb.append("\nWorkflow: pick a zone -> Empty zone -> Clone your template map into it\n");
		sb.append("(keep 'own map' checked) -> Rename zone -> add your NPCs. Scripts will work.");
		javax.swing.JTextArea ta = new javax.swing.JTextArea(sb.toString(), 28, 76);
		ta.setEditable(false);
		ta.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
		ta.setCaretPosition(0);
		JOptionPane.showMessageDialog(frame, new javax.swing.JScrollPane(ta), "Find reusable base zones", JOptionPane.INFORMATION_MESSAGE);
	}

	/**
	 * The FieldData region the loaded zone's map matrix points at (its first
	 * grid cell), or -1 - the right default for the OBJ tools so users don't
	 * have to know region numbers.
	 */
	private static int defaultRegionForLoadedZone() {
		try {
			if (mZonePnl == null || mZonePnl.zone == null) {
				return -1;
			}
			File mmFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.MAP_MATRIX, mZonePnl.zone.header.mapmatrixID);
			if (mmFile == null) {
				return -1;
			}
			byte[] mm = java.nio.file.Files.readAllBytes(mmFile.toPath());
			int sub0 = (mm[4] & 0xFF) | ((mm[5] & 0xFF) << 8) | ((mm[6] & 0xFF) << 16) | ((mm[7] & 0xFF) << 24);
			int w = (mm[sub0 + 4] & 0xFF) | ((mm[sub0 + 5] & 0xFF) << 8);
			int h = (mm[sub0 + 6] & 0xFF) | ((mm[sub0 + 7] & 0xFF) << 8);
			for (int k = 0; k < w * h; k++) {
				int id = (mm[sub0 + 8 + k * 2] & 0xFF) | ((mm[sub0 + 9 + k * 2] & 0xFF) << 8);
				if (id != 0xFFFF) {
					return id;
				}
			}
		} catch (Exception ex) {
			//fall through - the spinner just starts at 0
		}
		return -1;
	}

	/** Exports a map region's 3D model to a Blender-ready OBJ (Tools menu). */
	private static void exportMapObjAction() {
		if (!Workspace.valid) {
			JOptionPane.showMessageDialog(frame, "Load a workspace first (Options > Workspace settings).", "Export map to OBJ", JOptionPane.ERROR_MESSAGE);
			return;
		}
		int fieldCount = Workspace.getArchive(Workspace.ArchiveType.FIELD_DATA).length;
		int def = defaultRegionForLoadedZone();
		javax.swing.JSpinner idSpinner = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(def >= 0 ? def : 0, 0, fieldCount - 1, 1));
		Object[] form = {
			"Export a map region's 3D model as a Wavefront OBJ for Blender.",
			(def >= 0 ? "(Defaulted to the loaded zone's map region.)" : "Find the ID per cell in the Matrix Editor."),
			"FieldData region ID:",
			idSpinner,
			" ",
			"Edit it in Blender, keep the group names (mesh<N>_...) intact, then use",
			"Tools > Import OBJ into map region to bring the changes back."
		};
		if (JOptionPane.showConfirmDialog(frame, form, "Export map to OBJ", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
			return;
		}
		int id = (Integer) idSpinner.getValue();
		JFileChooser fc = new JFileChooser();
		fc.setDialogTitle("Save OBJ");
		fc.setSelectedFile(new File("region" + id + ".obj"));
		if (fc.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		try {
			GR gr = new GR(Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, id));
			byte[] model = gr.getFile(1);
			if (!ctrmap.formats.h3d.BchMapModel.isMapModel(model)) {
				JOptionPane.showMessageDialog(frame, "FieldData region " + id + " has no editable map model.", "Export map to OBJ", JOptionPane.ERROR_MESSAGE);
				return;
			}
			ctrmap.formats.h3d.BchMapModel bmm = new ctrmap.formats.h3d.BchMapModel(model);
			java.io.Writer w = new java.io.BufferedWriter(new java.io.FileWriter(fc.getSelectedFile()));
			java.util.List<Integer> skipped;
			try {
				skipped = ctrmap.formats.h3d.MapModelObj.export(bmm, w);
			} finally {
				w.close();
			}
			JOptionPane.showMessageDialog(frame,
					"Exported region " + id + " (" + bmm.meshes.size() + " meshes) to\n" + fc.getSelectedFile().getAbsolutePath()
					+ (skipped.isEmpty() ? "" : "\n\n" + skipped.size() + " mesh(es) use an exotic vertex format and were skipped;\nthey stay untouched on import."),
					"Export map to OBJ", JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(frame, "Export failed:\n" + ex.getMessage(), "Export map to OBJ", JOptionPane.ERROR_MESSAGE);
		}
	}

	/** Imports a (Blender-edited) OBJ back into a map region's 3D model (Tools menu). */
	private static void importMapObjAction() {
		if (!Workspace.valid) {
			JOptionPane.showMessageDialog(frame, "Load a workspace first (Options > Workspace settings).", "Import OBJ", JOptionPane.ERROR_MESSAGE);
			return;
		}
		int fieldCount = Workspace.getArchive(Workspace.ArchiveType.FIELD_DATA).length;
		int def = defaultRegionForLoadedZone();
		javax.swing.JSpinner idSpinner = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(def >= 0 ? def : 0, 0, fieldCount - 1, 1));
		Object[] form = {
			"Import a Blender-edited OBJ back into a map region.",
			"Use the SAME region you exported from" + (def >= 0 ? " (defaulted to the loaded zone's)." : "."),
			"FieldData region ID:",
			idSpinner,
			" ",
			"Groups named mesh<N>_... replace that mesh; other groups are added to",
			"the mesh whose material matches their usemtl. Textures follow the",
			"nearest original surface automatically."
		};
		if (JOptionPane.showConfirmDialog(frame, form, "Import OBJ", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
			return;
		}
		int id = (Integer) idSpinner.getValue();
		JFileChooser fc = new JFileChooser();
		fc.setDialogTitle("Pick the edited OBJ");
		if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
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
			File grFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, id);
			GR gr = new GR(grFile);
			byte[] model = gr.getFile(1);
			if (!ctrmap.formats.h3d.BchMapModel.isMapModel(model)) {
				JOptionPane.showMessageDialog(frame, "FieldData region " + id + " has no editable map model.", "Import OBJ", JOptionPane.ERROR_MESSAGE);
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
					javax.swing.JSpinner tMesh = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(0, 0, 999, 1));
					Object[] tForm = {
						"These OBJ groups use materials this map does not have:",
						"  " + newMats,
						" ",
						"They can be created as NEW materials by cloning a template mesh's",
						"render setup + texture. Pick any mesh whose LOOK (texture) fits:",
						"Template region:", tRegion,
						"Template mesh number (see exported group names, mesh<N>_...):", tMesh,
						" ",
						"Cancel imports only the groups whose materials already exist."
					};
					if (JOptionPane.showConfirmDialog(frame, tForm, "New materials", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
						GR tgr = new GR(Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, (Integer) tRegion.getValue()));
						byte[] tm = tgr.getFile(1);
						if (ctrmap.formats.h3d.BchMapModel.isMapModel(tm)) {
							templateModel = tm;
							templateMesh = (Integer) tMesh.getValue();
						} else {
							JOptionPane.showMessageDialog(frame, "That region has no map model - new-material groups will be skipped.", "Import OBJ", JOptionPane.WARNING_MESSAGE);
						}
					}
				}
			}
			java.util.List<ctrmap.formats.h3d.MapModelObjImporter.Outcome> outcomes = new java.util.ArrayList<>();
			byte[] edited = ctrmap.formats.h3d.MapModelObjImporter.apply(model, parsed, outcomes, templateModel, templateMesh);
			//sanity: the edited model must re-parse clean before it touches the workspace
			ctrmap.formats.h3d.BchMapModel check = new ctrmap.formats.h3d.BchMapModel(edited);
			if (!check.validate().isEmpty()) {
				JOptionPane.showMessageDialog(frame, "The edited model failed validation - nothing was changed:\n" + check.validate(), "Import OBJ", JOptionPane.ERROR_MESSAGE);
				return;
			}
			if (!gr.storeFile(1, edited)) {
				JOptionPane.showMessageDialog(frame, "Could not write the model into the workspace.", "Import OBJ", JOptionPane.ERROR_MESSAGE);
				return;
			}
			Workspace.addPersist(grFile);
			StringBuilder sb = new StringBuilder("Imported into region " + id + ":\n");
			for (ctrmap.formats.h3d.MapModelObjImporter.Outcome oc : outcomes) {
				sb.append("  ").append(oc.group).append(": ").append(oc.action)
				  .append(" (").append(oc.vertices).append(" verts, ").append(oc.faces).append(" faces)\n");
			}
			sb.append("\nRun File > Deploy to emulator to see it in game (packs automatically).");
			JOptionPane.showMessageDialog(frame, sb.toString(), "Import OBJ", JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(frame, "Import failed:\n" + ex.getMessage(), "Import OBJ", JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * "Start this zone's map from scratch": forks the zone's geometry to a
	 * private copy (base town untouched), then blanks every private region to a
	 * flat walkable canvas in the chosen ground material. The natural first step
	 * for building a brand-new town/facility.
	 */
	private static void blankCanvasAction() {
		if (!Workspace.valid || !Workspace.isOA()) {
			JOptionPane.showMessageDialog(frame, "Load an ORAS workspace first.", "Blank map canvas", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (mZonePnl == null || mZonePnl.zone == null || mZonePnl.zoneIndex < 0) {
			JOptionPane.showMessageDialog(frame, "Load the zone first (Zone tab).", "Blank map canvas", JOptionPane.ERROR_MESSAGE);
			return;
		}
		final int zoneIndex = mZonePnl.zoneIndex;
		//ground-material picker from the zone's first region model
		ctrmap.formats.h3d.BchMapModel probe;
		try {
			File mmFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.MAP_MATRIX, mZonePnl.zone.header.mapmatrixID);
			byte[] mm = java.nio.file.Files.readAllBytes(mmFile.toPath());
			int sub0 = (mm[4] & 0xFF) | ((mm[5] & 0xFF) << 8) | ((mm[6] & 0xFF) << 16) | ((mm[7] & 0xFF) << 24);
			int w = (mm[sub0 + 4] & 0xFF) | ((mm[sub0 + 5] & 0xFF) << 8);
			int h = (mm[sub0 + 6] & 0xFF) | ((mm[sub0 + 7] & 0xFF) << 8);
			int rid = -1;
			for (int k = 0; k < w * h && rid < 0; k++) {
				int id = (mm[sub0 + 8 + k * 2] & 0xFF) | ((mm[sub0 + 9 + k * 2] & 0xFF) << 8);
				if (id != 0xFFFF) {
					rid = id;
				}
			}
			GR gr = new GR(Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, rid));
			probe = new ctrmap.formats.h3d.BchMapModel(gr.getFile(1));
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(frame, "Could not inspect the zone's map:\n" + ex.getMessage(), "Blank map canvas", JOptionPane.ERROR_MESSAGE);
			return;
		}
		//list the zone model's materials LARGEST FIRST - the ground is almost
		//always the biggest mesh, and small parts (doors, windows, tree bits)
		//sink to the bottom instead of cluttering the top of the list
		java.util.List<int[]> order = new java.util.ArrayList<>(); //{meshIndex, tris}
		for (ctrmap.formats.h3d.BchMapModel.MeshGeom g : probe.geometry()) {
			if (!g.posOk) {
				continue;
			}
			order.add(new int[]{g.meshIndex, probe.getTriangles(g.meshIndex).length / 3});
		}
		order.sort((a, b) -> b[1] - a[1]);
		java.util.List<String> items = new java.util.ArrayList<>();
		java.util.List<Integer> meshIds = new java.util.ArrayList<>();
		for (int[] o : order) {
			String label = probe.getMaterialName(probe.getMeshMaterialIndex(o[0])) + "  (" + o[1] + " faces)";
			if (items.isEmpty()) {
				label += "  - this map's main ground";
			}
			items.add(label);
			meshIds.add(o[0]);
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
			"materials, largest first - the top entry is almost always the",
			"ground. City/cave maps use texture ATLASES, so a preview can look",
			"busy; the canvas still paints it as a clean tiled floor.",
			matPicker,
			" ",
			"This packs the workspace when done."
		};
		if (JOptionPane.showConfirmDialog(frame, form, "Blank map canvas", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
			return;
		}
		final int groundMesh = meshIds.get(matPicker.getSelectedIndex());
		try {
			//a re-run paints over the zone's own private regions instead of
			//appending another set beside them
			GeometryForker.ForkResult r = GeometryForker.ensurePrivate(zoneIndex);
			File fdDir = Workspace.getExtractionDirectory(Workspace.ArchiveType.FIELD_DATA);
			for (int newRegion : r.newRegions) {
				File f = new File(fdDir, String.valueOf(newRegion));
				GR gr = new GR(f);
				byte[] template = gr.getFile(1);
				if (!ctrmap.formats.h3d.BchMapModel.isMapModel(template)) {
					continue;
				}
				ctrmap.formats.h3d.BchMapModel tm = new ctrmap.formats.h3d.BchMapModel(template);
				int gm = groundMesh;
				if (gm >= tm.meshCount || !tm.geometry().get(gm).posOk) {
					gm = 0;
					int bt = -1;
					for (ctrmap.formats.h3d.BchMapModel.MeshGeom g : tm.geometry()) {
						if (g.posOk && tm.getTriangles(g.meshIndex).length > bt) {
							bt = tm.getTriangles(g.meshIndex).length;
							gm = g.meshIndex;
						}
					}
				}
				ctrmap.formats.h3d.RegionFactory.BlankContent bc = ctrmap.formats.h3d.RegionFactory.blank(template, gm);
				gr.storeFile(1, bc.model);
				gr.storeFile(2, bc.collision);
				gr.storeFile(0, bc.tilemap);
				gr.storeFile(3, bc.props);
				//extra layers (multi-layer templates): blank them out entirely
				if (gr.len >= 9) {
					gr.storeFile(7, ctrmap.formats.h3d.RegionFactory.voidTilemap());
					gr.storeFile(gr.len >= 11 ? 9 : 8, ctrmap.formats.h3d.RegionFactory.emptyCollision());
					if (gr.len >= 11) {
						gr.storeFile(8, ctrmap.formats.h3d.RegionFactory.voidTilemap());
						gr.storeFile(10, ctrmap.formats.h3d.RegionFactory.emptyCollision());
					}
				}
			}
			final int zi = zoneIndex;
			Workspace.packWorkspace(new Runnable() {
				@Override
				public void run() {
					mZonePnl.loadEverything(new Runnable() {
						@Override
						public void run() {
							mZonePnl.selectZone(zi);
							JOptionPane.showMessageDialog(frame,
									"Zone " + zi + " now has a private blank canvas (region(s) "
									+ java.util.Arrays.toString(r.newRegions) + ").\n\n"
									+ "Build on it with prefabs, the Geometry tool, or Blender OBJ import.\n"
									+ "Deploy to emulator to walk on it.",
									"Blank map canvas", JOptionPane.INFORMATION_MESSAGE);
						}
					});
				}
			});
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(frame, "Blank canvas failed:\n" + ex.getMessage(), "Blank map canvas", JOptionPane.ERROR_MESSAGE);
		}
	}

	/** The ORAS ZoneData index of the Battle Maison lobby (Battle Resort). */
	private static final int MAISON_LOBBY_ZONE = 517;
	/** The ORAS ZoneData index of the Battle Institute lobby (Mauville). */
	private static final int INSTITUTE_ZONE = 448;

	/**
	 * "Set up a Battle facility here": replaces the loaded BASE zone with a copy
	 * of a retail Battle facility lobby (Maison or Institute) - script AND
	 * entities verbatim, so the facility's engine logic is exactly retail - then
	 * forks its geometry so edits stay local. The starting point for ANY custom
	 * battle facility: author opponents in the pools' free slots (Game Data ->
	 * Facility opponents) and edit the text, then deploy. Must target a base
	 * zone (index &lt; 536) because appended zones cannot run field scripts.
	 */
	private static void setupFacilityAction() {
		if (!Workspace.valid || !Workspace.isOA()) {
			JOptionPane.showMessageDialog(frame, "Load an ORAS workspace first.", "Set up Battle facility", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (mZonePnl == null || mZonePnl.zoneIndex < 0) {
			JOptionPane.showMessageDialog(frame, "Load the base zone to convert first (Zone tab).", "Set up Battle facility", JOptionPane.ERROR_MESSAGE);
			return;
		}
		final int dstIndex = mZonePnl.zoneIndex;
		int baseZones = Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA).length - 2;
		if (dstIndex >= baseZones) {
			JOptionPane.showMessageDialog(frame,
					"This is an appended zone (index " + dstIndex + "). Appended zones cannot run field\n"
					+ "scripts, so a facility must go in a base zone (< " + baseZones + "). Load or repurpose a\n"
					+ "base zone (e.g. an unused one, via Tools -> Empty zone) and try again.",
					"Set up Battle facility", JOptionPane.ERROR_MESSAGE);
			return;
		}
		//two ways to build a facility - offer both honestly
		String[] paths = {"Independent battles (your own trainers)", "Clone a retail facility (full engine)"};
		int path = JOptionPane.showOptionDialog(frame,
				"How should this facility's battles work?\n\n"
				+ "INDEPENDENT: an NPC that battles trainer entries YOU author (Game Data ->\n"
				+ "Trainers), with streak + BP rewards in its own script. Nothing vanilla is\n"
				+ "touched, works in any zone, no zone replacement. (New - unproven in-game.)\n\n"
				+ "CLONE: this zone is replaced by a copy of the Battle Maison or Institute -\n"
				+ "the full retail engine (formats, streak saves, scoring), but its opponent\n"
				+ "pools are ENGINE-WIDE, shared with the retail facility.",
				"Set up Battle facility", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, paths, paths[0]);
		if (path < 0) {
			return;
		}
		if (path == 0) {
			JOptionPane.showMessageDialog(frame,
					"To add an independent battle NPC:\n\n"
					+ "1. Author its opponents' teams in Game Data -> Trainers (pick unused\n"
					+ "   trainer entries - blank-named ones are safe to repurpose).\n"
					+ "2. On the World Editor, choose the NPC tool -> Add -> \"Battle challenge\n"
					+ "   (own trainers)\" and build the lineup, rewards and dialogue there.\n\n"
					+ "Each NPC is one challenge lane; place several for a multi-lane facility.",
					"Independent battle facility", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		String[] kinds = {"Battle Maison (5 formats, Chatelaines)", "Battle Institute (single test)"};
		Object kind = JOptionPane.showInputDialog(frame,
				"Replace zone " + dstIndex + " with a copy of which retail facility?\n"
				+ "(Its script + NPCs are copied verbatim - the engine logic is retail.\n"
				+ "You then author the opponents and text.)",
				"Set up Battle facility", JOptionPane.PLAIN_MESSAGE, null, kinds, kinds[0]);
		if (kind == null) {
			return;
		}
		final int srcIndex = kind == kinds[1] ? INSTITUTE_ZONE : MAISON_LOBBY_ZONE;
		if (srcIndex == dstIndex) {
			JOptionPane.showMessageDialog(frame, "That IS the source facility zone - pick a different base zone to convert.", "Set up Battle facility", JOptionPane.ERROR_MESSAGE);
			return;
		}
		int confirm = JOptionPane.showConfirmDialog(frame,
				"Zone " + dstIndex + " will be COMPLETELY REPLACED by a copy of the facility\n"
				+ "lobby (zone " + srcIndex + "): its map, NPCs and script. The copy gets its own\n"
				+ "geometry, so editing it will not change the real facility.\n\n"
				+ "Note: opponent pools are ENGINE-WIDE (shared with the retail facility) -\n"
				+ "author new teams in the pools' FREE slots via Game Data -> Facility opponents.\n"
				+ "Then edit the text and Deploy. Continue?",
				"Set up Battle facility", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (confirm != JOptionPane.OK_OPTION) {
			return;
		}
		try {
			ctrmap.ZoneCloner.cloneIntoSlot(srcIndex, dstIndex);
			mZonePnl.clearForkDecline(dstIndex); //the slot holds a new zone now
			GeometryForker.ensurePrivate(dstIndex);
			Workspace.packWorkspace(new Runnable() {
				@Override
				public void run() {
					mZonePnl.loadEverything(new Runnable() {
						@Override
						public void run() {
							mZonePnl.selectZone(dstIndex);
							JOptionPane.showMessageDialog(frame,
									"Zone " + dstIndex + " is now a copy of the facility (from zone " + srcIndex + ").\n\n"
									+ "Next: Game Data -> Facility opponents to author the teams (use the\n"
									+ "pools' free slots - they are shared with the retail facility),\n"
									+ "edit the facility's text, and Deploy to try it in the emulator.\n"
									+ "(A grown/custom facility is unproven in-game - test it.)",
									"Set up Battle facility", JOptionPane.INFORMATION_MESSAGE);
						}
					});
				}
			});
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(frame, "Facility setup failed:\n" + ex.getMessage(), "Set up Battle facility", JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Grows the loaded zone's map to WxH regions ({@link MapResizer}) - new
	 * cells become blank canvases in the zone's own area style.
	 */
	private static void resizeMapAction() {
		if (!Workspace.valid || !Workspace.isOA()) {
			JOptionPane.showMessageDialog(frame, "Load an ORAS workspace first.", "Resize map", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (mZonePnl == null || mZonePnl.zone == null || mZonePnl.zoneIndex < 0) {
			JOptionPane.showMessageDialog(frame, "Load the zone first (Zone tab).", "Resize map", JOptionPane.ERROR_MESSAGE);
			return;
		}
		final int zoneIndex = mZonePnl.zoneIndex;
		javax.swing.JSpinner wSpin = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(2, 1, 4, 1));
		javax.swing.JSpinner hSpin = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(1, 1, 4, 1));
		Object[] form = {
			"Grow this zone's map to a grid of regions (each region = one 40x40-tile map).",
			"Existing map cells stay; NEW cells become flat blank canvases in this",
			"zone's own style - build on them with prefabs, the Geometry tool, or Blender.",
			" ",
			"Width (regions):", wSpin,
			"Height (regions):", hSpin,
			" ",
			"EXPERIMENTAL: multi-region retail maps prove the engine path, but a grown",
			"custom map has not been booted in-game yet. This packs when done."
		};
		if (JOptionPane.showConfirmDialog(frame, form, "Resize map", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
			return;
		}
		try {
			final MapResizer.ResizeResult r = MapResizer.resize(zoneIndex, (Integer) wSpin.getValue(), (Integer) hSpin.getValue());
			Workspace.packWorkspace(new Runnable() {
				@Override
				public void run() {
					mZonePnl.loadEverything(new Runnable() {
						@Override
						public void run() {
							mZonePnl.selectZone(zoneIndex);
							JOptionPane.showMessageDialog(frame,
									"Map grown " + r.oldW + "x" + r.oldH + " -> " + r.newW + "x" + r.newH
									+ " (new blank region(s) " + java.util.Arrays.toString(r.newRegions) + ", matrix " + r.newMatrix + ").\n"
									+ "Deploy to emulator to walk the new area.",
									"Resize map", JOptionPane.INFORMATION_MESSAGE);
						}
					});
				}
			});
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(frame, "Resize failed:\n" + ex.getMessage(), "Resize map", JOptionPane.ERROR_MESSAGE);
		}
	}

	private static void importMapModelAction() {
		if (!Workspace.valid) {
			javax.swing.JOptionPane.showMessageDialog(frame, "Load a workspace first (Options > Workspace settings).", "Import map model", javax.swing.JOptionPane.ERROR_MESSAGE);
			return;
		}
		int fieldCount = Workspace.getArchive(Workspace.ArchiveType.FIELD_DATA).length;
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
		if (javax.swing.JOptionPane.showConfirmDialog(frame, form, "Import map model", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE) != javax.swing.JOptionPane.OK_OPTION) {
			return;
		}
		int id = (Integer) idSpinner.getValue();

		Preferences prefs = Preferences.userRoot().node(CtrmapMainframe.class.getName());
		JFileChooser jfc = new JFileChooser(prefs.get("LAST_DIR", new File(".").getAbsolutePath()));
		jfc.setDialogTitle("Open map model .bch");
		jfc.setFileSelectionMode(JFileChooser.FILES_ONLY);
		jfc.setMultiSelectionEnabled(false);
		if (jfc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION || jfc.getSelectedFile() == null) {
			return;
		}
		File chosen = jfc.getSelectedFile();
		prefs.put("LAST_DIR", chosen.getParent());

		byte[] bch;
		try {
			bch = java.nio.file.Files.readAllBytes(chosen.toPath());
		} catch (java.io.IOException ex) {
			javax.swing.JOptionPane.showMessageDialog(frame, "Could not read the file:\n" + ex.getMessage(), "Import map model", javax.swing.JOptionPane.ERROR_MESSAGE);
			return;
		}
		//sanity-check that it parses as a BCH model
		try {
			ctrmap.formats.h3d.BCHFile parsed = new ctrmap.formats.h3d.BCHFile(bch);
			if (parsed.errorlevel != 0 || parsed.models.isEmpty()) {
				javax.swing.JOptionPane.showMessageDialog(frame, "That file does not parse as a BCH model (no models / read error).", "Import map model", javax.swing.JOptionPane.ERROR_MESSAGE);
				return;
			}
		} catch (RuntimeException ex) {
			javax.swing.JOptionPane.showMessageDialog(frame, "That file is not a readable BCH model:\n" + ex.getMessage(), "Import map model", javax.swing.JOptionPane.ERROR_MESSAGE);
			return;
		}

		Object[] warnOpts = {"Continue", "Cancel"};
		int confirm = javax.swing.JOptionPane.showOptionDialog(frame,
				"EXPERIMENTAL - untested on real hardware.\n\n"
				+ "This replaces the visual model of FieldData region " + id + " with your\n"
				+ ".bch. The archive rebuild is byte-faithful for every other region and\n"
				+ "subfile, but whether the GAME loads a rebuilt/replaced map model is not\n"
				+ "yet verified. Keep a RomFS backup and test in Citra before hardware.\n\n"
				+ "Continue?",
				"Import map model", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE,
				null, warnOpts, warnOpts[1]);
		if (confirm != 0) {
			return;
		}
		try {
			File grFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, id);
			GR gr = new GR(grFile);
			if (gr.len < 2) {
				javax.swing.JOptionPane.showMessageDialog(frame, "FieldData entry " + id + " is not a map region container.", "Import map model", javax.swing.JOptionPane.ERROR_MESSAGE);
				return;
			}
			if (!gr.storeFile(1, bch)) {
				javax.swing.JOptionPane.showMessageDialog(frame, "Could not write the model into the workspace.", "Import map model", javax.swing.JOptionPane.ERROR_MESSAGE);
				return;
			}
			Workspace.addPersist(grFile);
		} catch (RuntimeException ex) {
			javax.swing.JOptionPane.showMessageDialog(frame, "Import failed:\n" + ex.getMessage(), "Import map model", javax.swing.JOptionPane.ERROR_MESSAGE);
			return;
		}
		javax.swing.JOptionPane.showMessageDialog(frame,
				"Map model imported into FieldData region " + id + ".\n\n"
				+ "Run File > Pack Workspace, then load the RomFS as a LayeredFS mod and\n"
				+ "TEST IN CITRA - confirm the map loads before trusting it.",
				"Import map model", javax.swing.JOptionPane.INFORMATION_MESSAGE);
	}

	public static void adjustSplitPanes() {
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
