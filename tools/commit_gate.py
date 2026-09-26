# -*- coding: utf-8 -*-
"""THE COMMIT MESSAGE, ASKED WHAT README SECTIONS 2 AND 14 SAY A COMMIT GATE ASKS.

Install as the `commit-msg` hook's body:

    .githooks/commit-msg:   exec python tools/commit_gate.py "$1"
    git config core.hooksPath .githooks

WHY IT SHIPS AS CODE. The README described every one of these refusals in prose, and the project
this bundle was extracted from had built them - so the next project read the prose and began
again. Each has a bill attached, from that project's history:

  fix -> guard        a commit whose subject is a FIX must touch a place a guard lives (`tests/`,
                      `tools/`, `.githooks/`, `.claude/hooks/`), or say `No-guard: <why>`. "Producer
                      with no consumer" was filed in seven consecutive audits because nothing
                      asked this.
  Guard: <kind>       a fix names what it built - refusal, detector or convention - with a CLASS,
                      not a label (60+ characters). A detector or convention also says
                      `No-refusal: <why the point of action cannot refuse>` (80+). A gate that reads
                      prose gets prose: a five-hundred-character `Guard: refusal` had one clause
                      with a tool behind it and one of rhetoric.
  Checker: resolves   `Checker: tools/x.py :: tests/test_x.py::TheClass` - the file exists, git
                      tracks it, it EXITS 0 now, the class exists and names the module, and the
                      plant ledger holds an `instance` AND an `elsewhere` plant for it.
  a new plant         a fix records a NEW plant in `tests/plants.json`, and every new one is DRIVEN
                      here - planted, its one test watched red, restored - before the commit lands.
  held plants         every plant HEAD already held whose planted file or named test this commit
                      changes is DRIVEN again, on every commit, fix or not. A plant proven once can
                      be emptied by the next edit to its own file, and nothing else drives it.
  Deferred-gap:       a message that ADMITS work outstanding ("still owed", "not done", "I have
                      not") carries `Deferred-gap: <80+ characters why the run could not close
                      it>`. README section 14, FIND IT AND FILE IT. Admissions only, measured: the
                      promise vocabulary too refused 5 of 23 honest commits on one project.
  N tests             a count in the message matches the last recorded run, that run passed, and
                      it measured THIS tree - `require_build.subject_digest`, which RAISES when it
                      cannot look, and a subject that cannot be computed REFUSES. The first version
                      of this passed on an unknown subject, one line from the check it sat beside.
  Co-Authored-By      when a project requires the trailer, it is a flush-left line of the SHAPE
                      `Co-Authored-By: Claude <model> <noreply@anthropic.com>` - never one model's
                      exact string, which refused every correct trailer from any other version and
                      left a false one as the only way through.

EVERY MARKER COUNTS ONLY AT THE START OF A LINE. `marker in message` is satisfied by prose that
DESCRIBES the marker, so the commit adding a rule once exempted itself by explaining it.

Exit 0, or 1 with every reason on stderr.
"""
import ast
import io
import json
import os
import re
import subprocess
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

LF = chr(10)

#: No console window for a child whose output is captured - Windows opens one for every
#: console process unless told otherwise, and a gate that blinks windows at every commit
#: is one somebody removes.
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0


def _root():
    """The repository root - the nearest ancestor of this file holding `.git` or `.githooks`."""
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

#: Where a guard can live. A fix touching none of these carried nothing that would catch it again.
#: ... and every folder a hook is asked from, which `guard_paths` adds: the bundle's hooks may
#: sit beside a stack of the project's own (`tools/bundle_hooks.py`).
GUARD_PATHS = ("tests/", "tools/", ".githooks/")

#: The suite runner's record: {"ran": N, "rc": 0, "subject": "<require_build.subject_digest>"}.
RECORD = ".last-suite-run"

#: The plant ledger (see `tools/prove_plants.py`).
LEDGER = "tests/plants.json"

