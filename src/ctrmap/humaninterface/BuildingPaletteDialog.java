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

	/** Per-session caches: extraction and donor textures are dump-backed. */
	private static final Map<BuildingCatalog.Entry, MapPrefab> prefabCache = new HashMap<>();
	private static final Map<Integer, List<H3DTexture>> donorTexCache = new HashMap<>();

	public static MapPrefab cachedPrefab(BuildingCatalog.Entry e) {
		return prefabCache.computeIfAbsent(e, BuildingCatalog::extract);
	}

	/** The donor area's decoded textures (world pack file 11, prop pack file 1). */
	public static List<H3DTexture> donorTextures(int areaId) {
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
		final DefaultListModel<BuildingCatalog.Entry> model = new DefaultListModel<>();
		final JList<BuildingCatalog.Entry> list = new JList<>(model);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		Runnable refilter = () -> {
			String q = search.getText().trim().toLowerCase();
			model.clear();
			for (BuildingCatalog.Entry e : all) {
				if (q.isEmpty() || e.name.toLowerCase().contains(q) || e.kind.toLowerCase().contains(q)) {
					model.addElement(e);
				}
			}
			if (!model.isEmpty()) {
				list.setSelectedIndex(0);
			}
		};
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
		left.add(search, BorderLayout.NORTH);
		JScrollPane sp = new JScrollPane(list);
		sp.setPreferredSize(new Dimension(280, 420));
		left.add(sp, BorderLayout.CENTER);
		left.add(new JLabel("<html><small>Type to search: pokemon, mart, house, sign, tree...</small></html>"), BorderLayout.SOUTH);
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
						MapPrefab.StampResult r = p.stampGeometry(base, ax, ay, 0f);
						previewModel = r.newModel;
						if (baseTextures != null) {
							texes.addAll(baseTextures);
						}
						texes.addAll(donorTextures(e.donorArea));
						note = "  " + e.name + " - " + e.tilesW() + "x" + e.tilesH() + " tiles"
								+ (e.enterable() ? ", enterable (door + interior wiring on Apply)" : "")
								+ (r.stamped.isEmpty() ? "  [nothing stamped!]" : "");
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
					info.setText(fnote);
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
