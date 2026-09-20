#!/usr/bin/env python3
"""Cap multi-agent fan-out in this project. Cap, not block.

WHY THIS EXISTS. On 2026-09-02, fixing an audit's 33 findings burned ~5.3M
subagent tokens in about half an hour - close to 90% of a 5-hour allowance and
37% of a weekly one - and hit the session limit twice. What it cost:

  batch one (fix)      5 agents   1.61M tokens   22 findings fixed
  VERIFICATION        22 agents   2.91M tokens   21 verdicts, 7 real gaps
  batch two attempt 1  3 agents   0.73M tokens   NOTHING - all died on the limit
  batch two attempt 2  3 agents   ~0.5M tokens   stopped mid-flight

Verification cost more than the fixing did, because 22 agents each rebuilt the
same cluster context from scratch to answer one question apiece. Two fix
workflows also ran concurrently with it, so the limit killed both.

WHAT ACTUALLY FIXED IT was not doing less work - it was doing it without
duplication:
  - verify per CLUSTER, not per finding: one agent already holds the context
    for five to seven of them;
  - prove guard strength MECHANICALLY (tools/../mutate.py reverse-applies one
    hunk of a fix, rebuilds, re-runs the suite). Stronger evidence than an
    agent's opinion, and it costs builds instead of tokens. It found a real
    hole that the 2.9M-token agent pass had rated "fixed";
  - one fix workflow at a time, never two;
  - mechanical work in the main thread with no agent at all.

So this hook caps the shapes that burn, and leaves the shapes that work alone.
A single workflow with a handful of agents passes. A 22-agent fan-out, or a
third concurrent workflow, does not.

(A separate project, DT-Engine, lost 96% of a weekly allowance to the same
pattern on 2026-08-17: 26 workflow runs, ~97M subagent tokens, ~24M of it
returning nothing because whole runs died mid-flight. Different codebase, same
failure. That incident is where this hook's design came from.)

AND IT HAPPENED A THIRD TIME, THROUGH THIS FILE, on 2026-09-14: 3 workflows,
791 agents, 111.9M subagent tokens - a full week of a metered plan - while this
hook was installed, wired and running. It read fan-out width from the launch
ARGUMENTS; all three launches passed a plain string there and kept their fan-out
in the script body, so width measured 0 against a cap of 6, three times. An
unmeasurable quantity was being treated as a small one. That is the general
lesson and it is now the rule here: width comes from the script text, and a
launch whose width cannot be read is REFUSED rather than assumed harmless.

TO LIFT IT for a session the owner has decided is worth it:
    CTRMAP_AGENT_CAP=20  CTRMAP_WORKFLOW_CAP=10  CTRMAP_WIDTH_CAP=12
"""
import json
import os
import re
import sys
import time

AGENT_CAP = int(os.environ.get("CTRMAP_AGENT_CAP", "10"))
WORKFLOW_CAP = int(os.environ.get("CTRMAP_WORKFLOW_CAP", "5"))
WIDTH_CAP = int(os.environ.get("CTRMAP_WIDTH_CAP", "6"))
WINDOW = 6 * 3600
STATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".fanout-count")


#: `agent(` call sites, array literals a fan-out maps over, and Array.from lengths.
_AGENT_CALL = re.compile(r"\bagent\s*\(")
_ARRAY_FROM = re.compile(r"Array\.from\s*\(\s*\{\s*length\s*:\s*([0-9]+)")
_CONST_LIST = re.compile(r"\b(?:const|let|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*\[")
_DECLARED = re.compile(r"\bagents\s*:\s*([0-9]+)")
_LOOPS = re.compile(r"\b(while|for)\s*\(")


def _literal_list_len(text, start):
    """How many top-level elements the array literal starting at `start` holds."""
    depth = 0
    items = 1
    i = start
    while i < len(text):
        ch = text[i]
        if ch in "[{(":
            depth += 1
        elif ch in "]})":
            depth -= 1
            if depth == 0:
                return items
        elif ch == "," and depth == 1:
            items += 1
        elif ch in "'\"`":
            quote = ch
            i += 1
            while i < len(text) and text[i] != quote:
                i += 2 if text[i] == chr(92) else 1
        i += 1
    return None


