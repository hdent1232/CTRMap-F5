package ctrmap.humaninterface;

import ctrmap.Workspace;
import ctrmap.ZoneAppender;
import ctrmap.formats.encounters.EncounterTable;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.text.GFMessageFile;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;

import static ctrmap.CtrmapMainframe.*;

/**
 * The wild-encounter editor: a per-zone table of all 61 encounter slots
 * (grass/long grass/DexNav-hidden/surf/rock smash/rods/hordes) with live
 * species names. Edits go into the workspace's EN pack (the zone-count-aware
 * codec keeps every other zone byte-identical and manages the rate bytes so a
 * method can never soft-brick). "Copy from zone" clones another zone's table
 * as a starting point - the fastest way to give a custom zone sensible wild
 * Pokemon.
 */
public class EncounterEditDialog {

	/** Opens the editor for the currently loaded zone. */
	public static void show(Frame parent) {
		if (!Workspace.valid || !Workspace.isOA()) {
			JOptionPane.showMessageDialog(parent, "Load an ORAS workspace first.", "Wild encounters", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (mZonePnl == null || mZonePnl.zoneIndex < 0) {
			JOptionPane.showMessageDialog(parent, "Load a zone first (Zone tab).", "Wild encounters", JOptionPane.ERROR_MESSAGE);
			return;
		}
		final int zoneIndex = mZonePnl.zoneIndex;
		GARC zo = Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA);
		final int enIndex = zo.length - 1;
		final int zoneCount = zo.length - 2;

		byte[] pack = loadPack(zo, enIndex, zoneCount);
		if (pack == null) {
			JOptionPane.showMessageDialog(parent, "Could not read the encounter pack.", "Wild encounters", JOptionPane.ERROR_MESSAGE);
			return;
		}
		String[] species = loadSpeciesNames();
		EncounterTable start = EncounterTable.read(pack, zoneIndex);
		final EncounterTable table = start != null ? start : new EncounterTable();

		final SlotModel model = new SlotModel(table, species);
		JTable jt = new JTable(model);
		jt.getColumnModel().getColumn(0).setPreferredWidth(140);
		jt.getColumnModel().getColumn(2).setPreferredWidth(70);
		jt.getColumnModel().getColumn(3).setPreferredWidth(120);
		//double-click the species column -> the visual picker (stat/type card)
		ctrmap.humaninterface.pokepick.PokePickers.installDoubleClickPickers(jt, col
				-> col == 2 ? ctrmap.humaninterface.pokepick.PokePickers.Kind.SPECIES : null);

		final JDialog dlg = new JDialog(parent, "Wild encounters - zone " + zoneIndex, true);
		dlg.setLayout(new BorderLayout());
		dlg.add(new JLabel("  Double-click a Species to pick it visually (types + stats). Species 0 = empty slot. Rates are managed automatically."), BorderLayout.NORTH);
		dlg.add(new JScrollPane(jt), BorderLayout.CENTER);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton copyFrom = new JButton("Copy from zone...");
		JButton clear = new JButton("Clear all");
		JButton save = new JButton("Save");
		JButton cancel = new JButton("Cancel");
		buttons.add(copyFrom);
		buttons.add(clear);
		buttons.add(save);
		buttons.add(cancel);
		dlg.add(buttons, BorderLayout.SOUTH);

		final byte[] packRef = pack;
		copyFrom.addActionListener(e -> {
			String s = JOptionPane.showInputDialog(dlg, "Copy encounters from which zone number? (e.g. 23 = Route 101)");
			if (s == null) {
				return;
			}
			try {
				int src = Integer.parseInt(s.trim());
				EncounterTable other = EncounterTable.read(packRef, src);
				if (other == null) {
					JOptionPane.showMessageDialog(dlg, "Zone " + src + " has no wild data.", "Copy", JOptionPane.INFORMATION_MESSAGE);
					return;
				}
				copyInto(other, table);
				model.fireTableDataChanged();
			} catch (RuntimeException ex) {
				JOptionPane.showMessageDialog(dlg, "Could not copy: " + ex.getMessage(), "Copy", JOptionPane.ERROR_MESSAGE);
			}
		});
		clear.addActionListener(e -> {
			for (EncounterTable.Slot[] bank : table.banks) {
				for (EncounterTable.Slot sl : bank) {
					sl.species = 0;
					sl.form = 0;
					sl.minLevel = 1;
					sl.maxLevel = 1;
				}
			}
			model.fireTableDataChanged();
		});
		save.addActionListener(e -> {
			try {
				if (jt.isEditing()) {
					jt.getCellEditor().stopCellEditing();
				}
				byte[] newPack = EncounterTable.write(packRef, zoneIndex, table);
				File enFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, enIndex);
				try (FileOutputStream fos = new FileOutputStream(enFile)) {
					fos.write(newPack);
				}
				Workspace.addPersist(enFile);
				dlg.dispose();
				JOptionPane.showMessageDialog(parent,
						(table.isEmpty() ? "Wild data removed for zone " : "Wild encounters saved for zone ") + zoneIndex
						+ ".\nDeploy to emulator to apply (packs automatically).",
						"Wild encounters", JOptionPane.INFORMATION_MESSAGE);
			} catch (Exception ex) {
				JOptionPane.showMessageDialog(dlg, "Save failed:\n" + ex.getMessage(), "Wild encounters", JOptionPane.ERROR_MESSAGE);
			}
		});
		cancel.addActionListener(e -> dlg.dispose());

		dlg.setSize(640, 620);
		dlg.setLocationRelativeTo(parent);
		dlg.setVisible(true);
	}

