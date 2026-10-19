#!/usr/bin/env python3
"""EVERY MESSAGE THE OWNER EVER SENT THIS PROJECT IS ACCOUNTED FOR - NOT ONLY TODAY'S.

WHY THE REQUEST LEDGER WAS NOT ENOUGH, measured on 2026-09-26. Asked for "a full list of every
single thing I have asked you to do that still has not been done ... complete and total and
absolute", the agent answered from `request_ledger.py show`: 42 requests. The ledger was right
about what it covers - and it covers a session FROM THE MOMENT THAT SESSION FIRST READ IT, which
for that session was that morning. The project held 986 owner messages over nine transcripts
back to 2026-07-06. The list was the ledger's scope presented as the owner's. Nothing was false
and most of it was missing: a convenient list standing in for the whole tree, the class
CLAUDE.md names ("a measurement's scope is derived from the tree, never from a list").

THE SCOPE IS DERIVED, NEVER CHOSEN. The transcripts the harness wrote for this project are the
folder it wrote the current session into, plus every folder a FORMER location of this project
left - found from the `cwd` the transcripts themselves record (this repository lived at
`sessions/KTBT` before it was renamed). A candidate folder must be classified in the register -
`include` or `exclude`, each with its reason - so a folder cannot drop out of scope by being
left unmentioned.

EVERY MESSAGE HAS A DISPOSITION, or the turn cannot end (`guard_asked.py`):

    items        it asked for these items - each item is open, or closed with checkable evidence
    answered     it asked a question, and the transcript shows a reply after it
    context      it asked for no new work (an approval, a "continue", a correction) - with a reason

or it is inside a session ledger's own scope with items there, which `request_ledger.py` holds.
A disposition covers the SENTENCES it shares a word with; any other sentence carries its own
(`sentence <message> <n> items|answered|context ...`) - see SENTENCE_CEILING.
One message is one message however many rows carry it: a forked session repeats its parent's
history with the same timestamps, and a queued message is delivered twice - both are merged by
their text and time, never counted twice and never dropped.

AN ITEM IS CLOSED ONLY WITH SOMETHING A READER CAN CHECK:

    done / standing   a commit reachable in this repository, or a `path` that exists
    dropped           the OWNER said so - it names the owner's later message (m...) that says it
    superseded        the owner's later message that replaced it

    python <hooks>/asked.py                       what keeps the turn open
    python <hooks>/asked.py scope                 the folders, transcripts and message count
    python <hooks>/asked.py pending               EVERY open item, grouped by release
    python <hooks>/asked.py messages [N]          the messages still owing a disposition
    python <hooks>/asked.py sentences [N]         messages with a sentence no disposition covers
    python <hooks>/asked.py batch <file.json>     many verbs at once, each checked as if typed

The register (`REGISTER`) is written by this file and nothing else; `guard_asked.py` refuses a
Write, an Edit or a command that reaches it any other way.

OFF UNTIL THE PROJECT DECLARES IT (F199, 2026-10-17). A project with years of owner messages and none disposed of
would be locked out of every turn the moment this arrived, so the register is switched on by the project's
`.claude/bundle-install.json`:

    {"_asked": {"releases": ["2.9.8", "2.9.9", "trigger", "decide"], "sentences_seeded": 0}}

`releases` are the names an item may file itself under; `sentences_seeded` is how many sentences of disposed messages
no disposition covered when the register arrived, the ceiling that may only fall. Every key of that file except
`_bundle` and `_why` is the owner's decision, so `guard_owner_files.py` refuses an agent that adds or changes
`_asked` without the owner's typed code - deleting the declaration is no quieter a way off than deleting the guard.
A declarations file that cannot be read is trouble, never "off".
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
import time

LF = chr(10)
HERE = os.path.dirname(os.path.abspath(__file__))
FOLDER = ".claude/" + os.path.basename(HERE)
sys.path.insert(0, HERE)
import _tree_root        # noqa: E402  - the project root, anchored: never counted (F216)
ROOT = _tree_root.require(__file__)
import bounded           # noqa: E402  - a deadline ends the child's whole tree
import request_ledger    # noqa: E402  - what an owner message IS: one reader, not two

#: The register: tracked, so every disposition is a line in a diff somebody can read.
REGISTER = os.path.join("docs", "asked.json")
#: Per-transcript facts, keyed by size and mtime. The directory ignores itself.
CACHE = os.path.join(".claude", "asked-cache")
#: Where the harness keeps every project's transcripts.
PROJECTS = os.path.join(os.path.expanduser("~"), ".claude", "projects")
SESSION_VAR = "CLAUDE_CODE_SESSION_ID"

#: Two rows with the same words this close together are ONE message delivered twice.
SAME_MESSAGE_SECONDS = 180
#: A session whose transcript moved this recently is alive: what its own ledger holds is its job.
LIVE_SECONDS = 24 * 3600

STATUSES = ("open", "done", "standing", "dropped", "superseded")
MINIMUM = {"done": 20, "standing": 20, "dropped": 60, "superseded": 40,
           "answered": 20, "context": 20, "item": 12}
BACKTICKED = re.compile(r"`([^`" + LF + r"]{3,})`")
COMMIT = re.compile(r"\b[0-9a-f]{7,40}\b")
MESSAGE_ID = re.compile(r"\bm[0-9a-f]{10}\b")

#: Adapted per project: see ADAPT.md.
ADAPT = ("REGISTER", "SESSION_VAR")

#: Where a project switches the register on, and the key it declares it under.
DECLARATIONS = os.path.join(".claude", "bundle-install.json")
DECLARATION_KEY = "_asked"
#: The release names an item may name when a project declares none of its own.
DEFAULT_RELEASES = ("next", "later", "trigger", "decide")


def declared(root=None):
    """(config, trouble): {"releases": (...), "sentences_seeded": n} when the project declares the register, None when
    it does not - and trouble when its declarations cannot be read, because an unknown register is not no register."""
    path = os.path.join(_root(root), DECLARATIONS)
    try:
        with io.open(path, encoding="utf-8") as handle:
            held = json.load(handle)
    except FileNotFoundError:
        return None, None
    except (OSError, ValueError) as exc:
        return None, ("%s cannot be read (%s), so whether this project keeps the owner-message register is UNKNOWN"
                      % (DECLARATIONS, exc))
    entry = held.get(DECLARATION_KEY) if isinstance(held, dict) else None
    if entry is None:
        return None, None
    names = entry.get("releases", list(DEFAULT_RELEASES)) if isinstance(entry, dict) else None
    floor = entry.get("sentences_seeded", 0) if isinstance(entry, dict) else None
    if not isinstance(names, list) or not names or not all(isinstance(n, str) and n.strip() for n in names) \
            or isinstance(floor, bool) or not isinstance(floor, int) or floor < 0:
        return None, ("%s declares %s as %r - it must be {\"releases\": [names], \"sentences_seeded\": a count}"
                      % (DECLARATIONS, DECLARATION_KEY, entry))
    return {"releases": tuple(names), "sentences_seeded": floor}, None


def releases(root=None):
    """The release names this project's items may name."""
    return (declared(root)[0] or {}).get("releases") or DEFAULT_RELEASES


