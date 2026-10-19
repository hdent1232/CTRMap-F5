# -*- coding: utf-8 -*-
"""A COMMIT MADE PAST THE GATE IS PAID FOR BY THE NEXT ONE.

On 2026-10-08 the owner allowed one commit past the gate's plant drive, and said: "keep a record
of what we skipped and what needs to be tested next time we commit to running them. The tests are
there for a reason I do not want you making a habit of asking to skip them." The gate drives only
the plants a commit ADDS and the held ones in the files it changes, so the 1117 + 465 plants that
commit added would otherwise never be driven at all. `tools/guard/gate_owed.json` is the record;
`commit_guard.check_gate_owed` refuses the next commit until every one was driven red against the
tree being committed; `tools/guard/drive_owed.py` is the only thing that clears an entry.

RUN IT:   python -B -m unittest test_gate_owed -v
PROVE IT: python -B tools/prove_plants.py --only test_gate_owed
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
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
HERE = _tree_root.require(__file__)
sys.path.insert(0, os.path.join(HERE, "tools"))
sys.path.insert(0, os.path.join(HERE, "tools", "guard"))
import commit_guard  # noqa: E402  - whose rule is asked
import drive_owed  # noqa: E402  - the one thing that clears the record

#: This file drives guards. A project that discovers its guard suites by a marker finds it.
GUARD_SUITE = True

PARENT = {"plants": [{"id": "p1", "suite": "ATest", "needs": []}], "bundle_plants": ["b1"],
          "mutation_sweep_owed": ["src/A.java"]}

#: A drive that plants, says so, and is then held until it is terminated from outside.
TERMINATED_DRIVE = """import io
import sys
import time
sys.path[:0] = [sys.argv[1] + "/tools", sys.argv[1] + "/tools/guard"]
import drive_owed
readings, record = sys.argv[2], sys.argv[3]


def planted_then_held(key):
    with io.open(readings, "w", encoding="utf-8") as handle:
        handle.write("read with a defect planted")
    print("PLANTED", flush=True)
    time.sleep(120)
    return True, "red"


drive_owed.main([], drive_own=planted_then_held, drive_bundle=planted_then_held, digest=lambda: "S",
                stale=lambda files: [], record_path=record, shape=lambda: [], written=[readings])
