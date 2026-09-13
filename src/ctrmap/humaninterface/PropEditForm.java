package ctrmap.humaninterface;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.glu.gl2.GLUgl2;
import ctrmap.CtrmapMainframe;
import ctrmap.LoadedZone;
import ctrmap.LittleEndianDataInputStream;
import ctrmap.formats.propdata.GRProp;
import ctrmap.formats.propdata.GRPropData;
import ctrmap.Workspace;
import ctrmap.formats.containers.BM;
import ctrmap.formats.containers.GR;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.BchTexturePack;
import ctrmap.formats.h3d.model.H3DModel;
import ctrmap.formats.h3d.model.H3DVertex;
import ctrmap.formats.h3d.texturing.H3DTexture;
import ctrmap.formats.propdata.ADPropRegistry;
import ctrmap.formats.propdata.PropDatabase;
import ctrmap.formats.text.LocationNames;
import ctrmap.formats.vectors.Vec3f;
import ctrmap.formats.zone.Zone;
import ctrmap.formats.zone.ZoneHeader;
import ctrmap.gamedef.ArchiveType;
import ctrmap.humaninterface.tools.PropTool;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.DefaultListModel;
import javax.swing.JFormattedTextField;
import javax.swing.JOptionPane;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.NumberFormatter;

/**
 * GUI form for modifying GR-3 prop data.
 */
public class PropEditForm extends javax.swing.JPanel implements CM3DRenderable {

	/**
	 * Creates new form PropEditForm
	 */
	public GR gr;
	public GRPropData props;
	public ArrayList<H3DModel> models = new ArrayList<>();
	public GRProp prop;
	public ADPropRegistry reg;
	public ADPropRegistry.ADPropRegistryEntry regentry;
	public int propIndex;
	public boolean loaded = false;

	/**
	 * Whether a failed {@code showProp} has already been reported for this
	 * region. One report, not one per drag: {@code showProp} runs on every mouse
	 * move while a prop is dragged, and a dialog per frame would be worse than
	 * the silence it replaces.
	 */
	private boolean saidAShowFailed = false;

	public List<H3DTexture> propTextures = new ArrayList<>();

	/**
	 * Palette rows currently shown in paletteList, index-aligned with the list
	 * model (getNamedModels() filtered by the search field).
	 */
	private PropDatabase.PropModel[] paletteEntries = new PropDatabase.PropModel[0];

	/**
	 * True while a background SwingWorker is building the PropDatabase for the
	 * palette - guards against launching a second build.
	 */
	private boolean paletteLoading = false;

	/** The zone owner this form was handed: the open zone's header and area, and the table, for the area's name. */
	private final LoadedZone loadedZone;

	/** Which tool the editor is holding, handed in: this class only asks. */
	private final ctrmap.humaninterface.tools.ToolSelection tools;

	/** Asks for the editor to be drawn again; handed in, because a JFrame needs a display. */
	private final Redraw redraw;

	/**
	 * The map view, handed in AS ITSELF.
	 *
	 * <p>A narrower interface was weighed and rejected for the reason
	 * {@code ToolHost.map()} already gives: this class uses enough of the panel
	 * that naming a capability per member would be a longer way of writing
	 * TileMapPanel. What matters for the standard is that it is HANDED one and
	 * could be handed a different one - which a suite now does - rather than
	 * reaching into the main window for the only one that exists.
	 */
	private final TileMapPanel map;

	/** The 3D gizmo, handed in. Its two reaches here had no null guard at all. */
	private final Navigator navi;


	/**
	 * Where this form puts a pane of its own - the tool column beside the map.
	 *
	 * <p>HANDED IN, not reached for. The registry editor it opens used to be a window,
	 * which is what this project does not do with features; putting it in the tool
	 * column means naming the column, and naming it through the main window would add
	 * a reference MainframeEdgesTest counts and holds down. So the window hands this
	 * form the one thing it needs - somewhere to show a pane - and a suite can hand it
	 * a recorder instead.
	 */
	private final java.util.function.Consumer<javax.swing.JComponent> toolPane;

