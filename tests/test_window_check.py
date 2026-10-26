# -*- coding: utf-8 -*-
"""tools/window_check.py: what refuses a release driven through its own window, and the commit that changes the
version without one.

The launches themselves need the game, a display and minutes, so they are run by package.ps1 and not here; what is
held here is every judgement made of what a launch said - each refusal with a launch that earns it beside one that
does not - and the record's subject, in a scratch repository whose index and working tree this suite controls.
"""
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
HERE = _tree_root.require(__file__)
sys.path.insert(0, os.path.join(HERE, "tools"))
import window_check  # noqa: E402
import bounded       # noqa: E402  - a deadline ends the child's whole tree

LF = chr(10)
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

#: What a launch that holds says, as the harness prints it (trimmed from a real second launch, 2026-10-24).
HELD = LF.join([
    "HARNESS mode trap, workspace W, game G",
    "WINDOW FIRST ON SCREEN at 2036 ms",
    "ZONE LIST has 0 rows at 2s",
    "ZONE LIST has 540 rows at 3s",
    "DIALOG at 3s, 'Backup belongs to another game':",
    "    [OK]",
    "  answering 'OK'",
    "DIALOG at 4s, '':",
    "    Loading Zone data",
    "MAIN WINDOW settled at 13s",
    "  zone panel: dropdown items=540, open zone=none",
    "TRAP CHECK: pressed 'Re-link zone' on the zone actions bar",
    "TRAP CHECK: zone list hidden with the tool open; way back showing=true bordered=true at 10,10 120x24",
    "TRAP CHECK after pressing it: zone list showing=true, load showing=true",
    "LOAD ATTEMPT: highlighted row 5 (Littleroot Town - 5), zone 5; pressing 'Load this zone'",
    "AFTER LOAD settled at 21s (8233 ms after Load was pressed, 8 s of quiet included)",
    "  zone panel: dropdown items=540, open zone=index 5",
    "WINDOW FIRST ON SCREEN at 2036 ms; WORST FREEZE WHILE ON SCREEN 0 ms",
    "WORST FREEZE ON SCREEN BEFORE LOAD 0 ms; IN THE ZONE LOAD 0 ms",
    "HARNESS DONE in phase 2",
]) + LF


def changed(old, new):
    """HELD with one line replaced - every refusal below is HELD plus exactly one defect."""
    assert old in HELD, old
    return HELD.replace(old, new)


