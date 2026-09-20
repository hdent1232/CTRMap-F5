#!/usr/bin/env python3
"""COUNT WHAT YOU EXPECT BEFORE RUNNING THE CHANGE.

WHAT THIS COST, from this project's own rules, and every instance is the same shape:

  * a regex that rewrote a function's own body so it returned itself - AND THE LINTER PASSED IT
  * an edit that inserted a constant BEFORE doing a replacement, so the replacement rewrote the
    definition it had just added
  * `grep -c` exiting 1 on zero matches and short-circuiting an `&&` chain, so a step never ran
    at all - a command that did not run looks exactly like a command that passed

Nearly every self-inflicted error in this project was caught by arithmetic and never by
re-reading. Asking "how many times does this pattern match" before substituting would have
caught the first two outright.

AND IT WAS STILL HAPPENING WHEN THIS WAS WRITTEN. `tools/guard/replant.py` refuses a plant
whose text does not match exactly once. `tools/guard/autoplant.py` - the tool that GENERATES
plants - had the identical function with the count missing, so a find matching twice would
plant a defect in two places and record it as one.

WHAT THIS REFUSES: a string rewrite whose result goes straight to a file, with nothing in the
preceding lines that counts the matches. `text.replace(a, b)` handed to a write call, or
assigned and written a few lines later, with no `.count(`, `re.findall`, or `assert` above it.

WHAT IT DOES NOT REFUSE: path-separator normalisation, line-ending normalisation, and every
other `.replace()` that never reaches a file. The first version of this check matched any
`.replace(` at all and reported 36 findings, 35 of which were `replace(os.sep, "/")` - a
checker that cries wolf 35 times out of 36 is one whose output nobody reads.

Usage: python tools/guard/count_first.py [root]
Exit 1 with reasons, 0 when every rewrite that reaches a file counted first.
"""
import io
import os
import re
import sys

SKIP_DIRS = ("build", "dist", ".git", "__pycache__", "node_modules", "wt", "lib")

#: A rewrite: a substitution that produces new text.
_REWRITE = re.compile(r"\.replace\s*\(|\bre\.sub\s*\(")

#: ...handed straight to something that writes a file, on the same line.
_WRITTEN_INLINE = re.compile(r"\b\w*write\w*\s*\([^\n]*(?:\.replace\s*\(|\bre\.sub\s*\()", re.I)

#: ...or assigned to a name that is written within the next few lines.
_ASSIGNED = re.compile(r"^\s*(\w+)\s*=\s*[^=\n]*(?:\.replace\s*\(|\bre\.sub\s*\()")

#: What counting looks like. `assert` is included because an assertion about the text is the
#: cheapest honest form of it, and it is what the scripts in this project actually use.
_COUNTED = re.compile(r"\.count\s*\(|\bre\.findall|\bassert\b|exactly once|_only_once")

#: Rewrites that cannot silently do the wrong thing: they normalise a representation rather
#: than edit content, and there is nothing to count.
#: Matched against the FIRST ARGUMENT of the rewrite, not against the call's opening bracket.
#: `replace(chr(13) + chr(10), chr(10))` is line-ending normalisation and an anchored pattern
#: could not see it - it demanded a comma straight after the marker, and the suite caught that
#: on the first run.
_HARMLESS = re.compile(
    r"os\.sep|chr\(92\)|chr\(13\)|chr\(10\)|"
    r"[\"']\\\\[\"']|\\r\\n|\\r[\"']|\bCRLF\b|\bLF\b|\bNL\b")


def _first_argument(line, at):
    """The text of the rewrite's first argument, up to its top-level comma."""
    depth = 0
    out = []
    for char in line[at:]:
        if char in "([{":
            depth += 1
            if depth == 1:
                continue
        elif char in ")]}":
            depth -= 1
            if depth == 0:
                break
        elif char == "," and depth == 1:
            break
        if depth >= 1:
            out.append(char)
    return "".join(out)


def harmless(line):
    """Whether this rewrite only respells a separator or a line ending."""
    for found in re.finditer(r"\.replace\s*\(|\bre\.sub\s*\(", line):
        argument = _first_argument(line, found.end() - 1)
        if not _HARMLESS.search(argument):
            return False
    return True

#: How far above a rewrite a count still counts as "before it", and how far below an
#: assignment a write still counts as "written".
LOOK_BACK = 12
LOOK_AHEAD = 8


def files(root):
    out = []
    for dirpath, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in names:
            if name.endswith(".py"):
                out.append(os.path.join(dirpath, name))
    return out


def reaches_a_file(lines, i):
    """Whether the rewrite on line `i` ends up written to a file."""
    line = lines[i]
    if _WRITTEN_INLINE.search(line):
        return True
    found = _ASSIGNED.match(line)
    if not found:
        return False
    name = found.group(1)
    for later in lines[i + 1:i + 1 + LOOK_AHEAD]:
        if re.search(r"\b\w*write\w*\s*\([^\n]*\b%s\b" % re.escape(name), later, re.I):
            return True
    return False


def findings(root):
    """Every rewrite that reaches a file without counting its matches first."""
    why = []
    looked = 0
    for path in files(root):
        try:
            lines = io.open(path, encoding="utf-8", errors="replace").read().splitlines()
        except OSError as cannotRead:
            why.append("cannot read %s (%s) - an unreadable file is not a clean one"
                       % (path, cannotRead))
            continue
        looked += 1
        rel = os.path.relpath(path, root)
        for i, line in enumerate(lines):
            if not _REWRITE.search(line) or harmless(line):
                continue
            if not reaches_a_file(lines, i):
                continue
            window = lines[max(0, i - LOOK_BACK):i + 1]
            if any(_COUNTED.search(w) for w in window):
                continue
            why.append(
                "%s:%d rewrites a file without counting the matches first:%s    %s%s"
                "  COUNT WHAT YOU EXPECT BEFORE RUNNING THE CHANGE. A pattern matching twice "
                "edits twice and reports once; a pattern matching nothing edits nothing and "
                "reports success. Assert the count above this line."
                % (rel, i + 1, chr(10), line.strip()[:100], chr(10)))
    if not looked:
        why.append("NOTHING WAS READ under %s - a scan that examined no files cannot report a "
                   "clean result." % os.path.abspath(root))
    return why


def main(argv):
    root = argv[1] if len(argv) > 1 else "."
    why = findings(root)
    if not why:
        print("every rewrite that reaches a file counts its matches first (%d files read)"
              % len(files(root)))
        return 0
    print("A REWRITE HERE CAN EDIT TWICE AND REPORT ONCE:")
    for reason in why:
        print("  - " + reason)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
