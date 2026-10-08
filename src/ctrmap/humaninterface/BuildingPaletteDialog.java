package ctrmap.humaninterface;

import ctrmap.Ui;
import ctrmap.Workspace;
import ctrmap.formats.GameCache;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.BuildingCatalog;
import ctrmap.formats.h3d.MapPrefab;
import ctrmap.formats.h3d.texturing.H3DTexture;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TerrainLighting;
import ctrmap.formats.tilemap.TilePalette;
import ctrmap.gamedef.ArchiveType;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

/**
 * The BUILDING PALETTE: pick a Pokemon Center, Mart, house, sign, tree...
 * from a named list with a live 3D preview, then place it on the tile
 * painter's grid with one click. Entries come from the mined catalog
 * ({@link BuildingCatalog}); the geometry is cut from the user's own pristine
 * dump on selection, stamped onto a grass base and rendered with the donor
 * area's real textures - so the preview IS the thing that will be placed.
 */
public class BuildingPaletteDialog {

	/**
	 * The cut prefabs and the donor areas' textures, kept by the game they were cut from (F218).
	 * They were static maps keyed to nothing, so after repointing to another game the palette
	 * offered - and Apply stamped - the first game's cut geometry and textures into the second.
	 * Both loaders are synchronized - the palette preview thread and the Map Builder's
	 * live-regen worker hit them concurrently, and the extraction writes fixed-named temp files.
	 */
	private static final GameCache.Key<Map<BuildingCatalog.Entry, MapPrefab>> PREFABS
			= new GameCache.Key<>("building palette: cut prefabs");
	private static final GameCache.Key<Map<Integer, List<H3DTexture>>> DONOR_TEXTURES
			= new GameCache.Key<>("building palette: donor area textures");

	/** This entry cut from the open game's pristine copy, kept for that game; null with no game open or nothing to cut. */
	public static synchronized MapPrefab cachedPrefab(BuildingCatalog.Entry e) {
		ctrmap.WorkspaceSession game = Workspace.session();
		if (game == null) {
			return null;   //nothing to cut from, as BuildingCatalog.extract answers for no game
		}
		Map<BuildingCatalog.Entry, MapPrefab> prefabs = game.cache().get(PREFABS, HashMap::new);
		//bounded: the harvested catalog holds thousands of entries - browsing
		//must not accumulate every cut prefab in memory
		if (prefabs.size() > 64 && !prefabs.containsKey(e)) {
			prefabs.clear();
		}
		return prefabs.computeIfAbsent(e, k -> BuildingCatalog.extract(game, k));
	}

	/**
	 * What a cut actually brings, shown before it is placed: a harvested name
	 * describes only what dominates the cut, so a "structure" can be a whole
	 * furnished room - 27 pieces, 27 textures to import - and a "bridge" a chunk
	 * of Sky Pillar reaching 464 units up. The numbers alone could not tell that
	 * room from a large lamp, so the passengers - the floors, the shadow decal,
	 * a sea plane under a pier - are named by class. {@code r} is the preview
	 * stamp, or null when only the cut itself is known.
	 */
	public static String manifest(MapPrefab p, MapPrefab.StampResult r, BuildingCatalog.Entry e) {
		String riders = p.passengerNote();
		return p.summary(e.baseY)
				+ (r == null || r.newMaterials.isEmpty() ? "" : ", " + r.newMaterials.size() + " new material(s) + "
				+ r.texturesNeeded.size() + " texture(s) to import")
				+ (p.collTris.isEmpty() ? ", no collision" : ", " + p.collTris.size() + " collision triangle(s)")
				+ (riders.isEmpty() ? "" : ", rides along: " + riders);
	}

	/** What the palette hands back: the entry, and whether its passengers come along. */
	public static final class Pick {

		public final BuildingCatalog.Entry entry;
		public final boolean passengers;

		Pick(BuildingCatalog.Entry entry, boolean passengers) {
			this.entry = entry;
			this.passengers = passengers;
		}
	}

