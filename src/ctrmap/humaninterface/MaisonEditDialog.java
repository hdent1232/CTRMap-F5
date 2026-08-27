package ctrmap.humaninterface;

import ctrmap.Workspace;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.maison.MaisonSet;
import ctrmap.formats.text.GFMessageFile;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.List;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.AbstractTableModel;

import static ctrmap.CtrmapMainframe.*;

/**
 * The Battle Maison opponent editor: browses and edits the opponent Pokemon
 * "sets" (species / 4 moves / nature / held item / form) that the Maison draws
 * from. Three pools exist (a/1/8/2 standard, a/1/8/4 incl. legendaries, a/1/8/6
 * Hoenn); each is 999 fixed 16-byte records. The class-to-set-list tables decide
 * which sets each trainer class uses - editing the sets themselves re-skins the
 * opponents' teams with no code patch. Edits go to the workspace and deploy.
 */
public class MaisonEditDialog {

	private static final Workspace.ArchiveType[] POOLS = {
		Workspace.ArchiveType.MAISON_SET_POOL_A,
		Workspace.ArchiveType.MAISON_SET_POOL_B,
		Workspace.ArchiveType.MAISON_SET_POOL_C
	};
	private static final String[] POOL_NAMES = {
		"Pool A (standard, a/1/8/2)", "Pool B (+legendaries, a/1/8/4)", "Pool C (Hoenn, a/1/8/6)"
	};

	public static void show(Frame parent) {
		if (!Workspace.valid || !Workspace.isOA()) {
			JOptionPane.showMessageDialog(parent, "Load an ORAS workspace first.", "Battle Maison", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (Workspace.getArchive(POOLS[0]) == null) {
			JOptionPane.showMessageDialog(parent, "This dump has no Battle Maison data (a/1/8).", "Battle Maison", JOptionPane.ERROR_MESSAGE);
			return;
		}
		String[] species = text(98), items = text(114), moves = text(14);
		final String[] natures = NATURES;

		final JComboBox<String> poolBox = new JComboBox<>(POOL_NAMES);
		final SetModel model = new SetModel(species, items, moves, natures);
		model.loadPool(0);
		final JTable jt = new JTable(model);
		jt.getColumnModel().getColumn(1).setPreferredWidth(120);
		jt.getColumnModel().getColumn(7).setPreferredWidth(120);
		jt.getColumnModel().getColumn(8).setPreferredWidth(80);

		final JDialog dlg = new JDialog(parent, "Battle Maison opponents", true);
		dlg.setLayout(new BorderLayout());
		JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
		top.add(new JLabel("Set pool:"));
		top.add(poolBox);
		final JLabel usedLbl = new JLabel();
		top.add(usedLbl);
		dlg.add(top, BorderLayout.NORTH);
		dlg.add(new JScrollPane(jt), BorderLayout.CENTER);

		Runnable refreshUsed = () -> usedLbl.setText("   " + model.usedCount() + " / " + model.rowCount() + " sets used");
		refreshUsed.run();
		poolBox.addActionListener(e -> {
			if (model.dirty && !confirmDiscard(dlg)) {
				poolBox.setSelectedIndex(model.poolIndex);
				return;
			}
			model.loadPool(poolBox.getSelectedIndex());
			refreshUsed.run();
		});

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton save = new JButton("Save pool");
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
				refreshUsed.run();
				JOptionPane.showMessageDialog(dlg, "Saved " + POOL_NAMES[model.poolIndex]
						+ ".\nDeploy to emulator to apply.", "Battle Maison", JOptionPane.INFORMATION_MESSAGE);
			} catch (Exception ex) {
				JOptionPane.showMessageDialog(dlg, "Save failed:\n" + ex.getMessage(), "Battle Maison", JOptionPane.ERROR_MESSAGE);
			}
		});
		close.addActionListener(e -> {
			if (model.dirty && !confirmDiscard(dlg)) {
				return;
			}
			dlg.dispose();
		});

