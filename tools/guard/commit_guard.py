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

  5. A FIX IS CLOSED BY A REFUSAL, OR SAYS WHY IT CANNOT BE. A detector reports the wreckage
     after the fact; a convention asks somebody to be careful. Only a refusal makes the SECOND
     occurrence impossible rather than merely visible. The three kinds were already named here,
     and naming them turned out not to be the same as preferring one - fixes shipped as
     detectors because a detector is easier, and the class stayed open. So `Guard: detector`
     and `Guard: convention` are refused unless the message also carries a `No-refusal:` line
     saying why the point of action cannot refuse.

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
#: The escape from rule 5. A detector or a convention is allowed only with this line,
#: which stays in git log and says why a refusal was impossible HERE.
NO_REFUSAL = "No-refusal:"
#: The escape from rule 6, and the only way a hole reaches the owner: it must say why the
#: run that found it could not close it. "I ran out of turn" is a reason; silence is not.
DEFERRED_GAP = "Deferred-gap:"
#: The citation rule 7 wants: a count and the command that produced it.
REVIEWED = "Reviewed:"
NO_REVIEW = "No-review:"

#: A claim about things OUTSIDE this diff - the population, not the change. Deliberately narrow:
#: "every caller now catches it" describes the diff and is fine; "everything else holds" is a
#: statement about what was not opened.
_BLANKET = ("everything else", "all other", "every other", "the rest", "nothing else",
            "no others", "no other guard", "the remaining", "all remaining", "everything remaining")
#: ...asserted to have been EXAMINED and found sound, which is what makes it a coverage claim
#: rather than a note. Measured against 30 real messages when this was written: a looser list
#: including "already", "pass", "green" and "clean" refused two commits that were describing a
#: DEFECT - "every other zone already sharing them" is prose about shared areas, not a claim
#: that anything was reviewed. A rule that fires on honest work gets written around, and the
#: previous rule in this file spent 577 commits asking 33 of them for exactly that reason.
_SOUNDNESS = ("review", "audit", "verif", "checked out", "checked and", "holds", "hold their",
              "is fine", "are fine", "sound", "in order", "unaffected", "untouched",
              "no change needed", "nothing to fix", "all good")

#: WHAT A KNOWN HOLE SOUNDS LIKE. A measurement that names its own incompleteness - "no slice
#: opened it", "93 of 147 were never read", "skimmed, not read" - and then files that as work
#: for somebody else. Written as phrases rather than one clever pattern because the phrasing is
#: the evidence: these are the words a run uses when it knows it stopped early.
_HOLE_PHRASES = (
    "never read", "never opened", "never examined", "was not read", "were not read",
    "nobody read", "no slice", "not covered", "did not reach", "was skimmed", "skimmed, not read",
    "coverage-gap", "coverage gap", "unread", "did not cover", "could not cover",
    "the census missed", "the sweep missed", "the audit missed", "was applied from",
    "produced no findings although", "shallow coverage", "was not judged", "were not judged",
)
#: ...but only when it is being FILED, and filing has a shape: an open queue item. Prose about a
#: hole is how a fix for one gets written, so matching prose would refuse the very commits this
#: rule is trying to produce - including its own. An unticked box is the handover.
_OPEN_ITEM = re.compile(r"^\s*[-*]\s*\[\s*\]", re.M)
#: The escape, anchored the way `Guard:` is. Mentioning the token in a sentence about the rule is
#: not using it - the first version checked for the token anywhere and refused this file's own
#: commit for a zero-length reason it had read out of its own documentation.
_DEFERRED_LINE = re.compile(r"^Deferred-gap:\s*(.+)$", re.M | re.S)

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


#: Production source: the program, not its proofs and not its tooling.
def _is_production(path):
    return path.startswith("src/") and "/tests/" not in path and path.endswith(".java")


