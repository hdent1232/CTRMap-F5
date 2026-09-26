# -*- coding: utf-8 -*-
"""A MEASUREMENT THAT COVERS LESS THAN THE TREE MUST SAY SO, OR IT IS NOT A MEASUREMENT.

WHAT THIS EXISTS FOR, and it is the most expensive thing in this bundle's history.

The owner said, repeatedly and unambiguously, *measure everything*. The sweep needed a list of
packages to work through, so one was written: six entries. Eleven other packages held code. The
result, a month later:

    coverage_baseline.json :: modules        67 of 213      146 missing
    exercised_by.json :: exercised_by        67 of 213      146 missing
    mutation_baseline.json                   64 of 213      149 missing

Three records, each internally consistent, each with its own ratchets, each passing its own
tests, and all three silent about two thirds of the application. Nothing compared any of their
key sets against the tree. **An internally consistent measurement of the wrong set looks exactly
like a correct one.**

THE CORRELATION IS WHAT MADE IT INVISIBLE. Measured per package: the six listed were almost
entirely TESTED - one of them 0% untested, the rest 1 module each. The eleven missing were 100%
untested, every one. A package with tests sweeps cleanly and produces a score; a package without
them produces a refusal, which looks like the tool failing. So the packages that made the run
look like it worked went on the list and the others did not, and from then on the measurement
was SELF-CONFIRMING: it covered exactly the code that could produce results.

THE SECOND HALF IS WORSE. The planner REFUSES a module no test executes - correctly, because a
mutation score over untested code is 0% and 0% is not a measurement. But that refusal was a
PRINTED LINE and nothing recorded it. One listed package had all seven of its files untested: the
runner reached it, found nothing it was allowed to plan, and had nothing to do. Everything the
TOOL could weigh had been weighed - which is not the same sentence as everything being measured,
and the runner was stopped on the strength of it. 2,922 lines of order-placing code sat with
zero tests for a month while a report said 64%.

WHICH RECORDS GET CHECKED IS DERIVED. A hand-written list of records to check would be the
identical defect one level up - and that is not hypothetical, it is precisely what the scope
list was. A record is module-keyed when enough of its keys are real module paths; nobody
registers it, and one added tomorrow is checked the day it exists.

WHAT EACH RECORD IS FOR IS REGISTERED, and an unregistered one REFUSES. Two kinds exist and they
ratchet in OPPOSITE directions:

    covers    the record should describe every module - what it MISSES may only fall
    exempts   the record lists exceptions - how many it EXEMPTS may only fall

Guessing between them bounds half of them backwards: ratcheting "missing" on an exception list
rewards exempting more of the tree. Discovery derived, meaning declared, and a record nobody has
classified stops the check rather than being skipped - the split `check_bundle` uses for the
same reason.

    python -B tools/whole_tree.py           every module-keyed record and what it misses
    python -B tools/whole_tree.py record    write the ceilings
    python -B tools/whole_tree.py list X    the modules record X does not cover

ADAPT: `PACKAGE` is your application's import root and `RECORDS` is where your tracked baselines
live. Nothing else needs changing - see ADAPT.md.
"""
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

#: YOUR APPLICATION'S ROOT, relative to the project. Every record is measured against what is
#: actually in here - never against a list of what somebody thought was in here.
PACKAGE = "src"

#: Where your tracked measurements live.
RECORDS = "."

#: The file extension your source uses.
SOURCE = ".java"

BASELINE = os.path.join("tools", "guard", "whole_tree_baseline.json")

#: Adapted per project: see ADAPT.md.
ADAPT = ("PACKAGE", "RECORDS", "SOURCE", "BASELINE")

#: How many real module paths a dict needs before it counts as keyed BY MODULE. Below this, a
#: record that merely mentions a couple of modules in passing would be held to covering the
#: tree - a ratchet firing on honest work, which is the kind whose ceiling gets raised until
#: nothing in the folder is believed.
ENOUGH_KEYS = 5

#: The two meanings a module-keyed record can have.
KINDS = ("covers", "exempts")

