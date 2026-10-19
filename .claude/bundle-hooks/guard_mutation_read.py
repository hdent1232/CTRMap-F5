#!/usr/bin/env python3
"""Refuse to READ a module that a mutation run is holding open.

WHAT HAPPENED, 2026-09-01. A sweep of `dtengine/data/run.py` was in flight. I ran an AST walk
over the same file to group the surviving mutants by function and got back a table that looked
entirely plausible:

    <module>              267   lines 539-1684
    _sync_filing_index     38   lines 389-415

`_sync_filing_index` is at line 731. Line 762 was reported as belonging to no function at all.
`mutate.py` had the module replaced on disk with `ast.unparse` output - every comment gone,
every line renumbered - and my walk parsed THAT. Nothing errored. Nothing warned. The answer
was confident, wrong, and about a file that exists only between the lock being taken and the
`finally` that puts the real one back.

WHY THE EXISTING GUARDS DO NOT REACH IT. `tools/dev/safe.py rewrite` refuses under this lock and
`.githooks/pre-commit` refuses a commit under it - both sit on the WRITE. Reading is the earlier
half and it had nothing, which matters more than it sounds: several tools under `tools/audit/`
write a whole-tree baseline from what they read, so a read taken mid-sweep does not just mislead
a reader, it can RECORD the unparsed source as a measurement. That is the same class as the four
narrowed-run-replaces-whole-tree-record instances `tools/audit/baselines.py` was built for.

THE RULE. While `.mutation-in-flight` exists, a shell command may not read the file it names,
may not read a directory containing it, and may not run a tool under `tools/audit/`.

    `git ...`                     allowed - it reads the index and HEAD, not the mutated
                                  worktree, and `git checkout -- <file>` is the recovery
                                  command `mutate.py`'s own refusal tells you to run
    `tools/audit/mutate.py`       allowed - it refuses on its own and NAMES the file to
                                  restore, which is the message you actually need
    anything else naming it       refused

Not scoped to `tools/`, because the command that produced the wrong table was a bare
`python -c`. A guard that only knows about this project's own tools would have watched it go by.

TO READ IT ANYWAY: the owner sets `bundle_env.name("ALLOW_MUTATION_READ")` to 1 for that session.

A COMMAND IS ONE WAY TO READ A FILE, AND THIS KNEW ONLY THAT ONE. Its own `main` said so in a
comment - *the `Read` tool still falls through here because it carries no command* - and called
that the same answer the old name check gave. It was the same answer, and the same HOLE: the
`Read` tool reads the unparsed module exactly as `cat` does, `Grep` over its directory reads it
exactly as `grep -rn` does, and `Edit` writes over a mutant that the `finally` then overwrites.
A call is judged by the PATHS it carries as well as the commands, by shape, at any depth.
"""
import fnmatch
import json
import os
import re
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_env        # noqa: E402  - one PREFIX renames every override
import bundle_rules      # noqa: E402  - the ONE reader of a lock; which checkout a call is in
import bundle_shell      # noqa: E402  - the command, found by shape, at any depth

LOCK = ".mutation-in-flight"
BYPASS = bundle_env.name("ALLOW_MUTATION_READ")

#: Tools whose whole job is to read the tree and answer questions about it. Running one while a
#: module is unparsed on disk measures the unparsed module.
AUDIT_TOOL = re.compile(r"tools[/\\]audit[/\\]([A-Za-z_][A-Za-z0-9_]*)\.py")

#: The interpreters that RUN one. `grep -n x tools/audit/sweep.py` reads a tool's source and is
#: honest work; this rule is about running it, and its own docstring says so. Refusing the read
#: was the second false positive this guard produced, and a guard that fires on honest work is
#: the one people learn to switch off.
_RUNNERS = ("python", "python3", "python.exe", "py", "pythonw", "uv", "coverage")


def runs_it(segment, tool):
    """True when this segment EXECUTES the audit tool rather than merely naming it."""
    words = segment.split()
    if not words:
        return False
    first = os.path.basename(words[0].strip("(" + chr(39) + chr(34))).lower()
    if first in _RUNNERS:
        return True
    # `./tools/audit/x.py` or `tools/audit/x.py` invoked directly.
    return tool.group(0).replace(chr(92), "/") in words[0].replace(chr(92), "/")

#: The one that manages the lock itself, and whose refusal is more useful than this one.
EXEMPT_TOOL = "mutate.py"

