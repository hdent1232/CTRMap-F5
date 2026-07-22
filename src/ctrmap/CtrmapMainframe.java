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
	public static JMenuItem tilesetWriter;
	public static JMenuItem objconvert;
	public static JMenuItem importMapModel;
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
		tilesetWriter = new JMenuItem("Tileset Editor");
		objconvert = new JMenuItem("OBJ to collisions");
		importMapModel = new JMenuItem("Import map model (.bch)...");
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
		toolbar.add(btnEditTool);
		toolbar.add(btnSetTool);
		toolbar.add(btnFillTool);
		toolbar.add(btnCamTool);
		toolbar.add(btnPropTool);
		toolbar.add(btnNPCTool);
		toolbar.add(btnWarpTool);
		toolbar.add(btnTriggerTool);
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
		toolsmenu.add(tilesetWriter);
		toolsmenu.add(objconvert);
		toolsmenu.add(importMapModel);
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
