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
    appears in a tool call made AFTER the request and did not fail, or a commit made after it
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
import tempfile
import time

LF = chr(10)
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
FOLDER = ".claude/" + os.path.basename(HERE)
sys.path.insert(0, HERE)
import bundle_shell      # noqa: E402  - an agent launch, found by shape

#: The project's store, one file per session. The directory ignores itself.
STORE = os.path.join(".claude", "requests")
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

#: Adapted per project: see ADAPT.md.
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
        return text if isinstance(text, str) and not EVENTS.match(text) else None
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
        if origin not in (None, "human") or not text.strip() or EVENTS.match(text):
            return None
        return text
    if kind != "user" or row.get("isMeta") or row.get("isCompactSummary"):
        return None
    content = _parts(row)
    if isinstance(content, str):
        return None if EVENTS.match(content) else content
    if any(isinstance(p, dict) and p.get("type") == "tool_result" for p in content):
        return None
    kept = [p.get("text") or "" for p in content if isinstance(p, dict)
            and p.get("type") == "text" and not EVENTS.match(p.get("text") or "")]
    return LF.join(kept) if kept else None


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


def calls(rows):
    """{tool_use id: {index, name, input, strings, error, result}} - every tool call and what
    came back, as the harness recorded them."""
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
        done = subprocess.run(["git", "-C", root, "log", "-1", "--format=%ct", sha + "^{commit}"],
                              capture_output=True, text=True, timeout=30,
                              creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except (OSError, subprocess.SubprocessError):
        return False
    stamp = done.stdout.strip()
    return done.returncode == 0 and stamp.isdigit() and (after is None or int(stamp) >= after)


def evidence_holds(said, request, rows, root, made=None):
    """Does this `done` text cite something checkable that happened AFTER the request?"""
    made = calls(rows) if made is None else made
    for span in BACKTICKED.findall(said):
        span = flat(span)
        for call in made.values():
            if call["index"] > request["index"] and call["error"] is False \
                    and any(span in value for value in call["strings"]):
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


def store_path(session, root=None):
    return os.path.join(_root(root), STORE, _safe(session) + ".json")


def mark_path(session, root=None):
    """The start's second copy - per PROJECT as well as per session, so a ledger read against one
    tree never places the start of the same session's ledger in another."""
    tree = hashlib.sha256(os.path.normcase(os.path.abspath(_root(root))).encode("utf-8")).hexdigest()[:8]
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
            if status == "answered" and "?" not in request["text"]:
                out.append("%s is answered, and request %s asked no question - work asked for "
                           "is done, asked about, blocked or declined" % (label, request["key"]))
            if status in SAID_TO_OWNER:
                if not any(words(said) in text for text in spoken):
                    out.append("%s is %s, and the owner was never told: say it, in those words, "
                               "in your message" % (label, status))
    return out


def review_config(root=None):
    """(config, trouble): the reviewer this project declared, or None when it declared none."""
    text = _read(os.path.join(_root(root), DECLARATIONS))
    if not text:
        return None, None
    try:
        held = json.loads(text)
    except ValueError:
        return None, None
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
                 if TOKEN in str(launch.get("prompt") or "")]
        if not found or call["error"] is not False:
            continue
        prompt = str(found[0].get("prompt"))
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
        "and against the files, and count only the findings that still hold.",
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
        prompt = str(launch.get("prompt") or "")
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
    return len(found) == 1 and TOKEN in str(found[0].get("prompt") or "") \
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
        hits[0]["covers"] = covers
        save(session, held, root)
        return 0
    if verb == "review":
        prompt = owed_prompt(session, transcript, root)
        print(prompt if prompt else "no review is owed")
        return 0 if prompt else 1
    if verb == "item" and len(argv) == 5:
        keys = [r["key"] for r in held_requests(asked, held["since"])]
        covers = _covers(argv[3])
        if argv[2] not in keys or covers is None:
            print("usage: item <request> <clauses it covers, e.g. 1,3 or -> \"<what it asks>\" - "
                  "`show` lists the requests and their clauses")
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
        hits[0]["status"], hits[0]["said"] = verb, argv[3]
        save(session, held, root)
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