def _changes_behaviour():
    """True when this commit changes production CODE, as opposed to its comments.

    Asked of the staged DIFF rather than of the message, and that is the whole point of this
    function. See `is_a_fix`.
    """
    import subprocess
    prod = [p for p in staged_files() if _is_production(p)]
    if not prod:
        return False
    try:
        done = subprocess.run(["git", "diff", "--cached", "-U0", "--"] + prod,
                              cwd=ROOT, capture_output=True, text=True)
    except OSError:                                    # pragma: no cover - git not on PATH
        return True                                    # cannot tell: ask, do not assume innocent
    added, removed = [], []
    for line in done.stdout.splitlines():
        if line.startswith(("+++", "---", "@@")):
            continue
        if not line.startswith(("+", "-")):
            continue
        code = _code_of(line[1:])
        if not code:
            continue                       # blank, or a line that is nothing but a comment
        (added if line.startswith("+") else removed).append(code)
    # COMPARE THE CODE, NOT THE LINES. A trailing `//why` appended to an existing statement
    # changes the line and not the program, and the first version of this asked only whether a
    # line STARTED with a comment marker - so documenting a field read as a behaviour change and
    # the gate demanded a guard for it. A gate that fires on honest work gets switched off.
    return sorted(added) != sorted(removed)


def _code_of(text):
    """`text` with a trailing `//` comment removed, whitespace flattened; "" if all comment.

    Quote-aware, because `"http://x"` is not a comment and treating it as one would let two
    different statements normalise to the same thing - the one direction this must never get
    wrong, since equal-looking halves are what make a change read as comment-only.
    """
    body, quote, index = text, None, 0
    while index < len(body):
        char = body[index]
        if quote:
            if char == chr(92):
                index += 2
                continue
            if char == quote:
                quote = None
        elif char in ('"', chr(39)):
            quote = char
        elif char == "/" and body[index:index + 2] == "//":
            body = body[:index]
            break
        index += 1
    body = " ".join(body.split())
    if body.startswith(("/*", "*", "*/")):
        return ""
    return body


def is_a_fix(message):
    """Whether this commit must say what kind of guard it carries.

    IT USED TO ASK THE SUBJECT LINE whether it started with the word "fix". Measured across this
    repository's own history on 2026-09-14: 577 commits, 33 subjects starting with "fix", and 213
    commits that both change production code and describe a defect being closed. So the gate asked
    17% of the population it exists for, and 170 fixes went through without it ever putting the
    question - including every one written in this project's house style, which describes what the
    code USED TO DO rather than announcing itself as a fix.

    A trigger made of prose is not a refusal. It is a convention with a refusal's error message:
    reword the subject and the gate never fires, and nothing anywhere says it did not. So the
    trigger is now the STAGED DIFF - changing production code is the act that needs a guard, and
    no phrasing gets around it. The message half is kept as an OR, so a fix that lands entirely in
    `tools/` is still asked.

    Comment-only production changes are excluded deliberately: a ratchet that fires on honest work
    gets its ceiling raised, and that habit is what makes every other ratchet worthless.
    """
    return _changes_behaviour() or subject_of(message).lower().startswith("fix")


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
    if kind != "refusal" and NO_REFUSAL.lower() not in message.lower():
        sys.stderr.write(
            "REFUSING THE COMMIT: this fixes something and closes it with a %s." % kind + LF
            + "  A DETECTOR reports the wreckage after it happens. A CONVENTION asks" + LF
            + "  somebody to be careful. Neither makes a second occurrence IMPOSSIBLE," + LF
            + "  and the defects this project has had to fix twice were closed by one" + LF
            + "  of them the first time." + LF + LF
            + "  Close it where the mistake is MADE instead - refuse the action there." + LF
            + "  If the point of action genuinely cannot refuse, say why:" + LF + LF
            + "    %s <why a refusal is impossible here>" % NO_REFUSAL + LF + LF
            + "  That line stays in git log, which is the point." + LF)
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


