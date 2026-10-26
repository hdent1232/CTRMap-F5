# -*- coding: utf-8 -*-
"""Semantic mutation: break a fix in a way that still COMPILES, and see who notices.

The first mutator reverse-applied whole hunks. That answered NOBUILD for 72 of
188 - 38% - because a hunk often carries the very method or field the guard
calls, so the tree stops compiling before any suite runs. Coupling proven,
detection unproven, which is the weaker half of what a guard is for.

This one never removes a declaration. It edits one statement inside the fix's
own added lines, choosing edits that always compile and that recreate THIS
audit's failure mode - a tool that reports success while doing the wrong thing:

  drop-throw     delete a `throw ...;`         a refusal becomes a silent pass
  swallow-throw  `throw ...;` -> `return ...;` the error is caught and buried
  drop-report    delete a dialog/report call   the loud path goes quiet
  negate-if      `if (c)` -> `if (!(c))`       the guard condition inverts
  flip-return    `return true` <-> `false`     a validity check always agrees

A mutant that compiles and leaves every suite green is a line of the fix that
nothing asserts. That is the finding worth having, and unlike the reversal
mutator it can say so about the 38% that were silent before.

WHAT A MUTANT CAN COME BACK AS - six buckets, and the difference between them is
the whole value of the measurement:

  killed      a suite noticed. Either it exited non-zero, or it flooded its
              output - a flood under a mutation is still a detected behaviour
              change, and the reason is recorded so the two are never confused.
  SURVIVED    every suite that guards the file passed anyway. A hole.
  hung        a suite never finished. NOT a kill and never scored as one.
  nocompile   the harness emitted something javac rejected. That is a fact
              about THIS SCRIPT, not about the code, and it is printed as a
              defect to fix - not as a property of the tree.
  unmutable   no valid mutation of that kind exists for that line. Decided
              before anything is emitted where it can be (a report whose value
              is used, a throw whose enclosing return type cannot be read
              confidently), and otherwise by javac's own words afterwards -
              "missing return statement" on a dropped throw is a fact about
              the code, not a malformed edit.
  excluded    a line this harness deliberately does not score, each with a
              written reason. Counted in neither numerator nor denominator,
              and printed every run so it cannot quietly grow.

Usage: python tools/mutate2.py [max-mutants-per-file]
"""
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)  # no console window for a captured child (F184)

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root        # noqa: E402  - the project root, anchored: never counted (F216)
REPO = Path(_tree_root.require(__file__)).resolve()
BASE = REPO.parent   # the folder holding the repository - anchored on it, never counted (F216)
import bundle_hooks      # noqa: E402  - where the bundle's hooks are, found rather than spelled
sys.path.insert(0, bundle_hooks.path(str(REPO)))
import bundle_shell      # noqa: E402  - when a process began: a claim names its process, never a reusable pid alone
#ONE WORKTREE PER RUN, so several runs can share a sweep (--claims below): each names its own by CTRMAP_SWEEP_WT
WT = Path(os.environ["CTRMAP_SWEEP_WT"]).resolve() if os.environ.get("CTRMAP_SWEEP_WT") \
    else BASE / "wt" / "_guardcheck"
DUMP = BASE / "RomFS_original_garcs"
#the value after --anyway is a sentence, not the mutant cap
_RAW = sys.argv[1:]
#a word this does not read refuses the run, before anything runs (cli_words, 2026-10-10)
import cli_words  # noqa: E402
_VALUED = ("--anyway", "--claims", "--reuse")
#every flag spelled out: a tuple built by `+` is one test_flags_read_are_accepted cannot read, so it called this UNKNOWN
_UNREAD_WORDS = cli_words.refused(_RAW, flags=("--selftest", "--anyway", "--claims", "--reuse"), valued=_VALUED, most=1)
if _UNREAD_WORDS:
    print(_UNREAD_WORDS)
    raise SystemExit(2)
#THE SWEEP RUNS ON THE UNATTENDED DESKTOP, every suite it starts with it (2026-10-11: unattended suites
#opened the editor's windows on the owner's screen, and one cost them a window of edits) - see hidden_desktop
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "guard"))
import hidden_desktop  # noqa: E402
_AGAIN = hidden_desktop.on_the_unattended_desktop(__file__, _RAW)
if _AGAIN is not None:
    raise SystemExit(_AGAIN)
def bare_words(raw):
    """The words no flag took: every flag's value is its own word, never the mutant cap - not only --anyway's."""
    skip = {i + 1 for i, a in enumerate(raw) if a in _VALUED}
    return [a for i, a in enumerate(raw) if not a.startswith("-") and i not in skip]


_ARGS = bare_words(_RAW)
CAP = int(_ARGS[0]) if _ARGS else 6
#--claims <folder>: share the sweep with every run given the same folder; --reuse <log, results or folder>: take
#the verdicts a run already recorded (both below, at "sharing one sweep")
CLAIMS = next((_RAW[i + 1] for i, a in enumerate(_RAW) if a == "--claims"), None)
REUSE_FROM = [_RAW[i + 1] for i, a in enumerate(_RAW) if a == "--reuse"]

JDK = os.environ.get("CTRMAP_JDK")
if not JDK:
    # tolerated as missing here and refused later, at the point that needs it:
    # --selftest runs no Java at all, and the battery calls it on machines whose
    # JDK may live somewhere else entirely
    ad = Path(r"C:\Program Files\Eclipse Adoptium")
    found = sorted((d for d in ad.iterdir() if d.name.startswith("jdk-")),
                   key=lambda d: d.name) if ad.is_dir() else []
    JDK = str(found[-1]) if found else ""
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

# Work that was never on a fix branch, scored like a merged one: every line the range ADDED, judged by every
# suite that guards its file (suites_for derives them - a round names none by hand, which is the table that
# drifted). The 2026-10-06 audit's fixes were committed on the working branch, so no merge of sf/<branch>
# brings them in and the sweep scored none of them (F191). MEASURED 2026-10-10 by this file's own
# mutants_for over `git diff a93ef21`: 2,017 mutants in 161 production files - the owner chose the whole
# round over the 848 in game-data writers alone ("All of this round"). The tip is HEAD: the measurement
# commit the sweep is run on.
ROUNDS = {
    "audit-2026-10": ("a93ef21", "HEAD"),
}

SUBST = {
    "$a013": str(DUMP / "a/0/1/3"), "$a039": str(DUMP / "a/0/3/9"), "$a040": str(DUMP / "a/0/4/0"),
    "$pristine": str(DUMP), "$gamedir": str(BASE / "RomFS/000400000011C400"), "$step": "20",
}

# ---- lines this harness refuses to score, and why -----------------------------
# A line here is counted in NEITHER the numerator nor the denominator, and its
# reason is printed every run. Two rules make growing this list expensive, on
# purpose: the exact source text is recorded and a mismatch is a hard refusal
# (so an exclusion cannot drift onto a different line as the file moves), and
# the list has a hard ceiling. If a third line ever seems to belong here, the
# honest move is almost always to build the seam that makes it assertable - as
# Ui.record() already is for every OTHER caller of these two lines.
#
# Do not add a line here because no test happens to cover it. The bar is that no
# test COULD, without asserting its own instrument.
EXCLUSION_CEILING = 2
EXCLUSIONS = {
    ("src/ctrmap/Ui.java", 122): (
        "JOptionPane.showMessageDialog(parent, text, title, type);",
        "unreachable in a headless suite by construction: dialogs stay off unless the "
        "app's own main calls Ui.enableDialogs(), which no suite does and none may - "
        "that is the entire reason this seam exists. Nothing a headless test can "
        "observe distinguishes this line from its absence."),
    ("src/ctrmap/Ui.java", 119): (
        'System.out.println("[Ui] " + title + ": " + text.replace("\\n", " | "));',
        "this line IS the channel a suite reads a Ui message through when no sink is "
        "installed. A test asserting on it would be asserting its own instrument, and "
        "deleting the line makes any such assertion vacuously true - the mutant would "
        "be scored killed by a check that had stopped checking anything."),
}
if len(EXCLUSIONS) > EXCLUSION_CEILING:
    raise SystemExit("the exclusion list holds %d lines, over its ceiling of %d. Every entry "
                     "is a line nobody is watching on purpose; if the list needs to grow, that "
                     "decision belongs to a human, in a commit that says why."
                     % (len(EXCLUSIONS), EXCLUSION_CEILING))
# AND NO SLACK (F189): a ceiling above the list is room for a line to be excluded with nothing said.
# MutationBaselineTest reads EXCLUSION_CEILING from this line rather than keeping a copy.
if len(EXCLUSIONS) < EXCLUSION_CEILING:
    raise SystemExit("FAIL: the exclusion list holds %d lines under a ceiling of %d - lower the ceiling "
                     "to the list: a slot of slack is a line that could be excluded with nothing said"
                     % (len(EXCLUSIONS), EXCLUSION_CEILING))
for _k, _v in EXCLUSIONS.items():
    if len(_v[1]) < 60:
        raise SystemExit("exclusion %s:%d has no real reason written against it" % _k)


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


def resolve_round(name, base_rev, tip_rev):
    """(before sha, tip sha) of a round, or SystemExit naming the rev git could not resolve.

    A ROUND THAT CANNOT BE FOUND IS NOT AN EMPTY ONE: skipping it would score the tree as though the round
    had added nothing, which is the confident empty this file exists to refuse."""
    before = git("rev-parse", "--verify", "--quiet", base_rev + "^{commit}").stdout.strip()
    tip = git("rev-parse", "--verify", "--quiet", tip_rev + "^{commit}").stdout.strip()
    if not before or not tip:
        raise SystemExit("round %s names %s..%s, and git does not resolve %s - a round that cannot be read"
                         " is not one with nothing in it" % (name, base_rev, tip_rev,
                                                             base_rev if not before else tip_rev))
    return before, tip


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
    for name, (base_rev, tip_rev) in ROUNDS.items():
        resolved[name] = (resolve_round(name, base_rev, tip_rev), [])
    # How many suites this table cannot see. Measured, not asserted: when this
    # check was first written as a refusal it named 48 of the 85 suites test.ps1
    # registers - more than half the battery, invisible to the sweep. That is
    # why the survivor counts were wrong, and it is also why the answer is not
    # "go and name all 48". A table somebody has to remember to update is the
    # defect, not an incomplete instance of it. Correctness now comes from
    # ALL_SUITES, which no one has to maintain; this table only decides what
    # order to try things in, so a gap here costs time and never truth. Printed
    # every run so the cost stays visible.
    unnamed = sorted(set(known) - {s for ss in CLUSTERS.values() for s in ss})
    if unnamed:
        print("  note: %d of %d registered suite(s) are named by no cluster, so a kill by one of\n"
              "        them costs a full pass rather than one run. Correctness does not depend on\n"
              "        this table - see ALL_SUITES - only speed does.\n"
              "        %s" % (len(unnamed), len(known), ", ".join(unnamed)), flush=True)
    return resolved

THROW = re.compile(r"^\s*throw\s+new\s+\w")
#A PRINT TO ANY STREAM IS A REPORT, not one to System.out alone (2026-10-15): the updater's and both harvesters' reports
#were handed a PrintStream so a suite could read them, and `said.println(...)` matched nothing here - every one of
#those lines dropped out of the measurement unseen, and SilentCatchTest stopped counting the same shape in a catch
REPORT = re.compile(r"^\s*(JOptionPane\.show\w+|[A-Za-z_][\w.]*\.print(?:ln|f)?\(|\w*[Dd]ialog\w*\.\w+|"
                    r"\w*[Ss]tatus\w*\.setText|bad\.add|problems\.add|warn\w*\()")
IF = re.compile(r"^(\s*)(\}\s*else\s+)?if\s*\((.+)\)\s*\{\s*$")
RET_TRUE = re.compile(r"^(\s*)return\s+true\s*;\s*$")
RET_FALSE = re.compile(r"^(\s*)return\s+false\s*;\s*$")

# ---- running a child without letting it take the harness down ----------------
# The verdict only ever reads the LAST line of a suite's output, so only a tail
# is worth keeping. Anything past the flood ceiling is a behaviour change in its
# own right and is reported as one.
TAIL_BYTES = 256 * 1024
FLOOD_BYTES = 32 * 1024 * 1024
HUNG_RC = -99


class Hung(object):
    """A run that never finished. NOT a kill - see run()."""
    returncode = HUNG_RC
    stdout = stderr = ""
    flooded = False
    out_bytes = 0


class Ran(object):
    """What a child did, with its output kept only as a bounded tail."""

    def __init__(self, returncode, stdout, stderr, flooded, out_bytes):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr
        self.flooded = flooded
        self.out_bytes = out_bytes


class Tail(object):
    """Last `limit` bytes of a stream, and how many bytes went past. limit=None keeps all."""

    def __init__(self, limit=TAIL_BYTES):
        self.limit, self.buf, self.total = limit, b"", 0

    def add(self, chunk):
        self.total += len(chunk)
        self.buf = self.buf + chunk
        if self.limit is not None:
            self.buf = self.buf[-self.limit:]

    def text(self):
        return self.buf.decode("utf-8", "replace")


def _drain(stream, sink):
    """Read a pipe to EOF, keeping only the tail. Never blocks the child."""
    try:
        while True:
            chunk = stream.read(65536)
            if not chunk:
                break
            sink.add(chunk)
    except Exception:
        pass
    finally:
        try:
            stream.close()
        except Exception:
            pass


def run(cmd, timeout=900, keep=TAIL_BYTES):
    # NO -Djava.awt.headless=true ANYWHERE IN THIS FILE. The sweep answers "would
    # the BATTERY notice this?", and test.ps1 does not pass that flag - so a run
    # that does is answering about a different program. It is not academic: a map
    # load a headless JVM refuses at its progress dialog SUCCEEDS with a display,
    # and everything after it then runs against a loaded map instead of an empty
    # one. The same mistake was found and fixed in tools/guard/replant.py on the
    # same day, where it had left a guard green under the flag and red under the
    # battery. The baseline check is what stands behind this: a suite that cannot
    # pass unmutated without the flag stops the sweep before anything is scored.
    # keep=None means the CALLER PARSES THE WHOLE OUTPUT and must not be handed
    # a sample of it. Every git call does. The first cut of this fix bounded
    # every run at a 256 KB tail, and c1's merge diff is 376 KB: its head was
    # dropped, added_lines parsed from the middle of a hunk, and the sweep went
    # from 43 measured files to 35 before a single mutant ran. Truncating output
    # that is about to be parsed is not a smaller version of the same defect -
    # it is a new one, and a quieter one. So: a tail for suites, whose last line
    # is all the verdict reads; everything for anything that is parsed.
    #
    # A hang must never be scored as a kill. A mutant that makes a suite loop
    # forever has not been detected by that suite; counting it killed inflates
    # the score in the one direction this measurement must never drift. The
    # sister project scored two mutants "HUNG - counted as killed" with nothing
    # actually hung, because their bound was derived from the wrong unit.
    #
    # THAT RULE IS INTACT. What changed is that a flood is no longer mistaken
    # for a hang. subprocess.run(capture_output=True) buffers a child's ENTIRE
    # output with no cap: TilePainterForm.java:518 under negate-if printed until
    # Python's own reader thread raised MemoryError, and the sweep recorded the
    # mutant "hung - PaintApplyGuardsTest: never finished" when nothing had hung
    # at all - a detected behaviour change filed as an unmeasurable one. So the
    # tail is bounded, the pipes are drained by threads that never block the
    # child, and three outcomes are told apart: a genuine timeout (Hung, never a
    # kill), a flood (a kill, with the reason recorded), and an ordinary
    # non-zero exit.
    proc = subprocess.Popen(cmd, cwd=str(WT), stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, bufsize=0, creationflags=NO_WINDOW)
    out, err = Tail(keep), Tail(keep)
    threads = [threading.Thread(target=_drain, args=(proc.stdout, out)),
               threading.Thread(target=_drain, args=(proc.stderr, err))]
    for t in threads:
        t.daemon = True
        t.start()
    flooded, deadline = False, time.time() + timeout
    while True:
        try:
            proc.wait(timeout=0.2)
            break
        except subprocess.TimeoutExpired:
            pass
        # no flood cut when the whole output is wanted: cutting it would be the
        # same silent truncation, just triggered later
        if keep is not None and out.total + err.total > FLOOD_BYTES:
            flooded = True
            _kill(proc)
            break
        if time.time() > deadline:
            _kill(proc)
            for t in threads:
                t.join(10)
            return Hung()
    for t in threads:
        t.join(30)
    try:
        proc.wait(timeout=30)
    except subprocess.TimeoutExpired:
        _kill(proc)
    return Ran(proc.returncode, out.text(), err.text(), flooded, out.total + err.total)


def _kill(proc):
    try:
        proc.kill()
    except Exception:
        pass


def git(*args):
    """A git call, with its whole output. Everything git says here is parsed."""
    return run(["git"] + list(args), keep=None)


def merge_parents(branch):
    """(before, branch tip) of the merge that brought sf/<branch> into silent-failures.

    merge-base is the wrong primitive once a branch is merged: it returns the
    branch's own tip, the diff against it is empty, and a sweep would report a
    perfect baseline of nothing. The merge commit's first parent is the tree the
    branch landed on and its second parent is the branch; their diff is exactly
    what that merge introduced - including for c47 and c2, which had merged
    silent-failures into themselves first.
    """
    m = git("log", "--merges", "--first-parent", "--format=%H",
            "--grep=Merge branch 'sf/%s'" % branch, MAINLINE).stdout.split()
    if not m:
        return None, None
    # resolved to real shas so the log names the two trees, not one merge twice
    return (git("rev-parse", m[-1] + "^1").stdout.strip(),
            git("rev-parse", m[-1] + "^2").stdout.strip())


def whole_diff_or_refuse(diff, before="", tip=""):
    """Refuse a diff that does not start where a diff starts.

    The only way to be handed a partial diff is for something to have sampled
    it, and a partial diff does not announce itself: it parses perfectly, names
    fewer files, and the sweep reports a smaller measurement that looks entirely
    healthy. That happened - a 256 KB output cap ate the head of c1's 376 KB
    merge diff and eight files silently left the sweep. Two characters of check
    make it loud instead.
    """
    if diff and not diff.startswith("diff --git"):
        raise SystemExit(
            "TRUNCATED DIFF for %s..%s - it does not begin with 'diff --git' but with:\n    %s\n"
            "Something sampled output that is parsed in full. Every git call must go through "
            "git(), which passes keep=None." % (before[:7], tip[:7], diff[:120]))
    return diff


