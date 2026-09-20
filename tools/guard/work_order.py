# -*- coding: utf-8 -*-
"""Refuse a long measurement while the thing being measured is still moving.

WHY THIS EXISTS. A mutation sweep measures a FROZEN tree: it records a digest of every file it
measured, and MutationBaselineTest goes red the moment one of them changes. So a sweep started
while work is still queued is not a slow measurement, it is a discarded one - five hours for a
baseline that is stale before it is written.

That rule was written down in this project twice - once as `measure-last`, once in PROPERTIES.md -
and broken anyway on 2026-09-11, in the same conversation where it was quoted. The reason it was
broken is the reason every defect in this repository gets fixed twice: it lived as an intention.
An intention is not a guard.

Two preconditions, both mechanical:

  1. OUTSTANDING.md has no open items. Open work means source that is going to move.
  2. `git status --porcelain` is clean. Uncommitted source IS source that is still moving, and a
     baseline keyed on digests of a dirty tree records a state no commit will ever match again.

The escape is `--anyway "<reason>"`. It is not a flag that hides the problem: the reason is
printed, and the caller is expected to put it somewhere a human will read. A decision that is
visible in the output is a different thing from a rule nobody applied.

    python tools/guard/work_order.py            # check; exit 1 when a measurement must not start
    python tools/guard/work_order.py --list     # just print the open items
"""
import io
import os
import re
import subprocess
import sys

LF = chr(10)
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LEDGER = os.path.join(ROOT, "OUTSTANDING.md")

#: An open item. A done one is `- [x]` and is deliberately kept.
_OPEN = re.compile(r"^\s*-\s*\[ \]\s*(.+?)\s*$", re.M)


def open_items(text=None):
    """The open items under `## Open`, first line of each, in file order.

    ONLY THAT SECTION, deliberately. A LIMITATION is not an item: "the script archive cannot
    be forked" can never be ticked, because it is not work anybody will do, and leaving it
    here would block every measurement this project ever runs again. Those live under
    `## Known limitations` and are closed by a refusal in the product instead.
    """
    if text is None:
        try:
            text = io.open(LEDGER, encoding="utf-8", errors="replace").read()
        except OSError:
            return []
    start = text.find("## Open")
    if start < 0:
        return []
    rest = text[start + len("## Open"):]
    nxt = rest.find(LF + "## ")
    return [m.group(1) for m in _OPEN.finditer(rest if nxt < 0 else rest[:nxt])]


def dirty_files():
    """Tracked files with uncommitted changes. Empty when git cannot be asked."""
    try:
        done = subprocess.run(["git", "status", "--porcelain"], cwd=ROOT,
                              capture_output=True, text=True)
    except OSError:                                    # pragma: no cover - git not on PATH
        return []
    out = []
    for line in done.stdout.splitlines():
        name = line[3:].strip()
        #the ledger itself and the baseline being written are not "source moving"
        if name and name not in ("OUTSTANDING.md", "mutation_baseline.json"):
            out.append(name)
    return out


def unproven_plants():
    """Why the plant ledger is not proof about THIS tree. Empty means it is.

    PLANT BEFORE YOU SWEEP, and this is what makes that a rule rather than a preference.
    A plant that SURVIVES means the guard does not hold, and fixing that is a SOURCE
    change - so the plant pass is source-changing work, and a frozen-tree measurement
    started before it is thrown away by the first survivor. It was stated here and then
    ordered against in the next sentence, with nothing anywhere to stop it.
    """
    record = os.path.join(ROOT, ".last-replant")
    try:
        import json
        body = json.load(io.open(record, encoding="utf-8"))
    except (OSError, ValueError):
        return ["the plant ledger has never been proven against this tree - run"
                " `python tools/guard/replant.py`, which records .last-replant"]
    if int(body.get("notProven") or 0) > 0:
        return ["%d plant(s) did NOT prove their guard on the last full run; a guard that"
                " does not hold is a source change, not a measurement"
                % int(body["notProven"])]
    try:
        sys.path.insert(0, os.path.join(ROOT, "tools"))
        import require_build
        now = require_build.tree_digest(os.path.join(ROOT, "src"))
    except Exception as cannotDigest:            # pragma: no cover
        return ["cannot digest src/ to check the plant proof is current (%s)"
                % cannotDigest]
    #: THE FILES THE PLANTS TARGET, and how many of them there were. src/ alone left 27
    #: plants invisible - every plant against a hook or a guard lives under tools/ - and a
    #: plant ADDED after a full run leaves notProven at 0, so the count has to be compared
    #: too. Both were found by stopping a run ten minutes in rather than two hours in.
    try:
        sys.path.insert(0, os.path.join(ROOT, "tools", "guard"))
        import replant
        book = replant.ledger()
        if body.get("targets") is None or body.get("plants") is None:
            return ["the recorded proof predates the target digest, so which files it covered"
                    " is UNKNOWN - re-run `python tools/guard/replant.py`"]
        if replant.target_digest(book) != body["targets"]:
            return ["a file the plants target has changed since they were proven - re-run"
                    " `python tools/guard/replant.py` before measuring"]
        if len(book.get("plants") or []) != body["plants"]:
            return ["the ledger holds %d plant(s) and the proof covered %d, so %d have never"
                    " been proven against anything"
                    % (len(book.get("plants") or []), body["plants"],
                       abs(len(book.get("plants") or []) - body["plants"]))]
    except Exception as cannotCheck:                 # pragma: no cover
        return ["cannot check the plant proof against the ledger (%s)" % cannotCheck]
    if now != body.get("src"):
        return ["src/ has changed since the plants were last proven (recorded %s, now %s)"
                " - re-run `python tools/guard/replant.py` before measuring"
                % (str(body.get("src"))[:12], now[:12])]
    return []


