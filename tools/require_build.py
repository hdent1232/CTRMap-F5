# -*- coding: utf-8 -*-
"""Refuse to measure a tree that build.ps1 did not build from these sources.

The Python half of CTRMap/stamp.ps1. build.ps1 writes build/classes/
.built-by-build-ps1 with a digest of src/ and a digest of build/classes; this
recomputes both and refuses on any difference. A harness that imports this and
calls require_build() before running a suite cannot measure a hand-compiled
tree, a tree missing a resource, or a tree edited since it was built - which is
how a sweep once reported a guard suite red against a stale catalogue that no
battery had ever seen.

The digest algorithm is defined in stamp.ps1 and mirrored in
BatteryHygieneTest.builtByTheBattery; keep all three identical. Its per-file
half is mirrored again in MutationBaselineTest and tools/mutate2.py, which
import digest_bytes from here rather than writing a sixth copy.

THE BUNDLE'S ADDITIONS, TAKEN BACK (F183, 2026-10-07). The verification bootstrap's copy of this
file was adapted from this one and then grew what this one lacked: a digest of NOTHING refused
rather than answered, and `subject_digest` - which tree a recorded measurement was taken against.
`tools/commit_gate.py` calls the second, so every commit message claiming "N tests" crashed here
with an AttributeError, and the bundle's 13 tests of both were red in this tree. And this tree
already had a second body of `subject_digest`, `commit_guard.source_digest`, differing in how it
read the rule file - so `record_run` wrote one digest and the gate would have compared it with the
other. There is ONE now, below; `source_digest` answers through it. CTRMap's own digest rule is
kept: text files lose their CRs, as stamp.ps1 and the Java mirrors take them.
"""
import hashlib
import os
import subprocess
import sys
from pathlib import Path
if os.path.dirname(os.path.abspath(__file__)) not in sys.path:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bounded  # noqa: E402  - a deadline ends the child's whole tree: the build is a tree
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)  # no console window for a captured child (F184)

STAMP = ".built-by-build-ps1"

#: THE TWO ROOTS, relative to the project root handed to `stamp_problem`.
SOURCE_ROOT = ("src",)
OUTPUT_ROOT = ("build", "classes")

#: What produces the stamp.
BUILD_COMMAND = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "build.ps1"]

#: A BUILD, NOT A GIT QUERY. The first version of this timeout was 60 seconds, copied from the
#: sites beside it - and a cold javac over this tree takes longer than that, so it would have
#: turned honest builds into failures. That is the guard-fires-on-honest-work shape, introduced
#: by the very change that was supposed to stop a wedge.
BUILD_TIMEOUT = 30 * 60

# The extensions whose CRs are not content - see stamp.ps1 for why line endings
# are not part of a file's identity here. Binary files are hashed byte for byte.
TEXT_DIGEST_EXT = (".java", ".form", ".properties", ".tsv", ".md", ".txt")


class NothingToDigest(Exception):
    """A digest root that is absent, or present and empty - or a subject git could not be asked.

    `Path(missing).rglob("*")` yields nothing and raises nothing, so a missing src/ hashed to the
    SHA-256 of the empty string: the same value an empty directory gives, and a typo gives. An
    exception rather than a sentinel, because the sentinel was a perfectly good-looking hash.
    """


def digest_bytes(path):
    """The bytes a digest is taken over: text files with every CR removed."""
    return digest_of(Path(path).read_bytes(), str(path))


def digest_of(data, name):
    """`data` as a digest takes it, for a file called `name` - the one rule, for bytes that are not on
    disk too: the commit guard digests the STAGED copy of a file (F132), which may differ from it."""
    if name.lower().endswith(TEXT_DIGEST_EXT):
        return data.replace(b"\r", b"")
    return data


