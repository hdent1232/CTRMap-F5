#!/usr/bin/env python3
"""Refuse a commit that fixes something and cannot notice the second one.

SELF-CONTAINED ON PURPOSE. The project this came from spreads these checks across a larger
toolchain; here they are one file with no imports beyond the standard library, so the whole guard
can be copied into a repository and read in one sitting. A guard nobody can read is a guard
nobody keeps.

Four refusals, each with a bill attached:

  1. A FIX MUST TOUCH SOMEWHERE A GUARD CAN LIVE. "Producer with no consumer" was filed in SEVEN
     consecutive audits of the source project - fixed every time, made impossible none of them.
     This cannot judge whether a guard is any good; only planting the defect does that. It
     refuses the SHAPE that keeps costing: a source-only fix with nothing new that would notice
     a second occurrence.

  2. A FIX MUST SAY WHICH KIND OF GUARD IT BUILT. A detector reports the wreckage. A convention
     asks somebody to be careful. Only a REFUSAL closes the class. Three defects repeated in one
     session there WITH tracked, tested guards already in place, because every one of those
     guards sat on the damage rather than on the action.

  3. THE REASON MUST BE A CLASS, NOT A LABEL. Sixty characters minimum, because `guarded above`
     shipped once as an entire justification and it was false.

  4. A TEST COUNT IS A MEASUREMENT. A claim of "N tests" in a message is checked against the last
     recorded run. A wrong number in a commit message is permanent.

An honest exception is `No-guard: <reason>` in the message. It stays in `git log`, which is the
point: the decision is visible rather than absent.
"""
import io
import json
import os
import re
import sys

LF = chr(10)
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LAST_RUN = os.path.join(ROOT, ".last-suite-run")

#: Where a guard can live in THIS repository. `src/ctrmap/tests` is the Java suite the battery
#: runs; `tools/` and the hook directories are the refusals themselves.
GUARD_DIRS = ("src/ctrmap/tests", "tools", ".githooks", ".claude/hooks")

GUARD_KINDS = ("detector", "convention", "refusal")
MIN_GUARD_REASON = 60
NO_GUARD = "No-guard:"

#: `1,234 tests` or `57 tests`. A bare number followed by the word.
_TEST_CLAIM = re.compile(r"([0-9][0-9,]*)[ \t]+tests\b")
_GUARD_LINE = re.compile(r"^Guard:\s*([A-Za-z]+)\s*--\s*(.+)$", re.M | re.S)


def _message(path):
    try:
        return io.open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return ""


def subject_of(message):
    """The first line that is neither blank nor a comment."""
    for line in message.splitlines():
        if line.strip() and not line.startswith("#"):
            return line
    return ""


def is_a_fix(message):
    return subject_of(message).lower().startswith("fix")


def guard_line(message):
    """(kind, reason) from a `Guard:` line, or (None, '')."""
    found = _GUARD_LINE.search(message)
    if not found:
        return None, ""
    return found.group(1).strip().lower(), " ".join(found.group(2).split())


def staged_files():
    """Paths in the index, with forward slashes. Empty when git cannot be asked."""
    import subprocess
    try:
        done = subprocess.run(["git", "diff", "--cached", "--name-only"],
                              cwd=ROOT, capture_output=True, text=True)
    except OSError:                                    # pragma: no cover - git not on PATH
        return []
    return [p.strip().replace(chr(92), "/") for p in done.stdout.splitlines() if p.strip()]


def check_fix_has_a_guard(message):
    if not is_a_fix(message) or NO_GUARD.lower() in message.lower():
        return 0
    files = staged_files()
    if not files:                                      # nothing to judge; do not block
        return 0
    if any(f.startswith(d) for f in files for d in GUARD_DIRS):
        return 0
    sys.stderr.write(
        "REFUSING THE COMMIT: this fixes something and nothing new would notice a" + LF
        + "  SECOND one. Touch one of:" + LF
        + "".join("    %s%s" % (d, LF) for d in GUARD_DIRS)
        + "  'Producer with no consumer' was filed in SEVEN consecutive audits of the" + LF
        + "  project these guards came from - fixed every time, made impossible none." + LF
        + "  If this fix truly cannot carry one, say so in the message with a" + LF
        + "    %s <reason>" % NO_GUARD + LF
        + "  line, which stays in git log where anyone can find it." + LF)
    return 1


def check_guard_class(message):
    """Asks what the file-list check cannot: is what landed a REFUSAL, or a detector in its coat."""
    if not is_a_fix(message) or NO_GUARD.lower() in message.lower():
        return 0
    kind, reason = guard_line(message)
    if kind is None:
        sys.stderr.write(
            "REFUSING THE COMMIT: this fixes something and never says what kind of guard" + LF
            + "  it carries. Add a line:" + LF + LF
            + "    Guard: refusal -- <the CLASS this makes impossible, not the instance>" + LF
            + LF
            + "  A DETECTOR reports the wreckage. A CONVENTION asks somebody to be careful." + LF
            + "  Only a REFUSAL closes the class." + LF)
        return 1
    if kind not in GUARD_KINDS:
        sys.stderr.write("REFUSING THE COMMIT: `Guard: %s` is not one of %s.%s"
                         % (kind, ", ".join(GUARD_KINDS), LF))
        return 1
    if len(reason) < MIN_GUARD_REASON:
        sys.stderr.write(
            "REFUSING THE COMMIT: the Guard line names a kind and not a CLASS." + LF
            + "  %d characters is a label. Say what second occurrence is now" % len(reason) + LF
            + "  impossible, or impossible to miss. `guarded above` shipped once as an" + LF
            + "  entire justification and it was false." + LF)
        return 1
    return 0


def last_run():
    try:
        return json.load(io.open(LAST_RUN, encoding="utf-8"))
    except (OSError, ValueError):
        return None


def check_test_claim(message):
    """Every `N tests` claim must match the last recorded run.

    The LINE each claim sits on is quoted back, not just the number: a refusal that makes
    somebody scan a sixty-line message for a digit is its own small defect.
    """
    claimed = {}
    for line in message.splitlines():
        if line.startswith("#"):
            continue
        for found in _TEST_CLAIM.findall(line):
            claimed.setdefault(int(found.replace(",", "")), line.strip())
    if not claimed:
        return 0
    run = last_run()
    if run is None:
        sys.stderr.write(
            "REFUSING THE COMMIT: the message claims %s tests and no suite run has" % sorted(claimed)
            + LF + "  been recorded. Run `python tools\\guard\\record_run.py` first, or drop" + LF
            + "  the claim." + LF)
        return 1
    ran = int(run.get("ran") or 0)
    wrong = sorted(c for c in claimed if c != ran)
    if wrong:
        sys.stderr.write(
            "REFUSING THE COMMIT: the message claims %s tests; the last run said %d." % (wrong, ran)
            + LF + "  A count in a commit message is a measurement, and a wrong one is" + LF
            + "  permanent. The claim is on:" + LF
            + "".join("    %s%s" % (claimed[c], LF) for c in wrong))
        return 1
    return 0


def main(argv):
    if len(argv) < 2:
        return 0
    message = _message(argv[1])
    if not message.strip():
        return 0
    for check in (check_fix_has_a_guard, check_guard_class, check_test_claim):
        code = check(message)
        if code:
            return code
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
