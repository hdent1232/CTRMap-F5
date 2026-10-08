#!/usr/bin/env python3
"""The shell text a tool call would run, found by SHAPE rather than by tool name.

WHY THIS EXISTS, measured rather than argued.

Seven guards live beside this file. Five of them began with

    if str(payload.get("tool_name") or "") != "Bash":
        sys.exit(0)

and all seven were wired in `.claude/settings.json` under `"matcher": "Bash"`. This session
also offers a `PowerShell` tool that runs shell commands. `PowerShell` is not `Bash`, so the
matcher never selected the hooks, and the hooks would have exempted themselves even if it had:

  * the heredoc refusal - the one that put NUL bytes into a committed file - was off
  * the sweep-pipe refusal - the one that stopped a mutation sweep being killed mid-write - was off
  * the idle-poll refusal, the mutation-read refusal, the blocked-runner refusal - all off

Not one of them had to be bypassed. They had to be spelled differently. That is the project's
own rule about guards at call sites, paid for again by the guards themselves: A GUARD AT CALL
SITES IS ONE CALL SITE FROM BROKEN, filed four separate times before this.

So the question a guard asks is no longer "is this tool called Bash". It is "does this tool
input carry a command", which is a property of the CLASS of shell-running tools and cannot be
escaped by adding a tool with a different name. A tool nobody has heard of, whose input has a
`command` string, is a shell here.

The complementary rule applies to the answer: a payload this cannot read is not a payload that
runs nothing. `commands()` returns what it found; `unreadable()` says whether any part of the
input could not be walked, and a caller that gets True must refuse rather than allow.
"""
import importlib.util
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root        # noqa: E402  - the project root, anchored: never counted (F216)

#: How deep to walk a tool input looking for command strings. `browser_batch` nests one tool
#: input inside another; nothing here nests deeper than that, and an unbounded walk on a
#: self-referencing structure would hang a PreToolUse hook, which fails the turn.
MAX_DEPTH = 6

#: Keys whose string value IS a shell command. `command` is Bash and PowerShell; `script` is
#: how a couple of runners spell it. Both are shapes, not tool names.
COMMAND_KEYS = ("command", "script")


def _walk(node, depth, out, trouble):
    if depth > MAX_DEPTH:
        trouble.append("nested deeper than %d" % MAX_DEPTH)
        return
    if isinstance(node, dict):
        for key, value in node.items():
            if key in COMMAND_KEYS and isinstance(value, str):
                out.append(value)
            elif key in COMMAND_KEYS and value is not None and not isinstance(
                    value, (list, tuple, dict)):
                #: a command that is not text is a command this cannot read, and an
                #: unreadable command must not be reported as no command at all.
                trouble.append("%s was %s, not text" % (key, type(value).__name__))
            else:
                _walk(value, depth + 1, out, trouble)
    elif isinstance(node, (list, tuple)):
        for value in node:
            _walk(value, depth + 1, out, trouble)


def _bundle_shell():
    """The bundle's command reader, `bundle_shell`, or raises ImportError saying why not.

    THE ONE READER OF A SHELL TEXT (F176). Every guard here split shell text for itself - on
    `[;|&]`, or not at all - so a newline before the refused command passed five of them: a
    `cd .` line in front of a push, a game-data write, a source edit during a battery. The
    bundle's reader, one folder over, already reads newlines, quotes, heredoc bodies and the
    command a wrapper hands on; this stack uses it instead of growing a sixth splitter.
    Imported with its own folder first on the path only for the import, so nothing of this
    stack is shadowed by a bundle module of the same name.
    """
    import importlib
    root = _tree_root.require(__file__)
    folder = os.path.join(root, ".claude", "bundle-hooks")
    locator = os.path.join(root, "tools", "bundle_hooks.py")
    if os.path.isfile(locator):
        spec = importlib.util.spec_from_file_location("shellin_bundle_hooks", locator)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        folder = module.path(root)
    if not os.path.isfile(os.path.join(folder, "bundle_shell.py")):
        raise ImportError("the bundle's command reader is not at %s" % folder)
    sys.path.insert(0, folder)
    try:
        return importlib.import_module("bundle_shell")
    finally:
        sys.path.remove(folder)


