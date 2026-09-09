# -*- coding: utf-8 -*-
"""Find a plant for every registered suite that has none, by trying and checking.

WHY THIS EXISTS. tools/guard/plants.json is the ledger of proofs-by-breaking, and
replant.py re-runs them. Writing a plant by hand is not the slow part - knowing
whether it actually works is. So this does what replant.py does, in reverse:
apply a candidate mutation to a file the suite guards, compile it, run the suite,
and if the suite goes RED, record the plant WITH THE FAIL LINE IT PRODUCED as
must_say. Nothing is ever recorded that was not observed.

WHAT A MACHINE-FOUND PLANT IS WORTH, and it is less than a hand-written one. It
proves the suite is not vacuous: change a line it covers and it notices. It does
NOT put back a defect that ever reached a user, which is what the hand-written
plants do. The two claims are kept apart - every plant this writes carries
"auto": true, and replant.py's owed_real_defect counts the suites standing on one
alone. Filling this ledger by machine must never look like filling it.

THREE THINGS IT REFUSES TO CREDIT. A mutation that does not COMPILE proves
nothing, because the suite never ran. A suite that fails without saying its
must_say line may have failed for an unrelated reason - a missing dump, a stale
class. And a suite that is ALREADY RED before anything is planted proves nothing
by staying red: the first run of this recorded a plant against
MutationBaselineTest, which was failing its staleness check at the time and would
have gone on failing it with the mutation reverted. So every suite is run clean
first and skipped if it is not green. All three are dropped.

  python tools/guard/autoplant.py                 every suite with no plant
  python tools/guard/autoplant.py --limit=10      the first ten of them
  python tools/guard/autoplant.py ZoneClonerTest  just this one

Environment: CTRMAP_JDK, CTRMAP_PRISTINE, CTRMAP_GAMEDIR, same as replant.py.
"""
import io
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
LEDGER = os.path.join(HERE, "plants.json")
LIBS = "build/classes;lib/jogl-all.jar;lib/gluegen-rt.jar"
NL = chr(10)
CRLF = chr(13) + NL

#a suite that has not finished in this long is not going to; the mutation
#probably deadlocked it, and a hang is not a proof
SUITE_TIMEOUT = 150
#compiling one file is seconds; a whole tree is not, and this runs hundreds of
#times
COMPILE_TIMEOUT = 180


def jdk():
    if os.environ.get("CTRMAP_JDK"):
        return os.environ["CTRMAP_JDK"]
    base = r"C:\Program Files\Eclipse Adoptium"
    if not os.path.isdir(base):
        return None
    found = sorted(d for d in os.listdir(base) if d.startswith("jdk-"))
    return os.path.join(base, found[-1]) if found else None


def read(path):
    """(raw bytes, text with LF endings, whether the file was CRLF)."""
    raw = io.open(path, "rb").read()
    text = raw.decode("utf-8", "replace")
    return raw, (text.replace(CRLF, NL) if CRLF in text else text), (CRLF in text)


def write_text(path, text, crlf):
    io.open(path, "w", encoding="utf-8", newline="").write(
        text.replace(NL, CRLF) if crlf else text)


def compile_one(path, javac):
    """Compile one source into build/classes, the way build.ps1 would."""
    try:
        done = subprocess.run(
            [javac, "--release", "8", "-encoding", "UTF-8", "-nowarn",
             "-cp", LIBS, "-sourcepath", "src", "-d", os.path.join("build", "classes"),
             os.path.relpath(path, ROOT)],
            cwd=ROOT, capture_output=True, text=True, errors="replace",
            timeout=COMPILE_TIMEOUT)
    except subprocess.TimeoutExpired:
        return False
    return done.returncode == 0


def run_suite(cls, args, java):
    """Exactly as test.ps1 runs it, flags included.

    This used to add -Djava.awt.headless=true, which the battery does not, and
    a suite can behave differently under it: a map load that a headless JVM
    refuses at the progress dialog SUCCEEDS with a display, and the code after
    it then runs against a loaded map instead of an empty one. A guard proven
    under one set of flags and shipped under another is not proven."""
    try:
        done = subprocess.run(
            [java, "-Xmx4g", "-cp", LIBS, cls] + args,
            cwd=ROOT, capture_output=True, text=True, errors="replace",
            timeout=SUITE_TIMEOUT)
    except subprocess.TimeoutExpired:
        return None, ""
    return done.returncode, (done.stdout or "") + (done.stderr or "")


