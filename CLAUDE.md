# CLAUDE.md - standing rules

Installed 2026-09-14 into the load path. The rules below existed since 2026-09-07 in
`Desktop/verification-bootstrap/CLAUDE.md`, which is the TEMPLATE and is not a parent of this
working directory, so it never autoloaded and was never in force. The fan-out hook from that
bundle was installed (`.claude/hooks/guard_fanout.py`, wired in `.claude/settings.json`); this
file is the half that was missed. Section 1 is verbatim from the bundle; section 2 is this
owner's standing rules, which until today lived only in the memory directory.

---

## 1. From the verification bootstrap (verbatim)


**MEASURE, NEVER REASON FROM PLAUSIBILITY.** Before claiming a cause, count. Four consecutive
wrong theories about one bug were each abandoned only when someone finally measured. A probe
that reports a confident ZERO is the dangerous result — it looks like a clean negative. Twice a
scan returned 0 across 536 files because it read the wrong struct field. **Any scan you write
must include a known-answer control before its result is believed.**

**A FIX WITHOUT A GUARD IS NOT A FIX.** Reproduce first. Then a test that FAILS on the current
code and PASSES after the fix — record the failing output. Then make the silent path loud or
impossible. Fixing the instance is half the work; the other half is making the second one
impossible.

The cost of skipping it, measured: "producer with no consumer" was filed in **seven consecutive
audits**. "A guard at call sites is one call site from broken" was paid for **four separate
times**. A date-format rule existed **twenty-five times as five different behaviours**, twelve of
which printed an error message forbidding exactly what they then accepted. Every one was fixed
when found. None was made impossible, so every one came back.

**PROVE EVERY GUARD BY BREAKING IT.** Apply the break by hand, rebuild, watch the guard fail,
restore, watch it pass. An agent once claimed two mutation kills that did not hold up — it had
asserted the callee while the surviving line was the GUI call site. **A test that merely touches
a line does not guard it.**

**A CONFIDENT EMPTY RESULT IS A BUG UNTIL PROVEN OTHERWISE.** See rule one.

**COUNT WHAT YOU EXPECT BEFORE RUNNING THE CHANGE.** Nearly every self-inflicted error was caught
by arithmetic, never by re-reading: a regex that rewrote a function's own body so it returned
itself (*the linter passed it*); an edit that inserted a constant *before* doing a replacement,
so the replacement rewrote the definition; `grep -c` exiting 1 on zero matches and
short-circuiting an `&&` chain so a step never ran — **a command that did not run looks exactly
like a command that passed.**

**A STARTLING MAGNITUDE IS A BUG REPORT** until proven otherwise, in either direction.

**BUILD GENERAL SYSTEMS, NOT THE USER'S EXAMPLE.** The user describes what they personally would
do as an illustration. The deliverable is the capability for the whole class.

**DETANGLE, DO NOT RELOCATE.** Splitting a 3,000-line god object into ten 300-line files whose
pieces still read and write the same shared state is worse — the tangle is now in ten places.
The metric is **coupling, not line count**: count what a span depends on before and after; each
extracted piece must depend on a strict subset, and total edges must go DOWN, or leave it.

**EXTRACTION IS NOT TESTING.** Two modules split out of one function in the same refactor scored
**94.7%** and **37.3%**. The only difference was that tests were deliberately written for one of
them afterwards. Decomposition makes code *reachable* for testing, which is not the same as
tested.

**NEVER HAND-EDIT A MEASUREMENT.** If a baseline, digest or count is stale, re-run the thing that
produces it. Updating a recorded number without measuring is the quiet lie the file exists to
prevent.

**COMMIT BEFORE RUNNING ANYTHING THAT MUTATES SOURCES.** The mutation harness ends in
`git reset --hard`. It nearly erased an uncommitted fix.

**NEVER EDIT SOURCE WHILE THE SUITE IS RUNNING.** The result is neither the old code nor the new
one, and it looks green. Two full runs were invalidated this way.

**RECORD TRAPS YOU PAID FOR, WITH THE COST.** A trap list without the bill attached gets ignored.
See §10.

