# -*- coding: utf-8 -*-
"""EVERY ROOT IS ANCHORED, NONE IS COUNTED. Held at ZERO.

A CLIMB COUNTED FROM `__file__` IS RIGHT AT ONE DEPTH. The bundle moves its files - `_hooks_at`
puts the hooks folder anywhere under `.claude/`, `at` puts any one file anywhere - and a file that
counts `dirname`s up to its project then names a folder inside it, or above it, with nothing
saying so. Measured 2026-10-08 (F216): 72 counted climbs in one project's Python, 31 in the
bundle's, and EIGHT anchored walks with FOUR anchor sets, three of which answered a counted guess
when they found none. One rule had become five behaviours.

THREE SHAPES ARE REFUSED, in every Python file in the tree:

    a COUNTED CLIMB   an expression reaching above its own file's folder from `__file__` by
                      counting - nested `dirname`, `.parent.parent`, `.parents[N]`, a join onto
                      `..` or `os.pardir`, `Path / ".."` - followed through any name that holds
                      part of the climb
    a SECOND WALK     a loop stepping a folder up with `dirname`, testing a name joined onto it,
                      and answering THE FOLDER - an anchor rule of its own - outside
                      `_tree_root.py`. A walk that answers a FILE it found is a search, not a root.
    a DRIFTED COPY    a `_tree_root.py` whose bytes are not `tools/_tree_root.py`'s

A file's OWN folder - `dirname(abspath(__file__))` - is no climb: importing from it is how a file
reaches `_tree_root` at all. The root's own parent - `dirname(ROOT)` with ROOT anchored - is no
climb either: it is counted from the anchor, not from where a file happens to sit.

    python -B tools/counted_roots.py
"""
import ast
import io
import os
import subprocess
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

LF = chr(10)
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0
HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import _tree_root        # noqa: E402  - the one definition this holds every copy to

#: The definition every copy must equal, byte for byte.
CANONICAL = os.path.join(HERE, "_tree_root.py")
COPY = "_tree_root.py"

#: ASKED AT THE WRITE of any Python file, tests included: a test that counts its way to the
#: project is as wrong at another depth as a tool that does.
AT_WRITE = "findings_in"
AT_SCOPE = r"\.py$"

#: Calls that pass a path through unchanged in level.
_SAME = ("abspath", "realpath", "normpath", "Path", "PurePath", "str", "fspath", "absolute",
         "expanduser")


def _called(func):
    return getattr(func, "attr", None) or getattr(func, "id", None)


def _level(node, names):
    """How many folders above `__file__` NODE is (the file 0, its folder 1), or None."""
    if isinstance(node, ast.Name):
        return 0 if node.id == "__file__" else names.get(node.id)
    if isinstance(node, ast.Call):
        called = _called(node.func)
        if called in ("resolve", "absolute") and isinstance(node.func, ast.Attribute) \
                and not node.args:
            return _level(node.func.value, names)
        if called in _SAME and node.args:
            return _level(node.args[0], names)
        if called == "dirname" and node.args:
            inner = _level(node.args[0], names)
            return None if inner is None else inner + 1
        if called == "join" and node.args:
            inner = _level(node.args[0], names)
            if inner is None:
                return None
            ups = 0
            for part in node.args[1:]:
                if isinstance(part, ast.Constant) and isinstance(part.value, str):
                    steps = part.value.replace(chr(92), "/").split("/")
                    if set(steps) - {"..", ""}:
                        return None          # a path INTO somewhere, not a folder climbed to
                    ups += steps.count("..")
                elif isinstance(part, ast.Attribute) and part.attr == "pardir":
                    ups += 1
                else:
                    return None
            return inner + ups
        return None
    if isinstance(node, ast.Attribute) and node.attr == "parent":
        inner = _level(node.value, names)
        return None if inner is None else inner + 1
    if isinstance(node, ast.Subscript) and isinstance(node.value, ast.Attribute) \
            and node.value.attr == "parents":
        inner = _level(node.value.value, names)
        index = node.slice
        if inner is None or not (isinstance(index, ast.Constant) and isinstance(index.value, int)):
            return None
        return inner + index.value + 1
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Div):
        inner = _level(node.left, names)
        right = node.right
        if inner is not None and isinstance(right, ast.Constant) and right.value == "..":
            return inner + 1
    return None


