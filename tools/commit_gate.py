# -*- coding: utf-8 -*-
"""THE COMMIT MESSAGE, ASKED WHAT README SECTIONS 2 AND 14 SAY A COMMIT GATE ASKS.

Install as the `commit-msg` hook's body:

    .githooks/commit-msg:   exec python tools/commit_gate.py "$1"
    git config core.hooksPath .githooks

WHY IT SHIPS AS CODE. The README described every one of these refusals in prose, and the project
this bundle was extracted from had built them - so the next project read the prose and began
again. Each has a bill attached, from that project's history:

  fix -> guard        a commit whose subject is a FIX must touch a place a guard lives (`tests/`,
                      `tools/`, `.githooks/`, `.claude/hooks/`), or say `No-guard: <why>` (80+). A
                      project whose own gate asks the staged DIFF passes its answer as `fix=`, so
                      the rule is still this one, asked by a better trigger. "Producer
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
  a plant leaving     a plant HEAD held live leaves `plants` only into a record the commit can
                      ask again: `withdrawn` needs a flush-left `Withdraws: <key> -- <reason>` line
                      (80+), a change to the test file it named, and that test GONE from what the
                      commit records; `retired` is driven and its test must run to a pass; and a
                      plant that simply vanishes is refused. Deleting a guard's test and its plants
                      is the cheapest way past the whole ledger.
  Deferred-gap:       a message that ADMITS work outstanding ("still owed", "not done", "I have
                      not") carries `Deferred-gap: <80+ characters why the run could not close
                      it>`. README section 14, FIND IT AND FILE IT. Admissions only, measured: the
                      promise vocabulary too refused 5 of 23 honest commits on one project.
  N tests             a count in the message matches the last recorded run, that run passed, and
                      it measured THIS tree - `require_build.subject_digest`, which RAISES when it
                      cannot look, and a subject that cannot be computed REFUSES. The first version
                      of this passed on an unknown subject, one line from the check it sat beside.
  Co-Authored-By      when a project requires the trailer, it is a flush-left line of the SHAPE
                      `Co-Authored-By: Claude [<model>] <noreply@anthropic.com>` - never one model's
                      exact string, which refused every correct trailer from any other version and
                      left a false one as the only way through.

EVERY MARKER COUNTS ONLY AT THE START OF A LINE. `marker in message` is satisfied by prose that
DESCRIBES the marker, so the commit adding a rule once exempted itself by explaining it. And every
ESCAPE carries its reason, 80 characters, through `escaped` - one floor for all of them.

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


sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root        # noqa: E402  - the project root, anchored: never counted (F216)
import bounded         # noqa: E402  - a deadline ends the child's whole tree

ROOT = _tree_root.require(__file__)

#: Where a guard can live. A fix touching none of these carried nothing that would catch it again.
#: ... and every folder a hook is asked from, which `guard_paths` adds: the bundle's hooks may
#: sit beside a stack of the project's own (`tools/bundle_hooks.py`).
#: ADAPTED FOR CTRMAP (F186): its Java suite lives in src/ctrmap/tests, and the gate's list without
#: it refused a fix that carried its test there, while tools/guard/commit_guard.py - which now asks
#: this list - allowed it. The hook folders are added by `guard_paths`.
GUARD_PATHS = ("src/ctrmap/tests/", "tests/", "tools/", ".githooks/")

#: The suite runner's record: {"ran": N, "rc": 0, "subject": "<require_build.subject_digest>"}, and "unit":
#: what `ran` counted - "tests", or "suites" for a runner that counts suites; "tests" when it does not say.
RECORD = ".last-suite-run"

#: The plant ledger (see `tools/prove_plants.py`).
LEDGER = "tests/plants.json"

#: The required trailer's SHAPE, or None when the project requires none.
COAUTHOR_SHAPE = re.compile(r"^Co-Authored-By: Claude(?: [^<>\n]*\S)? <noreply@anthropic\.com>\s*$",
                            re.M)

#: Paths the subject digest leaves out - a status page a publisher rewrites, read by no test.
#: Keep it EMPTY unless the project has declared such a path somewhere a reviewer sees it.
SUBJECT_EXCLUDE = ()

#: Adapted per project: see the bootstrap's ADAPT.md.
ADAPT = ("GUARD_PATHS", "RECORD", "LEDGER", "COAUTHOR_SHAPE", "SUBJECT_EXCLUDE")

#: The settings that ship OFF, at the value they ship at. A project turns them on; the bundle's
#: own tests turn them off again for their run (`bundle_hooks.settings_off`).
SHIPPED_OFF = {"SUBJECT_EXCLUDE": ()}

GUARD_KINDS = ("refusal", "detector", "convention")
MIN_GUARD_REASON = 60
MIN_REASON = 80
GIT_TIMEOUT = 120
CHECKER_TIMEOUT = 900

#: `N tests` or `N suites` - a count the message claims. Quoted spans are blanked first: a number QUOTED
#: from somewhere else is a mention, not a claim this commit is making. BOTH WORDS (census M.85, 2026-10-12):
#: the gate read only `tests` in a project whose battery is counted in suites, and five battery-size
#: claims - "the app passed 128 suites" among them - went into git log with nothing asked of them.
_TEST_CLAIM = re.compile(r"([0-9][0-9,]*)[ \t]+(tests|suites)\b")
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


def escaped(message, marker):
    """True when a line STARTS with the escape `marker` and says why in `MIN_REASON` characters.

    AN ESCAPE WITHOUT ITS REASON IS A SWITCH. `No-guard:` and `No-checker:` were honoured at any
    length here - `No-guard: x` let any fix through - while `No-refusal:` and `Deferred-gap:` beside
    them held 80. Read on CTRMap 2026-10-07, whose own gate ran the same escapes at no floor at all
    (its F186): every escape is asked through this one function, so none can drift from the rest."""
    why = marker_reason(message, marker)
    return why is not None and len(why) >= MIN_REASON


def is_fix(message):
    subject = next((ln for ln in body_of(message).splitlines() if ln.strip()), "")
    return subject.lower().startswith("fix")


def _asked(message, fix):
    """Whether a fix rule is asked of this commit: `fix` when the project answered it - a project
    whose gate asks the staged DIFF rather than the subject line passes its own answer, so the rule
    BODY stays one implementation however the question is triggered - else the subject line."""
    return is_fix(message) if fix is None else bool(fix)


def _git(args, text=True):
    try:
        return bounded.run(["git"] + list(args), cwd=ROOT, capture_output=True, text=text,
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


def fix_touches_a_guard(message, files, fix=None):
    """`fix`: the project's own answer to whether this commit is a fix (see `_asked`)."""
    if not _asked(message, fix) or escaped(message, "No-guard:"):
        return []
    if files is None:
        return ["what this commit touches could not be read from git, so whether a fix carries "
                "a guard is UNKNOWN"]
    paths = guard_paths()
    if any(name.startswith(paths) for name in files):
        return []
    return ["this fixes something and touches none of %s - nothing new would catch it a second "
            "time. Add the guard, or say `No-guard: <why, %d+ characters>` where it stays in the "
            "log." % (", ".join(paths), MIN_REASON)]


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


