package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.LoadedZone;
import ctrmap.Ui;
import ctrmap.Workspace;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import ctrmap.humaninterface.ZoneLoadingPanel;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import javax.swing.JOptionPane;

/**
 * What the main window's menu actions DO before they touch anything, pinned so
 * a refactor that moves them cannot change it quietly.
 *
 * <p>WHY THIS SUITE EXISTS. Every one of these actions is a private static
 * method wired to an anonymous listener, and every one begins with the same
 * shape: a guard, a sentence, and a return. The sentence is the only thing that
 * distinguishes "the editor refused, and here is why" from "the menu item did
 * nothing", and until now not one of the twelve was measured.
 * {@link MainframeReportsTest} covers the three REPORTS the fork, rename and
 * matrix-open paths produce after they have worked; nothing covered the
 * refusals, and a refusal that says nothing is the silent failure the whole
 * {@link Ui} seam exists to remove.
 *
 * <p>The reason it matters more than usual here is that the actions are about
 * to have their statics given owners. A guard that reads
 * {@code Workspace.isValid()} today will read something else afterwards, and the
 * cheapest way for that to go wrong is for a guard to stop firing: the action
 * then runs on a workspace that is not loaded and dereferences a null archive
 * somewhere deeper, where the user gets a stack trace on the event thread
 * instead of one sentence. So each refusal is asserted whole - title AND text,
 * by equality, not by "contains" - because a guard that fires with the wrong
 * words has been moved to the wrong branch.
 *
 * <p>These are CHARACTERIZATION tests. Two of the things pinned below look
 * wrong and are pinned as they are, not as they should be; both are named in
 * the report rather than fixed here.
 *
 * <p>Painting, OpenGL and the modal forms are out of scope: an action whose
 * next step is {@code JOptionPane.showConfirmDialog} with a live Swing form
 * cannot be driven headless (the seam carries a String on purpose - see
 * DialogSeamTest), so the checks stop at the last decision before the wall and
 * say so.
 *
 * Usage: java ctrmap.tests.MainframeActionGuardsTest &lt;pristine dump root&gt;
 */
public class MainframeActionGuardsTest {
	/** The editors that show the zone, for the panels here: a spy that records and clears. */
	static final ZoneEditorsSpy ZONE_EDITORS = new ZoneEditorsSpy();

	/** The editor set the panels here flush: it records instead of saving. */
	static final RecordingEditors EDITORS = new RecordingEditors();

	/** The tool this suite holds: its own, so another suite may hold another. */
	static final ctrmap.humaninterface.tools.ToolSelection TOOLS = new ctrmap.humaninterface.tools.ToolSelection();


	/** ORAS: 536 base zones, then the master table and the EN pack. */
	private static final int STOCK_ZONEDATA_ENTRIES = 538;
	/** Battle Resort's Maison lobby, the default facility source. */
	private static final int MAISON_LOBBY_ZONE = 517;
	/** Mauville's Battle Institute lobby, the other facility source. */
	private static final int INSTITUTE_ZONE = 448;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");