#: The required trailer's SHAPE, or None when the project requires none.
COAUTHOR_SHAPE = re.compile(r"^Co-Authored-By: Claude [^<>\n]*\S <noreply@anthropic\.com>\s*$",
                            re.M)

#: Paths the subject digest leaves out - a status page a publisher rewrites, read by no test.
#: Keep it EMPTY unless the project has declared such a path somewhere a reviewer sees it.
SUBJECT_EXCLUDE = ()

#: Adapted per project: see ADAPT.md.
ADAPT = ("GUARD_PATHS", "RECORD", "LEDGER", "COAUTHOR_SHAPE", "SUBJECT_EXCLUDE")

GUARD_KINDS = ("refusal", "detector", "convention")
MIN_GUARD_REASON = 60
MIN_REASON = 80
GIT_TIMEOUT = 120
CHECKER_TIMEOUT = 900

#: `N tests` - a count the message claims. Quoted spans are blanked first: a number QUOTED from
#: somewhere else is a mention, not a claim this commit is making.
_TEST_CLAIM = re.compile(r"([0-9][0-9,]*)[ \t]+tests\b")
_QUOTED = re.compile(r"\*[^*]{1,400}?\*|" + chr(34) + r"[^" + chr(34) + r"]{1,400}?" + chr(34)
                     + r"|`[^`]{1,400}?`", re.S)
_CHECKER = re.compile(r"^Checker:\s*(\S+)\s*::\s*(\S+?)::(\w+)\s*$", re.M)


def body_of(message):
    """The message without the `#` lines git strips."""
    return LF.join(line for line in (message or "").splitlines() if not line.startswith("#"))


def marker_reason(message, marker):
    """The text after `marker` on the line that STARTS with it, or None when no line does."""
    for line in body_of(message).splitlines():
        stripped = line.strip()
        if stripped.lower().startswith(marker.lower()):
            return stripped[len(marker):].strip()
    return None


def is_fix(message):
    subject = next((ln for ln in body_of(message).splitlines() if ln.strip()), "")
    return subject.lower().startswith("fix")


def _git(args, text=True):
    try:
        return subprocess.run(["git"] + list(args), cwd=ROOT, capture_output=True, text=text,
                              timeout=GIT_TIMEOUT, creationflags=NO_WINDOW)
    except subprocess.TimeoutExpired:
        return subprocess.CompletedProcess(args, 124, "" if text else b"",
                                           "git did not answer in %ds" % GIT_TIMEOUT)


def staged_files():
    """What this commit touches, asked of git. A git that cannot answer is UNKNOWN, not empty."""
    done = _git(["diff", "--cached", "--name-only"])
    if done.returncode != 0:
        return None
    return [ln.strip().replace(chr(92), "/") for ln in done.stdout.splitlines() if ln.strip()]


def fix_touches_a_guard(message, files):
    if not is_fix(message) or marker_reason(message, "No-guard:") is not None:
        return []
    if files is None:
        return ["what this commit touches could not be read from git, so whether a fix carries "
                "a guard is UNKNOWN"]
    paths = guard_paths()
    if any(name.startswith(paths) for name in files):
        return []
    return ["this fixes something and touches none of %s - nothing new would catch it a second "
            "time. Add the guard, or say `No-guard: <why>` where it stays in the log."
            % ", ".join(paths)]


def guard_paths():
    """`GUARD_PATHS` and every hook folder - found, because the bundle's may not be `.claude/hooks`."""
    return tuple(GUARD_PATHS) + tuple(folder + "/" for folder in _bundle_hooks().hook_folders(ROOT)
                                      if folder + "/" not in GUARD_PATHS)


def _bundle_hooks():
    here = os.path.dirname(os.path.abspath(__file__))
    if here not in sys.path:
        sys.path.insert(0, here)
    import bundle_hooks
    return bundle_hooks


