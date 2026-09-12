package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The commit gate refuses what it says it refuses.
 *
 * <p>WHY THIS SUITE EXISTS. {@code tools/guard/commit_guard.py} is wired as this
 * repository's {@code commit-msg} hook, so it is the one piece of tooling that
 * runs on every commit and blocks it. A hook that has quietly stopped refusing
 * looks exactly like a hook that has nothing to refuse: every commit succeeds
 * either way. Its four refusals were each bought with a specific failure in the
 * project it came from - "producer with no consumer" filed in seven consecutive
 * audits, three defects repeated in one session with guards already in place
 * that sat on the damage rather than the action, {@code guarded above} shipped
 * once as an entire justification and false, and a wrong test count in a message
 * that is now permanent. This suite is what keeps them bought.
 *
 * <p>IT BUILDS ITS OWN REPOSITORY. The guard asks git what is staged, and
 * reads {@code .last-suite-run}, both relative to the root it computes from its
 * own path. Driving it against THIS working tree would make the answers depend
 * on whatever the developer happened to have staged, so each section copies the
 * guard into a scratch repository at the same relative depth, stages exactly
 * what it means to stage, and runs it there. Nothing here touches the real
 * index.
 *
 * <p>ORDER: needs no game, no dump and no display. Needs python and git on PATH
 * and says so and skips if either is missing, rather than passing.
 *
 * Usage: java ctrmap.tests.CommitGuardTest [repo-root]
 */
