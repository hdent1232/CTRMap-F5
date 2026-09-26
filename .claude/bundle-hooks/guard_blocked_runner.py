"""REFUSE MY COMMANDS WHILE THE RUNNER IS IDLE WAITING ON MY UNCOMMITTED TOOLING.

WHAT THIS COST. On 2026-09-07 the sweep runner waited 66 times over 5.5 hours. Every wait
printed the name of the single file it was blocked on - `tools/audit/sweep.py`, uncommitted by
me. The refusal it was obeying was CORRECT: a worker IMPORTS `sweep.py`, `mutate.py`,
`covering.py`, `exercised.py`, `noop_mutants.py` and `safe.py` from the working tree, not from
its `git worktree` checkout of HEAD, so an uncommitted one changes which mutations exist and
what a verdict means.

Nothing was wrong with the guard or with the message. What was missing is that NOTHING MADE ME
LOOK, and my response on finding it was to delete the guard rather than commit the file.

THE THREE TIERS, and this project has only ever been saved by the third:

    a convention   "remember to commit"            - the whole history here is these failing
    a detector     the runner printed 66 lines     - it printed 66 lines
    a refusal      this file                       - `guard_heredoc`, `guard_background_pipe`

So it sits at the point of MY action, not on the damage. Past the threshold every command is
refused except the ones that GET TO A COMMIT - git, the suite, and the two verification tools -
because a guard that blocks the way out of the situation it describes is worse than none. That
sentence is already in this repository, written about `guard_mutation_read.py` allowing `git`.

WHY 20 MINUTES, and it is derived rather than chosen: the runner polls every 300 seconds, so 20
minutes is four polls. Replayed against the real 5.5-hour outage it fires on the fourth wait
instead of the sixty-sixth - the same shape as `convergence.py check`, which fires at round 3 of
13. A threshold below one poll would fire before the runner has even noticed.

IT CANNOT FIRE WHEN NOBODY IS WAITING. No record, a record whose runner PID is dead, or a
record younger than the threshold all allow. A stale lock that refuses forever is the failure
this project has been wedged by three times, so the dead-PID case is checked explicitly rather
than trusted to the runner's cleanup.

EVERY STEP TOWARD THE COMMIT IT DEMANDS GOES THROUGH. Measured 2026-09-26 on the project this
came from, it refused the remedies its own refusal needed, and only the owner stopping the runner
released it: the commit was refused by the tiers ratchet, whose remedy `tools/audit/tiers.py
record` this refused; a commit-message refusal needed an edit to the message file in a scratch
folder, and Write was refused; `env -u X git commit` was read as new work; and the request
ledger's verbs were refused while the turn could not end without them. So these go through, and
only new work on the tree waits:

    git, the suite                            as before
    a run of a script under RULE_DIRS or the  how a record is re-measured (guard_held_writes allows
      hooks folder                            the same), and the request ledger's verbs
    each of these behind a wrapper            `env`, `timeout`, `bash -c` - read at the one door,
                                              `bundle_shell.every_command_is`
    a write outside the repository            a scratch commit message is not work on the tree
    a write to the tooling it waits on        the commit it demands is of those files

AND IT SAYS WHOSE THE TOOLING IS ONLY WHEN IT CAN KNOW. It told a second session "YOUR
uncommitted tooling" about files it had never touched. Whose each file is, is read from the
session's own transcript of writes; a file it shows no write to is said to be of unknown
authorship, and the way out offered is to ask, not to commit someone else's work.
"""
import io
import json
import os
import re
import subprocess
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_rules      # noqa: E402  - RULE_DIRS, where a record's tools live
import bundle_shell      # noqa: E402  - the command, found by shape, at any depth
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BLOCKED = os.path.join(ROOT, ".sweep", "blocked.json")

#: Where this guard sits, relative to the project: the hooks folder, found rather than spelled.
HOOKS_REL = os.path.relpath(os.path.dirname(os.path.abspath(__file__)), ROOT).replace(os.sep, "/")

#: Four polls of the runner's own 300-second cycle. Not a taste: at one poll it would fire
#: before the runner had finished noticing, and at sixty-six it is the outage it exists for.
THRESHOLD = 20 * 60

