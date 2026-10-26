#!/usr/bin/env python3
"""ONE ENTRY, OVER A DISPATCHER THAT DISCOVERS ITS GUARDS. A TRIGGER IS A CLASS, NOT A LIST.

MEASURED IN THIS PROJECT, 2026-09-22: `.claude/settings.json` held **eight `PreToolUse` entries,
every one of them `"matcher": "Bash"`**, while this session's primary shell is PowerShell and a
`PowerShell` tool is offered alongside `Bash`. Four of the eight guards ALSO opened with

    if str(payload.get("tool_name") or "") != "Bash":
        sys.exit(0)

so every heredoc rule, every backgrounded-pipe rule, every mutation-read rule and every
idle-poll rule was installed, wired, tracked, tested, proven by planting - and **off** for every
command issued through the other tool. Nobody bypassed anything. The guards were simply not
asked.

That is the bundle's rule this project had never adopted, word for word: *A GUARD'S TRIGGER IS A
CLASS, NOT A LIST OF NAMES. Ask a SHAPE - does this tool input carry a command - and wire one
entry over a dispatcher that DISCOVERS its guards rather than listing them, because a list is
the same defect one level up.*

WHAT THIS DOES. Registered once per EVENT under `"matcher": "*"` - `PreToolUse` and `Stop` - it
reads the payload, finds every `.claude/hooks/guard_*.py` in the directory, and hands each the
identical stdin. Each guard decides for itself whether the payload concerns it - `guard_fanout`
looks for the Workflow and Agent tools, `guard_promise` looks for the end of a turn, the command
guards look for a command - so adding a guard is adding a FILE, and it is asked from that moment
with nobody editing a list. The `Stop` event used to name `guard_promise.py` directly, which is
a list of one: a second end-of-turn guard written beside it would have been installed and off.

A GUARD THAT CANNOT RUN IS A REFUSAL, NOT A SKIP. An exit code this does not understand means
the guard did not get to answer, and an unanswered question must never render as permission.
This project has measured the other behaviour: a filter that matched nothing reported twelve
live sweep workers gone, and acting on that discarded 134 verdicts.

A COMMAND NOBODY CAN READ IS REFUSED HERE, ONCE. `bundle_shell.unreadable` shipped with no
caller: every command guard asked `commands()` and none asked whether the walk had failed, so a
`command` that was not text read as no command at all. Asking it in each guard would be a guard
at call sites, one call site from broken; this is the one place every call passes through.

IT DOES NOT PARSE COMMANDS ITSELF, deliberately. Every rule about what a command may do stays in
the guard that owns it; this only decides WHO IS ASKED. A dispatcher that also judged would be a
second copy of every rule, which is the failure this repository has paid for most.
"""
import json
import os
import re
import subprocess
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

LF = chr(10)
HERE = os.path.dirname(os.path.abspath(__file__))

sys.path.insert(0, HERE)
# A PLANT ON DISK IS ITS OWN TEST'S - `bundle_plant`. First, before any other sibling is imported:
# a plant in this very file hands the call to the file's true copy, and a plant in a sibling puts
# the sibling's true copy where every import finds it. A planted guard is loaded true in `ask`.
import bundle_plant    # noqa: E402  - which copy runs: the index's while a plant holds the file
PLANT_TROUBLE = bundle_plant.begin(__file__, __name__)
import bundle_env      # noqa: E402  - one PREFIX renames every override
import bundle_shell    # noqa: E402  - the command, found by shape, at any depth

#: This file, and anything that is not a guard. Derived from the naming convention the directory
#: already uses rather than from a roster - the roster is the thing being removed.
PREFIX = "guard_"

#: Windows gives a console-subsystem child its own window unless told otherwise, and a hook that
#: blinks ten terminals on every tool call is a hook somebody deletes within the hour.
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

#: NO PER-GUARD TIMEOUT, AND SAID SO. A guard is asked in this process (`ask`), where nothing can
#: stop it, and some guards legitimately take minutes - a write-time rule runs the tests a write
#: changes. What bounds a hung guard is the hook's own `timeout` in the settings (1800 s for
#: `PreToolUse`), and a hook the harness times out is a hook it IGNORES: the call goes ahead. So
#: every subprocess a guard starts is bounded (`tools/timeouts.py`), which is the bound that
#: holds. A `TIMEOUT = 20` stood here, and a `timeout` parameter `ask` deleted on entry, while
#: nothing read either (F169); both are gone rather than left to read as a bound.

