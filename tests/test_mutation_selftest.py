"""`tools/mutate2.py --selftest` runs where no sweep ever has: no worktree beside the repository.

The defect: its usage says "seconds, no worktree, no JDK, no dump", and its output checks handed the
child `cwd=WT` - the sweep's worktree - so on a machine that had never run a sweep it died with
FileNotFoundError before its verdict. test.ps1 runs it in every battery; the owner's machine has a
worktree, which is the one place the failure could not show. Run here from a COPY of tools/ in a
scratch folder, where nothing sits beside it - and its lock check asks for the lock beside the
mutant, inside the folder it mutates, never in the repository's parent.
"""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


class TheHarnessSelftestNeedsNoWorktree(unittest.TestCase):

    def test_the_selftest_passes_with_no_worktree_beside_it(self):
        base = tempfile.mkdtemp(prefix="ctrmap-noworktree-")
        self.addCleanup(shutil.rmtree, base, True)
        repo = os.path.join(base, "repo")
        shutil.copytree(os.path.join(HERE, "tools"), os.path.join(repo, "tools"),
                        ignore=shutil.ignore_patterns("__pycache__"))
        shutil.copytree(os.path.join(HERE, ".claude"), os.path.join(repo, ".claude"),
                        ignore=shutil.ignore_patterns("__pycache__"))
        self.assertFalse(os.path.exists(os.path.join(base, "wt")), "control: no worktree beside it")
        done = subprocess.run([sys.executable, "-B", os.path.join(repo, "tools", "mutate2.py"), "--selftest"],
                              cwd=repo, capture_output=True, text=True, timeout=600, creationflags=NO_WINDOW)
        said = done.stdout + done.stderr
        self.assertEqual(done.returncode, 0, "the selftest failed with no worktree beside it:\n%s"
                         % said[-1500:])
        self.assertIn("ALL PASS", said)
        self.assertFalse(os.path.exists(os.path.join(base, ".mutation-in-flight")),
                         "the selftest left a lock in the repository's parent, where no reader looks")


if __name__ == "__main__":
    unittest.main()
