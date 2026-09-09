package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.LoadedZone;
import ctrmap.Ui;
import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.text.LocationNames;
import ctrmap.formats.zone.Zone;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import ctrmap.humaninterface.NPCEditForm;
import ctrmap.humaninterface.TriggerEditForm;
import ctrmap.humaninterface.WarpEditForm;
import ctrmap.humaninterface.ZoneLoadingPanel;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;

/**
 * The three public mutable fields the zone panel holds, and every transition
 * between the states they can be in.
 *
 * <p>WHY THIS SUITE EXISTS. {@code zones}, {@code zone} and {@code zoneIndex}
 * are public, mutable and read from all over the editor: the NPC, warp, trigger
 * and paint forms, four menu actions, the cloner and the appender all index
 * into them. A later step gives them an owner, and the only thing that makes
 * that safe is knowing exactly what each transition leaves behind today.
 *
 * <p>The three that matter, and what goes wrong if one moves:
 *
 * <ul>
 * <li>REBUILDING THE LIST fills {@code zones} and the dropdown and opens
 *     nothing. If it left {@code zoneIndex} pointing at the old zone, every
 *     reader would index a fresh array with a stale number - which, after a
 *     removal, is an index into a zone that no longer exists.</li>
 * <li>A LOAD THAT FAILED must leave "no zone open", not half of one. Covered
 *     for the reporting by DataSafetyGuardsTest; what is covered here is the
 *     unload itself, driven directly, including the editors it has to take
 *     down with it.</li>
 * <li>A ZONE TABLE THAT CANNOT BE READ must leave an empty list rather than a
 *     list of the last game's zones. {@code Workspace.isValid()} answers from
 *     the session, which was opened from the paths and not from the archives'
 *     contents, so it is no help; {@code getLoadedZoneCount} is the honest
 *     answer and this pins that it stays honest.</li>
 * </ul>
 *
 * <p>The header form is checked against the BYTES, because that is the only
 * assertion that catches the interesting failure: the dropdowns are stored as
 * indices and written back through hand-written lookup tables, so a table with
 * a gap silently rewrites a zone's map type or weather on the next save of any
 * zone the user merely LOOKED at. The tables are swept over the whole retail
 * corpus and the round trip is then driven end to end on real zones.
 *
 * <p>Out of scope, and skipped loudly: opening a zone from the dropdown drives
 * the tilemap, matrix, camera, prop and NPC editors and ends in the 3D view.
 * DataSafetyGuardsTest already builds that whole set of forms to check the
 * failure report; this suite does not repeat it.
 *
 * Usage: java ctrmap.tests.ZoneLoadingStateTest &lt;pristine dump root&gt;
 */
public class ZoneLoadingStateTest {


	/** Writing the zone and re-showing its script, so both can be read back. */
	static final RecordingZoneSaver SAVER = new RecordingZoneSaver();

	/** Where the user is looking, so a placement can be asserted at all. */
	static final RecordingCentre CENTRE = new RecordingCentre();

	/** The 3D gizmo these forms move, so what they told it can be read back. */
	static final RecordingNavi NAVI = new RecordingNavi();
	/** The editors that show the zone, for the panels here: a spy that records and clears. */
	static final ZoneEditorsSpy ZONE_EDITORS = new ZoneEditorsSpy();

	/** The editor set the panels here flush: it records instead of saving. */
	static final RecordingEditors EDITORS = new RecordingEditors();

	/** The redraw the forms here are handed: what frame.repaint() was, but readable. */
	static final Redraws REDRAW = new Redraws();

	/** The tool this suite holds: its own, so another suite may hold another. */
	static final ctrmap.humaninterface.tools.ToolSelection TOOLS = new ctrmap.humaninterface.tools.ToolSelection();


	/** Fallarbor Town: shares map matrix 8 with Routes 111 to 114. */
	private static final int SHARED_ZONE = 10;
	/** Mauville: its map matrix is its own in the retail game. */
	private static final int PRIVATE_ZONE = 15;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");

