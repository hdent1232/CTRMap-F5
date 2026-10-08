#!/usr/bin/env python3
"""A COMMIT THE GATE NEVER SAW IS FOUND THE MOMENT AFTER, AND EVERY ACT IS REFUSED UNTIL IT IS OFF.

`guard_command_rules.py` refuses every spelling of a skipped gate that the call's own text shows.
It cannot read a PROGRAM. Measured 2026-09-23: of sixteen ways to run `git commit --no-verify`,
fifteen were visible in the call and are refused there now; the sixteenth was
`python -c "subprocess.run(['git', 'commit', '--no-verify'])"`, and a script, or a variable
holding git, is the same hole. No hook reads what a program will do. So the gate leaves a STAMP,
and a commit without one is asked about the moment it exists.

THE STAMP. `.githooks/commit-msg` runs `gate_stamps.py stamp <message file>` once its checker has
passed. It records the tree git is about to commit - `git write-tree` of the index git hands the
hook, which for `commit -a` is a temporary one exported as GIT_INDEX_FILE - and the message,
digested, in `STAMPS` inside the repository's common git directory. `--no-verify` and a repointed
`core.hooksPath` skip `commit-msg`, so they leave no stamp, whoever ran them and however.

THE QUESTION. Every commit that a `git commit` or a merge MADE - read from the reflogs, which
record which command moved a ref and to what, in every worktree - and that no ref reached when
the stamps began, must carry a stamp for as long as any ref reaches it. The repair is to take it
off every ref, `git reset --soft <the commit before it>`, and commit the same change through the
gate.

WHEN THE STAMPS BEGAN IS A SET OF COMMITS, NOT A TIME. `BEFORE_FILE`, tracked, lists what every
ref and every worktree's HEAD pointed at. The first version used a time, and a reflog's time is
whatever `GIT_COMMITTER_DATE` says - measured: a commit dated 1500000000 was logged at 1500000000
- so a backdated `--no-verify` commit fell before the line. What was reachable then is a fact no
environment variable changes. `init` writes the file once and refuses to write it again, and a
write that ADDS a commit to it is refused: forgiving a commit is a diff somebody must make.

REBASE, CHERRY-PICK, REVERT AND AM ARE ASKED TOO. git runs no `commit-msg` for the commits they
make, so even an honest one carries no stamp - and left out, they were the last cheap way around
the gate: a program's `--no-verify` commit on a scratch branch, cherry-picked onto the main one,
the scratch branch deleted. The owner's rule (2026-09-24) is that work lands through commits and
merges; `guard_command_rules.py` refuses those four before they run, and `cherry-pick
--no-commit` or `revert --no-commit` then `git commit` is the stamped way to do the same thing. A
fast-forward makes no commit, and a fetched commit was made somewhere else.

ASKED before every command (`AT_COMMAND`) and the moment after every call (`AT_AFTER`). Cached on
the reflog files' sizes and times, so a call that moved no ref costs one stat walk: measured on
the project this came from, 0.5 s to read 5,632 reflog entries and every reachable commit, under
0.1 s to see that none of it had moved.

    python -B tools/gate_stamps.py init                   write BEFORE_FILE, once
    python -B tools/gate_stamps.py stamp <message file>   the gate's last step
    python -B tools/gate_stamps.py check                  exit 1 while a commit is ungated
"""
import hashlib
import io
import json
import os
import re
import shlex
import subprocess
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

LF = chr(10)
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

#: The TRACKED file listing the commits every ref and worktree HEAD pointed at when the stamps
#: began. A commit reachable from them was made before there was a stamp to leave.
BEFORE_FILE = os.path.join(".githooks", "gate-before")

#: Inside the repository's COMMON git directory, so every worktree stamps into one place.
STAMPS = "gate-stamps"
JUDGED = "gate-judged.json"

#: Adapted per project: see the bootstrap's ADAPT.md.
ADAPT = ("BEFORE_FILE",)

GIT_TIMEOUT = 60

