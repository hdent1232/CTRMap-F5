#!/usr/bin/env python3
"""Refuse a citation this repository makes to something a checkout cannot open.

WHY THIS EXISTS, measured 2026-09-22. The queue's largest open item - the 280-finding whole-app
census - named its evidence as a file in the SESSION folder beside the repository. A clone gets
the repository and nothing else, so the work the queue described could not be started by anyone
who had not been sitting at this machine, and the entry naming it looked completely normal. The
owner caught that one by asking. Looking for its siblings found eight more citations of the
same shape, and three of them named a path that does not exist ANYWHERE:

  * TESTING.md told the reader to fix a red mutation ratchet by copying a file from
    _state over the repository's baseline - from a path a suite in this tree actively
    guarantees is absent, because the hand-copying of two baselines is the exact defect
    MutationBaselineTest was written to abolish. The documented remedy for the one red
    suite could not be carried out, and nothing said so.
  * The fan-out cap's refusal offered a cheaper alternative to spawning agents - re-run the
    mutation suite - and named it at a path with no file at it. A refusal that names a remedy
    nobody can run is a refusal with no way out, which is how a cap gets resented and raised.
  * The magnitude series' own module docstring said the series lives in the session folder,
    twenty lines above the assignment that puts it beside plants.json, and above the comment
    explaining that it was moved there precisely because the session folder does not travel
    with a clone.

None of the three was a lie and none was reachable by reading harder: each was true when
written, the thing moved, and prose does not move with it.

WHAT IT REFUSES. Every path this tree cites into the session folder must be DECLARED, with a
status and a reason, and the declaration is then checked against the disk:

  1. AN UNDECLARED CITATION. A tracked file names a session-folder path that the manifest does
     not carry. This is the one that would have caught the census: the answer is either to
     bring the thing into the repository, or to say in the manifest why it stays outside.

  2. A DECLARED `outside` PATH THAT IS NOT THERE. Evidence and measurement output legitimately
     live outside the repository. A citation of one that has since moved or been deleted is a
     dead reference, and it is checked rather than trusted.

  3. A DECLARED `forbidden` PATH THAT EXISTS. Some citations name a path precisely because
     nothing should be at it - MutationBaselineTest names the second baseline whose existence
     it refuses. For those the check runs the other way round, so the manifest cannot be used
     to launder a file back into existence.

  4. A MANIFEST ENTRY NOTHING CITES. The record may not outlive its subject, or it becomes a
     list of paths that were interesting once, which is how the census entry survived.

AND IT DOES NOT REPORT CLEAN WHEN IT COULD NOT LOOK. Refusals 2 and 3 are facts about a
directory that is NOT PART OF THIS REPOSITORY, so on a fresh checkout it is simply absent -
which is exactly the condition under which a check that collapses "not there" into "nothing
wrong" reports a clean negative. Where the session folder cannot be read, those two verdicts are
UNKNOWN and are printed as UNKNOWN; refusal 1, which is a fact about the tree itself, still
runs and still refuses. This project has paid for that distinction twice: a liveness probe that
called three running processes dead because it could not open them, and a shell filter that
reported twelve live workers gone and cost 134 verdicts.

THE CHEAPEST WAY TO SATISFY THIS is to declare every offending path `outside` with a reason like
"it lives outside", which buys a pass for the census entry that started this. What refuses that
is refusal 2: `outside` is a claim about the disk and is checked against it, so a declaration
only survives while the file is genuinely there to be found - and refusal 4 deletes the entry
the moment the citation goes. The second cheapest is to stop using backticks, since a citation
is recognised by being presented to a reader as a path. That one is not worth closing: a path
written as prose is not a citation, and the day someone strips the backticks to get past this is
the day the sentence stops telling anyone where to look, which is the defect and not an evasion
of it.
"""
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

LF = chr(10)
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

#: EVERY function here takes the tree it is judging. The first draft had `root` on the public
#: functions and a hard-coded constant inside the one that lists the files, so asking it about
#: a scratch tree enumerated THIS one and compared the answers against the wrong disk - and the
#: proof built on it would have passed while testing nothing. A parameter that some of the call
#: chain ignores is worse than no parameter.
def manifest_path(root=ROOT):
    return os.path.join(root, "tools", "guard", "citations.json")

