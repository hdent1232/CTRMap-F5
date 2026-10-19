package ctrmap.humaninterface;

import ctrmap.Workspace;
import ctrmap.formats.text.GFMessageFile;
import ctrmap.formats.trainers.TrainerEntry;
import ctrmap.gamedef.ArchiveType;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.AbstractTableModel;


/**
 * The trainer editor: edits a trainer's party (species/level/IVs/items/moves)
 * and battle settings, byte-compatible with retail/pk3DS. The zone link is the
 * point: a map NPC with script 3000+tid battles trainer tid, so the Tools
 * action defaults to the selected NPC's trainer.
 */
public class TrainerEditDialog {

	/**
	 * The Save button's work: trainer {@code tid}'s record and party, and its name when it changed, written
	 * as one change and kept in {@code saves} so it can be taken back (F209c). It wrote two files through
	 * truncating streams and the name with a bare write, one after another, and a failure part way left
	 * the first ones changed; nothing could take any of it back. Public so a suite can press it headless.
	 */
	public static void save(int tid, TrainerEntry t, String origName, String newName, ctrmap.WorkspaceSession ws,
			SaveHistory saves) throws java.io.IOException {
		File df = ws.getWorkspaceFile(ArchiveType.TRAINER_DATA, tid);
		File pf = ws.getWorkspaceFile(ArchiveType.TRAINER_POKE, tid);
		try (ctrmap.WorkspaceUnit unit = ctrmap.WorkspaceUnit.begin("Save trainer " + tid, ws)) {
			ctrmap.WorkspaceUnit.write(df, t.toTrdata());
			ctrmap.WorkspaceUnit.write(pf, t.toTrpoke());
			ws.addPersist(df);
			ws.addPersist(pf);
			if (!newName.equals(origName.trim())) {
				int entry = ws.profile().textIndex(ctrmap.gamedef.GameProfile.TextIndex.TRAINER_NAMES);
				File nf = ws.getWorkspaceFile(ArchiveType.GAMETEXT, entry);
				GFMessageFile nmsg = new GFMessageFile(Files.readAllBytes(nf.toPath()));
				nmsg.setLine(tid, newName);
				ctrmap.WorkspaceUnit.write(nf, nmsg.write());
				ws.addPersist(nf);
			}
			saves.keep(unit.commitKeeping());
		}
	}

