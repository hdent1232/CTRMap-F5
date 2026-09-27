#!/usr/bin/env python3
"""Record the owner's approval for ONE push of ONE commit.

The owner's standing rule: NEVER PUSH WITHOUT EXPLICIT PER-PUSH APPROVAL, and approval for one
push is not approval for the next. `.claude/hooks/guard_push.py` refuses a push that has no
token, or a token naming a different commit, remote, ref, or not naming a force push as one.

This writes that token. It pins the CURRENT HEAD, so the approval expires the moment there is
another commit - which is what "per push" means, and what habit erodes. `--commit` pins the one
commit the owner named instead, since the guard compares the token with what the push SENDS: an
approved ancestor of a newer local commit is pushed as `git push origin <that commit>:<ref>`.

    python tools/guard/approve_push.py "<what the owner actually said>"
    python tools/guard/approve_push.py "<...>" --remote origin --ref master --force
    python tools/guard/approve_push.py "<...>" --commit <rev> --repo <another repository>

`--repo` writes the token into another repository - the bundle, the notes - and makes sure that
repository's git ignores it, so an approval can never be committed and pushed along with the
commit it approves.

The quote is not decoration. The memory index records what a missing one cost: "publish
immediately" was read as standing permission when it had been conditional on a review that
never happened. A token with no quote is refused by the hook.
"""
import io
import os
import subprocess
import sys
import time

LF = chr(10)
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TOKEN_NAME = ".push-approved"


def _git(root, args):
    p = subprocess.Popen(["git", "-C", root] + args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    out, err = p.communicate(timeout=30)
    return p.returncode, out.decode("utf-8", "replace").strip(), err.decode("utf-8", "replace")


def head(root=ROOT, rev="HEAD"):
    """The full sha of `rev` as a commit in `root`; refuses when it names none."""
    code, out, err = _git(root, ["rev-parse", "--verify", "--quiet", rev + "^{commit}"])
    if code != 0 or not out:
        raise SystemExit("cannot resolve %s to a commit in %s: %s"
                         % (rev, root, err.strip() or "it names none"))
    return out


def keep_out_of_commits(root):
    """Make `root`'s git ignore the token, then ASK it again - a write is not a result."""
    if _git(root, ["check-ignore", "-q", TOKEN_NAME])[0] == 0:
        return
    code, exclude, err = _git(root, ["rev-parse", "--git-path", "info/exclude"])
    if code != 0:
        raise SystemExit("cannot find %s's exclude file (%s) - refusing to write a token it "
                         "could commit" % (root, err.strip()))
    exclude = exclude if os.path.isabs(exclude) else os.path.join(root, exclude)
    os.makedirs(os.path.dirname(exclude), exist_ok=True)
    with io.open(exclude, "a", encoding="utf-8", newline=LF) as handle:
        handle.write(LF + "# the push approval token: local, one push, never committed" + LF
                     + TOKEN_NAME + LF)
    if _git(root, ["check-ignore", "-q", TOKEN_NAME])[0] != 0:
        raise SystemExit("%s still does not ignore %s after it was excluded - refusing to write "
                         "a token it could commit" % (root, TOKEN_NAME))


def main(argv):
    said = ""
    remote = "origin"
    ref = ""
    force = False
    commit = "HEAD"
    root = ROOT
    rest = list(argv[1:])
    while rest:
        word = rest.pop(0)
        if word == "--remote" and rest:
            remote = rest.pop(0)
        elif word == "--ref" and rest:
            ref = rest.pop(0)
        elif word == "--commit" and rest:
            commit = rest.pop(0)
        elif word == "--repo" and rest:
            root = os.path.abspath(rest.pop(0))
        elif word == "--force":
            force = True
        elif word.startswith("--"):
            raise SystemExit("unknown option " + word)
        else:
            said = (said + " " + word).strip()

    if len(said) < 20:
        print(__doc__)
        raise SystemExit(
            "REFUSED: record what the owner actually said, in their words, at least 20 "
            "characters of it. An approval nobody can check is the shape that turned a "
            "conditional 'publish immediately' into a standing permission here.")

    sha = head(root, commit)
    keep_out_of_commits(root)
    token = os.path.join(root, TOKEN_NAME)
    with io.open(token, "w", encoding="utf-8", newline=LF) as handle:
        handle.write(
            "# One push, one commit. guard_push.py refuses a push this does not match." + LF
            + "# Written " + time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()) + LF
            + "sha=" + sha + LF
            + "remote=" + remote + LF
            + "ref=" + ref + LF
            + "force=" + ("yes" if force else "no") + LF
            + "said=" + " ".join(said.split()) + LF)
    print("approved ONE push of %s to %s%s%s" % (sha[:12], remote, (" " + ref) if ref else "",
                                                " (force)" if force else ""))
    print("recorded in %s, which that repository's git ignores" % TOKEN_NAME)
    print("It names that one commit; a push that sends any other is refused.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
