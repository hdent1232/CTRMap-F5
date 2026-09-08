package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.zone.NpcMoveCodes;
import ctrmap.formats.zone.Zone;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import ctrmap.humaninterface.NPCEditForm;
import java.io.File;
import java.io.FileOutputStream;
import java.util.TreeMap;

/**
 * The NPC form's two movement dropdowns must show a code and write back the
 * SAME code.
 *
 * <p>They did not. The translation between an NPC's movePerm2 byte and the
 * AI-motion dropdown's row was two hand-written switch statements - one each
 * way - and they disagreed on three codes. Because {@code saveEntry} reads the
 * raw spinner and the dropdown writes into that spinner, the disagreement was
 * a silent edit: picking a row in the dropdown (which a JComboBox fires even
 * when you re-pick the row already highlighted, so reading the value was
 * enough) replaced the NPC's movement type with a different one, with nothing
 * said and nothing to undo.
 *
 * <p>Measured against the pristine ORAS dump, 60 of the game's own 2904 NPCs
 * were exposed:
 * <ul>
 * <li>18 carry code 22, "Approaching diver". Row 12 showed it and wrote back
 *     11 - a code ORAS gives no name at all - because the reverse switch
 *     answered row 12 with XY's meaning before it looked at the game;</li>
 * <li>42 carry code 9. Row 6 showed it and wrote back 10, which not one NPC in
 *     the retail game uses.</li>
 * </ul>
 * A player would not see a crash; they would see a diver who no longer dives.
 *
 * <p>Both directions now come off one table in {@link NpcMoveCodes}, so they
 * cannot disagree, and this suite holds that: the round trip is checked for
 * every row of both games, in both directions, AND against every code the
 * retail dump actually contains - the last being the check that would have
 * caught the original defect, because the broken codes were real ones the
 * table's own shape said nothing about.
 *
 * Usage: java ctrmap.tests.NpcMoveCodesTest &lt;romfs-root&gt;
 */
public class NpcMoveCodesTest {

