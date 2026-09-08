package ctrmap.humaninterface;

import ctrmap.formats.npcreg.NPCRegistry;
import ctrmap.Workspace;
import ctrmap.formats.h3d.model.H3DModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/**
 * The overworld-model picker, with a live 3D preview.
 *
 * <p>One of the ten classes that lived inside {@link NPCEditForm}, which was
 * 2840 lines. Each was an inner class reading the editor it sat in through
 * synthetic bridges, so none could be built without one; each is handed what
 * it needs now and is an ordinary class in a file of its own.
 *
 * <p>A searchable NPC-model picker with a live 3D preview. Lists ONLY the
 * registered overworld models (from the NPC registry) as "UID: name" - so
 * empty/unregistered UIDs never clutter the browser - and previews the
 * selection. Names come from each model's embedded BCH name.
 */
public final class ModelPicker extends JPanel {

	private final javax.swing.JList<String> list = new javax.swing.JList<>();
	// each entry is {kind, value}: kind 0 = registered UID, kind 1 = global MoveModels index
	private final List<int[]> visibleEntries = new ArrayList<>();
	private final List<int[]> allEntries = new ArrayList<>();
	private final List<String> allLabels = new ArrayList<>();
	private final List<int[]> registered = new ArrayList<>();
	private final List<String> registeredLabels = new ArrayList<>();
	private final List<int[]> poolExtra = new ArrayList<>();
	private final List<String> poolExtraLabels = new ArrayList<>();
	private boolean poolLoaded = false;
	private final CustomH3DPreview preview = new CustomH3DPreview();
	private final javax.swing.JTextField filter = new javax.swing.JTextField();
	private final javax.swing.JCheckBox showAll = new javax.swing.JCheckBox("Browse ALL game NPC models (adds the one you pick to this area)");
	private final javax.swing.DefaultListModel<String> listModel = new javax.swing.DefaultListModel<>();

	/** The registry whose models this picker lists; handed in, because it is the editor's. */
	private final NPCRegistry reg;
	/** Where a live preview registers itself, so the form that built it can stop them all. */
	private final List<CustomH3DPreview> livePreviews;

	/** The open game, for the model pool this browses; handed in like everything else here. */
	private final ctrmap.formats.GameFiles game;

