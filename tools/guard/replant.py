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
    python tools/guard/replant.py --compiles     # every Java plant compiled planted, no tree touched

Usage note: this rebuilds the tree once per plant, so a full run is minutes, not seconds. It is a
tool you invoke deliberately. PlantLedgerTest is the cheap half that runs in every battery: it
checks the ledger's shape, that every plant still matches its file exactly once, and that the two
ratchets have not risen.
"""
import ast
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)  # no console window for a captured child (F184)

LF = chr(10)
CR = chr(13)
CRLF = CR + LF
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
import bounded         # noqa: E402  - a deadline ends the child's whole tree
ROOT = _tree_root.require(__file__)
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


#: test.ps1's sample step for the corpus suites, in its full run - `$step = if ($Quick) {..} else {"N"}`.
STEP_LINE = re.compile(r'^\$step\s*=\s*if\s*\(\$Quick\)\s*\{\s*"(\d+)"\s*\}\s*else\s*\{\s*"(\d+)"\s*\}', re.M)


def battery_step():
    """The step a full battery hands a corpus suite, read from test.ps1 - so a plant that says
    ${STEP} drives the suite as the battery does. A test.ps1 that no longer says it is refused:
    a guessed step would prove a plant against a different sample than the battery runs."""
    with io.open(os.path.join(ROOT, "test.ps1"), encoding="utf-8", errors="replace") as handle:
        m = STEP_LINE.search(handle.read())
    if m is None:
        raise SystemExit("test.ps1 no longer sets $step as replant reads it - ${STEP} cannot be expanded")
    return m.group(2)


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
    done = bounded.run([shell, "-NoProfile", "-ExecutionPolicy", "Bypass",
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
    #whether a battery holds its lock is battery_lock's answer, the one build.ps1 and test.ps1 ask: this
    #asked only whether the file was there, so a killed battery's lock blocked every plant build for good
    if not os.environ.get("CTRMAP_BUILD_ANYWAY"):
        try:
            import battery_lock
            held = battery_lock.holders(classes=True)
        except Exception as cannotAsk:          # noqa: BLE001 - unknown is refused like a live run
            held = ["UNKNOWN whether a battery is running (%s: %s)" % (type(cannotAsk).__name__, cannotAsk)]
        if held:
            return False, "REFUSING to build: a battery may be running out of build/classes - " + "; ".join(held)
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
    done = bounded.run([os.path.join(home, "bin", JAVAC_EXE), "--release", "8", "-encoding", "UTF-8",
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


#: Whether any plant in this run was built into build/classes - the full build. The fast path below never
#: touches them, so a run that only took it leaves the classes as clean as it found them.
BUILT_PLANTED = [False]


def overlaid(path, java):
    """The classpath with the planted file, compiled ALONE, in front of build/classes - or None when it
    does not compile alone against them.

    TWO FULL BUILDS A PLANT. Every Java plant rebuilt the whole tree with its defect in, and the run
    rebuilt it again to leave nothing planted - measured 2026-10-11 at about 8 minutes a plant, and a
    drive of 1,113 owed plants was days long. One file recompiled into its own folder ahead of the
    classpath is the same program with that one defect in, and the classes on disk are never touched.
    It answers only RED: what it cannot show red - a constant another class inlined, a suite that reads
    build/classes as bytecode - is asked again of the full build, so the fast path can never prove a
    plant the full build would not."""
    javac = os.path.join(os.path.dirname(java), JAVAC_EXE)
    out = tempfile.mkdtemp(prefix="replant_overlay_")
    try:
        done = bounded.run([javac, "--release", "8", "-encoding", "UTF-8", "-nowarn", "-proc:none", "-cp", LIBS,
                            "-d", out, path], cwd=ROOT, capture_output=True, text=True, errors="replace",
                           timeout=600, creationflags=NO_WINDOW)
    except (OSError, subprocess.SubprocessError):
        shutil.rmtree(out, ignore_errors=True)
        return None
    if done.returncode != 0:
        shutil.rmtree(out, ignore_errors=True)
        return None
    return os.pathsep.join([out, LIBS])


def run_suite(cls, args, java, classpath=None):
    """Exactly as test.ps1 runs it, flags included - and judged as it judges it (see verdict).

    This used to add -Djava.awt.headless=true, which the battery does not, and
    a suite can behave differently under it: a map load that a headless JVM
    refuses at the progress dialog SUCCEEDS with a display, and the code after
    it then runs against a loaded map instead of an empty one. A guard proven
    under one set of flags and shipped under another is not proven."""
    """(exit code or None when it never finished, everything it said)."""
    command = [java, "-Xmx4g", "-cp", classpath or LIBS, cls] + args
    #ON THE UNATTENDED DESKTOP, never the owner's (2026-10-11: a suite's zone loading dialog opened in the
    #middle of their screen, took the keyboard, and a window they were working in was lost with its edits).
    #The same JVM and flags, drawing on a desktop nobody is looking at - see hidden_desktop.
    import hidden_desktop
    try:
        code, said = hidden_desktop.run(command, cwd=ROOT, timeout=SUITE_TIMEOUT)
    except OSError as nowhere:
        #NOT ASKED, never asked on the owner's screen instead: no verdict, which is never scored a kill
        return None, "the suite could not be started on the unattended desktop: %s" % nowhere
    if code is None:
        return None, said
    return verdict(code, said), said


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
        if "${STEP}" in a:
            a = a.replace("${STEP}", battery_step())
        #A WINDOWS PATH IS THIS MACHINE'S PATH (2026-10-09): test.ps1 hands its suites "build\\classes" and the
        #ledger copies test.ps1, so on Linux 30 of 1358 plants pointed their suite at a folder that is not there,
        #it refused, and the plant could never redden - the owed drive read the first of them as a survivor
        out.append(this_machines(a.replace("${CODE}", code_path())))
    return out


def this_machines(arg):
    """`arg` with this machine's path separator: a backslash is one only where the separator is."""
    return arg.replace(chr(92), os.sep) if os.sep != chr(92) else arg


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