def registered(pristine, gamedir):
    """{simple name: (class, args as written, args resolved)} from test.ps1."""
    text = io.open(os.path.join(ROOT, "test.ps1"), encoding="utf-8",
                   errors="replace").read()
    known = {"$gamedir": gamedir, "$pristine": pristine,
             "$a039": os.path.join(pristine, "a", "0", "3", "9"),
             "$a013": os.path.join(pristine, "a", "0", "1", "3"),
             "$a040": os.path.join(pristine, "a", "0", "4", "0")}
    #plants.json is a SHIPPED file: SourceSeamTest refuses a home directory in
    #one, and it refused these the first time they were written as real paths.
    token = {gamedir: "${GAMEDIR}", pristine: "${PRISTINE}"}
    for name, parts in (("A039", ("a", "0", "3", "9")),
                        ("A013", ("a", "0", "1", "3")),
                        ("A040", ("a", "0", "4", "0"))):
        token[os.path.join(pristine, *parts)] = "${%s}" % name
    out = {}
    pattern = r'c\s*=\s*"(ctrmap\.tests\.(\w+))"\s*;\s+a\s*=\s*@\(([^)]*)\)'
    for m in re.finditer(pattern, text):
        written, resolved, ok = [], [], True
        for lit, var in re.findall(r'"([^"]*)"|(\$\w+)', m.group(3)):
            raw = lit or var
            if raw.startswith("$"):
                if raw not in known:
                    ok = False   #a variable this does not know how to resolve
                    break
                value = known[raw]
            else:
                value = raw.replace(chr(92), os.sep)
            resolved.append(value)
            written.append(token.get(value, value))
        if ok:
            out[m.group(2)] = (m.group(1), written, resolved)
    return out


#a suite names the classes it guards; these are the ones worth mutating
NAMED = re.compile(r'ctrmap\.[\w.]+?\.([A-Z]\w+)')


def guarded_files(suite_simple, limit):
    """Application sources this suite names, the ones it names most first."""
    path = os.path.join(ROOT, "src", "ctrmap", "tests", suite_simple + ".java")
    if not os.path.isfile(path):
        return []
    text = io.open(path, encoding="utf-8", errors="replace").read()
    names = set(NAMED.findall(text))
    names |= set(re.findall(r'\b([A-Z]\w+)\s*\.', text))
    hits = []
    for base, _, files in os.walk(os.path.join(ROOT, "src", "ctrmap")):
        if os.sep + "tests" in base:
            continue        #the program, not its battery
        for fn in files:
            if fn.endswith(".java") and fn[:-5] in names:
                hits.append((text.count(fn[:-5]), os.path.join(base, fn)))
    hits.sort(key=lambda h: -h[0])
    return [h[1] for h in hits[:limit]]


IF = re.compile(r'^(\s*)if \(([^;{]{4,120})\) \{$')
RET = re.compile(r'^(\s*)return (true|false);$')


def candidates(text):
    """(find, replace, kind) substitutions that should still compile."""
    out = []
    for line in text.split(NL):
        m = IF.match(line)
        if m and "instanceof" not in line and "=" not in m.group(2) \
                and text.count(line) == 1:
            out.append((line, "%sif (!(%s)) {" % (m.group(1), m.group(2)), "negate-if"))
            continue
        m = RET.match(line)
        if m and text.count(line) == 1:
            other = "false" if m.group(2) == "true" else "true"
            out.append((line, "%sreturn %s;" % (m.group(1), other), "flip-return"))
    return out


def fail_line(out):
    """The first thing the suite said about failing, short enough to match on."""
    for line in out.split(NL):
        said = line.strip()
        if said.startswith("FAIL:"):
            body = said[5:].strip()
        elif said.startswith("FAIL "):
            body = said[5:].strip()
        else:
            continue
        body = body.split(" (")[0].strip()
        #a message that names a path names THIS machine, and plants.json is a
        #shipped file. Keep the sentence, drop the drive letter.
        body = re.split(r"[A-Za-z]:[/\\]", body)[0].strip()
        if len(body) >= 20:
            return body[:90]
    return None


def try_one(src, find, repl, cls, args, java, javac):
    """Plant it, compile it, run the suite, put the file back. (verdict, said)."""
    raw, text, crlf = read(src)
    write_text(src, text.replace(find, repl), crlf)
    try:
        if not compile_one(src, javac):
            return "nocompile", ""
        code, said = run_suite(cls, args, java)
    finally:
        io.open(src, "wb").write(raw)
        if not compile_one(src, javac):
            raise SystemExit("PUT BACK BUT WILL NOT COMPILE: %s - fix the tree by hand" % src)
    if code is None:
        return "hung", ""
    if code == 0:
        return "survived", said
    return "red", said


