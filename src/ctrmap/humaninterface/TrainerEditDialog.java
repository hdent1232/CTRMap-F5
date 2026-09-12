package ctrmap.humaninterface;

import ctrmap.Workspace;
import ctrmap.formats.text.GFMessageFile;
import ctrmap.formats.trainers.TrainerEntry;
import ctrmap.gamedef.ArchiveType;
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
	public static javax.swing.JComponent panelForSelection(java.awt.Component parent,
			Integer selectedNpcScript, Runnable onClose) {
		//TRAINER_EDITING, not "is it ORAS". "Load an ORAS workspace first" was
		//an instruction a user with X/Y open could not act on and was not even
		//true - a workspace WAS loaded - and Sun/Moon got the same sentence.
		ctrmap.gamedef.GameProfile prof = Workspace.isValid() ? Workspace.profile() : null;
		if (prof == null) {
			ctrmap.Ui.error(parent, "Load a workspace first (Options > Workspace settings).", "Trainer editor");
			return null;
		}
		if (!prof.supports(ctrmap.gamedef.GameProfile.Feature.TRAINER_EDITING)) {
			ctrmap.Ui.error(parent, "Editing trainers is not available for " + prof.displayName() + "."
					+ "\n\nThe trainer archives are located, and their entry and party-member"
					+ " record layouts measured, for Omega Ruby / Alpha Sapphire only."
					+ "\n\nCTRMap refuses here rather than writing bytes into a guess.",
					"Trainer editor");
			return null;
		}
		if (Workspace.getArchive(ArchiveType.TRAINER_DATA) == null
				|| Workspace.getArchive(ArchiveType.TRAINER_POKE) == null) {
			ctrmap.Ui.error(parent, "This dump has no trainer archives.", "Trainer editor");
			return null;
		}
		//THE SELECTED NPC'S SCRIPT IS HANDED IN. This dialog reached into the main
		//window for the NPC form to read one number off the record it happens to
		//have open - so it knew there was an NPC editor, and a caller that wanted
		//the dialog for some other reason got whatever that form was showing.
		int def = 1;
		if (selectedNpcScript != null) {
			int s = selectedNpcScript;
			if (s >= 3000 && s < 5000) {
				def = s - 3000;
			} else if (s >= 5000 && s < 7000) {
				def = s - 5000;
			}
		}
		String in = (String) ctrmap.Ui.input(parent,
				"Trainer ID (1..949). A map NPC with script 3000+ID battles that trainer;\n"
				+ "the selected NPC's trainer is pre-filled when it is one.", "Input", JOptionPane.QUESTION_MESSAGE, null, def);
		if (in == null) {
			return null;
		}
		try {
			int tid = Integer.parseInt(in.trim());
			//THE SHARED BOUND, not a fifth copy of the number. This refused 948 and
			//above while the prompt one line up offered 1..949, so a user who typed
			//the number they had just been given was told they had typed it wrong -
			//and trainer 949 is real: the trainer archives hold 950 entries with 0
			//as the dummy, and TrainerDataTest round-trips every one of 1..949
			//byte-identically. Four other places already agree on 949
			//(NpcTemplates.TRAINER_ID_MAX and its own range check, the gauntlet
			//wizard, the challenge form's lineup); this was the only outlier, so it
			//now names the same constant instead of repeating the digits.
			if (tid < 1 || tid > ctrmap.formats.scripts.NpcTemplates.TRAINER_ID_MAX) {
				throw new NumberFormatException();
			}
			return panel(parent, tid, onClose);
		} catch (NumberFormatException ex) {
			ctrmap.Ui.error(parent, "Enter a trainer id between 1 and "
					+ ctrmap.formats.scripts.NpcTemplates.TRAINER_ID_MAX + ".", "Trainer editor");
		}
		return null;
	}

	/**
	 * The script of the NPC the user has selected, or null when none is.
	 *
	 * <p>Handed to {@link #showForSelection} rather than read off the NPC form:
	 * a trainer id is pre-filled from it when the script is a battle script, and
	 * that is a fact about the selection, not about which editor is on screen.
	 */
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
	public static javax.swing.JComponent panel(java.awt.Component parent, int tid,
			Runnable onClose) {
		try {
			byte[] d = Files.readAllBytes(Workspace.getWorkspaceFile(ArchiveType.TRAINER_DATA, tid).toPath());
			byte[] p = Files.readAllBytes(Workspace.getWorkspaceFile(ArchiveType.TRAINER_POKE, tid).toPath());
			final TrainerEntry t = TrainerEntry.read(d, p);
			ctrmap.gamedef.GameProfile prof = Workspace.profile();
			String[] species = text(prof.textIndex(ctrmap.gamedef.GameProfile.TextIndex.SPECIES_NAMES)),
					items = text(prof.textIndex(ctrmap.gamedef.GameProfile.TextIndex.ITEM_NAMES)),
					moves = text(prof.textIndex(ctrmap.gamedef.GameProfile.TextIndex.MOVE_NAMES)),
					classes = text(prof.textIndex(ctrmap.gamedef.GameProfile.TextIndex.TRAINER_CLASS_NAMES)),
					names = text(prof.textIndex(ctrmap.gamedef.GameProfile.TextIndex.TRAINER_NAMES));
			String trName = tid < names.length ? names[tid] : "#" + tid;
			String clName = t.classId < classes.length ? classes[t.classId] : "#" + t.classId;

			final PartyModel model = new PartyModel(t, species, items, moves);
			JTable jt = new JTable(model);
			jt.getColumnModel().getColumn(1).setPreferredWidth(110);
			jt.getColumnModel().getColumn(7).setPreferredWidth(100);
			//double-click species / item / moves -> the visual picker (preview card)
			ctrmap.humaninterface.pokepick.PokePickers.installDoubleClickPickers(jt, col
					-> col == 0 ? ctrmap.humaninterface.pokepick.PokePickers.Kind.SPECIES
					: col == 6 ? ctrmap.humaninterface.pokepick.PokePickers.Kind.ITEM
					: (col >= 8 && col <= 11) ? ctrmap.humaninterface.pokepick.PokePickers.Kind.MOVE : null);

			//A PANEL, not a window: everything under this is unchanged.
			final JPanel dlg = new JPanel();
			dlg.setLayout(new BorderLayout());
			JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
			final JSpinner classSpin = new JSpinner(new SpinnerNumberModel(t.classId, 0, 279, 1));
			final JSpinner typeSpin = new JSpinner(new SpinnerNumberModel(t.battleType, 0, 4, 1));
			final JSpinner moneySpin = new JSpinner(new SpinnerNumberModel(t.moneyRate, 0, 255, 1));
			final String[] classNames = classes;
			final JLabel classNameLbl = new JLabel();
			Runnable refreshClass = () -> {
				int c = (Integer) classSpin.getValue();
				classNameLbl.setText(" = " + (c < classNames.length ? classNames[c] : "#" + c) + "   ");
			};
			refreshClass.run();
			classSpin.addChangeListener(e -> refreshClass.run());
			top.add(new JLabel("Class:"));
			top.add(classSpin);
			top.add(classNameLbl);
			top.add(new JLabel("Battle type (0 single, 1 double):"));
			top.add(typeSpin);
			top.add(new JLabel("Money rate:"));
			top.add(moneySpin);
			//the displayed trainer NAME lives in the game text list - editable
			//here so a repurposed blank slot gets a real name in one place
			JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
			final javax.swing.JTextField nameField = new javax.swing.JTextField(
					tid < names.length && !names[tid].isEmpty() ? names[tid] : "", 16);
			nameRow.add(new JLabel("Name (shown in battle):"));
			nameRow.add(nameField);
			nameRow.add(new JLabel("Blank-named slots are unused by the retail game - safe to repurpose."));
			JPanel north = new JPanel(new java.awt.GridLayout(3, 1));
			north.add(top);
			north.add(nameRow);
			north.add(new JLabel("  Double-click a Species, Item or Move cell to pick it visually (types, stats, move category)."));
			dlg.add(north, BorderLayout.NORTH);
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
			final String origName = tid < names.length ? names[tid] : "";
			save.addActionListener(e -> {
				try {
					if (jt.isEditing()) {
						jt.getCellEditor().stopCellEditing();
					}
					t.classId = (Integer) classSpin.getValue();
					t.battleType = (Integer) typeSpin.getValue();
					t.moneyRate = (Integer) moneySpin.getValue();
					File df = Workspace.getWorkspaceFile(ArchiveType.TRAINER_DATA, tid);
					File pf = Workspace.getWorkspaceFile(ArchiveType.TRAINER_POKE, tid);
					try (FileOutputStream fos = new FileOutputStream(df)) {
						fos.write(t.toTrdata());
					}
					try (FileOutputStream fos = new FileOutputStream(pf)) {
						fos.write(t.toTrpoke());
					}
					Workspace.addPersist(df);
					Workspace.addPersist(pf);
					String newName = nameField.getText().trim();
					if (!newName.equals(origName.trim())) {
						int entry = Workspace.profile().textIndex(ctrmap.gamedef.GameProfile.TextIndex.TRAINER_NAMES);
						File nf = Workspace.getWorkspaceFile(ArchiveType.GAMETEXT, entry);
						GFMessageFile nmsg = new GFMessageFile(Files.readAllBytes(nf.toPath()));
						nmsg.setLine(tid, newName);
						Files.write(nf.toPath(), nmsg.write());
						Workspace.addPersist(nf);
					}
					onClose.run();
					ctrmap.Ui.message(parent, "Trainer " + tid + " saved. Deploy to emulator to apply.",
							"Trainer editor", JOptionPane.INFORMATION_MESSAGE);
				} catch (Exception ex) {
					ctrmap.Ui.error(dlg, "Save failed:\n" + ex.getMessage(), "Trainer editor");
				}
			});
			cancel.addActionListener(e -> onClose.run());

			return dlg;
		} catch (Exception ex) {
			ctrmap.Ui.error(parent, "Could not open trainer " + tid + ":\n" + ex.getMessage(), "Trainer editor");
		}
		return null;
	}

	private static String[] text(int entry) {
		try {
			File f = Workspace.getWorkspaceFile(ArchiveType.GAMETEXT, entry);
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
			//form/level/IV/gender-ability are typed; species(0)/item(6)/moves(8-11)
			//and the name/item display columns open the visual picker on double-click
			return c == 2 || c == 3 || c == 4 || c == 5;
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
