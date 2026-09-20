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


def commands(payload):
    """Every shell command string in this tool call. Empty list when it runs no shell."""
    found, _ = scan(payload)
    return found


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
            out.append(found.group(1).strip('"' + chr(39)))
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
