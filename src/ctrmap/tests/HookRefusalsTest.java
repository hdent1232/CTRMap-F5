package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Three standing rules that had nothing behind them, and now refuse at the point of action.
 *
 * <p>WHAT THIS SUITE IS FOR. {@code CLAUDE.md} carries twenty-eight rules in capitals. On
 * 2026-09-20 sixteen of them had no mechanism at all - they were rules in the sense that a
 * sign is a rule. Three of the sixteen had already cost something measurable here:
 *
 * <ul>
 * <li>GAME DATA IS READ-ONLY. On 2026-09-19 a test fixture written for this project drove a
 *     pack against a {@code WorkspaceSession} rooted on the LIVE DUMP. It did no damage; that
 *     was luck. The dump is not in version control and cannot be regenerated without a 3DS.</li>
 * <li>NEVER PUSH WITHOUT EXPLICIT PER-PUSH APPROVAL. Approval for one push is not approval for
 *     the next, and the memory index records what a missing quote cost once already: a
 *     conditional "publish immediately" read as standing permission.</li>
 * <li>NEVER EDIT SOURCE WHILE THE SUITE IS RUNNING. Two full battery runs here were invalidated
 *     by edits landing mid-run. {@code build.ps1} has refused to REBUILD during a battery since
 *     the second one - nothing refused the EDIT, which is the half that makes the result
 *     meaningless rather than merely noisy.</li>
 * </ul>
 *
 * <p>IT DRIVES THE HOOKS THE HARNESS RUNS, in {@code .claude/hooks}, which git tracks. A second
 * "version-controlled" copy in {@code tools/hooks} was kept for when that folder was not tracked;
 * the two drifted - one fix in each - and this suite drove the copy that did not run (F187).
 * {@link HooksWiredTest} refuses a hook git does not track.
 *
 * <p>EVERY SECTION ASSERTS BOTH DIRECTIONS. A guard that refuses everything passes a
 * refusal-only suite perfectly, and is worse than no guard: within an hour of being wired here
 * one refused {@code mutate2.py --selftest}, which the battery itself runs.
 *
 * <p>ORDER: needs no game, no dump and no display. Needs python on PATH and says so and skips
 * rather than passing.
 *
 * Usage: java ctrmap.tests.HookRefusalsTest [repo-root]
 */
