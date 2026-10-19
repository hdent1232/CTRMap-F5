#!/usr/bin/env python3
"""EVERY TRACKED TEXT FILE IS CHECKED OUT WITH THE LINE ENDINGS .gitattributes GIVES IT.

MEASURED, 2026-10-09: every text file in the index is LF, and with core.autocrlf on, this Windows
checkout held 622 of them CRLF and 89 LF - the LF ones written since by tools that write LF. Six
battery suites read a tracked file and compared it with LF text, and failed for it: whether a suite
passed depended on which group its file happened to be in, which no test said and no diff showed.

`.gitattributes` now gives every text file `eol=lf` (a batch file `eol=crlf`, for cmd.exe), so git
writes them that way on every checkout, on every machine. What git does not write - a Java tool's
println, a PowerShell Out-File, on Windows CRLF - can still put one back. The rules' own reading
treats line ends as no part of a file's text, so no write rule can see this; it is asked AT_AFTER,
the moment a call has changed the tree, and a file it left with the wrong ends is said at once.

    python -B tools/guard/lf_checkout.py [root]          exit 1 naming each file, 0 when none
    python -B tools/guard/lf_checkout.py --fix [root]    convert each one, then ask again

It asks GIT which endings a file should have (`git ls-files --eol`), so .gitattributes is the one
statement of the rule; and a text file git governs with no `eol` at all is a finding too, so a
.gitattributes that lost the line reads as every file unfixed rather than as a clean tree. A git that
cannot be asked is exit 2 - UNKNOWN, never clean. --fix rewrites only the line ends, of a file whose
index is LF, so `git diff` of every file is what it was. `ctrmap.tests.RuleRefusalsTest` runs it over
a scratch repository both ways, and over this tree.
"""
import os
import subprocess
import sys

if os.path.dirname(os.path.abspath(__file__)) not in sys.path:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bounded  # noqa: E402  - a deadline ends the child's whole tree

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
FLAGS = ("--fix",)
UNKNOWN = 2

AT_AFTER = "after"


def entries(root):
    """[(path, index, worktree, attr)] for every tracked file, as `git ls-files --eol` gives it.
    Raises RuntimeError when git cannot be asked - never an empty list for a tree it could not read."""
    done = bounded.run(["git", "-C", root, "ls-files", "--eol", "-z"], capture_output=True,
                       timeout=120, creationflags=NO_WINDOW)
    if done.returncode != 0:
        raise RuntimeError("git ls-files --eol failed in %s: %s"
                           % (root, done.stderr.decode("utf-8", "replace").strip()))
    out = []
    for record in done.stdout.decode("utf-8", "replace").split("\0"):
        if not record:
            continue
        head, _, path = record.partition("\t")
        fields = head.split(None, 2)
        if len(fields) < 3 or not path:
            raise RuntimeError("git ls-files --eol gave a line this cannot read: %r" % record[:120])
        out.append((path, fields[0][2:], fields[1][2:], fields[2][5:].strip()))
    return out


def wanted(attr):
    """'lf', 'crlf', or None when the attributes give this file no line ending."""
    for word in attr.split():
        if word in ("eol=lf", "eol=crlf"):
            return word[4:]
    return None


def findings(rows):
    """(path, why) for every tracked text file whose working copy is not what .gitattributes gives."""
    out = []
    for path, index, work, attr in rows:
        if index in ("-text", "none", "") or work in ("-text", "none", ""):
            continue                     # binary, empty, or not in the working tree
        want = wanted(attr)
        if want is None:
            out.append((path, "a text file .gitattributes gives no line ending (attr/%s)" % attr))
        elif work != want:
            out.append((path, "checked out %s, and .gitattributes gives it %s" % (work, want)))
    return out


def after(root):
    """The AT_AFTER verdict: one line per file the tree now holds with the wrong line ends."""
    rows = entries(root)
    if not rows:
        raise RuntimeError("git lists no tracked file in %s - a tree with nothing to read is not a clean one" % root)
    return ["%s: %s - `python -B tools/guard/lf_checkout.py --fix`" % (path, why) for path, why in findings(rows)]


def convert(data, want):
    """The same text with every line end `want`'s: only line ends change."""
    lf = data.replace(b"\r\n", b"\n")
    return lf if want == "lf" else lf.replace(b"\n", b"\r\n")


def main(argv):
    #a word this does not read refuses the run, before anything runs (cli_words, 2026-10-10)
    import _cli
    unread_words = _cli.refused(argv[1:], flags=FLAGS, most=1)
    if unread_words:
        print(unread_words)
        return 2
    words = argv[1:]
    places = [w for w in words if not w.startswith("-")]
    root = os.path.abspath(places[0] if places else os.getcwd())
    try:
        rows = entries(root)
    except (RuntimeError, OSError, subprocess.SubprocessError) as exc:
        print("UNKNOWN: which files are checked out with which line endings cannot be asked (%s)" % exc)
        return UNKNOWN
    if not rows:
        print("UNKNOWN: git lists no tracked file in %s - a tree with nothing to read is not a clean one" % root)
        return UNKNOWN
    found = findings(rows)
    if "--fix" in words and found:
        ungoverned = [p for p, why in found if "gives no line ending" in why]
        if ungoverned:
            print("REFUSING to convert: %d text file(s) have no eol in .gitattributes (%s) - put the rule"
                  " back before converting to it" % (len(ungoverned), ", ".join(ungoverned[:3])))
            return 1
        want_of = {path: wanted(attr) for path, _i, _w, attr in rows}
        for path, _why in found:
            target = os.path.join(root, *path.split("/"))
            with open(target, "rb") as handle:
                data = handle.read()
            with open(target, "wb") as handle:
                handle.write(convert(data, want_of[path]))
        print("converted %d file(s); asking again" % len(found))
        found = findings(entries(root))
    for path, why in found:
        print("  %s: %s" % (path, why))
    if found:
        print("%d of %d tracked file(s) are not checked out as .gitattributes says - run"
              " `python -B tools/guard/lf_checkout.py --fix`" % (len(found), len(rows)))
        return 1
    print("every one of %d tracked file(s) is checked out as .gitattributes says" % len(rows))
    return 0


if __name__ == "__main__":
    sys.dont_write_bytecode = True
    raise SystemExit(main(sys.argv))
