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
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

LF = chr(10)


sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root        # noqa: E402  - the project root, anchored: never counted (F216)

ROOT = _tree_root.require(__file__)

if os.path.dirname(os.path.abspath(__file__)) not in sys.path:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_hooks      # noqa: E402  - every folder a hook is asked from, found

#: Folders whose every module a commit runner may execute - so every one is a gate.
GATE_DIRS = ()

#: Folders a gate's imports are resolved in.
IMPORT_DIRS = ("tools",)

#: The floor on how many gate files the derivation must find before its answer is believed.
MIN_GATES = 3

#: Adapted per project: see the bootstrap's ADAPT.md.
ADAPT = ("GATE_DIRS", "IMPORT_DIRS", "MIN_GATES")

#: The settings that ship OFF, at the value they ship at. A project turns them on; the bundle's
#: own tests turn them off again for their run (`bundle_hooks.settings_off`).
SHIPPED_OFF = {"GATE_DIRS": ()}

WAITS = ("run", "check_output", "check_call", "call")
CEILING = 0

#: THE ONE IMPLEMENTATION of a deadline that ends a child's whole tree (tools/bounded.py, and its copies
#: beside the hooks and the tests). `bounded.run` is bounded the way subprocess's waits are, and is the
#: only way a deadline may be handed to a child's wait.
BOUNDED = "bounded"


def kills_only_the_child(source):
    """[line] of every one of subprocess's own waits that SOURCE hands a deadline.

    A DEADLINE SUBPROCESS TAKES ENDS ONLY THE CHILD IT STARTED. PAID FOR 2026-10-09 on the project this
    bundle was installed into first: a test handed `bash -c` a 120-second deadline; bash died on it, and the
    python launcher bash had started and the hook that launcher ran were still alive seven hours later, the
    hook blocked reading a stdin nobody would close. That project then counted 98 such calls in 37 files, all
    bounded as `unbounded` asks and none ending what its child had started. `bounded.run` is subprocess.run
    with the tree ended on the deadline - moving a call is changing its name. Asked by shape, every alias and
    from-import resolved as `unbounded` resolves them; `Popen(...).communicate(timeout=)` - stopping the WAIT,
    which a harness that restores a file in its own `finally` needs - is not one of subprocess's waits."""
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return [0]
    modules, waits = {"subprocess"}, set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                if alias.name == "subprocess":
                    modules.add(alias.asname or alias.name)
        elif isinstance(node, ast.ImportFrom) and node.module == "subprocess":
            for alias in node.names:
                if alias.name in WAITS:
                    waits.add(alias.asname or alias.name)
    out = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call) or not any(k.arg == "timeout" for k in node.keywords):
            continue
        attr = getattr(node.func, "attr", None)
        owner = getattr(getattr(node.func, "value", None), "id", None)
        bare = getattr(node.func, "id", None)
        if (attr in WAITS and owner in modules) or bare in waits:
            out.append(node.lineno)
    return sorted(set(out))


def tree_offenders():
    """[(rel, line)] of every deadline handed to one of subprocess's waits, in EVERY Python file the tree
    holds - a gate or not, because the seven-hour survivors came out of a test - or None when the tree
    cannot be listed: a tree nobody could list is not a clean one. bounded.py itself is asked like any
    file: it stops its waits with `Popen(...).communicate(timeout=)`, which is not one of subprocess's."""
    import counted_roots
    files = counted_roots.python_files(ROOT)
    if files is None:
        return None
    out = []
    for rel in files:
        with io.open(os.path.join(ROOT, rel), encoding="utf-8", errors="replace") as handle:
            out.extend((rel, line) for line in kills_only_the_child(handle.read()))
    return out