def _from(node, names):
    """The level of what NODE climbs FROM, so a wrapper (abspath, Path, str) adds nothing."""
    if isinstance(node, ast.Call):
        if _called(node.func) in ("resolve", "absolute") and isinstance(node.func, ast.Attribute):
            return _level(node.func.value, names) or 0
        return (_level(node.args[0], names) or 0) if node.args else 0
    if isinstance(node, ast.Attribute):
        return _level(node.value, names) or 0
    if isinstance(node, ast.Subscript):
        return _level(node.value.value, names) or 0
    if isinstance(node, ast.BinOp):
        return _level(node.left, names) or 0
    return 0


def _anchored_steps(tree):
    """ids of every `dirname(NAME)` that STEPS a loop testing a name joined onto NAME - an anchored
    walk or search, which stops where a named thing is, not after a number of folders. Only the
    step is excused: a counted fallback inside the same loop is still a counted climb."""
    steps = set()
    for loop in ast.walk(tree):
        if not isinstance(loop, (ast.While, ast.For)):
            continue
        calls = [node for node in ast.walk(loop) if isinstance(node, ast.Call)]
        stepping = [node for node in calls if _called(node.func) == "dirname" and node.args
                    and isinstance(node.args[0], ast.Name)]
        tested = {node.args[0].id for node in calls if _called(node.func) == "join"
                  and len(node.args) >= 2 and isinstance(node.args[0], ast.Name)}
        steps.update(id(node) for node in stepping if node.args[0].id in tested)
    return steps


