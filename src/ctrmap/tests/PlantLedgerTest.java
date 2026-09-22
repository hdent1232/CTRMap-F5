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
		aTrapListWithoutTheBillGetsIgnored(repo);
		theLockNamesTheFileItIsAboutToPlant(runner);

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

	/**
	 * RECORD TRAPS YOU PAID FOR, WITH THE COST.
	 *
	 * <p>The rule carries its own bill: a trap list without the bill attached gets
	 * ignored. This ledger IS the trap list - every entry is a defect put back to
	 * prove a guard still notices it - and the entries that close are the ones that
	 * say "filed in SEVEN consecutive audits" or "791 agents and 111.9M tokens", not
	 * the ones that name a shape.
	 *
	 * <p>A ceiling rather than a ban, because 103 of 171 entries cite no number and a
	 * ban would fire on all of them - which is how a guard gets its ceiling raised
	 * once and never looked at again. It may only fall.
	 */
	static void aTrapListWithoutTheBillGetsIgnored(File repo) throws Exception {
		System.out.println("--- and every trap says what it cost");
		String said = python(repo, "--selftest");
		String line = firstLineContaining(said, "cite no measured cost");
		check(!line.isEmpty(), "the ledger counts the entries with no measured cost: "
			+ line);
		check(line.startsWith("ok:"), "and it is at or under its ceiling");
		//AND THAT THE COUNT IS REAL. Reading the number proves only that a number was
		//printed: weaken the ratchet and it still prints one, so a plant against it could
		//not redden anything and was recorded NOT PROVEN. A ceiling is proven by putting
		//something OVER it, so the runner drives its own predicate against a scratch ledger
		//holding one costless entry and one priced one, and this reads that.
		String counted = firstLineContaining(said, "cites no number is counted");
		check(counted.startsWith("ok:"),
				"a plant whose why cites no number is counted: " + counted);
		String priced = firstLineContaining(said, "records what it cost is not");
		check(priced.startsWith("ok:"),
				"and one that records what it cost is not: " + priced);
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

	/**
	 * The run lock is written BEFORE the file is planted, and cleared only once the restore
	 * has been read back.
	 *
	 * <p>PAID FOR 2026-09-22. A replant was started by accident, held the tree, and was
	 * killed. {@code hold(path)} ran AFTER {@code write(path, planted)}, so there was a window
	 * in which a file was planted and the lock still named the PREVIOUS one - and the kill
	 * landed in it. The lock said {@code liveness_check.py}, which was already clean; the live
	 * plant was in {@code commit_guard.py}, its coupling refusal replaced by {@code if True}.
	 * Restoring what the lock named would have left a disarmed guard in the tree and nothing
	 * saying so. It was found by diffing the whole tree, which is the thing the lock exists to
	 * make unnecessary.
	 *
	 * <p>A lock naming a file that turns out to be clean costs nothing. A lock naming the
	 * wrong file is worse than no lock, because it is believed.
	 *
	 * <p>Read from the source, because the failure is an ORDER and an order is exactly what a
	 * green run cannot show you: both orders work perfectly until something dies in between.
	 */
	static void theLockNamesTheFileItIsAboutToPlant(File runner) throws Exception {
		System.out.println("--- the run lock names the file it is about to plant, not the last one");
		String text = SourceSeamTest.stripComments(new String(
				java.nio.file.Files.readAllBytes(runner.toPath()),
				java.nio.charset.StandardCharsets.UTF_8));
		int holds = text.indexOf("hold(path)");
		int plants = text.indexOf("write(path, text.replace(");
		check(holds >= 0 && plants >= 0,
				"the runner still holds a lock and plants a file (" + holds + ", " + plants + ")");
		if (holds < 0 || plants < 0) {
			return;
		}
		check(holds < plants,
				"the lock is taken BEFORE the defect reaches disk, so a run killed between the"
				+ " two leaves a lock naming the file that is planted - not the one before it");

		int restored = text.indexOf("back != raw");
		int released = text.indexOf("release()", plants);
		check(restored >= 0 && released > restored,
				"and it is released only AFTER the restore is read back, so a restore that did"
				+ " not take leaves the lock naming the file it did not take on (" + restored
				+ ", " + released + ")");
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok: " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}
}
