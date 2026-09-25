# -*- coding: utf-8 -*-
"""EVERY SUBPROCESS A GATE STARTS TAKES A TIMEOUT. Held at ZERO. README section 16.

A GATE THAT CAN BLOCK FOREVER. A verification step ran for thirteen and a half hours on the
source project. Measured against the operating system: the gate had used 0.14 seconds of CPU and
its child 11.8 - blocked in `subprocess.run`, because `capture_output=True` with no timeout reads
to EOF, and EOF never arrives while any grandchild still holds the pipe. From outside, "still
working" and "wedged" are the same picture, and the run held the suite lock the whole time.

The second project to install this bundle measured 28 unbounded calls across the 59 files its
gates run - `git log` in a claim audit, `git ls-files` in two tracked-ness checks, the linter, a
PowerShell probe, the suite runner - and two of them read a FAILED `git` as "nothing changed".

WHAT A GATE IS, DERIVED - a list of gates is the defect one level up:

    every `.py` in every hook folder   asked on every tool call (`bundle_hooks.hook_folders`)
    every script `.githooks/*` runs    and every project module it imports, transitively
    every `.py` under `GATE_DIRS`      tools a commit runner discovers and runs (ADAPT)

A TIMEOUT IS A RESULT. `timeout=` is half the fix. The other half is that a call site treats
`TimeoutExpired` as an ANSWER - a refusal, or a failure with its reason - never as an exception
that escapes, and never as the empty answer that reads as clean. And where the child writes a
file it restores in a `finally` (a mutation harness), stop WAITING - `communicate(timeout=)` -
rather than KILLING: `subprocess.run(timeout=)` kills, and a killed harness leaves its mutant.

    python -B tools/timeouts.py
"""
import ast
import io
import os
import re
import sys

LF = chr(10)


def _root():
    here = os.path.dirname(os.path.abspath(__file__))
    while True:
        if os.path.exists(os.path.join(here, ".git")) or os.path.isdir(
                os.path.join(here, ".githooks")):
            return here
        parent = os.path.dirname(here)
        if parent == here:
            return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        here = parent


ROOT = _root()

if os.path.dirname(os.path.abspath(__file__)) not in sys.path:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_hooks      # noqa: E402  - every folder a hook is asked from, found

#: Folders whose every module a commit runner may execute - so every one is a gate.
GATE_DIRS = ()

#: Folders a gate's imports are resolved in.
IMPORT_DIRS = ("tools",)

#: The floor on how many gate files the derivation must find before its answer is believed.
MIN_GATES = 3

#: Adapted per project: see ADAPT.md.
ADAPT = ("GATE_DIRS", "IMPORT_DIRS", "MIN_GATES")

WAITS = ("run", "check_output", "check_call", "call")
CEILING = 0


def unbounded(source):
    """[line] of every `subprocess.<wait>(...)` and `.communicate(...)` with no `timeout=`."""
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return [0]
    out = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        name = getattr(node.func, "attr", None)
        owner = getattr(getattr(node.func, "value", None), "id", None)
        if not ((name in WAITS and owner == "subprocess") or name == "communicate"):
            continue
        if not any(keyword.arg == "timeout" for keyword in node.keywords):
            out.append(node.lineno)
    return out


def _with_imports(rel, seen):
    if rel in seen:
        return
    seen.add(rel)
    try:
        tree = ast.parse(io.open(os.path.join(ROOT, rel), encoding="utf-8").read())
    except (OSError, SyntaxError):
        return
    names = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            names.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            names.add(node.module.split(".")[0])
    for name in names:
        for folder in IMPORT_DIRS:
            for base, _dirs, files in os.walk(os.path.join(ROOT, folder)):
                if name + ".py" in files:
                    candidate = os.path.relpath(os.path.join(base, name + ".py"), ROOT)
                    _with_imports(candidate.replace(os.sep, "/"), seen)


def gates():
    found = set()
    for folder in bundle_hooks.hook_folders(ROOT):
        hooks = os.path.join(ROOT, *folder.split("/"))
        if os.path.isdir(hooks):
            for name in sorted(os.listdir(hooks)):
                if name.endswith(".py"):
                    _with_imports(folder + "/" + name, found)
    githooks = os.path.join(ROOT, ".githooks")
    if os.path.isdir(githooks):
        for name in sorted(os.listdir(githooks)):
            try:
                text = io.open(os.path.join(githooks, name), encoding="utf-8").read()
            except (OSError, UnicodeDecodeError):
                continue
            for script in re.findall(r"python[0-9.]*\s+(?:-\S+\s+)*(\S+\.py)", text):
                _with_imports(script.strip(chr(34) + "'"), found)
    for folder in GATE_DIRS:
        for base, _dirs, files in os.walk(os.path.join(ROOT, folder)):
            for name in files:
                if name.endswith(".py"):
                    rel = os.path.relpath(os.path.join(base, name), ROOT).replace(os.sep, "/")
                    _with_imports(rel, found)
    return sorted(rel for rel in found if os.path.isfile(os.path.join(ROOT, rel)))


#: ASKED AT THE WRITE of any file a commit runs - `.claude/hooks/guard_write_rules.py` finds this
#: marker. A child started with no timeout in a gate is refused by the edit that types it, not
#: found by the commit it would hang.
AT_WRITE = "findings_in"

_GATES = []


def findings_in(rel, source):
    """[findings] for ONE file's proposed source, when that file is one a commit runs."""
    rel = str(rel).replace(os.sep, "/")
    if not rel.endswith(".py"):
        return []
    if not _GATES:
        _GATES.append(set(gates()))
    if rel not in _GATES[0]:
        return []
    try:
        return ["%d: a child started with no timeout, in a file a commit runs" % line
                for line in unbounded(source)]
    except SyntaxError:
        return []


def offenders():
    out = []
    for rel in gates():
        source = io.open(os.path.join(ROOT, rel), encoding="utf-8").read()
        out.extend((rel, line) for line in unbounded(source))
    return out


def main(argv):
    found = gates()
    if len(found) < MIN_GATES:
        # NOTHING WALKED IS NOT A CLEAN TREE. A derivation that finds almost no gate looked in the
        # wrong place, and a small scope reads exactly like a clean one.
        sys.stdout.write("REFUSING: only %d gate file(s) derived under %s - the walk is broken%s"
                         % (len(found), ROOT, LF))
        return 1
    bad = offenders()
    sys.stdout.write("%d gate file(s); %d unbounded subprocess call(s), ceiling %d%s"
                     % (len(found), len(bad), CEILING, LF))
    if len(bad) > CEILING:
        for rel, line in bad:
            sys.stdout.write("  REFUSING: %s:%d starts a child with no timeout%s" % (rel, line, LF))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
