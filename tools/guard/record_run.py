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
        "quick": bool(quick),
        "_why": ("`ran` is what a commit message may claim as `N tests`, and for this battery "
                 "that is the number of SUITES that passed. test.ps1 echoes only the last two "
                 "lines of each suite, so the per-suite check counts are not in its output at "
                 "all and summing whatever digits appear there would invent a number. A claim "
                 "this cannot verify is worse than one it refuses."),
    }


def main(argv):
    record = run(quick="--quick" in argv)
    if record is None:
        return 2
    io.open(LAST_RUN, "w", encoding="utf-8", newline=LF).write(
        json.dumps(record, indent=1, sort_keys=True) + LF)

    print("%d suite(s) passed in %.1fs" % (record["suites_passed"], record["seconds"]))
    if record["suites_failed"]:
        # NAMED, not counted. Finding out WHICH costs a whole battery otherwise.
        print("FAILED SUITES:")
        for name in record["suites_failed"]:
            print("    %s" % name)
    print("recorded to %s" % os.path.relpath(LAST_RUN, ROOT))
    return 0 if record["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