#: The owner's way past the unreadable-command refusal, spelled once in `bundle_env`.
ALLOW_UNREADABLE = bundle_env.name("ALLOW_UNREADABLE")


def guards(folder=HERE):
    """Every guard file in the directory, discovered. Sorted, so the order is reproducible."""
    try:
        names = sorted(os.listdir(folder))
    except OSError:
        return []
    return [os.path.join(folder, name) for name in names
            if name.startswith(PREFIX) and name.endswith(".py")]


def ask(path, raw):
    """(exit code, stderr, stdout) for one guard, handed the identical payload.

    IN-PROCESS, NOT SPAWNED, AND THE REASON IS ARITHMETIC. This dispatcher is asked on EVERY
    tool call, and there are ten guards; spawning a fresh interpreter for each costs roughly a
    second per call on Windows. A guard layer that makes every edit and every read visibly
    slower is a guard layer somebody turns off, and this project already has a section on a tool
    that watched the work and cost more than the work.

    Each guard is loaded as a module and its `main` called with `sys.stdin` pointed at the
    payload, which is the contract they already have - they all read `json.load(sys.stdin)` and
    return or raise `SystemExit` with a code.

    EVERY RETURN IS THREE VALUES. The version this replaced returned two on the load-failure
    branches and three on success, and `verdict` unpacks three - so a guard that could not load
    raised `ValueError` inside the dispatcher instead of being reported, which is the one branch
    whose whole job is to report.
    """
    import io as _io

    name = os.path.splitext(os.path.basename(path))[0]
    try:
        # THE GUARD AS IT TRULY IS - git's index's copy while a plant holds it, never the plant on
        # disk; the lock is read on both sides of the read (`bundle_plant.true_text`).
        module, code = bundle_plant.module("dtguard_" + name, path, bundle_plant.true_text(path))
        exec(code, module.__dict__)
    except bundle_plant.Planted as exc:
        return CRASHED, ("%s has not been asked: %s." % (name, exc)), ""
    except BaseException as exc:                     # a guard that cannot load has not passed
        return CRASHED, ("%s could not be loaded (%s: %s). A guard that cannot run has not "
                         "passed - it has not been asked." % (name, type(exc).__name__, exc)), ""

    entry = getattr(module, "main", None)
    if not callable(entry):
        return CRASHED, ("%s has no `main` to call, so it cannot answer. A guard nobody can "
                         "invoke is a file." % name), ""

    held_in, held_err, held_out = sys.stdin, sys.stderr, sys.stdout
    sys.stdin, sys.stderr, sys.stdout = _io.StringIO(raw), _io.StringIO(), _io.StringIO()
    try:
        code = entry()
        code = 0 if code is None else int(code)
    except SystemExit as exc:
        code = 0 if exc.code is None else (exc.code if isinstance(exc.code, int) else 2)
    except BaseException as exc:
        # CRASHED, NOT REFUSED, AND THE DIFFERENCE IS LOAD-BEARING. This returned 2 - the same
        # code a guard uses to say no - so `verdict` could not tell "this command is forbidden"
        # from "this guard could not answer". The repair carve-out was written into the branch
        # for an unrecognised exit code, which a crash never reached, so it did nothing at all.
        # Caught by driving a deliberately broken guard rather than by reading the branch.
        code = CRASHED
        sys.stderr.write("%s raised %s: %s" % (name, type(exc).__name__, exc))
    finally:
        err, out = sys.stderr.getvalue(), sys.stdout.getvalue()
        sys.stdin, sys.stderr, sys.stdout = held_in, held_err, held_out
    return code, err, out


