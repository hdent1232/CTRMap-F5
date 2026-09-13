package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.formats.GameFiles;
import ctrmap.formats.containers.GR;
import ctrmap.formats.containers.MM;
import ctrmap.formats.mapmatrix.MapMatrix;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The format layer works when it is HANDED its game, with no workspace open -
 * and the count of format classes that still reach for the global only falls.
 *
 * <p>WHY THIS SUITE EXISTS. Sixteen classes under {@code ctrmap.formats}
 * fetched the open game from {@link Workspace}'s statics themselves, so not one
 * of them could be exercised without a live workspace installed in the global,
 * and none could be pointed at a different game. {@link GameFiles} is the
 * format layer's own statement of what it needs; a class that takes one as a
 * parameter can be handed the open {@link WorkspaceSession} by the application
 * and a {@link FakeGameFiles} by a suite. This is the proof for the first two
 * classes moved - {@code AbstractGamefreakContainer} and {@link MapMatrix} -
 * and the ratchet the rest are moved under.
 *
 * <p>The property asserted is the owner's: a migrated class is one that is
 * handed what it needs and could be handed something else. So the checks
 * below run with {@code Workspace.reset()} first and nothing installed after,
 * hand a matrix two different fake games, and read back which one each write
 * reached. A class that quietly kept reading the global would report its
 * writes nowhere (the global has no session) and fail here.
 *
 * <p>Proven by breaking: with {@code storeFile} put back to
 * {@code Workspace.addPersist}, the fake heard about no write and the bytecode
 * count rose, and both checks named it.
 *
 * <p>ORDER: needs no dump, opens no workspace, writes only under {@link Scratch}
 * and reads {@code build/classes} for the ratchet. Resets Workspace before and
 * after, so it can run anywhere in the battery.
 *
 * Usage: java ctrmap.tests.GameFilesSeamTest [src-root] [classes-root]
 *        (defaults "src" and "build/classes")
 */
public class GameFilesSeamTest {

	/**
	 * Distinct (format class, Workspace member) edges in the compiled program,
	 * measured 2026-09-08 by {@link ClassFileScanner} - 42 across 16 classes
	 * before this seam existed, 39 across 15 once MapMatrix was handed its game
	 * and the container base kept only its transitional constructors' one,
	 * 31 across 13 once BchTexturePack and MaisonPoolGuard were handed theirs
	 * ({@link HandedGameTest} is their proof), 15 across 9 once ItemTable,
	 * ItemText, PokeData and LocationNames were handed theirs (the same suite
	 * is their proof; {@link GameFiles#durable} was added for the item baseline),
	 * 7 across 3 once ADPropRegistry, GRProp, PropDatabase, NPCRegistry,
	 * MoveModelPool and NpcTemplates were handed theirs (the same suite again;
	 * their nine edges went, and ZoneHeader, not yet moved, gained the one
	 * session() it hands the registry it builds), 1 across 1 once ZoneHeader
	 * and BuildingCatalog (through TerrainCatalog) were handed theirs (the same
	 * suite, sections 18 and 19), and 0 across 0 once the container base's
	 * transitional constructors were deleted with their last callers migrated:
	 * the format layer reaches the global nowhere. Both stay at zero; never
	 * raise either without saying in the commit message which class went back
	 * to the global and why it had to.
	 */
	private static final int FORMATS_EDGES = 0;
	private static final int FORMATS_CLASSES = 0;

	private static final String WORKSPACE = "ctrmap/Workspace";
	private static final String CONTAINER = "ctrmap/formats/containers/AbstractGamefreakContainer";
	private static final String MATRIX = "ctrmap/formats/mapmatrix/MapMatrix";

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File classes = new File(args.length > 1 ? args[1] : "build/classes");