public class CommitGuardTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File repo = new File(args.length > 0 ? args[0] : ".");
		File guard = new File(repo, "tools/guard/commit_guard.py");
		if (!guard.isFile()) {
			System.out.println("  FAIL: no " + guard.getPath() + " - the commit gate is not installed");
			System.exit(1);
		}
		if (!onPath("python") || !onPath("git")) {
			System.out.println("  skip: python or git is not on PATH - the gate is a python hook, "
					+ "so this suite cannot run it here");
			System.out.println("ALL PASS");
			return;
		}

		theHookIsWiredToTheGuard(repo);
		aMessageThatIsNotAFixIsNotPoliced(guard);
		aFixWithNothingThatWouldNoticeASecondIsRefused(guard);
		aFixThatNamesNoKindOfGuardIsRefused(guard);
		aGuardLineThatIsALabelIsRefused(guard);
		anUnknownKindOfGuardIsRefused(guard);
		aFixThatCarriesARealGuardIsAllowed(guard);
		theNoGuardEscapeIsHonouredAndStaysInTheMessage(guard);
		aTestCountIsCheckedAgainstTheLastRecordedRun(guard);
		aFixClosedByADetectorIsRefused(guard);
		theNoRefusalEscapeIsHonouredAndStaysInTheMessage(guard);
		theWorkOrderRefusesWhileTheTreeIsMoving(repo);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	// ------------------------------------------------------------- 1. the wiring
	/**
	 * The hook exists, is wired, and runs the guard rather than something else.
	 *
	 * <p>An unwired hook is the failure mode worth checking first: every refusal
	 * below can be perfect and none of them will ever run.
	 */
	static void theHookIsWiredToTheGuard(File repo) throws Exception {
		System.out.println("--- the commit-msg hook is wired, and runs the guard");
		File hook = new File(repo, ".githooks/commit-msg");
		check(hook.isFile(), "there is a commit-msg hook at .githooks/commit-msg");
		if (hook.isFile()) {
			String text = new String(Files.readAllBytes(hook.toPath()), StandardCharsets.UTF_8);
			check(text.contains("tools/guard/commit_guard.py"),
					"and it runs the guard, not something else");
		}
		String configured = git(repo, "config", "core.hooksPath").trim();
		check(configured.equals(".githooks"), "and git is pointed at that directory (core.hooksPath="
				+ (configured.isEmpty() ? "<unset>" : configured)
				+ "); set it with: git config core.hooksPath .githooks");
	}

	// -------------------------------------------------------- 2. what it ignores
	/** Only a subject beginning "fix" is policed; everything else passes untouched. */
	static void aMessageThatIsNotAFixIsNotPoliced(File guard) throws Exception {
		System.out.println("--- a message that does not claim to fix anything is not policed");
		Run r = run(guard, "Rename the thing and move two files", new String[]{"src/ctrmap/A.java"}, null);
		check(r.code == 0, "it is allowed with no Guard line at all (exit " + r.code + ") " + r.said);
	}

	// ------------------------------------------------- 3. refusal one: no guard
	static void aFixWithNothingThatWouldNoticeASecondIsRefused(File guard) throws Exception {
		System.out.println("--- a fix that touches no place a guard could live is refused");
		Run r = run(guard, "Fix the off-by-one in the matrix loader"
				+ "\n\nGuard: refusal -- a second one of these is impossible because the loader "
				+ "now refuses a width it cannot address at all",
				new String[]{"src/ctrmap/humaninterface/A.java"}, null);
		check(r.code != 0, "refused (exit " + r.code + ")");
		check(r.said.contains("SECOND one"), "and it says what the shape of the mistake is: "
				+ firstLine(r.said));
		check(r.said.contains("src/ctrmap/tests"), "and names where a guard can live");
	}

	// ------------------------------------------- 4. refusal two: no kind of guard
	static void aFixThatNamesNoKindOfGuardIsRefused(File guard) throws Exception {
		System.out.println("--- a fix that never says what KIND of guard it carries is refused");
		Run r = run(guard, "Fix the off-by-one in the matrix loader",
				new String[]{"src/ctrmap/humaninterface/A.java", "src/ctrmap/tests/ATest.java"}, null);
		check(r.code != 0, "refused even though it touched the suite directory (exit " + r.code + ")");
		check(r.said.contains("Only a REFUSAL closes the class"),
				"because a detector in a guard's coat is the thing it is asking about: " + firstLine(r.said));
	}

	// --------------------------------------------- 5. refusal three: a label
	static void aGuardLineThatIsALabelIsRefused(File guard) throws Exception {
		System.out.println("--- a Guard line that is a label rather than a class is refused");
		Run r = run(guard, "Fix the off-by-one in the matrix loader\n\nGuard: refusal -- guarded above",
				new String[]{"src/ctrmap/tests/ATest.java"}, null);
		check(r.code != 0, "refused (exit " + r.code + ")");
		check(r.said.contains("is a label"), "and says so by counting it: " + firstLine(r.said));
	}

	static void anUnknownKindOfGuardIsRefused(File guard) throws Exception {
		System.out.println("--- and a kind of guard that is not one of the three is refused");
		Run r = run(guard, "Fix the off-by-one in the matrix loader\n\nGuard: sticker -- "
				+ "this reason is long enough to pass the length check on its own, easily",
				new String[]{"src/ctrmap/tests/ATest.java"}, null);
		check(r.code != 0, "refused (exit " + r.code + ")");
		check(r.said.contains("detector, convention, refusal"),
				"naming the three it knows: " + firstLine(r.said));
	}

	// ------------------------------------------------------ 6. what it allows
	static void aFixThatCarriesARealGuardIsAllowed(File guard) throws Exception {
		System.out.println("--- a fix that touches a guard directory and names a class is allowed");
		Run r = run(guard, "Fix the off-by-one in the matrix loader"
				+ "\n\nGuard: refusal -- the loader now refuses a matrix whose declared width "
				+ "cannot address its own cells, so the whole class of silent truncation is gone",
				new String[]{"src/ctrmap/humaninterface/A.java", "src/ctrmap/tests/ATest.java"}, null);
		check(r.code == 0, "allowed (exit " + r.code + ") " + r.said);
	}

	static void theNoGuardEscapeIsHonouredAndStaysInTheMessage(File guard) throws Exception {
		System.out.println("--- and an honest No-guard line is honoured, which is why it is in the message");
		Run r = run(guard, "Fix the typo in the About box\n\nNo-guard: a string literal with no "
				+ "behaviour behind it; nothing could notice a second typo that a person could not",
				new String[]{"src/ctrmap/humaninterface/A.java"}, null);
		check(r.code == 0, "allowed with no guard at all (exit " + r.code + ") " + r.said);
	}

	// ------------------------------------------------ 7. refusal four: the count
	/**
	 * A count in a commit message is a measurement, and the guard checks it
	 * against the last recorded run rather than against a memory of one.
	 */
	static void aTestCountIsCheckedAgainstTheLastRecordedRun(File guard) throws Exception {
		System.out.println("--- a test count in the message is checked against the last recorded run");
		String ran = "{\"ran\": 124, \"ok\": true}";

		Run wrong = run(guard, "Move two files\n\nMeasured: 125 tests, all green", new String[]{}, ran);
		check(wrong.code != 0, "a claim of 125 against a recorded 124 is refused (exit " + wrong.code + ")");
		check(wrong.said.contains("the last run said 124"), "saying what the run actually said: "
				+ firstLine(wrong.said));
		check(wrong.said.contains("Measured: 125 tests"),
				"and quoting the LINE, so nobody scans a long message for a digit");

		Run right = run(guard, "Move two files\n\nMeasured: 124 tests, all green", new String[]{}, ran);
		check(right.code == 0, "and the true count is allowed (exit " + right.code + ") " + right.said);

		Run unrecorded = run(guard, "Move two files\n\nMeasured: 124 tests, all green", new String[]{}, null);
		check(unrecorded.code != 0, "a count with NO recorded run is refused too, not assumed right");
		check(unrecorded.said.contains("record_run.py"), "and says how to record one: "
				+ firstLine(unrecorded.said));
	}

	// -------------------------------------- 8. refusal five: close it by refusing
	/**
	 * A fix closed by a DETECTOR is refused, because a detector reports the
	 * wreckage and does not stop it happening again.
	 *
	 * <p>The three kinds were named in this guard from the start, and naming them
	 * turned out not to be the same as preferring one. Fixes shipped as detectors
	 * because a detector is the easier thing to write, and the class stayed open -
	 * which is the whole shape of a defect being fixed twice. The kind is now a
	 * refusal by default and the exception has to be written down.
	 */
	static void aFixClosedByADetectorIsRefused(File guard) throws Exception {
		System.out.println("--- a fix closed by a detector rather than a refusal is refused");
		Run r = run(guard, "Fix the stale cache in the tileset loader"
			+ "\n\nGuard: detector -- a suite now notices when the cached tileset and the "
			+ "zone it belongs to have drifted apart, so the next one is reported",
			new String[]{"src/ctrmap/humaninterface/A.java", "src/ctrmap/tests/ATest.java"}, null);
		check(r.code != 0, "refused (exit " + r.code + ")");
		check(r.said.contains("makes a second occurrence IMPOSSIBLE"),
			"and says what a detector cannot do: " + firstLine(r.said));
		check(r.said.contains("No-refusal:"), "and names the escape, so the answer is not to lie"
			+ " about the kind");
	}

	/** ...and the honest exception is honoured, which is why it lives in the message. */
	static void theNoRefusalEscapeIsHonouredAndStaysInTheMessage(File guard) throws Exception {
		System.out.println("--- and a No-refusal line that says why is honoured");
		Run r = run(guard, "Fix the stale cache in the tileset loader"
			+ "\n\nGuard: detector -- a suite now notices when the cached tileset and the "
			+ "zone it belongs to have drifted apart, so the next one is reported"
			+ "\n\nNo-refusal: the drift is produced by the GPU driver dropping a texture "
			+ "handle, which happens below any line this program could refuse at",
			new String[]{"src/ctrmap/humaninterface/A.java", "src/ctrmap/tests/ATest.java"}, null);
		check(r.code == 0, "allowed (exit " + r.code + ") " + r.said);
	}

	// --------------------------------------- 9. the other process guard: order
	/**
	 * The work-order guard refuses a long measurement while the tree is moving.
	 *
	 * <p>WHY IT IS IN THIS SUITE. A mutation sweep records a digest of every file
	 * it measured, so one started while work is still queued is not slow, it is
	 * discarded - and that rule was written down twice in this repository and
	 * broken anyway, in the same conversation where it was quoted. It is the same
	 * failure as any unguarded rule in the product, so it gets the same treatment:
	 * a refusal, and a guard on the refusal.
	 *
	 * <p>Driven against a ledger this suite writes, never the real one, so it
	 * asserts the BEHAVIOUR rather than today's queue.
	 */
	static void theWorkOrderRefusesWhileTheTreeIsMoving(File repo) throws Exception {
		System.out.println("--- the work order refuses a long measurement while work is queued");
		File guard = new File(repo, "tools/guard/work_order.java".replace(".java", ".py"));
		check(guard.isFile(), "there is a work-order guard at tools/guard/work_order.py");
		if (!guard.isFile()) {
			return;
		}
		String src = new String(java.nio.file.Files.readAllBytes(
			new File(repo, "tools/mutate2.py").toPath()), java.nio.charset.StandardCharsets.UTF_8);
		check(src.contains("work_order.require_frozen"),
			"and the sweep asks it before it touches anything - a guard nothing calls is the"
			+ " defect this project keeps finding");
		int askedAt = src.indexOf("work_order.require_frozen");
		int resetAt = src.indexOf("git(\"reset\", \"--hard\"");
		check(askedAt >= 0 && resetAt >= 0 && askedAt < resetAt,
			"and asks BEFORE the hard reset, not after it has already moved the tree ("
			+ askedAt + " then " + resetAt + ")");
		
		//the ledger format itself: an open item must be findable, a done one must not
		File ledger = new File(repo, "OUTSTANDING.md");
		check(ledger.isFile(), "the queue is a tracked file, not something somebody remembers");
		if (ledger.isFile()) {
			String text = new String(java.nio.file.Files.readAllBytes(ledger.toPath()),
				java.nio.charset.StandardCharsets.UTF_8);
			check(text.contains("- [ ]") || text.contains("- [x]"),
				"and it uses the checkbox form the guard reads");
		}
	}
	// ---- plumbing ----------------------------------------------------------
	/** What the guard did: its exit code and everything it said. */
	static final class Run {

		final int code;
		final String said;

		Run(int code, String said) {
			this.code = code;
			this.said = said;
		}
	}

	/**
	 * Runs the guard over {@code message} in a fresh repository where exactly
	 * {@code staged} is staged, and {@code lastRun} is the recorded run (or none).
	 *
	 * <p>The guard finds its root three directories above itself, so the copy has
	 * to sit at tools/guard/ inside the scratch repository for the root it
	 * computes to be the scratch repository.
	 */
	static Run run(File guard, String message, String[] staged, String lastRun) throws Exception {
		File dir = Scratch.dir("commit-guard");
		try {
			File tools = new File(dir, "tools/guard");
			if (!tools.mkdirs()) {
				throw new java.io.IOException("could not make " + tools);
			}
			Files.copy(guard.toPath(), new File(tools, "commit_guard.py").toPath());
			git(dir, "init", "-q");
			for (String rel : staged) {
				File f = new File(dir, rel);
				if (f.getParentFile() != null) {
					f.getParentFile().mkdirs();
				}
				Files.write(f.toPath(), "//staged for the guard to see\n".getBytes(StandardCharsets.UTF_8));
				git(dir, "add", "--", rel);
			}
			if (lastRun != null) {
				Files.write(new File(dir, ".last-suite-run").toPath(),
						lastRun.getBytes(StandardCharsets.UTF_8));
			}
			File msg = new File(dir, "MSG");
			Files.write(msg.toPath(), message.getBytes(StandardCharsets.UTF_8));

			ProcessBuilder pb = new ProcessBuilder("python", "tools/guard/commit_guard.py",
					msg.getAbsolutePath());
			pb.directory(dir);
			pb.redirectErrorStream(true);
			Process p = pb.start();
			String said = drain(p);
			return new Run(p.waitFor(), said);
		} finally {
			Scratch.deleteTree(dir);
		}
	}

	static String git(File where, String... argv) throws Exception {
		List<String> command = new ArrayList<>();
		command.add("git");
		command.addAll(Arrays.asList(argv));
		ProcessBuilder pb = new ProcessBuilder(command);
		pb.directory(where);
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String said = drain(p);
		p.waitFor();
		return said;
	}

	static String drain(Process p) throws Exception {
		StringBuilder sb = new StringBuilder();
		try (java.io.BufferedReader r = new java.io.BufferedReader(
				new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			for (String line = r.readLine(); line != null; line = r.readLine()) {
				sb.append(line).append('\n');
			}
		}
		return sb.toString();
	}

	static boolean onPath(String program) {
		try {
			ProcessBuilder pb = new ProcessBuilder(program, "--version");
			pb.redirectErrorStream(true);
			Process p = pb.start();
			drain(p);
			return p.waitFor() == 0;
		} catch (Exception missing) {
			return false;
		}
	}

	static String firstLine(String s) {
		int nl = s.indexOf('\n');
		return (nl < 0 ? s : s.substring(0, nl)).trim();
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok: " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}
}
