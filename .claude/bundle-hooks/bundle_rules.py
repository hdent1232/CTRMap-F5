# -*- coding: utf-8 -*-
"""THE RULES A PROJECT DECLARES, FOUND AND ASKED AT THE POINT OF ACTION. ONE IMPLEMENTATION.

WHY THIS FILE EXISTS, measured on the project this bundle was installed into first. Its tier
record called fourteen class checkers "at the point of action" because each declared a marker its
write channel asked. The channel carried SCRIPTED edits only; the Edit and Write tools - the way
nearly every line is actually written - went straight to disk and met those rules at the commit.
Thirty-three more sat at the commit or in the suite with a paragraph each explaining why they
could not be asked earlier, and most of those paragraphs were wrong: *its subject is tree-wide*,
*it reads a record*, *its subject is tests/* - every one of those is a fact about a SET OF FILES,
and a write changes exactly one file of the set.

A RULE IS ASKED WHERE ITS SUBJECT CHANGES. A checker module declares, at module level, which acts
it has a verdict on, by naming its own entry points - discovered, never listed:

    AT_WRITE      = "fn"                 fn(rel, text) -> [finding]
                                         one file, as the write would leave it
    AT_WRITE_TREE = ("facts", "judge")   facts(rel, text) -> JSON-able facts about ONE file
                                         judge({rel: facts}) -> [finding] over the whole scope
    AT_RECORD     = "fn"                 fn(path, payload) -> [finding]
                                         a measurement record, as the write would leave it -
                                         asked only of a `.json` in its scope (`SHAPES`), so one
                                         AT_SCOPE may name a rule's tests and its record both
    AT_WRITE_CHANGE = "fn"               fn(rel, before, after, root) -> [finding]
                                         what the write CHANGES, when answering needs a RUN -
                                         the tests a write adds or alters, run as they would be
    AT_COMMAND    = "fn"                 fn(command, root) -> [finding]
                                         one shell command, before it runs
    AT_COMMIT     = "fn"                 fn(message, root) -> [finding]
                                         a commit message, before git is started
    AT_SCOPE      = r"<regex>"           the repo-relative paths the three WRITE kinds are asked
                                         about. Defaults below; a TREE rule must say.

WHAT EACH IS FOR, and the one that needed inventing is the second. A verdict that depends on more
than one file - "a constant NOTHING reads", "a field that is a state flag ANYWHERE is not a
failure field", "every guard's wiring" - was the excuse for asking at the commit. It is not an
excuse: the verdict is a function of the files in scope, the write replaces one of them, and the
judge can be asked about the tree as it WOULD be. What made it look impossible was the price -
re-reading the whole scope on every edit was measured at 4 to 10 seconds per checker. So `facts`
is asked of one file at a time and CACHED against that file's size and modification time, and a
write re-reads only the file being written. The judge sees every other file's facts from the
cache and the proposed file's facts fresh.

EVERY WRITE KIND REFUSES GROWTH, NOT EXISTENCE. A file, a tree or a record that already carries
debt can still be written, as long as the write adds none. A command and a message have no
"before", so those two refuse any finding - and so does a CHANGE rule, whose findings are about
the change already. That kind exists because some verdicts need a run: whether a test passes
alone, whether it executes the line it names, whether it kills the mutant it claims. Running the
whole suite at a write is not possible; running the tests the write touches is, and it is the
only point at which the answer arrives before the test is built on.

A RULE THAT CANNOT ANSWER IS NOT A PASS. A checker that will not load, is declared wrongly, or
raises, refuses the call - except a call writing into `RULE_DIRS` or the hooks directory, because
the only way to repair a broken checker is to write to it, and a guard that blocks the way out of
the situation it describes is worse than none (`dispatch.py` learned that by wedging a session).

    python .claude/hooks/bundle_rules.py            every rule, its kind and its scope
    python .claude/hooks/bundle_rules.py warm       build every tree rule's fact cache now
"""
import ast
import hashlib
import importlib
import importlib.util
import io
import json
import os
import re
import subprocess
import sys
import time
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundle_env        # noqa: E402  - one PREFIX renames every environment name

LF = chr(10)

#: Set in the environment while a process judges a tree, naming that tree - so a process a rule
#: starts, and every process under it, cannot judge the SAME tree again inside the judgement.
JUDGING = bundle_env.name("JUDGING")

#: Where a project keeps checkers that declare a marker, relative to its root.
RULE_DIRS = ("tools",)

#: Source files a WRITE rule is asked about when it does not say: every `.py` outside `tests/`,
#: whose fixtures carry the defects plants are made of. A rule whose subject IS tests says so in
#: its own `AT_SCOPE`.
SOURCE_SCOPE = r"^(?!tests/)(?!\.\./)[^/].*\.py$"

#: Records a RECORD rule is asked about when it does not say.
RECORD_SCOPE = r"^tests/[^/]+\.json$"

#: What a kind can JUDGE, by the file's shape - asked ON TOP of the rule's own scope. One module
#: may declare several kinds over ONE `AT_SCOPE` naming its test files AND its record, and a
#: record rule asked of a `.py` can only say "not JSON": measured 2026-09-23, that refused every
#: edit to every Layer 1 test file in the project this came from, and every edit to its store.
SHAPES = {"AT_RECORD": r"\.json$"}

#: The lock a mutation run holds, naming the module it has replaced with rewritten output. That
#: module is neither asked about nor read into a fact: what is on disk is not what it says.
LOCK = ".mutation-in-flight"

#: Where tree facts are cached. The directory ignores itself, so no project has to be told to.
CACHE = os.path.join(".claude", "rules-cache")

#: The hooks directory, a repair route like `RULE_DIRS`: the folder this file sits in, read rather
#: than spelled, because a project may keep the bundle's hooks beside a stack of its own
#: (`_hooks_at` in `.claude/bundle-install.json`).
HOOKS = os.path.join(".claude", os.path.basename(os.path.dirname(os.path.abspath(__file__))))

#: Adapted per project: see ADAPT.md.
ADAPT = ("RULE_DIRS", "SOURCE_SCOPE", "RECORD_SCOPE", "LOCK", "CACHE", "HOOKS")

MARKERS = ("AT_WRITE", "AT_WRITE_TREE", "AT_WRITE_CHANGE", "AT_RECORD", "AT_COMMAND",
           "AT_COMMIT", "AT_AFTER")
WRITE_KINDS = ("AT_WRITE", "AT_WRITE_TREE", "AT_WRITE_CHANGE", "AT_RECORD")

#: Which hook asks which marker. `tiers` reads this to decide whether a declared rule is really at
#: the point of action: a marker nothing dispatched asks is a declaration, not a refusal.
#:
#: `AT_AFTER` is the one kind asked AFTER the act, and it is the honest name for what it is. Some
#: verdicts are a command's exit code over the tree ON DISK - a ledger item that is closed when a
#: command says so - and before a write lands the disk is not the tree the write would leave, so
#: nothing can ask them first. The earliest they can be asked is the moment after, and a finding
#: there is handed straight back to the session that made the change, before anything else runs.
ASKED_BY = {"AT_WRITE": "guard_write_rules.py", "AT_WRITE_TREE": "guard_write_rules.py",
            "AT_WRITE_CHANGE": "guard_write_rules.py", "AT_RECORD": "guard_write_rules.py",
            "AT_COMMAND": "guard_command_rules.py", "AT_COMMIT": "guard_command_rules.py",
            "AT_AFTER": "after_rules.py"}

