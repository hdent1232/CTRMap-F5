#!/usr/bin/env python3
"""A guard that is installed but cannot be reached is a guard that is OFF.

WHY THIS EXISTS, measured on this project on 2026-09-20 rather than imagined.

`.claude/hooks/` held eight guards. `.claude/settings.json` wired seven of them under
`"matcher": "Bash"`, and five of the seven opened with

    if str(payload.get("tool_name") or "") != "Bash":
        sys.exit(0)

This session also offers a `PowerShell` tool that runs shell commands. `PowerShell` is not
`Bash`. So the heredoc refusal - the one that put NUL bytes into a committed test.ps1 - the
mutation-sweep pipe refusal, the idle-poll refusal, the mutation-read refusal and the
blocked-runner refusal were all installed, all wired, all running, and all off for every
command issued through the other tool. Nobody bypassed anything. The command was spelled
differently.

That is the project's own rule about guards at call sites, paid for by the guards themselves,
and it had already been filed four separate times before this. A matcher that names tools IS a
call-site list. So is `if tool_name != "Bash"`.

WHAT THIS REFUSES, and each one is a way the same thing happens again:

  1. a `guard_*.py` no wired command can reach - installed, forgotten, off
  2. a wiring entry whose matcher names particular tools instead of `*` - a call-site list,
     which is one new tool from off
  3. a `guard_*.py` with no `decide(payload)` - present, unaskable by the dispatcher, and
     indistinguishable from one that answered no
  4. a settings file or hooks directory it cannot read - because "I could not look" is not
     "there is nothing there", and collapsing the two is what reported twelve live workers
     gone and discarded 134 verdicts here
  5. an installed hook that differs from its version-controlled copy in `tools/hooks` - the
     installed directory is outside version control, so a hook hollowed out there leaves no
     diff, no history and no review, and goes on looking installed and wired
  6. a hook in `tools/hooks` that is not installed at all - a guard that exists only on paper

Usage: python tools/guard/hooks_wired.py [repo-root]
Exit 1 with reasons, 0 when every installed guard is reachable for every tool.
"""
import ast
import io
import json
import os
import re
import sys

#: A matcher that covers every tool. Anything else enumerates call sites.
UNIVERSAL = ("*", ".*", "", "**")

#: How a dispatcher says "I find my own guards" - a glob over the sibling files rather than a
#: list of them. A dispatcher that listed its guards would have the same defect one level up.
DISCOVERS = re.compile(r"glob[^\n]*guard_\*\.py")


#: -------- THREE REFUSALS ADOPTED FROM THE VERIFICATION BOOTSTRAP ------------------
#: Each is a defect this project has already had and fixed BY HAND, which is the definition of
#: a class left open. All three are asked of the AST rather than the text, because every one of
#: these files now carries prose explaining the defect - a text search would report the FIXED
#: files as broken, and a check that cries wolf on day one is one nobody reads by day two.

def _parsed(hooks_dir, names):
    """(path, tree) for each hook that parses, and a finding for each that does not."""
    out, trouble = [], []
    for name in names:
        path = os.path.join(hooks_dir, name)
        try:
            out.append((path, ast.parse(io.open(path, encoding="utf-8").read(), path)))
        except (OSError, SyntaxError) as bad:
            trouble.append("%s does not parse (%s) - an unreadable guard is not a clean one"
                           % (name, bad))
    return out, trouble


def hooks_that_run_on_import(hooks_dir, names):
    """A hook that acts when you import it is a hook no test and no dispatcher can reach.

    MEASURED HERE: guard_fanout.py ended with a bare `main()`. Importing it read stdin, hit a
    JSONDecodeError and exited - so the fan-out cap, which exists because three fan-outs burned
    a week of a metered plan, was the one guard the dispatcher could not ask. It was fixed by
    hand the day the dispatcher was written; nothing stopped the next one until now.
    """
    parsed, why = _parsed(hooks_dir, names)
    for path, tree in parsed:
        for node in tree.body:
            if (isinstance(node, ast.Expr) and isinstance(node.value, ast.Call)
                    and getattr(node.value.func, "id", None) == "main"):
                why.append(
                    "%s calls main() at module level (line %d), so IMPORTING it runs it - it "
                    "reads stdin and exits, and neither a test nor the dispatcher can ask it. "
                    "Put it behind `if __name__ == \"__main__\":`."
                    % (os.path.basename(path), node.lineno))
    return why


