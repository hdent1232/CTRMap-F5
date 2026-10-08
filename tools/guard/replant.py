# -*- coding: utf-8 -*-
"""Put each recorded defect back, and require the guard that was proven against it to go RED.

WHY THIS EXISTS. "Proven by breaking" is real in this project and it is one-time: the proof lives
in a commit message and cannot be re-executed. A guard proven in one month and hollowed out in the
next looks identical to one that still works - the suite is green either way, because the thing it
was watching is gone. This re-runs the proofs.

Per plant in tools/guard/plants.json:

  1. the substitution is applied to one file, and it must match EXACTLY ONCE. A plant that matches
     twice is ambiguous and a plant that matches nothing has rotted; both are refusals, not skips.
  2. the tree is rebuilt (unless the plant says no_rebuild - a PowerShell or data file the battery
     reads at run time needs no compile);
  3. the named suite is run and must FAIL, and its output must contain the plant's must_say. Exit
     code alone is not enough: a suite that fails for an unrelated reason would otherwise be
     recorded as having caught this;
  4. the file is restored from the bytes read in step 1 and the restore is VERIFIED byte for byte.
     A runner that breaks a tree and cannot prove it put it back is worse than no runner.

The tree is rebuilt once at the end, whatever happened, so nobody is left with a planted build.

    python tools/guard/replant.py                # every plant
    python tools/guard/replant.py <id> [<id>...] # named plants only
    python tools/guard/replant.py --owed         # the two ratchets, no builds, seconds
    python tools/guard/replant.py --selftest     # the runner's own checks, no builds, no JDK

Usage note: this rebuilds the tree once per plant, so a full run is minutes, not seconds. It is a
tool you invoke deliberately. PlantLedgerTest is the cheap half that runs in every battery: it
checks the ledger's shape, that every plant still matches its file exactly once, and that the two
ratchets have not risen.
"""
import io
import json
import os
import re
import shutil
import subprocess
import sys
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)  # no console window for a captured child (F184)

LF = chr(10)
CR = chr(13)
CRLF = CR + LF
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LEDGER = os.path.join(ROOT, "tools", "guard", "plants.json")
#: THE PLATFORM'S SEPARATOR. A literal ";" is a classpath on Windows only; on Linux java read the
#: whole string as one missing path, and every suite failed to load (2026-10-07).
LIBS = os.pathsep.join(["build/classes", "lib/jogl-all.jar", "lib/gluegen-rt.jar"])
JAVA_EXE = "java.exe" if os.name == "nt" else "java"
JAVAC_EXE = "javac.exe" if os.name == "nt" else "javac"

#: THE EXIT CODE FOR "COULD NOT BE ASKED": a plant whose suite reads game data this machine does
#: not have - the owner's dump lives on their PC, never in a cloud checkout. Neither a kill (0) nor
#: a survivor (1): reading it as a survivor told the commit gate 19 guards had been emptied that
#: nobody had touched, and reading it as a kill would be a proof nobody ran (2026-10-07).
UNKNOWN = 3

#: The tokens a plant's arguments use for the owner's game data (see `expand`).
GAME_DATA = ("${PRISTINE}", "${GAMEDIR}", "${A039}", "${A013}", "${A040}", "${CODE}")


def code_path():
    """The decompressed code.bin, found as test.ps1 finds $code: CTRMAP_CODE, else beside the
    checkout, else one folder further up - the first that exists, else the first."""
    given = os.environ.get("CTRMAP_CODE")
    if given:
        return given
    near = os.path.join(os.path.dirname(ROOT), "code.bin")
    far = os.path.join(os.path.dirname(os.path.dirname(ROOT)), "code.bin")
    return near if os.path.exists(near) or not os.path.exists(far) else far


def ledger():
    with io.open(LEDGER, encoding="utf-8") as handle:
        return json.load(handle)


def jdk(adoptium=r"C:\Program Files\Eclipse Adoptium"):
    """The JDK the battery uses, or None. `adoptium` is a parameter so the self-test can ask the
    search on a machine that has that folder as on one that has not.

    In build.ps1's order first - CTRMAP_JDK, then the newest Adoptium JDK, the owner's - so on
    the owner's machine nothing changes. Then JAVA_HOME and the javac on PATH, because a Linux
    checkout has neither of the first two and its JDK is wherever the system put it: this
    answered None there, and every plant the commit gate drove read as emptied (2026-10-07).
    """
    override = os.environ.get("CTRMAP_JDK")
    if override and os.path.isdir(override):
        return override
    base = adoptium
    if os.path.isdir(base):
        found = sorted(d for d in os.listdir(base) if d.lower().startswith("jdk"))
        if found:
            return os.path.join(base, found[-1])
    home = os.environ.get("JAVA_HOME")
    if home and os.path.isfile(os.path.join(home, "bin", JAVAC_EXE)):
        return home
    javac = shutil.which("javac")
    if javac:
        return os.path.dirname(os.path.dirname(os.path.realpath(javac)))
    return None


