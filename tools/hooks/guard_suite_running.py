#!/usr/bin/env python3
"""NEVER EDIT SOURCE WHILE THE SUITE IS RUNNING. The result is green and means nothing.

WHY THIS EXISTS, and it has already been paid for twice here: two full battery runs were
invalidated by edits landing while they ran. A suite compiled from half the old tree and half
the new one is neither, and it passes. There is nothing in the output that says so.

`build.ps1` has refused to REBUILD during a battery since that second time - it reads the same
lock this does. Nothing refused the EDIT, which is the half that makes the run meaningless
rather than merely noisy, so the rule sat in CLAUDE.md with nothing behind it.

WHAT IT REFUSES: a write to anything the build compiles or a suite reads - `.java`, `.py`,
`.ps1`, `.json`, `.properties`, `.txt`, `.form` inside the repository, outside `build/`,
`dist/` and `.git/` - while a run holds a lock. Reads are untouched, and so is everything
outside the repository.

THE LOCKS IT READS, both of which existed already:

  * `build/.battery-running` - written by test.ps1 for the length of a battery
  * `.mutation-in-flight` - written by mutate2.py and replant.py while a fault is on disk

HOW IT DECIDES A LOCK IS STALE. By the process id when the lock carries one, and otherwise by
age, with the same ninety minutes build.ps1 has always used. A lock it CANNOT check is treated
as LIVE: every probe has an answer for "it is not there" and an answer for "I could not look",
and collapsing the second into the first is what reported twelve live workers gone here and
cost 134 verdicts. The probe itself lives in `liveness.py`, which is the project's only
answer to that question - three copies of it were three chances to write the wrong one.

TO EDIT ANYWAY: the owner sets CTRMAP_ALLOW_EDIT_DURING_RUN=1, or deletes the lock.
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shellin                                    # noqa: E402  (path set above)
import liveness                                   # noqa: E402  (same)

HOOK = "guard_suite_running.py"
BYPASS = "CTRMAP_ALLOW_EDIT_DURING_RUN"

#: (repo-relative lock, what holds it). Both already existed; neither stopped an edit.
LOCKS = (
    (os.path.join("build", ".battery-running"), "the battery"),
    (".mutation-in-flight", "a mutation or replant run"),
)

#: What the build compiles or a suite reads.
SOURCE = (".java", ".py", ".ps1", ".json", ".properties", ".txt", ".form")
#: What no suite reads and no build compiles.
NOT_SOURCE = ("build", "dist", ".git", "__pycache__", "node_modules")

#: build.ps1 has aged its lock out at ninety minutes since the day it was written. The two
#: have to agree, or one of them refuses work the other has already released.
ABANDONED = 90 * 60


def repos(cwd):
    """The directories that might hold a lock: the working directory and one level down."""
    out = [cwd]
    try:
        out.extend(os.path.join(cwd, name) for name in sorted(os.listdir(cwd))
                   if os.path.isdir(os.path.join(cwd, name)))
    except OSError:
        pass
    return out


def held(cwd):
    """(repo, lock path, what, why it is live) for every live lock found. Empty means none."""
    found = []
    for repo in repos(cwd):
        for relative, what in LOCKS:
            path = os.path.join(repo, relative)
            if not os.path.isfile(path):
                continue
            live, why = _live(path)
            if live:
                found.append((repo, path, what, why))
    return found


def _live(path):
    """(is a run in flight, how this knows). Anything it cannot determine counts as live."""
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            body = handle.read()
        age = time.time() - os.path.getmtime(path)
    except OSError as cannotRead:
        return True, ("the lock at %s could not be read (%s), so whether a run is in flight is "
                      "unknown - and an unknown is not a finished run" % (path, cannotRead))
    found = re.search(r"\bpid\s*=\s*(\d+)", body)
    if found:
        return alive(int(found.group(1)))
    if age <= ABANDONED:
        return True, ("it was written %d minute(s) ago and carries no process id, so it is "
                      "taken to be running - the same ninety minutes build.ps1 uses"
                      % (age / 60))
    return False, "it is %d minute(s) old, past the ninety build.ps1 ages a lock out at" % (
        age / 60)


def alive(pid):
    """(is it running, how this knows) - the project's one answer; see liveness.py.

    THIS WAS A THIRD SPELLING, using `tasklist`. It was not wrong, which is exactly why it had
    to go: a question answered three ways is answered differently the fourth time, and the
    difference between "gone" and "I could not look" is what cost 134 verdicts here.
    """
    return liveness.alive(pid)


def is_source(path, repo):
    """True when a write here changes what a running suite is measuring."""
    full = os.path.normcase(os.path.abspath(path))
    if not full.startswith(os.path.normcase(os.path.abspath(repo)) + os.sep):
        return False
    if os.path.splitext(full)[1].lower() not in SOURCE:
        return False
    parts = re.split("[" + chr(47) + chr(92) * 2 + "]+", full)
    return not any(part in NOT_SOURCE for part in parts)


def written(payload):
    """Every path this call would write - the file tools by shape, plus shell writes."""
    out = list(shellin.paths_written(payload))
    for command in shellin.commands(payload):
        for found in re.finditer(r">>?\s*([^\s|;&]+)", command):
            out.append(found.group(1).strip('"' + chr(39)))
        for piece in re.split(r"[;|&]+", command):
            words = piece.strip().split()
            if not words:
                continue
            head = os.path.basename(words[0]).lower()
            if head in ("rm", "mv", "cp", "sed", "remove-item", "move-item", "copy-item",
                        "set-content", "add-content", "out-file"):
                out.extend(w for w in words[1:] if not w.startswith("-"))
    return out


def decide(payload):
    if os.environ.get(BYPASS) == "1":
        return False, None
    blind = shellin.unreadable(payload)
    if blind:
        return True, shellin.blind_refusal(HOOK, "; ".join(blind), BYPASS)
    targets = written(payload)
    if not targets:
        return False, None
    cwd = str((payload or {}).get("cwd") or os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd())
    for repo, path, what, why in held(cwd):
        touched = [t for t in targets if is_source(os.path.join(repo, t)
                                                   if not os.path.isabs(t) else t, repo)]
        if touched:
            return True, _no(touched[0], what, why, path)
    return False, None


def _no(path, what, why, lock):
    return (
        "BLOCKED BY PROJECT POLICY (.claude/hooks/%s).\n"
        "NEVER EDIT SOURCE WHILE THE SUITE IS RUNNING: this writes %s, and %s is in flight "
        "(%s).\n\n"
        "An edit landing mid-run leaves a result that is neither the old tree nor the new one, "
        "and it looks green. Two full battery runs here were invalidated exactly this way. A "
        "mutation run is worse - it restores from the bytes it read, so an edit in between is "
        "either overwritten or folded into the next baseline.\n\n"
        "Wait for it, or stop it and delete %s. To edit anyway, the owner sets %s=1.\n"
        % (HOOK, path, what, why, lock, BYPASS))


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
