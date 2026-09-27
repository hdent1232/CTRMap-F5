#!/usr/bin/env python3
"""A TURN MAY NOT END ON A PROMISE. Refused at the end of the turn, not regretted afterwards.

THE MEASURED FAILURE. Three turns in a row ended with a sentence describing work that was then
not done:

    "Starting there and working the rest the moment the lock clears."
    "I'll keep going on both unless you say otherwise."
    "the moment it lands: commit -> verify plants -> re-run the derive"

None of them was blocked. Every one read like progress. The owner had to notice, in my own
words, that the words were all there was - which is the thing this repository names as the
reason it stops trusting a report: *never end a message with "I found a clear mistake with a
clear fix" and no fix*, and *a run that knows what it missed goes back*.

CLAUDE.md lists that second rule among the ones NOTHING ENFORCES, with the reason written out:
"every other rule here binds a tool call, a write or a commit; this one binds the sentence I
write at the end, and no chokepoint exists for a report." That was true. `Stop` is the
chokepoint - it fires when the turn is about to end, and it can refuse.

WHAT IT REFUSES. A final message carrying a FORWARD-LOOKING marker about work, unless the same
message also says plainly what is NOT DONE. Two ways out and both are improvements:

  * do the thing now, and describe it in the past tense, or
  * say it is not done, in those words, where the owner can see it.

THE CHEAPEST WAY TO SATISFY IT IS TO STOP PROMISING, which is the point. This project's own
rule says to ask what the cheapest evasion of a new guard is and refuse that too; here the
cheapest evasion - ending the turn without announcing a next step at all - IS the desired
behaviour, so there is nothing left to refuse.

WHY A CLOSED VOCABULARY IS LEGITIMATE HERE, when this repository says a grep is not a guard. The
subject of every other guard here is CODE, where a rule about text loses to formatting - two
deleted spaces opened live routing while the assertion forbidding it reported OK. The subject of
this one IS text, written by the thing being guarded, and English future aspect is a closed
class of about fifteen markers. The failure mode a textual guard has on code - the same meaning
spelled another way - is not available: a sentence that carries no forward marker at all is not
a promise, it is silence, and silence is allowed.

IT DOES NOT LOOP. `stop_hook_active` says the stop was already refused once this turn. A second
refusal over the SAME text would spin forever, so an identical message passes and is recorded
instead; a CHANGED message that still promises is refused again, because that is progress being
made in the wrong direction rather than a stuck loop.
"""
import hashlib
import io
import json
import os
import re
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there
import tempfile

LF = chr(10)


def _repo_root():
    """The repository root, ANCHORED against a file that must be in it.

    A hook that resolves its own root by counting `dirname` calls is one rename from reading an
    empty tree and reporting it clean, which `liveness.py` shipped here and this file will not.
    """
    here = os.path.dirname(os.path.abspath(__file__))
    while True:
        if os.path.isfile(os.path.join(here, "CLAUDE.md")):
            return here
        parent = os.path.dirname(here)
        if parent == here:
            return None
        here = parent


#: FORWARD-LOOKING ASPECT. A closed class in English, not a roster of phrases somebody thought
#: of: modal future (`will`, `'ll`), periphrastic future (`going to`, `about to`), sequence
#: (`next`, `then`, `after that`, `once ... clears/lands/finishes`) and inceptive aspect, which
#: is the form that caught none of the three real offences until it was added - `Starting there`
#: has no subject and no modal at all.
FORWARD = (
    r"\bI(?:'ll| will| am going to| am about to| intend to| plan to| shall)\b",
    r"\bwe(?:'ll| will| are going to)\b",
    r"\bgoing to\b",
    r"\babout to\b",
    r"\bnext(?:,| up| step| I| we)\b",
    r"\bafter (?:that|this)\b",
    r"\bonce (?:it|that|the|this)\b[^.]*\b(?:clears?|lands?|finishes|completes?|is done)\b",
    r"\bthe moment\b",
    r"\bwhen (?:it|that|the)\b[^.]*\b(?:clears?|lands?|finishes|completes?)\b",
    # INCEPTIVE, at the start of a sentence, where the subject is elided. This is the form the
    # real offence took: "Starting there and working the rest ...".
    r"(?:^|[.!?]\s+|\n)(?:Starting|Continuing|Resuming|Beginning|Moving on|Proceeding|"
    r"Picking up|Carrying on|Working|Running|Doing|Kicking off)\b",
)