def reader():
    """The one reader of shell text this stack uses - see `_bundle_shell`."""
    return _bundle_shell()


#: A command that only changes the directory the NEXT ones run in.
_MOVERS = ("cd", "chdir", "pushd", "set-location", "push-location", "sl")
#: One that changes it to somewhere the text does not say.
_LOST = ("popd", "pop-location")
#: Their options that take a value, so the value is not read as the place.
_MOVER_VALUED = ("-path", "-literalpath", "-stackname")


def absolute(path):
    return (os.path.isabs(path) or path.startswith(("/", chr(92)))
            or (len(path) > 2 and path[1] == ":" and path[2] in "/" + chr(92)))


def _arrive(where, target):
    """Where a move to `target` from `where` lands, or None when that cannot be told."""
    if not target or target == "-" or "$" in target or "%" in target:
        return None
    if target.startswith("~"):
        return os.path.expanduser(target)
    if absolute(target):
        return target
    if where is None:
        return None
    return os.path.join(where, target) if where else target


def _moved(read, command, where):
    """(True, where after it) when the command only changes directory, else (False, where)."""
    held = read.pairs(command)
    if not held:
        return False, where
    name = read.program_of(held[0][0])
    if name in _LOST:
        return True, None
    if name not in _MOVERS:
        return False, where
    if len(read.stages(held)) != 1:
        return False, where
    at = 1
    while at < len(held):
        word, typed = held[at]
        if word.lower() in _MOVER_VALUED and at + 1 < len(held):
            word, typed = held[at + 1]
            return True, _arrive(where, typed if typed is not None else word)
        if word.startswith("-") and word != "-":
            at += 1
            continue
        return True, _arrive(where, typed if typed is not None else word)
    return True, (None if name in ("set-location", "sl") else os.path.expanduser("~"))


def _chdir_of(stage):
    """The folder `env -C <dir>` / `env --chdir=<dir>` names in a stage, or None."""
    for at, (word, typed) in enumerate(stage):
        if word in ("-C", "--chdir") and at + 1 < len(stage):
            return stage[at + 1][1] if stage[at + 1][1] is not None else stage[at + 1][0]
        if word.startswith("--chdir="):
            return word.split("=", 1)[1]
    return None


def each_located(text_, where="", depth=0):
    """[(command, where it runs)] for every command in a shell text: split on newlines, `;`, `&&`
    and `||` outside quotes, and every command a runner (env, sudo, nohup, xargs ...) or a shell
    (bash -c, sh -c, powershell -Command, cmd /c, eval) in one of them hands on - to the
    reader's own depth.

    F175 and F173 were this: a push or a game-data write reached through `bash -c`, `sudo` or
    `env`, or on any line but the first, was never seen by the guard that refuses it.

    WHERE IT RUNS is part of the answer, because splitting the text took it away. A push used
    to be judged with the `cd` before it on the same line in view; once each command is judged
    alone, `cd ../other && git push` would be judged in the call's own folder and read that
    repository's approval. `where` is "" for the folder the call starts in, a path relative to
    it or absolute after a `cd`, and None when the text moves somewhere it does not say (`cd -`,
    `popd`, a variable) - unknown, never "here". A command a shell is handed starts where that
    shell started, and its own moves end with it.
    """
    read = _bundle_shell()
    out = []
    for command in read.split_commands(text_ or ""):
        moves, where_next = _moved(read, command, where)
        out.append((command, where))
        if moves:
            where = where_next
            continue
        if depth >= read.MAX_DEPTH:
            continue
        held = read.pairs(command)
        for stage in read.stages(held) if held else []:
            rest, chdir = read.peeled(stage)
            here = _arrive(where, _chdir_of(stage)) if chdir else where
            if rest and len(rest) < len(stage):
                out.extend(each_located(read.joined_text(rest), here, depth + 1))
            inner, _why = read.handed(rest)
            if inner:
                out.extend(each_located(inner, here, depth + 1))
    return out


