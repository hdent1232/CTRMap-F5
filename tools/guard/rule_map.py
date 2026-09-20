#!/usr/bin/env python3
"""Every rule in CLAUDE.md, and the mechanism that refuses it. Measured, not claimed.

WHY THIS EXISTS. The owner asked for every guard and every rule to be a refusal by class at the
point of action. The suites were measured and converted; THE RULES THEMSELVES WERE NEVER
MEASURED AT ALL. When they finally were, on 2026-09-20, sixteen of the twenty-eight rules in
capitals had no mechanism behind them - not a weak one, none. Among them:

  * GAME DATA IS READ-ONLY, while a test fixture had driven a pack against the live dump the
    day before
  * NEVER PUSH WITHOUT EXPLICIT PER-PUSH APPROVAL
  * NEVER EDIT SOURCE WHILE THE SUITE IS RUNNING, after two battery runs had been invalidated
  * A QUERY THAT CANNOT READ ITS SUBJECT MUST NOT REPORT IT ABSENT, after 134 verdicts were
    discarded on the strength of a probe that could not look

The answer to "is every rule enforced" was a claim until this file made it a count. That is
the same defect the rules are about, one level up: A CLAIM ABOUT WHAT YOU DID NOT OPEN IS A
MEASUREMENT.

WHAT IT REFUSES:

  1. a rule in CLAUDE.md with no entry in the map at all - a rule nobody has even decided
     about is not enforced, and it is not exempt either
  2. an entry naming a mechanism that does not exist in the tree - "reviewed: all of them"
     with a colon in it
  3. more unenforced rules than the ceiling allows, so the number may only fall
  4. a CLAUDE.md it cannot read - because "I could not look" is not "there is nothing there"

Usage: python tools/guard/rule_map.py [repo-root]
Exit 1 with reasons, 0 when every rule is accounted for and the ceiling holds.
"""
import io
import json
import os
import re
import sys

MAP = os.path.join("tools", "guard", "rule_map.json")


def claude_md(root):
    """The CLAUDE.md governing this repository, or None. Looked for at the root and above."""
    here = os.path.abspath(root)
    for _ in range(4):
        candidate = os.path.join(here, "CLAUDE.md")
        if os.path.isfile(candidate):
            return candidate
        parent = os.path.dirname(here)
        if parent == here:
            break
        here = parent
    return None


def rules_in(text):
    """Every rule headline: a bolded span whose first sentence is in capitals.

    The shape, not a list - a list would have to be kept in step with the file by hand, which
    is the thing being refused one level down.
    """
    found = []
    for span in re.findall(r"\*\*(.+?)\*\*", text, re.S):
        head = " ".join(span.split()).split(".")[0].strip()
        letters = [c for c in head if c.isalpha()]
        if letters and all(c.isupper() for c in letters) and len(head) > 12:
            found.append(head)
    seen = set()
    return [r for r in found if not (r in seen or seen.add(r))]


def _exists(root, mechanism):
    """Whether a named mechanism is really in the tree.

    `path/to/file.py` must be a file. `module.function` must appear in one. A name that
    matches nothing is the citation rule's defect: a sentence with a colon in it.
    """
    if "/" in mechanism or mechanism.endswith((".py", ".java", ".ps1")):
        return os.path.isfile(os.path.join(root, mechanism))
    token = re.split(r"[ .+(]", mechanism)[0]
    if not token:
        return False
    for base in ("tools", "src", ".githooks"):
        for dirpath, dirs, files in os.walk(os.path.join(root, base)):
            dirs[:] = [d for d in dirs if d not in ("__pycache__", "build", "dist")]
            for name in files:
                if not name.endswith((".py", ".java", ".ps1")):
                    continue
                try:
                    with io.open(os.path.join(dirpath, name), encoding="utf-8",
                                 errors="replace") as handle:
                        if token in handle.read():
                            return True
                except OSError:
                    continue
    return False


def findings(root):
    """Every rule that is not accounted for, and every claim in the map that is not true."""
    path = claude_md(root)
    if path is None:
        return ["there is no CLAUDE.md at or above %s, so which rules are in force could not "
                "be read at all - which is not the same as there being none"
                % os.path.abspath(root)]
    try:
        text = io.open(path, encoding="utf-8").read()
    except OSError as cannotRead:
        return ["cannot read %s (%s) - an unreadable rule file is not an empty one"
                % (path, cannotRead)]

    try:
        book = json.load(io.open(os.path.join(root, MAP), encoding="utf-8"))
    except (OSError, ValueError) as cannotRead:
        return ["cannot read %s (%s) - so which rules have a mechanism is UNKNOWN"
                % (MAP, cannotRead)]

    entries = book.get("rules") or {}
    ceiling = book.get("unenforced_ceiling")
    why = []
    rules = rules_in(text)
    if not rules:
        return ["no rules found in %s - either the file changed shape or this is reading it "
                "wrongly. Both are refusals, and a scan that finds nothing is the second." % path]

    unknown = []
    unenforced = []
    for rule in rules:
        entry = entries.get(rule)
        if entry is None:
            unknown.append(rule)
            continue
        mechanism = (entry or {}).get("by") or ""
        if not mechanism:
            unenforced.append(rule)
            continue
        if not _exists(root, mechanism):
            why.append("%r claims to be enforced by %r, which is not in the tree. A citation "
                       "naming nothing that exists is the same sentence with a colon in it."
                       % (rule[:60], mechanism))

    for rule in unknown:
        why.append("%r is a rule in CLAUDE.md with NO ENTRY in %s. A rule nobody has decided "
                   "about is not enforced, and it is not exempt either - add it with a "
                   "mechanism, or with \"by\": \"\" and a reason, which counts against the "
                   "ceiling." % (rule[:70], MAP))

    stale = [r for r in entries if r not in rules]
    for rule in stale:
        why.append("%r is in %s but is no longer a rule in CLAUDE.md - a map that describes a "
                   "file it has drifted from is worse than none." % (rule[:60], MAP))

    n = len(unenforced)
    if ceiling is None:
        why.append("%s declares no unenforced_ceiling, so nothing stops the number rising"
                   % MAP)
    elif n > ceiling:
        why.append("%d rule(s) have no mechanism, over the ceiling of %d. It may only fall: "
                   "sixteen of twenty-eight were unenforced when this was first measured, and "
                   "every one of them had been in the file for weeks." % (n, ceiling))
    return why


def summary(root):
    """(total, enforced, unenforced) - the count, for a report that has to cite one."""
    path = claude_md(root)
    text = io.open(path, encoding="utf-8").read() if path else ""
    rules = rules_in(text)
    try:
        book = json.load(io.open(os.path.join(root, MAP), encoding="utf-8"))
    except (OSError, ValueError):
        return len(rules), 0, len(rules)
    entries = book.get("rules") or {}
    enforced = sum(1 for r in rules if (entries.get(r) or {}).get("by"))
    return len(rules), enforced, len(rules) - enforced


def main(argv):
    root = argv[1] if len(argv) > 1 else "."
    why = findings(root)
    total, enforced, unenforced = summary(root)
    if not why:
        print("%d rules in CLAUDE.md: %d have a mechanism, %d do not (ceiling holds)"
              % (total, enforced, unenforced))
        return 0
    print("THE RULES AND THEIR MECHANISMS DO NOT AGREE:")
    for reason in why:
        print("  - " + reason)
    print()
    print("%d rules, %d enforced, %d not" % (total, enforced, unenforced))
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
