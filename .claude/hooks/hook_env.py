# -*- coding: utf-8 -*-
"""THE ONE PLACE THIS PROJECT'S ENVIRONMENT VARIABLES ARE SPELLED.

WHY IT EXISTS, measured on 2026-09-21. The hooks here carried TWO project prefixes at once:
three caps and seven overrides spelled `CTRMAP_`, and four overrides spelled `DTENGINE_` -
naming the project these hooks were carried from, not this one. Setting `CTRMAP_ALLOW_PIPE`
did nothing, and looked exactly like a bypass that does not work.

The direction mattered and was the safe one: an unset override reads as "not permitted", so a
dead name leaves the guard IN FORCE. A dead CAP name is the dangerous mirror - it falls to its
default, so `int(os.environ.get(<dead name>, "10"))` silently uses 10 while somebody believes
they set 4, which is how 791 agents went through a cap of 6 in the project this came from.

ONE RULE WITH TWO SPELLINGS IS TWO RULES. Change PREFIX once, here, and every name moves;
`hooks_wired.py` REFUSES a hook that spells one itself, so the second spelling cannot come back
quietly - the only kind of fix that holds.

A hook imports this by sitting beside it: a script run as `python .claude/hooks/guard_x.py` has
its own directory first on `sys.path`.
"""
import os

#: CHANGE THIS, ONCE, AND NOTHING ELSE. Uppercase, no trailing underscore.
PREFIX = "CTRMAP"

#: Variables this project does NOT own and must not rename. `CLAUDE_PROJECT_DIR` is set by the
#: agent harness; prefixing it would read as unset and send a hook looking in the wrong tree.
FOREIGN = ("CLAUDE_PROJECT_DIR",)


def name(suffix):
    """The full environment variable name for `suffix`, e.g. "ALLOW_PIPE"."""
    return "%s_%s" % (PREFIX, suffix)


def allowed(suffix):
    """True only when the owner explicitly set this override to 1.

    AN UNSET VARIABLE IS NOT PERMISSION. Anything other than exactly "1" reads as no, so a
    typo, an empty string or a stale `0` all leave the guard in force.
    """
    return os.environ.get(name(suffix)) == "1"


def cap(suffix, default):
    """An integer cap from the environment, or `default`, saying so when it cannot be read.

    AN UNREADABLE CAP IS NOT AN ABSENT ONE. `int(os.environ.get(...))` raises on "six" and
    takes the hook down with it, and a guard that crashes is a guard that gets uninstalled.
    """
    raw = os.environ.get(name(suffix))
    if raw is None:
        return default
    try:
        return int(raw)
    except ValueError:
        import sys
        sys.stderr.write("%s=%r is not a number; using %d\n" % (name(suffix), raw, default))
        return default