#: Adapted per project: see the bootstrap's ADAPT.md.
ADAPT = ("LOCK", "AUDIT_TOOL", "EXEMPT_TOOL")


def touched_paths(paths, locked, root):
    """The path arguments of a NON-SHELL call that resolve to the locked file or a parent of it.

    Absolute paths are made relative to `root`; both separators and both cases are accepted,
    because a Windows path arrives however the caller spelled it. A path OUTSIDE the root cannot
    be the locked module and is ignored.
    """
    found = []
    base = os.path.normcase(os.path.abspath(root))
    for raw in paths:
        candidate = raw.strip()
        if not candidate:
            continue
        full = os.path.normcase(os.path.abspath(os.path.join(root, candidate)))
        if full != base and not full.startswith(base + os.sep):
            continue
        rel = bundle_shell.relative(full, base).rstrip("/")
        target = os.path.normcase(locked).replace(os.sep, "/")
        if rel in (".", "") or rel == target or target.startswith(rel + "/"):
            found.append(raw)
    return found


def locked_module(root):
    """The repo-relative path `mutate.py` recorded in the lock, or None when no run is in flight.

    An empty or unreadable lock reads as NO lock rather than as an unnamed one: this hook may
    never be the reason a turn cannot proceed on a file nobody is mutating.

    THE FIRST LINE, BY THE ONE READER. This read the WHOLE file as the path, and every lock
    writer writes more than one line - the bundle's own `take_lock` writes the path, then a nonce
    - so the path never matched and a read of the mutated file went through under every real
    lock (reported by the CTRMap session 2026-10-07, confirmed against `take_lock`). Two readers
    of one file is one rule written twice; `bundle_rules.locked` is the reader every judge uses.
    """
    return bundle_rules.locked(root, LOCK) or None


def places(payload, root):
    """[(checkout, its commands are read against it)] for every checkout of this repository this
    call can read: the project, where the call RUNS, and where each path it names lands.

    A LINKED WORKTREE HOLDS ITS OWN LOCK. A mutation run started there plants into the worktree's
    files and writes the worktree's `.mutation-in-flight`, while the hooks run from the project
    and read only the project's - measured 2026-10-07 on DF Engine, a plant drive in a worktree
    the harness made, while the same session read that worktree's modules. A command is
    read against the lock where it runs (and the project's, as it always was); a path against the
    lock of the checkout it lands in.
    """
    acting = bundle_rules.acting_in(payload, root)
    out = [(root, True)]
    if acting != root:
        out.append((acting, True))
    for one in bundle_shell.paths(payload):
        if not isinstance(one, str) or not one.strip():
            continue
        place = bundle_rules.landing(root, one if os.path.isabs(one) else os.path.join(acting, one))
        if place is not None and all(place != held for held, _runs in out):
            out.append((place, False))
    return out


#: A command that moves where it runs: a relative path after it is not followed here.
_MOVES = re.compile(r"^\s*(?:cd|chdir|pushd|popd|Set-Location|sl|Push-Location|Pop-Location)\b",
                    re.I)


def _same(a, b):
    return os.path.normcase(os.path.realpath(a)) == os.path.normcase(os.path.realpath(b))


def _home(word, root, acting, shell):
    """The checkout of this repository the file `word` lives in, read the way the running shell
    reads a path - or None when that cannot be told, or two readings disagree."""
    found = bundle_shell.readings(word, acting, shell)
    if not found or any(path is None for path in found):
        return None
    homes = {bundle_rules.landing(root, path) for path in found}
    if len(homes) != 1:
        return None
    return homes.pop()


def tool_homes(command, root, acting, shell=None):
    """[checkout, or None where it cannot be told] for every audit tool this command runs by an
    interpreter - where the tool's FILE lives, which is the tree it reads.

    A TOOL READS THE CHECKOUT IT LIVES IN. Each takes its root from its own file, so a linked
    worktree's `tools/audit/tiers.py` reads the worktree whatever the main tree is doing. This read
    every command against the project's lock as well as the lock where it runs, and measured
    2026-10-10 on DF Engine, another session's plant drive in the main checkout refused that tool,
    run for a worktree whose own lock was absent, for about fifteen minutes. The other way round
    it was blind: run from the main tree by its path, a worktree's tool was never read against the
    worktree's lock. A `cd` anywhere before a tool makes it unplaceable here, and an unplaceable
    tool is judged as it always was, against every lock."""
    homes, moved = [], False
    for one in bundle_shell.split_commands(command or ""):
        if _MOVES.match(one):
            moved = True
            continue
        for words, _background in bundle_shell.runs(one):
            if not words:
                continue
            program = bundle_shell.program_of(words[0][0])
            if not re.fullmatch(r"py|pythonw?[0-9.]*|uv|coverage", program):
                continue
            args = [(t if t is not None else w) for w, t in words[1:]]
            module = next((args[at + 1] for at, a in enumerate(args[:-1]) if a == "-m"), "")
            script = next((a for a in args if a.endswith(".py")), "")
            if AUDIT_TOOL.search(script.replace(chr(92), "/")):
                word = script
            elif re.match(r"tools\.audit\.", module):
                word = "."                      # a module runs from where the call runs
            else:
                continue
            homes.append(None if moved else _home(word, root, acting, shell))
    return homes


