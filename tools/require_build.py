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
BatteryHygieneTest.builtByTheBattery; keep all three identical.
"""
import hashlib
import subprocess
from pathlib import Path

STAMP = ".built-by-build-ps1"


def tree_digest(root, exclude=""):
    root = Path(root)
    lines = []
    for p in root.rglob("*"):
        if not p.is_file():
            continue
        rel = p.relative_to(root).as_posix()
        if rel == exclude:
            continue
        lines.append(rel + ":" + hashlib.sha256(p.read_bytes()).hexdigest() + "\n")
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


def build_with_battery(root):
    """The battery's build - build.ps1 - and nothing else; then prove it stamped."""
    r = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "build.ps1"],
                       cwd=str(root), capture_output=True, text=True)
    if r.returncode != 0 or "Build OK" not in (r.stdout or ""):
        return False
    return stamp_problem(root) is None
