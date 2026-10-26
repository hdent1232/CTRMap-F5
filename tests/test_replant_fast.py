"""A PLANT IS PROVEN BY ITS OWN FILE COMPILED ALONE, AND ONLY A RED IS TAKEN FROM THAT.

MEASURED 2026-10-11: replant rebuilt the whole tree with each Java plant in it and again after, about eight
minutes a plant, and the 1,113 owed plants were days of work. tools/guard/replant.py now compiles the
planted file alone in front of build/classes (overlaid) and runs the suite over that; the classes on disk
are never touched. It answers only RED: anything else - a constant another class inlined, a suite reading
build/classes as bytecode - is asked again of the full build, so the fast path can prove nothing the full
build would not. And the clean tree, asked whether a red suite says its words with nothing planted, is one
tree: it is asked once per suite and arguments, not once per plant.
"""
import io
import os
import shutil
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _owner_switches  # noqa: E402,F401  - the owner's settings out of this process, before any hook can read one
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)

HERE = _tree_root.require(__file__)
sys.path.insert(0, os.path.join(HERE, "tools", "guard"))
import replant  # noqa: E402  - the project's plant runner

LF = chr(10)


class TheFastPathTakesOnlyARed(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="replant-fast-")
        self.addCleanup(shutil.rmtree, self.root, True)
        os.makedirs(os.path.join(self.root, "src"))
        with io.open(os.path.join(self.root, "src", "A.java"), "w", encoding="utf-8", newline=LF) as handle:
            handle.write("class A { int x = 1; }" + LF)
        self.plant = {"id": "p", "file": "src/A.java", "find": "int x = 1;", "replace": "int x = 2;",
                      "suite": "ctrmap.tests.S", "args": [], "must_say": "FAIL: x is 1"}
        self.builds, self.runs = [], []
        self.answers = []

        def suite(cls, args, java, classpath=None):
            self.runs.append(classpath)
            return self.answers.pop(0)

        def build():
            self.builds.append(True)
            return True, "Build OK"
        for name, value in (("ROOT", self.root), ("hold", lambda path: None), ("release", lambda: None),
                            ("overlaid", lambda path, java: "OVERLAY" + os.pathsep + "LIBS"),
                            ("build", build), ("run_suite", suite)):
            patch = mock.patch.object(replant, name, value)
            patch.start()
            self.addCleanup(patch.stop)
        replant.BUILT_PLANTED[0] = False

    def test_a_restore_puts_back_the_time_with_the_bytes(self):
        """PAID FOR TWICE (2026-10-18, 2026-10-23): a fast-path plant's byte-exact restore left the source newer than
        its class, which no build had touched, and EdtReachTest refused a tree nobody had edited."""
        path = os.path.join(self.root, "src", "A.java")
        os.utime(path, ns=(1_000_000_000_000_000_000, 1_000_000_000_000_000_000))
        with io.open(path, "rb") as handle:
            bytes_before = handle.read()
        self.answers = [(1, "FAIL: x is 1")]
        self.assertTrue(replant.replant(self.plant, "java", "p", "g"), "CONTROL: the plant was not driven red")
        with io.open(path, "rb") as handle:
            self.assertEqual(handle.read(), bytes_before, "CONTROL: the restore did not put the bytes back")
        self.assertEqual(os.stat(path).st_mtime_ns, 1_000_000_000_000_000_000,
                         "the restored file's modification time is not the one it had before the plant")

    def test_a_red_from_the_fast_path_needs_no_build(self):
        self.answers = [(1, "FAIL: x is 1")]
        self.assertTrue(replant.replant(self.plant, "java", "p", "g"), "a red on the fast path was not taken")
        self.assertEqual((self.builds, (self.runs[0] or "").split(os.pathsep)[0]), ([], "OVERLAY"),
                         "a red on the fast path still rebuilt the tree")

    def test_anything_else_from_the_fast_path_is_asked_of_the_full_build(self):
        self.answers = [(0, "ALL PASS"), (1, "FAIL: x is 1")]
        self.assertTrue(replant.replant(self.plant, "java", "p", "g"),
                        "a plant the full build shows red was lost because the fast path did not show it")
        self.assertEqual((len(self.builds), self.runs[-1]), (1, None),
                         "a pass on the fast path was taken as the verdict, not asked again of the full build")
        self.assertTrue(replant.BUILT_PLANTED[0], "and the run does not know the classes now hold a plant")

    def test_a_fast_red_that_does_not_say_its_words_is_asked_again(self):
        """Red for some other reason is not this plant's red - a missing class the overlay could not carry
        fails a suite too. Only a red that says the plant's words is taken from the fast path."""
        self.answers = [(1, "Exception: NoSuchMethodError"), (1, "FAIL: x is 1")]
        proven = replant.replant(self.plant, "java", "p", "g")
        self.assertEqual(len(self.builds), 1, "a fast red that did not say its words was taken as the plant's")
        self.assertTrue(proven, "the full build's red was lost")

    def test_the_clean_tree_is_asked_once_per_suite(self):
        asked = []
        plants = [dict(self.plant, id="p1"), dict(self.plant, id="p2")]
        held, lost = ["p1", "p2"], []
        replant.against_the_clean_tree(plants, held, lost, True,
                                       lambda suite, args: asked.append(suite) or (0, "ALL PASS"))
        self.assertEqual((asked, held), (["ctrmap.tests.S"], ["p1", "p2"]),
                         "the clean tree was asked once per plant, not once per suite")


if __name__ == "__main__":
    unittest.main()
