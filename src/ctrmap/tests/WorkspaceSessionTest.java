package ctrmap.tests;

import ctrmap.Ui;
import ctrmap.Workspace;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import ctrmap.WorkspaceSession;
import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.GameProfile;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The open game is an object, not a set of statics - and the proof of it.
 *
 * <p>{@link Workspace} was the god object: the open game's paths, {@code
 * GameType}, archive files and GARC handles lived in public static fields
 * that seventy production files read and a handful wrote, so a suite that
 * wanted to exercise one decision had to build a whole scratch workspace by
 * hand and hope nothing else in the JVM had left one behind. {@link
 * WorkspaceSession} is that state as an instance. Workspace keeps its old
 * statics as delegators to the CURRENT session while callers are migrated to
 * being handed one, which is why this suite exists: it is the difference
 * between an instance that is real and a facade over the same statics.
 *
 * <h2>What is held</h2>
 * <ol>
 * <li>A session opened against the pristine dump owns its state: it knows its
 * game and archives while Workspace's statics know nothing, and opening it
 * writes nothing - no scratch copy of the game, no directories.</li>
 * <li>Two sessions share nothing, and installing one into Workspace makes
 * the statics answer for THAT one, by identity.</li>
 * <li>A folder that is not a game opens nothing, and says which problem it
 * is - not the old game's archive names.</li>
 * <li>The fix that fell out of the structure: a workspace repointed at a
 * folder that fails validation is left CLEARLY invalid. It used to keep the
 * previous game's {@code GameType} and every one of its GARC handles live,
 * with {@code valid} false and the archive {@code File}s rebuilt from the new
 * folder using the old game's layout - half of each game at once.</li>
 * <li>The session keeps no global of its own and never reads the facade -
 * the seam that stops the tangle growing back inside the new class.</li>
 * <li>A ratchet on how many production files still reach Workspace's
 * statics. Fails when the number RISES; lowered by hand as callers are
 * migrated. The list it prints is the boundary the next migration moves.</li>
 * </ol>
 *
 * <p>ORDER: reads the dump, writes only under the JVM's temp folder. Resets
 * Workspace before and after, so it can run anywhere in the battery.
 *
 * <p>Wants the COMPLETE dump, not the partial GARC set: a session is opened
 * the way the application opens one, and the game is identified by its sound
 * archive (or the last GARC of its RomFS), neither of which the partial set
 * carries.
 *
 * Usage: java ctrmap.tests.WorkspaceSessionTest &lt;romfs title folder&gt; [src-root]
 */
public class WorkspaceSessionTest {

	/**
	 * Production files (outside ctrmap.tests) that name a static member of
	 * Workspace other than its nested types, as of 2026-09-07 - the day the
	 * state moved into WorkspaceSession, when every one of them still read
	 * the facade. (Eight more name only {@code ArchiveType} or
	 * {@code GameType}; a type is not state, so they are not counted.)
	 * LOWER THIS as callers are migrated to a session they are handed; never
	 * raise it without saying in the commit message which file went back to
	 * the global and why it had to.
	 */
	private static final int FILES_REACHING_WORKSPACE = 42; //GARC, WorkspaceIntegrity, ZoneManager, MapResizer, MapMatrix, BchTexturePack, MaisonPoolGuard, ItemTable, ItemText, PokeData, LocationNames, ADPropRegistry, GRProp, PropDatabase, NPCRegistry, MoveModelPool, NpcTemplates, ZoneHeader, BuildingCatalog handed what they read; AbstractGamefreakContainer's transitional constructors, the format layer's last reach, deleted

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS/000400000011C400");
		File src = new File(args.length > 1 ? args[1] : "src");

