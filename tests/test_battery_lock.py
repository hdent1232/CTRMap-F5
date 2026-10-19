"""EVERY READER OF THE BATTERY'S LOCK GIVES ONE ANSWER - tools/guard/battery_lock.py's.

MEASURED 2026-10-11: four readers of build/.battery-running, four rules. test.ps1 asked whether its pid was
alive, by a spelling of its own; build.ps1 asked only the file's age, so a battery past ninety minutes would
have had build/classes deleted under it; replant.py asked only whether the file was there, so a killed battery
blocked every plant build for good; the hook that refuses an edit during a run asked bundle_runs.lock_runs.
battery_lock asks the hook's rule, and the two builders ask battery_lock. test_one_battery holds test.ps1 to it.

build.ps1 is run for real here, against this tree, with the lock held by a live process: it must refuse before
its first delete. The replant build is asked with its delete and its compiler replaced, so a builder that stops
asking the lock is caught deleting nothing.
"""
import io
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
import bounded  # noqa: E402  - a deadline ends the child's whole tree

HERE = _tree_root.require(__file__)
sys.path.insert(0, os.path.join(HERE, "tools", "guard"))
import battery_lock  # noqa: E402  - the one reader of the battery's lock

LF = chr(10)
LOCK = os.path.join(HERE, *battery_lock.LOCK[0].split("/"))
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def gone():
    """The pid of a process that has finished."""
    child = subprocess.Popen([sys.executable, "-B", "-c", "pass"], creationflags=NO_WINDOW,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    child.wait(timeout=60)
    return child.pid


class TheLockIsReadByTheHooksRule(unittest.TestCase):
    """battery_lock hands the hook's reader THIS lock - not the project's whole list, and not by a rule of its own."""

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="battery-lock-")
        self.addCleanup(shutil.rmtree, self.root, True)
        os.makedirs(os.path.join(self.root, "build"))
        self.lock = os.path.join(self.root, "build", ".battery-running")

    def lock_says(self, body, age=0):
        with io.open(self.lock, "w", encoding="utf-8", newline=LF) as handle:
            handle.write(body)
        if age:
            os.utime(self.lock, (time.time() - age, time.time() - age))
        return battery_lock.holders(self.root)

    def test_a_live_battery_holds_it_and_a_killed_one_does_not(self):
        self.assertEqual(battery_lock.holders(self.root), [], "CONTROL: no lock at all was read as a battery")
        self.assertTrue(self.lock_says("started now" + LF + "pid=%d" % os.getpid() + LF),
                        "a lock naming a live process was read as nobody's")
        self.assertEqual(self.lock_says("started then" + LF + "pid=%d" % gone() + LF), [],
                         "a killed battery's lock was read as a running one")

    def test_a_mutation_run_is_not_a_battery(self):
        """The hook reads every lock the project declares, the mutation run's among them; asked about the battery
        alone, this must not - replant.py takes that lock itself, and would refuse its own build."""
        with io.open(os.path.join(self.root, ".mutation-in-flight"), "w", encoding="utf-8") as handle:
            handle.write("pid=%d" % os.getpid() + LF)
        self.assertEqual(battery_lock.holders(self.root), [], "a mutation run's lock was read as a battery")

    def test_an_open_editor_holds_the_classes_for_a_build_and_not_for_a_battery(self):
        """2026-10-11: run from this checkout the editor loads its classes from build/classes, so a build
        deleting them takes them from an open window. It declares itself in build/.editor-running; a build
        asks --classes and is held by it, while the battery's one-at-a-time check is not."""
        with io.open(os.path.join(self.root, "build", ".editor-running"), "w", encoding="utf-8") as handle:
            handle.write("started now" + LF + "pid=%d" % os.getpid() + LF + "what=the editor" + LF)
        self.assertEqual(battery_lock.holders(self.root), [], "an open editor held the battery's own lock")
        self.assertTrue(battery_lock.holders(self.root, classes=True),
                        "an open editor did not hold build/classes against a build")

    def test_a_reader_that_cannot_be_asked_is_not_a_free_lock(self):
        def unaskable(root=None, classes=False):
            raise OSError("the bundle's hooks are not where the install says")
        with mock.patch.object(battery_lock, "holders", unaskable):
            code = battery_lock.main(["battery_lock.py"])
        self.assertNotEqual(code, 0, "a lock nobody could read was read as free")

    def test_a_lock_naming_no_process_holds_until_it_is_abandoned(self):
        self.assertTrue(self.lock_says("started now, naming no process" + LF),
                        "a fresh lock naming no process was read as nobody's - unknown is not finished")
        self.assertEqual(self.lock_says("started long ago, naming no process" + LF, age=91 * 60), [],
                         "an abandoned lock naming no process still held")