#: A must_say that quotes what a ratchet MEASURED goes stale the next time the count moves, and the
#: plant then reads as a guard that stopped noticing. Paid for 2026-10-08: two plants said
#: "(measured 55, recorded 56)" and "(measured 32, recorded 33)", reddened their suite, and were
#: scored SURVIVED because the counts had become 50 and 28. The count is the suite's output, not the
#: defect: quote the words that name the check. The count AFTER its word too: "larger than the 8
#: recorded" was let through by the order this read, and that plant could not be proven once the
#: package-cycle ceiling fell to 7 (2026-10-08, F119); "exactly the 9 recorded" was waiting for the same.
COUNTED = re.compile(r"\b(?:measured|recorded|ceiling of)\s+\d|\b\d+\s+recorded\b")


#: A why in the shape of a fix brief - what somebody was told to DO - rather than the defect the plant
#: puts back and what it cost (F190). Nine entries were pasted in that way ('Take answer (a)...',
#: 'Do not touch...', 'Write one small new suite...'), measured 2026-10-08; none named what the user lost.
BRIEF = re.compile(r"^(?:Fix|Add|Zero|Change|Write|Take|Make|Remove|Delete|Move|Use|Guard|Pin)\s+"
                   r"(?:the|a|an|one|this|that|all|every|each|answer|it)\b|^(?:Do not|Don't|Never)\b"
                   r"|^Decided\b|\bthe analyst'?s?\b|\bso I guard\b|^The production fix\b")


#: A must_say that names a source class the tree's code no longer names can never be printed again: the
#: suite now says the class's new name, and the plant reads as a guard that stopped noticing. Paid for
#: 2026-10-08 (F248): the map painter's engine moved out of TilePainterForm, sixteen plants were re-pointed
#: at the new file by their find text, and "TilePainterForm.wireSigns names ORAS" was left in one verdict -
#: found only by re-driving all 198 touched plants, about two hours. A class named as `Foo.java` or
#: `Foo.member`, Foo with a lower-case letter in it (ARCHITECTURE.md is a document, not a class).
NAMED_CLASS = re.compile(r"\b([A-Z][A-Za-z0-9]*[a-z][A-Za-z0-9]*)\.(?!md\b|txt\b|tsv\b|json\b|py\b|ps1\b)(?:java\b|[a-z]\w*)")
#: A Java string, char literal or comment: the code is what is left with the comments blanked.
JAVA_TOKEN = re.compile(r'"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'|//[^\n]*|/\*.*?\*/', re.S)
_named_in_code = {}


def named_in_code(root):
    """Every capitalised name the Java code under root/src names, comments blanked - a JDK class the code
    uses is named there as surely as one of the project's. Read once per root."""
    if root not in _named_in_code:
        names = set()
        for d, _dirs, files in os.walk(os.path.join(root, "src")):
            for f in files:
                if f.endswith(".java"):
                    _, text, _ = read(os.path.join(d, f))
                    code = JAVA_TOKEN.sub(lambda m: m.group(0) if m.group(0)[0] in "\"'" else " ", text)
                    names.update(re.findall(r"\b[A-Z]\w*", code))
        _named_in_code[root] = names
    return _named_in_code[root]


def check_shape(book, code_root=None):
    """Every refusal the ledger can make without building anything. Returns a list of problems.
    code_root: the tree whose Java code a must_say's class names are looked for in (the project's own)."""
    code_root = code_root or ROOT
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
        if BRIEF.search(p.get("why", "")):
            bad.append("%s: its why is a fix brief (%r) - say what the defect was and what it cost the "
                       "user, not what to do" % (pid, p["why"][:60]))
        if COUNTED.search(p.get("must_say", "")):
            bad.append("%s: its must_say quotes a measured count (%r) - it goes stale the next time "
                       "the count moves; quote the words that name the check" % (pid, p["must_say"]))
        for name in NAMED_CLASS.findall(p.get("must_say", "")):
            if name not in named_in_code(code_root):
                bad.append("%s: its must_say names %s, which no code in the tree names any more - a verdict "
                           "written against a class since moved or renamed is never printed again; quote "
                           "what the suite says now" % (pid, name))
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
            "${A039}": "$a039", "${A013}": "$a013", "${A040}": "$a040", "${CODE}": "$code",
            "${STEP}": "$step"}
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


