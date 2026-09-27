#!/usr/bin/env python3
"""THE VERIFICATION BOOTSTRAP IS INSTALLED HERE - asked at every commit, not remembered.

INSTALL IT INTO THE PROJECT and call it from `.githooks/pre-commit`. `tools/check_install.py`
stays in the bundle and is run FROM the project; this is the project-side half that runs it,
because a check somebody has to remember to run is a convention, and conventions are the
category the project this came from watched fail most. On 2026-09-22 the check WAS run, and
printed "installed: ... 9 hook(s) are present, wired, and current" over an install where
`bundle_env.py` had never been copied, `dispatch.py` had drifted, and no tool, test or plant had
been looked at. It asks every file now, and this asks it at the commit - so the next drift is
refused the moment somebody tries to land it.

Where the bundle lives is read from `.claude/bundle-install.json` (`_bundle`), a TRACKED file: a
moved bundle is a reviewed diff, not a flag. A bundle that cannot be read REFUSES - the install
is UNKNOWN, and an unknown install rendering as a clean one is the defect this exists after.

    python -B tools/bundle_install.py
"""
import importlib.util
import io
import json
import os
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

LF = chr(10)

#: The project-side declarations file, relative to the repository root.
DECLARATIONS_NAME = os.path.join(".claude", "bundle-install.json")

#: Adapted per project: see ADAPT.md.
ADAPT = ("DECLARATIONS_NAME",)


def _root():
    """The repository root: the nearest ancestor holding the declarations file, or `.git`."""
    here = os.path.dirname(os.path.abspath(__file__))
    while True:
        if os.path.isfile(os.path.join(here, DECLARATIONS_NAME)) or os.path.exists(
                os.path.join(here, ".git")):
            return here
        parent = os.path.dirname(here)
        if parent == here:
            return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        here = parent


ROOT = _root()
DECLARATIONS = os.path.join(ROOT, DECLARATIONS_NAME)


def _project_of(declarations):
    """The project root a declarations file speaks for: the folder holding `.claude/` when the file
    sits in it - its real place - and otherwise the file's own folder."""
    here = os.path.dirname(os.path.abspath(declarations))
    return os.path.dirname(here) if os.path.basename(here) == ".claude" else here


def bundle_path(declarations=None):
    """(path, refusal) - where the bundle lives, or why that cannot be said.

    `_bundle` is ONE PATH OR A LIST OF THEM, tried in order, and the first that is a folder wins.
    Added 2026-09-26, when the bundle moved from one Desktop folder to a GitHub repository: a
    project cloned onto a machine with no such Desktop - every cloud machine - refused EVERY commit
    here, correctly, because an install nobody can check is not an installed one. A list lets a
    project name both the machine's shared copy and a clone of its own, and still refuses exactly
    as before when none of them is there.

    A RELATIVE entry is resolved against the PROJECT, never the working directory. Resolved
    against the working directory it would be found by a commit run from the project root and
    missed by the same commit run from `tools/` - a verdict that depends on where you stood.

    "A moved bundle is a reviewed diff, not a flag": the list lives in the tracked declarations
    file like the single path did, so no environment variable can point this anywhere.
    """
    declarations = declarations or DECLARATIONS
    try:
        with io.open(declarations, encoding="utf-8") as handle:
            held = json.load(handle)
    except (OSError, ValueError) as exc:
        return None, "%s cannot be read (%s), so where the bundle lives is UNKNOWN" % (
            declarations, exc)
    where = held.get("_bundle") if isinstance(held, dict) else None
    entries = [where] if isinstance(where, str) else where if isinstance(where, list) else []
    entries = [e for e in entries if isinstance(e, str) and e.strip()]
    if not entries:
        return None, "%s names no `_bundle`, so there is nothing to check against" % declarations
    project = _project_of(declarations)
    tried = []
    for entry in entries:
        path = entry if os.path.isabs(entry) else os.path.join(project, entry)
        if os.path.isdir(path):
            return path, None
        tried.append(path)
    return None, ("the bundle recorded at %s is not a folder here - an install nobody can "
                  "check is not an installed one. If it moved, change `_bundle` in %s"
                  % (" or ".join(tried), declarations))


