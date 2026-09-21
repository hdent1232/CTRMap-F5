#!/usr/bin/env python3
"""Run CTRMap's regression battery and RECORD what it said.

Two jobs, and the second is the one that pays:

  * run `test.ps1` and report the outcome;
  * write `.last-suite-run` so a commit message claiming "N tests" can be checked against a real
    run rather than against a memory of one.

IT NAMES THE SUITES THAT FAILED. A runner that says "3 suites failed" costs a second full battery
to find out which three - and this battery is not quick. The source project fixed exactly that
defect in its own runner after paying for it twice. test.ps1 already names them on its last line;
this reads that line rather than guessing from the per-suite echo, which cannot tell a suite that
failed from one whose last two lines simply did not contain the word.

    python tools/guard/record_run.py            # full battery
    python tools/guard/record_run.py --quick    # test.ps1 -Quick
"""
import io
import json
import os
import re
import subprocess
import sys
import time

LF = chr(10)
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LAST_RUN = os.path.join(ROOT, ".last-suite-run")
SCRIPT = os.path.join(ROOT, "test.ps1")

#: WHAT test.ps1 ACTUALLY PRINTS, which is not what the generic parser this file arrived with
#: assumed. Each suite is announced on its own line as "--- <name>", then AT MOST THE LAST TWO
#: LINES of that suite's own output are echoed, indented. The verdict is never on the same line
#: as the name. The generic parser read the bare line "    ALL PASS" as a suite called "ALL" that
#: passed, so a full battery recorded one suite and named none of the real ones - a runner that
#: is confidently wrong about what it just ran is worse than one that refuses.
#:
#: The run's verdict is the LAST line: "ALL SUITES PASS  (123s)", or "FAILED: a, b, c" naming
#: every suite that failed. That line is the authority; the per-suite echo is not, because a
#: suite whose last two lines happen to contain neither word is not thereby a failure.
_SUITE = re.compile(r"^--- (.+?)\s*$", re.M)
_FAILED = re.compile(r"^FAILED:\s*(.+?)\s*$", re.M)
_ALL_PASS = re.compile(r"^ALL SUITES PASS\b", re.M)


def failed_in(text, announced):
    """Which ANNOUNCED suites the verdict line names as failed.

    NOT `line.split(",")`. test.ps1 joins the failed names with ", " and suite names contain
    ", " themselves - "Battery hygiene (temp paths, corpus args)" is one suite - so splitting
    on the separator cuts a name in half. MEASURED on the run of 2026-09-21: four suites
    failed, the split produced five names, two of which matched nothing announced, and
    `passed = [n for n in announced if n not in failed]` therefore counted the FAILING battery
    hygiene suite as PASSED. The record said 133 of 136 passed with four red, and that record
    is the only number a commit message is allowed to claim.

    The announced names are already known and exact, so ask which of them the line contains
    rather than trying to cut it up. And then ACCOUNT FOR THE WHOLE LINE: if anything is left
    over after removing the names that matched, a suite has been renamed or the verdict line
    has changed shape, and the difference between that and "nothing failed" must not be
    silent - which is the very defect being fixed, one level up.
    """
    named = _FAILED.search(text)
    if not named:
        return []
    line = named.group(1)
    hits = [n for n in announced if n and n in line]
    rest = line
    for name in sorted(hits, key=len, reverse=True):
        rest = rest.replace(name, "", 1)
    #: The SEPARATORS are what is left between the names that were removed, and they are not
    #: leftover text. Trimming only the ends left ", ," in the middle of four removed names and
    #: reported a phantom fifth failure - caught by the control, which drove this on the real
    #: verdict line from the run that exposed the defect in the first place.
    rest = rest.replace(",", " ").strip()
    if rest:
        hits.append("(the verdict line names something that was never announced: %r - a suite"
                    " renamed, or this parser out of step with test.ps1)" % rest[:120])
    return hits


