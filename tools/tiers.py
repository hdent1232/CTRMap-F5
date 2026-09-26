#!/usr/bin/env python3
"""EVERY GUARD IS ASKED AT THE POINT OF ACTION - derived from what is WIRED, and refused otherwise.

WHY THIS FILE SHIPS, measured on the project this bundle was installed into first. Asked whether
the guards installed that day refused at the point of action or were found at the commit, the
answer was mostly the second: fourteen rules recorded as refusing "at the write" were asked by a
channel that carried scripted edits only, and thirty-three more sat at the commit or in the suite
with a paragraph each explaining why nothing earlier could ask them. Most of those paragraphs
were wrong - "tree-wide", "reads a record", "its subject is the tests", "needs a run" are each a
fact about a set of files, and a write changes exactly one file of a set.

A GUARD IS AT THE POINT OF ACTION WHEN ONE OF THESE IS TRUE, and this derives which:

    a hook the harness runs on the tool call      `.claude/hooks/guard_*.py` with the dispatcher
                                                  wired, or any other hook the settings name
    a rule a wired hook asks at the act           it declares AT_WRITE, AT_WRITE_TREE,
                                                  AT_WRITE_CHANGE, AT_RECORD, AT_COMMAND,
                                                  AT_COMMIT or AT_AFTER, and `bundle_rules.asked`
                                                  says the hook that asks that marker is wired
    the commit gate, while it cannot be skipped   a git hook script, or a tool one RUNS, while
                                                  `guard_command_rules.py` refuses `--no-verify`

Anything else that refuses is below the point of action, and is REFUSED: at the commit by this
tool's check, and at the WRITE that lands it by the tree rule below. A tool that measures and
refuses nothing about the tree is named in `NOT_GUARDS` with the reason, and a reason shorter
than MIN_REASON is refused too - the cheapest way to zero guards below action is to stop calling
them guards.

    python -B tools/tiers.py          every guard and whether it is at the point of action
    python -B tools/tiers.py --adopt  ONCE, at install: the guards the project ARRIVED with below
                                      the point of action, as its starting debt

THE STARTING DEBT, measured installing this bundle into a real project. CTRMap arrived with 38 of
its own tools that refuse things and that nothing asks at the point of action - older forks of
these hooks, its own mutation harness, its own guards - and a gate whose ceiling is zero refused
every commit over them, which it could not tell apart from a regression. `--adopt` records that
set once, in `.claude/bundle-install.json` where the diff shows it; from then on a guard below the
point of action that is NOT in it is refused, and the adopted set can only shrink as its tools
are brought to the act. Adopting again is the cheapest way past, so it is refused - also after
the record is deleted, while the committed declarations still carry it.
"""
import ast
import io
import json
import os
import re
import subprocess
import sys
if __name__ == "__main__":
    sys.dont_write_bytecode = True     # run from its folder, a tool leaves no bytecode there
import time

LF = chr(10)
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0
HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import bundle_hooks      # noqa: E402  - where the bundle's hooks are, found rather than spelled
sys.path.insert(0, bundle_hooks.path(os.path.dirname(HERE)))
import bundle_rules      # noqa: E402  - what a file declares, and whether that act is asked

#: Where guards live, relative to the project root, beside every hook folder `hook_dirs` finds.
#: WALKED, never listed.
GUARD_DIRS = (".githooks", "tools")

#: The files that measure and refuse nothing ABOUT THE TREE, each with the reason. Adapt it; a
#: reason under MIN_REASON is refused.
NOT_GUARDS = {
    "render_readme": "Renders README.md to README.html. Its one refusal is that it cannot render "
                     "without the markdown package - about itself, not about the project.",
    "install": "Performs an install from the bundle into a project, once, by hand. Its refusals "
               "are about the install it is doing - a file that differs, a hooks path elsewhere "
               "- and it is never installed; the project's own gates ask the install afterwards.",
    "check_bundle": "The bundle's own self-check, run inside the bundle folder by its suite and "
                    "never installed into a project; the project asks the install through "
                    "bundle_install at the write.",
    "check_install": "Run FROM the bundle against a project. The project asks it through "
                     "tools/bundle_install.py, which declares AT_WRITE and is asked at the write "
                     "of every file the install check reads.",
    "require_build": "A LIBRARY, not a gate: its refusal runs inside `require_build(root)`, which "
                     "a project's harness calls immediately before it measures - the point of "
                     "action by construction, wherever it is called.",
}