def climbs(source):
    """[line] of every counted climb above its own file's folder in SOURCE."""
    found = []
    tree = ast.parse(source)
    excused = _anchored_steps(tree)

    def scope(body, names):
        names = dict(names)
        for statement in body:
            if isinstance(statement, ast.Assign) and len(statement.targets) == 1 \
                    and isinstance(statement.targets[0], ast.Name):
                held = _level(statement.value, names)
                if held is not None:
                    names[statement.targets[0].id] = held
            if isinstance(statement, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                scope(statement.body, names)
                continue
            for node in ast.walk(statement):
                if isinstance(node, ast.Name) or id(node) in excused:
                    continue        # a NAME holding a climb is a use of it, not another climb
                held = _level(node, names)
                if held is not None and held >= 2 and held > _from(node, names):
                    found.append(node)
    scope(tree.body, {})
    inside = {id(sub) for node in found for sub in ast.walk(node) if sub is not node}
    return sorted({node.lineno for node in found if id(node) not in inside})


def walks(source):
    """[line] of every loop that steps a folder up with `dirname`, tests a name joined onto it,
    and answers the folder itself - a definition of the root of its own."""
    found = []
    for function in ast.walk(ast.parse(source)):
        if not isinstance(function, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        for loop in ast.walk(function):
            if not isinstance(loop, (ast.While, ast.For)):
                continue
            stepped = {node.args[0].id for node in ast.walk(loop) if isinstance(node, ast.Call)
                       and _called(node.func) == "dirname" and node.args
                       and isinstance(node.args[0], ast.Name)}
            tested = [node for node in ast.walk(loop) if isinstance(node, ast.Call)
                      and _called(node.func) == "join" and len(node.args) >= 2
                      and isinstance(node.args[0], ast.Name) and node.args[0].id in stepped]
            if not tested:
                continue
            answers = set()
            for node in ast.walk(function):
                if isinstance(node, ast.Return) and node.value is not None:
                    value = node.value
                    while isinstance(value, ast.Call) and _called(value.func) in _SAME \
                            and value.args:
                        value = value.args[0]
                    if isinstance(value, ast.Name):
                        answers.add(value.id)
            if answers & stepped:
                found.append(function.lineno)
                break
    return found


def _canonical():
    with io.open(CANONICAL, encoding="utf-8", newline="") as handle:
        return handle.read()


def _canonical_rel():
    """The definition's own project path - it is the one `_tree_root.py` not held to another."""
    root = _tree_root.require(__file__)
    return os.path.relpath(CANONICAL, root).replace(os.sep, "/")


def findings_in(rel, source):
    """[findings] for ONE Python file's proposed source."""
    rel = str(rel).replace(os.sep, "/")
    if not rel.endswith(".py"):
        return []
    name = rel.rsplit("/", 1)[-1]
    if name == COPY:
        if rel == _canonical_rel():
            return []
        if source != _canonical():
            return ["%s is not tools/%s byte for byte - the root has ONE definition, and a copy "
                    "that drifts is a second" % (rel, COPY)]
        return []
    try:
        out = ["%d: a root counted in folders from __file__ - ask _tree_root.require(__file__)"
               % line for line in climbs(source)]
        out += ["%d: an anchored walk of its own - the root is _tree_root.repo_root, one "
                "definition" % line for line in walks(source)]
    except SyntaxError:
        return []
    return out


def python_files(root):
    """Every Python file in the tree - tracked, and untracked but not ignored - or None when git
    cannot answer: a tree nobody could list is not a clean one."""
    try:
        done = subprocess.run(["git", "-C", root, "ls-files", "-co", "--exclude-standard", "-z"],
                              capture_output=True, timeout=60, creationflags=NO_WINDOW)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if done.returncode != 0:
        return None
    names = done.stdout.decode("utf-8", "replace").split(chr(0))
    return sorted(name for name in names if name.endswith(".py")
                  and os.path.isfile(os.path.join(root, name)))


def offenders(root):
    """[(rel, finding)] over the whole tree, or None when the tree cannot be listed."""
    files = python_files(root)
    if files is None:
        return None
    out = []
    for rel in files:
        with io.open(os.path.join(root, rel), encoding="utf-8", errors="replace",
                     newline="") as handle:
            source = handle.read()
        out.extend((rel, finding) for finding in findings_in(rel, source))
    return out


#: KNOWN ANSWERS, asked before any tree is believed: a checker that has stopped seeing the shape
#: reports a clean tree in exactly the words a clean tree would earn.
CONTROLS = (
    ("import os" + LF + "R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))" + LF, 1),
    ("import os" + LF + "H = os.path.dirname(os.path.abspath(__file__))" + LF
     + "R = os.path.dirname(H)" + LF, 1),
    ("import os" + LF + "H = os.path.dirname(os.path.abspath(__file__))" + LF
     + "R = os.path.join(H, os.pardir)" + LF, 1),
    ("from pathlib import Path" + LF + "R = Path(__file__).resolve().parents[1]" + LF, 1),
    ("from pathlib import Path" + LF + "R = Path(__file__).parent.parent" + LF, 1),
    ("import os" + LF + "def r():" + LF + "    here = os.getcwd()" + LF + "    while True:" + LF
     + "        if os.path.exists(os.path.join(here, '.git')):" + LF + "            return here"
     + LF + "        here = os.path.dirname(here)" + LF, 1),
    ("import os" + LF + "H = os.path.dirname(os.path.abspath(__file__))" + LF
     + "T = os.path.join(H, 'x')" + LF, 0),
    # an anchored SEARCH for a named file, stepping up from this file's folder: no count
    ("import os" + LF + "H = os.path.dirname(os.path.abspath(__file__))" + LF + "def f():" + LF
     + "    here = H" + LF + "    for _ in range(5):" + LF + "        here = os.path.dirname(here)"
     + LF + "        c = os.path.join(here, 'tools', 'x.py')" + LF
     + "        if os.path.isfile(c):" + LF + "            return c" + LF, 0),
    # the same loop with nothing tested is a count, however it is written
    ("import os" + LF + "H = os.path.dirname(os.path.abspath(__file__))" + LF + "def f():" + LF
     + "    here = H" + LF + "    for _ in range(2):" + LF + "        here = os.path.dirname(here)"
     + LF + "    return here" + LF, 1),
    # a root walk of its own, with a counted guess when it finds nothing: both refused
    ("import os" + LF + "def r():" + LF + "    here = os.path.dirname(os.path.abspath(__file__))"
     + LF + "    while True:" + LF + "        if os.path.exists(os.path.join(here, '.git')):" + LF
     + "            return here" + LF + "        parent = os.path.dirname(here)" + LF
     + "        if parent == here:" + LF
     + "            return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))" + LF
     + "        here = parent" + LF, 2),
    ("import os" + LF + "import _tree_root" + LF + "R = _tree_root.require(__file__)" + LF
     + "P = os.path.dirname(R)" + LF, 0),
)


def controls():
    """'' when every known answer holds, else what went wrong."""
    for source, want in CONTROLS:
        got = len(findings_in("x.py", source))
        if got != want:
            return "a known answer did not hold: %d finding(s), expected %d, in:%s%s" % (
                got, want, LF, source)
    return ""


def main(argv):
    broken = controls()
    if broken:
        sys.stdout.write("REFUSING: the checker is broken - %s%s" % (broken, LF))
        return 1
    root = _tree_root.repo_root(__file__)
    files = python_files(root) if root else None
    if not files:
        # NOTHING LISTED IS NOT A CLEAN TREE.
        sys.stdout.write("REFUSING: the Python files under %s could not be listed, so whether any "
                         "root is counted is UNKNOWN%s" % (root, LF))
        return 1
    bad = offenders(root) or []
    sys.stdout.write("%d Python file(s); %d counted root(s), second walk(s) or drifted copies%s"
                     % (len(files), len(bad), LF))
    for rel, finding in bad:
        sys.stdout.write("  REFUSING: %s:%s%s" % (rel, finding, LF))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
