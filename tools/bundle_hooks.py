# -*- coding: utf-8 -*-
"""WHERE A PROJECT KEEPS THE BUNDLE'S HOOKS - found in the tree, never assumed.

WHY IT EXISTS, measured on CTRMap, 2026-09-24. That project keeps a guard stack of its own in
`.claude/hooks`: a dispatcher that asks every `guard_*.py` beside it for a `decide()` the
bundle's guards do not have, and seven guards whose NAMES are the bundle's. Two dispatchers with
different protocols cannot share one folder, so the bundle's hooks are installed beside the
project's (`_hooks_at` in `.claude/bundle-install.json`). Every tool and test that spelled
`.claude/hooks` then looked at the PROJECT's guards - `tiers.py` importing a `bundle_rules` that
was not there, the tests driving CTRMap's own `guard_heredoc.py` as though it were the bundle's.

The folder is the one directly under `.claude/` holding `bundle_rules.py`, which every bundle
hook that reads the rules imports, so no install of the hooks is without it. A REFUSAL when two
do: a project with two copies of the bundle's hooks cannot say which one runs, and picking one
would be a guess reported as a fact.

WHEN NONE DOES - nothing installed yet, or a scratch tree a test built - the answer is THIS
installation's layout: the folder the project this file sits in keeps them in, and `DEFAULT`
only when that finds none either. `bundle_rules.HOOKS` reads the folder IT sits in, and a test
building a scratch project writes its hooks where this says; answered `DEFAULT` instead, the
two disagreed in exactly the projects this file exists for, and every scratch test there asked
`bundle_rules` about a hook in a folder it was not looking in.

A hook never imports this: the hooks import nothing from the project, and read their own folder
from where they sit (`bundle_shell.FOLDER`, `bundle_rules.HOOKS`).
"""
import os
import sys

#: Where the bundle's hooks go when a project has not said otherwise.
DEFAULT = ".claude/hooks"

#: The file that marks a folder as the bundle's hooks.
MARK = "bundle_rules.py"


#: The project this file is installed in - ANCHORED, never counted (F216), and None for a copy
#: sitting in no project, which then answers `DEFAULT`. `_tree_root` is imported from beside this
#: file with that folder on the path for the import only: a hook loads this file by its path, and
#: must not find `tools/` shadowing its own modules afterwards.
_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)
try:
    import _tree_root  # noqa: E402
finally:
    sys.path.remove(_HERE)
HOME = _tree_root.repo_root(__file__)


def _held(root):
    """The folder under `root/.claude` holding the bundle's hooks, or None."""
    base = os.path.join(root, ".claude")
    try:
        names = sorted(os.listdir(base))
    except FileNotFoundError:
        return None
    found = [name for name in names if os.path.isfile(os.path.join(base, name, MARK))]
    if len(found) > 1:
        raise ValueError("%s holds the bundle's hooks in %d folders (%s) - which one runs cannot "
                         "be told. Keep one, and name it in `_hooks_at` if it is not %s"
                         % (root, len(found), ", ".join(".claude/" + n for n in found), DEFAULT))
    return ".claude/" + found[0] if found else None


def folder(root):
    """The bundle's hooks folder under `root`, as a '/'-separated project path.

    Raises ValueError when two folders hold the bundle's hooks, and OSError when `.claude`
    exists and cannot be listed - "I could not look" is not "there is nothing there".
    """
    return _held(root) or (_held(HOME) if HOME else None) or DEFAULT


def path(root):
    """`folder(root)` as a path on disk."""
    return os.path.join(root, *folder(root).split("/"))


#: Where a project records what it keeps elsewhere: `{"tools/x.py": {"at": "tools/audit/x.py"}}`.
DECLARATIONS = ".claude/bundle-install.json"


def placed(root, rel):
    """Where the project under `root` keeps the bundle's file `rel`: its declared `at`, else `rel`.

    WHY, measured 2026-10-07 installing into DF Engine: an installed test imported `tiers` from
    `tools/`, and that project keeps its tier tool at `tools/audit/tiers.py` - declared - so the
    test could not import it run alone, and in the whole suite found it only when another test file
    had put `tools/audit` on the path first: an answer decided by the order the files ran in.
    A project with no declarations keeps everything where the bundle does; one whose declarations
    cannot be parsed raises - the install check reports that file, and a guess here would hide it.
    """
    import json
    try:
        with open(os.path.join(root, *DECLARATIONS.split("/")), encoding="utf-8") as handle:
            held = json.load(handle)
    except FileNotFoundError:
        held = {}
    entry = held.get(rel) if isinstance(held, dict) else None
    at = entry.get("at") if isinstance(entry, dict) else None
    return os.path.join(root, *(at or rel).split("/"))


