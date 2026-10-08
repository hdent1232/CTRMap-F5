# -*- coding: utf-8 -*-
"""THE PROJECT ROOT, ANCHORED - NEVER COUNTED. ONE DEFINITION, THE SAME BYTES IN EVERY FOLDER.

A file that finds its project by counting `dirname`s up from `__file__` is right only while it sits
at the depth it was written for, and the bundle moves files: `_hooks_at` puts the whole hooks
folder anywhere under `.claude/`, and `at` puts any one file anywhere at all. Measured 2026-10-08
(F216): 72 counted climbs in one project's Python and 31 in the bundle's - and EIGHT anchored walks
with FOUR different anchor sets (`.git`; `CLAUDE.md`; either; `.git` or `.githooks`), three of
which, finding none, answered a counted guess as though it were the root.

THE ROOT is the nearest folder at or above a file holding `.git` - a folder, or a file in a
worktree - or `CLAUDE.md`. A file outside every project HAS no root: `repo_root` answers None and
`require` refuses in words, where a count would have named some folder anyway.

COPIED, NOT IMPORTED ACROSS FOLDERS. A file can always import from its own folder, and reaching
any other folder first needs the root - so each folder whose files need one holds this file.
Every copy is held byte-identical, and no other anchored walk or counted climb may exist:
`tools/counted_roots.py`, asked at every write.
"""
import os


def repo_root(start):
    """The nearest folder at or above START's folder holding `.git` or `CLAUDE.md`, or None."""
    here = os.path.dirname(os.path.abspath(start))
    while True:
        if os.path.exists(os.path.join(here, ".git")) or os.path.isfile(
                os.path.join(here, "CLAUDE.md")):
            return here
        parent = os.path.dirname(here)
        if parent == here:
            return None
        here = parent


def require(start):
    """`repo_root`, refusing in words when START sits in no project."""
    root = repo_root(start)
    if root is None:
        raise LookupError("%s sits in no project: no folder at or above it holds .git or CLAUDE.md "
                          "- and a root counted in folders would have named one anyway" % start)
    return root
