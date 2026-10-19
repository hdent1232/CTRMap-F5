# -*- coding: utf-8 -*-
"""THE SHELL TEXT A TOOL CALL WILL RUN, FOUND BY SHAPE RATHER THAN BY TOOL NAME.

WHY IT EXISTS, measured in the project this bundle was carried to.

Four of the hooks here opened with

    if str(payload.get("tool_name") or "") != "Bash":
        sys.exit(0)

and all of them were wired under `"matcher": "Bash"`. That session also offered a `PowerShell`
tool that runs shell commands. `PowerShell` is not `Bash`, so the matcher never selected them
and they would have exempted themselves if it had: the heredoc refusal - the one bought with
NUL bytes in a committed file - the sweep-pipe refusal, the idle-poll refusal and the
mutation-read refusal were **installed, wired, running, and off** for every command issued
through the other tool.

Nobody bypassed anything. The command was spelled differently. That is this bundle's own rule
about guards at call sites, paid for again by the guards themselves, and it is why section 1
now says A GUARD'S TRIGGER IS A CLASS, NOT A LIST OF NAMES.

So a hook no longer asks what the tool is called. It asks whether the tool input carries a
command, which is a property of the CLASS of shell-running tools and cannot be escaped by
adding a tool with a new name. A tool nobody has heard of, whose input has a `command` string,
is a shell here.

THE COMPLEMENTARY RULE APPLIES TO THE ANSWER. `commands()` returns what it found; `unreadable()`
says whether any part of the input could not be walked, and a caller that gets a non-empty
answer must REFUSE rather than treat it as carrying nothing. AN UNMEASURABLE QUANTITY IS NOT A
SMALL ONE. `dispatch.py` asks it ONCE for every guard - it shipped here with no caller at all,
a producer with no consumer inside the file written to stop them.

THE SAME QUESTION, ASKED OF THE OTHER SHAPES A CALL CAN TAKE. A command is one way to touch a
file. `Read`, `Grep` and `Edit` carry a path and no command, and a guard whose whole subject is
"do not READ this file" or "do not WRITE into that tree" was blind to them for exactly the
reason it was once blind to PowerShell: it asked for one shape and the call arrived in another.
`paths()` and `writes()` answer by shape too - which keys the input carries, never which tool
carries them.

AND ONE SPLITTER FOR "WHICH COMMANDS ARE ON THIS LINE". Five hooks each carried their own, and
three of them did not treat a NEWLINE as a separator - so `cd x`, a newline, and `sleep 300`
was one command beginning `cd`, and a multi-line PowerShell call walked past every rule that
anchors at the start of a command. One rule with five spellings is five rules.

A hook imports this by sitting beside it, exactly as it imports `bundle_env`: a script run as
`python .claude/hooks/guard_x.py` has its own directory first on `sys.path`.

AND WHERE THESE HOOKS LIVE IS READ FROM THIS FILE, never spelled. A project that already keeps a
guard stack of its own in `.claude/hooks` installs the bundle's beside it (`_hooks_at` in
`.claude/bundle-install.json`) - measured on CTRMap, whose dispatcher asks every `guard_*.py`
in that folder for a `decide()` the bundle's guards do not have. Twenty refusals there said
`BLOCKED BY PROJECT POLICY (.claude/hooks/guard_heredoc.py)` about a guard that was not in that
folder, while the project's OWN guard of that name was: a message that sends the reader to the
wrong file. `policy(__file__)` names the file that refused, wherever it was installed.
"""
import base64
import os
import re
import shlex
import shutil
import subprocess
import sys

import bounded           # a deadline ends the child's whole tree - beside this file, as bundle_env is

#: This folder, as a project path: `.claude/<the folder this file sits in>`.
FOLDER = ".claude/" + os.path.basename(os.path.dirname(os.path.abspath(__file__)))


def policy(path):
    """`BLOCKED BY PROJECT POLICY (<this folder>/<that file>).` for the guard at `path`."""
    return "BLOCKED BY PROJECT POLICY (%s/%s)." % (FOLDER, os.path.basename(path))


def relative(path, start):
    """`path` relative to `start`, `/`-separated - and an ANSWER for a path no relative path
    reaches: one on another drive, or a `start` of nothing.

    `os.path.relpath` raises ValueError across Windows drives. Measured 2026-10-08: the shell
    path readings read the `e:` in a command's `except Exception as e:` as drive E:, `rel_of`
    asked relpath about it, and the command guard exited 3 - which the dispatcher counts as a
    refusal - for an ordinary `python -c`. Every relpath in this folder goes through here, held by
    a test that refuses a bare one. Such a path is OUTSIDE the tree, so the answer starts `..`,
    the shape every caller already reads as outside; the absolute path after it keeps it unique.
    """
    try:
        return os.path.relpath(path, start).replace(os.sep, "/")
    except ValueError:
        return "../" + os.path.abspath(path).replace(os.sep, "/")


def judge_under_mutation(root, lock_name):
    """The file of THIS hooks folder a mutation run's lock names, or '' - read here, not by the
    judge.

    A JUDGE CANNOT VOUCH FOR ITSELF. Measured 2026-09-26 on the project this came from: a commit's
    gate re-drove the plants held in `bundle_rules.py` - the judge every hook imports - and with
    "the lock is never read" on disk, two sessions' hooks ran the planted judge and held the tree
    on a planted state. The judge reads the lock around every judgement, and that reading lived in
    the file being planted. This one lives in another: a lock names ONE file, so one of the two
    readings is always the real code. A hook asks this before it asks the judge anything, and a
    judge under mutation is asked nothing - its answer, either way, would be the plant's.
    """
    named = lock_names(root, lock_name)
    return named if named.startswith(FOLDER + "/") else ""


def lock_names(root, lock_name):
    """The repo-relative file a mutation run's lock names, or '' - its FIRST line, read here and
    not by the judge (see `judge_under_mutation`).

    THE FIRST LINE, BECAUSE EVERY WRITER WRITES MORE. `take_lock` writes the path and a nonce, and
    a project's own sweep the path, a pid and what it is; `guard_mutation_read` read the whole
    file as the path, which no command can ever contain - so under every real lock a `cat` of the
    mutated file itself went through (CTRMap F171).
    """
    import io
    try:
        with io.open(os.path.join(root, lock_name), encoding="utf-8") as handle:
            lines = handle.read().splitlines()
    except OSError:
        return ""
    named = (lines[0] if lines else "").strip().replace(chr(92), "/")
    if os.path.isabs(named):
        named = relative(named, root)
    # THE PLANT'S OWN TEST judges the planted code on purpose: its runner names the file in the
    # environment (`bundle_rules.PLANTED`, read here without importing the judge).
    import bundle_env
    if named and named == os.environ.get(bundle_env.name("PLANTED"), "").replace(chr(92), "/"):
        return ""
    return named

#: How deep to walk a tool input looking for command strings. A batching tool nests one input
#: inside another; nothing here nests deeper, and an unbounded walk on a self-referencing
#: structure would hang a PreToolUse hook, which fails the whole turn.
MAX_DEPTH = 6

#: Keys whose string value IS a shell command. Both are shapes, not tool names.
COMMAND_KEYS = ("command", "script")

#: Keys whose value is a PATH the call will read or write. `file_path` (Read, Edit, Write),
#: `path` (Grep, Glob), `notebook_path`, and the plural forms a batching tool uses.
PATH_KEYS = ("file_path", "path", "notebook_path", "paths", "file_paths")

#: Keys whose PRESENCE means the call writes content somewhere. A shape: `Write` carries
#: `content`, `Edit` carries `new_string`, a multi-edit carries `edits`, a notebook edit
#: carries `new_source`.
WRITE_KEYS = ("content", "new_string", "edits", "new_source")

LF = chr(10)


def payload_text(stream=None):
    """The hook payload, as the harness wrote it: UTF-8 bytes, decoded as UTF-8.

    NOT IN THE LOCALE'S CODE PAGE. `sys.stdin.read()` decodes a Windows pipe as cp1252, so every
    character outside ASCII reached every guard mangled - a path, a command, a prompt. Found when
    the request ledger's own review could not be launched: its prompt quoted the owner's text with
    two ellipses, each arrived as three code-page characters, the prompt was no longer the owed
    one, and the fan-out cap counted it as an ordinary launch. Every replay passed, because
    `json.dumps` escapes non-ASCII and hid it.
    A stream already holding text - the dispatcher's own hand-off to a guard - is read as it is.
    """
    stream = sys.stdin if stream is None else stream
    buffer = getattr(stream, "buffer", None)
    if buffer is None:
        return stream.read()
    return buffer.read().decode("utf-8", errors="replace")