def blockers():
    """Why a long measurement must not start now. Empty means it may."""
    why = []
    items = open_items()
    if items:
        why.append("OUTSTANDING.md has %d open item(s):" % len(items))
        for it in items[:8]:
            why.append("    - " + (it if len(it) < 100 else it[:97] + "..."))
        if len(items) > 8:
            why.append("    (+%d more)" % (len(items) - 8))
    why.extend(unproven_plants())
    files = dirty_files()
    if files:
        why.append("%d file(s) have uncommitted changes, so the tree is still moving:" % len(files))
        for f in files[:8]:
            why.append("    " + f)
        if len(files) > 8:
            why.append("    (+%d more)" % (len(files) - 8))
    return why


def require_frozen(what, argv):
    """Refuses, loudly, unless the tree is frozen. Returns the --anyway reason, or None."""
    anyway = None
    if "--anyway" in argv:
        i = argv.index("--anyway")
        anyway = argv[i + 1] if i + 1 < len(argv) else ""
        if not anyway.strip():
            sys.stderr.write("REFUSING %s: --anyway must be followed by a reason." % what + LF)
            raise SystemExit(1)
    why = blockers()
    if not why:
        return None
    if anyway is not None:
        print("WORK ORDER OVERRIDDEN for %s: %s" % (what, anyway))
        for line in why:
            print("  " + line)
        print("  Recorded here because a decision that is not visible is not a decision.")
        return anyway
    sys.stderr.write("REFUSING %s: the tree is still moving, so this would measure a state" % what
                     + LF + "  no commit will ever match again." + LF)
    for line in why:
        sys.stderr.write("  " + line + LF)
    sys.stderr.write(LF
                     + "  Finish and commit the queue first - that is the `measure last` rule, and" + LF
                     + "  it was written down twice and broken anyway, which is why this refuses" + LF
                     + "  rather than reminds." + LF
                     + "  If it genuinely must run now:  --anyway \"<reason>\"" + LF)
    raise SystemExit(1)


def gate(what, anyway):
    """For callers that are not python: prints and returns 0 (may run) or 1 (refused).

    IT ASKS THE QUEUE, NOT THE TREE, and the difference matters. The mutation sweep
    measures a COMMIT - it records digests and MutationBaselineTest refuses a baseline
    whose files have moved - so a dirty tree invalidates it and require_frozen refuses
    both. The battery measures the WORKING TREE: verifying uncommitted work is the
    whole job, and refusing a dirty tree would make it impossible to check anything
    before committing it. What ruins a battery is starting one with work still queued,
    because the result is void the moment the next edit lands - so that is what this
    refuses on.
    """
    items = open_items()
    why = []
    if items:
        why.append("OUTSTANDING.md has %d open item(s):" % len(items))
        for it in items[:8]:
            why.append("    - " + (it if len(it) < 100 else it[:97] + "..."))
    if not why:
        return 0
    if anyway:
        print("WORK ORDER OVERRIDDEN for %s: %s" % (what, anyway))
        for line in why:
            print("  " + line)
        return 0
    print("REFUSING %s: there is queued work, so this measures a tree that is about to" % what)
    print("  move - and the result is void the moment the next edit lands.")
    for line in why:
        print("  " + line)
    print("")
    print("  Finish the queue first. That is the `measure last` rule, which was made")
    print("  mechanical for the mutation sweep and left as an intention for this one,")
    print("  and then broken here twice in one afternoon.")
    print("  If it genuinely must run now:  -Anyway \"<reason>\"")
    return 1


if __name__ == "__main__":
    if "--gate" in sys.argv:
        i = sys.argv.index("--gate")
        what = sys.argv[i + 1] if i + 1 < len(sys.argv) else "this measurement"
        reason = ""
        if "--anyway" in sys.argv:
            j = sys.argv.index("--anyway")
            reason = sys.argv[j + 1] if j + 1 < len(sys.argv) else ""
        raise SystemExit(gate(what, reason))
    if "--list" in sys.argv:
        for it in open_items():
            print("- " + it)
        raise SystemExit(0)
    why = blockers()
    for line in why:
        print(line)
    print("work order: " + ("BLOCKED" if why else "clear - a long measurement may start"))
    raise SystemExit(1 if why else 0)
