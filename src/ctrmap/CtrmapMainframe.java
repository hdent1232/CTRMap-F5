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
	public static JMenuItem findReusableZones;
	public static JMenuItem wssettings;
	public static JMenuItem wsclean;
	public static JMenuItem isstracker;
	public static JMenuItem about;
	public static JToolBar toolbar;
	public static ButtonGroup toolBtnGroup;
	public static JRadioButton btnEditTool;
	public static JRadioButton btnSetTool;
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
		tilePainter = new JMenuItem("Paint map tiles (this zone)...");
		areaLighting = new JMenuItem("Edit area fog & lighting...");
		wildEncounters = new JMenuItem("Edit wild encounters (this zone)...");
		resizeMap = new JMenuItem("Resize map (this zone)...");
		trainerEditor = new JMenuItem("Edit trainer (party/battle)...");
		maisonEditor = new JMenuItem("Edit Battle Maison opponents...");
		shopEditor = new JMenuItem("Edit shop inventories (Marts)...");
		setupFacility = new JMenuItem("Set up Battle facility here (clone Maison)...");
		renameZone = new JMenuItem("Rename zone (in-game name)...");
		emptyZone = new JMenuItem("Empty zone (clear contents)...");
		findReusableZones = new JMenuItem("Find reusable base zones...");
		wssettings = new JMenuItem("Workspace settings");
		wsclean = new JMenuItem("Clean workspace");
		isstracker = new JMenuItem("Support/Issue tracker");
		about = new JMenuItem("About");
		toolbar = new JToolBar();
		btnEditTool = Utils.createGraphicalButton("_tool_edit");
		btnSetTool = Utils.createGraphicalButton("_tool_set");
		btnFillTool = Utils.createGraphicalButton("_tool_fill");
		btnCamTool = Utils.createGraphicalButton("_tool_cam");
		btnPropTool = Utils.createGraphicalButton("_tool_prop");
		btnNPCTool = Utils.createGraphicalButton("_tool_npc");
		btnWarpTool = Utils.createGraphicalButton("_tool_warp");
		btnTriggerTool = Utils.createGraphicalButton("_tool_trigger");
		btnGeoTool = new JRadioButton("3D");
		btnGeoTool.setToolTipText("Geometry tool - drag tiles, then move/duplicate/delete the 3D map geometry on them");
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
		toolBtnGroup.add(btnGeoTool);
		toolbar.add(btnEditTool);
		toolbar.add(btnSetTool);
		toolbar.add(btnFillTool);
		toolbar.add(btnCamTool);
		toolbar.add(btnPropTool);
		toolbar.add(btnNPCTool);
		toolbar.add(btnWarpTool);
		toolbar.add(btnTriggerTool);
		toolbar.add(btnGeoTool);
		toolbar.add(currentTool);

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

		tileEditMasterPnl.add(toolbar, BorderLayout.NORTH);
		tileEditMasterPnl.add(jsp, BorderLayout.CENTER);

		collEditMasterPnl.add(jsp2);

		mtxEditMasterPnl.add(jsp3);

		tabs.add("World Editor", tileEditMasterPnl);
		tabs.add("Collision Editor", collEditMasterPnl);
		tabs.add("Matrix Editor", mtxEditMasterPnl);
		tabs.add("Zone Loader", mZonePnl);
		tabs.setToolTipTextAt(tabs.indexOfComponent(mZonePnl), "Pick a map from the zone dropdown here - this is how zones are opened.");
		tabs.add("Script Editor (experimental)", mScriptPnl);
		tabs.add("Extras", mExtrasPnl);
		tabs.add("Text Editor", mTextEditor);
		tabs.add("Builder", mBuilder);

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
				ctrmap.humaninterface.TilePainterForm.show();
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
					mTileMapPanel.loadMatrix(new MapMatrix(new MM(jfc.getSelectedFile())), null, null, null);
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
		toolsmenu.add(tilesetWriter);
		toolsmenu.add(objconvert);
		toolsmenu.add(importMapModel);
		toolsmenu.add(exportMapObj);
		toolsmenu.add(importMapObj);
		toolsmenu.add(forkGeometry);
		toolsmenu.add(blankCanvas);
		toolsmenu.add(tilePainter);
		toolsmenu.add(areaLighting);
		toolsmenu.add(wildEncounters);
		toolsmenu.add(resizeMap);
		toolsmenu.add(trainerEditor);
		toolsmenu.add(maisonEditor);
		toolsmenu.add(shopEditor);
		toolsmenu.add(setupFacility);
		toolsmenu.add(renameZone);
		toolsmenu.add(emptyZone);
		toolsmenu.add(findReusableZones);
		optionsmenu.add(wssettings);
		optionsmenu.add(wsclean);
		helpmenu.add(isstracker);
		helpmenu.add(about);
		menubar.add(filemenu);
		menubar.add(toolsmenu);
		menubar.add(optionsmenu);
		menubar.add(helpmenu);

		CM3DComponents.add(mTileMapPanel);
		CM3DComponents.add(mPropEditForm);
		CM3DComponents.add(mNPCEditForm);

		frame.setSize(1280, 720 + menubar.getHeight());
		frame.setMinimumSize(frame.getSize());
		frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
		frame.setVisible(true);

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
					System.exit(0);
				}
			}
		});
		frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("F2"), "switch2D");
		frame.getRootPane().getActionMap().put("switch2D", new AbstractAction("switch2D") {
			@Override
			public void actionPerformed(ActionEvent e) {
				Utils.setGraphicUI(mTilemapScrollPane);
			}
		});
		frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("F3"), "switch3D");
		frame.getRootPane().getActionMap().put("switch3D", new AbstractAction("switch3D") {
			@Override
			public void actionPerformed(ActionEvent e) {
				Utils.setGraphicUI(m3DDebugPanel);
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
		Workspace.validate(frame);
	}

	/**
	 * Lands the user on the tab that actually opens zones and explains how to use
	 * it. Called from Workspace.validate() so that it also fires for the first
	 * validation that succeeds after the paths are set in Workspace settings.
	 */
	public static void showZoneLoadingHint() {
		tabs.setSelectedComponent(mZonePnl);
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
		Object[] form = {
			"Deploy your edits as a LayeredFS mod - only archives you actually changed are copied.",
			"Your workspace is packed automatically first, so the latest edits always ship.",
			" ",
			"Title ID:", titleField,
			"Mod folder (Azahar auto-detected; Browse to your SD card for a 3DS/Luma):", folderRow,
			"Code patch to install (optional - the code.ips from 'Add zones'):", ipsRow
		};
		if (JOptionPane.showConfirmDialog(frame, form, "Deploy to emulator (LayeredFS mod)",
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
			return;
		}
		String folder = folderField.getText().trim();
		if (folder.isEmpty()) {
			JOptionPane.showMessageDialog(frame, "Pick a mod folder first.", "Deploy mod", JOptionPane.ERROR_MESSAGE);
			return;
		}
		final File modRoot = new File(folder);
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
				sb.append("To disable the mod, delete that folder.");
				JOptionPane.showMessageDialog(frame, sb.toString(), "Deploy to emulator", JOptionPane.INFORMATION_MESSAGE);
			}
		});
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
			GeometryForker.ForkResult r = GeometryForker.forkGeometry(zoneIndex);
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
		java.util.List<String> items = new java.util.ArrayList<>();
		java.util.List<Integer> meshIds = new java.util.ArrayList<>();
		int def = 0, bestTris = -1;
		for (ctrmap.formats.h3d.BchMapModel.MeshGeom g : probe.geometry()) {
			if (!g.posOk) {
				continue;
			}
			int tris = probe.getTriangles(g.meshIndex).length / 3;
			items.add(probe.getMaterialName(probe.getMeshMaterialIndex(g.meshIndex)) + "  (" + tris + " faces)");
			meshIds.add(g.meshIndex);
			if (tris > bestTris) {
				bestTris = tris;
				def = items.size() - 1;
			}
		}
		javax.swing.JComboBox<String> matPicker = new javax.swing.JComboBox<>(items.toArray(new String[0]));
		matPicker.setSelectedIndex(def);
		Object[] form = {
			"Replace THIS ZONE's map with a flat, walkable blank canvas.",
			"The zone gets its own private map first - the town it was cloned",
			"from keeps its map. Then build with prefabs, the Geometry tool,",
			"or Blender.",
			" ",
			"Ground material (the floor's look):",
			matPicker,
			" ",
			"This packs the workspace when done."
		};
		if (JOptionPane.showConfirmDialog(frame, form, "Blank map canvas", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
			return;
		}
		final int groundMesh = meshIds.get(matPicker.getSelectedIndex());
		try {
			GeometryForker.ForkResult r = GeometryForker.forkGeometry(zoneIndex);
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
	 * forks its geometry so edits stay local. The starting point for a custom
	 * Delta-Emerald Frontier facility: reskin the opponents (Tools -> Edit Battle
	 * Maison opponents) and the text, then deploy. Must target a base zone
	 * (index &lt; 536) because appended zones cannot run field scripts.
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
		String[] kinds = {"Battle Maison (5 formats, Chatelaines)", "Battle Institute (single test)"};
		Object kind = JOptionPane.showInputDialog(frame,
				"Replace zone " + dstIndex + " with a copy of which facility?\n"
				+ "(Its script + NPCs are copied verbatim - the engine logic is retail.\n"
				+ "You then reskin the opponents and text.)",
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
				+ "After it packs: reskin opponents with Tools -> Edit Battle Maison opponents,\n"
				+ "edit the text, then Deploy. Continue?",
				"Set up Battle facility", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (confirm != JOptionPane.OK_OPTION) {
			return;
		}
		try {
			ctrmap.ZoneCloner.cloneIntoSlot(srcIndex, dstIndex);
			GeometryForker.forkGeometry(dstIndex);
			Workspace.packWorkspace(new Runnable() {
				@Override
				public void run() {
					mZonePnl.loadEverything(new Runnable() {
						@Override
						public void run() {
							mZonePnl.selectZone(dstIndex);
							JOptionPane.showMessageDialog(frame,
									"Zone " + dstIndex + " is now a copy of the facility (from zone " + srcIndex + ").\n\n"
									+ "Next: Tools -> Edit Battle Maison opponents to set the teams,\n"
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
