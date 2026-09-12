package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.ZoneAppender;
import ctrmap.gamedef.ArchiveType;
import ctrmap.formats.garc.GARC;
import java.io.File;
import java.util.List;

/**
 * The multi-step workflows, driven end to end: do the thing, pack, do it again.
 *
 * <p>WHY THIS SUITE EXISTS, and it is worth saying plainly because the gap it
 * fills cost a user a night. Every other suite in this battery drives ONE
 * operation - a format round-trip, a form's save, a panel's state after a load.
 * Not one of them drives a SEQUENCE. So the app could pass 127 suites and a
 * mutation sweep that killed 209 of 209 mutants while its most fundamental
 * feature was dead, because the failure only appears on the SECOND pass: append
 * a zone, pack, try to append another, and be refused forever by a list nothing
 * prunes - with the refusal telling you to pack, which is what you just did.
 *
 * <p>A guard over one operation cannot see that. This one can, because the
 * shape it asserts is the shape the user works in.
 *
 * <p>WHAT THESE ARE NOT. They are not a substitute for the in-game check: they
 * prove the editor can be driven twice and that what it wrote is still
 * coherent, not that the game will load it. They also do not cover the dialogs
 * those operations are reached through in the app - {@code openGrAction}'s file
 * chooser and the progress dialogs cannot run under the battery. Each workflow
 * is driven at the first method BELOW its dialog, which is the same seam the
 * window calls.
 *
 * <p>ORDER: every section runs on {@link ScratchGame}, a throwaway copy of the
 * dump. These sections PACK, which writes archives, so they must never be
 * pointed at the real game folder.
 *
 * Usage: java ctrmap.tests.WorkflowGuardsTest &lt;romfs-root&gt;
 */
public class WorkflowGuardsTest {

	private static int fails = 0;