_DECLARES = re.compile(r"^(?:%s|AT_SCOPE)\s*=" % "|".join(MARKERS), re.M)

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0


def repo_root(start=None):
    """The project root, ANCHORED on `.git` or `CLAUDE.md` - never counted in `dirname`s."""
    here = os.path.dirname(os.path.abspath(start or __file__))
    while True:
        if os.path.exists(os.path.join(here, ".git")) or os.path.isfile(
                os.path.join(here, "CLAUDE.md")):
            return here
        parent = os.path.dirname(here)
        if parent == here:
            return None
        here = parent


def rel_of(root, path):
    return os.path.relpath(os.path.abspath(path), root).replace(os.sep, "/")


def locked(root, name=None):
    """The repo-relative module a mutation run holds, or ''. `name` is the lock's file name when
    the caller reads another project's lock, whose `LOCK` ADAPT.md lets it rename."""
    try:
        with io.open(os.path.join(root, name or LOCK), encoding="utf-8") as handle:
            held = handle.read().strip()
    except OSError:
        return ""
    if not held:
        return ""
    first = held.splitlines()[0].strip().replace(chr(92), "/")
    if os.path.isabs(first):
        first = rel_of(root, first)
    return first


# ----------------------------------------------------------------------------- the lock's door

# ONE READING OF THE LOCK SAYS NOTHING ABOUT A FILE READ AT ANOTHER MOMENT. Measured 2026-09-26 on
# the project this came from: a commit's gate re-drove 49 plants in one file back to back, and
# another session's judgement read the status during one plant, the lock in the gap between two
# and the file during the next - so the planted text was HELD as a command's write, with no lock
# in sight. A rule also reads more than the file it is asked about, and a plant can come and go
# entirely inside one judgement: no lock at its start, none at its end, a mutant read in between.
# So every release is COUNTED, and two readings of `lock_state` around a stretch of work that are
# equal and name no file are the only evidence that no mutation run touched it.

#: How many times the lock has come off, inside the cache that ignores itself.
RELEASED = "lock-released"


def _released(root):
    try:
        with io.open(os.path.join(root, CACHE, RELEASED), encoding="utf-8") as handle:
            return handle.read().strip()
    except OSError:
        return ""


def lock_state(root):
    """(the file a mutation run's lock names or '', the count of its releases) - one reading."""
    return locked(root), _released(root)


#: The lock each run in THIS process took, by tree: the nonce on the lock's second line.
_TAKEN = {}


def take_lock(root, rel):
    """Take the lock naming `rel`: None, or why it could not be taken. Created EXCLUSIVELY, so two
    runs cannot both believe they hold it, and SIGNED with a nonce on its second line (`locked`
    reads only the first), so a release removes only the lock its own run took."""
    path = os.path.join(root, LOCK)
    nonce = "%d-%s" % (os.getpid(), hashlib.sha256(os.urandom(16)).hexdigest()[:16])
    try:
        with io.open(path, "x", encoding="utf-8", newline=LF) as handle:
            handle.write(rel + LF + nonce + LF)
    except FileExistsError:
        held = locked(root)
        return ("a mutation or plant run already holds %s on %s - if none is running it was "
                "killed: put %s back, then delete %s" % (LOCK, held or "no named file",
                                                        held or "that file", LOCK))
    except OSError as exc:
        return "%s could not be taken (%s)" % (LOCK, exc)
    _TAKEN[_tree_key(root)] = nonce
    return None


def note_release(root, attempts=5, pause=0.05):
    """Count one release of the lock: None, or why it could not be counted.

    Called BEFORE the lock file goes, so a judgement ending in between sees the lock or the new
    count, never neither. Retried: on Windows a reader holding the count for a moment refuses the
    replace, and a release nobody counted is a plant a judgement can straddle unseen.
    """
    why = None
    for attempt in range(attempts):
        try:
            target = os.path.join(_cache_folder(root), RELEASED)
            try:
                count = int(_released(root) or "0")
            except ValueError:
                count = 0
            temp = "%s.%d.tmp" % (target, os.getpid())
            with io.open(temp, "w", encoding="utf-8", newline=LF) as handle:
                handle.write("%d%s" % (count + 1, LF))
            os.replace(temp, target)
            return None
        except OSError as exc:
            why = "%s: %s" % (type(exc).__name__, exc)
            time.sleep(pause * (attempt + 1))
    return "the release of %s could not be counted (%s)" % (LOCK, why)


def release_lock(root, attempts=5, pause=0.1):
    """Count the release, THEN remove the lock: None, or why it still stands.

    A release that could not be counted keeps the lock - the file is back, and a lock left standing
    costs a refusal that names it, while an uncounted release costs a verdict taken beside a mutant.

    ONLY THE LOCK THIS RUN TOOK. The refusal above tells a person to delete a lock a killed run
    left, and a person can be wrong about which run is dead: the next run takes a fresh lock, and
    the first one's release would remove it while the second is mutating. A lock that is GONE, or
    signed by another run, is left as it is and returned as the reason - "already gone" is not
    "released by me". Found by the CTRMap session reading this door, 2026-09-26.
    """
    path = os.path.join(root, LOCK)
    try:
        with io.open(path, encoding="utf-8") as handle:
            lines = handle.read().splitlines()
    except FileNotFoundError:
        return ("the lock this run took (%s) is GONE - something else removed it while the run "
                "held it, so which run holds the tree now is UNKNOWN" % path)
    except OSError as exc:
        return "%s could not be read to release it (%s)" % (LOCK, exc)
    mine = _TAKEN.get(_tree_key(root))
    if not mine or len(lines) < 2 or lines[1].strip() != mine:
        return ("%s (on %s) is not the lock this run took - it is left in place"
                % (LOCK, lines[0].strip() if lines else "no named file"))
    why = note_release(root)
    if why:
        return why
    for attempt in range(attempts):
        try:
            os.remove(path)
            _TAKEN.pop(_tree_key(root), None)
            return None
        except FileNotFoundError:
            return ("the lock this run took (%s) went while it was being released - something "
                    "else removed it" % path)
        except OSError as exc:
            why = "%s: %s" % (type(exc).__name__, exc)
            time.sleep(pause * (attempt + 1))
    return "%s could not be removed (%s)" % (LOCK, why)


#: The scope of a rule whose `AT_SCOPE` is an expression rather than a literal: resolved by
#: importing the checker the first time a write asks.
COMPUTED = object()