#: What a payload this harness writes carries - one of them at least, for every event: the event's
#: name, the session, its transcript, the tool. A payload holding none is not one it wrote.
PAYLOAD_KEYS = ("hook_event_name", "session_id", "transcript_path", "tool_name", "tool_input")


def payload_problem(raw):
    """Why `raw` is not a hook payload anything here can judge, or None when it is one.

    A PAYLOAD NOBODY CAN READ IS UNKNOWN, NEVER CLEAN. Measured on CTRMap 2026-10-06 (its F213):
    every wired entry point - both PreToolUse stacks, both Stop hooks, PostToolUse - was fed text
    that is not JSON, `{}`, and a payload cut mid-command, and all fifteen runs exited 0 saying
    nothing, while the same hooks refused the controls. `dispatch.py` returned before any guard
    was asked, so even `guard_fanout`, which refuses an unreadable payload itself, never saw one.
    """
    import json
    try:
        payload = json.loads(raw)
    except ValueError as exc:
        return "the hook payload is not JSON (%s)" % str(exc)[:120]
    if not isinstance(payload, dict):
        return "the hook payload is JSON but not an object (%s)" % type(payload).__name__
    if not any(key in payload for key in PAYLOAD_KEYS):
        return ("the hook payload names no event, session, transcript or tool (%s)"
                % (", ".join(sorted(payload))[:120] or "it is empty"))
    return None


def refused_before(raw):
    """True when `raw` - parsed or not - shows a turn end a stop hook already sent back once.

    THE DISPATCHER REFUSES THE END OF A TURN ONCE: a second refusal of the same stop spins forever
    over something the turn may not be able to reach. The harness marks the retry with
    `stop_hook_active`, and a payload that does not parse can still carry it, so it is read from
    the raw text. An unreadable turn end is then refused once, and the retry allowed - and SAID.
    """
    return re.search(r'"stop_hook_active"\s*:\s*true', raw or "") is not None


def unreadable_payload(hook, problem, bypass_name):
    """What a hook says when it cannot judge the call it was handed - and the one way past."""
    return (policy(hook) + "\n"
            "This call could not be judged: %s. Nothing behind %s was asked, and a call nobody "
            "could judge is UNKNOWN - not allowed.\n\n"
            "A QUERY THAT CANNOT READ ITS SUBJECT MUST NOT REPORT IT ABSENT.\n\n"
            "If the harness changed what it sends, the owner can set %s=1 for the session: every "
            "call is then accepted unchecked, and each one SAYS so.\n"
            % (problem, os.path.basename(hook), bypass_name))


def speak_utf8():
    """Make this process's stdout and stderr write UTF-8 - the other half of `payload_text`. Every
    hook module the harness can run as a process calls it first, in its `__main__` block.

    NOT IN THE LOCALE'S CODE PAGE, AND ON STDOUT NOT EVEN SURVIVABLY. A Windows pipe's stdout is
    cp1252 and STRICT: measured 2026-09-26 in CTRMap, a SessionStart hook raised
    UnicodeEncodeError halfway through the index it printed, exited 1, and the session was shown
    nothing. For the dispatcher the same raise is worse than silence - it forwards a guard's deny
    on stdout, a deny carrying one character the code page lacks crashed it into exit 1, and the
    harness counts exit 1 as PERMISSION. stderr never raises (Python writes it with
    backslashreplace) but reached the harness as `\\u2192` where the guard wrote an arrow.

    One call per process rather than one per write, because a door at each write is one `print`
    from broken. A stream that cannot be reconfigured is left as it was: raising here would crash
    the dispatcher into the exit code that means yes.
    """
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            continue
        try:
            reconfigure(encoding="utf-8")
        except (ValueError, OSError):
            continue


def _walk(node, depth, out, trouble):
    if depth > MAX_DEPTH:
        trouble.append("nested deeper than %d" % MAX_DEPTH)
        return
    if isinstance(node, dict):
        for key, value in node.items():
            if key in COMMAND_KEYS and isinstance(value, str):
                out.append(value)
            elif key in COMMAND_KEYS and value is not None and not isinstance(
                    value, (list, tuple, dict)):
                #: a command that is not text is a command this cannot read, and an unreadable
                #: command must not be reported as no command at all
                trouble.append("%s was %s, not text" % (key, type(value).__name__))
            else:
                _walk(value, depth + 1, out, trouble)
    elif isinstance(node, (list, tuple)):
        for value in node:
            _walk(value, depth + 1, out, trouble)


def scan(payload):
    """(commands, trouble) - the one walk both questions are answered from."""
    out = []
    trouble = []
    try:
        tool_input = (payload or {}).get("tool_input")
    except AttributeError:
        return [], ["the payload was %s, not an object" % type(payload).__name__]
    if tool_input is None:
        return [], []
    if not isinstance(tool_input, (dict, list, tuple)):
        return [], ["tool_input was %s, not an object" % type(tool_input).__name__]
    _walk(tool_input, 0, out, trouble)
    return out, trouble


def commands(payload):
    """Every shell command string in this tool call. Empty list when it runs no shell."""
    return scan(payload)[0]


def unreadable(payload):
    """Why part of this input could not be read, or empty when all of it was.

    A caller deciding whether to REFUSE must treat a non-empty answer as "there may be a
    command in here I cannot see", never as "there is no command".
    """
    return scan(payload)[1]


def text(payload):
    """Every command in the call, joined with newlines - what a pattern scan wants.

    Newlines rather than spaces, so a pattern anchored to the start of a line still matches
    the second command of a batch.
    """
    return LF.join(commands(payload))


def is_tool_call(payload):
    """Is this payload a TOOL CALL at all, rather than the end of a turn or anything else?

    A shape, like everything here: a payload that names a tool or carries a tool input is a
    call. A `Stop` payload carries neither, and a guard whose subject is tool calls must let it
    through - the dispatcher hands every guard every event, so that a guard added tomorrow is
    asked the day it exists, and a tool guard that refused the end of a turn would wedge it.
    """
    try:
        return bool(payload.get("tool_name")) or payload.get("tool_input") is not None
    except AttributeError:
        return False


def owner_turn_ends(payload):
    """Is this payload the end of the OWNER's turn - not a tool call, and no event but `Stop`?

    A SUBAGENT'S END HAS A STOP'S SHAPE. `SubagentStop` carries no tool, and exit 2 there keeps
    the SUBAGENT running: a turn-end guard that judged it would hold every subagent behind the
    owner's last message or a change the subagent never made. `guard_requests` asked the event's
    name; the promise, claim and held-change guards asked only the shape, and judged a subagent's
    end as the turn's (measured 2026-10-06). One predicate, asked by all four."""
    try:
        return not is_tool_call(payload) and payload.get("hook_event_name") in (None, "Stop")
    except AttributeError:
        return False


def _collect(node, depth, keys, out):
    if depth > MAX_DEPTH:
        return
    if isinstance(node, dict):
        for key, value in node.items():
            if key in keys:
                if isinstance(value, str) and value:
                    out.append(value)
                elif isinstance(value, (list, tuple)):
                    out.extend(v for v in value if isinstance(v, str) and v)
                elif value is not None and key in WRITE_KEYS:
                    out.append(key)
            _collect(value, depth + 1, keys, out)
    elif isinstance(node, (list, tuple)):
        for value in node:
            _collect(value, depth + 1, keys, out)


def paths(payload):
    """Every path this call names in a path-shaped key, at any depth. Empty when it names none."""
    out = []
    try:
        _collect((payload or {}).get("tool_input"), 0, PATH_KEYS, out)
    except AttributeError:
        return []
    return out


def writes(payload):
    """Does this call WRITE content somewhere, by the shape of its input rather than its name?

    CONTENT AND A PLACE. A write key alone was the rule, and the harness hands a `SendMessage`
    input a `content` key of its own - measured from the transcript: `to`, `message`, `summary`,
    plus `recipient`, `type` and `content`. Every message therefore read as a file write, so it
    was not a read, and on 2026-09-24 a stalled-job refusal took away both sessions' only way to
    tell each other or the owner why they had stopped. A file write names where it lands -
    `file_path`, `notebook_path` - and a message names nobody's file. Asked of the WHOLE input,
    not one dict: content nested a level below its path is still a write.
    """
    found = []
    try:
        _collect((payload or {}).get("tool_input"), 0, WRITE_KEYS, found)
    except AttributeError:
        return False
    return bool(found) and bool(paths(payload))


