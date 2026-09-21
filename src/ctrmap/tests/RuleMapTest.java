package ctrmap.tests;

import java.io.File;

/**
 * Every rule in CLAUDE.md has a mechanism, or is counted against a falling ceiling.
 *
 * <p>WHAT THIS EXISTS FOR, and it is the defect one level up from every other suite here. The
 * owner asked for every guard and every rule to be a refusal by class at the point of action.
 * The suites were measured and converted. THE RULES THEMSELVES WERE NEVER MEASURED AT ALL.
 *
 * <p>When they finally were, on 2026-09-20, <b>sixteen of the twenty-eight</b> rules in
 * capitals had no mechanism behind them - not a weak one, none. Among them GAME DATA IS
 * READ-ONLY, while a fixture had driven a pack against the live dump the day before; NEVER
 * PUSH WITHOUT EXPLICIT PER-PUSH APPROVAL; NEVER EDIT SOURCE WHILE THE SUITE IS RUNNING, after
 * two battery runs had already been invalidated that way; and A QUERY THAT CANNOT READ ITS
 * SUBJECT MUST NOT REPORT IT ABSENT, after 134 verdicts were discarded on the strength of a
 * probe that could not look.
 *
 * <p>Nobody had lied. Nobody had counted either, and the difference between twelve enforced
 * rules and twenty-eight was visible nowhere - which is this project's own rule about blanket
 * claims, aimed at itself: A CLAIM ABOUT WHAT YOU DID NOT OPEN IS A MEASUREMENT.
 *
 * <p>THE CHEAPEST WAY ROUND IT is to write an entry naming a mechanism that does not do what
 * the entry says. {@code rule_map.py} checks that the name exists in the tree, which stops the
 * emptiest version - a citation with a colon in it - and no more than that. What the map buys
 * is that the count is a number in a file rather than an impression.
 *
 * <p>ORDER: needs no game, no dump and no display. Needs python on PATH and says so and skips
 * rather than passing.
 *
 * Usage: java ctrmap.tests.RuleMapTest [repo-root]
 */
