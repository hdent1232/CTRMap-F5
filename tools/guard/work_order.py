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

And a third, for the sweep (`require_frozen`), which --anyway does NOT reach: the commit the sweep
serves must not be refused for something the sweep cannot change - a pre-commit check that fails,
or an owed plant no tree here can drive (`release_blockers`). Only the owner's override passes it.

    python tools/guard/work_order.py            # check; exit 1 when a measurement must not start
    python tools/guard/work_order.py --list     # just print the open items
"""
import io
import os
import re
import subprocess
import sys
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)  # no console window for a captured child (F184)

LF = chr(10)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
import bounded         # noqa: E402  - a deadline ends the child's whole tree
ROOT = _tree_root.require(__file__)
LEDGER = os.path.join(ROOT, "OUTSTANDING.md")

#: What the gate says when it could not read the queue (F192).
UNREAD = ("OUTSTANDING.md cannot be read, or has no '## Open' section, so whether work is queued is UNKNOWN - which is not the same as nothing queued")

#: An open item. A done one is `- [x]` and is deliberately kept.
_OPEN = re.compile(r"^\s*-\s*\[ \]\s*(.+?)\s*$", re.M)


def open_items(text=None):
    """The open items under `## Open`, first line of each, in file order - or None when the queue
    cannot be read, or has no `## Open` section to read.

    NONE IS NOT EMPTY (F192). This returned [] for a file it could not open, and gate() then let a
    long measurement start with the queue unread - A QUERY THAT CANNOT READ ITS SUBJECT MUST NOT
    REPORT IT ABSENT, in the one tool rule_map names for two of this project's rules.

    ONLY THAT SECTION, deliberately. A LIMITATION is not an item: "the script archive cannot
    be forked" can never be ticked, because it is not work anybody will do, and leaving it
    here would block every measurement this project ever runs again. Those live under
    `## Known limitations` and are closed by a refusal in the product instead.
    """
    if text is None:
        try:
            with io.open(LEDGER, encoding="utf-8", errors="replace") as handle:
                text = handle.read()
        except OSError:
            return None
    start = text.find("## Open")
    if start < 0:
        return None
    rest = text[start + len("## Open"):]
    nxt = rest.find(LF + "## ")
    return [m.group(1) for m in _OPEN.finditer(rest if nxt < 0 else rest[:nxt])]


def dirty_files():
    """Tracked files with uncommitted changes, or None when git could not be asked or did not answer
    (not on PATH, timed out, not a repository): a git that cannot say is not a clean tree (F192)."""
    try:
        done = bounded.run(["git", "status", "--porcelain"], cwd=ROOT,
                           capture_output=True, text=True, timeout=60, creationflags=NO_WINDOW)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if done.returncode != 0:
        return None
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
        with io.open(record, encoding="utf-8") as handle:
            body = json.load(handle)
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


#: THE COMMIT A SWEEP SERVES IS ASKED BEFORE THE SWEEP (2026-10-18). Days of mutation sweeps for 1.0.4 ran while
#: its release commit could never land: the bundle drifted under the install check, and of the 1,578 plants two
#: past-the-gate commits owed, some were in no ledger any more and one planted into a deleted hook. Each refused
#: the commit whatever the sweep found, and each was found only after it. The owner: "WHY THE FUCK DID YOU JUST HAVE
#: ME WASTE SEVERAL DAYS IN REAL LIFE DOING SHIT LIKE SWEEPING KNOWING THERE WAS SHIT YOU STILL DIDNT DO". Every one
#: of those sweeps started with --anyway, so --anyway does not reach this list: only the owner's override does.
RELEASE_OVERRIDE = "ALLOW_RELEASE_BLOCKED_SWEEP"

#: A command the pre-commit hook runs, as the hook spells it: `"$PY" -B tools/x.py || exit 1`, `exec "$PY" ...`.
_HOOK_RUN = re.compile(r'^\s*(?:exec\s+)?"\$PY"\s+(?:-B\s+)?(tools/\S+\.py)((?:\s+--?[\w-]+)*)\s*(?:\|\||$)', re.M)


def precommit_checks(hook=None):
    """[[tool, arg...]] the pre-commit hook runs, read from the hook itself rather than typed here - None when the
    hook cannot be read or names nothing: an unread hook is not one that asks nothing."""
    try:
        with io.open(hook or os.path.join(ROOT, ".githooks", "pre-commit"), encoding="utf-8") as handle:
            text = handle.read()
    except OSError:
        return None
    found = [[m.group(1)] + m.group(2).split() for m in _HOOK_RUN.finditer(text)]
    return found or None


def failing_checks(checks=None):
    """Each pre-commit check that refuses this tree, said with its last line."""
    checks = precommit_checks() if checks is None else checks
    if checks is None:
        return ["the pre-commit hook cannot be read, so what the commit asks is UNKNOWN - which is not the same as"
                " nothing"]
    out = []
    for argv in checks:
        try:
            done = bounded.run([sys.executable, "-B"] + argv, cwd=ROOT, capture_output=True, text=True,
                               errors="replace", timeout=600, creationflags=NO_WINDOW)
        except (OSError, subprocess.SubprocessError) as exc:
            out.append("`%s` could not be asked (%s), so whether it refuses the commit is UNKNOWN"
                       % (" ".join(argv), exc))
            continue
        if done.returncode != 0:
            said = [l.strip() for l in ((done.stdout or "") + (done.stderr or "")).splitlines() if l.strip()]
            out.append("`%s` refuses the commit: %s" % (" ".join(argv), (said[-1] if said else
                                                                         "exit %d" % done.returncode)[:200]))
    return out


def undrivable_owed(root=None):
    """The plants the gate's owed record names that no tree here can drive - an id or key no ledger holds, or a
    plant whose file is gone. The commit refuses each one, whatever a measurement finds."""
    import json
    root = root or ROOT
    record_path = os.path.join(root, "tools", "guard", "gate_owed.json")
    if not os.path.isfile(record_path):
        return []
    try:
        with io.open(record_path, encoding="utf-8") as handle:
            record = json.load(handle)
        with io.open(os.path.join(root, "tools", "guard", "plants.json"), encoding="utf-8") as handle:
            own = {p.get("id"): p for p in json.load(handle).get("plants") or []}
        with io.open(os.path.join(root, "tests", "plants.json"), encoding="utf-8") as handle:
            bundle = json.load(handle).get("plants") or {}
    except (OSError, ValueError, AttributeError) as exc:
        return ["the owed record or a plant ledger cannot be read (%s), so whether every owed plant can be driven"
                " is UNKNOWN" % exc]
    try:
        #the gate's own answer: a plant the PARENT's ledger retired is not owed, and nothing could drive it
        import commit_guard
        retired = commit_guard.retired_at_parent() if root == ROOT else set()
    except Exception as exc:                      # noqa: BLE001 - CannotAsk, or a git that would not answer
        return ["which owed plants the parent retired cannot be read (%s), so whether every owed plant can be"
                " driven is UNKNOWN" % exc]
    gone = []
    for key, held, where in ([(p.get("id"), own.get(p.get("id")), "the project's ledger")
                              for p in record.get("plants") or [] if p.get("id") not in retired]
                             + [(k, bundle.get(k), "the bundle's ledger") for k in record.get("bundle_plants") or []]):
        if not isinstance(held, dict):
            gone.append("%s - in no plant of %s" % (key, where))
        elif held.get("file") and not os.path.exists(os.path.join(root, held["file"])):
            gone.append("%s - its file %s is gone" % (key, held["file"]))
    owed = len([p for p in record.get("plants") or [] if p.get("id") not in retired]) + len(record.get("bundle_plants") or [])
    out = []
    if owed:
        #A PLANT THAT WILL NOT REDDEN IS FOUND ONLY BY DRIVING IT. Of the 1,578 owed on 2026-10-18, 63 had rotted, 5
        #skip here, one hangs and one is an equivalent mutant - none visible to a reading of the ledger, each refusing
        #the commit whatever a sweep found. So the debt is driven before the sweep, never after it.
        out.append("%d plant(s) are owed by a commit made past the gate, and the commit refuses until each is driven"
                   " red against its tree - drive them first (tools/guard/drive_owed.py --workers 8): a plant that"
                   " will not redden is found only by driving it" % owed)
    if gone:
        out += (["%d owed plant(s) can be driven by no tree here, so the commit is refused whatever this measures:"
                 % len(gone)] + ["    " + g for g in gone[:8]] + (["    (+%d more)" % (len(gone) - 8)] if len(gone) > 8 else []))
    return out


def release_blockers():
    """Why the commit a measurement serves would be refused whatever the measurement finds. Empty: none."""
    return failing_checks() + undrivable_owed()


def _owner_allows_release_blocked():
    """The owner's own override, read where every override is read (bundle_env) - never --anyway."""
    sys.path.insert(0, os.path.join(ROOT, "tools"))
    import bundle_hooks
    sys.path.insert(0, bundle_hooks.path(ROOT))
    import bundle_env
    return bundle_env.allowed(RELEASE_OVERRIDE)