def hooks_that_spell_an_environment_name(hooks_dir, names):
    """Every environment name comes from hook_env, so one prefix moves all of them.

    MEASURED HERE on 2026-09-21: four bypasses were spelled DTENGINE_ - naming the project
    these hooks were carried from - while every other name was CTRMAP_. Setting
    CTRMAP_ALLOW_PIPE did nothing and looked like a bypass that does not work.
    """
    parsed, why = _parsed(hooks_dir, names)
    for path, tree in parsed:
        if os.path.basename(path) == "hook_env.py":
            continue
        for node in ast.walk(tree):
            if not (isinstance(node, ast.Call) and node.args):
                continue
            if getattr(node.func, "attr", None) != "get":
                continue
            if getattr(getattr(node.func, "value", None), "attr", None) != "environ":
                continue
            first = node.args[0]
            if (isinstance(first, ast.Constant) and isinstance(first.value, str)
                    and first.value not in FOREIGN_ENV):
                why.append(
                    "%s:%d reads the environment name %r directly. Spell it "
                    "`hook_env.name(...)`, so changing the prefix once changes every one - "
                    "this project shipped two prefixes at the same time."
                    % (os.path.basename(path), node.lineno, first.value))
    return why


def hooks_that_decide_by_tool_name(hooks_dir, names):
    """A hook that asks what the tool is CALLED is one new tool name from off.

    MEASURED HERE on 2026-09-20: eight hooks were wired under "matcher": "Bash" and five opened
    by exempting any tool not called Bash, in a session that also offered a PowerShell tool. All
    of them were installed, wired, running and OFF for every command issued through it.
    """
    parsed, why = _parsed(hooks_dir, names)
    for path, tree in parsed:
        for node in ast.walk(tree):
            if not isinstance(node, ast.Compare) or not node.comparators:
                continue
            against = node.comparators[0]
            if not (isinstance(against, ast.Constant) and isinstance(against.value, str)):
                continue
            if "tool_name" not in ast.dump(node.left):
                continue
            why.append(
                "%s:%d decides by the tool NAME (%r). Ask a SHAPE - shellin.commands(payload) "
                "- because a comparison that names tools is a call-site list, and it is one "
                "new tool away from silent."
                % (os.path.basename(path), node.lineno, against.value))
    return why


#: Names this project does not own and must not prefix.
FOREIGN_ENV = ("CLAUDE_PROJECT_DIR", "PATH", "PATHEXT", "TEMP", "TMP", "USERPROFILE")


def claude_dir(root):
    """The .claude directory that actually HOLDS the guards for `root`, or None.

    Checked at the root and upwards, because this repository sits inside the session folder
    that owns the hooks. It picks the first candidate holding a `hooks` directory with a
    `guard_*.py` in it and not merely the first `.claude` it meets: this repository has its
    own `.claude` with no hooks, and stopping there made the check report "cannot read the
    hooks directory" about a wiring that was in perfect order one level up. A checker that
    looks in the wrong place and says so is recoverable; one that looks in the wrong place and
    reports a clean result is the confident empty answer this project keeps paying for.
    """
    here = os.path.abspath(root)
    seen = []
    for _ in range(4):
        candidate = os.path.join(here, ".claude")
        if os.path.isdir(candidate):
            seen.append(candidate)
            hooks = os.path.join(candidate, "hooks")
            try:
                if any(n.startswith("guard_") and n.endswith(".py")
                       for n in os.listdir(hooks)):
                    return candidate
            except OSError:
                pass
        parent = os.path.dirname(here)
        if parent == here:
            break
        here = parent
    #: a .claude with no guards in it is still the wiring that governs this tree, and its
    #: emptiness is a finding rather than a reason to keep walking up out of the project.
    return seen[0] if seen else None


def _declared_event(path):
    """Which hook event a guard says it answers, or None when that cannot be read.

    A guard says `EVENT = "Stop"` at module level; one that says nothing answers
    PreToolUse, which is what every guard here did before a second event existed. Asked
    of the AST, because the word in a docstring is not a declaration - the same
    distinction that made a __name__ grep useless in the hook two directories over.
    """
    try:
        tree = ast.parse(io.open(path, encoding="utf-8", errors="replace").read())
    except (OSError, SyntaxError, ValueError):
        return None
    for node in tree.body:
        if not isinstance(node, ast.Assign):
            continue
        for target in node.targets:
            if getattr(target, "id", None) == "EVENT":
                value = getattr(node.value, "value", None)
                return value if isinstance(value, str) else None
    return "PreToolUse"


