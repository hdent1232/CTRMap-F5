#!/usr/bin/env python3
"""Refuse a census that does not cover what it claims, or that knows what it missed.

WHY THIS EXISTS. On 2026-09-13 a whole-app census read 264 production files, confirmed 280
defects, and ended with a completeness critic that named ELEVEN things it had not covered: a
291-line workspace writer no agent opened, a 952-line engine recorded as "skimmed, not read",
93 of 147 suite files never read - which meant its own "already refused by a suite" exclusion
had been applied from 37% of the guard layer. All eleven were written into the queue as work
for the owner's next window. The run knew, and filed it.

`commit_guard.py` rule six refuses that filing. This is its other half, and the pair is the
point: a rule that only forbids REPORTING holes is satisfied fastest by not looking for them.
Drop the critic, claim coverage, ship a clean number. So:

  - commit_guard rule 6 refuses handing a known hole to the owner.
  - THIS refuses a clean report that has not earned the word: coverage is computed HERE, from
    the filesystem, against the scope the census names. A census cannot buy a pass by reading
    less, because the list of what it had to read is not something it gets to supply.

FIVE REFUSALS:

  1. NOTHING WAS READ. A census with an empty read list is not a clean census, it is one that
     did not run. Reported separately because it is the shape a broken harness produces, and
     "0 findings" from it reads exactly like "nothing is wrong".

  2. A FILE IN SCOPE WAS NOT READ. Scope is a list of directories the census claims; every
     source file under them is expanded here and must appear in the read list. This is the
     anti-gaming clause: narrowing what you look at is what it refuses.

  3. NO COMPLETENESS CHECK RAN. A census that never asked what it missed cannot claim it
     missed nothing. `critic.rounds` must be at least 1.

  4. THE COMPLETENESS CHECK FOUND SOMETHING. `critic.gaps` must be empty. A gap is work for
     the run that found it - that is the rule this file was written for.

  5. A READ-LIST ENTRY CANNOT BE TRUE. Scope computed from disk stops a census passing by
     looking at less; it does nothing about one that says it looked. Every entry carrying its
     own size - "(lines 1-100 of 210)", "(20,489 B)" - is checked against the file. Added after
     a run caught its own agent reporting a 210-line build.ps1 that is 115 lines everywhere.

THE INPUT, which is the contract:

    {"scope":      ["src/ctrmap"],              directories the census covered
     "filesRead":  ["src/ctrmap/Foo.java", ...] every file it actually read
     "critic":     {"rounds": 2, "gaps": []},   the completeness check and what it still names
     "findings":   [ ... ]}                     what it found; may be empty, if 1-5 all pass

Usage:
    python tools/guard/census.py <result.json>            # refuse, or say it is clean
    python tools/guard/census.py <result.json> --report out.md
    python tools/guard/census.py --selftest               # its own refusals, no census needed
"""
import io
import json
import os
import re
import subprocess
import sys

LF = chr(10)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
import bounded         # noqa: E402  - a deadline ends the child's whole tree
ROOT = _tree_root.require(__file__)

#: WHAT A CENSUS DOES NOT ASK TO HAVE OPENED - each kind with its reason. Everything else git
#: lists is in scope, whatever its name, and whether or not it has a suffix at all.
#:
#: This was the other way round: a hand list of suffixes to INCLUDE. It was short once already -
#: `build.ps1`, `run.bat` and `DummyKAGE.bin` were added after a census skipped the build, the
#: launcher and bytes that reach a save file - and it stayed short: measured 2026-10-08, 52 of 729
#: listed files could never count as unread, the git hooks that gate every commit among them, the
#: licence, the manifest and the models and tileset the program loads (F185). A kind nobody has
#: named here is IN scope, so the next one is read rather than skipped.
NOT_READ = {
    ".png": "an image - pixels, not code or data the program parses",
    ".ico": "the window icon - pixels, not code or data the program parses",
    ".jar": "a third-party library, compiled - its source is not in this repository to read",
}
#: Folders a claimed file is looked for in by name (`fabricated`), not a scope.
SKIP_DIRS = ("build", ".git", "dist", "__pycache__", "lib", "wt")
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0