LF = chr(10)


def _root(project=None):
    return os.path.abspath(project or HERE)


def modules(project=None):
    """Every source file under the package, WALKED. What every record is measured against."""
    root = _root(project)
    found = set()
    for base, dirs, names in os.walk(os.path.join(root, PACKAGE)):
        dirs[:] = [d for d in dirs if not d.startswith(".") and d != "__pycache__"]
        for name in sorted(names):
            if name.endswith(SOURCE):
                rel = os.path.relpath(os.path.join(base, name), root)
                found.add(rel.replace(os.sep, "/"))
    return found


def _sections(payload):
    """(label suffix, dict) for the top level and each dict one level in.

    ONE LEVEL, not a full walk. A record keys its modules at the top or under a single named
    section; descending further finds a module's own per-function detail and reports it as a
    record that covers exactly one module.
    """
    yield "", payload
    for key, value in sorted(payload.items()):
        if isinstance(value, dict):
            yield " :: " + key, value


def records(project=None, tree=None):
    """{label: covered modules} for every tracked record keyed by module path."""
    root = _root(project)
    tree = modules(project) if tree is None else tree
    folder = os.path.join(root, RECORDS)
    found = {}
    if not os.path.isdir(folder):
        return found
    for name in sorted(os.listdir(folder)):
        if not name.endswith(".json"):
            continue
        try:
            with io.open(os.path.join(folder, name), encoding="utf-8") as handle:
                payload = json.loads(handle.read())
        except (OSError, ValueError):
            continue
        if not isinstance(payload, dict):
            continue
        for suffix, section in _sections(payload):
            covered = set(section) & tree
            if len(covered) >= ENOUGH_KEYS:
                found[name + suffix] = covered
                break
    return found


def gaps(project=None, tree=None):
    """{label: sorted modules the record does not mention}."""
    tree = modules(project) if tree is None else tree
    return {label: sorted(tree - covered)
            for label, covered in records(project, tree).items()}


def held(project=None):
    try:
        with io.open(os.path.join(_root(project), BASELINE), encoding="utf-8") as handle:
            return json.loads(
                handle.read())
    except (OSError, ValueError):
        return {}


def arrivals(missing, record):
    """The modules in `missing` that were NOT IN THE TREE when the ceilings were recorded.

    A RATCHET OVER A GROWING SCOPE, COUNTED IN TOTAL, CANNOT TELL A NEW MODULE FROM A LOST ONE.
    A module added to the package is missing from a record that is written only after the module
    is committed - a mutation score from a sweep of committed code, say - and the commit was
    refused for that score not being there, so in the project this came from no new module could
    ever land. A module that existed at the last `record` and lost its entry is still refused;
    only one that did not exist then is let in, printed as owed. The recorded set is the subject
    the ceiling was measured against, so a baseline without it allows no arrival at all.
    """
    recorded = (record or {}).get("tree_modules")
    if not isinstance(recorded, list):
        return []
    before = set(recorded)
    return sorted(m for m in missing if m not in before)