def readers_of(command, locked):
    """The path spellings in `command` that resolve to the locked file or a parent of it.

    TWO RULES, and the difference between them is the difference between a guard and a nuisance.

    The file itself is matched ANYWHERE in the text, quotes and all, because the command that
    started this carried the path inside a `python -c` body and any tokeniser would have lost it.

    A parent directory is matched only as a whole TOKEN. `grep -rn x dtengine/` reads the mutated
    module just as surely as naming it does - but `dtengine/data/asof.py` CONTAINS the string
    `dtengine/data/`, and refusing an honest read of an unmutated sibling is how a guard gets
    switched off. Both separators either way: the lock stores forward slashes and a shell line
    here is as likely to carry backslashes.
    """
    found = []
    for spelling in (locked, locked.replace("/", chr(92))):
        if spelling in command:
            found.append(locked)
            break
    for raw in command.split():
        token = raw.strip(chr(39) + '"' + "()").replace(chr(92), "/").rstrip("/")
        if token and locked.startswith(token + "/"):
            found.append(token + "/")
    return found


def segments(command):
    """The command string split on the separators, so one `git` does not excuse its neighbour.

    Deliberately cruder than `guard_heredoc.commands`: this hook searches RAW text, because the
    path that started all this sat inside a quoted `python -c` body and blanking quoted runs
    would have hidden it.
    """
    return [s for s in re.split(r"&&|\|\||[;|\n]", command) if s.strip()]


#: Searches that read every file beneath where they stand when handed no path: `grep -r x` and
#: `rg x` read the working directory, and the working directory holds the module.
_SEARCHES = ("rg", "ag", "ack", "ripgrep")
_GREPS = ("grep", "egrep", "fgrep", "zgrep")


def _resolve(where, token):
    """`token` as a project path from `where` (project-relative, '' at the root), or None when it
    is absolute or cannot be placed - an absolute path is matched as text, as before."""
    token = token.strip(chr(39) + chr(34)).replace(chr(92), "/")
    if not token or where is None or os.path.isabs(token) or re.match(r"^[A-Za-z]:/", token):
        return None
    joined = os.path.normpath(os.path.join(where or ".", token)).replace(chr(92), "/")
    return "" if joined == "." else joined


def _holds(path, locked):
    """Is the project path `path` the locked module, or a folder it sits in - globs included?"""
    if path is None:
        return False
    if path == "" or path == locked or locked.startswith(path + "/"):
        return True
    if any(c in path for c in "*?["):
        parts = locked.split("/")
        return any(fnmatch.fnmatchcase("/".join(parts[:n]), path) for n in range(1, len(parts) + 1))
    return False


def _module_names(locked):
    """The dotted names the locked module imports as - `src/pkg/mod.py` is `pkg.mod` and
    `src.pkg.mod` - never the bare last part, which names half the modules anywhere."""
    parts = re.sub(r"\.py$", "", locked).split("/")
    return [".".join(parts[n:]) for n in range(len(parts) - 1)]


