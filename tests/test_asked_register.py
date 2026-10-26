"""The owner-message register: every message the owner ever sent this project, accounted for.

THE FAILURE, 2026-09-26. Asked for a complete list of everything asked and not done, the agent
listed its own session ledger - 42 requests since that morning - while nine transcripts held 986
owner messages back to July. `.claude/hooks/asked.py` derives the scope from the transcripts the
harness wrote and refuses a turn while any message in it is unaccounted; these tests hold that
scope, the dispositions, and the closures to what they claim.

Every test builds a scratch project and a scratch transcript folder - nothing here reads the
real transcripts or writes the real register.
"""
import io
import json
import os
import shutil
import sys
import tempfile
import unittest
import unittest.mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _owner_switches  # noqa: E402,F401  - the owner's settings out of this process, before any hook can read one
import _tree_root            # noqa: E402  - the project root, anchored: never counted (F216)
HERE = _tree_root.require(__file__)
sys.path.insert(0, os.path.join(HERE, "tools"))
import bundle_hooks          # noqa: E402  - where this project keeps its hooks
import bounded               # noqa: E402  - a deadline ends the child's whole tree
HOOKS = bundle_hooks.path(HERE)
sys.path.insert(0, HOOKS)
import asked                 # noqa: E402
import guard_asked           # noqa: E402

LF = chr(10)


def user(text, at, cwd):
    return {"type": "user", "timestamp": at, "cwd": cwd,
            "message": {"role": "user", "content": text}}


def reply(text, at):
    return {"type": "assistant", "timestamp": at,
            "message": {"role": "assistant", "content": [{"type": "text", "text": text}]}}


class Scratch(unittest.TestCase):
    """A project root and a harness projects folder, both temporary - the project keeping the register, declared as
    a project switches it on (`_asked`), with a sentence ceiling seeded above anything a test here counts."""

    SEED = 10
    RELEASES = ["2.9.6", "trigger", "decide"]

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="asked-")
        self.root = os.path.join(self.tmp, "work", "Proj")
        os.makedirs(os.path.join(self.root, "docs"))
        os.makedirs(os.path.join(self.root, ".claude"))
        self.declare({"releases": self.RELEASES, "sentences_seeded": self.SEED})
        self.projects = os.path.join(self.tmp, "projects")
        self.home = os.path.join(self.projects, asked.slug(self.root))
        os.makedirs(self.home)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def declare(self, entry, *, text=None):
        """The project's declarations: `_asked` set to `entry`, absent when None, or `text` written as it is."""
        with io.open(os.path.join(self.root, asked.DECLARATIONS), "w", encoding="utf-8") as handle:
            handle.write(text if text is not None else json.dumps({} if entry is None else {"_asked": entry}))

    def transcript(self, name, rows, folder=None):
        path = os.path.join(folder or self.home, name + ".jsonl")
        with io.open(path, "w", encoding="utf-8") as handle:
            for row in rows:
                handle.write(json.dumps(row) + LF)
        return path

    def register(self, **parts):
        held = asked.empty_register()
        held.update(parts)
        asked.save(held, self.root)
        return held

    def found(self, transcript=None, register=None):
        held = register or asked.load(self.root)[0]
        folders, _c, _t = asked.scope(held, self.root, self.projects, transcript)
        return asked.messages(folders, self.root)

    def problems(self, transcript, session="s-now"):
        return asked.problems(self.root, transcript, session, self.projects)


