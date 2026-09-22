#!/usr/bin/env python3
"""Refuse to END A TURN that tells the owner work is owed, unless the work is in the queue.

WHY THIS EXISTS, measured on 2026-09-22 in this session's own transcript. Three times in one
day I named work that was mine and then did not do it:

  * the option (a) vs (b) timing measurement - deferred, then deferred again, and only done
    when the owner said "why the fuck have you still not done this";
  * the same measurement a second time, at the end of a summary;
  * a test gap I had found myself - "my suite never drives the real ZoneLoadingPanel" - which
    I stated and skipped, then gave the owner restart advice twice that I had not checked.

The owner's verdict on the 161-hour census plan was "option a is the correct option but ... you
are a lazy fuck who will have work they know they need to do and are instructed to do and will
choose not to do it several times so option a will never work". That is a correctness argument,
not an insult: a plan that spans many sessions cannot rest on an intention that nothing reads.

WHY NOTHING CAUGHT IT. `commit_guard.check_known_hole` refuses a COMMIT MESSAGE that names a
hole without saying why it could not be closed, and `work_order` refuses the long measurements
while `OUTSTANDING.md` has open items. Both are real refusals, and both are blind to the
channel where every one of those three promises was made: the reply to the owner. A rule
enforced in one channel and not in its neighbour is this project's oldest shape.

WHAT IT ASKS. Only two phrasings, and they were chosen by MEASUREMENT rather than by guess -
built against this session's 3,981 assistant blocks and controlled on the items actually
dropped. "I owe you" fires in 14 of them and catches the a/b measurement exactly; "still
outstanding / still to come / still ahead" fires in 20. Both are commitments that CROSS A TURN
BOUNDARY, which is the class. Phrasings like "I'll run the suites" were rejected deliberately:
they fire 42 times and are usually followed, in the same turn, by running the suites - a guard
that fires on honest work gets switched off within the day.

WHAT IT DOES NOT CATCH, and this is a limit rather than a caveat: the SILENT case. The test gap
above was never written as a promise, so no reading of the words can find it. That one is only
closed by the habit this file cannot enforce - and saying so here is better than implying the
class is covered.

THE ESCAPE IS THE QUEUE, not a flag. Put the item in OUTSTANDING.md and the turn ends: the work
is then visible to `work_order`, which already refuses a battery or a sweep while it is open.
That is the whole point - a promise becomes a blocker instead of a sentence.
"""
import io
import json
import os
import re
import sys

LF = chr(10)

#: WHICH EVENT THIS ANSWERS. guard_all dispatches PreToolUse guards by discovering
#: guard_*.py beside it; without this declaration a Stop hook is handed a tool payload on
#: every call, answers nothing, and is counted as a tool guard - installed and off.
EVENT = "Stop"

if __name__ == "__main__":
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

#: Commitments that cross a turn boundary. Measured, not imagined - see the header.
OWED = re.compile(
    r"\bI (?:still )?owe you\b"
    r"|\bstill (?:outstanding|owed|to come|ahead)\b",
    re.I)

#: ...and the sentence that says the queue already knows, so naming the queue is not itself
#: a promise. Without this the hook fires on the very message that records the item.
RECORDED = re.compile(r"OUTSTANDING\.md", re.I)


def project_root():
    """The directory the hooks were installed into, or the cwd."""
    here = os.environ.get("CLAUDE_PROJECT_DIR")
    if here:
        return here
    return os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def open_items(root):
    """Every open line in OUTSTANDING.md, or None when the file cannot be read.

    None is NOT an empty list. A queue that cannot be read is unknown, and an unknown queue
    must not be reported empty - that is the refusal this whole tree is built around.
    """
    for candidate in (os.path.join(root, "OUTSTANDING.md"),
                      os.path.join(root, "CTRMap", "OUTSTANDING.md")):
        try:
            body = io.open(candidate, encoding="utf-8").read()
        except OSError:
            continue
        return [ln.strip() for ln in body.splitlines() if ln.strip().startswith("- [ ]")]
    return None


def last_reply(transcript_path):
    """The final assistant text of the turn, or '' when it cannot be read."""
    try:
        rows = io.open(transcript_path, encoding="utf-8", errors="replace").read().splitlines()
    except OSError:
        return ""
    said = []
    for line in reversed(rows):
        line = line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if row.get("type") != "assistant":
            if said:
                break
            continue
        parts = (row.get("message") or {}).get("content") or []
        text = "".join(p.get("text") or "" for p in parts
                       if isinstance(p, dict) and p.get("type") == "text")
        if text.strip():
            said.append(text)
            break
    return said[0] if said else ""


def decide(payload):
    """(block, why). True means the turn may not end yet."""
    #: The harness sets this when a stop hook has already blocked once this turn. Honouring it
    #: is what keeps a refusal from becoming a loop the owner has to kill.
    if payload.get("stop_hook_active"):
        return False, ""
    reply = last_reply(payload.get("transcript_path") or "")
    if not reply:
        return False, ""
    found = OWED.search(reply)
    if not found:
        return False, ""
    if RECORDED.search(reply):
        return False, ""
    root = project_root()
    items = open_items(root)
    if items is None:
        return True, ("this reply says work is owed and OUTSTANDING.md could not be read at "
                      "all, so whether it is queued is UNKNOWN - which is not the same as "
                      "queued")
    quoted = reply[max(0, found.start() - 60):found.end() + 90].replace(LF, " ").strip()
    return True, ("this reply tells the owner work is owed:" + LF
                  + "    ..." + quoted + "..." + LF
                  + "  and OUTSTANDING.md has " + str(len(items)) + " open item(s), none of "
                  "which this turn added.")


def main():
    try:
        payload = json.loads(sys.stdin.read() or "{}")
    except ValueError:
        return 0
    block, why = decide(payload)
    if not block:
        return 0
    sys.stderr.write(
        "DO IT, OR QUEUE IT - do not end the turn having said it is owed." + LF
        + "  " + why + LF + LF
        + "  MEASURED 2026-09-22, in this session: work the owner had asked for was named as" + LF
        + "  owed and skipped three times in one day, twice for the same measurement. The" + LF
        + "  owner's answer to a 161-hour plan was that it cannot work while that is true," + LF
        + "  and they are right - a plan spanning many sessions cannot rest on a sentence" + LF
        + "  nothing reads." + LF + LF
        + "  Two ways forward, and only two:" + LF
        + "    1. DO IT NOW. It is usually smaller than the paragraph describing it." + LF
        + "    2. PUT IT IN OUTSTANDING.md as `- [ ] <date> <what and why>`, which makes it" + LF
        + "       a BLOCKER rather than a promise: work_order already refuses the battery" + LF
        + "       and the mutation sweep while the queue has anything open." + LF)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
