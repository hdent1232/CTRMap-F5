#!/usr/bin/env python3
"""NEVER EDIT SOURCE WHILE THE SUITE IS RUNNING - refused at the write AND the command, while a run
of THIS tree is live.

CLAUDE.md: *the result is neither the old code nor the new one, and it looks green. Two full runs
were invalidated this way.*

WHAT A RUN IS: a program `bundle_runs.kind` calls a run - a test runner or a build - read as the
PROGRAM, through runners and shells (`bundle_shell.runs`). A runner in watch mode re-runs after
every change and lives for the session by design: it is never held.

WHERE IT RUNS IS PART OF IT. A run measures the tree it runs in: the launching row's `cwd`, then
every `cd` the command makes, a variable it assigned first included. A run in another tree - a
scratch copy, another repository - holds nothing here; a run whose place cannot be read is held,
because a run nobody can place is the one that could cost most.

THREE WITNESSES, because each sees what the others cannot.

    the transcript  every background command this session launched, and its end - read ONLY from
                    the rows the harness writes to say a task ended. Measured 2026-10-07 on this
                    session's transcript: 105 `queue-operation` rows, 39 `queued_command`
                    attachments, 9 user rows whose origin is a task notification, 1 restart
                    notice marked `isMeta`, and the result of each `TaskStop` - which ends the
                    task it stopped and no other. The first version took an end from ANY line, so
                    the same words in a command, an output, a summary or 13 of the assistant's own
                    messages ended a run that was live. Read once: what was read is kept beside
                    the file's size and head, and the next call reads only what was appended.

    the machine     every process of this account whose program is a run and whose working folder
                    is in this tree - a suite in another terminal, another session, a subagent,
                    a command sent to the background with `&`, none of which the transcript
                    shows. The process asking, and every process above it, is the run that asks,
                    not one beside it. A run whose folder cannot be read is held when its command
                    line names this tree, and SAID otherwise - never read as absent.

    a lock          a run that DECLARES itself: a file a project's own runner writes while it
                    measures, listed in `bundle_runs.LOCKS`, naming its process. On Windows the
                    machine cannot place a run whose command line never names the tree, so a
                    battery in the owner's terminal was only said; its lock holds it.

WHAT IS REFUSED while a run of this tree is live: a Write or Edit to a file of this project - a
call carrying content, by shape - and a command unless every program in it only reads, says or
waits, reads git, or runs in another tree; a redirect into this tree is a write whatever the
program. A command that might write is refused because whether a program writes cannot be read
from its name: a plant drive, a commit whose gate drives plants, a formatter, a script.

WHAT IT CANNOT SEE, said rather than implied: a run on another machine or kernel over this tree;
a program that changes its working folder after it starts.

A TRANSCRIPT NOBODY CAN READ IS NOT AN IDLE MACHINE. With the transcript unreadable the machine
still answers; with neither, a write is refused, because unknown is not idle.

THE WAY OUT: wait for the run's notification, or stop the task. The owner's way past:
`bundle_env.name("ALLOW_WRITE_DURING_SUITE")`.
"""
import hashlib
import io
import json
import os
import re
import subprocess
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_env        # noqa: E402  - one PREFIX renames every override
import bundle_rules      # noqa: E402  - where the project is
import bundle_runs       # noqa: E402  - what a run is, one answer
import bundle_shell      # noqa: E402  - the call, and the program it runs, found by shape
import bounded         # noqa: E402  - a deadline ends the child's whole tree
import guard_held_writes  # noqa: E402  - READERS, spelled once

LF = chr(10)
BYPASS = bundle_env.name("ALLOW_WRITE_DURING_SUITE")

#: No console window for a child whose output is captured - Windows opens one for every console
#: process unless told otherwise, and a guard asked at every write would blink one each time.
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

#: Where what was read of each transcript is kept: outside the tree, so keeping it is no change.
STATE_DIR = os.path.join(tempfile.gettempdir(), "bundle-suite-running")

#: The keys a tool that STOPS a background task names it by - by shape, whatever the tool is called.
STOP_KEYS = ("task_id", "shell_id", "bash_id")

