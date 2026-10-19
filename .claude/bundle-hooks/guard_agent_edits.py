#!/usr/bin/env python3
"""AGENTS THAT CANNOT EDIT EACH OTHER'S FILES MUST NOT EDIT AT THE SAME TIME. Refused at launch.

WHAT IT COST, measured in one project's own documents: eleven agent audit rounds produced 476
finding headlines over 41 distinct code locations, rounds 11 and 13 found nothing the earlier
ones had not, and the fix pass left a handover file whose first line is

    Each agent owned a disjoint file set. These are the changes they identified but could not
    make, because the change belongs in another agent's file.

One agent renamed a check's output keys; the consumer lived in another agent's file, so the fix
was made and NEVER REACHED THE USER. Another entry records two agents fixing the same thing
concurrently, caught by luck. "Producer with no consumer" was filed in seven consecutive audits
and it was MANUFACTURED by the process that found it.

THE CAP IS THE WRONG INSTRUMENT, AND THIS PROJECT HAD RECORDED THE CAP AS THE ENFORCEMENT. README
section 12: *six agents owning disjoint files across a shared contract fail exactly as sixty do;
unlimited tokens make it worse, because more agents means more seams. Refuse parallel EDITING on
its own terms, before width is even measured.* `guard_fanout` counts launches and never asks
what a launch will DO, so four editing agents at once sat comfortably under its cap of four.

WHAT IS REFUSED - an EDITING launch, meaning one given its own worktree or sandbox, or one whose
brief tells it to change something, when it would run BESIDE another writer:

    in the BACKGROUND      the default, and the main thread is a writer too - two writers
    beside a SIBLING       a second editing launch in the same message runs concurrently
    unmeasurable siblings  the transcript cannot be read, so concurrency is UNKNOWN

WHAT IS NOT. A survey - fan out to READ, bring the findings back to one writer - composes, and
this guard leaves it alone. A single editing agent in the foreground is one writer at a time.

A SESSION THAT WORKS ANOTHER REPOSITORY IS NOT A WRITER OF THIS ONE. A message handing work to an
existing session (`bundle_shell.launches`) has no foreground to run in, and was refused here as a
background writer whatever repository it worked: measured 2026-10-09, the owner's instruction to
every open session - land your work on main - could reach none of them. The question this guard
asks is whether two writers share files, and a session's repositories are on record in the
session tools' own listing (`repositories_of`). One that works only OTHER repositories writes no
file of this tree; one that has THIS repository checked out is a second writer of it - that is
how this bundle's main came to hold two implementations of one fix - and so is one whose
repositories are not on record: refused, unless the owner types the code the refusal carries
(`owner_codes`) for that exact message. What it cannot see, said: a brief telling a session that
works elsewhere to clone this repository and write it - deliberate concealment.

The siblings are MEASURED, not guessed: at `PreToolUse` the harness has already written this
call into the transcript under its `tool_use_id` (checked 2026-09-22 by a probe guard that
looked for its own id and found it), so the message this launch belongs to can be read and its
other launches counted.

TO LAUNCH ANYWAY: the owner sets `bundle_env.name("ALLOW_PARALLEL_EDIT")` to 1 for that session.
"""
import io
import json
import os
import re
import subprocess
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_env        # noqa: E402  - one PREFIX renames every override
import bundle_shell      # noqa: E402  - the call, found by shape, at any depth
import bounded         # noqa: E402  - a deadline ends the child's whole tree
import owner_codes       # noqa: E402  - the owner's typed yes for a message to a shared repository
import request_ledger    # noqa: E402  - the one launch the ledger itself demands

BYPASS = bundle_env.name("ALLOW_PARALLEL_EDIT")

#: Isolation that exists to let an agent write somewhere of its own.
EDITING_ISOLATION = ("worktree", "remote")

#: A brief that tells an agent to CHANGE something, as an imperative: at the start of a
#: sentence, a line or a list item, or after `please`, `then`, `and`, `also`, `now`. A verb in
#: any other position is prose - "report which lines to fix" asks for a READ.
_VERBS = (r"(?:fix|edit|implement|apply|change|refactor|modify|update|create|delete|remove|"
          r"rename|replace|rewrite|commit|patch|port|migrate|install)")
