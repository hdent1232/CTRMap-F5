package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.LoadedZone;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.zone.WarpTransitions;
import ctrmap.formats.zone.Zone;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import ctrmap.humaninterface.WarpEditForm;
import java.io.File;
import java.io.FileOutputStream;
import java.util.TreeMap;

/**
 * The warp form's transition dropdown must show a code and write back the
 * SAME code.
 *
 * <p>It did not. The translation between a warp's transition byte and the
 * dropdown row was two hand-written switch statements - one each way - the
 * same shape NpcMoveCodesTest guards for the NPC form, and broken the same
 * way: XY row 10 was selected by code 55 and wrote back 22, a game-neutral
 * first switch leaked one game's codes into the other's rows, and a code
 * neither switch knew selected no row and then saved as -1.
 *
 * <p>Measured against the pristine ORAS dump (911 warps in 536 zones) every
 * retail ORAS code has a row and survives the trip; the XY defect needs an XY
 * dump to observe and this project has none, so the table is held by
 * construction instead: both directions come off one array in
 * {@link WarpTransitions}, and this suite asserts the round trip for every
 * row of both games, that the form asks the table rather than keeping a copy,
 * and that a code the table does not know is preserved through a save.
 *
 * Usage: java ctrmap.tests.WarpTransitionsTest &lt;pristine-garc-root&gt;
 */
public class WarpTransitionsTest {
	/** The redraw the forms here are handed: what frame.repaint() was, but readable. */
	static final Redraws REDRAW = new Redraws();


