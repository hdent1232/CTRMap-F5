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
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_env        # noqa: E402  - one PREFIX renames every override
import bundle_shell      # noqa: E402  - an agent launch, found by shape
import guard_fanout      # noqa: E402  - the one reader of the one launch counter
import request_ledger    # noqa: E402  - the one launch the ledger itself demands

import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
ROOT = _tree_root.require(__file__)

#: Where this campaign's durable state lives: OUTSIDE the repository, beside it, one folder per
#: project - so a `git reset --hard` or a fresh clone cannot take it with the tree.
STATE_FILE = os.path.join(os.path.dirname(ROOT), "_state", os.path.basename(ROOT), "STATE.md")

#: The round log: {"rounds": [{"scope", "n", "findings", "recorded"}]}.
CONVERGENCE_LOG = os.path.join(ROOT, "docs", "audit-convergence.json")

#: Adapted per project: see the bootstrap's ADAPT.md.
ADAPT = ("STATE_FILE", "CONVERGENCE_LOG")


def state_problem(now=None, scripted=False):
    """Why a MULTI-agent launch may not proceed for want of durable state, or None. A `scripted`
    run - a workflow - is multi-agent by itself, whatever the window has launched."""
    held = guard_fanout.load()
    if held is None:
        return None             # guard_fanout refuses an unreadable counter itself, and says why
    agents, _workflows = held
    if agents < 1 and not scripted:
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


#: How long the reason closing a scope must be: a closure is a decision, and a short one is a
#: rename by another spelling.
CLOSE_REASON = 80


def not_converging(rows):
    """[(scope, its last three counts)] for EVERY scope whose last three rounds are not falling,
    in the order the log first names them. THE ONE TREND: the project's `check` command asks
    this too, so the refusal at the act and the report can never disagree.

    EVERY SCOPE, NOT THE NEWEST. This judged only the scope whose latest row was newest - measured
    on CTRMap, 2026-10-07: a flat scope plus ONE round recorded under any new scope name, and the
    launch went through while the project's `check` said STOP AND REPORT. Renaming the campaign
    is exactly the evasion this guard exists to refuse. A scope is let go only by a row CLOSING
    it - {"scope": s, "closed": "<why, CLOSE_REASON characters>"} - and a round recorded after
    that opens it again. A malformed row RAISES; the caller says so rather than guessing.
    """
    counts, closed, order = {}, {}, []
    for row in rows:
        scope = row["scope"]
        if scope not in counts:
            counts[scope] = []
            order.append(scope)
        if "closed" in row:
            if len(str(row["closed"]).strip()) < CLOSE_REASON:
                raise ValueError("the row closing %r gives a reason under %d characters"
                                 % (scope, CLOSE_REASON))
            closed[scope] = True
            continue
        counts[scope].append(int(row["findings"]))
        closed[scope] = False
    out = []
    for scope in order:
        last = counts[scope][-3:]
        if not closed.get(scope) and len(last) == 3 and not last[0] > last[1] > last[2]:
            out.append((scope, last))
    return out


def trend_problem():
    """Why the next round may not start - a scope is not converging - or None."""
    if not os.path.exists(CONVERGENCE_LOG):
        return None             # no rounds recorded: nothing to converge
    try:
        with io.open(CONVERGENCE_LOG, encoding="utf-8") as handle:
            bad = not_converging(json.load(handle).get("rounds") or [])
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as exc:
        return ("the convergence log at %s cannot be read (%s: %s) - an unmeasurable trend is "
                "not a falling one" % (CONVERGENCE_LOG, type(exc).__name__, exc))
    if not bad:
        return None
    return ("NOT CONVERGING - %s. Two or three failed fixes on one piece of logic means the "
            "design is wrong - say so and ask" % "; ".join(
                "%r: %s" % (scope, " -> ".join(map(str, last))) for scope, last in bad))


def verdict(payload):
    # A SCRIPTED RUN IS A CAMPAIGN ROUND TOO. This asked agent launches only, so a workflow - the
    # kind of run that lost three agents and fifteen verifiers to one session limit - needed no
    # state and no falling trend at all.
    scripted = bool(bundle_shell.workflows(payload))
    if not bundle_shell.launches(payload) and not scripted:
        return False, None
    # THE REVIEW THE LEDGER DEMANDS IS NOT A CAMPAIGN ROUND. Refusing it in a window that already
    # ran an agent, or under a flat round log, leaves a turn that must be reviewed to end and may
    # not be - the deadlock `guard_agent_edits` put two sessions in on 2026-09-26. Its own
    # convergence is the ledger's: after REVIEW_ROUNDS without a PASS it is not asked again.
    if request_ledger.is_owed_review(payload):
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
        why = state_problem(scripted=scripted)
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
