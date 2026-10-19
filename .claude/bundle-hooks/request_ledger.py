#!/usr/bin/env python3
"""EVERY REQUEST THE OWNER MADE IS ACCOUNTED FOR, ITEM BY ITEM, BEFORE A TURN MAY END.

WHY `guard_promise.py` WAS NOT ENOUGH, in the owner's own terms. It judges the WORDING of the
last message - a promise, an admission, an offer - and a message that simply says nothing about
the part of the request it skipped carries no marker at all. Asked how "don't leave work undone"
was enforced, the honest answer was: by the sentence, not by the scope. A turn that did four of
five things and reported the four in the past tense passed it cleanly. The owner said: do both -
a ledger of what was asked, and an independent reviewer of whether it was done.

THE ONE THING THE AGENT DOES NOT CHOOSE IS WHAT IT WAS ASKED. Requests are read from the
session's transcript - the harness writes it - never recorded by the agent: every typed prompt,
and every message the owner queued mid-turn. Harness events, peer sessions and injected reminders
are not the owner and are left out by their opening tag.

THE CHECKLIST IS NOT THE AGENT'S EITHER. The first version let the agent write its own items, so
the cheapest way through was to scope the work down - one broad item, any later commit - and the
owner asked exactly that: can it cheat by never writing down much it would then have to meet? It
could. Every sentence the owner wrote (quoted, pasted and fenced text aside) is now a numbered
clause, and every clause must be covered by an item that shares its words, or declined to the
owner in writing. Doing less never shrinks the list; doing more costs nothing. And none of it
asks the owner to review anything: the owner's words were that policing whether instructions
were followed is exactly the job they should not have to do. What is refused:

  * a request since the ledger began that has no item
  * a sentence of the owner's that no item covers, or an item claiming one it shares no word with
  * an item left open
  * `done` without evidence the transcript or git can check: a `backticked` command or path that
    appears in a tool call made AFTER the request and did not fail, or a commit made after it -
    never this ledger's own verb, which carries the evidence it cites, nor a command that only
    says it (`echo`); refused by the `done` verb itself, when it is written
  * `answered` for a request that asked no question, or an answer the owner was never shown
  * `asked`, `blocked` or `declined` whose text never reached the owner - each must appear in
    something the agent SAID after the request, because a refusal nobody sees is a deletion
  * `declined` in under 80 characters: a reason that short is a label

WHERE IT STARTS, and why deleting it does not help. A session's ledger begins at the request that
was current when the ledger was first read, recorded twice - in the project's store and beside the
system's temp folder - and never moved by any verb. Deleting the store keeps the start and loses
the items, which REOPENS every request; it is never a way out. Every door a hook can see into
either copy is refused (`guard_requests.py`); a program written to conceal that it reaches them
is not, and that is said here rather than implied.

THE REVIEWER, where the project declares one (`"_review"` in `.claude/bundle-install.json`): a
model judging what no mechanism can - whether the evidence shows the thing was done, and done as
deeply as asked. It draws on the subscription's usage, not a separate bill, and whether it is
worth that is a measurement (README: the A/B). When declared: once every request is
itemised and resolved, the requests whose items changed since their last PASS are owed a review:
a subagent on the declared model, launched with EXACTLY the prompt this file writes - it names the
requests verbatim, the items, and what to fail - and synchronously, so the verdict is in the
transcript when the turn tries to end. The verdict is read from the tool result the harness
wrote, never from anything the agent records; a launch whose prompt carries the review token and
differs from the owed prompt by one word is refused before it runs; and an earlier FAIL is handed
to the next reviewer of the same request, so re-rolling until a PASS is not free.

    python <hooks>/request_ledger.py                          what would keep this turn open
    python <hooks>/request_ledger.py show                     requests, their clauses, their items
    python <hooks>/request_ledger.py item <request> <clauses e.g. 1,3> "<what it asks>"
    python <hooks>/request_ledger.py cover <item> <clauses>   what an existing item covers
    python <hooks>/request_ledger.py done <item> "<evidence, with a `command` or a commit>"
    python <hooks>/request_ledger.py answered|asked|blocked|declined <item> "<what you told the owner>"
    python <hooks>/request_ledger.py review                   the owed review's prompt, verbatim

The session is `CLAUDE_CODE_SESSION_ID`, which the harness gives every command it runs.
"""
import calendar
import hashlib
import io
import json
import os
import re
import subprocess
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there
import tempfile
import time

LF = chr(10)
HERE = os.path.dirname(os.path.abspath(__file__))
FOLDER = ".claude/" + os.path.basename(HERE)
sys.path.insert(0, HERE)
import _tree_root        # noqa: E402  - the project root, anchored: never counted (F216)
ROOT = _tree_root.require(__file__)
import bundle_env        # noqa: E402  - the one spelling of the owner's switches
import bundle_shell      # noqa: E402  - an agent launch, found by shape
import bounded         # noqa: E402  - a deadline ends the child's whole tree

#: The project's store, one file per session. The directory ignores itself.
STORE = os.path.join(".claude", "requests")

#: What this tool writes: its ledger, in STORE - a folder of its own that ignores everything in it - and
#: a mark in the temp folder; nothing a measurement of the tree reads. guard_suite_running reads this line
#: by its shape, so the review a turn owes can be asked for beside a live sweep (census M.85).
WRITES_ONLY_ITS_STORE = True
#: The second copy of where each ledger starts, outside the project.
MARKS = "bundle-request-starts"
DECLARATIONS = os.path.join(".claude", "bundle-install.json")
REVIEW_KEY = "_review"
SESSION_VAR = "CLAUDE_CODE_SESSION_ID"

#: Content that is not the owner: harness events, peer sessions, injected reminders, a slash
#: command and its own output, a subagent's hand-back. By the opening tag, which the harness
#: always writes first. The last two were read as owner requests on 2026-09-25: `/compact`, which
#: nothing can be done about and nothing can answer, and a background reviewer's verdict, which
#: then needed a review of its own - a loop. And the harness's own NOTICES, which it brackets:
#: on 2026-09-26 an idle notice for a subscribed session - "not a message from a person" - was
#: itemised as an owner request nothing could answer. By their exact opening, never any bracket:
#: an owner may open a message with "[urgent]".
EVENTS = re.compile(r"^\s*(?:<(task-notification|cross-session-message|system-reminder|"
                    r"agent-message|command-(?:name|message|args)|"
                    r"local-command-(?:stdout|stderr|caveat))\b"
                    r"|\[Cross-session (?:idle|delivery) notice\])")

#: A HARNESS MARKER, KNOWN BY ITS SHAPE: the whole text is one bracketed span. Measured 2026-09-26
#: over 3,305 transcripts, 167,351 user and queued rows: every bracketed text the harness writes
#: into the user role is either flagged `isMeta` (image annotations, enforcement preambles,
#: notices) or, unflagged, the ENTIRE text is one span - "[Request interrupted by user]" 47 times
#: and "... for tool use" 12. An interrupt is the owner WITHDRAWING a request; itemised, it could
#: never be answered, and a turn could not end over it. Listing the two strings would have closed
#: this for two spellings, as the notices above were closed for theirs. An owner's "[urgent] ..."
#: says something outside its bracket and stays the owner's; a message that is ONLY a bracket
#: asks nothing an item could hold.
MARKER = re.compile(r"^\s*\[[^\[\]" + LF + r"]*\]\s*$")


def harness_text(text):
    """Is this text the harness speaking - a frame, a notice, a marker - and never the owner?

    THE ONE PREDICATE. `_owner_text` asked `EVENTS` at four places, each on its own; a shape added
    at three of them is the fourth reading an interrupt as the owner."""
    return bool(EVENTS.match(text) or MARKER.match(text))

RESOLUTIONS = ("done", "answered", "asked", "blocked", "declined")
#: The shortest text each resolution may carry. A reason under these is a label, not a reason.
MINIMUM = {"done": 40, "answered": 20, "asked": 20, "blocked": 40, "declined": 80}
#: Resolutions whose text must have reached the owner, in something said after the request.
SAID_TO_OWNER = ("answered", "asked", "blocked", "declined")

BACKTICKED = re.compile(r"`([^`" + LF + r"]{6,})`")
COMMIT = re.compile(r"\b[0-9a-f]{7,40}\b")
TOKEN = "ledger-review:"
VERDICT = re.compile(r"VERDICT\s+ledger-review:([0-9a-f]{12})\s+(PASS|FAIL)\b")
REQUEST_LINE = re.compile(r"^\[request ([0-9a-f]{10}(?:-\d+)?) state ([0-9a-f]{8})\]", re.M)
#: A request longer than this is shown to the reviewer cut, and says so.
REQUEST_LIMIT = 8000
#: An earlier FAIL longer than this is shown cut, to the next reviewer and in the refusal, and
#: says so - a cut that does not say so was read by the first real reviewer as a broken failure.
REASONS_LIMIT = 1500
#: What the reviewer may spend checking evidence.
REVIEW_CALLS = 8

#: Adapted per project: see the bootstrap's ADAPT.md.
ADAPT = ("STORE", "SESSION_VAR", "REVIEW_CALLS")


def flat(text):
    return " ".join(str(text or "").split())


def words(text):
    """Lowercase words, punctuation and markdown gone: what a sentence SAYS, for asking whether
    the owner was shown it. `**blocked** by the lock` and `blocked by the lock` say one thing."""
    return " ".join(re.findall(r"[a-z0-9]+", str(text or "").lower()))


