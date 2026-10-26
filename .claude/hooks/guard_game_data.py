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

NOR A COPY IN THE TEMP FOLDER. The refusal below tells whoever was refused to work on a copy
under the scratch directory, and until 2026-10-24 a copy named the way the game is named was
refused there too: measuring 1.0.4 on copies of the owner's workspace and game needed the copies
renamed (ws, game) before they could be written at all. A path is judged by the folder it
really is - `..` and links resolved, a relative one from the folder the call runs in - and one
inside the system's temp folder is a copy, never the owner's data. A path that cannot be
resolved (a variable, a folder the call does not name) is judged by its name, as before.

The roots are data: `readonly_paths.txt` beside this file, one path component per line, so
this hook stays byte-identical across projects.

TO WRITE THERE ANYWAY: the owner sets CTRMAP_ALLOW_GAME_WRITE=1 for that session. It is their
data and their decision, and it should take a decision.
"""
import os
import re
import sys
import tempfile

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


def _drive_spelled(path):
    """`/c/x` - Git Bash's name for `C:/x` - as `C:/x` on Windows, when that drive is there and no
    folder `/c` stands at the root of the current one; anything else as given."""
    if (os.name == "nt" and len(path) >= 2 and path[0] == "/" and path[1].isalpha()
            and (len(path) == 2 or path[2] in "/" + chr(92)) and not os.path.isdir(path[:2])
            and os.path.isdir(path[1] + ":" + os.sep)):
        return path[1].upper() + ":/" + path[3:]
    return path


def a_scratch_copy(path, base):
    """True when `path`, judged from the folder `base` (the call's own), lies inside the system's
    temp folder - a copy, which no owner's game is. False whenever that cannot be told: a variable,
    a home folder, a wildcard, a relative path with no folder to judge it from."""
    path = path.strip().strip(chr(34) + chr(39))
    if not path or any(c in path for c in "$%*?") or path.startswith("~"):
        return False
    path = _drive_spelled(path)
    if not shellin.absolute(path):
        if not base or not shellin.absolute(base):
            return False
        path = os.path.join(_drive_spelled(base), path)
    #REALPATH, NOT ABSPATH: `..` climbs out and a junction inside the temp folder can stand on the
    #real dump; both resolve to the folder actually written
    full = os.path.normcase(os.path.realpath(path))
    scratch = os.path.normcase(os.path.realpath(tempfile.gettempdir()))
    return full.startswith(scratch.rstrip(os.sep) + os.sep)


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

#: WRITERS BY SHAPE, beside the names (F173). `find RomFS -name '*.bak' -delete` and
#: `rsync -a --delete empty/ RomFS/` deleted from the dump while the guard looked only for a
#: writer's NAME as the first word - `find` reads unless it is given an action, and `rsync`,
#: `ln` and an extracting `tar`/`unzip` write ONE of their arguments, not all of them.
_FIND_ACTIONS = ("-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0",
                 "-fprintf", "-fls")


def _by_shape(head, words):
    """(path, why) for the argument a shape-recognised writer is about to change."""
    out = []
    if head == "find":
        acting = [w for w in words[1:] if w in _FIND_ACTIONS]
        if acting:
            for word in words[1:]:
                if word.startswith(("-", "(", "!")):
                    break
                out.append((word, "`find ... %s` changes what it finds under it" % acting[0]))
    elif head in ("rsync", "ln"):
        positional = [w for w in words[1:] if not w.startswith("-")]
        if positional:
            out.append((positional[-1], "`%s` writes its last argument" % head))
    elif head == "tar" and any(w.startswith("-") and "x" in w.lstrip("-") or w == "--extract"
                               for w in words[1:]):
        for at, word in enumerate(words[1:-1], 1):
            if word in ("-C", "--directory"):
                out.append((words[at + 1], "`tar` extracts into it"))
        out.extend((w.split("=", 1)[1], "`tar` extracts into it")
                   for w in words[1:] if w.startswith("--directory="))
    elif head == "unzip":
        for at, word in enumerate(words[1:-1], 1):
            if word == "-d":
                out.append((words[at + 1], "`unzip -d` extracts into it"))
    return out


#: Writers that change ONE argument - where they put the copy - and only READ the rest.
#: `cp RomFS/x build/` reads the dump, and the docstring above is the rule: reading is not refused.
_COPIERS = ("cp", "install", "copy", "copy-item", "xcopy")
#: Their options naming the place written, and those naming what is only read.
_TARGET_VALUED = ("-t", "--target-directory", "-destination")
_SOURCE_VALUED = ("-path", "-literalpath", "-filter", "-include", "-exclude", "-s", "--suffix",
                  "-credential")
#: cmd's switches - `xcopy a b /E /Y` - are not where the copy goes.
_SWITCH = re.compile(r"^/[A-Za-z?]+(?::\S*)?$")


def _copied_to(head, args):
    """The arguments a copier writes."""
    out, positional, directories, at = [], [], False, 1
    while at < len(args):
        word, low = args[at], args[at].lower()
        if low in _TARGET_VALUED and at + 1 < len(args):
            out.append(args[at + 1])
            at += 2
            continue
        if low.startswith(("--target-directory=", "-destination:")):
            out.append(word.split("=" if "=" in word else ":", 1)[1])
        elif low in _SOURCE_VALUED and at + 1 < len(args):
            at += 2
            continue
        elif head == "install" and low in ("-d", "--directory"):
            directories = True
        elif not (word.startswith("-") and len(word) > 1) and not (
                head in ("copy", "xcopy") and _SWITCH.match(word)):
            positional.append(word)
        at += 1
    if out:
        return out
    if directories:
        return positional
    if len(positional) >= 2:
        return positional[-1:]
    #`Copy-Item x` copies into the folder it runs in; `cp x` alone is an error, judged anyway
    return ["."] if head in ("copy-item", "copy", "xcopy") else positional


def _sed_files(args):
    """The files `sed -i` rewrites - every positional but the SCRIPT, which is the first one
    unless `-e`/`-f` gave it. Reading the script as a file refused a scratch-file edit whose
    replacement text held the word RomFS (audit F173, observed live)."""
    files, scripted, at = [], False, 1
    while at < len(args):
        word = args[at]
        if word in ("-e", "--expression", "-f", "--file") and at + 1 < len(args):
            scripted = True
            at += 2
            continue
        if word.startswith(("--expression=", "--file=")):
            scripted = True
        elif not (word.startswith("-") and len(word) > 1):
            files.append(word)
        at += 1
    return files if scripted else files[1:]


def _in_place(args):
    return any(w == "-i" or w.startswith("--in-place") or re.match(r"^-[A-Za-z]*i", w)
               and not w.startswith("--") for w in args[1:])


def _targets(command):
    """(path, why) for every argument this command is about to write.

    READ AS THE SHELL READS IT (F173): words through the shared reader, quotes respected, so a
    `>` inside a quoted argument is not a redirect - one was, and `"Options > Workspace
    settings"` in a commit-message argument was refused as a write to Workspace/. A command the
    reader cannot split is read the old, cruder way, which refuses more and never less.
    """
    read = shellin.reader()
    held = read.pairs(command)
    if held is None:
        return _crude_targets(command)
    out = []
    for stage in read.stages(held):
        rest, _moves = read.peeled(stage)
        args, at = [], 0
        while at < len(rest):
            word, typed = rest[at]
            spelled = typed if typed is not None else word
            if word and set(word) <= set("<>&|0123456789") and ">" in word:
                if at + 1 < len(rest):
                    out.append((rest[at + 1][1] or rest[at + 1][0], "a redirect writes it"))
                at += 2
                continue
            if word and set(word) <= set("<&0123456789") and "<" in word:
                at += 2                                     # an input redirect is a read
                continue
            args.append(spelled)
            at += 1
        if not args:
            continue
        for at, word in enumerate(args[:-1]):
            if word.lower() == "-destination":
                out.append((args[at + 1], "it is the -Destination"))
        head = read.program_of(args[0])
        if head in _COPIERS:
            out.extend((w, "`%s` writes where it copies to" % head) for w in _copied_to(head, args))
        elif head == "dd":
            out.extend((w[3:], "`dd of=` writes it") for w in args[1:] if w.startswith("of="))
        elif head == "sed":
            if _in_place(args):
                out.extend((w, "`sed -i` rewrites the file in place") for w in _sed_files(args))
        elif head in _WRITERS:
            for word in args[1:]:
                if word.startswith("-") and len(word) > 1:
                    if "=" in word:
                        out.append((word.split("=", 1)[1], "`%s` writes its arguments" % head))
                    continue
                out.append((word, "`%s` writes its arguments" % head))
        out.extend(_by_shape(head, args))
    return out


def _crude_targets(command):
    """(path, why) for every argument this command looks about to write - by whitespace, for a
    command the reader could not split."""
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
        if head in _WRITERS or head in ("dd", "sed"):
            for word in words[1:]:
                out.append((word.split("=", 1)[-1], "`%s` writes its arguments" % head))
        out.extend(_by_shape(head, words))
        if _IN_PLACE.search(piece):
            for word in words[1:]:
                if not word.startswith("-"):
                    out.append((word, "`sed -i` rewrites the file in place"))
    return out


def verdict(command, where="", base=None):
    """(deny, reason) for one shell command, run in `where` - the folder it moved to, relative to
    the call's own ("" when it did not move; see shellin.each_located). `cd RomFS && rm a/0/1/3`
    deletes from the dump although no argument names it, so a relative target is judged where it
    lands. `base` is the call's own folder, which a copy in the temp folder is judged from; None
    judges every path by its name. Separated so it can be tested directly."""
    for path, why in _targets(command):
        landed = path if not where or shellin.absolute(path) else os.path.join(where, path)
        root = under_a_protected_root(landed)
        if root and not a_scratch_copy(landed, base):
            return True, refusal(landed, root, why)
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
    base = payload.get("cwd") if isinstance(payload.get("cwd"), str) else None
    #: the file-editing tools, read by shape - `file_path`, `notebook_path`, `path`
    for path in shellin.paths_written(payload):
        root = under_a_protected_root(path)
        if root and not a_scratch_copy(path, base):
            return True, refusal(path, root, "a file-editing tool writes it directly")
    for command, where in shellin.located(payload):
        if where is None:
            #A FOLDER THE TEXT DOES NOT NAME IS UNKNOWN, NOT HERE: after `cd "$X"` or `popd` a
            #relative write lands somewhere this cannot tell, and that may be the dump
            blind_writes = [p for p, _why in _targets(command) if not shellin.absolute(p)]
            if blind_writes:
                return True, refusal(blind_writes[0], "an UNKNOWN folder",
                                     "it writes after a move to a folder the command does not "
                                     "name, so where it lands cannot be told")
        deny, reason = verdict(command, where or "", base)
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
