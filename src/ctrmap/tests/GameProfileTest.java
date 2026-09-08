package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.ZoneTables;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.zone.NpcMoveCodes;
import ctrmap.formats.zone.WarpTransitions;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import ctrmap.gamedef.GameType;
import ctrmap.gamedef.OrasProfile;
import ctrmap.humaninterface.ExtrasPanel;
import ctrmap.humaninterface.builder.Builder;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.TreeMap;

/**
 * The gamedef seam answers what a game CAN DO and what somebody MEASURED about
 * it - and says "not measured" for the games nobody has measured, instead of
 * handing them another game's answer.
 *
 * <h2>The defect this suite exists for</h2>
 * The editor decided per-game questions with two-valued gates:
 * {@code Workspace.isXY()} and {@code Workspace.isOA()}. There are four games.
 * False to {@code isXY()} is true for ORAS, Sun/Moon and Ultra Sun/Ultra Moon
 * alike, so every {@code isXY() ? a : b} handed the Gen 7 games ORAS's answer:
 * <ul>
 * <li>{@code WarpTransitions.labels(false)} gave a Sun/Moon warp ORAS's
 *     eighteen camera-transition rows, and saving the form wrote ORAS's codes
 *     into a Gen 7 record;</li>
 * <li>{@code NpcMoveCodes.movePerm2Labels(false)} offered "Approaching diver
 *     (OA Extension)" for a Sun/Moon NPC, code 22;</li>
 * <li>{@code archive.length - (isXY() ? 1 : 2)} gave a zone count two short on
 *     any game whose archive tail nobody has counted, and named an ordinary
 *     zone as the master zone-header table.</li>
 * </ul>
 * None of that printed anything. Nobody chose it; it fell out of asking a
 * four-valued question with a boolean.
 *
 * <h2>What is checked</h2>
 * <ul>
 * <li>ORAS's measured numbers are re-derived FROM THE DUMP here, so they are
 *     measurements this suite repeats rather than constants it copies;</li>
 * <li>a game with no measured rows gets the BASE rows and nothing else - not
 *     ORAS's, not XY's - in both dropdown tables, and so does no game at all;</li>
 * <li>{@link ZoneTables} refuses an unmeasured game IN WORDS naming it, rather
 *     than returning a count;</li>
 * <li>the ORAS Special Demo's location-name table is selected by the EDITION,
 *     through {@link GameProfile.Variant}, and the demo probe is the profile's
 *     own business;</li>
 * <li>the two application sites that consumed those numbers behave: the Extras
 *     panel's AreaData mass edits cover every area (they were one short) and
 *     derive the per-area table's row stride from the dump, and the Builder
 *     fills a new region's shadow slot only when the measured subfile count
 *     says there is one.</li>
 * </ul>
 *
 * <p>ORDER: installs sessions of its own and leaves one behind, like every
 * other suite that calls {@link Sessions}. Reads the dump, writes nothing.
 *
 * Usage: java ctrmap.tests.GameProfileTest &lt;pristine-garc-root&gt;
 */
public class GameProfileTest {