#: What in a request is not the owner ASKING: an HTML comment the harness adds (`<!-- attach -->`),
#: a pasted block, a fenced block - and, line by line, a quoted line. Measured on the request this
#: was built for: twenty-six quoted lines of the agent's own explanation around two words.
NOT_ASKING = (re.compile(r"<!--.*?-->", re.S),
              re.compile(r"<pasted_content\b.*?</pasted_content>", re.S),
              re.compile(r"```.*?```", re.S))
QUOTED = re.compile(r"^\s*>")

#: Words too common to show that an item is ABOUT a sentence.
COMMON = frozenset((
    "about", "after", "again", "also", "been", "being", "both", "could", "does", "doing", "done",
    "each", "even", "from", "have", "here", "into", "just", "like", "make", "more", "much",
    "need", "only", "other", "over", "same", "should", "some", "still", "such", "sure", "than",
    "that", "their", "them", "then", "there", "these", "they", "this", "those", "very", "want",
    "well", "were", "what", "when", "where", "which", "while", "will", "with", "would", "your"))


def clauses(text):
    """The owner's own sentences in a request, in order - the checklist the items must cover.

    THE AGENT DOES NOT WRITE THIS LIST. The first ledger checked only that a request had SOME item,
    so one broad item and any later commit passed, and the cheapest way through was to scope the
    work down. Asked by the owner whether an agent could cheat by not writing much it would then
    have to meet: it could. The list comes from what the owner wrote now, so doing less never
    shrinks it and doing more costs nothing.
    """
    body = str(text or "")
    for pattern in NOT_ASKING:
        body = pattern.sub(" ", body)
    out = []
    for line in body.splitlines():
        if QUOTED.match(line):
            continue
        for part in re.split(r"(?<=[.!?])\s+", line):
            if words(part):
                out.append(flat(part))
    return out


def content(text):
    """The words that say what a sentence is ABOUT."""
    return {w for w in words(text).split() if len(w) >= 4 and w not in COMMON}


#: A LIST IN ONE SENTENCE IS SEVERAL REQUESTS. Measured 2026-10-15: the owner asked for an analysis "whether thats for
#: the money section, the invest section, the trade section, or maybe it reveals something entirely new"; ONE item
#: named all four and covered the sentence, a finding was checked against Invest alone, and the report's Trade section
#: read as covered. Every clause shared words with an item, and nothing asked whether every THING the sentence listed
#: had an item of its own - so one resolution closed four. Read as listed: the parts of a comma list (its last part
#: split on "and"/"or" too: "Money, Invest and Trade"), and requests joined by "and"/"or" with three or more words on
#: each side ("make it impossible and ship it to every project and update the repo"). A part is a THING only by the
#: words that are its own - "section" in all of them tells none of them apart.
SEPARATOR = re.compile(r"\s*[,;]\s*")
JOINER = re.compile(r"\s+(?:and|or|nor)\s+", re.I)
OPENER = re.compile(r"^(?:and|or|nor|but|then|also)\s+", re.I)
#: A part that opens on one of these continues the part before it - "you come back and tell me", ", when you didnt",
#: "so if the subagents ... or hallucinations" - it is narrative or a condition, not one more thing asked for.
#: Measured on the request this was built for: without it, its four-part analogy read as four requests.
CONTINUES = frozenset(("i", "you", "we", "he", "she", "they", "it", "this", "that", "these", "those", "there",
                       "which", "who", "whom", "whose", "when", "while", "because", "since", "so", "if", "unless",
                       "though", "although", "as", "like", "where", "whereas", "than"))


def _continues(part):
    first = words(OPENER.sub("", part.strip())).split()[:1]
    return bool(first) and first[0] in CONTINUES


def members(sentence):
    """The separate things one owner sentence LISTS, as [(text, its own words)] - [] when it lists fewer than two."""
    pieces = []
    for piece in SEPARATOR.split(OPENER.sub("", flat(sentence))):
        piece = OPENER.sub("", piece.strip())
        if not piece:
            continue
        if pieces and _continues(piece):
            pieces[-1] += ", " + piece
        else:
            pieces.append(piece)
    parts, chained = [], 0
    for index, piece in enumerate(pieces):
        split = [OPENER.sub("", s.strip()) for s in JOINER.split(piece)]
        closes_a_list = len(pieces) > 1 and index == len(pieces) - 1 and len(split[0].split()) <= 4
        requests_joined = all(len(s.split()) >= 3 for s in split) and not any(_continues(s) for s in split)
        if len(split) > 1 and (closes_a_list or requests_joined):
            parts += split
            chained = max(chained, len(split) if requests_joined else 0)
        else:
            parts.append(piece)
    parts = [p for p in parts if content(p)]
    listed = []
    for index, part in enumerate(parts):
        others = set()
        for other in parts[:index] + parts[index + 1:]:
            others |= content(other)
        own = content(part) - others
        if own:
            listed.append((part, own))
    return listed if len(listed) >= 2 and _a_list(listed, chained) else []


def _a_list(listed, chained):
    """Is this split a LIST of things asked for, rather than a sentence that happens to have commas?

    Measured 2026-10-15 over 6,305 owner sentences: splitting on every comma and "and" read 1,463 as lists, and a
    sample of 25 was mostly narrative ("On 2026-09-27, committing 90795a8, the rule refused...") - a guard that makes
    an agent itemise the fragments of a story is one that gets switched off. A list is: things in a shared frame
    ("the invest section, the trade section" - one word common to two of them, each short), three or more requests
    chained by "and", or a run of three or more short names ("Money, Invest, Trade")."""
    texts = [t for t, _ in listed]
    short = [t for t in texts if len(t.split()) <= 6]
    for i, a in enumerate(short):
        for b in short[i + 1:]:
            if content(a) & content(b):
                return True
    if chained >= 3:
        return True
    return sum(1 for t in texts if len(t.split()) <= 3) >= 3


def named(ask, listed):
    """The listed things an item's words name - each thing by the words that are its own."""
    said = content(ask)
    return [(text, own) for text, own in listed if own & said]


def bundled(ask, covers, request):
    """Why an item covering `covers` of `request` names several things one sentence LISTS, or '' - one item each."""
    said = clauses(request["text"])
    for number in covers:
        if not 1 <= number <= len(said):
            continue
        hit = named(ask, members(said[number - 1]))
        if len(hit) > 1:
            return ("clause %d lists %d things separately and this item names %d of them (%s) - each thing the owner "
                    "listed is its own item, done, asked, blocked or declined on its own; one item closing several is "
                    "how one was skipped while the report read as covered"
                    % (number, len(members(said[number - 1])), len(hit), "; ".join(t[:40] for t, _ in hit)))
    return ""


def owned(item, request):
    """The one listed thing this item is the item for, per clause it covers: [(clause, text, own words)]."""
    said, out = clauses(request["text"]), []
    for number in item.get("covers") or []:
        if not isinstance(number, int) or not 1 <= number <= len(said):
            continue
        hit = named(item.get("ask"), members(said[number - 1]))
        if len(hit) == 1:
            out.append((number,) + hit[0])
    return out


def unnamed_in(said, item, request):
    """Why a resolution of a listed thing's own item never names that thing, or '' - the owner hears it by name."""
    words_said = content(said)
    for number, text, own in owned(item, request):
        if not own & words_said:
            return ("this item is the one for \"%s\" (clause %d), and what it records never names it - say %s"
                    % (text[:60], number, " or ".join(sorted(own)[:3])))
    return ""


# ---------------------------------------------------------------------------- the transcript

def read_rows(path):
    """Every row of the transcript, or None when it cannot be read. None is COULD NOT LOOK."""
    try:
        with io.open(path, encoding="utf-8", errors="replace") as handle:
            lines = handle.read().splitlines()
    except (OSError, TypeError, ValueError):
        return None
    rows = []
    for line in lines:
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if isinstance(row, dict):
            rows.append(row)
    return rows


def _parts(row):
    message = row.get("message") if isinstance(row.get("message"), dict) else {}
    content = message.get("content")
    return content if isinstance(content, list) else ([] if content is None else content)


def parts(row):
    """The dict parts of a transcript row's message - text, tool calls, tool results - or []. The
    one reader of them for every hook outside this file."""
    held = row.get("message") if isinstance(row, dict) and isinstance(row.get("message"),
                                                                       dict) else {}
    content = held.get("content")
    return [p for p in content if isinstance(p, dict)] if isinstance(content, list) else []


def result_text(content):
    """A tool result's text, whether a string or a list of parts, nested results flattened."""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return LF.join(result_text(p.get("text") if p.get("type") == "text" else p.get("content"))
                       for p in content if isinstance(p, dict))
    return ""


def _text_parts(node):
    """A prompt's words, whether it is a string or a list of parts (text beside an image)."""
    if isinstance(node, str):
        return node
    if isinstance(node, list):
        return LF.join(p.get("text") or "" for p in node
                       if isinstance(p, dict) and p.get("type") == "text")
    return ""