def unplanted_says(p, run):
    """Whether the plant's suite says the plant's words with NOTHING planted - `run(suite, args)` answering
    (exit, said) on the clean tree. A red the clean tree also shows proves nothing: the suite fails for
    something else, and the words were going to be there anyway."""
    _, said = run(p["suite"], p.get("args", []))
    return p["must_say"] in (said or "")


def against_the_clean_tree(plants, held, lost, built, run):
    """Moves each red in `held` that proves nothing into `lost`, in place. A red is asked once more on the
    tree just rebuilt clean - `run(suite, args)` answering (exit, said) - and one whose suite says its
    words with nothing planted is not proven. When the clean rebuild failed (`built` false) no red is
    proven: a run over that tree is not a run of the clean tree, so nothing could be asked."""
    if not held:
        return
    if not built:
        print("  NOT PROVEN: the clean rebuild failed, so no red could be checked against the clean tree")
        lost.extend(held)
        del held[:]
        return
    by_id = {p["id"]: p for p in plants}
    #ONE CLEAN RUN PER SUITE AND ARGUMENTS: the clean tree is one tree, so every plant on a suite asks it
    #the same question - and each asked it again, one full suite run per plant
    seen = {}

    def once(suite, args):
        key = (suite, tuple(args))
        if key not in seen:
            seen[key] = run(suite, args)
        return seen[key]
    for pid in list(held):
        if unplanted_says(by_id[pid], once):
            print("  NOT PROVEN %s: %s says %r with nothing planted" % (pid, by_id[pid]["suite"],
                                                                     by_id[pid]["must_say"]))
            held.remove(pid)
            lost.append(pid)


def _calls(function, name):
    """Whether `function`'s own source holds a call to `name` - read from its syntax tree, so a comment
    or a string naming it does not count."""
    import inspect
    import textwrap
    tree = ast.parse(textwrap.dedent(inspect.getsource(function)))
    return any(isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == name
               for node in ast.walk(tree))


def replant(p, java, pristine, gamedir, keep=None):
    """True when the guard noticed, False when it did not, None when it could not be asked
    (UNKNOWN: its suite reads game data this machine does not have). Restores the file whatever
    happens. With `keep`, the planted suite's whole output is written to keep/<id>.out - what a
    plant's bill is read from."""
    path = os.path.join(ROOT, p["file"].replace("/", os.sep))
    raw, text, crlf = read(path)
    was = os.stat(path)
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
        code, said = None, ""
        fast = None
        if not p.get("no_rebuild") and p["file"].endswith(".java"):
            fast = overlaid(path, java)
        if fast is not None:
            try:
                code, said = run_suite(p["suite"], expand(p.get("args", []), pristine, gamedir), java, fast)
            finally:
                shutil.rmtree(fast.split(os.pathsep)[0], ignore_errors=True)
            if not (code not in (None, 0) and p["must_say"] in said):
                print("     the fast path did not show it red; asking the full build")
                fast = None
        if fast is None:
            if not p.get("no_rebuild"):
                BUILT_PLANTED[0] = True
                built, out = build()
                if not built:
                    print("     the planted tree does not compile, so nothing is proven here.")
                    print("     A plant must leave a tree that BUILDS - otherwise the suite never runs")
                    print("     and 'it failed' means only that javac did.")
                    return False
            code, said = run_suite(p["suite"], expand(p.get("args", []), pristine, gamedir), java)
        if keep is not None:
            with io.open(os.path.join(keep, p["id"] + ".out"), "w", encoding="utf-8", newline="") as kept:
                kept.write("exit %s%s%s" % (code, LF, said))
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
            #THE TIME GOES BACK WITH THE BYTES. A fast-path plant never rebuilds build/classes, so a restore that
            #left the file's modification time at NOW made the unchanged source newer than its class, and
            #EdtReachTest - which refuses a source edited after the build - went red over a tree nobody had
            #edited. PAID FOR TWICE: the 1.0.4 battery (PaintedCliffMesh.java, 2026-10-18) and the 1.0.5 one
            #(GeometryForker.java, 2026-10-23). build.ps1 empties build/classes before every compile, so an
            #older time can never keep a planted class.
            os.utime(path, ns=(was.st_atime_ns, was.st_mtime_ns))
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
                  if not _NUMBER.search(_NOT_A_BILL.sub("", _HASH.sub("", p.get("why", "")))))


#: A COMMIT HASH IS NOT A COST: "807bc8e" holds digits, so a why citing where its fix landed read as
#: priced with no bill in it (2026-10-08, 2 entries). A 7-40 character hex token with a digit and a
#: letter comes out before the number test; "0x38", "1234567" and "deadbeef" are not hashes here.
_HASH = re.compile(r"\b(?=[0-9a-f]*[0-9])(?=[0-9a-f]*[a-f])[0-9a-f]{7,40}\b")

#: NOR IS A DATE, A FINDING'S NAME OR A CENSUS LINE: "2026-10-09", "F209d" and "M.121" hold digits, so a why that
#: said when, or which finding, read as priced with nothing paid in it - 254 of 1357 plants passed only that way
#: (measured 2026-10-09: 102 counted costless, 356 with these out). They come out before the number test, as a
#: hash does; "7 files", "0x38" and "SEVEN audits" stay bills.
_NOT_A_BILL = re.compile(r"\b\d{4}-\d{2}-\d{2}\b|\bF\d{3}[a-z]?\b|\b[A-Z]\.\d+\b")


