# -*- coding: utf-8 -*-
"""A GUARD IS NOT VERIFIED BECAUSE IT IS GREEN. IT IS VERIFIED BECAUSE IT CAN GO RED.

README section 2 says this at length and the bundle did not do it. `tests/test_bundle.py` passed
on its first run for four of its classes - which is exactly what a suite that tests nothing also
does. The one class that did NOT pass found a live defect in a shipped hook, and that is the
only reason anybody knows the suite works at all.

WHAT THIS DOES. Every entry in `tests/plants.json` is a DEFECT, written as an exact substitution
into a real file, and the ONE test that must go red when it is applied. This performs the
substitution, runs that single test, requires it to FAIL, and puts the file back byte-for-byte.
Nothing is reported as proven unless the plant actually reddened.

    python -B tools/prove_plants.py                    drive every plant
    python -B tools/prove_plants.py --only Heredoc     one, by test-id substring
    python -B tools/prove_plants.py --owed             the ratchets only - milliseconds
    python -B tools/prove_plants.py --add new.json     drive NEW plants, record them if all redden
    python -B tools/prove_plants.py --reanchor x.json  the same, replacing plants whose anchor
                                                       the code they plant into has moved past
    python -B tools/prove_plants.py --retire r.json    {key: reason} - plants whose test now
                                                       runs and passes, because what they name
                                                       can no longer happen; driven first
    python -B tools/prove_plants.py --merge-from DIR   the same, for another ledger's plants whose
                                                       test file is installed here (a bundle's)
    python -B tools/prove_plants.py --recount          write the ceilings from the counts
    python -B tools/prove_plants.py --adopt            ONCE, at install: the project's own test
                                                       classes with no plant, as its starting debt

THE LEDGER IS WRITTEN BY THIS TOOL AND BY NOTHING ELSE. A plant added by hand is a plant nobody
watched fail, and a ceiling typed in is a measurement edited - so `--add` drives before it
writes, and `--recount` is the only thing that moves a ceiling.

DOES IT GENERALISE? A plant at the site a guard was WRITTEN for proves only that the guard is
not decoration - a checker that finds that one site and nothing else passes it perfectly. So
every plant records WHERE it was planted:

    instance    the defect the guard was written for - it is not decoration
    elsewhere   the same defect somewhere it had never seen - it DERIVES
    evasion     the cheapest way PAST the guard - it closes its own back door

A class with no `elsewhere` plant is named in `single_site`, and one with no `evasion` plant in
`unevadable`, each with a written reason - because a behaviour test about one function genuinely
has one site, and demanding a second there would be a ratchet firing on honest work.

THE RESTORE IS CHECKED, NOT ASSUMED. The source project had one transient `OSError` in a
restore's `finally` leave an injected fault on disk and report success; six modules were
measured as nothing afterwards. Here the write is retried, the bytes are compared after writing
them back, and a failed restore is shouted about rather than returned.
"""
import importlib.util
import io
import json
import os
import re
import subprocess
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there
import time

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEDGER = os.path.join(HERE, "tests", "plants.json")
LF = chr(10)
sys.path.insert(0, os.path.join(HERE, "tools"))
import bundle_hooks     # noqa: E402 - where THIS project keeps the bundle's hooks (`_hooks_at`)

#: No console window for a child whose output is captured - Windows opens one for every
#: console process unless told otherwise, and a gate that blinks windows at every commit
#: is one somebody removes.
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

#: Where a project that installed this bundle records what it relocated - read by `--merge-from`
#: so a bundle plant on a relocated file is planted where the project keeps it.
DECLARATIONS = os.path.join(HERE, ".claude", "bundle-install.json")

#: EVERY ENVIRONMENT VARIABLE THE CODE UNDER TEST READS ITS LIVE DATA ROOT FROM, pointed at a
#: fresh empty folder for every test this starts. README section 9: tests must never write live
#: state - and a planted defect is precisely code that stops honouring the redirect a test set up.
#: On the project this came from a mutant of the line reading its data-dir override wrote a row
#: into the live 4.9 GB store's migration ledger; the mutant was killed and the write had already
#: happened. The OS application-data roots are the default; ADD your own override's name.
SANDBOX_VARS = ("LOCALAPPDATA", "APPDATA", "XDG_DATA_HOME")

#: What a project may need to change about this file, and nothing else. `check_install.py`
#: compares this file with these names blanked, so setting them is not drift.
ADAPT = ("LEDGER", "TEST_TIMEOUT", "DECLARATIONS", "SANDBOX_VARS")


#: git's own variables that say WHICH repository, index or object store a git command acts on.
#: git hands them to its hooks - a `commit-msg` gets GIT_INDEX_FILE=.git/index, a relative path -
#: and a test child that inherits them runs its scratch repository's git against the committing
#: one's index. Measured on the project this came from, 2026-09-24: a test building a scratch
#: worktree failed 128 under the commit's plant check and passed outside it. Never passed on.
GIT_LOCATION = ("GIT_INDEX_FILE", "GIT_DIR", "GIT_WORK_TREE", "GIT_COMMON_DIR",
                "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_NAMESPACE",
                "GIT_PREFIX")


def sandboxed_env(root, base=None):
    """A child environment in which every `SANDBOX_VARS` root is `root` - no path to live data -
    and none of git's location variables, so a test's own repository is the one its git sees."""
    env = dict(os.environ if base is None else base, PYTHONDONTWRITEBYTECODE="1")
    for name in GIT_LOCATION:
        env.pop(name, None)
    for name in SANDBOX_VARS:
        env[name] = root
    return env

