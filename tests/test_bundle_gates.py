# -*- coding: utf-8 -*-
"""THE GATES A COMMIT RUNS, TESTED WHERE THEY SHIP.

`tools/commit_gate.py`, `tools/timeouts.py` and `tools/bundle_install.py` are the three tools a
project is likeliest to replace with its own: the project this bundle came from had built all
three before they shipped here, under other names and paths. So their tests are a file of their
own. A project installing the tools as they are installs this file with them; a project keeping
its own gates declares this file in `.claude/bundle-install.json` beside the tools it replaced,
and tests its own - `check_install.py` refuses silence, not difference.

They used to live in `test_bundle.py`, and a project that kept its own commit gate could then
install neither: the file imported a `commit_gate` that project deliberately does not have.

RUN IT:   python -B -m unittest test_bundle_gates -v
PROVE IT: python -B tools/prove_plants.py --only test_bundle_gates
"""
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
HERE = _tree_root.require(__file__)
TOOLS = os.path.join(HERE, "tools")
sys.path.insert(0, TOOLS)
import bundle_hooks          # noqa: E402  - where this project keeps the bundle's hooks
import bounded         # noqa: E402  - a deadline ends the child's whole tree
HOOKS = bundle_hooks.path(HERE)
OURS = bundle_hooks.folder(HERE)

LF = chr(10)
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

#: This file drives guards. A project that discovers its guard suites by a marker finds it.
GUARD_SUITE = True

#: What `setUpModule` turned off, for `tearDownModule` to put back.
_SHIPPED = []


def setUpModule():
    """Every setting the bundle ships off, off for this file's run: these tests say what the
    bundle's code does as it SHIPS, and a project that turned a stricter rule on installs them too
    (`bundle_hooks.settings_off`)."""
    _SHIPPED[:] = bundle_hooks.settings_off(HERE)


def tearDownModule():
    bundle_hooks.put_back(_SHIPPED)


class ACommitIsAskedWhatSections2And14Ask(unittest.TestCase):
    """`tools/commit_gate.py` - the refusals README describes for a commit, as code. The project
    this came from built each one after paying for its absence; the next project read the prose
    and started again. Every check is driven both ways."""

    FIX = "fix(reader): the empty file" + LF + LF

    def setUp(self):
        commit_gate = bundle_hooks.load(HERE, "tools/commit_gate.py")
        self.gate = commit_gate

    def test_a_fix_that_touches_NO_guard_is_refused_and_one_that_does_is_not(self):
        self.assertTrue(self.gate.fix_touches_a_guard(self.FIX, ["src/reader.py"]))
        self.assertEqual(self.gate.fix_touches_a_guard(self.FIX, ["src/reader.py",
                                                                  "tests/test_reader.py"]), [])
        self.assertEqual(self.gate.fix_touches_a_guard(
            self.FIX + "No-guard: a typo in a message string, which no test could tell from the "
            "right spelling any better than the person reading it" + LF, ["src/reader.py"]), [])

    def test_git_that_cannot_ANSWER_is_not_an_empty_commit(self):
        self.assertTrue(self.gate.fix_touches_a_guard(self.FIX, None))

    def test_a_checker_runs_in_ITS_OWN_repository_not_the_hooks(self):
        """MEASURED 2026-10-07: git hands its commit-msg hook `GIT_INDEX_FILE`, and the checker
        the gate ran inherited it - every test in the bundle's suite that builds a repository of
        its own ran against the bundle's index, and `check_bundle.py`, exiting 0 run by hand,
        exited 1 inside the hook. The plant driver strips git's place from its children; the
        checker's run did not."""
        prove_plants = bundle_hooks.load(HERE, "tools/prove_plants.py")
        from unittest import mock
        held = {name: "/somewhere/else/" + name for name in prove_plants.GIT_LOCATION}
        with mock.patch.dict(os.environ, held):
            env = self.gate.checker_env()
        self.assertEqual(sorted(n for n in prove_plants.GIT_LOCATION if n in env), [],
                         "the checker inherited the hook's git location - it judges the wrong "
                         "repository")
        self.assertEqual(env.get("BUNDLE_COMMIT_GATE_DEPTH"), "1",
                         "the control: the checker is no longer marked as run by the gate")

    def test_a_fix_names_its_KIND_and_a_detector_says_why_it_is_not_a_refusal(self):
        self.assertTrue(self.gate.guard_claim(self.FIX))
        self.assertTrue(self.gate.guard_claim(self.FIX + "Guard: refusal -- short" + LF))
        reason = "a reader that returns zero for a truncated file is refused at the read itself"
        self.assertEqual(self.gate.guard_claim(self.FIX + "Guard: refusal -- " + reason + LF), [])
        detector = self.FIX + "Guard: detector -- " + reason + LF
        self.assertTrue(self.gate.guard_claim(detector))
        self.assertEqual(self.gate.guard_claim(
            detector + "No-refusal: " + "x" * 90 + LF), [])

    def test_a_MARKER_mentioned_in_prose_is_not_the_marker(self):
        """The commit adding a rule once exempted itself by explaining it."""
        prose = self.FIX + "we no longer accept `No-guard:` lines that are this short" + LF
        self.assertTrue(self.gate.fix_touches_a_guard(prose, ["src/reader.py"]))

    def test_an_ESCAPE_without_its_REASON_is_no_escape(self):
        """`No-guard: x` let any fix through: the escape was asked for and its reason was not,
        while No-refusal and Deferred-gap beside it held 80 characters (CTRMap F186)."""
        reason = "a reader that returns zero for a truncated file is refused at the read itself"
        refusal = self.FIX + "Guard: refusal -- " + reason + LF
        for marker, asks in (("No-guard:", lambda m: self.gate.fix_touches_a_guard(
                self.FIX + m, ["src/reader.py"])),
                ("No-guard:", lambda m: self.gate.guard_claim(self.FIX + m)),
                ("No-checker:", lambda m: self.gate.checker_resolves(refusal + m, execute=False))):
            with self.subTest(marker):
                self.assertTrue(asks(marker + " x" + LF),
                                "ESCAPE: a %s line with no reason excused the fix" % marker)
                self.assertEqual(asks(marker + " " + "z" * 90 + LF), [],
                                 "ESCAPE: a %s line WITH its reason was refused" % marker)

    def test_a_projects_OWN_trigger_asks_the_SAME_rule(self):
        """A project whose gate asks the staged DIFF - CTRMap measured the subject line reaching
        33 of 213 fixes - passes its answer, and the rule body asked is this one, not a copy."""
        house = "The reader stops answering zero for a truncated file" + LF + LF
        for name, asks in (("guard place", lambda m, fix: self.gate.fix_touches_a_guard(
                m, ["src/reader.py"], fix=fix)), ("guard kind", self.gate.guard_claim)):
            with self.subTest(name):
                self.assertEqual(asks(house, None), [], "TRIGGER: a non-fix subject was asked")
                self.assertTrue(asks(house, True),
                                "TRIGGER: the project's own answer that this is a fix was ignored")
                self.assertEqual(asks(self.FIX, False), [],
                                 "TRIGGER: the project's own answer that this is NOT a fix was "
                                 "overridden by the subject line")

    def test_an_ADMISSION_needs_a_Deferred_gap_with_a_reason(self):
        admitted = self.FIX + "The writer is still owed a test." + LF
        self.assertTrue(self.gate.deferred_gap(admitted))
        self.assertTrue(self.gate.deferred_gap(admitted + "Deferred-gap: later" + LF))
        self.assertEqual(self.gate.deferred_gap(admitted + "Deferred-gap: " + "y" * 90 + LF), [])
        self.assertTrue(self.gate.deferred_gap(self.FIX + "Note: I have not written it." + LF),
                        "an admission behind a `Note:` prefix walked past")

    def test_NARRATION_is_not_an_admission(self):
        self.assertEqual(self.gate.deferred_gap(
            self.FIX + "Working through those: it had never run. Fixed." + LF), [])

    def test_the_trailer_is_a_SHAPE_not_one_models_name(self):
        for model in ("Opus 5", "Opus 5.5", "Sonnet 5"):
            self.assertEqual(self.gate.coauthor(
                "x" + LF + "Co-Authored-By: Claude %s <noreply@anthropic.com>" % model + LF), [])
        self.assertTrue(self.gate.coauthor(
            "x" + LF + "see Co-Authored-By: Claude X <noreply@anthropic.com> above" + LF))

    def test_the_trailer_Claude_Code_WRITES_is_the_shape(self):
        """Read from the Claude Code CLI itself, 2.1.291: a first-party model signs as `Claude
        <its model>`, a Fable model and an unknown one as plain `Claude`, and some as `Claude
        Code`. The shape required a model word, so every default commit from a session on a Fable
        model was refused by its own project's gate. A name that is not Claude's still is."""
        for name in ("Claude", "Claude Code", "Claude Opus 5.5"):
            with self.subTest(name):
                self.assertEqual(self.gate.coauthor(
                    "x" + LF + LF + "Co-Authored-By: %s <noreply@anthropic.com>" % name + LF), [],
                    "a trailer Claude Code writes by default was refused")
        for name in ("Somebody", "Claudette", "ClaudeX"):
            with self.subTest(name):
                self.assertTrue(self.gate.coauthor(
                    "x" + LF + LF + "Co-Authored-By: %s <noreply@anthropic.com>" % name + LF),
                    "a trailer that does not name Claude was accepted")

    def test_a_QUOTED_count_is_a_mention_and_a_bare_one_is_a_claim(self):
        self.assertEqual(self.gate.test_claims("as the doc says, `green at 6,105 tests`"), [])
        self.assertEqual(self.gate.test_claims("the suite: 6,349 tests, green"), [6349])

    def test_a_count_of_SUITES_is_a_claim_too_and_is_checked_in_the_records_word(self):
        """Census M.85: the gate read `N tests` in a project that writes `N suites`, and five battery-size
        claims went into git log unchecked. Both words are claims; each is checked against the record
        only in the word the record counted, and refused in the other."""
        self.assertEqual(self.gate.worded_claims("the app passed 128 suites"), [(128, "suites")])
        self.assertEqual(self.gate.test_claims("`ALL SUITES PASS at 127 suites`, as quoted"), [])
        folder = tempfile.mkdtemp(prefix="vb-unit-")
        self.addCleanup(shutil.rmtree, folder, True)
        self.addCleanup(setattr, self.gate, "ROOT", self.gate.ROOT)
        self.gate.ROOT = folder
        def said(record, message):
            io.open(os.path.join(folder, self.gate.RECORD), "w", encoding="utf-8").write(json.dumps(record))
            return [p for p in self.gate.test_counts(message) if "tree" not in p and "FAILED" not in p]
        suites = {"ran": 131, "rc": 0, "unit": "suites"}
        self.assertEqual(said(suites, "the battery: 131 suites"), [], "the right count of suites was refused")
        self.assertTrue(said(suites, "the battery: 130 suites"), "a wrong count of suites went through")
        self.assertTrue(said(suites, "the battery: 131 tests"), "a count in the word the record did not count "
                        "went through")
        tests = {"ran": 6349, "rc": 0}
        self.assertEqual(said(tests, "6,349 tests"), [], "a record that names no unit no longer counts tests")
        self.assertTrue(said(tests, "split into 4 suites"), "a suite count was checked against a count of tests")

    def test_an_UNKNOWN_subject_refuses_a_count(self):
        """The shipped defect on the project this came from: git could not be asked, the
        subject was unknown, and the gate passed on the half that never asks what was measured."""
        require_build = bundle_hooks.load(HERE, "tools/require_build.py")
        folder = tempfile.mkdtemp(prefix="vb-gate-")
        self.addCleanup(shutil.rmtree, folder, True)
        self.addCleanup(setattr, self.gate, "ROOT", self.gate.ROOT)
        self.gate.ROOT = folder
        io.open(os.path.join(folder, self.gate.RECORD), "w", encoding="utf-8").write(
            json.dumps({"ran": 12, "rc": 0, "subject": "abc"}))
        held = require_build.subject_digest

        def cannot(*_a, **_k):
            raise require_build.NothingToDigest("no git")
        require_build.subject_digest = cannot
        try:
            found = self.gate.test_counts("fix: x, 12 tests" + LF)
        finally:
            require_build.subject_digest = held
        self.assertTrue(any("UNKNOWN" in line for line in found), found)

    def test_a_fix_proven_in_the_PROJECTS_OWN_ledger_has_its_plant(self):
        """Measured on CTRMap: its Java tests are proven by its own ledger and runner, and this
        gate refused the first fix committed after the install - two plants, both red. A new plant
        in the project's declared ledger is a plant, driven through the project's own command, and
        one that does not redden is refused like any other."""
        own = ("tools/guard/plants.json", "id", ["python", "prove.py", "{key}"])
        self.assertEqual(self.gate.fix_has_a_plant(self.FIX, keys=[], own=own, added=["p1"],
                                                   prove=lambda k: (True, "red")), [],
                         "PROJECT LEDGER: a fix proven in the project's own ledger was refused")
        self.assertTrue(self.gate.fix_has_a_plant(self.FIX, keys=[], own=own, added=["p1"],
                                                  prove=lambda k: (False, "passed")),
                        "PROJECT LEDGER: a plant that did not redden was accepted")
        refused = self.gate.fix_has_a_plant(self.FIX, keys=[], own=own, added=[])
        self.assertTrue(any("tools/guard/plants.json" in line for line in refused),
                        "PROJECT LEDGER: a fix with a plant in neither ledger was not refused by "
                        "name: %r" % refused)

    def test_the_projects_ledger_is_READ_from_its_declaration(self):
        folder = tempfile.mkdtemp(prefix="vb-ledger-")
        self.addCleanup(shutil.rmtree, folder, True)
        self.addCleanup(setattr, self.gate, "ROOT", self.gate.ROOT)
        self.gate.ROOT = folder
        os.makedirs(os.path.join(folder, ".claude"))
        ledger = {"path": "tools/guard/plants.json", "key": "id",
                  "prove": ["python", "tools/guard/replant.py", "{key}"]}

        def declare(entry):
            with io.open(os.path.join(folder, ".claude", "bundle-install.json"), "w",
                         encoding="utf-8", newline=LF) as handle:
                handle.write(json.dumps({"_tests": {"not_covered": "own ledger", "ledger": entry}}))
        declare(ledger)
        self.assertEqual(self.gate.project_ledger(),
                         (ledger["path"], ledger["key"], ledger["prove"]),
                         "PROJECT LEDGER: the declared ledger was not read")
        declare(dict(ledger, prove=["python", "tools/guard/replant.py"]))
        self.assertIsNone(self.gate.project_ledger(),
                          "PROJECT LEDGER: a command naming no plant was taken as one that proves it")
        self.assertEqual((self.gate._plant_keys({"plants": [{"id": "a"}, {"id": "b"}]}, "id"),
                          self.gate._plant_keys({"plants": {"k": {}}})), ({"a", "b"}, {"k"}),
                         "PROJECT LEDGER: a list-shaped or a keyed ledger was misread")

    def test_a_fix_with_no_NEW_plant_or_one_that_stays_green_is_refused(self):
        """own=() - THIS bundle's ledger only. Left unset, the gate reads the INSTALLED project's
        ledger and proves every plant new since HEAD for real: measured in CTRMap, 2026-10-07, 292
        uncommitted plants, each a build and a suite, and the run sat on this test for 40 minutes."""
        self.assertTrue(self.gate.fix_has_a_plant(self.FIX, keys=[], own=()))
        self.assertTrue(self.gate.fix_has_a_plant(self.FIX, keys=["k"],
                                                  drive=lambda k: (False, "passed"), own=()))
        self.assertEqual(self.gate.fix_has_a_plant(self.FIX, keys=["k"],
                                                   drive=lambda k: (True, "red"), own=()), [])


