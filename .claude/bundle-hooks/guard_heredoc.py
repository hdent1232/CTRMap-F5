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

THE OTHER SPELLINGS OF THE SAME CHANNEL. A heredoc is bash's way of carrying a body inside a
command string; it is not the only one, and README section 10 names the others after measuring
one: *`python -c "..."` and `pwsh -Command "..."` are the same shell string one flag away, and a
guard that refused a backslash in a heredoc body was blind to them for as long as it existed* -
a word boundary written that way arrived as 0x08 and a count came out at 103 both before and
after a change that should have moved it. And this project's primary shell is PowerShell, whose
here-string - `@'` ... `'@` piped into `Set-Content` - is a heredoc with another name. Every one
of them is refused on the same two conditions: it WRITES A FILE, or it is LONGER than a one-liner.

TO RUN ONE ANYWAY: the owner sets `bundle_env.name("ALLOW_HEREDOC")` to 1 for that session.
"""
import json
import os
import re
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_env        # noqa: E402  - one PREFIX renames every override
import bundle_shell      # noqa: E402  - the command, found by shape, at any depth

#: `<<EOF`, `<<'EOF'`, `<<"EOF"`, `<<-EOF`. Not `<<<` (a here-STRING, which carries no body).
_HEREDOC = re.compile(r"(?<!<)<<-?\s*(?![<])(" + chr(39) + r"[^" + chr(39) + r"]+" + chr(39) +
                      r'|"[^"]+"|[A-Za-z_][A-Za-z0-9_]*)')

#: A heredoc feeding an interpreter is the project's own edit idiom and a short one is honest
#: work. Past this many body lines it is a file being typed through a channel that loses bytes.
MAX_SCRIPT_LINES = 20

BYPASS = bundle_env.name("ALLOW_HEREDOC")

#: A PowerShell here-string: `@'` or `@"` at the end of a line, closed by `'@` / `"@` at the
#: START of a later one. The body between them is content carried inside the command string.
_HERE_STRING = re.compile(r"@(['" + chr(34) + r"])[ \t]*\r?\n(.*?)\r?\n\1@", re.S)

#: What makes the body a FILE rather than an interpreter's stdin: a cmdlet that writes one, or a
#: redirect. `Out-File`, `Set-Content`, `Add-Content`, `Tee-Object -FilePath`, `>` and `>>`.
_PS_WRITES = re.compile(
    r"\|\s*(?:Out-File|Set-Content|Add-Content|sc|ac|Tee-Object)\b|(?<![0-9])>>?\s*[^|&\s]",
    re.I)

#: An interpreter handed its program as an ARGUMENT - the other channel README section 10 names.
#: `python -c`, `py -c`, `pwsh -Command`, `powershell -Command`, `bash -c`, `sh -c`, `node -e`.
_INLINE_PROGRAM = re.compile(
    r"(?:^|[\s;&|(])(?:python[0-9.]*(?:\.exe)?|py|node|pwsh|powershell(?:\.exe)?|bash|sh)"
    r"(?:\s+-[A-Za-z]+(?:\s+\S+)?)*?\s+(?:-c|-e|-Command|-command)\s+", re.I)

#: The body of an inline program writes a file when it opens one for writing or calls a
#: whole-file writer. File content in the program text is file content in a command string.
_BODY_WRITES = re.compile(
    r"open\([^)]*,\s*['" + chr(34) + r"][wax]b?['" + chr(34) + r"]|\.write_(?:text|bytes)\("
    r"|Set-Content|Out-File|Add-Content", re.I)


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
    """[(delimiter, body_line_count, writes_to_file)] for each heredoc in the command.

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
        for following in lines[index + 1:]:
            if following.strip() == delimiter:
                break
            body += 1
        # ONLY THIS COMMAND'S OWN REDIRECT, AND BOTH SIDES OF THE OPERATOR.
        #
        # Scanning the whole line found the `2>/dev/null` of a `cat` three commands earlier and
        # refused an honest interpreter heredoc, so this was narrowed to the text BEFORE `<<`.
        # It was narrowed TOO FAR: `cat <<EOF > notes.txt` puts the redirect on the other side,
        # the two orders are identical to the shell, and the undetected one is the commoner
        # spelling. Measured: three of five spellings were ALLOWED, so the rule this hook
        # exists for - file content never travels in a command string, after six manglings -
        # was defeated by moving six characters. Same family as `live_allowed=True` with two
        # spaces deleted.
        #
        # Found by writing a test suite for the extracted bundle and running it here.
        #
        # THIS COMMAND means: after the last separator before the operator, and before the next
        # separator after it. Still not the whole line, so the false positive above stays fixed.
        before = commands(line[:match.start()])[-1]
        after = (commands(line[match.end():]) or [""])[0]
        segment = before + " " + after
        # A DIGIT-PREFIXED REDIRECT IS NOT A FILE WRITE. `python - <<EOF 2>/dev/null` sends
        # stderr somewhere and carries no content into a path; refusing it would be that same
        # false positive reintroduced from the other side.
        writes = bool(re.search(r"(?<![0-9])>>?\s*[^|&\s]", segment)) or "tee " in segment
        found.append((delimiter, body, writes))
    return found