#: The session folder: worktrees, sweep state and probe output, one level up from the
#: repository. `census.py` already skips it by this name when computing scope.
SESSION_DIR = "wt"

#: A CITATION IS A PATH PRESENTED TO A READER AS A PATH - inside backticks in Markdown and in
#: this project's comment style, or inside quotes in code. Prose that happens to contain a
#: slash is not a citation and is not refused; see the docstring on why that is deliberate.
CITED = re.compile(
    r"[`" + chr(34) + chr(39) + r"]"
    r"(" + SESSION_DIR + r"/[A-Za-z0-9_][A-Za-z0-9_./-]*)"
    r"[`" + chr(34) + chr(39) + r"]")

#: Parenthesised citations, which is how a refusal offers a remedy: "re-run the suite
#: (wt/_state/mutate.py)". Added because the fan-out cap's dead remedy was written this way and
#: the backtick pattern above walked straight past it - the first draft of this file reported
#: a confident eight citations while nine were there.
CITED_PAREN = re.compile(r"\((" + SESSION_DIR + r"/[A-Za-z0-9_][A-Za-z0-9_./-]*)\)")

STATUSES = ("outside", "forbidden")


def _text_files(root=ROOT):
    """Every tracked or about-to-be-tracked text file, or None when that cannot be read.

    None is not an empty list. A tree this cannot enumerate is a tree this cannot judge, and
    the caller says so rather than printing a clean report over nothing.
    """
    try:
        out = subprocess.check_output(
            ["git", "ls-files", "--cached", "--others", "--exclude-standard"],
            cwd=root, stderr=subprocess.STDOUT, timeout=60)
    except (OSError, subprocess.SubprocessError):
        return None
    names = []
    for rel in out.decode("utf-8", "replace").replace(chr(13), "").split(LF):
        if not rel or rel.endswith("/"):
            continue
        if os.path.splitext(rel)[1].lower() not in (
                ".md", ".py", ".java", ".ps1", ".txt", ".json", ".bat", ".sh", ".properties"):
            continue
        names.append(rel)
    return names


def _is_the_record(rel, root=ROOT):
    """The manifest and this checker are the RECORD, and a record inside its own subject
    refuses itself forever. Excluded by computing what they are, not by matching their names.
    """
    full = os.path.normcase(os.path.abspath(os.path.join(root, rel)))
    return full in (os.path.normcase(os.path.abspath(manifest_path(root))),
                    os.path.normcase(os.path.abspath(
                        os.path.join(root, "tools", "guard", "citations.py"))))


def citations(root=ROOT):
    """{cited path: sorted [files citing it]}, or None when the tree cannot be enumerated."""
    names = _text_files(root)
    if names is None:
        return None
    found = {}
    for rel in names:
        if _is_the_record(rel, root):
            continue
        try:
            text = io.open(os.path.join(root, rel), encoding="utf-8", errors="replace").read()
        except OSError:
            #: A file git lists and this cannot open is not a file with no citations in it.
            found.setdefault("<unreadable> " + rel, []).append(rel)
            continue
        for pattern in (CITED, CITED_PAREN):
            for path in pattern.findall(text):
                found.setdefault(path, [])
                if rel not in found[path]:
                    found[path].append(rel)
    return dict((k, sorted(v)) for k, v in found.items())


def manifest(root=ROOT):
    """The declarations, or None when the manifest cannot be read - never an empty manifest,
    because an empty one refuses every citation in the tree and reads like a tree full of
    defects rather than a checker that could not find its own record."""
    try:
        return json.load(io.open(manifest_path(root), encoding="utf-8"))
    except (OSError, ValueError):
        return None


def session_dir(root=ROOT):
    """Where the session folder is, or None when it is not readable from here.

    Not an error: a checkout legitimately has no session folder. It is the difference between
    "the file is not there" and "I could not look", and every caller of this must keep them
    apart.
    """
    path = os.path.join(os.path.dirname(root), SESSION_DIR)
    return path if os.path.isdir(path) else None


