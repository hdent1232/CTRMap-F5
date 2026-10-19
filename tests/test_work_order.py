# -*- coding: utf-8 -*-
"""`tools/guard/work_order.py` answers UNKNOWN, never "nothing", when it cannot look (F192).

The gate in front of every long measurement - the mechanism rule_map names for COMMIT BEFORE RUNNING
ANYTHING THAT MUTATES SOURCES and FINISH CHANGES BEFORE STARTING A LONG MEASUREMENT - read a queue it
could not open as an empty one and a git that could not answer as a clean tree, so a measurement could
start with neither looked at. Each half is driven against scratch subjects, with a control that reads.
"""
import io
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
HERE = _tree_root.require(__file__)
sys.path.insert(0, os.path.join(HERE, "tools", "guard"))
import work_order  # noqa: E402

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
LF = chr(10)


class AQueueOrATreeItCannotReadIsNotClear(unittest.TestCase):

    def scratch(self):
        base = tempfile.mkdtemp(prefix="ctrmap-workorder-")
        self.addCleanup(shutil.rmtree, base, True)
        return base

    def test_an_unreadable_queue_is_unknown_not_empty(self):
        base = self.scratch()
        queue = os.path.join(base, "OUTSTANDING.md")
        with io.open(queue, "w", encoding="utf-8") as handle:
            handle.write("# q" + LF + "## Open" + LF + "- [ ] one" + LF + "## Done" + LF)
        with mock.patch.object(work_order, "LEDGER", queue):
            self.assertEqual(work_order.open_items(), ["one"], "control: a readable queue is read")
        with mock.patch.object(work_order, "LEDGER", os.path.join(base, "nowhere", "OUTSTANDING.md")):
            self.assertIsNone(work_order.open_items(), "an unreadable queue was read as an empty one")
        self.assertIsNone(work_order.open_items("# a queue with no Open section" + LF),
                          "a queue with no Open section was read as an empty one")

    def test_a_git_that_cannot_answer_is_not_a_clean_tree(self):
        base = self.scratch()
        repo = os.path.join(base, "repo")
        os.makedirs(repo)
        run = dict(cwd=repo, capture_output=True, timeout=60, creationflags=NO_WINDOW)
        subprocess.run(["git", "init", "-q"], **run)
        with io.open(os.path.join(repo, "a.txt"), "w", encoding="utf-8") as handle:
            handle.write("a" + LF)
        subprocess.run(["git", "add", "a.txt"], **run)
        subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "a"], **run)
        with io.open(os.path.join(repo, "a.txt"), "w", encoding="utf-8") as handle:
            handle.write("b" + LF)
        with mock.patch.object(work_order, "ROOT", repo):
            self.assertEqual(work_order.dirty_files(), ["a.txt"], "control: a modified tracked file is seen")
        notrepo = os.path.join(base, "not-a-repository")
        os.makedirs(notrepo)
        with mock.patch.object(work_order, "ROOT", notrepo):
            self.assertIsNone(work_order.dirty_files(), "a git that could not answer was read as a clean tree")

        def no_git(*args, **kwargs):
            raise OSError("git is not on PATH")
        #THE CALL work_order MAKES: it asks git through bounded.run, and a fake of subprocess.run caught
        #nothing - the real git answered and this went red the day the deadline moved (2026-10-10)
        with mock.patch.object(work_order.bounded, "run", no_git):
            self.assertIsNone(work_order.dirty_files(), "a git that could not be started was read as a clean tree")

    def test_the_gate_refuses_what_it_could_not_look_at(self):
        with mock.patch.object(work_order, "open_items", lambda text=None: None):
            with mock.patch("sys.stdout", new_callable=io.StringIO) as out:
                code = work_order.gate("the battery", "")
            self.assertEqual(code, 1, "the gate let a measurement start with the queue unread")
            self.assertIn("UNKNOWN", out.getvalue())
            with mock.patch.object(work_order, "dirty_files", lambda: None), \
                    mock.patch.object(work_order, "unproven_plants", lambda: []):
                why = work_order.blockers()
        self.assertTrue(any("UNKNOWN" in w and "queued" in w for w in why)
                        and any("UNKNOWN" in w and "clean" in w for w in why),
                        "blockers did not name both things it could not look at: %r" % why)


