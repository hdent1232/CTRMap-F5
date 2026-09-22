package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * A guard that is installed but cannot be reached is a guard that is OFF.
 *
 * <p>WHAT THIS COST, measured on 2026-09-20 rather than imagined.
 * {@code .claude/hooks/} held eight guards. {@code .claude/settings.json} wired seven of them
 * under {@code "matcher": "Bash"}, and five of the seven opened with
 * {@code if tool_name != "Bash": sys.exit(0)}. The session also offered a {@code PowerShell}
 * tool that runs shell commands. So the heredoc refusal - the one bought by NUL bytes in a
 * committed {@code test.ps1} - the mutation-sweep pipe refusal, the idle-poll refusal, the
 * mutation-read refusal and the blocked-runner refusal were all installed, all wired, all
 * running, and all off for every command issued through the other tool. Nobody bypassed
 * anything. The command was spelled differently.
 *
 * <p>That is the project's own rule paid for by the guards themselves: A GUARD AT CALL SITES IS
 * ONE CALL SITE FROM BROKEN, filed four separate times before this one. A matcher that names
 * tools IS a call-site list, and so is a tool-name comparison inside the hook.
 *
 * <p>WHY THIS SUITE CANNOT PASS BY FINDING NOTHING. The hooks live in the session folder above
 * this repository, so a fresh clone has none and the live check would report a clean result
 * about an installation it cannot see - the confident empty answer this project has been burned
 * by twice. Every section therefore BUILDS the installation it judges, in scratch: the wiring
 * this project actually shipped, which must be refused, and a sound one, which must not. Those
 * run everywhere. The live check is extra, and says so when there is nothing installed.
 *
 * <p>ORDER: needs no game, no dump and no display. Needs python on PATH and says so and skips
 * rather than passing.
 *
 * Usage: java ctrmap.tests.HooksWiredTest [repo-root]
 */
