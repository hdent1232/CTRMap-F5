#!/usr/bin/env python3
"""NOTHING THIS SESSION STARTS TAKES THE OWNER'S FOREGROUND.

PAID FOR 2026-10-11, twice in one evening: programs the session started - the editor's test suites, a
window a test opened to prove it could see one, a console a detached launch made - came up on the owner's
desktop while they worked in other programs. Seen or not, a window that comes up takes the keyboard: a
video they were watching paused, a screenshot they were working on was deleted, a window of theirs was
closed and its edits lost.

tools/guard/hidden_desktop.py runs a program on a second desktop of the same session, where a window
cannot take the owner's keyboard; build.ps1 and test.ps1 move themselves there. This refuses, before it
runs, a command that would start one on the owner's desktop instead:

  * the editor's Java started directly - java or javaw, any path, quoted or not, naming a ctrmap class
    or the editor's jar - rather than through hidden_desktop.py
  * a process created through WMI (Win32_Process Create), which is what made the detached console
  * a program launched with `start` or Start-Process, which opens a new window of its own

The owner's way past, for a session where they want windows: CTRMAP_FOREGROUND_OK=1.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import hook_env                                   # noqa: E402
import shellin                                    # noqa: E402  (path set above)

HOOK = "guard_foreground.py"
BYPASS = hook_env.name("FOREGROUND_OK")

#: The program a command runs, read as a shell reads it: past PowerShell's call operator, any NAME=value
#: assignments and the wrappers that run what follows them (exec, env, nohup, time, command, nice).
#:
#: READ, NOT MATCHED (2026-10-14). The program was the literal word java at the start of a command, so
#: `J=java; "$J" -cp build/classes ctrmap.tests.HandedGameTest` - which this session ran, several times - started
#: the editor's suites on the owner's desktop, and so would `FOO=1 java ...`. A program named by a variable, a
#: command substitution or backticks cannot be read here, and a program that cannot be read is never taken
#: for a harmless one: when the command runs the editor's classes, it is refused.
_PROGRAM = re.compile(r"""^\s*(?:&\s*)?(?:(?:\w+=(?:"[^"]*"|'[^']*'|\S*)|exec|env|nohup|time|command|nice)\s+)*"""
                      r"""(?P<p>\$\([^)]*\)|`[^`]*`|"[^"]*"|'[^']*'|\S+)""")
_JAVA_NAME = re.compile(r"""(?:^|[\\/])javaw?(?:\.exe)?$""", re.I)
_EDITOR = re.compile(r"\bctrmap\.[A-Za-z_]|CTRMap[^\s\"']*\.jar", re.I)


def program(command):
    """(name, readable) of the program `command` runs: its name as written, unquoted, and whether that is a name
    at all rather than a variable, a command substitution or backticks the shell fills in later."""
    m = _PROGRAM.match(command)
    if not m:
        return "", True
    p = m.group("p").strip("\"'")
    return p, not ("$" in p or "`" in p)
#: A process made through WMI.
_WMI = re.compile(r"Win32_Process\b[^\n]*\bCreate\b|\bCreate\b[^\n]*Win32_Process\b", re.I)
#: Where a COMMAND begins: a line's start, after a separator, a bracket or `=`, or at the head of a command
#: line handed to another shell (`cmd /c`, `/k`, `-Command "...`, `bash -c '...`).
#:
#: ASKED AT COMMAND POSITION, NOT ANYWHERE (2026-10-12). `start` is an English word, and the pattern matched
#: it after any space: it refused a ledger note quoting "4 of 4 scripts that start the JDK", and so it would
#: any commit message saying "start" after a `cd ... &&`. A command beginning with git or python was let
#: through instead, which meant `python x.py & start notepad` - a window - went through unasked.
_AT_COMMAND = r"""(?:^|[;&|({=]|(?:^|\s)/[ck]\s+["']?|\s-c(?:ommand)?\s+["']?)\s*"""
#: A program started in a window of its own.
_START = re.compile(_AT_COMMAND + r"""(?:(?:Start-Process|saps)\b|start\s+(?:""|/[a-z]|\S))""", re.I | re.M)


def verdict(command):
    """The reason `command` would start something on the owner's desktop, or None."""
    if "hidden_desktop.py" in command:
        return None
    name, readable = program(command)
    if _EDITOR.search(command) and not readable:
        return ("it runs the editor's classes through a program named by %s, which cannot be read here - and a "
                "program that cannot be read is not a harmless one" % name)
    if _EDITOR.search(command) and _JAVA_NAME.search(name):
        return "it starts the editor's Java on the owner's desktop"
    if _WMI.search(command):
        return "it makes a process through WMI, which opens on the owner's desktop"
    if _START.search(command):
        return "it starts a program in a window of its own"
    return None


def refusal(why, command):
    return (
        "BLOCKED BY PROJECT POLICY (.claude/hooks/%s).\n"
        "Nothing this session starts may take the owner's foreground, and %s:\n    %s\n\n"
        "Seen or not, a window that comes up takes the keyboard: on 2026-10-11 a video the owner was\n"
        "watching paused, a screenshot they were working on was deleted, and a window of theirs was\n"
        "closed with its edits lost - all from programs this session started.\n\n"
        "Start it on the unattended desktop instead, where no window reaches them:\n"
        "    python -B tools/guard/hidden_desktop.py --stream -- <command...>\n"
        "build.ps1 and test.ps1 move themselves there already.\n" % (HOOK, why, command.strip()[:160]))


def decide(payload):
    if os.environ.get(BYPASS) == "1":
        return False, None
    blind = shellin.unreadable(payload)
    if blind:
        return True, shellin.blind_refusal(HOOK, "; ".join(blind), BYPASS)
    for command in shellin.commands(payload):
        why = verdict(command)
        if why:
            return True, refusal(why, command)
    return False, None


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