def load_checker(where):
    """The bundle's `check_install` module, loaded from its own folder, or (None, why)."""
    path = os.path.join(where, "tools", "check_install.py")
    # NO BYTECODE WRITTEN INTO THE BUNDLE. Loading the checker from a project process that was
    # not started with `-B` - a commit runner spawning its ratchets - wrote `tools/__pycache__`
    # into the bundle folder, and the bundle's own check refused it as unfit to hand on. The
    # load is the one place every caller passes through, so the fix is here, not in each caller.
    held = sys.dont_write_bytecode
    sys.dont_write_bytecode = True
    try:
        spec = importlib.util.spec_from_file_location("bundle_check_install", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    except Exception as exc:                          # noqa: BLE001 - cannot load is a refusal
        return None, "%s could not be loaded (%s: %s)" % (path, type(exc).__name__, exc)
    finally:
        sys.dont_write_bytecode = held
    if not callable(getattr(module, "problems", None)):
        return None, "%s has no `problems` to ask" % path
    return module, None


def problems(project=None, declarations=None):
    """Every reason the bundle is not installed. Empty ONLY when the checker ran and agreed."""
    where, why = bundle_path(declarations)
    if why:
        return [why]
    checker, why = load_checker(where)
    if why:
        return [why]
    found, asked = checker.problems(project or ROOT)
    if not asked:
        return ["the bundle's checker asked about NO file - a wrong path, not a clean install"]
    return list(found)


def problems_with(overlay, project=None, declarations=None):
    """`problems` with `{absolute path: text}` served in place of those files on disk.

    The bundle's checker reads every project file through its one `read` helper, so serving the
    proposed text there asks the SAME checker about the install a write would leave - not a
    second derivation of what "installed" means.
    """
    where, why = bundle_path(declarations)
    if why:
        return [why]
    checker, why = load_checker(where)
    if why:
        return [why]
    served = {os.path.normcase(os.path.abspath(k)): v for k, v in overlay.items()}
    real = checker.read

    def read(path):
        key = os.path.normcase(os.path.abspath(path))
        return served[key] if key in served else real(path)

    checker.read = read
    try:
        found, asked = checker.problems(project or ROOT)
    finally:
        checker.read = real
    if not asked:
        return ["the bundle's checker asked about NO file - a wrong path, not a clean install"]
    return list(found)


def _installed_scope(declarations=None):
    """A pattern matching every project path the install check reads - derived from the bundle's
    own file list and this project's declarations, so a file the bundle adds tomorrow is in it.

    ONE WALK: the checker's. This walked the bundle itself with a skip list of its own, while the
    checker's walk had grown a rule this one never heard of - a tool's signed cache is not a file
    the bundle ships - so the two disagreed about what the bundle IS. A rule written at two
    places is one of them from wrong, so the list is asked of `check_install.bundle_files`.

    AND ONE READER OF THE DECLARATIONS: the checker's. This read the JSON itself and knew only a
    file's own `at`, so `_hooks_at` - every bundle hook moved beside a project's own stack by one
    key - left this scope asking about the PROJECT's hooks and never about the bundle's.
    """
    where, why = bundle_path(declarations)
    paths = {DECLARATIONS_NAME.replace(os.sep, "/"), "CLAUDE.md", ".claude/settings.json",
             ".claude/settings.local.json"}
    checker = None if why else load_checker(where)[0]
    if checker is not None and callable(getattr(checker, "bundle_files", None)):
        project = os.path.dirname(os.path.dirname(os.path.abspath(declarations or DECLARATIONS)))
        held, _trouble = checker.declarations(project)
        for rel in checker.bundle_files(where):
            entry = held.get(rel) or {}
            if "not_installed" not in entry:
                paths.add(entry.get("at") or rel)
    return "^(%s)$" % "|".join(sorted(p.replace(".", r"\.") for p in paths))


#: ASKED AT THE WRITE of every file the install check reads. The recorded reason this sat in the
#: suite - "no single write carries the question" - described the answer, not the question: the
#: install is a set of files, a write changes one, and the bundle's own checker can be asked
#: about the set with that one file as the write would leave it. A hook edited out of step with
#: the bundle, a declaration dropped, the dispatcher unwired, a merged plant removed - each is
#: refused by the write that does it, rather than found at a commit hours later.
AT_WRITE = "worse_in"
AT_SCOPE = _installed_scope()


def worse_in(rel, text):
    """The install check's findings with `rel` holding `text` - asked of before and after."""
    return problems_with({os.path.join(ROOT, rel.replace("/", os.sep)): text})


def main(argv):
    found = problems()
    for line in found:
        sys.stdout.write("REFUSING: %s%s" % (line, LF))
    if found:
        sys.stdout.write("%d problem(s) with the verification bootstrap's install. A rule that "
                         "is not in the load path is a document; a hook nobody calls is a file.%s"
                         % (len(found), LF))
        return 1
    sys.stdout.write("the verification bootstrap is installed, wired, current and merged%s" % LF)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