def guard_claim(message, fix=None):
    """A fix says WHICH of the three it built, and why that closes the class. `fix`: see `_asked`."""
    if not _asked(message, fix) or escaped(message, "No-guard:"):
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
    if kind != "refusal" and not escaped(message, "No-refusal:"):
        return ["`Guard: %s` is not a class closer. Build the refusal, or say `No-refusal: "
                "<why the point of action cannot refuse, %d+ characters>`." % (kind, MIN_REASON)]
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


def checker_env():
    """The environment a named checker runs in: this one, marked as run by the gate, and WITHOUT
    git's place. git hands its hook `GIT_INDEX_FILE` and its kin; a checker that inherited them
    ran every git command of every repository it built against this one's index - measured
    2026-10-07, `check_bundle.py` exited 0 by hand and 1 inside the hook. The plant driver's own
    list, `prove_plants.GIT_LOCATION`, so the two children are asked one question."""
    import prove_plants
    env = {k: v for k, v in os.environ.items() if k not in prove_plants.GIT_LOCATION}
    env["BUNDLE_COMMIT_GATE_DEPTH"] = "1"
    return env


def checker_resolves(message, execute=True, fix=None):
    """The `Checker:` line of a `Guard: refusal` resolves: README section 2's two conditions
    (exists and committed; exits clean now) and the plant ledger's two sites. `fix`: see `_asked`."""
    if not _asked(message, fix) or escaped(message, "No-guard:"):
        return []
    line = marker_reason(message, "Guard:") or ""
    if not line.lower().startswith("refusal") or escaped(message, "No-checker:"):
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
        try:
            done = bounded.run([sys.executable, tool], cwd=ROOT, capture_output=True,
                               text=True, env=checker_env(), timeout=CHECKER_TIMEOUT,
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
            "[<model>] <noreply@anthropic.com>` on a line of its own"]


