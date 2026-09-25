#!/usr/bin/env python3
"""EVERY AGENT IS ASKED FOR A STRUCTURED RESULT, AND TOLD AN ADMITTED GAP IS WORTH MORE.

README section 12, verbatim: *every agent returns a structured result with `provenByBreaking`,
`batteryGreen`, `leftUndone` - and is told plainly that an admitted gap is worth more than a
confident wrong answer.* It was a bullet in a list, and nothing asked any launch whether its
brief said so.

WHAT IT COST, and why the three fields are these three. A 22-agent verification pass cost 2.9M
tokens to produce 21 opinions; a mechanical sweep over the same fixes cost 545k and found a real
hole the opinions had rated "fixed". Relayed agent framing was repeatedly reported as fact and
later proved wrong - a property test praised as "432 exhaustive shapes" was 144 behaviours with
one vacuous property. A result that does not say whether its claim was PROVEN BY BREAKING, whether
the BATTERY was green, and what was LEFT UNDONE is an opinion in a result's clothes, and the one
reading it cannot tell which parts were measured.

And an agent that is not told a gap is acceptable reports none: a completeness critic that had
run out of real gaps began naming sibling worktrees and PNG screenshots rather than say it was
finished, at 791 agents and 111.9M tokens.

WHAT IS REFUSED. An agent launch - found by SHAPE, a `prompt` beside a `subagent_type` or a
`description` - whose brief does not name all three fields and does not carry the sentence about
an admitted gap. The cheapest way past this is to paste the words, and pasting them IS the
instruction: the agent reads its brief, not the hook.

TO LAUNCH ANYWAY: the owner sets `bundle_env.name("ALLOW_UNSTRUCTURED_AGENT")` to 1.
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_env        # noqa: E402  - one PREFIX renames every override
import bundle_shell      # noqa: E402  - the call, found by shape, at any depth

BYPASS = bundle_env.name("ALLOW_UNSTRUCTURED_AGENT")

#: The three fields README section 12 names. Asked as identifiers, case-insensitively.
FIELDS = ("provenByBreaking", "batteryGreen", "leftUndone")

#: The sentence the agent must be told. Whitespace and case do not matter; the words do.
ADMISSION = re.compile(r"admitted\s+gap\s+is\s+worth\s+more\s+than\s+a\s+confident\s+wrong\s+"
                       r"answer", re.I)


def missing(brief):
    """What this brief leaves out - field names, and the admission sentence. Empty when whole."""
    text = brief or ""
    out = [field for field in FIELDS if not re.search(r"\b" + field + r"\b", text, re.I)]
    if not ADMISSION.search(" ".join(text.split())):
        out.append("the sentence 'an admitted gap is worth more than a confident wrong answer'")
    return out


def verdict(payload):
    for launch in bundle_shell.launches(payload):
        absent = missing(launch.get("prompt"))
        if absent:
            return True, (
                bundle_shell.policy(__file__) + "\n"
                "This agent's brief does not ask for a structured result. Missing: %s.\n\n"
                "README section 12: every agent returns `provenByBreaking`, `batteryGreen` and "
                "`leftUndone`, and is told plainly that an admitted gap is worth more than a "
                "confident wrong answer. MEASURED: 22 agents, 2.9M tokens, 21 opinions - and a "
                "545k mechanical sweep found the hole they had rated fixed.\n\n"
                "Add to the brief, e.g.:\n"
                "    Return JSON: {\"provenByBreaking\": [...], \"batteryGreen\": true|false,\n"
                "    \"leftUndone\": [...]}. An admitted gap is worth more than a confident\n"
                "    wrong answer.\n" % "; ".join(absent))
    return False, None


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        sys.exit(0)                      # never break a turn on a parse failure
    if not isinstance(payload, dict) or not bundle_shell.launches(payload):
        sys.exit(0)
    if bundle_env.allowed("ALLOW_UNSTRUCTURED_AGENT"):
        sys.exit(0)
    deny, reason = verdict(payload)
    if deny:
        json.dump({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }}, sys.stdout)
    sys.exit(0)


if __name__ == "__main__":
    main()
