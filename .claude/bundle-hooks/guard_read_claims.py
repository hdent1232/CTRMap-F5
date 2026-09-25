#!/usr/bin/env python3
"""A CLAIM ABOUT A FILE MUST BE ABOUT A FILE THAT WAS OPENED, AND MUST BE TRUE OF IT.

README section 14, SAY YOU LOOKED: *the read list is a claim, and claims are free. Caught here: an
agent reported reading `build.ps1 (lines 1-100 of 210)` and wrote a paragraph about what lines
100-210 said. No copy of that file in any checkout is longer than 115 lines.* The refusal it
names: every claim that carries its own size - a line range, a line count, a byte count - is
checked against the file, and one that cannot be true fails.

AND THE RULE CLAUDE.md FILED AS HAVING NO MECHANICAL FORM. *A CLAIM ABOUT WHAT YOU DID NOT OPEN
IS A MEASUREMENT* was recorded as unenforceable because "the nearest mechanical form judges the
last message of a turn and cannot know which files were opened to write it". It can: the
transcript at the end of a turn holds every tool call and every result of the session. A message
citing `path:line` about a file no call ever touched is a claim about something nobody opened,
and that is now refused at the only chokepoint a report has - the end of the turn.

WHAT IS CHECKED, in the final message of the turn:

    `path:NN` or `](path:NN)`     the file exists, has at least NN lines, and was OPENED
    `path (lines A-B of N)`       the file has exactly N lines, B is within it, and was opened
    `path (N lines)`, `(N bytes)` the count is the file's own, and it was opened

"Opened" means the path appears in some tool call's input or some tool result this session - a
`Read`, a `Grep` hit, an `Edit`, a shell command naming it.

WHAT IS DELIBERATELY NOT. A bare path with no line and no size: README - *an unfalsifiable claim
is not made falsifiable by pretending, and the only effect of refusing those would be to teach
the next run to drop its annotations.* That is also this guard's cheapest evasion, and it is the
one the handoff chose to accept, in writing.

IT DOES NOT LOOP. The same text refused once already this session passes the second time, as
`guard_promise` does; a CHANGED text that is still false is refused again.
"""
import hashlib
import io
import json
import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_shell      # noqa: E402  - the call, found by shape

LF = chr(10)

#: A path: something with a directory separator or a file extension, no spaces, not a URL.
_PATH = r"(?P<path>(?![a-z]+://)[A-Za-z0-9_.~\-/\\]*[A-Za-z0-9_\-]\.[A-Za-z0-9]{1,8})"

#: `path:NN` or `path:NN-MM`, inside backticks or as a markdown link target.
LINE_REF = re.compile(r"(?:`|\]\()" + _PATH + r":(?P<a>\d+)(?:-(?P<b>\d+))?(?:`|\))")

#: `path (lines A-B of N)`, `path (N lines)`, `path (N bytes)`, the path optionally in backticks.
SIZE_CLAIM = re.compile(
    r"`?" + _PATH + r"`?\s*\((?:lines?\s+(?P<a>\d+)\s*[-–]\s*(?P<b>\d+)\s+of\s+"
    r"(?P<of>[\d,]+)|(?P<lines>[\d,]+)\s+lines?|(?P<bytes>[\d,]+)\s+bytes?)\)", re.I)


def _repo_root():
    """The project root, ANCHORED against a file that must be in it (see guard_promise)."""
    here = os.path.dirname(os.path.abspath(__file__))
    while True:
        if os.path.isfile(os.path.join(here, "CLAUDE.md")):
            return here
        parent = os.path.dirname(here)
        if parent == here:
            return None
        here = parent


def _entries(transcript_path):
    try:
        with io.open(transcript_path, encoding="utf-8", errors="replace") as handle:
            lines = handle.read().splitlines()
    except (OSError, TypeError):
        return None
    out = []
    for line in lines:
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        if isinstance(entry, dict):
            out.append(entry)
    return out


def last_text(entries):
    """The text of the final assistant message, or "" when there is none."""
    for entry in reversed(entries):
        if entry.get("type") != "assistant":
            continue
        parts = [b.get("text") or "" for b in (entry.get("message") or {}).get("content") or []
                 if isinstance(b, dict) and b.get("type") == "text"]
        if parts:
            return LF.join(parts)
    return ""


def _strings(node, out, depth=0):
    if depth > 8:
        return
    if isinstance(node, str):
        out.append(node)
    elif isinstance(node, dict):
        for value in node.values():
            _strings(value, out, depth + 1)
    elif isinstance(node, (list, tuple)):
        for value in node:
            _strings(value, out, depth + 1)


