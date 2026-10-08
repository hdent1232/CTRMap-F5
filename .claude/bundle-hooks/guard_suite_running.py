#!/usr/bin/env python3
"""NEVER EDIT SOURCE WHILE THE SUITE IS RUNNING - refused at the write, while it runs.

CLAUDE.md: *the result is neither the old code nor the new one, and it looks green. Two full runs
were invalidated this way.* It was a rule with nothing behind it: a suite started in the
background, a file edited while it ran, and a green result reported about a tree that never
existed.

WHAT IS MEASURED. The session's own transcript, which the harness writes: a command run in the
background gets a task id (`backgroundTaskId` in its result), and its end is recorded one of three
ways - a `<task-notification>` naming that id and a status, a `TaskStop` result saying it was
stopped, or a container restart's notice listing it. A background command with an id and none of
those is still running.

WHAT IS REFUSED: a Write or Edit to a file of this project - a call carrying content, by shape -
while a background command of this session that MEASURES the tree is still running: a test runner
or a build, read as the PROGRAM that runs (`bundle_shell.runs`), through runners and shells.

WHAT IT CANNOT SEE, said: a command's own writes (`sed -i`, a script) are not refused here, and a
suite started outside this session - another terminal, another session - is not in this
transcript. A transcript that cannot be read is said, never read as an idle machine.

THE WAY OUT: wait for the notification, or stop the task. The owner's way past:
`bundle_env.name("ALLOW_WRITE_DURING_SUITE")`.
"""
import io
import json
import os
import re
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_env        # noqa: E402  - one PREFIX renames every override
import bundle_rules      # noqa: E402  - where the project is
import bundle_shell      # noqa: E402  - the call, and the program it runs, found by shape

LF = chr(10)
BYPASS = bundle_env.name("ALLOW_WRITE_DURING_SUITE")

#: Programs that MEASURE the tree, with the arguments that make them do it - a test runner, a
#: build. Matched against the program `bundle_shell.runs` hands back and its words. ADAPT: a
#: project with a runner of its own adds it here.
MEASURES = (
    r"^(?:pytest|py\.test|tox|nox|ctest|jest|vitest|mocha)\b",
    r"^(?:py|pythonw?[0-9.]*) .*-m (?:pytest|unittest|tox|nox)\b",
    r"^(?:py|pythonw?[0-9.]*) .*\b(?:check_bundle|prove_plants|run_tests|runtests)\.py\b",
    r"^(?:gradle|gradlew)(?:\.bat)? .*\b(?:test|check|build|verify)\b",
    r"^(?:mvn|mvnw)(?:\.cmd)? .*\b(?:test|verify|package|install)\b",
    r"^(?:npm|pnpm|yarn) .*\btest\b",
    r"^(?:go|cargo|dotnet|swift) test\b",
)

#: Adapted per project: see the bootstrap's ADAPT.md.
ADAPT = ("MEASURES",)

_TASK_ID = re.compile(r"<task-id>([^<\s]+)</task-id>")
_STATUS = re.compile(r"<status>([^<\s]+)</status>")

#: THE OTHER TWO WAYS A TASK ENDS, measured on this guard's own session transcript (2026-10-06):
#: a task stopped with `TaskStop` gets no notification - the stop's own result says it ended -
#: and a container restart lists the tasks it took, `(task <id>)`, in a notice of its own. Read
#: from notifications alone, both ran for ever and every write after them was refused.
_STOPPED = re.compile(r"[Ss]uccessfully stopped task: ([A-Za-z0-9_-]+)")
_RESTARTED = re.compile(r"\(task ([A-Za-z0-9_-]+)\)")


def measures(command):
    """Does this command run a program that measures the tree?"""
    for words, _background in bundle_shell.runs(command or ""):
        if not words:
            continue
        said = " ".join([bundle_shell.program_of(words[0][0])]
                        + [(t if t is not None else w) for w, t in words[1:]])
        if any(re.search(pattern, said, re.I) for pattern in MEASURES):
            return True
    return False


def running(transcript_path):
    """{task id: command} for every background command of this session that measures the tree
    and has no notification of its end - or None when the transcript cannot be read."""
    try:
        with io.open(transcript_path, encoding="utf-8", errors="replace") as handle:
            lines = handle.read().splitlines()
    except (OSError, TypeError):
        return None
    launched, task_of, ended = {}, {}, set()
    for line in lines:
        ended.update(_STOPPED.findall(line))
        if "are now stopped" in line:
            ended.update(_RESTARTED.findall(line))
        if "<task-notification>" in line:
            text = line.replace(chr(92) + "n", LF)
            for block in text.split("<task-notification>")[1:]:
                ident, status = _TASK_ID.search(block), _STATUS.search(block)
                if ident and status and status.group(1).lower() not in ("running", "started"):
                    ended.add(ident.group(1))
            continue
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if not isinstance(row, dict):
            continue
        given = row.get("toolUseResult")
        if isinstance(given, dict) and isinstance(given.get("backgroundTaskId"), str):
            results = [part for part in ((row.get("message") or {}).get("content") or [])
                       if isinstance(part, dict) and part.get("type") == "tool_result"]
            if len(results) == 1:
                task_of[results[0].get("tool_use_id")] = given["backgroundTaskId"]
        for part in (row.get("message") or {}).get("content") or [] \
                if isinstance((row.get("message") or {}).get("content"), list) else []:
            if isinstance(part, dict) and part.get("type") == "tool_use" and \
                    (part.get("input") or {}).get("run_in_background"):
                launched[part.get("id")] = bundle_shell.text({"tool_input": part.get("input")})
    out = {}
    for use, command in launched.items():
        task = task_of.get(use)
        if task and task not in ended and measures(command):
            out[task] = command
    return out


def writes_here(payload, root):
    """The project files this call writes content into."""
    if not bundle_shell.writes(payload):
        return []
    out = []
    for target in bundle_shell.paths(payload):
        rel = bundle_rules.rel_of(root, target if os.path.isabs(target) else
                                  os.path.join(root, target))
        if rel != ".." and not rel.startswith("../"):
            out.append(rel)
    return out


def verdict(payload, root):
    """(exit code, message, system message) for one call."""
    if not isinstance(payload, dict) or not bundle_shell.is_tool_call(payload):
        return 0, "", ""
    if bundle_env.allowed("ALLOW_WRITE_DURING_SUITE"):
        return 0, "", ""
    targets = writes_here(payload, root)
    if not targets:
        return 0, "", ""
    live = running(payload.get("transcript_path"))
    if live is None:
        return 0, "", ("suite-running check: the transcript could not be read, so whether a "
                       "background suite is running was NOT checked before this write")
    if not live:
        return 0, "", ""
    task, command = sorted(live.items())[0]
    return 2, (bundle_shell.policy(__file__) + LF
               + "A background run that measures this tree is still running (task %s: `%s`), "
                 "and this writes %s.\n\n"
                 "CLAUDE.md: NEVER EDIT SOURCE WHILE THE SUITE IS RUNNING - the result is "
                 "neither the old code nor the new one, and it looks green. Two full runs were "
                 "invalidated this way.\n\n"
                 "Wait for its notification, or stop the task. The owner's way past: %s=1.\n"
               % (task, command[:120], targets[0], BYPASS)), ""


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
