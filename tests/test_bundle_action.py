# -*- coding: utf-8 -*-
"""EVERY GUARD IS ASKED AT THE ACT THAT MAKES ITS DEFECT - the doors, driven.

WHY THIS FILE SHIPS, measured on the project this bundle was installed into first. Asked whether
its guards refused at the point of action or were found at the commit, the answer was mostly the
second: fourteen rules recorded as refusing "at the write" were asked by a channel that carried
scripted edits only, while the Edit and Write tools - the way nearly every line is written - went
straight to disk. Thirty-three more sat at the commit or in the suite, each with a paragraph
explaining why nothing earlier could ask them, and most of those paragraphs were wrong.

So a checker now declares the ACT it is asked at, a hook asks it there, and this suite drives
each door against a SCRATCH PROJECT of stub rules - so what is asserted is the door, not
whichever real rule a project happens to declare:

    the write         a per-file, tree, change or record rule, asked as the write would leave
                      the file, the tree and the record - before the bytes land
    the commit        its message asked before git starts, and no commit may skip the gate
    the moment after  a verdict only the changed tree can give, handed straight back
    a command's write the same rules, the moment the tree shows it; a change they refuse is
                      HELD and every act but its repair is refused

THIS IS A HALF A PROJECT INSTALLS: it tests the hooks beside it, whatever project they sit in.
"""
import ast
import base64
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _owner_switches  # noqa: E402,F401  - the owner's settings out of this process, before any hook can read one
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
HERE = _tree_root.require(__file__)
sys.path.insert(0, os.path.join(HERE, "tools"))
import bundle_hooks          # noqa: E402  - where this project keeps the bundle's hooks
HOOKS = bundle_hooks.path(HERE)
sys.path.insert(0, HOOKS)

import after_rules            # noqa: E402
import bundle_rules           # noqa: E402
import bundle_shell           # noqa: E402
import bounded         # noqa: E402  - a deadline ends the child's whole tree
import guard_command_rules    # noqa: E402
import guard_held_writes      # noqa: E402
import guard_write_rules      # noqa: E402

LF = chr(10)
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

#: This file drives guards. A project that discovers its guard suites by a marker finds it.
GUARD_SUITE = True

#: The smallest rule of each kind, written into a scratch project's `tools/`.
STUBS = {
    "zzstub_file.py": (
        'AT_WRITE = "found_in"' + LF + 'AT_SCOPE = r"\\.py$"' + LF + LF + LF
        + "def found_in(rel, text):" + LF
        + '    return [line for line in text.splitlines() if "DEFECT" in line]' + LF),
    "zzstub_tree.py": (
        "import re" + LF + LF
        + 'AT_WRITE_TREE = ("facts_of", "judge")' + LF + 'AT_SCOPE = r"^src/.*\\.py$"' + LF + LF
        + LF + "def facts_of(rel, text):" + LF
        + '    return {"made": re.findall(r"MADE = (\\w+)", text),' + LF
        + '            "used": re.findall(r"USE\\((\\w+)\\)", text)}' + LF + LF + LF
        + "def judge(facts):" + LF
        + "    used = {u for held in facts.values() for u in held['used']}" + LF
        + "    return sorted(m for held in facts.values() for m in held['made']"
        + " if m not in used)" + LF),
    "zzstub_record.py": (
        'AT_RECORD = "record_in"' + LF + 'AT_SCOPE = r"^tests/.*\\.json$"' + LF + LF + LF
        + "def record_in(path, payload):" + LF
        + '    return ["slack"] if payload.get("ceiling", 0) > payload.get("count", 0)'
        + " else []" + LF),
    "zzstub_mixed.py": (
        'AT_WRITE = "text_in"' + LF + 'AT_RECORD = "record_in"' + LF
        + 'AT_SCOPE = r"^mixed/"' + LF + LF + LF
        + "def text_in(rel, text):" + LF
        + '    return ["MIXED"] if "MIXED" in text else []' + LF + LF + LF
        + "def record_in(path, payload):" + LF
        + '    return ["slack"] if payload.get("n", 0) > 5 else []' + LF),
    "zzstub_change.py": (
        'AT_WRITE_CHANGE = "change_in"' + LF + 'AT_SCOPE = r"^tests/test_.*\\.py$"' + LF + LF
        + LF + "def change_in(rel, before, after, root):" + LF
        + '    return ["RED added"] if "RED" in after and "RED" not in (before or "")'
        + " else []" + LF),
    "zzstub_broken.py": (
        'AT_WRITE = "boom"' + LF + 'AT_SCOPE = r"^broken/"' + LF + LF + LF
        + "def boom(rel, text):" + LF + '    raise ValueError("this rule is broken")' + LF),
    "zzstub_commit.py": (
        'AT_COMMIT = "message_in"' + LF + LF + LF + "def message_in(message, root):" + LF
        + '    return [] if "Signed-off" in message else ["no sign-off"]' + LF),
    "zzstub_command.py": (
        'AT_COMMAND = "command_in"' + LF + LF + LF + "def command_in(command, root):" + LF
        + '    return ["the forbidden act"] if "forbidden-act" in command else []' + LF),
    "zzstub_tracked.py": (
        "import subprocess" + LF + LF
        + 'AT_WRITE_CHANGE = "untracked_in"' + LF + 'AT_SCOPE = r"^tracked/"' + LF + LF + LF
        + "def untracked_in(rel, before, after, root):" + LF
        + '    done = subprocess.run(["git", "ls-files", "--error-unmatch", rel], cwd=root,' + LF
        + "                          capture_output=True, timeout=60)" + LF
        + '    return [] if done.returncode == 0 else ["%s is not tracked" % rel]' + LF),
}


