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
import unittest

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(HERE, "tools")
sys.path.insert(0, TOOLS)
import bundle_hooks          # noqa: E402  - where this project keeps the bundle's hooks
HOOKS = bundle_hooks.path(HERE)
OURS = bundle_hooks.folder(HERE)

LF = chr(10)
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

#: This file drives guards. A project that discovers its guard suites by a marker finds it.
GUARD_SUITE = True


class ACommitIsAskedWhatSections2And14Ask(unittest.TestCase):
    """`tools/commit_gate.py` - the refusals README describes for a commit, as code. The project
    this came from built each one after paying for its absence; the next project read the prose
    and started again. Every check is driven both ways."""

    FIX = "fix(reader): the empty file" + LF + LF

    def setUp(self):
        import commit_gate
        self.gate = commit_gate

    def test_a_fix_that_touches_NO_guard_is_refused_and_one_that_does_is_not(self):
        self.assertTrue(self.gate.fix_touches_a_guard(self.FIX, ["src/reader.py"]))
        self.assertEqual(self.gate.fix_touches_a_guard(self.FIX, ["src/reader.py",
                                                                  "tests/test_reader.py"]), [])
        self.assertEqual(self.gate.fix_touches_a_guard(
            self.FIX + "No-guard: a typo in a message string" + LF, ["src/reader.py"]), [])

    def test_git_that_cannot_ANSWER_is_not_an_empty_commit(self):
        self.assertTrue(self.gate.fix_touches_a_guard(self.FIX, None))

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

    def test_a_QUOTED_count_is_a_mention_and_a_bare_one_is_a_claim(self):
        self.assertEqual(self.gate.test_claims("as the doc says, `green at 6,105 tests`"), [])
        self.assertEqual(self.gate.test_claims("the suite: 6,349 tests, green"), [6349])

    def test_an_UNKNOWN_subject_refuses_a_count(self):
        """The shipped defect on the project this came from: git could not be asked, the
        subject was unknown, and the gate passed on the half that never asks what was measured."""
        import require_build
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
        self.assertTrue(self.gate.fix_has_a_plant(self.FIX, keys=[]))
        self.assertTrue(self.gate.fix_has_a_plant(self.FIX, keys=["k"],
                                                  drive=lambda k: (False, "passed")))
        self.assertEqual(self.gate.fix_has_a_plant(self.FIX, keys=["k"],
                                                   drive=lambda k: (True, "red")), [])


class AHeldPlantIsDrivenAgainWhenItsFileChanges(unittest.TestCase):
    """A plant is watched red on the day it is recorded, and nothing drove it again. Measured on
    the project this came from: 10 of 729 held plants no longer reddened, one emptied the same
    morning by the next edit to its own file, and seven of the ten by a change to the plant's own
    file. The commit that makes the change is where it is asked."""

    HELD = {"test_x.py::C::test_t": {"file": "src/a.py"},
            "test_y.py::D::test_u": {"file": "src/b.py"}}
    TRAILER = LF + LF + "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" + LF

    def setUp(self):
        import commit_gate
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
        """CTRMap proves its Java tests in its own ledger - 221 plants - and each names the file
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


class EveryGateSubprocessIsBOUNDED(unittest.TestCase):
    """README section 16: thirteen and a half hours on 0.14 seconds of CPU."""

    def test_the_checker_finds_an_unbounded_call_and_passes_a_bounded_one(self):
        import timeouts
        control = ("import subprocess" + LF + "subprocess.run(['a'], timeout=5)" + LF
                   + "subprocess.run(['b'])" + LF + "p.communicate()" + LF)
        self.assertEqual(timeouts.unbounded(control), [3, 4])

    def test_the_hooks_here_are_derived_and_bounded(self):
        import timeouts
        found = timeouts.gates()
        self.assertIn(OURS + "/dispatch.py", found)
        self.assertEqual([o for o in timeouts.offenders() if o[0].startswith(OURS + "/")],
                         [], "a hook starts a child it cannot stop waiting for")


class TheInstallIsAskedAtEveryCommit(unittest.TestCase):
    """`tools/bundle_install.py` - the commit's copy of the question `check_install.py` asks."""

    def setUp(self):
        import bundle_install
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
        import bundle_install
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


