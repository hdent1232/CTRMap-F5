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
import json
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


def _declarations(root):
    """The project's bundle declarations, or None when they cannot be read."""
    try:
        with io.open(os.path.join(root, ".claude", "bundle-install.json"), encoding="utf-8") as handle:
            held = json.load(handle)
    except (OSError, ValueError):
        return None
    return held if isinstance(held, dict) else None


def bundle_remote(root, repo):
    """The bundle's repository BESIDE this checkout's own origin - same host, same owner, the
    bundle's name - or None with no origin. Derived, so no public file has to spell the address."""
    code, url = _git(root, ["remote", "get-url", "origin"])
    if code != 0 or not url:
        return None
    head = url.rstrip("/")
    tail = ".git" if head.endswith(".git") else ""
    head = head[:-len(tail)] if tail else head
    cut = max(head.rfind("/"), head.rfind(":"), head.rfind(chr(92)))
    return (head[:cut + 1] + repo + tail) if cut >= 0 else None


def fetch_bundle(root):
    """(fetched, what happened): clone the bundle to where `_bundle_fetch` says.

    WHY, measured 2026-09-26: a cloud clone of this project has no copy of the bundle on its
    machine, so the install check refused every commit there - correctly, since an install nobody
    can check is not an installed one. `_bundle_fetch` names the bundle's repository and where to
    put it; that place must be one of the `_bundle` entries, or the clone would sit where nothing
    looks. It goes BESIDE the checkout, not inside it: the bundle's own tools walk folders, and a
    copy of them nested in this project's tree would be read as this project's.
    """
    held = _declarations(root)
    if held is None:
        return False, "the bundle declarations cannot be read, so where to fetch it is UNKNOWN"
    fetch = held.get("_bundle_fetch")
    if not isinstance(fetch, dict) or not fetch.get("repo") or not fetch.get("into"):
        return False, "no `_bundle_fetch` is declared, so there is nowhere to fetch the bundle from"
    entries = held.get("_bundle")
    entries = [entries] if isinstance(entries, str) else entries if isinstance(entries, list) else []
    if fetch["into"] not in entries:
        return False, ("`_bundle_fetch` would put the bundle at %s, which `_bundle` does not list"
                       % fetch["into"])
    target = os.path.normpath(os.path.join(root, fetch["into"]))
    if os.path.exists(target):
        return False, "%s already exists and is not the bundle" % fetch["into"]
    remote = bundle_remote(root, fetch["repo"])
    if remote is None:
        return False, "this checkout has no origin to find the bundle's repository beside"
    try:
        done = subprocess.run(["git", "clone", "--quiet", remote, target], capture_output=True,
                              text=True, timeout=300, creationflags=NO_WINDOW)
    except (OSError, subprocess.SubprocessError) as exc:
        return False, "git could not be run to fetch the bundle (%s)" % exc
    if done.returncode != 0:
        return False, ("fetching the bundle from its repository failed - on a cloud machine this "
                       "usually means its GitHub access does not reach that repository: %s"
                       % (done.stderr or "").strip()[-200:])
    return True, "fetched the bundle into %s from its repository beside this one's" % fetch["into"]


def bundle_reachable(root):
    """(reachable, what happened), asked of the installer's OWN reader rather than a second one -
    and when nothing it declares resolves, the bundle is fetched to where it says, then asked
    again. The answer names the bundle's place relative to this checkout, never as an absolute
    path, since this is printed where it can be pasted into the next commit message."""
    sys.path.insert(0, os.path.join(root, "tools"))
    try:
        import bundle_install
    except ImportError as exc:
        return False, "the bundle installer could not be loaded (%s) - reachability UNKNOWN" % exc
    finally:
        sys.path.pop(0)
    declarations = os.path.join(root, ".claude", "bundle-install.json")
    where, why = bundle_install.bundle_path(declarations)
    if where:
        return True, "verification bootstrap reachable at %s" % _shown(root, where)
    fetched, how = fetch_bundle(root)
    if fetched:
        where, why = bundle_install.bundle_path(declarations)
        if where:
            return True, how
        return False, "%s, and it still does not resolve: %s" % (how, why)
    return False, ("the verification bootstrap is NOT reachable from here, and was not fetched: %s. "
                   "Until it is, the install check refuses every commit in this checkout." % how)


def _shown(root, path):
    """A place as it relates to this checkout, not as an absolute path through a home directory."""
    try:
        return os.path.relpath(path, root).replace(os.sep, "/")
    except ValueError:
        return os.path.basename(path)


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