	/** Highest code worth probing; the table's largest is 23. */
	private static final int PROBE_CEILING = 64;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		tableIsABijection(GameType.XY, "XY");
		tableIsABijection(GameType.ORAS, "ORAS");
		rowsAndCodesLineUp(GameType.XY, "XY");
		rowsAndCodesLineUp(GameType.ORAS, "ORAS");
		theFormAsksTheTable();

		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump + " - the retail-code checks need ZoneData");
		} else {
			everyCodeTheGameShipsSurvivesTheDropdown(dump);
		}

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * Row to code to row, and code to row to code, for everything the table
	 * claims to know. This is the property the two switch statements broke.
	 */
	static void tableIsABijection(GameType g, String game) {
		int[] raws = NpcMoveCodes.movePerm2Raws(g);
		int bad = 0;
		for (int row = 0; row < raws.length; row++) {
			int code = NpcMoveCodes.movePerm2Raw(row, g);
			int back = NpcMoveCodes.movePerm2Index(code, g);
			if (back != row) {
				System.out.println("    " + game + " row " + row + " writes code " + code
						+ ", which shows as row " + back);
				bad++;
			}
		}
		check(bad == 0, game + ": all " + raws.length + " AI-motion rows write a code that shows as that same row");

		bad = 0;
		int known = 0;
		for (int code = 0; code <= PROBE_CEILING; code++) {
			int row = NpcMoveCodes.movePerm2Index(code, g);
			if (row < 0) {
				continue;
			}
			known++;
			if (row >= raws.length) {
				System.out.println("    " + game + " code " + code + " shows as row " + row
						+ ", past the end of a " + raws.length + "-row dropdown");
				bad++;
				continue;
			}
			int back = NpcMoveCodes.movePerm2Raw(row, g);
			if (back != code) {
				System.out.println("    " + game + " code " + code + " shows as row " + row
						+ ", which writes back code " + back);
				bad++;
			}
		}
		check(bad == 0, game + ": all " + known + " named codes survive a trip through the dropdown");
		check(known == raws.length, game + ": exactly one code per row (" + known + " named, " + raws.length + " rows)");
		check(NpcMoveCodes.movePerm2Index(PROBE_CEILING + 1, g) == -1,
				game + ": a code with no name says so rather than picking a neighbour");
		check(NpcMoveCodes.movePerm2Raw(-1, g) == -1 && NpcMoveCodes.movePerm2Raw(raws.length, g) == -1,
				game + ": no selection, and a row past the end, write no code");
	}

	/** A dropdown with more names than codes (or fewer) would mislabel rows. */
	static void rowsAndCodesLineUp(GameType g, String game) {
		String[] labels = NpcMoveCodes.movePerm2Labels(g);
		int[] raws = NpcMoveCodes.movePerm2Raws(g);
		check(labels.length == raws.length, game + ": " + labels.length + " AI-motion names for "
				+ raws.length + " codes - one name per code");
		check(labels.length >= 14, game + ": the AI-motion list is populated (" + labels.length + " rows)");
		check(NpcMoveCodes.movePerm1Labels(g).length >= 62,
				game + ": the movement list is populated (" + NpcMoveCodes.movePerm1Labels(g).length + " rows)");
		//movePerm1 is dense - the form selects row N for code N with no
		//translation at all, so a gap in this list would silently relabel every
		//code above it.
		for (String l : NpcMoveCodes.movePerm1Labels(g)) {
			if (l == null || l.isEmpty()) {
				check(false, game + ": every movement row has a name");
				return;
			}
		}
	}

	/**
	 * The form must not keep a second copy of the answer. It used to BE the
	 * copy, so a table nobody consults is exactly the failure to guard against.
	 */
	static void theFormAsksTheTable() {
		for (GameType g : new GameType[]{GameType.ORAS, GameType.XY}) {
			Sessions.bare(new File("no-workspace"), new File("no-game"), g);
			NPCEditForm form = new NPCEditForm();
			int bad = 0;
			for (int code = 0; code <= PROBE_CEILING; code++) {
				bad += form.getMot2Index(code) == NpcMoveCodes.movePerm2Index(code, g) ? 0 : 1;
			}
			for (int row = -1; row <= NpcMoveCodes.movePerm2Raws(g).length; row++) {
				bad += form.getMot2Raw(row) == NpcMoveCodes.movePerm2Raw(row, g) ? 0 : 1;
			}
			check(bad == 0, g + ": the form's dropdown translation is the table's, not a copy of it");
		}
	}

	/**
	 * Every movePerm2 code the retail game puts on an NPC must round-trip, and
	 * every movePerm1 code must have a row to be shown in.
	 *
	 * <p>This is the check with teeth. The table's own shape cannot tell you
	 * that code 22 matters; the game can, and it says 18 NPCs wear it.
	 */
	static void everyCodeTheGameShipsSurvivesTheDropdown(File dump) throws Exception {
		Sessions.bare(new File("no-workspace"), dump, GameType.ORAS);
		GARC zo = new GARC(new File(dump.getAbsolutePath()
				+ Workspace.getArchivePath(ArchiveType.ZONE_DATA, Workspace.game())));
		File tmp = Scratch.file("ctrmap_movecodes");
		TreeMap<Integer, Integer> move2 = new TreeMap<>();
		TreeMap<Integer, Integer> move1 = new TreeMap<>();
		int npcs = 0, zones = 0;
		//the last two ZoneData entries are the master table and the EN pack
		for (int i = 0; i < zo.length - 2; i++) {
			byte[] raw = zo.getDecompressedEntry(i);
			if (raw == null) {
				continue;
			}
			FileOutputStream fos = new FileOutputStream(tmp);
			try {
				fos.write(raw);
			} finally {
				fos.close();
			}
			ZoneEntities e;
			try {
				e = new Zone(new ZO(tmp), Workspace.game()).entities;
			} catch (Exception ex) {
				continue;
			}
			if (e == null) {
				continue;
			}
			zones++;
			for (ZoneEntities.NPC n : e.npcs) {
				npcs++;
				tally(move2, n.movePerm2);
				tally(move1, n.movePerm1);
			}
		}
		check(zones >= 500 && npcs >= 2000, "read " + npcs + " NPCs from " + zones + " retail zones");

		int rows = NpcMoveCodes.movePerm2Raws(GameType.ORAS).length;
		StringBuilder lost = new StringBuilder();
		StringBuilder nameless = new StringBuilder();
		for (java.util.Map.Entry<Integer, Integer> en : move2.entrySet()) {
			int code = en.getKey();
			int row = NpcMoveCodes.movePerm2Index(code, GameType.ORAS);
			if (row < 0) {
				//A code with no row cannot be shown, cannot be chosen, and reads
				//as an empty dropdown on an NPC that plainly does something. All
				//eight the game ships have names; dropping one is a regression,
				//not a discovery.
				nameless.append("\n    code ").append(code).append(" is worn by ")
						.append(en.getValue()).append(" NPC(s) and has no row to show it in");
				continue;
			}
			int back = row < rows ? NpcMoveCodes.movePerm2Raw(row, GameType.ORAS) : -1;
			if (back != code) {
				lost.append("\n    code ").append(code).append(" on ").append(en.getValue())
						.append(" NPC(s) shows as row ").append(row).append(" and writes back ").append(back);
			}
		}
		check(lost.length() == 0, "every AI-motion code the retail game uses ("
				+ move2.keySet() + ") is written back unchanged" + lost);
		check(nameless.length() == 0, "every AI-motion code the retail game uses has a row to show it in" + nameless);

		int names = NpcMoveCodes.movePerm1Labels(GameType.ORAS).length;
		StringBuilder unnamed = new StringBuilder();
		for (java.util.Map.Entry<Integer, Integer> en : move1.entrySet()) {
			if (en.getKey() < 0 || en.getKey() >= names) {
				unnamed.append("\n    movement code ").append(en.getKey()).append(" on ")
						.append(en.getValue()).append(" NPC(s) has no row in a ").append(names)
						.append("-row list");
			}
		}
		check(unnamed.length() == 0, "every movement code the retail game uses has a row to show it in" + unnamed);
	}

	private static void tally(TreeMap<Integer, Integer> m, int k) {
		Integer c = m.get(k);
		m.put(k, c == null ? 1 : c + 1);
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
		if (!ok) {
			fails++;
		}
	}
}