def each_command(text_, depth=0):
    """Every command in a shell text, one by one - `each_located` without the folders."""
    return [command for command, _where in each_located(text_, "", depth)]


def scripts(payload):
    """Every shell TEXT in the call, whole: the tool's own command, and every text a shell or a
    wrapper in it is handed (bash -c, powershell -Command, eval ...), each unsplit.

    For the guard whose question spans commands - a pipe into a filter, then `$?` - which split
    commands cannot answer: `x | head; echo $?` is two commands and one mistake. Every other
    guard asks `commands`, one command at a time.
    """
    found, _ = scan(payload)
    reader = _bundle_shell()
    out = []

    def walk(text_, depth):
        out.append(text_)
        if depth >= reader.MAX_DEPTH:
            return
        for command in reader.split_commands(text_):
            held = reader.pairs(command)
            for stage in reader.stages(held) if held else []:
                rest, _moves = reader.peeled(stage)
                inner, _why = reader.handed(rest)
                if inner:
                    walk(inner, depth + 1)
    for raw in found:
        walk(raw, 0)
    return out


def commands(payload):
    """Every shell command in this tool call, ONE BY ONE - each line and each command a wrapper
    hands on (see `each_command`). Empty list when it runs no shell."""
    return [command for command, _where in located(payload)]


def located(payload):
    """[(command, where it runs)] for every shell command in this tool call - see
    `each_located` for what `where` holds. A guard whose answer depends on the folder asks this."""
    found, _ = scan(payload)
    try:
        return [one for raw in found for one in each_located(raw)]
    except Exception:  # noqa: BLE001 - `unreadable` says why, and a caller refuses on it
        return [(raw, "") for raw in found]


def unreadable(payload):
    """Why part of this tool input could not be read, or an empty list when all of it was.

    A caller that is deciding whether to REFUSE must treat a non-empty answer as "there may be
    a command in here I cannot see", never as "there is no command".
    """
    _, trouble = scan(payload)
    return trouble


def scan(payload):
    """(commands, trouble) - the one walk both questions are answered from."""
    out = []
    trouble = []
    try:
        tool_input = (payload or {}).get("tool_input")
    except AttributeError:
        return [], ["the payload was %s, not an object" % type(payload).__name__]
    if tool_input is None:
        return [], []
    if not isinstance(tool_input, (dict, list, tuple)):
        return [], ["tool_input was %s, not an object" % type(tool_input).__name__]
    _walk(tool_input, 0, out, trouble)
    if out:
        #A COMMAND THE READER CANNOT SPLIT IS NOT ONE IT READ: say so, so every guard refuses
        #rather than judging the whole text as one command and missing the second line again
        try:
            for raw in out:
                each_command(raw)
        except Exception as exc:  # noqa: BLE001 - UNKNOWN, never "no command here"
            trouble.append("the command could not be split into its commands (%s: %s)"
                           % (type(exc).__name__, str(exc)[:120]))
    return out, trouble


def text(payload):
    """Every command in the call, joined - what a guard that scans for a pattern wants.

    Joined with a newline and not a space, so a pattern anchored to the start of a line still
    matches the second command of a batch.
    """
    return chr(10).join(commands(payload))


#: Shell words whose arguments are about to be written. `sed` only with `-i`: `sed -n '1,40p'`
#: READS, and treating every sed as a write refused a plain read of a source file within
#: minutes of the first guard that did it.
WRITE_WORDS = ("rm", "rmdir", "mv", "cp", "install", "truncate", "tee", "dd", "shred",
               "unlink", "touch", "mkdir", "del", "erase", "move", "copy", "xcopy",
               "robocopy", "ren", "rename", "remove-item", "move-item", "copy-item",
               "new-item", "set-content", "add-content", "out-file", "clear-content",
               "rename-item", "export-csv")