def reads_only(payload):
    """A tool call that carries no command and writes nothing: a READ, by shape.

    README section 15: *reading must never be blocked*. The remedy for a machine nobody
    understands is to look at it, and a guard that refuses the look is one somebody switches
    off. Two guards here refused every `Read` and `Grep` call while they were refusing commands,
    because they judged the empty command text of a call that carried none.
    """
    return is_tool_call(payload) and not commands(payload) and not writes(payload) \
        and not unreadable(payload)


#: `<<EOF`, `<<'EOF'`, `<<"EOF"`, `<<-EOF` - the opener whose BODY belongs to the command on
#: its line rather than being commands of its own. Not `<<<`, a here-STRING, which has no body.
_HEREDOC = re.compile(r"(?<!<)<<-?\s*(?![<])('([^']+)'|\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))")


def _heredoc_delimiters(line):
    blanked, quote = [], None
    for char in line:
        if quote:
            blanked.append(" " if char != quote else char)
            if char == quote:
                quote = None
        elif char in ("'", chr(34)):
            quote = char
            blanked.append(char)
        else:
            blanked.append(char)
    found = []
    for match in _HEREDOC.finditer("".join(blanked)):
        raw = line[match.start(1):match.end(1)]
        found.append(raw.strip("'" + chr(34)))
    return found


def heredoc_split(command):
    """(the command's own line, the text its heredoc hands it on stdin - or None).

    ONE READING OF "WHAT IS THE COMMAND AND WHAT IS ITS INPUT". `separated` keeps a heredoc's body
    with the command that opened it, and lexed whole, the body's words became the command's own:
    `git commit -F - <<'EOF'` with a message that MENTIONS `--no-verify` was refused as skipping
    the gate, and the message itself as unreadable (measured 2026-10-06, eighteen refusals over
    one session's calls). A heredoc opened inside quotes - `-m "$(cat <<'EOF' ...)"` - is part of
    an argument, not stdin, and is left where it is.
    """
    line, lf, rest = (command or "").partition(LF)
    delimiters = _heredoc_delimiters(line)
    if not lf or not delimiters:
        return command, None
    body = []
    for text in rest.split(LF):
        if text.strip() == delimiters[0]:
            break
        body.append(text)
    return line, LF.join(body) + LF


def split_commands(line):
    """The separate COMMANDS in a shell text, quotes respected. A pipeline is ONE command.

    Separators: `;`, `&&`, `||` and a NEWLINE - all outside quotes. A newline that ends in a
    line continuation (a trailing backslash, or PowerShell's trailing backtick) does not
    separate, and a heredoc's BODY belongs to the command that opened it rather than being read
    as commands of its own - a commit message fed through `<<EOF` is not a list of programs.

    `separated` is the same split, with the separator that came before each command.
    """
    return [command for _before, command in separated(line)]


def _joined(first, then):
    """The separator before a command when an EMPTY piece sat between two separators."""
    if first == "" or then == LF or then == first:
        return first
    if first in (";", LF) and then in (";", LF):
        return ";"
    return "?"


def separated(line):
    """[(separator before it, command)]: `""` for the first, then `;`, `&&`, `||` or a newline -
    or `?` where the text puts two separators together and what runs after them is not plain.

    WHY IT EXISTS. Whether a command runs at all can depend on the one before it: in
    `false && cd elsewhere; git commit` the `cd` never happens, and the commit runs where the
    call began. A guard deciding WHERE a command runs has to know which separator it came after.
    """
    text = line or ""
    out, current, quote = [], [], None
    index = 0
    pending = []                                  # heredoc delimiters opened on this line
    line_start = 0
    while index < len(text):
        char = text[index]
        if quote:
            current.append(char)
            if char == chr(92) and quote == chr(34) and index + 1 < len(text):
                current.append(text[index + 1])
                index += 2
                continue
            if char == quote:
                quote = None
            index += 1
            continue
        if char in ("'", chr(34)):
            quote = char
            current.append(char)
            index += 1
            continue
        if char == LF:
            before = "".join(current).rstrip()
            pending.extend(_heredoc_delimiters(text[line_start:index]))
            if pending:
                # Consume the bodies, in order, as part of THIS command.
                current.append(char)
                index += 1
                while pending and index < len(text):
                    end = text.find(LF, index)
                    body_line = text[index:] if end < 0 else text[index:end]
                    current.append(body_line + (LF if end >= 0 else ""))
                    index = len(text) if end < 0 else end + 1
                    if body_line.strip() == pending[0]:
                        pending.pop(0)
                line_start = index
                out.append(("".join(current), LF))
                current = []
                continue
            if before.endswith(chr(92)) or before.endswith("`"):
                current.append(char)
                index += 1
                line_start = index
                continue
            out.append(("".join(current), LF))
            current = []
            index += 1
            line_start = index
            continue
        if char == ";":
            out.append(("".join(current), ";"))
            current = []
            index += 1
            continue
        if char in ("&", "|") and text.startswith(char * 2, index):
            out.append(("".join(current), char * 2))
            current = []
            index += 2
            continue
        current.append(char)
        index += 1
    out.append(("".join(current), ""))
    found, before = [], ""
    for piece, ending in out:
        if piece.strip():
            found.append((before, piece.strip()))
            before = ending
        else:
            before = _joined(before, ending)
    return found


#: A command that only CHANGES WHERE or WITH WHAT the next one runs. It is not work, and it is
#: not an escape - it is neither, and treating it as either was how every command in one project
#: became exempt from a guard: they all began `cd "..." &&`.
NEUTRAL = re.compile(
    r"^\s*(?:cd|chdir|pushd|popd|Set-Location|Push-Location|Pop-Location|sl)(?:\s|$)"
    r"|^\s*\$env:[A-Za-z_][A-Za-z_0-9]*\s*=[^;|]*$"
    r"|^\s*export\s+[A-Za-z_][A-Za-z_0-9]*=\S*\s*$"
    r"|^\s*[A-Za-z_][A-Za-z_0-9]*=\S*\s*$"
    r"|^\s*#",
    re.I)

#: `VAR=value cmd` - the prefix is not the program. A QUOTED VALUE IS ONE VALUE, spaces and all (2026-10-12):
#: read as `\S*`, `X='rm -rf src'` lost `X='rm` as a prefix and ran `-rf`, and `H='a b' python x.py` ran `b'` - so
#: an assignment holding a sentence read as a command whose program was one of its words.
_ENV_PREFIX = re.compile(r"^\s*(?:[A-Za-z_][A-Za-z_0-9]*=(?:'[^']*'|" + chr(34) + r"(?:[^" + chr(34) + r"\\]|\\.)*"
                         + chr(34) + r"|[^\s'" + chr(34) + r"])*\s+)+")


def program(command):
    """The command with any leading `VAR=value` assignments removed - what actually runs."""
    return _ENV_PREFIX.sub("", command or "", count=1).strip()


# ----------------------------------------------------------------------------- the reader
#
# What a command RUNS, read through wrappers and shells. It lived in guard_command_rules
# until the escape door below needed it, and a project may decline that guard - so it
# lives here, in the file every installation carries, and that guard imports it.

#: Words that only start the NEXT word as the program: `& git`, `env X=1 git`, `time git`.
#:
#: EACH WITH THE OPERANDS IT TAKES BEFORE THE PROGRAM. Measured 2026-09-24 by the peer session:
#: `timeout 600 git commit --no-verify` walked past, because `timeout` was not known as a runner
#: and, known or not, its DURATION is a word before the program - read as the program, it hid the
#: commit behind it. A runner that takes an operand says how many.
_RUNS_NEXT = {"&": 0, "!": 0, "command": 0, "builtin": 0, "exec": 0, "time": 0, "nice": 0,
              "nohup": 0, "env": 0, "sudo": 0, "doas": 0, "xargs": 0, "call": 0, "start": 0,
              "timeout": 1, "stdbuf": 0, "unbuffer": 0, "setsid": 0, "ionice": 0, "chrt": 1,
              "taskset": 1, "caffeinate": 0, "watch": 0}

