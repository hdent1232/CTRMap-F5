"""A fresh clone turns its own git hooks on: tools/arm_checkout.py, judged from outside.

The defect: `core.hooksPath` lives in `.git/config`, which a clone never carries, so a fresh clone
ran no commit hook at all and every commit-time guard was silently off. One test per plant site:

  instance   the fix undone: a clone with hooks present and no hooks path must come out armed -
             read back from git itself, never from what the tool says it did.
  elsewhere  the same defect in the WIRING rather than the code: the repository's own settings
             must run the tool at SessionStart, or it is a file nobody calls.
  evasion    the cheapest way to report "armed" is to trust whatever hooks path is already set
             without looking at what it names - so a checkout pointing elsewhere must be repointed.
"""
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(HERE, "tools"))
import arm_checkout  # noqa: E402  - the tool under test

LF = chr(10)
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def _git(root, *args):
    done = subprocess.run(["git", "-C", root] + list(args), capture_output=True, text=True,
                          timeout=60, creationflags=NO_WINDOW)
    return done.returncode, (done.stdout or "").strip()


class AFreshCloneArmsItsOwnHooks(unittest.TestCase):
    """A checkout's git hooks are on after session start, whatever state it arrived in."""

    def _checkout(self, hooks_path=None):
        base = tempfile.mkdtemp(prefix="arm-checkout-test-")
        self.addCleanup(shutil.rmtree, base, True)
        root = os.path.join(base, "clone")
        os.makedirs(os.path.join(root, arm_checkout.HOOKS_DIR))
        subprocess.run(["git", "init", "-q", root], capture_output=True, timeout=60,
                       creationflags=NO_WINDOW)
        with io.open(os.path.join(root, arm_checkout.HOOKS_DIR, "commit-msg"), "w",
                     encoding="utf-8", newline=LF) as handle:
            handle.write("#!/bin/sh" + LF + "exit 0" + LF)
        if hooks_path is not None:
            _git(root, "config", "--local", "core.hooksPath", hooks_path)
        return root

    def test_a_fresh_clone_is_armed(self):
        root = self._checkout()
        arm_checkout.arm_hooks(root)
        code, now = _git(root, "config", "--local", "--get", "core.hooksPath")
        self.assertEqual((code, now), (0, arm_checkout.HOOKS_DIR),
                         "a fresh clone was left UNARMED - git reads core.hooksPath as %r, so no "
                         "commit hook runs" % (now if code == 0 else None,))

    def test_the_repository_runs_this_at_session_start(self):
        with io.open(os.path.join(HERE, ".claude", "settings.json"), encoding="utf-8") as handle:
            settings = json.load(handle)
        commands = [hook.get("command", "")
                    for matcher in settings.get("hooks", {}).get("SessionStart", [])
                    for hook in matcher.get("hooks", [])]
        wired = [c for c in commands if "tools/arm_checkout.py" in c]
        self.assertTrue(wired and os.path.isfile(os.path.join(HERE, "tools", "arm_checkout.py")),
                        "the repository's settings do not run tools/arm_checkout.py at session "
                        "start - SessionStart runs %r" % (commands,))

    def _cloud_clone(self, into="../verification-bootstrap"):
        """A checkout the way a cloud machine gets one: nothing it declares for the bundle
        resolves, and the bundle's repository sits BESIDE its origin, as on GitHub."""
        base = tempfile.mkdtemp(prefix="arm-cloud-")
        self.addCleanup(shutil.rmtree, base, True)
        remote = os.path.join(base, "owner", "verification-bootstrap")
        os.makedirs(remote)
        _git(remote, "init", "-q")
        with io.open(os.path.join(remote, "README.md"), "w", encoding="utf-8") as handle:
            handle.write("the bundle" + LF)
        _git(remote, "add", "README.md")
        _git(remote, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "bundle")
        root = os.path.join(base, "work", "CTRMap")
        os.makedirs(os.path.join(root, "tools"))
        os.makedirs(os.path.join(root, ".claude"))
        _git(root, "init", "-q")
        _git(root, "remote", "add", "origin", os.path.join(base, "owner", "CTRMap-F5"))
        shutil.copy(os.path.join(HERE, "tools", "bundle_install.py"), os.path.join(root, "tools"))
        declared = {"_bundle": [os.path.join(base, "nowhere"), "../verification-bootstrap"],
                    "_bundle_fetch": {"repo": "verification-bootstrap", "into": into}}
        with io.open(os.path.join(root, ".claude", "bundle-install.json"), "w",
                     encoding="utf-8") as handle:
            handle.write(json.dumps(declared) + LF)
        return root, os.path.join(base, "work", "verification-bootstrap")

    def test_a_clone_without_the_bundle_fetches_it_beside_itself(self):
        root, beside = self._cloud_clone()
        reached, said = arm_checkout.bundle_reachable(root)
        self.assertTrue(reached and os.path.isdir(os.path.join(beside, ".git")),
                        "a clone with no bundle on its machine was left UNABLE TO COMMIT - the "
                        "bundle was not fetched beside it: %s" % said)

    def test_a_fetch_to_a_place_nothing_looks_is_refused(self):
        root, beside = self._cloud_clone(into="../somewhere-else")
        reached, said = arm_checkout.bundle_reachable(root)
        self.assertFalse(reached, "a fetch into a place `_bundle` does not list was accepted")
        self.assertFalse(os.path.exists(os.path.join(os.path.dirname(beside), "somewhere-else")),
                         "the bundle was cloned where nothing will look for it")

    def test_a_checkout_pointing_its_hooks_elsewhere_is_repointed(self):
        root = self._checkout(hooks_path=".git/hooks")
        arm_checkout.arm_hooks(root)
        code, now = _git(root, "config", "--local", "--get", "core.hooksPath")
        self.assertEqual(now, arm_checkout.HOOKS_DIR,
                         "an existing hooks path was TRUSTED instead of checked - it still names "
                         "%r, where this project keeps no hook" % now)