class ALaunchIsJudgedByWhatItShowed(unittest.TestCase):

    def test_a_launch_that_held_is_not_refused(self):
        """THE CONTROL: every case below is this launch with one thing wrong."""
        self.assertEqual(window_check.judge(HELD, True), [])
        self.assertEqual(window_check.judge(HELD, False), [])

    def test_a_freeze_on_screen_over_the_limit_is_refused_before_the_load_and_in_it(self):
        line = "WORST FREEZE ON SCREEN BEFORE LOAD 0 ms; IN THE ZONE LOAD 0 ms"
        for before, during, when in ((window_check.FREEZE_MS + 1, 0, "before the zone load"),
                                     (0, 13807, "in the zone load")):
            said = window_check.judge(changed(line, "WORST FREEZE ON SCREEN BEFORE LOAD %d ms; IN THE ZONE LOAD %d ms"
                                              % (before, during)), True)
            self.assertTrue(any("froze on screen" in p and when in p for p in said),
                            "a release that froze %d ms %s was let through: %s" % (max(before, during), when, said))
        self.assertEqual(window_check.judge(changed(line, "WORST FREEZE ON SCREEN BEFORE LOAD %d ms; IN THE ZONE LOAD 0 ms"
                                                    % window_check.FREEZE_MS), True), [],
                         "a freeze AT the limit was refused - the limit is the most a release may show")

    def test_a_launch_with_no_freeze_measurement_is_unknown_not_still(self):
        said = window_check.judge(changed("WORST FREEZE ON SCREEN BEFORE LOAD 0 ms; IN THE ZONE LOAD 0 ms", ""), True)
        self.assertTrue(any("UNKNOWN" in p for p in said), said)

    def test_a_dialog_on_a_second_launch_is_refused_and_on_a_first_it_is_not(self):
        """1.0.4 opened a refusal dialog on every launch. A first launch may repair and say so; a second may not."""
        every = changed("DIALOG at 4s, '':", "DIALOG at 4s, 'Zones sharing a map or area':")
        self.assertTrue(any("Zones sharing a map or area" in p for p in window_check.judge(every, True)),
                        "a dialog on a second launch was let through")
        self.assertEqual(window_check.judge(every, False), [])

    def test_a_zone_tool_with_no_way_back_on_screen_is_refused(self):
        """The 1.0.4 trap: the way back was in the window, drawn where nobody could see it."""
        flat = changed("way back showing=true", "way back showing=false")
        self.assertTrue(any("1.0.4 trap" in p for p in window_check.judge(flat, True)),
                        "a way back that was not on screen was let through")
        gone = changed("TRAP CHECK: zone list hidden with the tool open; way back showing=true bordered=true at 10,10 120x24",
                       "TRAP CHECK: zone list hidden with the tool open; NO 'Back to the zone list' anywhere in the window")
        self.assertTrue(any("1.0.4 trap" in p for p in window_check.judge(gone, True)))
        stuck = changed("zone list showing=true, load showing=true", "zone list showing=false, load showing=false")
        self.assertTrue(any("did not put the zone list" in p for p in window_check.judge(stuck, True)))

    def test_a_zone_list_that_never_fills_is_refused(self):
        said = window_check.judge(changed("ZONE LIST has 540 rows at 3s", "ZONE LIST has 211 rows at 3s"), True)
        self.assertTrue(any("never held all 540" in p for p in said), "a zone list that never filled was let through: %s"
                        % said)

    def test_no_zone_opened_is_refused_and_an_unreadable_one_is_unknown(self):
        none = changed("  zone panel: dropdown items=540, open zone=index 5", "  zone panel: dropdown items=540, open zone=none")
        self.assertTrue(any("no zone was open" in p for p in window_check.judge(none, True)))
        unread = changed("open zone=index 5", "open zone=UNREADABLE (unreadable java.lang.NoSuchFieldException)")
        self.assertTrue(any("UNKNOWN" in p for p in window_check.judge(unread, True)))

    def test_a_run_that_did_not_finish_is_refused_whatever_it_measured_before(self):
        for end, why in (("HARNESS TIMED OUT in phase 1" + LF + "HARNESS DONE in phase 1", "never settled"),
                         ("HARNESS DEADLINE: 360 s passed; stopping.", "deadline"),
                         ("", "did not finish")):
            said = window_check.judge(changed("HARNESS DONE in phase 2", end), True)
            self.assertTrue(any(why in p for p in said), (end, said))
        self.assertTrue(window_check.judge("", True), "a launch that said nothing was passed")

    def test_no_window_on_screen_is_refused(self):
        said = window_check.judge(changed("WINDOW FIRST ON SCREEN at 2036 ms; WORST", "WINDOW FIRST ON SCREEN at -1 ms; WORST"),
                                  True)
        self.assertTrue(any("no window" in p for p in said), said)


class TheEditorsSettingsAreReadAsJavaWroteThem(unittest.TestCase):

    def test_a_name_and_a_path_round_trip_through_javas_registry_spelling(self):
        self.assertEqual(window_check.to_java_prefs("WORKSPACE_PATH"), "/W/O/R/K/S/P/A/C/E_/P/A/T/H")
        self.assertEqual(window_check.to_java_prefs("C:" + chr(92) + "Users"), "/C:///Users")
        self.assertEqual(window_check.from_java_prefs("/C:///Users"), "C:" + chr(92) + "Users",
                         "a backslash in the editor's settings was read back as something else")
        for text in ("C:" + chr(92) + "Users" + chr(92) + "A b/c", "plain", "x" + chr(0xdc) + "y"):
            self.assertEqual(window_check.from_java_prefs(window_check.to_java_prefs(text)), text)


