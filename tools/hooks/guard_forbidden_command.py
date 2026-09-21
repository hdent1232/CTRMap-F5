#!/usr/bin/env python3
"""Commands the owner has said must never run here. Refused at the call, not noticed after.

WHY THIS EXISTS. `CLAUDE.md` lists these inside the body of GAME DATA IS READ-ONLY:

    Never run Tidewater. Never `git stash`. Never hand-edit a NetBeans `initComponents`
    block. No Bash heredocs for Java or Python.

`rule_map.py` reported GAME DATA IS READ-ONLY as enforced, because it maps a rule's HEADLINE
to a mechanism - and `guard_game_data.py` does enforce the headline. Three of the four
imperatives inside that same paragraph had nothing behind them at all, and the map showed
green over the top of them. A rule can be enforced and its clauses unenforced at the same
time, and nothing was looking at the clauses.

WHAT EACH ONE IS FOR:

  Tidewater - the owner's standing instruction. It is not run here, by anything, ever.

  `git stash` - it moves uncommitted work somewhere no guard looks. This project's own rule is
  COMMIT BEFORE RUNNING ANYTHING THAT MUTATES SOURCES, because the mutation harness ends in
  `git reset --hard` and nearly erased an uncommitted fix once already. A stash is that same
  work, hidden better: `git stash drop`, or one left behind across a branch change, loses it
  outright. A commit can be amended; a stash has to be remembered.

The list is data - `forbidden_commands.txt` beside this file - so the hook stays byte-identical
across projects and each one names its own.

TO RUN ONE ANYWAY: the owner sets CTRMAP_ALLOW_FORBIDDEN=1 for that session. It is their
instruction, so it is theirs to lift.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hook_env                                   # noqa: E402
import shellin                                    # noqa: E402  (path set above)

HOOK = "guard_forbidden_command.py"
BYPASS = hook_env.name("ALLOW_FORBIDDEN")

#: Used only when the list beside this file cannot be read. EMPTY ON PURPOSE is wrong here:
#: a hook whose list went missing must not quietly permit everything, so an unreadable list
#: is reported by decide() rather than treated as "nothing is forbidden".
_LIST = "forbidden_commands.txt"


def forbidden():
    """(phrases, trouble). A list this cannot read is NOT an empty list."""
    beside = os.path.join(os.path.dirname(os.path.abspath(__file__)), _LIST)
    try:
        with open(beside, encoding="utf-8") as handle:
            out = tuple(line.strip() for line in handle
                        if line.strip() and not line.startswith("#"))
        return out, None
    except OSError as cannotRead:
        return (), "%s could not be read (%s)" % (_LIST, cannotRead)


def verdict(command, phrases):
    """(deny, reason) for one shell command.

    Matched on a word boundary so `git stash` is caught and a path called `stashed_notes.txt`
    is not - a guard that fires on an unrelated filename is one that gets switched off.
    """
    for phrase in phrases:
        if re.search(r"(?<![\w.-])" + re.escape(phrase) + r"(?![\w-])", command, re.I):
            return True, refusal(phrase, command)
    return False, None


def refusal(phrase, command):
    return (
        "BLOCKED BY PROJECT POLICY (.claude/hooks/%s).\n"
        "`%s` must never run in this project. The owner said so, in CLAUDE.md, inside the "
        "body of GAME DATA IS READ-ONLY:\n"
        "    %s\n\n"
        "That rule reported ENFORCED for a week, because the map checks a rule's headline and "
        "this is a clause inside it. A rule can be enforced and its clauses unenforced at the "
        "same time.\n\n"
        "If it is `git stash`: commit instead. The mutation harness ends in `git reset --hard` "
        "and nearly erased an uncommitted fix once already; a stash is that same work hidden "
        "somewhere no guard looks, and a dropped or forgotten one loses it outright.\n\n"
        "The owner lifts this with %s=1, because it is their instruction.\n"
        % (HOOK, phrase, command.strip()[:120], BYPASS))


def decide(payload):
    if os.environ.get(BYPASS) == "1":
        return False, None
    blind = shellin.unreadable(payload)
    if blind:
        return True, shellin.blind_refusal(HOOK, "; ".join(blind), BYPASS)
    commands = shellin.commands(payload)
    if not commands:
        return False, None
    phrases, trouble = forbidden()
    if trouble:
        #: AN UNREADABLE LIST IS NOT AN EMPTY ONE. Permitting everything because the list
        #: went missing is the shape that turns a guard off without anybody deciding to.
        return True, (
            "BLOCKED BY PROJECT POLICY (.claude/hooks/%s).\n"
            "The list of commands that must never run here could not be read: %s.\n\n"
            "That is not the same as nothing being forbidden. Restore %s, or set %s=1.\n"
            % (HOOK, trouble, _LIST, BYPASS))
    for command in commands:
        deny, reason = verdict(command, phrases)
        if deny:
            return deny, reason
    return False, None


def main():
    import json
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
