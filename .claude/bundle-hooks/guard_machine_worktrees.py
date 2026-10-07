#!/usr/bin/env python3
"""THE SWEEP'S WORKTREES ARE NOT MINE TO WRITE TO WHILE IT IS RUNNING.

WHAT THIS COST, on 2026-09-14 at 03:29, and every step of it was mine.

I tried to stop a sweep so it would restart with a fix. The stop was
`Stop-Process -Force -ErrorAction SilentlyContinue`, which SWALLOWED ITS OWN FAILURE - the
workers belonged to a session this account may not terminate. I then verified with

    Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'run_named' }

`CommandLine` is EMPTY for any process this account cannot open, so the filter matched nothing
and I read that as "no sweep processes left". Twelve workers were alive the whole time. A query
that could not READ a process reported it as ABSENT.

Believing the machine stopped, I ran `git checkout -- dtengine/data/universe.py` in eleven
worktrees. Those eleven workers were inside `named_baseline_is_green`, which writes the module
UNPARSED to ask whether the suite survives the unparse with nothing mutated. Replacing that file
with the original mid-check makes the answer green for the wrong reason - and a green baseline is
what licenses every kill in the run. All 134 verdicts for that module were discarded.

WHY THIS EXISTS RATHER THAN A NOTE. `os.kill(pid, 0)` reporting a live process as dead was found
here on 2026-09-08, written down, and closed by `tools/audit/liveness.py` - which walks THIS
REPOSITORY'S PYTHON. It cannot see a PowerShell command I type into a shell. One instance was
closed and the class was not, so the same mistake arrived through the door nothing was watching.

THE PREDICATE IS THE CONDITION, NOT A CAUSE. Any command naming one of the machine's worktrees is
refused while a sweep run's process is not DEFINITELY GONE - and `alive()` answers True when it
cannot tell, because "I could not read it" is the exact answer that caused this. Reading is
always allowed: the way to find out what the machine is doing must never be the thing that is
blocked.

WHEN THE MACHINE IS GENUINELY STOPPED this allows everything, which is the whole escape. There
is no flag and no acknowledgement file, because the only question it asks is one the operating
system answers.
"""
import io
import json
import os
import re
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_shell      # noqa: E402  - the command, found by shape, at any depth

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
STATE = os.path.join(ROOT, ".sweep")
RUN_LOCK = os.path.join(STATE, "running")

#: The machine's worktrees, as they appear in a command. `sweep.py` creates `.sweep/tree-N` per
#: worker; matched on the PREFIX so tree-0 and tree-17 need no enumeration, and on both
#: separators because a command may carry either on Windows.
WORKTREE = re.compile(r"\.sweep[/\\]tree-", re.IGNORECASE)

#: READING IS ALWAYS ALLOWED. Finding out what the machine is doing is the remedy for not
#: knowing, and a guard that blocks its own diagnosis is one somebody switches off. Everything
#: here answers a question and changes nothing; `git checkout`, `git restore`, `git clean`,
#: `git reset`, a redirection and every remove verb are deliberately NOT here.
READ_ONLY = (
    r"^\s*git\s+(-C\s+\S+\s+)?(status|diff|log|show|rev-parse|ls-files|worktree\s+list)\b",
    r"^\s*(ls|dir|cat|head|tail|wc|stat|file|du)\b",
    r"^\s*(grep|rg|findstr)\b",
    r"^\s*Get-(ChildItem|Content|Item|ItemProperty)\b",
    r"^\s*Test-Path\b",
)

#: Adapted per project: see ADAPT.md.
ADAPT = ("STATE", "RUN_LOCK", "WORKTREE", "READ_ONLY")


#: THE ONE PROBE OF WHETHER A PROCESS LIVES, beside every other reading of the machine in
#: `bundle_shell`. This file and `guard_blocked_runner` each kept a copy, and the copies disagreed
#: on POSIX: this one read a permission error as ALIVE, that one as dead.
alive = bundle_shell.alive


#: What `sweep_pid` answers for a lock that is THERE and cannot be read.
UNREADABLE = "unreadable"