#: WHERE A PLANT WAS PUT. Read by the ratchets below AND by `--add`'s validation - a vocabulary
#: in two places is two vocabularies, and adding `evasion` in one of them on the source project
#: made two honest plants fail a test about spelling.
SITES = ("instance", "elsewhere", "evasion")

#: A reason under this is a label. This bundle shipped `guarded above` as an entire
#: justification once, on the project it came from, and it was false.
MIN_REASON = 60

#: The four debts this ledger ratchets, and the key its ceiling lives under. Every one is refused
#: when ABSENT - a ledger that stops naming a ceiling would delete its own ratchet on the next
#: write, which README section 16 records happening - and every one is refused with SLACK, because
#: a ceiling above its count is a defect of exactly that size.
CEILINGS = (("owed", "owed_ceiling"),
            ("generalisation", "generalisation_ceiling"),
            ("evasion", "evasion_ceiling"),
            ("must_say", "must_say_ceiling"))


def read_json(path):
    with io.open(path, encoding="utf-8") as handle:
        return json.load(handle)


def load():
    try:
        return read_json(LEDGER)
    except (OSError, ValueError) as bad:
        raise SystemExit("cannot read %s: %s" % (LEDGER, bad))


def write(ledger):
    """The ONE write of the ledger. Sorted keys and a fixed indent, so a diff is the change."""
    with io.open(LEDGER, "w", encoding="utf-8", newline=LF) as handle:
        handle.write(json.dumps(ledger, indent=1, sort_keys=True, ensure_ascii=False) + LF)


def unittest_id(name):
    """`test_x.py::Class::test_m` -> `test_x.Class.test_m`, the id `unittest` takes.

    A FOURTH part is a label, for a second plant on the same test - `...::test_m::evasion` - so
    two defects that one test must catch are two keys rather than one overwriting the other.
    """
    parts = name.split("::")
    if len(parts) not in (3, 4) or not parts[0].endswith(".py"):
        raise SystemExit("a plant key must be `test_file.py::Class::test_method[::label]`, "
                         "not %r" % name)
    return "%s.%s.%s" % (parts[0][:-3], parts[1], parts[2])


#: How long ONE named test may take under a plant. README section 2: A SUITE THAT HANGS UNDER A
#: PLANT IS NEVER A KILL - it looks identical to a slow pass from outside, and one sat twenty
#: minutes before anybody asked. A timeout is a RESULT here, reported as hung, never as red.
TEST_TIMEOUT = 300

#: unittest's REPORT LINE for a test id that did not load - `ERROR: name (unittest.loader.
#: _FailedTest.name)` - never the word anywhere in the output, where a failure message can print it.
_UNRESOLVED = re.compile(r"^(?:FAIL|ERROR): \S+ \(unittest\.loader\._FailedTest\.", re.M)


def named_test_fails(test_id, must_say=None, folder=None):
    """(reddened?, what it said) for ONE test, run in its own process.

    A TEST ID THAT DOES NOT RESOLVE IS NOT A FAILING TEST. `unittest` reports an unknown name as
    an error, which looks exactly like the failure a plant is supposed to cause - so the output
    is checked for that shape and refused. `_FailedTest` is the name unittest gives it.

    THE FAILURE MUST SAY WHAT THE PLANT WAS WATCHING FOR, when the plant records `must_say`.
    README section 2: a planted defect that reddens the test through an unrelated check is scored
    as a kill while the guard it claims to prove is asleep - three plants were NOT PROVEN by this
    alone on the source project.
    """
    import shutil
    import tempfile
    sandbox = tempfile.mkdtemp(prefix="plant-data-")
    try:
        done = subprocess.run([sys.executable, "-B", "-m", "unittest", test_id],
                              cwd=folder or os.path.join(HERE, "tests"), capture_output=True,
                              text=True, timeout=TEST_TIMEOUT, creationflags=NO_WINDOW,
                              env=sandboxed_env(sandbox))
    except subprocess.TimeoutExpired:
        return False, ("HUNG after %ds - a hang is never a kill; it is a defect in the suite, "
                       "not evidence about the guard" % TEST_TIMEOUT)
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)
    said = (done.stdout or "") + (done.stderr or "")
    # UNITTEST'S OWN MARKER, NOT A WORD THAT MIGHT BE IN A MESSAGE. An unknown module, class or
    # method all come back as a `_FailedTest` - measured on 3.12. The first version also refused
    # any output containing `ModuleNotFoundError`, and so refused a real kill: an installer test
    # whose planted defect WAS a missing import, reported in the refusal the test printed.
    #
    # AND NOT THE WORD `_FailedTest` EITHER. The fix above left `"_FailedTest" in said`, the same
    # shape one word over: the project this came from refused a real kill the same afternoon
    # because the test's failure message printed a list holding that id as DATA. The marker is
    # unittest's report LINE - `ERROR: name (unittest.loader._FailedTest.name)`.
    if _UNRESOLVED.search(said):
        return False, "the test id does not resolve: " + said.strip().splitlines()[-1][:160]
    # A VERDICT NEEDS A RUN. A child that dies before unittest's summary - `os._exit`, a crash, an
    # interpreter that never started - exits non-zero with no test having failed anything.
    # The project this came from requires the same line and always has.
    if "Ran 1 test" not in said:
        return False, ("the test id does not resolve, or it never ran to a verdict: "
                       + (said.strip()[-200:] or "no output at all"))
    last = said.strip().splitlines()[-1][:160] if said.strip() else ""
    if done.returncode == 0:
        return False, last
    if must_say and must_say not in said:
        return False, ("it reddened, and its failure does not say %r - so it reddened through "
                       "some OTHER check, and the guard this plant claims to prove is unproven"
                       % must_say)
    return True, last