def read(path):
    """(raw bytes, text with LF endings, whether the file is CRLF)."""
    with io.open(path, "rb") as handle:
        raw = handle.read()
    text = raw.decode("utf-8", "replace")
    return raw, (text.replace(CRLF, LF) if CRLF in text else text), (CRLF in text)


def write(path, text, crlf):
    with io.open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(text.replace(LF, CRLF) if crlf else text)


def build():
    """(ok, output). Uses the same script the battery insists on."""
    #A PLANT PUTS A DEFECT BACK ON PURPOSE, and some of those defects are the very
    #things build.ps1 now refuses to build - a feature crawling back into a window, a
    #second implementation of something the tree already does. The gate would stop the
    #planted tree from building and the plant could never be proven, so it is skipped
    #HERE and only here: the suite is what has to notice, which is the whole exercise.
    env = dict(os.environ, CTRMAP_SKIP_STRUCTURE_GATE="1")
    shell = shutil.which("powershell") or shutil.which("pwsh")
    if not shell:
        return build_without_powershell()
    done = subprocess.run([shell, "-NoProfile", "-ExecutionPolicy", "Bypass",
                           "-File", os.path.join(ROOT, "build.ps1")],
                          cwd=ROOT, capture_output=True, text=True, errors="replace", env=env, timeout=1800, creationflags=NO_WINDOW)
    out = (done.stdout or "") + (done.stderr or "")
    return ("Build OK" in out), out


def build_without_powershell():
    """(ok, output): build.ps1's compile, for a machine that has no PowerShell to run it on.

    build.ps1 stays THE build wherever PowerShell exists - this is reached only where it does not,
    which is every Linux cloud checkout, where the commit gate could otherwise drive no plant at
    all. It does exactly what build.ps1 does for a planted tree: refuse while a battery runs out of
    build/classes, compile src with --release 8 against the two JOGL jars, copy the resources.
    The structure gate is skipped here as build() always skips it for a plant. What it does NOT
    do, said: write the build stamp, whose writer (stamp.ps1) is PowerShell - so nothing here
    claims the tree was built by build.ps1.
    """
    lock = os.path.join(ROOT, "build", ".battery-running")
    if os.path.exists(lock) and not os.environ.get("CTRMAP_BUILD_ANYWAY"):
        return False, "REFUSING to build: a battery is running out of build/classes (%s)" % lock
    home = jdk()
    if not home:
        return False, "No JDK: set CTRMAP_JDK or JAVA_HOME, or put javac on PATH"
    classes = os.path.join(ROOT, "build", "classes")
    if os.path.isdir(classes):
        shutil.rmtree(classes)
    os.makedirs(classes)
    sources = []
    for folder, _dirs, names in os.walk(os.path.join(ROOT, "src")):
        sources += [os.path.join(folder, n).replace(os.sep, "/") for n in names if n.endswith(".java")]
    listing = os.path.join(ROOT, "build", "sources.txt")
    with io.open(listing, "w", encoding="ascii", errors="replace") as handle:
        handle.write(LF.join('"%s"' % s for s in sorted(sources)))
    done = subprocess.run([os.path.join(home, "bin", JAVAC_EXE), "--release", "8", "-encoding", "UTF-8",
                           "-cp", os.pathsep.join(["lib/jogl-all.jar", "lib/gluegen-rt.jar"]),
                           "-d", classes, "@" + listing],
                          cwd=ROOT, capture_output=True, text=True, errors="replace", timeout=1800, creationflags=NO_WINDOW)
    out = (done.stdout or "") + (done.stderr or "")
    if done.returncode != 0:
        return False, out + LF + "javac failed with exit code %d" % done.returncode
    shutil.copytree(os.path.join(ROOT, "src", "ctrmap", "resources"),
                    os.path.join(classes, "ctrmap", "resources"), dirs_exist_ok=True)
    return True, out + LF + "Build OK (javac, no PowerShell: no build stamp written)"


#: A planted suite that never finishes is not a proof. A suite whose main() throws
#: leaves its JOGL animator threads running and the JVM alive for ever, so "still
#: going" and "caught it" look identical from here - and the first plant that did
#: this sat for twenty minutes looking like a slow pass.
#: RAISED from 600 on 2026-09-12. WorkflowGuardsTest opens a scratch game, packs it several
#: times and now draws a zone in a real preview pane, which is minutes of honest work - and
#: a timeout shorter than the suite makes every plant on it unprovable while LOOKING like
#: the guard failed to notice. That is the worst of both: no proof, and a false one.
SUITE_TIMEOUT = 1500