#: A reason shorter than this is a label.
MIN_REASON = 60

#: A file that SAYS NO. Derived from its own text: a guard refuses in words or holds a ceiling.
SAYS_NO = re.compile(r"REFUSING|refuse|_ceiling|ceiling\b", re.I)

#: The hooks every dispatched guard runs ON rather than guards of their own: the dispatcher, and
#: every module a `guard_*.py` beside it imports - `machinery()`. This was a list of four names,
#: and the fifth, `request_ledger`, was refused as "below the point of action" by the first
#: commit on CTRMap after it arrived: a module only a dispatched guard asks is asked at the act.
MACHINERY = ("dispatch",)


def imported(text):
    """The top-level names of the modules a file imports, or [] when it does not parse."""
    try:
        tree = ast.parse(text or "")
    except SyntaxError:
        return []
    out = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            out.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module and not node.level:
            out.add(node.module.split(".")[0])
    return sorted(out)


def machinery(guard_imports):
    """The names a hook folder's guards run ON: the dispatcher, and whatever its guards import."""
    out = set(MACHINERY)
    for names in guard_imports:
        out.update(names)
    return out


def machinery_in(root, folder):
    """`machinery` of the guards on disk in `folder`."""
    try:
        names = sorted(os.listdir(os.path.join(root, folder)))
    except OSError:
        return set(MACHINERY)
    return machinery(imported(read(root, os.path.join(folder, name))) for name in names
                     if name.startswith("guard_") and name.endswith(".py"))

#: Adapted per project: see ADAPT.md.
ADAPT = ("GUARD_DIRS", "NOT_GUARDS")


def _root():
    return bundle_rules.repo_root(__file__) or os.path.dirname(HERE)


def hook_dirs(root):
    """Every folder a hook is asked from. The bundle's may sit beside a stack of the project's
    own, and a guard in either is a guard - measured on CTRMap, where both are wired."""
    return bundle_hooks.hook_folders(root)


def guard_dirs(root):
    return tuple(sorted(set(GUARD_DIRS) | set(hook_dirs(root))))


def hook_folder_of(root, rel):
    """The hook folder `rel` sits in, or None."""
    for folder in hook_dirs(root):
        if rel.startswith(folder + "/"):
            return folder
    return None


def wired_from(settings, folder):
    """The names of the files in `folder` the settings run - either separator, as JSON spells it."""
    pattern = r"[/\\]+".join(re.escape(part) for part in folder.split("/"))
    return set(re.findall(pattern + r"[/\\]+([\w.-]+\.py)", settings or ""))


