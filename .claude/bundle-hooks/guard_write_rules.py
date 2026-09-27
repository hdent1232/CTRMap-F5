#!/usr/bin/env python3
"""A RULE THAT CAN BE ASKED OF A WRITE IS ASKED BEFORE THE WRITE - whatever tool is doing it.

THE HOLE THIS CLOSES, measured on the project this bundle was installed into first. Its write-time
checkers - a format string that cannot print, a lock release that swallows its failure, a suite
child that can reach the live store, an unbounded subprocess in a gate - each declared an
`AT_WRITE` entry point and were asked by the project's write channel before the bytes landed. The
tier record called all fourteen "at the point of action". But that channel carried SCRIPTED edits
only. The Edit and Write tools - the way nearly every line is actually written - went straight to
disk and met those rules at the commit, after the mistake was made and built on. The fan-out
hook's Workflow refusal crashed on a `%` in its own message for exactly that reason: it was
written with the Edit tool and found afterwards.

WHAT THIS DOES. For a call that writes a file - by SHAPE, whatever the tool is called:

    `content`                      the whole new file
    `old_string` / `new_string`    one replacement in the current file (`replace_all` honoured)
    `edits`                        a list of those, applied in order

it builds the file as it WOULD be and asks `bundle_rules.ask_write` - every `AT_WRITE`,
`AT_WRITE_TREE` and `AT_RECORD` rule whose scope names that path, discovered, never listed. A rule
the write makes WORSE refuses it. So does a `.py` that compiled before and would not after.

WHAT IT REFUSES WHATEVER THE RULES SAY: a write INSIDE the repository's git folder. Only git writes
there - `.git/config` can switch the commit gate off, and `.git/gate-stamps` says a commit passed it.

WHAT IT LETS THROUGH, each on purpose:

    a file outside the project                           not this project's rules
    a replacement whose old text is not in the file      the tool will refuse it anyway
    a rule that could not answer, on a REPAIR write      the only way to fix a broken rule is to
                                                         write to it; any other write is refused
    the module a mutation run is holding                 what is on disk is not what it says
"""
import io
import json
import os
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_rules      # noqa: E402  - the one implementation of asking a rule
import bundle_shell      # noqa: E402  - the call, found by shape

LF = chr(10)

#: Adapted per project: see ADAPT.md. The scopes, directories and lock live in `bundle_rules`.
ADAPT = ()


def _replace(text, old, new, every):
    if not old or old not in text:
        return None
    return text.replace(old, new) if every else text.replace(old, new, 1)


def proposed(payload, root):
    """(rel, before, after) for a call that writes one project file, or None when it does not."""
    tool_input = (payload or {}).get("tool_input")
    if not isinstance(tool_input, dict):
        return None
    target = tool_input.get("file_path") or tool_input.get("path")
    if not isinstance(target, str) or not target:
        return None
    path = os.path.abspath(target if os.path.isabs(target) else os.path.join(root, target))
    rel = bundle_rules.rel_of(root, path)
    if rel.startswith(".."):
        return None
    if rel == bundle_rules.locked(root):
        return None
    try:
        with io.open(path, encoding="utf-8", errors="replace") as handle:
            before = handle.read()
    except OSError:
        before = None
    if isinstance(tool_input.get("content"), str):
        return rel, before, tool_input["content"]
    after = before if before is not None else ""
    edits = tool_input.get("edits")
    if isinstance(edits, list):
        steps = edits
    elif isinstance(tool_input.get("new_string"), str):
        steps = [tool_input]
    else:
        return None
    for step in steps:
        if not isinstance(step, dict) or not isinstance(step.get("new_string"), str):
            return None
        after = _replace(after, step.get("old_string"), step["new_string"],
                         bool(step.get("replace_all")))
        if after is None:
            return None
    return rel, before, after


def into_git(payload, root):
    """The project-relative path when this call writes INSIDE the repository's git folder, else ''.

    ONLY GIT WRITES THERE. `.git/config` can repoint `core.hooksPath` and switch every commit
    gate off, and `.git/gate-stamps` holds the stamps that say a commit passed the gate - a write
    to either by a tool is a gate skipped or a stamp forged, however the text reads. A name that
    merely starts with `.git` - `.gitignore`, `.githooks/` - is a project file like any other.
    """
    tool_input = (payload or {}).get("tool_input")
    target = tool_input.get("file_path") or tool_input.get("path") if isinstance(
        tool_input, dict) else None
    if not isinstance(target, str) or not target:
        return ""
    path = os.path.abspath(target if os.path.isabs(target) else os.path.join(root, target))
    rel = bundle_rules.rel_of(root, path)
    return rel if rel.replace(chr(92), "/").split("/")[0].lower() == ".git" else ""


def verdict(payload, root):
    """(exit code, message) for one write."""
    inside = into_git(payload, root)
    if inside:
        return 2, ("BLOCKED: this write to %s is inside the repository's git folder, which only "
                   "git writes. Use a git command - `git config`, `git commit` - so the hooks that "
                   "guard it are asked." % inside)
    change = proposed(payload, root)
    if change is None:
        return 0, ""
    rel, before, after = change
    # A PLANTED JUDGE CAN REFUSE A WRITE AS WRONGLY AS IT CAN ALLOW ONE, so it is not asked. The
    # moment after asks nothing of it either and records nothing, so this write is judged as a
    # change the first time the real judge is back.
    if bundle_shell.judge_under_mutation(root, bundle_rules.LOCK):
        return 0, ""
    worse = bundle_rules.ask_write(root, rel, before, after)
    if not worse:
        return 0, ""
    if bundle_rules.repairs(root, rel):
        worse = {k: v for k, v in worse.items() if v[0] >= 0}
        if not worse:
            return 0, ""
    return 2, bundle_rules.refusal("this write to %s" % rel, worse)


def main():
    try:
        payload = json.load(sys.stdin)
    except (ValueError, IOError):
        return 0
    if not isinstance(payload, dict) or not bundle_shell.is_tool_call(payload):
        return 0
    if not bundle_shell.writes(payload):
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
