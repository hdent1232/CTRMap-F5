#!/usr/bin/env python3
"""A HOOK COMMAND FINDS ITS HOOK, OR SAYS WHERE IT LOOKED - it never points at one folder blind.

WHY, measured 2026-10-09 on the owner's PC. Every hook command was

    python "$CLAUDE_PROJECT_DIR/.claude/bundle-hooks/dispatch.py"

and `CLAUDE_PROJECT_DIR` is the folder a session STARTED in - the harness's documented meaning,
not a bug. A session opened in the folder above this repository ran that folder's untracked copy
of the hooks, which `hooks_wired` refused (F187), blocking every write. The running session was
then moved into the repository: the harness loaded the repository's settings, the variable kept
the folder above, every hook path named a file that was not there, Python exited 2 - which the
harness reads as a refusal - and every tool call was refused, the Read that would have shown why
included. The session wedged and was abandoned.

WHAT A COMMAND IS NOW, one definition, written by `command(rel)` and asked of every settings file:

    python -B -c "<RESOLVER>" <rel>

THE CHILD IS HANDED ITS STANDARD HANDLES BY NAME. Measured on the launcher's second draft: on
Windows a child started with no console window and no handles named got no stdin at all, and the
hook waited for the payload forever - a harness waits up to the hook's timeout, every call.

`RESOLVER` runs the hook at `rel` under the folder the session started in when it is there, and
otherwise under the repository of the session's CURRENT folder - the one a moved session's
settings came from. When neither holds it, nothing can guard the call, so it refuses everything
but a read and SAYS which folders it looked in and what to do. A launcher that fails in any other
way refuses too: Python's exit 1 is not a refusal to the harness, so an uncaught error here would
turn every guard off in silence.

    python -B tools/hook_command.py .claude/bundle-hooks/dispatch.py   print the command for a hook
"""
import json
import os
import re
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

LF = chr(10)

#: The launcher, run by `python -c`. It is spelled inside a double-quoted shell word in a JSON
#: string, so it holds no double quote, dollar, backtick or backslash (`SHELL_UNSAFE`), and every
#: Python string in it is single-quoted with no apostrophe inside.
RESOLVER = LF.join([
    "import json,os,subprocess,sys",
    "NW=getattr(subprocess,'CREATE_NO_WINDOW',0)",
    "def top():",
    "    try:",
    "        r=subprocess.run(['git','rev-parse','--show-toplevel'],capture_output=True,text=True,"
    "timeout=30,creationflags=NW)",
    "        return r.stdout.strip() if r.returncode==0 else ''",
    "    except Exception:",
    "        return ''",
    "def main(rel):",
    "    tried=[]",
    "    for d in (os.environ.get('CLAUDE_PROJECT_DIR') or '',top()):",
    "        if d:",
    "            p=os.path.join(d,*rel.split('/'))",
    "            tried.append(p)",
    "            if os.path.isfile(p):",
    "                return subprocess.run([sys.executable,'-B',p],stdin=sys.stdin,stdout=sys.stdout,"
    "stderr=sys.stderr,creationflags=NW).returncode",
    "    try:",
    "        payload=json.loads(sys.stdin.read())",
    "    except Exception:",
    "        payload={}",
    "    payload=payload if isinstance(payload,dict) else {}",
    "    event=payload.get('hook_event_name') or ''",
    "    if event=='PreToolUse' and payload.get('tool_name') in ('Read','Glob','Grep'):",
    "        return 0",
    "    if payload.get('stop_hook_active'):",
    "        return 0",
    "    sys.stderr.write('BLOCKED: the hook '+rel+' is not under the folder this session started"
    " in nor under the repository of its current folder, so none of its guards can run and nothing"
    " but a read goes through. Looked at: '+(', '.join(tried) or 'nothing - no start folder and no"
    " repository')+'. CLAUDE_PROJECT_DIR is the folder a session STARTED in, and a running session"
    " moved into another folder keeps it. Start a new session in the folder whose"
    " .claude/settings.json wires this hook.'+chr(10))",
    "    return 2",
    "try:",
    "    sys.exit(main(sys.argv[1]))",
    "except Exception as e:",
    "    sys.stderr.write('BLOCKED: the hook launcher could not run (%s: %s) - a launcher that"
    " cannot answer is not one that answered yes'%(type(e).__name__,e)+chr(10))",
    "    sys.exit(2)",
    ""])

#: What a double-quoted shell word would change, in bash and in PowerShell alike.
SHELL_UNSAFE = re.compile(r"[\"$`\\]")

#: How the launcher is spelled in front of the hook's path.
PREFIX = 'python -B -c "%s" ' % RESOLVER


def command(rel):
    """The one command that runs the hook at the project-relative `rel`."""
    return PREFIX + rel


def hook_of(text):
    """The project-relative hook a command runs, when it is `command(rel)`; None otherwise.

    INSIDE THE PROJECT: a `..` segment would point the launcher at a copy outside it - the
    folder above this repository held exactly such a copy of the hooks until 2026-10-09."""
    if not isinstance(text, str) or not text.startswith(PREFIX):
        return None
    rel = text[len(PREFIX):]
    if not re.fullmatch(r"[\w.-]+(?:/[\w.-]+)*\.py", rel):
        return None
    return None if any(part in (".", "..") for part in rel.split("/")) else rel


def commands_in(settings):
    """[(event, command)] for every hook command a parsed settings object wires."""
    out = []
    hooks = settings.get("hooks") if isinstance(settings, dict) else None
    for event, groups in (hooks.items() if isinstance(hooks, dict) else ()):
        for group in groups if isinstance(groups, list) else ():
            for hook in (group.get("hooks") or []) if isinstance(group, dict) else ():
                if isinstance(hook, dict):
                    out.append((event, hook.get("command")))
    return out


# ----------------------------------------------------------------------------- at the write

#: ASKED AT THE WRITE of a settings file: a hook command that is not the launcher - one anchored
#: on a single folder, which a moved session points at nothing - is refused by the write that
#: adds it.
AT_WRITE = "findings"
AT_SCOPE = r"^\.claude/settings(\.local)?\.json$"


def findings(rel, text):
    """Every hook command in this settings text that is not `command(<its hook>)`."""
    try:
        settings = json.loads(text or "{}")
    except ValueError as exc:
        return ["%s is not JSON (%s), so which hooks it runs is UNKNOWN" % (rel, exc)]
    out = []
    for event, said in commands_in(settings):
        if hook_of(said) is None:
            out.append("%s runs %r under %s - not the launcher, so a session that started in "
                       "another folder runs a missing or a different copy. Write it as "
                       "tools/hook_command.command(<hook path>)" % (rel, str(said)[:120], event))
    return out


def main(argv):
    #a word this does not read refuses the run, before anything runs (cli_words, 2026-10-10)
    import cli_words
    unread_words = cli_words.refused(argv[1:], most=1)
    if unread_words:
        print(unread_words)
        return 2
    if SHELL_UNSAFE.search(RESOLVER):
        sys.stderr.write("REFUSING: the launcher holds a character a double-quoted shell word "
                         "would change%s" % LF)
        return 2
    if len(argv) != 2:
        sys.stderr.write("usage: python -B tools/hook_command.py <hook path, project-relative>%s"
                         % LF)
        return 2
    sys.stdout.write(json.dumps(command(argv[1])) + LF)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
