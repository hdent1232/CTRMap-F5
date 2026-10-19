#!/usr/bin/env python3
"""A PLANT ON DISK IS ITS OWN TEST'S, NEVER THE SESSION'S.

MEASURED 2026-10-07 in this bundle's own session: the commit gate drove a held plant in
`guard_fanout.py` - a defect laid in the live hooks folder for the seconds its one test runs - and
the session's next call, a plain `grep`, went through the dispatcher, which loaded every guard from
disk and so ran the mutant: it refused the grep as a launch it could not measure. That one failed
loud. A plant that EMPTIES a refusal fails quiet - the guard is off for the session for as long as
the plant is on disk, and a commit's gate drives every held plant of every file it changes, back
to back. Emptying the fan-out guard's refusal is one of them.

The plant's own test must see the mutant - that is the point of it - and the driver says which
process that is (`PLANTED`, see `bundle_rules.excused`). Every other process reads GIT'S INDEX,
which holds the file's true content for the whole drive: a plant is refused on a file whose
changes the index does not hold, and the restore after it is from the index.

THE LOCK IS READ AROUND EVERY FILE, NOT ONCE A CALL. The first version read it once when the
dispatcher started and refused the call if it had changed by the end: during the next commit's
gate, plants laid back to back crossed most calls, and most calls were refused to be asked again.
A plant takes the lock before it lays its defect and gives it back after the file is restored, and
it stands for seconds while one read takes microseconds: so a file read with no lock naming it
both before and after the read was not a plant, and anything else is read from the index. A lock
that cannot be read says any file may be the planted one, so then EVERY file is read from the
index - true, and nothing refused for it.

A LOADER, NOT A GATE. It decides which copy is read and raises `Planted` when the true one cannot
be had; what to do about that is the entry's. Imported FIRST by the two entries the harness runs -
`dispatch.py` and `after_rules.py` - and `begin` installs it in front of every import from the
hooks folder before any sibling is imported. Nothing here imports a sibling: the lock's name and
the variable naming a plant's test are read from their modules' SOURCE.

    begin(path, name)     an entry's first act: install the finder; hand a planted entry to its
                          true copy (`rerun`). Returns what could not be loaded, or None.
    true_text(path)       a hook file's true bytes, or `Planted`
    held(here)            the repo-relative hook file in `here` a plant holds, UNKNOWN, or ''

WHAT IT CANNOT REACH, said here so it is not mistaken for covered: this module itself, which is
imported off the disk before it can choose (a plant in it moves only plant handling), `bounded.py`,
which its one git call reads off the disk for the same reason (`_bounded`), and the few
milliseconds between the interpreter reading an entry and `rerun` asking the lock.
"""
import importlib.abc
import importlib.util
import io
import os
import re
import subprocess
import sys

#: What `held` answers when a lock is there and cannot be read: which file it names is UNKNOWN,
#: so every file is read as the index holds it.
UNKNOWN = "?"

#: A plant is seconds long; one `git show` of one file is milliseconds.
GIT_TIMEOUT = 20

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

#: Set once an entry has handed itself to its index copy, so the copy does not hand itself on -
#: under the project's own prefix, read from `bundle_env`'s source like every name here (`_name`).
RERUN = "PLANT_RERUN"


class Planted(Exception):
    """A hook file that may be a plant on disk and whose true copy git's index cannot give."""


def _constant(path, name, default):
    """A module-level string constant read from SOURCE - the module may be the one planted."""
    try:
        with io.open(path, encoding="utf-8", errors="replace") as handle:
            text = handle.read()
    except OSError:
        return default
    found = re.search(r'^%s\s*=\s*"([^"]*)"' % re.escape(name), text, re.M)
    return found.group(1) if found else default


def _root(here):
    return os.path.dirname(os.path.dirname(os.path.abspath(here)))


def _name(here, suffix):
    """`bundle_env.name(suffix)`, spelled from `bundle_env`'s SOURCE: it may be the one planted."""
    return "%s_%s" % (_constant(os.path.join(here, "bundle_env.py"), "PREFIX", "VERIFY"), suffix)


def _relative(path, start):
    """`path` from `start`, `/`-separated - `bundle_shell.relative`'s answer, asked here without
    importing it: this module decides which copy of `bundle_shell` the session loads, so it reads
    nothing that may be the planted one (as `_constant` reads constants from source). A path no
    relative path reaches - another drive - is outside, in the shape every caller reads as that."""
    try:
        return os.path.relpath(path, start).replace(os.sep, "/")
    except ValueError:
        return "../" + os.path.abspath(path).replace(os.sep, "/")


def rel_of(path):
    """`path` - a file in a hooks folder - as the lock names it: from the project root, `/`."""
    here = os.path.dirname(os.path.abspath(path))
    return _relative(os.path.abspath(path), _root(here))


