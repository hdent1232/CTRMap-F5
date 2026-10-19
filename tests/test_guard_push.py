"""The push approval is compared with what the push SENDS: .claude/hooks/guard_push.py, judged from
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
import sys
import shutil
import subprocess
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
import bounded         # noqa: E402  - a deadline ends the child's whole tree
HERE = _tree_root.require(__file__)
LF = chr(10)
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def _load():
    path = os.path.join(HERE, ".claude", "hooks", "guard_push.py")
    spec = importlib.util.spec_from_file_location("guard_push_under_test", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


guard_push = _load()


def _git(root, *args):
    done = bounded.run(["git", "-C", root] + list(args), capture_output=True, text=True,
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

    def test_a_folder_spelled_the_git_bash_way_is_the_folder_it_names(self):
        """MEASURED 2026-10-12: `cd "/c/Users/<you>/verification-bootstrap" && git push origin main`, typed in the
        Bash tool, was refused for want of an approval at `C:/c/Users/...` - the guard joined Git Bash's spelling of
        a drive onto the call's own folder, so a push after a cd spelled that way was refused whatever was
        approved. Both ways a push names its folder are asked, from outside the repository."""
        if os.name != "nt":
            self.skipTest("a drive spelled /c/ is Git Bash's, on Windows")
        self._approve(self.b)
        spelled = "/" + self.repo[0].lower() + self.repo[2:].replace(chr(92), "/")
        self.assertFalse(os.path.exists(spelled), "CONTROL: %s exists as written, so it asks nothing" % spelled)
        outside = os.path.dirname(self.repo)
        for command in ('cd "%s" && git push origin master' % spelled, 'git -C "%s" push origin master' % spelled):
            deny, why = guard_push.decide({"tool_name": "Bash", "tool_input": {"command": command}, "cwd": outside})
            self.assertFalse(deny, "an approved push from a folder spelled the Git Bash way was refused: %s %s"
                             % (command, why))
        deny, _why = guard_push.decide({"tool_name": "Bash", "cwd": outside, "tool_input": {
            "command": 'git -C "%s" push origin side:master' % spelled}})
        self.assertTrue(deny, "a push the approval does not name went out from a folder spelled the Git Bash way")

    def test_a_push_named_in_quoted_text_is_no_push(self):
        """MEASURED 2026-10-12: `grep -n "Version|gh release|git push|git tag" package.ps1` - a search pattern - was
        refused as a push with no approval. Words a command is HANDED in quotes run nothing."""
        for command in ('grep -n "Version|gh release|git push|git tag" package.ps1',
                        "git commit -m 'then | git push origin side:master'",
                        'echo "done; git push origin side:master"'):
            with self.subTest(command):
                self.assertFalse(self._denied(command), "a push named inside quoted text was judged as a push: %s"
                                 % command)

    def test_a_push_a_shell_would_still_RUN_is_still_judged(self):
        """The cheapest way past a guard that blanks quotes is a push inside them that runs anyway: `$(...)` inside
        double quotes runs in both shells; an escaped quote, a backtick and a quote left open are read differently by
        Git Bash and PowerShell, so the line is judged whole; a quoted program is a program; a shell handed the push
        in quotes runs it, and shellin.located hands it to the guard as its own command."""
        self._approve(self.b)
        for command in ('echo "$(git push origin side:master)"', 'echo \\"; git push origin side:master; echo \\"',
                        "echo `date`; git push origin side:master", "echo 'never closed ; git push origin side:master",
                        '"C:/Program Files/Git/cmd/git.exe" push origin side:master',
                        'GIT_TRACE=0 "C:/Program Files/Git/cmd/git.exe" push origin side:master'):
            with self.subTest(command):
                self.assertTrue(self._denied(command), "a push that runs went out unjudged: %s" % command)
        deny, _why = guard_push.decide({"tool_name": "Bash", "cwd": self.repo,
                                        "tool_input": {"command": "bash -c 'git push origin side:master'"}})
        self.assertTrue(deny, "a push handed to bash -c in quotes went out unjudged")

    def test_a_push_whose_commit_cannot_be_told_is_refused(self):
        self._approve(self.b)
        self.assertTrue(self._denied("git push origin no-such-ref:master"),
                        "a refspec naming no commit was read as an approved one")
        self.assertTrue(self._denied("git push --all origin"),
                        "a push of every branch went out on an approval for one commit")

    def test_an_approval_for_a_named_commit_in_another_repository_is_one_it_never_commits(self):
        tool = os.path.join(HERE, "tools", "guard", "approve_push.py")
        done = bounded.run(["python", "-B", tool, "yes, push that one, I have read the diff",
                            "--commit", self.a[:7], "--ref", "master", "--repo", self.repo],
                           capture_output=True, text=True, timeout=60, creationflags=NO_WINDOW)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertFalse(self._denied("git push origin %s:master" % self.a[:7]),
                         "the approval the tool wrote for a named commit does not pass that push")
        ignored = bounded.run(["git", "-C", self.repo, "check-ignore", "-q", ".push-approved"],
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


class AHistoryTheRemoteNeverPublishedIsNotPublished(unittest.TestCase):
    """The privacy rewrite of 2026-09-26 removed every path and name from the owner's computer from
    the published history and force-pushed it; the pre-rewrite history stays on this machine as
    refs/original, starting from another root. An approval is a quote of the owner's words and
    cannot tell one history from another, so pre-push asks where the sent history STARTS."""

    def setUp(self):
        base = tempfile.mkdtemp(prefix="push roots ")
        self.addCleanup(shutil.rmtree, base, True)
        self.remote = os.path.join(base, "remote.git")
        self.repo = os.path.join(base, "a repo")
        _git(base, "init", "-q", "--bare", self.remote)
        os.makedirs(self.repo)
        _git(self.repo, "init", "-q")
        _git(self.repo, "symbolic-ref", "HEAD", "refs/heads/master")
        _git(self.repo, "config", "user.email", "suite@example.invalid")
        _git(self.repo, "config", "user.name", "suite")
        self.published = self._commit("published")
        _git(self.repo, "remote", "add", "origin", self.remote)
        _git(self.repo, "push", "-q", "origin", "master")
        self.assertEqual(_git(self.repo, "rev-parse", "origin/master"), self.published,
                         "CONTROL: the scratch remote holds no published history")
        self.next = self._commit("next")
        _git(self.repo, "checkout", "-q", "--orphan", "old")
        self.old = self._commit("pre-rewrite")
        _git(self.repo, "checkout", "-q", "master")

    def _commit(self, text):
        with io.open(os.path.join(self.repo, "a.txt"), "w", encoding="utf-8") as handle:
            handle.write(text + LF)
        _git(self.repo, "add", "a.txt")
        _git(self.repo, "commit", "-q", "-m", text)
        return _git(self.repo, "rev-parse", "HEAD")

    def _approve(self, sha, ref):
        with io.open(os.path.join(self.repo, ".push-approved"), "w", encoding="utf-8") as handle:
            handle.write(LF.join(["sha=" + sha, "remote=origin", "ref=" + ref, "force=no",
                                  "said=yes, push that one, I have read the diff", ""]))

    def _pre_push(self, local, sha, ref):
        zero = "0" * 40
        return guard_push.pre_push("origin", ["%s %s refs/heads/%s %s" % (local, sha, ref, zero)],
                                   self.repo)

    def test_an_approved_push_of_another_history_is_refused(self):
        self._approve(self.old, "old")
        deny, reason = self._pre_push("refs/heads/old", self.old, "old")
        self.assertTrue(deny, "an approved push of a history the remote never published went out")
        self.assertIn("a root the published history", reason or "",
                      "the refusal did not say the history starts somewhere else")

    def test_control_the_published_history_still_goes_out(self):
        self._approve(self.next, "next")
        deny, reason = self._pre_push("refs/heads/master", self.next, "next")
        self.assertFalse(deny, "CONTROL: an approved push of the published history was refused: %s"
                         % reason)

    def test_a_history_whose_start_cannot_be_told_is_refused(self):
        self._approve("f" * 40, "gone")
        deny, reason = self._pre_push("refs/heads/gone", "f" * 40, "gone")
        self.assertTrue(deny, "a push whose history could not be read went out")
        self.assertIn("cannot be told", reason or "")


if __name__ == "__main__":
    unittest.main()
