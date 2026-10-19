#!/usr/bin/env python3
"""WHAT GOVERNS THE AGENT IS WRITTEN ONLY WITH THE OWNER'S YES - asked of the write, by shape.

MEASURED 2026-10-07, against this bundle's own dispatcher in a self-wired clone: with every guard
asked, an Edit adding `"disableAllHooks": true` to `.claude/settings.json`, a Write of the same
into `~/.claude/settings.json`, and a command appending a row to a session transcript under
`~/.claude/projects` were each let through. The first two switch every guard off from the next
session; the third forges what the owner said, which is where `owner_codes.given` reads a yes.

THE OWNER'S FILES, by place:

    the Claude folder       `CLAUDE_CONFIG_DIR`, or `~/.claude`: user settings, user CLAUDE.md,
                            hooks, agents, skills, and every session's transcript
    the managed settings    `MANAGED` - what an administrator sets for every session
    this session's record   the payload's `transcript_path`, wherever the harness keeps it
    the project's wiring    `.claude/settings.json`, `.claude/settings.local.json` - hooks, env,
                            `disableAllHooks`, permissions
    which guards run        every decision in `.claude/bundle-install.json` - a file declared not
                            installed, kept diverged or placed elsewhere, the tests left to a
                            runner of the project's, and `_review`, the owner's standing yes to
                            the review the request ledger demands. A guard declared `not_installed`
                            with an 80-character reason is a guard the install check stops asking
                            about: the cheapest way past any of them. Only where the bundle lives
                            (`_bundle`) and the file's own note (`_why`) are not decisions.

A Write or Edit into one is refused with a one-time code, and goes ahead when the OWNER types that
code back (`owner_codes.given`) - one yes, one write, the identical call. A change to the declarations
that only brings the project back to the bundle as it ships, or switches a part of it on, is not
refused: installing the bundle needs no code (`loosening`, the owner, 2026-10-18). Two places inside the
Claude folder are where the harness itself tells an agent to write, and are not the owner's
(`AGENT_WRITES`): plans, and a project's memory.

A COMMAND that names any of these is `guard_command_rules`'s: they are part of its guard stack, and
a command writing it is refused unless it only reads it.
"""
import fnmatch
import json
import os
import re
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_rules      # noqa: E402  - where the project is
import bundle_shell      # noqa: E402  - a write, found by shape
import owner_codes       # noqa: E402  - the owner's typed yes
import request_ledger    # noqa: E402  - the transcript's rows

LF = chr(10)

#: What an administrator sets for every session, per platform - the owner's, never the agent's.
MANAGED = ("/etc/claude-code", "/Library/Application Support/ClaudeCode",
           "C:/ProgramData/ClaudeCode", "C:/Program Files/ClaudeCode")

#: Inside the Claude folder, where the harness asks the agent to write: a plan, a project memory.
AGENT_WRITES = ("plans/*", "projects/*/memory/*")

#: The project's files that wire the hooks, as project paths.
WIRING = (".claude/settings.json", ".claude/settings.local.json")

#: The install declarations, and the keys in them that decide nothing about which guard runs.
DECLARATIONS = ".claude/bundle-install.json"
NOT_DECISIONS = ("_bundle", "_why")
#: Declarations a project ADDS to switch a part of the bundle ON - absent, that part is off - so declaring one where
#: there was none adds a guard and takes nothing away. `_review` is not among them: it spends the owner's usage.
SWITCHES_ON = ("_asked",)

#: Adapted per project: see ADAPT.md.
ADAPT = ("MANAGED", "AGENT_WRITES")


def config_dir():
    """The Claude folder this session reads - `CLAUDE_CONFIG_DIR` when set, else `~/.claude`."""
    return os.environ.get("CLAUDE_CONFIG_DIR") or os.path.join(os.path.expanduser("~"), ".claude")


def main_config():
    """The harness's own state file - MCP servers, trust, per-project settings - beside the Claude
    folder: `$CLAUDE_CONFIG_DIR/.claude.json` when that is set, else `~/.claude.json`."""
    if os.environ.get("CLAUDE_CONFIG_DIR"):
        return os.path.join(os.environ["CLAUDE_CONFIG_DIR"], ".claude.json")
    return os.path.join(os.path.expanduser("~"), ".claude.json")


def _real(path):
    return os.path.normcase(os.path.realpath(os.path.abspath(os.path.expanduser(path))))


def owner_folders():
    """Every folder that is the owner's - the Claude folder and the managed settings - as absolute
    paths, each as spelled and as its links resolve, so a folder reached through a link is still
    the owner's. A transcript the harness keeps elsewhere is the owner's as a FILE (`owners`): its
    folder could be any folder."""
    out = set()
    for folder in [config_dir()] + [m for m in MANAGED if _native(m)]:
        given = os.path.abspath(os.path.expanduser(folder))
        out.update((given, os.path.realpath(given)))
    return sorted(out)


def _native(path):
    """Is this an absolute path on THIS machine's kind of filesystem? `C:/ProgramData` read on
    Linux is a relative path, and joined to the working folder it named a folder of the project."""
    drive = bool(re.match(r"^[A-Za-z]:[/\\]", path))
    return drive if os.name == "nt" else (path.startswith("/") and not drive)


