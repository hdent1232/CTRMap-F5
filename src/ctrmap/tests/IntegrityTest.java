package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.WorkspaceIntegrity;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import static ctrmap.util.LittleEndian.i32;

/**
 * Cross-archive references must resolve.
 *
 * <p>Each archive validates on its own; nothing validated the indexes between
 * them. That gap produced the same failure repeatedly - an area fork grew
 * AreaData to 230 entries and NPCRegistries to 229, so the new area existed
 * with no registry behind it and every load of that zone threw. It was found by
 * hand each time, always well after the edit that caused it.
 *
 * <p>Checks the invariants against a scratch copy of the pristine dump, which
 * must satisfy all of them, and against deliberately broken data - a synthetic
 * area table, and a real archive with a matrix cell pointing at a region that
 * does not exist - which must not. A checker that passes everything is worth
 * nothing, and this one silently passed every matrix in the game.
 *
 * Usage: java ctrmap.tests.IntegrityTest &lt;romfs-root&gt;
 */
public class IntegrityTest {

	static int fails = 0;

	/**
	 * F031: the integrity check never compared a zone's own header with its master-table row - the
	 * one divergence the forks and the open-time repair can produce, since they write the two
	 * separately and the game reads only the row. Two zones on one map, hand-built: agreeing, the
	 * check is quiet about headers; zone 1's header moved to area 1 while its row stays on area 0,
	 * the check names zone 1.
	 */
	static void aZoneWhoseHeaderAndRowDisagreeIsNamed() throws Exception {
		System.out.println("--- a zone whose header and master row disagree is named (F031)");
		Workspace.reset();
		try {
			String agreeing = String.valueOf(WorkspaceIntegrity.check(handBuilt("agree", 0, 0), true));
			check(!agreeing.contains("disagree") && !agreeing.contains("could not read any of the"),
					"headers that match their rows are not reported, and the headers were read: " + agreeing);
			String moved = String.valueOf(WorkspaceIntegrity.check(handBuilt("moved", 0, 1), true));
			check(moved.contains("1 zone(s) whose own header and master-table row disagree")
					&& moved.contains("zone 1's area: its header says 1, the master table says 0"),
					"zone 1's header on area 1 and its row on area 0 are reported, naming the zone: " + moved);
		} finally {
			Workspace.reset();
		}
	}

	/**
	 * A master zone table the check cannot read is said, with why - never a deep check that comes back clean.
	 *
	 * <p>Measured by the 2026-10-12 sweep (WorkspaceIntegrity 194, its report dropped, SURVIVED): nothing handed
	 * the check a table it could not read. Dropped, a deep check of a game whose master table is two rows and a
	 * byte came back with nothing to say about the one table every zone's area and map are read from.
	 */
	static void aMasterTableThatCannotBeReadIsSaid() throws Exception {
		System.out.println("--- a master zone table the check cannot read is said, with why");
		Workspace.reset();
		try {
			byte[] torn = java.util.Arrays.copyOf(rowsOnArea0(2), 2 * ctrmap.MasterTable.ROW + 1);
			List<String> bad = WorkspaceIntegrity.check(handBuilt("torn", 0, 0, torn), true);
			check(bad.toString().contains("the master zone table could not be read: ") && bad.size() == 1,
					"a master table of two rows and a byte is reported as unreadable, with why, and nothing else: " + bad);
		} finally {
			Workspace.reset();
		}
	}

	/** Two zones on map 0 with master rows on area 0; zone 1's own header names {@code headerArea1}. */
	static ctrmap.WorkspaceSession handBuilt(String name, int headerArea0, int headerArea1) throws Exception {
		return handBuilt(name, headerArea0, headerArea1, rowsOnArea0(2));
	}

	/** The same, with {@code master} as the master zone table's bytes. */
	static ctrmap.WorkspaceSession handBuilt(String name, int headerArea0, int headerArea1, byte[] master) throws Exception {
		return handBuiltFrom(name, zoneOn(headerArea0), zoneOn(headerArea1), master);
	}

	/**
	 * A header check that could read no zone's header says it verified nothing - never a pass that read nothing
	 * coming back as if every header agreed.
	 *
	 * <p>Measured by the 2026-10-12 sweep (WorkspaceIntegrity 259, its report dropped, SURVIVED): the blindness
	 * check below covers the matrix pass and never this one. Dropped, a game whose 2 ZoneData entries are no zone
	 * containers had its headers "checked" and nothing said.
	 */
	static void aHeaderCheckThatReadNothingSaysSo() throws Exception {
		System.out.println("--- a header check that could read no zone's header says it verified nothing");
		Workspace.reset();
		try {
			byte[] notAZone = "XX not a zone container".getBytes("US-ASCII");
			List<String> bad = WorkspaceIntegrity.check(handBuiltFrom("headless", notAZone, notAZone, rowsOnArea0(2)), true);
			check(bad.toString().contains("the header check could not read any of the 2 zones, so nothing was verified"),
					"two ZoneData entries that are no zone containers: the header check says it read none of the 2: " + bad);
		} finally {
			Workspace.reset();
		}
	}