class Rule(object):
    """One declared rule: which module, which kind, which entry point, which scope."""

    def __init__(self, name, path, kind, entry, scope, error=""):
        self.name, self.path, self.kind = name, path, kind
        self.entry, self.scope, self.error = entry, scope, error
        self._module = None

    def watches(self, rel):
        if self.kind not in WRITE_KINDS or not self.scope:
            return False
        if self.kind in SHAPES and not re.search(SHAPES[self.kind], rel):
            return False
        if self.scope is COMPUTED:
            # A scope DERIVED from the checker's own constants - `DIRS`, a package list - is read
            # off the imported module, so the write and the command line cannot disagree about
            # which files are the rule's subject. A second spelling of the scope would be.
            try:
                held = getattr(self.module(), "AT_SCOPE", None)
            except BaseException:                       # noqa: BLE001 - reported when asked
                return True
            if not isinstance(held, str):
                return True
            self.scope = held
        try:
            return re.search(self.scope, rel) is not None
        except re.error:
            return False

    def module(self):
        if self._module is None:
            self._module = load(self.path)
        return self._module

    def call(self, name):
        target = getattr(self.module(), name, None)
        if not callable(target):
            raise LookupError("%s declares %s naming `%s`, which is not a function it defines"
                              % (self.name, self.kind, name))
        return target

    def __repr__(self):
        return "Rule(%s, %s, %r)" % (self.name, self.kind, self.entry)


def _literals(tree):
    """{name: literal value} for every top-level `NAME = <literal>` in a parsed module."""
    out = {}
    for node in tree.body:
        if not isinstance(node, ast.Assign) or len(node.targets) != 1:
            continue
        target = node.targets[0]
        if not isinstance(target, ast.Name):
            continue
        try:
            out[target.id] = ast.literal_eval(node.value)
        except (ValueError, SyntaxError, TypeError):
            out[target.id] = node
    return out


