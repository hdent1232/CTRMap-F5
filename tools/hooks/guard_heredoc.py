#!/usr/bin/env python3
"""Refuse to carry file content inside a shell command string.

WHY THIS EXISTS, and why the four guards before it did not stop it.

This project has had heredoc content mangled six times. `chr(92) + "n"` written into a script
arrived as a real newline. A `chr(92) + "b"` arrived as a literal backspace, 0x08, in a file that
still compiled. A test file written this way died with

    /usr/bin/bash: -c: line 96: unexpected EOF while looking for matching "'"

meaning the heredoc had already terminated and bash was parsing the body as script.

MEASURED, not assumed: the same 156-line body, written to a script FILE and run with `bash
replay.sh`, round-tripped BYTE-IDENTICAL. Bash's heredoc handling is not the problem. The loss
happens in the transport that carries the command string to the shell, and no amount of care
inside the heredoc reaches it.

Every guard built for this so far sat downstream of that channel: `tools/dev/safe.py` compiles
the `.py` it writes and scans it for stray control characters, and `rewrite` refuses a file it
cannot parse. Those catch a mangled file AFTER it lands, and only when the write goes through
`rewrite` at all. The channel itself was never refused, so the mistake kept happening while the
guards kept reporting OK.

THE RULE: file content never travels in a command string.

    writing a file        -> the Write tool. It is exact and it is not a shell.
    running a script      -> Write it, then `python tools/dev/safe.py run-script <path>`,
                             which compiles it and refuses the mangling this hook is about.
    a genuine one-liner   -> still fine; a short heredoc feeding an interpreter is allowed.

TO RUN ONE ANYWAY: the owner sets DTENGINE_ALLOW_HEREDOC=1 for that session.
"""
import json
import os
import re
import sys

#: `<<EOF`, `<<'EOF'`, `<<"EOF"`, `<<-EOF`. Not `<<<` (a here-STRING, which carries no body).
_HEREDOC = re.compile(r"(?<!<)<<-?\s*(?![<])(" + chr(39) + r"[^" + chr(39) + r"]+" + chr(39) +
                      r'|"[^"]+"|[A-Za-z_][A-Za-z0-9_]*)')

#: A heredoc feeding an interpreter is the project's own edit idiom and a short one is honest
#: work. Past this many body lines it is a file being typed through a channel that loses bytes.
MAX_SCRIPT_LINES = 20

NEWLINE = chr(10)
BACKSLASH = chr(92)

BYPASS = "DTENGINE_ALLOW_HEREDOC"


def commands(line):
    """The separate COMMANDS on one shell line, quotes respected.

    A redirect belongs to the command it sits in. Scanning a whole line for one made
    `cat f 2>/dev/null && python - <<EOF` look like a heredoc written to a file.
    """
    out, current, quote, index = [], [], None, 0
    while index < len(line):
        char = line[index]
        if quote:
            if char == chr(92) and quote == chr(34):
                current.append(line[index:index + 2])
                index += 2
                continue
            if char == quote:
                quote = None
            current.append(char)
        elif char in (chr(34), chr(39)):
            quote = char
            current.append(char)
        elif char == ";":
            out.append("".join(current))
            current = []
        elif char in ("&", "|") and index + 1 < len(line) and line[index + 1] == char:
            out.append("".join(current))
            current = []
            index += 2
            continue
        else:
            current.append(char)
        index += 1
    out.append("".join(current))
    return out


def unquoted(line):
    """`line` with the CONTENTS of quoted runs blanked out, positions preserved.

    A `<<` inside a quoted argument is data - `python -c "print('a <<EOF b')"` mentions a heredoc,
    it does not open one. Blanking rather than deleting keeps every offset, so a match found here
    still indexes into the original line.
    """
    out, quote = [], None
    for char in line:
        if quote:
            out.append(" " if char != quote else char)
            if char == quote:
                quote = None
        elif char in (chr(34), chr(39)):
            quote = char
            out.append(char)
        else:
            out.append(char)
    return "".join(out)


