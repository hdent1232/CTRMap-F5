#!/usr/bin/env python3
"""NEVER PUSH WITHOUT EXPLICIT PER-PUSH APPROVAL. Approval for one push is not the next.

WHY THIS EXISTS. The owner's standing rule has been in force since this project began and it is
specific: approval for one push is not approval for the next. It was also a rule with nothing
behind it. The memory index records what that cost once already - "publish immediately" was read
as standing permission when it had been conditional on a review that never happened.

There is no honest way for a hook to know what the owner said. What it CAN refuse is a push
that nobody deliberately authorised for THIS COMMIT:

  * no approval token at all
  * a token that names a different commit than the push SENDS - so every new commit needs a new
    approval, which is exactly what "per-push" means and exactly what habit erodes
  * a token that names a different remote or a different ref than the push does
  * a force push, or a deletion, that the token does not say in so many words is a force push
  * a push whose commit cannot be told, or that sends every branch or tag at once
  * a token with no record of what the owner actually said

The token is written by `tools/guard/approve_push.py`, which asks for the owner's words and
pins the commit. It is not consumed on use, because the commit it names is: the moment there is
a new commit, that commit is a different thing to publish and the token does not name it.

THE APPROVAL IS COMPARED WITH WHAT THE PUSH SENDS, NOT WITH HEAD. Measured 2026-09-26: this
compared the token with HEAD whatever the command pushed, so with an approval for HEAD,
`git push origin <any other commit>:master` published a commit the owner never saw - and the
push the owner HAD approved, an ancestor of a newer local commit, was refused. The ref the token
names was never compared either, though this docstring said it was, and an empty-source refspec
(`:master`, which deletes the remote branch) did not count as a deletion. Each refspec is now
resolved to the commit it sends and the ref it lands on; one that cannot be resolved is
UNKNOWN, and an unknown commit is not an approved one.

AND A PUSH IT CANNOT RECOGNISE IS A PUSH IT NEVER JUDGES. The same day: the pattern that finds a
push read `-C` only as one bare word, so `git -C "<a folder with a space>" push` - every folder
this owner's work lives in has one - was not a push at all, and went out with no token. So did
`VAR=value git push`, `env VAR=value git push`, and a quoted path to git.exe, with or without
PowerShell's `&`. Each push is now judged in the repository its OWN `-C` names.

TO PUSH ANYWAY: there is no environment bypass here on purpose. Write the token.
"""
import os
import re
import subprocess
import sys
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)  # no console window for a captured child (F184)

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shellin                                    # noqa: E402  (path set above)

HOOK = "guard_push.py"
TOKEN = ".push-approved"

BACKSLASH = chr(92) * 2                           # one literal backslash, escaped for a pattern
#: One shell word: double-quoted, single-quoted, or bare.
_WORD = r"(?:\"[^\"]*\"|'[^']*'|[^\s;|&\"']+)"
#: What may stand before git in one command: `VAR=value` assignments, and the wrappers that run
#: the next word as the command.
_LEAD = r"(?:(?:[A-Za-z_]\w*=" + _WORD + r"?|env|command|nohup|time|exec|sudo|builtin)\s+)*"
#: git itself, bare or by path, the path quoted or not.
_GIT = (r"(?:\"[^\"]*[/" + BACKSLASH + r"]|'[^']*[/" + BACKSLASH + r"]|[^\s;|&\"']*[/"
        + BACKSLASH + r"])?git(?:\.exe)?[\"']?")
#: git's options before the subcommand; `-C` and `-c` take the NEXT word, quoted or not.
_GITOPT = r"(?:\s+-[Cc]\s+" + _WORD + r"|\s+-[^\s;|&]+)"
#: `git push`, `git -C x push`, `git.exe push`. Not `git push --help`, not a grep for the word.
_PUSH = re.compile(r"(?:^|[;|&(]|\$\()\s*" + _LEAD + _GIT + r"(" + _GITOPT + r"*)\s+push\b"
                   r"([^;|&]*)", re.I)
_HELP = re.compile(r"--help|-h\b")
_FORCE = re.compile(r"--force\b|--force-with-lease|(?:^|\s)\+[\w./-]+:|--delete\b|--mirror\b")
_DIR = re.compile(r"(?:^|\s)-C\s+(" + _WORD + r")")


