# -*- coding: utf-8 -*-
"""A COMMIT IS MADE ON TOP OF WHAT MAIN ALREADY HOLDS.

MEASURED 2026-10-09, in this bundle's own repository. Three sessions wrote it at once. On
2026-10-07 main and a session's branch each fixed the same reported defect - a project's copy
with its ADAPT constants set, refused on every update - in different code; a third session's
branch, `ctrmap/hook-modes`, held a third copy of a hook-mode fix main already had, in different
files. By the time anyone merged, main was 38 commits ahead, two implementations of one fix had
to be read side by side to keep one, and the owner asked every session on every project to stop
and land their work "so we can get rid of this drift problem". The same day on DF Engine,
uncommitted changes in one checkout blocked a fast-forward in another.

Nothing asked, at the commit, whether the work was being written on top of main as it stands.
Every one of those duplicates was written by a session that had not merged what main already
held - and a fix written on a stale main is a fix that may already exist there.

WHAT THIS REFUSES: a commit while the remote's default branch holds commits this one would not
contain - neither HEAD, nor the branch being merged in. Merge it first, then commit: the commit is
then written knowing everything main knows, and pushing it to main is a fast-forward.

    python -B tools/behind_main.py      0 when the commit would be on top of main, else REFUSING

UNKNOWN IS NOT AHEAD. Where the remote cannot be fetched, whether main moved is unknown, and the
commit is refused saying so - not waved through on the last thing fetched. A repository with no
remote has no main elsewhere to fall behind, and passes saying that.
"""
import os
import subprocess
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root        # noqa: E402  - the project root, anchored: never counted (F216)
import bounded         # noqa: E402  - a deadline ends the child's whole tree

LF = chr(10)
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

#: Adapted per project: see ADAPT.md.
ADAPT = ("REMOTE", "FETCH_TIMEOUT")

#: The remote whose default branch every commit is made on top of.
REMOTE = "origin"

#: Seconds a fetch of that branch may take before the answer is UNKNOWN.
FETCH_TIMEOUT = 120


def root():
    """The repository this file sits in, ANCHORED - `_tree_root.repo_root`, the one definition
    (F216): this walked to `.git` itself, a second walk `counted_roots.py` refuses."""
    return _tree_root.repo_root(__file__)


def _git(where, *args, timeout=FETCH_TIMEOUT):
    # A FETCH NEVER WAITS ON A PROMPT nobody can see from inside a hook: it fails, and the
    # answer is UNKNOWN.
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
    try:
        return bounded.run(["git", "-C", where] + list(args), capture_output=True, text=True,
                           timeout=timeout, creationflags=NO_WINDOW, env=env)
    except (OSError, subprocess.SubprocessError) as exc:
        return subprocess.CompletedProcess(args, 124, "", str(exc))


def default_branch(where):
    """(branch, None) - the remote's default branch - or (None, why it cannot be named). A
    repository with no such remote answers (None, None): nothing elsewhere to fall behind."""
    if _git(where, "remote", "get-url", REMOTE).returncode != 0:
        return None, None
    named = _git(where, "symbolic-ref", "--quiet", "--short", "refs/remotes/%s/HEAD" % REMOTE)
    if named.returncode == 0 and named.stdout.strip():
        return named.stdout.strip().split("/", 1)[-1], None
    shown = _git(where, "ls-remote", "--symref", REMOTE, "HEAD")
    for line in shown.stdout.splitlines() if shown.returncode == 0 else []:
        if line.startswith("ref:") and "\tHEAD" in line:
            return line.split()[1].rsplit("/", 1)[-1], None
    for guess in ("main", "master"):
        if _git(where, "rev-parse", "--verify", "-q",
                "refs/remotes/%s/%s" % (REMOTE, guess)).returncode == 0:
            return guess, None
    return None, "the default branch of %s cannot be read" % REMOTE


def behind(where):
    """[findings] - empty when the commit being made would hold everything the remote's default
    branch holds, or when there is no remote."""
    branch, why = default_branch(where)
    if branch is None:
        return ["REFUSING: %s, so whether this commit is made on top of it is UNKNOWN" % why] \
            if why else []
    fetched = _git(where, "fetch", "--quiet", REMOTE, branch)
    if fetched.returncode != 0:
        return ["REFUSING: %s/%s could not be fetched (%s), so whether main has moved since this "
                "work began is UNKNOWN - a commit is made on top of main, never on the last "
                "thing fetched" % (REMOTE, branch, (fetched.stderr or "").strip()[:200])]
    ours = ["HEAD"]
    merging = _git(where, "rev-parse", "-q", "--verify", "MERGE_HEAD")
    if merging.returncode == 0:
        ours.append("MERGE_HEAD")
    missing = _git(where, "rev-list", "--count", "%s/%s" % (REMOTE, branch),
                   *["^" + ref for ref in ours])
    if missing.returncode != 0:
        if _git(where, "rev-parse", "-q", "--verify", "HEAD").returncode != 0:
            return []                              # a first commit: there is nothing to be behind
        return ["REFUSING: git could not count what %s/%s holds that this commit would not (%s) "
                "- UNKNOWN" % (REMOTE, branch, missing.stderr.strip()[:200])]
    count = int(missing.stdout.strip() or "0")
    if count:
        return ["REFUSING: %s/%s holds %d commit(s) this commit would not. Two sessions writing "
                "one repository on different bases is how one fix gets written twice - merge it "
                "first (`git merge %s/%s`, resolving to ONE implementation wherever both sides "
                "fixed the same thing), then commit." % (REMOTE, branch, count, REMOTE, branch)]
    return []


#: Asked by `guard_command_rules` for every `git commit`, before git starts.
AT_COMMIT = "before_commit"


def before_commit(message, where):
    """The commit-time rule: `behind`, for the repository the commit is made in."""
    del message
    return behind(where)


def main(argv):
    import cli_words      # a word this does not read refuses the run, before anything runs
    why = cli_words.refused(argv[1:], most=1)
    if why:
        print(why)
        return 2
    where = argv[1] if len(argv) > 1 else root()
    if where is None:
        print("REFUSING: no `.git` above %s - there is no repository to ask"
              % os.path.dirname(os.path.abspath(__file__)))
        return 1
    found = behind(where)
    if found:
        print(LF.join(found))
        return 1
    print("this commit is made on top of what main holds")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