class ScratchProject(object):
    """A project of stub rules in a temp folder. A MIXIN, not a test class: it asserts nothing,
    and a class in a guard suite with no test of its own would be counted as a guard nobody
    has watched fail."""

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="bundle-action-")
        self.addCleanup(shutil.rmtree, self.root, True)
        for folder in ("tools", "src", ".githooks"):
            os.makedirs(os.path.join(self.root, folder))
        self.write_file("CLAUDE.md", "# scratch project" + LF)
        for name, text in STUBS.items():
            self.write_file("tools/" + name, text)
        self.write_file("src/a.py", "MADE = x" + LF + "USE(x)" + LF)
        # EACH STUB IMPORTED FRESH: `load` imports by plain name, and one left in `sys.modules`
        # by the previous test would answer from that test's scratch folder.
        for name in STUBS:
            sys.modules.pop(name[:-3], None)
            self.addCleanup(sys.modules.pop, name[:-3], None)

    def write_file(self, rel, text):
        path = os.path.join(self.root, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with io.open(path, "w", encoding="utf-8", newline=LF) as handle:
            handle.write(text)
        return path

    def git(self, *args):
        return bounded.run(["git"] + list(args), cwd=self.root, capture_output=True,
                           text=True, timeout=60, creationflags=NO_WINDOW)


# ----------------------------------------------------------------------------- the write

class AWriteIsJudgedAsItWouldLeaveTheTree(ScratchProject, unittest.TestCase):
    """`bundle_rules.ask_write`, and `guard_write_rules` over it."""

    def ask(self, rel, before, after):
        return bundle_rules.ask_write(self.root, rel, before, after)

    #: A SET rule: one asking a side, every changed file it watches at once. It records each call.
    SET_RULE = ('AT_WRITE_SET = "set_in"' + LF + 'AT_SCOPE = r"^set/"' + LF + LF + "CALLS = []"
                + LF + LF + LF + "def set_in(texts):" + LF
                + "    CALLS.append(sorted(texts.items(), key=str))" + LF
                + "    return sorted(rel for rel, text in texts.items()" + LF
                + '                  if text and "SETMARK" in text)' + LF)

    def test_a_SET_rule_is_asked_once_a_side_with_every_file_the_command_changed(self):
        """Measured 2026-10-10: the install check is a question about a set of files, asked one
        file at a time - twice for each of 61 files one command changed, and that dispatcher call
        took 98 s. A set rule is asked once before and once after, whatever the count, with a file
        the command created absent from the before side."""
        self.write_file("tools/zzset_rule.py", self.SET_RULE)
        sys.modules.pop("zzset_rule", None)
        self.addCleanup(sys.modules.pop, "zzset_rule", None)
        clean, marked = "x = 1" + LF, "x = 1  # SETMARK" + LF
        worse = bundle_rules.ask_changes(self.root, {"set/a.py": (clean, marked),
                                                     "set/b.py": (None, marked),
                                                     "set/c.py": (clean, clean),
                                                     "src/elsewhere.py": (clean, marked)})
        self.assertEqual(worse.get("zzset_rule"), (0, 2, ["set/a.py", "set/b.py"]),
                         "a set rule's growth was not refused: %r" % worse)
        self.assertEqual(sys.modules["zzset_rule"].CALLS,
                         [[("set/a.py", clean), ("set/b.py", None), ("set/c.py", clean)],
                          [("set/a.py", marked), ("set/b.py", marked), ("set/c.py", clean)]],
                         "a set rule was not asked once a side with the set it watches")

    def test_a_write_that_ADDS_a_finding_is_refused_and_one_that_keeps_or_removes_it_is_not(self):
        clean, dirty = "x = 1" + LF, "x = 1  # DEFECT" + LF
        self.assertIn("zzstub_file", self.ask("src/b.py", clean, dirty),
                      "a write adding a finding was let through")
        self.assertNotIn("zzstub_file", self.ask("src/b.py", dirty, dirty),
                         "a file already carrying debt could not be edited")
        self.assertNotIn("zzstub_file", self.ask("src/b.py", dirty, clean),
                         "removing a finding was refused")

    def test_a_write_INTO_the_git_folder_is_refused_and_a_look_alike_is_not(self):
        """Only git writes the repository's git folder: `.git/config` can switch the commit gate
        off and `.git/gate-stamps` says a commit passed it, so a tool writing either is a gate
        skipped or a stamp forged. `.gitignore` and `.githooks/` only look like it."""
        for rel in (".git/gate-stamps", ".git/config", ".git/hooks/commit-msg"):
            with self.subTest(rel):
                payload = {"tool_name": "Write", "tool_input": {
                    "file_path": os.path.join(self.root, rel.replace("/", os.sep)),
                    "content": "x" + LF}}
                self.assertEqual(guard_write_rules.verdict(payload, self.root)[0], 2,
                                 "a tool wrote inside the git folder")
        for rel in (".gitignore", ".githooks/pre-commit", "src/git_notes.py"):
            with self.subTest(rel):
                payload = {"tool_name": "Write", "tool_input": {
                    "file_path": os.path.join(self.root, rel.replace("/", os.sep)),
                    "content": "x = 1" + LF}}
                self.assertEqual(guard_write_rules.verdict(payload, self.root)[0], 0,
                                 "a project file that only looks like git's was refused")

    def test_a_NEW_file_is_asked_as_the_write_would_leave_it(self):
        """The cheapest way past a write rule is a file that did not exist before."""
        self.assertIn("zzstub_file", self.ask("src/new.py", None, "y = 2  # DEFECT" + LF),
                      "a new file carrying a defect was let in")

    def test_a_tree_rule_judges_the_OTHER_files_and_the_proposed_one_together(self):
        self.assertIn("zzstub_tree", self.ask("src/c.py", None, "MADE = y" + LF),
                      "a producer with no consumer anywhere in the tree was let in")
        self.write_file("src/d.py", "USE(z)" + LF)
        self.assertNotIn("zzstub_tree", self.ask("src/c.py", None, "MADE = z" + LF),
                         "the consumer in ANOTHER file was not seen")

    def test_a_cached_fact_is_refreshed_when_its_file_changes(self):
        self.ask("src/c.py", None, "MADE = w" + LF)
        self.write_file("src/a.py", "MADE = x" + LF + "USE(x)" + LF + "USE(w)" + LF + "# longer")
        self.assertNotIn("zzstub_tree", self.ask("src/c.py", None, "MADE = w" + LF),
                         "a fact cached before its file changed was served after it")

    def test_a_record_is_asked_as_JSON_and_one_that_is_not_JSON_is_refused(self):
        tight, slack = '{"ceiling": 1, "count": 1}', '{"ceiling": 5, "count": 1}'
        self.assertIn("zzstub_record", self.ask("tests/r.json", tight, slack))
        self.assertNotIn("zzstub_record", self.ask("tests/r.json", tight, tight))
        self.assertIn("zzstub_record", self.ask("tests/r.json", tight, "{not json"),
                      "a record that is not JSON was written")

    def test_a_record_rule_sharing_its_scope_with_a_source_rule_is_asked_only_of_records(self):
        """Measured 2026-09-23 in the project this came from: `skips.py` declares a write rule, a
        change rule and a record rule over ONE scope naming its Layer 1 test files AND its
        record, and every edit to every one of those test files was refused as `a record and
        the write is not JSON`. `migrations.py` had the same shape over the store module."""
        self.assertNotIn("zzstub_mixed", self.ask("mixed/test_a.py", "x = 1" + LF, "x = 2" + LF),
                         "a source file in a mixed scope was judged as a record")
        self.assertIn("zzstub_mixed", self.ask("mixed/test_a.py", "x = 1" + LF, "MIXED = 1" + LF),
                      "the source rule in a mixed scope stopped being asked")
        self.assertIn("zzstub_mixed", self.ask("mixed/r.json", '{"n": 1}', "{not json"),
                      "the record in a mixed scope stopped being asked")
        self.assertIn("zzstub_mixed", self.ask("mixed/r.json", '{"n": 1}', '{"n": 9}'),
                      "the record rule in a mixed scope stopped judging the record")

    def test_a_change_rule_is_asked_with_both_texts(self):
        self.assertIn("zzstub_change", self.ask("tests/test_x.py", "a = 1" + LF, "RED = 1" + LF))
        self.assertNotIn("zzstub_change", self.ask("tests/test_x.py", "RED = 1" + LF,
                                                   "RED = 2" + LF))

    def test_a_py_that_stops_compiling_is_refused(self):
        self.assertIn("compile", self.ask("src/e.py", "x = 1" + LF, "x = (" + LF))
        self.assertNotIn("compile", self.ask("src/e.py", "x = (" + LF, "x = [" + LF),
                         "a file that already did not compile could not be edited")

    def test_a_rule_whose_scope_is_not_a_pattern_is_refused_not_silently_unasked(self):
        """F168: with AT_SCOPE r'^src/(.*\\.py$' a rule was discovered with no error and every write
        passed with nothing said - watches() caught re.error and answered "not mine"; and a scope that
        was not a string was an error row no write kind carried. A RULE THAT CANNOT ANSWER IS NOT A PASS."""
        for name, scope in (("zzstub_badscope", 'r"^src/(.*\\.py$"'), ("zzstub_numscope", "3")):
            self.write_file("tools/%s.py" % name, 'AT_WRITE = "found_in"' + LF + "AT_SCOPE = " + scope
                            + LF + LF + LF + "def found_in(rel, text):" + LF + "    return []" + LF)
            sys.modules.pop(name, None)
            self.addCleanup(sys.modules.pop, name, None)
        said = self.ask("src/b.py", "x = 1" + LF, "x = 2" + LF)
        for name in ("zzstub_badscope", "zzstub_numscope"):
            self.assertIn(name, said, "a rule whose scope is not a pattern was never asked: %r" % (said,))

    def test_a_rule_that_cannot_answer_refuses_EXCEPT_the_write_that_repairs_it(self):
        payload = {"tool_name": "Write", "tool_input": {
            "file_path": os.path.join(self.root, "broken", "x.py"), "content": "x = 1" + LF}}
        code, said = guard_write_rules.verdict(payload, self.root)
        self.assertEqual(code, 2, "a rule that could not answer was read as a pass")
        self.assertIn("COULD NOT ANSWER", said)
        repair = {"tool_name": "Write", "tool_input": {
            "file_path": os.path.join(self.root, "tools", "zzstub_broken.py"),
            "content": 'AT_WRITE = "boom"' + LF}}
        self.assertEqual(guard_write_rules.verdict(repair, self.root)[0], 0,
                         "a broken rule blocked the only write that can repair it")

    def test_a_rule_file_that_cannot_be_READ_is_not_silently_absent(self):
        """`rules` skipped a file it could not open - `except OSError: continue` - so a rule
        nobody could read was a rule that did not exist, and every write it should have judged
        passed. A QUERY THAT CANNOT READ ITS SUBJECT MUST NOT REPORT IT ABSENT. A file that is
        not there - gone since the walk, a dangling link - is not a rule; one that is there and
        cannot be read is unknown."""
        target = self.write_file("tools/zzstub_locked.py", 'AT_WRITE = "found_in"' + LF)
        real_open = io.open

        def failing(error):
            def opener(path, *args, **kwargs):
                if os.path.abspath(str(path)) == os.path.abspath(target):
                    raise error(13, "cannot open", str(path))
                return real_open(path, *args, **kwargs)
            return opener
        with mock.patch.object(bundle_rules.io, "open", side_effect=failing(PermissionError)):
            found = [r for r in bundle_rules.rules(self.root) if r.name == "zzstub_locked"]
        self.assertTrue(found and found[0].error,
                        "a rule file nobody could read was reported as no rule at all")
        with mock.patch.object(bundle_rules.io, "open", side_effect=failing(FileNotFoundError)):
            gone = [r for r in bundle_rules.rules(self.root) if r.name == "zzstub_locked"]
        self.assertEqual(gone, [], "a file that is not there was read as a rule that cannot "
                                   "be asked")

    def test_a_MISDECLARED_rule_is_not_silently_absent(self):
        self.write_file("tools/zzstub_bad.py", 'AT_WRITE_TREE = "not a pair"' + LF)
        sys.modules.pop("zzstub_bad", None)
        bad = [r for r in bundle_rules.rules(self.root) if r.name == "zzstub_bad"]
        self.assertTrue(bad and bad[0].error, "a declaration of the wrong shape vanished")
        self.assertIn("zzstub_bad", self.ask("src/f.py", None, "x = 1" + LF))

    def test_the_hook_builds_the_file_from_the_edits_the_call_carries(self):
        path = self.write_file("src/g.py", "a = 1" + LF + "b = 1" + LF + "b = 1" + LF)
        one = {"tool_input": {"file_path": path, "old_string": "a = 1", "new_string": "a = 2"}}
        every = {"tool_input": {"file_path": path, "old_string": "b = 1", "new_string": "b = 3",
                                "replace_all": True}}
        several = {"tool_input": {"file_path": path, "edits": [
            {"old_string": "a = 1", "new_string": "a = 9"},
            {"old_string": "a = 9", "new_string": "a = 10"}]}}
        self.assertEqual(guard_write_rules.proposed(one, self.root)[2],
                         "a = 2" + LF + "b = 1" + LF + "b = 1" + LF)
        self.assertEqual(guard_write_rules.proposed(every, self.root)[2].count("b = 3"), 2)
        self.assertTrue(guard_write_rules.proposed(several, self.root)[2].startswith("a = 10"))
        missing = {"tool_input": {"file_path": path, "old_string": "zz", "new_string": "yy"}}
        self.assertIsNone(guard_write_rules.proposed(missing, self.root))

    def test_a_write_outside_the_project_is_not_this_projects_to_refuse(self):
        outside = {"tool_name": "Write", "tool_input": {
            "file_path": os.path.join(os.path.dirname(self.root), "elsewhere.py"),
            "content": "x = 1  # DEFECT" + LF}}
        self.assertEqual(guard_write_rules.verdict(outside, self.root)[0], 0)


# ----------------------------------------------------------------------------- the commit
    @staticmethod
    def fake_parse(_rel, text):
        """A Java parser for the tests that must run where no javac is: '' parses, any `return 1`
        missing its semicolon is the error javac gives for it."""
        return "';' expected" if "return 1" + chr(10) in text or "return 1 " in text else ""

    def test_a_JAVA_file_that_parsed_and_would_not_is_refused(self):
        """A `.py` that compiled before and would not after is refused at the write; a `.java`
        was asked nothing at all, so a Java project's every syntax error met the build first.
        Asked here through a stand-in parser, so it is asked on every machine; the real one is
        `test_javac_PARSES_without_a_classpath_and_writes_nothing`."""
        rel = "src/main/java/a/Calc.java"
        before = "package a;" + LF + "public class Calc {" + LF + "    int f() { return 1; }" \
            + LF + "}" + LF
        broken = before.replace("return 1;", "return 1 ")
        with mock.patch.object(bundle_rules, "java_parse_error", side_effect=self.fake_parse):
            self.assertIn("compile", bundle_rules.ask_write(self.root, rel, before, broken),
                          "a Java file broken by the write was let through")
            self.assertEqual(bundle_rules.ask_write(self.root, rel, before,
                                                    before.replace("1;", "2;")), {},
                             "a Java edit that parses was refused")
            self.assertNotIn("compile", bundle_rules.ask_write(self.root, rel, broken,
                                                               broken + LF),
                             "a file that did not parse before was refused for still not parsing")

    def test_a_JAVA_file_nobody_can_parse_here_is_UNKNOWN_not_clean(self):
        rel = "src/main/java/a/Calc.java"
        with mock.patch.object(bundle_rules, "javac", return_value=None):
            worse = bundle_rules.ask_write(self.root, rel, "class Calc {}" + LF,
                                           "class Calc { }" + LF)
        self.assertEqual(worse.get("compile", (0,))[0], -1,
                         "a Java write with no javac to ask was let through as parsed")

    @unittest.skipUnless(bundle_rules.javac(), "no javac on this machine to ask")
    def test_javac_PARSES_without_a_classpath_and_writes_nothing(self):
        """The real parser, where there is one: an import nothing can resolve is not a parse
        error, a missing semicolon is, and the class file is never written. Machine-dependent, so
        it carries no plant - one that cannot redden where javac is missing would refuse every
        install there."""
        good = "package a;" + LF + "import b.Missing;" + LF + "public class Calc {" + LF \
            + "    Missing m;" + LF + "    int f() { return 1; }" + LF + "}" + LF
        self.assertEqual(bundle_rules.java_parse_error("Calc.java", good), "",
                         "an unresolved import was read as a parse error")
        self.assertIn("expected", bundle_rules.java_parse_error(
            "Calc.java", good.replace("return 1;", "return 1")) or "",
            "a missing semicolon parsed")

class ACommitIsAskedBeforeGitStartsAndNeverSkipsTheGate(ScratchProject, unittest.TestCase):
    """`guard_command_rules`: the message before git runs, and no commit may skip the gate."""

    def setUp(self):
        ScratchProject.setUp(self)
        self.git("init", "-q")
        self.git("config", "core.hooksPath", ".githooks")
        self.good = self.write_file("msg-good.txt", "a change" + LF + LF + "Signed-off" + LF)
        self.bare = self.write_file("msg-bare.txt", "a change" + LF)

    def refused(self, command):
        payload = {"tool_name": "Bash", "tool_input": {"command": command}}
        return guard_command_rules.problems(payload, self.root)

    def test_skipping_the_gate_is_refused_however_it_is_spelled(self):
        for spelled in ('git commit --no-verify -F "%s"', 'git commit -n -F "%s"',
                        'git commit -anF "%s"', 'git -c core.hooksPath=/dev/null commit -F "%s"',
                        'git --config-env=core.hooksPath=H commit -F "%s"'):
            with self.subTest(spelled):
                self.assertTrue(self.refused(spelled % self.good),
                                "a commit that skips every git hook was let through")

    def test_a_commit_git_would_run_NO_HOOK_for_because_none_is_EXECUTABLE_is_refused(self):
        """MEASURED 2026-10-07 in the bundle's own repository: both git hooks tracked as 100644,
        so git printed a hint and ran neither, and every commit of that session went in with no
        gate asked. `core.hooksPath` naming the folder is not the gate running.

        ASKED AS POSIX ON ANY MACHINE. This skipped on Windows, where Git runs a hook whatever its
        mode, so its plant could never redden there - and the install drives every plant it
        merges, so it refused DF Engine's install (measured 2026-10-07). The mode is the one
        question that differs, and it is answered here by the test."""
        hook = self.write_file(".githooks/commit-msg", "#!/bin/sh" + LF + "exit 0" + LF)
        executable = [False]
        real = os.access

        def access(path, mode, *args, **kwargs):
            if mode == os.X_OK and os.path.abspath(path) == os.path.abspath(hook):
                return executable[0]
            return real(path, mode, *args, **kwargs)
        with mock.patch.object(bundle_rules, "MODES_MATTER", True), \
                mock.patch.object(bundle_rules.os, "access", access):
            said = " ".join(str(f) for _n, f in self.refused('git commit -F "%s"' % self.good))
            self.assertIn("not executable", said,
                          "a commit git would run no hook for was let through: %s" % said)
            executable[0] = True
            self.assertEqual(guard_command_rules.hooks_path_problem(self.root), "",
                             "a gate git will run was still refused")
        executable[0] = False
        with mock.patch.object(bundle_rules, "MODES_MATTER", False), \
                mock.patch.object(bundle_rules.os, "access", access):
            self.assertEqual(guard_command_rules.hooks_path_problem(self.root), "",
                             "a hook Git for Windows runs whatever its mode was refused")

    def test_a_hook_git_RECORDS_without_the_executable_bit_is_refused_on_every_system(self):
        """Measured 2026-10-07 by the CTRMap session: four hooks across two repositories committed
        from Windows, recorded 100644 - Git for Windows ran them, and every Linux and macOS clone,
        every cloud session among them, checked them out unrunnable and skipped the gate. A clone
        takes its mode from the index, so the index is asked on every system - here AS WINDOWS,
        where the disk's bit says nothing. And a mode git could not be asked is UNKNOWN."""
        self.write_file(".githooks/commit-msg", "#!/bin/sh" + LF + "exit 0" + LF)
        self.git("config", "core.fileMode", "false")
        self.git("add", "--", ".githooks/commit-msg")
        self.git("update-index", "--chmod=-x", "--", ".githooks/commit-msg")
        with mock.patch.object(bundle_rules, "MODES_MATTER", False), \
                mock.patch.object(bundle_rules.os, "access", lambda *a, **k: True):
            said = guard_command_rules.hooks_path_problem(self.root)
            self.assertIn("recorded by git as 100644", said,
                          "INDEX: a hook every clone checks out unrunnable was let through: %r"
                          % said)
            self.git("update-index", "--chmod=+x", "--", ".githooks/commit-msg")
            self.assertEqual(guard_command_rules.hooks_path_problem(self.root), "",
                             "INDEX: a hook git records as executable was still refused")

    def test_a_hook_mode_git_could_not_be_asked_is_UNKNOWN_never_executable(self):
        """A QUERY THAT CANNOT READ ITS SUBJECT MUST NOT REPORT IT ABSENT - here, as Windows, where
        the index is the only answer there is."""
        self.write_file(".githooks/commit-msg", "#!/bin/sh" + LF + "exit 0" + LF)
        self.git("add", "--", ".githooks/commit-msg")
        #THE CALL THE CODE MAKES: bundle_rules asks git through bounded.run, and a fake of subprocess.run
        #intercepted nothing - the real git answered and this was red on the bundle's own tree (2026-10-10)
        real = bundle_rules.bounded.run

        def unaskable(argv, *args, **kwargs):
            if "ls-files" in argv:
                raise OSError("git is not there")
            return real(argv, *args, **kwargs)
        with mock.patch.object(bundle_rules, "MODES_MATTER", False), \
                mock.patch.object(bundle_rules.bounded, "run", unaskable):
            said = guard_command_rules.hooks_path_problem(self.root)
        self.assertIn("UNKNOWN", said,
                      "INDEX: a mode git could not be asked was read as executable: %r" % said)

    def test_an_ABBREVIATED_no_verify_is_still_no_verify(self):
        """git accepts any unambiguous prefix of a long option. `--no-ver` is ambiguous with
        `--no-verbose` and git refuses it itself."""
        self.assertTrue(self.refused('git commit --no-veri -F "%s"' % self.good),
                        "an abbreviated --no-verify walked past the gate")
        self.assertFalse([n for n, _f in self.refused('git commit --no-ver -F "%s"' % self.good)
                          if n == "gate"])

    def test_a_message_on_STDIN_from_a_heredoc_is_read_and_its_words_are_not_flags(self):
        """`git commit -F - <<'EOF'` carries its message in the call, and was refused as
        "comes from standard input" - and the body's words were lexed as the commit's own, so a
        message that MENTIONS `--no-verify` was refused as skipping the gate. Measured
        2026-10-06 replaying 318 real calls through an install: eighteen such refusals."""
        signed = ("git commit -q -F - <<'EOF'" + LF + "a change that says --no-verify" + LF + LF
                  + "Signed-off" + LF + "EOF")
        self.assertEqual(self.refused(signed), [],
                         "a heredoc message on stdin was not read, or its words read as flags")
        unsigned = "git commit -q -F - <<'EOF'" + LF + "a change" + LF + "EOF"
        self.assertEqual([n for n, _f in self.refused(unsigned)], ["zzstub_commit"],
                         "the heredoc message was not ASKED - only a rule reading it can refuse it")
        self.assertTrue(self.refused("git commit -q -F - < msg.txt"),
                        "a message on stdin from somewhere the call does not show was let through")

    def test_repointing_the_hooks_is_refused_and_reading_them_is_not(self):
        self.assertTrue(self.refused("git config core.hooksPath elsewhere"))
        self.assertTrue(self.refused("git config --unset core.hooksPath"))
        self.assertFalse(self.refused("git config --get core.hooksPath"),
                         "reading where the hooks are was refused")

    def test_installing_the_gate_the_refusal_names_is_allowed(self):
        """A fresh clone had no way to its first commit: the commit was refused because the hooks
        were not installed, and the command installing them - the remedy that refusal printed -
        was refused as turning the gate off. Measured by the CTRMap session, 2026-09-26."""
        absolute = os.path.join(self.root, ".githooks")
        for value in (".githooks", "./.githooks", ".githooks/", absolute,
                      absolute.replace(os.sep, "/")):
            for command in ('git config core.hooksPath "%s"' % value,
                            'git config --local core.hooksPath "%s"' % value):
                with self.subTest(command):
                    self.assertEqual(self.refused(command), [],
                                     "the remedy the refusal names was refused")

    def test_only_the_gate_itself_may_be_installed(self):
        """The cheapest way past the exception is to widen it: any value that mentions the folder,
        or the same value written somewhere other than this repository's own config."""
        for command in ("git config core.hooksPath .githooks-off",
                        "git config core.hooksPath ../.githooks",
                        "git config core.hooksPath hooks",
                        'git config core.hooksPath ""',
                        "git config --global core.hooksPath .githooks",
                        "git config --system core.hooksPath .githooks",
                        "git config --add core.hooksPath .githooks",
                        "git config --unset core.hooksPath .githooks",
                        "git config --replace-all core.hooksPath .githooks"):
            with self.subTest(command):
                self.assertTrue(self.refused(command),
                                "a change that is not the gate was let through as installing it")

    def test_the_command_and_the_commit_ask_the_same_question(self):
        """Two spellings of one question are how the deadlock was made. Every value is asked both
        ways: a `git config` of it is allowed exactly when git holding it gates the commit."""
        absolute = os.path.join(self.root, ".githooks")
        for value in (".githooks", "./.githooks", ".githooks/", absolute,
                      absolute.replace(os.sep, "/"), ".githooks-off", "hooks", "../.githooks"):
            with self.subTest(value):
                self.git("config", "core.hooksPath", value)
                gated = guard_command_rules.hooks_path_problem(self.root) == ""
                allowed = self.refused('git config core.hooksPath "%s"' % value) == []
                self.assertEqual(allowed, gated,
                                 "the command and the commit disagree about %r" % value)

    def test_a_commit_while_the_hooks_are_NOT_installed_is_refused(self):
        self.git("config", "--unset", "core.hooksPath")
        self.assertTrue(self.refused('git commit -F "%s"' % self.good),
                        "a commit git would run no hook for was let through")

    def test_a_message_that_cannot_be_read_before_git_runs_is_refused(self):
        for command in ("git commit", 'git commit -F "%s"'
                        % os.path.join(self.root, "never-written.txt"),
                        "git commit --amend", 'git commit -m "$(Get-Content x)"'):
            with self.subTest(command):
                self.assertTrue(self.refused(command), "an unreadable message was waved through")

    def test_clustered_short_options_are_read_the_way_git_reads_them(self):
        """`git commit -qm "..."` is valid git and was refused as having no message. Clusters
        spread the way git's option parser does - and `-an` is still `--no-verify`."""
        for words, message in ((["-qm", "a message"], "a message"),
                               (["-am", "all of it"], "all of it"),
                               (["-qmattached"], "attached")):
            got = guard_command_rules.message_of(self.root, words)
            self.assertEqual(got, (message + LF, ""),
                             "a clustered -m was not read as the message: %r" % (words,))
        self.assertTrue(self.refused("git commit -an -m x"),
                        "a cluster carrying -n skipped the gate")

    def test_every_AT_COMMIT_rule_is_asked_of_the_message(self):
        self.assertFalse(self.refused('git commit -F "%s"' % self.good))
        self.assertTrue(self.refused('git commit -F "%s"' % self.bare),
                        "a message a commit rule refuses was let past the command")

    def test_every_AT_COMMAND_rule_is_asked_before_the_command_runs(self):
        self.assertTrue(self.refused("run forbidden-act now"),
                        "a rule about an act was not asked before the act")
        self.assertFalse(self.refused("run an-allowed-act now"))

    def test_a_git_command_that_commits_WITHOUT_commit_msg_is_refused(self):
        """rebase, cherry-pick, revert and am make commits git runs no `commit-msg` for -
        measured 2026-09-24, the hook's log silent for each - so they carry no gate stamp.
        Stopping one, or applying without committing so the commit goes through the gate, makes
        no ungated commit and is not refused."""
        for command in ("git rebase main", "git rebase --continue", "git cherry-pick abc123",
                        "git cherry-pick -x abc123", "git revert HEAD", "git am fix.patch",
                        "git pull --rebase", "git pull -r origin main"):
            with self.subTest(command):
                self.assertIn("gate", [n for n, _f in self.refused(command)],
                              "a command that commits past the gate was let run")
        for command in ("git rebase --abort", "git cherry-pick --no-commit abc123",
                        "git cherry-pick -n abc123", "git revert --no-commit HEAD",
                        "git am --abort", "git pull", "git pull --rebase=false"):
            with self.subTest(command):
                self.assertEqual(self.refused(command), [],
                                 "a way that makes no ungated commit was refused")


class TheGuardStackCannotBeSwitchedOffByACommand(ScratchProject, unittest.TestCase):
    """`guard_command_rules.stack_write`: a command naming the hooks, their wiring or the gate's
    own tools is refused unless it only reads, stages, commits, restores or runs them.

    MEASURED 2026-10-06 in a fresh install: `: > .claude/hooks/dispatch.py; : > .claude/hooks/
    after_rules.py` was allowed, every guard was off from that moment, and `git commit
    --no-verify` then went through. The moment-after judge saw the drift and could only say so -
    the guard that enforces its hold was a file the same command could empty."""

    def setUp(self):
        ScratchProject.setUp(self)
        self.git("init", "-q")
        os.makedirs(os.path.join(self.root, bundle_shell.FOLDER.replace("/", os.sep)))
        self.write_file(".githooks/pre-commit",
                        "#!/bin/sh" + LF + "# tools/not_run.py is only mentioned here" + LF
                        + '"$PY" -B tools/gate_tool.py || exit 1' + LF)

    def stack(self, command):
        payload = {"tool_name": "Bash", "tool_input": {"command": command}, "cwd": self.root}
        return [found for name, found in guard_command_rules.problems(payload, self.root)
                if name == "stack"]

    def test_emptying_the_dispatcher_is_refused(self):
        hooks = bundle_shell.FOLDER
        found = self.stack(": > %s/dispatch.py; : > %s/after_rules.py" % (hooks, hooks))
        self.assertTrue(found, "a command that switches every guard off was allowed")
        self.assertIn("guard stack", found[0][0], "the guard stack was not named in the refusal")

    def test_every_way_a_command_writes_the_stack_is_refused(self):
        """The same act through every channel a shell offers - an editor in place, a copy, a
        removal, a mode, inline code, a script on stdin, a wrapper, a shell handed the command,
        a folder moved into, and the gate's own tools and wiring rather than a hook."""
        hooks = bundle_shell.FOLDER
        for command in ("sed -i 's/a/b/' %s/guard_held_writes.py" % hooks,
                        "cp /dev/null %s/guard_held_writes.py" % hooks,
                        "rm -rf %s" % hooks,
                        "chmod -x .githooks/pre-commit",
                        "echo '{}' > .claude/settings.json",
                        "cat x | tee -a %s/dispatch.py" % hooks,
                        'python3 -c "open(\'%s/dispatch.py\', \'w\').write(\'\')"' % hooks,
                        "python3 - <<'EOF'" + LF + "open('%s/dispatch.py', 'w')" % hooks + LF
                        + "EOF",
                        "bash -c 'rm %s/dispatch.py'" % hooks,
                        "env X=1 truncate -s0 .githooks/commit-msg",
                        "cd %s && : > dispatch.py" % hooks,
                        ": > tools/gate_tool.py",
                        "perl -pi -e 's/a/b/' tools/gate_tool.py",
                        "python3 scratch.py %s/dispatch.py" % hooks,
                        "echo x > %s/%s/dispatch.py" % (self.root.replace(chr(92), "/"), hooks)):
            with self.subTest(command):
                self.assertTrue(self.stack(command),
                                "a command that writes the guard stack was allowed")

    def test_reading_staging_restoring_and_running_the_stack_are_let_through(self):
        """The opposite direction: a guard that refuses every command naming a hook is one an
        honest session meets on every `git diff`, and is switched off for it."""
        hooks = bundle_shell.FOLDER
        for command in ("cat %s/dispatch.py" % hooks, "ls -la %s/" % hooks,
                        "grep -rn main %s" % hooks, "git diff %s" % hooks,
                        "git add %s/dispatch.py" % hooks, "git checkout -- %s/dispatch.py" % hooks,
                        "git restore .githooks/pre-commit", "git config core.hooksPath .githooks",
                        "python %s/request_ledger.py show" % hooks,
                        'python %s/request_ledger.py done a.1 "ran python -B tools/gate_tool.py"'
                        % hooks,
                        "python -B tools/gate_tool.py", "sed -n 1,10p .githooks/pre-commit",
                        "cat %s/dispatch.py > /tmp/copy.py" % hooks,
                        "git commit -m 'fix %s/dispatch.py'" % hooks,
                        "git commit -F - <<'EOF'" + LF + "names tools/gate_tool.py" + LF + "EOF",
                        "python3 -c \"import json; print(len(open('%s/x.json').read()))\"" % hooks,
                        "echo see %s/x" % hooks, "mkdir -p %s" % hooks,
                        ": > tools/not_run.py", "find %s -name '*.py'" % hooks):
            with self.subTest(command):
                self.assertEqual(self.stack(command), [],
                                 "a command that only reads or runs the stack was refused")

    def owner_home(self):
        """A home with a Claude folder in it, in a temp folder, as `~`, `$HOME` and Python find it."""
        home = tempfile.mkdtemp(prefix="vb-home-")
        self.addCleanup(shutil.rmtree, home, True)
        os.makedirs(os.path.join(home, ".claude", "projects", "p"))
        patcher = mock.patch.dict(os.environ, {"HOME": home, "USERPROFILE": home})
        patcher.start()
        self.addCleanup(patcher.stop)
        os.environ.pop("CLAUDE_CONFIG_DIR", None)           # put back when the patch stops
        return home.replace(chr(92), "/")

    def test_a_command_writing_the_OWNERS_CLAUDE_FOLDER_is_refused(self):
        """MEASURED 2026-10-07 against the dispatcher with every guard wired: `echo '{}' >>
        ~/.claude/projects/<p>/<session>.jsonl` went through - a forged row in the record where the
        owner's yes is read - and so did every other write to the owner's settings and rules."""
        home = self.owner_home()
        record = home + "/.claude/projects/p/s.jsonl"
        for command in ("echo '{}' >> ~/.claude/projects/p/s.jsonl",
                        "echo '{}' >> %s" % record,
                        'echo x > "$HOME/.claude/settings.json"',
                        "cd ~ && echo x > .claude/settings.json",
                        "cd ~ && cp /tmp/x .claude/settings.json",
                        "cp /tmp/x ~/.claude.json",
                        "sed -i s/a/b/ ~/.claude/CLAUDE.md",
                        "rm -rf ~/.claude/projects/p",
                        'python3 -c "open(\'%s\', \'a\').write(\'x\')"' % record,
                        "tee -a ${HOME}/.claude/projects/p/s.jsonl < /tmp/x"):
            with self.subTest(command):
                self.assertTrue(self.stack(command),
                                "a command writing the owner's Claude folder was allowed")
        for command in ("cat ~/.claude/projects/p/s.jsonl",
                        "grep -c x $HOME/.claude/projects/p/s.jsonl", "ls -la ~/.claude",
                        "tail -5 %s" % record,
                        "python3 - <<'EOF'" + LF + "for line in open('%s'): pass" % record + LF
                        + "EOF"):
            with self.subTest(command):
                self.assertEqual(self.stack(command), [],
                                 "a command that only reads the owner's Claude folder was refused")

    def test_making_the_gates_hooks_RUNNABLE_is_let_through_and_taking_it_away_is_not(self):
        """The remedy `hooks_path_problem` names must run: a refusal whose fix is refused is a
        lock-out (CTRMap, 2026-09-26)."""
        for command in ("chmod +x .githooks/pre-commit", "chmod 755 .githooks/pre-commit",
                        "git update-index --chmod=+x .githooks/pre-commit"):
            with self.subTest(command):
                self.assertEqual(self.stack(command), [], "the gate's own remedy was refused")
        for command in ("chmod -x .githooks/pre-commit", "chmod 644 .githooks/pre-commit",
                        "chmod 000 .githooks/pre-commit"):
            with self.subTest(command):
                self.assertTrue(self.stack(command), "taking the gate's mode away was allowed")

    def test_a_command_writing_INSIDE_THE_GIT_FOLDER_is_refused_as_the_Write_tool_is(self):
        """MEASURED 2026-10-06 in a fresh install: `guard_write_rules` refused a Write into `.git` -
        only git writes there, `.git/config` repoints the hooks and `.git/info/exclude` hides a file
        from every judgement - while `echo ... >> .git/config`, `sed -i` on it and inline Python
        opening it for append all went through. One act, refused by one tool, allowed by the
        other. A name that only EXCLUDES the folder, or reads it, is not a write into it."""
        for command in ("echo '[core]' >> .git/config", "sed -i s/a/b/ .git/config",
                        "cd .git && echo x > config",
                        'python3 -c "open(\'.git/config\', \'a\').write(\'x\')"',
                        "cp /tmp/x .git/info/exclude", "rm -f .git/index",
                        "echo x > %s/.git/HEAD" % self.root.replace(chr(92), "/")):
            with self.subTest(command):
                self.assertTrue(self.stack(command), "a command writing into .git was allowed")
        for command in ("cat .git/config", "ls .git/", "git config user.name t",
                        "rsync -a --exclude .git src/ /tmp/out/", "tar --exclude=.git -cf x.tar .",
                        "grep -rn x --exclude-dir=.git .", "cat .gitignore > /tmp/x",
                        "echo x >> .gitignore"):
            with self.subTest(command):
                self.assertEqual(self.stack(command), [],
                                 "a command that reads or excludes .git was refused")

    def test_a_WORKTREES_git_folder_is_the_git_folder_too(self):
        """In a linked worktree `.git` is a FILE naming the real git folder, which sits outside the
        worktree - so `.git/config` there was a path no rule recognised, and the Write tool was let
        through as writing outside the project."""
        self.write_file("f.txt", "x" + LF)
        self.git("add", "f.txt")
        self.git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "x")
        tree = tempfile.mkdtemp(prefix="bundle-worktree-")
        self.addCleanup(shutil.rmtree, tree, True)
        os.rmdir(tree)
        self.assertEqual(self.git("worktree", "add", "-q", tree).returncode, 0)
        shared = os.path.join(self.root, ".git", "config").replace(chr(92), "/")
        payload = {"tool_name": "Bash", "cwd": tree,
                   "tool_input": {"command": "echo '[core]' >> %s" % shared}}
        self.assertTrue([f for n, f in guard_command_rules.problems(payload, tree) if n == "stack"],
                        "a command writing the worktree's real git folder was allowed")
        write = {"tool_name": "Write", "tool_input": {"file_path": shared, "content": "x"}}
        self.assertEqual(guard_write_rules.verdict(write, tree)[0], 2,
                         "a Write into the worktree's real git folder was allowed")
        pointer = {"tool_name": "Bash", "cwd": tree,
                   "tool_input": {"command": "echo 'gitdir: /elsewhere' > .git"}}
        self.assertTrue([f for n, f in guard_command_rules.problems(pointer, tree)
                         if n == "stack"], "repointing the worktree's .git file was allowed")

    def test_the_stack_is_DERIVED_from_what_the_git_hooks_run(self):
        """A tool the gate gains is in the stack the day a hook names it - a list of tools would be
        the stack minus the next one."""
        self.assertFalse(self.stack(": > tools/later.py"))
        self.write_file(".githooks/commit-msg", '"$PY" -B tools/later.py "$1"' + LF)
        self.assertTrue(self.stack(": > tools/later.py"),
                        "a tool a git hook runs was not part of the stack")

    def test_a_command_in_ANOTHER_repository_writes_its_own_hooks(self):
        """A scratch repository a test builds has a `.githooks` of its own: a command that runs
        there and spells no path of this project names nothing of this project's stack."""
        other = tempfile.mkdtemp(prefix="other-repo-")
        self.addCleanup(shutil.rmtree, other, True)
        os.makedirs(os.path.join(other, ".githooks"))
        # A WRITE, NOT THE REMEDY: `chmod +x` is let through anywhere now, as the gate's own fix,
        # and asked nothing about where it runs - measured 2026-10-07, this plant stayed green.
        payload = {"tool_name": "Bash", "cwd": other,
                   "tool_input": {"command": "chmod -x .githooks/commit-msg"}}
        self.assertEqual([f for n, f in guard_command_rules.problems(payload, self.root)
                          if n == "stack"], [], "another repository's hooks were judged as ours")