def added_lines(before, tip):
    """{path: [text of each line this merge ADDED]} - the fix's own new code.

    Text, not line numbers: numbers from this diff belong to the branch's copy
    of the file, and eleven other branches have interleaved edits into today's
    tree since. Each line is relocated by its text in the current file before
    it is mutated, and skipped when that text is not unique there.
    """
    diff = git("diff", "-U0", before + ".." + tip, "--", "src",
               ":!src/ctrmap/tests").stdout
    whole_diff_or_refuse(diff, before, tip)
    out, path = {}, None
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            path = line[6:]
            out.setdefault(path, [])
        elif line.startswith("+") and not line.startswith("+++") and path:
            out[path].append(line[1:])
    return {p: v for p, v in out.items() if v}


SKIPPED_AMBIGUOUS = []
SKIPPED_GONE = []


def baseline_path():
    """THE ONE COPY - see the ratchet at the bottom of this file for why it is found
    from this script's own location. It is a function because the suite ordering
    reads the last run BEFORE the sweep starts and the ratchet writes it after, and those
    two must not be able to drift into naming different files."""
    return REPO / "mutation_baseline.json"


def read_src(path):
    """The file exactly as it sits on disk, line endings included.

    newline="" on both halves of the read/restore pair. The previous version
    read with universal newlines and wrote back LF, so every file it touched was
    silently re-ended mid-sweep - harmless for javac, but the harness has no
    business rewriting bytes it was only asked to read.
    """
    with io.open(str(WT / path), encoding="utf-8", errors="replace", newline="") as handle:
        return handle.read()


def write_src(path, text):
    with io.open(str(WT / path), "w", encoding="utf-8", newline="") as handle:
        handle.write(text)


def relocate(path, texts):
    """Current line numbers of the given line texts in the tree under test.

    TWO ways a fix line fails to reach the measurement, and both must be
    counted. A line whose text appears MORE than once cannot be placed without
    guessing. A line whose text appears NOT AT ALL was edited or removed by a
    later merge - which is ordinary, twelve branches interleave here, but it is
    not nothing: the sweep set out to measure that line and did not.

    The second bucket used to fall out of the loop with nothing recorded, so the
    report's one figure for "what this sweep did not look at" counted only the
    duplicates. Measured on master at 270d58f, over the added lines of all 23
    merged branches:

                            occurrences     distinct (file, text)
        placed uniquely         257                 224
        duplicated               74                  38   <- the figure printed
        absent                  133                  68   <- recorded nowhere

    The 74 is the number this report has been showing as the whole of what it
    did not look at; the true figure was 207. A measurement that quietly gets
    smaller is precisely what this harness exists to make impossible, and it was
    doing it to itself.

    Absent is not automatically lost coverage: where the later edit came from
    another merged branch, the line that replaced it is in THAT branch's diff
    and is measured there. Where it did not, nothing measures it. The harness
    cannot tell those apart without guessing either, so it reports the count and
    the lines and leaves the judgement to a reader, rather than dropping both.
    """
    src = read_src(path).splitlines()
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
        elif not eligible:
            continue
        elif len(hits) > 1:
            SKIPPED_AMBIGUOUS.append((path.split("/")[-1], t.strip()[:60]))
        else:
            SKIPPED_GONE.append((path.split("/")[-1], t.strip()[:60]))
    return sorted(set(found))


# ---- reading Java well enough to emit something that compiles ----------------

def _clean(line, in_comment):
    """(line with literals and comments blanked, still-in-block-comment).

    Brackets inside a string are not brackets. Without this the extent scan
    below balances on `"("` and stops in the middle of a statement.
    """
    out, i, n = [], 0, len(line)
    while i < n:
        c = line[i]
        if in_comment:
            if c == "*" and i + 1 < n and line[i + 1] == "/":
                in_comment = False
                i += 2
            else:
                i += 1
            continue
        if c == "/" and i + 1 < n and line[i + 1] == "/":
            break
        if c == "/" and i + 1 < n and line[i + 1] == "*":
            in_comment = True
            i += 2
            continue
        if c == '"' or c == "'":
            q, i = c, i + 1
            while i < n:
                if line[i] == "\\":
                    i += 2
                    continue
                if line[i] == q:
                    i += 1
                    break
                i += 1
            out.append(" ")          # a literal is one opaque token
            continue
        out.append(c)
        i += 1
    return "".join(out), in_comment


def comment_state(src):
    """Whether each line STARTS inside a block comment."""
    state, inc = [], False
    for line in src:
        state.append(inc)
        _, inc = _clean(line, inc)
    return state


MAX_STMT_LINES = 24


def stmt_extent(src, state, i):
    """(first, last) line indices of the statement starting at src[i], or None.

    A statement is not a line, and this harness used to pretend otherwise.
    Fourteen of the last sweep's 27 "nocompile" verdicts were multi-line calls
    whose first line was deleted and whose continuation was left dangling: the
    harness emitted a syntax error and then filed it as a fact about the code.
    Balance the brackets, stop at the semicolon that closes the statement, and
    where that cannot be done inside a sane span, say so instead of guessing.
    """
    if state[i]:
        return None                  # the "statement" is comment text
    depth, inc = 0, state[i]
    for j in range(i, min(len(src), i + MAX_STMT_LINES)):
        code, inc = _clean(src[j], inc)
        for ch in code:
            if ch in "([{":
                depth += 1
            elif ch in ")]}":
                depth -= 1
        if depth < 0:
            return None
        if depth == 0 and code.rstrip().endswith(";"):
            return (i, j)
    return None


MODS = r"(?:public|protected|private|static|final|abstract|synchronized|native|strictfp|default)"
SIG = re.compile(r"^\s*(?:" + MODS + r"\s+)*"
                 r"(?:<[^<>]+>\s+)?"
                 r"(?P<type>[\w$.]+(?:\s*<[^;{}()]*>)?(?:\s*\[\s*\])*)\s+"
                 r"(?P<name>[\w$]+)\s*\(")
CTOR = re.compile(r"^\s*(?:" + MODS + r"\s+)*(?P<name>[A-Z][\w$]*)\s*\(")
# SIG's modifier prefix can give ground when it has to, and Python has no
# possessive quantifier to stop it: on `public WorldAnim(byte[] data) {` the
# prefix matched nothing, "public" was read as the return type, and three
# constructors got `return null;` - "incompatible types: unexpected return
# value". A keyword is never a return type, so reject it and let CTOR have the
# line.
NOT_A_TYPE = {
    "public", "protected", "private", "static", "final", "abstract", "synchronized",
    "native", "strictfp", "default", "transient", "volatile",
    "return", "new", "throw", "throws", "else", "case", "instanceof", "assert", "yield",
    "if", "for", "while", "switch", "catch", "do", "try", "super", "this",
    "break", "continue", "package", "import", "class", "interface", "enum",
    "extends", "implements", "null", "true", "false",
}


def _body_span(src, state, sig, i):
    """How the method declared at src[sig] relates to line i.

    "abstract" - it has no body; "closed" - its body ended before line i, so it
    is not the enclosing method; "lambda" - a lambda BLOCK is still open at line
    i, so the declared return type is not the one that governs there;
    "open" - line i is inside this method's own body.
    """
    inc = state[sig]
    depth_par, started, depth = 0, False, 0
    lambda_marks, pending = [], False
    for j in range(sig, i):
        code, inc = _clean(src[j], inc)
        k = 0
        while k < len(code):
            ch = code[k]
            if not started:
                if ch == "(":
                    depth_par += 1
                elif ch == ")":
                    depth_par -= 1
                elif ch == ";" and depth_par <= 0:
                    return "abstract"
                elif ch == "{" and depth_par <= 0:
                    started, depth = True, 1
                k += 1
                continue
            if ch == "-" and k + 1 < len(code) and code[k + 1] == ">":
                rest = code[k + 2:].lstrip()
                # an expression lambda cannot contain a statement, so only a
                # BLOCK lambda can be the thing enclosing our line
                if rest.startswith("{") or rest == "":
                    pending = True
                k += 2
                continue
            if ch == "{":
                depth += 1
                if pending:
                    lambda_marks.append(depth)
                    pending = False
            elif ch == "}":
                if lambda_marks and lambda_marks[-1] == depth:
                    lambda_marks.pop()
                depth -= 1
                if depth == 0:
                    return "closed"
            k += 1
    if not started:
        return "closed"
    return "lambda" if lambda_marks else "open"


def enclosing_return(src, state, i):
    """Declared return type of the method containing src[i], "<ctor>", or None.

    Scan backwards for the nearest method signature whose body is still open at
    line i. The brace check is what makes this safe rather than plausible: the
    nearest signature above a line is very often an anonymous class's method
    that already closed, and taking it would put `return null;` in a method that
    returns void. Where the answer cannot be settled - a block lambda in the
    way, no signature at all - this returns None and the CALLER MUST SKIP THE
    LINE and record it unmutable. A guessed type emits a mutant that does not
    compile, and a nocompile is a fact about this harness, not about the code.
    """
    j, tries = i - 1, 0
    while j >= 0 and tries < 16:
        if state[j]:
            j -= 1
            continue
        code, _ = _clean(src[j], False)
        kind = None
        m = SIG.match(code)
        if m and m.group("type") not in NOT_A_TYPE:
            kind = m.group("type").replace(" ", "")
        else:
            c = CTOR.match(code)
            if c and c.group("name") not in NOT_A_TYPE:
                kind = "<ctor>"
        if kind is None:
            j -= 1
            continue
        tries += 1
        span = _body_span(src, state, j, i)
        if span == "closed" or span == "abstract":
            j -= 1                   # an inner method that already ended; keep looking outward
            continue
        return kind if span == "open" else None
    return None


PRIMITIVE_BENIGN = {
    "void": "return;", "<ctor>": "return;",
    "boolean": "return false;",
    "byte": "return 0;", "short": "return 0;", "char": "return 0;", "int": "return 0;",
    "long": "return 0;", "float": "return 0;", "double": "return 0;",
}
REF_TYPE = re.compile(r"^[\w$.]+(?:<.*>)?(?:\[\])*$")


def benign_return(t):
    """The `return ...;` that swallows an error in a method declared to return t."""
    if t in PRIMITIVE_BENIGN:
        return PRIMITIVE_BENIGN[t]
    # everything left that is a legal return type is a reference type - a class,
    # an array or a type variable - and all of them accept null
    return "return null;" if REF_TYPE.match(t) else None


def deleted(text):
    """What a deleted statement leaves behind: an empty block at the same indent.

    Not nothing. A statement can be the UNBRACED body of an if or a for -
    BchModelAppender:126 is - and removing those lines outright either fails to
    compile ("variable declaration not allowed here") or, worse, silently
    swallows the NEXT statement into the if and mutates something the record
    does not name. An empty block is a legal statement anywhere a statement was
    legal and is a no-op, so the deletion means exactly what it says.
    """
    return re.match(r"^\s*", text).group(0) + "{ }"


def value_is_used(code):
    """True when the statement's own value is consumed, so deleting it cannot compile.

    REPORT matches on a prefix, and prefixes lie: `BuildingPaletteDialog.Pick
    pick = BuildingPaletteDialog.pick(...)` matched the dialog pattern and was
    a variable DECLARATION. Deleting it left every later use of `pick`
    undeclared. That line has no valid drop-report mutation at all, which is a
    different fact from "the mutation failed to compile" and is now recorded as
    one.
    """
    d = 0
    for k, c in enumerate(code):
        if c in "([{":
            d += 1
        elif c in ")]}":
            d -= 1
        elif c == "=" and d == 0:
            prev = code[k - 1] if k else ""
            nxt = code[k + 1] if k + 1 < len(code) else ""
            if prev not in "=!<>+-*/%&|^" and nxt != "=":
                return True
    return code.lstrip().startswith("return ")


def mutants_for(path, lines):
    """(candidates, noted) for this file.

    A candidate is a mutation that will actually be emitted and measured. A
    noted entry is a line that will not be - unmutable or excluded - and it is
    recorded and printed rather than dropped, because a line nobody measured is
    a fact about the measurement.
    """
    src = read_src(path).splitlines()
    state = comment_state(src)
    cands, noted = [], []

    def note(i, kind, verdict, why):
        noted.append(dict(i=i, j=i, kind=kind, repl=None, verdict=verdict, why=why))

    for ln in lines:
        i = ln - 1
        if i < 0 or i >= len(src):
            continue
        text = src[i]
        excl = EXCLUSIONS.get((path, ln))
        if excl is not None:
            want, why = excl
            if text.strip() != want:
                raise SystemExit(
                    "STALE EXCLUSION %s:%d - it was written against\n    %s\nbut the line now "
                    "reads\n    %s\nAn exclusion that has drifted onto another line is worse "
                    "than none: re-check that the reason still holds, then update or delete it."
                    % (path, ln, want, text.strip()))
            note(i, "excluded", "excluded", why)
            continue

        if state[i] and (THROW.match(text) or REPORT.match(text)):
            # commented-out debug code still matches the report pattern; it is
            # not a statement and deleting it changes nothing, so scoring it
            # would have manufactured a survivor out of a comment
            note(i, "drop-report" if REPORT.match(text) else "drop-throw", "unmutable",
                 "the line is inside a block comment - it is not code")
            continue

        if THROW.match(text):
            ext = stmt_extent(src, state, i)
            if ext is None:
                note(i, "drop-throw", "unmutable",
                     "the throw statement's extent could not be read")
                continue
            cands.append(dict(i=i, j=ext[1], kind="drop-throw", repl=deleted(text),
                              verdict=None, why=""))
            # The operator this project's entire failure mode is named after:
            # swallow the error and carry on. It also compiles exactly where
            # drop-throw cannot - a throw that is its method's only exit leaves
            # "missing return statement" behind when deleted, which is why 13 of
            # the last sweep's nocompiles were never mutants at all.
            rt = enclosing_return(src, state, i)
            repl = benign_return(rt) if rt else None
            if repl is None:
                note(i, "swallow-throw", "unmutable",
                     "the enclosing method's return type could not be read confidently"
                     + ("" if rt is None else " (read as %r)" % rt))
            else:
                indent = re.match(r"^\s*", text).group(0)
                cands.append(dict(i=i, j=ext[1], kind="swallow-throw", repl=indent + repl,
                                  verdict=None, why=""))
        elif REPORT.match(text):
            ext = stmt_extent(src, state, i)
            if ext is None:
                note(i, "drop-report", "unmutable",
                     "the statement's extent could not be read")
                continue
            joined = ""
            inc = state[i]
            for j in range(ext[0], ext[1] + 1):
                c, inc = _clean(src[j], inc)
                joined += c + " "
            if value_is_used(joined):
                note(i, "drop-report", "unmutable",
                     "the statement's value is used - deleting it would leave its uses dangling")
                continue
            cands.append(dict(i=i, j=ext[1], kind="drop-report", repl=deleted(text),
                              verdict=None, why=""))
        elif IF.match(text):
            m = IF.match(text)
            cands.append(dict(i=i, j=i, kind="negate-if",
                              repl="%s%sif (!(%s)) {" % (m.group(1), m.group(2) or "", m.group(3)),
                              verdict=None, why=""))
        elif RET_TRUE.match(text):
            cands.append(dict(i=i, j=i, kind="flip-return",
                              repl=RET_TRUE.match(text).group(1) + "return false;",
                              verdict=None, why=""))
        elif RET_FALSE.match(text):
            cands.append(dict(i=i, j=i, kind="flip-return",
                              repl=RET_FALSE.match(text).group(1) + "return true;",
                              verdict=None, why=""))
    return cands, noted


# ---- ONE LINE IS ONE LINE ----------------------------------------------------
# The sweep walks 23 branches and a file touched by four of them is measured
# four times. The previous version counted each of those as a separate mutant:
# 54 recorded survivors were only 44 distinct lines, CtrmapMainframe:1256 was
# counted three times, and every per-file total downstream - including the
# ratchet's - inherited the inflation. Aggregate by (path, line, kind) first;
# everything after this point talks about LINES.
#
# When the branches disagree about the same line the disagreement is the
# interesting part, so it is kept and printed rather than resolved away. Killed
# anywhere means killed: a suite that catches the mutation catches it regardless
# of whose turn it was.
PRECEDENCE = ["killed", "SURVIVED", "hung", "nocompile", "unmutable", "excluded"]


def aggregate(results):
    """(one record per (path, line, kind), verdict tally, disagreeing records)."""
    distinct = {}
    for r in results:
        k = (r["path"], r["line"], r["kind"])
        d = distinct.get(k)
        if d is None:
            d = distinct[k] = dict(r, clusters=[], verdicts=[])
        d["clusters"].append(r["cluster"])
        d["verdicts"].append(r["verdict"])
        if PRECEDENCE.index(r["verdict"]) < PRECEDENCE.index(d["verdict"]):
            d["verdict"], d["detail"] = r["verdict"], r["detail"]
    lines_ = sorted(distinct.values(), key=lambda d: (d["path"], d["line"], d["kind"]))
    tally = {}
    for d in lines_:
        tally[d["verdict"]] = tally.get(d["verdict"], 0) + 1
    return lines_, tally, [d for d in lines_ if len(set(d["verdicts"])) > 1]


def build_live(lines_):
    """The per-file record the baseline is written from, keyed by repo-relative path."""
    live = {}
    for d in lines_:
        # keyed by the repo-relative path: a basename is not unique enough to
        # trust, and MutationBaselineTest re-reads each recorded line from
        # exactly this file
        f = live.setdefault(d["path"], {"survivors": 0, "killed": 0, "unmeasured": 0,
                                        "unmutable": 0, "excluded_count": 0,
                                        "lines": [], "killed_lines": [], "excluded": []})
        if d["verdict"] == "SURVIVED":
            f["survivors"] += 1
            f["lines"].append({"line": d["line"], "kind": d["kind"], "code": d["code"]})
        elif d["verdict"] == "killed":
            f["killed"] += 1
            # recorded line by line, not just counted, so the ratchet can tell a
            # line that REGRESSED (killed before, survives now) from one measured
            # for the first time. Without this the two are indistinguishable and
            # the gate has to guess - which is how it came to cry wolf.
            # WHICH SUITE KILLED IT is recorded too. It was known at the moment of the
            # kill and thrown away, and the next run then guessed the order to try
            # suites in from a hand table measured wrong about 48 of 86 files - so a
            # line killed by the fortieth suite last run paid for thirty-nine JVM
            # starts again this run. Evidence the sweep already had beats a table
            # somebody has to remember to update.
            k = {"line": d["line"], "kind": d["kind"]}
            if d.get("by"):
                k["by"] = d["by"]
            f["killed_lines"].append(k)
        elif d["verdict"] == "unmutable":
            f["unmutable"] += 1
        elif d["verdict"] == "excluded":
            f["excluded_count"] += 1
            f["excluded"].append({"line": d["line"], "reason": d["detail"]})
        else:
            f["unmeasured"] += 1
    return live


