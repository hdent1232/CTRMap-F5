# -*- coding: utf-8 -*-
"""Semantic mutation: break a fix in a way that still COMPILES, and see who notices.

The first mutator reverse-applied whole hunks. That answered NOBUILD for 72 of
188 - 38% - because a hunk often carries the very method or field the guard
calls, so the tree stops compiling before any suite runs. Coupling proven,
detection unproven, which is the weaker half of what a guard is for.

This one never removes a declaration. It edits one statement inside the fix's
own added lines, choosing edits that always compile and that recreate THIS
audit's failure mode - a tool that reports success while doing the wrong thing:

  drop-throw    delete a `throw ...;`        a refusal becomes a silent pass
  drop-report   delete a dialog/report call  the loud path goes quiet
  negate-if     `if (c)` -> `if (!(c))`      the guard condition inverts
  flip-return   `return true` <-> `false`    a validity check always agrees

A mutant that compiles and leaves every suite green is a line of the fix that
nothing asserts. That is the finding worth having, and unlike the reversal
mutator it can say so about the 38% that were silent before.

Usage: python tools/mutate2.py [max-mutants-per-file]
"""
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

BASE = Path(r"C:\Users\flami\Desktop\Claude\sessions\3DS Editor")
WT = BASE / "wt" / "_guardcheck"
DUMP = BASE / "RomFS_original_garcs"
CAP = int(sys.argv[1]) if len(sys.argv) > 1 else 6

JDK = os.environ.get("CTRMAP_JDK")
if not JDK:
    ad = Path(r"C:\Program Files\Eclipse Adoptium")
    JDK = str(sorted((d for d in ad.iterdir() if d.name.startswith("jdk-")),
                     key=lambda d: d.name)[-1])
JAVAC, JAVA = JDK + r"\bin\javac.exe", JDK + r"\bin\java.exe"
CP = "build/classes;lib/jogl-all.jar;lib/gluegen-rt.jar"

# The branch the fixes were merged INTO. Configurable because it moved once
# already: the campaign merged into silent-failures, that was fast-forwarded
# into master, and a sweep still naming the old branch would have measured a
# tree 82 commits stale while looking perfectly healthy.
MAINLINE = os.environ.get("CTRMAP_MAINLINE", "master")

