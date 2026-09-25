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

#: Where the bundle's hooks go when a project has not said otherwise.
DEFAULT = ".claude/hooks"

#: The file that marks a folder as the bundle's hooks.
MARK = "bundle_rules.py"


#: The project this file is installed in: it sits in that project's `tools/`.
HOME = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


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
    return _held(root) or _held(HOME) or DEFAULT


def path(root):
    """`folder(root)` as a path on disk."""
    return os.path.join(root, *folder(root).split("/"))


def hook_folders(root):
    """Every folder under `root` a hook is asked from: the bundle's, and `DEFAULT` beside it.

    A project that keeps its own stack in `DEFAULT` has its hooks asked on every tool call too, so
    a rule about what runs on every call - a gate that can hang, a fix that touched a guard - is
    about both.
    """
    return sorted({DEFAULT, folder(root)})