def flat(text):
    return " ".join(str(text or "").split())


def _root(root):
    return ROOT if root is None else root


def slug(path):
    """The folder name the harness gives a project path: every character that is not a letter or
    a digit becomes a dash. Measured: `C:\\...\\sessions\\My App` is
    `C--...-sessions-My-App`."""
    return re.sub(r"[^A-Za-z0-9]", "-", os.path.normpath(str(path)))


def _epoch(stamp):
    try:
        return calendar.timegm(time.strptime(str(stamp)[:19], "%Y-%m-%dT%H:%M:%S"))
    except (TypeError, ValueError):
        return None


# ---------------------------------------------------------------------------- one transcript

def _read_json(path):
    try:
        with io.open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return None


def _write_text(path, text):
    folder = os.path.dirname(path)
    os.makedirs(folder, exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline=LF) as handle:
        handle.write(text)


def _own_digest():
    """What this reader and the ledger's reader are, as bytes - the cache's subject."""
    digest = hashlib.sha256()
    for path in (os.path.abspath(__file__), os.path.abspath(request_ledger.__file__)):
        try:
            with io.open(path, "rb") as handle:
                digest.update(handle.read())
        except OSError:
            digest.update(b"unreadable")
    return digest.hexdigest()[:12]


def _has_text(row):
    return row.get("type") == "assistant" and any(
        isinstance(p, dict) and p.get("type") == "text" and str(p.get("text") or "").strip()
        for p in request_ledger._parts(row))


def transcript_facts(path, root=None):
    """{"messages": [{key, at, text, answered}], "cwds": [...]} for one transcript, or None when
    it cannot be read. Cached by size and mtime: a transcript nobody is writing is read once."""
    try:
        stat = os.stat(path)
    except OSError:
        return None
    # The digest of THIS reader is part of the key: a cache written by an older reading of the
    # transcript is a measurement of a different subject.
    stamp = "%s-%d-%d" % (_own_digest(), stat.st_size, int(stat.st_mtime))
    cache_dir = os.path.join(_root(root), CACHE)
    cached = os.path.join(cache_dir, os.path.basename(path) + ".json")
    held = _read_json(cached)
    if isinstance(held, dict) and held.get("stamp") == stamp:
        return held
    rows = request_ledger.read_rows(path)
    if rows is None:
        return None
    # ANSWERED MEANS A REPLY BEFORE THE OWNER SPOKE AGAIN. "Any text anywhere later" would mark a
    # question answered by a reply to a different message hours on - the loose reading, and the
    # cheapest way to file an ignored question as settled.
    asked = request_ledger.requests(rows)
    ends = [a["index"] for a in asked[1:]] + [len(rows)]
    messages = [{"key": r["key"], "at": r["at"], "text": r["text"],
                 "answered": any(_has_text(row) for row in rows[r["index"] + 1:end])}
                for r, end in zip(asked, ends)]
    cwds = sorted({str(r.get("cwd")) for r in rows if r.get("cwd")})
    facts = {"stamp": stamp, "messages": messages, "cwds": cwds}
    try:
        if not os.path.isfile(os.path.join(cache_dir, ".gitignore")):
            _write_text(os.path.join(cache_dir, ".gitignore"), "*" + LF)
        _write_text(cached, json.dumps(facts) + LF)
    except OSError:
        pass                            # a cache that cannot be written is only slower
    return facts