class ACommitIsJudgedInTheRepositoryItLandsIn(ScratchProject, unittest.TestCase):
    """`guard_command_rules`: a commit's repository is part of the command.

    Measured 2026-09-23 on the project this came from: every commit was judged by that project's
    rules against that project's files, wherever it ran - a commit in a LINKED WORKTREE was
    refused because the worktree's own new files were not in the main tree, a commit in a scratch
    repository in a temp folder was refused for lacking the project's trailer, and `-F msg.txt`
    was read beside the project root while git reads it beside the directory it runs in."""

    def setUp(self):
        ScratchProject.setUp(self)
        self.git("init", "-q")
        self.git("config", "core.hooksPath", ".githooks")
        self.git("config", "user.email", "scratch@example.com")
        self.git("config", "user.name", "scratch")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "scratch")
        self.good = self.write_file("msg-good.txt", "a change" + LF + LF + "Signed-off" + LF)
        self.bare = self.write_file("msg-bare.txt", "a change" + LF)
        self.other = tempfile.mkdtemp(prefix="bundle-other-")
        self.addCleanup(shutil.rmtree, self.other, True)
        bounded.run(["git", "init", "-q", self.other], capture_output=True, timeout=60,
                    creationflags=NO_WINDOW)

    def refused(self, command, cwd=None, tool="Bash"):
        payload = {"tool_name": tool, "tool_input": {"command": command}}
        if cwd:
            payload["cwd"] = cwd
        return guard_command_rules.problems(payload, self.root)

    def test_a_message_file_is_read_where_the_SHELL_hands_it_to_git(self):
        """Measured 2026-10-07 on Windows: `git commit -F /c/Users/<you>/msg.txt` from Bash was
        refused as a message file that "does not exist yet" - Git for Windows' shell hands git
        `C:/Users/<you>/msg.txt`, and this read Python's `C:\\c\\Users\\...`. PowerShell hands git
        the path as typed, and there it is not there."""
        spelled = "/vb-msg-7f3e.txt"
        with mock.patch.object(bundle_shell, "WINDOWS", True), \
                mock.patch.object(bundle_shell, "msys_path",
                                  lambda path: self.good if path == spelled else None):
            command = "git commit -F %s" % spelled
            self.assertEqual(self.refused(command, tool="Bash"), [],
                             "a message file Git Bash hands to git was read as not there")
            self.assertIn("message", [n for n, _f in self.refused(command, tool="PowerShell")],
                          "a message file PowerShell cannot open was read as if it could")

    def test_a_cd_is_placed_where_the_SHELL_running_it_puts_it(self):
        """Measured 2026-10-07 on Windows: this read `/c/...` as a drive whatever the shell, and
        PowerShell reads it as `C:\\c\\...` and FAILS the cd - so a PowerShell `cd /c/<another
        repository>; git commit --no-verify` committed HERE, past the gate, while this placed it
        in the other repository. Asked as Windows on any machine, with Git for Windows' shell
        putting the path in the other repository."""
        nowhere = "/vb-other-repo-7f3e"
        with mock.patch.object(bundle_shell, "WINDOWS", True), \
                mock.patch.object(bundle_shell, "msys_path",
                                  lambda path: self.other if path == nowhere else None):
            skip = 'cd %s; git commit --no-verify -F "%s"' % (nowhere, self.bare)
            self.assertEqual(self.refused(skip, tool="Bash"), [],
                             "a Bash cd its shell puts in another repository was judged here")
            self.assertIn("gate", [n for n, _f in self.refused(skip, tool="PowerShell")],
                          "a PowerShell cd that fails let a commit skip the gate HERE")

    def test_a_commit_in_ANOTHER_repository_is_not_this_projects_to_judge(self):
        skip = 'git commit --no-verify -F "%s"' % self.bare
        self.assertTrue(self.refused(skip), "the control: a skipped gate HERE must be refused")
        for command, cwd in (('git -C "%s" commit --no-verify -F "%s"' % (self.other, self.bare),
                              None),
                             ('cd "%s" && %s' % (self.other, skip), None),
                             (skip, self.other)):
            with self.subTest(command=command, cwd=cwd):
                self.assertEqual(self.refused(command, cwd), [],
                                 "another repository's commit was judged by this one's rules")
        self.assertEqual(self.refused('git -C "%s" config core.hooksPath x' % self.other), [],
                         "another repository's configuration was refused by this one")

    def test_a_commit_in_ANOTHER_repository_that_carries_this_gate_runs_it_or_is_refused(self):
        """Measured 2026-10-09: the bundle's own clones had no `core.hooksPath`, a merge commit made
        in one went in with no gate asked, and a plant it emptied was found only at a project's
        install, after the push. Another repository's rules are its own; whether its gate RUNS is
        what a repository carrying a stamping `commit-msg` has already said."""
        hook = os.path.join(self.other, ".githooks", "commit-msg")
        os.makedirs(os.path.dirname(hook))
        with io.open(hook, "w", encoding="utf-8", newline=LF) as handle:
            handle.write("#!/bin/sh" + LF + 'exec python -B tools/gate_stamps.py stamp "$1"' + LF)
        os.chmod(hook, 0o755)

        def other(*args):
            return bounded.run(["git", "-C", self.other] + list(args), capture_output=True,
                               timeout=60, creationflags=NO_WINDOW)
        acts = (('git -C "%s" commit -F "%s"' % (self.other, self.bare), None),
                ('cd "%s" && git commit -F "%s"' % (self.other, self.bare), None),
                ('git commit -F "%s"' % self.bare, self.other),
                ('git -C "%s" merge topic' % self.other, None),
                ('git -C "%s" pull' % self.other, None))
        # UNSET, and SET TO A FOLDER THAT IS NOT THE GATE: "is the key set" is the cheap answer,
        # and git runs none of the gate's hooks under either.
        for value in (None, ".git/hooks"):
            if value:
                other("config", "core.hooksPath", value)
            for command, cwd in acts:
                with self.subTest(hooks=value, command=command):
                    self.assertIn("gate", [n for n, _f in self.refused(command, cwd)],
                                  "a commit went into another repository carrying this gate "
                                  "with the gate off")
        self.assertEqual(self.refused('git -C "%s" config core.hooksPath .githooks'
                                      % self.other), [], "the remedy the refusal names was refused")
        other("config", "core.hooksPath", ".githooks")
        for command, cwd in acts:
            with self.subTest(hooks=".githooks", command=command):
                self.assertEqual(self.refused(command, cwd), [],
                                 "a commit in a repository whose own gate runs was refused")
        # NOT EVERY HOOKS FOLDER IS THIS GATE: one whose commit-msg stamps nothing is that
        # repository's business, gated or not.
        with io.open(hook, "w", encoding="utf-8", newline=LF) as handle:
            handle.write("#!/bin/sh" + LF + "exit 0" + LF)
        other("config", "--unset", "core.hooksPath")
        self.assertEqual(self.refused(acts[0][0]), [],
                         "a repository with hooks of its own, not this gate, was judged by it")

    def test_a_commit_in_a_LINKED_WORKTREE_is_judged_by_that_worktrees_own_rules(self):
        tree = os.path.join(self.other, "wt")
        self.assertEqual(self.git("worktree", "add", "-q", tree).returncode, 0)
        self.addCleanup(self.git, "worktree", "remove", "--force", tree)
        # COUNTED BEFORE IT IS REWRITTEN: were the stub's marker to move, the replace below would
        # change nothing, the worktree's rule would be the main tree's, and the premise of this
        # test would quietly become a different one.
        stub = STUBS["zzstub_commit.py"]
        self.assertEqual(stub.count("Signed-off"), 1,
                         "the stub's marker moved - the rewrite below would change nothing")
        with io.open(os.path.join(tree, "tools", "zzstub_commit.py"), "w", encoding="utf-8",
                     newline=LF) as handle:
            handle.write(stub.replace("Signed-off", "Worktree-ok"))
        worktree_ok = self.write_file("msg-wt.txt", "a change" + LF + LF + "Worktree-ok" + LF)
        here = 'git commit -F "%s"' % worktree_ok
        self.assertIn("zzstub_commit", [n for n, _f in self.refused(here)],
                      "the control: this project's own rule refuses that message here")
        there = self.refused('git -C "%s" commit -F "%s"' % (tree, worktree_ok))
        self.assertEqual(there, [], "a worktree's commit was judged by the MAIN tree's rules")
        self.assertIn("zzstub_commit", [n for n, _f in self.refused(
            'git -C "%s" commit -F "%s"' % (tree, self.good))],
            "a worktree's own rule was not asked of a commit made in it")
        self.assertIn("gate", [n for n, _f in self.refused(
            'cd "%s" && git commit -n -F "%s"' % (tree, worktree_ok))],
            "a worktree's commit skipped the gate unrefused")

    def test_the_message_file_is_read_where_git_runs(self):
        os.makedirs(os.path.join(self.root, "sub"))
        with io.open(os.path.join(self.root, "sub", "m.txt"), "w", encoding="utf-8",
                     newline=LF) as handle:
            handle.write("from sub" + LF + LF + "Signed-off" + LF)
        self.write_file("m.txt", "from the root" + LF)
        self.assertTrue(self.refused("git commit -F m.txt"), "the control: the root's m.txt")
        self.assertEqual(self.refused("git -C sub commit -F m.txt"), [],
                         "`-C sub` read the message beside the root, not beside sub")
        self.assertEqual(self.refused("git commit -F m.txt",
                                      cwd=os.path.join(self.root, "sub")), [],
                         "a call running in sub read the message beside the root")

    def test_a_place_that_cannot_be_told_is_judged_as_THIS_project(self):
        """The cheapest way past a guard that honours where a commit runs is making it believe
        the commit runs somewhere else. Every one of these commits runs HERE."""
        skip = 'git commit -n -F "%s"' % self.good
        other = self.other
        for command in ('false && cd "%s"; %s' % (other, skip),
                        'true || cd "%s"; %s' % (other, skip),
                        '(cd "%s"); %s' % (other, skip),
                        '{ cd "%s"; }; %s' % (other, skip),
                        'cd "%s" | cat; %s' % (other, skip),
                        'cd $SOMEWHERE && %s' % skip,
                        'cd "%s" && popd && %s' % (other, skip),
                        'GIT_DIR="%s/.git" git -C "%s" commit -n -F "%s"'
                        % (self.root, other, self.good),
                        'git --git-dir="%s/.git" -C "%s" commit -n -F "%s"'
                        % (self.root, other, self.good),
                        "GIT_DIR='%s/.git' bash -c 'git -C \"%s\" commit -n -F \"%s\"'"
                        % (self.root, other, self.good)):
            with self.subTest(command):
                self.assertIn("gate", [n for n, _f in self.refused(command)],
                              "a commit this project cannot place skipped the gate unrefused")

    def test_a_refusal_of_a_commit_it_could_not_place_says_where_it_judged_it(self):
        """Measured 2026-09-24: `git -C "$W" commit`, in a worktree, was refused as though the
        worktree's own test class did not exist - true of the MAIN tree, where a commit nobody can
        place is judged - and nothing in the refusal said the main tree had been asked."""
        unplaced = self.refused('cd $SOMEWHERE && git commit -n -F "%s"' % self.good)
        self.assertIn("where", [n for n, _f in unplaced],
                      "an unplaceable commit was refused without saying where it was judged")
        placed = self.refused('git commit -n -F "%s"' % self.good)
        self.assertNotIn("where", [n for n, _f in placed],
                         "a commit that could be placed was said not to have been")
        self.assertEqual(self.refused('cd $SOMEWHERE && git commit -F "%s"' % self.good), [],
                         "saying where is a note on a refusal, never a refusal of its own")


