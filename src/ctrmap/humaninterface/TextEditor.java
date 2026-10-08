package ctrmap.humaninterface;

import ctrmap.Workspace;
import ctrmap.formats.text.GFMessageFile;
import ctrmap.formats.text.LocationNames;
import ctrmap.gamedef.ArchiveType;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.DefaultTableModel;

/**
 * Editor tab for the GameText GARC. Lines are decoded and re-encoded with GFMessageFile,
 * edited files are stored into the workspace gametext directory and registered for packing.
 */
public class TextEditor extends javax.swing.JPanel {

	private JComboBox<Integer> fileIdx;
	private JButton btnSave;
	private JTable table;
	private DefaultTableModel model;
	private JScrollPane tableScrollPane;

	private int loadedIdx = -1;
	private boolean modified = false;
	private GFMessageFile loadedFile;
	private boolean loading = false;

	public TextEditor() {
		setLayout(new BorderLayout());
		JPanel north = new JPanel(new FlowLayout(FlowLayout.LEFT));
		fileIdx = new JComboBox<>();
		btnSave = new JButton("Save file");
		north.add(new JLabel("Text file:"));
		north.add(fileIdx);
		north.add(btnSave);
		add(north, BorderLayout.NORTH);
		model = new DefaultTableModel(new Object[]{"Line", "Text"}, 0) {
			@Override
			public boolean isCellEditable(int row, int column) {
				return column == 1;
			}
		};
		table = new JTable(model);
		table.getColumnModel().getColumn(0).setMaxWidth(80);
		table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
		tableScrollPane = new JScrollPane(table);
		add(tableScrollPane, BorderLayout.CENTER);
		model.addTableModelListener(new TableModelListener() {
			@Override
			public void tableChanged(TableModelEvent e) {
				if (!loading) {
					modified = true;
				}
			}
		});
		fileIdx.addActionListener(e -> {
			if (loading) {
				return;
			}
			Integer sel = (Integer) fileIdx.getSelectedItem();
			if (sel == null || sel == loadedIdx) {
				return;
			}
			if (store(true)) {
				loadFile(sel);
			} else {
				//canceled, revert the selection
				loading = true;
				fileIdx.setSelectedItem(loadedIdx);
				loading = false;
			}
		});
		btnSave.addActionListener(e -> {
			store(false);
		});
	}

	public void loadGarc() {
		if (!Workspace.isValid()) {
			return;
		}
		loading = true;
		DefaultComboBoxModel<Integer> cbm = new DefaultComboBoxModel<>();
		for (int i = 0; i < Workspace.getArchive(ArchiveType.GAMETEXT).length; i++) {
			cbm.addElement(i);
		}
		fileIdx.setModel(cbm);
		int target = (loadedIdx >= 0 && loadedIdx < Workspace.getArchive(ArchiveType.GAMETEXT).length) ? loadedIdx : 0;
		fileIdx.setSelectedItem(target);
		loading = false;
		if (Workspace.getArchive(ArchiveType.GAMETEXT).length > 0) {
			loadFile(target);
		}
	}

	public void loadFile(int idx) {
		if (!Workspace.isValid()) {
			return;
		}
		loading = true;
		//A FILE THAT DOES NOT READ IS NOT OFFERED FOR EDITING. It was shown as an empty table that
		//took typing, and the next save wrote what was typed over the file it could not read - a
		//failed read, a file that would not extract and a file this decoder rejects all alike.
		String why = null;
		List<String> lines = new ArrayList<>();
		loadedFile = null;
		File f = Workspace.getWorkspaceFile(ArchiveType.GAMETEXT, idx);
		if (f == null || !f.exists()) {
			why = "it could not be extracted from the GameText archive";
		} else {
			try {
				loadedFile = new GFMessageFile(java.nio.file.Files.readAllBytes(f.toPath()));
				lines = loadedFile.getLines();
			} catch (IOException | RuntimeException unreadable) {
				loadedFile = null;
				why = ctrmap.Ui.reason(unreadable);
			}
		}
		model.setRowCount(0);
		for (int i = 0; i < lines.size(); i++) {
			model.addRow(new Object[]{i, lines.get(i)});
		}
		loadedIdx = why == null ? idx : -1;
		table.setEnabled(why == null);
		btnSave.setEnabled(why == null);
		modified = false;
		loading = false;
		if (why != null) {
			ctrmap.Ui.error(this, "Text file " + idx + " could not be read, so it is not offered for editing:\n" + why,
					"Text editor");
		}
	}

