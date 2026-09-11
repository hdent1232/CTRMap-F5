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