class EveryOwnerMessageInEveryTranscriptOwesADisposition(Scratch):

    def test_a_message_in_ANOTHER_session_is_refused_until_it_has_one(self):
        now = self.transcript("s-now", [user("build the importer", "2026-09-01T10:00:00Z", self.root),
                                        reply("built", "2026-09-01T10:05:00Z")])
        self.transcript("s-old", [user("add a halt banner", "2026-08-01T10:00:00Z", self.root),
                                  reply("noted", "2026-08-01T10:01:00Z")])
        ids = {m["text"]: m["id"] for m in self.found(now, asked.empty_register())}
        self.register(items={"A1": {"ask": "build the importer", "release": "2.9.6",
                                    "status": "open"}},
                      messages={ids["build the importer"]: {"items": ["A1"]}})
        found = self.problems(now)
        self.assertTrue(any("1 owner message(s) have no disposition" in p and "add a halt banner" in p
                            for p in found), found)

    def test_with_every_message_disposed_the_turn_may_end(self):
        now = self.transcript("s-now", [user("build the importer", "2026-09-01T10:00:00Z", self.root),
                                        reply("built", "2026-09-01T10:05:00Z")])
        self.transcript("s-old", [user("what is the plan?", "2026-08-01T10:00:00Z", self.root),
                                  reply("the plan is X", "2026-08-01T10:01:00Z")])
        ids = {m["text"]: m["id"] for m in self.found(now, asked.empty_register())}
        self.register(items={"A1": {"ask": "build the importer", "release": "2.9.6",
                                    "status": "open"}},
                      messages={ids["build the importer"]: {"items": ["A1"]},
                                ids["what is the plan?"]: {"answered": "answered in the next reply"}})
        self.assertEqual(self.problems(now), [])

    def test_ONE_forgotten_message_is_enough_to_refuse(self):
        now = self.transcript("s-now", [user("the ask that was itemised", "2026-09-01T10:00:00Z", self.root),
                                        user("the ask that was forgotten", "2026-09-01T11:00:00Z", self.root)])
        ids = {m["text"]: m["id"] for m in self.found(now, asked.empty_register())}
        self.register(items={"A1": {"ask": "the ask that was itemised", "release": "2.9.6",
                                    "status": "open"}},
                      messages={ids["the ask that was itemised"]: {"items": ["A1"]}})
        found = self.problems(now, session="s-other")
        self.assertTrue(any("the ask that was forgotten" in p for p in found), found)

    def test_deleting_the_register_reopens_every_message_and_frees_none(self):
        now = self.transcript("s-now", [user("one", "2026-09-01T10:00:00Z", self.root),
                                        user("two", "2026-09-01T11:00:00Z", self.root)])
        found = self.problems(now)
        self.assertTrue(any(p.startswith("2 owner message(s) have no disposition") for p in found), found)


class TheScopeIsTheWholeHistoryNotTheCurrentSession(Scratch):
    """EVASION: the cheapest way past a whole-history rule is to read only the session in hand -
    exactly what the failure it exists for did."""

    def test_asked_with_ONE_transcript_it_still_reads_every_transcript_in_the_folder(self):
        now = self.transcript("s-now", [user("today's ask", "2026-09-26T10:00:00Z", self.root)])
        self.transcript("s-july", [user("a July ask nobody itemised", "2026-07-06T00:25:00Z",
                                        self.root)])
        texts = sorted(m["text"] for m in self.found(now, asked.empty_register()))
        self.assertEqual(texts, ["a July ask nobody itemised", "today's ask"])


class AFormerLocationOfTheProjectIsInScopeOnceClassified(Scratch):

    def setUp(self):
        super().setUp()
        self.old_root = os.path.join(self.tmp, "work", "OldName")
        self.old_home = os.path.join(self.projects, asked.slug(self.old_root))
        os.makedirs(self.old_home)
        self.transcript("s-old", [user("the oldest ask", "2026-07-01T00:00:00Z", self.old_root)],
                        folder=self.old_home)
        self.now = self.transcript("s-now", [
            user("cd back to the old name", "2026-07-09T00:00:00Z", self.old_root),
            user("today", "2026-09-26T00:00:00Z", self.root)])

    def test_an_unclassified_former_folder_is_refused_not_left_out(self):
        found = self.problems(self.now)
        self.assertTrue(any("classified nowhere" in p and os.path.basename(self.old_home) in p
                            for p in found), found)

    def test_included_its_messages_are_counted_and_owed(self):
        held = self.register(folders={"include": {os.path.basename(self.old_home):
                                                  "the project lived here before its rename"},
                                      "exclude": {}})
        texts = sorted(m["text"] for m in self.found(self.now, held))
        self.assertIn("the oldest ask", texts)

    def test_excluded_with_a_reason_it_is_not_read(self):
        held = self.register(folders={"include": {}, "exclude": {os.path.basename(self.old_home):
                                                                 "a different project that shared a folder once"}})
        texts = sorted(m["text"] for m in self.found(self.now, held))
        self.assertNotIn("the oldest ask", texts)