		//needs no game: a panel that has never been given one, and the two
		//lookup tables, which are pure arithmetic
		aFreshPanelHoldsNoZone();
		theDropdownTablesAgreeWithThemselves();
		theZoneButtonsRefuseWhatTheyCannotDo();
		theZoneTabRefusesToBeBuiltWithoutTheEditors();

		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump + " - the state transitions need a zone table");
		} else {
			ScratchGame.open(dump);
			LocationNames.loadFromGarc(Workspace.session());
			LoadedZone lz = new LoadedZone();
			ZoneLoadingPanel pnl = new ZoneLoadingPanel(lz, TOOLS, EDITORS, ZONE_EDITORS, NAVI);
			CtrmapMainframe.mZonePnl = pnl;
			CtrmapMainframe.mNPCEditForm = new NPCEditForm(lz, TOOLS, REDRAW, NAVI, CENTRE, SAVER, SAVER, CtrmapMainframe.mTileMapPanel);
			CtrmapMainframe.mWarpEditForm = new WarpEditForm(lz, REDRAW, CENTRE);
			CtrmapMainframe.mTriggerEditForm = new TriggerEditForm(lz, REDRAW, CENTRE);

			theListIsTheArchivesZonesAndNothingIsOpened(pnl, lz);
			everyRetailHeaderSurvivesTheDropdowns(pnl, lz);
			aLoadedZoneIsWrittenBackUnchanged(pnl, lz);
			aZoneFromAFileTouchesNobodysTableSlot(pnl, lz);
			aHeaderTheFormCannotShowIsNotWrittenOverAnother(pnl, lz);
			selectingOutsideTheListChangesNothing(pnl);
			unloadingClearsTheZoneAndTheEditorsWithIt(pnl, lz);
			aSharedMapIsOfferedAForkAndADeclineIsRemembered(pnl, lz);
			//LAST: it replaces the zone table with one that cannot be read
			aZoneTableThatCannotBeReadLeavesNoZones(pnl, lz);
		}

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * The Zone tab refuses to be built without the editors it saves and the
	 * editors it shows a zone to.
	 *
	 * <p>WHY: it was handed null for both. The window assigned the two lists
	 * where they read best - after the editors they name - and built the Zone
	 * tab sixty lines earlier, so every save and every zone switch would have
	 * thrown at the moment the user asked for it. The window's own rule
	 * (MainframeEdgesTest) is what stops that being written again; this is the
	 * second line, for anything that builds a Zone tab some other way.
	 */
	static void theZoneTabRefusesToBeBuiltWithoutTheEditors() {
		System.out.println("--- the Zone tab refuses to be built without the editors it saves and shows");
		check(refusal(null, ZONE_EDITORS).contains("must be handed the editors"),
				"with nothing to save: " + refusal(null, ZONE_EDITORS));
		check(refusal(EDITORS, null).contains("must be handed the editors"),
				"and with nobody to show a zone to: " + refusal(EDITORS, null));
	}

	/** What building a Zone tab with these two says, or that it said nothing. */
	static String refusal(ctrmap.humaninterface.OpenEditors editors, ctrmap.humaninterface.ZoneEditors views) {
		try {
			new ZoneLoadingPanel(new LoadedZone(), TOOLS, editors, views, NAVI);
		} catch (IllegalArgumentException refused) {
			return String.valueOf(refused.getMessage());
		}
		return "(nothing was thrown)";
	}

	// ---- states reachable without a game ------------------------------------

	/**
	 * The state a panel is in before anything has been loaded into it.
	 *
	 * <p>-1 and null, not 0 and a blank zone: every reader tests for exactly
	 * these. {@code zoneIndex == 0} would mean "the first zone is open", which
	 * is what the menu actions default their spinners to and what the cloner
	 * would use as a source.
	 */
	static void aFreshPanelHoldsNoZone() {
		System.out.println("--- a panel that has never been given a game");
		LoadedZone lz = new LoadedZone();
		ZoneLoadingPanel pnl = new ZoneLoadingPanel(lz, TOOLS, EDITORS, ZONE_EDITORS, NAVI);
		check(lz.count() == 0, "no zone table");
		check(lz.open() == null, "no open zone");
		check(lz.index() == -1, "and the index is -1, not 0 - nothing is open (got " + lz.index() + ")");
		check(pnl.getLoadedZoneCount() == 0, "and the dropdown is empty, which is the honest count");
		check(pnl.store(false), "storing nothing succeeds trivially rather than throwing");
	}

	/**
	 * The two dropdowns whose value is not what the file holds.
	 *
	 * <p>Weather and map type are shown as list positions and written back
	 * through hand-written lookup tables, one pair per game. Every one of those
	 * tables is a switch with named cases and a default, so a missing case does
	 * not fail: it returns the default and the zone is written back with a
	 * different weather than it had. The user's only clue is the weather
	 * changing in a town they did not edit.
	 *
	 * <p>So both directions are required to agree with each other over every
	 * position the dropdown can actually be in - which is what the editor can
	 * produce - for both games.
	 */
	static void theDropdownTablesAgreeWithThemselves() throws Exception {
		System.out.println("--- the weather and map-type tables agree with themselves");
		ZoneLoadingPanel pnl = new ZoneLoadingPanel(new LoadedZone(), TOOLS, EDITORS, ZONE_EDITORS, NAVI);
		WorkspaceSession was = Workspace.session();

		openAs(GameType.XY);
		List<Integer> weatherBad = new ArrayList<>();
		for (int index = 0; index <= 24; index++) {   //XY's weather list is 25 long
			if (pnl.getWeatherIndex(pnl.getWeatherRaw(index)) != index) {
				weatherBad.add(index);
			}
		}
		check(weatherBad.isEmpty(),
				"XY: all 25 weather positions survive being written and read back " + weatherBad);
		List<Integer> typeBad = new ArrayList<>();
		for (int index = 0; index <= 7; index++) {    //XY's map-type list is 8 long
			if (pnl.getTypeIndex(pnl.getTypeRaw(index)) != index) {
				typeBad.add(index);
			}
		}
		check(typeBad.isEmpty(), "XY: all 8 map-type positions survive the same trip " + typeBad);

		openAs(GameType.ORAS);
		weatherBad.clear();
		for (int index = 0; index <= 9; index++) {    //ORAS's weather list is 10 long
			if (pnl.getWeatherIndex(pnl.getWeatherRaw(index)) != index) {
				weatherBad.add(index);
			}
		}
		check(weatherBad.isEmpty(), "ORAS: weather is stored raw, so all 10 positions survive " + weatherBad);
		typeBad.clear();
		for (int index = 0; index <= 1; index++) {    //ORAS's map-type list is only 2 long
			if (pnl.getTypeIndex(pnl.getTypeRaw(index)) != index) {
				typeBad.add(index);
			}
		}
		check(typeBad.isEmpty(), "ORAS: both map-type positions survive " + typeBad);

		//PINNED AS IS: the ORAS table is not one-to-one in the other direction.
		//Raw 1 and raw 3 both read as position 1, and position 1 writes 3, so a
		//header holding mapType 1 comes back as 3. No retail ORAS zone holds 1
		//- the corpus sweep below is what proves that - so the game is not
		//damaged today; the collision is real and is reported, not fixed here.
		check(pnl.getTypeIndex(1) == 1 && pnl.getTypeRaw(1) == 3,
				"ORAS raw map types 1 and 3 share position 1, and that position writes 3");
		check(pnl.getTypeIndex(3) == 1, "so a header holding 1 would be written back as 3");
		Workspace.install(was);
	}

	/**
	 * The two zone buttons refuse before they touch the archive.
	 *
	 * <p>Both begin by flushing every open editor, which is a chain of six
	 * saves that can each ask the user a question. Reaching that with no zone
	 * loaded means asking about edits to nothing; reaching the appender on XY
	 * means writing an ORAS-shaped zone table into an XY game. The refusals are
	 * one line each and neither was measured.
	 *
	 * <p>THE ADD BUTTON'S WORDS CHANGED. It used to say "Adding new zones is
	 * ORAS-only in v1." to every game that was not ORAS - a sentence about the
	 * editor's release history, identical for X/Y, Sun/Moon and Ultra
	 * Sun/Moon. It now names the game the user has open and what is missing
	 * for it, and the assertion follows. It also grew a guard IN FRONT: with
	 * no workspace at all it says so first, rather than asking a profile that
	 * does not exist which game it is.
	 */
	static void theZoneButtonsRefuseWhatTheyCannotDo() throws Exception {
		System.out.println("--- the Clone and Add buttons refuse before they flush anything");
		LoadedZone lz = new LoadedZone();
		ZoneLoadingPanel pnl = new ZoneLoadingPanel(lz, TOOLS, EDITORS, ZONE_EDITORS, NAVI);
		WorkspaceSession was = Workspace.session();

		openAs(GameType.ORAS);
		List<String> said = press(pnl, "btnCloneZoneActionPerformed");
		check(said.size() == 1 && said.get(0).equals("Clone zone: Load the source zone from the dropdown first."),
				"Clone with nothing open names the dropdown to use: " + said);

		Workspace.install(null);
		said = press(pnl, "btnAddZoneActionPerformed");
		check(said.size() == 1 && said.get(0).equals(
				"Add new zones: Load a workspace first (Options > Workspace settings)."),
				"Add zones with no workspace at all refuses before it asks which game: " + said);

		openAs(GameType.XY);
		said = press(pnl, "btnAddZoneActionPerformed");
		check(said.size() == 1 && said.get(0).equals("Add new zones: Adding new zones is not available for X / Y."
				+ "\n\nZones past the ones a game ships need that game's zone-table limit"
				+ " found in its executable and raised by a code patch. Both were measured"
				+ " for Omega Ruby / Alpha Sapphire only, so CTRMap refuses here rather"
				+ " than writing a zone this game would never load."),
				"Add zones on XY names the game the user has open, not a version number: " + said);

		openAs(GameType.ORAS);
		said = press(pnl, "btnAddZoneActionPerformed");
		check(said.size() == 1 && said.get(0).equals("Add new zones: Load a workspace first."),
				"Add zones with no zone list loaded says so: " + said);
		lz.table(new Zone[0]);
		said = press(pnl, "btnAddZoneActionPerformed");
		check(said.size() == 1 && said.get(0).equals("Add new zones: Load a workspace first."),
				"and an EMPTY zone list reads the same way, not as a workspace with no zones: " + said);
		Workspace.install(was);
	}

	/**
	 * Opens a workspace on the game given, with no archive in it.
	 *
	 * <p>The stand-in for the two statics these checks used to assign. The
	 * dropdown tables and the two buttons ask which game is open and nothing
	 * else; an archive-less session is exactly that much workspace, and it is
	 * a state the application reaches on its own when a load fails part way.
	 */
	static void openAs(GameType game) throws Exception {
		File root = Scratch.dir("ctrmap_zoneloading_session");
		Sessions.bare(new File(root, "ws"), new File(root, "game"), game);
	}

	// ---- states that need a real zone table ---------------------------------

	/**
	 * Rebuilding the list fills it from the archive and opens nothing.
	 *
	 * <p>Two facts, and the second is the one that is easy to lose. The list
	 * has to hold every zone the archive holds bar the master table and the EN
	 * pack, because its positions ARE zone indices everywhere else in the
	 * editor - an off-by-one here renames every zone in the dropdown by one
	 * place. And the rebuild must not leave a zone open, because the array it
	 * was in has just been replaced: after "Remove added zones" the old index
	 * points past the end of the new list.
	 */
	static void theListIsTheArchivesZonesAndNothingIsOpened(ZoneLoadingPanel pnl, LoadedZone lz) throws Exception {
		System.out.println("--- rebuilding the list fills it from the archive and opens nothing");
		lz.open(42, zoneAt(SHARED_ZONE));         //as if a zone had been open before
		long t0 = System.currentTimeMillis();
		pnl.loadEverything();
		long ms = System.currentTimeMillis() - t0;

		int entries = Workspace.getArchive(ArchiveType.ZONE_DATA).length;
		check(lz.count() == entries - 2,
				"the table holds every zone bar the master table and the EN pack ("
				+ lz.count() + " for " + entries + " entries, " + ms + "ms)");
		check(pnl.getLoadedZoneCount() == entries - 2,
				"and the dropdown holds exactly as many, so its positions are zone indices ("
				+ pnl.getLoadedZoneCount() + ")");
		check(lz.open() == null, "the rebuild leaves no zone open");
		check(lz.index() == 42,
				"and PINNED AS IS: the index is NOT reset by the rebuild (still " + lz.index() + ")");
		//PINNED AS IS: filling an empty JComboBox selects its first item, so the
		//dropdown SHOWS zone 0 while the panel holds no zone and zoneIndex names
		//another one. The three disagree until the user picks something.
		check(((JComboBox<?>) field(pnl, "zoneList")).getSelectedIndex() == 0,
				"and PINNED AS IS: the dropdown auto-selects zone 0 while no zone is open (showing "
				+ ((JComboBox<?>) field(pnl, "zoneList")).getSelectedIndex() + ")");
		check(lz.at(SHARED_ZONE) != null && lz.at(entries - 3) != null,
				"and both ends of the range really were read");
		lz.close();
	}

	/**
	 * Every retail zone header, through the two dropdowns and back.
	 *
	 * <p>The tables above agree with themselves. This asks the question that
	 * actually matters: does every value the RETAIL GAME holds survive them?
	 * The editor writes a zone's header back whenever the user saves any zone
	 * they had merely opened, so a value the tables cannot represent is
	 * rewritten in a zone nobody edited, and the only symptom is the weather or
	 * the map type changing in a town the user never touched.
	 *
	 * <p>Swept over the whole corpus because it costs nothing: the headers are
	 * already parsed and the check is arithmetic.
	 */
	static void everyRetailHeaderSurvivesTheDropdowns(ZoneLoadingPanel pnl, LoadedZone lz) {
		System.out.println("--- every retail header value survives the dropdowns");
		TreeSet<Integer> badType = new TreeSet<>();
		TreeSet<Integer> badWeather = new TreeSet<>();
		TreeSet<Integer> types = new TreeSet<>();
		TreeSet<Integer> weathers = new TreeSet<>();
		for (int i = 0; i < lz.count(); i++) {
			if (lz.at(i) == null) {
				continue;
			}
			int t = lz.at(i).header.mapType;
			int w = lz.at(i).header.weather;
			types.add(t);
			weathers.add(w);
			if (pnl.getTypeRaw(pnl.getTypeIndex(t)) != t) {
				badType.add(t);
			}
			if (pnl.getWeatherRaw(pnl.getWeatherIndex(w)) != w) {
				badWeather.add(w);
			}
		}
		check(types.size() > 1 && weathers.size() > 1,
				"the corpus really varies: map types " + types + ", weathers " + weathers);
		check(badType.isEmpty(),
				"no retail map type is changed by being shown and written back " + badType);
		check(badWeather.isEmpty(),
				"and neither is any retail weather " + badWeather);
	}

	/**
	 * A zone opened and saved with nothing typed must come back byte for byte.
	 *
	 * <p>The strongest statement this suite can make about the header form, and
	 * the one a refactor is most likely to break: forty-odd fields are copied
	 * out into Swing components on load and copied back on save, so any field
	 * that is read but not written, or written through the wrong table, changes
	 * the file. Nothing else notices - the zone still loads, the game still
	 * boots, and the difference is a flag the player meets three towns later.
	 *
	 * <p>Driven on real zones with real headers, and asserted on the bytes the
	 * container holds afterwards. It found one, which is pinned here as it
	 * behaves and reported rather than fixed: 52 retail zones do NOT come back
	 * unchanged, because one header field is six bits wide and its editor is a
	 * checkbox.
	 */
	static void aLoadedZoneIsWrittenBackUnchanged(ZoneLoadingPanel pnl, LoadedZone lz) throws Exception {
		System.out.println("--- a zone opened and saved with nothing typed, against the bytes");
		//"Is parent map" is a checkbox over a SIX-BIT field: the header packs
		//parentMap in the low 10 bits of the u16 at 0x1C and OLvalue in the
		//rest, and the form reads it as OLvalue == 1 and writes back 1 or 0.
		//Retail uses 2 as well, so opening one of those zones and saving zeroes
		//it. Measured, PINNED, and reported - not fixed here.
		TreeSet<Integer> wideOL = new TreeSet<>();
		TreeSet<Integer> values = new TreeSet<>();
		for (int i = 0; i < lz.count(); i++) {
			if (lz.at(i) == null) {
				continue;
			}
			values.add(lz.at(i).header.OLvalue);
			if (lz.at(i).header.OLvalue > 1) {
				wideOL.add(i);
			}
		}
		check(values.size() > 1, "the retail corpus uses OLvalue values " + values);
		check(wideOL.size() == 52, "and " + wideOL.size()
				+ " of " + lz.count() + " zones hold one the checkbox cannot represent"
				+ " (expected 52; first is zone " + (wideOL.isEmpty() ? "none" : wideOL.first()) + ")");

		//zones the form CAN represent must come back byte for byte
		int checkedZones = 0;
		int changed = 0;
		String firstChange = "";
		for (int idx : new int[]{0, SHARED_ZONE, PRIVATE_ZONE, 100, 200, 300, 448, 517, 535}) {
			if (idx >= lz.count() || wideOL.contains(idx)) {
				continue;
			}
			Zone z = zoneAt(idx);
			byte[] before = z.file.getFile(0);
			index(lz, idx);
			pnl.loadZone(z);
			check(lz.open() == z, "zone " + idx + " is the open one after a load");
			Boolean stored = storeOrNull(pnl);
			byte[] after = z.file.getFile(0);
			checkedZones++;
			if (stored == null || !stored || !Arrays.equals(before, after)) {
				changed++;
				if (firstChange.isEmpty()) {
					firstChange = "zone " + idx + (stored == null ? " THREW out of store()"
							: stored ? " header differs" : " refused to store");
				}
			}
		}
		check(checkedZones == 9, "opened and saved " + checkedZones + " zones the form can represent");
		check(changed == 0, "and not one header byte moved " + firstChange);

		//and one it cannot: exactly one byte, and exactly this byte
		int victim = wideOL.first();
		Zone z = zoneAt(victim);
		int wasOL = z.header.OLvalue;
		int wasParent = z.header.parentMap;
		byte[] before = z.file.getFile(0);
		index(lz, victim);
		pnl.loadZone(z);
		check(storeOrNull(pnl) != null, "zone " + victim + " saves without throwing out of the form");
		byte[] after = z.file.getFile(0);
		List<Integer> moved = new ArrayList<>();
		for (int k = 0; k < Math.min(before.length, after.length); k++) {
			if (before[k] != after[k]) {
				moved.add(k);
			}
		}
		check(before.length == after.length && moved.size() == 1 && moved.get(0) == 0x1D,
				"zone " + victim + " (OLvalue " + wasOL + ") loses exactly one byte, the high half of"
				+ " the packed parentMap word at 0x1C: moved " + moved);
		ctrmap.formats.zone.ZoneHeader reread
				= new ctrmap.formats.zone.ZoneHeader(after, Workspace.game());
		check(reread.OLvalue == 0,
				"OLvalue " + wasOL + " is written back as " + reread.OLvalue
				+ " - PINNED as it behaves, not as it should");
		check(reread.parentMap == wasParent,
				"and the location id sharing that word is untouched (" + wasParent + ")");

		//and the same save with no zone open must be a no-op rather than a throw
		lz.close();
		check(pnl.store(false), "saving with no zone open succeeds and writes nothing");
	}

	/**
	 * The dropdown can only be pointed at a zone it actually has.
	 *
	 * <p>{@code selectZone} is how the editor opens a zone it has just made -
	 * the appender, the cloner and the facility setup all end in it - and each
	 * of them computes the index itself, from a list that has just been
	 * rebuilt. An index the list does not have has to do nothing; without the
	 * bound it is an IllegalArgumentException at the end of an operation that
	 * otherwise worked, which reads as the whole thing having failed.
	 */
	/**
	 * A zone opened from a loose .zo file writes no other zone's table slot.
	 *
	 * <p>{@code loadZone} deliberately keeps whatever index was last recorded,
	 * because the dropdown's list worker records its own on the very next line.
	 * File &gt; Open Zone recorded none, so a zone opened from a FILE inherited
	 * the index of whatever zone the user had last picked - and store() then
	 * wrote this file's 0x38-byte header into the workspace's master
	 * zone-header table at that slot, silently replacing an unrelated map's
	 * entry. It did it without a word, while the harmless case - nothing ever
	 * picked, index -1 - was the one that reported.
	 *
	 * <p>-1 is what {@code LoadedZone.open(int, Zone)} documents for a zone that
	 * came from a file, and what the three operations that work on a table slot
	 * - append, clone and repurpose - already test for. This pins both halves:
	 * the slot the user had been on is untouched, and the save says out loud
	 * that the master table was not updated instead of doing it quietly.
	 */
	static void aZoneFromAFileTouchesNobodysTableSlot(ZoneLoadingPanel pnl, LoadedZone lz)
			throws Exception {
		System.out.println("--- a zone opened from a file writes nobody else's table slot");
		File master = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA,
			ctrmap.ZoneTables.masterIndex(Workspace.getArchive(ArchiveType.ZONE_DATA)));
		byte[] before = java.nio.file.Files.readAllBytes(master.toPath());
		
		//the user picks zone 3 from the dropdown...
		Zone picked = zoneAt(3);
		lz.open(3, picked);
		pnl.loadZone(picked);
		check(lz.index() == 3, "zone 3 is the one the dropdown recorded");
		
		//...and then opens an unrelated .zo from disk, which is File > Open Zone:
		//loadZone, then the index the window records for a zone with no slot
		Zone loose = zoneAt(PRIVATE_ZONE);
		pnl.loadZone(loose);
		lz.open(-1, loose);
		check(lz.index() == -1 && lz.open() == loose,
			"a zone that came from a file is open at no slot at all");
		
		List<String> said = Ui.record();
		Boolean stored;
		try {
			stored = storeOrNull(pnl);
		} finally {
			Ui.stopRecording();
		}
		check(stored != null && stored, "saving it answers, rather than throwing");
		check(java.util.Arrays.equals(before, java.nio.file.Files.readAllBytes(master.toPath())),
			"and the master zone-header table is byte-for-byte what it was - slot 3 still"
			+ " belongs to zone 3");
		boolean toldThem = false;
		for (String one : said) {
			toldThem |= one.contains("master zone-header") && one.contains("NOT updated");
		}
		check(toldThem, "and the user is told the master table was not updated, rather than"
			+ " the write happening silently somewhere else: " + said);
		lz.open(-1, null);
	}

	/**
	 * A header the form could not finish showing is never written over another
	 * zone's, and the user is told why.
	 *
	 * <p>{@code loadZone} sets {@code loaded} false, writes forty-odd widgets out
	 * of the header, and sets it true as its LAST line - all inside one try whose
	 * catch printed a stack trace and stopped there. Four of those writes are
	 * {@code setSelectedIndex} fed straight from the header, and an index the
	 * model does not have is an IllegalArgumentException: the ORAS map-type box
	 * holds two entries while getTypeIndex can answer 2 or 4..7, the weather box
	 * holds ten against a five-bit field, the transition box eight against
	 * another five-bit field.
	 *
	 * <p>When one throws, every field AFTER it still holds the PREVIOUS zone's
	 * value - and store() had no {@code loaded} test at all, so it copied that
	 * zone's boundaries, and for an early throw its matrix, script, text and
	 * parent-map ids too, into THIS zone's header on the next File &gt; Save,
	 * zone switch or window close. Cross-zone header corruption whose only trace
	 * was a stack trace on stderr.
	 *
	 * <p>Two halves are pinned. First that the RETAIL corpus never trips it -
	 * every zone's four dropdown indices are inside their models - so this is a
	 * guard against damaged or hand-made data rather than a live bug on shipped
	 * zones. Second that when it does happen the save writes NOTHING and says so,
	 * which is the half that used to be missing.
	 */
	static void aHeaderTheFormCannotShowIsNotWrittenOverAnother(ZoneLoadingPanel pnl, LoadedZone lz)
			throws Exception {
		System.out.println("--- a header the form could not show is not written over another");
		javax.swing.JComboBox<?> type = (javax.swing.JComboBox<?>) field(pnl, "type");
		javax.swing.JComboBox<?> weather = (javax.swing.JComboBox<?>) field(pnl, "weather");
		javax.swing.JComboBox<?> transition = (javax.swing.JComboBox<?>) field(pnl, "mapTransition");
		javax.swing.JComboBox<?> tmg = (javax.swing.JComboBox<?>) field(pnl, "tmg");
		TreeSet<String> outside = new TreeSet<>();
		int walked = 0;
		for (int i = 0; i < lz.count(); i++) {
			if (lz.at(i) == null) {
				continue;
			}
			walked++;
			ctrmap.formats.zone.ZoneHeader h = lz.at(i).header;
			if (pnl.getTypeIndex(h.mapType) >= type.getItemCount()) {
				outside.add("zone " + i + " map type " + h.mapType);
			}
			if (pnl.getWeatherIndex(h.weather) >= weather.getItemCount()) {
				outside.add("zone " + i + " weather " + h.weather);
			}
			if (h.mapChange >= transition.getItemCount()) {
				outside.add("zone " + i + " transition " + h.mapChange);
			}
			if (h.townMapGroup >= tmg.getItemCount()) {
				outside.add("zone " + i + " town map group " + h.townMapGroup);
			}
		}
		check(walked > 500, "walked " + walked + " retail headers, so this is the corpus");
		check(outside.isEmpty(), "and every one of their four dropdown values is inside its box "
			+ outside);
		
		//and the half that matters when one is not: a form that never finished
		//showing a zone writes nothing into it, and says why
		Zone victim = zoneAt(PRIVATE_ZONE);
		byte[] before = victim.file.getFile(0);
		lz.open(PRIVATE_ZONE, victim);
		pnl.loadZone(victim);
		setField(pnl, "loaded", false);
		List<String> said = Ui.record();
		boolean answered;
		try {
			answered = pnl.store(false);
		} finally {
			Ui.stopRecording();
		}
		check(java.util.Arrays.equals(before, victim.file.getFile(0)),
			"a form that never finished loading writes not one header byte");
		check(said.size() == 1 && said.get(0).contains("never finished"),
			"and says so, rather than leaving a stack trace on stderr: " + said);
		check(answered, "it answers TRUE on purpose - a refusal here would also stop the"
			+ " editor flush, and the window only closes when that answers true, so refusing"
			+ " would leave the editor with no way out at all");
		pnl.loadZone(victim);
	}

	static void selectingOutsideTheListChangesNothing(ZoneLoadingPanel pnl) throws Exception {
		System.out.println("--- selecting a zone the list does not have does nothing");
		//the dropdown's listener is what opens a zone, and opening one drives
		//every editor; this check is about the bound, so the listener is left
		//idle the way it is between a rebuild and the first click
		setField(pnl, "loaded", false);
		JComboBox<?> list = (JComboBox<?>) field(pnl, "zoneList");
		list.setSelectedIndex(-1);

		pnl.selectZone(-1);
		check(list.getSelectedIndex() == -1, "a negative index selects nothing");
		pnl.selectZone(list.getItemCount());
		check(list.getSelectedIndex() == -1, "one past the end selects nothing");
		pnl.selectZone(list.getItemCount() + 500);
		check(list.getSelectedIndex() == -1, "and neither does an index far past it");
		pnl.selectZone(PRIVATE_ZONE);
		check(list.getSelectedIndex() == PRIVATE_ZONE,
				"an index the list has selects that zone (" + list.getSelectedIndex() + ")");
		System.out.println("  skip: what the selection then LOADS drives six editors and the 3D view"
				+ " - DataSafetyGuardsTest covers that path");
		list.setSelectedIndex(-1);
	}

	/**
	 * Unloading takes the editors down with it.
	 *
	 * <p>Reached when a zone load throws part way, which is the state this
	 * exists for: some editors have already switched to the new zone and the
	 * rest are still on the old one. Leaving either standing is worse than an
	 * empty editor, because the user is then looking at records that belong to
	 * a zone {@code store} will never write - they edit them, press save, and
	 * nothing happens.
	 *
	 * <p>DataSafetyGuardsTest proves the user is TOLD when that happens. What
	 * is pinned here is the clearing itself, driven directly, so each field is
	 * named rather than inferred from one failed load.
	 */
	static void unloadingClearsTheZoneAndTheEditorsWithIt(ZoneLoadingPanel pnl, LoadedZone lz) throws Exception {
		System.out.println("--- unloading clears the zone and every editor showing it");
		Zone z = zoneAt(SHARED_ZONE);
		lz.open(SHARED_ZONE, z);
		pnl.loadZone(z);
		CtrmapMainframe.mNPCEditForm.loadFromEntities(z.entities, null);
		CtrmapMainframe.mWarpEditForm.loadFromEntities(z.entities);
		CtrmapMainframe.mTriggerEditForm.loadFromEntities(z.entities);
		check(CtrmapMainframe.mNPCEditForm.loaded, "the NPC editor is showing the zone first");
		JComboBox<?> list = (JComboBox<?>) field(pnl, "zoneList");
		setField(pnl, "loaded", false);
		list.setSelectedIndex(SHARED_ZONE);
		Zone[] tableBefore = snapshot(lz);

		Method m = ZoneLoadingPanel.class.getDeclaredMethod("unloadZone");
		m.setAccessible(true);
		m.invoke(pnl);

		check(lz.open() == null, "no zone is open afterwards");
		check(lz.index() == -1, "and the index says so too (got " + lz.index() + ")");
		check(list.getSelectedIndex() == -1, "the dropdown shows nothing selected");
		check(!CtrmapMainframe.mNPCEditForm.loaded,
				"the NPC editor is not left showing the zone that is no longer open");
		check(sameTable(lz, tableBefore),
				"but the ZONE LIST itself is kept - the same zones in the same slots, none is open");
		check(pnl.store(false), "and a save now writes nothing rather than writing the wrong zone");
	}

	/**
	 * A zone that shares its map is offered a private copy, once.
	 *
	 * <p>Forking is the safe default: editing a shared map changes every zone
	 * on it, and in the retail game most towns share with their story-event
	 * copies. The offer is the migration net for zones made before the editor
	 * forked automatically, and the two things it must get right are opposite
	 * failures - never offering means silent damage to other zones, and
	 * offering every time a zone is opened is nagging that gets clicked
	 * through.
	 *
	 * <p>So: a shared zone is offered and told WHO it shares with; a declined
	 * offer is remembered and not repeated; a zone whose map is its own is
	 * never asked. Only the decline is answered - saying yes runs a real fork
	 * and a pack, which ForkGuardsTest covers against the archive.
	 */
	static void aSharedMapIsOfferedAForkAndADeclineIsRemembered(ZoneLoadingPanel pnl, LoadedZone lz) throws Exception {
		System.out.println("--- a shared map is offered a fork, and a decline is remembered");
		String key = "FORK_DECLINED_" + Workspace.WORKSPACE_PATH.hashCode();
		try {
			lz.open(SHARED_ZONE, zoneAt(SHARED_ZONE));
			int mtx = lz.open().header.mapmatrixID;
			int sharers = 0;
			for (int i = 0; i < lz.count(); i++) {
				if (i != SHARED_ZONE && lz.at(i) != null && lz.at(i).header.mapmatrixID == mtx) {
					sharers++;
				}
			}
			check(sharers > 0, "zone " + SHARED_ZONE + " shares map matrix " + mtx
					+ " with " + sharers + " other zone(s), which is why it is the one used here");

			List<String> said = offerFork(pnl, JOptionPane.NO_OPTION);
			check(said.size() == 1 && said.get(0).startsWith("Shared map: This zone SHARES its map with "
					+ sharers + " other zone(s):"),
					"a shared zone is offered a private copy, and told how many: " + line(said));
			check(said.size() == 1 && said.get(0).contains("  - zone "),
					"and which ones, by index and name, rather than just a count");
			check(said.size() == 1 && said.get(0).contains("Editing the map here would change those zones too"),
					"and why it matters");

			said = offerFork(pnl, JOptionPane.NO_OPTION);
			check(said.isEmpty(), "a declined offer is remembered and never made again: " + said);

			pnl.clearForkDecline(SHARED_ZONE);
			said = offerFork(pnl, JOptionPane.NO_OPTION);
			check(said.size() == 1, "until the slot's occupant changes, when it is offered afresh: " + said.size());

			pnl.clearForkDeclinesFrom(0);
			said = offerFork(pnl, JOptionPane.NO_OPTION);
			check(said.size() == 1, "and clearing a whole range of declines does the same");

			//a zone whose map is already its own is never asked
			lz.open(PRIVATE_ZONE, zoneAt(PRIVATE_ZONE));
			said = offerFork(pnl, JOptionPane.NO_OPTION);
			check(said.isEmpty(), "a zone whose map is its own is never asked: " + said);

			//nor is anything asked with no zone open
			lz.close();
			said = offerFork(pnl, JOptionPane.NO_OPTION);
			check(said.isEmpty(), "and nothing is asked when no zone is open: " + said);
		} finally {
			//the declines live in the machine's own preferences; this run's
			//workspace is a throwaway folder, so its key is removed with it
			try {
				java.util.prefs.Preferences.userRoot().node("ctrmap.ZoneLoadingPanel").remove(key);
			} catch (Exception ignore) {
			}
		}
	}

	/**
	 * A zone table that cannot be read leaves an empty list, not the last one.
	 *
	 * <p>The rebuild computes its zone count as the archive's length less two,
	 * so an archive that could not be parsed gives a NEGATIVE count. Without
	 * the guard that is a NegativeArraySizeException inside a worker, which -
	 * before the {@code get()} in {@code done()} - vanished entirely and left
	 * the dropdown silently empty with nothing said anywhere.
	 *
	 * <p>What has to survive a refactor is the pair: the list ends up empty
	 * (never the previous game's zones, which would be openable and would write
	 * into the wrong archive), and the count the panel reports agrees with it.
	 */
	static void aZoneTableThatCannotBeReadLeavesNoZones(ZoneLoadingPanel pnl, LoadedZone lz) throws Exception {
		System.out.println("--- a zone table that cannot be read leaves the list empty");
		check(pnl.getLoadedZoneCount() > 0, "the list is full before the archive is broken");
		File missing = new File(Scratch.dir("ctrmap_no_zonedata"), "not-a-garc");
		//the same game, still open, holding a ZoneData handle on a file that is
		//not a GARC - which is what a truncated or half-copied dump leaves
		Workspace.install(Workspace.session().withArchive(ArchiveType.ZONE_DATA,
				new ctrmap.formats.garc.GARC(missing)));
		check(Workspace.getArchive(ArchiveType.ZONE_DATA).length <= 0,
				"an unreadable archive reports "
				+ Workspace.getArchive(ArchiveType.ZONE_DATA).length + " entries");

		pnl.loadEverything();
		check(pnl.getLoadedZoneCount() == 0,
				"and the dropdown is left empty rather than holding the last game's zones (got "
				+ pnl.getLoadedZoneCount() + ")");
		check(lz.open() == null && lz.index() == -1,
				"with nothing reported as open (zone " + lz.open() + ", index " + lz.index() + ")");
	}

	// ---- plumbing ----------------------------------------------------------

	/**
	 * The header save, with anything it throws coming back as null rather than
	 * ending the run. A form field the load never reached is a
	 * NullPointerException out of {@code store}, and one broken zone must not
	 * stop the suite reporting the rest.
	 */
	/** What {@code pnl.zoneIndex = i} used to do: the index alone, whatever is open stays open. */
	static void index(LoadedZone lz, int i) {
		lz.open(i, lz.open());
	}

	/** Every slot of the table, in order - the array the panel used to expose. */
	static Zone[] snapshot(LoadedZone lz) {
		Zone[] out = new Zone[lz.count()];
		for (int i = 0; i < out.length; i++) {
			out[i] = lz.at(i);
		}
		return out;
	}

	/** The same zones in the same slots: what {@code pnl.zones == before} asked when the array was exposed. */
	static boolean sameTable(LoadedZone lz, Zone[] before) {
		if (lz.count() != before.length) {
			return false;
		}
		for (int i = 0; i < before.length; i++) {
			if (lz.at(i) != before[i]) {
				return false;
			}
		}
		return true;
	}

	static Boolean storeOrNull(ZoneLoadingPanel pnl) {
		try {
			return pnl.store(false);
		} catch (RuntimeException ex) {
			System.out.println("  (store threw: " + ex + ")");
			return null;
		}
	}

	static Zone zoneAt(int index) throws Exception {
		return new Zone(new ZO(temp(Workspace.getArchive(ArchiveType.ZONE_DATA).getDecompressedEntry(index)), Workspace.session()), Workspace.game());
	}

	/** Presses one of the panel's buttons, whatever it throws coming back as a message. */
	static List<String> press(ZoneLoadingPanel pnl, String handler) throws Exception {
		List<String> said = Ui.record();
		try {
			Method m = ZoneLoadingPanel.class.getDeclaredMethod(handler, java.awt.event.ActionEvent.class);
			m.setAccessible(true);
			m.invoke(pnl, (java.awt.event.ActionEvent) null);
		} catch (InvocationTargetException ex) {
			said.add("THREW: " + (ex.getCause() == null ? ex : ex.getCause()));
		} finally {
			Ui.stopRecording();
		}
		return said;
	}

	/** The fork offer that runs after a zone is opened, with the answer supplied. */
	static List<String> offerFork(ZoneLoadingPanel pnl, int answer) throws Exception {
		List<String> said = Ui.record(answer);
		try {
			Method m = ZoneLoadingPanel.class.getDeclaredMethod("offerForkIfShared");
			m.setAccessible(true);
			m.invoke(pnl);
		} catch (InvocationTargetException ex) {
			said.add("THREW: " + (ex.getCause() == null ? ex : ex.getCause()));
		} finally {
			Ui.stopRecording();
		}
		return said;
	}

	static File temp(byte[] bytes) throws Exception {
		File f = File.createTempFile("ctrmap_zonestate", ".bin");
		f.deleteOnExit();
		java.nio.file.Files.write(f.toPath(), bytes);
		return f;
	}

	static Object field(Object o, String name) throws Exception {
		Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	static void setField(Object o, String name, Object value) throws Exception {
		Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		f.set(o, value);
	}

	static String line(List<String> said) {
		if (said.isEmpty()) {
			return "(nothing was said)";
		}
		int nl = said.get(0).indexOf('\n');
		return nl < 0 ? said.get(0) : said.get(0).substring(0, nl);
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}