#: Programs that only say, wait or move: they write nothing of their own.
QUIET = re.compile(r"^(?:echo|printf|true|false|:|pwd|sleep|wait|which|type|command|test|\[|"
                   r"cd|pushd|popd|set-location|sl|chdir|Write-Output|Write-Host|Start-Sleep|"
                   r"exit|return|break|continue|seq|date|basename|dirname|printenv|whoami|"
                   r"uname|id|hostname|nproc|expr|"
                   # A LOOP'S OWN WORDS, which the reader hands back as programs - measured
                   # 2026-10-07: a bounded wait beside this bundle's own plant drive was refused
                   # as a program called `for`.
                   r"for|select|case|esac|done|fi|"
                   # FILTERS THAT ONLY PRINT: no option of theirs writes a file.
                   r"fold|tr|cut|nl|rev|column|paste|expand|unexpand|fmt|jq|base64)$", re.I)

#: A command a backtick substitution runs - read as a command of its own.
_BACKTICK = re.compile(r"`([^`]*)`")


def backticked(command, shell=None):
    """The commands the backtick substitutions in `command` RUN. PowerShell runs none - a backtick there escapes the
    next character. Bash runs those outside quotes and inside double quotes, and none inside single quotes, which
    quote a backtick literally. MEASURED 2026-10-12: a ledger note in single quotes citing a `python -m unittest` it
    had run was refused beside a live suite as that command. A shell nobody named is read as bash, which runs the
    most; a single quote left open leaves the text whole rather than guessing where it ends."""
    if shell == "powershell":
        return []
    text = command or ""
    masked, quote, at = [], None, 0
    while at < len(text):
        char = text[at]
        if quote is None and char == chr(92) and at + 1 < len(text):
            masked.append(text[at:at + 2])
            at += 2
            continue
        if quote == "'":
            masked.append(char if char == "'" else " ")
            quote = None if char == "'" else quote
        else:
            if char in ("'", chr(34)) and quote is None:
                quote = char
            elif char == quote:
                quote = None
            masked.append(char)
        at += 1
    if quote == "'":
        return _BACKTICK.findall(text)
    return _BACKTICK.findall("".join(masked))

#: git that reads the tree and writes nothing in it.
GIT_READS = re.compile(r"^git (?:-C \S+ )?(?:-c \S+ )*(?:status|diff|log|show|rev-parse|ls-files|"
                       r"ls-tree|blame|grep|cat-file|for-each-ref|describe|shortlog|reflog|"
                       r"rev-list|merge-base|check-ignore|check-attr|config --get|remote -v|"
                       r"branch(?: --list| -a| -r| -v| --show-current)?(?:\s|$)|branch$)")

_BLOCK = re.compile(r"<task-notification>(.*?)</task-notification>", re.S)
_TASK_ID = re.compile(r"<task-id>\s*([^<\s]+)\s*</task-id>")
_STATUS = re.compile(r"<status>\s*([^<\s]+)\s*</status>")
_LAUNCHED_AS = re.compile(r"running in background with ID: ([A-Za-z0-9_-]+)")
_RESTARTED = re.compile(r"\(task ([A-Za-z0-9_-]+)\)")
_ASSIGNED = re.compile(r"(?:^|[\s;&|(])([A-Za-z_][A-Za-z0-9_]*)=('[^']*'|\"[^\"]*\"|[^\s;&|()]+)")


# ----------------------------------------------------------------------------- where a run is

def _real(path):
    return os.path.normcase(os.path.realpath(os.path.abspath(path)))


def inside(path, root):
    real, top = _real(path), _real(root)
    return real == top or real.startswith(top.rstrip(os.sep) + os.sep)


def _expand(word, assigned):
    def value(match):
        name = match.group(1) or match.group(2)
        return assigned.get(name, match.group(0))
    word = re.sub(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)", value, word)
    return os.path.expanduser(word) if word.startswith("~") else word


def _moved(words, where, assigned, shell=None):
    """The folder a `cd` takes the command to, read as `shell` reads a path (`bundle_shell.lands`),
    or None when it cannot be told."""
    target = words[1] if len(words) > 1 else "~"
    return bundle_shell.lands(_expand(target.strip("'" + chr(34)), assigned), where, shell)