#: A SUITE THAT SAYS IT FAILED HAS FAILED, whatever it exits with. Four suites printed their
#: verdict on a skip path and returned, so "FAILURES PRESENT" went out with exit 0: a plant was
#: scored SURVIVED here, and the battery passed the suite, while it printed "FAIL: the prop is
#: refused" (2026-10-07). test.ps1 judges the same line the same way; autoplant imports this.
_SAID_FAILED = re.compile(r"^FAILURES PRESENT", re.M)


def verdict(code, said):
    """The battery's verdict on one run: its exit code, or 1 when it exited 0 having printed
    FAILURES PRESENT at the start of a line. None - it never finished - stays None."""
    if code == 0 and _SAID_FAILED.search(said or ""):
        return 1
    return code


def run_suite(cls, args, java):
    """Exactly as test.ps1 runs it, flags included - and judged as it judges it (see verdict).

    This used to add -Djava.awt.headless=true, which the battery does not, and
    a suite can behave differently under it: a map load that a headless JVM
    refuses at the progress dialog SUCCEEDS with a display, and the code after
    it then runs against a loaded map instead of an empty one. A guard proven
    under one set of flags and shipped under another is not proven."""
    """(exit code or None when it never finished, everything it said)."""
    command = [java, "-Xmx4g", "-cp", LIBS, cls] + args
    try:
        done = subprocess.run(command, cwd=ROOT, capture_output=True, text=True,
                              errors="replace", timeout=SUITE_TIMEOUT, creationflags=NO_WINDOW)
    except subprocess.TimeoutExpired as late:
        out = late.stdout or ""
        err = late.stderr or ""
        if isinstance(out, bytes):
            out = out.decode("utf-8", "replace")
        if isinstance(err, bytes):
            err = err.decode("utf-8", "replace")
        return None, out + err
    said = (done.stdout or "") + (done.stderr or "")
    return verdict(done.returncode, said), said


def expand(args, pristine, gamedir):
    """${PRISTINE}/${A039}/${A013}/${A040} come out of the untouched GARC copy;
    ${GAMEDIR} is the live dump.

    They are not interchangeable and a plant has to say which it wants.
    RomFS_original_garcs holds only the archives the workspace flow needs to
    restore, so a suite that reads a/0/3/6 finds nothing there and fails for
    the wrong reason - which reads as a guard that noticed when it did not."""
    out = []
    for a in args:
        a = a.replace("${PRISTINE}", pristine).replace("${GAMEDIR}", gamedir)
        #the three archives test.ps1 hands out by name. Tokens rather than paths
        #because plants.json is a SHIPPED file and SourceSeamTest refuses a home
        #directory in one - it caught exactly that when autoplant first wrote
        #these, which is the rule doing its job.
        for name, parts in (("A039", ("a", "0", "3", "9")),
                            ("A013", ("a", "0", "1", "3")),
                            ("A040", ("a", "0", "4", "0"))):
            a = a.replace("${%s}" % name, os.path.join(pristine, *parts))
        out.append(a.replace("${CODE}", code_path()))
    return out


def owed(book):
    """The three things the ledger does not yet cover, and the suites it covers least.

    A plant marked `auto` was found by tools/guard/autoplant.py: a single-line
    mutation the suite was OBSERVED to catch. That proves the suite is not
    vacuous, which is worth knowing and is not the same claim as a hand-written
    plant, where the text put back is a defect that actually happened. Counting
    the two as one number would let a machine-generated line hide the absence of
    the real proof, so they are separate and both may only fall."""
    registered = set()
    with io.open(os.path.join(ROOT, "test.ps1"), encoding="utf-8", errors="replace") as handle:
        text = handle.read()
    for found in re.findall(r'c\s*=\s*"(ctrmap\.tests\.\w+)"', text):
        registered.add(found)
    planted = set(p["suite"] for p in book["plants"])
    by_hand = set(p["suite"] for p in book["plants"] if not p.get("auto"))
    site_only = sum(1 for p in book["plants"] if p.get("site_only"))
    auto_only = sorted(planted - by_hand)
    return (len(registered - planted), site_only, len(auto_only),
            sorted(registered - planted), auto_only)


def check_shape(book):
    """Every refusal the ledger can make without building anything. Returns a list of problems."""
    bad = []
    seen = set()
    for p in book["plants"]:
        pid = p.get("id", "<no id>")
        if pid in seen:
            bad.append("%s: two plants share an id" % pid)
        seen.add(pid)
        for field in ("id", "why", "file", "find", "replace", "suite", "must_say"):
            if field not in p:
                bad.append("%s: has no %s" % (pid, field))
        if "file" not in p or "find" not in p:
            continue
        path = os.path.join(ROOT, p["file"].replace("/", os.sep))
        if not os.path.isfile(path):
            bad.append("%s: no such file %s" % (pid, p["file"]))
            continue
        _, text, _ = read(path)
        hits = text.count(p["find"])
        if hits != 1:
            bad.append("%s: its substitution matches %s%d time(s) in %s - a plant that matches "
                       "nothing has rotted, and one that matches twice is ambiguous"
                       % (pid, "", hits, p["file"]))
        if p.get("replace") == p.get("find"):
            bad.append("%s: substitutes a thing for itself, so it plants nothing" % pid)
    bad.extend(check_args(book))
    return bad