def judge(root=ROOT):
    """(problems, unknowns). Problems refuse; unknowns are printed and refuse nothing."""
    problems = []
    unknowns = []

    cited = citations(root)
    if cited is None:
        return (["cannot enumerate this tree - is it a git repository? (refusing to report "
                 "a clean citation check over a tree that could not be listed)"], [])
    declared = manifest(root)
    if declared is None:
        return (["cannot read the citation manifest at tools/guard/citations.json - refusing "
                 "to report clean, because an unreadable record is not an empty one"], [])

    for path in sorted(cited):
        if path.startswith("<unreadable> "):
            unknowns.append("could not open %s to look for citations in it" % path[13:])
            continue
        if path not in declared:
            problems.append(
                "UNDECLARED: %s is cited by %s and is not in the manifest. A clone gets the "
                "repository and nothing else, so either bring it in, or declare it with a "
                "status and the reason it stays outside."
                % (path, ", ".join(cited[path])))

    for path in sorted(declared):
        entry = declared[path]
        status = entry.get("status")
        why = (entry.get("why") or "").strip()
        if status not in STATUSES:
            problems.append("MANIFEST: %s has status %r, which is not one of %s"
                            % (path, status, ", ".join(STATUSES)))
            continue
        if len(why) < 20:
            problems.append("MANIFEST: %s is declared with no reason worth reading (%r). The "
                            "reason is the whole value of the record." % (path, why))
        if path not in cited:
            problems.append(
                "MANIFEST: nothing in this tree cites %s any more. Remove the entry - a record "
                "that outlives its subject becomes a list of paths that were interesting once, "
                "which is how the census entry survived for nine days." % path)

    #: A FACT ABOUT THE TREE, so it runs before the disk is consulted and still runs on a
    #: checkout where the session folder is absent.
    problems = _forbidden_citers(problems, cited, declared)

    folder = session_dir(root)
    if folder is None:
        if declared:
            unknowns.append(
                "the session folder (%s beside the repository) is not readable from here, so "
                "whether the %d declared path(s) are where they say they are is UNKNOWN - not "
                "clean. On a checkout this is normal and expected."
                % (SESSION_DIR, len(declared)))
        return (problems, unknowns)

    for path in sorted(declared):
        entry = declared[path]
        if entry.get("status") not in STATUSES:
            continue
        full = os.path.join(os.path.dirname(root), path)
        there = os.path.exists(full)
        if entry["status"] == "outside" and not there:
            problems.append(
                "DEAD CITATION: %s is declared as living outside the repository, the session "
                "folder IS readable, and there is nothing at that path. Cited by %s."
                % (path, ", ".join(cited.get(path, ["(nothing)"]))))
        if entry["status"] == "forbidden" and there:
            problems.append(
                "FORBIDDEN PATH IS BACK: %s is cited as a path that must not exist, and it "
                "exists. Cited by %s." % (path, ", ".join(cited.get(path, ["(nothing)"]))))

    return (problems, unknowns)


def _forbidden_citers(problems, cited, declared):
    """A `forbidden` path may be NAMED, but only by something asserting it is absent.

    FOUND BY BREAKING THIS FILE, 2026-09-22. `forbidden` was added so a suite could name the
    second mutation baseline in order to refuse it. But a status is a fact about the DISK, and
    "nothing is at this path" says nothing about why a file mentions it - so TESTING.md telling
    a reader to COPY A BASELINE FROM that path passed cleanly, which is the exact instruction
    this guard was written after. The declaration therefore carries who may name it: a suite
    asserting its absence and a comment recording that it moved are legitimate, and a NEW
    citer is the event worth refusing, because a path that must not exist has no honest use
    for a reader.
    """
    for path in sorted(declared):
        entry = declared[path]
        if entry.get("status") != "forbidden":
            continue
        allowed = entry.get("cited_by")
        if not isinstance(allowed, list) or not allowed:
            problems.append(
                "MANIFEST: %s is declared forbidden with no `cited_by`. Name the files that "
                "may mention a path which must not exist, so that a new one is refused." % path)
            continue
        for citer in cited.get(path, []):
            if citer.replace(os.sep, "/") not in allowed:
                problems.append(
                    "FORBIDDEN PATH NAMED BY SOMETHING NEW: %s cites %s, which is declared as "
                    "a path that must not exist. A suite asserting its absence may name it; an "
                    "instruction telling a reader to use it is the defect this guard exists "
                    "for." % (citer, path))
    return problems


