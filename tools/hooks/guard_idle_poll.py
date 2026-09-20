#!/usr/bin/env python3
"""Refuse a command whose first act is to SLEEP and wait. The harness already notifies.

WHY THIS EXISTS, measured from one campaign's own log rather than argued.

The unattended sweep ran for 4.7 days - 113 hours of wall clock - to do about 29 hours of
sweeping. The largest line was the runner waiting on an over-broad dirty check, and that is now
a narrower predicate. THE SECOND LARGEST WAS ME, sitting in `sleep 115; check the output file`
loops, fifteen or more times in a single session, doing nothing at all while a background task
ran and while thousands of surviving mutants sat unworked.

IT BUYS NOTHING. A backgrounded command's completion arrives as a task notification with its own
exit status; the output is already captured to a file. Sleeping to look at it earlier does not
make it finish earlier - it only makes the interval between "the machine is free" and "somebody
noticed" longer, which is the exact quantity this project spent a day measuring.

WHAT TO DO INSTEAD. Start the long thing in the background and do OTHER WORK in the same turn:
read the next module's survivors, write the next test, run a targeted file. If there is genuinely
nothing to do until it lands, end the turn - the notification is what wakes it back up, and that
costs zero.

THE ONE THING A SLEEP IS FOR is giving a process a moment to come up before probing it - a
server binding a port, a detached runner writing its first log line. Those are seconds, not
minutes, so a short sleep is allowed and a long one is not.

TO WAIT ANYWAY: the owner sets DTENGINE_ALLOW_SLEEP=1 for that session.
"""
import json
import os
import re
import sys

#: A pause this long is not "let it come up", it is a poll. The longest honest wait measured in
#: this project is a server bind at about four seconds.
LONGEST_HONEST = 30

BYPASS = "DTENGINE_ALLOW_SLEEP"

#: `sleep 115`, `sleep 115;`, `sleep 1m`. A bare number is seconds.
SLEEP = re.compile(r"^\s*sleep\s+([0-9]+(?:\.[0-9]+)?)\s*([smh]?)\s*(?:;|&&|$)")

FACTOR = {"": 1, "s": 1, "m": 60, "h": 3600}


def seconds(command):
    """How long the FIRST statement sleeps, or None when it does not sleep at all.

    Only the first statement. `python x.py; sleep 60` runs the work and then pauses, which is
    pointless but not a poll; `sleep 60; check` is the shape that waited for hours.
    """
    found = SLEEP.match(command)
    if not found:
        return None
    return float(found.group(1)) * FACTOR.get(found.group(2), 1)


def verdict(command):
    waited = seconds(command)
    if waited is None or waited <= LONGEST_HONEST:
        return False, None
    return True, (
        "A %g-second sleep at the start of a command is a POLL, and the harness already tells "
        "you when a background task finishes - with its exit status, and its output already in "
        "a file.\n\n"
        "MEASURED: one sweep took 113 hours of wall clock to do 29 hours of work, and this is "
        "the second largest line in that gap. Fifteen of these in one session is fifteen "
        "stretches of doing nothing while thousands of surviving mutants sat unworked.\n\n"
        "Do other work in the same turn - the next module's survivors, the next test, a "
        "targeted run. If there is genuinely nothing to do until it lands, END THE TURN; the "
        "notification is what wakes it up and it costs nothing.\n\n"
        "A short pause to let a process come up is still allowed (up to %ds)."
        % (waited, LONGEST_HONEST))


sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shellin                                    # noqa: E402  (path set above)

HOOK = "guard_idle_poll.py"


def decide(payload):
    """(deny, reason) for ANY tool call - the class of shell-running tools, not one name.

    This hook used to begin by exempting every tool not called `Bash`, and the session's
    `PowerShell` tool ran the same shell past it. The question is now a shape: does this tool
    input carry a command. A payload part that cannot be read at all is refused rather than
    treated as carrying nothing.
    """
    if os.environ.get(BYPASS) == "1":
        return False, None
    blind = shellin.unreadable(payload)
    if blind:
        return True, shellin.blind_refusal(HOOK, "; ".join(blind), BYPASS)
    for command in shellin.commands(payload):
        deny, reason = verdict(command)
        if deny:
            return deny, reason
    return False, None


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        sys.exit(0)                      # never break a turn on a parse failure
    deny, reason = decide(payload)
    if deny:
        json.dump({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }}, sys.stdout)
    sys.exit(0)


if __name__ == "__main__":
    main()