def reads_by_running(command, locked, tools_elsewhere=False):
    """Why one command READS the locked module by what it RUNS, or ''.

    WHAT RUNS, NOT THE FIRST WORD TYPED - `bundle_shell.runs`, with `cd` followed. Measured
    2026-10-06: `grep -rn x .`, `grep -rn x ./src`, `rg foo` (a search handed no path reads where
    it stands), `cat src/pkg/*.py`, `python3 -c 'import pkg.mod'` and `timeout 60 python
    tools/audit/weigh.py` all read the mutant while this guard read the words as typed."""
    where = ""
    for one in bundle_shell.split_commands(command):
        moved = re.match(r"^\s*(?:cd|chdir|pushd|Set-Location|sl)\s+(\S+)\s*$", one, re.I)
        if moved:
            where = _resolve(where, moved.group(1))
            continue
        for words, _background in bundle_shell.runs(one):
            if not words:
                continue
            program = bundle_shell.program_of(words[0][0])
            if program == "git" or EXEMPT_TOOL in one:
                continue
            args = [(t if t is not None else w) for w, t in words[1:]]
            if re.fullmatch(r"py|pythonw?[0-9.]*|uv|coverage", program):
                module = next((args[at + 1] for at, a in enumerate(args[:-1]) if a == "-m"), "")
                script = next((a for a in args if a.endswith(".py")), "")
                tool = AUDIT_TOOL.search(script.replace(chr(92), "/")) or re.match(
                    r"tools\.audit\.([A-Za-z_][A-Za-z0-9_]*)$", module)
                if tool and not tools_elsewhere and not module.startswith(
                        ("ruff", "pyflakes", "flake8")):
                    return "runs %s, which reads the tree it answers about" % tool.group(0)
                code = " ".join(args)
                for name in _module_names(locked):
                    if module == name or re.search(r"\b%s\b" % re.escape(name), code):
                        return "imports %s, the mutated module" % name
            paths = [a for a in args if not a.startswith("-")]
            recursive = program in _SEARCHES or (program in _GREPS and any(
                re.fullmatch(r"-[A-Za-z]*[rR][A-Za-z]*|--recursive|--dereference-recursive", a)
                for a in args))
            if recursive:
                given = paths[1:] if not any(a in ("-e", "--regexp") or a.startswith("--regexp=")
                                             for a in args) else paths
                if not given and _holds(where, locked):
                    return "%s searches %s, where the mutated module is" % (
                        program, "the project" if where == "" else where or "where it stands")
            for token in paths:
                if _holds(_resolve(where, token), locked):
                    return "%s reads %s" % (program, token)
    return ""


def verdict(command, locked, tools_elsewhere=False):
    """(deny?, reason). Pure, so a test can drive it without a lock file or a hook runner.
    `tools_elsewhere`: every audit tool the command runs certainly lives in another checkout
    (`tool_homes`), so running one does not read the tree this lock is in."""
    if not locked:
        return False, None
    by_running = reads_by_running(command, locked, tools_elsewhere)
    if by_running:
        return True, (
            bundle_shell.policy(__file__) + "\n"
            "A mutation run is holding %s open, so what is on disk right now is a mutant, and "
            "this command %s.\n\n"
            "Wait for the run to finish. To read the committed content meanwhile:\n"
            "    git show HEAD:%s\n" % (locked, by_running, locked))
    for segment in segments(command):
        words = segment.split()
        if not words:
            continue
        if EXEMPT_TOOL in segment:
            continue
        if os.path.basename(words[0].strip("(")) in ("git", "git.exe"):
            continue
        named = readers_of(segment, locked)
        if named:
            return True, (
                bundle_shell.policy(__file__) + "\n"
                "A mutation run is holding %s open, so what is on disk right now is "
                "`ast.unparse` output: comments gone, every line renumbered, one operator "
                "deliberately wrong. This command reads %s.\n\n"
                "It happened: an AST walk over a mutated run.py reported `_sync_filing_index` "
                "at lines 389-415 when it is at 731, and line 762 as belonging to no function. "
                "Nothing errored - the answer was confident and about a file that exists only "
                "between the lock and the finally that restores it.\n\n"
                "Wait for the run to finish. To read the committed content meanwhile:\n"
                "    git show HEAD:%s\n"
                "If no run is actually in flight, the lock is stale - `mutate.py` leaves it "
                "deliberately when a restore fails, and it names the file to `git checkout`.\n"
                % (locked, ", ".join(named), locked))
        tool = AUDIT_TOOL.search(segment)
        if tool and not tools_elsewhere and runs_it(segment, tool):
            return True, (
                bundle_shell.policy(__file__) + "\n"
                "A mutation run is holding %s open, and `%s.py` reads the tree to answer a "
                "question about it. Whatever it walks, it will walk the unparsed module - and "
                "several tools here WRITE a whole-tree baseline from what they read, so this is "
                "not only a wrong answer on screen, it is a wrong answer recorded.\n\n"
                "Wait for the run to finish, then run it again.\n"
                % (locked, tool.group(1)))
    return False, None


