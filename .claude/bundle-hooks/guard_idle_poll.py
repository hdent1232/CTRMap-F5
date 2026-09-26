#!/usr/bin/env python3
"""Refuse a command whose first act is to SLEEP and wait. The harness already notifies.

WHY THIS EXISTS, measured from one campaign's own log rather than argued.

The unattended sweep ran for 4.7 days - 113 hours of wall clock - to do about 29 hours of
sweeping. The largest line was the runner waiting on an over-broad dirty check, and that is now
a narrower predicate. THE SECOND LARGEST WAS ME, sitting in `sleep 115; check the output file`
loops, fifteen or more times in a single session, doing nothing at all while a background task
ran and while thousands of surviving mutants sat unworked.

IT BUYS NOTHING. A backgrounded command's completion arrives as a task notification with its own
exit status; the output is already captured to a file. Sleeping to look at it earlier does not
make it finish earlier - it only makes the interval between "the machine is free" and "somebody
noticed" longer, which is the exact quantity this project spent a day measuring.

WHAT TO DO INSTEAD. Start the long thing in the background and do OTHER WORK in the same turn:
read the next module's survivors, write the next test, run a targeted file. If there is genuinely
nothing to do until it lands, end the turn - the notification is what wakes it back up, and that
costs zero.

THE ONE THING A SLEEP IS FOR is giving a process a moment to come up before probing it - a
server binding a port, a detached runner writing its first log line. Those are seconds, not
minutes, so a short sleep is allowed and a long one is not.

TO WAIT ANYWAY: the owner sets `bundle_env.name("ALLOW_SLEEP")` to 1 for that session.

A SLEEP IS SPELLED FOUR WAYS HERE AND THIS KNEW ONE. The session's primary shell is PowerShell,
whose sleep is `Start-Sleep -Seconds 115`; `cmd` has `timeout /t 115`; and `python -c` has
`time.sleep(115)`. Every one of them is the same act - waiting on purpose instead of working -
and `sleep 115` was the only spelling this guard could see.

AND A LOOP THAT SLEEPS IS A POLL WHATEVER ITS SLEEP. `until grep -q TOTAL out.txt; do sleep 30;
done` passed this guard - thirty seconds is under the bound - and one ran for TWENTY-ONE HOURS
on the source project against a file that had finished twenty hours earlier and would never
contain the string: about 2,500 wakeups on a condition that could not become true. A wait needs
a deadline and a way to fail. A `for` loop over a fixed range has both; `while` and `until` have
neither, so a `while`/`until` loop that sleeps is refused at any duration.
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

#: A pause this long is not "let it come up", it is a poll. The longest honest wait measured in
#: this project is a server bind at about four seconds.
LONGEST_HONEST = 30

BYPASS = bundle_env.name("ALLOW_SLEEP")

#: `sleep 115`, `sleep 115;`, `sleep 1m`. A bare number is seconds. Matched against ONE
#: segment, never against the whole command string - see `segments`.
SLEEP = re.compile(r"^sleep\s+([0-9]+(?:\.[0-9]+)?)\s*([smh]?)$")

FACTOR = {"": 1, "s": 1, "m": 60, "h": 3600}

#: THE OTHER SPELLINGS, each returning (amount, unit). Matched against one command, like SLEEP.
#: `Start-Sleep 5`, `Start-Sleep -Seconds 5`, `Start-Sleep -s 5`, `Start-Sleep -Milliseconds
#: 5000`, `Start-Sleep -m 5000`; `timeout /t 5` (cmd - NOT coreutils `timeout 5 cmd`, which
#: bounds a command rather than waiting); `ping -n 6 127.0.0.1`, the Windows sleep idiom.
START_SLEEP = re.compile(
    r"^Start-Sleep(?:\s+-(?P<unit>Seconds|Milliseconds|s|m|ms))?\s+(?P<n>[0-9]+(?:\.[0-9]+)?)\s*$",
    re.I)
CMD_TIMEOUT = re.compile(r"^timeout(?:\.exe)?\s+/t\s+(?P<n>[0-9]+)\b", re.I)
PING_WAIT = re.compile(r"^ping(?:\.exe)?\s+-n\s+(?P<n>[0-9]+)\s+(?:127\.0\.0\.1|localhost)\b",
                       re.I)
#: `time.sleep(115)` inside an inline program - the python spelling.
PY_SLEEP = re.compile(r"\btime\.sleep\(\s*([0-9]+(?:\.[0-9]+)?)\s*\)")

#: Any sleep at all, for the loop rule - the duration does not matter inside a poll loop.
ANY_SLEEP = re.compile(r"(?:^|[\s;{(&|])(?:sleep\s+[0-9]|Start-Sleep\b|timeout(?:\.exe)?\s+/t\b)"
                       r"|\btime\.sleep\(", re.I)
#: An UNBOUNDED loop: `while`/`until` in bash, `while (...)` and `do { } while|until` in
#: PowerShell. `for` is not here - a fixed range has a deadline built in.
UNBOUNDED_LOOP = re.compile(r"(?:^|[\s;{(&|])(?:while|until)\b|\bdo\s*\{", re.I)

#: A leading `cd somewhere` or `VAR=value` is a PREFIX, not work. Treating them as work is what
#: made every command in this project exempt: they all start `cd "..." && ...`.
PREFIX = re.compile(r"^(cd\s+\S+|[A-Za-z_][A-Za-z_0-9]*=\S*)\s+")

#: AND A SEGMENT THAT IS ONLY A PREFIX IS NOT WORK EITHER. `&&` splits `cd /tmp && sleep 5m`
#: into two segments, so stripping a leading prefix never reached the `cd` - it became segment
#: zero and the sleep after it read as "preceded by work". The same mistake one level in.
ONLY_A_PREFIX = re.compile(r"^(cd\s+\S+|[A-Za-z_][A-Za-z_0-9]*=\S*)$")


def split_outside_quotes(command):
    """The shell line cut at `&&`, `||`, `;` and `|`, ignoring any inside quotes.

    A separator inside quotes is not a separator: `echo "a; b"` is one command. Tracked with the
    quote state rather than with a cleverer regex, because a regex that has to know about
    quoting is the one that is wrong on the next spelling.
    """
    out, current, quote = [], [], None
    index = 0
    text = command or ""
    while index < len(text):
        char = text[index]
        if quote:
            current.append(char)
            if char == quote:
                quote = None
            index += 1
            continue
        if char in "'" + chr(34):
            quote = char
            current.append(char)
            index += 1
            continue
        hit = next((s for s in ("&&", "||", ";", "|") if text.startswith(s, index)), None)
        if hit:
            out.append("".join(current))
            current = []
            index += len(hit)
            continue
        current.append(char)
        index += 1
    out.append("".join(current))
    return out


def segments(command):
    """The individual commands in a shell line, each stripped of a leading `cd` or env prefix.

    COMMANDS FIRST, THROUGH THE ONE SPLITTER, and only then the pipe stages. This file's own
    splitter did not treat a NEWLINE as a separator, so a multi-line call - `cd x`, newline,
    `Start-Sleep 300`, newline, `Get-Content out.txt` - was one command beginning `cd`, and the
    anchored patterns below never saw the sleep.
    """
    out = []
    for command in bundle_shell.split_commands(command):
        for piece in split_outside_quotes(command):
            piece = piece.strip()
            while PREFIX.match(piece):
                piece = PREFIX.sub("", piece, count=1).strip()
            if piece and not ONLY_A_PREFIX.match(piece):
                out.append(piece)
    return out


def waits(piece):
    """Seconds this ONE command sleeps, or None when it is not a sleep in any spelling."""
    found = SLEEP.match(piece)
    if found:
        return float(found.group(1)) * FACTOR.get(found.group(2), 1)
    found = START_SLEEP.match(piece)
    if found:
        unit = (found.group("unit") or "Seconds").lower()
        scale = 0.001 if unit in ("milliseconds", "ms", "m") else 1.0
        return float(found.group("n")) * scale
    found = CMD_TIMEOUT.match(piece)
    if found:
        return float(found.group("n"))
    found = PING_WAIT.match(piece)
    if found:
        return max(0.0, float(found.group("n")) - 1)
    if re.match(r"^(?:python[0-9.]*(?:\.exe)?|py)\b.*\s-c\s", piece, re.I | re.S):
        slept = [float(n) for n in PY_SLEEP.findall(piece)]
        if slept:
            return sum(slept)
    return None


def _unquoted(text):
    """`text` with the contents of quoted runs blanked, so a quoted WORD is not a keyword."""
    out, quote = [], None
    for char in text or "":
        if quote:
            out.append(" " if char != quote else char)
            if char == quote:
                quote = None
        elif char in ("'", chr(34)):
            quote = char
            out.append(char)
        else:
            out.append(char)
    return "".join(out)


def poll_loop(command):
    """True when the text is an UNBOUNDED loop that sleeps - a wait with no deadline."""
    bare = _unquoted(command)
    if UNBOUNDED_LOOP.search(bare) and ANY_SLEEP.search(bare):
        return True
    # The same shape inside an inline python program: `while ...: time.sleep(5)`.
    for match in re.finditer(r"\s-c\s+(['" + chr(34) + r"])(.*?)\1", command or "", re.S):
        body = match.group(2)
        if re.search(r"\bwhile\b", body) and PY_SLEEP.search(body):
            return True
    return False


def seconds(command):
    """How long this command waits before something looks at a result, or None.

    ASKED OF THE SEGMENTS, NOT OF THE STRING. This was `SLEEP.match(command)` - anchored at the
    start of the raw text - so `cd /tmp && sleep 300; cat x` matched nothing and the guard was
    off for every command in a project whose every command begins with `cd`. Measured: four of
    five real spellings walked through.

    THE RULE THE DOCSTRING ALWAYS MEANT. `python x.py; sleep 60` runs the work and then pauses,
    which is pointless but not a poll; `sleep 60; check` is the shape that waited for hours. That
    is about position among COMMANDS, not among characters:

        a long sleep with anything AFTER it         a poll - something is waiting on it
        a long sleep with no real work BEFORE it    a pure wait - end the turn instead
        a long sleep LAST, after real work          allowed, exactly as before
    """
    pieces = segments(command)
    for index, piece in enumerate(pieces):
        waited = waits(piece)
        if waited is None:
            continue
        followed = index < len(pieces) - 1
        preceded_by_work = index > 0
        if followed or not preceded_by_work:
            return waited
    return None


def verdict(command):
    if poll_loop(command):
        return True, (
            "A `while`/`until` loop that sleeps is a POLL WITH NO DEADLINE, whatever the sleep.\n\n"
            "MEASURED: `until grep -q TOTAL <file>; do sleep 30; done` ran for twenty-one hours "
            "against a file that had finished twenty hours earlier - it had timed out, exited 0, "
            "and would never contain the string. About 2,500 wakeups on a condition that could "
            "not become true.\n\n"
            "The harness notifies you when a background task finishes. If you must wait on a "
            "condition, bound it: a `for` over a fixed range, with the failure reported when the "
            "range runs out.")
    waited = seconds(command)
    if waited is None or waited <= LONGEST_HONEST:
        return False, None
    return True, (
        "A %g-second sleep at the start of a command is a POLL, and the harness already tells "
        "you when a background task finishes - with its exit status, and its output already in "
        "a file.\n\n"
        "MEASURED: one sweep took 113 hours of wall clock to do 29 hours of work, and this is "
        "the second largest line in that gap. Fifteen of these in one session is fifteen "
        "stretches of doing nothing while thousands of surviving mutants sat unworked.\n\n"
        "Do other work in the same turn - the next module's survivors, the next test, a "
        "targeted run. If there is genuinely nothing to do until it lands, END THE TURN; the "
        "notification is what wakes it up and it costs nothing.\n\n"
        "A short pause to let a process come up is still allowed (up to %ds)."
        % (waited, LONGEST_HONEST))


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        sys.exit(0)                      # never break a turn on a parse failure
    # THE SHAPE, NOT THE NAME - see guard_heredoc.py. A sleep-poll issued through PowerShell is
    # the same sleep-poll, and this guard could not see one.
    # THE SHAPE, FOUND AT ANY DEPTH. This read one `command` key at one level, which a
    # BATCHING tool walks straight past by nesting its inputs - the same hole as
    # `tool_name != "Bash"`, one level down, inside the fix for it. `bundle_shell`
    # walks the payload to a bounded depth, takes `command` OR `script` wherever it
    # sits, and reports a command it could not READ as trouble rather than as none.
    if not bundle_shell.commands(payload):
        sys.exit(0)
    if bundle_env.allowed("ALLOW_SLEEP"):
        sys.exit(0)
    # THE SAME TEXT THE GATE LOOKED AT - see guard_heredoc.py. A gate and a rule
    # reading different things is one guard with a hole in the middle of it.
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