class AWorktreeSessionStillReadsTheRootsOwnHistory(Scratch):
    """A session in a linked worktree under the root writes its transcript to the WORKTREE's own
    folder. Read from there alone, the register saw one session: on 2026-10-06 ten superseded items,
    each citing the owner's message from the root's history, read as citing nothing and refused
    every turn end. The root's own folder is in scope wherever the current transcript sits."""

    def setUp(self):
        super().setUp()
        self.wt = os.path.join(self.root, ".claude", "worktrees", "w")
        os.makedirs(self.wt)
        self.wt_home = os.path.join(self.projects, asked.slug(self.wt))
        os.makedirs(self.wt_home)
        self.transcript("s-july", [user("drop the July page", "2026-07-01T00:00:00Z", self.root),
                                   reply("noted", "2026-07-01T00:01:00Z")])
        self.now = self.transcript("s-now", [user("today, from the worktree", "2026-10-06T00:00:00Z",
                                                  self.wt)], folder=self.wt_home)

    def test_the_roots_own_messages_are_read_from_a_worktree(self):
        texts = sorted(m["text"] for m in self.found(self.now, asked.empty_register()))
        self.assertIn("drop the July page", texts,
                      "a worktree session read only its own folder, not the root's history")

    def test_an_item_citing_the_roots_history_stands_from_a_worktree(self):
        ids = {m["text"]: m["id"] for m in self.found(self.now, asked.empty_register())}
        july = ids.get("drop the July page")
        self.assertIsNotNone(july, "the root's message was not read at all")
        self.register(items={"A1": {"ask": "drop the July page", "release": "2.9.6",
                                    "status": "superseded",
                                    "said": "the owner replaced it with the new page - " + july}},
                      messages={july: {"items": ["A1"]},
                                ids["today, from the worktree"]: {
                                    "context": "the worktree session this test runs from, only so it has a transcript"}})
        self.assertEqual(self.problems(self.now, session="s-now"), [])

    def test_a_worktree_a_session_made_itself_reads_the_main_checkouts_transcripts_and_ledgers(self):
        """The other direction (2026-10-15). A session started in the main checkout that makes a worktree itself keeps
        writing its transcript under the MAIN checkout's folder, and its ledger in the main checkout's store; the
        worktree's own folder never exists. Committing from that worktree, the coverage check looked for the
        worktree's folder and refused as "cannot look" - and once it could look, read every ledger-held message as
        held by nothing, because it read only the worktree's store. A real linked worktree, made by git."""
        import subprocess

        def git(*args):
            return bounded.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", *args], capture_output=True,
                               text=True, timeout=60, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        made = os.path.join(self.tmp, "made")
        for args in (("-C", self.root, "init", "-q"), ("-C", self.root, "commit", "-q", "--allow-empty", "-m", "x"),
                     ("-C", self.root, "worktree", "add", "-q", "-b", "made", made)):
            self.assertEqual(git(*args).returncode, 0, "the scratch repository could not be built: %s" % (args,))
        self.assertFalse(os.path.isdir(os.path.join(self.projects, asked.slug(made))),
                         "the control: the worktree has no transcript folder of its own")
        folders, _candidates, trouble = asked.scope(asked.empty_register(), made, self.projects, None)
        self.assertIsNone(trouble, trouble)
        self.assertIn(os.path.normcase(self.home), [os.path.normcase(f) for f in folders or []],
                      "a worktree a session made itself read no transcripts")
        store = os.path.join(self.root, asked.request_ledger.STORE)
        os.makedirs(store, exist_ok=True)
        with io.open(os.path.join(store, "s-july.json"), "w", encoding="utf-8") as handle:
            json.dump({"since": "k1", "items": [{"request": "k1", "ask": "drop the July page"}]}, handle)
        self.assertIn("s-july", asked.ledger_held(made), "the main checkout's session ledgers were not read")


class OneMessageIsOneMessageHoweverManyRowsCarryIt(Scratch):

    def test_a_fork_and_a_double_delivery_merge_and_a_later_repeat_does_not(self):
        now = self.transcript("s-now", [user("do the thing", "2026-09-01T10:00:00Z", self.root),
                                        user("do the thing", "2026-09-01T10:00:40Z", self.root),
                                        user("do the thing", "2026-09-03T09:00:00Z", self.root)])
        self.transcript("s-fork", [user("do the thing", "2026-09-01T10:00:00Z", self.root)])
        found = self.found(now, asked.empty_register())
        self.assertEqual(len(found), 2, [(m["at"], m["rows"]) for m in found])


