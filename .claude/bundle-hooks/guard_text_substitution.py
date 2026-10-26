#!/usr/bin/env python3
"""TEXT IS NOT RUN. A backtick inside text bash would RUN is refused before the command runs.

MEASURED 2026-10-18 on CTRMap: an answer to the owner was recorded with `request_ledger.py
answered`, its text double-quoted and holding a backticked `python -B tools/whole_tree.py check` -
the habit of writing a command name in backticks, as any message does. Bash ran that command first
and stored its OUTPUT inside the owner's answer, so the record matched nothing the owner was shown,
and the turn end was refused twice before anyone found why. The heredoc and backslash guards see
neither shape: nothing in the call is a heredoc, and nothing is a backslash.

WHERE BASH RUNS A BACKTICK IN TEXT: inside a double-quoted argument, and in the body of a heredoc
whose delimiter is not quoted. Not inside single quotes, not escaped (`\\` before it), not in a
quoted-delimiter heredoc - the `git commit -m "$(cat <<'EOF' ...)"` idiom is text to the end - and
not in PowerShell, where a backtick only escapes the next character. A backtick at the top level
of a command, outside any quote, is a substitution written as one and is left alone.

WHEN RUNNING IT IS THE POINT, `$(...)` says so, and this does not read it: a `$(` in a double-quoted
argument is the deliberate spelling (`"$(git rev-parse HEAD)"`), and nothing in the call tells it
from a `$(` meant as text. What this cannot see, said rather than implied: `$(...)` written into
text by mistake.

TO RUN ONE ANYWAY: the owner sets `bundle_env.name("ALLOW_TEXT_SUBSTITUTION")` to 1 for that session.
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

LF = chr(10)
BACKSLASH = chr(92)

#: A heredoc's opener: `<<EOF`, `<<-EOF`, `<<'EOF'`, `<<"EOF"`, `<<\EOF` - quoted when its body is text.
_OPENER = re.compile(r"<<(-?)[ \t]*(?:'([^'" + LF + r"]*)'|\"([^\"" + LF + r"]*)\"|(\\?)([A-Za-z0-9_.\-]+))")


def _closing(text, at):
    """Where the backtick substitution starting at `at` ends: its closing backtick, or the text's end."""
    while at < len(text):
        if text[at] == BACKSLASH:
            at += 2
            continue
        if text[at] == "`":
            return at
        at += 1
    return len(text)


def _body(text, at, opened, found):
    """Reads the heredoc bodies `opened` - [(delimiter, runs, strips tabs)] - from `at`, the line after
    their opener; notes each backtick a body whose delimiter is unquoted runs; answers where they end."""
    for delimiter, runs, strips in opened:
        while at < len(text):
            end = text.find(LF, at)
            line = text[at:] if end < 0 else text[at:end]
            at = len(text) if end < 0 else end + 1
            if (line.lstrip("\t") if strips else line) == delimiter:
                break
            index = 0
            while runs and index < len(line):
                if line[index] == BACKSLASH:
                    index += 2
                    continue
                if line[index] == "`":
                    close = _closing(line, index + 1)
                    found.append(("the body of a heredoc whose delimiter is not quoted",
                                  line[index + 1:close]))
                    index = close + 1
                    continue
                index += 1
    return at


def ran_in_text(command, shell=None):
    """[(where, what it runs)] - every backtick substitution bash would RUN from inside text in `command`.
    PowerShell runs none: a backtick there escapes the next character. A shell nobody named is read as bash.

    One pass over the command, holding a stack of what each character is inside: the command itself,
    a single-quoted or double-quoted string, or a `$(...)` - which opens a command of its own, so a
    quoted-delimiter heredoc inside `"$(...)"` is still text."""
    if shell == "powershell":
        return []
    text = command or ""
    found = []
    frames = [["command", 0]]               # what we are inside, and its open `(` count
    opened = []                             # heredocs whose bodies start at the next line
    at = 0
    while at < len(text):
        inside = frames[-1][0]
        char = text[at]
        if inside == "single":
            if char == "'":
                frames.pop()
            at += 1
            continue
        if char == BACKSLASH:
            at += 2
            continue
        if char == "`":
            close = _closing(text, at + 1)
            if inside == "double":
                found.append(("a double-quoted argument", text[at + 1:close]))
            at = close + 1
            continue
        if text.startswith("$(", at):
            frames.append(["command", 0])
            at += 2
            continue
        if inside == "double":
            if char == '"':
                frames.pop()
            at += 1
            continue
        if char == "'":
            frames.append(["single", 0])
        elif char == '"':
            frames.append(["double", 0])
        elif char == "(":
            frames[-1][1] += 1
        elif char == ")":
            if frames[-1][1]:
                frames[-1][1] -= 1
            elif len(frames) > 1:
                frames.pop()
        elif char == "#" and (at == 0 or text[at - 1] in " \t;&|(" + LF):
            end = text.find(LF, at)
            at = len(text) if end < 0 else end
            continue
        elif text.startswith("<<", at) and not text.startswith("<<<", at):
            opener = _OPENER.match(text, at)
            if opener:
                strips, single, double, escaped, bare = opener.groups()
                delimiter = single if single is not None else double if double is not None else bare
                runs = single is None and double is None and not escaped
                opened.append((delimiter, runs, bool(strips)))
                at = opener.end()
                continue
        elif char == LF and opened:
            at = _body(text, at + 1, opened, found)
            opened = []
            continue
        at += 1
    return found


def verdict(payload):
    """(exit code, message) for one call."""
    if not isinstance(payload, dict):
        return 0, ""
    found = [hit for command in bundle_shell.commands(payload)
             for hit in ran_in_text(command, bundle_shell.shell_of(payload.get("tool_name")))]
    if not found or bundle_env.allowed("ALLOW_TEXT_SUBSTITUTION"):
        return 0, ""
    where, runs = found[0]
    return 2, (bundle_shell.policy(__file__) + LF
               + "A backtick in %s RUNS its text as a command before this one does: this would run `%s`, "
                 "and put what it prints where the text was (%d such in this call).%s%s"
                 "Measured 2026-10-18: an answer recorded double-quoted, citing a backticked "
                 "`python -B tools/whole_tree.py check`, ran that command and stored its output inside "
                 "the owner's answer - the record matched nothing the owner was shown.%s%s"
                 "If it is text, single-quote it or escape each backtick with a backslash. If running it "
                 "is the point, spell it $(...), which says so. The owner's way past: %s=1.%s"
                 % (where, runs.strip(), len(found), LF, LF, LF, LF,
                    bundle_env.name("ALLOW_TEXT_SUBSTITUTION"), LF))


def main():
    try:
        payload = json.loads(bundle_shell.payload_text())
    except (ValueError, IOError):
        return 0
    code, said = verdict(payload)
    if code:
        sys.stderr.write(said)
    return code


if __name__ == "__main__":
    bundle_shell.speak_utf8()
    sys.exit(main())
