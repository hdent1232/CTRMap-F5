#!/usr/bin/env python3
"""A RULE THAT CAN ONLY BE ASKED OF THE TREE ON DISK IS ASKED THE MOMENT THE TREE CHANGES.

WIRED UNDER `PostToolUse`, not `PreToolUse`, and that is the whole of what is different about it.
Most rules can be asked of a write before it lands: the write carries the text. A few cannot -
their verdict is a COMMAND's exit code over the files as they are on disk, and before a write
lands the disk is not the tree it would leave. A ledger item closed "when this command exits 0"
is the example this was built for: an edit anywhere can re-open it, and nothing short of running
the command after the edit can say so.

So this runs every `AT_AFTER` rule as soon as a call that could have changed the tree has run,
and hands anything NEW straight back to the session that made the change - `PostToolUse` exit 2
puts the message in front of the model before its next step. It does not undo the write; it is
the earliest point at which the question has an answer, and it is not the commit hours later.

NEW, NOT STANDING. A finding that was already there before this call is not this call's doing,
and repeating it after every command is how a guard gets tuned out. The last answer is kept
beside the tree state it was taken against, and a call that left the tree exactly as it was is
not asked again.

AND EVERY WRITE NO HOOK COULD READ FIRST. A command's writes - a copy, a script, a redirect, a
restore - carry no text for `guard_write_rules.py` to ask, so this is the earliest moment they
exist. `bundle_rules.judge_unread` judges each by the same rules and growth test as a Write,
against the tree as it was last judged; a change they refuse is HELD, reported here at once, and
`guard_held_writes.py` refuses every act but its repair until it is gone. The files a Write or
Edit call wrote are named to it, because the write hook already asked those before they landed.
"""
import hashlib
import io
import json
import os
import subprocess
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_rules      # noqa: E402  - the one implementation of asking a rule
import bundle_shell      # noqa: E402  - the call, found by shape

LF = chr(10)
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

#: Where the last answer is kept, inside the rules cache that ignores itself.
STATE = "after-state.json"

#: Adapted per project: see ADAPT.md.
ADAPT = ("STATE",)


def tree_state(root):
    """A digest of HEAD, the status, and the size and time of every changed path - or None."""
    try:
        head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, capture_output=True,
                              text=True, timeout=30, creationflags=NO_WINDOW).stdout.strip()
        status = subprocess.run(["git", "status", "--porcelain", "-z"], cwd=root,
                                capture_output=True, timeout=30, creationflags=NO_WINDOW).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    digest = hashlib.sha256(head.encode("utf-8") + status)
    for entry in status.split(b"\0"):
        path = entry[3:].decode("utf-8", "replace") if len(entry) > 3 else ""
        try:
            stat = os.stat(os.path.join(root, path))
            digest.update(("%s|%d|%d" % (path, stat.st_size, stat.st_mtime_ns)).encode("utf-8"))
        except OSError:
            digest.update(path.encode("utf-8"))
    return digest.hexdigest()


