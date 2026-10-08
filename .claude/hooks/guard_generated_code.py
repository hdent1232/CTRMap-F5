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
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hook_env                                   # noqa: E402
import shellin                                    # noqa: E402  (path set above)
import _tree_root        # noqa: E402  - the project root, anchored: never counted (F216)

HOOK = "guard_generated_code.py"
BYPASS = hook_env.name("ALLOW_GENERATED_EDIT")

def _rule():
    """tools/guard/generated_code.py - the ONE region parser, and the AT_WRITE_CHANGE rule that
    asks what ANY write changed, a command's included (F194). Loaded by its path from the
    repository this hook sits in, never by name."""
    import importlib.util
    path = os.path.join(_tree_root.require(__file__), "tools", "guard", "generated_code.py")
    spec = importlib.util.spec_from_file_location("ctrmap_generated_code", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


generated_regions = _rule().generated_regions


def _targets(payload):
    """(path, key, text being placed) for each file this call edits - an Edit's, a Write's, and
    every entry of an `edits[]` batch, which passed here unasked (F194). A relative path is the
    call's, resolved against its own `cwd`, not this hook's working directory."""
    out = []
    tool_input = (payload or {}).get("tool_input")
    if not isinstance(tool_input, dict):
        return out
    path = tool_input.get("file_path")
    if not isinstance(path, str) or not path.endswith(".java"):
        return out
    cwd = (payload or {}).get("cwd")
    if not os.path.isabs(path) and isinstance(cwd, str) and cwd:
        path = os.path.join(cwd, path)
    #: an Edit says which text it is replacing; a Write replaces everything
    for key in ("old_string", "content", "new_string"):
        value = tool_input.get(key)
        if isinstance(value, str) and value:
            out.append((path, key, value))
    for edit in tool_input.get("edits") or []:
        if isinstance(edit, dict):
            for key in ("old_string", "new_string"):
                value = edit.get(key)
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