		theSessionKeepsNoGlobals(src);
		fewerFilesReachTheGlobal(src);
		aFolderThatIsNotAGameOpensNothing();
		if (!dump.isDirectory()) {
			System.out.println("  skip: no complete dump at " + dump + " - the opened-session checks need one");
		} else {
			Workspace.reset();
			try {
				theSessionOwnsItsState(dump);
				twoSessionsShareNothing(dump);
				aFailedSwitchLeavesNothingOfTheOldGame(dump);
				anEntryWhoseFileIsGoneDoesNotBlockTheOperationThatWroteIt(dump);
			} finally {
				Workspace.reset();
			}
		}

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	// ------------------------------------------------------- 1. the instance is real
	static void theSessionOwnsItsState(File dump) throws Exception {
		System.out.println("--- a session opened on the dump owns its state; Workspace's statics know nothing of it");
		File ws = Scratch.dir("ctrmap_session_ws");
		WorkspaceSession s = WorkspaceSession.open(ws, dump);

		check(s.game() == GameType.ORAS, "the game is detected from the folder (" + s.game() + ")");
		//the isOA()/isXY() gates that used to be checked here are gone: what a
		//caller needs is what the game can DO, and asking which game it is
		//answered "not XY" for three different games
		check(s.profile().supports(GameProfile.Feature.AREA_FORK),
				"and its profile answers what that game can do, which is what callers ask instead");
		check(s.profile() == GameProfile.of(GameType.ORAS), "and the profile is that game's");
		check(dump.equals(s.gameDir()) && ws.equals(s.workspaceDir()), "it remembers the two folders it was opened from");
		GARC ad = s.getArchive(ArchiveType.AREA_DATA);
		check(ad != null && ad.length > 0, "AreaData is open (" + (ad == null ? "null" : ad.length + " entries") + ")");
		check(s.getArchive(ArchiveType.MOVE_MODELS) != null, "and so is the last of the required archives");
		File expected = new File(dump.getPath() + Workspace.getArchivePath(ArchiveType.AREA_DATA, GameType.ORAS));
		check(expected.equals(s.archiveFile(ArchiveType.AREA_DATA)),
				"the archive file is the game folder plus the profile's path (" + s.archiveFile(ArchiveType.AREA_DATA) + ")");
		check(s.archiveFile(ArchiveType.SOUND_BCSAR) != null, "every archive the profile locates has a file, opened or not");
		check(new File(ws, "areadata").equals(s.getExtractionDirectory(ArchiveType.AREA_DATA)),
				"extraction directories resolve inside the workspace folder (" + s.getExtractionDirectory(ArchiveType.AREA_DATA) + ")");
		check(new File(ws, "temp").equals(s.temp()), "and so does the scratch directory");

		check(s.persistPaths().isEmpty(), "nothing is marked edited to begin with");
		File edited = new File(s.getExtractionDirectory(ArchiveType.AREA_DATA), "5");
		s.addPersist(edited);
		s.addPersist(edited);
		check(s.persistPaths().size() == 1 && s.persistPaths().contains(edited.getAbsolutePath()),
				"marking a file edited records it once (" + s.persistPaths() + ")");

		//the statics: untouched. This is the line between an instance and a facade.
		check(!Workspace.isValid(), "Workspace does not consider itself valid");
		check(Workspace.session() == null, "Workspace has no current session");
		check(Workspace.game() == null, "Workspace knows no game");
		check(Workspace.getArchive(ArchiveType.AREA_DATA) == null, "Workspace holds no archive");
		boolean refusedProfile = false;
		try {
			Workspace.profile();
		} catch (RuntimeException ex) {
			refusedProfile = true;
		}
		check(refusedProfile, "and asking Workspace for a profile with no game open REFUSES,"
				+ " rather than handing out ORAS's");
		check(Workspace.persistPaths().isEmpty(), "Workspace has nothing marked edited");

		String[] left = ws.list();
		check(left != null && left.length == 0,
				"opening a session wrote nothing into the workspace folder (it holds " + (left == null ? "?" : left.length) + " entries)");
	}