	/** Human category for the filter dropdown (curated kinds + harvested A_*). */
	static String categoryLabel(BuildingCatalog.Entry e) {
		switch (e.kind) {
			case "A_TREE": return "Trees & plants";
			case "A_ROCK": return "Rocks & cliff pieces";
			case "A_SIGN": return "Signs";
			case "A_FENCE": return "Fences";
			case "A_LAMP": return "Lamps & lights";
			case "A_STAIRS": return "Stairs";
			case "A_BRIDGE": return "Bridges";
			case "A_BUILDING": return "Buildings";
			case "A_DECOR": return "Small decor";
			case "A_STRUCT": return "Structures";
			default:
				break;
		}
		String k = e.kind.toUpperCase();
		if (k.contains("TREE") || k.contains("BUSH")) {
			return "Trees & plants";
		}
		if (k.contains("SIGN")) {
			return "Signs";
		}
		if (k.contains("FENCE")) {
			return "Fences";
		}
		return "Buildings";
	}

	/**
	 * The donor area's decoded textures (world pack file 11, prop pack file 1). THROWS when
	 * they cannot be read, and a failure is not cached (F211): both the read and each pack's
	 * parse were swallowed, the empty answer was cached for the session, and every building
	 * from that area drew untextured in the palette and the painter's preview without a word.
	 */
	public static synchronized List<H3DTexture> donorTextures(int areaId) throws Exception {
		ctrmap.WorkspaceSession session = Workspace.session();
		if (session == null) {
			throw new java.io.IOException("no workspace is open, so there is no untouched game to take textures from");
		}
		Map<Integer, List<H3DTexture>> kept = session.cache().get(DONOR_TEXTURES, HashMap::new);
		List<H3DTexture> cached = kept.get(areaId);
		if (cached != null) {
			return cached;
		}
		List<H3DTexture> out = new ArrayList<>();
		//NEVER THE LIVE GAME (F232). With no snapshot this read the game folder, so the textures stamped
		//into a new building came from the user's own edits - the contamination the snapshot exists to
		//prevent. The one pristine reader answers the snapshot, else the sealed backup, else nothing.
		File garcFile = session.pristineArchive(ArchiveType.AREA_DATA);
		if (garcFile == null) {
			throw new java.io.IOException("this workspace has no untouched copy of the area archive - no pristine"
					+ " snapshot and no sealed backup - so its textures would come from your edited game."
					+ " Reopen the workspace to take the snapshot, or seal a backup under Options.");
		}
		byte[] entry = new ctrmap.formats.garc.GARC(garcFile).getDecompressedEntry(areaId);
		File tmp = new File(session.scratch(), "bcat_area_" + areaId);
		try (java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp)) {
			fo.write(entry);
		}
		ctrmap.formats.containers.AD ad = new ctrmap.formats.containers.AD(tmp, session);
		for (int sub : new int[]{11, 1}) {
			byte[] pack = ad.getFile(sub);
			if (pack != null && pack.length > 0x44) {
				try {
					out.addAll(new BCHFile(pack).textures);
				} catch (RuntimeException ex) {
					throw new java.io.IOException("its texture pack (file " + sub + ") could not be read: "
							+ ctrmap.Ui.reason(ex), ex);
				}
			}
		}
		kept.put(areaId, out);
		return out;
	}

	/** Whether this session has said what the catalog could not read. */
	private static boolean catalogProblemsSaid;

	/**
	 * Shows the palette; returns the choice or null. {@code baseDonor} is the
	 * current zone's region model (the grass base the preview stamps onto).
	 */
	public static Pick pick(Dialog parent, byte[] baseDonor, List<H3DTexture> baseTextures) {
		List<BuildingCatalog.Entry> all = BuildingCatalog.entries();
		final Pick[] result = {null};
		final JDialog dlg = new JDialog(parent, "Building palette", true);
		dlg.setLayout(new BorderLayout(8, 8));
		((JPanel) dlg.getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		if (all.isEmpty()) {
			ctrmap.Ui.error(parent,
					"The building catalog is empty - this build shipped without oras_buildings.tsv.",
					"Building palette");
			return null;
		}
		//WHAT THE CATALOG COULD NOT READ IS SAID, once (F067): it went to stderr
		if (!catalogProblemsSaid && !BuildingCatalog.loadProblems().isEmpty()) {
			catalogProblemsSaid = true;
			List<String> p = BuildingCatalog.loadProblems();
			ctrmap.Ui.message(parent, "The building catalog skipped " + p.size() + " row(s) it could not read; the"
					+ " rest are offered:\n" + String.join("\n", p.subList(0, Math.min(5, p.size())))
					+ (p.size() > 5 ? "\n..." : ""), "Building palette", javax.swing.JOptionPane.WARNING_MESSAGE);
		}

		final JTextField search = new JTextField();
		//category + location filters make the harvested game-wide catalog
		//browsable: thousands of entries, narrowed in two clicks
		final java.util.LinkedHashSet<String> cats = new java.util.LinkedHashSet<>();
		final java.util.TreeSet<String> locs = new java.util.TreeSet<>();
		for (BuildingCatalog.Entry e : all) {
			cats.add(categoryLabel(e));
			if (!e.location.isEmpty()) {
				locs.add(e.location);
			}
		}
		final javax.swing.JComboBox<String> catBox = new javax.swing.JComboBox<>();
		catBox.addItem("All types");
		for (String c : cats) {
			catBox.addItem(c);
		}
		final javax.swing.JComboBox<String> locBox = new javax.swing.JComboBox<>();
		locBox.addItem("Everywhere");
		for (String l : locs) {
			locBox.addItem(l);
		}
		final DefaultListModel<BuildingCatalog.Entry> model = new DefaultListModel<>();
		final JList<BuildingCatalog.Entry> list = new JList<>(model);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
			@Override
			public java.awt.Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
				JLabel lbl = (JLabel) super.getListCellRendererComponent(l, v, i, sel, foc);
				BuildingCatalog.Entry en = (BuildingCatalog.Entry) v;
				lbl.setText("<html>" + en.name + " <small>(" + en.tilesW() + "x" + en.tilesH()
						+ (en.enterable() ? ", enterable" : "")
						+ (en.retailCount > 1 ? ", x" + en.retailCount + " in game" : "") + ")</small></html>");
				return lbl;
			}
		});
		Runnable refilter = () -> {
			String q = search.getText().trim().toLowerCase();
			String cat = catBox.getSelectedIndex() > 0 ? (String) catBox.getSelectedItem() : null;
			String loc = locBox.getSelectedIndex() > 0 ? (String) locBox.getSelectedItem() : null;
			model.clear();
			for (BuildingCatalog.Entry e : all) {
				if (cat != null && !categoryLabel(e).equals(cat)) {
					continue;
				}
				if (loc != null && !e.location.equals(loc)) {
					continue;
				}
				if (q.isEmpty() || e.name.toLowerCase().contains(q) || e.kind.toLowerCase().contains(q)
						|| e.location.toLowerCase().contains(q)) {
					model.addElement(e);
				}
			}
			if (!model.isEmpty()) {
				list.setSelectedIndex(0);
			}
		};
		catBox.addActionListener(e -> refilter.run());
		locBox.addActionListener(e -> refilter.run());
		search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
			public void insertUpdate(javax.swing.event.DocumentEvent e) {
				refilter.run();
			}

			public void removeUpdate(javax.swing.event.DocumentEvent e) {
				refilter.run();
			}

			public void changedUpdate(javax.swing.event.DocumentEvent e) {
				refilter.run();
			}
		});

		JPanel left = new JPanel(new BorderLayout(4, 4));
		JPanel filters = new JPanel(new java.awt.GridLayout(3, 1, 0, 2));
		filters.add(search);
		filters.add(catBox);
		filters.add(locBox);
		left.add(filters, BorderLayout.NORTH);
		JScrollPane sp = new JScrollPane(list);
		sp.setPreferredSize(new Dimension(300, 420));
		left.add(sp, BorderLayout.CENTER);
		left.add(new JLabel("<html><small>The whole game's structures are here - search, or narrow by<br>type and place. Curated entries (with door wiring) list first.</small></html>"), BorderLayout.SOUTH);
		dlg.add(left, BorderLayout.WEST);

		final MapPreview3D view = new MapPreview3D();
		view.setPreferredSize(new Dimension(520, 420));
		final JLabel info = new JLabel(" ");
		final javax.swing.JCheckBox leave = new javax.swing.JCheckBox(
				"Leave the passengers behind (sea planes, shadow decals, floors, terrain chips)");
		leave.setToolTipText("The structure's collision and movement tiles come along either way.");
		JPanel under = new JPanel(new BorderLayout());
		under.add(info, BorderLayout.NORTH);
		under.add(leave, BorderLayout.SOUTH);
		JPanel center = new JPanel(new BorderLayout(4, 4));
		center.add(view, BorderLayout.CENTER);
		center.add(under, BorderLayout.SOUTH);
		dlg.add(center, BorderLayout.CENTER);

		//ONE WORKER, NEWEST SELECTION WINS (F206): a row arrowed past before its turn is never cut
		//or built, and the grass every preview stands on is built once, on the worker, not per row
		final NewestOnly previews = new NewestOnly("building-preview");
		final byte[][] grassBase = {null};
		list.addListSelectionListener(ev -> {
			if (ev.getValueIsAdjusting()) {
				return;
			}
			final BuildingCatalog.Entry e = list.getSelectedValue();
			if (e == null) {
				return;
			}
			info.setText("  Loading preview: " + e.name + "...");
			previews.ask(ticket -> {
				Preview out = new Preview();
				try {
					MapPrefab p = cachedPrefab(e);
					if (p == null) {
						out.note = "  Could not cut this piece from the dump (see log).";
					} else if (ticket.current()) {
						if (grassBase[0] == null) {
							TilePalette[][] grass = new TilePalette[40][40];
							for (TilePalette[] row : grass) {
								java.util.Arrays.fill(row, TilePalette.GRASS);
							}
							grassBase[0] = PaintedRegionBuilder.build(Workspace.session(), baseDonor, grass, null, null,
									TerrainLighting.daytime(), false).model;
						}
						int ax = 20 - e.tilesW() / 2, ay = 20 - e.tilesH() / 2;
						MapPrefab.StampResult r = p.stampGeometry(grassBase[0], ax, ay, -e.baseY);
						out.model = r.newModel;
						if (baseTextures != null) {
							out.textures.addAll(baseTextures);
						}
						String untextured = "";
						try {
							out.textures.addAll(donorTextures(e.donorArea));
						} catch (Exception ex) {
							untextured = "  [shown untextured: donor area " + e.donorArea + "'s textures could not be"
									+ " read - " + Ui.reason(ex) + "]";
						}
						out.note = "  " + e.name + " - " + e.tilesW() + "x" + e.tilesH() + " tiles, " + manifest(p, r, e)
								+ (e.enterable() ? ", enterable (door + interior wiring on Apply)" : "")
								+ (r.missingMaterials.isEmpty() ? "" : "  [" + r.missingMaterials.size() + " of "
								+ p.pieces.size() + " piece(s) cannot be placed on this map: " + r.missingMaterials + "]")
								+ untextured;
					}
				} catch (Exception ex) {
					out.note = "  Preview failed: " + Ui.reason(ex);
				}
				return out;
			}, shown -> {
				if (shown.model != null) {
					view.setRegion(shown.model, shown.textures);
				}
				info.setText("<html>" + Ui.html(shown.note) + "</html>"); //wraps: the manifest outgrows one line
			}, null);
		});

		JPanel buttons = new JPanel();
		JButton place = new JButton("Place on the map");
		JButton cancel = new JButton("Cancel");
		buttons.add(place);
		buttons.add(cancel);
		dlg.add(buttons, BorderLayout.SOUTH);
		Runnable choose = () -> {
			BuildingCatalog.Entry chosen = list.getSelectedValue();
			result[0] = chosen == null ? null : new Pick(chosen, !leave.isSelected());
			dlg.dispose();
		};
		place.addActionListener(ev -> choose.run());
		cancel.addActionListener(ev -> dlg.dispose());
		list.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				if (e.getClickCount() == 2) {
					choose.run();
				}
			}
		});

		refilter.run();
		dlg.addWindowListener(new java.awt.event.WindowAdapter() {
			@Override
			public void windowClosing(java.awt.event.WindowEvent e) {
				view.stop();
			}
		});
		dlg.pack();
		dlg.setLocationRelativeTo(parent);
		dlg.setVisible(true);
		previews.close();
		view.stop();
		return result[0];
	}

	/** What the preview worker hands the event thread for one row. */
	private static final class Preview {

		String note = " ";
		byte[] model;
		final List<H3DTexture> textures = new ArrayList<>();
	}
}
