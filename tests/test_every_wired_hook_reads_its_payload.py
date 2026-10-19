"""Every hook the settings wire REFUSES a payload it cannot read, and says why (F213).

MEASURED 2026-10-06: all five entry points `.claude/settings.json` wires - both PreToolUse stacks,
both Stop hooks, PostToolUse - were fed text that is not JSON, `{}`, and a payload cut mid-command,
and all fifteen runs exited 0 saying nothing, while the same hooks refused the controls. The
entry points here are READ FROM THE SETTINGS, never listed: a sixth wired tomorrow is asked the
same question the day it is wired.

SessionStart is the one event left out, and by its nature rather than its name: the harness acts
on no refusal there, so its hook (tools/arm_checkout.py) is asked to SAY an unreadable payload -
it prints "PROBLEM: this hook's input could not be read" - which tests/test_arm_checkout.py drives.
"""
import json
import os
import re
import subprocess
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
import bounded         # noqa: E402  - a deadline ends the child's whole tree
HERE = _tree_root.require(__file__)
sys.path.insert(0, os.path.join(HERE, "tools"))
import hook_command  # noqa: E402  - the one spelling of a hook command, read back here
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

#: Events whose hooks the harness cannot refuse with: their answer is said, not enforced.
CANNOT_REFUSE = ("SessionStart",)

UNREADABLE = ("this is not json", "{}",
              '{"hook_event_name": "PreToolUse", "tool_name": "Bash", "tool_input": {"command": "ls')


def wired(root=HERE):
    """[(event, script path)] for every command the project's settings wire, read from the file."""
    with open(os.path.join(root, ".claude", "settings.json"), encoding="utf-8") as handle:
        settings = json.load(handle)
    out = []
    for event, said in hook_command.commands_in(settings):
        rel = hook_command.hook_of(said)
        if rel:
            out.append((event, os.path.join(root, *re.split(r"[/\\]+", rel))))
    return out


def run(script, raw):
    """(exit code, stdout, stderr) of `script` handed `raw`, with no owner override set."""
    env = {k: v for k, v in os.environ.items() if not k.endswith("_ALLOW_UNREADABLE")}
    env["CLAUDE_PROJECT_DIR"] = HERE
    done = bounded.run([sys.executable, "-B", script], input=raw.encode("utf-8"), cwd=HERE,
                       capture_output=True, timeout=120, env=env, creationflags=NO_WINDOW)
    return (done.returncode, done.stdout.decode("utf-8", "replace"),
            done.stderr.decode("utf-8", "replace"))


def refused(code, out):
    """A hook says no by exiting 2, or by printing a JSON deny and exiting 0."""
    return code == 2 or '"permissionDecision": "deny"' in out


class EveryWiredHookRefusesAPayloadItCannotRead(unittest.TestCase):

    def judges(self):
        found = [(event, script) for event, script in wired() if event not in CANNOT_REFUSE]
        names = {os.path.basename(script) for _e, script in found}
        self.assertTrue({"guard_all.py", "dispatch.py"} <= names,
                        "CONTROL: the settings walk did not find the two PreToolUse stacks, so it "
                        "finds nothing - %r" % sorted(names))
        return sorted(set(found))

    def test_every_wired_hook_refuses_what_it_cannot_read_and_says_why(self):
        for event, script in self.judges():
            for raw in UNREADABLE:
                with self.subTest(event=event, hook=os.path.basename(script), payload=raw[:20]):
                    code, out, err = run(script, raw)
                    self.assertTrue(refused(code, out),
                                    "UNREADABLE: %s for %s allowed a payload it could not read "
                                    "(exit %d): %r" % (os.path.basename(script), event, code,
                                                       (out + err)[:200]))
                    self.assertIn("could not", out + err, "and it did not say why")

    def test_an_unreadable_turn_end_is_refused_ONCE(self):
        """A second refusal of the same stop spins forever. The harness marks the retry with
        `stop_hook_active`, read from the raw text because the payload around it does not parse."""
        cut = '{"hook_event_name": "Stop", "session_id": "s", "transcript_path": "/x'
        retry = cut.replace('"session_id"', '"stop_hook_active": true, "session_id"')
        stops = [script for event, script in self.judges() if event == "Stop"]
        self.assertTrue(stops, "CONTROL: no Stop hook is wired, so this asks nothing")
        for script in stops:
            with self.subTest(hook=os.path.basename(script)):
                code, out, _err = run(script, cut)
                self.assertTrue(refused(code, out), "an unreadable turn end was let through")
                code, out, err = run(script, retry)
                self.assertFalse(refused(code, out), "RETRY: %s refused the retried turn end "
                                 "again - this spins forever" % os.path.basename(script))
                self.assertIn("already sent back once", out + err, "and the retry was not said")

    def test_the_owners_way_past_lets_every_hook_through_and_SAYS_so(self):
        """One variable per stack for a harness that changed what it sends - each read from the
        module that spells it, never typed here."""
        names = []
        for rel, module in ((".claude/hooks/hook_env.py", "hook_env_for_override"),
                            (".claude/bundle-hooks/bundle_env.py", "bundle_env_for_override")):
            import importlib.util
            spec = importlib.util.spec_from_file_location(module, os.path.join(HERE, rel))
            loaded = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(loaded)
            names.append(loaded.name("ALLOW_UNREADABLE"))
        for event, script in self.judges():
            with self.subTest(event=event, hook=os.path.basename(script)):
                env = dict(os.environ, CLAUDE_PROJECT_DIR=HERE, **{n: "1" for n in names})
                done = bounded.run([sys.executable, "-B", script], input=b"this is not json",
                                   cwd=HERE, capture_output=True, timeout=120, env=env,
                                   creationflags=NO_WINDOW)
                out = done.stdout.decode("utf-8", "replace")
                self.assertFalse(refused(done.returncode, out),
                                 "OVERRIDE: %s refused with the owner's way past set"
                                 % os.path.basename(script))
                self.assertIn("NO", out, "OVERRIDE: %s let it through and said nothing"
                              % os.path.basename(script))

    def test_a_readable_harmless_call_is_still_allowed(self):
        """The control: a hook that refuses everything is as dead as one that refuses nothing."""
        harmless = json.dumps({"hook_event_name": "PreToolUse", "session_id": "s",
                               "tool_name": "Read", "tool_input": {"file_path": "README.md"}})
        for event, script in self.judges():
            if event != "PreToolUse":
                continue
            with self.subTest(hook=os.path.basename(script)):
                code, out, err = run(script, harmless)
                self.assertFalse(refused(code, out), "CONTROL: %s refused a harmless read: %r"
                                 % (os.path.basename(script), (out + err)[:300]))


if __name__ == "__main__":
    unittest.main()
