# -*- coding: utf-8 -*-
"""A MODULE CONSTANT IS NEVER REPLACED BY A SECOND ASSIGNMENT THAT DOES NOT READ IT.

Measured 2026-10-15 in this bundle: `bundle_shell.py` gained a second `LAUNCH_KEYS = (...)` further down the file. Python
binds a module name to its LAST assignment, so every reader got the second tuple - shorter than the first - and launch
detection weakened everywhere at once, with lint passing: a name assigned twice is not an error to a linter. The held
plants caught it at the commit; nothing caught it at the write. (DF Engine F223.)

WHAT THIS REFUSES, asked of the AST of every Python file git knows here: a module CONSTANT - a name spelled in capitals,
`LAUNCH_KEYS`, `_SECTION` - assigned at the TOP LEVEL of a module (`X = ...`, `X: T = ...`, or a name inside a tuple
target) and assigned again at the top level by a value that does not read it. A script's working names (`tree`, `d`)
are reassigned step by step on purpose and are not asked: measured in DF Engine, they were all nine second assignments a
rule over every name found, and none was a constant. A second assignment that extends the first reads it and passes - `DATASETS = DATASETS + (...)`, `X = f(X)` -
and so does `X += ...`, which is not a replacement. An assignment inside an `if`, `try` or `with` at the top level is a
conditional definition - an import fallback, a platform branch - and is not asked; `_` is a throwaway and is not asked.
A first assignment of `None` or an empty literal is a DECLARATION, filled in below: the value that fills it is the one
held, and any assignment after that which does not read it is refused (CTRMap's `PRIOR_EXACT, PRIOR_FILE = {}, {}`,
"filled in below, once, from the last run", measured 2026-10-17 as the only two second assignments of a constant there).

    python -B tools/reassigned.py     0 when no module constant is silently replaced, else REFUSING

THE SCOPE IS THE TREE: `git ls-files` for what is tracked and what is new and not ignored. A git that cannot answer is a
refusal, and a tree with no Python file in it is the wrong tree - an unreadable tree is not a clean one.
"""
import ast
import io
import os
import subprocess
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root        # noqa: E402  - the project root, anchored: never counted (F216)
import bounded         # noqa: E402  - a deadline ends the child's whole tree

#: What a project may need to change about this file. `check_install.py` compares an installed
#: copy with these blanked. `EXCLUDE` - path prefixes that are not this project's code (vendored
#: trees, generated files), each one a decision a reader can see.
ADAPT = ("EXCLUDE", "GIT_TIMEOUT")
EXCLUDE = ()
GIT_TIMEOUT = 120

#: The settings that ship OFF, at the value they ship at. A project turns them on; the bundle's
#: own tests turn them off again for their run (`bundle_hooks.settings_off`).
SHIPPED_OFF = {"EXCLUDE": ()}

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

#: A name nobody reads: assigning it twice replaces nothing anybody uses.
THROWAWAY = "_"


def root():
    """The repository this file sits in, ANCHORED - `_tree_root.repo_root`, the one definition (F216)."""
    return _tree_root.repo_root(__file__)


def _bound(target):
    """The names an assignment target binds: one, or every name inside a tuple or list."""
    if isinstance(target, ast.Name):
        return [target.id]
    if isinstance(target, (ast.Tuple, ast.List)):
        return [name for element in target.elts for name in _bound(element)]
    if isinstance(target, ast.Starred):
        return _bound(target.value)
    return []


def _reads(value, name):
    return any(isinstance(node, ast.Name) and node.id == name for node in ast.walk(value))


def is_constant(name):
    """A module CONSTANT by its spelling - capitals, digits and underscores, with at least one letter: `LAUNCH_KEYS`,
    `_SECTION`. A script's working names (`tree`, `src`, `d`) are reassigned on purpose, step by step, and measured
    2026-10-17 they were every one of nine second assignments a rule over every name found in DF Engine."""
    bare = name.lstrip("_")
    return bool(bare) and bare.upper() == bare and any(c.isalpha() for c in bare)


def is_placeholder(value):
    """A DECLARATION, not a value: `None` or an empty literal - `{}`, `()`, `[]`, `set()`. Written above the functions that
    name it and filled in below, once (CTRMap's `PRIOR_EXACT, PRIOR_FILE = {}, {}`, "filled in below, once, from the last
    run"). The value that fills it is the one held; anything after THAT which does not read it is still refused."""
    if isinstance(value, ast.Constant) and value.value is None:
        return True
    if isinstance(value, (ast.Dict, ast.Tuple, ast.List, ast.Set)):
        parts = value.keys if isinstance(value, ast.Dict) else value.elts
        return not parts or (isinstance(value, ast.Tuple) and all(is_placeholder(p) for p in parts))
    return (isinstance(value, ast.Call) and isinstance(value.func, ast.Name) and value.func.id in ("set", "dict", "list")
            and not value.args and not value.keywords)


