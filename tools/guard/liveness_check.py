#!/usr/bin/env python3
"""A QUERY THAT CANNOT READ ITS SUBJECT MUST NOT REPORT IT ABSENT.

WHAT THIS COST, twice, measured.

`os.kill(pid, 0)` is not a liveness probe on Windows. Python implements every signal through
`OpenProcess(PROCESS_TERMINATE)`, so a process this account may not terminate raises
`[WinError 87]` and reads as DEAD. Checked against the process table on 2026-09-08 it called
three live processes dead - one of them the sweep runner the guard existed to notice, and the
function deciding whether to CLEAR a lock was one of its callers.

That was found, written down, and closed in the project's python. The same mistake then
arrived in a shell command no python checker could see:

    Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'run_named' }

`CommandLine` is EMPTY for any process this account cannot open, so the filter matched nothing
and twelve live workers were reported gone. Acting on that belief discarded 134 verdicts.

Every probe has an answer for "it is not there" and an answer for "I COULD NOT LOOK". Code
that collapses the second into the first reports a clean negative, which is the most dangerous
result there is: it looks exactly like a careful check that found nothing.

WHAT THIS REFUSES, across every .py and .ps1 in the project:

  1. `os.kill(pid, 0)` outside `liveness.py`, unless the file branches on `os.name` - because
     on Windows it answers the wrong question
  2. a `CommandLine` filter used to decide a process is absent
  3. a file that answers the liveness question itself instead of asking `liveness.alive`

Usage: python tools/guard/liveness_check.py [root]
Exit 1 with reasons, 0 when the question is asked in one place and asked correctly.
"""
import io
import os
import re
import sys

#: The one place allowed to answer it, and the one place allowed to route to that answer.
THE_ANSWER = "liveness.py"

#: What must not appear anywhere else.
_OS_KILL = re.compile(r"\bos\.kill\s*\(\s*[^,]+,\s*0\s*\)")
_BRANCHES = re.compile(r"os\.name\s*[!=]=\s*[\"']nt[\"']")
_COMMANDLINE = re.compile(r"CommandLine\s*(?:-match|-like|-eq|\.Contains|=~)", re.I)
#: a Windows process probe written out by hand rather than asked for
_HAND_ROLLED = re.compile(r"OpenProcess\s*\(")
#: `tasklist /FI "PID eq ..."` is not wrong, but it is a fourth spelling of one question
_TASKLIST = re.compile(r"\btasklist\b[^\n]*\bPID\s+eq\b", re.I)

SKIP_DIRS = ("build", "dist", ".git", "__pycache__", "node_modules", "wt", "lib")


#: What has to be nearby for a shell probe to be a shell probe rather than an explanation of
#: one. The refusal messages in the guards themselves quote a `CommandLine` filter verbatim,
#: and a guard that refuses its own refusal text is no use to anybody.
_RUNS_A_SHELL = ("subprocess", "Popen", "check_output", "os.system", "os.popen", "run(")


def _near(body, at, window=400):
    """True when something that RUNS a command appears just before `at`."""
    start = max(0, at - window)
    return any(word in body[start:at] for word in _RUNS_A_SHELL)


def code_of(path, body):
    """`body` with comments and string literals blanked, so this reads CODE and not prose.

    THE FIRST RUN OF THIS CHECKER REPORTED SEVEN DEFECTS AND EVERY ONE WAS ITS OWN
    EXPLANATION: the docstrings that describe `os.kill(pid, 0)` and a `CommandLine` filter, in
    the files that exist to refuse them. A checker that matches its own prose cannot tell a
    warning from a defect - and the opposite failure, where blanking hides a real call, is why
    the blanked text keeps its line structure and the suite plants a real one to prove it is
    still found.
    """
    if path.endswith(".py"):
        return _blank_python(body)
    return _blank_powershell(body)


def shell_of(path, body):
    """The same file with comments and docstrings gone but string LITERALS kept.

    A shell probe lives inside a string: `['tasklist', '/FI', ...]`, or a PowerShell
    pipeline. Scanning the fully blanked code could not see either, so the two spellings
    this exists to refuse went unrefused while it reported a clean tree."""
    if path.endswith(".py"):
        return _blank_python(body, keep_strings=True)
    return _blank_powershell(body, keep_strings=True)


