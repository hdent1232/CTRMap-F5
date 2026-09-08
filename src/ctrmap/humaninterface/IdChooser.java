package ctrmap.humaninterface;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;

/**
 * A searchable id picker over a list of game-text names.
 *
 * <p>One of the ten classes that lived inside {@link NPCEditForm}, which was
 * 2840 lines. Each was an inner class reading the editor it sat in through
 * synthetic bridges, so none could be built without one; each is handed what
 * it needs now and is an ordinary class in a file of its own.
 *
 * <p>A searchable ID picker: a filter field over a "id: name" list when names
 * are available, or a plain numeric spinner otherwise. getId() returns the
 * chosen 1-based ID, or -1 when the filtered list has no selection.
 */
public final class IdChooser extends JPanel {

	private javax.swing.JList<String> list;
	private final java.util.List<Integer> ids = new java.util.ArrayList<>();
	private JSpinner spinner;

	IdChooser(java.util.List<String> names, int maxId, int defaultId) {
		setLayout(new java.awt.BorderLayout());
		if (names == null || names.size() <= 1) {
			spinner = new JSpinner(new javax.swing.SpinnerNumberModel(defaultId, 1, maxId, 1));
			add(spinner, java.awt.BorderLayout.CENTER);
			return;
		}
		final java.util.List<String> entries = new java.util.ArrayList<>();
		final java.util.List<Integer> baseIds = new java.util.ArrayList<>();
		for (int i = 1; i <= maxId && i < names.size(); i++) {
			String nm = names.get(i);
			if (nm == null || nm.isEmpty() || nm.equals("-")) {
				continue;
			}
			entries.add(i + ": " + nm);
			baseIds.add(i);
		}
		final javax.swing.DefaultListModel<String> model = new javax.swing.DefaultListModel<>();
		list = new javax.swing.JList<>(model);
		list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		final javax.swing.JTextField filter = new javax.swing.JTextField();
		final Runnable rebuild = new Runnable() {
			@Override
			public void run() {
				String f = filter.getText().toLowerCase();
				model.clear();
				ids.clear();
				for (int k = 0; k < entries.size(); k++) {
					if (f.isEmpty() || entries.get(k).toLowerCase().contains(f)) {
						model.addElement(entries.get(k));
						ids.add(baseIds.get(k));
					}
				}
				if (!model.isEmpty() && list.getSelectedIndex() < 0) {
					list.setSelectedIndex(0);
				}
			}
		};
		filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
			@Override public void insertUpdate(javax.swing.event.DocumentEvent e) { rebuild.run(); }
			@Override public void removeUpdate(javax.swing.event.DocumentEvent e) { rebuild.run(); }
			@Override public void changedUpdate(javax.swing.event.DocumentEvent e) { rebuild.run(); }
		});
		rebuild.run();
		for (int k = 0; k < ids.size(); k++) {
			if (ids.get(k) == defaultId) {
				list.setSelectedIndex(k);
				break;
			}
		}
		add(filter, java.awt.BorderLayout.NORTH);
		JScrollPane sc = new JScrollPane(list);
		sc.setPreferredSize(new java.awt.Dimension(280, 150));
		add(sc, java.awt.BorderLayout.CENTER);
	}

	int getId() {
		if (spinner != null) {
			return (Integer) spinner.getValue();
		}
		int sel = list.getSelectedIndex();
		return (sel >= 0 && sel < ids.size()) ? ids.get(sel) : -1;
	}
}