#: The reflog actions that MADE a commit. `git commit` in every form and a merge that made a merge
#: commit run `commit-msg`, so the gate stamped them if it passed them. `rebase`, `cherry-pick`,
#: `revert` and `am` make commits git runs NO `commit-msg` for - measured 2026-09-24, one replayed
#: commit each, the hook's log silent - so theirs carry no stamp, and by the owner's rule of that
#: day they are asked about like any other: work lands through commits and merges.
MADE = re.compile(r"^(?:commit(?: \([^)]*\))?:|(?:merge|pull)\b[^\t]*: Merge made by"
                  r"|rebase(?: -i)? \((?:pick|reword|edit|squash|fixup)\):"
                  r"|cherry-pick:|revert:|am:)")

#: git subcommands that may run while a commit is ungated: they read, or take a commit off.
_READS = frozenset(("log", "show", "status", "diff", "reflog", "rev-parse", "rev-list",
                    "cat-file", "ls-files", "branch", "reset", "update-ref", "worktree", "config",
                    "stash", "restore", "checkout", "switch", "fetch", "blame", "grep"))


sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root        # noqa: E402  - the project root, anchored: never counted (F216)


ROOT = _tree_root.require(__file__)


def _git(root, args, given=None):
    # UTF-8, NOT THE CODE PAGE. `text=True` alone decodes git's output as cp1252 on Windows, while
    # `stamp` reads the message file as UTF-8 - so a message with any non-ASCII character (an em
    # dash, an accent, the byte-order mark PowerShell's `Set-Content -Encoding utf8` writes) was
    # digested two ways and every such commit read as made past the gate (measured 2026-09-27: a
    # BOM-led subject stamped by commit-msg and then refused as unstamped).
    try:
        done = subprocess.run(["git"] + args, cwd=root, capture_output=True, text=True,
                              encoding="utf-8", errors="replace",
                              input=given, timeout=GIT_TIMEOUT, creationflags=NO_WINDOW)
    except (OSError, subprocess.SubprocessError):
        return None
    return done.stdout if done.returncode == 0 else None


def common_dir(root):
    """The repository's common git directory, or None when git cannot say."""
    held = _git(root, ["rev-parse", "--git-common-dir"])
    if not held or not held.strip():
        return None
    return os.path.normpath(os.path.join(root, held.strip()))


def digests(message):
    """The message's digests as git may have stored it: whitespace cleaned, `#` lines kept -
    and with them stripped, which is what git does to a message that went through an editor."""
    out = []
    for strip_comments in (False, True):
        lines = [line.rstrip() for line in (message or "").replace(chr(13), "").split(LF)
                 if not (strip_comments and line.startswith("#"))]
        kept, blank = [], False
        for line in lines:
            if not line:
                blank = bool(kept)
                continue
            if blank:
                kept.append("")
            kept.append(line)
            blank = False
        out.append(hashlib.sha256(LF.join(kept).encode("utf-8")).hexdigest())
    return out


def stamp(message_path, root=ROOT):
    """Record that the gate passed the commit about to be made. 0, or 1 when it cannot."""
    try:
        with io.open(message_path, encoding="utf-8", errors="replace") as handle:
            message = handle.read()
    except OSError as exc:
        sys.stderr.write("REFUSING THE COMMIT: the gate cannot stamp it, the message cannot be "
                         "read (%s)%s" % (exc, LF))
        return 1
    tree = (_git(root, ["write-tree"]) or "").strip()
    common = common_dir(root)
    if not tree or not common:
        sys.stderr.write("REFUSING THE COMMIT: the gate cannot stamp it - git could not say "
                         "the tree or where the repository keeps its state%s" % LF)
        return 1
    try:
        with io.open(os.path.join(common, STAMPS), "a", encoding="utf-8", newline=LF) as handle:
            handle.write("%s %s%s" % (tree, " ".join(digests(message)), LF))
    except OSError as exc:
        sys.stderr.write("REFUSING THE COMMIT: the gate's stamp could not be written (%s) - a "
                         "commit without one would be refused after it anyway%s" % (exc, LF))
        return 1
    return 0


def tips(root):
    """Every commit a ref or a worktree's HEAD points at now, or None when git cannot say."""
    refs = _git(root, ["for-each-ref", "--format=%(objectname)"])
    heads = _git(root, ["worktree", "list", "--porcelain"])
    if refs is None or heads is None:
        return None
    found = set(refs.split())
    found.update(line.split()[1] for line in heads.splitlines()
                 if line.startswith("HEAD ") and len(line.split()) == 2)
    return sorted(found)