def _wired_commands(settings):
    """(commands, matchers, trouble) from a parsed settings file."""
    commands = []
    matchers = []
    trouble = []
    event_commands = []
    hooks = settings.get("hooks")
    if not isinstance(hooks, dict):
        return commands, matchers, ["settings.json has no hooks object"], event_commands
    for event, entries in hooks.items():
        if not isinstance(entries, list):
            trouble.append("hooks.%s is not a list" % event)
            continue
        for entry in entries:
            if not isinstance(entry, dict):
                trouble.append("an entry under hooks.%s is not an object" % event)
                continue
            matchers.append((event, str(entry.get("matcher", ""))))
            for hook in entry.get("hooks") or []:
                if isinstance(hook, dict) and isinstance(hook.get("command"), str):
                    commands.append(hook["command"])
                    event_commands.append((event, hook["command"]))
                else:
                    trouble.append("a hook under hooks.%s has no command string" % event)
    return commands, matchers, trouble, event_commands


def findings(root):
    """Every reason a guard here is off, or could not be shown to be on. Empty means on.

    An installation with no .claude at all reports that as its one finding rather than
    passing: a checker that reads "nothing installed" as "nothing wrong" is the confident
    empty result this project has been burned by twice.
    """
    where = claude_dir(root)
    if where is None:
        return ["INSTALLED NOWHERE: no .claude directory at or above %s, so this could not "
                "look at the wiring at all. That is not the same as a clean wiring."
                % os.path.abspath(root)]

    hooks_dir = os.path.join(where, "hooks")
    settings_path = os.path.join(where, "settings.json")
    why = []

    try:
        installed = sorted(name for name in os.listdir(hooks_dir)
                           if name.startswith("guard_") and name.endswith(".py"))
    except OSError as cannotList:
        return ["cannot read %s (%s) - an unreadable hooks directory is not an empty one"
                % (hooks_dir, cannotList)]
    if not installed:
        return ["no guard_*.py in %s - either nothing is installed, or this is looking in "
                "the wrong place. Both are refusals." % hooks_dir]

    try:
        settings = json.load(io.open(settings_path, encoding="utf-8"))
    except (OSError, ValueError) as cannotRead:
        return ["cannot read %s (%s) - so which guards are wired is UNKNOWN, which is not "
                "the same as all of them" % (settings_path, cannotRead)]

    commands, matchers, trouble, event_commands = _wired_commands(settings)
    why.extend(trouble)

    #: which wired commands are dispatchers that find their own guards
    dispatchers = []
    for command in commands:
        found = re.findall(r"[\w./$\\{}-]*guard_[\w.-]+\.py", command)
        for named in found:
            base = os.path.basename(named)
            path = os.path.join(hooks_dir, base)
            try:
                body = io.open(path, encoding="utf-8", errors="replace").read()
            except OSError:
                why.append("the wiring runs %s, which is not in %s" % (base, hooks_dir))
                continue
            if DISCOVERS.search(body):
                dispatchers.append(base)

    named_directly = set()
    for command in commands:
        for named in re.findall(r"guard_[\w.-]+\.py", command):
            named_directly.add(named)

    for name in installed:
        if name in named_directly or dispatchers:
            continue
        why.append(
            "%s is installed in %s and NOTHING IN THE WIRING RUNS IT. An installed guard that "
            "is never asked is off, and looks exactly like one with nothing to refuse."
            % (name, hooks_dir))

    #: A GUARD THAT ANSWERS A DIFFERENT EVENT MUST BE WIRED TO THAT EVENT. Until a Stop
    #: hook existed every guard here answered PreToolUse and one dispatcher covered them
    #: all - so the reachability test above passes anything at all once a dispatcher is
    #: present. A hook declaring EVENT = "Stop" that nobody wired to Stop sails through
    #: it: installed, never asked, indistinguishable from one with nothing to refuse.
    #: That is the shape this whole file was written for, one event along.
    for name in installed:
        declared = _declared_event(os.path.join(hooks_dir, name))
        if declared in (None, "PreToolUse"):
            continue
        if not [c for event, c in event_commands if event == declared and name in c]:
            why.append(
                "%s says it answers the %s event and NOTHING IN THE WIRING RUNS IT THERE."
                " The dispatcher only covers PreToolUse, so this guard is installed and"
                " never asked - wire it under hooks.%s, or it is decoration."
                % (name, declared, declared))

    #: a matcher that names tools is a call-site list
    for event, matcher in matchers:
        if matcher not in UNIVERSAL:
            why.append(
                "the %s entry is wired for %r only. A matcher that names tools is a CALL-SITE "
                "LIST: on 2026-09-20 seven guards here were wired for \"Bash\" while the "
                "session also had a PowerShell tool, and every one of them was off for every "
                "command issued through it. Use \"*\" and let each guard decide."
                % (event, matcher))

    why.extend(_differs_from_the_versioned_copy(root, hooks_dir))
    #: the three adopted refusals, over every installed hook
    every = sorted(n for n in os.listdir(hooks_dir) if n.endswith(".py"))
    why.extend(hooks_that_run_on_import(hooks_dir, every))
    why.extend(hooks_that_spell_an_environment_name(hooks_dir, every))
    why.extend(hooks_that_decide_by_tool_name(hooks_dir, every))

    #: a guard the dispatcher cannot ask
    if dispatchers:
        for name in installed:
            if name in dispatchers:
                continue
            try:
                body = io.open(os.path.join(hooks_dir, name), encoding="utf-8",
                               errors="replace").read()
            except OSError as cannotRead:
                why.append("cannot read %s (%s)" % (name, cannotRead))
                continue
            if "def decide(" not in body:
                why.append(
                    "%s has no decide(payload), so the dispatcher cannot ask it. A guard that "
                    "is present and unaskable answers nothing, which reads as a pass." % name)
    return why


