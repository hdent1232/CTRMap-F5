"""test.ps1 refuses to run when it cannot ask the work order, unless told -Anyway with a reason (census M.84).

The defect: the battery's MEASURE LAST gate ran `work_order.py --gate` only when python was on PATH, and on a
machine without it printed one line - "(no python on PATH - the work order cannot be asked)" - and ran on. An
unstamped build and a missing corpus are refused with exit 2 a few lines further down; this, the rule the file
states at the top, was a note that scrolled away above nine hundred lines of suite output. PowerShell is not on
every machine that runs this folder, so the gate's wiring is read from test.ps1 itself:

  instance   with no python and no -Anyway, the battery exits before measuring anything
  elsewhere  with -Anyway it runs, and the reason is printed into the run
  evasion    the gate block is found by what it runs, so a renamed variable cannot hide it
"""
import io
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _owner_switches  # noqa: E402,F401  - the owner's settings out of this process, before any hook can read one
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
HERE = _tree_root.require(__file__)


def gate():
    """test.ps1's work-order gate, its comments taken out: from the python lookup before work_order.py's --gate to
    the JDK lookup after it. WITHOUT COMMENTS: the refusing branch's own comment said "refused with exit 2", and a
    branch that only printed and ran on still matched an exit in its words."""
    with io.open(os.path.join(HERE, "test.ps1"), encoding="utf-8") as handle:
        text = handle.read()
    asks = text.find('"tools\\guard\\work_order.py"), "--gate"')
    start = text.rfind("Get-Command python", 0, asks) if asks >= 0 else -1
    end = text.find("$jdk", asks) if asks >= 0 else -1
    body = text[start:end] if start >= 0 and end > start else ""
    return "\n".join(line for line in body.split("\n") if not line.lstrip().startswith("#"))


class TheBatteryAsksTheWorkOrder(unittest.TestCase):

    def test_the_gate_is_found(self):
        self.assertIn("work_order.py", gate(), "test.ps1 has no work-order gate block that runs work_order.py --gate")

    def test_no_python_and_no_anyway_is_refused(self):
        body = gate()
        branches = re.split(r"\}\s*else", body)
        last = branches[-1] if len(branches) > 1 else ""
        self.assertRegex(last, r"exit [12]\b", "with no python and no -Anyway the battery runs on: %r" % last)
        self.assertNotRegex(last, r"\$Anyway", "the refusing branch is not the last one: %r" % last)

    def test_anyway_runs_and_says_why(self):
        body = gate()
        anyway = re.search(r"elseif \(\$Anyway\) \{([^}]*)\}", body)
        self.assertTrue(anyway, "there is no -Anyway branch for a machine without python: %r" % body)
        self.assertIn("$Anyway", anyway.group(1), "the -Anyway branch does not print its reason into the run")
        self.assertNotRegex(anyway.group(1), r"exit\b", "the -Anyway branch refuses anyway")


if __name__ == "__main__":
    unittest.main()