def guard_claim(message):
    """A fix says WHICH of the three it built, and why that closes the class."""
    if not is_fix(message) or marker_reason(message, "No-guard:") is not None:
        return []
    line = marker_reason(message, "Guard:")
    if line is None:
        return ["this fix never says what it built. Add `Guard: refusal -- <the CLASS this makes "
                "impossible>`. A detector reports the wreckage and a convention asks somebody to "
                "remember; only a refusal closes a class."]
    kind = next((k for k in GUARD_KINDS if line.lower().startswith(k)), None)
    if kind is None:
        return ["`Guard: %s` is not one of %s" % (line.split(" ")[0], ", ".join(GUARD_KINDS))]
    reason = line[len(kind):].lstrip(" -\t")
    if len(reason) < MIN_GUARD_REASON:
        return ["the Guard line names a kind and not a CLASS: %d characters is a label (%d+)"
                % (len(reason), MIN_GUARD_REASON)]
    if kind != "refusal":
        why = marker_reason(message, "No-refusal:")
        if why is None or len(why) < MIN_REASON:
            return ["`Guard: %s` is not a class closer. Build the refusal, or say `No-refusal: "
                    "<why the point of action cannot refuse, %d+ characters>`." % (kind,
                                                                                  MIN_REASON)]
    return []


def _defines(test_file, class_name, module):
    try:
        with io.open(os.path.join(ROOT, test_file), encoding="utf-8") as handle:
            tree = ast.parse(handle.read())
    except (OSError, SyntaxError):
        return "%s cannot be read" % test_file
    for node in ast.walk(tree):
        if isinstance(node, ast.ClassDef) and node.name == class_name:
            names = {n.id for n in ast.walk(node) if isinstance(n, ast.Name)}
            return None if module in names else ("%s never mentions %s - a checker the suite does "
                                                 "not drive is a convention" % (class_name, module))
    return "%s does not define %s" % (test_file, class_name)


def checker_resolves(message, execute=True):
    """The `Checker:` line of a `Guard: refusal` resolves: README section 2's two conditions
    (exists and committed; exits clean now) and the plant ledger's two sites."""
    if not is_fix(message) or marker_reason(message, "No-guard:") is not None:
        return []
    line = marker_reason(message, "Guard:") or ""
    if not line.lower().startswith("refusal") or marker_reason(message, "No-checker:"):
        return []
    found = _CHECKER.search(body_of(message))
    if not found:
        return ["the Guard line names no checker. Add `Checker: tools/<tool>.py :: "
                "tests/test_<x>.py::<TestClass>`, or `No-checker: <why>`."]
    tool, test_file, class_name = found.groups()
    out = []
    if not os.path.isfile(os.path.join(ROOT, tool)):
        out.append("%s does not exist" % tool)
    elif _git(["ls-files", "--error-unmatch", "--", tool]).returncode != 0:
        out.append("%s is not tracked by git - a guard that is not committed does not exist" % tool)
    elif execute and not os.environ.get("BUNDLE_COMMIT_GATE_DEPTH"):
        env = dict(os.environ, BUNDLE_COMMIT_GATE_DEPTH="1")
        try:
            done = subprocess.run([sys.executable, tool], cwd=ROOT, capture_output=True,
                                  text=True, env=env, timeout=CHECKER_TIMEOUT,
                                  creationflags=NO_WINDOW)
            if done.returncode != 0:
                out.append("%s exits %d, so the class is NOT closed now" % (tool, done.returncode))
        except subprocess.TimeoutExpired:
            out.append("%s did not finish in %ds" % (tool, CHECKER_TIMEOUT))
    why = _defines(test_file, class_name, os.path.splitext(os.path.basename(tool))[0])
    if why:
        out.append(why)
    try:
        with io.open(os.path.join(ROOT, LEDGER), encoding="utf-8") as handle:
            plants = json.load(handle).get("plants")
    except (OSError, ValueError):
        plants = None
    if plants is None:
        out.append("%s cannot be read, so whether this checker was watched failing is UNKNOWN"
                   % LEDGER)
    else:
        key = "%s::%s::" % (os.path.basename(test_file), class_name)
        sites = {e.get("site") for k, e in plants.items() if k.startswith(key)}
        for needed in ("instance", "elsewhere"):
            if needed not in sites:
                out.append("no `%s` plant names %s - the elsewhere one is the plant that cannot "
                           "be written instead of built" % (needed, class_name))
    return out