#: No console window for the git probe below. This runs on EVERY Bash command, and a hook that
#: blinks a window each time is one somebody switches off - which is the whole failure this file
#: was written about.
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

#: THE WAY OUT. Everything here leads to a commit - staging and committing, the suite that must
#: pass first, and the two tools whose refusals stand between an edit and a commit. Refusing
#: these would leave no reachable action at all, which is how a guard gets switched off.
#:
#: ANCHORED, AND ASKED OF EVERY COMMAND. These were searched anywhere in the whole line, so
#: `git status; <anything at all>` was an escape, and so was any command that merely MENTIONED
#: `tools/dev/safe.py` as an argument. The cheapest way past this refusal was to put the way
#: out in front of the thing being refused. Now each command on the line must itself BE a way
#: out - `bundle_shell.every_command_is` - and a tool only counts when it is the program RUN.
_PY = r"^(?:python[0-9.]*(?:\.exe)?|py)(?:\s+-[A-Za-z0-9.]+)*"
#: A script under RULE_DIRS or the hooks folder, named as a path from the project root.
_TOOL_DIRS = tuple(d.replace(os.sep, "/").strip("/") for d in bundle_rules.RULE_DIRS) \
    + (HOOKS_REL,)
_TOOL = _PY + r"\s+(?:\./)?(?:%s)[/\\][^\s;|&]*\.py(?:\s|$)" % "|".join(
    re.escape(d).replace("/", r"[/\\]") for d in _TOOL_DIRS)
ESCAPES = (
    r"^git(?:\.exe)?(?:\s|$)",
    _PY + r"\s+-m\s+unittest\b",
    _PY + r"\s+tools[/\\]dev[/\\]safe\.py\b",
    _PY + r"\s+tools[/\\]dev[/\\]plants\.py\b",
    _TOOL,
)

#: Adapted per project: see ADAPT.md.
ADAPT = ("BLOCKED", "ESCAPES")

#: A `git status` that never returns must not hang every tool call behind it. README section 16:
#: a gate blocked for thirteen and a half hours having used 0.14 seconds of CPU.
GIT_TIMEOUT = 20


