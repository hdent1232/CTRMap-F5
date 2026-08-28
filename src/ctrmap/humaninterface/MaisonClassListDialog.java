package ctrmap.humaninterface;

import ctrmap.Workspace;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.maison.MaisonClassList;
import ctrmap.formats.text.GFMessageFile;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;

/**
 * Edits the Battle Maison class-to-set-list tables: which opponent {@link
 * ctrmap.formats.maison.MaisonSet}s each trainer class draws from. Two tables:
 * a/1/8/3 (-&gt; set pool A) and a/1/8/5 (-&gt; pool B). Each row is a trainer
 * class with a comma-separated list of set indices into the paired pool; this
 * is how you point a class (e.g. a Battle Chatelaine, classes 64-67) at custom
 * teams authored in the sets editor.
 */
public class MaisonClassListDialog {

	private static final Workspace.ArchiveType[] TABLES = {
		Workspace.ArchiveType.MAISON_CLASS_LIST_A,
		Workspace.ArchiveType.MAISON_CLASS_LIST_B
	};
	private static final String[] TABLE_NAMES = {
		"Table A (-> set pool A, " + arcName(Workspace.ArchiveType.MAISON_CLASS_LIST_A) + ")",
		"Table B (-> set pool B, " + arcName(Workspace.ArchiveType.MAISON_CLASS_LIST_B) + ")"
	};

	private static String arcName(Workspace.ArchiveType t) {
		String p = Workspace.getArchivePath(t, Workspace.game != null ? Workspace.game : Workspace.GameType.ORAS);
		return p == null ? "?" : p;
	}

	public static void show(Dialog parent) {
		if (Workspace.getArchive(TABLES[0]) == null) {
			JOptionPane.showMessageDialog(parent, "This dump has no Maison class tables.", "Class assignments", JOptionPane.ERROR_MESSAGE);
			return;
		}
		String[] classNames = text(Workspace.profile().textIndex(ctrmap.gamedef.GameProfile.TextIndex.TRAINER_CLASS_NAMES));
		final JComboBox<String> tableBox = new JComboBox<>(TABLE_NAMES);
		final ListModel model = new ListModel(classNames);
		model.loadTable(0);
		final JTable jt = new JTable(model);
		jt.getColumnModel().getColumn(1).setPreferredWidth(150);
		jt.getColumnModel().getColumn(2).setPreferredWidth(430);

		final JDialog dlg = new JDialog(parent, "Maison class assignments", true);
		dlg.setLayout(new BorderLayout());
		JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
		top.add(new JLabel("Table:"));
		top.add(tableBox);
		top.add(new JLabel("   (Set indices = comma-separated, into the paired pool)"));
		dlg.add(top, BorderLayout.NORTH);
		dlg.add(new JScrollPane(jt), BorderLayout.CENTER);

		tableBox.addActionListener(e -> {
			if (model.dirty && !confirmDiscard(dlg)) {
				tableBox.setSelectedIndex(model.tableIndex);
				return;
			}
			model.loadTable(tableBox.getSelectedIndex());
		});

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton save = new JButton("Save table");
		JButton close = new JButton("Close");
		buttons.add(save);
		buttons.add(close);
		dlg.add(buttons, BorderLayout.SOUTH);

		save.addActionListener(e -> {
			try {
				if (jt.isEditing()) {
					jt.getCellEditor().stopCellEditing();
				}
				model.save();
				JOptionPane.showMessageDialog(dlg, "Saved " + TABLE_NAMES[model.tableIndex]
						+ ".\nDeploy to emulator to apply.", "Class assignments", JOptionPane.INFORMATION_MESSAGE);
			} catch (Exception ex) {
				JOptionPane.showMessageDialog(dlg, "Save failed:\n" + ex.getMessage(), "Class assignments", JOptionPane.ERROR_MESSAGE);
			}
		});
		close.addActionListener(e -> {
			if (model.dirty && !confirmDiscard(dlg)) {
				return;
			}
			dlg.dispose();
		});

		dlg.setSize(820, 520);
		dlg.setLocationRelativeTo(parent);
		dlg.setVisible(true);
	}

	private static boolean confirmDiscard(java.awt.Component c) {
		return JOptionPane.showConfirmDialog(c, "Discard unsaved changes to this table?", "Class assignments",
				JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
	}

	private static String[] text(int entry) {
		try {
			File f = Workspace.getWorkspaceFile(Workspace.ArchiveType.GAMETEXT, entry);
			List<String> lines = GFMessageFile.getStrings(Files.readAllBytes(f.toPath()));
			return lines.toArray(new String[0]);
		} catch (Exception ex) {
			return new String[0];
		}
	}

	private static class ListModel extends AbstractTableModel {

		final String[] classNames;
		int tableIndex = 0;
		MaisonClassList[] lists = new MaisonClassList[0];
		boolean dirty = false;

		ListModel(String[] classNames) {
			this.classNames = classNames;
		}

		void loadTable(int idx) {
			tableIndex = idx;
			GARC g = Workspace.getArchive(TABLES[idx]);
			lists = new MaisonClassList[g.length];
			for (int i = 0; i < g.length; i++) {
				try {
					byte[] rec = Files.readAllBytes(Workspace.getWorkspaceFile(TABLES[idx], i).toPath());
					lists[i] = MaisonClassList.read(rec);
				} catch (Exception ex) {
					lists[i] = new MaisonClassList();
				}
			}
			dirty = false;
			fireTableDataChanged();
		}

		void save() throws Exception {
			for (int i = 0; i < lists.length; i++) {
				File f = Workspace.getWorkspaceFile(TABLES[tableIndex], i);
				try (FileOutputStream fos = new FileOutputStream(f)) {
					fos.write(lists[i].write());
				}
				Workspace.addPersist(f);
			}
			dirty = false;
		}

		@Override
		public int getRowCount() {
			return lists.length;
		}

		@Override
		public int getColumnCount() {
			return 3;
		}

		@Override
		public String getColumnName(int c) {
			return c == 0 ? "Class #" : c == 1 ? "Class name" : "Set indices (comma-separated)";
		}

		@Override
		public boolean isCellEditable(int r, int c) {
			return c == 2;
		}

		@Override
		public Object getValueAt(int r, int c) {
			MaisonClassList l = lists[r];
			switch (c) {
				case 0: return l.classTag;
				case 1: return l.classTag < classNames.length ? classNames[l.classTag] : "#" + l.classTag;
				default:
					StringBuilder sb = new StringBuilder();
					for (int i = 0; i < l.setIndices.size(); i++) {
						if (i > 0) {
							sb.append(", ");
						}
						sb.append(l.setIndices.get(i));
					}
					return sb.toString();
			}
		}

		@Override
		public void setValueAt(Object v, int r, int c) {
			if (c != 2) {
				return;
			}
			MaisonClassList l = lists[r];
			java.util.List<Integer> parsed = new java.util.ArrayList<>();
			for (String tok : String.valueOf(v).split(",")) {
				tok = tok.trim();
				if (tok.isEmpty()) {
					continue;
				}
				try {
					parsed.add(Math.max(0, Math.min(0xFFFF, Integer.parseInt(tok))));
				} catch (NumberFormatException ignore) {
					return; // reject the whole edit on any bad token
				}
			}
			l.setIndices.clear();
			l.setIndices.addAll(parsed);
			dirty = true;
			fireTableRowsUpdated(r, r);
		}
	}
}