class AClosureIsOnlyWhatAReaderCanCheck(Scratch):

    def setUp(self):
        super().setUp()
        self.now = self.transcript("s-now", [user("drop the old page", "2026-09-01T10:00:00Z", self.root),
                                             user("is it done?", "2026-09-01T10:01:00Z", self.root),
                                             reply("yes", "2026-09-01T10:02:00Z")])
        self.ids = {m["text"]: m["id"] for m in self.found(self.now, asked.empty_register())}

    def test_done_with_nothing_checkable_is_refused(self):
        why = asked.item_problems({"items": {"A1": {"ask": "drop the old page", "release": "2.9.6",
                                                    "status": "done", "said": "done it, trust me, really"}}},
                                  set(self.ids.values()), self.root)
        self.assertTrue(why and "nothing checkable" in why[0], why)

    def test_done_naming_a_path_that_exists_stands(self):
        why = asked.item_problems({"items": {"A1": {"ask": "drop the old page", "release": "2.9.6",
                                                    "status": "done", "said": "the old page is removed; see `docs`"}}},
                                  set(self.ids.values()), self.root)
        self.assertEqual(why, [])

    def test_dropped_must_name_the_OWNERS_message_that_says_so(self):
        said = "the agent decided this no longer matters, after careful and lengthy consideration"
        why = asked.item_problems({"items": {"A1": {"ask": "drop the old page", "release": "2.9.6",
                                                    "status": "dropped", "said": said}}},
                                  set(self.ids.values()), self.root)
        self.assertTrue(why and "only the owner drops" in why[0], why)
        cited = said + " - " + self.ids["is it done?"]
        self.assertEqual(asked.item_problems(
            {"items": {"A1": {"ask": "drop the old page", "release": "2.9.6", "status": "dropped",
                              "said": cited}}}, set(self.ids.values()), self.root), [])

    def test_a_question_is_answered_only_by_a_reply_BEFORE_the_owner_spoke_again(self):
        self.register(messages={self.ids["drop the old page"]: {"answered": "answered in a reply below"},
                                self.ids["is it done?"]: {"answered": "answered in the reply below"}})
        found = self.problems(self.now)
        self.assertTrue(any(self.ids["drop the old page"] in p and "no reply follows it" in p
                            for p in found), found)
        self.assertFalse(any(self.ids["is it done?"] in p for p in found), found)


class ALedgerHeldMessageIsNotRefusedTwice(Scratch):

    def test_the_current_sessions_ledger_scope_is_the_ledgers_to_hold(self):
        now = self.transcript("s-now", [user("early ask", "2026-09-26T08:00:00Z", self.root),
                                        user("ledger-era ask", "2026-09-26T10:00:00Z", self.root)])
        keys = [m["key"] for m in asked.transcript_facts(now, self.root)["messages"]]
        store = os.path.join(self.root, ".claude", "requests")
        os.makedirs(store)
        with io.open(os.path.join(store, "s-now.json"), "w", encoding="utf-8") as handle:
            json.dump({"since": keys[1], "items": []}, handle)
        found = self.problems(now, session="s-now")
        self.assertTrue(any(p.startswith("1 owner message(s)") and "early ask" in p for p in found),
                        found)