def _owner_text(row):
    """The owner's words in this row, or None when the row is not the owner speaking."""
    kind = row.get("type")
    if kind == "queue-operation" and row.get("operation") == "enqueue":
        text = row.get("content")
        return text if isinstance(text, str) and not harness_text(text) else None
    if kind == "attachment":
        # A MESSAGE SENT MID-TURN IS DELIVERED AS A `queued_command` ATTACHMENT, AND IT SAYS WHO
        # SENT IT. The first reader knew only the queue's own row, whose content is EMPTY when
        # the message carries an image - measured over forty transcripts: 135 queued messages
        # with no text there. The owner's "I am tired of needing to beat in and enforce that
        # rules and instructions are followed", sent with a screenshot, was the one the ledger
        # it asked for could not see. `origin` names the sender: human is the owner, a peer is
        # not; an attachment from before the field existed is judged by its opening tag.
        held = row.get("attachment") if isinstance(row.get("attachment"), dict) else {}
        if held.get("type") != "queued_command":
            return None
        origin = (held.get("origin") or {}).get("kind")
        text = _text_parts(held.get("prompt"))
        if origin not in (None, "human") or not text.strip() or harness_text(text):
            return None
        return text
    if kind != "user" or row.get("isMeta") or row.get("isCompactSummary"):
        return None
    content = _parts(row)
    if isinstance(content, str):
        return None if harness_text(content) else content
    if any(isinstance(p, dict) and p.get("type") == "tool_result" for p in content):
        return None
    kept = [p.get("text") or "" for p in content if isinstance(p, dict)
            and p.get("type") == "text" and not harness_text(p.get("text") or "")]
    return LF.join(kept) if kept else None


def owner_typed(row):
    """The words the owner TYPED in this row, or None - the strict reading a YES needs.

    `_owner_text` is the generous reading, built so no request is missed: it takes a queued row
    that names no sender, and a row from before `origin` existed. A yes cannot be read that way,
    because a yes the agent can produce is no yes. Counted 2026-10-07 over this session's
    transcript, the owner's words are exactly two shapes, and nothing the agent writes, nothing a
    hook says and nothing another session sends takes either:

        a user row whose `origin.kind` is `human`        a message sent between turns
        a `queued_command` attachment whose `origin.kind`  a message sent mid-turn
          is `human`

    Never a sidechain's row (a subagent's brief is a user row in its own transcript), never
    `isMeta` (hook feedback, system notices), never a summary, never a tool's result, never the
    queue's own row (it names no sender: a subagent's hand-back arrives that way)."""
    if not isinstance(row, dict) or row.get("isSidechain"):
        return None
    if row.get("type") == "attachment":
        held = row.get("attachment") if isinstance(row.get("attachment"), dict) else {}
        origin = held.get("origin") if isinstance(held.get("origin"), dict) else {}
        if held.get("type") != "queued_command" or origin.get("kind") != "human":
            return None
        return _text_parts(held.get("prompt")) or None
    origin = row.get("origin") if isinstance(row.get("origin"), dict) else {}
    if row.get("type") != "user" or row.get("isMeta") or row.get("isCompactSummary") \
            or origin.get("kind") != "human":
        return None
    content = _parts(row)
    if isinstance(content, str):
        return content
    if any(isinstance(p, dict) and p.get("type") == "tool_result" for p in content):
        return None
    return _text_parts(content) or None


def approves_only(text, rows):
    """Is this owner message NOTHING BUT codes a refusal in `rows` issued - the owner's typed yes
    to a call the agent already described and priced - rather than a request?

    A YES IS NOT A REQUEST. Measured 2026-10-09: the owner typed one issued code, this read it as
    a request to itemise, an item had to share a word with the code, and the first item written
    without one could never be retired - no verb retracts an item - so the turn could not end on
    the owner's own approval. A message carrying any other word, or a code no refusal issued,
    is still a request: only the issued code is the owner's answer to a question already asked."""
    tokens = [token.rstrip(".,;:!") for token in str(text or "").split()]
    if not tokens:
        return False
    import owner_codes
    found = [owner_codes.CODE.fullmatch(token) for token in tokens]
    if not all(found):
        return False
    issued = owner_codes.issued(rows)
    return all(match.group(1) in issued for match in found)


def requests(rows):
    """[{key, index, at, text}] - every request the owner made, in order.

    A message queued mid-turn is written twice: once as it is queued and once as the harness
    delivers it. The delivery of a queued text is the same request, not a second one. The same
    words sent again LATER are a new request, and get a key of their own.
    """
    out, pending, seen = [], {}, {}
    # A QUEUED MESSAGE IS THE OWNER'S ONLY IF ITS DELIVERY SAYS SO. The queue's own row names no
    # sender; the attachment that delivers it does. A background subagent's hand-back reached the
    # ledger as an `enqueue` row with no origin, its delivery saying `peer`, and was itemised as an
    # owner request - so each review whose result arrived that way owed a review of its own.
    foreign = set()
    for row in rows:
        held = row.get("attachment") if isinstance(row.get("attachment"), dict) else {}
        if row.get("type") == "attachment" and held.get("type") == "queued_command" \
                and (held.get("origin") or {}).get("kind") not in (None, "human"):
            foreign.add(flat(_text_parts(held.get("prompt"))))
    for index, row in enumerate(rows):
        text = _owner_text(row)
        if text is None or not text.strip():
            continue
        if approves_only(text, rows):
            continue
        said = flat(text)
        if row.get("type") == "queue-operation" and said in foreign:
            continue
        if row.get("type") in ("user", "attachment") and pending.get(said):
            pending[said] -= 1                   # the delivery of a message already queued
            continue
        if row.get("type") == "queue-operation":
            pending[said] = pending.get(said, 0) + 1
        base = hashlib.sha256(said.encode("utf-8")).hexdigest()[:10]
        seen[base] = seen.get(base, 0) + 1
        key = base if seen[base] == 1 else "%s-%d" % (base, seen[base])
        out.append({"key": key, "index": index, "at": row.get("timestamp"), "text": text})
    return out


def said_after(rows, index, last=None):
    """Everything the agent SAID to the owner after row `index`, as words."""
    out = []
    for row in rows[index + 1:]:
        if row.get("type") != "assistant":
            continue
        for part in _parts(row):
            if isinstance(part, dict) and part.get("type") == "text":
                out.append(words(part.get("text")))
    if last:
        out.append(words(last))
    return out


def _strings(node, out):
    if isinstance(node, str):
        out.append(flat(node))
    elif isinstance(node, dict):
        for value in node.values():
            _strings(value, out)
    elif isinstance(node, list):
        for value in node:
            _strings(value, out)
    return out


#: A command that only RECORDS to this ledger, or only SAYS something, did no work: the `done`
#: verb carries the evidence it cites in its own arguments, and `echo` carries whatever it is
#: handed. Measured 2026-10-06: the verb's own call, after the request and exiting 0, was accepted
#: as the evidence it was recording - writing the evidence was the evidence.
RECORDS_OR_SAYS = re.compile(r"^\s*(?:echo|printf|Write-Output|Write-Host|Write-Information)\b"
                             r"|(?:^|[\s/\\\"'])request_ledger\.py\b", re.I)


#: VAR=value anywhere in a call's commands, and $VAR / ${VAR} - so a ledger call spelled through a variable is still
#: read as the ledger's own. MEASURED 2026-10-12: `L=.claude/hooks/request_ledger.py; python -B $L done ...` named no
#: request_ledger.py, so its own refusal - "the measurement it names covered 160 of 162" - was read as the output of the
#: measurement its argument cited, and every later citation of that measurement was refused on it.
_ASSIGNED = re.compile(r"(?:^|[\s;&|(])([A-Za-z_][A-Za-z0-9_]*)=('[^']*'|\"[^\"]*\"|[^\s;&|()]+)")
_REFERENCE = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)")


def _expanded(text, assigned):
    return _REFERENCE.sub(lambda m: assigned.get(m.group(1) or m.group(2), m.group(0)), text)