		//no game needed: these are the guards that fire BEFORE anything is read
		everyActionRefusesWithNoWorkspace();
		everyOrasOnlyActionRefusesAnXyWorkspace();
		aMissingZoneTableIsNamed();
		theModSwitchDecidesFromTheFolderItWasGiven();
		theModSwitchSaysSoWhenItCannotMove();
		restoreRoutesToTheChoiceThatWasMade();

		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump
					+ " - the loaded-workspace checks need one");
		} else {
			ScratchGame.open(dump);
			stockZoneDataReportsNoAddedZones();
			theActionsThatNeedAZoneSayWhichTab();
			facilitySetupRefusesAnAppendedZone();
			facilitySetupOffersBothWaysAndActsOnNeitherUnasked();
			theObjToolsDefaultToTheLoadedZonesRegion();
		}

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	// ---- the guards that fire before any archive is touched -----------------

	/**
	 * With no workspace loaded, every action must refuse, say one thing, and
	 * say it under the name of the thing the user was trying to do.
	 *
	 * <p>Twelve menu items, twelve first lines of defence. Lose one and the
	 * action runs on a Workspace whose archive handles are all null: the next
	 * statement in ten of the twelve is a dereference of
	 * {@code Workspace.getArchive(...)}, so the user's reward for clicking a
	 * menu item too early is a NullPointerException on the event thread and a
	 * window that appears to have ignored them.
	 *
	 * <p>The title is asserted with the text because the two are what the user
	 * actually reads. A guard that fires with another action's title has been
	 * copied into the wrong method, and the message is then worse than useless:
	 * it names something they did not do.
	 */
	static void everyActionRefusesWithNoWorkspace() throws Exception {
		System.out.println("--- with no workspace, every action refuses under its own title");
		Workspace.reset();
		CtrmapMainframe.mZonePnl = null;

		String settings = "Load a workspace first (Options > Workspace settings).";
		refuses("deployModAction", "Deploy mod: Load a workspace first.");
		refuses("forkGeometryAction", "Fork map geometry: " + settings);
		refuses("renameZoneAction", "Rename zone: " + settings);
		refuses("emptyZoneAction", "Empty zone: " + settings);
		refuses("findReusableZonesAction", "Find reusable zones: " + settings);
		refuses("exportMapObjAction", "Export map to OBJ: " + settings);
		refuses("importMapObjAction", "Import OBJ: " + settings);
		refuses("importMapModelAction", "Import map model: " + settings);
		//these four said "Load an ORAS workspace first." until the capability
		//gate replaced the game-identity one: with nothing open there is no
		//game to be the wrong one, so they now send the user where the other
		//nine do
		refuses("blankCanvasAction", "Blank map canvas: " + settings);
		refuses("setupFacilityAction", "Set up Battle facility: " + settings);
		refuses("resizeMapAction", "Resize map: " + settings);
		refuses("removeAddedZonesAction", "Remove added zones: " + settings);
		refuses("restoreFromVaultAction", "Restore: There is no game folder loaded to restore into.");
	}

	/**
	 * The four ORAS-only actions must refuse an XY workspace, and refuse it in
	 * their own words rather than the no-workspace ones.
	 *
	 * <p>CTRMap is multigenerational by design and XY is a supported game, so
	 * "no workspace" and "not this game" are different facts and the user acts
	 * on them differently: one means load something, the other means this
	 * feature is not written yet. Collapsing them sends an XY user to reload a
	 * workspace that was already fine.
	 *
	 * <p>Worse, the ORAS check is what keeps zone appending, geometry forking
	 * and facility cloning away from an archive laid out differently (XY has no
	 * EN pack, so its zone count is length-1 rather than length-2). Losing it
	 * does not report anything; it writes.
	 *
	 * <p>THE WORDS CHANGED, and the assertion follows them: the six used to say
	 * "Forking map geometry is ORAS-only in v1." and "Load an ORAS workspace
	 * first.", which named a version number and a game rather than the game the
	 * user actually had open. Each now names the OPEN game, says what is not
	 * available for it, and gives the site's own reason - which is the thing
	 * being asserted here, because it is per-site and a guard copied into the
	 * wrong method carries the wrong reason with it.
	 */
	static void everyOrasOnlyActionRefusesAnXyWorkspace() throws Exception {
		System.out.println("--- an XY workspace is refused by the ORAS-only actions, in their own words");
		Workspace.reset();
		openWithNoArchives(GameType.XY);
		CtrmapMainframe.mZonePnl = null;

		refuses("forkGeometryAction", "Fork map geometry: " + unsupported("Forking map geometry",
				"Giving one zone its own copy of its map appends to FieldData and the"
				+ " MapMatrix and repoints the zone in the master zone-header table;"
				+ " every one of those offsets was measured on Omega Ruby / Alpha"
				+ " Sapphire."));
		refuses("findReusableZonesAction", "Find reusable zones: " + unsupported("Finding reusable base zones",
				"The scan reads every zone header, its script and its warps to judge"
				+ " whether overwriting it is safe. That judgement rests on the zone"
				+ " header layout, the script format and the base/appended zone boundary"
				+ " as measured on Omega Ruby / Alpha Sapphire."));
		refuses("blankCanvasAction", "Blank map canvas: " + unsupported("Starting a zone's map from scratch",
				"It first gives the zone its OWN geometry (appending to FieldData and the"
				+ " MapMatrix), then rewrites each private region's BCH map model in place."
				+ " Both the fork offsets and the model layout were measured on Omega Ruby"
				+ " / Alpha Sapphire."));
		refuses("setupFacilityAction", "Set up Battle facility: " + unsupported("Setting up a Battle facility",
				"It replaces a zone with a verbatim copy of a retail facility lobby, which"
				+ " means knowing which zone that lobby is and where the engine keeps the"
				+ " opponent pools it draws from. Both were measured on Omega Ruby / Alpha"
				+ " Sapphire."));
		refuses("resizeMapAction", "Resize map: " + unsupported("Growing a zone's map",
				"New map cells are appended to FieldData and wired into a rebuilt"
				+ " MapMatrix after the zone's geometry has been made private. The"
				+ " container layouts and every offset involved were measured on Omega"
				+ " Ruby / Alpha Sapphire."));
		refuses("removeAddedZonesAction", "Remove added zones: " + unsupported("Removing added zones",
				"Undoing an append means knowing how many zones the game shipped with and"
				+ " how its master zone-header table is laid out. Both were measured for"
				+ " Omega Ruby / Alpha Sapphire, alongside the code patch that raises the"
				+ " zone limit in the first place."));
		//the zone-table actions are not ORAS-only, so on XY they get as far as
		//asking for the archive - and that is the sentence they must produce
		refuses("renameZoneAction", "Rename zone: ZoneData archive unavailable.");
		refuses("emptyZoneAction", "Empty zone: ZoneData archive unavailable.");
	}

	/**
	 * A workspace that says it is valid but holds no zone table.
	 *
	 * <p>{@code Workspace.isValid()} is set from the paths, before the archives are
	 * read, so "valid with a null ZoneData" is a state the application really
	 * reaches when a load fails part way. Three of the four actions that need
	 * the table say exactly that.
	 *
	 * <p>The fourth does not, and this pins what it says today rather than what
	 * it ought to: "Remove added zones" folds a MISSING zone table into the
	 * same branch as a stock one and reports "This ZoneData has no added zones
	 * (stock layout)" - a statement about an archive it never looked at. See
	 * suspected_defects; it is left exactly as it is so the refactor this suite
	 * guards is measured against today's behaviour and not against an
	 * improvement smuggled in here.
	 */
	static void aMissingZoneTableIsNamed() throws Exception {
		System.out.println("--- a valid ORAS workspace with no zone table loaded");
		Workspace.reset();
		openWithNoArchives(GameType.ORAS);
		CtrmapMainframe.mZonePnl = null;

		refuses("forkGeometryAction", "Fork map geometry: ZoneData archive unavailable.");
		refuses("renameZoneAction", "Rename zone: ZoneData archive unavailable.");
		refuses("emptyZoneAction", "Empty zone: ZoneData archive unavailable.");
		//PINNED AS IS, NOT AS IT SHOULD BE - see the javadoc above
		refuses("removeAddedZonesAction",
				"Remove added zones: This ZoneData has no added zones (stock layout).");
	}

	// ---- the mod switch, which is reachable end to end ----------------------

	/**
	 * "Turn mod OFF / back ON" decides its direction from the folder's real
	 * state, not from what the dialog was labelled.
	 *
	 * <p>The label is computed before the dialog opens and the emulator can be
	 * doing anything in between, so the decision is taken again at the moment
	 * of acting. Three states, three different things to say, and each one is
	 * something the user cannot check any other way: the mod folder is inside
	 * the emulator's data directory and its parked twin has a mangled name.
	 *
	 * <p>Getting the branch backwards is not a cosmetic failure. "Nothing
	 * deployed" over a folder that IS deployed leaves the user believing their
	 * edits never shipped, and the usual next move is to deploy again over a
	 * mod that was already there.
	 *
	 * <p>The whole action is driven here (it takes the folder as a parameter),
	 * so the filesystem is asserted as well as the sentence: what a move
	 * REPORTS and what it DID are two facts, and a suite that checks only the
	 * first cannot tell a working switch from a talkative one.
	 */
	static void theModSwitchDecidesFromTheFolderItWasGiven() throws Exception {
		System.out.println("--- the mod switch: what it says, and what it moved");
		File root = Scratch.dir("ctrmap_modswitch");
		File modRoot = new File(root, "000400000011C400");
		File parked = new File(root, "000400000011C400__off");

		//nothing there at all
		List<String> said = toggle(modRoot);
		check(said.size() == 1 && said.get(0).startsWith("Nothing deployed: There is no deployed mod at:"),
				"an empty folder is reported as nothing to switch off: " + said);
		check(said.size() == 1 && said.get(0).contains(modRoot.getAbsolutePath()),
				"and names the folder it looked in");
		check(!modRoot.exists() && !parked.exists(), "and moved nothing");

		//deployed: it must be parked, and the message must say where
		write(new File(modRoot, "romfs/a/0/1/3"), "zone data");
		said = toggle(modRoot);
		check(said.size() == 1 && said.get(0).startsWith("Playing vanilla: Mod is OFF"),
				"a deployed mod is switched off: " + first(said));
		check(said.size() == 1 && said.get(0).contains(parked.getAbsolutePath()),
				"and the user is told where their edits went");
		check(!modRoot.exists() && new File(parked, "romfs/a/0/1/3").isFile(),
				"and the mod really moved out of the load path, whole");

		//parked: the same action must now switch it back on
		said = toggle(modRoot);
		check(said.size() == 1 && said.get(0).startsWith("Mod switched on: Mod is back ON:"),
				"a parked mod is switched back on by the same action: " + first(said));
		check(new File(modRoot, "romfs/a/0/1/3").isFile() && !parked.exists(),
				"and the files are back in the load path");
		check(said.size() == 1 && said.get(0).contains("close and reopen the emulator"),
				"and both directions warn that the emulator caches game files");
	}

	/**
	 * A move that cannot be made must say so instead of reporting a switch that
	 * did not happen.
	 *
	 * <p>Reachable in the field: park the mod, deploy again (which recreates
	 * the folder), then press the switch. {@code isParked} is true, so it tries
	 * to move the parked copy back over a folder that now exists, and the
	 * mover refuses rather than overwriting. The catch turns that into the one
	 * sentence that tells the user their two copies both still exist and
	 * nothing was lost - and it names the emulator, because a file lock is the
	 * other way this fails.
	 */
	static void theModSwitchSaysSoWhenItCannotMove() throws Exception {
		System.out.println("--- a switch that cannot move anything says so, and keeps both copies");
		File root = Scratch.dir("ctrmap_modswitch_clash");
		File modRoot = new File(root, "000400000011C400");
		File parked = new File(root, "000400000011C400__off");
		write(new File(modRoot, "romfs/live"), "redeployed");
		write(new File(parked, "romfs/old"), "parked earlier");

		List<String> said = toggle(modRoot);
		check(said.size() == 1 && said.get(0).startsWith("Mod switch failed: Could not move the mod folder:"),
				"a switch that could not move reports the failure: " + first(said));
		check(said.size() == 1 && said.get(0).contains("already exists, refusing to overwrite"),
				"carrying what actually stopped it rather than a fixed sentence: " + first(said));
		check(said.size() == 1 && said.get(0).contains("Close the emulator"),
				"and naming the usual cause the user can act on");
		check(new File(modRoot, "romfs/live").isFile() && new File(parked, "romfs/old").isFile(),
				"and neither copy was touched");
	}

	// ---- restore from the pristine vault ------------------------------------

	/**
	 * "Restore from pristine backup" must ask which of two very different
	 * things the user wants, and must do NEITHER when nobody answers.
	 *
	 * <p>The two are not interchangeable: one archive keeps every edit made
	 * since the backup, the whole game discards all of them. That is why they
	 * are one question rather than two menu items, and it is also why an
	 * unanswered question has to mean "do nothing" - a headless run and a
	 * dismissed dialog give the same answer, and consent must never be the
	 * default reading of either.
	 *
	 * <p>Also pinned here, and NOT fixed: the second prompt offers the loaded
	 * game's archive path as the initial value, with a leading slash
	 * ("/a/0/1/3"), while the vault's manifest keys have none - so a user who
	 * accepts the suggestion the dialog itself made is told the backup does not
	 * contain it. Both halves are asserted below exactly as they behave.
	 */
	static void restoreRoutesToTheChoiceThatWasMade() throws Exception {
		System.out.println("--- restore from vault: the choice, and what each answer reaches");
		File scratch = Scratch.dir("ctrmap_mainframe_vault");
		String oldRoot = System.getProperty("ctrmap.vault.root");
		System.setProperty("ctrmap.vault.root", new File(scratch, "vault").getAbsolutePath());
		try {
			File game = new File(scratch, "000400000011C400");
			write(new File(game, "a/0/1/3"), "pristine zone data");
			write(new File(game, "a/0/4/0"), "pristine map matrix");
			Workspace.reset();
			openWithNoArchives(GameType.ORAS);
			Workspace.GAMEDIR_PATH = game.getAbsolutePath();

			//no backup at all
			List<String> said = restore();
			check(said.size() == 1 && said.get(0).startsWith("Nothing to restore: There is no pristine backup"),
					"with no backup the action says so rather than opening a chooser: " + first(said));
			check(said.size() == 1 && said.get(0).contains("setup wizard"),
					"and says how to take one");

			//a seal that was started and never finished must never read as one
			File entry = ctrmap.vault.Vault.entryDir(game, null);
			write(new File(entry, "sealing.in-progress"), "");
			said = restore();
			check(said.size() == 1 && said.get(0).contains("never finished"),
					"an unfinished backup is reported as unfinished, not as absent: " + first(said));
			check(said.size() == 1 && !said.get(0).contains("There is no pristine backup"),
					"and not as both");
			new File(entry, "sealing.in-progress").delete();

			ctrmap.vault.Vault.SealResult sealed
					= ctrmap.vault.Vault.seal(game, ctrmap.vault.Vault.Scope.FULL_RAW, null);
			check(sealed.sealed, "a pristine backup was taken for the rest of these checks (" + sealed.problem + ")");
			//the game is then edited, the way a user does before something breaks
			write(new File(game, "a/0/1/3"), "EDITED");

			//nobody answers: the dialog was closed, or there is no screen
			said = restore();
			check(said.size() == 1 && said.get(0).startsWith("Restore from pristine backup:"),
					"a sealed backup gets the two-way question: " + first(said));
			check(said.size() == 1 && said.get(0).contains("ONE archive keeps everything else you have done"),
					"which says plainly what each choice costs");
			check(str(new File(game, "a/0/1/3")).equals("EDITED"),
					"and an unanswered question restores nothing");

			//Cancel, explicitly
			said = restore("Cancel");
			check(said.size() == 1 && str(new File(game, "a/0/1/3")).equals("EDITED"),
					"Cancel asks nothing further and restores nothing: " + said.size() + " message(s)");

			//one archive, then no filename
			said = restore("Restore ONE archive", null);
			check(said.size() == 2 && said.get(1).startsWith("Restore one archive:"),
					"picking one archive asks which: " + (said.size() > 1 ? first2(said) : said.toString()));
			check(said.size() == 2 && said.get(1).contains(
					Workspace.profile().archivePath(ArchiveType.ZONE_DATA)),
					"naming THIS game's zone table as the example, not a literal one: " + (said.size() > 1 ? said.get(1) : ""));
			check(str(new File(game, "a/0/1/3")).equals("EDITED"),
					"and a cancelled filename restores nothing");

			//one archive, the name the vault actually holds
			said = restore("Restore ONE archive", "a/0/1/3");
			check(str(new File(game, "a/0/1/3")).equals("pristine zone data"),
					"a named archive is put back byte for byte");
			check(said.size() == 3 && said.get(2).startsWith("Restored a/0/1/3:"),
					"and the user is told which file came back: " + said);
			check(str(new File(game, "a/0/4/0")).equals("pristine map matrix"),
					"and nothing else in the game folder was touched");

			//PINNED AS IS: the dialog's own suggestion is refused by the restore
			write(new File(game, "a/0/1/3"), "EDITED AGAIN");
			String offered = Workspace.profile().archivePath(ArchiveType.ZONE_DATA);
			said = restore("Restore ONE archive", offered);
			check(said.size() == 3 && said.get(2).startsWith("Could not restore " + offered + ":"),
					"accepting the path the dialog itself offered is REFUSED today: " + (said.size() > 2 ? said.get(2) : said.toString()));
			check(str(new File(game, "a/0/1/3")).equals("EDITED AGAIN"),
					"and nothing is put back, so the user is left where they started");

			//the whole game, with the confirmation unanswered
			said = restore("Restore the WHOLE game");
			check(said.size() == 2 && said.get(1).startsWith("Restore the whole game?:"),
					"the whole-game choice asks again before overwriting anything: " + (said.size() > 1 ? said.get(1) : said.toString()));
			check(str(new File(game, "a/0/1/3")).equals("EDITED AGAIN"),
					"and an unanswered second question still restores nothing");

			//the whole game, answered
			said = restore("Restore the WHOLE game", JOptionPane.YES_OPTION);
			check(str(new File(game, "a/0/1/3")).equals("pristine zone data"),
					"answered yes, the whole game really is put back");
			check(said.size() == 3 && said.get(2).startsWith("Restored:"),
					"and says so: " + (said.size() > 2 ? said.get(2) : said.toString()));
		} finally {
			if (oldRoot == null) {
				System.clearProperty("ctrmap.vault.root");
			} else {
				System.setProperty("ctrmap.vault.root", oldRoot);
			}
		}
	}

	// ---- with a real ORAS workspace open ------------------------------------

	/**
	 * A stock ZoneData has nothing to remove, and the action must stop there
	 * rather than packing the workspace to find that out.
	 *
	 * <p>"Remove added zones" packs FIRST and deletes afterwards, so reaching
	 * the confirm on a stock archive would mean a full pack for nothing. The
	 * count it reports comes from the archive, so this also pins that the
	 * retail table really is the 538 entries the arithmetic assumes.
	 */
	static void stockZoneDataReportsNoAddedZones() throws Exception {
		System.out.println("--- a stock zone table has no added zones");
		int len = Workspace.getArchive(ArchiveType.ZONE_DATA).length;
		check(len == STOCK_ZONEDATA_ENTRIES,
				"the retail ZoneData holds " + STOCK_ZONEDATA_ENTRIES + " entries (got " + len + ")");
		CtrmapMainframe.mZonePnl = null;
		refuses("removeAddedZonesAction",
				"Remove added zones: This ZoneData has no added zones (stock layout).");
	}

	/**
	 * The three actions that operate on the LOADED zone must say which tab to
	 * open, not just that something is missing.
	 *
	 * <p>Each of them reads the open zone or its index off the owner the window is handed
	 * one line later. With no zone open that is a null dereference on the event
	 * thread; with the guard it is a sentence naming the tab, which is the only
	 * thing a user who has just loaded a workspace needs to hear.
	 */
	static void theActionsThatNeedAZoneSayWhichTab() throws Exception {
		System.out.println("--- the zone-scoped actions name the tab to open");
		CtrmapMainframe.mZonePnl = null;
		CtrmapMainframe.bindLoadedZone(null);
		refuses("blankCanvasAction", "Blank map canvas: Load the zone first (Zone tab).");
		refuses("resizeMapAction", "Resize map: Load the zone first (Zone tab).");
		refuses("setupFacilityAction", "Set up Battle facility: Load the base zone to convert first (Zone tab).");

		//a panel with no zone selected is the same situation, and must read the
		//same way: zoneIndex is -1 until the dropdown has been used
		LoadedZone lz = new LoadedZone();
		ZoneLoadingPanel pnl = new ZoneLoadingPanel(lz, TOOLS, EDITORS, ZONE_EDITORS);
		CtrmapMainframe.mZonePnl = pnl;
		CtrmapMainframe.bindLoadedZone(lz);
		check(lz.index() == -1 && lz.open() == null, "a fresh zone panel holds no zone");
		refuses("blankCanvasAction", "Blank map canvas: Load the zone first (Zone tab).");
		refuses("setupFacilityAction", "Set up Battle facility: Load the base zone to convert first (Zone tab).");
	}

	/**
	 * A facility cannot go in an appended zone, and the refusal must say why
	 * and what to do instead.
	 *
	 * <p>This is the hardest-won fact in the project: zones past the stock 536
	 * cannot run field scripts, so a facility built in one is a room the player
	 * can walk into and nothing else. The refusal is the only place that
	 * knowledge reaches the user, and it has to carry the bound (they need to
	 * know which zones qualify) and the workaround (Empty zone on an unused
	 * base zone), or it is just a no.
	 */
	static void facilitySetupRefusesAnAppendedZone() throws Exception {
		System.out.println("--- a facility refuses an appended zone, and says where to put it instead");
		LoadedZone lz = new LoadedZone();
		ZoneLoadingPanel pnl = new ZoneLoadingPanel(lz, TOOLS, EDITORS, ZONE_EDITORS);
		int baseZones = Workspace.getArchive(ArchiveType.ZONE_DATA).length - 2;
		lz.open(baseZones + 4, null);
		CtrmapMainframe.mZonePnl = pnl;
		CtrmapMainframe.bindLoadedZone(lz);

		List<String> said = record("setupFacilityAction");
		check(said.size() == 1 && said.get(0).startsWith("Set up Battle facility: This is an appended zone (index "
				+ lz.index() + ")"),
				"an appended zone is refused by index: " + first(said));
		check(said.size() == 1 && said.get(0).contains("cannot run field"),
				"because appended zones cannot run scripts");
		check(said.size() == 1 && said.get(0).contains("base zone (< " + baseZones + ")"),
				"the bound is named so the user knows which zones qualify");
		check(said.size() == 1 && said.get(0).contains("Empty zone"),
				"and the workaround is named");
	}

	/**
	 * Both ways to build a facility are offered, and neither runs unasked.
	 *
	 * <p>The action's first question routes between "your own trainers, nothing
	 * vanilla touched" and "replace this zone with a copy of the retail
	 * engine". The second is destructive and irreversible without a backup, so
	 * every unanswered question on the way to it must stop: an unanswered
	 * {@link Ui#option} is -1 and an unanswered {@link Ui#input} is null, and
	 * both mean the user closed the dialog.
	 *
	 * <p>The clone branch is driven up to its final confirm and stopped there.
	 * Past it is {@code ZoneCloner.cloneIntoSlot} followed by a pack, which is
	 * covered by ZoneClonerTest against the bytes; what has never been measured
	 * is the routing that decides whether to call it at all.
	 *
	 * <p>Pinned on the way through, and NOT fixed: the facility kind is matched
	 * with {@code ==} against the option array, so an answer that is EQUAL to
	 * "Battle Institute" but not the same object selects the Maison. A real
	 * JOptionPane returns the very object it was given, so the application is
	 * not wrong today; the check below records that the identity is what the
	 * code depends on, so a refactor that rebuilds the array (or routes the
	 * answer through anything that copies a String) has to trip here rather
	 * than quietly cloning the wrong facility.
	 */
	static void facilitySetupOffersBothWaysAndActsOnNeitherUnasked() throws Exception {
		System.out.println("--- the facility action offers two paths and acts on neither unasked");
		LoadedZone lz = new LoadedZone();
		ZoneLoadingPanel pnl = new ZoneLoadingPanel(lz, TOOLS, EDITORS, ZONE_EDITORS);
		lz.open(100, null); //a base zone, well under the stock bound
		CtrmapMainframe.mZonePnl = pnl;
		CtrmapMainframe.bindLoadedZone(lz);

		List<String> said = record("setupFacilityAction");
		check(said.size() == 1 && said.get(0).startsWith("Set up Battle facility: How should this facility's battles work?"),
				"the first question is which kind of facility: " + first(said));
		check(said.size() == 1 && said.get(0).contains("INDEPENDENT") && said.get(0).contains("CLONE"),
				"and it names both ways honestly");

		said = record("setupFacilityAction", 0);
		check(said.size() == 2 && said.get(1).startsWith("Independent battle facility:"),
				"the independent path explains itself and stops: " + (said.size() > 1 ? first2(said) : said.toString()));
		check(said.size() == 2 && said.get(1).contains("Game Data -> Trainers")
				&& said.get(1).contains("Battle challenge"),
				"naming both halves of the workflow it is asking for");

		said = record("setupFacilityAction", 1, null);
		check(said.size() == 2 && said.get(1).startsWith("Set up Battle facility: Replace zone 100 with a copy"),
				"the clone path asks WHICH retail facility: " + (said.size() > 1 ? said.get(1) : said.toString()));
		check(said.size() == 2, "and a closed chooser stops there, with nothing cloned");

		said = record("setupFacilityAction", 1, "Battle Maison (5 formats, Chatelaines)");
		check(said.size() == 3 && said.get(2).startsWith("Set up Battle facility: Zone 100 will be COMPLETELY REPLACED"),
				"a chosen facility asks for confirmation before replacing anything: "
				+ (said.size() > 2 ? said.get(2) : said.toString()));
		check(said.size() == 3 && said.get(2).contains("opponent pools are ENGINE-WIDE"),
				"and warns that the pools are shared with the retail facility");
		check(said.size() == 3, "and an unanswered confirmation clones nothing");

		//the source zone cannot be its own destination
		lz.open(MAISON_LOBBY_ZONE, null);
		said = record("setupFacilityAction", 1, "Battle Maison (5 formats, Chatelaines)");
		check(said.size() == 3 && said.get(2).equals(
				"Set up Battle facility: That IS the source facility zone - pick a different base zone to convert."),
				"the Maison lobby cannot be replaced by itself: " + (said.size() > 2 ? said.get(2) : said.toString()));

		//the other facility really is a different source, so zone 517 is a legal
		//destination for it - which is what makes the identity check below visible
		said = record("setupFacilityAction", 1, "Battle Institute (single test)");
		check(said.size() == 3 && said.get(2).contains("lobby (zone " + INSTITUTE_ZONE + ")"),
				"choosing the Institute clones zone " + INSTITUTE_ZONE + " instead: "
				+ (said.size() > 2 ? line(said.get(2)) : said.toString()));

		//PINNED AS IS: the answer is matched by IDENTITY, not by equality. It
		//works today only because both sides are the same interned literal; an
		//answer that is equal but not the same object silently falls through to
		//the Maison, which zone 517 then rejects as itself.
		said = record("setupFacilityAction", 1, new String("Battle Institute (single test)"));
		check(said.size() == 3 && said.get(2).contains("That IS the source facility zone"),
				"an equal-but-not-identical answer selects the Maison instead, which zone "
				+ MAISON_LOBBY_ZONE + " rejects as itself: "
				+ (said.size() > 2 ? line(said.get(2)) : said.toString()));
	}

	/**
	 * The OBJ tools default their region spinner to the loaded zone's own map,
	 * so a user never has to know region numbers.
	 *
	 * <p>The default is the FIRST cell of the zone's map matrix. Wrong, and the
	 * user exports one map and imports over another - which is silent, because
	 * both regions are valid and the import succeeds. -1 is the honest "I do
	 * not know", and the spinner then starts at 0 with the dialog saying to
	 * look the id up in the Matrix Editor.
	 */
	static void theObjToolsDefaultToTheLoadedZonesRegion() throws Exception {
		System.out.println("--- the OBJ tools default to the loaded zone's own map region");
		CtrmapMainframe.mZonePnl = null;
		CtrmapMainframe.bindLoadedZone(null);
		check(defaultRegion() == -1, "with no zone owner at all the default is -1, not a guess");

		LoadedZone lz = new LoadedZone();
		ZoneLoadingPanel pnl = new ZoneLoadingPanel(lz, TOOLS, EDITORS, ZONE_EDITORS);
		CtrmapMainframe.mZonePnl = pnl;
		CtrmapMainframe.bindLoadedZone(lz);
		check(defaultRegion() == -1, "with an owner holding no zone it is still -1");

		int zoneIndex = 15; //Mauville: its map matrix is its own in the retail game
		ctrmap.formats.zone.Zone z = new ctrmap.formats.zone.Zone(
				new ctrmap.formats.containers.ZO(temp(Workspace.getArchive(ArchiveType.ZONE_DATA).getDecompressedEntry(zoneIndex)), Workspace.session()),
				Workspace.game());
		lz.open(zoneIndex, z);
		int expected = ctrmap.formats.mapmatrix.MapMatrix.firstRegionId(
				java.nio.file.Files.readAllBytes(Workspace.getWorkspaceFile(
						ArchiveType.MAP_MATRIX, z.header.mapmatrixID).toPath()));
		check(expected >= 0, "zone " + zoneIndex + "'s matrix " + z.header.mapmatrixID
				+ " names region " + expected + " in its first cell");
		check(defaultRegion() == expected,
				"and that is what the OBJ tools offer (got " + defaultRegion() + ")");

		//a header naming a matrix the archive does not have must fall back, not throw
		lz.open().header.mapmatrixID = Workspace.getArchive(ArchiveType.MAP_MATRIX).length + 40;
		check(defaultRegion() == -1,
				"a header naming a matrix that does not exist falls back to -1 rather than throwing");
	}

	// ---- plumbing ----------------------------------------------------------

	/**
	 * A workspace that IS open, on the game given, with not one archive handle
	 * in it.
	 *
	 * <p>This is what the two statics these checks used to set - the game, and
	 * a valid flag - now that the open game is a {@link ctrmap.WorkspaceSession}
	 * and there is no setter for either. It is not a contrivance: a validation
	 * that fails part way leaves exactly this, an identified game whose
	 * archives were never opened, and it is the state the archive guards below
	 * exist for. The folders are scratch paths because a session needs two and
	 * nothing here reads them.
	 */
	static void openWithNoArchives(GameType game) throws Exception {
		File root = Scratch.dir("ctrmap_mainframe_session");
		Sessions.bare(new File(root, "ws"), new File(root, "game"), game);
	}

	/**
	 * The refusal {@code refuseUnsupported} builds for a game that cannot do
	 * the thing asked. Assembled here from the same three parts the window
	 * assembles it from - what was attempted, why it is not offered, and the
	 * game it was attempted on - so a check reads as the sentence the user
	 * gets, and so a change to the FRAME of that sentence fails every site at
	 * once rather than being pasted six times.
	 */
	static String unsupported(String what, String why) {
		String game = Workspace.profile().displayName();
		return what + " is not available for " + game + ".\n\n" + why
				+ "\n\nNone of that has been verified against " + game
				+ ", so CTRMap refuses here rather than writing through a guess.";
	}

	/**
	 * Runs one no-argument action with the dialogs recorded, and requires it to
	 * have said exactly the one thing given, and nothing else.
	 *
	 * <p>Equality rather than "contains" on purpose: a guard that fires with
	 * another action's title has been copied into the wrong method, and a guard
	 * that says two things has fallen through one it should have stopped at.
	 */
	static void refuses(String action, String expected) throws Exception {
		List<String> said = record(action);
		check(said.size() == 1 && said.get(0).equals(expected),
				action + " -> " + expected + (said.size() == 1 && said.get(0).equals(expected)
						? "" : "   (got " + said + ")"));
	}

	/** Drives one no-argument action, answering its questions in order. */
	static List<String> record(String action, Object... answers) throws Exception {
		List<String> said = Ui.record(answers);
		try {
			Method m = CtrmapMainframe.class.getDeclaredMethod(action);
			m.setAccessible(true);
			m.invoke(null);
		} catch (InvocationTargetException ex) {
			//a thrown action is a failure of the action, not of the suite: it
			//is reported as one rather than ending the run at the first one
			said.add("THREW: " + (ex.getCause() == null ? ex : ex.getCause()));
		} finally {
			Ui.stopRecording();
		}
		return said;
	}

	/** The vault action, answering its chain of questions in order. */
	static List<String> restore(Object... answers) throws Exception {
		return record("restoreFromVaultAction", answers);
	}

	/** The mod switch, which is the one action that takes the folder to act on. */
	static List<String> toggle(File modRoot) throws Exception {
		List<String> said = Ui.record();
		try {
			Method m = CtrmapMainframe.class.getDeclaredMethod("toggleModAction", File.class);
			m.setAccessible(true);
			m.invoke(null, modRoot);
		} catch (InvocationTargetException ex) {
			said.add("THREW: " + (ex.getCause() == null ? ex : ex.getCause()));
		} finally {
			Ui.stopRecording();
		}
		return said;
	}

	static int defaultRegion() throws Exception {
		Method m = CtrmapMainframe.class.getDeclaredMethod("defaultRegionForLoadedZone");
		m.setAccessible(true);
		return (Integer) m.invoke(null);
	}

	static File temp(byte[] bytes) throws Exception {
		File f = File.createTempFile("ctrmap_action", ".bin");
		f.deleteOnExit();
		java.nio.file.Files.write(f.toPath(), bytes);
		return f;
	}

	static void write(File f, String s) throws Exception {
		f.getParentFile().mkdirs();
		OutputStream os = new FileOutputStream(f);
		try {
			os.write(s.getBytes("UTF-8"));
		} finally {
			os.close();
		}
	}

	static String str(File f) throws Exception {
		return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
	}

	/** The first line of a report, so a failure prints something readable. */
	static String line(String s) {
		int nl = s.indexOf('\n');
		return nl < 0 ? s : s.substring(0, nl);
	}

	static String first(List<String> said) {
		return said.isEmpty() ? "(nothing was said)" : said.get(0);
	}

	static String first2(List<String> said) {
		return said.size() < 2 ? first(said) : said.get(1);
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