def deferred_gap(message):
    """An ADMISSION of outstanding work carries `Deferred-gap:` with 80+ characters of why."""
    why = marker_reason(message, "Deferred-gap:")
    if why is not None:
        return [] if len(why) >= MIN_REASON else [
            "`Deferred-gap:` carries %d characters; a deferral that does not say why the run "
            "could not close it is 'later' (%d+)" % (len(why), MIN_REASON)]
    hooks = _bundle_hooks().path(ROOT)
    if hooks not in sys.path:
        sys.path.insert(0, hooks)
    try:
        import guard_promise
    except ImportError as exc:
        return ["guard_promise cannot be imported (%s), so whether this message admits "
                "outstanding work is UNKNOWN" % exc]
    # THE TRAILERS ONLY, BY NAME. Stripping any `Word:` line was the cheapest way past this:
    # begin the admission with `Note:` and it vanished before it was read.
    prose = LF.join(line for line in body_of(message).splitlines()
                    if not re.match(r"^\s*(Co-Authored-By|Guard|Checker|No-guard|No-checker|"
                                    r"No-refusal|Deferred-gap):", line, re.I))
    found = [s for kind, s, _m in guard_promise.outstanding(prose) if kind == "admission"]
    if not found:
        return []
    return ["the message admits work outstanding and files it nowhere it can be argued with: %s "
            "- do it in this commit, or say `Deferred-gap: <why the run that found it could not "
            "close it, %d+ characters>`" % (found[0][:160], MIN_REASON)]


def coauthor(message):
    if COAUTHOR_SHAPE is None or COAUTHOR_SHAPE.search(body_of(message)):
        return []
    return ["the message carries no co-author trailer of the shape `Co-Authored-By: Claude "
            "<model> <noreply@anthropic.com>` on a line of its own"]


def test_claims(message):
    blanked = _QUOTED.sub(lambda m: re.sub(r"[^\n]", " ", m.group(0)), body_of(message))
    return sorted({int(n.replace(",", "")) for n in _TEST_CLAIM.findall(blanked)})


def test_counts(message):
    """A claimed count matches the last run, that run passed, and it measured THIS tree."""
    claims = test_claims(message)
    if not claims:
        return []
    try:
        with io.open(os.path.join(ROOT, RECORD), encoding="utf-8") as handle:
            run = json.load(handle)
    except (OSError, ValueError):
        return ["the message claims %s tests and no suite run is recorded in %s"
                % (claims, RECORD)]
    out = ["the message claims %d tests; the last run said %s" % (c, run.get("ran"))
           for c in claims if c != run.get("ran")]
    if run.get("rc") != 0:
        out.append("the message quotes the last run, and that run FAILED (exit %s)" % run.get("rc"))
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import require_build
    try:
        now = require_build.subject_digest(ROOT, exclude=SUBJECT_EXCLUDE)
    except require_build.NothingToDigest as exc:
        return out + ["which tree this commit holds cannot be computed (%s) - an UNKNOWN subject "
                      "is not an unchanged one" % exc]
    if run.get("subject") != now:
        out.append("the last recorded run measured %s code, not this tree - run the suite again "
                   "or drop the count" % ("DIFFERENT" if run.get("subject") else "unrecorded"))
    return out


def _plant_keys(payload, key=None):
    """The plant names in a ledger: the keys of a dict of plants, or `key` of each in a list."""
    plants = payload.get("plants") if isinstance(payload, dict) else None
    if isinstance(plants, dict):
        return set(plants)
    if isinstance(plants, list) and key:
        return {p[key] for p in plants if isinstance(p, dict) and isinstance(p.get(key), str)}
    return set()