def compare_to_baseline(live, old):
    """(regressed, new coverage, fixed, whether the baseline records kills per line).

    PER FILE, the survivor SET may only shrink - a gate at "zero survivors" is
    unsatisfiable, and a criterion nothing can satisfy never says stop.

    It compares LINES, not counts, for two reasons. A count went up four times in
    the last run - CtrmapMainframe 2 -> 10, SetupWizard 1 -> 7 - with nothing
    regressed at all: those files were simply measured on more branches than the
    run that recorded the baseline, and the duplicates were counted. And a count
    cannot be acted on. A gate that cries wolf gets ignored, which is exactly how
    a real regression slips through, so this one names the lines - and separates
    a line that WAS killed and now survives (a regression) from one that was
    never measured before (new coverage, and not the gate's business).
    """
    regressed, fresh, fixed, lost = [], [], [], []
    had_killed_lines = any(isinstance(v, dict) and v.get("killed_lines")
                           for k, v in old.items() if k != "_meta")
    for f, cur in sorted(live.items()):
        was = old.get(f)
        if not isinstance(was, dict):
            continue
        now_s = set((e["line"], e["kind"]) for e in cur["lines"])
        old_s = set((e["line"], e["kind"]) for e in was.get("lines", []))
        old_k = set((e["line"], e["kind"]) for e in was.get("killed_lines", []))
        moved = bool(was.get("sha256")) and was["sha256"] != cur.get("sha256", "")
        for key in sorted(now_s - old_s):
            where = "%s:%d %s" % (f, key[0], key[1])
            if moved:
                fresh.append(where + " - the file changed since the baseline, so its line "
                                     "numbers cannot be compared; re-read this one by hand")
            elif key in old_k:
                regressed.append(where + " - the baseline recorded this line KILLED")
            else:
                fresh.append(where + " - not in the baseline at all (newly measured)")
        # A survivor that has left the survivor list is not automatically a
        # survivor that got asserted. It can have been EXCLUDED by hand, or
        # found unmutable, or simply not measured this time - and reporting any
        # of those as "now asserted" credits the battery with a kill nobody
        # made. Ui.java:84 and :87 were reported fixed on the very run that
        # excluded them.
        killed_now = set((e["line"], e["kind"]) for e in cur.get("killed_lines", []))
        excluded_now = set(e["line"] for e in cur.get("excluded", []))
        # Killed before, and now neither killed nor surviving: it was not
        # measured. That is lost coverage, and it reads as innocent because
        # the SCORE can go up - hung, nocompile and unmutable all leave the
        # denominator. Only flagged when the file has not moved, since line
        # numbers are not comparable across an edit.
        if not moved:
            for key in sorted(old_k - killed_now - now_s):
                if key[0] in excluded_now:
                    continue                 # deliberately out of the measurement
                lost.append("%s:%d %s - the baseline recorded this line KILLED, and this "
                            "run did not measure it at all" % (f, key[0], key[1]))
        for key in sorted(old_s - now_s):
            if key in killed_now:
                why = "is now asserted - a suite kills it"
            elif key[0] in excluded_now:
                why = ("is now EXCLUDED from the measurement by hand - NOT asserted; it counts "
                       "in neither the numerator nor the denominator")
            else:
                why = "is no longer measured at all - check why before reading it as progress"
            fixed.append("%s:%d %s %s" % (f, key[0], key[1], why))
    return regressed, fresh, fixed, lost, had_killed_lines


import require_build


# ---- a mutant must never outlive the harness --------------------------------
# Between "write the mutated source" and "write the original back" the tree on
# disk carries an injected fault. The restore used to be a plain statement at the
# bottom of the loop, so anything that escaped between the two - a MemoryError
# from a chatty child (it happened), a KeyboardInterrupt, a SystemExit raised by
# a mid-sweep refusal - left the fault on disk, showing in git status as an
# ordinary modification, and the git reset at the very end never ran either.
# Restore is now owed the moment the mutated bytes land, and paid by atexit on
# any Python-level exit. A hard kill (SIGKILL, power) still cannot be caught in
# process; that is what the frozen-sha reset on the next run is for.
import atexit

#: WHERE THE LOCK LIVES: beside the mutants, in the worktree this sweep mutates (WT), and through
#: bundle_rules' one door - take_lock and release_lock (F179). It was written with mode 'w' at
#: BASE, the repository's PARENT, where no reader looks - a producer with no consumer, the defect
#: this project has filed in seven consecutive audits - and its removal was never counted, so the
#: hooks' came-and-went check could not see a sweep. The repository itself holds no mutant: a
#: sweep never writes there, so a read of the repository's file is not a read of a mutant.


def _door():
    """bundle_rules, where the lock's one door is."""
    repo = str(REPO)
    sys.path.insert(0, os.path.join(repo, "tools"))
    import bundle_hooks
    sys.path.insert(0, bundle_hooks.path(repo))
    import bundle_rules
    return bundle_rules


class _Pending(list):
    """The mutants currently on disk, and the lock that says so.

    A list rather than two calls at each apply/restore site: the lock is maintained by the
    thing that knows, so a site added later cannot forget it. A GUARD AT CALL SITES IS ONE
    CALL SITE FROM BROKEN.
    """

    #: the file the lock names while this list holds a mutant, or None
    _held = None

    def _sync(self):
        """Take the lock when a mutant lands, release it when none is left, re-take it when the
        file changes. A lock that cannot be taken STOPS the sweep: another run holds this tree,
        or it cannot be written - and a mutant on disk with no lock naming it is the read this
        is here to refuse. atexit puts every pending file back on the way out."""
        want = str(self[-1][0]).replace(chr(92), "/") if self else None
        if want == self._held:
            return
        door = _door()
        if self._held is not None:
            stuck = door.release_lock(str(WT))
            self._held = None
            if stuck:
                raise SystemExit("the mutation lock could not come off: %s" % stuck)
        if want is not None:
            refused = door.take_lock(str(WT), want)
            if refused:
                raise SystemExit("REFUSING to leave a mutant on disk unlocked: %s" % refused)
            self._held = want

    def append(self, item):
        list.append(self, item)
        self._sync()

    def pop(self, *args):
        got = list.pop(self, *args)
        self._sync()
        return got

    def __delitem__(self, key):
        list.__delitem__(self, key)
        self._sync()


_PENDING_RESTORE = _Pending()  # [(path, original_text)] while a mutant is on disk


def _restore_pending():
    """Put back any source file still carrying a mutant. Idempotent; loud."""
    while _PENDING_RESTORE:
        path, original = _PENDING_RESTORE.pop()
        try:
            write_src(path, original)
            print("RESTORED %s - a mutant was still on disk when the harness exited"
                  % path, flush=True)
        except Exception as ex:
            print("COULD NOT RESTORE %s: %s - the tree carries an injected fault; "
                  "run: git checkout -- %s" % (path, ex, path), flush=True)


atexit.register(_restore_pending)

# ---- a sampled run may not pose as a whole-tree record ---------------------
# CAP (the first positional argument) caps how many mutants per file are tried.
# The default is 6; the battery's gate has always been produced with 999. A run
# at the default samples a fraction of each file and then, until now, wrote the
# same baseline file with the same shape - indistinguishable from a full sweep
# except that every number was smaller. That is the record a narrowed run must
# never replace. Whether a run sampled is DERIVED from what happened (a file had
# more candidates than CAP), not read from the flag, so a future flag that
# narrows differently is caught the same way.
SAMPLED_FILES = []             # paths whose candidates exceeded CAP this run


def baseline_target(sampled_files, whole=None):
    """Where this run's record may be written: the gate only if nothing was sampled."""
    whole = whole if whole is not None else BASELINE
    if sampled_files:
        return whole.with_name(whole.stem + ".PARTIAL" + whole.suffix)
    return whole


def build():
    # The battery's own build and its stamp - never a bare javac. A javac-only
    # build once left build/classes with a stale catalogue, a guard suite failed
    # unmutated, and this sweep reported about a tree the battery never runs.
    # require_build refuses to proceed unless build/classes is exactly what
    # build.ps1 produced from exactly these sources, so that cannot recur here
    # or in any harness that calls it.
    ok, out = require_build.build_with_battery_verbose(WT)
    if ok:
        require_build.require_build(WT)
    return ok, out


# javac's own words for "this statement holds the method together, and no
# mutant of this kind exists for it". A throw that is its method's only exit; a
# throw in a constructor that runs before the blank finals are assigned; a
# statement that is the unbraced body of an if followed by a declaration. None
# of these are malformed output from this script - they are lines with no valid
# mutation, which is a fact about the code. Recording them as nocompile blamed
# the harness for a property of the tree and destroyed the distinction the
# report exists to make. Anything NOT on this list really is this script's own
# defect, and is shouted about rather than absorbed.
#
# The list is deliberately short and each entry is a definite-assignment or
# definite-return rule, never a syntax error: a syntax error is always the
# harness emitting nonsense, and must stay visible as that.
NO_VALID_MUTANT = (
    "missing return statement",
    "might not have been initialized",
    "variable declaration not allowed here",
    "unreachable statement",
)


def why_it_failed(out):
    """(verdict, detail) for a build that did not compile."""
    errs = [l.strip() for l in out.splitlines() if ": error:" in l]
    for known in NO_VALID_MUTANT:
        for line in errs:
            if known in line:
                return "unmutable", ("javac: %s - the statement is load-bearing for the "
                                     "compiler, so no mutant of this kind exists here" % known)
    if errs:
        return "nocompile", "the harness emitted something javac rejected: " + errs[0][:120]
    #NO JAVAC ERROR IS NOT A DIAGNOSIS (2026-10-14): "no diagnostic captured" was filed 9 times (8 serial, 1 shared)
    #with the build's own words thrown away - name what it said, so the cause is a fact rather than a guess
    said = [l.strip() for l in out.splitlines() if l.strip()]
    return "nocompile", ("the build failed with no javac error; it said: "
                         + (" | ".join(said[-3:])[:200] if said else "nothing at all"))


#A BUILD REFUSED AT A STRUCTURE GATE IS THE BATTERY NOTICING (2026-10-14): build.ps1 runs the battery's structure
#suites before it signs, and a mutant that breaks one is refused there - "BUILD REFUSED - <gate>", no javac error. It
#was filed "nocompile: no diagnostic captured", unmeasured: reproduced on BchMapModel 732 negated, which moves the
#marker DuplicateWorkTest finds that capability's one owner by. Which suite each gate is, build.ps1's own table says.
BUILD_GATE = re.compile(r'@\{\s*n\s*=\s*"([^"]+)"\s*;\s*c\s*=\s*"ctrmap\.tests\.(\w+)"\s*\}')
GATE_REFUSED = re.compile(r"BUILD REFUSED - (.+)")


def build_gates(text):
    """{gate name: suite} from a build.ps1's own gate table."""
    return {name.strip(): suite for name, suite in BUILD_GATE.findall(text)}


def refused_at_gate(out):
    """The BUILD REFUSED match when a structure gate refused the mutant BY FAILING A CHECK, else None. A gate that
    stopped without one - a crash under load, as DialogSeamTest did on 2026-10-14 - judged nothing; build.ps1 at
    c869a0e still heads that BUILD REFUSED over an ok line that says "refused", so the shape is asked here, not
    the header: a FAIL line after it."""
    m = GATE_REFUSED.search(out)
    if not m or not [l for l in out[m.end():].splitlines() if "FAIL" in l]:
        return None
    return m


def gate_kill(out, gates):
    """(suite, gate) when the build refused the mutant at a structure gate, else None. A gate the table does not name
    is still the battery's build refusing it, and is credited to build.ps1."""
    m = refused_at_gate(out)
    if not m:
        return None
    gate = m.group(1).strip()
    return gates.get(gate, "build.ps1"), gate


def failed_build(out, gates):
    """(verdict, detail, killer) for a mutant whose build did not succeed: killed by the gate's suite when the
    battery's build refused it at a structure gate, else what javac's words - or the build's - make it."""
    gated = gate_kill(out, gates)
    if gated:
        return "killed", gated[0] + ": the battery's build refused it at its gate - " + gated[1], gated[0]
    verdict, detail = why_it_failed(out)
    return verdict, detail, ""


def built():
    """(compiled, what the build said) for the mutant now on disk. A build that failed with no javac error and no gate
    refusal has not told this sweep anything about the mutant - a file held open, a stamp read mid-write - so it is
    asked ONCE more before any verdict; one that fails the same way twice is filed with what it said."""
    ok, out = build()
    if ok or [l for l in out.splitlines() if ": error:" in l] or refused_at_gate(out):
        return ok, out
    return build()


# ---- the harness's own guard -------------------------------------------------
# `python tools/mutate2.py --selftest` - seconds, no worktree, no JDK, no dump.
# Every case below is one of the four defects the sweep of 2026-09-03 shipped
# with, written so that reintroducing it fails here rather than in a four-hour
# run whose numbers nobody can tell are wrong.
SELFTEST_JAVA = u"""package t;

public class T {

\tpublic boolean check(int a) {
\t\tif (a < 0) {
\t\t\tthrow new IllegalStateException("negative: "
\t\t\t\t\t+ a + " is not allowed");
\t\t}
\t\tSystem.out.println("checked "
\t\t\t\t+ a);
\t\treturn true;
\t}

\tpublic void shout(String s) {
\t\tif (s == null) {
\t\t\tthrow new NullPointerException("null");
\t\t}
\t}

\tpublic T(int x) {
\t\tif (x < 0) {
\t\t\tthrow new IllegalArgumentException("bad");
\t\t}
\t}

\tpublic int size() {
\t\tthrow new UnsupportedOperationException("no");
\t}

\tpublic Runnable later() {
\t\treturn () -> {
\t\t\tthrow new IllegalStateException("in a lambda");
\t\t};
\t}

\tpublic String label() {
\t\tRunnable r = new Runnable() {
\t\t\t@Override
\t\t\tpublic void run() {
\t\t\t\tSystem.out.println("inner");
\t\t\t}
\t\t};
\t\tr.run();
\t\tthrow new IllegalStateException("after the inner class");
\t}

\tpublic void tell(java.io.PrintStream said) {
\t\tsaid.println("told");
\t}
}
"""


def _by_kills(tally):
    """Suite names out of a {name: kills} tally, commonest first."""
    return [n for n, _c in sorted(tally.items(), key=lambda kv: -kv[1])]


def _prior_killers():
    """What killed each line last run: ({path: {(line, kind): suite}}, {path: [suite...]}).

    Ordering only, never truth: a line whose file has been edited since may well have
    moved, and a wrong guess costs one wasted JVM start - the same bargain MENTIONS
    makes. The whole union still runs before anything may be called a survivor.
    """
    exact, tally = {}, {}
    try:
        with io.open(baseline_path(), encoding="utf-8") as handle:
            old_ = json.load(handle)
    except Exception:
        return exact, {}
    for path_, rec_ in old_.items():
        if not isinstance(rec_, dict):
            continue
        for e in rec_.get("killed_lines", []):
            by = e.get("by")
            if not by:
                continue
            exact.setdefault(path_, {})[(e.get("line"), e.get("kind"))] = by
            t = tally.setdefault(path_, {})
            t[by] = t.get(by, 0) + 1
    return exact, {p: _by_kills(t) for p, t in tally.items()}


#: filled in below, once, from the last run. Empty is a working state: it means
#: every order is decided by rank 3 and 4, which is what this file did before.
PRIOR_EXACT, PRIOR_FILE = {}, {}

#: what has killed something in each file DURING THIS RUN. A sweep that only learned
#: between runs would spend the whole first run after this change paying the old price.
LEARNED = {}

#: [times the first suite tried was the killer, kills]. The point of all of this,
#: measured, so a later ordering change can be judged instead of argued about.
FIRST_TRY = [0, 0]


def learn(path, suite_simple_name):
    """Remember that this suite kills things in this file, for the rest of the run."""
    t = LEARNED.setdefault(path, {})
    t[suite_simple_name] = t.get(suite_simple_name, 0) + 1


def suites_for(path, first, line=None, kind=None):
    """Every suite that may judge a mutant, likeliest killer first.

    THE ORDER IS EVIDENCE NOW, NOT A TABLE. It was: the cluster's own suites (a
    hand table, measured wrong about 48 of 86 files), then every suite whose source
    names the class, then the rest alphabetically. A kill found late costs one JVM
    start per suite ahead of it and there are 128 of them, so a table that is wrong
    half the time is a large part of this sweep's wall clock.

    Four ranks, each weaker evidence than the one before it:

      1. the suite that killed THIS line last run - as near certain as this gets;
      2. a suite that killed something else in this file - what this run has already
         learnt first, then last run, commonest first: guards come in files;
      3. the cluster table, then the suites whose source names the class - the old
         order, kept, because with no evidence at all it still beats alphabetical;
      4. everything else, so the union is still the whole battery.

    Rank 4 is what makes being wrong cheap: this returns a PERMUTATION of the judges,
    never a subset. Dropping a suite would not slow the sweep down, it would
    manufacture survivors - and the selftest holds it to exactly that.

    `first` is the branch's own list and is filtered too - a suite that cannot
    decide a mutant cannot decide it by being named early either.
    """
    ok = {c for c, _ in JUDGES}
    first = [x for x in first if x[0] in ok]
    simple = path.split("/")[-1][:-len(".java")]
    by_name = {}
    for c_, a_ in JUDGES:
        by_name.setdefault(c_.split(".")[-1], (c_, a_))

    order, seen = [], set()

    def take(entries):
        for e in entries:
            if e is not None and e[0] not in seen:
                seen.add(e[0])
                order.append(e)

    take([by_name.get(PRIOR_EXACT.get(path, {}).get((line, kind)))])
    take([by_name.get(n) for n in _by_kills(LEARNED.get(path, {}))])
    take([by_name.get(n) for n in PRIOR_FILE.get(path, ())])
    take(first)
    take([x for x in JUDGES if x[0] in MENTIONS.get(simple, ())])
    take(JUDGES)
    return order


