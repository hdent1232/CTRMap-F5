#!/usr/bin/env python3
"""A RULE ABOUT A COMMAND OR A COMMIT IS ASKED BEFORE THE COMMAND RUNS - and the gate cannot be skipped.

THREE THINGS, all at the point of action rather than after it:

1. EVERY `AT_COMMAND` RULE, for every command in the call. A rule whose subject is an ACT - "a
   mutation sweep of a later layer while an earlier one is unfinished", "a suite runner that is
   not sandboxed" - is decidable from the command before it runs, and asking it inside the tool
   that performs the act is one tool from bypassed: the same act, started another way, walks past.

2. EVERY `AT_COMMIT` RULE, for the message of every `git commit`, BEFORE GIT IS STARTED. The
   git `commit-msg` hook asks the same rules, but only after `pre-commit` has run every ratchet
   in the tree - minutes, on the project this was built for - to reject a message that was
   wrong before anything ran. And a message this cannot READ is refused rather than waved
   through: a query that cannot read its subject must not report it absent.

3. THE GATE CANNOT BE SKIPPED. `git commit --no-verify` does not run the git hooks at all, and
   neither does a commit made while `core.hooksPath` points somewhere else. Every rule the commit
   gate asks is off for that commit, and nobody bypassed anything a guard could see - a flag was
   passed. So a commit that would skip `HOOKS_DIR` is refused here, and so is any command that
   repoints `core.hooksPath`.

WHERE IS PART OF THE COMMAND. Measured 2026-09-23: every commit was judged by THIS project's rules
against THIS project's files, wherever it ran. A commit in a linked worktree of the same
repository was refused because the worktree's own new files were not in the main tree; a commit
in a scratch repository in a temp folder was refused for lacking this project's trailer; and `-F
msg.txt` was read beside this project's root while git reads it beside the directory it runs in -
so the message judged could be a different file's. A commit is judged by the repository it lands
in: this one here, a linked worktree of this one by ITS OWN files and rules in a child process
(its modules share names with these, and one process cannot hold both), another repository not at
all. Where the directory cannot be told - a `cd` after `||`, a variable, a group - it is judged as
this one, because a commit nobody can place is judged as the one it could cost most.

GIT BEHIND A WRAPPER IS STILL GIT. Measured the same day: `--no-verify` was refused as
`git commit --no-verify` and let through as `& git`, `env git`, `bash -c "git ..."`, `powershell
-Command "..."`, `cmd /c`, `iex`, `(git ...)` and `{ git ...; }` - sixteen spellings of one act,
fifteen of them visible in the call's own text. Every one is read here now, and so is an alias.
What cannot be read at all - git run from inside a script - is not claimed here: a hook reads the
call, and the call does not say.

The rules are found by `bundle_rules` - discovered from the markers checkers declare, never
listed. This file owns only the shape of a git command.
"""
import base64
import io
import json
import os
import re
import shlex
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_rules      # noqa: E402  - the one implementation of asking a rule
import bundle_shell      # noqa: E402  - the call, found by shape

LF = chr(10)

#: The directory `core.hooksPath` must name for a commit to be gated. A project without it has no
#: git gate for this to protect, and is not refused for lacking one.
HOOKS_DIR = ".githooks"

#: Adapted per project: see ADAPT.md.
ADAPT = ("HOOKS_DIR",)

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

#: The shortest abbreviation git accepts for `--no-verify` on a commit: `--no-ver` is ambiguous
#: with `--no-verbose`, so git itself refuses anything shorter.
SHORTEST_NO_VERIFY = "--no-veri"

#: git's own options that take a value and come BEFORE the subcommand.
_GLOBAL_WITH_VALUE = ("-C", "-c", "--git-dir", "--work-tree", "--namespace", "--exec-path",
                      "--config-env")

#: Environment that rewrites git's configuration for one command - `core.hooksPath` among it.
_CONFIG_ENV = re.compile(r"GIT_CONFIG_(PARAMETERS|COUNT|KEY_\d+|VALUE_\d+|GLOBAL|SYSTEM)",
                         re.I)

#: Environment that moves the repository a git command acts on away from where it runs.
_PLACE_ENV = re.compile(r"\bGIT_(?:DIR|WORK_TREE|COMMON_DIR)\s*=", re.I)

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