# Every fix branch that has been merged, with the guard suites that are supposed
# to catch its regressions. The base of each is taken from git at run time and
# the suites' arguments from test.ps1, so this table cannot drift from the
# battery the way a hand-typed one did: the first version named only batch one's
# five branches, and a sweep run on the final tree would have reported a
# baseline that quietly said nothing about the other eight.
#
# A SUITE NAMED BY NO CLUSTER CAN NEVER KILL ANYTHING. FILE_SUITES below is a
# union of these lists and nothing else, so a suite the battery runs but this
# table does not name is invisible to the sweep however much it asserts. That is
# not a hypothetical: PaintFormGuards, TextureCarryGuards, ZoneAppend and
# PackScope were all registered in test.ps1 and named here by nobody, and the
# baseline recorded 13 lines as survivors that those four demonstrably kill -
# every one of them verified by hand, mutant built and suite run. A hole in the
# record reads exactly like a hole in the coverage, and sends the next person to
# write a guard for a line that already has one. When a suite is added to
# test.ps1, add it to the cluster whose files it guards.
CLUSTERS = {
    "c1":           ["PlacementGuardsTest", "MapPrefabTest"],
    "c3":           ["PaintedFloorTest", "PaintedRegionTest"],
    "c5a":          ["DataSafetyGuardsTest"],
    #NpcEditFormGuards is what kills ZoneEntities' altitude lookup (setYFromColl)
    "c5b":          ["NpcEntityGuardsTest", "ZoneScriptAnalyzerTest", "NpcTemplatesTest",
                     "NpcEditFormGuardsTest"],
    "c6":           ["ScriptAssemblerGuardTest"],
    "c8":           ["DataSafetyGuardsTest", "BatteryHygieneTest"],
    "gap-warp":     ["DataSafetyGuardsTest"],
    "seam":         ["DataSafetyGuardsTest"],
    #PackScope is what kills Workspace's pack-only-what-was-edited decisions
    "c47":          ["ForkGuardsTest", "MatrixForkTest", "IntegrityTest", "PackReportTest",
                     "SnapshotIntegrityTest", "DataSafetyGuardsTest", "PackScopeTest"],
    #TextureCarryGuards is the only suite that calls BchTexturePack.carryToArea
    "c2":           ["PaintApplyGuardsTest", "TexturePackImportTest", "TerrainImportTest",
                     "AreaShareGuardTest", "TextureCarryGuardsTest"],
    #PaintFormGuards is the only suite that constructs PaintForm
    "guards-paint": ["PaintedFloorTest", "PlacementGuardsTest", "MapPrefabTest",
                     "ScriptAssemblerGuardTest", "DataSafetyGuardsTest", "PaintApplyGuardsTest",
                     "PaintFormGuardsTest"],
    "guards-npc":   ["NpcEditFormGuardsTest", "NpcEntityGuardsTest", "DataSafetyGuardsTest",
                     "ZoneScriptAnalyzerTest", "NpcTemplatesTest"],
    "gap2":         ["PackReportTest", "DataSafetyGuardsTest", "DoorPropGuardsTest"],
    "uifix":        ["BatteryHygieneTest", "DataSafetyGuardsTest"],
    "d1":           ["TerrainImportNoiseTest", "BatteryHygieneTest", "BchMapModelTest"],
    "d2":           ["NpcEditFormGuardsTest", "BatteryHygieneTest"],
    #ZoneAppend runs the real GARC.packDirectory and reads the header back
    "d3":           ["PackRollbackTest", "MisplacedRegistryTest", "IntegrityTest", "ForkGuardsTest",
                     "ZoneAppendTest"],
    "d4":           ["PaintApplyGuardsTest", "PlacementGuardsTest", "BuildingCatalogTest",
                     "MapPrefabTest"],
    "m1":           ["NpcEditFormGuardsTest", "NpcEntityGuardsTest", "DataSafetyGuardsTest",
                     "ZoneEntitiesRoundTripTest"],
    "m2":           ["PaintApplyGuardsTest", "PaintFormGuardsTest", "PaintedFloorTest",
                     "PlacementGuardsTest", "MapPrefabTest"],
    "m3":           ["PackReportTest", "PackRollbackTest", "IntegrityTest", "MisplacedRegistryTest",
                     "SnapshotIntegrityTest", "ForkGuardsTest", "DataSafetyGuardsTest",
                     "SetupWizardTest", "BatteryHygieneTest"],
    "m4":           ["BuildingCatalogTest", "PlacementGuardsTest", "TexturePackImportTest",
                     "DoorPropGuardsTest"],
    "talkerfix":    ["DispatchTrampolineTest", "TalkerWizardDryRunTest", "GiveBpScriptTest",
                     "GauntletScriptTest", "ZoneScriptAnalyzerTest", "NpcTemplatesTest",
                     "SignWrapperInjectTest", "MsgWrapperInjectTest"],
}

SUBST = {
    "$a013": str(DUMP / "a/0/1/3"), "$a039": str(DUMP / "a/0/3/9"), "$a040": str(DUMP / "a/0/4/0"),
    "$pristine": str(DUMP), "$gamedir": str(BASE / "RomFS/000400000011C400"), "$step": "20",
}


def suite_args():
    """{short class name: [args]} read from test.ps1's $suites, as the battery runs them."""
    text = (WT / "test.ps1").read_text(encoding="utf-8", errors="replace")
    out = {}
    # Balanced-paren scan, not a regex: @((Join-Path $p "a-0-1-4"), (Join-Path ...))
    # has two nested groups and the first version's regex stopped at the first
    # ")", handing TexturePackImportTest one argument. It then defaulted the
    # other to a path relative to the worktree, failed unmutated, and the sweep
    # refused - the third time its own refusal has found a real defect.
    entries = []
    pos = 0
    while True:
        i = text.find('c = "ctrmap.tests.', pos)
        if i < 0:
            break
        cls = text[i + len('c = "ctrmap.tests.'):text.index('"', i + len('c = "ctrmap.tests.'))]
        j = text.index('a = @(', i) + len('a = @(')
        depth, k = 1, j
        while depth and k < len(text):
            depth += {'(': 1, ')': -1}.get(text[k], 0)
            k += 1
        inner = text[j:k - 1]
        toks, cur, d = [], '', 0
        for ch in inner:
            if ch == '(':
                d += 1
            elif ch == ')':
                d -= 1
            if ch == ',' and d == 0:
                toks.append(cur)
                cur = ''
            else:
                cur += ch
        toks.append(cur)
        entries.append((cls, [t.strip() for t in toks if t.strip()]))
        pos = k
    for cls, raw_tokens in entries:
        args = []
        for tok in raw_tokens:
            # the outer @( ... ) capture keeps the inner group's opening paren
            # but not always its closing one, so accept either
            jp = re.match(r'\(?Join-Path\s+(\$\w+)\s+"([^"]+)"\)?', tok)
            if jp:
                args.append(str(Path(SUBST[jp.group(1)]) / jp.group(2).replace("\\", "/")))
            elif tok.startswith('"'):
                args.append(tok.strip('"'))
            elif tok.startswith("$(if"):
                args.append("1")
            else:
                args.append(SUBST.get(tok, tok))
        out[cls] = args
    return out