def pushes(command):
    """Every `git push` in this command line: (git's own options, the push's arguments)."""
    out = []
    for found in _PUSH.finditer(command):
        tail = found.group(2) or ""
        if _HELP.search(tail):
            continue
        out.append(((found.group(1) or "").strip(), tail.strip()))
    return out


def repo_of(command, cwd, options=""):
    """The repository a push runs in.

    Its OWN `git -C <dir>` when given, then a `cd <dir>` earlier on the same line, then the
    working directory - and if that is not a repository, one level down, because this project's
    tree holds the repository inside the session folder the hooks are wired from.
    """
    found = _DIR.search(options)
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


def head_of(repo, rev="HEAD"):
    """(sha, trouble) of `rev` as a commit. A repository or revision this cannot read is UNKNOWN,
    never an empty one."""
    try:
        p = subprocess.Popen(["git", "-C", repo, "rev-parse", "--verify", "--quiet",
                              rev + "^{commit}"],
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=NO_WINDOW)
        out, err = p.communicate(timeout=20)
    except Exception as cannotAsk:
        return None, "%s: %s" % (type(cannotAsk).__name__, cannotAsk)
    if p.returncode != 0:
        return None, ((err or b"").decode("utf-8", "replace").strip()[:200]
                      or "%s names no commit here" % rev)
    return out.decode("utf-8", "replace").strip(), None


def _branch_of(repo):
    """The branch HEAD is on, or None when detached or unreadable."""
    try:
        p = subprocess.Popen(["git", "-C", repo, "symbolic-ref", "--quiet", "--short", "HEAD"],
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=NO_WINDOW)
        out, _ = p.communicate(timeout=20)
    except Exception:
        return None
    if p.returncode != 0:
        return None
    return out.decode("utf-8", "replace").strip() or None


#: Options of `git push` that take the NEXT word as their value, so it is not a positional.
_VALUED = ("-o", "--push-option", "--repo", "--receive-pack", "--exec")
#: Options that send more than one named ref - an approval names ONE commit.
_MANY = ("--all", "--mirror", "--tags", "--branches")


def _words(tail):
    """The push's words, with shell redirections and their targets removed - `2>&1` or `> log` is
    the shell's, not a refspec."""
    out, skip = [], False
    for word in tail.split():
        if skip:
            skip = False
            continue
        if re.match(r"^\d*[<>]", word):
            skip = bool(re.match(r"^\d*(?:>>?|<)$", word))
            continue
        out.append(word.strip('"' + chr(39)))
    return out


def positional(tail):
    """The push's positional words - the remote, then its refspecs - skipping every option and the
    value an option like `-o` takes."""
    words, out, at = _words(tail), [], 0
    while at < len(words):
        if words[at] in _VALUED:
            at += 2
            continue
        if not words[at].startswith("-"):
            out.append(words[at])
        at += 1
    return out


def sends(repo, tail):
    """([(commit, ref it lands on or None, the spec)], trouble) - what this push publishes. A
    refspec with an EMPTY source deletes the remote ref, and is returned with commit None."""
    many = [w for w in _words(tail) if w.split("=")[0] in _MANY]
    if many:
        return None, ("it sends more than one named ref (%s), and an approval names one commit"
                      % many[0])
    specs = positional(tail)[1:]
    if not specs:
        branch = _branch_of(repo)
        sha, trouble = head_of(repo)
        if sha is None:
            return None, trouble
        return [(sha, branch, "(the current branch)")], None
    out = []
    for spec in specs:
        source, _, target = spec.lstrip("+").partition(":")
        if not source:
            out.append((None, target, spec))
            continue
        sha, trouble = head_of(repo, source)
        if sha is None:
            return None, "%s: %s" % (spec, trouble)
        landed = target or source
        if landed.startswith("refs/heads/"):
            landed = landed[len("refs/heads/"):]
        out.append((sha, landed, spec))
    return out, None


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
    for options, tail in pushes(command):
        deny, reason = _judge(repo_of(command, cwd, options), tail)
        if deny:
            return deny, reason
    return False, None


#: git's own commands. An alias cannot shadow one, so only another word is looked up as an alias.
_BUILTIN = frozenset((
    "add", "am", "apply", "archive", "bisect", "blame", "branch", "bundle", "cat-file", "checkout",
    "cherry", "cherry-pick", "clean", "clone", "commit", "config", "describe", "diff", "fetch",
    "for-each-ref", "format-patch", "fsck", "gc", "grep", "hash-object", "help", "init", "log",
    "ls-files", "ls-remote", "ls-tree", "merge", "merge-base", "mv", "name-rev", "notes", "pull",
    "push", "rebase", "reflog", "remote", "reset", "restore", "rev-list", "rev-parse", "revert",
    "rm", "shortlog", "show", "show-ref", "stash", "status", "submodule", "switch",
    "symbolic-ref", "tag", "update-index", "update-ref", "var", "version", "worktree"))
