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


def source_digest(root=ROOT):
    """WHICH TREE a measurement was taken against, or None when that cannot be read.

    Git's own answer to "what here is source" - tracked files plus untracked ones it does not
    ignore - minus DOTFILES AT THE REPOSITORY ROOT, which is where the records themselves
    live. That exclusion is a shape and not a list of names on purpose: `.last-suite-run`
    would otherwise be part of its own subject, and the scratch repositories the suites build
    carry no `.gitignore` at all, so excluding it by ignore-rule would hold here and not
    there. Everything a suite can read moves this digest - `tools/guard/magnitudes.json` and
    `plants.json` included, because a suite reads each of those and a changed number is a
    changed verdict, not noise.

    Line endings are normalised for the reason `replant.target_digest` normalises them: this
    tree is already mixed and autocrlf rewrites on commit, so raw bytes would refuse a record
    taken against identical content in a different checkout.

    None means COULD NOT LOOK - no git, not a repository, a call that failed or timed out -
    and the caller must refuse on it rather than read it as "nothing has changed". A query
    that cannot read its subject must not report it absent.
    """
    import hashlib
    import subprocess
    try:
        done = subprocess.run(
            ["git", "-C", root, "ls-files", "--cached", "--others", "--exclude-standard"],
            capture_output=True, text=True, timeout=300)
    except (OSError, subprocess.SubprocessError):
        #: TimeoutExpired is a SubprocessError and NOT an OSError - catching only OSError
        #: here would let a hung git escape as a traceback, which is the third time that
        #: exact pair has been got wrong in this repository.
        return None
    if done.returncode != 0:
        return None
    digest = hashlib.sha256()
    for rel in sorted(line.strip() for line in done.stdout.splitlines() if line.strip()):
        if rel.startswith(".") and "/" not in rel:
            continue
        digest.update(rel.encode("utf-8"))
        try:
            with open(os.path.join(root, rel.replace("/", os.sep)), "rb") as handle:
                digest.update(handle.read().replace(b"\r\n", b"\n"))
        except OSError:
            digest.update(b"<unreadable>")
    return digest.hexdigest()

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

#: -------- EXTRACTION IS NOT TESTING ------------------------------------------------
#: Two modules split out of one function in the same refactor scored 94.7% and 37.3% under
#: mutation. The only difference was that tests were deliberately written for one of them
#: afterwards. Decomposition makes code REACHABLE for testing, which is not the same as tested,
#: and a new file with nothing naming it is the shape that scored 37.3.
NO_TEST = "No-test:"

#: -------- DETANGLE, DO NOT RELOCATE ------------------------------------------------
#: Splitting a 3,000-line god object into ten 300-line files whose pieces still read and write
#: the same shared state is worse - the tangle is now in ten places. The metric is COUPLING,
#: not line count, so a commit that says it split something has to say what the edges did.
COUPLING = "Coupling:"
NO_COUPLING = "No-coupling:"
_SPLIT_WORDS = ("split", "extract", "decompos", "break up", "broke out", "carve", "pull out",
                "pulled out", "separate into")
_COUPLING_LINE = re.compile(r"^Coupling:\s*([0-9]+)\s*(?:->|to|=>)\s*([0-9]+)", re.M)

#: -------- THE OWNER DOES THE IN-APP AND IN-GAME TESTING -----------------------------
#: A suite cannot load the game. Every claim that something was checked in the editor or in the
#: ROM is either the owner's, quoted, or it is not a claim anybody can act on.
OWNER_TESTED = "Owner-tested:"
_IN_GAME = (
    "tested in game", "tested in-game", "in-game test", "verified in game",
    "verified in-game", "loaded the rom", "loaded in azahar", "ran the editor",
    "opened the editor", "checked in the editor", "played through", "booted the game",
    "confirmed in game", "confirmed in-game", "works in game", "works in-game",
)

#: -------- A CONFIDENT EMPTY RESULT IS A BUG UNTIL PROVEN OTHERWISE ------------------
#: Twice a scan here returned 0 across 536 files because it read the wrong struct field. A
#: probe that reports a confident ZERO is the dangerous result: it looks like a clean negative.
CONTROL = "Control:"
_ZERO_CLAIM = re.compile(
    r"\b(?:scan(?:ned)?|check(?:ed)?|search(?:ed)?|swept|sweep|audit(?:ed)?|grep(?:ped)?|"
    r"count(?:ed)?|measur(?:ed|ement)|probe[ds]?)\b[^.\n]{0,120}?"
    r"\b(?:0|zero|no|none|nothing|nowhere)\b", re.I)



