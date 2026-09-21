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
"""
import hashlib
import subprocess
from pathlib import Path

STAMP = ".built-by-build-ps1"

# The extensions whose CRs are not content - see stamp.ps1 for why line endings
# are not part of a file's identity here. Binary files are hashed byte for byte.
TEXT_DIGEST_EXT = (".java", ".form", ".properties", ".tsv", ".md", ".txt")


def digest_bytes(path):
    """The bytes a digest is taken over: text files with every CR removed."""
    data = Path(path).read_bytes()
    if str(path).lower().endswith(TEXT_DIGEST_EXT):
        return data.replace(b"\r", b"")
    return data


def tree_digest(root, exclude=""):
    root = Path(root)
    lines = []
    for p in root.rglob("*"):
        if not p.is_file():
            continue
        rel = p.relative_to(root).as_posix()
        if rel == exclude:
            continue
        lines.append(rel + ":" + hashlib.sha256(digest_bytes(p)).hexdigest() + "\n")
    lines.sort()                          # code-point order == ordinal for these paths
    return hashlib.sha256("".join(lines).encode("utf-8")).hexdigest()


def stamp_problem(root):
    """None when build/classes is exactly what build.ps1 made from src/, else why not."""
    root = Path(root)
    path = root / "build" / "classes" / STAMP
    if not path.is_file():
        return "build/classes carries no stamp - it was not produced by build.ps1"
    kv = dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines() if "=" in line)
    if tree_digest(root / "src") != kv.get("src"):
        return "src/ has changed since build.ps1 last ran - rebuild before measuring anything"
    if tree_digest(root / "build" / "classes", STAMP) != kv.get("classes"):
        return "build/classes is not what build.ps1 produced (a file added, removed or replaced since) - rebuild"
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
    #: A BUILD, NOT A GIT QUERY. The first version of this timeout was 60 seconds, copied from
    #: the sites beside it - and a cold javac over this tree takes longer than that, so it
    #: would have turned honest builds into failures. That is the guard-fires-on-honest-work
    #: shape, introduced by the very change that was supposed to stop a wedge.
    #:
    #: AND THE TIMEOUT IS A RESULT. `TimeoutExpired` is a SubprocessError, not an OSError, so
    #: adding the keyword without catching it converts "blocks forever" into "crashes with a
    #: traceback" - which is louder and still not an answer.
    try:
        r = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                            "-File", "build.ps1"],
                           cwd=str(root), capture_output=True, text=True, timeout=1800)
    except subprocess.TimeoutExpired:
        return False, ("the build did not finish in 1800s. It has not necessarily failed - a "
                       "capture_output run reads to EOF, and EOF does not arrive while any "
                       "grandchild still holds the pipe, so 'still working' and 'wedged' look "
                       "the same from here. Run build.ps1 by hand and watch it.")
    out = (r.stdout or "") + (r.stderr or "")
    if r.returncode != 0 or "Build OK" not in (r.stdout or ""):
        return False, out
    return stamp_problem(root) is None, out


def build_with_battery(root):
    """The battery's build - build.ps1 - and nothing else; then prove it stamped."""
    return build_with_battery_verbose(root)[0]
