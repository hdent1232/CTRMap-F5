"""A check that crashes names where it broke, and the repair of those files goes through.

OUTSTANDING 2026-09-22, "A GUARD CAN STILL BLOCK ITS OWN REPAIR", paid for twice. A half-finished
edit left `tools/guard/hooks_wired.py` raising NameError; `.claude/hooks/guard_all.py` runs it on
every tool call; every call was refused - the Read that would have shown the line, the Edit and the
`git checkout` that would have fixed it - and the refusal named no file. 2026-09-22 and again
2026-10-09: both times the owner restored the file from their own terminal.

  wiring     the wiring check raises: a read, an Edit of the file it broke in and a `git checkout`
             naming that file go through; an Edit of any other file is refused, naming the file
  guard      a guard raises in a helper beside it: a read of the helper and an Edit of it go
             through; a read of anything else is still refused - some guards exist to refuse reads
  control    the crash really happens: with nothing to repair, the same call is refused
"""
import importlib.util
import io
import os
import shutil
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
HERE = _tree_root.require(__file__)
HOOKS = os.path.join(HERE, ".claude", "hooks")
LF = chr(10)


def load_guard_all():
    sys.path.insert(0, HOOKS)
    spec = importlib.util.spec_from_file_location("guard_all_crash_under_test",
                                                  os.path.join(HOOKS, "guard_all.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _write(path, text):
    with io.open(path, "w", encoding="utf-8", newline=LF) as handle:
        handle.write(text)


def read_of(path):
    return {"hook_event_name": "PreToolUse", "session_id": "s", "tool_name": "Read",
            "tool_input": {"file_path": path}}


def edit_of(path):
    return {"hook_event_name": "PreToolUse", "session_id": "s", "tool_name": "Edit",
            "tool_input": {"file_path": path, "old_string": "a", "new_string": "b"}}


def bash(command, cwd):
    return {"hook_event_name": "PreToolUse", "session_id": "s", "tool_name": "Bash", "cwd": cwd,
            "tool_input": {"command": command}}


class AWiringCheckThatCrashesLetsItsRepairThrough(unittest.TestCase):

    def setUp(self):
        self.dispatcher = load_guard_all()
        self.scratch = tempfile.mkdtemp(prefix="ctrmap-crash-")
        self.addCleanup(shutil.rmtree, self.scratch, True)
        os.makedirs(os.path.join(self.scratch, "tools", "guard"))
        self.checker = os.path.join(self.scratch, "tools", "guard", "hooks_wired.py")
        _write(self.checker, "def findings(root):" + LF + "    return copies_above(root)" + LF)
        self.other = os.path.join(self.scratch, "other.py")
        _write(self.other, "x = 1" + LF)
        patcher = mock.patch.object(self.dispatcher, "_checker_found",
                                    lambda: (self.scratch, self.checker))
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_an_edit_of_the_file_it_broke_in_goes_through(self):
        deny, reason = self.dispatcher.wiring(edit_of(self.checker))
        self.assertFalse(deny, "the Edit that repairs the crashed check was refused: %s" % reason)

    def test_a_git_checkout_of_that_file_goes_through(self):
        deny, reason = self.dispatcher.wiring(
            bash("git checkout -- tools/guard/hooks_wired.py", self.scratch))
        self.assertFalse(deny, "the restore of the crashed check was refused: %s" % reason)

    def test_a_read_goes_through(self):
        deny, reason = self.dispatcher.wiring(read_of(self.other))
        self.assertFalse(deny, "a READ was refused while the wiring check was broken - the wedge "
                         "of 2026-09-22 and 2026-10-09: %s" % reason)

    def test_an_edit_of_anything_else_is_refused_and_the_file_is_named(self):
        deny, reason = self.dispatcher.wiring(edit_of(self.other))
        self.assertTrue(deny, "an unrelated write went through while the wiring check could "
                        "not run")
        self.assertIn(self.checker, reason or "",
                      "the refusal did not name the file the check broke in")


class AGuardThatCrashesLetsItsRepairThrough(unittest.TestCase):

    def setUp(self):
        self.dispatcher = load_guard_all()
        self.scratch = tempfile.mkdtemp(prefix="ctrmap-crash-guard-")
        self.addCleanup(shutil.rmtree, self.scratch, True)
        # THE HELPER LIVES OUTSIDE THE HOOKS FOLDER, as tools/guard/hooks_wired.py does: a write
        # into the hooks folder was already exempt, and a test with its helper there passed on
        # that older exemption without asking the new one.
        hooks, lib = os.path.join(self.scratch, "hooks"), os.path.join(self.scratch, "lib")
        os.makedirs(hooks)
        os.makedirs(lib)
        self.helper = os.path.join(lib, "crash_helper_mod.py")
        _write(self.helper, "def verdict(payload):" + LF + "    return undefined_name" + LF)
        _write(os.path.join(hooks, "guard_crashes.py"), LF.join([
            "import os, sys",
            "sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname("
            "os.path.abspath(__file__))), 'lib'))",
            "import crash_helper_mod",
            "def decide(payload):",
            "    return crash_helper_mod.verdict(payload)",
            ""]))
        self.other = os.path.join(self.scratch, "other.txt")
        _write(self.other, "x" + LF)
        patcher = mock.patch.object(self.dispatcher, "HERE", hooks)
        patcher.start()
        self.addCleanup(patcher.stop)
        env = mock.patch.dict(os.environ, {self.dispatcher.BROKEN_OK: "0"})
        env.start()
        self.addCleanup(env.stop)
        self.addCleanup(sys.modules.pop, "crash_helper_mod", None)

    def ask(self, payload):
        return self.dispatcher.ask("guard_crashes.py", payload)

    def test_an_edit_of_the_helper_it_broke_in_goes_through(self):
        deny, reason = self.ask(edit_of(self.helper))
        self.assertFalse(deny, "the Edit of the helper the guard crashed in was refused: %s"
                         % reason)

    def test_a_read_of_the_helper_goes_through(self):
        deny, reason = self.ask(read_of(self.helper))
        self.assertFalse(deny, "the Read of the helper the guard crashed in was refused - an Edit "
                         "needs a Read first: %s" % reason)

    def test_control_a_read_of_anything_else_is_still_refused(self):
        deny, _reason = self.ask(read_of(self.other))
        self.assertTrue(deny, "CONTROL: a crashed guard let an unrelated read through - a guard "
                        "that refuses reads would be off whenever it crashes")


if __name__ == "__main__":
    unittest.main()
