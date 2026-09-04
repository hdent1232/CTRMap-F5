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

BASE = Path(r"C:\Users\flami\Desktop\Claude\sessions\3DS Editor")
WT = BASE / "wt" / "_guardcheck"
DUMP = BASE / "RomFS_original_garcs"
_ARGS = [a for a in sys.argv[1:] if not a.startswith("-")]
CAP = int(_ARGS[0]) if _ARGS else 6

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
CLUSTERS = {
    "c1":           ["PlacementGuardsTest", "MapPrefabTest"],
    "c3":           ["PaintedFloorTest", "PaintedRegionTest"],
    "c5a":          ["DataSafetyGuardsTest"],
    "c5b":          ["NpcEntityGuardsTest", "ZoneScriptAnalyzerTest", "NpcTemplatesTest"],
    "c6":           ["ScriptAssemblerGuardTest"],
    "c8":           ["DataSafetyGuardsTest", "BatteryHygieneTest"],
    "gap-warp":     ["DataSafetyGuardsTest"],
    "seam":         ["DataSafetyGuardsTest"],
    "c47":          ["ForkGuardsTest", "MatrixForkTest", "IntegrityTest", "PackReportTest",
                     "SnapshotIntegrityTest", "DataSafetyGuardsTest"],
    "c2":           ["PaintApplyGuardsTest", "TexturePackImportTest", "TerrainImportTest",
                     "AreaShareGuardTest"],
    "guards-paint": ["PaintedFloorTest", "PlacementGuardsTest", "MapPrefabTest",
                     "ScriptAssemblerGuardTest", "DataSafetyGuardsTest", "PaintApplyGuardsTest"],
    "guards-npc":   ["NpcEditFormGuardsTest", "NpcEntityGuardsTest", "DataSafetyGuardsTest",
                     "ZoneScriptAnalyzerTest", "NpcTemplatesTest"],
    "gap2":         ["PackReportTest", "DataSafetyGuardsTest", "DoorPropGuardsTest"],
    "uifix":        ["BatteryHygieneTest", "DataSafetyGuardsTest"],
    "d1":           ["TerrainImportNoiseTest", "BatteryHygieneTest", "BchMapModelTest"],
    "d2":           ["NpcEditFormGuardsTest", "BatteryHygieneTest"],
    "d3":           ["PackRollbackTest", "MisplacedRegistryTest", "IntegrityTest", "ForkGuardsTest"],
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
EXCLUSION_CEILING = 4
EXCLUSIONS = {
    ("src/ctrmap/Ui.java", 87): (
        "JOptionPane.showMessageDialog(parent, text, title, type);",
        "unreachable in a headless suite by construction: dialogs stay off unless the "
        "app's own main calls Ui.enableDialogs(), which no suite does and none may - "
        "that is the entire reason this seam exists. Nothing a headless test can "
        "observe distinguishes this line from its absence."),
    ("src/ctrmap/Ui.java", 84): (
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
                            stderr=subprocess.PIPE, bufsize=0)
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


def read_src(path):
    """The file exactly as it sits on disk, line endings included.

    newline="" on both halves of the read/restore pair. The previous version
    read with universal newlines and wrote back LF, so every file it touched was
    silently re-ended mid-sweep - harmless for javac, but the harness has no
    business rewriting bytes it was only asked to read.
    """
    return io.open(str(WT / path), encoding="utf-8", errors="replace", newline="").read()


def write_src(path, text):
    io.open(str(WT / path), "w", encoding="utf-8", newline="").write(text)


def relocate(path, texts):
    """Current line numbers of the given line texts in the tree under test."""
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
        elif len(hits) > 1 and eligible:
            SKIPPED_AMBIGUOUS.append((path.split("/")[-1], t.strip()[:60]))
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
            f["killed_lines"].append({"line": d["line"], "kind": d["kind"]})
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
    regressed, fresh, fixed = [], [], []
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
        for key in sorted(old_s - now_s):
            if key in killed_now:
                why = "is now asserted - a suite kills it"
            elif key[0] in excluded_now:
                why = ("is now EXCLUDED from the measurement by hand - NOT asserted; it counts "
                       "in neither the numerator nor the denominator")
            else:
                why = "is no longer measured at all - check why before reading it as progress"
            fixed.append("%s:%d %s %s" % (f, key[0], key[1], why))
    return regressed, fresh, fixed, had_killed_lines


import require_build


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
    return "nocompile", ("the harness emitted something javac rejected: "
                         + (errs[0][:120] if errs else "no diagnostic captured"))


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
}
"""


def selftest():
    import shutil
    import tempfile
    global WT, run
    fails = [0]

    def check(ok, what):
        print(("  ok: " if ok else "  FAIL: ") + what)
        if not ok:
            fails[0] += 1

    tmp = Path(tempfile.mkdtemp(prefix="mutate2-selftest"))
    saved_wt = WT
    try:
        (tmp / "src/t").mkdir(parents=True)
        io.open(tmp / "src/t/T.java", "w", encoding="utf-8", newline="").write(SELFTEST_JAVA)
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

        # DEFECT 5: an exclusion that has drifted onto another line is refused
        (tmp / "src/ctrmap").mkdir(parents=True)
        io.open(tmp / "src/ctrmap/Ui.java", "w", encoding="utf-8", newline="").write(
            u"\n".join(["// not the real Ui"] * 90))
        try:
            mutants_for("src/ctrmap/Ui.java", [84, 87])
            check(False, "a stale exclusion is refused rather than applied to whatever line it lands on")
        except SystemExit as e:
            check("STALE EXCLUSION" in str(e),
                  "a stale exclusion is refused rather than applied to whatever line it lands on")
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
    regressed, fresh, fixed, had = compare_to_baseline(live, old)
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
    _, _, fixed2, _ = compare_to_baseline(gone, old)
    check(len(fixed2) == 1 and "EXCLUDED" in fixed2[0] and "NOT asserted" in fixed2[0],
          "a survivor that was EXCLUDED by hand is not reported as one the battery now asserts")
    check(had, "the baseline is recognised as carrying line-level kill records")
    moved = {"src/F.java": dict(old["src/F.java"], sha256="different")}
    r2, f2, _, _ = compare_to_baseline(live, moved)
    check(not r2 and len(f2) == 2,
          "and when the file has changed since the baseline, its line numbers are not compared")

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
git("reset", "--hard", MAINLINE)
FROZEN = git("rev-parse", "HEAD").stdout.strip()
print("measuring %s (frozen; the branch may move without affecting this run)" % FROZEN[:7], flush=True)
# resolved AFTER the reset, so the suites and arguments come from the tree
# about to be measured, not whatever test.ps1 the worktree held before
RESOLVED = resolve_clusters()
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
        r = run([JAVA, "-Xmx4g", "-Djava.awt.headless=true", "-cp", CP, cls] + a)
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
        print("  %s: %d mutable line(s), trying %d%s"
              % (path.split("/")[-1], len(cands), len(picked),
                 (", %d not mutable" % len(noted)) if noted else ""), flush=True)
        whole = read_src(path).splitlines()
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
            write_src(path, "".join(src))

            ok, buildout = build()
            if not ok:
                # A NOCOMPILE IS THIS SCRIPT'S DEFECT, NOT THE CODE'S: the
                # operator emitted something malformed - the line's extent was
                # misread, or a return type was wrong. It is kept in the
                # accounting and shouted about at the end so it gets fixed
                # rather than absorbed as a property of the tree. What javac
                # calls a missing return or an uninitialised variable is a
                # different thing - a line with no legal mutation of this kind -
                # and is filed as unmutable, which is what it is.
                verdict, detail = why_it_failed(buildout)
            else:
                verdict, detail = "SURVIVED", ""
                # the branch's own suites first - they are the most specific and
                # usually the killer, so a kill still costs one or two runs;
                # only a true survivor pays for the whole union
                ordered = suites + [x for x in FILE_SUITES.get(path, []) if x not in suites]
                for cls, a in ordered:
                    r = run([JAVA, "-Xmx4g", "-Djava.awt.headless=true", "-cp", CP, cls] + a)
                    if r.returncode == HUNG_RC:
                        verdict, detail = "hung", cls.split(".")[-1] + ": never finished"
                        break
                    if r.flooded:
                        # checked BEFORE the exit code, because killing a flood
                        # leaves a non-zero code that would read as an ordinary
                        # failure and hide what actually happened
                        verdict = "killed"
                        detail = (cls.split(".")[-1] + ": OUTPUT FLOOD, %d MB and still going"
                                  % (r.out_bytes // (1024 * 1024)))
                        break
                    if r.returncode != 0:
                        tail = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
                        verdict = "killed"
                        detail = cls.split(".")[-1] + ": " + (tail[-1][:100] if tail else "")
                        break

            mark = "   <-- NOTHING ASSERTS THIS LINE" if verdict == "SURVIVED" else ""
            print("     L%-5d %-13s %-11s %s%s" % (i + 1, kind, verdict, detail, mark), flush=True)
            # EVERY attempt is recorded, nocompile included. Dropping them shrinks
            # the denominator silently: the last run reported "87 compiling
            # mutants" and never mentioned the 9 that were never measured at all.
            results.append(dict(cluster=cid, file=path.split("/")[-1], path=path, line=i + 1,
                                kind=kind, verdict=verdict, detail=detail,
                                code=original.splitlines()[i].strip()[:160]))
            write_src(path, original)

    git("reset", "--hard", FROZEN)

build()
io.open(BASE / "wt/_state/mutation_semantic.json", "w", encoding="utf-8", newline="\n").write(
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
BASELINE = BASE / "wt/_state/mutation_baseline.json"
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
    _f["sha256"] = hashlib.sha256(_abs.read_bytes()).hexdigest() if _abs.is_file() else ""

regressed, fresh_holes = [], []
if BASELINE.exists():
    old = json.load(io.open(BASELINE, encoding="utf-8"))
    regressed, fresh_holes, fixed, had_killed_lines = compare_to_baseline(live, old)
    for line in fixed:
        print("  fixed: " + line)
    print("\nratchet: %d file(s) measured against the recorded baseline" % len(live))
    if not had_killed_lines:
        print("  note: the recorded baseline predates line-level kill records, so a line that "
              "regressed cannot be told from one measured for the first time. This run "
              "re-establishes the ratchet; the next one can break on a regression.")
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
                 "measurements": len(results), "distinct": len(lines_),
                 "attempted": attempted, "survived": len(survived),
                 "killed": tally.get("killed", 0),
                 "killed_by_flood": len(floods),
                 "hung": tally.get("hung", 0), "nocompile": tally.get("nocompile", 0),
                 "unmeasured": tally.get("hung", 0) + tally.get("nocompile", 0),
                 "unmutable": tally.get("unmutable", 0),
                 "excluded": tally.get("excluded", 0)}
io.open(BASELINE, "w", encoding="utf-8", newline="\n").write(json.dumps(live, indent=1))
print("baseline written to %s" % BASELINE)
print("  to make it the battery's gate, copy it to CTRMap/mutation_baseline.json "
      "and commit - MutationBaselineTest reads it from there")
if regressed:
    raise SystemExit(1)
