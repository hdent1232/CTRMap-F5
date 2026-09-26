#!/usr/bin/env python3
"""A FILE OPENED IS A FILE CLOSED BY THE CODE THAT OPENED IT - never left to the collector.

MEASURED, 2026-09-26. The rule DT Engine adopted the same day (`tools/audit/unclosed.py` there),
pointed at this tree, found 70 opens whose handle nothing held, in 23 files under `tools/` and
`.claude/` - fourteen in `tools/mutate2.py`, nine in `tools/guard/replant.py`, one in each copy of
`guard_all.py`. On Windows an open handle is not a style point: the next write or delete of that
path fails with errno 22 or a sharing violation, and that is the failure a planting write, a
restore and a lock release have each paid for on the project the rule came from. A handle the
collector closes "soon" is closed at a moment nothing chose.

All 70 are held now: 69 hoisted into a `with` around their statement by a script, proven exact by
folding every single-use `with` back and comparing syntax trees, and one by hand
(`rule_map.summary`, under a conditional expression). The hook copies outside this repository,
`../.claude/hooks`, were hoisted in the same step, because `guard_all` refuses every call while
they differ from `tools/hooks`.

WHAT IS REFUSED - an open whose handle is used INLINE:

    json.load(io.open(p))          passed straight to a call
    io.open(p, "rb").read()        an attribute of the call
    for line in open(p): ...       iterated
    io.open(p, "w").write(t)       and every other use but the three below

WHAT IS A HELD HANDLE, and allowed: the context manager of a `with`; a value RETURNED (a factory,
whose caller holds it); and a value assigned to a name THAT THE SAME SCOPE CLOSES - `.close()` on
it, `with` on it, or returning it. The last condition is the cheapest way past the rule written
without it: assign, then read, then never close. It refuses that too.

The opens asked about: builtin `open`, `io.open`, `codecs.open`, `tokenize.open`, `gzip.open`,
`bz2.open`, `lzma.open`. `os.open` is a descriptor, not a file object, and is not one.

    python tools/guard/unclosed.py      0 when every open under tools/ and .claude/ is held

Asked at the write (`AT_WRITE`, discovered by `.claude/bundle-hooks/bundle_rules.py` under
`RULE_DIRS`), and a ceiling of 0 held by `ctrmap.tests.RuleRefusalsTest`, which also drives the
rule both ways in a scratch tree.
"""
import ast
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

#: The folders whose code this rule reads.
ROOTS = ("tools", ".claude")

#: Folders under them that are not this tree's code: caches, and whole checkouts.
SKIP = ("__pycache__", ".git", "rules-cache", "worktrees")

#: What opens a FILE OBJECT.
OPENERS = frozenset({"open", "io.open", "codecs.open", "tokenize.open", "gzip.open", "bz2.open",
                     "lzma.open"})

SCOPES = (ast.FunctionDef, ast.AsyncFunctionDef, ast.Module)


def _parents(tree):
    up = {}
    for node in ast.walk(tree):
        for child in ast.iter_child_nodes(node):
            up[child] = node
    return up


def _scope(node, up):
    while not isinstance(node, SCOPES) and node in up:
        node = up[node]
    return node


def _handed_on(value, name):
    """Is `name` returned bare, or handed to a new object that is returned - `return
    TextIOWrapper(reader(raw))` - rather than consumed on the way out? The returned call must
    CONSTRUCT something (a CapWords name) and the handle may not be the receiver of a method on
    the way: `return json.load(h)` is the cheapest way past a rule that accepted any mention in
    a return."""
    if isinstance(value, ast.Name):
        return value.id == name
    if not isinstance(value, ast.Call):
        return False
    func = value.func.attr if isinstance(value.func, ast.Attribute) else getattr(value.func, "id", "")
    if not func[:1].isupper():
        return False
    uses = [n for n in ast.walk(value) if isinstance(n, ast.Name) and n.id == name]
    receivers = {id(n.value) for n in ast.walk(value) if isinstance(n, ast.Attribute)}
    return bool(uses) and not any(id(n) in receivers for n in uses)