def listed(root=None):
    """Every file git lists - tracked, and untracked but not ignored - repo-relative, or None
    when git cannot answer: a tree nobody could list is not a tree with nothing in it."""
    root = root or ROOT
    try:
        done = bounded.run(["git", "ls-files", "-co", "--exclude-standard", "-z"], cwd=root,
                           capture_output=True, timeout=60, creationflags=NO_WINDOW)
    except (OSError, subprocess.SubprocessError):
        return None
    if done.returncode != 0:
        return None
    return sorted(rel for rel in done.stdout.decode("utf-8", "replace").split(chr(0))
                  if rel and os.path.isfile(os.path.join(root, rel)))


def expand(scope, root=None):
    """Every file under the named paths a census must open, repo-relative with forward slashes,
    or None when the tree cannot be listed. A path named outright is in scope whatever its kind."""
    root = root or ROOT
    files = listed(root)
    if files is None:
        return None
    out = set()
    for rel in scope:
        rel = rel.replace(os.sep, "/").strip("/")
        if rel not in ("", ".") and os.path.isfile(os.path.join(root, rel)):
            out.add(rel)
            continue
        under = "" if rel in ("", ".") else rel + "/"
        out.update(path for path in files if path.startswith(under)
                   and os.path.splitext(path)[1].lower() not in NOT_READ)
    return out


def path_of(entry):
    """The path an entry names, with its size annotation removed.

    ONE PARSER, because there were two. `fabricated()` split an entry at its first "(" to find
    the file; this did not, so an annotated entry - `src/ctrmap/Zone.java (207 lines)`, which is
    the shape agents actually write - matched nothing in the scope and every file read that way
    counted as unread. The first real census this tool was ever pointed at reported 454 of 454
    files missed while holding 872 read entries, and the coverage refusal it exists for was
    therefore unusable on real input. Two functions reading one format is the defect this
    project's own README spends a page on.
    """
    text = str(entry).replace(chr(92), "/").strip()
    # ONLY A TRAILING GROUP IS AN ANNOTATION. This cut at the FIRST "(", so a file whose own name
    # holds one - `nbproject/configs/GfmapMainframe (1).properties`, two of them here - could
    # never be named in a read list and stayed unread whatever was opened (F185, 2026-10-08).
    note = _ANNOTATION.match(text)
    return (note.group(1) if note else text).strip()


#: A read-list entry's size annotation: one parenthesised group that ENDS the entry.
_ANNOTATION = re.compile(r"^(.*?\S)\s*\([^()]*\)\s*$")


def normalise(paths):
    """Read lists arrive with backslashes, absolute paths and repo prefixes. Compare by tail."""
    out = set()
    top = ROOT.replace(os.sep, "/").rstrip("/") + "/"
    for p in paths or []:
        q = path_of(p)
        if not q:
            continue
        # A PATH THAT NAMES A FILE OF THIS TREE KEEPS ITS PATH. Everything outside src/ and tools/
        # was cut to its basename, and these are a set - so the five copies of `_tree_root.py`,
        # all read, were ONE bare name covering one file, and a census of the whole tree could
        # never pass however much it read (measured 2026-10-08, F185: 11 such files).
        if q.lower().startswith(top.lower()):
            q = q[len(top):]
        if q.startswith("./"):
            q = q[2:]
        if os.path.isfile(os.path.join(ROOT, q.replace("/", os.sep))):
            out.add(q)
            continue
        low = q.lower()
        cut = low.find("/src/")
        if cut >= 0:
            q = q[cut + 1:]
        elif low.startswith("src/") or low.startswith("tools/"):
            pass
        else:
            q = os.path.basename(q)
        out.add(q)
    return out


