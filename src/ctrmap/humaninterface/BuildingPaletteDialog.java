package ctrmap.humaninterface;

import ctrmap.Workspace;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.BuildingCatalog;
import ctrmap.formats.h3d.MapPrefab;
import ctrmap.formats.h3d.texturing.H3DTexture;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TerrainLighting;
import ctrmap.formats.tilemap.TilePalette;
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

	/** Per-session caches: extraction and donor textures are dump-backed.
	 *  Both loaders are synchronized - the palette preview thread and the Map
	 *  Builder's live-regen worker hit them concurrently, and the extraction
	 *  writes fixed-named temp files. */
	private static final Map<BuildingCatalog.Entry, MapPrefab> prefabCache = new HashMap<>();
	private static final Map<Integer, List<H3DTexture>> donorTexCache = new HashMap<>();

	public static synchronized MapPrefab cachedPrefab(BuildingCatalog.Entry e) {
		//bounded: the harvested catalog holds thousands of entries - browsing
		//must not accumulate every cut prefab in memory
		if (prefabCache.size() > 64 && !prefabCache.containsKey(e)) {
			prefabCache.clear();
		}
		return prefabCache.computeIfAbsent(e, BuildingCatalog::extract);
	}

	/**
	 * What a cut actually brings, shown before it is placed: a harvested name
	 * describes only the dominant material, so "Littleroot Town lamp" is a whole
	 * furnished room - 27 pieces, 27 textures to import - and a "bridge" can be
	 * a chunk of Sky Pillar reaching 464 units up.
	 */
	public static String manifest(MapPrefab p, MapPrefab.StampResult r, BuildingCatalog.Entry e) {
		float[] span = p.heightSpan();
		return p.pieces.size() + " piece(s) / " + p.triangleCount() + " triangles"
				+ (r.newMaterials.isEmpty() ? "" : ", " + r.newMaterials.size() + " new material(s) + "
				+ r.texturesNeeded.size() + " texture(s) to import")
				+ (p.collTris.isEmpty() ? ", no collision" : ", " + p.collTris.size() + " collision triangle(s)")
				+ ", " + Math.round(span[0] - e.baseY) + ".." + Math.round(span[1] - e.baseY) + " above ground";
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

	/** The donor area's decoded textures (world pack file 11, prop pack file 1). */
	public static synchronized List<H3DTexture> donorTextures(int areaId) {
		return donorTexCache.computeIfAbsent(areaId, id -> {
			List<H3DTexture> out = new ArrayList<>();
			try {
				String rel = Workspace.getArchivePath(Workspace.ArchiveType.AREA_DATA, Workspace.game);
				File garcFile = new File(Workspace.originalSnapshotDir().getAbsolutePath() + rel);
				if (!garcFile.exists()) {
					garcFile = new File(Workspace.GAMEDIR_PATH + rel);
				}
				byte[] entry = new ctrmap.formats.garc.GARC(garcFile).getDecompressedEntry(id);
				File tmp = new File(Workspace.temp, "bcat_area_" + id);
				try (java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp)) {
					fo.write(entry);
				}
				ctrmap.formats.containers.AD ad = new ctrmap.formats.containers.AD(tmp);
				for (int sub : new int[]{11, 1}) {
					byte[] pack = ad.getFile(sub);
					if (pack != null && pack.length > 0x44) {
						try {
							BCHFile bch = new BCHFile(pack);
							out.addAll(bch.textures);
						} catch (RuntimeException ignore) {
						}
					}
				}
			} catch (Exception ex) {
				System.err.println("BuildingPalette: donor textures area " + id + ": " + ex);
			}
			return out;
		});
	}

	/**
	 * Shows the palette; returns the chosen entry or null. {@code baseDonor} is
	 * the current zone's region model (the grass base the preview stamps onto).
	 */
	public static BuildingCatalog.Entry pick(Dialog parent, byte[] baseDonor, List<H3DTexture> baseTextures) {
		List<BuildingCatalog.Entry> all = BuildingCatalog.entries();
		final BuildingCatalog.Entry[] result = {null};
		final JDialog dlg = new JDialog(parent, "Building palette", true);
		dlg.setLayout(new BorderLayout(8, 8));
		((JPanel) dlg.getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		if (all.isEmpty()) {
			javax.swing.JOptionPane.showMessageDialog(parent,
					"The building catalog is empty - this build shipped without oras_buildings.tsv.",
					"Building palette", javax.swing.JOptionPane.ERROR_MESSAGE);
			return null;
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
		JPanel center = new JPanel(new BorderLayout(4, 4));
		center.add(view, BorderLayout.CENTER);
		center.add(info, BorderLayout.SOUTH);
		dlg.add(center, BorderLayout.CENTER);

		final int[] previewSeq = {0};
		list.addListSelectionListener(ev -> {
			if (ev.getValueIsAdjusting()) {
				return;
			}
			final BuildingCatalog.Entry e = list.getSelectedValue();
			if (e == null) {
				return;
			}
			info.setText("  Loading preview: " + e.name + "...");
			final int seq = ++previewSeq[0];
			new Thread(() -> {
				String note;
				byte[] previewModel = null;
				List<H3DTexture> texes = new ArrayList<>();
				try {
					MapPrefab p = cachedPrefab(e);
					if (p == null) {
						note = "  Could not cut this piece from the dump (see log).";
					} else {
						TilePalette[][] grass = new TilePalette[40][40];
						for (TilePalette[] row : grass) {
							java.util.Arrays.fill(row, TilePalette.GRASS);
						}
						byte[] base = PaintedRegionBuilder.build(baseDonor, grass, null, null,
								TerrainLighting.daytime(), false).model;
						int ax = 20 - e.tilesW() / 2, ay = 20 - e.tilesH() / 2;
						MapPrefab.StampResult r = p.stampGeometry(base, ax, ay, -e.baseY);
						previewModel = r.newModel;
						if (baseTextures != null) {
							texes.addAll(baseTextures);
						}
						texes.addAll(donorTextures(e.donorArea));
						note = "  " + e.name + " - " + e.tilesW() + "x" + e.tilesH() + " tiles, " + manifest(p, r, e)
								+ (e.enterable() ? ", enterable (door + interior wiring on Apply)" : "")
								+ (r.missingMaterials.isEmpty() ? "" : "  [" + r.missingMaterials.size() + " of "
								+ p.pieces.size() + " piece(s) cannot be placed on this map: " + r.missingMaterials + "]");
					}
				} catch (Exception ex) {
					note = "  Preview failed: " + ex.getMessage();
				}
				final String fnote = note;
				final byte[] fmodel = previewModel;
				final List<H3DTexture> ftex = texes;
				SwingUtilities.invokeLater(() -> {
					if (seq != previewSeq[0]) {
						return; // a newer selection superseded this preview
					}
					if (fmodel != null) {
						view.setRegion(fmodel, ftex);
					}
					info.setText("<html>" + fnote + "</html>"); //wraps: the manifest outgrows one line
				});
			}, "building-preview").start();
		});

		JPanel buttons = new JPanel();
		JButton place = new JButton("Place on the map");
		JButton cancel = new JButton("Cancel");
		buttons.add(place);
		buttons.add(cancel);
		dlg.add(buttons, BorderLayout.SOUTH);
		place.addActionListener(ev -> {
			result[0] = list.getSelectedValue();
			dlg.dispose();
		});
		cancel.addActionListener(ev -> dlg.dispose());
		list.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				if (e.getClickCount() == 2) {
					result[0] = list.getSelectedValue();
					dlg.dispose();
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
		view.stop();
		return result[0];
	}
}