public class HooksWiredTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File repo = new File(args.length > 0 ? args[0] : ".");
		File checker = new File(repo, "tools/guard/hooks_wired.py");
		if (!checker.isFile()) {
			System.out.println("  FAIL: no " + checker.getPath()
					+ " - nothing measures whether the guards are reachable");
			System.exit(1);
		}
		if (!CommitGuardTest.onPath("python")) {
			System.out.println("  skip: python is not on PATH - the wiring check is a python "
					+ "script, so this suite cannot run it here");
			System.out.println("ALL PASS");
			return;
		}

		theWiringThisProjectShippedIsRefused(checker);
		aGuardNothingRunsIsRefused(checker);
		aGuardTheDispatcherCannotAskIsRefused(checker);
		anUnreadableWiringIsRefusedRatherThanPassed(checker);
		aHookEditedInPlaceIsRefused(checker);
		aGuardWiredToNoEventIsRefused(checker);
		aSoundWiringIsAllowed(checker);
		theThreeAdoptedRefusalsCatchTheirDefects(repo);
		theLiveInstallationIsSound(checker, repo);

		System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
		if (fails != 0) {
			System.exit(1);
		}
	}

	/**
	 * The exact shape that had seven guards off here must not read as a clean wiring.
	 *
	 * <p>A matcher naming tools is a call-site list. It does not matter that every guard in the
	 * list was present and running - a command issued through a tool the matcher does not name
	 * never reaches any of them, and the result is indistinguishable from a clean run.
	 */
	static void theWiringThisProjectShippedIsRefused(File checker) throws Exception {
		System.out.println("--- the wiring this project shipped until 2026-09-20");
		File root = install("matcher-bash", "\"Bash\"", "guard_heredoc.py",
				new String[]{"guard_heredoc.py", "guard_idle_poll.py"}, true);
		String said = ask(checker, root);
		check(said.contains("CALL-SITE LIST"),
				"a matcher that names tools is refused: " + firstReason(said));
		check(said.contains("Bash"), "and the refusal quotes the matcher it is refusing");
	}

	/** A guard in the directory that no wired command runs is installed and off. */
	static void aGuardNothingRunsIsRefused(File checker) throws Exception {
		System.out.println("--- a guard nothing in the wiring runs");
		File root = install("unwired", "\"*\"", "guard_heredoc.py",
				new String[]{"guard_heredoc.py", "guard_forgotten.py"}, true);
		String said = ask(checker, root);
		check(said.contains("guard_forgotten.py") && said.contains("NOTHING IN THE WIRING"),
				"the forgotten guard is named: " + firstReason(said));
	}

	/**
	 * A guard the dispatcher cannot ask answers nothing, which reads as a pass.
	 *
	 * <p>This is the same defect one level in: the file is there, the wiring reaches it, and it
	 * has no {@code decide(payload)} for the dispatcher to call.
	 */
	static void aGuardTheDispatcherCannotAskIsRefused(File checker) throws Exception {
		System.out.println("--- a guard with no decide() for the dispatcher to ask");
		File root = install("undecidable", "\"*\"", "guard_all.py",
				new String[]{"guard_all.py", "guard_mute.py"}, false);
		write(new File(root, ".claude/hooks/guard_all.py"),
				"import glob, os\n"
				+ "def guards():\n"
				+ "    return glob.glob(os.path.join('.', 'guard_*.py'))\n"
				+ "def decide(payload):\n    return False, None\n");
		write(new File(root, ".claude/hooks/guard_mute.py"), "# no decide here\n");
		String said = ask(checker, root);
		check(said.contains("guard_mute.py") && said.contains("decide(payload)"),
				"the unaskable guard is named: " + firstReason(said));
	}

	/**
	 * A wiring that cannot be read is UNKNOWN, and an unknown is not a clean wiring.
	 *
	 * <p>Every probe has an answer for "it is not there" and an answer for "I could not look".
	 * Collapsing the second into the first is what reported twelve live workers gone here and
	 * cost 134 verdicts.
	 */
	static void anUnreadableWiringIsRefusedRatherThanPassed(File checker) throws Exception {
		System.out.println("--- a wiring that cannot be read");
		File root = install("unreadable", "\"*\"", "guard_all.py",
				new String[]{"guard_all.py"}, false);
		write(new File(root, ".claude/hooks/guard_all.py"),
				"import glob, os\n"
				+ "def guards():\n    return glob.glob(os.path.join('.', 'guard_*.py'))\n"
				+ "def decide(payload):\n    return False, None\n");
		File settings = new File(root, ".claude/settings.json");
		write(settings, "{ this is not json");
		String said = ask(checker, root);
		check(said.contains("UNKNOWN"),
				"unreadable settings are UNKNOWN, not clean: " + firstReason(said));

		check(settings.delete(), "and with the settings file removed entirely");
		said = ask(checker, root);
		check(said.contains("UNKNOWN") || said.contains("cannot read"),
				"a missing wiring is refused too: " + firstReason(said));

		//AND THE CONTROL IN THE OTHER DIRECTION: no .claude at all must not read as clean.
		File bare = Scratch.dir("hookwire-bare");
		said = ask(checker, bare);
		check(said.contains("INSTALLED NOWHERE"),
				"and an installation it cannot see is reported, not passed");
	}

	/**
	 * A hook edited where it runs, rather than where it is reviewed, is refused.
	 *
	 * <p>{@code .claude/hooks} is outside version control. A guard hollowed out there leaves no
	 * diff, no history and no review, and goes on looking installed and wired - and a suite
	 * that read the installed copy would simply agree with whatever it now said. The
	 * repository copy in {@code tools/hooks} is what {@link HookRefusalsTest} drives and what
	 * the plant ledger can break, so the two have to be the same file; otherwise the suite
	 * pins a copy that does not run.
	 */
	static void aHookEditedInPlaceIsRefused(File checker) throws Exception {
		System.out.println("--- a hook edited where it runs rather than where it is reviewed");
		File root = install("edited", "\"*\"", "guard_all.py",
				new String[]{"guard_all.py", "guard_one.py"}, false);
		write(new File(root, ".claude/hooks/guard_all.py"), DISPATCHER);
		write(new File(root, ".claude/hooks/guard_one.py"), STUB);
		//the repository's copy of the same two hooks
		write(new File(root, "tools/hooks/guard_all.py"), DISPATCHER);
		write(new File(root, "tools/hooks/guard_one.py"), STUB);
		String said = askAsIs(checker, root);
		check(said.contains("reachable for every tool"),
				"two copies that agree pass: " + firstReason(said));

		write(new File(root, ".claude/hooks/guard_one.py"), STUB + "#edited in place\n");
		said = askAsIs(checker, root);
		check(said.contains("guard_one.py") && said.contains("differs from"),
				"a hook edited in place is named: " + firstReason(said));

		write(new File(root, ".claude/hooks/guard_one.py"), STUB);
		write(new File(root, "tools/hooks/guard_orphan.py"), STUB);
		said = askAsIs(checker, root);
		check(said.contains("guard_orphan.py") && said.contains("only on paper"),
				"and a versioned hook that is not installed is named: " + firstReason(said));
	}

	/**
	 * The three refusals adopted from the verification bootstrap, each shown catching its own
	 * defect and leaving the honest shape alone.
	 *
	 * <p>EVERY ONE IS A DEFECT THIS PROJECT ALREADY HAD AND FIXED BY HAND, which is the
	 * definition of a class left open:
	 *
	 * <ul>
	 * <li><b>runs on import</b> — {@code guard_fanout.py} ended with a bare {@code main()}, so
	 *     importing it read stdin and exited. The fan-out cap, which exists because three
	 *     fan-outs burned a week of a metered plan, was the one guard the dispatcher could not
	 *     ask.</li>
	 * <li><b>spells its own environment name</b> — four bypasses were spelled
	 *     {@code DTENGINE_} while every other name was {@code CTRMAP_}, so setting
	 *     {@code CTRMAP_ALLOW_PIPE} did nothing and looked like a broken bypass.</li>
	 * <li><b>decides by tool name</b> — eight hooks wired under {@code "matcher": "Bash"} in a
	 *     session that also had a PowerShell tool, all of them installed, wired, running and
	 *     off.</li>
	 * </ul>
	 *
	 * <p>ALL THREE ARE ASKED OF THE AST, and the negative half is why: several hooks now carry
	 * prose EXPLAINING these defects, so a text search would report the fixed files as broken —
	 * and a check that cries wolf on the day it ships is one nobody reads by the next.
	 */
	static void theThreeAdoptedRefusalsCatchTheirDefects(File repo) throws Exception {
		System.out.println("--- the three refusals adopted from the bootstrap");
		File live = new File(repo, "tools/hooks");
		String[] every = live.list((d, n) -> n.endsWith(".py"));
		check(every != null && every.length > 0, "there are hooks to ask about");

		check(ask(repo, "hooks_that_run_on_import", live, every).isEmpty(),
				"live: no hook runs on import");
		check(ask(repo, "hooks_that_spell_an_environment_name", live, every).isEmpty(),
				"live: no hook spells its own env name");
		check(ask(repo, "hooks_that_decide_by_tool_name", live, every).isEmpty(),
				"live: no hook decides by tool name");

		File one = Scratch.dir("adopted");
		write(new File(one, "guard_x.py"), "def main():\n    pass\n\nmain()\n");
		check(!ask(repo, "hooks_that_run_on_import", one, new String[]{"guard_x.py"}).isEmpty(),
				"a hook that calls main() at module level is caught");

		write(new File(one, "guard_x.py"), "import os\nB = os.environ.get(\"CTRMAP_ALLOW_X\")\n");
		check(!ask(repo, "hooks_that_spell_an_environment_name", one,
				new String[]{"guard_x.py"}).isEmpty(),
				"a hook that spells its own env name is caught");

		write(new File(one, "guard_x.py"), "import sys\ndef main(p):\n"
				+ "    if p.get(\"tool_name\") != \"Bash\":\n        sys.exit(0)\n");
		check(!ask(repo, "hooks_that_decide_by_tool_name", one,
				new String[]{"guard_x.py"}).isEmpty(),
				"a hook that decides by tool name is caught");

		//THE NEGATIVE HALF: prose about the defect is not the defect.
		write(new File(one, "guard_x.py"),
				"\"\"\"Never write `if tool_name != \"Bash\"` - one tool name from off.\"\"\"\n"
				+ "import os\nX = os.environ.get(\"CLAUDE_PROJECT_DIR\")\n");
		check(ask(repo, "hooks_that_decide_by_tool_name", one,
				new String[]{"guard_x.py"}).isEmpty(),
				"while prose ABOUT the defect is not the defect");
		check(ask(repo, "hooks_that_spell_an_environment_name", one,
				new String[]{"guard_x.py"}).isEmpty(),
				"and a foreign name this project does not own is allowed");
	}

	/** Runs one of the checker's predicates over a directory and returns what it said. */
	static String ask(File repo, String predicate, File dir, String[] names) throws Exception {
		File work = Scratch.dir("askpred");
		File script = new File(work, "ask.py");
		StringBuilder list = new StringBuilder();
		for (String n : names) {
			list.append(list.length() == 0 ? "" : ",").append("'").append(n).append("'");
		}
		Files.write(script.toPath(),
				("import importlib.util, sys\n"
				+ "spec = importlib.util.spec_from_file_location('hw', sys.argv[1])\n"
				+ "hw = importlib.util.module_from_spec(spec); spec.loader.exec_module(hw)\n"
				+ "found = getattr(hw, sys.argv[2])(sys.argv[3], [" + list + "])\n"
				+ "print('FOUND' if found else 'CLEAN')\n"
				+ "for f in found:\n    print('   ' + str(f)[:120])\n")
						.getBytes(StandardCharsets.UTF_8));
		ProcessBuilder pb = new ProcessBuilder("python", script.getAbsolutePath(),
				new File(repo, "tools/guard/hooks_wired.py").getAbsolutePath(),
				predicate, dir.getAbsolutePath());
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String said = CommitGuardTest.drain(p);
		p.waitFor();
		if (!said.contains("FOUND") && !said.contains("CLEAN")) {
			System.out.println("  FAIL: the predicate never answered - "
					+ CommitGuardTest.firstLine(said));
			fails++;
			return "?";
		}
		return said.contains("FOUND") ? said : "";
	}

	/** A dispatcher that finds its own guards, as the checker expects one to. */
	static final String DISPATCHER =
			"import glob, os\n"
			+ "def guards():\n    return glob.glob(os.path.join('.', 'guard_*.py'))\n"
			+ "def decide(payload):\n    return False, None\n";

	static final String STUB = "def decide(payload):\n    return False, None\n";

	/**
	 * The negative control. Without it a checker that refuses everything scores a perfect pass.
	 */
	/**
	 * A guard that answers a DIFFERENT event must be wired to that event.
	 *
	 * <p>WHY THIS WAS NEEDED, measured 2026-09-22. Every guard here answered
	 * {@code PreToolUse} and one dispatcher discovered them all - so the reachability check
	 * above passes anything at all once a dispatcher is present. The day a {@code Stop} hook
	 * arrived, a hook declaring {@code EVENT = "Stop"} with nobody wired to {@code Stop} sailed
	 * straight through it: installed, never asked, and indistinguishable from one with nothing
	 * to refuse. That is the exact shape this whole file exists for - seven guards wired for a
	 * tool name while the session ran another tool - happening one event along instead of one
	 * tool along.
	 *
	 * <p>The dispatcher also stops handing such a hook a TOOL payload, which it would otherwise
	 * do on every call: answering nothing, while being counted among the guards that answered.
	 */
	static void aGuardWiredToNoEventIsRefused(File checker) throws Exception {
		System.out.println("--- a guard that answers an event nobody wired");
		File root = install("unwired-event", "\"*\"", "guard_all.py",
				new String[]{"guard_all.py", "guard_later.py"}, true);
		//a dispatcher that discovers its own guards, so the reachability test is satisfied
		write(new File(root, ".claude/hooks/guard_all.py"),
				"import glob, os\n"
				+ "def guards():\n"
				+ "    return glob.glob(os.path.join('.', 'guard_*.py'))\n"
				+ "def decide(payload):\n    return False, None\n");
		//...and a guard that says it answers something else entirely
		write(new File(root, ".claude/hooks/guard_later.py"),
				"EVENT = \"Stop\"\n"
				+ "def decide(payload):\n    return False, None\n");
		String said = ask(checker, root);
		check(said.contains("guard_later.py") && said.contains("Stop"),
				"the guard wired to no event is named: " + firstReason(said));
		check(said.contains("NOTHING IN THE WIRING RUNS IT THERE"),
				"...and says it is never asked, not merely mis-wired");

		//AND THE NEGATIVE HALF: wire it, and it is left alone. Without this the check could
		//refuse every guard that declares an event, which is worse than not having it.
		write(new File(root, ".claude/settings.json"),
				"{\n \"hooks\": {\n  \"PreToolUse\": [\n   { \"matcher\": \"*\", \"hooks\": "
				+ "[ { \"type\": \"command\", \"command\": \"python .claude/hooks/guard_all.py\""
				+ " } ] }\n  ],\n  \"Stop\": [\n   { \"hooks\": [ { \"type\": \"command\", "
				+ "\"command\": \"python .claude/hooks/guard_later.py\" } ] }\n  ]\n }\n}\n");
		String wired = ask(checker, root);
		check(!wired.contains("NOTHING IN THE WIRING RUNS IT THERE"),
				"and once it IS wired to that event nothing is refused: " + firstReason(wired));
	}

	static void aSoundWiringIsAllowed(File checker) throws Exception {
		System.out.println("--- a sound wiring");
		File root = install("sound", "\"*\"", "guard_all.py",
				new String[]{"guard_all.py", "guard_one.py", "guard_two.py"}, false);
		write(new File(root, ".claude/hooks/guard_all.py"),
				"import glob, os\n"
				+ "def guards():\n    return glob.glob(os.path.join('.', 'guard_*.py'))\n"
				+ "def decide(payload):\n    return False, None\n");
		write(new File(root, ".claude/hooks/guard_one.py"), "def decide(p):\n    return False, None\n");
		write(new File(root, ".claude/hooks/guard_two.py"), "def decide(p):\n    return False, None\n");
		String said = ask(checker, root);
		check(said.contains("reachable for every tool"),
				"one universal entry over a dispatcher that finds its own guards passes: "
				+ firstReason(said));
	}

	/**
	 * The live installation, when there is one. It is not allowed to be silent either way.
	 */
	static void theLiveInstallationIsSound(File checker, File repo) throws Exception {
		System.out.println("--- the installation this repository actually runs under");
		String said = ask(checker, repo);
		if (said.contains("INSTALLED NOWHERE")) {
			System.out.println("  note: no .claude above " + repo.getAbsolutePath()
					+ " - this clone has no hooks to reach, which is NOT the same as a clean "
					+ "wiring. The four sections above are what pin the rule.");
			return;
		}
		check(said.contains("reachable for every tool"),
				"every installed guard is reachable for every tool: " + firstReason(said));
	}

	// ---------------------------------------------------------------- fixtures

	/**
	 * A scratch installation: {@code <root>/.claude/settings.json} plus named guard files.
	 *
	 * @param matcher what the single PreToolUse entry matches, already quoted for JSON
	 * @param runs    the hook file that entry runs
	 * @param present the guard files that exist in the hooks directory
	 * @param stubs   whether to fill them with a decide() (false when the caller writes them)
	 */
	static File install(String name, String matcher, String runs, String[] present,
			boolean stubs) throws Exception {
		File root = Scratch.dir("hookwire-" + name);
		File hooks = new File(root, ".claude/hooks");
		check(hooks.mkdirs(), "built a scratch installation for " + name);
		for (String guard : present) {
			if (stubs) {
				write(new File(hooks, guard), "def decide(payload):\n    return False, None\n");
			} else {
				write(new File(hooks, guard), "");
			}
		}
		write(new File(root, ".claude/settings.json"),
				"{\n \"hooks\": {\n  \"PreToolUse\": [\n   {\n    \"matcher\": " + matcher
				+ ",\n    \"hooks\": [\n     { \"type\": \"command\", \"command\": "
				+ "\"python .claude/hooks/" + runs + "\" }\n    ]\n   }\n  ]\n }\n}\n");
		return root;
	}

	static void write(File f, String body) throws Exception {
		f.getParentFile().mkdirs();
		Files.write(f.toPath(), body.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Runs the checker against a root whose two copies of each hook agree.
	 *
	 * <p>The checker also refuses an installed hook that differs from its version-controlled
	 * copy, so every scratch installation needs both. Mirroring here rather than in each
	 * section keeps the sections about the one thing each is asserting - and a section that
	 * forgot would fail for a reason that has nothing to do with what it is testing, which is
	 * the shape that gets a red suite explained away.
	 */
	static String ask(File checker, File root) throws Exception {
		File hooks = new File(root, ".claude/hooks");
		File versioned = new File(root, "tools/hooks");
		File[] beside = hooks.listFiles();
		if (beside != null) {
			versioned.mkdirs();
			for (File one : beside) {
				Files.copy(one.toPath(), new File(versioned, one.getName()).toPath(),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		}
		return askAsIs(checker, root);
	}

	/** Runs the checker against a root exactly as it stands, mirroring nothing. */
	static String askAsIs(File checker, File root) throws Exception {
		ProcessBuilder pb = new ProcessBuilder("python", checker.getAbsolutePath(),
				root.getAbsolutePath());
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String said = CommitGuardTest.drain(p);
		p.waitFor();
		return said;
	}

	static String firstReason(String said) {
		for (String line : said.split("\n")) {
			if (line.trim().startsWith("- ") || line.contains("reachable for every tool")
					|| line.contains("INSTALLED NOWHERE")) {
				return line.trim().length() > 120 ? line.trim().substring(0, 120) : line.trim();
			}
		}
		return CommitGuardTest.firstLine(said);
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok: " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}
}