def runs_elsewhere(command, root, start, shell=None):
    """Does every program this command runs CERTAINLY run outside every checkout of `root`'s
    repository, naming nothing inside one?

    Measured 2026-10-09 on DF Engine: under a plant drive's lock, `cd <a clone in the scratchpad>
    && python -m pytest ... ruff check tools/audit/x.py` was refused as reading the mutant - the
    clone's own copy of the tool, in a repository no lock was held in. Only a certain answer
    excuses: the command moves with `cd` to a folder `bundle_shell.lands` can place and
    `bundle_rules.landing` puts in no checkout, every later program runs there, and no word any of
    them carries can be read as a path into one. A second cd back, a variable, a glob, a word
    that lands here - each is read against the lock as before."""
    here, moved = start, False
    for one in bundle_shell.split_commands(command):
        cd = _CD.match(one)
        if cd:
            here = bundle_shell.lands(next(g for g in cd.groups() if g), here, shell)
            if here is None or bundle_rules.landing(root, here) is not None:
                return False
            moved = True
            continue
        if not moved:
            return False
        for words, _background in bundle_shell.runs(one):
            if not words:
                return False
            for word, typed in words:
                found = bundle_shell.readings(typed or word, here, shell)
                if not found or any(path is None or bundle_rules.landing(root, path) is not None
                                    for path in found):
                    return False
    return moved


#: A command that only moves: `cd` and its spellings, to one folder, quoted or not.
_CD = re.compile(r"""^\s*(?:cd|chdir|pushd|Set-Location|sl)\s+(?:"([^"]+)"|'([^']+)'|(\S+))\s*$""",
                 re.I)


def judged(payload, root):
    """(deny?, reason) for one call, against the lock of every checkout it can read - and of every
    checkout an audit tool it runs lives in (`tool_homes`)."""
    shell = bundle_shell.shell_of(payload.get("tool_name"))
    acting = bundle_rules.acting_in(payload, root)
    command = bundle_shell.text(payload) if bundle_shell.commands(payload) else ""
    homes = tool_homes(command, root, acting, shell)
    held = places(payload, root)
    for home in homes:
        if home is not None and all(not _same(home, place) for place, _runs in held):
            held.append((home, True))
    for place, runs_here in held:
        locked = locked_module(place)
        if not locked:
            continue
        if runs_here and command and not runs_elsewhere(command, place, acting, shell):
            # THE SAME TEXT THE GATE LOOKED AT - see guard_heredoc.py.
            elsewhere = bool(homes) and all(home is not None and not _same(home, place)
                                            for home in homes)
            deny, reason = verdict(command, locked, elsewhere)
            if deny:
                return deny, reason
        named = touched_paths(bundle_shell.paths(payload), locked, place)
        if named:
            return True, (
                bundle_shell.policy(__file__) + "\n"
                "A mutation run is holding %s open%s, and this call names %s. What is on disk "
                "right now is `ast.unparse` output with one operator deliberately wrong - a "
                "Read or Grep answers about THAT, and an Edit writes over a mutant the run's "
                "`finally` will then overwrite.\n\n"
                "This hook once let every non-shell call through, in a comment calling that the "
                "same answer the old name check gave. It was the same hole.\n\n"
                "To read the committed content meanwhile:\n"
                "    git show HEAD:%s\n" % (locked, "" if place == root else " in the worktree at "
                                           + place, ", ".join(named), locked))
    return False, None


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        sys.exit(0)                      # never break a turn on a parse failure
    # THE SHAPE, NOT THE NAME - see guard_heredoc.py. This guard's whole subject is a command
    # that reads a file mid-mutation, and a command issued through PowerShell reads it just as
    # well. The `Read` tool still falls through here because it carries no command, which is the
    # same answer the name check gave and the reason this swap changes no existing test.
    # THE SHAPE, FOUND AT ANY DEPTH. This read one `command` key at one level, which a
    # BATCHING tool walks straight past by nesting its inputs - the same hole as
    # `tool_name != "Bash"`, one level down, inside the fix for it. `bundle_shell`
    # walks the payload to a bounded depth, takes `command` OR `script` wherever it
    # sits, and reports a command it could not READ as trouble rather than as none.
    if bundle_env.allowed("ALLOW_MUTATION_READ"):
        sys.exit(0)
    root = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
    deny, reason = judged(payload, root)
    if deny:
        json.dump({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }}, sys.stdout)
    sys.exit(0)


if __name__ == "__main__":
    main()