class EverySentenceIsCoveredNotOnlyTheMessage(Scratch):
    """F224, 2026-10-15. A message is disposed of whole, and one linked to an item can hold a second ask
    in a later sentence: 2,840 sentences of 515 disposed messages shared no word with their disposition.
    A disposition covers the sentences it shares a word with; the rest carry their own."""

    IMPORTER = "Build the importer. Also paint the halt banner red."
    PLAN = "What is the plan? And add a log line to the sweep."

    def setUp(self):
        super().setUp()
        self.now = self.transcript("s-now", [user(self.IMPORTER, "2026-09-01T10:00:00Z", self.root),
                                             user(self.PLAN, "2026-09-01T11:00:00Z", self.root),
                                             reply("the plan is X", "2026-09-01T11:05:00Z")])
        self.known = {m["id"]: m for m in self.found(self.now, asked.empty_register())}
        self.ids = {m["text"]: i for i, m in self.known.items()}
        self.items = {"A1": {"ask": "build the importer", "release": "2.9.6", "status": "open"},
                      "A2": {"ask": "paint the halt banner red", "release": "2.9.6", "status": "open"}}

    def held(self, importer=None, plan=None, ceiling=0):
        importer = dict({"items": ["A1"]}, **(importer or {}))
        plan = dict({"answered": "the plan was given in the reply"}, **(plan or {}))
        return self.register(items=self.items, sentence_ceiling=ceiling,
                             messages={self.ids[self.IMPORTER]: importer, self.ids[self.PLAN]: plan})

    def refusals(self):
        return [p for p in self.problems(self.now) if "covered by no disposition" in p]

    def test_a_second_ask_inside_a_disposed_message_is_refused_over_the_ceiling(self):
        self.held()
        found = self.refusals()
        self.assertTrue(found and found[0].startswith("2 sentence(s)") and "paint the halt banner" in found[0],
                        "a second ask inside a disposed message was not refused: %r" % found)

    def test_a_sentence_given_an_item_that_names_it_or_a_reason_is_covered(self):
        self.held(importer={"sentences": {"2": {"items": ["A2"]}}},
                  plan={"sentences": {"2": {"context": "the sweep's log line is part of the runner item"}}})
        self.assertEqual(self.refusals(), [])

    def test_an_item_that_does_not_name_the_sentence_covers_nothing(self):
        """EVASION: link every leftover sentence to the nearest item and the count reaches zero."""
        self.held(importer={"sentences": {"2": {"items": ["A1"]}}},
                  plan={"sentences": {"2": {"context": "the sweep's log line is part of the runner item"}}})
        found = self.refusals()
        self.assertTrue(found and "paint the halt banner" in found[0],
                        "an item that names nothing in a sentence was taken as covering it")
        why = asked.apply(asked.load(self.root)[0], "sentence",
                          [self.ids[self.IMPORTER], "2", "items", "A1"], self.known, self.root)
        self.assertIn("shares no word", why)

    def test_an_answer_covers_only_the_sentences_it_names(self):
        """ELSEWHERE: the same hiding place in a message disposed of as answered, not linked."""
        self.held(importer={"sentences": {"2": {"items": ["A2"]}}})
        found = self.refusals()
        self.assertTrue(found and "add a log line" in found[0],
                        "a message-level answer was taken as covering every sentence in it")

    def test_a_plural_names_what_its_singular_names(self):
        """The first live message read under this rule: `filter options`, in a message linked to an item
        asking for `filters`, read as uncovered - and the right item would have been refused for it."""
        self.items["A2"]["ask"] = "add the banners"     # its one content word is the plural
        self.held(importer={"sentences": {"2": {"items": ["A2"]}}},
                  plan={"sentences": {"2": {"context": "the sweep's log line is part of the runner item"}}})
        self.assertEqual(self.refusals(), [], "a plural did not name what its singular names")

    def test_the_ceiling_never_passes_the_seed_and_a_verb_records_a_fall(self):
        register = self.held(ceiling=self.SEED + 5000)
        self.assertEqual(asked.sentence_ceiling(register, self.root), self.SEED,
                         "a ceiling recorded above the declared seed raised the bar")
        asked.tighten(register, list(self.known.values()), self.root)
        self.assertEqual(register["sentence_ceiling"], 2)

    def test_disposing_of_a_message_again_keeps_what_its_sentences_were_given(self):
        register = self.held(importer={"sentences": {"2": {"items": ["A2"]}}})
        self.assertEqual(asked.apply(register, "link", [self.ids[self.IMPORTER], "A1"], self.known, self.root), "")
        self.assertEqual(register["messages"][self.ids[self.IMPORTER]].get("sentences"), {"2": {"items": ["A2"]}},
                         "a message disposed of again lost what its sentences were given")


