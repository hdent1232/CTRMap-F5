# -*- coding: utf-8 -*-
"""THE MACHINE THE MACHINE GUARDS WATCH - a mutation sweep any project can fit to itself.

A test suite that passes says the code does what the tests check. It does not say the tests check
anything: the project this bundle came from measured two modules split out of one function at
94.7% and 37.3% - the only difference was that tests were written for one of them. A sweep says
which: it makes one deliberate defect at a time - a comparison flipped, a condition dropped, a
constant moved, a return emptied - in a checkout of its own, runs the project's tests against
it, and records whether they noticed. A defect the tests do not notice (`survived`) is a test
nobody has written yet, named by file, line and operator.

THREE GUARDS IN THIS BUNDLE WATCH A SWEEP, and until 2026-10-09 they shipped into projects with
no sweep to watch - reported by the CTRMap session (its audit F170): nothing in that project wrote
`.sweep/`, so they ran on every call and could never fire. The owner's decision: ship a sweep any
project can fit. This is it, and it writes exactly what they read:

    .sweep/running             the run's lock, {"pid": ...}   guard_machine_worktrees
    .sweep/tree-N              the worktrees it mutates in    guard_machine_worktrees
    .sweep/ledger-*.jsonl      a verdict per mutation site    guard_idle_machine
    .sweep/derive-progress.json  after every mutant           guard_idle_machine
    .sweep/blocked.json        waiting on uncommitted tooling guard_blocked_runner
    .sweep/STOP                stopped on purpose             guard_idle_machine
    .sweep/complete.json       every site at HEAD weighed     guard_idle_machine

A FINISHED SWEEP IS NOT A STALLED ONE, and the idle guard is told which: a pass that leaves no
site in scope without a verdict writes `complete.json` naming the commit, and the guard reads idle
at that commit as nothing left to weigh. A pass that stopped, was limited, or met a red baseline
or a suite that could not run does not - what it left is still unweighed. Its progress file is
removed when a run ends, however it ends: a progress file left behind reads as a job that stopped
advancing, and one did, for seven hours, on 2026-09-24.

A worktree file it has mutated is under the worktree's own mutation lock, so a session reading it
is refused as it is for a planted file (`guard_mutation_read`).

    python -B tools/sweep.py run [--module <path>] [--limit N] [--wait]
    python -B tools/sweep.py status
    python -B tools/sweep.py stop

WHAT A VERDICT IS. `killed`: the tests failed on the mutant. `survived`: they passed. `timeout`:
they did not finish - recorded apart, never read as either. `unknown`: they could not be run.
Before a module's first mutant its UNMUTATED code is run the way every mutant will be - parsed and
unparsed - and a module whose suite is red then is recorded `baseline-red` and not mutated: a
mutant killed by a suite that was already failing proves nothing.

FIT IT TO A PROJECT through ADAPT: which files are mutated (`SCOPE`), how the tests run
(`TEST_COMMAND`), how long one run may take (`TEST_TIMEOUT`), and which files a run must not start
with uncommitted (`TOOLING`) - a sweep whose own code is uncommitted changes what every verdict
means.
"""
import ast
import io
import json
import os
import re
import subprocess
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there
import time

LF = chr(10)
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0
HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import _tree_root        # noqa: E402  - the project root, anchored: never counted (F216)
import bundle_hooks      # noqa: E402  - where the bundle's hooks are, found rather than spelled
sys.path.insert(0, bundle_hooks.path(_tree_root.require(__file__)))
import bundle_rules      # noqa: E402  - the one mutation lock, taken and given back
import bundle_shell      # noqa: E402  - the one probe of whether a process is alive
import bounded         # noqa: E402  - a deadline ends the child's whole tree

#: Adapted per project: see ADAPT.md.
ADAPT = ("SCOPE", "TEST_COMMAND", "TEST_TIMEOUT", "TOOLING", "STATE", "POLL")

#: The files a sweep mutates, as a pattern over paths git knows, from the project root: the
#: project's Python, not its tests, not `tools/` - where this bundle's own tools are installed -
#: and nothing under a dot-folder.
SCOPE = r"^(?!tests/)(?!tools/)(?!\.)[^/].*\.py$"