def check_args(book):
    """A plant must run its suite with the arguments test.ps1 registers for it.

    Learned the hard way, twice in one session. A plant whose args do not match
    puts the suite in a state the battery never puts it in: MainframeShapeTest
    handed a dump root instead of "src" cannot find the window's source and
    fails six checks that have nothing to do with the planted defect, so the
    runner either credits the plant for the wrong red or refuses it for the
    wrong reason. Either way the ledger stops meaning what it says.
    """
    with io.open(os.path.join(ROOT, "test.ps1"), encoding="utf-8", errors="replace") as handle:
        text = handle.read()
    registered = {}
    for m in re.finditer(r'c\s*=\s*"(ctrmap\.tests\.\w+)"\s*;\s+a\s*=\s*@\(([^)]*)\)', text):
        #JOIN-PATH IS ONE ARGUMENT, not two. `a = @((Join-Path $pristine "a\0\1\4"))`
        #hands the suite a single path; reading it as two tokens made this rule
        #demand that a plant pass two, and a plant that obeyed handed the suite the
        #pristine DIRECTORY as argv[0] - so the suite failed on a GARC it could not
        #open, went red for the wrong reason, and the runner refused the plant as
        #NOT PROVEN. Two plants in this ledger were already shaped that way.
        spec = re.sub(r'Join-Path\s+(\$\w+|"[^"]*")\s+(\$\w+|"[^"]*")',
                      lambda j: '"%s/%s"' % (j.group(1).strip(chr(34)),
                                              j.group(2).strip(chr(34))), m.group(2))
        raw = []
        for lit, var in re.findall(r'"([^"]*)"|(\$\w+)', spec):
            raw.append(lit or var)
        registered[m.group(1)] = raw
    #the tokens a plant writes, and the test.ps1 variable each one stands for
    same = {"${PRISTINE}": "$pristine", "${GAMEDIR}": "$gamedir",
            "${A039}": "$a039", "${A013}": "$a013", "${A040}": "$a040", "${CODE}": "$code"}
    bad = []
    for p in book["plants"]:
        want = registered.get(p.get("suite"))
        if want is None:
            continue        #a suite test.ps1 does not register is its own problem
        #substituted INSIDE the token, because a joined path carries the variable
        #and the rest of the path in one string
        def name(a):
            for token, var in same.items():
                a = a.replace(token, var)
            return a
        got = [name(a) for a in p.get("args", [])]
        #"build/classes" and "build\classes" are the same directory; the rule is
        #about which arguments, not which slash
        norm = lambda xs: [x.replace(chr(92), "/") for x in xs]
        if norm(got) != norm(want):
            bad.append("%s: runs %s with %s, but test.ps1 registers it with %s - a plant has to "
                       "put the suite in the state the battery does"
                       % (p.get("id", "<no id>"), p.get("suite"), got or "no arguments", want))
    return bad


def missing_game_data(p, pristine, gamedir):
    """The game-data path a plant's suite would read and this machine does not have, or None.

    Asked from the plant's own arguments - a token from GAME_DATA, expanded - never from its
    suite's name, so a suite that starts reading the dump is asked the moment its plant says so.
    """
    raw = p.get("args", [])
    for given, done in zip(raw, expand(raw, pristine, gamedir)):
        if any(token in given for token in GAME_DATA) and not os.path.exists(done):
            return done
    return None