def new_plant_keys(ledger=LEDGER, key=None):
    """Plant keys in the index that HEAD does not have, or None when git cannot say."""
    def keys(spec):
        done = _git(["show", spec], text=False)
        if done.returncode != 0:
            return set()
        try:
            return _plant_keys(json.loads(done.stdout.decode("utf-8")), key)
        except (ValueError, UnicodeDecodeError):
            return None
    staged, head = keys(":" + ledger), keys("HEAD:" + ledger)
    if staged is None or head is None:
        return None
    return sorted(staged - head)


#: Where a project records its install decisions - `_tests.ledger` among them.
DECLARATIONS = os.path.join(".claude", "bundle-install.json")

#: How long proving ONE plant of a project's own ledger may take: a Java plant rebuilds the tree
#: and runs a suite.
PROJECT_PROVE_TIMEOUT = 3600


def project_ledger():
    """(path, key, prove argv) of the ledger a project proves its OWN tests with, or None.

    ONE PROJECT'S FIXES ARE PROVEN IN ITS OWN LEDGER. Measured installing into CTRMap, 2026-09-25:
    its Java tests are proven by `tools/guard/plants.json` and driven by `tools/guard/replant.py`,
    and this gate - knowing only `LEDGER` - refused the first fix committed after the install, one
    carrying two plants that both reddened. Every fix in such a project would have been refused,
    and a gate that refuses honest work is one somebody removes. Declared in `_tests.ledger`:
    `{"path": ..., "key": ..., "prove": [argv naming "{key}"]}`, where `prove` exits 0 only when
    the plant reddened its test. `check_install` refuses a malformed one.
    """
    try:
        with io.open(os.path.join(ROOT, DECLARATIONS), encoding="utf-8") as handle:
            held = json.load(handle)
    except (OSError, ValueError):
        return None
    tests = held.get("_tests") if isinstance(held, dict) else None
    ledger = tests.get("ledger") if isinstance(tests, dict) else None
    if not isinstance(ledger, dict):
        return None
    path, key, prove = ledger.get("path"), ledger.get("key"), ledger.get("prove")
    if not (isinstance(path, str) and isinstance(key, str) and isinstance(prove, list) and prove
            and all(isinstance(a, str) for a in prove) and any("{key}" in a for a in prove)):
        return None
    return path, key, prove


def _prove_in_project(prove, key):
    """(red?, detail) - the project's own command, run on ONE plant of its own ledger."""
    argv = [a.replace("{key}", key) for a in prove]
    try:
        done = subprocess.run(argv, cwd=ROOT, capture_output=True, text=True,
                              timeout=PROJECT_PROVE_TIMEOUT, creationflags=NO_WINDOW)
    except subprocess.TimeoutExpired:
        return False, "not proven within %ds" % PROJECT_PROVE_TIMEOUT
    except OSError as exc:
        return False, "could not run %s: %s" % (argv[0], exc)
    tail = (done.stdout + done.stderr).strip().splitlines()
    return done.returncode == 0, (tail[-1] if tail else "exit %d" % done.returncode)


def fix_has_a_plant(message, keys=None, drive=None, own=None, added=None, prove=None):
    """A fix records a NEW defect, and every new one is watched reddening its test here - in this
    bundle's ledger, or in the one the project proves its own tests with (`project_ledger`)."""
    if not is_fix(message) or marker_reason(message, "No-guard:") is not None:
        return []
    keys = new_plant_keys() if keys is None else keys
    own = project_ledger() if own is None else own
    if own and added is None:
        added = new_plant_keys(own[0], own[1])
    added = added if own else []
    if keys is None or added is None:
        return ["which plants this commit adds cannot be read from git - UNKNOWN is not none"]
    if not keys and not added:
        return ["this fixes something and records no NEW plant in %s%s - a defect written down as "
                "an exact substitution and the ONE test that must go red"
                % (LEDGER, " or in the project's own %s" % own[0] if own else "")]
    if keys and drive is None:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import prove_plants
        with io.open(os.path.join(ROOT, LEDGER), encoding="utf-8") as handle:
            plants = json.load(handle)["plants"]

        def drive(key):
            return prove_plants.drive_plant(key, plants[key])
    if added and prove is None:
        def prove(key):
            return _prove_in_project(own[2], key)
    out = []
    for key, driver in [(k, drive) for k in keys] + [(k, prove) for k in added]:
        try:
            red, detail = driver(key)
        except Exception as exc:                     # noqa: BLE001 - a broken plant is a NO
            red, detail = False, "%s: %s" % (type(exc).__name__, exc)
        if not red:
            out.append("the new plant %s does not redden its test: %s" % (key, str(detail)[:200]))
    return out