#: `foo.java (lines 1-100 of 210)`, `foo.java (115 lines - all)`, `OUTSTANDING.md (20,489 B)`.
#: A read list annotated with a size is a read list that can be checked against the file.
_CLAIM_OF = re.compile(r"\bof\s+([0-9][0-9,]*)\s*(?:lines?)?\s*\)?\s*$", re.I)
_CLAIM_LINES = re.compile(r"\(\s*([0-9][0-9,]*)\s*lines?\b", re.I)
_CLAIM_BYTES = re.compile(r"\(\s*([0-9][0-9,]*)\s*B\s*\)", re.I)


def _count_lines(path):
    try:
        with io.open(path, "rb") as fh:
            return sum(1 for _ in fh)
    except OSError:
        return None


def fabricated(entries):
    """Read-list entries whose own annotation does not match the file on disk.

    WHY THIS IS HERE. The gap-closing round of 2026-09-13 caught one of its own agents claiming
    `CTRMap/build.ps1 (lines 1-100 of 210)`, with a coverage note about what lines 100-210 said.
    No copy of build.ps1 in any checkout is longer than 115 lines, and a second entry in the same
    batch put OUTSTANDING.md at 20,489 bytes when it is 19,829. A third was exactly right, which
    is what made the other two identifiable as invented rather than stale.

    That matters more than two wrong numbers. This whole file's method is "diff the disk against
    the read list", and a read list is a CLAIM. Scope being computed from disk stops a census
    passing by looking at less; it does nothing about a census that says it looked. So every
    entry that carries its own size is checked against the file, and a claim that cannot be true
    is refused - it is the only part of a read list that can be falsified mechanically, so it is
    the part that gets checked.
    """
    bad = []
    for raw in entries or []:
        text = str(raw).strip()
        if not text:
            continue
        path = path_of(text)
        full = os.path.join(ROOT, path.replace("/", os.sep))
        if not os.path.isfile(full):
            base = os.path.basename(path)
            hit = None
            for dirpath, dirs, files in os.walk(ROOT):
                dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
                if base in files:
                    hit = os.path.join(dirpath, base)
                    break
            if hit is None:
                if "(" in text:
                    bad.append("%s - no such file, and it is claimed with a size" % path)
                continue
            full = hit
        claimed = None
        for pattern in (_CLAIM_OF, _CLAIM_LINES):
            found = pattern.search(text)
            if found:
                claimed = int(found.group(1).replace(",", ""))
                break
        if claimed is not None:
            actual = _count_lines(full)
            if actual is not None and claimed > actual:
                bad.append("%s claims %d lines; the file has %d" % (path, claimed, actual))
            continue
        found = _CLAIM_BYTES.search(text)
        if found:
            claimed_b = int(found.group(1).replace(",", ""))
            try:
                actual_b = os.path.getsize(full)
            except OSError:
                continue
            if claimed_b != actual_b:
                bad.append("%s claims %d bytes; the file is %d" % (path, claimed_b, actual_b))
    return bad