def _values(stmt):
    """[(name, the value it is given)] for one top-level assignment - a tuple target paired element by element."""
    if isinstance(stmt, ast.Assign):
        pairs = []
        for target in stmt.targets:
            if (isinstance(target, (ast.Tuple, ast.List)) and isinstance(stmt.value, (ast.Tuple, ast.List))
                    and len(target.elts) == len(stmt.value.elts)):
                pairs += [(n, v) for t, v in zip(target.elts, stmt.value.elts) for n in _bound(t)]
            else:
                pairs += [(n, stmt.value) for n in _bound(target)]
        return pairs
    if isinstance(stmt, ast.AnnAssign) and stmt.value is not None:
        return [(n, stmt.value) for n in _bound(stmt.target)]
    return []


def offenders(source, path="<source>"):
    """[(line, why)] for every top-level constant replaced by a later assignment that does not read it."""
    tree = ast.parse(source, path)
    held = {}
    found = []
    for stmt in tree.body:
        for name, value in _values(stmt):
            if name == THROWAWAY or not is_constant(name):
                continue
            if name in held and not _reads(value, name):
                found.append((stmt.lineno, "%s, assigned at line %d, is assigned again here by a value that does not "
                                           "read it - every reader of %s gets this one, and the first is silently gone"
                              % (name, held[name], name)))
            if name not in held and not is_placeholder(value):
                held[name] = stmt.lineno
    return found


#: A project with a WRITE-TIME channel - one that asks each rule about a file's proposed text
#: before the bytes land - finds this by the name, and asks `findings_in` per file. The commit
#: runs `main` over the whole tree as the second layer.
AT_WRITE = "findings_in"


def findings_in(rel, source):
    """[findings] for ONE file's proposed source - the same rule `main` asks of the tree."""
    if not str(rel).endswith(".py"):
        return []
    try:
        return ["%d: %s" % (line, why) for line, why in offenders(source, rel)]
    except SyntaxError:
        return []                          # not this rule's question; the compiler asks it


def files(where):
    """Every Python file git knows in `where`, or None when git cannot be asked."""
    try:
        done = bounded.run(["git", "ls-files", "-co", "--exclude-standard", "--", "*.py"],
                           cwd=where, capture_output=True, text=True,
                           creationflags=NO_WINDOW, timeout=GIT_TIMEOUT)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if done.returncode != 0:
        return None
    return sorted(line.strip() for line in done.stdout.splitlines()
                  if line.strip() and not line.strip().startswith(tuple(EXCLUDE)))


def problems(where=None):
    """Every silently replaced module constant, as lines - or the reason nothing could be asked."""
    where = where or root()
    if where is None:
        return ["REFUSING: no `.git` above %s, so there is no tree to ask - an unreadable tree "
                "is not a clean one" % os.path.dirname(os.path.abspath(__file__))]
    listed = files(where)
    if listed is None:
        return ["REFUSING: git could not list the files in %s, so which assignments exist is "
                "UNKNOWN" % where]
    if not listed:
        return ["REFUSING: git lists NO Python file in %s - that is the wrong tree, not a "
                "clean one" % where]
    out = []
    for rel in listed:
        path = os.path.join(where, rel.replace("/", os.sep))
        try:
            with io.open(path, encoding="utf-8", errors="replace") as handle:
                source = handle.read()
            for line, why in offenders(source, path):
                out.append("%s:%d  %s" % (rel, line, why))
        except SyntaxError:
            continue                       # not this check's question; the suite asks it
        except OSError:
            continue                       # listed and since deleted
    return out


def main(argv):
    import cli_words      # a word this does not read refuses the run, before anything runs
    why = cli_words.refused(argv[1:], most=1)
    if why:
        print(why)
        return 2
    found = problems(argv[1] if len(argv) > 1 else None)
    if found:
        print("REFUSING: %d module constant(s) silently replaced by a second assignment - Python binds the name to the "
              "last one, and a second LAUNCH_KEYS weakened every reader of the first:" % len(found))
        for line in found:
            print("   " + line)
        return 1
    print("no module constant is replaced by a second assignment that does not read it")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