	public PropEditForm(LoadedZone loadedZone, ctrmap.humaninterface.tools.ToolSelection tools, Redraw redraw,
			Navigator navi, TileMapPanel map,
			java.util.function.Consumer<javax.swing.JComponent> toolPane) {
		if (toolPane == null) {
			throw new IllegalArgumentException("PropEditForm must be handed the tool column its registry editor opens in");
		}
		this.toolPane = toolPane;
		this.map = map;
		if (navi == null) {
			throw new IllegalArgumentException("PropEditForm must be handed a Navigator - the gizmo it moves");
		}
		this.navi = navi;
		this.redraw = redraw;
		this.tools = tools;
		if (loadedZone == null) {
			throw new IllegalArgumentException("PropEditForm must be handed a LoadedZone");
		}
		this.loadedZone = loadedZone;
		initComponents();
		//affordance for the lazy database build - the list is not just empty
		DefaultListModel<String> placeholderModel = new DefaultListModel<>();
		placeholderModel.addElement("Click to load the prop database...");
		paletteList.setModel(placeholderModel);
		paletteFilter.setToolTipText("<html>Props are placeable objects: trees, doors, signs, statues, TVs, furniture.<br>"
				+ "Large buildings and Pok&eacute;mon Centers are part of the map itself, not props.<br>"
				+ "Names are Game Freak's internal codenames - type plain words (tree, computer,<br>"
				+ "statue, lighthouse, coral, boat, tv) and matching props are found.</html>");
		setFloatValueClass(new JFormattedTextField[]{x, y, z, sx, sy, sz, rx, ry, rz});
		//Only need the DocListeners on the fields that we want to reflect on the GUI immediately
		x.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				if (loaded && prop != null && prop.x != ctrmap.humaninterface.Forms.getFloatFromDocument(x)) {
					prop.x = ctrmap.humaninterface.Forms.getFloatFromDocument(x);
					props.modified = true;
					updateH3D(props.props.indexOf(prop));
					//THE REDRAW REQUEST NOW REACHES SOMETHING, AND ONLY WHEN THE PROP MOVED.
					//This was the bare inherited JComponent.firePropertyChange, and it sat
					//OUTSIDE the guard. Nothing has ever listened to THIS form, so with
					//changeSupport null the call returned at once; the only listener that
					//property has anywhere is on the MAP VIEW, which is why the same constant
					//works from PaintForm, Selector and TileUndo - they spell it with the panel
					//in front. What it cost the user: TileMapPanel.paintComponent composites the
					//3D scene, props included, over the tilemap at 50% alpha and only while the
					//panel is being painted, so a new X moved the prop in the 3D view (which has
					//its own animator) and left the 2D map drawing it where it was, until
					//hovering the map made Selector ask for a repaint.
					//THE RECEIVER is the Redraw this form is already handed and already uses for
					//exactly this in showProp, rather than the panel: it is what the rest of this
					//class asks through, and a suite can read a Redraw back.
					//INSIDE THE GUARD, because outside it every keystroke that changed nothing -
					//including the twelve fields this form fills while opening a region - would
					//now be a full-window repaint instead of the no-op it has always been.
					redraw.all();
				}
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				if (loaded && prop != null && prop.x != ctrmap.humaninterface.Forms.getFloatFromDocument(x)) {
					prop.x = ctrmap.humaninterface.Forms.getFloatFromDocument(x);
					props.modified = true;
					updateH3D(props.props.indexOf(prop));
					//as x's insertUpdate above: the request goes to the Redraw this form was
					//handed, and only when the value actually changed
					redraw.all();
				}
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
			}
		});
		y.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				if (loaded && prop != null && prop.y != ctrmap.humaninterface.Forms.getFloatFromDocument(y)) {
					prop.y = ctrmap.humaninterface.Forms.getFloatFromDocument(y);
					props.modified = true;
					updateH3D(props.props.indexOf(prop));
					//as x's insertUpdate above: the request goes to the Redraw this form was
					//handed, and only when the value actually changed
					redraw.all();
				}
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				if (loaded && prop != null && prop.y != ctrmap.humaninterface.Forms.getFloatFromDocument(y)) {
					prop.y = ctrmap.humaninterface.Forms.getFloatFromDocument(y);
					props.modified = true;
					updateH3D(props.props.indexOf(prop));
					//as x's insertUpdate above: the request goes to the Redraw this form was
					//handed, and only when the value actually changed
					redraw.all();
				}
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
			}
		});
		z.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				if (loaded && prop != null && prop.z != ctrmap.humaninterface.Forms.getFloatFromDocument(z)) {
					prop.z = ctrmap.humaninterface.Forms.getFloatFromDocument(z);
					props.modified = true;
					updateH3D(props.props.indexOf(prop));
					//as x's insertUpdate above: the request goes to the Redraw this form was
					//handed, and only when the value actually changed
					redraw.all();
				}
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				if (loaded && prop != null && prop.z != ctrmap.humaninterface.Forms.getFloatFromDocument(z)) {
					prop.z = ctrmap.humaninterface.Forms.getFloatFromDocument(z);
					props.modified = true;
					updateH3D(props.props.indexOf(prop));
					//as x's insertUpdate above: the request goes to the Redraw this form was
					//handed, and only when the value actually changed
					redraw.all();
				}
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
			}
		});
		paletteFilter.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				refreshPalette();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				refreshPalette();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
			}
		});
		paletteFilter.addFocusListener(new java.awt.event.FocusAdapter() {
			@Override
			public void focusGained(java.awt.event.FocusEvent e) {
				ensurePaletteLoaded();
			}
		});
		paletteList.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				ensurePaletteLoaded();
			}
		});
		paletteList.addListSelectionListener(new javax.swing.event.ListSelectionListener() {
			@Override
			public void valueChanged(javax.swing.event.ListSelectionEvent e) {
				if (!e.getValueIsAdjusting()) {
					previewPaletteSelection();
				}
			}
		});
	}

	/**
	 * Builds the session PropDatabase on the first palette interaction and
	 * fills the list. The build runs in a SwingWorker (it decompresses two
	 * whole GARCs) with the list showing "Loading..." so the EDT stays
	 * responsive; the list is populated in done(). No-op until the workspace
	 * archives are loaded.
	 */
	public void ensurePaletteLoaded() {
		if (PropDatabase.isBuilt(Workspace.session())) {
			if (paletteEntries.length == 0 && paletteFilter.getText().isEmpty()) {
				refreshPalette();
			}
			return;
		}
		if (paletteLoading) {
			return;
		}
		paletteLoading = true;
		DefaultListModel<String> loadingModel = new DefaultListModel<>();
		loadingModel.addElement("Loading prop database...");
		paletteEntries = new PropDatabase.PropModel[0];
		paletteList.setModel(loadingModel);
		new javax.swing.SwingWorker<PropDatabase, Void>() {
			@Override
			protected PropDatabase doInBackground() {
				return PropDatabase.get(Workspace.session());
			}

			@Override
			protected void done() {
				paletteLoading = false;
				PropDatabase db = null;
				try {
					db = get();
				} catch (Exception ex) {
					ex.printStackTrace();
				}
				if (db != null) {
					refreshPalette();
				} else {
					//no workspace - explain the empty list instead of staying silent
					DefaultListModel<String> listModel = new DefaultListModel<>();
					listModel.addElement("Palette requires a workspace (Options > Workspace settings)");
					paletteEntries = new PropDatabase.PropModel[0];
					paletteList.setModel(listModel);
				}
			}
		}.execute();
	}

	/**
	 * Refills the palette list from the database using the current search
	 * filter. Does nothing (and keeps the list empty) until the database has
	 * been built by ensurePaletteLoaded().
	 */
	//English search terms -> substrings of Game Freak's internal model names,
	//so "computer"/"statue"/"lighthouse" find the right props despite codenames.
	private static final String[][] SEARCH_ALIASES = {
		{"computer", "_bm_pc"}, {"healing", "_bm_pc"}, {"pokemon center", "_bm_pc"},
		{"statue", "monument"}, {"lighthouse", "toudai"}, {"coral", "kaisou"},
		{"boat", "boat"}, {"ship", "spship"}, {"submarine", "submarine"},
		{"tv", "_bm_tv"}, {"television", "_bm_tv"}, {"palm", "yashi"},
	};

	private void refreshPalette() {
		if (!PropDatabase.isBuilt(Workspace.session())) {
			return;
		}
		PropDatabase db = PropDatabase.get(Workspace.session());
		if (db == null) {
			return;
		}
		String filter = paletteFilter.getText().trim().toLowerCase();
		//expand the filter with any alias substrings whose English key matches
		List<String> extra = new ArrayList<>();
		if (!filter.isEmpty()) {
			for (String[] a : SEARCH_ALIASES) {
				if (a[0].contains(filter)) {
					extra.add(a[1]);
				}
			}
		}
		List<PropDatabase.PropModel> shown = new ArrayList<>();
		DefaultListModel<String> listModel = new DefaultListModel<>();
		for (PropDatabase.PropModel m : db.getNamedModels()) {
			String nl = m.name.toLowerCase();
			boolean match = filter.isEmpty() || nl.contains(filter);
			if (!match) {
				for (String s : extra) {
					if (nl.contains(s)) {
						match = true;
						break;
					}
				}
			}
			if (match) {
				shown.add(m);
				listModel.addElement(m.name + "  (model " + m.modelIndex + ", used in " + m.donorAreas.size() + " areas)");
			}
		}
		if (listModel.isEmpty()) {
			listModel.addElement("No match. Props are trees, doors, signs, statues, furniture...");
			listModel.addElement("Pokemon Centers, Marts and houses are part of the map, not props.");
		}
		paletteEntries = shown.toArray(new PropDatabase.PropModel[shown.size()]);
		paletteList.setModel(listModel);
	}

	/**
	 * Previews the selected palette model in the existing preview widget. The
	 * BCH is read straight from the BuildingModels GARC, textured with the
	 * current area's texture pack where possible.
	 */
	private void previewPaletteSelection() {
		int sel = paletteList.getSelectedIndex();
		if (sel < 0 || sel >= paletteEntries.length || Workspace.getArchive(ArchiveType.BUILDING_MODELS) == null) {
			return;
		}
		try {
			byte[] bch = PropDatabase.getSubfile(Workspace.getArchive(ArchiveType.BUILDING_MODELS).getDecompressedEntry(paletteEntries[sel].modelIndex), 0);
			if (!PropDatabase.isBCH(bch)) {
				return;
			}
			BCHFile bchf = new BCHFile(bch);
			if (bchf.models.isEmpty()) {
				return;
			}
			H3DModel m = bchf.models.get(0);
			m.setMaterialTextures(bchf.textures);
			if (propTextures != null) {
				m.setMaterialTextures(propTextures);
			}
			m.makeAllBOs();
			PropPreview.loadModel(m);
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	/**
	 * Places the selected palette prop at the viewport centre, auto-importing
	 * its registry entry from a donor area when this area does not have it.
	 * When the prop's textures are not in this area's texture pack (which
	 * would hardlock the game when the area loads), offers to import them
	 * from a donor area first; the prop is only placed when the textures are
	 * available.
	 */
	private void placeSelectedPaletteProp() {
		ensurePaletteLoaded();
		int sel = paletteList.getSelectedIndex();
		if (sel < 0 || sel >= paletteEntries.length) {
			ctrmap.Ui.message(this, "Select a prop in the palette list first.", "No prop selected", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		if (!loaded || props == null) {
			ctrmap.Ui.message(this, "Load a map through the Zone Loader first.", "No map loaded", JOptionPane.WARNING_MESSAGE);
			return;
		}
		if (reg == null) {
			ctrmap.Ui.message(this, "The prop palette needs an area prop registry.\nLoad a map through the Zone Loader first.", "No registry loaded", JOptionPane.WARNING_MESSAGE);
			return;
		}
		PropDatabase.PropModel pm = paletteEntries[sel];
		int uid = -1;
		for (ADPropRegistry.ADPropRegistryEntry e : reg.entries.values()) {
			if (e.model == pm.modelIndex) {
				uid = e.reference;
				break;
			}
		}
		if (uid == -1) {
			//model not in this area's registry - guard the textures, then auto-import a donor entry
			byte[] bch = PropDatabase.getSubfile(Workspace.getArchive(ArchiveType.BUILDING_MODELS).getDecompressedEntry(pm.modelIndex), 0);
			Set<String> available = new HashSet<>();
			if (propTextures != null) {
				for (H3DTexture t : propTextures) {
					available.add(t.textureName);
				}
			}
			List<String> missing = PropDatabase.getMissingTextureNames(bch, available);
			if (!missing.isEmpty() && !offerTextureImport(pm, missing)) {
				return; //user cancelled or the import failed - nothing was modified
			}
			ADPropRegistry.ADPropRegistryEntry imported = null;
			if (pm.template != null) {
				try {
					imported = new ADPropRegistry.ADPropRegistryEntry(new LittleEndianDataInputStream(new ByteArrayInputStream(pm.template)));
				} catch (IOException ex) {
					imported = null;
				}
			}
			if (imported == null) {
				imported = new ADPropRegistry.ADPropRegistryEntry(); //no donor anywhere - dummy entry, animations must be set in PRE
			}
			int ref = pm.modelIndex;
			while (reg.entries.containsKey(ref)) {
				ref++; //ref == model everywhere in vanilla; only user-made mismatched entries can collide
			}
			imported.reference = ref;
			imported.model = pm.modelIndex;
			imported.eventScr1 = 0;
			imported.eventScr2 = 0;
			reg.entries.put(imported.reference, imported);
			reg.modified = true;
			//register the model under the new reference the same way the
			//registry loader does, so rendering does not depend on the
			//uid==modelIndex failsafe (ref can differ from model on collision)
			try {
				BCHFile mdlBch = new BCHFile(new BM(Workspace.getWorkspaceFile(ArchiveType.BUILDING_MODELS, imported.model), Workspace.session()).getFile(0));
				if (!mdlBch.models.isEmpty()) {
					H3DModel mdl = mdlBch.models.get(0);
					mdl.setMaterialTextures(mdlBch.textures);
					if (propTextures != null) {
						mdl.setMaterialTextures(propTextures);
					}
					mdl.makeAllBOs();
					reg.models.put(imported.reference, mdl);
				}
			} catch (Exception ex) {
				ex.printStackTrace();
			}
			uid = imported.reference;
		}
		//mirror of the New entry flow, with the palette model's UID
		loaded = false;
		GRProp newProp = new GRProp();
		newProp.uid = uid;
		Point defaultPos = map.getWorldLocAtViewportCentre();
		newProp.x = defaultPos.x;
		newProp.z = defaultPos.y;
		newProp.updateName(reg, Workspace.session());
		props.props.add(newProp);
		models.add(reg.getModel(uid));
		entryBox.addItem(String.valueOf(props.props.size() - 1));
		loaded = true;
		setProp(entryBox.getItemCount() - 1);
		props.modified = true;
	}

	/**
	 * Offers to import the given missing prop textures from the best donor
	 * area into this area's texture pack (AD subfile 1) and performs the
	 * import on OK. All-or-nothing: the merged pack bytes are fully built and
	 * verified in memory before the single storeFile call, so a failure can
	 * never leave a half-imported pack. On success the imported textures are
	 * appended to the live propTextures list (shared with the ZoneHeader) so
	 * the prop renders immediately.
	 *
	 * @return true when the textures are available afterwards, false when the
	 * user cancelled or the import failed (nothing was modified either way)
	 */
	/**
	 * The refusal an area other zones use earns before the import is offered,
	 * so the user is not talked into one the shared-area rule then turns down.
	 * True when it was refused - and the user told which maps stood in the way.
	 *
	 * <p>It is a method of its own because the branch IS the guard, and nothing
	 * could reach it where it stood: three modal dialogs lead here, and a
	 * headless suite gets a HeadlessException from the first of them, so
	 * inverting this test left the whole battery green. Inverted, a prop grows
	 * an area fifteen other maps read - the finding this check exists for - and
	 * every prop on a zone's OWN area is refused with a message naming nobody.
	 */
	public static boolean refuseSharedArea(java.awt.Component parent, int areaId, int zoneIndex,
			String areaName, String inlineTextures) {
		String shared = BchTexturePack.zonesUsingArea(Workspace.session(), areaId, zoneIndex);
		if (shared != null) {
			ctrmap.Ui.error(parent,
					"This prop needs textures this area does not have (" + inlineTextures + "),\n"
					+ "but " + areaName + " is also used by " + shared + ".\n"
					+ "Importing them would change those maps too, so the prop was not placed.\n\n"
					+ "Give this zone its own area first (Map > Fork map geometry).",
					"Shared area");
			return true;
		}
		return false;
	}

	private boolean offerTextureImport(PropDatabase.PropModel pm, List<String> missing) {
		StringBuilder list = new StringBuilder();
		StringBuilder inline = new StringBuilder();
		for (String tex : missing) {
			list.append("\n    ").append(tex);
			if (inline.length() > 0) {
				inline.append(", ");
			}
			inline.append(tex);
		}
		PropDatabase db = PropDatabase.get(Workspace.session());
		ZoneHeader header = loadedZone.isOpen() ? loadedZone.open().header : null;
		if (db == null || header == null || header.areadata == null) {
			ctrmap.Ui.error(this,
					"This prop needs textures that this area does not have:" + list
					+ "\n\nPlacing it anyway would hardlock the game when the area loads,"
					+ "\nand no area data is loaded to import them into, so the prop was not placed.",
					"Missing textures");
			return false;
		}
		int donorArea = db.findDonorAreaWithTextures(pm, missing);
		if (donorArea == -1) {
			ctrmap.Ui.error(this,
					"This prop needs textures that this area does not have:" + list
					+ "\n\nPlacing it anyway would hardlock the game when the area loads."
					+ "\nNo single donor area contains all of these textures, so CTRMap"
					+ "\ncan not import them automatically and the prop was not placed.",
					"Missing textures");
			return false;
		}
		//An area other zones use is not this prop's to grow. Asked BEFORE the
		//import dialog, so the user is not talked into an import that the
		//shared-area rule then refuses.
		if (refuseSharedArea(this, header.areadataID,
				loadedZone.index(),
				getAreaDisplayName(header.areadataID), inline.toString())) {
			return false;
		}
		int rsl = ctrmap.Ui.confirm(this,
				"This prop's textures (" + inline + ") are not in this area.\n"
				+ "Import them from " + getAreaDisplayName(donorArea) + "?\n\n"
				+ "This modifies the area's texture data.",
				"Missing textures", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (rsl != JOptionPane.OK_OPTION) {
			return false;
		}
		try {
			byte[] targetPack = header.areadata.getFile(1);
			//read the donor from the same on-disk state the game will see: the
			//workspace file when one exists (it carries earlier in-session
			//imports that the packed GARC does not have yet), else the GARC
			byte[] donorPack;
			File donorWs = new File(Workspace.getExtractionDirectory(ArchiveType.AREA_DATA), String.valueOf(donorArea));
			if (donorWs.exists()) {
				donorPack = PropDatabase.getSubfile(java.nio.file.Files.readAllBytes(donorWs.toPath()), 1);
			} else {
				donorPack = PropDatabase.getSubfile(Workspace.getArchive(ArchiveType.AREA_DATA).getDecompressedEntry(donorArea), 1);
			}
			//verify against the actual bytes that will be read - the database's
			//name-sets can be ahead of disk after earlier in-session imports
			if (donorPack == null || !PropDatabase.getTexturePackTextureNames(donorPack).containsAll(missing)) {
				throw new IllegalStateException("donor area " + donorArea + " does not actually contain all the needed textures on disk");
			}
			//a name the area already holds under other pixels is not "missing",
			//so it is never imported - the prop just draws the area's version.
			//Say which, before anything is written.
			for (String c : BchTexturePack.clashesWith(header.areadata.getFile(11), targetPack, donorPack,
					new ArrayList<>(PropDatabase.getMaterialTextureNames(
							PropDatabase.getSubfile(Workspace.getArchive(ArchiveType.BUILDING_MODELS).getDecompressedEntry(pm.modelIndex), 0))))) {
				ctrmap.Ui.message(this, "This prop will draw the area's " + c + ", not its own - the two textures share a name and differ.",
						"Prop textures", JOptionPane.INFORMATION_MESSAGE);
			}
			byte[] merged = BchTexturePack.importIntoArea(Workspace.session(), header.areadataID,
					loadedZone.index(),
					targetPack, donorPack, missing);
			if (merged != targetPack) { //already-present names are a no-op (same array returned)
				//decode and verify the merged pack BEFORE storing anything
				BCHFile packBch = new BCHFile(merged);
				if (packBch.errorlevel != 0) {
					throw new IllegalStateException("merged texture pack failed verification (errorlevel " + packBch.errorlevel + ")");
				}
				List<H3DTexture> imported = new ArrayList<>();
				for (H3DTexture t : packBch.textures) {
					if (missing.contains(t.textureName)) {
						imported.add(t);
					}
				}
				if (imported.size() != missing.size()) {
					throw new IllegalStateException("merged texture pack is missing " + (missing.size() - imported.size()) + " of the imported textures");
				}
				//the single write: the workspace AD file, persisted by storeFile itself
				try {
					header.areadata.storeFile(1, merged);
				} catch (RuntimeException notWritten) {
					throw new IOException("could not write the merged texture pack to the workspace area"
						+ " data file: " + ctrmap.util.Bytes.reason(notWritten), notWritten);
				}
				//minimal in-memory refresh instead of a zone reload
				if (propTextures != null) {
					propTextures.addAll(imported);
				}
				db.registerImportedTextures(header.areadataID, missing);
			}
			return true;
		} catch (Exception ex) {
			ex.printStackTrace();
			ctrmap.Ui.error(this,
					"The texture import failed:\n" + ex
					+ "\n\nThe area was not modified and the prop was not placed.",
					"Import failed");
			return false;
		}
	}

	/**
	 * Human-readable label for an AreaData index: the location name of the
	 * first zone that uses the area, plus the raw index.
	 */
	private String getAreaDisplayName(int area) {
		try {
			if (loadedZone.count() > 0) {
				for (int i = 0; i < loadedZone.count(); i++) {
					Zone z = loadedZone.at(i);
					if (z != null && z.header != null && z.header.areadataID == area) {
						return LocationNames.getLocName(z.header.parentMap) + " (area " + area + ")";
					}
				}
			}
		} catch (Exception ex) {
			//no location names available - fall through to the raw index
		}
		return "area " + area;
	}

	public void setFloatValueClass(JFormattedTextField[] fields) {
		for (int i = 0; i < fields.length; i++) {
			((NumberFormatter) fields[i].getFormatter()).setValueClass(Float.class);
		}
	}

	public void loadDataFile(GR f, List<H3DTexture> propTextures) {
		gr = f;
		props = new GRPropData(gr);
		loadDataFile(props, null, propTextures);
	}

	public void loadDataFile(GRPropData f, ADPropRegistry reg, List<H3DTexture> propTextures) {
		saidAShowFailed = false;   //a new region gets its own first report
		this.propTextures = propTextures;
		models.clear();
		this.reg = reg;
		prop = null;
		loaded = false;
		props = f;
		for (int i = 0; i < props.props.size(); i++) {
			props.props.get(i).updateName(reg, Workspace.session()); //even if reg is null, the method handles it and (inaccurately) assigns the name by UID
			if (reg != null) {
				H3DModel model = reg.getModel(props.props.get(i).uid);
				models.add(model);
			}
			updateH3D(i);
		}
		entryBox.removeAllItems();
		for (int i = 0; i < props.props.size(); i++) {
			entryBox.addItem(String.valueOf(i));
		}
		loaded = true;
		if (entryBox.getItemCount() > 0) {
			showProp(0);
		}
	}

	public void unload() {
		loaded = false;
		propIndex = -1;
		prop = null;
		props = null;
		models.clear();
		reg = null;
		regentry = null;
		entryBox.setSelectedIndex(-1);
		entryBox.removeAllItems();
	}

	public void updateH3D(int index) {
		if (index >= models.size()) {
			return;
		}
		H3DModel m = models.get(index);
		GRProp p = props.props.get(index);
		if (m == null || p == null) {
			return;
		}
		m.worldLocX = p.x;
		m.worldLocY = p.y;
		m.worldLocZ = p.z;
		m.scaleX = p.scaleX;
		m.scaleY = p.scaleY;
		m.scaleZ = p.scaleZ;
		m.rotationX = p.rotateX;
		m.rotationY = p.rotateY;
		m.rotationZ = p.rotateZ;
		//the record moved, so the gizmo re-reads its position from it
		navi.resync();
	}

	public void setProp(int index) {
		entryBox.setSelectedIndex(index);
	}

	public void saveProp() {
		if (prop == null) {
			return;
		}
		GRProp prop2 = new GRProp();
		prop2.uid = (Integer) mdlNum.getValue();
		prop2.updateName(reg, Workspace.session());
		prop2.x = ctrmap.humaninterface.Forms.getFloatFromDocument(x);
		prop2.y = ctrmap.humaninterface.Forms.getFloatFromDocument(y);
		prop2.z = ctrmap.humaninterface.Forms.getFloatFromDocument(z);
		prop2.rotateX = ctrmap.humaninterface.Forms.getFloatFromDocument(rx);
		prop2.rotateY = ctrmap.humaninterface.Forms.getFloatFromDocument(ry);
		prop2.rotateZ = ctrmap.humaninterface.Forms.getFloatFromDocument(rz);
		prop2.scaleX = ctrmap.humaninterface.Forms.getFloatFromDocument(sx);
		prop2.scaleY = ctrmap.humaninterface.Forms.getFloatFromDocument(sy);
		prop2.scaleZ = ctrmap.humaninterface.Forms.getFloatFromDocument(sz);
		if (!equalsData(prop, prop2)) {
			int idx = props.props.indexOf(prop);
			prop = prop2;
			props.props.set(idx, prop);
			props.modified = true;
			updateH3D(idx);
			//as the three coordinate listeners in the constructor: the request goes to the
			//Redraw this form was handed, because the bare firePropertyChange reached
			//nobody. This one was already inside a "something actually changed" guard, so
			//only the receiver moves.
			redraw.all();
		}
	}

	public boolean store(boolean dialog) {
		if (props != null) {
			saveProp();
			if (props.modified) {
				switch (ctrmap.Ui.askToKeep(dialog, "Prop data")) {
					case SAVE:
						if (map.mm != null) {
							props.write(map.mm);
						} else if (gr != null) {
							props.write();
						} else {
							//ANSWERING "SAVE" AND WRITING NOTHING IS THE FAILURE THIS SWITCH
							//COULD STILL CAUSE. The two branches above are the only ways these
							//props reach a file: through the open matrix's regions, or through
							//the single GR this form was handed. With neither - which is what a
							//matrix load that failed part-way leaves behind, because awaitLoad
							//calls unload() and that sets mm to null while the prop data from the
							//previous zone stays loaded here - the switch fell straight through to
							//"props.modified = false" below, so every prop the user had moved,
							//added or removed was discarded AND marked clean, with nothing said
							//anywhere: not a dialog, not a log line. Refusing keeps the edits (the
							//modified flag is never reached) and stops the save chain, which is
							//what every other refusal in this program already does.
							ctrmap.Ui.error(this, "The prop edits were NOT saved: there is no map"
									+ " open to write them into.\n\nThis happens after a map that failed"
									+ " to load. Open a zone or a GR map file again and the props will"
									+ " have somewhere to go.", "Prop data");
							return false;
						}
						break;
					case DISCARD:
						break;
					case CANCEL:
						return false;
				}
				props.modified = false;
			}
			if (reg != null && reg.modified) {
				switch (ctrmap.Ui.askToKeep(dialog, "Prop registry")) {
					case DISCARD:
						reg.modified = false;
						return true;
					case CANCEL:
						return false;
				}
				reg.write();
			}
		}
		return true;
	}

	@Override
	public void renderCM3D(GL2 gl) {
		if (reg != null) {
			for (int i = 0; i < models.size(); i++) {
				if (models.size() > i && models.get(i) != null) {
					updateH3D(i);
					models.get(i).render(gl);
				}
			}
		}
	}

	@Override
	public void renderOverlayCM3D(GL2 gl) {
		if (reg != null) {
			for (int i = 0; i < models.size(); i++) {
				if (models.size() > i && models.get(i) != null) {
					if (i == propIndex && tools.holding(PropTool.class)) {
						updateH3D(i);
						models.get(i).renderBox(gl);
					}
				}
			}
		}
	}

	@Override
	public void uploadBuffers(GL2 gl) {
		for (int i = 0; i < models.size(); i++) {
			if (models.get(i) != null) {
				models.get(i).uploadAllBOs(gl);
			}
		}
	}

	@Override
	public void deleteGLInstanceBuffers(GL2 gl) {
		for (int i = 0; i < models.size(); i++) {
			if (models.get(i) != null) {
				models.get(i).destroyAllBOs(gl);
			}
		}
	}

	public boolean equalsData(GRProp p1, GRProp p2) {
		if (p1.uid != p2.uid) {
			return false;
		}
		if (p1.x != p2.x) {
			return false;
		}
		if (p1.y != p2.y) {
			return false;
		}
		if (p1.z != p2.z) {
			return false;
		}
		if (p1.rotateY != p2.rotateY) {
			return false;
		}
		if (p1.rotateX != p2.rotateX) {
			return false;
		}
		if (p1.rotateZ != p2.rotateZ) {
			return false;
		}
		if (p1.scaleX != p2.scaleX) {
			return false;
		}
		if (p1.scaleY != p2.scaleY) {
			return false;
		}
		if (p1.scaleZ != p2.scaleZ) {
			return false;
		}
		return true;
	}

	public void showProp(int index) {
		try {
			propIndex = index;
			if (index == -1 || index >= props.props.size()) {
				prop = null;
				return;
			}
			prop = props.props.get(index);
			if (prop == null) {
				return;
			}
			loaded = false;
			mdlNum.setValue(prop.uid);
			prop.updateName(reg, Workspace.session());
			mdlName.setText(prop.name);
			x.setValue(prop.x);
			y.setValue(prop.y);
			z.setValue(prop.z);
			rx.setValue(prop.rotateX);
			ry.setValue(prop.rotateY);
			rz.setValue(prop.rotateZ);
			sx.setValue(prop.scaleX);
			sy.setValue(prop.scaleY);
			sz.setValue(prop.scaleZ);
			updateModel(index);
			//the gizmo follows the prop THIS CALL was asked to show. It used to
			//index the list a second time through the selection box - two sources
			//for one identity, so when they disagreed the gizmo stood over a
			//different prop than the form displayed, and when the box had no
			//selection it was get(-1), thrown and swallowed below
			navi.follow(prop);
			redraw.all();
			loaded = true;
		} catch (Exception failed) {
			//WHAT THIS USED TO DO. propIndex, prop and all twelve widgets already
			//carried the new prop; only redraw.all() and loaded = true were
			//skipped. So the form LOOKED like it had switched and was inert: with
			//loaded false the entry box stops switching props, Save is a no-op,
			//the coordinate fields stop writing through, and both prop tools stop
			//selecting and dragging. The user got an editor that responds to
			//nothing and says nothing, and the only trace was stderr.
			//Now it holds nothing, and says so once rather than once per drag.
			propIndex = -1;
			prop = null;
			loaded = false;
			if (!saidAShowFailed) {
				saidAShowFailed = true;
				ctrmap.Ui.error(this, "Prop " + index + " could not be shown:" + '\n' + failed
						+ '\n' + "The prop editor is holding nothing until another prop is picked.",
						"Prop editor");
			}
		}
	}



	public void updateModel(int index) {
		if (index > models.size() - 1) {
			//AND STOP. Without this return the method carried on into
			//models.set(index, ...) with the very index it had just decided was
			//past the end, threw IndexOutOfBounds, and the throw was swallowed by
			//showProp's catch-all - which is what left the form permanently inert.
			//Loading a null model is the whole of what this branch meant to do.
			PropPreview.loadModel(null);
			return;
		}
		if (reg != null) {
			models.set(index, reg.getModel(props.props.get(index).uid));
		}
		if (models.get(index) == null) {
			//failsafe - resolve the model through the registry entry when one
			//exists; only fall back to uid==modelIndex when there is none
			int mdlIndex = props.props.get(index).uid;
			if (reg != null && reg.entries.containsKey(mdlIndex)) {
				mdlIndex = reg.entries.get(mdlIndex).model;
			}
			File f = Workspace.getWorkspaceFile(ArchiveType.BUILDING_MODELS, mdlIndex);
			if (f.exists()) {
				BCHFile bch = new BCHFile(new BM(f, Workspace.session()).getFile(0));
				bch.models.get(0).setMaterialTextures(bch.textures);
				if (propTextures != null) {
					//A REGION OPENED ON ITS OWN HAS NONE. File > Open GR Mapfile passes
					//null here, and so does every suite that is not testing meshes;
					//setMaterialTextures then threw inside, straight into showProp's
					//catch-all, which is what left the whole prop editor inert. The
					//line above has already applied the model's own textures.
					bch.models.get(0).setMaterialTextures(propTextures);
				}
				bch.models.get(0).makeAllBOs();
				models.set(index, bch.models.get(0));
			}
		}
		PropPreview.loadModel(models.get(index));
	}

	public void saveAndRefresh() {
		if (loaded) {
			saveProp();
			showProp(entryBox.getSelectedIndex());
			if (reg != null && prop != null) {
				regentry = reg.entries.get(prop.uid);
				if (regentry == null) {
					//register automatically: retail template entry (carries the
					//prop's animation bindings) + auto-imported textures
					regentry = autoRegisterProp(prop.uid);
				}
			}
		}
	}

	/**
	 * Automatically registers a prop model in this area, the way retail data
	 * would have it: the registry entry is cloned from the model's RETAIL
	 * template (which carries the correct animation bindings - door open/close
	 * etc.), and any textures the area lacks are imported from the best donor
	 * area (a missing texture would hardlock the game). Only a model with no
	 * retail template anywhere falls back to a bare entry, with a note.
	 *
	 * @return the registered entry, or null after reporting what failed
	 */
	private ADPropRegistry.ADPropRegistryEntry autoRegisterProp(int uid) {
		try {
			PropDatabase db = PropDatabase.get(Workspace.session());
			PropDatabase.PropModel pm = db == null ? null : db.getModel(uid);
			ZoneHeader header = loadedZone.isOpen() ? loadedZone.open().header : null;
			//textures first - all-or-nothing before any registry write
			if (pm != null && Workspace.getArchive(ArchiveType.BUILDING_MODELS) != null) {
				java.util.Set<String> available = new java.util.HashSet<>();
				if (propTextures != null) {
					for (H3DTexture t : propTextures) {
						available.add(t.textureName);
					}
				}
				byte[] mdlBch = PropDatabase.getSubfile(Workspace.getArchive(ArchiveType.BUILDING_MODELS).getDecompressedEntry(uid), 0);
				java.util.List<String> missing = PropDatabase.getMissingTextureNames(mdlBch, available);
				if (!missing.isEmpty()) {
					int donorArea = (db != null && header != null && header.areadata != null)
							? db.findDonorAreaWithTextures(pm, missing) : -1;
					if (donorArea == -1) {
						ctrmap.Ui.error(this,
								"This prop needs textures this area does not have (" + missing + ")\n"
								+ "and no donor area contains them all - it was not registered, because\n"
								+ "placing it would hardlock the game when the area loads.",
								"Prop registration");
						return null;
					}
					byte[] targetPack = header.areadata.getFile(1);
					byte[] donorPack;
					File donorWs = new File(Workspace.getExtractionDirectory(ArchiveType.AREA_DATA), String.valueOf(donorArea));
					if (donorWs.exists()) {
						donorPack = PropDatabase.getSubfile(java.nio.file.Files.readAllBytes(donorWs.toPath()), 1);
					} else {
						donorPack = PropDatabase.getSubfile(Workspace.getArchive(ArchiveType.AREA_DATA).getDecompressedEntry(donorArea), 1);
					}
					for (String c : BchTexturePack.clashesWith(header.areadata.getFile(11), targetPack, donorPack,
							new ArrayList<>(PropDatabase.getMaterialTextureNames(mdlBch)))) {
						ctrmap.Ui.message(this, "This prop will draw the area's " + c + ", not its own - the two textures share a name and differ.",
								"Prop textures", JOptionPane.INFORMATION_MESSAGE);
					}
					byte[] merged = BchTexturePack.importIntoArea(Workspace.session(), header.areadataID,
							loadedZone.index(),
							targetPack, donorPack, missing);
					if (merged != targetPack) {
						BCHFile packBch = new BCHFile(merged);
						if (packBch.errorlevel != 0) {
							throw new IllegalStateException("the merged texture pack failed verification");
						}
						try {
							header.areadata.storeFile(1, merged);
						} catch (RuntimeException notWritten) {
							throw new IllegalStateException("could not write the area's texture pack: "
								+ ctrmap.util.Bytes.reason(notWritten), notWritten);
						}
						Workspace.addPersist(Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, header.areadataID));
						for (H3DTexture t : packBch.textures) {
							if (missing.contains(t.textureName) && propTextures != null) {
								propTextures.add(t);
							}
						}
					}
				}
			}
			//registry entry: the retail template when one exists (animations ride along)
			ADPropRegistry.ADPropRegistryEntry entry;
			boolean fromTemplate = pm != null && pm.template != null;
			if (fromTemplate) {
				entry = new ADPropRegistry.ADPropRegistryEntry(new LittleEndianDataInputStream(new ByteArrayInputStream(pm.template)));
			} else {
				entry = new ADPropRegistry.ADPropRegistryEntry();
			}
			entry.reference = uid;
			entry.model = uid;
			reg.entries.put(entry.reference, entry);
			reg.modified = true;
			if (!fromTemplate) {
				ctrmap.Ui.message(this,
						"Registered with a BARE entry: this model has no retail registry template\n"
						+ "anywhere (custom prop?), so its animation bindings could not be copied.\n"
						+ "Set them via \"[DANGER] Edit registry data\" if the prop should animate.",
						"Prop registration", JOptionPane.INFORMATION_MESSAGE);
			}
			return entry;
		} catch (Exception ex) {
			ctrmap.Ui.error(this, "Automatic prop registration failed:\n" + ex.getMessage(),
					"Prop registration");
			return null;
		}
	}

	/**
	 * This method is called from within the constructor to initialize the form.
	 * WARNING: Do NOT modify this code. The content of this method is always
	 * regenerated by the Form Editor.
	 */
	@SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        entryBox = new javax.swing.JComboBox<>();
        entryLabel = new javax.swing.JLabel();
        header1Separator = new javax.swing.JSeparator();
        mdlNumLabel = new javax.swing.JLabel();
        mdlNum = new javax.swing.JSpinner();
        headerSeparator = new javax.swing.JSeparator();
        locLabel = new javax.swing.JLabel();
        locXLabel = new javax.swing.JLabel();
        x = new javax.swing.JFormattedTextField();
        locYLabel = new javax.swing.JLabel();
        y = new javax.swing.JFormattedTextField();
        lozZLabel = new javax.swing.JLabel();
        z = new javax.swing.JFormattedTextField();
        scaleLabel = new javax.swing.JLabel();
        sx = new javax.swing.JFormattedTextField();
        sy = new javax.swing.JFormattedTextField();
        sz = new javax.swing.JFormattedTextField();
        scaleZLabel = new javax.swing.JLabel();
        scaleYLabel = new javax.swing.JLabel();
        scaleXLabel = new javax.swing.JLabel();
        rotLabel = new javax.swing.JLabel();
        rotXLabel = new javax.swing.JLabel();
        rx = new javax.swing.JFormattedTextField();
        rotYLabel = new javax.swing.JLabel();
        ry = new javax.swing.JFormattedTextField();
        rotZLabel = new javax.swing.JLabel();
        rz = new javax.swing.JFormattedTextField();
        mdlName = new javax.swing.JLabel();
        btnSave = new javax.swing.JButton();
        btnRemEntry = new javax.swing.JButton();
        btnNewEntry = new javax.swing.JButton();
        btnRegEdit = new javax.swing.JButton();
        PropPreview = new ctrmap.humaninterface.CustomH3DPreview();
        paletteLabel = new javax.swing.JLabel();
        paletteFilter = new javax.swing.JTextField();
        paletteScroll = new javax.swing.JScrollPane();
        paletteList = new javax.swing.JList<>();
        btnPalettePlace = new javax.swing.JButton();

        entryBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                entryBoxActionPerformed(evt);
            }
        });

        entryLabel.setText("Prop entry");

        header1Separator.setOrientation(javax.swing.SwingConstants.VERTICAL);

        mdlNumLabel.setText("UID");

        mdlNum.setModel(new javax.swing.SpinnerNumberModel(0, 0, 65535, 1));
        mdlNum.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                mdlNumStateChanged(evt);
            }
        });

        locLabel.setText("World location (3D floats)");

        locXLabel.setText("X");

        x.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0.00"))));

        locYLabel.setText("Y");

        y.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0.00"))));

        lozZLabel.setText("Z");

        z.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0.00"))));

        scaleLabel.setText("Scale (multiplication floats)");

        sx.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0.00"))));

        sy.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0.00"))));

        sz.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0.00"))));

        scaleZLabel.setText("Z");

        scaleYLabel.setText("Y");

        scaleXLabel.setText("X");

        rotLabel.setText("Rotation (angle floats)");

        rotXLabel.setText("X");

        rx.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0.00"))));

        rotYLabel.setText("Y");

        ry.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0.00"))));

        rotZLabel.setText("Z");

        rz.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0.00"))));

        mdlName.setText("Model name: -");

        btnSave.setText("Save");
        btnSave.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSaveActionPerformed(evt);
            }
        });

        btnRemEntry.setText("Remove entry");
        btnRemEntry.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnRemEntryActionPerformed(evt);
            }
        });

        btnNewEntry.setText("New entry");
        btnNewEntry.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnNewEntryActionPerformed(evt);
            }
        });

        btnRegEdit.setForeground(new java.awt.Color(255, 51, 51));
        btnRegEdit.setText("[DANGER] Edit registry data");
        btnRegEdit.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnRegEditActionPerformed(evt);
            }
        });

        paletteLabel.setText("Prop palette (search by name)");

        paletteList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        paletteScroll.setViewportView(paletteList);

        btnPalettePlace.setText("Place selected prop");
        btnPalettePlace.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnPalettePlaceActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout PropPreviewLayout = new javax.swing.GroupLayout(PropPreview);
        PropPreview.setLayout(PropPreviewLayout);
        PropPreviewLayout.setHorizontalGroup(
            PropPreviewLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 0, Short.MAX_VALUE)
        );
        PropPreviewLayout.setVerticalGroup(
            PropPreviewLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 100, Short.MAX_VALUE)
        );

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(headerSeparator)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(mdlName)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(mdlNumLabel)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(mdlNum, javax.swing.GroupLayout.PREFERRED_SIZE, 50, Short.MAX_VALUE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(header1Separator, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(entryLabel)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(entryBox, 0, 50, Short.MAX_VALUE)))
                        .addContainerGap())
                    .addGroup(layout.createSequentialGroup()
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                            .addComponent(scaleLabel)
                            .addComponent(rotLabel)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(rotZLabel)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(rz))
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(lozZLabel)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(z))
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(locYLabel)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(y))
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(locXLabel)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(x))
                            .addComponent(locLabel)
                            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                                .addComponent(scaleZLabel)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(sz))
                            .addGroup(layout.createSequentialGroup()
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(scaleYLabel)
                                    .addComponent(scaleXLabel))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(sx)
                                    .addComponent(sy)))
                            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(rotYLabel)
                                    .addComponent(rotXLabel))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(rx, javax.swing.GroupLayout.PREFERRED_SIZE, 163, javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addComponent(ry, javax.swing.GroupLayout.PREFERRED_SIZE, 163, javax.swing.GroupLayout.PREFERRED_SIZE)))
                            .addComponent(btnSave, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(btnRemEntry, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(btnNewEntry, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(btnRegEdit, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(PropPreview, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(paletteLabel)
                            .addComponent(paletteFilter)
                            .addComponent(paletteScroll, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(btnPalettePlace, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                        .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                        .addComponent(header1Separator, javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(javax.swing.GroupLayout.Alignment.LEADING, layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(mdlNumLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(mdlNum, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)))
                    .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                        .addComponent(entryLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addComponent(entryBox, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(mdlName)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(headerSeparator, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(locLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 14, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(locXLabel)
                    .addComponent(x, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(locYLabel)
                    .addComponent(y, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lozZLabel)
                    .addComponent(z, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(scaleLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 14, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(scaleXLabel)
                    .addComponent(sx, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(scaleYLabel)
                    .addComponent(sy, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(scaleZLabel)
                    .addComponent(sz, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(rotLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 14, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(rotXLabel)
                    .addComponent(rx, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(rotYLabel)
                    .addComponent(ry, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(rotZLabel)
                    .addComponent(rz, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(PropPreview, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addComponent(btnNewEntry)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnRemEntry)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnSave)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnRegEdit)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(paletteLabel)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(paletteFilter, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(paletteScroll, javax.swing.GroupLayout.PREFERRED_SIZE, 140, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnPalettePlace)
                .addContainerGap())
        );
    }// </editor-fold>//GEN-END:initComponents

    private void entryBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_entryBoxActionPerformed
		if (loaded && entryBox.getSelectedIndex() != -1) {
			saveAndRefresh();
		}
    }//GEN-LAST:event_entryBoxActionPerformed

    private void btnRegEditActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnRegEditActionPerformed
		if (regentry == null) {
			return;
		}
		ADPropRegistryEditor pre = new ADPropRegistryEditor(this);
		pre.loadRegistry(reg, propTextures);
		pre.setEntry(regentry.reference);
		//IN THE TOOL COLUMN, where the prop form itself is. It was a window of its own,
		//which is the habit this application is being cleared of - and a window meant it
		//could sit behind the editor with unsaved registry edits in it.
		toolPane.accept(pre);
    }//GEN-LAST:event_btnRegEditActionPerformed

    private void btnSaveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSaveActionPerformed
		saveAndRefresh();
    }//GEN-LAST:event_btnSaveActionPerformed

    private void btnRemEntryActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnRemEntryActionPerformed
		int removed = entryBox.getSelectedIndex();
		//THE BOX AND THE LIST MUST NOT DISAGREE WHILE A LISTENER IS RUNNING.
		//DefaultComboBoxModel.removeElementAt picks the replacement selection
		//BEFORE it removes the item, so the box fired its ActionEvent while it
		//still held both entries. Removing prop 0 of 2 therefore re-entered
		//showProp with index 1 against a props list already down to one prop:
		//showProp took its "no such prop" early return, left prop null and
		//propIndex past the end, and the corrective setSelectedIndex that used to
		//stand here was a no-op that fired nothing. What that cost: the form went
		//on displaying the prop that had just been deleted, the gizmo went on
		//following it, the surviving prop could not be picked from the box (it was
		//already the selection) and neither prop tool could reach it either, since
		//setProp is setSelectedIndex. It also ran saveProp against a prop already
		//removed from the list, which is props.props.set(-1, prop) whenever a
		//coordinate had been typed and not saved. Muting the box for the removal
		//and then showing the final selection once, by hand, is what btnNewEntry
		//already does for the other direction.
		loaded = false;
		models.remove(removed);
		props.props.remove(removed);
		entryBox.removeItemAt(removed);
		loaded = true;
		showProp(entryBox.getSelectedIndex());
		props.modified = true;
    }//GEN-LAST:event_btnRemEntryActionPerformed

    private void btnNewEntryActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnNewEntryActionPerformed
		loaded = false;
		GRProp newProp = new GRProp();
		newProp.uid = (prop != null) ? prop.uid : 0;
		Point defaultPos = map.getWorldLocAtViewportCentre();
		newProp.x = defaultPos.x;
		newProp.z = defaultPos.y;
		newProp.updateName(reg, Workspace.session());
		props.props.add(newProp);
		models.add(reg.getModel(newProp.uid));
		entryBox.addItem(String.valueOf(props.props.size() - 1));
		loaded = true;
		setProp(entryBox.getItemCount() - 1);
		props.modified = true;
    }//GEN-LAST:event_btnNewEntryActionPerformed

    private void mdlNumStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_mdlNumStateChanged
		saveAndRefresh();
    }//GEN-LAST:event_mdlNumStateChanged

    private void btnPalettePlaceActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnPalettePlaceActionPerformed
		placeSelectedPaletteProp();
    }//GEN-LAST:event_btnPalettePlaceActionPerformed


    // Variables declaration - do not modify//GEN-BEGIN:variables
    private ctrmap.humaninterface.CustomH3DPreview PropPreview;
    private javax.swing.JButton btnNewEntry;
    private javax.swing.JButton btnPalettePlace;
    private javax.swing.JButton btnRegEdit;
    private javax.swing.JButton btnRemEntry;
    private javax.swing.JButton btnSave;
    private javax.swing.JComboBox<String> entryBox;
    private javax.swing.JLabel entryLabel;
    private javax.swing.JSeparator header1Separator;
    private javax.swing.JSeparator headerSeparator;
    private javax.swing.JLabel locLabel;
    private javax.swing.JLabel locXLabel;
    private javax.swing.JLabel locYLabel;
    private javax.swing.JLabel lozZLabel;
    private javax.swing.JLabel mdlName;
    private javax.swing.JSpinner mdlNum;
    private javax.swing.JLabel mdlNumLabel;
    private javax.swing.JTextField paletteFilter;
    private javax.swing.JLabel paletteLabel;
    private javax.swing.JList<String> paletteList;
    private javax.swing.JScrollPane paletteScroll;
    private javax.swing.JLabel rotLabel;
    private javax.swing.JLabel rotXLabel;
    private javax.swing.JLabel rotYLabel;
    private javax.swing.JLabel rotZLabel;
    private javax.swing.JFormattedTextField rx;
    private javax.swing.JFormattedTextField ry;
    private javax.swing.JFormattedTextField rz;
    private javax.swing.JLabel scaleLabel;
    private javax.swing.JLabel scaleXLabel;
    private javax.swing.JLabel scaleYLabel;
    private javax.swing.JLabel scaleZLabel;
    private javax.swing.JFormattedTextField sx;
    private javax.swing.JFormattedTextField sy;
    private javax.swing.JFormattedTextField sz;
    private javax.swing.JFormattedTextField x;
    private javax.swing.JFormattedTextField y;
    private javax.swing.JFormattedTextField z;
    // End of variables declaration//GEN-END:variables

	@Override
	public void doSelectionLoop(MouseEvent e, Component parent, float[] mvMatrix, float[] projMatrix, int[] view, Vec3f cameraVec) {
		if (!tools.holding(PropTool.class)) {
			return;
		}
		GLUgl2 glu = new GLUgl2();
		double closestDist = Float.MAX_VALUE;
		int closestIdx = -1;
		for (int i = 0; i < models.size(); i++) {
			if (models.get(i) == null) {
				continue;
			}
			GRProp p = props.props.get(i);
			float[][] box = models.get(i).boxVectors;
			boolean sysout = (p.name.equals("t101_bm_trees"));
			if (ctrmap.humaninterface.Picking.isBoxSelected(box, e, parent, new Vec3f(p.x, p.y, p.z), new Vec3f(p.scaleX, p.scaleY, p.scaleZ), new Vec3f(p.rotateX, p.rotateY, p.rotateZ), mvMatrix, projMatrix, view)) {
				H3DModel m = models.get(i);
				//GLU is buggy and sometimes completely fucks up the maths in certain camera angles. We can work around this by checking if the actual object is seen by the camera.
				boolean allow = false;
				for (int mesh = 0; mesh < m.meshes.size(); mesh++) {
					for (int vertex = 0; vertex < m.meshes.get(mesh).vertices.size(); vertex++) {
						H3DVertex v = m.meshes.get(mesh).vertices.get(vertex);
						float[] test = new float[3];
						glu.gluProject(v.position.x + p.x, v.position.y + p.y, v.position.z + p.z, mvMatrix, 0, projMatrix, 0, view, 0, test, 0);
						if (test[0] > 0 && test[0] < parent.getWidth() && test[1] > 0 && test[1] < parent.getHeight()) {
							allow = true;
							break;
						}
					}
					if (allow) {
						break;
					}
				}
				if (!allow) {
					continue;
				}
				double dist = ctrmap.humaninterface.Picking.getDistanceFromVector(new Vec3f(p.x, p.y, p.z), cameraVec);
				if (Math.abs(dist) < closestDist && i != propIndex) {
					closestDist = Math.abs(dist);
					closestIdx = i;
				}
			}
		}
		if (closestIdx != -1) {
			setProp(closestIdx);
		}
	}
}
