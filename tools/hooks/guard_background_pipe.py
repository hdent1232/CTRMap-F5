#!/usr/bin/env python3
"""Refuse a long-running command piped into a consumer that buffers or truncates it.

WHY THIS EXISTS, and it is not a style preference.

`python tools/audit/mutate.py <module> <pattern> | tail -70`, backgrounded. `tail` reads to EOF
before printing anything, so the run produced NO interim output for nineteen minutes and the exit
code that came back was `tail`'s. Believing it had hung, I killed the task. A hard kill skips
`mutate.py`'s `finally`, so `dtengine/invest/riskdesk/pre_trade_checks.py` was left ON DISK with
an injected fault - five hundred lines shorter, because `ast.unparse` drops comments - showing in
`git status` as an ordinary modification.

`head` is worse: it closes the pipe and SIGPIPEs the producer mid-mutation, which does the same
damage without anyone deciding to.

That damage already has a guard - `.githooks/pre-commit` and `tools/dev/safe.py` refuse to commit
or rewrite under `.mutation-in-flight`. Those guard the CONSEQUENCE. The cause is the pipe, and
because nothing guarded the cause I wrote the same command again twice more in the same session.
This is the guard on the cause.

WHAT TO DO INSTEAD: redirect to a file and read the file.

    python tools/audit/mutate.py <module> <pattern> > out.txt 2>&1

The harness already captures a backgrounded command's whole output to a file, so the pipe was
never buying anything in the first place.

TO RUN ONE ANYWAY: the owner sets DTENGINE_ALLOW_PIPE=1 for that session.
"""
import json
import os
import re
import sys

#: Consumers that either read to EOF before emitting anything, or close the pipe early.
BUFFERING = ("tail", "head", "less", "more", "sort", "uniq", "wc", "column", "tac")

#: Commands whose interruption leaves a source file on disk carrying an injected fault. For these
#: the pipe is refused whether or not the command was backgrounded, because SIGPIPE does not wait
#: to be asked.
#: SCRIPTS THAT LEAVE DAMAGE IF KILLED MID-RUN. These two are this project's; every project has
#: its own, and a hardcoded list is a guard that is dead in every project but one - which is what
#: happened: pointed at a second codebase whose sweep is `tools/mutate2.py`, it knew nothing about
#: the only script there that can leave an injected fault on disk, and allowed the exact pipe it
#: exists to refuse. So the list is data: a project drops `destructive_scripts.txt` beside this
#: file, one path per line, and the hook stays byte-identical everywhere.
_DEFAULT_DESTRUCTIVE = ("tools/audit/mutate.py", "tools/audit/weigh.py")


def _destructive():
    beside = os.path.join(os.path.dirname(os.path.abspath(__file__)), "destructive_scripts.txt")
    try:
        with open(beside, encoding="utf-8") as handle:
            named = tuple(line.strip() for line in handle
                          if line.strip() and not line.startswith("#"))
        return named or _DEFAULT_DESTRUCTIVE
    except OSError:
        return _DEFAULT_DESTRUCTIVE


DESTRUCTIVE_IF_INTERRUPTED = _destructive()
#: What actually starts a python program here. `sh`/`bash` are included because a script
#: run through them ends up executing the same driver.
INTERPRETERS = ("python", "python3", "python.exe", "py", "sh", "bash")


def invokes(stage, scripts=DESTRUCTIVE_IF_INTERRUPTED):
    """True when this pipeline stage RUNS one of `scripts`, rather than merely naming it.

    `grep -n x tools/audit/mutate.py` names it; `python tools/audit/mutate.py x` runs it.
    Only the second can be left mid-mutation by a SIGPIPE, and refusing the first made this
    guard fire on an ordinary read within an hour of being installed.
    """
    for command in re.split(r"&&|" + chr(39) + r"\|\|" + chr(39) + r"|;", stage):
        words = command.strip().split()
        if not words:
            continue
        head = os.path.basename(words[0])
        if any(words[0].endswith(script) for script in scripts):
            return True
        # `python -m ruff check tools/audit/weigh.py` runs RUFF. With -m or -c the
        # interpreter runs THAT module and everything after is the module's argv, so the
        # script is data there - as much as it is to `grep`. Refusing a lint is how a guard
        # gets switched off.
        if "-m" in words or "-c" in words:
            continue
        if head in INTERPRETERS:
            # The interpreter runs the FIRST .py on the line; everything after it is that
            # script's own argv. `python tools/dev/safe.py check-script mutate.py` runs
            # safe.py. Matching any word would refuse that; matching the first non-flag
            # word would let `python -W ignore mutate.py` through, which is worse.
            script_run = next((w for w in words[1:] if w.endswith(".py")), None)
            if script_run and any(script_run.endswith(s) for s in scripts):
                return True
    return False


BYPASS = "DTENGINE_ALLOW_PIPE"