#: -------- UNDO/REDO EVERYWHERE ------------------------------------------------------
#: The owner's standing rule, and the half of FEATURES LIVE IN THEIR OWN UI AREA that has
#: nothing else behind it. Undo exists in ONE of 79 files under humaninterface today, so this
#: binds new work rather than banning the existing 78: a form that lands without an undo story
#: is one the user cannot get out of, and the answer is cheapest to write while the form is
#: being written.
UNDO = "Undo:"
NO_UNDO = "No-undo:"
_UI_HOME = "src/ctrmap/humaninterface/"
#: ...and a SURFACE, not every class that lives there. The first version asked this
#: of CM3DInputManager, which handles key events and has no undo story of its own,
#: and it reddened four fixtures of this gate's own suite.
_UI_SURFACE = ("Form", "Dialog", "Editor", "Wizard", "Tab", "Panel")

#: -------- FEATURES LIVE IN THEIR OWN UI AREA ----------------------------------------
#: The owner's standing rule since this project began: never menu-dumped, never a new window;
#: seldom-used goes to the Extras tab; undo/redo everywhere. MainframeShapeTest pins the menu
#: bar exactly and caps how many items it may hold, but both of those are read AFTER the item
#: is written. This asks the question in the log, where the answer stays.
MENU_ITEM = "Menu-item:"

#: -------- ASK WHAT THE CHEAPEST WAY TO SATISFY YOUR OWN GUARD IS --------------------
#: Every guard creates an incentive to satisfy it cheaply, and the cheap way is usually to do
#: LESS work rather than more. A rule that forbids reporting a hole is satisfied fastest by not
#: looking for one. A guard that has never had that question asked of it is a guard with a
#: known way round it that nobody has written down.
EVASION = "Cheapest-evasion:"
_GUARD_HOMES = ("tools/guard/", "tools/hooks/", ".claude/hooks/", ".githooks/")



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
                              cwd=ROOT, capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):       # pragma: no cover
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
                              cwd=ROOT, capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):       # pragma: no cover
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


def record_is_about_another_tree(run):
    """Why the recorded run is not evidence about THIS tree. None means it is.

    MEASURED 2026-09-21: the record on disk was eight days and twenty-two changed files old
    and this check was still comparing against it - it would have APPROVED a claim of the
    stale count and REFUSED the true one, both silently. `work_order.unproven_plants` sits
    ten lines from here and digests src/, the plant targets and the plant count before it
    will call a proof current; this one read a number out of a file and believed it. A rule
    enforced for one measurement and not for its neighbour is the shape that keeps costing.
    """
    if not run.get("subject"):
        return ("the recorded run carries no subject digest, so WHICH TREE it measured is"
                " UNKNOWN - re-run `python tools\\guard\\record_run.py`")
    now = source_digest()
    if now is None:
        return ("this tree cannot be digested, so whether that run is about it cannot be"
                " read - and an unreadable subject is not an unchanged one")
    if now != run["subject"]:
        return ("the tree has CHANGED since that run - it measured %s, this is %s"
                % (run["subject"][:12], now[:12]))
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
    why = record_is_about_another_tree(run)
    if why:
        sys.stderr.write(
            "REFUSING THE COMMIT: the message claims %s tests and %s." % (sorted(claimed), why)
            + LF + "  A count measured against a different tree is not a measurement of this" + LF
            + "  one, in either direction: it approves a stale number and refuses the true" + LF
            + "  one. Re-run the battery, or drop the claim. The claim is on:" + LF
            + "".join("    %s%s" % (claimed[c], LF) for c in sorted(claimed)))
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
                              cwd=ROOT, capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):       # pragma: no cover
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


def added_files():
    """Paths ADDED by this commit, with forward slashes. Empty when git cannot be asked."""
    import subprocess
    try:
        done = subprocess.run(["git", "diff", "--cached", "--name-status", "--diff-filter=A"],
                              cwd=ROOT, capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):       # pragma: no cover
        return []
    out = []
    for line in done.stdout.splitlines():
        parts = line.split(chr(9))
        if len(parts) >= 2:
            out.append(parts[-1].strip().replace(chr(92), "/"))
    return out