#: A stand-in for the notes repository's sync tool, speaking its interface - `import|status
#: --memory <folder>` - and refusing a folder that is not there, as the real one did until
#: 2026-09-26: a machine may fetch a notes repository of any age. It logs every call it gets.
STUB_SYNC = LF.join([
    "import io, os, shutil, sys",
    "HERE = os.path.dirname(os.path.abspath(__file__))",
    "mode, memory = sys.argv[1], sys.argv[sys.argv.index('--memory') + 1]",
    "with io.open(os.path.join(HERE, 'calls.log'), 'a') as log:",
    "    log.write(mode + chr(10))",
    "if not os.path.isdir(memory):",
    "    print('cannot read the memory folder ' + memory)",
    "    sys.exit(2)",
    "names = sorted(n for n in os.listdir(HERE) if n.endswith('.md'))",
    "print(mode + ': memory ' + memory + '  <->  repository ' + HERE)",
    "if mode == 'import':",
    "    for name in names:",
    "        shutil.copy2(os.path.join(HERE, name), os.path.join(memory, name))",
    "    print('  added %d, updated 0' % len(names))",
    "else:",
    "    print('  changed in memory  MEMORY.md')",
    ""])


class AFreshMachineGetsTheNotes(unittest.TestCase):
    """The owner's notes reach a session on a machine that has none, and never overwrite one that
    has them. One test per plant site:

      instance   the import undone: a machine with no notes must end with them in the session's
                 memory folder AND in front of the session, which may have loaded memory already.
      evasion    the cheapest way to always have notes is to always import - which overwrites a
                 note written on this machine and not yet exported, the only copy of it.
      elsewhere  the wiring: the SessionStart entry must outlast every wait the tool can make, or
                 it is killed mid-clone and leaves a half-made folder that refuses every later one.
    """

    def _machine(self, memory_index=None):
        base = tempfile.mkdtemp(prefix="arm-notes-")
        self.addCleanup(shutil.rmtree, base, True)
        remote = os.path.join(base, "owner", arm_checkout.NOTES["repo"])
        os.makedirs(remote)
        _git(remote, "init", "-q")
        for name, text in (("MEMORY.md", "- [The recipe](recipe.md) - how the notes are indexed"),
                           ("recipe.md", "a note from the repository"),
                           ("sync.py", STUB_SYNC)):
            with io.open(os.path.join(remote, name), "w", encoding="utf-8", newline=LF) as handle:
                handle.write(text + LF)
        _git(remote, "add", "-A")
        _git(remote, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "notes")
        root = os.path.join(base, "work", "CTRMap")
        os.makedirs(root)
        _git(root, "init", "-q")
        _git(root, "remote", "add", "origin", os.path.join(base, "owner", "CTRMap-F5"))
        payload = {"transcript_path": os.path.join(base, "home", "projects", "a-project",
                                                   "session.jsonl")}
        memory = arm_checkout.memory_folder(payload)
        if memory_index is not None:
            os.makedirs(memory)
            with io.open(os.path.join(memory, "MEMORY.md"), "w", encoding="utf-8") as handle:
                handle.write(memory_index + LF)
        notes = os.path.normpath(os.path.join(root, arm_checkout.NOTES["into"]))
        return root, memory, notes

    def test_a_machine_with_no_notes_gets_them_and_the_session_is_shown_them(self):
        root, memory, notes = self._machine()
        brought, lines = arm_checkout.bring_notes(root, memory)
        landed = os.path.isfile(os.path.join(memory, "recipe.md"))
        shown = any("how the notes are indexed" in line for line in lines)
        self.assertTrue(brought and landed and shown,
                        "a machine with no notes was left with NO NOTES - brought %s, in the memory "
                        "folder %s, shown to the session %s: %s" % (brought, landed, shown, lines))

    def test_notes_already_on_the_machine_are_never_overwritten(self):
        root, memory, notes = self._machine(memory_index="- written here, not yet exported")
        arm_checkout.bring_notes(root, memory)
        with io.open(os.path.join(memory, "MEMORY.md"), encoding="utf-8") as handle:
            now = handle.read()
        self.assertIn("written here, not yet exported", now,
                      "notes already on this machine were OVERWRITTEN by the repository's copy - "
                      "a note not yet exported existed nowhere else")

    def test_a_session_whose_memory_folder_is_unknown_is_told_so_and_nothing_is_fetched(self):
        root, memory, notes = self._machine()
        brought, lines = arm_checkout.bring_notes(root, arm_checkout.memory_folder({}))
        self.assertFalse(brought, "notes were reported brought into a memory folder nobody knows")
        self.assertIn("UNKNOWN", " ".join(lines))
        self.assertFalse(os.path.exists(notes), "the notes were fetched for a session whose "
                                                "memory folder is unknown")

    def test_the_memory_folder_is_the_one_beside_the_sessions_transcripts(self):
        # Known answer, measured on the owner's machine 2026-09-26: a session's transcript and
        # its memory folder share one directory under ~/.claude/projects.
        where = os.path.join("home", ".claude", "projects", "a-project")
        self.assertEqual(
            arm_checkout.memory_folder({"transcript_path": os.path.join(where, "s.jsonl")}),
            os.path.join(os.path.abspath(where), "memory"))

    def test_input_that_cannot_be_read_is_unknown_not_empty(self):
        self.assertEqual(arm_checkout.session_input(io.StringIO('{"transcript_path": "t"}')),
                         {"transcript_path": "t"})
        self.assertIsNone(arm_checkout.session_input(io.StringIO("not json")),
                          "unreadable hook input was read as an empty one")

    def test_session_start_outlasts_every_wait_the_tool_can_make(self):
        with io.open(os.path.join(HERE, ".claude", "settings.json"), encoding="utf-8") as handle:
            settings = json.load(handle)
        limits = [hook.get("timeout", 60)
                  for matcher in settings.get("hooks", {}).get("SessionStart", [])
                  for hook in matcher.get("hooks", [])
                  if "tools/arm_checkout.py" in hook.get("command", "")]
        self.assertTrue(limits and min(limits) > arm_checkout.LONGEST_RUN,
                        "SessionStart is KILLED BEFORE THE TOOL CAN FINISH - it allows %r seconds "
                        "and the tool can wait %d; a clone cut off halfway leaves a folder that "
                        "refuses every later fetch" % (limits, arm_checkout.LONGEST_RUN))


if __name__ == "__main__":
    unittest.main()
