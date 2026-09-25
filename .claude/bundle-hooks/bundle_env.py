# -*- coding: utf-8 -*-
"""THE ONE PLACE THIS BUNDLE'S ENVIRONMENT VARIABLES ARE SPELLED.

WHY IT EXISTS. The hooks shipped with TWO different project prefixes at once: three caps
in one file carried one, and four session overrides across four other files carried another.
Both named a project this bundle was extracted from, and neither named yours.

That is not only untidy, it is the failure this bundle is about. A person adapting it finds and
changes one family, leaves the other, and now has overrides that look set and are not - a
variable nobody exported reads as "not permitted", which is the SAFE direction for a bypass and
the WRONG one for a cap: an `int(os.environ.get(<dead name>, "10"))` silently uses 10 while
you believe you set 4. ONE RULE WITH TWO SPELLINGS IS TWO RULES, and this project has paid for
that more than once - a date check existed twenty-five times as five behaviours.

HOW TO ADAPT IT. Change `PREFIX` on the next line, once. Nothing else in the bundle spells an
environment name, and `check_bundle.py` REFUSES a hook that reintroduces one - so the second
spelling cannot come back quietly, which is the only kind of fix that holds here.

A hook imports this by sitting beside it: a script run as `python .claude/hooks/guard_x.py` has
its own directory first on `sys.path`. That is deliberately the ONLY thing a hook imports.
Reaching into the project would put a second copy of some rule inside a guard, which is exactly
what the hooks avoid by reading nothing but the command string; a sibling that travels in the
same folder is not that - copy one hook without it and you get an ImportError, which is loud,
rather than a variable name that silently never matches.
"""
import os

#: CHANGE THIS, ONCE, AND NOTHING ELSE. Uppercase, no trailing underscore.
PREFIX = "VERIFY"

#: THE CONSTANTS AN INSTALLATION IS MEANT TO CHANGE, named by the file that owns them.
#: `check_install.py` compares an installed copy against this one with exactly these values
#: blanked, so a project that set its prefix reads as CURRENT and a project that changed
#: anything else reads as DRIFTED. Before this, "installed" was a byte comparison or nothing,
#: and a copy adapted exactly as ADAPT.md asks was indistinguishable from one that had rotted.
ADAPT = ("PREFIX",)

#: Variables this bundle does NOT own and must not rename. `CLAUDE_PROJECT_DIR` is set by the
#: agent harness; prefixing it would read as unset and send a hook looking in the wrong tree.
#: `CLAUDE_CODE_SESSION_ID` is the harness naming the session a command runs in, which is how the
#: request ledger's verbs find the transcript they account for.
FOREIGN = ("CLAUDE_PROJECT_DIR", "CLAUDE_CODE_SESSION_ID")


def name(suffix):
    """The full environment variable name for `suffix`, e.g. "ALLOW_PIPE"."""
    return "%s_%s" % (PREFIX, suffix)


def allowed(suffix):
    """True only when the owner explicitly set this override to 1.

    AN UNSET VARIABLE IS NOT PERMISSION. Anything other than exactly "1" reads as no, so a
    typo, an empty string or a stale `0` all leave the guard in force. A bypass that a
    half-remembered value can open is not a bypass, it is a hole.
    """
    return os.environ.get(name(suffix)) == "1"


def dead_prefixes():
    """Every prefix a hook must never spell, the live one included.

    `bundle_env` is the ONE file that spells a prefix. A hook that writes `<prefix>_ALLOW_X` in
    a message or a constant has a second spelling, and a second spelling is a second rule - so
    the live prefix is on this list for every file except this one.
    """
    return tuple(sorted({"CTRMAP_", "DTENGINE_", "VERIFY_", PREFIX + "_"}))


def hardcoded_reads(source):
    """[(line, name)] for every environment read in `source` whose name is SPELLED, not built.

    THE ONE IMPLEMENTATION of "no hook reads a hardcoded environment name", asked by
    `check_bundle.py` and by the installable suite alike - two copies of a checker drift like two
    copies of anything else. It resolves a module-level constant to the string it holds, because
    the first version asked only whether the argument was a string LITERAL and five hooks passed
    it by writing `BYPASS = "<PREFIX>_ALLOW_..."` at the top and `os.environ.get(BYPASS)` below.
    `os.environ.get`, `os.getenv` and `os.environ[...]` are all reads.
    """
    import ast
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return [(0, "<does not parse>")]
    constants = {}
    for node in tree.body:
        if isinstance(node, ast.Assign) and isinstance(node.value, ast.Constant) \
                and isinstance(node.value.value, str):
            for target in node.targets:
                if isinstance(target, ast.Name):
                    constants[target.id] = node.value.value
    found = []
    for node in ast.walk(tree):
        key = None
        if isinstance(node, ast.Call) and node.args:
            func = node.func
            if (getattr(func, "attr", None) == "get"
                    and getattr(getattr(func, "value", None), "attr", None) == "environ") \
                    or getattr(func, "attr", None) == "getenv":
                key = node.args[0]
        elif isinstance(node, ast.Subscript) and getattr(node.value, "attr", None) == "environ":
            key = node.slice
        if isinstance(key, ast.Name):
            value = constants.get(key.id)
        elif isinstance(key, ast.Constant) and isinstance(key.value, str):
            value = key.value
        else:
            value = None
        if value is not None and value not in FOREIGN:
            found.append((node.lineno, value))
    return found


def cap(suffix, default):
    """An integer cap from the environment, or `default`.

    AN UNREADABLE CAP IS NOT AN ABSENT ONE. `int(os.environ.get(...))` raises on a value like
    `"six"` and takes the hook down with it - and a guard that crashes is a guard that gets
    uninstalled. A value that cannot be read REFUSES by falling to the default and saying so on
    stderr, because silently using 10 while somebody believes they set 4 is how 791 agents went
    through a cap of 6.
    """
    raw = os.environ.get(name(suffix))
    if raw is None:
        return default
    try:
        return int(raw)
    except (TypeError, ValueError):
        import sys
        sys.stderr.write(
            "%s=%r is not a number, so the cap of %d is being used instead. An unmeasurable "
            "quantity is not a small one.\n" % (name(suffix), raw, default))
        return default
