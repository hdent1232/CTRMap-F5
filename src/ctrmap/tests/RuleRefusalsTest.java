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
		for (String tool : new String[]{"count_first.py", "magnitude.py", "game_identity.py"}) {
			File f = new File(repo, "tools/guard/" + tool);
			if (!f.isFile()) {
				System.out.println("  FAIL: no " + f.getPath()
						+ " - the rule it enforces has nothing behind it again");
				System.exit(1);
			}
		}
		if (!CommitGuardTest.onPath("python")) {
			System.out.println("  skip: python is not on PATH - these are python checkers, "
					+ "so this suite cannot run them here");
			System.out.println("ALL PASS");
			return;
		}

		aRewriteThatReachesAFileMustCountFirst(repo);
		aHarmlessRewriteIsNotAskedToCount(repo);
		theProjectCountsBeforeItRewrites(repo);
		aStartlingMoveIsRefused(repo);
		aNumberThatDidNotMoveAtAllIsRefused(repo);
		aGameIdentityOutsideTheSeamIsRefused(repo);

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

	// ---------------------------------------------------------------- fixtures

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
		ProcessBuilder pb = new ProcessBuilder("python",
				new File(repo, "tools/guard/" + tool).getAbsolutePath(),
				root.getAbsolutePath());
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
