#!/usr/bin/env python3
"""Every registered suite, classified by what KIND of guard it is. The count a review cites.

WHY THIS EXISTS. A review of this project's guards read fourteen of them - eight hooks, four
tools, two scripts - and then wrote "everything else reviewed holds its shape" about 130
registered suites and 150 plants. Nothing was lying on purpose; that is what a review feels like
from the inside when the part you looked at is the part you already knew. What was missing is
that the difference between fourteen and a hundred and fifty was not visible anywhere, and the
measurement that settles it takes two minutes.

So `commit_guard.py` rule 7 refuses a blanket claim about what a commit did not open unless the
message cites a measurement, and this is the measurement it cites:

    Reviewed: 130/130 suites by tools/guard/classify_guards.py

WHAT IT CANNOT TELL YOU. A suite always notices after the fact - that is what a suite is. This
does not grade whether a defect class has a refusal at its point of action in production; it
grades whether the SUITE provokes the bad action and asserts it was refused, which is the closest
thing to that question a text scan can answer. Read the ones it calls "compares outputs" before
believing they are weak: a byte-identical round-trip can only be compared, and that is correct.

    python tools/guard/classify_guards.py            # counts, and the worst-off suites
    python tools/guard/classify_guards.py --all      # every suite, with its kind
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TESTS = os.path.join(ROOT, "src", "ctrmap", "tests")
LF = chr(10)

#: the suite provokes a production call and asserts it was REFUSED
REFUSAL_DRIVEN = re.compile(r"catch\s*\(\s*(?:Runtime|Illegal|IO|Throwable|Exception)\w*\s+\w+\s*\)")
SAYS_REFUSED = re.compile(r"refus|threw|throws|rejected|cannot|would not|declined", re.I)
CEILING = re.compile(r"\bCEILING\b|ceiling|may only fall|may not rise", re.I)
SOURCE_SCAN = re.compile(r"Files\.readAllBytes|readAllLines|javaSources|\.contains\(\"")
BYTECODE = re.compile(r"ClassFileScanner|build/classes|\.class\b")


def registered():
    runner = os.path.join(ROOT, "test.ps1")
    names = []
    for line in io.open(runner, encoding="utf-8", errors="replace"):
        found = re.search(r'c\s*=\s*"ctrmap\.tests\.(\w+)"', line)
        if found:
            names.append(found.group(1))
    return names


def kind_of(body):
    if REFUSAL_DRIVEN.search(body) and SAYS_REFUSED.search(body):
        return "drives a refusal"
    if CEILING.search(body):
        return "ratchet/ceiling"
    if BYTECODE.search(body):
        return "asks the bytecode"
    if SOURCE_SCAN.search(body):
        return "source text only"
    return "compares outputs"


def planted():
    text = io.open(os.path.join(ROOT, "tools", "guard", "plants.json"),
                   encoding="utf-8").read()
    return set(re.findall(r'"suite"\s*:\s*"ctrmap\.tests\.(\w+)"', text))


def main(argv):
    names = registered()
    have_plant = planted()
    rows = []
    for name in names:
        path = os.path.join(TESTS, name + ".java")
        if not os.path.isfile(path):
            rows.append((name, 0, "MISSING FILE", False))
            continue
        body = io.open(path, encoding="utf-8", errors="replace").read()
        rows.append((name, body.count(LF) + 1, kind_of(body), name in have_plant))

    counts = {}
    for _, _, kind, _ in rows:
        counts[kind] = counts.get(kind, 0) + 1
    sys.stdout.write("%d registered suite(s)%s" % (len(rows), LF))
    for kind in sorted(counts, key=lambda k: -counts[k]):
        sys.stdout.write("  %-20s %3d%s" % (kind, counts[kind], LF))
    owed = [r for r in rows if not r[3]]
    sys.stdout.write("  %-20s %3d%s" % ("with a plant", len(rows) - len(owed), LF))
    sys.stdout.write("  %-20s %3d%s" % ("owed a plant", len(owed), LF))

    weak = [r for r in rows if r[2] in ("compares outputs", "source text only") and not r[3]]
    sys.stdout.write(LF + "%d suite(s) that neither drive a refusal nor have a plant - a class "
                     "whose%s  only witness is a comparison nobody has broken:%s"
                     % (len(weak), LF, LF))
    for name, lines, kind, _ in sorted(weak, key=lambda r: -r[1]):
        sys.stdout.write("  %-34s %5d lines  %s%s" % (name, lines, kind, LF))

    if "--all" in argv:
        sys.stdout.write(LF + "every suite:" + LF)
        for name, lines, kind, plant in sorted(rows):
            sys.stdout.write("  %-34s %-18s %s%s"
                             % (name, kind, "planted" if plant else "OWED a plant", LF))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