	/** Highest code worth probing; the table's largest is 57. */
	private static final int PROBE_CEILING = 96;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		tableIsABijection(GameType.XY, "XY");
		tableIsABijection(GameType.ORAS, "ORAS");
		rowsAndCodesLineUp(GameType.XY, "XY");
		rowsAndCodesLineUp(GameType.ORAS, "ORAS");
		theFormAsksTheTable();
		theFormKeepsACodeItCannotName();

		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump + " - the retail-code check needs ZoneData");
		} else {
			everyCodeTheGameShipsSurvivesTheDropdown(dump);
		}

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** Row to code to row, and code to row to code - the property the switches broke. */
	static void tableIsABijection(GameType g, String game) {
		int[] raws = WarpTransitions.raws(g);
		int bad = 0;
		for (int row = 0; row < raws.length; row++) {
			int code = WarpTransitions.raw(row, g);
			int back = WarpTransitions.index(code, g);
			if (back != row) {
				System.out.println("    " + game + " row " + row + " writes code " + code + ", which shows as row " + back);
				bad++;
			}
		}
		check(bad == 0, game + ": all " + raws.length + " transition rows write a code that shows as that same row");

		bad = 0;
		int known = 0;
		for (int code = 0; code <= PROBE_CEILING; code++) {
			int row = WarpTransitions.index(code, g);
			if (row < 0) {
				continue;
			}
			known++;
			int back = WarpTransitions.raw(row, g);
			if (back != code) {
				System.out.println("    " + game + " code " + code + " shows as row " + row + ", which writes back code " + back);
				bad++;
			}
		}
		check(bad == 0, game + ": all " + known + " named codes survive a trip through the dropdown");
		check(known == raws.length, game + ": exactly one code per row (" + known + " named, " + raws.length + " rows)");
		check(WarpTransitions.index(PROBE_CEILING + 1, g) == -1, game + ": a code with no name says so rather than picking a neighbour");
		check(WarpTransitions.raw(-1, g) == -1 && WarpTransitions.raw(raws.length, g) == -1,
				game + ": no selection, and a row past the end, write no code");
		//the defect that was measured on the old XY switch: 55 must be writable
		check(WarpTransitions.index(55, g) >= 0 && WarpTransitions.raw(WarpTransitions.index(55, g), g) == 55,
				game + ": code 55 has a row, and that row writes 55 back");
	}

	static void rowsAndCodesLineUp(GameType g, String game) {
		String[] labels = WarpTransitions.labels(g);
		int[] raws = WarpTransitions.raws(g);
		check(labels.length == raws.length, game + ": " + labels.length + " names for " + raws.length + " codes - one name per code");
		check(labels.length >= 17, game + ": the list is populated (" + labels.length + " rows)");
		for (String l : labels) {
			if (l == null || l.isEmpty()) {
				check(false, game + ": every row has a name");
				return;
			}
		}
		//the six shared rows lead, in code order, for both games
		check(raws[0] == 0 && raws[1] == 2 && raws[5] == 6, game + ": the shared rows come first");
	}

	/** The form must not keep a second copy of the answer - it used to BE the copy. */
	static void theFormAsksTheTable() throws Exception {
		for (GameType g : new GameType[]{GameType.ORAS, GameType.XY}) {
			game(g);
			WarpEditForm form = new WarpEditForm(new LoadedZone(), REDRAW);
			form.fillTransitionDropdown();
			int bad = 0;
			for (int code = 0; code <= PROBE_CEILING; code++) {
				bad += form.getTransitionIndex(code) == WarpTransitions.index(code, g) ? 0 : 1;
			}
			for (int row = -1; row <= WarpTransitions.raws(g).length; row++) {
				bad += form.getTransitionRaw(row) == WarpTransitions.raw(row, g) ? 0 : 1;
			}
			check(bad == 0, g + ": the form's dropdown translation is the table's, not a copy of it");
			check(form.transitionModel.getSize() == WarpTransitions.labels(g).length,
					g + ": the dropdown has one row per table row (" + form.transitionModel.getSize() + ")");
		}
	}

	/**
	 * What a save writes for the dropdown's row: the row's code, and - for no
	 * selection, which is how a code with no name is shown - the record's own
	 * code, untouched. The old form wrote {@code getTransitionRaw(-1)} here,
	 * which is -1: a warp wearing a code the editor had not heard of was
	 * corrupted by opening it and pressing save.
	 *
	 * <p>Checked through the form's own method rather than through saveEntry,
	 * because saveEntry reaches CtrmapMainframe for its dropdown labels and
	 * cannot run without the main window.
	 */
	static void theFormKeepsACodeItCannotName() throws Exception {
		game(GameType.XY);
		WarpEditForm form = new WarpEditForm(new LoadedZone(), REDRAW);
		form.fillTransitionDropdown();
		check(form.transitionToWrite(-1, 99) == 99, "XY: no selection keeps the record's code 99, not -1: " + form.transitionToWrite(-1, 99));
		check(form.transitionToWrite(-1, 0) == 0, "XY: no selection keeps code 0 too");
		int row55 = WarpTransitions.index(55, GameType.XY);
		check(form.transitionToWrite(row55, 99) == 55, "XY: a selected row writes its own code: " + form.transitionToWrite(row55, 99));
		game(GameType.ORAS);
		check(form.transitionToWrite(-1, 26) == 26, "ORAS: no selection keeps the record's code 26");
		check(form.transitionToWrite(WarpTransitions.index(26, GameType.ORAS), 0) == 26, "ORAS: the row for 26 writes 26");
	}

	/**
	 * Every transition code the retail game puts on a warp must have a row and
	 * round-trip.
	 */
	static void everyCodeTheGameShipsSurvivesTheDropdown(File dump) throws Exception {
		Sessions.bare(Scratch.dir("ctrmap_warptransitions_ws"), dump, GameType.ORAS);
		GARC zo = new GARC(new File(dump.getAbsolutePath()
				+ Workspace.getArchivePath(ArchiveType.ZONE_DATA, Workspace.game())));
		File tmp = Scratch.file("ctrmap_warptransitions");
		TreeMap<Integer, Integer> codes = new TreeMap<>();
		int warps = 0, zones = 0;
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
				e = new Zone(new ZO(tmp, Workspace.session()), Workspace.game()).entities;
			} catch (Exception ex) {
				continue;
			}
			if (e == null) {
				continue;
			}
			zones++;
			for (ZoneEntities.Warp w : e.warps) {
				warps++;
				codes.merge(w.transitionType, 1, Integer::sum);
			}
		}
		tmp.delete();
		check(zones >= 500 && warps >= 900, "the retail dump was read: " + warps + " warps in " + zones + " zones");
		int bad = 0;
		for (java.util.Map.Entry<Integer, Integer> en : codes.entrySet()) {
			int code = en.getKey();
			int row = WarpTransitions.index(code, GameType.ORAS);
			int back = WarpTransitions.raw(row, GameType.ORAS);
			if (row < 0 || back != code) {
				System.out.println("    code " + code + " on " + en.getValue() + " warp(s): row " + row + ", writes back " + back);
				bad++;
			}
		}
		check(bad == 0, "every transition code a retail ORAS warp wears (" + codes.size()
				+ " distinct) has a row and writes itself back");
	}

	static void check(boolean ok, String what) {
		System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what);
		if (!ok) {
			fails++;
		}
	}

	/**
	 * A session that is nothing but a game type: all the form asks Workspace for.
	 * Two scratch folders stand in for the workspace and the game; no archive opens.
	 */
	static void game(GameType g) throws Exception {
		Sessions.bare(Scratch.dir("ctrmap_warptransitions_ws"), Scratch.dir("ctrmap_warptransitions_game"), g);
	}
}
