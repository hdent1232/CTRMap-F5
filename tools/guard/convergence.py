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
    python tools/guard/convergence.py close <scope> <reason, 80 characters at least>

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
    with io.open(LOG, encoding="utf-8") as handle:
        return json.load(handle)


def _save(data):
    with io.open(LOG, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(
            json.dumps(data, indent=1, sort_keys=True) + "\n")


def add(scope, count, note=""):
    data = _load()
    data["rounds"].append({
        "scope": scope,
        "n": len([r for r in data["rounds"] if r["scope"] == scope and "closed" not in r]) + 1,
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
        counts = [r["findings"] for r in rs if "closed" not in r]
        print("%-22s %s" % (name, ", ".join(str(c) for c in counts)))
        for r in rs:
            if "closed" in r:
                print("    CLOSED %s: %s" % (r.get("recorded", ""), r["closed"]))
            elif r.get("note"):
                print("    round %d: %s" % (r["n"], r["note"]))
    return 0


def _trend():
    """The bundle's guard_agent_campaign, loaded by its path: its `not_converging` is THE trend,
    asked at every agent launch. This computed its own (F198): it judged every scope while the
    hook judged only the newest, so one round under a new scope name let the launch through while
    this said STOP - two answers to one stopping rule, and the one at the act was the weaker."""
    import importlib.util
    sys.path.insert(0, os.path.join(ROOT, "tools"))
    import bundle_hooks
    folder = bundle_hooks.path(ROOT)
    sys.path.insert(0, folder)
    spec = importlib.util.spec_from_file_location("ctrmap_agent_campaign",
                                                  os.path.join(folder, "guard_agent_campaign.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


#: ASKED AT THE WRITE OF THE LOG ITSELF, by whatever writes it: a closure with no real reason, or a
#: row the trend cannot read, is refused where it lands - not found later by a check nobody ran.
AT_SCOPE = r"^docs/audit-convergence\.json$"
AT_RECORD = "log_problems"


def log_problems(path, payload):
    """[findings] for the round log as a write would leave it: every row readable by THE trend, and
    every closure reasoned. Unreadable is a finding, never a pass."""
    rows = payload.get("rounds") if isinstance(payload, dict) else None
    if not isinstance(rows, list):
        return ["%s holds no `rounds` list the trend can read" % os.path.basename(path)]
    try:
        _trend().not_converging(rows)
    except (ValueError, KeyError, TypeError, AttributeError) as exc:
        return ["%s: a row the trend cannot read (%s: %s)" % (os.path.basename(path), type(exc).__name__, exc)]
    return []


def close(scope, reason):
    """Lets a scope go by DECISION: a row the trend reads as closed until a round re-opens it."""
    trend = _trend()
    if len(reason.strip()) < trend.CLOSE_REASON:
        print("REFUSING: a closing reason is %d characters at least - a short one is a rename by"
              " another spelling" % trend.CLOSE_REASON)
        return 2
    data = _load()
    data["rounds"].append({"scope": scope, "closed": reason.strip(),
                           "recorded": datetime.datetime.now(datetime.timezone.utc)
                           .strftime("%Y-%m-%dT%H:%M:%SZ")})
    _save(data)
    print("closed: %s" % scope)
    return 0


def check(scope=None):
    try:
        rows = _series(scope)
        bad = dict(_trend().not_converging(rows))
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as exc:
        #the hook refuses an unreadable log in words; this raised a KeyError on one
        print("the convergence log at %s cannot be read (%s: %s) - an unmeasurable trend is not a"
              " falling one. STOP AND REPORT." % (LOG, type(exc).__name__, exc))
        return 1
    by = {}
    for r in rows:
        if "closed" in r:
            by.setdefault(r["scope"], [])
            continue
        by.setdefault(r["scope"], []).append(r)
    if not by:
        print("no rounds recorded - nothing to converge. Record round 1 before round 2.")
        return 0
    for name, rs in sorted(by.items()):
        counts = [r["findings"] for r in rs]
        print("%-22s %s" % (name, ", ".join(str(c) for c in counts)))
        if name in bad:
            print("    NOT CONVERGING: %s. STOP AND REPORT." % " -> ".join(map(str, bad[name])))
            print("    A flat count measures how much code changed last round, not how healthy")
            print("    the codebase is. Two or three failed fixes on one piece of logic means")
            print("    the DESIGN is wrong - say so and ask, rather than writing fix number six.")
        elif len(counts) < 3:
            print("    fewer than three rounds since it opened, or closed - no trend asked of it")
        else:
            print("    converging: %s" % " -> ".join(map(str, counts[-3:])))
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
    if cmd == "close":
        if len(argv) < 4:
            print("usage: convergence.py close <scope> <reason, 80 characters at least>")
            return 2
        return close(argv[2], " ".join(argv[3:]))
    if cmd == "show":
        return show(argv[2] if len(argv) > 2 else None)
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