def run(quick=False, anyway=None):
    if not os.path.isfile(SCRIPT):
        sys.stderr.write("no test.ps1 at %s - is this the CTRMap root?%s" % (SCRIPT, LF))
        return None
    command = ["powershell", "-ExecutionPolicy", "Bypass", "-File", SCRIPT]
    if quick:
        command.append("-Quick")
    #: THE QUEUE GATE'S ESCAPE HAS TO REACH THE RECORDER. test.ps1 refuses to run while
    #: OUTSTANDING.md carries open work, and offers `-Anyway "<reason>"`. This file could not
    #: pass one - so for as long as the queue had anything in it, the ONLY recorder could not
    #: record anything, and the way round that is to run test.ps1 by hand and write the number
    #: into .last-suite-run, which is hand-editing a measurement. A guard whose cheapest
    #: satisfaction is forging its own record is worse than no guard.
    if anyway:
        command += ["-Anyway", anyway]
    #: THE TREE IS DIGESTED ON BOTH SIDES OF THE BATTERY, and a run whose tree moved
    #: underneath it is not recorded at all. NEVER EDIT SOURCE WHILE THE SUITE IS RUNNING had
    #: a hook behind it, and a hook only sees edits made through the tools - an edit made in
    #: the owner's IDE during a two-hour battery was invisible to it and produced a result
    #: that is neither the old code nor the new one and looks green. This is the half that
    #: does not care who made the edit. `commit_guard.source_digest` is asked for both, so
    #: there is one implementation of "which tree" rather than two that agree until they do
    #: not - and None from it means COULD NOT LOOK, which is refused, not waved through.
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import commit_guard
    before = commit_guard.source_digest(ROOT)
    began = time.time()
    done = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=7200)
    after = commit_guard.source_digest(ROOT)
    if before is None or after is None:
        sys.stderr.write(
            "REFUSING TO RECORD: this tree cannot be digested, so which tree the battery"
            " just measured is UNKNOWN.%s  An unreadable subject is not an unchanged one."
            "%s" % (LF, LF))
        return None
    if before != after:
        sys.stderr.write(
            "REFUSING TO RECORD: the tree CHANGED while the battery was running (%s -> %s)."
            "%s  The result is neither the old code nor the new one and it looks green."
            "%s  Two full runs were thrown away this way. Re-run it on a still tree.%s"
            % (before[:12], after[:12], LF, LF, LF))
        return None
    text = done.stdout + done.stderr
    announced = [n.strip() for n in _SUITE.findall(text)]
    failed = failed_in(text, announced)
    if not named and not _ALL_PASS.search(text) and done.returncode != 0:
        # the battery refused before running anything - a missing dump, an unstamped
        # build - and there is no verdict line to read. Say that, rather than
        # recording a clean run of nought suites.
        failed = ["(the battery did not reach its verdict line; exit %d)" % done.returncode]
    passed = [n for n in announced if n not in failed]
    return {
        "at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "subject": after,
        #: ...AND THE REASON IS RECORDED, not merely typed. An escape that lives only in the
        #: shell line that invoked it is invisible to everyone who later reads the number, and
        #: this number is the one a commit message is allowed to claim. None means the tree was
        #: frozen and the gate had nothing to say.
        "anyway": anyway or None,
        "seconds": round(time.time() - began, 1),
        "exit_code": done.returncode,
        "ok": done.returncode == 0 and not failed,
        "suites_announced": len(announced),
        "suites_passed": len(passed),
        "suites_failed": failed,
        "ran": len(passed),
        "slackRatchets": slack_ratchets(text),
        "quick": bool(quick),
        "_why": ("`ran` is what a commit message may claim as `N tests`, and for this battery "
                 "that is the number of SUITES that passed. test.ps1 echoes only the last two "
                 "lines of each suite, so the per-suite check counts are not in its output at "
                 "all and summing whatever digits appear there would invent a number. A claim "
                 "this cannot verify is worse than one it refuses."),
    }