def head_plants(ledger=LEDGER):
    """{key: entry} of the plants HEAD's ledger holds - {} when HEAD holds none, None when git
    cannot say."""
    done = _git(["show", "HEAD:" + ledger], text=False)
    if done.returncode != 0:
        return {}
    try:
        plants = json.loads(done.stdout.decode("utf-8")).get("plants")
    except (ValueError, UnicodeDecodeError, AttributeError):
        return None
    return plants if isinstance(plants, dict) else {}


def held_plant_keys(files, held=None):
    """The plants HEAD already held whose planted file, or whose named test file, this commit
    changes - None when either cannot be read.

    A PLANT PROVEN ONCE IS NOT PROVEN FOREVER. It was watched red against the code of the day it
    was recorded, and `fix_has_a_plant` drives only NEW plants. A later change to the file it
    plants into, or to the test it names, can leave it planting a defect nothing notices any more:
    a rule moved to a function the test now reaches instead, a second check added beside the first,
    a test whose example stopped moving. Measured on the project this bundle came from, 2026-09-25:
    of 729 held plants, 10 no longer reddened - one of them recorded red that morning and emptied
    by the next edit to its own file - and in seven of the ten the change that did it touched the
    plant's own file. Nothing had driven any of them since the day each was added.
    """
    held = head_plants() if held is None else held
    if files is None or held is None:
        return None
    changed = {f.replace(chr(92), "/") for f in files}
    names = {f.rsplit("/", 1)[-1] for f in changed}
    return sorted(key for key, entry in held.items()
                  if isinstance(entry, dict)
                  and (entry.get("file") in changed or key.split("::", 1)[0] in names))


def _own_entries(held, key):
    """{id: entry} of a project's own ledger, keyed or listed - None when it is neither."""
    if isinstance(held, dict):
        return {k: e for k, e in held.items() if isinstance(e, dict)}
    if isinstance(held, list):
        return {e[key]: e for e in held if isinstance(e, dict) and isinstance(e.get(key), str)}
    return None


def held_own_plant_keys(files, own=None, held=None):
    """The plants of the project's OWN ledger (`_tests.ledger`) that HEAD held, whose planted file
    this commit changes or whose `suite` is the name of a file it changes - [] when the project
    declares no such ledger, None when HEAD's copy of it cannot be read.

    ONE PROJECT'S TESTS ARE PROVEN IN ITS OWN LEDGER, and those plants go vacuous exactly as the
    bundle's do. Measured on CTRMap, 2026-09-25: 221 plants over 95 files, one driven in 43 s with
    a rebuild and 16 s without - so a commit touching its busiest file drives about eight minutes
    of them, and the whole ledger about two hours.
    """
    own = project_ledger() if own is None else own
    if not own:
        return []
    if files is None:
        return None
    path, key, _prove = own
    if held is None:
        done = _git(["show", "HEAD:" + path], text=False)
        if done.returncode != 0:
            return []
        try:
            held = json.loads(done.stdout.decode("utf-8")).get("plants")
        except (ValueError, UnicodeDecodeError, AttributeError):
            return None
    entries = _own_entries(held, key)
    if entries is None:
        return None
    changed = {f.replace(chr(92), "/") for f in files}
    stems = {os.path.splitext(f.rsplit("/", 1)[-1])[0] for f in changed}
    return sorted(ident for ident, entry in entries.items()
                  if entry.get("file") in changed or entry.get("suite") in stems)