def worded_claims(message):
    """[(count, word)] the message claims, `word` being `tests` or `suites`."""
    blanked = _QUOTED.sub(lambda m: re.sub(r"[^\n]", " ", m.group(0)), body_of(message))
    return sorted({(int(n.replace(",", "")), word) for n, word in _TEST_CLAIM.findall(blanked)})


def test_claims(message):
    """The counts the message claims, in either word."""
    return sorted({count for count, _ in worded_claims(message)})


def test_counts(message):
    """A claimed count matches the last run, that run passed, and it measured THIS tree."""
    claims = worded_claims(message)
    if not claims:
        return []
    try:
        with io.open(os.path.join(ROOT, RECORD), encoding="utf-8") as handle:
            run = json.load(handle)
    except (OSError, ValueError):
        return ["the message claims %s and no suite run is recorded in %s"
                % (", ".join("%d %s" % c for c in claims), RECORD)]
    unit = run.get("unit", "tests")
    out = ["the message claims %d %s, and the last run counted %s - a count in another word cannot be "
           "checked against it: say it in %s" % (c, word, unit, unit) for c, word in claims if word != unit]
    out += ["the message claims %d %s; the last run said %s" % (c, word, run.get("ran"))
            for c, word in claims if word == unit and c != run.get("ran")]
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


def unknown_exit():
    """The exit code the project's own plant driver gives for a plant it could NOT ASK on this
    machine (`_tests.ledger.unknown_exit`), or None when it declares none."""
    try:
        with io.open(os.path.join(ROOT, DECLARATIONS), encoding="utf-8") as handle:
            held = json.load(handle)
    except (OSError, ValueError):
        return None
    ledger = ((held.get("_tests") or {}) if isinstance(held, dict) else {}).get("ledger")
    code = ledger.get("unknown_exit") if isinstance(ledger, dict) else None
    return code if isinstance(code, int) and not isinstance(code, bool) and code > 1 else None


def _prove_in_project(prove, key, unknown=None):
    """(red?, detail) - the project's own command, run on ONE plant of its own ledger. `red` is
    None - UNKNOWN - when the driver exits `unknown` (its declared code for a plant it could not
    ask here) or cannot be run at all.

    COULD NOT ASK IS NOT SURVIVED. Reported by the CTRMap session, 2026-10-07: nineteen of its
    plants read the owner's game data, which exists only on the owner's machine, and its driver
    says so with its own exit code - while this read every non-zero exit as "no longer reddens"
    and told the session to re-anchor them. Re-anchoring a plant to get past a refusal is how a
    plant is made to prove less. UNKNOWN is still refused - a plant nobody drove proves nothing -
    and it says what it is, and where it can be proven."""
    argv = [a.replace("{key}", key) for a in prove]
    unknown = unknown_exit() if unknown is None else unknown
    try:
        done = bounded.run(argv, cwd=ROOT, capture_output=True, text=True,
                           timeout=PROJECT_PROVE_TIMEOUT, creationflags=NO_WINDOW)
    except subprocess.TimeoutExpired:
        return False, "not proven within %ds" % PROJECT_PROVE_TIMEOUT
    except OSError as exc:
        return None, "could not run %s: %s" % (argv[0], exc)
    tail = (done.stdout + done.stderr).strip().splitlines()
    said = tail[-1] if tail else "exit %d" % done.returncode
    if unknown is not None and done.returncode == unknown:
        return None, said
    return done.returncode == 0, said