#: Commands that move where the NEXT command runs, and those that move it back somewhere unknown.
_MOVES = frozenset(("cd", "chdir", "pushd", "set-location", "sl", "push-location"))
_UNMOVES = frozenset(("popd", "pop-location"))

#: git's own commands. An alias cannot shadow one, so only another word is looked up as an alias.
_BUILTIN = frozenset((
    "add am apply archive bisect blame branch bundle cat-file check-ignore checkout cherry "
    "cherry-pick clean clone commit config count-objects describe diff diff-files diff-index "
    "diff-tree fetch for-each-ref format-patch fsck gc grep hash-object help init log ls-files "
    "ls-remote ls-tree merge merge-base mktree mv notes pull push range-diff rebase reflog remote "
    "repack replace reset restore rev-list rev-parse revert rm shortlog show show-ref "
    "sparse-checkout stash status submodule switch symbolic-ref tag update-index update-ref var "
    "verify-commit version whatchanged worktree write-tree").split())

_HEREDOC_BODY = re.compile(r"\$\(\s*cat\s*<<-?\s*['\"]?(\w+)['\"]?\s*\n(.*?)\n\s*\1\s*\)",
                           re.S)

_DRIVE = re.compile(r"^/([A-Za-z])(?=/|$)")


def tokens(command):
    """The words of one command, quotes honoured; None when it cannot be split."""
    held = pairs(command)
    return None if held is None else [word for word, _typed in held]


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
    text = bundle_shell.program(command)
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


def git_call(words):
    """(global option words, subcommand, rest) for a `git` command, or None when it is not one."""
    if not words:
        return None
    if program_of(words[0]) != "git":
        return None
    index = 1
    options = []
    while index < len(words):
        word = words[index]
        if word in _GLOBAL_WITH_VALUE and index + 1 < len(words):
            options += [word, words[index + 1]]
            index += 2
            continue
        if word.startswith("-"):
            options.append(word)
            index += 1
            continue
        return options, word, words[index + 1:]
    return options, "", []


def skips_the_gate(options, rest):
    """Why this `git commit` would not run the git hooks, or ''."""
    for pair in zip(options, options[1:]):
        if pair[0] in ("-c", "--config-env") and pair[1].lower().replace(
                " ", "").startswith("core.hookspath"):
            return "`%s %s` points the hooks somewhere else for this commit" % pair
    for word in options:
        low = word.lower()
        if low.startswith("-ccore.hookspath") or (low.startswith("--config-env")
                                                  and "hookspath" in low):
            return "`%s` points the hooks somewhere else for this commit" % word
    for word in rest:
        if word == "--":
            break
        # ANY UNAMBIGUOUS PREFIX. git accepts `--no-veri` for `--no-verify` - the cheapest way past
        # a check that knew only the whole word. `--no-ver` is ambiguous with `--no-verbose` and
        # git refuses it itself, so the shortest spelling that skips the hooks is nine letters.
        if len(word) >= len(SHORTEST_NO_VERIFY) and "--no-verify".startswith(word):
            return "`%s` is `--no-verify`, which runs no git hook at all" % word
        if re.match(r"^-[A-Za-z]*n[A-Za-z]*$", word) and not word.startswith("--"):
            return "`%s` includes `-n`, which is `--no-verify` for a commit" % word
    return ""