class TheRegisterIsOffUntilTheProjectDeclaresIt(Scratch):
    """F199, 2026-10-17: the bundle carries the register, and a project switches it on - `_asked` in its declarations,
    which only the owner's typed code may add (`guard_owner_files.py`). A project with years of owner messages and
    none disposed of would otherwise be locked out of every turn the moment the bundle arrived."""

    def setUp(self):
        super().setUp()
        self.now = self.transcript("s-now", [user("build the importer", "2026-09-01T10:00:00Z", self.root)])

    def test_declared_it_refuses_an_owing_message(self):
        """The control: the same project, switched on, owes a disposition for the message."""
        found = self.problems(self.now)
        self.assertTrue(any("1 owner message(s)" in p for p in found), found)

    def test_undeclared_it_asks_nothing_and_says_it_is_off(self):
        self.declare(None)
        self.assertEqual(self.problems(self.now), [])
        out = io.StringIO()
        with unittest.mock.patch("sys.stdout", out):
            self.assertEqual(asked.main(["asked.py"], self.root), 0)
            self.assertEqual(asked.main(["asked.py", "pending"], self.root), 2,
                             "a verb ran against a register this project does not keep")
        self.assertIn("not switched on", out.getvalue())
        self.assertNotIn("accounted for", out.getvalue(), "off was answered as every message accounted for")

    def test_declarations_that_cannot_be_read_are_trouble_never_off(self):
        """EVASION: a stray comma in the declarations must not switch the register off with nothing said."""
        self.declare(None, text="{not json")
        found = self.problems(self.now)
        self.assertEqual(len(found), 1)
        self.assertIn("UNKNOWN", found[0])

    def test_a_declaration_of_the_wrong_shape_is_trouble(self):
        for entry in ({"releases": "2.9.6"}, {"releases": []}, {"sentences_seeded": -1}, {"sentences_seeded": True},
                      "on"):
            with self.subTest(entry=entry):
                self.declare(entry)
                found = self.problems(self.now)
                self.assertTrue(found and "it must be" in found[0], found)

    def test_an_item_names_one_of_the_declared_releases(self):
        known = {m["id"] for m in self.found(self.now, asked.empty_register())}
        ok = {"items": {"A1": {"ask": "build the importer", "release": "2.9.6", "status": "open"}}}
        late = {"items": {"A1": {"ask": "build the importer", "release": "3.0.0", "status": "open"}}}
        self.assertEqual(asked.item_problems(ok, known, self.root), [])
        self.assertTrue(any("names no release" in p for p in asked.item_problems(late, known, self.root)))

    def test_the_guard_is_silent_where_the_register_is_off_and_refuses_where_it_is_on(self):
        """And it reads its payload through `bundle_shell.payload_text`, the one door in: with stdin empty and the
        door handing it a Write into the register, only a guard that asks the door refuses."""
        payload = json.dumps({"tool_name": "Write", "tool_input": {"file_path": "docs/asked.json", "content": "{}"}})
        saved = asked.ROOT
        self.addCleanup(setattr, asked, "ROOT", saved)
        asked.ROOT = self.root
        for declared, refused in ((True, True), (False, False)):
            with self.subTest(declared=declared):
                self.declare({"releases": self.RELEASES} if declared else None)
                out = io.StringIO()
                with unittest.mock.patch.object(guard_asked.bundle_shell, "payload_text", lambda *a: payload), \
                        unittest.mock.patch("sys.stdin", io.StringIO("")), unittest.mock.patch("sys.stdout", out):
                    self.assertEqual(guard_asked.main(), 0)
                self.assertEqual("permissionDecision" in out.getvalue(), refused, out.getvalue())


class TheRegisterIsWrittenOnlyThroughItsVerbs(unittest.TestCase):

    def test_a_write_naming_the_register_is_refused(self):
        payload = {"tool_name": "Write", "tool_input": {"file_path": "docs/asked.json", "content": "{}"}}
        self.assertIn("writes", guard_asked.door(payload))

    def test_a_command_rewriting_it_is_refused_and_the_verbs_are_not(self):
        bad = {"tool_name": "Bash", "tool_input": {"command": "echo {} > docs/asked.json"}}
        good = {"tool_name": "Bash", "tool_input": {"command": "python -B %s/asked.py pending" % asked.FOLDER}}
        self.assertTrue(guard_asked.door(bad))
        self.assertEqual(guard_asked.door(good), "")

    def test_git_may_stage_commit_and_restore_the_tracked_register(self):
        for command in ("git add docs/asked.json", "git checkout -- docs/asked.json",
                        "git diff docs/asked.json"):
            payload = {"tool_name": "Bash", "tool_input": {"command": command}}
            self.assertEqual(guard_asked.door(payload), "", command)

    def test_the_door_asks_each_command_and_a_way_out_in_front_excuses_nothing(self):
        chained = {"tool_name": "Bash", "tool_input": {
            "command": "cd repo && python -m unittest x && git add docs/asked.json"}}
        smuggled = {"tool_name": "Bash", "tool_input": {
            "command": "git add docs/asked.json && echo {} > docs/asked.json"}}
        self.assertEqual(guard_asked.door(chained), "")
        self.assertIn("echo", guard_asked.door(smuggled))


if __name__ == "__main__":
    unittest.main()