#: The ledger's ratchets: (name printed, ceiling key).
CEILINGS = (("owed", "owed_ceiling"), ("owed_generalisation", "owed_generalisation_ceiling"),
            ("owed_real_defect", "owed_real_defect_ceiling"), ("no_cost", "no_cost_ceiling"),
            ("crash_pinned", "crash_pinned_ceiling"))

#: What a suite prints when the code under test CRASHED, rather than a sentence about the defect it
#: guards (F188): an exception class, "index N out of", a stack frame. A machine-found plant that
#: pins one proves the suite runs the code, not that it would notice anything - and pins a game-data
#: size ("Index 534916 out of bounds for length 534912") that moves with the dump. Measured
#: 2026-10-08: 10 machine-found plants, each the only proof of its suite.
CRASHED = re.compile(r"\b[A-Za-z]+(?:Exception|Error)\b|\b[Ii]ndex -?\d+ out of\b|\bat [\w.$]+\(")


def crash_pinned(book):
    """The machine-found plants whose must_say is crash text, by id. Hand-written ones are a person
    choosing the words - "not a bare NullPointerException" names what must NOT happen."""
    return [p.get("id") for p in book["plants"]
            if p.get("auto") and CRASHED.search(p.get("must_say", ""))]


def slack(book):
    """Each ledger ceiling that sits ABOVE its count, as (name, measured, ceiling) (F189). A ratchet
    only bites while it is kept tight: owed and owed_real_defect carried slack of 15 and 10 on
    2026-10-08, room for that many suites to land unproven with every check green."""
    n_owed, n_site, n_auto, _, _ = owed(book)
    counts = {"owed": n_owed, "owed_generalisation": n_site, "owed_real_defect": n_auto,
              "no_cost": len(without_a_cost(book)), "crash_pinned": len(crash_pinned(book))}
    return [(name, counts[name], book[key]) for name, key in CEILINGS
            if book.get(key) is not None and book[key] > counts[name]]


def tighten(book):
    """`--tighten`: lower each ceiling with slack to what is measured, and write the ledger. It only
    lowers - a count OVER its ceiling is a failing ratchet, which --selftest reports and this leaves
    alone - and refuses a ledger whose formatting a rewrite would change."""
    loose = slack(book)
    #A CEILING THE LEDGER DOES NOT DECLARE YET is adopted at today's count, which is where a new
    #ratchet starts: the debt that exists, and from here it only falls
    absent = [(name, key) for name, key in CEILINGS if book.get(key) is None]
    if not loose and not absent:
        print("every ceiling is already what is measured")
        return 0
    with io.open(LEDGER, encoding="utf-8", newline="") as handle:
        text = handle.read()
    if json.dumps(book, indent=1, ensure_ascii=False) + LF != text:
        print("REFUSING: the ledger is not in the formatting this writes (json, indent 1, a closing "
              "newline), so rewriting it would change more than its ceilings")
        return 2
    keys = dict(CEILINGS)
    for name, measured, ceiling in loose:
        book[keys[name]] = measured
        print("%s: ceiling %d -> %d" % (name, ceiling, measured))
    if absent:
        n_owed, n_site, n_auto, _, _ = owed(book)
        counts = {"owed": n_owed, "owed_generalisation": n_site, "owed_real_defect": n_auto,
                  "no_cost": len(without_a_cost(book)), "crash_pinned": len(crash_pinned(book))}
        for name, key in absent:
            book[key] = counts[name]
            print("%s: adopted at %d - the debt today, which may only fall" % (name, counts[name]))
    with io.open(LEDGER, "w", encoding="utf-8", newline="") as handle:
        handle.write(json.dumps(book, indent=1, ensure_ascii=False) + LF)
    return 0


def raise_ceiling(book, name, reason):
    """`--raise NAME "REASON"`: put one ceiling up to what is measured, and write why beside it.

    The ratchets may only fall, and a rise is a decision somebody makes - so it is never a number
    edited by hand: this refuses when nothing is over the ceiling, refuses a reason under 40
    characters, and appends {name, from, to, reason} to the ledger's "raised" list, which the
    self-test prints every run so the next reader can disagree with it."""
    keys = dict(CEILINGS)
    if name not in keys:
        print("REFUSING: %s is not a ceiling (%s)" % (name, ", ".join(keys)))
        return 2
    if len((reason or "").strip()) < 40:
        print("REFUSING: a rise needs its reason, at least 40 characters - it is a decision, and the "
              "ledger is where the next reader finds it")
        return 2
    n_owed, n_site, n_auto, _, _ = owed(book)
    counts = {"owed": n_owed, "owed_generalisation": n_site, "owed_real_defect": n_auto,
              "no_cost": len(without_a_cost(book)), "crash_pinned": len(crash_pinned(book))}
    was = book.get(keys[name])
    if was is not None and counts[name] <= was:
        print("REFUSING: %s is %d, at or under its ceiling %d - nothing to raise" % (name, counts[name], was))
        return 2
    with io.open(LEDGER, encoding="utf-8", newline="") as handle:
        text = handle.read()
    if json.dumps(book, indent=1, ensure_ascii=False) + LF != text:
        print("REFUSING: the ledger is not in the formatting this writes, so rewriting it would change "
              "more than one ceiling")
        return 2
    book[keys[name]] = counts[name]
    book.setdefault("raised", []).append({"name": name, "from": was, "to": counts[name], "reason": reason.strip()})
    with io.open(LEDGER, "w", encoding="utf-8", newline="") as handle:
        handle.write(json.dumps(book, indent=1, ensure_ascii=False) + LF)
    print("%s: ceiling %s -> %d, reason recorded" % (name, was, counts[name]))
    return 0