class AWriteIsJudgedInTheCheckoutItLandsIn(ScratchProject, unittest.TestCase):
    """`bundle_rules.landing`, and the three hooks that judge a tree over it: a write, a command's
    write and a held change are each judged by the checkout of this repository they land in.

    Measured 2026-10-06 on the project this came from, in a session working in a linked worktree
    the harness made: the write hooks judged the worktree's files against the MAIN
    tree's modules and rules - a correct test refused, a write making thirty claims stale let
    through - and the main tree's `git status` cannot see that folder, so every command's write in
    it was judged by nobody while another session's held change refused the worktree's own acts.
    Commits had been judged this way since 2026-09-24; writes had not.
    """

    #: Each checkout's rule refuses a DIFFERENT word, so a verdict says whose rules were asked:
    #: a word only one of them refuses is an example where the answer moves.
    MAIN_WORD, TREE_WORD = "DEFECT", "WORKTREE_BAD"

    def setUp(self):
        ScratchProject.setUp(self)
        self.git("init", "-q")
        self.git("config", "user.email", "scratch@example.com")
        self.git("config", "user.name", "scratch")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "scratch")
        self.other = tempfile.mkdtemp(prefix="bundle-wt-")
        self.addCleanup(shutil.rmtree, self.other, True)
        self.inside = self.worktree(os.path.join(self.root, ".claude", "worktrees", "wt"), "in")
        self.outside = self.worktree(os.path.join(self.other, "wt"), "out")

    def worktree(self, tree, branch):
        """A linked worktree whose stub rule refuses `TREE_WORD` instead - committed there, so
        the rule's own change is that checkout's HEAD and not a change it judges."""
        self.assertEqual(self.git("worktree", "add", "-q", "-b", branch, tree).returncode, 0)
        self.addCleanup(self.git, "worktree", "remove", "--force", tree)
        stub = STUBS["zzstub_file.py"]
        self.assertEqual(stub.count('"%s"' % self.MAIN_WORD), 1,
                         "the stub's word moved - the rewrite below would change nothing")
        with io.open(os.path.join(tree, "tools", "zzstub_file.py"), "w", encoding="utf-8",
                     newline=LF) as handle:
            handle.write(stub.replace('"%s"' % self.MAIN_WORD, '"%s"' % self.TREE_WORD))
        done = bounded.run(["git", "commit", "-q", "-am", "this checkout's own rule"], cwd=tree,
                           capture_output=True, text=True, timeout=60, creationflags=NO_WINDOW)
        self.assertEqual(done.returncode, 0, done.stderr)
        return tree

    @staticmethod
    def spellings(path):
        """One file, three spellings: a guard keyed on a path is one spelling from blind."""
        head, name = os.path.split(path)
        out = [path, os.path.join(head, "..", os.path.basename(head), name)]
        if len(path) > 1 and path[1] == ":":
            out.append(path[0].swapcase() + path[1:])
        return out

    @staticmethod
    def write(path, text):
        return {"tool_name": "Write", "tool_input": {"file_path": path, "content": text}}

    @staticmethod
    def bash(command, cwd):
        return {"tool_name": "Bash", "tool_input": {"command": command}, "cwd": cwd}

    def test_a_write_is_judged_by_the_rules_of_the_checkout_it_lands_in(self):
        main_bad, tree_bad = "x = 1  # %s" % self.MAIN_WORD + LF, "x = 1  # %s" % self.TREE_WORD + LF
        here = os.path.join(self.root, "src", "b.py")
        self.assertEqual(guard_write_rules.verdict(self.write(here, main_bad), self.root)[0], 2,
                         "the control: this tree's own rule refuses its own word here")
        self.assertEqual(guard_write_rules.verdict(self.write(here, tree_bad), self.root)[0], 0,
                         "the control: this tree's rule lets the worktree's word through")
        for tree in (self.inside, self.outside):
            for spelled in self.spellings(os.path.join(tree, "src", "b.py")):
                with self.subTest(tree=tree, spelled=spelled):
                    self.assertEqual(guard_write_rules.verdict(self.write(spelled, main_bad),
                                                               self.root)[0], 0,
                                     "a worktree's write was judged by the MAIN tree's rules")
                    code, said = guard_write_rules.verdict(self.write(spelled, tree_bad),
                                                           self.root)
                    self.assertEqual(code, 2, "a write landing in a worktree was not asked that "
                                              "worktree's own rules")
                    self.assertIn("zzstub_file", said)

    def test_a_commands_write_in_a_worktree_is_judged_and_held_by_that_worktree(self):
        for tree in (self.inside, self.outside):
            with self.subTest(tree=tree):
                with io.open(os.path.join(tree, "src", "c.py"), "w", encoding="utf-8",
                             newline=LF) as handle:
                    handle.write("y = 1  # %s" % self.TREE_WORD + LF)
                self.assertEqual(after_rules.verdict(self.bash("cp x src/c.py", self.root),
                                                     self.root)[0], 0,
                                 "the control: from the main tree the worktree is not seen")
                code, said = after_rules.verdict(self.bash("cp x src/c.py", tree), self.root)
                self.assertEqual(code, 2, "a command's write in a worktree was judged by nobody")
                self.assertIn("src/c.py", said)
                self.assertEqual(guard_held_writes.verdict(self.bash("python build.py", tree),
                                                           self.root)[0], 2,
                                 "a change held in a worktree did not hold the next act there")
                self.assertEqual(guard_held_writes.verdict(self.bash("python build.py",
                                                                     self.root), self.root)[0],
                                 0, "a worktree's held change refused an act in the main tree")

    def test_a_change_held_in_the_main_tree_does_not_hold_a_worktree_session(self):
        self.write_file("src/d.py", "z = 1  # %s" % self.MAIN_WORD + LF)
        self.assertEqual(after_rules.verdict(self.bash("cp x src/d.py", self.root),
                                             self.root)[0], 2,
                         "the control: the main tree's own change is held there")
        self.assertEqual(guard_held_writes.verdict(self.bash("python build.py", self.root),
                                                   self.root)[0], 2,
                         "the control: the main tree's next act is refused")
        for tree in (self.inside, self.outside):
            with self.subTest(tree=tree):
                self.assertEqual(guard_held_writes.verdict(self.bash("python build.py", tree),
                                                           self.root)[0], 0,
                                 "another session's held change in the main tree refused a "
                                 "worktree's own act")
                self.assertEqual(guard_held_writes.verdict(self.write(
                    os.path.join(tree, "src", "e.py"), "e = 1" + LF), self.root)[0], 0,
                    "a write into a worktree was held by the main tree's change")

    def test_a_READ_in_a_worktree_is_refused_under_that_worktrees_own_lock(self):
        """THE READ HALF OF THE SAME CLASS. A mutation run in a worktree writes the worktree's
        `.mutation-in-flight`, and the read guard read only the project's. Measured 2026-10-07 on
        the project this came from: a plant drive running in a worktree while the same session
        read that worktree's modules, each read unguarded."""
        import guard_mutation_read
        for tree in (self.inside, self.outside):
            with self.subTest(tree=tree):
                module = os.path.join(tree, "src", "a.py")
                read = {"tool_name": "Read", "tool_input": {"file_path": module}}
                run_there = self.bash("cat src/a.py", tree)
                self.assertFalse(guard_mutation_read.judged(read, self.root)[0],
                                 "the control: with no lock anywhere a read must pass")
                with io.open(os.path.join(tree, guard_mutation_read.LOCK), "w", encoding="utf-8",
                             newline=LF) as handle:
                    handle.write("src/a.py" + LF)
                try:
                    for spelled in self.spellings(module):
                        self.assertTrue(guard_mutation_read.judged(
                            {"tool_name": "Read", "tool_input": {"file_path": spelled}},
                            self.root)[0], "a Read of a module a worktree's mutation run holds "
                                           "was let through: %s" % spelled)
                    self.assertTrue(guard_mutation_read.judged(run_there, self.root)[0],
                                    "a command reading a module a worktree's mutation run holds "
                                    "was let through")
                    self.assertFalse(guard_mutation_read.judged(self.bash("cat src/a.py",
                                                                          self.root),
                                                                self.root)[0],
                                     "the worktree's lock refused a read of the MAIN tree's file")
                finally:
                    os.remove(os.path.join(tree, guard_mutation_read.LOCK))

    def test_a_FORGED_git_file_does_not_make_a_folder_a_checkout_no_rule_judges(self):
        """THE CHEAPEST WAY PAST a guard that honours where a file lands is a file saying it lands
        elsewhere. A `.git` file is one line, and one naming a real worktree's git folder makes git
        report the folder holding it as that worktree - measured before this was written."""
        gitdir = bounded.run(["git", "rev-parse", "--absolute-git-dir"], cwd=self.inside,
                             capture_output=True, text=True, timeout=60,
                             creationflags=NO_WINDOW).stdout.strip()
        self.write_file("src/sub/.git", "gitdir: %s" % gitdir + LF)
        sub = os.path.join(self.root, "src", "sub")
        top = bounded.run(["git", "rev-parse", "--show-toplevel"], cwd=sub, capture_output=True,
                          text=True, timeout=60, creationflags=NO_WINDOW).stdout.strip()
        self.assertEqual(os.path.normcase(os.path.realpath(top)),
                         os.path.normcase(os.path.realpath(sub)),
                         "the premise: git reads the forged file as a checkout of its own")
        code, _said = guard_write_rules.verdict(self.write(
            os.path.join(sub, "x.py"), "x = 1  # %s" % self.MAIN_WORD + LF), self.root)
        self.assertEqual(code, 2, "a forged .git file made a folder of this tree a checkout no "
                                  "rule judges")