def judge(result):
    """(ok, [problems]). The five refusals, in the order that makes the report readable."""
    problems = []
    scope = result.get("scope") or []
    read = normalise(result.get("filesRead"))
    critic = result.get("critic") or {}
    gaps = critic.get("gaps") or []

    if not read:
        problems.append(
            "NOTHING WAS READ. The read list is empty, so this is not a clean census - it is a"
            " census that did not run. A zero here and a zero from a real run look identical in"
            " a report, which is why it is refused rather than passed with a note.")

    if not scope:
        problems.append(
            "NO SCOPE. A census must say which directories it covered, because that is the list"
            " its coverage is checked against. Without it there is nothing to check and the"
            " word clean means nothing.")
    elif expand(scope) is None:
        problems.append(
            "THE TREE COULD NOT BE LISTED. git did not answer, so which files the scope holds is"
            " UNKNOWN - and an unknown scope covered is not a covered scope.")
    else:
        wanted = expand(scope)
        #: A BARE BASENAME COUNTS FOR ONE FILE, NOT FOR EVERY FILE OF THAT NAME. It used to
        #: satisfy any path with the same tail, so one `Zone.java` in the read list marked every
        #: `Zone.java` in the tree as read - and a project with `package-info.java` in thirty
        #: packages could cover thirty files by opening one. Bare names are matched against the
        #: scope ONCE each, in sorted order, so a list of them can never claim more than it has.
        bare = {}
        for entry in read:
            if "/" not in entry:
                bare[entry] = bare.get(entry, 0) + 1
        spent = {}
        missed = set()
        for w in sorted(wanted):
            if w in read:
                continue
            name = os.path.basename(w)
            if bare.get(name, 0) > spent.get(name, 0):
                spent[name] = spent.get(name, 0) + 1
                continue
            missed.add(w)
        if missed:
            shown = sorted(missed)
            problems.append(
                "%d FILE(S) IN SCOPE WERE NOT READ, out of %d. Scope is expanded from the tree"
                " git lists, so a census cannot pass by looking at less: %s%s"
                % (len(missed), len(wanted), ", ".join(shown[:12]),
                   " (+%d more)" % (len(shown) - 12) if len(shown) > 12 else ""))

    if int(critic.get("rounds") or 0) < 1:
        problems.append(
            "NO COMPLETENESS CHECK RAN. `critic.rounds` is %s. A census that never asked what it"
            " missed cannot report that it missed nothing, and dropping the question is the"
            " cheapest way to make this file say yes - which is exactly what it refuses."
            % critic.get("rounds"))

    if gaps:
        named = []
        for g in gaps[:8]:
            if isinstance(g, dict):
                named.append("%s:%s %s" % (g.get("file", "?"), g.get("line", "?"), g.get("title", "")))
            else:
                named.append(str(g))
        problems.append(
            "THE COMPLETENESS CHECK STILL NAMES %d GAP(S), and a gap is work for the run that"
            " found it, not for whoever reads the report: %s%s"
            % (len(gaps), "; ".join(named), " ..." if len(gaps) > 8 else ""))

    made_up = fabricated(result.get("filesRead"))
    if made_up:
        problems.append(
            "%d READ-LIST ENTR(IES) CANNOT BE TRUE. A read list is a claim, and its own size"
            " annotations are the part that can be falsified: %s%s"
            % (len(made_up), "; ".join(made_up[:6]),
               " (+%d more)" % (len(made_up) - 6) if len(made_up) > 6 else ""))

    return (not problems), problems


def report(result):
    """The census as a document. Only reachable once judge() says yes."""
    findings = result.get("findings") or []
    lines = ["# Census report", "",
             "%d finding(s), %d file(s) read across scope %s, completeness checked in %s round(s)"
             % (len(findings), len(normalise(result.get("filesRead"))),
                ", ".join(result.get("scope") or []),
                (result.get("critic") or {}).get("rounds")),
             ""]
    for f in findings:
        lines.append("- [%s/%s] %s:%s - %s"
                     % (f.get("severity", "?"), f.get("klass", "?"), f.get("file", "?"),
                        f.get("line", "?"), f.get("title", "")))
    return LF.join(lines) + LF


