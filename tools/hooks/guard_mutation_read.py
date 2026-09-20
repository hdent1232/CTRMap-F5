#!/usr/bin/env python3
"""Refuse to READ a module that a mutation run is holding open.

WHAT HAPPENED, 2026-09-01. A sweep of `dtengine/data/run.py` was in flight. I ran an AST walk
over the same file to group the surviving mutants by function and got back a table that looked
entirely plausible:

    <module>              267   lines 539-1684
    _sync_filing_index     38   lines 389-415

`_sync_filing_index` is at line 731. Line 762 was reported as belonging to no function at all.
`mutate.py` had the module replaced on disk with `ast.unparse` output - every comment gone,
every line renumbered - and my walk parsed THAT. Nothing errored. Nothing warned. The answer
was confident, wrong, and about a file that exists only between the lock being taken and the
`finally` that puts the real one back.

WHY THE EXISTING GUARDS DO NOT REACH IT. `tools/dev/safe.py rewrite` refuses under this lock and
`.githooks/pre-commit` refuses a commit under it - both sit on the WRITE. Reading is the earlier
half and it had nothing, which matters more than it sounds: several tools under `tools/audit/`
write a whole-tree baseline from what they read, so a read taken mid-sweep does not just mislead
a reader, it can RECORD the unparsed source as a measurement. That is the same class as the four
narrowed-run-replaces-whole-tree-record instances `tools/audit/baselines.py` was built for.

THE RULE. While `.mutation-in-flight` exists, a shell command may not read the file it names,
may not read a directory containing it, and may not run a tool under `tools/audit/`.

    `git ...`                     allowed - it reads the index and HEAD, not the mutated
                                  worktree, and `git checkout -- <file>` is the recovery
                                  command `mutate.py`'s own refusal tells you to run
    `tools/audit/mutate.py`       allowed - it refuses on its own and NAMES the file to
                                  restore, which is the message you actually need
    anything else naming it       refused

Not scoped to `tools/`, because the command that produced the wrong table was a bare
`python -c`. A guard that only knows about this project's own tools would have watched it go by.

TO READ IT ANYWAY: the owner sets DTENGINE_ALLOW_MUTATION_READ=1 for that session.
"""
import json
import os
import re
import sys

LOCK = ".mutation-in-flight"
BYPASS = "DTENGINE_ALLOW_MUTATION_READ"

#: Tools whose whole job is to read the tree and answer questions about it. Running one while a
#: module is unparsed on disk measures the unparsed module.
AUDIT_TOOL = re.compile(r"tools[/\\]audit[/\\]([A-Za-z_][A-Za-z0-9_]*)\.py")

#: The interpreters that RUN one. `grep -n x tools/audit/sweep.py` reads a tool's source and is
#: honest work; this rule is about running it, and its own docstring says so. Refusing the read
#: was the second false positive this guard produced, and a guard that fires on honest work is
#: the one people learn to switch off.
_RUNNERS = ("python", "python3", "python.exe", "py", "pythonw", "uv", "coverage")


def runs_it(segment, tool):
    """True when this segment EXECUTES the audit tool rather than merely naming it."""
    words = segment.split()
    if not words:
        return False
    first = os.path.basename(words[0].strip("(" + chr(39) + chr(34))).lower()
    if first in _RUNNERS:
        return True
    # `./tools/audit/x.py` or `tools/audit/x.py` invoked directly.
    return tool.group(0).replace(chr(92), "/") in words[0].replace(chr(92), "/")

#: The one that manages the lock itself, and whose refusal is more useful than this one.
EXEMPT_TOOL = "mutate.py"


def locked_module(root):
    """The repo-relative path `mutate.py` recorded in the lock, or None when no run is in flight.

    An empty or unreadable lock reads as NO lock rather than as an unnamed one: this hook may
    never be the reason a turn cannot proceed on a file nobody is mutating.
    """
    try:
        with open(os.path.join(root, LOCK), encoding="utf-8") as handle:
            held = handle.read().strip()
    except OSError:
        return None
    return held.replace(chr(92), "/") or None


def readers_of(command, locked):
    """The path spellings in `command` that resolve to the locked file or a parent of it.

    TWO RULES, and the difference between them is the difference between a guard and a nuisance.

    The file itself is matched ANYWHERE in the text, quotes and all, because the command that
    started this carried the path inside a `python -c` body and any tokeniser would have lost it.

    A parent directory is matched only as a whole TOKEN. `grep -rn x dtengine/` reads the mutated
    module just as surely as naming it does - but `dtengine/data/asof.py` CONTAINS the string
    `dtengine/data/`, and refusing an honest read of an unmutated sibling is how a guard gets
    switched off. Both separators either way: the lock stores forward slashes and a shell line
    here is as likely to carry backslashes.
    """
    found = []
    for spelling in (locked, locked.replace("/", chr(92))):
        if spelling in command:
            found.append(locked)
            break
    for raw in command.split():
        token = raw.strip(chr(39) + '"' + "()").replace(chr(92), "/").rstrip("/")
        if token and locked.startswith(token + "/"):
            found.append(token + "/")
    return found


def segments(command):
    """The command string split on the separators, so one `git` does not excuse its neighbour.

    Deliberately cruder than `guard_heredoc.commands`: this hook searches RAW text, because the
    path that started all this sat inside a quoted `python -c` body and blanking quoted runs
    would have hidden it.
    """
    return [s for s in re.split(r"&&|\|\||[;|\n]", command) if s.strip()]


def verdict(command, locked):
    """(deny?, reason). Pure, so a test can drive it without a lock file or a hook runner."""
    if not locked:
        return False, None
    for segment in segments(command):
        words = segment.split()
        if not words:
            continue
        if EXEMPT_TOOL in segment:
            continue
        if os.path.basename(words[0].strip("(")) in ("git", "git.exe"):
            continue
        named = readers_of(segment, locked)
        if named:
            return True, (
                "BLOCKED BY PROJECT POLICY (.claude/hooks/guard_mutation_read.py).\n"
                "A mutation run is holding %s open, so what is on disk right now is "
                "`ast.unparse` output: comments gone, every line renumbered, one operator "
                "deliberately wrong. This command reads %s.\n\n"
                "It happened: an AST walk over a mutated run.py reported `_sync_filing_index` "
                "at lines 389-415 when it is at 731, and line 762 as belonging to no function. "
                "Nothing errored - the answer was confident and about a file that exists only "
                "between the lock and the finally that restores it.\n\n"
                "Wait for the run to finish. To read the committed content meanwhile:\n"
                "    git show HEAD:%s\n"
                "If no run is actually in flight, the lock is stale - `mutate.py` leaves it "
                "deliberately when a restore fails, and it names the file to `git checkout`.\n"
                % (locked, ", ".join(named), locked))
        tool = AUDIT_TOOL.search(segment)
        if tool and runs_it(segment, tool):
            return True, (
                "BLOCKED BY PROJECT POLICY (.claude/hooks/guard_mutation_read.py).\n"
                "A mutation run is holding %s open, and `%s.py` reads the tree to answer a "
                "question about it. Whatever it walks, it will walk the unparsed module - and "
                "several tools here WRITE a whole-tree baseline from what they read, so this is "
                "not only a wrong answer on screen, it is a wrong answer recorded.\n\n"
                "Wait for the run to finish, then run it again.\n"
                % (locked, tool.group(1)))
    return False, None


sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shellin                                    # noqa: E402  (path set above)

HOOK = "guard_mutation_read.py"


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
        deny, reason = verdict(command, locked_module(
            os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()))
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
