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
        if red:
            #WHICH PLANT IT WAS, kept with the verdict: a verdict about a tree that has since changed is owed
            #again (reowed), and it can only be owed again as the plant it was
            driven[key] = dict({"red": True, "subject": subject},
                               **({"plant": plant} if plant is not None else {"bundle": True}))
            not_red.pop(key, None)
            return False
        not_red[key] = str(detail)[:300]
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
        why = _copy_of(tree, where)
        if why:
            return index, where, why
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
                results[key] = (True, "red")
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
         written=None, retired=None, checkpoint=CHECKPOINT):
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
    if drive_own is None or drive_bundle is None:
        gate = guard.gate()
        own = gate.project_ledger()
        prove_plants = _load("prove_plants_of_drive_owed", os.path.join(ROOT, "tools", "prove_plants.py"))
        with io.open(os.path.join(ROOT, gate.LEDGER), encoding="utf-8") as handle:
            bundle = json.load(handle)["plants"]
        if drive_own is None:
            if not own:
                sys.stderr.write("REFUSED: the project declares no prove command (_tests.ledger), so its"
                                 " plants cannot be driven%s" % LF)
                return 2

            def drive_own(key):
                return gate._prove_in_project(own[2], key)
        if drive_bundle is None:
            def drive_bundle(key):
                if key not in bundle:
                    return False, "not in %s" % gate.LEDGER
                return prove_plants.drive_plant(key, bundle[key])
    rotted = (shape or ledger_refusals)()
    if rotted:
        sys.stderr.write("REFUSED: the project's runner refuses its whole ledger, so no plant could be driven"
                         " and every answer would read as not red - re-anchor these first:%s%s%s"
                         % (LF, LF.join("    " + r for r in rotted[:10]), LF))
        return 2
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
    work = [(p.get("id"), drive_own) for p in record.get("plants") or []]
    work += [(k, drive_bundle) for k in record.get("bundle_plants") or []]
    if only is not None:
        work = [(k, d) for k, d in work if k in only]
    results = {}
    if workers > 1 and len(work) > 1:
        results, trouble = drive_in_copies(work, workers, path, subject, guard)
        for why in trouble:
            sys.stderr.write("  %s%s" % (why, LF))
        after = digest()
        if after != subject:
            sys.stderr.write("REFUSED: this tree moved while the workers drove copies of it (%s, then %s), so their"
                             " verdicts describe no tree being committed and nothing was written%s"
                             % (subject, after, LF))
            return 2
        work = []
    outputs = run_output() if written is None else written
    #TERMINATED IS FINISHED TOO: SIGTERM ends Python without running `finally`, so a drive stopped from
    #outside left its planted readings in magnitudes.json (2026-10-08). As SystemExit it unwinds.
    try:
        import signal
        signal.signal(signal.SIGTERM, lambda signum, frame: sys.exit(128 + signum))
    except (ImportError, ValueError):
        pass   #not the main thread, or no such signal here: a kill cannot be caught, nor anything said about it
    held = {}
    for out in outputs:
        with io.open(out, "rb") as handle:
            held[out] = handle.read()
    try:
        for n, (key, driver) in enumerate(work, 1):
            try:
                red, detail = driver(key)
            except Exception as exc:                     # noqa: BLE001 - a broken plant is a NO
                red, detail = False, "%s: %s" % (type(exc).__name__, exc)
            results[key] = (bool(red), detail)
            print("  [%d/%d] %s: %s" % (n, len(work), key, "red" if red else "NOT RED - " + " ".join(str(detail).split())[:160]))
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
