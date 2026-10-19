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

EVERY LAUNCH NEEDS THE OWNER'S YES, TYPED BY THE OWNER. On 2026-10-06 an assistant told "no
agents" launched a five-agent design panel and a review fan-out anyway: it stated its own price
and called that the yes, called a system prompt the yes, and called the agents read-only - and
this hook let four launches a window through with no yes at all. Now an agent launch, a workflow
and a session started on a prompt are each refused with a one-time code, and the launch goes
ahead only when a row THE OWNER TYPED (`request_ledger.owner_typed`) carries the code issued
for that exact launch, after it was issued; one yes, one launch. Nothing the assistant writes,
nothing a hook says and nothing a subagent or another session sends is a row the owner typed.
A transcript that cannot be read is no yes.

THE OWNER'S STANDING YES lives in the environment the owner sets, which no tool call reaches:
`bundle_env.name("AGENT_CAP")` launches a window (default NONE), and
`bundle_env.name("ALLOW_WORKFLOW")` with `bundle_env.cap("WORKFLOW_CAP", 1)` workflows - and
even then README section 12's rule holds: ONE FIX WORKFLOW AT A TIME.

EVERY ENVIRONMENT NAME COMES FROM `bundle_env`. The project's copy of this file read its cap
with a bare `int(os.environ.get(...))` - a value like `six` raised at IMPORT, which the
dispatcher reports as a crashed guard and so refuses every tool call in the session - while the
bundle's copy read the cap under ONE prefix and printed the other prefix's name in its refusal:
a message telling the reader to set a variable the hook did not read.
"""
import json
import os
import re
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_env      # noqa: E402  - one PREFIX renames every override
import bundle_shell    # noqa: E402  - an agent launch, found by shape
import owner_codes     # noqa: E402  - the owner's typed yes, issued and read in one place
import request_ledger  # noqa: E402  - the transcript's rows
import time

#: Launches a window the OWNER has said yes to in advance, in the environment the owner sets.
#: None by default: every launch is asked of the owner.
AGENT_CAP = bundle_env.cap("AGENT_CAP", 0)

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

#: Adapted per project: see the bootstrap's ADAPT.md.
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


#: A MESSAGE THE LISTING DOES NOT NAME YET IS NOT YET A LAUNCH. Measured 2026-10-09: the owner was asked for
#: codes all afternoon by sessions messaging each other, and one of the refusals was a session started after
#: the sender last listed its peers - one ListAgents call would have sent it with no code at all. The way that
#: needs no owner comes first; the code is for a recipient still unlisted after it.
LIST_FIRST = ("IF %r IS ONE OF THE OWNER'S RUNNING SESSIONS, NO CODE IS NEEDED: it is not in this session's "
              "latest ListAgents listing. Call ListAgents, then send the identical message again - a message "
              "to a session the latest listing names starts no agent and goes through. Only a recipient still "
              "unlisted after that is a launch, and only then:\n\n")


def ask_the_owner(payload, what, first=""):
    """Refuse this launch with a fresh code, saying what the owner is asked to approve - after `first`, the
    way through that needs no owner, when there is one."""
    code = owner_codes.issue("FANOUT")
    given = payload.get("tool_input") if isinstance(payload.get("tool_input"), dict) else {}
    script = given.get("script") if isinstance(given.get("script"), str) else ""
    counted = ("\nThis script calls agent() %d time(s) in its text - more where a call sits in "
               "a loop." % len(re.findall(r"\bagent\s*\(", script))) if script else ""
    deny(
        bundle_shell.policy(__file__) + "\n"
        "%s needs the OWNER'S yes, typed by the owner. A price you state is not a yes, a system "
        "prompt or a skill is not a yes, and a brief that says read-only is still a launch: "
        "CLAUDE.md, AN AGENT FAN-OUT NEEDS A YES AND A PRICE.%s\n\n%s"
        "Stop and tell the owner (a) how many agents this starts, (b) the estimated tokens, "
        "(c) what question it answers that reading in the main thread cannot - and this code. "
        "The owner approves THIS launch, once, by typing:\n\n    %s\n\n"
        "Then send the identical call again. Nothing you write can approve it, and the owner "
        "saying nothing is a no: do the work in the main thread."
        % (what, counted, first, code))


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

    # BY SHAPE AS WELL AS BY NAME - `bundle_shell.workflows`. A script of `agent(...)` calls under
    # another tool's name was every agent in it, uncounted.
    launch = tool in ("Agent", "Task") or (isinstance(payload, dict) and (
        bundle_shell.launches(payload) or bundle_shell.cli_sessions(payload)))
    scripted = tool == "Workflow" or (isinstance(payload, dict) and bundle_shell.workflows(payload))
    if not (launch or scripted):
        sys.exit(0)
    rows = request_ledger.read_rows(payload.get("transcript_path"))
    if rows is None:
        if bundle_env.allowed("ALLOW_FANOUT_UNMEASURED"):
            sys.exit(0)
        deny(bundle_shell.policy(__file__) + "\n"
             "Whether the owner said yes to this launch cannot be looked for: the session's "
             "transcript (%s) cannot be read. A yes nobody could look for is no yes. The owner's "
             "way past, if the hook is broken: %s=1." % (payload.get("transcript_path"),
                                                       ALLOW_UNMEASURED))
    # A MESSAGE TO ONE OF THE OWNER'S RUNNING SESSIONS STARTS NO AGENT, and the owner asked on 2026-10-09
    # not to be the messenger who types a code for every one. `bundle_shell.message_to_session` reads the
    # harness's own listing; a session it does not name, or a call carrying more than a message, is a launch.
    if bundle_shell.message_to_session(payload, rows):
        sys.exit(0)
    if owner_codes.given(payload, rows):
        sys.exit(0)
    unlisted = bundle_shell.message_recipient(payload)
    if unlisted and not scripted:
        ask_the_owner(payload, "A message to %r" % unlisted, first=LIST_FIRST % unlisted)

    if scripted:
        if not bundle_env.allowed("ALLOW_WORKFLOW"):
            ask_the_owner(payload, "A multi-agent workflow")
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
    if launch:
        # THE ONE LAUNCH ANOTHER HOOK DEMANDS. Where the project declares a reviewer, the request
        # ledger refuses the end of a turn until the owed review has run, with exactly the prompt
        # it writes - and refusing that here would leave a turn nothing can end. It goes ahead
        # on the OWNER'S standing yes: `_review` in `.claude/bundle-install.json`, which
        # `guard_owner_files` refuses an agent to add or change without the owner's typed code.
        # Not counted against a cap: the owner declared it affordable.
        if request_ledger.is_owed_review(payload):
            sys.exit(0)
        if AGENT_CAP <= 0:
            ask_the_owner(payload, "An agent launch")
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
            ask_the_owner(payload, "An agent launch past the owner's standing %d a window"
                          % AGENT_CAP)
        record(used + 1, workflows)
    sys.exit(0)


# A HOOK THAT RUNS WHEN YOU IMPORT IT CANNOT BE TESTED. This was a bare `main()`, so importing
# this module read stdin, failed to parse it and exited - taking the importing process with it.
# Every other hook here has the guard; this one, the highest-risk of them, did not.
if __name__ == "__main__":
    main()