#: SENTENCES A FORWARD MARKER DOES NOT MAKE A PROMISE ABOUT WORK. A SHORT, CLOSED EXEMPTION -
#: never a list of the verbs that count as work.
#:
#: THE FIRST VERSION WAS THAT LIST AND IT FAILED ON ITS OWN FIXTURE. `WORKISH` enumerated forty
#: work verbs and required one to be present, and "I'll keep going on both unless you say
#: otherwise" - one of the three sentences this guard exists for - walked straight through it,
#: because `going` was not among the forty. That is the hand-written-roster class: the cheapest
#: evasion is to describe the work with a verb nobody listed, and the evasion happens by
#: accident, in ordinary English, without trying.
#:
#: Inverted, it fails SAFE. Any forward-looking sentence is a promise unless it is one of these,
#: so being wrong costs a rewording rather than a missed lie.
NOT_ABOUT_WORK = (
    r"\bbe brief\b", r"\bbe quick\b", r"\bkeep (?:it|this) short\b",
    r"\bbe blunt\b", r"\bbe direct\b", r"\bbe precise\b", r"\bbe clear\b",
    r"\bsay (?:it|this) plainly\b", r"\bput it another way\b",
)

#: AN ADMISSION THAT WORK IS OUTSTANDING. THIS IS NOT AN EXIT - IT IS THE SECOND OFFENCE.
#:
#: The first version of this hook treated these as the way out: a message carrying a promise was
#: allowed through as long as it also said plainly what was not done. The owner read that in one
#: line and said what it was - *you should not be leaving anything knowingly undone in the first
#: place*. The cheapest way to satisfy that guard was to write NOT DONE and stop, which is a
#: confession mechanism wearing a refusal's clothes, and CLAUDE.md names that failure outright:
#: ASK WHAT THE CHEAPEST WAY TO SATISFY YOUR OWN GUARD IS, AND REFUSE THAT TOO.
#:
#: So knowing a thing is undone is now a reason the turn may NOT end. The exits are: do it, or
#: be genuinely blocked and say by what.
#: EVERY ENTRY IS CLAIM-SHAPED. Four false positives in four turns and all one cause: this list
#: mixed state phrases with BARE TOKENS, and a bare token matches ordinary prose.
#:
#:     "outstanding"   fired on the guard's own refusal text, quoted while explaining it
#:     "unfinished"    an adjective
#:     "didn't"        fired on "the only reason that didn't corrupt exercised_by.json" -
#:                     a sentence about something that did NOT happen, which is the opposite
#:                     of a claim that work is owed
#:
#: A negation needs a SUBJECT to be an admission. `I have not written X` is a claim about me;
#: `that didn't corrupt X` is a fact about an outcome. Every bare negation below now carries the
#: first person, and every other entry names a STATE - `not done`, `still owed`, `left undone`.
ADMISSION = (
    r"\bnot done\b", r"\bnot started\b",
    r"\bI have not\b", r"\bI haven't\b", r"\bwe have not\b",
    r"\bI did not\b", r"\bI didn't\b",
    r"\bstill owed\b", r"\bstill outstanding\b",
    r"\bnot yet (?:done|started|written|run|wired|measured|built|applied)\b",
    r"\bremains? (?:open|undone|outstanding)\b", r"\bleft undone\b",
    r"\bnever (?:started|applied|written|wired|run)\b", r"\bstill to (?:do|be done)\b",
    # ABSORBED FROM CTRMap's `guard_unfinished.py` (2026-09-27): a Stop hook of its own for this
    # class, its two phrasings chosen there by measurement over 3,981 of one session's messages.
    # Replayed over 1,312 turn-ending messages in every transcript on this machine, it refused
    # 55 and this guard let 17 of them through; these words, which it had and this did not,
    # are in 10 of the 17. It was deleted only once this guard refused everything it did.
    r"\bI (?:still )?owe you\b", r"\bstill (?:to come|ahead)\b",
    # `\boutstanding\b` AND `\bunfinished\b` WERE HERE AND ARE NOT CLAIMS.
    #
    # They are single adjectives with no claim structure, so they matched any use of the word -
    # including a sentence DESCRIBING this guard's own refusal, which says "work outstanding".
    # Writing up what the guard did was refused by the guard. Every other pattern above is
    # claim-shaped: `not done`, `have not`, `still owed`. An adjective on its own is not.
)

