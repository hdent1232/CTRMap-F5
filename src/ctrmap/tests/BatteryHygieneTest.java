package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The battery's own hygiene: rules about how a suite under ctrmap.tests may
 * touch the machine it runs on. Each was a live defect first.
 * <ul>
 * <li>No name of its own under the temp folder. BuildingCatalogTest wrote
 *     every donor region to %TEMP%/bcat_test_region; two batteries running at
 *     once rewrote it between each other's write and read, and one reported
 *     112 failures in a catalog that had not changed. Fifteen other suites
 *     had the same shape. Scratch space comes from {@link Scratch} - the
 *     JDK's unique-name API, removed at exit - never from java.io.tmpdir plus
 *     a name, and never from createTempFile's parent folder.</li>
 * <li>No dump path that only works beside this repo. Six suites resolved the
 *     corpus as "../RomFS..." with no way to override it, so the battery could
 *     not run from a worktree or a fresh clone: those six failed for a reason
 *     that had nothing to do with the code under test, and a contributor could
 *     not tell that from a real regression. A relative default is fine as a
 *     fallback; it must sit behind an args[0] the runner can pass.</li>
 * <li>...and the runner must actually pass it. The rule above only made the
 *     argument POSSIBLE, and two suites were still registered in test.ps1
 *     with none: BchMapModelTest fell back to its relative default and
 *     printed SKIP from every worktree, and MaisonClassListTest printed
 *     ALL PASS having round-tripped nought of nought entries. Both looked
 *     like a passing battery. A suite whose source names the dump must be
 *     registered with a path.</li>
 * <li>No count in the shipped documents that the repository cannot still
 *     produce. Three have shipped wrong - the building palette as 3,527 in
 *     README.md and 3,479 in TESTING.md against 3,583 in the tables, and the
 *     battery as "42 suites" and "84 headless test suites" against the ninety-odd
 *     test.ps1 registers. See {@link #publishedCountsAreMeasured}.</li>
 * <li>No class file in the build that no source could produce. build.ps1 used
 *     to compile into build\classes without clearing it, so 76 of the 906
 *     class files there were left over from earlier compiles and three still
 *     read fields the source had dropped. The build stamp signed them as
 *     genuine output. See {@link #noOrphanClassFiles}.</li>
 * </ul>
 * Comments are stripped before scanning, so only live code counts.
 *
 * Usage: java ctrmap.tests.BatteryHygieneTest [src-root]   (default "src")
 */
public class BatteryHygieneTest {

	/** A path built by hand under the JVM's temp folder. */
	private static final Pattern FIXED_TEMP = Pattern.compile("java\\.io\\.tmpdir");
	/** createTempFile used only to find the temp folder, then a name of the test's own. */
	private static final Pattern TEMP_PARENT = Pattern.compile("createTempFile\\([^;]*\\)\\s*\\.getParentFile\\(\\)");
	/**
	 * A path to the user's game data spelled relative to the repo's parent -
	 * the GARC/RomFS dumps, and the decompressed executable beside them.
	 *
	 * <p>code.bin was added to this pattern after ZoneLimitPatchTest was found
	 * registered with {@code a = @()}: it fell back to "../code.bin", which
	 * exists only in the author's layout, so from a worktree or a fresh clone
	 * the half of that suite which checks the five reverse-engineered stock
	 * words against the REAL executable never ran. It printed "(code.bin not
	 * found - skipped real-binary verification)" and then "PASS" - and since
	 * the runner shows only a suite's last two lines, even that notice was cut
	 * off. Green, silent, and asserting nothing about the executable at all.
	 * Two sibling suites (ItemIconPatch, ShopData) were registered with $code
	 * the whole time, which is what made the odd one out invisible.
	 */
	private static final Pattern REPO_RELATIVE_DUMP = Pattern.compile("\"\\.\\./(RomFS|code\\.bin)");
	/** Reading a path the runner passed in. */
	private static final Pattern TAKES_ARG = Pattern.compile("args\\s*\\[\\s*0\\s*\\]|args\\s*\\.\\s*length");
	/** A suite's registration line in the battery runner. */
	private static final Pattern REGISTERED = Pattern.compile("c\\s*=\\s*\"ctrmap\\.tests\\.(\\w+)\"");

	/**
	 * How the runner ANNOUNCES a suite. A suite that is not a Java main — the mutation
	 * harness's python selftest — is announced here and nowhere else, and it can fail on its
	 * own line like any other, so a count that reads only the registration array is short by
	 * exactly the suites that are not Java.
	 */
	private static final Pattern ANNOUNCED = Pattern.compile("Write-Host\\s*\\(\\s*\"--- \"");
	/** ...registered with nothing at all to point it at a dump. */
	private static final Pattern NO_ARGS = Pattern.compile("a\\s*=\\s*@\\(\\s*\\)");

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		File tests = new File(root, "ctrmap/tests");
		if (!tests.isDirectory()) {
			System.out.println("  skip: no test sources at " + tests);
			System.out.println("ALL PASS");
			return;
		}
		fixedTempPaths(tests);
		overridableCorpusPath(tests);
		everyBannedLiteralPinsItsSymbol(tests, root);
		File repo = root.getParentFile() == null ? new File(".") : root.getParentFile();
		//every script the battery or a harness executes, not just the runner: the
		//same heredoc trap that put NUL bytes into test.ps1 put them into
		//tools/mutate2.py an hour later - inside the comment describing the trap
		registeredWithItsCorpus(new File(repo, "test.ps1"), tests);
		runnerIsPlainText(new File(repo, "test.ps1"));
		runnerIsPlainText(new File(repo, "build.ps1"));
		runnerIsPlainText(new File(repo, "stamp.ps1"));
		File[] tools = new File(repo, "tools").listFiles();
		if (tools != null) {
			for (File t : tools) {
				if (t.getName().endsWith(".py") || t.getName().endsWith(".ps1")) {
					runnerIsPlainText(t);
				}
			}
		}
		thePublishInstructionCannotSilentlySkipTheTag(repo);
		theRecorderReadsTheVerdictLineWhole(repo);
		theRecorderDoesNotCallItsOwnOutputATreeThatMoved(repo);
		theDigestIgnoresLineEndingsButOnlyForText();
		aCopyWithNoRepositoryStillStamps(repo);
		builtByTheBattery(repo);
		noOrphanClassFiles(repo, root);
		noDialogsUnderTest(new File(root, "ctrmap"));
		publishedCountsAreMeasured(repo, root, new File(repo, "test.ps1"));
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** Every suite must take its scratch space from Scratch, not name it. */
	/**
	 * A check that bans a literal must also pin that the SYMBOL it bans still exists.
	 *
	 * <p>THE SHAPE. Thirteen checks in this battery assert that some text is ABSENT from a
	 * source file - {@code check(!text.contains("new ActionListener()"))},
	 * {@code check(!save.contains("populateScriptDropdown();"))}. Each is a real rule, and
	 * each has the same hole: it passes the instant the thing it forbids is spelled
	 * differently. A rename, a reformat, an extra space, a split across a {@code +}
	 * concatenation - and the check goes on reporting OK about a file it no longer describes.
	 * The verification bootstrap paid for this one: deleting two spaces from
	 * {@code live_allowed = True} opened live trading with the guard green, and an AST tool
	 * written to avoid that mistake made it again by matching a substring.
	 *
	 * <p>WHAT CANNOT BE DONE HERE. "Ask the object, not the file" is the right answer and it
	 * does not reach these: {@code new ActionListener()} is a source SHAPE, and the compiled
	 * class carries a synthetic inner class either way. So the textual check stays, and this
	 * closes the part that can be closed - the bootstrap’s own prescription, verbatim: "pin
	 * that the guarded symbol still EXISTS - or a rename makes every other assertion
	 * vacuous."
	 *
	 * <p>SO: for every banned literal, the longest identifier inside it must appear somewhere
	 * in the tree. {@code new ActionListener()} pins {@code ActionListener};
	 * {@code populateScriptDropdown();} pins {@code populateScriptDropdown}. When the method
	 * is renamed the pin fails and names the check that has quietly stopped asserting
	 * anything, instead of that check passing for ever.
	 */
	/**
	 * Every string this file WRITES, with the banned literals themselves taken out.
	 *
	 * <p>A test that drives a subprocess defines its own vocabulary in the script it emits,
	 * and that vocabulary is as real as any production symbol — it is just not in production.
	 * The {@code contains("...")} constructs come out first so a banned literal cannot rescue
	 * itself, which is the defect that made this whole check vacuous once.
	 */
	static String emittedStrings(String body) {
		String withoutBans = body.replaceAll("\\.contains\\(\\s*\"[^\"]*\"", ".contains(");
		StringBuilder out = new StringBuilder();
		java.util.regex.Matcher m =
			java.util.regex.Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"").matcher(withoutBans);
		while (m.find()) {
			out.append(m.group()).append(' ');
		}
		return out.toString();
	}

	/**
	 * The recorder reads the runner's verdict line WHOLE — including a suite whose own name
	 * contains the separator the line is joined with.
	 *
	 * <p>MEASURED on the run of 2026-09-21. {@code test.ps1} prints
	 * {@code FAILED: } and joins the names with {@code ", "}; one registered suite is called
	 * <i>Battery hygiene (temp paths, corpus args)</i>. The recorder split that line on commas,
	 * produced five names for four failing suites, two of which matched nothing announced — and
	 * since the passed list is <i>announced minus failed</i>, the failing hygiene suite was
	 * recorded as PASSED. The record said 133 of 136 with four red, and that record is the only
	 * number a commit message is allowed to claim.
	 *
	 * <p>Nothing drove {@code record_run.py} at all before this, which is why a parser at the
	 * centre of every count in the project could be wrong for as long as it liked.
	 */
	static void theRecorderReadsTheVerdictLineWhole(File repo) throws Exception {
		System.out.println("--- the recorder reads a verdict line whose suite names hold commas");
		File recorder = new File(repo, "tools/guard/record_run.py");
		if (!recorder.isFile() || !CommitGuardTest.onPath("python")) {
			System.out.println("  skip: no recorder or no python on PATH");
			return;
		}
		String q = String.valueOf((char) 34);
		String script =
			"import importlib.util, json, os, sys\n"
			+ "spec = importlib.util.spec_from_file_location('rr', sys.argv[1])\n"
			+ "m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)\n"
			+ "announced = ['Battery hygiene (temp paths, corpus args)', 'ModDeployer (ships)',"
			+ " 'Commit gate (refuses)']\n"
			+ "text = ('--- ' + announced[0] + chr(10) + '--- ' + announced[1] + chr(10)"
			+ " + '--- ' + announced[2] + chr(10)"
			+ " + 'FAILED: ' + announced[0] + ', ' + announced[1] + chr(10))\n"
			+ "failed = m.failed_in(text, announced)\n"
			+ "passed = [n for n in announced if n not in failed]\n"
			// AND THE WHOLE OF run(), with nothing actually launched. A NameError in this
			// function is otherwise found by running a thirteen-minute battery and watching
			// it crash at the end - which is exactly what happened on 2026-09-21, after an
			// edit removed a variable still used two lines below it. Importing the module
			// proves nothing: the body of a function that is never called does not run.
			+ "class Done(object):\n"
			+ "    def __init__(self, out): self.stdout = out; self.stderr = ''; "
			+ "self.returncode = 0\n"
			+ "class FakeSub(object):\n"
			+ "    SubprocessError = Exception\n"
			+ "    TimeoutExpired = Exception\n"
			+ "    def run(self, cmd, **kw):\n"
			+ "        return Done('' if cmd and cmd[0] == 'git' else text)\n"
			+ "m.subprocess = FakeSub()\n"
			// main(), NOT just run(). The first version of this drove run() alone and a
			// NameError in main() - one level up, same edit, same afternoon - still cost a
			// full battery to find. main() is where the record is written, the magnitude
			// recorded and the exit code decided, so it is where the lines nobody executes
			// accumulate. Its two writers are redirected: LAST_RUN to a temp file, and the
			// magnitude module through sys.modules, because `import magnitude` inside a
			// function binds a LOCAL name that cannot be patched on the module afterwards.
			+ "import tempfile, types\n"
			+ "fake_mag = types.ModuleType('magnitude')\n"
			+ "fake_mag.SERIES = os.path.join(os.path.dirname(sys.argv[1]),"
			+ " 'magnitudes.json')\n"
			+ "fake_mag.record = lambda k, v, why=None: (True, 'recorded %s = %s' % (k, v))\n"
			+ "sys.modules['magnitude'] = fake_mag\n"
			+ "m.LAST_RUN = os.path.join(tempfile.mkdtemp(prefix='ctrmap_rec_'), 'rec.json')\n"
			+ "code = m.main(['record_run.py', '--anyway',"
			+ " 'a reason long enough to count as a reason'])\n"
			+ "written = json.load(open(m.LAST_RUN))\n"
			+ "print(json.dumps({'failed': failed, 'passed': passed,"
			+ " 'ran': written['ran'], 'announced': written['suites_announced'],"
			+ " 'subject': bool(written['subject']), 'code': code}))\n";
		File dir = Scratch.dir("recorder-verdict");
		try {
			File py = new File(dir, "ask.py");
			Files.write(py.toPath(), script.getBytes(StandardCharsets.UTF_8));
			ProcessBuilder pb = new ProcessBuilder("python", "-B", py.getAbsolutePath(),
					recorder.getAbsolutePath());
			pb.redirectErrorStream(true);
			Process p = pb.start();
			String said = CommitGuardTest.drain(p).trim();
			p.waitFor();
			String last = said;
			int nl = said.lastIndexOf('\n');
			if (nl >= 0) {
				last = said.substring(nl + 1).trim();
			}
			check(last.contains(q + "failed" + q),
				"the recorder answered: " + (last.length() > 160 ? last.substring(0, 160) : last));
			check(last.contains("Battery hygiene (temp paths, corpus args)"),
				"a failing suite whose NAME holds the separator is named whole");
			// the decisive half: it must not also be in `passed`
			int passedAt = last.indexOf(q + "passed" + q);
			String passedPart = passedAt < 0 ? last : last.substring(passedAt);
			check(!passedPart.contains("Battery hygiene"),
				"...and is NOT counted among the suites that passed");
			check(passedPart.contains("Commit gate (refuses)"),
				"while the suite that really passed still is");
			// The end-to-end half: main() executed every line, with nothing launched.
			check(last.contains("\"ran\": 1"),
				"run() completes and counts one suite passed of three announced");
			check(last.contains("\"announced\": 3"),
				"...having read the announcements");
			check(last.contains("\"subject\": true"),
				"...and recorded which tree it measured");
			check(last.contains("\"code\": 1"),
				"...and main() ran to its end and reported the failures as a non-zero exit");
		} finally {
			Scratch.deleteTree(dir);
		}
	}

	/**
	 * The battery's own output is not the tree moving underneath it.
	 *
	 * <p>{@code record_run} digests the tree before and after the run and refuses to record
	 * one whose subject moved — the half of <i>never edit source while the suite is running</i>
	 * that does not care whether the edit went through a tool. MEASURED on its first real
	 * outing, 2026-09-21: it refused a clean 136-suite battery, because the ratchet suites
	 * record their readings into {@code tools/guard/magnitudes.json} as they go, and that is a
	 * tracked file. A guard that fires on honest work gets its ceiling raised until nothing
	 * here is believed.
	 *
	 * <p>Two questions, two subjects. <i>Which tree was this number measured against</i>
	 * includes the baselines, because a changed baseline changes verdicts. <i>Did the tree
	 * move underneath the run</i> leaves out what the run itself writes. The excluded path is
	 * derived from {@code magnitude.SERIES} rather than spelled again, so a rename cannot
	 * leave it excluding nothing.
	 */
	static void theRecorderDoesNotCallItsOwnOutputATreeThatMoved(File repo) throws Exception {
		System.out.println("--- the battery's own recorded readings are not a tree that moved");
		File guard = new File(repo, "tools/guard/commit_guard.py");
		if (!guard.isFile() || !CommitGuardTest.onPath("python")) {
			System.out.println("  skip: no guard or no python on PATH");
			return;
		}
		String script =
			"import importlib.util, json, os, subprocess, sys, tempfile\n"
			+ "spec = importlib.util.spec_from_file_location('cg', sys.argv[1])\n"
			+ "m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)\n"
			+ "d = tempfile.mkdtemp(prefix='ctrmap_subject_')\n"
			+ "os.makedirs(os.path.join(d, 'src'))\n"
			+ "os.makedirs(os.path.join(d, 'tools', 'guard'))\n"
			+ "def put(rel, body):\n"
			+ "    p = os.path.join(d, rel.replace('/', os.sep))\n"
			+ "    open(p, 'w').write(body)\n"
			+ "subprocess.run(['git', 'init', '-q'], cwd=d, capture_output=True, timeout=120)\n"
			+ "put('src/a.java', 'class A {}')\n"
			+ "put('tools/guard/magnitudes.json', '{\\\"a\\\": 1}')\n"
			+ "OUT = {'tools/guard/magnitudes.json'}\n"
			+ "full = m.source_digest(d)\n"
			+ "narrow = m.source_digest(d, exclude=OUT)\n"
			+ "put('tools/guard/magnitudes.json', '{\\\"a\\\": 2}')\n"
			+ "full2 = m.source_digest(d)\n"
			+ "narrow2 = m.source_digest(d, exclude=OUT)\n"
			+ "put('src/a.java', 'class A { int moved; }')\n"
			+ "narrow3 = m.source_digest(d, exclude=OUT)\n"
			+ "print(json.dumps({'outputMovesFull': full != full2,"
			+ " 'outputLeavesNarrow': narrow == narrow2,"
			+ " 'sourceStillMovesNarrow': narrow3 != narrow2}))\n";
		File dir = Scratch.dir("recorder-subject");
		try {
			File py = new File(dir, "ask.py");
			Files.write(py.toPath(), script.getBytes(StandardCharsets.UTF_8));
			ProcessBuilder pb = new ProcessBuilder("python", "-B", py.getAbsolutePath(),
					guard.getAbsolutePath());
			pb.redirectErrorStream(true);
			Process p = pb.start();
			String said = CommitGuardTest.drain(p).trim();
			p.waitFor();
			String last = said;
			int nl = said.lastIndexOf('\n');
			if (nl >= 0) {
				last = said.substring(nl + 1).trim();
			}
			check(last.contains("outputMovesFull\": true"),
				"a changed baseline DOES move the subject a record carries: "
					+ (last.length() > 140 ? last.substring(0, 140) : last));
			check(last.contains("outputLeavesNarrow\": true"),
				"...and does NOT count as the tree moving underneath the run");
			check(last.contains("sourceStillMovesNarrow\": true"),
				"while a real source edit mid-run still does, or this excuses everything");
		} finally {
			Scratch.deleteTree(dir);
		}
	}

	/**
	 * The packager's publish instruction must actually publish what it packaged.
	 *
	 * <p>MEASURED cutting 1.0.3 on 2026-09-22, two defects in one file:
	 *
	 * <ul>
	 * <li>the printed command was {@code git push --follow-tags}, which pushes only ANNOTATED
	 *     tags. Every tag this project has ever cut is lightweight. The tag was made, the
	 *     command reported success, master moved, and <b>the tag was not on the remote</b> —
	 *     after which {@code gh release create} would have made its own tag from wherever the
	 *     default branch happened to point. The only thing that decides whether a user is
	 *     offered an update is the release TAG compared against the version in their jar.</li>
	 * <li>the file carried a SECOND publish instruction in its header comment, which had
	 *     drifted: one zip, under a name the packager stopped producing two releases ago, and
	 *     a name ending in neither suffix {@code Updater.Flavour} selects on — so a release cut
	 *     by following it would have offered nothing to any user of either build.</li>
	 * </ul>
	 *
	 * <p>One rule written twice, behaving differently in each copy, is the shape CLAUDE.md
	 * records as costing this project repeatedly. So this asks for ONE instruction, and asks
	 * that it name the tag in the push rather than hope a flag carries it.
	 */
	static void thePublishInstructionCannotSilentlySkipTheTag(File repo) throws Exception {
		System.out.println("--- the publish instruction pushes the tag and both builds");
		File packager = new File(repo, "package.ps1");
		if (!packager.isFile()) {
			System.out.println("  skip: no package.ps1 at " + packager);
			return;
		}
		String body = read(packager);

		// --follow-tags carries an annotated tag and silently skips a lightweight one, and
		// `git tag <name>` with no -a makes a lightweight one. Refuse the pairing outright:
		// naming the tag in the push works for both kinds and cannot be got wrong.
		// ...asked of the CODE, not of the file. The comment above the fixed line names the
		// flag in order to explain why it is wrong, and prose about a defect is not the
		// defect - a check that cannot tell them apart fires on the very commit that fixes it.
		StringBuilder instructions = new StringBuilder();
		for (String line : body.split("\n")) {
			if (!line.trim().startsWith("#")) {
				instructions.append(line).append('\n');
			}
		}
		String code = instructions.toString();
		check(!code.contains("--follow-tags"),
			"the publish instruction does not lean on --follow-tags, which skips a lightweight"
			+ " tag without a word");
		check(body.contains("git push origin master v$Version"),
			"...it names the tag in the push instead");

		// ONE instruction. Two copies drift, and the drifted one is the one somebody reads.
		int pushes = 0;
		for (String line : body.split("\n")) {
			if (line.contains("git push") && !line.trim().startsWith("#")) {
				pushes++;
			}
		}
		check(pushes == 1, "and there is exactly ONE push instruction in the file, not a copy"
			+ " in the header to drift from it (found " + pushes + ")");

		// AND BOTH BUILDS. Updater.Flavour picks its download by asset-name suffix, so a
		// release carrying one zip offers nothing at all to users who installed the other way.
		int releases = 0;
		for (String line : body.split("\n")) {
			if (line.contains("gh release create") && !line.trim().startsWith("#")) {
				releases++;
				check(line.contains("$winZip") && line.contains("$zip"),
					"the release command carries BOTH builds, named from the variables that"
					+ " made them");
			}
		}
		check(releases == 1, "and there is exactly one release command (found " + releases + ")");

		// The suffixes are the contract between packager and updater. If either side renames,
		// this says so rather than letting a release publish assets nobody can select.
		check(body.contains("-portable.zip") && body.contains("-windows-x64.zip"),
			"...naming the two suffixes Updater.Flavour selects on");
		File flavour = new File(repo, "src/ctrmap/update/Updater.java");
		if (flavour.isFile()) {
			String updater = read(flavour);
			check(updater.contains("\"-portable.zip\"") && updater.contains("\"-windows-x64.zip\""),
				"and the updater still selects on those same two, or the packager is naming"
				+ " files nothing will download");
		}
	}

	static void everyBannedLiteralPinsItsSymbol(File tests, File src) throws Exception {
		System.out.println("--- every banned-literal check pins that its symbol still exists");
		java.util.regex.Pattern ban = java.util.regex.Pattern.compile(
			"!\\s*\\w+\\s*\\.contains\\(\\s*\"([^\"]{3,})\"");
		java.util.regex.Pattern word = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]{3,}");
		//PRODUCTION ONLY, and this is the whole check. Built over every source under src,
		//it included the suites - so a banned literal found ITSELF, in the very check
		//that names it, and every pin passed for that reason alone. It reported "27
		//checked" and asserted nothing: the class it exists to refuse, committed by the
		//fix for that class. A plant renaming a banned symbol SURVIVED, which is how it
		//was found - and is the argument for proving a guard by breaking it rather than
		//by watching it pass.
		StringBuilder everything = new StringBuilder();
		for (File j : DialogSeamTest.javaSources(src)) {
			if (j.getParentFile() != null && j.getParentFile().getName().equals("tests")) {
				continue;
			}
			everything.append(new String(java.nio.file.Files.readAllBytes(j.toPath()),
				java.nio.charset.StandardCharsets.UTF_8));
		}
		//AND THE TOOLING, because a banned symbol does not have to be Java. The first
		//honest run of this check reported CommitGuardTest's ban on
		//"work_order.require_frozen" as vacuous; that function is real and lives in
		//tools/guard/work_order.py, so the finding was this scan being narrower than the
		//program it claims to describe.
		File repo = src.getParentFile() == null ? new File(".") : src.getParentFile();
		java.util.List<File> extra = new java.util.ArrayList<>();
		collectTooling(new File(repo, "tools"), extra);
		File[] atRoot = repo.listFiles();
		if (atRoot != null) {
			for (File one : atRoot) {
				if (one.isFile() && one.getName().endsWith(".ps1")) {
					extra.add(one);
				}
			}
		}
		for (File one : extra) {
			everything.append(new String(java.nio.file.Files.readAllBytes(one.toPath()),
				java.nio.charset.StandardCharsets.UTF_8));
		}
		String tree = everything.toString();
		
		String quote = String.valueOf((char) 34);
		java.util.List<String> vacuous = new java.util.ArrayList<>();
		int pinned = 0;
		for (File j : DialogSeamTest.javaSources(tests)) {
			String body = new String(java.nio.file.Files.readAllBytes(j.toPath()),
				java.nio.charset.StandardCharsets.UTF_8);
			java.util.regex.Matcher m = ban.matcher(body);
			while (m.find()) {
				String literal = m.group(1);
				//the longest identifier in the banned text is the symbol it is about
				String symbol = "";
				java.util.regex.Matcher w = word.matcher(literal);
				while (w.find()) {
					if (w.group().length() > symbol.length()) {
						symbol = w.group();
					}
				}
				if (symbol.isEmpty()) {
					continue;   //a punctuation ban pins nothing; there are none today
				}
				pinned++;
				//AND THE HARNESS'S OWN VOCABULARY COUNTS AS REAL. Not every banned literal
				//names a production symbol: HookRefusalsTest drives each hook through a python
				//script it writes itself, which prints ALLOW or DENY, and asserts that the
				//guard answered one of them. DENY is that protocol - defined four lines above
				//the assertion, in a string this very file emits - and it appears nowhere in
				//production, so this check called a live assertion vacuous and the battery was
				//red for it. The literal is looked for in the STRINGS THE BANNING FILE ITSELF
				//WRITES, with every `contains("...")` construct removed first. That removal is
				//the whole point: the original defect was a banned literal matching ITSELF in
				//the ban line, which made every pin pass and let a plant survive. A comment
				//mentioning the symbol does not rescue it either - only a string the file
				//actually produces.
				if (!tree.contains(symbol) && !emittedStrings(body).contains(symbol)) {
					vacuous.add(j.getName() + " bans " + quote + literal + quote + " but " + symbol
						+ " is nowhere in the tree - that check can no longer fail");
				}
			}
		}
		check(pinned > 0, pinned + " banned-literal check(s) found, so this is still looking at"
			+ " something - a scan that quietly stops matching asserts nothing");
		check(vacuous.isEmpty(), "every banned literal names a symbol that still exists"
			+ (vacuous.isEmpty() ? " (" + pinned + " checked)" : ", but: " + vacuous));
	}

	/** Every .py under a tools directory, recursively. */
	static void collectTooling(File dir, java.util.List<File> into) {
		File[] kids = dir.listFiles();
		if (kids == null) {
			return;
		}
		for (File kid : kids) {
			if (kid.isDirectory()) {
				collectTooling(kid, into);
			} else if (kid.getName().endsWith(".py")) {
				into.add(kid);
			}
		}
	}

	static void fixedTempPaths(File tests) throws Exception {
		List<String> named = new ArrayList<>();
		List<File> sources = sources(tests);
		for (File f : sources) {
			String[] lines = SourceSeamTest.stripComments(read(f)).split("\n", -1);
			for (int i = 0; i < lines.length; i++) {
				if (FIXED_TEMP.matcher(lines[i]).find() || TEMP_PARENT.matcher(lines[i]).find()) {
					named.add(f.getName() + ":" + (i + 1));
				}
			}
		}
		check(sources.size() >= 50, sources.size() + " test sources scanned");
		check(named.isEmpty(), "no suite names its own path under the temp folder; found " + named);
	}

	/** A suite that names the dump must also accept one from the runner. */
	static void overridableCorpusPath(File tests) throws Exception {
		List<String> stuck = new ArrayList<>();
		for (File f : sources(tests)) {
			String src = SourceSeamTest.stripComments(read(f));
			if (REPO_RELATIVE_DUMP.matcher(src).find() && !TAKES_ARG.matcher(src).find()) {
				stuck.add(f.getName());
			}
		}
		check(stuck.isEmpty(), "every suite that names the dump can be pointed at one; stuck: " + stuck);
	}

	/**
	 * A suite that names the dump must be REGISTERED with one.
	 *
	 * <p>{@link #overridableCorpusPath} only made the argument possible, and
	 * that was the hole two suites fell through: registered with {@code a = @()},
	 * they fell back to their repo-relative default, and from a worktree
	 * BchMapModelTest printed SKIP while MaisonClassListTest printed ALL PASS
	 * over nought entries. Neither showed up as a failure, so the battery
	 * reported green on suites it had not run.
	 */
	static void registeredWithItsCorpus(File runner, File tests) throws Exception {
		if (!runner.isFile()) {
			System.out.println("  skip: no runner at " + runner);
			return;
		}
		List<String> starved = new ArrayList<>();
		int registered = 0;
		for (String line : Files.readAllLines(runner.toPath(), StandardCharsets.UTF_8)) {
			java.util.regex.Matcher m = REGISTERED.matcher(line);
			if (!m.find()) {
				continue;
			}
			registered++;
			File src = new File(tests, m.group(1) + ".java");
			if (NO_ARGS.matcher(line).find() && src.isFile()
					&& REPO_RELATIVE_DUMP.matcher(SourceSeamTest.stripComments(read(src))).find()) {
				starved.add(m.group(1));
			}
		}
		check(registered >= 50, registered + " suites registered in the battery");
		check(starved.isEmpty(), "every suite that names the dump is registered with one; starved: " + starved);
	}

	/** A count the shipped docs publish about the catalogue. */
	private static final Pattern DOC_CATALOG = Pattern.compile(
			"([0-9][0-9,]*)\\s*(?:\\*\\*\\s*)?(?:catalogued|auto-harvested|curated)");
	/** ...and about the size of this battery. */
	private static final Pattern DOC_SUITES = Pattern.compile(
			"([0-9][0-9,]*)(?:\\*\\*)?\\s+(?:headless\\s+)?(?:test\\s+)?suites?\\b");
	/** A data row in one of the catalogue tables: not blank, not a '#' comment. */
	private static final Pattern TSV_COMMENT = Pattern.compile("^\\s*(#.*)?$");

	/**
	 * A number the README, the release notes or this file publishes about the
	 * program has to be one the repository can still produce.
	 *
	 * <p>WHY THIS EXISTS. Three counts have shipped wrong, and each was read by
	 * somebody as fact. The building palette was published as 3,527 in README.md
	 * and 3,479 in TESTING.md while the two tables held 3,583 between them; the
	 * battery was published as "42 suites" and "84 headless test suites" while
	 * test.ps1 registered ninety-odd. Nothing was watching, because a number in
	 * a document is not code and no suite reads documents.
	 *
	 * <p>WHAT IS ASSERTED, and deliberately no more. Only two families of claim,
	 * both mechanically derivable from data in this repository: how many
	 * structures the palette ships (the two TSVs, counted the way
	 * {@code BuildingCatalog} counts them) and how many suites the battery runs
	 * (test.ps1's own registrations). Every number a doc states next to those
	 * words must be one of the answers. The claims must also still BE there - a
	 * guard that silently stops matching is not a guard - so each family has to
	 * appear at least once across the documents scanned.
	 *
	 * <p>WHAT IT WILL NOT CATCH: a doc that rephrases the claim out of these
	 * patterns, or any of the dozens of other measured numbers in these files.
	 * The bar for adding to this is that the number can be RE-DERIVED here from
	 * committed data; anything else would be a second hand-maintained copy of
	 * the fact, which is the defect, not the fix.
	 */
	static void publishedCountsAreMeasured(File repo, File srcRoot, File runner) throws Exception {
		File res = new File(srcRoot, "ctrmap/resources");
		int curated = tsvRows(new File(res, "oras_buildings.tsv"));
		int auto = tsvRows(new File(res, "oras_buildings_auto.tsv"));
		if (curated < 0 || auto < 0) {
			System.out.println("  skip: no building catalogue at " + res);
			return;
		}
		//COUNT WHAT THE BATTERY ANNOUNCES, NOT WHAT ONE ARRAY LITERAL HOLDS. This counted the
		//`@{ n = ... }` entries and stopped there, so it read 135 while the run announced 136:
		//"Mutation harness selftest" is announced and can fail on its own line, outside the
		//array, because it is python rather than a Java main. The check was therefore refusing
		//a document that stated the TRUE number and would have accepted one that did not -
		//a guard comparing against a figure it computed wrongly, which is the same defect
		//class as a count checked against a stale record. Every `--- ` announcement counts:
		//the loop's own is attributed to the array entries it iterates, and any other is a
		//suite in its own right.
		int suites = 0;
		int standalone = 0;
		if (runner.isFile()) {
			for (String line : Files.readAllLines(runner.toPath(), StandardCharsets.UTF_8)) {
				if (REGISTERED.matcher(line).find()) {
					suites++;
				} else if (ANNOUNCED.matcher(line).find() && !line.contains("$s.n")) {
					standalone++;
				}
			}
		}
		suites += standalone;
		//48 curated, 3,535 harvested, 3,583 in the palette: a doc may quote any
		//of the three, because all three are true and each says something
		//different. It may not quote a fourth.
		List<Integer> catalogOk = java.util.Arrays.asList(curated, auto, curated + auto);
		List<String> wrong = new ArrayList<>();
		int catalogClaims = 0, suiteClaims = 0;
		for (String name : new String[]{"README.md", "NOTES.md", "TESTING.md", "QUICKSTART.md"}) {
			File doc = new File(repo, name);
			if (!doc.isFile()) {
				continue;
			}
			String text = read(doc);
			Matcher m = DOC_CATALOG.matcher(text);
			while (m.find()) {
				catalogClaims++;
				int n = Integer.parseInt(m.group(1).replace(",", ""));
				if (!catalogOk.contains(n)) {
					wrong.add(name + " says " + m.group(0).trim() + " - the tables hold "
							+ curated + " curated + " + auto + " harvested = " + (curated + auto));
				}
			}
			m = DOC_SUITES.matcher(text);
			while (m.find()) {
				suiteClaims++;
				int n = Integer.parseInt(m.group(1).replace(",", ""));
				if (suites > 0 && n != suites) {
					wrong.add(name + " says " + m.group(0).trim() + " - test.ps1 registers " + suites);
				}
			}
		}
		check(wrong.isEmpty(), "every count the docs publish about the palette and the battery is "
				+ "one this repo can still produce (" + catalogClaims + " palette claim(s), "
				+ suiteClaims + " battery claim(s) checked); wrong: " + wrong);
		check(catalogClaims > 0 && suiteClaims > 0, "...and both claims are still MADE somewhere - "
				+ "a check that has quietly stopped matching asserts nothing (" + catalogClaims
				+ " palette, " + suiteClaims + " battery)");
	}

	/** Data rows in a catalogue TSV, counted as BuildingCatalog counts them; -1 if absent. */
	static int tsvRows(File f) throws Exception {
		if (!f.isFile()) {
			return -1;
		}
		int n = 0;
		for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
			if (!TSV_COMMENT.matcher(line).matches() && line.split("\t").length >= 14) {
				n++;
			}
		}
		return n;
	}

	/**
	 * The runner must be plain text. Five NUL bytes reached test.ps1 through an
	 * edit that spelled an archive path as "a\0\1\4" in a shell heredoc, which
	 * stripped a backslash before Python read the octal escapes. Three suites'
	 * arguments then held control bytes in place of their path, the battery
	 * would have handed them garbage on its next run, and nothing in the repo
	 * was looking. A byte below 0x20 that is not tab, CR or LF has no business
	 * in a PowerShell script.
	 */
	static void runnerIsPlainText(File runner) throws Exception {
		if (!runner.isFile()) {
			System.out.println("  skip: no runner at " + runner);
			return;
		}
		byte[] b = Files.readAllBytes(runner.toPath());
		List<String> bad = new ArrayList<>();
		int line = 1;
		for (int i = 0; i < b.length; i++) {
			if (b[i] == '\n') {
				line++;
			} else if ((b[i] & 0xFF) < 0x20 && b[i] != '\t' && b[i] != '\r') {
				bad.add("line " + line + " byte 0x" + Integer.toHexString(b[i] & 0xFF));
			}
		}
		check(bad.isEmpty(), runner.getName() + " holds no control bytes; found " + bad);
	}

	/**
	 * These classes must be the ones build.ps1 made from these sources. A
	 * verification harness once compiled the tree with bare javac, which copies
	 * no resources, and measured a build/classes whose catalogue was stale: a
	 * guard suite failed there and passed everywhere else, and the harness
	 * reported confidently about a tree the battery never runs. build.ps1 now
	 * stamps what it built (stamp.ps1); this recomputes the two digests - the
	 * sources, and everything under build/classes - and fails on any difference,
	 * so the battery itself cannot run against a hand-built or half-built tree.
	 * The algorithm is stamp.ps1's; keep the three copies identical.
	 */
	static void builtByTheBattery(File repo) throws Exception {
		File classes = new File(repo, "build/classes");
		File stamp = new File(classes, ".built-by-build-ps1");
		if (!stamp.isFile()) {
			check(false, "build/classes carries a build.ps1 stamp (none found - it was not produced by build.ps1)");
			return;
		}
		java.util.Map<String, String> kv = new java.util.HashMap<>();
		for (String line : Files.readAllLines(stamp.toPath(), StandardCharsets.UTF_8)) {
			int eq = line.indexOf('=');
			if (eq > 0) {
				kv.put(line.substring(0, eq), line.substring(eq + 1));
			}
		}
		check(treeDigest(new File(repo, "src"), "").equals(kv.get("src")),
				"src/ is what build.ps1 last compiled (otherwise: rebuild before measuring anything)");
		check(treeDigest(classes, ".built-by-build-ps1").equals(kv.get("classes")),
				"build/classes is exactly what build.ps1 produced (a file added, removed or replaced since fails this)");
	}

	/**
	 * A top-level type declared in a source file that is not named after it.
	 * Five exist here (FATBEntry and GARCEntry in GARC.java, BoundStructure in
	 * GRCollisionBounds.java, OBJMesh in WavefrontOBJ.java, Tile in
	 * TileDBWriter.java), and each compiles to a class file whose name matches
	 * no .java at all. Anchored at column 0 because that is where a top-level
	 * declaration sits: a nested type is indented, and it compiles to
	 * Outer$Inner.class, which this check has already resolved to its outer name
	 * before it looks here.
	 */
	private static final Pattern TOP_LEVEL_TYPE = Pattern.compile(
			"(?m)^(?:(?:public|final|abstract|strictfp)\\s+)*(?:class|interface|enum)\\s+(\\w+)\\b");

	/**
	 * No class file in the build whose source has gone. javac only ever ADDS to
	 * its -d directory, so before build.ps1 learned to clear build\classes a
	 * class outlived the deletion of the code that made it: 76 of the 906 class
	 * files there were orphans of earlier compiles, and three
	 * (CtrmapMainframe$26, $30, $42) still read {@code Workspace.valid} and
	 * {@code .persist_paths}, fields the source no longer declares.
	 *
	 * <p>Why that mattered rather than merely wasting disk: {@link #builtByTheBattery}
	 * asks whether build/classes is exactly what build.ps1 produced, and it was -
	 * build.ps1 stamped the ghosts along with everything else, so the stamp
	 * certified them. A guard that reads bytecode off the directory counts them
	 * too, and reports about code no source can produce.
	 *
	 * <p>WHAT IS ASSERTED: for every .class here, some source in src could have
	 * produced it. Foo$Bar.class and Foo$1.class resolve to their outermost name
	 * first, so the question is only ever about a top-level type; that is
	 * usually Foo.java beside it, and otherwise a type declared inside another
	 * file of the same package (see {@link #TOP_LEVEL_TYPE}).
	 *
	 * <p>WHAT IT WILL NOT CATCH: a stale ANONYMOUS class, Foo$26.class from a
	 * listener that has been deleted while Foo.java lives on - counting the
	 * anonymous classes a source would generate means compiling it, and a
	 * guess is worse than nothing. The clean in build.ps1 is what removes those;
	 * this is the check that the clean is still happening at all, since a build
	 * that stopped clearing the directory would strand a whole deleted class
	 * here on its very next run. Deliberately NOT a timestamp comparison: an
	 * mtime says when a file was written, never what is in it.
	 */
	static void noOrphanClassFiles(File repo, File srcRoot) throws Exception {
		File classes = new File(repo, "build/classes");
		if (!classes.isDirectory()) {
			System.out.println("  skip: no build at " + classes);
			return;
		}
		java.util.Map<String, java.util.Set<String>> declaredByPackage = new java.util.HashMap<>();
		List<String> orphans = new ArrayList<>();
		int scanned = 0;
		java.nio.file.Path base = classes.toPath();
		try (java.util.stream.Stream<java.nio.file.Path> walk = Files.walk(base)) {
			for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) walk::iterator) {
				if (!Files.isRegularFile(p)) {
					continue;
				}
				String rel = base.relativize(p).toString().replace('\\', '/');
				if (!rel.endsWith(".class")) {
					continue;
				}
				scanned++;
				String binary = rel.substring(0, rel.length() - ".class".length());
				int dollar = binary.indexOf('$');
				String top = dollar < 0 ? binary : binary.substring(0, dollar);
				if (new File(srcRoot, top + ".java").isFile()) {
					continue;
				}
				int slash = top.lastIndexOf('/');
				String pkg = slash < 0 ? "" : top.substring(0, slash);
				if (!declaredByPackage.containsKey(pkg)) {
					declaredByPackage.put(pkg, topLevelTypesIn(new File(srcRoot, pkg)));
				}
				if (!declaredByPackage.get(pkg).contains(top.substring(slash + 1))) {
					orphans.add(rel);
				}
			}
		}
		check(scanned >= 500, scanned + " class files walked in " + classes);
		java.util.Collections.sort(orphans);
		check(orphans.isEmpty(), "every class file in the build still has a source that could produce it "
				+ "(a clean build leaves no orphan); orphaned: " + orphans);
	}

	/** The top-level type names the .java files directly in one package declare. */
	static java.util.Set<String> topLevelTypesIn(File pkgDir) throws Exception {
		java.util.Set<String> names = new java.util.HashSet<>();
		File[] files = pkgDir.listFiles();
		if (files == null) {
			return names;
		}
		for (File f : files) {
			if (!f.isFile() || !f.getName().endsWith(".java")) {
				continue;
			}
			Matcher m = TOP_LEVEL_TYPE.matcher(SourceSeamTest.stripComments(read(f)));
			while (m.find()) {
				names.add(m.group(1));
			}
		}
		return names;
	}

	/**
	 * A suite must not be able to open a modal dialog. Ui shows a real window
	 * only after {@link ctrmap.Ui#enableDialogs()}, which only the application
	 * calls; anything else prints. Several foreign-snapshot warnings appeared on
	 * the owner's desktop during a battery run and it finished only because
	 * somebody was there to dismiss them - unattended, a modal dialog waits
	 * forever and the run reports nothing.
	 *
	 * <p>Checks both halves: dialogs are off right now (this IS a suite), and no
	 * file outside the application's entry point turns them on.
	 */
	static void noDialogsUnderTest(File srcRoot) throws Exception {
		java.lang.reflect.Field f = ctrmap.Ui.class.getDeclaredField("dialogsEnabled");
		f.setAccessible(true);
		check(!((Boolean) f.get(null)), "a suite runs with dialogs off, so no message can block it");

		List<String> callers = new ArrayList<>();
		for (File src : sources(srcRoot)) {
			if (SourceSeamTest.stripComments(read(src)).contains("Ui.enableDialogs()")) {
				callers.add(src.getName());
			}
		}
		check(callers.equals(java.util.Collections.singletonList("CtrmapMainframe.java")),
				"only the application enables dialogs; found " + callers);
	}

	/**
	 * A source file re-saved with the other line endings is not a file that
	 * changed - and a class file with a 0x0D in it still is.
	 *
	 * <p>WHY BOTH HALVES. git normalises endings on commit and restores them
	 * per checkout, so the same commit can hand two machines different bytes.
	 * Hashing those bytes made the stamp and the mutation baseline say a file
	 * had been edited when nothing had; on 2026-09-07 seven files a sed pass
	 * left as LF failed MutationBaselineTest for a reason that had nothing to
	 * do with the code. Ignoring CR everywhere would be the other mistake: a
	 * .class or a .png differing only in 0x0D bytes IS a different file, and a
	 * digest that could not say so would be worth less than the one it replaced.
	 */
	static void theDigestIgnoresLineEndingsButOnlyForText() throws Exception {
		System.out.println("--- endings are not content, for the files where they are not content");
		File dir = Scratch.dir("digest-endings");
		byte[] lf = "class A {\n\tint x;\n}\n".getBytes(StandardCharsets.UTF_8);
		byte[] crlf = "class A {\r\n\tint x;\r\n}\r\n".getBytes(StandardCharsets.UTF_8);

		File a = new File(dir, "A.java");
		File b = new File(dir, "B.java");
		Files.write(a.toPath(), lf);
		Files.write(b.toPath(), crlf);
		check(!java.util.Arrays.equals(Files.readAllBytes(a.toPath()), Files.readAllBytes(b.toPath())),
				"the two files really do differ on disk, or this proves nothing");
		check(java.util.Arrays.equals(digestBytes(a.toPath()), digestBytes(b.toPath())),
				"the same source saved LF and CRLF hashes the same");

		File c = new File(dir, "A.class");
		File d = new File(dir, "B.class");
		Files.write(c.toPath(), lf);
		Files.write(d.toPath(), crlf);
		check(!java.util.Arrays.equals(digestBytes(c.toPath()), digestBytes(d.toPath())),
				"but two class files differing by a 0x0D still hash differently");

		//and the text rule must not quietly become "every file": a name that
		//merely CONTAINS .java is not a .java
		File e = new File(dir, "A.java.class");
		Files.write(e.toPath(), crlf);
		check(!java.util.Arrays.equals(digestBytes(c.toPath()), digestBytes(e.toPath())),
				"the rule reads the extension, not the name");
		Scratch.deleteTree(dir);
	}

	/**
	 * build.ps1 stamps a copy that is not a git repository, instead of dying
	 * between compiling and stamping.
	 *
	 * <p>WHY THIS EXISTS. Downloading the source as a zip, or exporting it with
	 * {@code git archive}, gives a directory with no {@code .git} - the ordinary
	 * path for someone who is not a git user. {@code git rev-parse HEAD} then
	 * writes to stderr, and PowerShell with {@code $ErrorActionPreference =
	 * "Stop"} turns a native command's stderr into a TERMINATING error. So
	 * build.ps1 compiled 834 classes, died before writing the stamp, never
	 * printed "Build OK", and test.ps1 reported "build\classes carries no stamp
	 * - it was not produced by build.ps1". Every word of that is true and all of
	 * it points away from the cause.
	 *
	 * <p>This runs the real stamp.ps1 against a scratch directory with no
	 * repository in it, and asks for the file it should have written.
	 */
	static void aCopyWithNoRepositoryStillStamps(File repo) throws Exception {
		System.out.println("--- and a copy with no .git in it still gets stamped");
		File stamp = new File(repo, "stamp.ps1");
		if (!stamp.isFile()) {
			check(false, "no stamp.ps1 at " + stamp.getPath());
			return;
		}
		File dir = Scratch.dir("stamp-nogit");
		File classes = new File(dir, "build/classes");
		check(new File(dir, "src").mkdirs() && classes.mkdirs(), "a scratch tree with src and build/classes");
		Files.write(new File(dir, "src/A.java").toPath(), "class A {}\n".getBytes(StandardCharsets.UTF_8));
		Files.write(new File(classes, "A.class").toPath(), new byte[]{1, 2, 3});
		check(!new File(dir, ".git").exists(), "and no repository anywhere in it");

		ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
				"-Command", ". '" + stamp.getAbsolutePath() + "'; $ErrorActionPreference = 'Stop'; "
				+ "Write-BuildStamp '" + dir.getAbsolutePath() + "'");
		pb.redirectErrorStream(true);
		Process p = pb.start();
		StringBuilder said = new StringBuilder();
		try (java.io.BufferedReader r = new java.io.BufferedReader(
				new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			for (String line = r.readLine(); line != null; line = r.readLine()) {
				said.append(line).append('\n');
			}
		}
		int code = p.waitFor();
		File written = new File(classes, ".built-by-build-ps1");
		check(code == 0, "stamp.ps1 finishes rather than throwing on the missing repository (exit "
				+ code + ") " + said.toString().trim());
		check(written.isFile(), "and the stamp exists, which is what build.ps1 dies before doing");
		if (written.isFile()) {
			String text = new String(Files.readAllBytes(written.toPath()), StandardCharsets.UTF_8);
			check(text.contains("sha=unknown"), "recording the commit as unknown, a branch the stamp "
					+ "already had and could never reach: " + text.replace("\n", " | ").trim());
		}
		Scratch.deleteTree(dir);
	}

	/**
	 * The bytes a digest is taken over. stamp.ps1's rule: a file whose name ends
	 * in .java .form .properties .tsv .md or .txt is hashed with every CR byte
	 * removed, because line endings are not content - git normalises them on
	 * commit and restores them per checkout, so the same commit would otherwise
	 * hash differently on two machines. Everything else is hashed byte for byte.
	 */
	static byte[] digestBytes(java.nio.file.Path p) throws Exception {
		byte[] b = Files.readAllBytes(p);
		String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
		boolean text = false;
		for (String e : new String[]{".java", ".form", ".properties", ".tsv", ".md", ".txt"}) {
			if (name.endsWith(e)) {
				text = true;
				break;
			}
		}
		if (!text) {
			return b;
		}
		byte[] out = new byte[b.length];
		int n = 0;
		for (byte x : b) {
			if (x != 13) {
				out[n++] = x;
			}
		}
		return java.util.Arrays.copyOf(out, n);
	}

	/** stamp.ps1's digest: sorted "relpath:sha256" lines, sha256 of the manifest. */
	static String treeDigest(File root, String exclude) throws Exception {
		java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
		List<String> lines = new ArrayList<>();
		java.nio.file.Path base = root.toPath();
		try (java.util.stream.Stream<java.nio.file.Path> walk = Files.walk(base)) {
			for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) walk::iterator) {
				if (!Files.isRegularFile(p)) {
					continue;
				}
				String rel = base.relativize(p).toString().replace('\\', '/');
				if (rel.equals(exclude)) {
					continue;
				}
				lines.add(rel + ":" + hex(sha.digest(digestBytes(p))) + "\n");
			}
		}
		java.util.Collections.sort(lines);
		StringBuilder manifest = new StringBuilder();
		for (String l : lines) {
			manifest.append(l);
		}
		return hex(sha.digest(manifest.toString().getBytes(StandardCharsets.UTF_8)));
	}

	static String hex(byte[] d) {
		StringBuilder sb = new StringBuilder();
		for (byte b : d) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	/** The .java files under dir, minus this guard: it spells the patterns out. */
	static List<File> sources(File dir) {
		List<File> out = new ArrayList<>();
		File[] files = dir.listFiles();
		if (files == null) {
			return out;
		}
		for (File f : files) {
			if (f.isDirectory()) {
				out.addAll(sources(f));
			} else if (f.getName().endsWith(".java") && !f.getName().equals("BatteryHygieneTest.java")) {
				out.add(f);
			}
		}
		return out;
	}

	static String read(File f) throws Exception {
		return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
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