#: How the tests run, inside the worktree. `{python}` is this interpreter.
TEST_COMMAND = ("{python}", "-B", "-m", "unittest", "discover", "-s", "tests", "-t", ".")

#: Seconds one run of the tests may take before its verdict is `timeout`.
TEST_TIMEOUT = 600

#: Files a run must not start with uncommitted: the sweep's own code, which every verdict depends on.
TOOLING = ("tools/sweep.py",)

#: Where the sweep keeps its state, from the project root.
STATE = ".sweep"

#: Seconds between looks at uncommitted tooling while a run `--wait`s.
POLL = 300

GIT_TIMEOUT = 120

#: What a finished pass writes, and the verdicts that leave a site weighed. `timeout` is recorded
#: apart and weighed nothing either way, but asking again would ask the same; `unknown` and a red
#: baseline leave a site unweighed, and a pass that meets one is not finished.
COMPLETE = "complete.json"
WEIGHED = frozenset(("killed", "survived", "timeout", "unchanged"))

#: Each operator, and what it turns into.
SWAPS = {ast.Eq: ast.NotEq, ast.NotEq: ast.Eq, ast.Lt: ast.GtE, ast.GtE: ast.Lt, ast.Gt: ast.LtE,
         ast.LtE: ast.Gt, ast.In: ast.NotIn, ast.NotIn: ast.In, ast.Is: ast.IsNot,
         ast.IsNot: ast.Is}


def root():
    """The repository this file sits in, ANCHORED - `_tree_root.repo_root`, the one definition
    (F216): this walked to `.git` itself, a second walk `counted_roots.py` refuses."""
    return _tree_root.repo_root(__file__)


def git(where, *args, **kw):
    try:
        return bounded.run(["git", "-C", where] + list(args), capture_output=True,
                           timeout=GIT_TIMEOUT, creationflags=NO_WINDOW, **kw)
    except (OSError, subprocess.SubprocessError) as exc:
        return subprocess.CompletedProcess(args, 127, b"", str(exc).encode("utf-8"))


# ----------------------------------------------------------------------------- mutants

class _Mutator(ast.NodeTransformer):
    """Counts every mutation site in source order and changes the one numbered `target`."""

    def __init__(self, target=-1):
        self.target, self.sites = target, []

    def _site(self, node, op):
        self.sites.append((getattr(node, "lineno", 0), getattr(node, "col_offset", 0), op))
        return len(self.sites) - 1 == self.target

    def visit_Compare(self, node):
        self.generic_visit(node)
        for at, op in enumerate(node.ops):
            swapped = SWAPS.get(type(op))
            if swapped and self._site(node, "%s->%s" % (type(op).__name__, swapped.__name__)):
                node.ops[at] = swapped()
        return node

    def visit_BoolOp(self, node):
        self.generic_visit(node)
        other = ast.Or if isinstance(node.op, ast.And) else ast.And
        if self._site(node, "%s->%s" % (type(node.op).__name__, other.__name__)):
            node.op = other()
        return node

    def visit_UnaryOp(self, node):
        self.generic_visit(node)
        if isinstance(node.op, ast.Not) and self._site(node, "not-dropped"):
            return node.operand
        return node

    def visit_Constant(self, node):
        if isinstance(node.value, bool):
            if self._site(node, "%s->%s" % (node.value, not node.value)):
                return ast.copy_location(ast.Constant(value=not node.value), node)
        elif isinstance(node.value, int):
            if self._site(node, "%d->%d" % (node.value, node.value + 1)):
                return ast.copy_location(ast.Constant(value=node.value + 1), node)
        return node

    def visit_Return(self, node):
        self.generic_visit(node)
        empty = node.value is None or (isinstance(node.value, ast.Constant)
                                       and node.value.value is None)
        if not empty and self._site(node, "return->None"):
            node.value = ast.Constant(value=None)
        return node


def sites(source):
    """[(line, column, operator)] of every mutation site in `source`, in the order `mutant`
    numbers them - [] for source that will not parse."""
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return []
    mutator = _Mutator()
    mutator.visit(tree)
    return mutator.sites