def _scratch(tmp, cited, declared, on_disk, with_session_folder=True):
    """Build a throwaway tree and judge it. Returns (problems, unknowns).

    The tree is a real git repository, because the file list comes from git and a fixture that
    cannot be enumerated would make every refusal below pass for the wrong reason.
    """
    root = os.path.join(tmp, "repo")
    os.makedirs(os.path.join(root, "tools", "guard"))
    os.makedirs(os.path.join(root, "docs"))
    subprocess.check_output(["git", "init", "-q", root], stderr=subprocess.STDOUT, timeout=60)
    io.open(os.path.join(root, "docs", "note.md"), "w", encoding="utf-8").write(
        LF.join("see `%s` for the evidence" % c for c in cited) + LF)
    io.open(manifest_path(root), "w", encoding="utf-8").write(json.dumps(declared) + LF)
    if with_session_folder:
        os.makedirs(os.path.join(tmp, SESSION_DIR, "_state"))
        for rel in on_disk:
            full = os.path.join(tmp, rel)
            if not os.path.isdir(os.path.dirname(full)):
                os.makedirs(os.path.dirname(full))
            io.open(full, "w", encoding="utf-8").write("x" + LF)
    return judge(root)


def _case(name, got, want, ok):
    """`want` is a fragment that must appear in the verdicts, or None for 'no refusal'."""
    problems, unknowns = got
    if want is None:
        if problems:
            sys.stderr.write("SELFTEST FAILED [%s]: expected no refusal, got %r%s"
                             % (name, problems, LF))
            return False
        sys.stdout.write("    %-34s clean, as it should be%s" % (name, LF))
        return ok
    if not any(want in p for p in problems):
        sys.stderr.write("SELFTEST FAILED [%s]: no verdict contained %r; got %r%s"
                         % (name, want, problems, LF))
        return False
    sys.stdout.write("    %-34s refused%s" % (name, LF))
    return ok


