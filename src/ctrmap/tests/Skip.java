package ctrmap.tests;

/**
 * What a suite did NOT do, said in the one shape the battery counts (F144).
 *
 * <p>MEASURED 2026-10-06 by running all 136 registered suites with no game data: 79 exited 0,
 * and 50 of those had printed that they skipped their data checks first - a "skip" line, then
 * "ALL PASS", then exit 0, which test.ps1 counted as a pass. Six of them carried a javadoc
 * promising they "skip rather than pass" above code that did the opposite. A skip is UNKNOWN,
 * never passed: a suite that could not run at all ends with {@link #EXIT}, and a section that
 * could not run prints {@link #MARK}, which test.ps1 and tools/guard/record_run.py read - so a
 * battery with a skip in it never says ALL SUITES PASS.
 *
 * <p>NOT FOR A DEFECT. A record the suite guards that is missing, a corpus that no longer has
 * the shape the suite was written for, an argument naming nothing: those are failures, and
 * reporting one through here is the cheapest way round this class.
 *
 * <p>Not: {@link Scratch} - it makes the folders a suite works in; this says what it did not do.
 */
final class Skip {

	/** The exit code of a suite that ran nothing it was written for. */
	static final int EXIT = 3;

	/** The marker the battery reads in a suite's output: something here did not run. */
	static final String MARK = "SKIPPED: ";

	private Skip() {
	}

	/** Says a section did not run, and why. The suite carries on; the battery counts it skipped. */
	static void section(String why) {
		System.out.println("  " + MARK + why);
	}

	/** Ends a suite that could not run at all: says why, and exits {@link #EXIT}. */
	static void suite(String why) {
		section(why);
		System.out.println("SKIPPED - UNKNOWN, NOT PASSED");
		System.exit(EXIT);
	}
}