#: Where the version-controlled copy of each hook lives, relative to the repository root. The
#: installed copy in `.claude/hooks` is outside version control entirely, so a hook edited
#: there leaves no trace anywhere: no diff, no history, and a suite that read the installed
#: copy would simply agree with whatever it now says.
VERSIONED = os.path.join("tools", "hooks")


def _repo_root(root):
    """The repository holding tools/hooks, at `root` or one level down."""
    if os.path.isdir(os.path.join(root, VERSIONED)):
        return root
    try:
        for name in sorted(os.listdir(root)):
            if os.path.isdir(os.path.join(root, name, VERSIONED)):
                return os.path.join(root, name)
    except OSError:
        pass
    return None


def _text(path):
    """The file with line endings normalised, or None when it cannot be read.

    Normalised because this tree is checked out with autocrlf, so the working copy of a
    versioned file legitimately differs from the installed one by line endings alone. The
    project's own build digest ignores endings for text for exactly this reason; a check that
    did not would cry wolf on every clone and be switched off within a day.
    """
    try:
        with open(path, "rb") as handle:
            return handle.read().replace(b"\r\n", b"\n")
    except OSError:
        return None


def _differs_from_the_versioned_copy(root, hooks_dir):
    """Every installed hook that is not what the repository says it is.

    A HOOK EDITED IN PLACE LEAVES NO TRACE. `.claude/hooks` is not in version control: an edit
    there has no diff, no history and no review, and the guard goes on looking installed and
    wired. This is the half that makes the versioned copy mean anything.
    """
    repo = _repo_root(root)
    if repo is None:
        return ["there is no %s in or under %s, so what the installed hooks are SUPPOSED to "
                "say is unknown - and an unknown is not a match"
                % (VERSIONED, os.path.abspath(root))]
    versioned_dir = os.path.join(repo, VERSIONED)
    try:
        versioned = sorted(name for name in os.listdir(versioned_dir)
                           if name.endswith((".py", ".txt")))
        beside = sorted(name for name in os.listdir(hooks_dir)
                        if name.endswith((".py", ".txt")))
    except OSError as cannotList:
        return ["cannot list the hooks to compare them (%s)" % cannotList]

    why = []
    for name in beside:
        want = _text(os.path.join(versioned_dir, name))
        if want is None:
            why.append("%s is installed in %s but has no version-controlled copy in %s. A hook "
                       "edited in place leaves no diff and no history."
                       % (name, hooks_dir, VERSIONED))
            continue
        got = _text(os.path.join(hooks_dir, name))
        if got is None:
            why.append("cannot read the installed %s" % name)
        elif got != want:
            why.append("%s as installed differs from %s/%s (%d bytes vs %d). Whichever is "
                       "right, they are not the same guard, and only one of them runs."
                       % (name, VERSIONED, name, len(got), len(want)))
    for name in versioned:
        if name not in beside:
            why.append("%s/%s is in version control but is NOT installed in %s - a guard that "
                       "exists only on paper" % (VERSIONED, name, hooks_dir))
    return why


def main(argv):
    root = argv[1] if len(argv) > 1 else "."
    why = findings(root)
    if not why:
        where = claude_dir(root)
        count = len([n for n in os.listdir(os.path.join(where, "hooks"))
                     if n.startswith("guard_") and n.endswith(".py")])
        print("every one of the %d installed guards is reachable for every tool" % count)
        return 0
    print("THE WIRING DOES NOT REACH EVERY GUARD:")
    for reason in why:
        print("  - " + reason)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
