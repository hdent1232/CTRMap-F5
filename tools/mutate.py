# -*- coding: utf-8 -*-
"""Mutation-test the regression guards: does each one actually CATCH a regression?

The coarse check (revert a whole cluster, rebuild) only ever answered NOBUILD -
the guards reference API the fixes introduced, so the tree stops compiling
before any suite runs. That proves the guard is coupled to the fix; it does not
prove the guard would notice the BEHAVIOUR coming back.

So mutate one hunk at a time. Reverse-apply a single hunk of a fix, rebuild, and
run that cluster's guard suites:

  NOBUILD    the hunk carries API the guard needs - coupled, no verdict
  CAUGHT     a suite failed - that behaviour is genuinely guarded
  UNGUARDED  everything still passes - the fix changed behaviour nothing asserts

UNGUARDED is the finding worth having: it is a guard with a hole in it, which is
exactly the thing that lets a fixed defect come back silently.

Sampled rather than exhaustive: 211 hunks x ~80s of javac is five hours, and a
spread across each file answers the question at a fraction of that.

Usage: python tools/mutate.py [max-hunks-per-cluster]
"""
import io, os, json, subprocess, sys, time
from pathlib import Path

BASE = Path(r"C:\Users\flami\Desktop\Claude\sessions\3DS Editor")
WT = BASE / "wt" / "_guardcheck"
DUMP = BASE / "RomFS_original_garcs"
CAP = int(sys.argv[1]) if len(sys.argv) > 1 else 8

JDK = os.environ.get("CTRMAP_JDK")
if not JDK:
    ad = Path(r"C:\Program Files\Eclipse Adoptium")
    JDK = str(sorted((d for d in ad.iterdir() if d.name.startswith("jdk-")),
                     key=lambda d: d.name)[-1])
JAVAC, JAVA = JDK + r"\bin\javac.exe", JDK + r"\bin\java.exe"
CP = "build/classes;lib/jogl-all.jar;lib/gluegen-rt.jar"

CLUSTERS = {
    "c1":  ("4ed7eee", [("ctrmap.tests.PlacementGuardsTest", [str(DUMP / "a/0/3/9"), "20"]),
                        ("ctrmap.tests.MapPrefabTest", [str(DUMP / "a/0/3/9"), "20"])]),
    "c3":  ("4ed7eee", [("ctrmap.tests.PaintedFloorTest", [str(DUMP / "a/0/3/9")]),
                        ("ctrmap.tests.PaintedRegionTest", [str(DUMP / "a/0/3/9")])]),
    "c5a": ("4ed7eee", [("ctrmap.tests.DataSafetyGuardsTest", [str(DUMP / "a/0/4/0"), str(DUMP)])]),
    "c5b": ("4ed7eee", [("ctrmap.tests.NpcEntityGuardsTest", [str(DUMP / "a/0/1/3"), "src"])]),
    "c6":  ("4ed7eee", [("ctrmap.tests.ScriptAssemblerGuardTest", [str(DUMP / "a/0/1/3")])]),
}


def run(cmd, cwd=WT, timeout=600):
    return subprocess.run(cmd, cwd=str(cwd), capture_output=True, text=True,
                          shell=isinstance(cmd, str), timeout=timeout)


def split_hunks(diff):
    """One single-hunk patch per hunk, each keeping its file header."""
    out, cur_hdr, cur = [], None, None
    for line in diff.splitlines(keepends=True):
        if line.startswith("diff --git "):
            cur_hdr, cur = [line], None
        elif cur_hdr is not None and cur is None and line.startswith("@@"):
            cur = [line]
        elif line.startswith("@@") and cur is not None:
            out.append("".join(cur_hdr) + "".join(cur)); cur = [line]
        elif cur is not None:
            cur.append(line)
        elif cur_hdr is not None:
            cur_hdr.append(line)
    if cur is not None:
        out.append("".join(cur_hdr) + "".join(cur))
    return out


import require_build


def build():
    # The battery's build and its stamp - the same gate mutate2.py uses. This
    # sweep's own javac build copied no resources and measured a tree the
    # battery never runs; require_build refuses that outright.
    ok = require_build.build_with_battery(WT)
    if ok:
        require_build.require_build(WT)
    return ok


results, t0 = [], time.time()
run(["git", "reset", "--hard", "silent-failures"])

for cid, (base, suites) in CLUSTERS.items():
    diff = run(["git", "diff", base + "..sf/" + cid, "--", "src",
                ":!src/ctrmap/tests"]).stdout
    hunks = split_hunks(diff)
    step = max(1, len(hunks) // CAP)
    picked = hunks[::step][:CAP]
    print("\n=== %s: %d hunks, testing %d" % (cid, len(hunks), len(picked)), flush=True)

    for n, h in enumerate(picked):
        (WT / "m.patch").write_text(h, encoding="utf-8", newline="")
        if run(["git", "apply", "-R", "--check", "m.patch"]).returncode != 0:
            print("   hunk %-3d SKIP (does not reverse cleanly alone)" % n, flush=True)
            continue
        run(["git", "apply", "-R", "m.patch"])
        first = h.splitlines()[0].split(" b/")[-1].split("/")[-1]

        if not build():
            verdict, detail = "NOBUILD", ""
        else:
            verdict, detail = "UNGUARDED", ""
            for cls, a in suites:
                r = run([JAVA, "-Xmx4g", "-Djava.awt.headless=true", "-cp", CP, cls] + a)
                if r.returncode != 0:
                    tail = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
                    verdict, detail = "CAUGHT", cls.split(".")[-1] + ": " + (tail[-1][:110] if tail else "")
                    break

        mark = "  <-- GUARD HAS A HOLE" if verdict == "UNGUARDED" else ""
        print("   hunk %-3d %-9s %-28s %s%s" % (n, verdict, first, detail, mark), flush=True)
        results.append(dict(cluster=cid, hunk=n, file=first, verdict=verdict, detail=detail,
                            patch=h[:1200]))
        run(["git", "checkout", "--", "src"])
        run(["git", "reset", "--hard", "silent-failures"])

(WT / "m.patch").unlink(missing_ok=True)
build()
out = BASE / "wt/_state/mutation.json"
io.open(out, "w", encoding="utf-8", newline="\n").write(json.dumps(results, indent=1))
tally = {}
for r in results:
    tally[r["verdict"]] = tally.get(r["verdict"], 0) + 1
print("\n%d hunks mutated in %d min: %s" % (len(results), (time.time() - t0) / 60, tally))
holes = [r for r in results if r["verdict"] == "UNGUARDED"]
print("GUARDS WITH HOLES: %d" % len(holes))
for r in holes:
    print("   %s %s hunk %d" % (r["cluster"], r["file"], r["hunk"]))
