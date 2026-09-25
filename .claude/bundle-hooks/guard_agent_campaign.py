#!/usr/bin/env python3
"""A MULTI-AGENT RUN NEEDS DURABLE STATE, AND A ROUND NEEDS A TREND THAT FALLS. Refused at launch.

TWO RULES THE README STATES AND NOTHING HELD, both about the moment an agent is launched.

1. README section 11: *keep a `_state/` directory outside the repo with a `STATE.md` per
   campaign: what was decided, what is running, what to do on resume, what is blocked on the
   human. Update it at every transition. One project lost three agents and fifteen verifiers to
   a single session limit before this existed, and the relaunch burned more than the original
   run.* The day-one checklist says: write the first STATE.md BEFORE THE FIRST MULTI-AGENT RUN.
   So a launch that would make this window multi-agent - an agent already ran in it - is refused
   while the campaign's STATE.md is missing or older than the window.

2. README section 12, STATE CONVERGENCE, OR STOP: *before round N+1 of any audit or fix cycle,
   log the finding count and check the trend. Stop when the last three rounds are flat or
   rising.* Thirteen rounds ran `59, 79, 49, 48, 43, 29, 44, 47, 40, 39, 32, 35, 34` at ~97 million
   tokens in one day - flat from round 3, visible by round 6, and nobody looked because nothing
   made anyone look. The log existed; the command that checks it was something a person had to
   remember to run. So a launch is refused while the most recently logged scope's last three
   rounds are not strictly falling.

READ, NOT IMPORTED FROM THE PROJECT. The window count is `guard_fanout.load()` - the one reader of
the one counter, a sibling here - and the convergence log is data. A log that exists and cannot
be parsed REFUSES: an unmeasurable trend is not a falling one.

TO LAUNCH ANYWAY: the owner sets `bundle_env.name("ALLOW_NO_STATE")` or
`bundle_env.name("ALLOW_NONCONVERGING")` to 1 - separately, because they answer different
questions and one flag set for the first must not wave through the second.
"""
import io
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_env        # noqa: E402  - one PREFIX renames every override
import bundle_shell      # noqa: E402  - an agent launch, found by shape
import guard_fanout      # noqa: E402  - the one reader of the one launch counter

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

#: Where this campaign's durable state lives: OUTSIDE the repository, beside it, one folder per
#: project - so a `git reset --hard` or a fresh clone cannot take it with the tree.
STATE_FILE = os.path.join(os.path.dirname(ROOT), "_state", os.path.basename(ROOT), "STATE.md")

#: The round log: {"rounds": [{"scope", "n", "findings", "recorded"}]}.
CONVERGENCE_LOG = os.path.join(ROOT, "docs", "audit-convergence.json")

#: Adapted per project: see ADAPT.md.
ADAPT = ("STATE_FILE", "CONVERGENCE_LOG")


def state_problem(now=None):
    """Why a MULTI-agent launch may not proceed for want of durable state, or None."""
    held = guard_fanout.load()
    if held is None:
        return None             # guard_fanout refuses an unreadable counter itself, and says why
    agents, _workflows = held
    if agents < 1:
        return None             # the first agent of a window is not yet a multi-agent run
    try:
        age = (now or time.time()) - os.path.getmtime(STATE_FILE)
    except OSError:
        return ("no campaign state at %s, and this launch makes the window multi-agent" % STATE_FILE)
    if age > guard_fanout.WINDOW:
        return ("the campaign state at %s was last updated %.1f hours ago - older than the "
                "%d-hour window this launch belongs to" % (STATE_FILE, age / 3600.0,
                                                          guard_fanout.WINDOW // 3600))
    return None


def trend_problem():
    """Why the next round may not start - the latest scope is not converging - or None."""
    if not os.path.exists(CONVERGENCE_LOG):
        return None             # no rounds recorded: nothing to converge
    try:
        with io.open(CONVERGENCE_LOG, encoding="utf-8") as handle:
            rows = [r for r in json.load(handle).get("rounds") or []]
        latest = max(rows, key=lambda r: str(r.get("recorded") or ""))["scope"] if rows else None
        counts = [int(r["findings"]) for r in rows if r.get("scope") == latest]
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as exc:
        return ("the convergence log at %s cannot be read (%s: %s) - an unmeasurable trend is "
                "not a falling one" % (CONVERGENCE_LOG, type(exc).__name__, exc))
    if latest is None or len(counts) < 3:
        return None
    last = counts[-3:]
    if last[0] > last[1] > last[2]:
        return None
    return ("the latest scope, %r, is NOT CONVERGING: %s. Two or three failed fixes on one piece "
            "of logic means the design is wrong - say so and ask" % (latest,
                                                                    " -> ".join(map(str, last))))


def verdict(payload):
    if not bundle_shell.launches(payload):
        return False, None
    if not bundle_env.allowed("ALLOW_NONCONVERGING"):
        why = trend_problem()
        if why:
            return True, (
                bundle_shell.policy(__file__) + "\n%s.\n\n"
                "README section 12: thirteen rounds ran 59, 79, 49, 48, 43, 29, 44, 47, 40, 39, "
                "32, 35, 34 at ~97M tokens in a day - flat from round 3, visible by round 6, and "
                "nobody looked. STOP AND REPORT to the owner with the numbers. Only the owner "
                "lifts this: %s=1.\n" % (why, bundle_env.name("ALLOW_NONCONVERGING")))
    if not bundle_env.allowed("ALLOW_NO_STATE"):
        why = state_problem()
        if why:
            return True, (
                bundle_shell.policy(__file__) + "\n%s.\n\n"
                "README section 11: write STATE.md BEFORE the first multi-agent run - what was "
                "decided, what is running, what to do on resume, what is blocked on the human. "
                "One project lost three agents and fifteen verifiers to a single session limit "
                "before it existed, and the relaunch cost more than the run.\n\n"
                "Write it, then launch. Or set %s=1.\n" % (why, bundle_env.name("ALLOW_NO_STATE")))
    return False, None


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        sys.exit(0)                      # guard_fanout refuses an unreadable launch payload
    if not isinstance(payload, dict):
        sys.exit(0)
    deny, reason = verdict(payload)
    if deny:
        json.dump({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }}, sys.stdout)
    sys.exit(0)


if __name__ == "__main__":
    main()