def regressions(project=None, found=None):
    """Every way the tree is less covered, or more exempted, than the record says it was."""
    tree = modules(project)
    found = gaps(project, tree) if found is None else found
    record = held(project)
    ceilings = record.get("missing_ceiling") or {}
    kinds = record.get("kind") or {}
    out = []
    for label in sorted(found):
        missing = len(found[label])
        kind = kinds.get(label)
        if kind not in KINDS:
            out.append(
                "%s is keyed by module and nobody has said WHAT IT IS. A record that should "
                "describe every module and one that lists exceptions ratchet in OPPOSITE "
                "directions, and guessing bounds one of them backwards. Record it as %s."
                % (label, " or ".join(KINDS)))
            continue
        if label not in ceilings:
            out.append(
                "%s carries NO ceiling, so nothing bounds it - and it %s %d module(s). A "
                "record nobody has bounded is one that can quietly say less every week."
                % (label, "leaves out" if kind == "covers" else "exempts", missing))
            continue
        # NOT `>`, IN EITHER DIRECTION. A ceiling ABOVE the real count is room to regress into -
        # README section 16, A RATCHET WITH SLACK IS A DEFECT OF EXACTLY THAT SIZE - and the
        # project this came from measured it: one record improved from 146 missing to nearly
        # zero and sat under its old ceiling of 146, so re-narrowing it back would have passed.
        # The fix was made there and this copy kept `>`; the copies drifted in the direction
        # nobody watches. `record` lowers the ceiling to what is there.
        arrived = len(arrivals(found[label], record)) if kind == "covers" else 0
        if kind == "covers" and ceilings[label] <= missing <= ceilings[label] + arrived:
            continue
        if kind == "covers" and missing != ceilings[label]:
            out.append(
                "%s misses %d module(s); its ceiling is %d. %s"
                % (label, missing, ceilings[label],
                   "A measurement may cover more of the tree and never less."
                   if missing > ceilings[label] else
                   "It covers MORE than the record says - lower the ceiling to what is there "
                   "rather than leaving slack nobody watches: whole_tree.py record"))
        elif kind == "exempts" and (len(tree) - missing) != ceilings[label]:
            out.append(
                "%s exempts %d module(s); its ceiling is %d. %s"
                % (label, len(tree) - missing, ceilings[label],
                   "An exception list may only ever shrink."
                   if (len(tree) - missing) > ceilings[label] else
                   "It exempts FEWER than the record says - lower the ceiling: "
                   "whole_tree.py record"))
    for label in sorted(ceilings):
        if label not in found:
            out.append(
                "%s is bounded in the record and is no longer a module-keyed record here - it "
                "was renamed, emptied, or its keys stopped being module paths. A record that "
                "VANISHES is not a record that passed." % label)
    return out


#: ASKED AT THE WRITE OF A RECORD - `.claude/hooks/guard_write_rules.py` finds this marker and
#: asks it of every JSON record under `RECORDS` about to be written. A record rewritten to mention
#: less of the tree than its ceiling allows is refused before the bytes land, instead of being
#: found by the next check against a baseline it has already replaced. Only the WORSE direction:
#: a record covering more is an improvement, and `record` lowers the ceiling to meet it.
AT_RECORD = "unheld_in"


def scope(records):
    """The paths a record in `records` is written at. A folder at the project's TOP - `.`,
    where CTRMap keeps its mutation baseline - is every top-level `.json`: spelled as a folder,
    the pattern began with a literal `./` and no project path ever matched it, so the record's own write was
    never asked and the rule sat below the point of action with nothing saying so."""
    folder = records.replace(chr(92), "/").strip("/")
    if folder in ("", "."):
        return r"^[^/]+\.json$"
    return r"^%s/[^/]+\.json$" % folder.replace(".", r"\.")


AT_SCOPE = scope(RECORDS)


def unheld_in(path, payload, project=None):
    """Every way THIS payload leaves out more of the tree than the record's ceiling says."""
    if not isinstance(payload, dict):
        return []
    tree = modules(project)
    recorded = held(project)
    ceilings = recorded.get("missing_ceiling") or {}
    kinds = recorded.get("kind") or {}
    name = os.path.basename(path)
    out = []
    for suffix, section in _sections(payload):
        covered = set(section) & tree
        if len(covered) < ENOUGH_KEYS:
            continue
        label = name + suffix
        if label in ceilings and kinds.get(label) == "covers" \
                and len(tree - covered) > ceilings[label] + len(arrivals(tree - covered,
                                                                         recorded)):
            out.append("%s would miss %d module(s) against a ceiling of %d - a measurement may "
                       "cover more of the tree and never less"
                       % (label, len(tree - covered), ceilings[label]))
        elif label in ceilings and kinds.get(label) == "exempts" \
                and len(covered) > ceilings[label]:
            out.append("%s would exempt %d module(s) against a ceiling of %d - an exception list "
                       "may only ever shrink" % (label, len(covered), ceilings[label]))
        break
    return out