def piped_consumers(command):
    """The first word of each pipeline stage after a top-level `|`, quotes respected.

    A `|` inside a quoted string is data, not a pipe - `grep 'a|b' file` pipes into nothing.
    """
    stages = []
    current = []
    quote = None
    index = 0
    while index < len(command):
        char = command[index]
        if quote:
            if char == chr(92) and quote == chr(34):
                current.append(char)
                index += 1
                if index < len(command):
                    current.append(command[index])
                index += 1
                continue
            if char == quote:
                quote = None
            current.append(char)
        elif char in (chr(34), chr(39)):
            quote = char
            current.append(char)
        elif char == "|":
            # `||` is a control operator, not a pipe.
            if index + 1 < len(command) and command[index + 1] == "|":
                current.append(command[index:index + 2])
                index += 2
                continue
            stages.append("".join(current))
            current = []
        else:
            current.append(char)
        index += 1
    stages.append("".join(current))

    consumers = []
    for stage in stages[1:]:
        words = stage.strip().split()
        if words:
            consumers.append(os.path.basename(words[0]))
    return consumers


def segments(command):
    """The separate COMMANDS on a shell line, quotes respected.

    `;`, `&&` and `||` end a command; `|` does not - it connects stages inside one. Reading the
    whole line as a single pipeline made a redirected sweep followed by an unrelated `| grep`
    look like a sweep piped into grep, and the guard refused work that was already correct.
    """
    out = []
    current = []
    quote = None
    index = 0
    while index < len(command):
        char = command[index]
        if quote:
            if char == chr(92) and quote == chr(34):
                current.append(command[index:index + 2])
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
        elif char in ("&", "|") and index + 1 < len(command) and command[index + 1] == char:
            out.append("".join(current))
            current = []
            index += 2
            continue
        else:
            current.append(char)
        index += 1
    out.append("".join(current))
    return [s for s in (segment.strip() for segment in out) if s]


def verdict(command, background):
    """(deny?, reason). Separated from the I/O so it can be tested without a subprocess."""
    for segment in segments(command):
        deny, why = _verdict_for(segment, background)
        if deny:
            return deny, why
    return False, None


def _verdict_for(command, background):
    """One command - no `;`, `&&` or `||` inside it."""
    producer = command.split("|")[0]
    consumers = [c for c in piped_consumers(command) if c in BUFFERING]
    # THE CLASS IS "a sweep that can be killed", not "a sweep behind a pipe". The foreground
    # timeout is the other member, and it cost a restored file an hour after the pipe rule
    # went in. Both are refused here.
    #: `--list` prints the site table and `--selftest` runs the harness's own checks with no
    #: build, no JDK and no mutant ever written to disk - the battery itself runs the second one
    #: in the foreground. Refusing them is the shape that gets a guard switched off, and it did
    #: refuse one here within the hour of being wired to every tool.
    harmless = "--list" in command or "--selftest" in command
    if invokes(producer) and not background and not harmless:
        return True, (
            "BLOCKED BY PROJECT POLICY (.claude/hooks/guard_background_pipe.py).\n"
            "A mutation sweep must run in the BACKGROUND. The foreground has a ten-minute "
            "cap and kills with SIGTERM, which skips the `finally` that restores the "
            "module - leaving a risk file on disk carrying an injected fault. That has "
            "already happened here twice, once from a pipe and once from this timeout.\n\n"
            "Re-run it backgrounded, redirecting to a file:\n"
            "    <command> > out.txt 2>&1\n"
            "(`--list` only prints the site table and is allowed in the foreground.)\n")
    # Only NOW is a missing pipe uninteresting: the sweep rules above do not care
    # whether one is present, and putting this first is what made them unreachable.
    if not consumers:
        return False, None
    destructive = ([n for n in DESTRUCTIVE_IF_INTERRUPTED if n in producer]
                   if invokes(producer) else [])
    if destructive:
        return True, (
            "BLOCKED BY PROJECT POLICY (.claude/hooks/guard_background_pipe.py).\n"
            "%s is piped into %s. If that pipe closes early or the command is killed, the "
            "`finally` that restores the module never runs and a RISK MODULE IS LEFT ON DISK "
            "CARRYING AN INJECTED FAULT - hundreds of lines shorter, and indistinguishable in "
            "`git status` from an ordinary edit. That has already happened here once.\n\n"
            "Redirect to a file instead:\n"
            "    %s > out.txt 2>&1\n" % (destructive[0], ", ".join(consumers), destructive[0]))
    if background:
        return True, (
            "BLOCKED BY PROJECT POLICY (.claude/hooks/guard_background_pipe.py).\n"
            "A backgrounded command piped into %s produces NO output until it finishes - the "
            "consumer reads to EOF first - and the exit code you get back is the consumer's, "
            "not the command's. A command that never ran looks exactly like a command that "
            "passed.\n\n"
            "The harness already captures a backgrounded command's full output to a file, so "
            "the pipe buys nothing. Drop it, or redirect:\n"
            "    <command> > out.txt 2>&1\n" % ", ".join(consumers))
    return False, None


sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shellin                                    # noqa: E402  (path set above)

HOOK = "guard_background_pipe.py"


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
        deny, reason = verdict(command, bool(((payload.get("tool_input") or {})
                                       .get("run_in_background"))))
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