def check_extraction_is_tested(message):
    """A new production class that no test so much as names is not tested, only reachable.

    EXTRACTION IS NOT TESTING. Two modules split out of one function in the same refactor
    scored 94.7% and 37.3% under mutation; the only difference was that tests were written for
    one of them afterwards. This is the cheapest possible version of the question - does any
    test file mention the class at all - because the expensive version, "is it tested well",
    is what the mutation sweep answers, and a commit gate cannot run one.
    """
    if NO_TEST.lower() in message.lower():
        return 0
    #: A REPOSITORY WITH NO TEST TREE IS NOT ONE THIS RULE IS ABOUT. Without this, every
    #: commit in a scratch repository - which is how the suites drive this gate - was refused
    #: for adding production code to a tree that has nowhere to put a test, and four sections
    #: of CommitGuardTest went red for a reason that had nothing to do with what they assert.
    #: The cheapest way round the rule is therefore to delete src/ctrmap/tests, which would
    #: take 133 registered suites with it and is not a quiet evasion.
    if not os.path.isdir(os.path.join(ROOT, "src", "ctrmap", "tests")):
        return 0
    added = [f for f in added_files()
             if f.endswith(".java") and _is_production(f)]
    if not added:
        return 0
    unnamed = []
    for path in added:
        name = os.path.basename(path)[:-len(".java")]
        if not _named_by_a_test(name):
            unnamed.append(path)
    if not unnamed:
        return 0
    sys.stderr.write(
        "REFUSING THE COMMIT: this adds production code that NO TEST NAMES." + LF
        + "".join("    %s%s" % (p, LF) for p in unnamed)
        + "  EXTRACTION IS NOT TESTING. Two modules split out of one function in the same" + LF
        + "  refactor scored 94.7% and 37.3% under mutation - the only difference was that" + LF
        + "  tests were deliberately written for one of them afterwards. Decomposition makes" + LF
        + "  code REACHABLE for testing, which is not the same as tested." + LF
        + "  Write the test, or say why there is none with a" + LF
        + "    %s <reason>" % NO_TEST + LF
        + "  line, which stays in git log where anyone can disagree with it." + LF)
    return 1


def _named_by_a_test(name):
    """Whether any file under a test directory mentions this class by name."""
    for base in ("src/ctrmap/tests", "tools"):
        root = os.path.join(ROOT, base)
        for dirpath, dirs, files in os.walk(root):
            dirs[:] = [d for d in dirs if d not in ("__pycache__",)]
            for f in files:
                if not f.endswith((".java", ".py", ".ps1")):
                    continue
                #: ZoneTest.java names Zone in the place a reader looks first. Reading only
                #: the contents refused a commit that shipped the test beside the class,
                #: which is the exact thing this rule is asking for.
                if name in f:
                    return True
                try:
                    with io.open(os.path.join(dirpath, f), encoding="utf-8",
                                 errors="replace") as handle:
                        if name in handle.read():
                            return True
                except OSError:
                    continue
    return False


def check_detangle(message):
    """A commit that says it SPLIT something has to say what the coupling did.

    DETANGLE, DO NOT RELOCATE. Ten 300-line files whose pieces still read and write the same
    shared state are worse than one 3,000-line file: the tangle is now in ten places and each
    piece looks small. The metric is edges, not lines, and a split that does not reduce them
    has moved code rather than untangled it.
    """
    low = message.lower()
    if NO_COUPLING.lower() in low:
        return 0
    if not any(word in low for word in _SPLIT_WORDS):
        return 0
    if not [f for f in added_files() if f.endswith(".java") and _is_production(f)]:
        return 0
    found = _COUPLING_LINE.search(message)
    if found and int(found.group(2)) < int(found.group(1)):
        return 0
    sys.stderr.write(
        "REFUSING THE COMMIT: this says it split or extracted something and adds" + LF
        + "  production files, and it does not show the coupling going DOWN." + LF
        + "  DETANGLE, DO NOT RELOCATE: splitting a 3,000-line god object into ten" + LF
        + "  300-line files whose pieces still read and write the same shared state is" + LF
        + "  WORSE - the tangle is now in ten places and every piece looks small." + LF
        + "  Count what each span depends on before and after; each extracted piece must" + LF
        + "  depend on a strict subset, and the total must go down. Then say so:" + LF
        + "    %s <before> -> <after>" % COUPLING + LF
        + "  or, if this is not that kind of change, say why with a" + LF
        + "    %s <reason>" % NO_COUPLING + LF
        + "  line." + LF)
    return 1


