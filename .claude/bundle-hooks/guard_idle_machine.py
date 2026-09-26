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
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_shell      # noqa: E402  - the command, found by shape, at any depth
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
    # AND WHERE A DERIVE HAS GOT TO. The two long jobs publish nothing until they finish, which
    # is why the bound below exists at all - a constant measured on the day a derive took 54
    # minutes. That number went stale the first time the job's cost changed, and the machine
    # read as dead for three hours while it was measuring. `exercised.derive` writes this after
    # every test file now, so a working derive produces something about every twenty-five
    # seconds and nothing has to guess how long silence is allowed to last. It also makes a
    # HUNG derive visible, which a larger bound would have hidden better rather than less.
    os.path.join(STATE, "derive-progress.json"),
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
#:
#: ANCHORED, AND ASKED OF EVERY COMMAND - see `guard_blocked_runner.ESCAPES`. `\.sweep[/\\]`
#: searched anywhere in the line made ANY command an escape the moment it mentioned the state
#: folder: `<anything>; ls .sweep/` walked through. A way out must be what RUNS, and a read of
#: the machine's state counts only when the command is a read.
_PY = r"^(?:python[0-9.]*(?:\.exe)?|py)(?:\s+-[A-Za-z]+)*"
_READS = (r"^(?:cat|type|head|tail|less|more|ls|dir|stat|wc|grep|rg|findstr|Get-Content|gc"
          r"|Get-ChildItem|gci|Get-Item|Test-Path|Select-String|sls)\b")
ESCAPES = (
    r"^git(?:\.exe)?(?:\s|$)",
    _PY + r"\s+-m\s+unittest\b",
    _PY + r"\s+tools[/\\]dev[/\\](?:safe|plants|sweep_forever|progress)\.py\b",
    _PY + r"\s+tools[/\\]audit[/\\]sweep\.py\b",
    r"^(?:powershell|pwsh)(?:\.exe)?\b.*tools[/\\]dev[/\\]start_sweep_forever",
    r"^(?:&\s*)?(?:\.[/\\])?tools[/\\]dev[/\\]start_sweep_forever",
    _READS + r".*(?:\.sweep[/\\]|sweep-forever\.log|PROGRESS\.md)",
)

#: Adapted per project: see ADAPT.md.
ADAPT = ("STATE", "EXERCISED", "ACKNOWLEDGED", "PRODUCTS", "ESCAPES", "PROGRESS_FILES")


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


def stopped_on_purpose():
    """True when `.sweep/STOP` is present - the machine was turned off deliberately.

    THE OFF SWITCH IS ALREADY AN ACKNOWLEDGEMENT. This guard exists because a sweep sat for
    thirty-three hours and nobody noticed; a STOP file is somebody noticing, in advance and in
    writing. Demanding a SECOND acknowledgement on top of it turns the documented way to stop a
    sweep into a thing that refuses every command afterwards - a guard blocking the way out of
    the situation it describes, which this repository has already written down twice.

    NO EXPIRY IS REQUIRED HERE, and that is the difference from `idle-on-purpose.json`. That
    file excuses an ACCIDENT and must not become permanent. This one is an INSTRUCTION, and it
    announces itself: the launcher writes a refusal into the runner's log every five minutes
    for as long as it stands, so a forgotten STOP is the loudest state the machine has.

    STATE IS READ AT CALL TIME. `os.path.join(STATE, "STOP")` evaluated at import would leave
    this naming the REAL `.sweep/STOP` after a test redirects STATE, and a test that writes
    there halts the live sweep - which this repository has a note about.
    """
    return os.path.exists(os.path.join(STATE, "STOP"))


def is_escape(command):
    """Whether EVERY command on this line starts, inspects or lands the machine."""
    ways_out = ESCAPES + REMEDIES + (removes_progress(),)
    return bundle_shell.every_command_is(
        command, lambda one: any(re.search(pattern, one, re.I) for pattern in ways_out))


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


#: A JOB THAT PUBLISHES PROGRESS AND HAS STOPPED PUBLISHING IT, and how long is too long.
#:
#: DERIVED FROM THE JOB'S OWN PER-ITEM BOUND. `exercised.executed` kills a test file at 1800s
#: and records it as not measurable, so progress must advance within that plus the cost of
#: starting the next file. 2400 is that with margin. A progress file older than this means the
#: job is not slow - it is not doing the thing its own timeout was meant to bound.
#: This module had no newline constant, and the refusal below reached for one that did not
#: exist - a `NameError` that only fires WHEN A STALL IS DETECTED, which is the one moment the
#: guard has to work. Caught by driving the stall rather than by reading the branch.
LF = chr(10)

STALL_SECONDS = 2400

#: The files a long job writes to say HOW FAR IT HAS GOT. Each is keyed to work DONE, not to
#: being alive, and that distinction is the whole point: a process that is running and achieving
#: nothing is indistinguishable from a healthy one from the outside, which is exactly what a
#: task panel showing `3h39m` tells you.
PROGRESS_FILES = ("derive-progress.json", "covering-progress.json")

