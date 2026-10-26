"""A FAILED SUITE SHOWS WHAT IT SAID - said.ps1's Show-Failed, run by PowerShell; and test.ps1 hands it every failure.

PAID FOR 2026-10-15: the battery showed the last two lines of every suite, failed or not. ContainerBytes failed once
in a release battery with "... 6 more" and the JDK's "Exception while removing reference." as all that was left of
it, then passed 8 runs of 8 alone - and the lines that said why were gone. The Python suite's two failures the same
day had to be run again, five minutes, to be read at all.

  instance   a FAIL line and an exception far above the last 30 lines are shown
  elsewhere  a short output is shown once, not its failure line twice
  evasion    test.ps1 hands each captured output - a Java suite's, the mutation selftest's, the Python suite's - to
             Show-Failed when it fails; a function nobody calls shows nothing
"""
import io
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
import bounded  # noqa: E402  - a deadline ends the child's whole tree

HERE = _tree_root.require(__file__)
POWERSHELL = shutil.which("powershell") or shutil.which("pwsh")
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def shown(lines):
    """The lines Show-Failed writes for `lines`, stripped, as PowerShell itself runs it."""
    folder = tempfile.mkdtemp(prefix="said-")
    try:
        said = os.path.join(folder, "said.txt")
        with io.open(said, "w", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")
        command = ". '%s'; Show-Failed @(Get-Content -Encoding UTF8 '%s')" % (os.path.join(HERE, "said.ps1"), said)
        done = bounded.run([POWERSHELL, "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command],
                           capture_output=True, text=True, timeout=120, creationflags=NO_WINDOW)
        if done.returncode != 0:
            raise AssertionError("PowerShell could not run Show-Failed: %s" % (done.stdout + done.stderr))
        return [line.strip() for line in done.stdout.splitlines() if line.strip()]
    finally:
        shutil.rmtree(folder, True)


@unittest.skipUnless(POWERSHELL, "no PowerShell here to run the battery's display with")
class AFailedSuiteShowsWhatItSaid(unittest.TestCase):

    def test_a_failure_line_far_from_the_end_is_shown(self):
        lines = ["line %d" % n for n in range(1, 201)]
        lines[9] = "  FAIL: the check far up"
        lines[99] = "java.lang.IllegalStateException: boom"
        lines[100] = "Caused by: java.io.IOException: inner"
        out = shown(lines)
        for wanted in ("FAIL: the check far up", "java.lang.IllegalStateException: boom",
                       "Caused by: java.io.IOException: inner"):
            self.assertIn(wanted, out, "a failure line far from the end was not shown: %r" % wanted)
        self.assertEqual([line for line in out if line.startswith("line ")], ["line %d" % n for n in range(171, 201)],
                         "the last 30 lines, and only those of the rest, were not what was shown")

    def test_a_short_output_is_shown_once(self):
        out = shown(["--- a suite", "  FAIL: the one check", "FAILURES PRESENT (1)"])
        self.assertEqual(out.count("FAIL: the one check"), 1, "a failure line was shown more than once: %r" % out)
        self.assertEqual(out[-1], "FAILURES PRESENT (1)", "the verdict line was not the last shown: %r" % out)


class TheBatteryHandsEveryFailureToIt(unittest.TestCase):

    def test_test_ps1_shows_every_failed_suite_through_show_failed(self):
        with io.open(os.path.join(HERE, "test.ps1"), encoding="utf-8") as handle:
            battery = handle.read()
        self.assertIn('. (Join-Path $root "said.ps1")', battery, "test.ps1 does not load said.ps1")
        for output in ("$said", "$selfSaid", "$pySaid"):
            with self.subTest(output):
                self.assertTrue(re.search(r"Show-Failed \%s\b" % output, battery),
                                "test.ps1 does not show a failed suite's output %s through Show-Failed" % output)
        tails = [line.strip() for line in battery.splitlines() if "Select-Object -Last 2" in line]
        self.assertTrue(tails, "the control: test.ps1 shows a passing suite's last two lines")
        self.assertEqual([line for line in tails if not line.startswith("else")], [],
                         "test.ps1 does not show a failed suite through Show-Failed: a suite's last two lines are"
                         " shown whatever its verdict")


if __name__ == "__main__":
    unittest.main()