def check_in_game_claim(message):
    """Nothing here can load the game, so nothing here may claim it did.

    THE OWNER DOES THE IN-APP AND IN-GAME TESTING. A suite cannot open the editor and cannot
    boot a ROM. A claim that something was checked there is either the owner's, quoted, or it
    is a claim nobody can act on - and it is the one claim that ends a review.
    """
    low = message.lower()
    if OWNER_TESTED.lower() in low:
        return 0
    said = [phrase for phrase in _IN_GAME if phrase in low]
    if not said:
        return 0
    sys.stderr.write(
        "REFUSING THE COMMIT: this claims something was checked in the editor or in" + LF
        + "  the game (%r)." % said[0] + LF
        + "  THE OWNER DOES THE IN-APP AND IN-GAME TESTING. Nothing here can load the" + LF
        + "  game: no suite opens the editor, and no suite boots a ROM. A claim like" + LF
        + "  this cannot be checked by anybody reading the log, and it is exactly the" + LF
        + "  claim that ends a review." + LF
        + "  If the owner did it, quote them:" + LF
        + "    %s <what they said>" % OWNER_TESTED + LF
        + "  Otherwise say what WAS measured instead." + LF)
    return 1


def check_zero_claim(message):
    """A measurement that found nothing needs the control that proves it could find something.

    A CONFIDENT EMPTY RESULT IS A BUG UNTIL PROVEN OTHERWISE. Twice here a scan returned 0
    across 536 files because it read the wrong struct field, and both times the zero read as
    good news. A scan that has never been shown to find a planted case is not evidence of
    absence; it is evidence of nothing.
    """
    if CONTROL.lower() in message.lower():
        return 0
    found = _ZERO_CLAIM.search(message)
    if not found:
        return 0
    sys.stderr.write(
        "REFUSING THE COMMIT: this reports a measurement that found nothing -" + LF
        + "    %r" % found.group(0).strip()[:100] + LF
        + "  A CONFIDENT EMPTY RESULT IS A BUG UNTIL PROVEN OTHERWISE. Twice here a scan" + LF
        + "  returned 0 across 536 files because it read the wrong struct field, and both" + LF
        + "  times the zero read as good news." + LF
        + "  Say what the same scan finds when there IS something to find:" + LF
        + "    %s <the known-answer case it caught>" % CONTROL + LF)
    return 1


def check_cheapest_evasion(message):
    """A commit that adds a guard must say how that guard could be satisfied cheaply.

    ASK WHAT THE CHEAPEST WAY TO SATISFY YOUR OWN GUARD IS, AND REFUSE THAT TOO. Every guard
    creates an incentive to satisfy it cheaply, and the cheap way is nearly always to do LESS
    work rather than more: a rule that forbids REPORTING a hole is satisfied fastest by not
    looking for one. A guard nobody has asked that question of has a way round it that nobody
    has written down.
    """
    if EVASION.lower() in message.lower():
        return 0
    added = [f for f in added_files()
             if any(f.startswith(home) for home in _GUARD_HOMES)
             and f.endswith((".py", ".java"))]
    if not added:
        return 0
    sys.stderr.write(
        "REFUSING THE COMMIT: this adds a guard and does not say how to get round it." + LF
        + "".join("    %s%s" % (p, LF) for p in added)
        + "  ASK WHAT THE CHEAPEST WAY TO SATISFY YOUR OWN GUARD IS, AND REFUSE THAT" + LF
        + "  TOO. Every guard creates an incentive to satisfy it cheaply, and the cheap" + LF
        + "  way is nearly always to do LESS work rather than more - a rule that forbids" + LF
        + "  REPORTING a hole is satisfied fastest by not looking for one." + LF
        + "  Write the cheapest way you can think of to make this guard go quiet without" + LF
        + "  doing the work, and say what stops it:" + LF
        + "    %s <the cheap way, and what refuses it>" % EVASION + LF)
    return 1


