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
    running a script      -> Write it with the Write tool, then run the FILE with its
                             interpreter: `python <path>`, `bash <path>`. (The project this came
                             from ran it through a checker of its own, `tools/dev/safe.py`; it
                             ships nowhere, and a refusal must not send its reader there.)
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

#: A PROJECT'S STRICTER RULE IS TWO SETTINGS, NOT A SECOND COPY. CTRMap's owner wrote "No Bash
#: heredocs for Java or Python - write the script to a file", with no length in it, and CTRMap held
#: that by keeping its own copy of this guard: two copies of one rule, both asked on every call,
#: the shape the install check refuses (CTRMap F167). Measured on 29 cases both copies' tests ask,
#: its copy refused 5 this one let through, and this one refused none its copy let through. Both
#: are off here, so nothing about this guard changes for a project that sets neither:
#:   NEVER_FED     a regex over a program's name: no heredoc feeds a program it matches, at any
#:                 length (CTRMap: Python and Java);
#:   BODY_ESCAPES  True refuses a BACKSLASH in any heredoc body, and in the code an interpreter is
#:                 handed with -c, -e or -Command, at any length - the escape is what the command
#:                 string eats (a word boundary arrived as 0x08, README section 10).
NEVER_FED = r"py|pythonw?[0-9.]*|java|javaw|javac|jshell"
BODY_ESCAPES = True

#: Adapted per project: see the bootstrap's ADAPT.md.
ADAPT = ("NEVER_FED", "BODY_ESCAPES")

#: The settings that ship OFF, at the value they ship at. A project turns them on; the bundle's
#: own tests turn them off again for their run (`bundle_hooks.settings_off`).
SHIPPED_OFF = {"NEVER_FED": "", "BODY_ESCAPES": False}

#: The programs BODY_ESCAPES asks when one is handed code as an argument. `git -c` is handed a
#: setting, and a backslash in a Windows path there is a path, not an escape the shell will eat.
_INTERPRETER = re.compile(r"py|pythonw?[0-9.]*|perl|ruby|node|bash|sh|zsh|pwsh|powershell", re.I)

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
    return [(delimiter, body, writes)
            for delimiter, body, writes, _text, _feeds in heredoc_bodies(command)]


def heredoc_bodies(command):
    """`heredocs`, each with its BODY and the program it FEEDS: the program that runs, through any
    runner (`timeout 9 python - <<EOF` feeds python), or '' when none can be read.

    IN EVERY TEXT A SHELL IS HANDED (`bundle_shell.texts`), as `inline_bodies` reads them. Measured
    2026-10-09 by CTRMap's HookRefusalsTest: a heredoc this refused as a line passed handed to
    `bash -c` - it was looked for in the command's own lines only, where the shell body is one
    quoted word - so a 30-line program and a file write alike went through with one wrapper."""
    found = []
    for handed in bundle_shell.texts(command):
        found.extend(_heredocs_in(handed))
    return found


def _heredocs_in(command):
    """`heredoc_bodies` of one text, its own lines only."""
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
        body, text = 0, []
        for following in lines[index + 1:]:
            if following.strip() == delimiter:
                break
            body += 1
            text.append(following)
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
        runs = [words for words, _bg in bundle_shell.runs(before) if words]
        feeds = bundle_shell.program_of(runs[0][0][0]) if runs else ""
        found.append((delimiter, body, writes, chr(10).join(text), feeds))
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


#: Programs that write their arguments: content with an escape in it, handed to one of these and
#: sent into a file, is file content in a command string.
_SAYS = ("printf", "echo", "write-output", "write-host")

#: Where a stage's output lands in a FILE: a redirect to a path, or a program that writes one.
_FILE_WRITERS = ("tee", "set-content", "add-content", "out-file", "sc", "ac")


def escaped_into_file(command):
    """The command that sends content carrying a backslash escape into a FILE through `printf` or
    `echo`, or ''.

    THE SAME CHANNEL, SPELLED AS A ONE-LINER. A heredoc into a file is refused at any length; so
    is an inline program writing escaped content. `printf 'a\\nb' > f` is the same bytes - an
    escape inside a command string, landing in a file - and the escape is exactly what arrived
    mangled six times (`\\b` as 0x08). A plain word echoed into a file carries no escape and is
    left alone; an escape echoed to the screen lands nowhere."""
    for one in bundle_shell.split_commands(command):
        held = bundle_shell.pairs(bundle_shell.heredoc_split(one)[0])
        if not held:
            continue
        lands = False
        for at, (word, _typed) in enumerate(held):
            if re.fullmatch(r"&?>>?\|?", word) and at + 1 < len(held):
                target = held[at + 1][1] or held[at + 1][0]
                if not target.startswith("&") and target.lower() not in ("/dev/null", "nul",
                                                                           "$null"):
                    lands = True
        stages = [words for words, _bg in bundle_shell.runs(one) if words]
        lands = lands or any(bundle_shell.program_of(words[0][0]) in _FILE_WRITERS
                             for words in stages)
        if not lands:
            continue
        for words in stages:
            if bundle_shell.program_of(words[0][0]) in _SAYS and any(
                    chr(92) in (typed if typed is not None else word)
                    for word, typed in words[1:]):
                return one
    return ""


