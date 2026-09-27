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
import sys

#: This folder, as a project path: `.claude/<the folder this file sits in>`.
FOLDER = ".claude/" + os.path.basename(os.path.dirname(os.path.abspath(__file__)))


def policy(path):
    """`BLOCKED BY PROJECT POLICY (<this folder>/<that file>).` for the guard at `path`."""
    return "BLOCKED BY PROJECT POLICY (%s/%s)." % (FOLDER, os.path.basename(path))


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
    import io
    try:
        with io.open(os.path.join(root, lock_name), encoding="utf-8") as handle:
            lines = handle.read().splitlines()
    except OSError:
        return ""
    named = (lines[0] if lines else "").strip().replace(chr(92), "/")
    if os.path.isabs(named):
        named = os.path.relpath(named, root).replace(os.sep, "/")
    # THE PLANT'S OWN TEST judges the planted code on purpose: its runner names the file in the
    # environment (`bundle_rules.PLANTED`, read here without importing the judge).
    import bundle_env
    if named and named == os.environ.get(bundle_env.name("PLANTED"), "").replace(chr(92), "/"):
        return ""
    return named if named.startswith(FOLDER + "/") else ""

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

#: `VAR=value cmd` - the prefix is not the program.
_ENV_PREFIX = re.compile(r"^\s*(?:[A-Za-z_][A-Za-z_0-9]*=\S*\s+)+")


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
    TYPED keeps its backslashes: a POSIX reading turns an unquoted `C:\\Users\\x` into `C:Usersx`,
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


def _launch_shaped(node):
    return (isinstance(node, dict) and isinstance(node.get("prompt"), str)
            and ("subagent_type" in node or "description" in node))


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

    try:
        walk((payload or {}).get("tool_input"), 0)
    except AttributeError:
        return []
    return found


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
