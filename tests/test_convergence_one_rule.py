"""The stopping rule is ONE trend: `convergence.py check` and the agent-launch refusal agree (F198).

The rule from 2026-08-17 - thirteen flat audit rounds at ~97M tokens - was computed twice:
`tools/guard/convergence.py check` judged every scope, and the bundle's guard_agent_campaign, the
only copy asked at the act, judged only the newest. One round recorded under a new scope name let
the launch through while check still said STOP AND REPORT. Both now answer through the hook's
`not_converging`; asked here of the same logs, by both, and they must give the same verdict.
"""
import contextlib
import importlib.util
import io
import json
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _owner_switches  # noqa: E402,F401  - the owner's settings out of this process, before any hook can read one
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
HERE = _tree_root.require(__file__)


def load(name, rel):
    folder = os.path.dirname(os.path.join(HERE, rel))
    if folder not in sys.path:
        sys.path.insert(0, folder)
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, rel))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def row(scope, findings, day):
    return {"scope": scope, "n": 0, "findings": findings, "recorded": "2026-09-%02dT00:00:00Z" % day}


FLAT = [row("audit", 10, 1), row("audit", 10, 2), row("audit", 10, 3)]
LOGS = {
    "a flat scope hidden by one round under a new name": (FLAT + [row("audit-again", 3, 4)], True),
    "a falling scope": ([row("audit", 9, 1), row("audit", 6, 2), row("audit", 2, 3)], False),
    "a flat scope closed with its reason": (
        FLAT + [{"scope": "audit", "closed": "x" * 90, "recorded": "2026-09-05T00:00:00Z"}], False),
    "a row with no scope": (FLAT + [{"findings": 1}], True),
}


class TheStoppingRuleIsOneTrend(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="ctrmap-converge-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.log = os.path.join(self.tmp, "audit-convergence.json")
        self.check = load("ctrmap_convergence", "tools/guard/convergence.py")
        self.check.LOG = self.log
        self.hook = load("ctrmap_campaign_for_test", ".claude/bundle-hooks/guard_agent_campaign.py")
        self.hook.CONVERGENCE_LOG = self.log

    def verdicts(self, rows):
        with io.open(self.log, "w", encoding="utf-8") as handle:
            handle.write(json.dumps({"rounds": rows}))
        with contextlib.redirect_stdout(io.StringIO()):
            stops = self.check.check() != 0
        return stops, self.hook.trend_problem() is not None

    def test_check_and_the_launch_refusal_give_one_verdict(self):
        for name, (rows, expected) in sorted(LOGS.items()):
            with self.subTest(log=name):
                stops, refuses = self.verdicts(rows)
                self.assertEqual((stops, refuses), (expected, expected),
                                 "%s: check says %s, the launch refusal says %s - one stopping rule "
                                 "answered two ways" % (name, "STOP" if stops else "go",
                                                        "REFUSE" if refuses else "allow"))


class TheRoundLogIsAskedWhereItIsWritten(unittest.TestCase):
    """A closure with no real reason - the cheapest way past the trend - is refused at the write of
    the log, through the door every write is asked at, not found by a check nobody ran."""

    def test_a_closure_without_its_reason_is_refused_at_the_write(self):
        rules = load("ctrmap_bundle_rules_for_convergence", ".claude/bundle-hooks/bundle_rules.py")
        rel = "docs/audit-convergence.json"
        before = json.dumps({"rounds": FLAT})
        short = json.dumps({"rounds": FLAT + [{"scope": "audit", "closed": "done"}]})
        reasoned = json.dumps({"rounds": FLAT + [{"scope": "audit", "closed": "x" * 90}]})
        said = [f for name, (_w, _n, found) in rules.ask_changes(HERE, {rel: (before, short)}).items()
                if name == "convergence" for f in found]
        self.assertTrue(said and "cannot read" in said[0],
                        "a closure with a four-letter reason was written past the record rule: %r" % said)
        self.assertNotIn("convergence", rules.ask_changes(HERE, {rel: (before, reasoned)}),
                         "a closure with its reason was refused")


if __name__ == "__main__":
    unittest.main()
