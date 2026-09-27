#!/usr/bin/env python3
"""Arm this checkout at session start: turn its git hooks ON, and say whether the bundle is there.

WHY THIS EXISTS, measured 2026-09-26. `core.hooksPath = .githooks` was set in `.git/config`, which a
clone never carries. So a fresh clone - the owner's cloud session, or anyone's - ran NO commit hook
at all: commit_guard, the bundle's commit gate, the stamp, every commit-time refusal this project
has, silently off, with `.githooks/` sitting in the repository looking armed. That is this
project's oldest shape: installed, present, and not wired, which reads exactly like working.

Git will not let a repository turn its own hooks on at clone time, and that is deliberate on git's
part. The chokepoint that remains is the session: Claude Code runs a SessionStart hook from the
repository's own `.claude/settings.json` before any work happens, so that is where this runs.

WHAT IT DOES:

  1. Sets `core.hooksPath` to `.githooks` if it is anything else, then READS IT BACK and requires
     the directory it names to hold a `commit-msg`. A value that was written is not a value that
     took, and a hooks path naming an empty directory is the armed-looking checkout again.
  2. Asks the bundle installer whether the verification bootstrap is reachable from here, and says
     so either way. Where it is not - a cloud machine has no Desktop - every commit will be refused
     by the install check, and a session that learns that at its first commit has already done the
     work it cannot land.

It never fails the session start: its answer is printed, and a SessionStart hook's output is handed
to the session, which is what has to know. A problem is printed as a problem, never as silence.

    python -B tools/arm_checkout.py [--root <checkout>]
"""
import io
import os
import subprocess
import sys

LF = chr(10)
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HOOKS_DIR = ".githooks"
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def _git(root, args):
    """(exit code, stdout stripped) - (None, why) when git could not be run at all."""
    try:
        done = subprocess.run(["git", "-C", root] + args, capture_output=True, text=True,
                              timeout=60, creationflags=NO_WINDOW)
    except (OSError, subprocess.SubprocessError) as exc:
        return None, "%s: %s" % (type(exc).__name__, exc)
    return done.returncode, (done.stdout or "").strip()


def arm_hooks(root):
    """(armed, what happened). Armed means READ BACK and pointing at a real commit-msg."""
    hook = os.path.join(root, HOOKS_DIR, "commit-msg")
    if not os.path.isfile(hook):
        return False, ("this checkout has no %s/commit-msg, so there is nothing to arm - every "
                       "commit-time guard is OFF" % HOOKS_DIR)
    code, current = _git(root, ["config", "--local", "--get", "core.hooksPath"])
    if code is None:
        return False, "git could not be run to read core.hooksPath (%s) - UNKNOWN" % current
    was = current if code == 0 else None
    if was != HOOKS_DIR:
        code, why = _git(root, ["config", "--local", "core.hooksPath", HOOKS_DIR])
        if code != 0:
            return False, "core.hooksPath could not be set (%s)" % why
    code, now = _git(root, ["config", "--local", "--get", "core.hooksPath"])
    if code != 0 or now != HOOKS_DIR:
        return False, ("core.hooksPath reads back as %r after being set to %r - the hooks are NOT "
                       "armed" % (now, HOOKS_DIR))
    if not os.path.isfile(os.path.join(root, now, "commit-msg")):
        return False, "core.hooksPath names %s, which holds no commit-msg" % now
    if was == HOOKS_DIR:
        return True, "git hooks already armed (core.hooksPath = %s)" % HOOKS_DIR
    return True, ("git hooks ARMED: core.hooksPath was %s, is now %s - a fresh clone runs no "
                  "commit hook until this is set" % (repr(was) if was else "unset", HOOKS_DIR))


def bundle_reachable(root):
    """(reachable, what happened), asked of the installer's OWN reader rather than a second one."""
    sys.path.insert(0, os.path.join(root, "tools"))
    try:
        import bundle_install
    except ImportError as exc:
        return False, "the bundle installer could not be loaded (%s) - reachability UNKNOWN" % exc
    finally:
        sys.path.pop(0)
    where, why = bundle_install.bundle_path(
        os.path.join(root, ".claude", "bundle-install.json"))
    if where:
        return True, "verification bootstrap reachable at %s" % where
    return False, ("the verification bootstrap is NOT reachable from here: %s. Until it is, the "
                   "install check refuses every commit in this checkout." % why)


def main(argv):
    root = ROOT
    if "--root" in argv:
        at = argv.index("--root")
        if at + 1 >= len(argv):
            sys.stderr.write("usage: arm_checkout.py [--root <checkout>]" + LF)
            return 2
        root = os.path.abspath(argv[at + 1])
    armed, said = arm_hooks(root)
    sys.stdout.write(("OK: " if armed else "PROBLEM: ") + said + LF)
    reached, told = bundle_reachable(root)
    sys.stdout.write(("OK: " if reached else "PROBLEM: ") + told + LF)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