def heredocs(command):
    """[(delimiter, body_line_count, writes_to_file, body_text)] for each heredoc.

    `writes_to_file` is true when the heredoc's own command line redirects to a path - the
    `cat > file <<EOF` shape - as opposed to feeding an interpreter's stdin.
    """
    found = []
    lines = command.splitlines()
    for index, line in enumerate(lines):
        # DETECTED in the blanked line so a `<<` inside quotes is not a heredoc, but READ
        # from the original: blanking preserves offsets, not contents, so the delimiter of
        # `<<'EOF'` comes back as three spaces if it is taken from the blanked text.
        match = _HEREDOC.search(unquoted(line))
        if not match:
            continue
        raw = line[match.start(1):match.end(1)]
        delimiter = raw.strip(chr(39) + '"')
        body = 0
        text = []
        for following in lines[index + 1:]:
            if following.strip() == delimiter:
                break
            body += 1
            text.append(following)
        # ONLY THIS COMMAND'S OWN REDIRECT. Scanning the whole line found the `2>/dev/null`
        # of a `cat` three commands earlier and refused an interpreter heredoc.
        before = commands(line[:match.start()])[-1]
        writes = bool(re.search(r">>?\s*[^|&\s]", before)) or "tee " in before
        found.append((delimiter, body, writes, NEWLINE.join(text)))
    return found


def verdict(command):
    """(deny?, reason). Separated from the I/O so it can be driven directly by a test."""
    for delimiter, body, writes, text in heredocs(command):
        if writes:
            return True, (
                "BLOCKED BY PROJECT POLICY (.claude/hooks/guard_heredoc.py).\n"
                "This writes a file through a shell command string (heredoc <<%s, %d lines). "
                "That channel loses bytes: this project has had heredoc content mangled six "
                "times - a backslash-n arriving as a real newline, a backslash-b arriving as "
                "0x08 in a file that still compiled, and a body whose heredoc terminated early "
                "so bash parsed the content as script.\n\n"
                "MEASURED: the same body written to a script FILE and run with `bash file.sh` "
                "round-trips byte-identical. Bash is not the problem; the command string is.\n\n"
                "Use the Write tool. It is exact and it is not a shell.\n" % (delimiter, body))
        if BACKSLASH in text:
            return True, (
                "BLOCKED BY PROJECT POLICY (.claude/hooks/guard_heredoc.py)." + NEWLINE +
                "This heredoc body (<<%s) contains a BACKSLASH, and the command string eats "
                "one level of escaping on the way to the shell. Length is not what does this: "
                "a two-line body is mangled exactly as reliably as a fifty-line one, which is "
                "why the line limit below does not catch it." % delimiter + NEWLINE + NEWLINE +
                "MEASURED, twice, in two codebases: a backslash-n arrived as a real newline; a "
                "backslash-b arrived as 0x08 in a file that still compiled; and on the other "
                "project a backslash level was eaten twice in one session and put NUL bytes "
                "into a committed test runner." + NEWLINE + NEWLINE +
                "Write the body with the Write tool and run the file. It is exact and it is "
                "not a shell." + NEWLINE)
        if body > MAX_SCRIPT_LINES:
            return True, (
                "BLOCKED BY PROJECT POLICY (.claude/hooks/guard_heredoc.py).\n"
                "A %d-line heredoc (<<%s) is a file being typed through a channel that loses "
                "bytes, not a one-liner. Five of this project's six heredoc manglings were "
                "exactly this shape: an edit script inlined into the command.\n\n"
                "Write the script with the Write tool, then run it through the checker that "
                "refuses the mangling:\n"
                "    python tools/dev/safe.py run-script <path>\n"
                "Short heredocs feeding an interpreter (up to %d lines) are still allowed.\n"
                % (body, delimiter, MAX_SCRIPT_LINES))
    return False, None


sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shellin                                    # noqa: E402  (path set above)

HOOK = "guard_heredoc.py"


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
        deny, reason = verdict(command)
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