def init(root=ROOT):
    """Write BEFORE_FILE from the tips as they are now. Once: never over one that exists."""
    path = os.path.join(root, BEFORE_FILE)
    rel = BEFORE_FILE.replace(os.sep, "/")
    if os.path.exists(path) or _git(root, ["cat-file", "-e", "HEAD:" + rel]) is not None:
        sys.stderr.write("REFUSING: %s already exists, here or at HEAD. Writing it again would "
                         "forgive every commit made past the gate since%s" % (rel, LF))
        return 1
    held = tips(root)
    if not held:
        sys.stderr.write("REFUSING: git could not list the refs, so no baseline was written%s"
                         % LF)
        return 1
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline=LF) as handle:
        handle.write(LF.join(held) + LF)
    return 0


def before(root):
    """(the commits reachable when the stamps began, as tips), or (None, why)."""
    try:
        with io.open(os.path.join(root, BEFORE_FILE), encoding="utf-8") as handle:
            held = [line.strip() for line in handle if line.strip()]
    except OSError as exc:
        return None, ("%s cannot be read (%s), so which commits must carry the gate's stamp is "
                      "UNKNOWN - `gate_stamps.py init` writes it" % (BEFORE_FILE, exc))
    if not held or not all(re.match(r"^[0-9a-f]{40,64}$", sha) for sha in held):
        return None, ("%s is not a list of commits, so which must carry the gate's stamp is "
                      "UNKNOWN" % BEFORE_FILE)
    return held, ""


def reflogs(common):
    """Every reflog file of the repository - its refs, its HEAD, and every worktree's HEAD."""
    found = []
    for folder, _dirs, names in os.walk(os.path.join(common, "logs")):
        found += [os.path.join(folder, name) for name in names]
    try:
        worktrees = sorted(os.listdir(os.path.join(common, "worktrees")))
    except OSError:
        worktrees = []
    for name in worktrees:
        path = os.path.join(common, "worktrees", name, "logs", "HEAD")
        if os.path.isfile(path):
            found.append(path)
    return sorted(found)


def signature(paths):
    parts = []
    for path in paths:
        try:
            held = os.stat(path)
            parts.append("%s|%d|%d" % (path, held.st_size, held.st_mtime_ns))
        except OSError:
            parts.append(path + "|gone")
    return hashlib.sha256(LF.join(parts).encode("utf-8")).hexdigest()


def made_by_commit(paths):
    """{commit: the reflog action that made it} for every commit a `git commit` or a merge
    made, read from the reflog files themselves."""
    made = {}
    for path in paths:
        try:
            with io.open(path, encoding="utf-8", errors="replace") as handle:
                lines = handle.read().splitlines()
        except OSError:
            continue
        for line in lines:
            head, _tab, action = line.partition(chr(9))
            fields = head.split()
            if len(fields) >= 2 and MADE.match(action):
                made[fields[1]] = action
    return made


def _stamps(common):
    try:
        with io.open(os.path.join(common, STAMPS), encoding="utf-8") as handle:
            lines = handle.read().splitlines()
    except OSError:
        return set()
    held = set()
    for line in lines:
        fields = line.split()
        held.update((fields[0], d) for d in fields[1:])
    return held


def _described(root, commits):
    """{commit: (tree, message)} read in one git call, the commits handed over on STDIN - a
    command line is capped near 32K characters on Windows, about 700 commits."""
    if not commits:
        return {}
    text = _git(root, ["log", "--no-walk=unsorted", "--stdin", "--format=%H%x00%T%x00%B%x01"],
                given=LF.join(sorted(commits)) + LF)
    out = {}
    for record in (text or "").split(chr(1)):
        parts = record.lstrip(LF).split(chr(0))
        if len(parts) == 3:
            out[parts[0]] = (parts[1], parts[2])
    return out