# ---- sharing one sweep among several runs, and taking back what a run already measured ---------------------
# THE SWEEP RAN ALONE, and it was the slowest thing between a frozen tree and a release: measured 2026-10-14, 705
# verdicts in 46.4 hours - 15 an hour, one JVM at a time on a machine of 20 cores - with about 1,450 mutants left,
# about 4 days more. Several runs now share one sweep. Each has its own worktree (CTRMAP_SWEEP_WT) and claims a
# file before measuring it (--claims <folder>, the same folder for all), so no two measure the same file. Then
# one last run takes every recorded file back (--reuse) and writes the one baseline. A run that died after its
# claim left its file unrecorded, and the last run measures that file itself. A file nothing recorded is never
# taken as done.
#
# A RECORD IS TAKEN ONLY FOR THE TREE IT MEASURED. A log or results file names its frozen commit, and one of
# another commit is refused outright. A file whose recorded (line, kind) sequence is not exactly what this tree
# yields for it is measured again rather than guessed at, and said so: a record of a different file is not a
# measurement of this one.

LOG_SECTION = re.compile(r"^=== (\S+) \(merged from ")
LOG_FILE = re.compile(r"^  (\S+\.java): \d+ mutable line\(s\), trying \d+")
LOG_LINE = re.compile(r"^     L(\d+)\s+(\S+)\s+(killed|SURVIVED|hung|nocompile|unmutable|excluded)(?: (.*?))?"
                      r"(?:   <-- NOTHING ASSERTS THIS LINE)?$")
LOG_FROZEN = re.compile(r"^measuring ([0-9a-f]{7,40}) \(frozen")
LOG_END = re.compile(r"^\d+ measurement\(s\) in ")


def unit_expected(noted, picked):
    """The (line, kind) sequence a file's measurement prints, in its order: the lines nothing was emitted for, then
    each mutant tried."""
    return [[n["i"] + 1, n["kind"]] for n in noted] + [[c["i"] + 1, c["kind"]] for c in picked]


def unit_key(cid, path):
    return "%s\t%s" % (cid, path)


def read_log(text):
    """{"frozen": sha, "units": {(cid, file name, nth of that name in the cluster): [(line, kind, verdict, detail)]}}
    from a sweep's printed output - only the files whose measurement was printed to its END, which is the next file,
    the next cluster, or the closing count. The file a run was killed inside is not in it."""
    frozen, units, cid, open_unit, seen = None, {}, None, None, {}

    def close(complete):
        if open_unit is not None and complete:
            units[open_unit[0]] = open_unit[1]

    for raw in text.splitlines():
        line = raw.rstrip("\r")
        m = LOG_FROZEN.match(line)
        if m:
            frozen = m.group(1)
            continue
        m = LOG_SECTION.match(line)
        if m:
            close(True)
            open_unit, cid = None, m.group(1)
            continue
        m = LOG_FILE.match(line)
        if m and cid is not None:
            close(True)
            n = seen[(cid, m.group(1))] = seen.get((cid, m.group(1)), 0) + 1
            open_unit = ((cid, m.group(1), n), [])
            continue
        if LOG_END.match(line):
            close(True)
            open_unit = None
            continue
        m = LOG_LINE.match(line)
        if m and open_unit is not None:
            open_unit[1].append((int(m.group(1)), m.group(2), m.group(3), m.group(4) or ""))
    return {"frozen": frozen, "units": units}


#: what a results file says of its kills when each was confirmed by the same suite failing a second time; a record
#: that does not say it was written by a run that took one failing run as a kill
KILLS_CONFIRMED = "each confirmed by a second run"
#: where the suites live: a record taken before they changed may not know what they notice now
SUITES = "src/ctrmap/tests"


def measure_again(records, suites_moved, confirmed):
    """(line, kind) of every reused verdict that is not a measurement of THIS tree: a SURVIVED or hung one when the
    suites changed after it was taken - a newer suite may notice what the older ones did not - and a kill from a run
    that did not confirm its kills. Measured 2026-10-14 in the shares that did not: SetupWizardTest credited with 3
    kills of files it never runs, and OffscreenRenderTest with ItemTable 392's, which no test names. Every other
    verdict still is one.

    A NOCOMPILE IS NEVER TAKEN, AND IT IS ALONE (2026-10-26). It is this harness's failure, not a measurement, so it is
    measured again - that mutant, not its file. A record holding one was refused whole, and the nocompiles are
    deterministic: one each made the a562275 writer measure CtrmapMainframe, NPCEditForm, Ui, UndoHistory, Forms and
    PokePickers whole again, 328 minutes, as it will every sweep that reuses them."""
    return [(r["line"], r["kind"]) for r in records
            if (suites_moved and r["verdict"] in ("SURVIVED", "hung"))
            or (not confirmed and r["verdict"] == "killed")
            or r["verdict"] == "nocompile"]


def split_reuse(records, again, picked):
    """(records taken, candidates measured again): every record but those `again` names, and the candidates of
    exactly those - a file is measured again mutant by mutant, never whole. A verdict to measure again that this tree
    offers no candidate for stops the run: dropped, it would leave the baseline one mutant short and say nothing."""
    keys = set(again)
    taken = [r for r in records if (r["line"], r["kind"]) not in keys]
    redo = [c for c in picked if (c["i"] + 1, c["kind"]) in keys]
    missing = keys - set((c["i"] + 1, c["kind"]) for c in redo)
    if missing:
        raise SystemExit("a record names %s to measure again, and this tree offers no candidate for it - the record "
                         "is not of this tree's lines" % sorted(missing))
    return taken, redo


def plan_unit(reused, sharing, picked, noted):
    """(plan, records taken, candidates, unmeasurable lines) for one file, from what take_split answered:
    "skip" when a record answers it whole and runs share the sweep (the run that reuses the records takes it);
    "take" when a record answers it whole and this run writes the baseline; "measure" otherwise - with every record
    that still measures this tree taken, and only the rest measured."""
    if reused is None:
        return "measure", [], picked, noted
    _source, taken, redo = reused
    if not redo:
        return ("skip" if sharing else "take"), taken, [], []
    return "measure", taken, redo, []


class Reuse(object):
    """Every verdict this run may take instead of measuring: from printed sweep logs and from the results files the
    runs sharing a sweep wrote.

    A RECORD OF ANOTHER COMMIT IS TAKEN FILE BY FILE, for a file git says is byte-identical at both - the subject
    MutationBaselineTest holds a baseline to is each measured file's own bytes, so a file that did not move is the
    subject its record measured. A file that moved is measured again. A record naming a commit git cannot find is
    refused whole: nothing can be compared with a tree nobody has.

    THE BEST RECORD OF A FILE, NOT THE LAST ONE READ (2026-10-17). Several passes record one file, and each record read
    replaced the one before, so the folder given last won - with the folders given newest first, the OLDEST: Ips and
    ADPropRegistry held kill-confirmed records at faf456f and 315d48d, and c869a0e's unconfirmed ones were taken, every
    kill measured again. The 315d48d pass paid that whole, 1,641 verdicts over ten hours. A record taken here is the
    one that measures this tree best (rank), whatever order the folders were given in."""

    def __init__(self, sources, frozen):
        self.logs, self.records, self.taken, self.refused = [], {}, 0, []
        self.frozen, self._nth, self._same, self._suites, self._when = frozen, {}, {}, {}, {}
        self.last_measured, self.last_confirmed = None, False
        for source in sources:
            p = Path(source)
            files = sorted(p.glob("results-*.json")) if p.is_dir() else [p]
            if p.is_dir() and not files:
                raise SystemExit("--reuse %s: a folder with no results-*.json in it - nothing a run recorded is there,"
                                 " so nothing can be taken from it" % source)
            for f in files:
                if not f.is_file():
                    raise SystemExit("--reuse %s: no such file - a record that cannot be read is not one with "
                                     "nothing in it" % f)
                text = f.read_text(encoding="utf-8", errors="replace")
                if f.suffix == ".json":
                    data = json.loads(text)
                    measured = self.commit(data.get("frozen"), f)
                    for key, unit in data.get("units", {}).items():
                        held = self.records.get(key)
                        if held is None or self.rank(measured, unit) > self.rank(held[2], held[1]):
                            self.records[key] = (f.name, unit, measured)
                else:
                    log = read_log(text)
                    self.logs.append((f.name, log["units"], self.commit(log["frozen"], f)))

    def rank(self, measured, unit):
        """How well a record answers this tree, the better one comparing greater: one measured at this very commit
        first, then one whose kills a second run confirmed, then the newer measurement by its commit's time."""
        return measured == self.frozen, unit.get("kills") == KILLS_CONFIRMED, self.when(measured)

    def when(self, sha):
        """The commit time of a measured commit, asked of git; one git cannot date stops the run - an undatable
        record is not an old one."""
        if sha not in self._when:
            asked = git("show", "-s", "--format=%ct", sha)
            stamp = asked.stdout.strip()
            if asked.returncode != 0 or not stamp.isdigit():
                raise SystemExit("git could not date %s (exit %s) - which of two records is newer cannot be told, and "
                                 "an undated record is not an old one" % (sha[:7], asked.returncode))
            self._when[sha] = int(stamp)
        return self._when[sha]

    def commit(self, said, source):
        """The whole commit a record says it measured, or a refusal: a record naming no commit, or one git cannot
        find, cannot be compared with this tree at all."""
        if said and self.frozen.startswith(said):
            return self.frozen
        found = git("rev-parse", "--verify", "--quiet", "%s^{commit}" % said).stdout.strip() if said else ""
        if not found:
            raise SystemExit("--reuse %s measured %s, which git does not find - a record of a tree nobody can compare "
                             "with this one is not a measurement of it" % (source, said or "no commit at all"))
        return found

    def unchanged(self, measured, path):
        """Is this file byte-identical in the commit a record measured and the one this run measures? Asked of git,
        and a question git could not answer stops the run rather than reading as either."""
        if measured == self.frozen:
            return True
        key = (measured, path)
        if key not in self._same:
            asked = git("diff", "--quiet", measured, self.frozen, "--", path)
            if asked.returncode not in (0, 1):
                raise SystemExit("git could not say whether %s changed between %s and %s (exit %s) - a file nobody "
                                 "could compare is not unchanged" % (path, measured[:7], self.frozen[:7],
                                                                    asked.returncode))
            self._same[key] = asked.returncode == 0
        return self._same[key]

    def take(self, cid, path, expected, whole):
        """The result records for this file from a run that measured it, or None: measure it here."""
        name = path.split("/")[-1]
        nth = self._nth[(cid, name)] = self._nth.get((cid, name), 0) + 1
        record = self.records.get(unit_key(cid, path))
        if record is not None:
            source, unit, measured = record
            if not self.unchanged(measured, path):
                self.refused.append("%s %s: %s measured it at %s, and it has changed since"
                                    % (cid, path, source, measured[:7]))
            elif unit.get("expected") == expected:
                self.taken += 1
                self.last_measured, self.last_confirmed = measured, unit.get("kills") == KILLS_CONFIRMED
                return source, unit["results"]
            else:
                self.refused.append("%s %s: %s recorded %s, this tree has %s"
                                    % (cid, path, source, unit.get("expected"), expected))
        for source, units, measured in self.logs:
            lines = units.get((cid, name, nth))
            if lines is None:
                continue
            if not self.unchanged(measured, path):
                self.refused.append("%s %s: %s measured it at %s, and it has changed since"
                                    % (cid, path, source, measured[:7]))
                continue
            if [[l, k] for l, k, _v, _d in lines] != expected:
                self.refused.append("%s %s: %s printed %s, this tree has %s"
                                    % (cid, path, source, [[l, k] for l, k, _v, _d in lines], expected))
                continue
            self.taken += 1
            out = []
            for l, k, v, d in lines:
                d = d.lstrip()        # the verdict's column padding, never part of what it said
                r = dict(cluster=cid, file=name, path=path, line=l, kind=k, verdict=v, detail=d,
                         code=whole[l - 1].strip()[:160])
                if v == "killed":
                    r["by"] = d.split(":")[0]
                elif v in ("SURVIVED", "hung", "nocompile"):
                    r["by"] = ""
                out.append(r)
            self.last_measured, self.last_confirmed = measured, False    # a printed log does not say
            return source, out
        return None

    def suites_moved(self, measured):
        """Did the suites change between the commit a record measured and the one this run measures? Asked of git,
        and a question git could not answer stops the run rather than reading as either."""
        if measured == self.frozen:
            return False
        if measured not in self._suites:
            asked = git("diff", "--quiet", measured, self.frozen, "--", SUITES)
            if asked.returncode not in (0, 1):
                raise SystemExit("git could not say whether the suites changed between %s and %s (exit %s) - suites "
                                 "nobody could compare are not unchanged" % (measured[:7], self.frozen[:7],
                                                                             asked.returncode))
            self._suites[measured] = asked.returncode == 1
        return self._suites[measured]

    def take_split(self, cid, path, expected, whole, picked):
        """(source, records taken, candidates to measure again) for this file, or None: measure it all here.

        A VERDICT IS TAKEN ONLY WHERE IT STILL MEASURES THIS TREE (2026-10-14). A byte-identical production file shows
        nothing of the suites, and the guards written since a record was taken are in them: a SURVIVED or hung
        verdict from before they changed says nothing about what they notice now, and a kill by an untrusted suite
        never said anything (measure_again). Those mutants are measured again here; every other verdict is taken."""
        got = self.take(cid, path, expected, whole)
        if got is None:
            return None
        source, records = got
        taken, redo = split_reuse(records, measure_again(records, self.suites_moved(self.last_measured),
                                                         self.last_confirmed), picked)
        return source, taken, redo


def claims_folder(folder, frozen):
    """The folder runs sharing this sweep claim files in, made for this frozen tree or refused: a claim taken for
    another tree would leave a file of this one measured by nobody."""
    os.makedirs(folder, exist_ok=True)
    stamp = os.path.join(folder, "frozen")
    # written whole under another name and LINKED into place: a link onto a name that exists is refused, so exactly
    # one run's stamp lands, and no run can read a stamp another is still writing
    mine = "%s.%d" % (stamp, os.getpid())
    with io.open(mine, "w", encoding="utf-8") as handle:
        handle.write(frozen)
    try:
        os.link(mine, stamp)
    except FileExistsError:
        pass
    finally:
        os.remove(mine)
    with io.open(stamp, encoding="utf-8") as handle:
        said = handle.read().strip()
    if said != frozen:
        raise SystemExit("--claims %s was made for %s, and this run measures %s - its claims would leave files of "
                         "this tree measured by nobody" % (folder, said, frozen))
    return folder


def _claim_left_by_the_gone(target):
    """Where the claim at `target` is moved aside when the run that took it is gone, or None while it may be running.
    A claim that cannot be read, or names no pid, is held: unread is not absent. The name is the holder's own, so two
    runs taking one dead claim over move it to ONE name, and the operating system lets exactly one of them."""
    try:
        with io.open(target, encoding="utf-8") as handle:
            held = json.loads(handle.read())
    except (OSError, ValueError):
        return None
    if not isinstance(held, dict) or not held.get("pid"):
        return None
    #A RUN THAT FINISHED IS GONE TOO (2026-10-25): two shares ended, and the third, four hours into one file, then
    #took over their claims and measured their recorded files again. A file its run recorded is measured, not
    #abandoned: its claim is kept.
    if held.get("recorded"):
        return None
    if bundle_shell.alive(held.get("pid"), held.get("started_epoch")):
        return None
    return "%s.gone-%s-%s" % (target, held.get("pid"), held.get("started_epoch"))


def claim_file(folder, cid, path):
    """The one name a file's claim has in `folder`."""
    return os.path.join(folder, "claim-" + hashlib.sha1(unit_key(cid, path).encode("utf-8")).hexdigest()[:20])


def mark_recorded(folder, cid, path):
    """Say on this run's claim of the file that the file is recorded - swapped in whole, so a reader sees the claim
    before or after, never half - so no run that outlives this one takes the claim over as a gone run's."""
    target = claim_file(folder, cid, path)
    with io.open(target, encoding="utf-8") as handle:
        held = json.loads(handle.read())
    held["recorded"] = True
    part = "%s.%d.part" % (target, os.getpid())
    with io.open(part, "w", encoding="utf-8") as handle:
        handle.write(json.dumps(held))
    os.replace(part, target)


def claim(folder, cid, path):
    """True: this run measures the file. False: another run sharing the sweep already does. One file made by the
    operating system or refused by it as already there, so two runs can never both get True. Any other failure stops
    the run: a claim that could not be ASKED is not one another run holds.

    A CLAIM OF A RUN THAT IS GONE IS NOBODY'S. Measured 2026-10-17: three shares that stopped at their first mutant
    left claims on PokePickers, Updater and BuildingHarvester, and seven runs sharing the pass skipped all three as
    measured by another - the claim carried its holder's pid and start time and nothing read them. A claim whose
    holder `bundle_shell.alive` calls gone is moved aside under a name of that holder, and the file is asked for
    again; a holder it cannot tell about is ALIVE, and keeps its claim."""
    target = claim_file(folder, cid, path)
    try:
        fd = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        gone = _claim_left_by_the_gone(target)
        if gone is None:
            return False
        try:
            # LINKED, never renamed: a link onto a name that exists is refused everywhere, so of two runs that both
            # read the dead claim, the second links the FIRST one's new claim onto the same name and is refused
            os.link(target, gone)
            os.remove(target)
        except FileExistsError:
            return False                       # another run took this dead claim over first
        except FileNotFoundError:
            pass                               # another run moved it aside a moment ago: ask as it does
        try:
            fd = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        except FileExistsError:
            return False
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(json.dumps({"cluster": cid, "path": path, "worktree": str(WT), "pid": os.getpid(),
                                 "started_epoch": bundle_shell.process_started_at(os.getpid())}))
    return True


def record_unit(target, frozen, recorded, cid, path, expected, unit_results):
    """Add one measured file to this run's results file, rewritten whole and swapped into place, so a run killed at
    any moment leaves every file it finished recorded and none half-recorded."""
    recorded[unit_key(cid, path)] = {"expected": expected, "results": unit_results, "kills": KILLS_CONFIRMED}
    part = target + ".part"
    with io.open(part, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps({"frozen": frozen, "worktree": str(WT), "units": recorded}, indent=1))
    os.replace(part, target)


def record_claimed(target, frozen, recorded, folder, cid, path, expected, unit_results):
    """Record one file a run measured, then say so on its claim: ONE call, so no run records a file and leaves the
    claim looking like an abandoned one for the next run to take over."""
    record_unit(target, frozen, recorded, cid, path, expected, unit_results)
    mark_recorded(folder, cid, path)


#: kills a suite did not repeat: (file, suite) for every first failure the same suite passed on the second run
UNCONFIRMED = []