def selftest():
    """Each refusal, proven by handing it the shape it exists to refuse."""
    fails = []

    def expect(name, result, want_ok):
        ok, problems = judge(result)
        if ok != want_ok:
            fails.append("%s: wanted ok=%s, got ok=%s %s" % (name, want_ok, ok, problems))
        else:
            print("  ok: %s -> %s" % (name, "clean" if ok else problems[0][:78]))

    expect("a census that read nothing", {"scope": ["tools/guard"], "filesRead": [],
                                          "critic": {"rounds": 1, "gaps": []}}, False)
    expect("a census with no scope", {"scope": [], "filesRead": ["census.py"],
                                      "critic": {"rounds": 1, "gaps": []}}, False)
    expect("a census that skipped a file in scope",
           {"scope": ["tools/guard"], "filesRead": ["census.py"],
            "critic": {"rounds": 1, "gaps": []}}, False)
    # THE WHOLE TREE, FROM WHAT GIT LISTS (F185): the suffix list this replaced left out every
    # git hook - the commit gate itself - so a census of "." could pass without opening them.
    whole = expand(["."]) or set()
    for known in (".githooks/pre-commit", "LICENSE", "tools/guard/census.py"):
        if known not in whole:
            fails.append("control: the whole-tree scope does not hold %s" % known)
    skipped = {"scope": ["."], "filesRead": sorted(whole - {".githooks/pre-commit"}),
               "critic": {"rounds": 2, "gaps": []}}
    expect("a census of the whole tree that skipped a git hook", skipped, False)
    said = " ".join(judge(skipped)[1])
    if not said.startswith("1 FILE(S) IN SCOPE WERE NOT READ") or ".githooks/pre-commit" not in said:
        fails.append("a census of the whole tree that skipped a git hook was not refused for "
                     "exactly that hook: " + said[:160])
    expect("a census of the whole tree that read all of it",
           {"scope": ["."], "filesRead": sorted(whole), "critic": {"rounds": 2, "gaps": []}}, True)
    import tempfile
    nowhere = os.path.join(tempfile.mkdtemp(prefix="census-unlisted-"), "missing")
    if expand(["."], nowhere) is not None:
        fails.append("a tree git cannot list was given a scope - unknown read as empty")
    else:
        print("  ok: a tree git cannot list -> no scope, UNKNOWN")
    everything = sorted(expand(["tools/guard"]))
    expect("a census that read all of its scope but never asked what it missed",
           {"scope": ["tools/guard"], "filesRead": everything,
            "critic": {"rounds": 0, "gaps": []}}, False)
    expect("a census whose critic still names a gap",
           {"scope": ["tools/guard"], "filesRead": everything,
            "critic": {"rounds": 2, "gaps": [{"file": "x.java", "title": "never read"}]}}, False)
    expect("a census that covered its scope and closed its gaps",
           {"scope": ["tools/guard"], "filesRead": everything,
            "critic": {"rounds": 2, "gaps": []}}, True)
    expect("a census claiming lines a file does not have",
           {"scope": ["tools/guard"],
            "filesRead": everything + ["tools/guard/census.py (lines 1-100 of 99999)"],
            "critic": {"rounds": 2, "gaps": []}}, False)
    expect("a census claiming a byte count the file does not have",
           {"scope": ["tools/guard"],
            "filesRead": everything + ["tools/guard/census.py (12 B)"],
            "critic": {"rounds": 2, "gaps": []}}, False)

    print("ALL PASS" if not fails else "FAILURES PRESENT (%d)" % len(fails))
    for f in fails:
        print("  FAIL: " + f)
    return 1 if fails else 0


def main(argv):
    #a word this does not read refuses the run, before anything runs (cli_words, 2026-10-10)
    import _cli
    unread_words = _cli.refused(argv[1:], flags=("--selftest", "--report"), valued=("--report",), most=1)
    if unread_words:
        print(unread_words)
        return 2
    if "--selftest" in argv:
        return selftest()
    if len(argv) < 2:
        sys.stderr.write("usage: census.py <result.json> [--report out.md] | --selftest" + LF)
        return 2
    try:
        with io.open(argv[1], encoding="utf-8") as handle:
            result = json.load(handle)
    except (OSError, ValueError) as cannotRead:
        sys.stderr.write("census.py cannot read %s: %s%s" % (argv[1], cannotRead, LF))
        return 2

    ok, problems = judge(result)
    if not ok:
        sys.stderr.write("REFUSING TO REPORT THIS CENSUS AS COMPLETE:" + LF)
        for p in problems:
            sys.stderr.write("  - " + p + LF)
        sys.stderr.write(
            LF + "  Every one of these is work for the run that produced this, not for whoever" + LF
            + "  reads it. Go back and read the part that was skipped, then run this again." + LF)
        return 1

    text = report(result)
    if "--report" in argv:
        out = argv[argv.index("--report") + 1]
        with io.open(out, "w", encoding="utf-8", newline=LF) as handle:
            handle.write(text)
        print("census covered its scope and closed its gaps; report written to " + out)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