def selftest():
    fails = []

    def check(cond, what):
        print(("  ok: " if cond else "  FAIL: ") + what)
        if not cond:
            fails.append(what)

    book = ledger()
    words = {"suite": "S", "args": [], "must_say": "FAIL: the guard"}
    check(unplanted_says(words, lambda suite, args: (1, "FAIL: the guard\n"))
          and not unplanted_says(words, lambda suite, args: (0, "ok: the guard\n")),
          "a red the clean tree also shows is not proven: the unplanted suite's words are asked")
    two = [{"id": "a", "suite": "A", "must_say": "FAIL: a"}, {"id": "b", "suite": "B", "must_say": "FAIL: b"}]
    said = {"A": "FAIL: a\n", "B": "ok: b\n"}
    held, lost = ["a", "b"], []
    against_the_clean_tree(two, held, lost, True, lambda suite, args: (1, said[suite]))
    check(held == ["b"] and lost == ["a"],
          "a red whose suite says its words on the clean tree is moved to NOT PROVEN, and only that one"
          " (held %r, lost %r)" % (held, lost))
    held, lost = ["a", "b"], []
    against_the_clean_tree(two, held, lost, False, lambda suite, args: (0, "ok\n"))
    check(held == [] and lost == ["a", "b"],
          "after a failed clean rebuild no red is proven (held %r, lost %r)" % (held, lost))
    check(_calls(main, "against_the_clean_tree") and not _calls(main, "no_such_function_here"),
          "main asks every red against the clean tree")
    check(battery_step().isdigit() and expand(["${STEP}"], "p", "g") == [battery_step()],
          "${STEP} expands to the step test.ps1 hands the corpus suites in a full run (%s)" % battery_step())
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

    pinned = crash_pinned(book)
    ceiling = book.get("crash_pinned_ceiling")
    check(ceiling is not None and len(pinned) <= ceiling,
          "%d machine-found plant(s) pin a crash rather than a sentence, at or under the ceiling %s -"
          " re-plant each with a mutation its suite reports in its own words; this may only fall%s"
          % (len(pinned), ceiling, "" if ceiling is None or len(pinned) <= ceiling else ": " + ", ".join(pinned)))
    crashes = {"plants": [{"id": "a", "auto": True, "must_say": "zone 0: Index 0 out of bounds for length 0"},
                          {"id": "b", "auto": True, "must_say": "FAIL: the zone keeps its header after a pack"},
                          {"id": "c", "must_say": "FAIL: and its listener says so, not a bare NullPointerException"}]}
    check(crash_pinned(crashes) == ["a"],
          "a machine-found plant that pins a crash is counted, and a sentence or a hand-written one is not")
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import autoplant
    said = "  FAIL: zone 0: Index 0 out of bounds for length 0" + LF + "  FAIL: the zone keeps its header after a pack" + LF
    check(autoplant.fail_line(said) == "the zone keeps its header after a pack"
          and autoplant.fail_line("  FAIL: region 7: java.lang.ArrayIndexOutOfBoundsException: 3" + LF) is None,
          "the machine plant finder does not record a crash as what a suite says")

    loose = slack(book)
    check(loose == [], "every ceiling is what is measured%s" % (
        "" if not loose else " - not: " + ", ".join("%s %d under %d" % row for row in loose)
        + "; lower them with --tighten: a slot of slack is room for that many to land unremarked"))
    check([row[0] for row in slack(dict(book, owed_ceiling=n_owed + 1))] == ["owed"] + [row[0] for row in loose
                                                                              if row[0] != "owed"],
          "a ceiling one above its count is slack")
    check(slack(dict(book, owed_ceiling=n_owed, owed_generalisation_ceiling=n_site,
                     owed_real_defect_ceiling=n_auto, no_cost_ceiling=n_cost)) == [],
          "and one equal to its count is not")

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
    hashed = {"plants": [{"id": "h", "why": "the zone could be written back (807bc8e)"}]}
    check(without_a_cost(hashed) == ["h"], "and a commit hash is not a cost")
    dated = {"plants": [{"id": "d", "why": "measured 2026-10-09 for F209d, census M.121"},
                        {"id": "e", "why": "measured 2026-10-09: 7 files lost, header 0x38"}]}
    check(without_a_cost(dated) == ["d"], "nor a date, a finding's name or a census line - and a bill beside one stays a bill")
    check(expand(["build" + chr(92) + "classes"], "P", "G") == ["build" + os.sep + "classes"],
          "a plant's Windows path is handed to its suite as this machine's path")
    for row in book.get("raised") or []:
        print("  RAISED: %s %s -> %s - %s" % (row.get("name"), row.get("from"), row.get("to"), row.get("reason")))

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
    fake["plants"][0].update(find="param(", replace="param(x",
                             must_say="FAIL: and the ceiling is what is measured (measured 5, recorded 6)")
    check(any("quotes a measured count" in b for b in check_shape(fake)),
          "a must_say that quotes what a ratchet measured is refused")
    fake["plants"][0].update(must_say="FAIL: no package cycle is larger than the 8 recorded")
    check(any("quotes a measured count" in b for b in check_shape(fake)),
          "and so is one that quotes the recorded count after it")
    fake["plants"][0].update(must_say="FAIL: ORAS's Maison lobby is zone 517")
    check(not any("quotes a measured count" in b for b in check_shape(fake)),
          "and one that names a fixed fact with a number in it is not")
    fake["plants"][0].update(why="Take answer (a): the loose-map clear clears all three entity forms")
    check(any("is a fix brief" in b for b in check_shape(fake)), "a why that is a fix brief is refused")
    fake["plants"][0].update(why="Remove added zones reported an unread table as stock, so 3 zones stayed")
    check(not any("is a fix brief" in b for b in check_shape(fake)),
          "and one that opens with a menu item's name is not")
    fake["plants"] = [dict(fake["plants"][0], id="dup"), dict(fake["plants"][0], id="dup")]
    check(any("share an id" in b for b in check_shape(fake)), "and two plants may not share an id")
    #a verdict naming a class the code no longer names: the code here is a scratch tree's, in which
    #Kept is a class, ExecutorService a JDK class the code uses, Gone is named only in comments, and
    #Said only in a string - which a suite can print, so it counts
    code = tempfile.mkdtemp(prefix="replant-code-")
    os.makedirs(os.path.join(code, "src", "x"))
    with io.open(os.path.join(code, "src", "x", "Kept.java"), "w", encoding="utf-8") as h:
        h.write("package x;\n/** Gone.wireSigns moved here. */\nclass Kept { // was Gone.java\n"
                "\tjava.util.concurrent.ExecutorService pool;\n\tString s = \"Said.wireSigns\";\n}\n")
    fake["plants"] = [dict(fake["plants"][0], id="one")]

    def stale(say):
        fake["plants"][0]["must_say"] = say
        return any("which no code in the tree names" in b for b in check_shape(fake, code_root=code))
    check(stale("FAIL: Gone.wireSigns names ORAS") and stale("in Gone.java"),
          "a must_say naming a class the code no longer names is refused, a comment naming it not counted")
    check(not stale("FAIL: Kept.run names ORAS") and not stale("ExecutorService.execute runs code")
          and not stale("FAIL: Said.wireSigns names ORAS") and not stale("FAIL: ARCHITECTURE.md states the measured count"),
          "and one naming a class the code names - the project's, the JDK's, or in a string a suite prints - or a"
          " document, is not")
    shutil.rmtree(code, ignore_errors=True)

    #A MACHINE WITHOUT THE OWNER'S JDK OR GAME DATA. Every Linux cloud checkout is one: jdk()
    #answered None there and a missing dump read as an emptied guard (2026-10-07).
    check(len(LIBS.split(os.pathsep)) == 3,
          "the classpath is joined with this platform's separator (%r)" % LIBS)
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

    #THE GATE LEARNS THIS DRIVER'S "COULD NOT ASK" FROM THE DECLARATION, NOT FROM HERE. Without
    #`_tests.ledger.unknown_exit` the bundle's commit gate read every exit 3 as a guard that stopped
    #noticing (2026-10-07, 19 of them), and a declaration that drifts from UNKNOWN does it quietly.
    try:
        with io.open(os.path.join(ROOT, ".claude", "bundle-install.json"), encoding="utf-8") as h:
            declared = ((json.load(h).get("_tests") or {}).get("ledger") or {}).get("unknown_exit")
    except (OSError, ValueError, AttributeError) as bad:
        declared = "unreadable (%s)" % bad
    check(declared == UNKNOWN and not isinstance(declared, bool) and UNKNOWN > 1,
          "the commit gate is told the could-not-ask code: _tests.ledger.unknown_exit is %r, UNKNOWN is %d"
          % (declared, UNKNOWN))

    #A FLAG IT DOES NOT KNOW DRIVES NOTHING (2026-10-09: --help drove all 1,363 plants)
    check(all(refused_flags(["replant.py"] + given) for given in
              (["--help"], ["-h"], ["--owd"], ["an-id", "--all"], ["--magnitude", "a reason"])),
          "an unknown flag, and --magnitude without --owed, are refused")
    check(not any(refused_flags(["replant.py"] + given) for given in
                  ([], ["an-id"], ["--owed"], ["--owed", "--magnitude", "fell", "-3", "--for", "x"],
                   ["--raise", "owed_ceiling", "the", "--why"], ["--selftest"], ["--compiles"], ["--tighten"])),
          "and every flag main reads, ids, and the words of a reason after --raise or --magnitude, are not")
    #--keep takes a folder that does not exist yet: none, or one already there, is refused (2026-10-10)
    fresh = os.path.join(tempfile.gettempdir(), "ctrmap-replant-keep-%d-none" % os.getpid())
    check(refused_flags(["replant.py", "--keep"]) and refused_flags(["replant.py", "--keep", ROOT])
          and not refused_flags(["replant.py", "--no-cost", "--keep", fresh])
          and not refused_flags(["replant.py", "an-id", "--keep", fresh]),
          "--keep without a fresh folder is refused; with one, beside --no-cost or an id, it is not")
    #asked of main itself, by its words: an exit of 2 alone is also what a machine with no JDK answers
    import contextlib
    driven, heard = [], io.StringIO()
    kept = {name: globals()[name] for name in ("replant", "build")}
    globals()["replant"] = lambda *given: driven.append(given)
    globals()["build"] = lambda *given: (True, "")
    try:
        with contextlib.redirect_stdout(heard):
            code = main(["replant.py", "--help"])
    finally:
        globals().update(kept)
    check(code == 2 and not driven and "--help is not a flag this reads" in heard.getvalue(),
          "main refuses --help before it chooses a plant: exit %r, %d driven" % (code, len(driven)))

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


