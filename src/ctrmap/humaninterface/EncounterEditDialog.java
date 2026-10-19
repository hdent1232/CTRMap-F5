package ctrmap.humaninterface;

import ctrmap.LoadedZone;
import ctrmap.Workspace;
import ctrmap.formats.encounters.EncounterTable;
import ctrmap.formats.garc.GARC;
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
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;


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

	/**
	 * Every zone of the game, labelled by LocationNames.zoneLabel, in zone order - what Copy from zone offers (census M.149). From
	 * the master zone-header table, one entry, rather than opening every zone as the Zone Loader does.
	 */
	public static java.util.List<String> zoneChoices(ctrmap.formats.GameFiles game) throws java.io.IOException {
		ctrmap.MasterTable master = ctrmap.MasterTable.read(game);
		java.util.List<String> out = new java.util.ArrayList<>();
		for (int z = 0; z < master.zones(); z++) {
			out.add(ctrmap.formats.text.LocationNames.zoneLabel(game,
					ctrmap.formats.zone.ZoneHeader.parentMapOf(master.row(z)), z));
		}
		return out;
	}

	/**
	 * Reads one zone's wild encounters out of the EN pack.
	 *
	 * <p>Half of the seam under this dialog. Editing encounters, packing and
	 * editing again is a real workflow with real state between the steps - the EN
	 * pack is ONE archive entry holding every zone's table, so a second edit reads
	 * what the first one wrote - and nothing drove it, because the only way in was
	 * a modal dialog. WorkflowGuardsTest recorded that as a named hole rather than
	 * leaving it to be rediscovered; this is the hole being filled.
	 *
	 * @return the zone's table, or an empty one when it has no wild data
	 */
	public static EncounterTable readEncounters(ctrmap.WorkspaceSession ws, int zoneIndex)
			throws java.io.IOException {
		GARC zo = ws == null ? null : ws.getArchive(ArchiveType.ZONE_DATA);
		if (zo == null) {
			throw new java.io.IOException("No workspace is loaded.");
		}
		byte[] pack = loadPack(zo, ctrmap.ZoneTables.encounterPackIndex(zo, ws.profile()),
				ctrmap.ZoneTables.zoneCount(zo, ws.profile()));
		if (pack == null) {
			throw new java.io.IOException("Could not read the encounter pack.");
		}
		EncounterTable t = EncounterTable.read(pack, zoneIndex);
		return t != null ? t : new EncounterTable();
	}

	/**
	 * Writes one zone's wild encounters back into the EN pack and marks it edited.
	 *
	 * <p>The other half. It returns the sentence the user is shown rather than
	 * showing it, for the same reason every other report in this program does: a
	 * sentence built inside a dialog call is a sentence no test has ever seen.
	 *
	 * @return what to tell the user
	 */
	public static String saveEncounters(ctrmap.WorkspaceSession ws, int zoneIndex,
			EncounterTable table, SaveHistory saves) throws java.io.IOException {
		GARC zo = ws == null ? null : ws.getArchive(ArchiveType.ZONE_DATA);
		if (zo == null) {
			throw new java.io.IOException("No workspace is loaded.");
		}
		int enIndex = ctrmap.ZoneTables.encounterPackIndex(zo, ws.profile());
		byte[] pack = loadPack(zo, enIndex, ctrmap.ZoneTables.zoneCount(zo, ws.profile()));
		if (pack == null) {
			throw new java.io.IOException("Could not read the encounter pack.");
		}
		byte[] newPack = EncounterTable.write(pack, zoneIndex, table);
		File enFile = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, enIndex);
		if (enFile == null) {
			throw new java.io.IOException("The encounter pack could not be extracted.");
		}
		//ONE KEPT CHANGE (F209c): written through the unit, so the pack is copied before it changes and the save
		//can be taken back - it was a bare write nothing could put back
		try (ctrmap.WorkspaceUnit unit = ctrmap.WorkspaceUnit.begin("Save wild encounters of zone " + zoneIndex, ws)) {
			ctrmap.WorkspaceUnit.write(enFile, newPack);
			ws.addPersist(enFile);
			saves.keep(unit.commitKeeping());
		}
		return (table.isEmpty() ? "Wild data removed for zone " : "Wild encounters saved for zone ")
			+ zoneIndex + ".\nDeploy to emulator to apply (packs automatically).";
	}

	/**
	 * The wild-encounter editor, as a panel for the Game Data tab.
	 *
	 * <p>It was a modal window. A feature lives in the part of the UI it belongs to,
	 * and the Game Data tab existed already, holding nothing but the button that
	 * opened this. Nothing about the editing changed: the same table, the same
	 * seam ({@link #saveEncounters}), the same refusals - only the frame around it.
	 *
	 * @param onClose what to do when the user is finished with it; the host clears
	 * @return the editor, or null when there is nothing to edit (it says why first)
	 */
	public static javax.swing.JComponent panel(java.awt.Component parent, LoadedZone loaded,
			Runnable onClose, SaveHistory saves) {
		if (saves == null) {
			throw new IllegalArgumentException("the encounter editor must be handed the history its saves are kept in");
		}
		if (loaded == null) {
			throw new IllegalArgumentException("EncounterEditDialog must be handed the LoadedZone");
		}
		//ENCOUNTERS, not "is it ORAS": the EN pack's slot in the zone archive
		//and its 61-slot record layout were measured on ORAS and checked
		//nowhere else. The old gate said "Load an ORAS workspace first" to a
		//user who already had X/Y loaded.
		ctrmap.gamedef.GameProfile prof = Workspace.isValid() ? Workspace.profile() : null;
		if (prof == null) {
			ctrmap.Ui.error(parent, "Load a workspace first (Options > Workspace settings).", "Wild encounters");
			return null;
		}
		if (!prof.supports(ctrmap.gamedef.GameProfile.Feature.ENCOUNTERS)) {
			ctrmap.Ui.error(parent, "Editing wild encounters is not available for "
					+ prof.displayName() + "."
					+ "\n\nThe wild-encounter pack's slot at the end of the zone archive, and"
					+ " the 61-slot table inside it, were measured on Omega Ruby / Alpha"
					+ " Sapphire."
					+ "\n\nCTRMap refuses here rather than rewriting whatever this game keeps"
					+ " in that slot.",
					"Wild encounters");
			return null;
		}
		if (loaded.index() < 0) {
			ctrmap.Ui.error(parent, "Load a zone first (Zone tab).", "Wild encounters");
			return null;
		}
		final int zoneIndex = loaded.index();
		//THROUGH THE SEAM, like the save (F204): this read was the dialog's own copy of
		//readEncounters - with the zone count taken without the profile - so the read the
		//suites drove was never the one the editor ran
		final EncounterTable table;
		try {
			table = readEncounters(Workspace.session(), zoneIndex);
		} catch (java.io.IOException ex) {
			ctrmap.Ui.error(parent, ctrmap.Ui.reason(ex), "Wild encounters");
			return null;
		}
		java.util.List<String> unread = new java.util.ArrayList<>();
		String[] species = Workspace.textLines(Workspace.profile().textIndex(ctrmap.gamedef.GameProfile.TextIndex.SPECIES_NAMES), unread);
		ctrmap.Ui.saidUnread(parent, unread, "Wild encounters");

		final SlotModel model = new SlotModel(table, species);
		JTable jt = new JTable(model);
		jt.getColumnModel().getColumn(0).setPreferredWidth(140);
		jt.getColumnModel().getColumn(2).setPreferredWidth(70);
		jt.getColumnModel().getColumn(3).setPreferredWidth(120);
		//double-click the species column -> the visual picker (stat/type card)
		ctrmap.humaninterface.pokepick.PokePickers.installDoubleClickPickers(jt, col
				-> col == 2 ? ctrmap.humaninterface.pokepick.PokePickers.Kind.SPECIES : null, Workspace.session());

		//A PANEL, not a window. Everything below is unchanged: the same adds, the same
		//listeners, the same Ui calls - a JPanel answers all of them.
		final JPanel dlg = new JPanel(new BorderLayout());
		dlg.add(new JLabel("  Double-click a Species to pick it visually (types + stats). Species 0 = empty slot. Rates are managed automatically."), BorderLayout.NORTH);
		dlg.add(new JScrollPane(jt), BorderLayout.CENTER);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton copyFrom = new JButton("Copy from zone...");
		JButton clear = new JButton("Clear all");
		JButton save = new JButton("Save");
		JButton cancel = new JButton("Close");
		buttons.add(copyFrom);
		buttons.add(clear);
		buttons.add(save);
		buttons.add(cancel);
		dlg.add(buttons, BorderLayout.SOUTH);

		copyFrom.addActionListener(e -> copyFrom(dlg, Workspace.session(), table, model::fireTableDataChanged));
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
				//through the seam, so what the dialog does and what a suite can drive are
				//the same code rather than two copies that agree today
				String said = saveEncounters(Workspace.session(), zoneIndex, table, saves);
				onClose.run();
				ctrmap.Ui.message(parent, said, "Wild encounters", JOptionPane.INFORMATION_MESSAGE);
			} catch (Exception ex) {
				ctrmap.Ui.error(dlg, "Save failed:\n" + ctrmap.util.Bytes.reason(ex), "Wild encounters");
			}
		});
		cancel.addActionListener(e -> onClose.run());

		return dlg;
	}

	/**
	 * Asks which zone's wild encounters to copy and copies them into {@code table}: whether it did. A cancel, a zone
	 * with no wild data and a table that will not read copy nothing, the last two saying so. Out of the editor so a
	 * suite reaches it (the 2026-10-14 shared sweep, EncounterEditDialog 207, negate-if, SURVIVED: no suite pressed Copy).
	 *
	 * <p>PICKED BY NAME, NOT TYPED AS A NUMBER (census M.149): the user had to know a route's zone id, and a
	 * wrong-but-valid number copied another route's table into this zone.
	 *
	 * <p>AND SHOWN HERE (2026-10-16): the editor redrew its table when this answered true, in an if of its own - the
	 * 2026-10-14 guard asked the answer, and that if was what survived (the assembly, EncounterEditDialog 195). The
	 * redraw is {@code shown}, run once a copy has landed and not otherwise.
	 */
	public static boolean copyFrom(java.awt.Component dlg, ctrmap.WorkspaceSession ws, EncounterTable table, Runnable shown) {
		java.util.List<String> zones;
		try {
			zones = zoneChoices(ws);
		} catch (java.io.IOException noList) {
			ctrmap.Ui.error(dlg, "The zone list could not be read: " + ctrmap.util.Bytes.reason(noList), "Copy");
			return false;
		}
		Object picked = ctrmap.Ui.input(dlg, "Copy the wild encounters of which zone?", "Copy from zone",
				JOptionPane.QUESTION_MESSAGE, zones.toArray(), null);
		int src = picked == null ? -1 : zones.indexOf(picked.toString());
		if (src < 0) {
			return false;
		}
		try {
			EncounterTable other = readEncounters(ws, src);
			if (other.isEmpty()) {
				ctrmap.Ui.message(dlg, "Zone " + src + " has no wild data.", "Copy", JOptionPane.INFORMATION_MESSAGE);
				return false;
			}
			copyInto(other, table);
			shown.run();
			return true;
		} catch (java.io.IOException | RuntimeException ex) {
			ctrmap.Ui.error(dlg, "Could not copy: " + ctrmap.util.Bytes.reason(ex), "Copy");
			return false;
		}
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
			File enFile = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, enIndex);
			if (Workspace.isPendingArtifact(enFile)) {
				byte[] cand = Files.readAllBytes(enFile.toPath());
				try {
					ctrmap.formats.encounters.EnPack.parse(cand, zoneCount);
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
			} catch (NumberFormatException notANumber) {
				Forms.refuseTyped(null); //the cell keeps its old value; the signal says the typed one was refused (F211)
			}
		}
	}
}
