package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The last three rules in CLAUDE.md that had no mechanism.
 *
 * <p>On 2026-09-20 sixteen of the twenty-eight rules in capitals had nothing behind them. Three
 * survived the first pass because each looked like a judgement rather than a measurement. Each
 * turned out to have a shape:
 *
 * <ul>
 * <li>COUNT WHAT YOU EXPECT BEFORE RUNNING THE CHANGE - a rewrite that reaches a file without
 *     counting its matches first. The check found one on its first run, and it was real:
 *     {@code autoplant.try_one} wrote a substitution straight out while {@code replant.py} -
 *     the same function one directory over - had refused an ambiguous find since it was
 *     written. A find matching twice plants the defect in two places and records it as one.</li>
 * <li>A STARTLING MAGNITUDE IS A BUG REPORT - a recorded number that moved further than its
 *     allowance, or that did not move AT ALL across a change meant to move it. The second case
 *     happened here hours earlier: a ratchet re-measured to exactly 103 before and after,
 *     because the new predicate had a 0x08 in it. The true number was 64.</li>
 * <li>BUILD GENERAL SYSTEMS, NOT THE USER'S EXAMPLE - a game's identity hardcoded outside the
 *     gamedef seam. Two sites, named and ratcheted; a third is refused.</li>
 * </ul>
 *
 * <p>EVERY SECTION ASSERTS BOTH DIRECTIONS, in a scratch tree it builds itself, so none of them
 * can pass by finding nothing on a clone. The first version of the count-first check matched
 * any {@code .replace(} at all and reported 36 findings, 35 of them {@code replace(os.sep,
 * "/")} - a checker that cries wolf 35 times out of 36 is one whose output nobody reads, so
 * the harmless forms are planted here too.
 *
 * <p>ORDER: needs no game, no dump and no display. Needs python on PATH and says so and skips
 * rather than passing.
 *
 * Usage: java ctrmap.tests.RuleRefusalsTest [repo-root]
 */
public class RuleRefusalsTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File repo = new File(args.length > 0 ? args[0] : ".");
		for (String tool : new String[]{"count_first.py", "magnitude.py", "game_identity.py",
				"unclosed.py", "lf_checkout.py", "native_stderr.py"}) {
			File f = new File(repo, "tools/guard/" + tool);
			if (!f.isFile()) {
				System.out.println("  FAIL: no " + f.getPath()
						+ " - the rule it enforces has nothing behind it again");
				System.exit(1);
			}
		}
		if (!CommitGuardTest.onPath("python")) {
			Skip.suite("python is not on PATH - these are python checkers, "
					+ "so this suite cannot run them here");
			return;
		}

		aRewriteThatReachesAFileMustCountFirst(repo);
		aHarmlessRewriteIsNotAskedToCount(repo);
		theProjectCountsBeforeItRewrites(repo);
		aStartlingMoveIsRefused(repo);
		aNumberThatDidNotMoveAtAllIsRefused(repo);
		aGameIdentityOutsideTheSeamIsRefused(repo);
		anUnheldOpenIsRefused(repo);
		theProjectLeavesNoHandleOpen(repo);
		aCheckoutWithLineEndsItsAttributesDoNotGiveIsSaid(repo);
		thisCheckoutHasTheLineEndsItsAttributesGive(repo);
		everyToolHereRefusesAWordItDoesNotRead(repo);
		//ASKED BY RUNNING IT, not by `--version`: powershell rejects that flag, and onPath read the refusal as "not on PATH"
		if (runs("powershell", "-NoProfile", "-Command", "exit 0")) {
			aNativeCommandOutsideInvokeNativeIsRefused(repo);
			thisTreeRunsEveryNativeCommandThroughInvokeNative(repo);
			invokeNativeJudgesByTheExitCode(repo);
			theBuildIsSignedOnlyOnceItsGatesHaveJudgedIt(repo);
		} else {
			Skip.section("powershell is not on PATH - the .ps1 rule, Invoke-Native and build.ps1 cannot be asked here");
		}

		System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
		if (fails != 0) {
			System.exit(1);
		}
	}

	// ---------------------------------------- COUNT WHAT YOU EXPECT

	static void aRewriteThatReachesAFileMustCountFirst(File repo) throws Exception {
		System.out.println("--- a rewrite that reaches a file without counting");
		File root = python("edit.py",
				"def go(path, find, repl):\n"
				+ "    text = open(path).read()\n"
				+ "    write_text(path, text.replace(find, repl))\n");
		String said = ask(repo, "count_first.py", root);
		check(said.contains("edit.py") && said.contains("without counting"),
				"the uncounted rewrite is named: " + firstReason(said));

		//AND THE ASSIGN-THEN-WRITE FORM, which is the same defect spelled over two lines
		File split = python("edit.py",
				"def go(path, find, repl):\n"
				+ "    text = open(path).read()\n"
				+ "    out = text.replace(find, repl)\n"
				+ "    write_text(path, out)\n");
		said = ask(repo, "count_first.py", split);
		check(said.contains("without counting"),
				"and so is the same thing over two lines: " + firstReason(said));

		//THE NEGATIVE HALF: counting first is what it is asking for
		File counted = python("edit.py",
				"def go(path, find, repl):\n"
				+ "    text = open(path).read()\n"
				+ "    assert text.count(find) == 1\n"
				+ "    write_text(path, text.replace(find, repl))\n");
		said = ask(repo, "count_first.py", counted);
		check(said.contains("counts its matches first"),
				"while a rewrite that counts first is allowed: " + firstReason(said));

		//AN EXPLANATION IS NOT DISTANCE, AND A COMMENT IS NOT A COUNT. Measured on a clean
		//checkout: replant.py counts on line 254, writes on line 270, and the eight lines
		//between explain the lock order - a real count pushed out of a window of raw lines.
		StringBuilder explained = new StringBuilder("def go(path, find, repl):\n"
				+ "    text = open(path).read()\n"
				+ "    assert text.count(find) == 1\n");
		for (int k = 0; k < 14; k++) {
			explained.append("    # why the order matters, line ").append(k).append("\n");
		}
		explained.append("    write_text(path, text.replace(find, repl))\n");
		said = ask(repo, "count_first.py", python("edit.py", explained.toString()));
		check(said.contains("counts its matches first"),
				"COMMENTED: an explanation between the count and the write does not move the "
				+ "count out of reach: " + firstReason(said));
		File described = python("edit.py",
				"def go(path, find, repl):\n"
				+ "    text = open(path).read()\n"
				+ "    # assert text.count(find) == 1\n"
				+ "    write_text(path, text.replace(find, repl))\n");
		said = ask(repo, "count_first.py", described);
		check(said.contains("without counting"),
				"COMMENTED: while a count that is only a comment is no count: " + firstReason(said));
	}

	/**
	 * A checker that cries wolf is one whose output nobody reads.
	 *
	 * <p>Measured: the first version of this check reported 36 findings and 35 were
	 * {@code replace(os.sep, "/")} - path spelling, not an edit.
	 */
	static void aHarmlessRewriteIsNotAskedToCount(File repo) throws Exception {
		System.out.println("--- and a rewrite that only respells a path is left alone");
		File root = python("paths.py",
				"import os\n"
				+ "def go(path, text):\n"
				+ "    write_text(path, text.replace(os.sep, '/'))\n");
		String said = ask(repo, "count_first.py", root);
		check(said.contains("counts its matches first"),
				"os.sep normalisation is not an edit: " + firstReason(said));

		File endings = python("endings.py",
				"def go(path, text):\n"
				+ "    write_text(path, text.replace(chr(13) + chr(10), chr(10)))\n");
		said = ask(repo, "count_first.py", endings);
		check(said.contains("counts its matches first"),
				"and neither is line-ending normalisation: " + firstReason(said));

		File readonly = python("read.py",
				"def go(text):\n"
				+ "    return text.replace('a', 'b')\n");
		said = ask(repo, "count_first.py", readonly);
		check(said.contains("counts its matches first"),
				"and neither is a rewrite that never reaches a file: " + firstReason(said));

		//A JUDGE IS NOT A WRITER. The bundle's own test hands a rewritten string to
		//ask_write(root, rel, before, after), which judges a write and writes nothing, and the
		//checker refused it because the name CONTAINED "write" (2026-10-07).
		File judged = python("judge.py",
				"def go(root, rel, text):\n"
				+ "    after = text.replace('return 1;', 'return 1 ')\n"
				+ "    return ask_write(root, rel, text, after)\n");
		said = ask(repo, "count_first.py", judged);
		check(said.contains("counts its matches first"),
				"and neither is a string handed to a judge whose name merely contains write: "
				+ firstReason(said));
		//...while a writer that only LOOKS private is still a writer: narrowing the pattern to
		//names that begin with "write" must not stop it seeing _write, the one such writer
		//the project has
		File hidden = python("private.py",
				"def go(path, text):\n"
				+ "    _write(path, text.replace('a', 'b'))\n");
		said = ask(repo, "count_first.py", hidden);
		check(said.contains("without counting"),
				"but a private _write handed an uncounted rewrite is refused: " + firstReason(said));
	}

	/** The live answer, which is the one that found a real defect. */
	static void theProjectCountsBeforeItRewrites(File repo) throws Exception {
		System.out.println("--- and this project's own python");
		String said = ask(repo, "count_first.py", repo);
		check(said.contains("counts its matches first"),
				"every rewrite that reaches a file counts first: " + firstReason(said));
		check(!said.contains("(0 files read)"),
				"and it read something - a scan of nothing cannot report a clean result");
	}

	// ---------------------------------------- A STARTLING MAGNITUDE

	static void aStartlingMoveIsRefused(File repo) throws Exception {
		System.out.println("--- a number that moved further than its allowance");
		File guard = new File(repo, "tools/guard/magnitude.py");
		check(guard.isFile(), "the guard is installed at " + guard.getPath());
		check(said(repo, "startling(100, 400, False)").contains("True"),
				"100 -> 400 is startling");
		check(said(repo, "startling(100, 110, False)").contains("False"),
				"while 100 -> 110 is not - an allowance that fires on ordinary drift is one "
				+ "that gets widened");
		check(said(repo, "startling(2, 3, False)").contains("False"),
				"and neither is 2 -> 3, which is 50% and means nothing");
		check(said(repo, "startling(None, 400, False)").contains("False"),
				"and a first reading has nothing to be startling against");
	}

	/**
	 * The case that was paid for on the day this was written.
	 *
	 * <p>A ratchet re-measured after a change meant to loosen its predicate came back
	 * <b>103</b> - exactly what it had been. The new pattern carried a 0x08 and matched only
	 * digits. The true number was <b>64</b>. The same number twice across a change that should
	 * have moved it is a bug report, and nothing was comparing.
	 */
	static void aNumberThatDidNotMoveAtAllIsRefused(File repo) throws Exception {
		System.out.println("--- and a number that did not move at all");
		check(said(repo, "startling(103, 103, True)").contains("True"),
				"103 -> 103 across a change meant to move it is refused");
		check(said(repo, "startling(103, 103, False)").contains("False"),
				"while an unchanged number nobody expected to move is not");
		check(said(repo, "startling(103, 64, True)").contains("True"),
				"and the move to the TRUE number is startling too - which is the point: it "
				+ "asks, it does not decide");
	}

	// ---------------------------------------- BUILD GENERAL SYSTEMS

	static void aGameIdentityOutsideTheSeamIsRefused(File repo) throws Exception {
		System.out.println("--- a game's identity hardcoded outside the gamedef seam");
		File root = java("src/ctrmap/formats/Thing.java",
				"package ctrmap.formats;\n"
				+ "public class Thing { static final String ID = \"000400000011C400\"; }\n");
		String said = ask(repo, "game_identity.py", root);
		check(said.contains("Thing.java") && said.contains("BUILD GENERAL SYSTEMS"),
				"a title id in a format class is refused: " + firstReason(said));

		//THE NEGATIVE HALF: the seam is where a game says who it is
		File seam = java("src/ctrmap/gamedef/OrasProfile.java",
				"package ctrmap.gamedef;\n"
				+ "public class OrasProfile { String id() { return \"000400000011C400\"; } }\n");
		said = ask(repo, "game_identity.py", seam);
		check(said.contains("none new"),
				"while the same literal inside gamedef is the seam working: "
				+ firstReason(said));

		//and the live tree: two known sites, no third
		said = ask(repo, "game_identity.py", repo);
		check(said.contains("none new"),
				"and the live tree has no new site: " + firstReason(said));
	}

	// ---------------------------------------- A FILE OPENED IS A FILE CLOSED

	/**
	 * {@code tools/guard/unclosed.py}, which the bundle's hooks also ask before every write.
	 *
	 * <p>Measured 2026-09-26: 70 opens under tools/ and .claude/ whose handle nothing held, in
	 * 23 files. On Windows a handle left to the collector makes the next write or delete of that
	 * path fail - errno 22, a sharing violation - at a moment nothing chose.
	 */
	static void anUnheldOpenIsRefused(File repo) throws Exception {
		System.out.println("--- an open whose handle nothing holds");
		String said = ask(repo, "unclosed.py", tool("x.py",
				"import io, json\n"
				+ "def go(p):\n"
				+ "    return json.load(io.open(p))['a']\n"));
		check(said.contains("x.py:3") && said.contains("used inline"),
				"an open used inline is refused: " + firstReason(said));

		//THE CHEAPEST WAY PAST: assign the handle, read it, never close it
		said = ask(repo, "unclosed.py", tool("x.py",
				"import io\n"
				+ "def go(p):\n"
				+ "    handle = io.open(p)\n"
				+ "    return handle.read()\n"));
		check(said.contains("x.py:3") && said.contains("nothing in its scope closes"),
				"and so is a handle assigned and never closed: " + firstReason(said));

		//THE NEGATIVE HALF: a with, a factory whose caller holds it, a name the scope closes
		said = ask(repo, "unclosed.py", tool("x.py",
				"import io\n"
				+ "def go(p):\n"
				+ "    with io.open(p) as handle:\n"
				+ "        return handle.read()\n"
				+ "def opener(p):\n"
				+ "    return io.open(p)\n"
				+ "def closed(p):\n"
				+ "    handle = io.open(p)\n"
				+ "    try:\n"
				+ "        return handle.read()\n"
				+ "    finally:\n"
				+ "        handle.close()\n"));
		check(said.contains("every open under"),
				"while a held handle is left alone: " + firstReason(said));
	}

	/** The live tree, which held 70 of them the day this was written. */
	static void theProjectLeavesNoHandleOpen(File repo) throws Exception {
		System.out.println("--- and this project's own python");
		String said = ask(repo, "unclosed.py", repo);
		check(said.contains("every open under"),
				"every open under tools/ and .claude/ is held: " + firstReason(said));
	}

	// ---------------------------------------- LINE ENDINGS ARE THE INDEX'S, ON EVERY CHECKOUT

	/**
	 * {@code tools/guard/lf_checkout.py}, asked after every call that changes the tree.
	 *
	 * <p>Measured 2026-10-09: every text file in the index is LF, and this Windows checkout held 622 of
	 * them CRLF and 89 LF. Six suites that read a tracked file and compared it with LF text failed for
	 * it - pass or fail decided by which group a file happened to be in.
	 */
	static void aCheckoutWithLineEndsItsAttributesDoNotGiveIsSaid(File repo) throws Exception {
		System.out.println("--- a tracked file checked out with line ends .gitattributes does not give");
		File root = gitTree("lf", "* text=auto eol=lf\n", "one\r\ntwo\r\n");
		String said = ask(repo, "lf_checkout.py", root);
		check(said.contains("a.txt: checked out crlf, and .gitattributes gives it lf"),
				"a CRLF copy of a file the attributes give LF is named: " + firstReason(said));
		said = askWith(repo, "lf_checkout.py", "--fix", root.getAbsolutePath());
		check(said.contains("converted 1 file") && said.contains("every one of 2 tracked file")
				&& new String(Files.readAllBytes(new File(root, "a.txt").toPath()), StandardCharsets.UTF_8).equals("one\ntwo\n"),
				"and --fix rewrites its line ends, and only those: " + firstReason(said));

		//A TREE WITH NO RULE IS NOT A CLEAN TREE: attributes that lost the line leave nothing fixed
		root = gitTree("none", "", "one\ntwo\n");
		said = ask(repo, "lf_checkout.py", root);
		check(said.contains("a.txt: a text file .gitattributes gives no line ending"),
				"a text file the attributes give no ending is said, not passed: " + firstReason(said));

		//THE NEGATIVE HALF: a file already as the attributes give it
		root = gitTree("clean", "* text=auto eol=lf\n", "one\ntwo\n");
		said = ask(repo, "lf_checkout.py", root);
		check(said.contains("every one of 2 tracked file"), "while one already LF is left alone: " + firstReason(said));
	}

	/** The live checkout, which held 623 of them the day this was written. */
	static void thisCheckoutHasTheLineEndsItsAttributesGive(File repo) throws Exception {
		System.out.println("--- and this checkout");
		String said = ask(repo, "lf_checkout.py", repo);
		check(said.contains("every one of") && said.contains("is checked out as .gitattributes says"),
				"every tracked text file here has the line ends .gitattributes gives it: " + firstReason(said));
	}

	// ---------------------------------------- A WORD A TOOL DOES NOT READ IS REFUSED, NEVER IGNORED

	/**
	 * Every tool under tools/ that reads its command line, RUN with a word it does not read, refuses it
	 * in words and exits 2 - asked of the running tool, not only of its text. PAID FOR 2026-10-09:
	 * `replant.py --help` drove all 1,363 plants, about 7.5 hours with the tree unwritable. On
	 * 2026-10-10 the bundle's cli_words found 23 of this project's tools dropping words they did not
	 * know; each now asks cli_words.refused first. The tools are found by walking, as cli_words finds
	 * them, and a scratch tool that ignores its words is the control.
	 */
	static void everyToolHereRefusesAWordItDoesNotRead(File repo) throws Exception {
		System.out.println("--- every tool here refuses a word it does not read, run");
		String word = "--ctrmap-no-such-word";
		File scratch = Scratch.dir("rulerefusal-words");
		File ignoring = new File(scratch, "ignoring.py");
		Files.write(ignoring.toPath(), "import sys\nprint('ran with', sys.argv[1:])\n".getBytes(StandardCharsets.UTF_8));
		String said = runTool(ignoring, word);
		check(said.startsWith("0 ") && !said.contains("REFUSING"), "control: a tool that drops its words runs on past one: " + said.trim());

		java.util.List<File> tools = new java.util.ArrayList<>();
		java.util.Deque<File> walk = new java.util.ArrayDeque<>();
		walk.add(new File(repo, "tools"));
		while (!walk.isEmpty()) {
			File[] here = walk.pop().listFiles();
			for (File f : here == null ? new File[0] : here) {
				if (f.isDirectory() && !f.getName().equals("__pycache__")) {
					walk.add(f);
				} else if (f.getName().endsWith(".py") && !f.getName().equals("cli_words.py")
						&& new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).contains("sys.argv")) {
					tools.add(f);
				}
			}
		}
		check(tools.size() >= 23, "control: the walk finds the tools that read their command line (" + tools.size() + ")");
		java.util.List<String> ranOn = new java.util.ArrayList<>();
		for (File t : tools) {
			String out = runTool(t, word);
			if (!(out.startsWith("2 ") && out.contains("REFUSING") && out.contains(word))) {
				ranOn.add(t.getName() + " (" + CommitGuardTest.firstLine(out) + ")");
			}
		}
		check(ranOn.isEmpty(), "every one of " + tools.size() + " refuses " + word + " in words, exit 2, before acting: " + ranOn);
	}

	/** "exit output" of a python tool run with one word, its stdin closed, held to two minutes. */
	static String runTool(File tool, String word) throws Exception {
		ProcessBuilder pb = new ProcessBuilder("python", "-B", tool.getAbsolutePath(), word);
		pb.directory(tool.getParentFile());
		pb.redirectErrorStream(true);
		Process p = pb.start();
		p.getOutputStream().close();
		java.util.concurrent.Future<String> out = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
			Thread th = new Thread(r);
			th.setDaemon(true);
			return th;
		}).submit(() -> CommitGuardTest.drain(p));
		if (!p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
			p.destroyForcibly();
			return "TIMEOUT ran past two minutes";
		}
		return p.exitValue() + " " + out.get(30, java.util.concurrent.TimeUnit.SECONDS);
	}

	// ---------------------------------------- A NATIVE COMMAND'S EXIT CODE IS ITS VERDICT, NEVER ITS STDERR

	/**
	 * {@code tools/guard/native_stderr.py}, asked at the write of a .ps1.
	 *
	 * <p>Under Windows PowerShell 5.1 a native command's redirected stderr is an ErrorRecord, and
	 * ErrorActionPreference Stop makes it terminating. Paid for seven times across this tree's scripts,
	 * each fixed where it bit; the seventh, on 2026-10-10, was a structure gate's Swing warning ending
	 * build.ps1 between its stamp and the gates' verdict. Three ways to run a program are planted - the
	 * call operator, a program on PATH by name, a name nothing here defines - and the harmless forms.
	 */
	static void aNativeCommandOutsideInvokeNativeIsRefused(File repo) throws Exception {
		System.out.println("--- a .ps1 that runs a program outside Invoke-Native");
		File root = Scratch.dir("rulerefusal-ps1");
		Files.copy(new File(repo, "native.ps1").toPath(), new File(root, "native.ps1").toPath());
		Files.write(new File(root, "bare.ps1").toPath(), ("$ErrorActionPreference = 'Stop'\n"
				+ "& \"$env:windir\\system32\\cmd.exe\" /c exit 0\n"
				+ "git status\n"
				+ "frobnicate-ctrmap-tool --x\n").getBytes(StandardCharsets.UTF_8));
		Files.write(new File(root, "held.ps1").toPath(), (". (Join-Path $PSScriptRoot 'native.ps1')\n"
				+ "Invoke-Native { & cmd.exe /c exit 0 }\n"
				+ "Write-Host 'a cmdlet'\n"
				+ "& { Write-Host 'a block' }\n"
				+ "& \"$PSScriptRoot\\other.ps1\"\n"
				+ "function Do-Thing { }\n"
				+ "Do-Thing\n").getBytes(StandardCharsets.UTF_8));
		String said = ask(repo, "native_stderr.py", root);
		check(said.contains("bare.ps1:2 ") && said.contains("the call operator runs it"),
				"a program run by the call operator is named: " + firstReason(said));
		check(said.contains("bare.ps1:3 ") && said.contains("git is a program on PATH"),
				"a program run by name is named: " + firstReason(said));
		check(said.contains("bare.ps1:4 ") && said.contains("taken for a program"),
				"a name nothing here defines is taken for a program, never for safe: " + firstReason(said));
		check(!said.contains("held.ps1"), "while Invoke-Native, a cmdlet, a block, a script and a function pass: " + firstReason(said));

		//WHAT IT COULD NOT READ IS UNKNOWN, NEVER CLEAN: a script PowerShell cannot parse, and no PowerShell to ask
		File broken = Scratch.dir("rulerefusal-ps1-broken");
		Files.write(new File(broken, "broken.ps1").toPath(), "function {\n& git status\n".getBytes(StandardCharsets.UTF_8));
		said = ask(repo, "native_stderr.py", broken);
		check(said.contains("UNKNOWN: PowerShell cannot parse it"), "a script that does not parse is UNKNOWN, not clean: " + CommitGuardTest.firstLine(said));
		String python = CommitGuardTest.firstLine(askPython("import sys; print(sys.executable)"));
		ProcessBuilder pb = new ProcessBuilder(python, new File(repo, "tools/guard/native_stderr.py").getAbsolutePath(), root.getAbsolutePath());
		pb.environment().put("PATH", new File(python).getParent());
		pb.redirectErrorStream(true);
		Process p = pb.start();
		said = CommitGuardTest.drain(p);
		int code = p.waitFor();
		check(code == 2 && said.contains("UNKNOWN: PowerShell could not be asked"),
				"with no PowerShell to ask, which commands run unguarded is UNKNOWN (exit " + code + "): " + CommitGuardTest.firstLine(said));
	}

	/** What python prints for one statement. */
	static String askPython(String statement) throws Exception {
		ProcessBuilder pb = new ProcessBuilder("python", "-c", statement);
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String out = CommitGuardTest.drain(p);
		p.waitFor();
		return out;
	}

	/** This tree's five scripts, which held nine unguarded native commands the day this was written. */
	static void thisTreeRunsEveryNativeCommandThroughInvokeNative(File repo) throws Exception {
		System.out.println("--- and this tree's .ps1 files");
		String said = ask(repo, "native_stderr.py", repo);
		check(said.contains("every native command in the") && said.contains("runs through Invoke-Native"),
				"every native command in this tree's scripts runs through Invoke-Native: " + firstReason(said));
	}

	/**
	 * What Invoke-Native promises, asked of PowerShell: under Stop, a program that writes to stderr and
	 * exits 3 does not end the script, its exit code survives, its stderr comes back as output, the
	 * caller's variables reach the block, the preference is put back - and a block that ran no program
	 * answers -1, never the 0 left by whatever ran before. The control is the same call made bare.
	 */
	static void invokeNativeJudgesByTheExitCode(File repo) throws Exception {
		System.out.println("--- Invoke-Native judges a program by its exit code");
		File dir = Scratch.dir("invoke-native");
		File probe = new File(dir, "probe.ps1");
		Files.write(probe.toPath(), ("param([string]$Root)\n"
				+ "$ErrorActionPreference = 'Stop'\n"
				+ ". (Join-Path $Root 'native.ps1')\n"
				+ "$g = @{ c = 'from-the-caller' }\n"
				+ "$out = Invoke-Native { & cmd.exe /c \"echo $($g.c) & echo to-stderr 1>&2 & exit /b 3\" 2>&1 }\n"
				+ "'exit=' + $LASTEXITCODE\n"
				+ "'lines=' + ((@($out) | ForEach-Object { \"$_\".Trim() }) -join '|')\n"
				+ "'preference-after=' + $ErrorActionPreference\n"
				+ "Invoke-Native { & cmd.exe /c 'exit /b 0' }\n"
				+ "Invoke-Native { if ($false) { & cmd.exe /c 'exit /b 0' } }\n"
				+ "'never-ran=' + $LASTEXITCODE\n"
				+ "try { $bare = & cmd.exe /c 'echo to-stderr 1>&2 & exit /b 0' 2>&1; 'control: bare call survived' } catch { 'control: bare call TERMINATED' }\n"
				+ "'end reached'\n").getBytes(StandardCharsets.UTF_8));
		ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
				"-File", probe.getAbsolutePath(), "-Root", repo.getAbsolutePath());
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String said = CommitGuardTest.drain(p);
		p.waitFor();
		check(said.contains("control: bare call TERMINATED"), "control: under Stop, a bare program's stderr ends the script: " + CommitGuardTest.firstLine(said));
		check(said.contains("exit=3") && said.contains("end reached"),
				"through Invoke-Native it does not, and its exit code is the verdict: " + said.replace('\n', ' ').trim());
		check(said.contains("lines=from-the-caller to-stderr") || said.contains("lines=from-the-caller|to-stderr"),
				"its stderr comes back as output, and the caller's variable reached the block: " + said.replace('\n', ' ').trim());
		check(said.contains("preference-after=Stop"), "and the preference is put back");
		check(said.contains("never-ran=-1"), "a block that ran no program answers -1, not the 0 before it: " + said.replace('\n', ' ').trim());
	}

	/**
	 * build.ps1 on a scratch tree whose two structure gates are stand-ins. A gate that writes a warning to
	 * stderr and exits 0 - what DialogSeamTest's Swing table did on 2026-10-10 - lets the build through,
	 * signed; a gate that refuses removes build\classes. Each stand-in also refuses if the stamp is
	 * already there when it runs: the stamp is the build's word that the gates judged it, so it is
	 * written after them. And package.ps1 packages no build the gates refused.
	 */
	static void theBuildIsSignedOnlyOnceItsGatesHaveJudgedIt(File repo) throws Exception {
		System.out.println("--- build.ps1 signs a build only once its gates have judged it, and package.ps1 ships no refused one");
		File root = Scratch.dir("build-gates");
		for (String f : new String[]{"build.ps1", "stamp.ps1", "native.ps1", "package.ps1"}) {
			Files.copy(new File(repo, f).toPath(), new File(root, f).toPath());
		}
		File resources = new File(root, "src/ctrmap/resources");
		resources.mkdirs();
		Files.write(new File(resources, "version.properties").toPath(), "version=0.0.1\n".getBytes(StandardCharsets.UTF_8));
		for (String gate : new String[]{"DuplicateWorkTest", "DialogSeamTest"}) {
			File src = new File(root, "src/ctrmap/tests/" + gate + ".java");
			src.getParentFile().mkdirs();
			Files.write(src.toPath(), ("package ctrmap.tests;\n"
					+ "public class " + gate + " {\n"
					+ "\tpublic static void main(String[] a) {\n"
					+ "\t\tif (new java.io.File(\"build/classes/.built-by-build-ps1\").exists()) {\n"
					+ "\t\t\tSystem.out.println(\"FAIL: the build was signed before its gates judged it\");\n"
					+ "\t\t\tSystem.exit(1);\n"
					+ "\t\t}\n"
					+ "\t\tif (new java.io.File(\"gate-refuses\").exists()) {\n"
					+ "\t\t\tSystem.out.println(\"FAIL: the planted refusal\");\n"
					+ "\t\t\tSystem.exit(1);\n"
					+ "\t\t}\n"
					+ "\t\tif (new java.io.File(\"gate-crashes\").exists()) {\n"
					+ "\t\t\tSystem.out.println(\"  ok: and a blank reason is refused\");\n"
					+ "\t\t\tthrow new IllegalStateException(\"the planted crash\");\n"
					+ "\t\t}\n"
					+ "\t\tSystem.err.println(\"WARNING: row index is bigger than sorter's row count. Most likely this is a wrong sorter usage.\");\n"
					+ "\t\tSystem.out.println(\"ALL PASS\");\n"
					+ "\t}\n"
					+ "}\n").getBytes(StandardCharsets.UTF_8));
		}
		String said = powershellScript(root, "build.ps1");
		File stamp = new File(root, "build/classes/.built-by-build-ps1");
		check(said.contains("Build OK") && stamp.isFile(),
				"gates that warn on stderr and pass let the build through, signed: " + lastLines(said));
		Files.write(new File(root, "gate-refuses").toPath(), new byte[0]);
		said = powershellScript(root, "build.ps1");
		check(said.contains("BUILD REFUSED") && said.contains("the planted refusal") && !new File(root, "build/classes").exists(),
				"a gate that refuses removes build\\classes: " + lastLines(said));
		said = powershellScript(root, "package.ps1");
		check(said.contains("build.ps1 refused (exit 1) - nothing was packaged"),
				"and package.ps1 packages nothing after it: " + lastLines(said));
		//measured 2026-10-14: DialogSeamTest exited non-zero under eight sweep JVMs with no FAIL line, and the
		//refusal showed only an ok line that said "refused" - the crash was filtered out, and a mutation sweep
		//reads BUILD REFUSED as a kill by the gate's suite
		Files.delete(new File(root, "gate-refuses").toPath());
		Files.write(new File(root, "gate-crashes").toPath(), new byte[0]);
		said = powershellScript(root, "build.ps1");
		check(!said.contains("BUILD REFUSED") && said.contains("BUILD FAILED") && said.contains("judged nothing")
				&& !new File(root, "build/classes").exists(),
				"a gate that stops without failing a check is a failed build, not a refusal: " + lastLines(said));
		check(said.contains("the planted crash"), "and what stopped it is shown, not filtered away: " + lastLines(said));
	}

	/** Does this command start and exit 0? */
	static boolean runs(String... argv) {
		try {
			ProcessBuilder pb = new ProcessBuilder(argv);
			pb.redirectErrorStream(true);
			Process p = pb.start();
			CommitGuardTest.drain(p);
			return p.waitFor() == 0;
		} catch (Exception missing) {
			return false;
		}
	}

	/** Runs one of the scratch tree's scripts as the owner would, its whole output returned. */
	static String powershellScript(File root, String script) throws Exception {
		ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
				"-File", new File(root, script).getAbsolutePath());
		pb.directory(root);
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String said = CommitGuardTest.drain(p);
		p.waitFor();
		return said;
	}

	static String lastLines(String said) {
		String[] lines = said.trim().split("\n");
		StringBuilder sb = new StringBuilder();
		for (int i = Math.max(0, lines.length - 3); i < lines.length; i++) {
			sb.append(lines[i].trim()).append(" | ");
		}
		return sb.toString();
	}

	/** A scratch repository holding .gitattributes and a.txt, both added - so git lists them. */
	static File gitTree(String name, String attributes, String text) throws Exception {
		File root = Scratch.dir("rulerefusal-eol-" + name);
		Files.write(new File(root, ".gitattributes").toPath(), attributes.getBytes(StandardCharsets.UTF_8));
		Files.write(new File(root, "a.txt").toPath(), text.getBytes(StandardCharsets.UTF_8));
		CommitGuardTest.git(root, "init", "-q");
		CommitGuardTest.git(root, "add", "-A");
		return root;
	}

	// ---------------------------------------------------------------- fixtures

	/** A scratch tree holding one python file under tools/. */
	static File tool(String name, String body) throws Exception {
		File root = Scratch.dir("rulerefusal-tool");
		File target = new File(root, "tools/" + name);
		target.getParentFile().mkdirs();
		Files.write(target.toPath(), body.getBytes(StandardCharsets.UTF_8));
		return root;
	}

	/** A scratch tree holding one python file. */
	static File python(String name, String body) throws Exception {
		File root = Scratch.dir("rulerefusal");
		Files.write(new File(root, name).toPath(), body.getBytes(StandardCharsets.UTF_8));
		return root;
	}

	/** A scratch tree holding one java file at a given path under src/. */
	static File java(String path, String body) throws Exception {
		File root = Scratch.dir("rulerefusal-java");
		File target = new File(root, path);
		target.getParentFile().mkdirs();
		Files.write(target.toPath(), body.getBytes(StandardCharsets.UTF_8));
		return root;
	}

	static String ask(File repo, String tool, File root) throws Exception {
		return askWith(repo, tool, root.getAbsolutePath());
	}

	static String askWith(File repo, String tool, String... args) throws Exception {
		java.util.List<String> line = new java.util.ArrayList<>();
		line.add("python");
		line.add(new File(repo, "tools/guard/" + tool).getAbsolutePath());
		line.addAll(java.util.Arrays.asList(args));
		ProcessBuilder pb = new ProcessBuilder(line);
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String out = CommitGuardTest.drain(p);
		p.waitFor();
		if (out.contains("Traceback")) {
			System.out.println("  FAIL: " + tool + " crashed - " + firstReason(out));
			fails++;
		}
		return out;
	}

	/**
	 * Evaluates one expression against the magnitude module and prints the result.
	 *
	 * <p>Driven through python so this asserts the real predicate rather than a Java
	 * re-implementation of it, and written to a file rather than passed on the command line:
	 * an argv full of quotes is re-quoted by Windows on the way through CreateProcess, and a
	 * guard asked a malformed question answers nothing - which reads as a pass.
	 */
	static String said(File repo, String expression) throws Exception {
		File dir = Scratch.dir("magnitude-ask");
		File script = new File(dir, "ask.py");
		Files.write(script.toPath(),
				("import importlib.util, io, sys\n"
				+ "spec = importlib.util.spec_from_file_location('m', sys.argv[1])\n"
				+ "m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)\n"
				+ "expr = io.open(sys.argv[2], encoding='utf-8').read().strip()\n"
				+ "print(bool(eval('m.' + expr)))\n").getBytes(StandardCharsets.UTF_8));
		File arg = new File(dir, "expr.txt");
		Files.write(arg.toPath(), expression.getBytes(StandardCharsets.UTF_8));
		ProcessBuilder pb = new ProcessBuilder("python", script.getAbsolutePath(),
				new File(repo, "tools/guard/magnitude.py").getAbsolutePath(),
				arg.getAbsolutePath());
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String out = CommitGuardTest.drain(p);
		p.waitFor();
		if (!out.contains("True") && !out.contains("False")) {
			System.out.println("  FAIL: the predicate never answered - " + firstReason(out));
			fails++;
		}
		return out;
	}

	static String firstReason(String said) {
		for (String line : said.split("\n")) {
			String t = line.trim();
			if (t.startsWith("- ") || t.contains("counts its matches first")
					|| t.contains("none new") || t.contains("True") || t.contains("False")) {
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
