#!/usr/bin/env python3
"""A STARTLING MAGNITUDE IS A BUG REPORT, until proven otherwise, in either direction.

WHAT THIS COST, measured on 2026-09-20 and not invented.

A ratchet over the plant ledger was re-measured after a change that was meant to loosen its
predicate. It came back 103. Before the change it was 103. The true number was 64: the word
boundary in the new pattern had arrived as 0x08 through a shell escaping layer, so the pattern
compiled, matched nothing but digits, and measured exactly what it had measured before. THE
SAME NUMBER TWICE ACROSS A CHANGE THAT SHOULD HAVE MOVED IT is a bug report, and it went
unread for twenty minutes because nothing was comparing.

The other direction is in this project's rules too: a map rebuild whose output was 10.7 MB and
whose exit code said failure; a scan returning 0 across 536 files because it read the wrong
struct field, twice. A startling number is the cheapest bug report there is and the easiest to
nod at.

WHAT THIS REFUSES:

  * a recorded quantity that moved further than its allowance since the last recording, with
    no reason given
  * a quantity recorded as `--expect-change` that did not move AT ALL - the 103 case

The series lives in `wt/_state/magnitudes.json`, which is measurement output rather than
source. The escape is `--why "<what changed>"`, which is stored beside the number so the next
comparison starts from an explained baseline rather than a surprised one.

    python tools/guard/magnitude.py <name> <value> [--why "..."] [--expect-change]
    python tools/guard/magnitude.py --list

Exit 1 when the move is startling and unexplained, 0 when it is recorded.
"""
import io
import json
import os
import sys

LF = chr(10)
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
#: BESIDE plants.json, not under wt/. This project keeps its worktrees and sweep state
#: in the SESSION folder; putting a series inside the repository under wt/ created a
#: stray directory that looked like one of those and would not have travelled with a
#: clone - a comparison nobody else can make is not a comparison.
SERIES = os.path.join(ROOT, "tools", "guard", "magnitudes.json")

#: How far a number may move before it needs a sentence. Both must be exceeded: a count of 2
#: going to 3 is 50% and means nothing, and a count of 4000 going to 4100 is 100 and means
#: nothing either.
ALLOWANCE_FRACTION = 0.25
ALLOWANCE_ABSOLUTE = 3


def load():
    try:
        return json.load(io.open(SERIES, encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def save(book):
    folder = os.path.dirname(SERIES)
    if not os.path.isdir(folder):
        os.makedirs(folder)
    io.open(SERIES, "w", encoding="utf-8", newline=LF).write(
        json.dumps(book, indent=1, sort_keys=True) + LF)


def startling(previous, now, expect_change):
    """Why this number is a bug report, or None. `previous` None means nothing to compare."""
    if previous is None:
        return None
    if expect_change and previous == now:
        return (
            "it is EXACTLY the number it was before a change that was supposed to move it "
            "(%s). That is not reassurance - it is the shape of a measurement that did not "
            "happen. A predicate here compiled, matched nothing it was meant to match, and "
            "reported 103 both before and after; the true number was 64." % now)
    moved = abs(now - previous)
    if moved > ALLOWANCE_ABSOLUTE and moved > abs(previous) * ALLOWANCE_FRACTION:
        return ("it moved from %s to %s (%s%s, %.0f%%), further than %d and %.0f%% allow"
                % (previous, now, "+" if now > previous else "-", moved,
                   100.0 * moved / max(abs(previous), 1), ALLOWANCE_ABSOLUTE,
                   100 * ALLOWANCE_FRACTION))
    return None


def record(name, value, why=None, expect_change=False):
    """(ok, message). Refuses a startling unexplained move; records anything it allows."""
    book = load()
    entry = book.get(name) or {}
    previous = entry.get("value")
    trouble = startling(previous, value, expect_change)
    if trouble and not why:
        return False, (
            "REFUSING TO RECORD %r = %s: %s" % (name, value, trouble) + LF
            + "A STARTLING MAGNITUDE IS A BUG REPORT until proven otherwise, in EITHER" + LF
            + "direction. Find out why it moved - or why it did not - and then say so:" + LF
            + "    python tools/guard/magnitude.py %s %s --why \"<what changed>\"" % (name, value))
    book[name] = {"value": value, "why": why or entry.get("why"),
                  "previous": previous}
    save(book)
    if trouble:
        return True, "recorded %r = %s (%s), explained: %s" % (name, value, trouble, why)
    return True, "recorded %r = %s" % (name, value)


def main(argv):
    if "--list" in argv:
        for name, entry in sorted(load().items()):
            print("%-28s %-10s %s" % (name, entry.get("value"), entry.get("why") or ""))
        return 0
    rest = [a for a in argv[1:] if a not in ("--expect-change",)]
    expect_change = "--expect-change" in argv
    why = None
    if "--why" in rest:
        at = rest.index("--why")
        why = " ".join(rest[at + 1:]).strip() or None
        rest = rest[:at]
    if len(rest) < 2:
        print(__doc__)
        return 2
    name = rest[0]
    try:
        value = int(rest[1])
    except ValueError:
        try:
            value = float(rest[1])
        except ValueError:
            print("REFUSING: %r is not a number, and an unmeasurable quantity is not a small "
                  "one" % rest[1])
            return 1
    ok, message = record(name, value, why, expect_change)
    print(message)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
