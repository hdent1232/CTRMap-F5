"""ONE BATTERY AT A TIME: test.ps1 refuses to start while a live battery holds build/.battery-running.

MEASURED 2026-10-11: BatteryHygieneTest's stand-in for record_run's subprocess caught nothing once record_run
ran the battery through bounded.run, so record_run started the REAL battery from inside the battery, and that
one did the same - 302 processes, every level a full battery, before the tree was killed by hand. Whatever a
test does wrong, a second battery may not start beside a live one.

Run inside the battery, the battery's own lock is the live one; run alone, this test holds the lock with its
own pid, as a running battery would. Either way the second battery must refuse before it runs a suite. Whether
the lock is held is asked of tools/guard/battery_lock.py, the reader test.ps1 itself asks.

THE SECOND BATTERY IS POINTED AT A DUMP THAT IS NOT THERE, so a test.ps1 whose lock check has gone stops at its
corpus refusal seconds later instead of running forty minutes of suites inside this one - which is the very
recursion this exists to prevent. The lock is put back byte for byte afterwards, whatever that battery wrote.
"""
import io
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _owner_switches  # noqa: E402,F401  - the owner's settings out of this process, before any hook can read one
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
import bounded  # noqa: E402  - a deadline ends the child's whole tree

HERE = _tree_root.require(__file__)
sys.path.insert(0, os.path.join(HERE, "tools", "guard"))
import battery_lock  # noqa: E402  - the one reader of the battery's lock

LOCK = os.path.join(HERE, *battery_lock.LOCK[0].split("/"))
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def _read(path):
    try:
        with io.open(path, "rb") as handle:
            return handle.read()
    except FileNotFoundError:
        return None


@unittest.skipUnless(os.name == "nt", "test.ps1 is the Windows battery")
class ASecondBatteryIsRefused(unittest.TestCase):

    def test_a_battery_started_while_a_live_one_holds_the_lock_is_refused(self):
        before = _read(LOCK)
        made = not battery_lock.holders()
        if made:
            os.makedirs(os.path.dirname(LOCK), exist_ok=True)
            with io.open(LOCK, "w", encoding="utf-8") as handle:
                handle.write("started by %s%spid=%d%swhat=the battery%s" % (__name__, "\n", os.getpid(), "\n", "\n"))
        self.assertTrue(battery_lock.holders(), "CONTROL: the lock this test holds reads as nobody's")
        held = _read(LOCK)
        nowhere = tempfile.mkdtemp(prefix="one-battery-")
        #started as the owner starts it - not already on the unattended desktop - so it must move itself there
        env = dict(os.environ)
        env.pop("CTRMAP_ON_UNATTENDED_DESKTOP", None)
        try:
            done = bounded.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                                os.path.join(HERE, "test.ps1"), "-Anyway",
                                "a test of the battery's own lock, which must start no suite",
                                "-Pristine", os.path.join(nowhere, "no-dump"),
                                "-GameDir", os.path.join(nowhere, "no-game")],
                               cwd=HERE, env=env, capture_output=True, text=True, timeout=120, creationflags=NO_WINDOW)
        finally:
            os.rmdir(nowhere)
            if _read(LOCK) != held:              # a second battery that got past the check wrote its own
                with io.open(LOCK, "wb") as handle:
                    handle.write(held)
            if made and _read(LOCK) == held:
                if before is None:
                    os.remove(LOCK)
                else:
                    with io.open(LOCK, "wb") as handle:
                        handle.write(before)
        said = (done.stdout or "") + (done.stderr or "")
        self.assertIn("one battery at a time", said, "a second battery started while a live one held the lock: %s"
                      % said[-600:])
        self.assertNotEqual(done.returncode, 0, "a second battery refused and still exited 0")

    def test_the_battery_moves_itself_onto_the_unattended_desktop(self):
        """2026-10-11: run from the owner's screen, the battery's suites opened the editor's windows there,
        and one cost the owner a window of edits. Started as the owner starts it, test.ps1 runs itself again
        on the unattended desktop (tools/guard/hidden_desktop.py) and says so - pointed at a dump that is
        not there, so even a test.ps1 that stayed here would stop before any suite opened a window."""
        before = _read(LOCK)
        if battery_lock.holders():
            self.skipTest("a live battery holds build/.battery-running, and a second one refuses before it moves")
        nowhere = tempfile.mkdtemp(prefix="one-battery-desk-")
        env = dict(os.environ)
        env.pop("CTRMAP_ON_UNATTENDED_DESKTOP", None)
        try:
            done = bounded.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                                os.path.join(HERE, "test.ps1"), "-Anyway", "a test of where the battery runs",
                                "-Pristine", os.path.join(nowhere, "no-dump"),
                                "-GameDir", os.path.join(nowhere, "no-game")],
                               cwd=HERE, env=env, capture_output=True, text=True, timeout=180, creationflags=NO_WINDOW)
        finally:
            os.rmdir(nowhere)
            left = _read(LOCK)
            if left != before:
                if before is None:
                    os.remove(LOCK)
                else:
                    with io.open(LOCK, "wb") as handle:
                        handle.write(before)
        said = (done.stdout or "") + (done.stderr or "")
        self.assertIn("the battery runs on the unattended desktop", said,
                      "the battery ran on the owner's desktop: %s" % said[-600:])
        #PAID FOR 2026-10-12: test.ps1 wrote its lock and then refused the missing dump, and the lock it left
        #named a dead battery - build.ps1 then asked about the battery when the editor's lock was the subject
        self.assertEqual(left, before, "a battery that refused to run left its lock behind: %r" % (left,))


if __name__ == "__main__":
    unittest.main()
