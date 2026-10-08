#!/usr/bin/env python3
"""TWO WRITERS ARE REFUSED AT THE SECOND WRITE - asked of the write, where the act is.

README section 12, and CLAUDE.md: AGENTS THAT CANNOT EDIT EACH OTHER'S FILES MUST NOT EDIT AT THE
SAME TIME. Eleven audit rounds, 476 finding headlines over 41 locations, and a fix pass whose
handover opens "these are the changes they identified but could not make, because the change
belongs in another agent's file" - one of which never reached the user.

`guard_agent_edits` asks at the LAUNCH, from the brief's words, and measured 2026-10-06 it let ten
of twelve editing briefs through: "Make the tests pass", "Add error handling to load()", "Move
helper() into utils.py". Whether an agent will write cannot be read from its brief; whether it IS
writing can be read from its writes. That is the act, so that is where this asks.

WHAT THE HARNESS SAYS, per its hook contract (not measured here - this session runs no agents):
every tool call a subagent makes carries its `agent_id`, and `SubagentStart` and `SubagentStop`
report each subagent's beginning and end. So who has written, and whether they still run, is a
record kept from those - one per session, outside the tree.

WHAT IS REFUSED: a Write or Edit - a call carrying content for a file, by shape - while another
writer is LIVE:

    a subagent that has written and not stopped     beside the main thread or another subagent
    the main thread, having written since this      beside a subagent that started before it
      subagent started

WHAT IS NOT. Every read. A survey that only reads blocks nobody - fan out to READ, bring the
findings back to one writer. A foreground subagent: the main thread waits while it runs, so one
writer at a time is never two.

WHAT IT CANNOT SEE, said rather than implied: a command's writes (`sed -i`, a script) are not
attributed to anyone here - they are judged the moment after, by the rules, like any command's.
A subagent whose stop is never reported is presumed gone after STALE seconds without a call.

A RECORD NOBODY CAN READ IS UNKNOWN, NOT EMPTY: a write is refused while the record cannot be
read, and the refusal names the file. The owner's way past is the launch guard's own,
`bundle_env.name("ALLOW_PARALLEL_EDIT")`.
"""
import io
import json
import os
import re
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_env        # noqa: E402  - one PREFIX renames every override
import bundle_shell      # noqa: E402  - the call, found by shape

LF = chr(10)
BYPASS = bundle_env.name("ALLOW_PARALLEL_EDIT")

#: Where each session's writer record is kept: outside the tree, so no write here is a change to
#: judge, and a fresh clone or a reset cannot take a live writer's record with it.
STATE_DIR = os.path.join(tempfile.gettempdir(), "bundle-agent-writers")

#: A subagent silent this long, with no stop reported, is presumed gone - a lost `SubagentStop`
#: must not hold every write in the session for ever. Thirty minutes: longer than any one call a
#: live agent waits on in the suites measured here.
STALE = 30 * 60

#: Adapted per project: see the bootstrap's ADAPT.md.
ADAPT = ("STALE",)


def state_path(session):
    return os.path.join(STATE_DIR, re.sub(r"[^A-Za-z0-9_.-]", "_", str(session or "none"))[:80]
                        + ".json")


def load(session):
    """(record, '') - or (None, why) when a record exists and cannot be read."""
    path = state_path(session)
    if not os.path.exists(path):
        return {"agents": {}, "main": None}, ""
    try:
        with io.open(path, encoding="utf-8") as handle:
            held = json.load(handle)
    except (OSError, ValueError) as exc:
        return None, "%s cannot be read (%s: %s)" % (path, type(exc).__name__, exc)
    if not isinstance(held, dict) or not isinstance(held.get("agents"), dict):
        return None, "%s is not a writer record" % path
    return held, ""


def save(session, held):
    os.makedirs(STATE_DIR, exist_ok=True)
    path = state_path(session)
    temp = "%s.%d.tmp" % (path, os.getpid())
    with io.open(temp, "w", encoding="utf-8") as handle:
        json.dump(held, handle, sort_keys=True)
    os.replace(temp, path)