public class RuleMapTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File repo = new File(args.length > 0 ? args[0] : ".");
		File checker = new File(repo, "tools/guard/rule_map.py");
		if (!checker.isFile()) {
			System.out.println("  FAIL: no " + checker.getPath()
					+ " - nothing counts how many rules have a mechanism");
			System.exit(1);
		}
		if (!CommitGuardTest.onPath("python")) {
			System.out.println("  skip: python is not on PATH - the map is a python script, "
					+ "so this suite cannot run it here");
			System.out.println("ALL PASS");
			return;
		}

		everyRuleIsAccountedFor(repo, checker);
		aRuleWithNoEntryIsRefused(checker);
		aMechanismThatDoesNotExistIsRefused(checker);
		aClauseInsideAnEnforcedRuleIsRefused(checker);
		anUnreadableRuleFileIsNotAnEmptyOne(checker);

		System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
		if (fails != 0) {
			System.exit(1);
		}
	}

	/** The live answer, which is also the number any report about the rules has to cite. */
	static void everyRuleIsAccountedFor(File repo, File checker) throws Exception {
		System.out.println("--- every rule in CLAUDE.md is accounted for");
		String said = ask(checker, repo);
		if (said.contains("there is no CLAUDE.md")) {
			System.out.println("  note: this clone has no CLAUDE.md above it, so the live count "
					+ "cannot be taken. The three sections below are what pin the rule.");
			return;
		}
		check(said.contains("rules in CLAUDE.md"),
				"the map reports a count rather than an impression: "
				+ CommitGuardTest.firstLine(said));
		check(!said.contains("DO NOT AGREE"), "and the rules and their mechanisms agree");
	}

	/**
	 * A rule nobody has decided about is not enforced, and it is not exempt either.
	 *
	 * <p>Built in scratch, so this section says something on a clone with no CLAUDE.md at all.
	 */
	static void aRuleWithNoEntryIsRefused(File checker) throws Exception {
		System.out.println("--- a rule with no entry at all");
		File root = tree("**NEVER DO THE THING.** It costs.\n\n**ALWAYS COUNT FIRST.** It does too.\n",
				"{\"unenforced_ceiling\": 0, \"rules\": {"
				+ "\"NEVER DO THE THING\": {\"by\": \"tools/guard/rule_map.py\"}}}");
		String said = ask(checker, root);
		check(said.contains("ALWAYS COUNT FIRST") && said.contains("NO ENTRY"),
				"the rule nobody decided about is named: " + firstReason(said));
	}

	/** A citation naming nothing that exists is the same sentence with a colon in it. */
	static void aMechanismThatDoesNotExistIsRefused(File checker) throws Exception {
		System.out.println("--- a mechanism that is not in the tree");
		File root = tree("**NEVER DO THE THING.** It costs.\n",
				"{\"unenforced_ceiling\": 0, \"rules\": {"
				+ "\"NEVER DO THE THING\": {\"by\": \"tools/guard/no_such_tool.py\"}}}");
		String said = ask(checker, root);
		check(said.contains("no_such_tool.py") && said.contains("not in the tree"),
				"the invented mechanism is named: " + firstReason(said));

		//AND THE NEGATIVE HALF: a mechanism that IS there must be accepted.
		File sound = tree("**NEVER DO THE THING.** It costs.\n",
				"{\"unenforced_ceiling\": 0, \"rules\": {"
				+ "\"NEVER DO THE THING\": {\"by\": \"tools/guard/rule_map.py\"}}}");
		said = ask(checker, sound);
		check(said.contains("1 rules in CLAUDE.md") || said.contains("1 with a mechanism")
				|| said.contains("have a mechanism"),
				"while a real one passes: " + firstReason(said));
	}

	/**
	 * A clause inside a rule whose HEADLINE is enforced still has to have its own mechanism.
	 *
	 * <p>THE HOLE IN THIS CHECKER ITSELF, found on 2026-09-21. It reported <b>28 of 28</b>
	 * rules enforced. Inside the body of GAME DATA IS READ-ONLY - a rule it reported green,
	 * correctly, because {@code guard_game_data.py} does enforce the headline - sat three
	 * separate imperatives with nothing behind them at all:
	 *
	 * <p><i>"Never run Tidewater. Never {@code git stash}. Never hand-edit a NetBeans
	 * {@code initComponents} block."</i>
	 *
	 * <p>A rule can be enforced and its clauses unenforced at the same time, and the audit was
	 * built to look at exactly the level where that is invisible. A fourth, "Undo/redo
	 * everywhere", was invisible again one step further in, because the clause pattern only
	 * knew how to read a prohibition and that one is a requirement.
	 */
	static void aClauseInsideAnEnforcedRuleIsRefused(File checker) throws Exception {
		System.out.println("--- a clause inside a rule whose headline is enforced");
		File root = tree("**NEVER DO THE THING.** It costs. Never run Tidewater.\n",
				"{\"unenforced_ceiling\": 0, \"rules\": {"
				+ "\"NEVER DO THE THING\": {\"by\": \"tools/guard/rule_map.py\"}}}");
		String said = ask(checker, root);
		check(said.contains("Tidewater") && said.contains("NO ENTRY of its own"),
				"the unmapped clause is named: " + firstReason(said));
		check(said.contains("enforced and its clauses unenforced"),
				"...and it says why a green headline is not an answer");

		//AND THE NEGATIVE HALF: a clause WITH a mechanism must not be refused.
		File mapped = tree("**NEVER DO THE THING.** It costs. Never run Tidewater.\n",
				"{\"unenforced_ceiling\": 0, \"rules\": {"
				+ "\"NEVER DO THE THING\": {\"by\": \"tools/guard/rule_map.py\","
				+ " \"clauses\": {\"Never run Tidewater\": \"tools/guard/rule_map.py\"}}}}");
		said = ask(checker, mapped);
		check(said.contains("have a mechanism"),
				"while a clause with a mechanism passes: " + firstReason(said));

		//...and a clause citing something that is not there is refused like any other claim.
		File invented = tree("**NEVER DO THE THING.** It costs. Never run Tidewater.\n",
				"{\"unenforced_ceiling\": 0, \"rules\": {"
				+ "\"NEVER DO THE THING\": {\"by\": \"tools/guard/rule_map.py\","
				+ " \"clauses\": {\"Never run Tidewater\": \"tools/guard/nothing.py\"}}}}");
		said = ask(checker, invented);
		check(said.contains("not in the") || said.contains("nothing.py"),
				"and a clause citing nothing real is refused too: " + firstReason(said));
	}

	/**
	 * A rule file it cannot read is UNKNOWN, and an unknown is not an empty one.
	 *
	 * <p>The same distinction that cost 134 verdicts here when a probe that could not look
	 * reported twelve live workers gone.
	 */
	static void anUnreadableRuleFileIsNotAnEmptyOne(File checker) throws Exception {
		System.out.println("--- a rule file that is not there");
		File root = Scratch.dir("rulemap-bare");
		String said = ask(checker, root);
		check(said.contains("no CLAUDE.md"),
				"a missing rule file is reported, not passed: " + firstReason(said));

		File noMap = tree("**NEVER DO THE THING.** It costs.\n", null);
		said = ask(checker, noMap);
		check(said.contains("UNKNOWN") || said.contains("cannot read"),
				"and a missing map is UNKNOWN, not clean: " + firstReason(said));
	}

	// ---------------------------------------------------------------- fixtures

	/** A scratch tree with its own CLAUDE.md and, optionally, its own map. */
	static File tree(String rules, String map) throws Exception {
		File root = Scratch.dir("rulemap");
		java.nio.file.Files.write(new File(root, "CLAUDE.md").toPath(),
				rules.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		File guard = new File(root, "tools/guard");
		guard.mkdirs();
		//: a file for a sound entry to point at, so the negative half has something real
		java.nio.file.Files.write(new File(guard, "rule_map.py").toPath(),
				"#a real file\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		if (map != null) {
			java.nio.file.Files.write(new File(guard, "rule_map.json").toPath(),
					map.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		return root;
	}

	static String ask(File checker, File root) throws Exception {
		ProcessBuilder pb = new ProcessBuilder("python", checker.getAbsolutePath(),
				root.getAbsolutePath());
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String said = CommitGuardTest.drain(p);
		p.waitFor();
		if (said.contains("Traceback")) {
			System.out.println("  FAIL: the map checker crashed - " + firstReason(said));
			fails++;
		}
		return said;
	}

	static String firstReason(String said) {
		for (String line : said.split("\n")) {
			String t = line.trim();
			if (t.startsWith("- ") || t.contains("rules in CLAUDE.md")) {
				return t.length() > 140 ? t.substring(0, 140) : t;
			}
		}
		return CommitGuardTest.firstLine(said);
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok: " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}
}