def record(project=None):
    root = _root(project)
    tree = modules(project)
    found = gaps(project, tree)
    previous = held(project)
    kinds = dict(previous.get("kind") or {})
    before = previous.get("missing_ceiling") or {}
    ceilings = {}
    for label, missing in sorted(found.items()):
        # WHAT IS BOUNDED DEPENDS ON WHAT THE RECORD IS FOR: a measurement by what it leaves
        # out, an exception list by how much it exempts.
        count = len(missing) if kinds.get(label) != "exempts" else len(tree) - len(missing)
        # A COVERING RECORD'S CEILING RISES BY ITS ARRIVALS AND NOTHING ELSE - modules that did
        # not exist at the last record. Anything more is a regression, and stays refused.
        grown = len(arrivals(missing, previous)) if kinds.get(label) != "exempts" else 0
        ceilings[label] = min(count, before.get(label, count) + grown)
    payload = {
        "_how": "python -B tools/whole_tree.py record",
        "_why": ("Every tracked record keyed by module path, and how much of the application it "
                 "does not mention. Three of them described the same third of one tree while "
                 "each was internally consistent and passed its own tests. These may only ever "
                 "fall, and a record nobody has classified refuses rather than being skipped."),
        "modules_in_tree": len(tree),
        # THE SET, NOT ONLY ITS SIZE: `arrivals` asks which missing modules are new since this
        # record, and a count cannot answer that.
        "tree_modules": sorted(tree),
        "missing_ceiling": ceilings,
        "missing_now": {label: len(m) for label, m in sorted(found.items())},
        # CARRIED FORWARD, never rebuilt from scratch: a recorder that stops naming a field
        # deletes its own ratchet on every run, which is a defect this bundle has seen.
        "kind": kinds,
    }
    with io.open(os.path.join(root, BASELINE), "w", encoding="utf-8", newline=LF) as handle:
        handle.write(
            json.dumps(payload, indent=1, sort_keys=True) + LF)
    sys.stdout.write("recorded %d module-keyed record(s) against %d module(s)%s"
                     % (len(ceilings), len(tree), LF))
    return 0


def main(argv):
    command = argv[1] if len(argv) > 1 else "check"
    if command == "record":
        return record()

    tree = modules()
    found = gaps(tree=tree)

    if command == "list":
        if len(argv) < 3:
            sys.stdout.write("name a record: %s%s" % (", ".join(sorted(found)), LF))
            return 2
        for label in [name for name in found if argv[2] in name]:
            for rel in found[label]:
                sys.stdout.write(rel + LF)
        return 0

    if not found:
        # NOTHING MEASURED MUST NEVER RENDER AS SUCCESS. Finding no module-keyed record at all
        # means this looked in the wrong place, which is a mistake this bundle has shipped: a
        # checker whose root was one directory short walked an empty tree and reported clean.
        sys.stdout.write(
            "REFUSING: no module-keyed record found under %s/, and no records is not a clean "
            "answer. Check PACKAGE=%r and RECORDS=%r." % (RECORDS, PACKAGE, RECORDS) + LF)
        return 1

    sys.stdout.write("%d module(s) in %s/%s" % (len(tree), PACKAGE, LF))
    for label in sorted(found):
        sys.stdout.write("  %-46s covers %4d   MISSES %4d%s"
                         % (label, len(tree) - len(found[label]), len(found[label]), LF))

    record_now = held()
    for label in sorted(found):
        owed = arrivals(found[label], record_now) \
            if (record_now.get("kind") or {}).get(label) == "covers" else []
        if owed:
            sys.stdout.write("  OWED: %d module(s) new since the last record, not yet in %s: %s%s"
                             % (len(owed), label, ", ".join(owed), LF))

    problems = regressions(found=found)
    if problems:
        sys.stdout.write(LF)
        for line in problems:
            sys.stdout.write("REFUSING: %s%s" % (line, LF))
        return 1
    sys.stdout.write(LF + "every module-keyed record is within its ceiling." + LF)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