_IMPERATIVE = re.compile(
    r"(?:^|[.!?:;]\s+|\n\s*(?:[-*]|\d+[.)])?\s*|\b(?:please|then|and|also|now)\s+)"
    + _VERBS + r"\b", re.I)
_WRITE_A_THING = re.compile(
    r"\b(?:write|add)\s+(?:a |an |the |new |missing |some )?(?:tests?|files?|code|scripts?|"
    r"modules?|functions?|methods?|guards?|hooks?|checks?|patch(?:es)?|fix(?:es)?|entr(?:y|ies))\b",
    re.I)


def editing(launch):
    """Does this launch WRITE - its own workspace, or a brief telling it to change something?"""
    if str(launch.get("isolation") or "").lower() in EDITING_ISOLATION:
        return True
    brief = bundle_shell.brief(launch)
    return bool(_IMPERATIVE.search(brief) or _WRITE_A_THING.search(brief))


GIT_TIMEOUT = 20
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0


def owner_repo(url):
    """`owner/repo`, lower-case, from a repository URL in any of git's spellings; '' if none."""
    parts = [p for p in re.split(r"[/:]", (url or "").strip()) if p]
    if len(parts) < 2:
        return ""
    name = parts[-1][:-4] if parts[-1].endswith(".git") else parts[-1]
    return ("%s/%s" % (parts[-2], name)).lower()


def repository(root):
    """`owner/repo` of the repository at `root`, from its origin; '' when git cannot say."""
    try:
        done = bounded.run(["git", "-C", root, "config", "--get", "remote.origin.url"],
                           capture_output=True, text=True, timeout=GIT_TIMEOUT,
                           creationflags=NO_WINDOW)
    except (OSError, subprocess.SubprocessError):
        return ""
    return owner_repo(done.stdout) if done.returncode == 0 else ""


def repositories_of(rows, session, transcript_path=None):
    """The `owner/repo`s the session `session` works, as the LATEST session listing in this
    transcript recorded them (`bundle_shell.listed_session`, the one reader) - None when no
    listing names it."""
    body = bundle_shell.listed_session(rows, session, transcript_path)
    listed = re.search(r'"sources"\s*:\s*\[([^\]]*)\]', body or "")
    # A LISTING THAT NAMES NO REPOSITORY SAYS NOTHING ABOUT ONE: a session on the owner's machine
    # is listed with none, whatever it has checked out. Read as an empty list, it was a session
    # that writes nothing - and allowed.
    found = sorted({owner_repo(url) for url in re.findall(
        r'"url"\s*:\s*"([^"]+)"', listed.group(1))} - {""}) if listed else None
    return found or None


def shared(payload, launch):
    """Why a message to an existing session would make it a second writer of THIS repository,
    or '' when its repositories are on record and this is not one of them."""
    session = next((launch.get(key) for key in bundle_shell.ADDRESSEE_KEYS
                    if isinstance(launch.get(key), str) and launch[key].startswith("session_")),
                   None)
    if session is None:
        return "it is handed to a writer that is not a session with a listing"
    root = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
    here = repository(root)
    if not here:
        return "which repository this is cannot be read from git"
    held = repositories_of(request_ledger.read_rows(payload.get("transcript_path")), session,
                           payload.get("transcript_path"))
    if held is None:
        return ("%s's repositories are not on record - no session listing in this transcript "
                "names it; look it up first" % session)
    if here in held:
        return "%s has %s checked out too - a second writer of this repository" % (session, here)
    return ""


def siblings(transcript_path, tool_use_id):
    """Every tool_use input in the message this call belongs to, or None when it cannot tell.

    None means COULD NOT LOOK - no id, no transcript, or this id not in it - and the caller
    treats that as unknown concurrency rather than as none.
    """
    if not tool_use_id or not transcript_path:
        return None
    try:
        with io.open(transcript_path, encoding="utf-8", errors="replace") as handle:
            lines = handle.read().splitlines()
    except (OSError, TypeError):
        return None
    by_message, owner = {}, None
    for line in lines:
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        if not isinstance(entry, dict) or entry.get("type") != "assistant":
            continue
        message = entry.get("message") or {}
        key = message.get("id") or entry.get("requestId") or entry.get("uuid")
        for block in message.get("content") or []:
            if isinstance(block, dict) and block.get("type") == "tool_use":
                by_message.setdefault(key, []).append(block.get("input") or {})
                if block.get("id") == tool_use_id:
                    owner = key
    if owner is None:
        return None
    return by_message.get(owner, [])