class GitBehindAWrapperIsStillGit(ScratchProject, unittest.TestCase):
    """`guard_command_rules`: git started by anything that only starts it is still git.

    Measured 2026-09-23: `--no-verify` was refused as `git commit --no-verify` and let through in
    fifteen other spellings of the same act, every one visible in the call's own text."""

    def setUp(self):
        ScratchProject.setUp(self)
        self.git("init", "-q")
        self.git("config", "core.hooksPath", ".githooks")
        self.good = self.write_file("msg-good.txt", "a change" + LF + LF + "Signed-off" + LF)

    def refused(self, command):
        payload = {"tool_name": "Bash", "tool_input": {"command": command}}
        return [n for n, _f in guard_command_rules.problems(payload, self.root)]

    def test_every_wrapper_that_only_starts_git_is_read_through(self):
        inner = 'git commit --no-verify -F "%s"' % self.good
        for spelled in ("& " + inner, "env A=1 " + inner, "command " + inner, "exec " + inner,
                        "time " + inner, "nice -n 5 " + inner, "xargs " + inner,
                        "(" + inner + ")", "{ " + inner + "; }", "true | " + inner,
                        # A RUNNER'S OPERAND IS NOT THE PROGRAM - measured by the peer session:
                        # `timeout 600 git commit --no-verify` walked past as the program
                        # `timeout`, its DURATION the word before git.
                        "timeout 600 " + inner, "timeout -s KILL 600 " + inner,
                        "taskset 0x1 " + inner, "Measure-Command { " + inner + " }"):
            with self.subTest(spelled):
                self.assertIn("gate", self.refused(spelled),
                              "a wrapper that only starts git let the gate be skipped")

    def test_a_command_HANDED_to_a_shell_is_read_as_a_command(self):
        inner = "git commit --no-verify -F %s" % self.good.replace(chr(92), "/")
        for spelled in ("bash -c '%s'" % inner, "sh -lc '%s'" % inner,
                        "powershell -NoProfile -Command '%s'" % inner,
                        "pwsh -w hidden -c '%s'" % inner, "cmd /c %s" % inner,
                        "iex '%s'" % inner, "Invoke-Expression '%s'" % inner,
                        "eval '%s'" % inner, "bash -c \"bash -c '%s'\"" % inner):
            with self.subTest(spelled):
                self.assertIn("gate", self.refused(spelled),
                              "a command handed to a shell skipped the gate unread")
        encoded = base64.b64encode(inner.encode("utf-16-le")).decode("ascii")
        self.assertIn("gate", self.refused("powershell -EncodedCommand " + encoded),
                      "an encoded PowerShell command skipped the gate unread")
        self.assertIn("gate", self.refused("powershell -EncodedCommand not*base64"),
                      "a command nobody could decode was read as no command")

    def test_the_path_to_git_is_still_git(self):
        """The cheapest way past a check that knows the word `git` is the path to it."""
        for program in ('"C:/Program Files/Git/cmd/git.exe"', "/usr/bin/git", "git.exe"):
            with self.subTest(program):
                self.assertIn("gate", self.refused('& %s commit -n -F "%s"'
                                                   % (program, self.good)),
                              "git named by its path skipped the gate")

    def test_an_ALIAS_is_read_as_what_it_runs(self):
        self.assertIn("gate", self.refused('git -c alias.ci="commit --no-verify" ci -F "%s"'
                                           % self.good),
                      "an alias given on the command line skipped the gate")
        self.git("config", "alias.sneak", "commit -n")
        self.git("config", "alias.shelled", "!git commit --no-verify")
        for alias in ("sneak", "shelled"):
            with self.subTest(alias):
                self.assertIn("gate", self.refused('git %s -F "%s"' % (alias, self.good)),
                              "an alias in the repository's configuration skipped the gate")
        self.assertEqual(self.refused("bash -c 'echo git is a word here'"), [],
                         "a shell that runs no git was refused")


# ----------------------------------------------------------------------------- the moment after

class WhatOnlyTheChangedTreeCanAnswerIsAskedTheMomentAfter(unittest.TestCase):
    """`after_rules`: the verdicts that are a command run over the files on disk."""

    def test_only_findings_the_call_ADDED_are_handed_back(self):
        self.assertEqual(after_rules.new_findings({"r": ["a"]}, {"r": ["a", "b"], "s": []}),
                         {"r": ["b"]})
        self.assertEqual(after_rules.new_findings({"r": ["a"]}, {"r": ["a"]}), {})

    def test_the_tree_state_moves_when_a_file_changes_and_not_otherwise(self):
        folder = tempfile.mkdtemp(prefix="after-state-")
        self.addCleanup(shutil.rmtree, folder, True)
        bounded.run(["git", "init", "-q"], cwd=folder, capture_output=True, timeout=60,
                    creationflags=NO_WINDOW)
        path = os.path.join(folder, "a.txt")
        with io.open(path, "w", encoding="utf-8") as handle:
            handle.write("one")
        first = after_rules.tree_state(folder)
        self.assertEqual(first, after_rules.tree_state(folder))
        with io.open(path, "w", encoding="utf-8") as handle:
            handle.write("one and two")
        self.assertNotEqual(first, after_rules.tree_state(folder),
                            "a changed file left the state where it was, so it is never re-asked")

    def test_an_AT_AFTER_rule_is_asked_and_a_broken_one_is_reported(self):
        folder = tempfile.mkdtemp(prefix="after-rules-")
        self.addCleanup(shutil.rmtree, folder, True)
        os.makedirs(os.path.join(folder, "tools"))
        with io.open(os.path.join(folder, "CLAUDE.md"), "w", encoding="utf-8") as handle:
            handle.write("# scratch" + LF)
        with io.open(os.path.join(folder, "tools", "zzstub_after.py"), "w",
                     encoding="utf-8") as handle:
            handle.write('AT_AFTER = "now"' + LF + LF + LF + "def now(root):" + LF
                         + '    return ["an item re-opened"]' + LF)
        sys.modules.pop("zzstub_after", None)
        self.addCleanup(sys.modules.pop, "zzstub_after", None)
        self.assertEqual(bundle_rules.ask_after(folder), {"zzstub_after": ["an item re-opened"]})


class AnAnswerTakenWithAMutantOnDiskIsNeitherGivenNorKept(ScratchProject, unittest.TestCase):
    """While a mutation run holds its lock, one module on disk is an injected fault that exists for
    seconds. A rule asked then answers about that mutant - and `after_rules` kept each answer as
    its memo, so a finding the mutant happened to produce was not NEW when the real tree produced
    it, and was never said. Measured on the project this came from: an item reported re-opened
    after every call while a proof run had a module mutated."""

    def setUp(self):
        ScratchProject.setUp(self)
        self.git("init", "-q")
        self.git("config", "user.email", "scratch@example.com")
        self.git("config", "user.name", "scratch")
        self.write_file("tools/zzstub_after.py", 'AT_AFTER = "now"' + LF + LF + LF
                        + "def now(root):" + LF + '    return ["an item re-opened"]' + LF)
        sys.modules.pop("zzstub_after", None)
        self.addCleanup(sys.modules.pop, "zzstub_after", None)
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "scratch")
        self.lock = os.path.join(self.root, bundle_rules.LOCK)

    def hold_the_lock(self, rel):
        with io.open(self.lock, "w", encoding="utf-8", newline=LF) as handle:
            handle.write(rel + LF)

    def test_an_AT_AFTER_rule_is_neither_asked_nor_kept_under_the_lock(self):
        self.hold_the_lock("src/a.py")
        self.assertEqual(after_rules.after_findings(self.root), [],
                         "a rule was asked with a mutant on disk")
        self.assertEqual(after_rules._held(self.root), {},
                         "an answer taken with a mutant on disk was kept as the memo")
        os.remove(self.lock)
        self.assertTrue(after_rules.after_findings(self.root),
                        "the finding was never said once the module was back")

    def test_a_plants_own_test_is_ASKED_and_keeps_no_memo(self):
        """The sibling of a plant's own test recording a hold: under the excuse the lock reads as
        absent, so the rule is asked of the mutant - right, it is the test's subject - and the
        answer was KEPT as the memo, so the real tree's same finding was never NEW again."""
        self.hold_the_lock("src/a.py")
        with mock.patch.dict(os.environ, {bundle_rules.PLANTED: "src/a.py"}):
            self.assertTrue(after_rules.after_findings(self.root),
                            "the control: the plant's own test was not shown its subject")
        self.assertEqual(after_rules._held(self.root), {},
                         "an answer the plant's own test took of a mutant was kept as the memo")
        os.remove(self.lock)
        self.assertTrue(after_rules.after_findings(self.root),
                        "the finding was never said once the module was back")

    def test_the_module_under_mutation_is_not_judged_as_a_commands_write(self):
        self.hold_the_lock("src/a.py")
        self.write_file("src/a.py", "MADE = x" + LF + "USE(x)  # DEFECT" + LF)
        judged = bundle_rules.judge_unread(self.root, [])
        self.assertEqual(judged["fresh"], [],
                         "the mutant on disk was judged, and held, as a command's write")


#: A rule that reads ANOTHER file off disk while it judges one, as the install check reads every
#: installed copy: a planted `twin/base.py` HIDES the finding it makes about `twin/use.py`. A mutant
#: does not have to make a finding to do damage - hiding one records a change as clean, and a
#: change recorded clean is never judged again.
TWIN = ("import io" + LF + "import os" + LF + LF
        + 'AT_WRITE = "found_in"' + LF + 'AT_SCOPE = r"^twin/use\\.py$"' + LF + LF + LF
        + "def found_in(rel, text):" + LF
        + "    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))" + LF
        + '    base = os.path.join(root, "twin", "base.py")' + LF
        + '    with io.open(base, encoding="utf-8") as handle:' + LF
        + '        if "PLANTED" in handle.read():' + LF
        + "            return []" + LF
        + '    return [line for line in text.splitlines() if "FLAW" in line]' + LF)

#: The same shape asked the moment after: an item that re-opens unless `twin/base.py` is planted.
TWIN_AFTER = ("import io" + LF + "import os" + LF + LF + 'AT_AFTER = "now"' + LF + LF + LF
              + "def now(root):" + LF
              + '    base = os.path.join(root, "twin", "base.py")' + LF
              + '    with io.open(base, encoding="utf-8") as handle:' + LF
              + '        return [] if "PLANTED" in handle.read() else ["an item re-opened"]' + LF)


