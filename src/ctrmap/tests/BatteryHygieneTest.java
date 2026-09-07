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
		builtByTheBattery(repo);
		noDialogsUnderTest(new File(root, "ctrmap"));
		publishedCountsAreMeasured(repo, root, new File(repo, "test.ps1"));
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** Every suite must take its scratch space from Scratch, not name it. */
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
		int suites = 0;
		if (runner.isFile()) {
			for (String line : Files.readAllLines(runner.toPath(), StandardCharsets.UTF_8)) {
				if (REGISTERED.matcher(line).find()) {
					suites++;
				}
			}
		}
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
				lines.add(rel + ":" + hex(sha.digest(Files.readAllBytes(p))) + "\n");
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