#: WHAT REPAIRS A BROKEN GUARD. `git`, and touching the hooks directory itself.
#:
#: MEASURED, 2026-09-22, by doing it: `guard_idle_machine.py` was edited to call a function that
#: had not been written yet, so it raised `NameError` on every call. This dispatcher treats a
#: crashed guard as a hole and refuses - correctly - and the result was that Bash, Edit, Write
#: and Grep were ALL refused. There was no way to repair the file that did not go through the
#: file. The session could only be recovered by the owner running `git checkout` by hand.
#:
#: `guard_mutation_read.py` carries this rule in its own docstring - *a guard that blocks the way
#: out of the situation it describes is worse than none* - and allows `git` for exactly this. The
#: dispatcher is the one place where that failure is total rather than local.
#:
#: THE HOOKS DIRECTORY IS THIS ONE, read from where this file sits. Spelled `.claude/hooks`, the
#: repair route pointed at the wrong folder in a project that keeps the bundle's hooks beside a
#: stack of its own - a broken bundle guard there could be repaired by nothing but `git`.
#:
#: ASKED OF THE PARSED CALL, NEVER ITS RAW TEXT (2026-10-24). This was two patterns searched over
#: the raw payload: `^\s*git\b`, and the folder between two separators. A Write's file_path
#: arrives JSON-escaped - two characters between the names - and a command sits mid-line in
#: one-line JSON, where `^git` never starts a line. MEASURED 2026-10-18 installing the bundle into
#: CTRMap: a guard landed one write before the module it imports, crashed on load, and refused a
#: Write of that module by either slash and a `git rev-parse`, until a payload whose CONTENT
#: spelled the folder with forward slashes went through. So the folder is looked for in each path
#: and command the call carries, decoded, either slash; and `git` in each program it RUNS.
HOOKS_NAMED = re.compile(r"\.claude/+" + re.escape(os.path.basename(HERE)) + r"(?:/|$)", re.I)

#: The code `ask` returns when a guard could not ANSWER - it raised, timed out, would not load,
#: or has no entry point. Distinct from 2, which is a guard saying no, because the two need
#: opposite handling: a refusal stands for every call, and a guard that cannot answer must not
#: block the call that would repair it.
CRASHED = 3


def repairs(raw, payload=None):
    """Is this call one that could FIX a broken guard - does it name the hooks directory in any
    path or command it carries, or run `git` in any command?

    The shapes differ per tool - Bash and PowerShell carry `command`, Edit and Write carry
    `file_path`, and a batching tool nests either - so it asks `bundle_shell`, which already reads
    every tool's shape at any depth, rather than the raw text (see HOOKS_NAMED for what that
    cost). Being generous here costs a guard that has ALREADY CRASHED; being strict costs the only
    route out of a wedged session.
    """
    if payload is None:
        try:
            payload = json.loads(raw or "")
        except ValueError:
            return False
    if not isinstance(payload, dict):
        return False
    commands = bundle_shell.commands(payload)
    if any(HOOKS_NAMED.search(text.replace(chr(92), "/"))
           for text in bundle_shell.paths(payload) + commands):
        return True
    return any(words and bundle_shell.program_of(words[0][0]) == "git"
               for command in commands for words, _background in bundle_shell.runs(command))


def denies(out):
    """The JSON deny a guard printed on stdout, verbatim, or None.

    THERE ARE TWO REFUSAL PROTOCOLS IN THIS DIRECTORY AND THE FIRST DISPATCHER KNEW ONE.
    `guard_blocked_runner` refuses by exiting 2 with a message on stderr; `guard_heredoc`
    refuses by printing `{"hookSpecificOutput": {... "permissionDecision": "deny" ...}}` on
    stdout AND EXITING 0, which is equally valid and is what the hook system reads.

    Handling only exit 2 meant every JSON-protocol denial was swallowed: the guard said deny,
    the dispatcher saw a zero, and the command ran. Measured by driving four heredoc spellings
    the suite asserts are refused - all four came back allowed through the dispatcher while the
    guard itself was refusing them correctly.

    The whole point of a dispatcher is that it is the only thing standing between a guard and
    the tool, so a protocol it does not understand is a guard that is not installed.
    """
    text = (out or "").strip()
    if not text or "permissionDecision" not in text:
        return None
    for line in text.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            held = json.loads(line)
        except ValueError:
            continue
        decision = ((held.get("hookSpecificOutput") or {}).get("permissionDecision")
                    or held.get("permissionDecision"))
        if str(decision).lower() == "deny":
            return line
    return None


def said(out):
    """The `systemMessage` a guard printed on stdout while ALLOWING, or None.

    A GUARD THAT LETS A CALL THROUGH MAY STILL HAVE SOMETHING TO SAY. `guard_requests` allows a
    turn whose transcript it cannot read - refusing would wedge it - and prints that the requests
    were NOT checked, because "allowing silently would read as a clean ledger". This forwarded a
    deny and nothing else, so under the dispatcher that sentence went nowhere, and "could not look"
    reached the owner exactly as "nothing found" does.
    """
    for line in (out or "").splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            held = json.loads(line)
        except ValueError:
            continue
        if isinstance(held, dict) and isinstance(held.get("systemMessage"), str):
            return held["systemMessage"]
    return None


