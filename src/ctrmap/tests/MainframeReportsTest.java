package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.GeometryForker;
import ctrmap.Ui;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * What the main window tells the user after it has done something.
 *
 * <p>These sentences are the only way the user can know what happened, and
 * every one of them lives behind a modal dialog in an anonymous listener, so
 * none of them was ever measured. A mutation sweep broke three and the whole
 * battery stayed green:
 *
 * <ul>
 * <li>the geometry fork reporting "nothing was changed" - get that branch
 *     backwards and a fork that DID append private copies says it did nothing,
 *     so the user forks again, and the second fork orphans the regions the zone
 *     is now using (measured before: four forks of zone 15 grew FieldData by
 *     1.17 MB and left three matrices referenced by nobody);</li>
 * <li>the warning that the private copy still carries ground belonging to other
 *     zones - without it the user relabels that ground and takes those zones'
 *     banner, music and entities away, with nothing said;</li>
 * <li>"Open MapMatrix" failing to read the file the user picked - delete the
 *     message and picking a file that is not a map matrix is
 *     indistinguishable from the editor ignoring the click.</li>
 * </ul>
 *
 * <p>The report is now built by a function that returns the text, and the
 * failure is said through {@link Ui}, so both are facts a headless suite can
 * check. Nothing about the dialogs themselves changed.
 *
 * Usage: java ctrmap.tests.MainframeReportsTest &lt;romfs-root&gt;
 */
public class MainframeReportsTest {

