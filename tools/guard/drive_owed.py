#!/usr/bin/env python3
"""Drives what a commit made past the gate left owed, and clears only what reddened.

`tools/guard/gate_owed.json` names every plant the commit gate would have driven for a commit made
with --no-verify - the project's own (driven by `tools/guard/replant.py`, the project's declared
prove command) and the bundle's (`tests/plants.json`, driven by `tools/prove_plants.py`) - and the
files whose mutation scores it left stale. `commit_guard.check_gate_owed` refuses the next commit
until each plant was driven red against the very tree being committed. This is the one way to
clear an entry: through the gate's OWN doors, one plant at a time, recording the tree it reddened
against. A plant that did not redden stays owed with what it said; one the run never reached stays
owed as it was.

THE TREE MUST HOLD STILL. The digest is taken before the first plant and after the last, the record
itself left out; if anything else moved in between, the verdicts describe no single tree and
nothing is written. Never edit source while this runs.

    python -B tools/guard/drive_owed.py              every owed plant, one at a time
    python -B tools/guard/drive_owed.py --workers 4  every owed plant, four at a time in copies of this tree
    python -B tools/guard/drive_owed.py --only ID..  these plants only
    python -B tools/guard/drive_owed.py --keys-from F  the plants named one per line in F
    python -B tools/guard/drive_owed.py --list       what is owed and what game data each needs

FOUR AT A TIME, IN COPIES. A plant writes its defect into the tree and runs a suite over it, so two
cannot share one tree; measured 2026-10-11, 1,558 owed plants one at a time were days of work. Each
worker gets its own clone holding exactly this tree's index - `git write-tree`, read back into the
clone - with this tree's build beside it, and drives its share there; a verdict is taken back only
when the worker's tree digested to the same subject as this one, so a copy that was not this tree
proves nothing about it.

Exit 0 when nothing is owed afterwards, 1 when something still is, 2 when it refused.
"""
import base64
import importlib.util
import io
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
ROOT = _tree_root.require(__file__)
LF = chr(10)


def _load(name, path):
    """A module from its FILE, never by name: a name is answered by whichever copy loaded first (F184)."""
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


#: What a driver answers, in place of red, for a plant it SETTLED: retired in the bundle's ledger, and its test ran to
#: a pass with the defect planted - so the defect cannot happen, and nothing can redden.
SETTLED = "settled"

#: How a plant a driver could not ask is said and recorded: owed still, and not as one that stayed green.
UNASKED = "COULD NOT BE ASKED - "

_RULE = []


def _rule():
    """The gate's own rule file, loaded once from its FILE (F184): what GONE and a run to a pass are called there."""
    if not _RULE:
        _RULE.append(_load("commit_guard_of_drive_owed_rule", os.path.join(HERE, "commit_guard.py")))
    return _RULE[0]


_PATHS = []


def _masked(text):
    """`text` with every name of this machine masked, as tools/owner_paths.py masks a finding - a suite's crash
    names files by their full path, and the record this is written to is tracked (2026-10-24: a home directory in
    not_red, refused by the owner_paths rule the moment after the drive wrote it)."""
    if not _PATHS:
        _PATHS.append(_load("owner_paths_of_drive_owed", os.path.join(ROOT, "tools", "owner_paths.py")).identifiers())
    for kind, pattern in _PATHS[0]:
        text = pattern.sub("<%s>" % kind, text)
    return text


def settle(record, results, subject):
    """The record after a drive. A plant `results` says reddened moves to `driven` with the tree it
    reddened against; one that did not stays owed, what it said kept in `not_red`; one the drive
    never reached stays owed as it was."""
    out = dict(record)
    driven = dict(record.get("driven") or {})
    not_red = dict(record.get("not_red") or {})

    def still_owed(key, plant=None):
        result = results.get(key)
        if result is None:
            return True
        red, detail = result
        if red in (_rule().GONE, SETTLED):
            #SETTLED BY A PROOF about this tree, with how: GONE is asked again at the commit, a run to a pass is not
            driven[key] = dict({"settled": _masked(str(detail))[:300], "subject": subject,
                                "how": _rule().GONE if red == _rule().GONE else _rule().RAN_TO_A_PASS},
                               **({"plant": plant} if plant is not None else {"bundle": True}))
            not_red.pop(key, None)
            return False
        if red:
            #WHICH PLANT IT WAS, kept with the verdict: a verdict about a tree that has since changed is owed
            #again (reowed), and it can only be owed again as the plant it was
            driven[key] = dict({"red": True, "subject": subject},
                               **({"plant": plant} if plant is not None else {"bundle": True}))
            not_red.pop(key, None)
            return False
        not_red[key] = _masked(str(detail))[:300]
        return True
    out["plants"] = [p for p in record.get("plants") or [] if still_owed(p.get("id"), p)]
    out["bundle_plants"] = [k for k in record.get("bundle_plants") or [] if still_owed(k)]
    out["driven"] = driven
    out["not_red"] = not_red
    return out


