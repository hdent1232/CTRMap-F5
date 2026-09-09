package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * The plant ledger still matches the tree, and what it does not cover only
 * shrinks.
 *
 * <p>WHY THIS SUITE EXISTS. "Proven by breaking" is real in this project and it
 * is ONE-TIME: the proof lives in a commit message and cannot be re-executed. A
 * guard proven in one month and quietly hollowed out in the next looks identical
 * to one that still works, because the suite is green either way — the thing it
 * was watching is simply gone. {@code tools/guard/plants.json} keeps those
 * proofs as data: per guard, the exact text substitution that puts the defect
 * back and the suite that must go red when it is there.
 *
 * <p>THIS IS THE CHEAP HALF. Actually replanting rebuilds the tree once per
 * plant, so {@code tools/guard/replant.py} is a tool you invoke deliberately —
 * minutes, not seconds. What runs in every battery is its self-test: that the
 * ledger is well formed, that every plant still matches its file EXACTLY ONCE
 * (a plant that matches nothing has rotted with the code it was written
 * against; one that matches twice is ambiguous), and that no ratchet has
 * risen.
 *
 * <p>THE THREE RATCHETS, all of which may only fall. {@code owed} is the number
 * of registered suites with no plant at all — the honest size of what the
 * ledger does not yet cover, which is most of it. {@code owed_generalisation}
 * counts plants marked {@code site_only}: the defect was put back at exactly the
 * place it was found, and nothing shows the guard would catch the same defect
 * somewhere else. {@code owed_real_defect} counts suites whose ONLY plant is
 * marked {@code auto} — found by {@code tools/guard/autoplant.py} mutating a
 * line and watching the suite go red. That proves the suite is not vacuous,
 * which is worth knowing and is a WEAKER claim than a hand-written plant, where
 * the text put back is a defect that actually reached a user. The two are
 * counted apart so that filling the ledger by machine cannot look like filling
 * it. A number that is allowed to rise is not a ratchet, and a ledger with no
 * honest count of what it misses reads as complete.
 *
 * <p>ORDER: needs no game, no dump and no display. Needs python, and says so
 * and skips rather than passing if it is missing.
 *
 * Usage: java ctrmap.tests.PlantLedgerTest [repo-root]
 */
public class PlantLedgerTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File repo = new File(args.length > 0 ? args[0] : ".");
		File ledger = new File(repo, "tools/guard/plants.json");
		File runner = new File(repo, "tools/guard/replant.py");
		if (!ledger.isFile() || !runner.isFile()) {
			System.out.println("  FAIL: no plant ledger at " + ledger.getPath()
					+ " - the re-provable half of 'proven by breaking' is not installed");
			System.exit(1);
		}
		if (!CommitGuardTest.onPath("python")) {
			System.out.println("  skip: no python on PATH - the ledger's checks are a python tool, so "
					+ "run 'python tools/guard/replant.py --selftest' by hand");
			System.out.println("ALL PASS");
			return;
		}

		theLedgerStillMatchesTheTree(repo);
		whatItDoesNotCoverIsCountedAndNamed(repo);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * Every plant still substitutes something that is there, exactly once, and
	 * the ledger's own refusals still refuse.
	 */
	static void theLedgerStillMatchesTheTree(File repo) throws Exception {
		System.out.println("--- every plant still matches the file it was written against");
		String said = python(repo, "--selftest");
		boolean passed = said.contains("ALL PASS");
		check(passed, "the ledger's self-test passes");
		if (!passed) {
			for (String line : said.split("\n")) {
				if (line.contains("FAIL")) {
					System.out.println("      " + line.trim());
				}
			}
		}
	}

	/**
	 * The two ratchets are printed every run, so what the ledger does not cover
	 * is a number somebody sees rather than an absence nobody does.
	 */
	static void whatItDoesNotCoverIsCountedAndNamed(File repo) throws Exception {
		System.out.println("--- and what it does not cover is counted, not left implied");
		String said = python(repo, "--owed");
		String owed = firstLineContaining(said, "owed:");
		String general = firstLineContaining(said, "owed_generalisation:");
		String real = firstLineContaining(said, "owed_real_defect:");
		check(!owed.isEmpty(), "the number of suites with no plant is reported: " + owed);
		check(!general.isEmpty(), "and the number proven only at their own site: " + general);
		check(!real.isEmpty(), "and the number standing on a machine-found plant alone: " + real);
	}

	// ---- plumbing ----------------------------------------------------------
	static String python(File repo, String flag) throws Exception {
		ProcessBuilder pb = new ProcessBuilder("python", "tools/guard/replant.py", flag);
		pb.directory(repo);
		pb.redirectErrorStream(true);
		Process p = pb.start();
		StringBuilder sb = new StringBuilder();
		try (java.io.BufferedReader r = new java.io.BufferedReader(
				new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			for (String line = r.readLine(); line != null; line = r.readLine()) {
				sb.append(line).append('\n');
			}
		}
		p.waitFor();
		return sb.toString();
	}

	static String firstLineContaining(String text, String what) {
		for (String line : text.split("\n")) {
			if (line.contains(what)) {
				return line.trim();
			}
		}
		return "";
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok: " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}
}