	/** Mauville: its map matrix is already its own in the retail game. */
	private static final int PRIVATE_ZONE = 15;
	/** Fallarbor Town: shares matrix 8 with Routes 111, 112, 113 and 114. */
	private static final int SHARED_ZONE = 10;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump);
			System.out.println("ALL PASS");
			return;
		}
		ScratchGame.open(dump);

		aForkThatDidNothingSaysSo();
		aForkThatWorkedSaysWhatItDid();
		everyRegionTheForkMadeIsAccountedFor();
		groundBelongingToOtherZonesIsNamed();
		aFileThatIsNotAMapMatrixSaysSo();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** A zone that already owns its map must be told nothing was done. */
	static void aForkThatDidNothingSaysSo() throws Exception {
		GeometryForker.ForkResult r = GeometryForker.ensurePrivate(PRIVATE_ZONE);
		check(!r.forked, "zone " + PRIVATE_ZONE + "'s map is already its own, so nothing was forked");
		String said = CtrmapMainframe.forkGeometryReport(PRIVATE_ZONE, r);
		check(said.contains("already has its own map geometry"),
				"and the user is told it already had one: " + firstLine(said));
		check(said.contains("Nothing was changed"), "and that nothing was changed");
		check(!said.contains("now has private map geometry"),
				"and is NOT told a fork happened, which would send them to fork again");
	}

	/** A fork that did happen must say what it appended. */
	static void aForkThatWorkedSaysWhatItDid() throws Exception {
		GeometryForker.ForkResult r = GeometryForker.ensurePrivate(SHARED_ZONE);
		check(r.forked, "zone " + SHARED_ZONE + " shares its map, so it really is forked");
		String said = CtrmapMainframe.forkGeometryReport(SHARED_ZONE, r);
		check(said.contains("Zone " + SHARED_ZONE + " now has private map geometry"),
				"and the user is told so: " + firstLine(said));
		check(said.contains("Map matrix " + r.oldMatrix + " -> " + r.newMatrix),
				"and which matrix the copy is (" + r.oldMatrix + " -> " + r.newMatrix + ")");
		check(said.contains("FieldData region " + r.srcRegions[0] + " -> " + r.newRegions[0]),
				"and which region to edit (" + r.srcRegions[0] + " -> " + r.newRegions[0] + ")");
		check(!said.contains("already has its own map geometry"),
				"and is NOT told nothing was changed");
	}

	/**
	 * A zone whose map is several regions gets several private copies, and the
	 * report names ONE of them as the place to edit. It has to say the rest are
	 * listed above it.
	 *
	 * <p>Zone 10 above forks eighteen regions. Told to "edit region 857" and
	 * nothing else, the user edits an eighteenth of their own map and reads the
	 * silence about the other seventeen as the fork having half worked - the
	 * likeliest next move being to fork again, which appends another eighteen
	 * copies and orphans the ones the zone is now using. Said the other way
	 * round, a one-region fork sends them looking above for regions that were
	 * never made.
	 */
	static void everyRegionTheForkMadeIsAccountedFor() {
		GeometryForker.ForkResult many = forked();
		many.srcRegions = new int[]{200, 201, 202};
		many.newRegions = new int[]{857, 858, 859};
		String said = CtrmapMainframe.forkGeometryReport(9, many);
		check(said.contains("edit region 857 (and the other new regions above)"),
				"a fork of 3 regions points at the first and says the rest are above it: " + tail(said));
		check(said.contains("FieldData region 202 -> 859"),
				"and the ones above it are really listed, so \"above\" names something");

		GeometryForker.ForkResult one = forked();   //newRegions = {857}
		String single = CtrmapMainframe.forkGeometryReport(9, one);
		check(!single.contains("and the other new regions above"),
				"and a fork of ONE region does not send them hunting for regions that were never made: "
				+ tail(single));
	}

	/**
	 * The private copy can carry ground that belongs to other zones. Saying so
	 * is the difference between the user leaving those labels alone and
	 * relabelling them, which silently takes those zones' banner, music and
	 * entities away.
	 */
	static void groundBelongingToOtherZonesIsNamed() {
		GeometryForker.ForkResult carries = forked();
		carries.otherZones = new int[]{111, 112};
		String said = CtrmapMainframe.forkGeometryReport(9, carries);
		check(said.contains("carries ground belonging to zone(s) [111, 112]"),
				"a copy carrying other zones' ground names them: " + tail(said));
		check(said.contains("keeps their labels"), "and says the labels were kept");

		GeometryForker.ForkResult alone = forked();
		String quiet = CtrmapMainframe.forkGeometryReport(9, alone);
		check(!quiet.contains("carries ground belonging"),
				"and a copy that carries nobody else's ground does not say it does: " + tail(quiet));
	}

	/** A fork result for a zone that got its own copy of one region. */
	static GeometryForker.ForkResult forked() {
		GeometryForker.ForkResult r = new GeometryForker.ForkResult();
		r.zoneIndex = 9;
		r.forked = true;
		r.oldMatrix = 8;
		r.newMatrix = 30;
		r.srcRegions = new int[]{200};
		r.newRegions = new int[]{857};
		return r;
	}

	/**
	 * Picking a file that is not a map matrix must say so, not do nothing.
	 *
	 * <p>A file too short to hold a container header, because that is where the
	 * reader actually refuses: {@code MapMatrix}'s constructor swallows every
	 * parse error it meets, so a long file of the wrong kind loads as a
	 * nonsense grid instead (worth its own fix, in its own area). What reaches
	 * the user here has to come from reading the file, not from a panel this
	 * suite never built, which is what the last check pins down.
	 */
	static void aFileThatIsNotAMapMatrixSaysSo() throws Exception {
		File notAMatrix = Scratch.file("ctrmap_not_a_matrix");
		Files.write(notAMatrix.toPath(), new byte[]{'M', 'M'});
		List<String> said = Ui.record();
		boolean ok;
		try {
			ok = CtrmapMainframe.openMapMatrixFile(notAMatrix);
		} finally {
			Ui.stopRecording();
		}
		check(!ok, "a file that is not a map matrix is refused");
		check(!said.isEmpty(), "and the user is told why rather than watching nothing happen: " + said);
		check(!said.isEmpty() && said.get(0).startsWith("Open MapMatrix:"),
				"under the title of the thing they were doing");
		check(!said.isEmpty() && said.get(0).length() > "Open MapMatrix: ".length() + 4,
				"with something in it a person can act on: " + said);
		check(!said.isEmpty() && !said.get(0).contains("mTileMapPanel"),
				"and the refusal came from reading the file, not from a panel this suite never built");
	}

	static String firstLine(String s) {
		int nl = s.indexOf('\n');
		return nl < 0 ? s : s.substring(0, nl);
	}

	static String tail(String s) {
		return s.length() < 200 ? s : "..." + s.substring(s.length() - 200);
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
