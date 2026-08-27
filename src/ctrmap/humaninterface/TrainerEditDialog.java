package ctrmap.humaninterface;

import ctrmap.Workspace;
import ctrmap.formats.text.GFMessageFile;
import ctrmap.formats.trainers.TrainerEntry;
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
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.AbstractTableModel;

import static ctrmap.CtrmapMainframe.*;

/**
 * The trainer editor: edits a trainer's party (species/level/IVs/items/moves)
 * and battle settings, byte-compatible with retail/pk3DS. The zone link is the
 * point: a map NPC with script 3000+tid battles trainer tid, so the Tools
 * action defaults to the selected NPC's trainer.
 */
public class TrainerEditDialog {

	/** Opens the editor, defaulting to the selected NPC's trainer when it is one. */
	public static void showForSelection(Frame parent) {
		if (!Workspace.valid || !Workspace.isOA()) {
			JOptionPane.showMessageDialog(parent, "Load an ORAS workspace first.", "Trainer editor", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (Workspace.getArchive(Workspace.ArchiveType.TRAINER_DATA) == null
				|| Workspace.getArchive(Workspace.ArchiveType.TRAINER_POKE) == null) {
			JOptionPane.showMessageDialog(parent, "This dump has no trainer archives (a/0/3/6 + a/0/3/8).", "Trainer editor", JOptionPane.ERROR_MESSAGE);
			return;
		}
		int def = 1;
		if (mNPCEditForm != null && mNPCEditForm.npc != null) {
			int s = mNPCEditForm.npc.script;
			if (s >= 3000 && s < 5000) {
				def = s - 3000;
			} else if (s >= 5000 && s < 7000) {
				def = s - 5000;
			}
		}
		String in = JOptionPane.showInputDialog(parent,
				"Trainer ID (1..949). A map NPC with script 3000+ID battles that trainer;\n"
				+ "the selected NPC's trainer is pre-filled when it is one.", def);
		if (in == null) {
			return;
		}
		try {
			int tid = Integer.parseInt(in.trim());
			if (tid < 1 || tid > 948) {
				throw new NumberFormatException();
			}
			show(parent, tid);
		} catch (NumberFormatException ex) {
			JOptionPane.showMessageDialog(parent, "Enter a trainer id between 1 and 948.", "Trainer editor", JOptionPane.ERROR_MESSAGE);
		}
	}

	public static void show(Frame parent, int tid) {
		try {
			byte[] d = Files.readAllBytes(Workspace.getWorkspaceFile(Workspace.ArchiveType.TRAINER_DATA, tid).toPath());
			byte[] p = Files.readAllBytes(Workspace.getWorkspaceFile(Workspace.ArchiveType.TRAINER_POKE, tid).toPath());
			final TrainerEntry t = TrainerEntry.read(d, p);
			String[] species = text(98), items = text(114), moves = text(14), classes = text(21), names = text(22);
			String trName = tid < names.length ? names[tid] : "#" + tid;
			String clName = t.classId < classes.length ? classes[t.classId] : "#" + t.classId;

			final PartyModel model = new PartyModel(t, species, items, moves);
			JTable jt = new JTable(model);
			jt.getColumnModel().getColumn(1).setPreferredWidth(110);
			jt.getColumnModel().getColumn(7).setPreferredWidth(100);

			final JDialog dlg = new JDialog(parent, "Trainer " + tid + " - " + clName + " " + trName, true);
			dlg.setLayout(new BorderLayout());
			JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
			final JSpinner classSpin = new JSpinner(new SpinnerNumberModel(t.classId, 0, 279, 1));
			final JSpinner typeSpin = new JSpinner(new SpinnerNumberModel(t.battleType, 0, 4, 1));
			final JSpinner moneySpin = new JSpinner(new SpinnerNumberModel(t.moneyRate, 0, 255, 1));
			top.add(new JLabel("Class:"));
			top.add(classSpin);
			top.add(new JLabel("Battle type (0 single, 1 double):"));
			top.add(typeSpin);
			top.add(new JLabel("Money rate:"));
			top.add(moneySpin);
			dlg.add(top, BorderLayout.NORTH);
			dlg.add(new JScrollPane(jt), BorderLayout.CENTER);

			JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
			JButton addMon = new JButton("Add Pokemon");
			JButton delMon = new JButton("Remove last");
			JButton save = new JButton("Save");
			JButton cancel = new JButton("Cancel");
			buttons.add(addMon);
			buttons.add(delMon);
			buttons.add(save);
			buttons.add(cancel);
			dlg.add(buttons, BorderLayout.SOUTH);

			addMon.addActionListener(e -> {
				if (t.party.size() < 6) {
					TrainerEntry.PartyMon m = new TrainerEntry.PartyMon();
					m.species = 261;
					m.level = 5;
					t.party.add(m);
					model.fireTableDataChanged();
				}
			});
			delMon.addActionListener(e -> {
				if (t.party.size() > 1) {
					t.party.remove(t.party.size() - 1);
					model.fireTableDataChanged();
				}
			});
			save.addActionListener(e -> {
				try {
					if (jt.isEditing()) {
						jt.getCellEditor().stopCellEditing();
					}
					t.classId = (Integer) classSpin.getValue();
					t.battleType = (Integer) typeSpin.getValue();
					t.moneyRate = (Integer) moneySpin.getValue();
					File df = Workspace.getWorkspaceFile(Workspace.ArchiveType.TRAINER_DATA, tid);
					File pf = Workspace.getWorkspaceFile(Workspace.ArchiveType.TRAINER_POKE, tid);
					try (FileOutputStream fos = new FileOutputStream(df)) {
						fos.write(t.toTrdata());
					}
					try (FileOutputStream fos = new FileOutputStream(pf)) {
						fos.write(t.toTrpoke());
					}
					Workspace.addPersist(df);
					Workspace.addPersist(pf);
					dlg.dispose();
					JOptionPane.showMessageDialog(parent, "Trainer " + tid + " saved. Deploy to emulator to apply.",
							"Trainer editor", JOptionPane.INFORMATION_MESSAGE);
				} catch (Exception ex) {
					JOptionPane.showMessageDialog(dlg, "Save failed:\n" + ex.getMessage(), "Trainer editor", JOptionPane.ERROR_MESSAGE);
				}
			});
			cancel.addActionListener(e -> dlg.dispose());

			dlg.setSize(860, 360);
			dlg.setLocationRelativeTo(parent);
			dlg.setVisible(true);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(parent, "Could not open trainer " + tid + ":\n" + ex.getMessage(), "Trainer editor", JOptionPane.ERROR_MESSAGE);
		}
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

	private static class PartyModel extends AbstractTableModel {

		final TrainerEntry t;
		final String[] species, items, moves;

		PartyModel(TrainerEntry t, String[] species, String[] items, String[] moves) {
			this.t = t;
			this.species = species;
			this.items = items;
			this.moves = moves;
		}

		@Override
		public int getRowCount() {
			return t.party.size();
		}

		@Override
		public int getColumnCount() {
			return 12;
		}

		@Override
		public String getColumnName(int c) {
			switch (c) {
				case 0: return "Species #";
				case 1: return "Name";
				case 2: return "Form";
				case 3: return "Level";
				case 4: return "IV byte";
				case 5: return "G/A byte";
				case 6: return "Item #";
				case 7: return "Item";
				default: return "Move " + (c - 7);
			}
		}

		@Override
		public boolean isCellEditable(int r, int c) {
			return c != 1 && c != 7;
		}

		@Override
		public Object getValueAt(int r, int c) {
			TrainerEntry.PartyMon m = t.party.get(r);
			switch (c) {
				case 0: return m.species;
				case 1: return m.species < species.length ? species[m.species] : "#" + m.species;
				case 2: return m.form;
				case 3: return m.level;
				case 4: return m.ivByte;
				case 5: return m.genderAbility;
				case 6: return m.heldItem;
				case 7: return m.heldItem == 0 ? "-" : (m.heldItem < items.length ? items[m.heldItem] : "#" + m.heldItem);
				default: {
					int mv = m.moves[c - 8];
					return mv == 0 ? "0" : mv + " " + (mv < moves.length ? moves[mv] : "");
				}
			}
		}

		@Override
		public void setValueAt(Object v, int r, int c) {
			try {
				String s = String.valueOf(v).trim();
				int val = Integer.parseInt(s.contains(" ") ? s.substring(0, s.indexOf(' ')) : s);
				TrainerEntry.PartyMon m = t.party.get(r);
				switch (c) {
					case 0: m.species = Math.max(1, Math.min(721, val)); break;
					case 2: m.form = Math.max(0, Math.min(31, val)); break;
					case 3: m.level = Math.max(1, Math.min(100, val)); break;
					case 4: m.ivByte = Math.max(0, Math.min(255, val)); break;
					case 5: m.genderAbility = Math.max(0, Math.min(0x32, val)); break;
					case 6: m.heldItem = Math.max(0, Math.min(775, val)); break;
					default: m.moves[c - 8] = Math.max(0, Math.min(621, val)); break;
				}
				fireTableRowsUpdated(r, r);
			} catch (NumberFormatException ignore) {
			}
		}
	}
}
