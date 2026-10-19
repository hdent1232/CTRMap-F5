"""A javadoc stranded by an insertion is refused at the write - tools/guard/stranded_javadoc.py, asked.

PAID FOR FIFTEEN TIMES: a member inserted on the line where a declaration begins lands between that
declaration and its javadoc, and SourceDebrisTest found each one at a battery, hours after the write -
seven of them on 2026-10-15, in the release battery. The rule is declared AT_WRITE, so `bundle_rules`
asks it of every write, a command's included. Asked here through `ask_changes`, the door the write
channel and the held-writes channel both ask, over a real documented member FOUND by walking src/:

  instance   a member inserted between a doc and its declaration is a finding of `stranded_javadoc`
  elsewhere  so is a doc before a closing brace, at the end of the file, or before a line comment and
             then another doc (F158's shape)
  evasion    the same member inserted ABOVE the doc passes, and so does a write that only moves lines;
             a `/**` in a string or a char literal, `/**/` and a doc before an annotation are not docs
             documenting nothing - a rule that refused honest edits would be switched off within the day
"""
import importlib.util
import io
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
HERE = _tree_root.require(__file__)

#: A documented member: a doc's closing line, then a declaration on the next.
_MEMBER = re.compile(r"^([ \t]*)\*/\n([ \t]*)((?:public |protected |private )?static [^\n]*\{)$", re.M)


def a_documented_member():
    """(rel, declaration line): the first member under src/, in sorted order, whose javadoc sits directly on
    its declaration. Derived from the tree, never named: a named fixture is one deletion from a suite that
    cannot start."""
    for folder, dirs, files in os.walk(os.path.join(HERE, "src")):
        dirs.sort()
        for name in sorted(files):
            if not name.endswith(".java"):
                continue
            path = os.path.join(folder, name)
            with io.open(path, encoding="utf-8", newline="") as handle:
                text = handle.read()
            if "\r\n" in text:
                continue
            for made in _MEMBER.finditer(text):
                if text.count(made.group(2) + made.group(3)) == 1:
                    return os.path.relpath(path, HERE).replace(os.sep, "/"), made.group(2) + made.group(3)
    return None, None


FILE, DECLARATION = a_documented_member()


def load(name, rel):
    """A module by its path in THIS repository - never by a name another copy may answer."""
    folder = os.path.dirname(os.path.join(HERE, rel))
    if folder not in sys.path:
        sys.path.insert(0, folder)
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, rel))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


PROBE = "\t/** A probe member. */\n\tstatic void strandingProbe() {\n\t}\n\n"


class AStrandedJavadocIsRefusedAtTheWrite(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.rules = load("ctrmap_bundle_rules", ".claude/bundle-hooks/bundle_rules.py")
        cls.rule = load("ctrmap_stranded_javadoc", "tools/guard/stranded_javadoc.py")
        assert FILE is not None, "control: this tree holds a member whose javadoc sits on its declaration"
        with io.open(os.path.join(HERE, FILE), encoding="utf-8", newline="") as handle:
            cls.before = handle.read()
        assert DECLARATION in cls.before, "control: the member is in the file"

    def said(self, after):
        worse = self.rules.ask_changes(HERE, {FILE: (self.before, after)})
        return [line for name, (_w, _n, found) in worse.items() if name == "stranded_javadoc" for line in found]

    def test_a_member_inserted_on_the_declarations_line_is_refused(self):
        after = self.before.replace(DECLARATION, PROBE + DECLARATION, 1)
        self.assertNotEqual(after, self.before, "control: the probe was inserted")
        said = self.said(after)
        self.assertTrue(said and "followed by another javadoc" in said[0],
                        "a member inserted between a doc and its declaration passed the write: %r" % said)

    def test_the_rule_is_asked_of_every_source_file(self):
        asked = [rule for rule in self.rules.rules(HERE) if rule.name == "stranded_javadoc" and not rule.error]
        self.assertEqual(len(asked), 1, "control: the rule is declared once, and loads")
        for rel in (FILE, "src/ctrmap/X.java", "src/ctrmap/tests/deep/Y.java"):
            with self.subTest(rel):
                self.assertTrue(asked[0].watches(rel), "the rule is declared but not asked of %s" % rel)

    def test_a_doc_before_a_brace_or_at_the_end_is_found(self):
        cases = {"a closing brace": "class A {\n\t/** Nothing. */\n}\n",
                 "the end of the file": "class A {\n}\n/** Nothing. */\n"}
        for kind, text in cases.items():
            with self.subTest(kind):
                said = self.rule.findings("src/A.java", text)
                self.assertEqual(len(said), 1, "a doc before %s was not found: %r" % (kind, said))
                self.assertIn("followed by " + kind, said[0], "a stranded doc was named as the wrong kind: %r" % said)

    def test_a_line_comment_between_two_docs_hides_nothing(self):
        said = self.rule.findings("src/A.java", "class A {\n\t/** Nothing. */\n\t// a note\n\t/** Mine. */\n\tvoid m() {\n\t}\n}\n")
        self.assertEqual(len(said), 1, "a doc, a line comment and another doc were read as a doc on its member"
                                       " (F158): %r" % said)

    def test_an_insertion_above_the_doc_or_a_move_passes(self):
        start = self.before.rfind("/**", 0, self.before.index(DECLARATION))
        start = self.before.rfind("\n", 0, start) + 1
        above = self.before[:start] + PROBE + self.before[start:]
        self.assertEqual(self.said(above), [], "a member inserted above the doc was refused")
        moved = self.before.replace(DECLARATION, DECLARATION, 1).replace("\n\n", "\n\n\n", 1)
        self.assertNotEqual(moved, self.before, "control: the move changed the file")
        self.assertEqual(self.said(moved), [], "a write that only moved lines was refused")

    def test_what_is_not_a_doc_documenting_nothing_is_not_found(self):
        for text in ('class A {\n\tString s = "/** not a doc */";\n\t/** Mine. */\n\tvoid m() {\n\t}\n}\n',
                     "class A {\n\tchar c = '\"'; String s = \"/** x */\";\n\t/** Mine. */\n\tvoid m() {\n\t}\n}\n",
                     "class A {\n\t/**/\n\t/** Mine. */\n\tvoid m() {\n\t}\n}\n",
                     "class A {\n\t/** Mine. */\n\t@Override\n\tpublic String toString() {\n\t\treturn \"\";\n\t}\n}\n"):
            with self.subTest(text):
                self.assertEqual(self.rule.findings("src/A.java", text), [], "a doc that documents its member was found")

    def test_a_literal_hides_no_stranded_doc(self):
        for text in ('class A {\n\tString s = "/*";\n\t/** Stray. */\n\t/** Mine. */\n\tvoid m() {\n\t}\n}\n',
                     "class A {\n\tchar c = '\"';\n\t/** Stray. */\n\t/** Mine. */\n\tvoid m() {\n\t}\n}\n"):
            with self.subTest(text):
                said = self.rule.findings("src/A.java", text)
                self.assertEqual(len(said), 1, "a literal before a stranded doc hid it - read as a comment or a string"
                                               " running on: %r" % said)
                self.assertIn('"Stray."', said[0], "the wrong doc was named: %r" % said)

    def test_the_walk_refuses_a_tree_with_nothing_to_ask(self):
        import tempfile
        empty = tempfile.mkdtemp()
        self.addCleanup(os.rmdir, empty)
        self.assertEqual(self.rule.main(["stranded_javadoc.py", empty]), 2,
                         "a tree with no .java file under src was read as one with nothing stranded")


if __name__ == "__main__":
    unittest.main()
