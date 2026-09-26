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

TO RUN ONE ANYWAY: the owner sets `bundle_env.name("ALLOW_PIPE")` to 1 for that session.

WHICH SCRIPTS ARE DESTRUCTIVE IS THE PROJECT'S ANSWER, read from `destructive_scripts.txt`
beside this file (see `bundle_shell.destructive_scripts`). The constant below is only what a
bundle copied and never adapted falls back to, so it still guards something rather than nothing.

AND POWERSHELL BUFFERS TOO. `Select-Object -Last 70` is `tail -70`: it reads to the end before it
emits a line. `Select-Object -First` is `head`, and it stops the pipeline upstream. This hook knew
the POSIX names only, in a project whose primary shell is PowerShell - so the exact pipe it
exists for, spelled the way that shell spells it, walked through.
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

#: Consumers that either read to EOF before emitting anything, or close the pipe early.
BUFFERING = ("tail", "head", "less", "more", "sort", "uniq", "wc", "column", "tac")

#: THE SAME TWO BEHAVIOURS, SPELLED BY POWERSHELL. Compared case-insensitively, because
#: PowerShell is. `Select-Object` buffers only with `-Last`/`-First` - `Select-Object Name`
#: streams, and refusing that would be a guard firing on honest work.
BUFFERING_ALWAYS_PS = ("sort-object", "measure-object", "measure", "out-string", "group-object",
                       "group")
BUFFERING_WHEN_TRUNCATING_PS = ("select-object", "select")
_TRUNCATES = re.compile(r"(?:^|\s)-(?:last|first|l|f)\b", re.I)

#: Commands whose interruption leaves a source file on disk carrying an injected fault. For these
#: the pipe is refused whether or not the command was backgrounded, because SIGPIPE does not wait
#: to be asked. THE FALLBACK - the project's own list lives in `destructive_scripts.txt`.
DESTRUCTIVE_IF_INTERRUPTED = ("tools/audit/mutate.py", "tools/audit/weigh.py")
#: What actually starts a python program here. `sh`/`bash` are included because a script
#: run through them ends up executing the same driver.
INTERPRETERS = ("python", "python3", "python.exe", "py", "sh", "bash")

#: Adapted per project: see ADAPT.md.
ADAPT = ("DESTRUCTIVE_IF_INTERRUPTED",)


def destructive():
    """[(script, sub-command or None)] - the project's list, read at call time."""
    return bundle_shell.destructive_scripts(DESTRUCTIVE_IF_INTERRUPTED)


def _is(word, rest, script, sub):
    """`word` is `script`, and - when a damaging sub-command is named - `rest` starts with it."""
    return (word.strip(chr(34) + chr(39)).replace(chr(92), "/").endswith(script)
            and bundle_shell.sub_matches(rest, sub))


def invokes(stage, scripts=None):
    """True when this pipeline stage RUNS one of `scripts`, rather than merely naming it.

    `grep -n x tools/audit/mutate.py` names it; `python tools/audit/mutate.py x` runs it.
    Only the second can be left mid-mutation by a SIGPIPE, and refusing the first made this
    guard fire on an ordinary read within an hour of being installed.

    `scripts` is a list of paths or of (path, sub-command) pairs; the project's list by default.
    A sub-command narrows the rule to the verb that does the damage: `sweep.py run` puts mutants
    on disk and `sweep.py status` reads a ledger.
    """
    table = destructive() if scripts is None else [
        s if isinstance(s, tuple) else (s, None) for s in scripts]
    for command in bundle_shell.split_commands(stage):
        words = command.strip().split()
        if not words:
            continue
        head = os.path.basename(words[0].strip(chr(34) + chr(39))).lower()
        if any(_is(words[0], words[1:], script, sub) for script, sub in table):
            return True
        # `python -m ruff check tools/audit/weigh.py` runs RUFF. With -m or -c the
        # interpreter runs THAT module and everything after is the module's argv, so the
        # script is data there - as much as it is to `grep`. Refusing a lint is how a guard
        # gets switched off.
        if "-m" in words or "-c" in words:
            continue
        if head in INTERPRETERS or head.endswith(".exe") and head[:-4] in INTERPRETERS:
            # The interpreter runs the FIRST .py on the line; everything after it is that
            # script's own argv. `python tools/dev/safe.py check-script mutate.py` runs
            # safe.py. Matching any word would refuse that; matching the first non-flag
            # word would let `python -W ignore mutate.py` through, which is worse.
            at = next((i for i, w in enumerate(words) if i and w.endswith(".py")), None)
            if at is not None and any(_is(words[at], words[at + 1:], script, sub)
                                      for script, sub in table):
                return True
    return False