def still_builds(path, planted):
    """None when the planted file still compiles, else why not.

    README section 2: THE PLANTED TREE MUST STILL BUILD. If it does not compile, the suite never
    ran the guard and "it failed" means only that the compiler did.
    """
    if not path.endswith(".py"):
        return None
    try:
        compile(planted, path, "exec")
    except SyntaxError as bad:
        return "the planted file does not compile (%s) - the suite never reached the guard" % bad
    return None


def forget_bytecode(path):
    """Delete the cached bytecode for `path`, if any.

    PYTHON DECIDES A `.pyc` IS CURRENT FROM (mtime in whole seconds, size). A plant like
    `LIMIT = 8` -> `= 0` is the SAME LENGTH and lands inside the same second - so the test may
    import the cached ORIGINAL and pass, and worse, after the restore it may import the cached
    DEFECT. The second happened on the source project: correct source, clean tree, and the
    interpreter serving the defect to every test afterwards. `-B` stops WRITING bytecode, not
    reading it.
    """
    if not path.endswith(".py"):
        return
    try:
        cached = importlib.util.cache_from_source(path)
    except (NotImplementedError, ValueError):
        return
    try:
        os.remove(cached)
    except OSError:
        pass


def put_back(path, original, attempts=5):
    """Write `original` to `path` and read it back; None when it is there, else why not.

    RETRIED, because on Windows a scanner or the indexer can hold a file for a moment after a
    subprocess that touched it exits - errno 22 on a write that is not reproducible afterwards.
    One such failure on the source project left a planted launcher on disk.
    """
    why = None
    for attempt in range(attempts):
        try:
            with io.open(path, "wb") as handle:
                handle.write(original)
            with io.open(path, "rb") as handle:
                if handle.read() == original:
                    forget_bytecode(path)
                    return None
            why = "the bytes read back differ from the original"
        except OSError as bad:
            why = str(bad)
        time.sleep(0.2 * (attempt + 1))
    return why


def apply_and_run(entry, test_id, root=None, run=None, rebuild=None):
    """(reddened?, detail). Restores byte-for-byte whatever happens.

    `run(test_id, must_say)` asks the test - Python's `unittest` unless a project's declared
    runner is handed in. `rebuild()` is a declared runner's build, run after the plant AND after
    the restore: a compiled project's build output is its `.pyc`, and a restore the build never
    saw leaves every later test running the planted defect - the failure `forget_bytecode`
    exists for, one language over.
    """
    path = os.path.join(root or HERE, entry["file"].replace("/", os.sep))
    if not os.path.isfile(path):
        return False, "no such file: %s" % entry["file"]
    with io.open(path, "rb") as handle:
        original = handle.read()
    # LINE ENDINGS ARE THE FILE'S, NOT THE PLANT'S. A plant is written with LF; a file committed
    # CRLF would never match it, and a plant that cannot be applied reads as stale.
    crlf = chr(13) + LF
    flat = original.decode("utf-8").replace(crlf, LF)
    if flat.count(entry["old"]) != 1:
        return False, ("the plant's `old` text appears %d times in %s, not once - it no longer "
                       "describes the code" % (flat.count(entry["old"]), entry["file"]))
    planted_text = flat.replace(entry["old"], entry["new"], 1)
    if planted_text == flat:
        return False, "the substitution changed nothing"
    broken = still_builds(path, planted_text)
    if broken:
        return False, broken
    if crlf in original.decode("utf-8"):
        planted_text = planted_text.replace(LF, crlf)
    try:
        with io.open(path, "wb") as handle:
            handle.write(planted_text.encode("utf-8"))
        forget_bytecode(path)
        if rebuild is not None:
            broken = rebuild()
            if broken:
                return False, ("the planted tree does not build (%s) - the suite never reached "
                               "the guard" % broken)
        return (run or named_test_fails)(test_id, entry.get("must_say"))
    finally:
        failed = put_back(path, original)
        if failed:
            sys.stderr.write(
                LF + "RESTORE FAILED for %s (%s) - the file on disk is NOT what it was. Put it "
                "back before running anything else." % (entry["file"], failed) + LF)
            raise SystemExit(2)
        if rebuild is not None:
            broken = rebuild()
            if broken:
                sys.stderr.write(
                    LF + "THE RESTORED TREE DOES NOT BUILD (%s) - its build output may still "
                    "carry the plant. Rebuild it before running anything else." % broken + LF)
                raise SystemExit(2)


# ----------------------------------------------------------------------------- other languages

# A PROJECT'S OWN TESTS, IN WHATEVER LANGUAGE, BY ITS OWN COMMAND. Measured on the second project
# this bundle came from (Java): no JUnit, no Gradle, no Maven - 153 tests that are `main` classes
# run by a PowerShell table, each with its own arguments. No command this bundle could guess would
# run one of them. So a project DECLARES how, in `.claude/bundle-install.json`:
#
#     "_tests": {"find":  "src/**/tests/*Test.java",       every test file, a recursive glob
#                "run":   ["java", "-cp", "build/classes", "{name}"],   ONE test; exit != 0 is red
#                "build": ["powershell", "-File", "build.ps1"],         optional; after the plant
#                                                                       AND after the restore
#                "unresolved": "Could not find or load main class"}    text meaning "no such test"
#
# `{name}` is the package-qualified class, `{class}` the bare class, `{file}` the path. Each file
# `find` matches is one guard class, keyed `<file>::<Class>`, and is planted, driven and ratcheted
# exactly as a Python test class is. A project that proves its tests another way declares
# `"_tests": {"not_covered": "<80+ characters why>"}` instead; saying nothing is refused by the
# install check, because a ledger blind to the project's own tests reports clean over them.

