#!/usr/bin/env python3
"""A TURN MAY NOT END WITH A REQUEST UNACCOUNTED FOR. The ledger is `request_ledger.py`; this is
where it is asked, and the doors into it are shut.

AT THE END OF A TURN: every request the owner made since the ledger began must be itemised, each
item resolved with checkable evidence or with words the owner was shown - and, where the project
declares a reviewer, the changed requests must carry its PASS. Anything else refuses the stop and
says exactly which request, which item, and the command that records it.

IT DOES NOT LET A SECOND IDENTICAL STOP THROUGH, unlike `guard_promise.py`, and the difference
is deliberate. That guard judges wording, where a stuck loop is possible because the same text
can be refused forever. Here every refusal has an exit that is always open - `asked`, `blocked`
and `declined` need only be said to the owner - so letting the second stop through would be the
cheapest evasion there is: end the turn twice.

BEFORE A TOOL CALL, three doors:

  * the store and its start mark are written only by `request_ledger.py`. A Write or Edit naming
    either, or a command naming either, is refused - `show` reads the ledger;
  * a launch carrying the review token must be EXACTLY the review the ledger owes, on the declared
    model and agent, and synchronous: a reviewer handed a different prompt is not independent,
    and one running in the background has no verdict when the turn tries to end;
  * a launch for a state that already has its verdict is refused - a PASS needs no second one,
    and a FAIL is answered by changing the items, not by asking again.

WHAT IT CANNOT STOP, said rather than implied: a program written to conceal that it reaches the
store. The ledger and anything the agent runs share one account.
"""
import json
import os
import re
import sys

LF = chr(10)
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import bundle_shell      # noqa: E402  - the call, found by shape, at any depth
import request_ledger    # noqa: E402  - the ledger itself: one implementation

#: The two places the ledger lives, as any spelling of a path would carry them.
DOORS = re.compile(r"\.claude[/\\]+requests\b|" + re.escape(request_ledger.MARKS), re.I)

#: The ledger's own verbs. They take no path, so an item whose words mention the store is still
#: the ledger writing itself - but only when EVERY command in the call is one of them.
LEDGER_RUN = re.compile(r"^[\"']?\S*(?:py|pythonw?[0-9.]*)(?:\.exe)?[\"']?\s+(?:-\S+\s+)*"
                        r"[\"']?\S*request_ledger\.py\b", re.I)


def deny(reason):
    json.dump({"hookSpecificOutput": {"hookEventName": "PreToolUse",
                                      "permissionDecision": "deny",
                                      "permissionDecisionReason": reason}}, sys.stdout)
    return 0


def door(payload):
    """Why this call reaches the ledger's store around the ledger, or ''."""
    named = [p for p in bundle_shell.paths(payload) if DOORS.search(str(p))]
    if named and not bundle_shell.reads_only(payload):
        return "it writes %s" % named[0]
    for command in bundle_shell.commands(payload):
        if DOORS.search(command or "") and not bundle_shell.every_command_is(
                command, LEDGER_RUN.match):
            return "the command names the ledger's store: %s" % (command or "")[:160]
    return ""


def refusal(found):
    out = ["BLOCKED: this turn ends with a request unaccounted for.", ""]
    for line in found[:8]:
        out.append("    " + line)
    if len(found) > 8:
        out.append("    ... and %d more" % (len(found) - 8))
    ledger = "python %s/request_ledger.py" % request_ledger.FOLDER
    out += [
        "",
        "  What the owner asked is read from the transcript; what it holds is yours to record:",
        "",
        "    %s show" % ledger,
        "    %s item <request> \"<one thing it asks>\"         every thing, not the gist" % ledger,
        "    %s done <item> \"<evidence with a `command` or a commit>\"" % ledger,
        "    %s answered|asked|blocked|declined <item> \"<what you told the owner>\"" % ledger,
        "",
        "  Asked, blocked and declined are always open, and each must be SAID to the owner in",
        "  your message - a refusal nobody is shown is a deletion.",
    ]
    return LF.join(out)


def main():
    try:
        payload = json.load(sys.stdin)
    except (ValueError, OSError):
        return 0                       # a hook that cannot read its own input must not wedge
    if not isinstance(payload, dict):
        return 0
    if bundle_shell.is_tool_call(payload):
        why = door(payload) or request_ledger.launch_problem(payload)
        if why:
            return deny(bundle_shell.policy(__file__) + LF + "The request ledger refuses this call: "
                        + why + ".")
        return 0
    # ONLY THE END OF THE OWNER'S TURN, and here the event's NAME is the only thing that says so.
    # `SubagentStop` has the same shape as `Stop` and hands over a SUBAGENT's transcript, whose
    # "user" rows are its brief - read as the owner, no subagent could ever finish. And a
    # `UserPromptSubmit` refused with exit 2 is the owner's message swallowed. The dispatcher is
    # wired on `PreToolUse` and `Stop` today; this keeps a wider wiring from becoming either.
    if payload.get("hook_event_name") not in (None, "Stop"):
        return 0
    found = request_ledger.problems(payload.get("session_id"), payload.get("transcript_path"),
                                    payload.get("last_assistant_message"))
    if found is None:
        # COULD NOT LOOK. Refusing would wedge the session with no exit; allowing silently would
        # read as a clean ledger. So it allows, and says so where the owner sees it.
        json.dump({"systemMessage": "request ledger: the transcript could not be read, so this "
                                    "turn's requests were NOT checked"}, sys.stdout)
        return 0
    if not found:
        return 0
    sys.stderr.write(refusal(found) + LF)
    return 2


if __name__ == "__main__":
    sys.exit(main())