"""


class AGateSkippedOnceIsOwedAtTheNextCommit(unittest.TestCase):
    """The parent's record owed p1, b1 and a mutation file; the commit is asked what it did about each."""

    def test_every_plant_driven_red_against_THIS_tree_lets_it_land(self):
        staged = {"plants": [], "bundle_plants": [], "mutation_sweep_owed": [],
                  "driven": {"p1": {"red": True, "subject": "S"}, "b1": {"red": True, "subject": "S"}}}
        self.assertEqual(commit_guard.gate_owed_problems(PARENT, staged, "S", []), [],
                         "control: everything driven red against this tree, nothing stale, was refused")

    def test_a_record_still_owing_refuses(self):
        found = commit_guard.gate_owed_problems(PARENT, dict(PARENT), "S", [])
        self.assertTrue(any("still owed" in p for p in found),
                        "a record still owing items was let through: %r" % found)

    def test_a_plant_never_driven_or_not_red_refuses(self):
        staged = {"driven": {"p1": {"red": False, "subject": "S"}}}
        found = commit_guard.gate_owed_problems(PARENT, staged, "S", [])
        self.assertTrue(any("never driven red" in p and "p1" in p and "b1" in p for p in found),
                        "a plant that was not red, or never driven, was let through: %r" % found)

    def test_a_verdict_from_another_tree_clears_nothing(self):
        """The cheapest way past: copy the owed ids into `driven` by hand, or drive them and then
        change the code. Either way the verdict names a tree that is not the one committed."""
        staged = {"driven": {"p1": {"red": True, "subject": "OTHER"}, "b1": {"red": True, "subject": "S"}}}
        found = commit_guard.gate_owed_problems(PARENT, staged, "S", [])
        self.assertTrue(any("another tree" in p and "p1" in p for p in found),
                        "a verdict from another tree cleared an owed plant: %r" % found)

    def test_a_stale_mutation_file_refuses(self):
        staged = {"driven": {"p1": {"red": True, "subject": "S"}, "b1": {"red": True, "subject": "S"}}}
        found = commit_guard.gate_owed_problems(PARENT, staged, "S", ["src/A.java"])
        self.assertTrue(any("mutation sweep" in p for p in found),
                        "a file still owing its mutation sweep was let through: %r" % found)

    def test_a_plant_the_parent_had_retired_is_not_owed(self):
        """Measured 2026-10-09: the record owed 4 plants the ledger of the very commit it was written for had
        retired - nothing can drive a retired plant, so no tree could ever meet the rule."""
        staged = {"plants": [{"id": "p1"}], "driven": {"b1": {"red": True, "subject": "S"}}}
        self.assertEqual(commit_guard.gate_owed_problems(PARENT, staged, "S", [], {"p1"}), [],
                         "a plant the parent had retired is still owed")
        found = commit_guard.gate_owed_problems(PARENT, staged, "S", [], set())
        self.assertTrue(any("p1" in p for p in found),
                        "control: the same plant, not retired, was let through: %r" % found)

    def test_only_a_retirement_the_PARENT_holds_settles_a_plant(self):
        """The cheapest way past would be retiring an owed plant in the commit being made: the retired set is
        read from HEAD's ledger through git, and an entry with no reason retires nothing."""
        head = {"plants": [], "retired": [{"id": "p1", "retired": "the code this claim was about is gone"},
                                         {"id": "p0", "retired": " "}]}
        asked = []
        gate = commit_guard.gate()
        saved = (gate.project_ledger, gate._git, commit_guard._git_out)
        self.addCleanup(lambda: (setattr(gate, "project_ledger", saved[0]), setattr(gate, "_git", saved[1]),
                                 setattr(commit_guard, "_git_out", saved[2])))
        gate.project_ledger = lambda: ("tools/guard/plants.json", "id", ["prove", "{key}"])
        commit_guard._git_out = lambda args: (asked.append(list(args)) or "tools/guard/plants.json\n")

        class Shown(object):
            returncode = 0
            stdout = json.dumps(head).encode("utf-8")

        def git(args, text=True):
            asked.append(list(args))
            return Shown()
        gate._git = git
        self.assertEqual(commit_guard.retired_at_parent(), {"p1"},
                         "the retired set is not HEAD's reasoned retirements: %r" % (asked,))
        shows = [a for a in asked if a[:1] == ["show"]]
        self.assertEqual(shows, [["show", "HEAD:tools/guard/plants.json"]],
                         "the retirement was read from somewhere other than HEAD's ledger: %r" % asked)

    def test_the_rule_is_asked_at_every_commit_and_no_line_excuses_it(self):
        self.assertIn("check_gate_owed", commit_guard.main.__code__.co_names,
                      "main does not ask check_gate_owed, so no commit is held to the record")
        saved = (commit_guard._record_at, commit_guard.source_digest, commit_guard.stale_scores)
        self.addCleanup(lambda: (setattr(commit_guard, "_record_at", saved[0]),
                                 setattr(commit_guard, "source_digest", saved[1]),
                                 setattr(commit_guard, "stale_scores", saved[2])))
        commit_guard._record_at = lambda spec: dict(PARENT) if spec == "HEAD:" else {}
        commit_guard.source_digest = lambda *a, **k: "S"
        commit_guard.stale_scores = lambda files: list(files)
        excuses = "chore: x\n\nMutation-owed: %s\nNo-guard: %s\nDeferred-gap: %s\n" % (("a reason " * 12,) * 3)
        self.assertEqual(commit_guard.check_gate_owed(excuses), 1,
                         "an escape line excused a commit that drove nothing the parent owed")