class AHeldPlantIsDrivenAgainWhenItsFileChanges(unittest.TestCase):
    """A plant is watched red on the day it is recorded, and nothing drove it again. Measured on
    the project this came from: 10 of 729 held plants no longer reddened, one emptied the same
    morning by the next edit to its own file, and seven of the ten by a change to the plant's own
    file. The commit that makes the change is where it is asked."""

    HELD = {"test_x.py::C::test_t": {"file": "src/a.py"},
            "test_y.py::D::test_u": {"file": "src/b.py"}}
    TRAILER = LF + LF + "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" + LF

    def setUp(self):
        commit_gate = bundle_hooks.load(HERE, "tools/commit_gate.py")
        self.gate = commit_gate

    def test_a_change_to_the_planted_file_OR_the_named_test_selects_the_plant(self):
        self.assertEqual(self.gate.held_plant_keys(["src/a.py"], self.HELD),
                         ["test_x.py::C::test_t"], "a change to the planted file drove nothing")
        self.assertEqual(self.gate.held_plant_keys(["tests/test_y.py"], self.HELD),
                         ["test_y.py::D::test_u"],
                         "a change to the TEST a plant names drove nothing - the test is half of "
                         "what the plant proves")
        self.assertEqual(self.gate.held_plant_keys(["README.md"], self.HELD), [])
        self.assertIsNone(self.gate.held_plant_keys(None, self.HELD),
                          "a commit git could not list was read as touching nothing")

    def test_a_held_plant_that_stays_green_refuses_the_commit(self):
        refused = self.gate.held_plants_redden(["src/a.py"], keys=["test_x.py::C::test_t"],
                                               drive=lambda k: (False, "passed"))
        self.assertTrue(any("no longer reddens" in line for line in refused),
                        "a held plant that no longer reddened was let through: %r" % refused)
        self.assertEqual(self.gate.held_plants_redden(["src/a.py"], keys=["test_x.py::C::test_t"],
                                                      drive=lambda k: (True, "red")), [])
        self.assertTrue(self.gate.held_plants_redden(None),
                        "which plants a commit touches was UNKNOWN and read as none")

    def test_a_test_red_with_nothing_planted_refuses_the_commit(self):
        """MEASURED 2026-10-10: a test faked a call its code had stopped making and went red on the bundle's
        own tree; the gate drove its held plant "red" - the plant reddened for free - and let it through."""
        key = "test_x.py::C::test_t"
        refused = self.gate.held_plants_redden(["src/a.py"], keys=[key], drive=lambda k: (True, "red"),
                                               clean=lambda keys: ([key], []))
        self.assertTrue(any("RED WITH NOTHING PLANTED" in line and key in line for line in refused),
                        "a held plant on a test already red was counted as proving something: %r" % refused)
        self.assertTrue(any("UNKNOWN" in line for line in self.gate.held_plants_redden(
            ["src/a.py"], keys=[key], drive=lambda k: (True, "red"), clean=lambda keys: ([], [key]))),
            "a test that could not be run clean was read as passing")
        self.assertEqual(self.gate.held_plants_redden(["src/a.py"], keys=[key], drive=lambda k: (True, "red"),
                                                      clean=lambda keys: ([], [])), [],
                         "control: a test green with nothing planted, and red with its plant, passes")

    def test_each_named_test_is_run_once_with_nothing_planted(self):
        prove_plants = bundle_hooks.load(HERE, "tools/prove_plants.py")
        asked = []
        answers = {"test_x.C.test_t": (True, "FAILED"), "test_y.D.test_u": (False, "OK"),
                   "test_z.E.test_v": (None, "timed out")}

        def run(test_id):
            asked.append(test_id)
            return answers[test_id]
        red, unrun = prove_plants.red_with_nothing_planted(
            ["test_x.py::C::test_t", "test_x.py::C::test_t::elsewhere", "test_y.py::D::test_u",
             "test_z.py::E::test_v"], run=run)
        self.assertEqual(red, ["test_x.py::C::test_t"], "a test red with nothing planted was not named")
        self.assertEqual(unrun, ["test_z.py::E::test_v"], "a test with no verdict was not called UNKNOWN")
        self.assertEqual(sorted(asked), ["test_x.C.test_t", "test_y.D.test_u", "test_z.E.test_v"],
                         "each distinct test once, its plants' suffixes folded: %s" % asked)

    def test_a_commit_that_is_NOT_a_fix_is_asked_too(self):
        """The cheapest way past: drive held plants only for a fix. The change that empties a
        plant is usually a refactor or a feature, and says nothing about fixing anything."""
        found = self.gate.problems("refactor(reader): the rule moves" + self.TRAILER,
                                   files=["src/a.py"], keys=[], execute=False,
                                   held=["test_x.py::C::test_t"],
                                   redrive=lambda k: (False, "passed"))
        self.assertTrue(any("no longer reddens" in line for line in found),
                        "a commit that is not a fix walked past a held plant it emptied: %r"
                        % found)

    OWN = ("tools/guard/plants.json", "id", ["python", "prove.py", "{key}"])
    OWN_HELD = [{"id": "p1", "file": "src/A.java", "suite": "ATest"},
                {"id": "p2", "file": "src/B.java", "suite": "BTest"}]

    def test_a_projects_OWN_plant_is_selected_by_its_file_or_its_suite(self):
        """CTRMap proves its Java tests in its own ledger - 221 plants on 2026-09-25 - and each names the file
        it plants into and the suite that must notice."""
        self.assertEqual(self.gate.held_own_plant_keys(["src/A.java"], self.OWN, self.OWN_HELD),
                         ["p1"], "a change to an own plant's file drove nothing")
        self.assertEqual(self.gate.held_own_plant_keys(["src/app/tests/BTest.java"], self.OWN,
                                                       self.OWN_HELD),
                         ["p2"], "a change to the SUITE an own plant names drove nothing")
        self.assertEqual(self.gate.held_own_plant_keys(["README.md"], self.OWN, self.OWN_HELD), [])

    def test_a_projects_OWN_held_plant_that_stays_green_refuses_the_commit(self):
        refused = self.gate.held_plants_redden(["src/A.java"], keys=[],
                                               drive=lambda k: (True, "red"), own_keys=["p1"],
                                               prove=lambda k: (False, "passed"))
        self.assertTrue(any("p1" in line and "no longer reddens" in line for line in refused),
                        "a project's own held plant that no longer reddened was let through: %r"
                        % refused)

    def test_a_plant_this_machine_could_NOT_ASK_is_UNKNOWN_and_refused_never_called_survived(self):
        """Reported by the CTRMap session, 2026-10-07: nineteen plants read the owner's game data,
        which only the owner's machine has, and its driver says so with an exit code of its own -
        while the gate read every non-zero exit as "no longer reddens" and advised re-anchoring
        them. UNKNOWN is still refused; it is never called survived, and never answered with the
        way to make a plant prove less."""
        for shape, call in (
                ("held", lambda: self.gate.held_plants_redden(
                    ["src/A.java"], keys=[], drive=lambda k: (True, "red"), own_keys=["p1"],
                    prove=lambda k: (None, "no game data on this machine"))),
                ("new", lambda: self.gate.fix_has_a_plant(
                    "fix(reader): x" + self.TRAILER, keys=[], own=self.OWN, added=["p1"],
                    prove=lambda k: (None, "no game data on this machine")))):
            refused = call()
            self.assertTrue(any("p1" in line and "UNKNOWN" in line for line in refused),
                            "UNKNOWN: a %s plant nobody could drive was let through, or not called "
                            "UNKNOWN: %r" % (shape, refused))
            self.assertFalse(any("--reanchor" in line or "does not redden" in line
                                 or "no longer reddens" in line for line in refused),
                             "UNKNOWN: a %s plant this machine could not ask was called survived "
                             "and the way to make it prove less was offered: %r" % (shape, refused))

    def test_the_DECLARED_exit_code_reads_as_UNKNOWN_and_no_other(self):
        """The driver's own code, from `_tests.ledger.unknown_exit` - a driver that merely failed
        is survived, as before, and a project that declares no code has none."""
        folder = tempfile.mkdtemp(prefix="vb-unknown-")
        self.addCleanup(shutil.rmtree, folder, True)
        self.addCleanup(setattr, self.gate, "ROOT", self.gate.ROOT)
        self.gate.ROOT = folder
        os.makedirs(os.path.join(folder, ".claude"))

        def declare(**more):
            ledger = dict({"path": "plants.json", "key": "id",
                           "prove": [sys.executable, "-c", "pass", "{key}"]}, **more)
            with io.open(os.path.join(folder, ".claude", "bundle-install.json"), "w",
                         encoding="utf-8", newline=LF) as handle:
                handle.write(json.dumps({"_tests": {"ledger": ledger}}))

        def exits(code):
            return [sys.executable, "-c", "import sys; sys.exit(%d)" % code, "{key}"]
        declare(unknown_exit=3)
        self.assertEqual(self.gate.unknown_exit(), 3, "the declared code was not read")
        self.assertIsNone(self.gate._prove_in_project(exits(3), "p1")[0],
                          "UNKNOWN: the driver's declared could-not-ask code read as an answer")
        self.assertIs(self.gate._prove_in_project(exits(1), "p1")[0], False,
                      "a plant that survived read as UNKNOWN")
        self.assertIs(self.gate._prove_in_project(exits(0), "p1")[0], True)
        self.assertIsNone(self.gate._prove_in_project(["no-such-driver-anywhere", "{key}"],
                                                      "p1")[0],
                          "UNKNOWN: a driver that could not be started read as a plant survived")
        for wrong in (1, 0, True, "3"):
            declare(unknown_exit=wrong)
            self.assertIsNone(self.gate.unknown_exit(),
                              "%r was taken as the could-not-ask code - 0 is red, 1 is survived"
                              % (wrong,))
        declare()
        self.assertIs(self.gate._prove_in_project(exits(3), "p1")[0], False,
                      "with no code declared, a failing driver was read as UNKNOWN")

class APlantLeavesTheLedgerOnlyByAnActTheCommitAsksAgain(unittest.TestCase):
    """The cheapest way past the whole ledger is to delete a guard's test and its plants. So a
    plant HEAD held live leaves `plants` only into a record this gate asks again: `withdrawn` is
    argued on a flush-left `Withdraws:` line, in the change that removes its test, with the test
    gone from what the commit records; `retired` is driven and must run to a pass; and a plant
    that simply vanishes is refused. Each record can be typed in by hand, so the tool's own check
    is not taken on trust."""

    KEY = "test_gone.py::Gone::test_t"
    WHY = ("the page this test drove was replaced by one drawn from the engine, and "
           "test_engine_pages now holds what it held")
    LINE = "Withdraws: %s -- %s" % (KEY, WHY)
    LEDGER = "tests/plants.json"
    FILES = ["tests/plants.json", "tests/test_gone.py"]
    PLANT = {"file": "src/a.py", "old": "a", "new": "b", "site": "instance", "why": "w" * 70}
    TRAILER = LF + LF + "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" + LF

    def setUp(self):
        commit_gate = bundle_hooks.load(HERE, "tools/commit_gate.py")
        self.gate = commit_gate
        self.addCleanup(setattr, self.gate, "LEDGER", self.gate.LEDGER)
        self.gate.LEDGER = self.LEDGER

    def ledgers(self, record="withdrawn"):
        head = {"plants": {self.KEY: dict(self.PLANT)}}
        index = {"plants": {}, record: {self.KEY: dict(self.PLANT, **{record: {"why": self.WHY}})}}
        return head, index

    def leave(self, body, files=None, test_text=None, record="withdrawn"):
        head, index = self.ledgers(record)
        texts = {"tests/test_gone.py": test_text}
        return self.gate.plants_that_leave("feat: the page is the engine's" + LF + LF + body
                                           + self.TRAILER, self.FILES if files is None else files,
                                           head=head, index=index, read=texts.get)

    def test_named_with_a_reason_in_the_change_that_removes_its_test_passes(self):
        self.assertEqual(self.leave(self.LINE), [])

    def test_a_withdrawal_with_no_line_or_a_label_or_an_indented_line_is_refused(self):
        for body in ("The old pages are gone.", "Withdraws: %s -- the page went" % self.KEY,
                     "  " + self.LINE, "Withdraws: test_other.py::O::test_o -- " + self.WHY):
            with self.subTest(body=body[:40]):
                found = self.leave(body)
                self.assertTrue(any("without a flush-left `Withdraws:" in line for line in found),
                                "a withdrawal not argued on its own flush-left line went "
                                "through: %r" % found)

    def test_a_withdrawal_apart_from_its_tests_change_is_refused(self):
        found = self.leave(self.LINE, files=["tests/plants.json", "tools/x.py"])
        self.assertTrue(any("a plant leaves in the change that removes its test" in line
                            for line in found),
                        "a withdrawal apart from its test's change went through: %r" % found)

    def test_a_withdrawal_whose_test_is_STILL_in_the_commit_is_refused(self):
        """The cheapest way past `--withdraw`'s own check: type the record in by hand, touch the
        test file so the commit changes it, and keep the test. Asked of the tree the commit
        RECORDS, so a file deleted on disk and still staged counts as there."""
        kept = LF.join(["import unittest", "class Gone(unittest.TestCase):",
                        "    def test_t(self):", "        pass", ""])
        found = self.leave(self.LINE, test_text=kept)
        self.assertTrue(any("still in what this commit records" in line for line in found),
                        "a plant whose test is still in the commit was withdrawn: %r" % found)

    def test_a_plant_that_VANISHES_is_refused(self):
        head = {"plants": {self.KEY: dict(self.PLANT)}}
        found = self.gate.plants_that_leave("feat: x" + self.TRAILER, self.FILES, head=head,
                                            index={"plants": {}}, read={}.get)
        self.assertTrue(any(self.KEY in line and "unrecorded" in line for line in found),
                        "a plant deleted from the ledger by hand went through: %r" % found)

    def test_a_RETIRED_plant_is_driven_and_must_run_to_a_pass(self):
        """The next cheapest way past `Withdraws:`: call the deleted test's plant retired. Its
        test does not resolve, so it never runs to a pass."""
        head, index = self.ledgers("retired")
        for detail, red, refused in (("the test id does not resolve", False, True),
                                     ("FAILED (failures=1)", True, True),
                                     ("OK", False, False)):
            with self.subTest(detail=detail):
                found = self.gate.retirements_hold(self.FILES, head=head, index=index,
                                                   drive=lambda _k, d=detail, r=red: (r, d))
                self.assertEqual(bool(found), refused,
                                 "a retirement driven to %r was %s: %r"
                                 % (detail, "let through" if refused else "refused", found))

    def test_nothing_leaves_a_ledger_the_commit_leaves_alone_and_UNKNOWN_is_refused(self):
        self.assertEqual(self.gate.plants_that_leave("feat: x", ["src/a.py"], head=None,
                                                     index=None), [],
                         "a commit not touching the ledger was read for departures")
        from unittest import mock
        with mock.patch.object(self.gate, "ledger_at", return_value=None):
            found = self.gate.plants_that_leave("feat: x", self.FILES)
        self.assertTrue(any("UNKNOWN" in line for line in found),
                        "a ledger git could not read was taken as one nothing left")

    def test_the_commit_gate_asks_it(self):
        """Driven through `problems`, so a check written and never wired fails here."""
        head, index = self.ledgers()
        from unittest import mock
        with mock.patch.object(self.gate, "ledger_at",
                               side_effect=lambda spec: head if spec.startswith("HEAD") else index), \
                mock.patch.object(self.gate, "index_reader", return_value={}.get):
            found = self.gate.problems("feat: the page is the engine's" + self.TRAILER,
                                       files=self.FILES, keys=[], execute=False, held=[])
        self.assertTrue(any("is withdrawn without" in line for line in found),
                        "the commit gate never asked whether a withdrawal was argued: %r" % found)