def script_width(tool_input):
    """(width, how) for a Workflow launch. width is None when it cannot be read.

    Reads the SCRIPT, because that is where the fan-out actually lives. A launch
    that declares `agents: <n>` in its meta is taken at its word only if the script
    text does not imply more - you may over-declare, never under-declare.
    """
    script = tool_input.get("script")
    if not script:
        path = tool_input.get("scriptPath")
        if path:
            try:
                with open(path, encoding="utf-8", errors="replace") as fh:
                    script = fh.read()
            except OSError as exc:
                return None, "its scriptPath could not be read (%s)" % exc
    if not script:
        # a saved workflow by name, or nothing to read: fall back to the args list
        a = tool_input.get("args")
        if isinstance(a, list):
            return len(a), "the args list"
        if isinstance(a, dict):
            for key in ("items", "clusters", "findings"):
                if isinstance(a.get(key), list):
                    return len(a[key]), "args." + key
        return None, "there is no script to read and args carries no list"

    implied = 0
    why = []
    for found in _ARRAY_FROM.finditer(script):
        implied = max(implied, int(found.group(1)))
        why.append("Array.from length %s" % found.group(1))
    for found in _CONST_LIST.finditer(script):
        size = _literal_list_len(script, found.end() - 1)
        if size and size > 1:
            implied = max(implied, size)
            why.append("%s holds %d" % (found.group(1), size))
    calls = len(_AGENT_CALL.findall(script))
    if calls > implied:
        implied = calls
        why.append("%d agent() call site(s)" % calls)

    #: A LOOP AROUND A FAN-OUT MULTIPLIES IT, and the width above is one pass. The
    #: three runs that burned the week each looped their critic up to three times.
    looped = bool(_LOOPS.search(script)) and calls > 0

    declared = _DECLARED.search(script)
    if declared:
        n = int(declared.group(1))
        if n < implied:
            return implied, ("it declares agents: %d but the script implies at least "
                             "%d (%s)" % (n, implied, "; ".join(why[:4])))
        return n, "its declared agents: %d" % n

    if implied:
        return (implied * 3 if looped else implied), (
            "the script implies %d agent(s) (%s)%s" % (
                implied, "; ".join(why[:4]),
                ", and a loop around them can repeat that" if looped else ""))
    return None, "no declared width, no fan-out list and no agent() call found"


#: WHAT A FAN-OUT LOOKS LIKE, rather than what it is called. This compared `tool_name` to the
#: two literals "Workflow" and "Agent". The seven guards beside this one compared it to the
#: literal "Bash", and the session's `PowerShell` tool ran the same shell past every one of
#: them without anybody bypassing anything. A cap that 791 agents have already walked through
#: is the last one that should depend on a spelling, so a call is a fan-out when its NAME says
#: so or when its INPUT carries the things only a spawn carries.
FANOUT_NAMES = ("workflow", "agent", "task", "fleet", "swarm", "subagent")
FANOUT_KEYS = ("subagent_type", "agents", "resumeFromRunId", "scriptPath")


#: Tools that MANAGE work already running rather than start any. `TaskStop` carries "task" and
#: was classified as a fan-out of unreadable width, so the hook refused the command that STOPS
#: a run - it cost ten minutes and would have cost two hours of a replant that had to be
#: restarted anyway. A guard that refuses the brakes is one that gets switched off.
NOT_A_SPAWN = ("stop", "kill", "cancel", "abort", "output", "list", "status", "read", "wait")


