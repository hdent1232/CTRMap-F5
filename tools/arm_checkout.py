#!/usr/bin/env python3
"""Arm this checkout at session start: turn its git hooks ON, and bring the bundle and the notes.

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
  2. Asks the bundle installer whether the verification bootstrap is reachable from here, and
     FETCHES it beside the checkout when nothing declared resolves - a cloud machine has no
     Desktop, and without the bundle the install check refuses every commit.
  3. Brings the owner's private NOTES - Claude Code's memory for this project - onto a machine that
     has none: fetches the notes repository beside the checkout and imports it into this session's
     memory folder, which it derives from the transcript path Claude Code hands this hook. Notes
     already on the machine are never overwritten; how they differ from the repository is said.

It never fails the session start: its answer is printed, and a SessionStart hook's output is handed
to the session, which is what has to know. A problem is printed as a problem, never as silence.

    python -B tools/arm_checkout.py [--root <checkout>] [--memory <memory folder>]
"""
import io
import json
import os
import subprocess
import sys
import threading

LF = chr(10)
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HOOKS_DIR = ".githooks"
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)

#: The owner's private notes: which repository, beside this one's origin, and where it goes. Both
#: the fetch and the import read this one constant, so they cannot disagree about the place.
NOTES = {"repo": "ctrmap-claude-memory", "into": "../claude-memory"}
MEMORY_INDEX = "MEMORY.md"

#: The long waits this tool can make, in seconds. The SessionStart entry in .claude/settings.json
#: must outlast ALL of them together: a hook killed mid-clone leaves a half-made folder where the
#: clone goes, and every later session is then refused with "already exists".
INPUT_WAIT = 10
GIT_TIMEOUT = 60
CLONE_TIMEOUT = 180
SYNC_TIMEOUT = 120
GIT_CALLS = 5        # arm_hooks reads, sets, reads back; each of two clones asks for origin
CLONES = 2           # the bundle, the notes
SYNCS = 1            # import on a machine without notes, status on one with them - never both
LONGEST_RUN = (INPUT_WAIT + GIT_CALLS * GIT_TIMEOUT + CLONES * CLONE_TIMEOUT
               + SYNCS * SYNC_TIMEOUT)


def _git(root, args):
    """(exit code, stdout stripped) - (None, why) when git could not be run at all."""
    try:
        done = subprocess.run(["git", "-C", root] + args, capture_output=True, text=True,
                              timeout=GIT_TIMEOUT, creationflags=NO_WINDOW)
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


def remote_beside(root, repo):
    """The repository `repo` BESIDE this checkout's own origin - same host, same owner - or None
    with no origin. Derived, so no public file has to spell the address."""
    code, url = _git(root, ["remote", "get-url", "origin"])
    if code != 0 or not url:
        return None
    head = url.rstrip("/")
    tail = ".git" if head.endswith(".git") else ""
    head = head[:-len(tail)] if tail else head
    cut = max(head.rfind("/"), head.rfind(":"), head.rfind(chr(92)))
    return (head[:cut + 1] + repo + tail) if cut >= 0 else None


def clone_beside(root, repo, into):
    """(cloned, what happened): clone `repo`, found beside this checkout's origin, to `into`."""
    target = os.path.normpath(os.path.join(root, into))
    if os.path.exists(target):
        return False, "%s already exists and is not a clone of %s" % (into, repo)
    remote = remote_beside(root, repo)
    if remote is None:
        return False, "this checkout has no origin to find %s beside" % repo
    try:
        done = subprocess.run(["git", "clone", "--quiet", remote, target], capture_output=True,
                              text=True, timeout=CLONE_TIMEOUT, creationflags=NO_WINDOW)
    except (OSError, subprocess.SubprocessError) as exc:
        return False, "git could not be run to fetch %s (%s)" % (repo, exc)
    if done.returncode != 0:
        return False, ("fetching %s failed - on a cloud machine this usually means its GitHub "
                       "access does not reach that repository: %s"
                       % (repo, (done.stderr or "").strip()[-200:]))
    return True, "fetched %s into %s from its repository beside this one's" % (repo, into)


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
    return clone_beside(root, fetch["repo"], fetch["into"])


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


def session_input(stream=None, wait=INPUT_WAIT):
    """The JSON Claude Code hands this hook: {} when there is none to read (a terminal), None when
    there was input and it could not be read in time or parsed - which is UNKNOWN, not empty.

    Read as the UTF-8 BYTES the harness wrote, not in the locale's code page: `sys.stdin.read()`
    decodes a Windows pipe as cp1252, and a transcript path with one character outside ASCII then
    names a memory folder that does not exist - the notes land where no session looks. The bundle
    measured the same thing for every guard's payload (bundle_shell.payload_text)."""
    stream = sys.stdin if stream is None else stream
    if stream is None:
        return {}
    try:
        if stream.isatty():
            return {}
    except (AttributeError, ValueError, OSError):
        return {}
    buffer = getattr(stream, "buffer", None)
    read = ((lambda: buffer.read().decode("utf-8", errors="replace")) if buffer is not None
            else stream.read)
    box = []
    reader = threading.Thread(target=lambda: box.append(read()))
    reader.daemon = True
    reader.start()
    reader.join(wait)
    if not box:
        return None
    try:
        held = json.loads(box[0] or "{}")
    except ValueError:
        return None
    return held if isinstance(held, dict) else None