# ---------------------------------------------------------------------------- the scope

def _under(path, root):
    path = os.path.normcase(os.path.normpath(str(path)))
    root = os.path.normcase(os.path.normpath(str(root)))
    return path == root or path.startswith(root + os.sep)


def _transcripts(folder):
    return [os.path.join(folder, n) for n in sorted(os.listdir(folder)) if n.endswith(".jsonl")]


def _main_checkout(root):
    """The main checkout when `root` is a LINKED WORKTREE of it, else None - asked of git.

    A session that made a worktree itself, rather than being started in one, writes its transcripts
    under the MAIN checkout's folder, and a worktree's own folder exists only for a session started
    there. Measured 2026-10-15: committing F243 from such a worktree, the coverage check looked for
    the worktree's folder, found none, and refused the commit as "cannot look". Where git cannot say,
    the answer is None and the root's own folder is all there is - which still refuses when absent."""
    try:
        done = bounded.run(["git", "-C", root, "rev-parse", "--path-format=absolute", "--git-common-dir"],
                           capture_output=True, text=True, timeout=30,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except (OSError, subprocess.SubprocessError):
        return None
    common = os.path.normpath(done.stdout.strip()) if done.returncode == 0 else ""
    if os.path.basename(common) != ".git":
        return None
    main = os.path.dirname(common)
    return None if os.path.normcase(main) == os.path.normcase(os.path.normpath(str(root))) else main


def scope(register, root=None, projects=None, transcript=None):
    """(folders, candidates, trouble). The folders whose transcripts are this project's: the one
    the current transcript sits in (else the root's own), plus each folder a former location left.

    A CANDIDATE is a folder that exists for a `cwd` the transcripts recorded outside this root.
    Every candidate must be classified in the register's "folders" - an unclassified one is
    trouble, because a folder that falls out of scope by nobody mentioning it is the exact
    narrowing this file exists to refuse."""
    root, projects = _root(root), projects or PROJECTS
    main = _main_checkout(root)
    mains = os.path.join(projects, slug(main)) if main else None
    home = os.path.dirname(transcript) if transcript else os.path.join(projects, slug(root))
    if not transcript and not os.path.isdir(home) and mains and os.path.isdir(mains):
        home = mains                    # a worktree a session made itself: its transcripts are the main's
    if not os.path.isdir(home):
        return None, [], "the transcript folder %s does not exist - cannot look is not empty" % home
    classified = (register or {}).get("folders") or {}
    include = classified.get("include") or {}
    exclude = classified.get("exclude") or {}
    folders, candidates, unclassified = [home], [], []
    # THE ROOT'S OWN FOLDER IS ALWAYS THIS PROJECT'S. A session in a linked worktree under the
    # root writes its transcript to the WORKTREE's folder, and its `cwd` is under the root, so the
    # root's folder never became a candidate: the register read one session, and every item citing
    # an owner message from the root's history read as citing nothing. Measured 2026-10-06 - ten
    # superseded items refused every turn end of a session working in a linked worktree.
    own = os.path.join(projects, slug(root))
    if os.path.normcase(own) != os.path.normcase(home) and os.path.isdir(own):
        folders.append(own)
    if mains and os.path.isdir(mains) and os.path.normcase(mains) not in {os.path.normcase(f) for f in folders}:
        folders.append(mains)           # a linked worktree is this project, and so is the checkout it hangs off
    queue = list(folders)
    while queue:
        for path in _transcripts(queue.pop(0)):
            for cwd in (transcript_facts(path, root) or {}).get("cwds") or []:
                if _under(cwd, root) or (main and _under(cwd, main)):
                    continue
                other = os.path.join(projects, slug(cwd))
                if not os.path.isdir(other) or other in folders or other in candidates:
                    continue
                candidates.append(other)
                label = os.path.basename(other)
                if len(flat(include.get(label))) >= MINIMUM["item"]:
                    folders.append(other)
                    queue.append(other)
                elif len(flat(exclude.get(label))) < MINIMUM["item"]:
                    unclassified.append(label)
    if unclassified:
        return folders, candidates, (
            "transcript folder(s) a former location of this project left, classified nowhere: "
            "%s - put each under the register's folders.include or folders.exclude with a reason"
            % ", ".join(unclassified))
    return folders, candidates, None


def messages(folders, root=None):
    """[{id, at, text, answered, rows: [(session, key)]}] - every owner message in every
    transcript of the given folders, one entry per MESSAGE however many rows carry it."""
    rows = []
    for folder in folders:
        for path in _transcripts(folder):
            facts = transcript_facts(path, root)
            for message in (facts or {}).get("messages") or []:
                rows.append((message["at"] or "", os.path.basename(path)[:-6], message))
    rows.sort(key=lambda r: r[0])
    out, by_text = [], {}
    for at, session, message in rows:
        said = flat(message["text"])
        when = _epoch(at)
        same = None
        for earlier in by_text.get(said, []):
            if when is not None and earlier["_epoch"] is not None and \
                    abs(when - earlier["_epoch"]) <= SAME_MESSAGE_SECONDS:
                same = earlier
                break
        if same is not None:
            same["rows"].append((session, message["key"]))
            same["answered"] = same["answered"] or message["answered"]
            continue
        entry = {"id": "m" + hashlib.sha256((said + "|" + at[:16]).encode("utf-8")).hexdigest()[:10],
                 "at": at, "text": message["text"], "answered": bool(message["answered"]),
                 "rows": [(session, message["key"])], "_epoch": when}
        by_text.setdefault(said, []).append(entry)
        out.append(entry)
    for entry in out:
        entry.pop("_epoch", None)
    return out


# ---------------------------------------------------------------------------- the ledgers

def ledger_held(root=None):
    """{session: {"since": key, "items": {request key: [items]}}} from every session ledger's
    store - read only; the store is `request_ledger.py`'s alone to write. From a linked worktree the
    main checkout's store is read too: the hooks that keep a session's ledger are the project's, and a
    session that made the worktree itself keeps its ledger there (2026-10-15)."""
    root = _root(root)
    main = _main_checkout(root)
    out = {}
    for base in [root] + ([main] if main else []):
        folder = os.path.join(base, request_ledger.STORE)
        try:
            names = os.listdir(folder)
        except OSError:
            continue
        for name in names:
            held = _read_json(os.path.join(folder, name)) if name.endswith(".json") else None
            if not isinstance(held, dict) or name[:-5] in out:
                continue
            items = {}
            for item in held.get("items") or []:
                items.setdefault(item.get("request"), []).append(item)
            out[name[:-5]] = {"since": held.get("since"), "items": items}
    return out


def _session_keys(folders, root=None):
    """{session: [request keys in order]} - where each session's ledger begins is a position."""
    out = {}
    for folder in folders:
        for path in _transcripts(folder):
            facts = transcript_facts(path, root) or {}
            out[os.path.basename(path)[:-6]] = [m["key"] for m in facts.get("messages") or []]
    return out


def in_ledger_scope(session, key, ledgers, keys):
    ledger = ledgers.get(session)
    if not ledger or not ledger.get("since"):
        return False
    order = keys.get(session) or []
    if ledger["since"] not in order or key not in order:
        return False
    return order.index(key) >= order.index(ledger["since"])


# ---------------------------------------------------------------------------- the register

def empty_register():
    return {"_why": "Every owner message of this project, and what became of it. Written only "
                    "by asked.py beside the hooks; read by guard_asked.py at the end of every turn.",
            "folders": {"include": {}, "exclude": {}}, "items": {}, "messages": {}}


def load(root=None):
    """(register, trouble). An absent register is an EMPTY one - every message then owes a
    disposition, so deleting it reopens everything and frees nothing."""
    path = os.path.join(_root(root), REGISTER)
    if not os.path.isfile(path):
        return empty_register(), None
    held = _read_json(path)
    if not isinstance(held, dict) or not isinstance(held.get("items"), dict) \
            or not isinstance(held.get("messages"), dict):
        return None, "the register at %s cannot be read as a register - an unreadable " \
                     "register is not an empty one" % path
    return held, None


def save(register, root=None):
    _write_text(os.path.join(_root(root), REGISTER),
                json.dumps(register, indent=1, sort_keys=True, ensure_ascii=False) + LF)


def _commit_exists(sha, root):
    try:
        done = bounded.run(["git", "-C", root, "cat-file", "-e", sha + "^{commit}"],
                           capture_output=True, timeout=30,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except (OSError, subprocess.SubprocessError):
        return False
    return done.returncode == 0


def evidence_holds(said, root=None):
    """A commit reachable here, or a `path` that exists (in the repository, or absolute)."""
    root = _root(root)
    for span in BACKTICKED.findall(said):
        span = span.strip()
        if os.path.exists(span if os.path.isabs(span) else os.path.join(root, span)):
            return True
    return any(_commit_exists(sha, root) for sha in COMMIT.findall(said))


def item_problems(register, known, root=None):
    """Why an item cannot stand as it is written."""
    out = []
    for item_id, item in sorted(register["items"].items()):
        status, said = item.get("status"), flat(item.get("said"))
        if len(flat(item.get("ask"))) < MINIMUM["item"]:
            out.append("item %s says nothing about what was asked" % item_id)
        if status not in STATUSES:
            out.append("item %s has status %r - one of %s" % (item_id, status, ", ".join(STATUSES)))
            continue
        if item.get("release") not in releases(root):
            out.append("item %s names no release (%s)" % (item_id, ", ".join(releases(root))))
        if status == "open":
            continue
        if len(said) < MINIMUM[status]:
            out.append("item %s is %s in %d characters - under %d is a label, not a reason"
                       % (item_id, status, len(said), MINIMUM[status]))
        elif status in ("done", "standing") and not evidence_holds(said, root):
            out.append("item %s is %s with nothing checkable: a commit in this repository, or a "
                       "`path` that exists" % (item_id, status))
        elif status in ("dropped", "superseded") and \
                not [m for m in MESSAGE_ID.findall(said) if m in known]:
            out.append("item %s is %s and names no owner message that says so - only the "
                       "owner drops what the owner asked (cite the m... id)" % (item_id, status))
    return out


def disposition_problem(entry, disposition, register):
    """Why a message's disposition does not hold, or ''."""
    if not isinstance(disposition, dict):
        return "no disposition"
    if disposition.get("items"):
        missing = [i for i in disposition["items"] if i not in register["items"]]
        return ("names item(s) the register does not hold: %s" % ", ".join(missing)) if missing else ""
    if "answered" in disposition:
        if len(flat(disposition["answered"])) < MINIMUM["answered"]:
            return "is answered with a label, not a reason"
        return "" if entry["answered"] else "is marked answered and no reply follows it"
    if "context" in disposition:
        return "" if len(flat(disposition["context"])) >= MINIMUM["context"] else \
            "is context with a label, not a reason"
    return "has a disposition of no known kind"


#: EVERY SENTENCE, NOT ONLY EVERY MESSAGE (F224; the owner's request of 2026-09-26 for a list "complete
#: and total and absolute"). A message is disposed of whole, and a message linked to one item can hold a
#: second ask in its third sentence. Measured 2026-10-15 in DF Engine: 2,754 sentences of 496 disposed messages
#: shared no word with any disposition of theirs. A disposition covers the sentences it shares a word with - the
#: request ledger's own rule, `request_ledger.content`, plain endings aside - and a sentence it does not cover
#: carries its own: an item whose ask names it, an answer, or context with a reason. The count only falls: it may
#: never pass the project's declared `sentences_seeded` - the measurement the rule arrived at there - nor anything
#: lower a verb has recorded since.
SENTENCE_CEILING = "sentence_ceiling"


def _stems(text):
    """A sentence's content words with a plain ending taken off: `filter options` names an item asking
    for `filters`, and the first message read under this rule was refused over exactly that."""
    out = set()
    for word in request_ledger.content(text):
        for ending in ("ing", "ed", "es", "s"):
            if word.endswith(ending) and len(word) - len(ending) >= 4:
                word = word[:-len(ending)]
                break
        out.add(word)
    return out


def _words_of(disposition, register):
    """The words a disposition says - its items' asks, or its answer or reason."""
    if disposition.get("items"):
        words = set()
        for item_id in disposition["items"]:
            words |= _stems((register["items"].get(item_id) or {}).get("ask"))
        return words
    return _stems(disposition.get("answered") or disposition.get("context"))


def sentence_problem(entry, number, sentence, held, register):
    """Why one sentence's own disposition does not cover it, or ''. An item must NAME the sentence: linking
    every leftover sentence to whatever item is nearest is the cheapest way past this rule."""
    why = disposition_problem(entry, held, register)
    if why:
        return why
    if held.get("items") and not _stems(sentence) & _words_of(held, register):
        return "names item(s) whose ask shares no word with sentence %d - an item covers what it names" % number
    return ""


def sentence_gaps(entry, disposition, register):
    """[(number, sentence)]: the sentences of a disposed message that no disposition covers."""
    if not isinstance(disposition, dict):
        return []
    words = _words_of(disposition, register)
    own = disposition.get("sentences") or {}
    out = []
    for number, sentence in enumerate(request_ledger.clauses(entry["text"]), 1):
        need = _stems(sentence)
        if not need or need & words:
            continue
        held = own.get(str(number))
        if isinstance(held, dict) and not sentence_problem(entry, number, sentence, held, register):
            continue
        out.append((number, sentence))
    return out


def all_sentence_gaps(register, found):
    """[(message, number, sentence)] over every message the register disposes of."""
    out = []
    for entry in found:
        disposition = register["messages"].get(entry["id"])
        if disposition is not None:
            out += [(entry, number, sentence) for number, sentence in sentence_gaps(entry, disposition, register)]
    return out


def sentence_ceiling(register, root=None):
    """The most uncovered sentences the register may hold: the declared seed, or anything lower recorded since."""
    seed = (declared(root)[0] or {}).get("sentences_seeded", 0)
    recorded = register.get(SENTENCE_CEILING)
    return min(seed, recorded) if isinstance(recorded, int) else seed


def tighten(register, found, root=None):
    """Record the count when it fell - a verb that disposes of a sentence lowers the bar behind it."""
    count = len(all_sentence_gaps(register, found))
    if count < sentence_ceiling(register, root):
        register[SENTENCE_CEILING] = count


def _live(folders, now):
    out = {}
    for folder in folders:
        for path in _transcripts(folder):
            try:
                out[os.path.basename(path)[:-6]] = now - os.stat(path).st_mtime < LIVE_SECONDS
            except OSError:
                out[os.path.basename(path)[:-6]] = False
    return out


def problems(root=None, transcript=None, session=None, projects=None, now=None):
    """Everything that keeps a turn open. None when the transcripts cannot be read."""
    root = _root(root)
    config, trouble = declared(root)
    if trouble:
        return [trouble]
    if config is None:
        return []                       # not switched on here: see "OFF UNTIL THE PROJECT DECLARES IT"
    register, trouble = load(root)
    if trouble:
        return [trouble]
    folders, _candidates, trouble = scope(register, root, projects, transcript)
    if folders is None:
        return None
    out = [trouble] if trouble else []
    found = messages(folders, root)
    out += item_problems(register, {m["id"] for m in found}, root)
    ledgers, keys = ledger_held(root), _session_keys(folders, root)
    live = _live(folders, time.time() if now is None else now)
    owing = []
    for entry in found:
        disposition = register["messages"].get(entry["id"])
        if disposition is not None:
            why = disposition_problem(entry, disposition, register)
            if why:
                out.append("message %s (%s) %s" % (entry["id"], entry["at"][:10], why))
            continue
        # THE CURRENT SESSION'S ledger is asked by guard_requests, and a LIVE session's is its
        # own to finish: a message inside either is not refused twice for one thing.
        held = any(in_ledger_scope(s, k, ledgers, keys) and (
            ledgers.get(s, {}).get("items", {}).get(k) or s == session or live.get(s))
            for s, k in entry["rows"])
        if not held:
            owing.append(entry)
    if owing:
        out.append("%d owner message(s) have no disposition and no ledger holds them - first: "
                   "%s (%s) \"%s\"" % (len(owing), owing[0]["id"], owing[0]["at"][:10],
                                       flat(owing[0]["text"])[:100]))
    gaps, ceiling = all_sentence_gaps(register, found), sentence_ceiling(register, root)
    if len(gaps) > ceiling:
        entry, number, sentence = gaps[0]
        out.append("%d sentence(s) of disposed messages are covered by no disposition, over the ceiling of "
                   "%d - first: %s (%s) sentence %d \"%s\" - give it an item that names it, an answer or "
                   "context: `asked.py sentence <message> <n> items|answered|context <ids or reason>`"
                   % (len(gaps), ceiling, entry["id"], entry["at"][:10], number, flat(sentence)[:100]))
    return out


# ---------------------------------------------------------------------------- the list

def pending(root=None, transcript=None, projects=None):
    """(header, lines): every item not closed - the register's and every session ledger's - with
    the scope it was taken over. This is THE list; a list built any other way is narrower."""
    root = _root(root)
    register, trouble = load(root)
    if trouble:
        return trouble, []
    folders, _c, trouble = scope(register, root, projects, transcript)
    if folders is None:
        return "cannot look: " + (trouble or "no transcript folder"), []
    found = messages(folders, root)
    by_id = {m["id"]: m for m in found}
    sources = {}
    for message_id, disposition in register["messages"].items():
        for item_id in (disposition or {}).get("items") or []:
            sources.setdefault(item_id, []).append(message_id)
    lines = []
    for release in releases(root):
        open_items = [(i, it) for i, it in sorted(register["items"].items())
                      if it.get("status") == "open" and it.get("release") == release]
        if not open_items:
            continue
        lines += ["", "## %s  (%d open)" % (release, len(open_items))]
        for item_id, item in open_items:
            dates = sorted(by_id[m]["at"][:10] for m in sources.get(item_id, []) if m in by_id)
            when = (dates[0] + (".." + dates[-1] if dates[-1] != dates[0] else "")) if dates else "?"
            lines.append("- %s  %s  [asked %s]" % (item_id, flat(item["ask"]), when))
    ledger_open = []
    for ledger in ledger_held(root).values():
        for items in ledger["items"].values():
            ledger_open += ["- %s  %s  (%s)" % (item.get("id"), flat(item.get("ask"))[:140],
                                                item.get("status"))
                            for item in items
                            if item.get("status") not in ("done", "answered", "declined")]
    if ledger_open:
        lines += ["", "## session ledgers, not done (%d)" % len(ledger_open)] + sorted(ledger_open)
    gaps = all_sentence_gaps(register, found)
    if gaps:
        lines += ["", "## sentences no disposition covers yet (%d, ceiling %d) - each may hold an ask; "
                      "`asked.py sentences` lists every one" % (len(gaps), sentence_ceiling(register, root))]
    header =("%d owner messages in %d transcript folder(s) (%s), %s .. %s"
              % (len(found), len(folders), ", ".join(os.path.basename(f) for f in folders),
                 found[0]["at"][:10] if found else "-", found[-1]["at"][:10] if found else "-"))
    return header, lines


# ---------------------------------------------------------------------------- the verbs

def apply(register, verb, args, known, root=None):
    """One verb against the register, checked as if typed. '' or why it was refused."""
    if verb == "item" and len(args) == 3:
        item_id, release, ask = args
        if item_id in register["items"]:
            return "item %s already exists" % item_id
        if release not in releases(root) or len(flat(ask)) < MINIMUM["item"]:
            return "usage: item <id> <release: %s> \"<what was asked>\"" % "|".join(releases(root))
        register["items"][item_id] = {"ask": ask, "release": release, "status": "open"}
        return ""
    if verb in STATUSES and len(args) == 2:
        item_id, said = args
        if item_id not in register["items"]:
            return "no item %s" % item_id
        trial = dict(register["items"][item_id], status=verb, said=said)
        why = [] if verb == "open" else item_problems({"items": {item_id: trial}}, known, root)
        if why:
            return why[0]
        register["items"][item_id] = trial
        return ""
    if verb == "release" and len(args) == 2:
        if args[0] not in register["items"] or args[1] not in releases(root):
            return "usage: release <item> <%s>" % "|".join(releases(root))
        register["items"][args[0]]["release"] = args[1]
        return ""
    if verb in ("link", "answered", "context") and len(args) == 2:
        ids = [r for r in re.split(r"[,\s]+", args[0]) if r]
        unknown = [r for r in ids if r not in known]
        if unknown:
            return "no owner message %s - `messages` lists them" % ", ".join(unknown[:3])
        items = [i for i in re.split(r"[,\s]+", args[1]) if i] if verb == "link" else []
        missing = [i for i in items if i not in register["items"]]
        if missing:
            return "no item %s" % ", ".join(missing)
        if verb != "link" and len(flat(args[1])) < MINIMUM[verb]:
            return "%s needs a reason of %d+ characters" % (verb, MINIMUM[verb])
        for message_id in ids:
            kept = (register["messages"].get(message_id) or {}).get("sentences")
            register["messages"][message_id] = {"items": items} if verb == "link" else {verb: args[1]}
            if kept:  # a message disposed of again keeps what its sentences were given
                register["messages"][message_id]["sentences"] = kept
        return ""
    if verb == "sentence" and len(args) == 4:
        message_id, numbers, kind, value = args
        entry, disposition = known.get(message_id), register["messages"].get(message_id)
        if entry is None or not isinstance(disposition, dict):
            return "no disposed owner message %s - a sentence refines its message's disposition" % message_id
        said = request_ledger.clauses(entry["text"])
        chosen = sorted({int(n) for n in re.split(r"[,\s]+", numbers) if n.isdigit()})
        if not chosen or chosen[0] < 1 or chosen[-1] > len(said):
            return "message %s has sentences 1-%d" % (message_id, len(said))
        if kind == "items":
            held = {"items": [i for i in re.split(r"[,\s]+", value) if i]}
        elif kind in ("answered", "context"):
            held = {kind: value}
        else:
            return "usage: sentence <message> <n[,m]> items|answered|context <item ids or reason>"
        for number in chosen:
            why = sentence_problem(entry, number, said[number - 1], held, register)
            if why:
                return "sentence %d of %s %s" % (number, message_id, why)
        for number in chosen:
            disposition.setdefault("sentences", {})[str(number)] = dict(held)
        return ""
    if verb == "folder" and len(args) == 3 and args[0] in ("include", "exclude"):
        if len(flat(args[2])) < MINIMUM["item"]:
            return "a folder is classified with a reason"
        register.setdefault("folders", {}).setdefault(args[0], {})[args[1]] = args[2]
        return ""
    return "unknown verb or wrong arguments: %s %s" % (verb, " ".join(args)[:80])


def main(argv, root=None):
    root = _root(root)
    session = os.environ.get(SESSION_VAR)
    transcript = request_ledger.find_transcript(session) if session else None
    config, trouble = declared(root)
    if trouble or config is None:
        # OFF IS SAID, never answered as "every owner message is accounted for" over a register nobody keeps.
        print(trouble or "the owner-message register is not switched on in this project: %s declares no %s"
              % (DECLARATIONS, DECLARATION_KEY))
        return 2 if trouble or verb_of(argv) != "check" else 0
    register, trouble = load(root)
    if trouble:
        print(trouble)
        return 2
    verb = verb_of(argv)
    if verb == "check":
        found = problems(root, transcript, session)
        if found is None:
            print("cannot look: the transcripts could not be read")
            return 2
        print(LF.join(found) if found else "every owner message is accounted for")
        return 1 if found else 0
    if verb == "pending":
        header, lines = pending(root, transcript)
        print(header)
        print(LF.join(lines) if lines else "nothing open")
        return 0
    folders, candidates, trouble = scope(register, root, None, transcript)
    if folders is None:
        print(trouble)
        return 2
    found = messages(folders, root)
    known = {m["id"]: m for m in found}
    if verb == "scope":
        print("folders   : %s" % ", ".join(folders))
        print("candidates: %s" % (", ".join(candidates) or "none"))
        print("messages  : %d, %s .. %s" % (len(found), found[0]["at"][:16] if found else "-",
                                            found[-1]["at"][:16] if found else "-"))
        counts = {}
        for item in register["items"].values():
            counts[item.get("status")] = counts.get(item.get("status"), 0) + 1
        print("items     : %d  %s" % (len(register["items"]), "  ".join(
            "%s %d" % (status, counts[status]) for status in STATUSES if status in counts)))
        print("disposed  : %d message(s) in the register" % len(register["messages"]))
        if trouble:
            print(trouble)
        return 1 if trouble else 0
    if verb == "messages":
        limit = int(argv[2]) if len(argv) > 2 and argv[2].isdigit() else 50
        owing = [m for m in found if m["id"] not in register["messages"]]
        for entry in owing[:limit]:
            print("%s %s %s  %s" % (entry["id"], entry["at"][:16], entry["rows"][0][0][:8],
                                   flat(entry["text"])[:160]))
        print("%d without a disposition (a session ledger may hold some)" % len(owing))
        return 0
    if verb == "sentences":
        # Every sentence of each message that has one uncovered, so it is judged in its message.
        limit = int(argv[2]) if len(argv) > 2 and argv[2].isdigit() else 10
        gaps = all_sentence_gaps(register, found)
        by_message = {}
        for entry, number, _sentence in gaps:
            by_message.setdefault(entry["id"], (entry, set()))[1].add(number)
        for entry, open_numbers in list(by_message.values())[:limit]:
            disposition = register["messages"][entry["id"]]
            said = ", ".join("%s: %s" % (i, flat(register["items"].get(i, {}).get("ask"))[:90])
                             for i in disposition.get("items") or []) or \
                flat(disposition.get("answered") or disposition.get("context"))[:160]
            print("%s %s  [%s]" % (entry["id"], entry["at"][:16], said))
            for number, sentence in enumerate(request_ledger.clauses(entry["text"]), 1):
                print("  %s%d  %s" % ("*" if number in open_numbers else " ", number, flat(sentence)))
        print("%d sentence(s) in %d message(s) no disposition covers, ceiling %d"
              % (len(gaps), len(by_message), sentence_ceiling(register, root)))
        return 0
    if verb == "batch" and len(argv) == 3:
        ops = _read_json(argv[2])
        if not isinstance(ops, list):
            print("a batch is a JSON list of [verb, arg, ...]")
            return 2
        for number, op in enumerate(ops, 1):
            why = apply(register, op[0], [str(a) for a in op[1:]], known, root) \
                if isinstance(op, list) and op else "not a verb"
            if why:
                print("batch refused at operation %d (%s): %s - nothing was written"
                      % (number, (op or ["?"])[0], why))
                return 2
            if op[0] == "folder":
                # A folder included in this batch widens the scope for the operations after it.
                grown, _c, _t = scope(register, root, None, transcript)
                known = {m["id"]: m for m in messages(grown or folders, root)}
        tighten(register, list(known.values()), root)
        save(register, root)
        print("applied %d operation(s)" % len(ops))
        return 0
    why = apply(register, verb, argv[2:], known, root)
    if why:
        print(why)
        return 2
    tighten(register, found, root)
    save(register, root)
    return 0


def verb_of(argv):
    """The verb a command line asks for, `check` when it names none."""
    return argv[1] if len(argv) > 1 else "check"


if __name__ == "__main__":
    # `pending` prints the owner's own words, which carry what cp1252 cannot hold, into a pipe.
    import bundle_shell                 # noqa: E402 - beside request_ledger, in this folder
    bundle_shell.speak_utf8()           # the one door out, as payload_text is the one in
    sys.exit(main(sys.argv))
