package ctrmap.humaninterface;

import ctrmap.Workspace;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.maison.MaisonClassList;
import ctrmap.gamedef.ArchiveType;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComboBox;
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

	private static final ArchiveType[] TABLES = {
		ArchiveType.MAISON_CLASS_LIST_A,
		ArchiveType.MAISON_CLASS_LIST_B
	};

	/**
	 * The two tables' labels, named from the game open NOW (F218). They were a static final array
	 * computed when the class loaded, so the labels said "?" for the whole run when the class first
	 * loaded with no workspace, and named the first game's archives after a repoint.
	 */
	static String[] tableNames() {
		return new String[]{
			"Table A (-> set pool A, " + arcName(ArchiveType.MAISON_CLASS_LIST_A) + ")",
			"Table B (-> set pool B, " + arcName(ArchiveType.MAISON_CLASS_LIST_B) + ")"
		};
	}

	/**
	 * The archive path shown in the table label, or "?" when there is nothing
	 * to ask - no game open, or a game that has no such archive.
	 *
	 * <p>This used to substitute {@code GameType.ORAS} when no workspace was
	 * open, so the labels of a dialog reached before validation named ORAS's
	 * archives whatever the user had. A label is not worth a guess.
	 */
	private static String arcName(ArchiveType t) {
		if (!Workspace.isValid()) {
			return "?";
		}
		String p = Workspace.getArchivePath(t, Workspace.game());
		return p == null ? "?" : p;
	}

	/**
	 * The Save button's work for one class table: what changed, written as one change and kept in
	 * {@code saves}, the editor re-read through {@code reread} when it is taken back or put again (F209c).
	 * Public so a suite can press it headless.
	 */
	public static java.util.List<Integer> saveTable(String name, ctrmap.WorkspaceSession ws, ctrmap.RecordPool pool,
			MaisonClassList[] rows, Runnable reread, SaveHistory saves) throws java.io.IOException {
		return MaisonEditDialog.savePool(name, ws, pool, rows, MaisonClassList::write, reread, saves);
	}

	/**
	 * The editor, as a panel for the Game Data tab.
	 *
	 * <p>It was a modal window. A feature lives in the part of the UI it belongs to,
	 * and the Game Data tab existed already, holding nothing but the button that
	 * opened this. The editing is untouched: the same table, the same saves, the same
	 * refusals - only the frame around it.
	 *
	 * @param onClose what to do when the user is finished with it; the host clears
	 * @return the editor, or null when there is nothing to edit (it says why first)
	 */
	public static javax.swing.JComponent panel(java.awt.Component parent, Runnable onClose, SaveHistory saves) {
		if (saves == null) {
			throw new IllegalArgumentException("the class-assignment editor must be handed the history its saves are kept in");
		}
		if (Workspace.getArchive(TABLES[0]) == null) {
			ctrmap.Ui.error(parent, "This dump has no facility class tables.", "Class assignments");
			return null;
		}
		java.util.List<String> unread = new java.util.ArrayList<>();
		String[] classNames = Workspace.textLines(Workspace.profile().textIndex(ctrmap.gamedef.GameProfile.TextIndex.TRAINER_CLASS_NAMES), unread);
		ctrmap.Ui.saidUnread(parent, unread, "Class assignments");
		final String[] tableNames = tableNames();
		final JComboBox<String> tableBox = new JComboBox<>(tableNames);
		final ListModel model = new ListModel(classNames, tableNames);
		model.loadTable(0);
		final JTable jt = new JTable(model);
		jt.getColumnModel().getColumn(1).setPreferredWidth(150);
		jt.getColumnModel().getColumn(2).setPreferredWidth(430);

		//A PANEL, not a window: everything under this is unchanged.
		final JPanel dlg = new JPanel();
		model.promptOwner = dlg;
		dlg.setLayout(new BorderLayout());
		JPanel north = new JPanel(new java.awt.GridLayout(2, 1));
		JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
		top.add(new JLabel("Table:"));
		top.add(tableBox);
		top.add(new JLabel("   (Set indices = comma-separated, into the paired pool)"));
		final JButton restoreRow = new JButton("Restore retail row");
		restoreRow.setToolTipText("Put the selected class's retail set list back (from the pristine snapshot).");
		top.add(restoreRow);
		north.add(top);
		north.add(new JLabel("  Every row here is ENGINE-WIDE: it chooses the teams this trainer class uses in the retail facility AND every cloned one."));
		dlg.add(north, BorderLayout.NORTH);
		dlg.add(new JScrollPane(jt), BorderLayout.CENTER);

		restoreRow.addActionListener(e -> {
			int r = jt.getSelectedRow();
			if (r < 0) {
				ctrmap.Ui.message(dlg, "Select a class row first.", "Class assignments", JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			try {
				if (!model.restoreRetailRow(r)) {
					ctrmap.Ui.error(dlg, "No pristine snapshot is available to restore from.",
							"Class assignments");
				}
			} catch (IllegalStateException unreadable) {
				ctrmap.Ui.error(dlg, ctrmap.Ui.reason(unreadable), "Class assignments");
			}
		});

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
				model.save(saves);
				ctrmap.Ui.message(dlg, "Saved " + tableNames[model.tableIndex]
						+ ".\nDeploy to emulator to apply.", "Class assignments", JOptionPane.INFORMATION_MESSAGE);
			} catch (Exception ex) {
				ctrmap.Ui.error(dlg, "Save failed:\n" + ctrmap.util.Bytes.reason(ex), "Class assignments");
			}
		});
		close.addActionListener(e -> {
			if (model.dirty && !confirmDiscard(dlg)) {
				return;
			}
			onClose.run();
		});

		//THE LISTS EDITED IN MEMORY ARE A DOCUMENT (F209c): watched under the tab's Undo while this editor is shown
		return SaveHistory.watching(dlg, () -> java.util.Collections.<UndoHistory.Document<?>>singletonList(lists(model)));
	}

	/**
	 * The class table the editor shows, as its rows' records: one document per table read, put back into that
	 * table, read again first when another one is shown.
	 */
	private static UndoHistory.Document<byte[][]> lists(final ListModel model) {
		final ctrmap.RecordPool pool = model.pool;
		final int index = model.tableIndex;
		return new UndoHistory.Document<byte[][]>() {
			@Override
			public Object subject() {
				return pool;
			}

			@Override
			public byte[][] take() {
				byte[][] out = new byte[model.lists.length][];
				for (int i = 0; i < out.length; i++) {
					out[i] = model.lists[i] == null ? null : model.lists[i].write();
				}
				return out;
			}

			@Override
			public void put(byte[][] state) {
				if (model.pool != pool) {
					model.loadTable(index);
				}
				MaisonClassList[] back = new MaisonClassList[state.length];
				for (int i = 0; i < state.length; i++) {
					back[i] = state[i] == null ? null : MaisonClassList.read(state[i]);
				}
				model.lists = back;
				model.dirty = true;
				model.fireTableDataChanged();
			}

			@Override
			public boolean same(byte[][] a, byte[][] b) {
				return java.util.Arrays.deepEquals(a, b);
			}

			@Override
			public String what() {
				return "the class lists of " + model.tableNames[index];
			}
		};
	}

	private static boolean confirmDiscard(java.awt.Component c) {
		return ctrmap.Ui.confirm(c, "Discard unsaved changes to this table?", "Class assignments",
				JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
	}

	private static class ListModel extends AbstractTableModel {

		final String[] classNames;
		/** The labels the panel showed, for the save's unit name. */
		final String[] tableNames;
		int tableIndex = 0;
		MaisonClassList[] lists = new MaisonClassList[0];
		MaisonClassList[] vanilla = null;
		/** What could not be read, and what each record held - Save writes only what changed (F102). */
		ctrmap.RecordPool pool;
		boolean dirty = false;
		/** The one-time engine-wide disclosure was ACCEPTED in this dialog instance. */
		boolean disclosed = false;
		/** Owner for the disclosure prompt (the dialog itself, set in show). */
		java.awt.Component promptOwner = null;

		ListModel(String[] classNames, String[] tableNames) {
			this.classNames = classNames;
			this.tableNames = tableNames;
		}

		void loadTable(int idx) {
			tableIndex = idx;
			GARC g = Workspace.getArchive(TABLES[idx]);
			lists = new MaisonClassList[g.length];
			//A ROW THAT CANNOT BE READ IS NAMED AND READ-ONLY (F102): it was class 0 with no sets, and
			//Save wrote that over the retail row as a 4-byte record
			pool = ctrmap.RecordPool.load(Workspace.session(), TABLES[idx], lists, MaisonClassList::read, MaisonClassList::new);
			vanilla = ctrmap.formats.maison.MaisonPoolGuard.readSnapshotLists(Workspace.session(), TABLES[idx]);
			dirty = false;
			fireTableDataChanged();
		}

		/** Puts the retail set list back into row r (snapshot-sourced). */
		boolean restoreRetailRow(int r) {
			if (pool != null && pool.unreadable(r)) {
				throw new IllegalStateException("Class #" + r + " could not be read (" + pool.why(r) + "), so it is not edited.");
			}
			if (vanilla == null || r >= vanilla.length || vanilla[r] == null) {
				return false;
			}
			lists[r].setIndices.clear();
			lists[r].setIndices.addAll(vanilla[r].setIndices);
			dirty = true;
			fireTableRowsUpdated(r, r);
			return true;
		}

		void save(SaveHistory saves) throws Exception {
			//ONLY WHAT CHANGED, never a row that could not be read, as one unit (F102) - and kept, so it can be
			//taken back (F209c)
			final int shown = tableIndex;
			saveTable("Save " + tableNames[tableIndex], Workspace.session(), pool, lists, () -> loadTable(shown), saves);
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
			if (pool != null && pool.unreadable(r)) {
				return c == 0 ? (Object) "-" : c == 1 ? "(could not be read: " + pool.why(r) + ")" : "";
			}
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
			if (pool != null && pool.unreadable(r)) {
				ctrmap.Ui.error(promptOwner, "Class #" + r + " could not be read (" + pool.why(r) + "), so it is not edited:"
						+ " saving it would replace what it holds with what is shown here.", "Class assignments");
				return;
			}
			if (!disclosed) {
				//armed until the user actually accepts - a Cancel must re-prompt
				if (ctrmap.Ui.confirm(promptOwner,
						"Class assignments are ENGINE-WIDE: this row decides which team sets\n"
						+ "EVERY facility - the retail one and every clone - draws for this trainer\n"
						+ "class. There is no per-facility copy. (\"Restore retail row\" undoes a row.)\n\n"
						+ "Continue editing?",
						"Class assignments", JOptionPane.OK_CANCEL_OPTION,
						JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
					return;
				}
				disclosed = true;
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
				} catch (NumberFormatException notANumber) {
					//the whole edit is refused on any bad token, and the list keeps what it held (F211)
					Forms.refuseTyped(null);
					return;
				}
			}
			l.setIndices.clear();
			l.setIndices.addAll(parsed);
			dirty = true;
			fireTableRowsUpdated(r, r);
		}
	}
}