		Workspace.reset();
		try {
			aMatrixAndItsRegionsWorkWithNoWorkspaceOpen();
			aContainerRefusesToBeHandedNothing();
			theSessionHonoursTheContract();
			theFacadeDoesNotSatisfyIt();
		} finally {
			Workspace.reset();
		}
		theFormatLayerReachesTheGlobalLess(classes);
		File src = new File(args.length > 0 ? args[0] : "src");
		aWriteAnswersNothing(src);
		anAnswerThatCanOnlyBeYesIsNoAnswer(src);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	// ------------------------------------------------------ 0. a write answers nothing
	/**
	 * {@code storeFile} is declared void, and no production source reads an answer from it.
	 *
	 * <p>It used to return a boolean. When a failed write was made to THROW instead, the
	 * boolean was kept - the commit said "so the callers that DO check still compile and
	 * still read well" - and that is exactly what broke: twelve {@code if
	 * (!storeFile(...))} refusals could no longer fail, and two of them held the only
	 * message that named what the user was editing. The Apply that could not write a
	 * region stopped saying "could not write region 153" and started quoting a temp path
	 * and a subfile number. {@code PaintApplyGuardsTest} caught both.
	 *
	 * <p>WHY THIS CHECK EXISTS WHEN JAVAC ALREADY REFUSES IT. Void makes every
	 * {@code if (!storeFile(...))} a compile error, which is the real guard and needs no
	 * suite. What javac cannot refuse is somebody handing the method its boolean back -
	 * a one-word edit that compiles, reads like a courtesy to old callers, and silently
	 * reopens the twelve. This says no to the declaration.
	 */
	static void aWriteAnswersNothing(File src) throws Exception {
		System.out.println("--- a container write answers nothing, so a dead refusal cannot compile");
		File base = new File(src, "ctrmap/formats/containers/AbstractGamefreakContainer.java");
		check(base.isFile(), "the container base is where it was: " + base.getPath());
		String text = new String(java.nio.file.Files.readAllBytes(base.toPath()),
			java.nio.charset.StandardCharsets.UTF_8);
		java.util.regex.Matcher m = java.util.regex.Pattern
			.compile("public\\s+(\\w+)\\s+storeFile\\s*\\(").matcher(text);
		java.util.List<String> answering = new java.util.ArrayList<>();
		int found = 0;
		while (m.find()) {
			found++;
			if (!"void".equals(m.group(1))) {
				answering.add(m.group(1));
			}
		}
		check(found >= 2, found + " storeFile overloads declared (the bytes one and the file one)");
		check(answering.isEmpty(), "every storeFile is declared void" + (answering.isEmpty() ? ""
			: ", but " + answering + " answers instead - a write that failed THROWS, so an answer"
			+ " can only ever be the success one, and twelve callers once wrote refusals against"
			+ " it that could not fire"));
		
		//and nothing in production reads one. The compiler enforces this while the
		//declaration stays void; the point of checking the text too is that the pair is
		//what holds - a boolean put back with no caller is harmless until the next caller.
		java.util.List<String> readers = new java.util.ArrayList<>();
		java.util.regex.Pattern reads = java.util.regex.Pattern
			.compile("(if\\s*\\(\\s*!|&=|\\|=|=\\s*)[\\w.()\\[\\]]*\\.storeFile\\(");
		for (File j : DialogSeamTest.javaSources(new File(src, "ctrmap"))) {
			if (j.getParentFile() != null && j.getParentFile().getName().equals("tests")) {
				continue;
			}
			String body = new String(java.nio.file.Files.readAllBytes(j.toPath()),
				java.nio.charset.StandardCharsets.UTF_8);
			if (reads.matcher(body).find()) {
				readers.add(j.getName());
			}
		}
		check(readers.isEmpty(), "no production source reads an answer from a container write"
			+ (readers.isEmpty() ? "" : ", but these do: " + readers));
	}

