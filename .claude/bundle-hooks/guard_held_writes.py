#!/usr/bin/env python3
"""A CHANGE NO HOOK COULD READ BEFORE IT LANDED, AND THE RULES REFUSE, STOPS EVERYTHING ELSE.

THE HOLE. `guard_write_rules.py` asks every write rule of a Write or Edit call before the bytes
land, because the call carries the text. A command carries none: `cp`, `Copy-Item`, a script, a
redirect, `Set-Content`, `git stash pop` all change files no PreToolUse hook can read. On the
project this bundle was installed into first, a test file copied in that way carried an unused
import past every write-time rule, and the COMMIT gate was the first thing to see it - the
lateness the whole point-of-action design exists to remove.

WHAT THIS DOES. Before every act that is not a read, and at the end of a turn, it asks
`bundle_rules.judge_unread`: every file that changed since the tree was last judged, by any
route, judged by the same rules and the same growth test as a Write. A change they refuse is
HELD, and while anything is held every act is refused except the ones that repair it:

    a read                                    looking is how a repair starts
    a Write or Edit to a held path            the repair itself, still judged by the write hook
    a Write or Edit to a rule or a hook       a rule that is wrong must stay fixable
    a Write or Edit outside the project       not this project's to hold
    a command that names a held path          running, restoring or replacing that file -
                                              named as an argument, not in a comment
    a run of a script under a rule directory  a record is repaired by RE-MEASURING it, never by
                                              hand, and the tool that measures is the only
                                              honest way to write it; what it writes is judged
                                              exactly like everything else
    git checkout, restore or stash            back to the last committed text, never held

It cannot un-write what a command wrote: nothing can read a command's writes before it runs.
What it refuses is everything built after it, and a change nothing can be built on is what
refusing it at the write would have bought. `after_rules.py` asks the same question the moment
the command returns, so the refusal usually arrives before the next act is even chosen.

A TREE IT CANNOT READ IS NOT A CLEAN ONE. When git cannot list what changed, every act but a
read or a git command is refused - git is how the tree becomes readable again, and in a folder
that is not a repository yet, `git init` is the way in.
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_env        # noqa: E402  - one PREFIX renames every override
import bundle_rules      # noqa: E402  - the one implementation of asking a rule
import bundle_shell      # noqa: E402  - the call, found by shape

LF = chr(10)

#: Commands that only look. A held tree must stay readable, or nobody can see what to repair.
READERS = (
    r"^\s*git\s+(-C\s+\S+\s+)?(status|diff|log|show|rev-parse|ls-files|blame)\b",
    r"^\s*(ls|dir|cat|head|tail|wc|stat|file|grep|rg|findstr|pwd)\b",
    r"^\s*sed\s+-n\b(?!.*\s-i)",
    r"^\s*Get-(ChildItem|Content|Item|ItemProperty|Location)\b",
    r"^\s*(Test-Path|Select-String)\b",
)

#: git's ways back to the last committed text, which `judge_unread` never holds.
RESTORES = (r"^\s*git\s+(-C\s+\S+\s+)?(checkout|restore|stash)\b",)

#: The owner's way past, spelled once in `bundle_env`.
ALLOW = bundle_env.name("ALLOW_HELD_CHANGES")

#: Adapted per project: see ADAPT.md.
ADAPT = ("READERS", "RESTORES")


def _without_comment(command):
    """The command with an unquoted `#` comment removed - a path named there names nothing."""
    quote = None
    for index, char in enumerate(command):
        if quote:
            if char == quote:
                quote = None
        elif char in ("'", chr(34)):
            quote = char
        elif char == "#" and (index == 0 or command[index - 1].isspace()):
            return command[:index]
    return command


def names(command, rels):
    """Does the command carry one of these repo-relative paths as an argument?"""
    tokens = [t.strip("'" + chr(34)).replace(chr(92), "/")
              for t in _without_comment(command).split()]
    return any(t == rel or t.endswith("/" + rel) for t in tokens for rel in rels)


def _matches(command, patterns):
    return any(re.search(p, command, re.I) for p in patterns)


def runs_a_rule_tool(command):
    """Does the command run a Python script that lives under one of `bundle_rules.RULE_DIRS`?

    A RUNNER IN FRONT IS NOT THE PROGRAM. This read the first word, so `timeout 3000 python -B
    tools/dev/plants.py add x.json` - a record re-measured the only way a held change says it
    may be - was refused as though it built on the change. The command gate had the same blind
    spot the same day, for `timeout ... git commit --no-verify`. The program is found by the
    gate's own `pairs`, `stages` and `peeled`, so the two hooks read one command one way.
    """
    import guard_command_rules as gate                   # noqa: PLC0415 - the one peel
    held = gate.pairs(_without_comment(command))
    stages = gate.stages(held) if held else []
    if not stages:
        return False
    stage = gate.peeled(stages[0])[0]
    if not stage or not re.fullmatch(r"py|python[0-9.]*", gate.program_of(stage[0][0])):
        return False
    # THE PATH AS TYPED: the tokeniser reads a backslash as an escape, so `tools\x.py` comes back
    # as the word `toolsx.py` - the typed form is the path Windows will open.
    typed = [(t if t is not None else w).strip("'" + chr(34)) for w, t in stage[1:]]
    script = next((w for w in typed if not re.fullmatch(r"-[A-Za-z]+", w)), "")
    script = script.replace(chr(92), "/")
    script = script[2:] if script.startswith("./") else script
    dirs = [d.replace(chr(92), "/").strip("/") for d in bundle_rules.RULE_DIRS]
    return script.endswith(".py") and any(script.lower().startswith(d.lower() + "/")
                                          for d in dirs)


def looks(payload):
    """A read, by shape - and an agent launch is NOT one, though it carries no command and
    writes nothing itself: what it launches can build on anything standing in the tree."""
    return bundle_shell.reads_only(payload) and not bundle_shell.launches(payload)


def repairs(payload, root, held):
    """Is this call a repair of a held change - or a read, which is always one?"""
    if looks(payload):
        return True
    if bundle_shell.launches(payload) or bundle_shell.unreadable(payload):
        return False
    rels = set(held)
    if bundle_shell.writes(payload):
        targets = [bundle_rules.rel_of(root, t if os.path.isabs(t) else os.path.join(root, t))
                   for t in bundle_shell.paths(payload)]
        # A FILE OUTSIDE THE PROJECT IS NOT THIS PROJECT'S TO HOLD - the write hook says the
        # same. Measured: the source project's bundle is a folder beside it, and refusing a
        # write there while a change was held left the change and its fix in two places that
        # could each only be written after the other.
        if not targets or not all(t in rels or bundle_rules.repairs(root, t) or t == ".."
                                  or t.startswith("../") for t in targets):
            return False
    text = bundle_shell.text(payload)
    if not text:
        return bundle_shell.writes(payload)
    return bundle_shell.every_command_is(
        text, lambda one: (_matches(one, READERS + RESTORES) or names(one, rels)
                           or runs_a_rule_tool(one)))


def verdict(payload, root):
    """(exit code, message) for one call, or for the end of a turn."""
    call = bundle_shell.is_tool_call(payload)
    if call and looks(payload):
        return 0, ""
    if not call and payload.get("stop_hook_active"):
        return 0, ""
    if bundle_env.allowed("ALLOW_HELD_CHANGES"):
        return 0, ""
    judged = bundle_rules.judge_unread(root)
    if judged["blind"]:
        text = bundle_shell.text(payload) if call else ""
        if text and bundle_shell.every_command_is(
                text, lambda one: _matches(one, READERS + (r"^\s*git\b",))):
            return 0, ""
        return 2, ("BLOCKED: what changed in the tree cannot be read (%s), so no write that "
                   "arrived by a command can be judged. A tree nobody can read is not a clean "
                   "one. Only reads and git go through until it can be read - in a folder that "
                   "is not a repository yet, `git init`. The owner's way past: %s=1."
                   % (judged["blind"], ALLOW))
    if not judged["held"]:
        return 0, ""
    if call and repairs(payload, root, judged["held"]):
        return 0, ""
    said = bundle_rules.held_refusal(judged)
    if not call:
        said += LF + LF + "  The turn cannot end on it: a held change left standing is the next "
        said += "turn's foundation."
    return 2, said


def main():
    try:
        payload = json.load(sys.stdin)
    except (ValueError, IOError):
        return 0
    if not isinstance(payload, dict):
        return 0
    root = bundle_rules.repo_root(__file__)
    if root is None:
        return 0
    code, said = verdict(payload, root)
    if code:
        sys.stderr.write(said + LF)
    return code


if __name__ == "__main__":
    sys.exit(main())