def declared_tests(path=None):
    """The project's `_tests` runner from its declarations, or None when there is none."""
    try:
        held = read_json(path or DECLARATIONS)
    except (OSError, ValueError):
        return None
    tests = held.get("_tests") if isinstance(held, dict) else None
    return tests if isinstance(tests, dict) and tests.get("find") and tests.get("run") else None


def declared_classes(config=None, root=None):
    """{"<file>::<Class>"} for every test file the declared runner's `find` matches."""
    import glob
    config = declared_tests() if config is None else config
    if not config:
        return set()
    root = root or HERE
    out = set()
    for path in sorted(glob.glob(os.path.join(root, config["find"]), recursive=True)):
        if os.path.isfile(path):
            rel = os.path.relpath(path, root).replace(os.sep, "/")
            out.add("%s::%s" % (rel, os.path.splitext(os.path.basename(rel))[0]))
    return out


def declared_command(key, config, root=None):
    """The declared `run` command for one plant key, its placeholders filled."""
    import re
    rel, cls = key.split("::")[:2]
    try:
        with io.open(os.path.join(root or HERE, rel.replace("/", os.sep)),
                     encoding="utf-8", errors="replace") as handle:
            text = handle.read()
    except OSError:
        text = ""
    package = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.M)
    values = {"{name}": "%s.%s" % (package.group(1), cls) if package else cls,
              "{class}": cls, "{file}": rel}
    out = []
    for arg in config["run"]:
        for mark, value in values.items():
            arg = str(arg).replace(mark, value)
        out.append(arg)
    return out


def _declared_child(argv, root=None):
    """(exit code, None when it HUNG; everything it said) for one declared command, sandboxed."""
    import shutil
    import tempfile
    sandbox = tempfile.mkdtemp(prefix="plant-data-")
    try:
        done = subprocess.run(argv, cwd=root or HERE, capture_output=True, text=True,
                              timeout=TEST_TIMEOUT, creationflags=NO_WINDOW,
                              env=sandboxed_env(sandbox))
        return done.returncode, (done.stdout or "") + (done.stderr or "")
    except subprocess.TimeoutExpired:
        return None, "HUNG after %ds" % TEST_TIMEOUT
    except OSError as bad:
        return 127, "could not start %s: %s" % (argv[0] if argv else "nothing", bad)
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)


def run_declared(config, root=None):
    """A `run(key, must_say)` over the project's declared runner - the same verdicts as Python's."""
    def run(key, must_say=None):
        code, said = _declared_child(declared_command(key, config, root), root)
        if code is None:
            return False, ("HUNG after %ds - a hang is never a kill; it is a defect in the suite, "
                           "not evidence about the guard" % TEST_TIMEOUT)
        unresolved = config.get("unresolved")
        if code == 127 or (unresolved and unresolved in said):
            return False, "the test does not resolve: " + said.strip()[-200:]
        last = said.strip().splitlines()[-1][:160] if said.strip() else ""
        if code == 0:
            return False, last
        if must_say and must_say not in said:
            return False, ("it reddened, and its failure does not say %r - so it reddened "
                           "through some OTHER check, and the guard this plant claims to prove "
                           "is unproven" % must_say)
        return True, last
    return run


def build_declared(config, root=None):
    """A `rebuild()` over the declared build: None when it builds, else why not. None if none."""
    if not config.get("build"):
        return None

    def rebuild():
        code, said = _declared_child([str(a) for a in config["build"]], root)
        if code == 0:
            return None
        return "HUNG" if code is None else "exit %s: %s" % (code, said.strip()[-200:])
    return rebuild


def drive_plant(key, entry, root=None):
    """(reddened?, detail) for ONE plant, by whichever runner its test needs. The one door."""
    if key.split("::")[0].endswith(".py"):
        return apply_and_run(entry, unittest_id(key), root)
    config = declared_tests(os.path.join(root, ".claude", "bundle-install.json") if root
                            else None)
    if config is None:
        return False, ("%s is not a Python test, and this project declares no `_tests` runner "
                       "that could run it" % key.split("::")[0])
    return apply_and_run(entry, key, root, run=run_declared(config, root),
                         rebuild=build_declared(config, root))


def guard_classes(folder=None):
    """Every test class in the suite, as `test_file.py::Class`.

    A CLASS THAT ASSERTS NOTHING IS NOT A GUARD. A fixture mixin - setup two test classes share,
    with no test of its own - was counted as a guard class owing a plant, and no plant can redden
    a class that runs no test. Only a class that defines a `test*` method is one.
    """
    import ast
    # THE PROJECT'S OWN TESTS TOO, in whatever language its declared runner finds - asked only of
    # the project itself, never of a folder a caller hands in.
    named = declared_classes() if folder is None else set()
    folder = folder or os.path.join(HERE, "tests")
    for name in sorted(os.listdir(folder)):
        if not (name.startswith("test_") and name.endswith(".py")):
            continue
        with io.open(os.path.join(folder, name), encoding="utf-8") as handle:
            tree = ast.parse(handle.read())
        for node in tree.body:
            if isinstance(node, ast.ClassDef) and any(
                    isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef))
                    and child.name.startswith("test") for child in node.body):
                named.add("%s::%s" % (name, node.name))
    return named