#: git's options before the subcommand that take the NEXT word as their value.
_GIT_VALUED = ("-C", "-c", "--git-dir", "--work-tree", "--namespace", "--exec-path",
               "--config-env")


def _alias(directory, options, sub):
    """What alias `sub` expands to - from a `-c alias.<sub>=` on the line, else the repository's
    own config - or None when it is not an alias."""
    key = "alias.%s=" % sub.lower()
    for option, value in options:
        if option == "-c" and value and value.lower().startswith(key):
            return value.split("=", 1)[1]
        if option.lower().startswith("-c" + key):
            return option.split("=", 1)[1]
    try:
        p = subprocess.Popen(["git", "-C", directory, "config", "--get", "alias." + sub],
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=NO_WINDOW)
        out, _ = p.communicate(timeout=20)
    except Exception:
        return None
    if p.returncode != 0:
        return None
    return out.decode("utf-8", "replace").strip() or None


def dealiased(command, cwd, depth=0):
    """The commands `command` runs once a git alias in it is expanded: `git -c alias.p=push p
    origin master` is `git push origin master` (F175), and a `!` alias is the shell text it runs.
    Empty when there is no alias to expand."""
    read = shellin.reader()
    held = read.pairs(command)
    if not held or depth > 4 or read.program_of(held[0][0]) != "git":
        return []
    at, options = 1, []
    while at < len(held) and held[at][0].startswith("-"):
        option = held[at][0]
        if option in _GIT_VALUED and at + 1 < len(held):
            options.append((option, held[at + 1][0]))
            at += 2
            continue
        options.append((option, None))
        at += 1
    if at >= len(held) or held[at][0].lower() in _BUILTIN:
        return []
    directory = cwd
    for option, value in options:
        if option == "-C" and value:
            directory = os.path.join(directory, value)
    value = _alias(directory, options, held[at][0])
    if not value:
        return []
    rest = read.joined_text(held[at + 1:])
    if value.startswith("!"):
        return shellin.each_command(value[1:] + " " + rest)
    spelled = " ".join(["git"] + [w for o, v in options for w in ((o, v) if v else (o,))]
                       + [value, rest]).strip()
    return [spelled] + dealiased(spelled, cwd, depth + 1)


def _judge(repo, tail):
    """(deny, reason) for one push of `tail`, run in `repo`."""
    fields, trouble = token_of(repo)
    if fields is None:
        return True, _no("%s.\n\nThe owner's standing rule is that approval for one push is not "
                         "approval for the next. Ask them, quote what they say, and record it:\n"
                         "    python tools/guard/approve_push.py \"<what they said>\"" % trouble)
    if len(fields.get("said", "")) < 20:
        return True, _no("the approval token records no usable quote of what the owner said. An "
                         "approval nobody can check is the shape that turned a conditional "
                         "'publish immediately' into a standing permission here.")
    forced = fields.get("force", "").lower() in ("yes", "true", "1")
    approved = fields.get("sha", "").lower()
    if _FORCE.search(tail) and not forced:
        return True, _no("this is a force push or a deletion (%s) and the approval does not "
                         "say so. A force push discards published history; it needs its own "
                         "word from the owner, not a general yes." % tail.strip()[:60])
    remote = (positional(tail) or [None])[0]
    if remote and fields.get("remote") and remote != fields["remote"]:
        return True, _no("the approval is for remote %r and this pushes to %r"
                         % (fields["remote"], remote))
    sent, trouble = sends(repo, tail)
    if sent is None:
        return True, _no("which commit this push publishes is UNKNOWN (%s) - and an unknown "
                         "commit is not an approved one" % trouble)
    return _judge_sent(fields, sent)