def reowed(record, subject):
    """(record, how many were owed again, [keys that cannot be]). A red driven against another tree than
    `subject` says nothing about this one: it goes back to what is owed, as the plant it was. A run resumed
    after the tree changed carried such reds forward, out of what it would drive and into what the gate then
    refused (2026-10-11). One recorded without its plant cannot be owed again, and is named."""
    driven = dict(record.get("driven") or {})
    stale = [k for k, d in driven.items() if d.get("subject") != subject]
    if not stale:
        return record, 0, []
    out = dict(record)
    plants = list(out.get("plants") or [])
    bundle = list(out.get("bundle_plants") or [])
    lost = []
    for key in stale:
        said = driven.pop(key)
        if "plant" in said:
            plants.append(said["plant"])
        elif said.get("bundle"):
            bundle.append(key)
        else:
            lost.append(key)
            driven[key] = said
    out.update(plants=plants, bundle_plants=bundle, driven=driven)
    return out, len(stale) - len(lost), lost


def _value_list(argv, flag):
    at = argv.index(flag)
    return [a for a in argv[at + 1:] if not a.startswith("--")]


def ledger_refusals():
    """What the project's runner refuses about its WHOLE ledger before it drives any plant - one
    rotted anchor makes `replant.py <id>` refuse every id, and each refusal would read here as a
    plant that did not redden. Measured 2026-10-08: an edit that split one line of commit_guard.py
    rotted one plant, and the first drive answered NOT RED for every one of 1582 plants it reached."""
    replant = _load("replant_of_drive_owed", os.path.join(HERE, "replant.py"))
    return replant.check_shape(replant.ledger())


def run_output():
    """The files a suite writes as its own output while it runs - the ratchet readings - DERIVED from the
    recorder that owns them, as record_run derives them, never typed here. Every reading taken during a
    drive was taken with a defect planted, so none of it is a measurement of the tree: each is put back.
    Measured 2026-10-08: the first full drive moved magnitudes.json, and would have discarded every
    verdict at the end as a tree that moved."""
    magnitude = _load("magnitude_of_drive_owed", os.path.join(HERE, "magnitude.py"))
    return [magnitude.SERIES]


#: THE READINGS A DRIVE HOLDS, journalled before it plants (2026-10-25). A stop from outside that cannot be caught
#: runs no `finally` - on Windows every such stop is one (TerminateProcess: Stop-Process, a task's stop), elsewhere
#: SIGKILL - and the SIGTERM handler in main is the POSIX half alone: the commit gate found its test skipped on win32,
#: so on the one platform this project runs on, a drive stopped from outside left its planted readings in. The next
#: drive asks this before it takes any reading of the tree (put_back_left). A dotfile at the root, where the records
#: live, so it is no tree's subject, by shape.
HELD = os.path.join(ROOT, ".drive-owed-held")

_SHELL = []


def _shell():
    """bundle_shell - the bundle's one probe of whether a process is the one that wrote a lock - from its FILE."""
    if not _SHELL:
        folder = _load("bundle_hooks_of_drive_owed", os.path.join(ROOT, "tools", "bundle_hooks.py")).path(ROOT)
        if folder not in sys.path:
            sys.path.insert(0, folder)
        _SHELL.append(_load("bundle_shell_of_drive_owed", os.path.join(folder, "bundle_shell.py")))
    return _SHELL[0]


def _hold(journal, held):
    """Journal `held` ({path: bytes}) under this process's name before a defect is planted: see HELD."""
    _write_record(journal, {"pid": os.getpid(), "started_epoch": _shell().process_started_at(os.getpid()),
                            "files": {path: base64.b64encode(body).decode("ascii") for path, body in held.items()}})