def sites_by_class(ledger):
    sites = {}
    for key, entry in (ledger.get("plants") or {}).items():
        sites.setdefault("::".join(key.split("::")[:2]), set()).add(entry.get("site"))
    return sites


def debts(ledger, classes=None):
    """{debt: sorted names} for the four things this ledger ratchets.

    owed            guard classes no plant names
    generalisation  classes proven only at their own site, not excused in `single_site`
    evasion         classes whose cheapest way past nobody planted, not excused in `unevadable`
    must_say        plants that do not say what their failure must SAY
    """
    classes = guard_classes() if classes is None else classes
    sites = sites_by_class(ledger)
    single = ledger.get("single_site") or {}
    unevadable = ledger.get("unevadable") or {}
    return {
        "owed": sorted(cls for cls in classes if cls not in sites),
        "generalisation": sorted(cls for cls, seen in sites.items()
                                 if "elsewhere" not in seen and cls not in single),
        "evasion": sorted(cls for cls, seen in sites.items()
                          if "evasion" not in seen and cls not in unevadable),
        "must_say": sorted(key for key, entry in (ledger.get("plants") or {}).items()
                           if not entry.get("must_say")),
    }


def owed(ledger):
    """(unplanted, ungeneral) - kept for callers written before the evasion site existed."""
    held = debts(ledger)
    return held["owed"], held["generalisation"]


def ratchet_problems(ledger, classes=None):
    """(report lines, problems) for every ceiling and every excuse in the ledger."""
    held = debts(ledger, classes)
    lines, problems = [], []
    for debt, key in CEILINGS:
        count = len(held[debt])
        if key not in ledger:
            problems.append("the ledger carries no %s, so nothing bounds this debt - an absent "
                            "ceiling is not no ceiling. `--recount` seeds it at the count."
                            % key)
            continue
        ceiling = int(ledger[key])
        lines.append("%-15s %3d, ceiling %d" % (debt, count, ceiling))
        if count > ceiling:
            problems.append("%s is %d against a ceiling of %d - it may only ever fall:%s      %s"
                            % (debt, count, ceiling, LF, (LF + "      ").join(held[debt])))
        elif count < ceiling:
            problems.append("%s is %d and its ceiling is %d - lower it in the same change "
                            "(`--recount`); a ceiling with slack is a defect of that size"
                            % (debt, count, ceiling))
    classes = guard_classes() if classes is None else classes
    for excuses in ("single_site", "unevadable"):
        for cls, why in sorted((ledger.get(excuses) or {}).items()):
            if len(str(why).strip()) < MIN_REASON:
                problems.append("%s excuses %s with %d characters; a reason under %d is a label"
                                % (excuses, cls, len(str(why).strip()), MIN_REASON))
            if cls not in classes:
                problems.append("%s excuses %s, which is not a test class here - an excuse for "
                                "nothing will excuse whatever takes the name next" % (excuses, cls))
    return lines, problems


def stale_anchors(ledger, root=None):
    """[(key, matches)] for every plant whose `old` no longer appears EXACTLY ONCE in its file.

    ASKED BY `--owed`, which a commit runs - not only when somebody drives the plants. On the
    project this came from, three edits in one session moved code a plant was anchored in, and
    each stale anchor surfaced later, one at a time. Counting a string costs milliseconds.
    """
    out = []
    for key, entry in sorted((ledger.get("plants") or {}).items()):
        path = os.path.join(root or HERE, str(entry.get("file") or "").replace("/", os.sep))
        try:
            with io.open(path, "rb") as handle:
                text = handle.read().decode("utf-8").replace(chr(13) + LF, LF)
        except (OSError, UnicodeDecodeError):
            out.append((key, 0))
            continue
        matches = text.count(entry.get("old") or "")
        if matches != 1:
            out.append((key, matches))
    return out


def recount(ledger, before, classes=None):
    """(ledger with its ceilings rewritten, problems). Never raises a ceiling except for arrivals.

    Every ceiling falls to its count. A ceiling may RISE only by the number of guard classes that
    had NO plant at all in `before` and are now counted by that debt - giving a guard its first
    plant creates evasion and generalisation debt for it, and a ratchet that refused the most
    honest act available would get its ceiling raised by hand until it meant nothing.

    THE BOUND IS THE ARRIVALS, AND IT IS A BOUND. The source project's copy computed
    `ceiling + max(arrivals, count - ceiling)`, which is the count whatever happened: a class
    already counted that lost ground was absorbed and logged as an arrival. Any rise beyond the
    arrivals is a problem here, and nothing is written.
    """
    now = debts(ledger, classes)
    new_classes = set(sites_by_class(ledger)) - set(sites_by_class(before))
    problems = []
    for debt, key in CEILINGS:
        count = len(now[debt])
        if key not in ledger:
            ledger[key] = count                         # the first write IS the seed
            continue
        arrived = [name for name in now[debt]
                   if "::".join(name.split("::")[:2]) in new_classes]
        allowed = int(ledger[key]) + (len(arrived) if debt in ("generalisation", "evasion")
                                      else 0)
        if count > allowed:
            problems.append("%s would be %d against a ceiling of %d plus %d arrival(s) - a class "
                            "already counted lost ground" % (debt, count, int(ledger[key]),
                                                             len(arrived)))
            continue
        ledger[key] = count
    return ledger, problems