def names_a_known_hole(text):
    """A run admitting it stopped early, in something that FILES that as open work.

    Both halves are required, and the second one is a shape rather than a vocabulary: an
    unticked queue item. A commit that says "nobody had read these, so they were read" is the
    behaviour being asked for and must pass; a commit that adds `- [ ] ... was never read` is
    the handover, and is what gets refused. Matching prose instead refused this rule's own
    commit, which is a fair test of a predicate and one it failed.
    """
    low = (text or "").lower()
    hit = [p for p in _HOLE_PHRASES if p in low]
    if not hit:
        return []
    if not _OPEN_ITEM.search(text or ""):
        return []
    return hit


def _added_to_queue():
    """Lines this commit ADDS to OUTSTANDING.md. Empty when git cannot be asked."""
    import subprocess
    try:
        done = subprocess.run(["git", "diff", "--cached", "--unified=0", "--", "OUTSTANDING.md"],
                              cwd=ROOT, capture_output=True, text=True)
    except OSError:                                    # pragma: no cover - git not on PATH
        return ""
    return LF.join(l[1:] for l in done.stdout.splitlines()
                   if l.startswith("+") and not l.startswith("+++"))


def check_known_hole(message):
    """RULE 6. A run that knows what it missed may not hand that to the owner.

    THE DEFECT THIS CLOSES, which happened here. A whole-app census ran for two hours, ended
    with a completeness critic that named eleven things it had not covered - a 291-line writer
    no agent opened, a 952-line engine recorded as "skimmed", 93 of 147 suite files never read -
    and all eleven were written into OUTSTANDING.md as work for the owner's next window. The
    run knew. Knowing and filing it is the whole failure: the owner had to read the report,
    understand the hole, and tell somebody to go back and do the part that was skipped.

    AND IT REFUSES THE CHEAP WAY OUT TOO, which is why the escape is a sentence and not a flag.
    A rule that only forbids REPORTING holes is satisfied fastest by not looking for them -
    drop the critic, claim coverage, ship. So this is one half of a pair: `census.py` refuses a
    report whose coverage is not proven against a file list it computes from disk itself, and
    this refuses the queue entry. Stopping the search fails the first; filing the result fails
    the second; the only way through both is to go and read the thing.
    """
    used = _DEFERRED_LINE.search(message or "")
    if used:
        reason = " ".join(used.group(1).split())
        if len(reason) < MIN_GUARD_REASON:
            sys.stderr.write(
                "REFUSING THE COMMIT: `%s` is there but says almost nothing (%d chars)." % (DEFERRED_GAP, len(reason))
                + LF + "  A hole handed to somebody else has to arrive with the reason the run" + LF
                + "  that found it could not close it, in a sentence they can disagree with." + LF)
            return 1
        return 0
    hit = names_a_known_hole(message) or names_a_known_hole(_added_to_queue())
    if not hit:
        return 0
    sys.stderr.write(
        "REFUSING THE COMMIT: this records something a run of yours KNOWS it did not cover" + LF
        + "  and files it as work rather than doing it. The words that say so: %s" % ", ".join(hit) + LF
        + LF
        + "  A census that ends by naming eleven things it skipped has not finished; it has" + LF
        + "  stopped. The owner then pays twice - once to read the hole, once to ask for the" + LF
        + "  work that was already understood. Go back and read the part that was skipped." + LF
        + LF
        + "  Do not answer this by looking less hard: `census.py` refuses a report whose" + LF
        + "  coverage is not proven against a file list it computes from disk, so dropping the" + LF
        + "  completeness check fails that one instead." + LF
        + LF
        + "  If it genuinely cannot be closed now - the tree is frozen, it needs a dump nobody" + LF
        + "  has - say so where it stays visible:" + LF + LF
        + "    %s <why the run that found it could not close it>" % DEFERRED_GAP + LF)
    return 1


