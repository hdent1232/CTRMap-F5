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

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(HERE, "tools"))
import bundle_hooks          # noqa: E402  - where this project keeps the bundle's hooks
HOOKS = bundle_hooks.path(HERE)
sys.path.insert(0, HOOKS)

import after_rules            # noqa: E402
import bundle_rules           # noqa: E402
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
        return subprocess.run(["git"] + list(args), cwd=self.root, capture_output=True,
                              text=True, timeout=60, creationflags=NO_WINDOW)


# ----------------------------------------------------------------------------- the write

class AWriteIsJudgedAsItWouldLeaveTheTree(ScratchProject, unittest.TestCase):
    """`bundle_rules.ask_write`, and `guard_write_rules` over it."""

    def ask(self, rel, before, after):
        return bundle_rules.ask_write(self.root, rel, before, after)

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

    def test_an_ABBREVIATED_no_verify_is_still_no_verify(self):
        """git accepts any unambiguous prefix of a long option. `--no-ver` is ambiguous with
        `--no-verbose` and git refuses it itself."""
        self.assertTrue(self.refused('git commit --no-veri -F "%s"' % self.good),
                        "an abbreviated --no-verify walked past the gate")
        self.assertFalse([n for n, _f in self.refused('git commit --no-ver -F "%s"' % self.good)
                          if n == "gate"])

    def test_repointing_the_hooks_is_refused_and_reading_them_is_not(self):
        self.assertTrue(self.refused("git config core.hooksPath elsewhere"))
        self.assertTrue(self.refused("git config --unset core.hooksPath"))
        self.assertFalse(self.refused("git config --get core.hooksPath"),
                         "reading where the hooks are was refused")

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
        subprocess.run(["git", "init", "-q", self.other], capture_output=True, timeout=60,
                       creationflags=NO_WINDOW)

    def refused(self, command, cwd=None):
        payload = {"tool_name": "Bash", "tool_input": {"command": command}}
        if cwd:
            payload["cwd"] = cwd
        return guard_command_rules.problems(payload, self.root)

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
        subprocess.run(["git", "init", "-q"], cwd=folder, capture_output=True, timeout=60,
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
        done = subprocess.run([sys.executable, "-B", os.path.join(self.hooks, name)],
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
        subprocess.run(["git", "init", "-q"], cwd=folder, capture_output=True, timeout=60,
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


if __name__ == "__main__":
    unittest.main()