	// ------------------------------------------------------ 0b. an answer that can only be yes
	/**
	 * No production method answers boolean when the only answer it can give is true.
	 *
	 * <p>THE GENERALISATION OF THE ONE ABOVE, and the reason that one is not enough:
	 * {@code storeFile} was not special. The shape is - a method keeps a boolean result
	 * after its failure path becomes a throw, so the answer is structurally always yes,
	 * and every caller that wrote {@code if (!it())} has a refusal that can never fire.
	 * Those refusals read exactly like working ones, which is why twelve of them survived
	 * review, and two held the only message naming the region or area the user was
	 * editing. A suite that only knew about storeFile would have watched the next one
	 * happen somewhere else.
	 *
	 * <p>MEASURED AT ZERO on 2026-09-13, across every production source, which is the
	 * cheapest moment there will ever be to refuse the next one. The way out is not an
	 * exception list: it is to return void, which is what the answer already meant.
	 *
	 * <p>WHAT IT LOOKS AT. A method declared to answer boolean, whose body holds at
	 * least one {@code throw} and whose every {@code return} is the literal
	 * {@code true}. A predicate that returns an expression is not this; nor is one with
	 * no failure path at all, which has nothing to have hidden.
	 */
	static void anAnswerThatCanOnlyBeYesIsNoAnswer(File src) throws Exception {
		System.out.println("--- no production method answers boolean when the answer can only be yes");
		java.util.regex.Pattern sig = java.util.regex.Pattern.compile(
			"(?:public|protected|private|static|final|synchronized|\\s)+boolean\\s+(\\w+)\\s*\\([^;{]*\\)\\s*(?:throws[^{;]+)?\\{");
		java.util.regex.Pattern ret = java.util.regex.Pattern.compile("return\\s+([^;]+);");
		java.util.List<String> onlyYes = new java.util.ArrayList<>();
		int looked = 0;
		for (File j : DialogSeamTest.javaSources(new File(src, "ctrmap"))) {
			if (j.getParentFile() != null && j.getParentFile().getName().equals("tests")) {
				continue;
			}
			String body = new String(java.nio.file.Files.readAllBytes(j.toPath()),
				java.nio.charset.StandardCharsets.UTF_8).replace("\\r", "");
			java.util.regex.Matcher m = sig.matcher(body);
			while (m.find()) {
				looked++;
				String inner = methodBody(body, m.end() - 1);
				if (inner == null || inner.indexOf("throw ") < 0) {
					continue;
				}
				java.util.Set<String> answers = new java.util.HashSet<>();
				java.util.regex.Matcher r = ret.matcher(inner);
				while (r.find()) {
					answers.add(r.group(1).trim());
				}
				if (answers.size() == 1 && answers.contains("true")) {
					onlyYes.add(j.getName() + ":" + (1 + countLines(body, m.start())) + " " + m.group(1));
				}
			}
		}
		check(looked > 100, looked + " boolean method(s) read, so the scan found the program");
		check(onlyYes.isEmpty(), "no method answers boolean when its only answer is true"
			+ (onlyYes.isEmpty() ? " (" + looked + " read)" : ", but these do: " + onlyYes
			+ " - a caller that writes a refusal against one of these has written a refusal"
			+ " that can never fire, which is how twelve of them got into this program. Return"
			+ " void: it is what the answer already meant"));
	}