def opened(entries):
    """Everything any tool call named or returned this session, as one normalised haystack."""
    found = []
    for entry in entries:
        for block in (entry.get("message") or {}).get("content") or []:
            if not isinstance(block, dict):
                continue
            if block.get("type") == "tool_use":
                _strings(block.get("input"), found)
            elif block.get("type") == "tool_result":
                _strings(block.get("content"), found)
    return LF.join(found).replace(chr(92), "/").lower()


def _norm(path):
    return path.replace(chr(92), "/").lstrip("./").lower() if not os.path.isabs(path) \
        else path.replace(chr(92), "/").lower()


def _count_lines(path):
    with io.open(path, "rb") as handle:
        data = handle.read()
    return data.count(b"\n") + (1 if data and not data.endswith(b"\n") else 0), data


def problems(text, haystack, root):
    """Every claim in `text` that is false of its file or about a file nobody opened."""
    out = []
    seen = set()
    claims = []
    for match in LINE_REF.finditer(text or ""):
        claims.append((match.group("path"), match.group("a"), match.group("b"), None, None, None))
    for match in SIZE_CLAIM.finditer(text or ""):
        claims.append((match.group("path"), match.group("a"), match.group("b"),
                       match.group("of"), match.group("lines"), match.group("bytes")))
    for path, a, b, of, lines, size in claims:
        key = (path, a, b, of, lines, size)
        if key in seen:
            continue
        seen.add(key)
        full = path if os.path.isabs(path) else os.path.join(root or "", path)
        if not os.path.isfile(full):
            out.append("%s is cited and is not a file here" % path)
            continue
        if _norm(path) not in haystack and _norm(os.path.relpath(full, root or "")) not in haystack:
            out.append("%s is cited and NO tool call this session opened it - a claim about what "
                       "you did not open is a measurement nobody took" % path)
            continue
        try:
            count, data = _count_lines(full)
        except OSError:
            out.append("%s cannot be read to check the claim made about it" % path)
            continue
        for label, value in (("line", a), ("line", b)):
            if value and int(value) > count:
                out.append("%s is cited at %s %s and has %d lines" % (path, label, value, count))
        if of and int(of.replace(",", "")) != count:
            out.append("%s is claimed as '... of %s' lines and has %d" % (path, of, count))
        if lines and int(lines.replace(",", "")) != count:
            out.append("%s is claimed as %s lines and has %d" % (path, lines, count))
        if size:
            claimed = int(size.replace(",", ""))
            if claimed not in (len(data), len(data.replace(b"\r\n", b"\n"))):
                out.append("%s is claimed as %s bytes and is %d" % (path, size, len(data)))
    return out


def _seen_path(session_id):
    folder = os.path.join(tempfile.gettempdir(), "bundle-read-claims")
    try:
        os.makedirs(folder, exist_ok=True)
    except OSError:
        return None
    return os.path.join(folder, re.sub(r"[^A-Za-z0-9_.-]", "_", str(session_id or "none"))[:64]
                        + ".txt")


def main():
    try:
        payload = json.load(sys.stdin)
    except (ValueError, OSError):
        return 0
    # ONLY AT THE END OF A TURN - a payload that names a tool is a tool call (see guard_promise).
    if not isinstance(payload, dict) or bundle_shell.is_tool_call(payload):
        return 0
    entries = _entries(payload.get("transcript_path"))
    if entries is None:
        return 0
    text = last_text(entries)
    if not text:
        return 0
    found = problems(text, opened(entries), _repo_root())
    if not found:
        return 0
    digest = hashlib.sha256(text.encode("utf-8", "replace")).hexdigest()[:16]
    seen = _seen_path(payload.get("session_id"))
    if payload.get("stop_hook_active") and seen:
        try:
            if io.open(seen, encoding="utf-8").read().strip() == digest:
                return 0
        except OSError:
            pass
    if seen:
        try:
            io.open(seen, "w", encoding="utf-8").write(digest)
        except OSError:
            pass
    sys.stderr.write(LF.join(
        ["BLOCKED: this turn ends on a claim about a file that is false, or about a file "
         "nothing opened.", ""]
        + ["    " + line for line in found[:8]]
        + ["", "  README section 14: an agent reported `build.ps1 (lines 1-100 of 210)` and "
           "described lines", "  100-210; no copy of the file was longer than 115 lines. Open "
           "the file and check,", "  or drop the claim."]) + LF)
    return 2


if __name__ == "__main__":
    sys.exit(main())