def unparsed(source):
    """`source` as the sweep runs it unmutated: parsed and unparsed, so the baseline and every
    mutant are the same text but for the one defect."""
    return ast.unparse(ast.parse(source))


def mutant(source, index):
    """`source` with mutation site `index` applied, unparsed - None when there is no such site or
    the mutation changes nothing a reader of the unparsed text could see."""
    tree = ast.parse(source)
    mutator = _Mutator(index)
    tree = ast.fix_missing_locations(mutator.visit(tree))
    if index >= len(mutator.sites):
        return None
    text = ast.unparse(tree)
    return None if text == unparsed(source) else text


# ----------------------------------------------------------------------------- state

def state(where, *parts):
    return os.path.join(where, STATE, *parts)


def ledger_path(where, rel):
    return state(where, "ledger-%s.jsonl" % rel.replace("/", "__"))


def recorded(where, rel):
    """{(blob, line, column, operator): verdict} already in `rel`'s ledger."""
    out = {}
    try:
        with io.open(ledger_path(where, rel), encoding="utf-8") as handle:
            for line in handle:
                try:
                    held = json.loads(line)
                except ValueError:
                    continue
                out[(held.get("blob"), held.get("line"), held.get("col"), held.get("op"))] = \
                    held.get("verdict")
    except OSError:
        pass
    return out


def record(where, rel, entry):
    os.makedirs(state(where), exist_ok=True)
    with io.open(ledger_path(where, rel), "a", encoding="utf-8", newline=LF) as handle:
        handle.write(json.dumps(entry, sort_keys=True) + LF)


def progress(where, **fields):
    os.makedirs(state(where), exist_ok=True)
    fields.update(pid=os.getpid(), started_epoch=bundle_shell.process_started_at(os.getpid()), at=time.time())
    with io.open(state(where, "derive-progress.json"), "w", encoding="utf-8", newline=LF) as h:
        h.write(json.dumps(fields, sort_keys=True) + LF)


def take_run_lock(where):
    """None, or why a run cannot start: another LIVE run holds the lock - or one that cannot be
    told dead, which is the same answer (`bundle_shell.alive` says True when it cannot tell)."""
    path = state(where, "running")
    os.makedirs(state(where), exist_ok=True)
    try:
        with io.open(path, encoding="utf-8") as handle:
            held = json.load(handle)
    except FileNotFoundError:
        held = None
    except (OSError, ValueError):
        return "%s is there and cannot be read - which run holds it is UNKNOWN" % path
    if isinstance(held, dict) and bundle_shell.alive(held.get("pid"), held.get("started_epoch")):
        return "a sweep is already running as pid %s" % held.get("pid")
    with io.open(path, "w", encoding="utf-8", newline=LF) as handle:
        handle.write(json.dumps({"pid": os.getpid(), "since": time.time(),
                                 "started_epoch": bundle_shell.process_started_at(os.getpid())}) + LF)
    return None


def give_back_run_lock(where):
    try:
        os.remove(state(where, "running"))
    except OSError:
        pass


def uncommitted(where, paths):
    """Which of `paths` differ from HEAD - all of them when git cannot say."""
    done = git(where, "status", "--porcelain", "--", *paths)
    if done.returncode != 0:
        return list(paths)
    dirty = {line[3:].strip().strip('"') for line in
             done.stdout.decode("utf-8", "replace").splitlines() if len(line) > 3}
    return [p for p in paths if p in dirty]


# ----------------------------------------------------------------------------- the run

def in_scope(where):
    """Every file git tracks that `SCOPE` names - None when git cannot list them."""
    done = git(where, "ls-files", "-z")
    if done.returncode != 0:
        return None
    pattern = re.compile(SCOPE)
    return sorted(rel for rel in done.stdout.decode("utf-8", "replace").split(chr(0))
                  if rel and pattern.search(rel))


def worktree(where, head, number=0):
    """`.sweep/tree-<number>`, checked out at `head` - created the first time."""
    tree = state(where, "tree-%d" % number)
    if not os.path.isdir(tree):
        done = git(where, "worktree", "add", "--detach", tree, head)
        if done.returncode != 0:
            raise RuntimeError("the worktree %s could not be made: %s"
                               % (tree, done.stderr.decode("utf-8", "replace").strip()))
    done = git(tree, "checkout", "-f", "--detach", head)
    if done.returncode != 0:
        raise RuntimeError("the worktree %s could not be put at %s" % (tree, head))
    return tree


