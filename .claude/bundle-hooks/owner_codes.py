#!/usr/bin/env python3
"""THE OWNER'S YES, TYPED BY THE OWNER - one reader, for every act only the owner may approve.

CLAUDE.md: AN AGENT FAN-OUT NEEDS A YES AND A PRICE. Measured 2026-10-06: an assistant told "no
agents" launched a five-agent design panel and a review fan-out anyway. It stated its own price
and called that the yes, called a system prompt the yes, and called the agents read-only. A yes
the assistant can give itself is no yes, so the yes is a CODE this file issues in a refusal and
only the owner can type back:

    issue(kind)        a fresh `KIND-XXXXXXXX` for the refusal to carry
    given(payload, rows)
                       did a row THE OWNER TYPED (`request_ledger.owner_typed`) carry, after it
                       was issued, the code a refusal of THIS EXACT CALL carried - the same tool,
                       every word of its input - and has no identical call spent it since?
    relayed(payload, rows)
                       does this call CARRY a code a refusal ISSUED, in any spelling the
                       flattening below sees?
    issued(rows)       every code a refusal in this transcript carried - and nothing else

WHY THE CODE IS READ FROM THE HARNESS'S RECORD. A refusal's reason reaches the transcript as the
refused call's `tool_result`, written by the harness. A row the owner typed is written by the
harness when the owner types: `origin.kind` is `human` on the row, or on the `queued_command`
attachment of a message sent mid-turn - measured 2026-10-07 over this session's transcript, all 25
messages the owner typed carried it, and no hook output, task notification, summary or harness
interrupt did.

WHAT AN ASSISTANT COULD DO INSTEAD, and what refuses it: say the code itself (an assistant row is
not the owner's); have a hook, a subagent, a summary or another session say it (none is a row the
owner typed); send it to itself through a scheduled message, a trigger, a peer or a file
(`guard_owner_codes` refuses any call carrying a code); or write a forged row into the transcript
(`guard_owner_files` refuses a Write or Edit into the owner's Claude folder, and
`guard_command_rules` a command that names it). What none of them can see, said rather than
implied: a program that builds the transcript's path, or the code, from pieces - deliberate
concealment, the same limit `gate_stamps.forges` names.
"""
import hashlib
import json
import os
import re
import secrets
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import request_ledger    # noqa: E402  - the owner's typed words, read strictly

#: Every code this file issues: a kind, then eight characters of base32.
CODE = re.compile(r"\b(?:FANOUT|CONFIG)-([A-Z2-7]{8})\b")

#: How the harness records a hook's refusal: the refused call's OWN result, opening with the event
#: and the tool - measured 2026-10-09 over this bundle's session transcript, for both refusal
#: protocols (a JSON deny forwarded by the dispatcher and an exit 2). Nothing a document, a report,
#: an agent or a command prints is recorded that way, so a code is ISSUED only where this opens.
#:
#: A CODE-SHAPED WORD IS NOT A CODE. Every code-shaped string anywhere in the transcript counted as
#: issued, and the spoken form was refused in any call: reported 2026-10-09 from a brand-new
#: project, readers quoting the docs' example of a code - `CONFIG` and a hyphen before an
#: eight-letter word - made that word, "settings", refuse every call that carried it for the rest
#: of the session; and this bundle's own session had two such words, from its tests' sample codes.
REFUSAL = re.compile(r"^\s*(?:Error:\s*)?PreToolUse:\S+ hook error:")

#: How the harness records a call that NEVER RAN, so spent nothing: a hook's refusal (`REFUSAL`),
#: or the harness's own refusal at its concurrent-subagent limit. Measured 2026-10-09 in the Forven
#: session's transcript: the owner typed fourteen codes, this guard passed each launch, the harness
#: refused all fourteen with this text and `is_error`, and every retry was refused here as though
#: the code had been spent. Anything else spends the yes - a result that ran, the owner's own
#: interruption (`[Request interrupted by user for tool use]`, after which an agent may have run),
#: a call still in flight, a shape nobody measured: a yes read as unspent is a second launch the
#: owner never approved, and an unknown is not a refusal.
NEVER_RAN = re.compile(r"(?:%s)|^\s*Concurrent subagent limit reached\b" % REFUSAL.pattern)
_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"


def issue(kind):
    """A fresh code - `secrets`, so no earlier code says anything about the next one."""
    return "%s-%s" % (kind, "".join(secrets.choice(_ALPHABET) for _ in range(8)))


