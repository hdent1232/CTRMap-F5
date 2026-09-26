# -*- coding: utf-8 -*-
"""A REFUSAL THAT CANNOT BE PRINTED IS AN ALLOW.

The fan-out hook's Workflow refusal read

    "... burned ~97M subagent tokens - 96% of a weekly 20x allowance ..." % ALLOW_WORKFLOW

and `% o` - from "96% of" - is a conversion. Every Workflow call raised `TypeError` while
BUILDING the message that refuses it. A hook that exits 1 is a non-blocking error to the
harness, so standing alone it ALLOWED the one tool this project blocks outright, and under the
dispatcher it refused with a traceback instead of the reason. It was introduced by moving an
environment name into a constant, which turned an f-string into a `%` format, in the same
session that wrote the guard it broke; the bundle's copy carried it identically.

WHAT THIS REFUSES, asked of the AST of every Python file git knows here:

    "..." % x          whose conversions and arguments do not agree in number, or whose `%`
                       is not a conversion at all
    "...".format(...)  a field with no argument or keyword, or a brace str.format cannot parse

Only CONSTANT format strings are judged - a format built at run time cannot be read here - and a
right operand that might be a tuple is given the benefit, because a check that fires on honest
`fmt % args` code is one people learn to ignore.

    python -B tools/format_strings.py     0 when every constant format can print, else REFUSING

THE SCOPE IS THE TREE, not a folder list: `git ls-files` for what is tracked and what is new and
not ignored. A git that cannot answer is a refusal - an unreadable tree is not a clean one.
"""
import ast
import io
import os
import re
import string
import subprocess
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

LF = chr(10)

#: What a project may need to change about this file. `check_install.py` compares an installed
#: copy with these blanked. `EXCLUDE` - path prefixes that are not this project's code (vendored
#: trees, generated files), each one a decision a reader can see.
ADAPT = ("EXCLUDE", "GIT_TIMEOUT")
EXCLUDE = ()
GIT_TIMEOUT = 120

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

_SPEC = re.compile(r"%(?:\(([^)]*)\))?[#0\- +]*(?:\*|\d+)?(?:\.(?:\*|\d+))?[hlL]?(.)", re.S)
_VALID = set("diouxXeEfFgGcrsa%")


def root():
    """The repository this file sits in, ANCHORED on `.git` - never counted in `dirname`s,
    because a copy installed one folder deeper would otherwise sweep the wrong tree and say it
    was clean."""
    here = os.path.dirname(os.path.abspath(__file__))
    while True:
        if os.path.exists(os.path.join(here, ".git")):
            return here
        parent = os.path.dirname(here)
        if parent == here:
            return None
        here = parent


def folded(node):
    """The constant string `node` always evaluates to, or None."""
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
        left, right = folded(node.left), folded(node.right)
        if left is not None and right is not None:
            return left + right
    return None


def _conversions(fmt):
    """(positional conversions, uses names?, invalid specs) of a `%` format."""
    count, named, bad = 0, False, []
    for match in _SPEC.finditer(fmt):
        key, conv = match.group(1), match.group(2)
        if match.group(0) == "%%":
            continue
        if conv not in _VALID:
            bad.append(match.group(0))
            continue
        if key is not None:
            named = True
            continue
        count += 1 + match.group(0).count("*")
    return count, named, bad


def _maybe_tuples(tree):
    """Names this module ever binds to something that might be a tuple."""
    names = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Assign):
            value = node.value
            scalar = isinstance(value, (ast.Constant, ast.JoinedStr, ast.List, ast.Dict)) or (
                isinstance(value, ast.Call) and ast.unparse(value.func)
                in ("str", "repr", "os.path.join", "bundle_env.name", "int", "float"))
            if not scalar:
                for target in node.targets:
                    for sub in ast.walk(target):
                        if isinstance(sub, ast.Name):
                            names.add(sub.id)
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda)):
            every = node.args.posonlyargs + node.args.args + node.args.kwonlyargs
            names.update(arg.arg for arg in every)
            if node.args.vararg:
                names.add(node.args.vararg.arg)
        elif isinstance(node, (ast.For, ast.AsyncFor, ast.comprehension, ast.With)):
            targets = [node.target] if not isinstance(node, ast.With) else [
                item.optional_vars for item in node.items if item.optional_vars is not None]
            for target in targets:
                for sub in ast.walk(target):
                    if isinstance(sub, ast.Name):
                        names.add(sub.id)
        elif isinstance(node, (ast.AnnAssign, ast.AugAssign, ast.NamedExpr)):
            target = node.target
            if isinstance(target, ast.Name):
                names.add(target.id)
    return names