class EveryPlantTheGateDrivesIsCountedAloud(unittest.TestCase):
    """A SUCCESS THAT PRINTS NOTHING READS LIKE A STEP THAT NEVER RAN. On the project this came
    from, 2026-09-26, a commit adding plants showed only `0 plant(s) driven` - from `--owed`, which
    drives none by design - while this gate had driven every new plant red and said nothing."""

    FIX = "fix(reader): the empty file" + LF + LF + "Co-Authored-By: x" + LF

    def setUp(self):
        commit_gate = bundle_hooks.load(HERE, "tools/commit_gate.py")
        self.gate = commit_gate

    def said(self, call):
        import contextlib
        with contextlib.redirect_stdout(io.StringIO()) as out:
            call()
        return out.getvalue()

    def test_new_plants_driven_red_are_counted(self):
        said = self.said(lambda: self.gate.fix_has_a_plant(
            self.FIX, keys=["a", "b"], drive=lambda k: (True, "red"), own=(), added=[]))
        self.assertIn("2 new plant(s) driven, all 2 red", said,
                      "new plants were driven red and the gate said nothing: %r" % said)

    def test_held_plants_driven_again_are_counted(self):
        said = self.said(lambda: self.gate.held_plants_redden(
            ["src/a.py"], keys=["k1", "k2", "k3"], drive=lambda k: (True, "red")))
        self.assertIn("3 held plant(s) driven, all 3 red", said,
                      "held plants were driven again and the gate said nothing: %r" % said)

    def test_all_is_said_only_when_every_one_reddened(self):
        """The cheapest way to always print something reassuring is to count the plants asked."""
        said = self.said(lambda: self.gate.held_plants_redden(
            ["src/a.py"], keys=["k1", "k2"], drive=lambda k: (k == "k1", "red" if k == "k1"
                                                               else "passed")))
        self.assertIn("2 held plant(s) driven, 1 of 2 red", said,
                      "a drive where one plant stayed green was counted as all red: %r" % said)

    def test_a_plant_that_could_not_be_asked_is_COUNTED_as_UNKNOWN(self):
        """"1 of 2 red" over a plant nobody could drive says it survived."""
        said = self.said(lambda: self.gate.held_plants_redden(
            ["src/a.py"], keys=["k1", "k2"], drive=lambda k: (True, "red") if k == "k1"
            else (None, "no data here")))
        self.assertIn("2 held plant(s) driven, 1 of 2 red, 1 UNKNOWN", said,
                      "a plant that could not be asked went uncounted: %r" % said)

    def test_a_held_plant_that_LEFT_the_ledger_is_said_and_not_counted_red(self):
        """A plant this commit withdraws or retires is not driven, and it was counted RED as "not
        in the ledger being committed" - so a commit withdrawing three plants said "all 3 red"
        over three plants nothing drove."""
        folder = tempfile.mkdtemp(prefix="vb-left-")
        self.addCleanup(shutil.rmtree, folder, True)
        os.makedirs(os.path.join(folder, "tests"))
        with io.open(os.path.join(folder, "tests", "plants.json"), "w", encoding="utf-8") as handle:
            handle.write(json.dumps({"plants": {}}))
        self.addCleanup(setattr, self.gate, "ROOT", self.gate.ROOT)
        self.gate.ROOT = folder
        said = self.said(lambda: self.gate.held_plants_redden(
            ["tests/test_gone.py"], keys=["test_gone.py::Gone::test_t"], own_keys=[]))
        self.assertNotIn("all 1 red", said,
                         "a plant that left the ledger was counted red with nothing driven: %r"
                         % said)
        self.assertIn("1 held plant(s) leave the ledger", said,
                      "a held plant leaving the ledger went unsaid: %r" % said)

    def test_the_ratchet_step_says_it_drives_none_rather_than_counting_zero(self):
        prove_plants = bundle_hooks.load(HERE, "tools/prove_plants.py")
        said = self.said(lambda: prove_plants.main(["prove_plants.py", "--owed"]))
        self.assertNotIn("0 plant(s) driven", said,
                         "--owed counted zero plants driven beside a gate that drove them")
        self.assertIn("--owed drives no plant", said)

    def test_a_bare_invocation_drives_nothing_and_names_the_choices(self):
        """TYPING LESS WAS THE EXPENSIVE COMMAND. Measured 2026-09-27 on the project this came
        from: its copy run with no argument - meant as a quick look - started a drive of every
        held plant, about 75 minutes of defects laid on disk one after another, and one of them
        (the hook dispatcher) held another session's every act. The whole ledger is `--all` now;
        nothing is `--all` by omission."""
        prove_plants = bundle_hooks.load(HERE, "tools/prove_plants.py")
        from unittest import mock
        driven = []
        with mock.patch.object(prove_plants, "drive_plant",
                               lambda key, entry, *a, **k: driven.append(key) or (True, "red")):
            code = [None]
            said = self.said(lambda: code.__setitem__(0, prove_plants.main(["prove_plants.py"])))
        self.assertEqual(driven, [], "a bare invocation drove plants")
        self.assertNotEqual(code[0], 0, "a bare invocation that did nothing reported success")
        self.assertIn("--all", said, "the refusal did not say how to drive the whole ledger")

    def test_a_word_nothing_reads_drives_nothing_whatever_it_is_spelt_with(self):
        """Measured 2026-10-09 in the project this came from: its own driver read `--help`, run for
        its usage, as no plant named and drove all 1,363 plants - about 7.5 hours. Here only `--`
        words were asked, so `-h`, or a bare word, still fell through to the same default."""
        prove_plants = bundle_hooks.load(HERE, "tools/prove_plants.py")
        from unittest import mock
        driven = []
        with mock.patch.object(prove_plants, "drive_plant",
                               lambda key, entry, *a, **k: driven.append(key) or (True, "red")):
            for argv in (["-h"], ["-all"], ["help"], ["everything"], ["--owed", "stray"]):
                code = [None]
                said = self.said(lambda: code.__setitem__(0, prove_plants.main(["prove_plants.py"] + argv)))
                #ITS OWN WORDS: the default path has refusals of its own, and one of those printed
                #REFUSING with this refusal planted away - which read as this one
                named = ("%s is not a flag this reads" % argv[-1] in said
                         or "%s is a word this does not read" % argv[-1] in said)
                self.assertTrue(code[0] == 2 and named, "%s was not refused in words: %r" % (argv, said[:300]))
        self.assertEqual(driven, [], "a word nothing reads drove plants")


    def test_an_unclassified_record_can_be_classified_by_the_command_its_refusal_names(self):
        """F200: whole_tree refused an unclassified record with 'Record it as covers or exempts'
        and no command recorded a kind - the remedy was a hand edit."""
        whole_tree = bundle_hooks.load(HERE, "tools/whole_tree.py")
        root = tempfile.mkdtemp(prefix="wt-classify-")
        self.addCleanup(shutil.rmtree, root, True)
        package = os.path.join(root, whole_tree.PACKAGE)
        os.makedirs(package)
        names = ["m%d" % i for i in range(whole_tree.ENOUGH_KEYS + 2)]
        for name in names:
            with io.open(os.path.join(package, name + whole_tree.SOURCE), "w", encoding="utf-8") as handle:
                handle.write("x" + LF)
        record = {"%s/%s%s" % (whole_tree.PACKAGE, name, whole_tree.SOURCE): 1 for name in names[:-1]}
        os.makedirs(os.path.join(root, whole_tree.RECORDS), exist_ok=True)
        with io.open(os.path.join(root, whole_tree.RECORDS, "scores.json"), "w", encoding="utf-8") as handle:
            json.dump(record, handle)
        found = sorted(whole_tree.gaps(root))
        self.assertTrue(found, "control: the scratch record is found as module-keyed")
        label = found[0]
        problems = whole_tree.regressions(root)
        self.assertTrue(any("classify" in p for p in problems),
                        "the unclassified refusal does not name the command: %r" % problems)
        self.assertEqual(whole_tree.classify(label, "sideways", root)[0], 2, "an unknown kind was taken")
        self.assertEqual(whole_tree.classify("no-such-record", "covers", root)[0], 2,
                         "a label that is no record was taken")
        self.assertEqual(whole_tree.classify(label, "covers", root)[0], 0,
                         "the command the refusal names did not classify the record")
        self.assertEqual(whole_tree.held(root).get("kind"), {label: "covers"})
        self.assertEqual(whole_tree.classify(label, "exempts", root)[0], 2,
                         "a record's kind was switched, which moves its ceiling by hand")

    def test_the_programs_proofs_are_not_modules_a_record_must_cover(self):
        """F191: the module walk counted the tests, so the mutation record read 'missing 385 of 418' -
        a little of everything - when it scored 33 of 265 production modules, seven eighths of the
        program unscored."""
        whole_tree = bundle_hooks.load(HERE, "tools/whole_tree.py")
        root = tempfile.mkdtemp(prefix="wt-proofs-")
        self.addCleanup(shutil.rmtree, root, True)
        for rel in ("app/Main", "app/%s/MainTest" % whole_tree.PROOFS, "app/%s_kit/Helper" % whole_tree.PROOFS):
            path = os.path.join(root, whole_tree.PACKAGE, rel + whole_tree.SOURCE)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with io.open(path, "w", encoding="utf-8") as handle:
                handle.write("x" + LF)
        found = whole_tree.modules(root)
        self.assertIn("%s/app/Main%s" % (whole_tree.PACKAGE, whole_tree.SOURCE), found,
                      "control: the program's own module is walked")
        self.assertNotIn("%s/app/%s/MainTest%s" % (whole_tree.PACKAGE, whole_tree.PROOFS, whole_tree.SOURCE), found,
                         "a test under the proofs directory was counted as a module of the program")
        self.assertIn("%s/app/%s_kit/Helper%s" % (whole_tree.PACKAGE, whole_tree.PROOFS, whole_tree.SOURCE), found,
                      "a directory whose name only begins with the proofs' name was left out")
        self.assertEqual(len(found), 2, "the walk found %r" % sorted(found))

    def test_one_verdict_and_a_child_that_never_ran_is_not_a_kill(self):
        """F196: the verdict was written twice and only the Python copy refused a child that never
        ran to a verdict - a declared runner's JVM dying before any test was a KILL."""
        prove_plants = bundle_hooks.load(HERE, "tools/prove_plants.py")
        verdict = prove_plants.verdict
        self.assertFalse(verdict(1, "java.lang.OutOfMemoryError" + LF)[0],
                         "a child that died before any test, with no ran marker and no must_say, was a kill")
        self.assertTrue(verdict(1, "  FAIL: add is wrong" + LF, "add is wrong")[0],
                        "control: a test that printed the plant's own words is a kill")
        self.assertTrue(verdict(1, "1 failed" + LF, None, "failed")[0],
                        "and so is one whose declared ran marker shows it ran")
        self.assertFalse(verdict(1, "Traceback: os._exit" + LF, None, "Ran 1 test")[0],
                         "a unittest child that never printed its summary is not a kill")
        self.assertFalse(verdict(None, "", None, "Ran 1 test")[0], "a hang is never a kill")
        self.assertFalse(verdict(1, "Ran 1 test" + LF, None, "Ran 1 test", True)[0],
                         "an unresolved test is never a kill")
        run = prove_plants.run_declared({"run": [sys.executable, "-c", "import sys; sys.exit(1)"]},
                                        root=HERE)
        self.assertFalse(run("tests/x.py::X")[0],
                         "the declared runner scored a child that ran nothing as a kill")

    def test_a_flag_given_no_value_is_refused_in_words(self):
        """F197: --merge-from and --only read the next argument bare, so given last they crashed with
        IndexError while --add, --reanchor and --retire refused in words, and `--merge-from --only x`
        took a flag for a folder. A crash here reads as not refused."""
        prove_plants = bundle_hooks.load(HERE, "tools/prove_plants.py")
        from unittest import mock
        driven = []
        with mock.patch.object(prove_plants, "drive_plant",
                               lambda key, entry, *a, **k: driven.append(key) or (True, "red")):
            for argv in (["--add"], ["--reanchor"], ["--retire"], ["--merge-from"], ["--only"],
                         ["--merge-from", "--only", "x"], ["--only", "--no-such-flag"]):
                code = [None]

                def call():
                    try:
                        code[0] = prove_plants.main(["prove_plants.py"] + argv)
                    except Exception as crash:  # noqa: BLE001 - a crash is the defect under test
                        print("CRASHED: %r" % crash)
                said = self.said(call)
                self.assertTrue(code[0] not in (0, None) and "REFUSING" in said,
                                "%s was not refused in words: %r" % (argv, said))
        self.assertEqual(driven, [], "a refused invocation drove plants")