def load(root, rel):
    """The bundle's tool `rel` as the project under `root` keeps it, imported from that place.

    Every test the bundle ships asks this rather than `import <tool>`: a bare import finds a tool
    only in `tools/`, and a project that keeps it elsewhere gets an import error run alone and a
    pass in the suite only when another file put the right folder on the path first
    (`AShippedTestFindsAToolWhereTheProjectKeepsIt`)."""
    import importlib
    import sys
    where = os.path.dirname(placed(root, rel))
    if where not in sys.path:
        sys.path.insert(0, where)
    return importlib.import_module(os.path.splitext(os.path.basename(rel))[0])


#: The name a bundle file declares its settings that SHIP OFF under - `SHIPPED_OFF = {name: the
#: value it ships}`, each one of its ADAPT names: a rule a project may turn on to be stricter.
OFF_NAME = "SHIPPED_OFF"


def turn_off(module):
    """Every setting `module` declares in SHIPPED_OFF, set to the value the bundle ships it at:
    [(module, name, the value it had)], which `put_back` restores. A module declaring none is left
    as it was. A shipped test calls this on every module object it builds itself (`exec_module`);
    one it imports is turned off by `settings_off`."""
    kept = []
    for name, value in sorted((getattr(module, OFF_NAME, None) or {}).items()):
        kept.append((module, name, getattr(module, name)))
        setattr(module, name, value)
    return kept


def put_back(kept):
    """What `turn_off` or `settings_off` changed, as it was."""
    for module, name, value in reversed(kept):
        setattr(module, name, value)


def _declares_off(where):
    """Does the file at `where` assign SHIPPED_OFF a dict at module level - read from its text?"""
    import ast
    with open(where, encoding="utf-8") as handle:
        tree = ast.parse(handle.read(), where)
    return any(isinstance(node, ast.Assign) and isinstance(node.value, ast.Dict)
               and any(getattr(t, "id", "") == OFF_NAME for t in node.targets)
               for node in tree.body)


def settings_off(root):
    """Every setting the bundle ships off, at its shipped value in THIS process, for a shipped test
    of the bundle's own behaviour: what `put_back` restores, from tearDownModule.

    WHY, measured 2026-10-09 installing into CTRMap, which turns three of them on: four shipped
    tests failed there and a plant could not redden, each asking a module this process had imported
    with the project's value in it - `python - <<EOF` turned away by a rule the test was not
    about. A default patched into each test is one test from back, so every shipped test file that
    reaches such a module calls this once, in setUpModule - which `test_bundle_self.py` asks.

    FOUND IN THE TREE: every hook in the project's hooks folder and every tool under `tools/`, or
    where the project declares one (`placed`), whose text assigns SHIPPED_OFF - imported by the name
    a test imports it by, so the module a test asks IS the one turned off."""
    import importlib
    import json
    kept = []
    hooks = path(root)
    for name in sorted(os.listdir(hooks)) if os.path.isdir(hooks) else ():
        if not name.endswith(".py") or not _declares_off(os.path.join(hooks, name)):
            continue
        added = hooks not in sys.path
        if added:
            sys.path.insert(0, hooks)
        try:
            kept += turn_off(importlib.import_module(name[:-3]))
        finally:
            if added:
                sys.path.remove(hooks)
    try:
        with open(os.path.join(root, *DECLARATIONS.split("/")), encoding="utf-8") as handle:
            declared = json.load(handle)
    except FileNotFoundError:
        declared = {}
    tools = os.path.join(root, "tools")
    rels = {"tools/" + name for name in (os.listdir(tools) if os.path.isdir(tools) else ())}
    rels |= {rel for rel in (declared if isinstance(declared, dict) else ())
             if rel.startswith("tools/")}
    for rel in sorted(rels):
        where = placed(root, rel)
        if rel.endswith(".py") and os.path.isfile(where) and _declares_off(where):
            kept += turn_off(load(root, rel))
    return kept


def hook_folders(root):
    """Every folder under `root` a hook is asked from: the bundle's, and `DEFAULT` beside it.

    A project that keeps its own stack in `DEFAULT` has its hooks asked on every tool call too, so
    a rule about what runs on every call - a gate that can hang, a fix that touched a guard - is
    about both.
    """
    return sorted({DEFAULT, folder(root)})