def adopt(ledger, classes=None, committed=None):
    """(ledger, problems): the FIRST reading of a project's own test classes, taken ONCE.

    WHY IT EXISTS, measured installing this bundle into a fresh project: the ledger arrives with
    `owed_ceiling` 0, the project's own test classes have no plants, and the first commit was
    refused - `owed is 1 against a ceiling of 0` - while `--recount` refused too, reading a class
    this ledger had never seen as one "already counted" that lost ground. A ledger that meets a
    project for the first time cannot tell its existing debt from a regression; nothing could
    seed it except a hand edit, and a ceiling typed in is a measurement edited.

    So the first contact is its own act: the owed classes are RECORDED by name under `adopted`,
    the ceiling is set to their count, and from then on it only falls. ONCE, because adopting
    again is the cheapest way past every ceiling here - refused while this copy carries an
    adoption, and refused when the COMMITTED copy (`committed`, its text at HEAD) carries one this
    copy has lost, since deleting the key is the obvious way to try.
    """
    if ledger.get("adopted"):
        return ledger, ["this ledger adopted its project's tests on %s already - adopting again "
                        "would reset the debt it recorded, the cheapest way past every ceiling"
                        % (ledger["adopted"].get("at") or "an unrecorded date")]
    if committed and '"adopted"' in committed:
        return ledger, ["the COMMITTED ledger carries an adoption this copy has lost - deleting "
                        "the record to adopt again is the evasion this refuses"]
    owing = debts(ledger, classes)["owed"]
    ledger["adopted"] = {"at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                         "owed": owing}
    ledger["owed_ceiling"] = len(owing)
    return ledger, []


def adoptable(theirs, installed, relocated, held, declared_apart=()):
    """{key: entry} of another ledger's plants for test files present here and not yet held.

    `relocated` maps a file the other side ships to where this project keeps it.
    `declared_apart` are the files this project declared DIVERGED or NOT INSTALLED: a plant
    written against the bundle's copy describes code this project deliberately does not have,
    so it could only fail to apply - and `check_install.py` skips exactly the same set, or the
    merge and the check would disagree about what an install owes.
    """
    out = {}
    for key, entry in sorted(theirs.items()):
        if key.split("::")[0] not in installed or key in held:
            continue
        if entry.get("file") in declared_apart:
            continue
        entry = dict(entry)
        entry["file"] = relocated.get(entry.get("file"), entry.get("file"))
        out[key] = entry
    return out


def add(entries, drive=None, root=None, replace=False):
    """(exit code, lines). Validate, DRIVE, and only then record - all or none.

    `replace=True` (`--reanchor`) is the same path for a plant whose ANCHOR went stale because
    the code it plants into was rewritten: the key must exist, and the new form is driven red
    before it replaces the old. A plant that never carried `must_say` may keep having none - that
    debt is already counted - but one that had it must keep it.
    """
    ledger = load()
    held = ledger.get("plants") or {}
    failed, out = [], []
    for key, entry in sorted(entries.items()):
        if replace and key not in held:
            failed.append("%s is not in the ledger - `--reanchor` replaces existing plants only"
                          % key)
            continue
        if not replace and key in held:
            failed.append("%s is already in the ledger - `--add` records new plants only" % key)
            continue
        carried = replace and not held[key].get("must_say")
        for field in ("file", "old", "new", "site", "why", "must_say"):
            if field == "must_say" and carried:
                continue
            if field not in entry or (field != "new" and not entry.get(field)):
                failed.append("%s has no `%s`" % (key, field))
        if entry.get("site") not in SITES:
            failed.append("%s: site must be one of %s" % (key, list(SITES)))
        if len(str(entry.get("why") or "")) < MIN_REASON:
            failed.append("%s: `why` is under %d characters - a label, not a reason"
                          % (key, MIN_REASON))
    if failed:
        return 1, failed
    drive = drive or (lambda key, entry: drive_plant(key, entry, root))
    for key, entry in sorted(entries.items()):
        reddened, detail = drive(key, entry)
        out.append("  %-6s %s" % ("red" if reddened else "PASSED", key))
        if not reddened:
            failed.append("%s: %s" % (key, str(detail).strip()[:200]))
    if failed:
        return 1, out + ["REFUSING to record - a plant that does not redden proves nothing:"] + [
            "   " + line for line in failed]
    before = json.loads(json.dumps(ledger))
    ledger.setdefault("plants", {}).update(entries)
    ledger, problems = recount(ledger, before)
    if problems:
        return 1, out + ["REFUSING to record:"] + ["   " + line for line in problems]
    write(ledger)
    return 0, out + ["recorded %d plant(s); every one reddened its named test" % len(entries)]


#: A retirement's reason - what now stops the defect the plant named, so its test cannot notice
#: it. Longer than a plant's `why`, because retiring is the cheaper of the two acts.
MIN_RETIRE_REASON = 80


def retire(reasons, drive=None, root=None, classes=None):
    """(exit code, lines). Plants that can no longer redden, moved to `retired` with the reason -
    each DRIVEN first, and refused unless its test ran and passed.

    A PLANT CAN STOP REDDENING WITHOUT ANYTHING BEING WRONG. The defect it names can become one no
    single substitution expresses: a second check added beside the first, a rollback that puts
    back what an early return once had to prevent. Measured on this bundle's installer,
    2026-09-25: a refused install is stopped by two returns and then a rollback, so the plant that
    empties the first return has nothing left to show. Re-anchoring it would invent a defect for
    the test to catch; leaving it would refuse every commit that touches the file.

    NOT A WAY TO DROP A PLANT THAT WORKS. Refused while it still reddens; refused unless the run
    came back as unittest's own `OK` - a stale anchor, a planted file that does not compile, a
    skip or a hang is a plant to re-anchor, not a defect that became impossible; and a class left
    with no plant at all counts as `owed` again, against the ceiling that only falls.
    """
    ledger = load()
    held = ledger.get("plants") or {}
    failed, out = [], []
    for key, why in sorted(reasons.items()):
        if key not in held:
            failed.append("%s is not in the ledger" % key)
        elif len(str(why or "").strip()) < MIN_RETIRE_REASON:
            failed.append("%s: the reason is %d characters, and retiring needs %d - say what now "
                          "stops the defect it named" % (key, len(str(why or "").strip()),
                                                         MIN_RETIRE_REASON))
    if failed:
        return 1, failed
    drive = drive or (lambda key, entry: drive_plant(key, entry, root))
    for key in sorted(reasons):
        reddened, detail = drive(key, held[key])
        out.append("  %-6s %s" % ("red" if reddened else "PASSED", key))
        if reddened:
            failed.append("%s still reddens its test - it proves something, and retiring it "
                          "would drop a working guard" % key)
        elif str(detail).strip() != "OK":
            failed.append("%s did not redden, but its test did not simply pass either (%s) - "
                          "re-anchor it with `--reanchor`" % (key, str(detail).strip()[:160]))
    if failed:
        return 1, out + ["REFUSING to retire:"] + ["   " + line for line in failed]
    before = json.loads(json.dumps(ledger))
    stamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    for key, why in sorted(reasons.items()):
        entry = dict(ledger["plants"].pop(key))
        entry["retired"] = {"at": stamp, "why": str(why).strip()}
        ledger.setdefault("retired", {})[key] = entry
    ledger, problems = recount(ledger, before, classes)
    if problems:
        return 1, out + ["REFUSING to retire:"] + ["   " + line for line in problems]
    write(ledger)
    return 0, out + ["retired %d plant(s); each was driven and its test ran and passed"
                     % len(reasons)]


def reanchored(theirs, installed, relocated, held, declared_apart=()):
    """{key: entry} of another ledger's plants this project ALREADY holds, whose file, old or new
    have changed there since - planted where this project keeps the file.

    A MERGE THAT SKIPS WHAT IT HOLDS NEVER CARRIES A RE-ANCHOR. The bundle re-anchored a plant
    when the line it planted into changed; every project that had merged the old one kept it, so
    its anchor went stale the moment the new line was copied in, and the install check refused
    the project for a plant it had no way to update. A producer with no consumer.
    """
    out = {}
    for key, entry in sorted(theirs.items()):
        if key.split("::")[0] not in installed or key not in held:
            continue
        if entry.get("file") in declared_apart:
            continue
        entry = dict(entry)
        entry["file"] = relocated.get(entry.get("file"), entry.get("file"))
        mine = held[key]
        if any(entry.get(field) != mine.get(field) for field in ("file", "old", "new")):
            out[key] = entry
    return out


def hook_relocations(theirs, hooks, relocated=None):
    """{bundle path: project path} for every plant on a HOOK, when this project keeps the bundle's
    hooks somewhere other than `.claude/hooks`.

    `_hooks_at` moves the whole folder with ONE declaration, and this read only per-file `at`: on
    CTRMap, whose own guard stack is in `.claude/hooks`, a merge aimed 19 plants at files that are
    not the bundle's there - one of them at CTRMap's own `guard_fanout.py`. Where the hooks are is
    `bundle_hooks.folder`'s answer, the one every tool and test already asks.
    """
    relocated = relocated or {}
    prefix = bundle_hooks.DEFAULT + "/"
    if hooks == bundle_hooks.DEFAULT:
        return {}
    return {entry["file"]: hooks + "/" + entry["file"][len(prefix):]
            for entry in theirs.values()
            if str(entry.get("file") or "").startswith(prefix) and entry["file"] not in relocated}


def merge_from(other_root):
    """(exit code, lines): adopt another ledger's plants for the tests installed here."""
    try:
        theirs = read_json(os.path.join(other_root, "tests", "plants.json")).get("plants") or {}
    except (OSError, ValueError) as bad:
        return 1, ["REFUSING: the other ledger cannot be read (%s) - a subject that cannot be "
                   "read is not an empty one" % bad]
    declared = {}
    if os.path.isfile(DECLARATIONS):
        try:
            declared = read_json(DECLARATIONS)
        except (OSError, ValueError) as bad:
            return 1, ["REFUSING: %s cannot be read (%s)" % (DECLARATIONS, bad)]
    relocated = {rel: entry["at"] for rel, entry in declared.items()
                 if isinstance(entry, dict) and entry.get("at")}
    relocated.update(hook_relocations(theirs, bundle_hooks.folder(HERE), relocated))
    apart = {rel for rel, entry in declared.items()
             if isinstance(entry, dict) and ("diverged" in entry or "not_installed" in entry)}
    installed = set(os.listdir(os.path.join(HERE, "tests")))
    held = load().get("plants") or {}
    adopting = adoptable(theirs, installed, relocated, held, apart)
    moved = reanchored(theirs, installed, relocated, held, apart)
    if not adopting and not moved:
        return 0, ["nothing to merge: every plant for an installed test is already recorded, "
                   "as the other ledger records it"]
    out = []
    if moved:
        code, lines = add(moved, replace=True)
        out += ["  ~ %s" % key for key in moved] + lines
        if code:
            return code, out
    if adopting:
        code, lines = add(adopting)
        return code, out + ["  + %s" % key for key in adopting] + lines
    return 0, out


#: Every flag this reads. An unknown one is REFUSED: `--reanchor`, typed before it existed, fell
#: through to the default - driving every plant in the ledger - with nothing saying the argument
#: had been ignored. An argument nothing reads is not a request that was honoured.
FLAGS = ("--add", "--reanchor", "--retire", "--merge-from", "--recount", "--only", "--owed",
         "--adopt")


def committed_ledger():
    """The ledger's text at HEAD, '' when HEAD has none, None when git could not say."""
    rel = os.path.relpath(LEDGER, HERE).replace(os.sep, "/")
    try:
        done = subprocess.run(["git", "show", "HEAD:" + rel], cwd=HERE, capture_output=True,
                              text=True, timeout=60, creationflags=NO_WINDOW)
    except (OSError, subprocess.SubprocessError):
        return None
    return done.stdout if done.returncode == 0 else ""


def main(argv):
    unknown = [a for a in argv[1:] if a.startswith("--") and a not in FLAGS]
    if unknown:
        print("REFUSING: %s is not a flag this reads (%s) - an ignored argument runs the "
              "default, which drives every plant" % (", ".join(unknown), ", ".join(FLAGS)))
        return 2
    for flag in ("--add", "--reanchor"):
        if flag in argv:
            try:
                entries = read_json(argv[argv.index(flag) + 1])
            except (IndexError, OSError, ValueError) as bad:
                print("REFUSING: %s takes a JSON file of {key: plant} (%s)" % (flag, bad))
                return 1
            code, lines = add(entries, replace=(flag == "--reanchor"))
            print(LF.join(lines))
            return code
    if "--retire" in argv:
        try:
            reasons = read_json(argv[argv.index("--retire") + 1])
        except (IndexError, OSError, ValueError) as bad:
            print("REFUSING: --retire takes a JSON file of {key: reason} (%s)" % bad)
            return 1
        code, lines = retire(reasons)
        print(LF.join(lines))
        return code
    if "--merge-from" in argv:
        code, lines = merge_from(argv[argv.index("--merge-from") + 1])
        print(LF.join(lines))
        return code
    ledger = load()
    if "--adopt" in argv:
        committed = committed_ledger()
        if committed is None:
            print("REFUSING to adopt: git could not show the committed ledger, so whether this "
                  "project adopted before is UNKNOWN - and adopting twice resets the debt")
            return 1
        ledger, problems = adopt(ledger, committed=committed)
        if problems:
            print(LF.join(["REFUSING to adopt:"] + ["   " + line for line in problems]))
            return 1
        write(ledger)
        print("adopted %d test class(es) with no plant as this project's starting debt; the "
              "ceiling only falls from here" % ledger["owed_ceiling"])
        return 0
    if "--recount" in argv:
        ledger, problems = recount(ledger, ledger)
        if problems:
            print(LF.join(["REFUSING to recount:"] + ["   " + line for line in problems]))
            return 1
        write(ledger)
        print("ceilings written from the counts")
        return 0
    only = argv[argv.index("--only") + 1] if "--only" in argv else None
    # `--owed`: THE RATCHETS ONLY, no plant driven - milliseconds, so a commit can afford it every
    # time. Driving every plant is the expensive half and belongs where time is budgeted; the
    # commit gate drives each NEW plant itself (`tools/commit_gate.py`).
    owed_only = "--owed" in argv
    plants = ledger.get("plants") or {}
    # NOTHING TO DRIVE IS NOT A PASS - for DRIVING. The ratchets are another question: a project
    # that installed none of the bundle's tests and proves its own another way has an empty ledger
    # and no guard class owing a plant, and every commit was refused over it - measured installing
    # into CTRMap. `--owed` asks the ratchets, which count the guard classes themselves.
    if not plants and not owed_only:
        print("no plants recorded - nothing to drive, and that is not a pass")
        return 1

    problems, driven = [], 0
    for key in sorted(plants):
        if owed_only or (only and only not in key):
            continue
        entry = plants[key]
        for field in ("file", "old", "new", "site", "why"):
            if field not in entry:
                problems.append("%s has no `%s`" % (key, field))
        if entry.get("site") not in SITES:
            problems.append("%s: site must be one of %s" % (key, list(SITES)))
        if len(str(entry.get("why", ""))) < MIN_REASON:
            problems.append("%s: `why` is %d characters; %d are required - a reason under the "
                            "floor is a label" % (key, len(str(entry.get("why", ""))), MIN_REASON))
        if problems and problems[-1].startswith(key):
            continue
        reddened, detail = drive_plant(key, entry)
        driven += 1
        print("  %-6s %s" % ("red" if reddened else "PASSED", key))
        if not reddened:
            print("         %s" % detail)
            problems.append("%s did NOT redden - %s" % (key, detail))

    print()
    print("%d plant(s) driven" % driven)
    lines, trouble = ratchet_problems(ledger)
    print(LF.join(lines))
    problems.extend(trouble)
    for key, matches in stale_anchors(ledger):
        problems.append("%s: its anchor matches %d time(s), not once - re-anchor it in the same "
                        "change with `--reanchor`, driven red" % (key, matches))

    if problems:
        print()
        print("REFUSING: a plant that does not redden is a guard nobody has seen fail.")
        for line in problems:
            print("   %s" % line)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