def sweep_pid():
    """The pid of the bounded run that owns the worktrees, None when no run holds the lock - or
    UNREADABLE when the lock is there and cannot be read.

    A LOCK NOBODY CAN READ IS NOT AN ABSENT ONE. This read both as None, so a run lock caught
    half-written read as no run at all - the "could not look" collapsed into "not there" that
    this file's own docstring is about, inside it."""
    if not os.path.exists(RUN_LOCK):
        return None
    try:
        with io.open(RUN_LOCK, encoding="utf-8") as handle:
            held = json.load(handle)
    except (IOError, OSError, ValueError):
        return UNREADABLE
    return held.get("pid") if isinstance(held, dict) else UNREADABLE


def touches_a_worktree(command):
    return bool(WORKTREE.search(command or ""))


def is_read_only(command):
    """Whether EVERY command on the line only reads.

    ASKED PER COMMAND. This matched the patterns against the whole line, so `cat x; rm -rf
    .sweep/tree-3` was "read-only" because it BEGAN with a read - the cheapest way past the
    refusal was to put a read in front of the write.
    """
    return bundle_shell.every_command_is(
        command, lambda one: any(re.search(pattern, one) for pattern in READ_ONLY))


def written_worktree_paths(payload):
    """The paths a WRITING non-shell call names inside a worktree. Empty for a read.

    A COMMAND IS ONE WAY TO WRITE INTO A WORKTREE. `Edit` and `Write` carry a `file_path` and no
    command, and this hook - which exists because eleven worktree copies of a module were
    restored mid-baseline - could not see them at all.
    """
    if not bundle_shell.writes(payload):
        return []
    return [p for p in bundle_shell.paths(payload) if WORKTREE.search(p)]


def refusal(pid, command):
    return "\n".join([
        "BLOCKED: a sweep is running as pid %s and this writes inside its worktrees." % pid,
        "",
        "    " + (command or "").strip()[:200],
        "",
        "  `.sweep/tree-N` belongs to a worker. While a run holds the lock, a worker is",
        "  writing that module: `mutate.py` puts a mutant on disk and restores it in a",
        "  `finally`, and `named_baseline_is_green` writes it UNPARSED to ask whether the",
        "  suite survives the unparse with nothing mutated. Anything of mine landing in that",
        "  window makes a verdict mean something other than what it says.",
        "",
        "  IT HAPPENED, 2026-09-14 03:29. I believed the sweep was stopped because",
        "  `Stop-Process -ErrorAction SilentlyContinue` swallowed its failure and a",
        "  `Where-Object { $_.CommandLine -match ... }` filter matched nothing - CommandLine is",
        "  empty for a process this account cannot open, so a query that could not READ a",
        "  process reported it ABSENT. Twelve workers were alive. I restored eleven worktree",
        "  copies of a module mid-baseline, and all 134 verdicts were discarded.",
        "",
        "  Reading is not blocked: git status, git diff, git log, ls, cat, head, tail and grep",
        "  inside those trees all still run. Find out what it is doing first.",
        "",
        "  There is no flag for this. When the operating system says that pid is gone, this",
        "  allows everything - and 'I could not tell' counts as running, because that is the",
        "  answer that caused it.",
    ])


def main():
    try:
        payload = json.load(sys.stdin)
    except (ValueError, IOError):
        return 0
    if not isinstance(payload, dict) or not bundle_shell.is_tool_call(payload):
        return 0
    command = bundle_shell.text(payload)      # every command in the call, at any depth
    written = written_worktree_paths(payload)
    by_command = touches_a_worktree(command) and not is_read_only(command)
    if not by_command and not written:
        return 0
    pid = sweep_pid()
    if pid == UNREADABLE:
        sys.stderr.write(refusal("(unknown - %s exists and cannot be read; if no run is in "
                                 "flight, remove it)" % os.path.relpath(RUN_LOCK, ROOT),
                                 command if by_command else "writes " + ", ".join(written))
                         + "\n")
        return 2
    if pid is None or not alive(pid):
        return 0
    sys.stderr.write(refusal(pid, command if by_command else "writes " + ", ".join(written))
                     + "\n")
    return 2


if __name__ == "__main__":
    sys.exit(main())
