package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.GeometryForker;
import ctrmap.Ui;
import ctrmap.Workspace;
import ctrmap.ZoneManager;
import ctrmap.gamedef.GameType;
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
 * <p>It also covers the sentence a menu item gives a user whose game the editor
 * cannot work on - see {@link #aRefusedEditorNamesTheGameAndWhatIsMissing}. That
 * is the same kind of claim: the only thing the user is told, said from behind a
 * modal dialog, and for four games it was one sentence that was true for none of
 * them.
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
		//needs no game: the rename result is a plain record, and what the user
		//is told about it is the thing under test
		aRenameThatMovedOtherZonesSaysSo();

		//nor do these: a game the editor cannot do the job for is a session, not
		//a dump. Run before the dump gate so they are measured even on a machine
		//that has no corpus.
		aRefusedEditorNamesTheGameAndWhatIsMissing();
		aGameThatCannotForkIsNotOfferedTheForkPrompt();

		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump + " - the fork and matrix checks need one");
			System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
			if (fails > 0) {
				System.exit(1);
			}
			return;
		}
		ScratchGame.open(dump);

		aForkThatDidNothingSaysSo();
		aForkThatWorkedSaysWhatItDid();
		everyRegionTheForkMadeIsAccountedFor();
		groundBelongingToOtherZonesIsNamed();
		aFileThatIsNotAMapMatrixSaysSo();
		aFileThatIsNotAMapMatrixLeavesTheViewAlone();

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

	/**
	 * ...and the editor must not then carry on as though a map had loaded.
	 *
	 * <p>Saying so and then acting as if nothing had gone wrong is worse than
	 * saying nothing: the message goes away and the view is left refitted to
	 * whatever was on screen before, so what the user is looking at no longer
	 * matches what they think they opened. The menu action's decision - open,
	 * and fit the view only if it opened - is
	 * {@link CtrmapMainframe#openChosenMapMatrix}, so it can be driven with the
	 * fitting handed in as something this suite can watch for.
	 *
	 * <p>Only the refusal is driven here: succeeding needs the tilemap panel,
	 * and building one asks JOGL for a GL profile that the battery has no
	 * natives for. That is enough to pin the decision down - getting it
	 * backwards fits the view to a map that never loaded, which is what this
	 * asserts cannot happen.
	 */
	static void aFileThatIsNotAMapMatrixLeavesTheViewAlone() throws Exception {
		File notAMatrix = Scratch.file("ctrmap_not_a_matrix");
		Files.write(notAMatrix.toPath(), new byte[]{'M', 'M'});
		final boolean[] viewRefitted = {false};
		List<String> said = Ui.record();
		try {
			CtrmapMainframe.openChosenMapMatrix(notAMatrix, new Runnable() {
				@Override
				public void run() {
					viewRefitted[0] = true;
				}
			});
		} finally {
			Ui.stopRecording();
		}
		check(!viewRefitted[0],
				"a file that could not be read does not go on to fit the view to a map that never loaded");
		check(!said.isEmpty() && said.get(0).startsWith("Open MapMatrix:"),
				"and what the user gets instead is the reason it was not read: " + said);
	}

	/**
	 * A rename that moved OTHER zones has to say so.
	 *
	 * <p>Location names are shared - in the retail game one line names Fallarbor
	 * Town and Routes 111 to 114 together - and {@link ctrmap.ZoneManager} tries
	 * to give the renamed zone a private line so the rest keep theirs. The
	 * game's place-id bound is hard, though, so when no free line is left it
	 * edits the shared line instead and every zone on it takes the new name.
	 *
	 * <p>From the map the two outcomes are identical: the zone the user asked
	 * about is called what they typed. This sentence is the only thing that
	 * tells them four routes came with it. Said the wrong way round, the user
	 * ships a game with four routes named after their new town and no idea it
	 * happened; said the other wrong way round, they go hunting for damage that
	 * a private name line means was never done.
	 */
	static void aRenameThatMovedOtherZonesSaysSo() {
		ZoneManager.RenameResult shared = new ZoneManager.RenameResult();
		shared.oldName = "Fallarbor Town";
		shared.sharers = 5;
		shared.renamedSharers = true;
		String said = CtrmapMainframe.renameZoneReport(10, "Delta Town", shared);
		check(said.contains("ALL of them were renamed"),
				"a rename with no free name slot says every zone on that name moved: " + said.split("\n\n")[1]);
		check(said.contains("shared by 5 zones"), "and how many there were (5)");
		check(said.contains("\"Fallarbor Town\""), "and what the name they shared was");
		check(!said.contains("the others are unchanged"),
				"and does NOT also claim the others are unchanged");

		ZoneManager.RenameResult forked = new ZoneManager.RenameResult();
		forked.oldName = "Fallarbor Town";
		forked.sharers = 5;
		forked.gaveOwnName = true;
		said = CtrmapMainframe.renameZoneReport(10, "Delta Town", forked);
		check(said.contains("it now has its own name and the others are unchanged"),
				"a rename that got its own name slot says the others were left alone: " + said.split("\n\n")[1]);
		check(!said.contains("ALL of them were renamed"),
				"and does NOT warn about damage it did not do");

		ZoneManager.RenameResult alone = new ZoneManager.RenameResult();
		alone.oldName = "Mauville City";
		alone.sharers = 1;
		String solo = CtrmapMainframe.renameZoneReport(15, "Delta City", alone);
		check(solo.contains("The name belonged to this zone alone"),
				"a zone that owned its name is told nothing else moved: " + solo.split("\n\n")[1]);
		check(!solo.contains("ALL of them") && !solo.contains("the others are unchanged"),
				"and gets neither of the shared-name sentences");

		for (String s : new String[]{said, solo}) {
			check(s.startsWith("Zone "), "every one names the zone it renamed first: " + firstLine(s));
			check(s.contains("Deploy to emulator"), "and says what to do next to see it in game");
		}
	}

	/**
	 * The five Game Data / area editors, opened on a game they cannot edit,
	 * must name THAT GAME and say what has not been measured for it.
	 *
	 * <p>WHY. Each one opened with {@code !Workspace.isValid() || !isOA()} and
	 * said "Load an ORAS workspace first." That sentence is wrong in three ways
	 * at once for a user who has X/Y open: a workspace IS loaded, so the
	 * instruction cannot be followed; it says nothing about what the editor
	 * would have needed; and Sun/Moon - a game whose overworld is not even the
	 * same format generation - was told exactly the same thing, so the message
	 * carried no information about the game at all. It was the shape of a
	 * boolean, not an answer. The gates now ask the profile for the CAPABILITY
	 * (TRAINER_EDITING, MAISON, CODE_PATCHES, AREA_ENV, ENCOUNTERS), which has a
	 * third answer - "nobody measured that for this game" - and the refusal is
	 * built out of it.
	 *
	 * <p>Sun/Moon is the game driven here on purpose: it is the one whose
	 * profile answers false to every feature and null to every path, so a gate
	 * that still asked "is it ORAS" and a gate that asks the profile agree on
	 * WHETHER to refuse and disagree only on what they say. That is the whole
	 * difference this measures.
	 *
	 * <p>The dump is not needed: none of these gates reads an archive before
	 * refusing, and that is itself worth pinning down - a refusal that had to
	 * open a GARC first would fail on the game it exists to refuse.
	 */
	static void aRefusedEditorNamesTheGameAndWhatIsMissing() throws Exception {
		//with nothing open at all, the instruction "load a workspace" is the
		//true one - and the profile must not be asked for a game that is null
		Workspace.reset();
		for (String[] c : EDITORS) {
			List<String> said = Ui.record();
			try {
				openEditor(c[0]);
			} finally {
				Ui.stopRecording();
			}
			check(said.size() == 1 && said.get(0).startsWith(c[1] + ": Load a workspace first"),
					"with no workspace open, " + c[0] + " says to load one: " + said);
		}

		//...and with Sun/Moon open, the refusal is about Sun/Moon
		File root = Scratch.dir("ctrmap_unmeasured_game");
		Sessions.bare(new File(root, "ws"), new File(root, "game"), GameType.SM);
		try {
			for (String[] c : EDITORS) {
				List<String> said = Ui.record();
				try {
					openEditor(c[0]);
				} finally {
					Ui.stopRecording();
				}
				check(said.size() == 1, c[0] + " on Sun/Moon says exactly one thing: " + said);
				String msg = said.isEmpty() ? "" : said.get(0);
				check(msg.startsWith(c[1] + ": "),
						"under the title of the editor that was opened: " + firstLine(msg));
				check(msg.contains("Sun / Moon"),
						"and names the game the user actually has open: " + firstLine(msg));
				check(msg.contains(c[2]),
						"and says which capability is missing (\"" + c[2] + "\"): " + firstLine(msg));
				check(!msg.contains("Load an ORAS workspace first"),
						"and does NOT tell a user with a workspace open to load one: " + firstLine(msg));
			}
		} finally {
			Workspace.reset();
		}
	}

	/**
	 * A game that cannot fork an area is not OFFERED the fork.
	 *
	 * <p>{@link ctrmap.humaninterface.AreaForkPrompt#ensurePrivate} gated itself
	 * on {@code Workspace.isOA()}, which is the same defect wearing a different
	 * hat: the prompt is an OFFER, and offering "Give this zone its own area" to
	 * a game whose {@link ctrmap.AreaForker} refuses outright produces a button
	 * that can only ever fail. It now asks whether this game supports
	 * AREA_FORK. On Sun/Moon it must hand back the shared area untouched and say
	 * nothing at all - no question, and above all no fork.
	 *
	 * <p>HONESTLY: on the four profiles that exist today the two forms agree,
	 * because ORAS is the only game that both IS ORAS and supports AREA_FORK.
	 * This is a contract pinned before it is load-bearing - it starts telling
	 * the two apart the day a second game's fork offsets are measured and its
	 * flag flips, which is exactly when nobody will be looking at this prompt.
	 */
	static void aGameThatCannotForkIsNotOfferedTheForkPrompt() throws Exception {
		File root = Scratch.dir("ctrmap_unmeasured_fork");
		Sessions.bare(new File(root, "ws"), new File(root, "game"), GameType.SM);
		try {
			List<String> said = Ui.record();
			ctrmap.AreaForker.ForkResult r;
			try {
				r = ctrmap.humaninterface.AreaForkPrompt.ensurePrivate(new ctrmap.LoadedZone(), null, 10, 7, "a test edit");
			} finally {
				Ui.stopRecording();
			}
			check(said.isEmpty(),
					"a game that cannot fork an area is asked nothing about forking one: " + said);
			check(r != null && !r.forked && r.newArea == 7 && r.oldArea == 7 && r.zoneIndex == 10,
					"and the caller is handed the area it already had, unforked: "
					+ (r == null ? "null" : r.newArea + ", forked=" + r.forked));
		} finally {
			Workspace.reset();
		}
	}

	/**
	 * The editors driven above, as {which, dialog title, a phrase from the
	 * missing capability}. The third column is what makes the refusal worth
	 * reading: without it the message would name the game and still not say
	 * what the editor needed.
	 */
	private static final String[][] EDITORS = {
		{"trainer", "Trainer editor", "trainer archives are located"},
		{"maison", "Battle facility opponents", "opponent pools"},
		{"shop", "Shop editor", "live in the executable"},
		{"lighting", "Area fog & lighting", "AreaData subfile 4"},
		{"encounters", "Wild encounters", "wild-encounter pack"},
	};

	/** Opens one of {@link #EDITORS} with no parent window, as the menu does. */
	static void openEditor(String which) {
		if ("trainer".equals(which)) {
			ctrmap.humaninterface.TrainerEditDialog.showForSelection(null, null);
		} else if ("maison".equals(which)) {
			ctrmap.humaninterface.MaisonEditDialog.show(null);
		} else if ("shop".equals(which)) {
			ctrmap.humaninterface.ShopEditDialog.show(null);
		} else if ("lighting".equals(which)) {
			ctrmap.humaninterface.AreaLightingDialog.show(null, new ctrmap.LoadedZone());
		} else if ("encounters".equals(which)) {
			ctrmap.humaninterface.EncounterEditDialog.show(null, new ctrmap.LoadedZone());
		} else {
			throw new IllegalArgumentException("no editor named " + which);
		}
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