def unbounded(source):
    """[line] of every call in SOURCE that starts a child and waits with no bound.

    ASKED BY SHAPE, NOT BY THE NAME `subprocess` (F199): an alias (`import subprocess as sp`), a
    from-import (`from subprocess import run`), `Popen(...).wait()`, `os.system` and `os.popen`
    all blocked forever and passed a check that looked for the word. Every name this source binds
    to subprocess, to one of its waits, or to os.system/os.popen is resolved first. Flagged: a wait
    with no `timeout=`; `.communicate()` with none; `.wait()` given no bound at all; and every
    os.system / os.popen, which cannot take one - start the child through subprocess instead.
    """
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return [0]
    modules, oses, waits, shells, bounded_mods = {"subprocess"}, {"os"}, set(), set(), {BOUNDED}
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                if alias.name == "subprocess":
                    modules.add(alias.asname or alias.name)
                elif alias.name == "os":
                    oses.add(alias.asname or alias.name)
                elif alias.name == BOUNDED:
                    bounded_mods.add(alias.asname or alias.name)
        elif isinstance(node, ast.ImportFrom) and node.module in ("subprocess", "os"):
            for alias in node.names:
                if node.module == "subprocess" and alias.name in WAITS:
                    waits.add(alias.asname or alias.name)
                elif node.module == "os" and alias.name in ("system", "popen"):
                    shells.add(alias.asname or alias.name)
    out = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        attr = getattr(node.func, "attr", None)
        owner = getattr(getattr(node.func, "value", None), "id", None)
        bare = getattr(node.func, "id", None)
        timed = any(keyword.arg == "timeout" for keyword in node.keywords)
        if (attr in WAITS and owner in modules) or bare in waits or attr == "communicate" \
                or (attr == "run" and owner in bounded_mods):
            if not timed:
                out.append(node.lineno)
        elif attr == "wait":
            if not timed and not node.args:
                out.append(node.lineno)
        elif (attr in ("system", "popen") and owner in oses) or bare in shells:
            out.append(node.lineno)
    return sorted(set(out))


def _with_imports(rel, seen):
    if rel in seen:
        return
    seen.add(rel)
    try:
        with io.open(os.path.join(ROOT, rel), encoding="utf-8") as handle:
            tree = ast.parse(handle.read())
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
                with io.open(os.path.join(githooks, name), encoding="utf-8") as handle:
                    text = handle.read()
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
    """[findings] for ONE file's proposed source: in any Python file, a deadline that ends only the child;
    in a file a commit runs, a child started with no deadline at all."""
    rel = str(rel).replace(os.sep, "/")
    if not rel.endswith(".py"):
        return []
    out = []
    for line in kills_only_the_child(source):
        out.append("0: the file does not parse, so whether it hands subprocess a deadline is UNKNOWN"
                   if line == 0 else
                   "%d: a deadline handed to subprocess ends only the child it started - its children run"
                   " on; call bounded.run, which ends the whole tree" % line)
    if not _GATES:
        _GATES.append(set(gates()))
    if rel not in _GATES[0]:
        return out
    try:
        return out + ["%d: a child started with no timeout, in a file a commit runs" % line
                      for line in unbounded(source)]
    except SyntaxError:
        return out


def offenders():
    out = []
    for rel in gates():
        with io.open(os.path.join(ROOT, rel), encoding="utf-8") as handle:
            source = handle.read()
        out.extend((rel, line) for line in unbounded(source))
    return out


def main(argv):
    import cli_words      # a word this does not read refuses the run, before anything runs
    why = cli_words.refused(argv[1:], most=0)
    if why:
        print(why)
        return 2
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
    tree = tree_offenders()
    if tree is None:
        sys.stdout.write("REFUSING: the tree could not be listed, so whether a deadline ends only a child is UNKNOWN%s"
                         % LF)
        return 1
    sys.stdout.write("%d deadline(s) handed to subprocess that end only the child, ceiling %d%s"
                     % (len(tree), CEILING, LF))
    if len(bad) > CEILING or len(tree) > CEILING:
        for rel, line in bad:
            sys.stdout.write("  REFUSING: %s:%d starts a child with no timeout%s" % (rel, line, LF))
        for rel, line in tree:
            sys.stdout.write("  REFUSING: %s:%d hands subprocess a deadline that ends only the child - call"
                             " bounded.run%s" % (rel, line, LF))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