def _entry(held, agent, now):
    entry = held["agents"].setdefault(agent, {"first": now, "last": now, "wrote": None,
                                             "stopped": None})
    entry["last"] = now
    return entry


def live_writers(held, party, now):
    """Every OTHER writer still running beside `party` - an agent id, or "main"."""
    out = []
    for name, entry in sorted(held["agents"].items()):
        if name == party or not entry.get("wrote") or entry.get("stopped"):
            continue
        if now - float(entry.get("last") or 0) > STALE:
            continue
        out.append("subagent %s" % name)
    started = (held["agents"].get(party) or {}).get("first")
    if party != "main" and held.get("main") is not None and started is not None \
            and float(held["main"]) >= float(started):
        out.append("the main thread")
    return out


def refusal(party, others):
    return (bundle_shell.policy(__file__) + LF
            + "This %s writes while %s %s writing beside it - two writers who cannot see each "
              "other's files.\n\n"
              "MEASURED: eleven audit rounds, 476 findings over 41 locations, and a fix pass whose "
              "handover opens 'these are the changes they identified but could not make, because "
              "the change belongs in another agent's file' - one of which never reached the "
              "user.\n\n"
              "One writer at a time: let it finish, or run the next one in the foreground. A "
              "survey that only READS is never refused. The owner's way past: %s=1.\n"
            % ("subagent (%s)" % party if party != "main" else "main thread",
               " and ".join(others), "are" if len(others) > 1 else "is", BYPASS))


def verdict(payload, now=None):
    """(exit code, message) for one payload - a tool call, or a subagent's start or stop."""
    now = time.time() if now is None else now
    if not isinstance(payload, dict) or bundle_env.allowed("ALLOW_PARALLEL_EDIT"):
        return 0, ""
    session, agent = payload.get("session_id"), payload.get("agent_id")
    event = payload.get("hook_event_name")
    if event in ("SubagentStart", "SubagentStop"):
        held, _trouble = load(session)
        if held is None or not agent:
            return 0, ""             # nothing to record into; the next write refuses on it
        entry = _entry(held, agent, now)
        if event == "SubagentStart":
            entry["first"] = min(float(entry.get("first") or now), now)
        else:
            entry["stopped"] = now
        try:
            save(session, held)
        except OSError:
            pass
        return 0, ""
    if not bundle_shell.is_tool_call(payload):
        return 0, ""
    writes = bundle_shell.writes(payload)
    if not writes and not agent:
        return 0, ""                 # the main thread's reads and commands are nobody's business
    held, trouble = load(session)
    if held is None:
        if not writes:
            return 0, ""
        return 2, (bundle_shell.policy(__file__) + LF + "Whether another writer is live cannot "
                   "be read: %s. Unknown is not alone. Delete that file if no subagent is "
                   "running, or the owner sets %s=1.\n" % (trouble, BYPASS))
    party = agent or "main"
    entry = _entry(held, agent, now) if agent else None
    if writes:
        others = live_writers(held, party, now)
        if others:
            return 2, refusal(party, others)
        if entry is not None:
            entry["wrote"] = now
        else:
            held["main"] = now
    try:
        save(session, held)
    except OSError as exc:
        if writes:
            return 2, (bundle_shell.policy(__file__) + LF + "This write cannot be recorded "
                       "(%s), and a write nobody recorded is one the next writer cannot see. The "
                       "owner's way past: %s=1.\n" % (exc, BYPASS))
    return 0, ""


def main():
    try:
        payload = json.loads(bundle_shell.payload_text())
    except (ValueError, IOError):
        return 0
    code, said = verdict(payload)
    if code:
        sys.stderr.write(said + LF)
    return code


if __name__ == "__main__":
    bundle_shell.speak_utf8()
    sys.exit(main())