def run_tests(tree):
    """(verdict, seconds) of the project's tests run in `tree` as it now stands."""
    argv = [part.replace("{python}", sys.executable) for part in TEST_COMMAND]
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
    began = time.time()
    try:
        done = bounded.run(argv, cwd=tree, capture_output=True, timeout=TEST_TIMEOUT, env=env,
                           creationflags=NO_WINDOW)
    except subprocess.TimeoutExpired:
        return "timeout", time.time() - began
    except OSError:
        return "unknown", time.time() - began
    return ("survived" if done.returncode == 0 else "killed"), time.time() - began


def _put(tree, rel, text):
    with io.open(os.path.join(tree, *rel.split("/")), "w", encoding="utf-8", newline=LF) as h:
        h.write(text)


def sweep_module(where, tree, head, rel, limit=None):
    """Every mutant of `rel` not already in its ledger at this blob: [(site, verdict)]."""
    blob = git(where, "rev-parse", "%s:%s" % (head, rel)).stdout.decode().strip()
    original = git(where, "show", "%s:%s" % (head, rel)).stdout.decode("utf-8", "replace")
    found = sites(original)
    held = recorded(where, rel)
    # A SITE WHOSE TESTS COULD NOT BE RUN IS ASKED AGAIN: `unknown` is no verdict.
    todo = [(at, site) for at, site in enumerate(found)
            if held.get((blob,) + site) in (None, "unknown")]
    if limit is not None:
        todo = todo[:limit]
    if not todo:
        return []
    lock = bundle_rules.take_lock(tree, rel)
    if lock:
        raise RuntimeError(lock)
    out = []
    try:
        _put(tree, rel, unparsed(original))
        verdict, seconds = run_tests(tree)
        progress(where, module=rel, done=0, total=len(todo), last="baseline " + verdict)
        if verdict != "survived":
            record(where, rel, {"module": rel, "blob": blob, "head": head, "line": 0, "col": 0,
                                "op": "baseline", "verdict": "baseline-" + verdict,
                                "seconds": round(seconds, 2), "at": time.time()})
            return [(("baseline",), "baseline-" + verdict)]
        for done_count, (at, (line, col, op)) in enumerate(todo, 1):
            if os.path.exists(state(where, "STOP")):
                break
            text = mutant(original, at)
            if text is None:
                verdict, seconds = "unchanged", 0.0     # recorded, so it is not asked forever
            else:
                _put(tree, rel, text)
                verdict, seconds = run_tests(tree)
            record(where, rel, {"module": rel, "blob": blob, "head": head, "line": line,
                                "col": col, "op": op, "verdict": verdict,
                                "seconds": round(seconds, 2), "at": time.time()})
            out.append(((line, col, op), verdict))
            progress(where, module=rel, done=done_count, total=len(todo),
                     last="%s:%d %s %s" % (rel, line, op, verdict))
    finally:
        _put(tree, rel, original)
        trouble = bundle_rules.release_lock(tree)
        if trouble:
            sys.stderr.write("the worktree's lock: %s%s" % (trouble, LF))
    return out


