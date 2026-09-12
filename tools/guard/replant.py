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
import subprocess
import sys

LF = chr(10)
CR = chr(13)
CRLF = CR + LF
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LEDGER = os.path.join(ROOT, "tools", "guard", "plants.json")
LIBS = "build/classes;lib/jogl-all.jar;lib/gluegen-rt.jar"


def ledger():
    return json.load(io.open(LEDGER, encoding="utf-8"))


def jdk():
    """The JDK the battery uses, or None."""
    override = os.environ.get("CTRMAP_JDK")
    if override and os.path.isdir(override):
        return override
    base = r"C:\Program Files\Eclipse Adoptium"
    if not os.path.isdir(base):
        return None
    found = sorted(d for d in os.listdir(base) if d.lower().startswith("jdk"))
    return os.path.join(base, found[-1]) if found else None


def read(path):
    """(raw bytes, text with LF endings, whether the file is CRLF)."""
    raw = io.open(path, "rb").read()
    text = raw.decode("utf-8", "replace")
    return raw, (text.replace(CRLF, LF) if CRLF in text else text), (CRLF in text)


def write(path, text, crlf):
    io.open(path, "w", encoding="utf-8", newline="").write(text.replace(LF, CRLF) if crlf else text)


def build():
    """(ok, output). Uses the same script the battery insists on."""
    done = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                           "-File", os.path.join(ROOT, "build.ps1")],
                          cwd=ROOT, capture_output=True, text=True, errors="replace")
    out = (done.stdout or "") + (done.stderr or "")
    return ("Build OK" in out), out


#: A planted suite that never finishes is not a proof. A suite whose main() throws
#: leaves its JOGL animator threads running and the JVM alive for ever, so "still
#: going" and "caught it" look identical from here - and the first plant that did
#: this sat for twenty minutes looking like a slow pass.
SUITE_TIMEOUT = 600


def run_suite(cls, args, java):
    """Exactly as test.ps1 runs it, flags included.

    This used to add -Djava.awt.headless=true, which the battery does not, and
    a suite can behave differently under it: a map load that a headless JVM
    refuses at the progress dialog SUCCEEDS with a display, and the code after
    it then runs against a loaded map instead of an empty one. A guard proven
    under one set of flags and shipped under another is not proven."""
    """(exit code or None when it never finished, everything it said)."""
    command = [java, "-Xmx4g", "-cp", LIBS, cls] + args
    try:
        done = subprocess.run(command, cwd=ROOT, capture_output=True, text=True,
                              errors="replace", timeout=SUITE_TIMEOUT)
    except subprocess.TimeoutExpired as late:
        out = late.stdout or ""
        err = late.stderr or ""
        if isinstance(out, bytes):
            out = out.decode("utf-8", "replace")
        if isinstance(err, bytes):
            err = err.decode("utf-8", "replace")
        return None, out + err
    return done.returncode, (done.stdout or "") + (done.stderr or "")


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
        out.append(a)
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
    text = io.open(os.path.join(ROOT, "test.ps1"), encoding="utf-8", errors="replace").read()
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
    text = io.open(os.path.join(ROOT, "test.ps1"), encoding="utf-8", errors="replace").read()
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
            "${A039}": "$a039", "${A013}": "$a013", "${A040}": "$a040"}
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


def replant(p, java, pristine, gamedir):
    """True when the guard noticed. Restores the file whatever happens."""
    path = os.path.join(ROOT, p["file"].replace("/", os.sep))
    raw, text, crlf = read(path)
    if text.count(p["find"]) != 1:
        print("  REFUSED %s: its substitution no longer matches %s exactly once"
              % (p["id"], p["file"]))
        return False
    print("  %s -> %s" % (p["id"], p["suite"]))
    ok = False
    try:
        write(path, text.replace(p["find"], p["replace"]), crlf)
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
        elif noticed:
            print("     NOT PROVEN: the suite failed but never said %r." % p["must_say"])
            print("     It may have failed for something else entirely, which would record this")
            print("     guard as holding when it does not.")
        else:
            print("     SURVIVED: the defect is back and %s still passes." % p["suite"])
    finally:
        io.open(path, "wb").write(raw)
        back = io.open(path, "rb").read()
        if back != raw:
            print("     RESTORE FAILED for %s - the tree is NOT as it was." % p["file"])
            ok = False
    return ok


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

    # the runner's own refusals, on a scratch ledger rather than the real one
    fake = {"plants": [{"id": "a", "why": "w", "file": "test.ps1", "find": "zzz-not-here",
                        "replace": "x", "suite": "s", "must_say": "m"}],
            "owed_ceiling": 999, "owed_generalisation_ceiling": 999,
            "owed_real_defect_ceiling": 999}
    check(any("has rotted" in b for b in check_shape(fake)),
          "a plant whose text is gone is refused, not skipped")
    fake["plants"][0].update(find="param(", replace="param(")
    check(any("substitutes a thing for itself" in b for b in check_shape(fake)),
          "and a plant that substitutes a thing for itself is refused")
    fake["plants"] = [dict(fake["plants"][0], id="dup"), dict(fake["plants"][0], id="dup")]
    check(any("share an id" in b for b in check_shape(fake)), "and two plants may not share an id")

    print("ALL PASS" if not fails else "FAILURES PRESENT (%d)" % len(fails))
    return 0 if not fails else 1


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
        return 0

    bad = check_shape(book)
    if bad:
        print("THE LEDGER IS NOT USABLE:")
        for b in bad:
            print("  %s" % b)
        return 2

    java = jdk()
    if not java:
        print("No JDK found under C:\\Program Files\\Eclipse Adoptium and CTRMAP_JDK is not set.")
        return 2
    java = os.path.join(java, "bin", "java.exe")
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
    held, lost = [], []
    try:
        for p in plants:
            (held if replant(p, java, pristine, gamedir) else lost).append(p["id"])
    finally:
        print("rebuilding, so nobody is left with a planted tree")
        built, _ = build()
        print("  %s" % ("Build OK" if built else "THE REBUILD FAILED - check the tree by hand"))

    print("")
    print("%d guard(s) still notice; %d do not" % (len(held), len(lost)))
    for pid in lost:
        print("    NOT PROVEN: %s" % pid)
    print("owed: %d suite(s) with no plant; owed_generalisation: %d; owed_real_defect: %d"
          % (n_owed, n_site, n_auto))
    return 0 if not lost else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