def tree_manifest(root, exclude=""):
    """[`relpath:sha256`] for every file under `root`, sorted - what the digest is taken OF, and
    the thing to compare when two implementations of the digest disagree."""
    root = Path(root)
    if not root.is_dir():
        raise NothingToDigest(
            "%s is not a directory - there is nothing to digest, and an absent tree must not "
            "hash to the same value as an empty one" % root)
    lines = []
    for p in root.rglob("*"):
        if not p.is_file():
            continue
        rel = p.relative_to(root).as_posix()
        if rel == exclude:
            continue
        lines.append(rel + ":" + hashlib.sha256(digest_bytes(p)).hexdigest())
    if not lines:
        raise NothingToDigest(
            "%s exists and contains no files - NOTHING MEASURED MUST NEVER RENDER AS SUCCESS, "
            "and a digest over zero files is a number that looks like an answer" % root)
    lines.sort()                          # code-point order == ordinal for these paths
    return lines


def tree_digest(root, exclude=""):
    """One digest over every file under `root`, as stamp.ps1 takes it. Raises `NothingToDigest`
    rather than return one for a root that is absent or empty."""
    return hashlib.sha256("".join(line + "\n" for line in tree_manifest(root, exclude))
                          .encode("utf-8")).hexdigest()


#: HOW LONG A SUBJECT QUERY MAY TAKE: a call with no timeout turns "cannot read the subject" into
#: "still running", and those look identical from outside.
SUBJECT_TIMEOUT = 5 * 60

#: The rule file a suite's verdict reads. Found in every ANCESTOR of the root - see below.
RULES_FILE = "CLAUDE.md"


def subject_digest(root, exclude=()):
    """WHICH TREE a recorded measurement was taken against. Raises rather than guess.

    Git's own answer to what here is source - tracked files plus untracked ones it does not
    ignore - MINUS DOTFILES AT THE ROOT, which is where records live: a SHAPE, not a list of
    names, so the record is not part of its own subject in the scratch repositories the suites
    build either, which carry no `.gitignore`. Everything a suite reads moves it -
    `tools/guard/magnitudes.json` and `plants.json` included, because a changed number is a
    changed verdict. Line endings are normalised: this tree is mixed and autocrlf rewrites on
    commit, so raw bytes would refuse a record taken against identical content.

    THE RULE FILE ABOVE THE ROOT IS PART OF THE SUBJECT: every ancestor's `CLAUDE.md`, under its
    distance from the root, because a suite's verdict is a reading of the rules in the load path
    and git has never heard of the ones above it.

    `exclude` - repository-relative posix paths a caller has DERIVED from what a run itself
    writes (the battery records ratchet readings as it goes). "Did the tree move underneath this
    run" leaves those out; "which tree was this measured against" passes nothing.

    Raises `NothingToDigest` when it cannot look - no git, not a repository, a call that failed
    or timed out, or a repository holding no source. A subject you cannot compute is UNKNOWN,
    never unchanged.
    """
    exclude = {e.replace("\\", "/") for e in exclude}
    try:
        done = bounded.run(
            ["git", "-C", str(root), "ls-files", "--cached", "--others", "--exclude-standard"],
            capture_output=True, text=True, timeout=SUBJECT_TIMEOUT, creationflags=NO_WINDOW)
    except (OSError, subprocess.SubprocessError) as cannotAsk:
        #: TimeoutExpired is a SubprocessError and NOT an OSError. Catching only OSError here
        #: lets a hung git escape as a traceback from somewhere unrelated.
        raise NothingToDigest("cannot ask git what %s contains: %s" % (root, cannotAsk))
    if done.returncode != 0:
        raise NothingToDigest(
            "%s is not a git repository, so what it contains cannot be read - and an unreadable "
            "subject must not digest to the same value as an unchanged one" % root)
    digest = hashlib.sha256()
    counted = 0
    for rel in sorted(line.strip() for line in done.stdout.splitlines() if line.strip()):
        if rel.startswith(".") and "/" not in rel:
            continue
        if rel in exclude:
            continue
        counted += 1
        digest.update(rel.encode("utf-8"))
        try:
            digest.update(Path(root, rel).read_bytes().replace(b"\r\n", b"\n"))
        except OSError:
            digest.update(b"<unreadable>")
    if not counted:
        raise NothingToDigest(
            "%s is a repository holding no source at all - a digest over zero files is a number "
            "that looks like an answer" % root)
    here, hops = Path(root).resolve(), 0
    while here.parent != here:
        here, hops = here.parent, hops + 1
        rules = here / RULES_FILE
        if rules.is_file():
            digest.update(("../" * hops + RULES_FILE).encode("utf-8"))
            try:
                digest.update(rules.read_bytes().replace(b"\r\n", b"\n"))
            except OSError:
                digest.update(b"<unreadable>")
    return digest.hexdigest()