#: A MARKER INSIDE A QUOTATION IS DATA, NOT A CLAIM.
#:
#: This guard's subject is my own prose, and a great deal of my prose is ABOUT this guard: its
#: refusal text, the sentences that tripped it, the vocabulary it matches. Three times it has
#: fired on a mention rather than an assertion - a table row reading `mutation run via
#: PowerShell`, the phrase `never run` inside a report of the fix, and its own words `work
#: outstanding` quoted back while explaining what it does.
#:
#: This repository already has the rule and the reason: *a guard that refuses its own
#: documentation is a guard people learn to ignore, and the habit of ignoring one is what makes
#: every other ratchet worthless.* `guard_dirty_mutation` carries the same carve-out as
#: `QUOTES_IT`.
#:
#: Backticks and quotation marks around the marker are the shape. A sentence that means it says
#: it plainly.
#:
#: INSIDE AN OPEN QUOTATION, NOT NEAR A QUOTE CHARACTER. The first version asked whether any of
#: the three quote characters appeared in the 80 characters before the marker with none after
#: it - so the apostrophe in `That's` or `You're`, or the CLOSING backtick of a code span earlier
#: in the sentence, exempted the admission after it. Measured over 1,312 turn-ending messages,
#: fixing this alone refuses 18 that it had passed (*you're right and I haven't solved it*), two
#: of them among the 55 CTRMap's `guard_unfinished` refused. English contracts, so the cheapest
#: evasion of this carve-out happened without anybody trying.
#:
#: A quotation is OPEN when the text before the marker leaves one unclosed: an odd number of
#: backticks or straight double quotes, a curly quote opened and not closed, or a single quote
#: opened (after a space or bracket, before a character) and not closed. An apostrophe between
#: two letters opens nothing.
def quoted(before):
    if before.count(chr(96)) % 2 or before.count(chr(34)) % 2:
        return True
    if before.rfind(chr(0x201C)) > before.rfind(chr(0x201D)):
        return True
    if before.rfind(chr(0x2018)) > before.rfind(chr(0x2019)):
        return True
    opened = [m.end() - 1 for m in re.finditer(r"(?:^|[\s(\[])'(?=\S|$)", before)]
    closed = [m.start() for m in re.finditer(r"(?<=\S)'(?=$|[\s.,;:!?)\]])", before)]
    return bool(opened) and (not closed or opened[-1] > closed[-1])

#: THE ONLY HONEST REASON TO STOP WITH WORK OUTSTANDING, and most of them are CHECKABLE.
#:
#: A blocker is not a word - it is a fact about the machine, and where it is one this hook asks
#: rather than believes. Saying "blocked on the suite lock" with no suite lock held is a claim
#: the tree can contradict, and it does.
BLOCKED = (
    r"\bblocked on\b", r"\bblocked by\b", r"\bwaiting on\b", r"\bwaiting for\b",
    r"\brefused by\b", r"\bneeds? your (?:decision|answer|call|go-ahead|approval)\b",
    r"\brequires? (?:your|a) (?:decision|answer|call)\b",
    # ASKING FOR A DECISION, IN THE FORMS IT IS ACTUALLY WRITTEN. The first version had only
    # "needs your decision", and blocked a message whose whole last paragraph was "the decision
    # I need from you" followed by two options and a request to pick. A guard that refuses the
    # honest exit because the sentence was phrased the other way round teaches people to phrase
    # around it, which is worse than not having it.
    r"\bdecision I need\b", r"\bI need (?:a |your )?decision\b", r"\byour call\b",
    r"\bneed you to (?:pick|choose|decide|say)\b", r"\bwhich (?:one )?do you want\b",
    # `want me to` AND `should I` WERE HERE, AS BLOCKERS - and a claimed blocker exempts the
    # whole message. So "I will fix the recount next. Want me to?" PASSED, while the same
    # promise without the question was refused: two words appended laundered any promise, the
    # cheapest evasion this hook had. An offer is not a blocker. It is `OFFER` below.
)