	/** Games nobody has measured: everything the profile says must be absent. */
	private static final GameType[] UNMEASURED = {GameType.SM, GameType.USUM};

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		anUnmeasuredGameGetsBaseWarpRowsNotOras();
		anUnmeasuredGameGetsBaseMoveRowsNotOras();
		anUnmeasuredGameHasNoNumbersAndNoFeatures();
		theDemoIsAnEditionNotAGame();

		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump + " - the measured counts need the archives");
		} else {
			orasCountsStillMatchTheDump(dump);
			zoneTablesRefusesAnUnmeasuredGameInWords(dump);
			theAreaMassEditsCoverEveryArea(dump);
		}
		theShadowSlotIsWrittenBySlotCountNotByGame();
		theLastGameGatesAskTheProfileInstead();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * The headline: a game with no measured transition table gets the six rows
	 * every game in the family shares, and NOT ORAS's eighteen extras.
	 *
	 * <p>Checked as a relationship rather than against a row count, so it holds
	 * when somebody adds a row: the unmeasured game's list must be exactly the
	 * common prefix of XY's and ORAS's, and no code either game defines on its
	 * own may have a row in it.
	 */
	static void anUnmeasuredGameGetsBaseWarpRowsNotOras() {
		String[] oras = WarpTransitions.labels(GameType.ORAS);
		String[] xy = WarpTransitions.labels(GameType.XY);
		int shared = 0;
		while (shared < oras.length && shared < xy.length && oras[shared].equals(xy[shared])) {
			shared++;
		}
		check(shared > 0 && shared < oras.length && shared < xy.length,
				"XY and ORAS share " + shared + " transition rows and each adds its own");

		for (GameType g : UNMEASURED) {
			String[] rows = WarpTransitions.labels(g);
			int[] raws = WarpTransitions.raws(g);
			check(rows.length == shared, g + ": gets the " + shared + " shared transition rows, not "
					+ oras.length + " (got " + rows.length + ")");
			check(Arrays.equals(rows, Arrays.copyOf(oras, Math.min(shared, rows.length))),
					g + ": and they are the shared rows themselves");
			check(raws.length == rows.length, g + ": one code per row (" + raws.length + "/" + rows.length + ")");
			//the specific codes that used to leak: 10 is ORAS "Ladder up",
			//7 is XY's Lumiose camera rotate
			check(WarpTransitions.index(10, g) == -1, g + ": ORAS transition code 10 has no row here");
			check(WarpTransitions.index(7, g) == -1, g + ": XY transition code 7 has no row here");
			//and the shared ones still work, so this is absence, not breakage
			check(WarpTransitions.index(0, g) == 0 && WarpTransitions.raw(0, g) == 0,
					g + ": the shared code 0 still shows and writes itself back");
		}
		check(WarpTransitions.labels(null).length == shared,
				"no game open: the shared rows, not the last game's (" + WarpTransitions.labels(null).length + ")");
	}

	/** The same property for the NPC motion dropdowns. */
	static void anUnmeasuredGameGetsBaseMoveRowsNotOras() {
		String[] oras = NpcMoveCodes.movePerm2Labels(GameType.ORAS);
		String[] xy = NpcMoveCodes.movePerm2Labels(GameType.XY);
		int shared = 0;
		while (shared < oras.length && shared < xy.length && oras[shared].equals(xy[shared])) {
			shared++;
		}
		check(shared > 0 && shared < oras.length, "XY and ORAS share " + shared + " AI-motion rows");

		int move1Shared = 0;
		String[] m1oras = NpcMoveCodes.movePerm1Labels(GameType.ORAS);
		String[] m1xy = NpcMoveCodes.movePerm1Labels(GameType.XY);
		while (move1Shared < m1oras.length && move1Shared < m1xy.length
				&& m1oras[move1Shared].equals(m1xy[move1Shared])) {
			move1Shared++;
		}

		for (GameType g : UNMEASURED) {
			check(NpcMoveCodes.movePerm2Labels(g).length == shared,
					g + ": gets the " + shared + " shared AI-motion rows, not " + oras.length
					+ " (got " + NpcMoveCodes.movePerm2Labels(g).length + ")");
			check(NpcMoveCodes.movePerm2Raws(g).length == shared, g + ": one code per AI-motion row");
			check(NpcMoveCodes.movePerm1Labels(g).length == move1Shared,
					g + ": gets the " + move1Shared + " shared movement rows, not " + m1oras.length);
			//code 22 is "Approaching diver (OA Extension)" on ORAS and
			//"Rolling skater" on XY - two different meanings, so a game with
			//neither measured must show neither
			check(NpcMoveCodes.movePerm2Index(22, g) == -1, g + ": AI-motion code 22 has no row here");
			check(NpcMoveCodes.movePerm2Index(23, g) == -1, g + ": AI-motion code 23 has no row here");
			check(NpcMoveCodes.movePerm2Index(0, g) == 0, g + ": the shared code 0 still shows");
		}
		check(NpcMoveCodes.movePerm2Labels(null).length == shared,
				"no game open: the shared AI-motion rows only");
	}

	/**
	 * Every number and flag on an unmeasured profile is the "absent" sentinel,
	 * so a caller cannot get a plausible-looking wrong answer out of one.
	 */
	static void anUnmeasuredGameHasNoNumbersAndNoFeatures() {
		for (GameType g : UNMEASURED) {
			GameProfile p = GameProfile.of(g);
			check(p.zoneDataTrailingEntries() == -1, g + ": zoneDataTrailingEntries says NOT MEASURED (-1), got "
					+ p.zoneDataTrailingEntries());
			check(p.areaDataTrailingEntries() == -1, g + ": areaDataTrailingEntries says NOT MEASURED (-1), got "
					+ p.areaDataTrailingEntries());
			check(p.fieldDataSubfileCount() == -1, g + ": fieldDataSubfileCount says NOT MEASURED (-1), got "
					+ p.fieldDataSubfileCount());
			check(!p.zoneNumberInUnknownFlags(), g + ": a zone clone copies unknownFlags verbatim rather"
					+ " than rewriting bits of a layout nobody measured");
			check(p.titleId() == null, g + ": no title id, so the mod deployer offers no folder rather"
					+ " than another game's (got " + p.titleId() + ")");
			check(!p.cyclingFlagSafe(), g + ": the zone-header cycling flag is left clear, because"
					+ " nobody has tried setting it on this game");
			int on = 0;
			for (GameProfile.Feature f : GameProfile.Feature.values()) {
				if (p.supports(f)) {
					on++;
					System.out.println("    " + g + " claims " + f + " with nothing measured behind it");
				}
			}
			check(on == 0, g + ": supports no feature (" + GameProfile.Feature.values().length + " checked)");
		}
		//the three new capability flags exist and ORAS, the reference game, has them
		GameProfile oras = GameProfile.of(GameType.ORAS);
		check(oras.supports(GameProfile.Feature.AREA_FORK)
				&& oras.supports(GameProfile.Feature.ZONE_APPEND)
				&& oras.supports(GameProfile.Feature.ENCOUNTERS),
				"ORAS supports AREA_FORK, ZONE_APPEND and ENCOUNTERS");
		GameProfile xy = GameProfile.of(GameType.XY);
		check(!xy.supports(GameProfile.Feature.AREA_FORK) && !xy.supports(GameProfile.Feature.ZONE_APPEND),
				"XY does not - the fork and append offsets were never measured on it");
		//XY's counts are cited from upstream CTRMap, not measured here, but
		//they are PRESENT: a caller must not be refused for a game the editor
		//does know how to count
		check(xy.zoneDataTrailingEntries() == 1 && xy.areaDataTrailingEntries() == 1
				&& xy.fieldDataSubfileCount() == 6,
				"XY's archive-tail counts are present (1/1/6, established by upstream CTRMap)");
	}

	/**
	 * A demo is an EDITION of a game, not a game. The editor used to write that
	 * as {@code isOA() && isOADemo()} in the location-name accessor, which put
	 * one game's table number in a class every game shares.
	 */
	static void theDemoIsAnEditionNotAGame() throws Exception {
		GameProfile oras = GameProfile.of(GameType.ORAS);
		check(oras.textIndex(GameProfile.TextIndex.LOCATION_NAMES, GameProfile.Variant.RETAIL)
				== oras.textIndex(GameProfile.TextIndex.LOCATION_NAMES),
				"ORAS retail location names: the plain entry ("
				+ oras.textIndex(GameProfile.TextIndex.LOCATION_NAMES) + ")");
		check(oras.textIndex(GameProfile.TextIndex.LOCATION_NAMES, GameProfile.Variant.DEMO)
				== OrasProfile.DEMO_LOCATION_NAMES,
				"ORAS demo location names: its own entry (" + OrasProfile.DEMO_LOCATION_NAMES + ")");
		check(oras.textIndex(GameProfile.TextIndex.SPECIES_NAMES, GameProfile.Variant.DEMO)
				== oras.textIndex(GameProfile.TextIndex.SPECIES_NAMES),
				"and only the table that actually moved is answered differently");

		GameProfile xy = GameProfile.of(GameType.XY);
		check(xy.textIndex(GameProfile.TextIndex.LOCATION_NAMES, GameProfile.Variant.DEMO)
				== xy.textIndex(GameProfile.TextIndex.LOCATION_NAMES),
				"a game with one edition ignores the variant rather than inventing a second answer");

		//the probe is the profile's own: a folder carrying the demo's marker
		//reads as DEMO for ORAS and as RETAIL for a game that has no demo
		File demo = Scratch.dir("ctrmap_gameprofile_demo");
		File probe = new File(demo.getPath() + OrasProfile.DEMO_PROBE);
		probe.getParentFile().mkdirs();
		probe.createNewFile();
		File retail = Scratch.dir("ctrmap_gameprofile_retail");
		check(oras.detectVariant(demo) == GameProfile.Variant.DEMO, "ORAS recognises its own demo dump");
		check(oras.detectVariant(retail) == GameProfile.Variant.RETAIL, "and a dump without the marker is retail");
		check(xy.detectVariant(demo) == GameProfile.Variant.RETAIL,
				"XY does not claim the ORAS demo's marker as its own");
		for (GameType g : UNMEASURED) {
			check(GameProfile.of(g).detectVariant(demo) == GameProfile.Variant.RETAIL,
					g + ": no demo probe of its own, so RETAIL - not another game's answer");
		}

		//and the workspace hands the profile that edition
		Sessions.bare(Scratch.dir("ctrmap_gameprofile_ws"), demo, GameType.ORAS);
		check(Workspace.variant() == GameProfile.Variant.DEMO, "an open ORAS demo session reports DEMO");
		check(ctrmap.formats.text.LocationNames.gametextIndex() == OrasProfile.DEMO_LOCATION_NAMES,
				"and the location-name accessor asks for the demo's entry");
		Sessions.bare(Scratch.dir("ctrmap_gameprofile_ws"), retail, GameType.ORAS);
		check(Workspace.variant() == GameProfile.Variant.RETAIL, "a retail session reports RETAIL");
		check(ctrmap.formats.text.LocationNames.gametextIndex()
				== oras.textIndex(GameProfile.TextIndex.LOCATION_NAMES),
				"and the accessor asks for the retail entry");
		Scratch.deleteTree(demo);
		Scratch.deleteTree(retail);
	}

	/**
	 * Re-derives ORAS's three counts from the pristine dump, so they stay
	 * measurements rather than becoming folklore. A change to the profile that
	 * the archives do not agree with fails here.
	 */
	static void orasCountsStillMatchTheDump(File dump) throws Exception {
		GameProfile p = GameProfile.of(GameType.ORAS);

		GARC zo = new GARC(new File(dump.getAbsolutePath()
				+ Workspace.getArchivePath(ArchiveType.ZONE_DATA, GameType.ORAS)));
		int zones = 0;
		while (zones < zo.length && magicIs(zo.getDecompressedEntry(zones), "ZO")) {
			zones++;
		}
		check(zones > 500, "ZoneData: " + zones + " entries carry the ZO container magic");
		check(zo.length - p.zoneDataTrailingEntries() == zones,
				"zoneDataTrailingEntries() = " + p.zoneDataTrailingEntries() + " puts the zone count at "
				+ (zo.length - p.zoneDataTrailingEntries()) + ", and " + zones + " entries are zones");
		byte[] master = zo.getDecompressedEntry(zones);
		check(master != null && master.length == zones * 0x38,
				"and the entry right after them is the master zone-header table (" + zones + " rows of 0x38)");

		GARC ad = new GARC(new File(dump.getAbsolutePath()
				+ Workspace.getArchivePath(ArchiveType.AREA_DATA, GameType.ORAS)));
		int areas = 0;
		while (areas < ad.length && magicIs(ad.getDecompressedEntry(areas), "AD")) {
			areas++;
		}
		check(areas > 200, "AreaData: " + areas + " entries carry the AD container magic");
		check(ad.length - p.areaDataTrailingEntries() == areas,
				"areaDataTrailingEntries() = " + p.areaDataTrailingEntries() + " puts the area count at "
				+ (ad.length - p.areaDataTrailingEntries()) + ", and " + areas + " entries are areas");
		GARC np = new GARC(new File(dump.getAbsolutePath()
				+ Workspace.getArchivePath(ArchiveType.NPC_REGISTRIES, GameType.ORAS)));
		check(np.length == areas, "the NPC registry, which the engine indexes by the SAME id, holds "
				+ np.length + " entries - one per area");
		byte[] table = ad.getDecompressedEntry(areas);
		check(table != null && table.length == areas * 44,
				"and the entry right after them is the global per-area table (" + areas + " rows of 44)");

		GARC gr = new GARC(new File(dump.getAbsolutePath()
				+ Workspace.getArchivePath(ArchiveType.FIELD_DATA, GameType.ORAS)));
		TreeMap<Integer, Integer> counts = new TreeMap<>();
		for (int i = 0; i < gr.length; i++) {
			byte[] b = gr.getDecompressedEntry(i);
			if (magicIs(b, "GR")) {
				int n = (b[2] & 0xFF) | ((b[3] & 0xFF) << 8);
				Integer c = counts.get(n);
				counts.put(n, c == null ? 1 : c + 1);
			}
		}
		System.out.println("  FieldData subfile counts: " + counts);
		check(!counts.isEmpty() && counts.firstKey() == p.fieldDataSubfileCount(),
				"fieldDataSubfileCount() = " + p.fieldDataSubfileCount() + " is the smallest a region has ("
				+ (counts.isEmpty() ? "none read" : counts.firstKey().toString()) + "); larger ones are multi-layer");
	}

	/**
	 * The behaviour change this step is for: a game whose archive tail nobody
	 * has counted is REFUSED, with a sentence naming it - not given a count
	 * computed from ORAS's number.
	 */
	static void zoneTablesRefusesAnUnmeasuredGameInWords(File dump) throws Exception {
		GARC zo = new GARC(new File(dump.getAbsolutePath()
				+ Workspace.getArchivePath(ArchiveType.ZONE_DATA, GameType.ORAS)));
		File ws = Scratch.dir("ctrmap_gameprofile_zt");

		Sessions.bare(ws, dump, GameType.ORAS);
		int orasCount = ZoneTables.zoneCount(zo);
		check(orasCount == zo.length - 2, "ORAS: ZoneTables counts " + orasCount + " zones from "
				+ zo.length + " entries");
		check(ZoneTables.masterIndex(zo) == orasCount, "and names entry " + orasCount + " as the master table");

		for (GameType g : UNMEASURED) {
			Sessions.bare(ws, dump, g);
			String message = null;
			int wrong = -99;
			try {
				wrong = ZoneTables.zoneCount(zo);
			} catch (IOException ex) {
				message = ex.getMessage();
			}
			check(message != null, g + ": a zone count is REFUSED, not computed (got " + wrong + ")");
			if (message != null) {
				check(message.contains(GameProfile.of(g).displayName()),
						g + ": and the refusal names the game - \"" + firstLine(message) + "\"");
				check(message.contains("zoneDataTrailingEntries"),
						g + ": and says which number is missing");
			}
			message = null;
			try {
				ZoneTables.areaCount(zo);
			} catch (IOException ex) {
				message = ex.getMessage();
			}
			check(message != null && message.contains("areaDataTrailingEntries"),
					g + ": an area count is refused the same way");
		}
		Scratch.deleteTree(ws);
	}

	/**
	 * The Extras panel's AreaData mass edits reach EVERY area, and work out the
	 * per-area table's row stride from the table in front of them.
	 *
	 * <p>Two defects, both live on this tree before the profile answered these
	 * questions:
	 * <ul>
	 * <li>the loop bound was {@code archive.length - (isOA() ? 2 : 1)} = 227 on
	 *     ORAS, one short. Area 227 is a real area - it carries the "AD"
	 *     container magic and a live zone points at it - so the "inject dummy
	 *     camera collision into ALL areas" button had never touched it;</li>
	 * <li>the walk through the global per-area table stepped by a hardcoded
	 *     per-game skip ({@code isXY() ? 0x14 : 0x1c}), which a game that is
	 *     neither answered with ORAS's.</li>
	 * </ul>
	 * The stride is DERIVED here, so it is re-measured from the dump on every
	 * run rather than copied: 10032 bytes over 228 areas is 44, which is
	 * exactly the {@code 0x0f + 1 + 0x1c} the panel used to spell out.
	 */
	static void theAreaMassEditsCoverEveryArea(File dump) throws Exception {
		GARC ad = new GARC(new File(dump.getAbsolutePath()
				+ Workspace.getArchivePath(ArchiveType.AREA_DATA, GameType.ORAS)));
		File ws = Scratch.dir("ctrmap_gameprofile_extras");

		Sessions.overDump(ws, dump);
		int areas = ExtrasPanel.areaEditRange();
		check(areas == ad.length - 1, "ORAS: the AreaData mass edits cover " + areas
				+ " areas of " + ad.length + " entries");
		int oldBound = ad.length - 2;
		check(areas == oldBound + 1, "which is one MORE than the old isOA() bound covered ("
				+ oldBound + ")");
		check(magicIs(ad.getDecompressedEntry(areas - 1), "AD"),
				"and entry " + (areas - 1) + " - the one that bound skipped - is a real area (AD magic)");

		byte[] table = ad.getDecompressedEntry(areas);
		check(!magicIs(table, "AD"), "while entry " + areas + ", the global per-area table, is not");
		int rowSize = -1;
		String strideRefusal = null;
		try {
			rowSize = ExtrasPanel.areaTableRowSize(table.length, areas);
		} catch (IOException ex) {
			//reported rather than thrown: a wrong area count makes the table
			//indivisible, and that must read as a failed check, not as a suite
			//that stopped before the checks after it
			strideRefusal = firstLine(ex.getMessage());
		}
		check(strideRefusal == null, "the per-area table divides into equal rows"
				+ (strideRefusal == null ? "" : " - refused: " + strideRefusal));
		check(rowSize == table.length / areas, "its row stride is derived from the dump: "
				+ table.length + " / " + areas + " = " + rowSize);
		//the two constants the panel used to carry for ORAS, reassembled
		check(rowSize == ExtrasPanel.CAMERA_FLAG_OFFSET + 1 + 0x1c,
				"and reproduces the old hardcoded ORAS camera skip (0xf + 1 + 0x1c = " + rowSize + ")");
		check(rowSize == ExtrasPanel.STEREO_FLAG_OFFSET + 1 + 0x21,
				"and the old hardcoded ORAS 3D skip (0xa + 1 + 0x21)");

		String message = null;
		try {
			ExtrasPanel.areaTableRowSize(table.length + 1, areas);
		} catch (IOException ex) {
			message = ex.getMessage();
		}
		check(message != null && message.contains("not a whole number of equal rows"),
				"a table that does not divide into equal rows is refused in words, not rounded");

		for (GameType g : UNMEASURED) {
			//the SAME archive, opened as another game: the refusal has to come
			//from the profile having no measured tail, not from a missing file
			Workspace.install(new ctrmap.WorkspaceSession(ws, dump, g,
					java.util.Collections.singletonMap(ArchiveType.AREA_DATA, ad)).readOnly());
			message = null;
			int wrong = -99;
			try {
				wrong = ExtrasPanel.areaEditRange();
			} catch (IOException ex) {
				message = ex.getMessage();
			}
			check(message != null, g + ": the mass edits refuse rather than pick a range (got " + wrong + ")");
			if (message != null) {
				check(message.contains(GameProfile.of(g).displayName()),
						g + ": and the refusal names the game - \"" + firstLine(message) + "\"");
			}
		}
		Scratch.deleteTree(ws);
	}

	/**
	 * A new FieldData region gets the shadow ("KAGE") subfile only when its
	 * container HAS a seventh slot to put it in.
	 *
	 * <p>The Builder decided both with {@code isOA()}: the slot count as
	 * {@code isOA() ? 7 : 6}, and whether to write the shadow file at all. That
	 * built a six-slot ORAS-shaped container for Sun/Moon and said nothing.
	 * Tying the write to the measured count instead means the seventh slot is
	 * filled exactly when it exists.
	 */
	static void theShadowSlotIsWrittenBySlotCountNotByGame() {
		int oras = GameProfile.of(GameType.ORAS).fieldDataSubfileCount();
		int xy = GameProfile.of(GameType.XY).fieldDataSubfileCount();
		check(oras > Builder.KAGE_SLOT, "ORAS regions have a slot " + Builder.KAGE_SLOT
				+ " for the shadow file (" + oras + " subfiles)");
		check(xy == Builder.KAGE_SLOT, "XY regions have " + xy + " subfiles, so slot "
				+ Builder.KAGE_SLOT + " does not exist and no shadow file is written");
		for (GameType g : UNMEASURED) {
			check(GameProfile.of(g).fieldDataSubfileCount() < 1, g
					+ ": no measured subfile count, so the Builder refuses instead of building a"
					+ " container with another game's slots");
		}
	}

	/**
	 * The last three sites that asked WHICH GAME, now asking the profile.
	 *
	 * <p>Each was a two-valued gate with three games on the false side:
	 * <ul>
	 * <li>{@code MapResizer.resize} opened with {@code if (!ws.isOA())}, so it
	 *     refused X/Y and let Sun/Moon and Ultra Sun/Ultra Moon through to
	 *     ORAS's container offsets. It asks for AREA_FORK now - the same
	 *     measurements a resize appends with.</li>
	 * <li>{@code ModDeployer.guessTitleId} ended
	 *     {@code isXY() ? X : Omega Ruby}, so a Sun/Moon workspace was offered
	 *     Omega Ruby's emulator mods folder: a mod written where the game it
	 *     was built for never looks, and where another game does.</li>
	 * <li>the Extras panel wrote {@code header.enableCycling = isXY()}, which
	 *     is the right answer for the wrong reason - "not X/Y" rather than
	 *     "ORAS softlocks on a bike, and nobody has tried it on the others".</li>
	 * </ul>
	 */
	static void theLastGameGatesAskTheProfileInstead() throws Exception {
		//1. the resize refuses a game nobody measured, and says which game
		File ws = Scratch.dir("ctrmap_gameprofile_resize");
		for (GameType g : UNMEASURED) {
			Sessions.bare(ws, new File("no-game"), g);
			String message = null;
			try {
				ctrmap.MapResizer.resize(Workspace.session(), 0, 2, 1);
			} catch (IOException ex) {
				message = ex.getMessage();
			}
			check(message != null, g + ": a map resize refuses rather than using ORAS's offsets");
			check(message != null && message.contains(GameProfile.of(g).displayName()),
					g + ": and the refusal names the game - \"" + (message == null ? "(none)" : firstLine(message)) + "\"");
		}
		check(GameProfile.of(GameType.ORAS).supports(GameProfile.Feature.AREA_FORK),
				"...while ORAS, whose offsets those are, still resizes");

		//2. the deployer offers a folder only for a game whose id it knows
		String savedDir = Workspace.GAMEDIR_PATH;
		try {
			//a folder name that is NOT a 16-hex title id, so the profile answers
			Workspace.GAMEDIR_PATH = new File(ws, "RomFS").getAbsolutePath();
			Sessions.bare(ws, new File("no-game"), GameType.ORAS);
			check("000400000011C400".equals(ctrmap.ModDeployer.guessTitleId()),
					"ORAS deploys to Omega Ruby's title id (" + ctrmap.ModDeployer.guessTitleId() + ")");
			Sessions.bare(ws, new File("no-game"), GameType.XY);
			check("0004000000055D00".equals(ctrmap.ModDeployer.guessTitleId()),
					"X/Y deploys to Pokemon X's title id (" + ctrmap.ModDeployer.guessTitleId() + ")");
			for (GameType g : UNMEASURED) {
				Sessions.bare(ws, new File("no-game"), g);
				check(ctrmap.ModDeployer.guessTitleId() == null,
						g + ": no title id is offered, rather than Omega Ruby's (got "
						+ ctrmap.ModDeployer.guessTitleId() + ")");
			}
			check(ctrmap.ModDeployer.azaharModRoot(null) == null,
					"and no id means no mods folder, rather than one named \"null\"");
			//the dumped folder's own name still wins, whatever the game
			Workspace.GAMEDIR_PATH = new File(ws, "000400000011C500").getAbsolutePath();
			check("000400000011C500".equals(ctrmap.ModDeployer.guessTitleId()),
					"a RomFS folder named like a title id is still preferred over the profile's default");
		} finally {
			Workspace.GAMEDIR_PATH = savedDir;
		}

		//3. the cycling flag is a measurement, not an identity test
		check(GameProfile.of(GameType.XY).cyclingFlagSafe(),
				"X/Y tolerates a zone header's cycling flag");
		check(!GameProfile.of(GameType.ORAS).cyclingFlagSafe(),
				"ORAS does not - it softlocks, which is why the mass edit leaves the bit clear");
		for (GameType g : UNMEASURED) {
			check(!GameProfile.of(g).cyclingFlagSafe(),
					g + ": nobody has tried it, so the bit stays clear (the same answer the old"
					+ " isXY() gave, for a reason instead of by accident)");
		}
		Scratch.deleteTree(ws);
	}

	static String firstLine(String s) {
		int nl = s.indexOf('\n');
		return nl < 0 ? s : s.substring(0, nl);
	}

	static boolean magicIs(byte[] b, String magic) {
		if (b == null || b.length < magic.length()) {
			return false;
		}
		for (int i = 0; i < magic.length(); i++) {
			if ((b[i] & 0xFF) != magic.charAt(i)) {
				return false;
			}
		}
		return true;
	}

	static void check(boolean ok, String what) {
		System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what);
		if (!ok) {
			fails++;
		}
	}
}