def writes_something(payload):
    """Every path this tool call would create or change. Empty means it only reads.

    ONE ANSWER FOR ALL THE GUARDS THAT ASK IT. Two of them worked it out separately and then
    interlocked: one refused every call while an installed hook differed from its
    version-controlled copy - including the read that would have shown what differed - and the
    other refused the copy that would have fixed it, because a replant run held the lock. Each
    was correct on its own. A guard may refuse the work; it may never refuse the way out.
    """
    import re as _re
    out = list(paths_written(payload))
    for command in commands(payload):
        for found in _re.finditer(r">>?\s*([^\s|;&]+)", command):
            target = found.group(1).strip('"' + chr(39))
            #: DISCARDING output is not writing a file. `2>/dev/null` on a read made the
            #: wiring check call it a write and refuse it, which meant the command that
            #: would have shown what differed was itself refused - the same trap as a guard
            #: that blocks its own repair, one level along.
            if target.rsplit("/", 1)[-1].lower() in ("null", "nul", "$null"):
                continue
            out.append(target)
        for piece in _re.split(r"[;|&]+", command):
            words = piece.strip().split()
            if not words:
                continue
            head = words[0].rsplit("/", 1)[-1].rsplit(chr(92), 1)[-1].lower()
            if head.endswith(".exe"):
                head = head[:-4]
            if head == "sed":
                if "-i" in words:
                    out.extend(w for w in words[1:] if not w.startswith("-"))
                continue
            if head in WRITE_WORDS:
                out.extend(w for w in words[1:] if not w.startswith("-"))
    return out


def blind_refusal(guard, trouble, bypass):
    """What a guard says when it could not read part of the input it was asked about.

    The alternative is to read an unreadable command as no command, which is the shape that
    let twelve live processes be reported gone here: every probe has an answer for "it is not
    there" and an answer for "I could not look", and code that collapses the second into the
    first reports a clean negative.
    """
    return ("BLOCKED BY PROJECT POLICY (.claude/hooks/%s).\n"
            "This tool call carries something that may be a shell command and could not be "
            "read (%s), so this guard cannot tell whether what it refuses is in there.\n\n"
            "AN UNMEASURABLE QUANTITY IS NOT A SMALL ONE. A guard that reads an unreadable "
            "input as an empty one is not a guard - it is a guard that is off, and looks "
            "exactly like one that passed.\n\n"
            "Re-issue the call with the command as plain text, or set %s=1 for this session "
            "to accept it unchecked.\n" % (guard, trouble, bypass))


#: How a tool input spells the file it is pointed at.
PATH_KEYS = ("file_path", "notebook_path", "path")

#: The ONLY other things a call that merely READS a file carries. Everything else - content,
#: new_string, edits, a key nobody here has seen - means something is going in.
READ_ONLY_KEYS = PATH_KEYS + ("offset", "limit", "pages", "tabId", "max_chars")


def paths_written(payload):
    """Files this tool call would CREATE OR MODIFY, by shape, ignoring any shell text.

    THE PATH ALONE IS NOT ENOUGH, and reading it as enough refused a Read within a minute of
    being written: the reading tool and the writing tool both spell their target `file_path`.
    What separates them is the rest of the input - a read carries an offset or a limit or
    nothing, a write carries what it is writing.

    So the test is on the OTHER keys, and it fails in the safe direction: an input holding any
    key that is not one a read carries counts as a write, including a key this has never seen.
    A tool nobody has heard of that writes files is refused; a tool that only reads is not.
    """
    out = []
    tool_input = (payload or {}).get("tool_input")
    if not isinstance(tool_input, dict):
        return out
    carries = [key for key in tool_input if key not in READ_ONLY_KEYS]
    if not carries:
        return out
    for key in PATH_KEYS:
        value = tool_input.get(key)
        if isinstance(value, str) and value:
            out.append(value)
    return out