def main(argv):
    book = json.load(io.open(LEDGER, encoding="utf-8"))
    home = jdk()
    if not home:
        print("No JDK found and CTRMAP_JDK is not set.")
        return 2
    java = os.path.join(home, "bin", "java.exe")
    javac = os.path.join(home, "bin", "javac.exe")
    pristine = os.environ.get(
        "CTRMAP_PRISTINE", os.path.join(os.path.dirname(ROOT), "RomFS_original_garcs"))
    gamedir = os.environ.get(
        "CTRMAP_GAMEDIR", os.path.join(os.path.dirname(ROOT), "RomFS", "000400000011C400"))

    rows = registered(pristine, gamedir)
    planted = set(p["suite"] for p in book["plants"])
    wanted = [a for a in argv[1:] if not a.startswith("-")]
    todo = [n for n in sorted(rows) if rows[n][0] not in planted]
    if wanted:
        todo = [n for n in todo if n in wanted]
    limit = int(next((a[8:] for a in argv if a.startswith("--limit=")), "9999"))
    files_per = int(next((a[8:] for a in argv if a.startswith("--files=")), "3"))
    tries_per = int(next((a[8:] for a in argv if a.startswith("--tries=")), "5"))
    after = next((x[8:] for x in argv if x.startswith("--after=")), None)
    if after:
        todo = [n for n in todo if n > after]
    budget_s = int(next((x[9:] for x in argv if x.startswith("--budget=")), "150"))
    todo = todo[:limit]
    print("%d registered suite(s) have no plant; trying %d" % (
        len(set(rows[n][0] for n in rows) - planted), len(todo)), flush=True)

    found = 0
    for name in todo:
        cls, written, resolved = rows[name]
        started = time.time()
        #A SUITE THAT IS ALREADY RED CANNOT PROVE ANYTHING BY STAYING RED.
        clean, _ = run_suite(cls, resolved, java)
        if clean != 0:
            print("  %-34s %-11s %ds  (already red or absent - nothing to prove)" % (
                name, "(skip)", time.time() - started), flush=True)
            continue
        got = None
        budget = tries_per
        for src in guarded_files(name, files_per):
            if got or budget <= 0 or time.time() - started > budget_s:
                break
            _, text, _ = read(src)
            for find, repl, kind in candidates(text):
                #A SLOW SUITE MUST NOT EAT THE RUN. Some of these take minutes
                #each (the building catalogue harvests 3,479 structures), and
                #five tries at that is an hour for one plant. Past the budget
                #this suite is left owed, which is the honest outcome - it is
                #counted in `owed` and somebody can write one by hand.
                if budget <= 0 or time.time() - started > budget_s:
                    break
                budget -= 1
                verdict, said = try_one(src, find, repl, cls, resolved, java, javac)
                if verdict != "red":
                    continue
                say = fail_line(said)
                if not say:
                    continue    #red for something it did not name: not a proof
                rel = os.path.relpath(src, ROOT).replace(os.sep, "/")
                got = {
                    "id": "%s-%s-%s" % (
                        (name[:-4] if name.endswith("Test") else name).lower(),
                        os.path.basename(src)[:-5].lower(), kind),
                    "why": ("Machine-found by tools/guard/autoplant.py: a %s in %s that this "
                            "suite was OBSERVED to catch. It proves the suite is not vacuous. "
                            "It is NOT a defect that reached a user, which is what the "
                            "hand-written plants put back - see owed_real_defect." % (kind, rel)),
                    "file": rel,
                    "find": find,
                    "replace": repl,
                    "suite": cls,
                    "args": written,
                    "must_say": say,
                    "auto": True,
                }
                break
        if got:
            book["plants"].append(got)
            found += 1
            #written out every time, so a run that is interrupted keeps what it found
            io.open(LEDGER, "w", encoding="utf-8", newline=NL).write(
                json.dumps(book, indent=1, ensure_ascii=False) + NL)
            print("  %-34s %-11s %ds  %s" % (
                name, got["id"].rsplit("-", 1)[-1], time.time() - started,
                got["must_say"][:46]), flush=True)
        else:
            print("  %-34s %-11s %ds" % (name, "(none)", time.time() - started), flush=True)

    print("%d new plant(s); the ledger now holds %d" % (found, len(book["plants"])), flush=True)
    print("REMEMBER: owed_real_defect rises by one for every suite this covered, and its "
          "ceiling in plants.json has to be raised by a human who has read them.", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