	// ------------------------------------------------------- 2. two sessions
	static void twoSessionsShareNothing(File dump) throws Exception {
		System.out.println("--- two sessions share nothing; installing one makes the statics answer for it, by identity");
		WorkspaceSession a = WorkspaceSession.open(Scratch.dir("ctrmap_session_a"), dump);
		WorkspaceSession b = WorkspaceSession.open(Scratch.dir("ctrmap_session_b"), dump);
		check(a.getArchive(ArchiveType.ZONE_DATA) != b.getArchive(ArchiveType.ZONE_DATA),
				"each session opens its own archive handles");
		a.addPersist(new File(a.getExtractionDirectory(ArchiveType.ZONE_DATA), "7"));
		check(b.persistPaths().isEmpty(), "and marking a file edited in one is invisible to the other");

		Workspace.install(a);
		check(Workspace.isValid() && Workspace.session() == a, "installing a session is what makes Workspace valid");
		check(Workspace.getArchive(ArchiveType.ZONE_DATA) == a.getArchive(ArchiveType.ZONE_DATA),
				"the static answers with the installed session's handle - the very object, not a copy");
		check(Workspace.persistPaths() == a.persistPaths(), "and its edited-file list");
		Workspace.install(b);
		check(Workspace.getArchive(ArchiveType.ZONE_DATA) == b.getArchive(ArchiveType.ZONE_DATA)
				&& Workspace.persistPaths().isEmpty(), "installing another switches every answer to it");
		Workspace.install(null);
		check(!Workspace.isValid() && Workspace.getArchive(ArchiveType.ZONE_DATA) == null && Workspace.game() == null,
				"installing none leaves nothing behind");
	}

	// ------------------------------------------------------- 3. refusals
	static void aFolderThatIsNotAGameOpensNothing() throws Exception {
		System.out.println("--- a folder that is not a game opens nothing, and says which problem it is");
		File ws = Scratch.dir("ctrmap_session_refusals");
		File empty = Scratch.dir("ctrmap_session_empty");

		List<String> problems = refusal(ws, empty);
		check(problems.contains("Could not detect game version"), "an empty folder: the version cannot be detected (" + problems + ")");
		check(!String.valueOf(problems).contains("GARC not found"),
				"and no archive is reported missing from a game that was never identified (" + problems + ")");

		//a folder that IS identified as ORAS, and has none of its archives: the
		//profile's own sound archive is what detection looks for
		File hollow = Scratch.dir("ctrmap_session_hollow");
		File sound = new File(hollow, GameProfile.of(GameType.ORAS).archivePath(ArchiveType.SOUND_BCSAR));
		sound.getParentFile().mkdirs();
		Files.write(sound.toPath(), new byte[0]);
		problems = refusal(ws, hollow);
		check(problems.contains("AreaData GARC not found") && problems.contains("MoveModels GARC not found"),
				"a folder identified as a game with no archives names every missing one (" + problems.size() + ": " + problems + ")");
		check(!problems.contains("Could not detect game version"), "and does not also claim it could not tell the game");

		problems = refusal(new File(ws, "not-there"), empty);
		check(problems.contains("Workspace path not found") && problems.contains("Could not detect game version"),
				"every problem is collected, not just the first (" + problems + ")");
		problems = refusal(ws, new File(empty, "not-there"));
		check(problems.contains("Game directory path not found"), "a missing game folder (" + problems + ")");
		problems = refusal(null, null);
		check(problems.contains("Workspace path not set") && problems.contains("Game directory path not set"),
				"folders never set (" + problems + ")");
	}

	static List<String> refusal(File ws, File game) {
		try {
			WorkspaceSession opened = WorkspaceSession.open(ws, game);
			check(false, "opening " + game + " was refused (it opened " + opened + ")");
			return new ArrayList<>();
		} catch (WorkspaceSession.OpenFailed ex) {
			return ex.problems();
		}
	}