def ungated(root=ROOT):
    """Every commit on a ref that a `git commit` or a merge made without the gate's stamp."""
    start, why = before(root)
    if why:
        return [why]
    common = common_dir(root)
    if common is None:
        return ["git could not say where %s keeps its state, so no commit could be checked for "
                "the gate's stamp" % root]
    paths = reflogs(common) + [os.path.join(common, STAMPS)]
    # THE VERDICT IS KEYED ON THE JUDGE TOO. A memo keyed only on the reflogs and the stamps kept
    # replaying a wrong verdict after the rule that produced it was fixed (2026-09-27: the cp1252
    # decode in `_git`, fixed, and the lock stayed until some ref happened to move).
    mark = (signature(paths + [os.path.abspath(__file__)]) + "|"
            + hashlib.sha256(LF.join(start).encode("ascii")).hexdigest())
    try:
        with io.open(os.path.join(common, JUDGED), encoding="utf-8") as handle:
            memo = json.load(handle)
    except (OSError, ValueError):
        memo = {}
    if memo.get("signature") == mark and isinstance(memo.get("found"), list):
        return memo["found"]
    made = made_by_commit(paths[:-1])
    clean = set(memo.get("clean") or []) & set(made)
    found = []
    if set(made) - clean:
        # `--ignore-missing`: a tip since deleted and collected is not a reason to see nothing.
        reach = _git(root, ["rev-list", "--ignore-missing", "--all", "--stdin"],
                     given="".join("^%s%s" % (sha, LF) for sha in start))
        if reach is None:
            found = ["git could not list what the refs reach, so no commit could be checked for "
                     "the gate's stamp"]
        else:
            reached = (set(reach.split()) & set(made)) - clean
            stamped = _stamps(common)
            described = _described(root, reached)
            for commit in sorted(reached):
                tree, message = described.get(commit, ("", ""))
                if any((tree, d) in stamped for d in digests(message)):
                    clean.add(commit)
                    continue
                found.append("%s (%s) was made without the commit gate - no stamp for its "
                             "tree and message. Take it off every ref with `git reset "
                             "--soft %s~1` and commit the change through the gate"
                             % (commit[:10], made[commit][:60], commit[:10]))
    try:
        with io.open(os.path.join(common, JUDGED), "w", encoding="utf-8", newline=LF) as handle:
            json.dump({"signature": mark, "found": found, "clean": sorted(clean)}, handle)
    except OSError:
        pass                                   # a lost memo costs one re-read, never a finding
    return found


def _undoes(command):
    """Does this command only read, or take a commit off a ref?"""
    words = (command or "").strip().split()
    while words and "=" in words[0] and not words[0].startswith("-"):
        words = words[1:]
    if not words:
        return True
    if os.path.basename(words[0]).lower() in ("git", "git.exe"):
        rest = [w for w in words[1:] if not w.startswith("-")]
        return not rest or rest[0] in _READS
    return False


#: ASKED BEFORE EVERY COMMAND: while a commit is ungated, nothing may be built on it.
AT_COMMAND = "before_command"

#: ASKED THE MOMENT AFTER EVERY CALL: the call that made the commit hears about it at once.
AT_AFTER = "after_call"

#: ASKED OF EVERY COMMIT, BEFORE GIT STARTS: a hook git would skip is a gate that is off.
AT_COMMIT = "before_commit"

#: Every hook git runs, by the name it looks for (githooks(5)). A file in the hooks folder under
#: one of these names is a gate; anything else there - `BEFORE_FILE` sits beside them - is not.
GIT_HOOKS = ("applypatch-msg", "pre-applypatch", "post-applypatch", "pre-commit",
             "pre-merge-commit", "prepare-commit-msg", "commit-msg", "post-commit", "pre-rebase",
             "post-checkout", "post-merge", "pre-push", "pre-receive", "update", "proc-receive",
             "post-receive", "post-update", "reference-transaction", "push-to-checkout",
             "pre-auto-gc", "post-rewrite", "sendemail-validate", "fsmonitor-watchman",
             "p4-changelist", "p4-prepare-changelist", "p4-post-changelist", "p4-pre-submit",
             "post-index-change")