def put_back_left(journal):
    """(None, what was put back or None), or (why this refuses, None). A journal whose drive is gone is put back
    file by file, then removed. One whose drive may still run - ALIVE whenever that cannot be told, as
    bundle_shell.alive answers - is that drive's own to put back; one that cannot be read is UNKNOWN, never
    absent."""
    if not journal or not os.path.exists(journal):
        return None, None
    try:
        with io.open(journal, encoding="utf-8") as handle:
            left = json.load(handle)
        files = {path: base64.b64decode(body) for path, body in left["files"].items()}
        holder = (left.get("pid"), left.get("started_epoch"))
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as exc:
        return ("%s cannot be read (%s), so whether a drive killed from outside left planted readings in this tree"
                " is UNKNOWN - put its files back by hand, then delete it" % (journal, exc)), None
    if _shell().alive(*holder):
        return ("%s names a drive that may still be running (pid %s): the readings it planted are its own to put"
                " back - stop it, or delete the journal once it is gone" % (journal, holder[0])), None
    for path, body in files.items():
        with io.open(path, "wb") as handle:
            handle.write(body)
    os.remove(journal)
    return None, ("put back %d reading(s) a drive killed from outside left planted (pid %s, gone): %s"
                  % (len(files), holder[0], ", ".join(sorted(files))))


#: A RUN OF HOURS RECORDS AS IT GOES. The record was written once, at the end: on 2026-10-11 the app
#: hosting the session quit 154 plants into a 1,578-plant drive, and fifty minutes of verdicts went
#: with it. Every CHECKPOINT plants the planted readings are put back, the tree is asked whether it is
#: still the one the drive began on, and what has reddened is written - so a drive killed anywhere is
#: run again from where it stopped, because what it drove is no longer owed.
CHECKPOINT = 20