	/** Two ZoneData entries as given, then the master zone table and the encounter pack. */
	static ctrmap.WorkspaceSession handBuiltFrom(String name, byte[] zone0, byte[] zone1, byte[] master) throws Exception {
		File root = Scratch.dir("ctrmap_integrity_" + name);
		File zo = new File(root, "zonedata");
		HandedGameTest.writeGarc(zo, zone0, zone1, master, "EN".getBytes("US-ASCII"));
		File ad = new File(root, "areas");
		HandedGameTest.writeGarc(ad, new byte[]{1}, new byte[]{2});
		File np = new File(root, "registries");
		HandedGameTest.writeGarc(np, new byte[]{1}, new byte[]{2});
		File mm = new File(root, "matrices");
		HandedGameTest.writeGarc(mm, MapResizeTest.matrix(0));
		File fd = new File(root, "regions");
		HandedGameTest.writeGarc(fd, MapResizeTest.region("region zero"));
		return Sessions.bare(new File(root, "workspace"), new File(root, "game"), GameType.ORAS)
				.withArchive(ArchiveType.ZONE_DATA, new ctrmap.formats.garc.GARC(zo))
				.withArchive(ArchiveType.AREA_DATA, new ctrmap.formats.garc.GARC(ad))
				.withArchive(ArchiveType.NPC_REGISTRIES, new ctrmap.formats.garc.GARC(np))
				.withArchive(ArchiveType.MAP_MATRIX, new ctrmap.formats.garc.GARC(mm))
				.withArchive(ArchiveType.FIELD_DATA, new ctrmap.formats.garc.GARC(fd));
	}

	/** A zone container whose header names {@code area} and map 0. */
	static byte[] zoneOn(int area) {
		byte[] header = new byte[ctrmap.MasterTable.ROW];
		MapResizeTest.putU16(header, ctrmap.ZoneResource.AREA.headerOffset, area);
		return MapResizeTest.container(0x5a4f, header);
	}

	/** {@code zones} master rows, every one on area 0 and map 0. */
	static byte[] rowsOnArea0(int zones) {
		return new byte[zones * ctrmap.MasterTable.ROW];
	}