		dlg.setSize(940, 560);
		dlg.setLocationRelativeTo(parent);
		dlg.setVisible(true);
	}

	private static boolean confirmDiscard(java.awt.Component c) {
		return JOptionPane.showConfirmDialog(c, "Discard unsaved changes to this pool?", "Battle Maison",
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

	static final String[] NATURES = {
		"Hardy", "Lonely", "Brave", "Adamant", "Naughty", "Bold", "Docile", "Relaxed",
		"Impish", "Lax", "Timid", "Hasty", "Serious", "Jolly", "Naive", "Modest",
		"Mild", "Quiet", "Bashful", "Rash", "Calm", "Gentle", "Sassy", "Careful", "Quirky"
	};

	private static class SetModel extends AbstractTableModel {

		final String[] species, items, moves, natures;
		int poolIndex = 0;
		MaisonSet[] sets = new MaisonSet[0];
		boolean dirty = false;

		SetModel(String[] species, String[] items, String[] moves, String[] natures) {
			this.species = species;
			this.items = items;
			this.moves = moves;
			this.natures = natures;
		}

		void loadPool(int idx) {
			poolIndex = idx;
			GARC g = Workspace.getArchive(POOLS[idx]);
			sets = new MaisonSet[g.length];
			for (int i = 0; i < g.length; i++) {
				try {
					byte[] rec = Files.readAllBytes(Workspace.getWorkspaceFile(POOLS[idx], i).toPath());
					sets[i] = MaisonSet.read(rec);
				} catch (Exception ex) {
					sets[i] = new MaisonSet();
				}
			}
			dirty = false;
			fireTableDataChanged();
		}

		void save() throws Exception {
			for (int i = 0; i < sets.length; i++) {
				File f = Workspace.getWorkspaceFile(POOLS[poolIndex], i);
				try (FileOutputStream fos = new FileOutputStream(f)) {
					fos.write(sets[i].write());
				}
				Workspace.addPersist(f);
			}
			dirty = false;
		}

		int usedCount() {
			int n = 0;
			for (MaisonSet s : sets) {
				if (s != null && !s.isEmpty()) {
					n++;
				}
			}
			return n;
		}

		int rowCount() {
			return sets.length;
		}

		@Override
		public int getRowCount() {
			return sets.length;
		}

		@Override
		public int getColumnCount() {
			return 9;
		}

		@Override
		public String getColumnName(int c) {
			switch (c) {
				case 0: return "Set #";
				case 1: return "Species";
				case 2: return "Move 1";
				case 3: return "Move 2";
				case 4: return "Move 3";
				case 5: return "Move 4";
				case 6: return "Nature";
				case 7: return "Item";
				default: return "EV/Form";
			}
		}

		@Override
		public boolean isCellEditable(int r, int c) {
			return c != 0;
		}

		@Override
		public Object getValueAt(int r, int c) {
			MaisonSet s = sets[r];
			switch (c) {
				case 0: return r;
				case 1: return named(species, s.species);
				case 2: case 3: case 4: case 5: return named(moves, s.moves[c - 2]);
				case 6: return s.nature < natures.length ? natures[s.nature] : String.valueOf(s.nature);
				case 7: return s.heldItem == 0 ? "-" : named(items, s.heldItem);
				default: return "ev" + s.evSpreadPreset + " f" + s.formFlag;
			}
		}

		@Override
		public void setValueAt(Object v, int r, int c) {
			MaisonSet s = sets[r];
			String str = String.valueOf(v).trim();
			try {
				switch (c) {
					case 1: s.species = clampId(str, species, 0, 721); break;
					case 2: case 3: case 4: case 5: s.moves[c - 2] = clampId(str, moves, 0, 621); break;
					case 6: s.nature = clampNature(str); break;
					case 7: s.heldItem = clampId(str, items, 0, 775); break;
					default: break; // EV/Form is read-only-ish (raw), leave for now
				}
				dirty = true;
				fireTableRowsUpdated(r, r);
			} catch (NumberFormatException ignore) {
			}
		}

		/** Accepts a number or a "123 Name" string; clamps into range. */
		private int clampId(String s, String[] names, int lo, int hi) {
			int val;
			int sp = s.indexOf(' ');
			String head = sp > 0 ? s.substring(0, sp) : s;
			try {
				val = Integer.parseInt(head);
			} catch (NumberFormatException nf) {
				val = lookup(names, s);
				if (val < 0) {
					throw nf;
				}
			}
			return Math.max(lo, Math.min(hi, val));
		}

		private int clampNature(String s) {
			for (int i = 0; i < natures.length; i++) {
				if (natures[i].equalsIgnoreCase(s.trim())) {
					return i;
				}
			}
			return Math.max(0, Math.min(24, Integer.parseInt(s.trim())));
		}

		private static int lookup(String[] names, String s) {
			for (int i = 0; i < names.length; i++) {
				if (names[i] != null && names[i].equalsIgnoreCase(s.trim())) {
					return i;
				}
			}
			return -1;
		}

		private String named(String[] names, int id) {
			return id + (id < names.length && !names[id].isEmpty() ? " " + names[id] : "");
		}
	}
}