	/**
	 * Opens the editor, defaulting to the selected NPC's trainer when it is one.
	 *
	 * @param selectedNpcScript the script of the NPC the user has selected, or null when none
	 *        is. Handed in rather than read off the NPC form: a trainer id is pre-filled from it
	 *        when the script is a battle script, and that is a fact about the selection, not
	 *        about which editor is on screen.
	 */
	public static javax.swing.JComponent panelForSelection(java.awt.Component parent,
			Integer selectedNpcScript, Runnable onClose, SaveHistory saves) {
		if (saves == null) {
			throw new IllegalArgumentException("the trainer editor must be handed the history its saves are kept in");
		}
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
		//BROWSED, NOT TYPED (census M.150): this opened on a prompt for a trainer id the program offered no way to
		//look up - 949 of them, and the only Game Data editor with nothing to browse. The trainers are listed by
		//name beside the editor, filterable, the selected NPC's chosen when it is one - in the tab, not a window
		java.util.List<String> unread = new java.util.ArrayList<>();
		String[] names = Workspace.textLines(ctrmap.formats.scripts.NpcTemplates.gametextTrainerNames(prof), unread);
		ctrmap.Ui.saidUnread(parent, unread, "Trainer editor");
		final IdChooser chooser = new IdChooser(names == null ? null : java.util.Arrays.asList(names),
				ctrmap.formats.scripts.NpcTemplates.TRAINER_ID_MAX, def);
		final JPanel tab = new JPanel(new BorderLayout(6, 0));
		JPanel list = new JPanel(new BorderLayout(0, 4));
		list.add(new JLabel("Trainers - a map NPC with script 3000+ID battles that one"), BorderLayout.NORTH);
		list.add(chooser, BorderLayout.CENTER);
		JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		JButton open = new JButton("Edit this trainer");
		JButton close = new JButton("Close");
		listButtons.add(open);
		listButtons.add(close);
		list.add(listButtons, BorderLayout.SOUTH);
		tab.add(list, BorderLayout.WEST);
		final javax.swing.JComponent[] shown = {null};
		final int[] shownId = {-1};
		//the editor's own Save and Close put it away and leave the list, to pick the next
		final Runnable putAway = () -> {
			if (shown[0] != null) {
				tab.remove(shown[0]);
				shown[0] = null;
				shownId[0] = -1;
				tab.revalidate();
				tab.repaint();
			}
		};
		final java.util.function.IntConsumer show = tid -> {
			javax.swing.JComponent next = panel(parent, tid, putAway, saves);
			if (next != null) {
				putAway.run();
				shown[0] = next;
				shownId[0] = tid;
				tab.add(next, BorderLayout.CENTER);
				tab.revalidate();
				tab.repaint();
			}
		};
		open.addActionListener(e -> {
			int tid = chooser.getId();
			if (tid < 1 || tid > ctrmap.formats.scripts.NpcTemplates.TRAINER_ID_MAX) {
				ctrmap.Ui.error(parent, "Pick a trainer from the list first.", "Trainer editor");
				return;
			}
			//ASKED, NOT DROPPED: the editor shown may hold changes nobody saved
			if (shown[0] != null && tid != shownId[0] && ctrmap.Ui.confirm(parent, "Open trainer " + tid + " instead? Changes"
					+ " to trainer " + shownId[0] + " that were not saved are dropped.", "Trainer editor", JOptionPane.YES_NO_OPTION,
					JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) {
				return;
			}
			show.accept(tid);
		});
		close.addActionListener(e -> onClose.run());
		if (selectedNpcScript != null && def >= 1) {
			show.accept(def);   //the selected NPC's trainer, opened at once, as the prompt pre-filled it
		}
		return tab;
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
	public static javax.swing.JComponent panel(java.awt.Component parent, int tid,
			Runnable onClose, SaveHistory saves) {
		if (saves == null) {
			throw new IllegalArgumentException("the trainer editor must be handed the history its saves are kept in");
		}
		try {
			byte[] d = Files.readAllBytes(Workspace.getWorkspaceFile(ArchiveType.TRAINER_DATA, tid).toPath());
			byte[] p = Files.readAllBytes(Workspace.getWorkspaceFile(ArchiveType.TRAINER_POKE, tid).toPath());
			final TrainerEntry t = TrainerEntry.read(d, p);
			ctrmap.gamedef.GameProfile prof = Workspace.profile();
			java.util.List<String> unread = new java.util.ArrayList<>();
			String[] species = Workspace.textLines(prof.textIndex(ctrmap.gamedef.GameProfile.TextIndex.SPECIES_NAMES), unread),
					items = Workspace.textLines(prof.textIndex(ctrmap.gamedef.GameProfile.TextIndex.ITEM_NAMES), unread),
					moves = Workspace.textLines(prof.textIndex(ctrmap.gamedef.GameProfile.TextIndex.MOVE_NAMES), unread),
					classes = Workspace.textLines(prof.textIndex(ctrmap.gamedef.GameProfile.TextIndex.TRAINER_CLASS_NAMES), unread),
					names = Workspace.textLines(prof.textIndex(ctrmap.gamedef.GameProfile.TextIndex.TRAINER_NAMES), unread);
			ctrmap.Ui.saidUnread(parent, unread, "Trainer");

			final PartyModel model = new PartyModel(t, species, items, moves);
			JTable jt = new JTable(model);
			jt.getColumnModel().getColumn(1).setPreferredWidth(110);
			jt.getColumnModel().getColumn(7).setPreferredWidth(100);
			//double-click species / item / moves -> the visual picker (preview card)
			ctrmap.humaninterface.pokepick.PokePickers.installDoubleClickPickers(jt, col
					-> col == 0 ? ctrmap.humaninterface.pokepick.PokePickers.Kind.SPECIES
					: col == 6 ? ctrmap.humaninterface.pokepick.PokePickers.Kind.ITEM
					: (col >= 8 && col <= 11) ? ctrmap.humaninterface.pokepick.PokePickers.Kind.MOVE : null,
					Workspace.session());

			//A PANEL, not a window: everything under this is unchanged.
			final JPanel dlg = new JPanel();
			dlg.setLayout(new BorderLayout());
			JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
			final JSpinner classSpin = new JSpinner(new SpinnerNumberModel(t.classId, 0, Math.max(t.classId, ctrmap.Workspace.profile().trainerClassMax()), 1));
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
					save(tid, t, origName, nameField.getText().trim(), Workspace.session(), saves);
					onClose.run();
					ctrmap.Ui.message(parent, "Trainer " + tid + " saved. Deploy to emulator to apply.",
							"Trainer editor", JOptionPane.INFORMATION_MESSAGE);
				} catch (Exception ex) {
					ctrmap.Ui.error(dlg, "Save failed:\n" + ctrmap.util.Bytes.reason(ex), "Trainer editor");
				}
			});
			cancel.addActionListener(e -> onClose.run());

			return dlg;
		} catch (Exception ex) {
			ctrmap.Ui.error(parent, "Could not open trainer " + tid + ":\n" + ctrmap.util.Bytes.reason(ex), "Trainer editor");
		}
		return null;
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
					case 0: m.species = Math.max(1, Math.min(ctrmap.Workspace.profile().speciesIdMax(), val)); break;
					case 2: m.form = Math.max(0, Math.min(31, val)); break;
					case 3: m.level = Math.max(1, Math.min(100, val)); break;
					case 4: m.ivByte = Math.max(0, Math.min(255, val)); break;
					case 5: m.genderAbility = Math.max(0, Math.min(0x32, val)); break;
					case 6: m.heldItem = Math.max(0, Math.min(ctrmap.Workspace.profile().itemIdMax(), val)); break;
					default: m.moves[c - 8] = Math.max(0, Math.min(ctrmap.Workspace.profile().moveIdMax(), val)); break;
				}
				fireTableRowsUpdated(r, r);
			} catch (NumberFormatException notANumber) {
				Forms.refuseTyped(null); //the cell keeps its old value; the signal says the typed one was refused (F211)
			}
		}
	}
}
