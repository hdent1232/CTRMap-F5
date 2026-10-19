"""EVERY FLAG A TOOL READS IS ONE ITS REFUSAL ACCEPTS.

Since 2026-10-10 every tool hands its words to cli_words.refused before it acts, with the flags it reads - so a word
it ignores stops the run instead of running its default. The other direction was never asked. MEASURED 2026-10-12:
tools/guard/drive_owed.py read --workers and --keys-from in main() and refused both at its door, so `drive_owed.py
--workers 4` - its own usage line's example - ran nothing, and had run nothing since the option was written. A flag
the code reads and the door refuses is a feature that cannot be reached, and it says so only when somebody types it.

Asked by shape over every tool the walk of tools/ finds handing its words to refused(): each string that looks like a
flag and is COMPARED with the words (`"--x" in argv`, `argv.index("--x")`, `word == "--x"`, `w.startswith("--x=")`)
must be among the flags, valued flags, rest flags or prefixes that call accepts. A refusal whose accepted words this
cannot read (a name it cannot resolve to literal strings) is UNKNOWN, and said - never read as accepting everything.
"""
import ast
import io
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)

HERE = _tree_root.require(__file__)
FLAG = re.compile(r"^--[a-z][a-z0-9-]*=?$")
KEYS = ("flags", "valued", "rest", "prefixed")


def _strings(node, names):
    """The literal strings a tuple/list node, or a module-level name bound to one, holds - None if unreadable."""
    if isinstance(node, (ast.Tuple, ast.List, ast.Set)):
        out = []
        for element in node.elts:
            if isinstance(element, ast.Constant) and isinstance(element.value, str):
                out.append(element.value)
            else:
                return None
        return out
    if isinstance(node, ast.Name) and node.id in names:
        return _strings(names[node.id], names)
    return None


def flags_problems(source):
    """(read but not accepted, unreadable refusals) for one tool's source."""
    tree = ast.parse(source)
    names = {}
    for node in tree.body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            names[node.targets[0].id] = node.value
    accepted, prefixes, unreadable, calls = set(), set(), [], 0
    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and ast.unparse(node.func).split(".")[-1] == "refused":
            calls += 1
            for keyword in node.keywords:
                if keyword.arg in KEYS:
                    held = _strings(keyword.value, names)
                    if held is None:
                        unreadable.append("line %d: %s=%s" % (node.lineno, keyword.arg, ast.unparse(keyword.value)))
                    elif keyword.arg == "prefixed":
                        prefixes.update(held)
                    else:
                        accepted.update(held)
    if not calls:
        return None
    read = set()
    for node in ast.walk(tree):
        texts = []
        if isinstance(node, ast.Compare):
            texts = [part.value for part in [node.left] + node.comparators
                     if isinstance(part, ast.Constant) and isinstance(part.value, str)]
        elif isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) \
                and node.func.attr in ("index", "count", "startswith") and node.args:
            first = node.args[0]
            if isinstance(first, ast.Constant) and isinstance(first.value, str):
                texts = [first.value]
        read.update(t for t in texts if FLAG.match(t))
    missing = sorted(f for f in read if f.rstrip("=") not in accepted
                     and not any(f.startswith(p) for p in prefixes) and f not in prefixes)
    return missing, unreadable


class EveryFlagAToolReadsIsOneItAccepts(unittest.TestCase):

    def test_control_a_flag_read_and_refused_is_found_and_one_accepted_is_not(self):
        bad = ("import cli_words\nargv = []\nwords = cli_words.refused(argv, flags=('--list',), most=0)\n"
               "if '--workers' in argv:\n    n = argv[argv.index('--workers') + 1]\nif '--list' in argv:\n    pass\n")
        self.assertEqual(flags_problems(bad), (["--workers"], []), "CONTROL: a read-but-refused flag was not found")
        named = "FLAGS = ('--a', '--b')\nw = refused(x, flags=FLAGS)\nif '--b' in x:\n    pass\n"
        self.assertEqual(flags_problems(named), ([], []), "CONTROL: flags bound to a module name were not read")
        lost = "w = refused(x, flags=make())\n"
        self.assertEqual(flags_problems(lost)[1], ["line 1: flags=make()"], "CONTROL: an unreadable refusal passed")

    def test_every_tool_reads_only_flags_its_refusal_accepts(self):
        asked, missing, unreadable = 0, [], []
        for base, dirs, files in os.walk(os.path.join(HERE, "tools")):
            dirs[:] = [d for d in dirs if d != "__pycache__"]
            for name in sorted(files):
                if not name.endswith(".py"):
                    continue
                rel = os.path.relpath(os.path.join(base, name), HERE).replace(os.sep, "/")
                with io.open(os.path.join(base, name), encoding="utf-8") as handle:
                    found = flags_problems(handle.read())
                if found is None:
                    continue
                asked += 1
                missing.extend("%s reads %s" % (rel, flag) for flag in found[0])
                unreadable.extend("%s %s" % (rel, where) for where in found[1])
        self.assertGreater(asked, 20, "CONTROL: %d tools hand their words to refused() - a walk that read nothing"
                           % asked)
        print("%d of %d tools that hand their words to refused() read no flag it refuses" % (asked - len(
            {m.split(" reads ")[0] for m in missing}), asked))
        self.assertEqual(unreadable, [], "whether these refusals accept a flag is UNKNOWN: %s" % unreadable)
        self.assertEqual(missing, [], "a flag the tool reads is one its refusal turns away: %s" % missing)


if __name__ == "__main__":
    unittest.main()
