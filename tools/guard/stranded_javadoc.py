#!/usr/bin/env python3
"""A JAVADOC THAT DOCUMENTS NOTHING IS REFUSED AT THE WRITE THAT STRANDS IT.

PAID FOR FIFTEEN TIMES. A member inserted on the line where an existing member's declaration begins
lands BETWEEN that member's javadoc and its declaration: the old doc now sits above the new doc, the
newcomer is read as what it describes, and the member it was written for goes on undocumented a
screen further down. Eight were inserted that way on 2026-10-12, found by the battery's
SourceDebrisTest and moved back by hand; seven more on 2026-10-14 and 10-15, each a guard added for a
mutation survivor ahead of an existing check, found the same way - by a release battery, hours after
the write, with the guard that made them already proven and staged on top. A detector at the battery
is a defect found after it was built on; this asks the write itself.

WHAT COUNTS is SourceDebrisTest's own definition, mirrored line for line from its `docs` and
`orphans` so the two cannot disagree: a javadoc whose next text - past whitespace and line
comments - is another javadoc, a closing brace or the end of the file. A `/**` inside a string or a
char literal is not one, nor is `/**/`. SourceDebrisTest stays the whole tree's check at the
battery; this is the same question asked of one file as a write would leave it.

DECLARED AT_WRITE, so `bundle_rules` asks it of the file before and after any write - an Edit, a
Write, or whatever a command changed - and refuses only GROWTH: a file already carrying one can be
written, as long as the write strands no other.

    python -B tools/guard/stranded_javadoc.py [root]    every .java under root/src, walked; exit 1 naming each

`tests/test_stranded_javadoc.py` holds it to known answers both ways, and to this tree.
"""
import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)

FLAGS = ()

AT_WRITE = "findings"
AT_SCOPE = r"^src/.*\.java$"

#: What the doc is stranded before, indexed as SourceDebrisTest's KINDS are.
KINDS = ("another javadoc", "a closing brace", "the end of the file")


def docs(src):
    """[start, end) of every javadoc, string and char literals and other comments skipped -
    SourceDebrisTest.docs."""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c in "\"'":
            i += 1
            while i < n and src[i] != c:
                i += 2 if src[i] == "\\" else 1
            i += 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            if src.startswith("/**", i) and not src.startswith("/**/", i):
                out.append((i, j))
            i = j
        else:
            i += 1
    return out


def orphans(src):
    """[(start, kind)] of each javadoc that documents nothing - SourceDebrisTest.orphans."""
    found, n, out = docs(src), len(src), []
    for d, (_start, end) in enumerate(found):
        k = end
        while k < n:
            if src[k].isspace():
                k += 1
            elif src.startswith("//", k):
                j = src.find("\n", k)
                k = n if j < 0 else j
            else:
                break
        if k >= n:
            kind = 2
        elif d + 1 < len(found) and found[d + 1][0] == k:
            kind = 0
        elif src[k] == "}":
            kind = 1
        else:
            continue
        out.append((found[d][0], kind))
    return out


def _first_words(src, start):
    """The doc's first line of text, which names it however the lines around it move."""
    body = src[start + 3:src.find("*/", start + 3)]
    for line in body.splitlines():
        line = line.strip().lstrip("*").strip()
        if line:
            return line[:90]
    return "(empty)"


def findings(rel, text):
    """One finding for each javadoc in `text` that documents nothing. Named by the doc's own first words,
    not its line, so a write that moves the lines around one does not read as stranding a new one."""
    return ["%s: the javadoc \"%s\" documents nothing - it is followed by %s. A member inserted on the line"
            " where a declaration begins lands between that declaration and its doc: insert ABOVE the doc"
            % (rel, _first_words(text, start), KINDS[kind]) for start, kind in orphans(text or "")]


def main(argv):
    #a word this does not read refuses the run, before anything runs (cli_words, 2026-10-10)
    import _cli
    unread_words = _cli.refused(argv[1:], flags=FLAGS, most=1)
    if unread_words:
        print(unread_words)
        return 2
    root = argv[1] if len(argv) > 1 else _tree_root.require(__file__)
    src = os.path.join(root, "src")
    walked, out = 0, []
    for folder, _dirs, files in os.walk(src):
        for name in sorted(files):
            if name.endswith(".java"):
                path = os.path.join(folder, name)
                with io.open(path, encoding="utf-8", errors="replace") as handle:
                    text = handle.read()
                walked += 1
                out += findings(os.path.relpath(path, root).replace(os.sep, "/"), text)
    if walked == 0:
        print("NO .java FILE UNDER %s - nothing was asked, which is not a clean tree" % src)
        return 2
    for line in out:
        print(line)
    print("%d javadoc(s) documenting nothing in %d .java file(s)" % (len(out), walked))
    return 1 if out else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
