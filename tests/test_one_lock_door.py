"""The mutation lock has ONE door: bundle_rules.take_lock / release_lock. Nothing else writes it.

The defect (F179): three programs wrote `.mutation-in-flight` and disagreed about everything.
tools/guard/replant.py - which plants defects into this repository's own files - opened it with
mode 'w', silently overwriting a lock another run held, and removed it with os.remove, never
counting the release, so the came-and-went detection the hooks rely on was blind to every one of
its plant runs. tools/mutate2.py wrote its lock in the repository's PARENT, where no reader looks.

  instance   no Python file here but bundle_rules opens the lock for writing or removes it -
             asked of every tool and hook by walking, with a known answer first
  elsewhere  the reader of liveness takes the process id from the door's own signature, so a
             lock the door wrote is not left to the ninety-minute age rule
"""
import ast
import io
import os
import sys
import tempfile
import shutil
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _owner_switches  # noqa: E402,F401  - the owner's settings out of this process, before any hook can read one
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
HERE = _tree_root.require(__file__)
LOCK_NAME = ".mutation-in-flight"


def lock_writes(source):
    """[line] of every write or removal of the mutation lock in `source`: an open(...) in a mode
    that writes, os.remove/os.unlink/Path.unlink, whose subject names the lock - by the literal, or
    by a name holding it (`MUTATION_LOCK`, `LOCK`)."""
    tree = ast.parse(source)
    holders = {"MUTATION_LOCK"}
    for node in ast.walk(tree):
        if isinstance(node, ast.Assign) and LOCK_NAME in ast.unparse(node.value):
            holders |= {t.id for t in node.targets if isinstance(t, ast.Name)}

    def names_lock(expr):
        text = ast.unparse(expr)
        return LOCK_NAME in text or any(h in text for h in holders)

    out = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        func = ast.unparse(node.func)
        if func.endswith("open") and node.args and names_lock(node.args[0]):
            mode = node.args[1] if len(node.args) > 1 else next(
                (k.value for k in node.keywords if k.arg == "mode"), None)
            if mode is not None and any(c in ast.unparse(mode) for c in "wxa"):
                out.append(node.lineno)
        elif func in ("os.remove", "os.unlink") and node.args and names_lock(node.args[0]):
            out.append(node.lineno)
        elif func.endswith(".unlink") and names_lock(node.func):
            out.append(node.lineno)
    return sorted(set(out))


class TheLockHasOneDoor(unittest.TestCase):

    def test_the_rule_knows_a_lock_write_when_it_sees_one(self):
        self.assertEqual(lock_writes("M = R + '/.mutation-in-flight'\nopen(M, 'w').write('x')\n"), [2])
        self.assertEqual(lock_writes("import io\nio.open(MUTATION_LOCK, 'w', encoding='utf-8')\n"), [2])
        self.assertEqual(lock_writes("import os\nos.remove(MUTATION_LOCK)\n"), [2])
        self.assertEqual(lock_writes("MUTATION_LOCK.unlink()\n"), [1])
        self.assertEqual(lock_writes("open(MUTATION_LOCK, encoding='utf-8').read()\n"), [],
                         "a READ of the lock is not a write")

    def test_nothing_but_the_door_writes_the_lock(self):
        found = []
        for top in ("tools", ".claude"):
            for base, dirs, names in os.walk(os.path.join(HERE, top)):
                dirs[:] = [d for d in dirs if d != "__pycache__"]
                for name in names:
                    if not name.endswith(".py") or name == "bundle_rules.py":
                        continue
                    path = os.path.join(base, name)
                    with io.open(path, encoding="utf-8") as handle:
                        lines = lock_writes(handle.read())
                    found += ["%s:%d" % (os.path.relpath(path, HERE).replace(os.sep, "/"), n)
                              for n in lines]
        self.assertEqual(found, [], "these write or remove %s past bundle_rules.take_lock/"
                                    "release_lock - an overwrite another run cannot see, or a "
                                    "release nobody counted" % LOCK_NAME)


class ALockTheDoorWroteIsJudgedByItsProcess(unittest.TestCase):

    def test_the_liveness_reader_takes_the_pid_from_the_doors_signature(self):
        # THE BUNDLE'S READER, the one this project runs: its own copy of the suite guard, whose
        # `_live` this asked, was removed on 2026-10-09 as a second copy of one rule (F167), and the
        # lock it read is declared in the bundle's `bundle_runs.LOCKS` instead. Loaded BY ITS PATH
        # with its LOCKS set here, so neither the import order nor the project's list decides it.
        import importlib.util
        from unittest import mock
        sys.path.insert(0, os.path.join(HERE, "tools"))
        import bundle_hooks
        hooks = bundle_hooks.path(HERE)
        if hooks not in sys.path:
            sys.path.insert(0, hooks)             # its own imports: bundle_shell, _tree_root
        spec = importlib.util.spec_from_file_location(
            "ctrmap_bundle_runs", os.path.join(hooks, "bundle_runs.py"))
        bundle_runs = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(bundle_runs)
        folder = tempfile.mkdtemp(prefix="ctrmap-lockdoor-")
        self.addCleanup(shutil.rmtree, folder, True)
        path = os.path.join(folder, LOCK_NAME)
        with mock.patch.object(bundle_runs, "LOCKS", ((LOCK_NAME, "a mutation run"),)):
            # take_lock writes `rel`, then `<pid>-<16 hex>`; this process is alive, 999999 is not
            with io.open(path, "w", encoding="utf-8") as handle:
                handle.write("src/A.java\n%d-0123456789abcdef\n" % os.getpid())
            self.assertTrue(bundle_runs.lock_runs(folder),
                            "a lock held by a live process read as nothing running")
            with io.open(path, "w", encoding="utf-8") as handle:
                handle.write("src/A.java\n999999-0123456789abcdef\n")
            held = bundle_runs.lock_runs(folder)
            self.assertEqual(held, [], "a FRESH lock whose process is gone was judged by its age, "
                                       "not its process: %s" % held)


if __name__ == "__main__":
    unittest.main()