#: AN OFFER TO DO THE WORK IS THE WORK NOT DONE. The rule: *when the class is understood, close
#: it - do not stop and ask whether to.* MEASURED over one project's 1,535 real turn endings: 63
#: ended on an offer - "Want me to build it?", "Want me to start?", "Want me to run that proof
#: now?" - and 33 of them carried no price and no permission the rules require. Asked of the END
#: of the message, because an offer anywhere else is a sentence about the plan; the one that
#: ends a turn is the turn handing its work back.
OFFER = re.compile(
    r"\b(?:(?:do\s+you\s+)?want\s+me\s+to|shall\s+I|should\s+I|would\s+you\s+like\s+me\s+to"
    r"|let\s+me\s+know\s+if\s+you(?:'d|\s+would)\s+like\s+me\s+to|if\s+you\s+want,?\s+I\s+can"
    r"|I\s+can\s+(?:also\s+)?(?:do|fix|add|build|run|write|close)\b[^.?!]*\bif\s+you)"
    r"[^.?!]*[.?!]?", re.I)

#: How much of the END of a message is "the end".
OFFER_TAIL = 400

#: WHAT MAKES AN OFFER AN HONEST QUESTION: an act the rules say needs a yes - publishing, pushing,
#: deleting, killing a run - or a PRICE stated with it, because "surface the cost before spending
#: it" is the one case where stopping to ask IS the work. An offer carrying neither is refused.
PERMISSION = re.compile(
    r"\b(?:publish\w*|push\w*|release[sd]?|ship\w*|deploy\w*|delet\w*|drop\w*|destroy\w*"
    r"|kill\w*|un-?brick|live\s+store|agents?|fan-?out|workflow|tokens?|cost\w*|pric\w*"
    r"|hours?|\$\d|weekly|allowance|merge\w*)\b", re.I)

#: A NEGATED FUTURE IS NOT A PROMISE. "I am not going to guess" is a refusal to do something,
#: which is the opposite of work described instead of done - and the guard blocked exactly that
#: sentence on its first live firing.
#:
#: Matched in the WINDOW BEFORE the marker, because English puts the negation there: `will not`,
#: `won't`, `am not going to`, `never`. A sentence containing both a negated and an unnegated
#: forward marker is still a promise, which is why every hit is checked rather than the first.
NEGATED = re.compile(r"\b(?:not|never|n't|no longer|rather than)\b[^.!?]{0,24}$", re.I)

#: A COUNTERFACTUAL IS NOT A PROMISE. "the sync would have made them stale the moment it landed"
#: says what WOULD have happened had something been left running - past, conditional, and about an
#: outcome that did not occur - and the guard refused the turn for `the moment` (2026-09-24),
#: while explaining why two runs had just been stopped. A promise is about work still to come; a
#: conditional perfect is about a road not taken.
#:
#: Matched in the window before the marker AND INSIDE ITS CLAUSE: a comma, colon or semicolon ends
#: the window. "That would have been faster; the moment the sync lands the derive restarts" is a
#: promise with a counterfactual beside it, and a window that crossed the `;` would launder it.
COUNTERFACTUAL = re.compile(r"\b(?:would|could|might|should)(?:n't| not)? have\b[^.!?;:,]{0,40}$",
                            re.I)

#: A PLUPERFECT ADMISSION IS HISTORY, NOT OUTSTANDING WORK.
#:
#: "The bundle ships check_install.py, and I HAD never run it" describes the state BEFORE the
#: work in the same message - and the guard refused the turn for it. That is the difference
#: between "this is not done" and "this was not done before, and now it is", and reporting the
#: second is exactly what this repository asks for: every fix is written up with the state it
#: replaced.
#:
#: Matched in the window BEFORE the admission, because English puts the auxiliary there. The
#: present-tense forms - "is not done", "I have not done" - carry no such auxiliary and are
#: still caught, which is the half that matters.
PAST_ASPECT = re.compile(
    r"\b(?:had|used to|previously|formerly|until (?:now|then|today)|at the time|"
    r"before (?:this|today|that)|was|were)\b[^.!?]{0,40}$", re.I)