BYPASS = bundle_env.name("ALLOW_PIPE")


def piped_stages(command):
    """The text of each pipeline stage after a top-level `|`, quotes respected."""
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

    return [stage for stage in stages[1:] if stage.strip()]


def piped_consumers(command):
    """The first word of each pipeline stage after a top-level `|`, quotes respected.

    A `|` inside a quoted string is data, not a pipe - `grep 'a|b' file` pipes into nothing.
    """
    return [os.path.basename(stage.strip().split()[0]) for stage in piped_stages(command)]


def buffers(stage):
    """Does this pipeline stage read to EOF before emitting, or close the pipe early?"""
    words = stage.strip().split()
    if not words:
        return False
    name = os.path.basename(words[0])
    if name in BUFFERING:
        return True
    lowered = name.lower()
    if lowered in BUFFERING_ALWAYS_PS:
        return True
    return lowered in BUFFERING_WHEN_TRUNCATING_PS and bool(_TRUNCATES.search(stage))


def segments(command):
    """The separate COMMANDS on a shell line - `bundle_shell.split_commands`, the one splitter.

    `;`, `&&`, `||` and a NEWLINE end a command; `|` does not - it connects stages inside one.
    Reading the whole line as a single pipeline made a redirected sweep followed by an unrelated
    `| grep` look like a sweep piped into grep, and the guard refused work that was already
    correct. This file's own copy of the splitter did not split on a newline, so a sweep on the
    SECOND line of a command was read as an argument of the `cd` on the first.
    """
    return bundle_shell.split_commands(command)


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
    consumers = [os.path.basename(stage.strip().split()[0])
                 for stage in piped_stages(command) if buffers(stage)]
    # THE CLASS IS "a sweep that can be killed", not "a sweep behind a pipe". The foreground
    # timeout is the other member, and it cost a restored file an hour after the pipe rule
    # went in. Both are refused here.
    if invokes(producer) and not background and "--list" not in command:
        return True, (
            bundle_shell.policy(__file__) + "\n"
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
    named = ([script for script, _sub in destructive() if script in producer.replace(chr(92), "/")]
             if invokes(producer) else [])
    if named:
        return True, (
            bundle_shell.policy(__file__) + "\n"
            "%s is piped into %s. If that pipe closes early or the command is killed, the "
            "`finally` that restores the module never runs and a RISK MODULE IS LEFT ON DISK "
            "CARRYING AN INJECTED FAULT - hundreds of lines shorter, and indistinguishable in "
            "`git status` from an ordinary edit. That has already happened here once.\n\n"
            "Redirect to a file instead:\n"
            "    %s > out.txt 2>&1\n" % (named[0], ", ".join(consumers), named[0]))
    if background:
        return True, (
            bundle_shell.policy(__file__) + "\n"
            "A backgrounded command piped into %s produces NO output until it finishes - the "
            "consumer reads to EOF first - and the exit code you get back is the consumer's, "
            "not the command's. A command that never ran looks exactly like a command that "
            "passed.\n\n"
            "The harness already captures a backgrounded command's full output to a file, so "
            "the pipe buys nothing. Drop it, or redirect:\n"
            "    <command> > out.txt 2>&1\n" % ", ".join(consumers))
    return False, None


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        sys.exit(0)                      # never break a turn on a parse failure
    # THE SHAPE, NOT THE NAME - see guard_heredoc.py. Eight hooks were matched on "Bash" while
    # a PowerShell tool was offered alongside it, and four of them, this one included, also
    # self-exempted. A backgrounded pipe is a backgrounded pipe whichever tool carries it.
    # THE SHAPE, FOUND AT ANY DEPTH. This read one `command` key at one level, which a
    # BATCHING tool walks straight past by nesting its inputs - the same hole as
    # `tool_name != "Bash"`, one level down, inside the fix for it. `bundle_shell`
    # walks the payload to a bounded depth, takes `command` OR `script` wherever it
    # sits, and reports a command it could not READ as trouble rather than as none.
    if not bundle_shell.commands(payload):
        sys.exit(0)
    if bundle_env.allowed("ALLOW_PIPE"):
        sys.exit(0)
    # THE SAME TEXT THE GATE LOOKED AT - see guard_heredoc.py. `run_in_background` is
    # still read from the top-level input, because it is a property of the CALL rather
    # than of any one command inside it.
    tool_input = payload.get("tool_input") or {}
    deny, reason = verdict(bundle_shell.text(payload),
                           bool(tool_input.get("run_in_background")))
    if deny:
        json.dump({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }}, sys.stdout)
    sys.exit(0)


if __name__ == "__main__":
    main()