#: WHAT ACTUALLY FAILED, measured in DT Engine's own documents on 2026-09-20 rather than
#: argued. Eleven agent audit rounds produced 476 finding headlines and 1.8 MB of prose over
#: 41 distinct code locations. Rounds 11 and 13 found ZERO locations the earlier rounds had
#: not; rounds 8, 10 and 12 found one each. Six files were named by ten or eleven separate
#: rounds - every round rebuilding the same context from scratch.
#:
#: The width cap does not touch the part that broke. `docs/layer5-integration-items.md` opens:
#: "Each agent owned a disjoint file set. These are the changes they identified but COULD NOT
#: MAKE, because the change belongs in another agent's file." One agent corrected a check's
#: output and renamed its keys; the consumer was another agent's file, so "the browser still
#: receives no veto block, so none of the corrected check-8 output reaches the operator" - the
#: fix was made and never reached the user. Another entry records two agents fixing the same
#: thing concurrently, caught by luck.
#:
#: SIX AGENTS OWNING DISJOINT FILES ACROSS A SHARED CONTRACT IS THE SAME DEFECT AS SIXTY. The
#: cap passes it. Unlimited tokens make it worse, not better: more agents, more seams, more
#: integration file. So parallel WRITING is refused on its own terms, at any width.
#:
#: Reading in parallel is not this. A survey that returns findings composes fine - it was
#: merely wasteful. What does not compose is agents editing a codebase at the same time.
_EDITS = re.compile(r"isolation\s*:\s*[\"']worktree[\"']")
_EDIT_WORDS = re.compile(
    r"\b(fix|edit|change|apply|refactor|implement|rewrite|patch|migrate|update|modify|"
    r"repair|convert|port)\b", re.I)
#: a fan-out whose ITEMS are source files is one agent per file - the disjoint-ownership shape
_PER_FILE = re.compile(r"[\"'][\w./-]+\.(?:py|java|ts|tsx|js|jsx|go|rs|c|cpp|h|cs|rb|ps1)"
                       r"[\"']\s*,")


def parallel_edit(script, width):
    """Why this fan-out has agents editing at the same time, or None.

    Two signals, both from the script itself: `isolation: 'worktree'`, which the workflow API
    documents as being for agents that mutate files in parallel, and a fan-out over a list of
    SOURCE FILES whose prompt carries an edit verb - one agent per file, which is exactly the
    ownership split that produced an integration file instead of a working change.
    """
    if width is not None and width <= 1:
        return None
    if _EDITS.search(script):
        return "it asks for a worktree per agent, which is what parallel file mutation needs"
    if _PER_FILE.search(script) and _EDIT_WORDS.search(script):
        return ("it fans out over a list of SOURCE FILES and its prompts carry an edit verb - "
                "one agent per file, each blind to the seams it shares with the others")
    return None


def spawns_workers(tool, tool_input):
    """(is a fan-out, is it workflow-shaped). A spawn nobody can classify counts as one."""
    name = (tool or "").lower()
    if any(word in name for word in NOT_A_SPAWN):
        return False, False
    by_name = any(word in name for word in FANOUT_NAMES)
    by_shape = isinstance(tool_input, dict) and any(k in tool_input for k in FANOUT_KEYS)
    if not (by_name or by_shape):
        return False, False
    #: a workflow spawns a whole script's worth; an agent spawns one. When it is not clear
    #: which, treat it as the workflow - the wider cap is the one that was breached.
    single = "agent" in name or (isinstance(tool_input, dict)
                                 and "subagent_type" in tool_input
                                 and "script" not in tool_input
                                 and "scriptPath" not in tool_input)
    return True, not single


class Refuse(Exception):
    """A refusal carried out of the decision rather than written straight to stdout.

    `deny()` used to print and `sys.exit(0)`, which is why this guard could only ever be the
    whole process. The dispatcher runs every guard in ONE process, so a refusal has to be a
    value it can return - otherwise the first guard to refuse would take the others with it.
    """

    def __init__(self, reason):
        Exception.__init__(self, reason)
        self.reason = reason


def deny(reason):
    raise Refuse(reason)


def load():
    """(agents, workflows) used in the current window."""
    try:
        with open(STATE) as fh:
            stamp, agents, flows = fh.read().split(",")
        if time.time() - float(stamp) > WINDOW:
            return 0, 0
        return int(agents), int(flows)
    except Exception:
        return 0, 0


