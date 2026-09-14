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

FOUR REFUSALS:

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

THE INPUT, which is the contract:

    {"scope":      ["src/ctrmap"],              directories the census covered
     "filesRead":  ["src/ctrmap/Foo.java", ...] every file it actually read
     "critic":     {"rounds": 2, "gaps": []},   the completeness check and what it still names
     "findings":   [ ... ]}                     what it found; may be empty, if 1-4 all pass

Usage:
    python tools/guard/census.py <result.json>            # refuse, or say it is clean
    python tools/guard/census.py <result.json> --report out.md
    python tools/guard/census.py --selftest               # its own refusals, no census needed
"""
import io
import json
import os
import sys

LF = chr(10)
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

#: What counts as a file a census had to read. Source and the data the program ships; not
#: build output, not the version-control directory.
SOURCE_SUFFIXES = (".java", ".py", ".ps1", ".tsv", ".md", ".properties", ".form", ".xml")
SKIP_DIRS = ("build", ".git", "dist", "__pycache__", "lib", "wt")


def expand(scope):
    """Every source file under the named directories, repo-relative, forward slashes."""
    out = set()
    for rel in scope:
        base = os.path.join(ROOT, rel.replace("/", os.sep))
        if os.path.isfile(base):
            out.add(rel.replace(os.sep, "/"))
            continue
        for dirpath, dirs, files in os.walk(base):
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
            for name in files:
                if not name.endswith(SOURCE_SUFFIXES):
                    continue
                full = os.path.join(dirpath, name)
                out.add(os.path.relpath(full, ROOT).replace(os.sep, "/"))
    return out


def normalise(paths):
    """Read lists arrive with backslashes, absolute paths and repo prefixes. Compare by tail."""
    out = set()
    for p in paths or []:
        q = str(p).replace(chr(92), "/").strip()
        if not q:
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


def judge(result):
    """(ok, [problems]). The four refusals, in the order that makes the report readable."""
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
    else:
        wanted = expand(scope)
        by_tail = {}
        for w in wanted:
            by_tail.setdefault(os.path.basename(w), set()).add(w)
        missed = set()
        for w in wanted:
            if w in read:
                continue
            if os.path.basename(w) in read:
                continue
            missed.add(w)
        if missed:
            shown = sorted(missed)
            problems.append(
                "%d FILE(S) IN SCOPE WERE NOT READ, out of %d. Scope is expanded from disk here,"
                " so a census cannot pass by looking at less: %s%s"
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

    print("ALL PASS" if not fails else "FAILURES PRESENT (%d)" % len(fails))
    for f in fails:
        print("  FAIL: " + f)
    return 1 if fails else 0


def main(argv):
    if "--selftest" in argv:
        return selftest()
    if len(argv) < 2:
        sys.stderr.write("usage: census.py <result.json> [--report out.md] | --selftest" + LF)
        return 2
    try:
        result = json.load(io.open(argv[1], encoding="utf-8"))
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
        io.open(out, "w", encoding="utf-8", newline=LF).write(text)
        print("census covered its scope and closed its gaps; report written to " + out)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
