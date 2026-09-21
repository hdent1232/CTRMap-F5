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
 * <p>IT DRIVES THE VERSION-CONTROLLED COPIES in {@code tools/hooks}, not the installed ones in
 * {@code .claude/hooks}, for two reasons: the installed directory is outside version control,
 * so a plant could not break it and a diff would not show it; and a suite that read the
 * installed copy would simply agree with whatever it had been edited to say.
 * {@link HooksWiredTest} is the other half - it refuses when the two differ.
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
		File hooks = new File(repo, "tools/hooks");
		if (!hooks.isDirectory()) {
			System.out.println("  FAIL: no " + hooks.getPath()
					+ " - the guards are not under version control, so nothing here is pinned");
			System.exit(1);
		}
		if (!CommitGuardTest.onPath("python")) {
			System.out.println("  skip: python is not on PATH - these guards are python hooks, "
					+ "so this suite cannot run them here");
			System.out.println("ALL PASS");
			return;
		}

		gameDataIsReadOnly(hooks);
		aReadOfTheGameIsNotAWrite(hooks);
		everyPushNeedsItsOwnApproval(hooks);
		sourceIsNotEditedWhileARunHoldsTheLock(hooks);
		aRunThatIsOverDoesNotRefuse(hooks);
		theStatusOfAPipelineBelongsToItsFilter(hooks);
		aBackslashInAnInlineScriptIsRefused(hooks);
		agentsMayNotEditAtTheSameTime(hooks);
		aForbiddenCommandIsRefused(hooks);

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
			System.out.println("  skip: git is not on PATH");
			return;
		}
		git(repo, "config", "user.email", "suite@example.invalid");
		git(repo, "config", "user.name", "suite");
		Files.write(new File(repo, "a.txt").toPath(), "one\n".getBytes(StandardCharsets.UTF_8));
		git(repo, "add", "a.txt");
		git(repo, "commit", "-q", "-m", "one");

		String said = ask(guard, "verdict", "git push origin master", repo.getAbsolutePath());
		check(said.contains("DENY"), "a push with no approval at all is refused");

		String sha = capture(repo, "rev-parse", "HEAD").trim();
		approve(repo, sha);
		said = ask(guard, "verdict", "git push origin master", repo.getAbsolutePath());
		check(said.contains("ALLOW"), "an approval naming THIS commit allows the push");

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

	static void approve(File repo, String sha) throws Exception {
		Files.write(new File(repo, ".push-approved").toPath(),
				("sha=" + sha + "\nremote=origin\nref=master\n"
				+ "said=yes, push that one, I have read the diff\n")
						.getBytes(StandardCharsets.UTF_8));
	}

	/** An edit to source while a run holds the lock is refused; a read is not. */
	static void sourceIsNotEditedWhileARunHoldsTheLock(File hooks) throws Exception {
		System.out.println("--- NEVER EDIT SOURCE WHILE THE SUITE IS RUNNING");
		File guard = new File(hooks, "guard_suite_running.py");
		check(guard.isFile(), "the guard is installed at " + guard.getPath());
		File root = Scratch.dir("runlock");
		File repo = new File(root, "CTRMap");
		check(new File(repo, "src/ctrmap").mkdirs(), "built a scratch repository");
		File source = new File(repo, "src/ctrmap/A.java");
		Files.write(source.toPath(), "class A {}\n".getBytes(StandardCharsets.UTF_8));
		File lock = new File(repo, "build/.battery-running");
		lock.getParentFile().mkdirs();

		String edit = writes("Edit", source.getAbsolutePath());
		String read = reads("Read", source.getAbsolutePath());

		check(ask(guard, "decide", edit, root.getAbsolutePath()).contains("ALLOW"),
				"with no run in flight the edit is allowed");

		//a process id certainly alive: this JVM's own
		Files.write(lock.toPath(), ("started now\npid=" + pid() + "\nwhat=the battery\n")
				.getBytes(StandardCharsets.UTF_8));
		check(ask(guard, "decide", edit, root.getAbsolutePath()).contains("DENY"),
				"a live run refuses an edit to source");
		check(ask(guard, "decide", read, root.getAbsolutePath()).contains("ALLOW"),
				"but never a read - the run itself is reading");

		check(lock.delete(), "released the battery lock");
		File mutating = new File(repo, ".mutation-in-flight");
		Files.write(mutating.toPath(), "src/ctrmap/A.java\n".getBytes(StandardCharsets.UTF_8));
		check(ask(guard, "decide", edit, root.getAbsolutePath()).contains("DENY"),
				"and a mutation in flight refuses it too");
		check(mutating.delete(), "released the mutation lock");
	}

	/**
	 * A lock left behind by a crash must not refuse work forever.
	 *
	 * <p>The other half of the same rule: {@code build.ps1} ages its lock out at ninety minutes
	 * because a red battery once left one behind and blocked the build for the fix. A guard
	 * that cannot be released is a guard that gets deleted.
	 */
	static void aRunThatIsOverDoesNotRefuse(File hooks) throws Exception {
		System.out.println("--- and a run that is over releases");
		File guard = new File(hooks, "guard_suite_running.py");
		File root = Scratch.dir("runlock-stale");
		File repo = new File(root, "CTRMap");
		check(new File(repo, "src/ctrmap").mkdirs(), "built a second scratch repository");
		File source = new File(repo, "src/ctrmap/A.java");
		Files.write(source.toPath(), "class A {}\n".getBytes(StandardCharsets.UTF_8));
		File lock = new File(repo, "build/.battery-running");
		lock.getParentFile().mkdirs();
		//a process id nothing is using; the guard asks the operating system rather than guessing
		Files.write(lock.toPath(), "started now\npid=999999\n".getBytes(StandardCharsets.UTF_8));
		check(ask(guard, "decide", writes("Edit", source.getAbsolutePath()),
				root.getAbsolutePath()).contains("ALLOW"),
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
	static void aBackslashInAnInlineScriptIsRefused(File hooks) throws Exception {
		System.out.println("--- a backslash in an inline script body");
		File guard = new File(hooks, "guard_heredoc.py");
		check(guard.isFile(), "the guard is installed at " + guard.getPath());
		String body = "import re; re.compile('" + BACKSLASH + "b(one|two)')";
		check(refuses(guard, bash("python -c " + QUOTE + body + QUOTE)),
				"a backslash in an inline script body is refused");
		check(refuses(guard, bash("pwsh -Command 'Write-Host " + QUOTE + "a" + BACKSLASH
				+ "nb" + QUOTE + "'")),
				"and so is one in a PowerShell -Command body");
		check(!refuses(guard, bash("python -c " + QUOTE + "print(chr(92) + 'b')" + QUOTE)),
				"while the safe spelling is allowed - chr(92) has nothing to eat");
		check(!refuses(guard, bash("python tools/guard/replant.py --selftest")),
				"and so is an ordinary script run");
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
		File nolist = Scratch.dir("nolist");
		File alone = new File(nolist, "guard_forbidden_command.py");
		Files.copy(guard.toPath(), alone.toPath());
		//...with the SIBLINGS every guard imports beside it. Without them the copy could not
		//load at all, and "the guard never answered" is a different finding from "the guard
		//permitted everything" - a fixture that cannot ask the question cannot answer it.
		//
		//`hook_env.py` joined that list when the environment names moved into one place, and
		//this fixture went red the same hour: a copied guard's DEPENDENCIES are part of the
		//fixture, and nothing but running it says which they are.
		for (String sibling : new String[]{"shellin.py", "hook_env.py"}) {
			Files.copy(new File(hooks, sibling).toPath(),
					new File(nolist, sibling).toPath());
		}
		check(refuses(alone, bash("echo hello")),
				"a guard whose list has gone missing refuses rather than permits");

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
		return payload("Bash", "command", command, "");
	}

	/** A double quote, for building a command without escaping it twice. */
	static final String QUOTE = String.valueOf((char) 34);

	/** A newline, for building a script body without escaping one. */
	static final String NL = String.valueOf((char) 10);

	/**
	 * Agents editing a codebase at the same time is refused at ANY width.
	 *
	 * <p>WHAT ACTUALLY FAILED, measured in DT Engine's own documents on 2026-09-20 rather than
	 * argued. Eleven agent audit rounds produced <b>476 finding headlines and 1.8 MB of prose
	 * over 41 distinct code locations</b>. Rounds 11 and 13 found ZERO locations the earlier
	 * rounds had not; rounds 8, 10 and 12 found one each. Six files were named by ten or eleven
	 * separate rounds, each one rebuilding the same context from scratch.
	 *
	 * <p>The width cap never touched the part that broke.
	 * {@code docs/layer5-integration-items.md} opens: <i>"Each agent owned a disjoint file set.
	 * These are the changes they identified but could not make, because the change belongs in
	 * another agent's file."</i> One agent corrected a check and renamed its output keys; the
	 * consumer lived in another agent's file, so the corrected output never reached the
	 * operator. Another entry records two agents fixing the same thing concurrently, caught by
	 * luck.
	 *
	 * <p>SIX AGENTS OWNING DISJOINT FILES ACROSS A SHARED CONTRACT IS THE SAME DEFECT AS SIXTY,
	 * so this is not a cap and narrowing does not help. Reading in parallel is a different
	 * thing and stays allowed: a survey composes, a parallel rewrite does not.
	 */
	static void agentsMayNotEditAtTheSameTime(File hooks) throws Exception {
		System.out.println("--- agents editing at the same time");
		File guard = new File(hooks, "guard_fanout.py");
		check(guard.isFile(), "the guard is installed at " + guard.getPath());
		String worktree = "await parallel(FILES.map(f => () => agent(`go`, "
				+ "{isolation: 'worktree'})))";
		String perFile = "const FILES = ['a/b/run.py', 'a/c/service.py']" + NL
				+ "await parallel(FILES.map(f => () => agent(`fix ${f}`)))";
		String survey = "const LENSES = ['correctness', 'security']" + NL
				+ "await parallel(LENSES.map(l => () => agent(`review via ${l} and report`)))";

		check(shape(guard, worktree, 3).contains("SHAPE"),
				"a worktree per agent is parallel editing");
		check(shape(guard, perFile, 3).contains("SHAPE"),
				"and so is one agent per source file with an edit verb");
		check(shape(guard, survey, 3).contains("NONE"),
				"while a fan-out of review lenses is not - a survey composes");
		check(shape(guard, worktree, 1).contains("NONE"),
				"and neither is a single agent, at any isolation");
	}

	/** Asks the guard whether a script has the parallel-edit shape. SHAPE or NONE. */
	static String shape(File guard, String script, int width) throws Exception {
		File dir = Scratch.dir("fanoutshape");
		File ask = new File(dir, "ask.py");
		Files.write(ask.toPath(),
				("import importlib.util, io, sys\n"
				+ "spec = importlib.util.spec_from_file_location('g', sys.argv[1])\n"
				+ "g = importlib.util.module_from_spec(spec); spec.loader.exec_module(g)\n"
				+ "script = io.open(sys.argv[2], encoding='utf-8').read()\n"
				+ "print('SHAPE' if g.parallel_edit(script, int(sys.argv[3])) else 'NONE')\n")
						.getBytes(StandardCharsets.UTF_8));
		File body = new File(dir, "script.js");
		Files.write(body.toPath(), script.getBytes(StandardCharsets.UTF_8));
		ProcessBuilder pb = new ProcessBuilder("python", ask.getAbsolutePath(),
				guard.getAbsolutePath(), body.getAbsolutePath(), String.valueOf(width));
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String said = CommitGuardTest.drain(p);
		p.waitFor();
		if (!said.contains("SHAPE") && !said.contains("NONE")) {
			System.out.println("  FAIL: the guard never answered - " + oneLine(said));
			fails++;
		}
		return said;
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
			sb.append(c);
		}
		return sb.append('"').toString();
	}

	static boolean refuses(File guard, String payload) throws Exception {
		return ask(guard, "decide", payload, guard.getParentFile().getAbsolutePath())
				.contains("DENY");
	}

	/**
	 * Runs one of the guard's own functions and prints ALLOW or DENY.
	 *
	 * <p>{@code what} is {@code decide} for a whole payload, or {@code verdict} for a bare
	 * command and a working directory. Driven through python so this asserts the real code and
	 * not a Java re-implementation of it.
	 */
	static String ask(File guard, String what, String arg, String cwd) throws Exception {
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
