#!/usr/bin/env python3
"""NEVER HAND-EDIT A NETBEANS initComponents BLOCK. Refused at the edit.

WHY THIS EXISTS. It is the owner's standing instruction, and it is in `CLAUDE.md` inside the
body of GAME DATA IS READ-ONLY - where `rule_map.py` could not see it, because the map checks
a rule's HEADLINE and this is a clause underneath one that is enforced. It had nothing behind
it at all.

WHAT IT PROTECTS. NetBeans owns that region. The `.form` file is the source of truth and the
IDE regenerates the block from it, so a hand-edit is overwritten the next time anybody opens
the form in the designer - silently, and usually much later than the edit. Worse, the block is
fenced by a fold marker the IDE parses; an edit that disturbs the fence can make the form
unopenable, which turns a UI tweak into a file only a text editor can repair.

WHAT IT REFUSES: a Write or an Edit whose target text falls inside the generated region of a
`.java` file that has one, and a shell command that rewrites such a file in place.

WHAT IT DOES NOT REFUSE: editing the rest of the file, which is where hand-written code
belongs, or writing the `.form` file, which is the supported way to change the block.

TO EDIT IT ANYWAY: the owner sets CTRMAP_ALLOW_GENERATED_EDIT=1.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shellin                                    # noqa: E402  (path set above)

HOOK = "guard_generated_code.py"
BYPASS = "CTRMAP_ALLOW_GENERATED_EDIT"

#: How NetBeans fences the regions it owns. `//GEN-BEGIN:<name>` ... `//GEN-END:<name>` are
#: the markers the IDE itself keys on; the editor-fold comment beside them is for the reader.
#: A form has MORE THAN ONE - initComponents and the variables declaration at least - so this
#: reads them all. Reading only the first left the variables block unguarded, which is the
#: block a hand-edit is most likely to touch.
_BEGIN = re.compile(r"//GEN-BEGIN:(\w+)")
_END = re.compile(r"//GEN-END:(\w+)")


def generated_regions(body):
    """(start, end, name) for every region NetBeans owns in this file."""
    out = []
    for begin in _BEGIN.finditer(body):
        name = begin.group(1)
        end = None
        for candidate in _END.finditer(body, begin.end()):
            if candidate.group(1) == name:
                end = candidate
                break
        #: AN UNCLOSED REGION RUNS TO THE END OF THE FILE. Treating it as absent would let an
        #: edit into exactly the file whose fences are already damaged.
        out.append((begin.start(), end.end() if end else len(body), name))
    return out


def _targets(payload):
    """(path, text being placed) for each file this call edits."""
    out = []
    tool_input = (payload or {}).get("tool_input")
    if not isinstance(tool_input, dict):
        return out
    path = tool_input.get("file_path")
    if not isinstance(path, str) or not path.endswith(".java"):
        return out
    #: an Edit says which text it is replacing; a Write replaces everything
    for key in ("old_string", "content", "new_string"):
        value = tool_input.get(key)
        if isinstance(value, str) and value:
            out.append((path, key, value))
    return out


def decide(payload):
    if os.environ.get(BYPASS) == "1":
        return False, None
    for path, key, text in _targets(payload):
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                body = handle.read()
        except OSError:
            #: a file that is not there yet cannot have a generated block in it
            continue
        regions = generated_regions(body)
        if not regions:
            continue
        if key == "content":
            #: A WHOLE-FILE WRITE over a file with generated regions rewrites them by
            #: definition, unless the new text carries every one through unchanged.
            was = [body[s:e] for s, e, _ in regions]
            now = [text[s:e] for s, e, _ in generated_regions(text)]
            if was == now:
                continue
            return True, refusal(path, "a whole-file write would rewrite it")
        at = body.find(text)
        if at < 0:
            continue
        for start, end, name in regions:
            if at < end and at + len(text) > start:
                return True, refusal(
                    path, "the text it replaces is inside the " + name + " block")
    return False, None


def refusal(path, why):
    return (
        "BLOCKED BY PROJECT POLICY (.claude/hooks/%s).\n"
        "This edits the NetBeans generated block of %s (%s).\n\n"
        "NEVER HAND-EDIT A NETBEANS initComponents BLOCK - the owner's standing instruction, "
        "and until today it had nothing behind it: it sits inside the body of a rule whose "
        "HEADLINE was enforced, so the rule map reported it green.\n\n"
        "NetBeans owns that region. The .form file is the source of truth and the IDE "
        "regenerates the block from it, so a hand-edit is overwritten the next time anybody "
        "opens the designer - silently, and much later than the edit. An edit that disturbs "
        "the fold fence can make the form unopenable.\n\n"
        "Change the .form file, or put the code outside the block where hand-written code "
        "belongs. The owner lifts this with %s=1.\n" % (HOOK, os.path.basename(path), why,
                                                        BYPASS))


def main():
    import json
    try:
        payload = json.load(sys.stdin)
    except Exception:
        sys.exit(0)                      # never break a turn on a parse failure
    deny, reason = decide(payload)
    if deny:
        json.dump({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }}, sys.stdout)
    sys.exit(0)


if __name__ == "__main__":
    main()
