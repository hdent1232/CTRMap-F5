#!/usr/bin/env python3
"""Block mass multi-agent fan-out in this project.

WHY THIS EXISTS. On 2026-08-17 a single day of agentic work consumed 96% of the
owner's weekly usage allowance: 26 Workflow runs (13 adversarial audits + 13 fix
rounds), 34-59 subagents each, ~97 MILLION subagent tokens. Roughly 24M of that
returned nothing at all -- three runs lost every agent to a session limit
mid-flight and were relaunched.

The audits were not converging. Findings per round across 13 rounds:
59, 79, 49, 48, 43, 29, 44, 47, 40, 39, 32, 35, 34 -- flat from round 3.
That is a machine that manufactures ~35 findings per round by construction,
because each round hands the next audit 30-40 brand-new changes to hunt through.

Nothing in the harness stopped it, and the assistant never checked the burn rate
against the quota. This hook is the stop. It is deliberately mechanical: it does
not depend on a future assistant remembering, reading CLAUDE.md, or exercising
judgement while mid-task.

TO RUN A WORKFLOW ANYWAY: the owner sets `bundle_env.name("ALLOW_WORKFLOW")` to 1 in the
environment for that session. That is an explicit, deliberate act - and even then README
section 12's rule holds: ONE FIX WORKFLOW AT A TIME. A lifted block is capped at
`bundle_env.cap("WORKFLOW_CAP", 1)` per window, counted in the same file as the agents.

EVERY ENVIRONMENT NAME COMES FROM `bundle_env`. The project's copy of this file read its cap
with a bare `int(os.environ.get(...))` - a value like `six` raised at IMPORT, which the
dispatcher reports as a crashed guard and so refuses every tool call in the session - while the
bundle's copy read the cap under ONE prefix and printed the other prefix's name in its refusal:
a message telling the reader to set a variable the hook did not read.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_env      # noqa: E402  - one PREFIX renames every override
import bundle_shell    # noqa: E402  - an agent launch, found by shape
import request_ledger  # noqa: E402  - the one launch this cap does not count
import time

# One task-level Agent call is fine. A dozen is a screening campaign.
AGENT_CAP = bundle_env.cap("AGENT_CAP", 4)
STATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".fanout-count")

#: The window a count is held for. A cap that never resets is a cap that gets raised.
WINDOW = 6 * 3600

#: The owner's escape when this hook cannot MEASURE, as opposed to when it measures and refuses.
#: Separate from the workflow escape on purpose: that one says "I have decided this launch is
#: worth it", and this one says "the hook is broken and I know it" - different decisions, and
#: conflating them would let a broken hook be silently waved through by a flag set for a
#: different reason weeks earlier.
ALLOW_UNMEASURED = bundle_env.name("ALLOW_FANOUT_UNMEASURED")
ALLOW_WORKFLOW = bundle_env.name("ALLOW_WORKFLOW")

#: Adapted per project: see ADAPT.md.
ADAPT = ("STATE", "WINDOW")


def load():
    """(agents, workflows) recorded in this window, or None when the counter cannot be READ.

    ABSENT IS NOT UNREADABLE. `except Exception: used = 0` served both, so a counter that had
    never been written and one nobody could parse gave the same answer - and only the first of
    those is honestly zero. Every probe has an answer for "not there" and an answer for "I could
    not look"; this returns (0, 0) for the first and None for the second.

    The file is `stamp,agents` or `stamp,agents,workflows`; a counter written before workflows
    were counted is read as having launched none.
    """
    if not os.path.exists(STATE):
        return 0, 0                                 # no window has started - genuinely zero
    try:
        with open(STATE) as fh:
            fields = fh.read().strip().split(",")
        if len(fields) not in (2, 3):
            return None
        stamp, agents = float(fields[0]), int(fields[1])
        workflows = int(fields[2]) if len(fields) == 3 else 0
    except Exception:
        return None                                 # present and unreadable - UNKNOWN
    if agents < 0 or workflows < 0:
        return None
    if time.time() - stamp > WINDOW:                # counter ages out after the window
        return 0, 0
    return agents, workflows


def spawns_used():
    """Subagents recorded in this window, or None when the counter cannot be READ."""
    held = load()
    return None if held is None else held[0]


def deny(reason):
    json.dump({"hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": "deny",
        "permissionDecisionReason": reason,
    }}, sys.stdout)
    sys.exit(0)


def unreadable_counter():
    deny(
        "BLOCKED BY PROJECT POLICY: the subagent counter at\n%s\nexists and cannot "
        "be READ, so how many agents this window has already spawned is UNKNOWN.\n\n"
        "An unknown count is not a count of zero. `open(STATE, \"w\") ` truncates "
        "before it writes, so a process killed mid-write leaves an empty file - and "
        "reading that as 0 forgives the entire window, every time it happens.\n\n"
        "Delete that file to start a fresh window deliberately, or set %s=1."
        % (STATE, ALLOW_UNMEASURED)
    )


def record(agents, workflows):
    """Write the counter, or REFUSE. A spawn that cannot be counted does not happen.

    FAIL CLOSED. Measured: with this file read-only, TEN agents were allowed against a cap of
    four, because a swallowed write means `used` reads 0 forever. A permission bit, a full disk
    or a stale lock silently disabled the guard for the day that burned 96% of a weekly
    allowance.
    """
    try:
        with open(STATE, "w") as fh:
            fh.write("%s,%d,%d" % (time.time(), agents, workflows))
    except Exception as exc:
        deny(
            "BLOCKED BY PROJECT POLICY: the subagent counter at\n"
            f"{STATE}\ncannot be written ({type(exc).__name__}: {exc}).\n\n"
            "This cap is what stands between this project and another 2026-08-17, and"
            " an\nunrecordable spawn is an uncapped one: with this file read-only, TEN"
            " agents\nwere allowed against a cap of four. It refuses rather than"
            " guessing.\n\n"
            "Make the file writable, or work in the main thread."
        )


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception as exc:
        # AN UNMEASURABLE QUANTITY IS NOT A SMALL ONE. This was `sys.exit(0)` with the comment
        # "never break a turn on a parse failure" - which treats a payload nobody can read as a
        # launch nobody needs to count, and that is the whole defect this hook was rebuilt for.
        #
        # THE COST DECIDES IT. Refusing here blocks Agent and Workflow calls ONLY: editing,
        # git, the suite and every other command still run, so the way out of the situation is
        # open. Allowing costs a metered week - 96% of a weekly allowance in one day, twice.
        if bundle_env.allowed("ALLOW_FANOUT_UNMEASURED"):
            sys.exit(0)
        deny(
            bundle_shell.policy(__file__) + "\n"
            "This hook could not read the tool payload (%s: %s), so it cannot tell whether "
            "this launch fans out at all.\n\n"
            "An unmeasurable quantity is not a small one: the last two times this project "
            "assumed one was, it cost 96%% of a weekly allowance and then 111.9M subagent "
            "tokens. Only Agent and Workflow calls are refused - everything else still runs, "
            "so this does not block fixing it.\n\n"
            "If the harness payload format has changed, that is a real bug in this hook and "
            "worth fixing. To proceed meanwhile: %s=1"
            % (type(exc).__name__, exc, ALLOW_UNMEASURED)
        )
    tool = str(payload.get("tool_name") or "") if isinstance(payload, dict) else ""

    if tool == "Workflow":
        if not bundle_env.allowed("ALLOW_WORKFLOW"):
            deny(
                bundle_shell.policy(__file__) + "\n"
                "Multi-agent Workflow runs are disabled in this project. On 2026-08-17 "
                "26 of them burned ~97M subagent tokens - 96%% of a weekly 20x "
                "allowance - in one day, and ~24M of that returned nothing because "
                "whole runs died on session limits. The audits were not converging: "
                "findings per round ran 59/79/49/48/43/29/44/47/40/39/32/35/34, flat "
                "from round 3, because each round fed the next audit 30-40 fresh "
                "changes to hunt.\n\n"
                "Do the work in the main thread instead: read the code, reproduce the "
                "defect, fix it, run the suite. If a fan-out is genuinely warranted, "
                "STOP and ask the owner for approval with (a) how many agents, (b) an "
                "estimated token cost, and (c) what question it answers that a "
                "targeted read cannot. Only the owner may lift this, by setting "
                "%s=1." % ALLOW_WORKFLOW
            )
        held = load()
        if held is None:
            if bundle_env.allowed("ALLOW_FANOUT_UNMEASURED"):
                sys.exit(0)
            unreadable_counter()
        agents, workflows = held
        cap = bundle_env.cap("WORKFLOW_CAP", 1)
        if workflows >= cap:
            deny(
                "BLOCKED BY PROJECT POLICY: %d workflow(s) already launched in this window "
                "(cap %d). README section 12: ONE FIX WORKFLOW AT A TIME, never two "
                "concurrently - two runs editing one tree is how a fix gets made and never "
                "reaches the user. Raise %s deliberately if this is really wanted."
                % (workflows, cap, bundle_env.name("WORKFLOW_CAP")))
        record(agents, workflows + 1)
        sys.exit(0)

    # AN AGENT LAUNCH BY SHAPE, and by either name the launching tool has had. Asking for the
    # string "Agent" alone is one rename from off - the tool was called `Task` before it was
    # called `Agent` - which is the trigger-by-name defect this directory was rebuilt to remove.
    if tool in ("Agent", "Task") or (isinstance(payload, dict) and bundle_shell.launches(payload)):
        # THE REVIEW THE PROJECT DECLARED IT CAN AFFORD IS NOT FAN-OUT. `_review` in its
        # declarations is the owner's yes and its price; the launch must be exactly the prompt
        # the ledger owes, one agent, on the declared model - anything else is counted as usual.
        if request_ledger.is_owed_review(payload):
            sys.exit(0)
        held = load()
        if held is None:
            # THE SAME DECISION AS THE WRITE BELOW, WHICH ALREADY REFUSES. Its comment says a
            # swallowed write means the count reads 0 forever; this is that sentence's other
            # half, and it was `except Exception: used = 0` for months.
            if bundle_env.allowed("ALLOW_FANOUT_UNMEASURED"):
                sys.exit(0)
            unreadable_counter()
        used, workflows = held
        if used >= AGENT_CAP:
            deny(
                f"BLOCKED BY PROJECT POLICY: {used} subagents already spawned in "
                f"this window (cap {AGENT_CAP}).\n"
                "This project has been burned by unbounded agent fan-out - see "
                f"{bundle_shell.FOLDER}/{os.path.basename(__file__)}. Finish the work in the main "
                "thread, or ask the owner to raise %s for this "
                "session with a stated reason and cost." % bundle_env.name("AGENT_CAP")
            )
        record(used + 1, workflows)
    sys.exit(0)


# A HOOK THAT RUNS WHEN YOU IMPORT IT CANNOT BE TESTED. This was a bare `main()`, so importing
# this module read stdin, failed to parse it and exited - taking the importing process with it.
# Every other hook here has the guard; this one, the highest-risk of them, did not.
if __name__ == "__main__":
    main()