def _judge_sent(fields, sent):
    """(deny, reason) for the commits a push sends, [(commit or None, ref it lands on, spec)],
    against the token's fields - the question this hook and the pre-push hook both ask."""
    forced = fields.get("force", "").lower() in ("yes", "true", "1")
    approved = fields.get("sha", "").lower()
    for sha, ref, spec in sent:
        if sha is None and not forced:
            return True, _no("%s DELETES the remote ref %r, and the approval does not say it is "
                             "a force push or a deletion. That needs its own word from the "
                             "owner, not a general yes." % (spec, ref))
        if sha is not None and sha.lower() != approved:
            return True, _no(
                "the approval names commit %s, and the push sends %s (%s).\n\nThat is the rule "
                "working, not a bug: a commit the owner has not seen is a new thing to publish. "
                "Ask again and record what they say."
                % (approved[:12] or "(none)", sha[:12], spec))
        if fields.get("ref") and ref != fields["ref"]:
            return True, _no("the approval is for ref %r and %s lands on %r"
                             % (fields["ref"], spec, ref))
    return False, None


def _no(why):
    return ("BLOCKED BY PROJECT POLICY (.claude/hooks/%s).\n"
            "NEVER PUSH WITHOUT EXPLICIT PER-PUSH APPROVAL: %s\n" % (HOOK, why))


def decide(payload):
    blind = shellin.unreadable(payload)
    if blind:
        return True, shellin.blind_refusal(HOOK, "; ".join(blind), "<no bypass>")
    cwd = str((payload or {}).get("cwd") or os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd())
    #EACH PUSH IN THE FOLDER IT RUNS IN (F175): split into single commands, `cd x && git push`
    #lost its `cd`, and a push judged in the call's own folder reads THAT repository's approval
    for command, where in shellin.located(payload):
        for one in [command] + dealiased(command, cwd if where is None
                                         else os.path.join(cwd, where)):
            if where is None and pushes(one):
                return True, _no("this push runs after a move to a folder the command does "
                                 "not name (%s), so which repository it publishes is UNKNOWN - "
                                 "and an unknown repository is not an approved one. Name the "
                                 "folder, or use git -C <folder> push." % command.strip()[:80])
            deny, reason = verdict(one, os.path.join(cwd, where or ""))
            if deny:
                return deny, reason
    return False, None


def pre_push(remote, lines, repo):
    """(deny, reason) for what git hands a pre-push hook: the remote's name, and one line per ref
    - `<local ref> <local sha> <remote ref> <remote sha>`.

    THE SECOND DOOR (F175). The hook above judges a push it can READ in a tool call; a push it
    cannot read - spelled some way no reader knows, or typed in a terminal by an agent with no
    hooks - reached the remote with nothing asked. git itself hands this the commits it is about
    to send, already resolved, so nothing here depends on how the push was spelled.
    """
    fields, trouble = token_of(repo)
    if fields is None:
        return True, _no("%s." % trouble)
    if len(fields.get("said", "")) < 20:
        return True, _no("the approval token records no usable quote of what the owner said.")
    if fields.get("remote") and remote != fields["remote"]:
        return True, _no("the approval is for remote %r and this pushes to %r"
                         % (fields["remote"], remote))
    forced = fields.get("force", "").lower() in ("yes", "true", "1")
    sent = []
    for line in lines:
        words = line.split()
        if len(words) != 4:
            continue
        local_ref, local_sha, remote_ref, remote_sha = words
        landed = remote_ref[len("refs/heads/"):] if remote_ref.startswith("refs/heads/") \
            else remote_ref
        deleting = set(local_sha) == {"0"}
        sent.append((None if deleting else local_sha, landed, local_ref))
        if not deleting and set(remote_sha) != {"0"} and not forced:
            #a push that is not a fast-forward discards what the remote holds: a force push,
            #whether or not the word `--force` was typed - and one this cannot check is not
            #known to be anything else
            try:
                behind = subprocess.call(["git", "-C", repo, "merge-base", "--is-ancestor",
                                          remote_sha, local_sha],
                                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except Exception:
                behind = 2
            if behind != 0:
                return True, _no("%s does not fast-forward %s on the remote (or that cannot be "
                                 "told), so it discards published history - a force push, and "
                                 "the approval does not say so." % (local_ref, remote_ref))
    if not sent:
        return False, None
    return _judge_sent(fields, sent)


def main_pre_push(argv, stdin):
    """The `.githooks/pre-push` entry: exit 1 with the refusal when the push is not approved."""
    repo = os.getcwd()
    deny, reason = pre_push(argv[0] if argv else "", stdin.read().splitlines(), repo)
    if deny:
        sys.stderr.write(reason)
        return 1
    return 0


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
    if sys.argv[1:2] == ["--pre-push"]:
        sys.exit(main_pre_push(sys.argv[2:], sys.stdin))
    main()
