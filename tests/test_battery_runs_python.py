"""test.ps1 runs THIS folder, and a red run turns the battery red: tools/guard/record_run.py counts it.

The defect (F184): tests/ held 347 tests of the hooks, the commit gate and the tools that guard the
battery, and nothing ran them - test.ps1 ran two Python tools, the pre-commit five. Nineteen had
gone red, two of them real defects, and the only way anybody saw it was an audit running the folder
by hand. A suite nobody runs is a red suite nobody sees. PowerShell is not on every machine that
runs this folder, so the battery's wiring is read from test.ps1 itself:

  instance   the run's exit code reaches the battery's list of failed suites
  elsewhere  a machine with no python is a failure of this suite, never a pass
  evasion    the suite is announced the way record_run counts suites, by the name test.ps1 gives it
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
sys.path.insert(0, os.path.join(HERE, "tools", "guard"))
import record_run  # noqa: E402  - the runner whose record a commit's test count is checked against


def block():
    """test.ps1's Python-suite block: from `$pname =` to the battery's stopwatch stopping, the line after it.
    It ended at the line restoring the error action until Invoke-Native (2026-10-10) took that juggling
    out of every call site - and this said "no Python-suite block", loudly rather than passing on nothing."""
    text = io.open(os.path.join(HERE, "test.ps1"), encoding="utf-8").read()
    start = text.find("$pname = ")
    end = text.find("$sw.Stop()", start)
    return text, (text[start:end] if start >= 0 and end > start else "")


class TheBatteryRunsThePythonSuite(unittest.TestCase):

    def test_the_battery_runs_this_folder_and_a_red_run_fails_it(self):
        _, body = block()
        self.assertTrue(body, "test.ps1 has no Python-suite block - nothing runs tests/")
        self.assertRegex(body, r"-m unittest discover -s \(Join-Path \$root \"tests\"\)",
                         "the block does not run this folder")
        self.assertRegex(body, r"\$pyExit = \$LASTEXITCODE[\s\S]*if \(\$pyExit -ne 0\) \{[^}]*\$failed \+= \$pname \}",
                         "a red Python suite does not reach the battery's failed list")

    def test_no_python_is_a_failure_not_a_pass(self):
        _, body = block()
        missing = body[body.find("if (-not $py)"):body.find("} else {")]
        self.assertIn("$failed += $pname", missing,
                      "a machine with no python records the Python suite as passed: %r" % missing)

    def test_record_run_counts_it_by_the_name_test_ps1_gives_it(self):
        _, body = block()
        name = re.search(r'\$pname = "([^"]+)"', body)
        self.assertTrue(name, "the suite has no name record_run could read")
        self.assertIn('Write-Host ("--- " + $pname)', body, "the suite is never announced")
        said = "--- %s\n    FAILED (failures=1)\nFAILED: %s\n" % (name.group(1), name.group(1))
        announced = [n.strip() for n in record_run._SUITE.findall(said)]
        self.assertEqual(record_run.failed_in(said, announced), [name.group(1)],
                         "record_run does not count the Python suite's failure by its name")


if __name__ == "__main__":
    unittest.main()
