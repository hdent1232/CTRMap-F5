#!/usr/bin/env python3
"""NO CALL CARRIES THE OWNER'S CODE - the code reaches a row the owner typed by being TYPED.

`owner_codes` issues a one-time code in a refusal, and only a row the owner typed approves the
call it was issued for. What the assistant could do instead is put the code there itself by some
road that lands as a typed row: a message scheduled into its own session (`send_later`, a routine
fired into it), a message to a peer or another session that answers with it, a file another
process reads back, a forged row appended to the transcript. Measured 2026-10-07 against this
bundle's dispatcher with every guard wired: a `send_later` carrying a code was let through.

Each of those roads is a TOOL CALL that carries the code. So any call whose input carries one - in
any case, split across quotes, spaced out or joined with `+`, as `owner_codes.relayed` flattens it
- is refused, whatever the tool is called. Nothing honest needs to: the owner reads the code in
the assistant's message and types it. What this cannot see, said rather than implied: a code
encoded - base64, reversed, computed - which is deliberate concealment.
"""
import json
import os
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_shell      # noqa: E402  - a tool call, by shape
import owner_codes       # noqa: E402  - what a code looks like, and every code issued
import request_ledger    # noqa: E402  - the transcript's rows, None when they cannot be read

LF = chr(10)


def verdict(payload):
    """(exit code, message) for one call."""
    if not isinstance(payload, dict) or not bundle_shell.is_tool_call(payload):
        return 0, ""
    found = owner_codes.relayed(payload, request_ledger.read_rows(payload.get("transcript_path")))
    if not found:
        return 0, ""
    return 2, (bundle_shell.policy(__file__) + LF
               + "This call is refused: %s. A code approves a call only when the OWNER types it, "
                 "and no tool call is the owner typing - not a scheduled message, a routine, a "
                 "message to another session or agent, a file, or a row written into a "
                 "transcript.\n\nTell the owner the code in your message and stop. If the owner "
                 "does not type it, the answer is no.\n" % found)


def main():
    try:
        payload = json.loads(bundle_shell.payload_text())
    except (ValueError, IOError):
        return 0
    code, said = verdict(payload)
    if code:
        sys.stderr.write(said + LF)
    return code


if __name__ == "__main__":
    bundle_shell.speak_utf8()
    sys.exit(main())
