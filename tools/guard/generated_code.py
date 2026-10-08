#!/usr/bin/env python3
"""NEVER HAND-EDIT A NETBEANS initComponents BLOCK - asked of what a write CHANGES, by any tool.

THE OWNER'S STANDING INSTRUCTION, and NetBeans owns the region: the `.form` file is the source of
truth and the IDE regenerates the block from it, so a hand-edit is overwritten the next time
anybody opens the designer, and an edit that disturbs the fold fence can make the form unopenable.

WHY A RULE AND NOT ONLY THE HOOK (F194). `.claude/hooks/guard_generated_code.py` judged a Write or
an Edit by the text it carried, and had no shell half though its docstring promised one: `sed -i`
over a form, a script handed the file and an `edits[]` batch all passed, measured. This is
declared AT_WRITE_CHANGE, so `bundle_rules.ask_changes` asks it with the file as it was and as the
write leaves it - for an Edit or a Write before it lands, and for whatever a command changed,
whatever the command was. How the bytes got there no longer matters. The region parser lives here
once; the hook loads it from this file.

WHAT IT REFUSES: a change to an existing generated block - its text, or the block gone. Line
endings are not content. Writing a NEW file, which carries no block anybody owned, and editing
outside the blocks, are not refused.

TO EDIT IT ANYWAY: the owner sets CTRMAP_ALLOW_GENERATED_EDIT=1, the hook's own switch.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root        # noqa: E402  - the project root, anchored: never counted (F216)
sys.path.insert(0, os.path.join(_tree_root.require(__file__), ".claude", "hooks"))
import hook_env  # noqa: E402  - the one prefix every owner switch carries

BYPASS = hook_env.name("ALLOW_GENERATED_EDIT")

#: How NetBeans fences the regions it owns. `//GEN-BEGIN:<name>` ... `//GEN-END:<name>` are the
#: markers the IDE itself keys on. A form has MORE THAN ONE - initComponents and the variables
#: declaration at least - so this reads them all: reading only the first left the variables
#: block unguarded, which is the block a hand-edit is most likely to touch.
_BEGIN = re.compile(r"//GEN-BEGIN:(\w+)")
_END = re.compile(r"//GEN-END:(\w+)")

AT_SCOPE = r"^src/.*\.java$"
AT_WRITE_CHANGE = "generated_change_problems"


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


def blocks(body):
    """{name: text} of every generated region, line endings normalised."""
    body = (body or "").replace("\r\n", "\n")
    return {name: body[start:end] for start, end, name in generated_regions(body)}


def generated_change_problems(rel, before, after, root=None):
    """[findings] for a write leaving `rel` reading `after` where it read `before`."""
    if os.environ.get(BYPASS) == "1" or not before:
        return []
    was = blocks(before)
    if not was:
        return []
    now = blocks(after)
    return ["%s: the NetBeans %s block %s - NEVER HAND-EDIT A NETBEANS initComponents BLOCK. Change the"
            " .form file, or put the code outside the block (the owner lifts this with %s=1)"
            % (rel, name, "would be gone" if name not in now else "would change", BYPASS)
            for name, text in sorted(was.items()) if now.get(name) != text]