def could_not_ask(kind, key, detail):
    """The refusal for a plant this machine could not ask - never the advice to re-anchor it."""
    return ("the %s plant %s could NOT BE ASKED on this machine: %s. That is UNKNOWN - not red, "
            "and not survived - and the commit is refused until it is driven. Make this commit "
            "where what the plant needs exists (for a plant that reads data only the owner's "
            "machine has, on that machine). Never re-anchor a plant to get past this: that is how "
            "a plant is made to prove less." % (kind, key, str(detail)[:200]))


def fix_has_a_plant(message, keys=None, drive=None, own=None, added=None, prove=None, fix=None):
    """A fix records a NEW defect, and every new one is watched reddening its test here - in this
    bundle's ledger, or in the one the project proves its own tests with (`project_ledger`).
    `fix`: see `_asked`."""
    if not _asked(message, fix) or escaped(message, "No-guard:"):
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
    out, reds, unknown = [], 0, 0
    pairs = [(k, drive) for k in keys] + [(k, prove) for k in added]
    for key, driver in pairs:
        try:
            red, detail = driver(key)
        except Exception as exc:                     # noqa: BLE001 - a broken plant is a NO
            red, detail = False, "%s: %s" % (type(exc).__name__, exc)
        reds += bool(red)
        if red is None:
            unknown += 1
            out.append(could_not_ask("new", key, detail))
        elif not red:
            out.append("the new plant %s does not redden its test: %s" % (key, str(detail)[:200]))
    say_driven("new", len(pairs), reds, unknown)
    return out


def say_driven(kind, driven, reds, unknown=0):
    """Say what a drive did, when it passes as well as when it fails.

    A SUCCESS THAT PRINTS NOTHING READS LIKE A STEP THAT NEVER RAN. Measured on the project this
    came from, 2026-09-26: a commit adding plants showed only `0 plant(s) driven` - from the
    ratchet step, which drives none by design - while this gate had driven every new plant red and
    said nothing. The count is of plants that REDDENED, never of plants asked: 'all' is said only
    when it is true.
    """
    said = "all %d red" % driven if reds == driven else "%d of %d red" % (reds, driven)
    if unknown:
        said += ", %d UNKNOWN - could not be asked here" % unknown
    sys.stdout.write("commit gate: %d %s plant(s) driven, %s%s" % (driven, kind, said, LF))


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


def held_plants_redden(files, keys=None, drive=None, own_keys=None, prove=None, clean=None):
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
        # A PLANT THIS COMMIT TAKES OUT OF THE LEDGER IS NOT DRIVEN, AND NOT COUNTED RED. It was
        # counted red here as "not in the ledger being committed", so a commit withdrawing three
        # plants printed "all N red" over three plants nothing drove. `plants_that_leave` asks
        # them what took them out instead.
        left = [key for key in keys if key not in plants]
        keys = [key for key in keys if key in plants]
        if left:
            sys.stdout.write("commit gate: %d held plant(s) leave the ledger in this commit - "
                             "asked what took them out, not driven%s" % (len(left), LF))

        def drive(key):
            return prove_plants.drive_plant(key, plants[key])

        if clean is None:
            clean = prove_plants.red_with_nothing_planted
    if own_keys and prove is None:
        try:
            with io.open(os.path.join(ROOT, own[0]), encoding="utf-8") as handle:
                now = _own_entries(json.load(handle).get("plants"), own[1]) or {}
        except (OSError, ValueError, AttributeError, TypeError):
            now = None

        def prove(key):
            if now is None:
                return None, "the project's own ledger %s cannot be read" % own[0]
            if key not in now:
                return True, "not in the ledger being committed"
            return _prove_in_project(own[2], key)
    out, reds, unknown = [], 0, 0
    # A TEST RED WITH NOTHING PLANTED MAKES EVERY PLANT ON IT RED FOR FREE - asked first, once a test
    # (prove_plants.red_with_nothing_planted). A held plant on one is counted as nothing.
    if keys and clean is not None:
        red_clean, unrun = clean(keys)
        for test in red_clean:
            out.append("the test %s is RED WITH NOTHING PLANTED, so every held plant on it reddens for free and "
                       "proves nothing - make it pass first" % test)
        for test in unrun:
            out.append("the test %s could not be run to a verdict with nothing planted - whether its plants "
                       "prove anything is UNKNOWN" % test)
    pairs = [(k, drive) for k in keys] + [(k, prove) for k in own_keys]
    for key, driver in pairs:
        try:
            red, detail = driver(key)
        except Exception as exc:                     # noqa: BLE001 - a broken plant is a NO
            red, detail = False, "%s: %s" % (type(exc).__name__, exc)
        reds += bool(red)
        if red is None:
            unknown += 1
            out.append(could_not_ask("held", key, detail))
        elif not red:
            out.append("the held plant %s no longer reddens its test after this change: %s - "
                       "re-anchor it where the defect now lives (`python tools/prove_plants.py "
                       "--reanchor <file>`), or make the test notice it again. A plant that "
                       "cannot redden proves nothing." % (key, str(detail)[:200]))
    say_driven("held", len(pairs), reds, unknown)
    return out