	public static void main(String[] args) throws Exception {
		//needs no dump: a game written by hand
		aZoneWhoseHeaderAndRowDisagreeIsNamed();
		aMasterTableThatCannotBeReadIsSaid();
		aHeaderCheckThatReadNothingSaysSo();
		String root = args.length > 0 ? args[0] : "../RomFS_original_garcs";
		File gamedir = new File(root);
		if (!gamedir.isDirectory()) {
			Skip.section("no dump at " + gamedir);
			System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
			System.exit(fails == 0 ? 0 : 1);
		}

		if (!new File(gamedir.getAbsolutePath()
				+ Workspace.getArchivePath(ArchiveType.NPC_REGISTRIES,
						GameType.ORAS)).isFile()) {
			check(false, "dump does not carry the archives this checks");
			System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
			System.exit(fails == 0 ? 0 : 1);
		}
		//a scratch copy, because the last check below has to BREAK an archive
		ScratchGame.open(gamedir);

		int areas = Workspace.getArchive(ArchiveType.AREA_DATA).length;
		int regs = Workspace.getArchive(ArchiveType.NPC_REGISTRIES).length;
		int zones = Workspace.getArchive(ArchiveType.ZONE_DATA).length - 2;
		int mats = Workspace.getArchive(ArchiveType.MAP_MATRIX).length;
		int regions = Workspace.getArchive(ArchiveType.FIELD_DATA).length;
		System.out.println("  dump: " + areas + " areas, " + regs + " registries, " + zones
				+ " zones, " + mats + " matrices, " + regions + " regions");

		//--- the retail game must satisfy every invariant ----------------------
		List<String> bad = WorkspaceIntegrity.check(Workspace.session(), true);
		if (bad.isEmpty()) {
			System.out.println("  ok: the retail game passes every cross-archive invariant");
		} else {
			for (String b : bad) {
				System.out.println("  FAIL: retail reports a problem: " + b);
			}
			fails += bad.size();
		}

		//--- the invariant is about INDEXES IN USE, not archive lengths --------
		//Two count-based versions of this rule were written before anyone
		//measured. "One registry per area" calls retail broken. "Registries
		//trail areas by one" matches retail by accident - retail's highest
		//areadataID is 227 against 228 registries, so ten AreaData entries are
		//never named by any zone at all. Neither notices the failure that
		//actually happens: a forked zone whose new area id is past the end of
		//the registry archive.
		int maxArea = highestAreaInUse();
		System.out.println("  highest areadataID in use: " + maxArea
				+ " (registries go up to " + (regs - 1) + ")");
		check(maxArea < regs, "every area id in use has a registry at that index");
		check(maxArea < areas, "every area id in use has an AreaData entry");
		check(!needsMore(227, 228), "retail's 227-against-228 is not reported");
		check(needsMore(229, 229), "an area id past the last registry IS reported");

		danglingRegionIsFound(mats, regions);
		aPassThatUnderstoodNoMatrixSaysSo();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * A matrix cell naming a FieldData region that does not exist must be
	 * reported - and the pass has to reach every matrix to find it.
	 *
	 * <p>The region pass read the grid's width and height from offset 4 and 6
	 * of the raw MapMatrix container. A Gamefreak container starts u16 magic,
	 * u16 subfile count, u32 offsets[], so those bytes are offsets[0]: all 431
	 * retail matrices measured 16x0, every one failed the shape test, and the
	 * pass scanned nothing while reporting a clean bill of health. The check
	 * lists "a zone fork rewired a map matrix at a region index the FieldData
	 * archive did not yet have" as one of the three incidents it was written
	 * for, and it could not have caught any of them.
	 *
	 * <p>Breaks the LAST matrix, not the first: a pass that stops early is the
	 * same silent failure in a smaller size.
	 */
	static void danglingRegionIsFound(int matrices, int regions) throws Exception {
		int last = matrices - 1;
		File matFile = Workspace.getWorkspaceFile(ArchiveType.MAP_MATRIX, last);
		byte[] mat = Files.readAllBytes(matFile.toPath());
		int sub0 = i32(mat, 4);
		int cell = sub0 + 8;
		int dangling = regions + 4143; //well past the end, and not 0xFFFF
		mat[cell] = (byte) (dangling & 0xFF);
		mat[cell + 1] = (byte) ((dangling >> 8) & 0xFF);
		Files.write(matFile.toPath(), mat);
		Workspace.addPersist(matFile);
		Workspace.packArchives((percent, what) -> {
		});

		String bad = WorkspaceIntegrity.check(Workspace.session(), true).toString();
		check(bad.contains("FieldData region that does not exist"),
				"a matrix cell naming region " + dangling + " of " + regions + " is reported: " + bad);
		check(bad.contains("matrix " + last),
				"and the pass reached the last matrix (" + last + ") to find it");
	}

	/**
	 * The check reporting its own blindness. When MapMatrix holds entries but
	 * the region pass could make sense of none of them, it must SAY that
	 * nothing was verified rather than fall through to a clean bill of health.
	 *
	 * <p>This is not hypothetical: the pass read the grid's width and height
	 * out of the container header instead of subfile 0, measured every one of
	 * the 431 retail matrices as 16x0, scanned nothing at all, and reported
	 * clean - for however long it took somebody to notice. That is the worst
	 * failure available to a checker, because an integrity check that reports
	 * nothing is indistinguishable from one that found nothing wrong, and the
	 * whole point of running it is to be able to tell those apart.
	 *
	 * <p>The sentence at WorkspaceIntegrity:250 is the guard, and nothing
	 * asserted it. Here MapMatrix is swapped for the same archive with every
	 * entry coming back as four bytes that are no container at all - the same
	 * state the original defect produced, reached without waiting for another
	 * header to be misread - and the report has to name it. Then the real
	 * archive goes back and the sentence has to be gone.
	 */
	static void aPassThatUnderstoodNoMatrixSaysSo() throws Exception {
		ctrmap.formats.garc.GARC real = Workspace.getArchive(ArchiveType.MAP_MATRIX);
		String legible = WorkspaceIntegrity.check(Workspace.session(), true).toString();
		check(!legible.contains("could not read any of the"),
				"with matrices it can read, the check does not claim it read none of them: " + legible);

		//the check is handed a session, so the blind one is simply built and
		//passed - nothing installed, nothing to restore
		ctrmap.WorkspaceSession blinded = Workspace.session().withArchive(ArchiveType.MAP_MATRIX,
				new ctrmap.formats.garc.GARC(real.file) {
			@Override
			public byte[] getDecompressedEntry(int num) {
				return new byte[]{0, 0, 0, 0}; //no Gamefreak container, so no grid: unreadable
			}
		});
		String blind = WorkspaceIntegrity.check(blinded, true).toString();
		check(blind.contains("could not read any of the " + real.length + " matrices"),
				"a region pass that understood no matrix at all says so, and how many it gave up on: " + blind);
		check(blind.contains("nothing was verified"),
				"and says what that costs the user: " + blind);
		check(!blind.contains("FieldData region that does not exist"),
				"and does not also report the dangling region it never got to read: " + blind);
		check(!WorkspaceIntegrity.check(Workspace.session(), true).toString().contains("could not read any of the"),
				"with the real archive back, the check stops saying it: it is a report about"
				+ " THIS pass, not a line that is always printed");
	}

	/** The rule in isolation: an id is unusable when no registry has that index. */
	static boolean needsMore(int maxAreaInUse, int registryCount) {
		return maxAreaInUse >= registryCount;
	}

	/** Highest areadataID any zone actually names, from the master table. */
	static int highestAreaInUse() throws Exception {
		ctrmap.formats.garc.GARC zo = Workspace.getArchive(ArchiveType.ZONE_DATA);
		byte[] m = zo.getDecompressedEntry(zo.length - 2);
		int best = -1;
		for (int z = 0; z < Math.min(m.length / 0x38, zo.length - 2); z++) {
			int a = (m[z * 0x38 + 2] & 0xFF) | ((m[z * 0x38 + 3] & 0xFF) << 8);
			best = Math.max(best, a);
		}
		return best;
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