def run(where, module=None, limit=None, wait=False):
    """Exit code of one sweep."""
    if os.path.exists(state(where, "STOP")):
        print("REFUSING: %s says the sweep is stopped on purpose - remove it to run" %
              state(where, "STOP"))
        return 2
    dirty = uncommitted(where, TOOLING)
    while dirty:
        if not wait:
            print("REFUSING: the sweep's own tooling is uncommitted (%s) - every verdict depends "
                  "on it, so commit it first, or run with --wait" % ", ".join(dirty))
            return 2
        os.makedirs(state(where), exist_ok=True)
        with io.open(state(where, "blocked.json"), "w", encoding="utf-8", newline=LF) as h:
            h.write(json.dumps({"pid": os.getpid(), "since": time.time(), "paths": dirty,
                                "started_epoch": bundle_shell.process_started_at(os.getpid())}) + LF)
        time.sleep(POLL)
        dirty = uncommitted(where, TOOLING)
    try:
        os.remove(state(where, "blocked.json"))
    except OSError:
        pass
    held = take_run_lock(where)
    if held:
        print("REFUSING: %s" % held)
        return 2
    try:
        head = git(where, "rev-parse", "HEAD").stdout.decode().strip()
        scope = in_scope(where)
        if scope is None:
            print("REFUSING: git cannot list the files in %s - which are in scope is UNKNOWN"
                  % where)
            return 2
        if module:
            scope = [rel for rel in scope if rel == module]
        if not scope:
            print("REFUSING: no file in scope (%s) - that is the wrong scope, not a clean one"
                  % (module or SCOPE))
            return 2
        tree = worktree(where, head)
        complete = module is None and limit is None
        for rel in scope:
            if os.path.exists(state(where, "STOP")):
                complete = False
                break
            for site, verdict in sweep_module(where, tree, head, rel, limit):
                print("%-9s %s %s" % (verdict, rel, ":".join(str(s) for s in site)))
                complete = complete and verdict in WEIGHED
        if os.path.exists(state(where, "STOP")):
            complete = False
        if complete:
            with io.open(state(where, COMPLETE), "w", encoding="utf-8", newline=LF) as handle:
                handle.write(json.dumps({"head": head, "at": time.time(),
                                         "modules": len(scope)}, sort_keys=True) + LF)
            print("every site in scope at %s has a verdict - %s" % (head[:12], COMPLETE))
    finally:
        give_back_run_lock(where)
        try:
            os.remove(state(where, "derive-progress.json"))
        except OSError:
            pass
    return 0


def status(where):
    """Lines: per module at its latest blob, the verdicts and the survivors."""
    out = []
    for rel in in_scope(where) or []:
        held = {}
        try:
            with io.open(ledger_path(where, rel), encoding="utf-8") as handle:
                rows = [json.loads(line) for line in handle if line.strip()]
        except (OSError, ValueError):
            continue
        blob = rows[-1].get("blob") if rows else None
        for row in rows:
            if row.get("blob") == blob:
                held[(row.get("line"), row.get("col"), row.get("op"))] = row.get("verdict")
        counts = {}
        for verdict in held.values():
            counts[verdict] = counts.get(verdict, 0) + 1
        weighed = counts.get("killed", 0) + counts.get("survived", 0)
        score = "%.1f%%" % (100.0 * counts.get("killed", 0) / weighed) if weighed else "-"
        out.append("%s  %s  %s" % (rel, score, ", ".join("%s %d" % kv
                                                          for kv in sorted(counts.items()))))
        out += ["    survived  line %s col %s  %s" % site
                for site, verdict in sorted(held.items()) if verdict == "survived"]
    return out


def main(argv):
    import cli_words      # a word this does not read refuses the run, before anything runs
    why = cli_words.refused(argv[1:], flags=("--module", "--limit", "--wait"), valued=("--module", "--limit"), most=1)
    if why:
        print(why)
        return 2
    where = root()
    if where is None:
        print("REFUSING: no `.git` above %s - there is no tree to sweep" % HERE)
        return 2
    words = argv[1:]
    if not words or words[0] not in ("run", "status", "stop"):
        print("REFUSING: say what to do - run, status or stop")
        return 2
    if words[0] == "stop":
        os.makedirs(state(where), exist_ok=True)
        with io.open(state(where, "STOP"), "w", encoding="utf-8", newline=LF) as handle:
            handle.write("stopped on purpose at %s%s" % (time.ctime(), LF))
        print("the sweep stops after the mutant it is on")
        return 0
    if words[0] == "status":
        lines = status(where)
        print(LF.join(lines) if lines else "no verdict recorded yet")
        return 0
    try:
        module = words[words.index("--module") + 1] if "--module" in words else None
        limit = int(words[words.index("--limit") + 1]) if "--limit" in words else None
    except (IndexError, ValueError):
        print("REFUSING: --module takes a path and --limit a number")
        return 2
    return run(where, module, limit, "--wait" in words)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