class AVerdictTakenWhileAMutantCameOrWentIsUnknown(ScratchProject, unittest.TestCase):
    """A LOCK READ AT ONE MOMENT SAYS NOTHING ABOUT A FILE READ AT ANOTHER.

    Measured 2026-09-26 on the project this came from. A commit's gate re-drove the plants held in
    the files it changed - 49 in one file, back to back, each under the lock naming that file. In
    another session the judgement of a command's writes read the tree's status during one plant,
    the lock in the gap between two, and the file's text during the next: the planted text was
    judged as a command's write with no lock in sight, and every act in that session was HELD on
    `lint 0 -> 1`, an undefined name the plant had made. The same afternoon a rule that compares
    every installed copy read a file OTHER than the one being judged while that file was planted,
    and held the tree on `bundle_install 1 -> 2`. Both planted states were back byte-for-byte
    seconds later.

    Skipping the file the lock names, read once, was never enough: the lock is read at one moment
    and the tree at others, and a rule reads more than the file it is asked about. So a verdict is
    taken only over a stretch no mutation run touched - the lock, and a count of its releases, read
    before anything and after everything. A judgement that saw a lock, or saw the count move, is
    UNKNOWN: nothing held, nothing recorded as judged, and said - and the same change is judged
    the first time the lock is gone.
    """

    def setUp(self):
        ScratchProject.setUp(self)
        self.write_file("tools/zztwin.py", TWIN)
        self.write_file("tools/zztwin_after.py", TWIN_AFTER)
        for name in ("zztwin", "zztwin_after"):
            sys.modules.pop(name, None)
            self.addCleanup(sys.modules.pop, name, None)
        self.write_file("twin/base.py", "BASE = 1" + LF)
        self.write_file("twin/use.py", "x = 1" + LF)
        for args in (["init", "-q"], ["add", "-A"],
                     ["-c", "user.email=t@example.invalid", "-c", "user.name=t", "-c",
                      "core.autocrlf=false", "commit", "-qm", "base"]):
            done = self.git(*args)
            self.assertEqual(done.returncode, 0, done.stderr)
        self.assertEqual(bundle_rules.judge_unread(self.root)["held"], {})

    def plant(self, rel, text):
        """Put `text` at `rel` under the lock naming it, as a plant runner does; the original."""
        self.assertIsNone(bundle_rules.take_lock(self.root, rel), "the lock could not be taken")
        with io.open(os.path.join(self.root, rel), encoding="utf-8", newline="") as handle:
            original = handle.read()
        self.write_file(rel, text)
        return original

    def unplant(self, rel, original):
        """The file back FIRST, then the lock released - a plant runner's order."""
        self.write_file(rel, original)
        self.assertIsNone(bundle_rules.release_lock(self.root), "the lock could not be released")

    def test_a_lock_read_BETWEEN_two_plants_does_not_let_the_next_plant_be_judged(self):
        """The instance: status during one plant, lock in the gap, text during the next - which
        begins here as the rules are gathered, after the lock was read and before any file was."""
        honest = "MADE = x" + LF + "USE(x)" + LF + "y = 2" + LF
        self.write_file("src/a.py", honest)
        self.assertEqual(bundle_rules.judge_unread(self.root)["held"], {},
                         "the control: an honest change was held")
        real_dirty, real_rules, second = bundle_rules.dirty, bundle_rules.rules, {}

        def dirty(root):
            original = self.plant("src/a.py", honest + "z = 1  # DEFECT one" + LF)
            try:
                return real_dirty(root)
            finally:
                self.unplant("src/a.py", original)

        def rules(root, honour_lock=True):
            if not second:
                second["original"] = self.plant("src/a.py", honest + "z = 1  # DEFECT two" + LF)
            return real_rules(root, honour_lock)

        with mock.patch.object(bundle_rules, "dirty", dirty), \
                mock.patch.object(bundle_rules, "rules", rules):
            judged = bundle_rules.judge_unread(self.root)
        if second:
            self.unplant("src/a.py", second["original"])
        self.assertTrue(second, "the control: the second plant was never laid")
        self.assertEqual(judged["held"], {},
                         "a PLANTED text was held as a command's write - the lock was read in the "
                         "gap between two plants and the file during the next")
        self.assertTrue(judged.get("unknown"),
                        "a judgement a mutation run moved under was given as a verdict")
        self.assertEqual(bundle_rules.judge_unread(self.root)["held"], {},
                         "the honest change was held once the plants were done")

    def test_a_lock_naming_ANOTHER_file_leaves_every_verdict_UNKNOWN_and_none_clean(self):
        """The evasion: honouring the lock only for the file it names. A rule reads more than the
        file it is asked about, and a mutant that HIDES a finding records a change as clean."""
        original = self.plant("twin/base.py", "BASE = 1  # PLANTED" + LF)
        self.write_file("twin/use.py", "x = 1  # FLAW" + LF)
        judged = bundle_rules.judge_unread(self.root)
        self.assertEqual(judged["held"], {})
        self.assertTrue(judged.get("unknown"), "a verdict taken beside a mutant was given")
        self.assertIn("twin/use.py", judged.get("pending") or [],
                      "what was left unjudged was not named")
        self.unplant("twin/base.py", original)
        self.assertIn("twin/use.py", bundle_rules.judge_unread(self.root)["held"],
                      "a change judged beside a mutant was recorded CLEAN, and never judged again")

    def test_a_plant_that_came_AND_WENT_during_the_judgement_leaves_it_UNKNOWN(self):
        """No lock at the start, none at the end, and a rule read the mutant in between. Only a
        count of the lock's releases can say so, which is why every release is counted."""
        self.write_file("twin/use.py", "x = 1  # FLAW" + LF)
        real = bundle_rules.ask_changes

        def ask_changes(root, changes, found=None):
            original = self.plant("twin/base.py", "BASE = 1  # PLANTED" + LF)
            try:
                return real(root, changes, found)
            finally:
                self.unplant("twin/base.py", original)

        with mock.patch.object(bundle_rules, "ask_changes", ask_changes):
            judged = bundle_rules.judge_unread(self.root)
        self.assertTrue(judged.get("unknown"),
                        "a plant came and went during the judgement, and it was given as a verdict")
        self.assertIn("twin/use.py", bundle_rules.judge_unread(self.root)["held"],
                      "a change judged while a plant came and went was recorded CLEAN")

    def test_a_WRITE_judged_while_a_plant_came_and_went_is_asked_again(self):
        """At the write: the rule's answer was taken beside a plant that was gone by the end, so
        the write would be let through on the mutant's answer. It is asked again instead."""
        real, first = bundle_rules._ask_one, {}

        def ask_one(root, rule, rel, before, after):
            if rule.name != "zztwin" or first:
                return real(root, rule, rel, before, after)
            first["done"] = True
            original = self.plant("twin/base.py", "BASE = 1  # PLANTED" + LF)
            try:
                return real(root, rule, rel, before, after)
            finally:
                self.unplant("twin/base.py", original)

        with mock.patch.object(bundle_rules, "_ask_one", ask_one):
            worse = bundle_rules.ask_write(self.root, "twin/use.py", "x = 1" + LF,
                                           "x = 1  # FLAW" + LF)
        self.assertTrue(first, "the control: the plant was never laid under the rule")
        self.assertIn("zztwin", worse,
                      "a write was let through on an answer taken beside a plant")

    def test_an_AT_AFTER_answer_taken_while_a_plant_came_and_went_is_not_kept(self):
        """The moment after: an answer taken beside a plant is neither said nor kept as the memo,
        so the finding the mutant hid is said the first time the file is back."""
        real = bundle_rules.ask_after

        def ask_after(root, found=None):
            original = self.plant("twin/base.py", "BASE = 1  # PLANTED" + LF)
            try:
                return real(root, found)
            finally:
                self.unplant("twin/base.py", original)

        with mock.patch.object(bundle_rules, "ask_after", ask_after):
            self.assertEqual(after_rules.after_findings(self.root), [])
        self.assertEqual(after_rules._held(self.root), {},
                         "an answer taken while a plant came and went was kept as the memo")
        self.assertTrue(any("an item re-opened" in line
                            for line in after_rules.after_findings(self.root)),
                        "the finding the mutant hid was never said once the file was back")

    def test_a_COMMIT_asked_while_a_mutation_run_holds_the_lock_is_UNKNOWN_not_clean(self):
        """A commit's rules read the tree too, and a commit made beside a mutant is judged by it.
        The same message with no lock is the control."""
        message = "Signed-off-by: t"
        original = self.plant("twin/base.py", "BASE = 1  # PLANTED" + LF)
        try:
            said = bundle_rules.ask_command(self.root, message, kind="AT_COMMIT")
        finally:
            self.unplant("twin/base.py", original)
        self.assertTrue(any("UNKNOWN" in line for lines in said.values() for line in lines),
                        "a commit asked beside a mutant was answered clean: %s" % said)
        self.assertEqual(bundle_rules.ask_command(self.root, message, kind="AT_COMMIT"), {},
                         "the control: the same message with no lock was refused")

    def test_the_lock_is_taken_ONCE_and_every_release_is_COUNTED(self):
        """The door every plant runner takes and leaves the lock by. A second take while it is
        held is two runs mutating one tree; a release nobody counted is a plant a judgement could
        straddle without seeing."""
        before = bundle_rules.lock_state(self.root)
        self.assertEqual(before[0], "")
        self.assertIsNone(bundle_rules.take_lock(self.root, "twin/base.py"))
        self.assertEqual(bundle_rules.lock_state(self.root)[0], "twin/base.py")
        self.assertIsNotNone(bundle_rules.take_lock(self.root, "src/a.py"),
                             "a second run took a lock another holds")
        self.assertIsNone(bundle_rules.release_lock(self.root))
        after = bundle_rules.lock_state(self.root)
        self.assertEqual(after[0], "", "the lock outlived its release")
        self.assertNotEqual(after[1], before[1], "a release was not counted")

    def test_a_lock_is_the_SUBJECT_only_of_its_own_plants_test(self):
        """Inside the process a plant runner starts for that plant's test, the lock on the planted
        file is the test's subject, not a reason for UNKNOWN - the runner names the file. Named
        wrongly, or not at all, the lock stands: a marker that excused every lock would switch the
        whole of this off for anyone who set it."""
        self.assertIsNone(bundle_rules.take_lock(self.root, "twin/base.py"))
        self.addCleanup(bundle_rules.release_lock, self.root)
        with mock.patch.dict(os.environ, {bundle_rules.PLANTED: "twin/base.py"}):
            self.assertEqual(bundle_rules.locked(self.root), "",
                             "the plant's own test was told its subject was a fault beside it")
        with mock.patch.dict(os.environ, {bundle_rules.PLANTED: "src/a.py"}):
            self.assertEqual(bundle_rules.locked(self.root), "twin/base.py",
                             "a marker naming another file excused the lock")
        self.assertEqual(bundle_rules.locked(self.root), "twin/base.py", "the control")

    def test_a_plants_own_test_judges_its_subject_and_RECORDS_nothing(self):
        """THE HOLD NOBODY COULD LIFT, measured 2026-09-27 on the project this came from. A plant
        in the hook dispatcher was driven by a test that runs the real dispatcher against the live
        tree. Inside that test the lock is excused - PLANTED names its file - so the judgement read
        the planted dispatcher lock-free, held it on `lint 0 -> 1`, and SAVED that hold to the
        tree's own record. Every other process read the record: an unrelated session was refused
        every act on a file that was back at HEAD seconds later. The excuse is for the test's own
        view; a judgement taken under it is the test's alone and is never recorded."""
        self.write_file("src/other.py", "q = 1" + LF)
        for args in (["add", "-A"], ["-c", "user.email=t@example.invalid", "-c", "user.name=t",
                                     "-c", "core.autocrlf=false", "commit", "-qm", "other"]):
            self.assertEqual(self.git(*args).returncode, 0)
        self.assertEqual(bundle_rules.judge_unread(self.root)["held"], {})
        original = self.plant("src/a.py", "MADE = x" + LF + "USE(x)" + LF + "z = 1  # DEFECT" + LF)
        try:
            with mock.patch.dict(os.environ, {bundle_rules.PLANTED: "src/a.py"}):
                subject = bundle_rules.judge_unread(self.root)
            self.assertIn("src/a.py", subject["held"],
                          "the control: the plant's own test did not judge its subject")
            other = bundle_rules.judge_unread(self.root)
            self.assertEqual(other["held"], {},
                             "a hold the plant's own test took on its planted subject was RECORDED "
                             "for every other process to be refused on")
        finally:
            self.unplant("src/a.py", original)
        self.assertEqual(bundle_rules.judge_unread(self.root)["held"], {},
                         "the plant was back and something was still held")

    def test_a_hold_on_a_file_put_back_at_HEAD_goes_even_while_a_lock_stands(self):
        """THE HALF THAT KEPT IT: a lock anywhere made the judgement UNKNOWN, and UNKNOWN handed
        back the record's old holds untouched - so the "a file back at HEAD is never held" rule
        ran only when no lock stood, and a plant run of 730 plants kept a stale hold for as long
        as it ran. Comparing a file with HEAD asks no rule and reads no mutant, so it is asked
        under any lock but the one on that file."""
        self.write_file("src/other.py", "q = 1" + LF)
        for args in (["add", "-A"], ["-c", "user.email=t@example.invalid", "-c", "user.name=t",
                                     "-c", "core.autocrlf=false", "commit", "-qm", "other"]):
            self.assertEqual(self.git(*args).returncode, 0)
        base = "MADE = x" + LF + "USE(x)" + LF
        self.write_file("src/a.py", base + "z = 1  # DEFECT" + LF)
        self.assertIn("src/a.py", bundle_rules.judge_unread(self.root)["held"],
                      "the control: a command's defect was not held")
        self.write_file("src/a.py", base)
        original = self.plant("src/other.py", "q = 2  # PLANTED" + LF)
        try:
            judged = bundle_rules.judge_unread(self.root)
            self.assertTrue(judged.get("unknown"), "the control: the lock did not make it UNKNOWN")
            self.assertEqual(judged["held"], {},
                             "a file back at HEAD stayed held because a lock stood on ANOTHER file")
        finally:
            self.unplant("src/other.py", original)

    def test_a_hold_on_a_file_still_changed_STANDS_while_a_lock_stands(self):
        """The evasion the fix above invites: UNKNOWN read as "nothing is held". Then starting any
        plant run would be the cheapest way past every hold in the tree."""
        self.write_file("src/other.py", "q = 1" + LF)
        for args in (["add", "-A"], ["-c", "user.email=t@example.invalid", "-c", "user.name=t",
                                     "-c", "core.autocrlf=false", "commit", "-qm", "other"]):
            self.assertEqual(self.git(*args).returncode, 0)
        self.write_file("src/a.py", "MADE = x" + LF + "USE(x)" + LF + "z = 1  # DEFECT" + LF)
        self.assertIn("src/a.py", bundle_rules.judge_unread(self.root)["held"])
        original = self.plant("src/other.py", "q = 2  # PLANTED" + LF)
        try:
            self.assertIn("src/a.py", bundle_rules.judge_unread(self.root)["held"],
                          "a real hold was lifted because a plant run held a lock elsewhere")
        finally:
            self.unplant("src/other.py", original)

    def test_the_note_under_a_lock_names_the_hold_that_stands(self):
        """It said "Nothing is held" while a recorded hold was refusing every act - the sentence
        the session reads beside the refusal, contradicting it."""
        self.write_file("src/other.py", "q = 1" + LF)
        for args in (["add", "-A"], ["-c", "user.email=t@example.invalid", "-c", "user.name=t",
                                     "-c", "core.autocrlf=false", "commit", "-qm", "other"]):
            self.assertEqual(self.git(*args).returncode, 0)
        self.write_file("src/a.py", "MADE = x" + LF + "USE(x)" + LF + "z = 1  # DEFECT" + LF)
        self.assertIn("src/a.py", bundle_rules.judge_unread(self.root)["held"])
        original = self.plant("src/other.py", "q = 2  # PLANTED" + LF)
        try:
            said = bundle_rules.unknown_note(bundle_rules.judge_unread(self.root))
        finally:
            self.unplant("src/other.py", original)
        self.assertIn("src/a.py", said, "the note did not name the hold that stands")
        self.assertNotIn("Nothing is held", said, "the note says nothing is held over a hold")

    def test_a_release_removes_ONLY_the_lock_its_own_run_took(self):
        """The refusal tells a person to delete a lock a killed run left, and a person can be
        wrong about which run is dead. Run A holds the lock; it is deleted; run B takes its own.
        A's release must leave B's lock where it is - and a lock that is simply GONE is not one
        A released. Found by the CTRMap session reading this door, 2026-09-26."""
        lock = os.path.join(self.root, bundle_rules.LOCK)
        self.assertIsNone(bundle_rules.take_lock(self.root, "twin/base.py"))
        os.remove(lock)
        said = bundle_rules.release_lock(self.root)
        self.assertTrue(said, "a lock somebody else removed was reported released by this run")
        self.assertIsNone(bundle_rules.take_lock(self.root, "twin/base.py"))
        with io.open(lock, "w", encoding="utf-8", newline=LF) as handle:
            handle.write("src/a.py" + LF + "another-run" + LF)
        said = bundle_rules.release_lock(self.root)
        self.assertTrue(said, "another run's lock was released as this run's own")
        self.assertTrue(os.path.exists(lock), "this run removed ANOTHER run's lock")
        os.remove(lock)