def held(here):
    """The repo-relative `.py` in `here` a plant holds while this process is not that plant's
    test; UNKNOWN when a lock is there and cannot be read; '' otherwise."""
    root = _root(here)
    lock = _constant(os.path.join(here, "bundle_rules.py"), "LOCK", ".mutation-in-flight")
    try:
        with io.open(os.path.join(root, lock), encoding="utf-8", errors="replace") as handle:
            first = (handle.readline() or "").strip().replace(chr(92), "/")
    except FileNotFoundError:
        return ""
    except OSError:
        return UNKNOWN
    folder = _relative(os.path.abspath(here), root)
    if not first or not first.endswith(".py") or os.path.dirname(first) != folder:
        return ""
    if os.environ.get(_name(here, "PLANTED"), "").replace(chr(92), "/") == first:
        return ""                                  # this process IS the plant's test
    return first


def _planted(here, rel):
    """May `rel` be a plant on disk at this moment?"""
    return held(here) in (rel, UNKNOWN)


_BOUNDED = []


def _bounded(here):
    """`bounded` - the deadline that ends a child's whole tree - read OFF THE DISK beside this file.
    Not imported through the finder: the finder asks `source` for a file a plant holds, and `source`
    needs this, so a plant in `bounded.py` would ask for itself. A plant in it moves only plant
    handling, like a plant in this module (see WHAT IT CANNOT REACH)."""
    if not _BOUNDED:
        path = os.path.join(here, "bounded.py")
        spec = importlib.util.spec_from_file_location("_bundle_plant_bounded", path)
        built = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(built)
        _BOUNDED.append(built)
    return _BOUNDED[0]


def source(here, rel):
    """`rel`'s bytes in git's index, or None when git cannot say."""
    try:
        done = _bounded(here).run(["git", "-C", _root(here), "show", ":" + rel],
                                  capture_output=True, timeout=GIT_TIMEOUT, creationflags=NO_WINDOW)
    except (OSError, subprocess.SubprocessError):
        return None
    return done.stdout if done.returncode == 0 else None


def true_text(path):
    """The hook file at `path` as it truly is: the disk's bytes when no lock names it on either
    side of the read, git's index's otherwise. Raises `Planted` when the index cannot give them."""
    here = os.path.dirname(os.path.abspath(path))
    rel = rel_of(path)
    if not _planted(here, rel):
        with io.open(path, "rb") as handle:
            text = handle.read()
        if not _planted(here, rel):
            return text
    text = source(here, rel)
    if text is None:
        raise Planted("%s may be a plant on disk - a mutation run's lock %s - and git's index "
                      "cannot give its true copy" % (rel, "cannot be read" if held(here) == UNKNOWN
                                                     else "names it"))
    return text


def module(name, path, text):
    """(module, code) built from `text` and filed as `path`, so whatever it derives from its own
    place - the project root, its siblings - is what the file on disk would derive."""
    spec = importlib.util.spec_from_loader(name, loader=None, origin=path)
    built = importlib.util.module_from_spec(spec)
    built.__file__ = path
    return built, compile(text, path, "exec")


class _Loader(importlib.abc.Loader):
    def __init__(self, path):
        self.path = path

    def create_module(self, spec):
        return None

    def exec_module(self, built):
        built.__file__ = self.path
        exec(compile(true_text(self.path), self.path, "exec"), built.__dict__)


class Finder(importlib.abc.MetaPathFinder):
    """Every top-level import of a module in `here`, read through `true_text`."""

    def __init__(self, here):
        self.here = os.path.abspath(here)

    def find_spec(self, name, path=None, target=None):
        found = os.path.join(self.here, name + ".py")
        if "." in name or not os.path.isfile(found):
            return None
        return importlib.util.spec_from_loader(name, _Loader(found), origin=found)


def begin(path, name):
    """An entry's first act, before it imports any sibling: every import from its folder goes
    through `true_text`, and an entry that is itself planted hands the call to its true copy
    (`__main__` only). What could not be loaded, or None."""
    here = os.path.dirname(os.path.abspath(path))
    if not any(isinstance(f, Finder) and f.here == here for f in sys.meta_path):
        sys.meta_path.insert(0, Finder(here))
    return rerun(path) if name == "__main__" else None


def rerun(path):
    """When the entry file at `path` may itself be the one planted, run its index copy as
    `__main__` in its place, which exits. None when there is nothing to do; what could not be
    loaded when the true copy cannot be had."""
    here = os.path.dirname(os.path.abspath(path))
    mine, rerunning = rel_of(path), _name(here, RERUN)
    if not _planted(here, mine) or os.environ.get(rerunning) == mine:
        return None
    text = source(here, mine)
    if text is None:
        return ("%s may be planted by a mutation run and git's index cannot give its true copy, "
                "so it cannot answer as itself - wait for the run to finish" % mine)
    os.environ[rerunning] = mine
    built, code = module("__main__", os.path.abspath(path), text)
    sys.modules["__main__"] = built
    exec(code, built.__dict__)                      # the index's own copy of this entry
    sys.exit(0)