#: A runner's options that take a VALUE, so the value is not read as the program.
_RUNNER_VALUED = {"env": ("-u", "--unset", "-C", "--chdir", "-S", "--split-string"),
                  "nice": ("-n", "--adjustment"), "sudo": ("-u", "-g", "-C", "-D", "-h", "-p"),
                  "doas": ("-u", "-C"),
                  "xargs": ("-n", "-I", "-L", "-P", "-d", "-a", "-E", "-s"),
                  "time": ("-f", "-o"), "timeout": ("-s", "--signal", "-k", "--kill-after"),
                  "stdbuf": ("-i", "-o", "-e"), "ionice": ("-c", "-n", "-p"),
                  "watch": ("-n", "--interval", "-d")}

#: Shells handed their program as a STRING. A POSIX shell's `-c` may be clustered (`-lc`).
_POSIX_SHELLS = frozenset(("bash", "sh", "zsh", "dash", "ksh", "ash"))

_POWERSHELLS = frozenset(("powershell", "pwsh"))

_EVALS = frozenset(("eval", "iex", "invoke-expression"))

#: PowerShell's own options that take a value, spelled out; any unambiguous prefix is accepted.
_PS_VALUED = ("-executionpolicy", "-windowstyle", "-version", "-inputformat", "-outputformat",
              "-configurationname", "-workingdirectory", "-psconsolefile", "-settingsfile",
              "-custompipename")

#: How deep a command handed to a shell handed to a shell is read before it is refused.
MAX_NESTING = 6

def _lex(text, posix):
    try:
        lexer = shlex.shlex(text, posix=posix, punctuation_chars=True)
        lexer.whitespace_split = True
        return list(lexer)
    except ValueError:
        return None

def pairs(command):
    """[(word, the word as typed)] for one command, or None when it cannot be split.

    The punctuation of a shell - `( ) { } | &` - comes back as words of its own when it is not
    quoted, so `(git commit --no-verify)` is `--no-verify` and not `--no-verify)`. The word AS
    TYPED keeps its backslashes: a POSIX reading turns an unquoted `C:\\Tools\\x` into `C:Toolsx`,
    and a directory is looked for under both spellings.
    """
    text = program(command)
    words, typed = _lex(text, True), _lex(text, False)
    if words is None:
        words = typed
    if words is None:
        return None
    if typed is None or len(typed) != len(words):
        typed = [None] * len(words)
    else:
        typed = [t[1:-1] if len(t) > 1 and t[0] == t[-1] and t[0] in ("'", chr(34)) else t
                 for t in typed]
    return list(zip(words, typed))

def program_of(word):
    """The program a word names: `C:\\Program Files\\Git\\cmd\\git.exe` is `git`."""
    name = re.split(r"[\\/]", word or "")[-1].lower()
    return name[:-4] if name.endswith(".exe") else name


# ----------------------------------------------------------------------------- where a cd lands

#: Which shell reads a tool's command, by the harness's tool name. Any other tool is UNKNOWN, and
#: a path the two shells would read differently is then placed nowhere.
SHELLS = {"bash": "bash", "powershell": "powershell"}

#: Whether paths are read as Windows reads them. A module constant, so a test can ask the Windows
#: question on any machine - a plant that cannot redden off Windows is refused everywhere else.
WINDOWS = os.name == "nt"

#: Git for Windows' shell reads a single-letter top folder as a drive: `/c/Users` is `C:\Users`.
MSYS_DRIVE = re.compile(r"^/([A-Za-z])(?=/|$)")

#: What `cygpath` answered, per path, for this process.
_ASKED = {}

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0


def shell_of(tool):
    """'bash', 'powershell', or None when the tool's shell is not known."""
    return SHELLS.get(str(tool or "").lower())


def _cygpath():
    """The `cygpath` Git for Windows ships: on the PATH, or beside `git` - None when neither."""
    found = shutil.which("cygpath")
    if found:
        return found
    git = shutil.which("git")
    if git:
        beside = os.path.join(os.path.dirname(os.path.dirname(git)), "usr", "bin", "cygpath.exe")
        if os.path.isfile(beside):
            return beside
    return None


def msys_path(path):
    """Where Git for Windows' shell puts an absolute POSIX path, or None when that cannot be asked
    - a drive folder by its rule, anything else by asking `cygpath`, the shell's own answer."""
    drive = MSYS_DRIVE.match(path)
    if drive:
        return drive.group(1).upper() + ":" + (path[2:] or "/")
    if path not in _ASKED:
        tool, answer = _cygpath(), None
        if tool:
            try:
                done = bounded.run([tool, "-w", path], capture_output=True, text=True,
                                   timeout=10, creationflags=NO_WINDOW)
                if done.returncode == 0 and done.stdout.strip():
                    answer = done.stdout.strip()
            except (OSError, subprocess.SubprocessError):
                answer = None
        _ASKED[path] = answer
    return _ASKED[path]


def powershell_path(path, where):
    """Where PowerShell puts an absolute POSIX path - at the root of the drive it is working on, as
    `Set-Location /c/Users` looks for `C:\\c\\Users` - or None when that drive cannot be told."""
    drive = os.path.splitdrive(where)[0] if where else ""
    return drive + path.replace("/", "\\") if drive else None


def readings(word, where, shell=None):
    """Every absolute path the shell running a command could read `word` as - one for a shell that
    is named, each shell's for one nobody names on Windows - or [] when it cannot be told at all: a
    variable, a glob, a relative path from an unknown place. An entry is None where that shell's
    reading cannot be asked (no `cygpath` to ask, no drive to put it on).

    A QUESTION THAT PLACES SOMETHING ELSEWHERE asks that every reading agree (`lands`); a question
    that REFUSES a write into a tree asks whether any reading lands in it. Both read the same list.
    """
    if not word or any(char in word for char in "$`%*?"):
        return []
    path = os.path.expanduser(word) if word.startswith("~") else word
    if WINDOWS and path.startswith("/") and not path.startswith("//"):
        out = []
        if shell in ("bash", None):
            out.append(msys_path(path))
        if shell in ("powershell", None):
            out.append(powershell_path(path, where))
        return [os.path.normpath(found) if found else None for found in out]
    if not os.path.isabs(path):
        if where is None:
            return []
        path = os.path.join(where, path)
    return [os.path.normpath(path)]


def lands(word, where, shell=None):
    """The folder `cd <word>` from `where` lands in, READ THE WAY THE SHELL RUNNING IT READS A PATH
    - or None when that cannot be told: a variable, a glob, a relative path from an unknown place, a
    folder that is not there, or two shells that would put it in different places.

    WINDOWS PYTHON READS A POSIX PATH AS ANOTHER PLACE. Measured 2026-10-07 on this machine:
    Python reads `/tmp` as `C:\\tmp`, which is not there, while the Bash tool's shell puts it at
    the user's temp folder (`cygpath -w /tmp`); and PowerShell reads `/c/Users` as `C:\\c\\Users`
    and fails the `cd`, while that shell reads it as `C:\\Users`. So the suite guard held `cd /tmp
    && python -m pytest` as a run it could not place, and the commit gate placed a PowerShell
    `cd /c/<another repository>; git commit --no-verify` in that repository - the cd failed, the
    commit ran HERE, and the gate called it another project's. One reading, asked of the shell.
    """
    found = readings(word, where, shell)
    if not found or any(path is None or not os.path.isdir(path) for path in found):
        return None
    if len({os.path.normcase(os.path.realpath(path)) for path in found}) != 1:
        return None
    return found[0]

def stages(held):
    """The programs of one command: a pipeline's stages, and every group `( )`/`{ }` a program
    of its own - so a PowerShell script block, `Measure-Command { git commit ... }`, is read as
    the command it runs rather than as arguments to `Measure-Command`."""
    out, current = [], []
    for word, typed in held:
        if word in ("(", ")", "{", "}"):
            if current:
                out.append(current)
            current = []
            continue
        if word and set(word) <= set("|&") and not (word == "&" and not current):
            if current:
                out.append(current)
            current = []
            continue
        current.append((word, typed))
    if current:
        out.append(current)
    return out

def peeled(stage):
    """The stage with every word that only starts the next program taken off, and whether one
    of them also moved where it runs (`env -C dir`)."""
    index, moves = 0, False
    while index < len(stage):
        word = stage[index][0]
        name = program_of(word)
        if word != "&" and name not in _RUNS_NEXT:
            break
        if name == "timeout" and index + 1 < len(stage) and stage[index + 1][0].lower() == "/t":
            break                       # cmd's `timeout /t 115` is a wait, not a runner
        valued = _RUNNER_VALUED.get(name, ())
        index += 1
        while index < len(stage) and (stage[index][0].startswith("-") or (
                name == "env" and "=" in stage[index][0])):
            option = stage[index][0]
            if name == "env" and (option in ("-C", "--chdir") or option.startswith("--chdir=")):
                moves = True
            index += 2 if option in valued else 1
        index += _RUNS_NEXT.get(name, 0)
    return stage[index:], moves