def here_strings(command):
    """[(body_line_count, writes_to_file)] for each PowerShell here-string in the command."""
    found = []
    for match in _HERE_STRING.finditer(command or ""):
        body = match.group(2)
        # THIS COMMAND'S writer only: up to the next separator, never a later command's.
        rest = bundle_shell.split_commands(command[match.end():])
        found.append((body.count(chr(10)) + 1, bool(_PS_WRITES.search(rest[0] if rest else ""))))
    return found


def _quoted_argument(text, start):
    """The quoted string beginning at or after `start` (its contents), or the bare word there."""
    index = start
    while index < len(text) and text[index] in " \t":
        index += 1
    if index >= len(text):
        return ""
    quote = text[index]
    if quote not in ("'", chr(34)):
        end = index
        while end < len(text) and not text[end].isspace():
            end += 1
        return text[index:end]
    body, index = [], index + 1
    while index < len(text):
        char = text[index]
        if char == chr(92) and quote == chr(34) and index + 1 < len(text):
            body.append(text[index:index + 2])
            index += 2
            continue
        if char == quote:
            break
        body.append(char)
        index += 1
    return "".join(body)


def inline_programs(command):
    """[(body_line_count, writes_a_file_with_escapes)] for each `python -c` / `-Command` body."""
    found = []
    for match in _INLINE_PROGRAM.finditer(command or ""):
        body = _quoted_argument(command, match.end())
        writes = bool(_BODY_WRITES.search(body)) and chr(92) in body
        found.append((body.count(chr(10)) + 1, writes))
    return found


def verdict(command):
    """(deny?, reason). Separated from the I/O so it can be driven directly by a test."""
    for body, writes in here_strings(command):
        if writes or body > MAX_SCRIPT_LINES:
            return True, (
                bundle_shell.policy(__file__) + "\n"
                "This carries a %d-line PowerShell here-string %s. A here-string is a heredoc "
                "with another name: the body travels inside the command string, which is the "
                "channel this project has had content mangled through six times.\n\n"
                "Use the Write tool for a file. For a script, Write it and run it through "
                "`python tools/dev/safe.py run-script <path>`.\n"
                % (body, "into a file" if writes else "as a program"))
    for body, writes in inline_programs(command):
        if body > MAX_SCRIPT_LINES or writes:
            return True, (
                bundle_shell.policy(__file__) + "\n"
                "This hands an interpreter a %d-line program as a command-line argument%s. "
                "README section 10: `python -c` and `-Command` are the same shell string one "
                "flag away from a heredoc, and a word boundary written that way arrived as 0x08 "
                "- the regex compiled, matched nothing, and a count came out identical before "
                "and after the change it was measuring.\n\n"
                "Write the program to a file with the Write tool and run the file.\n"
                % (body, ", writing a file whose content carries backslash escapes"
                   if writes else ""))
    for delimiter, body, writes in heredocs(command):
        if writes:
            return True, (
                bundle_shell.policy(__file__) + "\n"
                "This writes a file through a shell command string (heredoc <<%s, %d lines). "
                "That channel loses bytes: this project has had heredoc content mangled six "
                "times - a backslash-n arriving as a real newline, a backslash-b arriving as "
                "0x08 in a file that still compiled, and a body whose heredoc terminated early "
                "so bash parsed the content as script.\n\n"
                "MEASURED: the same body written to a script FILE and run with `bash file.sh` "
                "round-trips byte-identical. Bash is not the problem; the command string is.\n\n"
                "Use the Write tool. It is exact and it is not a shell.\n" % (delimiter, body))
        if body > MAX_SCRIPT_LINES:
            return True, (
                bundle_shell.policy(__file__) + "\n"
                "A %d-line heredoc (<<%s) is a file being typed through a channel that loses "
                "bytes, not a one-liner. Five of this project's six heredoc manglings were "
                "exactly this shape: an edit script inlined into the command.\n\n"
                "Write the script with the Write tool, then run it through the checker that "
                "refuses the mangling:\n"
                "    python tools/dev/safe.py run-script <path>\n"
                "Short heredocs feeding an interpreter (up to %d lines) are still allowed.\n"
                % (body, delimiter, MAX_SCRIPT_LINES))
    return False, None


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        sys.exit(0)                      # never break a turn on a parse failure
    # THE SHAPE, NOT THE NAME. This read `!= "Bash"`, and this session's primary shell is
    # PowerShell: the guard was installed, wired, tracked, tested and proven by planting - and
    # OFF for every command issued through the other tool. Measured 2026-09-22 by driving a
    # PowerShell payload through `dispatch.py`: the heredoc case came back allowed.
    # Ask whether this tool input carries a command; the tool's name is not the question.
    # THE SHAPE, FOUND AT ANY DEPTH. This read one `command` key at one level, which a
    # BATCHING tool walks straight past by nesting its inputs - the same hole as
    # `tool_name != "Bash"`, one level down, inside the fix for it. `bundle_shell`
    # walks the payload to a bounded depth, takes `command` OR `script` wherever it
    # sits, and reports a command it could not READ as trouble rather than as none.
    if not bundle_shell.commands(payload):
        sys.exit(0)
    if bundle_env.allowed("ALLOW_HEREDOC"):
        sys.exit(0)
    # THE SAME TEXT THE GATE LOOKED AT. This judged one shallow key while the gate
    # above searched the whole payload, so a heredoc nested inside a batching tool's
    # input passed the gate and was then judged as an empty string - allowed.
    deny, reason = verdict(bundle_shell.text(payload))
    if deny:
        json.dump({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }}, sys.stdout)
    sys.exit(0)


if __name__ == "__main__":
    main()
