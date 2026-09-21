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
    """Every rule headline, in the order they appear. ONE parser, not a second one.

    This used to read the file its own way - splitting on every bolded span and cutting each
    headline at its first full stop - while `bodies_in` read it another. They disagreed about
    one rule out of thirty-one, and the disagreement was silent in the worst possible place:
    the clause audit looked up each rule's body with `bodies.get(rule, "")`, so a rule this
    function named and that function did not was audited as having NO CLAUSES AT ALL. A query
    that cannot read its subject must not report it absent, and the tool that audits that rule
    was breaking it.
    """
    return list(bodies_in(text))


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


#: An imperative inside a rule's body: its own prohibition, under a headline that may well
#: be enforced. "Never run Tidewater" sat under GAME DATA IS READ-ONLY for the life of this
#: project while the map reported that rule green.
#: Prohibitions AND requirements. "Undo/redo everywhere" is a rule of this project and
#: was invisible to a pattern that only knew how to read a Never - the same mistake as
#: auditing headlines and not clauses, one shape further in.
#: THE SHAPES AN IMPERATIVE COMES IN, and this list has been short twice. It began knowing only
#: prohibitions, so "Undo/redo everywhere" - a requirement - was missed; the `everywhere` arm was
#: added for it. It still did not know `must`, which is how "The record must not be part of its
#: own subject" sat in a rule body with nothing asked of it. Whatever level an audit reads,
#: something lives one level inside it: the test is not whether the count is high, it is whether
#: you can name the shape the count does not cover.
_CLAUSE = re.compile(
    r"\b((?:Never|Always|No|Do not|Don't)\s+[^.\n;]{6,110}"
    r"|[A-Z][\w/]{3,}(?:\s+\w+){0,3}\s+everywhere"
    r"|[A-Z][^.\n;]{0,80}?\bmust(?: not)?\s+[^.\n;]{6,110})")

#: ...but not the headline itself, which is audited separately and is in capitals.
def _is_headline(text):
    letters = [c for c in text if c.isalpha()]
    return bool(letters) and all(c.isupper() for c in letters)


#: A HEADLINE IS BOLD, ALL-CAPS, AND STARTS A LINE. Bold is also used for EMPHASIS inside a
#: body, and the previous version split on EVERY bolded span, so a body ended at its first
#: emphasised phrase and the rest was attributed to a key that is not a rule and then dropped.
#: MEASURED 2026-09-21 before this was changed: 11 of 31 rules were read only that far, and
#: 2,921 characters of CLAUDE.md - six imperatives among them - had never been read by the
#: clause audit at all. §16 of the bundle records this same check being built to recognise one
#: shape twice; this is the third. The count was 31 of 31 the whole time.
_HEADLINE = re.compile(r"^\*\*([A-Z][^*]*?)\*\*", re.M)


def bodies_in(text):
    """{headline: body} - the WHOLE of what each rule says, to the next rule's headline.

    A headline stated twice has BOTH bodies, joined. `A RUN THAT KNOWS WHAT IT MISSED GOES
    BACK` is in section 1 and again in section 2 in this project's CLAUDE.md, and a dict keyed
    by headline kept one of them - so one entire rule body, clauses included, was never audited
    and the count could not show it. Two statements of one rule are two sets of obligations,
    not a collision to resolve by writing the second over the first.
    """
    found = [(" ".join(m.group(1).split()).strip(" ."), m.start(), m.end())
             for m in _HEADLINE.finditer(text)]
    #: ...and the headline is NOT cut at its first full stop. "DO THE WORK MECHANICALLY. AGENT
    #: SWEEPS ARE THE LAST RESORT" was audited as "DO THE WORK MECHANICALLY", so the ledger
    #: carried an entry for half a rule and the other half was never named anywhere.
    found = [(h, at, after) for h, at, after in found if _is_headline(h) and len(h) > 12]
    out = {}
    for i, (head, at, after) in enumerate(found):
        end = found[i + 1][1] if i + 1 < len(found) else len(text)
        body = " ".join(text[after:end].split()).strip()
        out[head] = (out[head] + " " + body).strip() if head in out else body
    return out


def clauses_in(body):
    """Every imperative sentence in a rule's body, deduplicated, headline text excluded.

    Emphasis markers are removed before the scan, so a clause that happens to be bolded does
    not carry `**` into the middle of the key the ledger has to match exactly.
    """
    found = []
    seen = set()
    for hit in _CLAUSE.finditer((body or "").replace("**", "")):
        clause = " ".join(hit.group(1).split()).strip(" *_`,;")
        if _is_headline(clause) or len(clause) < 10:
            continue
        key = clause.lower()
        if key in seen:
            continue
        seen.add(key)
        found.append(clause)
    return found


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

    #: EVERY CLAUSE, not only the headline. A rule whose headline has a mechanism can still
    #: carry three imperatives that have none - that is what this missed for the life of the
    #: project, and it reported 28 of 28 over the top of it.
    bodies = bodies_in(text)
    for rule in rules:
        entry = entries.get(rule) or {}
        mapped = entry.get("clauses") or {}
        if rule not in bodies:
            #: NOT `bodies.get(rule, "")`. An absent body audits as a rule with no clauses,
            #: which is indistinguishable from a rule whose clauses are all accounted for -
            #: and it is how one rule's whole body went unaudited while the count said 31 of
            #: 31. There is no reading of this that is safe to guess.
            why.append("%r is a rule whose BODY could not be found, so its clauses are UNKNOWN "
                       "- not none. The two readers of CLAUDE.md have gone out of step."
                       % rule[:70])
            continue
        for clause in clauses_in(bodies[rule]):
            named = mapped.get(clause)
            if named is None:
                why.append(
                    "%r says %r, and that clause has NO ENTRY of its own. A rule can be "
                    "enforced and its clauses unenforced at the same time: \"Never run "
                    "Tidewater\" sat under a green rule for the life of this project. Add it "
                    "to that rule's \"clauses\" with a mechanism, or with \"\" and a reason."
                    % (rule[:52], clause[:74]))
            elif named and not _exists(root, named):
                why.append("%r claims the clause %r is enforced by %r, which is not in the "
                           "tree." % (rule[:40], clause[:50], named))

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