def hooks_here(root=ROOT):
    """(folder, [(repo-relative path or None, name, index mode or None)]) for every hook in the
    folder git itself runs hooks from - `rev-parse --git-path hooks`, which reads `core.hooksPath` -
    or (None, why) when that cannot be read. The path is None for a hook outside the repository."""
    where = _git(root, ["rev-parse", "--git-path", "hooks"])
    if where is None:
        return None, "git could not say where its hooks are"
    folder = os.path.join(root, where.strip())
    try:
        names = sorted(name for name in os.listdir(folder) if name in GIT_HOOKS)
    except FileNotFoundError:
        return folder, []
    except OSError as bad:
        return None, "%s cannot be listed (%s)" % (where.strip(), bad)
    rels = {}
    for name in names:
        rel = os.path.relpath(os.path.join(folder, name), root).replace(os.sep, "/")
        rels[name] = None if rel.startswith("../") or os.path.isabs(rel) else rel
    asked = [rel for rel in rels.values() if rel]
    staged = _git(root, ["ls-files", "-s", "--"] + asked) if asked else ""
    if staged is None:
        return None, "git could not list the hooks in %s" % where.strip()
    modes = {}
    for line in staged.splitlines():
        meta, _tab, path = line.partition("\t")
        modes[path.strip()] = (meta.split() or [""])[0]
    return folder, [(rels[name], name, modes.get(rels[name])) for name in names]


def unrunnable_hooks(root=ROOT):
    """[why] for every hook git would SKIP here: committed without the executable bit, or - on a
    system that has one - not executable on disk.

    MEASURED 2026-10-07: this bundle and CTRMap had both committed `.githooks/*` as 100644, from
    Windows, where git runs a hook whatever its mode. On Linux and macOS git skips such a hook with
    only a hint, so on every cloud checkout `core.hooksPath` named the gate, the session-start check
    said "armed", and the gate was off - its hooks installed, wired and present. A commit made past
    it is found the moment after (`ungated`); nothing stopped it being made. The index is asked on
    every system, because a clone takes a file's mode from it.
    """
    folder, hooks = hooks_here(root)
    if folder is None:
        return ["%s, so whether git would run its hooks is UNKNOWN - and a hook git skips is a "
                "gate that is off" % hooks]
    found = []
    for rel, name, mode in hooks:
        shown = rel or os.path.join(folder, name)
        if mode is not None and mode != "100755":
            found.append("`%s` is committed as %s, not executable, so git on Linux and macOS skips "
                         "it with only a hint and every rule it asks is off there. `git "
                         "update-index --chmod=+x -- %s` records it executable" % (rel, mode, rel))
        if os.name != "nt" and not os.access(os.path.join(folder, name), os.X_OK):
            found.append("`%s` is not executable on this machine, so git skips it with only a hint "
                         "and every rule it asks is off. `chmod +x %s`" % (shown, shown))
    return found


def before_commit(message, root=ROOT):
    """A commit is refused before git starts while git would skip one of its hooks: made, it would
    be made past the gate, and `ungated` would then refuse every command after it."""
    del message
    return unrunnable_hooks(root)


#: ASKED AT THE WRITE of the baseline: a commit added to it is a commit forgiven.
AT_WRITE_CHANGE = "forgiven_in"
AT_SCOPE = r"^\.githooks/gate-before$"


#: THE STAMP IS WRITTEN BY THE GATE, AND BY NOTHING ELSE. A stamp written any other way passes
#: every check above, so every way a call can reach the store is refused before it runs: naming
#: the store; running this tool's `stamp` step, which is git's `commit-msg` hook's to run; running
#: git's hook by hand; and running a script - however it was made - whose text does either. The
#: `.git` folder itself is refused to the Write and Edit tools by `guard_write_rules.py`.
STORE = (STAMPS, JUDGED)
STAMP_CALL = re.compile(r"gate_stamps(?:\.py)?[\"']?\s+stamp\b")
HOOK_RUN = re.compile(r"\bgit\b[^;&|]*\bhook\s+run\b")
_SEGMENT = re.compile(r"&&|\|\||[;|&\r\n]")
_RUNNERS = ("&", ".", "call", "start", "exec", "command", "env", "time", "nohup")
_INTERPRETERS = ("sh", "bash", "zsh", "dash", "pwsh", "powershell", "node", "ruby", "perl")
#: Python by SHAPE: `python3.12` and `pythonw3` are the same program as `python`, and a list of
#: names let every versioned one run a script nothing read.
_PYTHON = re.compile(r"py|pythonw?[0-9.]*")
#: Modules run with `-m` that READ the files they are handed and run none of them. A module not
#: named here keeps every argument a possible program - unknown is not data.
_READ_ONLY_MODULES = ("ruff", "pyflakes", "pycodestyle", "flake8", "py_compile", "compileall",
                      "tabnanny", "json.tool")