def stamp_problem(root):
    """None when build/classes is exactly what build.ps1 made from src/, else why not - with an
    answer for "not there" and a different one for "I could not look"."""
    root = Path(root)
    path = root.joinpath(*OUTPUT_ROOT) / STAMP
    if not path.is_file():
        return "build/classes carries no stamp - it was not produced by build.ps1"
    kv = dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines() if "=" in line)
    for key, parts, exclude, changed in (
            ("src", SOURCE_ROOT, "", "src/ has changed since build.ps1 last ran - rebuild before measuring anything"),
            ("classes", OUTPUT_ROOT, STAMP, "build/classes is not what build.ps1 produced (a file added, removed or"
                                            " replaced since) - rebuild")):
        recorded = kv.get(key)
        if recorded is None:
            return ("the stamp names no `%s` digest, so what the build saw is UNKNOWN - and an "
                    "unanswerable question is not a passing answer" % key)
        try:
            found = tree_digest(root.joinpath(*parts), exclude)
        except NothingToDigest as absent:
            return str(absent)
        if found != recorded:
            return changed
    return None


def require_build(root):
    """Exit with the reason rather than measure a tree the battery never runs."""
    problem = stamp_problem(root)
    if problem:
        raise SystemExit("REFUSING TO MEASURE %s: %s" % (root, problem))


def build_with_battery_verbose(root):
    """(ok, everything the build said) - the battery's build, keeping the compiler's words.

    A caller that only learns "it did not build" cannot tell a mutation that is
    malformed - the harness's own defect - from one that has no legal form at
    all, such as deleting a throw that is its method's only exit. javac already
    knows which; discarding what it said threw that away.
    """
    #: AND THE TIMEOUT IS A RESULT. `TimeoutExpired` is a SubprocessError, not an OSError, so
    #: adding the keyword without catching it converts "blocks forever" into "crashes with a
    #: traceback" - which is louder and still not an answer.
    try:
        r = bounded.run(BUILD_COMMAND, cwd=str(root), capture_output=True, text=True,
                        timeout=BUILD_TIMEOUT, creationflags=NO_WINDOW)
    except subprocess.TimeoutExpired:
        return False, ("the build did not finish in %ds. It has not necessarily failed - a "
                       "capture_output run reads to EOF, and EOF does not arrive while any "
                       "grandchild still holds the pipe, so 'still working' and 'wedged' look "
                       "the same from here. Run build.ps1 by hand and watch it." % BUILD_TIMEOUT)
    out = (r.stdout or "") + (r.stderr or "")
    if r.returncode != 0 or "Build OK" not in (r.stdout or ""):
        return False, out
    #THE REASON TRAVELS WITH THE ANSWER (2026-10-14): a stamp that refused after "Build OK" answered False and kept
    #why to itself, and the sweep filed the mutant "nocompile: no diagnostic captured" - its own failure, unnamed
    problem = stamp_problem(root)
    return problem is None, out if problem is None else out + "\nTHE BUILD'S STAMP REFUSED: " + problem


def build_with_battery(root):
    """The battery's build - build.ps1 - and nothing else; then prove it stamped."""
    return build_with_battery_verbose(root)[0]
