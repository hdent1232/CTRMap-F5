"""A hook command finds its hook, or refuses all but a read and says where it looked.

The defect, measured 2026-10-09 on the owner's PC: every hook command was
`python "$CLAUDE_PROJECT_DIR/.claude/..."`, and `CLAUDE_PROJECT_DIR` is the folder a session
STARTED in. A session moved into this repository from the folder above kept that variable, every
hook path named a file that was not there, Python exited 2 - a refusal, to the harness - and every
tool call was refused, the Read that would have shown why included. The session was abandoned.

  instance   started in the right folder, the hook runs, with its stdin and its exit code
  moved      started in a folder without the hook, the CURRENT repository's hook runs
  nowhere    neither holds it: a write is refused and the folders looked at are named; a read is
             let through; a retried turn end is let through (a second refusal spins forever)
  settings   every command this repository's settings wire IS the launcher, for a hook that is
             there - control: the walk finds the two PreToolUse stacks
  shell      the launcher survives a double-quoted shell word unchanged
"""
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _owner_switches  # noqa: E402,F401  - the owner's settings out of this process, before any hook can read one
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
import bounded         # noqa: E402  - a deadline ends the child's whole tree
HERE = _tree_root.require(__file__)
sys.path.insert(0, os.path.join(HERE, "tools"))
import hook_command  # noqa: E402  - the one definition of a hook command

LF = chr(10)
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0
REL = ".claude/probe-hooks/probe.py"

#: A hook that says where it ran, how much stdin it was handed, and exits with what it is told.
PROBE = LF.join([
    "import os, sys",
    "data = sys.stdin.read()",
    "sys.stdout.write('RAN %s %d' % (os.path.dirname(os.path.abspath(__file__)), len(data)))",
    "sys.exit(int(os.environ.get('PROBE_EXIT', '0')))",
    ""])


def _bash():
    """The bash the harness runs a hook command through. On PATH, or - on Windows, where a battery
    started from PowerShell has no bash on PATH and these six tests failed for it on 2026-10-11 - the
    one Claude Code uses: CLAUDE_CODE_GIT_BASH_PATH, else Git's own, in bin/ beside git's cmd/."""
    found = shutil.which("bash")
    if not found and os.name == "nt":
        named = os.environ.get("CLAUDE_CODE_GIT_BASH_PATH")
        git = shutil.which("git")
        for candidate in (named, git and os.path.join(os.path.dirname(os.path.dirname(git)), "bin", "bash.exe")):
            if candidate and os.path.isfile(candidate):
                found = candidate
                break
    if not found:
        raise AssertionError("no bash here - the harness runs hook commands through bash, and a "
                             "launcher nobody ran through it is not proven")
    return found


def run(command, payload, start, cwd, exit_code=0):
    """(exit, stdout, stderr) of a hook command run the way the harness runs it."""
    env = dict(os.environ, PROBE_EXIT=str(exit_code))
    if start is None:
        env.pop("CLAUDE_PROJECT_DIR", None)
    else:
        env["CLAUDE_PROJECT_DIR"] = start
    done = bounded.run([_bash(), "-c", command], input=payload.encode("utf-8"), cwd=cwd,
                       capture_output=True, timeout=120, env=env, creationflags=NO_WINDOW)
    return (done.returncode, done.stdout.decode("utf-8", "replace"),
            done.stderr.decode("utf-8", "replace"))


def _git(cwd, *argv):
    return bounded.run(["git"] + list(argv), cwd=cwd, capture_output=True, timeout=120,
                       creationflags=NO_WINDOW)


WRITE = json.dumps({"hook_event_name": "PreToolUse", "tool_name": "Bash",
                    "tool_input": {"command": "echo x > f"}})
READ = json.dumps({"hook_event_name": "PreToolUse", "tool_name": "Read",
                   "tool_input": {"file_path": "README.md"}})