	public boolean store(boolean dialog) {
		if (!Workspace.isValid() || loadedIdx == -1) {
			return true;
		}
		if (table.isEditing()) {
			table.getCellEditor().stopCellEditing();
		}
		if (!modified) {
			return true;
		}
		//THROUGH THE ONE QUESTION (F106): No cleared the flag and left the typed lines in the table,
		//shown and no longer marked unsaved; it now reads the file again
		final int shown = loadedIdx;
		switch (ctrmap.Ui.askToKeep(dialog, "Text file " + shown, () -> loadFile(shown))) {
			case DISCARD:
				return true;
			case CANCEL:
				return false;
			default:
				break; //continue to save
		}
		List<String> lines = new ArrayList<>();
		for (int i = 0; i < model.getRowCount(); i++) {
			Object val = model.getValueAt(i, 1);
			lines.add(val == null ? "" : val.toString());
		}
		byte[] b;
		try {
			if (loadedFile != null) {
				loadedFile.setLines(lines); //keeps per-line extra values that the games use
				b = loadedFile.write();
			} else {
				b = GFMessageFile.write(lines);
			}
		} catch (RuntimeException ex) {
			ctrmap.Ui.error(this, "Could not encode text file " + loadedIdx + ":\n" + ctrmap.util.Bytes.reason(ex), "Text encode error");
			return false;
		}
		File f = Workspace.getWorkspaceFile(ArchiveType.GAMETEXT, loadedIdx);
		if (f == null) {
			ctrmap.Ui.error(this, "Could not extract text file " + loadedIdx + " from the GameText archive.", "Text save error");
			return false;
		}
		try {
			ctrmap.WorkspaceUnit.write(f, b);
		} catch (IOException ex) {
			//SAID, NOT LOGGED (census H.68): the Save button drops this answer, so a log was all
			//there was - and the file is as it was, because the write goes beside it first
			ctrmap.Ui.error(this, "Text file " + loadedIdx + " was not saved; it is as it was:\n" + ctrmap.Ui.reason(ex),
					"Text save error");
			return false;
		}
		Workspace.addPersist(f);
		modified = false;
		if (isLocationNamesFile(loadedIdx)) {
			LocationNames.loadFromGarc(Workspace.session());
		}
		return true;
	}

	/**
	 * Whether the entry just saved is the location-name table, so the cached
	 * copy the whole editor reads names from is reloaded.
	 *
	 * <p>This used to carry its own copy of three GameText indices behind an
	 * {@code isXY() / isOADemo()} ladder - the numbers 72, 91 and 90, in a class
	 * that has no business knowing any of them. Two costs, both real: an ORAS
	 * demo whose entry 90 was edited fell through to the retail branch and
	 * reloaded nothing, and Sun/Moon answered false to both gates and so
	 * declared ORAS's entry 90 to be its location names. It asks
	 * {@link LocationNames#gametextIndex} now, which resolves game AND edition
	 * from the profile - the same number the reload it triggers will read from.
	 *
	 * <p>A game with no measured location-name entry answers -1, which no saved
	 * entry index can equal, so nothing is reloaded and nothing is claimed.
	 */
	private boolean isLocationNamesFile(int idx) {
		return idx >= 0 && idx == LocationNames.gametextIndex(Workspace.session());
	}
}