def replant(p, java, pristine, gamedir):
    """True when the guard noticed, False when it did not, None when it could not be asked
    (UNKNOWN: its suite reads game data this machine does not have). Restores the file whatever
    happens."""
    path = os.path.join(ROOT, p["file"].replace("/", os.sep))
    raw, text, crlf = read(path)
    if text.count(p["find"]) != 1:
        print("  REFUSED %s: its substitution no longer matches %s exactly once"
              % (p["id"], p["file"]))
        return False
    planted = text.replace(p["find"], p["replace"])
    #A SUITE HANDED A PATH THAT IS NOT THERE PASSES OR SKIPS, and a plant that then survived read
    #as a guard that stopped noticing (2026-10-07, 19 of them) - so with game data missing, only a
    #KILL counts: the section that caught it needed no game, and that is proof on any machine.
    #Anything short of one is UNKNOWN, never NOT PROVEN.
    absent = missing_game_data(p, pristine, gamedir)
    if p.get("no_rebuild") and not os.path.isdir(os.path.join(ROOT, "build", "classes")):
        #A PLANT THAT REUSES THE BUILD NEEDS ONE: on a checkout nobody had built, its suite could
        #not even load, and that read as "failed but never said it" (2026-10-07). Built clean,
        #BEFORE anything is planted, so the build is of the tree as it is.
        built, out = build()
        if not built:
            print("     the clean tree does not build, so this plant's suite cannot be run: %s"
                  % out.strip().splitlines()[-1:])
            return False
    print("  %s -> %s" % (p["id"], p["suite"]))
    ok = False
    try:
        #THE LOCK IS WRITTEN FIRST, and that order is the whole point of it. It used to be
        #written AFTER the plant, leaving a window in which a file was planted and the lock
        #still named the PREVIOUS one. PAID FOR 2026-09-22: a replant was killed in that
        #window, the lock said liveness_check.py - already clean - and the live plant was in
        #commit_guard.py, with the coupling refusal replaced by `if True`. Trusting the lock
        #would have restored the wrong file and left the guard disarmed in the tree. A lock
        #naming a file that turns out to be clean costs nothing; a lock naming the wrong file
        #is worse than none, because it is believed.
        refused = hold(path)
        if refused:
            #nothing is planted under a lock this run does not hold
            print("     NOT PLANTED: %s" % refused)
            return False
        write(path, planted, crlf)
        if not p.get("no_rebuild"):
            built, out = build()
            if not built:
                print("     the planted tree does not compile, so nothing is proven here.")
                print("     A plant must leave a tree that BUILDS - otherwise the suite never runs")
                print("     and 'it failed' means only that javac did.")
                return False
        code, said = run_suite(p["suite"], expand(p.get("args", []), pristine, gamedir), java)
        if code is None:
            print("     HUNG: %s never finished in %ds with the defect back." % (p["suite"], SUITE_TIMEOUT))
            print("     Never scored as a kill. A suite that hangs under a plant is telling you")
            print("     something - usually that a thrown section leaves non-daemon threads")
            print("     running - and that is a defect in the suite, not a proof about the guard.")
            return False
        noticed = code != 0
        named = p["must_say"] in said
        if noticed and named:
            print("     ok: red, and it said what it was watching for")
            ok = True
        elif absent:
            print("  UNKNOWN %s: %s reads game data at %s, which this machine does not have, and"
                  " nothing without it caught the plant - not proven, not refuted"
                  % (p["id"], p["suite"], absent))
            ok = None
        elif noticed:
            print("     NOT PROVEN: the suite failed but never said %r." % p["must_say"])
            print("     It may have failed for something else entirely, which would record this")
            print("     guard as holding when it does not.")
        else:
            print("     SURVIVED: the defect is back and %s still passes." % p["suite"])
    finally:
        with io.open(path, "wb") as handle:
            handle.write(raw)
        #...AND RELEASED ONLY ONCE THE RESTORE IS PROVEN. This cleared the lock before
        #reading the file back, so a restore that did not take left no lock naming the file
        #it did not take on - the one case where the lock is the only thing that knows.
        with io.open(path, "rb") as handle:
            back = handle.read()
        if back != raw:
            print("     RESTORE FAILED for %s - the tree is NOT as it was." % p["file"])
            print("     The lock is LEFT IN PLACE naming it, because nothing else now knows.")
            ok = False
        else:
            stuck = release()
            if stuck:
                print("     %s IS RESTORED and the lock could not come off: %s. Every judgement"
                      " reads the tree as UNKNOWN until it does - delete it." % (p["file"], stuck))
                raise SystemExit(2)
    return ok


#: chr(92) + "b" rather than a backslash-b literal: the first version of this line was
#: written through `python -c` inside a shell string, the escaping layer ate the
#: backslash, and the word boundary arrived as 0x08. It compiled. It matched nothing
#: but digits, and the ceiling re-measured to exactly the same number - which is what
#: a silent failure looks like from the outside.
#: A COST IS A NUMBER, and a number is not always a digit: this projects own rules say
#: "filed in SEVEN consecutive audits" and "four separate times". A digits-only test
#: called those costless, so the predicate was wrong rather than strict - and a
#: predicate that is wrong in the safe direction still teaches people to write 7.
_NUMBER = re.compile("[0-9]|" + chr(92) + "b(one|two|three|four|five|six|seven|"
                    "eight|nine|ten|eleven|twelve|twenty|thirty|forty|fifty|"
                    "hundred|thousand|million|twice|thrice|once)" + chr(92) + "b",
                    re.I)