	// ------------------------------------------------------- 4. the failed switch
	static void aFailedSwitchLeavesNothingOfTheOldGame(File dump) throws Exception {
		System.out.println("--- a workspace repointed at a folder that fails validation is left clearly invalid");
		File ws = Scratch.dir("ctrmap_session_switch");
		Workspace.WORKSPACE_PATH = ws.getAbsolutePath();
		Workspace.GAMEDIR_PATH = dump.getAbsolutePath();
		Workspace.install(WorkspaceSession.open(ws, dump));
		check(Workspace.isValid() && Workspace.game() == GameType.ORAS
				&& Workspace.getArchive(ArchiveType.AREA_DATA) != null,
				"the user has a game open");

		File notAGame = Scratch.dir("ctrmap_session_not_a_game");
		Workspace.GAMEDIR_PATH = notAGame.getAbsolutePath();
		List<String> said = Ui.record();
		try {
			Workspace.validate(null, true);
		} finally {
			Ui.stopRecording();
		}
		check(!Workspace.isValid(), "the switch failed and the workspace says so");
		check(Workspace.session() == null, "there is no current session");
		check(Workspace.game() == null, "nothing of the old game's identity survives the failed switch (game is " + Workspace.game() + ")");
		check(Workspace.getArchive(ArchiveType.AREA_DATA) == null && Workspace.getArchive(ArchiveType.ZONE_DATA) == null,
				"and none of its archive handles are reachable");
		check(said.size() == 1 && said.get(0).contains("Could not detect game version"),
				"the user is told what is wrong with the folder they chose: " + said);
		check(said.size() == 1 && !said.get(0).contains("GARC not found"),
				"and not that the OLD game's archives are missing from it, which is what the old code went on to check");
		check(notAGame.getAbsolutePath().equals(Workspace.GAMEDIR_PATH),
				"the setting still names the folder the user chose, so they can correct it");

		//and the same transaction, succeeding: the facade answers for what it opened
		Workspace.GAMEDIR_PATH = dump.getAbsolutePath();
		said = Ui.record();
		try {
			Workspace.validate(null, true);
		} finally {
			Ui.stopRecording();
		}
		//SAYS WHAT IT WAS TOLD WHEN IT FAILS. This asserted a boolean and printed
		//nothing else, so a regression here said only that the workspace did not
		//open - with the reason sitting unread in the recording two lines above.
		check(Workspace.isValid() && Workspace.session() != null && Workspace.session().gameDir().equals(dump),
				"pointing it back at the game opens it again"
				+ (!Workspace.isValid() ? " - it did not open, and said: " + said
					: Workspace.session() == null ? " - it opened but left no session"
					: " - it opened " + Workspace.session().gameDir() + ", not " + dump
					+ " (a RELATIVE path argument does not equal the absolute one the session"
					+ " keeps - run this the way test.ps1 does)"));
		check(Workspace.getArchive(ArchiveType.AREA_DATA) == Workspace.session().getArchive(ArchiveType.AREA_DATA),
				"through the session that was opened");
		check(new File(ws, "areadata").isDirectory() && new File(ws, "temp").isDirectory(),
				"and a validated workspace has its extraction directories");
		check(said.isEmpty(), "with nothing to complain about; said " + said);
		//the two tables derived from the game are loaded by validate, handed the
		//session it opened: neither LocationNames nor PokeData reads the global
		//any more, so a validate that forgot would leave the dropdowns refusing
		String name0;
		try {
			name0 = ctrmap.formats.text.LocationNames.getLocName(0);
		} catch (IllegalStateException ex) {
			name0 = null;
		}
		check(name0 != null, "validate loaded the location names from the session it opened"
				+ (name0 == null ? " (a name is refused)" : " (line 0 answers, " + name0.length() + " chars)"));
		check(ctrmap.formats.pokedata.PokeData.available(),
				"and the Pokemon reference tables, from the game folder it opened");
	}