def _inside(path, folder):
    return path == folder or path.startswith(folder.rstrip(os.sep) + os.sep)


def owners(path, transcript=None):
    """Is this filesystem path the owner's - inside the Claude folder or the managed settings, or
    the session's own record - and not where the harness asks the agent to write?"""
    real = _real(path)
    if (transcript and real == _real(str(transcript))) or real == _real(main_config()):
        return True
    config = _real(config_dir())
    if _inside(real, config):
        rel = bundle_shell.relative(real, config)
        return not any(fnmatch.fnmatch(rel, pattern) for pattern in AGENT_WRITES)
    return any(_inside(real, _real(folder)) for folder in owner_folders())


def _yes_of(text):
    """Every decision a declarations text makes about which guard runs - or "unreadable"."""
    try:
        held = json.loads(text) if text and text.strip() else {}
    except ValueError:
        return "unreadable"
    if not isinstance(held, dict):
        return "unreadable"
    return {k: v for k, v in held.items() if k not in NOT_DECISIONS}


def _decided(before, after):
    """The declaration keys whose decision this write changes."""
    was, now = _yes_of(before), _yes_of(after)
    if not isinstance(was, dict) or not isinstance(now, dict):
        return ["the whole file"]
    return sorted(k for k in set(was) | set(now) if was.get(k) != now.get(k))


def loosening(before, after):
    """The declaration keys this write changes in a way that can switch a guard off - or [] when every change only
    brings the project back to the bundle as it ships, or switches a part of it on.

    INSTALLING THE BUNDLE NEEDS NO CODE (the owner, 2026-10-18: "you dont need to ask me for a code to install the
    verification bundle"). An install takes the bundle's own guards: it removes a file's declaration once the project's
    copy is the bundle's again, rewords a declaration's reason, or declares a part the bundle ships switched off until a
    project asks for it. None of that switches a guard off. What still needs the owner's code is a write that DOES:
    declaring a file not installed, kept different or placed elsewhere, moving it, changing what kind of declaration it
    is, or changing or removing a switch - which is the one door this guard keeps shut."""
    was, now = _yes_of(before), _yes_of(after)
    if not isinstance(was, dict) or not isinstance(now, dict):
        return ["the whole file"]
    out = []
    for key in _decided(before, after):
        old, new = was.get(key), now.get(key)
        if key.startswith("_"):
            if not (key in SWITCHES_ON and key not in was):
                out.append(key)
        elif key not in now:
            continue                                   # the project's copy is the bundle's again
        elif key not in was:
            out.append(key)                            # a file declared not installed, different or elsewhere
        elif not (isinstance(old, dict) and isinstance(new, dict) and set(old) == set(new)
                  and old.get("at") == new.get("at")):
            out.append(key)                            # what kind of declaration, or where the file lives
    return out


def why(payload, root):
    """Why this call writes the owner's files, or ''."""
    if not bundle_shell.writes(payload):
        return ""
    transcript = payload.get("transcript_path")
    for target in bundle_shell.paths(payload):
        path = target if os.path.isabs(str(target)) else os.path.join(root or os.getcwd(), target)
        if owners(path, transcript):
            return "it writes %s, which is the owner's" % target
        rel = bundle_rules.rel_of(root, path) if root else ""
        if rel in WIRING:
            return "it writes %s, which wires every hook in this project" % rel
    if root:
        import guard_write_rules
        change = guard_write_rules.proposed(payload, root)
        if change and change[0] == DECLARATIONS and loosening(change[1], change[2]):
            return ("it changes %s in %s - which guards run here, and the reviewer the request "
                    "ledger demands, are the owner's decisions"
                    % (", ".join(loosening(change[1], change[2]))[:200], DECLARATIONS))
    return ""


def verdict(payload, root):
    """(exit code, message) for one call."""
    found = why(payload, root)
    if not found:
        return 0, ""
    rows = request_ledger.read_rows(payload.get("transcript_path"))
    if rows is not None and owner_codes.given(payload, rows):
        return 0, ""
    code = owner_codes.issue("CONFIG")
    return 2, (bundle_shell.policy(__file__) + LF
               + "This write is refused: %s.\n\n"
                 "What governs the agent - the hooks' wiring, the owner's settings, the owner's "
                 "record of what they said - is changed by the owner. Measured 2026-10-07: with "
                 "every guard wired, one Edit adding `disableAllHooks` would have switched all of "
                 "them off from the next session.\n\n"
                 "Tell the owner exactly what this write changes and why, and this code. The owner "
                 "approves THIS write, once, by typing:\n\n    %s\n\n"
                 "Then send the identical call again. Nothing you write can approve it.\n"
               % (found, code))


def main():
    try:
        payload = json.loads(bundle_shell.payload_text())
    except (ValueError, IOError):
        return 0
    if not isinstance(payload, dict) or not bundle_shell.is_tool_call(payload):
        return 0
    code, said = verdict(payload, bundle_rules.repo_root(__file__))
    if code:
        sys.stderr.write(said + LF)
    return code


if __name__ == "__main__":
    bundle_shell.speak_utf8()
    sys.exit(main())
