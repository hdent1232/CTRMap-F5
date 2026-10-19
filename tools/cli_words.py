#!/usr/bin/env python3
"""A WORD A TOOL DOES NOT READ IS REFUSED, NEVER IGNORED.

PAID FOR, 2026-10-09, on the project this bundle was installed into first: `replant.py --help`, run
for its usage, named no plant - the driver set every word starting with '-' aside before choosing -
and no plant named meant every plant: all 1,363, over an uncommitted tree, about 7.5 hours with the
tree unwritable, stopped only on the owner's word. Looked for the same day, the shape was wherever a
tool read its own command line: this bundle's prove_plants.py drove every plant for `-h` or for any
bare word; `sweep.py run --modle x` swept every module; `render_readme.py --chek` wrote the page it
was asked to check; a tool that takes a folder took a flag for one, read nothing there, and reported
a clean tree. Each read the words it knew and dropped the rest, and what a tool does with no word is
its default - usually the expensive one.

So a tool hands its words to `refused` before it acts on any of them, stating its own grammar, and
stops on the reason it answers. A tool under `tools/` that reads `sys.argv` and does not - that never
calls `refused`, or calls it and goes on whatever it says - is refused at the write (AT_WRITE). The
rule asks the SHAPE, this file reads its command line, never a list of tools, so a tool written
tomorrow is asked too.

    refused(words, flags=(), valued=(), rest=(), prefixed=(), most=0)
        words     the arguments after the program, argv[1:]
        flags     every flag the tool reads
        valued    of those, the ones whose value is the next word
        rest      of those, the ones after which every word is free text - a reason, a list of ids
        prefixed  flags given as `--name=value`, named by their `--name=`
        most      how many bare words the tool reads: a number, or None for any number

    python -B tools/cli_words.py [root]     every tool under root/tools that reads its words unrefused
"""
import ast
import io
import os
import sys

AT_WRITE = "findings"
AT_SCOPE = r"^tools/.*\.py$"

FINDING = ("reads its command line and does not stop on cli_words.refused, so a word it does not read "
           "is dropped and its default runs")


def refused(words, flags=(), valued=(), rest=(), prefixed=(), most=0):
    """Why these words cannot be run, or None. Every word must be a flag the tool reads, the value of
    one, free text after a `rest` flag, or one of at most `most` bare words."""
    words = list(words)
    unknown, bare, at = [], [], 0
    while at < len(words):
        word = words[at]
        if word in rest:
            break
        if word.startswith("-") and word != "-":
            if any(word.startswith(p) and len(word) > len(p) for p in prefixed):
                pass
            elif word not in flags:
                unknown.append(word)
            elif word in valued:
                # given last, or with a flag in its place: `--merge-from --only x` took a flag for a folder (F197)
                if at + 1 >= len(words) or words[at + 1] in flags or words[at + 1].startswith("--"):
                    return "REFUSING: %s takes a value, and none was given - nothing was run" % word
                at += 1
        else:
            bare.append(word)
        at += 1
    if unknown:
        known = list(flags) + ["%s<value>" % p for p in prefixed]
        return ("REFUSING: %s is not a flag this reads (%s) - a word it ignored would run its default,"
                " so nothing was run" % (", ".join(unknown), ", ".join(known) or "it reads none"))
    if most is not None and len(bare) > most:
        return ("REFUSING: %s is a word this does not read - it reads %s - so nothing was run"
                % (", ".join(bare[most:]), "no bare word" if most == 0 else "at most %d bare word(s)" % most))
    return None


def _calls_refused(node):
    return isinstance(node, ast.Call) and (getattr(node.func, "attr", None) == "refused"
                                           or getattr(node.func, "id", None) == "refused")


def _stops(body):
    """Does this block end the run - a return, a raise, or a sys.exit?"""
    for node in ast.walk(ast.Module(body=list(body), type_ignores=[])):
        if isinstance(node, (ast.Return, ast.Raise)):
            return True
        if isinstance(node, ast.Call) and getattr(node.func, "attr", None) == "exit":
            return True
    return False


def stops_on_refusal(tree):
    """Is refused's answer ACTED ON: tested by an `if` that returns, raises or exits - directly, or
    through the name it was assigned to? Calling it and going on regardless is the cheapest way past
    this rule, and is no refusal."""
    names = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.Assign, ast.AnnAssign)) and node.value is not None and _calls_refused(node.value):
            targets = node.targets if isinstance(node, ast.Assign) else [node.target]
            names |= {t.id for t in targets if isinstance(t, ast.Name)}
    for node in ast.walk(tree):
        if not isinstance(node, ast.If) or not _stops(node.body):
            continue
        for part in ast.walk(node.test):
            if _calls_refused(part) or (isinstance(part, ast.Name) and part.id in names):
                return True
    return False


def reads_its_words(tree):
    """Does this module read sys.argv - its own command line?"""
    return any(isinstance(node, ast.Attribute) and node.attr == "argv"
               and isinstance(node.value, ast.Name) and node.value.id == "sys" for node in ast.walk(tree))


def findings(rel, text):
    """[finding] when this tool reads its command line and does not stop on a word it does not read."""
    try:
        tree = ast.parse(text or "")
    except SyntaxError:
        return []                      # a file that does not parse is another rule's finding
    if os.path.basename(rel) == "cli_words.py" or not reads_its_words(tree) or stops_on_refusal(tree):
        return []
    return ["%s %s" % (rel, FINDING)]


def problems(root):
    """Every tool under root/tools that reads its words and does not stop on refused - walked."""
    base = os.path.join(root, "tools")
    if not os.path.isdir(base):
        return ["%s has no tools/ - a walk of nothing is not a clean tree" % root]
    found = []
    for folder, dirs, names in os.walk(base):
        dirs[:] = sorted(d for d in dirs if d != "__pycache__")
        for name in sorted(names):
            if name.endswith(".py"):
                path = os.path.join(folder, name)
                rel = os.path.relpath(path, root).replace(os.sep, "/")
                with io.open(path, encoding="utf-8", errors="replace") as handle:
                    found += findings(rel, handle.read())
    return found


def main(argv):
    why = refused(argv[1:], most=1)
    if why:
        print(why)
        return 2
    found = problems(os.path.abspath(argv[1] if len(argv) > 1 else os.getcwd()))
    for line in found:
        print("REFUSING: " + line)
    if not found:
        print("every tool under tools/ that reads its command line stops on a word it does not read")
    return 1 if found else 0


if __name__ == "__main__":
    sys.dont_write_bytecode = True
    raise SystemExit(main(sys.argv))