def without_a_cost(book):
    """Plant ids whose `why` records what the defect was but not what it cost.

    RECORD TRAPS YOU PAID FOR, WITH THE COST, and the rule carries its own bill: a trap list
    without the bill attached gets ignored. This ledger IS the trap list, and the entries that
    close are the ones that say "filed in SEVEN consecutive audits" or "791 agents and 111.9M
    tokens" rather than naming a shape. A number is the cheapest possible test for that, and
    it is a ratchet rather than a ban because banning would fire on years of honest entries -
    which is how a ceiling gets raised once and never looked at again.
    """
    return sorted(p.get("id", "<no id>") for p in book.get("plants", [])
                  if not _NUMBER.search(p.get("why", "")))


def selftest():
    fails = []

    def check(cond, what):
        print(("  ok: " if cond else "  FAIL: ") + what)
        if not cond:
            fails.append(what)

    book = ledger()
    check(isinstance(book.get("plants"), list) and book["plants"],
          "the ledger holds plants (%d)" % len(book.get("plants") or []))
    check(check_shape(book) == [], "every plant matches its file exactly once: %s"
          % (check_shape(book) or "yes"))
    n_owed, n_site, n_auto, missing, auto_only = owed(book)
    check(n_owed <= book["owed_ceiling"],
          "owed %d is at or under its ceiling %d - it may only fall" % (n_owed, book["owed_ceiling"]))
    check(n_site <= book["owed_generalisation_ceiling"],
          "owed_generalisation %d is at or under its ceiling %d"
          % (n_site, book["owed_generalisation_ceiling"]))
    check(n_auto <= book["owed_real_defect_ceiling"],
          "owed_real_defect %d is at or under its ceiling %d - a machine-found plant does"
          " not discharge the debt of a real one"
          % (n_auto, book["owed_real_defect_ceiling"]))
    n_cost = len(without_a_cost(book))
    ceiling = book.get("no_cost_ceiling")
    check(ceiling is not None, "the ledger declares a no_cost_ceiling")
    check(ceiling is None or n_cost <= ceiling,
          "%d plant(s) cite no measured cost, at or under the ceiling %s - a trap list"
          " without the bill attached gets ignored, so this may only fall"
          % (n_cost, ceiling))

    # THE RATCHET ITSELF, on a scratch ledger. Reading the real count and checking it says
    # "ok" proves only that the line is printed: weaken the ratchet and it still says ok, so
    # a plant against it could not redden anything. A ceiling is proven by putting something
    # OVER it and requiring the guard to notice.
    costless = {"plants": [{"id": "a", "why": "a shape, with no bill attached"}]}
    check(len(without_a_cost(costless)) == 1,
          "a plant whose why cites no number is counted")
    priced = {"plants": [{"id": "b", "why": "filed in SEVEN consecutive audits"}]}
    check(without_a_cost(priced) == [],
          "and one that records what it cost is not - 7 is a number")

    # the runner's own refusals, on a scratch ledger rather than the real one
    fake = {"plants": [{"id": "a", "why": "w", "file": "test.ps1", "find": "zzz-not-here",
                        "replace": "x", "suite": "s", "must_say": "m"}],
            "owed_ceiling": 999, "owed_generalisation_ceiling": 999,
            "owed_real_defect_ceiling": 999, "no_cost_ceiling": 999}
    check(any("has rotted" in b for b in check_shape(fake)),
          "a plant whose text is gone is refused, not skipped")
    fake["plants"][0].update(find="param(", replace="param(")
    check(any("substitutes a thing for itself" in b for b in check_shape(fake)),
          "and a plant that substitutes a thing for itself is refused")
    fake["plants"] = [dict(fake["plants"][0], id="dup"), dict(fake["plants"][0], id="dup")]
    check(any("share an id" in b for b in check_shape(fake)), "and two plants may not share an id")

    #A MACHINE WITHOUT THE OWNER'S JDK OR GAME DATA. Every Linux cloud checkout is one: jdk()
    #answered None there and a missing dump read as an emptied guard (2026-10-07).
    check(len(LIBS.split(os.pathsep)) == 3,
          "the classpath is joined with this platform's separator (%r)" % LIBS)
    import tempfile
    home = tempfile.mkdtemp(prefix="replant-jdk-")
    os.makedirs(os.path.join(home, "bin"))
    with io.open(os.path.join(home, "bin", JAVAC_EXE), "w"):
        pass
    saved = {k: os.environ.get(k) for k in ("CTRMAP_JDK", "JAVA_HOME")}
    try:
        os.environ.pop("CTRMAP_JDK", None)
        os.environ["JAVA_HOME"] = home
        found = jdk(adoptium=os.path.join(home, "no-adoptium-here"))
    finally:
        for k, v in saved.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        shutil.rmtree(home, True)
    check(found == home, "with no Adoptium folder, the JDK JAVA_HOME names is found (found %r)" % found)
    nowhere = os.path.join(home, "no-game-here")
    check(missing_game_data({"args": ["${GAMEDIR}", "src"]}, nowhere, nowhere) == nowhere,
          "a plant whose suite reads game data this machine lacks is UNKNOWN, named by its path")
    check(missing_game_data({"args": ["src", "build/classes"]}, nowhere, nowhere) is None,
          "and a plant whose suite reads no game data is asked as before")

    #THE VERDICT IS THE BATTERY'S: a suite that printed FAILURES PRESENT and exited 0 failed
    check(verdict(0, "  FAIL: the prop is refused" + LF + "FAILURES PRESENT (1)" + LF) == 1,
          "a suite that printed FAILURES PRESENT and exited 0 has failed")
    check(verdict(0, "  ok: nothing says FAILURES PRESENT at a line's start" + LF + "ALL PASS" + LF) == 0,
          "and the words inside another line are not the verdict")
    check(verdict(3, "ALL PASS" + LF) == 3 and verdict(None, "FAILURES PRESENT" + LF) is None,
          "a non-zero exit stands, and a run that never finished stays unfinished")

    print("ALL PASS" if not fails else "FAILURES PRESENT (%d)" % len(fails))
    return 0 if not fails else 1