_READ_LIMIT = 4 * 1024 * 1024


def _words(segment):
    """One command's words, a QUOTED path held together as one.

    Split on spaces, `python "C:\\a dir\\forge.py"` was two fragments, neither of them a file, so
    a script in any folder with a space in its name was never read - the project this came from
    has one in its root. A quote left open is split on spaces, as every command was before.
    """
    try:
        words = shlex.split(segment, posix=False)
    except ValueError:
        words = segment.split()
    return [w.strip(chr(34) + "'") for w in words]


def _local_module(module, root):
    """The files in the project that `-m module` would run: `-m` puts the working directory first
    on the path, so a `ruff.py` in the project IS what `python -m ruff` runs."""
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.]*", module):
        return []
    base = os.path.join(root, *module.split("."))
    return [p for p in (base + ".py", os.path.join(base, "__main__.py"),
                        os.path.join(base, "__init__.py")) if os.path.isfile(p)]


def _python_runs(args, root):
    """The words among Python's arguments that could be a program it RUNS.

    THE PROGRAM IS ONE WORD, NOT EVERY WORD. Every argument after the interpreter was read as a
    script, so `python -m ruff check tests/test_x.py` - a linter READING a test that names the
    store - was refused as forging a stamp. An unknown module's arguments are handed on, and
    `forges` reads the first of them that is a file: `-m` names a module, not a file, so in
    `python -m runpy forge.py` the file is the first thing the interpreter was handed to run.
    """
    index = 0
    while index < len(args):
        word = args[index]
        if not word.startswith("-") or word == "-":
            return args[index:]
        module = re.fullmatch(r"-[bBdEhiIOPqRsSuvVx]*m(.*)", word)
        if module:
            name = module.group(1) or (args[index + 1] if index + 1 < len(args) else "")
            rest = args[index + 1:] if module.group(1) else args[index + 2:]
            local = _local_module(name, root)
            if local:
                return local + rest
            return [] if name in _READ_ONLY_MODULES else rest
        index += 2 if word in ("-X", "-W") else 1
    return []


def _is_this_tool(path):
    try:
        return os.path.samefile(path, os.path.abspath(__file__))
    except OSError:
        return False


