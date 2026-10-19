"""The bundle's tools/cli_words.py for the tools in tools/guard, which live one folder below it.

A WORD A TOOL DOES NOT READ IS REFUSED, NEVER IGNORED. `replant.py --help`, run for its usage on
2026-10-09, read `--help` as no plant named and drove all 1,363 - about 7.5 hours with the tree
unwritable. The bundle answered with cli_words.refused, asked of every tool under tools/ at the write;
on 2026-10-10 twenty of this project's tools in tools/guard, and three in tools/, read their words
and dropped what they did not know. Each now asks `refused` before it acts, stating its own grammar.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
sys.path.insert(0, os.path.join(_tree_root.require(__file__), "tools"))
from cli_words import refused  # noqa: E402,F401  - re-exported: the tools here call _cli.refused