def _added_lines():
    """The lines this commit ADDS, per staged file. Empty when git cannot be asked."""
    import subprocess
    try:
        done = subprocess.run(["git", "diff", "--cached", "-U0"],
                              cwd=ROOT, capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):       # pragma: no cover
        return {}
    out = {}
    path = None
    for line in done.stdout.splitlines():
        if line.startswith("+++ b/"):
            path = line[len("+++ b/"):].strip().replace(chr(92), "/")
            out.setdefault(path, [])
        elif line.startswith("+") and not line.startswith("+++") and path:
            out[path].append(line[1:])
    return out


def check_menu_item(message):
    """A new item on the MAIN MENU BAR has to say why it is not a tab.

    FEATURES LIVE IN THEIR OWN UI AREA - never menu-dumped, never a new window; seldom-used
    goes to the Extras tab. Only the window that OWNS a JMenuBar is asked: a JMenuItem in a
    right-click menu on the map canvas is already in its own area, and refusing those would
    fire on honest work, which is how a rule gets a ceiling raised and then ignored.
    """
    if MENU_ITEM.lower() in message.lower():
        return 0
    guilty = []
    for path, lines in _added_lines().items():
        if not path.endswith(".java") or not _is_production(path):
            continue
        try:
            with io.open(os.path.join(ROOT, path), encoding="utf-8",
                         errors="replace") as handle:
                whole = handle.read()
        except OSError:
            continue
        if "JMenuBar" not in whole:
            continue
        if any("JMenuItem" in line for line in lines):
            guilty.append(path)
    if not guilty:
        return 0
    sys.stderr.write(
        "REFUSING THE COMMIT: this adds a menu item to a window that owns the main" + LF
        + "  menu bar." + LF
        + "".join("    %s%s" % (p, LF) for p in guilty)
        + "  FEATURES LIVE IN THEIR OWN UI AREA: never menu-dumped, never a new window;" + LF
        + "  seldom-used goes to the Extras tab; undo/redo everywhere. A menu bar is where" + LF
        + "  a feature goes when nobody decided where it goes." + LF
        + "  If this one really belongs there, say why it is not a tab:" + LF
        + "    %s <why the menu bar and not its own area>" % MENU_ITEM + LF)
    return 1


def check_undo(message):
    """A new editing surface has to say how what it does is undone.

    UNDO/REDO EVERYWHERE. Measured: one of the 79 files under humaninterface has an undo path
    (PaintForm, through TileUndo). Banning the other 78 would fire on years of honest work and
    the ceiling would be raised once and forgotten, so this binds only what is ADDED - where
    the answer is cheapest, because the person writing the form is the one who knows what its
    inverse is.
    """
    low = message.lower()
    if UNDO.lower() in low or NO_UNDO.lower() in low:
        return 0
    added = [f for f in added_files()
             if f.startswith(_UI_HOME) and f.endswith(".java")
             and _is_production(f)
             and any(os.path.basename(f)[:-5].endswith(k) for k in _UI_SURFACE)]
    if not added:
        return 0
    sys.stderr.write(
        "REFUSING THE COMMIT: this adds a user-facing surface and says nothing about" + LF
        + "  how its effects are undone." + LF
        + "".join("    %s%s" % (p, LF) for p in added)
        + "  UNDO/REDO EVERYWHERE is the owner's standing rule. One of the 79 files under" + LF
        + "  humaninterface has an undo path today, which is why this binds new work rather" + LF
        + "  than the existing 78 - and why the answer is cheapest now, while the person" + LF
        + "  writing the form still knows what its inverse is." + LF
        + "    %s <how the user takes it back>" % UNDO + LF
        + "  or, for a surface that changes nothing:" + LF
        + "    %s <why there is nothing to undo>" % NO_UNDO + LF)
    return 1


def main(argv):
    if len(argv) > 1 and argv[1] == "--subject":
        #: So a recorder - and the suite that drives this file - asks THIS implementation
        #: what tree it is looking at, instead of growing a second digest that agrees with
        #: it until the day it does not.
        digest = source_digest()
        if digest is None:
            sys.stderr.write("cannot digest this tree - is it a git repository?" + LF)
            return 2
        sys.stdout.write(digest + LF)
        return 0
    if len(argv) < 2:
        return 0
    message = _message(argv[1])
    if not message.strip():
        return 0
    for check in (check_fix_has_a_guard, check_guard_class, check_test_claim, check_known_hole,
                  check_blanket_claim, check_extraction_is_tested, check_detangle,
                  check_in_game_claim, check_zero_claim, check_cheapest_evasion,
                  check_menu_item, check_undo):
        code = check(message)
        if code:
            return code
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