#: Where replant records the tree it last proved the whole ledger against. work_order
#: refuses a frozen-tree measurement when src/ no longer digests to this.
PROVEN = os.path.join(ROOT, ".last-replant")


#: The lock `.claude/hooks/guard_mutation_read.py` reads to refuse a read of a file that
#: currently carries a planted defect. Nothing wrote it before 2026-09-20, so that hook could
#: never refuse anything: a consumer with no producer.
MUTATION_LOCK = os.path.join(ROOT, ".mutation-in-flight")


def _door():
    """bundle_rules, where the lock's one door is: take_lock and release_lock (F179)."""
    sys.path.insert(0, os.path.join(ROOT, "tools"))
    import bundle_hooks
    sys.path.insert(0, bundle_hooks.path(ROOT))
    import bundle_rules
    return bundle_rules


def hold(path):
    """Take the lock naming `path` before its defect lands: None, or why it could not be taken.

    THROUGH THE DOOR (F179). This opened the lock with mode 'w', so it silently OVERWROTE a lock
    another run held - a replant beside a prove_plants run could then delete that run's lock while
    its plant was on disk - and its release was never counted, so the hooks' came-and-went check
    was blind to every plant run here. take_lock creates it exclusively and signs it with this
    process's id, which guard_suite_running reads as liveness, as it read `pid=` before.
    """
    return _door().take_lock(ROOT, os.path.relpath(path, ROOT).replace(chr(92), "/"))


def release():
    """The defect is off disk again: count the release, then remove this run's own lock. None, or
    why it still stands."""
    return _door().release_lock(ROOT)


def target_digest(book):
    """A digest over every file the ledger plants into, line endings normalised.

    Narrower than digesting whole trees and it cannot be moved by an unrelated file: this is
    the proof's actual subject. `tools/guard/magnitudes.json` is measurement output that
    changes on every reading, and digesting all of tools/ would have invalidated the proof
    each time a number was recorded.
    """
    import hashlib
    h = hashlib.sha256()
    for plant in sorted(book.get("plants") or [], key=lambda p: p.get("file", "")):
        rel = plant.get("file") or ""
        h.update(rel.encode("utf-8"))
        try:
            with open(os.path.join(ROOT, rel.replace("/", os.sep)), "rb") as handle:
                h.update(handle.read().replace(b"\r\n", b"\n"))
        except OSError:
            #: A TARGET THIS CANNOT READ IS NOT AN UNCHANGED ONE. Feeding a marker rather
            #: than skipping keeps a vanished file visible as a moved digest.
            h.update(b"<unreadable>")
    return h.hexdigest()


def record_proof(proven, not_proven):
    """Write the digest of src/ this run proved the ledger against."""
    sys.path.insert(0, os.path.join(ROOT, "tools"))
    import require_build
    import json as _json
    import time as _time
    body = {
        "at": _time.strftime("%Y-%m-%dT%H:%M:%SZ", _time.gmtime()),
        "src": require_build.tree_digest(os.path.join(ROOT, "src")),
        #: AND the files the plants actually target, which is what the proof is
        #: ABOUT. The src/ digest alone left 27 plants invisible to the gate -
        #: every plant against a hook or a guard lives under tools/, so changing
        #: one after a full run moved nothing the gate was looking at.
        "targets": target_digest(ledger()),
        #: ...and HOW MANY there were. A plant added after a full run is unproven
        #: and leaves notProven at 0, so nothing could tell.
        "plants": len(ledger().get("plants") or []),
        "proven": proven,
        "notProven": not_proven,
    }
    with io.open(PROVEN, "w", encoding="utf-8", newline=LF) as handle:
        handle.write(
            _json.dumps(body, indent=1) + LF)
    print("recorded the proof against this tree in .last-replant")