class AVersionChangeNeedsAPassedCheckOfWhatItCommits(unittest.TestCase):
    """The commit-msg hook's question, asked in a scratch repository: the record carries the src/ and lib/ it measured,
    and a commit that changes the version is refused unless it stages exactly those."""

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="window_check_repo_")
        self.addCleanup(shutil.rmtree, self.root, True)
        self.write(window_check.VERSION_FILE, "version=1.0.0" + LF)
        self.write("src/ctrmap/A.java", "class A {}" + LF)
        self.write("lib/x.jar", "jar" + LF)
        self.write("OUTSTANDING.md", "# queue" + LF + LF + "## Open" + LF + LF + "## Known limitations" + LF)
        self.git("init", "-q")
        self.git("add", "-A")
        self.git("-c", "user.email=t@example.invalid", "-c", "user.name=t", "-c", "core.autocrlf=false",
                 "commit", "-qm", "base")

    def write(self, rel, text):
        path = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="") as handle:
            handle.write(text)

    def git(self, *args):
        done = bounded.run(["git", "-c", "core.autocrlf=false"] + list(args), cwd=self.root, capture_output=True,
                           text=True, timeout=120, creationflags=NO_WINDOW)
        self.assertEqual(done.returncode, 0, done.stderr)

    def bump(self, version="1.0.1"):
        self.write(window_check.VERSION_FILE, "version=%s" % version + LF)
        self.git("add", "-A")

    def record(self, **fields):
        held = {"verdict": "passed", "version": "1.0.1", "subject": window_check.subject(self.root, staged=False)}
        held.update(fields)
        self.write(window_check.RECORD, json.dumps(held))

    def test_a_commit_that_keeps_the_version_is_not_asked(self):
        self.write("src/ctrmap/A.java", "class A { int x; }" + LF)
        self.git("add", "-A")
        self.assertIsNone(window_check.release_commit(self.root))

    def test_a_version_change_with_no_check_is_refused(self):
        self.bump()
        self.assertIn("no readable record", window_check.release_commit(self.root) or "",
                      "a version change with no window check was let through")

    def test_a_version_change_over_what_the_check_measured_goes_on(self):
        """THE CONTROL: the record taken from the working tree matches the index when both hold the same files."""
        self.bump()
        self.record()
        self.assertIsNone(window_check.release_commit(self.root))

    def test_a_version_change_over_anything_else_is_refused(self):
        self.bump()
        self.record()
        self.write("src/ctrmap/A.java", "class A { int changedAfterTheCheck; }" + LF)
        self.git("add", "-A")
        self.assertIn("other src/ and lib/", window_check.release_commit(self.root) or "",
                      "a version change over sources the check never measured was let through")

    def test_a_check_that_failed_or_measured_another_version_is_refused(self):
        self.bump()
        self.record(verdict="refused")
        self.assertIn("did not pass", window_check.release_commit(self.root) or "")
        self.record(version="1.0.0")
        self.assertIn("not 1.0.1", window_check.release_commit(self.root) or "")


class AReleaseWaitsForAnEmptyQueue(AVersionChangeNeedsAPassedCheckOfWhatItCommits):
    """THE OWNER: 1.0.6 "is released only when every line here is closed" (2026-10-18, 2026-10-23, 2026-10-25). Nothing
    refused a version change while OUTSTANDING.md held open work - only the per-push approval stood between. A version
    change is refused while the queue it stages lists an open item; the owner's own switch is the one way past.
    Asked over the passed window check of the class above, so the queue is all that is left to refuse on."""

    SWITCH = None

    def open_item(self):
        self.write("OUTSTANDING.md", "# queue" + LF + LF + "## Open" + LF + LF
                   + "- [ ] 2026-10-25 F191: THE WHOLE-TREE SWEEP" + LF + LF + "## Known limitations" + LF)
        self.bump()
        self.record()

    def test_a_version_change_while_the_queue_holds_open_work_is_refused(self):
        self.open_item()
        said = window_check.release_commit(self.root) or ""
        self.assertTrue("1 open item" in said and "F191" in said,
                        "a release was let through while the queue it stages holds open work: %r" % said)

    def test_the_owners_own_switch_lets_it_through(self):
        self.open_item()
        name = window_check._owner_switch().name(window_check.RELEASE_WITH_OPEN_WORK)
        was = os.environ.get(name)
        self.addCleanup(lambda: os.environ.pop(name, None) if was is None else os.environ.__setitem__(name, was))
        os.environ[name] = "0"
        self.assertIn("open item", window_check.release_commit(self.root) or "",
                      "a switch the owner did not set to 1 let a release with open work through")
        os.environ[name] = "1"
        self.assertIsNone(window_check.release_commit(self.root),
                          "the owner's own switch did not let a release with open work through")

    def test_a_queue_that_cannot_be_read_refuses(self):
        self.write("OUTSTANDING.md", "# no queue here" + LF)
        self.bump()
        self.record()
        said = window_check.release_commit(self.root) or ""
        self.assertIn("UNKNOWN", said, "a queue that could not be read was read as an empty one: %r" % said)


if __name__ == "__main__":
    unittest.main()