def programs(command, cwd, shell=None):
    """[(words or None, where or None)] - every program the command runs, and the folder it runs
    in: None words for a command that cannot be read, None where for a place that cannot be told.
    `shell` is the one that runs the command ('bash', 'powershell', or None for not known)."""
    assigned = {}
    for name, value in _ASSIGNED.findall(command or ""):
        assigned[name] = value.strip("'" + chr(34))
    where, out = cwd, []
    # THE `cd` IS READ HERE, NOT BY `runs`: it skips a move as a neutral command, so no program it
    # hands back says where the next one runs. A move inside a string a shell is handed is not
    # followed - the program after it is placed where the outer command was, which holds more.
    for one in bundle_shell.split_commands(command or ""):
        held = bundle_shell.pairs(bundle_shell.heredoc_split(one)[0])
        first = [(typed if typed is not None else word) for word, typed in held] if held else []
        if first and bundle_shell.program_of(first[0]).lower() in (
                "cd", "pushd", "set-location", "sl", "chdir", "push-location"):
            where = _moved(first, where, assigned, shell)
            continue
        for rest, _background in bundle_shell.runs(one):
            if rest is None:
                out.append((None, where))
                continue
            out.append(([(typed if typed is not None else word) for word, typed in rest], where))
    return out


def runs_here(command, cwd, root, shell=None):
    """[what runs] for every program of the command that measures `root` - a run here, a run whose
    place cannot be told, or a command nobody can read (unknown is not no)."""
    out = []
    for words, where in programs(command, cwd, shell):
        if words is None:
            out.append("a command that cannot be read: %s" % (command or "")[:80])
            continue
        if bundle_runs.kind(words) != "run":
            continue
        if where is None or inside(where, root):
            out.append(" ".join(words)[:120])
    return out


# ----------------------------------------------------------------------------- the transcript