def _held(root):
    try:
        with io.open(os.path.join(root, bundle_rules.CACHE, STATE), encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return {}


def _keep(root, state, found):
    folder = os.path.join(root, bundle_rules.CACHE)
    try:
        os.makedirs(folder, exist_ok=True)
        with io.open(os.path.join(folder, STATE), "w", encoding="utf-8", newline=LF) as handle:
            json.dump({"state": state, "found": found}, handle, sort_keys=True)
    except OSError:
        pass                                # a lost memo costs one repeat, never a missed finding


def new_findings(previous, found):
    """{rule: [findings not in the previous answer]}, only the rules with some."""
    out = {}
    for name, items in found.items():
        fresh = [f for f in items if f not in (previous.get(name) or [])]
        if fresh:
            out[name] = fresh
    return out


def written_by(payload, root):
    """The project files a Write or Edit call wrote: the write hook asked those before they
    landed, so they are recorded rather than judged a second time."""
    if not bundle_shell.writes(payload):
        return []
    return [bundle_rules.rel_of(root, t if os.path.isabs(t) else os.path.join(root, t))
            for t in bundle_shell.paths(payload)]


def after_findings(root):
    """The lines to hand back for `AT_AFTER` rules this call changed the answer of, or [].

    NOT WHILE A MUTANT IS ON DISK. A mutation run holds `bundle_rules.LOCK` while one module is
    an injected fault that exists for seconds, and a rule asked then answers about that mutant.
    Worse than the wrong answer is the memo `_keep` writes: a finding the mutant happened to
    produce is not NEW when the real tree produces it later, so it is never said. Measured on the
    project this came from: an item reported re-opened after every call while a proof run had a
    module mutated. Nothing is asked and nothing is kept; the memo still names the tree from
    before the lock, so the first call after it is asked as if the lock had never been there.

    AND NOT WHEN ONE CAME AND WENT WHILE THE RULES WERE ASKED. Reading the lock once, at the start,
    let a plant laid during the asking be kept as the memo. `lock_state` is read before and after
    and must not move - its count of releases is what sees a plant that was gone by the end.
    """
    start = bundle_rules.lock_state(root)
    if start[0] or bundle_shell.judge_under_mutation(root, bundle_rules.LOCK):
        return []
    found_rules = [r for r in bundle_rules.rules(root) if r.kind == "AT_AFTER"]
    if not found_rules:
        return []
    state = tree_state(root)
    held = _held(root)
    if state is not None and held.get("state") == state:
        return []
    found = bundle_rules.ask_after(root, found_rules)
    if bundle_rules.lock_state(root) != start:
        return []                          # nothing said, nothing kept: asked again next call
    fresh = new_findings(held.get("found") or {}, found)
    _keep(root, state, found)
    if not fresh:
        return []
    lines = ["THE CALL THAT JUST RAN CHANGED SOMETHING A RULE WATCHES:", ""]
    for name, items in sorted(fresh.items()):
        lines.append("  %s" % name)
        lines += ["      %s" % item for item in items[:6]]
    lines += ["", "  Asked the moment the tree changed, because this rule's answer is a command run",
              "  over the files on disk and nothing earlier could ask it. Undo or repair it now,",
              "  before building on it."]
    return lines


def verdict(payload, root):
    """(exit code, message) the moment after one call."""
    if not bundle_shell.is_tool_call(payload) or bundle_shell.reads_only(payload):
        return 0, ""
    lines = []
    # THE JUDGE UNDER MUTATION IS ASKED NOTHING - neither a verdict nor a record of what this call
    # wrote. Recorded, a write would read as judged at the write and never be judged again.
    mutant = bundle_shell.judge_under_mutation(root, bundle_rules.LOCK)
    if mutant:
        return 2, ("NOT JUDGED YET: %s - the code that judges - is under a mutation run's lock, so "
                   "what this call changed is judged the first time it is back. Nothing is held "
                   "and nothing is recorded as judged; it is not clean, it is unknown." % mutant)
    judged = bundle_rules.judge_unread(root, written_by(payload, root))
    if judged["fresh"]:
        lines += [bundle_rules.held_refusal(judged), ""]
    if judged.get("unknown") and judged.get("pending"):
        lines += [bundle_rules.unknown_note(judged), ""]     # said, never read as clean
    lines += after_findings(root)
    return (2, LF.join(lines).rstrip()) if lines else (0, "")


def main():
    try:
        payload = json.loads(bundle_shell.payload_text())   # UTF-8, not the locale's code page
    except (ValueError, IOError):
        return 0
    if not isinstance(payload, dict):
        return 0
    root = bundle_rules.repo_root(__file__)
    if root is None:
        return 0
    code, said = verdict(payload, root)
    if code:
        sys.stderr.write(said + LF)
    return code


if __name__ == "__main__":
    bundle_shell.speak_utf8()           # the one door out, as payload_text is the one in
    sys.exit(main())