def _joined_text(stage):
    return " ".join(typed if typed is not None else shlex.quote(word) for word, typed in stage)

#: The text a stage runs, public: the escape door below and guard_command_rules read it.
joined_text = _joined_text

def handed(stage):
    """(the command text a shell or `eval` in this stage is handed, or None; why it cannot be
    read, or '')."""
    if not stage:
        return None, ""
    name, rest = program_of(stage[0][0]), stage[1:]
    if name in _POSIX_SHELLS:
        for at, (word, _typed) in enumerate(rest):
            if re.match(r"^-[A-Za-z]*c[A-Za-z]*$", word):
                return (rest[at + 1][0], "") if at + 1 < len(rest) else (None, "")
            if not word.startswith(("-", "+")):
                return None, ""
        return None, ""
    if name == "cmd":
        for at, (word, _typed) in enumerate(rest):
            if word.lower() in ("/c", "/k", "/r"):
                return _joined_text(rest[at + 1:]), ""
        return None, ""
    if name in _EVALS:
        rest = [pair for pair in rest if pair[0].lower() not in ("-command", "-c")]
        return _joined_text(rest), ""
    if name not in _POWERSHELLS:
        return None, ""
    at = 0
    while at < len(rest):
        low = rest[at][0].lower()
        if not low.startswith("-"):
            # powershell.exe reads words with no parameter as the command to run.
            return _joined_text(rest[at:]), ""
        if len(low) >= 2 and "-file".startswith(low):
            return None, ""
        if len(low) >= 2 and "-command".startswith(low):
            return _joined_text(rest[at + 1:]), ""
        if len(low) >= 2 and ("-encodedcommand".startswith(low) or low == "-ec"):
            try:
                return base64.b64decode(rest[at + 1][0]).decode("utf-16-le"), ""
            except (IndexError, ValueError, UnicodeDecodeError):
                return None, "`%s` carries a command this cannot decode" % rest[at][0]
        at += 2 if len(low) >= 2 and any(v.startswith(low) for v in _PS_VALUED) else 1
    return None, ""


def every_command_is(text_, allowed, _depth=0):
    """True when EVERY command in the text is neutral or satisfies `allowed(command)`.

    THE ESCAPE IS PER COMMAND, NEVER PER LINE. Two guards here asked `re.search(escape, line)`,
    so `git status; <anything at all>` was an escape - the cheapest way past a refusal was to
    put the way out in front of the thing being refused. An empty text has no commands, and is
    NOT an escape: nothing was asked for, so nothing is excused.

    AND READ THROUGH A WRAPPER. Five hooks excuse a command by a pattern on it AS TYPED, and on
    2026-09-26 `env -u PYTHONIOENCODING git commit` was refused as new work by a guard whose
    own refusal demanded that commit. A command the pattern does not excuse as typed is asked
    again as what it RUNS - the program `env`, `timeout` or `time` hands on, and every command
    a shell or `eval` is handed - read by the reader above, the one reader of those, and no
    deeper than MAX_DEPTH. Every program it runs must be excused, so new work behind a
    wrapper, or behind a way out inside a shell, is still new work.
    """
    found = split_commands(text_)
    if not found:
        return False
    for command in found:
        if NEUTRAL.match(command):
            continue
        if allowed(program(command)):
            continue
        inner = _runs(command) if _depth < MAX_DEPTH else None
        if not inner or not all(every_command_is(one, allowed, _depth + 1) for one in inner):
            return False
    return True


def _runs(command):
    """The commands a wrapper or a shell in `command` hands on, as text - or None when it
    hands nothing on, or cannot be read. A command that cannot be read is never excused."""
    held = pairs(command)
    if not held:
        return None
    out, handed_on = [], False
    for stage in stages(held):
        rest, _moves = peeled(stage)
        if not rest:
            return None
        text_, trouble = handed(rest)
        if trouble:
            return None
        handed_on = handed_on or text_ is not None or len(rest) != len(stage)
        out.append(text_ if text_ is not None else joined_text(rest))
    return out if handed_on else None


#: Words a shell reads before the program of a compound command - `for ...; do X; done`.
KEYWORDS = frozenset(("do", "then", "else", "elif", "if", "while", "until", "!", "time"))


def _ended_stages(held):
    """`stages`, each with the word that ended it: `&` alone means it was sent to the background."""
    out, current = [], []
    for word, typed in held:
        if word in ("(", ")", "{", "}") or (word and set(word) <= set("|&")
                                            and not (word == "&" and not current)):
            if current:
                out.append((current, word))
            current = []
            continue
        current.append((word, typed))
    if current:
        out.append((current, ""))
    return out


def runs(text_, _depth=0):
    """[(words, backgrounded)] - every PROGRAM this shell text runs, in order: through `;`, `&&`,
    `|`, a newline and a group, with the runners in front of it peeled (`env`, `timeout 700`,
    `nice`, `VAR=value`) and into every command a shell or `eval` is handed, to MAX_NESTING. A
    command that cannot be split comes back as (None, False) - unknown, never nothing.

    THE PROGRAM IS WHAT RUNS, NOT THE FIRST WORD TYPED. Three guards read a command's first word:
    measured 2026-10-06, `timeout 700 sleep 600` was no sleep, `timeout 3000 python <sweep>` in the
    foreground no sweep, and `timeout 60 python <audit tool>` beside a mutant no run of one. A
    guard asking which program runs asks this - the one reader of that, as `every_command_is` is
    the one reader of whether every command is excused."""
    out = []
    for command in split_commands(text_):
        if NEUTRAL.match(command):
            continue
        line, _body = heredoc_split(command)
        held = pairs(line)
        if held is None:
            out.append((None, False))
            continue
        for stage, ended in _ended_stages(held):
            while stage and stage[0][0] in KEYWORDS:
                stage = stage[1:]
            rest, _moves = peeled(stage)
            # A PLACE IS NOT A PROGRAM, peeled or not. `NEUTRAL` was asked of the whole command
            # only, so `DT=1 cd /tmp && sleep 5m` peeled to a `cd` that ran as work - measured
            # 2026-10-07 by DF Engine's older suite: the long wait after it was let through.
            if not rest or NEUTRAL.match(" ".join(item[0] for item in rest)):
                continue
            inner, trouble = handed(rest)
            if trouble:
                out.append((None, False))
            elif inner is not None and _depth < MAX_NESTING:
                out.extend(runs(inner, _depth + 1))
            else:
                out.append((rest, ended == "&"))
    return out


def texts(text_, _depth=0):
    """The text and every command text a shell or `eval` in it is handed, at any depth - for a
    question asked of a command's SHAPE, such as whether it loops, rather than of its programs."""
    out = [text_ or ""]
    if _depth >= MAX_NESTING:
        return out
    for command in split_commands(text_):
        held = pairs(heredoc_split(command)[0])
        for stage in stages(held) if held else []:
            rest, _moves = peeled(stage)
            inner = handed(rest)[0] if rest else None
            if inner is not None:
                out.extend(texts(inner, _depth + 1))
    return out


#: A SCRIPTED multi-agent run, by shape: a script that calls `agent(` or declares its `meta`, or
#: one named by its path or resumed by its run - whatever the tool carrying it is called.
_SCRIPTED = re.compile(r"\bagent\s*\(|\bexport\s+const\s+meta\b")


def workflows(payload):
    """Every SCRIPTED fan-out in this call, by shape - the other way a session launches agents.

    THE NAME WAS THE TRIGGER. `guard_fanout` asked `tool == "Workflow"`, the trigger-by-name this
    folder was rebuilt to remove: a script of `agent(...)` calls handed to a tool called anything
    else launched every agent in it uncounted. A saved one called only by its `name` carries no
    shape of its own, so `guard_fanout` still asks the name beside this, never instead of it."""
    found = []

    def walk(node, depth):
        if depth > MAX_DEPTH:
            return
        if isinstance(node, dict):
            script = node.get("script")
            if ("scriptPath" in node or "resumeFromRunId" in node
                    or (isinstance(script, str) and _SCRIPTED.search(script))):
                found.append(node)
            for value in node.values():
                walk(value, depth + 1)
        elif isinstance(node, (list, tuple)):
            for value in node:
                walk(value, depth + 1)

    try:
        walk(payload.get("tool_input"), 0)
    except AttributeError:
        return []
    return found