	public ModelPicker(NPCRegistry reg, List<CustomH3DPreview> livePreviews, ctrmap.formats.GameFiles game, int defaultUid) {
		this.game = game;
		this.reg = reg;
		this.livePreviews = livePreviews;
		setLayout(new java.awt.BorderLayout());
		if (reg != null) {
			List<Integer> keys = new ArrayList<>(reg.entries.keySet());
			Collections.sort(keys);
			for (int uid : keys) {
				H3DModel m = reg.getModel(uid);
				String nm = (m != null && m.name != null) ? m.name.trim() : "";
				registered.add(new int[]{0, uid});
				registeredLabels.add(uid + (nm.isEmpty() ? "" : ": " + nm));
			}
		}
		list.setModel(listModel);
		list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
			@Override public void insertUpdate(javax.swing.event.DocumentEvent e) { rebuild(); }
			@Override public void removeUpdate(javax.swing.event.DocumentEvent e) { rebuild(); }
			@Override public void changedUpdate(javax.swing.event.DocumentEvent e) { rebuild(); }
		});
		showAll.addActionListener((java.awt.event.ActionEvent e) -> {
			if (showAll.isSelected() && !poolLoaded) {
				setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
				try {
					loadFullPool();
				} finally {
					setCursor(java.awt.Cursor.getDefaultCursor());
				}
			}
			buildEntries();
			rebuild();
		});
		list.addListSelectionListener((javax.swing.event.ListSelectionEvent e) -> {
			if (!e.getValueIsAdjusting()) {
				updatePreview();
			}
		});
		buildEntries();
		rebuild();
		for (int k = 0; k < visibleEntries.size(); k++) {
			int[] en = visibleEntries.get(k);
			if (en[0] == 0 && en[1] == defaultUid) {
				list.setSelectedIndex(k);
				break;
			}
		}
		JScrollPane listScroll = new JScrollPane(list);
		listScroll.setPreferredSize(new java.awt.Dimension(300, 120));
		preview.setPreferredSize(new java.awt.Dimension(300, 190));
		javax.swing.JPanel top = new javax.swing.JPanel(new java.awt.BorderLayout());
		top.add(filter, java.awt.BorderLayout.NORTH);
		top.add(showAll, java.awt.BorderLayout.SOUTH);
		add(top, java.awt.BorderLayout.NORTH);
		add(listScroll, java.awt.BorderLayout.CENTER);
		add(preview, java.awt.BorderLayout.SOUTH);
		livePreviews.add(preview);
		updatePreview();
	}

	private void loadFullPool() {
		poolExtra.clear();
		poolExtraLabels.clear();
		java.util.Set<Integer> already = new java.util.HashSet<>();
		if (reg != null) {
			for (NPCRegistry.NPCRegistryEntry en : reg.entries.values()) {
				already.add(en.model);
			}
		}
		int n = ctrmap.formats.npcreg.MoveModelPool.size(game);
		for (int i = 0; i < n; i++) {
			if (already.contains(i)) {
				continue; //already offered via the registered list
			}
			String nm = ctrmap.formats.npcreg.MoveModelPool.name(game, i);
			poolExtra.add(new int[]{1, i});
			poolExtraLabels.add("[+] model " + i + (nm == null || nm.isEmpty() ? "" : ": " + nm));
		}
		poolLoaded = true;
	}

	private void buildEntries() {
		allEntries.clear();
		allLabels.clear();
		allEntries.addAll(registered);
		allLabels.addAll(registeredLabels);
		if (showAll.isSelected()) {
			allEntries.addAll(poolExtra);
			allLabels.addAll(poolExtraLabels);
		}
	}

	private void rebuild() {
		String f = filter.getText().toLowerCase();
		listModel.clear();
		visibleEntries.clear();
		for (int k = 0; k < allEntries.size(); k++) {
			if (f.isEmpty() || allLabels.get(k).toLowerCase().contains(f)) {
				listModel.addElement(allLabels.get(k));
				visibleEntries.add(allEntries.get(k));
			}
		}
		if (!listModel.isEmpty() && list.getSelectedIndex() < 0) {
			list.setSelectedIndex(0);
		}
	}

	/**
	 * The chosen UID. If the user picked a global model that is not yet in this
	 * area, it is registered on demand (and the new UID returned). Returns -1
	 * when nothing valid is selected or the area's registry is full.
	 */
	int getSelectedUid() {
		int s = list.getSelectedIndex();
		if (s < 0 || s >= visibleEntries.size()) {
			return -1;
		}
		int[] en = visibleEntries.get(s);
		if (en[0] == 0) {
			return en[1]; //already a registered UID
		}
		int uid = (reg != null) ? reg.registerModel(en[1]) : -1;
		if (uid < 0) {
			//through Ui, not JOptionPane: this is the only thing that
			//distinguishes "the model you picked is now on the NPC" from
			//"nothing happened", and a bare dialog is neither reachable nor
			//observable from a guard
			ctrmap.Ui.error(this,
					"This area's NPC registry is full (max " + NPCRegistry.MAX_ENTRIES + " unique models).\n"
					+ "Remove an unused model in the NPC registry editor and try again.",
					"Registry full");
		}
		return uid;
	}

	boolean hasModels() {
		return !registered.isEmpty();
	}

	private void updatePreview() {
		int s = list.getSelectedIndex();
		if (s < 0 || s >= visibleEntries.size()) {
			preview.loadModel(null);
			return;
		}
		int[] en = visibleEntries.get(s);
		H3DModel m = (en[0] == 0)
				? ((reg != null) ? reg.loadFreshModel(en[1]) : null)
				: NPCRegistry.loadFreshModelByIndex(game, en[1]);
		preview.loadModel(m);
	}
}
