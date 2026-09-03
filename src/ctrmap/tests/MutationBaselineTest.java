package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mutation baseline must still describe the code it was measured against.
 *
 * <p>The guards in this battery were themselves measured: wt/_state/mutate2.py
 * breaks one statement of a fix in a way that still compiles and asks whether
 * any suite notices. Where none does, that line is recorded in
 * src/ctrmap/tests/mutation_baseline.json as a survivor - a known hole, per
 * file, with the exact text of the line. The sweep costs half an hour of builds
 * and cannot run on every commit; this check runs in seconds and keeps the
 * record honest between sweeps.
 *
 * <p>Two things it refuses:
 * <ul>
 * <li>A recorded survivor whose line no longer reads as recorded. Someone edited
 *     the code a hole was measured on without re-measuring, so the count for
 *     that file is a number about a file that no longer exists. Re-run the
 *     sweep and commit the new baseline.</li>
 * <li>A file whose buckets do not add back up - survivors listed but not
 *     counted, or counted but not listed. The sweep's own accounting asserts
 *     killed + survived + nocompile + hung == attempted; this asserts the
 *     record kept that shape. An unmeasured mutant is a fact about the
 *     measurement, and must not be hidden by omission.</li>
 * </ul>
 * The survivor count itself may only fall, but that is the sweep's ratchet to
 * enforce: proving a count went down means re-measuring, which is the expensive
 * half. This is the cheap half.
 *
 * Usage: java ctrmap.tests.MutationBaselineTest [src-root]   (default "src")
 */
public class MutationBaselineTest {

	static int fails = 0;

	/** "path": { ... "survivors": n, ... "lines": [ {"line": n, "kind": "...", "code": "..."} ... ] } */
	private static final Pattern FILE = Pattern.compile("\"(src/[^\"]+\\.java)\"\\s*:\\s*\\{");
	private static final Pattern SURVIVORS = Pattern.compile("\"survivors\"\\s*:\\s*(\\d+)");
	private static final Pattern ENTRY = Pattern.compile("\\{\\s*\"line\"\\s*:\\s*(\\d+)\\s*,\\s*\"kind\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"code\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\}");

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		File baseline = new File(root, "ctrmap/tests/mutation_baseline.json");
		if (!baseline.isFile()) {
			System.out.println("  skip: no baseline at " + baseline + " - run wt/_state/mutate2.py and commit its output");
			System.out.println("ALL PASS");
			return;
		}
		String json = new String(Files.readAllBytes(baseline.toPath()), StandardCharsets.UTF_8);

		//split the document into one block per file, in order
		List<int[]> spans = new ArrayList<>();
		List<String> paths = new ArrayList<>();
		Matcher fm = FILE.matcher(json);
		while (fm.find()) {
			paths.add(fm.group(1));
			spans.add(new int[]{fm.end()});
		}
		check(!paths.isEmpty(), paths.size() + " file(s) recorded in the baseline");
		int filesChecked = 0, linesChecked = 0;
		for (int i = 0; i < paths.size(); i++) {
			int from = spans.get(i)[0];
			int to = i + 1 < spans.size() ? spans.get(i + 1)[0] : json.length();
			String block = json.substring(from, to);
			String path = paths.get(i);

			Matcher sm = SURVIVORS.matcher(block);
			if (!sm.find()) {
				check(false, path + ": has a survivors count");
				continue;
			}
			int survivors = Integer.parseInt(sm.group(1));

			List<String[]> entries = new ArrayList<>();
			Matcher em = ENTRY.matcher(block);
			while (em.find()) {
				entries.add(new String[]{em.group(1), em.group(2), em.group(3)});
			}
			check(entries.size() == survivors, path + ": lists " + entries.size()
					+ " survivor line(s) and counts " + survivors + " - the buckets must add up");

			File src = new File(root.getParentFile() == null ? new File(".") : root.getParentFile(), path);
			if (!src.isFile()) {
				//the baseline may have been measured in a worktree; resolve against root
				src = new File(root, path.substring("src/".length()));
			}
			if (!src.isFile()) {
				check(false, path + ": the recorded file still exists");
				continue;
			}
			List<String> lines = Files.readAllLines(src.toPath(), StandardCharsets.UTF_8);
			for (String[] e : entries) {
				int ln = Integer.parseInt(e[0]);
				String recorded = unescape(e[2]);
				String actual = ln >= 1 && ln <= lines.size() ? lines.get(ln - 1).trim() : null;
				//the sweep truncates to 160 characters, so compare on that prefix
				boolean same = actual != null && actual.startsWith(recorded);
				if (!same) {
					check(false, path + ":" + ln + " (" + e[1] + ") still reads as recorded - measured \""
							+ recorded + "\" but the line is now \"" + actual + "\"; re-run the sweep");
				}
				linesChecked++;
			}
			filesChecked++;
		}
		check(filesChecked > 0, filesChecked + " file(s) and " + linesChecked + " recorded survivor line(s) still match the source");

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** The JSON string escapes the sweep actually emits for a line of Java. */
	static String unescape(String s) {
		return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\t", "\t");
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
