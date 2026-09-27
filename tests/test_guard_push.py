"""The push approval is compared with what the push SENDS: tools/hooks/guard_push.py, judged from
outside against a scratch repository, so the real approval token is never read or written.

The defects, measured 2026-09-26: the guard compared the token with HEAD whatever the command
pushed. With an approval for HEAD, `git push origin <any commit>:master` published a commit the
owner never saw; the push the owner HAD approved - an ancestor of a newer local commit - was
refused; the ref the token names was never compared, though the guard's docstring said it was;
`:master`, which deletes the remote branch, did not count as a deletion; and a push spelled
`git -C "<folder with a space>" push` was not recognised as a push at all.

  instance   the commit a refspec sends is resolved: the approved commit goes out though HEAD has
             moved on, and a commit the approval does not name is refused though HEAD is approved.
  elsewhere  the same defect in the other half of the subject: the REF the push lands on.
  evasion    the cheapest way past a commit comparison is to push no commit at all - a refspec
             with an empty source, which deletes what the remote has - or to spell the push so
             it is not recognised as one.
"""
import importlib.util
import io
import os
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LF = chr(10)
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def _load():
    path = os.path.join(HERE, "tools", "hooks", "guard_push.py")
    spec = importlib.util.spec_from_file_location("guard_push_under_test", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


guard_push = _load()


def _git(root, *args):
    done = subprocess.run(["git", "-C", root] + list(args), capture_output=True, text=True,
                          timeout=60, creationflags=NO_WINDOW)
    return (done.stdout or "").strip()


class AnApprovalIsForTheCommitThePushSends(unittest.TestCase):

    def setUp(self):
        base = tempfile.mkdtemp(prefix="push gate ")      # a space, as the owner's folders have
        self.addCleanup(shutil.rmtree, base, True)
        self.repo = os.path.join(base, "a repo")
        os.makedirs(self.repo)
        _git(self.repo, "init", "-q")
        _git(self.repo, "symbolic-ref", "HEAD", "refs/heads/master")
        _git(self.repo, "config", "user.email", "suite@example.invalid")
        _git(self.repo, "config", "user.name", "suite")
        self.a = self._commit("one")
        self.b = self._commit("two")
        _git(self.repo, "checkout", "-q", "-b", "side", self.a)
        self.c = self._commit("three")
        _git(self.repo, "checkout", "-q", "master")
        self.assertEqual(_git(self.repo, "rev-parse", "HEAD"), self.b)

    def _commit(self, text):
        with io.open(os.path.join(self.repo, "a.txt"), "w", encoding="utf-8") as handle:
            handle.write(text + LF)
        _git(self.repo, "add", "a.txt")
        _git(self.repo, "commit", "-q", "-m", text)
        return _git(self.repo, "rev-parse", "HEAD")

    def _approve(self, sha, ref="master", force="no"):
        with io.open(os.path.join(self.repo, ".push-approved"), "w", encoding="utf-8") as handle:
            handle.write(LF.join(["sha=" + sha, "remote=origin", "ref=" + ref, "force=" + force,
                                  "said=yes, push that one, I have read the diff", ""]))

    def _denied(self, command, cwd=None):
        deny, _ = guard_push.verdict(command, cwd or self.repo)
        return deny

    def test_the_approved_commit_goes_out_though_head_has_moved_on(self):
        self._approve(self.a)
        self.assertFalse(self._denied("git push origin %s:master" % self.a[:7]),
                         "the owner's APPROVED push was refused - it sends %s, which the approval "
                         "names, and was judged by HEAD instead" % self.a[:7])

    def test_a_commit_the_approval_does_not_name_is_refused_though_head_is_approved(self):
        self._approve(self.b)
        self.assertTrue(self._denied("git push origin side:master"),
                        "the push PUBLISHED A COMMIT NOBODY APPROVED - it sends %s by refspec, and "
                        "the approval names HEAD" % self.c[:7])

    def test_the_ref_the_approval_names_is_the_ref_the_push_lands_on(self):
        self._approve(self.b)
        self.assertTrue(self._denied("git push origin HEAD:release"),
                        "a push LANDED ON A REF THE APPROVAL DOES NOT NAME - approved for master, "
                        "sent to release")

    def test_a_refspec_that_deletes_needs_its_own_word(self):
        self._approve(self.b)
        self.assertTrue(self._denied("git push origin :master"),
                        "`:master` DELETED THE REMOTE BRANCH on a general yes - a deletion sends "
                        "no commit, so a commit comparison alone never sees it")
        self._approve(self.b, force="yes")
        self.assertFalse(self._denied("git push origin :master"),
                         "a deletion the approval does name was refused")

    def test_a_push_spelled_any_way_git_accepts_is_still_judged(self):
        self._approve(self.b)
        outside = os.path.dirname(self.repo)
        spellings = [
            'git -C "%s" push origin side:master' % self.repo,
            "git -c user.name=someone push origin side:master",
            "GIT_TRACE=0 git push origin side:master",
            "env GIT_TRACE=0 git push origin side:master",
            '"C:/Program Files/Git/cmd/git.exe" push origin side:master',
            '& "C:/Program Files/Git/cmd/git.exe" push origin side:master',
        ]
        missed = [s for s in spellings
                  if not self._denied(s, outside if s.startswith("git -C") else None)]
        self.assertEqual(missed, [], "a push NOT RECOGNISED AS A PUSH went out unjudged: %r"
                         % missed)

    def test_a_push_whose_commit_cannot_be_told_is_refused(self):
        self._approve(self.b)
        self.assertTrue(self._denied("git push origin no-such-ref:master"),
                        "a refspec naming no commit was read as an approved one")
        self.assertTrue(self._denied("git push --all origin"),
                        "a push of every branch went out on an approval for one commit")

    def test_an_approval_for_a_named_commit_in_another_repository_is_one_it_never_commits(self):
        tool = os.path.join(HERE, "tools", "guard", "approve_push.py")
        done = subprocess.run(["python", "-B", tool, "yes, push that one, I have read the diff",
                               "--commit", self.a[:7], "--ref", "master", "--repo", self.repo],
                              capture_output=True, text=True, timeout=60, creationflags=NO_WINDOW)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertFalse(self._denied("git push origin %s:master" % self.a[:7]),
                         "the approval the tool wrote for a named commit does not pass that push")
        ignored = subprocess.run(["git", "-C", self.repo, "check-ignore", "-q", ".push-approved"],
                                 capture_output=True, timeout=60, creationflags=NO_WINDOW)
        self.assertEqual(ignored.returncode, 0,
                         "the approval token could be COMMITTED ALONG WITH what it approves - that "
                         "repository's git does not ignore it")

    def test_the_forms_that_send_the_approved_head_still_pass(self):
        self._approve(self.b)
        outside = os.path.dirname(self.repo)
        for command, cwd in (("git push origin master", None), ("git push", None),
                             ("git push -u origin master 2>&1", None),
                             ("git push origin HEAD:master", None),
                             ("git push origin refs/heads/master", None),
                             ('git -C "%s" push origin master' % self.repo, outside),
                             ('cd "%s" && git push origin master' % self.repo, outside)):
            self.assertFalse(self._denied(command, cwd),
                             "the approved HEAD was refused for %r" % command)
        self.assertFalse(self._denied("git status"), "a command that is not a push was judged")
        self._commit("four")
        self.assertTrue(self._denied("git push origin master"),
                        "one more commit did not expire the approval - which is what per-push "
                        "means")


if __name__ == "__main__":
    unittest.main()