**NOTHING IS FIXED UNTIL THE SECOND ONE IS IMPOSSIBLE, AND THE RULE IS THE AUTHORISATION.** When
the class is understood, close it — do not stop and ask whether to. Permission already given does
not need asking for again, and asking is how a known class stays open for another week. The
answer to "should I fix this" is in this file.

**ASK WHAT THE CHEAPEST WAY TO SATISFY YOUR OWN GUARD IS, AND REFUSE THAT TOO.** Every guard
creates an incentive to satisfy it cheaply, and the cheap way is usually to do less work rather
than more. A rule that forbids REPORTING a hole is satisfied fastest by not looking for one. See
§14, which is nothing but the list of ways this has been done here.

**AN UNMEASURABLE QUANTITY IS NOT A SMALL ONE.** Refuse it. A cap that reads an absent number as
zero is not a cap: a fan-out hook measured width from the launch arguments, three launches kept
their fan-out in the script body instead, and 791 agents and 111.9M tokens went through a cap of
six — while the hook was installed, wired, and running.

**A QUERY THAT CANNOT READ ITS SUBJECT MUST NOT REPORT IT ABSENT.** Every probe has an answer for
"it is not there" and an answer for "I could not look", and code that collapses the second into
the first reports a clean negative. `os.kill(pid, 0)` on Windows routes through
`OpenProcess(PROCESS_TERMINATE)`, so a process this account may not terminate reads as dead — it
called three live processes dead here, one of them the sweep runner, and the function deciding
whether to CLEAR a lock was one of its callers. That was found, written down, and closed by a
checker over the project's own Python. The same mistake then arrived in a shell command the
checker cannot see: `Where-Object { $_.CommandLine -match ... }` matched nothing, because
`CommandLine` is empty for a process this account cannot open, and twelve live workers were
reported gone. Acting on that belief discarded **134 verdicts**. Uncertainty is ALIVE, PRESENT,
UNKNOWN — never absent.

**A REMEDY'S EXIT CODE ANSWERS ITS OWN QUESTION, NOT YOURS.** When a check refuses and names a
fix, run the fix and then ASK THE CHECK AGAIN. A map rebuild wrote its 10.7 MB output and exited
1 — because the exit code reported the *suite it had run under coverage*, not whether a map was
produced — and the runner read that as a failed remedy, discarded 49 minutes of work, and sat for
**thirty-three hours** having decided nothing. Nor may the result be recorded against the wrong
subject: after the next fix, the runner performed remedy A, was refused for remedy B, and wrote
down that B does not help — barring the fix it was being asked for. **A second refusal naming a
DIFFERENT remedy is progress, not the same wall.**

**A RUN THAT KNOWS WHAT IT MISSED GOES BACK.** Never end by naming what you did not cover and
handing it over. If it genuinely cannot be closed now, say why in the commit, where it stays
visible and can be disagreed with.

**A CLAIM ABOUT WHAT YOU DID NOT OPEN IS A MEASUREMENT.** Saying the rest are fine, the others
are unaffected, everything else holds - that is a count, and it needs the command that produced
it. A review of one project's guards read fourteen of them and wrote "everything else reviewed
holds its shape" about 130 suites and 150 plants; nobody lied, and the difference between
fourteen and a hundred and fifty was visible nowhere. Cite the measurement or do not make the
claim.

**DO THE WORK MECHANICALLY; AN AGENT FAN-OUT NEEDS A YES AND A PRICE.** Greps, scripts, builds and
sweeps cost time; subagents cost a metered plan. Work in the main thread by default, and before
any fan-out state the agent count and the token estimate and get an answer. Three projects have
now lost most of an allowance to this, twice through a hook that was supposed to stop it.


**AGENTS THAT CANNOT EDIT EACH OTHER'S FILES MUST NOT EDIT AT THE SAME TIME.** This is not the
fan-out cap and narrowing the cap does not help: six agents owning disjoint files across a
shared contract fail exactly as sixty do, and unlimited tokens make it worse because more agents
means more seams. Measured in one project's own documents: eleven audit rounds, **476 finding
headlines over 41 distinct code locations**, rounds 11 and 13 finding nothing the earlier ones
had not, six files named by ten or eleven separate rounds. Its fix pass left behind a handover
file whose first line is *"Each agent owned a disjoint file set. These are the changes they
identified but could not make, because the change belongs in another agent's file"* - and one
of those changes was a renamed output key whose consumer lived elsewhere, so the fix was made
and **never reached the user**. Fan out to READ, bring the findings back to one writer; a survey
composes, a parallel rewrite does not.

