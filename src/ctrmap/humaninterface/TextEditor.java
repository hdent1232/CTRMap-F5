package ctrmap.humaninterface;

import ctrmap.Workspace;
import ctrmap.formats.text.GFMessageFile;
import ctrmap.formats.text.LocationNames;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
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
		if (!Workspace.valid) {
			return;
		}
		loading = true;
		DefaultComboBoxModel<Integer> cbm = new DefaultComboBoxModel<>();
		for (int i = 0; i < Workspace.texts.length; i++) {
			cbm.addElement(i);
		}
		fileIdx.setModel(cbm);
		int target = (loadedIdx >= 0 && loadedIdx < Workspace.texts.length) ? loadedIdx : 0;
		fileIdx.setSelectedItem(target);
		loading = false;
		if (Workspace.texts.length > 0) {
			loadFile(target);
		}
	}

	public void loadFile(int idx) {
		if (!Workspace.valid) {
			return;
		}
		loading = true;
		byte[] b = new byte[0];
		File f = Workspace.getWorkspaceFile(Workspace.ArchiveType.GAMETEXT, idx);
		if (f != null && f.exists()) {
			try {
				InputStream in = new FileInputStream(f);
				b = new byte[in.available()];
				in.read(b);
				in.close();
			} catch (IOException ex) {
				Logger.getLogger(TextEditor.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
		List<String> lines;
		try {
			loadedFile = new GFMessageFile(b);
			lines = loadedFile.getLines();
		} catch (RuntimeException ex) {
			loadedFile = null; //unreadable file - show it as empty rather than crashing the tab
			lines = new ArrayList<>();
		}
		model.setRowCount(0);
		for (int i = 0; i < lines.size(); i++) {
			model.addRow(new Object[]{i, lines.get(i)});
		}
		loadedIdx = idx;
		modified = false;
		loading = false;
	}

	public boolean store(boolean dialog) {
		if (!Workspace.valid || loadedIdx == -1) {
			return true;
		}
		if (table.isEditing()) {
			table.getCellEditor().stopCellEditing();
		}
		if (!modified) {
			return true;
		}
		if (dialog) {
			int rsl = JOptionPane.showConfirmDialog(this, "Save text changes?", "Save changes", JOptionPane.YES_NO_CANCEL_OPTION);
			switch (rsl) {
				case JOptionPane.YES_OPTION:
					break; //continue to save
				case JOptionPane.NO_OPTION:
					modified = false;
					return true;
				default:
					return false;
			}
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
			JOptionPane.showMessageDialog(this, "Could not encode text file " + loadedIdx + ":\n" + ex.getMessage(), "Text encode error", JOptionPane.ERROR_MESSAGE);
			return false;
		}
		File f = Workspace.getWorkspaceFile(Workspace.ArchiveType.GAMETEXT, loadedIdx);
		if (f == null) {
			JOptionPane.showMessageDialog(this, "Could not extract text file " + loadedIdx + " from the GameText archive.", "Text save error", JOptionPane.ERROR_MESSAGE);
			return false;
		}
		try {
			OutputStream os = new FileOutputStream(f);
			os.write(b);
			os.flush();
			os.close();
		} catch (IOException ex) {
			Logger.getLogger(TextEditor.class.getName()).log(Level.SEVERE, null, ex);
			return false;
		}
		Workspace.addPersist(f);
		modified = false;
		if (isLocationNamesFile(loadedIdx)) {
			LocationNames.loadFromGarc();
		}
		return true;
	}

	private boolean isLocationNamesFile(int idx) {
		if (Workspace.isXY()) {
			return idx == 72;
		} else if (Workspace.isOADemo()) {
			return idx == 91;
		} else {
			return idx == 90;
		}
	}
}