class ThePlantProofIsAboutThisTree(unittest.TestCase):
    """unproven_plants answers with a reason for every way the recorded proof is not about this tree,
    and with nothing only when it is (F145). CommitGuardTest drove it through python and accepted any
    output - a traceback included - so none of its branches was ever asserted. Driven here against a
    scratch root: the real require_build digests its src/, and a two-function stand-in for replant
    answers the ledger and the target digest."""

    def root(self, record=None, plants=3):
        base = tempfile.mkdtemp(prefix="ctrmap-plantproof-")
        self.addCleanup(shutil.rmtree, base, True)
        os.makedirs(os.path.join(base, "src"))
        with io.open(os.path.join(base, "src", "a.java"), "w", encoding="utf-8") as h:
            h.write("class A {}" + LF)
        os.makedirs(os.path.join(base, "tools", "guard"))
        shutil.copy(os.path.join(HERE, "tools", "require_build.py"), os.path.join(base, "tools"))
        with io.open(os.path.join(base, "tools", "guard", "replant.py"), "w", encoding="utf-8") as h:
            h.write("def ledger():" + LF + "    return {'plants': list(range(%d))}" % plants + LF
                    + "def target_digest(book):" + LF + "    return 'TARGETS'" + LF)
        if record is not None:
            import json
            with io.open(os.path.join(base, ".last-replant"), "w", encoding="utf-8") as h:
                json.dump(record, h)
        return base

    def answer(self, base):
        with mock.patch.object(work_order, "ROOT", base), mock.patch.object(sys, "path", list(sys.path)), \
                mock.patch.dict(sys.modules):
            sys.modules.pop("require_build", None)
            sys.modules.pop("replant", None)
            return work_order.unproven_plants()

    def digest(self, base):
        with mock.patch.object(sys, "path", [os.path.join(base, "tools")] + list(sys.path)), mock.patch.dict(sys.modules):
            sys.modules.pop("require_build", None)
            import require_build
            return require_build.tree_digest(os.path.join(base, "src"))

    def good(self, base):
        return {"notProven": 0, "targets": "TARGETS", "plants": 3, "src": self.digest(base)}

    def test_a_proof_about_this_tree_is_accepted(self):
        base = self.root()
        with io.open(os.path.join(base, ".last-replant"), "w", encoding="utf-8") as h:
            import json
            json.dump(self.good(base), h)
        self.assertEqual(self.answer(base), [], "a record that matches this tree was refused")

    def test_each_way_the_proof_is_not_about_this_tree_is_named(self):
        cases = [
            (None, "never been proven"),
            ({"notProven": 1}, "did NOT prove"),
            ({"notProven": 0, "src": "x"}, "predates the target digest"),
            ({"notProven": 0, "targets": "OTHER", "plants": 3, "src": "x"}, "a file the plants target has changed"),
            ({"notProven": 0, "targets": "TARGETS", "plants": 2, "src": "x"}, "the ledger holds 3 plant(s) and the proof covered 2"),
        ]
        for record, says in cases:
            why = self.answer(self.root(record))
            self.assertTrue(why and any(says in w for w in why), "record %r: wanted %r, got %r" % (record, says, why))
        base = self.root()
        stale = self.good(base)
        stale["src"] = "0" * 64
        with io.open(os.path.join(base, ".last-replant"), "w", encoding="utf-8") as h:
            import json
            json.dump(stale, h)
        why = self.answer(base)
        self.assertTrue(why and "src/ has changed" in why[0], "a stale src digest was not named: %r" % why)