class EveryGateSubprocessIsBOUNDED(unittest.TestCase):
    """README section 16: thirteen and a half hours on 0.14 seconds of CPU."""

    def test_every_way_to_start_a_child_and_wait_is_seen(self):
        """F199: the checker keyed on the module NAME `subprocess`, so an alias, a from-import, a
        Popen(...).wait(), os.system and os.popen each blocked forever and passed it."""
        timeouts = bundle_hooks.load(HERE, "tools/timeouts.py")
        source = LF.join([
            "import os",
            "import subprocess as sp",
            "from subprocess import run, check_output",
            "from os import system as shell",
            "sp.run(['a'])",
            "run(['b'])",
            "check_output(['c'])",
            "sp.Popen(['d']).wait()",
            "os.system('e')",
            "os.popen('f').read()",
            "shell('g')",
            "sp.run(['h'], timeout=5)",
            "run(['i'], timeout=5)",
            "sp.Popen(['j']).wait(timeout=5)",
            "sp.Popen(['k']).wait(5)",
        ]) + LF
        self.assertEqual(timeouts.unbounded(source), [5, 6, 7, 8, 9, 10, 11],
                         "the unbounded children were not all seen, or a bounded one was taken for one")

    def test_the_checker_finds_an_unbounded_call_and_passes_a_bounded_one(self):
        timeouts = bundle_hooks.load(HERE, "tools/timeouts.py")
        control = ("import subprocess" + LF + "subprocess.run(['a'], timeout=5)" + LF
                   + "subprocess.run(['b'])" + LF + "p.communicate()" + LF)
        self.assertEqual(timeouts.unbounded(control), [3, 4])

    def test_the_hooks_here_are_derived_and_bounded(self):
        timeouts = bundle_hooks.load(HERE, "tools/timeouts.py")
        found = timeouts.gates()
        self.assertIn(OURS + "/dispatch.py", found)
        self.assertEqual([o for o in timeouts.offenders() if o[0].startswith(OURS + "/")],
                         [], "a hook starts a child it cannot stop waiting for")