def alive(pid):
    """Whether `pid` is a live process - asking WINDOWS, not assuming POSIX.

    `os.kill(pid, 0)` IS NOT A PROBE HERE. Python implements `os.kill` on Windows through
    `OpenProcess(PROCESS_TERMINATE)` for every signal, so for a process this account may not
    open for termination it raises `[WinError 87] The parameter is incorrect`. Measured against
    the process table on 2026-09-08 it reported three LIVE processes as dead, one of them the
    sweep runner this hook exists to notice - so the first version of this function would have
    found every blocked runner "dead" and NEVER FIRED. A guard that cannot fire is decoration,
    and this one was written in the same session as that lesson.

    A pid of None or 0 is not a process. An account that may not query a real pid answers
    ALIVE: uncertainty must not silently disable the refusal.

    Standalone on purpose - the `.claude/hooks/` guards import nothing from this repository, so
    that a broken module cannot take a refusal down with it. `tools/audit/liveness.py` therefore
    checks that a probe BRANCHES ON `os.name`, not that there is only one copy of it.
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
        except OSError:
            return False
        return True
    import ctypes
    kernel32 = ctypes.windll.kernel32
    handle = kernel32.OpenProcess(0x1000, False, pid)   # QUERY_LIMITED_INFORMATION
    if not handle:
        # 87 is ERROR_INVALID_PARAMETER - Windows' answer for a pid that does not exist. Any
        # other failure is a permission answer, and permission-denied is not absence.
        return kernel32.GetLastError() != 87
    code = ctypes.c_ulong()
    ok = kernel32.GetExitCodeProcess(handle, ctypes.byref(code))
    kernel32.CloseHandle(handle)
    return bool(ok) and code.value == 259               # 259 is STILL_ACTIVE


def blocked_for():
    """(seconds blocked, [paths]) if a LIVE runner is waiting on uncommitted tooling.

    (0, []) for every other case - no record, unreadable record, dead runner, or a wait that
    has not yet reached the threshold's clock. An absence returns the same shape as a real
    reading rather than None, because every caller here wants the seconds.
    """
    try:
        with io.open(BLOCKED, encoding="utf-8") as handle:
            held = json.load(handle)
    except (IOError, OSError, ValueError):
        return 0, []
    if not alive(held.get("pid")):
        return 0, []
    since = held.get("since")
    if not isinstance(since, (int, float)):
        return 0, []
    # AND ASK THE TREE, NOT THE RECORD. This file is rewritten when the RUNNER polls, so between
    # my commit and its next poll it names paths that are already landed - and if the runner
    # dies in that window, forever. Measured: both paths here stayed named for twenty-two
    # minutes after they were committed, refusing every command in that time. The docstring
    # above checks the dead-PID case for exactly this reason and left the committed-path case
    # on trust.
    paths = still_uncommitted(list(held.get("paths") or []))
    if not paths:
        return 0, []
    return max(0.0, time.time() - since), paths


def still_uncommitted(paths):
    """Which of `paths` actually differ from HEAD right now, asked of git.

    KEPT ON ANY DOUBT. An OSError, a non-zero git, or no git at all returns the paths unchanged:
    refusing is the safe direction, and a question this cannot answer must never be the thing
    that switches a refusal off. Only a clean, successful answer clears it.

    Standalone like everything else here - `.claude/hooks/` imports nothing from this repository,
    so a broken module cannot take a refusal down with it.
    """
    if not paths:
        return []
    try:
        done = subprocess.run(["git", "status", "--porcelain", "--"] + list(paths),
                              cwd=ROOT, capture_output=True, creationflags=NO_WINDOW,
                              timeout=GIT_TIMEOUT)
    except (OSError, ValueError, subprocess.SubprocessError):
        return list(paths)
    if done.returncode != 0:
        return list(paths)
    dirty = set()
    for line in done.stdout.decode("utf-8", "replace").splitlines():
        name = line[3:].strip().strip('"')
        if name:
            dirty.add(name.replace(os.sep, "/"))
    return [p for p in paths if p.replace(os.sep, "/") in dirty]


def is_escape(command):
    """Whether EVERY command on this line is part of getting to a commit - read through any
    wrapper at the one door, `bundle_shell.every_command_is`."""
    return bundle_shell.every_command_is(
        command, lambda one: any(re.search(pattern, one, re.I) for pattern in ESCAPES))


def _rel(path):
    """`path` relative to the project, `/`-separated, case-folded where the file system is."""
    full = path if os.path.isabs(path) else os.path.join(ROOT, path)
    return os.path.normcase(os.path.relpath(os.path.abspath(full), ROOT)).replace(os.sep, "/")


def writes_allowed(payload, paths):
    """Does every place this call writes lie OUTSIDE the repository, or in the tooling the
    runner waits on? A scratch commit message is not work on the tree, and a gate refusing the
    waited-on files is answered by editing them. Any write into the rest of the tree is new
    work, and so is a call whose writes cannot be placed."""
    targets = bundle_shell.paths(payload)
    if not targets:
        return False
    waited = {_rel(p) for p in paths}
    return all(rel == ".." or rel.startswith("../") or rel in waited
               for rel in (_rel(t) for t in targets))


def whose(payload, paths):
    """{path: "wrote" | "named" | ""} - what this session's OWN transcript shows it did to each.

    "wrote" is a Write, Edit or any call whose input writes that path; "named" is a command that
    names it, which may or may not have written it; "" is nothing, and says nothing about who did.
    A transcript that cannot be read leaves every path "" - unknown, never somebody's.
    """
    found = {p: "" for p in paths}
    transcript = payload.get("transcript_path") if isinstance(payload, dict) else None
    if not transcript or not paths:
        return found
    rels = {_rel(p): p for p in paths}
    names = {os.path.basename(p) for p in paths}
    try:
        with io.open(transcript, encoding="utf-8", errors="replace") as handle:
            lines = [line for line in handle if any(name in line for name in names)]
    except (OSError, TypeError, ValueError):
        return found
    for line in lines:
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        message = entry.get("message") if isinstance(entry, dict) else None
        content = message.get("content") if isinstance(message, dict) else None
        for block in content if isinstance(content, list) else []:
            if not isinstance(block, dict) or block.get("type") != "tool_use":
                continue
            call = {"tool_name": block.get("name"), "tool_input": block.get("input")}
            if bundle_shell.writes(call):
                for target in bundle_shell.paths(call):
                    if _rel(target) in rels:
                        found[rels[_rel(target)]] = "wrote"
            said = bundle_shell.text(call).replace(chr(92), "/")
            for path in rels.values():
                if found[path] != "wrote" and path.replace(chr(92), "/") in said:
                    found[path] = "named"
    return found


def refusal(seconds, paths, owners=None):
    """What to say. Names the wait, the files, whose each is when that can be known, and what
    still runs - which is every step toward the commit."""
    owners = owners or {p: "" for p in paths}
    said = {"wrote": "this session wrote it",
            "named": "a command this session ran names it; whether it wrote it cannot be told",
            "": "this session's transcript shows no write to it - whose it is is unknown"}
    lines = [
        "BLOCKED: the sweep runner has been idle %.0f minutes waiting on uncommitted tooling."
        % (seconds / 60.0),
        "It is not measuring anything while this is true.",
        "",
    ]
    lines += ["    %s   - %s" % (p, said[owners.get(p, "")]) for p in paths]
    lines += [
        "",
        "  A worker IMPORTS these from the working tree - not from its worktree at HEAD - so",
        "  an uncommitted one changes which mutations exist and what a verdict means. `plan`",
        "  refuses, correctly, and the runner waits.",
        "",
        "  This exact wait cost 5.5 hours on 2026-09-07. The runner printed the filename 66",
        "  times and nobody looked, then the guard was removed instead of the file committed.",
        "  So the wait refuses new work now rather than printing a sixty-seventh line.",
        "",
    ]
    # THE COMMAND THAT ENDS IT IS ALWAYS NAMED; WHOSE IT IS TO RUN IS WHAT DEPENDS ON THE TRANSCRIPT.
    lines += ["  It ends when these files are committed:",
              "      python -m unittest discover -s tests",
              "      git add <those files> && git commit"]
    if any(owners.get(p) == "wrote" for p in paths):
        lines += ["  and this session wrote them, so that is this session's to do."]
    else:
        lines += ["  None of it is known to be this session's, and committing another's work is",
                  "  theirs to do: find who (ListAgents, then SendMessage - a message is never",
                  "  refused), or the owner can stop the runner with %s."
                  % os.path.relpath(os.path.join(os.path.dirname(BLOCKED), "STOP"), ROOT)]
    lines += [
        "",
        "  Every step toward that commit still runs, behind env, timeout or a shell too: git,",
        "  the suite, a script under %s (a record re-measured, the request ledger's verbs), a"
        % " or ".join(_TOOL_DIRS),
        "  write outside the repository, and a write to the files above. New work waits.",
    ]
    return "\n".join(lines)


def main():
    try:
        payload = json.load(sys.stdin)
    except (ValueError, IOError):
        return 0
    # NOT A TOOL CALL, OR ONLY A READ. The dispatcher hands this the end of a turn too, and
    # refusing that would wedge it; and README section 15 says READING MUST NEVER BE BLOCKED -
    # this refused every `Read` and `Grep` while it waited, because it judged the empty command
    # text of a call that carried none.
    if not isinstance(payload, dict) or bundle_shell.reads_only(payload) \
            or not bundle_shell.is_tool_call(payload):
        return 0
    command = bundle_shell.text(payload)      # every command in the call, at any depth
    writes = bundle_shell.writes(payload)
    if command and not writes and not bundle_shell.unreadable(payload) and is_escape(command):
        return 0
    seconds, paths = blocked_for()
    if seconds < THRESHOLD or not paths:
        return 0
    # EVERY PART OF THE CALL MUST BE A STEP TOWARD THE COMMIT: its commands, and its writes.
    if (not command or is_escape(command)) and writes and not bundle_shell.unreadable(payload) \
            and writes_allowed(payload, paths):
        return 0
    sys.stderr.write(refusal(seconds, paths, whose(payload, paths)) + "\n")
    return 2


if __name__ == "__main__":
    sys.exit(main())
