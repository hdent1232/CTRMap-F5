"""Two trees' code in one process: a plain import name is the file asked for, never the other tree's.

The defect, measured 2026-10-09 on this PC. `tools/bundle_install.py` loads the bundle's
`check_install.py` from the bundle's own folder into the hook process, and that checker put the
bundle's `tools` folder FIRST on `sys.path` and left it there. The hook then asked the project's
`tools/tiers.py` as a rule; `bundle_rules.load` imported it by plain name, `import tiers` found the
bundle's newer copy, and that copy called `bundle_rules.hooks_disabled`, which this project's
`bundle_rules` does not have. Every judgement held the round's 107 staged files on "tiers could
not answer", and every act but a read was refused - the session that applied the patch wedged.

  instance   `bundle_rules.load` returns the file asked for when a same-named module sits earlier
             on the path, and when one from another file is already loaded
  door       every call through `bundle_install`'s door leaves `sys.path` and the project's
             modules as it found them, and inside it the bundle's code imports its OWN siblings
  control    with the door open, the path really is changed by the checker - the test is real
"""
import io
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
HERE = _tree_root.require(__file__)
sys.path.insert(0, os.path.join(HERE, "tools"))
import bundle_hooks  # noqa: E402  - where this project keeps the bundle's hooks
sys.path.insert(0, bundle_hooks.path(HERE))
import bundle_rules  # noqa: E402  - whose `load` asks a rule by its file
bundle_install = bundle_hooks.load(HERE, "tools/bundle_install.py")  # the one door the bundle's code comes through

LF = chr(10)


def _write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline=LF) as handle:
        handle.write(text)


class Isolated(unittest.TestCase):
    """Every test here changes `sys.path` and `sys.modules` on purpose; both are put back."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="ctrmap-twotrees-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        saved_path, saved_modules = list(sys.path), dict(sys.modules)

        def restore():
            sys.path[:] = saved_path
            for name in list(sys.modules):
                if name not in saved_modules:
                    del sys.modules[name]
            sys.modules.update(saved_modules)
        self.addCleanup(restore)


class APlainNameIsTheFileAskedFor(Isolated):

    def test_a_same_named_module_earlier_on_the_path_does_not_answer(self):
        theirs = os.path.join(self.tmp, "bundle", "tools")
        ours = os.path.join(self.tmp, "project", "tools")
        _write(os.path.join(theirs, "twotrees_rule.py"), "WHO = 'bundle'" + LF)
        _write(os.path.join(ours, "twotrees_rule.py"), "WHO = 'project'" + LF)
        sys.path[:0] = [theirs, ours]          # the bundle's folder FIRST, as the checker left it
        got = bundle_rules.load(os.path.join(ours, "twotrees_rule.py"))
        self.assertEqual(got.WHO, "project",
                         "load returned the same-named module from the folder earlier on the path")

    def test_a_loaded_module_from_another_file_is_not_returned(self):
        theirs = os.path.join(self.tmp, "bundle", "tools")
        ours = os.path.join(self.tmp, "project", "tools")
        _write(os.path.join(theirs, "twotrees_held.py"), "WHO = 'bundle'" + LF)
        _write(os.path.join(ours, "twotrees_held.py"), "WHO = 'project'" + LF)
        sys.path.insert(0, theirs)
        import twotrees_held  # noqa: F401 - the other tree's module, already in sys.modules
        got = bundle_rules.load(os.path.join(ours, "twotrees_held.py"))
        self.assertEqual(got.WHO, "project", "load returned the module another file had loaded")

    def test_a_rules_sibling_import_resolves_beside_the_rule(self):
        theirs = os.path.join(self.tmp, "bundle", "tools")
        ours = os.path.join(self.tmp, "project", "tools")
        _write(os.path.join(theirs, "twotrees_sib.py"), "WHO = 'bundle'" + LF)
        _write(os.path.join(ours, "twotrees_sib.py"), "WHO = 'project'" + LF)
        _write(os.path.join(ours, "twotrees_user.py"),
               "import twotrees_sib" + LF + "WHO = twotrees_sib.WHO" + LF)
        sys.path[:0] = [theirs, ours]
        got = bundle_rules.load(os.path.join(ours, "twotrees_user.py"))
        self.assertEqual(got.WHO, "project",
                         "a rule's `import sibling` found the other tree's sibling first")


#: A stand-in for the bundle's checker: it does what the real one did - puts its own folder first
#: on the path, leaves it there, and imports a sibling by a name the project also has loaded.
CHECKER = LF.join([
    "import os, sys",
    "HERE = os.path.dirname(os.path.abspath(__file__))",
    "def problems(project):",
    "    sys.path.insert(0, HERE)",
    "    import twotrees_stamps",
    "    return [twotrees_stamps.WHO], ['asked']",
    ""])


class TheBundlesCodeLeavesTheProcessAsItFoundIt(Isolated):

    def setUp(self):
        super(TheBundlesCodeLeavesTheProcessAsItFoundIt, self).setUp()
        self.bundle = os.path.join(self.tmp, "bundle")
        _write(os.path.join(self.bundle, "tools", "check_install.py"), CHECKER)
        _write(os.path.join(self.bundle, "tools", "twotrees_stamps.py"), "WHO = 'bundle'" + LF)
        project = os.path.join(self.tmp, "project", "tools")
        _write(os.path.join(project, "twotrees_stamps.py"), "WHO = 'project'" + LF)
        sys.path.insert(0, project)
        import twotrees_stamps
        self.ours = twotrees_stamps

    def test_the_path_and_the_projects_modules_are_put_back(self):
        before = list(sys.path)
        checker, why = bundle_install.load_checker(self.bundle)
        self.assertIsNone(why, why)
        found, _asked = checker.problems(self.tmp)
        self.assertEqual(found, ["bundle"],
                         "the bundle's code imported the PROJECT's same-named module")
        self.assertEqual(sys.path, before, "the bundle's code left the import path changed")
        self.assertIs(sys.modules.get("twotrees_stamps"), self.ours,
                      "the project's module was replaced by the bundle's")

    def test_control_without_the_door_the_checker_does_change_the_path(self):
        checker, why = bundle_install.load_checker(self.bundle)
        self.assertIsNone(why, why)
        before = list(sys.path)
        checker.module.problems(self.tmp)                 # past the door, on purpose
        self.assertNotEqual(sys.path, before,
                            "the stand-in checker changed nothing - the door test proves nothing")


if __name__ == "__main__":
    unittest.main()