class TheInstallIsAskedAtEveryCommit(unittest.TestCase):
    """`tools/bundle_install.py` - the commit's copy of the question `check_install.py` asks."""

    def setUp(self):
        bundle_install = bundle_hooks.load(HERE, "tools/bundle_install.py")
        self.bi = bundle_install
        self.tmp = tempfile.mkdtemp(prefix="vb-bi-")
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def declared(self, where):
        path = os.path.join(self.tmp, "bundle-install.json")
        io.open(path, "w", encoding="utf-8").write(json.dumps({"_bundle": where}))
        return path

    def test_a_bundle_that_CANNOT_BE_READ_refuses(self):
        found = self.bi.problems(declarations=self.declared(os.path.join(self.tmp, "gone")))
        self.assertTrue(found and "not a folder" in found[0], found)

    def test_what_the_checker_REPORTS_is_what_this_refuses(self):
        os.makedirs(os.path.join(self.tmp, "b", "tools"))
        io.open(os.path.join(self.tmp, "b", "tools", "check_install.py"), "w",
                encoding="utf-8").write("def problems(p):" + LF + "    return ['DRIFT'], 3" + LF)
        self.assertEqual(self.bi.problems(declarations=self.declared(
            os.path.join(self.tmp, "b"))), ["DRIFT"])

    def test_loading_the_checker_writes_NO_bytecode_beside_it(self):
        """A project's commit runner spawns this without `-B`, and loading the bundle's checker
        then wrote `tools/__pycache__` INTO THE BUNDLE - which the bundle refuses to hand on."""
        os.makedirs(os.path.join(self.tmp, "d", "tools"))
        io.open(os.path.join(self.tmp, "d", "tools", "check_install.py"), "w",
                encoding="utf-8").write("def problems(p):" + LF + "    return [], 1" + LF)
        held = sys.dont_write_bytecode
        self.addCleanup(setattr, sys, "dont_write_bytecode", held)
        sys.dont_write_bytecode = False
        self.bi.problems(declarations=self.declared(os.path.join(self.tmp, "d")))
        self.assertFalse(os.path.isdir(os.path.join(self.tmp, "d", "tools", "__pycache__")),
                         "loading the checker wrote bytecode into the bundle")
        self.assertFalse(sys.dont_write_bytecode, "the caller's setting was not put back")

    def test_a_checker_that_asked_NOTHING_is_not_clean(self):
        os.makedirs(os.path.join(self.tmp, "c", "tools"))
        io.open(os.path.join(self.tmp, "c", "tools", "check_install.py"), "w",
                encoding="utf-8").write("def problems(p):" + LF + "    return [], 0" + LF)
        found = self.bi.problems(declarations=self.declared(os.path.join(self.tmp, "c")))
        self.assertTrue(found and "NO file" in found[0], found)

    @unittest.skipIf(os.name == "nt" or not shutil.which("sh"),
                     "needs a POSIX `sh` and symlinks - the machines that lack `python` are those")
    def test_the_gate_runs_where_only_python3_does(self):
        """macOS since 12.3, and Debian and Ubuntu without python-is-python3, have `python3` and
        no `python`. Both git hooks said `python -B ...`, so on those machines every commit died
        on `python: not found` before the gate asked anything - measured 2026-10-06. Each hook is
        run here by `sh`, as git runs it, with a PATH holding `python3` and nothing else, over
        stubs of every tool it names that record that they ran."""
        root = os.path.join(self.tmp, "p")
        folder, tools = os.path.join(root, "bin"), os.path.join(root, "tools")
        os.makedirs(folder)
        os.makedirs(tools)
        os.symlink(sys.executable, os.path.join(folder, "python3"))
        ran = os.path.join(root, "ran.txt")
        stub = ("import os" + LF + "with open(%r, 'a') as handle:" % ran + LF
                + "    handle.write(os.path.basename(__file__) + chr(10))" + LF)
        message = os.path.join(root, "message.txt")
        io.open(message, "w", encoding="utf-8").write("a change" + LF)
        for hook in ("pre-commit", "commit-msg"):
            text = io.open(os.path.join(HERE, ".githooks", hook), encoding="utf-8").read()
            # `tools/` AT ANY DEPTH: a project's hook that runs tools/guard/x.py first was never
            # stubbed, so it died on the missing file before the line under test - measured in
            # CTRMap 2026-10-07, whose commit-msg runs tools/guard/commit_guard.py first.
            named = sorted({rel for line in text.splitlines() if not line.lstrip().startswith("#")
                            for rel in re.findall(r"tools/[\w./-]+\.py", line)})
            self.assertTrue(named, "%s runs no tool - nothing here could be asked" % hook)
            for rel in named:
                os.makedirs(os.path.dirname(os.path.join(root, rel)), exist_ok=True)
                io.open(os.path.join(root, rel), "w", encoding="utf-8").write(stub)
            if os.path.exists(ran):
                os.remove(ran)
            done = bounded.run([shutil.which("sh"), os.path.join(HERE, ".githooks", hook),
                                message], cwd=root, capture_output=True, text=True,
                               timeout=120, env={"PATH": folder, "HOME": root},
                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            said = io.open(ran, encoding="utf-8").read().split() if os.path.exists(ran) else []
            self.assertEqual((done.returncode, sorted(said)),
                             (0, sorted(os.path.basename(r) for r in named)),
                             "the %s hook did not run its tools with python3 alone: %s"
                             % (hook, (done.stdout + done.stderr).strip()[-300:]))

    def test_the_install_SCOPE_is_the_checkers_own_walk(self):
        """The write rule's scope was a SECOND walk of the bundle, with a skip list of its own,
        while the checker's walk had grown a rule this one never heard of - a tool's signed cache
        is not a file the bundle ships. Two walks, two answers about what the bundle IS. Asked
        here with a checker whose file list and the folder on disk disagree on purpose."""
        where = os.path.join(self.tmp, "s")
        os.makedirs(os.path.join(where, "tools"))
        os.makedirs(os.path.join(where, "zzcache"))
        io.open(os.path.join(where, "zzcache", "junk.bin"), "w",
                encoding="utf-8").write("not shipped" + LF)
        io.open(os.path.join(where, "tools", "check_install.py"), "w",
                encoding="utf-8").write("def bundle_files(bundle):" + LF
                                        + "    return ['tools/check_install.py', "
                                        + "'tools/shipped.py']" + LF + LF + LF
                                        + "def declarations(p):" + LF + "    return {}, []"
                                        + LF + LF + LF
                                        + "def problems(p):" + LF + "    return [], 1" + LF)
        scope = re.compile(self.bi._installed_scope(self.declared(where)))
        self.assertTrue(scope.match("tools/shipped.py"),
                        "a file the checker says the bundle ships is outside the scope")
        self.assertFalse(scope.match("zzcache/junk.bin"),
                         "a file the checker does not count was put in scope by a second walk")


class ABundleIsFoundWhereverItsProjectDeclaresIt(unittest.TestCase):
    """`_bundle` may be a LIST, so a clone on a machine without the shared copy can name its own.

    Before 2026-09-26 it was one absolute path to a Desktop folder, and a clone of any project on
    a machine without that folder - every cloud machine - had every commit refused. The list is
    tried in order, relative entries are resolved against the PROJECT, and a re-install keeps it.
    """

    def setUp(self):
        bundle_install = bundle_hooks.load(HERE, "tools/bundle_install.py")
        self.bi = bundle_install
        self.tmp = tempfile.mkdtemp(prefix="vb-where-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.project = os.path.join(self.tmp, "project")
        os.makedirs(os.path.join(self.project, ".claude"))

    def declared(self, where):
        path = os.path.join(self.project, ".claude", "bundle-install.json")
        with io.open(path, "w", encoding="utf-8") as handle:
            handle.write(json.dumps({"_bundle": where}))
        return path

    def folder(self, *parts):
        path = os.path.join(self.tmp, *parts)
        os.makedirs(path)
        return path

    def test_the_first_listed_folder_that_exists_is_the_bundle(self):
        present = self.folder("present")
        where, why = self.bi.bundle_path(self.declared([os.path.join(self.tmp, "gone"), present]))
        self.assertEqual(os.path.normcase(where or ""), os.path.normcase(present),
                         "a listed bundle that is NOT a folder was taken over the one that is: "
                         "%r (%s)" % (where, why))

    def test_a_relative_entry_is_found_from_the_project_wherever_the_commit_runs(self):
        os.makedirs(os.path.join(self.project, ".bundle"))
        declarations = self.declared([os.path.join(self.tmp, "gone"), ".bundle"])
        elsewhere = self.folder("somewhere-else")
        before = os.getcwd()
        os.chdir(elsewhere)
        try:
            where, why = self.bi.bundle_path(declarations)
        finally:
            os.chdir(before)
        self.assertEqual(os.path.normcase(where or ""),
                         os.path.normcase(os.path.join(self.project, ".bundle")),
                         "a relative bundle entry was resolved against the WORKING DIRECTORY, "
                         "not the project: %r (%s)" % (where, why))

    def test_a_relative_entry_is_found_beside_claude_not_inside_it(self):
        """The project is the folder HOLDING .claude/, where bundle-install.json sits. Resolved
        against .claude/ itself, `.bundle` would be looked for inside it and never found."""
        os.makedirs(os.path.join(self.project, ".bundle"))
        where, why = self.bi.bundle_path(self.declared([".bundle"]))
        self.assertEqual(os.path.normcase(where or ""),
                         os.path.normcase(os.path.join(self.project, ".bundle")),
                         "a relative entry was resolved against .claude/ rather than the "
                         "project holding it: %r (%s)" % (where, why))

    def test_none_resolving_is_refused_and_names_every_place_tried(self):
        where, why = self.bi.bundle_path(self.declared(
            [os.path.join(self.tmp, "gone"), ".bundle"]))
        self.assertIsNone(where)
        self.assertTrue(why and "not a folder" in why and "gone" in why and ".bundle" in why,
                        "a refusal that does not name where it looked: %r" % why)

    def test_one_path_still_works_as_it_always_did(self):
        present = self.folder("single")
        where, why = self.bi.bundle_path(self.declared(present))
        self.assertEqual(os.path.normcase(where or ""), os.path.normcase(present), why)


def code_page(encoding):
    """While held, a subprocess run with `text=True` and no `encoding` decodes in `encoding` - what
    a Windows machine does with git's UTF-8 bytes - wherever the test runs. Every door Python has
    to that default is patched, because which one `subprocess` asks differs between versions."""
    import contextlib
    import locale
    from unittest import mock
    stack = contextlib.ExitStack()
    stack.enter_context(mock.patch.object(locale, "getpreferredencoding",
                                          lambda *_a, **_k: encoding))
    if hasattr(locale, "getencoding"):
        stack.enter_context(mock.patch.object(locale, "getencoding", lambda: encoding))
    if hasattr(subprocess, "_text_encoding"):
        stack.enter_context(mock.patch.object(subprocess, "_text_encoding", lambda: encoding))
    return stack


class ACommitTheGateNeverSawIsFoundAndRefused(unittest.TestCase):
    """`gate_stamps.py`: a hook reads the call, and a program's `git commit --no-verify` is not in
    the call. Measured 2026-09-23: fifteen spellings of a skipped gate are refused before git
    starts, and `python -c "subprocess.run(['git', 'commit', '--no-verify'])"` is the one no hook
    can see. The gate stamps what it passed; a commit without a stamp is found the moment after."""

    def setUp(self):
        gate_stamps = bundle_hooks.load(HERE, "tools/gate_stamps.py")
        self.gs = gate_stamps
        self.root = tempfile.mkdtemp(prefix="gate-stamps-")
        self.addCleanup(shutil.rmtree, self.root, True)
        self.env = dict(os.environ, GIT_AUTHOR_NAME="a", GIT_AUTHOR_EMAIL="a@example.com",
                        GIT_COMMITTER_NAME="a", GIT_COMMITTER_EMAIL="a@example.com",
                        GIT_EDITOR="true")
        self.git("init", "-q", "-b", "main")
        self.git("commit", "-q", "--allow-empty", "-m", "before the stamps began")
        self.assertEqual(gate_stamps.init(self.root), 0)
        tool = os.path.abspath(gate_stamps.__file__).replace(chr(92), "/")
        python = sys.executable.replace(chr(92), "/")
        with io.open(os.path.join(self.root, ".githooks", "commit-msg"), "w", encoding="utf-8",
                     newline=LF) as handle:
            handle.write("#!/bin/sh" + LF + 'exec "%s" -B "%s" stamp "$1"' % (python, tool) + LF)
        # EXECUTABLE, OR GIT NEVER RUNS IT. Windows runs a hook whatever its mode; Linux and macOS
        # skip one without the bit, with only a hint - so on those every commit here was made
        # without a stamp, five tests read gated commits as made past the gate, and two of the
        # plants they hold could not redden, which refused every install there.
        os.chmod(os.path.join(self.root, ".githooks", "commit-msg"), 0o755)
        self.git("config", "core.hooksPath", ".githooks")

    def git(self, *args, **extra):
        env = dict(self.env, **extra)
        done = bounded.run(["git"] + list(args), cwd=self.root, capture_output=True,
                           text=True, timeout=60, env=env, creationflags=NO_WINDOW)
        self.assertEqual(done.returncode, 0, "git %s failed: %s" % (args, done.stderr))
        return done.stdout.strip()

    def change(self, text):
        with io.open(os.path.join(self.root, "a.txt"), "w", encoding="utf-8",
                     newline=LF) as handle:
            handle.write(text + LF)
        self.git("add", "a.txt")

    def test_a_commit_made_past_the_gate_is_found_until_it_is_off_every_ref(self):
        self.change("one")
        self.git("commit", "-q", "-m", "through the gate")
        self.assertEqual(self.gs.ungated(self.root), [],
                         "a commit the gate passed was reported as made without it")
        self.change("two")
        self.git("commit", "-q", "--no-verify", "-m", "past the gate")
        found = self.gs.ungated(self.root)
        self.assertEqual(len(found), 1, "a commit made past the gate was not found: %s" % found)
        self.assertIn("past the gate", found[0])
        self.git("reset", "-q", "--soft", "HEAD~1")
        self.assertEqual(self.gs.ungated(self.root), [],
                         "a commit taken off every ref was still refused")

    def test_a_message_that_is_not_ascii_is_matched_to_its_stamp(self):
        """MEASURED 2026-09-27 on DF Engine: a subject led by the byte-order mark PowerShell's
        `Set-Content -Encoding utf8` writes was stamped by `commit-msg` and then refused as made
        past the gate, because git's copy was read back in the Windows code page. An em dash or an
        accent does the same."""
        self.change("one")
        path = os.path.join(self.root, "message.txt")
        with io.open(path, "w", encoding="utf-8-sig", newline=LF) as handle:
            handle.write("docs: the rename — café" + LF)
        self.git("commit", "-q", "-F", path)
        # THE CODE PAGE, ON EVERY MACHINE. A UTF-8 locale decodes git's bytes correctly however
        # `_git` asks, so on Linux and macOS this passed with the defect in place: the plant that
        # drops `encoding="utf-8"` stayed green and refused every install there. git is asked as
        # a Windows machine asks it - `text=True` decoding in cp1252.
        with code_page("cp1252"):
            found = self.gs.ungated(self.root)
        self.assertEqual(found, [],
                         "a commit the gate passed was refused because its message is not ASCII")

    def test_a_verdict_is_asked_again_when_the_judge_changes(self):
        """The memo was keyed on the reflogs and the stamps only, so a wrong verdict outlived the
        fix to the rule that gave it - the lock stayed until some ref happened to move."""
        judge = os.path.join(self.root, "judge.py")
        with io.open(judge, "w", encoding="utf-8") as handle:
            handle.write("v1" + LF)
        original = self.gs.__file__
        self.gs.__file__ = judge
        self.addCleanup(setattr, self.gs, "__file__", original)
        memo = os.path.join(self.root, ".git", self.gs.JUDGED)
        self.assertEqual(self.gs.ungated(self.root), [])
        with io.open(memo, encoding="utf-8") as handle:
            held = json.load(handle)
        held["found"] = ["a stale verdict"]
        with io.open(memo, "w", encoding="utf-8") as handle:
            json.dump(held, handle)
        self.assertEqual(self.gs.ungated(self.root), ["a stale verdict"],
                         "the control: with nothing changed the memo is what is answered")
        with io.open(judge, "w", encoding="utf-8") as handle:
            handle.write("version two of the judge" + LF)
        self.assertEqual(self.gs.ungated(self.root), [],
                         "a verdict outlived a change to the rule that gave it")

    def test_every_way_git_runs_the_gate_leaves_a_stamp_that_matches(self):
        """`commit -a` hands the hook a TEMPORARY index; an amend and a merge are commits too."""
        self.change("one")
        self.git("commit", "-q", "-m", "first")
        with io.open(os.path.join(self.root, "a.txt"), "w", encoding="utf-8",
                     newline=LF) as handle:
            handle.write("changed, not staged" + LF)
        self.git("commit", "-q", "-a", "-m", "with -a")
        self.git("commit", "-q", "--amend", "-m", "amended")
        self.git("checkout", "-q", "-b", "side", "HEAD~1")
        with io.open(os.path.join(self.root, "b.txt"), "w", encoding="utf-8",
                     newline=LF) as handle:
            handle.write("side" + LF)
        self.git("add", "b.txt")
        self.git("commit", "-q", "-m", "on the side")
        self.git("checkout", "-q", "main")
        self.git("merge", "-q", "--no-ff", "-m", "merged", "side")
        self.assertEqual(self.gs.ungated(self.root), [],
                         "a commit the gate passed carried no stamp that matched it")

    def test_a_repointed_hooks_path_leaves_no_stamp(self):
        self.change("one")
        self.git("-c", "core.hooksPath=%s" % os.devnull, "commit", "-q", "-m", "no hooks")
        self.assertEqual(len(self.gs.ungated(self.root)), 1,
                         "a commit git ran no hook for was not found")

    def test_a_gated_TREE_under_an_ungated_MESSAGE_is_still_ungated(self):
        """The cheapest way past a stamp is one that is already there: amend a gated commit's
        message with `--no-verify`, and the tree is the one the gate stamped."""
        self.change("one")
        self.git("commit", "-q", "-m", "the message the gate read")
        self.git("commit", "-q", "--amend", "--no-verify", "-m", "a message nobody read")
        self.assertEqual(len(self.gs.ungated(self.root)), 1,
                         "a gated tree under a message the gate never read passed as gated")

    def test_a_BACKDATED_commit_is_still_made_after_the_stamps_began(self):
        """A reflog's time is whatever GIT_COMMITTER_DATE says, so a line drawn in time is one
        environment variable from walked past. The line is the commits reachable then."""
        self.change("one")
        self.git("commit", "-q", "--no-verify", "-m", "dated long ago",
                 GIT_COMMITTER_DATE="1500000000 +0000")
        self.assertEqual(len(self.gs.ungated(self.root)), 1,
                         "a commit dated before the stamps began was let through")

    def test_a_commit_in_ANOTHER_WORKTREE_is_asked_too(self):
        tree = tempfile.mkdtemp(prefix="gate-stamps-wt-")
        shutil.rmtree(tree)
        self.addCleanup(shutil.rmtree, tree, True)
        self.git("worktree", "add", "-q", "--detach", tree)
        done = bounded.run(["git", "commit", "-q", "--allow-empty", "--no-verify", "-m",
                            "detached, elsewhere"], cwd=tree, capture_output=True,
                           text=True, timeout=60, env=self.env, creationflags=NO_WINDOW)
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertEqual(len(self.gs.ungated(self.root)), 1,
                         "a commit made past the gate in another worktree was not found")

    def test_while_one_is_ungated_only_a_read_or_a_repair_may_run(self):
        self.change("one")
        self.git("commit", "-q", "--no-verify", "-m", "past the gate")
        self.assertTrue(self.gs.before_command("python -m unittest", self.root),
                        "work was let run on top of a commit the gate never saw")
        for repair in ("git reset --soft HEAD~1", "git log -1", "git status"):
            with self.subTest(repair):
                self.assertEqual(self.gs.before_command(repair, self.root), [],
                                 "the repair of an ungated commit was refused")

    def test_with_NO_BASELINE_the_remedy_the_refusal_names_runs_and_nothing_else_does(self):
        """MEASURED 2026-10-07 in the bundle's own repository, the moment its wiring landed: every
        command was refused with "`gate_stamps.py init` writes it" - and `gate_stamps.py init`,
        not being a git read, was refused with them. A refusal whose remedy it refuses is a
        lock-out; only the Write tool, which this rule does not ask, was left."""
        os.remove(os.path.join(self.root, self.gs.BEFORE_FILE))
        self.assertTrue(self.gs.before_command("python -m unittest", self.root),
                        "the control: with no baseline, work was let run")
        for remedy in ("python -B tools/gate_stamps.py init", "python3 tools/gate_stamps.py init",
                       'py -3 "C:/b/tools/gate_stamps.py" init'):
            with self.subTest(remedy):
                self.assertEqual(self.gs.before_command(remedy, self.root), [],
                                 "the remedy the refusal names was refused by the refusal")
        for dressed in ("python -B tools/gate_stamps.py init; python -m unittest",
                        "python -B tools/gate_stamps.py check", "python init.py",
                        "python -B tools/gate_stamps.py init --force"):
            with self.subTest(dressed):
                self.assertTrue(self.gs.before_command(dressed, self.root),
                                "a command dressed as the remedy walked past the refusal")

    def test_once_the_baseline_EXISTS_its_remedy_is_no_way_past_an_ungated_commit(self):
        """The cheapest way past a stamp check is a command it lets through: `init` is the remedy
        only while there is no baseline, and with one it forgives nothing."""
        self.change("one")
        self.git("commit", "-q", "--no-verify", "-m", "past the gate")
        self.assertTrue(self.gs.before_command("python -B tools/gate_stamps.py init", self.root),
                        "the baseline's remedy let a command run over an ungated commit")

    def test_the_baseline_is_written_once_and_never_widened(self):
        """Forgiving a commit is the cheapest way past this check: write it into the baseline,
        or write the baseline again after it."""
        self.change("one")
        self.git("commit", "-q", "--no-verify", "-m", "past the gate")
        head = self.git("rev-parse", "HEAD")
        self.assertEqual(self.gs.init(self.root), 1, "the baseline was written a second time")
        rel = self.gs.BEFORE_FILE.replace(os.sep, "/")
        with io.open(os.path.join(self.root, self.gs.BEFORE_FILE), encoding="utf-8") as handle:
            now = handle.read()
        self.assertTrue(self.gs.forgiven_in(rel, now, now + head + LF, self.root),
                        "a commit written into the baseline was forgiven")
        self.git("add", rel)
        self.git("commit", "-q", "--no-verify", "-m", "the baseline, tracked")
        self.assertTrue(self.gs.forgiven_in(rel, None, now + head + LF, self.root),
                        "the baseline deleted and written again was forgiven")
        self.assertEqual(self.gs.forgiven_in(rel, now, now, self.root), [])

    def test_no_baseline_is_UNKNOWN_and_a_quiet_tree_is_answered_from_the_memo(self):
        self.assertEqual(self.gs.ungated(self.root), [])
        held = self.gs._git
        self.addCleanup(setattr, self.gs, "_git", held)
        calls = []
        self.gs._git = lambda *a, **k: calls.append(a) or held(*a, **k)
        self.gs.ungated(self.root)
        self.assertEqual([c for c in calls if "rev-list" in c[1]], [],
                         "a tree where no ref moved was walked again")
        os.remove(os.path.join(self.root, self.gs.BEFORE_FILE))
        found = self.gs.ungated(self.root)
        self.assertTrue(found and "UNKNOWN" in found[0],
                        "a missing baseline read as every commit gated")

    def test_every_way_a_call_could_FORGE_a_stamp_is_refused_before_it_runs(self):
        """A stamp written by anything but the gate passes every check, so the stamps are written
        by `commit-msg` and by nothing else: the store named, the `stamp` step run by hand, git's
        hook run by hand, or a script - however it was made - whose text does any of it."""
        forge = os.path.join(self.root, "forge.py")
        with io.open(forge, "w", encoding="utf-8", newline=LF) as handle:
            handle.write("open('.git/gate-stamps', 'a').write('tree digest')" + LF)
        tool = self.gs.__file__
        for command in ("echo x >> .git/gate-stamps",
                        "python -c \"open('.git/gate-stamps', 'a').write('x')\"",
                        "python forge.py",
                        "python -X utf8 %s" % forge,
                        "python forge.py %s check" % tool,
                        "python -B %s stamp msg.txt" % tool,
                        "sh .githooks/commit-msg msg.txt",
                        "git hook run commit-msg -- msg.txt"):
            with self.subTest(command):
                self.assertTrue(self.gs.forges(command, self.root),
                                "a way to write a stamp no gate wrote was let run")
                self.assertTrue(self.gs.before_command(command, self.root))
        for command in ("python -B %s check" % tool, "git status", "cat msg.txt",
                        "python -m unittest discover -s tests"):
            with self.subTest(command):
                self.assertEqual(self.gs.forges(command, self.root), "",
                                 "an honest command was read as forging a stamp")

    def test_the_PROGRAM_is_one_word_and_a_linter_reading_a_file_is_not_running_it(self):
        """Every word after an interpreter was read as a script, so `python -m ruff check` over a
        test that names the store was refused as forging a stamp. The program is the script, or
        the module `-m` names: an installed reader's arguments are data, a module found in the
        project is a file whose text is asked - a local `ruff.py` included - and an unknown
        module's arguments are still asked, as a script's are."""
        def write(name, text):
            with io.open(os.path.join(self.root, name), "w", encoding="utf-8",
                         newline=LF) as handle:
                handle.write(text + LF)
        write("names_it.py", "STORE = '.git/gate-stamps'")
        write("names_it.json", '{"store": ".git/gate-stamps"}')
        write("forger.py", "open('.git/gate-stamps', 'a').write('tree digest')")
        for command in ("python -m ruff check names_it.py",
                        "python -B -m py_compile names_it.py",
                        "py -3 -mpyflakes names_it.py",
                        "python -m json.tool names_it.json"):
            with self.subTest(command):
                self.assertEqual(self.gs.forges(command, self.root), "",
                                 "a linter reading a file was read as running it")
        for command, said in (
                ("python -m forger", "a module in the project run with -m was never read"),
                ("python -m runpy forger.py",
                 "an unknown module's arguments were waved through as data"),
                ("python3.12 forger.py", "a versioned interpreter ran a script nothing read")):
            with self.subTest(command):
                self.assertTrue(self.gs.forges(command, self.root), said)
        write("ruff.py", "open('.git/gate-stamps', 'a').write('tree digest')")
        self.assertTrue(self.gs.forges("python -m ruff check names_it.py", self.root),
                        "a local ruff.py shadowing the linter was read as the linter")
        #F181: a test file that names the store is refused when RUN - and told how it is run
        write("test_names_it.py", "STORE = '.git/gate-stamps'")
        said = self.gs.forges("python -B test_names_it.py", self.root)
        self.assertTrue(said and "python -m unittest test_names_it" in said,
                        "the refusal of a test file does not name the route that runs it: %r" % said)

    def _files(self):
        """A reader and a forger in each language, and files that name the store."""
        for name, text in (
                ("show.py", "import json, sys; print(len(json.load(open(sys.argv[1]))))"),
                ("show.sh", "wc -c \"$1\""),
                ("show.ps1", "Get-Content $args[0]"),
                ("forge.py", "open('.git/gate-stamps', 'a').write('tree digest')"),
                ("forge.sh", "echo tree >> .git/gate-stamps"),
                ("forge.ps1", "Add-Content .git/gate-stamps tree"),
                ("ledger.json", '{"plants": {"x": {"old": ".git/gate-stamps"}}}'),
                ("notes.txt", "the stamps live in .git/gate-stamps"),
                ("names_it.py", "STORE = '.git/gate-stamps'")):
            with io.open(os.path.join(self.root, name), "w", encoding="utf-8",
                         newline=LF) as handle:
                handle.write(text + LF)
        os.makedirs(os.path.join(self.root, "a dir"))
        for name in ("show.py", "forge.py"):
            shutil.copy(os.path.join(self.root, name), os.path.join(self.root, "a dir", name))

    def test_a_DATA_file_handed_to_a_script_is_read_by_it_not_run(self):
        """`python -B show.py tests\\plants.json` was refused on 2026-09-27 as forging a stamp: the
        plant ledger names the store because it holds plants for this tool's own tests, and it
        was the ARGUMENT of a script that only reads it. The program is the interpreter's script,
        and nothing it is handed is read, whatever its kind - the owner's rule."""
        self._files()
        os.makedirs(os.path.join(self.root, "tests"))
        shutil.copy(os.path.join(self.root, "ledger.json"),
                    os.path.join(self.root, "tests", "plants.json"))
        for command in ("python -B show.py ledger.json", "python show.py -v notes.txt",
                        "py -3 show.py tests\\plants.json", "python -B show.py names_it.py"):
            with self.subTest(command):
                self.assertEqual(self.gs.forges(command, self.root), "",
                                 "a data file a script reads was read as a program it runs")

    def test_a_program_named_by_a_VARIABLE_is_not_replaced_by_its_data(self):
        """A program this cannot find - `$TOOLS/show.py`, a variable - was skipped, and its first
        FILE argument read as the program instead: `python3 $T/show.py tests/plants.json` was
        refused as forging a stamp (measured 2026-10-06), the case above for a program named
        literally. The forger named literally, and handed nothing, is still read."""
        self._files()
        os.makedirs(os.path.join(self.root, "tests"))
        shutil.copy(os.path.join(self.root, "ledger.json"),
                    os.path.join(self.root, "tests", "plants.json"))
        for command in ("python3 $T/show.py tests/plants.json", "time python3 $T/show.py notes.txt"):
            with self.subTest(command):
                self.assertEqual(self.gs.forges(command, self.root), "",
                                 "a data file was read as the program a variable names")
        self.assertTrue(self.gs.forges("python3 forge.py ledger.json", self.root),
                        "the control: a forger named literally was not read")

    def test_a_data_file_handed_to_ANOTHER_interpreter_is_data_too(self):
        self._files()
        for command in ("bash show.sh ledger.json", "sh show.sh notes.txt",
                        "pwsh -ExecutionPolicy Bypass -File show.ps1 ledger.json"):
            with self.subTest(command):
                self.assertEqual(self.gs.forges(command, self.root), "",
                                 "a data file a shell script reads was read as a program")

    def test_a_PROGRAM_handed_to_a_script_is_still_read(self):
        """The program is still read whatever follows it: the script itself, and the file `-m
        runpy` is handed, because `-m` names a module and that file is the first thing run. The
        cheapest widening of the rule above is to read no file at all - and then the forger itself
        walks past, with a data file after it or an option's value in front of it."""
        self._files()
        for command in ("python forge.py ledger.json", "python -B forge.py notes.txt",
                        "bash forge.sh ledger.json",
                        "pwsh -ExecutionPolicy Bypass -File forge.ps1 ledger.json",
                        "python -m runpy forge.py ledger.json",
                        "@'" + LF + "open('.git/gate-stamps', 'a').write('x')" + LF
                        + "'@ | python -"):
            with self.subTest(command):
                self.assertTrue(self.gs.forges(command, self.root),
                                "the program itself was never read")

    def test_a_QUOTED_script_path_is_one_word(self):
        """Split on spaces, `python "C:\\a dir\\forge.py"` was two fragments, neither a file, so a
        forger in any folder with a space in its name was never read. Relative, because this
        class's root is named `gate-stamps-...` and any path through it names the store."""
        self._files()
        spaced = os.path.join("a dir", "forge.py")
        for command in ('python "%s"' % spaced, "python -B '%s' ledger.json" % spaced):
            with self.subTest(command):
                self.assertTrue(self.gs.forges(command, self.root),
                                "a quoted script path with a space in it was never read")

    def test_a_QUOTED_interpreter_path_is_one_word_too(self):
        self._files()
        command = '"C:\\Program Files\\Python312\\python.exe" forge.py'
        self.assertTrue(self.gs.forges(command, self.root),
                        "a quoted interpreter path with a space in it ran a script nothing read")

    def test_a_QUOTED_reader_handed_data_is_let_run(self):
        """The cheapest way to refuse a quoted forger is to refuse every quote - and then the
        command as it was refused on 2026-09-27, a quoted script path reading the plant ledger,
        is refused again the moment its folder has a space in it."""
        self._files()
        shown = os.path.join("a dir", "show.py")
        self.assertEqual(self.gs.forges('python -B "%s" ledger.json' % shown, self.root), "",
                         "a quoted reader handed a data file was read as forging a stamp")

    def test_a_copy_installed_DEEPER_still_finds_its_repository(self):
        """A project may keep its tools one level down - `tools/audit/` - and a root counted in
        `dirname`s then names `tools/`, so `init` writes the baseline where nothing reads it."""
        import importlib.util
        deeper = os.path.join(self.root, "tools", "audit")
        os.makedirs(deeper)
        shutil.copy(self.gs.__file__, os.path.join(deeper, "gate_stamps.py"))
        spec = importlib.util.spec_from_file_location(
            "gate_stamps_deeper", os.path.join(deeper, "gate_stamps.py"))
        copy = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(copy)
        bundle_hooks.turn_off(copy)
        self.assertEqual(os.path.normcase(copy.ROOT), os.path.normcase(self.root),
                         "a copy installed under tools/audit/ took tools/ for its repository")

    def test_a_CHERRY_PICKED_commit_carries_no_stamp_and_one_committed_through_the_gate_does(self):
        """cherry-pick, like rebase, revert and am, makes a commit git runs no `commit-msg` for, so
        a gated commit copied that way arrives unstamped - left unasked, it was the last cheap way
        round the gate. `--no-commit`, then a commit through the gate, is the stamped way."""
        self.git("checkout", "-q", "-b", "side")
        self.change("from the side")
        self.git("commit", "-q", "-m", "on the side")
        self.git("checkout", "-q", "main")
        # MAIN MOVES FIRST, so the picked commit's tree is one no gate ever saw. Picked onto a
        # main holding nothing else, it is the side commit's tree and message exactly - which the
        # gate DID pass - and the example would prove nothing.
        with io.open(os.path.join(self.root, "b.txt"), "w", encoding="utf-8",
                     newline=LF) as handle:
            handle.write("main moved" + LF)
        self.git("add", "b.txt")
        self.git("commit", "-q", "-m", "main moved")
        self.git("cherry-pick", "side")
        self.assertEqual(len(self.gs.ungated(self.root)), 1,
                         "a cherry-picked commit passed as gated")
        self.git("reset", "-q", "--hard", "HEAD~1")
        self.git("cherry-pick", "--no-commit", "side")
        self.git("commit", "-q", "-m", "on the side, through the gate")
        self.assertEqual(self.gs.ungated(self.root), [],
                         "a cherry-pick committed through the gate was refused")


class EveryToolHereIsAskedAtTheActItGuards(unittest.TestCase):
    """Each tool this folder ships declares the act it is asked at, and answers there.

    The project this came from recorded fourteen rules as refusing "at the write" while the
    Edit and Write tools went straight past them, and thirty-three more at the commit or in the
    suite. A tool that refuses must be a hook on the call, a rule a wired hook asks at the act, or
    the commit gate while nothing can skip it - `tools/tiers.py` derives which, and refuses the
    rest at the write that lands it."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="bundle-act-")
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def write(self, rel, text):
        path = os.path.join(self.tmp, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with io.open(path, "w", encoding="utf-8", newline=LF) as handle:
            handle.write(text)
        return path

    def test_every_tool_that_refuses_declares_an_act_or_is_run_by_the_gate(self):
        tiers = bundle_hooks.load(HERE, "tools/tiers.py")
        sys.path.insert(0, HOOKS)
        import bundle_rules
        gate = set()
        for name in sorted(os.listdir(os.path.join(HERE, ".githooks"))):
            gate |= tiers.gate_named(tiers.read(HERE, os.path.join(".githooks", name)))
        loose = [rel for rel, text in tiers.guards(HERE)
                 if rel.startswith("tools/") and rel not in gate
                 and not bundle_rules.declarations_in(text)]
        # THE PROJECT'S ADOPTED DEBT, AS tiers.py HOLDS IT - and held TIGHT. Installed, this asked
        # a stricter rule than the ratchet it tests: measured in CTRMap 2026-10-07, 31 of its own
        # tools refuse with no act, every one of them in the debt `--adopt` recorded at install,
        # and tiers.py passes them while this failed. Here in the bundle nothing is adopted, so
        # nothing changes. The debt only shrinks: an entry that is no longer loose is slack a tool
        # could fall back into unnoticed, and is refused until the record lets it go.
        debt = tiers.adopted(HERE)
        self.assertEqual([rel for rel in loose if rel not in debt], [],
                         "these tools refuse things and declare no act to be asked at")
        self.assertEqual(sorted(rel for rel in debt if rel.startswith("tools/") and rel not in loose),
                         [], "the adopted debt still holds tools that are no longer below the point "
                             "of action - take them out of `_tiers_adopted`, or they can fall back")

    def test_tiers_refuses_a_guard_nothing_asks_and_credits_one_a_wired_hook_asks(self):
        tiers = bundle_hooks.load(HERE, "tools/tiers.py")
        self.write(".claude/settings.json", '{"command": "python %s/dispatch.py"}' % OURS)
        self.write(OURS + "/guard_write_rules.py", "# present" + LF)
        self.write("CLAUDE.md", "# scratch" + LF)
        self.write("tools/declared.py", 'AT_WRITE = "f"' + LF + LF + LF
                   + "def f(rel, text):" + LF + '    return ["REFUSING"]' + LF)
        self.write("tools/bare.py", 'print("REFUSING: something")' + LF)
        self.assertEqual(tiers.below(self.tmp), ["tools/bare.py"],
                         "the derivation did not tell a declared rule from a detector")

    def test_an_ADOPTED_tool_list_only_SHRINKS_at_the_write(self):
        """`--adopt` records, once, the tools a project arrived with below the point of action,
        and its docstring says the set "can only shrink as its tools are brought to the act".
        Nothing asked: an Edit adding a name to it excused a new detector as starting debt at
        the commit, and deleting the record then writing a larger one was two allowed writes."""
        from unittest import mock
        tiers = bundle_hooks.load(HERE, "tools/tiers.py")
        rel = ".claude/bundle-install.json"

        def record(*below):
            return json.dumps({"_tiers_adopted": {"at": "2026-10-06T00:00:00Z",
                                                  "below": list(below)}})
        before, grown, shrunk = record("tools/old.py"), record("tools/old.py", "tools/new.py"), \
            record()
        with mock.patch.object(tiers, "committed_declarations", return_value=""):
            self.assertTrue(tiers.adoption_change_problems(rel, before, grown, self.tmp),
                            "a name added to the adoption by hand went through")
            self.assertEqual(tiers.adoption_change_problems(rel, before, shrunk, self.tmp), [],
                             "the adoption shrinking - its tools brought to the act - was refused")
            self.assertEqual(tiers.adoption_change_problems(rel, "{}", before, self.tmp), [],
                             "the first adoption, with none committed, was refused")
        with mock.patch.object(tiers, "committed_declarations", return_value=before):
            self.assertTrue(tiers.adoption_change_problems(rel, "{}", grown, self.tmp),
                            "deleting the adoption and writing a larger one went through")

    def test_the_COMMIT_holds_the_adoption_to_HEADs(self):
        """The second layer, for a write no hook saw: `tiers.py`, which every commit runs, refuses
        an adoption larger than the one HEAD carries."""
        from unittest import mock
        tiers = bundle_hooks.load(HERE, "tools/tiers.py")
        head = json.dumps({"_tiers_adopted": {"at": "x", "below": ["tools/old.py"]}})
        out = io.StringIO()
        with mock.patch.object(tiers, "guards", return_value=[("tools/new.py", "")]), \
                mock.patch.object(tiers, "below", return_value=["tools/new.py"]), \
                mock.patch.object(tiers, "adopted", return_value={"tools/old.py", "tools/new.py"}), \
                mock.patch.object(tiers, "committed_declarations", return_value=head), \
                mock.patch("sys.stdout", out):
            code = tiers.main(["tiers.py"])
        self.assertEqual(code, 1, "an adoption grown past HEAD's passed the commit: %s"
                         % out.getvalue())
        self.assertIn("grows past HEAD's", out.getvalue())

    def test_the_adoption_rule_is_DECLARED_where_the_write_hook_asks_it(self):
        sys.path.insert(0, HOOKS)
        import bundle_rules
        asked = [rule for rule in bundle_rules.rules(HERE) if rule.name == "tiers"
                 and rule.kind == "AT_WRITE_CHANGE" and rule.watches(".claude/bundle-install.json")]
        self.assertEqual(len(asked), 1, "no write-time rule watches the adoption record")

    def machinery_tree(self):
        """A dispatched guard importing a helper that says no, and a loose module nobody asks."""
        self.write(".claude/settings.json", '{"command": "python %s/dispatch.py"}' % OURS)
        self.write(OURS + "/dispatch.py", "# the dispatcher" + LF)
        self.write(OURS + "/bundle_rules.py", "# the mark" + LF)
        self.write(OURS + "/guard_x.py", "import helper_x" + LF + "def main():" + LF
                   + '    print("REFUSING: x")' + LF)
        self.write(OURS + "/helper_x.py", 'WHY = "REFUSING: because"' + LF)
        self.write(OURS + "/loose_y.py", 'WHY = "REFUSING: nobody asks me"' + LF)
        self.write("CLAUDE.md", "# scratch" + LF)
        return {OURS + "/" + name for name in ("helper_x.py", "loose_y.py")}

    def test_a_module_a_dispatched_guard_imports_is_asked_where_the_guard_is(self):
        """MACHINERY was a list of four names, and `request_ledger` - asked only by
        `guard_requests` and `guard_fanout` - was refused as below the point of action by
        CTRMap's first commit after it arrived. Imported by a dispatched guard is asked there."""
        tiers = bundle_hooks.load(HERE, "tools/tiers.py")
        self.machinery_tree()
        self.assertEqual([r for r in tiers.below(self.tmp) if r.startswith(OURS + "/")],
                         [OURS + "/loose_y.py"],
                         "a module a dispatched guard imports was refused as nobody's")

    def test_the_write_time_judge_derives_the_same_machinery(self):
        tiers = bundle_hooks.load(HERE, "tools/tiers.py")
        mine = self.machinery_tree()
        self.addCleanup(setattr, tiers, "_root", tiers._root)
        tiers._root = lambda: self.tmp
        facts = {}
        for base, _dirs, names in os.walk(self.tmp):
            for name in names:
                rel = os.path.relpath(os.path.join(base, name), self.tmp).replace(os.sep, "/")
                facts[rel] = tiers.facts_of(rel, tiers.read(self.tmp, rel))
        refused = [line.split(" ", 1)[0] for line in tiers.judge(facts)]
        self.assertEqual(sorted(r for r in refused if r in mine), [OURS + "/loose_y.py"],
                         "the write-time judge refused a module a dispatched guard imports")

    def test_an_exclusion_from_the_guards_must_carry_its_reason(self):
        tiers = bundle_hooks.load(HERE, "tools/tiers.py")
        self.assertEqual(tiers.excusals(), [])
        held = dict(tiers.NOT_GUARDS)
        self.addCleanup(setattr, tiers, "NOT_GUARDS", held)
        tiers.NOT_GUARDS = dict(held, anything="short")
        self.assertTrue(tiers.excusals(), "an exclusion with a label for a reason was accepted")

    def test_the_commit_gate_answers_a_message_before_git_starts(self):
        commit_gate = bundle_hooks.load(HERE, "tools/commit_gate.py")
        self.assertTrue(commit_gate.message_problems("docs: a note" + LF),
                        "a message with no co-author trailer passed the command-time check")
        self.assertEqual(commit_gate.message_problems(
            "docs: a note" + LF + LF + "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
            + LF), [])

    def test_a_gate_file_gaining_an_unbounded_child_is_refused_at_the_write(self):
        timeouts = bundle_hooks.load(HERE, "tools/timeouts.py")
        source = "import subprocess" + LF + 'subprocess.run(["x"])' + LF
        self.assertTrue(timeouts.findings_in(OURS + "/dispatch.py", source),
                        "an unbounded child in a file every tool call runs was let in")
        self.assertEqual(timeouts.findings_in("src/not_a_gate.py", source), [])

    def test_a_record_covering_LESS_of_the_tree_is_refused_at_its_write(self):
        whole_tree = bundle_hooks.load(HERE, "tools/whole_tree.py")
        # IN THE LAYOUT THE TOOL IS ADAPTED TO. Spelled as `src/m0.py` under `tests/`, this went
        # red in the first project to adapt it - CTRMap's sources are `.java` and its record sits
        # at the top - over code that was right.
        module = "%s/m%%d%s" % (whole_tree.PACKAGE.strip("/"), whole_tree.SOURCE)
        for n in range(6):
            self.write(module % n, "x = %d" % n + LF)
        self.write(whole_tree.BASELINE.replace(os.sep, "/"), json.dumps(
            {"missing_ceiling": {"r.json": 0}, "kind": {"r.json": "covers"}}))
        every = {module % n: 1 for n in range(6)}
        fewer = {module % n: 1 for n in range(5)}
        path = os.path.join(self.tmp, whole_tree.RECORDS, "r.json")
        rel = os.path.relpath(path, self.tmp).replace(os.sep, "/")
        self.assertTrue(re.match(whole_tree.AT_SCOPE, rel),
                        "the record's own write is outside the scope it is asked at")
        for records, where in ((".", "r.json"), ("tests", "tests/r.json")):
            self.assertTrue(re.match(whole_tree.scope(records), where),
                            "a record at %s is outside the scope for %r" % (where, records))
        self.assertTrue(whole_tree.unheld_in(path, fewer, project=self.tmp),
                        "a record that dropped a module was written over its ceiling")
        self.assertEqual(whole_tree.unheld_in(path, every, project=self.tmp), [])

    def test_the_install_is_asked_as_the_write_would_leave_it(self):
        bundle_install = bundle_hooks.load(HERE, "tools/bundle_install.py")
        project = os.path.join(self.tmp, "p")
        with io.open(os.path.join(HOOKS, "dispatch.py"),
                     encoding="utf-8") as handle:
            shipped = handle.read()
        # p has not moved its hooks, so it keeps them where the bundle ships them. And its
        # `_bundle` is the BUNDLE - this folder when the test runs in it, and otherwise the one the
        # project installed from: a project carries no `check_install.py` for p to be checked by.
        mine = self.write("p/" + bundle_hooks.DEFAULT + "/dispatch.py", shipped)
        where = (HERE if os.path.isfile(os.path.join(TOOLS, "check_install.py"))
                 else bundle_install.bundle_path()[0])
        self.assertTrue(where, "where the bundle lives could not be read from this project")
        declared = self.write("p/.claude/bundle-install.json", json.dumps({"_bundle": where}))
        same = bundle_install.problems_with({mine: shipped}, project, declared)
        drifted = bundle_install.problems_with({mine: shipped + LF + "ZZ_DRIFT = 1" + LF},
                                               project, declared)
        self.assertGreater(len(drifted), len(same),
                           "a hook edited out of step with the bundle was not seen at the write")

    def test_the_install_is_asked_as_a_SET_of_writes_would_leave_it(self):
        """`AT_WRITE_SET`: every changed install file at once, None for one that is gone. A
        command that changed many was asked one file at a time - the whole install check twice
        per file, 98 s for one dispatcher call (measured 2026-10-10) - so it is asked once a side,
        and the set must still be read as the command would leave it."""
        from unittest import mock
        bundle_install = bundle_hooks.load(HERE, "tools/bundle_install.py")
        self.assertEqual(bundle_install.AT_WRITE_SET, "worse_with")
        with io.open(os.path.join(HOOKS, "dispatch.py"), encoding="utf-8") as handle:
            shipped = handle.read()
        # A PROJECT OF ITS OWN, as the test above builds one: asked in this folder, the project's
        # copy of a hook and the bundle's are the same file, and a drift served for one is both.
        project = os.path.join(self.tmp, "p")
        rel = bundle_hooks.DEFAULT + "/dispatch.py"
        self.write("p/" + rel, shipped)
        where = (HERE if os.path.isfile(os.path.join(TOOLS, "check_install.py"))
                 else bundle_install.bundle_path()[0])
        declared = self.write("p/.claude/bundle-install.json", json.dumps({"_bundle": where}))
        for name, value in (("ROOT", project), ("DECLARATIONS", declared)):
            patched = mock.patch.object(bundle_install, name, value)
            patched.start()
            self.addCleanup(patched.stop)
        same = bundle_install.worse_with({rel: shipped})
        self.assertGreater(len(bundle_install.worse_with({rel: shipped + LF + "ZZ_DRIFT = 1" + LF})),
                           len(same), "a hook edited out of step was not seen in the set")
        self.assertGreater(len(bundle_install.worse_with({rel: None})), len(same),
                           "a hook the set deletes was read as still there")


class TheBundlesHooksAreFoundNotSpelled(unittest.TestCase):
    """`tools/bundle_hooks.py`. Measured on CTRMap: its own guard stack is in `.claude/hooks`, so
    the bundle's lives beside it, and every tool that spelled `.claude/hooks` asked the PROJECT's
    guards - `tiers.py` imported a `bundle_rules` that was not there, and credited the bundle's
    dispatcher with guards only the project's own dispatcher asks."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="bundle-found-")
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def write(self, rel, text):
        path = os.path.join(self.tmp, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with io.open(path, "w", encoding="utf-8", newline=LF) as handle:
            handle.write(text)
        return path

    def test_the_folder_holding_the_bundles_hooks_is_found(self):
        self.write(".claude/bundle-hooks/bundle_rules.py", "# the mark" + LF)
        self.assertEqual((bundle_hooks.folder(self.tmp), bundle_hooks.hook_folders(self.tmp)),
                         (".claude/bundle-hooks", sorted([".claude/bundle-hooks", bundle_hooks.DEFAULT])),
                         "the bundle's hooks beside a project's own were not found")

    def test_two_copies_of_the_bundles_hooks_are_refused_not_guessed(self):
        self.write(bundle_hooks.DEFAULT + "/bundle_rules.py", "# one" + LF)
        self.write(".claude/bundle-hooks/bundle_rules.py", "# two" + LF)
        with self.assertRaises(ValueError, msg="two copies of the bundle's hooks were guessed at"):
            bundle_hooks.folder(self.tmp)

    def test_a_tree_holding_none_answers_with_THIS_installations_layout(self):
        """A scratch tree a test builds has no hooks yet; it is built in the layout of the project
        the test runs in, so it must be answered in it. Asked with an installation whose layout is
        NOT the default, or the answer would be the default either way."""
        home = os.path.join(self.tmp, "home")
        self.write("home/.claude/moved/bundle_rules.py", "# the mark" + LF)
        os.makedirs(os.path.join(self.tmp, "scratch"))
        held = bundle_hooks.HOME
        bundle_hooks.HOME = home
        try:
            found = bundle_hooks.folder(os.path.join(self.tmp, "scratch"))
        finally:
            bundle_hooks.HOME = held
        self.assertEqual(found, ".claude/moved",
                         "a scratch tree was answered with a layout its installation does not use")

    def test_a_dispatcher_asks_only_the_guards_beside_it(self):
        tiers = bundle_hooks.load(HERE, "tools/tiers.py")
        refusing = "def main():" + LF + '    print("REFUSING: no")' + LF
        self.write(bundle_hooks.DEFAULT + "/guard_mine.py", refusing)
        self.write(".claude/bundle-hooks/bundle_rules.py", "# the mark" + LF)
        self.write(".claude/bundle-hooks/dispatch.py", "# the dispatcher" + LF)
        self.write(".claude/bundle-hooks/guard_theirs.py", refusing)
        self.write(".claude/settings.json", json.dumps({"hooks": {"PreToolUse": [{
            "matcher": "*", "hooks": [{"type": "command", "command":
                                       'python "$CLAUDE_PROJECT_DIR/.claude/bundle-hooks/'
                                       'dispatch.py"'}]}]}}))
        below = tiers.below(self.tmp)
        self.assertEqual(sorted(r for r in below if r.startswith(".claude/")),
                         [bundle_hooks.DEFAULT + "/guard_mine.py"],
                         "a dispatcher was credited with guards in a folder it does not discover")


class EveryRootIsAnchoredNeverCounted(unittest.TestCase):
    """`tools/counted_roots.py`, and the ONE definition it holds the tree to, `_tree_root.py`.

    Measured 2026-10-08 (F216): 72 counted climbs in one project's Python and 31 in the bundle's,
    each right only at the depth it was written for - and eight anchored walks with four anchor
    sets, three of which answered a counted guess when they found none."""

    def setUp(self):
        counted_roots = bundle_hooks.load(HERE, "tools/counted_roots.py")
        self.check = counted_roots

    def test_the_checker_still_sees_every_spelling_it_refuses(self):
        self.assertEqual(self.check.controls(), "", "a known answer did not hold")

    def test_no_file_here_counts_its_root_walks_its_own_or_holds_a_drifted_copy(self):
        files = self.check.python_files(HERE)
        self.assertIsNotNone(files, "git could not list this tree - UNKNOWN, not clean")
        self.assertIn("tools/_tree_root.py", files, "control: the walk does not reach the definition")
        found = self.check.offenders(HERE)
        self.assertEqual(found, [], "a root is counted, walked twice, or a copy drifted: %s" % found)

    def test_a_copy_that_drifts_is_refused_and_the_definition_is_held_to_nothing(self):
        with io.open(self.check.CANONICAL, encoding="utf-8", newline="") as handle:
            text = handle.read()
        self.assertEqual(self.check.findings_in("tests/_tree_root.py", text), [],
                         "an exact copy was refused")
        self.assertTrue(self.check.findings_in("tests/_tree_root.py", text + "#" + LF),
                        "a copy that drifted was not refused")
        self.assertEqual(self.check.findings_in(self.check._canonical_rel(), text + "#" + LF), [],
                         "the definition was held to its own copies, so it could never change")

    def test_the_root_is_the_nearest_anchor_and_no_anchor_is_refused_in_words(self):
        """Asked of a filesystem this test owns outright: under a home folder that holds a `.git`,
        a real temporary folder would be answered by it, and this would fail for a reason that is
        not the code's."""
        import types
        from unittest import mock
        import _tree_root
        anchors = set()
        view = types.SimpleNamespace(path=types.SimpleNamespace(
            exists=lambda path: path in anchors, isfile=lambda path: path in anchors,
            join=os.path.join, dirname=os.path.dirname, abspath=os.path.abspath))
        top = os.path.abspath(os.sep)
        start = os.path.join(top, "p", "a", "b", "x.py")
        with mock.patch.object(_tree_root, "os", view):
            self.assertIsNone(_tree_root.repo_root(start), "a file in no project was given a root")
            with self.assertRaises(LookupError) as refused:
                _tree_root.require(start)
            self.assertIn("sits in no project", str(refused.exception))
            anchors.add(os.path.join(top, "p", ".git"))
            self.assertEqual(_tree_root.repo_root(start), os.path.join(top, "p"),
                             "the repository above was not found")
            anchors.add(os.path.join(top, "p", "a", "CLAUDE.md"))
            self.assertEqual(_tree_root.repo_root(start), os.path.join(top, "p", "a"),
                             "the nearest anchor is not the root")


class AStampThatCannotBeWrittenSaysWhatGitSaid(unittest.TestCase):
    """`gate_stamps.stamp` refused a pathspec commit on CTRMap (2026-09-26) with "git could not say
    the tree or where the repository keeps its state" and nothing else: its git helper threw git's
    own words away, so the refusal could not say WHY and the case stayed unexplained for weeks.
    Reproduced on 2026-10-09 through the real commit-msg chain in a clone, the same pathspec commit
    stamped; whatever made it fail then, the next failure says what git said."""

    def setUp(self):
        self.gate_stamps = bundle_hooks.load(HERE, "tools/gate_stamps.py")
        self.tmp = tempfile.mkdtemp(prefix="stamp-why-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.message = os.path.join(self.tmp, "MSG")
        with io.open(self.message, "w", encoding="utf-8") as handle:
            handle.write("fix: a subject" + LF)

    def stamp(self, root):
        said = io.StringIO()
        held, sys.stderr = sys.stderr, said
        try:
            code = self.gate_stamps.stamp(self.message, root)
        finally:
            sys.stderr = held
        return code, said.getvalue()

    def test_a_stamp_git_cannot_place_says_what_git_said(self):
        nowhere = os.path.join(self.tmp, "not a repository")
        os.makedirs(nowhere)
        env = {k: os.environ.pop(k) for k in list(os.environ) if k.startswith("GIT_")}
        self.addCleanup(os.environ.update, env)
        code, said = self.stamp(nowhere)
        self.assertEqual(code, 1, "a stamp git could not place was written")
        self.assertIn("not a git repository", said.lower(),
                      "the stamp's refusal did not say what git said: %r" % said[:300])

    def test_control_a_stamp_in_a_repository_is_written(self):
        repo = os.path.join(self.tmp, "repo")
        os.makedirs(repo)
        bounded.run(["git", "init", "-q", repo], capture_output=True, timeout=120,
                    creationflags=NO_WINDOW)
        env = {k: os.environ.pop(k) for k in list(os.environ) if k.startswith("GIT_")}
        self.addCleanup(os.environ.update, env)
        code, said = self.stamp(repo)
        self.assertEqual(code, 0, "CONTROL: a stamp in a real repository was refused: %r" % said)


#: Long enough for a child to start its grandchild and say so; short enough that four of them are a minute.
TREE_DEADLINE = 4

#: A child that starts a grandchild, writes the grandchild's pid, then waits half a minute or leaves at
#: once. The grandchild sleeps TWO minutes: longer than any wait here, so one still listed after a wait
#: ends was left running - not one that happened to be finishing on its own.
TREE_CHILD = LF.join([
    "import subprocess, sys, time",
    "g = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(120)'])",
    "with open(sys.argv[1], 'w') as f:",
    "    f.write(str(g.pid))",
    "if sys.argv[2] == 'wait':",
    "    time.sleep(30)",
    "",
])


def _listed(pid):
    """True when `pid` is a live process, asked of THE ONE PROBE, `bundle_shell.present`, which answers ALIVE
    whenever it cannot tell - so a grandchild this cannot see is never read as one that was ended. It asked
    tasklist itself until 2026-10-11: a second spelling of a question with one answer, refused as such by
    CTRMap's liveness checker the day the bundle was installed there."""
    sys.path.insert(0, HOOKS)
    import bundle_shell
    return bundle_shell.present(pid)


def _end(pid):
    """Ends a survivor this test only knows by pid - the control's grandchild."""
    if os.name == "nt":
        bounded._taskkill(pid)
    else:
        try:
            os.kill(pid, 9)
        except OSError:
            pass


class ADeadlineEndsTheWholeTree(unittest.TestCase):
    """`bounded.run` - PAID FOR 2026-10-09 on CTRMap: a test handed `bash -c` a 120-second deadline,
    bash died on it, and the python launcher bash had started and the hook that launcher ran were
    still alive seven hours later. Each check starts a real grandchild, lets a real deadline pass and
    asks the operating system whether the grandchild is still there - after the CONTROL: the same
    deadline taken as `subprocess.run` takes it leaves the grandchild running, so a query that has
    stopped seeing processes cannot pass these."""

    def start(self, mode):
        folder = tempfile.mkdtemp(prefix="bundle-bounded-")
        self.addCleanup(shutil.rmtree, folder, True)
        script = os.path.join(folder, "child.py")
        with io.open(script, "w", encoding="utf-8") as handle:
            handle.write(TREE_CHILD)
        pidfile = os.path.join(folder, "grandchild.pid")
        return [sys.executable, script, pidfile, mode], pidfile

    def grandchild(self, pidfile):
        self.assertTrue(os.path.isfile(pidfile), "the child never wrote its grandchild's pid - the "
                                                 "setup did not run")
        with io.open(pidfile, encoding="utf-8") as handle:
            pid = int(handle.read())
        time.sleep(1)
        up = _listed(pid)
        if up:
            self.addCleanup(_end, pid)
        return pid, up

    def test_the_query_sees_a_live_process_and_not_a_dead_one(self):
        self.assertTrue(_listed(os.getpid()), "the query cannot see this very process")
        self.assertFalse(_listed(999999), "the query lists a pid nothing holds")

    def test_control_a_deadline_that_ends_only_the_child_leaves_the_grandchild_running(self):
        args, pidfile = self.start("wait")
        child = subprocess.Popen(args, creationflags=NO_WINDOW)
        with self.assertRaises(subprocess.TimeoutExpired):
            child.wait(timeout=TREE_DEADLINE)
        child.kill()
        child.wait(timeout=30)
        _pid, up = self.grandchild(pidfile)
        self.assertTrue(up, "CONTROL: the grandchild of a child killed alone was gone - this query "
                            "cannot tell a survivor from a dead process")

    def test_bounded_run_ends_the_grandchild_with_its_parent(self):
        args, pidfile = self.start("wait")
        with self.assertRaises(subprocess.TimeoutExpired) as raised:
            bounded.run(args, timeout=TREE_DEADLINE, creationflags=NO_WINDOW)
        pid, up = self.grandchild(pidfile)
        self.assertFalse(up, "grandchild %d outlived bounded.run's deadline" % pid)
        self.assertIsNone(raised.exception.tree_not_ended, "the tree ended and the exception said "
                                                          "it had not")

    def test_one_whose_parent_left_holding_the_pipe_is_ended_promptly(self):
        args, pidfile = self.start("leave")
        started = time.time()
        with self.assertRaises(subprocess.TimeoutExpired) as raised:
            bounded.run(args, capture_output=True, timeout=TREE_DEADLINE, creationflags=NO_WINDOW)
        took = time.time() - started
        pid, up = self.grandchild(pidfile)
        self.assertFalse(up, "grandchild %d outlived bounded.run's deadline" % pid)
        self.assertLess(took, TREE_DEADLINE + 20, "bounded.run waited %.1f s for a pipe a dead "
                                                  "tree held" % took)
        self.assertIsNone(raised.exception.tree_not_ended, "the tree ended and the exception said "
                                                          "it had not")

    def test_a_tree_it_cannot_end_is_given_up_on_promptly_and_said(self):
        """MEASURED: with the orphan out of reach, leaving `with Popen(...)` closed a pipe a reader
        thread was still blocked on, and on Windows that close waited for the orphan - 120 s, its
        whole life - inside a call that had already given up. Shown here by an end that ends nothing."""
        from unittest import mock
        args, pidfile = self.start("leave")
        started = time.time()
        with mock.patch.object(bounded, "end_tree", return_value="ended nothing, as told"), \
                mock.patch.object(bounded, "KILL_DEADLINE", 3):
            with self.assertRaises(subprocess.TimeoutExpired) as raised:
                bounded.run(args, capture_output=True, timeout=TREE_DEADLINE, creationflags=NO_WINDOW)
        took = time.time() - started
        self.grandchild(pidfile)
        self.assertLess(took, TREE_DEADLINE + 15, "bounded.run waited %.1f s with a tree it could "
                                                  "not end" % took)
        self.assertIn("ended nothing", raised.exception.tree_not_ended or "",
                      "a tree that was not ended was reported as ended")

    def test_a_child_that_finishes_gets_subprocess_runs_answer(self):
        done = bounded.run([sys.executable, "-c", "print('hi')"], capture_output=True, text=True,
                           timeout=60, creationflags=NO_WINDOW)
        self.assertEqual((done.returncode, done.stdout.strip()), (0, "hi"))
        with self.assertRaises(subprocess.CalledProcessError):
            bounded.run([sys.executable, "-c", "raise SystemExit(3)"], check=True, timeout=60,
                        creationflags=NO_WINDOW)


class ADeadlineThatEndsOnlyTheChildIsRefused(unittest.TestCase):
    """`tools/timeouts.py` refuses a deadline handed to one of subprocess's own waits, anywhere in the
    tree; `tools/counted_roots.py` holds every copy of `bounded.py` to the one in tools/."""

    def setUp(self):
        timeouts = bundle_hooks.load(HERE, "tools/timeouts.py")
        counted_roots = bundle_hooks.load(HERE, "tools/counted_roots.py")
        self.timeouts, self.counted = timeouts, counted_roots

    def test_every_spelling_of_a_subprocess_deadline_is_seen(self):
        refused = [
            "import subprocess" + LF + "subprocess.run(['x'], timeout=5)" + LF,
            "import subprocess as sp" + LF + "sp.check_output(['x'], timeout=5)" + LF,
            "from subprocess import call" + LF + "call(['x'], timeout=5)" + LF,
            "from subprocess import run as go" + LF + "go(['x'], timeout=5)" + LF,
        ]
        for source in refused:
            self.assertEqual(self.timeouts.kills_only_the_child(source), [2],
                             "not refused: " + source)
        allowed = [
            "import bounded" + LF + "bounded.run(['x'], timeout=5)" + LF,
            "import subprocess" + LF + "p = subprocess.Popen(['x'])" + LF
            + "p.communicate(timeout=5)" + LF,
            "import subprocess" + LF + "subprocess.run(['x'])" + LF,
        ]
        for source in allowed:
            self.assertEqual(self.timeouts.kills_only_the_child(source), [], "refused: " + source)

    def test_a_bounded_run_with_no_deadline_is_still_unbounded(self):
        for source in ("import bounded" + LF + "bounded.run(['x'])" + LF,
                       "import bounded as b" + LF + "b.run(['x'])" + LF):
            self.assertEqual(self.timeouts.unbounded(source), [2],
                             "a bounded.run with no deadline was not seen: " + source)

    def test_the_tree_walk_sees_an_offender_it_is_shown(self):
        """The control for the walk below: a tree holding one offender and one file that is clean."""
        from unittest import mock
        tmp = tempfile.mkdtemp(prefix="bundle-deadline-tree-")
        self.addCleanup(shutil.rmtree, tmp, True)
        env = {k: os.environ.pop(k) for k in list(os.environ) if k.startswith("GIT_")}
        self.addCleanup(os.environ.update, env)
        bounded.run(["git", "init", "-q", tmp], capture_output=True, timeout=120,
                    creationflags=NO_WINDOW)
        for name, call in (("x.py", "subprocess.run(['x'], timeout=5)"),
                           ("y.py", "subprocess.run(['x'])")):
            with io.open(os.path.join(tmp, name), "w", encoding="utf-8") as handle:
                handle.write("import subprocess" + LF + call + LF)
        with mock.patch.object(self.timeouts, "ROOT", tmp):
            found = self.timeouts.tree_offenders()
        self.assertEqual(found, [("x.py", 2)], "the walk did not see the one offender it was shown, "
                                               "or saw one in a clean file: %s" % found)

    def test_the_refusal_is_asked_of_every_python_file_at_the_write(self):
        said = self.timeouts.findings_in("tests/anything.py", "import subprocess" + LF
                                         + "subprocess.run(['x'], timeout=5)" + LF)
        self.assertEqual(len(said), 1, "a test file handing subprocess a deadline was not refused: "
                                       "%s" % said)
        self.assertIn("bounded.run", said[0])
        said = self.timeouts.findings_in("tests/anything.py", "def (" + LF)
        self.assertEqual(len(said), 1, "a file that does not parse was read as clean: %s" % said)
        self.assertIn("UNKNOWN", said[0])

    def test_nothing_in_this_tree_hands_subprocess_a_deadline(self):
        found = self.timeouts.tree_offenders()
        self.assertIsNotNone(found, "the tree could not be listed, so whether a deadline ends only a "
                                    "child is UNKNOWN")
        self.assertEqual(found, [], "a deadline that ends only the child: %s" % found)

    def test_a_copy_of_bounded_that_drifts_is_refused(self):
        with io.open(os.path.join(TOOLS, "bounded.py"), encoding="utf-8", newline="") as handle:
            text = handle.read()
        self.assertEqual(self.counted.findings_in("tests/bounded.py", text), [],
                         "an exact copy was refused")
        self.assertTrue(self.counted.findings_in("tests/bounded.py", text + "#" + LF),
                        "a copy of bounded.py that drifted was not refused")

    @staticmethod
    def fakes_of_run(source):
        """[module name] of every fake of a module's subprocess.run in SOURCE's CODE - patch.object(m.subprocess,
        "run", ...) or patch("m.subprocess.run", ...) - read from its syntax tree, so a string or a comment
        that spells one is not one. None when SOURCE does not parse."""
        import ast
        try:
            tree = ast.parse(source)
        except SyntaxError:
            return None
        out = []
        for n in ast.walk(tree):
            #THE WHOLE MODULE REPLACED - `m.subprocess = stand_in` - is a fake of every call it makes through it
            if isinstance(n, ast.Assign):
                out += [(t.value.id, "subprocess") for t in n.targets if isinstance(t, ast.Attribute)
                        and t.attr == "subprocess" and isinstance(t.value, ast.Name)]
                continue
            if not isinstance(n, ast.Call) or not n.args:
                continue
            name = getattr(n.func, "attr", None) or getattr(n.func, "id", None)
            first = n.args[0]
            second = n.args[1] if len(n.args) > 1 else None
            if name == "object" and isinstance(first, ast.Attribute) and first.attr == "subprocess" \
                    and isinstance(first.value, ast.Name) and isinstance(second, ast.Constant) \
                    and second.value == "run":
                out.append((first.value.id, "run"))
            elif name in ("object", "setattr") and isinstance(first, ast.Name) \
                    and isinstance(second, ast.Constant) and second.value == "subprocess":
                out.append((first.id, "subprocess"))
            elif name == "patch" and isinstance(first, ast.Constant) and isinstance(first.value, str) \
                    and re.fullmatch(r"\w+\.subprocess(\.run)?", first.value):
                out.append((first.value.split(".")[0], "run" if first.value.endswith(".run") else "subprocess"))
        return out

    @staticmethod
    def calls_subprocess_run(source, starters=("run",)):
        """Does SOURCE call `subprocess.<one of starters>` itself? None when it does not parse."""
        import ast
        try:
            tree = ast.parse(source)
        except SyntaxError:
            return None
        return any(isinstance(n, ast.Call) and getattr(n.func, "attr", None) in starters
                   and getattr(getattr(n.func, "value", None), "id", None) == "subprocess" for n in ast.walk(tree))

    #What a stand-in for a module's WHOLE subprocess could intercept: every way it starts a child
    STARTERS = ("run", "Popen", "call", "check_call", "check_output")

    def test_no_test_fakes_a_subprocess_run_its_code_no_longer_makes(self):
        """A FAKE OF A CALL THE CODE NO LONGER MAKES INTERCEPTS NOTHING. Measured 2026-10-10: when the
        deadline moved onto bounded.run, two tests still faked `subprocess.run` to make git unaskable -
        the fake caught nothing, the real git answered, and one of them shipped red on the bundle's own
        tree. A fake that a test expects to pass through is worse: it passes having tested nothing."""
        names = self.fakes_of_run('mock.patch.object(work_order.subprocess, "run", no_git)' + LF
                                  + 'mock.patch("rules.subprocess.run")' + LF
                                  + 'recorder.subprocess = types.SimpleNamespace(run=f)' + LF
                                  + 'mock.patch.object(gate, "subprocess", fake)' + LF
                                  + 'said = "mock.patch.object(quoted.subprocess, \'run\', x)"  # patch("c.subprocess.run")')
        self.assertEqual(sorted(names), [("gate", "subprocess"), ("recorder", "subprocess"), ("rules", "run"),
                                         ("work_order", "run")],
                         "control: every spelling of the fake is seen - its run, or its whole subprocess - and "
                         "one in a string or a comment is not")
        self.assertTrue(self.calls_subprocess_run("import subprocess" + LF + "subprocess.run(['x'])" + LF)
                        and not self.calls_subprocess_run("import bounded" + LF + "bounded.run(['x'])" + LF),
                        "control: a call of subprocess.run is told from one of bounded.run")
        files = self.counted.python_files(HERE)
        self.assertTrue(files, "the tree could not be listed - UNKNOWN, not clean")
        homes = {}
        for rel in files:
            homes.setdefault(rel.rsplit("/", 1)[-1][:-3], []).append(rel)

        def read(rel):
            with io.open(os.path.join(HERE, rel), encoding="utf-8", errors="replace") as handle:
                return handle.read()
        stale = []
        for rel in files:
            if not rel.rsplit("/", 1)[-1].startswith("test_"):
                continue
            fakes = self.fakes_of_run(read(rel))
            if fakes is None:
                stale.append("%s does not parse, so whether it fakes a call is UNKNOWN" % rel)
                continue
            for name, what in fakes:
                found = homes.get(name, [])
                starters = ("run",) if what == "run" else self.STARTERS
                if not found:
                    stale.append("%s fakes %s's subprocess, and no %s.py is here to fake it in" % (rel, name, name))
                elif not any(self.calls_subprocess_run(read(home), starters) for home in found):
                    stale.append("%s fakes %s's subprocess%s, which %s never starts a child through - fake the "
                                 "call it makes" % (rel, name, ".run" if what == "run" else "", "/".join(found)))
        self.assertEqual(stale, [], "a fake that intercepts nothing: %s" % stale)