def judge(path, ordered):
    """(verdict, detail, killer) for the mutant now built: each suite in order until one notices it.

    A KILL IS CONFIRMED BY THE SAME SUITE A SECOND TIME (2026-10-14). One failing run was taken as a kill, so a
    suite that fails for its own reasons turned a line nothing asserts into a killed one - measured: SetupWizardTest
    failed 1 run in 100 unmutated on a loaded machine, and was credited with killing Ips 48 and MapResizer 336, which
    it never runs, and AbstractGamefreakContainer 287, which no input reaches. A suite that passes the same mutant on
    its second run did not notice it: it is recorded in UNCONFIRMED and the next suite is asked. A second run that
    never finishes confirms nothing either, and a hang is never a kill.
    """
    verdict, detail, by = "SURVIVED", "", ""
    for cls, a in ordered:
        name = cls.split(".")[-1]
        r = run([JAVA, "-Xmx4g", "-cp", CP, cls] + a)
        if r.returncode == HUNG_RC:
            # A hang is still never a kill - but it must not stop the OTHER suites from trying. Measured:
            # GARC.java:207 and :225 came back "hung" once ForkGuardsTest ran first for that file, though the suite
            # behind it kills both. Remember the hang, keep going, and report it only if nothing else decides.
            if verdict == "SURVIVED":
                verdict, detail = "hung", name + ": never finished"
            continue
        if not r.flooded and r.returncode == 0:
            continue
        again = run([JAVA, "-Xmx4g", "-cp", CP, cls] + a)
        if again.returncode == HUNG_RC or (not again.flooded and again.returncode == 0):
            UNCONFIRMED.append((path, name))
            if again.returncode == HUNG_RC and verdict == "SURVIVED":
                verdict, detail = "hung", name + ": failed once, then never finished"
            continue
        by = name
        if r.flooded:
            # checked BEFORE the exit code, because killing a flood leaves a non-zero code that would read as an
            # ordinary failure and hide what actually happened
            detail = name + ": OUTPUT FLOOD, %d MB and still going" % (r.out_bytes // (1024 * 1024))
        else:
            tail = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
            detail = name + ": " + (tail[-1][:100] if tail else "")
            FIRST_TRY[1] += 1
            if (cls, a) == ordered[0]:
                FIRST_TRY[0] += 1
        learn(path, by)
        return "killed", detail, by
    return verdict, detail, by


def judges_of(suites):
    """The suites that can judge a mutant, each asked with the probe edit in place. One that fails is asked AGAIN, and
    only one failing twice is taken for a suite that asserts the repository's bytes rather than the program.

    FLAKY IS NOT SOURCE-SENSITIVE (2026-10-16). Asked once, WorkspaceRepointTest failed the probe in 3 of the
    assembly's 8 shares and passed it in the other 5, and in 8 of 8 runs four at a time - and in those 3 shares every
    line only it kills was filed SURVIVED (SetupWizard 793 and 802 among them). A kill still needs the same suite to
    fail twice (judge), so keeping a flaky suite as a judge credits it with nothing it did not do twice."""
    out = []
    for cls, a in suites:
        r = run([JAVA, "-Xmx4g", "-cp", CP, cls] + a)
        if r.returncode != 0:
            r = run([JAVA, "-Xmx4g", "-cp", CP, cls] + a)
            if r.returncode == 0:
                print("  flaky on the probe: %s failed it once and passed it again - kept as a judge"
                      % cls.split(".")[-1], flush=True)
        if r.returncode == 0:
            out.append((cls, a))
        else:
            print("  not a judge: %s fails on a source edit that changes no behaviour, twice"
                  % cls.split(".")[-1], flush=True)
    return out


def selftest():
    import shutil
    import tempfile
    global WT, run, build
    fails = [0]

    def check(ok, what):
        print(("  ok: " if ok else "  FAIL: ") + what)
        if not ok:
            fails[0] += 1

    tmp = Path(tempfile.mkdtemp(prefix="mutate2-selftest"))
    saved_wt = WT
    try:
        (tmp / "src/t").mkdir(parents=True)
        with io.open(tmp / "src/t/T.java", "w", encoding="utf-8", newline="") as handle:
            handle.write(SELFTEST_JAVA)
        WT = tmp
        src = read_src("src/t/T.java").splitlines()
        lines = [n for n, t in enumerate(src, 1)
                 if THROW.match(t) or REPORT.match(t) or IF.match(t)
                 or RET_TRUE.match(t) or RET_FALSE.match(t)]
        cands, noted = mutants_for("src/t/T.java", lines)
        got = dict(((c["kind"], c["i"] + 1), c) for c in cands)

        # DEFECT 4a: a statement is not a line. Deleting only the first line of
        # a multi-line throw or report leaves the continuation dangling, which
        # this harness used to file as a fact about the code.
        c = got.get(("drop-throw", 7))
        check(c is not None and c["j"] + 1 == 8,
              "a throw spanning two lines is deleted whole (7..%s)" % (c["j"] + 1 if c else "-"))
        c = got.get(("drop-report", 10))
        check(c is not None and c["j"] + 1 == 11,
              "a report spanning two lines is deleted whole (10..%s)" % (c["j"] + 1 if c else "-"))
        check(("drop-report", 49) in got,
              "a report printed to a stream the method was handed is a drop-report mutant, as one to System.out is")

        # DEFECT 4b: the missing operator, and it must pick the return the
        # ENCLOSING method declares - not the nearest signature above the line.
        for line, want, why in ((7, "return false;", "a boolean method"),
                                (17, "return;", "a void method"),
                                (23, "return;", "a constructor"),
                                (28, "return 0;", "an int method")):
            c = got.get(("swallow-throw", line))
            check(c is not None and c["repl"].strip() == want,
                  "swallow-throw in %s becomes %s (got %r)"
                  % (why, want, c["repl"].strip() if c else None))
        # the nearest signature above a line is very often an anonymous class's
        # method that already closed; taking it would put `return;` in a method
        # that owes the caller a String
        c = got.get(("swallow-throw", 45))
        check(c is not None and c["repl"].strip() == "return null;",
              "the return type comes from the ENCLOSING method, not the inner class's that "
              "already closed above it (got %r)" % (c["repl"].strip() if c else None))
        check(("swallow-throw", 33) not in got,
              "a throw inside a block lambda is NOT given the outer method's return type")
        check(any(n["kind"] == "swallow-throw" and n["i"] + 1 == 33
                  and n["verdict"] == "unmutable" for n in noted),
              "...it is recorded unmutable instead, which is not the same fact as nocompile")

        # a deleted statement leaves an empty block, so it cannot swallow the
        # next statement into an unbraced if
        check(all(c["repl"].strip() == "{ }" for c in cands
                  if c["kind"] in ("drop-throw", "drop-report")),
              "a deletion leaves an empty block rather than nothing")

        # DEFECT 6: relocate() must ACCOUNT for every fix line it cannot place,
        # not just the ambiguous ones. On master at 270d58f the silent bucket -
        # a line a later merge edited, so its exact text is gone - held 133
        # occurrences against the 74 the report named. The whole point of this
        # harness is that a measurement may not quietly get smaller, and this
        # was it doing that to itself.
        del SKIPPED_AMBIGUOUS[:]
        del SKIPPED_GONE[:]
        placed = relocate("src/t/T.java", [
            u"\t\treturn true;",                              # unique -> measured
            u"\t\t\tthrow new IllegalStateException(\"gone\");",   # eligible, absent
            u"\t\t}",                                          # not eligible, absent
        ])
        check(len(placed) == 1, "a fix line whose text is unique today is placed (got %r)" % placed)
        check(len(SKIPPED_GONE) == 1 and "IllegalStateException" in SKIPPED_GONE[0][1],
              "a mutable fix line whose text is no longer in the tree is RECORDED, not dropped "
              "(got %r)" % (SKIPPED_GONE,))
        check(not SKIPPED_AMBIGUOUS,
              "...and it is not filed as 'not uniquely relocatable', which is a different fact")
        dup = u"\t\tif (a < 0) {"
        with io.open(tmp / "src/t/T.java", "a", encoding="utf-8", newline="") as handle:
            handle.write(
                u"\nclass U {\n\tvoid f(int a) {\n" + dup + u"\n\t\t}\n\t}\n}\n")
        del SKIPPED_GONE[:]
        relocate("src/t/T.java", [dup])
        check(len(SKIPPED_AMBIGUOUS) == 1 and not SKIPPED_GONE,
              "a fix line whose text now appears twice is still the AMBIGUOUS bucket")
        del SKIPPED_AMBIGUOUS[:]
        del SKIPPED_GONE[:]

        # DEFECT 5: an exclusion that has drifted onto another line is refused.
        # The excluded (path, line) pairs come from EXCLUSIONS rather than being
        # written here, because this check hardcoded 84 and 87 and then FAILED
        # the moment the reporting-seam work moved those lines to 119 and 122 -
        # the exact drift it exists to catch, in the test itself.
        _ex_path, _ex_line = sorted(EXCLUSIONS)[0]
        (tmp / _ex_path).parent.mkdir(parents=True, exist_ok=True)
        with io.open(tmp / _ex_path, "w", encoding="utf-8", newline="") as handle:
            handle.write(
                u"\n".join(["// not the real file"] * (_ex_line + 8)))
        try:
            mutants_for(_ex_path, [_ex_line])
            check(False, "a stale exclusion is refused rather than applied to whatever line it lands on")
        except SystemExit as e:
            check("STALE EXCLUSION" in str(e),
                  "a stale exclusion is refused rather than applied to whatever line it lands on")

        # DEFECT 6: a mutant left on disk by an escaping exception is put back.
        # The restore was a plain statement after the suite runs; a MemoryError
        # from a chatty child once escaped above it, and nothing after that
        # point - the restore, the final git reset - ran.
        victim = tmp / "src/t/Victim.java"
        victim.parent.mkdir(parents=True, exist_ok=True)
        with io.open(victim, "w", encoding="utf-8", newline="") as handle:
            handle.write(u"GOOD\n")
        #WT FIRST: the lock is taken beside the mutant, in the tree being mutated (F179)
        _saved_wt = globals().get("WT")
        globals()["WT"] = tmp
        _PENDING_RESTORE.append((str(victim.relative_to(tmp)).replace("\\", "/"), u"GOOD\n"))
        with io.open(victim, "w", encoding="utf-8", newline="") as handle:
            handle.write(u"MUTANT\n")
        try:
            check(os.path.isfile(str(tmp / ".mutation-in-flight")),
                  "a mutant on disk is named by a lock beside it")
            _restore_pending()
        finally:
            globals()["WT"] = _saved_wt
        with io.open(victim, encoding="utf-8") as handle:
            check(handle.read() == u"GOOD\n",
                  "a mutant still on disk at exit is restored to the original bytes")
        check(not _PENDING_RESTORE, "...and nothing remains owed afterwards")

        # DEFECT 7: a sampled run may not overwrite the whole-tree record.
        whole = tmp / "mutation_baseline.json"
        check(baseline_target([], whole) == whole,
              "a run that sampled nothing writes the gate itself")
        part = baseline_target(["src/x/A.java"], whole)
        check(part != whole and ".PARTIAL" in part.name,
              "a run that sampled even one file is diverted away from the gate (%s)" % part.name)
    finally:
        WT = saved_wt
        shutil.rmtree(str(tmp), ignore_errors=True)

    # DEFECT 1: one line measured on three branches is ONE line, and a branch
    # that killed it outvotes two that did not - loudly.
    def rec(cluster, line, verdict):
        return dict(cluster=cluster, file="F.java", path="src/F.java", line=line,
                    kind="negate-if", verdict=verdict, detail=verdict, code="if (x) {")
    lines_, tally, dis = aggregate([rec("c1", 10, "SURVIVED"), rec("c2", 10, "killed"),
                                    rec("c3", 10, "SURVIVED"), rec("c1", 20, "SURVIVED"),
                                    rec("m3", 20, "SURVIVED")])
    check(len(lines_) == 2, "5 measurements of 2 lines aggregate to 2 (got %d)" % len(lines_))
    check(tally.get("killed") == 1 and tally.get("SURVIVED") == 1,
          "killed on any branch counts as killed once, not survived twice (%s)" % tally)
    check(len(dis) == 1 and dis[0]["line"] == 10,
          "and the branches disagreeing about it is reported, not resolved away silently")

    # DEFECT 2: the ratchet compares LINES against LINES, so a line measured for
    # the first time is new coverage and only a line that WAS killed is a break.
    live = build_live([rec("c1", 10, "SURVIVED"), rec("c1", 40, "SURVIVED"),
                       rec("c1", 30, "killed")])
    live["src/F.java"]["sha256"] = "abc"
    old = {"src/F.java": {"survivors": 1, "killed": 1, "sha256": "abc",
                          "lines": [{"line": 30, "kind": "negate-if", "code": "if (y) {"}],
                          "killed_lines": [{"line": 10, "kind": "negate-if"}]}}
    regressed, fresh, fixed, lost, had = compare_to_baseline(live, old)
    check(len(regressed) == 1 and ":10" in regressed[0],
          "a line the baseline recorded KILLED that now survives breaks the ratchet, by name")
    check(len(fresh) == 1 and ":40" in fresh[0],
          "a line never measured before is NEW COVERAGE, not a regression")
    check(len(fixed) == 1 and ":30" in fixed[0] and "is now asserted" in fixed[0],
          "a survivor a suite now kills is reported fixed")
    # ...and a survivor that merely left the list is NOT credited as a kill
    gone = build_live([rec("c1", 40, "SURVIVED")])
    gone["src/F.java"]["sha256"] = "abc"
    gone["src/F.java"]["excluded"] = [{"line": 30, "reason": "x" * 60}]
    _, _, fixed2, _, _ = compare_to_baseline(gone, old)
    check(len(fixed2) == 1 and "EXCLUDED" in fixed2[0] and "NOT asserted" in fixed2[0],
          "a survivor that was EXCLUDED by hand is not reported as one the battery now asserts")
    check(had, "the baseline is recognised as carrying line-level kill records")
    moved = {"src/F.java": dict(old["src/F.java"], sha256="different")}
    r2, f2, _, _, _ = compare_to_baseline(live, moved)
    check(not r2 and len(f2) == 2,
          "and when the file has changed since the baseline, its line numbers are not compared")

    # WT IS THE CWD run() HANDS ITS CHILD, and these runs need no worktree. A machine that never
    # ran a sweep has none, and the selftest died here with FileNotFoundError - red in test.ps1
    # on every such machine, while its own usage line says "no worktree" (found running the
    # battery's Python on a fresh clone, F184). A folder of its own, until the verdict.
    runs_dir = Path(tempfile.mkdtemp(prefix="mutate2-selftest-runs"))
    saved_wt_runs, WT = WT, runs_dir

    # DEFECT 3: a flood, a hang and an ordinary failure are three things.
    py = sys.executable
    r = run([py, "-c", "import sys; sys.stdout.write('the end\\n'); sys.exit(3)"], timeout=120)
    check(r.returncode == 3 and not getattr(r, "flooded", True)
          and r.stdout.strip().endswith("the end"),
          "an ordinary non-zero exit keeps its exit code and its last line")
    t = time.time()
    r = run([py, "-c", "import time; time.sleep(120)"], timeout=3)
    check(r.returncode == HUNG_RC and not getattr(r, "flooded", True) and time.time() - t < 40,
          "a run that never finishes is HUNG - never a kill, and never a flood")
    t = time.time()
    # getattr, not attribute access: a run() that has lost the flood machinery
    # must FAIL this check, not raise out of the middle of the guard. A guard
    # that crashes instead of reporting is one nobody can read the result of.
    r = run([py, "-u", "-c", "import sys\nl='x'*200+'\\n'\nwhile True: sys.stdout.write(l)"],
            timeout=120)
    check(getattr(r, "flooded", False) and r.returncode != HUNG_RC,
          "a child that never stops printing is a FLOOD, told apart from a hang")
    check(len(r.stdout) <= TAIL_BYTES + 8 and getattr(r, "out_bytes", 0) > FLOOD_BYTES,
          "only a bounded tail is kept, so the reader cannot run the harness out of memory "
          "(%d bytes kept of %d)" % (len(r.stdout), getattr(r, "out_bytes", 0)))
    check(time.time() - t < 100, "and it is cut off promptly rather than left to the timeout")

    # ...and the tail must never reach output that is PARSED rather than sampled.
    # The first cut of the flood fix bounded every run at 256 KB, and c1's merge
    # diff is 376 KB: eight files left the sweep silently before a mutant ran.
    big = 400000
    r = run([py, "-u", "-c", "import sys; sys.stdout.write('D'*%d)" % big], timeout=120, keep=None)
    check(len(r.stdout) == big,
          "output a caller parses in full is kept in full, not sampled (%d of %d)"
          % (len(r.stdout), big))
    r = run([py, "-u", "-c", "import sys; sys.stdout.write('D'*%d)" % big], timeout=120)
    check(len(r.stdout) == TAIL_BYTES, "while a suite's output is still bounded to its tail")
    try:
        whole_diff_or_refuse("@@ -1,0 +1,2 @@\n+something", "aaaaaaa", "bbbbbbb")
        check(False, "a diff that does not start with 'diff --git' is refused, loudly")
    except SystemExit as e:
        check("TRUNCATED DIFF" in str(e),
              "a diff that does not start with 'diff --git' is refused, loudly")
    check(whole_diff_or_refuse("diff --git a/x b/x\n@@\n+y") .startswith("diff --git"),
          "...and a whole one passes through untouched")
    # and the rule has to hold at the call site, not just be available there:
    # a git() that forgot keep=None would pass every check above
    real_run, seen = run, {}

    def spy(cmd, timeout=900, keep=TAIL_BYTES):
        seen["keep"] = keep
        return Ran(0, "", "", False, 0)

    run = spy
    try:
        git("rev-parse", "HEAD")
    finally:
        run = real_run
    check(seen.get("keep", TAIL_BYTES) is None,
          "every git call asks for the whole output - git output is parsed, never sampled")

    # DEFECT 8: the sweep KNEW which suite killed each mutant and threw it away.
    # The next run then guessed the order to try suites in out of a hand table that
    # was measured wrong about 48 of 86 files, so a kill the fortieth suite finds
    # cost thirty-nine JVM starts, every run, forever. The order is evidence now.
    # These hold it to being an ORDER and never a filter: a suite left out of the
    # list does not cost time, it manufactures a survivor - and a survivor costs
    # somebody a day writing a guard that already exists.
    import tempfile

    def J(n):
        return ("ctrmap.tests." + n, [])

    #JUDGES and MENTIONS are built from the worktree and the battery, far below the
    #--selftest exit: this runs with no JDK and no dump, so they do not exist yet.
    #Whatever is missing is created for the check and removed again afterwards.
    names = ("JUDGES", "MENTIONS", "PRIOR_EXACT", "PRIOR_FILE", "LEARNED", "baseline_path")
    kept = {k: globals()[k] for k in names if k in globals()}
    added = [k for k in names if k not in globals()]
    fake = None
    try:
        globals()["JUDGES"] = [J("AaaTest"), J("BbbTest"), J("CccTest"), J("DddTest")]
        globals()["MENTIONS"] = {"F": ["ctrmap.tests.CccTest"]}
        globals()["PRIOR_EXACT"] = {"src/F.java": {(10, "negate-if"): "DddTest"}}
        globals()["PRIOR_FILE"] = {"src/F.java": ["BbbTest"]}
        globals()["LEARNED"] = {}
        table = [J("AaaTest")]                      # what the hand table says

        o = suites_for("src/F.java", table, 10, "negate-if")
        check(o[0] == J("DddTest"),
              "the suite that killed THIS line last run is tried first (%s)" % o[0][0])
        check(len(o) == 4 and len({c for c, _ in o}) == 4,
              "and every judge is still in the list exactly once - the ORDER may be wrong, "
              "the UNION may not, because a suite left out does not cost time, it "
              "manufactures a survivor")

        o = suites_for("src/F.java", table, 99, "negate-if")
        check(o[0] == J("BbbTest"),
              "a line with no record of its own falls back to what killed the rest of "
              "its file (%s)" % o[0][0])

        learn("src/F.java", "CccTest")
        learn("src/F.java", "CccTest")
        o = suites_for("src/F.java", table, 99, "negate-if")
        check(o[0] == J("CccTest"),
              "and what THIS run has already watched kill something in the file outranks "
              "last run, so the first sweep after a change does not pay the old price the "
              "whole way through (%s)" % o[0][0])

        globals()["PRIOR_EXACT"], globals()["PRIOR_FILE"], globals()["LEARNED"] = {}, {}, {}
        o = [c.split(".")[-1] for c, _ in suites_for("src/F.java", table, 99, "negate-if")]
        check(o == ["AaaTest", "CccTest", "BbbTest", "DddTest"],
              "with no evidence at all the old order is kept - the cluster table, then the "
              "suites whose source names the class, then the rest - so this is never worse "
              "than what it replaced (%s)" % o)
        o = suites_for("src/F.java", [J("ZzzTest")], 99, "negate-if")
        check(all(c != "ctrmap.tests.ZzzTest" for c, _ in o),
              "a suite that cannot decide a mutant cannot decide it by being named early "
              "either")

        # ...and the record it all reads is written by the run before it.
        live3 = build_live([dict(rec("c1", 10, "killed"), by="AaaTest")])
        check(live3["src/F.java"]["killed_lines"][0].get("by") == "AaaTest",
              "the baseline records WHICH suite killed each line, so the next run starts "
              "from the answer instead of guessing it again")
        fake = Path(tempfile.mkdtemp(prefix="mutate2-prior")) / "b.json"
        with io.open(str(fake), "w", encoding="utf-8") as handle:
            handle.write(json.dumps(
                {"src/F.java": {"killed_lines": [
                    {"line": 10, "kind": "negate-if", "by": "AaaTest"},
                    {"line": 20, "kind": "negate-if", "by": "AaaTest"},
                    {"line": 30, "kind": "void-call", "by": "BbbTest"},
                    {"line": 40, "kind": "negate-if"}],
                    "sha256": "abc"},
                 "_meta": "not a file record at all"}))
        globals()["baseline_path"] = lambda: fake
        ex, per = _prior_killers()
        check(ex.get("src/F.java", {}).get((10, "negate-if")) == "AaaTest",
              "and reads it back line by line")
        check(per.get("src/F.java") == ["AaaTest", "BbbTest"],
              "commonest killer of the file first (%s)" % per.get("src/F.java"))
        check((40, "negate-if") not in ex.get("src/F.java", {}),
              "a baseline written before this existed names no killer and is simply "
              "evidence nobody has - not a crash, and not a wrong guess either")
    finally:
        globals().update(kept)
        for k in added:
            globals().pop(k, None)
        if fake is not None:
            shutil.rmtree(str(fake.parent), ignore_errors=True)

    WT = saved_wt_runs
    shutil.rmtree(str(runs_dir), ignore_errors=True)

    #A ROUND IS READ FROM GIT OR REFUSED (F191): a repository's first commit resolves, a made-up rev does not.
    #ASKED OF A REPOSITORY BUILT HERE: the selftest promises no worktree, and test_mutation_selftest runs it
    #from a copy of tools/ that is no git repository at all - git started in the missing worktree was
    #NotADirectoryError, not a verdict (2026-10-10). Whether ROUNDS' own revs resolve is asked by the sweep,
    #which resolves them right after freezing and refuses one it cannot, before a single mutant runs.
    rounds_dir = Path(tempfile.mkdtemp(prefix="mutate2-rounds-"))
    saved_wt_rounds, WT = WT, rounds_dir
    try:
        git("init", "-q")
        for n in ("first", "second"):
            git("-c", "user.name=selftest", "-c", "user.email=selftest@example.invalid",
                "commit", "--allow-empty", "-q", "-m", n)
        root = git("rev-list", "--max-parents=0", "HEAD").stdout.split()
        try:
            pair = resolve_round("probe", root[-1], "HEAD") if root else None
        except SystemExit:
            pair = None
        check(pair is not None and len(pair[0]) == 40 and len(pair[1]) == 40 and pair[0] != pair[1],
              "control: a round from a repository's first commit to HEAD resolves to two commits")
        try:
            resolve_round("probe", "no-such-rev-ctrmap", "HEAD")
            refused = ""
        except SystemExit as stop:
            refused = str(stop)
        check("no-such-rev-ctrmap" in refused and "nothing in it" in refused,
              "a round git cannot resolve is refused, naming the rev - never scored as one that added nothing")
    finally:
        WT = saved_wt_rounds
        shutil.rmtree(str(rounds_dir), ignore_errors=True)

    #SHARING ONE SWEEP (2026-10-14): a file is claimed by exactly one run, a claims folder of another tree is refused,
    #and a record is taken only for the file and the tree it measured - never a log's unfinished last file.
    check(bare_words(["999", "--reuse", "a.out", "--claims", "c", "--anyway", "because"]) == ["999"],
          "a flag's value is never read as the mutant cap: %s"
          % bare_words(["999", "--reuse", "a.out", "--claims", "c", "--anyway", "because"]))
    #ASKED OF A REPOSITORY BUILT HERE, as the rounds above are: a record of another commit is taken file by file,
    #so git is asked - and the selftest promises no worktree
    share = Path(tempfile.mkdtemp(prefix="mutate2-share-"))
    saved_wt_share, WT = WT, share / "repo"
    try:
        (WT / "src/x").mkdir(parents=True)
        (WT / "src/x/A.java").write_text("a\n", encoding="utf-8")
        (WT / "src/x/B.java").write_text("b\n", encoding="utf-8")
        ident = ("-c", "user.name=selftest", "-c", "user.email=selftest@example.invalid")
        git("init", "-q")
        git("add", "-A")
        git(*(ident + ("commit", "-q", "-m", "first")))
        first = git("rev-parse", "HEAD").stdout.strip()
        (WT / "src/x/B.java").write_text("b, changed\n", encoding="utf-8")
        git(*(ident + ("commit", "-q", "-a", "-m", "second")))
        sha = git("rev-parse", "HEAD").stdout.strip()
        folder = str(share / "claims")
        claims_folder(folder, sha)
        claims_folder(folder, sha)
        firsts = [claim(folder, "audit", "src/a/A.java"), claim(folder, "audit", "src/a/A.java"),
                  claim(folder, "audit", "src/a/B.java"), claim(folder, "c1", "src/a/A.java")]
        check(firsts == [True, False, True, True],
              "a file is claimed by the first run to ask and refused to the next; another file, or the same file "
              "under another cluster, is a claim of its own: %s" % firsts)
        # a claim of a run that is gone is taken over, once; a live holder's, or one that cannot be read, is kept
        gone_run = subprocess.Popen([sys.executable, "-c", "import sys; sys.stdin.read()"], stdin=subprocess.PIPE,
                                    creationflags=NO_WINDOW)
        gone_began = bundle_shell.process_started_at(gone_run.pid)
        gone_run.stdin.close()
        gone_run.wait()

        def left(path, text):
            name = "claim-" + hashlib.sha1(unit_key("audit", path).encode("utf-8")).hexdigest()[:20]
            with io.open(os.path.join(folder, name), "w", encoding="utf-8") as handle:
                handle.write(text)
            return name

        dead = left("src/a/C.java", json.dumps({"pid": gone_run.pid, "started_epoch": gone_began}))
        mine = left("src/a/D.java", json.dumps({"pid": os.getpid(),
                                                "started_epoch": bundle_shell.process_started_at(os.getpid())}))
        left("src/a/E.java", "")
        overs = [claim(folder, "audit", "src/a/C.java"), claim(folder, "audit", "src/a/C.java"),
                 claim(folder, "audit", "src/a/D.java"), claim(folder, "audit", "src/a/E.java")]
        aside = [n for n in os.listdir(folder) if n.startswith(dead + ".gone-")]
        check(overs == [True, False, False, False] and len(aside) == 1 and mine,
              "a claim whose run is gone is taken over once and moved aside under its holder's name; a live "
              "holder's claim, and one that cannot be read, are kept: %s, moved aside %s" % (overs, aside))
        # A RUN THAT FINISHED IS GONE TOO (2026-10-25): its claims are of files it recorded, and taken over they were
        # measured a second time - hours of it - by the run that outlived it
        recorded = left("src/a/F.java", json.dumps({"pid": gone_run.pid, "started_epoch": gone_began,
                                                     "recorded": True}))
        kept = claim(folder, "audit", "src/a/F.java")
        check(kept is False and not [n for n in os.listdir(folder) if n.startswith(recorded + ".gone-")],
              "a claim of a run that finished and recorded its file is kept, never taken over to measure the file "
              "again: %s" % kept)
        check(claim(folder, "audit", "src/a/G.java") is True, "control: a file nobody claimed is claimed")
        results_g = os.path.join(folder, "results-selftest.json")
        record_claimed(results_g, sha, {}, folder, "audit", "src/a/G.java", [], [])
        with io.open(claim_file(folder, "audit", "src/a/G.java"), encoding="utf-8") as handle:
            marked = json.loads(handle.read())
        with io.open(results_g, encoding="utf-8") as handle:
            units = json.load(handle).get("units") or {}
        check(marked.get("recorded") is True and marked.get("pid") == os.getpid()
              and unit_key("audit", "src/a/G.java") in units,
              "a file a run records says so on its claim, which still names the run: %s, recorded %s"
              % (marked, sorted(units)))
        try:
            claims_folder(folder, "0" * 40)
            other = ""
        except SystemExit as stop:
            other = str(stop)
        check(("was made for " + sha) in other,
              "a claims folder made for another tree is refused: " + (other or "it was taken"))

        (share / "sweep.out").write_text("\n".join([
            "measuring %s (frozen; the branch may move without affecting this run)" % sha[:7],
            "=== audit (merged from c869a0e onto a93ef21)",
            "  A.java: 2 mutable line(s), trying 2, 1 not mutable",
            "     L3     drop-report   unmutable   the value is used",
            "     L5     drop-throw    killed      ATest:   ok: refused",
            "     L7     negate-if     SURVIVED       <-- NOTHING ASSERTS THIS LINE",
            "  B.java: 1 mutable line(s), trying 1",
            "     L2     flip-return   killed      BTest: FAILURES PRESENT (1)",
            "  A.java: 1 mutable line(s), trying 1",
            "     L9     negate-if     hung        CTest: never finished",
            "  D.java: 1 mutable line(s), trying 1",
            "     L4     drop-throw    killed      DTest: x"]) + "\n", encoding="utf-8")
        whole = ["line %d" % n for n in range(1, 12)]
        reuse = Reuse([str(share / "sweep.out")], sha)
        a = reuse.take("audit", "src/x/A.java", [[3, "drop-report"], [5, "drop-throw"], [7, "negate-if"]], whole)
        got = [(r["line"], r["verdict"], r.get("by"), r["detail"], r["code"]) for r in a[1]] if a else None
        check(got == [(3, "unmutable", None, "the value is used", "line 3"),
                      (5, "killed", "ATest", "ATest:   ok: refused", "line 5"),
                      (7, "SURVIVED", "", "", "line 7")],
              "control: a file a log printed to its end is taken, each verdict with its killer and its code: %s" % got)
        b = reuse.take("audit", "src/x/B.java", [[2, "negate-if"]], whole)
        check(b is None and any("B.java" in why for why in reuse.refused),
              "a file whose recorded lines are not the ones this tree yields is measured again, and said so")
        a2 = reuse.take("audit", "src/y/A.java", [[9, "negate-if"]], whole)
        check(a2 is not None and [(r["line"], r["verdict"], r["by"]) for r in a2[1]] == [(9, "hung", "")],
              "a second file of the same name in the cluster is matched by its place, not taken as the first")
        d = reuse.take("audit", "src/x/D.java", [[4, "drop-throw"]], whole)
        check(d is None, "the file a log was still measuring when it ended is never taken")

        (share / "other.out").write_text("measuring 0000000 (frozen; x)\n", encoding="utf-8")
        try:
            Reuse([str(share / "other.out")], sha)
            other = ""
        except SystemExit as stop:
            other = str(stop)
        check("git does not find" in other,
              "a log of a commit git cannot find is refused whole: " + (other or "it was taken"))

        (share / "older.out").write_text("\n".join([
            "measuring %s (frozen; the branch may move without affecting this run)" % first[:7],
            "=== audit (merged from %s onto a93ef21)" % first[:7],
            "  A.java: 1 mutable line(s), trying 1",
            "     L1     drop-throw    killed      ATest: refused",
            "  B.java: 1 mutable line(s), trying 1",
            "     L1     drop-throw    killed      BTest: refused",
            "1 measurement(s) in 1 min"]) + "\n", encoding="utf-8")
        older = Reuse([str(share / "older.out")], sha)
        kept = older.take("audit", "src/x/A.java", [[1, "drop-throw"]], whole)
        moved = older.take("audit", "src/x/B.java", [[1, "drop-throw"]], whole)
        check(kept is not None and [r["by"] for r in kept[1]] == ["ATest"],
              "control: a file unchanged since an older commit's log is taken from it")
        check(moved is None and any("B.java" in why and "changed since" in why for why in older.refused),
              "a file changed since the commit a log measured is measured again, and said so: %s" % older.refused)
        try:
            Reuse([str(share / "older.out")], "1" * 40).take("audit", "src/x/A.java", [[1, "drop-throw"]], whole)
            other = ""
        except SystemExit as stop:
            other = str(stop)
        check("could not say" in other,
              "a file git could not compare stops the run - never read as unchanged: " + (other or "it was taken"))

        recorded = {}
        mine = [dict(cluster="audit", file="A.java", path="src/x/A.java", line=5, kind="drop-throw",
                     verdict="killed", detail="ATest: no", by="ATest", code="line 5")]
        record_unit(os.path.join(folder, "results-w1.json"), sha, recorded, "audit", "src/x/A.java",
                    [[5, "drop-throw"]], mine)
        back = Reuse([folder], sha)
        check(back.take("audit", "src/x/A.java", [[5, "drop-throw"]], whole) == ("results-w1.json", mine)
              and back.take("audit", "src/x/A.java", [[6, "drop-throw"]], whole) is None,
              "a share's recorded file is taken back whole, and only for the lines it measured")
        (share / "earlier").mkdir()
        record_unit(str(share / "earlier" / "results-w3.json"), first, {}, "audit", "src/x/B.java",
                    [[1, "drop-throw"]], mine)
        earlier = Reuse([str(share / "earlier")], sha)
        check(earlier.take("audit", "src/x/B.java", [[1, "drop-throw"]], whole) is None
              and any("changed since" in why for why in earlier.refused),
              "a file changed since the commit a share measured is measured again, and said so: %s" % earlier.refused)
        (share / "lost").mkdir()
        with io.open(str(share / "lost" / "results-w2.json"), "w", encoding="utf-8") as handle:
            handle.write(json.dumps({"frozen": "2" * 40, "units": {}}))
        try:
            Reuse([str(share / "lost")], sha)
            other = ""
        except SystemExit as stop:
            other = str(stop)
        check("git does not find" in other,
              "a share recorded for a commit git cannot find is refused whole: " + (other or "it was taken"))
        #A VERDICT IS TAKEN ONLY WHERE IT STILL MEASURES THIS TREE (2026-10-14): 82 SURVIVED verdicts recorded over
        #c869a0e would have gone into the baseline whole although the guards written since kill them - the file's
        #bytes did not move, its suites did
        ledger = [dict(cluster="audit", file="A.java", path="src/x/A.java", line=n, kind=k, verdict=v, detail=d, by=b,
                       code="line %d" % n)
                  for n, k, v, d, b in ((3, "drop-report", "unmutable", "the value is used", None),
                                        (5, "drop-throw", "killed", "ATest: no", "ATest"),
                                        (7, "negate-if", "SURVIVED", "", ""),
                                        (9, "negate-if", "killed", "SetupWizardTest: FAIL", "SetupWizardTest"))]
        for r in ledger:
            if r["by"] is None:
                del r["by"]
        expected = [[3, "drop-report"], [5, "drop-throw"], [7, "negate-if"], [9, "negate-if"]]
        picked = [dict(i=n - 1, j=n - 1, kind=k, repl="") for n, k in ((5, "drop-throw"), (7, "negate-if"),
                                                                       (9, "negate-if"))]
        (share / "at-first").mkdir()
        record_unit(str(share / "at-first" / "results-w4.json"), first, {}, "audit", "src/x/A.java", expected, ledger)
        (WT / "src/ctrmap/tests").mkdir(parents=True)
        (WT / "src/ctrmap/tests/ATest.java").write_text("a guard written after the sweep\n", encoding="utf-8")
        git("add", "-A")
        git(*(ident + ("commit", "-q", "-m", "third: a suite, and no production file")))
        third = git("rev-parse", "HEAD").stdout.strip()
        def lines_of(got):
            return None if got is None else ([r["line"] for r in got[1]], [c["i"] + 1 for c in got[2]])
        moved = Reuse([str(share / "at-first")], third).take_split("audit", "src/x/A.java", expected, whole, picked)
        check(lines_of(moved) == ([3, 5, 9], [7]),
              "after the suites changed, a SURVIVED verdict is measured again; the rest are taken: %s"
              % (lines_of(moved),))
        still = Reuse([str(share / "at-first")], sha).take_split("audit", "src/x/A.java", expected, whole, picked)
        check(lines_of(still) == ([3, 5, 7, 9], []),
              "control: with the suites as they were, every verdict of a run that confirmed its kills is taken: %s"
              % (lines_of(still),))
        (share / "unconfirmed").mkdir()
        with io.open(str(share / "unconfirmed" / "results-w5.json"), "w", encoding="utf-8") as handle:
            handle.write(json.dumps({"frozen": first, "units": {unit_key("audit", "src/x/A.java"):
                                                                    {"expected": expected, "results": ledger}}}))
        old = Reuse([str(share / "unconfirmed")], sha).take_split("audit", "src/x/A.java", expected, whole, picked)
        check(lines_of(old) == ([3, 7], [5, 9]),
              "a run that did not say its kills were confirmed has every kill measured again: %s" % (lines_of(old),))
        printed = Reuse([str(share / "sweep.out")], sha).take_split(
            "audit", "src/x/A.java", [[3, "drop-report"], [5, "drop-throw"], [7, "negate-if"]], whole,
            [dict(i=4, j=4, kind="drop-throw", repl=""), dict(i=6, j=6, kind="negate-if", repl="")])
        check(lines_of(printed) == ([3, 7], [5]),
              "nor does a printed log say so - its kills are measured again too: %s" % (lines_of(printed),))
        #THE BEST RECORD OF A FILE, NOT THE LAST ONE READ (2026-10-17): one file recorded twice - unconfirmed at the
        #older commit, kills confirmed at the newer - is answered by the confirmed one, whichever folder comes last
        (share / "confirmed-later").mkdir()
        record_unit(str(share / "confirmed-later" / "results-w6.json"), sha, {}, "audit", "src/x/A.java", expected,
                    ledger)
        best = [lines_of(Reuse([str(share / a), str(share / b)], third).take_split("audit", "src/x/A.java", expected,
                                                                                 whole, picked))
                for a, b in (("confirmed-later", "unconfirmed"), ("unconfirmed", "confirmed-later"))]
        check(best == [([3, 5, 9], [7])] * 2,
              "of two records of one file the confirmed newer one is taken, whichever folder is given last - only the"
              " survivor measured again after the suites changed: %s" % (best,))
        try:
            split_reuse(ledger, [(11, "negate-if")], picked)
            other = ""
        except SystemExit as stop:
            other = str(stop)
        check("offers no candidate" in other,
              "a verdict to measure again with no candidate in this tree stops the run, never dropped: "
              + (other or "it was dropped"))
        try:
            Reuse([str(share / "at-first")], "1" * 40).suites_moved(first)
            other = ""
        except SystemExit as stop:
            other = str(stop)
        check("suites nobody could compare" in other,
              "suites git could not compare stop the run - never read as unchanged: " + (other or "they were"))
        noted_lines = [dict(i=2, kind="drop-report", verdict="unmutable", why="the value is used")]
        plans = [plan_unit(None, True, picked, noted_lines)[0], plan_unit(moved, True, picked, noted_lines)[:3:2],
                 plan_unit(("s", ledger, []), True, picked, noted_lines)[0],
                 plan_unit(("s", ledger, []), False, picked, noted_lines)[0]]
        check(plans[0] == "measure" and plans[1] == ("measure", [moved[2][0]]) and plans[2] == "skip"
              and plans[3] == "take",
              "a file no record answers is measured; one with verdicts owed measures only those, sharing or not; one "
              "answered whole is skipped by a share and taken by the run that writes the baseline: %s" % (plans,))
    finally:
        WT = saved_wt_share
        shutil.rmtree(str(share), ignore_errors=True)

    #A KILL IS CONFIRMED BY THE SAME SUITE A SECOND TIME (2026-10-14): a suite that failed once and passes the same
    #mutant again did not notice it, and the next suite is asked; one that fails twice killed it
    real_run, script = run, []

    def scripted(cmd, timeout=900, keep=TAIL_BYTES):
        code = script.pop(0)
        return Ran(code, "FAILURES PRESENT (1)" if code else "ALL PASS", "", False, 0)

    run = scripted
    saved_unconfirmed = list(UNCONFIRMED)
    try:
        flaky_then_real = [("ctrmap.tests.FlakyTest", []), ("ctrmap.tests.RealTest", [])]
        script[:] = [1, 0, 1, 1]
        got = judge("src/t/T.java", flaky_then_real)
        check(got[0] == "killed" and got[2] == "RealTest" and ("src/t/T.java", "FlakyTest") in UNCONFIRMED
              and not script,
              "a suite that fails once and passes the same mutant again is not its killer; the next, failing twice, "
              "is: %s" % (got,))
        script[:] = [1, 0, 0]
        got = judge("src/t/T.java", flaky_then_real)
        check(got[0] == "SURVIVED" and not script,
              "control: and with no other suite failing, the mutant survives - one failing run is not a kill: %s"
              % (got,))
        script[:] = [1, HUNG_RC, 0]
        got = judge("src/t/T.java", flaky_then_real)
        check(got[0] == "hung" and not script,
              "a second run that never finishes confirms nothing: the mutant is unmeasured, never killed: %s" % (got,))
        #FLAKY IS NOT SOURCE-SENSITIVE (2026-10-16): a suite that fails the probe once and passes it again judges
        script[:] = [1, 0, 1, 1, 0]
        kept = [c.split(".")[-1] for c, _a in judges_of([("ctrmap.tests.FlakyTest", []), ("ctrmap.tests.BytesTest", []),
                                                          ("ctrmap.tests.GoodTest", [])])]
        check(kept == ["FlakyTest", "GoodTest"] and not script,
              "a suite that fails the probe once and passes it again is kept as a judge; one failing it twice is not: %s"
              % kept)
    finally:
        run = real_run
        UNCONFIRMED[:] = saved_unconfirmed

    #A BUILD THAT FAILED WITH NO JAVAC ERROR (2026-10-14): asked once more before any verdict, filed with what it
    #said when it fails again, and a record of such a failure is never reused - it measured the harness, not the file
    real_build, builds = build, []

    def scripted_build():
        return builds.pop(0)

    build = scripted_build
    try:
        builds[:] = [(False, "Build OK\nTHE BUILD'S STAMP REFUSED: build/classes is not what build.ps1 produced"),
                     (True, "Build OK")]
        check(built() == (True, "Build OK") and not builds,
              "a build that failed with no javac error is asked once more, and its second answer stands")
        builds[:] = [(False, "X.java:3: error: ';' expected"), (True, "Build OK")]
        check(built()[0] is False and len(builds) == 1,
              "control: a build javac refused is not asked again - that answer is about the mutant")
    finally:
        build = real_build
    #and the build itself carries the stamp's reason out with its answer (require_build), so there is one to name
    real_brun, real_stamp = require_build.bounded.run, require_build.stamp_problem
    require_build.bounded.run = lambda *a, **k: subprocess.CompletedProcess(a, 0, "Build OK\n", "")
    try:
        require_build.stamp_problem = lambda root: "src/ has changed since build.ps1 last ran - rebuild"
        refused = require_build.build_with_battery_verbose(".")
        require_build.stamp_problem = lambda root: None
        held = require_build.build_with_battery_verbose(".")
    finally:
        require_build.bounded.run, require_build.stamp_problem = real_brun, real_stamp
    check(refused[0] is False and "src/ has changed since build.ps1 last ran" in refused[1]
          and held == (True, "Build OK\n"),
          "a build whose stamp refused after 'Build OK' says why; control: one whose stamp holds adds nothing: %r / %r"
          % (refused, held))
    #A BUILD REFUSED AT A STRUCTURE GATE IS A KILL, by the gate's suite as build.ps1's own table names it
    table = build_gates('$gates = @(\n  @{ n = "Structure: one implementation per capability"; c = "ctrmap.tests.DuplicateWorkTest" },\n'
                        '  @{ n = "Structure: windows, dialogs and the seam";     c = "ctrmap.tests.DialogSeamTest" }\n)')
    refused_at = gate_kill("BUILD REFUSED - Structure: one implementation per capability\n  FAIL: control: the marker",
                           table)
    check(table.get("Structure: windows, dialogs and the seam") == "DialogSeamTest"
          and refused_at == ("DuplicateWorkTest", "Structure: one implementation per capability")
          and gate_kill("X.java:3: error: ';' expected", table) is None,
          "a build refused at a structure gate is a kill by that gate's suite; control: a javac error is not: %s"
          % (refused_at,))
    by_gate = failed_build("BUILD REFUSED - Structure: one implementation per capability\n  FAIL: the marker", table)
    by_javac = failed_build("X.java:3: error: ';' expected", table)
    check(by_gate[0] == "killed" and by_gate[2] == "DuplicateWorkTest" and by_javac[0] == "nocompile" and not by_javac[2],
          "and that is the verdict a failed build is given - killed, its gate's suite its killer: %s / %s"
          % (by_gate, by_javac))
    #A GATE THAT STOPPED WITHOUT FAILING A CHECK JUDGED NOTHING (2026-10-14): DialogSeamTest exited non-zero under
    #eight sweep JVMs having failed nothing, and build.ps1 at c869a0e still headed that BUILD REFUSED over an ok line
    #that said "refused" - credited here, it is a kill by a suite that never saw the mutant
    crashed_old = ("BUILD REFUSED - Structure: windows, dialogs and the seam\n"
                   "    ok: and a blank reason is refused, the control left as it was\n"
                   "  These are structural rules, checked here because checking them in the")
    crashed_new = ("BUILD FAILED - Structure: windows, dialogs and the seam: its gate stopped (exit 1) without failing a"
                   " check, so it judged nothing. Its last lines:\n  Exception in thread \"main\" java.lang.OutOfMemoryError")
    check(gate_kill(crashed_old, table) is None and gate_kill(crashed_new, table) is None
          and failed_build(crashed_old, table)[0] != "killed" and failed_build(crashed_new, table)[0] != "killed",
          "a gate that stopped without failing a check is no kill, headed REFUSED or FAILED: %s / %s"
          % (failed_build(crashed_old, table), failed_build(crashed_new, table)))
    builds[:] = [(False, "BUILD REFUSED - Structure: one implementation per capability\n  FAIL: the marker"),
                 (True, "Build OK")]
    real_build2, build = build, scripted_build
    try:
        check(built()[0] is False and len(builds) == 1,
              "and a gate's refusal is not asked again - a gate is the source's, not a moment's")
        builds[:] = [(False, crashed_old), (True, "Build OK")]
        check(built() == (True, "Build OK") and not builds,
              "but a gate that judged nothing is asked once more, as any build that told the sweep nothing")
    finally:
        build = real_build2
    said = why_it_failed("Build OK\nTHE BUILD'S STAMP REFUSED: src/ has changed since build.ps1 last ran")
    check(said[0] == "nocompile" and "STAMP REFUSED: src/ has changed" in said[1],
          "a nocompile with no javac error names what the build said: %s" % (said,))
    clean = Path(tempfile.mkdtemp(prefix="mutate2-nocompile-"))
    try:
        (clean / "n.out").write_text("\n".join([
            "measuring %s (frozen; x)" % ("c" * 7),
            "=== audit (merged from x onto y)",
            "  N.java: 1 mutable line(s), trying 1",
            "     L4     negate-if     nocompile   the harness emitted something javac rejected: no diagnostic captured",
            "1 measurement(s) in 1 min"]) + "\n", encoding="utf-8")
        candidates = [{"i": 3, "kind": "negate-if"}, {"i": 4, "kind": "drop-throw"}]
        taken = Reuse([str(clean / "n.out")], "c" * 40)
        got = taken.take_split("audit", "src/x/N.java", [[4, "negate-if"]], ["x"] * 5, candidates[:1])
        check(got is not None and [c["i"] + 1 for c in got[2]] == [4] and not got[1],
              "a printed nocompile is never reused - that mutant is measured again: %s" % (got,))
        (clean / "shares").mkdir()
        record_unit(str(clean / "shares" / "results-w9.json"), "c" * 40, {}, "audit", "src/x/N.java",
                    [[4, "negate-if"], [5, "drop-throw"]],
                    [dict(cluster="audit", file="N.java", path="src/x/N.java", line=4, kind="negate-if",
                          verdict="nocompile", detail="x", by="", code="x"),
                     dict(cluster="audit", file="N.java", path="src/x/N.java", line=5, kind="drop-throw",
                          verdict="killed", detail="ATest: refused", by="ATest", code="x")])
        shared = Reuse([str(clean / "shares")], "c" * 40)
        got = shared.take_split("audit", "src/x/N.java", [[4, "negate-if"], [5, "drop-throw"]], ["x"] * 6, candidates)
        check(got is not None and [c["i"] + 1 for c in got[2]] == [4] and [r["line"] for r in got[1]] == [5],
              "and a nocompile a share recorded is measured again alone, the rest of its file taken - one was "
              "costing whole files: %s" % (got,))
    finally:
        shutil.rmtree(str(clean), ignore_errors=True)
    print("ALL PASS" if not fails[0] else "FAILURES PRESENT (%d)" % fails[0])
    return 1 if fails[0] else 0


if "--selftest" in sys.argv:
    sys.exit(selftest())

if not JDK:
    raise SystemExit("No JDK found under C:\\Program Files\\Eclipse Adoptium and CTRMAP_JDK is "
                     "not set - the sweep runs the battery, which needs one.")

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
# THE WORK ORDER, ASKED BEFORE ANYTHING IS TOUCHED. A sweep measures a FROZEN
# tree and records a digest of every file in it, so one started while work is
# still queued is not a slow measurement but a discarded one. That rule was
# written down twice and broken anyway, which is why this refuses instead of
# reminding. See tools/guard/work_order.py.
sys.path.insert(0, str(Path(__file__).resolve().parent / "guard"))
import work_order
WORK_ORDER_OVERRIDE = work_order.require_frozen("the mutation sweep", sys.argv)

git("reset", "--hard", MAINLINE)
FROZEN = git("rev-parse", "HEAD").stdout.strip()
print("measuring %s (frozen; the branch may move without affecting this run)" % FROZEN[:7], flush=True)
# asked before a single suite runs: a record of another tree, or a claims folder made for one, refuses here
if len([a for a in _RAW if a == "--claims"]) > 1:
    raise SystemExit("--claims given twice - a run shares one sweep, so it claims in one folder")
REUSE = Reuse(REUSE_FROM, FROZEN)
for _name, _units, _at in REUSE.logs:
    print("reusing %s: %d file(s) printed to their end, %d verdict(s), measured at %s"
          % (_name, len(_units), sum(len(v) for v in _units.values()), _at[:7]), flush=True)
if REUSE.records:
    print("reusing %d file(s) recorded by runs that shared a sweep" % len(REUSE.records), flush=True)
if CLAIMS:
    claims_folder(CLAIMS, FROZEN)
    CLAIMED_TO = os.path.join(CLAIMS, "results-%s.json" % WT.name)
    print("sharing this sweep through %s: measuring only the files this run claims, recording them in %s"
          % (CLAIMS, CLAIMED_TO), flush=True)
CLAIMED, OTHERS_CLAIMED = {}, [0]
# resolved AFTER the reset, so the suites and arguments come from the tree
# about to be measured, not whatever test.ps1 the worktree held before
RESOLVED = resolve_clusters()
#the build's structure gates, read from the build.ps1 of the tree measured: a mutant refused at one is a kill
GATES = build_gates((WT / "build.ps1").read_text(encoding="utf-8", errors="replace"))
print("the build's structure gates: %s" % (", ".join("%s (%s)" % kv for kv in sorted(GATES.items())) or "NONE READ"),
      flush=True)
print("clusters: " + ", ".join("%s@%s" % (b, base[0][:7]) for b, (base, _) in RESOLVED.items()), flush=True)
print("excluded by hand (%d, ceiling %d):" % (len(EXCLUSIONS), EXCLUSION_CEILING), flush=True)
for (_p, _l), (_t, _w) in sorted(EXCLUSIONS.items()):
    print("   %s:%d - %s" % (_p, _l, _w), flush=True)

# REFUSE TO SCORE AGAINST A RED BASELINE. If a suite already fails unmutated,
# every mutant it "detects" is a free kill and the whole run reads as healthier
# than it is. This check is the only thing standing between a contaminated sweep
# and a number nobody can tell is wrong.
print("baseline: the suites must pass UNMUTATED before anything is scored", flush=True)
if not build()[0]:
    raise SystemExit("baseline does not compile - fix the tree before measuring it")
_seen = set()
for _cid, (_b, _suites) in RESOLVED.items():
    for cls, a in _suites:
        if cls in _seen:
            continue                     # one suite guards several branches; check it once
        _seen.add(cls)
        r = run([JAVA, "-Xmx4g", "-cp", CP, cls] + a)
        if r.flooded:
            # a suite that floods unmutated makes every mutant's flood meaningless
            raise SystemExit("baseline FLOODS: %s printed %d MB unmutated. A flood is scored as "
                             "a detected change, so it cannot also be this suite's normal "
                             "behaviour. Fix the suite, then measure."
                             % (cls, r.out_bytes // (1024 * 1024)))
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

# NOTHING BUT THE WHOLE BATTERY MAY CALL A LINE A SURVIVOR.
#
# The two rules above - a cluster's own suites, then every suite that guards the
# file - are both derived from CLUSTERS, which is maintained by hand. When it
# drifted, the sweep did not slow down or complain; it printed "NOTHING ASSERTS
# THIS LINE" about 30 lines that the battery kills every single run, and two
# agents were dispatched to write guards that already existed.
#
# The refusal in resolve_clusters stops that table from going stale again. This
# stops a stale table from being able to manufacture a survivor even so, because
# a guard that depends on someone remembering something is not a guard. A
# survivor now has to get past EVERY suite the battery registers, so the claim
# is about the battery rather than about the table.
#
# It is not expensive where it would hurt. The ordering is unchanged, so a kill
# still stops at the first suite that catches it - one or two runs, as before.
# Only a line that is about to be recorded as unasserted pays for the rest, and
# that is exactly the verdict worth paying for: a wrong "killed" costs a rerun,
# a wrong "SURVIVED" costs somebody a day writing a duplicate guard.
ALL_SUITES = [("ctrmap.tests." + s, a) for s, a in sorted(suite_args().items())]
print("last resort: %d registered suite(s), run only for a line about to be called a survivor"
      % len(ALL_SUITES), flush=True)


def _mentions():
    """{simple class name: [fqcn of every suite whose source names it]}, read from the suites."""
    # Which suites guard a file, DERIVED rather than declared. CLUSTERS says so
    # by hand and was wrong about 48 of 86; a suite that exercises a class very
    # nearly always names it, and where this guesses wrong it costs ordering, not
    # truth, because the full pass runs either way. This is only about reaching
    # the killer early: without it a survivor pays 86 runs in alphabetical order,
    # and the suite that actually catches it is as likely to be last as first.
    out = {}
    for f in sorted((WT / "src/ctrmap/tests").glob("*Test.java")):
        fq = "ctrmap.tests." + f.stem
        text = f.read_text(encoding="utf-8", errors="replace")
        for name in set(re.findall(r"\b([A-Z]\w{2,})\b", text)):
            out.setdefault(name, []).append(fq)
    return out


MENTIONS = _mentions()

PRIOR_EXACT, PRIOR_FILE = _prior_killers()
if PRIOR_EXACT:
    print("last run named the killer for %d line(s) in %d file(s); those go first"
          % (sum(len(v) for v in PRIOR_EXACT.values()), len(PRIOR_EXACT)), flush=True)



def _judges():
    """ALL_SUITES minus every suite that fails merely because a source file changed.

    Not every suite judges the PROGRAM. MutationBaselineTest hashes the sources
    the baseline recorded, so any mutation to one of those files fails it - and
    once a survivor is made to face every registered suite, that turns into a
    kill for a line nothing actually asserts. It happened: the first merged run
    credited it with 13 of its first 124 kills before this existed.

    Which suites those are is measured, not listed. A list would be one more
    thing to remember, and this file has just been through what that costs. So:
    change a source file in a way that alters its bytes and nothing else, build,
    and ask. Whatever fails is asserting something about the repository rather
    than about the program, and cannot be allowed to judge a mutant.
    """
    probe = "src/ctrmap/formats/garc/GARC.java"      # a file the baseline records
    original = read_src(probe)
    write_src(probe, original.rstrip("\r\n") + "\r\n//mutate2 probe: bytes changed, behaviour not\r\n")
    try:
        if not build():
            raise SystemExit("the source-sensitivity probe did not compile - that is this "
                             "script's defect, not the tree's")
        out = judges_of(ALL_SUITES)
    finally:
        write_src(probe, original)
        build()
    print("judges: %d of %d registered suite(s) can decide a mutant"
          % (len(out), len(ALL_SUITES)), flush=True)
    if not out:
        raise SystemExit("no suite survived the probe - refusing to sweep, every verdict "
                         "would be meaningless")
    return out


JUDGES = _judges()

for cid, (base, suites) in RESOLVED.items():
    before, tip = base
    print("\n=== %s (merged from %s onto %s)" % (cid, tip[:7], before[:7]), flush=True)
    for path, texts in added_lines(before, tip).items():
        if not (WT / path).is_file():
            continue                     # a file the fix added and a later merge removed
        cands, noted = mutants_for(path, relocate(path, texts))
        if not cands and not noted:
            continue
        step = max(1, len(cands) // CAP) if cands else 1
        picked = cands[::step][:CAP]
        whole = read_src(path).splitlines()
        expected = unit_expected(noted, picked)
        # asked of EVERY file, in order, whichever run measures it: a log is matched to this tree by its position
        reused = REUSE.take_split(cid, path, expected, whole, picked)
        plan, taken, picked, noted = plan_unit(reused, bool(CLAIMS), picked, noted)
        if plan == "skip":
            continue                           # recorded already: the run that reuses the records takes it
        if CLAIMS and not claim(CLAIMS, cid, path):
            OTHERS_CLAIMED[0] += 1
            continue                           # another run sharing the sweep measures it
        if len(cands) > CAP:
            SAMPLED_FILES.append(path)         # this run is a sample, not a record
        print("  %s: %d mutable line(s), trying %d%s"
              % (path.split("/")[-1], len(cands), len(picked),
                 (", %d not mutable" % len(noted)) if noted else ""), flush=True)
        first = len(results)
        if reused is not None:
            print("     (measured by an earlier run of this tree - %s%s)"
                  % (reused[0], "; %d of its verdict(s) measured again here: taken before the suites changed, or a "
                     "kill by a suite that fails for its own reasons" % len(picked) if plan == "measure" else ""),
                  flush=True)
            for r in taken:
                mark = "   <-- NOTHING ASSERTS THIS LINE" if r["verdict"] == "SURVIVED" else ""
                print("     L%-5d %-13s %-11s %s%s" % (r["line"], r["kind"], r["verdict"], r["detail"], mark),
                      flush=True)
                if r.get("by"):
                    learn(path, r["by"])
            results.extend(taken)
        if plan == "take":
            continue
        for n in noted:
            # recorded BEFORE anything is emitted, so "no valid mutation exists"
            # can never be confused with "the mutation did not compile"
            print("     L%-5d %-13s %-11s %s" % (n["i"] + 1, n["kind"], n["verdict"], n["why"]),
                  flush=True)
            results.append(dict(cluster=cid, file=path.split("/")[-1], path=path, line=n["i"] + 1,
                                kind=n["kind"], verdict=n["verdict"], detail=n["why"],
                                code=whole[n["i"]].strip()[:160]))

        for c in picked:
            i, j, kind, repl = c["i"], c["j"], c["kind"], c["repl"]
            original = read_src(path)
            src = original.splitlines(keepends=True)
            ending = src[i][len(src[i].rstrip("\r\n")):]
            src[i:j + 1] = [repl + ending] if repl is not None else []
            # owed from this line until the restore at the bottom of the loop;
            # atexit pays it if anything in between escapes
            _PENDING_RESTORE.append((path, original))
            write_src(path, "".join(src))

            ok, buildout = built()
            if not ok:
                # A NOCOMPILE IS THIS SCRIPT'S DEFECT, NOT THE CODE'S: the
                # operator emitted something malformed - the line's extent was
                # misread, or a return type was wrong. It is kept in the
                # accounting and shouted about at the end so it gets fixed
                # rather than absorbed as a property of the tree. What javac
                # calls a missing return or an uninitialised variable is a
                # different thing - a line with no legal mutation of this kind -
                # and is filed as unmutable, which is what it is.
                # A BUILD REFUSED AT A STRUCTURE GATE, though, is the battery noticing: a kill (failed_build)
                verdict, detail, by = failed_build(buildout, GATES)
                if by:
                    learn(path, by)
            else:
                # the branch's own suites first - they are the most specific and
                # usually the killer, so a kill still costs one or two runs;
                # only a true survivor pays for the whole union
                ordered = suites + [x for x in FILE_SUITES.get(path, []) if x not in suites]
                verdict, detail, by = judge(path, suites_for(path, ordered, i + 1, kind))

            mark = "   <-- NOTHING ASSERTS THIS LINE" if verdict == "SURVIVED" else ""
            print("     L%-5d %-13s %-11s %s%s" % (i + 1, kind, verdict, detail, mark), flush=True)
            # EVERY attempt is recorded, nocompile included. Dropping them shrinks
            # the denominator silently: the last run reported "87 compiling
            # mutants" and never mentioned the 9 that were never measured at all.
            results.append(dict(cluster=cid, file=path.split("/")[-1], path=path, line=i + 1,
                                kind=kind, verdict=verdict, detail=detail, by=by,
                                code=original.splitlines()[i].strip()[:160]))
            write_src(path, original)
            del _PENDING_RESTORE[:]            # paid; nothing is owed now

        if CLAIMS:
            record_claimed(CLAIMED_TO, FROZEN, CLAIMED, CLAIMS, cid, path, expected, results[first:])

    git("reset", "--hard", FROZEN)

build()
if REUSE.refused:
    print("\n%d file(s) a record named were measured again here - what it recorded is not what this tree yields:"
          % len(REUSE.refused))
    for why in REUSE.refused:
        print("   " + why)
if UNCONFIRMED:
    # a suite that fails a mutant once and passes it the next time is failing for its own reasons: named, so it is
    # fixed rather than left to buy kills it never earned
    print("\n%d kill(s) NOT CONFIRMED - the suite passed the same mutant on its second run:" % len(UNCONFIRMED))
    for _path, _suite in UNCONFIRMED:
        print("   %s by %s" % (_path, _suite))
if CLAIMS:
    # A SHARE OF A SWEEP IS NOT A SWEEP: no aggregate, no ratchet, no baseline from it - the run that reuses the
    # claims folder writes the one baseline, from every share and the measuring it still owes
    print("\nthis run measured %d file(s) of the sweep it shares, recorded in %s, in %d min; %d were taken from "
          "earlier records and %d claimed by other runs. The baseline is written by the run that reuses %s."
          % (len(CLAIMED), CLAIMED_TO, (time.time() - t0) / 60, REUSE.taken, OTHERS_CLAIMED[0], CLAIMS))
    raise SystemExit(0)
if REUSE.taken:
    print("\n%d file(s) were measured by an earlier run of this tree and taken from its record" % REUSE.taken)
with io.open(BASE / "wt/_state/mutation_semantic.json", "w", encoding="utf-8", newline="\n") as handle:
    handle.write(
        json.dumps(results, indent=1))

lines_, tally, disagreements = aggregate(results)
survived = [d for d in lines_ if d["verdict"] == "SURVIVED"]
floods = [d for d in lines_ if d["verdict"] == "killed" and "OUTPUT FLOOD" in d["detail"]]
attempted = sum(tally.get(v, 0) for v in ("killed", "SURVIVED", "hung", "nocompile"))
scored = tally.get("killed", 0) + len(survived)

print("\n%d measurement(s) in %d min, on %d DISTINCT (file, line, kind) mutant(s) - a line "
      "touched by several branches is measured once per branch and counted once"
      % (len(results), (time.time() - t0) / 60, len(lines_)))
if SKIPPED_AMBIGUOUS:
    # not measured, and said so: a line whose text is not unique in today's
    # file cannot be relocated from the branch's diff without guessing
    print("   %d added line(s) skipped as not uniquely relocatable, e.g. %s"
          % (len(SKIPPED_AMBIGUOUS), "; ".join("%s: %s" % s for s in SKIPPED_AMBIGUOUS[:3])))
if SKIPPED_GONE:
    # the other half of "not measured", which used to be recorded nowhere. See
    # relocate(): a later merge edited or removed the line, so its text is not
    # in today's file at all. Where the editing merge is itself in CLUSTERS its
    # replacement is measured under that branch; where it is not, nothing
    # measures it, and this is the only place a reader would find out.
    print("   %d added line(s) skipped because their text is no longer in the tree - a later "
          "merge\n       edited or removed them. Measured under the editing branch where that "
          "branch is\n       in CLUSTERS; not measured at all where it is not. e.g. %s"
          % (len(SKIPPED_GONE), "; ".join("%s: %s" % s for s in SKIPPED_GONE[:3])))
for v in PRECEDENCE:
    print("   %-10s %d" % (v, tally.get(v, 0)))
if floods:
    print("   (of the kills, %d were an OUTPUT FLOOD rather than a failed assertion - a "
          "detected behaviour change either way, and named as which)" % len(floods))
# NOTHING HIDDEN BY OMISSION: every candidate lands in exactly one bucket, and
# the buckets must add back up. An unmeasured mutant is a fact about the
# measurement, not an absence of one - and "no valid mutation exists" (unmutable)
# and "the mutation did not compile" (nocompile) are different facts.
assert sum(tally.values()) == len(lines_), "a mutant fell out of the accounting"
print("   accounting: %d killed + %d survived + %d hung + %d nocompile = %d ATTEMPTED; "
      "+ %d unmutable + %d excluded = %d candidate line(s)"
      % (tally.get("killed", 0), len(survived), tally.get("hung", 0), tally.get("nocompile", 0),
         attempted, tally.get("unmutable", 0), tally.get("excluded", 0), len(lines_)))
if scored:
    print("   score: %d/%d = %.0f%% of MEASURABLE mutants killed (hung, nocompile, unmutable "
          "and excluded are neither killed nor survived)"
          % (tally.get("killed", 0), scored, 100.0 * tally.get("killed", 0) / scored))
if FIRST_TRY[1]:
    print("   ordering: the first suite tried was the killer %d of %d time(s) (%.0f%%). "
          "Every miss is a JVM start this sweep paid for a wrong guess; the next run "
          "starts from the killers this one recorded."
          % (FIRST_TRY[0], FIRST_TRY[1], 100.0 * FIRST_TRY[0] / FIRST_TRY[1]))
if tally.get("nocompile", 0):
    print("   !! %d nocompile - THE HARNESS'S OWN DEFECT, not the tree's. Each one is a line "
          "this sweep failed to measure because it emitted something malformed; fix the "
          "operator rather than reading it as a property of the code:" % tally["nocompile"])
    for d in lines_:
        if d["verdict"] == "nocompile":
            print("      %s:%d %s  %s" % (d["path"], d["line"], d["kind"], d["code"][:70]))
if disagreements:
    print("   !! %d line(s) came back DIFFERENTLY on different branches. The suite union makes "
          "the branches irrelevant, so this is nondeterminism in the suite, not in the mutant. "
          "Killed anywhere counts as killed:" % len(disagreements))
    for d in disagreements:
        print("      %s:%d %s - %s" % (d["path"], d["line"], d["kind"],
                                       ", ".join("%s=%s" % p for p in zip(d["clusters"], d["verdicts"]))))
if tally.get("excluded", 0):
    print("   excluded from both the numerator and the denominator, by hand:")
    for d in lines_:
        if d["verdict"] == "excluded":
            print("      %s:%d - %s" % (d["path"], d["line"], d["detail"]))
if tally.get("unmutable", 0):
    print("   no valid mutation exists for these line(s) - nothing was emitted, so they are "
          "not nocompiles:")
    for d in lines_:
        if d["verdict"] == "unmutable":
            print("      %s:%d %s - %s" % (d["path"], d["line"], d["kind"], d["detail"]))
print("   survivors - a line the whole battery does not assert:")
for d in survived:
    print("   %-26s L%-5d %-13s %s" % (d["file"], d["line"], d["kind"], d["code"][:70]))

# ---- the ratchet -------------------------------------------------------------
# THE ONE COPY. This used to be BASE / "wt/_state/mutation_baseline.json" while
# MutationBaselineTest read CTRMap/mutation_baseline.json, and the two were kept
# equal BY HAND. They happened to be byte-identical, which is not a property -
# it is a coincidence that survives until the first sweep nobody copies out, and
# then the sweep writes one record while the battery checks another and neither
# says so. The sweep now writes the file the battery reads, found from this
# script's own location rather than from a spelled-out path, so moving the repo
# cannot separate them again. MutationBaselineTest asserts the two agree.
BASELINE = baseline_path()
live = build_live(lines_)

# The baseline names the sources it measured. MutationBaselineTest refuses a
# baseline whose digest is not the current build stamp's, so a fix line added
# after the sweep - a new mutation site nobody has measured - cannot hide
# behind an old count. This is the "denominator still matches the live source"
# invariant, done with the stamp instead of a second site count.
# per measured FILE, not the whole tree: the baseline says nothing about
# src/ctrmap/tests (the sweep never measures it), so an edit there must not
# invalidate it - and the baseline itself has to live outside src/ or
# committing it would change the digest it is checked against.
# Computed BEFORE the ratchet, which needs it: comparing recorded line NUMBERS
# against a file that has moved since compares nothing, and the digest is how
# the ratchet knows to say so instead.
for _p, _f in live.items():
    _abs = WT / _p
    # require_build.digest_bytes, not read_bytes: the baseline must not go stale
    # because a checkout handed the file CRLF where the sweep saw LF
    _f["sha256"] = (hashlib.sha256(require_build.digest_bytes(_abs)).hexdigest()
                    if _abs.is_file() else "")

regressed, fresh_holes = [], []
if BASELINE.exists():
    with io.open(BASELINE, encoding="utf-8") as handle:
        old = json.load(handle)
    regressed, fresh_holes, fixed, lost, had_killed_lines = compare_to_baseline(live, old)
    for line in fixed:
        print("  fixed: " + line)
    print("\nratchet: %d file(s) measured against the recorded baseline" % len(live))
    if not had_killed_lines:
        print("  note: the recorded baseline predates line-level kill records, so a line that "
              "regressed cannot be told from one measured for the first time. This run "
              "re-establishes the ratchet; the next one can break on a regression.")
    if lost:
        print("  COVERAGE LOST - killed in the baseline, not measured in this run:")
        for r in lost:
            print("     " + r)
    if regressed:
        print("  RATCHET BROKEN - these lines were killed before and survive now:")
        for r in regressed:
            print("     " + r)
    else:
        print("  ok: no line the baseline recorded as killed has started surviving")
    if fresh_holes:
        print("  new coverage (NOT a regression - these were never measured before):")
        for r in fresh_holes:
            print("     " + r)
else:
    print("\nratchet: no baseline yet, recording this run as the starting line")

live["_meta"] = {"measured_at": FROZEN[:7],
                 "work_order_override": WORK_ORDER_OVERRIDE,
                 "measurements": len(results), "distinct": len(lines_),
                 "attempted": attempted, "survived": len(survived),
                 "killed": tally.get("killed", 0),
                 "killed_by_flood": len(floods),
                 "hung": tally.get("hung", 0), "nocompile": tally.get("nocompile", 0),
                 "unmeasured": tally.get("hung", 0) + tally.get("nocompile", 0),
                 "unmutable": tally.get("unmutable", 0),
                 "excluded": tally.get("excluded", 0)}
_target = baseline_target(SAMPLED_FILES)
if SAMPLED_FILES:
    print("\nSAMPLED RUN - NOT the battery's gate. CAP=%d left %d file(s) partly measured, e.g. %s"
          % (CAP, len(SAMPLED_FILES), ", ".join(p.split("/")[-1] for p in SAMPLED_FILES[:4])))
    print("  A record that measured a fraction of each file is byte-for-byte the shape of a whole")
    print("  one, except every number is smaller. Written to %s instead;" % _target.name)
    print("  re-run with 999 to produce a baseline the battery may be gated on.")
with io.open(_target, "w", encoding="utf-8", newline="\n") as handle:
    handle.write(json.dumps(live, indent=1))
print("baseline written to %s" % _target)
print("  to make it the battery's gate, copy it to CTRMap/mutation_baseline.json "
      "and commit - MutationBaselineTest reads it from there")
if regressed:
    raise SystemExit(1)