def evidence_strings(given):
    """The strings of one call's input that can evidence work. A call carrying commands counts by
    them alone - its `description` is the agent's prose - less every command that, read through
    its wrappers and with the variables the call assigns expanded, only records to this ledger or
    only says something."""
    commands = bundle_shell.commands({"tool_input": given})
    #A CALL THAT RUNS NO COMMAND EVIDENCES ONLY THE PATHS IT NAMES (2026-10-13). A write: the file written to hand this
    #ledger its done text held the cited command, and its Write was read as a run of it - the latest, printing nothing,
    #shadowing the real run before it. Then an agent: the ledger's own review launch quotes every done item, its cited
    #commands among them, and stood as the evidence each had run - the ledger evidencing itself. Whatever a call that
    #runs nothing carries as TEXT is text; the path it read or wrote is the one thing it did, and a done may cite that.
    if not commands:
        return bundle_shell.paths({"tool_input": given})
    assigned = {}
    for command in commands:
        for name, value in _ASSIGNED.findall(command):
            assigned[name] = value.strip("'" + chr(34))
    out = []
    for command in commands:
        for one in bundle_shell.split_commands(command):
            #A COMMAND THAT ONLY ASSIGNS RUNS NOTHING (2026-10-12): `H='... cites `the measurement` ...'`, set to
            #hand the ledger its text, was read as a call that did the work, and the refusal it shared a call with
            #as that work's output
            words = bundle_shell.pairs(one) or []
            if words and all(re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", word) for word, _typed in words):
                continue
            if not bundle_shell.every_command_is(
                    _expanded(one, assigned), lambda each: bool(RECORDS_OR_SAYS.search(each))):
                out.append(flat(one))
    return out


def calls(rows):
    """{tool_use id: {index, name, input, strings, evidence, error, result}} - every tool call and
    what came back, as the harness recorded them."""
    out = {}
    for index, row in enumerate(rows):
        parts = [part for part in _parts(row) if isinstance(part, dict)]
        # The agent a launch ran as, from the harness's own record of the result - read only
        # where the row answers ONE call, so it cannot be attached to the wrong one.
        given = row.get("toolUseResult") if isinstance(row, dict) else None
        results = [part for part in parts if part.get("type") == "tool_result"]
        agent = given.get("agentId") if isinstance(given, dict) and len(results) == 1 else None
        for part in parts:
            if part.get("type") == "tool_use" and part.get("id"):
                out[part["id"]] = {"index": index, "name": part.get("name"),
                                   "input": part.get("input") or {},
                                   "strings": _strings(part.get("input"), []),
                                   "evidence": evidence_strings(part.get("input") or {}),
                                   "commands": bool(bundle_shell.commands({"tool_input": part.get("input") or {}})),
                                   "error": None, "result": None, "agent": None}
            elif part.get("type") == "tool_result" and part.get("tool_use_id") in out:
                call = out[part["tool_use_id"]]
                call["error"] = bool(part.get("is_error"))
                call["result"] = LF.join(_strings(part.get("content"), []))
                call["agent"] = agent if isinstance(agent, str) else None
    return out


#: The sentence a launch's own result carries when the harness delivers the agent's report as a
#: separate message instead - measured on Claude Code 2.1.281, whose result record says
#: `handback: "send"`. The id is the agent the report will come from.
HANDED_BACK = re.compile(r'report was delivered to you as a message from "([^"]+)"')

#: The frame a handed-back report arrives in: the harness's paragraph, then the report with every
#: line indented, then the closing tag at column zero.
_FRAME = re.compile(r'\A<agent-message from="([^"]+)">\n(.*?)The report follows:\n(.*)\n'
                    r'</agent-message>\s*\Z', re.S)


def handbacks(rows):
    """{agent id: [(row index, report)]} - each subagent's report, as the harness handed it back.

    THE HARNESS CHANGED HOW A SYNCHRONOUS AGENT RETURNS. Measured 2026-09-26 in a session on
    2.1.281: a review's own tool result read only `This agent's report was delivered to you as a
    message from "<id>" (its SubagentHandback call)`, and the report arrived as a queued message
    framed `<agent-message from="<id>">`. The ledger read the pointer as the answer, found no
    verdict in it, and counted a PASS as a non-pass - until it ESCALATED over three reviews, the
    last of which had passed.

    ONLY A FRAME THE HARNESS DELIVERED FOR THAT AGENT. A `queued_command` whose delivery names a
    PEER sender that is the agent itself (`from` and `senderTaskId`), whose frame names the same
    id. The harness indents every line of the report, so a line at column zero inside it is a
    forged frame, and the message is not read at all.
    """
    out = {}
    for index, row in enumerate(rows):
        held = row.get("attachment") if isinstance(row, dict) else None
        if not isinstance(held, dict) or held.get("type") != "queued_command":
            continue
        origin = held.get("origin") if isinstance(held.get("origin"), dict) else {}
        sender = origin.get("from")
        if origin.get("kind") != "peer" or not sender or origin.get("senderTaskId") != sender:
            continue
        frame = _FRAME.match(str(held.get("prompt") or "").replace("\r\n", LF))
        if not frame or frame.group(1) != sender:
            continue
        lines = frame.group(3).split(LF)
        if any(line and not line.startswith("  ") for line in lines):
            continue
        out.setdefault(sender, []).append((index, LF.join(line[2:] for line in lines)))
    return out


def handed_back_answer(call, handed):
    """The report a launch's result POINTS to, or None when its result is the report itself.

    THE FIRST HAND-BACK AFTER THE LAUNCH, NEVER A LATER ONE. A finished agent is resumed by
    messaging its id, and it hands back again under the same id - so a reviewer told its FAIL was
    wrong, and asked to reconsider, would hand back a PASS the agent had argued it into.
    """
    pointed = HANDED_BACK.search(call["result"] or "")
    if not pointed:
        return None
    agent = pointed.group(1)
    if call.get("agent") not in (None, agent):
        return ("the launch's result points to a hand-back from %s and the harness recorded the "
                "agent as %s - its verdict is unknown" % (agent, call["agent"]))
    after = [report for index, report in handed.get(agent, []) if index > call["index"]]
    if not after:
        return ("the reviewer's report was handed back as a message from %s, and no such "
                "hand-back is in the transcript - its verdict is unknown" % agent)
    return after[0]


def _epoch(stamp):
    try:
        return calendar.timegm(time.strptime(str(stamp)[:19], "%Y-%m-%dT%H:%M:%S"))
    except (TypeError, ValueError):
        return None


def _commit_after(sha, after, root):
    """Is `sha` a commit in this repository, made at or after `after` (epoch seconds)?"""
    try:
        done = bounded.run(["git", "-C", root, "log", "-1", "--format=%ct", sha + "^{commit}"],
                           capture_output=True, text=True, timeout=30,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except (OSError, subprocess.SubprocessError):
        return False
    stamp = done.stdout.strip()
    return done.returncode == 0 and stamp.isdigit() and (after is None or int(stamp) >= after)


#: THE WORDS BY WHICH WORK CLAIMS A WHOLE - in the item, or in the owner's clauses it covers. Measured on the project
#: this came from, 2026-10-06: an item read "an entire app wide exhaustive audit of the money section", its evidence
#: read "0 findings over 15 probes" over sixteen typed pages that left out the setup, and the reviewer passed it.
WHOLE = re.compile(r"\b(?:every|everything|everywhere|whole|entire|entirely|exhaustive|exhaustively|app[- ]wide)\b",
                   re.I)
#: how a measurement says what it covered: "39 of 39 pages"
COVERAGE = re.compile(r"\b(\d[\d,]*) of (\d[\d,]*)\b")
#: a measurement in the repository - a file under tests/ or tools/ run, or a test module run - never prose
MEASURES = re.compile(r"(?:^|[\s\"'/\\=])(?:tests|tools)[/\\]|-m\s+(?:unittest|pytest)\b", re.I)


def _words(text):
    """The words a command or a cited span reads as, quotes respected - None when it cannot be split."""
    got = bundle_shell.pairs(text)
    return None if got is None else [word for word, _typed in got]


def names_span(span, value):
    """Does `value` - one command a call ran - RUN the cited `span`, rather than carry its text inside an argument?

    WORD BY WORD, QUOTES RESPECTED (2026-10-13): a script handed the cited command as a quoted argument, to look up
    its runs, printed an old run's 164 of 165 and was read as the latest run of it. A sentence quoted into an
    argument is ONE word, and matches no span of several; the span's words must be a run of the command's. A span or
    command the reader cannot split is matched by its text, as every one was before."""
    want, have = _words(span), _words(value)
    if not want or have is None:
        return span in value
    n = len(want)
    return any(all(_same_word(w, h) for w, h in zip(want, have[i:i + n])) for i in range(len(have) - n + 1))


def _same_word(cited, ran):
    """A cited word names the word run, or the file a run path ends in: `jogl_sums.py` is `C:/scratch/jogl_sums.py`."""
    return ran == cited or ran.replace("\\", "/").endswith("/" + cited.replace("\\", "/").lstrip("/"))


def cites(span, call):
    """Did this call carry the cited span - a command it RAN, word by word, or a path it named?"""
    if call.get("commands"):
        return any(names_span(span, value) for value in call["evidence"])
    return any(span in value for value in call["evidence"])


def claims_whole(item, request):
    """Does this item - in its own words, or the owner's in the clauses it covers - claim a whole?

    A LISTED THING'S ITEM READS ITS OWN PART, AND THE LIST'S FIRST (2026-10-15). Once one sentence's list is one item
    per thing, reading the whole sentence lent every item the other things' words: "the money section, the invest
    section, the trade section, or something entirely new" made Money's item claim a whole because of "entirely new",
    and an honest `done` for Money was refused for want of a whole-coverage measurement. A frame shared by every part
    sits in the first ("check every page of Money, Invest and Trade"), so the first part is read for each - reading an
    item's own part alone is the cheapest way past, and it is refused by the test of a shared frame."""
    said = clauses(request["text"])
    mine = {number: text for number, text, _own in owned(item, request)}
    texts = [flat(item.get("ask"))]
    for number in item.get("covers") or []:
        if not isinstance(number, int) or not 1 <= number <= len(said):
            continue
        if number in mine:
            texts += [mine[number], members(said[number - 1])[0][0]]
        else:
            texts.append(said[number - 1])
    return any(WHOLE.search(text) for text in texts)


def coverage_problem(said, request, rows, made=None):
    """Why a `done` that claims a WHOLE is not backed by a measurement that says it covered all of it, or ''.

    A CLAIM OF THE WHOLE IS A MEASUREMENT OF THE WHOLE. The evidence must cite the `command` of a measurement in the
    repository (tests/ or tools/) that ran after the request without failing, and THAT command's own output must say
    what it covered as N of N - at least once, and never an N of M short of the whole. A count typed into the evidence
    proves nothing; the measurement printing it is what is read. What a measurement derives its N from is held in the
    code (a project's scope rules, such as one refusing a typed list of pages), and what it covered is held here.

    THE LATEST RUN OF EACH COMMAND IS ITS READING (2026-10-12). Every run since the request was read, so a measurement
    that found a gap, had it closed and was run again stayed refused for the gap - measure, close, measure again is the
    work - and the one way past was to spell the command so the first run no longer matched it. A run is a reading of
    the tree at its time; the latest says what the tree is now, a part measured after a whole included."""
    made = calls(rows) if made is None else made
    spans = [flat(s) for s in BACKTICKED.findall(said)]
    #a RUN of a measurement is a command that ran - a read of the measurement's file is not one (2026-10-13)
    measured = [call for call in made.values()
                if call["index"] > request["index"] and call["error"] is False and call.get("commands")
                and any(MEASURES.search(value) for value in call["evidence"])]
    latest = {}
    for span in spans:
        runs = [call for call in measured if cites(span, call)]
        if runs:
            latest[span] = max(runs, key=lambda call: call["index"])
    ran = list({id(call): call for call in latest.values()}.values())
    if not ran:
        return ("it claims the whole, and no `command` it names ran a measurement in tests/ or tools/ after request %s"
                " - the whole is shown by what a measurement covered, not by a description of it" % request["key"])
    lines = [m for call in ran for m in COVERAGE.finditer(call["result"] or "")]
    if not lines:
        return ("it claims the whole, and the measurement it names printed nothing of the form N of N - what it "
                "covered is unknown, and unknown is not all")
    short = [m.group(0) for m in lines if m.group(1).replace(",", "") != m.group(2).replace(",", "")]
    if short:
        return ("it claims the whole, and the measurement it names covered %s - part of it" % ", ".join(short[:3]))
    return ""


def evidence_holds(said, request, rows, root, made=None):
    """Does this `done` text cite something checkable that happened AFTER the request?"""
    made = calls(rows) if made is None else made
    for span in BACKTICKED.findall(said):
        span = flat(span)
        for call in made.values():
            if call["index"] > request["index"] and call["error"] is False and cites(span, call):
                return True
    after = _epoch(request.get("at"))
    return any(_commit_after(sha, after, root) for sha in COMMIT.findall(said))


# ---------------------------------------------------------------------------- the store

def _safe(session):
    return re.sub(r"[^A-Za-z0-9_.-]", "_", str(session or "none"))[:80]


def _root(root):
    """The project, read when ASKED - a default bound at import could never be pointed at a
    scratch tree, and a guard that cannot be pointed at one cannot be tested."""
    return ROOT if root is None else root


def home(root=None):
    """Where a session's ledger lives: the repository's MAIN checkout, whichever checkout asks.

    Reported 2026-10-09 by the Forven session: run inside a linked worktree, `review` read that
    worktree's store while the session's items - and its Stop hook - were in the main
    checkout's, and it printed "no review is owed" over six owed requests. A linked worktree's
    `.git` is a FILE naming its git folder, whose `commondir` names the repository's; the main
    checkout is the folder holding that. A `.git` folder, no `.git` at all, or a git folder with
    no `commondir` (a submodule) is its own home. A `.git` file that cannot be followed REFUSES:
    answering with the worktree would be the defect again, said confidently."""
    root = _root(root)
    marker = os.path.join(root, ".git")
    if not os.path.isfile(marker):
        return root
    named = re.match(r"\s*gitdir:\s*(.+?)\s*$", _read(marker) or "")
    if not named:
        raise LookupError("%s is not a git file this ledger can follow - so which checkout holds "
                          "this session's ledger cannot be said" % marker)
    gitdir = os.path.join(root, named.group(1))
    common = (_read(os.path.join(gitdir, "commondir")) or "").strip()
    if not common:
        return root
    common = os.path.normpath(os.path.join(gitdir, common))
    if os.path.basename(common) != ".git":
        return root                                 # a bare repository has no main checkout
    return os.path.dirname(common)


def store_path(session, root=None):
    return os.path.join(home(root), STORE, _safe(session) + ".json")


def mark_path(session, root=None):
    """The start's second copy - per PROJECT as well as per session, so a ledger read against one
    tree never places the start of the same session's ledger in another. Every checkout of one
    repository is one project (`home`)."""
    tree = hashlib.sha256(os.path.normcase(os.path.abspath(home(root))).encode("utf-8")).hexdigest()[:8]
    return os.path.join(tempfile.gettempdir(), MARKS, "%s-%s.txt" % (tree, _safe(session)))


def _read(path):
    try:
        with io.open(path, encoding="utf-8") as handle:
            return handle.read()
    except OSError:
        return None


def _write(path, text):
    folder = os.path.dirname(path)
    os.makedirs(folder, exist_ok=True)
    ignore = os.path.join(folder, ".gitignore")
    if folder.endswith(STORE) and not os.path.isfile(ignore):
        with io.open(ignore, "w", encoding="utf-8", newline=LF) as handle:
            handle.write("*" + LF)
    with io.open(path, "w", encoding="utf-8", newline=LF) as handle:
        handle.write(text)


def load(session, asked, root=None):
    """(ledger, trouble). The ledger always has a start: the store's, else the mark's, else the
    request current NOW - recorded in both places at once, so the start never moves again."""
    try:
        home(root)
    except LookupError as exc:
        return None, str(exc)
    text = _read(store_path(session, root))
    held = None
    if text is not None:
        try:
            held = json.loads(text)
        except ValueError:
            return None, "the ledger at %s is not JSON - it cannot be read, and an unreadable " \
                         "ledger is not an empty one" % store_path(session, root)
        if not isinstance(held, dict) or not isinstance(held.get("items"), list):
            return None, "the ledger at %s is not a ledger" % store_path(session, root)
    mark = (_read(mark_path(session, root)) or "").strip() or None
    # TWO COPIES THAT DISAGREE ARE SETTLED STRICTLY: the start that holds MORE requests wins, and
    # one this transcript cannot place holds them all. Letting the store's copy win would make
    # rewriting the store - the copy inside the project - a way to move the start forward.
    keys = [r["key"] for r in asked]
    starts = [s for s in ((held or {}).get("since"), mark) if s]
    since = min(starts, key=lambda s: keys.index(s) if s in keys else -1) if starts else None
    if since is None:
        since = asked[-1]["key"] if asked else None
    if held is None:
        held = {"since": since, "items": []}
        if since is not None:
            _write(store_path(session, root), json.dumps(held, indent=1) + LF)
    if since is not None and mark != since:
        try:
            _write(mark_path(session, root), since + LF)
        except OSError:
            pass
    held["since"] = since
    return held, None


def save(session, held, root=None):
    _write(store_path(session, root), json.dumps(held, indent=1) + LF)


def held_requests(asked, since):
    """The requests this ledger answers for. A start this transcript does not hold means the
    start cannot be placed, and then EVERY request is held - an unplaceable start is not a late
    one."""
    keys = [r["key"] for r in asked]
    if since in keys:
        return asked[keys.index(since):]
    return list(asked)


# ---------------------------------------------------------------------------- the verdict

def item_state(items):
    body = json.dumps([[i.get("id"), i.get("ask"), i.get("status"), i.get("said")]
                       for i in items], sort_keys=True)
    return hashlib.sha256(body.encode("utf-8")).hexdigest()[:8]


def uncovered(request, items):
    """Every sentence the owner wrote that no item covers, and every claim an item cannot back.

    An item names the clauses it covers, and must share a word with each: the cheapest way past
    a coverage rule is ONE item claiming every sentence, and an item that says nothing about a
    sentence is not about it.
    """
    said, out, covered = clauses(request["text"]), [], set()
    for item in items:
        for number in item.get("covers") or []:
            if not isinstance(number, int) or not 1 <= number <= len(said):
                out.append("%s covers clause %r, and request %s has clauses 1-%d"
                           % (item.get("id"), number, request["key"], len(said)))
                continue
            need = content(said[number - 1])
            if need and not need & content(item.get("ask")):
                out.append("%s claims clause %d and shares no word with it: \"%s\" - an item "
                           "covers what it names" % (item.get("id"), number, said[number - 1][:90]))
                continue
            covered.add(number)
    for number, sentence in enumerate(said, 1):
        if number not in covered:
            out.append("request %s clause %d is covered by no item: \"%s\" - cover it, or "
                       "decline it to the owner in writing" % (request["key"], number,
                                                               sentence[:110]))
    for item in items:
        why = bundled(item.get("ask"), [n for n in item.get("covers") or [] if isinstance(n, int)], request)
        if why:
            out.append("%s: %s - `cover %s -` and write one item per thing" % (item.get("id"), why, item.get("id")))
    for number in sorted(covered):
        for text, own in members(said[number - 1]):
            if not any(number in (i.get("covers") or []) and named(i.get("ask"), members(said[number - 1]))
                       == [(text, own)] for i in items):
                out.append("request %s clause %d lists \"%s\" and no item is its own - write one that names it and "
                           "none of the other things that sentence lists" % (request["key"], number, text[:80]))
    return out


def mechanical(held, asked, rows, last=None, root=None):
    """Every reason, short of the reviewer, that this turn may not end."""
    root, out = _root(root), []
    made = calls(rows)
    for request in held_requests(asked, held.get("since")):
        mine = [i for i in held["items"] if i.get("request") == request["key"]]
        if not mine:
            out.append("request %s is not itemised - \"%s\"" % (request["key"],
                                                                flat(request["text"])[:110]))
            continue
        out += uncovered(request, mine)
        spoken = said_after(rows, request["index"], last)
        for item in mine:
            status, said = item.get("status"), flat(item.get("said"))
            label = "%s (%s)" % (item.get("id"), flat(item.get("ask"))[:70])
            if status not in RESOLUTIONS:
                out.append("%s is still open" % label)
                continue
            if len(said) < MINIMUM[status]:
                out.append("%s is %s in %d characters - under %d is a label, not a reason"
                           % (label, status, len(said), MINIMUM[status]))
                continue
            if status == "done" and not evidence_holds(said, request, rows, root, made):
                out.append("%s is done with nothing checkable: name a `command` or path that a "
                           "tool call made after the request carried and did not fail, or a "
                           "commit made after it" % label)
            elif status == "done" and claims_whole(item, request):
                whole = coverage_problem(said, request, rows, made)
                if whole:
                    out.append("%s is done, and %s" % (label, whole))
            unnamed = unnamed_in(said, item, request)
            if unnamed:
                out.append("%s is %s, and %s" % (label, status, unnamed))
            if status == "answered" and "?" not in request["text"]:
                out.append("%s is answered, and request %s asked no question - work asked for "
                           "is done, asked about, blocked or declined" % (label, request["key"]))
            if status in SAID_TO_OWNER:
                if not any(words(said) in text for text in spoken):
                    out.append("%s is %s, and the owner was never told: say it, in those words, "
                               "in your message" % (label, status))
    return out


#: THE OWNER'S SWITCH FOR A SESSION WITH NO AGENTS AT ALL (CTRMap F178). The reviewer is an agent
#: launch at every turn end, and a project's rule file can forbid agents without a yes and a price:
#: CTRMap's owner gave the reviewer that yes once, and told a later session "NO AGENTS" - with no
#: way for that session to end a turn without one. Set to 1, the declared reviewer is not asked for
#: in this session, and every turn end says so; the declaration itself is untouched.
NO_REVIEW = bundle_env.name("NO_REVIEW")


def review_config(root=None):
    """(config, trouble): the reviewer asked for in THIS session - the one the project declared,
    unless the owner switched it off for the session (`NO_REVIEW`)."""
    if bundle_env.allowed("NO_REVIEW"):
        return None, None
    return declared_review(root)


def review_off(root=None):
    """True when the project declares a reviewer and the owner switched it off for this session -
    which the turn end says, so "not reviewed" never reads as "reviewed and passed"."""
    return bundle_env.allowed("NO_REVIEW") and declared_review(root)[0] is not None


def declared_review(root=None):
    """(config, trouble): the reviewer this project declared, or None when it declared none.

    A DECLARATIONS FILE THAT DOES NOT PARSE IS TROUBLE, not "no reviewer" (CTRMap F178): read as
    none, one stray comma switched the reviewer off with nothing said."""
    text = _read(os.path.join(_root(root), DECLARATIONS))
    if not text:
        return None, None
    try:
        held = json.loads(text)
    except ValueError as exc:
        return None, ("%s cannot be read (%s), so whether this project declares a reviewer is "
                      "UNKNOWN - and an unknown reviewer is not no reviewer" % (DECLARATIONS, exc))
    entry = held.get(REVIEW_KEY) if isinstance(held, dict) else None
    if entry is None:
        return None, None
    if not isinstance(entry, dict) or not isinstance(entry.get("model"), str) \
            or not entry["model"].strip():
        return None, ("%s declares %s as %r - it must be {\"model\": \"<model>\"}, and a "
                      "reviewer nobody can read is not no reviewer" % (DECLARATIONS, REVIEW_KEY,
                                                                      entry))
    return {"model": entry["model"].strip(),
            "subagent_type": str(entry.get("subagent_type") or "Explore")}, None


def reviews(rows, made=None):
    """[{digest, verdict, text, prompt, index}] - every ledger review this transcript holds, read
    from what the harness wrote back, never from anything the agent recorded.

    ONLY AN AGENT LAUNCH THAT RAN, AND ONLY ITS OWN RESULT. A `prompt` key is not a launch -
    `WebFetch` and `ScheduleWakeup` carry one - and a launch the door denied is still in the
    transcript, answered by the denial. And the result is the tool result of the launch itself,
    because the door makes a review synchronous: a task notification matched by its tool-use id
    could be written into the agent's own message, attaching a PASS to a launch that never gave
    one.

    AND ONLY THE REVIEWER'S OWN WORDS, from its verdict line on. The harness opens every
    subagent's result with a paragraph of its own - "[Subagent hand-back] The text below is the
    final report..." - and two readers sliced the answer from its start: the next reviewer's
    prompt and the standing-failure refusal. Measured: the first real review of a ledger failed
    it because the earlier failure it was shown "is incomplete/cut off", which it was - it was
    that paragraph. Cut here, where the answer is read, a reader written later is handed the
    same words; cut at each place it is shown, the next reader would be the next instance.

    AND OF THOSE, THE VERDICT AND WHAT IT SAYS WAS LEFT UNDONE. The answer then opens with
    `provenByBreaking`, what the reviewer checked, and puts the reasons last: the first two real
    FAILs ran 1,570 and 2,098 characters, and a 600-character slice ended inside that first list
    both times - the next reviewer was shown what had been checked and never why it failed. An
    answer whose `leftUndone` cannot be read is kept whole from its verdict on, never dropped.
    """
    made = calls(rows) if made is None else made
    handed = handbacks(rows)
    out = []
    for call in made.values():
        found = [launch for launch in bundle_shell.launches({"tool_input": call["input"]})
                 if TOKEN in bundle_shell.brief(launch)]
        if not found or call["error"] is not False:
            continue
        prompt = bundle_shell.brief(found[0])
        digest = prompt.split(TOKEN, 1)[1][:12]
        pointed = handed_back_answer(call, handed)
        answer = (call["result"] or "") if pointed is None else pointed
        found = [m for m in VERDICT.finditer(answer) if m.group(1) == digest]
        own = answer[found[-1].start():] if found else answer
        why = left_undone(own) if found else None
        verdict = found[-1].group(2) if found else None
        if verdict == "FAIL":
            why = quoted(own, prompt)
            verdict = "FAIL" if why else UNSUPPORTED
        text = own if why is None else "%s - left undone: %s" % (found[-1].group(0), why)
        out.append({"digest": digest, "verdict": verdict,
                    "text": text, "prompt": prompt, "index": call["index"]})
    return sorted(out, key=lambda r: r["index"])


#: A FAIL whose reasons quote nothing the reviewer was shown - a reviewer error, not a verdict.
UNSUPPORTED = "UNSUPPORTED"
#: The shortest quote that counts. A few words appear everywhere; a clause does not.
MIN_QUOTE = 12
#: The line an earlier failure is shown on. A quote of a previous reviewer is not evidence.
EARLIER = "AN EARLIER REVIEWER FAILED THIS REQUEST:"


def _left_entries(answer):
    """The `leftUndone` list a reviewer returned, or None when it cannot be read.

    Read from the JSON the review prompt asks for - between the answer's first `{` and its last
    `}`, which is where a fenced block puts it."""
    start, end = answer.find("{"), answer.rfind("}")
    if not 0 <= start < end:
        return None
    try:
        held = json.loads(answer[start:end + 1])
    except ValueError:
        return None
    entries = held.get("leftUndone") if isinstance(held, dict) else None
    return entries if isinstance(entries, list) and entries else None


def _one_line(entries):
    """Entries as one line. An entry may be a sentence or an object (the real reviewers wrote
    `{"item": ..., "issue": ..., "severity": ...}`), and every field is kept."""
    said = []
    for entry in entries:
        if isinstance(entry, dict):
            said.append(", ".join("%s: %s" % (key, flat(value)) for key, value in entry.items()))
        else:
            said.append(flat(entry))
    return " | ".join(said)


def left_undone(answer):
    """The `leftUndone` a reviewer returned, as one line, or None when it cannot be read."""
    entries = _left_entries(answer)
    return _one_line(entries) if entries else None


def quoted(answer, prompt):
    """The `leftUndone` entries whose `quote` is text the prompt's requests or items hold, as one
    line - None when there are none.

    A FAIL STANDS ONLY ON A REASON THE LEDGER CAN CHECK. The owner's words, 2026-09-26: a reviewer
    that keeps hallucinating is just as bad as shallow work, and their usage must not go on one
    that fails its own checks. Four and five FAILs in a row, in two sessions, rested on things no
    item said any more - an item "still citing" a script it had stopped citing, a rule quoted by
    half. So each reason must quote, exactly, the request or item it is about, and the quote is
    looked for in what that reviewer was SHOWN - never in an earlier reviewer's words, which are
    not evidence about the work. An entry whose quote is not there is dropped; a FAIL with none
    left is UNSUPPORTED - a reviewer error that stands against nothing.
    """
    entries = _left_entries(answer)
    if not entries:
        return None
    source = flat(LF.join(line for line in prompt.splitlines() if EARLIER not in line))
    kept = [entry for entry in entries if isinstance(entry, dict)
            and isinstance(entry.get("quote"), str) and len(flat(entry["quote"])) >= MIN_QUOTE
            and flat(entry["quote"]) in source]
    return _one_line(kept) if kept else None


def shown(text, limit=REASONS_LIMIT):
    """A review as a reader shows it: whole up to `limit`, and past it cut WITH the count."""
    text = flat(text)
    if len(text) <= limit:
        return text
    return "%s [... %d more characters not shown]" % (text[:limit], len(text) - limit)


def owed(held, asked, rows, made=None):
    """[(request, items, state)] - the requests whose items changed since their last PASS."""
    passed, out = set(), []
    for review in reviews(rows, made):
        if review["verdict"] == "PASS":
            passed.update(REQUEST_LINE.findall(review["prompt"]))
    for request in held_requests(asked, held.get("since")):
        mine = [i for i in held["items"] if i.get("request") == request["key"]]
        state = item_state(mine)
        if (request["key"], state) not in passed:
            out.append((request, mine, state))
    return out


def earlier_failures(key, rows, made=None):
    """[the latest FAIL of a review that covered `key`, as shown] - or [] when its reasons are
    about other requests.

    THE LATEST ONE, AND ONLY WHERE IT BELONGS. Every FAIL used to be shown, verbatim, under every
    request its review covered. Measured 2026-09-25 in two sessions: each reviewer read the earlier
    failures as established fact - one had quoted the evidence rule's first half and dropped
    `or a commit made after it` - and repeated them against items that had already answered
    them; each new FAIL was appended in turn, so the anchor only grew. Four and five FAILs in a
    row, on stale reasons, with no change to the items able to clear them. A failure whose reasons
    name some requests is shown under those alone; one that names none of them cannot be placed,
    and is shown under all rather than hidden.
    """
    failing = [review for review in reviews(rows, made) if review["verdict"] == "FAIL"
               and key in dict(REQUEST_LINE.findall(review["prompt"]))]
    if not failing:
        return []
    latest = failing[-1]
    named = [other for other, _state in REQUEST_LINE.findall(latest["prompt"])
             if other in latest["text"]]
    if named and key not in named:
        return []
    return [shown(latest["text"])]


#: What stands in a review prompt where the owner typed a one-time code.
HIDDEN_CODE = "[an owner code]"


def review_prompt(owing, rows, made=None):
    """The ONE prompt a review of these requests may be launched with."""
    blocks = []
    for request, items, state in owing:
        text = request["text"]
        cut = len(text) > REQUEST_LIMIT
        body = [
            "[request %s state %s]" % (request["key"], state),
            "asked at %s:" % (request.get("at") or "an unrecorded time"),
            LF.join("    " + line for line in text[:REQUEST_LIMIT].splitlines()),
        ]
        if cut:
            body.append("    [... %d more characters not shown]" % (len(text) - REQUEST_LIMIT))
        for item in items:
            body.append("  %s  %s: %s" % (item.get("id"), item.get("status"), flat(item.get("ask"))))
            body.append("      %s" % flat(item.get("said")))
        for failure in earlier_failures(request["key"], rows, made):
            body.append("  AN EARLIER REVIEWER FAILED THIS REQUEST: %s" % failure)
        blocks.append(LF.join(body))
    # AN OWNER'S CODE NEVER TRAVELS IN THE REVIEW. Measured 2026-10-09: the owner typed a fan-out
    # code to approve one send, the next owed review quoted that message word for word, and
    # `guard_owner_codes` refused the launch for carrying a code - so the review the stop demands
    # could not be launched at all, and the turn could not end. A reviewer never needs the code.
    # In any case: the code guard upper-cases a call before it looks, so a code the owner typed in
    # lower case would refuse the launch just the same.
    import owner_codes           # noqa: E402,PLC0415 - the one statement of what a code looks like
    blocks = [re.sub(owner_codes.CODE.pattern, HIDDEN_CODE, block, flags=re.I) for block in blocks]
    digest = hashlib.sha256(LF.join(blocks).encode("utf-8")).hexdigest()[:12]
    return LF.join([
        "You are an independent reviewer. " + TOKEN + digest,
        "",
        "The owner of this project made the requests below. The agent doing the work recorded "
        "an item for each thing it took a request to ask, and how each item was resolved. "
        "Decide whether the items account for EVERYTHING each request asks, and whether each "
        "resolution is supported. You did not do this work and have no stake in it passing.",
        "",
        "FAIL if any of these holds:",
        "  1. a request asks for something no item covers, or an item's wording is narrower "
        "than what was asked;",
        "  2. an item marked done cites evidence that does not show the thing was done - you "
        "may read files and run read-only git commands to check, at most %d tool calls;"
        % REVIEW_CALLS,
        "  3. an item marked answered does not answer the question that was asked;",
        "  4. an item marked asked, blocked or declined defers or refuses something the owner "
        "plainly authorised, or names no concrete blocker;",
        "  5. an earlier reviewer's failure is shown and the items do not answer it. An earlier "
        "reviewer can be wrong: check each of its findings against the CURRENT item text above "
        "and against the files, and count only the findings that still hold;",
        "  6. the owner asked for EVERY, ALL, the WHOLE, EVERYTHING or an ENTIRE or EXHAUSTIVE "
        "anything, and the evidence names a narrower scope than the owner did - some pages of a "
        "section, one part of an app, a sample of a set. Write the scope the evidence measured "
        "beside the scope the owner named, word for word, and FAIL if they differ. A reviewer "
        "passed \"0 findings over 15 probes\" over sixteen pages that left out a setup flow, as an "
        "\"entire app wide exhaustive audit\" - this item is that comparison.",
        "Otherwise PASS. Judge the requests as the owner wrote them, not as the items restate "
        "them.",
        "",
        "ALREADY CHECKED MECHANICALLY, against the conversation you cannot see: every done item "
        "cites at least one `backticked` command that a tool call made after its request ran "
        "without failing, or a commit made after its request; and every answered, asked, "
        "blocked or declined text was shown to the owner in the conversation. Do not fail an "
        "item for evidence only the conversation holds - judge whether that evidence, being "
        "true, shows the thing was done.",
        "",
        LF.join(blocks),
        "",
        "Your final message must begin with exactly one of these two lines:",
        "    VERDICT %s%s PASS" % (TOKEN, digest),
        "    VERDICT %s%s FAIL" % (TOKEN, digest),
        "and then return JSON: {\"provenByBreaking\": [what you checked and how], "
        "\"batteryGreen\": null, \"leftUndone\": [one object per failure: {\"quote\": \"<text "
        "copied EXACTLY from a request or an item above - the words the failure is about>\", "
        "\"why\": \"<what is wrong>\"}]}. A failure whose quote is not found, word for word, in "
        "a request or an item above is discarded; a quote of an earlier reviewer counts for "
        "nothing. An admitted gap is worth more than a confident wrong answer.",
    ])


#: How many reviews in a row, since the owner last spoke, may come back without a PASS before the
#: ledger stops asking. Each review spends the owner's usage.
REVIEW_ROUNDS = 3
#: What the closing message says once the ledger has stopped asking - the owner is told.
ESCALATION = "The reviewer and I disagree"


def not_passed_since_owner(asked, done):
    """How many reviews in a row, since the owner's latest request, did not PASS."""
    since = max((request["index"] for request in asked), default=-1)
    count = 0
    for review in reversed([r for r in done if r["index"] > since]):
        if review["verdict"] == "PASS":
            break
        count += 1
    return count


def escalation(asked, owing, done, rows, last=None):
    """None while the reviewer is still asked; once it has stopped, what keeps the turn open until
    the owner is told.

    A REVIEWER THAT LOOPS SPENDS THE OWNER'S USAGE ON NOTHING. The owner's words, 2026-09-26: a
    reviewer that keeps hallucinating and failing its own checks is just as bad as shallow work.
    Four and five reviews in a row, in two sessions, at about 50k tokens each, failed on reasons
    no item held. So after REVIEW_ROUNDS reviews since the owner last spoke without a PASS, the
    ledger stops asking - the owner's next message starts a fresh count - and the turn may end
    only once the closing message says ESCALATION, so the disagreement reaches the person who
    can settle it instead of looping out of sight.
    """
    if not owing:
        return None
    streak = not_passed_since_owner(asked, done)
    if streak < REVIEW_ROUNDS:
        return None
    latest = done[-1]
    spoken = said_after(rows, latest["index"], last)
    if any(words(ESCALATION) in text for text in spoken):
        return []
    return ["ESCALATED: the reviewer has not passed this ledger %d times in a row since the owner "
            "last spoke, so it is no longer asked - every review spends the owner's usage. Say "
            "`%s` in your closing message and give the owner the latest reasons in your own "
            "words, so the one who can settle it does: %s"
            % (streak, ESCALATION, shown(latest["text"]))]


def standing_failure(owing, done):
    """The latest FAIL whose requests are all exactly as it saw them, or None.

    A FAIL STANDS UNTIL SOMETHING IT REVIEWED CHANGES. Asked by the prompt's digest, it could
    never stand at all: the next prompt carries the failure itself, so its digest moves the
    moment the FAIL is read, and the refusal became a request for a fresh review - a re-roll.
    Both the stop and the launch ask this, so neither is the way round the other.
    """
    current = {r["key"]: state for r, _i, state in owing}
    for review in reversed(done):
        if review["verdict"] != "FAIL":
            continue
        pairs = REQUEST_LINE.findall(review["prompt"])
        if pairs and all(current.get(key) == state for key, state in pairs):
            return review
    return None


def review_problems(held, asked, rows, config, made=None):
    made = calls(rows) if made is None else made
    owing = owed(held, asked, rows, made)
    if not owing:
        return []
    done = reviews(rows, made)
    failed = standing_failure(owing, done)
    if failed:
        return ["the reviewer FAILED this ledger, and nothing it reviewed has changed since - "
                "change the items or the work it named; the next reviewer is shown its "
                "reasons:" + LF + "    " + shown(failed["text"])]
    prompt = review_prompt(owing, rows, made)
    digest = prompt.split(TOKEN, 1)[1][:12]
    mine = [r for r in done if r["digest"] == digest]
    keys = ", ".join(r["key"] for r, _i, _s in owing)
    return ["request(s) %s are owed a review. Launch ONE Agent, synchronously "
            "(run_in_background false), with model %r, subagent_type %r, and as its prompt "
            "EXACTLY what `python %s/request_ledger.py review` prints. The verdict is read from "
            "what comes back.%s" % (keys, config["model"], config["subagent_type"], FOLDER,
                                    " The last review of this state returned no verdict line."
                                    if mine else "")]


def problems(session, transcript, last=None, root=None):
    """Everything that keeps this turn open. [] only when every request since the ledger began
    is itemised, resolved, checkable - and, where a reviewer is declared, reviewed."""
    rows = read_rows(transcript)
    if rows is None:
        return None
    asked = requests(rows)
    held, trouble = load(session, asked, root)
    if trouble:
        return [trouble]
    out = mechanical(held, asked, rows, last, root)
    if out:
        return out
    config, trouble = review_config(root)
    if trouble:
        return [trouble]
    if config:
        made = calls(rows)
        stopped = escalation(asked, owed(held, asked, rows, made), reviews(rows, made), rows, last)
        if stopped is not None:
            return stopped
    return review_problems(held, asked, rows, config) if config else []


def owed_prompt(session, transcript, root=None):
    """The prompt the owed review must carry, or None when none is owed."""
    rows = read_rows(transcript)
    config, _trouble = review_config(root)
    if rows is None or config is None:
        return None
    asked = requests(rows)
    held, trouble = load(session, asked, root)
    if trouble or mechanical(held, asked, rows, None, root):
        return None
    made = calls(rows)
    owing = owed(held, asked, rows, made)
    done = reviews(rows, made)
    if not owing or standing_failure(owing, done) or \
            not_passed_since_owner(asked, done) >= REVIEW_ROUNDS:
        return None
    return review_prompt(owing, rows, made)


def launch_problem(payload, root=None):
    """Why an agent launch in this call may not run as a ledger review, or ''. A launch that
    carries no review token is not this function's business."""
    for launch in bundle_shell.launches(payload):
        prompt = bundle_shell.brief(launch)
        if TOKEN not in prompt:
            continue
        config, trouble = review_config(root)
        if trouble or config is None:
            return trouble or "this project declares no reviewer, so no launch is a ledger review"
        wanted = owed_prompt(payload.get("session_id"), payload.get("transcript_path"), root)
        if wanted is None:
            return ("no review is owed: every changed request already has its verdict, and a FAIL "
                    "stands until the items it reviewed change - asking again is not an answer")
        if flat(prompt) != flat(wanted):
            return ("this launch carries the review token and is not the owed review. A reviewer "
                    "handed a different prompt is not independent - launch it with exactly what "
                    "`python %s/request_ledger.py review` prints" % FOLDER)
        if str(launch.get("model") or "") != config["model"]:
            return "a ledger review runs on model %r, and this launch names %r" % (
                config["model"], launch.get("model"))
        if str(launch.get("subagent_type") or "") != config["subagent_type"]:
            return "a ledger review runs as subagent_type %r, and this launch names %r" % (
                config["subagent_type"], launch.get("subagent_type"))
        if launch.get("run_in_background") is not False:
            return ("a ledger review runs synchronously - pass run_in_background: false - so its "
                    "verdict is in the transcript when the turn tries to end")
        if launch.get("isolation"):
            return ("a ledger review reads THIS tree - a worktree or a remote copy holds HEAD, not "
                    "the work it is judging - so it names no isolation (this one names %r)"
                    % launch.get("isolation"))
    return ""


def is_owed_review(payload, root=None):
    """Is this call ONE launch, and exactly the review the ledger owes? Asked by the fan-out cap,
    which does not count a review the project declared it can afford."""
    found = bundle_shell.launches(payload)
    return len(found) == 1 and TOKEN in bundle_shell.brief(found[0]) \
        and not launch_problem(payload, root)


# ---------------------------------------------------------------------------- the verbs

def find_transcript(session):
    base = os.path.join(os.path.expanduser("~"), ".claude", "projects")
    try:
        folders = os.listdir(base)
    except OSError:
        return None
    for folder in folders:
        path = os.path.join(base, folder, _safe(session) + ".jsonl")
        if os.path.isfile(path):
            return path
    return None


def beyond_clauses(request, covers):
    """Why `covers` names a clause `request` does not have, or ''."""
    said = len(clauses(request["text"]))
    beyond = [n for n in covers if not 1 <= n <= said]
    if not beyond:
        return ""
    return ("request %s has clauses 1-%d, so it has no clause %s - `show` numbers them"
            % (request["key"], said, ", ".join(str(n) for n in beyond)))


def resolution_problem(verb, said, request, rows, root, item=None):
    """Why this resolution may not be recorded NOW, or '' - every check `mechanical` makes that
    does not wait on the message to the owner."""
    if request is None:
        return "this item belongs to no request this ledger holds"
    if len(flat(said)) < MINIMUM[verb]:
        return ("%s in %d characters is a label, not a reason - %d are the floor"
                % (verb, len(flat(said)), MINIMUM[verb]))
    if verb == "answered" and "?" not in request["text"]:
        return ("request %s asked no question - work asked for is done, asked about, blocked "
                "or declined" % request["key"])
    if verb == "done" and not evidence_holds(said, request, rows, _root(root)):
        return ("nothing checkable stands behind this yet: name a `command` or path that a tool "
                "call made after request %s carried and did not fail - not this verb, not an "
                "echo - or a commit made after it" % request["key"])
    if verb == "done" and item is not None and claims_whole(item, request):
        whole = coverage_problem(said, request, rows)
        if whole:
            return whole
    if item is not None:
        unnamed = unnamed_in(said, item, request)
        if unnamed:
            return unnamed
    return ""


def _covers(text):
    """`1,3` -> [1, 3]; `-` -> []; anything else -> None."""
    if text.strip() == "-":
        return []
    if not re.fullmatch(r"\s*\d+(\s*,\s*\d+)*\s*", text):
        return None
    return sorted({int(n) for n in text.split(",")})


def main(argv, root=None):
    session = os.environ.get(SESSION_VAR)
    transcript = find_transcript(session) if session else None
    if not transcript:
        print("no transcript for this session (%s=%r) - the ledger reads what the owner asked "
              "from it, and cannot guess" % (SESSION_VAR, session))
        return 2
    rows = read_rows(transcript)
    if rows is None:
        print("the transcript %s cannot be read" % transcript)
        return 2
    asked = requests(rows)
    held, trouble = load(session, asked, root)
    if trouble:
        print(trouble)
        return 2
    verb = argv[1] if len(argv) > 1 else "check"
    if verb == "check":
        found = problems(session, transcript, None, root) or []
        print(LF.join(found) if found else "every request is itemised, resolved and checkable")
        return 1 if found else 0
    if verb == "show":
        for request in held_requests(asked, held["since"]):
            print("%s  %s" % (request["key"], flat(request["text"])[:150]))
            for number, sentence in enumerate(clauses(request["text"]), 1):
                print("    clause %d: %s" % (number, sentence[:140]))
            for item in [i for i in held["items"] if i.get("request") == request["key"]]:
                print("    %-16s %-9s covers %-8s %s" % (
                    item["id"], item.get("status"),
                    ",".join(str(n) for n in item.get("covers") or []) or "-",
                    flat(item.get("ask"))[:80]))
        return 0
    if verb == "cover" and len(argv) == 4:
        hits = [i for i in held["items"] if i.get("id") == argv[2]]
        covers = _covers(argv[3])
        if len(hits) != 1 or covers is None:
            print("usage: cover <item> <clauses, e.g. 1,3>")
            return 2
        # THE SAME ACT BY ITS OTHER VERB: `item` refused a clause past the request's last, and
        # `cover` re-pointed an item at one with nothing asked.
        request = {r["key"]: r for r in held_requests(asked, held["since"])}.get(
            hits[0].get("request"))
        beyond = beyond_clauses(request, covers) if request else (
            "item %s belongs to no request this ledger holds" % argv[2])
        beyond = beyond or bundled(hits[0].get("ask"), covers, request)
        if beyond:
            print(beyond)
            return 2
        hits[0]["covers"] = covers
        save(session, held, root)
        return 0
    if verb == "review":
        prompt = owed_prompt(session, transcript, root)
        print(prompt if prompt else "no review is owed")
        return 0 if prompt else 1
    if verb == "item" and len(argv) == 5:
        held_now = {r["key"]: r for r in held_requests(asked, held["since"])}
        covers = _covers(argv[3])
        if argv[2] not in held_now or covers is None:
            print("usage: item <request> <clauses it covers, e.g. 1,3 or -> \"<what it asks>\" - "
                  "`show` lists the requests and their clauses")
            return 2
        # A CLAUSE THE OWNER NEVER WROTE IS REFUSED HERE, where it is written - `uncovered` refused
        # it only at the end of the turn, after the item was recorded and built on (measured
        # 2026-10-06: `item <request> 3` accepted over a request of two clauses).
        beyond = beyond_clauses(held_now[argv[2]], covers) or bundled(argv[4], covers, held_now[argv[2]])
        if beyond:
            print(beyond)
            return 2
        count = sum(1 for i in held["items"] if i.get("request") == argv[2])
        held["items"].append({"id": "%s.%d" % (argv[2], count + 1), "request": argv[2],
                              "ask": argv[4], "covers": covers, "status": "open"})
        save(session, held, root)
        print(held["items"][-1]["id"])
        return 0
    if verb in RESOLUTIONS and len(argv) == 4:
        hits = [i for i in held["items"] if i.get("id") == argv[2]]
        if len(hits) != 1:
            print("no item %s - `show` lists them" % argv[2])
            return 2
        # WHAT CAN BE KNOWN WHEN THE VERB IS WRITTEN IS ASKED WHEN IT IS WRITTEN. The end of the
        # turn refused these after the resolution was recorded and the turn built on it; only
        # whether the owner was TOLD has to wait for the message that tells them.
        request = {r["key"]: r for r in held_requests(asked, held["since"])}.get(
            hits[0].get("request"))
        why = resolution_problem(verb, argv[3], request, rows, root, hits[0])
        if why:
            print(why)
            return 2
        hits[0]["status"], hits[0]["said"] = verb, argv[3]
        save(session, held, root)
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    bundle_shell.speak_utf8()           # the one door out, as payload_text is the one in
    sys.exit(main(sys.argv))