def verdict(payload):
    """(deny?, reason) for one call. Pure apart from reading the transcript it is pointed at."""
    # THE REVIEW THE LEDGER DEMANDS IS A SURVEY, and a hook that refused it left a turn nothing
    # could end - measured 2026-09-26: the ledger's stop demanded the owed review, this guard read
    # the owner's quoted words in its prompt ("fix", "build") as an editing brief and, unable to
    # find the call in the transcript, refused it; the only way out was an override. The owed
    # review is one synchronous launch of the prompt the ledger writes, on the declared model -
    # `guard_fanout` already lets exactly that through, and every launch guard must.
    if request_ledger.is_owed_review(payload):
        return False, None
    # A SESSION ALREADY RUNNING IS A WRITER WHETHER OR NOT IT IS MESSAGED: telling it to change its own work
    # adds no writer beside this one. A subagent resumed, or a session nobody listed, is still judged.
    if bundle_shell.message_to_session(payload, request_ledger.read_rows(payload.get("transcript_path"))):
        return False, None
    found = [launch for launch in bundle_shell.launches(payload) if editing(launch)]
    started = []
    for launch in found:
        if not any(key in launch for key in bundle_shell.ADDRESSEE_KEYS):
            started.append(launch)
            continue
        # WORK HANDED TO A WRITER THAT ALREADY EXISTS - it has no foreground to run in, and it is
        # a second writer of THIS tree only when it works this repository (`shared`).
        why = shared(payload, launch)
        if not why:
            continue
        if owner_codes.given(payload, request_ledger.read_rows(payload.get("transcript_path"))):
            continue
        return True, (
            bundle_shell.policy(__file__) + "\n"
            "This hands work to another writer while %s. Two writers of one repository who "
            "cannot see each other's files is how this bundle's main came to hold two "
            "implementations of one fix.\n\n"
            "The owner approves THIS message, once, by typing:\n\n    %s\n\n"
            "Then send the identical call again. Or hand it only work in its own repositories, "
            "and bring anything for this one back here.\n" % (why, owner_codes.issue("FANOUT")))
    found = started
    if not found:
        return False, None
    tool_input = payload.get("tool_input") or {}
    background = tool_input.get("run_in_background")
    if background is not False:
        return True, (
            bundle_shell.policy(__file__) + "\n"
            "This launches an EDITING agent in the background%s, where it writes at the same "
            "time as the main thread - two writers who cannot see each other's files.\n\n"
            "MEASURED: eleven audit rounds, 476 findings over 41 locations, and a fix pass "
            "whose handover opens 'these are the changes they identified but could not make, "
            "because the change belongs in another agent's file' - one of which never reached "
            "the user.\n\n"
            "Run it with run_in_background: false, so one writer works at a time - or make it "
            "a SURVEY whose findings come back to you to apply.\n"
            % ("" if background else " (the default)"))
    held = siblings(payload.get("transcript_path"), payload.get("tool_use_id"))
    if held is None:
        return True, (
            bundle_shell.policy(__file__) + "\n"
            "This launches an EDITING agent, and whether another editing launch is in the "
            "same message could not be read from the transcript. Concurrency that cannot be "
            "measured is not concurrency of one.\n\n"
            "Set %s=1 for this session if the transcript format has changed.\n" % BYPASS)
    writers = sum(1 for tool in held for launch in bundle_shell.launches({"tool_input": tool})
                  if editing(launch))
    if writers > 1:
        return True, (
            bundle_shell.policy(__file__) + "\n"
            "This message launches %d EDITING agents at once. Agents that cannot edit each "
            "other's files must not edit at the same time: a disjoint-file split across a "
            "shared contract is where a producer gets written and its consumer never wired.\n\n"
            "Launch them one at a time in the foreground, or fan out to READ and apply the "
            "findings yourself.\n" % writers)
    return False, None


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        sys.exit(0)                      # never break a turn on a parse failure
    if not isinstance(payload, dict) or not bundle_shell.launches(payload):
        sys.exit(0)
    if bundle_env.allowed("ALLOW_PARALLEL_EDIT"):
        sys.exit(0)
    deny, reason = verdict(payload)
    if deny:
        json.dump({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }}, sys.stdout)
    sys.exit(0)


if __name__ == "__main__":
    main()
