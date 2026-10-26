# -*- coding: utf-8 -*-
"""`.claude/hooks/guard_all.py` refuses a guard that never answers, inside its own deadline (F169).

It listed "a guard that takes longer than PATIENCE seconds" among what it refuses, and measured the
time only AFTER decide() returned - so a guard that never returned was never refused. It held the call
until the harness's 20 s timeout, and the harness IGNORES a hook it times out: the call went ahead.
"""
import importlib.util
import io
import os
import shutil
import sys
import tempfile
import time
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _owner_switches  # noqa: E402,F401  - the owner's settings out of this process, before any hook can read one
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
HERE = _tree_root.require(__file__)
HOOKS = os.path.join(HERE, ".claude", "hooks")
LF = chr(10)


def load_guard_all():
    sys.path.insert(0, HOOKS)
    spec = importlib.util.spec_from_file_location("guard_all_under_test", os.path.join(HOOKS, "guard_all.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class AGuardThatNeverAnswersIsRefusedInTime(unittest.TestCase):

    def test_a_hung_guard_is_refused_within_the_patience(self):
        dispatcher = load_guard_all()
        scratch = tempfile.mkdtemp(prefix="ctrmap-guardall-")
        self.addCleanup(shutil.rmtree, scratch, True)
        for name, body in (("guard_quick.py", "def decide(payload):" + LF + "    return False, None" + LF),
                           ("guard_hung.py", "import time" + LF + LF + LF + "def decide(payload):" + LF
                            + "    time.sleep(30)" + LF + "    return False, None" + LF),
                           ("guard_hungload.py", "import time" + LF + "time.sleep(30)" + LF + LF + LF
                            + "def decide(payload):" + LF + "    return False, None" + LF)):
            with io.open(os.path.join(scratch, name), "w", encoding="utf-8") as handle:
                handle.write(body)
        payload = {"hook_event_name": "PreToolUse", "session_id": "s", "tool_name": "Read",
                   "tool_input": {"file_path": os.path.join(scratch, "x.txt")}}
        with mock.patch.object(dispatcher, "HERE", scratch), mock.patch.object(dispatcher, "PATIENCE", 0.5), \
                mock.patch.dict(os.environ, {dispatcher.BROKEN_OK: "0"}):
            self.assertEqual(dispatcher.ask("guard_quick.py", payload), (False, None),
                             "control: a guard that answers is heard")
            started = time.time()
            deny, reason = dispatcher.ask("guard_hung.py", payload)
            took = time.time() - started
        self.assertLess(took, 5, "the dispatcher waited %.1fs for a guard that never answers" % took)
        self.assertTrue(deny, "a guard that never answered cleared the call")
        self.assertIn("did not answer within", reason or "")
        #...AND THE CHEAPEST WAY PAST IT: hanging while being imported rather than while deciding
        with mock.patch.object(dispatcher, "HERE", scratch), mock.patch.object(dispatcher, "PATIENCE", 0.5), \
                mock.patch.dict(os.environ, {dispatcher.BROKEN_OK: "0"}):
            started = time.time()
            deny, reason = dispatcher.ask("guard_hungload.py", payload)
            took = time.time() - started
        self.assertLess(took, 5, "the dispatcher waited %.1fs for a guard that never finished loading" % took)
        self.assertTrue(deny, "a guard that never finished loading cleared the call")


if __name__ == "__main__":
    unittest.main()
