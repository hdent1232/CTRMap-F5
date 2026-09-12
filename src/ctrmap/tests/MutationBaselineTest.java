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
 * <p>The guards in this battery were themselves measured: {@code tools/mutate2.py}
 * breaks one statement of a fix in a way that still compiles and asks whether
 * any suite notices. Where none does, that line is recorded in
 * mutation_baseline.json as a survivor - a known hole, per
 * file, with the exact text of the line. The sweep costs hours of builds
 * and cannot run on every commit; this check runs in seconds and keeps the
 * record honest between sweeps.
 *
 * <p>WHEN THIS SUITE FAILS AFTER YOU EDIT A FILE, that is what it is for, and
 * the fix is NOT to edit mutation_baseline.json. The record says "these lines
 * of these files were measured, and this is what the battery noticed"; changing
 * a digest by hand asserts a measurement nobody took, which is the one lie this
 * file exists to prevent. Re-run the sweep
 * ({@code python tools/mutate2.py}, hours, and it ends in {@code git reset
 * --hard} so commit first), copy the baseline it writes over
 * mutation_baseline.json, and commit that. Until the sweep is re-run the
 * failure is the honest state of the record - report it rather than silencing
 * it. See TESTING.md, "The mutation sweep", for the whole procedure.
 *
 * <p>Two things it refuses:
 * <ul>
 * <li>A recorded survivor whose line no longer reads as recorded. Someone edited
 *     the code a hole was measured on without re-measuring, so the count for
 *     that file is a number about a file that no longer exists. Re-run the
 *     sweep and commit the new baseline.</li>
 * <li>A file whose buckets do not add back up - survivors listed but not
 *     counted, or counted but not listed. The sweep's own accounting asserts
 *     killed + survived + hung + nocompile == attempted, plus unmutable and
 *     excluded to reach the candidate lines; this asserts the record kept that
 *     shape. An unmeasured mutant is a fact about the measurement, and must not
 *     be hidden by omission.</li>
 * <li>An excluded line with no reason written against it, or more of them than
 *     the ceiling allows. A line the sweep scores in neither the numerator nor
 *     the denominator is a line nobody is watching on purpose. Two exist -
 *     Ui.java's dialog call, which cannot execute in a headless suite at all,
 *     and its System.out fallback, which is the channel the suites read Ui
 *     messages THROUGH, so a test asserting on it would be asserting its own
 *     instrument. Both stay visible here so the list cannot grow quietly.</li>
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
	private static final Pattern SHA = Pattern.compile("\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"");
	private static final Pattern ENTRY = Pattern.compile("\\{\\s*\"line\"\\s*:\\s*(\\d+)\\s*,\\s*\"kind\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"code\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\}");
	/** An excluded line: {@code {"line": n, "reason": "..."}} - no "kind", so ENTRY cannot match it. */
	private static final Pattern EXCLUDED = Pattern.compile("\\{\\s*\"line\"\\s*:\\s*(\\d+)\\s*,\\s*\"reason\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\}");
	private static final Pattern EXCLUDED_COUNT = Pattern.compile("\"excluded_count\"\\s*:\\s*(\\d+)");

	/**
	 * How many lines the whole tree may exclude from the measurement. Mirrors
	 * EXCLUSION_CEILING in tools/mutate2.py; raising either without the other is
	 * the drift this check exists to catch.
	 */
	private static final int EXCLUSION_CEILING = 4;

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		//beside the repo, NOT under src/: the baseline records a digest of the
		//sources it measured, and build.ps1 stamps a digest of the sources it
		//compiled. Filing it inside src/ would make committing it change the
		//very digest it is checked against - the record could never agree with
		//the tree it describes. It is data about the code, not code.
		File repo = root.getParentFile() == null ? new File(".") : root.getParentFile();
		File baseline = new File(repo, "mutation_baseline.json");
		if (!baseline.isFile()) {
			System.out.println("  skip: no baseline at " + baseline + " - run tools/mutate2.py and commit its output");
			System.out.println("ALL PASS");
			return;
		}
		thereIsOneBaselineAndTheSweepWritesIt(repo);
		theHarnessStillPassesItsOwnSelftest(repo);

		String json = new String(Files.readAllBytes(baseline.toPath()), StandardCharsets.UTF_8);

		//The baseline must still describe the files it measured - each one, by
		//its own digest. A whole-tree digest was tried first and was the wrong
		//instrument twice over: committing the baseline changed the tree it was
		//checked against, and editing this very file would have invalidated a
		//record that says nothing about it. The sweep never measures test
		//sources, so only these files can make its counts stale.
		check(json.contains("\"measured_at\""), "the baseline records the commit it was measured at");

		//AND WHETHER IT WAS MEASURED OVER A MOVING TREE. tools/guard/work_order.py
		//refuses a sweep while work is queued or the tree is dirty, and the escape
		//- --anyway "<reason>" - writes that reason into the baseline. It was
		//written there and read by nobody, so an overridden baseline would gate this
		//whole battery with nothing said: a decision recorded invisibly is the same
		//silence the refusal was built to remove, in a more respectable coat.
		int at = json.indexOf("\"work_order_override\"");
		String override = "";
		if (at >= 0) {
			int open = json.indexOf(":", at) + 1;
			int end = json.indexOf(",", open);
			override = end < 0 ? "" : json.substring(open, end).trim();
		}
		check(override.isEmpty() || override.equals("null"),
			"and it was measured over a tree that had stopped moving"
			+ (override.isEmpty() || override.equals("null") ? ""
				: " - this one says it was not: " + override
				+ ". A baseline taken with --anyway gates the whole battery on a state no"
				+ " commit matches; re-run the sweep once the queue is clear."));

		//split the document into one block per file, in order
		List<int[]> spans = new ArrayList<>();
		List<String> paths = new ArrayList<>();
		Matcher fm = FILE.matcher(json);
		while (fm.find()) {
			paths.add(fm.group(1));
			spans.add(new int[]{fm.end()});
		}
		check(!paths.isEmpty(), paths.size() + " file(s) recorded in the baseline");
		int filesChecked = 0, linesChecked = 0, excludedTotal = 0;
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

			//A line the sweep scores in neither the numerator nor the
			//denominator has to say why, in the record, where it is read rather
			//than in a comment in the harness. An exclusion with no reason is
			//indistinguishable from a line quietly dropped because it was
			//inconvenient - which is the one thing this whole measurement is
			//supposed to make impossible.
			List<int[]> excludedLines = new ArrayList<>();
			Matcher xm = EXCLUDED.matcher(block);
			while (xm.find()) {
				excludedLines.add(new int[]{Integer.parseInt(xm.group(1))});
				String reason = unescape(xm.group(2)).trim();
				check(reason.length() >= 60, path + ":" + xm.group(1)
						+ " is excluded from the measurement and says why (" + reason.length()
						+ " chars): " + (reason.length() > 70 ? reason.substring(0, 70) + "..." : reason));
			}
			excludedTotal += excludedLines.size();
			Matcher xc = EXCLUDED_COUNT.matcher(block);
			if (xc.find()) {
				check(Integer.parseInt(xc.group(1)) == excludedLines.size(), path + ": lists "
						+ excludedLines.size() + " excluded line(s) and counts " + xc.group(1)
						+ " - the buckets must add up");
			}

			//this file must be the one that was measured. The line texts below
			//catch an edit ON a recorded survivor; this catches an edit anywhere
			//else in the same file, which can add a mutation site nobody has
			//scored while every recorded line still reads correctly.
			Matcher hm = SHA.matcher(block);
			File onDisk = new File(repo, path);
			if (!hm.find()) {
				check(false, path + ": records the digest of the file that was measured");
			} else if (!onDisk.isFile()) {
				check(false, path + ": the measured file still exists");
			} else {
				java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
				StringBuilder hex = new StringBuilder();
				//the same rule stamp.ps1 uses, through the same helper: a file
				//re-saved with different line endings is not a file that changed
				for (byte b : sha.digest(BatteryHygieneTest.digestBytes(onDisk.toPath()))) {
					hex.append(String.format("%02x", b));
				}
				check(hex.toString().equals(hm.group(1)), path
						+ " is unchanged since the sweep measured it (otherwise: re-run tools/mutate2.py)");
			}

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
		check(excludedTotal <= EXCLUSION_CEILING, excludedTotal + " line(s) excluded from the "
				+ "measurement, ceiling " + EXCLUSION_CEILING + " - every one is a line nobody is "
				+ "watching on purpose, so growing the list is a decision for a human");

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * JSON string escapes, in ONE left-to-right pass.
	 *
	 * <p>Sequential replaces cannot do this: a Java line containing a literal
	 * backslash-n is written {@code \\n} in the file, and unescaping {@code \\}
	 * to {@code \} first leaves {@code \n}, which the next replace turns into a
	 * real newline. Two recorded lines compared unequal against source they
	 * matched exactly.
	 */
	static String unescape(String s) {
		StringBuilder out = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c != '\\' || i + 1 >= s.length()) {
				out.append(c);
				continue;
			}
			char n = s.charAt(++i);
			switch (n) {
				case 'n': out.append('\n'); break;
				case 't': out.append('\t'); break;
				case 'r': out.append('\r'); break;
				case 'b': out.append('\b'); break;
				case 'f': out.append('\f'); break;
				case 'u':
					out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
					i += 4;
					break;
				default: out.append(n); break;   // \" \\ \/ and anything else
			}
		}
		return out.toString();
	}

	/**
	 * The sweep writes the baseline this suite reads, and there is one of it.
	 *
	 * <p>WHY. tools/mutate2.py wrote {@code wt/_state/mutation_baseline.json}
	 * while this suite read {@code mutation_baseline.json} beside the repo, and
	 * the two were kept equal BY HAND. They happened to be byte-identical, which
	 * is not a property - it is a coincidence that lasts until the first sweep
	 * whose output nobody copies across. After that the sweep writes one record
	 * and the battery checks another, both are internally consistent, and
	 * nothing anywhere says they disagree. A ratchet with two copies is not a
	 * ratchet.
	 *
	 * <p>The sweep now derives the path from its own location, so moving the
	 * repository cannot separate them again, and this checks that it still does.
	 */
	static void thereIsOneBaselineAndTheSweepWritesIt(File repo) throws Exception {
		System.out.println("--- the sweep writes the baseline this suite reads, and there is one of it");
		File tool = new File(repo, "tools/mutate2.py");
		if (!tool.isFile()) {
			check(false, "no " + tool.getPath() + " - the sweep that writes this record is missing");
			return;
		}
		String text = new String(Files.readAllBytes(tool.toPath()), StandardCharsets.UTF_8);
		//RETARGETED, not dropped. This named the assignment itself -
		//`BASELINE = Path(__file__)...` - and the sweep now finds that path through
		//baseline_path(), because the suite ordering reads the last run BEFORE the
		//sweep starts while the ratchet writes it after, and a constant defined at the
		//bottom of the file cannot be read at the top of it. The PROPERTY is unchanged
		//and is what is checked here: one spelling of the path, built from the
		//location of the script itself, with the ratchet naming that one and no other.
		check(text.contains("def baseline_path():")
			&& text.contains("return Path(__file__).resolve().parent.parent / \"mutation_baseline.json\""),
			"the sweep finds the baseline beside the repo it lives in, from its own path");
		check(text.contains("BASELINE = baseline_path()"),
			"and the ratchet writes THAT one, rather than spelling the path a second time");
		//CODE lines only: the comment above that line records what the path used
		//to be and why it moved, which is worth keeping and is not a second copy
		String second = "";
		for (String line : text.split("\n")) {
			if (!line.trim().startsWith("#") && line.contains("wt/_state/mutation_baseline.json")) {
				second = line.trim();
			}
		}
		check(second.isEmpty(),
				"and no line of it names a second copy for anyone to keep equal by hand: " + second);
		File stale = new File(repo.getParentFile() == null ? new File("..") : repo.getParentFile(),
				"wt/_state/mutation_baseline.json");
		check(!stale.isFile(), "and the copy that used to be kept in step is gone (" + stale.getPath() + ")");
	}

	/**
	 * The sweep that writes this baseline still passes its own selftest.
	 *
	 * <p>WHY FROM HERE, when test.ps1 already runs it as a step of its own. Because
	 * a proof-by-breaking needs a suite it can START: tools/guard/replant.py puts a
	 * defect back and requires the named suite to go red, and it runs Java suites.
	 * Every guard living in the harness itself - and the suite ordering that decides
	 * how long a sweep takes is one - was therefore unprovable, which is the exact
	 * gap the ledger exists to close for the product.
	 *
	 * <p>It belongs to this suite rather than another because this record is worth
	 * precisely what the sweep that wrote it is worth. A harness whose own checks
	 * have rotted still writes a baseline, and it looks just like a good one.
	 */
	static void theHarnessStillPassesItsOwnSelftest(File repo) {
		System.out.println("--- the sweep that writes this baseline passes its own selftest");
		File tool = new File(repo, "tools/mutate2.py");
		if (!tool.isFile()) {
			check(false, "no " + tool.getPath() + " - the sweep that writes this record is missing");
			return;
		}
		java.util.List<String> said = new java.util.ArrayList<>();
		int exit;
		try {
			ProcessBuilder pb = new ProcessBuilder("python", "tools/mutate2.py", "--selftest");
			pb.directory(repo);
			pb.redirectErrorStream(true);
			Process p = pb.start();
			java.io.BufferedReader r = new java.io.BufferedReader(
				new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
			try {
				for (String line; (line = r.readLine()) != null;) {
					said.add(line);
				}
			} finally {
				r.close();
			}
			exit = p.waitFor();
		} catch (java.io.IOException noPython) {
			//the same skip CommitGuardTest takes: a python guard cannot be judged
			//without python, and saying so is not the same as passing
			System.out.println("  skip: python is not on PATH, so the harness selftest cannot run ("
				+ noPython.getMessage() + ")");
			return;
		} catch (InterruptedException stopped) {
			Thread.currentThread().interrupt();
			check(false, "the harness selftest did not finish - it was interrupted");
			return;
		}
		boolean passed = false;
		for (String line : said) {
			//WHAT IT SAID, not just that it failed: a plant is proven by the words its
			//guard used, and a wrapper that swallows them proves nothing
			if (line.contains("FAIL")) {
				System.out.println("     " + line.trim());
			}
			if (line.contains("ALL PASS")) {
				passed = true;
			}
		}
		check(exit == 0 && passed, "the mutation harness passes its own selftest, so the record"
			+ " below was written by a sweep whose own checks still hold (exit " + exit + ", "
			+ said.size() + " line(s))");
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
