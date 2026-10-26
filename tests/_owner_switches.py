# -*- coding: utf-8 -*-
"""THE OWNER'S SETTINGS ARE TAKEN OUT OF EVERY TEST PROCESS. Every `tests/test_*.py` imports this
before anything that is not the standard library, and `test_bundle` refuses one that does not.

WHY. A hook's test asks what the HOOK does. Handed the owner's environment, it asks what the hook
does under the owner's settings - and the owner sets them in the very sessions that run the suites.
Measured 2026-10-23 in a project with this bundle installed: the owner's way past a held tree
(`<PREFIX>_ALLOW_HELD_CHANGES=1`), set for a repair, was in the session, and the battery's Python
suite failed 24 tests in test_bundle_action, test_held_messages and test_bundle - every one
expecting a held tree to refuse, every hook they ran inheriting the switch. The same three files
with it unset: OK. In this folder, the same three with the switch: 23 failed.

WHY HERE, AND AT IMPORT. The tests run hooks in-process, as children with `env=dict(os.environ,
...)`, and as children with the environment untouched - there is no one call site to clean, and a
guard at call sites is one call site from broken. What every one of them shares is the process's
own `os.environ`, read when the hook runs; and a hook may read a setting when it is IMPORTED
(`guard_fanout.AGENT_CAP`), so this runs before any of them is. A test file run on its own - a
plant drives exactly one test - imports this itself, which is why every file does and not only
the first one discovery happens to load.

WHAT STAYS. The names the bundle's own tools hand the processes they start (`bundle_env.INTERNAL`):
the plant driver tells a plant's own test which file is planted. A test that wants an owner's
setting sets it itself, on top of a process that holds none.
"""
import importlib.util
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)

_ROOT = _tree_root.require(__file__)
sys.path.insert(0, os.path.join(_ROOT, "tools"))
import bundle_hooks  # noqa: E402  - where this project keeps the bundle's hooks

#: What this process held, by name, before it was taken out - for a test that needs to say so.
TAKEN = {}

#: WHY THE OWNER'S SETTINGS COULD NOT BE TAKEN OUT, or None. RECORDED, NOT RAISED (2026-10-24): raised here,
#: at import, a hooks folder the search did not find killed every test module before any test in it could say
#: what was wrong. Measured on CTRMap, which keeps the bundle's hooks in .claude/bundle-hooks: the plant that
#: breaks the folder search made test_bundle_gates' own check of that search crash at import, never red - the
#: guard could not see the defect it was written for. test_bundle's EveryTestProcessStartsWithoutTheOwnersSettings
#: fails on this, saying it; every other test still runs and says what it sees.
UNREAD = None

#: LOADED UNDER A NAME OF ITS OWN, so nothing a test later imports as `bundle_env` - from this
#: project's hooks or from a scratch tree's - is decided by this import.
try:
    _SPEC = importlib.util.spec_from_file_location(
        "_owner_switches_env", os.path.join(bundle_hooks.path(_ROOT), "bundle_env.py"))
    _ENV = importlib.util.module_from_spec(_SPEC)
    _SPEC.loader.exec_module(_ENV)
    TAKEN = {key: os.environ.pop(key) for key in _ENV.owner_settings(os.environ)}
except (OSError, ImportError, ValueError) as _cannot:
    UNREAD = "the owner's settings could not be taken out: %s: %s" % (type(_cannot).__name__, _cannot)
