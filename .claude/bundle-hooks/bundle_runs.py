#!/usr/bin/env python3
"""WHAT A RUN THAT MEASURES THE TREE IS - one answer, for every guard that asks.

CLAUDE.md: NEVER EDIT SOURCE WHILE THE SUITE IS RUNNING. A guard that holds a write while a suite
runs must first know what a suite is. `kind` answers it for a program and its words - the program
as `bundle_shell.program_of` names it, then every word after it - and nothing else asks.

    run      a test runner or a build: while one lives, the tree is being measured
    watch    a runner in WATCH mode: it re-runs after every change and lives for hours by design,
             so it is said and never held - read as a run, one `jest --watch` would hold every
             write of a session
    ''       anything else

THE PROGRAM, NEVER THE TEXT. Every pattern is anchored on the program a command or process runs:
a shell whose `-c` string mentions `pytest` is a shell. ADAPT: a project with a runner of its own
adds it to MEASURES.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_shell      # noqa: E402  - program_of, the one spelling of "which program"

#: Programs that MEASURE the tree, with the words that make them do it - a test runner, a build.
MEASURES = (
    r"^(?:pytest|py\.test|ptw|pytest-watch|tox|nox|ctest|jest|vitest|mocha)\b",
    r"^(?:py|pythonw?[0-9.]*) .*-m (?:pytest|unittest|tox|nox)\b",
    r"^(?:py|pythonw?[0-9.]*) .*\b(?:check_bundle|prove_plants|run_tests|runtests)\.py\b",
    r"^(?:gradle|gradlew)(?:\.bat)? .*\b(?:test|check|build|verify)\b",
    r"^(?:mvn|mvnw)(?:\.cmd)? .*\b(?:test|verify|package|install)\b",
    r"^(?:npm|pnpm|yarn) .*\btest\b",
    r"^(?:go|cargo|dotnet|swift) test\b",
)

#: A runner in watch mode - said, never held.
WATCHES = (
    r"(?:^|\s)(?:--watch(?:All)?|--looponfail|--continuous)(?:[\s=]|$)",
    r"^(?:ptw|pytest-watch)\b",
    r"^(?:pytest|py\.test|(?:py|pythonw?[0-9.]*) .*-m pytest)\b.*\s-f(?:\s|$)",
    r"^(?:gradle|gradlew)(?:\.bat)? (?:.*\s)?-t(?:\s|$)",
)

#: A RUN THAT DECLARES ITSELF, by a lock it writes while it measures: `(repo-relative path, what
#: holds it)`. On Windows a process's working folder is not something it reports, so a battery
#: started in the owner's terminal - its java naming `build/classes`, never the tree - was only SAID
#: by the machine witness and never held. CTRMap's runners write such locks, each with a `pid=`
#: line, and CTRMap kept its own copy of the suite guard to read them, beside this one (CTRMap
#: F167). Empty here: a project lists its own.
LOCKS = (("build/.battery-running", "the battery"),
         (".mutation-in-flight", "a mutation or replant run"))

#: A declared lock that names no process is taken to be live for this long after it was written,
#: and released after - the ninety minutes CTRMap's build.ps1 has always aged its own lock out at.
ABANDONED = 90 * 60

#: Adapted per project: see ADAPT.md.
ADAPT = ("MEASURES", "WATCHES", "LOCKS", "ABANDONED")

#: The settings that ship OFF, at the value they ship at. A project turns them on; the bundle's
#: own tests turn them off again for their run (`bundle_hooks.settings_off`).
SHIPPED_OFF = {"LOCKS": ()}


def lock_runs(root, locks=None):
    """[what each declared lock under `root` says is running]. A lock whose process is alive - or
    cannot be told dead, `bundle_shell.alive` - holds the tree. A `pid=` line names the process, and
    so does `bundle_rules.take_lock`'s `<pid>-<nonce>`; a lock that names none is live while it is
    younger than ABANDONED; one that cannot be READ is live, because unknown is not a finished run.

    `locks`, given, is read IN PLACE OF LOCKS and by the same rule: a project's own reader of one lock
    asks here rather than answering by a rule of its own. CTRMap's battery lock had four readers on
    2026-10-11 - deciding by pid, by age, by bare presence, and by this - so whether a battery was
    running depended on who asked.

    A PID IS A NUMBER THE OPERATING SYSTEM REUSES, and this asked it alone. Measured in CTRMap 2026-10-26: an
    editor ended from outside leaves build/.editor-running naming its pid, and every build refuses while any
    process at all holds that number. The lock's writer began before it wrote the file, so a process that began
    after the file's time is somebody else (`bundle_shell.began_after`); one whose start cannot be read is alive."""
    import time
    found = []
    for rel, what in (LOCKS if locks is None else locks):
        path = os.path.join(root, *rel.replace(chr(92), "/").split("/"))
        if not os.path.isfile(path):
            continue
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                body = handle.read()
            written = os.path.getmtime(path)
            age = time.time() - written
        except OSError as exc:
            found.append("%s, by its lock %s, which cannot be read (%s)" % (what, rel, exc))
            continue
        named = (re.search(r"\bpid\s*=\s*(\d+)", body)
                 or re.search(r"(?m)^(\d+)-[0-9a-f]{16}\s*$", body))
        if named:
            pid = int(named.group(1))
            if bundle_shell.alive(pid) and not bundle_shell.began_after(pid, written):
                found.append("%s, by its lock %s (pid %s)" % (what, rel, named.group(1)))
        elif age <= ABANDONED:
            found.append("%s, by its lock %s (it names no process and was written %d minute(s) "
                         "ago)" % (what, rel, age / 60))
    return found


def said_of(argv):
    """The one string the patterns read: the program, then its words."""
    if not argv:
        return ""
    return " ".join([bundle_shell.program_of(str(argv[0]))] + [str(w) for w in argv[1:]])


def kind(argv):
    """'run', 'watch' or '' for a program and its words."""
    said = said_of(argv)
    if not said or not any(re.search(pattern, said, re.I) for pattern in MEASURES):
        return ""
    return "watch" if any(re.search(pattern, said) for pattern in WATCHES) else "run"