def resolve_clusters():
    """(base sha, [(fqcn, args)]) per branch, from git and test.ps1."""
    known = suite_args()
    resolved = {}
    for branch, suites in CLUSTERS.items():
        before, tip = merge_parents(branch)
        if not before:
            print("  skip %s: no merge of sf/%s on %s" % (branch, branch, MAINLINE))
            continue
        base = (before, tip)
        picked = []
        for s in suites:
            if s not in known:
                raise SystemExit("%s names suite %s, which test.ps1 does not register - "
                                 "a suite the battery does not run guards nothing" % (branch, s))
            picked.append(("ctrmap.tests." + s, known[s]))
        resolved[branch] = (base[:7], picked)
    return resolved

THROW = re.compile(r"^\s*throw\s+new\s+\w")
REPORT = re.compile(r"^\s*(JOptionPane\.show\w+|System\.out\.print\w*|\w*[Dd]ialog\w*\.\w+|"
                    r"\w*[Ss]tatus\w*\.setText|bad\.add|problems\.add|warn\w*\()")
IF = re.compile(r"^(\s*)(\}\s*else\s+)?if\s*\((.+)\)\s*\{\s*$")
RET_TRUE = re.compile(r"^(\s*)return\s+true\s*;\s*$")
RET_FALSE = re.compile(r"^(\s*)return\s+false\s*;\s*$")


class Hung(object):
    """A run that never finished. NOT a kill - see below."""
    returncode = -99
    stdout = stderr = ""


