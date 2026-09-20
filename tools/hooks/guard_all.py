#!/usr/bin/env python3
"""Every guard beside this file, asked about every tool call, from one wiring point.

WHY THIS EXISTS, measured.

`.claude/settings.json` wired seven hooks under `"matcher": "Bash"`, and five of the seven
opened by exempting any tool not named `Bash`. This session also offers a `PowerShell` tool
that runs shell commands. Nothing had to be bypassed for the heredoc refusal - the one that put
NUL bytes into a committed file - and six others to be off. The command only had to be spelled
through a different tool.

That is this project's own rule, paid for by the guards themselves: A GUARD AT CALL SITES IS
ONE CALL SITE FROM BROKEN, filed four separate times before this. A matcher naming tools is a
call-site list, and so is `if tool_name != "Bash"`. Both were.

So there is now ONE entry, `"matcher": "*"`, and it is this file. It DISCOVERS the guards -
every `guard_*.py` beside it - rather than listing them, because a list is the same defect one
level up: a guard dropped into this directory and forgotten about is a guard that is installed,
wired and off, which is exactly how 791 agents went through a cap of six.

Three things it refuses rather than allows, because each of them is "I could not look":

  * a `guard_*.py` beside it that has no `decide()` - present, unaskable, and indistinguishable
    from one that answered no
  * a guard whose `decide()` raises - a guard that crashed did not clear the call
  * a guard that takes longer than `PATIENCE` seconds - PreToolUse is synchronous, and a guard
    that never returns is one that cannot answer

TO RUN WITH THE GUARDS BROKEN: the owner sets CTRMAP_GUARD_BROKEN=1 for that session. That
turns a crashing guard from a refusal into a warning on stderr. It does not turn off a guard
that works.
"""
import glob
import importlib.util
import io
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import shellin                                    # noqa: E402  (path set above)

#: Longer than this and the guard is not answering. Every existing guard decides in
#: milliseconds; the slowest reads three small files.
PATIENCE = 8.0

BROKEN_OK = "CTRMAP_GUARD_BROKEN"

#: This file is the dispatcher, not a guard, and shellin is a library.
NOT_A_GUARD = ("guard_all.py",)


def guards():
    """Every guard file beside this one, sorted, so the order is the same every run."""
    found = sorted(os.path.basename(p) for p in glob.glob(os.path.join(HERE, "guard_*.py")))
    return [name for name in found if name not in NOT_A_GUARD]


def ask(name, payload):
    """(deny, reason) from one guard. A guard that cannot answer is a refusal, not a pass."""
    started = time.time()
    #: A guard that still calls `main()` at module level would read stdin during the import
    #: and block this process forever - PreToolUse is synchronous, so that is the whole turn.
    #: Handing it an empty stream turns the hang into a SystemExit, which is caught below and
    #: reported as a guard that did not answer. guard_fanout.py was exactly this shape.
    real_stdin = sys.stdin
    sys.stdin = io.StringIO("")
    try:
        spec = importlib.util.spec_from_file_location(
            "ctrmap_guard_" + name[:-3], os.path.join(HERE, name))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    except SystemExit:
        return _broken(name, "it ran itself during import instead of defining decide()", None,
                       payload)
    except Exception as cannotLoad:
        return _broken(name, "it could not be imported", cannotLoad, payload)
    finally:
        sys.stdin = real_stdin
    decide = getattr(module, "decide", None)
    if decide is None:
        return _broken(name, "it has no decide(payload) for the dispatcher to ask", None,
                       payload)
    try:
        answer = decide(payload)
    except SystemExit:
        #: a guard that still exits the process instead of returning a value would take
        #: every guard after it with it, silently. That is a broken guard, not a pass.
        return _broken(name, "it exited the process instead of returning a verdict", None,
                       payload)
    except Exception as cannotDecide:
        return _broken(name, "it raised while deciding", cannotDecide, payload)
    took = time.time() - started
    if took > PATIENCE:
        return _broken(name, "it took %.1fs to answer (limit %.0fs)" % (took, PATIENCE),
                       None, payload)
    try:
        deny, reason = answer
    except (TypeError, ValueError):
        return _broken(name, "it answered %r, not (deny, reason)" % (answer,), None, payload)
    return bool(deny), reason


def repairs_a_guard(payload):
    """True when this call writes into the hooks directory - i.e. it is the repair.

    A GUARD THAT BLOCKS ITS OWN REPAIR IS A TRAP, and this one sprang within the hour: a new
    guard here shipped with an unterminated character set, raised on every call, and the
    dispatcher then refused the edit that would have fixed it, in either tool. Being unable to
    answer is a refusal - but never a refusal of the answer.
    """
    for path in shellin.paths_written(payload):
        if os.path.normcase(os.path.abspath(path)).startswith(os.path.normcase(HERE)):
            return True
    return False