def memory_folder(payload):
    """This session's memory folder, derived from the transcript path Claude Code hands its hooks:
    the memory lives in `memory/` beside the transcripts. None when no transcript path came."""
    transcript = (payload or {}).get("transcript_path")
    if not transcript:
        return None
    return os.path.join(os.path.dirname(os.path.abspath(transcript)), "memory")


def _sync(tool, mode, memory):
    """(exit code, output lines) of the notes repository's OWN sync tool - never a second copy of
    its rules here - or (None, [why]) when it could not be run."""
    try:
        done = subprocess.run([sys.executable, "-B", tool, mode, "--memory", memory],
                              capture_output=True, text=True, timeout=SYNC_TIMEOUT,
                              creationflags=NO_WINDOW)
    except (OSError, subprocess.SubprocessError) as exc:
        return None, ["the notes' sync tool could not be run (%s)" % exc]
    said = ((done.stdout or "") + (done.stderr or "")).splitlines()
    #: The first line names both folders by absolute path; this output is handed to the session,
    #: where it can be pasted into a commit message, so it is not relayed.
    return done.returncode, [line for line in said[1:] if line.strip()]


def bring_notes(root, memory):
    """(ok, lines): the owner's notes into this session's memory folder, on a machine without them.

    WHY, measured 2026-09-26: the notes - the format research, the build recipe, the reverse-
    engineering record, the standing rules as first written - existed only on the owner's machine,
    so a cloud session started without any of them, and the owner was told to clone and import by
    hand. Notes ALREADY on the machine are never overwritten: which side is newer cannot be told
    from here, and a note written locally and not yet exported exists nowhere else.
    """
    if memory is None:
        return False, ["this session's memory folder is UNKNOWN - no transcript path reached this "
                       "hook - so the notes were not brought in"]
    notes = os.path.normpath(os.path.join(root, NOTES["into"]))
    tool = os.path.join(notes, "sync.py")
    lines = []
    if not os.path.isfile(tool):
        cloned, how = clone_beside(root, NOTES["repo"], NOTES["into"])
        if not cloned:
            return False, ["the notes are NOT on this machine and were not fetched: %s" % how]
        if not os.path.isfile(tool):
            return False, ["%s was fetched and holds no sync.py" % NOTES["into"]]
        lines.append(how)
    if os.path.isfile(os.path.join(memory, MEMORY_INDEX)):
        code, said = _sync(tool, "status", memory)
        return code == 0, lines + ["notes already on this machine, left as they are; against the "
                                   "notes repository at %s:" % NOTES["into"]] + said
    try:
        #: The folder is Claude Code's own, derived from its transcript path; a notes repository
        #: of any age can then import into it, including one whose sync predates "not there yet".
        os.makedirs(memory, exist_ok=True)
    except OSError as exc:
        return False, lines + ["this session's memory folder could not be made (%s)" % exc]
    code, said = _sync(tool, "import", memory)
    if code != 0:
        return False, lines + ["importing the notes FAILED, exit %s:" % code] + said
    try:
        with io.open(os.path.join(memory, MEMORY_INDEX), encoding="utf-8") as handle:
            index = handle.read().splitlines()
    except OSError as exc:
        return False, lines + said + ["the notes were imported and their index cannot be read "
                                      "(%s)" % exc]
    return True, lines + said + [
        "the notes are in this session's memory folder. The session may have loaded its memory "
        "before this hook ran, so their index follows:"] + index + [
        "Notes written in this session go back with `python -B %s/sync.py export --memory <this "
        "session's memory folder>`, then a commit and a push there, with the owner's approval for "
        "that push." % NOTES["into"]]


def _shown(root, path):
    """A place as it relates to this checkout, not as an absolute path through a home directory."""
    try:
        return os.path.relpath(path, root).replace(os.sep, "/")
    except ValueError:
        return os.path.basename(path)


def _say(text):
    """One line to the session, as UTF-8 bytes. NOT in the pipe's code page: stdout on a Windows
    pipe is cp1252 and STRICT, and measured 2026-09-26 the notes' index - which holds arrows -
    raised UnicodeEncodeError halfway through, so the hook exited 1 and the session was shown
    nothing it was meant to see. stderr would have survived (Python writes it with
    backslashreplace); stdout does not."""
    data = (text + LF).encode("utf-8")
    buffer = getattr(sys.stdout, "buffer", None)
    if buffer is None:
        sys.stdout.write(data.decode("utf-8"))
        return
    sys.stdout.flush()
    buffer.write(data)
    buffer.flush()


def main(argv):
    root = ROOT
    memory = None
    for flag in ("--root", "--memory"):
        if flag in argv:
            at = argv.index(flag)
            if at + 1 >= len(argv):
                sys.stderr.write("usage: arm_checkout.py [--root <checkout>] [--memory <folder>]" + LF)
                return 2
            if flag == "--root":
                root = os.path.abspath(argv[at + 1])
            else:
                memory = os.path.abspath(argv[at + 1])
    armed, said = arm_hooks(root)
    _say(("OK: " if armed else "PROBLEM: ") + said)
    reached, told = bundle_reachable(root)
    _say(("OK: " if reached else "PROBLEM: ") + told)
    if memory is None:
        payload = session_input()
        if payload is None:
            _say("PROBLEM: this hook's input could not be read, so this session's memory folder "
                 "is UNKNOWN and the notes were not brought in")
            return 0
        memory = memory_folder(payload)
    brought, lines = bring_notes(root, memory)
    _say(("OK: " if brought else "PROBLEM: ") + (lines[0] if lines else ""))
    for line in lines[1:]:
        _say(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
