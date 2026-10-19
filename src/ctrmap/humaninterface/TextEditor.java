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
	private Lines model;
	private JScrollPane tableScrollPane;

	private int loadedIdx = -1;
	private GFMessageFile loadedFile;
	private boolean loading = false;
	/** One per file loaded: what the lines' document is keyed by, so loading another file is no edit. */
	private Object shown = new Object();
	/** The saves made in this tab, and the lines typed and not saved, each kept to be taken back (F209c). */
	private final SaveHistory saves = new SaveHistory(() -> java.util.Collections.<Object>singletonList(Workspace.session()));

	/** The lines shown and whether they differ from what was read - what this editor edits in memory. */
	private static final class Lines extends DefaultTableModel {

		boolean modified = false;

		Lines() {
			super(new Object[]{"Line", "Text"}, 0);
		}

		@Override
		public boolean isCellEditable(int row, int column) {
			return column == 1;
		}
	}

	public TextEditor() {
		setLayout(new BorderLayout());
		JPanel north = new JPanel(new FlowLayout(FlowLayout.LEFT));
		fileIdx = new JComboBox<>();
		btnSave = new JButton("Save file");
		north.add(new JLabel("Text file:"));
		north.add(fileIdx);
		north.add(btnSave);
		add(north, BorderLayout.NORTH);
		model = new Lines();
		table = new JTable(model);
		table.getColumnModel().getColumn(0).setMaxWidth(80);
		table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
		tableScrollPane = new JScrollPane(table);
		add(tableScrollPane, BorderLayout.CENTER);
		model.addTableModelListener(new TableModelListener() {
			@Override
			public void tableChanged(TableModelEvent e) {
				if (!loading) {
					model.modified = true;
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
		model.modified = false;
		shown = new Object();
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
		if (!model.modified) {
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
		//ONE KEPT CHANGE (F209c): written through the unit and kept in this tab's history; put back or put again,
		//the editor reads the file again - and the location names, when they are what it holds
		try (ctrmap.WorkspaceUnit unit = ctrmap.WorkspaceUnit.begin("Save text file " + shown, Workspace.session())) {
			ctrmap.WorkspaceUnit.write(f, b);
			Workspace.addPersist(f);
			ctrmap.WorkspaceUnit.inMemory(() -> readAgain(shown), () -> readAgain(shown));
			saves.keep(unit.commitKeeping());
		} catch (IOException | RuntimeException ex) {
			//SAID, NOT LOGGED (census H.68): the Save button drops this answer, so a log was all
			//there was - and the file is as it was, because the write goes beside it first
			ctrmap.Ui.error(this, "Text file " + loadedIdx + " was not saved; it is as it was:\n" + ctrmap.Ui.reason(ex),
					"Text save error");
			return false;
		}
		model.modified = false;
		if (isLocationNamesFile(loadedIdx)) {
			LocationNames.loadFromGarc(Workspace.session());
		}
		return true;
	}

	/** After a save is put back or put again: the file shown read again, and the location names when they are it. */
	private void readAgain(int idx) {
		if (loadedIdx == idx) {
			loadFile(idx);
		}
		if (isLocationNamesFile(idx)) {
			LocationNames.loadFromGarc(Workspace.session());
		}
	}

	/** This editor with Undo and Redo under it, owned by its history: what the Text Editor tab shows. */
	public javax.swing.JComponent withUndo() {
		return saves.around(SaveHistory.watching(this,
				() -> java.util.Collections.<UndoHistory.Document<?>>singletonList(lines())));
	}

	/** The history this tab keeps: the window settles it around every gesture, and a suite undoes through it. */
	public UndoHistory history() {
		return saves.history();
	}

	/** The file shown, as its lines: one document per file loaded, put back into that file, loaded first. */
	private UndoHistory.Document<Object[]> lines() {
		final Object loaded = shown;
		final Lines m = model;
		return new UndoHistory.Document<Object[]>() {
			@Override
			public Object subject() {
				return loaded;
			}

			@Override
			public Object[] take() {
				String[] rows = new String[m.getRowCount()];
				for (int i = 0; i < rows.length; i++) {
					Object v = m.getValueAt(i, 1);
					rows[i] = v == null ? "" : v.toString();
				}
				return new Object[]{loadedIdx, rows};
			}

			@Override
			public void put(Object[] state) {
				int idx = (Integer) state[0];
				if (idx != loadedIdx) {
					loadFile(idx);
					loading = true;
					fileIdx.setSelectedItem(idx);
					loading = false;
				}
				String[] rows = (String[]) state[1];
				loading = true;
				try {
					m.setRowCount(0);
					for (int i = 0; i < rows.length; i++) {
						m.addRow(new Object[]{i, rows[i]});
					}
				} finally {
					loading = false;
				}
				m.modified = true;
			}

			@Override
			public boolean same(Object[] a, Object[] b) {
				return a[0].equals(b[0]) && java.util.Arrays.equals((String[]) a[1], (String[]) b[1]);
			}

			@Override
			public String what() {
				return "the lines of text file " + loadedIdx;
			}
		};
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