def _text(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return LF.join(_text(p.get("text") if p.get("type") == "text" else p.get("content"))
                       for p in content if isinstance(p, dict))
    return ""


def _parts(row):
    held = row.get("message") if isinstance(row.get("message"), dict) else {}
    content = held.get("content")
    return [p for p in content if isinstance(p, dict)] if isinstance(content, list) else []


def harness_end(row):
    """The text of a row the HARNESS wrote to say a task ended - or None for every other row."""
    kind = row.get("type")
    if kind == "queue-operation" and isinstance(row.get("content"), str):
        return row["content"]
    if kind == "attachment":
        held = row.get("attachment") if isinstance(row.get("attachment"), dict) else {}
        origin = held.get("origin") if isinstance(held.get("origin"), dict) else {}
        if held.get("type") == "queued_command" and origin.get("kind") in (None,
                                                                         "task-notification"):
            return _text(held.get("prompt"))
        return None
    if kind != "user" or row.get("isSidechain") or row.get("isCompactSummary"):
        return None
    origin = row.get("origin") if isinstance(row.get("origin"), dict) else {}
    content = (row.get("message") or {}).get("content") if isinstance(row.get("message"),
                                                                       dict) else None
    if origin.get("kind") == "task-notification" and isinstance(content, str):
        return content
    if row.get("isMeta") and isinstance(content, str) and \
            content.lstrip().startswith("<system-reminder>") and "container was restarted" in content:
        return content
    return None


def fresh_state():
    return {"launched": {}, "task_of": {}, "ended": [], "stops": {}}


def take(state, row):
    """Fold one transcript row into what is known of the session's background tasks."""
    if not isinstance(row, dict):
        return
    said = harness_end(row)
    if said:
        ended = set(state["ended"])
        for block in _BLOCK.findall(said):
            ident, status = _TASK_ID.search(block), _STATUS.search(block)
            if ident and status and status.group(1).lower() not in ("running", "started"):
                ended.add(ident.group(1))
        if "are now stopped" in said and "container was restarted" in said:
            ended.update(_RESTARTED.findall(said))
        state["ended"] = sorted(ended)
    parts = _parts(row)
    for part in parts:
        if part.get("type") != "tool_use":
            continue
        given = part.get("input") if isinstance(part.get("input"), dict) else {}
        if given.get("run_in_background"):
            state["launched"][part.get("id")] = {
                "command": bundle_shell.text({"tool_input": given}), "cwd": row.get("cwd"),
                "tool": part.get("name")}
        stopped = [given[k] for k in STOP_KEYS if isinstance(given.get(k), str)]
        if stopped and not bundle_shell.commands({"tool_input": given}):
            state["stops"][part.get("id")] = stopped[0]
    results = [p for p in parts if p.get("type") == "tool_result"]
    given = row.get("toolUseResult")
    for part in results:
        use = part.get("tool_use_id")
        text = _text(part.get("content"))
        if use in state["launched"] and use not in state["task_of"]:
            task = given.get("backgroundTaskId") if isinstance(given, dict) and \
                len(results) == 1 else None
            found = _LAUNCHED_AS.search(text)
            task = task if isinstance(task, str) else (found.group(1) if found else None)
            if task:
                state["task_of"][use] = task
        if use in state["stops"]:
            task = state["stops"].pop(use)
            if re.search(r"(?:stopped task|No task found with ID):? %s\b" % re.escape(task), text):
                state["ended"] = sorted(set(state["ended"]) | {task})


def _keep_path(transcript):
    key = hashlib.sha256(_real(transcript).encode("utf-8")).hexdigest()[:32]
    return os.path.join(STATE_DIR, key + ".read.json")


def _head(handle):
    handle.seek(0)
    return hashlib.sha256(handle.read(4096)).hexdigest()


def read_state(transcript):
    """What the transcript says of this session's background tasks, read from where the last call
    stopped - or None when the transcript cannot be read. A file that shrank or whose head changed
    is read again from its start; a keep that cannot be read is a cold read."""
    if not transcript:
        return None
    try:
        handle = io.open(transcript, "rb")
    except (OSError, TypeError):
        return None
    with handle:
        size = os.fstat(handle.fileno()).st_size
        head = _head(handle)
        keep = _keep_path(transcript)
        state, offset = None, 0
        try:
            with io.open(keep, encoding="utf-8") as kept:
                held = json.load(kept)
            if held.get("head") == head and 0 <= int(held.get("offset", -1)) <= size and \
                    isinstance(held.get("state"), dict):
                state, offset = held["state"], int(held["offset"])
        except (OSError, ValueError, TypeError, AttributeError):
            state, offset = None, 0
        if state is None or not all(k in state for k in fresh_state()):
            state, offset = fresh_state(), 0
        handle.seek(offset)
        blob = handle.read()
    end = blob.rfind(b"\n") + 1
    for line in blob[:end].splitlines():
        try:
            take(state, json.loads(line.decode("utf-8", "replace")))
        except ValueError:
            continue
    try:
        os.makedirs(STATE_DIR, exist_ok=True)
        temp = "%s.%d.tmp" % (keep, os.getpid())
        with io.open(temp, "w", encoding="utf-8") as out:
            json.dump({"head": head, "offset": offset + end, "state": state}, out)
        os.replace(temp, keep)
    except OSError:
        pass                                  # not kept: the next call reads it again, cold
    return state


def transcript_runs(transcript, root):
    """{task: what runs} for every background run of this tree the transcript has not seen end -
    or None when it cannot be read."""
    state = read_state(transcript)
    if state is None:
        return None
    out = {}
    ended = set(state["ended"])
    for use, launch in state["launched"].items():
        task = state["task_of"].get(use)
        if not task or task in ended:
            continue
        found = runs_here(launch.get("command"), launch.get("cwd") or root, root,
                          bundle_shell.shell_of(launch.get("tool")))
        if found:
            out["task %s" % task] = found[0]
    return out


# ----------------------------------------------------------------------------- the machine

def _ancestors():
    """This process and every process above it, as far as the machine says."""
    out, pid = set(), os.getpid()
    for _step in range(64):
        out.add(pid)
        try:
            with io.open("/proc/%d/status" % pid, encoding="utf-8", errors="replace") as handle:
                ppid = next((int(line.split()[1]) for line in handle
                             if line.startswith("PPid:")), 0)
        except (OSError, ValueError, StopIteration):
            ppid = os.getppid() if pid == os.getpid() else 0
        if ppid <= 1 or ppid in out:
            break
        pid = ppid
    return out


def _linux(root):
    mine, out, unplaced = os.getuid(), [], []
    above = _ancestors()
    for name in os.listdir("/proc"):
        if not name.isdigit() or int(name) in above:
            continue
        base = os.path.join("/proc", name)
        try:
            with io.open(os.path.join(base, "status"), encoding="utf-8", errors="replace") as fh:
                uid = next((int(line.split()[1]) for line in fh if line.startswith("Uid:")), -1)
            if uid != mine:
                continue
            with io.open(os.path.join(base, "cmdline"), "rb") as fh:
                argv = [w.decode("utf-8", "replace") for w in fh.read().split(b"\0") if w]
        except (OSError, ValueError, StopIteration):
            continue                          # gone between the listing and the read
        if not argv or bundle_runs.kind(argv) != "run":
            continue
        said = "pid %s: %s" % (name, " ".join(argv)[:120])
        try:
            where = os.readlink(os.path.join(base, "cwd"))
        except OSError:
            where = None
        if where is not None:
            if inside(where, root):
                out.append(said)
        elif _real(root) in _real_words(argv):
            out.append(said)
        else:
            unplaced.append(said)
    return out, unplaced


def _real_words(argv):
    return {_real(w) for w in argv if os.path.isabs(w)} | {
        _real(os.path.join(w, "")).rstrip(os.sep) for w in argv if os.path.isabs(w)}


def _posix(root):
    """Another POSIX machine: `ps` for the processes, `lsof` for a run's working folder."""
    done = bounded.run(["ps", "-A", "-o", "pid=,ppid=,uid=,args="], capture_output=True,
                       text=True, timeout=20, creationflags=NO_WINDOW)
    if done.returncode != 0:
        raise OSError("ps exited %d" % done.returncode)
    out, unplaced, above = [], [], _ancestors()
    for line in done.stdout.splitlines():
        fields = line.split(None, 3)
        if len(fields) < 4 or not fields[0].isdigit():
            continue
        pid, uid, args = int(fields[0]), int(fields[2]), fields[3]
        argv = args.split()
        if uid != os.getuid() or pid in above or bundle_runs.kind(argv) != "run":
            continue
        said = "pid %d: %s" % (pid, args[:120])
        try:
            found = bounded.run(["lsof", "-a", "-d", "cwd", "-p", str(pid), "-Fn"],
                                capture_output=True, text=True, timeout=20,
                                creationflags=NO_WINDOW)
            where = next((line[1:] for line in found.stdout.splitlines()
                          if line.startswith("n")), None)
        except (OSError, subprocess.SubprocessError):
            where = None
        if where is not None:
            if inside(where, root):
                out.append(said)
        elif _real(root) in args or root in args:
            out.append(said)
        else:
            unplaced.append(said)
    return out, unplaced


def _windows(root):
    """Windows: command lines only - a process's working folder is not something it reports."""
    script = ("Get-CimInstance Win32_Process | Select-Object ProcessId,CommandLine | "
              "ConvertTo-Json -Compress")
    done = bounded.run(["powershell", "-NoProfile", "-Command", script], capture_output=True,
                       text=True, timeout=30, creationflags=NO_WINDOW)
    if done.returncode != 0:
        raise OSError("powershell exited %d" % done.returncode)
    held = json.loads(done.stdout or "[]")
    out, unplaced = [], []
    for entry in held if isinstance(held, list) else [held]:
        args = str((entry or {}).get("CommandLine") or "")
        pid = (entry or {}).get("ProcessId")
        if not args or pid == os.getpid() or bundle_runs.kind(args.split()) != "run":
            continue
        said = "pid %s: %s" % (pid, args[:120])
        (out if root.lower() in args.lower() else unplaced).append(said)
    return out, unplaced


def machine_runs(root):
    """([runs of this tree], [runs whose place cannot be read]) on this machine - or None when the
    machine cannot be asked. Never raises: a witness that crashed is one that could not look."""
    try:
        if sys.platform.startswith("linux") and os.path.isdir("/proc"):
            return _linux(root)
        if os.name == "nt":
            return _windows(root)
        return _posix(root)
    except Exception:                         # noqa: BLE001 - could not look, said by the caller
        return None


# ----------------------------------------------------------------------------- the verdict

def writes_here(payload, root):
    """The project files this call writes content into."""
    if not bundle_shell.writes(payload):
        return []
    out = []
    for target in bundle_shell.paths(payload):
        path = target if os.path.isabs(target) else os.path.join(root, target)
        if inside(path, root):
            out.append(bundle_rules.rel_of(root, path))
    return out


def _redirects_here(words, where, root, shell=None):
    """The redirect target that may land in `root`, or ''. Read as the shell running the command
    reads a path, and refused when ANY reading lands here or none can be had: measured 2026-10-07
    on Windows, `echo x > /c/Users/<you>/src/a.py` from Bash writes this tree, and Python's own
    reading put it at `C:\\c\\Users\\...`, outside it, so the write went ahead beside a live run."""
    for at, word in enumerate(words):
        if re.fullmatch(r"\d*&?>>?[|&]?", word) and at + 1 < len(words):
            target = words[at + 1]
            if word.endswith("&") and target.isdigit():
                continue
            if target in ("/dev/null", "NUL", "$null"):
                continue
            found = bundle_shell.readings(target, where, shell)
            if not found or any(path is None or inside(path, root) for path in found):
                return target
    return ""


def git_place(words, where):
    """Where a git command acts: each `-C <dir>` applied in turn from `where`, as git applies them -
    None when one cannot be told (a variable, or relative to a place that cannot be told).

    A COMMIT IN ANOTHER TREE IS WORK IN ANOTHER TREE. Measured 2026-10-09: with a run of this tree
    live, `git -C <a scratch repository> commit` was refused as a write of this tree - the `cd`
    was read, git's own `-C` was not - while the same commit after a `cd` went through."""
    if not words or bundle_shell.program_of(words[0]).lower() not in ("git", "git.exe"):
        return where
    place, at = where, 1
    while at < len(words) - 1 and words[at].startswith("-"):
        if words[at] == "-C":
            path = words[at + 1]
            if "$" in path or "%" in path or (place is None and not os.path.isabs(path)):
                return None
            place = os.path.normpath(os.path.join(place or "", path))
            at += 2
        elif words[at] == "-c":
            at += 2
        else:
            at += 1
    if at < len(words) and words[at] == "clone":
        return _clone_place(words[at + 1:], place)
    return place


#: `git clone`'s options that take the NEXT word as their value, from `git clone -h`: the grammar
#: of the command, read to tell its destination from an option's value.
CLONE_VALUED = frozenset(("-j", "--jobs", "--template", "--reference", "--reference-if-able",
                          "-o", "--origin", "-b", "--branch", "--revision", "-u",
                          "--upload-pack", "--depth", "--shallow-since", "--shallow-exclude",
                          "--ref-format", "-c", "--config", "--server-option", "--filter",
                          "--bundle-uri"))


def _clone_place(words, place):
    """Where `git clone <words>`, run at `place`, writes: its destination - the second word that
    is no option, or the source's own name under `place` when there is none. None when that
    cannot be told: a variable, a word count git would not take, or a git folder kept apart.

    A CLONE WRITES ITS DESTINATION AND ONLY READS ITS SOURCE. Measured 2026-10-10: with a plant
    drive of this tree live, `git clone --shared <this tree> <a scratch folder>` was refused as a
    write of this tree, placed where it ran like every git command."""
    given, at = [], 0
    while at < len(words):
        word = words[at]
        if word == "--":
            given += words[at + 1:]
            break
        if word.startswith("--separate-git-dir"):
            return None
        at += 2 if word in CLONE_VALUED else 1
        if not word.startswith("-"):
            given.append(word)
    if len(given) == 2:
        dest = given[1]
    elif len(given) == 1:
        name = given[0].rstrip("/\\")
        name = name[:-5] if name.endswith(("/.git", "\\.git")) else name
        name = name[:-4] if name.endswith(".git") else name
        dest = re.split(r"[/\\:]", name.rstrip("/\\"))[-1]
    else:
        return None
    if not dest or "$" in dest or "%" in dest or (place is None and not os.path.isabs(dest)):
        return None
    return os.path.normpath(os.path.join(place or "", dest))


#: A tool that writes nothing a measurement reads says so in its own text, on a line of its own. The
#: request ledger keeps its ledger in an ignored folder of its own; refused here beside a live sweep, the
#: turn-end review it owes could not be asked for and the turn could not end (census M.85, 2026-10-12).
#: Asked of the script's text by SHAPE, never of a list of names.
_STORE_ONLY = re.compile(r"(?m)^WRITES_ONLY_ITS_STORE = True[ \t]*$")


def store_only(words, where):
    """Is this `python [flags] <script> ...`, the script declaring that it writes only its own store?"""
    if not words or not re.match(r"^(?:python[0-9.]*|py)$", bundle_shell.program_of(words[0])):
        return False
    args = list(words[1:])
    while args and args[0].startswith("-"):
        if args[0] in ("-m", "-c"):
            return False
        args = args[1:]
    if not args:
        return False
    script = args[0] if os.path.isabs(args[0]) else os.path.join(where or ".", args[0])
    try:
        with io.open(script, encoding="utf-8") as handle:
            return bool(_STORE_ONLY.search(handle.read()))
    except (OSError, UnicodeDecodeError):
        return False


def command_writer(command, cwd, root, shell=None):
    """The program in this command that may write `root`, or '' when every one provably does not:
    it only reads, says or waits, reads git, or runs in another tree - and redirects nowhere here."""
    # A BACKTICK RUNS ITS COMMAND FIRST, and the reader hands back `$(...)` but not this
    # spelling of it: `echo \`sed -i s/a/b/ src/a.py\`` read as a quiet `echo`.
    for inner in backticked(command, shell):
        found = command_writer(inner, cwd, root, shell)
        if found:
            return found
    for words, where in programs(command, cwd, shell):
        if words is None:
            return "a command that cannot be read"
        into = _redirects_here(words, where, root, shell)
        if into:
            return "`%s` redirects into %s" % (" ".join(words)[:80], into)
        place = git_place(words, where)
        if place is not None and not inside(place, root):
            continue
        said = " ".join(words)
        program = bundle_shell.program_of(words[0])
        if QUIET.match(program) or GIT_READS.match(" ".join([program] + words[1:])):
            continue
        if store_only(words, where):
            continue
        if any(re.search(pattern, said, re.I) for pattern in guard_held_writes.READERS):
            continue
        return "`%s`" % said[:80]
    return ""


def live_runs(payload, root, machine):
    """([live runs of this tree], [what could not be looked at or placed])."""
    found, said = [], []
    told = transcript_runs(payload.get("transcript_path"), root)
    if told is None:
        said.append("the session's transcript could not be read")
    else:
        found += ["%s: `%s`" % (task, what) for task, what in sorted(told.items())]
    seen = machine(root)
    if seen is None:
        said.append("this machine's processes could not be read")
    else:
        found += seen[0]
        said += ["a run whose folder cannot be read (%s)" % s for s in seen[1][:3]]
    # THE THIRD WITNESS: a run that declared itself by a lock (`bundle_runs.LOCKS`) - the one that
    # holds on Windows, where the machine cannot place a run whose command line never names the tree.
    found += bundle_runs.lock_runs(root)
    return found, said


def verdict(payload, root, machine=None):
    """(exit code, message, note) for one call."""
    machine = machine_runs if machine is None else machine
    if not isinstance(payload, dict) or not bundle_shell.is_tool_call(payload):
        return 0, "", ""
    if bundle_env.allowed("ALLOW_WRITE_DURING_SUITE"):
        return 0, "", ""
    targets = writes_here(payload, root)
    cwd = payload.get("cwd") or root
    writer = ""
    if not targets:
        for command in bundle_shell.commands(payload):
            writer = command_writer(command, cwd, root,
                                    bundle_shell.shell_of(payload.get("tool_name")))
            if writer:
                break
        if not writer:
            return 0, "", ""
    found, said = live_runs(payload, root, machine)
    blind = len([s for s in said if "could not be read" in s]) == 2
    if not found and not blind:
        return 0, "", (("suite-running check: %s - " % "; ".join(said)
                        + "this call was judged by what could be read") if said else "")
    act = ("this writes %s" % targets[0]) if targets else ("this command may write it: %s"
                                                           % writer)
    if not found:
        return 2, (bundle_shell.policy(__file__) + LF
                   + "Whether a run of this tree is live cannot be told - %s - and %s.\n\nUnknown "
                     "is not idle. The owner's way past: %s=1.\n" % ("; ".join(said), act, BYPASS)), ""
    return 2, (bundle_shell.policy(__file__) + LF
               + "A run that measures this tree is live (%s), and %s.\n\n"
                 "CLAUDE.md: NEVER EDIT SOURCE WHILE THE SUITE IS RUNNING - the result is neither "
                 "the old code nor the new one, and it looks green. Two full runs were "
                 "invalidated this way.\n\n"
                 "Commands that only read, say or wait still run, and so does work in another "
                 "tree. Wait for the run's notification, or stop it. The owner's way past: %s=1.\n"
               % (found[0], act, BYPASS)), ""


def main():
    try:
        payload = json.loads(bundle_shell.payload_text())
    except (ValueError, IOError):
        return 0
    root = bundle_rules.repo_root(__file__)
    if root is None:
        return 0
    code, said, note = verdict(payload, root)
    if note:
        json.dump({"systemMessage": note}, sys.stdout)
    if code:
        sys.stderr.write(said + LF)
    return code


if __name__ == "__main__":
    bundle_shell.speak_utf8()
    sys.exit(main())
