# -*- coding: utf-8 -*-
"""THE HOOKS ARE THE HIGHEST-RISK LOGIC IN THIS BUNDLE AND NOTHING TESTED THEM.

An outside reviewer put it plainly: *the bundle has no test suite for the hooks, despite the
hooks being the highest-risk logic here.* That was true, and this folder spends fifteen sections
saying a guard nobody has watched fail is a comment with an assert in it.

THIS IS THE HALF A PROJECT INSTALLS. It is about the hooks and the tools a project carries, and
it runs against whatever `.claude/hooks/` and `tools/` sit beside it - the bundle's own copies
here, the project's copies there. The folder's self-check is `tests/test_bundle_self.py`.

WHAT THIS SUITE IS FOR, and it is not coverage:

  * every hook IMPORTS without doing anything - derived from the AST
  * every hook REFUSES its case and ALLOWS its opposite - a guard that refuses everything is
    uninstalled within the day, and one that refuses nothing is decoration
  * the environment names come from ONE place - they shipped with two prefixes at once
  * a digest root that is absent or empty REFUSES rather than hashing to the empty digest
  * a call is judged by its SHAPE - a command, a path, a write, an agent launch - in every
    spelling the session's shells use, never by what the tool is called

RUN IT:   python -B -m unittest discover -s tests -v
PROVE IT: python -B tools/prove_plants.py        - every test above, watched failing
"""
import ast
import glob
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(HERE, "tools"))
import bundle_hooks          # noqa: E402  - where this project keeps the bundle's hooks
HOOKS = bundle_hooks.path(HERE)
TOOLS = os.path.join(HERE, "tools")
sys.path.insert(0, HOOKS)
sys.path.insert(0, TOOLS)

LF = chr(10)

#: No console window for a child whose output is captured - on Windows every one opens its own,
#: and the project this was first installed into refuses a suite that blinks them.
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

#: This file drives guards. A project that discovers its guard suites by a marker finds it.
GUARD_SUITE = True


def hook_files():
    """Every hook, DERIVED from the folder. A roster written by hand is the class whose whole
    job is catching a guard that was written and never wired."""
    return sorted(os.path.basename(p) for p in glob.glob(os.path.join(HOOKS, "guard_*.py")))


def parsed(path):
    return ast.parse(io.open(path, encoding="utf-8").read(), path)