class ASweepIsRefusedWhileTheCommitItServesIs(unittest.TestCase):
    """2026-10-18: days of sweeps for 1.0.4 ran while its release commit could never land - a check the commit runs
    failed, and owed plants were in no ledger any more - and every one of them was started with --anyway. So the
    sweep's gate asks the commit's own checks and the owed record first, and --anyway does not reach that list."""

    def scratch(self):
        base = tempfile.mkdtemp(prefix="ctrmap-releaseblock-")
        self.addCleanup(shutil.rmtree, base, True)
        return base

    def write(self, base, rel, text):
        path = os.path.join(base, *rel.split("/"))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with io.open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        return path

    def test_the_hooks_own_checks_are_read_and_an_unread_hook_is_unknown(self):
        base = self.scratch()
        hook = self.write(base, ".githooks/pre-commit", "#!/bin/sh" + LF + "# \"$PY\" -B tools/commented.py" + LF
                          + "\"$PY\" -B tools/first.py || exit 1" + LF + "exec \"$PY\" -B tools/last.py --owed" + LF)
        self.assertEqual(work_order.precommit_checks(hook), [["tools/first.py"], ["tools/last.py", "--owed"]],
                         "the hook's own commands were not read as it spells them")
        self.assertIsNone(work_order.precommit_checks(os.path.join(base, "no-such-hook")),
                          "a hook that cannot be read was read as one that asks nothing")
        with mock.patch.object(work_order, "precommit_checks", lambda hook=None: None):
            self.assertTrue(any("UNKNOWN" in w for w in work_order.failing_checks()),
                            "a hook that cannot be read was read as no check failing")
        self.assertEqual(work_order.failing_checks(checks=[]), [], "control: no checks, nothing failing")

    def test_an_owed_plant_no_tree_can_drive_is_named(self):
        import json
        base = self.scratch()
        self.write(base, "src/here.java", "class Here {}" + LF)
        self.write(base, "tools/guard/plants.json", json.dumps({"plants": [
            {"id": "p-ok", "file": "src/here.java"}, {"id": "p-file", "file": "src/gone.java"}]}))
        self.write(base, "tests/plants.json", json.dumps({"plants": {"k-ok": {"file": "src/here.java"}}}))
        self.write(base, "tools/guard/gate_owed.json", json.dumps({
            "plants": [{"id": "p-ok"}, {"id": "p-file"}, {"id": "p-gone"}], "bundle_plants": ["k-ok", "k-gone"]}))
        said = LF.join(work_order.undrivable_owed(base))
        for named in ("p-gone - in no plant", "p-file - its file src/gone.java is gone", "k-gone - in no plant"):
            self.assertIn(named, said, "an owed plant no tree here can drive was not named: %s" % said)
        self.assertNotIn("p-ok", said, "a plant that can be driven was named as one that cannot")
        self.assertNotIn("k-ok", said, "a bundle plant that can be driven was named as one that cannot")
        self.write(base, "tools/guard/gate_owed.json", json.dumps({"plants": [{"id": "p-ok"}], "bundle_plants": ["k-ok"]}))
        said = LF.join(work_order.undrivable_owed(base))
        self.assertIn("2 plant(s) are owed", said,
                      "owed plants not yet driven did not refuse the sweep - a plant that will not redden is found"
                      " only by driving it: %s" % said)
        self.assertNotIn("can be driven by no tree", said, "every owed plant here is drivable, yet one was named")
        self.write(base, "tools/guard/gate_owed.json", json.dumps({"plants": [], "bundle_plants": []}))
        self.assertEqual(work_order.undrivable_owed(base), [], "control: nothing owed, nothing named")

    def test_anyway_does_not_reach_the_release_blockers_and_the_owner_does(self):
        def ask(release, owner, queued, argv):
            with mock.patch.object(work_order, "release_blockers", lambda: release), \
                    mock.patch.object(work_order, "_owner_allows_release_blocked", lambda: owner), \
                    mock.patch.object(work_order, "blockers", lambda: queued), \
                    mock.patch("sys.stderr", new_callable=io.StringIO) as err, \
                    mock.patch("sys.stdout", new_callable=io.StringIO):
                try:
                    return work_order.require_frozen("the mutation sweep", argv), err.getvalue()
                except SystemExit as stop:
                    return ("refused", stop.code), err.getvalue()
        anyway = ["--anyway", "because the queue is the owner's"]
        answer, said = ask(["`tools/bundle_install.py` refuses the commit: 18 problem(s)"], False, [], anyway)
        self.assertEqual(answer, ("refused", 1), "--anyway reached the release blockers: the sweep started")
        self.assertIn("bundle_install", said, "the refusal did not name what refuses the commit: %s" % said)
        answer, _said = ask(["`tools/bundle_install.py` refuses the commit"], True, [], [])
        self.assertIsNone(answer, "the owner's own override did not pass the release blockers")
        answer, _said = ask([], False, ["OUTSTANDING.md has 1 open item(s):"], anyway)
        self.assertEqual(answer, "because the queue is the owner's",
                         "control: with no release blocker, --anyway still passes the queue as before")


if __name__ == "__main__":
    unittest.main()