def held_plants_redden(files, keys=None, drive=None, own_keys=None, prove=None):
    """Every held plant this commit could have emptied, driven again - on EVERY commit, in the
    bundle's ledger and in the one the project proves its own tests with.

    NOT ONLY A FIX. The change that empties a plant is usually not one: a refactor that moves a
    rule out from under its plant, a feature that adds a second check beside the first. Measured
    before building: 60 commits of that project would have driven a median of 43 plants each, 171
    at the ninetieth percentile, at half a second a plant.
    """
    keys = held_plant_keys(files) if keys is None else keys
    own = project_ledger()
    own_keys = held_own_plant_keys(files, own) if own_keys is None else own_keys
    if keys is None or own_keys is None:
        return ["which held plants this commit touches cannot be read from git - UNKNOWN is not "
                "none"]
    if keys and drive is None:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import prove_plants
        with io.open(os.path.join(ROOT, LEDGER), encoding="utf-8") as handle:
            plants = json.load(handle)["plants"]

        def drive(key):
            if key not in plants:
                return True, "not in the ledger being committed"
            return prove_plants.drive_plant(key, plants[key])
    if own_keys and prove is None:
        try:
            with io.open(os.path.join(ROOT, own[0]), encoding="utf-8") as handle:
                now = _own_entries(json.load(handle).get("plants"), own[1]) or {}
        except (OSError, ValueError, AttributeError, TypeError):
            now = None

        def prove(key):
            if now is None:
                return False, "the project's own ledger %s cannot be read" % own[0]
            if key not in now:
                return True, "not in the ledger being committed"
            return _prove_in_project(own[2], key)
    out = []
    for key, driver in [(k, drive) for k in keys] + [(k, prove) for k in own_keys]:
        try:
            red, detail = driver(key)
        except Exception as exc:                     # noqa: BLE001 - a broken plant is a NO
            red, detail = False, "%s: %s" % (type(exc).__name__, exc)
        if not red:
            out.append("the held plant %s no longer reddens its test after this change: %s - "
                       "re-anchor it where the defect now lives (`python tools/prove_plants.py "
                       "--reanchor <file>`), or make the test notice it again. A plant that "
                       "cannot redden proves nothing." % (key, str(detail)[:200]))
    return out


#: ASKED OF THE MESSAGE BEFORE GIT IS STARTED. `.claude/hooks/guard_command_rules.py` hands every
#: `git commit`'s message here, so a message the git hook would refuse is refused before the
#: pre-commit gate spends its time reaching the same answer. The two checks that read what is
#: STAGED - a fix touching a guard, a fix recording a plant - stay with the git hook: a command
#: like `git add x && git commit` stages after this has run. The hook that asks this also refuses
#: a commit that would skip the git hooks, so those two cannot be skipped either.
AT_COMMIT = "message_problems"


def message_problems(message, _root=None):
    """Every refusal that needs nothing but the message and the recorded run."""
    return (guard_claim(message) + deferred_gap(message) + coauthor(message)
            + test_counts(message) + checker_resolves(message))


def problems(message, files=None, keys=None, drive=None, execute=True, held=None, redrive=None):
    """Every refusal, cheapest first; the plant drives run last and only when the rest pass."""
    files = staged_files() if files is None else files
    cheap = (fix_touches_a_guard(message, files) + guard_claim(message) + deferred_gap(message)
             + coauthor(message) + test_counts(message)
             + checker_resolves(message, execute=execute))
    return cheap or (fix_has_a_plant(message, keys=keys, drive=drive)
                     + held_plants_redden(files, keys=held, drive=redrive))


def main(argv):
    if len(argv) < 2:
        sys.stdout.write(__doc__)
        return 2
    try:
        with io.open(argv[1], encoding="utf-8") as handle:
            message = handle.read()
    except OSError as exc:
        sys.stderr.write("REFUSING THE COMMIT: the message cannot be read (%s)%s" % (exc, LF))
        return 1
    found = problems(message)
    for line in found:
        sys.stderr.write("REFUSING THE COMMIT: %s%s" % (line, LF))
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