def forges(command, root=ROOT):
    """Why this command could put a stamp in the store that no gate wrote, or ''.

    What it cannot see, said rather than implied: a program that reaches the store WITHOUT its
    text naming it - the name built from pieces, or read from somewhere else at run time. That is
    deliberate concealment, and no check inside the account the gate runs in can stop a program
    written to hide what it does; a stamp only a separate account could sign would.

    THE PROGRAM IS READ, AND NOTHING IT IS HANDED - the owner's rule, 2026-09-27. The program is
    the first word after the interpreter's options that names a file: an option's value
    (`-ExecutionPolicy Bypass`) or a module's name is not one. `python -B show.py
    tests\\plants.json` was refused as forging a stamp because the plant ledger names the store
    while holding this tool's own plants. A script that runs a file it is handed is a program
    reading its code from somewhere else at run time - the concealment above - and reading its
    arguments did not close that: a forger renamed `.txt` walked past the version that tried.
    """
    text = command or ""
    if any(name in text for name in STORE):
        return ("this command names the gate's own stamp store - the stamps are written by "
                "`commit-msg` and by nothing else")
    if STAMP_CALL.search(text):
        return ("`gate_stamps.py stamp` is the last step of git's `commit-msg` hook; run by hand "
                "it stamps a commit no gate passed")
    if HOOK_RUN.search(text):
        return ("`git hook run` runs the gate's hook by hand, and the stamp it leaves would be one "
                "no commit earned")
    for segment in _SEGMENT.split(text):
        words = [w for w in _words(segment) if w and not re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", w)]
        while words and words[0].lower() in _RUNNERS:
            words = words[1:]
        if not words:
            continue
        # EITHER SEPARATOR, ON EVERY MACHINE - as `bundle_shell.program_of` reads a program.
        # `os.path.basename` splits only on its own platform's, so on Linux and macOS
        # `"C:\\Program Files\\Python312\\python.exe" forge.py` was no interpreter at all.
        name = re.split(r"[\\/]", words[0])[-1].lower()
        name = name[:-4] if name.endswith(".exe") else name
        if _PYTHON.fullmatch(name):
            # THE PROGRAM, AND ONLY IT. `_python_runs` hands back the program first and what it
            # is handed after. Scanning past a program that is no file here - `$TOOLS/show.py` -
            # read its first FILE argument as the program, and `python3 $T/show.py
            # tests/plants.json` was refused as forging a stamp (measured 2026-10-06), the case
            # b22e36d closed for a program named literally.
            candidates = _python_runs(words[1:], root)[:1]
        elif name in _INTERPRETERS:
            candidates = words[1:]
        else:
            candidates = words[:1]
        for word in candidates:
            if word.startswith("-"):
                continue
            path = word if os.path.isabs(word) else os.path.join(root, word)
            if not os.path.isfile(path):  # an option's value or a module's name: not the program
                continue
            if _is_this_tool(path):
                break
            try:
                with io.open(path, encoding="utf-8", errors="replace") as handle:
                    body = handle.read(_READ_LIMIT)
            except OSError:
                return ("`%s` names %s, which could not be read - whether it writes a gate stamp "
                        "is UNKNOWN, and unknown is not no" % (segment.strip()[:60], word))
            if any(store in body for store in STORE) or STAMP_CALL.search(body):
                #A TEST FILE IS TOLD ITS ROUTE (F181): the files that must name the store are the
                #ones that test it, and a refusal with no way forward is one somebody removes
                base = os.path.basename(path)
                route = ""
                if base.startswith("test_") and base.endswith(".py"):
                    dotted = os.path.relpath(path, root)[:-3].replace(os.sep, ".").replace("/", ".")
                    route = (" - a test file runs by its NAME, `python -m unittest %s`, as the "
                             "battery runs it" % dotted)
                return ("`%s` runs %s, whose text reaches the gate's stamp store - the stamps are "
                        "written by `commit-msg` and by nothing else%s"
                        % (segment.strip()[:60], word, route))
            break                         # the program is read, and nothing it is handed
    return ""


def before_command(command, root=ROOT):
    forged = forges(command, root)
    if forged:
        return [forged]
    return [] if _undoes(command) else ungated(root)


def after_call(root=ROOT):
    return ungated(root)


def forgiven_in(rel, before_text, after_text, root=ROOT):
    """A commit the baseline gains is one made before the stamps began by declaration.

    A file deleted and written again is judged against HEAD's copy: "there was nothing before"
    must not be the cheapest way to add a line.
    """
    if before_text is None:
        before_text = _git(root, ["show", "HEAD:" + rel])
    if before_text is None:
        return []
    was = set(before_text.split())
    added = sorted(set((after_text or "").split()) - was)
    return ["%s would forgive %s - a commit is made before the stamps began by git, not by an "
            "edit" % (rel, sha[:10]) for sha in added]


def main(argv):
    if len(argv) > 2 and argv[1] == "stamp":
        # WHERE GIT RUNS THE HOOK, not where this file sits: git runs a hook at the top of the
        # worktree being committed, and a tool shared by several stamps into the one committed.
        return stamp(argv[2], os.getcwd())
    if len(argv) > 1 and argv[1] == "init":
        return init()
    # NO ARGUMENT IS `check`: run bare, as a commit gate runs a named checker, the answer is
    # whether any commit on a ref was made past the gate - exit 0 only when none was.
    if len(argv) == 1 or argv[1] == "check":
        found = ungated()
        for line in found:
            sys.stdout.write("REFUSING: %s%s" % (line, LF))
        if not found:
            sys.stdout.write("every commit made since the stamps began carries the gate's stamp%s"
                             % LF)
        return 1 if found else 0
    sys.stdout.write(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
