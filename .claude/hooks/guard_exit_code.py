#!/usr/bin/env python3
"""A REMEDY'S EXIT CODE ANSWERS ITS OWN QUESTION, NOT YOURS.

WHAT THIS COST, and it was paid again on the day this was written.

`$?` after a pipeline is the LAST stage's status, not the producer's. So

    python tools/guard/liveness_check.py . | head -5; echo "exit=$?"

printed `exit=0` about a checker that had just exited 1 with seven findings. That is the same
shape as the thirty-three hours this project lost: a map rebuild wrote its 10.7 MB output and
exited 1 - because the exit code reported the suite it had run UNDER COVERAGE, not whether a
map was produced - and the runner read that as a failed remedy, discarded 49 minutes of work,
and sat for a day and a half having decided nothing.

A COMMAND THAT DID NOT RUN LOOKS EXACTLY LIKE A COMMAND THAT PASSED. `grep -c` exiting 1 on
zero matches has short-circuited an `&&` chain here so that a later step never ran at all.

WHAT IT REFUSES:

  * reading `$?` after a pipeline whose last stage is a filter - `head`, `tail`, `grep`,
    `sort`, `wc` and friends. The producer's status is in `${PIPESTATUS[0]}`, and asking for
    that is allowed.
  * `$LASTEXITCODE` straight after a PowerShell pipeline into the same kind of filter.

WHAT IT DOES NOT REFUSE: the pipeline itself. Piping into `head` to read a long output is
ordinary and useful; what is not ordinary is believing the number that comes back afterwards.

TO READ IT ANYWAY: the owner sets CTRMAP_ALLOW_PIPED_STATUS=1.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hook_env                                   # noqa: E402
import shellin                                    # noqa: E402  (path set above)

HOOK = "guard_exit_code.py"
BYPASS = hook_env.name("ALLOW_PIPED_STATUS")

#: Consumers whose own status is what `$?` will report - and which almost always succeed.
FILTERS = ("head", "tail", "grep", "egrep", "fgrep", "sort", "uniq", "wc", "cut", "sed",
           "awk", "tr", "column", "tac", "less", "more", "jq", "select-object",
           "where-object", "foreach-object", "out-string", "format-table", "measure-object")

#: `$?`, `$LASTEXITCODE`. `${PIPESTATUS[0]}` and `$PIPESTATUS` are the RIGHT question and are
#: deliberately not matched - a guard that refuses the correct spelling teaches nothing.
_STATUS = re.compile(r"\$\?|\$LASTEXITCODE\b", re.I)
_RIGHT = re.compile(r"PIPESTATUS", re.I)


def _statements(command):
    """The separate statements on a line, in order: `;`, `&&`, `||` end one, `|` does not."""
    out = []
    current = []
    quote = None
    index = 0
    while index < len(command):
        char = command[index]
        if quote:
            if char == quote:
                quote = None
            current.append(char)
        elif char in ('"', chr(39)):
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
        elif char == "\n":
            out.append("".join(current))
            current = []
        else:
            current.append(char)
        index += 1
    out.append("".join(current))
    return [s for s in (piece.strip() for piece in out) if s]


def last_filter(statement):
    """The filter a pipeline ends in, or None when it is not a pipeline into one."""
    stages = _split_pipes(statement)
    if len(stages) < 2:
        return None
    words = stages[-1].strip().split()
    if not words:
        return None
    head = words[0].rsplit("/", 1)[-1].rsplit(chr(92), 1)[-1].lower()
    if head.endswith(".exe"):
        head = head[:-4]
    return head if head in FILTERS else None


def _split_pipes(statement):
    stages = []
    current = []
    quote = None
    index = 0
    while index < len(statement):
        char = statement[index]
        if quote:
            if char == quote:
                quote = None
            current.append(char)
        elif char in ('"', chr(39)):
            quote = char
            current.append(char)
        elif char == "|":
            stages.append("".join(current))
            current = []
        else:
            current.append(char)
        index += 1
    stages.append("".join(current))
    return stages


def verdict(command):
    """(deny, reason). A `$?` read in a statement that FOLLOWS a piped pipeline."""
    if _RIGHT.search(command):
        return False, None
    pending = None
    for statement in _statements(command):
        if _STATUS.search(statement) and pending:
            return True, refusal(pending, statement)
        found = last_filter(statement)
        pending = found or None
    return False, None


def refusal(filter_name, statement):
    return (
        "BLOCKED BY PROJECT POLICY (.claude/hooks/%s).\n"
        "This reads an exit status that belongs to `%s`, not to the command you care about:\n"
        "    %s\n\n"
        "A REMEDY'S EXIT CODE ANSWERS ITS OWN QUESTION, NOT YOURS. `$?` after a pipeline is "
        "the LAST stage's status, and a filter almost always succeeds - so this prints a 0 "
        "about a command that failed. It did, here, about a checker that had just exited 1 "
        "with seven findings.\n\n"
        "The same shape cost this project thirty-three hours: a map rebuild wrote its 10.7 MB "
        "output and exited 1 because the code reported the suite it ran UNDER, not whether a "
        "map was produced, and the runner discarded 49 minutes of work on the strength of "
        "it.\n\n"
        "Ask the right question instead:\n"
        "    <command> | head -5; echo \"exit=${PIPESTATUS[0]}\"\n"
        "or redirect to a file and read the file, which keeps the status and the whole "
        "output.\n" % (HOOK, filter_name, statement.strip()[:120]))


def decide(payload):
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
    import json
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