def long_programs(command):
    """[(program, body lines, writes with an escape)] for every program handed code as an
    argument - `-c`, `-e`, `-r`, `-Command` - whatever the program is called.

    A LIST OF INTERPRETERS IS THE CLASS MINUS THE NEXT ONE. `_INLINE_PROGRAM` names python, py,
    node, pwsh, powershell, bash and sh, so `perl -e`, `ruby -e`, `zsh -c` and `php -r` carried a
    long program unasked. Asked of every program's flags instead, and of every command a shell in
    the text is handed - read, not run."""
    return [(program, body.count(chr(10)) + 1, bool(_BODY_WRITES.search(body)) and chr(92) in body)
            for program, body in inline_bodies(command)]


def inline_bodies(command):
    """[(program, the code it is handed)] - the one reading `long_programs` and BODY_ESCAPES ask."""
    found = []
    for text in bundle_shell.texts(command):
        for one in bundle_shell.split_commands(text):
            held = bundle_shell.pairs(bundle_shell.heredoc_split(one)[0])
            for stage in bundle_shell.stages(held) if held else []:
                rest, _moves = bundle_shell.peeled(stage)
                for at, (word, _typed) in enumerate(rest[1:-1], 1):
                    if re.fullmatch(r"-[A-Za-z]*[cer]|-command", word, re.I):
                        body = rest[at + 1][1] if rest[at + 1][1] is not None else rest[at + 1][0]
                        found.append((bundle_shell.program_of(rest[0][0]), body))
                        break
    return found


def verdict(command):
    """(deny?, reason). Separated from the I/O so it can be driven directly by a test."""
    escaped = escaped_into_file(command)
    if escaped:
        return True, (
            bundle_shell.policy(__file__) + "\n"
            "This writes content carrying a backslash escape into a file through a command "
            "string: %s\n\n"
            "That is the channel this project has had content mangled through six times - a "
            "backslash-n arriving as a real newline, a backslash-b arriving as 0x08 in a file "
            "that still compiled. A heredoc into a file is refused for it; this is the same "
            "bytes on one line.\n\n"
            "Use the Write tool. It is exact and it is not a shell.\n" % escaped[:160])
    if BODY_ESCAPES:
        for program, body in inline_bodies(command):
            if chr(92) in body and _INTERPRETER.fullmatch(program or ""):
                return True, (
                    bundle_shell.policy(__file__) + "\n"
                    "This hands %s code carrying a BACKSLASH as a command-line argument, and this "
                    "project refuses that at any length (BODY_ESCAPES): the command string eats "
                    "one level of escaping on its way to the shell, and a word boundary written "
                    "this way arrived as 0x08 - the regex compiled, matched nothing, and the count "
                    "it measured came out the same before and after the change.\n\n"
                    "Write the program to a file with the Write tool and run the file, or spell "
                    "the character `chr(92)`.\n" % program)
    for program, body, writes in long_programs(command):
        if body > MAX_SCRIPT_LINES or writes:
            return True, (
                bundle_shell.policy(__file__) + "\n"
                "This hands %s a %d-line program as a command-line argument%s - the same shell "
                "string one flag away from a heredoc, whatever the interpreter.\n\n"
                "Write the program to a file with the Write tool and run the file.\n"
                % (program, body, ", writing a file whose content carries backslash escapes"
                   if writes else ""))
    for body, writes in here_strings(command):
        if writes or body > MAX_SCRIPT_LINES:
            return True, (
                bundle_shell.policy(__file__) + "\n"
                "This carries a %d-line PowerShell here-string %s. A here-string is a heredoc "
                "with another name: the body travels inside the command string, which is the "
                "channel this project has had content mangled through six times.\n\n"
                "Use the Write tool for a file. For a script, Write it and run the file with "
                "its interpreter - `pwsh -File <path>`, `python <path>`.\n"
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
    for delimiter, body, writes, text, feeds in heredoc_bodies(command):
        if NEVER_FED and feeds and re.fullmatch(NEVER_FED, feeds, re.I):
            return True, (
                bundle_shell.policy(__file__) + "\n"
                "This heredoc (<<%s, %d lines) feeds %s, and this project's rule has no length "
                "in it: no heredoc feeds %s at all (NEVER_FED).\n\n"
                "Write the script with the Write tool and run the file. It is exact and it is "
                "not a shell.\n" % (delimiter, body, feeds, feeds))
        if BODY_ESCAPES and chr(92) in text:
            return True, (
                bundle_shell.policy(__file__) + "\n"
                "This heredoc body (<<%s) carries a BACKSLASH, and this project refuses that at "
                "any length (BODY_ESCAPES): the command string eats one level of escaping on its "
                "way to the shell, and a two-line body is mangled as reliably as a fifty-line "
                "one - a backslash-n arrived as a real newline, a backslash-b as 0x08.\n\n"
                "Write the body with the Write tool and run the file.\n" % delimiter)
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
                "Write the script with the Write tool, then run the file with its interpreter:\n"
                "    python <path>        (or bash <path>, node <path>)\n"
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
