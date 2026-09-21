#!/usr/bin/env python3
"""GAME DATA IS READ-ONLY. Refuse the write, not report it afterwards.

WHY THIS EXISTS. The owner's extracted game lives in `RomFS/`, the untouched copy in
`RomFS_original_garcs/`, and their working copy in `Workspace/`. None of it is in version
control, none of it can be regenerated without a 3DS and several hours, and the rule has been
standing since this project began: nothing writes there but the editor, under the owner's own
hand.

It was a rule with nothing behind it until 2026-09-20. On 2026-09-19 a test fixture written
here drove a pack against a `WorkspaceSession` rooted on the LIVE DUMP. It happened to do no
damage - the newest file in the dump was checked afterwards and predated it - but that was
luck, and luck is not a guard. A second fixture put a directory where a file was expected and
passed for the wrong reason. Neither was caught by anything except reading them again later.

WHAT IT REFUSES, by class rather than by call site:

  * a file-editing tool whose target is under a protected root - whatever the tool is called,
    because the target is read from the input's shape and not from a list of tool names
  * a shell command that moves, removes, truncates, redirects into, or otherwise writes a path
    under a protected root, in Bourne shell or PowerShell spelling

WHAT IT DELIBERATELY DOES NOT REFUSE: reading. Every suite in this battery is handed the dump
as a corpus and must read it. A guard that refused `python tools/x.py RomFS/...` would refuse
the honest work as well, and a guard that refuses honest work is a guard that gets switched
off. Programs that write through code the shell cannot see are refused where they run, not
here.

The roots are data: `readonly_paths.txt` beside this file, one path component per line, so
this hook stays byte-identical across projects.

TO WRITE THERE ANYWAY: the owner sets CTRMAP_ALLOW_GAME_WRITE=1 for that session. It is their
data and their decision, and it should take a decision.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hook_env                                   # noqa: E402
import shellin                                    # noqa: E402  (path set above)

HOOK = "guard_game_data.py"
BYPASS = hook_env.name("ALLOW_GAME_WRITE")

#: Used only when readonly_paths.txt is missing. A hardcoded list is a guard that is dead in
#: every project but one, which is how the sweep-pipe guard arrived here knowing nothing about
#: this project's own destructive script.
_DEFAULT_ROOTS = ("RomFS", "RomFS_original_garcs", "Workspace")


def roots():
    beside = os.path.join(os.path.dirname(os.path.abspath(__file__)), "readonly_paths.txt")
    try:
        with open(beside, encoding="utf-8") as handle:
            named = tuple(line.strip() for line in handle
                          if line.strip() and not line.startswith("#"))
        return named or _DEFAULT_ROOTS
    except OSError:
        return _DEFAULT_ROOTS


PROTECTED = roots()


def under_a_protected_root(path):
    """The protected root this path lies under, or None.

    Matched on whole path COMPONENTS. A substring test would refuse
    `src/ctrmap/WorkspaceSession.java` for holding the word Workspace, and a guard that
    refuses the file you are editing is one that gets switched off within the hour.
    """
    parts = [p for p in re.split(chr(91) + chr(47) + chr(92)*2 + chr(93) + chr(43), path.strip().strip(chr(34) + chr(39)))
             if p not in ("", ".")]
    for part in parts:
        for root in PROTECTED:
            if part == root:
                return root
    return None


#: Bourne and PowerShell spellings of "this argument is about to be written".
_WRITERS = (
    "rm", "rmdir", "mv", "cp", "install", "truncate", "tee", "dd", "shred", "unlink",
    "touch", "mkdir", "chmod", "chown", "attrib", "del", "erase", "move", "copy", "xcopy",
    "robocopy", "ren", "rename",
    "remove-item", "move-item", "copy-item", "new-item", "set-content", "add-content",
    "out-file", "clear-content", "set-itemproperty", "rename-item", "export-csv",
)

#: `> path`, `>> path`, `| Out-File path`, `-Destination path`
_REDIRECT = re.compile(r">>?\s*([^\s|;&]+)")
_DESTINATION = re.compile(r"-Destination\s+([^\s|;&]+)", re.I)
#: `sed -i`, `python -c "...open(p,'w')..."` and friends write without a shell redirect
_IN_PLACE = re.compile(r"\bsed\b[^|;&]*\s-i\b")


def _targets(command):
    """(path, why) for every argument this command looks about to write."""
    out = []
    for found in _REDIRECT.finditer(command):
        out.append((found.group(1), "a redirect writes it"))
    for found in _DESTINATION.finditer(command):
        out.append((found.group(1), "it is the -Destination"))
    for piece in re.split(r"[;|&]+|&&", command):
        words = piece.strip().split()
        if not words:
            continue
        head = os.path.basename(words[0]).lower()
        if head.endswith(".exe"):
            head = head[:-4]
        if head in _WRITERS:
            for word in words[1:]:
                if word.startswith("-") or word.startswith("/"):
                    continue
                out.append((word, "`%s` writes its arguments" % head))
        if _IN_PLACE.search(piece):
            for word in words[1:]:
                if not word.startswith("-"):
                    out.append((word, "`sed -i` rewrites the file in place"))
    return out


def verdict(command):
    """(deny, reason) for one shell command. Separated so it can be tested directly."""
    for path, why in _targets(command):
        root = under_a_protected_root(path)
        if root:
            return True, refusal(path, root, why)
    return False, None


def refusal(path, root, why):
    return (
        "BLOCKED BY PROJECT POLICY (.claude/hooks/%s).\n"
        "GAME DATA IS READ-ONLY, and this writes %s, which is under %s/ (%s).\n\n"
        "That directory holds the owner's extracted game. It is not in version control, it "
        "cannot be regenerated without a 3DS and several hours, and the standing rule is that "
        "nothing writes there but the editor, under the owner's own hand. On 2026-09-19 a test "
        "fixture written here drove a pack against a session rooted on the live dump; it "
        "happened to do no damage, which is luck, not a guard.\n\n"
        "Work on a copy under the scratch directory instead. If this really is the owner's own "
        "edit, they set %s=1 for the session - it is their data, and it should take a "
        "decision.\n" % (HOOK, path, root, why, BYPASS))


def decide(payload):
    """(deny, reason) for any tool call, by the shape of what it writes."""
    if os.environ.get(BYPASS) == "1":
        return False, None
    blind = shellin.unreadable(payload)
    if blind:
        return True, shellin.blind_refusal(HOOK, "; ".join(blind), BYPASS)
    #: the file-editing tools, read by shape - `file_path`, `notebook_path`, `path`
    for path in shellin.paths_written(payload):
        root = under_a_protected_root(path)
        if root:
            return True, refusal(path, root, "a file-editing tool writes it directly")
    for command in shellin.commands(payload):
        deny, reason = verdict(command)
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