#: What makes a `prompt` the brief of an AGENT rather than a picture's caption: a key that starts
#: a model working on it - an agent type or a task description, an environment or a repository a
#: session is started in, a schedule a routine starts one on. A prompt alone is not a launch: an
#: image generator takes one too.
LAUNCH_KEYS = ("subagent_type", "description", "environment_id", "source_url", "cron_expression",
               "run_once_at", "create_new_session_on_fire", "persistent_session_id", "title",
               "permission_mode", "append_system_prompt", "extra_allowed_tools", "outcome_branch",
               "source_revision", "sparse_checkout_paths")

#: Who a brief is HANDED to when it is not started fresh: another session, an agent already
#: running, a routine fired now. A message to one of them is work for a model that is not this one.
ADDRESSEE_KEYS = ("session_id", "to", "trigger_id")

#: The words a brief handed to an addressee travels in.
BRIEF_KEYS = ("message", "text", "prompt")

#: A tool whose NAME says it starts a model working - one word of it, between underscores.
_LAUNCHER_NAME = re.compile(r"(?:^|_)(?:session|agent|routine|trigger|task)s?(?:_|$)", re.I)

#: A GitHub comment that summons the Claude app starts an agent on the repository.
_SUMMONS = re.compile(r"(?<![\w.-])@claude\b", re.I)


def _launch_shaped(node):
    """A REMOTE OR SCHEDULED SESSION IS AN AGENT TOO. This asked for `subagent_type` or
    `description` only, so a session started in the cloud on a prompt, or a routine that starts
    one on a schedule - an agent working a brief on the same metered plan - was never counted,
    capped or asked for a structured result (measured 2026-10-06 against the session tools
    offered here).

    AND A BRIEF HANDED ON IS A LAUNCH. Measured 2026-10-07 against the dispatcher with every guard
    wired: a session started on a prompt and a title alone went through, and so does any message
    that asks a running agent, another session or a fired routine to do the work - the session
    tools' own description calls that "meta-orchestration: asking a sibling session to perform a
    subtask". So is a GitHub comment that summons the Claude app, which starts an agent there."""
    if not isinstance(node, dict):
        return False
    if isinstance(node.get("prompt"), str) and any(key in node for key in LAUNCH_KEYS):
        return True
    if any(key in node for key in ADDRESSEE_KEYS) and any(
            isinstance(node.get(key), str) and node[key].strip() for key in BRIEF_KEYS):
        return True
    return ("owner" in node and "repo" in node
            and any(isinstance(v, str) and _SUMMONS.search(v) for v in node.values()))


#: The one tool that PROPOSES work the owner starts: the desktop app's task chip, which puts a card
#: in front of the owner and starts nothing until the owner clicks it - and the exact input it takes.
SUGGESTION = ("mcp__ccd_session__spawn_task", frozenset(("title", "prompt", "tldr")))


def owner_starts(payload):
    """Is this call a card only the OWNER can start - the app's task chip, exactly as it is?

    THE CHIP IS THE OWNER'S YES ALREADY. Reported 2026-10-09 from a brand-new project: the app's
    "suggest a task" chip was refused as a background editing agent - a prompt beside a title reads
    as a session started on a brief - so a problem could not even be filed as a card. Nothing it
    proposes runs until the owner clicks it.

    BY THE TOOL, NEVER BY THE SHAPE. A shape - a `tldr` beside the prompt - would be the cheapest
    way past every launch guard: a launcher that accepts an extra key would pass as a suggestion.
    So it is this one tool, with exactly its own input and nothing more; any other key, or any
    other tool, is read as the launch it may be."""
    if not isinstance(payload, dict):
        return False
    given = payload.get("tool_input")
    tool, keys = SUGGESTION
    return (payload.get("tool_name") == tool and isinstance(given, dict)
            and set(given) <= keys and isinstance(given.get("prompt"), str))


def launches(payload):
    """Every AGENT LAUNCH in this call, by shape: an input carrying a `prompt` and a
    `subagent_type` or `description`. Nested launches in a batching tool count too.

    THE NAME IS NOT THE QUESTION HERE EITHER. The tool that launches a subagent has been called
    `Task` and is called `Agent`; a guard asking for one of those names is one rename from off,
    which is what happened to eight shell guards when the shell was spelled `PowerShell`.
    """
    found = []

    def walk(node, depth):
        if depth > MAX_DEPTH:
            return
        if _launch_shaped(node):
            found.append(node)
        if isinstance(node, dict):
            for value in node.values():
                walk(value, depth + 1)
        elif isinstance(node, (list, tuple)):
            for value in node:
                walk(value, depth + 1)

    if owner_starts(payload):
        return []
    try:
        walk((payload or {}).get("tool_input"), 0)
    except AttributeError:
        return []
    # THE NAME BESIDE THE SHAPE, NEVER INSTEAD. A session started on a prompt ALONE carries no key
    # that says a model will work it - measured 2026-10-07, `create_session` with only `prompt`
    # went through - and its tool's name is the one thing left that does.
    given = payload.get("tool_input") if isinstance(payload, dict) else None
    if (isinstance(given, dict) and given not in found
            and _LAUNCHER_NAME.search(str(payload.get("tool_name") or ""))
            and isinstance(given.get("prompt"), str) and given["prompt"].strip()):
        found.append(given)
    return found


def brief(launch):
    """The words a launch hands its model, read from every key `_launch_shaped` finds a brief by.

    ONE READER FOR THE BRIEF (2026-10-09) - and one implementation of it: this session and the
    Desktop clone each wrote one the same day, and the merge kept both, the second shadowing the
    first. `launches` counts a message handed to another session,
    a running agent or a routine as a launch - `to`, `session_id` or `trigger_id` beside a
    `message` or `text` - and every guard reading the brief asked `prompt` alone. So
    `guard_agent_brief` refused every `SendMessage` whatever it carried (measured: a message
    holding all three fields and the sentence, refused twice), and `guard_agent_edits` read an
    empty brief and let a message telling a session to change files through as no edit at all.
    A guard asks this, and a fourth reader cannot read a third set of keys."""
    if not isinstance(launch, dict):
        return ""
    return "\n".join(launch[key] for key in BRIEF_KEYS
                     if isinstance(launch.get(key), str) and launch[key].strip())


#: The keys of a call that only hands words to a session already running: who, what, a label for this
#: transcript, and whether to hear when it goes idle - and the three the harness adds to every such call as
#: it records it (`type` "message", a `recipient` naming the same session, the words again as `content`),
#: measured 2026-10-09 from the transcript after a hand-built payload without them passed every test and
#: the real call was refused. A prompt, a model, a subagent type or an isolation starts or shapes a
#: model, and a call carrying one is a launch.
MESSAGE_ONLY = frozenset(("to", "message", "summary", "notify_when_idle", "type", "recipient", "content"))

#: What the harness's own listing calls a SESSION of the owner's - on this machine, in the cloud, or reached
#: by Remote Control. A subagent this session spawned is none of these: messaging it resumes it, a launch.
SESSION_KINDS = ("interactive", "cloud", "Remote Control")

#: One row of that listing: `  <name> [<ref>]  ·  <kind>  ·  ...`.
_LISTED = re.compile("^[ \t]+(?P<name>\\S.*?) \\[(?P<ref>[0-9a-f]+)\\][ \t]+·[ \t]+"
                     "(?P<kind>[^·\n]+?)[ \t]*(?:·|$)", re.M)


def listed_sessions(rows):
    """[(name, ref, kind)] from the LATEST peer listing the harness wrote in this transcript: the result of
    a `ListAgents` call, and of no other tool. Text that only looks like a listing - a file shown by `cat`,
    a message quoted back - lists nothing. Empty when there is no listing, which leaves every message a
    launch: a session nobody listed is not known to be the owner's.

    EVERY ROW, NOT ONE PER NAME. Two sessions may share a name, and the harness then asks for the ref. The
    first cut returned a dict keyed by name, so the later row replaced the earlier: a listing holding
    "Twin [60d91b]" and "Twin [35cfc3]" kept only the second, and a message to the first still
    needed a code (the Bakugan session, 2026-10-09)."""
    asked, latest = set(), None
    for row in rows or ():
        message = row.get("message") if isinstance(row, dict) else None
        parts = message.get("content") if isinstance(message, dict) else None
        for part in parts if isinstance(parts, list) else ():
            if not isinstance(part, dict):
                continue
            if part.get("type") == "tool_use" and part.get("name") == "ListAgents":
                asked.add(part.get("id"))
            elif part.get("type") == "tool_result" and part.get("tool_use_id") in asked:
                latest = part.get("content")
    if isinstance(latest, list):
        latest = "\n".join(str(p.get("text") or "") for p in latest if isinstance(p, dict))
    return [(m.group("name"), m.group("ref"), m.group("kind").strip())
            for m in _LISTED.finditer(latest if isinstance(latest, str) else "")]