	/** Sootopolis, a real multi-region city: a donor big enough that a fork is not trivial. */
	private static final int DONOR = 533;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS/000400000011C400");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump + " - the workflow checks pack real archives");
		} else {
			section("an orphaned entry never refuses an append", () -> anOrphanedEntryNeverRefusesAnAppend(dump));
			section("append, pack, append again", () -> appendPackAppendAgain(dump));
			section("every appended zone owns its map", () -> everyAppendedZoneOwnsItsMap(dump));
			section("a spare left sharing is repaired", () -> spareLeftSharingIsRepaired(dump));
			section("resize, pack, resize again", () -> resizePackResizeAgain(dump));
			section("deploy, disable, deploy again", () -> deployDisableDeployAgain(dump));
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * Append zones, pack, and append again - the sequence that was dead.
	 *
	 * <p>The refusal that blocked it asked whether the new EN slot's path was in
	 * the persisted-file list. Packing writes those files into the archives and
	 * does not clear that list, so the answer stayed yes forever and the second
	 * append was refused with "Pack the workspace before adding more" - naming
	 * the one action that could not help. One orphaned entry (a file listed but
	 * no longer on disk) did the same thing without any append at all.
	 *
	 * <p>Asserted on the ARCHIVE, not on the return value: after the first pack
	 * ZoneData must really have grown, and the second append must be allowed to
	 * run and grow it again. A version that merely returned success without
	 * writing would pass a check on the result and fail this one.
	 */
	static void appendPackAppendAgain(File dump) throws Exception {
		System.out.println("--- append zones, pack, append again");
		ScratchGame.open(dump);
		if (!stockBase()) {
			return;
		}
		int stock = Workspace.getArchive(ArchiveType.ZONE_DATA).length;
		check(stock > 500, "the scratch game starts at the stock zone count (" + stock + " entries)");

		ZoneAppender.AppendResult first = ZoneAppender.appendZones(1, DONOR);
		check(first != null && first.realZones == 1,
				"the first append reports one real zone added");
		check(first.spareZones >= 0, "and says how many spares padded it to a multiple of four ("
				+ first.spareZones + ")");

		List<String> said = pack();
		check(said != null, "the workspace packs");
		int afterFirst = Workspace.getArchive(ArchiveType.ZONE_DATA).length;
		check(afterFirst > stock, "and ZoneData really grew: " + stock + " -> " + afterFirst);

		//THE SECOND APPEND. This is the whole point of the section.
		Exception refused = null;
		ZoneAppender.AppendResult second = null;
		try {
			second = ZoneAppender.appendZones(1, DONOR);
		} catch (Exception ex) {
			refused = ex;
		}
		check(refused == null || !String.valueOf(refused.getMessage()).contains("already pending"),
				"a second append after a pack is not refused as 'already pending': "
				+ (refused == null ? "it ran" : refused.getMessage()));
		if (refused != null) {
			//it may legitimately refuse for a DIFFERENT reason - the appender only
			//supports growing from a stock base - and that refusal must say so in
			//its own words rather than blaming a pending append
			check(String.valueOf(refused.getMessage()).contains("stock")
					|| String.valueOf(refused.getMessage()).contains("Revert"),
					"and if it refuses, it refuses for the reason that is actually true: "
					+ refused.getMessage());
		} else {
			check(second != null && second.realZones == 1, "the second append adds its zone too");
			check(Workspace.getArchive(ArchiveType.ZONE_DATA).length >= afterFirst,
					"and ZoneData did not shrink under it");
		}
	}

	/**
	 * Every zone an append creates owns its own map - the padding ones included.
	 *
	 * <p>Appending rounds up to a multiple of four and the extras are "spares".
	 * They used to keep the DONOR's map, so editing a spare rewrote the city it
	 * was padded out of, and the "Shared map" dialog explained this by saying the
	 * zone "was added before the editor forked new zones automatically" - false
	 * for a zone the running editor had created seconds earlier.
	 *
	 * <p>Asserted by matrix id, which is what sharing means: no zone the append
	 * created may point at the donor's matrix, and no two of them may point at
	 * each other's.
	 */
	static void everyAppendedZoneOwnsItsMap(File dump) throws Exception {
		System.out.println("--- every zone an append creates owns its own map, padding included");
		ScratchGame.open(dump);
		if (!stockBase()) {
			return;
		}
		int stock = Workspace.getArchive(ArchiveType.ZONE_DATA).length;
		int donorMatrix = matrixOf(DONOR);
		check(donorMatrix >= 0, "the donor names a matrix (" + donorMatrix + ")");

		ZoneAppender.AppendResult r = ZoneAppender.appendZones(1, DONOR);
		int added = r.realZones + r.spareZones;
		check(added >= 1, "the append created " + added + " zone(s): " + r.realZones
				+ " real and " + r.spareZones + " spare");
		pack();

		java.util.Map<Integer, Integer> seen = new java.util.TreeMap<>();
		java.util.List<String> sharesDonor = new java.util.ArrayList<>();
		java.util.List<String> sharesSibling = new java.util.ArrayList<>();
		for (int i = 0; i < added; i++) {
			int zone = stock - 2 + i;   //the stock count includes the master+EN tail
			int mat = matrixOf(zone);
			if (mat < 0) {
				continue;
			}
			if (mat == donorMatrix) {
				sharesDonor.add("zone " + zone);
			}
			Integer other = seen.put(mat, zone);
			if (other != null) {
				sharesSibling.add("zones " + other + " and " + zone + " both use matrix " + mat);
			}
		}
		check(sharesDonor.isEmpty(), "no appended zone still points at the donor's map "
				+ sharesDonor);
		check(sharesSibling.isEmpty(), "and no two appended zones share one with each other "
				+ sharesSibling);
		
		//AND THE PADDING ZONES ARE BLANK, WHICH IS A DIFFERENT CLAIM FROM
		//INDEPENDENT. The zones the user asked for are clones of the donor - that is
		//what picking a donor means. The spares were never asked for, so they get an
		//empty slot rather than a second copy of the city. Asserted on the tilemap
		//bytes, against the factory that writes them, so "blank" means the same thing
		//here as it does in Blank map canvas rather than being re-described.
		byte[] blankTiles = ctrmap.formats.h3d.RegionFactory.blankTilemap();
		java.util.List<String> notBlank = new java.util.ArrayList<>();
		long realBytes = 0, spareBytes = 0;
		for (int i = 0; i < added; i++) {
			int zone = stock - 2 + i;
			boolean spare = i >= r.realZones;
			for (int region : regionsOf(zone)) {
				File rf = Workspace.getWorkspaceFile(ArchiveType.FIELD_DATA, region);
				if (rf == null || !rf.isFile()) {
					continue;
				}
				if (spare) {
					spareBytes += rf.length();
					byte[] tiles = new ctrmap.formats.containers.GR(rf, Workspace.session()).getFile(0);
					if (!java.util.Arrays.equals(tiles, blankTiles)) {
						notBlank.add("zone " + zone + " region " + region);
					}
				} else {
					realBytes += rf.length();
				}
			}
		}
		check(notBlank.isEmpty(), "every padding zone is blank, not a second copy of the donor "
			+ notBlank);
		check(realBytes > 0, "the zone that WAS asked for keeps the donor's map ("
			+ realBytes + " bytes of regions)");
		check(r.spareZones == 0 || spareBytes < realBytes * r.spareZones,
			"and the spares cost less than copying it " + r.spareZones + " more times ("
			+ spareBytes + " against " + (realBytes * r.spareZones) + " bytes)");
	}

	/**
	 * An orphaned persisted entry never refuses an append - the regression itself.
	 *
	 * <p>This is the one assertion here that holds on ANY dump, stock or already
	 * grown, because it is about the REASON a refusal gives rather than about
	 * whether the append can run. The persisted-file list is the durable record of
	 * edited extraction files; an entry outlives its file easily, and the appender
	 * used membership of that list as its test for "is one of me already pending?".
	 * So a single orphan refused every future append with "Pack the workspace
	 * before adding more" - the one action that cannot clear that list, in any
	 * version of this program.
	 *
	 * <p>The append may still refuse for a reason that is TRUE (the appender only
	 * grows from a stock base). What it may never do is blame a pending append
	 * that is not there.
	 */
	static void anOrphanedEntryNeverRefusesAnAppend(File dump) throws Exception {
		System.out.println("--- an orphaned persist entry never refuses an append");
		ScratchGame.open(dump);
		int count = ctrmap.ZoneTables.zoneCount(Workspace.getArchive(ArchiveType.ZONE_DATA));
		int master = ctrmap.formats.codepatch.ZoneLimitPatch.masterIndex(1);
		File dir = Workspace.getExtractionDirectory(ArchiveType.ZONE_DATA);
		dir.mkdirs();
		File enSlot = new File(dir, String.valueOf(master + 1));
		//mark it edited, then take the file away: exactly what a pack that threw
		//part way, a revert, or a hand-deleted workspace file leaves behind
		java.nio.file.Files.write(enSlot.toPath(), new byte[]{0});
		Workspace.addPersist(enSlot);
		check(enSlot.delete(), "the EN slot is marked edited and then removed");
		check(Workspace.persistPaths().contains(enSlot.getAbsolutePath()),
			"the list still names it, which is the state that used to be fatal");
		
		String why = "";
		try {
			ZoneAppender.appendZones(1, DONOR);
		} catch (Exception ex) {
			why = String.valueOf(ex.getMessage());
		}
		check(!why.contains("already pending"),
			"the append is not refused as 'already pending' by an entry whose file is gone"
			+ (why.isEmpty() ? " - it ran" : " - it said: " + why));
		check(why.isEmpty() || why.contains("stock") || why.contains("Revert"),
			"and any refusal it does give is the one that is actually true: "
			+ (why.isEmpty() ? "(none)" : why));
		check(count > 0, "measured against a zone table of " + count + " zones");
	}

	/**
	 * Whether this dump still has the stock zone count the appender grows from.
	 *
	 * <p>Appending is ONE-SHOT: {@code appendZones} refuses unless the table is at
	 * the stock base, so a dump that has already been appended to and packed can
	 * not be appended to again until the added zones are removed. The sections
	 * below need a stock base to have anything to measure, and say so out loud
	 * rather than passing on a dump where they did nothing.
	 */
	static boolean stockBase() {
		try {
			int have = ctrmap.ZoneTables.zoneCount(Workspace.getArchive(ArchiveType.ZONE_DATA));
			int stock = ctrmap.formats.codepatch.ZoneLimitPatch.BASE_ZONES;
			if (have != stock) {
				//THE SCRATCH COPY IS BROUGHT BACK TO STOCK RATHER THAN SKIPPED, and doing
				//it through the product's own revert makes this a four-step workflow
				//instead of a three-step one: remove the added zones, append, pack,
				//append again. The owner's dump has already been appended to, so a suite
				//that skipped here would have skipped on the one machine where the defect
				//was found. ScratchGame owns this file; the real game is never touched.
				int removed = ctrmap.ZoneRemover.removeFromFile(
					Workspace.getArchive(ArchiveType.ZONE_DATA).file);
				Workspace.reloadGARC(ArchiveType.ZONE_DATA);
				have = ctrmap.ZoneTables.zoneCount(Workspace.getArchive(ArchiveType.ZONE_DATA));
				System.out.println("  (the scratch copy held " + (have + removed) + " zones; removing "
					+ removed + " added one(s) put it back to " + have + ")");
			}
			if (have != stock) {
				System.out.println("  skip: the scratch copy holds " + have + " zones and would not"
					+ " revert to the stock " + stock + " - there is nothing to append from");
				return false;
			}
			return true;
		} catch (Exception ex) {
			System.out.println("  skip: could not read the zone count: " + ex);
			return false;
		}
	}

	/**
	 * Grow a zone's map, pack, and grow it again.
	 *
	 * <p>A resize appends a new map matrix and new blank regions and repoints the
	 * zone at them - the same append-and-repoint shape as a fork, and the same shape
	 * that broke for zone appending: the SECOND call is the one that meets whatever
	 * the first left behind. GameProfileTest already drives one resize and checks its
	 * refusal on a game that does not support it; nothing drives two.
	 *
	 * <p>Asserted on the ARCHIVES, not on the returned result: both archives must
	 * really have grown each time, and the zone must end up pointing at the matrix
	 * the SECOND resize made, not the first. A version that returned success without
	 * writing, or that repointed the master table and not the zone container, passes
	 * a check on the return value and fails this one.
	 */
	static void resizePackResizeAgain(File dump) throws Exception {
		System.out.println("--- grow a map, pack, grow it again");
		ScratchGame.open(dump);
		int zone = DONOR;
		int matrixBefore = matrixOf(zone);
		int mmCount = Workspace.getArchive(ArchiveType.MAP_MATRIX).length;
		int fdCount = Workspace.getArchive(ArchiveType.FIELD_DATA).length;
		check(matrixBefore >= 0, "the zone starts on matrix " + matrixBefore);
		
		ctrmap.MapResizer.resize(Workspace.session(), zone, 3, 3);
		pack();
		int matrixOnce = matrixOf(zone);
		check(Workspace.getArchive(ArchiveType.MAP_MATRIX).length > mmCount,
			"the first resize appended a matrix (" + mmCount + " -> "
			+ Workspace.getArchive(ArchiveType.MAP_MATRIX).length + ")");
		check(Workspace.getArchive(ArchiveType.FIELD_DATA).length > fdCount,
			"and regions to fill it (" + fdCount + " -> "
			+ Workspace.getArchive(ArchiveType.FIELD_DATA).length + ")");
		check(matrixOnce != matrixBefore, "and the zone points at the new matrix, not the old one ("
			+ matrixBefore + " -> " + matrixOnce + ")");
		
		//THE SECOND RESIZE, which is the whole point of the section
		int mmMid = Workspace.getArchive(ArchiveType.MAP_MATRIX).length;
		ctrmap.MapResizer.resize(Workspace.session(), zone, 4, 4);
		pack();
		int matrixTwice = matrixOf(zone);
		check(Workspace.getArchive(ArchiveType.MAP_MATRIX).length > mmMid,
			"a second resize after a pack appends again (" + mmMid + " -> "
			+ Workspace.getArchive(ArchiveType.MAP_MATRIX).length + ")");
		check(matrixTwice != matrixOnce && matrixTwice != matrixBefore,
			"and the zone follows it to the newest matrix (" + matrixOnce + " -> "
			+ matrixTwice + ")");
	}

	/**
	 * Deploy to a mod folder, switch it off, and deploy again.
	 *
	 * <p>Deploy copies the archives the user actually changed into an emulator mod
	 * folder; disable parks that folder so the retail game comes back. The sequence
	 * that matters is the round trip - deploy, park, deploy again - because parking
	 * MOVES the folder, and a second deploy has to cope with what parking left.
	 *
	 * <p>SAFE TO RUN because deploy() takes the mod root as a parameter: this points
	 * it at scratch space, never at the user's real emulator. That is the only reason
	 * this workflow is testable at all, and it is worth keeping that way.
	 */
	static void deployDisableDeployAgain(File dump) throws Exception {
		System.out.println("--- deploy, switch it off, deploy again");
		ScratchGame.open(dump);
		File modRoot = new File(Scratch.dir("ctrmap_deploy"), "mods_target");
		check(!ctrmap.ModDeployer.isDeployed(modRoot), "nothing is deployed to begin with");
		//DEPLOY SHIPS ONLY WHAT CHANGED, so a workspace with no edits deploys nothing
		//and never creates the folder - correct, and the window guards on isDeployed
		//before offering to switch anything off. The workflow being driven here is the
		//real one, so it starts with an edit.
		ctrmap.MapResizer.resize(Workspace.session(), DONOR, 3, 3);
		pack();
		check(true, "a map was grown, so there is something to ship");
		
		ctrmap.ModDeployer.Result first = ctrmap.ModDeployer.deploy(modRoot, null);
		check(first != null, "the first deploy answers");
		check(ctrmap.ModDeployer.isDeployed(modRoot),
			"and the mod folder is live afterwards (" + first.deployed.size() + " archive(s) written, "
			+ first.unchanged + " unchanged)");
		
		File parked = ctrmap.ModDeployer.disable(modRoot);
		check(parked != null && ctrmap.ModDeployer.isParked(modRoot),
			"switching it off parks the folder rather than deleting it: " + parked);
		check(!ctrmap.ModDeployer.isDeployed(modRoot),
			"and the emulator would load the retail game again");
		
		//DEPLOY AGAIN WHILE IT IS STILL PARKED - the step nothing has ever driven.
		//It must REFUSE, in words: doing it would leave two copies on disk and the
		//on/off switch would try to move the parked one over the live one and fail
		//with a raw path error from a menu item that worked a moment earlier.
		String refusal = "";
		try {
			ctrmap.ModDeployer.deploy(modRoot, null);
		} catch (RuntimeException ex) {
			refusal = String.valueOf(ex.getMessage());
		}
		check(refusal.contains("switched OFF"),
			"deploying while the mod is parked is refused, and says so: " + refusal);
		check(refusal.contains("Turn the mod back ON"),
			"and tells the user what to do about it");
		check(!ctrmap.ModDeployer.isDeployed(modRoot),
			"and wrote nothing, so there is still exactly one copy");

		//AND THE REFUSAL REACHES THE USER, which is a separate claim from the
		//deployer making it. The window deploys from inside the pack worker's
		//completion callback, and a worker swallows whatever its callback throws -
		//so an uncaught refusal is a menu item that silently does nothing, which is
		//worse than the raw path error it replaced. Driven through the window's own
		//method rather than a copy of the logic, because a copy would keep passing
		//after someone deleted the catch.
		java.util.List<String> told = ctrmap.Ui.record();
		try {
			ctrmap.CtrmapMainframe.deployAfterPack(modRoot, null);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(!told.isEmpty(), "a refused deploy tells the user rather than doing nothing: " + told);
		check(!told.isEmpty() && told.get(0).contains("switched OFF"),
			"and what they get is the refusal itself, not some other message: " + told);
		check(!told.isEmpty() && told.get(0).startsWith("Deploy to emulator:"),
			"under the title of the thing they were doing");
		check(ctrmap.ModDeployer.isParked(modRoot) && !ctrmap.ModDeployer.isDeployed(modRoot),
			"and it still wrote nothing - a refusal that half-deployed would be worse than either");
		
		//turn it back on, THEN deploy over it - the sequence that is supposed to work
		ctrmap.ModDeployer.enable(modRoot);
		ctrmap.ModDeployer.Result second = ctrmap.ModDeployer.deploy(modRoot, null);
		check(second != null, "a second deploy after parking answers");
		check(ctrmap.ModDeployer.isDeployed(modRoot),
			"and the mod folder is live again (" + second.deployed.size() + " archive(s) written, "
			+ second.unchanged + " unchanged)");
		check(second.deployed.size() + second.unchanged >= first.deployed.size(),
			"with no archive silently dropped the second time round (" + first.deployed.size()
			+ " then " + (second.deployed.size() + second.unchanged) + ")");
		
		//and the round trip once more, to show the switch still works after a redeploy
		ctrmap.ModDeployer.disable(modRoot);
		File back = ctrmap.ModDeployer.enable(modRoot);
		check(back != null && ctrmap.ModDeployer.isDeployed(modRoot),
			"and the on/off switch still works after a redeploy");
	}

	/**
	 * WHY THERE IS NO ENCOUNTER SECTION HERE, recorded rather than left as a gap
	 * someone has to rediscover.
	 *
	 * <p>Editing wild encounters, packing and editing again is a real workflow with
	 * no coverage, and it belongs in this suite. It cannot be written yet:
	 * {@code EncounterEditDialog.show} builds a modal JDialog and calls setVisible,
	 * and there is no seam below it that a suite can call. test.ps1 runs suites
	 * WITHOUT {@code -Djava.awt.headless=true}, so that would not throw
	 * HeadlessException and fail - it would put a window up and HANG the whole
	 * battery waiting for a click, which is the one outcome a runner cannot report.
	 *
	 * <p>The fix is to extract the decision below the dialog the way
	 * {@code CtrmapMainframe.openChosenGr} was extracted from its file chooser, and
	 * then this section is a dozen lines. Until then this is a named hole, not a
	 * silent one.
	 */
	static void encountersAreNotCoveredHere() {
		//intentionally empty: see the javadoc.
	}

	/** A section that throws is a FAILURE, not a crash that ends the run.
	 *
	 * <p>These sections drive real archive operations, and an operation that has
	 * regressed throws rather than returning a bad answer - which used to kill the
	 * whole suite at the first one, taking the sections after it with it and
	 * leaving a stack trace where a named failure belongs. Worse for the ledger: a
	 * crash prints no FAIL line, so a plant that puts the defect back cannot be
	 * credited by what the suite SAID, and the runner correctly refuses to credit
	 * it at all. Catching here turns the throw into the sentence it should have
	 * been.
	 */
	static void section(String name, Section body) {
		try {
			body.run();
		} catch (Throwable ex) {
			check(false, name + " threw instead of answering: " + ex);
		}
	}

	interface Section {

		void run() throws Exception;
	}

	// ---- plumbing ----------------------------------------------------------

	/**
	 * A padding spare that an OLDER version left sharing the donor's map is given
	 * its own, in one pass, without being asked.
	 *
	 * <p>THE STATE BEING REPAIRED CANNOT BE PRODUCED BY THIS BUILD, which is the
	 * whole point of the repair existing. So it is put back by hand, and by hand
	 * means exactly what 1.0.0 left on disk and nothing more: the spare's ZO header
	 * and its master-table row both pointed at the donor's matrix, because that
	 * version simply never repointed them. Both, because a repair that read one and
	 * not the other would pass a test that wrote only the one it reads.
	 *
	 * <p>What is asserted is the property, not the mechanism: afterwards no
	 * appended zone shares a map with anything, the donor still has the map it
	 * started with, and the repaired slot is EMPTY - because nobody had built on
	 * it, which the repair establishes by comparing the slot against its donor
	 * rather than by trusting a flag nothing was setting.
	 */
	static void spareLeftSharingIsRepaired(File dump) throws Exception {
		System.out.println("--- a spare an older version left sharing is repaired on open");
		ScratchGame.open(dump);
		if (!stockBase()) {
			return;
		}
		int stock = Workspace.getArchive(ArchiveType.ZONE_DATA).length;
		ZoneAppender.AppendResult r = ZoneAppender.appendZones(1, DONOR);
		pack();
		int added = r.realZones + r.spareZones;
		check(r.spareZones > 0, "the append made " + r.spareZones + " padding spare(s) to round up to four");
		
		//PUT 1.0.0 BACK: every spare pointing at the donor's matrix again, in the ZO
		//header and in the master-table row, which is where that version left them.
		int donorMatrix = matrixOf(DONOR);
		java.util.List<Integer> broken = new java.util.ArrayList<>();
		for (int i = r.realZones; i < added; i++) {
			int zone = stock - 2 + i;
			repointZoneAt(zone, donorMatrix);
			broken.add(zone);
		}
		check(!broken.isEmpty() && matrixOf(broken.get(0)) == donorMatrix,
			"put back the old state: spare(s) " + broken + " share the donor's matrix "
			+ donorMatrix + " again");
		
		//THE REPAIR, exactly as the window runs it when a workspace opens
		ctrmap.GeometryForker.RepairReport rep = ctrmap.GeometryForker.repairSharedAppendedZones(
			ctrmap.formats.codepatch.ZoneLimitPatch.BASE_ZONES);
		check(rep.refusedBecause.isEmpty(), "the repair runs rather than refusing: "
			+ (rep.refusedBecause.isEmpty() ? "no refusal" : rep.refusedBecause));
		check(rep.shared.size() == broken.size(),
			"it finds every spare that was left sharing (" + rep.shared + ")");
		check(rep.forked.size() == broken.size(),
			"and gives each one its own map in ONE pass - ensurePrivate could only do one"
			+ " per pack " + rep.forked);
		check(rep.blanked.size() == broken.size(),
			"and empties them, because nobody had built on them " + rep.blanked + " " + rep.kept);
		pack();
		
		check(matrixOf(DONOR) == donorMatrix,
			"the donor still has the map it started with (" + donorMatrix + ") - the repair copies, never writes");
		java.util.List<String> stillSharing = new java.util.ArrayList<>();
		int zoneCount = ctrmap.ZoneTables.zoneCount(Workspace.getArchive(ArchiveType.ZONE_DATA));
		for (int z : broken) {
			int m = matrixOf(z);
			for (int other = 0; other < zoneCount; other++) {
				if (other != z && matrixOf(other) == m) {
					stillSharing.add("zone " + z + " still shares matrix " + m + " with " + other);
				}
			}
		}
		check(stillSharing.isEmpty(), "and no repaired spare shares a map with anything "
			+ stillSharing);
		
		byte[] blankTiles = ctrmap.formats.h3d.RegionFactory.blankTilemap();
		java.util.List<String> notBlank = new java.util.ArrayList<>();
		for (int z : broken) {
			for (int region : regionsOf(z)) {
				File rf = Workspace.getWorkspaceFile(ArchiveType.FIELD_DATA, region);
				if (rf == null || !rf.isFile()) {
					continue;
				}
				byte[] tiles = new ctrmap.formats.containers.GR(rf, Workspace.session()).getFile(0);
				if (!java.util.Arrays.equals(tiles, blankTiles)) {
					notBlank.add("zone " + z + " region " + region);
				}
			}
		}
		check(notBlank.isEmpty(), "and what they were repaired INTO is empty, not another copy"
			+ " of the donor " + notBlank);
		
		//AND IT DOES NOT RUN TWICE. A repair that forked again on the next open would
		//append another set of regions and orphan the ones the zone is now using -
		//1.17 MB a time, measured, and nothing ever reclaims them.
		ctrmap.GeometryForker.RepairReport again = ctrmap.GeometryForker.repairSharedAppendedZones(
			ctrmap.formats.codepatch.ZoneLimitPatch.BASE_ZONES);
		check(again.shared.isEmpty() && !again.changedAnything(),
			"opening the workspace again repairs nothing, because there is nothing left to"
			+ " repair " + again.shared);

		//AND SOMETHING STILL CALLS IT. Everything above drives the repair directly,
		//which is the only way a suite CAN drive it: the window runs it behind
		//frame != null, deliberately, so that opening a scratch copy in this battery
		//never forks and packs underneath the thing a suite is measuring. That leaves
		//the call itself unwatched, and a repair nothing calls is the exact shape of
		//defect this project keeps finding - a fix that is present, correct and never
		//reached. Asked of the BYTECODE, so it cannot be dodged by an import or by
		//splitting the call across lines - and asked about PaddingZoneRepair rather
		//than GeometryForker, because a plant proved the difference: while the repair
		//was wrapped in a private method of the window, the window went on naming it
		//after the call was deleted, and this check passed with nothing reaching it.
		java.util.Set<String> callers = ClassFileScanner.callersOf(
			ClassFileScanner.application(new File("build/classes")),
			"ctrmap/PaddingZoneRepair", "repairOnOpen");
		check(callers.contains("ctrmap/CtrmapMainframe"),
			"and the window still calls it, so a workspace with those spares is repaired"
			+ " without anyone having to know to ask " + callers);
	}

	/** Points a zone's ZO header AND its master-table row at a matrix - what 1.0.0 left. */
	static void repointZoneAt(int zoneIndex, int matrix) throws Exception {
		File zf = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, zoneIndex);
		byte[] b = java.nio.file.Files.readAllBytes(zf.toPath());
		int hdrOff = (b[4] & 0xFF) | ((b[5] & 0xFF) << 8) | ((b[6] & 0xFF) << 16) | ((b[7] & 0xFF) << 24);
		b[hdrOff + 4] = (byte) matrix;
		b[hdrOff + 5] = (byte) (matrix >> 8);
		java.nio.file.Files.write(zf.toPath(), b);
		Workspace.addPersist(zf);
		ctrmap.GeometryForker.repointMasterRow(Workspace.getArchive(ArchiveType.ZONE_DATA), zoneIndex, matrix);
	}
	/** The FieldData regions a zone's matrix names, from the packed archives. */
	static java.util.List<Integer> regionsOf(int zoneIndex) {
		java.util.List<Integer> out = new java.util.ArrayList<>();
		try {
			int matrix = matrixOf(zoneIndex);
			if (matrix < 0) {
				return out;
			}
			File mf = Workspace.getWorkspaceFile(ArchiveType.MAP_MATRIX, matrix);
			if (mf == null || !mf.isFile()) {
				return out;
			}
			byte[] b = java.nio.file.Files.readAllBytes(mf.toPath());
			int s0 = (b[4] & 0xFF) | ((b[5] & 0xFF) << 8) | ((b[6] & 0xFF) << 16) | ((b[7] & 0xFF) << 24);
			int w = (b[s0 + 4] & 0xFF) | ((b[s0 + 5] & 0xFF) << 8);
			int h = (b[s0 + 6] & 0xFF) | ((b[s0 + 7] & 0xFF) << 8);
			for (int k = 0; k < w * h; k++) {
				int id = (b[s0 + 8 + k * 2] & 0xFF) | ((b[s0 + 9 + k * 2] & 0xFF) << 8);
				if (id != 0xFFFF && !out.contains(id)) {
					out.add(id);
				}
			}
		} catch (Exception ex) {
			return out;
		}
		return out;
	}

	/** The matrix id in a zone's header, or -1 when the zone cannot be read. */
	static int matrixOf(int zoneIndex) {
		try {
			File f = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, zoneIndex);
			if (f == null || !f.isFile()) {
				return -1;
			}
			ctrmap.formats.zone.Zone z = new ctrmap.formats.zone.Zone(
					new ctrmap.formats.containers.ZO(f, Workspace.session()), Workspace.game());
			return z.header.mapmatrixID;
		} catch (Exception ex) {
			return -1;
		}
	}

	/** Packs, the way the window does, with no progress dialog. */
	static List<String> pack() throws Exception {
		return Workspace.packArchives((percent, what) -> {
		});
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
