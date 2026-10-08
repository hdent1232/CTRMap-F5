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
	 * The donor areas' textures, kept by the game they were read from (F218). They were a static map
	 * keyed to nothing, so after repointing to another game the palette offered - and Apply stamped -
	 * the first game's textures into the second. The loader is synchronized - the palette preview
	 * thread and the Map Builder's live-regen worker hit it concurrently, and it writes a fixed-named
	 * temp file. The cut prefabs are {@link BuildingCatalog#prefab}'s (F248).
	 */
	private static final GameCache.Key<Map<Integer, List<H3DTexture>>> DONOR_TEXTURES
			= new GameCache.Key<>("building palette: donor area textures");

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
	 * The entries a search and the two filters leave: of the type and place chosen (null for any), and
	 * answering {@link BuildingCatalog#matchesQuery} - every word in the name, kind, place or material,
	 * a size word ("11x9") naming the box (F251).
	 */
	public static List<BuildingCatalog.Entry> shown(List<BuildingCatalog.Entry> all, String query, String category,
			String location) {
		List<BuildingCatalog.Entry> out = new ArrayList<>();
		for (BuildingCatalog.Entry e : all) {
			if ((category == null || categoryLabel(e).equals(category)) && (location == null || e.location.equals(location))
					&& BuildingCatalog.matchesQuery(e, query)) {
				out.add(e);
			}
		}
		return out;
	}

	/** The heading the curated entries list under, open until it is closed. */
	public static final String CURATED = "Curated, with door wiring";

	/** One place's heading in the palette's list; a click opens or closes the entries under it (F251). */
	public static final class Heading {

		public final String title;
		public final int count;
		public final boolean open;

		Heading(String title, int count, boolean open) {
			this.title = title;
			this.count = count;
			this.open = open;
		}

		@Override
		public String toString() {
			return (open ? "▾ " : "▸ ") + title + " (" + count + ")";
		}
	}

	/**
	 * The palette's rows: the entries shown, each under one heading for its place - the curated entries
	 * first, under {@link #CURATED}, then the places in alphabetical order - and a closed heading's entries
	 * left out. The curated heading starts open and every place closed; with {@code everyOpen} - a search
	 * typed or a place chosen, asking to see what matched - every heading starts open. {@code flipped}
	 * names the headings clicked to the other state. The 3,535 harvested entries were one flat list, a
	 * house in it reached by arrowing through 2,130 "structure N" rows (F251).
	 */
	public static List<Object> rows(List<BuildingCatalog.Entry> shown, java.util.Set<String> flipped, boolean everyOpen) {
		Map<String, List<BuildingCatalog.Entry>> byPlace = new java.util.TreeMap<>();
		List<BuildingCatalog.Entry> curated = new ArrayList<>();
		for (BuildingCatalog.Entry e : shown) {
			if (!e.auto) {
				curated.add(e);
			} else {
				byPlace.computeIfAbsent(e.location.isEmpty() ? "Unknown place" : e.location, k -> new ArrayList<>()).add(e);
			}
		}
		List<Object> rows = new ArrayList<>();
		if (!curated.isEmpty()) {
			addGroup(rows, CURATED, curated, !flipped.contains(CURATED));
		}
		for (Map.Entry<String, List<BuildingCatalog.Entry>> place : byPlace.entrySet()) {
			addGroup(rows, place.getKey(), place.getValue(), everyOpen != flipped.contains(place.getKey()));
		}
		return rows;
	}

	private static void addGroup(List<Object> rows, String title, List<BuildingCatalog.Entry> entries, boolean open) {
		rows.add(new Heading(title, entries.size(), open));
		if (open) {
			rows.addAll(entries);
		}
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
		final DefaultListModel<Object> model = new DefaultListModel<>();
		final JList<Object> list = new JList<>(model);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
			@Override
			public java.awt.Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
				JLabel lbl = (JLabel) super.getListCellRendererComponent(l, v, i, sel, foc);
				if (v instanceof Heading) {
					lbl.setText("<html><b>" + Ui.html(v.toString()) + "</b></html>");
					return lbl;
				}
				BuildingCatalog.Entry en = (BuildingCatalog.Entry) v;
				lbl.setText("<html>&nbsp;&nbsp;" + Ui.html(en.name) + " <small>(" + en.tilesW() + "x" + en.tilesH()
						+ (en.enterable() ? ", enterable" : "")
						+ (en.retailCount > 1 ? ", x" + en.retailCount + " in game" : "") + ")</small></html>");
				return lbl;
			}
		});
		//the headings clicked open or closed; a new search or filter starts from the defaults again
		final java.util.Set<String> flipped = new java.util.HashSet<>();
		//rebuilds the rows; the heading named stays selected, else the first entry is
		final java.util.function.Consumer<String> rebuild = keep -> {
			String q = search.getText();
			String cat = catBox.getSelectedIndex() > 0 ? (String) catBox.getSelectedItem() : null;
			String loc = locBox.getSelectedIndex() > 0 ? (String) locBox.getSelectedItem() : null;
			model.clear();
			for (Object row : rows(shown(all, q, cat, loc), flipped, !q.trim().isEmpty() || loc != null)) {
				model.addElement(row);
			}
			for (int i = 0; i < model.size(); i++) {
				Object row = model.get(i);
				if (keep == null ? row instanceof BuildingCatalog.Entry : row instanceof Heading && ((Heading) row).title.equals(keep)) {
					list.setSelectedIndex(i);
					list.ensureIndexIsVisible(i);
					break;
				}
			}
		};
		Runnable refilter = () -> {
			flipped.clear();
			rebuild.accept(null);
		};
		final java.util.function.Consumer<Heading> toggle = h -> {
			if (!flipped.remove(h.title)) {
				flipped.add(h.title);
			}
			rebuild.accept(h.title);
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
		left.add(new JLabel("<html><small>The whole game's structures are here, under their places - click a<br>place"
				+ " to open it. Search any words of a name, place or material, or a size<br>such as 11x9; or narrow by type"
				+ " and place. Curated entries (with door<br>wiring) list first.</small></html>"), BorderLayout.SOUTH);
		dlg.add(left, BorderLayout.WEST);

		//NULL WHEN OPENGL CANNOT START (F077): the catalogue, search and placing still work
		final MapPreview3D view = GlSupport.available() ? new MapPreview3D() : null;
		final java.awt.Component viewShown = view != null ? view : GlSupport.placeholder("The structure preview");
		viewShown.setPreferredSize(new Dimension(520, 420));
		final JLabel info = new JLabel(" ");
		final javax.swing.JCheckBox leave = new javax.swing.JCheckBox(
				"Leave the passengers behind (sea planes, shadow decals, floors, terrain chips)");
		leave.setToolTipText("The structure's collision and movement tiles come along either way.");
		JPanel under = new JPanel(new BorderLayout());
		under.add(info, BorderLayout.NORTH);
		under.add(leave, BorderLayout.SOUTH);
		JPanel center = new JPanel(new BorderLayout(4, 4));
		center.add(viewShown, BorderLayout.CENTER);
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
			if (!(list.getSelectedValue() instanceof BuildingCatalog.Entry)) {
				return;   //nothing, or a place's heading: the last preview stays up
			}
			final BuildingCatalog.Entry e = (BuildingCatalog.Entry) list.getSelectedValue();
			info.setText("  Loading preview: " + e.name + "...");
			previews.ask(ticket -> {
				Preview out = new Preview();
				try {
					MapPrefab p = BuildingCatalog.prefab(Workspace.session(), e);
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
						out.note = "  " + e.name + " - " + e.tilesW() + "x" + e.tilesH() + " tiles, " + p.manifest(r, e.baseY)
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
				if (shown.model != null && view != null) {
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
			Object chosen = list.getSelectedValue();
			if (chosen instanceof Heading) {
				return;   //a place is not a building: the palette stays open
			}
			result[0] = chosen == null ? null : new Pick((BuildingCatalog.Entry) chosen, !leave.isSelected());
			dlg.dispose();
		};
		place.addActionListener(ev -> choose.run());
		cancel.addActionListener(ev -> dlg.dispose());
		list.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				int at = list.locationToIndex(e.getPoint());
				Object row = at < 0 ? null : model.get(at);
				if (row instanceof Heading) {
					if (e.getClickCount() == 1) {   //a double click's second press does not close it again
						toggle.accept((Heading) row);
					}
				} else if (e.getClickCount() == 2) {
					choose.run();
				}
			}
		});
		//a heading opens and closes from the keyboard too
		list.addKeyListener(new java.awt.event.KeyAdapter() {
			@Override
			public void keyPressed(java.awt.event.KeyEvent e) {
				if ((e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER || e.getKeyCode() == java.awt.event.KeyEvent.VK_SPACE)
						&& list.getSelectedValue() instanceof Heading) {
					toggle.accept((Heading) list.getSelectedValue());
					e.consume();
				}
			}
		});

		refilter.run();
		dlg.addWindowListener(new java.awt.event.WindowAdapter() {
			@Override
			public void windowClosing(java.awt.event.WindowEvent e) {
				if (view != null) {
					view.stop();
				}
			}
		});
		dlg.pack();
		dlg.setLocationRelativeTo(parent);
		dlg.setVisible(true);
		previews.close();
		if (view != null) {
			view.stop();
		}
		return result[0];
	}

	/** What the preview worker hands the event thread for one row. */
	private static final class Preview {

		String note = " ";
		byte[] model;
		final List<H3DTexture> textures = new ArrayList<>();
	}
}