def blanket_claim(message):
    """The phrases where a message vouches for what it did not open. [] when there are none."""
    low = " ".join((message or "").lower().split())
    hits = []
    for phrase in _BLANKET:
        at = low.find(phrase)
        while at >= 0:
            window = low[at:at + 140]
            if any(word in window for word in _SOUNDNESS):
                hits.append(low[max(0, at - 30):at + 70].strip())
                break
            at = low.find(phrase, at + 1)
    return hits


def check_blanket_claim(message):
    """RULE 7. A claim about what you did not open must cite the measurement that covers it.

    THE DEFECT, and it is this file's author again. A commit reviewing the project's guards
    examined fourteen of them - eight hooks, four tools, two scripts - and then wrote
    "Everything else reviewed holds its shape" into the message. There are 130 registered suites
    and 150 plants. The sentence was not a lie anybody told on purpose; it is what a review feels
    like from the inside when the part you looked at was the part you already knew, and nothing
    anywhere made the difference between fourteen and a hundred and fifty visible.

    The measurement that would have settled it took one command and two minutes when it was
    finally run: 130 suites, 81 driving a refusal, 12 reading source text alone, 53 owed a plant.

    SO A BLANKET CLAIM NEEDS A CITATION. Saying the rest are fine is a measurement, and a
    measurement has a number and a way to reproduce it:

        Reviewed: 130/130 suites by tools/guard/classify_guards.py

    The citation must name a path that exists, because "reviewed: all of them" is the same
    sentence with a colon in it. The escape is `No-review:` for a claim that genuinely rests on
    something other than counting - and it stays in git log, where somebody can disagree.

    NARROW ON PURPOSE. "Every caller now catches it" describes the diff and passes; "everything
    else holds" is about the population and does not. A rule that fires on honest prose gets its
    author writing around it, which is how the previous rule in this file spent 577 commits
    asking 33 of them.
    """
    hits = blanket_claim(message)
    if not hits:
        return 0
    low = message.lower()
    if NO_REVIEW.lower() in low:
        return 0
    cited = re.search(r"^Reviewed:\s*(.+)$", message, re.M)
    if cited:
        named = cited.group(1)
        paths = re.findall(r"[\w./\\-]+\.(?:py|ps1|java|md|json|sh|bat)", named)
        if any(os.path.exists(os.path.join(ROOT, p.replace(chr(92), "/"))) for p in paths):
            return 0
        sys.stderr.write(
            "REFUSING THE COMMIT: `%s` names nothing that exists in this repository." % REVIEWED
            + LF + "  A citation is a thing somebody else can run. %r resolves to no file here."
            % named.strip()[:90] + LF)
        return 1
    sys.stderr.write(
        "REFUSING THE COMMIT: this message vouches for things it did not open." + LF
        + "".join("    ...%s...%s" % (h, LF) for h in hits[:3])
        + LF
        + "  Saying the rest are fine is a MEASUREMENT, and this commit does not carry one." + LF
        + "  A review of this project's guards once covered fourteen of them and then wrote" + LF
        + "  \"everything else reviewed holds its shape\" about 130 suites and 150 plants." + LF
        + "  The count that settled it took one command." + LF + LF
        + "  Cite it, with a number and something runnable:" + LF + LF
        + "    %s 130/130 suites by tools/guard/classify_guards.py" % REVIEWED + LF + LF
        + "  Or, if the claim genuinely does not rest on counting, say so where it stays" + LF
        + "  visible:" + LF + LF
        + "    %s <what the claim rests on instead>" % NO_REVIEW + LF)
    return 1


def main(argv):
    if len(argv) < 2:
        return 0
    message = _message(argv[1])
    if not message.strip():
        return 0
    for check in (check_fix_has_a_guard, check_guard_class, check_test_claim, check_known_hole,
                  check_blanket_claim):
        code = check(message)
        if code:
            return code
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
