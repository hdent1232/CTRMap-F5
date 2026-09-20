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


def run(quick=False):
    if not os.path.isfile(SCRIPT):
        sys.stderr.write("no test.ps1 at %s - is this the CTRMap root?%s" % (SCRIPT, LF))
        return None
    command = ["powershell", "-ExecutionPolicy", "Bypass", "-File", SCRIPT]
    if quick:
        command.append("-Quick")
    began = time.time()
    done = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
    text = done.stdout + done.stderr
    announced = [n.strip() for n in _SUITE.findall(text)]
    named = _FAILED.search(text)
    failed = [n.strip() for n in named.group(1).split(",") if n.strip()] if named else []
    if not named and not _ALL_PASS.search(text) and done.returncode != 0:
        # the battery refused before running anything - a missing dump, an unstamped
        # build - and there is no verdict line to read. Say that, rather than
        # recording a clean run of nought suites.
        failed = ["(the battery did not reach its verdict line; exit %d)" % done.returncode]
    passed = [n for n in announced if n not in failed]
    return {
        "at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
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
    record = run(quick="--quick" in argv)
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
