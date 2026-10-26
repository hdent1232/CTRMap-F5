"""`tools/mutate2.py --selftest` runs where no sweep ever has: no worktree beside the repository.

The defect: its usage says "seconds, no worktree, no JDK, no dump", and its output checks handed the
child `cwd=WT` - the sweep's worktree - so on a machine that had never run a sweep it died with
FileNotFoundError before its verdict. test.ps1 runs it in every battery; the owner's machine has a
worktree, which is the one place the failure could not show. Run here from a COPY of tools/ in a
scratch folder, where nothing sits beside it - and its lock check asks for the lock beside the
mutant, inside the folder it mutates, never in the repository's parent.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _owner_switches  # noqa: E402,F401  - the owner's settings out of this process, before any hook can read one
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
import bounded         # noqa: E402  - a deadline ends the child's whole tree
HERE = _tree_root.require(__file__)
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
        # A CHECKOUT HAS ITS RULES FILE. The harness finds its repository by that anchor (F216); a
        # copy of tools/ with none above it sits in no project, and is refused as one.
        shutil.copyfile(os.path.join(HERE, "CLAUDE.md"), os.path.join(repo, "CLAUDE.md"))
        self.assertFalse(os.path.exists(os.path.join(base, "wt")), "control: no worktree beside it")
        done = bounded.run([sys.executable, "-B", os.path.join(repo, "tools", "mutate2.py"), "--selftest"],
                           cwd=repo, capture_output=True, text=True, timeout=600, creationflags=NO_WINDOW)
        said = done.stdout + done.stderr
        self.assertEqual(done.returncode, 0, "the selftest failed with no worktree beside it:\n%s"
                         % said[-1500:])
        self.assertIn("ALL PASS", said)
        self.assertFalse(os.path.exists(os.path.join(base, ".mutation-in-flight")),
                         "the selftest left a lock in the repository's parent, where no reader looks")

    def test_a_ceiling_above_the_exclusion_list_refuses_to_run(self):
        """F189: the exclusion ceiling sat at 4 over a list of 2 - room for two more lines to stop
        being measured with nothing said. The harness refuses slack before it does anything, driven
        here on a copy whose ceiling is raised by one."""
        base = tempfile.mkdtemp(prefix="ctrmap-exclusion-slack-")
        self.addCleanup(shutil.rmtree, base, True)
        repo = os.path.join(base, "repo")
        for folder in ("tools", ".claude"):
            shutil.copytree(os.path.join(HERE, folder), os.path.join(repo, folder),
                            ignore=shutil.ignore_patterns("__pycache__"))
        shutil.copyfile(os.path.join(HERE, "CLAUDE.md"), os.path.join(repo, "CLAUDE.md"))
        path = os.path.join(repo, "tools", "mutate2.py")
        with open(path, encoding="utf-8", newline="") as handle:
            text = handle.read()
        found = re.search(r"(?m)^EXCLUSION_CEILING = (\d+)\s*$", text)
        self.assertIsNotNone(found, "control: mutate2.py declares its exclusion ceiling")
        with open(path, "w", encoding="utf-8", newline="") as handle:
            handle.write(text[:found.start(1)] + str(int(found.group(1)) + 1) + text[found.end(1):])
        done = bounded.run([sys.executable, "-B", path, "--selftest"], cwd=repo, capture_output=True,
                           text=True, timeout=600, creationflags=NO_WINDOW)
        said = done.stdout + done.stderr
        self.assertIn("lower the ceiling to the list", said,
                      "a ceiling above the exclusion list is refused in words:\n%s" % said[-800:])
        self.assertNotEqual(done.returncode, 0, "and the run stops there")


if __name__ == "__main__":
    unittest.main()
