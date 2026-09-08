"""The stopping rule, as a command that exits non-zero.

WHAT THIS IS FOR. On 2026-08-17 thirteen audit rounds produced

    59, 79, 49, 48, 43, 29, 44, 47, 40, 39, 32, 35, 34

findings. Flat from round 3 -- because each round handed the next one 30-40 brand-new changes and
said "hunt here". The trend was visible by round 6 and nobody looked, because nothing made anyone
look. That is the failure this file exists to make impossible: not the flat trend, which is
information, but the trend going UNSEEN.

    python tools/guard/convergence.py add <scope> <count> [note]
    python tools/guard/convergence.py check [scope]
    python tools/guard/convergence.py show [scope]

`check` exits NON-ZERO when the last three rounds of a scope are flat or rising. A non-zero exit
means STOP AND REPORT. It does not mean run one more and hope.
"""
import io
import json
import os
import sys
import datetime

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LOG = os.path.join(ROOT, "docs", "audit-convergence.json")


def _load():
    if not os.path.exists(LOG):
        return {"rounds": []}
    return json.load(io.open(LOG, encoding="utf-8"))


def _save(data):
    io.open(LOG, "w", encoding="utf-8", newline="\n").write(
        json.dumps(data, indent=1, sort_keys=True) + "\n")


def add(scope, count, note=""):
    data = _load()
    data["rounds"].append({
        "scope": scope,
        "n": len([r for r in data["rounds"] if r["scope"] == scope]) + 1,
        "findings": int(count),
        "note": note,
        "recorded": datetime.datetime.now(datetime.timezone.utc)
                            .strftime("%Y-%m-%dT%H:%M:%SZ"),
    })
    _save(data)
    print("recorded: %s round %d, %s findings"
          % (scope, data["rounds"][-1]["n"], count))
    return 0


def _series(scope=None):
    data = _load()
    rows = [r for r in data["rounds"] if scope is None or r["scope"] == scope]
    return rows


def show(scope=None):
    rows = _series(scope)
    if not rows:
        print("no rounds recorded%s" % ("" if scope is None else " for %s" % scope))
        return 0
    by = {}
    for r in rows:
        by.setdefault(r["scope"], []).append(r)
    for name, rs in sorted(by.items()):
        counts = [r["findings"] for r in rs]
        print("%-22s %s" % (name, ", ".join(str(c) for c in counts)))
        for r in rs:
            if r.get("note"):
                print("    round %d: %s" % (r["n"], r["note"]))
    return 0


def check(scope=None):
    rows = _series(scope)
    by = {}
    for r in rows:
        by.setdefault(r["scope"], []).append(r)
    if not by:
        print("no rounds recorded - nothing to converge. Record round 1 before round 2.")
        return 0
    bad = False
    for name, rs in sorted(by.items()):
        counts = [r["findings"] for r in rs]
        print("%-22s %s" % (name, ", ".join(str(c) for c in counts)))
        if len(counts) < 3:
            print("    fewer than three rounds - no trend yet, and no claim of one")
            continue
        last3 = counts[-3:]
        falling = last3[0] > last3[1] > last3[2]
        if not falling:
            bad = True
            print("    NOT CONVERGING: %s. STOP AND REPORT." % " -> ".join(map(str, last3)))
            print("    A flat count measures how much code changed last round, not how healthy")
            print("    the codebase is. Two or three failed fixes on one piece of logic means")
            print("    the DESIGN is wrong - say so and ask, rather than writing fix number six.")
        else:
            print("    converging: %s" % " -> ".join(map(str, last3)))
    return 1 if bad else 0


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    cmd = argv[1]
    if cmd == "add":
        if len(argv) < 4:
            print("usage: convergence.py add <scope> <count> [note]")
            return 2
        return add(argv[2], argv[3], " ".join(argv[4:]))
    if cmd == "check":
        return check(argv[2] if len(argv) > 2 else None)
    if cmd == "show":
        return show(argv[2] if len(argv) > 2 else None)
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