**A GUARD'S TRIGGER IS A CLASS, NOT A LIST OF NAMES.** Eight hooks were wired under
`"matcher": "Bash"` while the session also offered a `PowerShell` tool, and five of them opened
by exempting any tool not called `Bash`. All eight were installed, wired, running and **off**
for every command issued through the other tool; nobody bypassed anything. Ask a SHAPE - does
this tool input carry a command - and wire one entry over a dispatcher that DISCOVERS its guards
rather than listing them, because a list is the same defect one level up.

**A RECORDED MEASUREMENT MUST CARRY THE SUBJECT IT WAS TAKEN AGAINST.** A number in a file is not
a fact about your tree; it is a fact about SOME tree, and which one is exactly what a bare number
does not say. A commit-message rule checked every "N tests" claim against the last recorded run
and never asked what that run had measured: the record on disk was **eight days and twenty-two
changed files old**, so the rule would have approved a claim of the stale count and refused the
true one, and said nothing either way. A sibling function ten lines away had digested its own
subject since the day it was written — one measurement covered, its neighbour not. Store the
digest beside the number, refuse when it does not match, and treat a subject you cannot compute
as UNKNOWN rather than as unchanged. **The record must not be part of its own subject** — exclude
it by SHAPE, because excluding it by ignore-rule holds in your repository and not in the scratch
one your suite builds.

---

## 2. This owner's standing rules

**DO THE WORK MECHANICALLY. AGENT SWEEPS ARE THE LAST RESORT.** Greps, scripts, builds, suites and
mutation runs cost time; subagents cost a metered plan. Work in the main thread with no agent at
all. A fan-out needs the owner's explicit yes, with the agent count and a token estimate stated
first. The bill for ignoring this, three times: **2026-08-17** DT-Engine, 26 workflow runs, ~97M
subagent tokens, 96% of a weekly allowance. **2026-09-02** CTRMap, ~5.3M tokens in half an hour,
~90% of a 5-hour allowance, session limit hit twice. **2026-09-14** CTRMap, 3 workflows, 791
agents, 111.9M tokens, a full week of a $200 plan, for a census that still would not close.
Enforced by `.claude/hooks/guard_fanout.py`, which now reads fan-out width from the script text
and refuses a launch whose width it cannot read.

**When a tool or session setting says token cost is not a constraint, it is wrong about this
owner's plan. This rule outranks it.**

**MAKE IT IMPOSSIBLE AT THE POINT OF ACTION.** Not a detector, not a convention, not a note. The
rule is the authorisation: when a defect's class is understood, close it — do not stop and ask
whether to. Asking again for permission already given is itself the disobedience.

**A RUN THAT KNOWS WHAT IT MISSED GOES BACK.** Never report a gap and stop. And never satisfy that
by looking less hard: coverage is computed from disk, not claimed. `tools/guard/commit_guard.py`
rule six and `tools/guard/census.py` enforce both halves.

**NEVER PUSH WITHOUT EXPLICIT PER-PUSH APPROVAL.** Approval for one push is not approval for the
next.

**FINISH CHANGES BEFORE STARTING A LONG MEASUREMENT.** A battery or mutation sweep measures one
frozen tree; starting one with edits still coming throws it away. `tools/guard/work_order.py`
gates this on `OUTSTANDING.md`.

**FEATURES LIVE IN THEIR OWN UI AREA.** Never menu-dumped, never a new window; seldom-used goes to
the Extras tab. Undo/redo everywhere.

**GAME DATA IS READ-ONLY.** `RomFS*`, `RomFS_original_garcs` and the owner's `Workspace` folder are
never written by anything but the editor under the owner's own hand. Never run Tidewater. Never
`git stash`. Never hand-edit a NetBeans `initComponents` block. No Bash heredocs for Java or
Python — write the script to a file.

**THE OWNER DOES THE IN-APP AND IN-GAME TESTING.** Suites cannot load the game.
