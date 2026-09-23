#!/usr/bin/env python3
"""REFUSE MY COMMANDS WHILE THE MACHINE HAS PRODUCED NOTHING. Whatever the reason.

WHAT THIS COST, and it is the second time the same shape has been paid for here.

On 2026-09-13 the sweep runner sat for thirty-three hours having decided nothing. The cause was
a defect in its own remedy loop - a recorded covering map discarded because `covering.py build`
returns the SUITE's exit code - and the evidence was everywhere. `docs/PROGRESS.md` said
`workers last wrote 33.4 hours ago  - stale`. The runner's log printed `nothing plannable right
now` every five minutes for hours. I READ THE STALE LINE AND MOVED ON, and it took the owner
asking before I opened the log, where the cause was four consecutive lines.

`guard_blocked_runner.py` exists for exactly this and did not fire, because its trigger is one
CAUSE: `.sweep/blocked.json`, written when the runner is waiting on tooling I have not committed.
That night it was idle for a different reason and nothing refused anything. A guard that knows
one cause finds the instances that happen to have that cause - the same mistake as a checker
that finds a class by where a file SITS.

SO THIS ONE ASKS WHAT THE MACHINE HAS PRODUCED, and does not care why. Three files are its
entire output:

    .sweep/ledger-*.jsonl     a verdict for one mutation site
    .covering-map.json        which tests cover which lines
    tests/exercised_by.json   which test files reach which modules

If none of them has been written for longer than the bound below, nothing is being weighed. That
is true of a deadlock, a crashed runner, a runner nobody started, a wedged worker, a baseline
storm, and of a dozen causes nobody has met yet.

THE BOUND IS DERIVED FROM THE SUITE'S OWN MEASURED COST. Both long jobs - the exercised-by
derive and the covering-map rebuild - run the whole suite once and write nothing until they
finish, so back to back they are legitimately silent for two suite runs. Measured on
2026-09-14: the derive took 54 minutes and the rebuild 47.5, against a suite whose per-file
seconds sum to 34 minutes. `SUITE_RUNS = 4` is those two with the margin their overheads need,
and it moves with the suite instead of going stale.

READ FROM `tests/exercised_by.json`, WHICH IS 63 KB. `.covering-map.json` holds the same kind of
number and is 10.7 MB; parsing it on every Bash command is how a hook becomes the thing somebody
switches off. Its mtime is still consulted - a `stat` is free - just not its contents.

TO STOP THE MACHINE ON PURPOSE, write `.sweep/idle-on-purpose.json`:

    {"why": "<at least 80 characters saying why>", "until": <epoch seconds>}

A reason, because this project has shipped `guarded above` as an entire justification and it was
false. An EXPIRY, because a permanent acknowledgement is an off-switch with a comment on it.

THE WAY OUT IS NEVER BLOCKED. git, the suite, `safe.py`, `plants.py` and every command that
starts or inspects the runner still run. A guard that blocks the way out of the situation it
describes is worse than none, which this repository already wrote about `guard_mutation_read.py`
allowing `git`.
"""
import glob
import io
import json
import os
import re
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
STATE = os.path.join(ROOT, ".sweep")
EXERCISED = os.path.join(ROOT, "tests", "exercised_by.json")
ACKNOWLEDGED = os.path.join(STATE, "idle-on-purpose.json")

#: The machine's entire output. A file here being written means something was weighed, measured
#: or mapped; none of them moving means nothing was.
PRODUCTS = (
    os.path.join(STATE, "ledger-*.jsonl"),
    os.path.join(ROOT, ".covering-map.json"),
    EXERCISED,
)

#: How many whole-suite runs the machine may legitimately go without writing anything.
#:
#: MEASURED, 2026-09-14. The derive took 54 minutes and the covering rebuild 47.5 - 101.5
#: minutes of honest silence back to back - against a suite whose per-file seconds sum to 55.7.
#: So the real silence is 1.8 suite runs and this is that with a little over half again, which
#: is margin for a slower machine without being a number nothing can exceed.
#:
#: It is not four. Four was the first value here and it produced a bound of 3.7 hours, because
#: the sum it multiplies had itself been measured while I was running tests against the same
#: box - a contended measurement makes this LOOSER, which is the direction a bound must not
#: drift in silently. Three keeps the bound near the thing it is derived from.
SUITE_RUNS = 3

#: When nothing has measured the suite - a fresh clone, a deleted record - the bound still has
#: to be a number. Ninety minutes is longer than either job has ever taken alone.
FLOOR_SECONDS = 90 * 60

#: A reason has to be a reason. The same length `accounted.MIN_REASON` uses, for the same
#: reason: this project has shipped a three-word justification that was false.
MIN_REASON = 80

#: THE WAY OUT. Committing, proving, and anything that starts, inspects or diagnoses the runner -
#: because the remedy for an idle machine is to look at it and restart it, and a refusal that
#: forbids that leaves no reachable action at all.
ESCAPES = (
    r"^git\b",
    r"^\s*python[0-9.]*\s+-u?\s*-?m?\s*unittest\b",
    r"python[0-9.]*\s+-u?\s*-m\s+unittest\b",
    r"tools[/\\]dev[/\\]safe\.py",
    r"tools[/\\]dev[/\\]plants\.py",
    r"tools[/\\]dev[/\\]sweep_forever\.py",
    r"tools[/\\]dev[/\\]start_sweep_forever",
    r"tools[/\\]dev[/\\]progress\.py",
    r"tools[/\\]audit[/\\]sweep\.py",
    r"sweep-forever\.log",
    r"\.sweep[/\\]",
)


