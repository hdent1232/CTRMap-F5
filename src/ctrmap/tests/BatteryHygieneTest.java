package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
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