def blockers():
    """Why a long measurement must not start now. Empty means it may."""
    why = []
    items = open_items()
    if items is None:
        why.append(UNREAD)
    elif items:
        why.append("OUTSTANDING.md has %d open item(s):" % len(items))
        for it in items[:8]:
            why.append("    - " + (it if len(it) < 100 else it[:97] + "..."))
        if len(items) > 8:
            why.append("    (+%d more)" % (len(items) - 8))
    why.extend(unproven_plants())
    files = dirty_files()
    if files is None:
        why.append("git could not say whether the tree is clean, so whether it is still moving is "
                   "UNKNOWN - which is not the same as clean")
    elif files:
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
    blocked = release_blockers()
    if blocked:
        if not _owner_allows_release_blocked():
            sys.stderr.write("REFUSING %s: the commit it serves is refused whatever it measures, so it would be"
                             " hours or days spent before a decision that needs none of them." % what + LF)
            for line in blocked:
                sys.stderr.write("  " + line + LF)
            sys.stderr.write(LF + "  Clear these first, or put them to the owner. --anyway does not reach this list:"
                             " every sweep that wasted days on 1.0.4 was started with it." + LF)
            raise SystemExit(1)
        print("RELEASE BLOCKERS OVERRIDDEN BY THE OWNER for %s:" % what)
        for line in blocked:
            print("  " + line)
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
    if items is None:
        why.append(UNREAD)
    elif items:
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
    #a word this does not read refuses the run, before anything runs (cli_words, 2026-10-10)
    import _cli
    unread_words = _cli.refused(sys.argv[1:], flags=("--gate", "--anyway", "--list"),
                                valued=("--gate", "--anyway"), most=0)
    if unread_words:
        print(unread_words)
        raise SystemExit(2)
    if "--gate" in sys.argv:
        i = sys.argv.index("--gate")
        what = sys.argv[i + 1] if i + 1 < len(sys.argv) else "this measurement"
        reason = ""
        if "--anyway" in sys.argv:
            j = sys.argv.index("--anyway")
            reason = sys.argv[j + 1] if j + 1 < len(sys.argv) else ""
        raise SystemExit(gate(what, reason))
    if "--list" in sys.argv:
        listed = open_items()
        if listed is None:
            print(UNREAD)
            raise SystemExit(1)
        for it in listed:
            print("- " + it)
        raise SystemExit(0)
    why = blockers()
    for line in why:
        print(line)
    print("work order: " + ("BLOCKED" if why else "clear - a long measurement may start"))
    raise SystemExit(1 if why else 0)