def message_recipient(payload):
    """The name a call hands words to, when it ONLY hands words to a recipient by name - the shape
    `message_to_session` asks of a SendMessage - or '' for any other call. ONE shape test: the guard that
    tells a session to list its sessions before it asks the owner for a code asks this, not a copy."""
    given = payload.get("tool_input") if isinstance(payload, dict) else None
    if not isinstance(given, dict) or not set(given) <= MESSAGE_ONLY:
        return ""
    to, words = given.get("to"), given.get("message")
    if not (isinstance(to, str) and to.strip() and isinstance(words, str)):
        return ""
    to = to.strip()
    # the harness's own copies must say the same thing, or a listed name fronts for another recipient
    if given.get("type", "message") != "message" or str(given.get("recipient", to)).strip() != to:
        return ""
    return to


def replied_to_session(rows, to):
    """Is `to` the address of a running SESSION that sent this one a message - as the harness delivered
    it: a `queued_command` attachment whose origin is a peer named by its session (`fromSession`) and by
    that address (`from`)?

    THE HARNESS'S OWN WAY TO REPLY. SendMessage says: to reply to an incoming message, copy its `from`
    attribute as `to`. That address is in no listing, so a reply read as a launch. Measured 2026-10-10 on
    DF Engine: a reply to the session merging every open branch, sent the way the tool documents, was
    refused for an owner's code, while the same words to the name the listing shows went through.

    ONLY WHAT THE HARNESS WROTE. An attachment row is the harness's; the same text in this session's own
    message or in a tool result is not a delivery. A subagent's hand-back names `senderTaskId`, and a
    message to it resumes it - a launch - so a delivery naming one is never a session's."""
    for row in rows or ():
        if not isinstance(row, dict) or row.get("type") != "attachment":
            continue
        held = row.get("attachment")
        if not isinstance(held, dict) or held.get("type") != "queued_command":
            continue
        origin = held.get("origin")
        if (isinstance(origin, dict) and origin.get("kind") == "peer"
                and isinstance(origin.get("fromSession"), str) and origin["fromSession"].strip()
                and not origin.get("senderTaskId") and origin.get("from") == to):
            return True
    return False


def message_to_session(payload, rows):
    """True when this call ONLY hands words to a session of the owner's that the harness's latest listing
    names, by its name or by its name and ref.

    A MESSAGE TO A SESSION ALREADY RUNNING STARTS NO AGENT (the owner, 2026-10-09: "it defeats the point of
    me telling you to stop making me a messenger if i have to send a message (code) for every message
    anyway"). `launches` counts a brief handed on as a launch, which is right for a subagent resumed or a
    routine fired, and it put a one-time code in front of every word one of the owner's sessions said to
    another. The session spends its own turn either way; the guards that weigh a launch weigh this as none."""
    given = payload.get("tool_input") if isinstance(payload, dict) else None
    # THE SAME ACT THROUGH THE OTHER DOOR. The session tools message a session by its id, and that
    # door was a launch whatever the session: measured 2026-10-09, every message this session sent
    # one of the owner's running sessions asked the owner for a code, while the same words through
    # `SendMessage` asked for none. A guard keyed to one tool's name is the class one level down.
    if (isinstance(given, dict) and REMOTE_MESSAGE.search(str(payload.get("tool_name") or ""))
            and set(given) <= REMOTE_MESSAGE_ONLY):
        session = given.get("session_id")
        return (isinstance(session, str) and session.startswith("session_")
                and isinstance(given.get("message"), str)
                and session_is_live(rows, session, payload.get("transcript_path")))
    to = message_recipient(payload)
    if not to:
        return False
    if replied_to_session(rows, to):
        return True
    listed = listed_sessions(rows)
    for name, ref, kind in listed:
        if to == "%s [%s]" % (name, ref):
            return kind in SESSION_KINDS
    # a bare name is enough only where the harness itself lets it be: one row carries it
    same = [kind for name, _ref, kind in listed if name == to]
    return len(same) == 1 and same[0] in SESSION_KINDS


#: The session tools' own door to a running session - a message by its id - and the keys of a call
#: that only hands it words: which session, what, and how soon. A Slack thread, an attachment or a
#: role-addressed delivery is more than a message to a session the listing names.
REMOTE_MESSAGE = re.compile(r"(?:^|__)send_message$")
REMOTE_MESSAGE_ONLY = frozenset(("session_id", "message", "priority"))

#: The tools whose results list sessions by id - the session tools' own listing, never a result an
#: agent could shape, like a file it wrote and read back.
SESSION_LISTING = re.compile(r"(?:^|_)(?:list_sessions|get_session)$")

#: How the harness records a result too large for the transcript: the path it saved it to.
SAVED_RESULT = re.compile(r"Full output saved to:\s*(\S+)")


def result_text(content, transcript_path=None):
    """A tool result's text - the harness's saved copy when it was too large to keep inline, read
    only from beside this transcript, where no agent may write."""
    import request_ledger
    text = request_ledger.result_text(content)
    saved = SAVED_RESULT.search(text)
    if saved and transcript_path:
        folder = os.path.splitext(os.path.abspath(transcript_path))[0]
        path = os.path.abspath(saved.group(1))
        if path.startswith(folder + os.sep):
            try:
                with open(path, encoding="utf-8", errors="replace") as handle:
                    return handle.read().replace('\\"', '"')
            except OSError:
                return text
    return text


def listed_session(rows, session, transcript_path=None):
    """What the LATEST session listing in this transcript says about `session` - its record, up to
    the next session's - or None when no listing names it. One reader, for whether it is live
    (`session_is_live`) and which repositories it works (`guard_agent_edits`)."""
    import request_ledger
    tools, found = {}, None
    for row in rows or []:
        for part in request_ledger.parts(row):
            if part.get("type") == "tool_use":
                tools[part.get("id")] = str(part.get("name") or "")
            elif (part.get("type") == "tool_result"
                  and SESSION_LISTING.search(tools.get(part.get("tool_use_id"), ""))):
                text = result_text(part.get("content"), transcript_path)
                at = re.search(r'"id"\s*:\s*"%s"' % re.escape(session), text)
                if not at:
                    continue
                rest = text[at.end():]
                ends = re.search(r'"id"\s*:\s*"session_', rest)
                found = rest[:ends.start()] if ends else rest
    return found


def session_is_live(rows, session, transcript_path=None):
    """Does the latest listing name `session` with a status, and one that is not archived? An
    archived session is no running writer; a record with no status cannot be told live."""
    record = listed_session(rows, session, transcript_path)
    status = re.search(r'"session_status"\s*:\s*"([A-Z_]+)"', record or "")
    return bool(status) and status.group(1) != "SESSION_STATUS_ARCHIVED"


#: The program that starts a Claude session from a command, and the packages a runner fetches it as.
_CLI = re.compile(r"^claude(?:-code)?$", re.I)
_CLI_PACKAGE = re.compile(r"@anthropic-ai/claude-(?:code|agent-sdk)\b", re.I)
_RUNNERS = frozenset(("npx", "bunx", "pnpx", "pnpm", "yarn", "npm", "bun", "deno"))


def cli_sessions(payload):
    """Every command in this call that runs the Claude CLI, as text - by the PROGRAM, through every
    wrapper `runs` peels. Measured 2026-10-07: nothing read `claude -p "<brief>"` as a launch, and
    it starts a whole session on the same plan; `claude config set` writes the owner's settings.
    Either way the owner's, so every run of it but `--version` and `--help` is counted. What a
    program does inside a script - the Agent SDK imported - no hook reads, and that is said here
    rather than implied."""
    out = []
    for command in commands(payload):
        for rest, _background in runs(command):
            if not rest:
                continue
            words = [w for w, _typed in rest]
            program = program_of(words[0])
            said = " ".join(words)
            if _CLI.match(program):
                if all(w in ("--version", "-v", "--help", "-h") for w in words[1:]) and words[1:]:
                    continue
                out.append(said)
            elif program in _RUNNERS and _CLI_PACKAGE.search(said):
                out.append(said)
    return out