def key(name, given):
    """The call a yes is FOR: its tool and every word of its input. Approving one survey agent
    does not approve a fifty-agent run, and approving one settings line does not approve another."""
    return hashlib.sha256(json.dumps([name, given], sort_keys=True, default=str)
                          .encode("utf-8")).hexdigest()


def given(payload, rows):
    """Did the owner type, after it was issued, the code a refusal of THIS call carried - and has
    no identical call spent it yet?

    The code is read from the refusal the harness recorded for an earlier call with the same tool
    and input, and the yes from a row the owner typed after that refusal. An identical call
    between the yes and this one spent it, unless the harness recorded that it never ran
    (`NEVER_RAN`); this call itself does not."""
    want = key(payload.get("tool_name"), payload.get("tool_input"))
    current = payload.get("tool_use_id")
    uses, results, typed = [], {}, []
    for index, row in enumerate(rows or []):
        if not isinstance(row, dict):
            continue
        words = request_ledger.owner_typed(row)
        if words:
            typed.append((index, words))
        for slot, part in enumerate(request_ledger.parts(row)):
            if part.get("type") == "tool_use":
                uses.append(((index, slot), part.get("id"), key(part.get("name"), part.get("input"))))
            elif part.get("type") == "tool_result":
                text = request_ledger.result_text(part.get("content"))
                results[part.get("tool_use_id")] = ((index, slot), text, part.get("is_error") is True
                                                    and bool(NEVER_RAN.match(text)))
    here = next((at for at, use, _key in uses if current and use == current), (len(rows or []), 0))
    same = [(at, use) for at, use, held in uses if held == want and use != current]
    spent = [at for at, use in same if not (use in results and results[use][2])]
    for at, use in same:
        if at >= here or use not in results:
            continue
        issued_at, text, _never_ran = results[use]
        for body in set(_carried(text)):
            yes = next((i for i, words in typed if i > issued_at[0] and body in words), None)
            if yes is not None and not any((yes, 0) < other < here for other in spent):
                return True
    return False


#: How a refusal ISSUES a code: alone on a line of its own, as every guard that issues one writes
#: it (`guard_fanout`, `guard_agent_edits`, `guard_owner_files`: "by typing:\n\n    <code>").
#: A code anywhere else in a refusal is QUOTED - measured 2026-10-10 by the DF Engine worktree
#: session: a write-time rule ran a test that printed its made-up code (`said='<code>, and carry
#: on'`), the refusal carried the run's output, and that code read as issued for the rest of the
#: session. Only the line a guard writes for the owner to copy issues one.
ISSUED_LINE = re.compile(r"^[ \t]*" + CODE.pattern + r"[ \t]*\r?$", re.M)


def _carried(text):
    """The code bodies a refusal's recorded text ISSUES - each alone on its own line - and none
    when the text is not a refusal."""
    return ISSUED_LINE.findall(text) if isinstance(text, str) and REFUSAL.match(text) else []


def issued(rows):
    """Every code body a refusal recorded in `rows` carried."""
    found = set()
    for row in rows or []:
        if isinstance(row, dict):
            for part in request_ledger.parts(row):
                if part.get("type") == "tool_result":
                    found.update(_carried(request_ledger.result_text(part.get("content"))))
    return found


def relayed(payload, rows):
    """Why this call carries a code, or ''. `rows` None: the transcript could not be read.

    THE CALL, FLATTENED. Its input as JSON, upper-cased, with everything but letters and digits
    removed - so a code split across quotes, spaced out, joined with `+` or lower-cased is the same
    eight characters - is searched for every code a refusal ISSUED (`issued`). Where the
    transcript cannot be read, which codes were issued is UNKNOWN, and the code's own shape -
    the kind, a hyphen, eight base32 capitals - is refused instead: never a lower-case word."""
    given_input = (payload or {}).get("tool_input")
    try:
        said = json.dumps(given_input, sort_keys=True, default=str)
    except (TypeError, ValueError):
        said = str(given_input)
    if rows is None:
        shaped = CODE.search(said)
        return ("it carries %s, and which codes were issued cannot be read" % shaped.group(0)
                if shaped else "")
    flat = re.sub(r"[^A-Z0-9]", "", said.upper())
    for body in sorted(issued(rows)):
        if body in flat:
            return "it carries the code %s..., spelled so a reader would not see it" % body[:2]
    return ""