def suite_seconds():
    """What one whole-suite run costs, as the machine last measured it. 0 when nothing has.

    `seconds_per_test_file` is written by `exercised.py derive`, which runs every test file once
    - so the sum IS a suite run, measured by the machine about itself rather than chosen here.
    """
    try:
        with io.open(EXERCISED, encoding="utf-8") as handle:
            held = json.load(handle)
    except (IOError, OSError, ValueError):
        return 0.0
    seconds = held.get("seconds_per_test_file")
    if not isinstance(seconds, dict):
        return 0.0
    total = 0.0
    for value in seconds.values():
        try:
            total += float(value or 0)
        except (TypeError, ValueError):
            continue
    return total


def bound_seconds():
    """How long the machine may legitimately produce nothing."""
    return max(FLOOR_SECONDS, SUITE_RUNS * suite_seconds())


def last_product():
    """(seconds since the machine last wrote anything, the file it wrote), or (0, None).

    ZERO WHEN THERE IS NOTHING TO BE STALE. A tree that has never run a sweep has no products,
    and refusing there would fire on a fresh clone - a guard that fires where there is no
    machine is one nobody keeps.
    """
    newest, name = 0.0, None
    for pattern in PRODUCTS:
        for path in glob.glob(pattern):
            try:
                at = os.path.getmtime(path)
            except OSError:
                continue
            if at > newest:
                newest, name = at, os.path.relpath(path, ROOT).replace(os.sep, "/")
    if name is None:
        return 0.0, None
    return max(0.0, time.time() - newest), name


def acknowledged():
    """True when somebody has written down, with a reason and an expiry, that this is on purpose.

    BOTH HALVES REQUIRED. A reason under `MIN_REASON` characters is a label - this project has
    shipped `guarded above` as an entire justification and the line it excused was reachable -
    and an acknowledgement with no expiry is an off-switch wearing a comment. Anything
    unreadable, short or expired is not an acknowledgement.
    """
    try:
        with io.open(ACKNOWLEDGED, encoding="utf-8") as handle:
            held = json.load(handle)
    except (IOError, OSError, ValueError):
        return False
    if len(str(held.get("why") or "")) < MIN_REASON:
        return False
    try:
        return float(held.get("until") or 0) > time.time()
    except (TypeError, ValueError):
        return False


def is_escape(command):
    text = (command or "").strip()
    return any(re.search(pattern, text) for pattern in ESCAPES)


def tail_of_the_log(lines=6):
    """The last few lines the runner wrote, so the refusal hands over the diagnosis."""
    path = os.path.join(ROOT, "docs", ".sweep-forever.log")
    try:
        with io.open(path, encoding="utf-8", errors="replace") as handle:
            held = handle.read().splitlines()
    except (IOError, OSError):
        return []
    return held[-lines:]


def refusal(seconds, name, bound):
    out = [
        "BLOCKED: the machine has weighed NOTHING for %.0f minutes." % (seconds / 60.0),
        "",
        "  last thing it produced   %s" % name,
        "  bound                    %.0f minutes (%d whole-suite runs, derived from the"
        % (bound / 60.0, SUITE_RUNS),
        "                           per-file seconds `exercised.py derive` measured)",
        "",
        "  A derive and a covering rebuild are one suite run each and publish only at the end,",
        "  so two of them back to back is the longest the machine is legitimately silent.",
        "  Past that it is not busy, it is stopped - and WHY does not matter here: a deadlock,",
        "  a crashed runner, one nobody started, a wedged worker, a baseline storm.",
        "",
        "  The last lines it wrote:",
    ]
    out += ["      " + line for line in tail_of_the_log()] or ["      (no log)"]
    out += [
        "",
        "  THIS COST THIRTY-THREE HOURS ON 2026-09-13. `docs/PROGRESS.md` said `workers last",
        "  wrote 33.4 hours ago - stale` and the runner printed `nothing plannable right now`",
        "  every five minutes. I read the stale line and moved on. A detector nobody looks at",
        "  is why this refuses instead of printing a thirty-fourth hour of them.",
        "",
        "  Look at it, then start it:",
        "      python tools/dev/progress.py            # what it thinks it is doing",
        "      tail docs/.sweep-forever.log            # what it last did",
        "      powershell tools/dev/start_sweep_forever.ps1",
        "",
        "  git, the suite, `safe.py`, `plants.py` and everything that starts or inspects the",
        "  runner still run. To stop it ON PURPOSE, write .sweep/idle-on-purpose.json with a",
        "  `why` of %d+ characters and an `until` epoch - a reason and an expiry, because an" % MIN_REASON,
        "  acknowledgement with neither is an off-switch with a comment on it.",
    ]
    return "\n".join(out)


sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shellin                                    # noqa: E402  (path set above)

HOOK = "guard_idle_machine.py"


def decide(payload):
    """(deny, reason) for ANY tool call that runs a shell, whatever the tool is named.

    Wired under `"matcher": "Bash"` and reading `tool_input.command`, this answered for one
    tool name while the session also had `PowerShell`. It now asks a shape. A tool call that
    runs no shell at all is not this hook's business; a tool call carrying something it cannot
    read is refused, because an unreadable command is not an absent one.
    """
    found = shellin.commands(payload)
    blind = shellin.unreadable(payload)
    if blind:
        return True, shellin.blind_refusal(HOOK, "; ".join(blind), "<no bypass>")
    if not found:
        return False, None
    command = shellin.text(payload)
    if is_escape(command) or acknowledged():
        return False, None
    seconds, name = last_product()
    if name is None:
        return False, None
    bound = bound_seconds()
    if seconds < bound:
        return False, None
    return True, refusal(seconds, name, bound)


def main():
    try:
        payload = json.load(sys.stdin)
    except (ValueError, IOError):
        return 0
    deny, reason = decide(payload)
    if not deny:
        return 0
    sys.stderr.write(reason + chr(10))
    return 2


if __name__ == "__main__":
    sys.exit(main())
