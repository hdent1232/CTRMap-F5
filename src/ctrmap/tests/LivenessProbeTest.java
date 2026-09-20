package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * A QUERY THAT CANNOT READ ITS SUBJECT MUST NOT REPORT IT ABSENT.
 *
 * <p>WHAT IT COST, twice, measured. {@code os.kill(pid, 0)} is not a liveness probe on Windows:
 * python routes every signal through {@code OpenProcess(PROCESS_TERMINATE)}, so a process this
 * account may not terminate raises {@code [WinError 87]} and reads as DEAD. Checked against the
 * process table on 2026-09-08 it called three live processes dead, one of them the sweep runner
 * a guard existed to notice - and the function deciding whether to CLEAR a lock was one of its
 * callers.
 *
 * <p>That was found and closed in the project's python. The same mistake then arrived in a
 * shell command no python checker could see: a {@code Where-Object &#123; $_.CommandLine -match
 * ... &#125;} filter matched nothing, because {@code CommandLine} is EMPTY for a process this
 * account cannot open. Twelve live workers were reported gone, and acting on that discarded
 * 134 verdicts.
 *
 * <p>The project now answers the question in one place - {@code tools/hooks/liveness.py} - and
 * {@code tools/guard/liveness_check.py} refuses any other spelling in any {@code .py} or
 * {@code .ps1} it owns.
 *
 * <p>WHY THIS SUITE PLANTS ITS OWN DEFECTS. A checker that reports zero is the dangerous
 * result: twice here a scan returned 0 across 536 files because it read the wrong field. So
 * every section builds a file the checker MUST catch and a file it MUST NOT, and the live tree
 * is only checked after those pass. The first run of the checker over this very repository
 * reported seven defects and every one was its own docstring - the prose that describes the
 * trap, in the files that exist to refuse it - so the blanking that fixed that is planted here
 * in both directions too.
 *
 * <p>ORDER: needs no game, no dump and no display. Needs python on PATH and says so and skips
 * rather than passing.
 *
 * Usage: java ctrmap.tests.LivenessProbeTest [repo-root]
 */
public class LivenessProbeTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File repo = new File(args.length > 0 ? args[0] : ".");
		File checker = new File(repo, "tools/guard/liveness_check.py");
		if (!checker.isFile()) {
			System.out.println("  FAIL: no " + checker.getPath()
					+ " - nothing refuses a probe that reads a live process as gone");
			System.exit(1);
		}
		if (!CommitGuardTest.onPath("python")) {
			System.out.println("  skip: python is not on PATH - the checker is a python script, "
					+ "so this suite cannot run it here");
			System.out.println("ALL PASS");
			return;
		}

		theProbeThatCalledThreeLiveProcessesDeadIsRefused(checker);
		theShellFilterThatReportedTwelveWorkersGoneIsRefused(checker);
		aSecondSpellingOfTheQuestionIsRefused(checker);
		proseIsNotCode(checker);
		aCorrectProbeIsAllowed(checker);
		theProjectItselfIsClean(checker, repo);
		theOneAnswerSaysHowItKnows(repo);

		System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
		if (fails != 0) {
			System.exit(1);
		}
	}

	static void theProbeThatCalledThreeLiveProcessesDeadIsRefused(File checker) throws Exception {
		System.out.println("--- os.kill(pid, 0) on Windows");
		File root = tree("oskill", "probe.py",
				"import os\n"
				+ "def running(pid):\n"
				+ "    try:\n"
				+ "        os.kill(pid, 0)\n"
				+ "    except OSError:\n"
				+ "        return False\n"
				+ "    return True\n");
		String said = ask(checker, root);
		check(said.contains("os.kill(pid, 0)") && said.contains("probe.py"),
				"a probe with no os.name branch is refused: " + firstReason(said));
	}

	static void theShellFilterThatReportedTwelveWorkersGoneIsRefused(File checker)
			throws Exception {
		System.out.println("--- a CommandLine filter deciding a process is absent");
		File root = tree("cmdline", "sweep.ps1",
				"$live = Get-CimInstance Win32_Process |\n"
				+ "  Where-Object { $_.CommandLine -match 'run_named' }\n"
				+ "if (-not $live) { Write-Host 'nothing running' }\n");
		String said = ask(checker, root);
		check(said.contains("CommandLine") && said.contains("sweep.ps1"),
				"the filter is refused: " + firstReason(said));
	}

	static void aSecondSpellingOfTheQuestionIsRefused(File checker) throws Exception {
		System.out.println("--- a second spelling of one question");
		File root = tree("second", "probe.py",
				"import ctypes\n"
				+ "def running(pid):\n"
				+ "    return bool(ctypes.windll.kernel32.OpenProcess(0x1000, False, pid))\n");
		String said = ask(checker, root);
		check(said.contains("OpenProcess") && said.contains("probe.py"),
				"a hand-rolled Windows probe is refused: " + firstReason(said));

		root = tree("fourth", "probe.py",
				"import subprocess\n"
				+ "def running(pid):\n"
				+ "    out = subprocess.check_output(['tasklist', '/FI', 'PID eq %d' % pid])\n"
				+ "    return str(pid) in str(out)\n");
		said = ask(checker, root);
		check(said.contains("tasklist"),
				"and so is a tasklist probe, which is not wrong - it is a second answer: "
				+ firstReason(said));
	}

	/**
	 * The trap the checker fell into on its first run, planted so it cannot come back.
	 *
	 * <p>Seven findings, every one of them the checker's own explanation of the defect. A check
	 * that matches prose cannot tell a warning from a bug, and one that over-blanks hides a
	 * real call - so both directions are here.
	 */
	static void proseIsNotCode(File checker) throws Exception {
		System.out.println("--- prose about the trap is not the trap");
		File root = tree("prose", "notes.py",
				"\"\"\"Never write os.kill(pid, 0) as a probe, and never filter on\n"
				+ "CommandLine -match to decide a process is gone.\n"
				+ "\"\"\"\n"
				+ "#: not OpenProcess( either, and not tasklist /FI 'PID eq 1'\n"
				+ "def running(pid):\n"
				+ "    import liveness\n"
				+ "    return liveness.alive(pid)[0]\n");
		String said = ask(checker, root);
		check(said.contains("asked correctly"),
				"a file that only DESCRIBES the traps is clean: " + firstReason(said));

		File shell = tree("prose-ps", "notes.ps1",
				"# Do not use $_.CommandLine -match here; it is empty for a process we\n"
				+ "# cannot open. Ask liveness.alive instead.\n"
				+ "Write-Host 'nothing to see'\n");
		said = ask(checker, shell);
		check(said.contains("asked correctly"),
				"and so is a PowerShell comment about it: " + firstReason(said));

		//AND THE OTHER DIRECTION: blanking must not hide a real call on the same line
		File both = tree("prose-and-code", "mixed.py",
				"import os\n"
				+ "#: os.kill(pid, 0) is wrong, which is why this line is a comment\n"
				+ "def running(pid):\n"
				+ "    os.kill(pid, 0)   # and THIS one is not a comment\n"
				+ "    return True\n");
		said = ask(checker, both);
		check(said.contains("os.kill(pid, 0)") && said.contains("mixed.py"),
				"a real call beside prose about it is still found: " + firstReason(said));
	}

	/** The negative control: the correct probe must not be refused. */
	static void aCorrectProbeIsAllowed(File checker) throws Exception {
		System.out.println("--- a probe that branches on the platform");
		File root = tree("correct", "probe.py",
				"import os\n"
				+ "def running(pid):\n"
				+ "    if os.name == 'nt':\n"
				+ "        import liveness\n"
				+ "        return liveness.alive(pid)[0]\n"
				+ "    try:\n"
				+ "        os.kill(pid, 0)\n"
				+ "    except ProcessLookupError:\n"
				+ "        return False\n"
				+ "    except OSError:\n"
				+ "        return True\n"
				+ "    return True\n");
		String said = ask(checker, root);
		check(said.contains("asked correctly"),
				"a probe that branches on os.name is allowed: " + firstReason(said));
	}

	/** Only now is the project's own answer worth reading. */
	static void theProjectItselfIsClean(File checker, File repo) throws Exception {
		System.out.println("--- and this project");
		String said = ask(checker, repo);
		check(said.contains("asked correctly"),
				"the liveness question is asked in one place: " + firstReason(said));
		check(!said.contains("(0 files read)"),
				"and it read something - a scan of nothing cannot report a clean result");
	}

	/**
	 * The one answer must say HOW it knows, not merely yes or no.
	 *
	 * <p>That is the whole correction: a caller with a bare boolean cannot tell "the operating
	 * system says there is no such process" from "I was not allowed to look", and the second
	 * one silently became the first here twice.
	 */
	static void theOneAnswerSaysHowItKnows(File repo) throws Exception {
		System.out.println("--- and it says how it knows");
		File answer = new File(repo, "tools/hooks/liveness.py");
		check(answer.isFile(), "the one answer lives at " + answer.getPath());
		String body = new String(Files.readAllBytes(answer.toPath()), StandardCharsets.UTF_8);
		check(body.contains("return True, (") || body.contains("return True,"),
				"it returns a reason beside the verdict");
		check(body.contains("permission answer") && body.contains("not an absence"),
				"and it says in so many words that permission denied is not absence");
	}

	// ---------------------------------------------------------------- fixtures

	static File tree(String name, String file, String body) throws Exception {
		File root = Scratch.dir("liveness-" + name);
		File target = new File(root, file);
		target.getParentFile().mkdirs();
		Files.write(target.toPath(), body.getBytes(StandardCharsets.UTF_8));
		return root;
	}

	static String ask(File checker, File root) throws Exception {
		ProcessBuilder pb = new ProcessBuilder("python", checker.getAbsolutePath(),
				root.getAbsolutePath());
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String said = CommitGuardTest.drain(p);
		p.waitFor();
		if (said.contains("Traceback")) {
			System.out.println("  FAIL: the checker crashed - " + firstReason(said));
			fails++;
		}
		return said;
	}

	static String firstReason(String said) {
		for (String line : said.split("\n")) {
			String t = line.trim();
			if (t.startsWith("- ") || t.contains("asked correctly")) {
				return t.length() > 130 ? t.substring(0, 130) : t;
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
