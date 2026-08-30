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
 * The battle facility opponent editor: browses and edits the opponent Pokemon
 * "sets" (species / 4 moves / nature / held item / form) that the facility
 * engine draws from. Three pools exist (a/1/8/2 standard, a/1/8/4 incl.
 * legendaries, a/1/8/6 Hoenn); each is 999 fixed 16-byte records. The
 * class-to-set-list tables decide which sets each trainer class uses.
 *
 * <p>The pools are ENGINE-WIDE - the retail facility and every cloned custom
 * facility battle from the same data - so the editor is vanilla-safe by
 * default: retail rows (per the pristine snapshot) are marked and guarded, and
 * new teams go into the pools' free slots ({@link
 * ctrmap.formats.maison.MaisonPoolGuard}). Edits go to the workspace and deploy.
 */
public class MaisonEditDialog {

	private static final Workspace.ArchiveType[] POOLS = {
		Workspace.ArchiveType.MAISON_SET_POOL_A,
		Workspace.ArchiveType.MAISON_SET_POOL_B,
		Workspace.ArchiveType.MAISON_SET_POOL_C
	};
	private static final String[] POOL_NAMES = {
		"Pool A (standard)", "Pool B (+legendaries)", "Pool C (Hoenn)"
	};

	public static void show(Frame parent) {
		if (!Workspace.valid || !Workspace.isOA()) {
			JOptionPane.showMessageDialog(parent, "Load an ORAS workspace first.", "Battle facility opponents", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (Workspace.getArchive(POOLS[0]) == null) {
			JOptionPane.showMessageDialog(parent, "This dump has no battle facility opponent data.", "Battle facility opponents", JOptionPane.ERROR_MESSAGE);
			return;
		}
		ctrmap.gamedef.GameProfile prof = Workspace.profile();
		String[] species = text(prof.textIndex(ctrmap.gamedef.GameProfile.TextIndex.SPECIES_NAMES)),
				items = text(prof.textIndex(ctrmap.gamedef.GameProfile.TextIndex.ITEM_NAMES)),
				moves = text(prof.textIndex(ctrmap.gamedef.GameProfile.TextIndex.MOVE_NAMES));
		final String[] natures = NATURES;

		final JComboBox<String> poolBox = new JComboBox<>(POOL_NAMES);
		final SetModel model = new SetModel(species, items, moves, natures);
		model.loadPool(0);
		final JTable jt = new JTable(model);
		model.table = jt;
		jt.getColumnModel().getColumn(1).setPreferredWidth(120);
		jt.getColumnModel().getColumn(7).setPreferredWidth(120);
		jt.getColumnModel().getColumn(8).setPreferredWidth(80);
		//retail rows draw greyed - they are the shipped game's data; free rows
		//are the authoring space for custom facilities
		final javax.swing.table.DefaultTableCellRenderer guardRenderer = new javax.swing.table.DefaultTableCellRenderer() {
			@Override
			public java.awt.Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
				java.awt.Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
				if (!sel) {
					boolean retail = model.isRetail(r);
					comp.setBackground(retail ? new java.awt.Color(0xED, 0xED, 0xED) : java.awt.Color.WHITE);
					comp.setForeground(retail ? new java.awt.Color(0x60, 0x60, 0x60) : java.awt.Color.BLACK);
				}
				return comp;
			}
		};
		jt.setDefaultRenderer(Object.class, guardRenderer);
		//double-click species / moves / item -> the visual picker (with preview card)
		ctrmap.humaninterface.pokepick.PokePickers.installDoubleClickPickers(jt, col
				-> col == 1 ? ctrmap.humaninterface.pokepick.PokePickers.Kind.SPECIES
				: (col >= 2 && col <= 5) ? ctrmap.humaninterface.pokepick.PokePickers.Kind.MOVE
				: col == 7 ? ctrmap.humaninterface.pokepick.PokePickers.Kind.ITEM : null);

		final JDialog dlg = new JDialog(parent, "Battle facility opponents", true);
		dlg.setLayout(new BorderLayout());
		JPanel north = new JPanel(new java.awt.GridLayout(3, 1));
		JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
		top.add(new JLabel("Set pool:"));
		top.add(poolBox);
		final JLabel usedLbl = new JLabel();
		top.add(usedLbl);
		final JButton goFree = new JButton("Go to first free slot");
		goFree.setToolTipText("Jump to the first slot that is yours to author - empty and unused by the retail game.");
		top.add(goFree);
		north.add(top);
		north.add(new JLabel("  These pools are ENGINE-WIDE: the retail facility and every cloned custom facility battle from them."));
		north.add(new JLabel("  Grey rows are retail data (guarded); free rows are yours. Double-click a Species, Move or Item to pick visually."));
		dlg.add(north, BorderLayout.NORTH);
		dlg.add(new JScrollPane(jt), BorderLayout.CENTER);

		Runnable refreshUsed = () -> usedLbl.setText("   " + model.usedCount() + " / " + model.rowCount()
				+ " sets used, " + model.guard.freeCount() + " free"
				+ (model.guard.exact ? "" : " (no pristine snapshot - non-empty rows treated as retail)"));
		refreshUsed.run();
		goFree.addActionListener(e -> {
			int free = model.guard.firstFreeSlot(model.sets);
			if (free < 0) {
				JOptionPane.showMessageDialog(dlg, "No free slot left in this pool.", "Battle facility opponents", JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			jt.getSelectionModel().setSelectionInterval(free, free);
			jt.scrollRectToVisible(jt.getCellRect(free, 0, true));
		});
		poolBox.addActionListener(e -> {
			if (model.dirty && !confirmDiscard(dlg)) {
				poolBox.setSelectedIndex(model.poolIndex);
				return;
			}
			model.loadPool(poolBox.getSelectedIndex());
			refreshUsed.run();
		});

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton classes = new JButton("Class assignments...");
		JButton save = new JButton("Save pool");
		JButton close = new JButton("Close");
		buttons.add(classes);
		buttons.add(save);
		buttons.add(close);
		dlg.add(buttons, BorderLayout.SOUTH);

		classes.addActionListener(e -> MaisonClassListDialog.show(dlg));

		save.addActionListener(e -> {
			try {
				if (jt.isEditing()) {
					jt.getCellEditor().stopCellEditing();
				}
				model.save();
				refreshUsed.run();
				JOptionPane.showMessageDialog(dlg, "Saved " + POOL_NAMES[model.poolIndex]
						+ ".\nDeploy to emulator to apply.", "Battle facility opponents", JOptionPane.INFORMATION_MESSAGE);
			} catch (Exception ex) {
				JOptionPane.showMessageDialog(dlg, "Save failed:\n" + ex.getMessage(), "Battle facility opponents", JOptionPane.ERROR_MESSAGE);
			}
		});
		close.addActionListener(e -> {
			if (model.dirty && !confirmDiscard(dlg)) {
				return;
			}
			dlg.dispose();
		});

		dlg.setSize(960, 580);
		dlg.setLocationRelativeTo(parent);
		dlg.setVisible(true);
	}

	private static boolean confirmDiscard(java.awt.Component c) {
		return JOptionPane.showConfirmDialog(c, "Discard unsaved changes to this pool?", "Battle facility opponents",
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

		/** The class-list table pairing of each pool (C has none). */
		private static final Workspace.ArchiveType[] PAIRED_LISTS = {
			Workspace.ArchiveType.MAISON_CLASS_LIST_A,
			Workspace.ArchiveType.MAISON_CLASS_LIST_B,
			null
		};

		final String[] species, items, moves, natures;
		int poolIndex = 0;
		MaisonSet[] sets = new MaisonSet[0];
		ctrmap.formats.maison.MaisonPoolGuard guard;
		boolean dirty = false;
		/** The user explicitly chose to edit retail data in this dialog instance. */
		boolean allowVanillaEdits = false;
		JTable table;

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
			guard = ctrmap.formats.maison.MaisonPoolGuard.load(POOLS[idx], PAIRED_LISTS[idx], sets);
			dirty = false;
			fireTableDataChanged();
		}

		boolean isRetail(int r) {
			return guard != null && r < guard.vanillaUsed.length && guard.vanillaUsed[r];
		}

		/** Copies row {@code r} into the first free slot; returns it or -1. */
		int copyToFreeSlot(int r) {
			int free = guard.firstFreeSlot(sets);
			if (free < 0) {
				return -1;
			}
			MaisonSet src = sets[r];
			MaisonSet cp = new MaisonSet();
			cp.species = src.species;
			System.arraycopy(src.moves, 0, cp.moves, 0, cp.moves.length);
			cp.evSpreadPreset = src.evSpreadPreset;
			cp.nature = src.nature;
			cp.heldItem = src.heldItem;
			cp.formFlag = src.formFlag;
			sets[free] = cp;
			dirty = true;
			fireTableRowsUpdated(free, free);
			return free;
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
			return c == 6; //nature is typed; species/moves/item open the visual picker on double-click
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
			//validate the value FIRST - an unparseable edit must not trigger the
			//guard flow (or create a stray free-slot copy)
			if (!parses(v, c)) {
				return;
			}
			//vanilla stays vanilla by default: editing a retail row asks first,
			//with "author in a free slot instead" as the safe primary choice
			int row = r;
			if (isRetail(r) && !allowVanillaEdits) {
				String[] opts = {"Copy to a free slot", "Edit the retail data", "Cancel"};
				int pick = JOptionPane.showOptionDialog(table,
						"Set #" + r + " is RETAIL data - the retail facility (and every cloned\n"
						+ "facility) battles with it. Editing it changes the shipped game.\n\n"
						+ "Copy this set to a free slot and put your edit there instead?",
						"Battle facility opponents", JOptionPane.DEFAULT_OPTION,
						JOptionPane.WARNING_MESSAGE, null, opts, opts[0]);
				if (pick == 0) {
					int free = copyToFreeSlot(r);
					if (free < 0) {
						JOptionPane.showMessageDialog(table, "No free slot left in this pool.",
								"Battle facility opponents", JOptionPane.ERROR_MESSAGE);
						return;
					}
					row = free;
					if (table != null) {
						table.getSelectionModel().setSelectionInterval(free, free);
						table.scrollRectToVisible(table.getCellRect(free, 0, true));
					}
				} else if (pick == 1) {
					allowVanillaEdits = true; // this dialog instance only
				} else {
					return;
				}
			}
			MaisonSet s = sets[row];
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
				fireTableRowsUpdated(row, row);
			} catch (NumberFormatException ignore) {
			}
		}

		/** True when the typed value will be accepted for column c. */
		private boolean parses(Object v, int c) {
			String str = String.valueOf(v).trim();
			try {
				switch (c) {
					case 1: clampId(str, species, 0, 721); break;
					case 2: case 3: case 4: case 5: clampId(str, moves, 0, 621); break;
					case 6: clampNature(str); break;
					case 7: clampId(str, items, 0, 775); break;
					default: return false; // EV/Form stays read-only
				}
				return true;
			} catch (NumberFormatException ex) {
				return false;
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