	// ------------------------------------------------------- 5. the seam inside the new class
	static void theSessionKeepsNoGlobals(File src) throws Exception {
		System.out.println("--- the session keeps no global of its own and never reads the facade");
		List<String> mutableStatics = new ArrayList<>();
		for (Field f : WorkspaceSession.class.getDeclaredFields()) {
			int m = f.getModifiers();
			if (Modifier.isStatic(m) && !Modifier.isFinal(m) && !f.isSynthetic()) {
				mutableStatics.add(f.getName());
			}
		}
		check(mutableStatics.isEmpty(), "WorkspaceSession has no static mutable field" + (mutableStatics.isEmpty() ? "" : ": " + mutableStatics));

		File source = new File(src, "ctrmap/WorkspaceSession.java");
		if (!source.isFile()) {
			check(false, "there is a WorkspaceSession source to read at " + source);
			return;
		}
		String code = stripComments(new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8));
		List<String> reads = new ArrayList<>();
		Matcher m = Pattern.compile("\\bWorkspace\\.([a-z_]\\w*)").matcher(code);
		while (m.find()) {
			reads.add(m.group(1));
		}
		check(reads.isEmpty(), "WorkspaceSession never names a static of Workspace (only its nested types)"
				+ (reads.isEmpty() ? "" : " - it reads " + reads));
		List<String> ui = new ArrayList<>();
		for (String forbidden : new String[]{"CtrmapMainframe", "javax.swing", "java.awt", "LoadingDialog", "Ui.", "Preferences"}) {
			if (code.contains(forbidden)) {
				ui.add(forbidden);
			}
		}
		check(ui.isEmpty(), "and is headless - no window, dialog, main frame or preferences" + (ui.isEmpty() ? "" : ": " + ui));
	}

	// ------------------------------------------------------- 6. the ratchet
	static void fewerFilesReachTheGlobal(File src) throws Exception {
		System.out.println("--- how many production files still reach Workspace's statics");
		List<File> files = new ArrayList<>();
		javaFiles(src, files);
		Pattern member = Pattern.compile("\\bWorkspace\\.(?!ArchiveType\\b|GameType\\b|PackProgress\\b)(\\w+)");
		List<String> reaching = new ArrayList<>();
		int edges = 0;
		for (File f : files) {
			String rel = src.toURI().relativize(f.toURI()).getPath();
			if (rel.startsWith("ctrmap/tests/") || rel.equals("ctrmap/Workspace.java")) {
				continue;
			}
			String code = stripComments(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
			Matcher m = member.matcher(code);
			List<String> touched = new ArrayList<>();
			while (m.find()) {
				if (!touched.contains(m.group(1))) {
					touched.add(m.group(1));
				}
			}
			if (!touched.isEmpty()) {
				reaching.add(rel + " " + touched);
				edges += touched.size();
			}
		}
		Collections.sort(reaching);
		if (files.isEmpty()) {
			check(false, "there are sources to measure at " + src);
			return;
		}
		System.out.println("  " + reaching.size() + " production files reach Workspace's statics, "
				+ edges + " file-member edges; the boundary:");
		for (String r : reaching) {
			System.out.println("    " + r);
		}
		check(reaching.size() <= FILES_REACHING_WORKSPACE,
				"no more production files reach Workspace's statics than the " + FILES_REACHING_WORKSPACE
				+ " recorded (it is " + reaching.size() + ")");
		check(reaching.size() == FILES_REACHING_WORKSPACE,
				"and the recorded number is the measured one - lower it when a caller is migrated (measured "
				+ reaching.size() + ", recorded " + FILES_REACHING_WORKSPACE + ")");
	}

	// ------------------------------------------------------- helpers
	static String stripComments(String s) {
		return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
	}

	/**
	 * A persisted entry whose file is gone blocks nothing, and does not survive
	 * the next open.
	 *
	 * <p>WHAT THIS COST A USER, which is why it is pinned on real bytes rather
	 * than argued. The persisted list is the durable record of "extracted files
	 * the user edited", and five operations use membership of it as their test
	 * for "is one of me already pending?" - appending zones, forking an area,
	 * forking geometry, resizing a map, and editing encounters. Nothing ever
	 * pruned it. {@code cleanAll()} empties it wholesale and Remove-added-zones
	 * drops entries by walking the DIRECTORY, so it cannot reach an entry whose
	 * file has already gone.
	 *
	 * <p>So one orphan - left by a pack that threw part way, a revert, a
	 * hand-deleted file - refused that operation for the rest of the workspace's
	 * life. Worse, the refusal it printed said "Pack the workspace before adding
	 * more", and packing has never touched this list, in any version: the advice
	 * named the one action that could not possibly help. A real workspace was
	 * found holding NINE orphaned entries of fourteen, five of them zonedata
	 * slots, with zone appending dead in it and a message telling the owner to do
	 * the thing they had just done.
	 *
	 * <p>TWO THINGS ARE ASSERTED, because one without the other leaves the hole
	 * open: the list drops orphans as it is read, so a workspace heals itself on
	 * open; and {@code isPendingArtifact} answers on the file rather than on the
	 * listing, for a file that disappears while the session is live.
	 */
	static void anEntryWhoseFileIsGoneDoesNotBlockTheOperationThatWroteIt(File dump) throws Exception {
		System.out.println("--- a persisted entry whose file is gone blocks nothing");
		File ws = Scratch.dir("ctrmap_session_orphan");
		WorkspaceSession s = WorkspaceSession.open(ws, dump);
		
		//one real edited file, and one entry whose file was never written - which is
		//exactly the shape a half-finished zone append leaves behind
		File zoneDir = s.getExtractionDirectory(ArchiveType.ZONE_DATA);
		zoneDir.mkdirs();
		File real = new File(zoneDir, "536");
		java.nio.file.Files.write(real.toPath(), new byte[]{1, 2, 3, 4});
		File orphan = new File(zoneDir, "537");
		check(!orphan.exists(), "the orphan names a file that is not there");
		
		java.nio.file.Files.write(new File(ws, "ctrmap_persist.txt").toPath(),
			(java.io.File.separator + "zonedata" + java.io.File.separator + "536" + "\n"
			+ java.io.File.separator + "zonedata" + java.io.File.separator + "537" + "\n")
			.getBytes("UTF-8"));
		
		WorkspaceSession reopened = WorkspaceSession.open(ws, dump);
		check(reopened.persistPaths().contains(real.getAbsolutePath()),
			"reopening keeps the entry whose file is really there");
		check(!reopened.persistPaths().contains(orphan.getAbsolutePath()),
			"and drops the one whose file is gone, so the workspace heals on open: "
			+ reopened.persistPaths());
		
		//and the question the five operations actually ask
		check(reopened.isPendingArtifact(real),
			"a file that is listed AND on disk is a pending artifact");
		check(!reopened.isPendingArtifact(orphan),
			"one that is listed and NOT on disk is not - which is what stopped a zone"
			+ " append from ever running again");
		
		//the live case: marked this session, then deleted underneath us
		File vanishes = new File(zoneDir, "538");
		java.nio.file.Files.write(vanishes.toPath(), new byte[]{9});
		reopened.addPersist(vanishes);
		check(reopened.isPendingArtifact(vanishes), "a file marked and present is pending");
		check(vanishes.delete(), "the file is removed underneath the session");
		check(!reopened.isPendingArtifact(vanishes),
			"and it stops being pending the moment it is gone, without waiting for a reopen");
	}

	static void javaFiles(File dir, List<File> out) {
		File[] kids = dir.listFiles();
		if (kids == null) {
			return;
		}
		for (File f : kids) {
			if (f.isDirectory()) {
				javaFiles(f, out);
			} else if (f.getName().endsWith(".java")) {
				out.add(f);
			}
		}
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