def read(root, rel):
    try:
        with io.open(os.path.join(root, rel), encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return ""


def wiring(root):
    return read(root, os.path.join(".claude", "settings.json")) + read(
        root, os.path.join(".claude", "settings.local.json"))


def gate_named(text):
    """Every `.py` a git hook's script RUNS - its non-comment lines, never its commentary."""
    found = set()
    for line in (text or "").splitlines():
        if line.strip().startswith("#"):
            continue
        found.update(m.replace(chr(92), "/") for m in re.findall(r"[\w./-]+\.py", line))
    return found


def protects(text):
    """Does this command hook's text point the unskippable gate at `.githooks`?"""
    try:
        tree = ast.parse(text)
    except SyntaxError:
        return False
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(
                isinstance(t, ast.Name) and t.id == "HOOKS_DIR" for t in node.targets):
            try:
                return str(ast.literal_eval(node.value)).strip("/") == ".githooks"
            except ValueError:
                return False
    return False


def is_guard(rel, text):
    stem = os.path.splitext(os.path.basename(rel))[0]
    if not rel.endswith(".py") and not rel.startswith(".githooks/"):
        return False
    if stem in NOT_GUARDS or stem.startswith("__"):
        return False
    return bool(SAYS_NO.search(text or ""))


def discovers(text):
    """Is this hook a DISPATCHER - does it find `guard_*` files beside it rather than list them?

    By what it does, not by its name. The bundle's is `dispatch.py`; measured on CTRMap, whose
    own `guard_all.py` discovers every guard beside it and asks each at the act, a derivation that
    knew only the one name counted all of CTRMap's guards below the point of action.
    """
    source = text or ""
    return "guard_" in source and ("listdir(" in source or "glob(" in source)


def dispatched(root, settings, folder=None):
    """Does the wiring run a dispatcher OVER `folder` - the bundle's, or any wired hook in it that
    discovers guards? A dispatcher asks the guards beside it and no others: CTRMap's asks its own
    folder, the bundle's asks the bundle's. `folder` None asks the bundle's."""
    folder = folder or bundle_hooks.folder(root)
    wired = wired_from(settings, folder)
    if "dispatch.py" in wired:
        return True
    return any(discovers(read(root, os.path.join(folder, name)))
               for name in wired if os.path.isfile(os.path.join(root, folder, name)))


def at_action(root, rel, text, settings, gate, protected, by_dispatcher=None):
    """Is this guard asked AT the act? The one test, used by the check and by the write."""
    stem = os.path.splitext(os.path.basename(rel))[0]
    if any(not error and bundle_rules.asked(root, kind, settings)
           for kind, _entry, _scope, error in bundle_rules.declarations_in(text)):
        return True
    folder = hook_folder_of(root, rel)
    if folder:
        if stem.startswith("guard_") or stem in machinery_in(root, folder):
            return (dispatched(root, settings, folder) if by_dispatcher is None
                    else by_dispatcher)
        return os.path.basename(rel) in wired_from(settings, folder)
    return (rel.startswith(".githooks/") or rel in gate) and protected


def guards(root):
    """[(rel, text)] for every file under the guard folders that says no."""
    out = []
    for folder in guard_dirs(root):
        base = os.path.join(root, folder)
        for dirpath, dirnames, names in os.walk(base):
            dirnames[:] = [d for d in dirnames if d != "__pycache__"]
            for name in sorted(names):
                rel = os.path.relpath(os.path.join(dirpath, name), root).replace(os.sep, "/")
                text = read(root, rel)
                if is_guard(rel, text):
                    out.append((rel, text))
    return out


def below(root=None):
    """Every guard nothing asks at the point of action."""
    root = root or _root()
    settings = wiring(root)
    gate = set()
    folder = os.path.join(root, ".githooks")
    if os.path.isdir(folder):
        for name in sorted(os.listdir(folder)):
            gate |= gate_named(read(root, os.path.join(".githooks", name)))
    protected = dispatched(root, settings) and protects(
        read(root, os.path.join(bundle_hooks.folder(root), "guard_command_rules.py")))
    return sorted(rel for rel, text in guards(root)
                  if not at_action(root, rel, text, settings, gate, protected))


def excusals():
    return sorted("%s is excluded from the guards with %d characters of reason" % (stem, len(why))
                  for stem, why in NOT_GUARDS.items() if len(str(why)) < MIN_REASON)


# ----------------------------------------------------------------------------- at the write

#: ASKED AT THE WRITE, OF THE WIRING AS THE WRITE WOULD LEAVE IT: a guard landed with no act
#: declared, or a hook or settings edit that unwires one, is refused by the write that does it.
AT_WRITE_TREE = ("facts_of", "judge")
AT_SCOPE = (r"^\.claude/settings(\.local)?\.json$|^(%s)/[^/]+$|^\.githooks/[^/]+$"
            r"|^tools/.*\.py$" % "|".join(re.escape(d) for d in hook_dirs(_root())))


def facts_of(rel, text):
    if rel.startswith(".claude/settings"):
        return {"wiring": text}
    out = {"guard": bool(rel.startswith(tuple(d + "/" for d in guard_dirs(_root())))
                         and is_guard(rel, text)),
           "declares": [kind for kind, _e, _s, error in bundle_rules.declarations_in(text)
                        if not error]}
    if rel.startswith(".githooks/"):
        out["runs"] = sorted(gate_named(text))
    if os.path.basename(rel) == "guard_command_rules.py":
        out["protects"] = protects(text)
    if hook_folder_of(_root(), rel):
        out["discovers"] = discovers(text)
        out["imports"] = imported(text)
    return out


def judge(facts):
    root = _root()
    settings = "".join((held or {}).get("wiring") or "" for rel, held in facts.items()
                       if rel.startswith(".claude/settings"))
    gate = set()
    for held in facts.values():
        gate.update((held or {}).get("runs") or [])

    def by_dispatcher(folder):
        wired = wired_from(settings, folder)
        return "dispatch.py" in wired or any(
            (held or {}).get("discovers") and rel == folder + "/" + os.path.basename(rel)
            and os.path.basename(rel) in wired for rel, held in facts.items())

    ours = bundle_hooks.folder(root)
    protected = by_dispatcher(ours) and any(
        (held or {}).get("protects") for rel, held in facts.items()
        if rel == ours + "/guard_command_rules.py")
    out = []
    for rel, held in sorted(facts.items()):
        if not (held or {}).get("guard"):
            continue
        stem = os.path.splitext(os.path.basename(rel))[0]
        asked = any(bundle_rules.asked(root, kind, settings) for kind in held["declares"])
        folder = hook_folder_of(root, rel)
        runs_on = machinery((held_ or {}).get("imports") or [] for rel_, held_ in facts.items()
                            if folder and rel_.startswith(folder + "/")
                            and os.path.basename(rel_).startswith("guard_"))
        hooked = bool(folder) and (
            ((stem.startswith("guard_") or stem in runs_on) and by_dispatcher(folder))
            or os.path.basename(rel) in wired_from(settings, folder))
        gated = (rel.startswith(".githooks/") or rel in gate) and protected
        if not (asked or hooked or gated):
            out.append("%s refuses things and nothing asks it at the point of action - declare "
                       "the act it is asked at, or name it in NOT_GUARDS with the reason it "
                       "refuses nothing about the tree" % rel)
    return out


#: The project's declarations, where the adopted starting debt is recorded.
DECLARATIONS = os.path.join(".claude", "bundle-install.json")
ADOPTED = "_tiers_adopted"


def _declarations(root):
    try:
        with io.open(os.path.join(root, DECLARATIONS), encoding="utf-8") as handle:
            held = json.load(handle)
    except (OSError, ValueError):
        return {}
    return held if isinstance(held, dict) else {}


def adopted(root):
    """The guards the project arrived with below the point of action - an empty set if none."""
    record = _declarations(root).get(ADOPTED) or {}
    return set(record.get("below") or []) if isinstance(record, dict) else set()


def committed_declarations(root):
    """The declarations' text at HEAD, '' when HEAD has none, None when git could not say."""
    try:
        done = subprocess.run(["git", "show", "HEAD:" + DECLARATIONS.replace(os.sep, "/")],
                              cwd=root, capture_output=True, text=True, timeout=60,
                              creationflags=NO_WINDOW)
    except (OSError, subprocess.SubprocessError):
        return None
    return done.stdout if done.returncode == 0 else ""


def adopt(root, committed):
    """Problems, or [] after recording the guards now below the point of action - ONCE."""
    held = _declarations(root)
    if held.get(ADOPTED):
        return ["the project adopted its starting tiers on %s already - adopting again would "
                "reset the debt, the cheapest way past this gate"
                % (held[ADOPTED].get("at") or "an unrecorded date")]
    if committed is None:
        return ["git could not show the committed declarations, so whether this project adopted "
                "before is UNKNOWN - and adopting twice resets the debt"]
    if ADOPTED in committed:
        return ["the COMMITTED declarations carry an adoption this copy has lost - deleting the "
                "record to adopt again is the evasion this refuses"]
    held[ADOPTED] = {"at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                     "below": below(root)}
    with io.open(os.path.join(root, DECLARATIONS), "w", encoding="utf-8", newline=LF) as handle:
        handle.write(json.dumps(held, indent=1, sort_keys=True) + LF)
    return []


def main(argv):
    root = _root()
    found = guards(root)
    if not found:
        sys.stdout.write("REFUSING: no guard found under %s - a walk that finds nothing looked in "
                         "the wrong place%s" % (", ".join(guard_dirs(root)), LF))
        return 1
    if "--adopt" in argv[1:]:
        problems = adopt(root, committed_declarations(root))
        for line in problems:
            sys.stdout.write("REFUSING: %s%s" % (line, LF))
        if not problems:
            sys.stdout.write("adopted %d guard(s) below the point of action as this project's "
                             "starting debt; it only shrinks from here%s"
                             % (len(adopted(root)), LF))
        return 1 if problems else 0
    standing = below(root)
    known = adopted(root)
    problems = excusals() + ["%s is below the point of action" % rel
                             for rel in standing if rel not in known]
    for line in problems:
        sys.stdout.write("REFUSING: %s%s" % (line, LF))
    owed = sorted(set(standing) & known)
    sys.stdout.write("%d guard(s), %d at the point of action%s%s"
                     % (len(found), len(found) - len(standing),
                        ("; %d below it adopted as the starting debt" % len(owed)) if owed else "",
                        LF))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