def _broken(name, what, error, payload=None):
    detail = "" if error is None else " (%s: %s)" % (type(error).__name__, error)
    if payload is not None and repairs_a_guard(payload):
        sys.stderr.write("guard %s is broken: %s%s - allowed because this call repairs a "
                         "guard\n" % (name, what, detail))
        return False, None
    if os.environ.get(BROKEN_OK) == "1":
        sys.stderr.write("guard %s is broken: %s%s - allowed by %s=1\n"
                         % (name, what, detail, BROKEN_OK))
        return False, None
    return True, (
        "BLOCKED BY PROJECT POLICY (.claude/hooks/guard_all.py).\n"
        "The guard %s did not clear this call: %s%s.\n\n"
        "A guard that could not answer is not a guard that answered no. Every probe has an "
        "answer for \"it is not there\" and an answer for \"I could not look\", and code that "
        "collapses the second into the first reports a clean negative - which is how twelve "
        "live workers were reported gone here and 134 verdicts were discarded.\n\n"
        "Fix the guard, or set %s=1 for this session to run with it broken.\n"
        % (name, what, detail, BROKEN_OK))


#: Where the wiring check lives. It is in the repository rather than beside this file so that
#: it is version controlled, breakable by the plant ledger and pinned by a suite. This walks up
#: to find it rather than hardcoding a path, because the session folder gets renamed.
CHECKER = os.path.join("CTRMap", "tools", "guard", "hooks_wired.py")


def _checker_path():
    here = HERE
    for _ in range(5):
        here = os.path.dirname(here)
        candidate = os.path.join(here, CHECKER)
        if os.path.isfile(candidate):
            return candidate
        beside = os.path.join(here, "tools", "guard", "hooks_wired.py")
        if os.path.isfile(beside):
            return beside
    return None


def _edits_the_wiring(payload):
    """True when this call is how the wiring gets REPAIRED.

    Without this the wiring check is a trap: a settings file that enumerates tools refuses
    every call, including the edit that would fix it. The escape is deliberately the narrowest
    one that works - a write whose target is inside `.claude`.
    """
    checker = _checker_path()
    for path in shellin.paths_written(payload):
        spelled = path.replace(chr(92), "/")
        if ".claude" in spelled.split("/"):
            return True
        #: AND THE CHECKER ITSELF. Leaving it out made the same trap one directory over: an
        #: edit that referred to a function it was about to add left the check raising, and
        #: the refusal then blocked the edit that would have finished it. A guard may refuse
        #: the work; it may never refuse its own repair.
        if checker and os.path.normcase(os.path.abspath(spelled)) == os.path.normcase(checker):
            return True
    text = shellin.text(payload)
    if ".claude" in text and ("settings.json" in text or "hooks" in text):
        return True
    return bool(checker) and os.path.basename(checker) in text


def wiring(payload):
    """(deny, reason) when this installation's own wiring cannot reach every guard.

    THIS IS THE POINT OF ACTION FOR THE DEFECT THAT CREATED THIS FILE. On 2026-09-20 seven
    guards here were wired under `"matcher": "Bash"` while the session also ran a `PowerShell`
    tool, so all seven were installed, wired, running and off. A suite would have reported
    that afterwards. This refuses the call.
    """
    if _edits_the_wiring(payload):
        return False, None
    checker = _checker_path()
    if checker is None:
        return True, (
            "BLOCKED BY PROJECT POLICY (.claude/hooks/guard_all.py).\n"
            "The wiring check (%s) is not where this expected it, so whether these guards are "
            "reachable at all is UNKNOWN - and an unknown is not a clean wiring. Seven guards "
            "here were installed, wired and off for a whole class of tool calls without "
            "anybody bypassing anything.\n\n"
            "Restore the checker, or set %s=1 for this session.\n" % (CHECKER, BROKEN_OK))
    try:
        spec = importlib.util.spec_from_file_location("ctrmap_hooks_wired", checker)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        #: the SESSION root - `.claude`'s parent - not `.claude` itself. The check looks for
        #: the repository's `tools/hooks` at that root or one level down, and handing it
        #: `.claude` made it report the versioned copies missing when they were one directory
        #: further out.
        why = module.findings(os.path.dirname(os.path.dirname(HERE)))
    except Exception as cannotCheck:
        return True, (
            "BLOCKED BY PROJECT POLICY (.claude/hooks/guard_all.py).\n"
            "The wiring check could not run (%s: %s), so whether every installed guard is "
            "reachable is UNKNOWN.\n\nFix it, or set %s=1 for this session.\n"
            % (type(cannotCheck).__name__, cannotCheck, BROKEN_OK))
    if not why:
        return False, None
    return True, (
        "BLOCKED BY PROJECT POLICY (.claude/hooks/guard_all.py).\n"
        "This project's own guards are not all reachable:\n\n  - %s\n\n"
        "A GUARD THAT IS INSTALLED BUT CANNOT BE REACHED IS OFF, and looks exactly like one "
        "with nothing to refuse. On 2026-09-20 seven guards here were wired for the tool name "
        "\"Bash\" while the session also ran a PowerShell tool; the heredoc refusal that put "
        "NUL bytes into a committed file was among them.\n\n"
        "Edits inside .claude are still allowed, so the wiring can be repaired from here. To "
        "work with it as it is, set %s=1 for this session.\n"
        % ("\n  - ".join(why), BROKEN_OK))


def decide(payload):
    """(deny, reason) - the first refusal, naming the guard that made it."""
    if os.environ.get(BROKEN_OK) != "1":
        deny, reason = wiring(payload)
        if deny:
            return True, reason
    for name in guards():
        deny, reason = ask(name, payload)
        if deny:
            return True, reason
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