def _compare_the_ceilings(book, n_owed, n_site, n_auto, argv):
    """Record the four numbers this project acts on, and refuse a startling unexplained move.

    `--magnitude "<reason>"` explains one, and the reason is stored beside the number so the
    next comparison starts from an explained baseline rather than a surprised one.
    """
    try:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import magnitude
    except ImportError as cannotCompare:                 # pragma: no cover
        print("WARNING: cannot compare these numbers to the last run (%s)" % cannotCompare)
        return 0
    why = None
    if "--magnitude" in argv:
        why = " ".join(argv[argv.index("--magnitude") + 1:]).strip() or None
    series = (("ledger.owed", n_owed), ("ledger.owed_generalisation", n_site),
              ("ledger.owed_real_defect", n_auto),
              ("ledger.no_cost", len(without_a_cost(book))),
              ("ledger.plants", len(book.get("plants") or [])))
    worst = 0
    for name, value in series:
        ok, said = magnitude.record(name, value, why)
        if not ok:
            print(said)
            worst = 1
    return worst


def main(argv):
    book = ledger()
    if "--selftest" in argv:
        return selftest()

    n_owed, n_site, n_auto, missing, auto_only = owed(book)
    if "--owed" in argv:
        print("owed: %d registered suite(s) have no plant (ceiling %d)"
              % (n_owed, book["owed_ceiling"]))
        print("owed_generalisation: %d plant(s) proven only at their own site (ceiling %d)"
              % (n_site, book["owed_generalisation_ceiling"]))
        print("owed_real_defect: %d suite(s) whose only plant is machine-found (ceiling %d)"
              % (n_auto, book["owed_real_defect_ceiling"]))
        for name in auto_only:
            print("    auto only: %s" % name)
        for name in missing:
            print("    %s" % name)
        # A STARTLING MAGNITUDE IS A BUG REPORT, and these four numbers are the ones this
        # project acts on. One of them - the costless count - was re-measured after a change
        # meant to loosen its predicate and came back EXACTLY 103, the number it had been
        # before, because the new pattern had a 0x08 in it and matched only digits. The true
        # value was 64. The same number twice across a change that should have moved it went
        # unread for twenty minutes, because nothing was comparing.
        return _compare_the_ceilings(book, n_owed, n_site, n_auto, argv)

    bad = check_shape(book)
    if bad:
        print("THE LEDGER IS NOT USABLE:")
        for b in bad:
            print("  %s" % b)
        return 2

    java = jdk()
    if not java:
        print("No JDK found: not CTRMAP_JDK, not under C:\\Program Files\\Eclipse Adoptium, not"
              " JAVA_HOME, and no javac on PATH.")
        return 2
    java = os.path.join(java, "bin", JAVA_EXE)
    pristine = os.environ.get("CTRMAP_PRISTINE",
                              os.path.join(os.path.dirname(ROOT), "RomFS_original_garcs"))
    gamedir = os.environ.get("CTRMAP_GAMEDIR",
                             os.path.join(os.path.dirname(ROOT), "RomFS",
                                          "000400000011C400"))

    wanted = [a for a in argv[1:] if not a.startswith("-")]
    plants = [p for p in book["plants"] if not wanted or p["id"] in wanted]
    if wanted and len(plants) != len(wanted):
        print("no such plant: %s" % ", ".join(sorted(set(wanted) - set(p["id"] for p in plants))))
        return 2

    print("replanting %d defect(s); each must make its guard go red" % len(plants))
    held, lost, unknown = [], [], []
    planted = False
    try:
        for p in plants:
            verdict = replant(p, java, pristine, gamedir)
            #EVERY plant may have been built into the classes, an UNKNOWN one too - it is planted
            #and run now even with game data missing - so the tree is rebuilt whatever the verdicts
            planted = True
            (unknown if verdict is None else held if verdict else lost).append(p["id"])
    finally:
        if planted:
            print("rebuilding, so nobody is left with a planted tree")
            built, _ = build()
            print("  %s" % ("Build OK" if built else "THE REBUILD FAILED - check the tree by hand"))

    print("")
    print("%d guard(s) still notice; %d do not; %d could not be asked here"
          % (len(held), len(lost), len(unknown)))
    for pid in lost:
        print("    NOT PROVEN: %s" % pid)
    for pid in unknown:
        print("    UNKNOWN: %s" % pid)
    #A FULL RUN IS PROOF ABOUT THIS TREE; a filtered one is not, so only the full run
    #records it - and a run that could not ask some of its plants is not a full proof either.
    #work_order refuses a frozen-tree measurement when src/ has moved since.
    if not wanted and not unknown:
        record_proof(len(held), len(lost))
    print("owed: %d suite(s) with no plant; owed_generalisation: %d; owed_real_defect: %d"
          % (n_owed, n_site, n_auto))
    return 1 if lost else UNKNOWN if unknown else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