	private static void copyInto(EncounterTable from, EncounterTable to) {
		System.arraycopy(from.rates, 0, to.rates, 0, 9);
		for (int b = 0; b < to.banks.length; b++) {
			for (int s = 0; s < to.banks[b].length; s++) {
				to.banks[b][s].species = from.banks[b][s].species;
				to.banks[b][s].form = from.banks[b][s].form;
				to.banks[b][s].minLevel = from.banks[b][s].minLevel;
				to.banks[b][s].maxLevel = from.banks[b][s].maxLevel;
			}
		}
	}

	/** The workspace EN pack: a persisted valid edit wins, else the GARC bytes. */
	private static byte[] loadPack(GARC zo, int enIndex, int zoneCount) {
		try {
			File enFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, enIndex);
			if (enFile != null && Workspace.persist_paths.contains(enFile.getAbsolutePath())) {
				byte[] cand = Files.readAllBytes(enFile.toPath());
				try {
					ZoneAppender.validateEN(cand, zoneCount);
					return cand;
				} catch (RuntimeException stale) {
					//fall through
				}
			}
			return zo.getDecompressedEntry(enIndex);
		} catch (Exception ex) {
			return null;
		}
	}

	/** Species names from GameText (entry 98; index == national dex number). */
	private static String[] loadSpeciesNames() {
		try {
			File f = Workspace.getWorkspaceFile(Workspace.ArchiveType.GAMETEXT, 98);
			List<String> lines = GFMessageFile.getStrings(Files.readAllBytes(f.toPath()));
			return lines.toArray(new String[0]);
		} catch (Exception ex) {
			return new String[0];
		}
	}

	/** 61 rows: bank / slot / species# / name / form / min / max. */
	private static class SlotModel extends AbstractTableModel {

		final EncounterTable table;
		final String[] species;
		final int[] rowBank = new int[61];
		final int[] rowSlot = new int[61];

		SlotModel(EncounterTable table, String[] species) {
			this.table = table;
			this.species = species;
			int r = 0;
			for (int b = 0; b < EncounterTable.BANK_SIZES.length; b++) {
				for (int s = 0; s < EncounterTable.BANK_SIZES[b]; s++) {
					rowBank[r] = b;
					rowSlot[r] = s;
					r++;
				}
			}
		}

		@Override
		public int getRowCount() {
			return 61;
		}

		@Override
		public int getColumnCount() {
			return 7;
		}

		@Override
		public String getColumnName(int c) {
			switch (c) {
				case 0: return "Method";
				case 1: return "Slot";
				case 2: return "Species #";
				case 3: return "Name";
				case 4: return "Form";
				case 5: return "Min Lv";
				default: return "Max Lv";
			}
		}

		@Override
		public boolean isCellEditable(int r, int c) {
			//species (col 2) opens the visual picker on double-click; form/levels are typed
			return c == 4 || c == 5 || c == 6;
		}

		private EncounterTable.Slot slot(int r) {
			return table.banks[rowBank[r]][rowSlot[r]];
		}

		@Override
		public Object getValueAt(int r, int c) {
			EncounterTable.Slot s = slot(r);
			switch (c) {
				case 0: return rowSlot[r] == 0 ? EncounterTable.BANK_NAMES[rowBank[r]] : "";
				case 1: return rowSlot[r] + 1;
				case 2: return s.species;
				case 3: return s.species == 0 ? "-" : (s.species < species.length ? species[s.species] : "#" + s.species);
				case 4: return s.form;
				case 5: return s.minLevel;
				default: return s.maxLevel;
			}
		}

		@Override
		public void setValueAt(Object v, int r, int c) {
			try {
				int val = Integer.parseInt(String.valueOf(v).trim());
				EncounterTable.Slot s = slot(r);
				switch (c) {
					case 2:
						s.species = Math.max(0, Math.min(0x7FF, val));
						if (s.species > 0 && s.minLevel <= 1 && s.maxLevel <= 1) {
							s.minLevel = 5;
							s.maxLevel = 5;
						}
						break;
					case 4:
						s.form = Math.max(0, Math.min(31, val));
						break;
					case 5:
						s.minLevel = Math.max(1, Math.min(100, val));
						if (s.maxLevel < s.minLevel) {
							s.maxLevel = s.minLevel;
						}
						break;
					case 6:
						s.maxLevel = Math.max(1, Math.min(100, val));
						if (s.minLevel > s.maxLevel) {
							s.minLevel = s.maxLevel;
						}
						break;
					default:
						break;
				}
				fireTableRowsUpdated(r, r);
			} catch (NumberFormatException ignore) {
			}
		}
	}
}