def offenders(source, path="<source>"):
    """[(line, why)] for every constant format in `source` that cannot print."""
    tree = ast.parse(source, path)
    maybe_tuple = _maybe_tuples(tree)
    found = []
    for node in ast.walk(tree):
        if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                and node.func.attr == "format" and folded(node.func.value) is not None):
            if any(isinstance(a, ast.Starred) for a in node.args) or any(
                    k.arg is None for k in node.keywords):
                continue
            try:
                fields = [f for _t, f, _s, _c in string.Formatter().parse(folded(node.func.value))
                          if f is not None]
            except ValueError as bad:
                found.append((node.lineno, "str.format cannot parse it: %s" % bad))
                continue
            names = {k.arg for k in node.keywords}
            automatic = 0
            for field in fields:
                head = re.split(r"[.\[]", field, 1)[0]
                if head == "":
                    automatic += 1
                elif head.isdigit() and int(head) >= len(node.args):
                    found.append((node.lineno, "field {%s} and only %d argument(s)"
                                  % (head, len(node.args))))
                elif not head.isdigit() and head not in names:
                    found.append((node.lineno, "field {%s} and no such keyword" % head))
            if automatic > len(node.args):
                found.append((node.lineno, "%d {} field(s) and %d argument(s)"
                              % (automatic, len(node.args))))
            continue
        if not (isinstance(node, ast.BinOp) and isinstance(node.op, ast.Mod)):
            continue
        fmt = folded(node.left)
        if fmt is None:
            continue
        count, named, bad = _conversions(fmt)
        if bad:
            found.append((node.lineno, "%r is not a conversion - a literal percent sign is "
                                       "written %%%%" % bad[0]))
            continue
        right = node.right
        if named or isinstance(right, (ast.Dict, ast.DictComp)):
            continue
        if isinstance(right, ast.Tuple):
            if any(isinstance(e, ast.Starred) for e in right.elts):
                continue
            given = len(right.elts)
        elif isinstance(right, (ast.Constant, ast.JoinedStr, ast.List, ast.Compare)):
            given = 1
        elif isinstance(right, ast.Name) and right.id not in maybe_tuple:
            given = 1
        else:
            if count == 0:
                found.append((node.lineno, "no conversion, and an argument is supplied"))
            continue
        if given != count:
            found.append((node.lineno, "%d conversion(s) and %d argument(s)" % (count, given)))
    return found


#: A project with a WRITE-TIME channel - one that asks each rule about a file's proposed text
#: before the bytes land - finds this by the name, and asks `findings_in` per file. The commit
#: runs `main` over the whole tree as the second layer. A project without such a channel ignores
#: the constant; nothing here depends on it.
AT_WRITE = "findings_in"


def findings_in(rel, source):
    """[findings] for ONE file's proposed source - the same rule `main` asks of the tree."""
    if not str(rel).endswith(".py"):
        return []
    try:
        return ["%d: %s" % (line, why) for line, why in offenders(source, rel)]
    except SyntaxError:
        return []                          # not this rule's question; the compiler asks it


def files(where):
    """Every Python file git knows in `where`, or None when git cannot be asked."""
    try:
        done = subprocess.run(["git", "ls-files", "-co", "--exclude-standard", "--", "*.py"],
                              cwd=where, capture_output=True, text=True,
                              creationflags=NO_WINDOW, timeout=GIT_TIMEOUT)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if done.returncode != 0:
        return None
    return sorted(line.strip() for line in done.stdout.splitlines()
                  if line.strip() and not line.strip().startswith(tuple(EXCLUDE)))


def problems(where=None):
    """Every format that cannot print, as lines - or the reason nothing could be asked."""
    where = where or root()
    if where is None:
        return ["REFUSING: no `.git` above %s, so there is no tree to ask - an unreadable tree "
                "is not a clean one" % os.path.dirname(os.path.abspath(__file__))]
    listed = files(where)
    if listed is None:
        return ["REFUSING: git could not list the files in %s, so which formats exist is "
                "UNKNOWN" % where]
    if not listed:
        return ["REFUSING: git lists NO Python file in %s - that is the wrong tree, not a "
                "clean one" % where]
    out = []
    for rel in listed:
        path = os.path.join(where, rel.replace("/", os.sep))
        try:
            with io.open(path, encoding="utf-8", errors="replace") as handle:
                source = handle.read()
            for line, why in offenders(source, path):
                out.append("%s:%d  %s" % (rel, line, why))
        except SyntaxError:
            continue                       # not this check's question; the suite asks it
        except OSError:
            continue                       # listed and since deleted
    return out


def main(argv):
    found = problems(argv[1] if len(argv) > 1 else None)
    if found:
        print("REFUSING: %d format(s) cannot print - and a refusal that throws while building "
              "its message is an allow:" % len(found))
        for line in found:
            print("   " + line)
        return 1
    print("every constant format string can print")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