def _blank_python(body, keep_strings=False):
    """Python with comments and docstrings blanked, keeping the shape of the file.

    `keep_strings` keeps ordinary string literals, because a SHELL command lives in one. The
    distinction is docstring versus literal, not comment versus string: blanking every string
    made the branch test unable to see `os.name == "nt"` at all - so a probe that did branch
    was reported as one that did not - and it hid a `tasklist` command inside a subprocess
    argument list, so the second spelling this refuses went unrefused.
    """
    import io as _io
    import tokenize
    try:
        out = []
        last = (1, 0)
        fresh = True                     # at the start of a logical line: a string here is a
        for token in tokenize.generate_tokens(_io.StringIO(body).readline):  # docstring
            kind, text, start, end, _ = token
            #: keep the gap between tokens so line numbers and word boundaries survive
            while last[0] < start[0]:
                out.append("\n")
                last = (last[0] + 1, 0)
            if start[1] > last[1]:
                out.append(" " * (start[1] - last[1]))
            blank = kind == tokenize.COMMENT or (
                kind == tokenize.STRING and (fresh or not keep_strings))
            if blank:
                #: a blank of the same shape, so nothing shifts
                out.append("".join("\n" if c == "\n" else " " for c in text))
            else:
                out.append(text)
            if kind not in (tokenize.NL, tokenize.NEWLINE, tokenize.INDENT,
                            tokenize.DEDENT, tokenize.COMMENT):
                fresh = kind == tokenize.STRING and fresh
            last = end
        return "".join(out)
    except (tokenize.TokenError, IndentationError, SyntaxError):
        #: A FILE THIS CANNOT PARSE IS NOT A CLEAN FILE. Scanning the raw text may give a
        #: false positive, which is loud; skipping it gives a false negative, which is not.
        return body


def _blank_powershell(body, keep_strings=False):
    out = []
    quote = None
    index = 0
    while index < len(body):
        char = body[index]
        if quote:
            out.append(char if keep_strings else ("\n" if char == "\n" else " "))
            if char == quote:
                quote = None
            index += 1
            continue
        if char in ('"', chr(39)):
            quote = char
            out.append(char if keep_strings else " ")
            index += 1
            continue
        if char == "#":
            while index < len(body) and body[index] != "\n":
                out.append(" ")
                index += 1
            continue
        out.append(char)
        index += 1
    return "".join(out)


def files(root):
    """Every python and PowerShell file this project owns."""
    out = []
    for dirpath, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in names:
            if name.endswith((".py", ".ps1")):
                out.append(os.path.join(dirpath, name))
    return out


def findings(root):
    """Every place the liveness question is answered wrongly, or answered twice."""
    why = []
    looked = 0
    for path in files(root):
        base = os.path.basename(path)
        try:
            body = io.open(path, encoding="utf-8", errors="replace").read()
        except OSError as cannotRead:
            why.append("cannot read %s (%s) - an unreadable file is not a clean one"
                       % (path, cannotRead))
            continue
        looked += 1
        rel = os.path.relpath(path, root)
        #: TWO VIEWS OF THE SAME FILE, because the two kinds of defect live in different
        #: places. A python probe is CODE; a shell probe is a STRING. One view could not see
        #: both: blanking every literal hid the shell command, and keeping them made every
        #: refusal message that quotes the trap look like the trap.
        raw = body
        code = code_of(path, raw)
        shell = shell_of(path, raw)
        body = code

        found = _COMMANDLINE.search(shell)
        if found and (path.endswith(".ps1") or _near(shell, found.start())):
            why.append(
                "%s filters processes on CommandLine. That property is EMPTY for any process "
                "this account cannot open, so the filter silently matches nothing and reports "
                "live processes as gone - it did, for twelve workers, and 134 verdicts were "
                "discarded on the strength of it." % rel)

        if base == THE_ANSWER:
            continue

        #: the CALL is code, but the branch that makes it safe compares against the
        #: literal "nt" - which the blanked view does not have. Asking the wrong view
        #: reported a probe that DID branch as one that did not.
        if _OS_KILL.search(code) and not _BRANCHES.search(shell):
            why.append(
                "%s uses os.kill(pid, 0) as a liveness probe without branching on os.name. On "
                "Windows python routes every signal through OpenProcess(PROCESS_TERMINATE), so "
                "a process this account may not terminate reads as DEAD - measured against the "
                "process table, it called three live processes dead here. Ask "
                "liveness.alive(pid) instead." % rel)

        if _HAND_ROLLED.search(body):
            why.append(
                "%s calls OpenProcess itself. The project has one answer to whether a process "
                "is alive, in %s, because a question answered in several places is answered "
                "differently in one of them - and the wrong answer here is not loud, it is a "
                "guard that never fires." % (rel, THE_ANSWER))

        found = _TASKLIST.search(shell)
        if found and (path.endswith(".ps1") or _near(shell, found.start())):
            why.append(
                "%s probes liveness with tasklist. It is not wrong, which is exactly the "
                "problem: it is a second spelling of a question that has one answer in %s, and "
                "the next spelling will not be right." % (rel, THE_ANSWER))

    if not looked:
        why.append("NOTHING WAS READ under %s - a scan that examined no files cannot report a "
                   "clean result. See rule one." % os.path.abspath(root))
    return why


def main(argv):
    root = argv[1] if len(argv) > 1 else "."
    why = findings(root)
    if not why:
        print("the liveness question is asked in one place, and asked correctly (%d files read)"
              % len(files(root)))
        return 0
    print("A PROBE HERE CAN REPORT A LIVE PROCESS ABSENT:")
    for reason in why:
        print("  - " + reason)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