class AJudgeUnderMutationJudgesNothing(ScratchProject, unittest.TestCase):
    """A JUDGE CANNOT VOUCH FOR ITSELF.

    Measured 2026-09-26 on the project this came from, the afternoon the lock was first read around
    every judgement: a commit's gate re-drove the plants held in `bundle_rules.py` - the judge every
    hook imports - and with "the lock is never read" on disk, two sessions' hooks ran the planted
    judge and held the tree on a planted state. The reading of the lock was right, and it lived in
    the file being planted. So a second reading lives in `bundle_shell`, another file: the lock
    names ONE file, so one of the two readings is always the real code. The hooks here are a COPY
    inside the scratch project, run as the harness runs them, so the plant touches nothing real.
    """

    #: ONE LINE WITHOUT ITS ENDING. It carried "+ LF", and on a CRLF checkout - what git
    #: gives every Windows clone - that matched nothing, the plant landed nowhere, and five
    #: tests here failed on their own control. Without the ending, the substitution keeps
    #: whichever one the file came with.
    BLIND = ("    return locked(root), _released(root)", '    return "", ""')

    def setUp(self):
        ScratchProject.setUp(self)
        # THE FOLDER'S NAME IS THE INSTALL'S, read from where the hooks are - a project may keep
        # them beside its own stack - never spelled here.
        self.folder = ".claude/" + os.path.basename(HOOKS)
        self.hooks = os.path.join(self.root, *self.folder.split("/"))
        shutil.copytree(HOOKS, self.hooks,
                        ignore=shutil.ignore_patterns("__pycache__", "rules-cache"))
        for args in (["init", "-q"], ["add", "-A"],
                     ["-c", "user.email=t@example.invalid", "-c", "user.name=t", "-c",
                      "core.autocrlf=false", "commit", "-qm", "base"]):
            done = self.git(*args)
            self.assertEqual(done.returncode, 0, done.stderr)
        self.assertEqual(self.run_hook("guard_held_writes.py", self.shell("python src/run.py"))[0],
                         0, "the control: a clean tree was held")

    def run_hook(self, name, payload, planted=None):
        env = dict(os.environ)
        env.pop(bundle_rules.PLANTED, None)
        if planted is not None:
            env[bundle_rules.PLANTED] = planted
        done = bounded.run([sys.executable, "-B", os.path.join(self.hooks, name)],
                           input=json.dumps(payload), capture_output=True, text=True,
                           encoding="utf-8", errors="replace", timeout=300, cwd=self.root,
                           creationflags=NO_WINDOW, env=env)
        return done.returncode, done.stderr

    def test_the_plants_OWN_test_judges_the_planted_code_and_no_other_is_excused(self):
        """A test that drives a hook a plant broke must see the break: the runner names the
        planted file in the environment, and there the lock on THAT file is its subject. Measured
        2026-09-26: without it, three plants whose tests drive a real hook could not redden. The
        cheapest way past is a marker that excuses any lock, so one naming another file excuses
        nothing."""
        self.plant_the_judge()
        self.write_file("src/b.py", "x = 1  # DEFECT" + LF)
        code, said = self.run_hook("guard_held_writes.py", self.shell("python src/run.py"),
                                   planted=self.folder + "/bundle_rules.py")
        self.assertEqual(code, 2, "the plant's own test did not judge the planted code: %s"
                         % said[-300:])
        code, said = self.run_hook("guard_held_writes.py", self.shell("python src/run.py"),
                                   planted="src/other.py")
        self.assertEqual(code, 0, "a marker naming ANOTHER file excused the lock: %s"
                         % said[-300:])

    @staticmethod
    def shell(command):
        return {"tool_name": "Bash", "tool_input": {"command": command}}

    def test_the_judge_is_planted_on_a_CRLF_checkout_too(self):
        """Measured 2026-09-26: on a CRLF checkout BLIND carried a line break the file did not
        have, so the judge was never planted and five tests here failed on their own control.
        This converts its own copy of the judge to CRLF first, so it holds on ANY checkout."""
        path = os.path.join(self.hooks, "bundle_rules.py")
        with io.open(path, encoding="utf-8", newline="") as handle:
            text = handle.read()
        crlf = text.replace(chr(13) + LF, LF).replace(LF, chr(13) + LF)
        with io.open(path, "w", encoding="utf-8", newline="") as handle:
            handle.write(crlf)
        self.plant_the_judge()
        with io.open(path, encoding="utf-8", newline="") as handle:
            planted = handle.read()
        self.assertNotEqual(planted, crlf, "the judge could not be planted on a CRLF checkout")
        self.assertIn(chr(13) + LF, planted, "planting the judge changed its line endings")

    def plant_the_judge(self):
        path = os.path.join(self.hooks, "bundle_rules.py")
        with io.open(path, encoding="utf-8", newline="") as handle:
            original = handle.read()
        self.assertEqual(original.count(self.BLIND[0]), 1, "the control: the judge's reading moved")
        with io.open(path, "w", encoding="utf-8", newline="") as handle:
            handle.write(original.replace(*self.BLIND))
        self.write_file(bundle_rules.LOCK, self.folder + "/bundle_rules.py" + LF)
        self.addCleanup(self.unplant_the_judge, path, original)
        return path, original

    def unplant_the_judge(self, path, original):
        with io.open(path, "w", encoding="utf-8", newline="") as handle:
            handle.write(original)
        lock = os.path.join(self.root, bundle_rules.LOCK)
        if os.path.exists(lock):
            os.remove(lock)

    def test_a_judge_planted_BLIND_holds_nothing_and_the_change_waits_for_it(self):
        path, original = self.plant_the_judge()
        self.write_file("src/b.py", "x = 1  # DEFECT" + LF)
        code, said = self.run_hook("guard_held_writes.py", self.shell("python src/run.py"))
        self.assertEqual(code, 0, "a judge that was itself a plant held the tree: %s" % said[-300:])
        self.unplant_the_judge(path, original)
        self.assertEqual(self.run_hook("guard_held_writes.py", self.shell("python src/run.py"))[0],
                         2, "the change beside the planted judge was never judged once it was back")

    def test_the_moment_after_SAYS_it_did_not_judge(self):
        """Its OWN reading of the lock, alone. `bundle_plant` now loads a planted judge as git's
        index holds it, and the true judge reads the lock itself - so with the loader in place this
        reading is never the one that decides, and its plant could not redden. The loader is
        stubbed out here, as in a project whose hooks predate it, so this reading is what is left."""
        with io.open(os.path.join(self.hooks, "bundle_plant.py"), "w", encoding="utf-8",
                     newline=LF) as handle:
            handle.write("def begin(path, name):" + LF + "    return None" + LF)
        path, original = self.plant_the_judge()
        self.write_file("src/b.py", "x = 1  # DEFECT" + LF)
        code, said = self.run_hook("after_rules.py", self.shell("python src/run.py"))
        self.assertIn("NOT JUDGED YET", said,
                      "the moment after judged with a planted judge, or said nothing: %s"
                      % said[-300:])
        self.unplant_the_judge(path, original)
        self.assertEqual(self.run_hook("guard_held_writes.py", self.shell("python src/run.py"))[0],
                         2, "the change the moment after could not judge was never judged")

    def test_a_WRITE_is_not_asked_of_a_planted_judge_and_is_judged_once_it_is_back(self):
        """A planted judge can wrongly refuse a write as easily as wrongly allow one, so it is not
        asked; the write is judged by the moment after, the first time the judge is back."""
        path, original = self.plant_the_judge()
        write = {"tool_name": "Write", "tool_input": {
            "file_path": os.path.join(self.root, "src", "c.py"), "content": "y = 1  # DEFECT" + LF}}
        code, said = self.run_hook("guard_write_rules.py", write)
        self.assertEqual(code, 0, "a planted judge was asked about a write: %s" % said[-300:])
        self.write_file("src/c.py", "y = 1  # DEFECT" + LF)
        self.run_hook("after_rules.py", write)
        self.unplant_the_judge(path, original)
        self.assertEqual(self.run_hook("guard_held_writes.py", self.shell("python src/run.py"))[0],
                         2, "a write made while the judge was a plant was never judged")

    def test_a_COMMIT_is_UNKNOWN_while_its_judge_is_a_plant(self):
        self.assertEqual(self.git("config", "core.hooksPath", ".githooks").returncode, 0)
        commit = self.shell('git commit -m "Signed-off-by: t"')
        self.assertEqual(self.run_hook("guard_command_rules.py", commit)[0], 0,
                         "the control: the commit was refused with the judge intact")
        self.plant_the_judge()
        code, said = self.run_hook("guard_command_rules.py", commit)
        self.assertEqual(code, 2, "a commit was judged by a planted judge: %s" % said[-300:])
        self.assertIn("UNKNOWN", said)

    def test_every_hook_that_asks_the_judge_reads_the_lock_in_ANOTHER_file_first(self):
        """The class: every function outside the judge that asks it - judges a command's writes,
        a write, a command, a commit, the moment after - asks `bundle_shell.judge_under_mutation`
        first, and its answer DECIDES something: it stands in an `if`, itself or through the name
        it was given. Calling it and ignoring the answer is the cheapest way past a rule that
        only looks for the call. Derived from the hooks' source, so a hook written tomorrow is held
        to it."""
        entries = {"judge_unread", "ask_write", "ask_command", "ask_after", "ask_changes"}
        asking, unasked = [], []

        def decides(fn):
            ask = "bundle_shell.judge_under_mutation"
            named = {t.id for n in ast.walk(fn) if isinstance(n, ast.Assign)
                     and isinstance(n.value, ast.Call) and ast.unparse(n.value.func) == ask
                     for t in n.targets if isinstance(t, ast.Name)}
            for node in ast.walk(fn):
                if isinstance(node, (ast.If, ast.While, ast.IfExp)):
                    for sub in ast.walk(node.test):
                        if (isinstance(sub, ast.Call) and ast.unparse(sub.func) == ask) or (
                                isinstance(sub, ast.Name) and sub.id in named):
                            return True
            return False

        for name in sorted(os.listdir(HOOKS)):
            if not name.endswith(".py") or name == "bundle_rules.py":
                continue
            with io.open(os.path.join(HOOKS, name), encoding="utf-8") as handle:
                tree = ast.parse(handle.read())
            for fn in ast.walk(tree):
                if not isinstance(fn, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    continue
                calls = {ast.unparse(n.func) for n in ast.walk(fn) if isinstance(n, ast.Call)}
                if not any(c in {"bundle_rules." + e for e in entries} for c in calls):
                    continue
                asking.append("%s.%s" % (name[:-3], fn.name))
                if not decides(fn):
                    unasked.append("%s.%s" % (name[:-3], fn.name))
        self.assertIn("guard_held_writes.verdict", asking, "the scan no longer sees a known caller "
                                                           "- it is wrong, not clean")
        self.assertEqual(unasked, [], "these ask the judge without first asking whether the judge "
                                      "itself is under a mutation run's lock")


def _rule_call(node):
    """`<rule>.call(...)` - the expression that fetches a declared rule's entry point."""
    return (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
            and node.func.attr == "call")


class EveryAskingOfARuleReadsTheLockAroundIt(unittest.TestCase):
    """THE CLASS, NOT THE CHANNELS FOUND SO FAR.

    Four ways of asking a rule were found reading the tree with no reading of the lock around
    them - the judgement of a command's writes, the write's own asking, a commit's, and the moment
    after - and each was bracketed by `lock_state`. A fifth written tomorrow would read a mutant
    with every test above still green. So the rule is derived from the hooks' own source: every
    function that INVOKES a declared rule's entry point - `rule.call(...)(...)`, or a name bound
    from `rule.call(...)` and then called - reads `lock_state` itself, or is private and reached
    only through functions that do. A public one can be called from anywhere, so it must read it
    itself."""

    @staticmethod
    def functions():
        out = {}
        for name in sorted(os.listdir(HOOKS)):
            if not name.endswith(".py"):
                continue
            with io.open(os.path.join(HOOKS, name), encoding="utf-8") as handle:
                tree = ast.parse(handle.read())
            for node in ast.walk(tree):
                if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    out.setdefault("%s.%s" % (name[:-3], node.name), node)
        return out

    @staticmethod
    def invokes(node):
        bound = {t.id for sub in ast.walk(node) if isinstance(sub, ast.Assign)
                 and _rule_call(sub.value) for t in sub.targets if isinstance(t, ast.Name)}
        return any(isinstance(sub, ast.Call) and (_rule_call(sub.func) or (
            isinstance(sub.func, ast.Name) and sub.func.id in bound)) for sub in ast.walk(node))

    @staticmethod
    def called(node):
        for sub in ast.walk(node):
            if isinstance(sub, ast.Call) and isinstance(sub.func, ast.Name):
                yield sub.func.id, False
            elif isinstance(sub, ast.Call) and isinstance(sub.func, ast.Attribute):
                yield sub.func.attr, True

    def unsafe(self, functions):
        callers = {}
        for key, node in functions.items():
            module = key.split(".", 1)[0]
            for name, anywhere in self.called(node):
                for target in functions:
                    tmod, tname = target.split(".", 1)
                    if tname == name and (anywhere or tmod == module) and target != key:
                        callers.setdefault(target, set()).add(key)

        def safe(key, seen):
            node = functions[key]
            if any(name == "lock_state" for name, _anywhere in self.called(node)):
                return True
            if not key.split(".", 1)[1].startswith("_") or key in seen:
                return False
            who = callers.get(key, set())
            return bool(who) and all(safe(c, seen | {key}) for c in who)

        invokers = {key for key, node in functions.items() if self.invokes(node)}
        return invokers, sorted(k for k in invokers if not safe(k, frozenset()))

    def test_every_function_that_asks_a_rule_reads_the_lock_or_is_reached_only_through_one(self):
        invokers, unsafe = self.unsafe(self.functions())
        for known in ("bundle_rules._ask_one", "bundle_rules._ask_command_once",
                      "bundle_rules._tree_facts_once"):
            self.assertIn(known, invokers, "the scan no longer sees %s asking a rule - the scan "
                                           "is wrong, and a wrong scan reads as clean" % known)
        self.assertEqual(unsafe, [], "these ask a rule with no reading of the mutation lock "
                                     "around them - a plant laid meanwhile is judged as the tree")


# ----------------------------------------------------------------------------- a command's write

class ACommandsWriteIsJudgedAndHeldUntilRepaired(ScratchProject, unittest.TestCase):
    """`bundle_rules.judge_unread`, and `guard_held_writes` and `after_rules` over it.

    Every file here is written straight to disk, as a command writes - no hook is handed its
    text first, which is the whole of the hole this closes.
    """

    def setUp(self):
        ScratchProject.setUp(self)
        self.write_file("src/m.py", "MADE = y" + LF)
        self.write_file("src/u.py", "USE(y)" + LF)
        self.commit()
        self.assertEqual(bundle_rules.judge_unread(self.root)["held"], {})

    def commit(self, message="base"):
        steps = [] if os.path.isdir(os.path.join(self.root, ".git")) else [["init", "-q"]]
        for args in steps + [["add", "-A"],
                             ["-c", "user.email=t@example.invalid", "-c", "user.name=t", "-c",
                              "core.autocrlf=false", "commit", "-qm", message]]:
            done = self.git(*args)
            self.assertEqual(done.returncode, 0, done.stderr)

    def held(self, written=()):
        return sorted(bundle_rules.judge_unread(self.root, written)["held"])

    def refused(self, payload):
        return guard_held_writes.verdict(payload, self.root)[0] == 2

    @staticmethod
    def shell(command):
        return {"tool_name": "Bash", "tool_input": {"command": command}}

    def test_a_command_that_writes_a_defect_is_held_and_the_next_act_refused(self):
        self.write_file("src/b.py", "x = 1  # DEFECT" + LF)
        judged = bundle_rules.judge_unread(self.root)
        self.assertEqual(sorted(judged["held"]), ["src/b.py"])
        self.assertEqual(judged["fresh"], ["src/b.py"])
        self.assertIn("zzstub_file", judged["worse"])
        self.assertTrue(self.refused(self.shell("python src/run.py")),
                        "a held change let the next command build on it")
        self.assertTrue(self.refused({"tool_name": "Write", "tool_input": {
            "file_path": os.path.join(self.root, "src", "a.py"), "content": "z = 2" + LF}}))
        self.assertTrue(self.refused({"tool_name": "Agent", "tool_input": {
            "prompt": "carry on", "subagent_type": "general-purpose"}}))

    #: A rule that must RUN the change, as an isolation rule runs the changed tests: its child
    #: judges the SAME tree again, which asks this rule again. Bounded at three deep, so the
    #: recursion it exists to show costs seconds rather than every process on the machine.
    REJUDGE = '''import os
import subprocess
import sys

AT_WRITE_CHANGE = "change_in"
AT_SCOPE = r"^rejudge/"


def change_in(rel, before, after, root):
    depth = int(os.environ.get("ZZ_DEPTH", "0"))
    if depth >= 3:
        return []
    code = ("import sys; sys.path.insert(0, sys.argv[2]); import bundle_rules; "
            "print(bundle_rules.judge_unread(sys.argv[1])['blind'] or 'JUDGED')")
    done = subprocess.run([sys.executable, "-B", "-c", code, root, os.environ["ZZ_HOOKS"]],
                          env=dict(os.environ, ZZ_DEPTH=str(depth + 1)),
                          capture_output=True, text=True, timeout=120)
    with open(os.environ["ZZ_OUT"], "a", encoding="utf-8") as handle:
        handle.write("%d %s" % (depth + 1, (done.stdout or done.stderr).strip()[-300:]) + chr(10))
    return []
'''

    def test_a_rules_child_that_judges_the_same_tree_is_told_why_and_starts_nothing(self):
        """A JUDGEMENT MUST NOT START ITSELF AGAIN. Measured 2026-09-26 on the project this came
        from: a test that ran the real hooks made the isolation rule's child judge the real tree,
        which held that test's own file and ran it again in another child - about ninety
        processes, and every act in two sessions held until the change went back to HEAD."""
        from unittest import mock
        out = os.path.join(tempfile.mkdtemp(prefix="rejudge-"), "said.txt")
        self.addCleanup(shutil.rmtree, os.path.dirname(out), True)
        self.write_file("tools/zzstub_rejudge.py", self.REJUDGE)
        sys.modules.pop("zzstub_rejudge", None)
        self.addCleanup(sys.modules.pop, "zzstub_rejudge", None)
        self.commit("the rule")
        self.write_file("rejudge/x.txt", "changed" + LF)
        with mock.patch.dict(os.environ, {"ZZ_HOOKS": HOOKS, "ZZ_OUT": out}):
            os.environ.pop("ZZ_DEPTH", None)
            os.environ.pop(bundle_rules.JUDGING, None)
            bundle_rules.judge_unread(self.root)
            self.assertNotIn(bundle_rules.JUDGING, os.environ,
                             "the judgement left its mark on the process after it ended")
        with io.open(out, encoding="utf-8") as handle:
            said = handle.read().splitlines()
        self.assertEqual(len(said), 1, "a child judging the same tree started the rule again: %s"
                         % said)
        self.assertIn("already running", said[0],
                      "a child judging the same tree was not told why it may not: %s" % said)

    def test_the_same_tree_spelled_another_way_is_still_the_same_tree(self):
        """The cheapest way past a guard keyed on a path is another spelling of it: a drive
        letter in the other case, a `..` back into the same folder. The child finds its tree
        from where its hook sits, and nothing makes that spelling match the parent's."""
        from unittest import mock
        spellings = [os.path.join(self.root, "src", "..")]
        if os.name == "nt":
            spellings.append(self.root.upper())
        for spelled in spellings:
            with self.subTest(spelled=spelled):
                marked = {bundle_rules.JUDGING: bundle_rules._tree_key(self.root)}
                with mock.patch.dict(os.environ, marked):
                    judged = bundle_rules.judge_unread(spelled)
                self.assertIn("already running", judged["blind"],
                              "the tree being judged, spelled another way, was judged again")

    def test_the_turn_cannot_end_on_a_held_change_but_is_not_refused_twice(self):
        self.write_file("src/b.py", "x = 1  # DEFECT" + LF)
        self.assertTrue(self.refused({"hook_event_name": "Stop"}),
                        "the turn ended on a held change")
        self.assertFalse(self.refused({"hook_event_name": "Stop", "stop_hook_active": True}),
                         "the end of a turn was refused twice and would spin")

    #: A rule whose finding NAMES files it did not read the name of from the held text - as a
    #: kill-claim rule names the test making the claim, by its unittest id, when the MODULE the
    #: claim is about is what changed.
    NAMES = '''AT_WRITE = "named_in"
AT_SCOPE = r"^named/"


def named_in(rel, text):
    if "BREAK" not in text:
        return []
    return ["%s breaks the claim test_named.Claims.test_it makes; src/u.py:1 reads it" % rel]
'''

    def edit(self, rel):
        return {"tool_name": "Edit", "tool_input": {
            "file_path": os.path.join(self.root, rel.replace("/", os.sep)),
            "old_string": "y", "new_string": "w"}}

    def hold_a_change_whose_finding_names_two_files(self):
        self.write_file("tools/zzstub_names.py", self.NAMES)
        sys.modules.pop("zzstub_names", None)
        self.addCleanup(sys.modules.pop, "zzstub_names", None)
        self.write_file("tests/test_named.py", "y = 1" + LF)
        self.commit("the rule, and the test its finding names")
        self.write_file("named/x.py", "BREAK" + LF)
        judged = bundle_rules.judge_unread(self.root)
        self.assertEqual(sorted(judged["held"]), ["named/x.py"])
        return judged

    def test_a_file_a_held_finding_names_BY_PATH_is_writable_as_its_repair(self):
        """Measured 2026-09-26 on the project this came from: a held kill-claim finding named the
        test making the claim, and the Edit that would repair that test was refused - the change
        and its fix each writable only after the other. `src/u.py:1` is a path with a line."""
        self.hold_a_change_whose_finding_names_two_files()
        self.assertFalse(self.refused(self.edit("src/u.py")),
                         "a write to src/u.py, which the held finding names by path, was refused")

    def test_a_file_a_held_finding_names_BY_ITS_TEST_ID_is_writable_as_its_repair(self):
        """The case measured: the finding named the test as unittest does, `module.Class.test`,
        and the module resolves to a tracked file whatever folder it sits in."""
        self.hold_a_change_whose_finding_names_two_files()
        self.assertFalse(self.refused(self.edit("tests/test_named.py")),
                         "a write to tests/test_named.py, which the held finding names by its "
                         "test id, was refused")

    #: A rule whose finding names a file under a DOT-folder, as `bundle_install` names
    #: `.claude/bundle-install.json` - in the Windows spelling the finding used when measured.
    DOTTED = '''AT_WRITE = "dotted_in"
AT_SCOPE = r"^named/"


def dotted_in(rel, text):
    if "BREAK" not in text:
        return []
    return ["%s diverges: declare it in .dotted\\\\held.json, or see .dotted/other.json" % rel]
'''

    def test_a_file_under_a_DOT_FOLDER_a_held_finding_names_is_writable_as_its_repair(self):
        """Measured 2026-10-10 by the DF Engine worktree session: a held `bundle_install` finding
        named `.claude\\bundle-install.json`, every word of a finding was stripped of `.:-` at
        both ends, the path became `claude/bundle-install.json`, which does not exist - so the
        refusal named no file a repair may write, and the Edit of the very file the finding named
        was refused. Every path under `.claude/` or `.githooks/` was closed the same way."""
        self.write_file("tools/zzstub_dotted.py", self.DOTTED)
        sys.modules.pop("zzstub_dotted", None)
        self.addCleanup(sys.modules.pop, "zzstub_dotted", None)
        self.write_file(".dotted/held.json", "y" + LF)
        self.write_file(".dotted/other.json", "y" + LF)
        self.commit("the rule, and the dot-folder files its finding names")
        self.write_file("named/x.py", "BREAK" + LF)
        judged = bundle_rules.judge_unread(self.root)
        self.assertEqual(sorted(judged["held"]), ["named/x.py"])
        for rel in (".dotted/held.json", ".dotted/other.json"):
            with self.subTest(rel=rel):
                self.assertIn(rel, judged["named"],
                              "DOT: a dot-folder file the held finding names opened nothing: %r"
                              % judged["named"])
                self.assertFalse(self.refused(self.edit(rel)),
                                 "DOT: the write the finding names as the repair was refused")

    def test_a_file_NO_held_finding_names_is_still_refused(self):
        """The cheapest way past a hold: a write somewhere else."""
        judged = self.hold_a_change_whose_finding_names_two_files()
        self.assertTrue(self.refused(self.edit("src/m.py")),
                        "a write to src/m.py, which no held finding names, went through")
        # A LINE OF ITS OWN: `src/u.py` is inside the quoted finding too, so a substring of the
        # message would pass with the listing gone.
        said = bundle_rules.held_refusal(judged).splitlines()
        for rel in ("src/u.py", "tests/test_named.py"):
            self.assertIn("    " + rel, said, "the refusal does not say %s may be written" % rel)

    def test_a_name_the_HELD_CHANGE_wrote_opens_nothing(self):
        """A rule that quotes the file it judges - a line, a claim - names whatever that file
        spells. So the cheapest way past a hold is to make the held change carry the name of the
        file you want to write next. `zzstub_file`'s finding IS the offending line."""
        self.write_file("src/b.py", "x = 1  # DEFECT, then see src/m.py" + LF)
        self.assertEqual(self.held(), ["src/b.py"])
        self.assertTrue(self.refused(self.edit("src/m.py")),
                        "a name the held change wrote itself opened src/m.py to a write")

    def test_only_a_read_or_a_repair_of_the_held_path_goes_through(self):
        self.write_file("src/b.py", "x = 1  # DEFECT" + LF)
        target = os.path.join(self.root, "src", "b.py")
        for payload in (
                {"tool_name": "Read", "tool_input": {"file_path": target}},
                {"tool_name": "Edit", "tool_input": {"file_path": target, "old_string": "x",
                                                     "new_string": "w"}},
                {"tool_name": "Write", "tool_input": {
                    "file_path": os.path.join(self.root, "tools", "zzstub_file.py"),
                    "content": "AT_WRITE = 'found_in'" + LF}},
                self.shell("git checkout -- src/b.py"),
                self.shell("git status"),
                self.shell("python -m pytest src/b.py"),
                self.shell("python -B tools/zzstub_record.py record"),
                self.shell("sed -n 1,5p src/a.py"),
                {"tool_name": "Write", "tool_input": {
                    "file_path": os.path.join(os.path.dirname(self.root), "beside.txt"),
                    "content": "not this project's" + LF}}):
            self.assertFalse(self.refused(payload), json.dumps(payload))
        for command in ("python src/run.py # src/b.py", "git status; python src/run.py",
                        "sed -n -i s/x/y/ src/a.py"):
            self.assertTrue(self.refused(self.shell(command)),
                            "%r walked past a held change" % command)

    def test_a_rule_tool_behind_a_runner_is_still_a_repair(self):
        """`timeout 3000 python -B tools/dev/plants.py add x.json` is how a held change gets
        repaired, and reading `timeout` as the program refused it - the command gate had the same
        blind spot for `timeout ... git commit --no-verify`. The control: a runner in front of a
        command that is NOT a rule tool leaves it refused."""
        self.write_file("src/b.py", "x = 1  # DEFECT" + LF)
        for command in ("timeout 3000 python -B tools/zzstub_record.py record",
                        "env A=1 python tools/zzstub_record.py record",
                        "timeout -s KILL 60 python tools/zzstub_record.py",
                        "py tools" + chr(92) + "zzstub_record.py record"):
            self.assertFalse(self.refused(self.shell(command)), command)
        for command in ("timeout 60 python src/run.py", "env A=1 python src/run.py"):
            self.assertTrue(self.refused(self.shell(command)),
                            "%r walked past a held change" % command)

    def test_a_change_to_what_git_TRACKS_is_judged_again(self):
        self.write_file("tracked/t.py", "t = 1" + LF)
        self.assertEqual(self.held(), ["tracked/t.py"])
        self.assertEqual(self.git("add", "tracked/t.py").returncode, 0)
        self.assertEqual(self.held(), [],
                         "git add released nothing: the tree was compared by its bytes alone")

    def test_repairing_the_change_releases_it(self):
        self.write_file("src/b.py", "x = 1  # DEFECT" + LF)
        self.assertEqual(self.held(), ["src/b.py"])
        self.write_file("src/b.py", "x = 1" + LF)
        self.assertEqual(self.held(), [])
        self.assertFalse(self.refused(self.shell("python src/run.py")))

    def test_a_file_put_back_at_HEAD_is_never_held(self):
        # HEAD carries a debt, a command pays it, and putting the file back ADDS it again - a
        # growth against the tree as last judged. It is still the way out: HEAD is the last
        # tree the gates passed, and `git checkout -- <file>` is what every refusal here names.
        self.write_file("src/d.py", "d = 1  # DEFECT" + LF)
        self.commit("debt")
        self.assertEqual(self.held(), [])
        self.write_file("src/d.py", "d = 1" + LF)
        self.assertEqual(self.held(), [])
        self.write_file("src/d.py", "d = 1  # DEFECT" + LF)
        self.assertEqual(self.held(), [], "going back to the committed text is the way OUT")

    def test_a_deletion_is_judged_as_the_tree_it_leaves(self):
        os.remove(os.path.join(self.root, "src", "u.py"))
        judged = bundle_rules.judge_unread(self.root)
        self.assertEqual(sorted(judged["held"]), ["src/u.py"])
        self.assertIn("zzstub_tree", judged["worse"])

    def test_a_file_the_write_hook_already_asked_is_recorded_not_asked_twice(self):
        self.write_file("src/b.py", "x = 1  # DEFECT" + LF)
        self.assertEqual(self.held(written=["src/b.py"]), [])

    def test_a_change_is_judged_against_what_it_WAS_not_against_HEAD(self):
        self.write_file("src/b.py", "x = 1  # DEFECT" + LF)
        self.assertEqual(self.held(written=["src/b.py"]), [])
        self.write_file("src/b.py", "x = 1  # DEFECT" + LF + "z = 2" + LF)
        self.assertEqual(self.held(), [], "a command that kept the debt it found was refused")

    def test_with_no_record_the_before_is_HEAD_never_the_disk(self):
        self.write_file("src/b.py", "x = 1  # DEFECT" + LF)
        os.remove(os.path.join(self.root, bundle_rules.CACHE, bundle_rules.JUDGED))
        self.assertEqual(self.held(), ["src/b.py"],
                         "deleting the record made the disk the baseline - the cheapest way past")

    def bare_folder(self, prefix):
        folder = tempfile.mkdtemp(prefix=prefix)
        self.addCleanup(shutil.rmtree, folder, True)
        with io.open(os.path.join(folder, "CLAUDE.md"), "w", encoding="utf-8") as handle:
            handle.write("# scratch" + LF)
        return folder

    def test_a_repository_with_no_commit_takes_its_first_reading_as_the_baseline(self):
        folder = self.bare_folder("held-first-")
        with io.open(os.path.join(folder, "broken.py"), "w", encoding="utf-8") as handle:
            handle.write("def (:" + LF)
        bounded.run(["git", "init", "-q"], cwd=folder, capture_output=True, timeout=60,
                    creationflags=NO_WINDOW)
        judged = bundle_rules.judge_unread(folder, found=[])
        self.assertEqual((judged["held"], judged["blind"]), ({}, ""),
                         "a repository with nothing committed held its own starting files")

    def test_a_tree_git_cannot_read_refuses_everything_but_reads_and_git(self):
        folder = self.bare_folder("held-blind-")
        said = guard_held_writes.verdict(self.shell("python src/run.py"), folder)
        self.assertEqual(said[0], 2, "a tree nobody could read was treated as a clean one")
        self.assertIn("cannot be read", said[1])
        self.assertEqual(guard_held_writes.verdict(self.shell("git init"), folder)[0], 0)
        self.assertEqual(guard_held_writes.verdict({"tool_name": "Read", "tool_input": {
            "file_path": os.path.join(folder, "CLAUDE.md")}}, folder)[0], 0)

    def test_the_moment_after_a_command_hands_back_what_it_wrote_once(self):
        self.write_file("src/b.py", "x = 1  # DEFECT" + LF)
        code, said = after_rules.verdict(self.shell("cp x src/b.py"), self.root)
        self.assertEqual(code, 2)
        self.assertIn("src/b.py", said)
        self.assertEqual(after_rules.verdict(self.shell("git status"), self.root), (0, ""),
                         "a standing hold repeated after every call is how a guard gets tuned out")
        self.assertEqual(after_rules.written_by({"tool_name": "Write", "tool_input": {
            "file_path": os.path.join(self.root, "src", "c.py"), "content": ""}}, self.root),
            ["src/c.py"])

    def test_a_write_the_write_hook_did_NOT_judge_is_not_recorded_as_judged(self):
        """`after_rules.written_by` named every path a write-shaped call carried, at any depth, as
        asked by the write hook before it landed - and the write hook asks only a top-level
        `file_path` or `path` with `content`, `new_string` or `edits`. A notebook edit, or a write
        nested in a batching tool's input, was judged by neither: skipped before, recorded after."""
        rel = "src/a.py"
        judged = {"tool_name": "Edit", "tool_input": {
            "file_path": os.path.join(self.root, rel), "old_string": "x", "new_string": "y"}}
        self.assertEqual(after_rules.written_by(judged, self.root), [rel],
                         "a write the write hook judged was not recorded as judged")
        for unjudged in ({"tool_name": "NotebookEdit", "tool_input": {
                              "notebook_path": os.path.join(self.root, "n.ipynb"),
                              "new_source": "x = 1"}},
                         {"tool_name": "Batch", "tool_input": {"calls": [{
                             "file_path": os.path.join(self.root, rel), "content": "z = 1"}]}}):
            with self.subTest(unjudged["tool_name"]):
                self.assertEqual(after_rules.written_by(unjudged, self.root), [],
                                 "a write no hook judged was recorded as judged at the write")
        nested = {"tool_name": "Batch", "tool_input": {"calls": [{
            "file_path": os.path.join(self.root, ".git", "config"), "content": "x"}]}}
        self.assertEqual(guard_write_rules.verdict(nested, self.root)[0], 2,
                         "a write into .git nested in a batching tool's input went through")



class TheDeclaredBundlesInstallerRunsAsAStackTool(ScratchProject, unittest.TestCase):
    """F261, found 2026-10-18 installing the bundle into DF Engine: the installer takes a file that
    differs from the bundle's only by `--take <path>`, a command naming a hook was refused as a write
    of the stack, and so no session could ever take the bundle's copy of a hook. The installer of the
    bundle the project DECLARES runs as the stack's own tools do - and only while its checkout holds
    no change and its commit is on the bundle's GitHub main, which a push needs the owner's yes to
    reach. Each way past that is a test here, beside the control that the honest install runs."""

    def setUp(self):
        ScratchProject.setUp(self)
        self.git("init", "-q")
        os.makedirs(os.path.join(self.root, bundle_shell.FOLDER.replace("/", os.sep)))
        self.bundle = self.repo("bundle-declared-")
        origin = tempfile.mkdtemp(prefix="bundle-origin-")
        self.addCleanup(shutil.rmtree, origin, True)
        self.run_git(origin, "init", "-q", "--bare")
        self.run_git(self.bundle, "remote", "add", "origin", origin)
        self.run_git(self.bundle, "push", "-q", "-u", "origin", "main")
        self.write_file(guard_command_rules.DECLARATIONS, json.dumps({"_bundle": self.bundle}))

    def repo(self, prefix):
        """A bundle checkout in a temp folder holding one committed installer, on `main`."""
        where = tempfile.mkdtemp(prefix=prefix)
        self.addCleanup(shutil.rmtree, where, True)
        self.run_git(where, "init", "-q", "-b", "main")
        for key, value in (("user.name", "t"), ("user.email", "t@example.invalid"), ("commit.gpgsign", "false"),
                           ("core.hooksPath", os.path.join(where, "no-hooks"))):
            self.run_git(where, "config", key, value)
        self.add_to(where, guard_command_rules.INSTALLER, "print('installs')" + LF)
        self.run_git(where, "add", "-A")
        self.run_git(where, "commit", "-q", "-m", "the installer")
        return where

    def add_to(self, where, rel, text):
        path = os.path.join(where, *rel.split("/"))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with io.open(path, "a", encoding="utf-8", newline=LF) as handle:
            handle.write(text)

    def run_git(self, where, *args):
        done = bounded.run(["git"] + list(args), cwd=where, capture_output=True, text=True, timeout=60,
                           creationflags=NO_WINDOW)
        self.assertEqual(done.returncode, 0, "git %s failed: %s" % (" ".join(args), done.stderr))

    def install(self, bundle=None):
        """The command a session runs to take the bundle's copy of a hook."""
        installer = os.path.join(bundle or self.bundle, *guard_command_rules.INSTALLER.split("/"))
        return 'python -B "%s" "%s" --take %s/dispatch.py' % (
            installer.replace(chr(92), "/"), self.root.replace(chr(92), "/"), bundle_shell.FOLDER)

    def stack(self, command):
        payload = {"tool_name": "Bash", "tool_input": {"command": command}, "cwd": self.root}
        return [found for name, found in guard_command_rules.problems(payload, self.root)
                if name == "stack"]

    def test_the_declared_installer_at_a_released_commit_runs_and_no_other_does(self):
        self.assertEqual(self.stack(self.install()), [],
                         "the declared bundle's installer, clean and released, was refused as a write of the stack")
        other = self.repo("bundle-other-")
        self.assertTrue(self.stack(self.install(other)), "an installer outside the declared bundle ran as a stack tool")

    def test_a_change_in_the_bundles_checkout_is_not_the_bundle_as_released(self):
        for label, rel in (("the installer edited in place", guard_command_rules.INSTALLER),
                           ("a file nobody added beside it", "tools/json.py")):
            with self.subTest(label):
                self.add_to(self.bundle, rel, "import os" + LF)
                try:
                    self.assertTrue(self.stack(self.install()), "%s ran as the stack's own tool" % label)
                finally:
                    self.run_git(self.bundle, "checkout", "--", guard_command_rules.INSTALLER)
                    stray = os.path.join(self.bundle, "tools", "json.py")
                    if os.path.exists(stray):
                        os.remove(stray)

    def test_a_commit_the_bundles_github_does_not_hold_is_not_released(self):
        self.assertEqual(self.stack(self.install()), [], "the control: the released installer must run first")
        self.add_to(self.bundle, guard_command_rules.INSTALLER, "print('unreleased')" + LF)
        self.run_git(self.bundle, "commit", "-q", "-am", "not pushed")
        self.assertTrue(self.stack(self.install()), "an installer at a commit nobody pushed ran as the stack's own tool")


class AStackIsJudgedInTheCheckoutTheCommandRunsIn(ScratchProject, unittest.TestCase):
    """`guard_command_rules.stack_write` asks the stack of the checkout a command RUNS in.

    Measured 2026-10-08 on the project this came from, in a session working in a linked worktree
    the harness made: `python tools/dev/plants.py add <file>` - the one door a plant enters the
    ledger by - was refused as naming the guard stack. The rule asked the MAIN checkout's stack,
    found the worktree's copy of the tool in none of it, so the excuse for running a stack tool
    never applied while its name still matched. A worktree's stack is its own, and a write into
    it is refused there exactly as in the main checkout - both halves asked, in both checkouts."""

    def setUp(self):
        ScratchProject.setUp(self)
        self.write_file("tools/zz_gate_tool.py", "print('the gate ran')" + LF)
        self.write_file(".githooks/pre-commit",
                        "#!/bin/sh" + LF + "python tools/zz_gate_tool.py" + LF)
        self.git("init", "-q")
        self.git("config", "user.email", "scratch@example.com")
        self.git("config", "user.name", "scratch")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "scratch")
        self.tree = os.path.join(self.root, ".claude", "worktrees", "wt")
        self.assertEqual(self.git("worktree", "add", "-q", "-b", "wt", self.tree).returncode, 0)
        self.addCleanup(self.git, "worktree", "remove", "--force", self.tree)

    def stack_findings(self, command, cwd):
        payload = {"tool_name": "Bash", "tool_input": {"command": command}, "cwd": cwd}
        return [found for rule, findings in guard_command_rules.problems(payload, self.root)
                if rule == "stack" for found in findings]

    def test_running_the_gates_own_tool_is_not_a_write_in_either_checkout(self):
        command = "python -B tools/zz_gate_tool.py add plants.json"
        self.assertEqual(self.stack_findings(command, self.root), [],
                         "the control: the gate's own tool, run in the main checkout")
        self.assertEqual(self.stack_findings(command, self.tree), [],
                         "the gate's own tool, run in a linked worktree, was refused as a write")

    def test_a_write_into_the_hooks_is_refused_in_either_checkout(self):
        command = "echo exit 0 > .githooks/pre-commit"
        self.assertNotEqual(self.stack_findings(command, self.root), [],
                            "the control: a write into the main checkout's git hooks")
        self.assertNotEqual(self.stack_findings(command, self.tree), [],
                            "a write into a linked worktree's git hooks went through")


if __name__ == "__main__":
    unittest.main()
