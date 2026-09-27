#!/usr/bin/env python3
"""NOTHING A PROJECT TRACKS MAY NAME THE MACHINE IT WAS WRITTEN ON.

MEASURED, 2026-09-26, in two PUBLIC repositories this bundle is installed into. The owner's home
directory, their account name inside it, and the folders between it and the project had reached
tracked files: a census quoting its evidence by full path, two scripts with a hard-coded base
folder, a queue naming the session folder - and `_bundle`, which THIS bundle's installer wrote as
an absolute path into every project it installed into. One project's own home-path check existed
and missed every one: its list of extensions left out `.py`, it knew paths through a home
directory but not bare folder names, and it ran in the battery, after the commits were public.
Getting them out took a rewrite of the whole history and a force-push.

WHAT IT REFUSES, at the write of any text file and in any commit message:
  * THIS machine's home directory, in any spelling - either slash, any case, inside a URL;
  * two or more consecutive folders of the chain between that home and the project, joined by a
    separator ("<a>/<b>"), which is how a relative path or `~/...` still gives it away;
  * a folder of that chain whose name is DISTINCTIVE - it holds a space or a digit - on its own.
All of it DERIVED at the moment of asking from where this checkout sits, so the rule names nobody:
on another machine it refuses that machine's paths, and a clone whose home is `/root` and whose
project sits outside it has nothing to refuse.

WHAT IT LEAVES ALONE, on purpose. Somebody ELSE's home - a generic `C:/Users/someone` in a test,
an upstream author's IDE file - is not this machine, and refusing it would refuse every test that
proves a path check works. A folder that is an ordinary word ("Desktop", "sessions") alone is
prose, not an address.

A FINDING NEVER REPEATS WHAT IT FOUND. It says which kind of identifier sits on which line and
masks it, because a refusal is printed where it can be pasted into the next commit message.

Every write kind refuses GROWTH (bundle_rules), so a file already carrying one can still be edited
as long as the edit adds none - which is how a project scrubs itself a file at a time.

    python -B tools/owner_paths.py [files...]     report findings; exit 1 if there are any
"""
import io
import os
import re
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

LF = chr(10)
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

AT_WRITE = "findings"
AT_COMMIT = "message_findings"
#: Every tracked file that can hold text. Listed by what it EXCLUDES - binaries - rather than by
#: what it includes: the project check this replaces had a list of extensions, and `.py` was not
#: on it.
AT_SCOPE = (r"^(?!\.git/)(?!.*\.(?:png|jpe?g|gif|ico|bmp|webp|bin|bch|cmvd|dat|jar|class|dll|"
            r"so|dylib|exe|zip|gz|7z|xz|pdf|ttf|otf|woff2?)$).+$")

SEP = r"[\\/]+"


def _parts(path):
    """A path's components, its drive included, whichever separator it was written with."""
    drive, rest = os.path.splitdrive(os.path.normpath(path))
    parts = [p for p in re.split(r"[\\/]+", rest) if p]
    return ([drive] if drive else []), parts


def _distinctive(name):
    """A folder name that identifies rather than describes: it holds a space or a digit."""
    return len(name) >= 4 and bool(re.search(r"\s|\d", name))


def identifiers(home=None, root=None):
    """[(kind, pattern)] that name THIS machine, from its home directory and where `root` sits."""
    home = os.path.abspath(home or os.path.expanduser("~"))
    root = os.path.abspath(root or ROOT)
    found = []
    drive, parts = _parts(home)
    #: A home worth refusing names somebody: a folder of homes AND a name in it. `/root` does not.
    if len(parts) >= 2:
        pieces = [re.escape(p) for p in drive + parts]
        found.append(("home directory",
                      re.compile(SEP.join(pieces) + r"(?![\w-])", re.I)))
    try:
        inside = os.path.relpath(root, home)
    except ValueError:                                  # another drive: nothing between them
        inside = os.pardir
    if not inside.startswith(os.pardir) and inside != os.curdir:
        chain = [p for p in re.split(r"[\\/]+", inside) if p][:-1]   # the project's own name stays
        for a, b in zip(chain, chain[1:]):
            found.append(("folder chain",
                          re.compile(r"(?<![\w-])" + re.escape(a) + SEP + re.escape(b)
                                     + r"(?![\w-])", re.I)))
        for name in chain:
            if _distinctive(name):
                found.append(("folder name",
                              re.compile(r"(?<![\w-])" + re.escape(name) + r"(?![\w-])", re.I)))
    return found


def findings(rel, text, patterns=None):
    """One finding per line that names this machine - masked, never repeating what it found."""
    patterns = identifiers() if patterns is None else patterns
    out = []
    for n, line in enumerate((text or "").splitlines(), 1):
        for kind, pattern in patterns:
            if pattern.search(line):
                masked = line.strip()
                for other, p in patterns:
                    masked = p.sub("<%s>" % other, masked)
                out.append("%s:%d names this machine's %s: %s" % (rel, n, kind, masked[:140]))
                break
    return out


def message_findings(message, root):
    """The same, of a commit message - which GitHub publishes exactly like a file."""
    return findings("the commit message", message, identifiers(root=root or ROOT))


def main(argv):
    found = []
    for rel in argv[1:]:
        try:
            with io.open(rel, encoding="utf-8", errors="replace") as handle:
                found += findings(rel, handle.read())
        except OSError as exc:
            found.append("%s cannot be read (%s) - a file nobody can read is not a clean one"
                         % (rel, exc))
    for line in found:
        sys.stdout.write("REFUSING: " + line + LF)
    return 1 if found else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