def run(cmd, timeout=900):
    # A hang must never be scored as a kill. A mutant that makes a suite loop
    # forever has not been detected by that suite; counting it killed inflates
    # the score in the one direction this measurement must never drift. The
    # sister project scored two mutants "HUNG - counted as killed" with nothing
    # actually hung, because their bound was derived from the wrong unit.
    try:
        return subprocess.run(cmd, cwd=str(WT), capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return Hung()


def merge_parents(branch):
    """(before, branch tip) of the merge that brought sf/<branch> into silent-failures.

    merge-base is the wrong primitive once a branch is merged: it returns the
    branch's own tip, the diff against it is empty, and a sweep would report a
    perfect baseline of nothing. The merge commit's first parent is the tree the
    branch landed on and its second parent is the branch; their diff is exactly
    what that merge introduced - including for c47 and c2, which had merged
    silent-failures into themselves first.
    """
    m = run(["git", "log", "--merges", "--first-parent", "--format=%H",
             "--grep=Merge branch 'sf/%s'" % branch, MAINLINE]).stdout.split()
    if not m:
        return None, None
    # resolved to real shas so the log names the two trees, not one merge twice
    return (run(["git", "rev-parse", m[-1] + "^1"]).stdout.strip(),
            run(["git", "rev-parse", m[-1] + "^2"]).stdout.strip())


def added_lines(before, tip):
    """{path: [text of each line this merge ADDED]} - the fix's own new code.

    Text, not line numbers: numbers from this diff belong to the branch's copy
    of the file, and eleven other branches have interleaved edits into today's
    tree since. Each line is relocated by its text in the current file before
    it is mutated, and skipped when that text is not unique there.
    """
    diff = run(["git", "diff", "-U0", before + ".." + tip, "--", "src",
                ":!src/ctrmap/tests"]).stdout
    out, path = {}, None
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            path = line[6:]
            out.setdefault(path, [])
        elif line.startswith("+") and not line.startswith("+++") and path:
            out[path].append(line[1:])
    return {p: v for p, v in out.items() if v}


SKIPPED_AMBIGUOUS = []


def relocate(path, texts):
    """Current line numbers of the given line texts in the tree under test."""
    src = (WT / path).read_text(encoding="utf-8", errors="replace").splitlines()
    index = {}
    for n, line in enumerate(src, 1):
        index.setdefault(line.strip(), []).append(n)
    found = []
    for t in texts:
        # only lines an operator could mutate count as "unmeasured" when they
        # cannot be placed; a duplicated `}` was never going to be measured
        eligible = any(p.match(t) for p in (THROW, REPORT, IF, RET_TRUE, RET_FALSE))
        hits = index.get(t.strip(), [])
        if len(hits) == 1:
            found.append(hits[0])
        elif len(hits) > 1 and eligible:
            SKIPPED_AMBIGUOUS.append((path.split("/")[-1], t.strip()[:60]))
    return sorted(set(found))


def mutants_for(path, lines):
    """(line index, kind, replacement) for each way to break this fix and compile."""
    src = (WT / path).read_text(encoding="utf-8", errors="replace").splitlines()
    found = []
    for ln in lines:
        i = ln - 1
        if i < 0 or i >= len(src):
            continue
        text = src[i]
        if THROW.match(text):
            found.append((i, "drop-throw", ""))
        elif REPORT.match(text):
            found.append((i, "drop-report", ""))
        elif IF.match(text):
            m = IF.match(text)
            found.append((i, "negate-if",
                          "%s%sif (!(%s)) {" % (m.group(1), m.group(2) or "", m.group(3))))
        elif RET_TRUE.match(text):
            found.append((i, "flip-return", RET_TRUE.match(text).group(1) + "return false;"))
        elif RET_FALSE.match(text):
            found.append((i, "flip-return", RET_FALSE.match(text).group(1) + "return true;"))
    return found


import require_build


def build():
    # The battery's own build and its stamp - never a bare javac. A javac-only
    # build once left build/classes with a stale catalogue, a guard suite failed
    # unmutated, and this sweep reported about a tree the battery never runs.
    # require_build refuses to proceed unless build/classes is exactly what
    # build.ps1 produced from exactly these sources, so that cannot recur here
    # or in any harness that calls it.
    ok = require_build.build_with_battery(WT)
    if ok:
        require_build.require_build(WT)
    return ok


if not (WT / ".git").exists():
    # say what is wrong, rather than throwing NotADirectoryError from inside
    # subprocess three frames down: the worktree is routinely removed during
    # cleanup, and the first symptom was a traceback that named neither it nor
    # the fix.
    raise SystemExit(
        "No worktree at %s - the sweep needs its own checkout.\n"
        "  git -C \"%s\" worktree add -b <branch> \"%s\" silent-failures"
        % (WT, (BASE / "CTRMap"), WT))

results, t0 = [], time.time()
# Freeze the tree under measurement at ONE commit and reset to that, never to
# the branch name. A run that reset to MAINLINE between clusters had
# the branch move underneath it - a merge landed mid-sweep - so its later
# clusters were scored against a tree its baseline had never seen. A sweep must
# measure one tree or it measures none.
run(["git", "reset", "--hard", MAINLINE])
FROZEN = run(["git", "rev-parse", "HEAD"]).stdout.strip()
print("measuring %s (frozen; the branch may move without affecting this run)" % FROZEN[:7], flush=True)
# resolved AFTER the reset, so the suites and arguments come from the tree
# about to be measured, not whatever test.ps1 the worktree held before
RESOLVED = resolve_clusters()
print("clusters: " + ", ".join("%s@%s" % (b, base[0][:7]) for b, (base, _) in RESOLVED.items()), flush=True)

# REFUSE TO SCORE AGAINST A RED BASELINE. If a suite already fails unmutated,
# every mutant it "detects" is a free kill and the whole run reads as healthier
# than it is. This check is the only thing standing between a contaminated sweep
# and a number nobody can tell is wrong.
print("baseline: the suites must pass UNMUTATED before anything is scored", flush=True)
if not build():
    raise SystemExit("baseline does not compile - fix the tree before measuring it")
_seen = set()
for _cid, (_b, _suites) in RESOLVED.items():
    for cls, a in _suites:
        if cls in _seen:
            continue                     # one suite guards several branches; check it once
        _seen.add(cls)
        r = run([JAVA, "-Xmx4g", "-Djava.awt.headless=true", "-cp", CP, cls] + a)
        if r.returncode != 0:
            raise SystemExit("baseline is RED: %s exits %s. Every mutant would score a free "
                             "kill against it. Fix the suite, then measure."
                             % (cls, r.returncode))
print("  ok: every guard suite passes unmutated\n", flush=True)

# A mutant is judged by every suite that guards its FILE, not only by the list
# of the branch that happens to have introduced the line. The per-branch lists
# are hand-maintained and drifted: NPCEditForm:485 is attributed to c5b, whose
# list does not name NpcEditFormGuardsTest - the suite that actually kills it -
# so a run reported 59 survivors the battery demonstrably kills. Union them.
FILE_SUITES = {}
for _cid, (_base, _suites) in RESOLVED.items():
    _before, _tip = _base
    for _path in added_lines(_before, _tip):
        _have = FILE_SUITES.setdefault(_path, [])
        for _s in _suites:
            if _s not in _have:
                _have.append(_s)
print("suite union: %d file(s), %.1f suites each on average"
      % (len(FILE_SUITES), sum(len(v) for v in FILE_SUITES.values()) / max(1, len(FILE_SUITES))),
      flush=True)

for cid, (base, suites) in RESOLVED.items():
    before, tip = base
    print("\n=== %s (merged from %s onto %s)" % (cid, tip[:7], before[:7]), flush=True)
    for path, texts in added_lines(before, tip).items():
        if not (WT / path).is_file():
            continue                     # a file the fix added and a later merge removed
        cands = mutants_for(path, relocate(path, texts))
        if not cands:
            continue
        step = max(1, len(cands) // CAP)
        picked = cands[::step][:CAP]
        print("  %s: %d mutable line(s), trying %d" % (path.split("/")[-1], len(cands), len(picked)),
              flush=True)

        for i, kind, repl in picked:
            original = (WT / path).read_text(encoding="utf-8", errors="replace")
            src = original.splitlines(keepends=True)
            ending = "\n" if src[i].endswith("\n") else ""
            src[i] = (repl + ending) if repl else ""
            (WT / path).write_text("".join(src), encoding="utf-8", newline="")

            if not build():
                verdict, detail = "nocompile", ""
            else:
                verdict, detail = "SURVIVED", ""
                # the branch's own suites first - they are the most specific and
                # usually the killer, so a kill still costs one or two runs;
                # only a true survivor pays for the whole union
                ordered = suites + [x for x in FILE_SUITES.get(path, []) if x not in suites]
                for cls, a in ordered:
                    r = run([JAVA, "-Xmx4g", "-Djava.awt.headless=true", "-cp", CP, cls] + a)
                    if r.returncode == Hung.returncode:
                        verdict, detail = "hung", cls.split(".")[-1] + ": never finished"
                        break
                    if r.returncode != 0:
                        tail = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
                        verdict = "killed"
                        detail = cls.split(".")[-1] + ": " + (tail[-1][:100] if tail else "")
                        break

            mark = "   <-- NOTHING ASSERTS THIS LINE" if verdict == "SURVIVED" else ""
            print("     L%-5d %-11s %-11s %s%s" % (i + 1, kind, verdict, detail, mark), flush=True)
            # EVERY attempt is recorded, nocompile included. Dropping them shrinks
            # the denominator silently: the last run reported "87 compiling
            # mutants" and never mentioned the 9 that were never measured at all.
            results.append(dict(cluster=cid, file=path.split("/")[-1], path=path, line=i + 1,
                                kind=kind, verdict=verdict, detail=detail,
                                code=original.splitlines()[i].strip()[:160]))
            (WT / path).write_text(original, encoding="utf-8", newline="")

    run(["git", "reset", "--hard", FROZEN])

build()
io.open(BASE / "wt/_state/mutation_semantic.json", "w", encoding="utf-8", newline="\n").write(
    json.dumps(results, indent=1))
tally = {}
for r in results:
    tally[r["verdict"]] = tally.get(r["verdict"], 0) + 1
survived = [r for r in results if r["verdict"] == "SURVIVED"]
scored = tally.get("killed", 0) + len(survived)

print("\n%d mutants attempted in %d min" % (len(results), (time.time() - t0) / 60))
if SKIPPED_AMBIGUOUS:
    # not measured, and said so: a line whose text is not unique in today's
    # file cannot be relocated from the branch's diff without guessing
    print("   %d added line(s) skipped as not uniquely relocatable, e.g. %s"
          % (len(SKIPPED_AMBIGUOUS), "; ".join("%s: %s" % s for s in SKIPPED_AMBIGUOUS[:3])))
for v in ("killed", "SURVIVED", "nocompile", "hung"):
    print("   %-10s %d" % (v, tally.get(v, 0)))
# NOTHING HIDDEN BY OMISSION: every attempt lands in exactly one bucket, and the
# buckets must add back up to the attempts. An unmeasured mutant is a fact about
# the measurement, not an absence of one.
assert sum(tally.values()) == len(results), "a mutant fell out of the accounting"
print("   accounting: %d killed + %d survived + %d nocompile + %d hung == %d attempted"
      % (tally.get("killed", 0), len(survived), tally.get("nocompile", 0),
         tally.get("hung", 0), len(results)))
if scored:
    print("   score: %d/%d = %.0f%% of MEASURABLE mutants killed (nocompile and hung are "
          "neither killed nor survived)" % (tally.get("killed", 0), scored,
                                            100.0 * tally.get("killed", 0) / scored))
for r in survived:
    print("   %-4s %-26s L%-5d %-11s %s" % (r["cluster"], r["file"], r["line"], r["kind"], r["code"][:70]))

# ---- the ratchet -------------------------------------------------------------
# A gate at "zero survivors" is unsatisfiable - even a module at 100% branch
# coverage does not kill every mutant - and a criterion nothing can satisfy never
# says stop. So: PER FILE, the survivor count may only fall. A tree-wide ceiling
# cannot tell a new file from a regression in an old one.
BASELINE = BASE / "wt/_state/mutation_baseline.json"
live = {}
for r in results:
    # keyed by the repo-relative path: a basename is not unique enough to trust,
    # and MutationBaselineTest re-reads each recorded line from exactly this file
    f = live.setdefault(r["path"], {"survivors": 0, "killed": 0, "unmeasured": 0, "lines": []})
    if r["verdict"] == "SURVIVED":
        f["survivors"] += 1
        f["lines"].append({"line": r["line"], "kind": r["kind"], "code": r["code"]})
    elif r["verdict"] == "killed":
        f["killed"] += 1
    else:
        f["unmeasured"] += 1

regressed = []
if BASELINE.exists():
    old = json.load(io.open(BASELINE, encoding="utf-8"))
    for f, cur in sorted(live.items()):
        was = old.get(f, {}).get("survivors")
        if was is not None and cur["survivors"] > was:
            regressed.append("%s: %d -> %d" % (f, was, cur["survivors"]))
    print("\nratchet: %d file(s) measured against the recorded baseline" % len(live))
    if regressed:
        print("  RATCHET BROKEN - survivors went UP:")
        for r in regressed:
            print("     " + r)
    else:
        print("  ok: no file's survivor count increased")
else:
    print("\nratchet: no baseline yet, recording this run as the starting line")

# The baseline names the sources it measured. MutationBaselineTest refuses a
# baseline whose digest is not the current build stamp's, so a fix line added
# after the sweep - a new mutation site nobody has measured - cannot hide
# behind an old count. This is the "denominator still matches the live source"
# invariant, done with the stamp instead of a second site count.
# per measured FILE, not the whole tree: the baseline says nothing about
# src/ctrmap/tests (the sweep never measures it), so an edit there must not
# invalidate it - and the baseline itself has to live outside src/ or
# committing it would change the digest it is checked against.
for _p, _f in live.items():
    _abs = WT / _p
    _f["sha256"] = hashlib.sha256(_abs.read_bytes()).hexdigest() if _abs.is_file() else ""
live["_meta"] = {"measured_at": FROZEN[:7],
                 "attempted": len(results), "survived": len(survived),
                 "killed": tally.get("killed", 0), "unmeasured": tally.get("nocompile", 0) + tally.get("hung", 0)}
io.open(BASELINE, "w", encoding="utf-8", newline="\n").write(json.dumps(live, indent=1))
print("baseline written to %s" % BASELINE)
print("  to make it the battery's gate, copy it to CTRMap/mutation_baseline.json "
      "and commit - MutationBaselineTest reads it from there")
if regressed:
    raise SystemExit(1)