#: THE REMEDIES A STALLED-JOB REFUSAL NAMES, reachable. It says "stop the job, or delete the
#: progress file if it has finished", and neither act was an escape: on 2026-09-24 a derive
#: stopped for another session's sync left its progress file behind, and from forty minutes later
#: this refused every command in BOTH sessions, for seven hours, until the owner deleted the file
#: by hand. The one escape that could delete it was `git clean -X`, which - measured, dry run -
#: would have taken every ignored file under `.sweep/` with it, the sweep's ledgers included.
#: So: looking at processes, stopping one, deleting EXACTLY a progress file this guard reads and
#: nothing else on the line, and starting the job that writes it again.
REMEDIES = (
    r"^(?:Get-Process|gps|ps|tasklist|Get-CimInstance|gcim|Get-WmiObject|gwmi)\b",
    r"^(?:Stop-Process|spps|kill|taskkill)\b",
    _PY + r"\s+tools[/\\]audit[/\\](?:exercised|covering)\.py\b",
)


def removes_progress():
    """The command that deletes exactly one of the progress files this guard reads, and nothing
    else. Built from `STATE` at CALL time, for the reason `stopped_on_purpose` gives."""
    return (r"^(?:rm|del|erase|Remove-Item|ri)\s+(?:-\S+\s+)*"
            r"(?:\"[^\"]*[/\\]|'[^']*[/\\]|[^\s\"';&|]*[/\\]|)%s[/\\](?:%s)[\"']?\s*$"
            % (re.escape(os.path.basename(STATE)),
               "|".join(re.escape(name) for name in PROGRESS_FILES)))


def stalled_job():
    """A sentence naming a job that stopped publishing progress, or None.

    WHY THIS IS ASKED BEFORE THE OFF-SWITCHES. On 2026-09-22 a derive wedged on
    `test_sweep_forever.py` and sat for two and a half hours producing nothing. This guard -
    whose entire subject is whether the machine has produced anything - said nothing, because
    `.sweep/STOP` was present. That STOP was six days old and means *do not run the sweep*. It
    is not an excuse for a job I started myself going silent, and the owner was the one who
    noticed, after the hours were gone.

    One off-switch silencing a guard about EVERYTHING is the same shape as one cause defining
    it - which is the failure this file's own docstring describes about `guard_blocked_runner`.
    A STOP explains the sweep's silence and nothing else's.
    """
    for name in PROGRESS_FILES:
        path = os.path.join(STATE, name)
        try:
            with io.open(path, encoding="utf-8") as handle:
                held = json.load(handle)
        except (IOError, OSError, ValueError):
            continue
        at = held.get("at")
        if not isinstance(at, (int, float)) or isinstance(at, bool):
            continue
        idle = time.time() - float(at)
        if idle < STALL_SECONDS:
            continue
        return (
            bundle_shell.policy(__file__) + LF + LF +
            "%s last finished an item %.1f hours ago, and the job's own per-item timeout is "
            "%d seconds. It is not slow - it has stopped advancing." % (
                name, idle / 3600.0, STALL_SECONDS) + LF + LF +
            "    last: %s    done: %s" % (held.get("last"), held.get("done")) + LF + LF +
            "A wedged derive sat for two and a half hours on 2026-09-22 and nothing said so, "
            "because `.sweep/STOP` was present and this guard treated that as covering "
            "everything. A STOP explains the sweep. It does not explain this." + LF + LF +
            "Stop the job, or delete %s if it has finished." % path + LF)
    return None


def decide(command):
    """(exit code, what to say) for one command. THE WHOLE DECISION, and nothing else.

    LIFTED OUT OF `main` SO THE SUITE CAN DRIVE IT. The tests had a hand-copy of this body -
    `is_escape or acknowledged`, then the product check - so adding `stopped_on_purpose` to
    `main` reached NOT ONE of the twelve tests over this guard. A second copy of a decision is
    the class this repository has paid for most often, and `weigh.outcome` is the precedent: it
    was lifted out of a 90-line `main` because a decision buried there could only be tested by
    performing a real sweep, which is why it was never tested and why it was wrong.
    """
    # A STALLED JOB IS ASKED ABOUT FIRST, BECAUSE NO OFF-SWITCH HERE EXPLAINS ONE.
    #
    # `stopped_on_purpose` reads `.sweep/STOP`, which says "do not run the sweep". It said
    # nothing about a derive I started myself wedging for two and a half hours, and this guard
    # returned "allow" on it before looking at anything - so the thing whose entire subject is
    # whether the machine has produced anything was silent while it produced nothing.
    #
    # `is_escape` stays below it: the way out of a stalled job is still stopping and inspecting
    # it, and a refusal that forbids that leaves no reachable action.
    if not is_escape(command):
        stalled = stalled_job()
        if stalled is not None:
            return 2, stalled

    if is_escape(command) or acknowledged() or stopped_on_purpose():
        return 0, ""
    seconds, name = last_product()
    if name is None:
        return 0, ""
    bound = bound_seconds()
    if seconds < bound:
        return 0, ""
    return 2, refusal(seconds, name, bound)


def main():
    try:
        payload = json.load(sys.stdin)
    except (ValueError, IOError):
        return 0
    # NOT A TOOL CALL, OR ONLY A READ - see `guard_blocked_runner.main`. This refused every
    # `Read` of the runner's own log while telling the reader to go and read the runner's log.
    if not isinstance(payload, dict) or bundle_shell.reads_only(payload) \
            or not bundle_shell.is_tool_call(payload):
        return 0
    code, said = decide(bundle_shell.text(payload))
    if said:
        sys.stderr.write(said + "\n")
    return code


if __name__ == "__main__":
    sys.exit(main())