#: "118 silent catch(es) ... against a ceiling of 119" - a ratchet reporting its measurement.
_RATCHET = re.compile(r"\b([0-9][0-9,]*)\b[^\n]{0,160}?against a ceiling of\s+([0-9][0-9,]*)",
                      re.I)


def slack_ratchets(text):
    """Every ratchet whose measurement is UNDER its ceiling, with the gap.

    A RATCHET ONLY BITES WHILE IT IS KEPT TIGHT. Measured on 2026-09-20: the silent-catch
    ceiling stood at 119 while the count was 118, so there was one free slot - and a plant that
    added a silent catch landed in it and SURVIVED. The guard was on, reported green, and could
    not see a defect of exactly the size of its own slack.

    Slack is honest for the length of one fix and no longer: you lower the number in the same
    commit that lowers the count. This is what makes that mechanical instead of remembered.
    """
    out = []
    for found in _RATCHET.finditer(text or ""):
        now = int(found.group(1).replace(",", ""))
        ceiling = int(found.group(2).replace(",", ""))
        if now < ceiling:
            out.append((now, ceiling, found.group(0).strip()[:120]))
    return out


def main(argv):
    anyway = None
    if "--anyway" in argv:
        at = argv.index("--anyway")
        anyway = argv[at + 1] if at + 1 < len(argv) else ""
        if len(anyway.strip()) < 20:
            sys.stderr.write(
                "REFUSING: --anyway needs a REASON, not a flag.%s"
                "  It is written into .last-suite-run and read by everyone who later reads"
                " the count.%s  Say what is queued and why this run is still worth taking."
                "%s" % (LF, LF, LF))
            return 2
        anyway = anyway.strip()
    record = run(quick="--quick" in argv, anyway=anyway)
    if record is None:
        return 2
    io.open(LAST_RUN, "w", encoding="utf-8", newline=LF).write(
        json.dumps(record, indent=1, sort_keys=True) + LF)

    print("%d suite(s) passed in %.1fs" % (record["suites_passed"], record["seconds"]))

    # A STARTLING MAGNITUDE IS A BUG REPORT, and this number is the one a commit
    # message is allowed to claim. A battery that suddenly announces a third fewer suites
    # has not become faster - something stopped registering, and the count looks exactly as
    # healthy as it did before. Recorded AFTER .last-suite-run is written, so a startling
    # move is reported without throwing away a battery that has already run.
    try:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import magnitude
        ok, said = magnitude.record("battery.suites_announced", record["suites_announced"],
                                    why=" ".join(argv[argv.index("--magnitude") + 1:])
                                    if "--magnitude" in argv else None)
        print(said)
        if not ok:
            return 1
    except Exception as cannotCompare:              # pragma: no cover
        print("WARNING: could not compare this run's size to the last (%s: %s)"
              % (type(cannotCompare).__name__, cannotCompare))
    if record["suites_failed"]:
        # NAMED, not counted. Finding out WHICH costs a whole battery otherwise.
        print("FAILED SUITES:")
        for name in record["suites_failed"]:
            print("    %s" % name)
    print("recorded to %s" % os.path.relpath(LAST_RUN, ROOT))
    slack = record.get("slackRatchets") or []
    if slack:
        # A RATCHET WITH SLACK IS A DEFECT OF EXACTLY THAT SIZE THAT THE GUARD CANNOT
        # SEE. Measured: the silent-catch ceiling stood at 119 against a count of 118,
        # and a plant that added one landed in the free slot and SURVIVED while the
        # suite reported green.
        print("REFUSING TO RECORD: %d ratchet(s) are not tight." % len(slack))
        for now, ceiling, said in slack:
            print("    %d against a ceiling of %d - %d free slot(s): %s"
                  % (now, ceiling, ceiling - now, said))
        print("  Lower the ceiling to what was measured. Slack is honest for the length of")
        print("  one fix and no longer: it is lowered in the same commit that lowers the")
        print("  count, or the guard stops noticing a defect the size of the gap.")
        return 1
    return 0 if record["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