def _prove_plants():
    here = os.path.dirname(os.path.abspath(__file__))
    if here not in sys.path:
        sys.path.insert(0, here)
    import prove_plants
    return prove_plants


def ledger_at(spec):
    """The plant ledger at a git spec (`HEAD:<path>`, `:<path>` for the index) - {} when git
    holds none there, None when it cannot be read."""
    done = _git(["show", spec], text=False)
    if done.returncode == 124:
        return None
    if done.returncode != 0:
        return {}
    try:
        held = json.loads(done.stdout.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return None
    return held if isinstance(held, dict) else None


def index_reader():
    """`read(rel)`: the text of `rel` as this commit would record it, None when git's index holds
    no such file - raising OSError when git cannot say, because cannot-look is not absent."""
    def read(rel):
        listed = _git(["ls-files", "--cached", "--", rel])
        if listed.returncode != 0:
            raise OSError("git could not list %s in its index" % rel)
        if not listed.stdout.strip():
            return None
        shown = _git(["show", ":" + rel], text=False)
        if shown.returncode != 0:
            raise OSError("git could not show %s from its index" % rel)
        return shown.stdout.decode("utf-8", "replace")
    return read


def keyed_reason(message, marker, key):
    """The reason on the flush-left `<marker> <key> -- <reason>` line naming `key`, or None.

    FLUSH LEFT, where git keeps a trailer: an indented line is a body quoting one. And one line
    per key - a line naming another plant answers nothing about this one."""
    for line in body_of(message).splitlines():
        if not line.startswith(marker):
            continue
        named, sep, reason = line[len(marker):].partition(" -- ")
        if sep and named.strip() == key:
            return reason.strip()
    return None


def _leaving(files, head, index):
    """(head, index, {record: [keys HEAD held live that this commit moves into it]}), or a
    refusal line when the ledger cannot be read. Nothing leaves a ledger the commit leaves alone."""
    if files is not None and LEDGER not in files:
        return {}, {}, {}
    head = ledger_at("HEAD:" + LEDGER) if head is None else head
    index = ledger_at(":" + LEDGER) if index is None else index
    if head is None or index is None:
        return ("%s cannot be read at HEAD or in the index, so which plants this commit takes "
                "out of it is UNKNOWN" % LEDGER)
    live, now = head.get("plants") or {}, index.get("plants") or {}
    moved = {record: sorted(key for key in (index.get(record) or {})
                            if key in live and key not in now)
             for record in _prove_plants().LEFT}
    return head, index, moved


def plants_that_leave(message, files, head=None, index=None, read=None):
    """Every plant HEAD held live that this commit takes out of `plants` without a record the
    commit can stand behind - the cheap half: two reads of the ledger and one of each test file.

    `prove_plants.py --withdraw` exists because deleted tests left their plants naming nothing,
    and it is also the cheapest way past the whole ledger: delete a guard's test, withdraw its
    plants, and nothing counts a class that is gone. So, as the project this came from asks of a
    reopened item, each withdrawn plant is named on a flush-left `Withdraws: <key> -- <reason>`
    line where `git log` keeps it, and the commit changes the test file the plant named - a plant
    leaves in the change that removes its test. And the tool's own check is asked AGAIN, of the
    tree the commit records: a record typed into the ledger by hand is not the tool, and moving a
    plant whose test still runs into `withdrawn` would pass every other line of this.
    """
    found = _leaving(files, head, index)
    if isinstance(found, str):
        return [found]
    head, index, moved = found
    pp = _prove_plants()
    out = pp.departures(head, index)
    if moved.get("withdrawn") and files is None:
        return out + ["what this commit touches could not be read from git, so whether each "
                      "withdrawn plant left with its test is UNKNOWN"]
    read = read or index_reader()
    for key in moved.get("withdrawn") or []:
        reason = keyed_reason(message, pp.WITHDRAWS, key)
        homes = pp.test_files(key)
        if reason is None or len(reason) < MIN_REASON:
            out.append("%s is withdrawn without a flush-left `%s %s -- <reason>` line of %d+ "
                       "characters - the argument for dropping a guard belongs where `git log` "
                       "keeps it" % (key, pp.WITHDRAWS, key, MIN_REASON))
        elif not any(rel in files for rel in homes):
            out.append("%s is withdrawn, and this commit does not change %s - a plant leaves in "
                       "the change that removes its test" % (key, homes[0]))
        else:
            try:
                still = pp.test_exists(key, read=read)
            except OSError as exc:
                still = None
                out.append("whether the test %s names is gone cannot be read from git's index "
                           "(%s) - UNKNOWN is not gone" % (key, exc))
            if still:
                out.append("%s is withdrawn while the test it names is still in what this commit "
                           "records - a plant on a test that exists is re-anchored, or retired "
                           "once driven, never withdrawn" % key)
    return out


def retirements_hold(files, head=None, index=None, drive=None):
    """Every plant this commit moves from `plants` to `retired`, DRIVEN - `--retire`'s own
    question, asked again of the record the commit carries.

    `--retire` drives a plant and moves it only when its test runs and passes. A plant typed into
    `retired` by hand asked nothing, and it is the next cheapest way past `Withdraws:` once that
    is refused: call the deleted test's plant retired instead. Its test does not resolve, so it
    never runs to a pass, and it is refused here.
    """
    found = _leaving(files, head, index)
    if isinstance(found, str):
        return [found]
    _head, index, moved = found
    keys = moved.get("retired") or []
    if not keys:
        return []
    if drive is None:
        pp = _prove_plants()

        def drive(key):
            return pp.drive_plant(key, index["retired"][key])
    out, passed = [], 0
    for key in keys:
        try:
            red, detail = drive(key)
        except Exception as exc:                     # noqa: BLE001 - a broken plant is a NO
            red, detail = False, "%s: %s" % (type(exc).__name__, exc)
        if _prove_plants().verdict_label(red, detail) == "skip":
            out.append("%s is retired and declared for another platform, and its test skipped "
                       "here - it can be retired only where that test runs" % key)
        elif red:
            out.append("%s is retired and still reddens its test - it proves something, and "
                       "retiring it drops a working guard" % key)
        elif str(detail).strip() != "OK":
            out.append("%s is retired and its test did not run to a pass (%s) - a plant whose "
                       "test is gone is withdrawn, not retired" % (key, str(detail).strip()[:160]))
        else:
            passed += 1
    sys.stdout.write("commit gate: %d retired plant(s) driven, %d ran to a pass%s"
                     % (len(keys), passed, LF))
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
             + checker_resolves(message, execute=execute) + plants_that_leave(message, files))
    return cheap or (fix_has_a_plant(message, keys=keys, drive=drive)
                     + held_plants_redden(files, keys=held, drive=redrive)
                     + retirements_hold(files))


def main(argv):
    import cli_words      # a word this does not read refuses the run, before anything runs
    why = cli_words.refused(argv[1:], most=1)
    if why:
        print(why)
        return 2
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