def selftest():
    """Known-answer control, then the guard broken on purpose six ways.

    A scan that reports a confident zero is the dangerous result, and a checker whose refusals
    have never been seen to fire is exactly that. Every branch that can refuse is driven here
    against a tree built to be wrong in that one way - including the branch that must NOT
    refuse, because reporting a clean tree when the disk could not be read is the failure this
    project has paid for twice.
    """
    ok = True
    sample = ("see `" + SESSION_DIR + "/_state/thing.json` for it" + LF
              + "re-run the suite (" + SESSION_DIR + "/_state/mutate.py) instead" + LF
              + "and " + SESSION_DIR + "/_state/bare.json in prose, which is not a citation"
              + LF)
    hits = sorted(set(CITED.findall(sample)) | set(CITED_PAREN.findall(sample)))
    expected = sorted([SESSION_DIR + "/_state/thing.json", SESSION_DIR + "/_state/mutate.py"])
    if hits != expected:
        sys.stderr.write("SELFTEST FAILED: expected %r, found %r%s" % (expected, hits, LF))
        ok = False
    if CITED.findall("nothing here") or CITED_PAREN.findall("nothing here"):
        sys.stderr.write("SELFTEST FAILED: the pattern fires on text with no citation" + LF)
        ok = False
    if session_dir(os.path.join(ROOT, "no", "such", "tree")) is not None:
        sys.stderr.write("SELFTEST FAILED: an absent session folder did not report absent" + LF)
        ok = False

    there = SESSION_DIR + "/_state/there.json"
    gone = SESSION_DIR + "/_state/gone.json"
    reason = "a reason long enough to be worth reading by anybody"
    sys.stdout.write("  breaking it on purpose:" + LF)
    for name, kwargs, want in (
        ("a declared, present citation",
         dict(cited=[there], declared={there: {"status": "outside", "why": reason}},
              on_disk=[there]), None),
        ("an undeclared citation",
         dict(cited=[there], declared={}, on_disk=[there]), "UNDECLARED"),
        ("declared outside, not on disk",
         dict(cited=[gone], declared={gone: {"status": "outside", "why": reason}},
              on_disk=[]), "DEAD CITATION"),
        ("declared forbidden, on disk",
         dict(cited=[there], declared={there: {"status": "forbidden", "why": reason}},
              on_disk=[there]), "FORBIDDEN PATH IS BACK"),
        ("a manifest entry nothing cites",
         dict(cited=[there], declared={there: {"status": "outside", "why": reason},
                                       gone: {"status": "outside", "why": reason}},
              on_disk=[there, gone]), "nothing in this tree cites"),
        ("declared with no reason",
         dict(cited=[there], declared={there: {"status": "outside", "why": "because"}},
              on_disk=[there]), "no reason worth reading"),
        #: THE HOLE THIS FILE SHIPPED WITH, found by breaking it against the real tree: a
        #: `forbidden` path was absent from disk and declared, so an instruction telling a
        #: reader to COPY A BASELINE FROM IT passed cleanly - the exact sentence the guard was
        #: written after.
        ("a forbidden path named by a new file",
         dict(cited=[gone], declared={gone: {"status": "forbidden", "why": reason,
                                             "cited_by": ["src/some/Suite.java"]}},
              on_disk=[]), "NAMED BY SOMETHING NEW"),
        ("a forbidden path named by its declared citer",
         dict(cited=[gone], declared={gone: {"status": "forbidden", "why": reason,
                                             "cited_by": ["docs/note.md"]}},
              on_disk=[]), None),
        ("forbidden, with nobody allowed to cite it",
         dict(cited=[gone], declared={gone: {"status": "forbidden", "why": reason}},
              on_disk=[]), "no `cited_by`"),
    ):
        tmp = tempfile.mkdtemp(prefix="citations-")
        try:
            ok = _case(name, _scratch(tmp, **kwargs), want, ok)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    #: THE ONE THAT MUST NOT REFUSE. Same tree as "declared outside, not on disk" - which is a
    #: refusal above - with the session folder absent entirely. The file is missing in both, and
    #: only one of them is a defect; a checker that cannot tell them apart reports a clean
    #: negative over a disk it never read.
    tmp = tempfile.mkdtemp(prefix="citations-")
    try:
        problems, unknowns = _scratch(
            tmp, cited=[gone], declared={gone: {"status": "outside", "why": reason}},
            on_disk=[], with_session_folder=False)
        if problems:
            sys.stderr.write("SELFTEST FAILED [cannot look]: refused a tree it could not read: "
                             "%r%s" % (problems, LF))
            ok = False
        elif not any("UNKNOWN" in u or "not readable" in u for u in unknowns):
            sys.stderr.write("SELFTEST FAILED [cannot look]: reported clean instead of UNKNOWN"
                             + LF)
            ok = False
        else:
            sys.stdout.write("    %-34s UNKNOWN, not clean%s"
                             % ("no session folder at all", LF))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    #: "ALL PASS" is what the battery reads a suite's verdict from; census.py answers the same
    #: way and the runner treats anything else as red.
    sys.stdout.write(("citations.py selftest: %s" % ("ALL PASS" if ok else "FAILED")) + LF)
    return 0 if ok else 1


def main(argv):
    if "--selftest" in argv:
        return selftest()
    problems, unknowns = judge()
    for u in unknowns:
        sys.stdout.write("UNKNOWN: " + u + LF)
    if problems:
        sys.stderr.write("REFUSING THESE CITATIONS:" + LF)
        for p in problems:
            sys.stderr.write("  - " + p + LF)
        return 1
    sys.stdout.write("every session-folder citation in this tree is declared and checks out"
                     + LF)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
