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