def alive(pid, started_epoch=None):
    """Whether `pid` is a live process - and, given the `started_epoch` a lock recorded beside it, whether it is still
    the process that wrote the lock. ALIVE whenever it cannot tell. THE ONE PROBE.

    A PID IS A NUMBER THE OPERATING SYSTEM REUSES. Measured on DF Engine, 2026-09-08 to 09-22: four locks asked only
    "is that pid alive?" when the question was "is that still the process that wrote this?" - one cleared a suite lock
    held by a live run, two refused to start over recycled pids, one reported a dead page server as serving. Its
    `tools/audit/identity.py` then refused this bundle's own sweep at install, 2026-10-09, over two such locks.
    """
    return present(pid) and same_process(pid, started_epoch)


def process_started_at(pid):
    """Epoch seconds at which the process holding `pid` began, or None if it cannot be asked - never a guess.

    NONE IS THE ONLY FAILURE ANSWER: a process this account may not open is UNKNOWN, not somebody else's (DF Engine
    read ACCESS_DENIED as "not ours" and called its own running sweep finished). No subprocess: `GetProcessTimes`
    through ctypes on Windows, `/proc/<pid>/stat` on Linux; elsewhere None, so a reader falls back to presence alone.
    """
    import os
    import time
    try:
        pid = int(pid)
    except (TypeError, ValueError):
        return None
    if pid <= 0:
        return None
    if os.name == "nt":
        try:
            import ctypes
            from ctypes import wintypes
            handle = ctypes.windll.kernel32.OpenProcess(0x1000, False, pid)
            if not handle:
                return None
            try:
                created = wintypes.FILETIME()
                rest = (wintypes.FILETIME(), wintypes.FILETIME(), wintypes.FILETIME())
                if not ctypes.windll.kernel32.GetProcessTimes(
                        handle, ctypes.byref(created), ctypes.byref(rest[0]),
                        ctypes.byref(rest[1]), ctypes.byref(rest[2])):
                    return None
                ticks = (created.dwHighDateTime << 32) | created.dwLowDateTime
                # FILETIME counts 100ns intervals from 1601-01-01; 11644473600 seconds to the epoch.
                return ticks / 1e7 - 11644473600.0
            finally:
                ctypes.windll.kernel32.CloseHandle(handle)
        except Exception:                                        # noqa: BLE001 - None is "cannot ask"
            return None
    try:
        with open("/proc/%d/stat" % pid, encoding="utf-8") as handle:
            fields = handle.read().rsplit(")", 1)[-1].split()
        with open("/proc/uptime", encoding="utf-8") as handle:
            uptime = float(handle.read().split()[0])
        return time.time() - uptime + float(fields[19]) / os.sysconf("SC_CLK_TCK")
    except (OSError, ValueError, IndexError, AttributeError):
        return None


def same_process(pid, started_epoch):
    """Is `pid` still held by the process that recorded `started_epoch`? YES when either side is unknown - this decides
    whether a lock is released, and the flattering error is the one that must not happen."""
    began = process_started_at(pid)
    if began is None or not started_epoch:
        return True
    try:
        return abs(float(began) - float(started_epoch)) < 2.0
    except (TypeError, ValueError):
        return True


def present(pid):
    """Whether `pid` is a live process, answering ALIVE whenever it cannot tell. `alive` asks this and then whether it
    is still the process a lock recorded.

    `os.kill(pid, 0)` IS NOT A PROBE ON WINDOWS. Python routes it through
    `OpenProcess(PROCESS_TERMINATE)` for every signal, so a process this account may not open for
    termination raises `[WinError 87]` - measured on 2026-09-08 against the process table, where it
    called three live processes dead, one of them the sweep runner. And on POSIX a process another
    account owns raises a PERMISSION error, which is not an absence either: two guards each kept a
    copy of this function, and one of them read that error as a dead process.

    UNCERTAINTY IS ALIVE. Every answer short of "the operating system says this pid does not
    exist" counts as running. A pid that is not a positive number is no process.
    """
    import os
    try:
        pid = int(pid)
    except (TypeError, ValueError):
        return False
    if pid <= 0:
        return False
    if os.name != "nt":
        # THE POSIX ANSWER, inside the branch that asked the platform: `os.kill(pid, 0)` answers
        # "no such process" for a pid that is gone, and anything else - a permission error above
        # all, the answer for a live process another account owns - is not an absence. It was a
        # function of its own so a test could ask it on any machine, and then anything could call
        # it on Windows, where the same call reports live processes dead; the test patches
        # `os.name` and asks `alive` instead.
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return False
        except OSError:
            return True                      # permission, or anything else: not an absence
        return True
    import ctypes
    kernel32 = ctypes.windll.kernel32
    handle = kernel32.OpenProcess(0x1000, False, pid)      # QUERY_LIMITED_INFORMATION
    if not handle:
        # 87 is ERROR_INVALID_PARAMETER - Windows' answer for a pid that does not exist. Every
        # other failure is a permission answer, and permission-denied is not absence.
        return kernel32.GetLastError() != 87
    code = ctypes.c_ulong()
    ok = kernel32.GetExitCodeProcess(handle, ctypes.byref(code))
    kernel32.CloseHandle(handle)
    if not ok:
        return True                          # could not read the exit code: not an absence
    return code.value == 259                 # STILL_ACTIVE


#: WHERE A PROJECT NAMES THE SCRIPTS THAT LEAVE DAMAGE WHEN KILLED MID-RUN - one path per line,
#: optionally followed by the sub-command that does the damage (`tools/audit/sweep.py run`).
#: ADAPT.md promised this file for months while no hook read it: the list was hardcoded to the
#: source project, so pointed at a codebase whose sweep is `tools/mutate2.py` the guard allowed
#: the exact pipe it exists to refuse. And two hooks here each kept their OWN list - one knew two
#: scripts, the other six - which is one rule with two spellings.
DESTRUCTIVE_FILE = "destructive_scripts.txt"


def sub_matches(rest, sub):
    """Does the argv after a script (`rest`) select the damaging sub-command `sub`?

    `sub` is None (every invocation), a word (`run`), or alternatives joined by `|`, where `-`
    means INVOKED WITH NO SUB-COMMAND AT ALL - a tool whose default verb is the damaging one
    (`plants.py` with no argument runs `verify`, which plants defects) is otherwise a guard with
    a hole exactly the size of typing less.
    """
    if sub is None:
        return True
    choices = sub.split("|")
    if not rest:
        return "-" in choices
    return rest[0] in choices


def destructive_scripts(default, folder=None):
    """[(script path, sub-command or None)] - from the project's file, else `default`.

    A file that exists and names NOTHING is an answer - the project says no script is
    destructive - and is honoured. A file that cannot be READ is not: it falls back to the
    default rather than to an empty list, because an unreadable roster read as empty is a guard
    that is off.
    """
    import os
    folder = folder or os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(folder, DESTRUCTIVE_FILE)
    if not os.path.exists(path):
        return [(script, None) for script in default]
    try:
        with open(path, encoding="utf-8") as handle:
            lines = handle.read().splitlines()
    except (OSError, UnicodeDecodeError):
        return [(script, None) for script in default]
    found = []
    for line in lines:
        words = line.split("#", 1)[0].split()
        if words:
            found.append((words[0].replace(chr(92), "/"), words[1] if len(words) > 1 else None))
    return found


def blind_refusal(guard, trouble, bypass_name):
    """What a guard says when it could not read part of the input it was asked about.

    The alternative is to read an unreadable command as no command, which is the shape that
    reports a clean negative: every probe has an answer for "it is not there" and an answer for
    "I could not look", and code that collapses the second into the first is wrong in the
    direction nobody checks.
    """
    return (policy(guard) + "\n"
            "This tool call carries something that may be a shell command and could not be "
            "read (%s), so this guard cannot tell whether what it refuses is in there.\n\n"
            "AN UNMEASURABLE QUANTITY IS NOT A SMALL ONE. A guard that reads an unreadable "
            "input as an empty one is not a guard - it is a guard that is off, and looks "
            "exactly like one that passed.\n\n"
            "Re-issue the call with the command as plain text, or set %s=1 for this session "
            "to accept it unchecked.\n" % (trouble, bypass_name))
