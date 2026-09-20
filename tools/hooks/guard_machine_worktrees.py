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


def alive(pid):
    """Whether `pid` is a live process, answering ALIVE whenever it cannot tell.

    `os.kill(pid, 0)` IS NOT A PROBE ON WINDOWS. Python routes it through
    `OpenProcess(PROCESS_TERMINATE)` for every signal, so a process this account may not open
    for termination raises `[WinError 87]` - measured on 2026-09-08 against the process table,
    where it called three live processes dead, one of them the sweep runner.

    UNCERTAINTY IS ALIVE HERE, and that is the whole correction. The failure this file exists
    for is an absence concluded from something unreadable, so every answer short of "the
    operating system says this pid does not exist" counts as running.

    Standalone on purpose - the `.claude/hooks/` guards import nothing from this repository, so
    a broken module cannot take a refusal down with it.
    """
    try:
        pid = int(pid)
    except (TypeError, ValueError):
        return False
    if pid <= 0:
        return False
    if os.name != "nt":
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return False
        except OSError:
            return True                      # permission, or anything else: not an absence
        return True
    import ctypes
    kernel32 = ctypes.windll.kernel32
    handle = kernel32.OpenProcess(0x1000, False, pid)      # QUERY_LIMITED_INFORMATION
    if not handle:
        # 87 is ERROR_INVALID_PARAMETER - Windows' answer for a pid that does not exist. Every
        # other failure is a permission answer, and permission-denied is not absence.
        return kernel32.GetLastError() != 87
    code = ctypes.c_ulong()
    ok = kernel32.GetExitCodeProcess(handle, ctypes.byref(code))
    kernel32.CloseHandle(handle)
    if not ok:
        return True                          # could not read the exit code: not an absence
    return code.value == 259                 # STILL_ACTIVE


def sweep_pid():
    """The pid of the bounded run that owns the worktrees, or None when no run holds the lock."""
    try:
        with io.open(RUN_LOCK, encoding="utf-8") as handle:
            held = json.load(handle)
    except (IOError, OSError, ValueError):
        return None
    return held.get("pid")


def touches_a_worktree(command):
    return bool(WORKTREE.search(command or ""))


def is_read_only(command):
    text = (command or "").strip()
    return any(re.search(pattern, text) for pattern in READ_ONLY)


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


sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shellin                                    # noqa: E402  (path set above)

HOOK = "guard_machine_worktrees.py"


def decide(payload):
    """(deny, reason) for ANY tool call that runs a shell, whatever the tool is named.

    Wired under `"matcher": "Bash"` and reading `tool_input.command`, this answered for one
    tool name while the session also had `PowerShell`. It now asks a shape. A tool call that
    runs no shell at all is not this hook's business; a tool call carrying something it cannot
    read is refused, because an unreadable command is not an absent one.
    """
    found = shellin.commands(payload)
    blind = shellin.unreadable(payload)
    if blind:
        return True, shellin.blind_refusal(HOOK, "; ".join(blind), "<no bypass>")
    if not found:
        return False, None
    command = shellin.text(payload)
    if not touches_a_worktree(command) or is_read_only(command):
        return False, None
    pid = sweep_pid()
    if pid is None or not alive(pid):
        return False, None
    return True, refusal(pid, command)


def main():
    try:
        payload = json.load(sys.stdin)
    except (ValueError, IOError):
        return 0
    deny, reason = decide(payload)
    if not deny:
        return 0
    sys.stderr.write(reason + chr(10))
    return 2


if __name__ == "__main__":
    sys.exit(main())