def compiles(book, java_home, workers=None):
    """0 when every Java plant still compiles planted, 1 when some do not, 2 when it could not ask.

    THE MIDDLE TIER. The cheap check (PlantLedgerTest, --selftest) asks only that each plant still
    MATCHES its file once; the full replant asks everything and takes hours. Neither between them
    saw a plant whose replacement stopped compiling because ANOTHER file changed: Workspace.cleanAll
    began to throw IOException (F253), clean-workspace-menu-asks-nothing's replacement calls it
    bare, and that plant read as fine to every check short of a re-drive, which found it weeks later
    (2026-10-08). A plant that cannot build proves nothing - its suite never runs.

    Each plant is put into a scratch copy of its ONE file and compiled there against build/classes,
    so no tree is touched and no suite is run. Two controls come first, on the first Java plant's
    file: unplanted it must compile (else the classpath is wrong and every plant would read as
    broken), and with a line of garbage it must not (else a failure would read as a pass).
    """
    classes = os.path.join(ROOT, "build", "classes")
    if not os.path.isdir(os.path.join(classes, "ctrmap")):
        print("REFUSING: nothing is compiled under %s - build first. Compiled against nothing, every"
              " plant would read as broken, and that is not a measurement." % classes)
        return 2
    javac = os.path.join(java_home, "bin", JAVAC_EXE)
    cp = os.pathsep.join([classes, os.path.join(ROOT, "lib", "jogl-all.jar"), os.path.join(ROOT, "lib", "gluegen-rt.jar")])
    java_plants = [p for p in book["plants"] if p["file"].endswith(".java")]
    if not java_plants:
        print("REFUSING: the ledger holds no Java plant, so there is nothing to compile - and a check of"
              " nothing would say every plant compiles.")
        return 2
    scratch = tempfile.mkdtemp(prefix="replant_compiles_")

    def attempt(key, rel, text):
        """(compiled, first error line) for this text as the file at rel."""
        src = os.path.join(scratch, "src", key)
        out = os.path.join(scratch, "out", key)
        os.makedirs(src)
        os.makedirs(out)
        target = os.path.join(src, os.path.basename(rel))
        write(target, text, False)
        done = subprocess.run([javac, "--release", "8", "-encoding", "UTF-8", "-nowarn", "-proc:none",
                               "-cp", cp, "-d", out, target], capture_output=True, text=True, errors="replace",
                              creationflags=NO_WINDOW)
        said = [line for line in (done.stdout + done.stderr).splitlines() if "error:" in line]
        shutil.rmtree(src, ignore_errors=True)
        shutil.rmtree(out, ignore_errors=True)
        return done.returncode == 0, (said[0].split("error:", 1)[1].strip() if said else "javac exit %d" % done.returncode)

    try:
        first = java_plants[0]
        _, text, _ = read(os.path.join(ROOT, first["file"].replace("/", os.sep)))
        clean, why = attempt("control-clean", first["file"], text)
        garbage, _ = attempt("control-garbage", first["file"], text + LF + "this is not java" + LF)
        if not clean or garbage:
            print("REFUSING: the controls on %s did not hold (unplanted compiles: %s%s; with garbage"
                  " compiles: %s), so no plant's answer here could be believed"
                  % (first["file"], clean, "" if clean else " - " + why, garbage))
            return 2
        print("control: %s compiles unplanted and not with garbage; compiling %d Java plant(s) planted"
              % (first["file"], len(java_plants)))

        def one(indexed):
            i, p = indexed
            _, text, _ = read(os.path.join(ROOT, p["file"].replace("/", os.sep)))
            if text.count(p["find"]) != 1:
                return p["id"], False, "its substitution no longer matches %s exactly once" % p["file"]
            ok, why = attempt(str(i), p["file"], text.replace(p["find"], p["replace"]))
            return p["id"], ok, why

        with ThreadPoolExecutor(max_workers=workers or max(1, (os.cpu_count() or 2) - 1)) as pool:
            results = list(pool.map(one, enumerate(java_plants)))
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    broken = [(pid, why) for pid, ok, why in results if not ok]
    for pid, why in broken:
        print("    DOES NOT COMPILE PLANTED: %s - %s" % (pid, why))
    print("%d of %d Java plant(s) compile planted; %d do not" % (len(results) - len(broken), len(results), len(broken)))
    return 1 if broken else 0