def load(path):
    """The module at `path`, imported by its plain name with its directory importable.

    By the PLAIN name so a channel that already imported a checker gets the same module back,
    and a checker's `import sibling` resolves exactly as it does from its own command line.
    """
    folder = os.path.dirname(os.path.abspath(path))
    stem = os.path.splitext(os.path.basename(path))[0]
    if folder not in sys.path:
        sys.path.insert(0, folder)
    held = sys.modules.get(stem)
    if held is not None and os.path.abspath(getattr(held, "__file__", "") or "") == \
            os.path.abspath(path):
        return held
    held_bytecode = sys.dont_write_bytecode
    sys.dont_write_bytecode = True
    try:
        if held is None:
            return importlib.import_module(stem)
        spec = importlib.util.spec_from_file_location("rules_" + stem, path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    finally:
        sys.dont_write_bytecode = held_bytecode


#: A string that names THE folder the bundle ships its hooks in - `.claude/hooks`, `.claude\\hooks`,
#: or a pattern for it such as `\.claude[/\\]hooks` - with nothing but separators between the two
#: words. A scratch tree a test builds at `.claude/bundle-hooks` or `.claude/moved` is a fixture,
#: not an assumption about where a project keeps them; the first version refused those too, and
#: fired on the test written to prove it.
HOOK_FOLDER = re.compile(r"claude[^A-Za-z0-9_-]{1,8}hooks")


def _joined(node):
    """The text of a `+` chain of string literals, flattened - or None if any part is not one."""
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
        left, right = _joined(node.left), _joined(node.right)
        if left is not None and right is not None:
            return left + right
    return None


def spelled_folders(source, allowed=()):
    """[(line, what)] for every place `source` SPELLS the folder the bundle ships its hooks in.

    MEASURED ON CTRMAP, 2026-09-24: a project with its own guard stack in `.claude/hooks` keeps
    the bundle's hooks beside it, and 45 places in the files a project installs spelled
    `.claude/hooks` - the dispatcher's repair route, 21 refusal messages, three tools and three
    tests - each one looking at the project's guards instead of the bundle's. A hook reads its
    folder from where it sits (`HOOKS`, `bundle_shell.FOLDER`).

    Asked of the AST, so a docstring saying where hooks usually live is prose, not a path. A `+`
    chain of literals is FLATTENED first - `".claude/" + "hooks"` is the cheapest way past a check
    that reads one constant at a time - and a path join naming `.claude` then `hooks` is the same
    path in another spelling. `allowed` names module constants that may spell it: the one a tool
    finds the folder with. Raises SyntaxError on source it cannot read - unknown is not clean.
    """
    tree = ast.parse(source)
    skip = set()
    for node in ast.walk(tree):
        body = getattr(node, "body", None)
        if (isinstance(node, (ast.Module, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))
                and body and isinstance(body[0], ast.Expr)
                and isinstance(body[0].value, ast.Constant)):
            skip.add(id(body[0].value))
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(
                isinstance(t, ast.Name) and t.id in allowed for t in node.targets):
            skip.add(id(node.value))
    found, seen = [], set()
    for node in ast.walk(tree):
        if id(node) in skip or id(node) in seen:
            continue
        text = _joined(node)
        if text is not None:
            if isinstance(node, ast.BinOp):
                seen.update(id(n) for n in ast.walk(node))
            if HOOK_FOLDER.search(text):
                found.append((node.lineno, "spells the hooks folder (%r)" % text[:60]))
            continue
        if isinstance(node, ast.Call):
            words = [a.value for a in node.args
                     if isinstance(a, ast.Constant) and isinstance(a.value, str)]
            if any(a == ".claude" and b == "hooks" for a, b in zip(words, words[1:])):
                found.append((node.lineno, "joins `.claude` and `hooks` into a path"))
    return found


def declarations_in(text):
    """[(kind, entry, scope, error)] for every marker one checker's TEXT declares.

    The one reading of a declaration, shared by `rules` below and by anything that must decide
    whether a file is asked at the point of action - a tier derivation reading its own copy of
    this would be the second spelling of a rule that decides what counts as enforced.
    """
    if not _DECLARES.search(text or ""):
        return []
    try:
        values = _literals(ast.parse(text))
    except SyntaxError as exc:
        return [("AT_WRITE", None, None, "does not parse (%s)" % exc)]
    scope = values.get("AT_SCOPE")
    if isinstance(scope, ast.AST):
        scope = COMPUTED
    elif scope is not None and not isinstance(scope, str):
        return [("AT_SCOPE", None, None, "AT_SCOPE is %s, not a pattern" % type(scope).__name__)]
    out = []
    for kind in MARKERS:
        if kind not in values:
            continue
        entry = values[kind]
        want_pair = kind == "AT_WRITE_TREE"
        ok = (isinstance(entry, tuple) and len(entry) == 2 and
              all(isinstance(e, str) for e in entry)) if want_pair else isinstance(entry, str)
        if not ok:
            out.append((kind, None, None, "%s must be %s" % (
                kind, "a pair of function names" if want_pair else "one function name")))
            continue
        if kind in ("AT_WRITE", "AT_WRITE_CHANGE"):
            where = scope or SOURCE_SCOPE
        elif kind == "AT_RECORD":
            where = scope or RECORD_SCOPE
        elif kind == "AT_WRITE_TREE":
            where = scope
            if not where:
                out.append((kind, entry, None,
                            "AT_WRITE_TREE needs an AT_SCOPE saying which files are its tree"))
                continue
        else:
            where = None
        out.append((kind, entry, where, ""))
    return out


def rules(root, honour_lock=True):
    """Every declared rule under `RULE_DIRS`, found by reading the files - nothing is listed.

    A declaration that is not a literal of the right shape is returned with `error` set rather
    than dropped: a rule somebody meant to wire and got wrong must not read as a rule that does
    not exist.

    `honour_lock=False` is for a caller DRIVING one rule on purpose - a test proving that rule,
    while a plant holds the lock on the very file it planted into. Every write-time caller keeps
    the default: a module under mutation is not what its file says.
    """
    held = locked(root) if honour_lock else ""
    found = []
    for folder in RULE_DIRS:
        base = os.path.join(root, folder)
        for dirpath, dirnames, names in os.walk(base):
            dirnames[:] = sorted(d for d in dirnames if d != "__pycache__")
            for name in sorted(names):
                if not name.endswith(".py") or name.startswith("_"):
                    continue
                path = os.path.join(dirpath, name)
                if held and rel_of(root, path) == held:
                    continue
                try:
                    with io.open(path, encoding="utf-8", errors="replace") as handle:
                        text = handle.read()
                except OSError:
                    continue
                for kind, entry, where, error in declarations_in(text):
                    found.append(Rule(name[:-3], path, kind, entry, where, error))
    return found


def asked(root, kind, wiring=None):
    """Is a rule of this kind really asked - its hook present, and wired where the harness runs it?

    A `guard_*.py` hook is reached by `dispatch.py`, so it is asked when the dispatcher is wired.
    Any other hook is asked only when the settings name it. `wiring` is the settings text, read
    when not given.
    """
    hook = ASKED_BY.get(kind)
    if not hook or not os.path.isfile(os.path.join(root, HOOKS, hook)):
        return False
    if wiring is None:
        wiring = ""
        for name in ("settings.json", "settings.local.json"):
            try:
                with io.open(os.path.join(root, ".claude", name), encoding="utf-8") as handle:
                    wiring += handle.read()
            except OSError:
                pass
    if hook.startswith("guard_"):
        return "dispatch.py" in wiring
    return re.search(r"\b%s\b" % re.escape(hook), wiring) is not None


# ----------------------------------------------------------------------------- the tree

def tracked(root):
    """Every file git would carry - tracked, or new and not ignored - as repo-relative paths.

    From git rather than a walk because a project's ignored directories are where its bulk lives
    (worktrees, stores, builds) and a walk that descends them costs more than the rule it feeds.
    Outside a repository it falls back to a walk that skips dot-directories.
    """
    try:
        done = subprocess.run(["git", "ls-files", "-co", "--exclude-standard", "-z"], cwd=root,
                              capture_output=True, timeout=30, creationflags=NO_WINDOW)
        if done.returncode == 0:
            return sorted(set(p.decode("utf-8", "replace").replace(chr(92), "/")
                              for p in done.stdout.split(b"\0") if p))
    except (OSError, subprocess.SubprocessError):
        pass
    out = []
    for dirpath, dirnames, names in os.walk(root):
        dirnames[:] = [d for d in dirnames if not d.startswith(".") and d != "__pycache__"]
        for name in names:
            out.append(rel_of(root, os.path.join(dirpath, name)))
    return sorted(out)


def read_text(root, rel):
    try:
        with io.open(os.path.join(root, rel), encoding="utf-8", errors="replace",
                     newline="") as handle:
            return handle.read().replace(chr(13) + LF, LF)
    except OSError:
        return None


def _normal(value):
    """Round-tripped through JSON, so a fresh fact and a cached one are the same TYPE.

    A judge that received a tuple fresh and a list from the cache would answer two ways about
    one file - the second copy of a rule, grown inside one function.
    """
    return json.loads(json.dumps(value, sort_keys=True))


def _rule_digest(rule):
    """The rule's own source and every sibling it imports, so an edit to either rebuilds."""
    digest = hashlib.sha256()
    folder = os.path.dirname(rule.path)
    seen = [rule.path]
    try:
        with io.open(rule.path, encoding="utf-8", errors="replace") as handle:
            tree = ast.parse(handle.read())
        for node in ast.walk(tree):
            names = []
            if isinstance(node, ast.Import):
                names = [a.name.split(".")[0] for a in node.names]
            elif isinstance(node, ast.ImportFrom) and node.module and not node.level:
                names = [node.module.split(".")[0]]
            for name in names:
                candidate = os.path.join(folder, name + ".py")
                if os.path.isfile(candidate) and candidate not in seen:
                    seen.append(candidate)
    except (OSError, SyntaxError):
        pass
    for path in sorted(seen):
        try:
            with io.open(path, "rb") as handle:
                digest.update(handle.read())
        except OSError:
            digest.update(b"missing")
    return digest.hexdigest()


def _cache_path(root, rule):
    return os.path.join(root, CACHE, rule.name + ".json")


def _load_cache(root, rule, digest):
    try:
        with io.open(_cache_path(root, rule), encoding="utf-8") as handle:
            held = json.load(handle)
    except (OSError, ValueError):
        return {}
    if not isinstance(held, dict) or held.get("rule") != digest:
        return {}
    files = held.get("files")
    return files if isinstance(files, dict) else {}


#: The Cache Directory Tagging specification's signature - the SHAPE every walk here already
#: reads a generated folder by, and what ruff, pytest and mypy write into theirs.
CACHE_TAG = "Signature: 8a477f597d28d172789f06886806bc55"


def _cache_folder(root):
    """The cache directory, made if absent, carrying the `.gitignore` that ignores it and the
    `CACHEDIR.TAG` that says it is a cache. Measured 2026-09-26: ignored by git and untagged, a
    cache made inside the bundle's own folder was copied into a project by its installer, and
    `git add` refused the project's first install."""
    folder = os.path.join(root, CACHE)
    os.makedirs(folder, exist_ok=True)
    for name, text in ((".gitignore", "*" + LF), ("CACHEDIR.TAG", CACHE_TAG + LF)):
        path = os.path.join(folder, name)
        if not os.path.isfile(path):
            with io.open(path, "w", encoding="utf-8", newline=LF) as handle:
                handle.write(text)
    return folder


def _save_cache(root, rule, digest, files):
    try:
        _cache_folder(root)
        target = _cache_path(root, rule)
        temp = "%s.%d.tmp" % (target, os.getpid())
        with io.open(temp, "w", encoding="utf-8", newline=LF) as handle:
            json.dump({"rule": digest, "files": files}, handle, sort_keys=True)
        os.replace(temp, target)
    except OSError:
        pass                               # a cache that cannot be saved costs time, not truth


def tree_facts(root, rule, files=None):
    """{rel: facts} for every file in the rule's scope as it is ON DISK, cached per file.

    Gathered over a stretch no mutation run moved in, like every answer a rule gives here: a
    gathering the lock moved under is taken again, and one that never holds still is refused.
    """
    for _attempt in range(ASK_AGAIN):
        start = lock_state(root)
        facts = _tree_facts_once(root, rule, files)
        if lock_state(root) == start:
            return facts
    raise RuntimeError(_moved("tree"))


def _tree_facts_once(root, rule, files):
    files = tracked(root) if files is None else files
    held = locked(root)
    facts_of = rule.call(rule.entry[0])
    digest = _rule_digest(rule)
    cache = _load_cache(root, rule, digest)
    fresh, out, changed = {}, {}, False
    for rel in files:
        if rel == held or not rule.watches(rel):
            continue
        try:
            stat = os.stat(os.path.join(root, rel))
        except OSError:
            continue
        stamp = [stat.st_size, stat.st_mtime_ns]
        entry = cache.get(rel)
        if isinstance(entry, list) and len(entry) == 2 and entry[0] == stamp:
            out[rel] = entry[1]
            fresh[rel] = entry
            continue
        text = read_text(root, rel)
        if text is None:
            continue
        value = _normal(facts_of(rel, text))
        out[rel] = value
        fresh[rel] = [stamp, value]
        changed = True
    if changed or set(fresh) != set(cache):
        _save_cache(root, rule, digest, fresh)
    return out


# ----------------------------------------------------------------------------- asking a write

def _findings(value):
    return [str(v) for v in (value or [])]


def _grew(was, now):
    """(was count, now count, [findings new in `now`]) when `now` is worse, else None."""
    if len(now) <= len(was):
        return None
    left = list(was)
    new = []
    for item in now:
        if item in left:
            left.remove(item)
        else:
            new.append(item)
    return len(was), len(now), new or now[len(was):]


def repairs(root, rel):
    """Is this write one that could FIX a broken rule? Into `RULE_DIRS` or the hooks."""
    tops = [d.replace(os.sep, "/").rstrip("/") + "/" for d in RULE_DIRS + (HOOKS,)]
    return any(rel.startswith(top) for top in tops)


def _as_json(text):
    try:
        return True, json.loads(text)
    except ValueError:
        return False, None


def ask_write(root, rel, before, after, found=None):
    """{rule name: (was, now, [new findings])} for every rule this write makes worse.

    `before` is the file's text now, or None when it does not exist; `after` is the text the
    write would leave. A rule that could not answer is reported under its own name with `was`
    of -1 - the caller refuses it unless the write is a repair.
    """
    return ask_changes(root, {rel: (before, after)}, found)


def _compiles(rel, text):
    try:
        compile(text, rel, "exec", dont_inherit=True)
        return True
    except (SyntaxError, ValueError):
        return False


def _ask_one(root, rule, rel, before, after):
    """(was, now) findings of one per-file rule about one file. `after` None is a deletion."""
    if rule.kind == "AT_WRITE":
        call = rule.call(rule.entry)
        was = _findings(call(rel, before)) if before is not None else []
        return was, (_findings(call(rel, after)) if after is not None else [])
    if rule.kind == "AT_WRITE_CHANGE":
        # A deleted file runs nothing: what it held is judged by whatever still reads it.
        if after is None:
            return [], []
        return [], _findings(rule.call(rule.entry)(rel, before, after, root))
    if after is None:                                   # AT_RECORD, deleted
        return [], []
    ok_after, payload_after = _as_json(after)
    if not ok_after:
        return [], ["%s is a record and the write is not JSON" % rel]
    call = rule.call(rule.entry)
    path = os.path.join(root, rel)
    ok_before, payload_before = _as_json(before) if before is not None else (False, None)
    was = _findings(call(path, payload_before)) if ok_before else []
    return was, _findings(call(path, payload_after))


#: How many times an asking is repeated when a mutation run took or released its lock during it.
ASK_AGAIN = 3


def _moved(kind):
    return ("UNKNOWN: a mutation run took or released %s while this %s was being judged, %d times "
            "over - a rule may have read its fault, and an unknown verdict is not a clean one. "
            "Ask again in a moment." % (LOCK, kind, ASK_AGAIN))


def ask_changes(root, changes, found=None):
    """{rule name: (was, now, [new findings])} for every rule a SET of changes makes worse.

    `changes` is {rel: (before, after)}, either side None where the file does not exist. A write
    through the Write or Edit tool is a set of one. A command can change several files at once -
    a copy, a script, a `git stash pop` - and those are judged TOGETHER: a tree rule is asked
    once, with every changed file at its before and then every one at its after, so the verdict
    is about the tree the command left and costs one judgement rather than one per file. A
    per-file rule is asked of each file, and refuses when any one of them grows.

    ASKED AGAIN WHEN A MUTATION RUN MOVED UNDER IT. A rule reads the tree as well as the text it
    is handed, and a plant that came and went between its two askings - before and after - was
    charged to the write. A stretch in which `lock_state` did not move is the only one whose
    answer is given; a lock held still throughout is read the same way by both sides.
    """
    found = rules(root) if found is None else found
    for _attempt in range(ASK_AGAIN):
        start = lock_state(root)
        worse = _ask_changes_once(root, changes, found)
        if lock_state(root) == start:
            return worse
    return {"lock": (-1, -1, [_moved("change")])}


def _ask_changes_once(root, changes, found):
    worse = {}
    files = []
    for rel, (before, after) in sorted(changes.items()):
        if not rel.endswith(".py") or after is None:
            continue
        if (before is None or _compiles(rel, before)) and not _compiles(rel, after):
            try:
                compile(after, rel, "exec", dont_inherit=True)
            except (SyntaxError, ValueError) as exc:
                held = worse.get("compile", (0, 0, []))
                worse["compile"] = (0, held[1] + 1,
                                    held[2] + ["%s does not compile: %s" % (rel, exc)])
    for rule in found:
        if rule.error:
            if rule.kind in WRITE_KINDS:
                worse[rule.name] = (-1, -1, ["%s is declared but cannot be asked: %s"
                                             % (rule.name, rule.error)])
            continue
        mine = [rel for rel in sorted(changes) if rule.watches(rel)]
        if not mine:
            continue
        try:
            if rule.kind == "AT_WRITE_TREE":
                facts_of = rule.call(rule.entry[0])
                judge = rule.call(rule.entry[1])
                if not files:
                    files = tracked(root)
                facts = tree_facts(root, rule, files)
                # THE BEFORE THE CALLER NAMED, not the disk's. The hook passes the file as it is
                # on disk and the two agree; a channel asked about a transform passes the text
                # it read, and judging the disk instead would compare two different befores.
                for side in (0, 1):
                    for rel in mine:
                        text = changes[rel][side]
                        if text is None:
                            facts.pop(rel, None)
                        else:
                            facts[rel] = _normal(facts_of(rel, text))
                    if side == 0:
                        was = _findings(judge(dict(facts)))
                now = _findings(judge(facts))
                change = _grew(was, now)
            else:
                grown = [_grew(*_ask_one(root, rule, rel, *changes[rel])) for rel in mine]
                grown = [g for g in grown if g]
                change = (sum(g[0] for g in grown), sum(g[1] for g in grown),
                          [f for g in grown for f in g[2]]) if grown else None
        except BaseException as exc:                    # noqa: BLE001 - reported, see below
            if isinstance(exc, KeyboardInterrupt):
                raise
            worse[rule.name] = (-1, -1, ["%s could not answer (%s: %s)"
                                         % (rule.name, type(exc).__name__, exc)])
            continue
        if change:
            worse[rule.name] = change
    return worse


def ask_after(root, found=None):
    """{rule name: [findings]} for every `AT_AFTER` rule, over the tree as it now is."""
    return ask_command(root, None, found, kind="AT_AFTER")


def ask_command(root, command, found=None, kind="AT_COMMAND"):
    """{rule name: [findings]} for a command (or, with kind AT_COMMIT, a commit message).

    Asked again, as a change is, when a mutation run moved under the asking. And a COMMIT beside a
    held lock is UNKNOWN whatever its rules say: they read the tree the commit records, and one
    file of it is an injected fault. A command is not refused for it - every act in every session
    would stop for as long as a plant runs.
    """
    found = rules(root) if found is None else found
    for _attempt in range(ASK_AGAIN):
        start = lock_state(root)
        out = _ask_command_once(root, command, found, kind)
        if lock_state(root) == start:
            break
    else:
        return {"lock": [_moved(kind == "AT_COMMIT" and "commit" or "command")]}
    if kind == "AT_COMMIT" and start[0]:
        out.setdefault("lock", []).append(
            "UNKNOWN: %s. A commit is judged by rules that read the tree it records; ask again "
            "when the lock is gone." % _unknown_reason(start, start))
    return out


def _ask_command_once(root, command, found, kind):
    out = {}
    for rule in found:
        if rule.kind != kind:
            continue
        if rule.error:
            out[rule.name] = ["%s is declared but cannot be asked: %s" % (rule.name, rule.error)]
            continue
        try:
            call = rule.call(rule.entry)
            said = _findings(call(root) if kind == "AT_AFTER" else call(command, root))
        except BaseException as exc:                    # noqa: BLE001 - a rule that cannot answer
            if isinstance(exc, KeyboardInterrupt):
                raise
            said = ["%s could not answer (%s: %s)" % (rule.name, type(exc).__name__, exc)]
        if said:
            out[rule.name] = said
    return out


def refusal(what, worse):
    lines = ["BLOCKED: %s adds a class violation the project refuses." % what, ""]
    for name, (was, now, findings) in sorted(worse.items()):
        if was < 0:
            lines.append("  %-16s COULD NOT ANSWER" % name)
        else:
            lines.append("  %-16s %d -> %d" % (name, was, now))
        lines += ["      %s" % (f,) for f in findings[:4]]
    lines += ["",
              "  Asked here, before the file is written, because a rule met only at the commit",
              "  is met after the mistake has been built on. It refuses GROWTH, not existence:",
              "  the file may keep the debt it had. Fix the one this write adds.",
              "",
              "  If this is one HALF of a change whose other half is elsewhere - an import and",
              "  its first use - make both halves in ONE write: the whole file, or one edit that",
              "  spans them. Each half alone is a defect; together they are not."]
    return LF.join(lines)


# ----------------------------------------------------------------------------- a write no hook read

# A WRITE THAT NO HOOK COULD READ BEFORE IT LANDED. The Write and Edit tools carry their text, so
# every rule above is asked of it before the bytes land. A shell command does not: `cp`, a
# script, a redirect, `Set-Content`, `git stash pop` all change files with the text nowhere in
# the call. Measured on the project this bundle was installed into first: a test file copied in
# by a command carried an unused import past every write-time rule, and the COMMIT gate was the
# first thing to see it - the exact lateness these rules exist to remove.
#
# Nothing can read a command's writes before it runs. The earliest they exist is the moment
# after, so that is where they are judged - by the same rules and the same growth test as a
# Write, against the tree as it was last judged - and a change the rules refuse is HELD: every
# later act is refused until it is repaired (`guard_held_writes.py`). It stands on disk, and
# nothing can be built on it, which is what refusing it at the write would have bought.

#: The tree as last judged, and the changes still refused: inside the cache that ignores itself.
JUDGED = "judged-tree.json"

#: The text of each changed file as last judged, kept by its digest, so the next change to it is
#: judged against what it WAS, not against HEAD.
BLOBS = "judged-blobs"


def _git(root, args):
    """git's completed run, or None when git could not be started at all."""
    try:
        return subprocess.run(["git", "--no-optional-locks"] + list(args), cwd=root,
                              capture_output=True, timeout=60, creationflags=NO_WINDOW)
    except (OSError, subprocess.SubprocessError):
        return None


def _digest(root, rel):
    try:
        with io.open(os.path.join(root, rel), "rb") as handle:
            return hashlib.sha256(handle.read()).hexdigest()
    except OSError:
        return None


def head_commit(root):
    """HEAD's commit, '' when the repository has none yet, None when git could not say."""
    done = _git(root, ["rev-parse", "--verify", "-q", "HEAD"])
    if done is None:
        return None
    if done.returncode == 0:
        return done.stdout.decode("ascii", "replace").strip()
    return "" if done.returncode == 1 else None


def dirty(root):
    """{rel: "<git status>|<content digest>"} for every path that differs from HEAD.

    None when git could not say - which is never read as "nothing changed". Untracked files are
    listed one by one, so a new file inside a new directory is a path like any other.

    THE STATUS IS PART OF THE STATE, not only the bytes. Measured on the source project: a held
    change whose finding was "installed and NOT TRACKED BY GIT" stayed held after `git add`,
    because nothing in the file had changed and the tree read as exactly as it was last judged.
    A rule's verdict can depend on what git tracks, so a change to that is a change to judge.
    """
    done = _git(root, ["status", "--porcelain", "-z", "-uall", "--no-renames"])
    if done is None or done.returncode != 0:
        return None
    found = {}
    for entry in done.stdout.split(b"\0"):
        if len(entry) < 4:
            continue
        rel = entry[3:].decode("utf-8", "replace").replace(chr(92), "/")
        found[rel] = "%s|%s" % (entry[:2].decode("ascii", "replace"), _digest(root, rel))
    return found


def text_at(root, commit, rel):
    """The file's text at `commit`, newline-normalised as `read_text` is; None when the commit
    does not hold it. LookupError when git could not say which - absent and unreadable are two
    answers, and only one of them is None."""
    if not commit:
        return None
    listed = _git(root, ["ls-tree", "-z", "--name-only", commit, "--", rel])
    if listed is None or listed.returncode != 0:
        raise LookupError("git could not list %s at %s" % (rel, commit[:12]))
    if not listed.stdout.strip(b"\0"):
        return None
    shown = _git(root, ["show", "%s:%s" % (commit, rel)])
    if shown is None or shown.returncode != 0:
        raise LookupError("git could not show %s at %s" % (rel, commit[:12]))
    return shown.stdout.decode("utf-8", "replace").replace(chr(13) + LF, LF)


def _put(root, text):
    """Keep a text by its digest and return the digest - None for a file that does not exist."""
    if text is None:
        return None
    key = hashlib.sha256(text.encode("utf-8")).hexdigest()
    try:
        folder = os.path.join(_cache_folder(root), BLOBS)
        os.makedirs(folder, exist_ok=True)
        target = os.path.join(folder, key)
        if not os.path.isfile(target):
            temp = "%s.%d.tmp" % (target, os.getpid())
            with io.open(temp, "w", encoding="utf-8", newline=LF) as handle:
                handle.write(text)
            os.replace(temp, target)
    except OSError:
        pass                  # a text that cannot be kept is judged against HEAD next time
    return key


def _get(root, key):
    """(kept, text) for a digest `_put` returned. None is the file that did not exist."""
    if key is None:
        return True, None
    try:
        with io.open(os.path.join(root, CACHE, BLOBS, key), encoding="utf-8",
                     newline="") as handle:
            return True, handle.read()
    except OSError:
        return False, None


def _load_judged(root):
    try:
        with io.open(os.path.join(root, CACHE, JUDGED), encoding="utf-8") as handle:
            held = json.load(handle)
    except (OSError, ValueError):
        return {}
    return held if isinstance(held, dict) else {}


def _save_judged(root, state):
    try:
        folder = _cache_folder(root)
        target = os.path.join(folder, JUDGED)
        temp = "%s.%d.tmp" % (target, os.getpid())
        with io.open(temp, "w", encoding="utf-8", newline=LF) as handle:
            json.dump(state, handle, sort_keys=True)
        os.replace(temp, target)
        keep = set(state["texts"].values()) | set(state["held"].values())
        blobs = os.path.join(folder, BLOBS)
        for name in (os.listdir(blobs) if os.path.isdir(blobs) else []):
            if name not in keep:
                os.remove(os.path.join(blobs, name))
    except OSError:
        pass                  # a lost memo is judged again from HEAD, never skipped


def _judged_by_some_rule(rel, found):
    if rel.endswith(".py"):
        return True
    return any(r.kind in WRITE_KINDS and (r.error or r.watches(rel)) for r in found)


def _tree_key(root):
    return os.path.normcase(os.path.realpath(root))


def judge_unread(root, written=(), found=None):
    """`_judge_unread`, refused INSIDE A JUDGEMENT OF THE SAME TREE.

    A rule that must run the change - the changed tests, each alone - runs them in a child, and
    a test that runs a real hook makes that child judge the tree again: it holds the same change,
    asks the same rule, starts the same child. Measured 2026-09-26 on the project this came from:
    about ninety processes, and every act in two sessions held, until the change went back to
    HEAD. So the judgement names its tree in the environment every child inherits, and a
    judgement of a tree already being judged above it is BLIND - told why, never a clean empty
    answer - which `guard_held_writes` refuses on, loudly and once, where the test is visible.
    """
    mine = _tree_key(root)
    above = [key for key in os.environ.get(JUDGING, "").split(os.pathsep) if key]
    if mine in above:
        return {"held": {}, "worse": {}, "fresh": [], "unknown": "", "pending": [], "blind": (
            "a judgement of %s is already running in a process above this one (%s) - a rule's "
            "child that judges the same tree again would start the same rule again, without end"
            % (root, JUDGING))}
    os.environ[JUDGING] = os.pathsep.join(above + [mine])
    try:
        judged = _judge_unread(root, written, found)
        judged["named"] = named_by(root, judged) if judged["held"] else []
        return judged
    finally:
        if above:
            os.environ[JUDGING] = os.pathsep.join(above)
        else:
            os.environ.pop(JUDGING, None)


def _judge_unread(root, written=(), found=None):
    """Every change that reached the tree since it was last judged, judged now; what stays HELD.

    {"held": {rel: digest of its text before}, "worse": {rule: [was, now, findings]},
     "fresh": [paths held by THIS call], "blind": why the tree could not be read, or ""}

    `written` names the files a Write or Edit call just wrote: the write hook asked those before
    they landed, so they are recorded and not asked twice. Everything else that changed is
    judged - a copy, a script, a redirect, a deletion. Three things are never held:

        a file back at HEAD's text   the last tree the gates passed. `git checkout -- <file>` is
                                     the way OUT of a held change, and a guard that blocks the
                                     way out of the situation it describes is worse than none
        the module a mutation run    what is on disk is not what it says
        holds
        a repository with no commit  there is no gated tree to judge against, so the first
                                     reading is the baseline

    WITH NO RECORD, THE BEFORE IS HEAD - the last tree the gates passed - never the disk. A
    missing record read as "the disk is the baseline" would make deleting it the cheapest way
    past every rule here.

    NOTHING IS A VERDICT THAT A MUTATION RUN TOUCHED. `lock_state` is read before anything and
    after everything; a lock seen at either end, or a release counted in between, makes the whole
    judgement UNKNOWN - `unknown` says why and `pending` names what is still to judge. Nothing is
    held and nothing is recorded, so the same change is judged the first time the lock is gone.
    The WHOLE judgement, not the locked file's: a rule reads more than the file it is asked about.
    """
    start = lock_state(root)
    state = _load_judged(root)
    held = dict(state.get("held") or {})
    out = {"held": held, "worse": state.get("worse") or {}, "fresh": [], "blind": "",
           "unknown": "", "pending": []}
    now = dirty(root)
    head = head_commit(root)
    if now is None or head is None:
        out["blind"] = "git could not list what changed under %s" % root
        return out
    lock = start[0]
    first = not isinstance(state.get("paths"), dict)
    prev_paths = {} if first else state["paths"]
    prev_texts = state.get("texts") or {}
    prev_head = head if first else (state.get("head") or "")
    if lock:
        out["unknown"] = _unknown_reason(start, start)
    if (not first and head == prev_head and set(written) <= set(held)
            and {r: d for r, d in now.items() if r != lock}
            == {r: d for r, d in prev_paths.items() if r != lock}):
        return out
    found = rules(root) if found is None else found
    if lock or lock_state(root) != start:
        out["unknown"] = _unknown_reason(start, lock_state(root))
        out["pending"] = sorted(
            rel for rel in (set(prev_paths) | set(now) | set(written)) - {lock}
            if (rel in written or prev_paths.get(rel) != now.get(rel))
            and _judged_by_some_rule(rel, found))
        return out
    # FROM HERE NO LOCK IS HELD - one was returned as UNKNOWN above - so nothing below excludes the
    # locked file: that exclusion was the whole defence once, and it read the lock at one moment.
    written = set(written)
    changes, befores = {}, {}
    try:
        for rel in sorted(set(prev_paths) | set(now)):
            current = now[rel] if rel in now else "  |%s" % _digest(root, rel)
            if rel in prev_paths and prev_paths[rel] == current:
                continue
            if (rel in written and rel not in held) or not _judged_by_some_rule(rel, found):
                continue
            if first and not head:
                continue
            after = read_text(root, rel)
            at_head = text_at(root, head, rel)
            if after == at_head:
                held.pop(rel, None)
                continue
            kept, before = _get(root, prev_texts.get(rel)) if rel in prev_texts else (False, None)
            if not kept:
                before = at_head if prev_head == head else text_at(root, prev_head, rel)
            if before == after:
                continue
            changes[rel] = (before, after)
            befores[rel] = before
        group = {}
        for rel, key in held.items():
            kept, before = _get(root, key)
            group[rel] = (before if kept else text_at(root, head, rel), read_text(root, rel))
    except LookupError as exc:
        out["blind"] = str(exc)
        return out
    for rel, change in changes.items():
        group.setdefault(rel, change)
    worse = ask_changes(root, group, found) if group else {}
    if worse and all(repairs(root, rel) for rel in group):
        worse = {k: v for k, v in worse.items() if v[0] >= 0}
    now_held = {}
    if worse:
        for rel in group:
            now_held[rel] = held[rel] if rel in held else _put(root, befores[rel])
    texts = {}
    for rel in now:
        if _judged_by_some_rule(rel, found):
            same = rel in prev_texts and prev_paths.get(rel) == now[rel]
            texts[rel] = prev_texts[rel] if same else _put(root, read_text(root, rel))
    saved = {"head": head, "paths": dict(now), "texts": texts, "held": now_held,
             "worse": {k: list(v) for k, v in worse.items()} if now_held else {}}
    # AFTER THE LAST READ - the texts kept above are read from disk too - and before anything is
    # recorded: a verdict a mutation run moved under is not one.
    end = lock_state(root)
    if end != start:
        out["unknown"] = _unknown_reason(start, end)
        out["pending"] = sorted(set(changes) | set(group))
        return out
    _save_judged(root, saved)
    return {"held": now_held, "worse": saved["worse"],
            "fresh": sorted(set(now_held) - set(state.get("held") or {})), "blind": "",
            "unknown": "", "pending": []}


def _unknown_reason(start, end):
    """Why a judgement is UNKNOWN, from the two readings of `lock_state` around it."""
    held = start[0] or end[0]
    if held:
        return ("a mutation run holds %s on %s - until it is released that file is an injected "
                "fault, and a rule reading it answers about the fault" % (LOCK, held))
    return ("a mutation run took and released %s while this was being judged - a rule may have "
            "read its fault" % LOCK)


def unknown_note(judged):
    """What to tell the session whose change could not be judged, never a clean answer."""
    lines = ["NOT JUDGED YET: %s." % judged["unknown"], ""]
    lines += ["  %s" % rel for rel in (judged.get("pending") or [])[:12]]
    lines += ["", "  Nothing is held and nothing is recorded as judged: a verdict taken beside a",
              "  mutant is about the mutant. What changed is judged the first time the lock is",
              "  gone - it is not clean yet, it is unknown."]
    return LF.join(lines)


#: A word a finding could name a file with: a path, `path:line`, or a dotted id.
_NAMING = re.compile(r"[A-Za-z0-9_.~/\\:-]+")


def named_by(root, judged):
    """Every file of this project a held finding NAMES - a write there is a repair of the hold.

    Measured 2026-09-26 on the project this came from: a held kill-claim finding named the test
    making the claim, and the Edit that would repair that test was refused, so the change and its
    fix could each be written only after the other. Read from the findings' TEXT, because a rule
    returns strings and `_findings` keeps them so. A finding names a file by

        its path          `src/u.py`, `src/u.py:12`, absolute inside the project; it must exist
        a dotted id       `test_x.Class.test` or `pkg.mod`: the longest dotted prefix, as a
                          module path, that a tracked file's path ends with
        a file name       `plants.json`: every tracked file of that name

    A NAME THE HELD CHANGE WROTE OPENS NOTHING. A rule that quotes the file it judges - a line,
    a claim - names whatever that file spells, so the cheapest way past a hold would be to make
    the held change carry the name of the file you want to write next. A word found in the text
    of any held file does not count. What this cannot see: a tree rule quoting ANOTHER file,
    written earlier through a Write the rules passed.
    """
    held = judged.get("held") or {}
    wrote = [read_text(root, rel) or "" for rel in held]
    words = set()
    for _was, _now, findings in (judged.get("worse") or {}).values():
        for finding in findings:
            for word in _NAMING.findall(str(finding)):
                word = word.strip(".:-")
                if word and not any(word in text for text in wrote):
                    words.add(word)
    files, out = None, set()
    for word in sorted(words):
        path = re.sub(r":\d+(?:-\d+)?$", "", word).replace(chr(92), "/")
        if "/" in path:
            rel = rel_of(root, path if os.path.isabs(path) else os.path.join(root, path))
            if rel != ".." and not rel.startswith("../") \
                    and os.path.isfile(os.path.join(root, rel)):
                out.add(rel)
            continue
        if "." not in path:
            continue
        files = tracked(root) if files is None else files
        parts = path.split(".")
        candidates = [path] + (["/".join(parts[:k]) + ".py" for k in range(len(parts), 0, -1)]
                               if all(p.isidentifier() for p in parts) else [])
        for candidate in candidates:
            hits = [f for f in files if f == candidate or f.endswith("/" + candidate)]
            if hits:
                out.update(hits)
                break
    return sorted(out - set(held))


def held_refusal(judged):
    """The message for a change the rules refuse that stands on disk."""
    lines = ["BLOCKED: a change reached the tree by a route no rule could read before it landed,",
             "and the rules refuse it. Nothing else runs until it is repaired.", ""]
    for rel in sorted(judged["held"]):
        lines.append("  %s" % rel)
    lines.append("")
    if judged.get("named"):
        lines.append("  and the files the findings below name, which a repair may write:")
        lines += ["    %s" % rel for rel in judged["named"]]
        lines.append("")
    for name, (was, now, findings) in sorted(judged["worse"].items()):
        lines.append("  %-16s %s" % (name, "COULD NOT ANSWER" if was < 0 else
                                     "%d -> %d" % (was, now)))
        lines += ["      %s" % (f,) for f in findings[:4]]
    lines += ["",
              "  The Write and Edit tools are asked BEFORE their bytes land. A command - a copy,",
              "  a script, a redirect, a restore - carries no text to ask, so what it wrote is",
              "  asked the moment the tree shows it, against the tree as it was last judged.",
              "",
              "  Until it is repaired only these go through: a read; a Write or Edit to a path",
              "  above, held or named, or to a rule or a hook; a command that names a HELD path;",
              "  a run of a",
              "  tool under %s, which is how a record is re-measured; and `git checkout`,"
              % ", ".join(RULE_DIRS),
              "  `git restore` or `git stash`, which put a file back at the last committed",
              "  text - which is never held."]
    return LF.join(lines)


def main(argv):
    root = repo_root(os.path.join(os.getcwd(), "x")) or repo_root()
    if root is None:
        sys.stderr.write("no project root above %s%s" % (os.getcwd(), LF))
        return 2
    found = rules(root)
    if len(argv) > 1 and argv[1] == "warm":
        files = tracked(root)
        for rule in found:
            if rule.kind == "AT_WRITE_TREE" and not rule.error:
                sys.stdout.write("  %-18s %d file(s)%s"
                                 % (rule.name, len(tree_facts(root, rule, files)), LF))
        return 0
    for rule in found:
        scope = "(derived by the checker)" if rule.scope is COMPUTED else (rule.scope or "")
        sys.stdout.write("  %-18s %-15s %-28s %s%s" % (
            rule.name, rule.kind, rule.entry, rule.error or scope, LF))
    sys.stdout.write("%d rule(s) declared under %s%s" % (len(found), ", ".join(RULE_DIRS), LF))
    return 1 if any(r.error for r in found) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