#: {what the message claims is blocking it: a callable saying whether it really is}. A blocker
#: nothing can check is still allowed - "needs your decision" is real and unverifiable - but one
#: that names a lock or a run is verified, because that is the sort a message would reach for.
def _lock_held(name):
    root = _repo_root()
    return bool(root) and os.path.exists(os.path.join(root, name))


CHECKABLE = (
    (r"\bsuite lock\b|\bsuite is running\b|\bsuite run\b", lambda: _lock_held(".suite-running"),
     "no suite lock is held"),
    (r"\bmutation lock\b|\bmutation (?:run|in.?flight)\b",
     lambda: _lock_held(".mutation-in-flight"), "no mutation lock is held"),
)

#: WHERE THIS PROJECT KEEPS ITS QUEUE of work a turn could not do, as paths under the root.
#: Empty means it keeps none and nothing is asked. ABSORBED FROM CTRMap's `guard_unfinished.py`,
#: which let a turn end on owed work only once the message named `OUTSTANDING.md` - the file
#: that project's `work_order.py` refuses its long measurements over while anything in it is
#: open, which is what turns a sentence into a blocker. Here a BLOCKED admission must name the
#: queue, and the queue must be readable and hold open work (`- [ ]`): an unreadable queue is
#: UNKNOWN, which is not queued, and a queue with nothing open queues nothing.
#:
#: ITS LIMIT, SAID: it asks that the queue is named and holds open work, not that THIS work is
#: the open item. `guard_unfinished` asked only for the name.
QUEUE = ("OUTSTANDING.md",)

#: Adapted per project: see ADAPT.md. The lock files a blocker can be checked against, and
#: where the project keeps its queue.
ADAPT = ("CHECKABLE", "QUEUE")


def sentences(text):
    """The text as sentences, keeping enough boundary for the inceptive pattern to anchor."""
    out, current = [], []
    for chunk in re.split(r"(?<=[.!?])\s+|\n{2,}", text or ""):
        chunk = chunk.strip()
        if chunk:
            out.append(chunk)
    del current
    return out


def blocker(text):
    """(claimed, complaint) - what the message says is blocking it, and whether that is false.

    A blocker NOTHING CAN CHECK is allowed: "needs your decision" is a real reason to stop and
    no file on disk can confirm it. A blocker that names a LOCK is asked, because that is the
    kind a message reaches for when it wants an exit, and a claim the tree can contradict must
    not be taken on the message's word.

    Returns (False, None) when no blocker is claimed at all.
    """
    claims = [s for s in sentences(text) if any(re.search(p, s, re.I) for p in BLOCKED)]
    if not claims:
        return False, None
    # SCOPED TO THE SENTENCE THAT CLAIMS A BLOCKER, and the first version was not.
    #
    # It searched the WHOLE message for "mutation run", so a message describing a test case -
    # "mutation run via PowerShell: refused" - was read as claiming a mutation run was blocking
    # it, found no lock, and refused the turn as a false blocker. That is mention-versus-
    # invocation, the defect this repository has recorded six times, inside the guard written to
    # stop me evading the rules.
    #
    # A blocker is claimed in the sentence that claims it. Elsewhere the same words are prose.
    for sentence in claims:
        for pattern, alive, complaint in CHECKABLE:
            if re.search(pattern, sentence, re.I) and not alive():
                return True, complaint
    return True, None


def unqueued(text):
    """Why a BLOCKED admission is not in the project's queue, or "" when it is - or when the
    project keeps no queue at all (`QUEUE` empty), where nothing is asked.

    Cannot look is not queued: a queue that cannot be read, or a root that cannot be found,
    refuses as UNKNOWN."""
    if not QUEUE:
        return ""
    named = [rel for rel in QUEUE if os.path.basename(rel).lower() in (text or "").lower()]
    if not named:
        return ("this project keeps its queue in %s and the message names none of it"
                % " / ".join(QUEUE))
    root = _repo_root()
    if not root:
        return "the repository root cannot be found, so whether the work is queued is UNKNOWN"
    for rel in named:
        try:
            with io.open(os.path.join(root, rel), encoding="utf-8", errors="replace") as handle:
                body = handle.read()
        except OSError:
            return "%s cannot be read, so whether the work is queued is UNKNOWN" % rel
        if any(line.strip().startswith("- [ ]") for line in body.splitlines()):
            return ""
    return "%s holds no open item (`- [ ]`), so naming it queues nothing" % named[0]


