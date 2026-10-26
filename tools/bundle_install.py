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
import contextlib
import importlib.util
import os
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

LF = chr(10)

#: The project-side declarations file, relative to the repository root.
DECLARATIONS_NAME = os.path.join(".claude", "bundle-install.json")

#: Adapted per project: see the bootstrap's ADAPT.md.
ADAPT = ("DECLARATIONS_NAME",)


sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root        # noqa: E402  - the project root, anchored: never counted (F216)

ROOT = _tree_root.require(__file__)
DECLARATIONS = os.path.join(ROOT, DECLARATIONS_NAME)


def bundle_path(declarations=None):
    """(path, refusal) - where the bundle lives, or why that cannot be said.

    Asked of the hooks' shared module: `bundle_rules.bundle_path` is the ONE reader of `_bundle`,
    because the stack guard asks it too (F261), and a reader here as well would be two readings of
    one record. Its docstring says how `_bundle` is read."""
    sys.path.insert(0, os.path.join(ROOT, "tools"))
    import bundle_hooks  # noqa: PLC0415 - where THIS project keeps the bundle's hooks (`_hooks_at`)
    sys.path.insert(0, bundle_hooks.path(ROOT))
    import bundle_rules  # noqa: PLC0415 - the one reader of `_bundle`
    return bundle_rules.bundle_path(declarations or DECLARATIONS)


@contextlib.contextmanager
def sealed(where):
    """Run the BUNDLE's code in this process and leave its imports as they were found.

    TWO TREES, ONE PROCESS, AND A PLAIN NAME RESOLVES TO WHICHEVER COMES FIRST. Measured
    2026-10-09 on CTRMap: the checker put the bundle's `tools` folder first on `sys.path` and left
    it there. The hook process that loaded it then asked the project's `tools/tiers.py` as a rule,
    `import tiers` found the bundle's newer copy, and that copy called a function the project's
    `bundle_rules` does not have - every judgement held, and every act but a read was refused.
    The other direction is the same defect: the checker's own `import gate_stamps` returned the
    PROJECT's module when one was already loaded. So, for the length of the call, a module whose
    name the bundle's `tools` also holds is hidden from it; afterwards the path is put back, every
    module the bundle's folder added is dropped, and every project module is put back as it was.
    """
    theirs = set()
    try:
        theirs = {name[:-3] for name in os.listdir(os.path.join(where, "tools"))
                  if name.endswith(".py")}
    except OSError:
        pass
    path, modules = list(sys.path), dict(sys.modules)
    for name in theirs & set(modules):
        del sys.modules[name]
    bundle = os.path.normcase(os.path.abspath(where)).rstrip(os.sep) + os.sep
    try:
        yield
    finally:
        sys.path[:] = path
        for name in list(sys.modules):
            if name in modules:
                continue
            origin = getattr(sys.modules.get(name), "__file__", None) or ""
            if origin and os.path.normcase(os.path.abspath(origin)).startswith(bundle):
                del sys.modules[name]
        for name, module in modules.items():
            if sys.modules.get(name) is not module:
                sys.modules[name] = module


class Sealed(object):
    """The bundle's checker, with EVERY call into it run `sealed` - at the door, not at each call
    site, because a guard at the call sites is one call site from broken. `module` is the raw
    module, for setting an attribute on it."""

    def __init__(self, module, where):
        self.module = module
        self.where = where

    def __getattr__(self, name):
        value = getattr(self.module, name)
        if not callable(value):
            return value

        def call(*args, **kwargs):
            with sealed(self.where):
                return value(*args, **kwargs)
        return call


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
        with sealed(where):
            spec.loader.exec_module(module)
    except Exception as exc:                          # noqa: BLE001 - cannot load is a refusal
        return None, "%s could not be loaded (%s: %s)" % (path, type(exc).__name__, exc)
    finally:
        sys.dont_write_bytecode = held
    if not callable(getattr(module, "problems", None)):
        return None, "%s has no `problems` to ask" % path
    return Sealed(module, where), None


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
    real = checker.module.read

    def read(path):
        key = os.path.normcase(os.path.abspath(path))
        return served[key] if key in served else real(path)

    checker.module.read = read
    try:
        found, asked = checker.problems(project or ROOT)
    finally:
        checker.module.read = real
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
#:
#: AND A COMMAND THAT WRITES MANY IS ASKED ONCE A SIDE. This was AT_WRITE, asked of one file at a
#: time: measured 2026-10-10, a command that left 61 install files changed asked the whole install
#: check 122 times, about 1.6 s each, and the dispatcher call holding it took 98 s.
AT_WRITE_SET = "worse_with"
AT_SCOPE = _installed_scope()


def worse_with(texts):
    """The install check's findings with each of `texts` - {rel: text, None where the file is
    absent} - in place of the file on disk."""
    return problems_with({os.path.join(ROOT, rel.replace("/", os.sep)): text
                          for rel, text in texts.items()})


def main(argv):
    import cli_words      # a word this does not read refuses the run, before anything runs
    why = cli_words.refused(argv[1:], most=0)
    if why:
        print(why)
        return 2
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
