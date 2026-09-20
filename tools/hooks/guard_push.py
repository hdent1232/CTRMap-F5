#!/usr/bin/env python3
"""NEVER PUSH WITHOUT EXPLICIT PER-PUSH APPROVAL. Approval for one push is not the next.

WHY THIS EXISTS. The owner's standing rule has been in force since this project began and it is
specific: approval for one push is not approval for the next. It was also a rule with nothing
behind it. The memory index records what that cost once already - "publish immediately" was read
as standing permission when it had been conditional on a review that never happened.

There is no honest way for a hook to know what the owner said. What it CAN refuse is a push
that nobody deliberately authorised for THIS COMMIT:

  * no approval token at all
  * a token that names a different commit - so every new commit needs a new approval, which is
    exactly what "per-push" means and exactly what habit erodes
  * a token that names a different remote or a different ref than the push does
  * a force push, or a deletion, that the token does not say in so many words is a force push
  * a token with no record of what the owner actually said

The token is written by `tools/guard/approve_push.py`, which asks for the owner's words and
pins the commit. It is not consumed on use, because the commit it names is: the moment there is
a new commit, HEAD moves and the token stops matching.

TO PUSH ANYWAY: there is no environment bypass here on purpose. Write the token.
"""
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shellin                                    # noqa: E402  (path set above)

HOOK = "guard_push.py"
TOKEN = ".push-approved"

#: `git push`, `git -C x push`, `git.exe push`. Not `git push --help`, not a grep for the word.
_PUSH = re.compile(r"(?:^|[;|&]|&&)\s*(?:[^\s;|&]*[/" + chr(92) * 2 + r"])?git(?:\.exe)?\b"
                   r"((?:\s+-[^\s]+|\s+-C\s+[^\s]+)*)\s+push\b([^;|&]*)", re.I)
_HELP = re.compile(r"--help|-h\b")
_FORCE = re.compile(r"--force\b|--force-with-lease|(?:^|\s)\+[\w./-]+:|--delete\b|--mirror\b")


def pushes(command):
    """Every `git push` in this command line, with its arguments."""
    out = []
    for found in _PUSH.finditer(command):
        tail = found.group(2) or ""
        if _HELP.search(tail):
            continue
        out.append(tail.strip())
    return out


def repo_of(command, cwd):
    """The repository a push runs in.

    `git -C <dir>` when given, then a `cd <dir>` earlier on the same line, then the working
    directory - and if that is not a repository, one level down, because this project's tree
    holds the repository inside the session folder the hooks are wired from.
    """
    found = re.search(r"-C\s+([^\s]+)", command)
    if found:
        return found.group(1).strip('"' + chr(39))
    found = re.search(r"(?:^|[;|&])\s*cd\s+([^;|&]+?)\s*(?:&&|;|$)", command)
    if found:
        return found.group(1).strip().strip('"' + chr(39))
    if os.path.isdir(os.path.join(cwd, ".git")):
        return cwd
    try:
        for name in sorted(os.listdir(cwd)):
            if os.path.isdir(os.path.join(cwd, name, ".git")):
                return os.path.join(cwd, name)
    except OSError:
        pass
    return cwd


def head_of(repo):
    """(sha, trouble). A repository this cannot read has an UNKNOWN head, never an empty one."""
    try:
        p = subprocess.Popen(["git", "-C", repo, "rev-parse", "HEAD"],
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        out, err = p.communicate(timeout=20)
    except Exception as cannotAsk:
        return None, "%s: %s" % (type(cannotAsk).__name__, cannotAsk)
    if p.returncode != 0:
        return None, (err or b"").decode("utf-8", "replace").strip()[:200]
    return out.decode("utf-8", "replace").strip(), None


def token_of(repo):
    """(fields, trouble) from the approval token."""
    path = os.path.join(repo, TOKEN)
    try:
        with open(path, encoding="utf-8") as handle:
            body = handle.read()
    except OSError as cannotRead:
        return None, "there is no approval token at %s (%s)" % (path, cannotRead.strerror)
    fields = {}
    for line in body.splitlines():
        if "=" in line and not line.strip().startswith("#"):
            key, value = line.split("=", 1)
            fields[key.strip().lower()] = value.strip()
    return fields, None


def verdict(command, cwd):
    """(deny, reason) for one shell command."""
    wanted = pushes(command)
    if not wanted:
        return False, None
    repo = repo_of(command, cwd)
    head, trouble = head_of(repo)
    if head is None:
        return True, _no("this could not read HEAD in %s (%s), so which commit is about to be "
                         "published is UNKNOWN - and an unknown commit is not an approved one"
                         % (repo, trouble))
    fields, trouble = token_of(repo)
    if fields is None:
        return True, _no("%s.\n\nThe owner's standing rule is that approval for one push is not "
                         "approval for the next. Ask them, quote what they say, and record it:\n"
                         "    python tools/guard/approve_push.py \"<what they said>\"" % trouble)
    if fields.get("sha", "").lower() != head.lower():
        return True, _no(
            "the approval names commit %s, and HEAD is now %s.\n\nThat is the rule working, not "
            "a bug: a new commit is a new thing to publish, and the owner has not seen it. Ask "
            "again and record what they say."
            % (fields.get("sha", "(none)")[:12], head[:12]))
    if len(fields.get("said", "")) < 20:
        return True, _no("the approval token records no usable quote of what the owner said. An "
                         "approval nobody can check is the shape that turned a conditional "
                         "'publish immediately' into a standing permission here.")
    for tail in wanted:
        if _FORCE.search(tail) and fields.get("force", "").lower() not in ("yes", "true", "1"):
            return True, _no("this is a force push or a deletion (%s) and the approval does not "
                             "say so. A force push discards published history; it needs its own "
                             "word from the owner, not a general yes." % tail.strip()[:60])
        remote = _first_positional(tail)
        if remote and fields.get("remote") and remote != fields["remote"]:
            return True, _no("the approval is for remote %r and this pushes to %r"
                             % (fields["remote"], remote))
    return False, None


def _first_positional(tail):
    for word in tail.split():
        if not word.startswith("-"):
            return word
    return None


def _no(why):
    return ("BLOCKED BY PROJECT POLICY (.claude/hooks/%s).\n"
            "NEVER PUSH WITHOUT EXPLICIT PER-PUSH APPROVAL: %s\n" % (HOOK, why))


def decide(payload):
    blind = shellin.unreadable(payload)
    if blind:
        return True, shellin.blind_refusal(HOOK, "; ".join(blind), "<no bypass>")
    cwd = str((payload or {}).get("cwd") or os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd())
    for command in shellin.commands(payload):
        deny, reason = verdict(command, cwd)
        if deny:
            return deny, reason
    return False, None


def main():
    import json
    try:
        payload = json.load(sys.stdin)
    except Exception:
        sys.exit(0)                      # never break a turn on a parse failure
    deny, reason = decide(payload)
    if deny:
        json.dump({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }}, sys.stdout)
    sys.exit(0)


if __name__ == "__main__":
    main()