def outstanding(text):
    """Every reason this turn may not end, as (kind, sentence, marker).

    TWO KINDS, and the second one is the whole correction. A PROMISE is work described instead
    of done. An ADMISSION is work KNOWN to be undone - and the first version of this hook
    treated an admission as the way out, so the cheapest way to satisfy it was to write NOT DONE
    and stop. *You should not be leaving anything knowingly undone in the first place.*

    Both are refused unless the message names a blocker, and a blocker that names a lock is
    verified against the tree rather than believed. An ADMISSION is excused only by a blocker in
    its own paragraph, and in a project that keeps a queue (`QUEUE`) only once the queue is
    named and holds open work.
    """
    if not text:
        return []
    found = []
    pairs = [(p, s) for p in re.split(r"\n{2,}", text) for s in sentences(p)]
    for paragraph, sentence in pairs:
        exempt = any(re.search(p, sentence, re.I) for p in NOT_ABOUT_WORK)
        if not exempt:
            # EVERY hit, not the first. A sentence may carry a negated marker and an unnegated
            # one - "I am not going to guess, but I will measure it" is still a promise - and
            # stopping at the first would let the negation launder the rest of the sentence.
            for pattern in FORWARD:
                for hit in re.finditer(pattern, sentence, re.I | re.M):
                    before = sentence[:hit.start()]
                    if NEGATED.search(before) or COUNTERFACTUAL.search(before):
                        continue
                    found.append(("promise", sentence.strip(), hit.group(0).strip(),
                                  paragraph))
                    break
                else:
                    continue
                break
        for pattern in ADMISSION:
            hit = re.search(pattern, sentence, re.I)
            if hit:
                before = sentence[:hit.start()]
                if PAST_ASPECT.search(before):
                    continue              # history, not a claim about now - see PAST_ASPECT
                if quoted(before):
                    continue              # quoted, so it is data - see quoted()
                found.append(("admission", sentence.strip(), hit.group(0).strip(),
                              paragraph))
                break
    tail = text.strip()[-OFFER_TAIL:]
    offered = OFFER.search(tail)
    if offered and not PERMISSION.search(tail):
        found.append(("offer", offered.group(0).strip(), offered.group(0).strip()[:60],
                      text))
    if not found:
        return []

    claimed, complaint = blocker(text)
    if claimed and complaint:
        return [("false blocker", complaint, complaint)] + [f[:3] for f in found]
    out = []
    for kind, sentence, marker, where in found:
        if kind != "admission":
            if not claimed:
                out.append((kind, sentence, marker))
            continue
        # AN ADMISSION NEEDS ITS BLOCKER BESIDE IT. A blocker claimed anywhere used to exempt
        # every admission in the message, so *the push needs your approval* in one paragraph
        # carried *the re-sweep is not done* in another. Measured over 1,312 turn-ending
        # messages: 7 of the 55 CTRMap's `guard_unfinished` refused went through that way, and
        # an admission is a claim about ONE piece of work, blocked or not by what is said
        # about it. Promises and offers keep the message-wide reading.
        if not blocker(where)[0]:
            out.append((kind, sentence, marker))
            continue
        missing = unqueued(text)
        if missing:
            out.append(("unqueued", sentence, missing))
    return out


#: Kept so anything reading the old name still gets an answer about the same subject.
def promises(text):
    return [(s, m) for kind, s, m in outstanding(text) if kind == "promise"]


def last_assistant_text(transcript_path):
    """The text of the final assistant message in the transcript, or None if it cannot be read.

    None means COULD NOT LOOK, and the caller treats that differently from "nothing to refuse".
    A query that cannot read its subject must not report it absent.
    """
    try:
        with io.open(transcript_path, encoding="utf-8", errors="replace") as handle:
            lines = handle.read().splitlines()
    except (OSError, TypeError):
        return None
    for line in reversed(lines):
        line = line.strip()
        if not line:
            continue
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        if entry.get("type") != "assistant":
            continue
        message = entry.get("message") or {}
        parts = []
        for block in message.get("content") or []:
            if isinstance(block, dict) and block.get("type") == "text":
                parts.append(block.get("text") or "")
        if parts:
            return LF.join(parts)
    return ""