def _read_file(where, name):
    if not os.path.isabs(name) and where is None:
        return None
    path = name if os.path.isabs(name) else os.path.join(where, name)
    try:
        with io.open(path, encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return None


def _git_text(where, args):
    try:
        done = subprocess.run(["git"] + args, cwd=where, capture_output=True, text=True,
                              timeout=30, creationflags=NO_WINDOW)
    except (OSError, subprocess.SubprocessError):
        return None
    return done.stdout if done.returncode == 0 else None


#: The short options of `git commit` that take a VALUE. Any other letter in a cluster is a flag.
_VALUED = "mFCct"


def spread(words):
    """Clustered short options spread out the way git reads them: `-qm msg` is `-q -m msg`.

    Measured 2026-09-23: `git commit -qm "..."` - valid git - was refused as having no message,
    because only `-m` standing alone was read. A guard that refuses honest work gets switched off.
    """
    out = []
    for word in words:
        if len(word) > 2 and word[0] == "-" and word[1].isalpha() and word[1] not in _VALUED:
            flags = []
            for at, char in enumerate(word[1:], 1):
                if char in _VALUED:
                    flags.append("-" + char)
                    if word[at + 1:]:
                        flags.append(word[at + 1:])
                    break
                if not char.isalpha():
                    flags = None
                    break
                flags.append("-" + char)
            if flags:
                out.extend(flags)
                continue
        out.append(word)
    return out


def message_of(where, rest):
    """(message, why unreadable) for a `git commit`'s arguments. Exactly one is set.

    `where` is the directory git runs in - AFTER any `-C` - because that is where git reads
    `-F` from. None when it cannot be told, and then a relative message file cannot be found.
    """
    rest = spread(rest)
    parts, reuse, amend, edits = [], None, False, True
    index = 0
    while index < len(rest):
        word = rest[index]
        value = None
        for short, long_ in (("-m", "--message"), ("-F", "--file"), ("-C", "--reuse-message"),
                             ("-c", "--reedit-message"), ("-t", "--template")):
            if word in (short, long_):
                value = rest[index + 1] if index + 1 < len(rest) else None
                index += 1
            elif word.startswith(long_ + "="):
                value = word[len(long_) + 1:]
            elif word.startswith(short) and len(word) > 2 and not word.startswith("--"):
                value = word[2:]
            else:
                continue
            if value is None:
                return None, "`%s` names no value" % word
            if short == "-m":
                body = _HEREDOC_BODY.search(value)
                if body:
                    value = body.group(2)
                elif "$(" in value or "`" in value:
                    return None, ("the message is built by the shell (%r), so it cannot be read "
                                  "before the command runs - write it to a file and pass `-F`"
                                  % value[:40])
                parts.append(value)
            elif short == "-F":
                if value == "-":
                    return None, "the message comes from standard input"
                if where is None and not os.path.isabs(value):
                    return None, ("the message file %s is relative, and where this commit runs "
                                  "cannot be told - name it by its full path" % value)
                text = _read_file(where, value)
                if text is None:
                    return None, ("the message file %s does not exist yet - write it first, "
                                  "then commit with `-F`" % value)
                parts.append(text)
            elif short in ("-C", "-c"):
                reuse = value
            else:
                return None, "`--template` opens an editor, and an editor's text is not here"
            break
        if word == "--amend":
            amend = True
        if word == "--no-edit":
            edits = False
        index += 1
    if parts:
        return (LF + LF).join(p.strip(LF) for p in parts) + LF, ""
    if reuse is not None or (amend and not edits):
        text = _git_text(where, ["log", "-1", "--format=%B", reuse or "HEAD"]) if where else None
        if text is None:
            return None, "the message to be reused could not be read from git"
        return text, ""
    if amend:
        return None, "`--amend` without `-m`, `-F` or `--no-edit` opens an editor"
    return None, "no `-m` or `-F`, so git would open an editor, and an editor's text is not here"


#: git commands that MAKE COMMITS WITHOUT RUNNING `commit-msg` - measured 2026-09-24, one
#: replayed commit each: `rebase (pick):`, `cherry-pick:`, `revert:` and `am:` in the reflog, and
#: the hook's log silent for every one. So they carry no gate stamp, and the owner's rule of that
#: day is that work lands through commits and merges, which the gate sees.
_UNGATED = ("rebase", "cherry-pick", "revert", "am")

#: What each may still be asked to do: stop, which makes nothing - or, for the two that can,
#: apply without committing, so the commit that follows goes through the gate and is stamped.
_STOPS = ("--abort", "--quit", "--show-current-patch")
_UNCOMMITTED = {"cherry-pick": ("-n", "--no-commit"), "revert": ("-n", "--no-commit")}


def past_the_gate(sub, rest):
    """Why this git command would make a commit the gate never sees, or ''."""
    words = spread([w for w in rest if w != "--"])
    if sub == "pull":
        rebases = any(w in ("-r", "--rebase") or (w.startswith("--rebase=")
                                                   and w != "--rebase=false") for w in words)
        return ("`git pull --rebase` replays commits git runs no `commit-msg` for, so they carry "
                "no gate stamp - pull with a merge instead" if rebases else "")
    if sub not in _UNGATED or any(w in _STOPS for w in words):
        return ""
    if any(w in _UNCOMMITTED.get(sub, ()) for w in words):
        return ""
    way = ("; `git %s --no-commit`, then `git commit`, is the stamped way to do it" % sub
           if sub in _UNCOMMITTED else "")
    return ("`git %s` makes commits git runs no `commit-msg` for, so they carry no gate stamp "
            "and would refuse every act after them - land the work through a commit or a "
            "merge%s" % (sub, way))


def hooks_path_problem(root):
    """Why a commit here would not be gated by `HOOKS_DIR`, or ''."""
    if not os.path.isdir(os.path.join(root, HOOKS_DIR)):
        return ""
    held = _git_text(root, ["config", "--get", "core.hooksPath"])
    held = (held or "").strip().replace(chr(92), "/").rstrip("/")
    want = HOOKS_DIR.replace(chr(92), "/").rstrip("/")
    if held in (want, "./" + want, os.path.join(root, want).replace(chr(92), "/")):
        return ""
    return ("`core.hooksPath` is %r, not %r, so git would run none of the project's hooks. "
            "`git config core.hooksPath %s` installs them." % (held or "(unset)", want, want))


# ----------------------------------------------------------------------------- where it runs

def directory(where, word, typed=None):
    """The directory a `cd` or `-C` word names from `where`, or None when it cannot be known.

    Never a guess: a variable, a glob, a path that is not there, or a relative path from an
    unknown place is None - and None is judged as this project, never as somewhere else.
    """
    for candidate in (word, typed):
        if not candidate or any(char in candidate for char in "$`%*?"):
            continue
        path = os.path.expanduser(candidate) if candidate.startswith("~") else candidate
        if os.name == "nt":
            path = _DRIVE.sub(lambda match: match.group(1) + ":", path)
        if not os.path.isabs(path):
            if where is None:
                continue
            path = os.path.join(where, path)
        if os.path.isdir(path):
            return os.path.normpath(path)
    return None


def moved(where, stage):
    """(True, the directory now) when this stage is a `cd`-like command; (False, where) if not."""
    if not stage:
        return False, where
    head = stage[0][0].lower()
    if head in _UNMOVES:
        return True, None
    if head not in _MOVES:
        return False, where
    named = [(w, t) for w, t in stage[1:]
             if w.lower() not in ("-l", "-p", "--", "-path", "-literalpath")]
    if len(named) != 1:
        return True, None
    return True, directory(where, named[0][0], named[0][1])


def _norm(path):
    return os.path.normcase(os.path.realpath(path))


def repository(place):
    """(top, common git directory) of the repository `place` is in, both normalised, or None
    when git cannot say - which is never read as "not a repository"."""
    if place is None:
        return None
    text = _git_text(place, ["rev-parse", "--show-toplevel", "--git-common-dir"])
    lines = (text or "").splitlines()
    if len(lines) < 2:
        return None
    return _norm(lines[0]), _norm(os.path.join(place, lines[1]))


def judged(root, place):
    """Which project a git command running in `place` acts on, laid out as `root` is.

    `root` itself; the same project in a LINKED WORKTREE of this repository, a path of its own,
    whose files and rules are its own; or None for ANOTHER repository, which this project's rules
    have no say over. Where git cannot say, the answer is `root`.
    """
    theirs = repository(place)
    if theirs is None:
        return root
    ours = repository(root)
    if ours is None:
        return None
    if theirs[1] != ours[1]:
        return None
    try:
        inside = os.path.relpath(_norm(root), ours[0])
    except ValueError:
        return root
    target = os.path.normpath(os.path.join(theirs[0], inside))
    return root if _norm(target) == _norm(root) else target


def git_place(where, stage):
    """The directory a git stage acts in, after its `-C`s, or None when git is told another way."""
    index = 1
    while index < len(stage):
        word = stage[index][0]
        if word in ("--git-dir", "--work-tree") or word.startswith(("--git-dir=",
                                                                     "--work-tree=")):
            return None
        if word == "-C" and index + 1 < len(stage):
            where = directory(where, stage[index + 1][0], stage[index + 1][1])
            index += 2
            continue
        if word in _GLOBAL_WITH_VALUE and index + 1 < len(stage):
            index += 2
            continue
        if not word.startswith("-"):
            break
        index += 1
    return where


# ----------------------------------------------------------------------------- what runs

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


def dealiased(place, options, sub, rest, depth=0):
    """(subcommand, rest) with a git alias expanded - or ("!", shell text) for a shell alias."""
    if not sub or sub in _BUILTIN or depth > MAX_NESTING:
        return sub, rest
    value = None
    for option, setting in zip(options, options[1:]):
        if option == "-c" and setting.lower().startswith("alias.%s=" % sub.lower()):
            value = setting.split("=", 1)[1]
    for option in options:
        if option.lower().startswith("-calias.%s=" % sub.lower()):
            value = option.split("=", 1)[1]
    if value is None:
        value = (_git_text(place, ["config", "--get", "alias." + sub]) or "").strip() \
            if place else ""
    if not value:
        return sub, rest
    if value.startswith("!"):
        return "!", value[1:] + "".join(" " + shlex.quote(word) for word in rest)
    words = tokens(value) or []
    if not words:
        return sub, rest
    return dealiased(place, options, words[0], words[1:] + list(rest), depth + 1)


# ----------------------------------------------------------------------------- the judgement

class Call(object):
    """What the judgement of one call shares across its commands and the texts inside them."""

    def __init__(self, root, delegate, tool):
        self.root, self.delegate, self.tool = root, delegate, tool
        self.out = []
        self._found = None

    def rules(self):
        if self._found is None:
            self._found = bundle_rules.rules(self.root)
        return self._found


def start_of(payload, root):
    """Where the call begins: the harness's `cwd` when it names a directory, else `root`."""
    held = payload.get("cwd") if isinstance(payload, dict) else None
    return os.path.normpath(held) if isinstance(held, str) and os.path.isdir(held) else root


def delegated(target, stage, where, tool):
    """The findings of a linked worktree's own rules on a commit made in it, from a child."""
    payload = {"tool_name": tool, "cwd": where,
               "tool_input": {"command": " ".join(shlex.quote(w) for w, _t in stage)}}
    try:
        # `-B`: the child imports this folder's modules, and bytecode written beside them is a
        # file nobody shipped - the bundle's own check refuses a folder carrying it.
        done = subprocess.run([sys.executable, "-B", os.path.abspath(__file__), "--judge", target],
                              input=json.dumps(payload), capture_output=True, text=True,
                              encoding="utf-8", timeout=1200, creationflags=NO_WINDOW)
        found = json.loads(done.stdout) if done.returncode == 0 else None
    except (OSError, subprocess.SubprocessError, ValueError):
        found, done = None, None
    if not isinstance(found, list):
        said = ((done.stderr or done.stdout) if done is not None else "").strip()[-300:]
        return [("worktree", ["the rules of the worktree at %s could not be asked about this "
                              "commit: %s" % (target, said or "no answer")])]
    return [(name, findings) for name, findings in found]


def judge_text(call, text, where, depth, placed=True):
    """Every command in a shell text that begins running in `where` (None: cannot be told).

    `placed` False: the environment this text inherits already decides the repository.
    """
    if depth > MAX_NESTING:
        if re.search(r"\bgit\b", text or "", re.I):
            call.out.append(("gate", ["a command nested %d deep carries `git`, and nothing reads "
                                      "that deep - run it directly" % depth]))
        return
    placed = placed and not _PLACE_ENV.search(text or "")
    pending = False
    for before, command in bundle_shell.separated(text):
        if pending and before != "&&":
            where, pending = None, False
        held = pairs(command) or []
        grouped = any(word in ("(", ")", "{", "}") for word, _typed in held)
        if bundle_shell.NEUTRAL.match(command):
            if _CONFIG_ENV.search(command):
                call.out.append(("gate", ["`%s` rewrites git's configuration for the commands "
                                          "after it - `core.hooksPath` among it" % command[:60]]))
        elif depth == 0:
            said = bundle_rules.ask_command(call.root, command, call.rules())
            call.out += sorted(said.items())
        programs = stages(held)
        for stage in programs:
            stage, moves = peeled(stage)
            is_move, now = moved(where, stage)
            if is_move:
                if len(programs) > 1:
                    now = None
                if before in ("", ";", LF):
                    where = now
                elif before == "&&":
                    where, pending = now, True
                else:
                    where = None
                continue
            judge_stage(call, stage, command, where, placed and not moves, depth)
        if grouped:
            where = None


def judge_stage(call, stage, command, where, placed, depth):
    """One program: a shell handed a command, or git - judged in the repository it acts on.

    `placed` is False when something other than the directory - `GIT_DIR`, `env -C` - decides
    which repository git acts on. Then no `-C` can place it either: `GIT_DIR=<this repository>
    git -C <another> commit` commits HERE.
    """
    inner, unreadable = handed(stage)
    if unreadable:
        call.out.append(("gate", [unreadable]))
        return
    if inner is not None:
        judge_text(call, inner, where, depth + 1, placed)
        return
    found = git_call([word for word, _typed in stage])
    if found is None:
        return
    options, sub, rest = found
    place = git_place(where, stage) if placed else None
    sub, rest = dealiased(place or call.root, options, sub, rest)
    if sub == "!":
        judge_text(call, rest, place, depth + 1, placed)
        return
    if sub == "config" and any("hookspath" in w.lower() for w in rest) and not any(
            w in ("--get", "--get-all", "--list", "-l") for w in rest):
        if judged(call.root, place) is not None:
            call.out.append(("gate", ["`git config` changing `core.hooksPath` turns the commit "
                                      "gate off for every commit after it"]))
        return
    ungated = past_the_gate(sub, rest)
    if ungated:
        if judged(call.root, place) is not None:
            call.out.append(("gate", [ungated]))
        return
    if sub != "commit":
        return
    target = judged(call.root, place)
    if target is None:
        return
    if target != call.root and call.delegate:
        call.out += delegated(target, stage, where, call.tool)
        return
    was = len(call.out)
    judge_commit(call, options, rest, command, place)
    # A REFUSAL SAYS WHERE IT JUDGED. Measured 2026-09-24: `git -C "$W" commit`, in a worktree,
    # was refused as though the worktree's own test class did not exist - true of the MAIN tree,
    # which is where a commit nobody can place is judged, and nothing said that had happened. A
    # note on a refusal, never a refusal of its own: added only when something already refused.
    if place is None and len(call.out) > was:
        call.out.append(("where", ["where this commit runs could not be told from the command - "
                                   "a variable, a group, a `cd` that may not have run, or "
                                   "GIT_DIR - so it was judged as %s. If it lands anywhere "
                                   "else, name that directory literally" % call.root]))


def judge_commit(call, options, rest, command, place):
    """Every rule a `git commit` in this project is asked, before git starts."""
    if _CONFIG_ENV.search(command):
        call.out.append(("gate", ["this commit rewrites git's configuration through the "
                                  "environment, `core.hooksPath` among it"]))
    skipped = skips_the_gate(options, rest)
    if skipped:
        call.out.append(("gate", ["%s. Every rule the commit gate asks would be off for this "
                                  "commit." % skipped]))
    unhooked = hooks_path_problem(call.root)
    if unhooked:
        call.out.append(("gate", [unhooked]))
    message, unreadable = message_of(place, rest)
    if message is None:
        call.out.append(("message", ["the commit message cannot be read before git runs: %s"
                                     % unreadable]))
        return
    said = bundle_rules.ask_command(call.root, message, call.rules(), kind="AT_COMMIT")
    call.out += sorted(said.items())


def problems(payload, root, delegate=True):
    """[(rule, [findings])] for one call. Empty when it may run."""
    call = Call(root, delegate, (payload.get("tool_name") if isinstance(payload, dict)
                                 else None) or "Bash")
    start = start_of(payload, root)
    for line in bundle_shell.commands(payload):
        judge_text(call, line, start, 0)
    return call.out


def refusal(found):
    lines = ["BLOCKED: this command is refused before it runs.", ""]
    for name, findings in found:
        lines.append("  %s" % name)
        lines += ["      %s" % (f,) for f in findings[:6]]
    lines += ["",
              "  A rule about an act is asked before the act, and a commit is asked before git",
              "  starts - not after every ratchet in the tree has run to reach the same answer."]
    return LF.join(lines)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    try:
        payload = json.load(sys.stdin)
    except (ValueError, IOError):
        return 0
    if not isinstance(payload, dict) or not bundle_shell.is_tool_call(payload):
        return 0
    if not bundle_shell.commands(payload):
        return 0
    # `--judge <worktree>`: a linked worktree's own rules, asked by its parent in this process
    # of its own. Its answer goes back as data; the parent decides.
    if argv[:1] == ["--judge"] and len(argv) > 1 and os.path.isdir(argv[1]):
        sys.stdout.write(json.dumps(problems(payload, argv[1], delegate=False)) + LF)
        return 0
    root = bundle_rules.repo_root(__file__)
    if root is None:
        return 0
    found = problems(payload, root)
    if not found:
        return 0
    sys.stderr.write(refusal(found) + LF)
    return 2


if __name__ == "__main__":
    sys.exit(main())
