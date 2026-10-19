package ctrmap.tests;

import ctrmap.RecordPool;
import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.formats.encounters.EncounterTable;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.maison.MaisonClassList;
import ctrmap.formats.maison.MaisonSet;
import ctrmap.formats.trainers.TrainerEntry;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import ctrmap.humaninterface.SaveHistory;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Every save the Game Data tab makes is one kept edit its history takes back and puts again (F209c).
 *
 * <p>Measured on 2026-10-08, the trainer, encounter and battle-facility saves wrote their files with no
 * history at all - the trainer's through two truncating streams and a bare write, one after another - and
 * nothing in the program could take one back. Each now runs as one WorkspaceUnit and hands the tab's history
 * what it kept. Here each Save is pressed headless, through the seam its button calls, on a made game, and
 * taken back and put again: the files byte for byte, the list of the workspace's edits with them, and the
 * editor told to read again.
 *
 * <p>The shop and item-icon saves are pressed the same way in CodeIpsTest, where their code.ips is made.
 */
public class GameDataUndoTest {

	static int fails = 0;

	static void check(boolean cond, String what) {
		System.out.println((cond ? "  ok: " : "  FAIL: ") + what);
		if (!cond) {
			fails++;
		}
	}

	public static void main(String[] args) throws Exception {
		Workspace.reset();
		try {
			WorkspaceSession ws = game();
			theTrainerListShowsOneEditorAtATime();
			trainerSave(ws);
			encounterSave(ws);
			poolSave(ws);
			tableSave(ws);
			unsavedEdits();
		} finally {
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	static SaveHistory saves() {
		return new SaveHistory(() -> Collections.<Object>emptyList());
	}

	/** A made Omega Ruby game: two trainers, three zones and their encounter pack, a set pool, a class table. */
	static WorkspaceSession game() throws Exception {
		File root = Scratch.dir("ctrmap_gamedataundo");
		File archives = new File(root, "archives");
		archives.mkdirs();
		new File(root, "workspace").mkdirs();
		WorkspaceSession ws = Sessions.bare(new File(root, "workspace"), new File(root, "game"), GameType.ORAS);
		ws = ws.withArchive(ArchiveType.TRAINER_DATA, garc(archives, "trdata", trdata(5), trdata(7)));
		ws = ws.withArchive(ArchiveType.TRAINER_POKE, garc(archives, "trpoke", trpoke(25), trpoke(133)));
		byte[] none = new byte[0];
		ws = ws.withArchive(ArchiveType.ZONE_DATA, garc(archives, "zonedata", new byte[16], new byte[16], new byte[16],
				new byte[16], EnPackTest.handMade(none, none, none)));
		ws = ws.withArchive(ArchiveType.MAISON_SET_POOL_A, garc(archives, "pool", set(25), set(151), set(0)));
		//the third class row is two bytes, too short to read: a row the editor names and will not edit (F102)
		ws = ws.withArchive(ArchiveType.MAISON_CLASS_LIST_A, garc(archives, "classes", list(1), list(2), new byte[2]));
		ws.prepareDirectories();
		Workspace.install(ws);
		return ws;
	}

	static GARC garc(File dir, String name, byte[]... entries) throws Exception {
		File f = new File(dir, name);
		HandedGameTest.writeGarc(f, entries);
		return new GARC(f);
	}

	/** A trainer of class {@code cls} with one Pokemon and no items or moves. */
	static byte[] trdata(int cls) {
		byte[] b = new byte[24];
		b[2] = (byte) cls;
		b[7] = 1;
		return b;
	}

	static byte[] trpoke(int species) {
		byte[] b = new byte[8];
		b[2] = 10;
		b[4] = (byte) species;
		b[5] = (byte) (species >> 8);
		return b;
	}

	static byte[] set(int species) {
		MaisonSet s = new MaisonSet();
		s.species = species;
		return s.write();
	}

	static byte[] list(int set) {
		MaisonClassList l = new MaisonClassList();
		l.setIndices.add(set);
		return l.write();
	}

	static byte[] bytes(File f) throws Exception {
		return f.isFile() ? Files.readAllBytes(f.toPath()) : null;
	}

	/**
	 * The trainer list opens the selected NPC's trainer beside it, one editor at a time (the 2026-10-15 shared sweep,
	 * TrainerEditDialog 128, negate-if, SURVIVED: no suite opened the list). Inverted, opening the first threw on an
	 * editor that was not there to put away, and opening again laid a second over the first. Over the made game.
	 */
	static void theTrainerListShowsOneEditorAtATime() {
		System.out.println("--- the trainer list opens the selected NPC's trainer beside it, one at a time");
		List<String> said = ctrmap.Ui.record();
		String opened;
		String again;
		try {
			javax.swing.JComponent tab = ctrmap.humaninterface.TrainerEditDialog.panelForSelection(null, 3001, () -> { },
					saves());
			opened = tab == null ? "nothing" : tab.getComponentCount() + " part(s)";
			javax.swing.AbstractButton edit = tab == null ? null : ItemEditTest.button(tab, "Edit this trainer");
			if (edit != null) {
				edit.doClick();
			}
			again = tab == null ? "nothing" : tab.getComponentCount() + " part(s)";
		} catch (RuntimeException threw) {
			opened = "threw " + threw;
			again = "-";
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check("2 part(s)".equals(opened) && "2 part(s)".equals(again), "opened for an NPC running trainer 1's script, the"
				+ " list shows trainer 1 beside it, and opening it again replaces it: " + opened + ", then " + again
				+ " (the list and one editor), " + said);
	}

	static void trainerSave(WorkspaceSession ws) throws Exception {
		System.out.println("--- the trainer editor's Save is one kept edit: record and party back together (F209c)");
		File df = ws.getWorkspaceFile(ArchiveType.TRAINER_DATA, 1);
		File pf = ws.getWorkspaceFile(ArchiveType.TRAINER_POKE, 1);
		byte[] d0 = bytes(df), p0 = bytes(pf);
		List<String> listed0 = new java.util.ArrayList<>(ws.persistPaths());
		TrainerEntry t = TrainerEntry.read(d0, p0);
		check(t.classId == 7 && t.party.size() == 1 && t.party.get(0).species == 133,
				"control: the made trainer reads back (class " + t.classId + ", species " + t.party.get(0).species + ")");
		t.classId = 9;
		t.party.get(0).species = 150;
		SaveHistory saves = saves();
		ctrmap.humaninterface.TrainerEditDialog.save(1, t, "", "", ws, saves);
		TrainerEntry saved = TrainerEntry.read(bytes(df), bytes(pf));
		check(saved.classId == 9 && saved.party.get(0).species == 150 && ws.persistPaths().contains(df.getAbsolutePath()),
				"Save writes the record and the party, and lists them as edits");
		check(saves.history().undo() && Arrays.equals(bytes(df), d0) && Arrays.equals(bytes(pf), p0)
				&& ws.persistPaths().equals(listed0),
				"Undo puts both files back byte for byte, and takes them off the list of edits");
		check(saves.history().redo() && TrainerEntry.read(bytes(df), bytes(pf)).classId == 9
				&& ws.persistPaths().contains(pf.getAbsolutePath()),
				"and Redo writes them again, listed again");
		check(Files.readAllLines(WorkspaceSession.persistFile(ws.workspaceDir()).toPath()).toString().contains(pf.getName()),
				"and the list on disk says so too - its file is put again with the others, not only the list held");
	}

	static void encounterSave(WorkspaceSession ws) throws Exception {
		System.out.println("--- the encounter editor's Save is one kept edit (F209c)");
		File en = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, 4);
		byte[] before = bytes(en);
		EncounterTable table = ctrmap.humaninterface.EncounterEditDialog.readEncounters(ws, 1);
		table.banks[0][0].species = 151;
		table.banks[0][0].minLevel = 7;
		table.banks[0][0].maxLevel = 9;
		SaveHistory saves = saves();
		ctrmap.humaninterface.EncounterEditDialog.saveEncounters(ws, 1, table, saves);
		byte[] after = bytes(en);
		check(!Arrays.equals(after, before)
				&& ctrmap.humaninterface.EncounterEditDialog.readEncounters(ws, 1).banks[0][0].species == 151,
				"control: Save writes the zone's encounters into the pack");
		check(saves.history().undo() && Arrays.equals(bytes(en), before), "Undo puts the pack back byte for byte");
		check(saves.history().redo() && Arrays.equals(bytes(en), after), "and Redo writes it again");
	}

	static void poolSave(WorkspaceSession ws) throws Exception {
		System.out.println("--- the battle-facility set editor's Save is one kept edit, the editor reading again (F209c)");
		MaisonSet[] sets = new MaisonSet[3];
		RecordPool pool = RecordPool.load(ws, ArchiveType.MAISON_SET_POOL_A, sets, MaisonSet::read, MaisonSet::new);
		File second = ws.getWorkspaceFile(ArchiveType.MAISON_SET_POOL_A, 1);
		byte[] before = bytes(second);
		sets[1].species = 249;
		int[] reread = {0};
		SaveHistory saves = saves();
		List<Integer> wrote = ctrmap.humaninterface.MaisonEditDialog.savePool("Save Pool A", ws, pool, sets, MaisonSet::write,
				() -> reread[0]++, saves);
		check(wrote.equals(Collections.singletonList(1)) && MaisonSet.read(bytes(second)).species == 249,
				"control: Save writes only the set that changed: " + wrote);
		check(saves.history().undo() && Arrays.equals(bytes(second), before) && reread[0] == 1,
				"Undo puts the set back, and the editor reads its pool again");
		check(saves.history().redo() && MaisonSet.read(bytes(second)).species == 249 && reread[0] == 2,
				"and Redo writes it again, the editor reading again");
	}

	/**
	 * What an edit in the table leaves - the model's records changed, nothing written - taken back by the history
	 * the editor is shown under, around the gesture that made it.
	 */
	@SuppressWarnings("unchecked")
	static void unsavedEdits() {
		System.out.println("--- an edit not yet saved in the battle-facility editors is taken back too (F209c)");
		SaveHistory saves = saves();
		javax.swing.JComponent sets = ctrmap.humaninterface.MaisonEditDialog.panel(null, () -> { }, (title, editor) -> { },
				saves);
		javax.swing.JComponent lists = ctrmap.humaninterface.MaisonClassListDialog.panel(null, () -> { }, saves);
		check(sets != null && lists != null, "control: both editors open on the made game");
		//Restore retail row on a game with no untouched copy says so, never nothing (the 2026-10-14 shared sweep,
		//MaisonClassListDialog 126, negate-if, SURVIVED: no suite pressed it)
		if (lists != null) {
			javax.swing.JTable rows = find(lists, javax.swing.JTable.class, null);
			javax.swing.JButton restore = find(lists, javax.swing.JButton.class, "Restore retail row");
			check(rows != null && restore != null && rows.getRowCount() > 0,
					"control: the class-assignment editor shows its rows and a Restore retail row button");
			if (rows != null && restore != null && rows.getRowCount() > 0) {
				rows.setRowSelectionInterval(0, 0);
				List<String> heard = ctrmap.Ui.record();
				try {
					restore.doClick(0);
				} finally {
					ctrmap.Ui.stopRecording();
				}
				check(heard.size() == 1 && heard.get(0).contains("No pristine snapshot is available to restore from"),
						"Restore retail row on a game with no untouched copy says so: " + heard);
				//and on a row that could not be read, refuses before anything else, naming it (F102; the 2026-10-14
				//shared sweep, MaisonClassListDialog 262, drop-throw, SURVIVED: every row a suite restored read)
				check(rows.getRowCount() == 3, "control: the made game's third class row is there to select: "
						+ rows.getRowCount() + " row(s)");
				if (rows.getRowCount() == 3) {
					rows.setRowSelectionInterval(2, 2);
					heard = ctrmap.Ui.record();
					try {
						restore.doClick(0);
					} finally {
						ctrmap.Ui.stopRecording();
					}
					check(heard.size() == 1 && heard.get(0).contains("Class #2 could not be read"),
							"Restore retail row on a row that could not be read refuses, naming it: " + heard);
				}
			}
		}
		for (javax.swing.JComponent panel : new javax.swing.JComponent[]{sets, lists}) {
			saves.around(panel);
			List<ctrmap.humaninterface.UndoHistory.Document<?>> docs = SaveHistory.documentsOf(panel);
			check(docs.size() == 1, "the editor says it edits one document in memory: " + docs.size());
			ctrmap.humaninterface.UndoHistory.Document<byte[][]> d
					= (ctrmap.humaninterface.UndoHistory.Document<byte[][]>) docs.get(0);
			byte[][] before = d.take();
			byte[][] edited = before.clone();
			edited[1] = panel == sets ? set(250) : list(9);
			saves.history().settle();
			d.put(edited);   //what an edit in the table leaves
			saves.history().settle();
			check(Arrays.deepEquals(SaveHistory.documentsOf(panel).isEmpty() ? null
					: ((ctrmap.humaninterface.UndoHistory.Document<byte[][]>) SaveHistory.documentsOf(panel).get(0)).take(), edited),
					"control: the editor holds the edit, read through its document");
			check(saves.history().undo() && Arrays.deepEquals(d.take(), before),
					d.what() + ": Undo puts back what the edit changed, before anything was saved");
			check(saves.history().redo() && Arrays.deepEquals(d.take(), edited), d.what() + ": and Redo puts it again");
		}
	}

	/** The first component of {@code type} under {@code root} - with {@code text}, when it is a button - or null. */
	static <T extends java.awt.Component> T find(java.awt.Container root, Class<T> type, String text) {
		for (java.awt.Component c : root.getComponents()) {
			if (type.isInstance(c) && (text == null || (c instanceof javax.swing.AbstractButton
					&& text.equals(((javax.swing.AbstractButton) c).getText())))) {
				return type.cast(c);
			}
			if (c instanceof java.awt.Container) {
				T inner = find((java.awt.Container) c, type, text);
				if (inner != null) {
					return inner;
				}
			}
		}
		return null;
	}

	static void tableSave(WorkspaceSession ws) throws Exception {
		System.out.println("--- the class-assignment editor's Save is one kept edit (F209c)");
		MaisonClassList[] lists = new MaisonClassList[2];
		RecordPool pool = RecordPool.load(ws, ArchiveType.MAISON_CLASS_LIST_A, lists, MaisonClassList::read,
				MaisonClassList::new);
		File first = ws.getWorkspaceFile(ArchiveType.MAISON_CLASS_LIST_A, 0);
		byte[] before = bytes(first);
		lists[0].setIndices.add(42);
		SaveHistory saves = saves();
		ctrmap.humaninterface.MaisonClassListDialog.saveTable("Save table A", ws, pool, lists, () -> { }, saves);
		byte[] after = bytes(first);
		check(!Arrays.equals(after, before), "control: Save writes the class list that changed");
		check(saves.history().undo() && Arrays.equals(bytes(first), before) && saves.history().redo()
				&& Arrays.equals(bytes(first), after), "Undo puts it back byte for byte, and Redo writes it again");
	}
}