class DriveOwedClearsOnlyWhatReddened(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="gate-owed-")
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.path = os.path.join(self.dir, "gate_owed.json")
        with io.open(self.path, "w", encoding="utf-8") as handle:
            json.dump({"plants": [{"id": "p1"}, {"id": "p2"}], "bundle_plants": ["b1"],
                       "mutation_sweep_owed": []}, handle)

    def read(self):
        with io.open(self.path, encoding="utf-8") as handle:
            return json.load(handle)

    def test_red_moves_to_driven_with_its_tree_and_not_red_stays_owed(self):
        out = drive_owed.settle({"plants": [{"id": "p1"}, {"id": "p2"}], "bundle_plants": ["b1"]},
                                {"p1": (True, "red"), "p2": (False, "passed"), "b1": (True, "red")}, "S")
        self.assertEqual({k: out["driven"].get("p1", {}).get(k) for k in ("red", "subject")}, {"red": True, "subject": "S"},
                         "a red plant was not recorded red")
        self.assertEqual(out["driven"]["p1"].get("plant"), {"id": "p1"}, "a red was recorded without the plant it was")
        self.assertEqual([p["id"] for p in out["plants"]], ["p2"],
                         "a plant that did not redden was cleared from the record")
        self.assertIn("passed", out["not_red"].get("p2", ""), "what the plant that stayed green said was lost")

    def test_a_plant_the_drive_never_reached_stays_owed(self):
        out = drive_owed.settle({"plants": [{"id": "p1"}, {"id": "p2"}], "bundle_plants": ["b1"]},
                                {"p1": (True, "red")}, "S")
        self.assertEqual(sorted([p["id"] for p in out["plants"]] + out["bundle_plants"]), ["b1", "p2"],
                         "a plant the drive never reached was cleared: %r" % out)

    def test_a_drive_records_what_it_drove(self):
        code = drive_owed.main([], drive_own=lambda k: (k == "p1", "said"), drive_bundle=lambda k: (True, "red"),
                               digest=lambda: "S", stale=lambda files: [], record_path=self.path,
                               shape=lambda: [], written=[])
        rec = self.read()
        self.assertEqual((code, [p["id"] for p in rec["plants"]], sorted(rec["driven"])), (1, ["p2"], ["b1", "p1"]),
                         "the drive did not record what reddened and what is still owed: %r" % rec)

    def test_a_plant_the_parent_retired_is_neither_driven_nor_owed(self):
        driven = []
        code = drive_owed.main([], drive_own=lambda k: (driven.append(k) or True, "red"),
                               drive_bundle=lambda k: (True, "red"), digest=lambda: "S", stale=lambda files: [],
                               record_path=self.path, shape=lambda: [], written=[], retired={"p2"})
        rec = self.read()
        self.assertEqual((code, driven, [p["id"] for p in rec["plants"]], rec.get("retired_at_parent")),
                         (0, ["p1"], [], ["p2"]),
                         "a plant the parent retired was driven, or left owed, or not named: %r" % rec)

    def test_a_tree_that_moved_writes_nothing(self):
        digests = iter(["BEFORE", "AFTER"])
        code = drive_owed.main([], drive_own=lambda k: (True, "red"), drive_bundle=lambda k: (True, "red"),
                               digest=lambda: next(digests), stale=lambda files: [], record_path=self.path,
                               shape=lambda: [], written=[])
        self.assertEqual((code, len(self.read()["plants"])), (2, 2),
                         "verdicts from a tree that moved during the run were written")

    def test_readings_taken_with_a_defect_planted_are_put_back(self):
        """Every suite run during a drive runs over a planted defect, so what it records is no reading of
        the tree; left in, the tree read as moved at the end and every verdict was thrown away."""
        readings = os.path.join(self.dir, "magnitudes.json")
        with io.open(readings, "w", encoding="utf-8") as handle:
            handle.write("before")

        def planted_run(key):
            with io.open(readings, "w", encoding="utf-8") as handle:
                handle.write("read with a defect planted")
            return True, "red"
        code = drive_owed.main([], drive_own=planted_run, drive_bundle=planted_run, digest=lambda: "S",
                               stale=lambda files: [], record_path=self.path, shape=lambda: [], written=[readings])
        with io.open(readings, encoding="utf-8") as handle:
            left = handle.read()
        self.assertEqual((code, left), (0, "before"),
                         "readings taken while a defect was planted were left in: %r" % left)

    @unittest.skipIf(os.name == "nt", "Windows terminates a process without a signal it could catch")
    def test_readings_are_put_back_when_the_drive_is_terminated(self):
        """SIGTERM ends Python without running `finally`. The drive runs in a child process, as the real
        one did, and is terminated from outside while a defect is planted - the way a drive was stopped
        on 2026-10-08, leaving its planted readings in magnitudes.json."""
        readings = os.path.join(self.dir, "magnitudes.json")
        with io.open(readings, "w", encoding="utf-8") as handle:
            handle.write("before")
        script = os.path.join(self.dir, "terminated_drive.py")
        with io.open(script, "w", encoding="utf-8") as handle:
            handle.write(TERMINATED_DRIVE)
        child = subprocess.Popen([sys.executable, "-B", script, HERE, readings, self.path],
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True,
                                 creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        self.addCleanup(child.stdout.close)
        self.addCleanup(lambda: child.poll() is None and child.kill())
        said = []
        for line in child.stdout:
            said.append(line)
            if line.strip() == "PLANTED":
                break
        self.assertEqual(said[-1:], ["PLANTED\n"],
                         "control: the drive never reached its plant, so nothing was terminated: %r" % said)
        child.terminate()
        child.wait(timeout=60)
        with io.open(readings, encoding="utf-8") as handle:
            left = handle.read()
        self.assertEqual(left, "before", "a drive terminated from outside left its planted readings in: %r" % left)

    def test_a_drive_killed_partway_keeps_what_reddened_and_is_run_again_from_there(self):
        """2026-10-11: the app hosting the session quit 154 plants into a 1,578-plant drive, and every
        verdict went with it - the record was written once, at the end. Killed after its second plant
        here, with a checkpoint every plant: the two it drove are kept, and the next run drives only b1."""
        def killed_on(victim):
            def drive(key):
                if key == victim:
                    raise KeyboardInterrupt("the app quit")
                return True, "red"
            return drive
        with self.assertRaises(KeyboardInterrupt):
            drive_owed.main([], drive_own=killed_on("b1"), drive_bundle=killed_on("b1"), digest=lambda: "S",
                            stale=lambda files: [], record_path=self.path, shape=lambda: [], written=[],
                            checkpoint=1)
        rec = self.read()
        self.assertEqual(sorted(rec.get("driven") or {}), ["p1", "p2"],
                         "a drive killed partway lost what it had driven before the kill: %r" % rec)
        again = []
        code = drive_owed.main([], drive_own=lambda k: (again.append(k) or True, "red"),
                               drive_bundle=lambda k: (again.append(k) or True, "red"), digest=lambda: "S",
                               stale=lambda files: [], record_path=self.path, shape=lambda: [], written=[],
                               checkpoint=1)
        self.assertEqual((code, again), (0, ["b1"]), "the run again did not start where the killed one stopped")

    def test_a_tree_that_moves_mid_drive_stops_the_drive_there(self):
        digests = iter(["BEFORE"] + ["AFTER"] * 10)
        driven = []
        code = drive_owed.main([], drive_own=lambda k: (driven.append(k) or True, "red"),
                               drive_bundle=lambda k: (driven.append(k) or True, "red"),
                               digest=lambda: next(digests), stale=lambda files: [], record_path=self.path,
                               shape=lambda: [], written=[], checkpoint=1)
        self.assertEqual((code, driven, len(self.read()["plants"])), (2, ["p1"], 2),
                         "a drive went on over a tree that had moved, or wrote what it drove there")

    def test_a_red_driven_against_another_tree_is_owed_again(self):
        """2026-10-11: a drive resumed after the tree changed carried reds about the old tree out of what it
        would drive and into what the gate then refused. Each goes back as the plant it was; one recorded
        without its plant cannot, and is named rather than dropped."""
        record = {"plants": [], "bundle_plants": [], "driven": {
            "p1": {"red": True, "subject": "OLD", "plant": {"id": "p1", "suite": "S"}},
            "b1": {"red": True, "subject": "OLD", "bundle": True},
            "p2": {"red": True, "subject": "NOW", "plant": {"id": "p2"}},
            "legacy": {"red": True, "subject": "OLD"}}}
        out, again, lost = drive_owed.reowed(record, "NOW")
        self.assertEqual(([p["id"] for p in out["plants"]], out["bundle_plants"], sorted(out["driven"]), again, lost),
                         (["p1"], ["b1"], ["legacy", "p2"], 2, ["legacy"]),
                         "a red about another tree was kept, or one about this tree was owed again: %r" % out)

    def test_a_worker_red_about_another_tree_is_not_taken_back(self):
        results, trouble = {}, []
        theirs = {"driven": {"k1": {"red": True, "subject": "S"}, "k2": {"red": True, "subject": "ELSEWHERE"},
                             "k9": {"red": True, "subject": "S"}}, "not_red": {"k3": "passed"}}
        drive_owed.take_back(0, ["k1", "k2", "k3"], theirs, "S", results, trouble)
        self.assertEqual(sorted(results), ["k1", "k3"], "a worker's red about another tree, or outside its share,"
                                                       " was taken back: %r" % results)
        self.assertTrue(results["k1"][0] and not results["k3"][0] and trouble,
                        "and the red about another tree was not named: %r" % trouble)

    def test_a_worker_copy_digests_to_the_tree_it_copies(self):
        """A tree's subject reads every ancestor's CLAUDE.md, so a copy made where the tree's ancestors are not is
        another subject however exact its files. Measured 2026-10-18: the copies were made in the temp folder, the
        tree's parent held a CLAUDE.md, and all 1,578 verdicts of an eight-worker drive were discarded as "on another
        tree". The copy a worker gets is made beside the tree; the control, the same copy made in the temp folder,
        must be another subject, or this test could not have seen the difference."""
        #git writes its objects read-only, and Windows will not delete one: ignore_errors left every folder behind
        parent = tempfile.mkdtemp(prefix="drive_owed_parent_")
        self.addCleanup(shutil.rmtree, parent, onerror=drive_owed._writable)
        with io.open(os.path.join(parent, "CLAUDE.md"), "w", encoding="utf-8") as handle:
            handle.write("a rule in the load path of the tree below" + chr(10))
        root = os.path.join(parent, "proj")
        os.makedirs(os.path.join(root, "src"))
        with io.open(os.path.join(root, "src", "A.java"), "w", encoding="utf-8") as handle:
            handle.write("class A {}" + chr(10))

        def git(*args):
            return subprocess.run(["git", "-C", root, "-c", "user.name=t", "-c", "user.email=t@example.invalid"]
                                  + list(args), capture_output=True, text=True,
                                  creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        git("init", "-q")
        git("add", "-A")
        git("commit", "-q", "-m", "the tree a drive copies")
        tree = git("write-tree").stdout.strip()
        build = commit_guard.require_build()
        subject = build.subject_digest(root)
        beside = drive_owed._worker_root(0, root)
        self.addCleanup(shutil.rmtree, beside, onerror=drive_owed._writable)
        elsewhere = tempfile.mkdtemp(prefix="drive_owed_elsewhere_")
        self.addCleanup(shutil.rmtree, elsewhere, onerror=drive_owed._writable)
        made = [drive_owed._copy_of(tree, beside, root), drive_owed._copy_of(tree, elsewhere, root)]
        self.assertEqual(made, [None, None], "a copy could not be made: %r" % made)
        self.assertNotEqual(build.subject_digest(elsewhere), subject,
                            "CONTROL: a copy under no such rule file digested to the tree - the difference is unseen")
        self.assertEqual(build.subject_digest(beside), subject,
                         "a worker's copy digests to another tree than the one it copies, so every verdict it"
                         " drives is discarded: made at %s, the tree at %s" % (beside, root))

    def test_a_ledger_the_runner_refuses_drives_nothing(self):
        """One rotted anchor makes the project's runner refuse every id; read one plant at a time,
        that is 1582 plants 'not red' (measured 2026-10-08, the first drive)."""
        driven = []
        code = drive_owed.main([], drive_own=lambda k: driven.append(k) or (False, "refused"),
                               drive_bundle=lambda k: driven.append(k) or (False, "refused"),
                               digest=lambda: "S", stale=lambda files: [], record_path=self.path,
                               shape=lambda: ["p9: its substitution matches 0 time(s)"])
        self.assertEqual((code, driven), (2, []),
                         "a drive went ahead over a ledger its runner refuses wholesale: drove %r" % driven)


if __name__ == "__main__":
    unittest.main()