class AHookCommandFindsItsHookOrSaysWhereItLooked(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="ctrmap-hookcmd-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.started = os.path.join(self.tmp, "started")
        self.repo = os.path.join(self.tmp, "repo")
        self.empty = os.path.join(self.tmp, "empty")
        for folder in (self.started, self.repo, self.empty):
            os.makedirs(folder)
        for folder in (self.started, self.repo):
            os.makedirs(os.path.join(folder, ".claude", "probe-hooks"))
            with io.open(os.path.join(folder, *REL.split("/")), "w", encoding="utf-8",
                         newline=LF) as handle:
                handle.write(PROBE)
        self.assertEqual(_git(self.repo, "init", "-q").returncode, 0, "git init failed")
        self.command = hook_command.command(REL)

    def _ran_in(self, out):
        return os.path.normcase(os.path.realpath(out.split(" ")[1])) if out.startswith("RAN ") \
            else None

    def _hooks_of(self, folder):
        return os.path.normcase(os.path.realpath(os.path.join(folder, ".claude", "probe-hooks")))

    def test_started_in_the_right_folder_the_hook_runs_with_its_stdin_and_exit(self):
        code, out, err = run(self.command, WRITE, self.started, self.empty, exit_code=2)
        self.assertEqual(self._ran_in(out), self._hooks_of(self.started),
                         "the hook under the start folder did not run: %r %r" % (out, err))
        self.assertEqual(out.split(" ")[2], str(len(WRITE)), "the hook was not handed its stdin")
        self.assertEqual(code, 2, "the hook's refusal (exit 2) did not reach the harness")

    def test_a_moved_session_runs_the_current_repositorys_hook(self):
        code, out, err = run(self.command, WRITE, self.empty, self.repo)
        self.assertEqual(self._ran_in(out), self._hooks_of(self.repo),
                         "a session started elsewhere did not run the hook of the repository it "
                         "is in now: %r %r" % (out, err))
        self.assertEqual(code, 0)

    def test_nowhere_a_write_is_refused_and_the_folders_are_named(self):
        code, out, err = run(self.command, WRITE, self.empty, self.empty)
        self.assertEqual(code, 2, "a call no guard could judge was let through: %r" % (out + err))
        self.assertIn("is not under the folder this session started in", err,
                      "the refusal did not say what was missing")
        self.assertIn(os.path.join(self.empty, ".claude", "probe-hooks", "probe.py"), err,
                      "the refusal did not name the folder it looked in")

    def test_nowhere_a_read_goes_through_so_the_session_can_see_why(self):
        code, out, err = run(self.command, READ, self.empty, self.empty)
        self.assertEqual(code, 0, "a READ was refused when no hook could be found - the wedge of "
                         "2026-10-09, where the Read that would have shown why was refused too")

    def test_nowhere_a_retried_turn_end_goes_through(self):
        stop = json.dumps({"hook_event_name": "Stop", "stop_hook_active": True})
        code, _out, _err = run(self.command, stop, None, self.empty)
        self.assertEqual(code, 0, "a retried turn end was refused again - that spins forever")

    def test_control_without_the_launcher_a_moved_session_is_wedged(self):
        anchored = 'python "$CLAUDE_PROJECT_DIR/%s"' % REL
        code, _out, _err = run(anchored, READ, self.empty, self.repo)
        self.assertEqual(code, 2, "CONTROL: the old anchored command did not refuse a read from "
                         "a moved session, so the tests above prove nothing about it")


class EveryWiredCommandIsTheLauncher(unittest.TestCase):

    def test_every_command_in_the_settings_is_the_launcher_for_a_hook_that_is_there(self):
        with io.open(os.path.join(HERE, ".claude", "settings.json"), encoding="utf-8") as handle:
            settings = json.load(handle)
        found = hook_command.findings(".claude/settings.json", json.dumps(settings))
        self.assertEqual(found, [], "a wired command is not the launcher: %r" % found)
        wired = hook_command.commands_in(settings)
        hooks = [hook_command.hook_of(said) for _event, said in wired]
        self.assertTrue({".claude/hooks/guard_all.py", ".claude/bundle-hooks/dispatch.py"}
                        <= set(hooks), "CONTROL: the walk did not find the two PreToolUse stacks "
                        "- %r" % sorted(set(map(str, hooks))))
        for rel in hooks:
            self.assertTrue(os.path.isfile(os.path.join(HERE, *rel.split("/"))),
                            "the settings run %s, which is not in this repository" % rel)

    def test_the_rule_refuses_an_anchored_command(self):
        anchored = {"hooks": {"PreToolUse": [{"matcher": "*", "hooks": [
            {"type": "command", "command": 'python "$CLAUDE_PROJECT_DIR/.claude/x/y.py"'}]}]}}
        self.assertEqual(len(hook_command.findings(".claude/settings.json",
                                                   json.dumps(anchored))), 1,
                         "a command anchored on one folder was not refused")
        outside = {"hooks": {"PreToolUse": [{"matcher": "*", "hooks": [
            {"type": "command", "command": hook_command.command("../.claude/hooks/guard_all.py")}]}]}}
        self.assertEqual(len(hook_command.findings(".claude/settings.json",
                                                   json.dumps(outside))), 1,
                         "a launcher pointed outside the repository was not refused")

    def test_the_launcher_compiles(self):
        """Python's exit 1 on a SyntaxError is NOT a refusal to the harness: a launcher that does
        not compile turns every guard off in silence - measured on its own first draft."""
        compile(hook_command.RESOLVER, "<launcher>", "exec")

    def test_the_launcher_survives_a_double_quoted_shell_word(self):
        self.assertIsNone(hook_command.SHELL_UNSAFE.search(hook_command.RESOLVER),
                          "the launcher holds a character a double-quoted shell word changes")


def _hooks_wired():
    import importlib.util
    spec = importlib.util.spec_from_file_location(
        "hooks_wired_under_test", os.path.join(HERE, "tools", "guard", "hooks_wired.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ACopyAboveTheRepositoryIsRefused(unittest.TestCase):
    """The folder above this repository held a second copy of the rules and the hooks (OUTSTANDING
    2026-09-22): it drifted from the repository's in one edit (2026-09-23), and a session started
    there ran it (2026-10-09). What may sit above: one import of the rules, and settings that run
    exactly this repository's hooks through the launcher."""

    def setUp(self):
        self.wired = _hooks_wired()
        self.tmp = tempfile.mkdtemp(prefix="ctrmap-above-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.home = os.path.join(self.tmp, "home")
        self.above = os.path.join(self.tmp, "sessions")
        self.root = os.path.join(self.above, "repo")
        os.makedirs(os.path.join(self.root, ".claude"))
        os.makedirs(self.home)
        self.settings = {"hooks": {"PreToolUse": [{"matcher": "*", "hooks": [
            {"type": "command", "command": hook_command.command(".claude/hooks/guard_x.py")}]}]}}
        self._write(os.path.join(self.root, ".claude", "settings.json"), json.dumps(self.settings))

    def _write(self, path, text):
        if not os.path.isdir(os.path.dirname(path)):
            os.makedirs(os.path.dirname(path))
        with io.open(path, "w", encoding="utf-8", newline=LF) as handle:
            handle.write(text)

    def found(self):
        return self.wired.copies_above(self.root, home=self.home, launcher=hook_command)

    def test_control_an_import_and_a_mirror_above_are_allowed(self):
        self._write(os.path.join(self.above, "CLAUDE.md"), "@repo/CLAUDE.md" + LF)
        mirror = {"hooks": {"PreToolUse": [{"matcher": "*", "hooks": [
            {"type": "command",
             "command": hook_command.command("repo/.claude/hooks/guard_x.py")}]}]}}
        self._write(os.path.join(self.above, ".claude", "settings.json"), json.dumps(mirror))
        self.assertEqual(self.found(), [], "CONTROL: the one shape allowed above was refused")

    def test_a_copy_of_the_rules_above_is_refused(self):
        self._write(os.path.join(self.above, "CLAUDE.md"), "# rules" + LF + "a rule" + LF)
        found = self.found()
        self.assertEqual(len(found), 1, "a second copy of the rules above was not refused: %r"
                         % found)
        self.assertIn("SECOND COPY of the rules", found[0])

    def test_an_import_followed_by_a_copy_is_still_a_copy(self):
        self._write(os.path.join(self.above, "CLAUDE.md"),
                    "@repo/CLAUDE.md" + LF + "a rule kept up here as well" + LF)
        self.assertEqual(len(self.found()), 1,
                         "an import with a copy of a rule after it passed as an import")

    def test_a_copy_of_the_hooks_above_is_refused(self):
        self._write(os.path.join(self.above, ".claude", "hooks", "guard_x.py"), "x = 1" + LF)
        found = self.found()
        self.assertTrue(any("SECOND COPY of the hooks" in f for f in found),
                        "a second copy of the hooks above was not refused: %r" % found)

    def test_settings_above_that_are_not_the_mirror_are_refused(self):
        anchored = {"hooks": {"PreToolUse": [{"matcher": "*", "hooks": [
            {"type": "command", "command": 'python "$CLAUDE_PROJECT_DIR/.claude/hooks/x.py"'}]}]}}
        self._write(os.path.join(self.above, ".claude", "settings.json"), json.dumps(anchored))
        self.assertTrue(any("does not run exactly this repository's hooks" in f
                            for f in self.found()),
                        "settings above that run something else were not refused")

    def test_the_wiring_check_guard_all_runs_asks_it(self):
        """The call site, not the function: guard_all asks `findings`, never `copies_above`."""
        self._write(os.path.join(self.root, ".claude", "hooks", "guard_x.py"),
                    "def decide(payload):" + LF + "    return False, None" + LF)
        self._write(os.path.join(self.above, "CLAUDE.md"), "# rules" + LF + "a rule" + LF)
        self.assertTrue(any("SECOND COPY of the rules" in f for f in self.wired.findings(self.root)),
                        "the wiring check guard_all runs on every call did not ask about a copy "
                        "above the repository")

    def test_the_users_home_is_not_a_copy(self):
        home_above = os.path.join(self.tmp, "sessions")
        self._write(os.path.join(home_above, ".claude", "hooks", "user_hook.py"), "x = 1" + LF)
        found = self.wired.copies_above(self.root, home=home_above, launcher=hook_command)
        self.assertEqual(found, [], "the user's own ~/.claude, which the harness reads for every "
                         "project, was refused as a copy: %r" % found)


if __name__ == "__main__":
    unittest.main()
