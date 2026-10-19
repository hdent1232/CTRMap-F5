"""The run record's subject and the commit gate's are ONE digest: tools/require_build.subject_digest.

The defect (F183): `tools/commit_gate.py` asks `require_build.subject_digest` which tree a "N tests"
claim was measured against, and this tree's require_build had no such function - every commit
message stating a count crashed. And the record the gate reads is written by
`tools/guard/record_run.py` with `commit_guard.source_digest`, a second body of the same digest
that read the rule file above the repository differently, so even with the function present an
honest count would have been refused as "measured DIFFERENT code". One test per site:

  instance   a record carrying record_run's subject, claiming the count it ran, is ACCEPTED by
             the gate - with a CLAUDE.md above the root, where the two bodies disagreed.
  control    the same record claiming another count is refused, so the rule is really asked.
"""
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
import bounded         # noqa: E402  - a deadline ends the child's whole tree
HERE = _tree_root.require(__file__)
sys.path.insert(0, os.path.join(HERE, "tools"))
sys.path.insert(0, os.path.join(HERE, "tools", "guard"))
import commit_gate  # noqa: E402  - the gate whose count rule is asked
import commit_guard  # noqa: E402  - whose source_digest record_run writes into the record

LF = chr(10)
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


class TheRecordAndTheGateMeasureOneSubject(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="ctrmap-subject-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.repo = os.path.join(self.tmp, "repo")
        os.makedirs(os.path.join(self.repo, "src"))
        bounded.run(["git", "init", "-q"], cwd=self.repo, capture_output=True, timeout=120,
                    creationflags=NO_WINDOW)
        io.open(os.path.join(self.repo, "src", "A.java"), "w", encoding="utf-8").write("class A {}" + LF)
        # THE RULE FILE ABOVE THE ROOT, where the two bodies read it differently
        io.open(os.path.join(self.tmp, "CLAUDE.md"), "w", encoding="utf-8").write("a rule" + LF)
        subject = commit_guard.source_digest(self.repo)
        self.assertIsNotNone(subject, "record_run's digest could not read a real repository")
        io.open(os.path.join(self.repo, commit_gate.RECORD), "w", encoding="utf-8").write(
            json.dumps({"ran": 7, "rc": 0, "subject": subject}))
        self.addCleanup(setattr, commit_gate, "ROOT", commit_gate.ROOT)
        commit_gate.ROOT = self.repo

    def test_a_count_measured_by_record_run_is_accepted_by_the_gate(self):
        self.assertEqual(commit_gate.test_counts("fix: x, 7 tests" + LF), [],
                         "an honest count, recorded by record_run against this very tree, was refused")

    def test_a_count_the_run_did_not_measure_is_still_refused(self):
        self.assertTrue(commit_gate.test_counts("fix: x, 8 tests" + LF),
                        "a count the run never said was accepted - the rule was not asked")

    def test_the_right_count_measured_on_ANOTHER_tree_is_refused(self):
        """The cheapest way to accept every honest count is to stop asking which tree it was."""
        io.open(os.path.join(self.repo, "src", "A.java"), "w", encoding="utf-8").write(
            "class A { int moved; }" + LF)
        said = commit_gate.test_counts("fix: x, 7 tests" + LF)
        self.assertTrue(any("DIFFERENT" in line for line in said),
                        "a count recorded against an earlier tree was accepted for this one: %r" % said)


if __name__ == "__main__":
    unittest.main()
