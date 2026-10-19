#!/usr/bin/env python3
"""IS A BATTERY RUNNING? One answer, for every reader of build/.battery-running.

MEASURED 2026-10-11: four readers, four rules. test.ps1 asked whether the pid the lock names was
alive, by a spelling of its own; build.ps1 asked only how old the file was, so a battery past ninety
minutes would have build/classes deleted under it and a killed one blocked every build for ninety;
replant.py asked only whether the file existed, so a killed battery blocked every plant build until
someone deleted it by hand; and the hook that refuses an edit during a run asked
`bundle_runs.lock_runs`. Whether a battery was running depended on who asked.

Every one of them asks this now, and this asks the hook's rule - `bundle_runs.lock_runs`, handed
this one lock. A lock naming a process holds while that process is alive or cannot be told dead;
one naming none holds for ninety minutes after it was written; one that cannot be read holds.
Unknown is never a finished run.

    python -B tools/guard/battery_lock.py            says what holds the lock and exits 1; 0 when nothing does
    python -B tools/guard/battery_lock.py --classes  the same for anything running out of build/classes -
                                                     the battery, or the editor (build/.editor-running)

Exit 2 when the question cannot be asked at all, and a reader treats 2 as it treats 1.

THE EDITOR TOO, for whatever deletes build/classes (2026-10-11): run from this checkout it loads its classes
from there as it needs them, so a build under it takes them away from an open window. It declares itself as
the battery does (ctrmap.RunningFrom); a build asks --classes, while the battery's own one-at-a-time check
asks only about batteries - an open editor does not stop a test run.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
ROOT = _tree_root.require(__file__)

#: The lock test.ps1 writes while it runs, as `bundle_runs.LOCKS` names it.
LOCK = ("build/.battery-running", "the battery")
#: The lock the editor writes while it runs out of build/classes (ctrmap.RunningFrom.LOCK).
EDITOR = ("build/.editor-running", "the editor")


def holders(root=ROOT, classes=False):
    """[what holds the battery's lock under `root` - and, with `classes`, the editor's too]; empty when
    nothing does. Raises when it cannot ask - the bundle's reader is found under this project, wherever
    the lock being asked about is."""
    sys.path.insert(0, os.path.join(ROOT, "tools"))
    import bundle_hooks
    sys.path.insert(0, bundle_hooks.path(ROOT))
    import bundle_runs
    return bundle_runs.lock_runs(root, (LOCK, EDITOR) if classes else (LOCK,))


def main(argv):
    #a word this does not read refuses the run, before anything runs (cli_words, 2026-10-10)
    import _cli
    unread_words = _cli.refused(argv[1:], flags=("--classes",), most=0)
    if unread_words:
        print(unread_words)
        return 2
    try:
        held = holders(classes="--classes" in argv)
    except Exception as cannotAsk:          # noqa: BLE001 - any failure to ask is UNKNOWN
        print("UNKNOWN whether a battery is running: the lock's reader could not be asked (%s: %s)"
              % (type(cannotAsk).__name__, cannotAsk))
        return 2
    for what in held:
        print("RUNNING: " + what)
    if not held:
        print("nothing runs out of build/classes" if "--classes" in argv else "no battery holds " + LOCK[0])
    return 1 if held else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