def _seen_path(session_id):
    folder = os.path.join(tempfile.gettempdir(), "dtengine-promise")
    try:
        os.makedirs(folder, exist_ok=True)
    except OSError:
        return None
    safe_id = re.sub(r"[^A-Za-z0-9_.-]", "_", str(session_id or "none"))[:64]
    return os.path.join(folder, safe_id + ".txt")


def already_refused(session_id, digest):
    """Has this EXACT message already been refused once this session?

    Identical text twice means the refusal is not landing and a third would spin forever. A
    CHANGED message that still promises is refused again - that is a fresh promise, not a loop.
    """
    path = _seen_path(session_id)
    if path is None:
        return False
    try:
        with io.open(path, encoding="utf-8") as handle:
            return handle.read().strip() == digest
    except OSError:
        return False


def remember(session_id, digest):
    path = _seen_path(session_id)
    if path is None:
        return
    try:
        with io.open(path, "w", encoding="utf-8") as handle:
            handle.write(digest)
    except OSError:
        pass


def refusal(found):
    out = ["BLOCKED: this turn ends with work outstanding.", ""]
    for kind, sentence, marker in found[:4]:
        if kind == "false blocker":
            out.append("    the message claims it is blocked, and %s" % sentence)
            continue
        if kind == "unqueued":
            out.append("    %s" % sentence[:220])
            out.append("      ^ blocked, and not queued: %s" % marker)
            continue
        out.append("    %s" % sentence[:220])
        out.append("      ^ %s: %r" % (kind, marker))
    out += [
        "",
        "  A PROMISE is work described instead of done. Three turns in a row ended that way",
        "  and none of the work happened.",
        "",
        "  AN ADMISSION IS NOT THE WAY OUT. The first version of this hook let a message",
        "  through if it also said plainly what was not done, and the cheapest way to satisfy",
        "  that was to write NOT DONE and stop. You should not be leaving anything knowingly",
        "  undone in the first place.",
        "",
        "  AN OFFER IS THE WORK NOT DONE. When the class is understood, close it - do not stop",
        "  and ask whether to. Ask only for what is not yours: a publish, a push, a delete, or",
        "  a price you state in the question.",
        "",
        "  THE EXITS:",
        "",
        "    1. DO IT NOW, in this turn, and describe it in the past tense.",
        "    2. Be genuinely BLOCKED and say by what, in the paragraph that says what is",
        "       undone. A blocker naming a lock or a run is checked against the tree -",
        "       claiming one that is not held does not work. Where this project keeps a",
        "       queue (QUEUE), name it, with the work open in it.",
        "    3. Need a decision that is not yours to make, and ask for it.",
        "",
        "  Ending a turn with nothing outstanding and no announcement is fine. Silence is not",
        "  a promise.",
    ]
    return LF.join(out)


def main():
    try:
        payload = json.load(sys.stdin)
    except (ValueError, OSError):
        # A hook that cannot read its own input must not block the session. This is the one
        # place where failing open is right: the cost of a false block here is a wedged turn.
        return 0

    # ONLY AT THE END OF A TURN, AND THE SHAPE SAYS WHICH. `dispatch.py` hands every guard every
    # payload - that is the point of it - and a PreToolUse payload carries a `tool_name` and a
    # `transcript_path` both. Without this gate the guard judged the last assistant message
    # BEFORE EVERY TOOL CALL, so a sentence narrating work being done right now ("Running the
    # suite's own class:") was refused as a promise about work not done. It blocked the very
    # command it was describing.
    #
    # Asked as a shape rather than as `hook_event_name == "Stop"`, for the same reason the
    # command guards ask for a command rather than for the name `Bash`: a payload that names a
    # TOOL is a tool call, whatever the event is called.
    if payload.get("tool_name") or (payload.get("tool_input") is not None):
        return 0

    text = last_assistant_text(payload.get("transcript_path"))
    if text is None:
        return 0
    found = outstanding(text)
    if not found:
        return 0

    digest = hashlib.sha256(text.encode("utf-8", "replace")).hexdigest()[:16]
    session = payload.get("session_id")
    if payload.get("stop_hook_active") and already_refused(session, digest):
        return 0
    remember(session, digest)
    sys.stderr.write(refusal(found) + LF)
    return 2


if __name__ == "__main__":
    sys.exit(main())