class EveryBuilderAsksIt(unittest.TestCase):
    """A builder deletes build/classes; under a live battery that turns its suites into failures the tree never
    had. Both builders ask battery_lock before the delete."""

    def hold(self):
        """Hold the real lock with this process, as a running battery would - or use the battery's, when one
        holds it. Put back byte for byte afterwards."""
        try:
            with io.open(LOCK, "rb") as handle:
                before = handle.read()
        except FileNotFoundError:
            before = None
        if not battery_lock.holders():
            os.makedirs(os.path.dirname(LOCK), exist_ok=True)
            with io.open(LOCK, "w", encoding="utf-8") as handle:
                handle.write("started by %s%spid=%d%swhat=the battery%s" % (__name__, LF, os.getpid(), LF, LF))

            def put_back():
                if before is None:
                    os.remove(LOCK)
                else:
                    with io.open(LOCK, "wb") as handle:
                        handle.write(before)
            self.addCleanup(put_back)
        self.assertTrue(battery_lock.holders(), "CONTROL: the held lock reads as nobody's")

    @unittest.skipUnless(os.name == "nt", "build.ps1 is the Windows build")
    def test_build_ps1_refuses_to_delete_the_classes_under_a_live_battery(self):
        self.hold()
        env = dict(os.environ)
        env.pop("CTRMAP_BUILD_ANYWAY", None)
        done = bounded.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                            os.path.join(HERE, "build.ps1")],
                           cwd=HERE, env=env, capture_output=True, text=True, timeout=600, creationflags=NO_WINDOW)
        said = (done.stdout or "") + (done.stderr or "")
        self.assertIn("REFUSING to build", said, "build.ps1 built under a live battery: %s" % said[-600:])
        self.assertNotEqual(done.returncode, 0, "build.ps1 refused and still exited 0")

    @unittest.skipUnless(os.name == "nt", "build.ps1 is the Windows build")
    def test_build_ps1_refuses_to_delete_the_classes_under_an_open_editor(self):
        """The editor's lock alone: a battery's lock beside it makes build.ps1 refuse whatever the editor's
        says (2026-10-12: a dead battery's lock left by a refused test.ps1 kept this green with the editor's
        check removed). A dead one is set aside for the run; a live one cannot be, and this is skipped."""
        battery = LOCK
        if battery_lock.holders():
            self.skipTest("a live battery holds build/.battery-running; build.ps1 refuses on it alone")
        try:
            with io.open(battery, "rb") as handle:
                dead = handle.read()
        except FileNotFoundError:
            dead = None
        if dead is not None:
            os.remove(battery)

            def dead_back():
                with io.open(battery, "wb") as handle:
                    handle.write(dead)
            self.addCleanup(dead_back)
        editor = os.path.join(HERE, "build", ".editor-running")
        try:
            with io.open(editor, "rb") as handle:
                before = handle.read()
        except FileNotFoundError:
            before = None
        with io.open(editor, "w", encoding="utf-8") as handle:
            handle.write("started by %s%spid=%d%swhat=the editor%s" % (__name__, LF, os.getpid(), LF, LF))

        def put_back():
            if before is None:
                os.remove(editor)
            else:
                with io.open(editor, "wb") as handle:
                    handle.write(before)
        self.addCleanup(put_back)
        env = dict(os.environ)
        env.pop("CTRMAP_BUILD_ANYWAY", None)
        done = bounded.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                            os.path.join(HERE, "build.ps1")],
                           cwd=HERE, env=env, capture_output=True, text=True, timeout=600, creationflags=NO_WINDOW)
        said = (done.stdout or "") + (done.stderr or "")
        self.assertIn("REFUSING to build", said, "build.ps1 deleted the classes an open editor runs from: %s"
                      % said[-600:])

    def test_the_replant_build_refuses_under_a_live_battery_and_deletes_nothing(self):
        import replant
        self.hold()

        def refused(*args, **kwargs):
            raise AssertionError("the replant build reached its delete or its compiler under a live battery")
        with mock.patch.dict(os.environ, {}, clear=False), \
                mock.patch.object(replant.shutil, "rmtree", refused), \
                mock.patch.object(replant.bounded, "run", refused):
            os.environ.pop("CTRMAP_BUILD_ANYWAY", None)
            ok, said = replant.build_without_powershell()
        self.assertFalse(ok, "the replant build went ahead under a live battery")
        self.assertIn("REFUSING to build", said)

    def test_the_replant_build_refuses_when_the_lock_cannot_be_read(self):
        import replant

        def unaskable(root=None, classes=False):
            raise OSError("the bundle's hooks are not where the install says")

        def refused(*args, **kwargs):
            raise AssertionError("the replant build went ahead with the lock unread")
        with mock.patch.dict(os.environ, {}, clear=False), \
                mock.patch.object(battery_lock, "holders", unaskable), \
                mock.patch.object(replant.shutil, "rmtree", refused), \
                mock.patch.object(replant.bounded, "run", refused):
            os.environ.pop("CTRMAP_BUILD_ANYWAY", None)
            ok, said = replant.build_without_powershell()
        self.assertFalse(ok, "the replant build went ahead with the lock unread")
        self.assertIn("UNKNOWN", said)


if __name__ == "__main__":
    unittest.main()