def _dispatch():
    import importlib.util
    spec = importlib.util.spec_from_file_location("bundle_dispatch_under_test",
                                                  os.path.join(HOOKS, "dispatch.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class EveryHookMustBeIMPORTABLE(unittest.TestCase):
    """A hook that acts when you import it is a hook no test can reach.

    MEASURED: `guard_fanout.py` ended with a bare `main()`. Importing it consumed stdin, hit a
    `JSONDecodeError`, and called `sys.exit(0)` - so the importing process died silently with a
    success code."""

    def test_no_hook_calls_main_at_module_level(self):
        """ASKED OF THE AST, NOT THE TEXT. `grep -q "__name__"` passed this file the whole time
        it was broken, because `type(exc).__name__` appears in an unrelated message."""
        offenders = []
        for name in hook_files():
            tree = parsed(os.path.join(HOOKS, name))
            for node in tree.body:
                if (isinstance(node, ast.Expr) and isinstance(node.value, ast.Call)
                        and getattr(node.value.func, "id", None) == "main"):
                    offenders.append("%s:%d" % (name, node.lineno))
        self.assertEqual(offenders, [],
                         "these run main() on import, so nothing can import them: %s"
                         % offenders)

    def test_every_hook_actually_imports_in_a_fresh_process(self):
        """The behavioural half. A SUBPROCESS, because an import that calls `sys.exit` would end
        this run rather than fail it. Bounded, because a hook that BLOCKS on stdin would
        otherwise hang the suite forever."""
        for name in hook_files() + ["bundle_env.py", "bundle_shell.py", "dispatch.py"]:
            with self.subTest(hook=name):
                done = subprocess.run(
                    [sys.executable, "-B", "-c",
                     "import sys; sys.path.insert(0, %r); import %s"
                     % (HOOKS, os.path.splitext(name)[0])],
                    stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=60,
                    creationflags=NO_WINDOW)
                self.assertEqual(done.returncode, 0,
                                 "%s did something on import: %s"
                                 % (name, (done.stdout + done.stderr)[:400]))


class EveryEnvironmentNameComesFromONEPlace(unittest.TestCase):
    """They shipped with TWO project prefixes at once - three caps carrying one, four session
    overrides across four files carrying another.

    The direction that matters: an unset BYPASS reads as "not permitted", which is safe; an
    unset CAP falls to its default, so `int(os.environ.get(<dead name>, "10"))` quietly uses 10
    while somebody believes they set 4. That is how 791 agents went through a cap of six."""

    def test_no_hook_reads_a_hardcoded_environment_name(self):
        """`bundle_env.hardcoded_reads` - the one implementation, which resolves a module
        constant to the string it holds. The first version checked literals only, and five
        hooks passed it with `BYPASS = "<name>"` one line up."""
        import bundle_env
        offenders = []
        for name in hook_files() + ["dispatch.py", "bundle_shell.py"]:
            source = io.open(os.path.join(HOOKS, name), encoding="utf-8").read()
            offenders += ["%s:%d %s" % (name, line, value)
                          for line, value in bundle_env.hardcoded_reads(source)]
        self.assertEqual(offenders, [],
                         "spell it with bundle_env.name(...) so one prefix moves everything: %s"
                         % offenders)

    def test_a_name_hidden_in_a_CONSTANT_is_still_a_hardcoded_read(self):
        """The shape the literal-only check missed, planted."""
        import bundle_env
        source = ("import os" + LF + "BYPASS = 'SOMEONE_ALLOW_X'" + LF
                  + "flag = os.environ.get(BYPASS)" + LF)
        self.assertEqual([value for _line, value in bundle_env.hardcoded_reads(source)],
                         ["SOMEONE_ALLOW_X"])

    def test_no_hook_SPELLS_any_prefix_the_live_one_included(self):
        """The textual second layer. A name that survives in a MESSAGE was three of them,
        printed at the moment of refusal, naming a variable the hook does not read."""
        import bundle_env
        for name in hook_files() + ["dispatch.py", "bundle_shell.py"]:
            body = io.open(os.path.join(HOOKS, name), encoding="utf-8").read()
            for dead in bundle_env.dead_prefixes():
                self.assertNotIn(dead, body, "%s spells the prefix %s" % (name, dead))

    def test_an_unreadable_cap_falls_back_LOUDLY_and_does_not_crash(self):
        import bundle_env
        held = os.environ.get(bundle_env.name("AGENT_CAP"))
        self.addCleanup(lambda: os.environ.pop(bundle_env.name("AGENT_CAP"), None)
                        if held is None else os.environ.__setitem__(
                            bundle_env.name("AGENT_CAP"), held))
        os.environ[bundle_env.name("AGENT_CAP")] = "six"
        captured, sys.stderr = sys.stderr, io.StringIO()
        try:
            value, said = bundle_env.cap("AGENT_CAP", 4), sys.stderr.getvalue()
        finally:
            sys.stderr = captured
        self.assertEqual(value, 4)
        self.assertIn("not a number", said, "it fell back silently")

    def test_an_unset_override_is_NOT_permission(self):
        import bundle_env
        os.environ.pop(bundle_env.name("ALLOW_NOTHING"), None)
        self.assertFalse(bundle_env.allowed("ALLOW_NOTHING"))

    def test_a_value_that_is_not_exactly_1_is_not_permission(self):
        import bundle_env
        key = bundle_env.name("ALLOW_NOTHING")
        self.addCleanup(lambda: os.environ.pop(key, None))
        for value in ("0", "", "true", "yes", "2"):
            os.environ[key] = value
            self.assertFalse(bundle_env.allowed("ALLOW_NOTHING"), value)
        os.environ[key] = "1"
        self.assertTrue(bundle_env.allowed("ALLOW_NOTHING"))


class ADigestOfNOTHINGIsNotADigest(unittest.TestCase):
    """`Path(missing).rglob("*")` yields nothing and raises nothing, so the first version of
    `tree_digest` hashed an absent directory to the SHA-256 of the empty string."""

    def setUp(self):
        import require_build
        self.rb = require_build
        self.tmp = tempfile.mkdtemp(prefix="vb-digest-")
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_a_MISSING_root_refuses(self):
        with self.assertRaises(self.rb.NothingToDigest) as refused:
            self.rb.tree_digest(os.path.join(self.tmp, "no-such-directory"))
        self.assertIn("not a directory", str(refused.exception))

    def test_an_EMPTY_root_refuses(self):
        empty = os.path.join(self.tmp, "empty")
        os.makedirs(empty)
        with self.assertRaises(self.rb.NothingToDigest) as refused:
            self.rb.tree_digest(empty)
        self.assertIn("contains no files", str(refused.exception))

    def test_a_REAL_tree_still_digests(self):
        real = os.path.join(self.tmp, "real")
        os.makedirs(real)
        io.open(os.path.join(real, "a.txt"), "w", encoding="utf-8").write("a")
        self.assertEqual(len(self.rb.tree_digest(real)), 64)

    def test_the_manifest_is_what_the_digest_is_taken_OF(self):
        real = os.path.join(self.tmp, "manifest")
        os.makedirs(os.path.join(real, "sub"))
        io.open(os.path.join(real, "b.txt"), "w", encoding="utf-8").write("b")
        io.open(os.path.join(real, "sub", "a.txt"), "w", encoding="utf-8").write("a")
        lines = self.rb.tree_manifest(real)
        self.assertEqual(lines, sorted(lines), "unsorted - the digest would not be stable")
        self.assertEqual([line.split(":")[0] for line in lines], ["b.txt", "sub/a.txt"])

    def test_a_tree_with_NO_SOURCES_is_refused_rather_than_passed(self):
        root = os.path.join(self.tmp, "project")
        classes = os.path.join(root, *self.rb.OUTPUT_ROOT)
        os.makedirs(classes)
        io.open(os.path.join(classes, "x.class"), "w", encoding="utf-8").write("x")
        empty_digest = __import__("hashlib").sha256(b"").hexdigest()
        io.open(os.path.join(classes, self.rb.STAMP), "w", encoding="utf-8", newline=LF).write(
            "src=" + empty_digest + LF
            + "classes=" + self.rb.tree_digest(classes, self.rb.STAMP) + LF)
        problem = self.rb.stamp_problem(root)
        self.assertIsNotNone(problem, "a tree with no sources reported NO PROBLEM")
        self.assertIn("nothing to digest", problem)

    def test_a_stamp_MISSING_a_digest_key_refuses(self):
        root = os.path.join(self.tmp, "nokey")
        classes = os.path.join(root, *self.rb.OUTPUT_ROOT)
        os.makedirs(classes)
        io.open(os.path.join(classes, self.rb.STAMP), "w", encoding="utf-8", newline=LF).write(
            "classes=whatever" + LF)
        self.assertIn("UNKNOWN", self.rb.stamp_problem(root) or "")

    def test_the_build_is_BOUNDED(self):
        self.assertIsInstance(self.rb.BUILD_TIMEOUT, int)
        self.assertGreater(self.rb.BUILD_TIMEOUT, 0)
        source = io.open(os.path.join(TOOLS, "require_build.py"), encoding="utf-8").read()
        tree = ast.parse(source)
        runs = [n for n in ast.walk(tree)
                if isinstance(n, ast.Call) and getattr(n.func, "attr", None) == "run"]
        self.assertTrue(runs, "no subprocess call to check")
        for node in runs:
            self.assertIn("timeout", {k.arg for k in node.keywords},
                          "a subprocess with no timeout can hang the gate forever")


class EveryHookRefusesItsCaseAndALLOWSTheOpposite(unittest.TestCase):
    """A guard that refuses everything is uninstalled within the day; one that refuses nothing
    is decoration. Both halves, per hook."""

    def test_a_long_sleep_that_then_looks_at_something_is_refused(self):
        import guard_idle_poll
        self.assertTrue(guard_idle_poll.verdict("sleep 115; cat out.txt")[0])

    def test_a_short_sleep_goes_through(self):
        import guard_idle_poll
        self.assertFalse(guard_idle_poll.verdict("sleep 5")[0])

    def test_the_sleep_UNIT_is_understood(self):
        import guard_idle_poll
        self.assertTrue(guard_idle_poll.verdict("sleep 2m")[0])

    REDIRECTS = (
        ("cat <<EOF > notes.txt", True),
        ("cat > notes.txt <<EOF", True),
        ("cat <<" + chr(39) + "EOF" + chr(39) + " > notes.txt", True),
        ("cat <<EOF >notes.txt", True),
        ("cat <<EOF >> notes.txt", True),
        ("cat <<EOF | tee notes.txt", True),
        ("python - <<EOF", False),
        ("cat a.txt 2>/dev/null; python - <<EOF", False),
        ("python - <<EOF 2>/dev/null", False),
    )

    def test_a_heredoc_that_writes_a_FILE_is_refused_however_it_is_spelled(self):
        import guard_heredoc
        for opener, refuse in self.REDIRECTS:
            with self.subTest(command=opener):
                command = opener + LF + "x" + LF + "EOF"
                self.assertEqual(guard_heredoc.verdict(command)[0], refuse, opener)

    def test_a_2_redirect_belonging_to_an_EARLIER_command_is_not_this_ones(self):
        import guard_heredoc
        command = ("cat a.txt 2>/dev/null; python - <<EOF" + LF + "print(1)" + LF + "EOF")
        self.assertFalse(guard_heredoc.verdict(command)[0])

    def test_a_here_STRING_is_not_a_heredoc(self):
        import guard_heredoc
        self.assertFalse(guard_heredoc.verdict("grep x <<< 'data'")[0])

    def test_an_ordinary_command_goes_through(self):
        import guard_heredoc
        self.assertFalse(guard_heredoc.verdict("git status --short")[0])

    def test_a_backgrounded_pipe_into_a_buffering_consumer_is_refused(self):
        import guard_background_pipe
        self.assertTrue(guard_background_pipe.verdict("python x.py | tail -5", True)[0])

    def test_the_same_pipe_in_the_FOREGROUND_is_allowed(self):
        import guard_background_pipe
        self.assertFalse(guard_background_pipe.verdict("python x.py | tail -5", False)[0])

    def test_a_backgrounded_command_with_NO_pipe_is_allowed(self):
        import guard_background_pipe
        self.assertFalse(guard_background_pipe.verdict("python x.py > out.txt 2>&1", True)[0])

    def test_reading_the_module_a_mutation_run_replaced_is_refused(self):
        import guard_mutation_read
        deny, _why = guard_mutation_read.verdict("cat src/thing.py", "src/thing.py")
        self.assertTrue(deny)

    def test_reading_an_UNRELATED_file_under_the_same_parent_is_allowed(self):
        import guard_mutation_read
        deny, _why = guard_mutation_read.verdict("cat src/other.py", "src/thing.py")
        self.assertFalse(deny)

    def test_with_NO_run_in_flight_nothing_is_refused(self):
        import guard_mutation_read
        self.assertFalse(guard_mutation_read.verdict("cat src/thing.py", None)[0])

    def test_git_is_allowed_even_while_a_module_is_mutated(self):
        import guard_mutation_read
        self.assertFalse(
            guard_mutation_read.verdict("git show HEAD:src/thing.py", "src/thing.py")[0])


class AFanOutThisHookCannotMEASUREIsRefused(unittest.TestCase):
    """THE WRITE ALREADY REFUSED AND THE READ ABOVE IT DID NOT. `load()` had one
    `except Exception: return 0, 0` serving two different facts - no file, and a file nobody
    could parse. `open(..., "w")` truncates before writing, so a kill in between leaves the
    second, and reading it as zero forgives the whole window, every time."""

    def setUp(self):
        import guard_fanout
        self.guard = guard_fanout
        self.tmp = tempfile.mkdtemp(prefix="vb-fanout-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.addCleanup(setattr, guard_fanout, "STATE", guard_fanout.STATE)
        guard_fanout.STATE = os.path.join(self.tmp, "fanout-count")

    def clean_env(self, **extra):
        # THE HOOK'S OWN NAME FOR ITS ESCAPE. This asked `bundle_env` for a name the hook did
        # not read, so the escape test set a variable nothing looked at and failed - the defect
        # this class's neighbour exists to refuse, inside the test for it.
        env = {k: v for k, v in os.environ.items() if k != self.guard.ALLOW_UNMEASURED}
        env.update(extra)
        return env

    def decision(self, payload, env=None):
        done = subprocess.run(
            [sys.executable, "-B", os.path.join(HOOKS, "guard_fanout.py")],
            input=payload, capture_output=True, text=True,
            env=env if env is not None else self.clean_env(), timeout=60,
            creationflags=NO_WINDOW)
        said = (done.stdout or "").strip()
        # A CRASH IS NOT AN ALLOW, and this read it as one. The harness treats a hook that exits
        # 1 as a non-blocking error, so a crash IS an allow to the tool call - which is exactly
        # why it must not be one to the test. The Workflow refusal threw `TypeError` building its
        # own message for every call, and "no stdout" here said `allow` and passed.
        if done.returncode not in (0, 2):
            return "crashed (rc %d): %s" % (done.returncode, (done.stderr or "")[-300:])
        if not said:
            return "allow"
        return json.loads(said)["hookSpecificOutput"]["permissionDecision"]

    def test_a_WORKFLOW_is_refused_and_the_refusal_PRINTS(self):
        env = {k: v for k, v in self.clean_env().items()
               if k != self.guard.ALLOW_WORKFLOW}
        self.assertEqual(self.decision(json.dumps({"tool_name": "Workflow"}), env), "deny")

    def write(self, text):
        io.open(self.guard.STATE, "w", encoding="utf-8", newline=LF).write(text)

    def test_NO_counter_yet_is_honestly_zero(self):
        self.assertEqual(self.guard.load(), (0, 0))

    def test_a_counter_that_cannot_be_PARSED_is_UNKNOWN_not_zero(self):
        self.write("this,is,not,a,count")
        self.assertIsNone(self.guard.load())

    def test_an_EMPTY_counter_is_UNKNOWN_not_zero(self):
        self.write("")
        self.assertIsNone(self.guard.load())

    def test_a_VALID_counter_still_reads_its_numbers(self):
        self.write("%f,2,1" % time.time())
        self.assertEqual(self.guard.load(), (2, 1))

    def test_a_counter_written_before_workflows_were_counted_still_reads(self):
        """`stamp,agents` - two fields. Reading it as unparseable would refuse every launch in
        a project whose counter predates the third field."""
        self.write("%f,3" % time.time())
        self.assertEqual(self.guard.load(), (3, 0))

    def test_a_counter_older_than_the_window_is_zero_again(self):
        self.write("%f,9,9" % (time.time() - self.guard.WINDOW - 60))
        self.assertEqual(self.guard.load(), (0, 0))

    def test_an_UNREADABLE_payload_is_refused_through_the_real_contract(self):
        self.assertEqual(self.decision("this is not json"), "deny")

    def test_a_readable_payload_for_a_tool_this_hook_does_not_guard_goes_through(self):
        self.assertEqual(
            self.decision(json.dumps({"tool_name": "Read", "tool_input": {}})), "allow")

    def test_the_owner_escape_lifts_the_unmeasured_refusal(self):
        env = self.clean_env(**{self.guard.ALLOW_UNMEASURED: "1"})
        self.assertEqual(self.decision("this is not json", env), "allow")

    def test_an_agent_launch_under_ANOTHER_NAME_is_counted(self):
        """The launching tool was `Task` before it was `Agent`. By shape, whatever its name."""
        import bundle_shell
        launch = {"tool_name": "Delegate", "tool_input": {
            "prompt": "survey x", "description": "survey", "subagent_type": "Explore"}}
        self.assertEqual(len(bundle_shell.launches(launch)), 1)


class ARecordedMeasurementCarriesTheTreeItMeasured(unittest.TestCase):
    """`subject_digest` is the half that was missing. A digest returns sixty-four hex characters
    whether or not it read anything, and two calls to a function that reads nothing agree."""

    def setUp(self):
        import require_build
        self.rb = require_build
        self.tmp = tempfile.mkdtemp(prefix="vb-subject-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.repo = os.path.join(self.tmp, "repo")
        os.makedirs(os.path.join(self.repo, "src"))
        subprocess.run(["git", "init", "-q"], cwd=self.repo, capture_output=True, timeout=120,
                       creationflags=NO_WINDOW)
        self.write("src/a.txt", "one")

    def write(self, rel, body, crlf=False):
        path = os.path.join(self.repo, rel.replace("/", os.sep))
        io.open(path, "w", encoding="utf-8",
                newline=(chr(13) + LF) if crlf else LF).write(body + LF)

    def test_a_tree_that_is_NOT_a_repository_refuses(self):
        plain = os.path.join(self.tmp, "plain")
        os.makedirs(plain)
        io.open(os.path.join(plain, "a.txt"), "w", encoding="utf-8").write("a")
        with self.assertRaises(self.rb.NothingToDigest) as refused:
            self.rb.subject_digest(plain)
        self.assertIn("not a git repository", str(refused.exception))

    def test_a_real_tree_digests_and_a_changed_file_MOVES_it(self):
        first = self.rb.subject_digest(self.repo)
        self.assertEqual(len(first), 64)
        self.assertEqual(self.rb.subject_digest(self.repo), first, "and it is stable")
        self.write("src/a.txt", "two")
        self.assertNotEqual(self.rb.subject_digest(self.repo), first)
        self.write("src/a.txt", "one")
        self.assertEqual(self.rb.subject_digest(self.repo), first)

    def test_a_record_at_the_ROOT_is_not_part_of_its_OWN_subject(self):
        first = self.rb.subject_digest(self.repo)
        self.write(".last-suite-run", '{"ran": 3}')
        self.write(".last-replant", '{"plants": 3}')
        self.assertEqual(self.rb.subject_digest(self.repo), first)
        self.write("src/.hidden", "x")
        self.assertNotEqual(self.rb.subject_digest(self.repo), first,
                            "but a dotfile INSIDE the tree is source, and does move it")

    def test_the_SAME_content_with_other_line_endings_digests_the_same(self):
        first = self.rb.subject_digest(self.repo)
        self.write("src/a.txt", "one", crlf=True)
        self.assertEqual(self.rb.subject_digest(self.repo), first)

    def test_a_DECLARED_path_is_left_out_and_nothing_else_is(self):
        """A published page rewritten every few minutes is not what a suite measured, and a
        subject including it refuses every honest claim - but only the declared path goes."""
        self.write("src/page.md", "reading 1")
        first = self.rb.subject_digest(self.repo, exclude=("src/page.md",))
        self.write("src/page.md", "reading 2")
        self.assertEqual(self.rb.subject_digest(self.repo, exclude=("src/page.md",)), first,
                         "the declared page moved the subject")
        self.write("src/a.txt", "changed")
        self.assertNotEqual(self.rb.subject_digest(self.repo, exclude=("src/page.md",)), first,
                            "excluding one path excluded more than that path")

    def test_the_RULE_FILE_above_the_root_is_part_of_the_subject(self):
        """ADAPT.md said so for months and the function never did: on CTRMap CLAUDE.md sits
        ABOVE the repository, where git has never heard of it."""
        first = self.rb.subject_digest(self.repo)
        io.open(os.path.join(self.tmp, "CLAUDE.md"), "w", encoding="utf-8").write("a rule" + LF)
        self.assertNotEqual(self.rb.subject_digest(self.repo), first,
                            "a rule added above the root did not move the subject")


class OneSplitterAndTheShapesOfACall(unittest.TestCase):
    """FIVE HOOKS EACH CARRIED THEIR OWN COMMAND SPLITTER and three did not treat a NEWLINE as a
    separator, so `cd x`, newline, `sleep 300` was one command beginning `cd`. And a call that
    carried a PATH instead of a command - `Read`, `Grep`, `Edit` - was invisible to every guard
    whose subject is reading or writing a file."""

    CASES = (
        ("git status; rm -rf x", ["git status", "rm -rf x"]),
        ("cd x" + LF + "sleep 300" + LF + "cat y", ["cd x", "sleep 300", "cat y"]),
        ("echo 'a; b' | grep a", ["echo 'a; b' | grep a"]),
        ("git commit -F - <<EOF" + LF + "fix: x; y" + LF + "rm z" + LF + "EOF" + LF + "git log",
         ["git commit -F - <<EOF" + LF + "fix: x; y" + LF + "rm z" + LF + "EOF", "git log"]),
        ("Get-Item x `" + LF + "  -Force; ls", ["Get-Item x `" + LF + "  -Force", "ls"]),
        ("a || b && c", ["a", "b", "c"]),
    )

    def test_a_NEWLINE_separates_commands_and_a_heredoc_body_does_not(self):
        import bundle_shell
        for given, expected in self.CASES:
            with self.subTest(given=given):
                self.assertEqual(bundle_shell.split_commands(given), expected)

    def test_an_escape_is_asked_of_EVERY_command_never_of_the_line(self):
        """`git status; <anything>` was an escape in two guards. And nothing asked is not an
        escape: an empty line excuses nothing."""
        import bundle_shell
        is_git = lambda c: c.startswith("git")          # noqa: E731
        self.assertFalse(bundle_shell.every_command_is("git status; rm -rf x", is_git))
        self.assertTrue(bundle_shell.every_command_is("cd /repo && git status", is_git))
        self.assertFalse(bundle_shell.every_command_is("", is_git))

    def test_PATHS_and_WRITES_are_found_by_shape_at_any_depth(self):
        import bundle_shell
        read = {"tool_name": "Read", "tool_input": {"file_path": "src/a.py"}}
        nested = {"tool_name": "Batch", "tool_input": {"actions": [
            {"input": {"path": "src/"}}]}}
        edit = {"tool_name": "Edit", "tool_input": {"file_path": "a", "new_string": "x"}}
        self.assertEqual(bundle_shell.paths(read), ["src/a.py"])
        self.assertEqual(bundle_shell.paths(nested), ["src/"])
        self.assertFalse(bundle_shell.writes(read))
        self.assertTrue(bundle_shell.writes(edit))
        self.assertTrue(bundle_shell.reads_only(read))
        self.assertFalse(bundle_shell.reads_only(edit))
        self.assertFalse(bundle_shell.reads_only({"transcript_path": "x"}),
                         "the end of a turn is not a tool call at all")

    def test_the_DESTRUCTIVE_list_is_the_projects_file_when_there_is_one(self):
        """ADAPT.md promised `destructive_scripts.txt` for months while no hook read it."""
        import bundle_shell
        folder = tempfile.mkdtemp(prefix="vb-destr-")
        self.addCleanup(shutil.rmtree, folder, True)
        self.assertEqual(bundle_shell.destructive_scripts(("a.py",), folder), [("a.py", None)])
        io.open(os.path.join(folder, bundle_shell.DESTRUCTIVE_FILE), "w", encoding="utf-8",
                newline=LF).write("tools/mutate2.py" + LF + "tools/sweep.py run  # verb" + LF)
        self.assertEqual(bundle_shell.destructive_scripts(("a.py",), folder),
                         [("tools/mutate2.py", None), ("tools/sweep.py", "run")])

    def test_a_DEFAULT_verb_that_does_damage_is_matched_when_nothing_is_typed(self):
        """`plants.py` with no argument runs `verify`, which plants defects. A rule keyed on
        the word `verify` has a hole the size of typing less."""
        import bundle_shell
        self.assertTrue(bundle_shell.sub_matches([], "verify|-"))
        self.assertTrue(bundle_shell.sub_matches(["verify", "--only", "x"], "verify|-"))
        self.assertFalse(bundle_shell.sub_matches(["owed"], "verify|-"))
        self.assertFalse(bundle_shell.sub_matches([], "run"))


class TheDispatcherAsksEveryGuardAndCannotBeWedged(unittest.TestCase):
    """THE BUNDLE'S DISPATCHER CRASHED ON THE ONE BRANCH WHOSE JOB IS TO REPORT. `ask` returned
    two values when a guard could not load and three otherwise, and `verdict` unpacks three."""

    def test_a_guard_that_cannot_LOAD_is_reported_not_raised(self):
        dispatch = _dispatch()
        folder = tempfile.mkdtemp(prefix="vb-dispatch-")
        self.addCleanup(shutil.rmtree, folder, True)
        broken = os.path.join(folder, "guard_broken.py")
        io.open(broken, "w", encoding="utf-8", newline=LF).write("this is not python(" + LF)
        code, said, forward = dispatch.verdict(
            json.dumps({"tool_name": "Bash", "tool_input": {"command": "ls"}}), found=[broken])
        self.assertEqual(code, 2)
        self.assertIn("could not be loaded", said)

    def test_a_command_nobody_can_READ_is_refused_once_for_every_guard(self):
        """`bundle_shell.unreadable` shipped with no caller: a `command` that was not text read
        as no command at all, to every guard."""
        dispatch = _dispatch()
        code, said, _f = dispatch.verdict(
            json.dumps({"tool_name": "Shell", "tool_input": {"command": 42}}), found=[])
        self.assertEqual(code, 2)
        self.assertIn("could not be read", said)
        code, _said, _f = dispatch.verdict(
            json.dumps({"tool_name": "Shell", "tool_input": {"command": "ls"}}), found=[])
        self.assertEqual(code, 0, "a readable command must not be refused for readability")

    def test_a_crashed_guard_refuses_the_end_of_a_turn_ONCE(self):
        """The second refusal of the same `Stop` would spin forever over a file the turn may not
        be able to reach; the first sends the turn back to fix it."""
        dispatch = _dispatch()
        crashed = lambda *_a, **_k: (dispatch.CRASHED, "raised", "")    # noqa: E731
        held = dispatch.ask
        dispatch.ask = crashed
        try:
            first = dispatch.verdict(json.dumps({"session_id": "s"}), found=["g.py"])[0]
            again = dispatch.verdict(json.dumps({"session_id": "s", "stop_hook_active": True}),
                                     found=["g.py"])[0]
        finally:
            dispatch.ask = held
        self.assertEqual((first, again), (2, 0))


class AHookNamesTheFolderItSitsIn(unittest.TestCase):
    """A project that keeps its own guard stack in `.claude/hooks` installs the bundle's beside it
    (`_hooks_at`). Measured on CTRMap: every refusal named `.claude/hooks/<guard>` about a guard
    that was not there - while the project's OWN guard of that name was - and the dispatcher's
    repair route pointed at the project's folder, so a broken bundle guard could be repaired by
    nothing but git. Driven from a copy of the hooks in a folder of another name, each in its own
    interpreter so no module already loaded from the real folder answers for the copy."""

    def moved(self):
        folder = tempfile.mkdtemp(prefix="vb-moved-")
        self.addCleanup(shutil.rmtree, folder, True)
        target = os.path.join(folder, ".claude", "elsewhere")
        shutil.copytree(HOOKS, target, ignore=shutil.ignore_patterns("__pycache__", ".*"))
        return folder, target

    def run_in(self, target, code, stdin=""):
        done = subprocess.run([sys.executable, "-B", "-c", code], cwd=os.path.dirname(target),
                              input=stdin, capture_output=True, text=True, timeout=120,
                              creationflags=NO_WINDOW,
                              env=dict(os.environ, PYTHONPATH=target))
        return done.stdout + done.stderr

    def test_a_refusal_names_the_folder_its_guard_is_in(self):
        _folder, target = self.moved()
        payload = json.dumps({"tool_name": "Bash", "tool_input": {
            "command": "cat > notes.txt <<EOF" + LF + "x" + LF + "EOF"}})
        said = subprocess.run([sys.executable, "-B", os.path.join(target, "guard_heredoc.py")],
                              input=payload, capture_output=True, text=True, timeout=120,
                              creationflags=NO_WINDOW)
        out = said.stdout + said.stderr
        self.assertIn("BLOCKED", out, "the planted heredoc was not refused at all: " + out[-400:])
        self.assertIn(".claude/elsewhere/guard_heredoc.py", out,
                      "a refusal named a folder its guard is not in: " + out[-400:])

    def test_the_repair_route_is_the_folder_the_dispatcher_is_in(self):
        _folder, target = self.moved()
        said = self.run_in(target, "import dispatch; print(dispatch.repairs("
                                   "'{\"file_path\": \"p/.claude/elsewhere/guard_x.py\"}'), "
                                   "dispatch.repairs('{\"file_path\": \"p/.claude/other/x.py\"}'))")
        self.assertEqual(said.strip(), "True False",
                         "the repair route is not the folder the dispatcher sits in: " + said[-400:])

    def test_no_hook_SPELLS_the_folder_it_sits_in(self):
        """EVERY hook, not the one guard driven above. Asked of each hook's AST in the folder this
        project keeps them in, through the one rule the bundle's own self-check asks - so a
        project that never runs that self-check still refuses a message spelled back in."""
        import bundle_rules
        import bundle_shell
        self.assertEqual(bundle_shell.FOLDER, bundle_hooks.folder(HERE),
                         "SPELLED: the hooks do not read the folder this project keeps them in")
        found = []
        for name in sorted(os.listdir(HOOKS)):
            if name.endswith(".py"):
                with io.open(os.path.join(HOOKS, name), encoding="utf-8") as handle:
                    found += ["%s:%d %s" % (name, line, what)
                              for line, what in bundle_rules.spelled_folders(handle.read())]
        self.assertEqual(found, [], "SPELLED: a hook names a folder it may not sit in: %r" % found)
        # Built from `bundle_hooks.DEFAULT` at run time: spelled here, this file would carry the
        # very constants the rule refuses in anything a project installs.
        claude, hooks = bundle_hooks.DEFAULT.split("/")
        for control in ('M = "BLOCKED (%s/%s/guard_x.py)"' % (claude, hooks),
                        'M = "%s/" + "%s"' % (claude, hooks)):
            self.assertTrue(bundle_rules.spelled_folders(control + LF),
                            "SPELLED: the rule missed the spelling it exists for: %s" % control)


class ThePowerShellSpellingIsTheSameAct(unittest.TestCase):
    """THIS SESSION'S PRIMARY SHELL IS POWERSHELL, and every guard knew bash's spelling only:
    `Start-Sleep`, a here-string into `Set-Content`, `Select-Object -Last` - each the exact act a
    guard exists to refuse, each walking through it."""

    def test_Start_Sleep_is_a_sleep(self):
        import guard_idle_poll
        self.assertTrue(guard_idle_poll.verdict("Start-Sleep -Seconds 300; Get-Content out.txt")[0])
        self.assertTrue(guard_idle_poll.verdict("Start-Sleep 120")[0])
        self.assertFalse(guard_idle_poll.verdict("Start-Sleep -Milliseconds 500")[0])

    def test_a_sleep_on_the_SECOND_LINE_is_seen(self):
        import guard_idle_poll
        self.assertTrue(guard_idle_poll.verdict("cd x" + LF + "sleep 300" + LF + "cat y")[0])

    def test_a_LOOP_that_sleeps_is_a_poll_whatever_the_sleep(self):
        """`until grep -q TOTAL f; do sleep 30; done` ran twenty-one hours against a file that
        would never contain the string. A `for` over a fixed range has a deadline; allowed."""
        import guard_idle_poll
        self.assertTrue(guard_idle_poll.verdict(
            "until grep -q TOTAL out.txt; do sleep 30; done")[0])
        self.assertTrue(guard_idle_poll.verdict(
            "while (-not (Test-Path x)) { Start-Sleep 5 }")[0])
        self.assertFalse(guard_idle_poll.verdict("for i in 1 2 3; do curl -s x && break; sleep 1;"
                                                 " done")[0])
        self.assertFalse(guard_idle_poll.verdict("git commit -m 'while I sleep 5 minutes'")[0])

    def test_a_here_STRING_written_to_a_file_is_refused(self):
        import guard_heredoc
        body = "@'" + LF + "print(1)" + LF + "'@ | Set-Content x.py"
        self.assertTrue(guard_heredoc.verdict(body)[0])
        short = "@'" + LF + "print(1)" + LF + "'@ | python -"
        self.assertFalse(guard_heredoc.verdict(short)[0], "a one-liner to an interpreter is fine")

    def test_a_LONG_inline_program_is_refused_and_a_short_one_is_not(self):
        """README section 10: `python -c` and `-Command` are the same shell string one flag
        away from a heredoc."""
        import guard_heredoc
        long_body = "python -c " + chr(34) + LF.join("x%d = %d" % (n, n) for n in range(30)) \
            + chr(34)
        self.assertTrue(guard_heredoc.verdict(long_body)[0])
        self.assertFalse(guard_heredoc.verdict("python -c " + chr(34) + "print(1)" + chr(34))[0])

    def test_Select_Object_Last_buffers_like_tail(self):
        import guard_background_pipe
        self.assertTrue(guard_background_pipe.verdict(
            "python x.py | Select-Object -Last 5", True)[0])
        self.assertFalse(guard_background_pipe.verdict(
            "Get-ChildItem | Select-Object Name", True)[0], "a projection streams")

    def test_a_destructive_run_on_the_SECOND_LINE_is_seen(self):
        import guard_background_pipe
        self.assertTrue(guard_background_pipe.invokes(
            "cd x" + LF + "python tools/audit/mutate.py m.py", ["tools/audit/mutate.py"]))
        self.assertFalse(guard_background_pipe.invokes(
            "grep -n x tools/audit/mutate.py", ["tools/audit/mutate.py"]))


class AReadIsAReadWhateverCarriesIt(unittest.TestCase):
    """`guard_mutation_read` said in a comment that the `Read` tool falls through because it
    carries no command. It did - and it read the unparsed module as surely as `cat`."""

    def setUp(self):
        import guard_mutation_read
        self.guard = guard_mutation_read
        self.root = tempfile.mkdtemp(prefix="vb-read-")
        self.addCleanup(shutil.rmtree, self.root, True)

    def test_the_READ_tool_on_the_locked_module_is_refused(self):
        found = self.guard.touched_paths([os.path.join(self.root, "src", "thing.py")],
                                         "src/thing.py", self.root)
        self.assertEqual(len(found), 1)

    def test_GREP_over_its_directory_is_refused_and_a_sibling_is_not(self):
        self.assertTrue(self.guard.touched_paths(["src/"], "src/thing.py", self.root))
        self.assertFalse(self.guard.touched_paths(["src/other.py"], "src/thing.py", self.root))
        self.assertFalse(self.guard.touched_paths(["/elsewhere/src/thing.py"], "src/thing.py",
                                                  self.root))

    def _main_under(self, module, payload):
        """Run `module.main()` in-process on `payload`, with stdout/stderr captured."""
        held = sys.stdin, sys.stdout, sys.stderr
        sys.stdin, sys.stdout, sys.stderr = (io.StringIO(json.dumps(payload)), io.StringIO(),
                                             io.StringIO())
        try:
            return module.main()
        finally:
            sys.stdin, sys.stdout, sys.stderr = held

    def test_a_READ_is_never_refused_by_the_machine_guards(self):
        """README section 15: reading must never be blocked. Both machine guards refused every
        `Read` while they refused commands, because they judged the empty command text of a call
        that carried none.

        DRIVEN IN THE STATE WHERE THEY REFUSE. Asked against a machine that is not idle, this
        test passes with the carve-out deleted - it measures the machine, not the guard. So each
        guard's decision is forced to REFUSE, and a read and the end of a turn must still pass
        while a command does not."""
        import guard_blocked_runner
        import guard_idle_machine
        self.addCleanup(setattr, guard_blocked_runner, "blocked_for",
                        guard_blocked_runner.blocked_for)
        self.addCleanup(setattr, guard_idle_machine, "decide", guard_idle_machine.decide)
        guard_blocked_runner.blocked_for = lambda: (10 ** 6, ["tools/x.py"])
        guard_idle_machine.decide = lambda command: (2, "the machine is idle")
        read = {"tool_name": "Read", "tool_input": {"file_path": "x"}}
        stop = {"session_id": "s", "transcript_path": "x"}
        work = {"tool_name": "Bash", "tool_input": {"command": "python work.py"}}
        for module in (guard_blocked_runner, guard_idle_machine):
            with self.subTest(guard=module.__name__):
                self.assertEqual(self._main_under(module, work), 2,
                                 "the forced state must refuse work, or this proves nothing")
                self.assertEqual(self._main_under(module, read), 0, "a READ was refused")
                self.assertEqual(self._main_under(module, stop), 0,
                                 "the end of a turn was refused")

    #: A `SendMessage` input AS THE HARNESS RECORDS IT, measured from a transcript: the three keys
    #: the call was given, and three it gained - one of them `content`, which `Write` carries.
    MESSAGE = {"tool_name": "SendMessage", "tool_input": {
        "to": "peer", "summary": "stuck", "message": "the machine guard refused me",
        "recipient": "peer", "type": "message", "content": "the machine guard refused me"}}

    def test_a_MESSAGE_is_never_refused_by_the_machine_guards(self):
        """2026-09-24: a stalled-job refusal held two sessions for seven hours, and neither could
        tell the other or the owner why - every message read as a file WRITE, because the harness
        gives a message a `content` key. A write is content AND a place to put it."""
        import bundle_shell
        import guard_blocked_runner
        import guard_idle_machine
        self.assertFalse(bundle_shell.writes(self.MESSAGE),
                         "MESSAGE: a message with nobody's file in it read as a file write")
        self.assertTrue(bundle_shell.reads_only(self.MESSAGE))
        batch = {"tool_name": "Batch", "tool_input": {"actions": [
            {"input": {"file_path": "a.py", "new_string": "x"}}]}}
        self.assertTrue(bundle_shell.writes(batch),
                        "MESSAGE: a write nested inside a batch walked past as a read")
        self.addCleanup(setattr, guard_blocked_runner, "blocked_for",
                        guard_blocked_runner.blocked_for)
        self.addCleanup(setattr, guard_idle_machine, "decide", guard_idle_machine.decide)
        guard_blocked_runner.blocked_for = lambda: (10 ** 6, ["tools/x.py"])
        guard_idle_machine.decide = lambda command: (2, "the machine is idle")
        for module in (guard_blocked_runner, guard_idle_machine):
            with self.subTest(guard=module.__name__):
                self.assertEqual(self._main_under(module, self.MESSAGE), 0,
                                 "MESSAGE: the way to say why the work stopped was refused")


class AnEscapeIsAskedOfEveryCommand(unittest.TestCase):
    """The cheapest way past a refusal was to put the way out in front of the thing refused."""

    def test_the_blocked_runner_escape_is_per_command(self):
        import guard_blocked_runner as guard
        self.assertTrue(guard.is_escape("git add -A && git commit -m x"))
        self.assertFalse(guard.is_escape("git status; python evil.py"))
        self.assertFalse(guard.is_escape("echo tools/dev/safe.py"),
                         "a tool NAMED is not a tool RUN")

    def test_the_idle_machine_escape_is_per_command(self):
        import guard_idle_machine as guard
        self.assertTrue(guard.is_escape("cat .sweep/blocked.json"))
        self.assertFalse(guard.is_escape("python work.py; ls .sweep/"))

    def test_every_REMEDY_a_stalled_job_refusal_names_is_reachable(self):
        """It says "stop the job, or delete <file> if it has finished". On 2026-09-24 neither was
        an escape, and two sessions waited seven hours for the owner to delete one file by hand.
        The only escape that could delete it was `git clean -X`, which would have taken every
        ignored file in the state folder with it. Exactly the named file, and nothing beside it."""
        import guard_idle_machine as guard
        state = os.path.basename(guard.STATE)
        named = [os.path.join(guard.STATE, name) for name in guard.PROGRESS_FILES]
        for command in (['rm "%s"' % named[0], 'Remove-Item -LiteralPath "%s"' % named[1],
                         "rm %s/%s" % (state, guard.PROGRESS_FILES[0]),
                         "Stop-Process -Id 4242", "Get-Process python",
                         "python -u -B tools/audit/exercised.py derive"]):
            self.assertTrue(guard.is_escape(command),
                            "REMEDY: the refusal's own remedy was refused: %s" % command)
        for command in ("rm -r %s" % state, "Remove-Item %s" % state,
                        "rm %s/%s %s/ledger-0.jsonl" % (state, guard.PROGRESS_FILES[0], state),
                        "rm %s/%s; python work.py" % (state, guard.PROGRESS_FILES[0]),
                        "rm %s/%s.bak" % (state, guard.PROGRESS_FILES[0])):
            self.assertFalse(guard.is_escape(command),
                             "REMEDY: more than the named file walked out: %s" % command)

    def test_a_READ_in_front_does_not_make_a_WRITE_read_only(self):
        import guard_machine_worktrees as guard
        self.assertTrue(guard.is_read_only("cat .sweep/tree-3/x.py"))
        self.assertFalse(guard.is_read_only("cat x; rm -rf .sweep/tree-3"))

    def test_an_EDIT_into_a_worktree_is_seen(self):
        import guard_machine_worktrees as guard
        edit = {"tool_name": "Edit", "tool_input": {
            "file_path": "C:/r/.sweep/tree-2/src/a.py", "new_string": "x"}}
        read = {"tool_name": "Read", "tool_input": {"file_path": "C:/r/.sweep/tree-2/src/a.py"}}
        self.assertEqual(len(guard.written_worktree_paths(edit)), 1)
        self.assertEqual(guard.written_worktree_paths(read), [])


def _transcript(folder, messages):
    """A transcript holding `messages`: [(message id, [(tool_use id, input)])]."""
    path = os.path.join(folder, "t.jsonl")
    with io.open(path, "w", encoding="utf-8", newline=LF) as handle:
        for message_id, uses in messages:
            for use_id, tool_input in uses:
                handle.write(json.dumps({"type": "assistant", "message": {
                    "id": message_id, "content": [
                        {"type": "tool_use", "id": use_id, "name": "Agent",
                         "input": tool_input}]}}) + LF)
    return path


class AnEditingAgentMayNotWriteBesideAnother(unittest.TestCase):
    """AGENTS THAT CANNOT EDIT EACH OTHER'S FILES MUST NOT EDIT AT THE SAME TIME. A project had
    recorded its fan-out CAP as the enforcement of this rule; four editing agents at once sat
    comfortably under a cap of four."""

    FIX = {"prompt": "Fix the parser in src/p.py.", "description": "fix", "subagent_type": "x"}
    SURVEY = {"prompt": "Report which lines to fix; do not edit.", "description": "survey",
              "subagent_type": "Explore"}

    def setUp(self):
        import guard_agent_edits
        self.guard = guard_agent_edits
        self.tmp = tempfile.mkdtemp(prefix="vb-edits-")
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def payload(self, tool_input, use_id, transcript):
        return {"tool_name": "Agent", "tool_input": tool_input, "tool_use_id": use_id,
                "transcript_path": transcript}

    def test_an_editing_agent_in_the_BACKGROUND_is_refused(self):
        transcript = _transcript(self.tmp, [("m1", [("u1", self.FIX)])])
        self.assertTrue(self.guard.verdict(self.payload(self.FIX, "u1", transcript))[0])

    def test_ONE_editing_agent_in_the_foreground_is_allowed(self):
        launch = dict(self.FIX, run_in_background=False)
        transcript = _transcript(self.tmp, [("m1", [("u1", launch)])])
        self.assertFalse(self.guard.verdict(self.payload(launch, "u1", transcript))[0])

    def test_TWO_editing_agents_in_one_message_are_refused(self):
        launch = dict(self.FIX, run_in_background=False)
        transcript = _transcript(self.tmp, [("m1", [("u1", launch), ("u2", launch)])])
        self.assertTrue(self.guard.verdict(self.payload(launch, "u1", transcript))[0])

    def test_a_SURVEY_in_the_background_is_allowed(self):
        """Fan out to READ, bring the findings back to one writer - a survey composes."""
        transcript = _transcript(self.tmp, [("m1", [("u1", self.SURVEY)])])
        self.assertFalse(self.guard.verdict(self.payload(self.SURVEY, "u1", transcript))[0])

    def test_a_WORKTREE_launch_edits_whatever_its_brief_says(self):
        launch = dict(self.SURVEY, isolation="worktree")
        self.assertTrue(self.guard.editing(launch))

    def test_unmeasurable_concurrency_is_refused(self):
        launch = dict(self.FIX, run_in_background=False)
        self.assertTrue(self.guard.verdict(
            self.payload(launch, "u1", os.path.join(self.tmp, "missing.jsonl")))[0])


class AnAgentIsAskedForAStructuredResult(unittest.TestCase):
    """README section 12: provenByBreaking, batteryGreen, leftUndone - and an admitted gap is
    worth more than a confident wrong answer. A bullet in a list, and nothing asked."""

    def test_a_brief_without_the_contract_is_refused(self):
        import guard_agent_brief
        launch = {"tool_name": "Agent", "tool_input": {
            "prompt": "Look at x and tell me.", "description": "look"}}
        deny, why = guard_agent_brief.verdict(launch)
        self.assertTrue(deny)
        self.assertIn("leftUndone", why)

    def test_a_brief_WITH_the_contract_is_allowed(self):
        import guard_agent_brief
        launch = {"tool_name": "Agent", "tool_input": {"description": "look", "prompt": (
            "Look at x. Return provenByBreaking, batteryGreen and leftUndone. An admitted gap "
            "is worth more than a confident wrong answer.")}}
        self.assertFalse(guard_agent_brief.verdict(launch)[0])

    def test_a_call_that_launches_nothing_is_none_of_its_business(self):
        import guard_agent_brief
        self.assertFalse(guard_agent_brief.verdict(
            {"tool_name": "Bash", "tool_input": {"command": "ls"}})[0])


class AnAgentCampaignNeedsStateAndATrendThatFalls(unittest.TestCase):
    """README section 11 - STATE.md before the first multi-agent run - and section 12 - stop when
    the last three rounds are flat or rising. Both were sentences; the round log existed and the
    command that checks it was something a person had to remember to run."""

    LAUNCH = {"tool_name": "Agent", "tool_input": {"prompt": "survey x", "description": "s"}}

    def setUp(self):
        import guard_agent_campaign
        import guard_fanout
        self.guard, self.fanout = guard_agent_campaign, guard_fanout
        self.tmp = tempfile.mkdtemp(prefix="vb-campaign-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        for module, name, value in (
                (guard_fanout, "STATE", os.path.join(self.tmp, "fanout-count")),
                (guard_agent_campaign, "STATE_FILE", os.path.join(self.tmp, "STATE.md")),
                (guard_agent_campaign, "CONVERGENCE_LOG", os.path.join(self.tmp, "conv.json"))):
            self.addCleanup(setattr, module, name, getattr(module, name))
            setattr(module, name, value)

    def agents_so_far(self, count):
        io.open(self.fanout.STATE, "w", encoding="utf-8").write("%f,%d,0" % (time.time(), count))

    def rounds(self, counts):
        io.open(self.guard.CONVERGENCE_LOG, "w", encoding="utf-8").write(json.dumps({"rounds": [
            {"scope": "audit", "n": i + 1, "findings": c, "recorded": "2026-09-2%dT00:00:00Z" % i}
            for i, c in enumerate(counts)]}))

    def test_the_FIRST_agent_of_a_window_needs_no_state(self):
        self.agents_so_far(0)
        self.assertFalse(self.guard.verdict(self.LAUNCH)[0])

    def test_a_SECOND_agent_without_state_is_refused_and_with_fresh_state_is_not(self):
        self.agents_so_far(1)
        deny, why = self.guard.verdict(self.LAUNCH)
        self.assertTrue(deny)
        self.assertIn("no campaign state", why)
        io.open(self.guard.STATE_FILE, "w", encoding="utf-8").write("decided: x" + LF)
        self.assertFalse(self.guard.verdict(self.LAUNCH)[0], "fresh state was refused")

    def test_STALE_state_is_refused(self):
        self.agents_so_far(1)
        io.open(self.guard.STATE_FILE, "w", encoding="utf-8").write("old" + LF)
        old = time.time() - self.fanout.WINDOW - 60
        os.utime(self.guard.STATE_FILE, (old, old))
        deny, why = self.guard.verdict(self.LAUNCH)
        self.assertTrue(deny)
        self.assertIn("hours ago", why)

    def test_a_FLAT_trend_refuses_the_next_round_and_a_falling_one_does_not(self):
        self.agents_so_far(0)
        self.rounds([59, 79, 49, 48, 48])
        deny, why = self.guard.verdict(self.LAUNCH)
        self.assertTrue(deny)
        self.assertIn("NOT CONVERGING", why)
        self.rounds([40, 30, 20])
        self.assertFalse(self.guard.verdict(self.LAUNCH)[0], "a falling trend was refused")

    def test_an_UNREADABLE_log_is_not_a_falling_trend(self):
        self.agents_so_far(0)
        io.open(self.guard.CONVERGENCE_LOG, "w", encoding="utf-8").write("{not json")
        self.assertTrue(self.guard.verdict(self.LAUNCH)[0])

    def test_a_call_that_launches_nothing_is_none_of_its_business(self):
        self.agents_so_far(3)
        self.rounds([5, 6, 7])
        self.assertFalse(self.guard.verdict(
            {"tool_name": "Bash", "tool_input": {"command": "ls"}})[0])


class AClaimAboutAFileMustBeAboutAnOpenedFile(unittest.TestCase):
    """SAY YOU LOOKED - `build.ps1 (lines 1-100 of 210)`, about a file no copy of which was
    longer than 115 lines. And a claim about a file nothing opened is a measurement nobody took."""

    def setUp(self):
        import guard_read_claims
        self.guard = guard_read_claims
        self.root = tempfile.mkdtemp(prefix="vb-claims-")
        self.addCleanup(shutil.rmtree, self.root, True)
        io.open(os.path.join(self.root, "a.py"), "w", encoding="utf-8", newline=LF).write(
            LF.join("line %d" % n for n in range(10)) + LF)

    def test_a_TRUE_claim_about_an_OPENED_file_passes(self):
        haystack = "read c:/x/a.py"
        self.assertEqual(self.guard.problems("see `a.py:7`", haystack, self.root), [])

    def test_a_line_PAST_the_end_is_refused(self):
        found = self.guard.problems("see [a](a.py:99)", "a.py", self.root)
        self.assertTrue(any("has 10 lines" in line for line in found), found)

    def test_a_claim_about_a_file_NOBODY_OPENED_is_refused(self):
        found = self.guard.problems("see `a.py:3`", "nothing relevant", self.root)
        self.assertTrue(any("NO tool call" in line for line in found), found)

    def test_a_WRONG_line_count_is_refused(self):
        found = self.guard.problems("a.py (lines 1-5 of 210)", "a.py", self.root)
        self.assertTrue(any("of 210" in line for line in found), found)

    def test_a_BARE_path_is_not_a_checkable_claim(self):
        """README: an unfalsifiable claim is not made falsifiable by pretending."""
        self.assertEqual(self.guard.problems("see a.py for details", "", self.root), [])


def installed_tool(rel):
    """Load a tool this bundle ships FROM WHERE THIS TREE KEEPS IT.

    A project may install `tools/x.py` somewhere else and declare it in
    `.claude/bundle-install.json`; importing it from `tools/` would test the bundle's idea of the
    layout instead of the project's copy. Read the declaration, load the file it names.
    """
    import importlib.util
    at = rel
    declared = os.path.join(HERE, ".claude", "bundle-install.json")
    if os.path.isfile(declared):
        with io.open(declared, encoding="utf-8") as handle:
            at = (json.load(handle).get(rel) or {}).get("at", rel)
    path = os.path.join(HERE, at.replace("/", os.sep))
    spec = importlib.util.spec_from_file_location(
        "installed_" + os.path.splitext(os.path.basename(at))[0], path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ARefusalMustBeAbleToPRINT(unittest.TestCase):
    """A REFUSAL THAT THROWS WHILE BUILDING ITS MESSAGE IS AN ALLOW. `"96% of ..." % NAME` made
    the Workflow refusal raise `TypeError` on every call - `% o` is a conversion - and a hook that
    exits 1 is a non-blocking error to the harness. `tools/format_strings.py` refuses the shape
    over the whole tree at commit; this asks it of the controls and of every hook and tool here."""

    def setUp(self):
        self.fs = installed_tool("tools/format_strings.py")

    def test_the_shipped_defect_and_its_siblings_are_found(self):
        for bad in ('x = "96% of %s" % name',
                    # THE CHEAPEST WAY PAST: split the literal across `+`. A banned UI label
                    # written `'... Swing '+'section'` once passed the test forbidding it.
                    'x = ("96% of " + "%s") % name',
                    'x = "%s and %s" % (a,)',
                    'x = "a {b} c".format(d=1)',
                    'x = "a { c".format()'):
            with self.subTest(source=bad):
                self.assertTrue(self.fs.offenders(bad), "a format that cannot print passed")

    def test_honest_formats_pass(self):
        for good in ('x = "96%% of %s" % name',
                     'x = "%d of %d" % (a, b)',
                     'x = "%(a)s" % {"a": 1}',
                     'x = fmt % args',
                     'def f(args):' + LF + '    return "%s %s" % args',
                     'x = "{b} {{c}}".format(b=1)'):
            with self.subTest(source=good):
                self.assertEqual(self.fs.offenders(good), [])

    def test_every_hook_and_tool_here_can_print_its_messages(self):
        found = []
        for folder in (HOOKS, TOOLS):
            for base, dirs, names in os.walk(folder):
                dirs[:] = [d for d in dirs if d != "__pycache__"]
                for name in names:
                    if name.endswith(".py"):
                        path = os.path.join(base, name)
                        with io.open(path, encoding="utf-8") as handle:
                            found += ["%s:%d %s" % (name, line, why) for line, why
                                      in self.fs.offenders(handle.read(), path)]
        self.assertEqual(found, [], "these formats throw instead of printing")


class AnOfferToDoTheWorkIsTheWorkNotDone(unittest.TestCase):
    """*When the class is understood, close it - do not stop and ask whether to.* `guard_promise`
    listed `want me to` and `should I` as BLOCKERS, and a claimed blocker exempts the whole
    message - so "I will fix it next. Want me to?" passed a guard that refused the same promise
    without the question. Measured over 1,535 real turn endings: 33 ended on an offer carrying
    neither a price nor a permission the rules require."""

    def setUp(self):
        import guard_promise
        self.guard = guard_promise

    def kinds(self, text):
        return [kind for kind, _sentence, _marker in self.guard.outstanding(text)]

    def test_an_offer_at_the_END_is_refused(self):
        for text in ("I found the defect in the recount. Want me to fix it?",
                     "The class is understood. Shall I build the guard?",
                     "Let me know if you'd like me to add the test.",
                     "If you want, I can build the guard."):
            with self.subTest(text=text):
                self.assertIn("offer", self.kinds(text), "an offer to do the work passed")

    def test_appending_a_question_does_not_LAUNDER_a_promise(self):
        self.assertIn("promise", self.kinds("I will fix the recount next. Want me to?"),
                      "two words appended laundered a promise")

    def test_an_offer_that_needs_a_yes_or_names_a_price_passes(self):
        for text in ("Built and tested. Want me to publish 2.9.4 to the release page?",
                     "The sweep needs 12 agents and about 2M tokens. Want me to proceed?",
                     "Fixed, planted, and green."):
            with self.subTest(text=text):
                self.assertNotIn("offer", self.kinds(text), "an honest ask was refused")

    def test_an_offer_after_a_LONG_report_is_still_the_end(self):
        """The cheapest way past a check on the end of a message: bury the offer under enough
        report that a window read from the start never reaches it."""
        text = ("Measured and recorded. " * 60) + "Want me to build the guard?"
        self.assertIn("offer", self.kinds(text),
                      "an offer at the end of a long message passed")


class ACounterfactualIsNotAPromise(unittest.TestCase):
    """`the moment` refused *the sync would have made them stale the moment it landed* - written
    on 2026-09-24 to explain why two runs had just been stopped. A conditional perfect is about a
    road not taken, not work to come; a guard that refuses the explanation of what it prevented is
    one people learn to phrase around."""

    def setUp(self):
        import guard_promise
        self.guard = guard_promise

    def test_a_COUNTERFACTUAL_is_not_a_promise(self):
        """Every forward marker is judged at one site, so the exemption is asked of all of them -
        `after that` here as well as `the moment`."""
        for text in ("So the sync would have made them stale the moment it landed.",
                     "Left running, the derive could have recorded a map the moment it changed.",
                     "That plant might have reddened the moment the anchor moved.",
                     "Without the lock, a second derive could have started after that."):
            with self.subTest(text=text):
                self.assertEqual(self.guard.outstanding(text), [],
                                 "a counterfactual was refused as a promise: " + text)

    def test_a_counterfactual_in_ANOTHER_CLAUSE_does_not_launder_a_promise(self):
        """The half that keeps the exemption from being a hole: the counterfactual must govern the
        marker's OWN clause. A `would have` before a semicolon or a comma is a separate thought,
        and the promise after it is still a promise."""
        for text in ("That would have been faster; the moment the sync lands the derive restarts.",
                     "It would have been cleaner, so the moment it lands I rerun the derive.",
                     "The derive restarts the moment the sync lands."):
            with self.subTest(text=text):
                self.assertTrue(self.guard.outstanding(text),
                                "a counterfactual in another clause laundered a promise: " + text)


def console_keepers(source):
    """Lines that start a child, CAPTURE its output, and still let Windows give it a console.

    Captured by `capture_output=True` OR by a `PIPE` on stdout/stderr - the second spelling is
    the cheapest way past a check that asks only about the first."""
    starters = {"subprocess.run", "subprocess.Popen", "subprocess.check_output",
                "subprocess.call", "subprocess.check_call"}
    out = []
    for node in ast.walk(ast.parse(source)):
        if isinstance(node, ast.Call) and ast.unparse(node.func) in starters:
            given = {k.arg: ast.unparse(k.value) for k in node.keywords if k.arg}
            captured = (given.get("capture_output") == "True"
                        or "PIPE" in given.get("stdout", "") or "PIPE" in given.get("stderr", ""))
            if captured and "creationflags" not in given:
                out.append(node.lineno)
    return out


class ACapturedChildOpensNoWINDOW(unittest.TestCase):
    """The project this was first installed into refuses a suite that blinks console windows,
    and five of the bundle's own calls did on the day they arrived - two in `require_build`,
    which runs `git` beside every recorded measurement. Asked of every hook and tool here and
    of this file."""

    def test_the_rule_sees_both_spellings_of_a_capture(self):
        self.assertEqual(console_keepers("subprocess.run(['a'], capture_output=True)"), [1])
        self.assertEqual(console_keepers("subprocess.run(['a'], stdout=subprocess.PIPE)"), [1])
        self.assertEqual(console_keepers(
            "subprocess.run(['a'], capture_output=True, creationflags=X)"), [])

    def test_no_captured_child_here_opens_a_window(self):
        found = []
        paths = [os.path.abspath(__file__)]
        for folder in (HOOKS, TOOLS):
            for base, dirs, names in os.walk(folder):
                dirs[:] = [d for d in dirs if d != "__pycache__"]
                paths += [os.path.join(base, n) for n in names if n.endswith(".py")]
        for path in paths:
            with io.open(path, encoding="utf-8") as handle:
                found += ["%s:%d" % (os.path.relpath(path, HERE), line)
                          for line in console_keepers(handle.read())]
        self.assertEqual(found, [], "these capture a child's output and still give it a "
                                    "console window: pass creationflags=NO_WINDOW")


if __name__ == "__main__":
    unittest.main()