def bump(agents, flows, kind):
    # FAIL CLOSED. A cap nobody can record is not a cap: with this file left
    # read-only in the project it was ported from, ten agents ran against a cap
    # of four, because a swallowed write means the count reads 0 forever. If the
    # spawn cannot be counted, the spawn does not happen.
    try:
        with open(STATE, "w") as fh:
            fh.write("%f,%d,%d" % (time.time(), agents, flows))
    except Exception as exc:
        deny(
            "BLOCKED BY PROJECT POLICY: the fan-out counter at\n%s\ncannot be "
            "written (%s: %s).\n\nThis cap is what stands between this project "
            "and another 2026-09-02, and an unrecordable spawn is an uncapped "
            "one. It refuses rather than guessing.\n\nMake the file writable, or "
            "do the work in the main thread." % (STATE, type(exc).__name__, exc)
        )


def decide(payload):
    """(deny, reason) for any tool call. Records the spawn only when it is allowed.

    The counter is bumped here and not in `main` so that the shared dispatcher - which runs
    every guard in one process - keeps the window honest for a spawning tool this file has
    never heard of.
    """
    try:

        tool = str(payload.get("tool_name") or "")
        tool_input = payload.get("tool_input") or {}
        fanout, wide = spawns_workers(tool, tool_input)
        if not fanout:
            return False, None
        agents, flows = load()

        if wide:
            # WIDTH, not just count. Agents a workflow spawns never pass through the
            # Agent tool, so the per-agent cap below cannot see them: the 22-verifier
            # run that burned this project would have read as "1 workflow, 0 agents".
            #
            # IT USED TO LOOK ONLY AT THE LAUNCH ARGUMENTS, on the reasoning that
            # "every script here fans out over a list in args". On 2026-09-14 three
            # launches passed a plain STRING as args and hardcoded their fan-out in the
            # script body - SLICES, LENSES, GAPS, SUITE_BATCHES - so this computed a
            # width of 0 against a cap of 6, three times, and let 791 agents and 111.9M
            # subagent tokens through: the DT-Engine incident in the docstring above,
            # repeated at a larger scale by the person who wrote this file.
            #
            # So the width now comes from the SCRIPT, and an unreadable width is a
            # REFUSAL rather than a zero. "I could not see it" was the whole defect.
            width, how = script_width(payload.get("tool_input", {}))

            #: BEFORE THE WIDTH, because width is not what broke. A fan-out of six agents
            #: each owning a file across a shared contract fails the same way sixty do.
            script_text = str(tool_input.get("script") or "")
            if not script_text and tool_input.get("scriptPath"):
                try:
                    with open(tool_input["scriptPath"], encoding="utf-8",
                              errors="replace") as fh:
                        script_text = fh.read()
                except OSError:
                    script_text = ""
            shape = parallel_edit(script_text, width)
            if shape and os.environ.get("CTRMAP_ALLOW_PARALLEL_EDIT") != "1":
                deny(
                    "BLOCKED BY PROJECT POLICY: this fans out agents that EDIT AT THE SAME "
                    "TIME - %s.\n\n"
                    "This is not the width cap, and making it narrower does not help. "
                    "Measured in DT Engine's own documents: eleven agent audit rounds, 476 "
                    "finding headlines, 1.8 MB of prose, 41 distinct code locations. Rounds "
                    "11 and 13 found NOTHING the earlier rounds had not. Six files were named "
                    "by ten or eleven separate rounds, each one rebuilding the same context.\n\n"
                    "And the fix pass is worse than wasteful. `layer5-integration-items.md` "
                    "opens: \"Each agent owned a disjoint file set. These are the changes they "
                    "identified but COULD NOT MAKE, because the change belongs in another "
                    "agent's file.\" One agent corrected a check and renamed its output keys; "
                    "the consumer lived in another agent's file, so the corrected output never "
                    "reached the operator at all. Another entry records two agents fixing the "
                    "same thing concurrently, caught by luck.\n\n"
                    "Unlimited tokens make this worse, not better: more agents, more seams, "
                    "more integration file. Do the edit in the main thread, or fan out to READ "
                    "and bring the findings back to one writer - a survey composes, a parallel "
                    "rewrite does not.\n\n"
                    "Owner override for a case that genuinely needs it: "
                    "CTRMAP_ALLOW_PARALLEL_EDIT=1." % shape)

            if width is None:
                deny(
                    "BLOCKED BY PROJECT POLICY: this launch does not say how wide it "
                    "fans out, and an unmeasured fan-out is what burned this project.\n\n"
                    "%s\n\nDeclare it where the hook can see it before it costs "
                    "anything - either\n"
                    "  meta = { name: ..., description: ..., agents: <n> }\n"
                    "in the script, or an args list this fans out over. A launch whose "
                    "width cannot be read is refused rather than assumed small, because "
                    "assuming small is exactly how 791 agents got through a cap of %d."
                    % (how, WIDTH_CAP)
                )
            if width > WIDTH_CAP:
                deny(
                    "BLOCKED BY PROJECT POLICY: this workflow fans out over %d items "
                    "(cap %d per launch).\n\n"
                    "Width is what burned this project: 22 verifiers, one per finding, "
                    "each rebuilding the same context - 2.9M tokens to answer one small "
                    "question apiece, and it still missed 9 unguarded fixes that "
                    "mechanical mutation testing found for free. Group the work per "
                    "CLUSTER so one agent holds the context for several findings, or "
                    "split the launch and read each batch's results before the next.\n\n"
                    "Owner override for a launch that genuinely needs it: CTRMAP_WIDTH_CAP=<n>."
                    % (width, WIDTH_CAP)
                )
            if flows >= WORKFLOW_CAP:
                deny(
                    "BLOCKED BY PROJECT POLICY (.claude/hooks/guard_fanout.py): "
                    "%d workflow runs already started in this 6-hour window (cap "
                    "%d).\n\n"
                    "Workflows are allowed here - what is not allowed is running "
                    "them faster than their results can be read. On 2026-09-02 five "
                    "launches in half an hour burned ~5.3M subagent tokens and two "
                    "of them died on the session limit having produced nothing.\n\n"
                    "Read the results you already have, merge what is green, and "
                    "launch the next one after. If this run is genuinely needed now, "
                    "ask the owner, with how many agents it spawns and what it "
                    "answers that the finished runs do not."
                    % (flows, WORKFLOW_CAP)
                )
            bump(agents, flows + 1, "workflow")
            return False, None

        #: every fan-out that is not workflow-shaped spawns one worker, whatever the tool
        #: calling itself. This used to be `if tool == "Agent"`, so a spawn under any other
        #: name was not counted at all - and an uncounted spawn is an uncapped one.
        if True:
            if agents >= AGENT_CAP:
                deny(
                    "BLOCKED BY PROJECT POLICY: %d subagents already spawned in this "
                    "6-hour window (cap %d).\n\n"
                    "The pattern that burned this project was many agents each "
                    "rebuilding the same context to answer one small question. If "
                    "this is verification, do it per CLUSTER - one agent already "
                    "holds the context for several findings. If it is a guard's "
                    "strength you want to prove, that is mechanical: revert the fix, "
                    "rebuild, re-run the suite (wt/_state/mutate.py) - it costs "
                    "builds instead of tokens and is better evidence.\n\n"
                    "Otherwise finish in the main thread, or ask the owner to raise "
                    "CTRMAP_AGENT_CAP with a stated reason and cost."
                    % (agents, AGENT_CAP)
                )
            bump(agents + 1, flows, "agent")
        return False, None
    except Refuse as refused:
        return True, refused.reason


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        sys.exit(0)                      # never break a turn on a parse failure
    deny_it, reason = decide(payload)
    if deny_it:
        json.dump({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }}, sys.stdout)
    sys.exit(0)


if __name__ == "__main__":
    main()
