"""Every warning the editor shows that an action is unproven in game is in the owner's queue (F217).

Five shipped actions said in their own dialogs that they were experimental or unproven in game,
and none was in OUTSTANDING.md: the owner's in-game test queue lived only inside dialogs a reader
sees by clicking the action they guard. The section "## In-game verification owed" now QUOTES each
warning, and this holds the two together both ways, derived from the tree rather than from a list:

  - a string literal in production Java that carries a marker word must contain a quote from
    that section which carries one too - a new warning with no line is refused;
  - a marker-bearing quote in that section must still be in some production literal - a line
    whose warning was removed (the action proven, or renamed) is refused as stale.

Comments are not warnings a user sees, so the scan reads literals only, through a lexer whose
answer on a known snippet is checked before its answer on the tree is believed.
"""
import os
import sys
import re
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _owner_switches  # noqa: E402,F401  - the owner's settings out of this process, before any hook can read one
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
HERE = _tree_root.require(__file__)
SOURCES = os.path.join(HERE, "src")
QUEUE = os.path.join(HERE, "OUTSTANDING.md")
HEADING = "## In-game verification owed"
MARKER = re.compile(r"(?i)\b(experimental|unproven|untested|never booted|not yet verified)\b")
QUOTE = re.compile(r'"([^"\n]+)"')
ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", "f": "\f", "s": " ",
           "0": "\0", '"': '"', "'": "'", "\\": "\\", "\n": ""}
# 278 production files on 2026-10-07; a walk that finds far fewer has lost the tree, not the code.
MIN_FILES = 200


class CannotRead(Exception):
    """A source the lexer could not finish - never read as a file with no warnings in it."""


def literals(text):
    """(line, value) of every string literal and text block in Java source, comments skipped."""
    found = []
    i, line, end = 0, 1, len(text)
    while i < end:
        c = text[i]
        if c == "\n":
            line += 1
            i += 1
        elif text.startswith("//", i):
            stop = text.find("\n", i)
            i = end if stop < 0 else stop
        elif text.startswith("/*", i):
            stop = text.find("*/", i + 2)
            if stop < 0:
                raise CannotRead("a comment opened on line %d never closes" % line)
            line += text.count("\n", i, stop)
            i = stop + 2
        elif c == "'":
            stop = i + 1
            while stop < end and text[stop] != "'":
                stop += 2 if text[stop] == "\\" else 1
            if stop >= end:
                raise CannotRead("a character literal on line %d never closes" % line)
            i = stop + 1
        elif c == '"':
            block = text.startswith('"""', i)
            close = '"""' if block else '"'
            start, stop, value = line, i + len(close), []
            if block:
                stop = text.find("\n", stop)
                if stop < 0:
                    raise CannotRead("a text block on line %d never opens a line" % start)
                line += 1
                stop += 1
            while True:
                if stop >= end or (not block and text[stop] == "\n"):
                    raise CannotRead("a string on line %d never closes" % start)
                if text.startswith(close, stop):
                    break
                ch = text[stop]
                if ch == "\\":
                    nxt = text[stop + 1:stop + 2]
                    if nxt == "u":
                        value.append(chr(int(text[stop + 2:stop + 6], 16)))
                        stop += 6
                        continue
                    if nxt == "\n":
                        line += 1
                    value.append(ESCAPES.get(nxt, nxt))
                    stop += 2
                    continue
                if ch == "\n":
                    line += 1
                value.append(ch)
                stop += 1
            found.append((start, "".join(value)))
            i = stop + len(close)
        else:
            i += 1
    return found


def production_sources():
    """Every .java under src/ that is not a test - walked, never listed."""
    for folder, dirs, names in os.walk(SOURCES):
        dirs[:] = sorted(d for d in dirs if d != "tests")
        for name in sorted(names):
            if name.endswith(".java"):
                yield os.path.join(folder, name)


def warnings_in_tree():
    """[(path, line, literal)] for every production literal that carries a marker, and the file count."""
    hits, files = [], 0
    for path in production_sources():
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        try:
            found = literals(text)
        except CannotRead as why:
            raise CannotRead("%s: %s" % (os.path.relpath(path, HERE), why))
        files += 1
        for line, value in found:
            if MARKER.search(value):
                hits.append((os.path.relpath(path, HERE).replace(os.sep, "/"), line, value))
    return hits, files