public class HookRefusalsTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File repo = new File(args.length > 0 ? args[0] : ".");
		File hooks = new File(repo, ".claude/hooks");
		if (!hooks.isDirectory()) {
			System.out.println("  FAIL: no " + hooks.getPath()
					+ " - the guards are not under version control, so nothing here is pinned");
			System.exit(1);
		}
		if (!CommitGuardTest.onPath("python")) {
			Skip.suite("python is not on PATH - these guards are python hooks, "
					+ "so this suite cannot run them here");
			return;
		}

		//FIVE GUARDS LIVED TWICE HERE (F167): a copy of this project's in .claude/hooks beside the
		//bundle's in .claude/bundle-hooks, both asked on every call, and measured on 29 shared cases
		//the copies disagreed on 7. The project's copies are gone; these sections drive the one copy
		//that runs, as this project installed it - its ADAPT settings and its script list included.
		File bundleHooks = new File(repo, ".claude/bundle-hooks");
		check(bundleHooks.isDirectory(), "the bundle's hooks are installed at " + bundleHooks.getPath());

		gameDataIsReadOnly(hooks);
		aReadOfTheGameIsNotAWrite(hooks);
		aCopyInTheTempFolderIsNotTheGame(hooks);
		everyPushNeedsItsOwnApproval(hooks);
		sourceIsNotEditedWhileARunHoldsTheLock(bundleHooks);
		aRunThatIsOverDoesNotRefuse(bundleHooks);
		theStatusOfAPipelineBelongsToItsFilter(hooks);
		nothingStartsOnTheOwnersDesktop(hooks);
		aBackslashInAnInlineScriptIsRefused(bundleHooks);
		aHeredocFeedingPythonOrJavaIsRefused(bundleHooks);
		aFanOutWaitsForTheOwnersYes(bundleHooks);
		aForbiddenCommandIsRefused(hooks);
		aPayloadOutsideTheCodePageIsStillJudged(hooks);
		aCrashedCheckStillRefusesWhatDoesNotRepairIt(hooks);
		aSweepRunsWhereNothingCanKillIt(bundleHooks);
		theFileBeingMutatedIsNotRead(bundleHooks);
		everyShellReadingGuardIsHeldInEverySpelling(hooks);

		System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
		if (fails != 0) {
			System.exit(1);
		}
	}

	/** A shell command or a file tool that writes under a protected root is refused. */
	static void gameDataIsReadOnly(File hooks) throws Exception {
		System.out.println("--- GAME DATA IS READ-ONLY");
		File guard = new File(hooks, "guard_game_data.py");
		check(guard.isFile(), "the guard is installed at " + guard.getPath());
		check(refuses(guard, bash("rm -rf RomFS/a/0/1/3")),
				"deleting part of the dump is refused");
		check(refuses(guard, bash("echo x > RomFS/a/0/1/3/0001")),
				"redirecting into the dump is refused");
		check(refuses(guard, bash("Set-Content Workspace/notes.txt value")),
				"a PowerShell write to the workspace is refused");
		check(refuses(guard, writes("Quux", "RomFS/a/0/1/3/0001")),
				"and so is a write by a file tool nobody has heard of - it is read by shape");
		//F173, AS MEASURED: each of these deleted from the dump while the guard allowed it
		check(refuses(guard, bash("env rm RomFS/a/0/1/3")), "a delete behind env is refused");
		check(refuses(guard, bash("sudo rm RomFS/a/0/1/3")), "a delete behind sudo is refused");
		check(refuses(guard, bash("bash -c " + QUOTE + "rm RomFS/a/0/1/3" + QUOTE)),
				"a delete handed to bash -c is refused");
		check(refuses(guard, bash("find RomFS -name '*.bak' -delete")),
				"find with -delete under the dump is refused - a writer by shape, not by name");
		check(refuses(guard, bash("rsync -a --delete empty/ RomFS/")),
				"rsync INTO the dump is refused - it writes its last argument");
		check(refuses(guard, bash("tar -xf backup.tar -C Workspace")),
				"tar extracting into the workspace is refused");
		check(refuses(guard, bash("cd RomFS && rm a/0/1/3")),
				"a relative delete after a cd into the dump is refused - judged where it lands");
		check(refuses(guard, bash("dd if=/tmp/x of=RomFS/a/0/1/3")),
				"dd writing into the dump is refused - its target is the of= word");
		check(refuses(guard, bash("rm /mnt/backup/project/RomFS/a")),
				"an absolute path into the dump is refused");
		check(refuses(guard, bash("cd " + QUOTE + "$X" + QUOTE + " && rm a")),
				"a relative write after a move to a folder the text does not name is UNKNOWN, and refused");
	}

	/**
	 * THE NEGATIVE HALF, and it is not a formality.
	 *
	 * <p>Every suite in this battery is handed the dump as a corpus and must read it. A guard
	 * that refused reads would refuse the whole battery, and a guard that refuses honest work
	 * is a guard that gets switched off within the hour - one beside this did exactly that.
	 */
	static void aReadOfTheGameIsNotAWrite(File hooks) throws Exception {
		System.out.println("--- and reading the game is still allowed");
		File guard = new File(hooks, "guard_game_data.py");
		check(!refuses(guard, bash("java -cp build/classes ctrmap.tests.GarcSniffTest "
				+ "RomFS/000400000011C400")),
				"a suite reading the dump is allowed");
		check(!refuses(guard, bash("cat RomFS/a/0/1/3/0001 > build/copy.bin")),
				"copying OUT of the dump is allowed");
		check(!refuses(guard, reads("Read", "RomFS/a/0/1/3/0001")),
				"and a plain read of a dump file is allowed");
		check(!refuses(guard, writes("Write", "src/ctrmap/WorkspaceSession.java")),
				"and a source file whose NAME holds a protected word is not a protected path");
		check(!refuses(guard, bash("find RomFS -name '*.bak'")),
				"find with no action only READS the dump, and is allowed");
		check(!refuses(guard, bash("rsync -a RomFS/ build/copy/")),
				"and rsync OUT of the dump is allowed - its last argument is the only one written");
		//F173's other direction, observed live: the guard refused edits that never touched the game
		check(!refuses(guard, bash("cp RomFS/a/0/1/3 build/copy")),
				"cp OUT of the dump is allowed - a copier writes only where it copies to");
		check(!refuses(guard, bash("sed -i 's/RomFS/x/' /tmp/scratch.txt")),
				"a sed -i whose SCRIPT names the dump is not a write to it");
		check(!refuses(guard, bash("python3 fnd.py add " + QUOTE + "Options > Workspace settings"
				+ QUOTE)), "a > inside a quoted argument is not a redirect");
		check(!refuses(guard, bash("dd if=RomFS/a/0/1/3 of=/tmp/x")),
				"and dd reading the dump is allowed");
	}

	/**
	 * NOR IS A COPY IN THE TEMP FOLDER THE GAME (2026-10-24). The refusal tells whoever it refuses to work on a copy
	 * under the scratch directory, and a copy named the way the game is named was refused there too: measuring 1.0.4 on
	 * copies of the owner's workspace and game needed them renamed (ws, game) before they could be written. A path is
	 * judged by the folder it really is - {@code ..} and links resolved, a relative one from the call's folder.
	 */
	static void aCopyInTheTempFolderIsNotTheGame(File hooks) throws Exception {
		System.out.println("--- and a copy in the temp folder is not the game");
		File guard = new File(hooks, "guard_game_data.py");
		//THE GUARD'S TEMP FOLDER, asked of the python that runs it: Java's can be another spelling or another folder
		Process p = new ProcessBuilder("python", "-B", "-c", "import tempfile, os; print(os.path.realpath(tempfile.gettempdir()))")
				.redirectErrorStream(true).start();
		String temp = CommitGuardTest.drain(p).trim().replace(File.separatorChar, '/');
		p.waitFor();
		check(new File(temp).isDirectory(), "python names its temp folder: " + temp);
		String home = hooks.getCanonicalPath().replace(File.separatorChar, '/');
		check(!home.toLowerCase(java.util.Locale.ROOT).startsWith(temp.toLowerCase(java.util.Locale.ROOT) + "/"),
				"control: the refusals above were asked from " + home + ", outside the temp folder - from inside it, "
				+ "every relative one would be a copy");
		String copy = temp + "/ctrmap_gamecopy";
		check(!refuses(guard, bash("rm -rf " + QUOTE + copy + "/RomFS/region" + QUOTE)),
				"deleting from a copy of the dump in the temp folder is allowed");
		check(!refuses(guard, writes("Write", copy + "/Workspace/notes.txt")),
				"a file tool writing into a copy of the workspace in the temp folder is allowed");
		check(!refusesIn(guard, bash("rm -rf RomFS/a"), copy, null),
				"a relative write from a call whose folder is a copy in the temp folder is allowed");
		check(refuses(guard, bash("rm -rf " + QUOTE + temp + "/../../RomFS/a" + QUOTE)),
				"a path that climbs out of the temp folder by .. is judged where it lands, and refused");
		check(refuses(guard, bash("rm -rf $TMP/RomFS/a")),
				"a path through a variable cannot be told to be a copy, and is refused");
		if (File.separatorChar == '\\' && temp.length() > 2 && temp.charAt(1) == ':') {
			String gitBash = "/" + Character.toLowerCase(temp.charAt(0)) + temp.substring(2);
			check(!refuses(guard, bash("rm -rf " + QUOTE + gitBash + "/ctrmap_gamecopy/RomFS/a" + QUOTE)),
					"and Git Bash's spelling of the same copy (" + gitBash + ") is the same copy");
		}
	}

	/**
	 * A push with no token, or a token for another commit, is refused.
	 *
	 * <p>The repository it judges is built here, so the real approval token is never read and
	 * never written.
	 */
	static void everyPushNeedsItsOwnApproval(File hooks) throws Exception {
		System.out.println("--- NEVER PUSH WITHOUT EXPLICIT PER-PUSH APPROVAL");
		File guard = new File(hooks, "guard_push.py");
		check(guard.isFile(), "the guard is installed at " + guard.getPath());
		File repo = Scratch.dir("pushgate");
		if (!git(repo, "init", "-q")) {
			Skip.section("git is not on PATH");
			return;
		}
		// The guard resolves the ref a push names, so the branch must BE master wherever the
		// suite runs - a machine whose git defaults to "main" has no master to push.
		git(repo, "symbolic-ref", "HEAD", "refs/heads/master");
		git(repo, "config", "user.email", "suite@example.invalid");
		git(repo, "config", "user.name", "suite");
		Files.write(new File(repo, "a.txt").toPath(), "one\n".getBytes(StandardCharsets.UTF_8));
		git(repo, "add", "a.txt");
		git(repo, "commit", "-q", "-m", "one");

		String said = ask(guard, "verdict", "git push origin master", repo.getAbsolutePath());
		check(said.contains("DENY"), "a push with no approval at all is refused");

		//F175: asked as the harness asks it, and in every spelling that runs the same push
		String here = repo.getAbsolutePath();
		check(refusesIn(guard, bash("git push origin master"), here, null),
				"a push in a tool call is refused with no approval");
		git(repo, "config", "alias.pp", "push");
		String[][] spellings = {
			{"echo pushing" + NL + "git push -u origin HEAD", "on a second line"},
			{"sh -c 'git push origin master'", "handed to sh -c"},
			{"powershell -Command " + QUOTE + "git push origin master" + QUOTE, "handed to PowerShell"},
			{"xargs git push origin master", "run by xargs"},
			{"timeout 60 git push origin master", "behind a runner with an operand"},
			{"git -c alias.p=push p origin master", "through an alias on the line"},
			{"git pp origin master", "through an alias in the repository's config"},
			{"git -c 'alias.s=!git push' s origin master", "through a shell alias"},
		};
		for (String[] spelling : spellings) {
			check(ask(guard, "decide", payload("Bash", "command", spelling[0], ""), here)
					.contains("DENY"), "a push " + spelling[1] + " is refused too");
		}
		check(ask(guard, "decide", payload("Bash", "command", "git push --help", ""), here)
				.contains("ALLOW"), "while a push's help page is not a push");

		String sha = capture(repo, "rev-parse", "HEAD").trim();
		approve(repo, sha);
		said = ask(guard, "verdict", "git push origin master", repo.getAbsolutePath());
		check(said.contains("ALLOW"), "an approval naming THIS commit allows the push");

		//EACH PUSH IN THE FOLDER IT RUNS IN: split one command at a time, `cd <other> && git push`
		//lost its cd and read THIS repository's approval for another one
		File other = new File(repo.getParentFile(), repo.getName() + "-other");
		check(other.mkdirs() && git(other, "init", "-q"), "built a second repository with no approval");
		git(other, "symbolic-ref", "HEAD", "refs/heads/master");
		git(other, "config", "user.email", "suite@example.invalid");
		git(other, "config", "user.name", "suite");
		git(other, "commit", "-q", "--allow-empty", "-m", "other");
		check(ask(guard, "decide", payload("Bash", "command", "git push origin master", ""), here)
				.contains("ALLOW"), "the approved push is allowed asked as a tool call");
		check(ask(guard, "decide", payload("Bash", "command", "cd " + other.getAbsolutePath()
				+ " && git push origin master", ""), here).contains("DENY"),
				"a push after a cd into a repository with no approval is judged THERE and refused");
		check(ask(guard, "decide", payload("Bash", "command", "cd ../" + other.getName() + NL
				+ "git push origin master", ""), here).contains("DENY"),
				"and so is one a line after a relative cd");
		check(ask(guard, "decide", payload("Bash", "command", "cd -" + NL
				+ "git push origin master", ""), here).contains("DENY"),
				"a push after a move to a folder the text does not name is UNKNOWN, and refused");
		check(ask(guard, "decide", payload("Bash", "command", "cd " + here
				+ " && git push origin master", ""), other.getAbsolutePath()).contains("ALLOW"),
				"while a cd INTO the approved repository is judged there and allowed");

		//THE SECOND DOOR: what git hands a pre-push hook, already resolved
		String zero = "0000000000000000000000000000000000000000";
		check(ask(guard, "prepush", "origin" + NL + "refs/heads/master " + sha
				+ " refs/heads/master " + zero, here).contains("ALLOW"),
				"git's pre-push door lets the approved commit through");
		check(ask(guard, "prepush", "origin" + NL + "refs/heads/master "
				+ "1111111111111111111111111111111111111111 refs/heads/master " + zero, here)
				.contains("DENY"), "and refuses a commit the approval does not name");
		check(ask(guard, "prepush", "origin" + NL + "(delete) " + zero + " refs/heads/master "
				+ sha, here).contains("DENY"), "and a deletion the approval does not say");
		check(ask(guard, "prepush", "upstream" + NL + "refs/heads/master " + sha
				+ " refs/heads/master " + zero, here).contains("DENY"),
				"and a remote the approval does not name");
		pushedThroughGitsOwnDoor(hooks);

		Files.write(new File(repo, "a.txt").toPath(), "two\n".getBytes(StandardCharsets.UTF_8));
		git(repo, "add", "a.txt");
		git(repo, "commit", "-q", "-m", "two");
		said = ask(guard, "verdict", "git push origin master", repo.getAbsolutePath());
		check(said.contains("DENY"),
				"and one more commit expires it - which is what per-push means");

		approve(repo, capture(repo, "rev-parse", "HEAD").trim());
		said = ask(guard, "verdict", "git push --force origin master", repo.getAbsolutePath());
		check(said.contains("DENY"), "a force push needs its own word, not a general yes");

		said = ask(guard, "verdict", "git status", repo.getAbsolutePath());
		check(said.contains("ALLOW"), "a command that is not a push is left alone");
	}

	/**
	 * F175's second door, end to end: a real {@code git push} to a scratch remote, through this
	 * project's own {@code .githooks/pre-push}. A hook git does not run - not executable, or not
	 * there - lets the unapproved push through, and this fails.
	 */
	static void pushedThroughGitsOwnDoor(File hooks) throws Exception {
		File root = hooks.getParentFile().getParentFile();
		File door = new File(root, ".githooks/pre-push");
		check(door.isFile(), "git's own door is installed at " + door.getPath());
		File remote = Scratch.dir("pushgate-remote");
		File repo = Scratch.dir("pushgate-door");
		check(git(remote, "init", "-q", "--bare") && git(repo, "init", "-q"),
				"built a repository and a remote for it");
		git(repo, "symbolic-ref", "HEAD", "refs/heads/master");
		git(repo, "config", "user.email", "suite@example.invalid");
		git(repo, "config", "user.name", "suite");
		git(repo, "remote", "add", "origin", remote.getAbsolutePath());
		File installed = new File(repo, ".claude/hooks");
		check(installed.mkdirs(), "gave it the push guard the door runs");
		copyWithImports(new File(hooks, "guard_push.py"), installed);
		//THE DOOR FOR THE PUSH ONLY: wired for the commit too, this project's commit hooks refused
		//the scratch commit, there was nothing to push, and "the push is refused" passed for that
		String doors = "core.hooksPath=" + door.getParentFile().getAbsolutePath();
		check(git(repo, "commit", "-q", "--allow-empty", "-m", "one"), "made a commit to push");
		check(!git(repo, "-c", doors, "push", "-q", "origin", "master"),
				"git itself refuses a push with no approval");
		approve(repo, capture(repo, "rev-parse", "HEAD").trim());
		check(git(repo, "-c", doors, "push", "-q", "origin", "master"),
				"and lets the approved commit through");
		check(git(repo, "commit", "-q", "--allow-empty", "-m", "two"), "made one more commit");
		check(!git(repo, "-c", doors, "push", "-q", "origin", "master"),
				"and one more commit needs its own approval at git's door too");
		check(git(repo, "push", "-q", "origin", "master"),
				"while the same push with no door goes through - so the door, and nothing else, refused it");
	}

	/**
	 * A mutation sweep is never run where a pipe or the foreground timeout can kill it mid-mutant:
	 * the {@code finally} that restores the module does not run, and a file carrying an injected
	 * fault is left on disk looking like an ordinary edit. Measured in F172: on the second line,
	 * after a {@code cd}, both copies of this guard let it through.
	 */
	static void aSweepRunsWhereNothingCanKillIt(File bundleHooks) throws Exception {
		System.out.println("--- a mutation sweep is never piped or run in the foreground");
		String guard = "guard_background_pipe.py";
		check(new File(bundleHooks, guard).isFile(), "the guard is installed at " + bundleHooks.getPath());
		File root = bundleProject(bundleHooks, "sweepguard");
		check(bundleRefuses(root, guard, bash("python tools/mutate2.py x | tail -5")),
				"a sweep piped into a filter is refused");
		check(bundleRefuses(root, guard, bash("python tools/mutate2.py x")),
				"and one in the foreground");
		check(!bundleRefuses(root, guard, bash("python tools/mutate2.py --list")),
				"while --list, which writes nothing, is not");
		check(!bundleRefuses(root, guard, bash("python x.py | tail -5")),
				"and neither is an ordinary pipe in the foreground");
		//F172: `destructive_scripts.txt` beside the guard is how this project hands it its sweep -
		//IN THE FOREGROUND, so only the list can refuse it: backgrounded, a pipe into tail is refused
		//for being a backgrounded pipe whatever runs, and that check passed with the list emptied
		check(bundleRefuses(root, guard, bash("cd src" + NL + "python tools/mutate2.py x | tail -5")),
				"this project's sweep is refused on a second line too");
		check(bundleRefuses(root, guard, payload("Bash", "command",
				"bash -c 'python tools/mutate2.py x | head -3'", ",\"run_in_background\":true")),
				"and piped inside bash -c in the background");
	}

	/**
	 * A read of the file a mutation run holds is refused: it shows the injected fault, not the
	 * source. F171: the lock every writer here leaves is {@code path}, then {@code pid=} and
	 * {@code what=} lines, and the guard took the whole of it as the path - so a read of the
	 * mutated file itself was allowed under every lock this project writes.
	 */
	static void theFileBeingMutatedIsNotRead(File bundleHooks) throws Exception {
		System.out.println("--- the file a mutation run holds is not read");
		String guard = "guard_mutation_read.py";
		check(new File(bundleHooks, guard).isFile(), "the guard is installed at " + bundleHooks.getPath());
		File root = bundleProject(bundleHooks, "mutationread");
		Files.write(new File(root, ".mutation-in-flight").toPath(),
				("src/ctrmap/A.java" + NL + "pid=" + pid() + NL + "what=a mutation sweep" + NL)
						.getBytes(StandardCharsets.UTF_8));
		check(bundleRefuses(root, guard, bash("cat src/ctrmap/A.java")),
				"a read of the mutated file is refused under the lock mutate2.py writes");
		check(!bundleRefuses(root, guard, bash("cat src/ctrmap/B.java")),
				"while a read of another file is not");
		//F167: this project's own copy of the guard let the Read tool through
		check(bundleRefuses(root, guard, reads("Read", new File(root, "src/ctrmap/A.java").getAbsolutePath())),
				"and the Read tool's read of it is refused too");
	}

	static void approve(File repo, String sha) throws Exception {
		Files.write(new File(repo, ".push-approved").toPath(),
				("sha=" + sha + "\nremote=origin\nref=master\n"
				+ "said=yes, push that one, I have read the diff\n")
						.getBytes(StandardCharsets.UTF_8));
	}

	/** An edit to source while a run holds the lock is refused; a read is not. */
	static void sourceIsNotEditedWhileARunHoldsTheLock(File bundleHooks) throws Exception {
		System.out.println("--- NEVER EDIT SOURCE WHILE THE SUITE IS RUNNING");
		//THE LOCK IS THE WITNESS THAT HOLDS ON WINDOWS. The bundle's guard also watches the
		//session's tasks and the machine's processes, but a battery started in the owner's terminal
		//runs java naming build/classes, never this tree, and Windows reports no process's folder -
		//so this project lists its runners' locks in bundle_runs.LOCKS, and that is asked here
		String guard = "guard_suite_running.py";
		check(new File(bundleHooks, guard).isFile(), "the guard is installed at " + bundleHooks.getPath());
		File repo = bundleProject(bundleHooks, "runlock");
		File source = new File(repo, "src/ctrmap/A.java");
		check(source.getParentFile().mkdirs(), "built a scratch repository");
		Files.write(source.toPath(), "class A {}\n".getBytes(StandardCharsets.UTF_8));
		File lock = new File(repo, "build/.battery-running");
		lock.getParentFile().mkdirs();

		String edit = writes("Edit", source.getAbsolutePath());
		String read = reads("Read", source.getAbsolutePath());

		check(!bundleRefuses(repo, guard, edit), "with no run in flight the edit is allowed");

		//a process id certainly alive: this JVM's own
		Files.write(lock.toPath(), ("started now\npid=" + pid() + "\nwhat=the battery\n")
				.getBytes(StandardCharsets.UTF_8));
		check(bundleRefuses(repo, guard, edit), "a live run refuses an edit to source");
		check(!bundleRefuses(repo, guard, read), "but never a read - the run itself is reading");
		//F176: a shell write to source, in every spelling - `cd .` on the line before it passed
		check(bundleRefuses(repo, guard, bash("rm src/ctrmap/A.java")),
				"a live run refuses a shell command that deletes source");
		check(!bundleRefuses(repo, guard, bash("cat src/ctrmap/A.java")),
				"and not one that reads it");

		check(lock.delete(), "released the battery lock");
		File mutating = new File(repo, ".mutation-in-flight");
		Files.write(mutating.toPath(), "src/ctrmap/A.java\n".getBytes(StandardCharsets.UTF_8));
		check(bundleRefuses(repo, guard, edit), "and a mutation in flight refuses it too");
		check(mutating.delete(), "released the mutation lock");
	}

	/**
	 * A lock left behind by a crash must not refuse work forever.
	 *
	 * <p>The other half of the same rule: {@code build.ps1} ignores a lock no live battery holds -
	 * asked of tools/guard/battery_lock.py, which reads it by this guard's rule - because a red
	 * battery once left one behind and blocked the build for the fix. A guard that cannot be
	 * released is a guard that gets deleted.
	 */
	static void aRunThatIsOverDoesNotRefuse(File bundleHooks) throws Exception {
		System.out.println("--- and a run that is over releases");
		String guard = "guard_suite_running.py";
		File repo = bundleProject(bundleHooks, "runlock-stale");
		File source = new File(repo, "src/ctrmap/A.java");
		check(source.getParentFile().mkdirs(), "built a second scratch repository");
		Files.write(source.toPath(), "class A {}\n".getBytes(StandardCharsets.UTF_8));
		File lock = new File(repo, "build/.battery-running");
		lock.getParentFile().mkdirs();
		//a process id nothing is using; the guard asks the operating system rather than guessing
		Files.write(lock.toPath(), "started now\npid=999999\n".getBytes(StandardCharsets.UTF_8));
		check(!bundleRefuses(repo, guard, writes("Edit", source.getAbsolutePath())),
				"a lock whose process is gone does not refuse");
	}

	/**
	 * A REMEDY'S EXIT CODE ANSWERS ITS OWN QUESTION, NOT YOURS.
	 *
	 * <p>{@code $?} after a pipeline is the LAST stage's status, and a filter almost always
	 * succeeds. On the day this was written,
	 * {@code python tools/guard/liveness_check.py . | head -5; echo "exit=$?"} printed
	 * {@code exit=0} about a checker that had just exited 1 with seven findings.
	 *
	 * <p>The same shape cost this project thirty-three hours: a map rebuild wrote its
	 * 10.7 MB output and exited 1 - because the code reported the suite it had run
	 * UNDER, not whether a map was produced - and the runner discarded 49 minutes of
	 * work and then sat for a day and a half having decided nothing.
	 *
	 * <p>The pipeline itself is not refused. {@code ${PIPESTATUS[0]}} is the right
	 * question and must stay allowed, or the guard teaches nothing.
	 */
	static void theStatusOfAPipelineBelongsToItsFilter(File hooks) throws Exception {
		System.out.println("--- a status that belongs to the filter");
		File guard = new File(hooks, "guard_exit_code.py");
		check(guard.isFile(), "the guard is installed at " + guard.getPath());
		check(refuses(guard, bash("python x.py | head -5; echo " + QUOTE + "exit=$?"
			+ QUOTE)), "reading $? after a pipe into head is refused");
		check(refuses(guard, bash("java -cp b X | grep FAIL; echo $?")),
			"and after a pipe into grep");
		check(!refuses(guard, bash("python x.py | head -5; echo " + QUOTE
			+ "exit=${PIPESTATUS[0]}" + QUOTE)),
			"while the RIGHT question is allowed - a guard that refuses the correct"
			+ " spelling teaches nothing");
		check(!refuses(guard, bash("python x.py > out.txt 2>&1; echo " + QUOTE
			+ "exit=$?" + QUOTE)), "and so is a redirect, which keeps the status");
		check(!refuses(guard, bash("python x.py | head -5")),
			"and so is the pipeline itself, with nobody asking about the status");
	}

	/**
	 * The command string eats a backslash in an inline script body, not only in a heredoc.
	 *
	 * <p>MEASURED ON 2026-09-20, an hour after this guard was widened to every tool. A word
	 * boundary written through {@code python -c} inside a shell string arrived as <b>0x08</b>.
	 * The regex compiled. It matched nothing but digits, and the count it was measuring came
	 * out at 103 both before and after the change - when the true number was <b>64</b>.
	 * Thirty-nine entries were miscounted, and a silent failure looked exactly like a correct
	 * result.
	 *
	 * <p>The guard already refused a backslash in a heredoc body. It could not see the same
	 * escaping layer one flag away.
	 */
	static void aBackslashInAnInlineScriptIsRefused(File bundleHooks) throws Exception {
		System.out.println("--- a backslash in an inline script body");
		//THIS PROJECT'S SETTING, not the bundle's default: BODY_ESCAPES in the installed copy
		String guard = "guard_heredoc.py";
		check(new File(bundleHooks, guard).isFile(), "the guard is installed at " + bundleHooks.getPath());
		File root = bundleProject(bundleHooks, "inlinebackslash");
		String body = "import re; re.compile('" + BACKSLASH + "b(one|two)')";
		check(bundleRefuses(root, guard, bash("python -c " + QUOTE + body + QUOTE)),
				"a backslash in an inline script body is refused");
		check(bundleRefuses(root, guard, bash("pwsh -Command 'Write-Host " + QUOTE + "a" + BACKSLASH
				+ "nb" + QUOTE + "'")),
				"and so is one in a PowerShell -Command body");
		check(!bundleRefuses(root, guard, bash("python -c " + QUOTE + "print(chr(92) + 'b')" + QUOTE)),
				"while the safe spelling is allowed - chr(92) has nothing to eat");
		check(!bundleRefuses(root, guard, bash("python tools/guard/replant.py --selftest")),
				"and so is an ordinary script run");
	}

	/**
	 * F174: CLAUDE.md says "No Bash heredocs for Java or Python - write the script to a file", and
	 * this guard allowed a short one by design (up to 20 lines) - one rule spelled two ways. A
	 * heredoc whose command is a Python or Java interpreter is refused at any length.
	 */
	static void aHeredocFeedingPythonOrJavaIsRefused(File bundleHooks) throws Exception {
		System.out.println("--- a heredoc feeding Python or Java, at any length");
		//THIS PROJECT'S SETTING, not the bundle's default: NEVER_FED in the installed copy
		String guard = "guard_heredoc.py";
		File root = bundleProject(bundleHooks, "pyheredoc");
		check(bundleRefuses(root, guard, bash("python3 - <<'EOF'\nprint(1)\nEOF")),
				"a two-line Python heredoc is refused");
		check(bundleRefuses(root, guard, bash("py -3 - <<EOF\nprint(1)\nEOF")),
				"and so is one to the py launcher");
		check(bundleRefuses(root, guard, bash("jshell <<'EOF'\nSystem.out.println(1);\nEOF")),
				"and a jshell one");
		check(!bundleRefuses(root, guard, bash("git commit -F - <<'EOF'\nfix: a thing\nEOF")),
				"while a commit message through a heredoc is not");
		check(!bundleRefuses(root, guard, bash("grep -c x tools/a.py <<'EOF'\nx\nEOF")),
				"nor a command whose argument merely ends in .py");
		check(!bundleRefuses(root, guard, bash("python3 tools/guard/replant.py --selftest")),
				"nor running a script file");
	}

	/** A backslash, built so this file does not have to escape one to talk about one. */
	static final String BACKSLASH = String.valueOf((char) 92);

	/**
	 * Commands the owner said must never run, refused at the call.
	 *
	 * <p>THE HOLE THESE CAME OUT OF. {@code rule_map.py} reported GAME DATA IS READ-ONLY
	 * enforced, and it was - {@code guard_game_data.py} enforces the headline. Three
	 * separate imperatives sitting inside that same paragraph had nothing behind them at
	 * all, because the map was built to check a rule's HEADLINE. A rule can be
	 * enforced and its clauses unenforced at the same time, and 28 of 28 was reported over
	 * the top of it.
	 *
	 * <p>{@code git stash} is the one with a measured cost: this project's own rule is
	 * COMMIT BEFORE RUNNING ANYTHING THAT MUTATES SOURCES, because the mutation harness
	 * ends in {@code git reset --hard} and nearly erased an uncommitted fix. A stash is
	 * that same work hidden where no guard looks.
	 */
	static void aForbiddenCommandIsRefused(File hooks) throws Exception {
		System.out.println("--- a command the owner said must never run");
		File guard = new File(hooks, "guard_forbidden_command.py");
		check(guard.isFile(), "the guard is installed at " + guard.getPath());
		check(refuses(guard, bash("git stash")), "git stash is refused");
		check(refuses(guard, bash("cd x && git stash -u")),
			"...anywhere on the line, not only at the start");
		check(refuses(guard, bash("python tools/Tidewater.py run")),
			"and Tidewater is refused");
		//THE NEGATIVE HALF: a word that merely contains one is not one.
		check(!refuses(guard, bash("git status")), "while git status is allowed");
		check(!refuses(guard, bash("cat stashed_notes.txt")),
			"and a filename that merely contains the word is not the command");
		
		//AN UNREADABLE LIST IS NOT AN EMPTY ONE. A copy of the guard with no list beside it
		//must refuse everything rather than permit everything - the same shape as the fan-out
		//cap reading an unmeasurable width as zero and letting 791 agents through.
		File nolistRoot = Scratch.dir("nolist");
		File nolist = new File(nolistRoot, ".claude/hooks");
		File reader = new File(nolistRoot, ".claude/bundle-hooks");
		check(nolist.mkdirs() && reader.mkdirs(), "built a project with no forbidden list");
		File alone = new File(nolist, "guard_forbidden_command.py");
		//A PROJECT HAS ITS RULES FILE, and the hooks find their project by it (F216) - counted
		//from the hook's own folder, this scratch tree was its root by coincidence of depth.
		Files.write(new File(nolistRoot, "CLAUDE.md").toPath(), "# scratch".getBytes(StandardCharsets.UTF_8));
		copyWithImports(guard, nolist);
		//AND THE SHARED SHELL READER, where shellin looks for it. Without it every call is refused
		//as UNREADABLE - list or no list - and this check passed for that reason while a plant
		//made a missing list permit everything (measured: the plant survived)
		//...WITH ITS OWN IMPORTS. Copied alone it could not load once it imported bounded beside it
		//(2026-10-10), and the guard refused as UNREADABLE again - the reason this check exists to
		//tell apart - so the reader is copied the way the guard is, following what it imports.
		copyWithImports(new File(hooks.getParentFile().getParentFile(), ".claude/bundle-hooks/bundle_shell.py"),
				reader);
		//...with the SIBLINGS every guard imports beside it, copied by copyWithImports above.
		//Without them the copy could not load at all, and "the guard never answered" is a
		//different finding from "the guard permitted everything" - a fixture that cannot ask the
		//question cannot answer it.
		String said = ask(alone, "decide", bash("echo hello"), nolistRoot.getAbsolutePath());
		check(said.contains("DENY") && said.contains("list of commands that must never run here"),
				"a guard whose list has gone missing refuses rather than permits - and says it is the list: "
				+ oneLine(said));

		//...and the generated block nobody may hand-edit
		File gen = new File(hooks, "guard_generated_code.py");
		check(gen.isFile(), "the generated-code guard is installed at " + gen.getPath());
	}

	// ---------------------------------------------------------------- fixtures

	static long pid() {
		String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
		int at = name.indexOf('@');
		try {
			return Long.parseLong(at < 0 ? name : name.substring(0, at));
		} catch (NumberFormatException notAPid) {
			return 1;
		}
	}

	/**
	 * A payload built as JSON, with the path escaped once rather than four times.
	 *
	 * <p>Hand-escaped JSON inside a Java string inside a command line is how a fixture ends up
	 * asserting nothing: it becomes unreadable, then wrong, and a guard asked a malformed
	 * question answers no and looks like a pass.
	 */
	static String payload(String tool, String key, String value, String extra) {
		return "{\"tool_name\":" + quoted(tool) + ",\"tool_input\":{"
				+ quoted(key) + ":" + quoted(value) + extra + "}}";
	}

	static String writes(String tool, String path) {
		return payload(tool, "file_path", path, ",\"content\":\"x\"");
	}

	static String reads(String tool, String path) {
		return payload(tool, "file_path", path, "");
	}

	static String bash(String command) {
		String built = payload("Bash", "command", command, "");
		COMMANDS.put(built, command);
		return built;
	}

	/** The shell command each payload {@link #bash} built carries, so a refusal can be re-asked. */
	static final java.util.Map<String, String> COMMANDS = new java.util.HashMap<>();

	/** A double quote, for building a command without escaping it twice. */
	static final String QUOTE = String.valueOf((char) 34);

	/** A newline, for building a script body without escaping one. */
	static final String NL = String.valueOf((char) 10);

	/**
	 * EVERY FAN-OUT WAITS FOR THE OWNER'S TYPED YES. CLAUDE.md: "A fan-out needs the owner's
	 * explicit yes, with the agent count and a token estimate stated first." This project's own copy
	 * of the fan-out guard capped launches instead: one agent went through with no yes at all, and so
	 * did a workflow whose width it could not read - measured on the cases both copies' tests ask,
	 * the bundle's copy refused both (F167). That copy is gone; the bundle's asks the owner for every
	 * launch but the review the owner declared ({@code _review}).
	 *
	 * <p>WHY A CAP WAS NEVER THE POINT, measured in DT Engine's own documents on 2026-09-20: eleven
	 * agent audit rounds produced <b>476 finding headlines over 41 distinct code locations</b>, rounds
	 * 11 and 13 found nothing the earlier ones had not, and six agents owning disjoint files across a
	 * shared contract failed exactly as sixty do. A launch the owner said yes to is the only kind.
	 *
	 * <p>AND A LAUNCH IS WHAT ITS INPUT CARRIES, NOT WHAT ITS TOOL IS CALLED (F193): the harness's
	 * to-do tools carry no launch, and are never asked for a yes.
	 */
	static void aFanOutWaitsForTheOwnersYes(File bundleHooks) throws Exception {
		System.out.println("--- every fan-out waits for the owner's typed yes");
		String guard = "guard_fanout.py";
		check(new File(bundleHooks, guard).isFile(), "the guard is installed at " + bundleHooks.getPath());
		File root = bundleProject(bundleHooks, "fanout");
		String worktree = "await parallel(FILES.map(f => () => agent(`go`, "
				+ "{isolation: 'worktree'})))";
		String perFile = "const FILES = ['a/b/run.py', 'a/c/service.py']" + NL
				+ "await parallel(FILES.map(f => () => agent(`fix ${f}`)))";
		check(bundleRefuses(root, guard, payload("Workflow", "script", worktree, "")),
				"a worktree per agent is refused");
		check(bundleRefuses(root, guard, payload("Workflow", "script", perFile, "")),
				"and so is one agent per source file with an edit verb");
		check(bundleRefuses(root, guard, payload("Orchestrate", "script", perFile, "")),
				"under any tool name - the script's shape is the launch");
		check(bundleRefuses(root, guard, payload("Workflow", "name", "saved-review", "")),
				"and a saved workflow launched by name, though it carries no script");
		check(bundleRefuses(root, guard, payload("Agent", "prompt", "survey the parser",
				",\"subagent_type\":\"Explore\",\"description\":\"d\"")),
				"and one agent - this project's own copy let it through with no yes");
		String[][] todo = {
			{"TaskCreate", "{\"subject\": \"x\", \"description\": \"y\"}",
				"a to-do tool that spawns nothing is not a fan-out (TaskCreate)"},
			{"TaskUpdate", "{\"taskId\": \"1\", \"status\": \"completed\"}", "and neither is TaskUpdate"},
			{"TaskGet", "{\"taskId\": \"1\"}", "nor TaskGet"},
			{"mcp__ccd_session__spawn_task", "{\"title\": \"t\", \"prompt\": \"p\", \"tldr\": \"x\"}",
				"nor a task suggestion, which carries a prompt but nothing that launches it"},
			{"TaskStop", "{\"task_id\": \"b1\"}", "nor the brake"},
		};
		for (String[] c : todo) {
			check(!bundleRefuses(root, guard, "{\"tool_name\": " + quoted(c[0]) + ", \"tool_input\": "
					+ c[1] + "}"), c[2]);
		}
	}

	/**
	 * F177: guard_all read its payload with json.load(sys.stdin) - the locale's code page, cp1252 on
	 * Windows - and exited 0, which is ALLOW, on any failure to read it. One character the code page
	 * cannot decode, in a path, a commit message or a comment, and every project guard was skipped.
	 * The whole hook runs as the harness runs it, payload on stdin; PYTHONIOENCODING pins the code
	 * page so this means the same on every system. U+3041 is E3 81 81 in UTF-8, and 0x81 is one of
	 * the five bytes cp1252 leaves undefined - most non-Latin text decodes to garbage WITHOUT
	 * raising, which would prove nothing.
	 */
	static void aPayloadOutsideTheCodePageIsStillJudged(File hooks) throws Exception {
		System.out.println("--- a payload the code page cannot decode is still judged");
		File all = new File(hooks, "guard_all.py");
		String said = runWhole(all, "{\"tool_name\": \"Bash\", \"tool_input\": {\"command\":"
				+ " \"git stash  # ぁ\"}, \"cwd\": " + quoted(hooks.getParentFile().getParentFile().getAbsolutePath()) + "}");
		check(said.contains("\"deny\"") && said.contains("stash"),
				"a forbidden command carrying one character cp1252 cannot decode is still refused - said "
				+ oneLine(said));
		said = runWhole(all, "this is not a payload");
		check(said.contains("\"deny\""),
				"and a payload the guard stack cannot read is refused, never waved through - said " + oneLine(said));
	}

	/**
	 * F176 FOR THE DISPATCHER. guard_all reads shell text to let a crashed check's repair through -
	 * a call that touches only the files the check broke in (OUTSTANDING 2026-09-22, paid for twice)
	 * - so the refusal it keeps for every OTHER call must hold in every spelling: behind a
	 * {@code cd .} line, handed to {@code bash -c}, behind a runner. A write dressed as a repair would
	 * otherwise walk past a guard stack that could not run. Asked of a scratch project named as this
	 * one is, whose wiring check really raises.
	 */
	static void aCrashedCheckStillRefusesWhatDoesNotRepairIt(File hooks) throws Exception {
		System.out.println("--- a crashed check still refuses what does not repair it");
		File project = new File(Scratch.dir("crashspelling"), "CTRMap");
		File scratchHooks = new File(project, ".claude/hooks");
		File checkerDir = new File(project, "tools/guard");
		check((scratchHooks.mkdirs() || scratchHooks.isDirectory())
				&& (checkerDir.mkdirs() || checkerDir.isDirectory()),
				"built a scratch project whose wiring check raises");
		copyWithImports(new File(hooks, "guard_all.py"), scratchHooks);
		Files.write(new File(checkerDir, "hooks_wired.py").toPath(),
				("def findings(root):" + NL + "    return undefined_name" + NL).getBytes(StandardCharsets.UTF_8));
		File all = new File(scratchHooks, "guard_all.py");
		String cwd = project.getAbsolutePath();
		check(refusesIn(all, bash("echo x > notes.txt"), cwd, null),
				"a write to a file the crashed check did not break in is refused");
		check(!refusesIn(all, bash("git checkout -- tools/guard/hooks_wired.py"), cwd, null),
				"while the git checkout of the file it broke in goes through");
	}

	/** Runs a hook as the harness does: the payload as UTF-8 on stdin, its answer on stdout. */
	static String runWhole(File guard, String payload) throws Exception {
		ProcessBuilder pb = new ProcessBuilder("python", guard.getAbsolutePath());
		pb.environment().put("PYTHONIOENCODING", "cp1252");
		pb.environment().put("CTRMAP_GUARD_BROKEN", "1");
		pb.redirectErrorStream(true);
		Process p = pb.start();
		try (java.io.OutputStream in = p.getOutputStream()) {
			in.write(payload.getBytes(StandardCharsets.UTF_8));
		}
		String said = CommitGuardTest.drain(p);
		p.waitFor();
		return said;
	}

	/**
	 * A scratch project holding a copy of the bundle's hooks AS THIS PROJECT INSTALLED THEM - its
	 * ADAPT settings and its {@code destructive_scripts.txt} included - so a guard that reads its
	 * project's root reads the scratch one, never this tree, and never this tree's locks. The copy is
	 * the whole folder: a guard's siblings are part of what it is.
	 */
	static File bundleProject(File bundleHooks, String name) throws Exception {
		File root = Scratch.dir(name);
		File hooks = new File(root, ".claude/bundle-hooks");
		check(hooks.mkdirs() || hooks.isDirectory(), "built a scratch project for " + name);
		File[] files = bundleHooks.listFiles(File::isFile);
		for (File file : files == null ? new File[0] : files) {
			Files.copy(file.toPath(), new File(hooks, file.getName()).toPath(),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		Files.copy(new File(bundleHooks.getParentFile(), "bundle-install.json").toPath(),
				new File(root, ".claude/bundle-install.json").toPath(),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		//A PROJECT HAS ITS RULES FILE, and the hooks find their project by it (F216) - and it is a
		//repository, as every project is: several hooks ask git what the tree holds
		Files.write(new File(root, "CLAUDE.md").toPath(), "# scratch".getBytes(StandardCharsets.UTF_8));
		Files.write(new File(root, "transcript.jsonl").toPath(), new byte[0]);
		check(git(root, "init", "-q"), "made the scratch project a repository");
		return root;
	}

	/**
	 * DENY or ALLOW from one of the bundle's guards in {@code root}, asked as the harness asks it:
	 * the whole hook, the payload as UTF-8 on stdin with its working folder and transcript, the
	 * answer in its exit code or its JSON. A guard that answers neither has not answered.
	 */
	static String bundleSays(File root, String guard, String payload) throws Exception {
		String whole = "{\"cwd\": " + quoted(root.getAbsolutePath()) + ", \"transcript_path\": "
				+ quoted(new File(root, "transcript.jsonl").getAbsolutePath())
				+ ", \"hook_event_name\": \"PreToolUse\", \"session_id\": \"hookrefusals\", "
				+ payload.trim().substring(1);
		ProcessBuilder pb = new ProcessBuilder("python", "-B",
				new File(root, ".claude/bundle-hooks/" + guard).getAbsolutePath());
		pb.directory(root);
		pb.environment().put("CLAUDE_PROJECT_DIR", root.getAbsolutePath());
		pb.environment().put("PYTHONIOENCODING", "utf-8");
		pb.redirectErrorStream(true);
		Process p = pb.start();
		try (java.io.OutputStream in = p.getOutputStream()) {
			in.write(whole.getBytes(StandardCharsets.UTF_8));
		}
		String said = CommitGuardTest.drain(p);
		int code = p.waitFor();
		if (code == 2 || said.contains("\"deny\"")) {
			return "DENY " + oneLine(said);
		}
		if (code == 0) {
			return "ALLOW " + oneLine(said);
		}
		System.out.println("  FAIL: " + guard + " never answered (exit " + code + ") - " + oneLine(said));
		fails++;
		return "CRASH " + oneLine(said);
	}

	/** {@link #bundleSays}, and every refusal of a shell command held in every spelling (F176). */
	static boolean bundleRefuses(File root, String guard, String payload) throws Exception {
		boolean refused = bundleSays(root, guard, payload).startsWith("DENY");
		String command = COMMANDS.get(payload);
		if (refused && command != null) {
			String shellQuoted = "'" + command.replace("'", "'\\''") + "'";
			java.util.List<String[]> spellings = new java.util.ArrayList<>();
			spellings.add(new String[]{"cd ." + NL + command, "behind a preceding line"});
			spellings.add(new String[]{"bash -c " + shellQuoted, "handed to bash -c"});
			if (!command.matches("(?s).*(&&|\\|\\||;|\\||\\n).*")) {
				spellings.add(new String[]{"env " + command, "behind a runner"});
			}
			for (String[] spelling : spellings) {
				check(bundleSays(root, guard, payload("Bash", "command", spelling[0], "")).startsWith("DENY"),
						guard + " refuses " + oneLine(command) + " " + spelling[1] + " too");
			}
		}
		return refused;
	}

	/**
	 * Copies {@code guard} into {@code to} with every module it imports from its own folder,
	 * followed through theirs - DERIVED from the files, never listed. The lists this replaced
	 * went red twice: {@code hook_env.py} joined one the hour the environment names moved into
	 * one place, and {@code _tree_root.py} both the day the root stopped being counted (F216).
	 */
	static void copyWithImports(File guard, File to) throws java.io.IOException {
		java.util.regex.Pattern imported = java.util.regex.Pattern.compile("(?m)^\\s*(?:import|from)\\s+(\\w+)");
		java.util.ArrayDeque<File> todo = new java.util.ArrayDeque<>();
		java.util.Set<String> done = new java.util.HashSet<>();
		todo.add(guard);
		while (!todo.isEmpty()) {
			File file = todo.poll();
			if (!done.add(file.getName())) {
				continue;
			}
			Files.copy(file.toPath(), new File(to, file.getName()).toPath(),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			java.util.regex.Matcher m = imported.matcher(
					new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
			while (m.find()) {
				File sibling = new File(file.getParentFile(), m.group(1) + ".py");
				if (sibling.isFile()) {
					todo.add(sibling);
				}
			}
		}
	}

	static String oneLine(String said) {
		String flat = said.replace('\n', ' ').replace('\r', ' ').trim();
		return flat.length() > 200 ? flat.substring(0, 200) : flat;
	}

	static String quoted(String s) {
		StringBuilder sb = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			if (c == '"' || c == '\\') {
				sb.append('\\');
			}
			//A NEWLINE IS ESCAPED TOO: JSON forbids a raw one inside a string, and a payload
			//json.loads cannot read is a guard that never answered
			if (c == '\n') {
				sb.append("\\n");
				continue;
			}
			sb.append(c);
		}
		return sb.append('"').toString();
	}

	static boolean refuses(File guard, String payload) throws Exception {
		return refusesIn(guard, payload, guard.getParentFile().getAbsolutePath(), null);
	}

	/** {@link #refuses}, asked in a working folder and with environment entries of its own. */
	static boolean refusesIn(File guard, String payload, String cwd,
			java.util.Map<String, String> env) throws Exception {
		boolean refused = ask(guard, "decide", payload, cwd, env).contains("DENY");
		String command = COMMANDS.get(payload);
		if (refused && command != null) {
			heldInEverySpelling(guard, command, cwd, env);
		}
		return refused;
	}

	/** Every guard a shell refusal of which held in every spelling this suite asks. */
	static final java.util.Set<String> HELD = new java.util.TreeSet<>();

	/**
	 * F176, THE CLASS: a command a guard refuses as one line is refused behind a preceding line and
	 * handed to a shell too. Five of the project's guards passed their own refused case once a
	 * {@code cd .} line came first, and the push and game-data guards passed it handed to
	 * {@code bash -c} - each split shell text for itself. Asked here of EVERY refusal this suite
	 * checks, so a guard added tomorrow is held to it by its first refused case, not by a list.
	 */
	static void heldInEverySpelling(File guard, String command, String cwd,
			java.util.Map<String, String> env) throws Exception {
		String shellQuoted = "'" + command.replace("'", "'\\''") + "'";
		java.util.List<String[]> spellings = new java.util.ArrayList<>();
		spellings.add(new String[]{"cd ." + NL + command, "behind a preceding line"});
		spellings.add(new String[]{"bash -c " + shellQuoted, "handed to bash -c"});
		//a runner runs ONE command: `env cd x && rm a` does not move the rm, so only a single
		//command is the same command behind it
		if (!command.matches("(?s).*(&&|\\|\\||;|\\||\\n).*")) {
			spellings.add(new String[]{"env " + command, "behind a runner"});
		}
		boolean all = true;
		for (String[] spelling : spellings) {
			String again = payload("Bash", "command", spelling[0], "");
			boolean held = ask(guard, "decide", again, cwd, env).contains("DENY");
			check(held, guard.getName() + " refuses " + oneLine(command) + " " + spelling[1] + " too");
			all &= held;
		}
		if (all) {
			HELD.add(guard.getName());
		}
	}

	/**
	 * Nothing this session starts takes the owner's foreground: the editor's Java run bare is refused, and the same
	 * run through tools/guard/hidden_desktop.py is not. Its refused case is what holds the guard to every spelling
	 * (F176) - a shell reader with no case here was named by the scan and driven by nothing.
	 */
	static void nothingStartsOnTheOwnersDesktop(File hooks) throws Exception {
		System.out.println("--- nothing starts on the owner's desktop");
		File guard = new File(hooks, "guard_foreground.py");
		check(guard.isFile(), "the guard is installed at " + guard.getPath());
		check(refuses(guard, bash("java -cp build/classes ctrmap.CtrmapMainframe")),
				"the editor's Java started bare is refused");
		check(!refuses(guard, bash("python -B tools/guard/hidden_desktop.py --stream -- java -cp build/classes ctrmap.CtrmapMainframe")),
				"and the same run on the hidden desktop is not");
	}

	/**
	 * F176, THE CLASS BY THE TREE: every guard that reads shell text must show this suite one refusal
	 * held in every spelling above. The guards are found by reading their source for the shared
	 * reader, not listed - three guards whose subject was a list kept here passed their own one-line
	 * case and nothing else, and a list would have kept doing so for a guard added tomorrow.
	 */
	static void everyShellReadingGuardIsHeldInEverySpelling(File hooks) throws Exception {
		System.out.println("--- F176: every guard that reads shell text, held in every spelling");
		java.util.List<String> readers = new java.util.ArrayList<>();
		File[] found = hooks.listFiles((dir, name) -> name.startsWith("guard_") && name.endsWith(".py"));
		for (File guard : found == null ? new File[0] : found) {
			String body = new String(Files.readAllBytes(guard.toPath()), StandardCharsets.UTF_8);
			if (body.matches("(?s).*shellin\\.(commands|located|scripts|text)\\(.*")) {
				readers.add(guard.getName());
			}
		}
		java.util.Collections.sort(readers);
		//a scan that finds nothing reads as a clean pass: the guard this whole class began with
		//must be among what it found, or the scan itself is broken
		check(readers.contains("guard_game_data.py"),
				"the scan finds the shell readers (" + readers.size() + ": " + readers + ")");
		for (String name : readers) {
			check(HELD.contains(name), name + " shows a refusal held in every spelling");
		}
	}

	/**
	 * Runs one of the guard's own functions and prints ALLOW or DENY.
	 *
	 * <p>{@code what} is {@code decide} for a whole payload, or {@code verdict} for a bare
	 * command and a working directory. Driven through python so this asserts the real code and
	 * not a Java re-implementation of it.
	 */
	static String ask(File guard, String what, String arg, String cwd) throws Exception {
		return ask(guard, what, arg, cwd, null);
	}

	static String ask(File guard, String what, String arg, String cwd,
			java.util.Map<String, String> env) throws Exception {
		//THE PAYLOAD GOES IN A FILE, NOT ON THE COMMAND LINE. Windows re-quotes an argv entry
		//full of double quotes on its way through CreateProcess, so every JSON payload arrived
		//as something json.loads could not read - and a guard asked a malformed question
		//answers nothing, which is one line of plumbing away from reading as a pass.
		String script =
				"import importlib.util, io, sys, json\n"
				+ "spec = importlib.util.spec_from_file_location('g', sys.argv[1])\n"
				+ "m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)\n"
				+ "arg = io.open(sys.argv[3], encoding='utf-8').read()\n"
				+ "cwd = io.open(sys.argv[4], encoding='utf-8').read()\n"
				+ "if sys.argv[2] == 'verdict':\n"
				+ "    deny, why = m.verdict(arg, cwd)\n"
				+ "elif sys.argv[2] == 'prepush':\n"
				+ "    lines = arg.split(chr(10))\n"
				+ "    deny, why = m.pre_push(lines[0], lines[1:], cwd)\n"
				+ "else:\n"
				+ "    p = json.loads(arg)\n"
				+ "    p['cwd'] = cwd\n"
				+ "    deny, why = m.decide(p)\n"
				+ "print('DENY' if deny else 'ALLOW')\n"
				+ "print((why or '').replace(chr(10), ' ')[:200])\n";
		File dir = Scratch.dir("hookask");
		File file = new File(dir, "ask.py");
		Files.write(file.toPath(), script.getBytes(StandardCharsets.UTF_8));
		File payload = new File(dir, "payload.txt");
		Files.write(payload.toPath(), arg.getBytes(StandardCharsets.UTF_8));
		File where = new File(dir, "cwd.txt");
		Files.write(where.toPath(), cwd.getBytes(StandardCharsets.UTF_8));
		ProcessBuilder pb = new ProcessBuilder("python", file.getAbsolutePath(),
				guard.getAbsolutePath(), what, payload.getAbsolutePath(),
				where.getAbsolutePath());
		if (env != null) {
			pb.environment().putAll(env);
		}
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String said = CommitGuardTest.drain(p);
		p.waitFor();
		//A GUARD ASKED A MALFORMED QUESTION ANSWERS NOTHING, and nothing reads as ALLOW. Say so
		//rather than scoring it.
		if (!said.contains("DENY") && !said.contains("ALLOW")) {
			System.out.println("  FAIL: the guard never answered - " + oneLine(said));
			fails++;
		}
		return said;
	}

	static boolean git(File repo, String... args) throws Exception {
		java.util.List<String> line = new java.util.ArrayList<>();
		line.add("git");
		line.add("-C");
		line.add(repo.getAbsolutePath());
		line.addAll(java.util.Arrays.asList(args));
		ProcessBuilder pb = new ProcessBuilder(line);
		pb.redirectErrorStream(true);
		Process p = pb.start();
		CommitGuardTest.drain(p);
		return p.waitFor() == 0;
	}

	static String capture(File repo, String... args) throws Exception {
		java.util.List<String> line = new java.util.ArrayList<>();
		line.add("git");
		line.add("-C");
		line.add(repo.getAbsolutePath());
		line.addAll(java.util.Arrays.asList(args));
		ProcessBuilder pb = new ProcessBuilder(line);
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String said = CommitGuardTest.drain(p);
		p.waitFor();
		return said;
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok: " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}
}
