"""A fresh clone turns its own git hooks on: tools/arm_checkout.py, judged from outside.

The defect: `core.hooksPath` lives in `.git/config`, which a clone never carries, so a fresh clone
ran no commit hook at all and every commit-time guard was silently off. One test per plant site:

  instance   the fix undone: a clone with hooks present and no hooks path must come out armed -
             read back from git itself, never from what the tool says it did.
  elsewhere  the same defect in the WIRING rather than the code: the repository's own settings
             must run the tool at SessionStart, or it is a file nobody calls.
  evasion    the cheapest way to report "armed" is to trust whatever hooks path is already set
             without looking at what it names - so a checkout pointing elsewhere must be repointed.
"""
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(HERE, "tools"))
import arm_checkout  # noqa: E402  - the tool under test

LF = chr(10)
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def _git(root, *args):
    done = subprocess.run(["git", "-C", root] + list(args), capture_output=True, text=True,
                          timeout=60, creationflags=NO_WINDOW)
    return done.returncode, (done.stdout or "").strip()


class AFreshCloneArmsItsOwnHooks(unittest.TestCase):
    """A checkout's git hooks are on after session start, whatever state it arrived in."""

    def _checkout(self, hooks_path=None):
        base = tempfile.mkdtemp(prefix="arm-checkout-test-")
        self.addCleanup(shutil.rmtree, base, True)
        root = os.path.join(base, "clone")
        os.makedirs(os.path.join(root, arm_checkout.HOOKS_DIR))
        subprocess.run(["git", "init", "-q", root], capture_output=True, timeout=60,
                       creationflags=NO_WINDOW)
        with io.open(os.path.join(root, arm_checkout.HOOKS_DIR, "commit-msg"), "w",
                     encoding="utf-8", newline=LF) as handle:
            handle.write("#!/bin/sh" + LF + "exit 0" + LF)
        if hooks_path is not None:
            _git(root, "config", "--local", "core.hooksPath", hooks_path)
        return root

    def test_a_fresh_clone_is_armed(self):
        root = self._checkout()
        arm_checkout.arm_hooks(root)
        code, now = _git(root, "config", "--local", "--get", "core.hooksPath")
        self.assertEqual((code, now), (0, arm_checkout.HOOKS_DIR),
                         "a fresh clone was left UNARMED - git reads core.hooksPath as %r, so no "
                         "commit hook runs" % (now if code == 0 else None,))

    def test_the_repository_runs_this_at_session_start(self):
        with io.open(os.path.join(HERE, ".claude", "settings.json"), encoding="utf-8") as handle:
            settings = json.load(handle)
        commands = [hook.get("command", "")
                    for matcher in settings.get("hooks", {}).get("SessionStart", [])
                    for hook in matcher.get("hooks", [])]
        wired = [c for c in commands if "tools/arm_checkout.py" in c]
        self.assertTrue(wired and os.path.isfile(os.path.join(HERE, "tools", "arm_checkout.py")),
                        "the repository's settings do not run tools/arm_checkout.py at session "
                        "start - SessionStart runs %r" % (commands,))

    def test_a_checkout_pointing_its_hooks_elsewhere_is_repointed(self):
        root = self._checkout(hooks_path=".git/hooks")
        arm_checkout.arm_hooks(root)
        code, now = _git(root, "config", "--local", "--get", "core.hooksPath")
        self.assertEqual(now, arm_checkout.HOOKS_DIR,
                         "an existing hooks path was TRUSTED instead of checked - it still names "
                         "%r, where this project keeps no hook" % now)


if __name__ == "__main__":
    unittest.main()