	/** The text between the brace at {@code open} and its match, or null if unbalanced. */
	static String methodBody(String text, int open) {
		int depth = 0;
		for (int i = open; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return text.substring(open, i);
				}
			}
		}
		return null;
	}

	/** Newlines before {@code at}. */
	static int countLines(String text, int at) {
		int n = 0;
		for (int i = 0; i < at && i < text.length(); i++) {
			if (text.charAt(i) == '\n') {
				n++;
			}
		}
		return n;
	}

	// ------------------------------------------------------ 1. handed a game, no workspace
	static void aMatrixAndItsRegionsWorkWithNoWorkspaceOpen() throws Exception {
		System.out.println("--- a map matrix and its region containers, handed a fake game, with no workspace open");
		check(!Workspace.isValid() && Workspace.session() == null, "no workspace is open");

		FakeGameFiles fake = new FakeGameFiles();
		//two region containers staged as FIELD_DATA entries 3 and 5, each with a word in subfile 0
		GR three = new GR(fake.staged(ArchiveType.FIELD_DATA, 3), 7, fake);
		three.storeFile(0, bytes("three"));
		GR five = new GR(fake.staged(ArchiveType.FIELD_DATA, 5), 7, fake);
		five.storeFile(0, bytes("five"));
		check(fake.edited().size() == 2 && fake.edited().contains(three.getOriginFile().getAbsoluteFile())
				&& fake.edited().contains(five.getOriginFile().getAbsoluteFile()),
				"the region writes were reported to the fake, once each (" + fake.edited().size() + ")");

		//a 2x1 matrix pointing at them, written through a container handed the same fake
		File mmFile = new File(fake.scratch(), "matrix.mm");
		MM mm = new MM(mmFile, 2, MM.MM_MAP_MATRIX, fake);
		MapMatrix blank = new MapMatrix(mm, 2, 1, 0);
		blank.ids.set(0, 0, (short) 3);
		blank.ids.set(1, 0, (short) 5);
		blank.write();
		check(fake.edited().contains(mmFile.getAbsoluteFile()), "writing the matrix reported its file to the fake");
		check(Workspace.persistPaths().isEmpty(), "and nothing reached the global's edited-file list");

		//read back: each populated cell's region is opened from what the matrix was handed
		MapMatrix read = new MapMatrix(new MM(mmFile, fake), fake);
		check(read.width == 2 && read.height == 1 && read.ids.get(0, 0) == 3 && read.ids.get(1, 0) == 5,
				"the matrix reads back its grid (" + read.width + "x" + read.height + ")");
		check(read.regions.get(0, 0) != null && startsWith(read.regions.get(0, 0).getFile(0), "three")
				&& read.regions.get(1, 0) != null && startsWith(read.regions.get(1, 0).getFile(0), "five"),
				"and opened each populated cell's region from the fake");

		//a DIFFERENT game: the same two containers, entry 3 saying "drei" instead,
		//copied in as staged bytes so nothing has been reported to it yet
		FakeGameFiles donor = new FakeGameFiles();
		GR drei = new GR(donor.staged(ArchiveType.FIELD_DATA, 3), 7, donor);
		drei.storeFile(0, bytes("drei"));
		FakeGameFiles other = new FakeGameFiles()
				.plant(ArchiveType.FIELD_DATA, 3, Files.readAllBytes(drei.getOriginFile().toPath()))
				.plant(ArchiveType.FIELD_DATA, 5, Files.readAllBytes(five.getOriginFile().toPath()));
		check(other.edited().isEmpty(), "the other game has heard of no write yet");
		MapMatrix elsewhere = new MapMatrix(new MM(mmFile, other), other);
		check(elsewhere.regions.get(0, 0) != null && startsWith(elsewhere.regions.get(0, 0).getFile(0), "drei"),
				"handed another game, the same matrix file opens that game's region");

		//a write through a region the matrix opened goes to the game the matrix was handed
		int fakeHeard = fake.edited().size();
		GR viaOther = elsewhere.regions.get(1, 0);
		if (viaOther == null) {
			//refused in words rather than dereferenced: the checks after this one,
			//the bytecode ratchet above all, must still get to speak
			check(false, "a region written through the matrix reports to the game the matrix was handed"
					+ " (no region was opened to write through)");
		} else {
			viaOther.storeFile(0, bytes("fuenf"));
			check(other.edited().size() == 1
					&& other.edited().get(0).equals(other.staged(ArchiveType.FIELD_DATA, 5).getAbsoluteFile()),
					"a region written through the matrix reports to the game the matrix was handed (" + other.edited() + ")");
			check(fake.edited().size() == fakeHeard, "and not to the other one");
		}

		//handed nothing, the grid alone
		MapMatrix grid = new MapMatrix(new MM(mmFile, fake), null);
		check(grid.ids.get(1, 0) == 5 && grid.regions.get(0, 0) == null && grid.regions.get(1, 0) == null,
				"handed null it reads the grid and opens no region");

		//a subfile extracted to disk lands in the handed scratch directory
		File io = read.file.getIOFile(0);
		check(io != null && io.isFile() && fake.scratch().equals(io.getParentFile()),
				"a subfile extracted to disk lands in the handed scratch directory (" + io + ")");

		check(Workspace.session() == null && Workspace.persistPaths().isEmpty(),
				"and after all of it no workspace is open and nothing reached the global");
	}

	// ------------------------------------------------------ 2. null is refused
	static void aContainerRefusesToBeHandedNothing() throws Exception {
		System.out.println("--- a container handed null refuses, rather than writing edits nobody will pack");
		File f = Scratch.file("ctrmap_gamefiles_null");
		try {
			new GR(f, 7, (GameFiles) null);
			check(false, "a container handed null GameFiles refuses (it accepted)");
		} catch (IllegalArgumentException ex) {
			check(ex.getMessage() != null && ex.getMessage().contains("unrecorded"),
					"a container handed null refuses and says why: " + ex.getMessage());
		}
	}

	// ------------------------------------------------------ 3. the session is the real one
	static void theSessionHonoursTheContract() throws Exception {
		System.out.println("--- the session satisfies the contract by delegation, built from parts with no dump");
		File ws = Scratch.dir("ctrmap_gamefiles_ws");
		File game = Scratch.dir("ctrmap_gamefiles_game");
		WorkspaceSession s = new WorkspaceSession(ws, game, GameType.ORAS, null);
		GameFiles handed = s;
		check(s.temp().equals(handed.scratch()), "scratch() is the session's temp directory");
		check(handed.profile() == s.profile(), "profile() is the session's profile");
		check(handed.archive(ArchiveType.AREA_DATA) == null, "archive() is null for an archive nothing opened");
		check(handed.staged(ArchiveType.AREA_DATA, 0) == null,
				"staged() is null, not a NullPointerException, for an archive that is not open");
		check(handed.staged(ArchiveType.PERSONAL, 0) == null, "and for an archive nothing extracts");
		File item = new File(game.getPath() + s.profile().archivePath(ArchiveType.ITEM_DATA));
		check(item.equals(handed.archiveFile(ArchiveType.ITEM_DATA)),
				"archiveFile() is the game folder plus the profile's path (" + handed.archiveFile(ArchiveType.ITEM_DATA) + ")");
		check(handed.pristine() == null, "pristine() is null before a snapshot exists");
		s.originalSnapshotDir().mkdirs();
		check(s.originalSnapshotDir().equals(handed.pristine()), "and the snapshot directory once it does");
		File e = new File(ws, "areadata/5");
		handed.edited(e);
		handed.edited(e);
		check(s.isPersisted(e) && s.persistPaths().size() == 1, "edited() marks the file for the next pack, once");
		check(handed.variant() == GameProfile.Variant.RETAIL, "variant() probes the game folder: an empty one is retail");
		check(new File(ws, "original_items").equals(handed.durable("original_items")),
				"durable() is a directory under the workspace folder, by the name asked for ("
				+ handed.durable("original_items") + ")");
		check(!handed.durable("original_items").exists(), "not created by asking");
		for (String cleaned : new String[]{"temp", "areadata", s.originalSnapshotDir().getName()}) {
			try {
				handed.durable(cleaned);
				check(false, "durable() refuses \"" + cleaned + "\", which cleaning empties or the snapshot owns (it handed it out)");
			} catch (IllegalArgumentException ex) {
				check(true, "durable() refuses \"" + cleaned + "\", which cleaning empties or the snapshot owns: " + ex.getMessage());
			}
		}
		check(Workspace.session() == null, "none of which needed a workspace open");
	}

	// ------------------------------------------------------ 4. the facade stays out
	static void theFacadeDoesNotSatisfyIt() {
		System.out.println("--- the static facade does not satisfy the contract; only the session does");
		check(!GameFiles.class.isAssignableFrom(Workspace.class),
				"Workspace does not implement GameFiles - a facade satisfying it would let a caller keep the"
				+ " global and still look migrated");
		check(GameFiles.class.isAssignableFrom(WorkspaceSession.class), "WorkspaceSession does");
	}

	// ------------------------------------------------------ 5. the ratchet
	static void theFormatLayerReachesTheGlobalLess(File classes) throws Exception {
		System.out.println("--- how many format classes still reach Workspace, measured in the bytecode");
		if (!new File(classes, WORKSPACE + ".class").isFile()) {
			//never "0 edges found in nothing": a guard that measures an empty
			//tree and passes is the failure this battery exists to refuse
			check(false, "there is a compiled program to measure - no " + WORKSPACE + ".class under "
					+ classes.getAbsolutePath() + " (run build.ps1, or pass the classes root as args[1])");
			return;
		}
		List<ClassFileScanner.ClassFile> app = ClassFileScanner.application(classes);
		Map<String, Set<String>> byClass = new TreeMap<>();
		int edges = 0;
		for (ClassFileScanner.Member m : ClassFileScanner.edgesTo(app, WORKSPACE)) {
			if (!m.owner.startsWith("ctrmap/formats/")) {
				continue;
			}
			Set<String> members = byClass.get(m.owner);
			if (members == null) {
				members = new TreeSet<>();
				byClass.put(m.owner, members);
			}
			members.add(m.name);
			edges++;
		}
		System.out.println("  " + byClass.size() + " format classes reach Workspace, " + edges
				+ " class-member edges; the boundary:");
		for (Map.Entry<String, Set<String>> e : byClass.entrySet()) {
			System.out.println("    " + e.getKey() + " " + e.getValue());
		}
		check(edges <= FORMATS_EDGES && byClass.size() <= FORMATS_CLASSES,
				"no more format classes reach Workspace than the " + FORMATS_CLASSES + " recorded, over no more than "
				+ FORMATS_EDGES + " edges (it is " + byClass.size() + " over " + edges + ")");
		check(edges == FORMATS_EDGES && byClass.size() == FORMATS_CLASSES,
				"and the recorded numbers are the measured ones (measured "
				+ byClass.size() + " classes, " + edges + " edges; recorded " + FORMATS_CLASSES + ", " + FORMATS_EDGES + ")");
		check(!byClass.containsKey(MATRIX), "MapMatrix, the worked example, has no edge to the global at all"
				+ (byClass.containsKey(MATRIX) ? " - it reaches " + byClass.get(MATRIX) : ""));
		check(!byClass.containsKey(CONTAINER), "AbstractGamefreakContainer, whose transitional constructors were"
				+ " the last edge, has none" + (byClass.containsKey(CONTAINER) ? " - it reaches " + byClass.get(CONTAINER) : ""));
		check(byClass.isEmpty(), "the format layer reaches the global nowhere"
				+ (byClass.isEmpty() ? "" : " - " + byClass.keySet() + " still do"));
	}

	// ------------------------------------------------------ helpers
	static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}

	static boolean startsWith(byte[] data, String word) {
		byte[] w = bytes(word);
		if (data == null || data.length < w.length) {
			return false;
		}
		for (int i = 0; i < w.length; i++) {
			if (data[i] != w[i]) {
				return false;
			}
		}
		return true;
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
