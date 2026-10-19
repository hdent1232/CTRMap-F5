# -*- coding: utf-8 -*-
"""A CHARACTER SET IS NOT A PREFIX.

`str.strip`, `lstrip` and `rstrip` take a SET of characters, and a word written there reads as the
prefix or suffix it spells. Measured 2026-10-07 in this bundle: the installer normalised every
`--take <file>` with `.lstrip("./")`, so `--take .claude/hooks/dispatch.py` became
`claude/hooks/dispatch.py` - no file the bundle ships - and was refused as stray. Every hook and
git hook the bundle ships lives under a folder whose name starts with a dot, so the remedy the
conflict refusal names was refused for exactly the files it is named for. A search for the shape
found two more: the installer's reading of which tool a git hook runs, where `.claude/...` could
never match (a dependency missed, silently), and the read-claims guard, where `../out/a.py` became
`out/a.py` and a claim about a file outside the tree counted as opened when a namesake was.

WHAT THIS REFUSES, asked of the AST of every Python file git knows here: a constant argument to
`strip`/`lstrip`/`rstrip` (or `str.lstrip(x, ...)`) of two or more characters that spells a
prefix or suffix rather than a set -

    a dot with a path separator     "./"  "../"  ".\\"
    a letter with a separator       "http://"  "src/"
    a letter with a dot             ".py"  ".txt"  "v."
    a letter with an underscore     "test_"  "_test"

A set of one kind - whitespace, punctuation, digits, letters, digits with punctuation such as
"<>&|0123456789" - is what the call is for and passes. Write a prefix as a prefix:
`while s.startswith("./"): s = s[2:]`, or `str.removeprefix` where the Python is 3.9 or newer.

    python -B tools/strip_sets.py     0 when no strip argument spells a word, else REFUSING

THE SCOPE IS THE TREE, not a folder list: `git ls-files` for what is tracked and what is new and
not ignored. A git that cannot answer is a refusal - an unreadable tree is not a clean one.
"""
import ast
import io
import os
import subprocess
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root        # noqa: E402  - the project root, anchored: never counted (F216)
import bounded         # noqa: E402  - a deadline ends the child's whole tree

LF = chr(10)

#: What a project may need to change about this file. `check_install.py` compares an installed
#: copy with these blanked. `EXCLUDE` - path prefixes that are not this project's code (vendored
#: trees, generated files), each one a decision a reader can see.
ADAPT = ("EXCLUDE", "GIT_TIMEOUT")
EXCLUDE = ()
GIT_TIMEOUT = 120

#: The settings that ship OFF, at the value they ship at. A project turns them on; the bundle's
#: own tests turn them off again for their run (`bundle_hooks.settings_off`).
SHIPPED_OFF = {"EXCLUDE": ()}

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

STRIPS = ("strip", "lstrip", "rstrip")
SEPARATORS = "/" + chr(92)


def root():
    """The repository this file sits in, ANCHORED - never counted in `dirname`s, because a copy
    installed one folder deeper would otherwise sweep the wrong tree and say it was clean.
    `_tree_root.repo_root`, the one definition (F216): this walked to `.git` itself, a second walk
    `counted_roots.py` refuses."""
    return _tree_root.repo_root(__file__)


def folded(node):
    """The constant string `node` always evaluates to, or None."""
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
        left, right = folded(node.left), folded(node.right)
        if left is not None and right is not None:
            return left + right
    return None


def spells_a_word(chars):
    """Why `chars`, handed to a strip, reads as a prefix or suffix - or None when it is a set."""
    if len(set(chars)) < 2:
        return None
    alpha = any(c.isalpha() for c in chars)
    dot = "." in chars
    sep = any(c in SEPARATORS for c in chars)
    if dot and sep:
        return "a dot with a path separator"
    if alpha and sep:
        return "a letter with a path separator"
    if alpha and dot:
        return "a letter with a dot"
    if alpha and "_" in chars:
        return "a letter with an underscore"
    return None


def eats_a_dotfile(chars):
    """Why `chars`, stripped from the LEFT, takes the dot a dotfile's name begins with - or None.

    A SET THAT CLEANS PUNCTUATION TAKES A NAME'S FIRST DOT TOO. Measured 2026-10-10 by the DF
    Engine worktree session: the held tree's reader of what a finding names stripped every word
    with `.strip(".:-")` - a set, which `spells_a_word` passes and this file's own tests listed as
    honest - so `.claude\\bundle-install.json` became `claude/bundle-install.json`, no file, and the
    Edit the finding named as its repair was refused. Every file the bundle ships to a project's
    `.claude/` or `.githooks/` reads the same way. A dot ALONE (`ext.lstrip(".")`) says it means
    the dot and passes; a dot among other punctuation is a cleaning that eats a name. Strip the
    end with `rstrip`, and the left only of what cannot begin a name."""
    if "." in chars and len(set(chars)) > 1:
        return ("a dot among punctuation stripped from the left: `.claude/x` loses the dot its "
                "name begins with - `rstrip` the end, and `lstrip` only what is not a dot")
    return None


def offenders(source, path="<source>"):
    """[(line, why)] for every strip in `source` whose constant argument spells a word."""
    tree = ast.parse(source, path)
    found = []
    for node in ast.walk(tree):
        if not (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                and node.func.attr in STRIPS and not node.keywords):
            continue
        args = node.args
        if (isinstance(node.func.value, ast.Name) and node.func.value.id == "str"
                and len(args) == 2):
            args = args[1:]                         # str.lstrip(text, chars)
        if len(args) != 1:
            continue
        chars = folded(args[0])
        if chars is None:
            continue
        why = spells_a_word(chars) or (node.func.attr != "rstrip" and eats_a_dotfile(chars))
        if why:
            found.append((node.lineno, ".%s(%r) strips any of those characters, not the %s %r - "
                                       "%s" % (node.func.attr, chars,
                                               "suffix" if node.func.attr == "rstrip"
                                               else "prefix", chars, why)))
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
        done = bounded.run(["git", "ls-files", "-co", "--exclude-standard", "--", "*.py"],
                           cwd=where, capture_output=True, text=True,
                           creationflags=NO_WINDOW, timeout=GIT_TIMEOUT)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if done.returncode != 0:
        return None
    return sorted(line.strip() for line in done.stdout.splitlines()
                  if line.strip() and not line.strip().startswith(tuple(EXCLUDE)))


def problems(where=None):
    """Every strip that spells a word, as lines - or the reason nothing could be asked."""
    where = where or root()
    if where is None:
        return ["REFUSING: no `.git` above %s, so there is no tree to ask - an unreadable tree "
                "is not a clean one" % os.path.dirname(os.path.abspath(__file__))]
    listed = files(where)
    if listed is None:
        return ["REFUSING: git could not list the files in %s, so which strips exist is "
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
    import cli_words      # a word this does not read refuses the run, before anything runs
    why = cli_words.refused(argv[1:], most=1)
    if why:
        print(why)
        return 2
    found = problems(argv[1] if len(argv) > 1 else None)
    if found:
        print("REFUSING: %d strip(s) spell a word - a strip takes a SET of characters, and "
              "`.lstrip(\"./\")` took `.claude/x` to `claude/x`. Test `startswith` and slice:"
              % len(found))
        for line in found:
            print("   " + line)
        return 1
    print("no strip argument spells a prefix or suffix")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