#: Every flag main() reads. The words after --raise and --magnitude are their reason, not flags.
FLAGS = ("--selftest", "--compiles", "--tighten", "--raise", "--owed", "--magnitude", "--keep", "--no-cost")
_REASON_AFTER = ("--raise", "--magnitude")
#: Flags whose value is the next word - a folder, never a plant id.
_VALUED = ("--keep",)


def refused_flags(argv):
    """Why `argv` must not be run, or None.

    AN UNKNOWN FLAG DROVE THE WHOLE LEDGER. main() set every argument starting with '-' aside before
    choosing plants, so a flag it did not know named no plant, and no plant named means every plant.
    PAID FOR 2026-10-09: `replant.py --help`, run for its usage, drove all 1,363 plants over an
    uncommitted tree - about 7.5 hours with the tree unwritable throughout - and the session that
    started it was refused stopping it until the owner said so. A flag this does not know is refused
    before anything is chosen - asked of the bundle's cli_words, the one implementation every tool's
    words go through (2026-10-10) - and so is --magnitude without the --owed whose number it explains.
    Any other word names a plant, and no word at all drives every plant.
    """
    import _cli
    unread_words = _cli.refused(argv[1:], flags=FLAGS, valued=_VALUED, rest=_REASON_AFTER, most=None)
    if unread_words:
        return unread_words
    if "--keep" in argv:
        at = argv.index("--keep") + 1
        if at >= len(argv) or argv[at].startswith("-") or os.path.exists(argv[at]):
            return ("REFUSING: --keep takes a folder that does not exist yet, to write each plant's suite output"
                    " into - nothing was driven, and no earlier output was written over.")
    if "--magnitude" in argv and "--owed" not in argv:
        return ("REFUSING: --magnitude explains a number --owed records; without --owed it names no plant,"
                " which drives every plant. Nothing was driven.")
    return None


