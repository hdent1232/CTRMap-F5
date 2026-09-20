#!/usr/bin/env python3
"""Record the owner's approval for ONE push of ONE commit.

The owner's standing rule: NEVER PUSH WITHOUT EXPLICIT PER-PUSH APPROVAL, and approval for one
push is not approval for the next. `.claude/hooks/guard_push.py` refuses a push that has no
token, or a token naming a different commit, remote, ref, or not naming a force push as one.

This writes that token. It pins the CURRENT HEAD, so the approval expires the moment there is
another commit - which is what "per push" means, and what habit erodes.

    python tools/guard/approve_push.py "<what the owner actually said>"
    python tools/guard/approve_push.py "<...>" --remote origin --ref master --force

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
TOKEN = os.path.join(ROOT, ".push-approved")


def head():
    p = subprocess.Popen(["git", "-C", ROOT, "rev-parse", "HEAD"],
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    out, err = p.communicate(timeout=30)
    if p.returncode != 0:
        raise SystemExit("cannot read HEAD in %s: %s"
                         % (ROOT, err.decode("utf-8", "replace").strip()))
    return out.decode("utf-8", "replace").strip()


def main(argv):
    said = ""
    remote = "origin"
    ref = ""
    force = False
    rest = list(argv[1:])
    while rest:
        word = rest.pop(0)
        if word == "--remote" and rest:
            remote = rest.pop(0)
        elif word == "--ref" and rest:
            ref = rest.pop(0)
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

    sha = head()
    io.open(TOKEN, "w", encoding="utf-8", newline=LF).write(
        "# One push, one commit. guard_push.py refuses a push this does not match." + LF
        + "# Written " + time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()) + LF
        + "sha=" + sha + LF
        + "remote=" + remote + LF
        + "ref=" + ref + LF
        + "force=" + ("yes" if force else "no") + LF
        + "said=" + " ".join(said.split()) + LF)
    print("approved ONE push of %s to %s%s" % (sha[:12], remote, " (force)" if force else ""))
    print("recorded in " + TOKEN)
    print("It stops matching the moment there is another commit.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