def _copy_of(tree, where, root=ROOT):
    """None when `where` is now a clone holding exactly tree `tree` with `root`'s build beside it, else why not."""
    import shutil
    import bounded
    for args in (["git", "clone", "-q", "--shared", "--no-checkout", root, where],
                 ["git", "-C", where, "read-tree", "--reset", "-u", tree]):
        done = bounded.run(args, capture_output=True, text=True, errors="replace", timeout=1800,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        if done.returncode != 0:
            return "%s: %s" % (" ".join(args[:4]), (done.stderr or done.stdout).strip()[:300])
    build = os.path.join(root, "build")
    if os.path.isdir(build):
        shutil.copytree(build, os.path.join(where, "build"), ignore=shutil.ignore_patterns(".battery-running"))
    return None


def copy_problem(where, subject, guard, root=ROOT):
    """None when the copy at `where` digests to `subject`, this tree's; else why not, naming what this tree holds that
    git's index - which every copy is made from - does not.

    ASKED BEFORE A PLANT IS DRIVEN, NOT AFTER EVERY ONE IS (2026-10-24). A worker's verdict was compared with this tree
    only when it was taken back: an untracked file here that no ignore rule named (.claude/settings.local.json) was part
    of this tree's subject and of no copy, and 1,569 verdicts were refused as another tree's two hours later."""
    import bounded
    theirs = guard.source_digest(root=where, exclude=(guard.GATE_OWED,))
    if theirs == subject:
        return None
    differ = set()
    for args in (["git", "-C", root, "ls-files", "--others", "--exclude-standard"], ["git", "-C", root, "diff", "--name-only"]):
        done = bounded.run(args, capture_output=True, text=True, errors="replace", timeout=600,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        differ.update(done.stdout.split())
    return ("its copy digests to %s and this tree to %s, so every verdict it drove would be refused as another tree's -"
            " this tree holds what git's index does not: %s" % (theirs, subject, ", ".join(sorted(differ)[:10]) or
                                                                 "nothing git names (an ancestor's CLAUDE.md?)"))


def _worker_root(index, root=ROOT):
    """A new, empty folder for worker `index`'s copy, made BESIDE `root` - in the same parent, so with the same ancestors.

    A TREE'S SUBJECT READS THE RULES ABOVE IT (require_build.subject_digest: every ancestor's CLAUDE.md, by its
    distance), so a copy anywhere else is another subject however exact its files. MEASURED 2026-10-18: the copies were
    made in the temp folder, this tree's parent holds a CLAUDE.md the temp folder does not, and all 1,578 verdicts of an
    eight-worker drive came back "on another tree" and were discarded - two hours. That digest with the ancestors
    switched off was the workers' exactly."""
    import tempfile
    return tempfile.mkdtemp(prefix="_drive_owed_w%d_" % index, dir=os.path.dirname(os.path.abspath(root)))


def drive_in_copies(work, workers, record_path_in, subject, guard, log=print):
    """{key: (red, detail)} for `work` driven `workers` at a time, each worker in its own copy of this tree's
    index, plus [what could not be done]. A worker's verdicts are taken only when its tree digested to
    `subject`."""
    import shutil
    import tempfile
    import bounded
    from concurrent.futures import ThreadPoolExecutor
    done = bounded.run(["git", "-C", ROOT, "write-tree"], capture_output=True, text=True, errors="replace", timeout=600,
                       creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    tree = (done.stdout or "").strip()
    if done.returncode != 0 or not tree:
        return {}, ["this tree's index cannot be written as a tree (%s)" % (done.stderr or "").strip()[:200]]
    shares = [[k for i, (k, _d) in enumerate(work) if i % workers == w] for w in range(workers)]
    shares = [s for s in shares if s]
    base = tempfile.mkdtemp(prefix="drive_owed_workers_")   # the keys and the logs; each copy is made beside ROOT
    copies = []
    env = dict(os.environ)
    env.setdefault("CTRMAP_PRISTINE", os.path.join(os.path.dirname(ROOT), "RomFS_original_garcs"))
    env.setdefault("CTRMAP_GAMEDIR", os.path.join(os.path.dirname(ROOT), "RomFS", "000400000011C400"))

    def one(index):
        where = _worker_root(index)
        copies.append(where)
        why = _copy_of(tree, where) or copy_problem(where, subject, guard)
        if why:
            return index, where, "worker %d: %s" % (index, why)
        keys = os.path.join(base, "w%d.keys" % index)
        with io.open(keys, "w", encoding="utf-8", newline=LF) as handle:
            handle.write(LF.join(shares[index]) + LF)
        with io.open(os.path.join(base, "w%d.log" % index), "w", encoding="utf-8") as out:
            #NO CONSOLE OF ITS OWN (2026-10-12): everything it says goes to its log, so a window is all a console adds
            ran = bounded.run([sys.executable, "-B", os.path.join(where, "tools", "guard", "drive_owed.py"),
                               "--keys-from", keys], cwd=where, env=env, stdout=out, stderr=subprocess_stdout(),
                              timeout=48 * 3600, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        return index, where, None if ran.returncode in (0, 1) else "worker %d exited %d" % (index, ran.returncode)

    results, trouble = {}, []
    try:
        with ThreadPoolExecutor(max_workers=len(shares)) as pool:
            finished = list(pool.map(one, range(len(shares))))
        for index, where, why in finished:
            if why:
                trouble.append(why)
            try:
                with io.open(os.path.join(where, guard.GATE_OWED), encoding="utf-8") as handle:
                    theirs = json.load(handle)
            except (OSError, ValueError) as exc:
                trouble.append("worker %d left no record that can be read (%s)" % (index, exc))
                continue
            take_back(index, shares[index], theirs, subject, results, trouble)
        log("workers drove %d: %d red; %s" % (len(results), sum(1 for r, _ in results.values() if r),
                                             "; ".join(trouble) or "nothing went wrong"))
    finally:
        for where in [base] + copies:
            shutil.rmtree(where, onerror=_writable)
    return results, trouble


def take_back(index, share, theirs, subject, results, trouble):
    """Into `results`, worker `index`'s verdicts on the keys of its `share`, from its record `theirs` - a red
    only when it was driven on a tree that digested to `subject`, this one. A red about another tree is
    named in `trouble` and not taken: a copy that was not this tree proves nothing about it."""
    for key, said in (theirs.get("driven") or {}).items():
        if key in share:
            if said.get("subject") == subject:
                results[key] = ((True, "red") if not said.get("settled") else
                                (_rule().GONE if said.get("how") == _rule().GONE else SETTLED, said["settled"]))
            else:
                trouble.append("worker %d drove %s on another tree (%s)" % (index, key, said.get("subject")))
    for key, said in (theirs.get("not_red") or {}).items():
        if key in share and key not in results:
            results[key] = (False, said)


def subprocess_stdout():
    import subprocess
    return subprocess.STDOUT


def _writable(function, path, *_why):
    """git writes its objects read-only and Windows will not delete a read-only file."""
    import stat
    os.chmod(path, stat.S_IWRITE | stat.S_IREAD)
    function(path)


def _write_record(path, settled):
    tmp = path + ".writing"
    with io.open(tmp, "w", encoding="utf-8", newline=LF) as handle:
        handle.write(json.dumps(settled, indent=1, ensure_ascii=False) + LF)
    os.replace(tmp, path)


def main(argv, drive_own=None, drive_bundle=None, digest=None, stale=None, record_path=None, shape=None,
         written=None, retired=None, checkpoint=CHECKPOINT, proven=None, held_at=None, prove=None):
    #a word this does not read refuses the run, before anything runs (cli_words, 2026-10-10)
    import _cli
    #EVERY FLAG main READS IS ONE IT ACCEPTS (2026-10-12): --workers and --keys-from were read below and refused
    #here, so both were dead from the day they were written - `--workers 4`, the usage line's own example, ran nothing
    unread_words = _cli.refused(argv, flags=("--list", "--only", "--workers", "--keys-from"),
                                valued=("--workers", "--keys-from"), rest=("--only",), most=0)
    if unread_words:
        print(unread_words)
        return 2
    guard = _load("commit_guard_of_drive_owed", os.path.join(HERE, "commit_guard.py"))
    path = record_path or os.path.join(ROOT, guard.GATE_OWED)
    try:
        with io.open(path, encoding="utf-8") as handle:
            record = json.load(handle)
    except (OSError, ValueError) as exc:
        sys.stderr.write("REFUSED: %s cannot be read (%s) - an unread record is not an empty one%s"
                         % (path, exc, LF))
        return 2
    #A PLANT THE PARENT HAD RETIRED IS NOT OWED, and nothing can drive it: asked of the gate's own rule
    #(commit_guard.retired_at_parent - HEAD's ledger through git), dropped from what is owed and named
    try:
        gone = guard.retired_at_parent() if retired is None else set(retired)
    except guard.CannotAsk as exc:
        sys.stderr.write("REFUSED: which plants the parent retired cannot be read (%s)%s" % (exc, LF))
        return 2
    dropped = sorted(p.get("id") for p in record.get("plants") or [] if p.get("id") in gone)
    if dropped:
        record = dict(record)
        record["plants"] = [p for p in record.get("plants") or [] if p.get("id") not in gone]
        record["retired_at_parent"] = sorted(set(record.get("retired_at_parent") or []) | set(dropped))
        print("not owed - the parent's ledger retired them: %s" % ", ".join(dropped))
    if "--list" in argv:
        for p in record.get("plants") or []:
            print("  %-60s %-45s needs %s" % (p.get("id"), p.get("suite"), "+".join(p.get("needs") or []) or "nothing"))
        for k in record.get("bundle_plants") or []:
            print("  %s" % k)
        print("owed: %d project plant(s), %d bundle plant(s), %d mutation file(s)" % (
            len(record.get("plants") or []), len(record.get("bundle_plants") or []),
            len(record.get("mutation_sweep_owed") or [])))
        return 0 if not guard.owed_in(record) else 1
    only = set(_value_list(argv, "--only")) if "--only" in argv else None
    if "--keys-from" in argv:
        at = argv.index("--keys-from") + 1
        try:
            with io.open(argv[at], encoding="utf-8") as handle:
                only = set(line.strip() for line in handle if line.strip())
        except (IndexError, OSError) as exc:
            sys.stderr.write("REFUSED: --keys-from names no file that can be read (%s)%s" % (exc, LF))
            return 2
    workers = 1
    if "--workers" in argv:
        try:
            workers = max(1, int(argv[argv.index("--workers") + 1]))
        except (IndexError, ValueError):
            sys.stderr.write("REFUSED: --workers takes a number%s" % LF)
            return 2
    if digest is None:
        def digest():
            return guard.source_digest(exclude=(guard.GATE_OWED,))
    if proven is None and prove is None and (drive_own is None or drive_bundle is None):
        #PROVEN UNDRIVABLE in the tree being driven - git's index, the tree the workers copy and the commit records -
        #asked of the gate's own rule, so the drive and the commit agree on what a proof is
        def prove(plants, keys):
            return guard.proven_undrivable(plants, keys, guard.gate().index_reader())
    if drive_own is None or drive_bundle is None:
        gate = guard.gate()
        own = gate.project_ledger()
        prove_plants = _load("prove_plants_of_drive_owed", os.path.join(ROOT, "tools", "prove_plants.py"))
        with io.open(os.path.join(ROOT, gate.LEDGER), encoding="utf-8") as handle:
            held = json.load(handle)
        bundle, bundle_retired = held["plants"], held.get("retired") or {}
        if drive_own is None:
            if not own:
                sys.stderr.write("REFUSED: the project declares no prove command (_tests.ledger), so its"
                                 " plants cannot be driven%s" % LF)
                return 2

            def drive_own(key):
                return gate._prove_in_project(own[2], key)
        if drive_bundle is None:
            def drive_bundle(key):
                if key in bundle_retired and key not in bundle:
                    #RETIRED IN THE BUNDLE: settled only when its test runs to a pass with the defect planted - the
                    #bundle gate's own question (commit_gate.retirements_hold), asked of this tree
                    red, detail = prove_plants.drive_plant(key, bundle_retired[key])
                    if prove_plants.verdict_label(red, detail) == "skip":
                        return False, "retired, and its test skipped here: %s" % detail
                    if red:
                        return False, "retired, and it still reddens its test - a working guard, not a retired one"
                    if str(detail).strip() != "OK":
                        return False, "retired, and its test did not run to a pass: %s" % str(detail).strip()[:160]
                    return SETTLED, "retired (%s): its test ran to a pass with the defect planted" % str(
                        (bundle_retired[key].get("retired") or {}).get("why") or "")[:80]
                if key not in bundle:
                    return False, "not in %s" % gate.LEDGER
                return prove_plants.drive_plant(key, bundle[key])
    rotted = (shape or ledger_refusals)()
    if rotted:
        sys.stderr.write("REFUSED: the project's runner refuses its whole ledger, so no plant could be driven"
                         " and every answer would read as not red - re-anchor these first:%s%s%s"
                         % (LF, LF.join("    " + r for r in rotted[:10]), LF))
        return 2
    outputs = run_output() if written is None else written
    #WHAT A DRIVE KILLED FROM OUTSIDE LEFT PLANTED is put back before this one takes any reading of the tree
    journal = held_at or (HELD if written is None else None)
    why, said = put_back_left(journal)
    if why:
        sys.stderr.write("REFUSED: %s%s" % (why, LF))
        return 2
    if said:
        print(said)
    subject = digest()
    if subject is None:
        sys.stderr.write("REFUSED: this tree cannot be digested, so no verdict could say which tree it is about%s" % LF)
        return 2
    record, again, lost = reowed(record, subject)
    if lost:
        sys.stderr.write("REFUSED: %d verdict(s) were recorded against another tree and do not say which plant they"
                         " were, so they cannot be owed again - restore the record from the commit (git checkout"
                         " -- %s) and drive again: %s%s" % (len(lost), guard.GATE_OWED, ", ".join(sorted(lost)[:5]), LF))
        return 2
    if again:
        print("owed again - driven against another tree: %d" % again)
    #ASKED OF WHAT IS OWED NOW, after the verdicts about another tree are owed again (2026-10-25): asked before them, a
    #plant settled by proof against the last tree came back owed, was DRIVEN, and could not redden - 4 of 1,576 NOT RED
    if proven is None and prove is not None:
        try:
            proven = prove(record.get("plants") or [], list(record.get("bundle_plants") or []))
        except (OSError, guard.CannotAsk) as exc:
            sys.stderr.write("REFUSED: what is staged cannot be read to prove a plant undrivable (%s)%s" % (exc, LF))
            return 2
    proven = proven or {}
    work = [(p.get("id"), drive_own) for p in record.get("plants") or []]
    work += [(k, drive_bundle) for k in record.get("bundle_plants") or []]
    if only is not None:
        work = [(k, d) for k, d in work if k in only]
    #A PLANT PROVEN UNDRIVABLE HERE IS SETTLED, NOT DRIVEN: planted, its anchor would match nothing, or its test is gone
    results = {k: (_rule().GONE, proven[k]) for k, _d in work if k in proven}
    for key in sorted(results):
        print("  settled, not driven: %s - %s" % (key, results[key][1]))
    work = [(k, d) for k, d in work if k not in proven]
    if workers > 1 and len(work) > 1:
        taken, trouble = drive_in_copies(work, workers, path, subject, guard)
        results.update(taken)
        for why in trouble:
            sys.stderr.write("  %s%s" % (why, LF))
        after = digest()
        if after != subject:
            sys.stderr.write("REFUSED: this tree moved while the workers drove copies of it (%s, then %s), so their"
                             " verdicts describe no tree being committed and nothing was written%s"
                             % (subject, after, LF))
            return 2
        work = []
    #TERMINATED IS FINISHED TOO: SIGTERM ends Python without running `finally`, so a drive stopped from
    #outside left its planted readings in magnitudes.json (2026-10-08). As SystemExit it unwinds. A stop nothing
    #can catch is the journal's (HELD).
    try:
        import signal
        signal.signal(signal.SIGTERM, lambda signum, frame: sys.exit(128 + signum))
    except (ImportError, ValueError):
        pass   #not the main thread, or no such signal here: a kill cannot be caught, nor anything said about it
    held = {}
    for out in outputs:
        with io.open(out, "rb") as handle:
            held[out] = handle.read()
    if journal and held:
        _hold(journal, held)
    try:
        for n, (key, driver) in enumerate(work, 1):
            try:
                red, detail = driver(key)
            except Exception as exc:                     # noqa: BLE001 - a broken plant is a NO
                red, detail = False, "%s: %s" % (type(exc).__name__, exc)
            if red is None:
                #COULD NOT BE ASKED IS NOT A PLANT THAT STAYED GREEN (2026-10-25): a driver's UNKNOWN - a plant it could
                #not lay, a suite that never finished - printed NOT RED and read as a plant to re-anchor. It stays
                #owed all the same, and says what it is.
                detail = UNASKED + str(detail)
            results[key] = (SETTLED if red == SETTLED else bool(red), detail)
            print("  [%d/%d] %s: %s" % (n, len(work), key, "settled - " + str(detail)[:160] if red == SETTLED
                                        else "red" if red else ("" if red is None else "NOT RED - ")
                                        + " ".join(str(detail).split())[:160]))
            sys.stdout.flush()
            if checkpoint and n % checkpoint == 0 and n < len(work):
                for out, body in held.items():
                    with io.open(out, "wb") as handle:
                        handle.write(body)
                now = digest()
                if now != subject:
                    sys.stderr.write("REFUSED: the tree moved while the plants were driven (%s, then %s at"
                                     " plant %d), so the verdicts describe no single tree and nothing more"
                                     " was written%s" % (subject, now, n, LF))
                    return 2
                kept = settle(record, results, subject)
                kept["mutation_sweep_owed"] = list(record.get("mutation_sweep_owed") or [])
                _write_record(path, kept)
    finally:
        for out, body in held.items():
            with io.open(out, "wb") as handle:
                handle.write(body)
        if journal and held and os.path.exists(journal):
            os.remove(journal)      #the readings are back: a journal already gone has nothing left to keep
    after = digest()
    if after != subject:
        sys.stderr.write("REFUSED: the tree moved while the plants were driven (%s, then %s), so the verdicts"
                         " describe no single tree and nothing was written%s" % (subject, after, LF))
        return 2
    settled = settle(record, results, subject)
    owed_files = list(record.get("mutation_sweep_owed") or [])
    try:
        settled["mutation_sweep_owed"] = sorted((stale or guard.stale_scores)(owed_files)) if owed_files else []
    except guard.CannotAsk as exc:
        #whether they were re-swept is UNKNOWN, and unknown is still owed - never cleared
        settled["mutation_sweep_owed"] = owed_files
        sys.stderr.write("the mutation files stay owed: %s%s" % (exc, LF))
    _write_record(path, settled)
    reds = sum(1 for red, _ in results.values() if red)
    print("drove %d: %d red, %d not; still owed: %d project plant(s), %d bundle plant(s), %d mutation file(s)" % (
        len(results), reds, len(results) - reds, len(settled["plants"]), len(settled["bundle_plants"]),
        len(settled["mutation_sweep_owed"])))
    return 0 if not guard.owed_in(settled) else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