def main(argv):
    refused = refused_flags(argv)
    if refused:
        print(refused)
        return 2
    book = ledger()
    if "--selftest" in argv:
        return selftest()
    if "--compiles" in argv:
        home = jdk()
        if not home:
            print("No JDK found, so whether the plants compile is UNKNOWN.")
            return UNKNOWN
        return compiles(book, home)
    if "--tighten" in argv:
        return tighten(book)
    if "--raise" in argv:
        at = argv.index("--raise")
        if len(argv) < at + 3 or argv[at + 1].startswith("-"):
            print("REFUSING: --raise takes a ceiling name and its reason")
            return 2
        return raise_ceiling(book, argv[at + 1], " ".join(argv[at + 2:]))

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

    keep = argv[argv.index("--keep") + 1] if "--keep" in argv else None
    wanted = [a for i, a in enumerate(argv) if i > 0 and not a.startswith("-")
              and not (keep is not None and argv[i - 1] == "--keep")]
    #THE UNPRICED, FROM THE RULE: the plants without_a_cost names - the same function the ceiling counts with,
    #so the set driven to price them is the set the ratchet says is unpriced, never a list typed here
    if "--no-cost" in argv:
        unpriced = set(without_a_cost(book))
        wanted = [w for w in wanted if w in unpriced] if wanted else sorted(unpriced)
    plants = [p for p in book["plants"] if not wanted or p["id"] in wanted]
    if wanted and len(plants) != len(wanted):
        print("no such plant: %s" % ", ".join(sorted(set(wanted) - set(p["id"] for p in plants))))
        return 2
    if keep is not None:
        os.makedirs(keep)
        print("each plant's suite output is kept in %s, as <id>.out" % keep)

    print("replanting %d defect(s); each must make its guard go red" % len(plants))
    held, lost, unknown = [], [], []
    planted = False
    built = False
    try:
        for p in plants:
            verdict = replant(p, java, pristine, gamedir, keep)
            #EVERY plant may have been built into the classes, an UNKNOWN one too - it is planted
            #and run now even with game data missing - so the tree is rebuilt whatever the verdicts
            planted = True
            (unknown if verdict is None else held if verdict else lost).append(p["id"])
    finally:
        if planted and BUILT_PLANTED[0]:
            print("rebuilding, so nobody is left with a planted tree")
            built, _ = build()
            print("  %s" % ("Build OK" if built else "THE REBUILD FAILED - check the tree by hand"))
        elif planted:
            #every plant took the fast path, which never wrote build/classes: they are as they were
            built = True

    #A RED THE CLEAN TREE ALSO SHOWS PROVES NOTHING (2026-10-08): each plant judged red is asked once
    #more, on the tree just rebuilt clean, with nothing planted - one whose suite says its words anyway
    #is not proven. Never asked over a tree whose clean rebuild failed: that run is not of the clean tree.
    if planted:
        against_the_clean_tree(plants, held, lost, built,
                               lambda suite, args: run_suite(suite, expand(args, pristine, gamedir), java))
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