def unreadable_refusal(payload):
    """The refusal for a call carrying a command nobody can read, or None when all of it read."""
    trouble = bundle_shell.unreadable(payload)
    if not trouble or bundle_env.allowed("ALLOW_UNREADABLE"):
        return None
    return bundle_shell.blind_refusal("dispatch.py", "; ".join(trouble), ALLOW_UNREADABLE)


def verdict(raw, found=None):
    """(exit code, stderr message, stdout to forward) after asking every guard.

    A guard exits 0 to allow, exits 2 to refuse, or prints a JSON deny and exits 0. ANY OTHER
    CODE means it crashed, and a crashed guard is a hole rather than a pass - the one place this
    differs from the guards themselves, which exit 0 on a malformed payload so a parse failure
    cannot wedge a turn. Here the payload has already parsed, so an unexpected code is the
    guard's own fault and hiding it would leave the rule silently off.
    """
    try:
        payload = json.loads(raw)
    except ValueError:
        payload = {}
    blind = unreadable_refusal(payload) if isinstance(payload, dict) else None
    if blind:
        return 2, blind, ""
    if PLANT_TROUBLE and not excused(raw, payload):
        # A HOOK FILE THAT COULD NOT BE LOADED TRUE has not answered - the same hole as a guard
        # that crashed, and the same way out for the call that could fix it.
        return 2, PLANT_TROUBLE, ""
    heard = []
    for path in (guards() if found is None else found):
        code, err, out = ask(path, raw)
        denial = denies(out)
        if denial is not None:
            # FORWARDED VERBATIM. Re-wording a guard's own refusal would put this file in the
            # business of explaining rules it does not own, which is how a second copy of every
            # rule gets written.
            return 0, "", denial
        if code == 0:
            message = said(out)
            if message:
                heard.append(message)
            continue
        if code == 2:
            return 2, (err or out), ""
        if excused(raw, payload):
            continue
        return 2, ("%s exited %s, which is neither allow (0) nor refuse (2). A guard whose "
                   "answer cannot be read must not be counted as permission.%s%s"
                   % (os.path.basename(path), code, LF, (err + out).strip()[-800:])), ""
    return 0, "", json.dumps({"systemMessage": LF.join(heard)}) if heard else ""


def excused(raw, payload):
    """May this call go ahead past a guard that could not ANSWER?

    A GUARD THAT COULD NOT ANSWER IS A HOLE - EXCEPT FOR THE CALL THAT COULD FIX IT. Refusing
    everything on a crashed guard locked this session out of every tool at once, with the only
    route to the broken file running through the broken file. A working guard's refusal (exit 2)
    still stands for repair calls too - this carve-out is only for a guard that could not answer.

    AND AT THE END OF A TURN, ONCE. Refusing a `Stop` sends the turn back to work, which is the
    right answer the first time - the work is to fix the guard. The second time the harness says
    so with `stop_hook_active`, and refusing again would spin forever over a file the turn may
    not be able to reach.
    """
    if repairs(raw, payload):
        return True
    return bool(isinstance(payload, dict) and not bundle_shell.is_tool_call(payload)
                and payload.get("stop_hook_active"))


def main():
    raw = bundle_shell.payload_text()           # UTF-8, never the locale's code page
    #: A PAYLOAD NO GUARD COULD JUDGE IS NOT ONE EVERY GUARD ALLOWED. This returned 0 "never wedge
    #: a turn over a malformed hook payload" - and so the guards that refuse an unreadable payload
    #: themselves were never asked one (CTRMap F213). It is refused in words; a turn end only ONCE,
    #: as a crashed guard's is; and the owner's way past is one variable. Whatever goes through
    #: unjudged says so.
    problem = bundle_shell.payload_problem(raw)
    if problem:
        retried = bundle_shell.refused_before(raw)
        if bundle_env.allowed("ALLOW_UNREADABLE") or retried:
            why = ("a turn end already sent back once" if retried
                   else "%s is set" % ALLOW_UNREADABLE)
            sys.stdout.write(json.dumps({"systemMessage": "dispatch.py: %s, and this is %s - so NO "
                                         "guard judged it" % (problem, why)}) + LF)
            return 0
        sys.stderr.write(bundle_shell.unreadable_payload(__file__, problem, ALLOW_UNREADABLE))
        return 2
    code, said, forward = verdict(raw)
    if forward:
        sys.stdout.write(forward + LF)
        return 0
    if code:
        sys.stderr.write(said.rstrip() + LF)
    return code


if __name__ == "__main__":
    bundle_shell.speak_utf8()           # the one door out, as payload_text is the one in
    sys.exit(main())