class ACommitTheGateNeverSawIsFoundAndRefused(unittest.TestCase):
    """`gate_stamps.py`: a hook reads the call, and a program's `git commit --no-verify` is not in
    the call. Measured 2026-09-23: fifteen spellings of a skipped gate are refused before git
    starts, and `python -c "subprocess.run(['git', 'commit', '--no-verify'])"` is the one no hook
    can see. The gate stamps what it passed; a commit without a stamp is found the moment after."""

    def setUp(self):
        import gate_stamps
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
        self.git("config", "core.hooksPath", ".githooks")

    def git(self, *args, **extra):
        env = dict(self.env, **extra)
        done = subprocess.run(["git"] + list(args), cwd=self.root, capture_output=True,
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
        done = subprocess.run(["git", "commit", "-q", "--allow-empty", "--no-verify", "-m",
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
        write("forger.py", "open('.git/gate-stamps', 'a').write('tree digest')")
        for command in ("python -m ruff check names_it.py",
                        "python -B -m py_compile names_it.py",
                        "py -3 -mpyflakes names_it.py"):
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
        import tiers
        sys.path.insert(0, HOOKS)
        import bundle_rules
        gate = set()
        for name in sorted(os.listdir(os.path.join(HERE, ".githooks"))):
            gate |= tiers.gate_named(tiers.read(HERE, os.path.join(".githooks", name)))
        loose = [rel for rel, text in tiers.guards(HERE)
                 if rel.startswith("tools/") and rel not in gate
                 and not bundle_rules.declarations_in(text)]
        self.assertEqual(loose, [], "these tools refuse things and declare no act to be asked at")

    def test_tiers_refuses_a_guard_nothing_asks_and_credits_one_a_wired_hook_asks(self):
        import tiers
        self.write(".claude/settings.json", '{"command": "python %s/dispatch.py"}' % OURS)
        self.write(OURS + "/guard_write_rules.py", "# present" + LF)
        self.write("CLAUDE.md", "# scratch" + LF)
        self.write("tools/declared.py", 'AT_WRITE = "f"' + LF + LF + LF
                   + "def f(rel, text):" + LF + '    return ["REFUSING"]' + LF)
        self.write("tools/bare.py", 'print("REFUSING: something")' + LF)
        self.assertEqual(tiers.below(self.tmp), ["tools/bare.py"],
                         "the derivation did not tell a declared rule from a detector")

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
        import tiers
        self.machinery_tree()
        self.assertEqual([r for r in tiers.below(self.tmp) if r.startswith(OURS + "/")],
                         [OURS + "/loose_y.py"],
                         "a module a dispatched guard imports was refused as nobody's")

    def test_the_write_time_judge_derives_the_same_machinery(self):
        import tiers
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
        import tiers
        self.assertEqual(tiers.excusals(), [])
        held = dict(tiers.NOT_GUARDS)
        self.addCleanup(setattr, tiers, "NOT_GUARDS", held)
        tiers.NOT_GUARDS = dict(held, anything="short")
        self.assertTrue(tiers.excusals(), "an exclusion with a label for a reason was accepted")

    def test_the_commit_gate_answers_a_message_before_git_starts(self):
        import commit_gate
        self.assertTrue(commit_gate.message_problems("docs: a note" + LF),
                        "a message with no co-author trailer passed the command-time check")
        self.assertEqual(commit_gate.message_problems(
            "docs: a note" + LF + LF + "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
            + LF), [])

    def test_a_gate_file_gaining_an_unbounded_child_is_refused_at_the_write(self):
        import timeouts
        source = "import subprocess" + LF + 'subprocess.run(["x"])' + LF
        self.assertTrue(timeouts.findings_in(OURS + "/dispatch.py", source),
                        "an unbounded child in a file every tool call runs was let in")
        self.assertEqual(timeouts.findings_in("src/not_a_gate.py", source), [])

    def test_a_record_covering_LESS_of_the_tree_is_refused_at_its_write(self):
        import whole_tree
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
        import bundle_install
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
        import tiers
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