def _closed_in(scope, name):
    """Does this scope close `name` - `.close()` on it, a `with` on it, or return it, bare or
    handed on to a new object it returns?"""
    for node in ast.walk(scope):
        if isinstance(node, ast.Return) and node.value is not None \
                and _handed_on(node.value, name):
            return True
        if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                and node.func.attr == "close" and isinstance(node.func.value, ast.Name)
                and node.func.value.id == name):
            return True
        if isinstance(node, ast.withitem) and isinstance(node.context_expr, ast.Name) \
                and node.context_expr.id == name:
            return True
    return False


def unclosed(tree):
    """[(line, code, why)] for every open in this tree whose handle is not held."""
    up = _parents(tree)
    out = []
    for call in ast.walk(tree):
        if not (isinstance(call, ast.Call) and ast.unparse(call.func) in OPENERS):
            continue
        parent = up.get(call)
        if isinstance(parent, ast.withitem) and parent.context_expr is call:
            continue
        if isinstance(parent, ast.Return) and parent.value is call:
            continue
        if isinstance(parent, (ast.Assign, ast.AnnAssign)) and parent.value is call:
            targets = parent.targets if isinstance(parent, ast.Assign) else [parent.target]
            names = [t.id for t in targets if isinstance(t, ast.Name)]
            if names and all(_closed_in(_scope(parent, up), name) for name in names):
                continue
            out.append((call.lineno, ast.unparse(call)[:90],
                        "is assigned to a name nothing in its scope closes"))
            continue
        out.append((call.lineno, ast.unparse(call)[:90], "is used inline, never held"))
    return sorted(out)


def _in_scope(rel):
    rel = str(rel).replace(os.sep, "/")
    return rel.endswith(".py") and rel.split("/", 1)[0] in ROOTS \
        and not any(part in SKIP for part in rel.split("/")[:-1])


def _trees(roots=ROOTS, root=ROOT):
    out = {}
    for folder in roots:
        for dirpath, dirnames, filenames in os.walk(os.path.join(root, folder)):
            dirnames[:] = [d for d in dirnames if d not in SKIP]
            for name in sorted(filenames):
                if not name.endswith(".py"):
                    continue
                path = os.path.join(dirpath, name)
                with io.open(path, encoding="utf-8", errors="replace") as handle:
                    source = handle.read()
                try:
                    out[os.path.relpath(path, root).replace(os.sep, "/")] = ast.parse(source)
                except SyntaxError:
                    continue
    return out


#: ASKED AT THE WRITE - the bundle's hooks discover a checker by this name and ask it about a
#: file's proposed text before the bytes land.
AT_WRITE = "findings_in"
AT_SCOPE = r"^(tools|\.claude)/.*\.py$"


def findings_in(rel, source):
    """[findings] for ONE file's proposed source."""
    if not _in_scope(rel):
        return []
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return []
    return ["%d: %s %s - on Windows the next write or delete of that path can fail while the "
            "collector still holds it; open it in a `with`" % (line, code, why)
            for line, code, why in unclosed(tree)]


def offenders(trees=None):
    trees = _trees() if trees is None else trees
    return [(rel, line, code, why) for rel, tree in sorted(trees.items())
            for line, code, why in unclosed(tree)]


def main(argv):
    root = os.path.abspath(argv[1]) if len(argv) > 1 else ROOT
    trees = _trees(root=root)
    if not trees:
        print("REFUSING: found no Python under %s - that is the wrong tree, not a clean one"
              % ", ".join(ROOTS))
        return 1
    found = offenders(trees)
    if found:
        print("REFUSING: %d open(s) whose handle nothing holds:" % len(found))
        for rel, line, code, why in found:
            print("   %s:%d  %s %s" % (rel, line, code, why))
        print("Open a file in a `with`, or close the name it is assigned to in the same scope.")
        return 1
    print("%d file(s) walked; every open under %s is held and closed by the code that opened it"
          % (len(trees), ", ".join(ROOTS)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