def queued_quotes(text):
    """The marker-bearing quotes of the in-game section, or None when there is no such section."""
    start = text.find("\n" + HEADING + "\n")
    if start < 0:
        return None
    body = text[start + len(HEADING) + 2:]
    stop = body.find("\n## ")
    if stop >= 0:
        body = body[:stop]
    return [q for q in QUOTE.findall(body) if MARKER.search(q)]


class TheLexerReadsWhatItClaims(unittest.TestCase):
    """Known answers first: a scan that misreads comments reports a clean tree."""

    SNIPPET = ('// a line comment that says "unproven" and isn\'t one\n'
               '/* a block that says "EXPERIMENTAL"\n   and isn\'t one */\n'
               '/** javadoc: "untested" */\n'
               'String a = "unproven \\"quoted\\" here"; char c = \'"\'; char d = \'\\\'\';\n'
               'String b = "x // not a comment /* nor this */";\n'
               'String e = "EXPERIMENTAL - y";\n'
               'String f = """\n    never booted\n    """;\n')

    def test_literals_are_read_and_comments_are_not(self):
        misread = ("LEXER: a comment was read as a warning the user sees, or a literal was"
                   " missed")
        try:
            found = literals(self.SNIPPET)
        except CannotRead as why:   # a comment's apostrophe read as a character literal
            self.fail("%s - %s" % (misread, why))
        values = [v for _, v in found]
        self.assertEqual(values, ['unproven "quoted" here', "x // not a comment /* nor this */",
                                  "EXPERIMENTAL - y", "    never booted\n    "], misread)
        self.assertEqual([line for line, _ in found], [5, 6, 7, 8], "LEXER: lines miscounted")
        self.assertEqual(sum(1 for v in values if MARKER.search(v)), 3)

    def test_a_source_that_does_not_close_is_refused_not_read_as_empty(self):
        for broken in ('String a = "never closed;\n', "/* never closed", "char c = 'x"):
            with self.assertRaises(CannotRead, msg="LEXER: an unreadable source was read as one"
                                   " with no warnings: %r" % broken):
                literals(broken)

    def test_the_section_reader_finds_quotes_and_stops_at_the_next_heading(self):
        text = ("# Q\n\n" + HEADING + "\n\n- A - \"Thing (experimental)\" and \"plain words\"\n"
                "\n## Done\n\n- B - \"Other (unproven)\"\n")
        self.assertEqual(queued_quotes(text), ["Thing (experimental)"],
                         "SECTION: the queue read quotes from outside its own section")
        self.assertIsNone(queued_quotes("# Q\n\n## Done\n"),
                          "SECTION: a missing section was read as an empty one")


class EveryUnprovenActionIsQueued(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.hits, cls.files = warnings_in_tree()
        with open(QUEUE, encoding="utf-8") as handle:
            cls.quotes = queued_quotes(handle.read())

    def test_the_walk_read_the_tree(self):
        self.assertGreaterEqual(self.files, MIN_FILES,
                                "SCOPE: read %d production sources under src/ - the walk lost the tree"
                                % self.files)

    def test_every_warning_is_quoted_in_the_queue(self):
        if self.quotes is None:
            self.assertEqual(self.hits, [], "QUEUE: OUTSTANDING.md has no '%s' section and %d"
                             " production strings warn the user: %s" % (HEADING, len(self.hits),
                             "; ".join("%s:%d" % (p, n) for p, n, _ in self.hits)))
            return
        missing = ["%s:%d %r" % (p, n, v) for p, n, v in self.hits
                   if not any(q in v for q in self.quotes)]
        self.assertEqual(missing, [], "QUEUE: these warnings tell the user an action is unproven"
                         " and OUTSTANDING.md '%s' quotes none of them - add a line quoting the"
                         " warning and saying what the owner checks in game:\n  %s"
                         % (HEADING, "\n  ".join(missing)))

    def test_every_queued_quote_is_still_a_warning(self):
        if self.quotes is None:
            return
        values = [v for _, _, v in self.hits]
        stale = [q for q in self.quotes if not any(q in v for v in values)]
        self.assertEqual(stale, [], "QUEUE: OUTSTANDING.md '%s' quotes warnings no production"
                         " string carries any more - the action was proven or the words changed;"
                         " remove or re-quote the line: %s" % (HEADING, stale))


if __name__ == "__main__":
    unittest.main()
