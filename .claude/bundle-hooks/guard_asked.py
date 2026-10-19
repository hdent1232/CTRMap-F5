#!/usr/bin/env python3
"""A TURN MAY NOT END WHILE ANY MESSAGE THE OWNER EVER SENT THIS PROJECT IS UNACCOUNTED FOR.

The register is `asked.py`; this is where it is asked, and the doors into it are shut.

THE FAILURE IT EXISTS FOR, 2026-09-26. Asked for "every single thing I have asked you to do that
still has not been done", the agent listed its own session ledger - 42 requests since that
morning - while the project's transcripts held 986 owner messages back to July. `guard_requests`
was satisfied, because it answers for the requests since a SESSION's ledger began, and that is
all it was ever asked. Nothing asked about the rest. Now something does, at every turn's end, in
every session: each owner message in every transcript of this project - the scope derived from
the transcripts' own record of where this project has lived - has a disposition, or is held by a
session ledger, or the turn does not end.

BEFORE A TOOL CALL, one door: the register is written by `asked.py` and nothing else. A Write or
Edit naming it, or a command naming it that is not `asked.py` itself, is refused - a
disposition typed around the verbs would skip every check the verbs make.

OFF UNTIL THE PROJECT DECLARES IT (`asked.declared`, `_asked` in `.claude/bundle-install.json`): it refuses nothing
and says nothing in a project that keeps no register, and a declarations file it cannot read is said at the turn's end.

WHAT IT CANNOT STOP, said rather than implied. It makes the RECORD complete and every closure
checkable; it cannot judge whether a disposition is honest - a real request filed as `context`
passes it. That is the reviewer's to catch, and the register is tracked so every such line is
also a line in a diff. And a program written to conceal that it reaches the register is not seen;
the register and anything the agent runs share one account.
"""
import json
import os
import re
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

LF = chr(10)
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import asked             # noqa: E402  - the register itself: one implementation
import bundle_shell      # noqa: E402  - the call, found by shape, at any depth

#: The register as any spelling of its path would carry it.
DOORS = re.compile(r"[/\\]+".join(re.escape(part) for part in re.split(r"[/\\]", asked.REGISTER)),
                   re.I)

#: The register's own verbs: the only command allowed to name it.
ASKED_RUN = re.compile(r"^[\"']?\S*(?:py|pythonw?[0-9.]*)(?:\.exe)?[\"']?\s+(?:-\S+\s+)*"
                       r"[\"']?\S*asked\.py\b", re.I)
#: git, which records and restores a tracked file.
GIT_RUN = re.compile(r"^\s*git\b", re.I)


def deny(reason):
    json.dump({"hookSpecificOutput": {"hookEventName": "PreToolUse",
                                      "permissionDecision": "deny",
                                      "permissionDecisionReason": reason}}, sys.stdout)
    return 0


def door(payload):
    """Why this call reaches the register around `asked.py`, or ''."""
    named = [p for p in bundle_shell.paths(payload) if DOORS.search(str(p))]
    if named and not bundle_shell.reads_only(payload):
        return "it writes %s" % named[0]
    for line in bundle_shell.commands(payload):
        # PER COMMAND, NOT PER LINE: in `cd x && git add docs/asked.json` only the part that names
        # the register is asked about - and in `git add ... && echo {} > docs/asked.json` the
        # second part is refused on its own. GIT IS ALLOWED: the register is TRACKED, so staging,
        # committing, diffing and restoring it are how it is recorded and repaired, and every
        # disposition is re-checked at the end of the turn whatever wrote it. Refusing git
        # refused this file's own first commit.
        for command in bundle_shell.split_commands(line or ""):
            if DOORS.search(command) and not bundle_shell.every_command_is(
                    command, lambda part: ASKED_RUN.match(part) or GIT_RUN.match(part)):
                return "the command names the register: %s" % command[:160]
    return ""


def refusal(found):
    tool = "python %s/asked.py" % asked.FOLDER
    out = ["BLOCKED: this turn ends with an owner message unaccounted for, somewhere in this",
           "project's history - not only in this session's ledger.", ""]
    out += ["    " + line for line in found[:8]]
    if len(found) > 8:
        out.append("    ... and %d more" % (len(found) - 8))
    out += ["",
            "  Every owner message, in every transcript of this project, is linked to an item,",
            "  answered, or context with a reason - and an item closes only on evidence:",
            "",
            "    %s messages          the ones still owing" % tool,
            "    %s item <id> <release> \"<what was asked>\"" % tool,
            "    %s link <m...> <item ids>" % tool,
            "    %s pending           the complete open list" % tool]
    return LF.join(out)


def main():
    # THE ONE DOOR IN (`bundle_shell.payload_text`): `json.load(sys.stdin)` decodes the harness's UTF-8 in the console
    # code page on Windows, so a non-ASCII path reached the door check as other characters than the call carried.
    try:
        payload = json.loads(bundle_shell.payload_text())
    except (ValueError, OSError):
        return 0                       # a hook that cannot read its own input must not wedge
    if not isinstance(payload, dict):
        return 0
    config, trouble = asked.declared()
    if config is None and not trouble:
        return 0                       # not switched on here
    if bundle_shell.is_tool_call(payload):
        why = door(payload)
        if why:
            return deny(bundle_shell.policy(__file__) + LF + "The register refuses this call: "
                        + why + ". Use `python %s/asked.py`." % asked.FOLDER)
        return 0
    # ONLY THE END OF THE OWNER'S TURN - `SubagentStop` has the same shape and a subagent's
    # transcript, whose "user" rows are its brief.
    if payload.get("hook_event_name") not in (None, "Stop"):
        return 0
    found = asked.problems(None, payload.get("transcript_path"), payload.get("session_id"))
    if found is None:
        json.dump({"systemMessage": "owner-message register: the transcripts could not be read, "
                                    "so this project's history was NOT checked"}, sys.stdout)
        return 0
    if not found:
        return 0
    sys.stderr.write(refusal(found) + LF)
    return 2


if __name__ == "__main__":
    sys.exit(main())
